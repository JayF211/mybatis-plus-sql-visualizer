package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Component;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 二级 SQL 列表单元格渲染器（F-44-2）：耗时 + 执行时间 + 源码位置 + SQL 摘要。
 * SQL 摘要走 [SqlListHtmlRenderer] 高亮（关键字/表名/字符串/数字分色）；
 * 耗时/时间戳/源码走灰色 `#9E9E9E` 以与 SQL 视觉分层。
 * internal 供 UI 测试断言各种条目状态（空耗时/空源码/特殊字符/超长 SQL）下的渲染健壮性。
 */
public class HistoryCellRenderer extends DefaultListCellRenderer {

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
        if (entry != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(new Date(entry.timestamp()));
            String duration = entry.executionTimeMs() != null ? entry.executionTimeMs() + "ms" : "—";
            String source;
            if (entry.sourceFile() != null) {
                String file = entry.sourceFile();
                int sep = file.lastIndexOf(File.separatorChar);
                String name = sep >= 0 ? file.substring(sep + 1) : file;
                source = entry.sourceLine() > 0 ? name + ":" + entry.sourceLine() : name;
            } else {
                source = "unknown";
            }
            String sqlHtml = SqlListHtmlRenderer.render(entry.summary());
            // 选中态用 selectionForeground；非选中走 uniform 前景色（不要按耗时变色避免与 SQL 高亮冲突）
            String durationHtml = "<span style='color:#9E9E9E'>[" + duration + "]</span> ";
            String timestampHtml = "<span style='color:#9E9E9E'>[" + timestamp + "]</span> ";
            String sourceHtml = "<span style='color:#9E9E9E'>[" + source + "]</span> ";
            this.setText("<html>" + durationHtml + timestampHtml + sourceHtml + sqlHtml + "</html>");
            setToolTipText(entry.restoredSql());
            setForeground(isSelected
                    ? (list != null ? list.getSelectionForeground() : JBColor.foreground())
                    : (list != null ? list.getForeground() : JBColor.foreground()));
        }
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
        ));
        return this;
    }
}
