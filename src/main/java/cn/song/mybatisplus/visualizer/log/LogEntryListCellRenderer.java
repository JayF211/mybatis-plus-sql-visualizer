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
import java.util.WeakHashMap;

/**
 * 日志列表条目单元格渲染器（F-44-2 重写）：HTML 通道恢复 SQL 高亮 + 耗时 + 时间戳 + 去重 ⨉N 徽章。
 * 抽为具名 internal 类（原匿名 object），便于单元测试直接渲染各种条目状态、锁定渲染崩溃/配色缺陷。
 *
 * T-LogListFreeze-02：完整列表 HTML 惰性缓存（弱引用键），避免每次 layout pass 重复走
 * SqlListHtmlRenderer + BasicHTML 解析；{@link SqlLogPanel} 刷新列表时调用
 * {@link #invalidateListHtmlCache()} 整体失效。
 */
public class LogEntryListCellRenderer extends DefaultListCellRenderer {

    /** 去重丢弃计数快照（徽章渲染数据源；由 [SqlLogPanel.refreshList] 更新）。 */
    public Map<String, Integer> dedupeCounts = Map.of();

    /** 列表 HTML 缓存（条目为弱键；实例级——面板与测试各自独立，refreshList 时整体清空）。 */
    private final Map<LogSqlEntry, String> listHtmlCache = new WeakHashMap<>();

    /** T-LogListFreeze-02：清空全部条目的列表 HTML 缓存（过滤/排序/设置变更后需重新生成）。 */
    public void invalidateListHtmlCache() {
        listHtmlCache.clear();
    }

    /** 测试缝：读取条目当前缓存的列表 HTML（null = 未计算/已失效）。 */
    public String cachedListHtmlForTest(LogSqlEntry entry) {
        return listHtmlCache.get(entry);
    }

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
            String html = listHtmlCache.get(entry);
            if (html == null) {
                String timestampHtml = SqlLogPanelKt.formatTimestampHtml(entry.timestamp());
                String durationHtml = PluginSettings.getInstance().getState().getShowDurationInLogList()
                        ? SqlLogPanelKt.formatDurationHtml(entry.executionTimeMs(), isSelected) : "";
                String sourceHtml = formatSourceHtml(entry, isSelected);
                String sqlHtml = SqlListHtmlRenderer.render(SqlLogPanelKt.withTerminator(entry.summary()));
                Integer cnt = dedupeCounts.get(entry.restoredSql());
                String dedupeBadge = (cnt != null && cnt > 0) ? " ⨉" + cnt + " " : "";
                html = "<html>" + timestampHtml + durationHtml + sourceHtml + sqlHtml + dedupeBadge + "</html>";
                listHtmlCache.put(entry, html);
            }
            this.setText(html);
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

    /** F-44-7：格式化来源为 HTML 片段（用于列表渲染）。 */
    private static String formatSourceHtml(LogSqlEntry entry, boolean isSelected) {
        String source;
        if (SourceMarkers.isMpPaginationCount(entry.sourceFile())) {
            source = "MP 分页计数";
        } else if (entry.sourceFile() != null) {
            // F-44-7：标记路径始终使用正斜杠（与 CallerSourceLocator 一致）
            String file = entry.sourceFile();
            String name = file.substring(file.lastIndexOf('/') + 1);
            source = entry.sourceLine() > 0 ? name + ":" + entry.sourceLine() : name;
        } else {
            return "";
        }
        String color = isSelected ? "color:#FFFFFF" : "color:#9E9E9E";
        return "<span style='" + color + "'>[" + source + "]</span> ";
    }
}
