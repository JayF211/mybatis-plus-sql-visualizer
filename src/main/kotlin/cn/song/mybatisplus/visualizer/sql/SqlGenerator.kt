package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.UpdateStatement

/**
 * SQL 生成结果：SQL 文本 + 未解析参数来源（F-02 参数对照表基础）。
 *
 * @param sql              生成的 SQL（未解析参数渲染为 `?`）
 * @param unresolvedParams 按出现顺序的未解析参数来源（变量名/表达式文本）
 */
data class GeneratedSql(
    val sql: String,
    val unresolvedParams: List<String>,
)

/**
 * SQL 生成器（M1-04 / M2-03 / M4-04）：把 [SqlStatement] 模型渲染为 SQL 文本。
 *
 * 支持语句类型：SELECT（含 MPJ 联表）、UPDATE、DELETE。
 *
 * 输出约定（与 docs/项目介绍.md 联表示例风格对齐）：
 *  - 关键字大写
 *  - 默认多行模式：每个子句独占一行，WHERE 后续条件以 4 空格缩进 + `AND` 开头
 *  - 单行模式（compact）：子句间以空格连接，WHERE 条件内联（F-03 格式化视图切换）
 *  - 参数：psi 层已求值的字面量直接渲染；运行时值渲染为 `?` 并收集来源（F-02）
 *
 * 本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
object SqlGenerator {

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 生成 SELECT SQL（多行模式，兼容旧调用）。
     */
    fun generate(statement: SelectStatement): String = generate(statement as SqlStatement, compact = false)

    /**
     * 生成 SQL（多行模式，任意语句类型）。
     */
    fun generate(statement: SqlStatement): String = generate(statement, compact = false)

    /**
     * 生成 SQL（按语句类型分发）。
     *
     * @param statement 语句模型（SELECT / UPDATE / DELETE）
     * @param compact   true=单行模式，false=多行缩进模式
     * @param dialect   数据库方言（影响分页子句，默认 MySQL）
     * @return SQL 文本
     */
    fun generate(statement: SqlStatement, compact: Boolean, dialect: Dialect = MySqlDialect): String =
        generateInternal(statement, compact, null, dialect)

    /**
     * 生成 SQL 并收集未解析参数来源（F-02 参数替换与对照表）。
     *
     * @param statement 语句模型
     * @param dialect   数据库方言（影响分页子句）
     * @return SQL 与参数来源列表
     */
    fun generateWithParams(statement: SqlStatement, compact: Boolean = false, dialect: Dialect = MySqlDialect): GeneratedSql {
        val params = mutableListOf<String>()
        val sql = generateInternal(statement, compact, params, dialect)
        return GeneratedSql(sql, params)
    }

    // ------------------------------------------------------------------
    // 内部渲染
    // ------------------------------------------------------------------

    private fun generateInternal(
        statement: SqlStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
        dialect: Dialect,
    ): String = when (statement) {
        is SelectStatement -> generateSelect(statement, compact, paramSink, dialect)
        is UpdateStatement -> generateUpdate(statement, compact, paramSink)
        is DeleteStatement -> generateDelete(statement, compact, paramSink)
    }

    /** SELECT 渲染（含 MPJ 主表别名与联表子句）。 */
    private fun generateSelect(
        statement: SelectStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
        dialect: Dialect,
    ): String {
        val nl = if (compact) " " else "\n"

        // SELECT 列：未显式指定时展开为表全部列（联表场景同主表列）
        val selectColumns = if (statement.selectColumns.isEmpty()) {
            val prefix = statement.tableAlias?.let { "$it." } ?: ""
            statement.table.allColumnNames.map { ColumnRef(columnName = prefix + it) }
        } else {
            statement.selectColumns
        }
        val sb = StringBuilder("SELECT ")
        if (statement.distinct) sb.append("DISTINCT ")
        sb.append(selectColumns.joinToString(", ") { it.rendered })

        // FROM 主表（可带别名）
        sb.append(nl).append("FROM ").append(statement.table.tableName)
        statement.tableAlias?.let { sb.append(" ").append(it) }

        // JOIN 子句
        statement.joins.forEach { join ->
            sb.append(nl).append(renderJoin(join, compact, paramSink))
        }

        // WHERE 条件
        if (statement.conditions.isNotEmpty()) {
            sb.append(nl).append("WHERE ")
            sb.append(renderConditionList(statement.conditions, Connector.AND, indent = !compact, paramSink))
        }

        // GROUP BY
        if (statement.groupBy.isNotEmpty()) {
            sb.append(nl).append("GROUP BY ")
                .append(statement.groupBy.joinToString(", ") { it.qualifiedName })
        }

        // HAVING
        statement.having?.let { sb.append(nl).append("HAVING ").append(renderCondition(it, paramSink)) }

        // ORDER BY
        if (statement.orderBy.isNotEmpty()) {
            sb.append(nl).append("ORDER BY ")
                .append(statement.orderBy.joinToString(", ") {
                    "${it.column.qualifiedName} ${if (it.asc) "ASC" else "DESC"}"
                })
        }

        // LIMIT（分页子句按方言渲染）
        statement.limit?.let { sb.append(nl).append(dialect.renderLimit(it)) }

        return sb.toString()
    }

    /** UPDATE 渲染：UPDATE 表 SET 列 = 值, ... WHERE ... */
    private fun generateUpdate(
        statement: UpdateStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
    ): String {
        val nl = if (compact) " " else "\n"
        val sb = StringBuilder("UPDATE ").append(statement.table.tableName)

        if (statement.sets.isNotEmpty()) {
            sb.append(nl).append("SET ")
            sb.append(statement.sets.joinToString(", ") { set ->
                when {
                    set.isRaw -> set.rawSql.orEmpty()
                    set.column != null -> "${set.column.qualifiedName} = ${renderValue(set.value ?: SqlValue.Placeholder("?"), paramSink)}"
                    else -> ""
                }
            })
        }

        appendWhere(sb, statement.conditions, nl, compact, paramSink)
        return sb.toString()
    }

    /** DELETE 渲染：DELETE FROM 表 WHERE ... */
    private fun generateDelete(
        statement: DeleteStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
    ): String {
        val nl = if (compact) " " else "\n"
        val sb = StringBuilder("DELETE FROM ").append(statement.table.tableName)
        appendWhere(sb, statement.conditions, nl, compact, paramSink)
        return sb.toString()
    }

    /** 追加 WHERE 子句（无条件时不输出）。 */
    private fun appendWhere(
        sb: StringBuilder,
        conditions: List<Condition>,
        nl: String,
        compact: Boolean,
        paramSink: MutableList<String>?,
    ) {
        if (conditions.isNotEmpty()) {
            sb.append(nl).append("WHERE ")
            sb.append(renderConditionList(conditions, Connector.AND, indent = !compact, paramSink))
        }
    }

    /** JOIN 子句渲染：`INNER JOIN 表 别名 ON 条件1 AND 条件2`。 */
    private fun renderJoin(join: JoinClause, compact: Boolean, paramSink: MutableList<String>?): String {
        val typeName = when (join.type) {
            cn.song.mybatisplus.visualizer.model.JoinType.INNER -> "INNER JOIN"
            cn.song.mybatisplus.visualizer.model.JoinType.LEFT -> "LEFT JOIN"
            cn.song.mybatisplus.visualizer.model.JoinType.RIGHT -> "RIGHT JOIN"
        }
        val onPart = if (join.onConditions.isEmpty()) {
            ""
        } else {
            " ON " + renderConditionList(join.onConditions, Connector.AND, indent = false, paramSink)
        }
        return "$typeName ${join.tableName} ${join.alias}$onPart"
    }

    // ------------------------------------------------------------------
    // 条件渲染（SELECT/UPDATE/DELETE 共用）
    // ------------------------------------------------------------------

    /**
     * 渲染一组条件。
     *
     * @param indent   true=顶层多条件换行缩进（如 `    AND xxx`），false=单行连接
     * @param paramSink 参数收集器（可为 null）
     */
    private fun renderConditionList(
        conditions: List<Condition>,
        connector: Connector,
        indent: Boolean,
        paramSink: MutableList<String>?,
    ): String {
        if (indent) {
            return conditions.mapIndexed { index, c ->
                if (index == 0) renderCondition(c, paramSink) else "    ${connector.name} " + renderCondition(c, paramSink)
            }.joinToString("\n")
        }
        return conditions.joinToString(" ${connector.name} ") { renderCondition(it, paramSink) }
    }

    /**
     * 渲染单个条件节点。
     */
    private fun renderCondition(condition: Condition, paramSink: MutableList<String>? = null): String = when (condition) {
        is Condition.Simple ->
            "${condition.column.qualifiedName} ${condition.op} ${renderValue(condition.value, paramSink)}"

        is Condition.Unary ->
            "${condition.column.qualifiedName} ${condition.op}"

        is Condition.In -> {
            val op = if (condition.negated) "NOT IN" else "IN"
            val values = condition.values.joinToString(", ") { renderValue(it, paramSink) }
            "${condition.column.qualifiedName} $op ($values)"
        }

        is Condition.Between -> {
            val op = if (condition.negated) "NOT BETWEEN" else "BETWEEN"
            "${condition.column.qualifiedName} $op ${renderValue(condition.lo, paramSink)} AND ${renderValue(condition.hi, paramSink)}"
        }

        is Condition.Like -> {
            val op = if (condition.negated) "NOT LIKE" else "LIKE"
            "${condition.column.qualifiedName} $op ${renderLike(condition.pattern, condition.position, paramSink)}"
        }

        is Condition.Group ->
            "(${renderConditionList(condition.children, condition.connector, indent = false, paramSink)})"

        is Condition.ColumnComparison ->
            "${condition.left.qualifiedName} = ${condition.right.qualifiedName}"

        is Condition.Raw ->
            condition.sqlText
    }

    /**
     * 渲染参数值：字面量原样输出；占位符输出 `?` 并收集来源。
     */
    private fun renderValue(value: SqlValue, paramSink: MutableList<String>?): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> {
            paramSink?.add(value.source)
            "?"
        }
    }

    /**
     * 渲染 LIKE 模式：按 [LikePosition] 在字面量两侧拼接 `%`。
     * 占位符值暂输出 `?`（% 由运行时值决定，静态无法拼接）。
     */
    private fun renderLike(pattern: SqlValue, position: LikePosition, paramSink: MutableList<String>?): String = when (pattern) {
        is SqlValue.Placeholder -> {
            paramSink?.add(pattern.source)
            "?"
        }
        is SqlValue.Literal -> {
            val p = pattern.raw
            when (position) {
                LikePosition.ANYWHERE -> "%$p%"
                LikePosition.LEFT -> "%$p"
                LikePosition.RIGHT -> "$p%"
                LikePosition.NONE -> p
            }
        }
    }
}
