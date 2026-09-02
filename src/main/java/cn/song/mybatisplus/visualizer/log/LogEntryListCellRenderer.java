package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.util.Map;

/**
 * 日志列表条目单元格渲染器（F-44-2 重写）：HTML 通道恢复 SQL 高亮 + 耗时 + 时间戳 + 去重 ⨉N 徽章。
 * 抽为具名 internal 类（原匿名 object），便于单元测试直接渲染各种条目状态、锁定渲染崩溃/配色缺陷。
 */
public class LogEntryListCellRenderer extends DefaultListCellRenderer {

    /** 去重丢弃计数快照（徽章渲染数据源；由 [SqlLogPanel.refreshList] 更新）。 */
    public Map<String, Integer> dedupeCounts = Map.of();

    @Override
    public Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
    ) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        LogSqlEntry entry = value instanceof LogSqlEntry ? (LogSqlEntry) value : null;
        setText(null);
        if (entry != null) {
            String timestampHtml = SqlLogPanelKt.formatTimestampHtml(entry.timestamp());
            String durationHtml = PluginSettings.getInstance().getState().getShowDurationInLogList()
                    ? SqlLogPanelKt.formatDurationHtml(entry.executionTimeMs(), isSelected) : "";
            String sqlHtml = SqlListHtmlRenderer.render(SqlLogPanelKt.withTerminator(entry.summary()));
            Integer cnt = dedupeCounts.get(entry.restoredSql());
            String dedupeBadge = (cnt != null && cnt > 0) ? " ⨉" + cnt + " " : "";
            this.setText("<html>" + timestampHtml + durationHtml + sqlHtml + dedupeBadge + "</html>");
            setToolTipText(entry.restoredSql());
            setForeground(isSelected
                    ? (list != null ? list.getSelectionForeground() : JBColor.foreground())
                    : (list != null ? list.getForeground() : JBColor.foreground()));
        }
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                new EmptyBorder(5, 8, 5, 8)
        ));
        return this;
    }
}
