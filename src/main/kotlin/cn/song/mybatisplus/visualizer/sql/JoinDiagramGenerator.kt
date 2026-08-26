package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.EntityNode
import cn.song.mybatisplus.visualizer.ui.diagram.RelationshipLine

/**
 * F-1/F-xx：JOIN 关系图结构化模型生成器。
 *
 * 将 [SelectStatement] 中的 JOIN 关系映射为 [DiagramModel]：
 *  - 主表 → 绿色实体（含列信息，PK 标 *）
 *  - JOIN 表 → 蓝色实体
 *  - JOIN 类型 → 关系线样式（LEFT/INNER/RIGHT/FULL）
 *  - ON 条件 → 关系线标签
 *  - 列数 > 50 时仅显示主键列（避免图过大）
 *
 * 无 JOIN 时返回空模型（调用方据此决定是否展示标签页）。
 */
object JoinDiagramGenerator {

    /** 列数上限：超过此值仅显示主键列。 */
    private const val MAX_COLUMNS = 50

    /**
     * 构建 JOIN 关系图的结构化模型。
     *
     * @param stmt SELECT 语句模型
     * @return [DiagramModel]；无 JOIN 时返回空模型
     */
    fun buildDiagramModel(stmt: SelectStatement): DiagramModel {
        if (stmt.joins.isEmpty()) return DiagramModel(emptyList(), emptyList())

        val mainAlias = sanitizeAlias(stmt.tableAlias ?: "t")
        val entities = mutableListOf<EntityNode>()
        val relationships = mutableListOf<RelationshipLine>()

        // 主表
        entities.add(
            EntityNode(
                alias = mainAlias,
                tableName = stmt.table.tableName,
                columns = pickColumns(stmt.table).map { col ->
                    ColumnEntry(
                        name = col.columnName,
                        type = col.fieldType,
                        isPk = col.isId,
                    )
                },
                isMain = true,
            ),
        )

        // JOIN 表 + 关系线
        val usedAliases = mutableSetOf(mainAlias)
        stmt.joins.forEachIndexed { index, join ->
            val joinAlias = resolveJoinAlias(join.alias, index)
            val uniqueAlias = uniquify(joinAlias, usedAliases)
            usedAliases.add(uniqueAlias)

            // Extract JOIN table columns from ON conditions
            val joinColumns = extractJoinColumns(join, uniqueAlias, stmt)

            entities.add(
                EntityNode(
                    alias = uniqueAlias,
                    tableName = join.tableName,
                    columns = joinColumns,
                    isMain = false,
                ),
            )

            relationships.add(
                RelationshipLine(
                    fromAlias = mainAlias,
                    toAlias = uniqueAlias,
                    joinType = join.type,
                    label = buildOnLabel(join),
                ),
            )
        }

        return DiagramModel(entities, relationships)
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
     * 解析 JOIN 别名为有效的标识符。
     * 空字符串时生成默认别名 `t{index+1}`。
     */
    private fun resolveJoinAlias(alias: String, index: Int): String =
        if (alias.isBlank()) "t${index + 1}" else sanitizeAlias(alias)

    /**
     * 将别名 sanitize 为合法标识符（仅保留字母/数字/下划线，数字开头则加前缀）。
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

    /**
     * 提取 JOIN 表的列信息，来源：
     * 1. selectColumns 中引用该 JOIN 表的列（含 selectAs 映射的列）
     * 2. ON 条件中引用该 JOIN 表的列
     * 3. FillClause 中该 JOIN 表的 selectColumns
     *
     * 类型解析优先级：ColumnRef.type (selectAs) > tableInfo 字段推断 > null
     */
    private fun extractJoinColumns(join: JoinClause, joinAlias: String, stmt: SelectStatement): List<ColumnEntry> {
        val columns = mutableListOf<ColumnEntry>()
        val seen = mutableSetOf<String>()

        // Helper: resolve Java type from tableInfo by column name
        fun typeFromTable(colName: String): String? {
            val fieldType = join.tableInfo?.columns?.firstOrNull { it.columnName == colName }?.fieldType
            return fieldType?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
        }

        // 1. From selectColumns (includes selectAs results, with type from ColumnRef.type)
        for (col in stmt.selectColumns) {
            if (col.tableAlias == joinAlias && !seen.contains(col.columnName)) {
                seen.add(col.columnName)
                val javaType = col.type?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
                    ?: typeFromTable(col.columnName)
                columns.add(ColumnEntry(name = col.columnName, type = javaType, isPk = false))
            }
        }

        // 2. From ON conditions (resolve type from tableInfo)
        for (cond in join.onConditions) {
            if (cond is Condition.ColumnComparison) {
                if (cond.right.tableAlias == joinAlias && !seen.contains(cond.right.columnName)) {
                    seen.add(cond.right.columnName)
                    columns.add(ColumnEntry(name = cond.right.columnName, type = typeFromTable(cond.right.columnName), isPk = false))
                }
                if (cond.left.tableAlias == joinAlias && !seen.contains(cond.left.columnName)) {
                    seen.add(cond.left.columnName)
                    columns.add(ColumnEntry(name = cond.left.columnName, type = typeFromTable(cond.left.columnName), isPk = false))
                }
            }
        }

        // 3. From FillClause selectColumns
        stmt.fillClauses?.forEach { fill ->
            if (fill.childAlias == joinAlias) {
                for (col in fill.selectColumns) {
                    if (!seen.contains(col.columnName)) {
                        seen.add(col.columnName)
                        columns.add(ColumnEntry(name = col.columnName, type = typeFromTable(col.columnName), isPk = false))
                    }
                }
            }
        }

        return columns
    }
}
