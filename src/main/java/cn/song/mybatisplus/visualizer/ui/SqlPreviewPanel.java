package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.SqlExecutor;
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier;
import cn.song.mybatisplus.visualizer.model.BatchColumn;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.model.ConditionView;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.xml.PagesColumn;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.share.ui.ShareDialog;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator;
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction;
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter;
import cn.song.mybatisplus.visualizer.util.CaseFormatUtil;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.ParameterMapping;
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql;
import cn.song.mybatisplus.visualizer.xml.XmlConditionView;
import cn.song.mybatisplus.visualizer.xml.XmlForEachView;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewState;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupEx;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.SlowOperations;
import com.intellij.openapi.application.AccessToken;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL 预览面板（M9-01/02/03 可编辑版，F-07/F-08）。
 *
 * 弹窗 [SqlPreviewDialog] 与工具窗口共用本面板，展示：
 *  - 顶部：元信息（Wrapper 类型 / 表名 / 风险警示）+ 工具栏（复制 / 压缩多行切换 / 刷新）
 *  - 中部左右分栏：左侧**参数值输入框**（`#param` → 值，输入实时回填 SQL），右侧**可编辑 SQL 编辑器**
 *  - SQL 编辑器：`EditorFactory.createEditor`（可写）+ 原生 SQL 语法高亮；文档由原生 SQL FileType PSI
 *    文件支撑，挂 [SqlCompletionContributor] 提供补全（Ctrl+Space）
 */
public class SqlPreviewPanel extends JBPanel<SqlPreviewPanel> implements Disposable {

    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";
    private static final String COLUMN_INFO_TAB_TITLE = "列信息";
    private static final long REHOST_MIN_INTERVAL_MS = 800L;
    private static final Pattern NUMBER_REGEX = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");

    private final Project project;

    private final JBLabel metaLabel = new JBLabel();

    private final AnAction reformatAction = new AnAction() {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            formatSql();
        }
    };

    private final JBPanel<JBPanel<?>> paramPanel = new JBPanel<JBPanel<?>>() {{
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }};

    private final JBPanel<JBPanel<?>> paramTopAlign = new JBPanel<JBPanel<?>>(new BorderLayout()) {{
        add(paramPanel, BorderLayout.NORTH);
        setOpaque(false);
    }};

    private final JBScrollPane paramScroll = new JBScrollPane(paramTopAlign) {{
        setPreferredSize(new Dimension(240, 200));
        setOpaque(false);
        getViewport().setOpaque(false);
        setBorder(JBUI.Borders.empty());
    }};

    private final List<JBTextField> paramFields = new ArrayList<>();
    private final List<JCheckBox> conditionCheckBoxes = new ArrayList<>();
    private Disposable autoPopupDisposable = null;
    private final List<JBTextField> conditionValueFields = new ArrayList<>();
    private final List<JCheckBox> batchCheckBoxes = new ArrayList<>();
    private JButton selectAllButton = null;
    private JButton selectNoneButton = null;
    private JButton clearButton = null;
    private JButton batchClearButton = null;
    private JButton batchSelectAllButton = null;
    private JButton batchSelectNoneButton = null;

    private Editor editor = null;

    private com.intellij.openapi.editor.event.DocumentListener autoPopupListener = null;
    private boolean autoPopupScheduled = false;
    private boolean suppressAutoPopup = false;
    private PsiFile psiFile = null;

    private volatile VirtualFile previewTempFile = null;
    private volatile Pair<VirtualFile, PsiFile> prewarmedTempFile = null;
    private volatile boolean previewDisposed = false;
    private String sourceLocation = null;

    private final JBPanel<JBPanel<?>> editorHolder = new JBPanel<JBPanel<?>>(new GridLayout(1, 1)) {{
        setOpaque(false);
    }};

    private final SqlResultPanel resultPanel = new SqlResultPanel() {{
        setVisible(false);
    }};

    private JComponent embeddedConsole = null;

    private final JBPanel<JBPanel<?>> consoleHost = new JBPanel<JBPanel<?>>(new BorderLayout()) {{
        setOpaque(false);
    }};

    private Integer lastConsoleEmbeddedId = null;
    private long lastRehostAt = 0L;

    private Document nativeEditorDoc = null;

    private PreviewResult current = null;
    private boolean compactMode = false;
    private String previousSql = null;

    private final DiagramPanel diagramPanel;
    // T-Dgm-04：标签页宿主——原生图框架组件（joinDiagramNative=true 且 Diagrams 插件可用）与
    // 自绘 DiagramPanel 共用此容器按需切换；切换时旧原生编辑器即时 dispose 防 FileEditor 泄漏
    private final javax.swing.JPanel diagramTabHost = new javax.swing.JPanel(new java.awt.BorderLayout());
    private javax.swing.JComponent currentNativeDiagram = null;
    private JComponent columnInfoPanel = null;
    private JBPanel<JBPanel<?>> contentPane = null;
    private JTabbedPane contentTabPane = null;

    private final Set<String> enabledIds = new LinkedHashSet<>();
    private final Map<String, String> paramValues = new LinkedHashMap<>();
    private final Map<String, Integer> xmlForEachCounts = new LinkedHashMap<>();
    private final Set<String> xmlForEachNulls = new LinkedHashSet<>();
    private List<String> xmlErrors = new ArrayList<>();
    private List<ParameterMapping> xmlMappings = new ArrayList<>();
    private JButton errorCopyButton = null;
    private String currentWarning = null;

    private final ParamHistoryCache paramHistoryCache = new ParamHistoryCache();
    private final Timer historyAlarm = new Timer(500, e -> flushHistoryToCache());

    {
        historyAlarm.setRepeats(false);
    }

    private JButton modeButton;

    public SqlPreviewPanel() {
        this(null);
    }

    public SqlPreviewPanel(@Nullable Project project) {
        super(new BorderLayout());
        this.project = project;
        this.diagramPanel = new DiagramPanel(project);

        metaLabel.setBorder(JBUI.Borders.empty(0, 8, 4, 8));
        JBPanel<JBPanel<?>> topRow = new JBPanel<JBPanel<?>>(new BorderLayout()) {{
            add(metaLabel, BorderLayout.WEST);
            add(toolbar(), BorderLayout.EAST);
            setBorder(JBUI.Borders.empty(4, 8, 2, 8));
        }};
        add(topRow, BorderLayout.NORTH);

        ThemedSplitPane split = new ThemedSplitPane(paramScroll, editorHolder);
        split.setResizeWeight(0.3);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(false);
        split.setDividerSize(3);
        split.setBorder(JBUI.Borders.empty());
        split.setBackground(UIUtil.getPanelBackground());

        add(split, BorderLayout.CENTER);
        add(resultPanel, BorderLayout.SOUTH);

        reformatAction.registerCustomShortcutSet(new CustomShortcutSet(reformatShortcuts()), this);

        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(LafManagerListener.TOPIC, e -> lookAndFeelChanged());

        Disposer.register(this, diagramPanel);
        // T-Dgm-04：标签页实际展示 diagramTabHost（内嵌原生组件或 diagramPanel）
        diagramTabHost.setOpaque(false);
        diagramTabHost.add(diagramPanel, java.awt.BorderLayout.CENTER);

        if (project != null) {
            prewarmPreviewTempFile();
        }
    }

    /** T-M36.6-03：后台预热预览临时文件（占位空内容，createEditor 就绪后按实际 SQL 同步）。 */
    private void prewarmPreviewTempFile() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (previewTempFile != null || previewDisposed) return;
            try {
                java.io.File io = java.io.File.createTempFile("mp-sql-preview-", ".sql");
                java.nio.file.Files.writeString(io.toPath(), "");
                io.deleteOnExit();
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io);
                if (vf == null) return;
                if (previewDisposed) {
                    new java.io.File(vf.getPath()).delete();
                    return;
                }
                Project proj = project;
                if (proj == null) return;
                PsiFile psi = ReadAction.compute(() -> PsiManager.getInstance(proj).findFile(vf));
                if (psi == null) return;
                previewTempFile = vf;
                prewarmedTempFile = new Pair<>(vf, psi);
            } catch (Throwable ignored) {
                // 预热失败不影响预览（createEditor 走同步兜底路径）
            }
        });
    }

    /** T-DarkTheme：主题变更回调——重绘面板（命名色已自动更新，仅需触发 UI 刷新）。 */
    private void lookAndFeelChanged() {
        revalidate();
        repaint();
    }

    /**
     * T-M26.1：分隔条主题化分栏——覆写 paint，在 LAF 绘制完成后把分隔条区域覆盖为面板底色。
     */
    private static class ThemedSplitPane extends JSplitPane {
        ThemedSplitPane(JComponent left, JComponent right) {
            super(JSplitPane.HORIZONTAL_SPLIT, left, right);
            setBorder(JBUI.Borders.empty());
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g);
            int loc = getDividerLocation();
            if (loc < 0) return;
            g.setColor(UIUtil.getPanelBackground());
            g.fillRect(loc, 0, getDividerSize(), getHeight());
        }
    }

    /** 顶部工具栏。 */
    private JBPanel<JBPanel<?>> toolbar() {
        JBPanel<JBPanel<?>> bar = new JBPanel<JBPanel<?>>() {{
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        }};
        errorCopyButton = smallButton(MpSqlVisualizerBundle.message("panel.copyError"), this::copyError);
        errorCopyButton.setVisible(false);
        bar.add(errorCopyButton);
        modeButton = smallButton(modeButtonText(), () -> {
            toggleCompact();
            modeButton.setText(modeButtonText());
        });
        bar.add(modeButton);
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.format"), this::formatSql));
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.keywordWrap"), this::formatClauseLine));
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.param.import"), this::importParamsFromClipboard));
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.compare"), this::showSqlDiff));
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.share"), this::showShareDialog));
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.refresh"), this::refreshView));
        return bar;
    }

    /** 执行 SQL（M36）。 */
    public void executeSql() {
        Project proj = project;
        if (proj == null) return;
        String sql = firstNonBlankSql();
        if (sql == null) {
            Logger.getInstance(SqlPreviewPanel.class).warn(
                    "[M36] executeSql aborted: no SQL text (nativeDoc=" + (nativeEditorDoc != null)
                            + ", editor=" + (editor != null) + ")");
            return;
        }
        Logger.getInstance(SqlPreviewPanel.class).info("[M36] executeSql: len=" + sql.length() + ", prepared=" + SqlExecutor.isPrepared());

        boolean isQuery = SqlTypeClassifier.isQuery(sql);
        if (!isQuery) {
            String keyword = SqlTypeClassifier.getFirstKeyword(sql);
            if (keyword == null) keyword = "SQL";
            int confirm = Messages.showYesNoDialog(
                    proj,
                    MpSqlVisualizerBundle.message("execute.confirm.dml.message", keyword),
                    MpSqlVisualizerBundle.message("execute.confirm.dml"),
                    Messages.getWarningIcon());
            if (confirm != Messages.YES) return;
        }

        resultPanel.showLoading(null);
        if (SqlExecutor.isPrepared()) {
            if (nativeEditorDoc != null && !nativeEditorDoc.getText().equals(sql)) {
                WriteCommandAction.runWriteCommandAction(proj, () -> nativeEditorDoc.setText(sql));
            }
            boolean executed = SqlExecutor.executePrepared(
                    proj,
                    fullConsole -> embedConsole(fullConsole),
                    errorMsg -> resultPanel.showError(errorMsg));
            if (executed) return;
        }
        SqlExecutor.execute(
                proj, sql, null, isQuery,
                consoleComponent -> embedConsole(consoleComponent, true),
                consoleEditor -> onNativeEditorReady(consoleEditor),
                errorMsg -> resultPanel.showError(errorMsg));
    }

    /** 取当前非空 SQL 文本（优先原生编辑器文档，兜底自研编辑器）。 */
    @Nullable
    private String firstNonBlankSql() {
        if (nativeEditorDoc != null) {
            String t = nativeEditorDoc.getText();
            if (t != null && !t.isBlank()) return t;
        }
        if (editor != null) {
            String t = editor.getDocument().getText();
            if (t != null && !t.isBlank()) return t;
        }
        return null;
    }

    /** M36.5：原生控制台编辑器就绪统一处理。 */
    private void onNativeEditorReady(Editor consoleEditor) {
        SqlCompletionContext ctx = buildCompletionContext();
        consoleEditor.putUserData(SqlCompletionContext.KEY, ctx);
        SqlCompletionContributor.registerContext(consoleEditor.getDocument(), ctx);
        nativeEditorDoc = consoleEditor.getDocument();
        if (editor != null) {
            releaseEditor();
            editorHolder.removeAll();
        }
    }

    /** M36.5：发起控制台默认准备（面板展示时调用，不执行）。 */
    private void prepareDefaultConsole() {
        Project proj = project;
        if (proj == null) return;
        String sql = editor != null ? editor.getDocument().getText() : null;
        if (sql == null || sql.isBlank()) return;
        SqlExecutor.prepareConsole(
                proj, sql,
                consoleComponent -> {
                    embedConsole(consoleComponent);
                    resultPanel.showEmpty();
                },
                consoleEditor -> onNativeEditorReady(consoleEditor),
                null, null);
    }

    /** M36.4：将拼合的原生控制台 UI 组件内嵌到右侧 SQL 显示栏。 */
    private void embedConsole(JComponent consoleComponent) {
        embedConsole(consoleComponent, false);
    }

    private void embedConsole(JComponent consoleComponent, boolean previewMode) {
        int id = System.identityHashCode(consoleComponent);
        Integer prev = lastConsoleEmbeddedId;
        Logger.getInstance(SqlPreviewPanel.class).info("[M36] embedConsole identity: id=" + id
                + ", prevEmbedded=" + prev
                + ", sameAsPrev=" + (prev != null && prev == id)
                + ", sameInstance=" + (consoleComponent == embeddedConsole)
                + ", previewMode=" + previewMode);
        if (consoleComponent == embeddedConsole) {
            resultPanel.showIdle();
            if (!previewMode && consoleComponent instanceof JSplitPane) {
                JSplitPane sp = (JSplitPane) consoleComponent;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (sp.getHeight() > 0) {
                        sp.setDividerLocation(0.6);
                        sp.revalidate();
                    }
                }, ModalityState.any());
            }
            return;
        }
        detachConsole();
        embeddedConsole = consoleComponent;
        lastConsoleEmbeddedId = id;
        consoleComponent.setBorder(JBUI.Borders.empty());
        consoleComponent.setOpaque(false);
        editorHolder.removeAll();
        editorHolder.add(consoleHost, BorderLayout.CENTER);
        consoleHost.removeAll();
        consoleHost.add(consoleComponent, BorderLayout.CENTER);
        attachRehostListener(consoleComponent);
        if (consoleComponent instanceof JSplitPane) {
            JSplitPane sp = (JSplitPane) consoleComponent;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (sp.getHeight() > 0) {
                    sp.setDividerLocation(previewMode ? 1.0 : 0.6);
                    sp.revalidate();
                    sp.repaint();
                }
                DataSourceSelector.installOutputHistoryFillLayout(sp, Logger.getInstance(SqlPreviewPanel.class));
            }, ModalityState.any());
            sp.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (sp.getHeight() < 200) return;
                    sp.setDividerLocation(previewMode ? 1.0 : 0.6);
                    sp.removeComponentListener(this);
                }
            });
        } else {
            consoleComponent.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    consoleComponent.revalidate();
                    consoleComponent.repaint();
                    consoleComponent.removeComponentListener(this);
                }
            });
        }
        resultPanel.showIdle();
        revalidate();
        repaint();
    }

    /** M36.5.39-A3：DBN 释放监听（去抖重挂）。 */
    private void attachRehostListener(JComponent component) {
        component.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) == 0L) return;
            if (e.getComponent().getParent() != null) return;
            if (e.getComponent() != embeddedConsole) return;
            long now = System.currentTimeMillis();
            long since = now - lastRehostAt;
            if (since >= 0 && since < REHOST_MIN_INTERVAL_MS) return;
            lastRehostAt = now;
            Logger.getInstance(SqlPreviewPanel.class).info("[M36] console component released by DBN (id="
                    + System.identityHashCode(e.getComponent()) + ") → re-host (since=" + since + "ms)");
            ApplicationManager.getApplication().invokeLater(
                    () -> rehostConsoleForTest((JComponent) e.getComponent()), ModalityState.any());
        });
    }

    /** M36.5.39-A3：重挂（同步逻辑抽出可单测）。 */
    boolean rehostConsoleForTest(JComponent component) {
        if (component != embeddedConsole || component.getParent() == consoleHost) return false;
        consoleHost.removeAll();
        consoleHost.add(component, BorderLayout.CENTER);
        consoleHost.revalidate();
        consoleHost.repaint();
        return true;
    }

    /** M36：摘除已内嵌的控制台组件。 */
    private void detachConsole() {
        if (embeddedConsole != null) consoleHost.remove(embeddedConsole);
        embeddedConsole = null;
    }

    private JButton smallButton(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(11f));
        b.addActionListener(e -> action.run());
        return b;
    }

    private String modeButtonText() {
        return MpSqlVisualizerBundle.message(compactMode ? "panel.mode.multiline" : "panel.mode.singleline");
    }

    // ------------------------------------------------------------------
    // 展示与渲染
    // ------------------------------------------------------------------

    /** 更新展示内容。 */
    public void showResult(PreviewResult result) {
        current = result;
        paramValues.clear();
        Map<String, String> initVals = result.getInitialParamValues();
        for (String source : result.getUnresolvedParams()) {
            if (!paramValues.containsKey(source)) {
                String v = initVals != null ? initVals.get(source) : null;
                paramValues.put(source, v != null ? v : "");
            }
        }
        SqlPreviewModel pm = result.getPreviewModel();
        if (pm != null) {
            for (ConditionView view : pm.getConditionViews()) {
                String vs = view.getValueSource();
                if (vs != null && !paramValues.containsKey(vs)) paramValues.put(vs, "");
            }
        }
        enabledIds.clear();
        if (pm != null) {
            for (ConditionView view : pm.getConditionViews()) {
                if (view.getDefaultEnabled() && view.getId() != null) enabledIds.add(view.getId());
            }
        }
        BatchModel bm = result.getBatchModel();
        if (bm != null) {
            for (BatchColumn col : bm.getToggleColumns()) {
                if (col.getChecked() && col.getColumnName() != null) enabledIds.add(col.getColumnName());
            }
        }
        XmlPreviewModel xmlModel = result.getXmlModel();
        if (xmlModel != null) {
            for (XmlConditionView view : xmlModel.getConditionViews()) {
                if (view.defaultEnabled() && view.id() != null) enabledIds.add(view.id());
            }
            xmlForEachCounts.clear();
            xmlForEachCounts.putAll(xmlModel.defaultState().forEachCounts());
            xmlForEachNulls.clear();
            xmlErrors = new ArrayList<>();
            XmlPreviewState initState = new XmlPreviewState(
                    new LinkedHashSet<>(enabledIds),
                    new LinkedHashMap<>(xmlForEachCounts),
                    new LinkedHashSet<>(xmlForEachNulls),
                    Collections.emptyMap());
            RenderedXmlSql initial = xmlModel.renderSql(initState);
            for (Map.Entry<String, String> e : initial.bindValues().entrySet()) {
                String name = e.getKey();
                String value = e.getValue();
                if (paramValues.get(name) == null || paramValues.get(name).isEmpty()) {
                    paramValues.put(name, value);
                }
            }
        }
        recallFromHistory();
        if (result.getPreviewModel() != null) compactMode = !result.getPreviewModel().getFormatSql();
        modeButton.setText(modeButtonText());
        rebuildLeftPanel(result);
        refreshCompletionContext();
        renderSql();
        updateMeta(result);
        updateDiagramTab(result);
        updateColumnInfoTab(result);
        previousSql = editor != null ? editor.getDocument().getText() : null;
        prepareDefaultConsole();
    }

    /** F-1：根据当前预览结果更新 JOIN 图标签页。 */
    private void updateDiagramTab(PreviewResult result) {
        SqlPreviewModel pm = result.getPreviewModel();
        boolean hasJoins = pm != null && pm.getStatement() instanceof SelectStatement
                && !((SelectStatement) pm.getStatement()).getJoins().isEmpty();
        if (hasJoins) {
            ensureTabbedContent(true);
            DiagramModel model = JoinDiagramGenerator.buildDiagramModel((SelectStatement) pm.getStatement());
            // T-Embed-02：数据源可用时把列类型/注释升级为 DB 真实值（int(11)/DB COMMENT），缺席静默回落
            model = cn.song.mybatisplus.visualizer.ui.diagram.DiagramSchemaEnricher.enrich(project, model);
            showJoinDiagram(model);
        } else {
            removeDiagramTab();
            diagramPanel.clear();
            clearNativeDiagram();
        }
    }

    /**
     * T-Dgm-04：按设置与 Diagrams 插件可用性渲染 JOIN 图。
     *
     * 原生路径：快照登记 → UmlEditorProvider.createEditor → 组件嵌入 diagramTabHost
     * （缩放/布局/折叠/导出全框架托管）；Diagrams 插件缺席或框架异常（buildTabComponent 返回 null）
     * 自动降级自绘 DiagramPanel，与 Community 行为一致。
     */
    private void showJoinDiagram(DiagramModel model) {
        boolean nativeEnabled = PluginSettings.getInstance().getState().getJoinDiagramNative();
        JComponent nativeCmp = null;
        if (nativeEnabled) {
            // T-Embed-03 降级链：Database 原生图（真实 DbTable + 虚拟外键 JOIN 线）优先，
            // 数据源缺席/表未定位/框架异常回落 A 路径自实现 Provider，再回落自绘渲染器
            if (project != null) {
                nativeCmp = cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport
                        .buildTabComponent(project, model, MpSqlVisualizerBundle.message("diagram.tab.title"), this);
            }
            if (nativeCmp == null) {
                nativeCmp = cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramSupport
                        .buildTabComponent(project, model, MpSqlVisualizerBundle.message("diagram.tab.title"), this);
            }
        }
        clearNativeDiagram();
        diagramTabHost.removeAll();
        if (nativeCmp != null) {
            currentNativeDiagram = nativeCmp;
            diagramTabHost.add(nativeCmp, java.awt.BorderLayout.CENTER);
        } else {
            diagramTabHost.add(diagramPanel, java.awt.BorderLayout.CENTER);
            diagramPanel.showDiagram(model);
        }
        diagramTabHost.revalidate();
        diagramTabHost.repaint();
    }

    /** 释放并移除当前原生图组件（内部 FileEditor 经 clientProperty 定位后 dispose）。 */
    private void clearNativeDiagram() {
        if (currentNativeDiagram != null) {
            // 两条原生路径的 clientProperty key 不同，各 dispose 一次（未命中为 no-op）
            cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramSupport
                    .disposeNativeEditor(currentNativeDiagram);
            cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport
                    .disposeNativeEditor(currentNativeDiagram);
            diagramTabHost.remove(currentNativeDiagram);
        }
        currentNativeDiagram = null;
    }

    private void ensureTabbedContent(boolean addDiagramTab) {
        if (contentTabPane != null) return;
        BorderLayout bl = (BorderLayout) getLayout();
        Component center = bl.getLayoutComponent(BorderLayout.CENTER);
        if (center != null) remove(center);
        JTabbedPane pane = new JTabbedPane();
        pane.addTab(MpSqlVisualizerBundle.message("panel.tab.sql"), center);
        if (addDiagramTab) {
            pane.addTab(MpSqlVisualizerBundle.message("diagram.tab.title"), diagramTabHost);
        }
        if (addDiagramTab) {
            pane.addChangeListener(e -> {
                if (pane.getSelectedIndex() == 1 && currentNativeDiagram == null) {
                    diagramPanel.refreshAfterVisible();
                }
            });
        }
        add(pane, BorderLayout.CENTER);
        contentTabPane = pane;
        revalidate();
        repaint();
    }

    private void removeDiagramTab() {
        JTabbedPane tabPane = contentTabPane;
        if (tabPane == null) return;
        Component sqlContent = tabPane.getComponentAt(0);
        remove(tabPane);
        add(sqlContent, BorderLayout.CENTER);
        contentTabPane = null;
        revalidate();
        repaint();
    }

    private void updateColumnInfoTab(PreviewResult result) {
        List<PagesColumn> pages = result.getPagesColumns();
        if (pages != null) {
            ensureTabbedContent(false);
            JTabbedPane tabPane = contentTabPane;
            if (tabPane == null) return;
            if (!hasTabTitle(tabPane, COLUMN_INFO_TAB_TITLE)) {
                JComponent panel = buildColumnInfoPanel(pages);
                tabPane.addTab(COLUMN_INFO_TAB_TITLE, panel);
            }
        } else {
            removeColumnInfoTab();
        }
    }

    private JComponent buildColumnInfoPanel(List<PagesColumn> columns) {
        Object[] header = new Object[]{
                MpSqlVisualizerBundle.message("pages.columns.id"),
                MpSqlVisualizerBundle.message("pages.columns.title"),
                MpSqlVisualizerBundle.message("pages.columns.dataType"),
                MpSqlVisualizerBundle.message("pages.columns.visible"),
        };
        Object[][] data = new Object[columns.size()][];
        for (int k = 0; k < columns.size(); k++) {
            PagesColumn c = columns.get(k);
            data[k] = new Object[]{c.id(), c.title(), c.dataType(), c.visible() ? "✅" : "❌"};
        }
        JBTable table = new JBTable(new DefaultTableModel(data, header) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        });
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
        table.getTableHeader().setReorderingAllowed(false);
        return new JBScrollPane(table);
    }

    private void removeColumnInfoTab() {
        JTabbedPane tabPane = contentTabPane;
        if (tabPane == null) return;
        for (int i = tabPane.getTabCount() - 1; i >= 0; i--) {
            if (COLUMN_INFO_TAB_TITLE.equals(tabPane.getTitleAt(i))) {
                tabPane.removeTabAt(i);
                break;
            }
        }
        if (tabPane.getTabCount() == 1) {
            Component sqlContent = tabPane.getComponentAt(0);
            remove(tabPane);
            add(sqlContent, BorderLayout.CENTER);
            contentTabPane = null;
            revalidate();
            repaint();
        }
    }

    private boolean hasTabTitle(JTabbedPane tabPane, String title) {
        for (int i = 0; i < tabPane.getTabCount(); i++) {
            if (title.equals(tabPane.getTitleAt(i))) return true;
        }
        return false;
    }

    /** 刷新补全上下文（M16.2 双载体：Editor userData 优先 + PSI 兜底）。 */
    private void refreshCompletionContext() {
        SqlCompletionContext ctx = buildCompletionContext();
        if (editor != null) editor.putUserData(SqlCompletionContext.KEY, ctx);
        if (psiFile != null) psiFile.putUserData(SqlCompletionContext.KEY, ctx);
        if (editor != null) SqlCompletionContributor.registerContext(editor.getDocument(), ctx);
    }

    private SqlCompletionContext buildCompletionContext() {
        return new SqlCompletionContext(
                current != null ? current.getTableName() : null,
                current != null ? current.getColumns() : Collections.emptyList(),
                null,
                current != null ? current.getColumnComments() : Collections.emptyMap(),
                current != null ? current.getTableComment() : null,
                current != null ? current.getCandidateTables() : Collections.emptyList());
    }

    /**
     * JDBC 命名参数（NamedParameterJdbcTemplate `:name`）按名回填。
     *
     * 当 current.namedParamHints 非空时，把 SQL 中 `:name` 按用户输入的参数值整体替换
     * （同名参数所有出现共用输入值，对齐 NamedParameterJdbcTemplate 语义）；`?` 占位符不受影响，
     * 由调用方继续走 backfillParamsBySource 按序回填。命名参数提示为空时原样返回（兼容既有顺序回填路径）。
     */
    private String applyNamedParams(String sql) {
        PreviewResult r = current;
        if (r == null) return sql;
        if (r.getNamedParamHints().isEmpty()) return sql;
        return ParameterReplacer.replaceNamedParams(sql, paramValues, r.getNamedParamHints());
    }

    /** 按当前压缩/多行模式 + 动态条件启用集合 + 参数回填重渲染 SQL 编辑器。 */
    private void renderSql() {
        PreviewResult result = current;
        if (result == null) return;
        SqlPreviewModel model = result.getPreviewModel();
        BatchModel batch = result.getBatchModel();
        XmlPreviewModel xml = result.getXmlModel();
        if (xml != null) {
            XmlPreviewState state = new XmlPreviewState(
                    new LinkedHashSet<>(enabledIds),
                    new LinkedHashMap<>(xmlForEachCounts),
                    new LinkedHashSet<>(xmlForEachNulls),
                    Collections.emptyMap());
            RenderedXmlSql rendered = xml.renderSql(state);
            xmlErrors = rendered.errors();
            xmlMappings = rendered.mappings();
            for (Map.Entry<String, String> e : rendered.bindValues().entrySet()) {
                String name = e.getKey();
                String value = e.getValue();
                if (paramValues.get(name) == null || paramValues.get(name).isEmpty()) {
                    paramValues.put(name, value);
                }
            }
            String base = rendered.sql();
            String sql = compactMode ? WHITESPACE_REGEX.matcher(base).replaceAll(" ").trim() : base;
            List<String> sources = new ArrayList<>();
            for (ParameterMapping m : rendered.mappings()) sources.add(m.property());
            Set<Integer> inListIndices = detectInClauseParams(sql);
            List<ParamHint> xmlHints = new ArrayList<>();
            for (int idx = 0; idx < sources.size(); idx++) {
                String source = sources.get(idx);
                Boolean q = xml.quoteFor(source);
                boolean quote = q != null ? q : true;
                xmlHints.add(new ParamHint(quote, null, inListIndices.contains(idx)));
            }
            showSql(backfillParamsBySource(sql, sources, xmlHints));
        } else if (batch != null) {
            Set<String> active = new LinkedHashSet<>(enabledIds);
            String sql = batch.renderSql(active);
            List<String> sources = batch.orderedSources(active);
            List<ParamHint> hints = batch.orderedHints(active);
            showSql(backfillParamsBySource(sql,
                    sources != null ? sources : Collections.emptyList(),
                    hints != null ? hints : Collections.emptyList()));
        } else if (model != null) {
            Object generated = SqlGenerator.generateWithParams(model.getStatement(), compactMode, model.getDialect(), enabledIds);
            String sql = ((cn.song.mybatisplus.visualizer.sql.GeneratedSql) generated).getSql();
            List<String> up = ((cn.song.mybatisplus.visualizer.sql.GeneratedSql) generated).getUnresolvedParams();
            List<ParamHint> ph = ((cn.song.mybatisplus.visualizer.sql.GeneratedSql) generated).getParamHints();
            // JDBC 命名参数按名回填（先），再对残留 `?` 按序回填
            showSql(backfillParamsBySource(applyNamedParams(sql),
                    up != null ? up : Collections.emptyList(),
                    ph != null ? ph : Collections.emptyList()));
        } else {
            // JDBC 命名参数按名回填（先），再对残留 `?` 按序回填
            String base = compactMode ? result.getCompactSql() : result.getSql();
            showSql(backfillParamsBySource(applyNamedParams(base), result.getUnresolvedParams(), result.getParamHints()));
        }
    }

    /** 刷新视图（M19.3）：模式复位为与设置「SQL 格式化输出」对齐。 */
    public void refreshView() {
        compactMode = !formatSqlSetting();
        modeButton.setText(modeButtonText());
        renderSql();
    }

    private boolean formatSqlSetting() {
        try {
            return PluginSettings.getInstance().getState().getFormatSql();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 格式化当前编辑器 SQL。 */
    public void formatSql() {
        Document doc = nativeEditorDoc != null ? nativeEditorDoc : (editor != null ? editor.getDocument() : null);
        if (doc == null) return;
        String formatted = SqlFormatter.INSTANCE.format(doc.getText());
        if (!formatted.equals(doc.getText())) {
            WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(formatted));
        }
    }

    private void formatClauseLine() {
        Document doc = nativeEditorDoc != null ? nativeEditorDoc : (editor != null ? editor.getDocument() : null);
        if (doc == null) return;
        String formatted = SqlFormatter.INSTANCE.formatClauseLine(doc.getText());
        if (!formatted.equals(doc.getText())) {
            WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(formatted));
        }
    }

    private Shortcut[] reformatShortcuts() {
        Shortcut[] fromKeymap = KeymapManager.getInstance().getActiveKeymap().getShortcuts("ReformatCode");
        if (fromKeymap.length > 0) return fromKeymap;
        return new Shortcut[]{
                new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_MASK | InputEvent.ALT_MASK), null)
        };
    }

    /** 压缩 / 多行切换。 */
    public boolean toggleCompact() {
        compactMode = !compactMode;
        renderSql();
        return compactMode;
    }

    /** 一键复制当前 SQL。 */
    public boolean copySql() {
        String raw = firstNonBlankSql();
        if (raw == null) return false;
        String text = SqlCommentStripper.strip(raw);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        if (project != null) {
            String[] options = new String[]{
                    MpSqlVisualizerBundle.message("dialog.ok"),
                    MpSqlVisualizerBundle.message("dialog.closeSqlPanel")
            };
            int choice = Messages.showDialog(project,
                    MpSqlVisualizerBundle.message("dialog.copy.done"),
                    MpSqlVisualizerBundle.message("plugin.name"),
                    options, 0, Messages.getInformationIcon());
            if (choice == 1 && onClosePanelRequested != null) onClosePanelRequested.run();
        }
        return true;
    }

    public void copyExecutableSql() {
        new CopyExecutableSqlAction(project).execute(this);
    }

    public void showShareDialog() {
        new ShareDialog(project, this).show();
    }

    public void showSqlDiff() {
        Project proj = project;
        if (proj == null) return;
        String currentSql = firstNonBlankSql();
        if (currentSql == null) return;
        String oldSql = previousSql;
        if (oldSql == null) {
            showDiffToast(MpSqlVisualizerBundle.message("preview.diff.noPrevious"));
            return;
        }
        DiffContentFactory diffContentFactory = DiffContentFactory.getInstance();
        com.intellij.openapi.fileTypes.FileType sqlFileType = FileTypeManager.getInstance().getStdFileType("SQL");
        DocumentContent leftContent = diffContentFactory.createEditable(proj, oldSql, sqlFileType);
        DocumentContent rightContent = diffContentFactory.createEditable(proj, currentSql, sqlFileType);
        SimpleDiffRequest request = new SimpleDiffRequest(
                MpSqlVisualizerBundle.message("panel.diff.dialogTitle"),
                leftContent, rightContent,
                MpSqlVisualizerBundle.message("panel.diff.previous"),
                MpSqlVisualizerBundle.message("panel.diff.current"));
        DiffManager.getInstance().showDiff(proj, request);
    }

    private void showDiffToast(String message) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        Project proj = project;
        if (proj == null) return;
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(message, NotificationType.INFORMATION)
                    .notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /** T-M23.6：提示「关闭 SQL 面板」按钮回调（由 [SqlPreviewDialog] 注入 dispose()）。 */
    public Runnable onClosePanelRequested = null;

    /** 清空展示区。 */
    public void clear() {
        current = null;
        enabledIds.clear();
        paramValues.clear();
        paramFields.clear();
        conditionCheckBoxes.clear();
        conditionValueFields.clear();
        batchCheckBoxes.clear();
        xmlForEachCounts.clear();
        xmlForEachNulls.clear();
        xmlErrors = new ArrayList<>();
        currentWarning = null;
        if (errorCopyButton != null) errorCopyButton.setVisible(false);
        selectAllButton = null;
        selectNoneButton = null;
        batchSelectAllButton = null;
        batchSelectNoneButton = null;
        paramPanel.removeAll();
        previousSql = null;
        releaseEditor();
        editorHolder.removeAll();
        metaLabel.setText("");
        removeDiagramTab();
        diagramPanel.clear();
        clearNativeDiagram();
        resultPanel.clear();
        detachConsole();
        nativeEditorDoc = null;
        SqlExecutor.clearPrepared();
        if (project != null) prewarmPreviewTempFile();
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------
    // 编辑器（PSI 支撑 + 可编辑 + 高亮）
    // ------------------------------------------------------------------

    private void showSql(String sql) {
        String withFill = appendFillSection(sql);
        String text = (formatSqlSetting() && !compactMode) ? SqlFormatter.INSTANCE.format(withFill) : withFill;
        Document nativeDoc = nativeEditorDoc;
        if (nativeDoc != null && !nativeDoc.getText().equals(text)) {
            suppressAutoPopup = true;
            try {
                WriteCommandAction.runWriteCommandAction(project, () -> nativeDoc.setText(text));
            } finally {
                suppressAutoPopup = false;
            }
            return;
        }
        Editor existing = editor;
        if (existing != null) {
            if (!existing.getDocument().getText().equals(text)) {
                suppressAutoPopup = true;
                try {
                    WriteCommandAction.runWriteCommandAction(project, () -> existing.getDocument().setText(text));
                } finally {
                    suppressAutoPopup = false;
                }
            }
            return;
        }
        createEditor(text);
    }

    private String appendFillSection(String sql) {
        PreviewResult result = current;
        if (result == null) return sql;
        List<FillClause> fills = result.getFillClauses();
        if (fills == null || fills.isEmpty()) return sql;
        Dialect dialect = result.getPreviewModel() != null ? result.getPreviewModel().getDialect() : MySqlDialect.INSTANCE;
        StringBuilder sb = new StringBuilder(sql);
        sb.append("\n\n-- ").append(MpSqlVisualizerBundle.message("panel.fill.section")).append("\n");
        for (FillClause fill : fills) {
            sb.append(SqlGenerator.generateFill(fill, dialect));
            sb.append("\n");
        }
        return sb.toString();
    }

    private void createEditor(String sql) {
        Project proj = project;
        com.intellij.openapi.fileTypes.FileType sqlFileType = FileTypeManager.getInstance().getStdFileType("SQL");
        Editor created;
        if (proj != null) {
            Pair<VirtualFile, PsiFile> prewarmed = prewarmedTempFile;
            VirtualFile vFile;
            PsiFile psi;
            if (prewarmed != null) {
                prewarmedTempFile = null;
                vFile = prewarmed.getFirst();
                psi = prewarmed.getSecond();
            } else {
                try (AccessToken ignored = SlowOperations.allowSlowOperations(SlowOperations.GENERIC)) {
                    // T-PreviewWiLock：必须 WriteIntentReadAction——refreshAndFindFileByIoFile 是同步 VFS
                    // refresh，2024.2+ 平台要求 write-safe context（write-intent lock）；EDT 进 ReadAction
                    // 会把 write-intent lock check out，262 平台 RefreshSessionImpl.launch 硬抛
                    // "Access is allowed from write thread only"（日志双击预览崩溃实证）。
                    // write-intent 隐含读权限，lambda 内 findFile 不受影响。
                    // T-Wia262：显式选 compute(Computable) 重载——compute(ThrowableComputable)
                    // 重载在 262 平台已移除（verifyPlugin 兼容性问题实证），compute(Computable) 为
                    // 243~262 三版本交集；lambda 内仅不受检异常，SAM 换型语义不变。
                    Pair<VirtualFile, PsiFile> pair = WriteIntentReadAction.compute(
                            (Computable<Pair<VirtualFile, PsiFile>>) () -> {
                        VirtualFile vf = previewTempFile;
                        if (vf == null) {
                            try {
                                java.io.File io = java.io.File.createTempFile("mp-sql-preview-", ".sql");
                                java.nio.file.Files.writeString(io.toPath(), sql);
                                io.deleteOnExit();
                                vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io);
                            } catch (java.io.IOException e) {
                                throw new RuntimeException(e);
                            }
                            if (vf == null) throw new IllegalStateException("无法创建 SQL 预览临时文件");
                        }
                        PsiFile p = PsiManager.getInstance(proj).findFile(vf);
                        if (p == null) throw new IllegalStateException("临时 SQL 文件无 PSI");
                        return new Pair<>(vf, p);
                    });
                    vFile = pair.getFirst();
                    psi = pair.getSecond();
                }
            }
            previewTempFile = vFile;
            psiFile = psi;
            Document doc = PsiDocumentManager.getInstance(proj).getDocument(psi);
            if (doc == null) throw new IllegalStateException("临时 SQL 文件无关联文档");
            if (!doc.getText().equals(sql)) {
                WriteCommandAction.runWriteCommandAction(proj, () -> {
                    doc.setText(sql);
                    PsiDocumentManager.getInstance(proj).commitDocument(doc);
                });
            }
            created = EditorFactory.getInstance().createEditor(doc, proj, sqlFileType, false);
        } else {
            created = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql));
        }
        SqlCompletionContext ctx = buildCompletionContext();
        created.putUserData(SqlCompletionContext.KEY, ctx);
        if (psiFile != null) psiFile.putUserData(SqlCompletionContext.KEY, ctx);
        SqlCompletionContributor.registerContext(created.getDocument(), ctx);
        EditorEx createdEx = (EditorEx) created;
        // F-CASE：自研 SQL 高亮器（SqlLexer 将 CASE/WHEN/THEN/ELSE/END 标记为 KEYWORD，
        // 字段名标记为 IDENTIFIER → 双击选择不会跨 token 粘连；不依赖 Database 插件）
        createdEx.setHighlighter(new LexerEditorHighlighter(
                new SqlSyntaxHighlighter(), EditorColorsManager.getInstance().getGlobalScheme()));
        createdEx.getSettings().setLineNumbersShown(true);
        createdEx.getSettings().setFoldingOutlineShown(true);
        createdEx.getSettings().setIndentGuidesShown(false);
        createdEx.getSettings().setAdditionalPageAtBottom(false);
        editorHolder.removeAll();
        // T-M27.1：外层包装必须「实现 DataProvider 接口」（master 显式声明）——仅写 getData 方法
        // 不声明接口时 instanceof DataProvider 不成立，DataManager 沿父链找不到 EDITOR 数据
        EditorDataWrapper wrapper = new EditorDataWrapper();
        wrapper.editor = created;
        wrapper.add(created.getComponent(), BorderLayout.CENTER);
        editorHolder.add(wrapper, BorderLayout.CENTER);
        editorHolder.revalidate();
        editorHolder.repaint();
        editor = created;
        bindAutoPopup(created);
    }

    /**
     * T-M27.1：编辑器组件链 DataProvider 包装——必须显式实现 DataProvider 接口
     * （2025.3 平台编辑器内部 UiCompatibleDataProvider 仅新 UI 兼容查询路径生效，
     * 传统 DataManager.getDataContext 沿父链只认 DataProvider 接口）。
     */
    private static final class EditorDataWrapper extends JBPanel<JBPanel<?>> implements com.intellij.openapi.actionSystem.DataProvider {
        Editor editor;

        EditorDataWrapper() {
            super(new BorderLayout());
        }

        @Override
        public Object getData(@NotNull String dataId) {
            return CommonDataKeys.EDITOR.is(dataId) ? editor : null;
        }
    }

    /** T-M27.2：自建输入自动补全。 */
    private void bindAutoPopup(Editor created) {
        com.intellij.openapi.editor.event.DocumentListener listener = new com.intellij.openapi.editor.event.DocumentListener() {
            @Override
            public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent event) {
                if (suppressAutoPopup) return;
                if (event.getNewLength() == 0 || event.getOldLength() > event.getNewLength()) return;
                Editor ed = editor;
                if (ed == null) return;
                if (ed.isDisposed() || project == null) return;
                LookupEx active = LookupManager.getActiveLookup(ed);
                if (active != null && !active.getItems().isEmpty()) return;
                if (autoPopupScheduled) return;
                int insertEnd = event.getOffset() + event.getNewLength();
                if (insertEnd <= 0) return;
                CharSequence seq = ed.getDocument().getText(TextRange.create(insertEnd - 1, insertEnd));
                if (seq.length() == 0) return;
                char prev = seq.charAt(0);
                if (!Character.isLetterOrDigit(prev) && prev != '_') return;
                autoPopupScheduled = true;
                ApplicationManager.getApplication().invokeLater(() -> {
                    autoPopupScheduled = false;
                    Editor ed2 = editor;
                    Project proj = project;
                    if (ed2 == null || proj == null) return;
                    if (ed2.isDisposed()) return;
                    LookupEx active2 = LookupManager.getActiveLookup(ed2);
                    if (active2 != null && !active2.getItems().isEmpty()) return;
                    try {
                        PsiDocumentManager.getInstance(proj).commitDocument(ed2.getDocument());
                        new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
                                .invokeCompletion(proj, ed2, 1, false);
                    } catch (Throwable ignored) {
                    }
                });
            }
        };
        if (autoPopupDisposable != null) Disposer.dispose(autoPopupDisposable);
        autoPopupDisposable = Disposer.newDisposable("SqlPreviewPanel.autoPopup");
        created.getDocument().addDocumentListener(listener, autoPopupDisposable);
        autoPopupListener = listener;
    }

    /** 释放当前编辑器（幂等）。 */
    private void releaseEditor() {
        if (editor != null) {
            autoPopupListener = null;
            if (autoPopupDisposable != null) Disposer.dispose(autoPopupDisposable);
            autoPopupDisposable = null;
            EditorFactory.getInstance().releaseEditor(editor);
            editor = null;
        }
        psiFile = null;
        if (previewTempFile != null) new java.io.File(previewTempFile.getPath()).delete();
        previewTempFile = null;
        prewarmedTempFile = null;
    }

    // ------------------------------------------------------------------
    // 参数输入与回填
    // ------------------------------------------------------------------

    private void rebuildLeftPanel(PreviewResult result) {
        paramPanel.removeAll();
        paramFields.clear();
        conditionCheckBoxes.clear();
        conditionValueFields.clear();
        batchCheckBoxes.clear();
        selectAllButton = null;
        selectNoneButton = null;
        clearButton = null;
        batchSelectAllButton = null;
        batchSelectNoneButton = null;
        batchClearButton = null;
        List<ConditionView> views = result.getPreviewModel() != null ? result.getPreviewModel().getConditionViews() : Collections.emptyList();
        BatchModel batchModel = result.getBatchModel();

        if (views.isEmpty() && result.getUnresolvedParams().isEmpty() && batchModel == null) {
            paramPanel.add(new JBLabel(MpSqlVisualizerBundle.message("panel.param.none")) {{
                setBorder(JBUI.Borders.empty(6, 8));
            }});
            paramPanel.revalidate();
            paramPanel.repaint();
            return;
        }

        if (batchModel != null) {
            paramPanel.add(batchSectionHeader());
            int labelWidth = labelColumnWidth(batchModel.getFixedColumns().stream().map(c -> "#" + c.getSource()).collect(Collectors.toList()));
            for (BatchColumn col : batchModel.getToggleColumns()) paramPanel.add(buildBatchColumnRow(col, true));
            for (BatchColumn col : batchModel.getFixedColumns()) paramPanel.add(buildBatchColumnRow(col, false, labelWidth));
            paramPanel.revalidate();
            paramPanel.repaint();
            return;
        }

        XmlPreviewModel xmlModel = result.getXmlModel();
        if (xmlModel != null) {
            boolean hasControls = !xmlModel.getConditionViews().isEmpty() || !xmlModel.getForEachViews().isEmpty();
            if (!hasControls && result.getUnresolvedParams().isEmpty()) {
                paramPanel.add(new JBLabel(MpSqlVisualizerBundle.message("panel.param.none")) {{
                    setBorder(JBUI.Borders.empty(6, 8));
                }});
                paramPanel.revalidate();
                paramPanel.repaint();
                return;
            }
            List<String> ordinarySources = result.getUnresolvedParams().stream()
                    .filter(s -> !s.startsWith("__frch_")).distinct().collect(Collectors.toList());
            List<String> labelSources = new ArrayList<>();
            labelSources.addAll(xmlModel.getForEachViews().stream().map(XmlForEachView::collectionExpr).collect(Collectors.toList()));
            labelSources.addAll(ordinarySources.stream().map(s -> "#" + s).collect(Collectors.toList()));
            int labelWidth = labelColumnWidth(labelSources);
            if (!xmlModel.getConditionViews().isEmpty()) {
                paramPanel.add(conditionSectionHeader());
                for (XmlConditionView view : xmlModel.getConditionViews()) paramPanel.add(buildXmlConditionRow(view));
            }
            if (!xmlModel.getForEachViews().isEmpty()) {
                paramPanel.add(sectionHeaderRow(MpSqlVisualizerBundle.message("panel.xml.foreach.section")));
                for (XmlForEachView view : xmlModel.getForEachViews()) paramPanel.add(buildXmlForEachRow(view, labelWidth));
            }
            if (!ordinarySources.isEmpty()) {
                if (hasControls) paramPanel.add(sectionHeaderRow(MpSqlVisualizerBundle.message("panel.param.section")));
                for (String source : ordinarySources) paramPanel.add(buildParamRow(source, labelWidth));
            }
            paramPanel.revalidate();
            paramPanel.repaint();
            return;
        }

        Set<String> viewSources = new LinkedHashSet<>();
        for (ConditionView view : views) {
            List<String> vs = view.getValueSources();
            if (vs.isEmpty()) {
                String v = view.getValueSource();
                if (v != null) viewSources.add(v);
            } else {
                viewSources.addAll(vs);
            }
        }
        List<String> ordinarySources = result.getUnresolvedParams().stream()
                .filter(s -> !viewSources.contains(s)).distinct().collect(Collectors.toList());
        int labelWidth = labelColumnWidth(ordinarySources.stream().map(s -> "#" + s).collect(Collectors.toList()));
        if (!views.isEmpty()) {
            paramPanel.add(conditionSectionHeader());
            for (ConditionView view : views) paramPanel.add(buildConditionRow(view));
        }
        if (!ordinarySources.isEmpty()) {
            if (!views.isEmpty()) paramPanel.add(sectionHeaderRow(MpSqlVisualizerBundle.message("panel.param.section")));
            for (String source : ordinarySources) paramPanel.add(buildParamRow(source, labelWidth));
        }
        paramPanel.revalidate();
        paramPanel.repaint();
    }

    private JBPanel<JBPanel<?>> buildBatchColumnRow(BatchColumn col, boolean isToggle) {
        return buildBatchColumnRow(col, isToggle, 0);
    }

    private JBPanel<JBPanel<?>> buildBatchColumnRow(BatchColumn col, boolean isToggle, int labelWidth) {
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 8, 6));
        if (isToggle) {
            JCheckBox checkBox = new JCheckBox(checkBoxText(col.getSource()), col.getColumnName() != null && enabledIds.contains(col.getColumnName()));
            checkBox.setToolTipText(checkBoxText(col.getSource()).equals(col.getSource()) ? col.getSource() : col.getColumnName());
            checkBox.addActionListener(e -> {
                if (checkBox.isSelected()) {
                    if (col.getColumnName() != null) enabledIds.add(col.getColumnName());
                } else {
                    if (col.getColumnName() != null) enabledIds.remove(col.getColumnName());
                }
                renderSql();
            });
            batchCheckBoxes.add(checkBox);
            row.add(checkBox);
        } else {
            row.add(paramLabel(col.getSource(), labelWidth));
        }
        JBTextField field = new JBTextField(8);
        field.setToolTipText("#" + col.getSource());
        field.setText(paramValues.getOrDefault(col.getSource(), ""));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onParamChanged(col.getSource(), field.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onParamChanged(col.getSource(), field.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onParamChanged(col.getSource(), field.getText());
            }
        });
        paramFields.add(field);
        row.add(field);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    private JBPanel<JBPanel<?>> sectionHeaderRow(String title, List<JButton> buttons) {
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new BorderLayout());
        row.add(new TitledSeparator(title), BorderLayout.CENTER);
        if (buttons != null && !buttons.isEmpty()) {
            JBPanel<JBPanel<?>> btnWrap = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.RIGHT, 2, 2));
            for (JButton b : buttons) btnWrap.add(b);
            row.add(btnWrap, BorderLayout.EAST);
        }
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    private JBPanel<JBPanel<?>> sectionHeaderRow(String title) {
        return sectionHeaderRow(title, Collections.emptyList());
    }

    private JBPanel<JBPanel<?>> batchSectionHeader() {
        batchSelectAllButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll"), () -> setAllBatchColumns(true));
        batchSelectNoneButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectNone"), () -> setAllBatchColumns(false));
        batchClearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear"), this::clearBatchValues);
        return sectionHeaderRow(
                MpSqlVisualizerBundle.message("panel.batch.columns"),
                Arrays.asList(batchClearButton, batchSelectAllButton, batchSelectNoneButton));
    }

    private void setAllBatchColumns(boolean enabled) {
        BatchModel model = current != null ? current.getBatchModel() : null;
        if (model == null) return;
        for (BatchColumn col : model.getToggleColumns()) {
            if (col.getColumnName() != null) {
                if (enabled) enabledIds.add(col.getColumnName());
                else enabledIds.remove(col.getColumnName());
            }
        }
        for (JCheckBox cb : batchCheckBoxes) cb.setSelected(enabled);
        renderSql();
    }

    private JBPanel<JBPanel<?>> conditionSectionHeader() {
        selectAllButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll"), () -> setAllConditions(true));
        selectNoneButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectNone"), () -> setAllConditions(false));
        clearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear"), this::clearConditionValues);
        return sectionHeaderRow(
                MpSqlVisualizerBundle.message("panel.condition.section"),
                Arrays.asList(clearButton, selectAllButton, selectNoneButton));
    }

    private JButton compactButton(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(10.5f));
        b.setMargin(JBUI.insets(0, 6, 0, 6));
        b.addActionListener(e -> action.run());
        return b;
    }

    private void setAllConditions(boolean enabled) {
        List<ConditionView> views = current != null && current.getPreviewModel() != null
                ? current.getPreviewModel().getConditionViews() : Collections.emptyList();
        List<XmlConditionView> xmlViews = current != null && current.getXmlModel() != null
                ? current.getXmlModel().getConditionViews() : Collections.<XmlConditionView>emptyList();
        for (ConditionView view : views) {
            if (view.getId() != null) {
                if (enabled) enabledIds.add(view.getId());
                else enabledIds.remove(view.getId());
            }
        }
        for (XmlConditionView view : xmlViews) {
            if (view.id() != null) {
                if (enabled) enabledIds.add(view.id());
                else enabledIds.remove(view.id());
            }
        }
        for (JCheckBox cb : conditionCheckBoxes) cb.setSelected(enabled);
        renderSql();
    }

    private JBPanel<JBPanel<?>> buildConditionRow(ConditionView view) {
        String rawLabel = view.getLabel();
        String displayLabel = checkBoxText(rawLabel);
        JCheckBox checkBox = new JCheckBox(displayLabel, view.getId() != null && enabledIds.contains(view.getId()));
        checkBox.setToolTipText(displayLabel.equals(rawLabel) ? conditionTooltip(view) : rawLabel);
        checkBox.addActionListener(e -> {
            if (view.getId() != null) {
                if (checkBox.isSelected()) enabledIds.add(view.getId());
                else enabledIds.remove(view.getId());
            }
            renderSql();
        });
        conditionCheckBoxes.add(checkBox);
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.add(checkBox);
        List<String> sources = view.getValueSources();
        if (sources.isEmpty()) {
            String vs = view.getValueSource();
            if (vs != null) sources = Collections.singletonList(vs);
            else sources = Collections.emptyList();
        }
        for (String source : sources) {
            JBTextField field = conditionValueField(view, source);
            row.add(field);
            paramFields.add(field);
            conditionValueFields.add(field);
        }
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    private JBTextField conditionValueField(ConditionView view, String source) {
        JBTextField field = new JBTextField(8);
        field.setToolTipText("#" + source);
        field.setText(paramValues.getOrDefault(source, ""));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onConditionValueChanged(view, source, field.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onConditionValueChanged(view, source, field.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onConditionValueChanged(view, source, field.getText());
            }
        });
        return field;
    }

    private void clearConditionValues() {
        List<ConditionView> views = current != null && current.getPreviewModel() != null
                ? current.getPreviewModel().getConditionViews() : Collections.emptyList();
        for (ConditionView view : views) {
            List<String> vs = view.getValueSources();
            if (vs.isEmpty()) {
                String v = view.getValueSource();
                if (v != null) vs = Collections.singletonList(v);
                else vs = Collections.emptyList();
            }
            for (String source : vs) {
                paramValues.put(source, "");
                syncParamFieldText(source, "");
            }
        }
        renderSql();
        saveToHistory();
    }

    private void clearBatchValues() {
        BatchModel model = current != null ? current.getBatchModel() : null;
        if (model == null) return;
        List<BatchColumn> all = new ArrayList<>(model.getToggleColumns());
        all.addAll(model.getFixedColumns());
        for (BatchColumn col : all) {
            paramValues.put(col.getSource(), "");
            syncParamFieldText(col.getSource(), "");
        }
        renderSql();
        saveToHistory();
    }

    private String conditionTooltip(ConditionView view) {
        String fs = view.getFlagSource();
        return fs != null ? MpSqlVisualizerBundle.message("panel.condition.flag", fs) : "";
    }

    private void onConditionValueChanged(ConditionView view, String source, String text) {
        paramValues.put(source, text);
        renderSql();
        saveToHistory();
    }

    private JBPanel<JBPanel<?>> buildXmlConditionRow(XmlConditionView view) {
        String rawLabel = view.label();
        String displayLabel = checkBoxText(rawLabel);
        JCheckBox checkBox = new JCheckBox(displayLabel, view.id() != null && enabledIds.contains(view.id()));
        checkBox.setToolTipText(displayLabel.equals(rawLabel) ? MpSqlVisualizerBundle.message("panel.xml.condition") : rawLabel);
        checkBox.addActionListener(e -> {
            if (view.id() != null) {
                if (checkBox.isSelected()) enabledIds.add(view.id());
                else enabledIds.remove(view.id());
            }
            renderSql();
        });
        conditionCheckBoxes.add(checkBox);
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.add(checkBox);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    private JBPanel<JBPanel<?>> buildXmlForEachRow(XmlForEachView view, int labelWidth) {
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 8, 6));
        JBLabel label = columnLabel(view.collectionExpr(), labelWidth);
        label.setBorder(JBUI.Borders.empty(0, 8, 0, 0));
        row.add(label);
        JBTextField valuesField = new JBTextField(8);
        valuesField.setToolTipText("集合值（逗号分隔，如 2,3；留空 = 默认展开 " + XmlPreviewModel.DEFAULT_FOREACH_SIZE + " 项）");
        valuesField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onXmlForEachValuesChanged(view, valuesField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onXmlForEachValuesChanged(view, valuesField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onXmlForEachValuesChanged(view, valuesField.getText());
            }
        });
        row.add(valuesField);
        paramFields.add(valuesField);
        JCheckBox nullBox = new JCheckBox(MpSqlVisualizerBundle.message("panel.xml.foreach.null"), xmlForEachNulls.contains(view.collectionExpr()));
        nullBox.addActionListener(e -> {
            if (nullBox.isSelected()) xmlForEachNulls.add(view.collectionExpr());
            else xmlForEachNulls.remove(view.collectionExpr());
            renderSql();
        });
        row.add(nullBox);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    private void onXmlForEachValuesChanged(XmlForEachView view, String text) {
        String item = view.item();
        if (item == null) return;
        List<String> values = Arrays.stream(text.split(","))
                .map(s -> s.trim()).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        xmlForEachCounts.put(view.collectionExpr(), values.isEmpty() ? XmlPreviewModel.DEFAULT_FOREACH_SIZE : values.size());
        renderSql();
        List<ParameterMapping> keys = xmlMappings.stream()
                .filter(m -> m.property().startsWith("__frch_" + item + "_")).collect(Collectors.toList());
        if (values.isEmpty()) {
            for (ParameterMapping key : keys) paramValues.remove(key.property());
        } else {
            int i = 0;
            for (ParameterMapping key : keys) {
                if (i < values.size()) paramValues.put(key.property(), values.get(i));
                else paramValues.remove(key.property());
                i++;
            }
        }
        renderSql();
    }

    private JBPanel<JBPanel<?>> buildParamRow(String source, int labelWidth) {
        JBTextField field = new JBTextField(8);
        field.setToolTipText("#" + source);
        field.setText(paramValues.getOrDefault(source, ""));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onParamChanged(source, field.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onParamChanged(source, field.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onParamChanged(source, field.getText());
            }
        });
        paramFields.add(field);
        return new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 8, 6)) {{
            add(paramLabel(source, labelWidth));
            add(field);
            setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        }};
    }

    private JBLabel paramLabel(String source, int columnWidth) {
        return columnLabel("#" + source, columnWidth);
    }

    private JBLabel columnLabel(String fullText, int columnWidth) {
        JBLabel label = new JBLabel(fullText);
        FontMetrics metrics = label.getFontMetrics(UIUtil.getLabelFont());
        if (metrics.stringWidth(fullText) > columnWidth) {
            label.setToolTipText(fullText);
            label.setText(ellipsize(fullText, metrics, columnWidth));
        }
        label.setPreferredSize(new Dimension(columnWidth, label.getPreferredSize().height));
        return label;
    }

    private int labelColumnWidth(List<String> texts) {
        if (texts.isEmpty()) return 140;
        FontMetrics metrics = getFontMetrics(UIUtil.getLabelFont());
        int viewport = paramScroll.getViewport().getWidth();
        int maxWidth = viewport <= 0 ? 260 : clamp(viewport - 128, 80, 260);
        int max = 0;
        for (String t : texts) max = Math.max(max, metrics.stringWidth(t));
        return clamp(max, 80, maxWidth);
    }

    private String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        String mark = "…";
        if (metrics.stringWidth(text) <= maxWidth) return text;
        int tailLen = (int) (text.length() * 0.3);
        tailLen = Math.max(tailLen, 4);
        tailLen = Math.min(tailLen, text.length() - 1);
        String tail = text.substring(text.length() - tailLen);
        int head = text.length() - tailLen;
        while (head > 0 && metrics.stringWidth(text.substring(0, head) + mark + tail) > maxWidth) head--;
        return text.substring(0, head) + mark + tail;
    }

    private int checkBoxMaxWidth() {
        int viewport = paramScroll.getViewport().getWidth();
        return viewport <= 0 ? 280 : clamp(viewport - 56, 100, 280);
    }

    private Font checkBoxFontCache = null;

    private Font checkBoxFont() {
        if (checkBoxFontCache == null) checkBoxFontCache = new JCheckBox().getFont();
        return checkBoxFontCache;
    }

    private String checkBoxText(String raw) {
        return checkBoxText(raw, checkBoxMaxWidth());
    }

    private String checkBoxText(String raw, int maxWidth) {
        FontMetrics metrics = getFontMetrics(checkBoxFont());
        return metrics.stringWidth(raw) > maxWidth ? ellipsize(raw, metrics, maxWidth) : raw;
    }

    private void onParamChanged(String source, String value) {
        paramValues.put(source, value);
        renderSql();
        saveToHistory();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    // ------------------------------------------------------------------
    // P-05：参数批量导入 / 历史召回
    // ------------------------------------------------------------------

    public void importParamsFromClipboard() {
        Project proj = project;
        if (proj == null) return;
        String preFill = paramValues.isEmpty() ? null : serializeParamsToJson();
        new ParamImportDialog(proj, preFill, new ParamImportDialog.OnConfirm() {
            @Override
            public void confirm(String text) {
                importParams(ParamImportParser.INSTANCE.parse(text));
            }
        }).show();
    }

    private String serializeParamsToJson() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : paramValues.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (!first) sb.append(",\n");
            first = false;
            String escaped = e.getValue().replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("  \"").append(e.getKey()).append("\": \"").append(escaped).append("\"");
        }
        return sb.length() == 0 ? "" : "{\n" + sb + "\n}";
    }

    void importParams(Map<String, String> params) {
        if (params.isEmpty()) {
            showImportResult(0, 0, Collections.emptyList(), true);
            return;
        }
        Set<String> allSources = buildAllSources();
        int matched = 0;
        List<String> unmatchedKeys = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String source = resolveSource(e.getKey(), allSources);
            if (source != null) {
                paramValues.put(source, e.getValue());
                syncParamFieldText(source, e.getValue());
                matched++;
            } else {
                unmatchedKeys.add(e.getKey());
            }
        }
        renderSql();
        showImportResult(matched, unmatchedKeys.size(), unmatchedKeys, false);
    }

    private Set<String> buildAllSources() {
        PreviewResult result = current;
        if (result == null) return Collections.emptySet();
        Set<String> sources = new LinkedHashSet<>();
        sources.addAll(result.getUnresolvedParams());
        SqlPreviewModel pm = result.getPreviewModel();
        if (pm != null) {
            for (ConditionView view : pm.getConditionViews()) {
                List<String> vs = view.getValueSources();
                if (vs.isEmpty()) {
                    String v = view.getValueSource();
                    if (v != null) sources.add(v);
                } else {
                    sources.addAll(vs);
                }
            }
        }
        BatchModel bm = result.getBatchModel();
        if (bm != null) {
            for (BatchColumn c : bm.getToggleColumns()) sources.add(c.getSource());
            for (BatchColumn c : bm.getFixedColumns()) sources.add(c.getSource());
        }
        XmlPreviewModel xm = result.getXmlModel();
        if (xm != null) {
            for (XmlForEachView v : xm.getForEachViews()) sources.add(v.collectionExpr());
        }
        return sources;
    }

    private String resolveSource(String key, Set<String> sources) {
        if (sources.contains(key)) return key;
        String stripped = key.startsWith("#") ? key.substring(1) : key;
        if (sources.contains(stripped)) return stripped;
        String snake = CaseFormatUtil.camelToSnake(stripped);
        if (sources.contains(snake)) return snake;
        String camel = CaseFormatUtil.snakeToCamel(stripped);
        if (sources.contains(camel)) return camel;
        return null;
    }

    private void syncParamFieldText(String source, String value) {
        int idx = currentParamFieldIndex(source);
        if (idx >= 0 && idx < paramFields.size()) {
            paramFields.get(idx).setText(value);
        }
    }

    private int currentParamFieldIndex(String source) {
        return new ArrayList<>(paramValues.keySet()).indexOf(source);
    }

    private void showImportResult(int matched, int unmatched, List<String> unmatchedKeys, boolean isEmptyClipboard) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        String title = MpSqlVisualizerBundle.message("panel.param.import.result.title");
        Project proj = project;
        if (proj == null) return;
        if (isEmptyClipboard) {
            Messages.showErrorDialog(proj, MpSqlVisualizerBundle.message("panel.param.import.result.empty"), title);
        } else if (unmatched == 0) {
            Messages.showInfoMessage(proj, MpSqlVisualizerBundle.message("panel.param.import.result.success", matched), title);
        } else {
            Messages.showMessageDialog(proj,
                    MpSqlVisualizerBundle.message("panel.param.import.result.partial", matched, unmatched, String.join(", ", unmatchedKeys)),
                    title, Messages.getWarningIcon());
        }
    }

    private String historyCacheKey() {
        PreviewResult result = current;
        if (result == null) return null;
        return paramHistoryCache.computeKey(result.getTableName(), result.getSql());
    }

    private void recallFromHistory() {
        String key = historyCacheKey();
        if (key == null) return;
        Map<String, String> history = paramHistoryCache.recall(key);
        if (history == null || history.isEmpty()) return;
        int applied = 0;
        for (Map.Entry<String, String> e : history.entrySet()) {
            String source = e.getKey();
            String value = e.getValue();
            if (paramValues.get(source) == null || paramValues.get(source).isEmpty()) {
                paramValues.put(source, value);
                syncParamFieldText(source, value);
                applied++;
            }
        }
        if (applied > 0) renderSql();
    }

    private void saveToHistory() {
        historyAlarm.restart();
    }

    private void flushHistoryToCache() {
        String key = historyCacheKey();
        if (key == null) return;
        paramHistoryCache.save(key, new LinkedHashMap<>(paramValues));
    }

    private Set<Integer> detectInClauseParams(String sql) {
        Set<Integer> result = new LinkedHashSet<>();
        int paramIndex = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '?') {
                String prefix = sql.substring(0, i).trim().toUpperCase();
                if (prefix.endsWith("IN (") || prefix.endsWith("IN(")) result.add(paramIndex);
                paramIndex++;
            }
            i++;
        }
        return result;
    }

    private String backfillParamsBySource(String sql, List<String> sources) {
        return backfillParamsBySource(sql, sources, Collections.emptyList());
    }

    private String backfillParamsBySource(String sql, List<String> sources, List<ParamHint> hints) {
        if (sources == null || sources.isEmpty()) return sql;
        List<ParamHint> safeHints = hints != null ? hints : Collections.emptyList();
        StringBuilder sb = new StringBuilder(sql.length() + 32);
        int paramIndex = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '?') {
                String source = paramIndex < sources.size() ? sources.get(paramIndex) : null;
                String v = source != null ? paramValues.get(source) : null;
                sb.append(renderParamValue(v, paramIndex < safeHints.size() ? safeHints.get(paramIndex) : null));
                paramIndex++;
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    private String renderParamValue(String v, ParamHint hint) {
        if (v == null || v.isBlank()) return "?";
        LikePosition like = hint != null ? hint.getLikePosition() : null;
        if (hint != null && hint.getInList()) {
            List<String> parts = Arrays.stream(v.split(",")).map(s -> s.trim()).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (parts.isEmpty()) return "?";
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < parts.size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append(renderSingleValue(parts.get(k), hint));
            }
            return sb.toString();
        }
        String escaped = v.replace("'", "''");
        if (like != null) {
            switch (like) {
                case ANYWHERE:
                    return "'%" + escaped + "%'";
                case LEFT:
                    return "'%" + escaped + "'";
                case RIGHT:
                    return "'" + escaped + "%'";
                case NONE:
                    return "'" + escaped + "'";
                default:
                    return renderSingleValue(v, hint);
            }
        }
        return renderSingleValue(v, hint);
    }

    private String renderSingleValue(String raw, ParamHint hint) {
        String escaped = raw.replace("'", "''");
        if (hint != null && hint.getQuote()) return "'" + escaped + "'";
        if (hint != null && !hint.getQuote()) return raw.trim();
        String trimmed = raw.trim();
        return NUMBER_REGEX.matcher(trimmed).matches() ? trimmed : "'" + escaped + "'";
    }

    // ------------------------------------------------------------------
    // 元信息
    // ------------------------------------------------------------------

    private void updateMeta(PreviewResult result) {
        List<String> metaParts = new ArrayList<>();
        if (result.getWrapperType() != null) {
            metaParts.add(MpSqlVisualizerBundle.message("panel.meta.type") + ": " + result.getWrapperType().name());
        }
        if (result.getTableName() != null) {
            metaParts.add(MpSqlVisualizerBundle.message("panel.meta.table") + ": " + result.getTableName());
        }
        XmlPreviewModel xml = result.getXmlModel();
        if (xml != null) {
            metaParts.add("XML " + xml.getKind().name().toLowerCase() + ": " + xml.getStatementId());
            if (xml.getParameterType() != null) {
                metaParts.add("parameterType: " + xml.getParameterType());
            }
        }
        String meta = String.join("   ", metaParts);
        String xmlWarning = null;
        if (xml != null && xmlErrors != null && !xmlErrors.isEmpty()) {
            xmlWarning = String.join("；", xmlErrors);
        }
        currentWarning = xmlWarning != null ? xmlWarning : result.getWarning();
        String warningLine = currentWarning != null ? "   ⚠ " + currentWarning : "";
        String perfLine = buildPerfLine(result);
        String displayText;
        if (!perfLine.isEmpty()) {
            displayText = "<html>" + meta + warningLine + "<br>" + perfLine + "</html>";
        } else {
            displayText = meta + warningLine;
        }
        metaLabel.setText(displayText);
        metaLabel.setToolTipText(meta + warningLine + perfLine);
        metaLabel.setForeground(currentWarning != null
                ? JBColor.namedColor("ErrorForeground", JBColor.RED)
                : JBUI.CurrentTheme.Label.foreground());
        if (errorCopyButton != null) errorCopyButton.setVisible(currentWarning != null);
    }

    private String buildPerfLine(PreviewResult result) {
        List<PerfWarning> warnings = result.getPerfWarnings();
        if (warnings == null || warnings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < warnings.size(); idx++) {
            PerfWarning warning = warnings.get(idx);
            boolean isError = warning.getSeverity() == PerfSeverity.ERROR || warning.getSeverity() == PerfSeverity.WARN;
            String prefix = isError ? "⚠ " : "ℹ ";
            String localized = localizeWarning(warning);
            String color = isError ? "#E05555" : "#3B82F6";
            sb.append("   <font color=\"").append(color).append("\">").append(prefix).append(localized).append("</font>");
            if (idx != warnings.size() - 1) sb.append("；");
        }
        return sb.toString();
    }

    private String localizeWarning(PerfWarning warning) {
        List<String> args = warning.getMessageArgs();
        String[] arr = args != null ? args.toArray(new String[0]) : new String[0];
        if (arr.length > 0) return MpSqlVisualizerBundle.message(warning.getMessageKey(), (Object[]) arr);
        return MpSqlVisualizerBundle.message(warning.getMessageKey());
    }

    public boolean copyError() {
        if (currentWarning == null) return false;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(currentWarning), null);
        return true;
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        releaseEditor();
    }

    @Override
    public void dispose() {
        previewDisposed = true;
        Disposer.dispose(this);
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    Editor editorForTest() {
        return editor;
    }

    PsiFile psiFileForTest() {
        return psiFile;
    }

    PreviewResult currentForTest() {
        return current;
    }

    boolean compactForTest() {
        return compactMode;
    }

    void embedConsoleForTest(JComponent component) {
        embedConsole(component);
    }

    JComponent embeddedConsoleForTest() {
        return embeddedConsole;
    }

    JComponent consoleHostForTest() {
        return consoleHost;
    }

    JComponent editorHolderForTest() {
        return editorHolder;
    }

    Integer lastConsoleEmbeddedIdForTest() {
        return lastConsoleEmbeddedId;
    }

    public void setSourceLocation(String loc) {
        sourceLocation = loc;
    }

    public String currentSourceLocation() {
        return sourceLocation;
    }

    public String editorDocumentText() {
        return editor != null ? editor.getDocument().getText() : null;
    }

    public List<String> unresolvedParams() {
        return current != null ? current.getUnresolvedParams() : Collections.emptyList();
    }

    public Map<String, String> paramValuesSnapshot() {
        return new LinkedHashMap<>(paramValues);
    }

    public String currentTableName() {
        return current != null ? current.getTableName() : null;
    }

    public String currentDialect() {
        return current != null && current.getPreviewModel() != null ? current.getPreviewModel().getDialect().getName() : null;
    }

    JBTextField paramFieldForTest(int index) {
        return index >= 0 && index < paramFields.size() ? paramFields.get(index) : null;
    }

    String metaLabelForTest() {
        return metaLabel.getText();
    }

    JButton errorCopyButtonForTest() {
        return errorCopyButton;
    }

    JBLabel paramRowLabelForTest(int index) {
        List<JBPanel<?>> labeled = new ArrayList<>();
        for (Component c : paramPanel.getComponents()) {
            if (c instanceof JBPanel) {
                JBPanel<?> row = (JBPanel<?>) c;
                for (Component cc : row.getComponents()) {
                    if (cc instanceof JBLabel) {
                        labeled.add(row);
                        break;
                    }
                }
            }
        }
        if (index >= labeled.size()) return null;
        JBPanel<?> row = labeled.get(index);
        for (Component cc : row.getComponents()) {
            if (cc instanceof JBLabel) return (JBLabel) cc;
        }
        return null;
    }

    JCheckBox conditionCheckboxForTest(int index) {
        return index >= 0 && index < conditionCheckBoxes.size() ? conditionCheckBoxes.get(index) : null;
    }

    JBTextField conditionValueFieldForTest(int index) {
        return index >= 0 && index < conditionValueFields.size() ? conditionValueFields.get(index) : null;
    }

    JCheckBox batchCheckboxForTest(int index) {
        return index >= 0 && index < batchCheckBoxes.size() ? batchCheckBoxes.get(index) : null;
    }

    JButton batchSelectAllButtonForTest() {
        return batchSelectAllButton;
    }

    JButton batchSelectNoneButtonForTest() {
        return batchSelectNoneButton;
    }

    Set<String> enabledIdsForTest() {
        return new LinkedHashSet<>(enabledIds);
    }

    JButton selectAllButtonForTest() {
        return selectAllButton;
    }

    JButton selectNoneButtonForTest() {
        return selectNoneButton;
    }

    JButton clearButtonForTest() {
        return clearButton;
    }

    JButton batchClearButtonForTest() {
        return batchClearButton;
    }

    JSplitPane splitPaneForTest() {
        return findSplitPane(this);
    }

    private static JSplitPane findSplitPane(java.awt.Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JSplitPane && ((JSplitPane) child).getOrientation() == JSplitPane.HORIZONTAL_SPLIT) {
                return (JSplitPane) child;
            }
            if (child instanceof java.awt.Container) {
                JSplitPane found = findSplitPane((java.awt.Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    AnAction reformatActionForTest() {
        return reformatAction;
    }

    void importParamsForTest(Map<String, String> params) {
        importParams(params);
    }

    ParamHistoryCache paramHistoryCacheForTest() {
        return paramHistoryCache;
    }

    Map<String, String> paramValuesForTest() {
        return new LinkedHashMap<>(paramValues);
    }

    void flushHistoryForTest() {
        flushHistoryToCache();
    }

    String serializeParamsToJsonForTest() {
        return serializeParamsToJson();
    }
}
