package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
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

    /** 元信息行 / SQL 行（internal 供 UI 测试遍历片段断言文本与着色）。 */
    public final SimpleColoredComponent metaLine = new SimpleColoredComponent();
    public final SimpleColoredComponent sqlLine = new SimpleColoredComponent();

    public LogEntryListCellRenderer() {
        super(new BorderLayout());
        setOpaque(true);
        metaLine.setOpaque(false);
        sqlLine.setOpaque(false);
        JPanel twoLines = new JPanel(new GridLayout(2, 1));
        twoLines.setOpaque(false);
        twoLines.add(metaLine);
        twoLines.add(sqlLine);
        add(twoLines, BorderLayout.CENTER);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                new javax.swing.border.EmptyBorder(5, 8, 5, 8)
        ));
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
        setBackground(isSelected && list != null ? list.getSelectionBackground() : (list != null ? list.getBackground() : null));
        java.awt.Color fg = isSelected
                ? (list != null && list.getSelectionForeground() != null ? list.getSelectionForeground() : JBColor.foreground())
                : (list != null ? list.getForeground() : JBColor.foreground());
        metaLine.setForeground(fg);
        sqlLine.setForeground(fg);
        metaLine.clear();
        sqlLine.clear();
        LogSqlEntry entry = value instanceof LogSqlEntry ? (LogSqlEntry) value : null;
        if (entry != null) {
            // T-TwoLine：元信息行（时间戳/耗时/来源/去重徽章）在上，段间距由尾随空格承担
            kotlin.Pair<String, SimpleTextAttributes> ts = SqlLogPanelKt.formatTimestampFragment(entry.timestamp());
            if (ts != null) {
                metaLine.append(ts.getFirst(), ts.getSecond());
            }
            if (PluginSettings.getInstance().getState().getShowDurationInLogList()) {
                kotlin.Pair<String, SimpleTextAttributes> dur = SqlLogPanelKt.formatDurationFragment(entry.executionTimeMs(), isSelected);
                metaLine.append(dur.getFirst(), dur.getSecond());
            }
            kotlin.Pair<String, SimpleTextAttributes> src = SqlLogPanelKt.formatSourceFragment(entry, isSelected);
            if (src != null) {
                metaLine.append(src.getFirst(), src.getSecond());
            }
            Integer cnt = entry != null ? dedupeCounts.get(entry.dedupeKey()) : null;
            if (cnt != null && cnt > 0) {
                metaLine.append("⨉" + cnt + " ", SqlLogPanelKt.LIST_META_GRAY);
            }
            // SQL 高亮行在下：选中态保留 SQL 高亮原色（与原 HTML 行为一致）
            for (SqlTextFragment f : SqlListFragmentRenderer.renderFragments(SqlLogPanelKt.withTerminator(entry.summary()))) {
                sqlLine.append(f.text(), f.attributes());
            }
        }
        // T-ClickPreview：不设 toolTipText（悬停逐行弹气泡 = 条目多时卡顿根因），预览改单击触发
        return this;
    }
}
