package cn.song.mybatisplus.visualizer.share.ui;
import com.intellij.openapi.editor.Editor;

import cn.song.mybatisplus.visualizer.share.SqlShareManager;
import cn.song.mybatisplus.visualizer.share.format.DiagnosticReportFormatter;
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
 * <p>提供七种分享方式：
 * <ul>
 *   <li>复制为 Markdown 代码块</li>
 *   <li>复制为 Markdown 表格</li>
 *   <li>复制为可执行 SQL</li>
 *   <li>导出 HTML 报告文件</li>
 *   <li>R50：复制诊断报告（Markdown，五段聚合：SQL+参数/perf/EXPLAIN 建议/无界写/回滚预案）</li>
 *   <li>R50：导出诊断报告（HTML）</li>
 *   <li>R94：投影诊断草稿，逐段审阅后显式同意反馈</li>
 * </ul>
 * 用户可填写来源位置（文件名:行号），附到分享内容中。
 */
public class ShareDialog extends DialogWrapper {

    private final JBTextField sourceField = new JBTextField(30);
    private final JRadioButton markdownRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.markdown"));
    private final JRadioButton tableRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.markdownTable"));
    private final JRadioButton executableRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.executableSql"));
    private final JRadioButton htmlRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.html"));
    // R50：诊断报告两档（Markdown 走剪贴板 / HTML 走文件——与既有分享两通道同形）
    private final JRadioButton diagnosticRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.diagnostic"));
    private final JRadioButton diagnosticHtmlRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.diagnosticHtml"));
    private final JRadioButton diagnosticFeedbackRadio = new JRadioButton(MpSqlVisualizerBundle.message("share.dialog.diagnosticFeedback"));

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
        group.add(diagnosticRadio);
        group.add(diagnosticHtmlRadio);
        group.add(diagnosticFeedbackRadio);
        markdownRadio.setSelected(true);

        JBPanel<JBPanel<?>> optionsPanel = new JBPanel<JBPanel<?>>(new GridLayout(7, 1, 0, 4));
        optionsPanel.add(markdownRadio);
        optionsPanel.add(tableRadio);
        optionsPanel.add(executableRadio);
        optionsPanel.add(htmlRadio);
        optionsPanel.add(diagnosticRadio);
        optionsPanel.add(diagnosticHtmlRadio);
        optionsPanel.add(diagnosticFeedbackRadio);
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
        } else if (diagnosticRadio.isSelected()) {
            copyDiagnosticReport(src);
        } else if (diagnosticHtmlRadio.isSelected()) {
            exportDiagnosticHtml(src);
        } else if (diagnosticFeedbackRadio.isSelected()) {
            String sql = panel.editorDocumentText();
            if (sql == null || sql.isBlank()) {
                return;
            }
            cn.song.mybatisplus.visualizer.feedback.FeedbackReporting.openDiagnosticDialog(
                    project, feedbackDraft(sql));
        }
        super.doOKAction();
    }

    /** R94：诊断反馈草稿（两段：摘要 + 严格投影后的 SQL）。 */
    public cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft feedbackDraft(@NotNull String sql) {
        cn.song.mybatisplus.visualizer.share.ShareContentProjection.SqlProjection projected =
                cn.song.mybatisplus.visualizer.share.ShareContentProjection.sql(sql);
        return new cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft(java.util.List.of(
                new cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft.Section(
                        "summary", MpSqlVisualizerBundle.message("share.review.summary"),
                        MpSqlVisualizerBundle.message("share.review.summaryText")),
                new cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft.Section(
                        "sql", MpSqlVisualizerBundle.message("share.review.sql"),
                        projected.sql() != null ? projected.sql()
                                : MpSqlVisualizerBundle.message("share.review.omitted"))));
    }

    /** R50：诊断报告输入（各诊断段由纯分析器聚合，IDE 侧只取 SQL/参数/方言/来源）。 */
    private DiagnosticReportFormatter.Input diagnosticInput(@Nullable String source) {
        String sql = panel.editorDocumentText();
        if (sql == null || sql.isBlank()) {
            return null;
        }
        return DiagnosticReportFormatter.buildInput(
                sql,
                panel.currentDialect(),
                panel.unresolvedParams(),
                panel.paramValuesSnapshot(),
                panel.currentTableName(),
                source);
    }

    /** R50：复制诊断报告（Markdown）到剪贴板。 */
    private void copyDiagnosticReport(@Nullable String source) {
        DiagnosticReportFormatter.Input input = diagnosticInput(source);
        if (input == null) {
            return;
        }
        cn.song.mybatisplus.visualizer.util.UiTextClipboard.copy(DiagnosticReportFormatter.format(input));
    }

    /** R50：导出诊断报告（HTML 文件，复用既有 HTML 落盘链）。 */
    private void exportDiagnosticHtml(@Nullable String source) {
        DiagnosticReportFormatter.Input input = diagnosticInput(source);
        if (input == null) {
            return;
        }
        String html = DiagnosticReportFormatter.formatHtml(input);
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                MpSqlVisualizerBundle.message("share.dialog.diagnosticHtml"),
                "Save diagnostic report",
                "html");
        FileSaverDialog dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);
        com.intellij.openapi.vfs.VirtualFileWrapper saved =
                dialog.save((com.intellij.openapi.vfs.VirtualFile) null, "sql-diagnostic-report.html");
        if (saved != null) {
            File file = saved.getFile();
            shareManager.saveHtmlReport(html, file.getAbsolutePath());
            BrowserUtil.open(file.toURI().toString());
        }
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
        com.intellij.openapi.vfs.VirtualFileWrapper saved = dialog.save((com.intellij.openapi.vfs.VirtualFile) null, "sql-report.html");
        if (saved != null) {
            File file = saved.getFile();
            shareManager.saveHtmlReport(html, file.getAbsolutePath());
            BrowserUtil.open(file.toURI().toString());
        }
    }
}
