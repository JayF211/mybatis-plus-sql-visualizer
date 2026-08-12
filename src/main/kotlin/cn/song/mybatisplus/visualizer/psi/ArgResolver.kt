package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.NamingStrategy
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
     * @return WrapperArg 模型
     */
    fun resolve(expression: PsiExpression, table: TableInfo?, entityClass: String?): WrapperArg = when (expression) {
        // Lambda 方法引用：Entity::getXxx → 列引用
        is PsiMethodReferenceExpression ->
            WrapperArg.ColumnArg(resolveLambdaColumn(expression, table))

        // MPJ on(col1, col2)：两列比较实参（F-11）
        is PsiMethodCallExpression -> resolveMethodCallArg(expression, table)

        // 字面量：直接求值
        is PsiLiteralExpression ->
            WrapperArg.ValueArg(ExpressionEvaluator.evaluate(expression))

        // Lambda 表达式体：and(w -> w.eq(...)) → 嵌套链
        is PsiLambdaExpression ->
            WrapperArg.NestedArg(resolveNestedLambda(expression, table, entityClass))

        // 变量 / 算术 / new 表达式：求值为占位符
        else ->
            WrapperArg.ValueArg(ExpressionEvaluator.evaluate(expression))
    }

    /**
     * 解析方法调用实参：MPJ 的 `on(col1, col2)` → [WrapperArg.OnConditionArg]。
     * 其他方法调用（如集合工具调用）→ 占位符。
     */
    private fun resolveMethodCallArg(call: PsiMethodCallExpression, table: TableInfo?): WrapperArg {
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
        return WrapperArg.ValueArg(ExpressionEvaluator.evaluate(call))
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
     * getter 方法名转属性名：`getShareSchemeId` → `shareSchemeId`，`isEnabled` → `enabled`。
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
     */
    internal fun toWrapperCall(call: PsiMethodCallExpression, table: TableInfo?, entityClass: String?): WrapperCall {
        val methodName = call.methodExpression.referenceName ?: call.methodExpression.text
        val args = call.argumentList.expressions.map { resolve(it, table, entityClass) }
        return WrapperCall(methodName, args)
    }
}
