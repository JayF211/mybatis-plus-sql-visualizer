package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.psi.ArgResolver
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

/**
 * MyBatis Dynamic SQL 转换器（DS，M44 其他 ORM 第一批，矩阵 §7）。
 *
 * MyBatis 官方 DSL（javap org.mybatis.dynamic-sql 2.0.0 实证）：手写 SqlTable 常量（无生成代码）
 * → builder 链 → build() 出 SelectModel → render(RenderingStrategy) 出 SQL：
 * `SelectDSL.select(UserDynamicSqlSupport.userId).from(userTable).where(col, isEqualTo(1)
 * .and(col2, isIn(1, 2))).orderBy(col.descending()).limit(10).build().render(...)`
 *
 * ## 链面（javap SelectDSL / LimitAndOffsetOperations / WhereOperations）
 *  - entry：`SelectDSL.select(BasicColumn...)/selectDistinct(...)`（static，2.0 dsl 包）；
 *  - `from(SqlTable[, alias])`、`where(col, 谓词[.and(col, 谓词)/.or(...)])`、`orderBy(排序列...)`、
 *    `limit(n)`/`offset(n)`（build/render/union 段忽略）；
 *  - 谓词（SqlBuilder 静态工厂 / org.mybatis.dynamic.sql.condition.*）：isEqualTo/isNotEqualTo/
 *    isIn/notIn/isLike/isNull/isNotNull/isBetween/isNotBetween/greaterThan/greaterThanOrEqualTo/
 *    lessThan/lessThanOrEqualTo（WhenPresent 变体同法近似渲染）；
 *  - update/delete 基础形态：`UpdateDSL.update(table).set(col).equalTo(v).where(...)` /
 *    `DeleteDSL.deleteFrom(table).where(col, isEqualTo(...))`（1.x SqlBuilder 同名入口兼容）。
 *
 * ## 列常量解析（SqlColumn 字段声明处初始化，无生成代码依赖）
 *  `UserDynamicSqlSupport.userId` → 字段初始化 `user.id`（转发引用）→ SqlTable 子类字段
 *  `column("id", JDBCType.BIGINT)` → 列名 = 首个字符串字面量、所属表 = 子类构造 `super("t_user")`
 *  串 / `SqlTable.of("t_user")` / `new SqlTable("t_user")`。解析失败按字段名 snake 兜底。
 *
 * ## 占位符形态说明
 *  MDS 运行时渲染按 RenderingStrategy 产生 `#{parameters[..]}`（MyBatis3）或 `:name`
 *  （SpringNamedParameter）形态；本插件参数面板统一 `?` 顺序占位 + 参数行（预览尾部注释标注）。
 *
 * ## 边界（MVP）
 *  - insert/insertMultiple/insertSelect（记录形态）暂不建模；union/with/子查询条件暂不建模；
 *  - `and(...)/or(...)` 分组谓词、not(...) 嵌套暂不建模（忽略，不出错误 SQL）。
 */
object MdsConverter {

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /** MyBatis Dynamic SQL 预览结果。 */
    data class MdsPreview(
        val methodName: String,
        val statement: SqlStatement,
        val sql: String,
        val paramSources: List<String> = emptyList(),
        val paramHints: List<ParamHint> = emptyList(),
        val tableName: String = "",
        val columns: List<String> = emptyList(),
        val warning: String? = null,
    )

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    /** entry 工厂类（2.0 dsl 包 SelectDSL/UpdateDSL/DeleteDSL + 1.x SqlBuilder 兼容）。 */
    private val ENTRY_CLASSES: Set<String> = setOf("SelectDSL", "SqlBuilder", "DeleteDSL", "UpdateDSL")

    /** entry 方法名（select 系 SELECT；deleteFrom DELETE；update UPDATE）。 */
    private val ENTRY_METHODS: Set<String> = setOf("select", "selectDistinct", "deleteFrom", "update")

    /** 比较谓词 → 操作符（SqlBuilder 静态工厂）。 */
    private val COMPARE_PREDICATES: Map<String, String> = mapOf(
        "isEqualTo" to "=",
        "isNotEqualTo" to "!=",
        "greaterThan" to ">",
        "greaterThanOrEqualTo" to ">=",
        "lessThan" to "<",
        "lessThanOrEqualTo" to "<=",
    )

    /** IN / LIKE / BETWEEN / 一元谓词。 */
    private val IN_PREDICATES: Set<String> = setOf("isIn", "notIn")
    private val UNARY_PREDICATES: Map<String, String> = mapOf(
        "isNull" to "IS NULL", "isNotNull" to "IS NOT NULL",
    )
    private val LIKE_PREDICATES: Set<String> = setOf("isLike")
    private val BETWEEN_PREDICATES: Set<String> = setOf("isBetween", "isNotBetween")
    private val PREDICATES: Set<String> =
        COMPARE_PREDICATES.keys + IN_PREDICATES + UNARY_PREDICATES.keys + LIKE_PREDICATES + BETWEEN_PREDICATES

    /** where 谓词链连接方法（and(col, pred) / or(col, pred)）。 */
    private val CONNECTORS: Set<String> = setOf("and", "or")

    /** 列常量初始化方法名（SqlTable.column / SqlTable.of）。 */
    private val COLUMN_INITIALIZER_METHODS: Set<String> = setOf("column", "of")

    /** 链遍历深度守卫。 */
    private const val MAX_CHAIN_DEPTH = 32

    // ------------------------------------------------------------------
    // 识别与入口
    // ------------------------------------------------------------------

    /** 调用是否为 MDS DSL 链头（静态工厂类 + entry 方法名）。 */
    fun isMdsCall(call: PsiMethodCallExpression): Boolean = runCatching {
        val methodName = call.methodExpression.referenceName ?: return@runCatching false
        if (methodName !in ENTRY_METHODS) return@runCatching false
        val qualifierName = (call.methodExpression.qualifierExpression as? PsiReferenceExpression)?.referenceName
        qualifierName in ENTRY_CLASSES
    }.getOrDefault(false)

    /**
     * 转换 MDS 链（链上任意位置调用均可，自动回溯 entry）。
     * 解析失败（无 from 表等）返回 null——不做错误 SQL 降级。
     */
    fun convert(call: PsiMethodCallExpression, resolveVariables: Boolean = true): MdsPreview? = runCatching {
        convertInternal(call, resolveVariables)
    }.getOrNull()

    private fun convertInternal(call: PsiMethodCallExpression, resolveVariables: Boolean): MdsPreview? {
        val entry = findEntryCall(call, MAX_CHAIN_DEPTH) ?: return null
        val entryName = entry.methodExpression.referenceName ?: return null
        val segments = collectSegments(entry)

        val ctx = MdsCtx()
        // entry 实参 = SELECT 列清单（select(colA, colB)）；allColumns() 等方法调用实参解析为 null
        // → 不入列清单（生成器降级全列展开）
        if (entryName == "select" || entryName == "selectDistinct") {
            for (arg in entry.argumentList.expressions) {
                resolveColumnName(arg, ctx)?.let { ctx.selectColumns.add(ColumnRef(columnName = it)) }
            }
        }
        // update(table) / deleteFrom(table)：表名来自 entry 首参（SqlTable 常量）
        if (entryName == "deleteFrom" || entryName == "update") {
            ctx.tableName = entry.argumentList.expressions.firstOrNull()?.let { resolveSqlTableName(it) }
        }
        for (segment in segments.drop(1)) {
            when (segment.methodExpression.referenceName) {
                "from" -> parseFrom(segment, ctx)
                "where" -> ctx.addConditions(parseWhereCall(segment, ctx))
                "orderBy" -> ctx.addOrderBy(parseOrderBy(segment, ctx))
                "limit" -> ctx.limit = limitArg(segment)?.let { LimitClause(count = it) }
                "offset" -> ctx.offset = limitArg(segment)
                "set" -> parseSet(segment, ctx)
                "equalTo", "equalToWhenPresent" -> completeSet(segment, ctx)
                else -> Unit // build/render/union/distinct 等段忽略
            }
        }
        val tableName = ctx.tableName ?: return null
        val table = TableInfo(entityClassName = tableName, tableName = tableName, columns = emptyList())
        val notes = mutableListOf(
            "-- MyBatis Dynamic SQL：运行时渲染占位符为 #{parameters[..]}（MyBatis3 策略），预览统一 ? 顺序占位",
        )
        val statement: SqlStatement = when (entryName) {
            "select", "selectDistinct" -> SelectStatement(
                table,
                selectColumns = ctx.selectColumns,
                distinct = entryName == "selectDistinct",
                tableAlias = ctx.tableAlias,
                conditions = ctx.conditions,
                orderBy = ctx.orderBy,
                limit = ctx.limit?.let { LimitClause(offset = ctx.offset ?: it.offset, count = it.count) },
            )
            "deleteFrom" -> DeleteStatement(table, ctx.conditions)
            "update" -> UpdateStatement(table, ctx.sets, ctx.conditions)
            else -> return null
        }
        // 普查批：update 无 where → 全表更新警示（此前只报 deleteFrom，不对称）
        val warning = when {
            entryName == "deleteFrom" && ctx.conditions.isEmpty() -> "⚠ 无 WHERE 条件，将删除全表数据！"
            entryName == "update" && ctx.conditions.isEmpty() -> "⚠ 无 WHERE 条件，将更新全表数据！"
            else -> null
        }
        val generated = SqlGenerator.generateWithParams(statement)
        return MdsPreview(
            methodName = entryName,
            statement = statement,
            sql = generated.sql + notes.joinToString("") { "\n$it" },
            paramSources = generated.unresolvedParams,
            paramHints = generated.paramHints,
            tableName = tableName,
            columns = ctx.selectColumns.map { it.columnName },
            warning = warning,
        )
    }

    // ------------------------------------------------------------------
    // 段解析
    // ------------------------------------------------------------------

    /** from(table[, alias]) → 表名 + 别名。 */
    private fun parseFrom(segment: PsiMethodCallExpression, ctx: MdsCtx) {
        val args = segment.argumentList.expressions
        ctx.tableName = args.getOrNull(0)?.let { resolveSqlTableName(it) } ?: return
        val alias = (args.getOrNull(1) as? PsiLiteralExpression)?.value as? String
        if (!alias.isNullOrBlank()) ctx.tableAlias = alias
    }

    /** where(col, isEqualTo(1).and(col2, isIn(1, 2))) → 条件列表（or 顺序合并为 OR 组）。 */
    private fun parseWhereCall(segment: PsiMethodCallExpression, ctx: MdsCtx): List<Condition> {
        val args = segment.argumentList.expressions
        if (args.size < 2) return emptyList()
        val firstColumn = resolveColumnName(args[0], ctx) ?: return emptyList()
        val predicateChain = ArgResolver.collectCallChain(args[1] as? PsiMethodCallExpression ?: return emptyList())
        val result = mutableListOf<Condition>()
        for (predCall in predicateChain) {
            val name = predCall.methodExpression.referenceName ?: continue
            when {
                // 链头谓词：作用于 where 首参列
                name in PREDICATES && result.isEmpty() -> {
                    buildPredicate(firstColumn, predCall, ctx)?.let { result.add(it) }
                }
                // and(col, pred) / or(col, pred)：换列 + 连接符
                name in CONNECTORS -> {
                    val cArgs = predCall.argumentList.expressions
                    if (cArgs.size < 2) continue
                    val col = resolveColumnName(cArgs[0], ctx) ?: continue
                    val nested = cArgs[1] as? PsiMethodCallExpression ?: continue
                    if (nested.methodExpression.referenceName !in PREDICATES) continue
                    val condition = buildPredicate(col, nested, ctx) ?: continue
                    if (name == "or" && result.isNotEmpty()) {
                        val prev = result.removeAt(result.size - 1)
                        result.add(Condition.Group(Connector.OR, listOf(prev, condition)))
                    } else {
                        result.add(condition)
                    }
                }
            }
        }
        return result
    }

    /** 谓词调用 → Condition（谓词名单见类 KDoc；值实参静态求值）。 */
    private fun buildPredicate(column: String, call: PsiMethodCallExpression, ctx: MdsCtx): Condition? {
        val name = call.methodExpression.referenceName ?: return null
        val columnRef = ctx.aliasedColumn(column)
        val args = call.argumentList.expressions
        return when {
            name in COMPARE_PREDICATES -> args.firstOrNull()?.let {
                Condition.Simple(columnRef, COMPARE_PREDICATES[name]!!, ExpressionEvaluator.evaluate(it))
            }
            name in IN_PREDICATES -> {
                val values = args.map { ExpressionEvaluator.evaluate(it) }
                if (values.isEmpty()) null else Condition.In(columnRef, negated = name == "notIn", values = values)
            }
            name in UNARY_PREDICATES -> Condition.Unary(columnRef, UNARY_PREDICATES[name]!!)
            name in LIKE_PREDICATES -> args.firstOrNull()?.let {
                Condition.Like(
                    columnRef,
                    negated = false,
                    pattern = ExpressionEvaluator.evaluate(it),
                    position = LikePosition.NONE, // MDS isLike 不自动加 %——模式原样
                )
            }
            name in BETWEEN_PREDICATES -> {
                val lo = args.getOrNull(0)?.let { ExpressionEvaluator.evaluate(it) } ?: return null
                val hi = args.getOrNull(1)?.let { ExpressionEvaluator.evaluate(it) } ?: return null
                Condition.Between(columnRef, negated = name == "isNotBetween", lo = lo, hi = hi)
            }
            else -> null
        }
    }

    /** orderBy(col.descending(), col2) → ORDER BY（descending DESC / ascending/缺省 ASC）。 */
    private fun parseOrderBy(segment: PsiMethodCallExpression, ctx: MdsCtx): List<OrderByItem> =
        segment.argumentList.expressions.mapNotNull { arg ->
            when (arg) {
                is PsiMethodCallExpression -> {
                    val asc = when (arg.methodExpression.referenceName) {
                        "descending" -> false
                        "ascending" -> true
                        else -> return@mapNotNull null
                    }
                    val qualifier = arg.methodExpression.qualifierExpression
                    val column = qualifier?.let { resolveColumnName(it, ctx) } ?: return@mapNotNull null
                    OrderByItem(ctx.aliasedColumn(column), asc)
                }
                else -> resolveColumnName(arg, ctx)?.let { OrderByItem(ctx.aliasedColumn(it), asc = true) }
            }
        }

    /** update DSL：set(col) → 挂起列（equalTo(v) 完成）。 */
    private fun parseSet(segment: PsiMethodCallExpression, ctx: MdsCtx) {
        ctx.pendingSetColumn = segment.argumentList.expressions.firstOrNull()?.let { resolveColumnName(it, ctx) }
    }

    private fun completeSet(segment: PsiMethodCallExpression, ctx: MdsCtx) {
        val column = ctx.pendingSetColumn ?: return
        val value = segment.argumentList.expressions.firstOrNull()?.let { ExpressionEvaluator.evaluate(it) } ?: return
        ctx.sets.add(SetClause(column = ctx.aliasedColumn(column), value = value))
        ctx.pendingSetColumn = null
    }

    // ------------------------------------------------------------------
    // SqlColumn / SqlTable 常量解析（声明处初始化，无生成代码）
    // ------------------------------------------------------------------

    /**
     * 实参 → 列名：
     *  1. SqlColumn 字段引用（`UserDynamicSqlSupport.userId` / static import 的 `userId`）→
     *     字段初始化 `column("user_id", ...)` / `user.userId`（转发引用）递归解析；
     *  2. 解析失败 → 引用名 snake 兜底（`userId` → user_id），不出错误列名。
     */
    private fun resolveColumnName(expr: PsiExpression, ctx: MdsCtx): String? {
        val fieldRef = expr as? PsiReferenceExpression ?: return null
        (fieldRef.resolve() as? PsiField)?.let { field ->
            resolveColumnField(field, 0)?.let { return it }
        }
        return fieldRef.referenceName?.let { NamingStrategy.toSnakeCase(it) }
    }

    /** SqlColumn 字段 → 列名（转发引用递归，depth 守卫防循环）。 */
    private fun resolveColumnField(field: PsiField, depth: Int): String? {
        if (depth > 4) return null
        val initializer = field.initializer ?: return null
        // 形态 1：`column("user_id", JDBCType.X)` / `SqlTable.of(...)` 调用 → 首个字符串字面量
        (initializer as? PsiMethodCallExpression)?.let { call ->
            if (call.methodExpression.referenceName in COLUMN_INITIALIZER_METHODS) {
                return firstStringLiteral(call.argumentList.expressions.firstOrNull())
            }
        }
        // 形态 2：`user.userId` 转发引用 → 递归目标字段；
        // 引用不可解析（qualifier 静态类型未声明该字段等 stub 场景）→ 按引用名兜底（user.id → id）
        (initializer as? PsiReferenceExpression)?.let { ref ->
            (ref.resolve() as? PsiField)?.let { target -> return resolveColumnField(target, depth + 1) }
            ref.referenceName?.let { return NamingStrategy.toSnakeCase(it) }
        }
        return null
    }

    /** 实参 → SqlTable 表名（字段/变量引用 → `SqlTable.of("t_user")` / `new UserTable()` super 串）。 */
    private fun resolveSqlTableName(expr: PsiExpression): String? {
        // 直接形态：SqlTable.of("t_user")
        (expr as? PsiMethodCallExpression)?.let { call ->
            if (call.methodExpression.referenceName == "of") {
                return firstStringLiteral(call.argumentList.expressions.firstOrNull())
            }
        }
        // 直接形态：new SqlTable("t_user") / new UserTable()（子类 super 串）
        (expr as? PsiNewExpression)?.let { newExpr ->
            return sqlTableNameOf(newExpr)
        }
        // 引用形态：字段/变量持有 SqlTable 实例（`UserDynamicSqlSupport.user`）
        val ref = expr as? PsiReferenceExpression ?: return null
        return when (val resolved = ref.resolve()) {
            is PsiField -> sqlTableNameOfInitializer(resolved.initializer)
            is PsiVariable -> sqlTableNameOfInitializer(resolved.initializer)
            else -> null
        }
    }

    private fun sqlTableNameOfInitializer(initializer: PsiElement?): String? = when (initializer) {
        is PsiNewExpression -> sqlTableNameOf(initializer)
        is PsiMethodCallExpression ->
            if (initializer.methodExpression.referenceName == "of") {
                firstStringLiteral(initializer.argumentList.expressions.firstOrNull())
            } else {
                null
            }
        else -> null
    }

    /** new 形态表名：构造串字面量优先；SqlTable 子类构造器 `super("t_user")` 串兜底。 */
    private fun sqlTableNameOf(newExpr: PsiNewExpression): String? {
        firstStringLiteral(newExpr.argumentList?.expressions?.firstOrNull())?.let { return it }
        val clazz = newExpr.classReference?.resolve() as? PsiClass ?: return null
        var current: PsiClass? = clazz
        var depth = 0
        while (current != null && depth++ < 4) {
            for (ctor in current.constructors) {
                val body = ctor.body ?: continue
                // 形态 1：显式 super(...) 调用（若 PSI 建模为 PsiMethodCallExpression）
                val superCall = PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
                    .firstOrNull { it.methodExpression.referenceName == "super" }
                superCall?.let {
                    firstStringLiteral(it.argumentList.expressions.firstOrNull())?.let { name -> return name }
                }
                // 形态 2 兜底：构造器体内首个字符串字面量（super(...) 若非 PsiMethodCallExpression 形态，
                // 构造器仅含 super("t_user") 一句时字面量即表名）
                PsiTreeUtil.collectElementsOfType(body, PsiLiteralExpression::class.java)
                    .firstNotNullOfOrNull { it.value as? String }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            current = current.superClass
        }
        return null
    }

    /** 表达式内首个字符串字面量（拼接式递归）。 */
    private fun firstStringLiteral(expr: PsiExpression?): String? {
        if (expr == null) return null
        (expr as? PsiLiteralExpression)?.let { literal -> return literal.value as? String }
        for (child in expr.children) {
            if (child is PsiExpression) {
                firstStringLiteral(child)?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 链回溯（链上任意位置触发）
    // ------------------------------------------------------------------

    private fun findEntryCall(from: PsiMethodCallExpression, maxDepth: Int): PsiMethodCallExpression? {
        var current: PsiMethodCallExpression = from
        var depth = 0
        while (depth++ < maxDepth) {
            if (isMdsCall(current)) return current
            current = (current.methodExpression.qualifierExpression as? PsiMethodCallExpression) ?: return null
        }
        return null
    }

    /**
     * entry → 链尾完整段列表（头=entry，尾=build/render 等终端）。
     * 回溯处理调用层 + **引用层**（entry.parent 是外层调用的 methodExpression）两层延续——
     * 漏掉引用层则 segments 恒为 [entry]，from/where/orderBy/limit 全丢。
     */
    private fun collectSegments(entry: PsiMethodCallExpression): List<PsiMethodCallExpression> {
        var outermost: PsiMethodCallExpression = entry
        var cursor: PsiElement? = entry.parent
        while (cursor != null) {
            when {
                cursor is PsiMethodCallExpression && cursor.methodExpression.qualifierExpression === outermost -> {
                    outermost = cursor
                    cursor = cursor.parent
                }
                cursor is PsiReferenceExpression && cursor.qualifierExpression === outermost -> {
                    val next = cursor.parent as? PsiMethodCallExpression ?: break
                    outermost = next
                    cursor = next.parent
                }
                else -> break
            }
        }
        return ArgResolver.collectCallChain(outermost)
    }

    /** limit/offset 实参求值。 */
    private fun limitArg(segment: PsiMethodCallExpression): SqlValue? =
        segment.argumentList.expressions.firstOrNull()?.let { ExpressionEvaluator.evaluate(it) }

    /** 转换上下文（每次 convert 独立实例）。 */
    private class MdsCtx {
        var tableName: String? = null
        var tableAlias: String? = null
        val selectColumns = mutableListOf<ColumnRef>()
        val conditions = mutableListOf<Condition>()
        val orderBy = mutableListOf<OrderByItem>()
        val sets = mutableListOf<SetClause>()
        var limit: LimitClause? = null
        var offset: SqlValue? = null
        var pendingSetColumn: String? = null

        fun addConditions(conditions: List<Condition>) {
            this.conditions.addAll(conditions)
        }

        fun addOrderBy(items: List<OrderByItem>) {
            orderBy.addAll(items)
        }

        /** 别名存在时列引用补别名（对齐 MDS 渲染 `u.user_id`）。 */
        fun aliasedColumn(column: String): ColumnRef =
            tableAlias?.let { ColumnRef(columnName = column, tableAlias = it) } ?: ColumnRef(columnName = column)
    }
}
