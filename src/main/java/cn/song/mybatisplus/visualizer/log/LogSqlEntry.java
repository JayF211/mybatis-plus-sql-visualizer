package cn.song.mybatisplus.visualizer.log;

import java.util.List;

/**
 * 日志 SQL 条目模型（T-M31）：一条 SQL 语句 + 其参数列表。
 *
 * 来源：MyBatis / Hibernate / JdbcTemplate 控制台日志，经解析器配对、LogSqlRestorer 还原后进入面板。
 */
public record LogSqlEntry(
    /** 原始 SQL 文本（未替换占位符）。 */
    String rawSql,
    /** 还原后的可执行 SQL（参数已替换；无法替换的 ? 保留）。 */
    String restoredSql,
    /** 参数列表（与 SQL 中 ? 按序对应）。 */
    List<LogSqlParam> params,
    /** 捕获时间戳。 */
    long timestamp,
    /**
     * 摘要（列表展示用，智能截断：在第一行末尾或 MAX_SUMMARY_LENGTH 处取较小值，
     * 避免 mid-line 截断导致 multiline SQL 列表展示不完整）。
     */
    String summary,
    /** 条目来源（MyBatis / Hibernate / 未知）。 */
    SqlSource source,
    /**
     * 执行耗时（毫秒）；null 表示未解析到耗时信息。
     */
    Long executionTimeMs,
    /**
     * 产生该 SQL 的源文件路径（相对项目根目录）。
     * null 表示未知（旧版 F-43 保存的条目无此信息）。
     */
    String sourceFile,
    /**
     * 产生该 SQL 的源码行号（0 表示未知）。
     */
    int sourceLine,
    /**
     * T-LogRowNumber：会话内捕获序号（「SQL 日志」列表行号）。由 SqlLogEntryBuffer.addEntry
     * 成功入队时分配（单调递增；去重丢弃/暂停/过滤关键字命中的条目不占号），
     * 项目启动（processStarted）或手动清空列表时归零。0 表示无行号
     * （历史文件读回、日志转 SQL round-trip 产出的条目）。
     */
    long sequenceNumber
) {
    /** 摘要最大长度。 */
    private static final int MAX_SUMMARY_LENGTH = 160;

    /**
     * T-Perf-19（自 dev 移植）：去重键指纹（sqlFingerprint 惰性计算一次）——dedupeCounts 不再以
     * 完整 SQL 原文为键（还原参数文本可达数 KB/条，长会话数十~上百 MB 永久驻留）。
     * T-LogDedupeMpMark：指纹前先 normalizeSqlWhitespace——同一语句的双路日志副本（named 行 trim 过 /
     * prepared 行尾随空白原样保留）仅差首尾空白，原文指纹判重永不命中。
     * （record 无实例字段，纯函数每次现算——FNV-1a 线性扫描，成本可忽略。）
     */
    public String dedupeKey() {
        return LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace(restoredSql));
    }

    /** 仅原始/还原 SQL + 参数（其余取默认）。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params) {
        this(rawSql, restoredSql, params, System.currentTimeMillis(),
            computeSummary(restoredSql), SqlSource.UNKNOWN, null, null, 0, 0L);
    }

    /** 含显式时间戳。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, long timestamp) {
        this(rawSql, restoredSql, params, timestamp,
            computeSummary(restoredSql), SqlSource.UNKNOWN, null, null, 0, 0L);
    }

    /** 含来源。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, SqlSource source) {
        this(rawSql, restoredSql, params, System.currentTimeMillis(),
            computeSummary(restoredSql), source, null, null, 0, 0L);
    }

    /** 含时间戳 + 源码位置 + 耗时（历史文件反解析）。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params,
                       long timestamp, String sourceFile, int sourceLine, Long executionTimeMs) {
        this(rawSql, restoredSql, params, timestamp,
            computeSummary(restoredSql), SqlSource.UNKNOWN, executionTimeMs, sourceFile, sourceLine, 0L);
    }

    /** T-LogRowNumber：以本条为原型返回带指定捕获序号的副本（Kotlin data class copy(sequenceNumber=…) 的 Java 等价）。 */
    public LogSqlEntry withSequenceNumber(long newSequenceNumber) {
        return new LogSqlEntry(rawSql, restoredSql, params, timestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, newSequenceNumber);
    }

    /** 智能截断摘要。 */
    private static String computeSummary(String restoredSql) {
        int newlineIdx = restoredSql.indexOf('\n');
        int endIdx;
        if (newlineIdx >= 0 && newlineIdx < MAX_SUMMARY_LENGTH) {
            endIdx = newlineIdx;
        } else {
            endIdx = Math.min(restoredSql.length(), MAX_SUMMARY_LENGTH);
        }
        if (endIdx < restoredSql.length()) {
            return restoredSql.substring(0, endIdx) + "\u2026";
        }
        return restoredSql;
    }
}
