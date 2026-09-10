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
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
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
import com.intellij.ui.awt.RelativePoint;
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
import java.awt.Insets;
import java.awt.Point;
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

    /** T-RefreshDbSteal：去抖窗口内被跳过的释放 → 延迟复查间隔（ms）。取与去抖门槛同值：
     *  复查时刻距最近一次重挂必然已越过门槛，`since` 复查不会误拦。 */
    private static final long REHOST_RETRY_DELAY_MS = 800L;

    /** T-PreviewFlash：EDT 等待预热产物的轮询间隔与超时（毫秒）。 */
    static final long PREWARM_POLL_MS = 20L;
    /** T-PreviewSlow F1：把「EDT 同步自旋等预热产物」的预算从 2000ms 砍到 60ms——
     *  冷启动时预热线程（同步 VFS refresh + PSI findFile）常跑几百毫秒到数秒，EDT 干等
     *  到 2000ms 会把延迟烧进 UI 线程（真机日志双击→createEditor 达 3.07s）。降到经验值
     *  60ms：预热线程已就绪（面板再开/热态）立刻命中走 PSI 主路径，冷启动第一拍直接降级
     *  纯内存编辑器不烧 EDT；预热完成后下次打开即自愈走回 PSI 高亮/补全路径。 */
    static final long PREWARM_WAIT_MS = 60L;

    /** T-PreviewTopOverlap F3：元信息宽度预算下限（px）——极窄时保底可读，超出仍由 ellipsize 收。 */
    private static final int MIN_META_BUDGET_PX = 80;

    private static final Pattern NUMBER_REGEX = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");

    private final Project project;

    private final JBLabel metaLabel = new JBLabel();

    // T-PreviewTopOverlap F1/F3：顶部行与工具栏提升为字段——布局断言（topRowForTest/toolbarForTest）
    // 与宽度预算计算（applyMetaTruncation 需工具栏理想宽）使用
    private JBPanel<JBPanel<?>> topRow = null;
    private JBPanel<JBPanel<?>> toolbarPanel = null;

    // T-PreviewTopOverlap F3：元信息原文缓存——metaLineText 第一行（meta+警示，纯文本）、
    // metaPerfSegments 第二行分段（可见文本+颜色）；tooltip 恒全文，label 文本按预算重拼
    private String metaLineText = "";
    private List<MetaPerfSegment> metaPerfSegments = List.of();
    private int lastMetaBudget = -1;

    /** F3：perf 建议分段（可见文本 + HTML 颜色值），截断时按预算丢尾段。 */
    private record MetaPerfSegment(String text, String color) {
    }

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

    /** T-RefreshDbSteal：当前挂接的 rehost HierarchyListener 引用——detach/dispose 时显式移除。
     *  旧实现「监听随组件废弃，无需显式解绑」的假设在共享控制台场景不成立：组件被新面板复用后
     *  旧面板的监听仍存活，parent==null 时会把共享控制台抢回已废弃面板（真机日志 6 连抢实锤）。 */
    private java.awt.event.HierarchyListener rehostListener = null;

    /** T-RefreshDbSteal：去抖窗口内被跳过的释放事件 → 已调度延迟复查（单次标志，防重复调度）。 */
    private boolean rehostRetryScheduled = false;

    private Document nativeEditorDoc = null;

    private PreviewResult current = null;
    private boolean compactMode = false;

    /**
     * T-PreviewDisplayMode：编辑器显示模式。
     *
     * 编辑器文本是「模型派生视图」——参数输入/条件勾选/foreach 次数/压缩切换等任何变更都会
     * renderSql 全量重建并整篇覆盖文档，因此<b>一次性文档变换必被下一次重建抹掉</b>（早期三个
     * 格式按钮直接 setText 的写法：竖向排列/关键字换行无条件丢失，格式化仅在设置开启且非压缩
     * 时因 showSql 里那句 format() 而"看似保留"）。模式改存状态、由 applyDisplayMode 在
     * 渲染管线中统一套用，重建后形态保持。
     */
    private PreviewDisplayMode displayMode = PreviewDisplayMode.NORMAL;

    /** T-PreviewDisplayMode：预览编辑器显示模式（见 displayMode 字段注释）。 */
    public enum PreviewDisplayMode {
        NORMAL, FORMAT, KEYWORD_WRAP, VERTICAL
    }

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

        // T-PreviewTopOverlap F1：metaLabel 从 WEST 迁 CENTER——BorderLayout 的 WEST/EAST 各按
        // 理想宽整块贴边绝不收缩，两偏好宽之和超容器宽时直接互相重叠（用户真机截图实锤：元信息
        // 文字压进按钮行）；CENTER 只拿剩余宽度，与 EAST 结构性零重叠。宽度不足由 F3 截断兜底。
        metaLabel.setBorder(JBUI.Borders.empty(0, 8, 4, 8));
        topRow = new JBPanel<JBPanel<?>>(new BorderLayout());
        topRow.add(metaLabel, BorderLayout.CENTER);
        toolbarPanel = toolbar();
        topRow.add(toolbarPanel, BorderLayout.EAST);
        topRow.setBorder(JBUI.Borders.empty(4, 8, 2, 8));
        add(topRow, BorderLayout.NORTH);
        // T-PreviewTopOverlap F3：宽度预算截断跟随尺寸变化（首显 + 拉伸/缩窄；宽度未变短路）
        topRow.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applyMetaTruncation();
            }

            @Override
            public void componentShown(ComponentEvent e) {
                applyMetaTruncation();
            }
        });

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
     * T-PreviewFlash：EDT 上阻塞等待预热产物（有界，避免与预热线程竞态导致 createEditor 无文件可用）。
     * 等待超时（理论上仅在预热线程失败/极端慢时）返回 null，由调用方走既有失败提示。
     */
    private Pair<VirtualFile, PsiFile> awaitPrewarmedTempFile() {
        long waited = 0L;
        while (true) {
            Pair<VirtualFile, PsiFile> ready = prewarmedTempFile;
            if (ready != null) {
                prewarmedTempFile = null;
                return ready;
            }
            if (waited >= PREWARM_WAIT_MS) return null;
            try {
                Thread.sleep(PREWARM_POLL_MS);
                waited += PREWARM_POLL_MS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
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
        errorCopyButton = smallButton(MpSqlVisualizerBundle.message("panel.copyError"), () -> {
            if (copyError()) {
                // T-ExecCopyFix：复制成功反馈（对齐 SqlClickPreview.flashCopied 对号语义）
                if (errorCopyButton != null) CopyFeedback.flashCopied(errorCopyButton);
            }
        });
        errorCopyButton.setVisible(false);
        bar.add(errorCopyButton);
        modeButton = smallButton(modeButtonText(), () -> {
            toggleCompact();
            modeButton.setText(modeButtonText());
        });
        bar.add(modeButton);
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.format"), this::formatSql));
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.keywordWrap"), this::formatClauseLine));
        // T-VStack：竖向排列（INSERT 列清单/VALUES 逐行竖排 + 值行尾列名配对标注）
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.vertical"), this::formatVertical));
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.refresh"), this::refreshView));
        // T-PreviewTopOverlap F2：「更多」折叠菜单入口（低频动作收纳）
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.more"), this::showMoreActionsPopup));
        return bar;
    }

    /** F2：「更多」折叠菜单的三项低频动作（文案键沿用原工具栏按钮，执行体一字不差）。 */
    private List<kotlin.Pair<String, Runnable>> moreActions() {
        List<kotlin.Pair<String, Runnable>> actions = new ArrayList<>();
        actions.add(new kotlin.Pair<>(MpSqlVisualizerBundle.message("panel.param.import"), (Runnable) this::importParamsFromClipboard));
        actions.add(new kotlin.Pair<>(MpSqlVisualizerBundle.message("panel.compare"), (Runnable) this::showSqlDiff));
        actions.add(new kotlin.Pair<>(MpSqlVisualizerBundle.message("panel.share"), (Runnable) this::showShareDialog));
        return actions;
    }

    /**
     * F2：弹出「更多」折叠菜单（锚定按钮下方；JBPopupFactory + BaseListPopupStep 路线为本项目
     * 现网先例：SqlClickPreview / DataSourceSelector）。show 依赖真实屏幕，测试只构造 step 不弹层。
     */
    private void showMoreActionsPopup() {
        List<kotlin.Pair<String, Runnable>> actions = moreActions();
        JButton anchor = null;
        for (Component c : toolbarPanel.getComponents()) {
            if (c instanceof JButton) anchor = (JButton) c;
        }
        if (anchor == null) return;
        JBPopupFactory.getInstance()
                .createListPopup(moreActionsStep(actions))
                .show(new RelativePoint(anchor, new Point(0, anchor.getHeight())));
    }

    /** F2：构造折叠菜单 step（独立可测——items 与 onChosen 委托可直接断言，无需真实弹层）。 */
    private BaseListPopupStep<String> moreActionsStep(List<kotlin.Pair<String, Runnable>> actions) {
        List<String> titles = actions.stream().map(kotlin.Pair::getFirst).collect(Collectors.toList());
        return new BaseListPopupStep<String>(null, titles) {
            @Override
            public PopupStep<?> onChosen(String selectedValue, boolean finalChoice) {
                for (kotlin.Pair<String, Runnable> action : actions) {
                    if (action.getFirst().equals(selectedValue)) {
                        action.getSecond().run();
                        break;
                    }
                }
                return PopupStep.FINAL_CHOICE;
            }
        };
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
            // T-RefreshDbSteal F4：isPrepared 只证明插件级共享控制台存在，不证明它还挂在本面板——
            // 失联时 executePrepared 会把 SQL 执行到别的弹窗/Database 工具窗的控制台（症状③）。
            // 先走恢复链把控制台重嵌（prepareConsole 复用链同步 SQL + onConsole 重嵌），
            // 仍就绪才执行——恢复链自身失败时 isPrepared 已被全链重建路径覆盖，回退下方
            // selectAndExecute 全链兜底
            if (!consoleAttachedToMe()) {
                Logger.getInstance(SqlPreviewPanel.class)
                        .warn("[M36] executeSql: prepared console detached from this panel → recover before execute");
                recoverConsole(sql);
                if (SqlExecutor.isPrepared() && !consoleAttachedToMe()) {
                    // 恢复未落地（异步链未回调）：放弃 executePrepared，落全链（全链会重建/复用
                    // 共享控制台并经 onConsole 重嵌本面板），避免打在别人的控制台上
                    SqlExecutor.execute(
                            proj, sql, null, isQuery,
                            consoleComponent -> embedConsole(consoleComponent, true),
                            consoleEditor -> onNativeEditorReady(consoleEditor),
                            errorMsg -> resultPanel.showError(errorMsg));
                    return;
                }
            }
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

    /**
     * M36.5：发起控制台默认准备（面板展示时调用，不执行）——
     * Ultimate 下原生控制台即刻可见（▶ 工具栏/Tx Auto/Playground 等 DBN 原生元素常驻预览面板）；
     * Community 下静默回退自研编辑器；无数据源静默引导（不弹错误打断预览）。
     *
     * <p>M36.5.25g 试过回退"预览即 DBN 控制台"——但用户明确要求<b>预览阶段也显示 DBN 工具栏</b>
     * （▶ 工具栏需始终可见），故恢复 prepareConsole 调用。
     *
     * <p>T-RefreshDbSteal F4：兼作失联恢复入口 {@link #recoverConsole}——回退编辑器在场时以回退编辑器
     * 的文本发起准备（准备链复用共享控制台会同步该 SQL），面板无任何编辑器时静默跳过。
     */
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

    /**
     * T-RefreshDbSteal F4：所有权判定——本面板记录的控制台实例是否仍在自己的 consoleHost 树内。
     * false = 控制台已被其他面板收养/或 DBN 释放后未重挂成功，此时任何"写入 nativeEditorDoc /
     * executePrepared"都是打在别人的控制台上（三症状公共前置条件）。
     */
    boolean consoleAttachedToMe() {
        JComponent console = embeddedConsole;
        if (console == null) return false;
        return console.getParent() != null && consoleHost.isAncestorOf(console);
    }

    /**
     * T-RefreshDbSteal F4：失联恢复——共享控制台已不在本面板时重新走准备链把它要回来。
     * {@code prepareConsole} 的 onConsole 回调经 {@link #embedConsole} 幂等重嵌（M36.5.5 复用链会同步 SQL
     * 进控制台文档），准备链失败静默（与 prepareDefaultConsole 同语义），本面板此时已落在
     * 回退编辑器上，显示不悬空。
     */
    private void recoverConsole(String sqlForRecovery) {
        if (project == null) return;
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] recoverConsole triggered: len=" + sqlForRecovery.length()
                        + ", embedded=" + System.identityHashCode(embeddedConsole));
        prepareDefaultConsole();
    }

    /** M36.5.39-A3：DBN 释放监听（去抖重挂）。 */
    private void attachRehostListener(JComponent component) {
        if (rehostListener != null) {
            component.removeHierarchyListener(rehostListener);
        }
        java.awt.event.HierarchyListener listener = e -> {
            boolean parentChanged = (e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0L;
            if (!parentChanged || e.getComponent().getParent() != null || e.getComponent() != embeddedConsole) return;
            long now = System.currentTimeMillis();
            long since = now - lastRehostAt;
            if (since >= 0 && since < REHOST_MIN_INTERVAL_MS) {
                // T-RefreshDbSteal：去抖 skip 有日志 + 单次延迟复查兜底（旧实现静默 return 是
                // 控制台永久悬空的零日志黑洞——「间隔必然 > 门槛」假设被 DBN dance 收尾证伪）
                Logger.getInstance(SqlPreviewPanel.class)
                        .info("[M36] re-host debounced (since=" + since + "ms, id="
                                + System.identityHashCode(e.getComponent()) + ") → retry scheduled");
                if (e.getComponent() instanceof JComponent) {
                    scheduleRehostRetry((JComponent) e.getComponent());
                }
                return;
            }
            lastRehostAt = now;
            Logger.getInstance(SqlPreviewPanel.class).info("[M36] console component released by DBN (id="
                    + System.identityHashCode(e.getComponent()) + ") → re-host (since=" + since + "ms)");
            ApplicationManager.getApplication().invokeLater(
                    () -> rehostConsoleForTest((JComponent) e.getComponent()), ModalityState.any());
        };
        rehostListener = listener;
        component.addHierarchyListener(listener);
    }

    /** T-RefreshDbSteal：去抖窗口内被跳过的释放 → 延迟复查一次（仍失联则幂等重挂）。
     *  单发 Swing Timer（flashCopied 同款范式）：invokeLater 无延迟，复查会永远落在去抖
     *  窗口内被自己的 `since` 拦截，等于从未重试。 */
    private void scheduleRehostRetry(JComponent component) {
        if (rehostRetryScheduled) return;
        rehostRetryScheduled = true;
        javax.swing.Timer timer = new javax.swing.Timer((int) REHOST_RETRY_DELAY_MS, e -> {
            rehostRetryScheduled = false;
            // 复查条件与监听器同款：仍是当前嵌入实例且仍无 parent（被别的容器收养则不抢）
            if (component != embeddedConsole || component.getParent() != null) return;
            long since = System.currentTimeMillis() - lastRehostAt;
            if (since >= 0 && since < REHOST_MIN_INTERVAL_MS) return;
            lastRehostAt = System.currentTimeMillis();
            Logger.getInstance(SqlPreviewPanel.class)
                    .info("[M36] re-host retry fired (id=" + System.identityHashCode(component) + ", since=" + since + "ms)");
            rehostConsoleForTest(component);
        });
        timer.setRepeats(false);
        timer.start();
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

    /**
     * M36：摘除已内嵌的控制台组件（清空预览时调用；JdbcConsole 生命周期由 IDE 管理）。
     * M36.5.39-A2：只清空 consoleHost 子层，容器保留——再次嵌入复用同一容器。
     * T-RefreshDbSteal：同时移除本面板挂接的 rehost 监听——共享控制台被新面板接管后，
     * 本面板的监听必须随之失效（旧实现不移除，僵尸监听把控制台抢回废弃面板）。
     */
    private void detachConsole() {
        if (embeddedConsole != null) {
            if (rehostListener != null) {
                embeddedConsole.removeHierarchyListener(rehostListener);
            }
            consoleHost.remove(embeddedConsole);
        }
        embeddedConsole = null;
        rehostListener = null;
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

    /**
     * 刷新视图（M19.3）：模式<b>复位为与设置「SQL 格式化输出」对齐</b>（默认多行）后重渲染编辑器——
     * 用户实测：点刷新后 SQL 仍停留在压缩模式（此前手动切换/设置快照的压缩被保留）；
     * 刷新 = 回到默认状态（参数与回填保留，仅模式复位）。
     *
     * T-PreviewDisplayMode：显示模式一并复位为 NORMAL（即回到设置派生形态——设置开启为格式化、
     * 关闭为原样），与「刷新=回到默认状态」语义一致，竖排/关键字换行不跨刷新残留。
     */
    public void refreshView() {
        // T-RefreshDbSteal：刷新是唯一常规触达共享控制台文档的按钮（WriteCommandAction），
        // 入口记 4 态快照——真机复现时对照判归属
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] refreshView entry: compactMode=" + compactMode
                        + ", nativeDoc=" + (nativeEditorDoc != null)
                        + ", editor=" + (editor != null)
                        + ", embedded=" + System.identityHashCode(embeddedConsole)
                        + ", attachedToMe=" + consoleAttachedToMe());
        compactMode = !formatSqlSetting();
        displayMode = PreviewDisplayMode.NORMAL;
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

    /**
     * T-VPairAnno：竖向排列 VALUES 行尾配对列名标注开关；环境异常兜底 true（保持默认标注）。
     * 与 formatSqlSetting 同构——保存后的下一次渲染生效，不做已开弹窗即时推送。
     */
    private boolean verticalPairAnnotationSetting() {
        try {
            return PluginSettings.getInstance().getState().getVerticalPairAnnotation();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * T-PreviewDisplayMode：按当前显示模式变换渲染文本（showSql 的唯一收敛点调用）。
     *
     * 优先级：压缩模式最高——压缩语义是"全部挤一行"，而模式变换会引入换行，二者相抵故短路；
     * 其余按模式分派：NORMAL 交「SQL 格式化输出」设置决定（保持设置关闭时不格式化的既有语义）、
     * FORMAT 强制格式化、KEYWORD_WRAP / VERTICAL 走对应变换（二者对非 INSERT 已内含 format，
     * 无需叠加）。
     */
    private String applyDisplayMode(String sql) {
        if (compactMode) return sql;
        switch (displayMode) {
            case NORMAL:
                return formatSqlSetting() ? SqlFormatter.INSTANCE.format(sql) : sql;
            case FORMAT:
                return SqlFormatter.INSTANCE.format(sql);
            case KEYWORD_WRAP:
                return SqlFormatter.INSTANCE.formatClauseLine(sql);
            case VERTICAL:
                return SqlFormatter.INSTANCE.formatVertical(sql, verticalPairAnnotationSetting());
            default:
                return sql;
        }
    }

    /**
     * T-PreviewDisplayMode：切换编辑器显示模式并重渲染（格式族按钮与 Ctrl+Alt+L 快捷键的统一入口）。
     *
     * 与旧实现的区别：不再直接对文档做一次性变换，而是记录模式后交渲染管线产出——这样参数输入、
     * 条件勾选、foreach 次数增减等任何重建都不会再把竖排/关键字换行形态抹掉。
     *
     * 压缩模式下切换格式族模式会一并退出压缩：否则模式被压缩短路、点击无可见效果（且"压缩态 +
     * 已格式化文档"是自相矛盾的中间态）。
     */
    public void setDisplayMode(PreviewDisplayMode mode) {
        displayMode = mode;
        if (compactMode) {
            compactMode = false;
            if (modeButton != null) modeButton.setText(modeButtonText());
        }
        renderSql();
    }

    /**
     * 格式化当前编辑器 SQL（关键字大写 / 子句换行 / 条件缩进，SqlFormatter）。
     * T-PreviewDisplayMode：等价于「格式化」按钮——设 FORMAT 模式后由渲染管线套用（形态在参数
     * 变更后保持）；不再需要直接改文档，故改走状态路径。
     */
    public void formatSql() {
        setDisplayMode(PreviewDisplayMode.FORMAT);
    }

    /** 关键字换行格式化：子句顶格换行、同行内容压一行（SqlFormatter.formatClauseLine）。 */
    private void formatClauseLine() {
        setDisplayMode(PreviewDisplayMode.KEYWORD_WRAP);
    }

    /**
     * 竖向排列格式化：INSERT 列清单/VALUES 逐行竖排、值行尾标注配对列名（SqlFormatter.formatVertical）。
     * T-VPairAnno：标注是否追加由设置 verticalPairAnnotation 控制（默认开）。
     */
    public void formatVertical() {
        setDisplayMode(PreviewDisplayMode.VERTICAL);
    }

    private Shortcut[] reformatShortcuts() {
        Shortcut[] fromKeymap = KeymapManager.getInstance().getActiveKeymap().getShortcuts("ReformatCode");
        if (fromKeymap.length > 0) return fromKeymap;
        return new Shortcut[]{
                new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_MASK | InputEvent.ALT_MASK), null)
        };
    }

    /** 压缩 / 多行切换。T-PreviewDisplayMode：不改显示模式——切回多行时既有模式（如竖排）恢复。 */
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
        // T-PreviewDisplayMode：显示模式随面板清空复位（与其余会话状态一致，不跨预览残留）
        displayMode = PreviewDisplayMode.NORMAL;
        releaseEditor();
        editorHolder.removeAll();
        metaLabel.setText("");
        // T-PreviewTopOverlap F3：元信息原文缓存随清空复位（下次 updateMeta 重建）
        metaLineText = "";
        metaPerfSegments = List.of();
        lastMetaBudget = -1;
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
        // T-RefreshDbSteal：三分支走查埋点——真机故障期症状②（左参数不再同步右侧 SQL）的
        // 丢失路径必经此方法（三个静默分支之一吞掉了渲染），带分支标签定位
        Logger fLogger = Logger.getInstance(SqlPreviewPanel.class);
        String withFill = appendFillSection(sql);
        // T-PreviewDisplayMode：显示模式在渲染管线内统一套用（唯一收敛点——XML/批量/模型/旧文本
        // 四条重建路径都经此处，故参数输入/勾选/foreach 变更后形态保持）
        String text = applyDisplayMode(withFill);
        // M36.4：优先写入原生编辑器文档（内嵌后）
        Document nativeDoc = nativeEditorDoc;
        if (nativeDoc != null) {
            // T-RefreshDbSteal F4：控制台失联时不写"别人的文档"（症状②：参数改动同步进
            // 被抢走的控制台，本面板视觉零变化）——改走失联恢复把控制台要回来；恢复链
            // 内部会同步 SQL 进控制台文档（reuseSharedConsole setText），本次渲染不丢
            if (!consoleAttachedToMe()) {
                fLogger.warn("[M36] showSql nativeDoc detached (attachedToMe=false) → recoverConsole");
                recoverConsole(text);
                return;
            }
            if (!nativeDoc.getText().equals(text)) {
                fLogger.info("[M36] showSql branch=nativeDoc: len=" + text.length()
                        + ", attachedToMe=" + consoleAttachedToMe());
                suppressAutoPopup = true;
                try {
                    WriteCommandAction.runWriteCommandAction(project, () -> nativeDoc.setText(text));
                } finally {
                    suppressAutoPopup = false;
                }
            }
            return;
        }
        Editor existing = editor;
        if (existing != null) {
            fLogger.info("[M36] showSql branch=editor: len=" + text.length() + ", nativeDoc=null");
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
        fLogger.info("[M36] showSql branch=createEditor: len=" + text.length()
                + ", attachedToMe=" + consoleAttachedToMe());
        // T-RefreshDbSteal F4：createEditor 是 UX 悬崖（无 DBN 工具栏、形态突变带行号）——
        // 控制台插件级就绪但已失联时，先走恢复链重嵌，恢复链自身按回退编辑器保显示
        if (!consoleAttachedToMe() && SqlExecutor.isPrepared()) {
            fLogger.warn("[M36] createEditor fallback while prepared console detached → recoverConsole");
            recoverConsole(text);
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
        // T-RefreshDbSteal：createEditor 是症状①的特征终点（自建编辑器带行号，DBN 控制台无），
        // 但真机故障期它零日志（71s 空窗）——带栈埋点定调用人路径，兜底断言非用户路径
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] createEditor: len=" + sql.length()
                        + ", nativeDoc=" + (nativeEditorDoc != null)
                        + ", embedded=" + System.identityHashCode(embeddedConsole),
                        new Throwable("createEditor path"));
        Project proj = project;
        com.intellij.openapi.fileTypes.FileType sqlFileType = FileTypeManager.getInstance().getStdFileType("SQL");
        Editor created;
        if (proj != null) {
            // T-PreviewFlash：有界等待预热产物——createTempFile + refresh + findFile 全移到后台线程，
            // EDT 不再触发 WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言（allowSlowOperations(GENERIC)
            // 压不住：SlowOperations.startSection 只在非 EDT 线程压栈，EDT 上该逃生舱是空壳，见开发计划 §3.1 反编译）。
            // T-PreviewDocLock2 F3：临时文件支链从「必得」改「尽力」——awaitPrewarmedTempFile 超时/拿不到
            // 产物、或 releaseEditor 后未经 clear() 二次 createEditor 时（prewarmedTempFile 一次性、已置 null），
            // 原实现直接 throw IllegalStateException 表现为「预览打不开」。改为降级到纯内存文档编辑器
            // （复用下面 project==null 分支的模板：createDocument(sql)），代价是降级态无 PSI → 无自建高亮/
            // 补全（平台默认裸编辑），功能降级优于硬失败——主路径（project 非空且预热就绪）行为逐字节不变。
            Document doc;
            try {
                Pair<VirtualFile, PsiFile> ready = awaitPrewarmedTempFile();
                if (ready == null) throw new IllegalStateException("无法创建 SQL 预览临时文件");
                VirtualFile vFile = ready.getFirst();
                PsiFile psi = ready.getSecond();
                previewTempFile = vFile;
                psiFile = psi;
                // T-PreviewDocLock：262 平台给「PSI 文件 → 文档」解析加了运行期读权限断言（243 只有
                // @RequiresReadLock 注解，官方 243 sources jar 核实），故 EDT 鼠标事件里裸调即报内部错误：
                // 单次调用固定 4 条 SEVERE「Plugin to blame: SQL Visualizer」——vf→Document 解析 2 条
                //（FileDocumentManager.getDocument 顶部的 EditorLockFreeTyping.assertReadAccess +
                // FileDocumentManagerBase.getDocument 首行的 softAssertReadAccess）与 Document/PSI
                // 文本长度校验 2 条（PsiFileImpl.getTextLength → view provider content → getDocument）。
                // 生产模式因 Logger.shouldRethrow 只认控制流异常而不抛（功能未断），但已是 SEVERE + FUS 上报，
                // 且 UsefulTestCase 模式下直接抛 AssertionError。处置同 T-M42（同文件
                // createPreviewFileForTest 包 PsiFileFactory.createFileFromText 的先例）：EDT 上同步执行
                // ReadAction，语义不变。约束：lambda 内只能做纯内存模型读——EDT 进 ReadAction 会 check out
                // write-intent lock，塞同步 VFS refresh 会复现 T-PreviewWiLock 的 write-safe 硬抛。
                Document d = ReadAction.compute(() -> PsiDocumentManager.getInstance(proj).getDocument(psi));
                if (d == null) throw new IllegalStateException("临时 SQL 文件无关联文档");
                // T-M36.6-03：预热文件为占位空内容 → 首次装配按实际 SQL 填充并同步 PSI
                //（同 EDT tick 内完成，编辑器尚未显示，无闪烁）；同步路径文件内容=SQL，幂等跳过
                if (!d.getText().equals(sql)) {
                    WriteCommandAction.runWriteCommandAction(proj, () -> {
                        d.setText(sql);
                        PsiDocumentManager.getInstance(proj).commitDocument(d);
                    });
                }
                doc = d;
            } catch (Throwable t) {
                Logger.getInstance(SqlPreviewPanel.class)
                        .warn("[M36] preview temp file unavailable, fall back to in-memory editor: "
                                + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                psiFile = null;
                doc = EditorFactory.getInstance().createDocument(sql);
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
        // T-RefreshDbSteal：createEditor 兜底断言的对称端点——谁释放了自建编辑器决定了
        // 下一次 showSql 走哪个分支，旧实现零日志
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] releaseEditor: editor=" + System.identityHashCode(editor)
                        + ", nativeDoc=" + (nativeEditorDoc != null), new Throwable("releaseEditor path"));
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
        // T-HibDualCapture：8 列放不下 16 字符日期值（视口滚到尾部被当成「值截断」），下限放宽到 16 列
        JBTextField field = new JBTextField(16);
        field.setText(paramValues.getOrDefault(source, ""));
        // 长值超框宽时光标钉回首字符，视口停在头部；完整值以 tooltip 兜底
        if (!field.getText().isEmpty()) field.setCaretPosition(0);
        field.setToolTipText(paramTooltip(source, field.getText()));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onParamFieldText(source, field);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onParamFieldText(source, field);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onParamFieldText(source, field);
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

    /**
     * T-HibDualCapture：参数框文本变更（含导入回填等程序化 setText 触发的文档事件）——
     * tooltip 随值同步，其余走既有回填链路。
     */
    private void onParamFieldText(String source, JBTextField field) {
        field.setToolTipText(paramTooltip(source, field.getText()));
        onParamChanged(source, field.getText());
    }

    /** T-HibDualCapture：参数框 tooltip——`#source = 完整值`，框宽放不下的长值以此兜底可见。 */
    private static String paramTooltip(String source, String value) {
        return "#" + source + (value == null || value.isEmpty() ? "" : " = " + value);
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
        // T-SqlPerf：性能建议文案单独一行（HTML 换行）。
        // T-PreviewTopOverlap F3：原文分段缓存，label 文本按宽度预算重拼（applyMetaTruncation）
        metaLineText = meta + warningLine;
        metaPerfSegments = buildPerfSegments(result);
        metaLabel.setToolTipText(meta + warningLine
                + metaPerfSegments.stream().map(MetaPerfSegment::text).collect(java.util.stream.Collectors.joining("；")));
        metaLabel.setForeground(currentWarning != null
                ? JBColor.namedColor("ErrorForeground", JBColor.RED)
                : JBUI.CurrentTheme.Label.foreground());
        if (errorCopyButton != null) errorCopyButton.setVisible(currentWarning != null);
        // F3：复制错误按钮显隐改变工具栏理想宽，预算随显隐重算（lastMetaBudget 复位后应用）
        lastMetaBudget = -1;
        applyMetaTruncation();
    }

    /**
     * T-PreviewTopOverlap F3：元信息宽度预算截断。
     *
     * 预算 = topRow 宽 − 工具栏理想宽 − topRow 边距 − metaLabel 左右边距；未布局（width<=0）跳过，
     * 等组件首显/拉伸时由 ComponentListener 触发。超预算时 meta 行中段省略（复用 T-M26.3 ellipsize），
     * perf 行丢尾段加省略号（HTML 标签外截断）；tooltip 恒全文。宽度未变短路防重算抖动。
     */
    private void applyMetaTruncation() {
        if (metaLineText.isEmpty() && metaPerfSegments.isEmpty()) return;
        int width = topRow.getWidth();
        if (width <= 0) {
            // 未布局（showResult 先于首帧布局 / headless 测试）：先展示全文，布局后由 ComponentListener 按预算重截
            metaLabel.setText(fullDisplayText());
            return;
        }
        Insets insets = topRow.getInsets();
        int side = insets != null ? insets.left + insets.right : 0;
        int budget = Math.max(width - toolbarPanel.getPreferredSize().width - side - 16, MIN_META_BUDGET_PX);
        if (budget == lastMetaBudget) return;
        lastMetaBudget = budget;
        FontMetrics metrics = metaLabel.getFontMetrics(metaLabel.getFont());
        if (metaPerfSegments.isEmpty()) {
            metaLabel.setText(metrics.stringWidth(metaLineText) <= budget
                    ? metaLineText
                    : ellipsize(metaLineText, metrics, budget));
        } else {
            String line1 = metrics.stringWidth(metaLineText) <= budget
                    ? metaLineText
                    : ellipsize(metaLineText, metrics, budget);
            metaLabel.setText("<html>" + line1 + "<br>" + perfLineHtml(budget, metrics) + "</html>");
        }
    }

    /** F3：全文展示文本（未布局/预算充裕时）——meta 行 + perf 行原着色 markup，与 T-SqlPerf 原渲染一致。 */
    private String fullDisplayText() {
        if (metaPerfSegments.isEmpty()) return metaLineText;
        StringBuilder markup = new StringBuilder();
        for (MetaPerfSegment segment : metaPerfSegments) {
            if (markup.length() > 0) markup.append("；");
            markup.append("<font color=\"").append(segment.color()).append("\">").append(segment.text()).append("</font>");
        }
        return "<html>" + metaLineText + "<br>   " + markup + "</html>";
    }

    /** F3：perf 行预算内重拼——逐段（含「；」分隔宽）按像素累计，放得下保留原着色；放不下丢尾段并加省略号。 */
    private String perfLineHtml(int budget, FontMetrics metrics) {
        List<MetaPerfSegment> segments = metaPerfSegments;
        if (segments.isEmpty()) return "";
        String indent = "   ";
        int used = metrics.stringWidth(indent);
        StringBuilder rendered = new StringBuilder(indent);
        int kept = 0;
        for (int index = 0; index < segments.size(); index++) {
            MetaPerfSegment segment = segments.get(index);
            String piece = (index > 0 ? "；" : "") + segment.text();
            int pieceWidth = metrics.stringWidth(piece);
            if (kept > 0 && used + pieceWidth > budget) continue;
            if (kept == 0 && used + pieceWidth > budget) {
                // 首段放不下：首段文本尾部省略至预算，后续段全弃（HTML 标签外截断，tooltip 恒全文）
                String text = ellipsize(segment.text(), metrics, Math.max(budget - used, MIN_META_BUDGET_PX / 2));
                rendered.append("<font color=\"").append(segment.color()).append("\">").append(text).append("</font>");
                return rendered.toString();
            }
            if (index > 0) rendered.append("；");
            rendered.append("<font color=\"").append(segment.color()).append("\">").append(segment.text()).append("</font>");
            used += pieceWidth;
            kept++;
        }
        if (kept < segments.size()) {
            rendered.append("<font color=\"").append(segments.get(kept - 1).color()).append("\">…</font>");
        }
        return rendered.toString();
    }

    /**
     * F2/T-SqlPerf：构建性能建议分段（可见文本 + 颜色）。ERROR/WARN 红前缀 ⚠，INFO 蓝前缀 ℹ。
     */
    private List<MetaPerfSegment> buildPerfSegments(PreviewResult result) {
        List<PerfWarning> warnings = result.getPerfWarnings();
        if (warnings == null || warnings.isEmpty()) return List.of();
        List<MetaPerfSegment> segments = new ArrayList<>();
        for (PerfWarning warning : warnings) {
            boolean isError = warning.getSeverity() == PerfSeverity.ERROR || warning.getSeverity() == PerfSeverity.WARN;
            String prefix = isError ? "⚠ " : "ℹ ";
            segments.add(new MetaPerfSegment(prefix + localizeWarning(warning), isError ? "#E05555" : "#3B82F6"));
        }
        return segments;
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
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] panel removeNotify: embedded=" + System.identityHashCode(embeddedConsole));
        releaseEditor();
    }

    @Override
    public void dispose() {
        // T-RefreshDbSteal：面板废弃——移除 rehost 监听（共享控制台可能已被新面板接管，
        // 本面板的监听不得残活抢组件）；detachConsole 对 null 安全
        try {
            detachConsole();
        } catch (Throwable ignored) {
            // dispose 路径不因监听摘除失败中断
        }
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

    /** T-RefreshDbSteal F3：摘除控制台 + 移除 rehost 监听（生命周期断言用）。 */
    void detachConsoleForTest() {
        detachConsole();
    }

    /** T-RefreshDbSteal F4：所有权判定（卫队断言用，见 RefreshDbStealTest）。 */
    boolean consoleAttachedToMeForTest() {
        return consoleAttachedToMe();
    }

    /** T-RefreshDbSteal F3：当前挂接的 rehost 监听是否仍在组件上（解绑断言用）。 */
    boolean rehostListenerAttachedForTest() {
        JComponent console = embeddedConsole;
        if (console == null) return false;
        java.awt.event.HierarchyListener listener = rehostListener;
        if (listener == null) return false;
        for (java.awt.event.HierarchyListener l : console.getHierarchyListeners()) {
            if (l == listener) return true;
        }
        return false;
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

    /** T-PreviewDisplayMode：当前显示模式（参数变更后保持性断言用）。 */
    PreviewDisplayMode displayModeForTest() {
        return displayMode;
    }

    // ------------------------------------------------------------------
    // T-PreviewTopOverlap F4：顶部行布局 / 折叠菜单测试访问
    // ------------------------------------------------------------------

    /** 顶部行（metaLabel + 工具栏宿主，布局断言用）。 */
    JBPanel<JBPanel<?>> topRowForTest() {
        return topRow;
    }

    /** 工具栏（直显按钮集合断言用）。 */
    JBPanel<JBPanel<?>> toolbarForTest() {
        return toolbarPanel;
    }

    /** 「更多」折叠菜单项文案（顺序与弹层一致，断言用；show 依赖真实屏幕故只暴露文案与 step）。 */
    List<String> moreActionsForTest() {
        List<String> titles = new ArrayList<>();
        for (kotlin.Pair<String, Runnable> action : moreActions()) titles.add(action.getFirst());
        return titles;
    }

    /** 「更多」折叠菜单 step（onChosen 委托断言用，无需真实弹层）。 */
    BaseListPopupStep<String> moreActionsStepForTest() {
        return moreActionsStep(moreActions());
    }

    /** F3：宽度预算截断手动触发（headless 无 componentResized，测试直调）。 */
    void applyMetaTruncationForTest() {
        applyMetaTruncation();
    }

    /** T-HibDualCapture：第 index 个普通参数框的 tooltip（`#source = 值`），供长值兜底断言。 */
    String paramFieldTooltipForTest(int index) {
        return index >= 0 && index < paramFields.size() ? paramFields.get(index).getToolTipText() : null;
    }

    /** T-HibDualCapture：第 index 个普通参数框的光标位置（应钉 0，视口停在值头部）。 */
    int paramFieldCaretForTest(int index) {
        return index >= 0 && index < paramFields.size() ? paramFields.get(index).getCaretPosition() : -1;
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
