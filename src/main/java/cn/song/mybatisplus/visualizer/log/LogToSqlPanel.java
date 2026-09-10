package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * 「日志转 SQL」tab 面板（T-M38）：粘贴 MyBatis 日志（`Preparing:` / `Parameters:` 行）→
 * 批量还原为可执行 SQL。复用 {@link MyBatisLogParser} 配对与 {@link LogSqlRestorer} 还原（与自动捕获同链路，
 * 前缀/混合/截断配置同源读取设置）；一段日志含多组语句时全部转换，组间 `-- SQL #N` 注释分隔。
 */
public class LogToSqlPanel extends JPanel implements Disposable {

    private static final String TOOLBAR_ID_INPUT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.input";
    private static final String TOOLBAR_ID_RESULT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.result";

    private final Project project;
    private final Editor inputEditor;
    private final Editor resultEditor;
    private final JBLabel statusLabel;
    private final JBLabel inputHintLabel;
    private final JBLabel resultHintLabel;

    /** 输出格式切换态：true=多行格式化（默认，用户确认）；false=日志原样压缩。 */
    private boolean formatToggled = true;

    private boolean disposed = false;

    public LogToSqlPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.statusLabel = new JBLabel(" ");
        this.statusLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        this.inputHintLabel = new JBLabel(MpSqlVisualizerBundle.message("log.panel.hint.input"));
        this.inputHintLabel.setForeground(UIUtil.getInactiveTextColor());
        this.inputHintLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        this.resultHintLabel = new JBLabel(MpSqlVisualizerBundle.message("log.panel.hint.result"));
        this.resultHintLabel.setForeground(UIUtil.getInactiveTextColor());
        this.resultHintLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        this.inputEditor = createInputEditor();
        this.resultEditor = createResultEditor();

        // 上：日志输入区（工具栏 + 灰字提示 + 编辑器——空文档显示引导文案）
        JPanel inputPanel = new JPanel(new BorderLayout());
        JPanel inputTop = new JPanel(new BorderLayout());
        inputTop.add(buildInputToolbar(), BorderLayout.NORTH);
        inputTop.add(inputHintLabel, BorderLayout.SOUTH);
        inputPanel.add(inputTop, BorderLayout.NORTH);
        inputPanel.add(inputEditor.getComponent(), BorderLayout.CENTER);

        // 下：SQL 结果区（工具栏 + 灰字提示 + 编辑器）
        JPanel resultPanel = new JPanel(new BorderLayout());
        JPanel resultTop = new JPanel(new BorderLayout());
        resultTop.add(buildResultToolbar(), BorderLayout.NORTH);
        resultTop.add(resultHintLabel, BorderLayout.SOUTH);
        resultPanel.add(resultTop, BorderLayout.NORTH);
        resultPanel.add(resultEditor.getComponent(), BorderLayout.CENTER);

        Splitter splitter = new Splitter(true, 0.5f);
        splitter.setFirstComponent(inputPanel);
        splitter.setSecondComponent(resultPanel);
        splitter.setShowDividerControls(false);
        add(splitter, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        // 空态提示显隐跟随文档内容
        inputEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void beforeDocumentChange(DocumentEvent e) {
            }

            @Override
            public void documentChanged(DocumentEvent e) {
                inputHintLabel.setVisible(inputEditor.getDocument().getText().isBlank());
            }
        }, this);
        resultEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void beforeDocumentChange(DocumentEvent e) {
            }

            @Override
            public void documentChanged(DocumentEvent e) {
                resultHintLabel.setVisible(resultEditor.getDocument().getText().isBlank());
            }
        }, this);
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private JPanel buildInputToolbar() {
        DefaultActionGroup group = new DefaultActionGroup(new ConvertAction(), new ClearInputAction());
        com.intellij.openapi.actionSystem.ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(TOOLBAR_ID_INPUT, group, true);
        toolbar.setTargetComponent(this);
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        panel.add(toolbar.getComponent());
        return panel;
    }

    private JPanel buildResultToolbar() {
        DefaultActionGroup group = new DefaultActionGroup(new CopyAction(), new ToggleFormatAction(), new ClearResultAction());
        com.intellij.openapi.actionSystem.ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(TOOLBAR_ID_RESULT, group, true);
        toolbar.setTargetComponent(this);
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        panel.add(toolbar.getComponent());
        return panel;
    }

    private class ConvertAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Execute);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.convert"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            convert();
        }
    }

    private class ClearInputAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.GC);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.clearInput"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            setDocumentText(inputEditor, "");
        }
    }

    private class CopyAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Copy);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.copy"));
            e.getPresentation().setEnabled(!resultEditor.getDocument().getText().isBlank());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(resultEditor.getDocument().getText()), null);
            status(MpSqlVisualizerBundle.message("logToSql.copy.done"), false);
        }
    }

    /** 压缩/多行切换：立即用当前输入重转（空输入不动作）。 */
    private class ToggleFormatAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.ReformatCode);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.toggleFormat"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            formatToggled = !formatToggled;
            if (!inputEditor.getDocument().getText().isBlank()) {
                convert();
            }
        }
    }

    private class ClearResultAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.GC);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.clearResult"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            setDocumentText(resultEditor, "");
            status(" ", false);
        }
    }

    // ------------------------------------------------------------------
    // 转换流程
    // ------------------------------------------------------------------

    /**
     * 逐行解析输入日志 → 批量还原（每组 withTerminator + 格式化/压缩切换）→ 写结果区。
     *
     * 三解析器并列：MyBatis + Hibernate + JdbcTemplate 同时喂行，各自识别匹配的格式，结果合并输出。
     */
    private void convert() {
        String text = inputEditor.getDocument().getText();
        if (text.isBlank()) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.empty"), true);
            return;
        }
        PluginSettings.State state = PluginSettings.getInstance().getState();
        LogSqlRestorer restorer = new LogSqlRestorer();
        MyBatisLogParser myBatisParser = new MyBatisLogParser(restorer,
                state.getLogSqlPrefix(), state.getLogParamPrefix(), state.getLogMixedPrefix(), state.getLogMaxParseLength());
        HibernateLogParser hibernateParser = new HibernateLogParser(restorer,
                state.getLogHibernateSqlPrefix(), state.getLogHibernateParamPrefix(), state.getLogMixedPrefix(), state.getLogMaxParseLength(), true);
        JdbcTemplateLogParser jdbcParser = new JdbcTemplateLogParser(restorer, state.getLogJdbcEnabled(), state.getLogMaxParseLength());
        for (String line : text.split("\n", -1)) {
            myBatisParser.feed(line);
            hibernateParser.feed(line);
            jdbcParser.feed(line);
        }
        myBatisParser.closePending();
        hibernateParser.closePending();
        jdbcParser.closePending();
        List<LogSqlEntry> entries = new ArrayList<>();
        entries.addAll(myBatisParser.takeEntries());
        entries.addAll(hibernateParser.takeEntries());
        entries.addAll(jdbcParser.takeEntries());
        if (entries.isEmpty()) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), true);
            return;
        }
        boolean multi = entries.size() > 1;
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            LogSqlEntry entry = entries.get(index);
            String base = withTerminator(entry.restoredSql());
            String body = formatToggled ? SqlFormatter.INSTANCE.format(base) : base;
            String piece = multi
                    ? "-- " + MpSqlVisualizerBundle.message("logToSql.status.group", index + 1) + "\n" + body
                    : body;
            if (index > 0) {
                out.append("\n\n");
            }
            out.append(piece);
        }
        setDocumentText(resultEditor, out.toString());
        // T-HibDualCapture：string-aware 计数，字符串/注释里的 `?` 不再误计
        long unresolved = entries.stream().filter(e -> LogEntryPreviewRecognizerKt.countPlaceholders(e.restoredSql()) > 0).count();
        if (unresolved > 0) {
            status(MpSqlVisualizerBundle.message("logToSql.status.unresolved", unresolved), true);
        } else {
            status(MpSqlVisualizerBundle.message("logToSql.status.converted", entries.size()), false);
        }
    }

    /** T-M38：列表「发送到日志转 SQL」落点——条目渲染回日志文本填入输入区并立即转换。 */
    void loadFromEntry(LogSqlEntry entry) {
        setDocumentText(inputEditor, LogSqlModelsKt.toLogText(entry));
        convert();
    }

    /** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复）。 */
    private String withTerminator(String sql) {
        return (sql.isEmpty() || sql.endsWith(";")) ? sql : sql + ";";
    }

    private void setDocumentText(Editor editor, String text) {
        if (editor.getDocument().getText().equals(text)) {
            return;
        }
        WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().setText(text));
    }

    /** 状态行（错误红字 / 提示主题色）。 */
    private void status(String message, boolean error) {
        statusLabel.setForeground(error
                ? JBColor.namedColor("ErrorForeground", JBColor.RED)
                : EditorColorsManager.getInstance().getGlobalScheme().getAttributes(HighlighterColors.TEXT).getForegroundColor());
        statusLabel.setText(message);
    }

    // ------------------------------------------------------------------
    // 编辑器
    // ------------------------------------------------------------------

    /** 日志输入编辑器：普通文本（日志含前缀/时间戳等噪声，SQL 高亮会误导）；行号开。 */
    private Editor createInputEditor() {
        Editor editor = EditorFactory.getInstance()
                .createEditor(EditorFactory.getInstance().createDocument(""), project);
        EditorEx ex = (EditorEx) editor;
        ex.getSettings().setLineNumbersShown(true);
        ex.getSettings().setFoldingOutlineShown(false);
        ex.getSettings().setIndentGuidesShown(false);
        ex.getSettings().setAdditionalPageAtBottom(false);
        return editor;
    }

    /** SQL 结果编辑器：可编辑（微调参数后复制）+ 原生 SQL 语法高亮。 */
    private Editor createResultEditor() {
        Editor editor = EditorFactory.getInstance()
                .createEditor(EditorFactory.getInstance().createDocument(""), project);
        EditorEx ex = (EditorEx) editor;
        com.intellij.openapi.fileTypes.FileType sqlFileType = FileTypeManager.getInstance().getStdFileType("SQL");
        if (sqlFileType != null) {
            SyntaxHighlighter sqlHighlighter = SyntaxHighlighterFactory
                    .getSyntaxHighlighter(sqlFileType, project, null);
            if (sqlHighlighter != null) {
                ex.setHighlighter(new LexerEditorHighlighter(sqlHighlighter, EditorColorsManager.getInstance().getGlobalScheme()));
            }
        }
        ex.getSettings().setLineNumbersShown(true);
        ex.getSettings().setFoldingOutlineShown(true);
        ex.getSettings().setIndentGuidesShown(false);
        ex.getSettings().setAdditionalPageAtBottom(false);
        return editor;
    }

    /** 测试访问器：输入区空态提示行。 */
    JBLabel inputHintLabelForTest() {
        return inputHintLabel;
    }

    /** 测试访问器：结果区空态提示行。 */
    JBLabel resultHintLabelForTest() {
        return resultHintLabel;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        EditorFactory.getInstance().releaseEditor(inputEditor);
        EditorFactory.getInstance().releaseEditor(resultEditor);
    }

    // ------------------------------------------------------------------
    // internal 测试访问器
    // ------------------------------------------------------------------

    void setInputForTest(String text) {
        setDocumentText(inputEditor, text);
    }

    void convertForTest() {
        convert();
    }

    void toggleFormatForTest() {
        formatToggled = !formatToggled;
    }

    String resultTextForTest() {
        return resultEditor.getDocument().getText();
    }

    String statusForTest() {
        return statusLabel.getText();
    }

    void loadFromEntryForTest(LogSqlEntry entry) {
        loadFromEntry(entry);
    }
}
