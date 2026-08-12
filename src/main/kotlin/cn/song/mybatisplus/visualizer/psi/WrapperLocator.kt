package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
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
    fun locateWithTable(anchor: PsiElement, resolveVariables: Boolean = true): LocatedWrapper? {
        findWrapperVariable(anchor)?.let { return buildChain(it, resolveVariables) }
        findWrapperNewExpression(anchor)?.let { return buildChainFromNew(it, resolveVariables) }
        findWrapperFactoryCall(anchor)?.let { return buildChainFromFactory(it, resolveVariables) }
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
                is PsiLocalVariable -> if (isWrapperVariable(current)) return current
                is PsiField -> if (isWrapperVariable(current)) return current
                is PsiParameter -> if (isWrapperVariable(current)) return current
                is PsiMethodCallExpression -> {
                    val qualifier = current.methodExpression.qualifierExpression as? PsiReferenceExpression
                    val resolved = qualifier?.resolve() as? PsiVariable
                    if (resolved != null && isWrapperVariable(resolved)) return resolved
                }
            }
            current = current.parent
        }
        return null
    }

    /**
     * 变量是否为 Wrapper（含基类型降级）：类型直接是具体 Wrapper，或类型为抽象基类
     * （`Wrapper<X>` / `AbstractWrapper<X>`）但初始化表达式链首是具体 Wrapper 构造 / Wrappers 工厂。
     */
    private fun isWrapperVariable(variable: PsiVariable): Boolean =
        detectWrapperType(variable.type) != null || detectWrapperTypeFromInitializer(variable.initializer) != null

    /**
     * 从初始化表达式链首推断 Wrapper 类型（基类型声明降级用）：
     * 沿 `methodExpression.qualifierExpression` 回溯到链起点，返回其 wrapper 类型。
     */
    private fun detectWrapperTypeFromInitializer(init: PsiElement?): WrapperType? {
        var expr: PsiElement? = init ?: return null
        while (expr is PsiMethodCallExpression) {
            expr = expr.methodExpression.qualifierExpression
        }
        return when (expr) {
            is PsiNewExpression -> detectWrapperType(expr.type)
            is PsiMethodCallExpression -> factoryWrapperType(expr)
            else -> null
        }
    }

    /**
     * 表达式链首的最近消费调用：向上找到第一个实参列表（`PsiExpressionList`）的父方法调用。
     * 用于判断 wrapper 作为实参传给了哪个方法（`service.remove(new W()...)` 取 remove 而非更外层）。
     */
    private fun PsiElement.consumingCall(): PsiMethodCallExpression? {
        var cursor: PsiElement? = parent
        while (cursor != null) {
            when (cursor) {
                is PsiExpressionList -> return cursor.parent as? PsiMethodCallExpression
                is PsiStatement, is PsiMethod, is PsiClass -> return null
            }
            cursor = cursor.parent
        }
        return null
    }

    /**
     * 从锚点查找内联 Wrapper 构造表达式（`list(new MPJLambdaQueryWrapper<X>().eq(...))`，清单 P17）。
     *
     * 定位策略（防跨方法误命中）：
     *  - 向下扫锚点**子树**只在锚点初始位置执行一次（锚点可能是包裹调用，如 `list(new W()...)`）；
     *  - 沿祖先链只检查元素**自身**是否为 wrapper new 表达式——绝不能每层都向下扫，
     *    否则走到 PsiClass 级别会命中类内其他方法的 wrapper（回归：删除/修改被串成查询，2026-08-12）。
     */
    private fun findWrapperNewExpression(anchor: PsiElement): PsiNewExpression? {
        PsiTreeUtil.findChildOfType(anchor, PsiNewExpression::class.java)?.let { newExpr ->
            if (detectWrapperType(newExpr.type) != null) return newExpr
        }
        var current: PsiElement? = anchor
        while (current != null) {
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
    private fun buildChainFromNew(newExpr: PsiNewExpression, resolveVariables: Boolean): LocatedWrapper? {
        val wrapperType = detectWrapperType(newExpr.type) ?: return null
        val (entityClass, typeArgName) = entityInfoFromType(newExpr.type)
        val table: TableInfo? = resolveTableInfo(entityClass, typeArgName)
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
            .map { ArgResolver.toWrapperCall(it, table, entityClass?.name, resolveVariables) }

        // 消费识别：wrapper 作为实参传给删除/计数方法（`service.remove(new W()...)` / `service.count(new W()...)`）
        val consumingCall = newExpr.consumingCall()
        val deleteConsumed = consumingCall?.let { it.methodExpression.referenceName in deleteConsumerMethodNames } == true
        val countConsumed = consumingCall?.let { it.methodExpression.referenceName in countConsumerMethodNames } == true

        return LocatedWrapper(
            chain = WrapperChain(wrapperType, entityClass?.name, wrapperCalls, constructorAlias, deleteConsumed, countConsumed),
            table = table,
        )
    }

    // ------------------------------------------------------------------
    // Wrappers 静态工厂链（getOne(Wrappers.<T>lambdaQuery().select(...).eq(...)) 等）
    // ------------------------------------------------------------------

    /**
     * 从锚点查找内联 Wrapper 工厂调用（`getOne(Wrappers.<T>lambdaQuery().select(...).eq(...))`）。
     *
     * `Wrappers.<T>lambdaQuery()` 是静态工厂调用，不产生 `new` 表达式、也不一定绑定 Wrapper 类型变量。
     * 定位策略同 [findWrapperNewExpression]：向下扫子树只在锚点初始位置一次，向上只检查元素自身，
     * 避免走到类级别时误命中其他方法的工厂调用。
     */
    private fun findWrapperFactoryCall(anchor: PsiElement): PsiMethodCallExpression? {
        PsiTreeUtil.collectElementsOfType(anchor, PsiMethodCallExpression::class.java)
            .firstOrNull { isWrapperFactoryCall(it) }
            ?.let { return it }
        var current: PsiElement? = anchor
        while (current != null) {
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
    private fun buildChainFromFactory(factoryCall: PsiMethodCallExpression, resolveVariables: Boolean): LocatedWrapper? {
        val wrapperType = factoryWrapperType(factoryCall) ?: return null
        // 实体类优先取显式类型实参（`Wrappers.<MerchantEntity>lambdaQuery()`），失败时用类名按命名策略兜底
        val typeArg = factoryCall.typeArguments.firstOrNull() as? PsiClassType
        val entityClass = typeArg?.resolve() ?: resolveEntityClass(factoryCall.type)
        val typeArgName = typeArg?.className
        val table: TableInfo? = resolveTableInfo(entityClass, typeArgName)

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
            .map { ArgResolver.toWrapperCall(it, table, entityClass?.name, resolveVariables) }

        // 消费识别：wrapper 作为实参传给删除/计数方法（`service.remove(Wrappers.<T>lambdaQuery()...)` 等）
        val consumingCall = factoryCall.consumingCall()
        val deleteConsumed = consumingCall?.let { it.methodExpression.referenceName in deleteConsumerMethodNames } == true
        val countConsumed = consumingCall?.let { it.methodExpression.referenceName in countConsumerMethodNames } == true

        return LocatedWrapper(
            chain = WrapperChain(wrapperType, entityClass?.name, wrapperCalls, constructorAlias = null, deleteConsumed = deleteConsumed, countConsumed = countConsumed),
            table = table,
        )
    }

    /**
     * 识别 Wrappers 静态工厂调用：`Wrappers.<T>lambdaQuery()`（qualifier 指向 Wrappers 工具类）。
     */
    private fun isWrapperFactoryCall(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName
        if (methodName !in wrapperFactoryMethodNames) return false
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
    private fun buildChain(variable: PsiVariable, resolveVariables: Boolean): LocatedWrapper? {
        // 基类型声明降级：类型不是具体 Wrapper 时从初始化表达式链首推断（`Wrapper<X> w = new LambdaQueryWrapper<X>()...`）
        val wrapperType = detectWrapperType(variable.type)
            ?: detectWrapperTypeFromInitializer(variable.initializer)
            ?: return null
        val (entityClass, typeArgName) = entityInfoFromType(variable.type)
        val table: TableInfo? = resolveTableInfo(entityClass, typeArgName)
        val constructorAlias = extractConstructorAlias(variable.initializer)

        val callElements = mutableListOf<PsiMethodCallExpression>()
        var deleteConsumed = false
        var countConsumed = false

        // 1. 初始化表达式中的链式调用；若链首为工厂方法调用（baseJoinWrapper()），展开其方法体内的 wrapper 链（P03）；
        //    Wrappers 静态工厂调用（Wrappers.<T>lambdaQuery()）不是 Wrapper 操作，从链中剔除
        variable.initializer?.let { init ->
            val initCalls = ArgResolver.collectChainFromInitializer(init).dropWhile { isWrapperFactoryCall(it) }
            callElements += expandFactoryCalls(initCalls)
        }

        // 2. 变量引用上的方法调用（分步写法）+ 消费识别（`service.remove(wrapper)` / `service.count(wrapper)` 等）
        ReferencesSearch.search(variable).forEach { ref ->
            val refExpression = ref.element as? PsiReferenceExpression ?: return@forEach
            if (isConsumerArgument(refExpression, deleteConsumerMethodNames)) deleteConsumed = true
            if (isConsumerArgument(refExpression, countConsumerMethodNames)) countConsumed = true
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

        val wrapperCalls = sortedCalls.map { ArgResolver.toWrapperCall(it, table, entityClass?.name, resolveVariables) }
        return LocatedWrapper(
            chain = WrapperChain(wrapperType, entityClass?.name, wrapperCalls, constructorAlias, deleteConsumed, countConsumed),
            table = table,
        )
    }

    /**
     * 判断引用是否作为**实参**传给指定消费方法集合（`service.remove(wrapper)` / `service.count(wrapper)` 等）。
     *
     * 引用是方法调用 qualifier（`wrapper.eq(...)` 的链调用）或位于其他上下文时返回 false。
     */
    private fun isConsumerArgument(ref: PsiReferenceExpression, consumerMethods: Set<String>): Boolean {
        var cursor: PsiElement? = ref.parent
        while (cursor != null) {
            when (cursor) {
                is PsiExpressionList -> {
                    val call = cursor.parent as? PsiMethodCallExpression ?: return false
                    return call.methodExpression.referenceName in consumerMethods
                }
                // 引用是链调用的 qualifier / 语句 / 方法 / 类边界，不是消费实参
                is PsiMethodCallExpression, is PsiStatement, is PsiMethod, is PsiClass -> return false
            }
            cursor = cursor.parent
        }
        return false
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

    /** Wrappers 静态工厂方法名（`Wrappers.<T>lambdaQuery()` 等）。M10 起 internal 供 [WrapperAnchorDetector] 复用。 */
    internal val wrapperFactoryMethodNames: Set<String> = setOf(
        "lambdaQuery", "lambdaUpdate", "lambdaDelete", "query", "update",
    )

    /** 删除类消费方法：wrapper 作为实参传入时，即使类型为查询类也应转 DELETE（MP 惯用删除写法）。 */
    internal val deleteConsumerMethodNames: Set<String> = setOf("remove", "delete")

    /** 计数类消费方法：wrapper 作为实参传入时，查询类 wrapper 转 `SELECT COUNT(*) FROM ...`。 */
    internal val countConsumerMethodNames: Set<String> = setOf("count", "selectJoinCount")

    /**
     * 识别 PsiType 对应的 Wrapper 类型（按简单类名匹配）。
     *
     * 注意：不能用 `canonicalText.substringAfterLast('.')` 提取类名——当泛型实参带包名时
     * （真实项目常态，如 `LambdaQueryWrapper<com.biz.BikeDeviceProductInfoEntity>`），
     * 最后一个 `.` 会落在泛型参数内导致提取错误（回归：M10 gutter 图标 + WrapperLocator 定位）。
     * `PsiClassType.getClassName()` 在第一个 `<` 之前取末段，语义正确且无需 resolve（快速路径安全）。
     *
     * M10 起为 internal：供 [WrapperAnchorDetector] 复用（gutter 锚点判定，快速路径
     * 只做 canonicalText 文本匹配，不 resolve），保证 Wrapper 类名集合单一来源。
     */
    internal fun detectWrapperType(type: PsiType?): WrapperType? {
        val simpleName = (type as? PsiClassType)?.className ?: return null
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

    /**
     * 从 Wrapper 类型解析 (实体类, 类型参数类名)。
     *
     * 实体类可能不可解析（如粘贴片段无实体类定义）：此时类型参数类名仍可获取，
     * 供 [resolveTableInfo] 按命名策略兜底推断表名。
     */
    private fun entityInfoFromType(type: PsiType?): Pair<PsiClass?, String?> {
        val classType = type as? PsiClassType ?: return null to null
        val param = classType.parameters.firstOrNull() as? PsiClassType
        return param?.resolve() to param?.className
    }

    /**
     * 解析实体表信息；实体类不可解析时，用类型参数类名按命名策略推断表名
     * （列名由 getter → 命名策略兜底，见 [cn.song.mybatisplus.visualizer.psi.ArgResolver.resolveLambdaColumn]）。
     */
    private fun resolveTableInfo(entityClass: PsiClass?, typeArgName: String?): TableInfo? {
        entityClass?.let { return EntityResolver().resolve(it) }
        val name = typeArgName ?: return null
        return TableInfo(
            entityClassName = name,
            tableName = NamingStrategy.toSnakeCase(name),
            columns = emptyList(),
        )
    }
}
