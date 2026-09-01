package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Component;
import java.io.File;
import java.util.Locale;

/**
 * 一级文件列表单元格渲染器（F-44-3）：文件名（加粗）+ 文件大小 | SQL 条数（灰）。
 * internal 供 UI 测试断言文件名/大小/条数渲染与文件大小格式化边界。
 */
public class HistoryFileCellRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
    ) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        HistoryFileEntry fe = value instanceof HistoryFileEntry ? (HistoryFileEntry) value : null;
        if (fe != null) {
            String size = formatFileSize(fe.getSizeBytes());
            String sqlCount = MpSqlVisualizerBundle.message("log.history.sqlCount", Integer.toString(fe.sqlCount()));
            this.setText("<html><b>" + escapeHtml(fe.getDisplayName()) + "</b>  " +
                    "<span style='color:#9E9E9E'>" + size + " | " + sqlCount + "</span></html>");
            setToolTipText(fe.file().getAbsolutePath());
            setIcon(AllIcons.FileTypes.Text);
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

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String formatFileSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ENGLISH, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
