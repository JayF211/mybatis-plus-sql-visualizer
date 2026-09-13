package cn.song.mybatisplus.visualizer.log;

import javax.swing.JPanel;

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
            // T-CrudColor：改用条目级缓存属性（行为等价）——避免每次刷新都重跑分类
            // （classify 内 Regex 每次编译 Pattern），与渲染器共用同一份结果。
            if (activeTypes.contains(it.statementType())) {
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

    /**
     * T-CrudColor：按标记形态应用「色条 / 整行淡背景 + 选中描边」——<b>两个列表渲染器共用</b>（§1.2.1）。
     *
     * 三分支：
     *  - 关闭 / OTHER（tint = null）→ 与改造前逐字节一致；
     *  - {@link SqlListTypeColor#STYLE_BAR} → 色条着色，单元格背景仍走平台选中填充（色条不受影响，选中不丢色）；
     *  - {@link SqlListTypeColor#STYLE_BACKGROUND} → 底色与列表背景混色（选中时提高比例），<b>不铺</b>平台选中填充，
     *    选中由「描边 + 色调加深」共同表达。
     *
     * selectedBorder 预建后此处只切引用 + 改色（{@link SqlListSelectionOutline#setColor}），热路径零分配；
     * 描边色经 {@link SqlListTypeColor#ensureOutlineContrast}——暗色主题下 selectionBackground
     * （#2E436E）压在加深后的行底色上几乎不可见，必须提亮。
     */
    public static void applyCrudColorMark(
            JPanel cell,
            JPanel colorBar,
            javax.swing.border.Border normalBorder,
            SqlListSelectionOutline selectedBorder,
            Color tint,
            boolean isSelected,
            boolean cellHasFocus,
            javax.swing.JList<?> list) {
        Color listBg = list != null ? list.getBackground() : cell.getBackground();
        Color selectionBg = list != null ? list.getSelectionBackground() : listBg;
        switch (SqlListTypeColor.styleOf()) {
            case SqlListTypeColor.STYLE_BAR: {
                if (tint == null) {
                    setOpaqueIfChanged(colorBar, false);
                } else {
                    setBackgroundIfChanged(colorBar, tint);
                    setOpaqueIfChanged(colorBar, true);
                }
                setBorderIfChanged(cell, normalBorder);
                setBackgroundIfChanged(cell, isSelected ? selectionBg : listBg);
                break;
            }
            case SqlListTypeColor.STYLE_BACKGROUND: {
                setOpaqueIfChanged(colorBar, false);
                if (tint == null) {
                    setBorderIfChanged(cell, normalBorder);
                    setBackgroundIfChanged(cell, isSelected ? selectionBg : listBg);
                } else {
                    setBackgroundIfChanged(
                            cell,
                            SqlListTypeColor.blend(
                                    listBg,
                                    tint,
                                    isSelected ? SqlListTypeColor.MIX_SELECTED : SqlListTypeColor.MIX_NORMAL));
                    if (isSelected) {
                        Color raw = cellHasFocus
                                ? selectionBg
                                : javax.swing.UIManager.getColor("List.selectionInactiveBackground");
                        if (raw == null) {
                            raw = selectionBg;
                        }
                        // setColor 只改字段、不触发 Swing 属性变更（无 repaint 副作用），故无需守卫
                        selectedBorder.setColor(
                                SqlListTypeColor.ensureOutlineContrast(raw, SqlListTypeColor.brightnessOf(listBg) < 0.5));
                        setBorderIfChanged(cell, selectedBorder);
                    } else {
                        setBorderIfChanged(cell, normalBorder);
                    }
                }
                break;
            }
            default: { // STYLE_OFF
                setOpaqueIfChanged(colorBar, false);
                setBorderIfChanged(cell, normalBorder);
                setBackgroundIfChanged(cell, isSelected ? selectionBg : listBg);
                break;
            }
        }
    }

    /**
     * 仅在变化时设置边框（T-CrudColor 性能守卫）。
     *
     * 实测（JBR 21）：{@code JComponent.setBorder(新实例)} <b>即使值与旧值相同也会触发 repaint() +1</b>，
     * 而传入<b>同一实例</b>则 revalidate/repaint 均为 0。渲染器逐格调用，若每格无脑赋值就会把
     * 渲染热路径变成「每格一次重绘调度」——正是 T-LogListFreeze 那类副作用累积问题的成因。
     * 用<b>同一性</b>（{@code !=}）比较：本函数只会在预建的两个 Border 实例间切换，身份比较即可，
     * 且避免 CompoundBorder.equals 的深度比较开销。
     */
    private static void setBorderIfChanged(JPanel cell, javax.swing.border.Border border) {
        if (cell.getBorder() != border) {
            cell.setBorder(border);
        }
    }

    /**
     * 仅在变化时设置背景（T-CrudColor 性能守卫）。
     *
     * 实测（JBR 21）：{@code setBackground(新 Color 实例)} 即使 RGB 相同也会触发 repaint() +1；
     * 传入同一实例则为 0。配合 {@link SqlListTypeColor#colorOf} 的实例缓存，同色格不再产生任何 repaint。
     */
    private static void setBackgroundIfChanged(JPanel target, Color color) {
        if (!java.util.Objects.equals(color, target.getBackground())) {
            target.setBackground(color);
        }
    }

    /** 仅在变化时设置不透明（setOpaque 同值本身零副作用，此处为语义显式化）。 */
    private static void setOpaqueIfChanged(JPanel target, boolean opaque) {
        if (target.isOpaque() != opaque) {
            target.setOpaque(opaque);
        }
    }

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
