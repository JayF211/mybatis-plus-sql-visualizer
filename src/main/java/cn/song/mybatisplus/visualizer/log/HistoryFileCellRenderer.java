package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;

import javax.swing.BorderFactory;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.Locale;

/**
 * 一级文件列表单元格渲染器（F-44-3；T-LogListFreeze-04 弃 HTML 通道）：
 * 文件名（加粗）+ 文件大小 | SQL 条数（灰），SimpleColoredComponent 片段渲染。
 * internal 供 UI 测试断言文件名/大小/条数渲染与文件大小格式化边界。
 */
public class HistoryFileCellRenderer extends JPanel implements javax.swing.ListCellRenderer<Object> {

    /** 历史面板元信息灰（与原 HTML 通道 #9E9E9E 一致）。T-UN-13（T-UiNative）：改 namedColor，亮值不变。 */
    private static final SimpleTextAttributes HISTORY_META_GRAY =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.HISTORY_META_GRAY);

    /** 渲染行（internal 供 UI 测试遍历片段断言文本）。 */
    public final SimpleColoredComponent line = new SimpleColoredComponent();

    public HistoryFileCellRenderer() {
        super(new BorderLayout());
        setOpaque(true);
        line.setOpaque(false);
        add(line, BorderLayout.CENTER);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
        ));
    }

    @Override
    public Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
    ) {
        setBackground(isSelected && list != null ? list.getSelectionBackground() : (list != null ? list.getBackground() : null));
        line.setForeground(isSelected
                ? (list != null && list.getSelectionForeground() != null ? list.getSelectionForeground() : JBColor.foreground())
                : (list != null ? list.getForeground() : JBColor.foreground()));
        line.clear();
        HistoryFileEntry fe = value instanceof HistoryFileEntry ? (HistoryFileEntry) value : null;
        if (fe != null) {
            String size = formatFileSize(fe.getSizeBytes());
            String sqlCount = MpSqlVisualizerBundle.message("log.history.sqlCount", Integer.toString(fe.sqlCount()));
            line.setIcon(AllIcons.FileTypes.Text);
            line.append(fe.getDisplayName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            line.append("  " + size + " | " + sqlCount + " ", HISTORY_META_GRAY);
            // 文件条目少、低频，tooltip（完整路径）保留；条目级 tooltip 卡顿根因在二级 SQL 列表（T-ClickPreview）
            setToolTipText(fe.file().getAbsolutePath());
        }
        return this;
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
