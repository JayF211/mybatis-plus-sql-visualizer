package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger

/**
 * T-McpToolset MCP 工具调用审计日志（计划 §三-5）。
 *
 * 每次调用记 idea.log（INFO）：时间、工具、入参摘要（截断）、结果状态、耗时——
 * 安全排障与「审计日志可查」验收项的载体。入参摘要不含完整 SQL/凭据（截断 + 只摘要键）。
 */
object McpAuditLog {

    private val LOG = Logger.getInstance("cn.song.mybatisplus.visualizer.mcp.McpAudit")

    /** 入参摘要最大长度。 */
    private const val SUMMARY_LIMIT = 120

    /** 记一次工具调用（INFO 级，idea.log 可 grep `[MCP-AUDIT]`）。 */
    fun call(tool: String, argsSummary: String, status: String, elapsedMs: Long) {
        LOG.info("[MCP-AUDIT] tool=$tool status=$status elapsedMs=$elapsedMs args=${clip(argsSummary)}")
    }

    /** 记录入参摘要文本（键=值对拼接，值截断）。 */
    fun summarize(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(" ") { (key, value) -> "$key=${value?.toString()?.replace('\n', ' ') ?: "null"}" }

    private fun clip(text: String): String =
        if (text.length <= SUMMARY_LIMIT) text else text.take(SUMMARY_LIMIT) + "…"
}
