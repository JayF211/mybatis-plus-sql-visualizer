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
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement

/**
 * SQL 生成结果：SQL 文本 + 未解析参数来源（F-02 参数对照表基础）。
 *
 * @param sql              生成的 SQL（未解析参数渲染为 `?`）
 * @param unresolvedParams 按出现顺序的未解析参数来源（变量名/表达式文本）
 * @param paramHints       与 [unresolvedParams] 并行的回填提示（M13）：引号与 LIKE 百分号，
 *                         供面板按用户输入值回填渲染；无上下文信息时为空
 */
data class GeneratedSql(
    val sql: String,
    val unresolvedParams: List<String>,
    val paramHints: List<ParamHint> = emptyList(),
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
     * @param statement          语句模型（SELECT / UPDATE / DELETE）
     * @param compact            true=单行模式，false=多行缩进模式
     * @param dialect            数据库方言（影响分页子句，默认 MySQL）
     * @param enabledConditionIds 动态条件启用集合（M12）；null（默认）→ 按 toggle.defaultEnabled
     * @return SQL 文本
     */
    fun generate(
        statement: SqlStatement,
        compact: Boolean,
        dialect: Dialect = MySqlDialect,
        enabledConditionIds: Set<String>? = null,
    ): String = generateInternal(statement, compact, null, null, dialect, enabledConditionIds)

    /**
     * 生成 SQL 并收集未解析参数来源与回填提示（F-02 参数替换与对照表，M13 hints）。
     *
     * @param statement          语句模型
     * @param compact            true=单行模式，false=多行缩进模式
     * @param dialect            数据库方言（影响分页子句）
     * @param enabledConditionIds 动态条件启用集合（M12）：仅渲染 id 在该集合内的动态条件；
     *                             null（默认）→ 按各条件 toggle.defaultEnabled 渲染（与 M11 基线一致）
     * @return SQL、参数来源列表与并行的 [ParamHint] 回填提示
     */
    fun generateWithParams(
        statement: SqlStatement,
        compact: Boolean = false,
        dialect: Dialect = MySqlDialect,
        enabledConditionIds: Set<String>? = null,
    ): GeneratedSql {
        val params = mutableListOf<String>()
        val hints = mutableListOf<ParamHint>()
        val sql = generateInternal(statement, compact, params, hints, dialect, enabledConditionIds)
        return GeneratedSql(sql, params, hints)
    }

    /**
     * 渲染单个条件为文本（占位符渲染为 `?`，不收集参数）。
     * 供动态条件复选框标签 / 条件视图复用（M12）。
     */
    internal fun renderConditionText(condition: Condition): String = renderCondition(condition)

    // ------------------------------------------------------------------
    // 内部渲染
    // ------------------------------------------------------------------

    private fun generateInternal(
        statement: SqlStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        dialect: Dialect,
        enabledConditionIds: Set<String>?,
    ): String = when (statement) {
        is SelectStatement -> generateSelect(statement, compact, paramSink, hintSink, dialect, enabledConditionIds)
        is UpdateStatement -> generateUpdate(statement, compact, paramSink, hintSink, enabledConditionIds)
        is DeleteStatement -> generateDelete(statement, compact, paramSink, hintSink, enabledConditionIds)
    }

    /** SELECT 渲染（含 MPJ 主表别名与联表子句）。 */
    private fun generateSelect(
        statement: SelectStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        dialect: Dialect,
        enabledConditionIds: Set<String>?,
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
            sb.append(nl).append(renderJoin(join, compact, paramSink, hintSink, enabledConditionIds))
        }

        // WHERE 条件
        val whereSql = renderConditionList(
            statement.conditions, Connector.AND, indent = !compact, paramSink, enabledConditionIds, hintSink, statement.table,
        )
        if (whereSql.isNotEmpty()) {
            sb.append(nl).append("WHERE ").append(whereSql)
        }

        // GROUP BY
        if (statement.groupBy.isNotEmpty()) {
            sb.append(nl).append("GROUP BY ")
                .append(statement.groupBy.joinToString(", ") { it.qualifiedName })
        }

        // HAVING
        statement.having?.let { h ->
            if (h.isConditionVisible(enabledConditionIds)) {
                val havingSql = renderCondition(h, paramSink, hintSink, statement.table, enabledConditionIds)
                if (havingSql.isNotEmpty()) sb.append(nl).append("HAVING ").append(havingSql)
            }
        }

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
        hintSink: MutableList<ParamHint>?,
        enabledConditionIds: Set<String>?,
    ): String {
        val nl = if (compact) " " else "\n"
        val sb = StringBuilder("UPDATE ").append(statement.table.tableName)

        if (statement.sets.isNotEmpty()) {
            sb.append(nl).append("SET ")
            sb.append(statement.sets.joinToString(", ") { set ->
                when {
                    set.isRaw -> set.rawSql.orEmpty()
                    set.column != null -> "${set.column.qualifiedName} = ${renderValue(
                        set.value ?: SqlValue.Placeholder("?"), paramSink, hintSink, quoteHint(statement.table, set.column),
                    )}"
                    else -> ""
                }
            })
        }

        appendWhere(sb, statement.conditions, nl, compact, paramSink, hintSink, enabledConditionIds, statement.table)
        return sb.toString()
    }

    /** DELETE 渲染：DELETE FROM 表 WHERE ... */
    private fun generateDelete(
        statement: DeleteStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        enabledConditionIds: Set<String>?,
    ): String {
        val nl = if (compact) " " else "\n"
        val sb = StringBuilder("DELETE FROM ").append(statement.table.tableName)
        appendWhere(sb, statement.conditions, nl, compact, paramSink, hintSink, enabledConditionIds, statement.table)
        return sb.toString()
    }

    /** 追加 WHERE 子句（无条件时不输出）。 */
    private fun appendWhere(
        sb: StringBuilder,
        conditions: List<Condition>,
        nl: String,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        enabledConditionIds: Set<String>?,
        table: TableInfo,
    ) {
        val whereSql = renderConditionList(conditions, Connector.AND, indent = !compact, paramSink, enabledConditionIds, hintSink, table)
        if (whereSql.isNotEmpty()) {
            sb.append(nl).append("WHERE ").append(whereSql)
        }
    }

    /** JOIN 子句渲染：`INNER JOIN 表 别名 ON 条件1 AND 条件2`。 */
    private fun renderJoin(
        join: JoinClause,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        enabledConditionIds: Set<String>?,
    ): String {
        val typeName = when (join.type) {
            cn.song.mybatisplus.visualizer.model.JoinType.INNER -> "INNER JOIN"
            cn.song.mybatisplus.visualizer.model.JoinType.LEFT -> "LEFT JOIN"
            cn.song.mybatisplus.visualizer.model.JoinType.RIGHT -> "RIGHT JOIN"
        }
        val onPart = if (join.onConditions.isEmpty()) {
            ""
        } else {
            " ON " + renderConditionList(join.onConditions, Connector.AND, indent = false, paramSink, enabledConditionIds, hintSink, null)
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
        enabledIds: Set<String>? = null,
        hintSink: MutableList<ParamHint>? = null,
        table: TableInfo? = null,
    ): String {
        // 动态条件按启用集合/defaultEnabled 过滤；组内全空的 Group 渲染为空字符串后剔除
        val rendered = conditions
            .filter { it.isConditionVisible(enabledIds) }
            .map { renderCondition(it, paramSink, hintSink, table, enabledIds) }
            .filter { it.isNotEmpty() }
        if (rendered.isEmpty()) return ""
        return if (indent) {
            rendered.mapIndexed { index, r -> if (index == 0) r else "    ${connector.name} $r" }.joinToString("\n")
        } else {
            rendered.joinToString(" ${connector.name} ")
        }
    }

    /**
     * 动态条件可见性（M12）：无 toggle 恒可见；有 toggle 时按启用集合（enabledIds 非 null）
     * 或 defaultEnabled（enabledIds 为 null，默认渲染与 M11 基线一致）判定。
     */
    private fun Condition.isConditionVisible(enabledIds: Set<String>?): Boolean {
        val t = toggle ?: return true
        return if (enabledIds != null) t.id in enabledIds else t.defaultEnabled
    }

    /**
     * 渲染单个条件节点。
     *
     * @param enabledIds 动态条件启用集合（递归传给 Group 子条件；默认 null 按 defaultEnabled）
     * @param hintSink   回填提示收集器（M13，可为 null）
     * @param table      主表信息（列类型查询用；join ON 条件等无法匹配时类型未知）
     */
    private fun renderCondition(
        condition: Condition,
        paramSink: MutableList<String>? = null,
        hintSink: MutableList<ParamHint>? = null,
        table: TableInfo? = null,
        enabledIds: Set<String>? = null,
    ): String = when (condition) {
        is Condition.Simple ->
            "${condition.column.qualifiedName} ${condition.op} ${renderValue(condition.value, paramSink, hintSink, quoteHint(table, condition.column))}"

        is Condition.Unary ->
            "${condition.column.qualifiedName} ${condition.op}"

        is Condition.In -> {
            val op = if (condition.negated) "NOT IN" else "IN"
            val values = condition.values.joinToString(", ") { renderValue(it, paramSink, hintSink, quoteHint(table, condition.column)) }
            "${condition.column.qualifiedName} $op ($values)"
        }

        is Condition.Between -> {
            val op = if (condition.negated) "NOT BETWEEN" else "BETWEEN"
            "${condition.column.qualifiedName} $op ${renderValue(condition.lo, paramSink, hintSink, quoteHint(table, condition.column))} AND ${renderValue(condition.hi, paramSink, hintSink, quoteHint(table, condition.column))}"
        }

        is Condition.Like -> {
            val op = if (condition.negated) "NOT LIKE" else "LIKE"
            "${condition.column.qualifiedName} $op ${renderLike(condition.pattern, condition.position, paramSink, hintSink)}"
        }

        is Condition.Group -> {
            val childrenSql = renderConditionList(condition.children, condition.connector, indent = false, paramSink, enabledIds, hintSink, table)
            if (childrenSql.isEmpty()) "" else "($childrenSql)"
        }

        is Condition.ColumnComparison ->
            "${condition.left.qualifiedName} = ${condition.right.qualifiedName}"

        is Condition.Raw ->
            condition.sqlText
    }

    /**
     * 渲染参数值：字面量原样输出；占位符输出 `?` 并收集来源与回填提示（M13）。
     */
    private fun renderValue(value: SqlValue, paramSink: MutableList<String>?, hintSink: MutableList<ParamHint>?, quote: Boolean?): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> {
            paramSink?.add(value.source)
            hintSink?.add(ParamHint(quote = quote))
            "?"
        }
    }

    /**
     * 渲染 LIKE 模式：按 [LikePosition] 在字面量两侧拼接 `%`。
     * 占位符值输出 `?` 并收集回填提示（M13：按位置拼 `%`、恒引号——LIKE 模式恒为字符串）。
     */
    private fun renderLike(pattern: SqlValue, position: LikePosition, paramSink: MutableList<String>?, hintSink: MutableList<ParamHint>?): String = when (pattern) {
        is SqlValue.Placeholder -> {
            paramSink?.add(pattern.source)
            hintSink?.add(ParamHint(quote = true, likePosition = position))
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

    /**
     * 列值引号提示（M13）：按实体字段类型判定 —— String/CharSequence 列恒加引号；
     * 数值/时间等类型恒原样；列或类型未知返回 null（面板走原数字启发式）。
     */
    private fun quoteHint(table: TableInfo?, column: ColumnRef): Boolean? {
        val type = table?.columns?.firstOrNull { it.columnName == column.columnName }?.fieldType ?: return null
        return if ("String" in type || "CharSequence" in type) true else false
    }
}
