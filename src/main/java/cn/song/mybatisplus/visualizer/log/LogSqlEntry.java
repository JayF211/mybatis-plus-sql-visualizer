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
    int sourceLine
) {
    /** 摘要最大长度。 */
    private static final int MAX_SUMMARY_LENGTH = 160;

    /** 仅原始/还原 SQL + 参数（其余取默认）。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params) {
        this(rawSql, restoredSql, params, System.currentTimeMillis(),
            computeSummary(restoredSql), SqlSource.UNKNOWN, null, null, 0);
    }

    /** 含显式时间戳。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, long timestamp) {
        this(rawSql, restoredSql, params, timestamp,
            computeSummary(restoredSql), SqlSource.UNKNOWN, null, null, 0);
    }

    /** 含来源。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, SqlSource source) {
        this(rawSql, restoredSql, params, System.currentTimeMillis(),
            computeSummary(restoredSql), source, null, null, 0);
    }

    /** 含时间戳 + 源码位置 + 耗时（历史文件反解析）。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params,
                       long timestamp, String sourceFile, int sourceLine, Long executionTimeMs) {
        this(rawSql, restoredSql, params, timestamp,
            computeSummary(restoredSql), SqlSource.UNKNOWN, executionTimeMs, sourceFile, sourceLine);
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
