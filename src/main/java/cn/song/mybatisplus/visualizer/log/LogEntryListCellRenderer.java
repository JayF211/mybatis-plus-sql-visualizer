package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 日志列表条目单元格渲染器（F-44-2 重写；T-LogListFreeze-04 弃 HTML 通道）：
 * 双 {@link SimpleColoredComponent} 两行结构（T-TwoLine 语义保留：元信息行在上、SQL 高亮行在下）。
 * 原 JLabel+HTML 通道每次 setText 都触发 BasicHTML 解析排版，叠加可变行高逐条测量——
 * 16s EDT Freeze 报告主因；片段通道渲染只剩轻量文本测量，配合固定行高布局走 O(1) 快路径。
 * 文本原样着色（无 HTML 转义面，注入场景不复存在）；摘要构造时已在首行截断（LogSqlEntry.summary），无换行处理面。
 * 抽为具名类，便于单元测试直接渲染各种条目状态、锁定配色语义。
 */
public class LogEntryListCellRenderer extends JPanel implements javax.swing.ListCellRenderer<Object> {

    /** 去重丢弃计数快照（徽章渲染数据源；由 SqlLogPanel.refreshList 更新）。 */
    public Map<String, Integer> dedupeCounts = Map.of();

    /** 元信息行（internal 供 UI 测试遍历片段断言文本与着色）。 */
    public final SimpleColoredComponent metaLine = new SimpleColoredComponent();

    /** SQL 行组件池（T-ListMode：单行模式恒 1 行；多行模式按变换后行数伸缩）。 */
    private final List<SimpleColoredComponent> sqlLinePool = new ArrayList<>();

    private final JPanel linesPanel = new JPanel(new GridLayout(0, 1));

    /**
     * T-CrudColor：左侧类型色条（{@link SqlListTypeColor#STYLE_BAR} 下着色可见；
     * 其他样式 isOpaque=false 但仍<b>占位</b>——占位是刻意的：切换样式时文本左缘不位移，
     * 且左内边距 5 + 色条 3 = 原 8px，文本左缘像素位置与改造前一致。
     */
    public final JPanel colorBar = new JPanel();

    /** 未选中态 Border（分隔线 + 内边距）。 */
    public final javax.swing.border.Border normalBorder = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            new javax.swing.border.EmptyBorder(5, 5, 5, 8) // 左 5 + 色条 3 = 原 8
    );

    /**
     * T-CrudColor：选中态描边 Border（{@link SqlListTypeColor#STYLE_BACKGROUND} 专用）。
     * 预建一次、渲染时只切引用 + 改色（{@link SqlListSelectionOutline#setColor}），热路径零分配。
     * 内层分隔线为<b>透明占位</b>：仅占 1px inset，不绘制——否则灰线会画在蓝框内部把底边割断。
     */
    public final SqlListSelectionOutline selectedBorder = new SqlListSelectionOutline(
            SqlListTypeColor.OUTLINE_WIDTH,
            JBColor.foreground(),
            BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, SqlListTypeColor.TRANSPARENT),
                    new javax.swing.border.EmptyBorder(
                            5 - SqlListTypeColor.OUTLINE_WIDTH,
                            5 - SqlListTypeColor.OUTLINE_WIDTH,
                            5 - SqlListTypeColor.OUTLINE_WIDTH,
                            8 - SqlListTypeColor.OUTLINE_WIDTH)
            ));

    public LogEntryListCellRenderer() {
        super(new BorderLayout());
        sqlLinePool.add(newLineComponent());
        setOpaque(true);
        metaLine.setOpaque(false);
        linesPanel.setOpaque(false);
        linesPanel.add(metaLine);
        linesPanel.add(sqlLinePool.get(0));
        // T-CrudColor：色条挂 WEST——CENTER 已被组件池面板占用，WEST 空闲且零结构冲突
        colorBar.setPreferredSize(new Dimension(SqlListTypeColor.BAR_WIDTH, 1));
        colorBar.setOpaque(false);
        add(colorBar, BorderLayout.WEST);
        add(linesPanel, BorderLayout.CENTER);
        setBorder(normalBorder);
    }

    /** SQL 首行（SINGLE_LINE 恒为唯一 SQL 行；测试断言兼容 T-LogListFreeze-04 单行语义）。 */
    public SimpleColoredComponent getSqlLine() {
        return sqlLinePool.get(0);
    }

    /** 全部 SQL 行（internal 供 UI 测试按行断言多行模式渲染）。 */
    public List<SimpleColoredComponent> getSqlLines() {
        return sqlLinePool;
    }

    /** 新建 SQL 行组件（池扩充用；不透明性与既有行一致）。 */
    private static SimpleColoredComponent newLineComponent() {
        SimpleColoredComponent comp = new SimpleColoredComponent();
        comp.setOpaque(false);
        return comp;
    }

    /** 元信息行渲染文本（测试断言用：全部片段拼接、trim 段间距）。 */
    public String getMetaTextForTest() {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = metaLine.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
        }
        return sb.toString().trim();
    }

    @Override
    public Component getListCellRendererComponent(
            javax.swing.JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
    ) {
        Color listBg = list != null ? list.getBackground() : getBackground();
        Color selectionBg = list != null ? list.getSelectionBackground() : listBg;
        LogSqlEntry entry = value instanceof LogSqlEntry ? (LogSqlEntry) value : null;

        // T-CrudColor：标记形态分派（读设置 + 纯函数；热路径零分配）
        String style = SqlListTypeColor.styleOf();
        Color tint = entry != null ? SqlListTypeColor.colorOf(entry.statementType()) : null;
        // 背景色样式下底色是淡色调而非饱和选中填充：若按选中态取白字，亮色主题下不可见；
        // 且耗时着色会被抹成白色、丢失快/慢信号。故仅在色条样式（走平台填充）时按选中态取前景。
        boolean useSelectionFill = isSelected && SqlListTypeColor.usesSelectionFill(style);
        SqlLogPanelKt.applyCrudColorMark(this, colorBar, normalBorder, selectedBorder, tint, isSelected, cellHasFocus, list);

        Color fg = useSelectionFill
                ? (list != null && list.getSelectionForeground() != null ? list.getSelectionForeground() : JBColor.foreground())
                : (list != null ? list.getForeground() : JBColor.foreground());
        metaLine.setForeground(fg);
        metaLine.clear();
        List<List<SqlTextFragment>> lineFragments = null;
        if (entry != null) {
            // T-LogRowNumber：行号片段在最前（最高频引用锚点；0 = 无行号——历史文件/round-trip 条目）
            if (entry.sequenceNumber() > 0) {
                metaLine.append("#" + entry.sequenceNumber() + " ", SqlLogPanelKt.LIST_META_GRAY);
            }
            // T-TwoLine：元信息行（时间戳/耗时/来源/去重徽章）在上，段间距由尾随空格承担
            kotlin.Pair<String, SimpleTextAttributes> ts = SqlLogPanelKt.formatTimestampFragment(entry.timestamp());
            if (ts != null) {
                metaLine.append(ts.getFirst(), ts.getSecond());
            }
            if (PluginSettings.getInstance().getState().getShowDurationInLogList()) {
                kotlin.Pair<String, SimpleTextAttributes> dur =
                        SqlLogPanelKt.formatDurationFragment(entry.executionTimeMs(), useSelectionFill);
                metaLine.append(dur.getFirst(), dur.getSecond());
            }
            kotlin.Pair<String, SimpleTextAttributes> src = SqlLogPanelKt.formatSourceFragment(entry, useSelectionFill);
            if (src != null) {
                metaLine.append(src.getFirst(), src.getSecond());
            }
            Integer cnt = dedupeCounts.get(entry.dedupeKey());
            if (cnt != null && cnt > 0) {
                metaLine.append("⨉" + cnt + " ", SqlLogPanelKt.LIST_META_GRAY);
            }
            lineFragments = sqlLineFragmentsFor(entry);
        }
        // SQL 高亮行在下（T-ListMode：行数随显示模式伸缩）；选中态保留 SQL 高亮原色
        ensureSqlLineCount(lineFragments != null ? lineFragments.size() : 1);
        for (int i = 0; i < sqlLinePool.size(); i++) {
            SimpleColoredComponent comp = sqlLinePool.get(i);
            comp.setForeground(fg);
            comp.clear();
            if (lineFragments != null && i < lineFragments.size()) {
                for (SqlTextFragment f : lineFragments.get(i)) {
                    comp.append(f.text(), f.attributes());
                }
            }
        }
        // T-ClickPreview：不设 toolTipText（悬停逐行弹气泡 = 条目多时卡顿根因），预览改单击触发
        return this;
    }

    /** SQL 行组件池伸缩到 n 行（首位恒保留——sqlLine 测试缝；收缩移除末尾）。 */
    private void ensureSqlLineCount(int n) {
        while (sqlLinePool.size() < n) {
            SimpleColoredComponent comp = newLineComponent();
            sqlLinePool.add(comp);
            linesPanel.add(comp);
        }
        while (sqlLinePool.size() > Math.max(1, n)) {
            linesPanel.remove(sqlLinePool.remove(sqlLinePool.size() - 1));
        }
    }

    /**
     * SQL 行片段（T-ListMode 模式分流）：SINGLE_LINE 直渲染不缓存（固定行高 + 主题切换实时生效）；
     * 多行模式走条目级缓存（键含模式自动失效）——可变行高布局逐条测量免重复分词。
     */
    private List<List<SqlTextFragment>> sqlLineFragmentsFor(LogSqlEntry entry) {
        String sql = SqlLogPanelKt.withTerminator(entry.summary());
        SqlListDisplayMode mode = PluginSettings.getInstance().sqlListDisplayMode();
        if (mode == SqlListDisplayMode.SINGLE_LINE) {
            List<List<SqlTextFragment>> single = new ArrayList<>();
            single.add(SqlListFragmentRenderer.renderFragments(sql));
            return single;
        }
        List<List<SqlTextFragment>> cached = entry.cachedListLineFragments();
        if (cached != null && entry.cachedListLineFragmentsMode() == mode) {
            return cached;
        }
        List<List<SqlTextFragment>> computed = SqlListFragmentRenderer.renderLineFragments(sql, mode);
        entry.setCachedListLineFragments(computed);
        entry.setCachedListLineFragmentsMode(mode);
        return computed;
    }
}
