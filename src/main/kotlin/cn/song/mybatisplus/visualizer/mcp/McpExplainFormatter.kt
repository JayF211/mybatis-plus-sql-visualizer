package cn.song.mybatisplus.visualizer.mcp

/**
 * T-McpToolset EXPLAIN 计划文本格式化（纯逻辑层，无 IDE 类型）。
 *
 * 输入为已读取的行列数据（适配层把 DBN RemoteResultSet 读成纯数据后再进本层），
 * 行数/单元格字符预算参照 DBX MCP 的上下文防撑爆口径（调研报告 §8：MAX_ALLOWED_ROWS /
 * 单元格 200 字符上限 4000）。
 */
object McpExplainFormatter {

    /** 返回行数预算（EXPLAIN 计划行通常 <100；超预算按截断处理）。 */
    const val MAX_ROWS = 200

    /** 单元格字符预算。 */
    const val MAX_CELL_CHARS = 2000

    /** 已读取的查询结果（适配层采集；[truncated]=采集时已到行数预算上限）。 */
    data class RawResult(
        val headers: List<String>,
        val rows: List<List<String>>,
        val truncated: Boolean = false,
    )

    /**
     * 计划文本：列头 + 行 + 截断提示（截断标记来自 [McpExplainFormatter.RawResult.truncated]，
     * 行数已由适配层按 [MAX_ROWS] 采集裁剪）。
     */
    fun format(raw: RawResult): String {
        val out = StringBuilder()
        if (raw.headers.isNotEmpty()) {
            out.append(raw.headers.joinToString(" | ") { it.trim() }).append('\n')
        }
        for (row in raw.rows) {
            out.append(row.joinToString(" | ") { cell(it) }).append('\n')
        }
        if (raw.truncated) {
            out.append("\n[TRUNCATED] output limited to $MAX_ROWS rows; the actual plan may have more.")
        }
        return out.toString().trimEnd()
    }

    private fun cell(value: String): String {
        val flat = value.replace("\r\n", "\n").trim()
        return if (flat.length > MAX_CELL_CHARS) flat.take(MAX_CELL_CHARS) + "…" else flat
    }
}
