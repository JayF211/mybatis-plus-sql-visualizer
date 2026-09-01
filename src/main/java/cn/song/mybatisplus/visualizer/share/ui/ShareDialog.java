package cn.song.mybatisplus.visualizer.share.ui;

import cn.song.mybatisplus.visualizer.share.SqlShareManager;
import cn.song.mybatisplus.visualizer.share.format.HtmlReportFormatter;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.File;

/**
 * 分享选项对话框（F-8）。
 *
 * <p>提供四种分享方式：
 * <ul>
 *   <li>复制为 Markdown 代码块</li>
 *   <li>复制为 Markdown 表格</li>
 *   <li>复制为可执行 SQL</li>
 *   <li>导出 HTML 报告文件</li>
 * </ul>
 * 用户可填写来源位置（文件名:行号），附到分享内容中。
 */
public class ShareDialog extends DialogWrapper {

    private final JBTextField sourceField = new JBTextField(30);
    private final JRadioButton markdownRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.markdown"));
    private final JRadioButton tableRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.markdownTable"));
    private final JRadioButton executableRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.executableSql"));
    private final JRadioButton htmlRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.html"));

    private final SqlShareManager shareManager;
    private final SqlPreviewPanel panel;
    private final Project project;

    public ShareDialog(@Nullable Project project, @NotNull SqlPreviewPanel panel) {
        super(project, true);
        this.project = project;
        this.panel = panel;
        this.shareManager = new SqlShareManager(project);
        setTitle(MpSqlVisualizerBundle.message("share.dialog.title"));
        // F-46：预填充当前代码位置（文件名:行号），用户可编辑覆盖
        String loc = panel.currentSourceLocation();
        if (loc == null) {
            loc = computeCurrentLocation();
        }
        if (loc != null && !loc.isEmpty()) {
            sourceField.setText(loc);
        }
        init();
    }

    /** 从当前编辑器实时计算代码位置（文件名:行号），兜底 panel 未设置 sourceLocation 的场景。 */
    @Nullable
    private String computeCurrentLocation() {
        Project proj = project;
        if (proj == null) {
            return null;
        }
        Editor editor = FileEditorManager.getInstance(proj).getSelectedTextEditor();
        if (editor == null) {
            return null;
        }
        com.intellij.openapi.vfs.VirtualFile virtualFile =
                FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile == null) {
            return null;
        }
        int line = editor.getDocument().getLineNumber(editor.getCaretModel().getOffset()) + 1;
        return virtualFile.getName() + ":" + line;
    }

    @Override
    protected JComponent createCenterPanel() {
        JBPanel<JBPanel<?>> root = new JBPanel<JBPanel<?>>();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(JBUI.Borders.empty(10));

        // 来源输入行
        JBPanel<JBPanel<?>> sourceRow = new JBPanel<JBPanel<?>>(new BorderLayout());
        sourceRow.add(new JBLabel(MpSqlVisualizerBundle.message("share.dialog.source") + ":"), BorderLayout.WEST);
        sourceRow.add(sourceField, BorderLayout.CENTER);
        sourceRow.setBorder(JBUI.Borders.emptyBottom(10));
        root.add(sourceRow);

        // 选项单选
        ButtonGroup group = new ButtonGroup();
        group.add(markdownRadio);
        group.add(tableRadio);
        group.add(executableRadio);
        group.add(htmlRadio);
        markdownRadio.setSelected(true);

        JBPanel<JBPanel<?>> optionsPanel = new JBPanel<JBPanel<?>>(new GridLayout(4, 1, 0, 4));
        optionsPanel.add(markdownRadio);
        optionsPanel.add(tableRadio);
        optionsPanel.add(executableRadio);
        optionsPanel.add(htmlRadio);
        optionsPanel.setBorder(JBUI.Borders.emptyBottom(10));
        root.add(optionsPanel);

        return root;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return sourceField;
    }

    /** 测试缝：返回中心面板（与 {@link #createCenterPanel()} 同构），供测试遍历断言。DialogWrapper 的
     *  rootPane/contentPane 仅在 show() 时构建，headless 下构造即可用本缝取面板。 */
    public JComponent centerPanelForTest() {
        return createCenterPanel();
    }

    @Override
    protected Action[] createActions() {
        getOKAction().putValue(Action.NAME, MpSqlVisualizerBundle.message("dialog.ok"));
        getCancelAction().putValue(Action.NAME, MpSqlVisualizerBundle.message("share.dialog.cancel"));
        return new Action[]{getOKAction(), getCancelAction()};
    }

    @Override
    protected void doOKAction() {
        String source = sourceField.getText().trim();
        String src = source.isEmpty() ? null : source;
        if (markdownRadio.isSelected()) {
            shareManager.copyAsMarkdown(panel, src);
        } else if (tableRadio.isSelected()) {
            shareManager.copyAsMarkdownTable(panel, src);
        } else if (executableRadio.isSelected()) {
            shareManager.copyExecutableSql(panel, src);
        } else if (htmlRadio.isSelected()) {
            exportHtmlReport();
        }
        super.doOKAction();
    }

    /** 导出 HTML 报告（复用 {@link HtmlReportFormatter}）。 */
    private void exportHtmlReport() {
        String sql = panel.editorDocumentText();
        if (sql == null || sql.isBlank()) {
            return;
        }
        // format 的默认参数对 Java 不可见，须按声明顺序全参传入
        String html = HtmlReportFormatter.INSTANCE.format(
                sql,
                panel.unresolvedParams(),
                panel.paramValuesSnapshot(),
                panel.currentTableName(),
                panel.currentDialect());
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                MpSqlVisualizerBundle.message("share.dialog.html"),
                "Save HTML report",
                "html");
        FileSaverDialog dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);
        com.intellij.openapi.vfs.VirtualFile saved = dialog.save((com.intellij.openapi.vfs.VirtualFile) null, "sql-report.html");
        if (saved != null) {
            File file = saved.getFile();
            shareManager.saveHtmlReport(html, file.getAbsolutePath());
            BrowserUtil.open(file.toURI().toString());
        }
    }
}
