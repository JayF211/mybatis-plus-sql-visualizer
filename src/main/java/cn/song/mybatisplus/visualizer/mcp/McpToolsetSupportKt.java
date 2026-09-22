// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetSupport.kt 顶层函数段移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.log.SqlLogManagerLookupKt;
import cn.song.mybatisplus.visualizer.log.SqlSource;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * T-McpAuditTrail（T-MAT-01/02）：MCP 执行链落历史的顶层函数承载（Kotlin 文件门面类
 * {@code McpToolsetSupportKt} 的 Java 等价，同 {@link McpDbExecutorKt} 先例）。
 *
 * <p>为什么是<b>顶层函数</b>而非基类方法：本文件门面类的顶层函数可被裸 JUnit / 平台测试直调，
 * 加载时不必解析抽象基类的 mcpserver 接口（可选插件依赖）——{@code McpDbExecutor.drainWarnings}
 * 同款先例。落库链（{@link SqlLogManager} 取用 + 条目构造）整体留在这里，基类只多一个回调接线点；
 * 未来新增执行类工具照抄 {@link #recordMcpExecution} 一处即可获得历史与审计。</p>
 */
public final class McpToolsetSupportKt {

    private McpToolsetSupportKt() {
    }

    private static final Logger LOG =
        Logger.getInstance("cn.song.mybatisplus.visualizer.mcp.McpAuditTrail");

    /**
     * MCP 执行条目构造（T-McpAuditTrail T-MAT-01/02）。
     *
     * <p><b>文本恒为调用方传入的原始 selectSql，绝不落 {@code EXPLAIN <select>} 包装句</b>：包装句首 token 为
     * {@code EXPLAIN} → {@code LogStatementClassifier} 判 {@code StatementType.OTHER} →
     * {@code SqlListTypeColor.colorOf} 对 OTHER 返回 null → 列表无类型染色（计划 §一 硬约束 2，
     * 第八轮报告 #38 宣称的「风险类别复用分类器」当场失效）。「该条来自 EXPLAIN 包装执行」由
     * {@link SqlSource#MCP} 承担独立标记（该来源仅 explain 通道产出）。</p>
     *
     * <p>无参数（params 空、无占位符）：MCP 工具收的是已成型 SELECT，参数已在 agent 侧落地——
     * 故本条目依赖 {@code SqlLogAutoSaver} 的 {@code source == MCP} 条件才写出 pd 行（T-MAT-03 配套必改）。</p>
     *
     * @param dataSourceName 数据源显示名 → 复用既有目标库角标 {@code formatDatabaseFragment}
     * @param elapsedMs 执行耗时（历史列表按慢查询阈值着色）
     * @param errorText 失败时的「异常类名: 首行消息」（成功为 null，列表红字片段展示）
     */
    public static LogSqlEntry mcpHistoryEntry(String selectSql, String dataSourceName, Long elapsedMs,
                                              String errorText, long timestamp) {
        String sql = selectSql.trim();
        return new LogSqlEntry(
            sql,                     // rawSql
            sql,                     // restoredSql
            List.of(),               // params
            timestamp,               // timestamp
            null,                    // logTimestamp
            computeSummary(sql),     // summary
            SqlSource.MCP,           // source
            elapsedMs,               // executionTimeMs
            null,                    // sourceFile
            0,                       // sourceLine
            List.of(),               // sourceCandidates
            List.of(),               // candidateClasses
            null,                    // stackDigest
            0L,                      // sequenceNumber
            null,                    // mapperStatement
            errorText,               // errorText
            null,                    // requestTag
            null,                    // updateCount
            dataSourceName           // databaseName
        );
    }

    /** Kotlin 默认参数等价重载：timestamp 缺省当前时刻。 */
    public static LogSqlEntry mcpHistoryEntry(String selectSql, String dataSourceName, Long elapsedMs,
                                              String errorText) {
        return mcpHistoryEntry(selectSql, dataSourceName, elapsedMs, errorText, System.currentTimeMillis());
    }

    /**
     * 落历史（T-McpAuditTrail T-MAT-01）：走 {@link SqlLogManager} 既有入库路径——{@code buffer.addEntry}
     * 之后面板刷新与 {@code SqlLogAutoSaver} 写盘全部复用既有链路（不新开写通道、不自建 MCP 专属存储）。
     * 取服务用 {@code project.getService}（Kotlin 类无平台生成的 {@code getInstance} 静态——Java 注解
     * 处理器只处理 Java 类，全仓取用口径一致，见 LogStartupCapture / SectionConfigurable）。
     *
     * <p>失败只记 idea.log 不冒泡：审计是旁路，绝不影响工具结果（{@link McpDbExecutor} 侧的回调投递同样
     * runCatching，两道都不许把审计故障升级为工具故障）。</p>
     *
     * <p><b>门控（D1 定案，2026-09-22 用户）</b>：审计条目<b>不受「SQL 日志捕获」开关约束</b>——走
     * {@link cn.song.mybatisplus.visualizer.log.SqlLogEntryBuffer#addAuditEntry}（跳过 {@code paused} 门），
     * 即开关关着（含用户显式停止后的 {@code paused=true}）时照样落列表；MCP 工具的可用性只由设置页
     * 「启用 SQL Visualizer MCP 工具」＋工具级禁用清单决定（{@code McpToolGate}）。
     * 其余门与普通条目一致：去重窗口内同 SQL 折叠、过滤关键字命中的丢弃（两者是列表展示偏好，不是开关）。</p>
     */
    public static void recordMcpExecution(Project project, String selectSql, String dataSourceName,
                                          long elapsedMs, String errorText) {
        try {
            // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建；审计是旁路，取不到即跳过）
            SqlLogManager manager = SqlLogManagerLookupKt.sqlLogManagerIfCreated(project);
            if (manager != null) {
                manager.getEntries().addAuditEntry(
                    mcpHistoryEntry(selectSql, dataSourceName, elapsedMs, errorText)
                );
            }
        } catch (Throwable e) {
            LOG.warn("T-McpAuditTrail: MCP 执行落历史失败（工具结果不受影响）", e);
        }
    }

    /** 摘要截断长度（Kotlin data class summary 默认参数口径，对齐 {@link LogSqlEntry}）。 */
    private static final int MAX_SUMMARY_LENGTH = 160;

    /**
     * 摘要默认值（Kotlin data class 默认参数；与 LogSqlEntry 私有 computeSummary 同一口径）。
     * 与 {@code log.agent.AgentEventAdapter} 同款私有副本——该方法在 log 包内为包内可见，
     * 跨包构造条目只能就地复刻。
     */
    private static String computeSummary(String restoredSql) {
        int newlineIdx = restoredSql.indexOf('\n');
        int endIdx = newlineIdx >= 0 && newlineIdx < MAX_SUMMARY_LENGTH
            ? newlineIdx
            : Math.min(restoredSql.length(), MAX_SUMMARY_LENGTH);
        if (endIdx < restoredSql.length()) {
            return restoredSql.substring(0, endIdx) + "\u2026";
        }
        return restoredSql;
    }
}
