package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement

/**
 * F-1：JOIN 关系图 PlantUML 文本生成器。
 *
 * 将 [SelectStatement] 中的 JOIN 关系映射为 PlantUML entity 图：
 *  - 主表 → 绿色实体（含列信息，PK 标 *）
 *  - JOIN 表 → 蓝色实体
 *  - JOIN 类型 → 关系线样式（LEFT: `||--o{` / INNER: `||--||` / RIGHT: `}o--||` / FULL: `}o--o{`）
 *  - ON 条件 → 关系线标签
 *  - 列数 > 50 时仅显示主键列（避免图过大）
 *
 * 无 JOIN 时返回空字符串（调用方据此决定是否展示标签页）。
 */
object JoinDiagramGenerator {

    /** 列数上限：超过此值仅显示主键列。 */
    private const val MAX_COLUMNS = 50

    /**
     * 生成 PlantUML entity 图文本。
     *
     * @param stmt SELECT 语句模型
     * @return PlantUML 源文本；无 JOIN 时返回空字符串
     */
    fun generatePlantUml(stmt: SelectStatement): String {
        if (stmt.joins.isEmpty()) return ""

        val mainAlias = sanitizeAlias(stmt.tableAlias ?: "t")
        val sb = StringBuilder()
        sb.append("@startuml\n")
        sb.append("!theme plain\n")
        sb.append("hide circle\n")
        sb.append("skinparam linetype ortho\n\n")

        // 主表（绿色）
        appendEntity(sb, mainAlias, stmt.table.tableName, stmt.table, isMain = true)

        // JOIN 表 + 关系线
        val usedAliases = mutableSetOf(mainAlias)
        stmt.joins.forEachIndexed { index, join ->
            val joinAlias = resolveJoinAlias(join.alias, index)
            // 防别名冲突：已存在则追加序号
            val uniqueAlias = uniquify(joinAlias, usedAliases)
            usedAliases.add(uniqueAlias)

            appendEntity(sb, uniqueAlias, join.tableName, null, isMain = false)

            val rel = relationshipFor(join.type)
            val label = buildOnLabel(join)
            sb.append("$mainAlias $rel $uniqueAlias : \"$label\"\n\n")
        }

        sb.append("@enduml\n")
        return sb.toString()
    }

    /**
     * 追加一个 entity 块（含列信息）。
     *
     * @param alias     实体别名（已 sanitize，用于 as 子句）
     * @param tableName 表名（用于显示名）
     * @param tableInfo 表信息（含列）；JOIN 表无列信息时传 null
     * @param isMain    是否主表（控制颜色）
     */
    private fun appendEntity(
        sb: StringBuilder,
        alias: String,
        tableName: String,
        tableInfo: cn.song.mybatisplus.visualizer.model.TableInfo?,
        isMain: Boolean,
    ) {
        val displayName = if (alias != tableName) "$tableName ($alias)" else tableName
        val color = if (isMain) "#lightgreen" else "#lightblue"
        sb.append("entity \"$displayName\" as $alias $color {\n")
        if (tableInfo != null) {
            val columns = pickColumns(tableInfo)
            for (col in columns) {
                val marker = if (col.isId) "* " else "  "
                val typeStr = col.fieldType ?: ""
                sb.append("  $marker${col.columnName} : $typeStr\n")
            }
        }
        sb.append("}\n\n")
    }

    /**
     * 选取要展示的列：总数 <= [MAX_COLUMNS] 时全显，否则仅主键列。
     */
    private fun pickColumns(tableInfo: cn.song.mybatisplus.visualizer.model.TableInfo) =
        if (tableInfo.columns.size <= MAX_COLUMNS) {
            tableInfo.columns
        } else {
            tableInfo.columns.filter { it.isId }
        }

    /**
     * 解析 JOIN 别名为有效的 PlantUML 标识符。
     * 空字符串时生成默认别名 `t{index+1}`。
     */
    private fun resolveJoinAlias(alias: String, index: Int): String =
        if (alias.isBlank()) "t${index + 1}" else sanitizeAlias(alias)

    /**
     * 将别名 sanitize 为合法 PlantUML 标识符（仅保留字母/数字/下划线，数字开头则加前缀）。
     */
    private fun sanitizeAlias(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return if (cleaned.isNotEmpty() && cleaned[0].isDigit()) "_$cleaned" else cleaned
    }

    /**
     * 确保别名唯一：已被占用则追加递增序号。
     */
    private fun uniquify(alias: String, used: Set<String>): String {
        if (alias !in used) return alias
        var i = 2
        while ("${alias}_$i" in used) i++
        return "${alias}_$i"
    }

    /**
     * JOIN 类型 → PlantUML 关系线样式。
     */
    private fun relationshipFor(type: JoinType): String = when (type) {
        JoinType.LEFT -> "||--o{"
        JoinType.INNER -> "||--||"
        JoinType.RIGHT -> "}o--||"
        JoinType.FULL -> "}o--o{"
    }

    /**
     * 构建关系线标签（ON 条件）。
     * rawSql 非空时显示 "(rawSql)" 提示；否则拼接 ON 条件（AND 连接）。
     */
    private fun buildOnLabel(join: JoinClause): String {
        if (!join.rawSql.isNullOrBlank()) return "(rawSql)"
        if (join.onConditions.isEmpty()) return ""
        return join.onConditions.map { conditionToLabel(it) }.joinToString(" AND ")
    }

    /**
     * 将条件转换为可读的标签文本。
     * [Condition.ColumnComparison] → `left = right`；其余类型取简化表示。
     */
    private fun conditionToLabel(condition: Condition): String = when (condition) {
        is Condition.ColumnComparison ->
            "${condition.left.qualifiedName} = ${condition.right.qualifiedName}"
        is Condition.Simple ->
            "${condition.column.qualifiedName} ${condition.op} ?"
        is Condition.Unary ->
            "${condition.column.qualifiedName} ${condition.op}"
        is Condition.In ->
            "${condition.column.qualifiedName} IN (...)"
        is Condition.Between ->
            "${condition.column.qualifiedName} BETWEEN ? AND ?"
        is Condition.Like ->
            "${condition.column.qualifiedName} LIKE ?"
        is Condition.Group ->
            condition.children.joinToString(
                if (condition.connector == cn.song.mybatisplus.visualizer.model.Connector.OR) " OR " else " AND ",
            ) { conditionToLabel(it) }
        is Condition.Raw ->
            condition.sqlText
    }
}
