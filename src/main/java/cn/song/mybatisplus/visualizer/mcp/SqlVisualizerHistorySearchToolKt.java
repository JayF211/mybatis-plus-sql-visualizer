// T-SyncK2JMaster482 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerHistorySearchTool.kt 顶层函数段移植
package cn.song.mybatisplus.visualizer.mcp;

import kotlin.Pair;

/**
 * 审计入参摘要（T-MAT-04：新增有入参面的工具显式传真实摘要，不落 "ok"）的顶层函数承载
 * （Kotlin 文件门面类 {@code SqlVisualizerHistorySearchToolKt} 的 Java 等价，同
 * {@link McpToolsetSupportKt} 先例）。
 *
 * <p>顶层函数（{@code executionArgsSummary} 同款先例）：裸 JUnit 可直测，加载门面类不必初始化 Logger。
 * 只记过滤条件形态，不记任何 SQL 文本/参数值（脱敏口径对齐 T-RA 系列）。</p>
 */
public final class SqlVisualizerHistorySearchToolKt {

    private SqlVisualizerHistorySearchToolKt() {
    }

    /**
     * 审计入参摘要：只记过滤条件形态（keyword/regex/fromMs/toMs/sourceFile/allDates），
     * 不记任何 SQL 文本/参数值。
     */
    public static String historySearchArgsSummary(
        String keyword,
        String regex,
        Long fromMs,
        Long toMs,
        String sourceFile,
        boolean allDates
    ) {
        return McpAuditLog.summarize(
            new Pair<>("keyword", keyword.trim()),
            new Pair<>("regex", regex.trim()),
            new Pair<>("fromMs", fromMs),
            new Pair<>("toMs", toMs),
            new Pair<>("sourceFile", sourceFile.trim()),
            new Pair<>("allDates", allDates)
        );
    }
}
