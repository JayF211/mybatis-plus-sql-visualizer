// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpAuditLog.kt 顶层函数段移植
package cn.song.mybatisplus.visualizer.mcp;

import kotlin.Pair;

/**
 * 审计入参摘要的顶层函数承载（Kotlin 文件门面类 {@code McpAuditLogKt} 的 Java 等价，
 * 同 {@link McpDbExecutorKt} 先例）。
 *
 * 为什么与 {@link McpAuditLog} 分家：master 侧这两个函数是<b>顶层函数</b>（非 object 成员）——
 * 裸 JUnit 可直测，加载门面类不必初始化 {@link McpAuditLog} 的 Logger。
 */
public final class McpAuditLogKt {

    private McpAuditLogKt() {
    }

    /**
     * 执行类工具入参摘要（T-McpAuditTrail T-MAT-04）：工具名之外的 {@code dataSource} / {@code dialect} / SQL 首段。
     *
     * <p>顶层函数（非 {@link McpAuditLog} 成员）：裸 JUnit 可直测，加载门面类不必初始化
     * {@link McpAuditLog} 的 Logger（{@code McpDbExecutor.drainWarnings} 同款先例）。
     * <b>生产消费点在 SqlVisualizerExplainTool.sqlv_explain</b>——改造前该工具的成功路径把字面量 {@code "ok"}
     * 当入参传，审计行里没有任何真实入参（{@code McpAuditLog.summarize} 全仓零调用）。</p>
     *
     * <p>脱敏口径（对齐 T-RA 系列「不落异常 message」）：只记 SQL 文本摘要、不记任何参数值——MCP 工具
     * 收到的已是成型 SQL，且 summarize 会把换行压平；整体长度由 {@link McpAuditLog#clip} 收口到
     * {@link McpAuditLog#SUMMARY_LIMIT}。空白值回落 {@code auto}（工具语义：省略=从数据源 URL 自动识别），
     * 避免 {@code dataSource=} 这类空值噪音。</p>
     */
    public static String executionArgsSummary(String dataSource, String dialect, String sql) {
        return McpAuditLog.summarize(
            new Pair<>("dataSource", blankToAuto(dataSource)),
            new Pair<>("dialect", blankToAuto(dialect)),
            new Pair<>("sql", sql)
        );
    }

    /**
     * 错误路径的审计入参槽位（T-McpAuditTrail T-MAT-04）：{@code <入参摘要> error=<错误文本>} 两段拼接，
     * 整体仍受 {@link McpAuditLog#SUMMARY_LIMIT} 约束。生产消费点在
     * {@code McpToolsetSupport.AuditTicket.expectedError}。
     *
     * <p><b>为什么不是整串 {@link McpAuditLog#clip}</b>（T-MAT-04 回退修复，独立复核轮实测）：整串截断让
     * 入参摘要先占满预算——{@code sqlv_explain} 的 SQL 段 &gt;80 字符时错误文本开始被截、≥113 字符时
     * <b>整段消失</b>，而长 SQL 恰是最需要看错误原因的场景（改造前该槽位只放错误文本，错误文本独占
     * 120 字符）。故改<b>两段分预算</b>：错误文本优先占预算，入参摘要让位但保留
     * {@link #ERROR_PATH_ARGS_FLOOR} 保底（数据源标识与字段格式不消失），错误文本短时余量全部还给摘要，
     * 两段各带省略号且总长仍 ≤ 120。</p>
     *
     * <p>不取「error 段前置」的理由：前置会让长错误文本把入参摘要整段挤掉，审计行退化成只有 {@code error=}，
     * T-MAT-04 补入参的意义在长 SQL 场景同样落空；分预算下两段都不会归零。</p>
     *
     * <p>顶层函数（非 {@link McpAuditLog} 成员）：与 {@link #executionArgsSummary} 同款先例，裸 JUnit 可直测。</p>
     */
    public static String errorArgsSummary(String argsSummary, String errorText) {
        String separator = " error=";
        int available = McpAuditLog.SUMMARY_LIMIT - separator.length();
        int argsFloor = Math.min(argsSummary.length(), ERROR_PATH_ARGS_FLOOR);
        int argsBudget;
        int errorBudget;
        if (errorText.length() >= available - argsFloor) {
            // 错误文本已吃满「除摘要保底外的全部预算」：摘要只留保底，错误文本按剩余预算截断
            argsBudget = argsFloor;
            errorBudget = available - argsBudget;
        } else {
            // 错误文本放得下：余量全部还给摘要（长 SQL 的摘要此时仍能露出 SQL 首段）
            errorBudget = errorText.length();
            argsBudget = Math.min(argsSummary.length(), available - errorBudget);
        }
        return clipSegment(argsSummary, argsBudget) + separator + clipSegment(errorText, errorBudget);
    }

    /** 错误路径入参摘要的保底长度：保住 {@code dataSource=} 头，审计行的字段格式不因错误文本过长而退化。 */
    private static final int ERROR_PATH_ARGS_FLOOR = 16;

    /** 段内截断：省略号计入 {@code limit}（段长含省略号不超 limit，两段相加即不超 {@link McpAuditLog#SUMMARY_LIMIT}）。 */
    private static String clipSegment(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        if (limit <= 1) {
            // Kotlin "…".take(limit.coerceAtLeast(0))：limit ≤ 1 时取 0 或 1 个字符
            return "…".substring(0, Math.max(limit, 0));
        }
        return text.substring(0, limit - 1) + "…";
    }

    /** Kotlin {@code value.trim().ifBlank { "auto" }} 等价：去空白后为空则回落 {@code auto}。 */
    private static String blankToAuto(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "auto" : trimmed;
    }
}
