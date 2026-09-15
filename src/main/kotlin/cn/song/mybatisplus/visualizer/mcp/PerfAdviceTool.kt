package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints

/**
 * mpsv_perf_advice 工具（T-McpToolset2 / T-MTS2-01，R1 纯逻辑）：SQL 性能建议。
 *
 * 复用 F-2 的 SqlPerfAnalyzer（20 条规则引擎，规则内缓存）；警告经
 * [MpSqlVisualizerBundle.messageEn]（bundle base 即英文，零新键）渲染为 agent 面英文，
 * 未识别键诚实输出键名。纯静态分析，不触数据库。
 * 逻辑分层同 [CheckSqlTool]：[perfAdvice] 纯函数可测，挂起方法只做 audit 包装。
 */
class PerfAdviceTool : McpToolsetSupport("mpsv_perf_advice") {

    @McpTool(name = "mpsv_perf_advice", title = "Performance advice for a SQL statement")
    @McpDescription(
        "Static performance review of a single SQL statement (no execution, no database access): " +
            "SELECT * usage, missing-index hints, unindexed LIKE leading-wildcard, large IN lists, " +
            "NULL-comparison pitfalls and more (20 rules, severity ERROR/WARN/INFO, with line numbers). " +
            "Deterministic rule-engine analysis. Typical chain: mpsv_render_sql or mpsv_render_logs to " +
            "produce the SQL, this tool to review it, mpsv_explain to get the real plan. " +
            "Read-only: analysis only, never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun mpsv_perf_advice(sql: String): String = audit("mpsv_perf_advice") { perfAdvice(sql) }

    /** 纯函数核心（零 IDE 依赖）。 */
    internal fun perfAdvice(sql: String): String {
        if (sql.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "sql is empty."))
        }
        val warnings = SqlPerfAnalyzer.analyze(sql)
        if (warnings.isEmpty()) {
            return "No performance findings (20-rule static review passed)."
        }
        return buildString {
            append(warnings.size).append(" finding(s):\n")
            warnings.forEach { warning ->
                append("- [").append(warning.severity).append("] ").append(warning.ruleId)
                if (warning.line > 0) append(" (line ").append(warning.line).append(")")
                append(": ").append(
                    MpSqlVisualizerBundle.messageEn(warning.messageKey, *warning.messageArgs.toTypedArray()),
                ).append('\n')
            }
        }.trimEnd()
    }
}
