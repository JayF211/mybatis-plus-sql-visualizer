package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.model.JoinType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil

/**
 * JPA CriteriaQuery / CriteriaUpdate / CriteriaDelete 定位器（F-JPA-02，T-J4/T-J6/T-J7/T-J17）。
 *
 * 从光标位置识别 JPA CriteriaBuilder 强类型查询的 PSI 调用链，构建 [CriteriaQueryModel] 中间表示。
 *
 * 识别模式：
 *  - `cb.createQuery(Xxx.class)` / `cb.createCriteriaUpdate(Xxx.class)` / `cb.createCriteriaDelete(Xxx.class)`
 *  - `cq.from(Xxx.class)` / `update.from(Xxx.class)` 设置根实体
 *  - `root.get("fieldName")` 或 `root.get(Xxx_.fieldName)` 引用列
 *  - `cb.equal/gt/ge/lt/le/isTrue/isFalse/in/like/...` 构建条件（清单见 [CB_CONDITION_METHODS]）
 *  - `cb.lower/upper/concat/abs/cast/coalesce/selectCase...` 表达式（见 [CB_EXPRESSION_FUNCTIONS]）
 *  - `cq.where / groupBy / having / orderBy / multiselect / select / distinct` 各子句
 *  - `root.join/joinCollection/joinSet/joinList/joinMap/fetch` + `Join.on(...)` 显式 ON
 *  - `query.subquery(...)` + `sq.from/select/where` + `cb.exists(sq)` / `path.get("x").in(sq)`
 *  - `update.set(path, value)` / `update.set("attr", value)` SET 子句
 *
 * 定位策略：
 *  1. 从光标向上查找包含 CriteriaQuery 相关调用的代码块或方法体；
 *  2. 扫描范围内全部 PsiMethodCallExpression；
 *  3. 识别 `createQuery` / `from` / `where` 等调用并提取中间模型。
 *
 * 无法静态解析的能力一律降级为 Raw/注释占位，绝不抛异常。
 */
object CriteriaQueryLocator {

    // CriteriaQuery 子句方法名。
    // T-J4 清理：原名单中的 getRestriction 是死条目——它只是谓词读取 API，从不参与查询构建，已移除。
    private val CRITERIA_QUERY_METHODS = setOf(
        "from", "select", "multiselect", "where", "orderBy",
        "groupBy", "having", "distinct",
    )

    // CriteriaBuilder 条件方法名。
    // T-J4 清理：原名单中的 notIn 是死条目——jakarta persistence 3.1 的 CriteriaBuilder 并无该方法
    // （javap 反编译证实），NOT IN 由 `CriteriaBuilder.In.value(...).not()` 或 Expression.not() 表达，
    // 链式 .not() 在 buildIn 中识别。
    // T-J17 补齐：gt/ge/lt/le（Number 变体）、isTrue/isFalse、conjunction/disjunction、
    // exists、isEmpty/isNotEmpty 与 isMember/isNotMember（后四者渲染降级）。
    private val CB_CONDITION_METHODS = setOf(
        "equal", "notEqual",
        "greaterThan", "greaterThanOrEqualTo",
        "lessThan", "lessThanOrEqualTo",
        "gt", "ge", "lt", "le",
        "between", "isNull", "isNotNull",
        "isTrue", "isFalse",
        "like", "notLike", "in",
        "conjunction", "disjunction",
        "and", "or", "not",
        "exists",
        "isEmpty", "isNotEmpty", "isMember", "isNotMember",
    )

    // CriteriaBuilder 排序辅助方法
    private val CB_ORDER_METHODS = setOf("asc", "desc")

    // SELECT 聚合函数名（T-J17 补充的 countDistinct/sumAsLong/sumAsDouble/greatest/least 单独分支处理）
    private val AGGREGATE_FUNCTIONS = setOf("COUNT", "SUM", "MAX", "MIN", "AVG")

    // CriteriaBuilder 表达式函数名（T-J17）：lower/upper/concat/substring/length/locate/trim、
    // abs/ceiling/floor/round/exp/ln/power/sqrt/sign、sum/prod/diff/quot/mod/neg、
    // toLong/.../toString、coalesce/nullif、function、count、size
    private val CB_EXPRESSION_FUNCTIONS = setOf(
        "lower", "upper", "concat", "substring", "length", "locate", "trim",
        "abs", "ceiling", "floor", "round", "exp", "ln", "power", "sqrt", "sign",
        "sum", "prod", "diff", "quot", "mod", "neg",
        "toLong", "toInteger", "toFloat", "toDouble", "toBigDecimal", "toBigInteger", "toString",
        "coalesce", "nullif",
        "currentDate", "currentTime", "currentTimestamp",
        "function", "count", "size",
    )

    // 转换函数 → CAST 目标类型（统一 SQL 标准型，方言差异不处理）
    private val CAST_TYPE_MAP = mapOf(
        "toLong" to "BIGINT",
        "toInteger" to "INT",
        "toFloat" to "FLOAT",
        "toDouble" to "DOUBLE",
        "toBigDecimal" to "DECIMAL",
        "toBigInteger" to "BIGINT",
        "toString" to "VARCHAR",
    )

    // Trimspec 枚举名
    private val TRIM_SPECS = setOf("LEADING", "TRAILING", "BOTH")

    // join/fetch 识别方法名（T-J7：joinCollection/joinSet/joinList/joinMap 按 join 同路径识别）
    private val JOIN_METHODS = setOf("join", "joinCollection", "joinSet", "joinList", "joinMap")

    // 关联属性 join 类型枚举名（join(String) 无 JoinType 实参时按 JPA 规范默认 INNER，T-J7）
    private val JOIN_TYPE_LEFT = setOf("LEFT", "Left")
    private val JOIN_TYPE_INNER = setOf("INNER", "Inner")
    private val JOIN_TYPE_RIGHT = setOf("RIGHT", "Right")

    // 谓词提取的宿主方法（这些调用的实参由宿主分支负责提取，扫描循环跳过避免重复）。
    // on 的实参由 Join.on 后处理收集；add 的实参由列表变量解引用收集，同样属于宿主。
    private val PREDICATE_HOST_METHODS = setOf("and", "or", "not", "where", "having", "on", "add")

    /**
     * 单次 locate 的上下文：根/join/谓词变量与子查询登记表。
     * 变量解析依赖 PSI 源码顺序（声明先于使用），乱序代码按降级处理。
     */
    private class LocateContext(val block: PsiElement) {
        /** root 变量名（cq.from 赋值目标）。 */
        val roots = mutableSetOf<String>()
        /** 全部 join/fetch 记录（含内联未赋值形态），按发现顺序。 */
        val allJoins = mutableListOf<WorkingJoin>()
        /** join/fetch 变量名 → join 记录。 */
        val joinVars = mutableMapOf<String, WorkingJoin>()
        /** 谓词变量名 → 已提取模型（Predicate p = cb.equal(...) 的变量追踪）。 */
        val predicateVars = mutableMapOf<String, PredicateModel>()
        /** 子查询变量名 → 子查询模型。 */
        val subqueries = mutableMapOf<String, SubqueryModel>()
        /** 子查询 from 引入的 root 变量名。 */
        val subRoots = mutableSetOf<String>()
    }

    /** join 提取的临时记录：显式 ON 谓词按调用序收集，最终物化为 [JoinModel]。 */
    private class WorkingJoin(
        val callText: String,
        val fieldName: String,
        val joinType: JoinType,
        val fetch: Boolean,
        val variableName: String?,
    ) {
        val onPredicates = mutableListOf<PredicateModel>()

        fun toModel(): JoinModel = JoinModel(
            fieldName = fieldName,
            joinType = joinType,
            fetch = fetch,
            onPredicates = onPredicates.toList(),
            variableName = variableName,
        )
    }

    /**
     * 从光标位置定位 CriteriaQuery 调用链并构建中间模型。
     *
     * @param anchor 光标所在 PSI 元素
     * @return CriteriaQueryModel；未识别到 CriteriaQuery 时返回 null
     */
    fun locate(anchor: PsiElement): CriteriaQueryModel? {
        val block = findRelevantBlock(anchor) ?: return null
        val allCalls = PsiTreeUtil.collectElementsOfType(block, PsiMethodCallExpression::class.java)
        val ctx = LocateContext(block)

        var entityClass: PsiClass? = null
        var entityClassName: String? = null
        var resultClassName: String? = null
        var operation = CriteriaOperation.SELECT
        var distinct = false
        val criteriaQueryVars = mutableSetOf<String>()
        val predicates = mutableListOf<PredicateModel>()
        val groupBys = mutableListOf<GroupByModel>()
        val havings = mutableListOf<PredicateModel>()
        val orderBys = mutableListOf<OrderByModel>()
        val selectColumns = mutableListOf<SelectColumn>()
        val setClauses = mutableListOf<CriteriaSetClause>()
        // Join.on(...) 调用后处理：PSI 遍历外层调用先于内层 join 登记，须在扫描结束后再解析目标
        val pendingOnCalls = mutableListOf<PsiMethodCallExpression>()

        // 第一遍：识别 createQuery / createCriteriaUpdate / createCriteriaDelete。
        // 关键：创建调用的**赋值目标**才是 CriteriaQuery/Update/Delete 变量，而非 qualifier（那是 CriteriaBuilder）
        for (call in allCalls) {
            val methodName = call.methodExpression.referenceName ?: continue
            val assignedVar = findAssignedVariable(call)
            when (methodName) {
                "createQuery" -> {
                    resultClassName = resultClassName ?: extractResultClass(call)
                    assignedVar?.let { criteriaQueryVars += it }
                }
                "createCriteriaUpdate" -> {
                    operation = CriteriaOperation.UPDATE
                    resultClassName = resultClassName ?: extractResultClass(call)
                    assignedVar?.let { criteriaQueryVars += it }
                }
                "createCriteriaDelete" -> {
                    operation = CriteriaOperation.DELETE
                    resultClassName = resultClassName ?: extractResultClass(call)
                    assignedVar?.let { criteriaQueryVars += it }
                }
            }
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

        // 仍无 CriteriaQuery 变量 → 不是 Criteria 调用链
        if (criteriaQueryVars.isEmpty()) return null

        // 子查询登记（T-J17/J13）：`Subquery<T> sq = query.subquery(...)` → 变量名 → 模型
        collectSubqueries(allCalls, criteriaQueryVars, ctx)

        // 第二遍：收集各子句
        for (call in allCalls) {
            val methodName = call.methodExpression.referenceName ?: continue
            val qualifier = call.methodExpression.qualifierExpression
            val qualifierText = qualifier?.text ?: continue

            when {
                // cq.from(Xxx.class) / update.from(Xxx.class)
                methodName == "from" && qualifierText in criteriaQueryVars -> {
                    val fromArg = call.argumentList.expressions.firstOrNull()
                    entityClass = extractEntityClass(fromArg)
                    entityClassName = entityClassName ?: extractEntityClassName(fromArg)
                    // Root 变量：from 调用的直接赋值目标
                    findAssignedVariable(call)?.let { ctx.roots += it }
                }

                // cq.where(predicate...)
                methodName == "where" && qualifierText in criteriaQueryVars -> {
                    for (arg in call.argumentList.expressions) {
                        extractPredicate(arg, ctx)?.let { predicates += it }
                    }
                }

                // cq.groupBy(root.get("field")) / cq.groupBy(List)
                methodName == "groupBy" && qualifierText in criteriaQueryVars -> {
                    for (arg in call.argumentList.expressions) {
                        for (item in expandListArgument(arg, ctx)) {
                            extractGroupBy(item)?.let { groupBys += it }
                        }
                    }
                }

                // cq.having(predicate...)
                methodName == "having" && qualifierText in criteriaQueryVars -> {
                    for (arg in call.argumentList.expressions) {
                        extractPredicate(arg, ctx)?.let { havings += it }
                    }
                }

                // cq.orderBy(cb.asc(...)) / cq.orderBy(List)
                methodName == "orderBy" && qualifierText in criteriaQueryVars -> {
                    for (arg in call.argumentList.expressions) {
                        for (item in expandListArgument(arg, ctx)) {
                            extractOrderBy(item)?.let { orderBys += it }
                        }
                    }
                }

                // cq.multiselect(...) / cq.select(...)
                methodName in setOf("multiselect", "select") && qualifierText in criteriaQueryVars -> {
                    selectColumns += extractSelectColumns(call, ctx)
                }

                // cq.distinct(true)
                methodName == "distinct" && qualifierText in criteriaQueryVars -> {
                    val arg = call.argumentList.expressions.firstOrNull()
                    if (arg == null || arg.text == "true") distinct = true
                }

                // root.join(...) / root.fetch(...)（T-J7）
                methodName in JOIN_METHODS && qualifierText in ctx.roots -> {
                    registerJoin(call, call, fetch = false, ctx)
                }
                methodName == "fetch" && qualifierText in ctx.roots -> {
                    registerJoin(call, call, fetch = true, ctx)
                }

                // Join.on(...)：显式 ON 优先于外键推断（T-J7）；PSI 遍历外层先于内层 join，先挂起后处理
                methodName == "on" -> pendingOnCalls += call

                // update.set(root.get("name"), v) / update.set("name", v)（T-J6）
                methodName == "set" && qualifierText in criteriaQueryVars && operation == CriteriaOperation.UPDATE -> {
                    extractSetClause(call)?.let { setClauses += it }
                }
                // 防御：个别包装 API 提供 setNull 形态，按 set(path, null) 语义处理
                methodName == "setNull" && qualifierText in criteriaQueryVars && operation == CriteriaOperation.UPDATE -> {
                    extractSetClause(call, nullValue = true)?.let { setClauses += it }
                }

                // Expression.in(...)：path.get("x").in(v1, v2) / .in(collection) / .in(subquery)（T-J17/J13）
                methodName == "in" && qualifier is PsiMethodCallExpression &&
                    qualifier.methodExpression.referenceName == "get" -> {
                    extractExpressionIn(call, qualifier, ctx)?.let { model ->
                        predicates += model
                        findAssignedVariable(call)?.let { v -> ctx.predicateVars[v] = model }
                    }
                }

                // CriteriaBuilder 条件方法（cb.equal / cb.greaterThan / cb.gt ...）
                methodName in CB_CONDITION_METHODS && isCriteriaBuilderCall(call) -> {
                    // 排除作为 and/or/not/where/having 实参的嵌套调用（宿主分支负责提取，避免重复）
                    if (!isNestedInHostPredicate(call)) {
                        recordPredicate(predicates, call, extractPredicateFromCall(call, ctx), ctx)
                    }
                }

                // 链式取反：cb.equal(...).not() / cb.exists(sq).not()（qualifier 为谓词调用）
                methodName == "not" && qualifier is PsiMethodCallExpression -> {
                    if (!isNestedInHostPredicate(call)) {
                        recordPredicate(predicates, call, extractPredicateFromCall(call, ctx), ctx)
                    }
                }
            }
        }

        // Join.on 后处理：join 变量 / 内联 join 调用文本匹配显式 ON 谓词
        for (onCall in pendingOnCalls) {
            val q = onCall.methodExpression.qualifierExpression
            val target = when (q) {
                is PsiReferenceExpression -> ctx.joinVars[q.referenceName]
                is PsiMethodCallExpression -> ctx.allJoins.lastOrNull { it.callText == q.text }
                else -> null
            }
            target?.let { join ->
                for (arg in onCall.argumentList.expressions) {
                    extractPredicate(arg, ctx)?.let { join.onPredicates += it }
                }
            }
        }

        val joins = collectWorkingJoins(ctx).map { it.toModel() }

        // 没有实体信息且无条件/SET → 不是可渲染的 Criteria 调用链
        if (entityClassName == null && predicates.isEmpty() && setClauses.isEmpty()) return null

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
            distinct = distinct,
            operation = operation,
            setClauses = setClauses,
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
                is PsiFile -> return current
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
     * 判断方法调用是否为 cb.xxx() 形式（qualifier 为简单引用）。
     *
     * 由于 CriteriaBuilder 变量类型通常不可 resolve（LightPlatform 无 JPA classpath），
     * 这里采用启发式：方法名属于 CB 条件方法集，且 qualifier 是简单标识符引用。
     */
    private fun isCriteriaBuilderCall(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName ?: return false
        if (methodName !in CB_CONDITION_METHODS) return false
        val qualifier = call.methodExpression.qualifierExpression ?: return false
        // 简单引用表达式（cb.equal(...) 中 qualifier 是 cb）
        return qualifier is PsiReferenceExpression
    }

    /**
     * 检查 call 是否嵌套在负责谓词提取的宿主调用内（and/or/not/where/having 的实参位置）。
     * 嵌套调用会在处理宿主时被提取，扫描循环跳过以避免重复入列。
     */
    private fun isNestedInHostPredicate(call: PsiMethodCallExpression): Boolean {
        var parent: PsiElement? = call.parent
        while (parent != null) {
            when {
                // 实参位置：argumentList → 宿主调用（链式调用要爬过多层才抵达，循环内处理）
                parent is PsiExpressionList -> parent = parent.parent
                // 链式位置：cb.equal(...).not() 中 equal 的 parent 是引用表达式 → 宿主调用
                parent is PsiReferenceExpression -> parent = parent.parent
                // value() 是透明链层：cb.in(x).value(1).value(2) 经两层 value 抵达 where 宿主
                parent is PsiMethodCallExpression && parent.methodExpression.referenceName == "value" ->
                    parent = parent.parent
                else -> break
            }
        }
        val parentCall = parent as? PsiMethodCallExpression ?: return false
        val parentName = parentCall.methodExpression.referenceName ?: return false
        return parentName in PREDICATE_HOST_METHODS
    }

    /** 记录条件模型；调用若赋值给变量则同步登记，供 and/or 组与 where 实参引用解析。 */
    private fun recordPredicate(
        sink: MutableList<PredicateModel>,
        call: PsiMethodCallExpression,
        model: PredicateModel?,
        ctx: LocateContext,
    ) {
        if (model == null) return
        sink += model
        findAssignedVariable(call)?.let { v -> ctx.predicateVars[v] = model }
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
     * 从 createQuery/createCriteriaUpdate/createCriteriaDelete 实参提取结果类名。
     */
    private fun extractResultClass(call: PsiMethodCallExpression): String? {
        // 实参形式：cb.createQuery(Xxx.class)
        val arg = call.argumentList.expressions.firstOrNull()
        if (arg is PsiClassObjectAccessExpression) {
            return (arg.operand.type as? PsiClassType)?.className
        }
        // 类型参数形式：类型参数由 PsiMethodCallExpression 的 typeArguments 提供
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
     *  - `root.get(Xxx_.fieldName)`：静态字段引用
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

    /**
     * 从实参表达式提取字段名（支持 root.get(...) 或字符串字面量）。
     */
    private fun extractFieldNameFromArg(arg: PsiExpression?): String? {
        if (arg is PsiMethodCallExpression && arg.methodExpression.referenceName == "get") {
            return extractFieldName(arg)
        }
        if (arg is PsiLiteralExpression) {
            return arg.value as? String
        }
        return null
    }

    // ------------------------------------------------------------------
    // List 实参解引用（T-J17 bug 修复：multiselect(List) / orderBy(List) / groupBy(List)）
    // ------------------------------------------------------------------

    /**
     * 解引用 List 类型实参为元素表达式列表。
     *
     * 有界实现：
     *  - 变量初始化为 `List.of(...)` / `Arrays.asList(...)` → 直接收集实参；
     *  - 变量初始化为 `new ArrayList<>()` 且以 `list.add(x)` 逐个加入 → 按源码顺序收集 add 实参；
     *  - 其余（无法解析的变量/表达式）→ 原样返回单元素列表。
     */
    private fun expandListArgument(expr: PsiExpression, ctx: LocateContext): List<PsiExpression> {
        if (expr !is PsiReferenceExpression) return listOf(expr)
        val varName = expr.referenceName ?: return listOf(expr)
        // root/join 等实体变量不是列表变量
        if (varName in ctx.roots || varName in ctx.joinVars) return listOf(expr)
        val decl = PsiTreeUtil.collectElementsOfType(ctx.block, PsiLocalVariable::class.java)
            .firstOrNull { it.name == varName } ?: return listOf(expr)
        val initializer = decl.initializer ?: return listOf(expr)
        // List.of(...) / Arrays.asList(...)
        if (initializer is PsiMethodCallExpression) {
            val initName = initializer.methodExpression.referenceName
            if (initName == "of" || initName == "asList") {
                return initializer.argumentList.expressions.toList()
            }
        }
        // new ArrayList<>() + add(...) 逐个收集（按源码顺序）
        if (initializer is PsiNewExpression) {
            val adds = PsiTreeUtil.collectElementsOfType(ctx.block, PsiMethodCallExpression::class.java)
                .filter {
                    it.methodExpression.referenceName == "add" &&
                        it.methodExpression.qualifierExpression?.text == varName
                }
            if (adds.isNotEmpty()) return adds.flatMap { it.argumentList.expressions.toList() }
        }
        return listOf(expr)
    }

    // ------------------------------------------------------------------
    // SELECT 解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.select(...) / cq.multiselect(...) 提取 SELECT 列。
     *
     * 支持：root.get(...) 普通列、聚合函数（含 countDistinct/sumAsLong/greatest 等，T-J17）、
     * 复杂表达式（CASE/COALESCE/CONCAT/算术等，T-J17）、List 变量实参解引用（T-J17 bug 修复）。
     */
    private fun extractSelectColumns(
        call: PsiMethodCallExpression,
        ctx: LocateContext,
    ): List<SelectColumn> {
        val methodName = call.methodExpression.referenceName ?: return emptyList()
        val rawArgs = call.argumentList.expressions
        if (rawArgs.isEmpty()) return emptyList()

        // cq.select(root) → 全列（返回空列表，converter 按全列处理）
        if (methodName == "select" && rawArgs.size == 1) {
            val arg = rawArgs[0]
            if (arg is PsiReferenceExpression && arg.referenceName in ctx.roots) {
                return emptyList()
            }
        }

        val args = rawArgs.flatMap { expandListArgument(it, ctx) }
        return args.mapNotNull { arg ->
            // multiselect(root)：root 整体作选择目标，静态预览按全列语义处理
            if (arg is PsiReferenceExpression && arg.referenceName in ctx.roots) return@mapNotNull null

            // 聚合函数调用
            extractAggregate(arg, ctx)?.let {
                return@mapNotNull SelectColumn(fieldName = it.fieldName, aggregate = it)
            }

            // root.get(...) 普通列
            val getCall = arg as? PsiMethodCallExpression
            if (getCall != null && getCall.methodExpression.referenceName == "get") {
                val fieldName = extractFieldName(getCall) ?: return@mapNotNull null
                return@mapNotNull SelectColumn(fieldName)
            }

            // 复杂表达式（CASE / COALESCE / CONCAT / (a + b) 等，T-J17）
            val exprModel = translateExpression(arg, ctx)
            when (exprModel) {
                is ExpressionModel.Column -> SelectColumn(exprModel.fieldName)
                is ExpressionModel.Raw -> null // 原样降级文本不进 SELECT 列
                else -> SelectColumn(fieldName = null, expression = exprModel)
            }
        }
    }

    /**
     * 尝试从 SELECT 参数表达式提取聚合函数信息（T-J17：countDistinct/sumAsLong/sumAsDouble/greatest/least）。
     */
    private fun extractAggregate(arg: PsiExpression, ctx: LocateContext): AggregateInfo? {
        val call = arg as? PsiMethodCallExpression ?: return null
        val methodName = call.methodExpression.referenceName ?: return null
        val qualifier = call.methodExpression.qualifierExpression
        if (qualifier !is PsiReferenceExpression) return null

        val args = call.argumentList.expressions
        return when (methodName) {
            "countDistinct" -> AggregateInfo(
                function = "COUNT",
                fieldName = extractFieldNameFromArg(args.firstOrNull()),
                distinct = true,
            )
            "sumAsLong" -> AggregateInfo(
                function = "SUM",
                fieldName = extractFieldNameFromArg(args.firstOrNull()),
                castType = CAST_TYPE_MAP.getValue("toLong"),
            )
            "sumAsDouble" -> AggregateInfo(
                function = "SUM",
                fieldName = extractFieldNameFromArg(args.firstOrNull()),
                castType = CAST_TYPE_MAP.getValue("toDouble"),
            )
            "greatest" -> AggregateInfo(
                function = "MAX",
                fieldName = extractFieldNameFromArg(args.firstOrNull()),
            )
            "least" -> AggregateInfo(
                function = "MIN",
                fieldName = extractFieldNameFromArg(args.firstOrNull()),
            )
            else -> {
                val upperName = methodName.uppercase()
                if (upperName !in AGGREGATE_FUNCTIONS) return null
                // cb.sum(a, b) 双参是算术加法而非聚合，交由表达式路径处理（T-J17）
                if (methodName == "sum" && args.size >= 2) return null
                val firstArg = args.firstOrNull()
                when {
                    // cb.count() → COUNT(*)
                    firstArg == null -> AggregateInfo(function = upperName)
                    // cb.count(root)：实参是 Root 变量引用 → COUNT(*) 语义
                    firstArg is PsiReferenceExpression && firstArg.referenceName in ctx.roots ->
                        AggregateInfo(function = upperName)
                    else -> {
                        val fieldName = extractFieldNameFromArg(firstArg)
                        if (fieldName != null) {
                            AggregateInfo(function = upperName, fieldName = fieldName)
                        } else {
                            // 聚合内层复杂表达式（如 AVG(COALESCE(age, 0))）
                            AggregateInfo(function = upperName, expression = translateExpression(firstArg, ctx))
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // WHERE / Predicate 解析
    // ------------------------------------------------------------------

    /**
     * 从 where/having 的实参（谓词调用、谓词变量、谓词列表变量、toArray 形态）提取条件模型。
     */
    private fun extractPredicate(expr: PsiExpression?, ctx: LocateContext): PredicateModel? {
        if (expr == null) return null
        if (expr is PsiMethodCallExpression) {
            // value() 透明链层：cb.in(x).value(a).value(b) 的最外层是 value 调用，下钻到基调用
            if (expr.methodExpression.referenceName == "value") {
                var cursor: PsiExpression = expr
                while (cursor is PsiMethodCallExpression && cursor.methodExpression.referenceName == "value") {
                    cursor = cursor.methodExpression.qualifierExpression ?: return null
                }
                return extractPredicate(cursor, ctx)
            }
            val methodName = expr.methodExpression.referenceName
            return when (methodName) {
                // predicates.toArray(new Predicate[0]) / predicates.add(p)：解引用列表变量（add 收集）
                "toArray", "add" -> {
                    val qualifier = expr.methodExpression.qualifierExpression ?: return null
                    predicateGroup(expandListArgument(qualifier, ctx).mapNotNull { extractPredicate(it, ctx) })
                }
                // List.of(p1, p2) / Arrays.asList(p1, p2)
                "of", "asList" ->
                    predicateGroup(expr.argumentList.expressions.mapNotNull { extractPredicate(it, ctx) })
                // 仅处理 CriteriaBuilder 条件方法，排除 Java 集合工具方法等
                else -> if (methodName in CB_CONDITION_METHODS) extractPredicateFromCall(expr, ctx) else null
            }
        }
        // 变量引用 → 先查已登记的谓词变量，再尝试列表变量解引用
        if (expr is PsiReferenceExpression) {
            ctx.predicateVars[expr.referenceName]?.let { return it }
            val items = expandListArgument(expr, ctx)
            if (items.size > 1 || (items.size == 1 && items[0] !== expr)) {
                val group = predicateGroup(items.mapNotNull { extractPredicate(it, ctx) })
                if (group != null) return group
            }
            // 变量引用不生成 Raw 谓词，避免变量名出现在 WHERE 子句
            return null
        }
        return null
    }

    /** 谓词列表组装：空 → null（不可渲染）；单条件直用；多条件 AND 组。 */
    private fun predicateGroup(children: List<PredicateModel>): PredicateModel? {
        if (children.isEmpty()) return null
        return children.singleOrNull() ?: PredicateModel.Group(GroupConnector.AND, children)
    }

    /**
     * 从 CriteriaBuilder 条件调用提取 PredicateModel。
     *
     * 支持的调用模式（T-J17 全集）：
     *  - cb.equal/notEqual/greaterThan(...)/gt/ge/lt/le → Simple / ExpressionComparison
     *  - cb.isNull/isNotNull → IsNull；cb.between → Between / ExpressionBetween
     *  - cb.like/notLike（含 escapeChar 重载）→ Like
     *  - cb.in(x).value(a).value(b)（链式收集，bug 修复）/ cb.isTrue/isFalse / conjunction/disjunction
     *  - cb.exists(subquery) → Exists；isEmpty/isMember 等集合谓词 → 注释降级 Raw
     *  - cb.and(p1, p2) → Group(AND, [...])；cb.or → Group(OR)；cb.not(p) → Group(AND, [p], negated)
     */
    private fun extractPredicateFromCall(
        call: PsiMethodCallExpression,
        ctx: LocateContext,
    ): PredicateModel {
        val methodName = call.methodExpression.referenceName ?: return PredicateModel.Raw(call.text)
        val args = call.argumentList.expressions

        return when (methodName) {
            "equal" -> buildComparison(call, args, "=", ctx)
            "notEqual" -> buildComparison(call, args, "!=", ctx)
            "greaterThan", "gt" -> buildComparison(call, args, ">", ctx)
            "greaterThanOrEqualTo", "ge" -> buildComparison(call, args, ">=", ctx)
            "lessThan", "lt" -> buildComparison(call, args, "<", ctx)
            "lessThanOrEqualTo", "le" -> buildComparison(call, args, "<=", ctx)

            "between" -> buildBetween(call, args, ctx)

            "isNull" -> buildUnaryNull(call, args, negated = false, ctx)
            "isNotNull" -> buildUnaryNull(call, args, negated = true, ctx)

            "isTrue" -> buildBoolean(call, args, value = true, ctx)
            "isFalse" -> buildBoolean(call, args, value = false, ctx)

            "like" -> buildLike(call, args, negated = false, ctx)
            "notLike" -> buildLike(call, args, negated = true, ctx)

            // Expression.in 形态（path.get("x").in(...)）的 qualifier 是 get 调用 → 走 Expression.in 路径
            "in" -> {
                val qualifier = call.methodExpression.qualifierExpression
                if (qualifier is PsiMethodCallExpression && qualifier.methodExpression.referenceName == "get") {
                    extractExpressionIn(call, qualifier, ctx) ?: PredicateModel.Raw(call.text)
                } else {
                    buildIn(call, args, ctx)
                }
            }

            "conjunction" -> PredicateModel.BooleanConstant(value = true)
            "disjunction" -> PredicateModel.BooleanConstant(value = false)

            "exists" -> buildExists(call, args, ctx)

            // 集合谓词：IS [NOT] EMPTY / [NOT] MEMBER OF 需集合子查询，静态渲染不可行 → 注释降级（T-J17）
            "isEmpty" -> degradeCollectionPredicate(call, args, "IS EMPTY", ctx)
            "isNotEmpty" -> degradeCollectionPredicate(call, args, "IS NOT EMPTY", ctx)
            "isMember" -> degradeCollectionPredicate(call, args, "MEMBER OF", ctx)
            "isNotMember" -> degradeCollectionPredicate(call, args, "NOT MEMBER OF", ctx)

            "and" -> buildGroup(args, GroupConnector.AND, ctx)
            "or" -> buildGroup(args, GroupConnector.OR, ctx)
            "not" -> buildNot(call, args, ctx)

            else -> PredicateModel.Raw(call.text)
        }
    }

    /** 构建二元比较条件（左值为列 → Simple；左值为函数/运算/CASE 表达式 → ExpressionComparison）。 */
    private fun buildComparison(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        op: String,
        ctx: LocateContext,
    ): PredicateModel {
        if (args.size < 2) return PredicateModel.Raw(call.text)
        val left = args[0]
        val leftFieldName = extractFieldNameFromArg(left)
        val right = args[1]

        // 列对列比较（Join.on / root.get 与 join.get 互比，T-J7）
        val rightGet = right as? PsiMethodCallExpression
        if (leftFieldName != null && rightGet != null && rightGet.methodExpression.referenceName == "get") {
            val rightFieldName = extractFieldName(rightGet)
            if (rightFieldName != null) {
                return PredicateModel.ColumnEquals(
                    leftFieldName = leftFieldName,
                    rightFieldName = rightFieldName,
                    op = op,
                    leftVariable = receiverVariable(left),
                    rightVariable = receiverVariable(rightGet),
                )
            }
        }

        if (leftFieldName != null) {
            // 普通列左值 + 值右值：保持既有 Simple 形态；literal()/parameter() 展开为值文本
            val rightModel = translateExpression(right, ctx)
            val rightText = if (rightModel is ExpressionModel.Value) rightModel.expressionText else right.text
            return PredicateModel.Simple(leftFieldName, op, rightText)
        }
        // 表达式左值（LOWER(x) / (a+b) / CASE ... 等，T-J17）
        val leftModel = translateExpression(left, ctx)
        if (leftModel is ExpressionModel.Raw && leftModel.expressionText == call.text) {
            return PredicateModel.Raw(call.text)
        }
        return PredicateModel.ExpressionComparison(leftModel, op, translateExpression(right, ctx))
    }

    /** get 调用接收者变量名（root / deptJoin 等）。 */
    private fun receiverVariable(getCall: PsiExpression): String? =
        (getCall as? PsiMethodCallExpression)?.methodExpression?.qualifierExpression
            ?.let { it as? PsiReferenceExpression }?.referenceName

    /** 构建 BETWEEN 条件（左值为表达式时走 ExpressionBetween，T-J17）。 */
    private fun buildBetween(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        ctx: LocateContext,
    ): PredicateModel {
        if (args.size < 3) return PredicateModel.Raw(call.text)
        val fieldName = extractFieldNameFromArg(args[0])
        if (fieldName != null) {
            return PredicateModel.Between(fieldName, args[1].text, args[2].text)
        }
        val leftModel = translateExpression(args[0], ctx)
        if (leftModel is ExpressionModel.Raw && leftModel.expressionText == call.text) {
            return PredicateModel.Raw(call.text)
        }
        return PredicateModel.ExpressionBetween(
            leftModel,
            translateExpression(args[1], ctx),
            translateExpression(args[2], ctx),
        )
    }

    /** 构建 IS NULL / IS NOT NULL 条件（左值为表达式时复用 ExpressionComparison 的 IS NULL op）。 */
    private fun buildUnaryNull(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        negated: Boolean,
        ctx: LocateContext,
    ): PredicateModel {
        if (args.isEmpty()) return PredicateModel.Raw(call.text)
        val fieldName = extractFieldNameFromArg(args[0])
        if (fieldName != null) return PredicateModel.IsNull(fieldName, negated)
        val leftModel = translateExpression(args[0], ctx)
        if (leftModel is ExpressionModel.Raw && leftModel.expressionText == call.text) {
            return PredicateModel.Raw(call.text)
        }
        return PredicateModel.ExpressionComparison(
            leftModel,
            if (negated) "IS NOT NULL" else "IS NULL",
            ExpressionModel.Value("null"),
        )
    }

    /** 构建 isTrue / isFalse 条件（→ col = TRUE / col = FALSE）。 */
    private fun buildBoolean(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        value: Boolean,
        ctx: LocateContext,
    ): PredicateModel {
        if (args.isEmpty()) return PredicateModel.Raw(call.text)
        val literal = if (value) "TRUE" else "FALSE"
        val fieldName = extractFieldNameFromArg(args[0])
        if (fieldName != null) return PredicateModel.Simple(fieldName, "=", literal)
        val leftModel = translateExpression(args[0], ctx)
        if (leftModel is ExpressionModel.Raw && leftModel.expressionText == call.text) {
            return PredicateModel.Raw(call.text)
        }
        return PredicateModel.ExpressionComparison(leftModel, "=", ExpressionModel.Value(literal))
    }

    /** 构建 LIKE / NOT LIKE 条件（escapeChar 重载 → ESCAPE 渲染，T-J17）。 */
    private fun buildLike(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        negated: Boolean,
        ctx: LocateContext,
    ): PredicateModel {
        if (args.size < 2) return PredicateModel.Raw(call.text)
        val fieldName = extractFieldNameFromArg(args[0]) ?: return PredicateModel.Raw(call.text)
        // like(x, pattern, escapeChar) 三参重载
        val escapeChar = args.getOrNull(2)?.text
        return PredicateModel.Like(fieldName, negated, args[1].text, escapeChar)
    }

    /**
     * 构建 IN 条件（cb.in(x) 返回 CriteriaBuilder.In 的形态）。
     *
     * 支持形态：
     *  - `cb.in(root.get("a")).value(v1).value(v2)`：链式收集（T-J17 bug 修复：原实现只沿
     *    parent 找一层且 PSI 上 parent 是引用表达式，实际取不到值）
     *  - `cb.in(root.get("a"), v1, v2)`：直接实参（历史兼容）
     *  - 链尾 `.not()` 由 buildNot 统一包裹为 NOT 组（jakarta 3.1 无 cb.notIn）
     */
    private fun buildIn(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        ctx: LocateContext,
    ): PredicateModel {
        if (args.isEmpty()) return PredicateModel.Raw(call.text)
        val fieldName = extractFieldNameFromArg(args[0]) ?: return PredicateModel.Raw(call.text)
        val chainedValues = collectInChain(call)
        val directValues = if (args.size > 1) args.drop(1).map { it.text } else emptyList()
        val values = (directValues + chainedValues).ifEmpty {
            // 无值时添加占位符标记，面板可输入多值
            listOf("__IN_PLACEHOLDER__")
        }
        return PredicateModel.In(fieldName, negated = false, valueExpressions = values)
    }

    /**
     * Expression.in 形态：`path.get("x").in(v1, v2)` / `.in(collectionVar)` / `.in(subquery)`。
     *
     * @param qualifier `in` 调用的 qualifier（须为 get 调用）
     */
    private fun extractExpressionIn(
        call: PsiMethodCallExpression,
        qualifier: PsiMethodCallExpression,
        ctx: LocateContext,
    ): PredicateModel? {
        val fieldName = extractFieldName(qualifier) ?: return null
        val args = call.argumentList.expressions

        // path.get("x").in(subquery) → IN 子查询（T-J17/J13）
        val subqueryRef = args.firstOrNull() as? PsiReferenceExpression
        if (args.size == 1 && subqueryRef != null) {
            val subQuery = subqueryRef.referenceName?.let { ctx.subqueries[it] }
            if (subQuery != null) {
                return PredicateModel.InSubQuery(
                    fieldName = fieldName,
                    subQuery = subQuery,
                    negated = false,
                )
            }
        }

        val values = args.map { it.text }.ifEmpty { listOf("__IN_PLACEHOLDER__") }
        return PredicateModel.In(fieldName, negated = false, valueExpressions = values)
    }

    /**
     * 沿调用链向上收集 `cb.in(...).value(a).value(b)` 的值实参。
     *
     * PSI 结构：`cb.in(x)` 的 parent 是引用表达式 `value`，其 parent 才是 `.value(a)` 调用，
     * 因此须按「引用表达式 → 方法调用」交替向上行走。链尾 `.not()` 不在此消费——
     * 由调用链上的 not 分支统一包裹 NOT 组，避免双重取反。
     */
    private fun collectInChain(call: PsiMethodCallExpression): List<String> {
        val values = mutableListOf<String>()
        var current: PsiElement? = call.parent
        while (true) {
            val ref = current as? PsiReferenceExpression ?: break
            val chained = ref.parent as? PsiMethodCallExpression ?: break
            if (chained.methodExpression.referenceName != "value") break
            chained.argumentList.expressions.forEach { values += it.text }
            current = chained.parent
        }
        return values
    }

    /** cb.exists(subquery) → EXISTS 子查询（T-J17/J13）。 */
    private fun buildExists(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        ctx: LocateContext,
    ): PredicateModel {
        val ref = args.firstOrNull() as? PsiReferenceExpression
        val subquery = ref?.referenceName?.let { ctx.subqueries[it] }
            ?: return PredicateModel.Raw(call.text)
        return PredicateModel.Exists(subQuery = subquery, negated = false)
    }

    /** 集合谓词注释降级：IS EMPTY / MEMBER OF 等 JPQL 语义无直接 SQL 对应，静态渲染不可行。 */
    private fun degradeCollectionPredicate(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        semantic: String,
        ctx: LocateContext,
    ): PredicateModel {
        val target = args.firstOrNull()?.text ?: call.text
        return PredicateModel.Raw(
            "/* 集合谓词 $semantic($target)：需按关联表生成子查询，静态预览暂不支持 */",
        )
    }

    /** 构建 AND / OR 条件组。 */
    private fun buildGroup(
        args: Array<PsiExpression>,
        connector: GroupConnector,
        ctx: LocateContext,
    ): PredicateModel {
        val children = args.mapNotNull { extractPredicate(it, ctx) }
        return PredicateModel.Group(connector, children)
    }

    /**
     * 构建 NOT 条件：cb.not(p) 与链式 cb.equal(...).not()（后者实参为空、谓词在 qualifier 上）。
     * qualifier 为 value 链中间层时（cb.in(x).value(1).not()）下钻到基调用提取。
     */
    private fun buildNot(
        call: PsiMethodCallExpression,
        args: Array<PsiExpression>,
        ctx: LocateContext,
    ): PredicateModel {
        var inner: PsiExpression? = args.firstOrNull() ?: call.methodExpression.qualifierExpression
            ?: return PredicateModel.Raw(call.text)
        // value() 透明链层：下钻到 cb.in(...) 等基调用
        while (inner is PsiMethodCallExpression && inner.methodExpression.referenceName == "value") {
            inner = inner.methodExpression.qualifierExpression ?: break
        }
        val child = extractPredicate(inner, ctx) ?: return PredicateModel.Raw(call.text)
        return PredicateModel.Group(GroupConnector.AND, listOf(child), negated = true)
    }

    // ------------------------------------------------------------------
    // 表达式解析（T-J17：cb 表达式函数全集 → ExpressionModel）
    // ------------------------------------------------------------------

    /**
     * 表达式翻译：将 cb 表达式调用 / 字面量 / 变量翻译为 [ExpressionModel]。
     * 无法结构化的形态降级 Raw（converter 原样透传），绝不抛异常。
     */
    private fun translateExpression(expr: PsiExpression?, ctx: LocateContext): ExpressionModel {
        if (expr == null) return ExpressionModel.Raw("?")
        when (expr) {
            is PsiMethodCallExpression -> {
                val methodName = expr.methodExpression.referenceName
                val qualifier = expr.methodExpression.qualifierExpression

                when (methodName) {
                    "get" -> extractFieldName(expr)?.let { return ExpressionModel.Column(it) }
                    // cb.literal(x)：字符串/数值按实参文本交由 converter 求值
                    "literal" -> return ExpressionModel.Value(
                        expr.argumentList.expressions.firstOrNull()?.text ?: "?",
                    )
                    // cb.nullLiteral() → NULL
                    "nullLiteral" -> return ExpressionModel.Value("null")
                    // cb.parameter("name") → ? 占位（取未加引号的参数名，converter 识别为占位符）
                    "parameter" -> {
                        val arg = expr.argumentList.expressions.firstOrNull()
                        val name = (arg as? PsiLiteralExpression)?.value as? String
                            ?: arg?.text ?: "?"
                        return ExpressionModel.Value(name)
                    }
                    // 链式 value()：cb.coalesce(x).value(y).value(z)
                    "value" -> return translateValueChain(expr, ctx)
                    // CASE 链（最外层为 when/otherwise，T-J17）
                    "when", "otherwise" -> translateCaseChain(expr, ctx)?.let { return it }
                    // Expression.not()：布尔表达式取反
                    "not" -> qualifier?.let {
                        return ExpressionModel.Function("NOT", listOf(translateExpression(it, ctx)))
                    }
                }

                if (methodName != null && methodName in CB_EXPRESSION_FUNCTIONS && qualifier is PsiReferenceExpression) {
                    return translateFunctionCall(expr, methodName, ctx)
                }
                // 链式调用的中间层（如 .value() 之上的嵌套）降级原文
                return ExpressionModel.Raw(expr.text)
            }
            is PsiLiteralExpression -> return ExpressionModel.Value(expr.text)
            is PsiClassObjectAccessExpression -> return ExpressionModel.Value(expr.text)
            is PsiReferenceExpression -> return ExpressionModel.Value(expr.text)
            else -> return ExpressionModel.Raw(expr.text)
        }
    }

    /**
     * 翻译 cb 表达式函数调用为 Function 节点。
     *
     * 函数名规范化：
     *  - 算术：sum(2 参)=ADD、prod=MUL、diff=SUB、quot=DIV、mod=MOD、neg=NEG（sum 1 参=SUM 聚合）
     *  - 转换：toLong/.../toString → CAST + modifier 目标类型（统一 SQL 标准型）
     *  - trim：Trimspec 实参 → modifier（TRIM(LEADING FROM x)）
     *  - function(name, type, args...)：name 原样拼接（白名单为远期项，注释说明）
     *  - size：集合统计需子查询，静态渲染不可行 → 注释降级
     */
    private fun translateFunctionCall(
        call: PsiMethodCallExpression,
        methodName: String,
        ctx: LocateContext,
    ): ExpressionModel {
        val args = call.argumentList.expressions

        // cb.count(root) → COUNT(*)：实参是 Root 变量引用而非 get 调用
        if (methodName == "count" && args.size == 1) {
            val ref = args[0] as? PsiReferenceExpression
            if (ref != null && ref.referenceName in ctx.roots) {
                return ExpressionModel.Function("COUNT", emptyList())
            }
        }

        // function(name, type, args...)：原样拼接 name(args)；返回类型实参跳过
        if (methodName == "function") {
            val fnName = (args.getOrNull(0) as? PsiLiteralExpression)?.value as? String
                ?: args.getOrNull(0)?.text
                ?: return ExpressionModel.Raw(call.text)
            val realArgs = args.drop(2).map { translateExpression(it, ctx) }
            return ExpressionModel.Function(fnName, realArgs)
        }

        // size(collection)：JPQL SIZE 语义须展开为关联表 COUNT 子查询，静态不可行 → 注释降级
        if (methodName == "size") {
            val target = args.firstOrNull()?.text ?: call.text
            return ExpressionModel.Raw("/* cb.size($target)：集合统计需子查询，静态预览暂不支持 */")
        }

        // trim(x) / trim(Trimspec, x)
        if (methodName == "trim") {
            val modifier = args.firstOrNull()
                ?.let { it as? PsiReferenceExpression }
                ?.referenceName
                ?.takeIf { it in TRIM_SPECS }
            val realArgs = (if (modifier != null) args.drop(1) else args.toList())
            return ExpressionModel.Function("TRIM", realArgs.map { translateExpression(it, ctx) }, modifier)
        }

        // sum：1 参为 SUM 聚合，2 参为加法
        if (methodName == "sum") {
            val function = if (args.size >= 2) "ADD" else "SUM"
            return ExpressionModel.Function(function, args.map { translateExpression(it, ctx) })
        }

        // 转换函数 → CAST(x AS 类型)
        if (methodName in CAST_TYPE_MAP) {
            val first = args.firstOrNull() ?: return ExpressionModel.Raw(call.text)
            return ExpressionModel.Function(
                "CAST",
                listOf(translateExpression(first, ctx)),
                CAST_TYPE_MAP[methodName],
            )
        }

        val function = when (methodName) {
            "prod" -> "MUL"
            "diff" -> "SUB"
            "quot" -> "DIV"
            "mod" -> "MOD"
            "neg" -> "NEG"
            else -> methodName.uppercase()
        }
        return ExpressionModel.Function(function, args.map { translateExpression(it, ctx) })
    }

    /**
     * 链式 value() 翻译：`cb.coalesce(x).value(y).value(z)` → COALESCE(x, y, z)。
     * 基调用非 coalesce 时按基调用翻译（链式实参丢弃降级）。
     */
    private fun translateValueChain(expr: PsiMethodCallExpression, ctx: LocateContext): ExpressionModel {
        var base: PsiExpression = expr
        val chained = mutableListOf<PsiExpression>()
        while (base is PsiMethodCallExpression && base.methodExpression.referenceName == "value") {
            chained += base.argumentList.expressions
            base = base.methodExpression.qualifierExpression ?: break
        }
        val baseModel = translateExpression(base, ctx)
        if (chained.isEmpty() || baseModel !is ExpressionModel.Function || baseModel.function != "COALESCE") {
            return baseModel
        }
        return baseModel.copy(args = baseModel.args + chained.reversed().map { translateExpression(it, ctx) })
    }

    /**
     * CASE 链翻译（T-J17）：
     *  - `cb.selectCase().when(cond, r1).when(cond2, r2).otherwise(e)` → searched case
     *  - `cb.selectCase(expr).when(v1, r1).otherwise(e)` → simple case
     *
     * @return Case 模型；链结构不符（基调用非 selectCase）时返回 null 走降级
     */
    private fun translateCaseChain(
        outermost: PsiMethodCallExpression,
        ctx: LocateContext,
    ): ExpressionModel? {
        val whens = mutableListOf<Pair<PsiExpression, PsiExpression>>()
        var otherwiseArg: PsiExpression? = null
        var cursor: PsiMethodCallExpression = outermost

        // 最外层可能是 otherwise
        if (cursor.methodExpression.referenceName == "otherwise") {
            otherwiseArg = cursor.argumentList.expressions.firstOrNull()
            cursor = cursor.methodExpression.qualifierExpression as? PsiMethodCallExpression ?: return null
        }
        // 逐层下钻 when
        while (cursor.methodExpression.referenceName == "when") {
            val args = cursor.argumentList.expressions
            if (args.size < 2) return null
            whens += args[0] to args[1]
            cursor = cursor.methodExpression.qualifierExpression as? PsiMethodCallExpression ?: return null
        }
        if (cursor.methodExpression.referenceName != "selectCase") return null
        val simpleExpr = cursor.argumentList.expressions.firstOrNull()

        return ExpressionModel.Case(
            simpleExpr = simpleExpr?.let { translateExpression(it, ctx) },
            whens = whens.reversed().map { (cond, result) ->
                if (simpleExpr == null) {
                    // searched case：条件是谓词
                    CaseWhen(predicate = extractPredicate(cond, ctx), result = translateExpression(result, ctx))
                } else {
                    // simple case：条件是匹配值
                    CaseWhen(condition = translateExpression(cond, ctx), result = translateExpression(result, ctx))
                }
            },
            otherwise = otherwiseArg?.let { translateExpression(it, ctx) },
        )
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
    // JOIN / FETCH 解析（T-J7）
    // ------------------------------------------------------------------

    /**
     * 从 root.join(...)/fetch(...) 调用提取 join 记录并登记变量。
     *
     * 覆盖 join/joinCollection/joinSet/joinList/joinMap/fetch 的 (attr[, JoinType]) 实参形态；
     * 无 JoinType 实参时按 JPA 规范默认 INNER。
     */
    private fun registerJoin(
        call: PsiMethodCallExpression,
        joinCall: PsiMethodCallExpression,
        fetch: Boolean,
        ctx: LocateContext,
    ) {
        val args = call.argumentList.expressions
        if (args.isEmpty()) return

        // 关联属性：字符串字面量或方法引用
        val fieldName = when (val firstArg = args[0]) {
            is PsiLiteralExpression -> firstArg.value as? String
            is PsiReferenceExpression -> firstArg.referenceName
            else -> firstArg.text
        } ?: return

        // JoinType（join(String) 无实参时按规范默认 INNER）
        val joinType = if (args.size > 1) {
            val typeArg = args[1]
            if (typeArg is PsiReferenceExpression) {
                mapJoinType(typeArg.referenceName)
            } else {
                JoinType.INNER
            }
        } else {
            JoinType.INNER
        }

        val variableName = findAssignedVariable(call)
        val join = WorkingJoin(
            callText = joinCall.text,
            fieldName = fieldName,
            joinType = joinType,
            fetch = fetch,
            variableName = variableName,
        )
        ctx.allJoins += join
        variableName?.let { ctx.joinVars[it] = join }
    }

    /** 汇总全部 join 记录（变量登记 + 内联 root.join("d").on(...) 形态均按发现顺序）。 */
    private fun collectWorkingJoins(ctx: LocateContext): List<WorkingJoin> = ctx.allJoins

    /** JoinType 枚举名映射。 */
    private fun mapJoinType(enumName: String?): JoinType = when {
        enumName in JOIN_TYPE_LEFT -> JoinType.LEFT
        enumName in JOIN_TYPE_INNER -> JoinType.INNER
        enumName in JOIN_TYPE_RIGHT -> JoinType.RIGHT
        else -> JoinType.INNER
    }

    // ------------------------------------------------------------------
    // 子查询解析（T-J17/J13：单层有界实现）
    // ------------------------------------------------------------------

    /**
     * 收集子查询变量与其 from/select/where 子句。
     *
     * correlate 关联与「子查询 where 中再引用子查询」的多层嵌套无法静态渲染 →
     * 置 degradedReason，converter 整体降级 Raw 注释。
     */
    private fun collectSubqueries(
        allCalls: Collection<PsiMethodCallExpression>,
        criteriaQueryVars: Set<String>,
        ctx: LocateContext,
    ) {
        // 1. subquery 变量登记：`Subquery<T> sq = query.subquery(...)`
        for (call in allCalls) {
            val methodName = call.methodExpression.referenceName ?: continue
            if (methodName != "subquery") continue
            val qualifierText = call.methodExpression.qualifierExpression?.text ?: continue
            if (qualifierText !in criteriaQueryVars) continue
            findAssignedVariable(call)?.let { ctx.subqueries[it] = SubqueryModel() }
        }
        if (ctx.subqueries.isEmpty()) return

        // 2. 子查询子句：sq.from / sq.select / sq.where / sq.correlate
        val pending = mutableListOf<Pair<String, PsiMethodCallExpression>>()
        for (call in allCalls) {
            val methodName = call.methodExpression.referenceName ?: continue
            val qualifierText = call.methodExpression.qualifierExpression?.text ?: continue
            if (qualifierText !in ctx.subqueries) continue
            when (methodName) {
                "from" -> {
                    val arg = call.argumentList.expressions.firstOrNull()
                    ctx.subqueries[qualifierText] = ctx.subqueries.getValue(qualifierText)
                        .copy(entityClassName = extractEntityClassName(arg))
                    findAssignedVariable(call)?.let { ctx.subRoots += it }
                }
                "select" -> {
                    val fieldName = call.argumentList.expressions.firstOrNull()
                        ?.let { extractFieldNameFromArg(it) }
                    if (fieldName != null) {
                        ctx.subqueries[qualifierText] = ctx.subqueries.getValue(qualifierText)
                            .copy(selectFieldName = fieldName)
                    }
                }
                "where" -> {
                    val children = call.argumentList.expressions.mapNotNull { extractPredicate(it, ctx) }
                    val current = ctx.subqueries.getValue(qualifierText)
                    ctx.subqueries[qualifierText] = current.copy(predicates = current.predicates + children)
                }
                "correlate" -> ctx.subqueries[qualifierText] = ctx.subqueries.getValue(qualifierText)
                    .copy(degradedReason = "correlate 关联子查询")
                else -> pending += qualifierText to call
            }
        }

        // 3. 多层嵌套检测：子查询变量链上的子查询（sq2 = sq1... 或 where 中引用其他子查询变量）
        for ((varName, call) in pending) {
            val referencesSubquery = PsiTreeUtil.collectElementsOfType(call, PsiReferenceExpression::class.java)
                .any { it.referenceName in ctx.subqueries && it.referenceName != varName }
            if (referencesSubquery) {
                ctx.subqueries[varName] = ctx.subqueries.getValue(varName)
                    .copy(degradedReason = "多层嵌套子查询")
            }
        }
        // 子查询谓词内含 EXISTS/IN 子查询 → 同样按多层嵌套降级
        for ((varName, sub) in ctx.subqueries) {
            if (containsSubqueryPredicate(sub.predicates)) {
                ctx.subqueries[varName] = sub.copy(degradedReason = "多层嵌套子查询")
            }
        }
    }

    /** 谓词树中是否含子查询节点（有界：子查询内不再嵌子查询）。 */
    private fun containsSubqueryPredicate(predicates: List<PredicateModel>): Boolean =
        predicates.any { containsSubqueryPredicate(it) }

    private fun containsSubqueryPredicate(predicate: PredicateModel): Boolean = when (predicate) {
        is PredicateModel.Exists, is PredicateModel.InSubQuery -> true
        is PredicateModel.Group -> predicate.children.any { containsSubqueryPredicate(it) }
        else -> false
    }

    // ------------------------------------------------------------------
    // UPDATE SET 解析（T-J6）
    // ------------------------------------------------------------------

    /**
     * 从 update.set(root.get("name"), v) / update.set("name", v) / setNull 提取 SET 子句。
     */
    private fun extractSetClause(
        call: PsiMethodCallExpression,
        nullValue: Boolean = false,
    ): CriteriaSetClause? {
        val args = call.argumentList.expressions
        val fieldName = args.firstOrNull()?.let { arg ->
            when {
                arg is PsiMethodCallExpression && arg.methodExpression.referenceName == "get" ->
                    extractFieldName(arg)
                arg is PsiLiteralExpression -> arg.value as? String
                arg is PsiReferenceExpression -> arg.referenceName
                else -> null
            }
        } ?: return null
        val value = if (nullValue) "null" else args.getOrNull(1)?.text ?: "null"
        return CriteriaSetClause(fieldName, value)
    }
}
