package cn.song.mybatisplus.visualizer.log;

import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;

import javax.swing.BorderFactory;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 二级 SQL 列表单元格渲染器（F-44-2；T-LogListFreeze-04 弃 HTML 通道）：
 * 双 {@link SimpleColoredComponent} 两行结构（T-TwoLine 语义保留）：元信息行在上（[耗时] [时间戳] [来源]，
 * 均灰 0x9E9E9E 以与 SQL 视觉分层），SQL 高亮行在下（SqlListFragmentRenderer 分词着色）。
 * 原 JLabel-HTML 通道每次 setText 触发 BasicHTML 解析排版 + 可变行高逐条测量 = 列表卡顿根因。
 * internal 供 UI 测试断言各种条目状态（空耗时/空源码/特殊字符/超长 SQL）下的渲染健壮性。
 */
public class HistoryCellRenderer extends JPanel implements javax.swing.ListCellRenderer<Object> {

    /** 历史面板元信息灰（与原 HTML 通道 #9E9E9E 一致）。 */
    private static final SimpleTextAttributes HISTORY_META_GRAY =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new Color(0x9E9E9E));

    /** 元信息行 / SQL 行（internal 供 UI 测试遍历片段断言）。 */
    public final SimpleColoredComponent metaLine = new SimpleColoredComponent();
    public final SimpleColoredComponent sqlLine = new SimpleColoredComponent();

    public HistoryCellRenderer() {
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
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
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
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
    ) {
        setBackground(isSelected && list != null ? list.getSelectionBackground() : (list != null ? list.getBackground() : null));
        Color fg = isSelected
                ? (list != null && list.getSelectionForeground() != null ? list.getSelectionForeground() : JBColor.foreground())
                : (list != null ? list.getForeground() : JBColor.foreground());
        metaLine.setForeground(fg);
        sqlLine.setForeground(fg);
        metaLine.clear();
        sqlLine.clear();
        LogSqlEntry entry = value instanceof LogSqlEntry ? (LogSqlEntry) value : null;
        if (entry != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(new Date(entry.timestamp()));
            String duration = entry.executionTimeMs() != null ? entry.executionTimeMs() + "ms" : "—";
            String source;
            if (SourceMarkers.isMpPaginationCount(entry.sourceFile())) {
                source = "MP 分页计数";
            } else if (entry.sourceFile() != null) {
                // F-44-7：标记路径始终使用正斜杠（与 CallerSourceLocator 一致），不能用 File.separatorChar
                String file = entry.sourceFile();
                int sep = file.lastIndexOf('/');
                String name = sep >= 0 ? file.substring(sep + 1) : file;
                source = entry.sourceLine() > 0 ? name + ":" + entry.sourceLine() : name;
            } else {
                source = "unknown";
            }
            // T-TwoLine：元信息行在上（uniform 灰——不按耗时变色避免与 SQL 高亮冲突），SQL 高亮行在下
            metaLine.append("[" + duration + "] ", HISTORY_META_GRAY);
            metaLine.append("[" + timestamp + "] ", HISTORY_META_GRAY);
            metaLine.append("[" + source + "] ", HISTORY_META_GRAY);
            for (SqlTextFragment f : SqlListFragmentRenderer.renderFragments(SqlLogPanelKt.withTerminator(entry.summary()))) {
                sqlLine.append(f.text(), f.attributes());
            }
        }
        // T-ClickPreview：不设 toolTipText（悬停逐行弹气泡 = 条目多时卡顿根因），预览改单击触发
        return this;
    }
}
