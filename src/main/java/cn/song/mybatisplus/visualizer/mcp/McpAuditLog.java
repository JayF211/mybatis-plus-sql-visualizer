// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpAuditLog.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.diagnostic.Logger;
import kotlin.Pair;

/**
 * T-McpToolset MCP 工具调用审计日志（计划 §三-5）。
 *
 * 每次调用记 idea.log（INFO）：时间、工具、入参摘要（截断）、结果状态、耗时——
 * 安全排障与「审计日志可查」验收项的载体。入参摘要不含凭据与参数值：整体经 {@link #clip} 截断到
 * {@link #SUMMARY_LIMIT} 字符（含 SQL 首段，见 {@link McpAuditLogKt#executionArgsSummary} 的脱敏口径）；
 * 错误路径的组合串（摘要 + 错误文本）走 {@link McpAuditLogKt#errorArgsSummary} 的两段分预算，
 * 同样收口到 {@link #SUMMARY_LIMIT}。
 */
public final class McpAuditLog {

    private McpAuditLog() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpAuditLog INSTANCE = new McpAuditLog();

    private static final Logger LOG = Logger.getInstance("cn.song.mybatisplus.visualizer.mcp.McpAudit");

    /** 入参摘要最大长度。 */
    public static final int SUMMARY_LIMIT = 120;

    /** 记一次工具调用（INFO 级，idea.log 可 grep `[MCP-AUDIT]`）。 */
    public static void call(String tool, String argsSummary, String status, long elapsedMs) {
        LOG.info("[MCP-AUDIT] tool=" + tool + " status=" + status + " elapsedMs=" + elapsedMs
            + " args=" + clip(argsSummary));
    }

    /** 记录入参摘要文本（键=值对拼接，值截断）。 */
    @SafeVarargs
    public static String summarize(Pair<String, ?>... pairs) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            String key = pairs[i].getFirst();
            Object value = pairs[i].getSecond();
            out.append(key).append('=')
                .append(value == null ? "null" : value.toString().replace('\n', ' '));
        }
        return out.toString();
    }

    /**
     * 摘要截断（T-McpAuditTrail T-MAT-04 起 internal：审计行的「不超 120 字符」由本函数保证，
     * 需要可断言——计划 §五 走查 3 的截断口径）。
     */
    public static String clip(String text) {
        return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT) + "…";
    }
}
