package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.Dimension;
import java.util.List;

/**
 * 多 SQL 选择弹窗（T-M23.7/8；T-M25-01 泛化支持 XML 语句候选）。
 *
 * 选择代码块后右键、选择区内含**多个** SQL 候选时，弹出列表让用户选择要预览哪一个。
 */
public class SqlSelectionDialog<T> extends DialogWrapper {

    /** 选中确定回调。 */
    public interface SelectCallback<T> {
        void select(T value);
    }

    private final List<T> candidates;
    private final JList<String> list;
    private final SelectCallback<T> onSelect;

    public SqlSelectionDialog(@NotNull Project project,
                              @NotNull List<T> candidates,
                              @NotNull List<String> displayTexts,
                              @NotNull SelectCallback<T> onSelect) {
        super(project, true);
        this.candidates = candidates;
        this.onSelect = onSelect;
        this.list = new JList<>(displayTexts.toArray(new String[0]));
        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setTitle(MpSqlVisualizerBundle.message("dialog.selectSql.title"));
        this.list.setSelectedIndex(0);
        init();
    }

    @NotNull
    @Override
    protected JComponent createCenterPanel() {
        JScrollPane pane = new JScrollPane(list);
        pane.setPreferredSize(new Dimension(DIALOG_WIDTH, DIALOG_HEIGHT));
        pane.setMinimumSize(new Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT));
        return pane;
    }

    @Override
    protected void doOKAction() {
        int selected = list.getSelectedIndex();
        if (selected >= 0 && selected < candidates.size()) {
            onSelect.select(candidates.get(selected));
        }
        super.doOKAction();
    }

    /** internal 测试访问器（断言渲染文本与预计算一致）。 */
    public JList<String> listForTest() {
        return list;
    }

    public static final int DIALOG_WIDTH = 560;
    public static final int DIALOG_HEIGHT = 220;
    public static final int DIALOG_MIN_WIDTH = 420;
    public static final int DIALOG_MIN_HEIGHT = 120;

    /** 代码行预览最大长度（字符）。 */
    public static final int MAX_LINE_LENGTH = 80;

    /**
     * 列表项显示文本（纯函数，可单测）——两行 HTML。
     */
    public static String displayText(String summary, String lineText) {
        String core = lineText.length() > MAX_LINE_LENGTH
                ? lineText.substring(0, MAX_LINE_LENGTH) + "…" : lineText;
        String escaped = core.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html>" + summary + "<br>&nbsp;&nbsp;&nbsp;" + escaped + "</html>";
    }
}
