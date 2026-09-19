package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger

/**
 * T-McpToolset MCP 工具调用审计日志（计划 §三-5）。
 *
 * 每次调用记 idea.log（INFO）：时间、工具、入参摘要（截断）、结果状态、耗时——
 * 安全排障与「审计日志可查」验收项的载体。入参摘要不含凭据与参数值：整体经 [clip] 截断到
 * [SUMMARY_LIMIT] 字符（含 SQL 首段，见 [executionArgsSummary] 的脱敏口径）；错误路径的
 * 组合串（摘要 + 错误文本）走 [errorArgsSummary] 的两段分预算，同样收口到 [SUMMARY_LIMIT]。
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

/**
 * 错误路径的审计入参槽位（T-McpAuditTrail T-MAT-04）：`<入参摘要> error=<错误文本>` 两段拼接，
 * 整体仍受 [McpAuditLog.SUMMARY_LIMIT] 约束。生产消费点在 `McpToolsetSupport.AuditTicket.expectedError`。
 *
 * **为什么不是整串 [McpAuditLog.clip]**（T-MAT-04 回退修复，独立复核轮实测）：整串截断让入参摘要
 * 先占满预算——`mpsv_explain` 的 SQL 段 >80 字符时错误文本开始被截、≥113 字符时**整段消失**，
 * 而长 SQL 恰是最需要看错误原因的场景（改造前该槽位只放错误文本，错误文本独占 120 字符）。
 * 故改**两段分预算**：错误文本优先占预算，入参摘要让位但保留 [ERROR_PATH_ARGS_FLOOR] 保底
 * （数据源标识与字段格式不消失），错误文本短时余量全部还给摘要，两段各带省略号且总长仍 ≤ 120。
 *
 * 不取「error 段前置」的理由：前置会让长错误文本把入参摘要整段挤掉，审计行退化成只有 `error=`，
 * T-MAT-04 补入参的意义在长 SQL 场景同样落空；分预算下两段都不会归零。
 *
 * 顶层函数（非 [McpAuditLog] 成员）：与 [executionArgsSummary] 同款先例，裸 JUnit 可直测。
 */
internal fun errorArgsSummary(argsSummary: String, errorText: String): String {
    val separator = " error="
    val available = McpAuditLog.SUMMARY_LIMIT - separator.length
    val argsFloor = minOf(argsSummary.length, ERROR_PATH_ARGS_FLOOR)
    val argsBudget: Int
    val errorBudget: Int
    if (errorText.length >= available - argsFloor) {
        // 错误文本已吃满「除摘要保底外的全部预算」：摘要只留保底，错误文本按剩余预算截断
        argsBudget = argsFloor
        errorBudget = available - argsBudget
    } else {
        // 错误文本放得下：余量全部还给摘要（长 SQL 的摘要此时仍能露出 SQL 首段）
        errorBudget = errorText.length
        argsBudget = minOf(argsSummary.length, available - errorBudget)
    }
    return clipSegment(argsSummary, argsBudget) + separator + clipSegment(errorText, errorBudget)
}

/** 错误路径入参摘要的保底长度：保住 `dataSource=` 头，审计行的字段格式不因错误文本过长而退化。 */
private const val ERROR_PATH_ARGS_FLOOR = 16

/** 段内截断：省略号计入 [limit]（段长含省略号不超 limit，两段相加即不超 [McpAuditLog.SUMMARY_LIMIT]）。 */
private fun clipSegment(text: String, limit: Int): String = when {
    text.length <= limit -> text
    limit <= 1 -> "…".take(limit.coerceAtLeast(0))
    else -> text.take(limit - 1) + "…"
}
