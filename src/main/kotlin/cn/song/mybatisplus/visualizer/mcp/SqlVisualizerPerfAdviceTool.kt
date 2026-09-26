package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints

/**
 * sqlv_perf_advice 工具（T-McpToolset2 / T-MTS2-01，R1 纯逻辑）：SQL 性能建议。
 *
 * 复用 F-2 的 SqlPerfAnalyzer（20 条规则引擎，规则内缓存）；警告经
 * [MpSqlVisualizerBundle.messageEn]（bundle base 即英文，零新键）渲染为 agent 面英文，
 * 未识别键诚实输出键名。纯静态分析，不触数据库。
 * 逻辑分层同 [SqlVisualizerCheckSqlTool]：[perfAdvice] 纯函数可测，挂起方法只做 audit 包装。
 * 输出预算（T-McpOutputBudget / O-20）见 [renderFindings] 与 McpToolsetSupport 类注释登记表。
 */
open class SqlVisualizerPerfAdviceTool : McpToolsetSupport("sqlv_perf_advice") {

    companion object {
        /**
         * 建议条数预算（20 条规则多命中理论上可超）：超限按 SEVERITY 等级序先裁低级
         * （ERROR > WARN > INFO，高危建议不因预算丢失），计数后缀恒可见。阈值宽于正常
         * 产出（日常 SQL 命中为个位数）——少于上限的输出逐字节与无预算版本一致（防回归硬门禁）。
         */
        const val MAX_FINDINGS = 30

        /**
         * 单条文案字符预算：对齐 McpSchemaFormatter.MAX_CELL_CHARS 的 200 字符口径（DBX
         * agent_tools.rs 短文案预算）。perf 文案本体是 bundle 英文短句（全库最长约 140 字符）
         * 加标识符插值，200 不伤正常输出；SqlVisualizerExplainTool 的 2000 是执行计划文本单元格口径，
         * 对建议文案无意义。
         */
        const val MAX_ADVICE_CHARS = 200
    }

    @McpTool(name = "sqlv_perf_advice", title = "Performance advice for a SQL statement")
    @McpDescription(
        "Static performance review of a single SQL statement (no execution, no database access): " +
            "SELECT * usage, missing-index hints, unindexed LIKE leading-wildcard, large IN lists, " +
            "NULL-comparison pitfalls and more (20 rules, severity ERROR/WARN/INFO, with line numbers). " +
            "Deterministic rule-engine analysis. Typical chain: sqlv_render_sql or sqlv_render_logs to " +
            "produce the SQL, this tool to review it, sqlv_explain to get the real plan. " +
            "Read-only: analysis only, never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_perf_advice(sql: String): String = audit("sqlv_perf_advice") { perfAdvice(sql) }

    /** 纯函数核心（零 IDE 依赖）：规则分析 + 预算渲染。 */
    internal fun perfAdvice(sql: String): String {
        if (sql.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "sql is empty."))
        }
        return renderFindings(SqlPerfAnalyzer.analyze(sql))
    }

    /**
     * 发现清单渲染（纯函数，合成 [PerfWarning] 清单可直测；输出预算落点）。
     *
     * 条数超 [MAX_FINDINGS] 才排序裁剪（SEVERITY 等级序，同级保持原始命中顺序）并追加
     * 计数后缀；单条文案超 [MAX_ADVICE_CHARS] 截断加省略号。少于上限时零排序零裁剪——
     * 输出逐字节与无预算版本一致（硬门禁：排序会打散同严重度内的原始顺序，只有真超限
     * 才值得付这个代价）。
     */
    internal fun renderFindings(warnings: List<PerfWarning>): String {
        if (warnings.isEmpty()) {
            return "No performance findings (20-rule static review passed)."
        }
        val shown = if (warnings.size > MAX_FINDINGS) {
            // PerfSeverity 声明序 ERROR=0/WARN=1/INFO=2——升序即高危在前（稳定排序，同级保持原始命中顺序）
            warnings.sortedBy { it.severity.ordinal }.take(MAX_FINDINGS)
        } else {
            warnings
        }
        return buildString {
            append(warnings.size).append(" finding(s):\n")
            shown.forEach { warning ->
                append("- [").append(warning.severity).append("] ").append(warning.ruleId)
                if (warning.line > 0) append(" (line ").append(warning.line).append(")")
                append(": ").append(
                    cell(
                        MpSqlVisualizerBundle.messageEn(warning.messageKey, *warning.messageArgs.toTypedArray()),
                    ),
                ).append('\n')
            }
            if (shown.size < warnings.size) {
                append("[TRUNCATED] showing ").append(shown.size).append(" of ").append(warnings.size)
                    .append(" findings (").append(warnings.size - shown.size)
                    .append(" more; ordered ERROR > WARN > INFO, lower severities trimmed first).")
            }
        }.trimEnd()
    }

    /** 单条文案截断（省略号形态与 McpSchemaFormatter/McpExplainFormatter 同款）。 */
    private fun cell(value: String): String =
        if (value.length > MAX_ADVICE_CHARS) value.take(MAX_ADVICE_CHARS) + "…" else value
}
