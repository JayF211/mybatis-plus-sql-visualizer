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
        return WHITESPACE_RUN_REGEX.matcher(sql.trim()).replaceAll(" ");
    }

    /** T-PS4-04：空白折叠正则提文件级常量（dedupeKey lazy/stampLogTime/桥嗅探三路共用，一处受益三路）。 */
    private static final java.util.regex.Pattern WHITESPACE_RUN_REGEX = java.util.regex.Pattern.compile("\\s+");

    /**
     * T-AgentLogTime：日志时间关联键——<b>占位符版 rawSql 的指纹</b>。
     *
     * 增强通道的 logTimestamp 是派生的（控制台链嗅探暂存 → agent 事件取回），关联键必须两侧同源：
     * 控制台侧 rawSql = {@code Preparing:} 行原文，agent 侧 rawSql = {@code prepareStatement} 收到的原文，
     * 二者都不经过参数还原。旧口径用 {@link LogSqlEntry#dedupeKey()}（还原后 SQL 指纹）要求两套还原器
     * 逐字符一致，布尔参数渲染、注释内 {@code ?} 计数、参数个数守卫三类分叉都会静默失配。
     *
     * 与 dedupeKey 的分工：dedupeKey 管面板去重（跨通道同 SQL 折叠，语义不动），
     * 本键只管日志时间配对——错位替换防护（agent 侧「占位符数 ≠ 参数数就不还原」）因此得以保留。
     */
    public static String logTimeKey(LogSqlEntry entry) {
        return sqlFingerprint(normalizeSqlWhitespace(entry.rawSql()));
    }

    /**
     * T-AgentLogTime：日志时间配对的次级判别——参数<b>值</b>序列签名。
     * 同键（同一条 SQL 多次执行）时用它挑参数一致的那条；两侧渲染口径若分叉则退化为队首配对
     * （近似但不错失），故不做强制相等断言。无参语句两侧都是空串，天然相等。
     * T-PS4-05：join 前逐值截断 {@link #PARAM_SIGNATURE_MAX_CHARS}——超长 blob/JSON 参数不再整串进签名，
     * 相关器驻留有界（桥嗅探 put 与 agent take 共用本函数，截断天然等价）。
     */
    public static String logTimeParamSignature(java.util.List<LogSqlParam> params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append('\u0001');
            String v = params.get(i).value();
            String rendered = v != null ? v : "\u0000";
            sb.append(rendered.length() > PARAM_SIGNATURE_MAX_CHARS
                ? rendered.substring(0, PARAM_SIGNATURE_MAX_CHARS) : rendered);
        }
        return sb.toString();
    }

    /**
     * T-PS4-05：签名单值截断上限（256 字符）。两侧（桥嗅探 put 与 agent take）走同一函数天然等价；
     * 次级判别失配本就设计为退化为队首配对（LogTimeCorrelator 注释），语义风险可控。
     */
    public static final int PARAM_SIGNATURE_MAX_CHARS = 256;

    /**
     * T-AgentLogTime：给 agent 条目回填控制台嗅探到的日志时间；未命中返回 null
     * （调用方回落 {@link LogSqlEntry#timestamp()} 并留痕）。
     *
     * 增强通道回填的唯一入口——生产路径（AgentManager.AgentSession）与跨通道守卫测试都走它，
     * 避免测试另抄一份「取键 + take」逻辑而自证（T-AgentDialectLoop 的教训：两侧各自绿、集成不通）。
     */
    public static LogSqlEntry stampLogTime(LogSqlEntry entry) {
        Long logTime = LogTimeCorrelator.take(logTimeKey(entry), logTimeParamSignature(entry.params()));
        return logTime != null ? entry.withLogTimestamp(logTime) : null;
    }
}
