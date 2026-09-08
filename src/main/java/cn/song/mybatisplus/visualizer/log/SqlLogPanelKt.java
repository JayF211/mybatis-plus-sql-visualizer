package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.ui.SimpleTextAttributes;

import java.awt.Color;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code SqlLogPanel.kt} 顶层函数（{@code withTerminator} / {@code filterByType} / {@code filterBySearch} /
 * T-Perf-03 差分 {@code diffVisible} 家族 / 渲染辅助 {@code formatTimestampFragment} /
 * {@code formatDurationFragment} / {@code formatSourceFragment}）的 Java 承载类。
 */
public final class SqlLogPanelKt {

    private SqlLogPanelKt() {
    }

    /** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复；T-M42 提为顶层，openPreview/右键复制/列表复制共用）。 */
    public static String withTerminator(String sql) {
        return (sql.isEmpty() || sql.endsWith(";")) ? sql : sql + ";";
    }

    // ---------- T-LogListFreeze：列表刷新分批常量 ----------

    /** 首屏立即写入条数（保证快速首屏，EDT 冻结可控）。 */
    public static final int REFRESH_FIRST_BATCH_SIZE = 30;

    /** 每批异步追加条数（invokeLater 每批一次 EDT 脉冲）。 */
    public static final int REFRESH_CHUNK_SIZE = 30;

    /** 同步增量追加条数上限：超过则回退全量重建（全量重建带分批 EDT 写入，大批量更稳）。 */
    public static final int MAX_SYNC_APPEND = 300;

    /** 类型多选弹窗可勾选的类型（OTHER 不单列；全选时 OTHER 一并透传）。 */
    public static final List<StatementType> FILTERABLE_TYPES = List.of(
            StatementType.SELECT,
            StatementType.INSERT,
            StatementType.UPDATE,
            StatementType.DELETE
    );

    /**
     * 类型过滤（纯逻辑，可独立单测）：所选类型覆盖全部可筛选类型时视为「全部」，
     * 透传所有条目（含 OTHER）；否则仅保留分类命中的条目。
     */
    public static List<LogSqlEntry> filterByType(List<LogSqlEntry> entries, Set<StatementType> activeTypes) {
        if (activeTypes.containsAll(FILTERABLE_TYPES)) {
            return entries;
        }
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry it : entries) {
            if (activeTypes.contains(LogStatementClassifier.classify(it))) {
                result.add(it);
            }
        }
        return result;
    }

    /**
     * 搜索过滤（纯逻辑，可独立单测）：查询词忽略大小写 contains，匹配还原 SQL 与摘要
     * （摘要含参数值的还原 SQL 前缀；参数值是常见检索目标）；空白查询透传全部。
     */
    public static List<LogSqlEntry> filterBySearch(List<LogSqlEntry> entries, String query) {
        String q = query.trim();
        if (q.isEmpty()) {
            return entries;
        }
        String ql = q.toLowerCase(Locale.ENGLISH);
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry it : entries) {
            if (it.restoredSql().toLowerCase(Locale.ENGLISH).contains(ql)
                    || it.summary().toLowerCase(Locale.ENGLISH).contains(ql)) {
                result.add(it);
            }
        }
        return result;
    }

    // ---------- T-Perf-03（自 dev 移植）：可见列表差分 ----------

    /**
     * 新旧可见列表差分结果（增量刷新决策；元素按同一性 == 引用比较——条目为不可变 record，
     * 缓冲内同一条目恒为同一实例）。
     */
    public interface VisibleDiff {

        /** 头部淘汰 evicted 条 + 尾部追加 appended（缓冲满后的流式 steady state；appended 可为空）。 */
        record HeadEvictAppend(int evicted, List<LogSqlEntry> appended) implements VisibleDiff {
        }

        /** 从 index 起插入 entries（尾部纯追加时 index == old.size）。 */
        record Insert(int index, List<LogSqlEntry> entries) implements VisibleDiff {
        }

        /** 尾部纯移除：移除 [from, old.size)。 */
        record TailRemove(int from) implements VisibleDiff {
        }

        /** 无变化。 */
        final class Same implements VisibleDiff {
            public static final Same INSTANCE = new Same();

            private Same() {
            }
        }

        /** 无法增量（离散增删/超大追加）——全量重建。 */
        final class FullRebuild implements VisibleDiff {
            public static final FullRebuild INSTANCE = new FullRebuild();

            private FullRebuild() {
            }
        }
    }

    /** 同一性公共前缀长度。 */
    public static int commonPrefixLength(List<LogSqlEntry> old, List<LogSqlEntry> new_) {
        int n = Math.min(old.size(), new_.size());
        int i = 0;
        while (i < n && old.get(i) == new_.get(i)) i++;
        return i;
    }

    /** 同一性公共后缀长度（不与 prefix 重叠）。 */
    public static int commonSuffixLength(List<LogSqlEntry> old, List<LogSqlEntry> new_, int prefix) {
        int n = Math.min(old.size(), new_.size()) - prefix;
        int i = 0;
        while (i < n && old.get(old.size() - 1 - i) == new_.get(new_.size() - 1 - i)) i++;
        return i;
    }

    /** old 的 [aStart..] 与 new 的 [bStart..] 前 length 个元素是否同一。 */
    public static boolean identityPrefix(List<LogSqlEntry> old, int aStart, List<LogSqlEntry> new_, int bStart, int length) {
        if (old.size() - aStart < length || new_.size() - bStart < length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (old.get(aStart + i) != new_.get(bStart + i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 可见列表差分：流式灌入时把「clear+全量重建」降阶为「尾部追加 / 头部淘汰+追加」。
     *
     * 判定顺序：
     *  1. 头部淘汰对齐（evicted>0 且 old 去头后与 new 前缀同一）→ HeadEvictAppend；
     *  2. 前缀/后缀差分 → 纯插入（含尾部追加）/ 尾部纯移除 / 无变化；
     *  3. 其余（过滤、搜索、去重裁剪、排序导致的离散增删）→ FullRebuild。
     */
    public static VisibleDiff diffVisible(List<LogSqlEntry> old, List<LogSqlEntry> new_, int evicted) {
        if (evicted > 0 && old.size() >= evicted && new_.size() >= old.size() - evicted
                && identityPrefix(old, evicted, new_, 0, old.size() - evicted)) {
            List<LogSqlEntry> appended = new_.subList(old.size() - evicted, new_.size());
            return new VisibleDiff.HeadEvictAppend(evicted, appended);
        }
        int prefix = commonPrefixLength(old, new_);
        int suffix = commonSuffixLength(old, new_, prefix);
        int removed = old.size() - prefix - suffix;
        int added = new_.size() - prefix - suffix;
        if (removed == 0 && added == 0) {
            return VisibleDiff.Same.INSTANCE;
        }
        if (removed == 0 && added >= 1 && added <= MAX_SYNC_APPEND) {
            return new VisibleDiff.Insert(prefix, new_.subList(prefix, prefix + added));
        }
        if (added == 0 && suffix == 0) {
            return new VisibleDiff.TailRemove(prefix);
        }
        return VisibleDiff.FullRebuild.INSTANCE;
    }

    // ---------- 列表渲染辅助（T-LogListFreeze-04 片段通道；渲染器与 SqlLogPanel 共用） ----------

    /** 元信息灰（时间戳/来源/徽章；与原 HTML 通道 #9E9E9E 一致）。 */
    public static final SimpleTextAttributes LIST_META_GRAY =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new Color(0x9E9E9E));

    /** 时间戳片段：未开启时间戳返回 null。片段文本带尾随空格做段间距（原 HTML span 尾空格语义）。 */
    public static kotlin.Pair<String, SimpleTextAttributes> formatTimestampFragment(long timestamp) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        if (!state.getLogTimestampEnabled()) {
            return null;
        }
        String formatted;
        try {
            formatted = new SimpleDateFormat(state.getLogTimestampFormat()).format(new Date(timestamp));
        } catch (IllegalArgumentException e) {
            formatted = new SimpleDateFormat(SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT).format(new Date(timestamp));
        }
        return new kotlin.Pair<>("[" + formatted + "] ", LIST_META_GRAY);
    }

    /** 耗时片段：颜色分档与原 HTML 通道一致（选中白/无耗时灰/慢红/偏慢橙/正常绿）；无耗时显示 "—" 占位。 */
    public static kotlin.Pair<String, SimpleTextAttributes> formatDurationFragment(Long ms, boolean isSelected) {
        long threshold = PluginSettings.getInstance().getState().getSlowQueryThresholdMs();
        Color color;
        if (isSelected) {
            color = new Color(0xFFFFFF);
        } else if (ms == null) {
            color = new Color(0x9E9E9E);          // 灰：无耗时
        } else if (ms >= threshold) {
            color = new Color(0xD32F2F);     // 红：慢查询
        } else if (ms >= threshold / 2) {
            color = new Color(0xE67E22); // 橙：偏慢
        } else {
            color = new Color(0x4CAF50);                // 绿：正常
        }
        String text = (ms != null) ? (ms + "ms") : "—";
        return new kotlin.Pair<>(text + " ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color));
    }

    /** 来源片段：未知来源返回 null；选中态走组件前景（selectionForeground），非选中灰。 */
    public static kotlin.Pair<String, SimpleTextAttributes> formatSourceFragment(LogSqlEntry entry, boolean isSelected) {
        String source;
        if (SourceMarkers.isMpPaginationCount(entry.sourceFile())) {
            source = "MP 分页计数";
        } else if (entry.sourceFile() != null) {
            // F-44-7：标记路径始终使用正斜杠（与 CallerSourceLocator 一致），不能用 File.separatorChar
            String file = entry.sourceFile();
            String name = file.substring(file.lastIndexOf('/') + 1);
            source = entry.sourceLine() > 0 ? name + ":" + entry.sourceLine() : name;
        } else {
            return null;
        }
        return new kotlin.Pair<>("[" + source + "] ",
                isSelected ? SimpleTextAttributes.REGULAR_ATTRIBUTES : LIST_META_GRAY);
    }
}
