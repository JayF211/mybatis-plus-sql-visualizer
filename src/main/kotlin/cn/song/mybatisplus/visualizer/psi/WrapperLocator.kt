package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * 定位结果：调用链 + 实体表信息。
 *
 * @param chain Wrapper 调用链
 * @param table 实体解析的表信息（实体类缺失时为 null）
 */
data class LocatedWrapper(
    val chain: WrapperChain,
    val table: TableInfo?,
)

/**
 * Wrapper 定位器（M1-02，F-01 入口）：从光标所在元素定位 Wrapper 调用链。
 *
 * 支持三种写法（对照 docs/项目介绍.md）：
 *  1. 链式：`new LambdaQueryWrapper<Entity>().eq(...).like(...)`
 *  2. 分步：`LambdaQueryWrapper<Entity> wrapper = new LambdaQueryWrapper<>(); wrapper.eq(...)`
 *  3. 混合：初始化链 + 后续分步调用
 *
 * 当前实现聚焦 Java PSI（项目示例为 Java 代码）；Kotlin 文件支持列入后续里程碑。
 */
object WrapperLocator {

    /**
     * 从锚点元素定位并解析 Wrapper 调用链。
     *
     * @param anchor 光标所在元素（编辑器 PSI 元素）
     * @return 调用链模型；未找到 Wrapper 或类型不支持时返回 null
     */
    fun locate(anchor: PsiElement): WrapperChain? = locateWithTable(anchor)?.chain

    /**
     * 定位并同时返回实体表信息（供 converter 全链路使用）。
     *
     * 支持两类 Wrapper 来源：
     *  - 变量声明（`LambdaQueryWrapper<X> w = ...`，含分步续链）
     *  - 内联 new 表达式（`list(new MPJLambdaQueryWrapper<X>().select(...).eq(...))`，无变量）
     */
    fun locateWithTable(anchor: PsiElement): LocatedWrapper? {
        findWrapperVariable(anchor)?.let { return buildChain(it) }
        findWrapperNewExpression(anchor)?.let { return buildChainFromNew(it) }
        findWrapperFactoryCall(anchor)?.let { return buildChainFromFactory(it) }
        return null
    }

    // ------------------------------------------------------------------
    // 定位 Wrapper 变量
    // ------------------------------------------------------------------

    /**
     * 从锚点向上查找 Wrapper 类型变量。
     *
     * 查找路径：
     *  - 锚点本身/父链上的局部变量或字段（类型为 Wrapper）
     *  - 锚点在方法调用上时，其 qualifier 引用的变量
     */
    private fun findWrapperVariable(anchor: PsiElement): PsiVariable? {
        var current: PsiElement? = anchor
        while (current != null) {
            when (current) {
                is PsiLocalVariable -> if (detectWrapperType(current.type) != null) return current
                is PsiField -> if (detectWrapperType(current.type) != null) return current
                is PsiMethodCallExpression -> {
                    val qualifier = current.methodExpression.qualifierExpression as? PsiReferenceExpression
                    val resolved = qualifier?.resolve() as? PsiVariable
                    if (resolved != null && detectWrapperType(resolved.type) != null) return resolved
                }
            }
            current = current.parent
        }
        return null
    }

    /**
     * 从锚点查找内联 Wrapper 构造表达式（`list(new MPJLambdaQueryWrapper<X>().eq(...))`，清单 P17）。
     * 锚点可能在 new 表达式链内（向上命中），也可能在包裹它的外层调用上（向下检查子树）。
     */
    private fun findWrapperNewExpression(anchor: PsiElement): PsiNewExpression? {
        var current: PsiElement? = anchor
        while (current != null) {
            // 向下：锚点子树中的 wrapper new 表达式（如 list(new Wrapper<X>()...))
            PsiTreeUtil.findChildOfType(current, PsiNewExpression::class.java)?.let { newExpr ->
                if (detectWrapperType(newExpr.type) != null) return newExpr
            }
            // 向上：锚点自身为 wrapper new 表达式（或在 new 链的调用上）
            if (current is PsiNewExpression && detectWrapperType(current.type) != null) return current
            current = current.parent
        }
        return null
    }

    /**
     * 从内联 new 表达式构建调用链（无变量声明场景）。
     *
     * 收集以该 new 表达式为链起点的所有链式调用（`new W().select(...).eq(...)`），
     * 按源码结束位置排序。
     */
    private fun buildChainFromNew(newExpr: PsiNewExpression): LocatedWrapper? {
        val wrapperType = detectWrapperType(newExpr.type) ?: return null
        val entityClass = resolveEntityClass(newExpr.type)
        val table: TableInfo? = entityClass?.let { EntityResolver().resolve(it) }
        val constructorAlias = extractConstructorAlias(newExpr)

        // 宿主 = 包含该 new 表达式的最外层表达式（如 list(new W().select(...).eq(...)) 的整个调用）
        var host: PsiElement = newExpr
        var cursor: PsiElement? = newExpr.parent
        while (cursor != null) {
            if (cursor is PsiExpression) host = cursor
            cursor = cursor.parent
        }
        val wrapperCalls = PsiTreeUtil.collectElementsOfType(host, PsiMethodCallExpression::class.java)
            .filterNot { PsiTreeUtil.getParentOfType(it, PsiLambdaExpression::class.java) != null }
            .filter { call ->
                // 链首的 qualifier 是 newExpr（即以该 new 为起点的 wrapper 链）
                ArgResolver.collectCallChain(call).first().methodExpression.qualifierExpression === newExpr
            }
            .distinctBy { it.textRange.endOffset }
            .sortedBy { it.textRange.endOffset }
            .map { ArgResolver.toWrapperCall(it, table, entityClass?.name) }

        return LocatedWrapper(
            chain = WrapperChain(wrapperType, entityClass?.name, wrapperCalls, constructorAlias),
            table = table,
        )
    }

    // ------------------------------------------------------------------
    // Wrappers 静态工厂链（getOne(Wrappers.<T>lambdaQuery().select(...).eq(...)) 等）
    // ------------------------------------------------------------------

    /**
     * 从锚点查找内联 Wrapper 工厂调用（`getOne(Wrappers.<T>lambdaQuery().select(...).eq(...))`）。
     *
     * `Wrappers.<T>lambdaQuery()` 是静态工厂调用，不产生 `new` 表达式、也不一定绑定 Wrapper 类型变量，
     * 需单独识别。查找路径与 [findWrapperNewExpression] 相同：向下扫锚点子树、向上逐层检查。
     */
    private fun findWrapperFactoryCall(anchor: PsiElement): PsiMethodCallExpression? {
        var current: PsiElement? = anchor
        while (current != null) {
            // 向下：扫锚点子树内全部方法调用（工厂调用可能在嵌套链内部，
            // 如 getOne(Wrappers.<T>lambdaQuery()...)，findChildOfType 只会命中最外层调用）
            PsiTreeUtil.collectElementsOfType(current, PsiMethodCallExpression::class.java)
                .firstOrNull { isWrapperFactoryCall(it) }
                ?.let { return it }
            // 向上：锚点自身为工厂调用（或链上的调用）
            if (current is PsiMethodCallExpression && isWrapperFactoryCall(current)) return current
            current = current.parent
        }
        return null
    }

    /**
     * 从内联工厂调用构建调用链（无变量声明场景）。
     *
     * 收集以该工厂调用为链起点的所有链式调用（`Wrappers.<T>lambdaQuery().select(...).eq(...)`），
     * 按源码结束位置排序；工厂调用自身（非 Wrapper 操作）不入链。
     */
    private fun buildChainFromFactory(factoryCall: PsiMethodCallExpression): LocatedWrapper? {
        val wrapperType = factoryWrapperType(factoryCall) ?: return null
        val entityClass = resolveFactoryEntityClass(factoryCall)
        val table: TableInfo? = entityClass?.let { EntityResolver().resolve(it) }

        // 宿主 = 包含该工厂调用的最外层表达式（如 getOne(Wrappers.<T>lambdaQuery()...) 的整个调用）
        var host: PsiElement = factoryCall
        var cursor: PsiElement? = factoryCall.parent
        while (cursor != null) {
            if (cursor is PsiExpression) host = cursor
            cursor = cursor.parent
        }
        val wrapperCalls = PsiTreeUtil.collectElementsOfType(host, PsiMethodCallExpression::class.java)
            .filterNot { PsiTreeUtil.getParentOfType(it, PsiLambdaExpression::class.java) != null }
            .filter { call ->
                // 链起点是工厂调用（沿 qualifier 回溯命中）；排除工厂调用自身
                call !== factoryCall && ArgResolver.collectCallChain(call).any { it === factoryCall }
            }
            .distinctBy { it.textRange.endOffset }
            .sortedBy { it.textRange.endOffset }
            .map { ArgResolver.toWrapperCall(it, table, entityClass?.name) }

        return LocatedWrapper(
            chain = WrapperChain(wrapperType, entityClass?.name, wrapperCalls, constructorAlias = null),
            table = table,
        )
    }

    /**
     * 识别 Wrappers 静态工厂调用：`Wrappers.<T>lambdaQuery()`（qualifier 指向 Wrappers 工具类）。
     */
    private fun isWrapperFactoryCall(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName
        if (methodName !in WRAPPER_FACTORY_METHODS) return false
        val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return false
        val resolved = qualifier.resolve()
        return when (resolved) {
            is PsiClass -> resolved.name == "Wrappers"
            else -> qualifier.referenceName == "Wrappers"
        }
    }

    /** Wrappers 静态工厂方法 → Wrapper 类型映射。 */
    private fun factoryWrapperType(call: PsiMethodCallExpression): WrapperType? =
        when (call.methodExpression.referenceName) {
            "lambdaQuery" -> WrapperType.LAMBDA_QUERY
            "lambdaUpdate" -> WrapperType.LAMBDA_UPDATE
            "lambdaDelete" -> WrapperType.LAMBDA_DELETE
            "query" -> WrapperType.QUERY
            "update" -> WrapperType.UPDATE
            else -> null
        }

    /**
     * 解析工厂调用的实体类：优先取显式类型实参（`Wrappers.<MerchantEntity>lambdaQuery()` 的 `<MerchantEntity>`），
     * 失败时回退调用返回类型（真实项目 Wrappers 可解析时）。
     */
    private fun resolveFactoryEntityClass(call: PsiMethodCallExpression): PsiClass? {
        (call.typeArguments.firstOrNull() as? PsiClassType)?.let { return it.resolve() }
        return resolveEntityClass(call.type)
    }

    // ------------------------------------------------------------------
    // 构建调用链
    // ------------------------------------------------------------------

    /**
     * 从 Wrapper 变量构建完整调用链。
     *
     * 调用来源（按源码 offset 合并排序、去重）：
     *  - 变量初始化表达式中的链式调用（new W().eq(...)）
     *  - 变量后续引用上的方法调用（wrapper.eq(...)）
     */
    private fun buildChain(variable: PsiVariable): LocatedWrapper? {
        val wrapperType = detectWrapperType(variable.type) ?: return null
        val entityClass = resolveEntityClass(variable.type)
        val table: TableInfo? = entityClass?.let { EntityResolver().resolve(it) }
        val constructorAlias = extractConstructorAlias(variable.initializer)

        val callElements = mutableListOf<PsiMethodCallExpression>()

        // 1. 初始化表达式中的链式调用；若链首为工厂方法调用（baseJoinWrapper()），展开其方法体内的 wrapper 链（P03）；
        //    Wrappers 静态工厂调用（Wrappers.<T>lambdaQuery()）不是 Wrapper 操作，从链中剔除
        variable.initializer?.let { init ->
            val initCalls = ArgResolver.collectChainFromInitializer(init).dropWhile { isWrapperFactoryCall(it) }
            callElements += expandFactoryCalls(initCalls)
        }

        // 2. 变量引用上的方法调用（分步写法）
        ReferencesSearch.search(variable).forEach { ref ->
            val refExpression = ref.element as? PsiReferenceExpression ?: return@forEach
            // w 引用的 PSI 结构：w.eq(...) 中 w 引用 → methodExpression(eq 引用) → PsiMethodCallExpression
            // 向上遍历找最近的调用表达式
            val call = generateSequence(refExpression.parent) { it.parent }
                .filterIsInstance<PsiMethodCallExpression>()
                .firstOrNull() ?: return@forEach
            // 仅收集 qualifier 是该引用的调用（wrapper.eq(...) 而非方法实参中的引用）
            if (call.methodExpression.qualifierExpression === refExpression) {
                callElements += call
            }
        }

        // 合并去重（注意：链式调用表达式的 textOffset 可能相同——都从 new 起始，
        // 必须用 endOffset 区分），按源码结束位置排序
        val sortedCalls = callElements
            .distinctBy { it.textRange.endOffset }
            .sortedBy { it.textRange.endOffset }

        val wrapperCalls = sortedCalls.map { ArgResolver.toWrapperCall(it, table, entityClass?.name) }
        return LocatedWrapper(
            chain = WrapperChain(wrapperType, entityClass?.name, wrapperCalls, constructorAlias),
            table = table,
        )
    }

    /**
     * 提取表别名构造的别名（清单 C4/P11）：`new MPJLambdaWrapper<>(Entity.class, "apply_info")` 的第二参。
     * 非两参构造或无字符串第二参时返回 null（使用默认主表别名）。
     */
    private fun extractConstructorAlias(initializer: PsiElement?): String? {
        val newExpr = when (initializer) {
            is PsiNewExpression -> initializer
            else -> initializer?.let { PsiTreeUtil.findChildOfType(it, PsiNewExpression::class.java) }
        } ?: return null
        val args = newExpr.argumentList?.expressions ?: return null
        if (args.size < 2) return null
        val alias = (args[1] as? PsiLiteralExpression)?.value as? String
        return alias?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // 跨方法工厂链（清单 P03/P16）：baseJoinWrapper() 返回 wrapper，调用方继续续链
    // ------------------------------------------------------------------

    /**
     * 展开工厂方法调用：当初始化链首元素是「非构造的独立方法调用」（如 `baseJoinWrapper()`）时，
     * 通过 [PsiMethodCallExpression.resolveMethod] 找到方法定义，解析其 `return` 表达式中的 wrapper 链，
     * 返回「工厂内部链 + 续链」的完整调用列表。
     *
     * 非工厂调用（new 构造链）或解析失败时原样返回。
     */
    private fun expandFactoryCalls(calls: List<PsiMethodCallExpression>): List<PsiMethodCallExpression> {
        if (calls.isEmpty()) return calls
        val first = calls.first()
        val qualifier = first.methodExpression.qualifierExpression
        val isFactoryCall = qualifier == null && first.methodExpression.referenceName != null &&
            !(first is PsiNewExpression)
        if (!isFactoryCall) return calls

        val method = first.resolveMethod() ?: return calls
        val factoryChain = findFactoryReturnChain(method)
        if (factoryChain.isEmpty()) return calls
        return factoryChain + calls.drop(1)
    }

    /**
     * 解析工厂方法体 `return` 表达式中的 wrapper 构造链。
     * 如 `return new MPJLambdaWrapper<>().selectAll(...).leftJoin(...)` → [new, selectAll, leftJoin]。
     */
    private fun findFactoryReturnChain(method: PsiMethod): List<PsiMethodCallExpression> {
        val body = method.body ?: return emptyList()
        val returnStatement = PsiTreeUtil.findChildOfType(body, PsiReturnStatement::class.java) ?: return emptyList()
        val expr = PsiTreeUtil.getChildOfType(returnStatement, PsiExpression::class.java) ?: return emptyList()
        return ArgResolver.collectChainFromInitializer(expr)
    }

    // ------------------------------------------------------------------
    // 类型识别
    // ------------------------------------------------------------------

    /** Wrappers 静态工厂方法名（`Wrappers.<T>lambdaQuery()` 等）。 */
    private val WRAPPER_FACTORY_METHODS = setOf("lambdaQuery", "lambdaUpdate", "lambdaDelete", "query", "update")

    /**
     * 识别 PsiType 对应的 Wrapper 类型（按简单类名匹配）。
     */
    private fun detectWrapperType(type: PsiType?): WrapperType? {
        val simpleName = type?.canonicalText?.substringAfterLast('.')?.substringBefore('<') ?: return null
        return when (simpleName) {
            "LambdaQueryWrapper" -> WrapperType.LAMBDA_QUERY
            "LambdaUpdateWrapper" -> WrapperType.LAMBDA_UPDATE
            "LambdaDeleteWrapper" -> WrapperType.LAMBDA_DELETE
            "MPJLambdaWrapper" -> WrapperType.MPJ_LAMBDA
            "MPJQueryWrapper" -> WrapperType.MPJ_QUERY
            "MPJLambdaQueryWrapper" -> WrapperType.MPJ_LAMBDA_QUERY
            "QueryWrapper" -> WrapperType.QUERY
            "UpdateWrapper" -> WrapperType.UPDATE
            else -> null
        }
    }

    /**
     * 从 Wrapper 泛型参数解析实体类：`LambdaQueryWrapper<DeviceInfoEntity>` → PsiClass。
     */
    private fun resolveEntityClass(type: PsiType?): PsiClass? {
        val classType = type as? PsiClassType ?: return null
        val parameters = classType.parameters
        if (parameters.isEmpty()) return null
        val entityType = parameters[0] as? PsiClassType ?: return null
        return entityType.resolve()
    }
}
