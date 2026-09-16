package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
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

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
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

    /** T-Perf-14：大日志后台转换阈值——字符数（约 1MB）。 */
    private static final int HEAVY_INPUT_CHARS = 1_000_000;
    /** T-Perf-14：大日志后台转换阈值——行数（约 1000 行）。 */
    private static final int HEAVY_INPUT_LINES = 1_000;

    private final Project project;
    private final Editor inputEditor;
    private final Editor resultEditor;
    private final JBLabel statusLabel;

    /**
     * T-UiNative T-UN-14：空态提示接编辑器原生 placeholder（两区锚定物均为 EditorFactory
     * 直建编辑器，实际类型 EditorEx——243 javap 核实有 {@code setPlaceholder(CharSequence)}），
     * 显隐由编辑器随空文档/焦点原生管理；原自摆 inputHintLabel/resultHintLabel 删除，
     * 文案键（"log.panel.hint.input" / "log.panel.hint.result"）沿用不变。
     */
    private final String inputPlaceholder = MpSqlVisualizerBundle.message("log.panel.hint.input");
    private final String resultPlaceholder = MpSqlVisualizerBundle.message("log.panel.hint.result");

    /** 输出格式切换态：true=多行格式化（默认，用户确认）；false=日志原样压缩。 */
    private boolean formatToggled = true;

    /** T-Perf-14：后台转换进行中标志（防重入）。 */
    private final java.util.concurrent.atomic.AtomicBoolean converting = new java.util.concurrent.atomic.AtomicBoolean(false);

    private boolean disposed = false;

    public LogToSqlPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.statusLabel = new JBLabel(" ");
        this.statusLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        this.inputEditor = createInputEditor();
        this.resultEditor = createResultEditor();

        // 上：日志输入区（工具栏 + 编辑器——空文档由编辑器原生 placeholder 显示引导文案）
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.add(buildInputToolbar(), BorderLayout.NORTH);
        inputPanel.add(inputEditor.getComponent(), BorderLayout.CENTER);

        // 下：SQL 结果区（工具栏 + 编辑器）
        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.add(buildResultToolbar(), BorderLayout.NORTH);
        resultPanel.add(resultEditor.getComponent(), BorderLayout.CENTER);

        Splitter splitter = new Splitter(true, 0.5f);
        splitter.setFirstComponent(inputPanel);
        splitter.setSecondComponent(resultPanel);
        splitter.setShowDividerControls(false);
        add(splitter, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
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
            UiTextClipboard.copy(resultEditor.getDocument().getText());
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
            // T-AutoFold F4-c：清空后同步清除折叠区域
            cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(resultEditor, "");
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
     * 一段混合日志（同时含多种格式）也能正确转换。
     *
     * T-Perf-14：大日志（超过 {@link #HEAVY_INPUT_CHARS} / {@link #HEAVY_INPUT_LINES} 阈值，粘贴数 MB 日志场景）
     * 解析 + 逐条格式化移 {@link com.intellij.openapi.progress.Task.Backgroundable} 后台 + 进度，EDT 不再全量解析冻结；
     * 小输入保持同步（即时反馈）。防重入：后台转换进行中忽略再次触发。
     */
    private void convert() {
        String text = inputEditor.getDocument().getText();
        if (text.isBlank()) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.empty"), true);
            return;
        }
        // 前缀/混合/截断与自动捕获同源（每次转换即时读取，改设置立即生效）
        Parsers parsers = buildParsers();
        int lineCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineCount++;
            }
        }
        boolean heavy = text.length() > HEAVY_INPUT_CHARS || lineCount >= HEAVY_INPUT_LINES;
        if (!heavy) {
            applyConversion(computeConversion(text, parsers));
            return;
        }
        if (!converting.compareAndSet(false, true)) {
            return;
        }
        status(MpSqlVisualizerBundle.message("logToSql.status.converting"), false);
        new com.intellij.openapi.progress.Task.Backgroundable(
                project,
                MpSqlVisualizerBundle.message("logToSql.panel.convertingTask"),
                true) {
            @Override
            public void run(com.intellij.openapi.progress.ProgressIndicator indicator) {
                final ConversionOutput out = computeConversion(text, parsers);
                ApplicationManager.getApplication().invokeLater(() -> {
                    converting.set(false);
                    if (project.isDisposed()) {
                        return;
                    }
                    applyConversion(out);
                });
            }
        }.queue();
    }

    /** 三解析器装配（转换配置每次转换即时读取）。 */
    private Parsers buildParsers() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        return new Parsers(
                new MyBatisLogParser(new LogSqlRestorer(),
                        state.getLogSqlPrefix(), state.getLogParamPrefix(), state.getLogMixedPrefix(), state.getLogMaxParseLength()),
                new HibernateLogParser(new LogSqlRestorer(),
                        state.getLogHibernateSqlPrefix(), state.getLogHibernateParamPrefix(), state.getLogMixedPrefix(),
                        state.getLogMaxParseLength(), true),
                new JdbcTemplateLogParser(new LogSqlRestorer(), state.getLogJdbcEnabled(), state.getLogMaxParseLength()));
    }

    /** 纯计算：逐行喂三解析器 + 逐条还原/格式化（后台线程安全——不触碰 Swing）。 */
    private ConversionOutput computeConversion(String text, Parsers parsers) {
        MyBatisLogParser myBatisParser = parsers.myBatis();
        HibernateLogParser hibernateParser = parsers.hibernate();
        JdbcTemplateLogParser jdbcParser = parsers.jdbc();
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
            return new ConversionOutput(null, 0, 0);
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
        // T-HibDualCapture：string-aware 计数，字符串/注释里的 `?` 不再误计
        long unresolved = entries.stream().filter(e -> LogEntryPreviewRecognizerKt.countPlaceholders(e.restoredSql()) > 0).count();
        return new ConversionOutput(out.toString(), entries.size(), (int) unresolved);
    }

    /** 应用转换结果（EDT）：写结果区 + 状态行。 */
    private void applyConversion(ConversionOutput out) {
        if (out.text() == null) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), true);
            return;
        }
        setDocumentText(resultEditor, out.text());
        // T-AutoFold F4-c：转换产出后按当前设置重建结果区折叠（关闭态零操作）。
        // 随 dev 的计算/应用拆分从 computeConversion（后台线程）移到此处（EDT），写后即折。
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(resultEditor, out.text());
        if (out.unresolved() > 0) {
            status(MpSqlVisualizerBundle.message("logToSql.status.unresolved", out.unresolved()), true);
        } else {
            status(MpSqlVisualizerBundle.message("logToSql.status.converted", out.entriesCount()), false);
        }
    }

    /** 转换结果（null text = 无条目）。 */
    private record ConversionOutput(String text, int entriesCount, int unresolved) {
    }

    /** 三解析器三元组（Kotlin Triple 的 Java 承载）。 */
    private record Parsers(MyBatisLogParser myBatis, HibernateLogParser hibernate, JdbcTemplateLogParser jdbc) {
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
        ex.setPlaceholder(inputPlaceholder); // T-UiNative T-UN-14：空态提示原生件
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
        ex.setPlaceholder(resultPlaceholder); // T-UiNative T-UN-14：空态提示原生件
        return editor;
    }

    /** T-UiNative T-UN-14：输入区空态提示（原 hintLabel 测试缝，断言 placeholder 文案接线用）。 */
    String inputPlaceholderForTest() {
        return inputPlaceholder;
    }

    /** T-UiNative T-UN-14：结果区空态提示。 */
    String resultPlaceholderForTest() {
        return resultPlaceholder;
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

    /** T-AutoFold F4-c：结果区编辑器（折叠区域断言用）。 */
    Editor resultEditorForTest() {
        return resultEditor;
    }

    String statusForTest() {
        return statusLabel.getText();
    }

    void loadFromEntryForTest(LogSqlEntry entry) {
        loadFromEntry(entry);
    }
}
