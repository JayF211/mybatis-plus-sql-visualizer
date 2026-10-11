package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * {@code HistorySqlLogPanel.kt} 顶层声明的 Java 承载类：
 *
 * <ul>
 *   <li>T-HistorySearchToggles 检索纯逻辑：{@code regex:} 前缀常量、正则 memo 解析
 *       {@code resolveHistoryRegex}、二级检索过滤 {@code filterHistoryEntries}；</li>
 *   <li>R31 历史/版本 SQL 对比结论：{@link #historyDiffVerdict}/{@link #historyDiffDialogTitle}
 *       与载体 {@link HistoryDiffVerdict}；</li>
 *   <li>T-SettingsFocusFromLink 空态链接显示条件 {@link #shouldShowAutoSaveHistoryLink}。</li>
 * </ul>
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
                    : LogEntryFilteringKt.timestampDisplayText(entry);
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

    /**
     * R31：两条目对比结论——按时间戳升序定「旧/新」（旧在左、新在右，入参顺序无关），
     * {@link SqlDiffer#computeDiff} 逐行 diff 统计，结构指纹（字面量/数字参数化，
     * {@link LogSqlEntry#structureKey()}）判等价。死代码复活：{@link SqlDiffer}
     * （T-Perf-24 标注「无任何调用方」）自此获得唯一真实调用方。
     */
    public static HistoryDiffVerdict historyDiffVerdict(LogSqlEntry first, LogSqlEntry second) {
        List<LogSqlEntry> ordered = new ArrayList<>(List.of(first, second));
        ordered.sort(Comparator.comparingLong(LogSqlEntry::timestamp));
        LogSqlEntry older = ordered.get(0);
        LogSqlEntry newer = ordered.get(1);
        List<SqlDiffer.DiffEntry> entries =
                SqlDiffer.computeDiff(older.restoredSql(), newer.restoredSql());
        int deleteCount = 0;
        int insertCount = 0;
        for (SqlDiffer.DiffEntry e : entries) {
            if (e.type() == SqlDiffer.DiffType.DELETE) {
                deleteCount++;
            }
            if (e.type() == SqlDiffer.DiffType.INSERT) {
                insertCount++;
            }
        }
        return new HistoryDiffVerdict(
                Objects.equals(older.structureKey(), newer.structureKey()),
                entries.size(),
                deleteCount,
                insertCount,
                older.restoredSql(),
                newer.restoredSql());
    }

    /** R31 对比窗口标题：结构等价提示「仅参数不同」，否则带差异行数。 */
    public static String historyDiffDialogTitle(HistoryDiffVerdict verdict) {
        String base = MpSqlVisualizerBundle.message("log.history.compare.title");
        return verdict.structurallyEquivalent()
                ? base + " — " + MpSqlVisualizerBundle.message("log.history.compare.equivalent")
                : base + " — " + MpSqlVisualizerBundle.message("log.history.compare.diffLines", verdict.diffCount());
    }

    /**
     * 历史页空态「自动保存历史 SQL」链接的显示条件（T-SettingsFocusFromLink，纯函数便于单测）：
     * 仅当用户<b>未开启自动保存</b>且<b>没有任何历史文件</b>时露出（用户诉求语义）。
     */
    public static boolean shouldShowAutoSaveHistoryLink(boolean autoSaveEnabled, int fileCount) {
        return !autoSaveEnabled && fileCount == 0;
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
