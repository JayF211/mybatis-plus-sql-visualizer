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
                        type = javaTypeToSqlType(col.fieldType),
                        isPk = col.isId,
                        comment = col.comment,
                    )
                },
                isMain = true,
                tableComment = stmt.table.comment,
            ),
        )

        // JOIN 表 + 关系线
        val usedAliases = mutableSetOf(mainAlias)
        val aliasMap = mutableMapOf<String, String>()
        stmt.tableAlias?.takeIf { it.isNotBlank() }?.let { aliasMap[it] = mainAlias }
        stmt.joins.forEachIndexed { index, join ->
            val joinAlias = resolveJoinAlias(join, index)
            val uniqueAlias = uniquify(joinAlias, usedAliases)
            usedAliases.add(uniqueAlias)
            if (join.alias.isNotBlank()) aliasMap[join.alias] = uniqueAlias

            val joinColumns = extractJoinColumns(join, uniqueAlias, stmt)
            val displayTableName = resolveDisplayTableName(join)

            entities.add(
                EntityNode(
                    alias = uniqueAlias,
                    tableName = displayTableName,
                    columns = joinColumns,
                    isMain = false,
                    joinType = join.type,
                    tableComment = join.tableInfo?.comment,
                ),
            )

            relationships.add(
                RelationshipLine(
                    fromAlias = mainAlias,
                    toAlias = uniqueAlias,
                    joinType = join.type,
                    label = buildOnLabel(join, aliasMap),
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
     * 解析 JOIN 别名为有效的 SQL 标识符。
     *
     * 优先使用 SQL 别名（纯小写+下划线格式）；
     * 实体类名（含大写驼峰）fallback 为默认别名 `t{index+1}`。
     */
    private fun resolveJoinAlias(join: JoinClause, index: Int): String {
        val raw = join.alias
        if (raw.isBlank()) return "t${index + 1}"
        if (looksLikeSqlAlias(raw)) return sanitizeAlias(raw)
        return "t${index + 1}"
    }

    /** 判断别名是否像 SQL 别名（纯小写+下划线+数字，非驼峰）。 */
    private fun looksLikeSqlAlias(raw: String): Boolean =
        raw.isNotBlank() && raw.all { it.isLowerCase() || it.isDigit() || it == '_' }

    /**
     * 解析 JOIN 表的显示名称。
     * 子查询（tableName 含 SELECT 或以 '(' 开头）显示为 "Subquery"。
     */
    private fun resolveDisplayTableName(join: JoinClause): String {
        val name = join.tableName
        if (name.isBlank()) return "Subquery"
        if (name.startsWith("(", ignoreCase = true)) return "Subquery"
        if (name.contains("SELECT", ignoreCase = true)) return "Subquery"
        return name
    }

    /**
     * Java 字段类型 → SQL 列类型映射。
     * 用于图中列类型显示（用户期望看到 SQL 类型而非 Java 类型）。
     */
    private fun javaTypeToSqlType(javaType: String?): String? {
        if (javaType == null) return null
        val simple = javaType.substringAfterLast('.')
        return when (simple) {
            "Integer", "int" -> "INT"
            "Long", "long" -> "BIGINT"
            "Short", "short" -> "SMALLINT"
            "Byte", "byte" -> "TINYINT"
            "Boolean", "boolean" -> "BOOLEAN"
            "Float", "float" -> "FLOAT"
            "Double", "double" -> "DOUBLE"
            "BigDecimal" -> "DECIMAL"
            "String" -> "VARCHAR"
            "LocalDate" -> "DATE"
            "LocalDateTime", "Date", "Timestamp" -> "DATETIME"
            "LocalTime", "Time" -> "TIME"
            "byte[]" -> "BLOB"
            "UUID" -> "CHAR(36)"
            else -> simple
        }
    }

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
    private fun buildOnLabel(join: JoinClause, aliasMap: Map<String, String> = emptyMap()): String {
        if (!join.rawSql.isNullOrBlank()) return "(rawSql)"
        if (join.onConditions.isEmpty()) return ""
        return join.onConditions.map { conditionToLabel(it, aliasMap) }.joinToString(" AND ")
    }

    /** 将条件转换为可读标签，并将解析期实体别名归一到图中显示别名。 */
    private fun conditionToLabel(condition: Condition, aliasMap: Map<String, String>): String = when (condition) {
        is Condition.ColumnComparison ->
            "${qualifiedName(condition.left, aliasMap)} = ${qualifiedName(condition.right, aliasMap)}"
        is Condition.Simple ->
            "${qualifiedName(condition.column, aliasMap)} ${condition.op} ?"
        is Condition.Unary ->
            "${qualifiedName(condition.column, aliasMap)} ${condition.op}"
        is Condition.In ->
            "${qualifiedName(condition.column, aliasMap)} IN (...)"
        is Condition.Between ->
            "${qualifiedName(condition.column, aliasMap)} BETWEEN ? AND ?"
        is Condition.Like ->
            "${qualifiedName(condition.column, aliasMap)} LIKE ?"
        is Condition.Group ->
            condition.children.joinToString(
                if (condition.connector == cn.song.mybatisplus.visualizer.model.Connector.OR) " OR " else " AND ",
            ) { conditionToLabel(it, aliasMap) }
        is Condition.Raw -> condition.sqlText
    }

    private fun qualifiedName(ref: cn.song.mybatisplus.visualizer.model.ColumnRef, aliasMap: Map<String, String>): String =
        ref.tableAlias?.let { "${aliasMap[it] ?: it}.${ref.columnName}" } ?: ref.columnName

    /**
     * 提取 JOIN 表的列信息，来源：
     * 1. selectColumns 中引用该 JOIN 表的列（含 selectAs 映射的列）
     * 2. ON 条件中引用该 JOIN 表的列
     * 3. FillClause 中该 JOIN 表的 selectColumns
     *
     * 类型解析优先级：ColumnRef.type (selectAs) > tableInfo 字段推断 > null
     *
     * T-Embed-07（真机缺陷修复）：列引用的 tableAlias 是转换器原始别名（MPJ 类名小驼峰如
     * `stationInfoEntity`），而 joinAlias 是 sanitize/去重后的显示别名（如 `t1`）——
     * 二者取并集匹配，否则 JOIN 表列全部落空（ON 标签因走 aliasMap 映射而正常，更具迷惑性）。
     */
    private fun extractJoinColumns(join: JoinClause, joinAlias: String, stmt: SelectStatement): List<ColumnEntry> {
        val columns = mutableListOf<ColumnEntry>()
        val seen = mutableSetOf<String>()
        val acceptedAliases = setOf(joinAlias, join.alias).filter { it.isNotBlank() }.toSet()

        // Helper: resolve SQL type from tableInfo by column name
        fun sqlTypeFromTable(colName: String): String? {
            val fieldType = join.tableInfo?.columns?.firstOrNull { it.columnName == colName }?.fieldType
            return javaTypeToSqlType(fieldType)
        }

        // Helper: resolve comment from tableInfo by column name
        fun commentFromTable(colName: String): String? {
            return join.tableInfo?.columns?.firstOrNull { it.columnName == colName }?.comment
        }

        // Helper: 列引用是否指向本 JOIN 表（原始别名或显示别名任一命中）
        fun belongsToJoin(ref: cn.song.mybatisplus.visualizer.model.ColumnRef): Boolean =
            ref.tableAlias != null && ref.tableAlias in acceptedAliases

        // 1. From selectColumns (includes selectAs results, with type from ColumnRef.type)
        for (col in stmt.selectColumns) {
            if (belongsToJoin(col) && !seen.contains(col.columnName)) {
                seen.add(col.columnName)
                val sqlType = javaTypeToSqlType(col.type) ?: sqlTypeFromTable(col.columnName)
                columns.add(ColumnEntry(name = col.columnName, type = sqlType, isPk = false, comment = commentFromTable(col.columnName)))
            }
        }

        // 2. From ON conditions (resolve type from tableInfo)
        for (cond in join.onConditions) {
            if (cond is Condition.ColumnComparison) {
                if (belongsToJoin(cond.right) && !seen.contains(cond.right.columnName)) {
                    seen.add(cond.right.columnName)
                    columns.add(ColumnEntry(name = cond.right.columnName, type = sqlTypeFromTable(cond.right.columnName), isPk = false, comment = commentFromTable(cond.right.columnName)))
                }
                if (belongsToJoin(cond.left) && !seen.contains(cond.left.columnName)) {
                    seen.add(cond.left.columnName)
                    columns.add(ColumnEntry(name = cond.left.columnName, type = sqlTypeFromTable(cond.left.columnName), isPk = false, comment = commentFromTable(cond.left.columnName)))
                }
            }
        }

        // 3. From FillClause selectColumns
        stmt.fillClauses?.forEach { fill ->
            if (fill.childAlias == joinAlias || fill.childAlias == join.alias) {
                for (col in fill.selectColumns) {
                    if (!seen.contains(col.columnName)) {
                        seen.add(col.columnName)
                        columns.add(ColumnEntry(name = col.columnName, type = sqlTypeFromTable(col.columnName), isPk = false, comment = commentFromTable(col.columnName)))
                    }
                }
            }
        }

        return columns
    }
}
