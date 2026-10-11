package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code HistorySqlLogPanel.kt} 顶层声明（T-HistorySearchToggles 检索纯逻辑：{@code regex:} 前缀常量、
 * 正则 memo 解析 {@code resolveHistoryRegex}、二级检索过滤 {@code filterHistoryEntries}）的 Java 承载类。
 */
public final class HistorySqlLogPanelKt {

    private HistorySqlLogPanelKt() {
    }

    /** {@code regex:} 前缀（R16 已发行能力）：正则开关关闭时同样触发正则匹配（或语义，前缀在场先剥）。 */
    public static final String HISTORY_REGEX_PREFIX = "regex:";

    /** 按 {@code memo} 取编译结果：同源串<b>同旗</b>复用，否则（重）编译；非法源串 memo 失败供下次复用。 */
    public static kotlin.text.Regex resolveHistoryRegex(String source, boolean matchCase, HistoryRegexMemo memo) {
        if (source.equals(memo.source) && matchCase == memo.matchCase) {
            return memo.compiled;
        }
        kotlin.text.Regex compiled;
        try {
            compiled = matchCase
                    ? new kotlin.text.Regex(source)
                    : new kotlin.text.Regex(source, kotlin.text.RegexOption.IGNORE_CASE);
        } catch (Throwable t) {
            compiled = null;
        }
        memo.source = source;
        memo.matchCase = matchCase;
        memo.compiled = compiled;
        return compiled;
    }

    /**
     * 历史二级检索过滤（纯逻辑，形态照日志页 {@code filterBySearch} 先例）：
     *
     * <ul>
     *   <li><b>正则</b>：生效条件 = 开启正则开关<b>或</b> {@code regex:} 前缀（前缀在场先剥、不参与匹配）；
     *       命中三维 restoredSql/summary/sourceFile（R16 现状，时间不参与）；编译旗随 matchCase；</li>
     *   <li><b>非法正则</b>：fail-closed 回退普通子串匹配（回退查询为原始 query 全文含前缀，与现状逐字一致）；</li>
     *   <li><b>子串</b>：匹配 restoredSql/summary/时间显示片段/sourceFile，大小写统一由 matchCase 控制；
     *       行号 {@code #N}/{@code N} 精确 equals sequenceNumber，不受开关影响；</li>
     *   <li>空查询透传全部。</li>
     * </ul>
     */
    public static List<LogSqlEntry> filterHistoryEntries(List<LogSqlEntry> entries, String query) {
        return filterHistoryEntries(entries, query, false, false, new HistoryRegexMemo(), null);
    }

    public static List<LogSqlEntry> filterHistoryEntries(List<LogSqlEntry> entries, String query,
            boolean regexEnabled, boolean matchCase, HistoryRegexMemo memo) {
        return filterHistoryEntries(entries, query, regexEnabled, matchCase, memo, null);
    }

    public static List<LogSqlEntry> filterHistoryEntries(List<LogSqlEntry> entries, String query,
            boolean regexEnabled, boolean matchCase, HistoryRegexMemo memo,
            java.util.function.Function<LogSqlEntry, String> timestampTextOf) {
        String q = query.trim();
        if (q.isEmpty()) {
            return entries;
        }
        if (regexEnabled || q.startsWith(HISTORY_REGEX_PREFIX)) {
            kotlin.text.Regex pattern = resolveHistoryRegex(
                    q.substring(HISTORY_REGEX_PREFIX.length()), matchCase, memo);
            if (pattern != null) {
                List<LogSqlEntry> result = new ArrayList<>();
                for (LogSqlEntry entry : entries) {
                    if (pattern.containsMatchIn(entry.restoredSql())
                            || pattern.containsMatchIn(entry.summary())
                            || (entry.sourceFile() != null && pattern.containsMatchIn(entry.sourceFile()))) {
                        result.add(entry);
                    }
                }
                return result;
            }
            // 非法正则：回退普通子串匹配（继续走下方通用路径）
        }
        Long rowNumber = null;
        String rowRaw = q.startsWith("#") ? q.substring(1) : q;
        try {
            rowNumber = Long.parseLong(rowRaw);
        } catch (NumberFormatException ignored) {
            // 非纯数字查询 → 行号维度不参与
        }
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry entry : entries) {
            String ts = timestampTextOf != null
                    ? timestampTextOf.apply(entry)
                    : SqlLogPanelKt.timestampDisplayText(entry);
            boolean match = contains(entry.restoredSql(), q, !matchCase)
                    || contains(entry.summary(), q, !matchCase)
                    || (rowNumber != null && entry.sequenceNumber() == rowNumber)
                    || (ts != null && contains(ts, q, !matchCase))
                    || (entry.sourceFile() != null && contains(entry.sourceFile(), q, !matchCase));
            if (match) {
                result.add(entry);
            }
        }
        return result;
    }

    private static boolean contains(String text, String q, boolean ignoreCase) {
        if (text == null) {
            return false;
        }
        if (ignoreCase) {
            return text.toLowerCase(java.util.Locale.ROOT).contains(q.toLowerCase(java.util.Locale.ROOT));
        }
        return text.contains(q);
    }
}
