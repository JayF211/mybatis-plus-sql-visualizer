package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger

/**
 * T-McpToolset MCP 工具调用审计日志（计划 §三-5）。
 *
 * 每次调用记 idea.log（INFO）：时间、工具、入参摘要（截断）、结果状态、耗时——
 * 安全排障与「审计日志可查」验收项的载体。入参摘要不含凭据与参数值：整体经 [clip] 截断到
 * [SUMMARY_LIMIT] 字符（含 SQL 首段，见 [executionArgsSummary] 的脱敏口径）。
 */
object McpAuditLog {

    private val LOG = Logger.getInstance("cn.song.mybatisplus.visualizer.mcp.McpAudit")

    /** 入参摘要最大长度。 */
    internal const val SUMMARY_LIMIT = 120

    /** 记一次工具调用（INFO 级，idea.log 可 grep `[MCP-AUDIT]`）。 */
    fun call(tool: String, argsSummary: String, status: String, elapsedMs: Long) {
        LOG.info("[MCP-AUDIT] tool=$tool status=$status elapsedMs=$elapsedMs args=${clip(argsSummary)}")
    }

    /** 记录入参摘要文本（键=值对拼接，值截断）。 */
    fun summarize(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(" ") { (key, value) -> "$key=${value?.toString()?.replace('\n', ' ') ?: "null"}" }

    /**
     * 摘要截断（T-McpAuditTrail T-MAT-04 起 internal：审计行的「不超 120 字符」由本函数保证，
     * 需要可断言——计划 §五 走查 3 的截断口径）。
     */
    internal fun clip(text: String): String =
        if (text.length <= SUMMARY_LIMIT) text else text.take(SUMMARY_LIMIT) + "…"
}

/**
 * 执行类工具入参摘要（T-McpAuditTrail T-MAT-04）：工具名之外的 `dataSource` / `dialect` / SQL 首段。
 *
 * 顶层函数（非 [McpAuditLog] 成员）：裸 JUnit 可直测，加载门面类不必初始化 [McpAuditLog] 的 Logger
 * （McpDbExecutor.drainWarnings 同款先例）。**生产消费点在 ExplainTool.mpsv_explain**——改造前该工具
 * 的成功路径把字面量 `"ok"` 当入参传，审计行里没有任何真实入参（McpAuditLog.summarize 全仓零调用）。
 *
 * 脱敏口径（对齐 T-RA 系列「不落异常 message」）：只记 SQL 文本摘要、不记任何参数值——MCP 工具
 * 收到的已是成型 SQL，且 summarize 会把换行压平；整体长度由 [McpAuditLog.clip] 收口到 SUMMARY_LIMIT。
 * 空白值回落 `auto`（工具语义：省略=从数据源 URL 自动识别），避免 `dataSource=` 这类空值噪音。
 */
internal fun executionArgsSummary(dataSource: String, dialect: String, sql: String): String =
    McpAuditLog.summarize(
        "dataSource" to dataSource.trim().ifBlank { "auto" },
        "dialect" to dialect.trim().ifBlank { "auto" },
        "sql" to sql,
    )
