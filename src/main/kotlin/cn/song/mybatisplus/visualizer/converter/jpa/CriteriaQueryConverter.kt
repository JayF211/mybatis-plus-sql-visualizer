package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel
import cn.song.mybatisplus.visualizer.psi.jpa.GroupConnector
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel
import cn.song.mybatisplus.visualizer.util.NamingStrategy

/**
 * CriteriaQuery → SqlStatement 转换器（F-JPA-02）。
 *
 * 将 [CriteriaQueryModel] 中间表示转换为 [SelectStatement]，供
 * [cn.song.mybatisplus.visualizer.sql.SqlGenerator] 渲染为 SQL。
 *
 * CriteriaBuilder 方法 → Condition 映射：
 *  - cb.equal(a, b)              → Condition.Simple(col, "=", value)
 *  - cb.notEqual(a, b)           → Condition.Simple(col, "!=", value)
 *  - cb.greaterThan(a, b)        → Condition.Simple(col, ">", value)
 *  - cb.greaterThanOrEqualTo(a,b)→ Condition.Simple(col, ">=", value)
 *  - cb.lessThan(a, b)           → Condition.Simple(col, "<", value)
 *  - cb.lessThanOrEqualTo(a, b)  → Condition.Simple(col, "<=", value)
 *  - cb.between(a, lo, hi)       → Condition.Between(col, false, lo, hi)
 *  - cb.isNull(a)                → Condition.Unary(col, "IS NULL")
 *  - cb.isNotNull(a)             → Condition.Unary(col, "IS NOT NULL")
 *  - cb.like(a, pattern)         → Condition.Like(col, false, pattern, Anywhere)
 *  - cb.notLike(a, pattern)      → Condition.Like(col, true, pattern, Anywhere)
 *  - cb.in(a).value(b)           → Condition.In(col, false, listOf(b))
 *  - cb.and(p1, p2)              → Condition.Group(AND, listOf(p1, p2))
 *  - cb.or(p1, p2)               → Condition.Group(OR, listOf(p1, p2))
 *  - cb.not(p)                   → Condition.Group(AND, listOf(p), negated=true)
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
object CriteriaQueryConverter {

    /**
     * 转换 CriteriaQueryModel 为 SqlStatement。
     *
     * @param model CriteriaQueryLocator 输出的中间模型
     * @param overrideTable 可选的表信息覆盖（测试/外部传入预解析表信息用）；
     *                      null 时按 model.entityClass 或 model.entityClassName 解析
     * @return SelectStatement；无法解析实体时返回全 Raw 降级的 SELECT
     */
    fun convert(model: CriteriaQueryModel, overrideTable: TableInfo? = null): SqlStatement {
        val tableInfo = overrideTable ?: resolveTableInfo(model)
        val conditions = convertPredicates(model.predicates, tableInfo)
        val orderBy = convertOrderBy(model.orderBys, tableInfo)
        val joins = convertJoins(model.joins)
        val selectColumns = convertSelectColumns(model.selectColumns, tableInfo)

        return SelectStatement(
            table = tableInfo,
            selectColumns = selectColumns,
            conditions = conditions,
            orderBy = orderBy,
            joins = joins,
        )
    }

    // ------------------------------------------------------------------
    // 表信息解析
    // ------------------------------------------------------------------

    /**
     * 解析实体表信息。
     *
     * 优先通过 entityClass PsiClass 走 JpaEntityResolver 解析（@Table/@Column 注解）；
     * 不可解析时按命名策略由类名推断表名。
     */
    private fun resolveTableInfo(model: CriteriaQueryModel): TableInfo {
        val entityClass = model.entityClass
        if (entityClass != null) {
            return JpaEntityResolver().resolve(entityClass)
        }
        val className = model.entityClassName ?: model.resultClassName ?: "UnknownEntity"
        return TableInfo(
            entityClassName = className,
            tableName = JpaEntityResolver.inferDecoratedTableName(className),
            columns = emptyList(),
        )
    }

    // ------------------------------------------------------------------
    // SELECT 列转换
    // ------------------------------------------------------------------

    /**
     * 转换 SELECT 列为 ColumnRef 列表。
     *
     * 规则：
     *  - 空列表 → 空（converter 层全列语义，生成器展开为 SELECT *）
     *  - 有列 → 按字段名查找实体列映射，找不到时按命名策略兜底
     */
    private fun convertSelectColumns(
        columns: List<cn.song.mybatisplus.visualizer.psi.jpa.SelectColumn>,
        tableInfo: TableInfo,
    ): List<ColumnRef> {
        if (columns.isEmpty()) return emptyList()
        return columns.map { sel ->
            val columnName = resolveColumnName(sel.fieldName, tableInfo)
            ColumnRef(columnName = columnName, alias = sel.alias)
        }
    }

    // ------------------------------------------------------------------
    // WHERE 条件转换
    // ------------------------------------------------------------------

    /**
     * 转换谓词列表为 Condition 列表。
     */
    private fun convertPredicates(
        predicates: List<PredicateModel>,
        tableInfo: TableInfo,
    ): List<Condition> {
        return predicates.map { convertPredicate(it, tableInfo) }
    }

    /**
     * 转换单个 PredicateModel 为 Condition。
     */
    private fun convertPredicate(predicate: PredicateModel, tableInfo: TableInfo): Condition = when (predicate) {
        is PredicateModel.Simple -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            Condition.Simple(column, predicate.op, sqlValue(predicate.valueExpression))
        }

        is PredicateModel.IsNull -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            val op = if (predicate.negated) "IS NOT NULL" else "IS NULL"
            Condition.Unary(column, op)
        }

        is PredicateModel.Between -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            Condition.Between(
                column = column,
                negated = false,
                lo = sqlValue(predicate.loExpression),
                hi = sqlValue(predicate.hiExpression),
            )
        }

        is PredicateModel.Like -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            Condition.Like(
                column = column,
                negated = predicate.negated,
                pattern = sqlValue(predicate.patternExpression),
                position = LikePosition.ANYWHERE,
            )
        }

        is PredicateModel.In -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            Condition.In(
                column = column,
                negated = predicate.negated,
                values = predicate.valueExpressions.map { sqlValue(it) },
            )
        }

        is PredicateModel.Group -> {
            val connector = when (predicate.connector) {
                GroupConnector.AND -> Connector.AND
                GroupConnector.OR -> Connector.OR
            }
            Condition.Group(
                connector = connector,
                children = predicate.children.map { convertPredicate(it, tableInfo) },
                negated = predicate.negated,
            )
        }

        is PredicateModel.Raw -> {
            Condition.Raw(predicate.expressionText)
        }
    }

    // ------------------------------------------------------------------
    // ORDER BY 转换
    // ------------------------------------------------------------------

    /**
     * 转换 OrderByModel 列表为 OrderByItem 列表。
     */
    private fun convertOrderBy(
        orderBys: List<cn.song.mybatisplus.visualizer.psi.jpa.OrderByModel>,
        tableInfo: TableInfo,
    ): List<OrderByItem> {
        return orderBys.map { ob ->
            val column = resolveColumnRef(ob.fieldName, tableInfo)
            OrderByItem(column = column, asc = ob.asc)
        }
    }

    // ------------------------------------------------------------------
    // JOIN 转换
    // ------------------------------------------------------------------

    /**
     * 转换 JoinModel 列表为 JoinClause 列表。
     *
     * 关联实体走 JpaEntityResolver 解析；不可解析时按类名推断表名。
     */
    private fun convertJoins(joins: List<cn.song.mybatisplus.visualizer.psi.jpa.JoinModel>): List<JoinClause> {
        return joins.map { join ->
            val entityClassName = join.entityClassName ?: join.fieldName
            val tableName = JpaEntityResolver.inferDecoratedTableName(entityClassName)
            val alias = join.alias ?: entityClassName.replaceFirstChar { it.lowercaseChar() }

            JoinClause(
                type = join.joinType,
                tableName = tableName,
                alias = alias,
                onConditions = emptyList(), // CriteriaQuery join ON 条件需运行时关联，当前留空
            )
        }
    }

    // ------------------------------------------------------------------
    // 列名解析
    // ------------------------------------------------------------------

    /**
     * 解析实体字段名 → ColumnRef。
     *
     * 优先从 TableInfo 的列映射查找（按 fieldName）；找不到时按驼峰转下划线兜底。
     */
    private fun resolveColumnRef(fieldName: String, tableInfo: TableInfo): ColumnRef {
        val columnName = resolveColumnName(fieldName, tableInfo)
        return ColumnRef(columnName = columnName)
    }

    /**
     * 解析实体字段名 → 数据库列名。
     */
    private fun resolveColumnName(fieldName: String, tableInfo: TableInfo): String {
        val matched = tableInfo.columnByField(fieldName)
        return matched?.columnName ?: NamingStrategy.toSnakeCase(fieldName)
    }

    // ------------------------------------------------------------------
    // 值表达式求值
    // ------------------------------------------------------------------

    /**
     * 把 PSI 表达式文本转为 SqlValue。
     *
     * 简单启发式：
     *  - 字符串字面量（单/双引号包裹）→ Literal（去引号，渲染时重新包裹）
     *  - 数字字面量 → Literal
     *  - null → Literal("NULL")
     *  - true/false → Literal
     *  - 其他 → Placeholder（运行时值）
     */
    private fun sqlValue(expression: String): SqlValue {
        val trimmed = expression.trim()

        // null 字面量
        if (trimmed == "null") return SqlValue.Literal("NULL")

        // 布尔
        if (trimmed == "true" || trimmed == "false") return SqlValue.Literal(trimmed)

        // 字符串字面量（单引号或双引号）
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))
        ) {
            val unquoted = trimmed.substring(1, trimmed.length - 1)
            return SqlValue.Literal(
                raw = "'" + unquoted.replace("'", "''") + "'",
                unquoted = unquoted,
            )
        }

        // 数字
        if (trimmed.matches(Regex("^-?\\d+(\\.\\d+)?[LlFfDd]?$"))) {
            // 去除类型后缀 L/F/D
            val numeric = trimmed.trimEnd('L', 'l', 'F', 'f', 'D', 'd')
            return SqlValue.Literal(numeric)
        }

        // 运行时值 → 占位符
        return SqlValue.Placeholder(trimmed)
    }
}
