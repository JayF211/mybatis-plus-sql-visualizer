package cn.song.mybatisplus.visualizer.mcp

/**
 * T-McpToolset schema 上下文限幅与紧凑格式化（纯逻辑层，无 IDE 类型）。
 *
 * max_tables 口径参照 DBX（默认 8、上限 20，调研报告 §5.1）；输出面向 LLM 的紧凑
 * 表+列上下文（含注释），超限追加截断提示（DBX「截断时提示让用户 @table 指定」同语义）。
 */
object McpSchemaFormatter {

    /** 默认返回表数（DBX 口径）。 */
    const val DEFAULT_MAX_TABLES = 8

    /** 硬上限（DBX 口径）。 */
    const val HARD_MAX_TABLES = 20

    /** 单元格字符预算（DBX MCP agent_tools.rs 的 200 字符列预算口径）。 */
    const val MAX_CELL_CHARS = 200

    /** [tables] 入参允许的最多条目（超限直接拒——防一次调用拖全库遍历）。 */
    const val MAX_TABLE_NAMES = 50

    /** 格式化输入的一张表。 */
    data class TableEntry(
        val name: String,
        val comment: String?,
        val columns: List<ColumnLine>,
    )

    /** 格式化输入的一列（[notNull]=true 时追加 NOT NULL 标记）。 */
    data class ColumnLine(
        val name: String,
        val typeSpec: String?,
        val comment: String?,
        val notNull: Boolean? = null,
    )

    /** max_tables 限幅：null/非正 → 默认 8；超过硬上限 → 20；其余原样。 */
    fun clampMaxTables(requested: Int?): Int = when {
        requested == null || requested <= 0 -> DEFAULT_MAX_TABLES
        requested > HARD_MAX_TABLES -> HARD_MAX_TABLES
        else -> requested
    }

    /**
     * 表名清单校验与去重（保序，大小写不敏感去重）。
     * @return 通过的表名；[names] 为空或超 [MAX_TABLE_NAMES] 条目时返回 null（调用方报 INVALID_ARGUMENT）。
     */
    fun sanitizeTableNames(names: List<String>): List<String>? {
        if (names.isEmpty() || names.size > MAX_TABLE_NAMES) return null
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in names) {
            val name = raw.trim()
            if (name.isEmpty() || !seen.add(name.lowercase())) continue
            out.add(name)
        }
        return out.ifEmpty { null }
    }

    /**
     * 紧凑表列上下文（含注释）。
     *
     * 形态（逐表一段）：
     * ```
     * user (用户表)
     *   id bigint NOT NULL -- 主键
     *   name varchar(64) -- 用户名
     * ```
     * [requestedCount] 与实际返回数不一致（白名单超出 max_tables 截断）时在尾部追加截断提示。
     */
    fun format(tables: List<TableEntry>, maxTables: Int, requestedCount: Int): String {
        val out = StringBuilder()
        val shown = tables.take(maxTables)
        for (table in shown) {
            out.append(table.name)
            table.comment?.takeIf { it.isNotBlank() }?.let { out.append(" (").append(cell(it)).append(')') }
            out.append('\n')
            for (column in table.columns) {
                out.append("  ").append(column.name)
                column.typeSpec?.takeIf { it.isNotBlank() }?.let { out.append(' ').append(cell(it)) }
                if (column.notNull == true) out.append(" NOT NULL")
                column.comment?.takeIf { it.isNotBlank() }?.let { out.append(" -- ").append(cell(it)) }
                out.append('\n')
            }
        }
        if (requestedCount > shown.size) {
            out.append("\n[TRUNCATED] showing ${shown.size} of $requestedCount requested tables ")
                .append("(maxTables=$maxTables); re-run with a smaller whitelist or a higher maxTables (limit ")
                .append(HARD_MAX_TABLES)
                .append(").")
        }
        return out.toString().trimEnd()
    }

    private fun cell(value: String): String =
        if (value.length > MAX_CELL_CHARS) value.take(MAX_CELL_CHARS) + "…" else value
}
