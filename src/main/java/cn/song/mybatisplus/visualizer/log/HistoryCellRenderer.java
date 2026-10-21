package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.speedSearch.SpeedSearchUtil;

import javax.swing.BorderFactory;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 二级 SQL 列表单元格渲染器（F-44-2；T-LogListFreeze-04 弃 HTML 通道）：
 * 双 {@link SimpleColoredComponent} 两行结构（T-TwoLine 语义保留）：元信息行在上、SQL 高亮行在下。
 * 原 JLabel-HTML 通道每次 setText 触发 BasicHTML 解析排版 + 可变行高逐条测量 = 列表卡顿根因。
 * internal 供 UI 测试断言各种条目状态（空耗时/空源码/特殊字符/超长 SQL）下的渲染健壮性。
 */
public class HistoryCellRenderer extends JPanel implements javax.swing.ListCellRenderer<Object> {

    /** 历史面板元信息灰（与原 HTML 通道 #9E9E9E 一致）。T-UN-13（T-UiNative）：改 namedColor，亮值不变。 */
    private static final SimpleTextAttributes HISTORY_META_GRAY =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.HISTORY_META_GRAY);

    /** O-90：同模板计数快照（「×N 同模板」徽章渲染数据源；HistorySqlLogPanel.applySqlSearch 更新）。 */
    public Map<String, Integer> templateCounts = Map.of();

    /** 元信息行（internal 供 UI 测试遍历片段断言）。 */
    public final SimpleColoredComponent metaLine = new SimpleColoredComponent();

    /** SQL 行组件池（T-ListMode：单行模式恒 1 行；多行模式按变换后行数伸缩）。 */
    private final List<SimpleColoredComponent> sqlLinePool = new ArrayList<>();

    private final JPanel linesPanel = new JPanel(new GridLayout(0, 1));

    /**
     * T-CrudColor：左侧类型色条（色条样式下着色可见；其他样式 isOpaque=false 但仍占位——
     * 占位是刻意的：切换样式时文本左缘不位移，且左内边距 5 + 色条 3 = 原 8）。
     */
    public final JPanel colorBar = new JPanel();

    /** Border（分隔线 + 内边距；T-UN-09 描边退役后选中态同样只用它，选中视觉归平台绘制）。 */
    public final javax.swing.border.Border normalBorder = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 8) // 左 5 + 色条 3 = 原 8
    );

    public HistoryCellRenderer() {
        super(new BorderLayout());
        SimpleColoredComponent firstSqlLine = new SimpleColoredComponent();
        firstSqlLine.setOpaque(false);
        sqlLinePool.add(firstSqlLine);
        setOpaque(true);
        metaLine.setOpaque(false);
        linesPanel.setOpaque(false);
        linesPanel.add(metaLine);
        linesPanel.add(firstSqlLine);
        // T-CrudColor：色条挂 WEST（CENTER 已被组件池面板占用）
        colorBar.setPreferredSize(new Dimension(SqlListTypeColor.BAR_WIDTH, 1));
        colorBar.setOpaque(false);
        add(colorBar, BorderLayout.WEST);
        add(linesPanel, BorderLayout.CENTER);
        setBorder(normalBorder);
    }

    /** SQL 首行（SINGLE_LINE 恒为唯一 SQL 行；测试断言兼容单行语义）。 */
    public SimpleColoredComponent getSqlLine() {
        return sqlLinePool.get(0);
    }

    /** 全部 SQL 行（internal 供 UI 测试按行断言多行模式渲染）。 */
    public List<SimpleColoredComponent> getSqlLines() {
        return sqlLinePool;
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
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
    ) {
        LogSqlEntry entry = value instanceof LogSqlEntry ? (LogSqlEntry) value : null;
        // T-CrudColor：标记形态分派（与日志页共用 applyCrudColorMark，语义完全一致）
        Color tint = entry != null ? SqlListTypeColor.colorOf(entry.statementType()) : null;
        // T-UN-09：选中描边退役后，三种样式的选中行统一走平台选中填充 → 前景按选中态取
        // selectionForeground（与日志页 LogEntryListCellRenderer 同款）
        boolean useSelectionFill = isSelected;
        SqlLogPanelKt.applyCrudColorMark(this, colorBar, normalBorder, tint, isSelected, list);

        Color fg = useSelectionFill
                ? (list != null && list.getSelectionForeground() != null ? list.getSelectionForeground() : JBColor.foreground())
                : (list != null ? list.getForeground() : JBColor.foreground());
        metaLine.setForeground(fg);
        metaLine.clear();
        List<List<SqlTextFragment>> lineFragments = null;
        if (entry != null) {
            List<SqlTextFragment> metadata = SqlEntryPresentation.metadata(entry, isSelected, true, templateCounts);
            for (SqlTextFragment fragment : SqlEntryPresentation.clipForList(metadata, list, this, SqlListTypeColor.BAR_WIDTH)) {
                metaLine.append(fragment.text(), isSelected
                        ? SqlEntryPresentation.selectedAttributes(fragment.attributes(), fg) : fragment.attributes());
            }
            lineFragments = sqlLineFragmentsFor(entry);
        }
        // T-ListMode：SQL 行数随显示模式伸缩；T-ClickPreview 不设 toolTipText（预览改单击触发）
        ensureSqlLineCount(lineFragments != null ? lineFragments.size() : 1);
        // T-UiNative speed search 高亮：对元信息行与各 SQL 行施加匹配片段高亮（同主日志列表）
        if (list != null) {
            SpeedSearchUtil.applySpeedSearchHighlighting(list, metaLine, false, useSelectionFill);
        }
        for (int i = 0; i < sqlLinePool.size(); i++) {
            SimpleColoredComponent comp = sqlLinePool.get(i);
            comp.setForeground(fg);
            comp.clear();
            if (lineFragments != null && i < lineFragments.size()) {
                for (SqlTextFragment f : SqlEntryPresentation.clipForList(lineFragments.get(i), list, this, SqlListTypeColor.BAR_WIDTH)) {
                    comp.append(f.text(), isSelected ? SqlEntryPresentation.selectedAttributes(f.attributes(), fg) : f.attributes());
                }
            }
            if (list != null) {
                SpeedSearchUtil.applySpeedSearchHighlighting(list, comp, false, useSelectionFill);
            }
        }
        return this;
    }

    /** SQL 行组件池伸缩到 n 行（首位恒保留——sqlLine 测试缝；收缩移除末尾）。 */
    private void ensureSqlLineCount(int n) {
        while (sqlLinePool.size() < n) {
            SimpleColoredComponent comp = new SimpleColoredComponent();
            comp.setOpaque(false);
            sqlLinePool.add(comp);
            linesPanel.add(comp);
        }
        while (sqlLinePool.size() > Math.max(1, n)) {
            linesPanel.remove(sqlLinePool.remove(sqlLinePool.size() - 1));
        }
    }

    /**
     * SQL 行片段（T-ListMode 模式分流，与 {@link LogEntryListCellRenderer} 同款）：
     * SINGLE_LINE 直渲染不缓存；多行模式走条目级缓存（键含模式自动失效）。
     * 历史条目为每次加载新建的实例，缓存随条目生命周期天然失效。
     */
    private List<List<SqlTextFragment>> sqlLineFragmentsFor(LogSqlEntry entry) {
        SqlListDisplayMode mode = PluginSettings.getInstance().sqlListDisplayMode();
        // R60：只在展示投影脱敏；FORMATTED 使用完整 SQL，其他模式沿用摘要。
        boolean maskOn = PluginSettings.getInstance().getState().getDisplayMaskEnabled();
        String sql = SqlLogPanelKt.withTerminator(SqlListFragmentRenderer.displaySqlForList(entry, mode, maskOn));
        if (mode == SqlListDisplayMode.SINGLE_LINE) {
            List<List<SqlTextFragment>> single = new ArrayList<>();
            single.add(SqlListFragmentRenderer.renderFragments(sql));
            return single;
        }
        List<List<SqlSemanticFragment>> semantics = entry.cachedListLineFragments();
        if (semantics == null || entry.cachedListLineFragmentsMode() != mode
                || !Objects.equals(entry.cachedListLineFragmentsMasked(), maskOn)) {
            semantics = SqlListFragmentRenderer.semanticLineFragments(sql, mode);
            entry.setCachedListLineFragments(semantics);
            entry.setCachedListLineFragmentsMode(mode);
            entry.setCachedListLineFragmentsMasked(maskOn);
        }
        return SqlEntryPresentation.visibleSqlLines(SqlListFragmentRenderer.projectLineFragments(semantics), mode);
    }
}
