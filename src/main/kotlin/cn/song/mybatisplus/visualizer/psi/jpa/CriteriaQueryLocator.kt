package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.model.JoinType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil

/**
 * JPA CriteriaQuery 定位器（F-JPA-02）。
 *
 * 从光标位置识别 JPA CriteriaBuilder 强类型查询的 PSI 调用链，构建 [CriteriaQueryModel] 中间表示。
 *
 * 识别模式：
 *  - `cb.createQuery(Xxx.class)` 或 `cb.createQuery()` 创建查询
 *  - `cq.from(Xxx.class)` 设置根实体
 *  - `root.get("fieldName")` 或 `root.get(Xxx_.fieldName)` 引用列
 *  - `cb.equal/greaterThan/lessThan/in/like/null...` 构建条件
 *  - `cq.where(predicate...)` 设置 WHERE
 *  - `cq.orderBy(cb.asc(root.get("field")))` 设置排序
 *  - `cq.multiselect(root.get("a"), root.get("b"))` 或 `cq.select(root)` 设置 SELECT
 *  - `root.join("xxx", JoinType.LEFT)` 设置 JOIN
 *
 * 定位策略：
 *  1. 从光标向上查找包含 CriteriaQuery 相关调用的代码块或方法体；
 *  2. 扫描范围内全部 PsiMethodCallExpression；
 *  3. 识别 `createQuery` / `from` / `where` 等调用并提取中间模型。
 */
object CriteriaQueryLocator {

    // CriteriaQuery 方法名
    private val CRITERIA_QUERY_METHODS = setOf(
        "from", "select", "multiselect", "where", "orderBy",
        "groupBy", "having", "distinct", "getRestriction",
    )

    // CriteriaBuilder 条件方法名
    private val CB_CONDITION_METHODS = setOf(
        "equal", "notEqual",
        "greaterThan", "greaterThanOrEqualTo",
        "lessThan", "lessThanOrEqualTo",
        "between", "isNull", "isNotNull",
        "like", "notLike", "in", "notIn",
        "and", "or", "not",
    )

    // CriteriaBuilder 排序辅助方法
    private val CB_ORDER_METHODS = setOf("asc", "desc")

    // 关联属性 join 类型枚举名（默认 JoinType.LEFT）
    private val JOIN_TYPE_LEFT = setOf("LEFT", "Left")
    private val JOIN_TYPE_INNER = setOf("INNER", "Inner")
    private val JOIN_TYPE_RIGHT = setOf("RIGHT", "Right")

    /**
     * 从光标位置定位 CriteriaQuery 调用链并构建中间模型。
     *
     * @param anchor 光标所在 PSI 元素
     * @return CriteriaQueryModel；未识别到 CriteriaQuery 时返回 null
     */
    fun locate(anchor: PsiElement): CriteriaQueryModel? {
        val block = findRelevantBlock(anchor) ?: return null
        val allCalls = PsiTreeUtil.collectElementsOfType(block, PsiMethodCallExpression::class.java)

        // 查找 CriteriaQuery 变量与 from 调用
        var entityClass: PsiClass? = null
        var entityClassName: String? = null
        var resultClassName: String? = null
        val criteriaQueryVars = mutableSetOf<String>()
        val roots = mutableSetOf<String>() // from 引入的 Root 变量名
        val predicates = mutableListOf<PredicateModel>()
        val groupBys = mutableListOf<GroupByModel>()
        val havings = mutableListOf<PredicateModel>()
        val orderBys = mutableListOf<OrderByModel>()
        val selectColumns = mutableListOf<SelectColumn>()
        val joins = mutableListOf<JoinModel>()

        // 第一遍：识别 createQuery（确认 CriteriaQuery 变量）
        // 模式：`CriteriaQuery<X> cq = cb.createQuery(X.class)` 或内联 `cb.createQuery(X.class)`
        // 关键：createQuery 的**赋值目标**才是 CriteriaQuery 变量，而非 qualifier（那是 CriteriaBuilder）
        for (call in allCalls) {
            val methodName = call.methodExpression.referenceName ?: continue
            if (methodName != "createQuery") continue
            // 提取结果类型（createQuery(Xxx.class) 的类型参数）
            resultClassName = resultClassName ?: extractResultClass(call)
            // 查找赋值目标变量（cq）
            findAssignedVariable(call)?.let { cqVar ->
                criteriaQueryVars += cqVar
            }
            // 未赋值给变量（内联调用）：记录 qualifier 作为潜在 CriteriaBuilder，后续通过 from 推断
        }

        // 未识别到 CriteriaQuery 变量时尝试从 from 调用推断（`cq.from(...)` 的 qualifier）
        if (criteriaQueryVars.isEmpty()) {
            for (call in allCalls) {
                val methodName = call.methodExpression.referenceName ?: continue
                if (methodName != "from") continue
                val qualifierText = call.methodExpression.qualifierExpression?.text ?: continue
                criteriaQueryVars += qualifierText
            }
        }

        // 仍无 CriteriaQuery 变量 → 不是 CriteriaQuery 调用链
        if (criteriaQueryVars.isEmpty()) return null

        // 第二遍：收集各子句
        for (call in allCalls) {
            val methodName = call.methodExpression.referenceName ?: continue
            val qualifierText = call.methodExpression.qualifierExpression?.text ?: continue

            when {
                // cq.from(Xxx.class)
                methodName == "from" && qualifierText in criteriaQueryVars -> {
                    val fromArg = call.argumentList.expressions.firstOrNull()
                    entityClass = extractEntityClass(fromArg)
                    entityClassName = entityClassName ?: extractEntityClassName(fromArg)
                    // Root 变量：from 调用的直接赋值目标
                    findAssignedVariable(call)?.let { roots += it }
                }

                // cq.where(predicate...)
                methodName == "where" && qualifierText in criteriaQueryVars -> {
                    for (arg in call.argumentList.expressions) {
                        extractPredicate(arg)?.let { predicates += it }
                    }
                }

                // cq.groupBy(root.get("field"))
                methodName == "groupBy" && qualifierText in criteriaQueryVars -> {
                    for (arg in call.argumentList.expressions) {
                        extractGroupBy(arg)?.let { groupBys += it }
                    }
                }

                // cq.having(predicate...)
                methodName == "having" && qualifierText in criteriaQueryVars -> {
                    for (arg in call.argumentList.expressions) {
                        extractPredicate(arg)?.let { havings += it }
                    }
                }

                // cq.orderBy(cb.asc(...)/cb.desc(...))
                methodName == "orderBy" && qualifierText in criteriaQueryVars -> {
                    for (arg in call.argumentList.expressions) {
                        extractOrderBy(arg)?.let { orderBys += it }
                    }
                }

                // cq.multiselect(...) / cq.select(...)
                methodName in setOf("multiselect", "select") && qualifierText in criteriaQueryVars -> {
                    selectColumns += extractSelectColumns(call, roots)
                }

                // root.join(...)
                methodName == "join" && qualifierText in roots -> {
                    extractJoin(call)?.let { joins += it }
                }

                // CriteriaBuilder 条件方法（cb.equal / cb.greaterThan ...）
                methodName in CB_CONDITION_METHODS &&
                    isCriteriaBuilderCall(call) -> {
                    // 排除作为 cb.not/cb.and/cb.or 参数的嵌套调用（避免重复提取）
                    if (!isNestedInParentPredicate(call)) {
                        extractPredicate(call)?.let { predicates += it }
                    }
                }
            }
        }

        // 没有实体信息但存在条件 → 仍返回（converter 层降级处理）
        if (entityClassName == null && predicates.isEmpty()) return null

        return CriteriaQueryModel(
            entityClassName = entityClassName,
            entityClass = entityClass,
            selectColumns = selectColumns,
            predicates = predicates,
            groupBys = groupBys,
            havings = havings,
            orderBys = orderBys,
            joins = joins,
            resultClassName = resultClassName,
        )
    }

    // ------------------------------------------------------------------
    // 作用域查找
    // ------------------------------------------------------------------

    /**
     * 从锚点向上查找包含 CriteriaQuery 调用的代码块或方法体。
     */
    private fun findRelevantBlock(anchor: PsiElement): PsiElement? {
        var current: PsiElement? = anchor
        while (current != null) {
            when (current) {
                is PsiCodeBlock -> return current
                is PsiMethod -> return current.body
                // 已到文件顶层：以文件为范围
                is com.intellij.psi.PsiFile -> return current
            }
            current = current.parent
        }
        return null
    }

    // ------------------------------------------------------------------
    // 变量追踪
    // ------------------------------------------------------------------

    /**
     * 查找方法调用被赋值给的局部变量名（如 `Root<X> root = cq.from(...)` 的 root）。
     */
    private fun findAssignedVariable(call: PsiMethodCallExpression): String? {
        val parent = call.parent ?: return null
        // 局部变量声明：Type var = call;
        if (parent is PsiLocalVariable && parent.initializer === call) {
            return parent.name
        }
        // 赋值语句：var = call;
        if (parent is com.intellij.psi.PsiAssignmentExpression) {
            val lhs = parent.lExpression as? PsiReferenceExpression ?: return null
            return lhs.referenceName
        }
        return null
    }

    /**
     * 判断方法调用是否为 cb.xxx() 形式（qualifier 为已知 CriteriaBuilder 变量名）。
     *
     * 由于 CriteriaBuilder 变量类型通常不可 resolve（LightPlatform 无 JPA classpath），
     * 这里采用启发式：方法名属于 CB 条件方法集，且 qualifier 是简单标识符引用（非 cq/root 等已知实体集合）。
     */
    private fun isCriteriaBuilderCall(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName ?: return false
        if (methodName !in CB_CONDITION_METHODS) return false
        val qualifier = call.methodExpression.qualifierExpression ?: return false
        // 简单引用表达式（cb.equal(...) 中 qualifier 是 cb）
        if (qualifier is PsiReferenceExpression) {
            return true
        }
        return false
    }

    /**
     * 检查 call 是否嵌套在父级 CriteriaBuilder 谓词内（cb.not/cb.and/cb.or）。
     * 嵌套调用会在处理父级时被 extractPredicate(args[0]) 提取，避免重复。
     */
    private fun isNestedInParentPredicate(call: PsiMethodCallExpression): Boolean {
        val parent = call.parent as? PsiMethodCallExpression ?: return false
        val parentName = parent.methodExpression.referenceName ?: return false
        return parentName in setOf("and", "or", "not")
    }

    // ------------------------------------------------------------------
    // 实体类解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.from(Xxx.class) 或 cb.createQuery(Xxx.class) 的实参解析 PsiClass。
     */
    private fun extractEntityClass(expr: PsiExpression?): PsiClass? {
        if (expr == null) return null
        // Xxx.class → PsiClassObjectAccessExpression
        if (expr is PsiClassObjectAccessExpression) {
            return (expr.operand.type as? PsiClassType)?.resolve()
        }
        return null
    }

    /**
     * 从 from/createQuery 实参提取实体类名（不可 resolve 时按类型参数/类名兜底）。
     */
    private fun extractEntityClassName(expr: PsiExpression?): String? {
        if (expr == null) return null
        if (expr is PsiClassObjectAccessExpression) {
            return (expr.operand.type as? PsiClassType)?.className
        }
        return null
    }

    /**
     * 从 createQuery 类型参数提取结果类名（`cb.createQuery(Xxx.class)` 的类型参数）。
     */
    private fun extractResultClass(call: PsiMethodCallExpression): String? {
        // 实参形式：cb.createQuery(Xxx.class)
        val arg = call.argumentList.expressions.firstOrNull()
        if (arg is PsiClassObjectAccessExpression) {
            return (arg.operand.type as? PsiClassType)?.className
        }
        // 类型参数形式：cb.createQuery(Xxx.class) 已由 PsiMethodCallExpression 的类型参数提供
        val typeArg = call.typeArguments.firstOrNull() as? PsiClassType
        return typeArg?.className
    }

    // ------------------------------------------------------------------
    // 列引用解析（root.get("field") / root.get(Xxx_.field)）
    // ------------------------------------------------------------------

    /**
     * 从 root.get(...) 调用提取字段名。
     *
     * 支持两种形态：
     *  - `root.get("fieldName")`：字符串字面量
     *  - `root.get(Xxx_.fieldName)`：静态方法引用
     */
    private fun extractFieldName(call: PsiMethodCallExpression): String? {
        val methodName = call.methodExpression.referenceName ?: return null
        if (methodName != "get") return null
        val arg = call.argumentList.expressions.firstOrNull() ?: return null
        return when (arg) {
            is PsiLiteralExpression -> arg.value as? String
            is PsiReferenceExpression -> arg.referenceName
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // SELECT 解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.select(...) / cq.multiselect(...) 提取 SELECT 列。
     *
     * 增强版（P1-5 聚合函数支持）：
     *  - cb.count(root) → SelectColumn(null, aggregate=AggregateInfo("COUNT"))
     *  - cb.sum(root.get("field")) → SelectColumn(null, aggregate=AggregateInfo("SUM", "field"))
     */
    private fun extractSelectColumns(
        call: PsiMethodCallExpression,
        roots: Set<String>,
    ): List<SelectColumn> {
        val methodName = call.methodExpression.referenceName ?: return emptyList()
        val args = call.argumentList.expressions
        if (args.isEmpty()) return emptyList()

        // cq.select(root) → 全列（返回空列表，converter 按全列处理）
        if (methodName == "select" && args.size == 1) {
            val arg = args[0]
            if (arg is PsiReferenceExpression && arg.referenceName in roots) {
                return emptyList() // 全列语义
            }
        }

        // cq.multiselect(...) / cq.select(...) 各参数
        return args.mapNotNull { arg ->
            // 尝试解析为聚合函数调用
            val aggregate = extractAggregate(arg)
            if (aggregate != null) {
                return@mapNotNull SelectColumn(fieldName = aggregate.fieldName, aggregate = aggregate)
            }

            // 否则尝试 root.get(...)
            val getCall = arg as? PsiMethodCallExpression ?: return@mapNotNull null
            if (getCall.methodExpression.referenceName != "get") return@mapNotNull null
            val fieldName = extractFieldName(getCall) ?: return@mapNotNull null
            SelectColumn(fieldName)
        }
    }

    /**
     * 尝试从 SELECT 参数表达式提取聚合函数信息。
     */
    private fun extractAggregate(arg: PsiExpression): AggregateInfo? {
        val call = arg as? PsiMethodCallExpression ?: return null
        val methodName = call.methodExpression.referenceName ?: return null

        val upperName = methodName.uppercase()
        if (upperName !in AGGREGATE_FUNCTIONS) return null

        val qualifier = call.methodExpression.qualifierExpression
        if (qualifier !is PsiReferenceExpression) return null

        val args = call.argumentList.expressions
        if (args.isEmpty()) return AggregateInfo(function = upperName)

        val firstArg = args[0]
        val fieldName = extractFieldNameFromArg(firstArg)

        return AggregateInfo(function = upperName, fieldName = fieldName)
    }

    // CriteriaBuilder 聚合函数名
    private val AGGREGATE_FUNCTIONS = setOf("COUNT", "SUM", "MAX", "MIN", "AVG")

    // ------------------------------------------------------------------
    // WHERE / Predicate 解析
    // ------------------------------------------------------------------

    /**
     * 从 where 的实参（单个 Predicate 或 Predicate 数组）提取条件模型。
     */
    private fun extractPredicate(expr: PsiExpression?): PredicateModel? {
        if (expr == null) return null
        // 直接是方法调用：cb.equal(...) / cb.and(...) / cb.or(...) / cb.not(...)
        if (expr is PsiMethodCallExpression) {
            val methodName = expr.methodExpression.referenceName
            // 仅处理 CriteriaBuilder 条件方法，排除 Java 集合工具方法（toArray 等）
            if (methodName in CB_CONDITION_METHODS || methodName in setOf("and", "or", "not")) {
                return extractPredicateFromCall(expr)
            }
            return null
        }
        // 变量引用（如 predicates）→ 尝试解析为谓词数组
        if (expr is PsiReferenceExpression) {
            return resolvePredicateVariable(expr)
        }
        // 其他表达式 → 忽略（不生成 Raw 谓词）
        return null
    }

    /**
     * 尝试解析谓词变量（如 predicates）为谓词列表。
     * 场景：cq.where(predicates.toArray(new Predicate[0]))
     * 由于 StaticPredicateModel 不支持列表展开，此处返回 null 避免生成错误的 Raw 谓词。
     * 实际谓词已通过 CB_CONDITION_METHODS 路径提取（cb.equal/greaterThan 等）。
     */
    private fun resolvePredicateVariable(expr: PsiReferenceExpression): PredicateModel? {
        // 变量引用不生成 Raw 谓词，避免 "predicates.toArray(...)" 出现在 WHERE 子句
        return null
    }

    /**
     * 从 CriteriaBuilder 条件调用提取 PredicateModel。
     *
     * 支持的调用模式：
     *  - cb.equal(root.get("a"), value)     → Simple(a, "=", value)
     *  - cb.greaterThan(root.get("a"), v)   → Simple(a, ">", v)
     *  - cb.isNull(root.get("a"))           → IsNull(a, false)
     *  - cb.between(root.get("a"), lo, hi)  → Between(a, lo, hi)
     *  - cb.like(root.get("a"), pattern)    → Like(a, false, pattern)
     *  - cb.in(root.get("a"))               → In(a, false, [])
     *  - cb.and(p1, p2)                     → Group(AND, [p1, p2])
     *  - cb.or(p1, p2)                      → Group(OR, [p1, p2])
     *  - cb.not(p)                          → Group(AND, [p], negated=true)
     */
    private fun extractPredicateFromCall(call: PsiMethodCallExpression): PredicateModel {
        val methodName = call.methodExpression.referenceName ?: return PredicateModel.Raw(call.text)
        val args = call.argumentList.expressions

        return when (methodName) {
            "equal" -> buildSimple(call, args, "=")
            "notEqual" -> buildSimple(call, args, "!=")
            "greaterThan" -> buildSimple(call, args, ">")
            "greaterThanOrEqualTo" -> buildSimple(call, args, ">=")
            "lessThan" -> buildSimple(call, args, "<")
            "lessThanOrEqualTo" -> buildSimple(call, args, "<=")

            "between" -> buildBetween(call, args)

            "isNull" -> buildIsNull(call, args, negated = false)
            "isNotNull" -> buildIsNull(call, args, negated = true)

            "like" -> buildLike(call, args, negated = false)
            "notLike" -> buildLike(call, args, negated = true)

            "in" -> buildIn(call, args, negated = false)
            "notIn" -> buildIn(call, args, negated = true)

            "and" -> buildGroup(args, GroupConnector.AND)
            "or" -> buildGroup(args, GroupConnector.OR)
            "not" -> buildNot(args)

            else -> PredicateModel.Raw(call.text)
        }
    }

    /** 构建二元比较条件（eq/ne/gt/ge/lt/le）。 */
    private fun buildSimple(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        op: String,
    ): PredicateModel {
        if (args.size < 2) return PredicateModel.Raw(call.text)
        val fieldName = args.getOrNull(0)?.let { extractFieldNameFromArg(it) } ?: return PredicateModel.Raw(call.text)
        val valueExpr = args[1].text
        return PredicateModel.Simple(fieldName, op, valueExpr)
    }

    /** 构建 BETWEEN 条件。 */
    private fun buildBetween(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
    ): PredicateModel {
        if (args.size < 3) return PredicateModel.Raw(call.text)
        val fieldName = args.getOrNull(0)?.let { extractFieldNameFromArg(it) } ?: return PredicateModel.Raw(call.text)
        return PredicateModel.Between(fieldName, args[1].text, args[2].text)
    }

    /** 构建 IS NULL / IS NOT NULL 条件。 */
    private fun buildIsNull(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        negated: Boolean,
    ): PredicateModel {
        if (args.isEmpty()) return PredicateModel.Raw(call.text)
        val fieldName = args.getOrNull(0)?.let { extractFieldNameFromArg(it) } ?: return PredicateModel.Raw(call.text)
        return PredicateModel.IsNull(fieldName, negated)
    }

    /** 构建 LIKE / NOT LIKE 条件。 */
    private fun buildLike(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        negated: Boolean,
    ): PredicateModel {
        if (args.size < 2) return PredicateModel.Raw(call.text)
        val fieldName = args.getOrNull(0)?.let { extractFieldNameFromArg(it) } ?: return PredicateModel.Raw(call.text)
        return PredicateModel.Like(fieldName, negated, args[1].text)
    }

    /** 构建 IN / NOT IN 条件（cb.in(root.get(...)) → 单列；value 追加通过链式调用，当前简化为单值）。 */
    private fun buildIn(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        negated: Boolean,
    ): PredicateModel {
        if (args.isEmpty()) return PredicateModel.Raw(call.text)
        // cb.in(root.get("a")) 或 cb.in(root.get("a"), v1, v2, ...)
        val fieldName = args.getOrNull(0)?.let { extractFieldNameFromArg(it) } ?: return PredicateModel.Raw(call.text)
        val values = if (args.size > 1) {
            args.drop(1).map { it.text }
        } else {
            emptyList()
        }
        return PredicateModel.In(fieldName, negated, values)
    }

    /** 构建 AND / OR 条件组。 */
    private fun buildGroup(args: Array<PsiExpression>, connector: GroupConnector): PredicateModel {
        val children = args.mapNotNull { extractPredicate(it) }
        return PredicateModel.Group(connector, children)
    }

    /** 构建 NOT 条件（cb.not(p) → Group(AND, [p], negated=true)）。 */
    private fun buildNot(args: Array<PsiExpression>): PredicateModel {
        if (args.isEmpty()) return PredicateModel.Raw("cb.not()")
        val child = extractPredicate(args[0]) ?: return PredicateModel.Raw(args[0].text)
        return PredicateModel.Group(GroupConnector.AND, listOf(child), negated = true)
    }

    /**
     * 从实参表达式提取字段名（支持 root.get(...) 或字符串字面量）。
     */
    private fun extractFieldNameFromArg(arg: PsiExpression): String? {
        if (arg is PsiMethodCallExpression && arg.methodExpression.referenceName == "get") {
            return extractFieldName(arg)
        }
        if (arg is PsiLiteralExpression) {
            return arg.value as? String
        }
        return null
    }

    // ------------------------------------------------------------------
    // GROUP BY 解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.groupBy(...) 的实参提取 GroupByModel。
     */
    private fun extractGroupBy(expr: PsiExpression?): GroupByModel? {
        if (expr == null) return null
        val fieldName = extractFieldNameFromArg(expr) ?: return null
        return GroupByModel(fieldName)
    }

    // ------------------------------------------------------------------
    // ORDER BY 解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.orderBy(...) 的实参提取 OrderByModel。
     *
     * 支持：
     *  - cb.asc(root.get("field")) → OrderByModel(field, asc=true)
     *  - cb.desc(root.get("field")) → OrderByModel(field, asc=false)
     */
    private fun extractOrderBy(expr: PsiExpression?): OrderByModel? {
        if (expr == null) return null
        if (expr !is PsiMethodCallExpression) return null
        val methodName = expr.methodExpression.referenceName ?: return null
        if (methodName !in CB_ORDER_METHODS) return null
        val innerArg = expr.argumentList.expressions.firstOrNull() ?: return null
        val fieldName = extractFieldNameFromArg(innerArg) ?: return null
        return OrderByModel(fieldName, asc = methodName == "asc")
    }

    // ------------------------------------------------------------------
    // JOIN 解析
    // ------------------------------------------------------------------

    /**
     * 从 root.join("field", JoinType.LEFT) 提取 JoinModel。
     */
    private fun extractJoin(call: PsiMethodCallExpression): JoinModel? {
        val args = call.argumentList.expressions
        if (args.isEmpty()) return null

        // 关联属性：字符串字面量或方法引用
        val fieldName = when (val firstArg = args[0]) {
            is PsiLiteralExpression -> firstArg.value as? String
            is PsiReferenceExpression -> firstArg.referenceName
            else -> firstArg.text
        } ?: return null

        // JoinType（可选，默认 LEFT）
        val joinType = if (args.size > 1) {
            val typeArg = args[1]
            if (typeArg is PsiReferenceExpression) {
                mapJoinType(typeArg.referenceName)
            } else {
                JoinType.LEFT
            }
        } else {
            JoinType.LEFT
        }

        return JoinModel(fieldName = fieldName, joinType = joinType)
    }

    /** JoinType 枚举名映射。 */
    private fun mapJoinType(enumName: String?): JoinType = when {
        enumName in JOIN_TYPE_LEFT -> JoinType.LEFT
        enumName in JOIN_TYPE_INNER -> JoinType.INNER
        enumName in JOIN_TYPE_RIGHT -> JoinType.RIGHT
        else -> JoinType.LEFT
    }
}
