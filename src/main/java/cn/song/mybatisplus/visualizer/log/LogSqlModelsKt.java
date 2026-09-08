package cn.song.mybatisplus.visualizer.log;

import java.util.stream.Collectors;

/**
 * 日志条目渲染回 MyBatis 日志文本（T-M38）的扩展函数载体。
 */
public final class LogSqlModelsKt {
    private LogSqlModelsKt() {}

    /** 条目渲染回 MyBatis 日志文本。 */
    public static String toLogText(LogSqlEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("==>  Preparing: ").append(entry.rawSql());
        if (!entry.params().isEmpty()) {
            sb.append('\n').append("==> Parameters: ");
            sb.append(entry.params().stream().map(LogSqlModelsKt::toParamText).collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    /** 单个参数渲染回日志形态：1(Long) / abc(String) / null。 */
    private static String toParamText(LogSqlParam p) {
        String v = p.value();
        if (v == null) return "null";
        return p.type() != null ? v + "(" + p.type() + ")" : v;
    }

    /**
     * T-Perf-19（自 dev 移植）：SQL 指纹（FNV-1a 64 位十六进制串）——去重计数键用，避免以完整 SQL 原文为键
     * 的内存驻留。64 位散列下两条不同 SQL 撞键概率可忽略（~2^-64 量级）。
     */
    public static String sqlFingerprint(String sql) {
        long h = -0x340d631b7bdddcdbL; // FNV-1a 64-bit offset basis
        for (int i = 0; i < sql.length(); i++) {
            h = h ^ (long) sql.charAt(i);
            h *= 0x100000001b3L; // FNV-1a 64-bit prime
        }
        return Long.toHexString(h);
    }

    /**
     * T-LogDedupeMpMark：去重口径的空白规范化（trim + 连续空白折叠为单空格）。
     * JdbcTemplate 双路日志对同一语句产出 named/prepared 两副本，仅差首尾空白；
     * 大小写与分号不动——COUNT 前缀判定、达梦带引号标识符大小写均不受影响。
     */
    public static String normalizeSqlWhitespace(String sql) {
        return sql.trim().replaceAll("\\s+", " ");
    }
}
