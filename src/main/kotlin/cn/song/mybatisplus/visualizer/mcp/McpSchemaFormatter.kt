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

    // ------------------------------------------------------------------
    // P3 / T-McpArgLooseDecode：入参形态与取值校验
    //
    // 载体类型（McpStringArg/McpIntArg/McpArgKind）定义在 SqlVisualizerSchemaContextTool.kt：
    // 它们带 kotlinx.serialization 依赖，而本对象保持「零第三方类型」的纯逻辑层约定
    // （见 McpToolErrors 文件头），此处只做形态与取值判定，仍是纯函数、可直测。
    // ------------------------------------------------------------------

    /**
     * [tables] 入参 → 合规表名清单（保序、大小写不敏感去重）。
     *
     * **形态判定先于取值判定**：平台参数绑定层对「JSON 数组/对象 → String」不容错（见载体 KDoc 的
     * `Index -1 out of bounds` 根因），载体把形态带到这一层，非字符串形态与空清单/超限同归
     * [McpToolErrors.INVALID_ARGUMENT]——类型写错的 agent 拿到的是参数校验错误，不是内部异常。
     *
     * @return 通过的表名；形态不符或取值不符时 null（调用方用 [tableNamesError] 报错）。
     */
    fun parseTableNames(arg: McpStringArg): List<String>? {
        if (arg.kind != McpArgKind.STRING) return null
        val names = arg.text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return sanitizeTableNames(names)
    }

    /** [parseTableNames] 的配套错误文案（形态不符 / 取值不符分别措辞，均为 INVALID_ARGUMENT）。 */
    fun tableNamesError(arg: McpStringArg): String =
        if (arg.kind != McpArgKind.STRING) {
            typeMismatch("tables", "a comma-separated string of 1..$MAX_TABLE_NAMES table names", arg)
        } else {
            McpToolErrors.code(
                McpToolErrors.INVALID_ARGUMENT,
                "tables must contain 1..$MAX_TABLE_NAMES table names (comma-separated).",
            )
        }

    /**
     * [dataSource] 入参 → 数据源别名（空串是合法取值 = 未指定；形态不符 → null）。
     * 形态判定同样为 P3 口径：`dataSource: ["mp_demo@localhost"]` 曾与 `tables` 同栈崩（同一次实测）。
     */
    fun parseDataSource(arg: McpStringArg): String? =
        arg.text.takeIf { arg.kind == McpArgKind.STRING }

    /** [parseDataSource] 的配套错误文案。 */
    fun dataSourceError(arg: McpStringArg): String =
        typeMismatch("dataSource", "a data source display name string", arg)

    /**
     * [maxTables] 入参 → 请求上限（随后由 [clampMaxTables] 钳制）。
     *
     * 数值字符串（`"8"`）照旧接受：平台绑定对「字符串 → Int」本就有 kotlinx 宽松转换，改前可用，
     * 不因本次收口而拒（属取值合法、仅形态宽松）。形态不符或非整数文本 → null。
     */
    fun parseMaxTables(arg: McpIntArg): Int? =
        if (arg.kind == McpArgKind.NUMBER || arg.kind == McpArgKind.STRING) arg.text.trim().toIntOrNull() else null

    /** [parseMaxTables] 的配套错误文案（形态不符 / 非整数文本分别措辞）。 */
    fun maxTablesError(arg: McpIntArg): String =
        if (arg.kind == McpArgKind.NUMBER || arg.kind == McpArgKind.STRING) {
            McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "maxTables must be an integer (got '${arg.text}').")
        } else {
            typeMismatch("maxTables", "an integer", arg)
        }

    /** 形态不符的统一文案：点明期望形态与实际 JSON 形态（agent 据此自我纠正）。 */
    private fun typeMismatch(name: String, expected: String, arg: McpArg): String =
        McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "$name must be $expected (got JSON ${arg.kind.label}).")

    private fun cell(value: String): String =
        if (value.length > MAX_CELL_CHARS) value.take(MAX_CELL_CHARS) + "…" else value
}
