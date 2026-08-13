package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil

/**
 * 实参解析器：把 Wrapper 方法实参 [PsiExpression] 解析为 [WrapperArg]。
 *
 * 处理四种实参：
 *  - 方法引用 `Entity::getXxx` → [WrapperArg.ColumnArg]（列引用，F-13 Lambda 解析）
 *  - 字面量 / 变量 / 表达式 → [WrapperArg.ValueArg]（[ExpressionEvaluator] 求值）
 *  - Lambda `w -> w.eq(...)` → [WrapperArg.NestedArg]（and/or 嵌套，递归解析）
 *  - 其他（new 表达式、集合等）→ [WrapperArg.ValueArg] 占位
 */
object ArgResolver {

    /**
     * 解析单个实参表达式。
     *
     * @param expression     实参（PSI 表达式）
     * @param table          实体表信息（解析 Lambda 列引用用，可为 null）
     * @param entityClass    外层实体类名（嵌套链填充用，可为 null）
     * @param asAlias        true 表示该位置是 MPJ selectAs 的目标 VO 字段（方法引用 → [WrapperArg.AliasArg]，
     *                       保留驼峰属性名作别名，不做 snake_case 列名转换）
     * @param resolveVariables 变量求值开关（M14.6「替换 ? 占位符」配置）：false 时跳过
     *                         [ExpressionEvaluator] 变量追踪，可静态求值的变量也渲染为 `?`；
     *                         字面量（代码直接写的常量）恒内联，不受此开关影响
     * @return WrapperArg 模型
     */
    fun resolve(
        expression: PsiExpression,
        table: TableInfo?,
        entityClass: String?,
        asAlias: Boolean = false,
        resolveVariables: Boolean = true,
    ): WrapperArg {
        // selectAs(Entity::getX, VO::getY)：第二参方法引用 → 目标 VO 属性名（别名）
        if (asAlias && expression is PsiMethodReferenceExpression) {
            val name = expression.referenceName ?: return WrapperArg.ValueArg(SqlValue.Placeholder(expression.text))
            val property = getterToProperty(name)
            return if (property != null) {
                WrapperArg.AliasArg(property)
            } else {
                WrapperArg.ValueArg(SqlValue.Placeholder(expression.text))
            }
        }
        return when (expression) {
        // Lambda 方法引用：Entity::getXxx → 列引用
        is PsiMethodReferenceExpression ->
            WrapperArg.ColumnArg(resolveLambdaColumn(expression, table))

        // MPJ on(col1, col2)：两列比较实参（F-11）
        is PsiMethodCallExpression -> resolveMethodCallArg(expression, table, resolveVariables)

        // 字面量：直接求值（不受「替换 ? 占位符」开关影响，代码常量恒内联）
        is PsiLiteralExpression ->
            WrapperArg.ValueArg(ExpressionEvaluator.evaluate(expression))

        // Lambda 表达式体：and(w -> w.eq(...)) → 嵌套链
        is PsiLambdaExpression ->
            WrapperArg.NestedArg(resolveNestedLambda(expression, table, entityClass))

        // 变量 / 算术 / new 表达式：求值为字面量或占位符（开关关闭时恒为占位符）
        else ->
            resolveValueArg(expression, resolveVariables)
        }
    }

    /**
     * 非字面量实参求值（M14.6）：resolveVariables=false 时跳过变量追踪，直接渲染 `?`（保留表达式原文作 source）。
     */
    private fun resolveValueArg(expression: PsiExpression, resolveVariables: Boolean): WrapperArg =
        WrapperArg.ValueArg(
            if (resolveVariables) ExpressionEvaluator.evaluate(expression)
            else SqlValue.Placeholder(expression.text),
        )

    /**
     * 解析方法调用实参：MPJ 的 `on(col1, col2)` → [WrapperArg.OnConditionArg]。
     * 其他方法调用（如集合工具调用）→ 占位符（受 resolveVariables 开关控制）。
     */
    private fun resolveMethodCallArg(call: PsiMethodCallExpression, table: TableInfo?, resolveVariables: Boolean): WrapperArg {
        val methodName = call.methodExpression.referenceName
        if (methodName == ON_METHOD) {
            val args = call.argumentList.expressions
            if (args.size >= 2) {
                val left = (args[0] as? PsiMethodReferenceExpression)?.let { resolveLambdaColumn(it, table) }
                val right = (args[1] as? PsiMethodReferenceExpression)?.let { resolveLambdaColumn(it, table) }
                if (left != null && right != null) {
                    return WrapperArg.OnConditionArg(left, right)
                }
            }
        }
        return resolveValueArg(call, resolveVariables)
    }

    /** MPJ on 函数名。 */
    private const val ON_METHOD = "on"

    // ------------------------------------------------------------------
    // Lambda 方法引用 → 列
    // ------------------------------------------------------------------

    /**
     * 解析 `Entity::getXxx` / `Entity::isXxx` 方法引用为列引用。
     *
     * getter 名 → 属性名 → 查实体字段映射；无实体信息时按命名策略兜底推断。
     */
    private fun resolveLambdaColumn(methodRef: PsiMethodReferenceExpression, table: TableInfo?): ColumnRef {
        val methodName = methodRef.referenceName ?: return ColumnRef(columnName = methodRef.text)
        val propertyName = getterToProperty(methodName) ?: return ColumnRef(columnName = methodName)

        // 优先从实体字段映射取列名
        val mapped = table?.columnByField(propertyName)?.columnName
        val columnName = mapped ?: NamingStrategy.toSnakeCase(propertyName)
        return ColumnRef(columnName = columnName)
    }

    /**
     * getter 方法名转属性名：`getStationId` → `shareSchemeId`，`isEnabled` → `enabled`。
     * 非 getter/is 前缀返回 null。
     */
    private fun getterToProperty(methodName: String): String? {
        val prefix = when {
            methodName.startsWith("get") && methodName.length > 3 -> "get"
            methodName.startsWith("is") && methodName.length > 2 -> "is"
            else -> return null
        }
        val name = methodName.removePrefix(prefix)
        // 首字母小写
        return name.replaceFirstChar { it.lowercaseChar() }
    }

    // ------------------------------------------------------------------
    // Lambda 体 → 嵌套调用链
    // ------------------------------------------------------------------

    /**
     * 解析 `and(w -> w.eq(...).like(...))` 的 Lambda 体为嵌套调用链。
     *
     * 支持两种体：
     *  - 表达式体（方法调用链）：直接收集链
     *  - 代码块体：收集块内全部方法调用（按源码顺序）
     */
    private fun resolveNestedLambda(lambda: PsiLambdaExpression, table: TableInfo?, entityClass: String?): WrapperChain {
        val body = lambda.body ?: return WrapperChain(WrapperType.NESTED, entityClass, emptyList())
        val calls = mutableListOf<PsiMethodCallExpression>()

        when (body) {
            // 表达式体：w.eq(...).like(...)
            is PsiMethodCallExpression -> calls += collectCallChain(body)

            // 代码块体：收集块内所有方法调用
            else -> PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
                .sortedBy { it.textOffset }
                .forEach { calls += it }
        }

        val wrapperCalls = calls
            .distinctBy { it.textRange.endOffset }
            .map { toWrapperCall(it, table, entityClass) }
        return WrapperChain(WrapperType.NESTED, entityClass, wrapperCalls)
    }

    // ------------------------------------------------------------------
    // 调用链收集
    // ------------------------------------------------------------------

    /**
     * 从链尾调用沿 qualifier 回溯收集整条链（含自身）。
     *
     * 如 `new W().eq(a).like(b)` 传入 like 调用 → [eq, like]。
     */
    internal fun collectCallChain(call: PsiMethodCallExpression): List<PsiMethodCallExpression> {
        val chain = mutableListOf<PsiMethodCallExpression>()
        var current: PsiMethodCallExpression? = call
        while (current != null) {
            chain.add(0, current)
            current = current.methodExpression.qualifierExpression as? PsiMethodCallExpression
        }
        return chain
    }

    /**
     * 收集初始化表达式中的链式调用（new LambdaQueryWrapper<>().eq(...) 部分）。
     *
     * 找出所有"不是其他调用 qualifier"的链尾调用，各自回溯整链。
     * 注意：跳过 lambda 体内的调用（如 `and(x -> x.eq(...))` 中的 x.eq），
     * 它们由 [resolveNestedLambda] 作为嵌套链解析，不应混入外层链。
     */
    internal fun collectChainFromInitializer(initializer: PsiElement): List<PsiMethodCallExpression> {
        val result = mutableListOf<PsiMethodCallExpression>()
        PsiTreeUtil.collectElementsOfType(initializer, PsiMethodCallExpression::class.java)
            .filter { call ->
                // 跳过 lambda 体内的调用
                if (PsiTreeUtil.getParentOfType(call, PsiLambdaExpression::class.java) != null) {
                    return@filter false
                }
                // 链尾：其父不是另一调用的 qualifier 引用
                val parent = call.parent
                !(parent is PsiReferenceExpression && parent.parent is PsiMethodCallExpression)
            }
            .forEach { chainTail -> result += collectCallChain(chainTail) }
        return result
    }

    /**
     * 把 PsiMethodCallExpression 转为模型 [WrapperCall]。
     *
     * MPJ 特殊参数位置：
     *  - `selectAs(Entity::getX, VO::getY)` / `selectCount(...)` 第二参按「目标 VO 字段」解析（[WrapperArg.AliasArg]）
     *  - `selectFunc(模板, func -> func.accept(列).values(值), 别名)` 第二参按「聚合函数」解析（[WrapperArg.FuncArg]）
     * 其余参数按常规实参解析。
     */
    internal fun toWrapperCall(
        call: PsiMethodCallExpression,
        table: TableInfo?,
        entityClass: String?,
        resolveVariables: Boolean = true,
    ): WrapperCall {
        val methodName = call.methodExpression.referenceName ?: call.methodExpression.text
        val args = call.argumentList.expressions.mapIndexed { index, expr ->
            when {
                methodName in JOIN_METHODS && index == 0 ->
                    resolveJoinClass(expr, resolveVariables)
                methodName == SELECT_AS_CLASS ->
                    resolveJoinClass(expr, resolveVariables)
                methodName in ALIAS_SECOND_ARG_METHODS && index == 1 ->
                    resolve(expr, table, entityClass, asAlias = true, resolveVariables = resolveVariables)
                methodName == SELECT_FUNC || methodName == APPLY_FUNC && index == 1 ->
                    resolveFuncLambda(expr, table)
                else -> resolve(expr, table, entityClass, resolveVariables = resolveVariables)
            }
        }
        return WrapperCall(methodName, args)
    }

    /** MPJ join 方法名集合（第一参为关联实体类引用；join 为自定义关键字形态，第一参为关键字字符串）。 */
    private val JOIN_METHODS = setOf("leftJoin", "innerJoin", "rightJoin", "fullJoin", "join")

    /**
     * 解析 join 的关联实体引用：`leftJoin(XxxEntity.class, ...)` → [WrapperArg.EntityRefArg]（F-13 关联实体注解解析）。
     * 无法解析为实体类时降级为类名字面量（converter 按命名策略兜底）。
     */
    private fun resolveJoinClass(expr: PsiExpression, resolveVariables: Boolean): WrapperArg {
        val classAccess = expr as? PsiClassObjectAccessExpression
        val psiClass = (classAccess?.operand?.type as? PsiClassType)?.resolve() as? PsiClass
        return if (psiClass != null) {
            WrapperArg.EntityRefArg(EntityResolver().resolve(psiClass))
        } else {
            resolveValueArg(expr, resolveVariables)
        }
    }

    /** MPJ selectAs 方法名（第二参为别名位置）。 */
    private const val SELECT_AS = "selectAs"

    /** MPJ selectFunc / applyFunc 方法名（第二参为 func -> func.accept(...).values(...) lambda）。 */
    private const val SELECT_FUNC = "selectFunc"
    private const val APPLY_FUNC = "applyFunc"

    /** MPJ selectAsClass 方法名（两个实参均为实体类引用，converter 按字段交集映射）。 */
    private const val SELECT_AS_CLASS = "selectAsClass"

    /** MPJ 中第二参为「目标 VO 字段/别名」的方法名集合（selectAs、selectCount、selectSum/Min/Max/Avg/Len）。 */
    private val ALIAS_SECOND_ARG_METHODS = setOf(
        "selectAs", "selectCount", "selectSum", "selectMin", "selectMax", "selectAvg", "selectLen",
    )

    /**
     * 解析 selectFunc 的聚合 lambda：`func -> func.accept(列1, 列2).values(值1, 值2)`。
     *
     * 收集 accept 的列引用与 values 的绑定参数值（清单 P09）。
     * 实参不是 lambda（异常代码）时降级为占位值。
     */
    private fun resolveFuncLambda(expression: PsiExpression, table: TableInfo?): WrapperArg {
        val lambda = expression as? PsiLambdaExpression
            ?: return WrapperArg.ValueArg(ExpressionEvaluator.evaluate(expression))
        val body = lambda.body
        val calls = if (body is PsiMethodCallExpression) collectCallChain(body) else emptyList()
        var columns = emptyList<ColumnRef>()
        var values = emptyList<SqlValue>()
        for (call in calls) {
            when (call.methodExpression.referenceName) {
                "accept" -> columns = call.argumentList.expressions.mapNotNull { expr ->
                    (expr as? PsiMethodReferenceExpression)?.let { resolveLambdaColumn(it, table) }
                }
                "values" -> values = call.argumentList.expressions.map { ExpressionEvaluator.evaluate(it) }
            }
        }
        return WrapperArg.FuncArg(columns, values)
    }
}
