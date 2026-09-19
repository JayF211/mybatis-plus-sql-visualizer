package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.execute.DocWriteContext;
import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.SqlExecutor;
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier;
import cn.song.mybatisplus.visualizer.model.BatchColumn;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.model.ConditionView;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.xml.ExprReason;
import cn.song.mybatisplus.visualizer.xml.ExprSupport;
import cn.song.mybatisplus.visualizer.xml.PagesColumn;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
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
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore;
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator;
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction;
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter;
import cn.song.mybatisplus.visualizer.util.CaseFormatUtil;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
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
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
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
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBLoadingPanel;
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
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
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
 * 弹窗 SqlPreviewDialog 与工具窗口共用本面板，展示：
 *  - 顶部单行：元信息（Wrapper 类型 / 表名 / 风险警示，T-PreviewTopOverlap F3 按宽度预算截断）
 *    + 工具栏（压缩切换 / 格式化 / 关键字换行 / 竖向排列 / 执行计划 / 刷新 + 「更多」折叠低频动作；
 *    复制错误仅警示时可见）
 *  - 中部左右分栏：左侧**参数值输入框**（`#param` → 值，输入实时回填 SQL），右侧**可编辑 SQL 编辑器**
 *  - SQL 编辑器：`EditorFactory.createEditor`（可写）+ 原生 SQL 语法高亮；文档由原生 SQL FileType PSI
 *    文件支撑，挂 SqlCompletionContributor 提供补全（Ctrl+Space）
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

    /**
     * T-PT-02：TEMPORAL 参数的可解析日期形态——`yyyy` / `yyyy-M-d` / 追加 ` HH:mm[:ss[.SSS]]`
     * （`T` 分隔兼容 ISO 形态；只做提示，不阻塞渲染）。
     */
    private static final Pattern DATE_REGEX = Pattern.compile(
            "\\d{4}(-\\d{1,2}(-\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?)?)?");

    /** T-PT-02：行尾校验警示标（淡色，深浅主题各一档）。 */
    private static final String PARAM_WARN_MARK = "⚠";
    private static final JBColor PARAM_WARN_FOREGROUND = new JBColor(0xB8860B, 0xD9A343);

    /**
     * T-PT-02：集合命名启发式的单数词排除表——以 `s` 结尾但语义为单数（状态/地址等），
     * 不做数组提示（宁缺勿错：提示只是占位文案）。
     */
    private static final Set<String> COLLECTION_NAME_EXCEPTIONS = Set.of(
            "status", "address", "class", "business", "progress", "process", "access", "alias",
            "analysis", "radius", "series", "species", "news", "pass", "glass", "mass", "press",
            "stress", "success", "express", "compass", "canvas", "atlas", "bias", "lens",
            "physics", "politics");

    /**
     * T-DollarInjectMark：`${}` 直替提示的会话级关闭记忆（✕ 后本 IDE 会话所有预览面板
     * 不再弹——常用 `${orderBy}` 场景的横幅疲劳缓解；面板关闭/切换文件不重置）。
     */
    private static boolean dollarMarkDismissedSession = false;

    /** T-DollarInjectMark：横幅文案里的 `${}` token（技术记号非语言文案，双语言共用，作 {0} 参数传入）。 */
    static final String DOLLAR_TOKEN = "${}";

    /** 测试复位：清除 `${}` 提示的会话级关闭记忆。 */
    static void resetDollarMarkForTest() {
        dollarMarkDismissedSession = false;
    }

    /** T-Perf-04：参数击键 renderSql 防抖窗口（毫秒）。 */
    private static final int RENDER_DEBOUNCE_MS = 150;

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
        // T-PreviewBgFit（问题一）：本面板自身给出面板底色——左侧参数区整条链是透明的
        //（paramTopAlign/viewport 均非不透明，M36.5 起用于与父容器融合），底色一直靠祖先容器提供；
        // T-UiNative P0a 把自绘 ThemedSplitPane（当时显式设了 UIUtil.getPanelBackground()）换成平台
        // OnePixelSplitter 时丢了那层背景，深色主题下参数行下方直接透出 splitter 的近黑背景。
        // 恢复祖先背景之外再让本滚动区成不透明面板灰，今后再换容器也不会复发。
        setOpaque(true);
        setBackground(UIUtil.getPanelBackground());
        getViewport().setOpaque(false);
        // M36.5：去 LAF 默认 viewport 描边（左侧白色内边框根因）
        setBorder(JBUI.Borders.empty());
    }};

    private final List<JBTextField> paramFields = new ArrayList<>();
    // T-PT-02：普通参数行行尾校验警示标（与 paramFields 同序，仅 buildParamRow 产出）
    private final List<JBLabel> paramWarnLabels = new ArrayList<>();
    private final List<JCheckBox> conditionCheckBoxes = new ArrayList<>();
    private Disposable autoPopupDisposable = null;
    private final List<JBTextField> conditionValueFields = new ArrayList<>();
    private final List<JCheckBox> batchCheckBoxes = new ArrayList<>();
    // T-SelToggle：全选/全不选合并为单个状态切换按钮——全勾时文案「全不选」点击清空，
    // 其余态文案「全选」点击填满；文案随勾选态翻转（renderSql 末尾单点同步）。
    private JButton toggleSelectButton = null;
    private JButton clearButton = null;        // 动态条件分区清除按钮
    private JButton batchClearButton = null;   // 批量参数列分区清除按钮
    // T-M23.2：批量「参数列」区全选/全不选切换按钮（T-SelToggle 合并单按钮）
    private JButton batchToggleSelectButton = null;

    private Editor editor = null;

    /**
     * T-M27.2：自建 autopopup 的调度防重入标志（平台 autopopup 对非文件编辑器不生效）。
     * T-RA-02：监听器本体不再用字段记账——T-M52 起移除职责全在 {@code autoPopupDisposable}
     * （{@code addDocumentListener(listener, disposable)}，dispose 即摘），原 {@code autoPopupListener}
     * 字段自此只写不读，已删；细节见 {@link #bindAutoPopup}。
     */
    private boolean autoPopupScheduled = false;
    private boolean suppressAutoPopup = false;
    private PsiFile psiFile = null;

    private volatile VirtualFile previewTempFile = null;
    private volatile Pair<VirtualFile, PsiFile> prewarmedTempFile = null;

    /**
     * T-PerfTail #2：预热产物异步载体（消除 EDT sleep-poll）——prewarmPreviewTempFile 每次
     * 预热新建（clear 重预热随之更新），createEditor 热态同步消费、冷态注册回调回 EDT 装配。
     */
    private volatile PrewarmAwait<Pair<VirtualFile, PsiFile>> prewarmAwait = null;
    /** T-GifRerecord：预热失败标志——await 快速降级用，避免 EDT 空等 2s 后再走降级路径。 */
    private volatile boolean prewarmFailed = false;
    private volatile boolean previewDisposed = false;
    /** T-PreviewExecDs-01c：已 setMapping 方言的预览 vf（同文件幂等短路，避免每次预览重设方言触发 push all dumb）。 */
    private volatile VirtualFile previewDialectMappedVf = null;
    private String sourceLocation = null;

    private final JBPanel<JBPanel<?>> editorHolder = new JBPanel<JBPanel<?>>(new GridLayout(1, 1)) {{
        setOpaque(false);
    }};

    private final SqlResultPanel resultPanel = new SqlResultPanel() {{
        setVisible(false);
    }};

    /**
     * T-UxOnboard T-UX-02：预览编辑器空态引导（原生 placeholder，显隐由编辑器随空文档管理）。
     * 常态下预览内容即编辑器全文、placeholder 不可见；用户清空文档后原生提示接管。
     */
    private final String previewEmptyHint = MpSqlVisualizerBundle.message("panel.preview.emptyHint");

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

    /**
     * T-AutoFold F5：原生控制台编辑器引用（onNativeEditorReady 时登记）——
     * showSql nativeDoc 支链的程序性折叠应用目标；detachConsole/clear/dispose 时清空，
     * 防止 EditorFactory 泄漏告警。recoverConsole 失联路径经 embedConsole 换新组件后
     * 由下一次 onNativeEditorReady 重新登记。
     */
    private Editor nativeConsoleEditor = null;

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
    /**
     * T-F8：JOIN 图标签页的加载面——包住整个 {@link #diagramTabHost}，转圈盖在标签页内容之上。
     *
     * <p>为什么加载面必须在宿主层而不是图面板内部：唯一真正的耗时段是 {@link #updateDiagramTab} 的
     * <b>后台 schema 富化</b>（冷缓存按 schema 规模线性），而原生图路径（默认设置
     * {@code joinDiagramNative = true}，Ultimate 真机默认）下 {@link #showJoinDiagram} 的
     * {@code diagramTabHost.removeAll()} 会把 {@link #diagramPanel} 摘出层级——面板内部自摆的转圈在默认配置下
     * 根本不可见（审计项 F8：T-UN-14 接的转圈从未出现过，showLoading/hideLoading 全仓零调用点）。
     * 宿主层对原生/自绘两条渲染路径都可见，是唯一能覆盖真机默认路径的加载面。
     *
     * <p>转圈层由平台 {@link JBLoadingPanel} 提供（独立 loadingLayer 盖在内容层之上，不替换内容，
     * 2 参构造 startDelayMs=-1 使 startLoading/stopLoading 同步改可见性）。
     * Disposable 挂本面板（JBLoadingPanel 的 2 参构造要求父 Disposable）。
     */
    private final JBLoadingPanel diagramLoadingHost = new JBLoadingPanel(new BorderLayout(), this);
    private javax.swing.JComponent currentNativeDiagram = null;
    /**
     * T-DbSchemaCompletion：待补刷富化的图（首次富化一点数据源信息都没拿到时记下）。
     *
     * <p>仅存"上一次富化空手而归"的那个 {@link PreviewResult}——数据源建连（原生控制台就绪）后据此补刷一次，
     * 刷完置空；新预览替换 current 时旧值自然失配，无需额外清理。
     */
    private PreviewResult diagramEnrichRetryTarget = null;
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

    // T-PreviewUx-Pack #2：诚实降级横幅——渲染警示（RENDER_WARNING）与 T-PreviewExecDs
    // 降级链层级提示（DEGRADED）统一收口；降级提示优先级高于渲染警示（见 DegradationBanner.Kind）
    private final DegradationBanner degradationBanner = createDegradationBanner();

    /** 组装横幅（T-DollarInjectMark ✕ 回调：关闭 → 会话级记忆 + 清除本类别）。 */
    private static DegradationBanner createDegradationBanner() {
        DegradationBanner banner = new DegradationBanner();
        // T-DollarInjectMark：✕ 关闭 → 会话级记忆（本 IDE 会话不再弹；测试经 resetDollarMarkForTest 复位）
        banner.onDismiss = () -> {
            dollarMarkDismissedSession = true;
            banner.clear(DegradationBanner.Kind.DOLLAR_INJECT);
        };
        return banner;
    }

    /** T-DollarInjectMark：最近一次 XML 渲染的 `${}` 直替计数（非 XML 路径恒 0）。 */
    private int xmlDollarSubstitutions = 0;

    /**
     * T-InjectionGate T-IG-02：本面板实例内已确认过 `${}` raw 段执行（会话级记忆）。
     *
     * <p>同一面板确认一次后不再重复弹（常用 `${orderBy}` 场景的确认疲劳缓解）；记忆随面板实例
     * 存活——面板 dispose 即失效（工具窗换预览内容不重置，与 T-DollarInjectMark 的 IDE 会话级
     * 关闭记忆同向）。volatile：执行入口可能在非 EDT 触发（弹窗按钮回调），读写可见性保守处理。
     */
    private volatile boolean dollarRawConfirmedInSession = false;

    private final ParamHistoryCache paramHistoryCache = new ParamHistoryCache();
    private final Timer historyAlarm = new Timer(500, e -> flushHistoryToCache());

    {
        historyAlarm.setRepeats(false);
    }

    // T-Perf-04：参数击键路径 renderSql 防抖（Swing Timer，150ms，模式同 historyAlarm）
    /** 待执行的渲染任务（最后一次调度者生效——防抖语义）。 */
    private Runnable pendingRender = null;
    private final Timer renderDebounceTimer = new Timer(RENDER_DEBOUNCE_MS, e -> flushDebouncedRender());

    {
        renderDebounceTimer.setRepeats(false);
    }

    /**
     * 调度防抖渲染：keystroke 路径用——150ms 停顿后只渲染一次，最终结果与逐键全量渲染一致。
     *
     * <p>单元测试模式（isUnitTestMode，headless 无 EDT 泵）同步执行：既有测试依赖「改参数值 →
     * 文档即时更新」的同步语义，且挂起的 Timer 在后续测试泵 EDT 时迟到触发会触碰已销毁面板
     * 并触发 TransactionGuard "Write-unsafe context" 日志断言（交叉污染）。生产行为不变。
     */
    private void scheduleRender(Runnable task) {
        pendingRender = task;
        com.intellij.openapi.application.Application app =
                com.intellij.openapi.application.ApplicationManager.getApplication();
        if (app != null && app.isUnitTestMode()) {
            flushDebouncedRender();
            return;
        }
        renderDebounceTimer.restart();
    }

    /** Timer 动作：执行最后调度的渲染任务（EDT）。 */
    private void flushDebouncedRender() {
        Runnable task = pendingRender;
        pendingRender = null;
        if (task != null) task.run();
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
        // （合并曾取 dev T-GifHeader 两行布局为基底，用户定案「更多」折叠菜单模式——单行方案转正。）
        metaLabel.setBorder(JBUI.Borders.empty(0, 8, 4, 8));
        topRow = new JBPanel<JBPanel<?>>(new BorderLayout());
        topRow.add(metaLabel, BorderLayout.CENTER);
        toolbarPanel = toolbar();
        topRow.add(toolbarPanel, BorderLayout.EAST);
        topRow.setBorder(JBUI.Borders.empty(4, 8, 2, 8));
        // T-PreviewUx-Pack #2：诚实降级横幅（面板内嵌非模态提示，不算弹窗）——渲染警示 /
        // T-PreviewExecDs 降级链层级提示统一收口（触发即显示、恢复即隐藏，不占高度）
        JBPanel<JBPanel<?>> northHost = new JBPanel<JBPanel<?>>(new BorderLayout());
        northHost.setOpaque(false);
        northHost.add(topRow, BorderLayout.NORTH);
        northHost.add(degradationBanner, BorderLayout.SOUTH);
        add(northHost, BorderLayout.NORTH);
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

        // T-UiNative：ThemedSplitPane → 平台 OnePixelSplitter（原生分隔条主题融合 + 比例记忆）
        OnePixelSplitter split = new OnePixelSplitter(false, "mpvs.preview.splitter", 0.3f);
        split.setFirstComponent(paramScroll);
        split.setSecondComponent(editorHolder);
        // T-PreviewBgFit（问题一）：恢复 T-UiNative P0a 换组件时丢掉的容器背景——
        // 原 ThemedSplitPane 显式设过 `background = UIUtil.getPanelBackground()`，OnePixelSplitter
        // 版本漏了它，左侧透明参数区于是透出分栏自身背景（深色主题近黑）。
        // T-DarkTheme：命名色按主题解析，浅/深主题各自正确（主题切换由 LafManagerListener 触发重绘）。
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
        // T-F8：加载面在宿主层（标签页内容 = 加载面板 → diagramTabHost），并把它交给图面板出图
        //（面板自身的 contentLayer 在原生路径下不可见，见 diagramLoadingHost KDoc）
        diagramLoadingHost.add(diagramTabHost, BorderLayout.CENTER);
        diagramPanel.bindHostLoadingPanel(diagramLoadingHost);

        if (project != null) {
            prewarmPreviewTempFile();
        }
    }

    /** T-M36.6-03 / T-PreviewFlash：后台预热预览临时文件（占位空内容，createEditor 就绪后按实际 SQL 同步）。 */
    private void prewarmPreviewTempFile() {
        Logger LOG = Logger.getInstance(SqlPreviewPanel.class);
        // T-PerfTail #2：预热线程产出 PrewarmAwait 完成态（成功 complete / 失败 fail）——
        // 消费侧（createEditor）不再 EDT sleep-poll
        PrewarmAwait<Pair<VirtualFile, PsiFile>> await =
                new PrewarmAwait<>(PREWARM_WAIT_MS, runnable ->
                        ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any()));
        prewarmAwait = await;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (previewTempFile != null || previewDisposed) {
                await.fail(new IllegalStateException("prewarm skipped: disposed or already prepared"));
                return;
            }
            try {
                // T-PreviewExecDs-01a：预览临时文件迁项目内 .idea/sql-visualizer/preview/
                // ——系统 Temp 不在数据源关联范围，Database 插件不解析（M36.5.12 exec 同教训，
                // 预览路径此前未跟进；旧实现 09:34:40 起持续预热失败后回退裸文档，补全/注释全灭）。
                java.io.File io = projectPreviewFile();
                if (io == null) {
                    prewarmFailed = true;
                    await.fail(new IllegalStateException("project preview file unavailable"));
                    LOG.warn("[M36] prewarm FAILED: project preview file unavailable (project/basePath null or mkdirs false)");
                    return;
                }
                // T-GifRerecord：VFS 忙时 refreshAndFindFileByIoFile 偶发查不到新文件（2026.2 沙盒
                // 录制期实证）——旧实现只重试一次仍持续失败（两项目同开 VFS 忙窗口）；T-PreviewExecDs-01b
                // 加重试到 3 次带退避，失败置 prewarmFailed 让 await 快速返回，不空等预算。
                LocalFileSystem lfs = LocalFileSystem.getInstance();
                VirtualFile vf = lfs.refreshAndFindFileByIoFile(io);
                int retries = 0;
                while (vf == null && retries < 3 && !previewDisposed) {
                    Thread.sleep(100L * (retries + 1));
                    vf = lfs.refreshAndFindFileByIoFile(io);
                    retries++;
                }
                if (vf == null) {
                    prewarmFailed = true;
                    await.fail(new IllegalStateException("VFS refresh null after " + (retries + 1) + " tries"));
                    LOG.warn("[M36] prewarm FAILED: VFS refresh null after " + (retries + 1) + " tries ("
                            + io.getPath() + ", exists=" + io.exists() + ")");
                    return;
                }
                if (previewDisposed) {
                    new java.io.File(vf.getPath()).delete();
                    await.fail(new IllegalStateException("disposed during prewarm"));
                    return;
                }
                Project proj = project;
                if (proj == null) return;
                // T-PreviewExecDs-01c：方言映射须在 findFile/PSI 创建前（映射决定 PSI 语言，
                // 创建后再映射需 push 重建；exec 路径同款顺序）
                mapPreviewDialectBeforePsi(proj, vf);
                final VirtualFile vfFinal = vf;
                final Project projFinal = proj;
                PsiFile psi = ReadAction.compute(() -> PsiManager.getInstance(projFinal).findFile(vfFinal));
                if (psi == null) {
                    prewarmFailed = true;
                    await.fail(new IllegalStateException("PsiManager.findFile null for " + vf.getName()));
                    LOG.warn("[M36] prewarm FAILED: PsiManager.findFile null for " + vf.getName());
                    return;
                }
                previewTempFile = vf;
                prewarmedTempFile = new Pair<>(vf, psi);
                await.complete(new Pair<>(vf, psi));
            } catch (Throwable e) {
                prewarmFailed = true;
                await.fail(e);
                LOG.warn("[M36] prewarm FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        });
    }

    /**
     * T-PreviewExecDs-01a：项目内预览临时文件——{@code <project>/.idea/sql-visualizer/preview/mpsql-preview.sql}。
     * 独立子目录（exec/ 互不污染，同 M36.5.13 语义）。文件在项目内容内，Database 插件才能解析
     * schema 补全/注释。VFS 安全写入三态（document 驱动 > setBinaryContent > FileUtil 兜底），
     * 杜绝 MemoryDiskConflictResolver 的 File Cache Conflict 弹窗（exec 路径 M36.5.13 同款）。
     */
    @Nullable
    private java.io.File projectPreviewFile() {
        Project proj = project;
        if (proj == null) return null;
        String base = proj.getBasePath();
        if (base == null) return null;
        java.io.File dir = new java.io.File(base,
                ".idea" + java.io.File.separator + cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME
                        + java.io.File.separator + "preview");
        if (!dir.exists() && !dir.mkdirs()) return null;
        java.io.File f = new java.io.File(dir, "mpsql-preview.sql");
        VirtualFile existing = LocalFileSystem.getInstance().findFileByIoFile(f);
        if (existing != null) {
            boolean ok = false;
            try {
                // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
                com.intellij.openapi.application.WriteAction.compute(
                        (com.intellij.openapi.util.ThrowableComputable<Void, Throwable>) () -> {
                            Document fileDoc = com.intellij.openapi.fileEditor.FileDocumentManager
                                    .getInstance().getDocument(existing);
                            if (fileDoc != null) {
                                fileDoc.setText(""); // 预热占位空内容，createEditor 装配时按实际 SQL 填充
                            } else {
                                existing.setBinaryContent(new byte[0]);
                            }
                            return null;
                        });
                ok = true;
            } catch (Throwable ignored) {
                ok = false;
            }
            if (ok) return f;
        }
        try {
            com.intellij.openapi.util.io.FileUtil.writeToFile(f, "");
        } catch (Throwable ignored) {
            // ignore
        }
        return f;
    }

    /**
     * T-PreviewExecDs-01c：预览文件方言预映射（exec 路径 DataSourceSelector.mapDialectBeforePsi 同款）——
     * PSI 创建时即按映射方言解析，Generic SQL 下 Database 补全不触发。映射匹配与 exec 一致：
     * SqlDialectMappings.getAvailableValues() 里 language.id == "MySQL" 的实例（MyBatis-Plus 预览
     * 场景即 MySQL；插件自定义 Dialect 与 Database 方言映射无现成对应，硬编码与 exec 对齐）。
     * 同 vf 已映射过即幂等短路（避免每次预览点 setMapping 触发 SqlDialectPusher push all dumb）。
     *
     * <p>T-ExecSlowOps F1：反射段收编进 {@link cn.song.mybatisplus.visualizer.execute.DialectPreMapping}，
     * 本方法仅保留「面板级进程内幂等短路」语义 + 成功回写 {@link #previewDialectMappedVf}。真机实锤：
     * 本方法在 {@link #prewarmPreviewTempFile} 后台线程内调用，旧实现反射直调 {@code setMapping} →
     * 平台 {@code handleMappingChange → reparseFiles → runWriteAction → VcsVFSListener.beforeContentsChange}
     * 的 {@code assertEventDispatchThread} 硬抛 RuntimeExceptionWithAttachments（SEVERE ×3）。
     * DialectPreMapping.ensureMapped 两段式：稳态（workspace.xml 映射幸存/本进程已设）读探测直接跳过，
     * 仅项目首见经 invokeLater 派 EDT 执行映射并 CountDownLatch 有界等待——映射写归 EDT、慢操作归 BGT，
     * 两侧 SEVERE 同消。</p>
     */
    private void mapPreviewDialectBeforePsi(Project proj, VirtualFile vf) {
        boolean inProcessMapped = previewDialectMappedVf != null && previewDialectMappedVf.isValid()
                && previewDialectMappedVf == vf;
        if (cn.song.mybatisplus.visualizer.execute.DialectPreMapping.ensureMapped(proj, vf, inProcessMapped)) {
            previewDialectMappedVf = vf;
        }
    }

    /** T-DarkTheme：主题变更回调——重绘面板（命名色已自动更新，仅需触发 UI 刷新）。 */
    private void lookAndFeelChanged() {
        revalidate();
        repaint();
    }

    /**
     * T-PerfTail #2：预热产物消费决策（替代原 {@code awaitPrewarmedTempFile} 的 EDT sleep-poll）。
     *
     * <ul>
     *  <li><b>热态（结果已出）</b>：同步取产物装配——主路径（成功）或快速降级（失败，原 prewarmFailed
     *     快速返回语义），EDT 零等待；</li>
     *  <li><b>冷态（预热线程未完成）</b>：{@code scheduleOnce} 注册完成回调，超时预算语义保留
     *     （PrewarmAwait 内 completeOnTimeout，预算=PREWARM_WAIT_MS），回调统一回 EDT 装配——
     *     EDT 零阻塞，超时/失败走降级链（T-PreviewExecDs 三级降级语义保留）；</li>
     *  <li><b>单元测试模式</b>：无真实 EDT 泵，为保留既有测试「showResult 返回时编辑器即就绪」语义，
     *     退回同步有界等待（awaitOrNullForTest，生产路径不走）。</li>
     * </ul>
     *
     * @return 产物（热态主路径非 null）；null = 本调用不装配（回调挂起中）或快速降级。
     *         消费装配由调用方按 {@code resolved} 标志区分。
     */
    @NotNull
    private kotlin.Pair<Boolean, Pair<VirtualFile, PsiFile>> resolvePreviewEditorSource(@NotNull String sql) {
        PrewarmAwait<Pair<VirtualFile, PsiFile>> await = prewarmAwait;
        if (await == null) return new kotlin.Pair<>(Boolean.TRUE, null);
        if (await.isDone()) return new kotlin.Pair<>(Boolean.TRUE, await.joinOrNull());
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app != null && app.isUnitTestMode()) {
            return new kotlin.Pair<>(Boolean.TRUE, await.awaitOrNullForTest());
        }
        // 冷态：注册回调（幂等），本次渲染不装配（编辑器挂起至回调完成，≤ 超时预算）
        await.scheduleOnce(
                pair -> {
                    if (!previewDisposed) assemblePreviewEditor(sql, pair);
                },
                () -> {
                    if (!previewDisposed) assemblePreviewEditor(sql, null);
                });
        return new kotlin.Pair<>(Boolean.FALSE, null);
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
        // M37.2：EXPLAIN（真实执行计划，静态规则标注同屏对照）。
        // T-MergeFollowup-01：master T-PreviewTopOverlap 重构按钮行时把 dev 的 add 行删掉而注释残留，
        // explainSql() 全树零调用（合并语义丢失无冲突标记实锤）——补回，保持内联高频位（对齐 M37M38 文档 §2.3）。
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.explain"), this::explainSql));
        // T-RollbackSql：回滚 SQL（预览窗口直达按钮——计划 F3 定案「分享之前」的原直达锚点
        // 已随 T-PreviewTopOverlap F2 折叠进「更多」，按计划意图保持一键直达形态；
        // T-MergeR2IntoMaster §3.2 定案：按钮行以 master 为基，回滚按钮插在执行计划之后、刷新之前）
        bar.add(smallButton(MpSqlVisualizerBundle.message("panel.rollbackSql"), this::showRollbackSql));
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

    /**
     * T-RollbackSql：回滚 SQL 视图入口（面板【回滚SQL】按钮）。
     *
     * <p>生成结果分层（计划 §2.2）：Complete/Partial 打开 {@link RollbackSqlDialog}；可解析的
     * UPDATE/DELETE（Unable 但有快照工作流）同样进视图——人工恢复指引 + 粘贴快照生成回滚；
     * 完全不可推导（解析失败/MERGE/DDL/SELECT）不弹视图，仅消息框说明原因与人工恢复路径。
     */
    void showRollbackSql() {
        String rawSql = currentEditorSql();
        String sql = null;
        if (rawSql != null) {
            String stripped = SqlCommentStripper.strip(rawSql);
            if (!stripped.isBlank()) sql = stripped;
        }
        if (sql == null) return;
        String kind = SqlTypeClassifier.getFirstKeyword(sql);
        if (kind == null) kind = "SQL";
        RollbackSqlGenerator.RollbackResult result = RollbackSqlGenerator.generate(sql);
        RollbackSnapshotStore store = project != null ? project.getService(RollbackSnapshotStore.class) : null;
        RollbackSnapshotStore.SnapshotEntry entry = store != null ? store.findBySql(sql) : null;
        if (result.rating() == RollbackSqlGenerator.Rating.UNABLE && result.snapshotSelectSql() == null) {
            Project proj = project;
            if (proj == null) return;
            List<String> reasonLines = new ArrayList<>();
            for (RollbackSqlGenerator.RollbackWarning warning : result.warnings()) {
                reasonLines.add(warning.messageArgs().isEmpty()
                        ? MpSqlVisualizerBundle.message(warning.messageKey())
                        : MpSqlVisualizerBundle.message(warning.messageKey(), warning.messageArgs().toArray()));
            }
            Messages.showInfoMessage(
                    proj,
                    String.join("\n", reasonLines),
                    MpSqlVisualizerBundle.message("rollback.dialog.title"));
            return;
        }
        new RollbackSqlDialog(project, this, result, kind, entry).show();
    }

    /** {@link #executeAdhocSql(String, boolean, RollbackSnapshotStore.SnapshotEntry)} 的默认参形态。 */
    void executeAdhocSql(String sql) {
        executeAdhocSql(sql, false, null);
    }

    /** {@link #executeAdhocSql(String, boolean, RollbackSnapshotStore.SnapshotEntry)} 的默认参形态。 */
    void executeAdhocSql(String sql, boolean dmlConfirmed) {
        executeAdhocSql(sql, dmlConfirmed, null);
    }

    /**
     * T-RollbackSql F4：临时 SQL 执行（回滚弹窗专用）——复用 {@link #executeSql} 的既有管线
     * （分类 → DML 二次确认 → isPrepared 快路径/全链），T-RefreshDbSteal F4 所有权卫队
     * 对这条路径天然生效；执行完毕结果反馈走既有 {@link #resultPanel}。
     *
     * <p>刻意不走 {@code cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute} 从弹窗直调全链：
     * 全链建临时 .sql 并 attachConsole，可能把正嵌在预览面板里的共享控制台偷走。
     *
     * @param dmlConfirmed    DML 二次确认豁免（回滚弹窗「执行回滚」已过用户显式确认链）
     * @param rollbackOfEntry 本次执行本身是回滚语句时指向被回滚的原快照条目（F6 留痕链）
     */
    void executeAdhocSql(String sql, boolean dmlConfirmed,
                         @Nullable RollbackSnapshotStore.SnapshotEntry rollbackOfEntry) {
        Project proj = project;
        if (proj == null) return;
        if (sql.isBlank()) return;
        boolean isQuery = SqlTypeClassifier.isQuery(sql);
        if (!isQuery && !dmlConfirmed) {
            String keyword = SqlTypeClassifier.getFirstKeyword(sql);
            if (keyword == null) keyword = "SQL";
            int confirm = Messages.showYesNoDialog(
                    proj,
                    cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(sql, keyword),
                    MpSqlVisualizerBundle.message("execute.confirm.dml"),
                    Messages.getWarningIcon());
            if (confirm != Messages.YES) return;
        }
        // P2-F5/F6：经执行按钮执行的 UPDATE/DELETE 执行前登记快照条目（回滚语句登记留痕链）
        registerRollbackSnapshotIfNeeded(sql, rollbackOfEntry);

        resultPanel.showLoading(null);
        if (SqlExecutor.isPrepared()) {
            if (!consoleAttachedToMe()) {
                Logger.getInstance(SqlPreviewPanel.class)
                        .warn("[TRollbackSql] executeAdhocSql: prepared console detached → recover before execute");
                recoverConsole(sql);
                if (SqlExecutor.isPrepared() && !consoleAttachedToMe()) {
                    SqlExecutor.execute(
                            proj, sql, null, isQuery,
                            consoleComponent -> embedConsole(consoleComponent, true),
                            consoleEditor -> onNativeEditorReady(consoleEditor),
                            errorMsg -> resultPanel.showError(errorMsg),
                            info -> showErrorRichWithLocate(info));
                    return;
                }
            }
            // 快路径同步执行 SQL 进控制台文档（executePrepared 执行的就是控制台文档；
            // 回滚执行后下一次 renderSql 会按预览 SQL 同步回来）
            if (nativeEditorDoc != null && !nativeEditorDoc.getText().equals(sql)) {
                WriteCommandAction.runWriteCommandAction(proj, () -> nativeEditorDoc.setText(sql));
            }
            boolean executed = SqlExecutor.executePrepared(
                    proj,
                    fullConsole -> embedConsole(fullConsole),
                    errorMsg -> resultPanel.showError(errorMsg),
                    info -> showErrorRichWithLocate(info));
            if (executed) return;
        }
        SqlExecutor.execute(
                proj, sql, null, isQuery,
                consoleComponent -> embedConsole(consoleComponent, true),
                consoleEditor -> onNativeEditorReady(consoleEditor),
                errorMsg -> resultPanel.showError(errorMsg),
                info -> showErrorRichWithLocate(info));
    }

    /**
     * T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目（内存 store，LogSqlEntry 不动字段）。
     * 回滚语句（rollbackOfEntry 非 null）也登记——parentExecutionId 指向被回滚的原执行，
     * 形成 dbx {@code rollback_of} 语义的留痕链；仅登记可解析到目标表的语句（解析失败登记无意义）。
     */
    private void registerRollbackSnapshotIfNeeded(String sql,
                                                  @Nullable RollbackSnapshotStore.SnapshotEntry rollbackOfEntry) {
        Project proj = project;
        if (proj == null) return;
        String keyword = SqlTypeClassifier.getFirstKeyword(sql);
        if (keyword == null) return;
        if (!keyword.equals("UPDATE") && !keyword.equals("DELETE")) return;
        RollbackSnapshotStore store = proj.getService(RollbackSnapshotStore.class);
        if (rollbackOfEntry != null) {
            store.register(sql, rollbackOfEntry.targetTable(), null, List.of(), rollbackOfEntry.executionId());
            return;
        }
        RollbackSqlGenerator.RollbackResult analyzed =
                RollbackSqlGenerator.generate(SqlCommentStripper.strip(sql));
        String table = analyzed.targetTable();
        if (table == null) return;
        store.register(sql, table, analyzed.snapshotSelectSql(), analyzed.setColumns());
    }

    /** 当前编辑器 SQL 文本（原生编辑器文档优先，兜底自研编辑器；两处皆空 null）。 */
    @Nullable
    private String currentEditorSql() {
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

    /**
     * T-InjectionGate T-IG-02：`${}` 原文拼接段的执行确认闸门（{@link #executeSql}/{@link #explainSql} 共用）。
     *
     * <p>判定信号取自 {@link PreviewResult} 的 hasDollarRawSegments（解析期 `${` token 收集的渲染期事实，
     * 不是最终文本 regex——`${}` 成功替换为模拟值后文本里已无痕迹，regex 会漏掉全部成功场景）。
     * 含 raw 段且本面板未确认过 → 弹确认框（复用 DML 二次确认同款 YesNo 形态与 warning 图标，
     * 标题同 {@link MpSqlVisualizerBundle} 的 {@code execute.confirm.dml}，消息说明 raw 段当前填的是模拟值）；
     * 用户选 NO → 中止；选 YES → 记会话级记忆，本面板后续执行不再重复弹。
     *
     * <p>范围：只在 XML/注解预览链生效（Wrapper/JPA/日志还原链无 `${}` 概念，该字段恒 false）。
     *
     * @return true = 放行（无 raw 段 / 已确认过 / 本次确认通过）
     */
    private boolean confirmDollarRawSegments(Project proj) {
        PreviewResult cur = current;
        if (cur == null || !cur.isHasDollarRawSegments()) return true;
        if (dollarRawConfirmedInSession) return true;
        int confirm = Messages.showYesNoDialog(
                proj,
                MpSqlVisualizerBundle.message("execute.confirm.dollarRaw.message", DOLLAR_TOKEN),
                MpSqlVisualizerBundle.message("execute.confirm.dml"),
                Messages.getWarningIcon());
        if (confirm != Messages.YES) return false;
        dollarRawConfirmedInSession = true;
        return true;
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
                    cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(sql, keyword),
                    MpSqlVisualizerBundle.message("execute.confirm.dml"),
                    Messages.getWarningIcon());
            if (confirm != Messages.YES) return;
        }
        // T-InjectionGate T-IG-02：`${}` raw 拼接段确认门（DML 分类判定之后追加——预览产物里的
        // raw 段带的是模拟值，执行前须用户确认值真实性；NO 直接中止，不落快照不执行）
        if (!confirmDollarRawSegments(proj)) return;
        // T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目（回滚弹窗据此提供粘贴回填工作流）
        registerRollbackSnapshotIfNeeded(sql, null);

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
                            errorMsg -> resultPanel.showError(errorMsg),
                            info -> showErrorRichWithLocate(info));
                    return;
                }
            }
            if (nativeEditorDoc != null && !nativeEditorDoc.getText().equals(sql)) {
                WriteCommandAction.runWriteCommandAction(proj, () -> nativeEditorDoc.setText(sql));
            }
            boolean executed = SqlExecutor.executePrepared(
                    proj,
                    fullConsole -> embedConsole(fullConsole),
                    errorMsg -> resultPanel.showError(errorMsg),
                    info -> showErrorRichWithLocate(info));
            if (executed) return;
        }
        SqlExecutor.execute(
                proj, sql, null, isQuery,
                consoleComponent -> embedConsole(consoleComponent, true),
                consoleEditor -> onNativeEditorReady(consoleEditor),
                errorMsg -> resultPanel.showError(errorMsg),
                info -> showErrorRichWithLocate(info));
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

    /**
     * T-ErrorLocate T-EL-03：错误定位——跳转到预览源锚点（语句起始行）。
     *
     * <p>诚实降级定案：驱动报错位置（Position/at line）指向<b>被执行 SQL 文本</b>的字符/行，
     * 与 mapper 原文行不一一对应（动态 SQL 拼接、参数注入后行号必然错位），v1 只承诺
     * 「语句起始行」跳转 + 按钮 tooltip 如实说明，绝不虚标精确跳转。
     * 锚点来自 {@link SqlPreviewService} 的 lastPreviewSourceAnchor（预览触发时登记）；
     * 无锚点（gutter 面等未登记入口）定位按钮不出现。
     */
    private void locateErrorSource() {
        Project proj = project;
        if (proj == null) return;
        SqlPreviewService.SourceAnchor anchor = null;
        try {
            anchor = proj.getService(SqlPreviewService.class).lastPreviewSourceAnchor;
        } catch (Throwable t) {
            return;
        }
        if (anchor == null) return;
        try {
            new com.intellij.openapi.fileEditor.OpenFileDescriptor(proj, anchor.file(), anchor.line0(), 0).navigate(true);
        } catch (Throwable ignored) {
            // 与原 runCatching 等价
        }
    }

    /** T-ErrorLocate：执行调用点共用的富错误回调（错误呈现 + 可解析锚点时附定位动作）。 */
    private void showErrorRichWithLocate(cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo info) {
        resultPanel.showErrorRich(info, () -> locateErrorSource());
        maybeProbeDdlEffective(info);
    }

    /**
     * T-DdlRecover（O-15）T-DR-02：「失败不等于未生效」——执行 DDL 撞连接类断开时，数据库侧
     * 可能已隐式提交。判定链：连接类异常（SQLState 08xxx / vendor 码 / 签名兜底）∧ 可能改元数据
     * （MetadataInvalidator 既有预判复用）→ 提取 CREATE 目标表名 → 后台退避探测（500ms/1.5s/3s
     * 三次，等 DBN 自动重连窗口）→ 命中呈现警示条、未命中 tooltip 如实注记。
     * 探测/提取任一失败维持原错误呈现（诚实降级）；警示无新弹窗（弹窗克制红线）。
     */
    private void maybeProbeDdlEffective(cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo info) {
        Project proj = project;
        if (proj == null) return;
        if (!cn.song.mybatisplus.visualizer.execute.ConnectionErrorClassifier.isConnectionError(info)) return;
        String sql = currentEditorSql();
        if (sql == null) return;
        if (!cn.song.mybatisplus.visualizer.execute.MetadataInvalidator.mayChangeMetadata(sql)) return;
        String target = cn.song.mybatisplus.visualizer.execute.DdlTargetExtractor.extractCreateTarget(sql);
        if (target == null) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean hit = false;
            for (long delayMs : new long[]{500L, 1500L, 3000L}) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.TableMeta meta = null;
                try {
                    meta = cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.tableMeta(proj, target);
                } catch (Throwable t) {
                    meta = null;
                }
                if (meta == null) break;
                if (meta.comment() != null || !meta.columns().isEmpty()) {
                    hit = true;
                    break;
                }
            }
            String probedAt = java.time.LocalTime.now().withNano(0).toString();
            // Kotlin 闭包可捕获可变局部变量（Ref 包装），Java lambda 要求实际最终变量——
            // 固化探测结果后再进 EDT 回调，语义与 master 一致。
            final boolean probed = hit;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (probed) {
                    resultPanel.showDdlEffectiveWarning(target, probedAt);
                } else {
                    resultPanel.noteDdlProbeMissed();
                }
            }, ModalityState.any());
        });
    }

    /**
     * M37.2：真实 EXPLAIN——按当前方言把预览 SQL 包装为 EXPLAIN 语句，走执行链提交原生控制台。
     * 结果展示与普通执行一致（内嵌控制台结果 Tab）；静态性能规则标注保留在顶部 meta 区，同屏对照。
     * EXPLAIN 归免确认类（{@link SqlTypeClassifier}），无 DML 二次确认；方言不支持（null）→ 提示降级。
     */
    void explainSql() {
        Project proj = project;
        if (proj == null) return;
        String sql = (nativeEditorDoc != null && !nativeEditorDoc.getText().isBlank())
                ? nativeEditorDoc.getText()
                : (editor != null && !editor.getDocument().getText().isBlank()
                        ? editor.getDocument().getText() : null);
        if (sql == null) return;
        PreviewResult cur = current;
        Dialect dialect = (cur != null && cur.getPreviewModel() != null && cur.getPreviewModel().getDialect() != null)
                ? cur.getPreviewModel().getDialect() : cn.song.mybatisplus.visualizer.sql.MySqlDialect.INSTANCE;
        // T-ExplainDbl：幂等包装——首次执行后 EXPLAIN 语句已在控制台文档中，再点一次
        // 不在已包装文本上二次加前缀（重复前缀 → MySQL 1064）
        String explainSqlText = dialect.renderExplainStatementIdempotent(sql);
        if (explainSqlText == null) {
            // T-UiNative T-UN-02：方言不支持提示改轻通知（原模态弹窗——纯告知无需打断）
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                try {
                    new Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("panel.explain"),
                            MpSqlVisualizerBundle.message("panel.explain.unsupported", dialect.getName()),
                            NotificationType.INFORMATION
                    ).notify(proj);
                } catch (Throwable ignored) {
                    // headless 无通知系统依赖，与原 runCatching 等价
                }
            }
            return;
        }
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M37.2] explainSql: dialect=" + dialect.getName() + ", len=" + explainSqlText.length());
        // T-InjectionGate T-IG-03：EXPLAIN 本就真实执行——`${}` raw 段同门控（旁路不得绕开执行闸门）；
        // 方言不支持（上方已 return，未执行）不弹
        if (!confirmDollarRawSegments(proj)) return;
        resultPanel.showLoading(null);
        SqlExecutor.execute(
                proj, explainSqlText, null, true,
                consoleComponent -> embedConsole(consoleComponent, true),
                consoleEditor -> onNativeEditorReady(consoleEditor),
                errorMsg -> resultPanel.showError(errorMsg),
                info -> showErrorRichWithLocate(info));
    }

    /** M36.5：原生控制台编辑器就绪统一处理。 */
    private void onNativeEditorReady(Editor consoleEditor) {
        SqlCompletionContext ctx = buildCompletionContext();
        consoleEditor.putUserData(SqlCompletionContext.KEY, ctx);
        SqlCompletionContributor.registerContext(consoleEditor.getDocument(), ctx);
        nativeEditorDoc = consoleEditor.getDocument();
        // T-DbSchemaCompletion：控制台就绪 = 数据源已 attach/建连，此刻的 schema 预热最容易命中
        //（预览打开常早于建连，那时 getModel() 还是 null）
        if (current != null) prefetchSchemaForCompletion(current);
        // T-DbSchemaCompletion：同一窗口下 JOIN 图也一样——首次富化空手（模型未加载）时补刷一次，
        // 否则那张图会永远停在实体注释上（库里有 COMMENT，用户看到的是"JOIN 图没有字段注释"）。
        // 只补一次：target 命中即置空；富化确实拿到数据时从不登记，不会无谓重建图。
        PreviewResult retryTarget = current;
        if (retryTarget != null && diagramEnrichRetryTarget == retryTarget) {
            diagramEnrichRetryTarget = null;
            updateDiagramTab(retryTarget);
        }
        // T-AutoFold F5：登记控制台编辑器引用（折叠应用目标）；就绪即按当前文档内容应用一次
        //（prepareConsole 链可能已同步 SQL 进文档，早于下一次 showSql）
        nativeConsoleEditor = consoleEditor;
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(
                consoleEditor,
                consoleEditor.getDocument().getText());
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
                    // T-PreviewFast P7：请求→控制台可见（第三段锚点，用户感知的「预览快不快」）
                    try {
                        long req = SqlPreviewService.lastPreviewRequestMs;
                        if (req > 0) {
                            Logger.getInstance(SqlPreviewPanel.class)
                                    .info("[T-PreviewFast] request→console-visible: "
                                            + (System.currentTimeMillis() - req) + "ms");
                        }
                    } catch (Throwable ignored) {
                        // 与原 runCatching 等价（诊断打点不阻断主流程）
                    }
                    // T-PreviewConsoleFmt：控制台挂在这个 exec 文件上（其文档就是该文件文档），而文件
                    // 内容由预览链预热写成**预览原文**（裸单行）——挂载后不重渲染，用户看到的就是裸
                    // SQL（真机 20260917 日志：showSql createEditor len=274/16 行 → 预热原文 → attach
                    // 后控制台显示 1 行）。嵌入完成即按当前渲染文本回填一次；必须排在此处而非
                    // onNativeEditorReady —— showSql 的 nativeDoc 分支要求 consoleAttachedToMe()
                    // （= embeddedConsole 已进 consoleHost），挂载回调那一刻尚未满足。
                    // T-WSC-01：此处回调**会写文档**（renderSql → showSql → document.setText），故必须用
                    // write-safe 模态——原 ModalityState.any() 恒 write-unsafe，真机报 Write-unsafe context
                    // （见 DocWriteContext KDoc 与计划 20260917-1555 §一）。nonModal 的代价是模态弹窗存续期
                    // 回填延后到弹窗关闭——这正是平台对「改文档」要求的语义。
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (Disposer.isDisposed(this)) return;
                        try {
                            renderSql();
                        } catch (Throwable e) {
                            Logger.getInstance(SqlPreviewPanel.class)
                                    .warn("[M36] post-attach re-render FAILED: " + e.getClass().getSimpleName()
                                            + ": " + e.getMessage());
                        }
                    }, ModalityState.nonModal());
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
                // write-safe: UI-only（纯 Swing/只读，无文档写）
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
            // write-safe: UI-only（纯 Swing/只读，无文档写）
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
            // ModalityState.any()：模态弹窗 pump 期间重挂照常执行（纯 Swing 操作安全，M36.5.13 教训）
            // write-safe: UI-only（纯 Swing/只读，无文档写）
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
        // T-AutoFold F5：控制台组件摘除即清编辑器引用（防 EditorFactory 泄漏；新组件就绪时经
        // onNativeEditorReady 重新登记）
        nativeConsoleEditor = null;
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
            xmlDollarSubstitutions = 0;
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
        // T-DbSchemaCompletion：后台预热本文档涉及表的数据源 schema，完成后再刷一次上下文
        //（数据源列进补全候选；不依赖 JOIN 图，落 T-PreviewExecDs 遗留的 P1-5）
        prefetchSchemaForCompletion(result);
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
            // T-Perf-09：schema 富化（数据源全量遍历）移后台预取——数千表冷缓存时 EDT 直跑冻结秒级；
            // 富化完成回 EDT 再展示。当前 PreviewResult 被替换（新预览）时作废迟到的应用。
            // T-Embed-02：数据源可用时把列类型/注释升级为 DB 真实值（int(11)/DB COMMENT），缺席静默回落
            SelectStatement stmt = (SelectStatement) pm.getStatement();
            Project proj = project;
            // T-F8：加载态包住「后台富化 + 回 EDT 换页」整段（showJoinDiagram 本体与 locateDbTables
            // 都在 EDT 同步跑，同一 EDT 事件内 startLoading 画不出来，故起止点必须跨事件）。
            // 这里只「进」；「出」的主收口点是 showJoinDiagram 末尾（正常路径两条分支都过它）。
            // T-TS-FixA：主收口点只在 showJoinDiagram **被进入**时生效——建模型抛在调用之前时
            // 它不会执行，故两条分支各自的 catch 内还必须显式摘圈（异常路径的收口点）。
            diagramPanel.showLoading();
            if (proj == null) {
                // T-TS-05：buildDiagramModel 移入 try——抛在 showJoinDiagram 调用**之前**时，
                // showJoinDiagram 的 finally 根本没机会执行，转圈会留在宿主层。
                try {
                    showJoinDiagram(JoinDiagramGenerator.buildDiagramModel(stmt));
                } catch (Throwable t) {
                    // T-TS-FixA：本 catch 是这条分支的**唯一**收尾方——buildDiagramModel 是
                    // showJoinDiagram 的**实参**，实参求值期抛出时方法根本没进入，其 finally
                    // （唯一收口点）不会执行；此处不摘圈，上面 showLoading() 打开的转圈就永留宿主层，
                    // 直到下一次预览或整图卸载（对照后台富化分支：那边同样在异常时显式摘圈，
                    // 两条分支对同一异常的处理必须对称）。
                    // 线程：updateDiagramTab 由 showResult 在 EDT 上同步调用，本 catch 亦在 EDT，
                    // 直接摘即可——invokeLater 归位只服务于跨线程的后台富化分支。
                    diagramPanel.hideLoading();
                    Logger.getInstance(SqlPreviewPanel.class)
                            .warn("[T-F8] join diagram build failed (no project), loading cleared here", t);
                }
                return;
            }
            final PreviewResult resultFinal = result;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                // T-TS-05：建模型同样在 try 内——本拍一旦抛，pooled 线程终止、下面的 invokeLater
                // 永不投递，转圈就永远留在宿主层（收尾方只剩 showJoinDiagram，而它不会被调用）。
                DiagramModel base;
                try {
                    base = JoinDiagramGenerator.buildDiagramModel(stmt);
                } catch (Throwable t) {
                    Logger.getInstance(SqlPreviewPanel.class)
                            .warn("[T-F8] join diagram model build failed (background)", t);
                    // 回 EDT 摘转圈：作废判定同下面那拍——转圈属于「当前加载」，不能摘后一次预览的
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (current == resultFinal && (project == null || !project.isDisposed())) {
                            diagramPanel.hideLoading();
                        }
                    });
                    return;
                }
                DiagramModel enriched;
                try {
                    enriched = cn.song.mybatisplus.visualizer.ui.diagram.DiagramSchemaEnricher.enrich(proj, base);
                } catch (Throwable t) {
                    Logger.getInstance(SqlPreviewPanel.class)
                            .warn("[T-Perf-09] diagram schema enrich failed, showing un-enriched model", t);
                    enriched = base;
                }
                // T-DbSchemaCompletion：本次富化是否一点数据源信息都没拿到（结构相等 = 没改动）。
                // 命中就说明模型未加载（预览打开常早于数据源建连）——记下来供数据源就绪后的补刷。
                boolean contributed = enriched != base;
                DiagramModel enrichedModel = enriched;
                ApplicationManager.getApplication().invokeLater(() -> {
                    // T-F8：作废分支**不得**摘转圈——迟到的作废回调若停加载态，会把后一次预览
                    // 刚打开的转圈一起摘掉（转圈是「当前加载」的状态，不是本次回调的状态）。
                    // 此时加载态的归属方是后一次预览：它要么自己收尾（showJoinDiagram 末尾），
                    // 要么走无 JOIN 分支（unloadJoinDiagram → diagramPanel.clear() 复位）。
                    if (current != resultFinal || project == null || project.isDisposed()) return;
                    if (!contributed) diagramEnrichRetryTarget = resultFinal;
                    // T-TS-05：建图异常不逃逸到平台事件泵——逃逸只会让平台记一条无上下文的错，
                    // 而转圈已由 showJoinDiagram 的 finally 摘下；这里补一条带上下文的 warn 便于定位。
                    try {
                        showJoinDiagram(enrichedModel);
                    } catch (Throwable t) {
                        Logger.getInstance(SqlPreviewPanel.class)
                                .warn("[T-F8] join diagram build failed (post-enrich), loading cleared by finally", t);
                    }
                });
            });
        } else {
            // 无 JOIN：整图卸载（移除 diagram 标签页 + 释放原生图 + 回收虚拟外键登记）
            removeDiagramTab();
            unloadJoinDiagram();
        }
    }

    /**
     * T-Dgm-04：按设置与 Diagrams 插件可用性渲染 JOIN 图。
     *
     * <p>原生路径：快照登记 → UmlEditorProvider.createEditor → 组件嵌入 diagramTabHost
     * （缩放/布局/折叠/导出全框架托管）；Diagrams 插件缺席或框架异常（buildTabComponent 返回 null）
     * 自动降级自绘 DiagramPanel，与 Community 行为一致。
     *
     * <p>T-F8：本方法末尾是加载态的<b>主收口点</b>——两条调用分支（updateDiagramTab 的无 project
     * 同步分支与后台富化回 EDT 分支）正常路径都经此，故「摘转圈」写在这里而不是各调用点：结构上
     * 保证转圈一定晚于渲染被摘（原生图建图/自绘测量都在本方法内同步完成），调用方也不会漏收尾。
     * T-TS-FixA：主收口点只覆盖「进得来」的情形——建模型抛在<b>调用之前</b>（实参求值期）时本方法
     * 不会被进入，故两条分支的 catch 内各自还有一层异常收口（见 updateDiagramTab）。
     *
     * <p>T-TS-05：收尾改成 {@code try { … } finally}——原生框架建图（buildTabComponent 反射链/文件编辑器
     * 创建）与自绘测量都可能抛，原先的「顺序执行 + 末尾摘转圈」在异常逃逸时直接跳过摘转圈那行，
     * 用户看到的是转圈永远留在宿主层（且异常还会顺着 EDT 事件泵往上冒）。finally 保证「进过加载态
     * 就一定出」，异常照旧向上抛给调用方的带日志兜底。
     */
    private void showJoinDiagram(DiagramModel model) {
        try {
            // T-TS-05 测试缝：注入一个会抛的 render 复现建图异常路径（正常为 null）。
            // 为什么需要缝：结构合法的模型在测试沙盒里几乎不抛（同 DiagramPanel.renderGuarded 的
            // 单独成函数理由），异常兜底路径否则只能靠人工改线上代码才验得出来。
            if (diagramRenderForTest != null) {
                diagramRenderForTest.accept(model);
                return;
            }
            boolean nativeEnabled = PluginSettings.getInstance().getState().getJoinDiagramNative();
            // T-EmbedFkOrder F1：**必须先清旧图、再建新图**——旧图 dispose 会清空 JOIN 虚拟外键注册表
            //（JoinFkRegistry），顺序颠倒会把新图刚登记的 ON 条件整批抹掉：Database 原生图于是只剩节点
            // 没有连线（真机实证：同一面板里"一张图有连线、另一张没有"；含子查询的走 wrapper 路径不读
            // 注册表，故不受影响）。清理集中在此处（建图之前），登记之后不再有人清它。
            clearNativeDiagram();
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
        } finally {
            // T-F8：图已换入 → 退出加载态（渲染失败时 showDiagram 内部已落错误页并复位，此处幂等）。
            // T-TS-05：异常路径同样必须走到这里——否则转圈永留宿主层。
            diagramPanel.hideLoading();
        }
    }

    /**
     * T-TS-05 异常兜底测试缝：非 null 时 showJoinDiagram 的换图动作改走它（注入会抛的 render）。
     *
     * <p>只在测试里赋值；生产恒 null（多一次空判，无语义影响）。
     */
    java.util.function.Consumer<DiagramModel> diagramRenderForTest = null;

    /**
     * 释放并移除当前原生图组件（内部 FileEditor 经 clientProperty 定位后 dispose）。
     *
     * <p>T-EmbedFkOrder F2：本方法<b>不</b>清理 JOIN 虚拟外键注册表——它可能在建新图之前被调用（此时
     * 新图尚未登记，清了也无害），也可能在换图/卸载时被调用（此时若清会把刚登记的抹掉）。
     * 注册表的清理职责在明确语义点（三点口径）：登记前自带 clear、{@link #unloadJoinDiagram}（无 JOIN/
     * 面板清空）与面板 {@link #dispose}（T-PS4-14④）。
     */
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

    /** T-EmbedFkOrder F2：整图卸载（无 JOIN/面板清空）——释放原生图并回收虚拟外键登记。 */
    private void unloadJoinDiagram() {
        clearNativeDiagram();
        diagramPanel.clear();
        cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.clearRegisteredFk();
    }

    private void ensureTabbedContent(boolean addDiagramTab) {
        if (contentTabPane != null) return;
        BorderLayout bl = (BorderLayout) getLayout();
        Component center = bl.getLayoutComponent(BorderLayout.CENTER);
        if (center != null) remove(center);
        JTabbedPane pane = new JTabbedPane();
        pane.addTab(MpSqlVisualizerBundle.message("panel.tab.sql"), center);
        if (addDiagramTab) {
            // T-F8：标签页内容是加载面板（内含 diagramTabHost）——转圈盖住整页，
            // 原生/自绘两条路径共用同一个加载面
            pane.addTab(MpSqlVisualizerBundle.message("diagram.tab.title"), diagramLoadingHost);
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
        // T-DbSchemaCompletion：预览实际输入面是原生控制台（Ultimate），它的上下文只在
        // onNativeEditorReady 挂过一次——不在此同步，schema 预热完成后控制台仍读旧上下文，
        // 库里独有的列照旧补不出来（且控制台端编辑器 userData 优先级高于 document 兜底）。
        Editor console = nativeConsoleEditor;
        if (console != null) {
            console.putUserData(SqlCompletionContext.KEY, ctx);
            SqlCompletionContributor.registerContext(console.getDocument(), ctx);
        }
    }

    /**
     * T-DbSchemaCompletion：补全 schema 预热涉及的表——SQL 里出现的表（含 JOIN）+ 预览主表。
     *
     * <p>表名取自 TableExtractor（纯词法，无 PSI）；解析不到时只剩主表兜底。带别名/括号的误提取
     * 最多导致一次未命中（现在不写负缓存，见 DatabaseSchemaResolver.resolveFromSources），无害。
     */
    @NotNull
    private List<String> completionTables(@Nullable PreviewResult result) {
        if (result == null) return List.of();
        List<String> merged = new ArrayList<>();
        merged.addAll(cn.song.mybatisplus.visualizer.sql.extract.TableExtractor.extract(result.getSql()));
        if (result.getTableName() != null) merged.add(result.getTableName());
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : merged) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty() && seen.add(trimmed)) out.add(trimmed);
        }
        return out;
    }

    /**
     * T-DbSchemaCompletion：后台预热「本文档涉及的表」的数据源 schema，完成后刷新补全上下文。
     *
     * <p>快照判据与 T-Perf-09 的 JOIN 富化同款（{@code current == result} 才应用）——新预览打开即作废迟到结果。
     * 数据源未连接时 {@code getModel()} 为 null → 解析 0 张且不留负缓存，控制台就绪（数据源已连）时会经
     * {@link #onNativeEditorReady} 再触发一次，覆盖「预览早于建连」的窗口（真机日志实证该窗口存在）。
     *
     * <p>补全热路径不做任何遍历：本方法只在后台把结果填进 DatabaseSchemaResolver 缓存，
     * 之后 {@link #buildCompletionContext} 只读缓存（T-Perf-09 红线）。
     */
    private void prefetchSchemaForCompletion(PreviewResult result) {
        Project proj = project;
        if (proj == null) return;
        List<String> tables = completionTables(result);
        if (tables.isEmpty()) return;
        final PreviewResult resultFinal = result;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            int resolved;
            try {
                resolved = cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.prefetchTables(proj, tables);
            } catch (Throwable t) {
                resolved = 0;
            }
            Logger.getInstance(SqlPreviewPanel.class)
                    .info("[T-DbSchemaCompletion] schema 预热: tables=" + tables.size() + ", resolved=" + resolved
                            + ", list=" + tables);
            if (resolved == 0) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (current != resultFinal || project == null || project.isDisposed()) return;
                refreshCompletionContext();
            });
        });
    }

    private SqlCompletionContext buildCompletionContext() {
        return new SqlCompletionContext(
                current != null ? current.getTableName() : null,
                current != null ? current.getColumns() : Collections.emptyList(),
                null,
                current != null ? current.getColumnComments() : Collections.emptyMap(),
                current != null ? current.getTableComment() : null,
                current != null ? current.getCandidateTables() : Collections.emptyList(),
                // T-DbSchemaCompletion：数据源 schema 列（库里存在而实体类没有的列也是候选）。
                // 只读预热缓存（columnsCached 不触发遍历）；未预热/无数据源时空 Map = 改动前语义。
                cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
                        .columnsCached(project, completionTables(current)));
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
        // T-WSC-01：写安全自愈闸——renderSql 是面板全部文档写入的唯一上游（showSql 三条分支
        // 都写文档：nativeDoc / 自建 editor / createEditor），而写入合法性取决于「这次 EDT 活动
        // 是否 write-safe」（见 DocWriteContext KDoc）。非 write-safe（如 invokeLater(any) 排出的
        // 活动、Timer 拍）时重投递到 nonModal 的下一拍；renderSql 幂等重算，不会写出过期文本
        // ——这正是闸门放在 renderSql 而非 showSql(text) 的原因（后者收的是调用时刻的快照）。
        if (!DocWriteContext.isWriteAllowedNow()) {
            DocWriteContext.invokeLaterWriteSafe(this, "SqlPreviewPanel.renderSql", () -> renderSql());
            return;
        }
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
            xmlDollarSubstitutions = rendered.dollarSubstitutions();
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
        // T-SelToggle：勾选态一切变更均以 renderSql 收尾——切换按钮文案在此单点同步
        syncToggleSelectLabels();
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
    /**
     * T-PreviewFast P6：显示模式变换 <b>单槽缓存</b>——同一 (SQL, 模式, 压缩态, 格式化设置) 组合
     * 只做一次 SqlFormatter 变换。命中场景：挂载后回填渲染与紧随其后的参数/勾选重渲染
     * （内容未变时反复走格式化管线是纯浪费，大 SQL 上尤甚）。
     */
    private String formatCacheKey = null;
    private String formatCacheValue = null;

    private String applyDisplayMode(String sql) {
        if (compactMode) return sql;
        String key = displayMode + "|" + compactMode + "|" + formatSqlSetting() + "|" + sql;
        if (formatCacheValue != null && key.equals(formatCacheKey)) return formatCacheValue;
        String out;
        switch (displayMode) {
            case NORMAL:
                out = formatSqlSetting() ? SqlFormatter.INSTANCE.format(sql) : sql;
                break;
            case FORMAT:
                out = SqlFormatter.INSTANCE.format(sql);
                break;
            case KEYWORD_WRAP:
                out = SqlFormatter.INSTANCE.formatClauseLine(sql);
                break;
            case VERTICAL:
                out = SqlFormatter.INSTANCE.formatVertical(sql, verticalPairAnnotationSetting());
                break;
            default:
                out = sql;
                break;
        }
        formatCacheKey = key;
        formatCacheValue = out;
        return out;
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
        // 复制时剥离 SQL 注释（-- 行注释 + /* */ 块注释），与 Java 代码复制对齐
        String text = SqlCommentStripper.strip(raw);
        UiTextClipboard.copy(text);
        if (project != null) {
            // T-UiNative T-UN-02：复制成功提示改轻通知 + 「关闭 SQL 面板」动作（T-M23.6 原双按钮
            // 模态改为非模态——点动作等价原 index==1 分支，回调关闭预览弹窗）；
            // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                try {
                    Notification notification = new Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("plugin.name"),
                            MpSqlVisualizerBundle.message("dialog.copy.done"),
                            NotificationType.INFORMATION
                    );
                    notification.addAction(NotificationAction.createSimpleExpiring(
                            MpSqlVisualizerBundle.message("dialog.closeSqlPanel"),
                            () -> {
                                if (onClosePanelRequested != null) onClosePanelRequested.run();
                            }));
                    notification.notify(project);
                } catch (Throwable ignored) {
                    // headless 无通知系统依赖，与原 runCatching 等价
                }
            }
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
        xmlDollarSubstitutions = 0;
        currentWarning = null;
        if (errorCopyButton != null) errorCopyButton.setVisible(false);
        toggleSelectButton = null;
        batchToggleSelectButton = null;
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
        // F-1：清除图集 + 恢复纯 SQL 视图（整图卸载含虚拟外键回收）
        removeDiagramTab();
        unloadJoinDiagram();
        resultPanel.clear();
        detachConsole();
        nativeEditorDoc = null;
        // T-AutoFold F5：控制台编辑器引用随清空复位
        nativeConsoleEditor = null;
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
                    // T-PreviewFast P4：程序性写入开闸——否则本次写入会触发执行文件的 1.5s 防抖
                    // 自动落盘（内容刚由本写入落盘，再写一遍纯属自己与自己抢）
                    final Document nativeDocFinal = nativeDoc;
                    cn.song.mybatisplus.visualizer.execute.ExecDocAutosaveGate.suppressDuring(() -> {
                        WriteCommandAction.runWriteCommandAction(project, () -> nativeDocFinal.setText(text));
                        // T-PS4-12 定案①：同临界区落盘（照 DataSourceSelector.writeVfTextViaDocument
                        // 手法，isDocumentUnsaved → saveDocument）——闸门前提「内容由本写入落盘」
                        // 在此成真，formatted 文本不再滞留 modified-unsaved（第七轮审计发现本体）。
                        //
                        // 「磁盘存原文还是 formatted」核实结论（T-PreviewConsoleFmt F1/F2 +
                        // T-PreviewFast P1/P3 定例）：挂载后磁盘跟随 document 是既定语义——
                        //  1) ConsoleFmt F1 定例 = 控制台挂载后渲染链把 formatted 回填进控制台
                        //     文档，文档内容（=控制台所见）即权威；
                        //  2) T-PreviewFast P1 `execFileContentMatches` 定例 = 有已加载 document
                        //     时以 document 文本为准（注释原文「也是落盘会写入的内容」）；
                        //  3) 「磁盘存预览原文」仅是预热路径的写入，且只发生在文档未挂编辑器时
                        //     （ConsoleFmt F2 + PreviewFast P3：文档打开时预热跳过内容刷新）；
                        //  4) 落盘 formatted 不改变执行语义：执行链执行前同步的是控制台文档内容，
                        //     用户手改兜底（F5）落盘的同样是 document 内容。
                        // 故本写入落盘 formatted 与既有定例一致；孤儿文档（getFile null，测试缝
                        // createDocument 形态）无盘可落，自然跳过。
                        com.intellij.openapi.fileEditor.FileDocumentManager fdm =
                                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
                        VirtualFile vf = fdm.getFile(nativeDocFinal);
                        if (vf != null && fdm.isDocumentUnsaved(nativeDocFinal)) {
                            fdm.saveDocument(nativeDocFinal);
                        }
                        return null;
                    });
                } finally {
                    suppressAutoPopup = false;
                }
            }
            // T-AutoFold F4-a：nativeDoc 支链的程序性折叠（关闭态 applyTo 内部即清除短路）
            Editor foldTarget = nativeConsoleEditor;
            if (foldTarget != null && !foldTarget.isDisposed()) {
                cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(foldTarget, text);
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
            // T-AutoFold F4-a：自建编辑器支链
            cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(existing, text);
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
        if (proj == null) {
            assemblePreviewEditor(sql, null);
            return;
        }
        // T-PerfTail #2：预热产物消费决策——热态同步装配（主路径/快速降级，EDT 零等待）、
        // 冷态注册回调回 EDT 装配（超时预算语义保留，EDT 零阻塞）、单元测试模式同步有界等待
        kotlin.Pair<Boolean, Pair<VirtualFile, PsiFile>> resolved = resolvePreviewEditorSource(sql);
        if (!Boolean.TRUE.equals(resolved.getFirst())) return;   // 回调挂起中——装配延后至回调（超时/失败走降级链）
        assemblePreviewEditor(sql, resolved.getSecond());
    }

    /**
     * T-PerfTail #2：编辑器装配（原 createEditor 后半抽取，同步主路径与异步回调共用同一收口）。
     *
     * @param prewarmed 预热产物（项目内文件 + PSI）；null → 降级链（内存 PSI → 裸 document，
     *                  T-PreviewExecDs 降级顺序与诚实降级横幅层级提示保留）。主路径对 {@code prewarmedTempFile}
     *                  做<b>一次性消费</b>（同引用校验）——releaseEditor 后二次 createEditor 时产物已被消费，
     *                  与原 await 一次性语义一致地走降级。
     */
    private void assemblePreviewEditor(String sql, @Nullable Pair<VirtualFile, PsiFile> prewarmed) {
        Project proj = project;
        com.intellij.openapi.fileTypes.FileType sqlFileType = FileTypeManager.getInstance().getStdFileType("SQL");
        Document doc = null;
        if (proj != null) {
            // T-PreviewFlash：createTempFile + refresh + findFile 全在后台线程，
            // EDT 不再触发 WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言（allowSlowOperations(GENERIC)
            // 压不住：SlowOperations.startSection 只在非 EDT 线程压栈，EDT 上该逃生舱是空壳，见开发计划 §3.1 反编译）。
            // T-PreviewDocLock2 F3：临时文件支链「尽力」——产物缺失（预热超时/失败/已消费）时
            // 降级到内存 PSI → 裸文档（功能降级优于硬失败——主路径行为逐字节不变）。
            if (prewarmed != null && prewarmed == prewarmedTempFile) {
                prewarmedTempFile = null;
                try {
                    VirtualFile vFile = prewarmed.getFirst();
                    PsiFile psi = prewarmed.getSecond();
                    // T-PreviewUx-Pack #2：主路径成功（项目内文件 + PSI 就绪）——清除降级横幅
                    degradationBanner.clear(DegradationBanner.Kind.DEGRADED);
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
                    doc = fallbackPreviewDocument(sql, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                }
            } else {
                doc = fallbackPreviewDocument(sql, "prewarmed product unavailable (timeout/failed/consumed)");
            }
        }
        Editor created;
        if (proj != null && doc != null) {
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
        createdEx.setPlaceholder(previewEmptyHint); // T-UxOnboard T-UX-02：空态提示原生件（LogToSqlPanel T-UN-14 同款）
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
        // T-AutoFold F4-a：createEditor 支链（含内存文档降级，同一函数收口）——新编辑器按初始内容应用
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(created, sql);
    }

    /**
     * T-PreviewExecDs-01d / T-PerfTail #2：预览文档降级链（第二级 → 第三级）——
     * 第二级 = 内存 PSI（PsiFileFactory.createFileFromText，无 VFS 文件但保留补全上下文载体）；
     * 第三级（兜底）= 纯内存文档编辑器（无 PSI，补全/注释灭）。
     * T-PreviewUx-Pack #2：降级链层级横幅——触发时明示当前层级与恢复指引（不破坏降级顺序）。
     */
    @NotNull
    private Document fallbackPreviewDocument(String sql, String msg) {
        // 第二级：内存 PSI（保留补全上下文载体）
        Document memoryPsiDoc = createInMemoryPreviewPsi(sql);
        if (memoryPsiDoc != null) {
            degradationBanner.show(
                    DegradationBanner.Kind.DEGRADED,
                    MpSqlVisualizerBundle.message("panel.banner.degradedPsi"));
            return memoryPsiDoc;
        }
        // 第三级：裸 document（内存 PSI 也失败）
        Logger.getInstance(SqlPreviewPanel.class)
                .warn("[M36] preview temp file unavailable, fall back to bare in-memory editor: " + msg);
        psiFile = null;
        degradationBanner.show(
                DegradationBanner.Kind.DEGRADED,
                MpSqlVisualizerBundle.message("panel.banner.degradedPlain"));
        return EditorFactory.getInstance().createDocument(sql);
    }

    /**
     * T-PreviewExecDs-01d：降级链第二级——内存 PSI 预览文档（无项目内 VFS 文件，但保留
     * {@link SqlCompletionContext} 补全载体，避免掉到裸 document 后补全/注释全灭）。createFileFromText
     * 包 ReadAction（T-M42 同机制先例：EDT 直调触发 markGenerated softAssertReadAccess 硬抛）。
     * 失败返回 null，由调用方回落到第三级裸文档。
     */
    @Nullable
    private Document createInMemoryPreviewPsi(String sql) {
        Project proj = project;
        if (proj == null) return null;
        Logger LOG = Logger.getInstance(SqlPreviewPanel.class);
        try {
            com.intellij.openapi.fileTypes.FileType fileType =
                    com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL");
            PsiFile psi = ReadAction.compute(
                    (com.intellij.openapi.util.ThrowableComputable<PsiFile, Throwable>) () ->
                            PsiFileFactory.getInstance(proj)
                                    .createFileFromText("mpsql-preview-memory.sql", fileType, sql));
            psiFile = psi;
            LOG.info("[M36] in-memory PSI preview document created (len=" + sql.length() + ")");
            Document d = PsiDocumentManager.getInstance(proj).getDocument(psi);
            return d != null ? d : EditorFactory.getInstance().createDocument(sql);
        } catch (Throwable e) {
            LOG.warn("[M36] in-memory PSI preview fallback FAILED: " + e.getMessage());
            psiFile = null;
            return null;
        }
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
                // write-safe: 同 SqlInputEditor.bindAutoPopup——本拍只由**用户输入活动**派生
                // （程序性写入经 suppressAutoPopup 抑制，不调度补全），无进度模态时平台取模态链
                // （defaultModalityImpl）落 NON_MODAL（恒 write-safe，见 DocWriteContext KDoc）；
                // commitDocument 只做 PSI 同步、不改文档文本。改投递为 nonModal 会把补全弹窗延后到
                // 模态关闭后，输入期补全退化。
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
        // T-M52：双参数版（Disposable 管理监听生命周期，releaseEditor 时自动移除）。
        // T-RA-02：移除职责只归 autoPopupDisposable——不要再用字段存 listener 做「记账」，
        // 那会让「谁负责摘监听」出现两个说法（T-M52 前的手动 removeDocumentListener 已删，
        // 二次移除会报 "Can't remove document listener"）。重新注册前先 dispose 旧 disposable，
        // 防同一文档挂上两个 listener。
        if (autoPopupDisposable != null) Disposer.dispose(autoPopupDisposable);
        autoPopupDisposable = Disposer.newDisposable("SqlPreviewPanel.autoPopup");
        created.getDocument().addDocumentListener(listener, autoPopupDisposable);
    }

    /** 释放当前编辑器（幂等）。 */
    private void releaseEditor() {
        // T-RefreshDbSteal：createEditor 兜底断言的对称端点——谁释放了自建编辑器决定了
        // 下一次 showSql 走哪个分支，旧实现零日志
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] releaseEditor: editor=" + System.identityHashCode(editor)
                        + ", nativeDoc=" + (nativeEditorDoc != null), new Throwable("releaseEditor path"));
        if (editor != null) {
            // T-M52：监听器由 Disposable 管理，dispose 即移除（无需手动 removeDocumentListener，
            // 也不再有 listener 字段需要置 null——T-RA-02 删除）
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
        paramWarnLabels.clear();
        conditionCheckBoxes.clear();
        conditionValueFields.clear();
        batchCheckBoxes.clear();
        // T-M26.3：按钮随场景重建（标题行右侧）——先清空上次场景的残留引用
        toggleSelectButton = null;
        clearButton = null;
        batchToggleSelectButton = null;
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

    /**
     * 批量「参数列」分区标题行（T-M23.2 回归，T-M26.3 TitledSeparator 化）：左侧分区标题 +
     * 右侧单个全选/全不选切换按钮（T-SelToggle；操作 batchCheckBoxes，与动态条件区按钮互不影响）。
     */
    private JBPanel<JBPanel<?>> batchSectionHeader() {
        batchToggleSelectButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll"),
                () -> setAllBatchColumns(!batchCheckBoxes.stream().allMatch(JCheckBox::isSelected)));
        batchClearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear"), this::clearBatchValues);
        return sectionHeaderRow(
                MpSqlVisualizerBundle.message("panel.batch.columns"),
                Arrays.asList(batchToggleSelectButton, batchClearButton));
    }

    /**
     * 批量列全选/全不选（T-M23.2）：操作 batchCheckBoxes 与 enabledIds（列名作 id），
     * 同步复选框 UI 与 SQL 重渲染（全不选 → 空列清单形态，文档化边界）。
     * T-SelToggle：由单个切换按钮按当前勾选态取反调用。
     */
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

    /**
     * 动态条件分区标题行（T-M26.3）：TitledSeparator「动态条件」+ 右侧单个全选/全不选切换按钮
     * （T-M23.2 形态回归，IDEA 组操作惯例，T-SelToggle 双按钮合一；XML 条件开关同样复用，见 T-M24-04）。
     */
    private JBPanel<JBPanel<?>> conditionSectionHeader() {
        toggleSelectButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll"),
                () -> setAllConditions(!conditionCheckBoxes.stream().allMatch(JCheckBox::isSelected)));
        clearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear"), this::clearConditionValues);
        return sectionHeaderRow(
                MpSqlVisualizerBundle.message("panel.condition.section"),
                Arrays.asList(toggleSelectButton, clearButton));
    }

    private JButton compactButton(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(10.5f));
        b.setMargin(JBUI.insets(0, 6, 0, 6));
        b.addActionListener(e -> action.run());
        return b;
    }

    /**
     * T-SelToggle：切换按钮文案判定——复选框非空且全部勾选 → 「全不选」（点击清空），
     * 其余一切态（空集/部分勾选）→ 「全选」（点击填满）。以复选框 UI 态为基准
     * （enabledIds 跨场景共享可能残留本场景外 id，与用户所见不一致）。
     */
    private String toggleLabel(List<JCheckBox> boxes) {
        boolean allSelected = !boxes.isEmpty() && boxes.stream().allMatch(JCheckBox::isSelected);
        return MpSqlVisualizerBundle.message(allSelected ? "panel.condition.selectNone" : "panel.condition.selectAll");
    }

    /**
     * T-SelToggle：切换按钮文案随勾选态刷新——挂在 renderSql 末尾单点收口
     * （三个勾选入口——切换按钮自身、批量列行/动态条件行/XML 条件行 listener——均以 renderSql 收尾）。
     * 文案未变化不 setText，避免每次重渲染触发标题行 FlowLayout 无谓重排。
     */
    private void syncToggleSelectLabels() {
        if (toggleSelectButton != null) {
            String label = toggleLabel(conditionCheckBoxes);
            if (!toggleSelectButton.getText().equals(label)) toggleSelectButton.setText(label);
        }
        if (batchToggleSelectButton != null) {
            String label = toggleLabel(batchCheckBoxes);
            if (!batchToggleSelectButton.getText().equals(label)) batchToggleSelectButton.setText(label);
        }
    }

    /**
     * 全选/全不选（M13.1）：批量设置动态条件勾选态（普通参数不受影响），
     * 同步复选框 UI 与 SQL 重渲染。T-M24-04：XML 预览的条件开关（id = test 表达式）一并处理。
     * T-SelToggle：由单个切换按钮按当前勾选态取反调用。
     */
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

    /**
     * 动态条件值变更（M13：复选框纯手动）：仅更新按 source 绑定的参数值并重渲染，
     * 勾选态只由用户操作决定（值空保留 `?`，不自动取消勾选）。
     * T-Perf-04：击键路径——重渲染防抖（同 onParamChanged）。
     */
    private void onConditionValueChanged(ConditionView view, String source, String text) {
        paramValues.put(source, text);
        scheduleRender(() -> renderSql());
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
        // T-ExprAstPrecheck T-EP-03：表达式超出支持面时补淡色标注（提示，不改展开行为）
        JBLabel anno = xmlConditionAnnotation(view);
        if (anno != null) row.add(anno);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    /**
     * T-ExprAstPrecheck T-EP-03：条件行的支持面标注（SUPPORTED 返回 null 不占位）。
     * 用上下文提示色（淡）以与条件标签区分；理由进 tooltip，行内只留档位短句。
     */
    @Nullable
    private JBLabel xmlConditionAnnotation(XmlConditionView view) {
        String text;
        if (view.support() == ExprSupport.SUPPORTED) return null;
        switch (view.support()) {
            case UNSUPPORTED_SYNTAX:
                text = MpSqlVisualizerBundle.message("panel.xml.expr.unsupported");
                break;
            case EVAL_FALLBACK:
                text = MpSqlVisualizerBundle.message("panel.xml.expr.fallback");
                break;
            default:
                return null;
        }
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setToolTipText(xmlConditionAnnotationTooltip(view));
        return label;
    }

    /** 标注 tooltip：按理由码取文案，附细节（方法名 / 静态字段引用 / 解析错误消息）。 */
    private String xmlConditionAnnotationTooltip(XmlConditionView view) {
        String detail = view.supportDetail() != null ? view.supportDetail() : "";
        ExprReason reason = view.supportReason();
        if (reason == null) return view.label();
        switch (reason) {
            case SYNTAX:
                return MpSqlVisualizerBundle.message("panel.xml.expr.reason.syntax", detail);
            case UNKNOWN_METHOD:
                return MpSqlVisualizerBundle.message("panel.xml.expr.reason.unknownMethod", detail);
            case NARROW_CARRIER:
                return MpSqlVisualizerBundle.message("panel.xml.expr.reason.narrowCarrier", detail);
            case STATIC_FIELD:
                return MpSqlVisualizerBundle.message("panel.xml.expr.reason.staticField", detail);
            default:
                return view.label();
        }
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

    /**
     * foreach 值变更：T-Perf-04 击键路径每键两次全量渲染——整个处理器防抖
     * （停顿后以最终文本执行一次；handler 内部的两段渲染依赖 xmlMappings 中间态，保持同步语义不拆散）。
     */
    private void onXmlForEachValuesChanged(XmlForEachView view, String text) {
        scheduleRender(() -> applyXmlForEachValues(view, text));
    }

    /** foreach 值应用（由防抖 Timer 调度；原 onXmlForEachValuesChanged 主体）。 */
    private void applyXmlForEachValues(XmlForEachView view, String text) {
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

    /** 普通参数行：`#&lt;source&gt;` 标签 + 值输入框（T-PT-02：按签名类型类别挂占位提示与行尾校验警示）。 */
    private JBPanel<JBPanel<?>> buildParamRow(String source, int labelWidth) {
        ParamKind kind = paramKindOf(source);
        // T-PT-02：行尾淡色警示标（校验是提示不是闸门——值照原样渲染，渲染链 renderValue 行为不动）
        JBLabel warnLabel = new JBLabel("");
        warnLabel.setForeground(PARAM_WARN_FOREGROUND);
        warnLabel.setVisible(false);
        // T-HibDualCapture：8 列放不下 16 字符日期值（视口滚到尾部被当成「值截断」），下限放宽到 16 列
        JBTextField field = new JBTextField(16);
        field.setText(paramValues.getOrDefault(source, ""));
        field.getEmptyText().setText(paramEmptyText(source, kind));
        // 长值超框宽时光标钉回首字符，视口停在头部；完整值以 tooltip 兜底
        if (!field.getText().isEmpty()) field.setCaretPosition(0);
        field.setToolTipText(paramTooltip(source, field.getText()));
        updateParamWarning(warnLabel, kind, field.getText());
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onParamFieldText(source, field, warnLabel, kind);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onParamFieldText(source, field, warnLabel, kind);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onParamFieldText(source, field, warnLabel, kind);
            }
        });
        paramFields.add(field);
        paramWarnLabels.add(warnLabel);
        // T-M19.4-01：FlowLayout 间距 (4,2) → (8,6)——参数名较长时视觉"贴太近"（用户实测）
        JBPanel<JBPanel<?>> row = new JBPanel<JBPanel<?>>(new FlowLayout(FlowLayout.LEFT, 8, 6));
        row.add(paramLabel(source, labelWidth));
        row.add(field);
        row.add(warnLabel);
        row.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        return row;
    }

    // ------------------------------------------------------------------
    // T-PT-02：按签名类型类别的占位提示与校验（提示不是闸门——渲染链零改动）
    // ------------------------------------------------------------------

    /** 参数 source 的类型类别（无类型信息 → UNKNOWN，行为=升级前）。 */
    private ParamKind paramKindOf(String source) {
        XmlPreviewModel model = current != null ? current.getXmlModel() : null;
        return model != null ? model.kindFor(source) : ParamKind.UNKNOWN;
    }

    /** 占位提示（emptyText）：有类型信息按类别；UNKNOWN 走参数命名启发式（zMyBatis 同款，仅提示）。 */
    private String paramEmptyText(String source, ParamKind kind) {
        switch (kind) {
            case NUMBER:
                return MpSqlVisualizerBundle.message("panel.param.emptyText.number");
            case TEMPORAL:
                return MpSqlVisualizerBundle.message("panel.param.emptyText.temporal");
            case COLLECTION:
                return MpSqlVisualizerBundle.message("panel.param.emptyText.collection");
            case OBJECT:
                return MpSqlVisualizerBundle.message("panel.param.emptyText.object");
            case TEXT:
                return "";
            case UNKNOWN:
            default:
                return looksLikeCollectionName(source)
                        ? MpSqlVisualizerBundle.message("panel.param.emptyText.collection")
                        : "";
        }
    }

    /**
     * 集合命名启发式（T-PT-02，zMyBatis ParameterInputDialog 同款）：参数名以 {@code s} 结尾
     * （排除 status/address 等单数词）或含 list/ids/array/collection → 按数组提示。
     */
    private boolean looksLikeCollectionName(String source) {
        int dot = source.lastIndexOf('.');
        String name = (dot >= 0 ? source.substring(dot + 1) : source).toLowerCase();
        if (COLLECTION_NAME_EXCEPTIONS.contains(name)) return false;
        if (name.contains("list") || name.contains("ids") || name.contains("array") || name.contains("collection")) {
            return true;
        }
        return name.endsWith("s") && !name.endsWith("ss");
    }

    /** 行尾校验警示（NUMBER 非数字 / TEMPORAL 非可解析日期）：淡色标记 + tooltip 说明，不阻塞渲染。 */
    private void updateParamWarning(JBLabel label, ParamKind kind, @Nullable String value) {
        String message = paramWarningOf(kind, value);
        label.setText(message == null ? "" : PARAM_WARN_MARK);
        label.setToolTipText(message);
        label.setVisible(message != null);
    }

    @Nullable
    private String paramWarningOf(ParamKind kind, @Nullable String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        switch (kind) {
            case NUMBER:
                return NUMBER_REGEX.matcher(trimmed).matches()
                        ? null : MpSqlVisualizerBundle.message("panel.param.warn.number");
            case TEMPORAL:
                return DATE_REGEX.matcher(trimmed).matches()
                        ? null : MpSqlVisualizerBundle.message("panel.param.warn.temporal");
            default:
                return null;
        }
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

    /** 普通参数值变更：更新回填表并重渲染 SQL（T-Perf-04：重渲染防抖——逐键全量重生成+格式化
     *  +重分词在大 SQL 下构成卡顿；paramValues 即时更新，渲染合并到停顿后一次）。 */
    private void onParamChanged(String source, String value) {
        paramValues.put(source, value);
        scheduleRender(() -> renderSql());
        saveToHistory();
    }

    /**
     * T-HibDualCapture：参数框文本变更（含导入回填等程序化 setText 触发的文档事件）——
     * tooltip 随值同步，其余走既有回填链路。
     * T-PT-02：行尾校验警示随值同步（默认参数无警示标，调用方为 buildParamRow）。
     */
    private void onParamFieldText(String source, JBTextField field) {
        onParamFieldText(source, field, null, ParamKind.UNKNOWN);
    }

    private void onParamFieldText(String source, JBTextField field,
                                  @Nullable JBLabel warnLabel, ParamKind kind) {
        field.setToolTipText(paramTooltip(source, field.getText()));
        if (warnLabel != null) updateParamWarning(warnLabel, kind, field.getText());
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
        // T-Perf-23：单趟遍历计数（原 toList+indexOf 每次 O(C) 拷贝 + 线性查找）
        // paramFields 按 buildParamRow/buildBatchColumnRow/buildXmlForEachRow 追加顺序排列，
        // 与 unresolvedParams + 条件值来源的顺序一致；但 source 可能出现在多个场景，
        // 此处按 paramValues 键顺序定位（与输入框构建顺序一致）
        int index = 0;
        for (String key : paramValues.keySet()) {
            if (key.equals(source)) return index;
            index++;
        }
        return -1;
    }

    private void showImportResult(int matched, int unmatched, List<String> unmatchedKeys, boolean isEmptyClipboard) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        String title = MpSqlVisualizerBundle.message("panel.param.import.result.title");
        Project proj = project;
        if (proj == null) return;
        if (isEmptyClipboard) {
            // T-Perf-20：空剪贴板属用户输入类提示——改面板内行内警示（元信息行红字），
            // 不再弹模态错误框（toast 语义：下次 updateMeta 覆盖）
            String hint = MpSqlVisualizerBundle.message("panel.param.import.result.empty");
            currentWarning = hint;
            metaLabel.setText("⚠ " + hint);
            metaLabel.setToolTipText(hint);
            metaLabel.setForeground(JBColor.namedColor("ErrorForeground", JBColor.RED));
            if (errorCopyButton != null) errorCopyButton.setVisible(true);
        } else if (unmatched == 0) {
            // T-UiNative T-UN-02：导入结果改轻通知（原模态弹窗——纯告知无需打断）
            try {
                new Notification(
                        NOTIFICATION_GROUP_ID,
                        title,
                        MpSqlVisualizerBundle.message("panel.param.import.result.success", matched),
                        NotificationType.INFORMATION
                ).notify(proj);
            } catch (Throwable ignored) {
                // headless 无通知系统依赖，与原 runCatching 等价
            }
        } else {
            // T-UiNative T-UN-02：部分匹配改轻通知（原模态警示框；WARNING 保留原警示图标语义）
            try {
                new Notification(
                        NOTIFICATION_GROUP_ID,
                        title,
                        MpSqlVisualizerBundle.message("panel.param.import.result.partial", matched, unmatched, String.join(", ", unmatchedKeys)),
                        NotificationType.WARNING
                ).notify(proj);
            } catch (Throwable ignored) {
                // headless 无通知系统依赖，与原 runCatching 等价
            }
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

    /** P-05：Timer 动作——实际写回缓存（由 historyAlarm 调度）。
     * T-Perf-24：序列化/持久化写挪 pooled 线程（EDT 只做取值快照）。 */
    private void flushHistoryToCache() {
        String key = historyCacheKey();
        if (key == null) return;
        Map<String, String> snapshot = new LinkedHashMap<>(paramValues);
        // headless 单测：同步执行保持既有测试语义
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            paramHistoryCache.save(key, snapshot);
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> paramHistoryCache.save(key, snapshot));
    }

    /**
     * 扫描 `IN (?, ?, ...)` 模式，返回 IN 子句内 `?` 的参数索引集合。
     * 面板回填时这些参数按逗号分割多值渲染。
     *
     * <p>T-Perf-04：单趟 O(N) 扫描——旧实现对每个 `?` 复制并大写化整个前缀（O(L×P)）；
     * 现改为就地回扫（跳过空白 → `(` → 可选一个空格 → `IN`，与旧 trimEnd+endsWith 语义一致），
     * 零分配。
     */
    Set<Integer> detectInClauseParams(String sql) {
        Set<Integer> result = new LinkedHashSet<>();
        int paramIndex = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) != '?') continue;
            int j = i - 1;
            while (j >= 0 && Character.isWhitespace(sql.charAt(j))) j--;
            if (j >= 0 && sql.charAt(j) == '(') {
                j--;
                if (j >= 0 && sql.charAt(j) == ' ') j--; // "IN (" 单空格形态（"IN(" 直接判 N/I）
                if (j >= 1 && (sql.charAt(j) == 'N' || sql.charAt(j) == 'n')
                        && (sql.charAt(j - 1) == 'I' || sql.charAt(j - 1) == 'i')) {
                    result.add(paramIndex);
                }
            }
            paramIndex++;
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
                sb.append(renderParamValue(v, paramIndex < safeHints.size() ? safeHints.get(paramIndex) : null, source));
                paramIndex++;
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    private String renderParamValue(String v, ParamHint hint) {
        return renderParamValue(v, hint, null);
    }

    private String renderParamValue(String v, ParamHint hint, @Nullable String source) {
        // 未填写（paramValues 未播种的 source，非标准路径）保留 `?` 兜底提示
        if (v == null) return "?";
        // T-PT-04：空输入按设置策略出 NULL / ''（默认 NULL）——**仅对带签名类型信息的参数**生效；
        // 无类型信息（UNKNOWN）保留 `?` 占位提示，行为与升级前一致（验收 3）
        if (v.isBlank()) return hasSignatureTypeInfo(source) ? emptyParamLiteral() : "?";
        LikePosition like = hint != null ? hint.getLikePosition() : null;
        if (hint != null && hint.getInList()) {
            List<String> parts = Arrays.stream(v.split(",")).map(s -> s.trim()).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            // 逗号分割后无有效值（如 `,` 形态）：按空输入档处理
            if (parts.isEmpty()) return hasSignatureTypeInfo(source) ? emptyParamLiteral() : "?";
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

    /**
     * T-PT-04：空参数填充字面量——按设置「空参数填充策略」出 {@code NULL}（默认）或 {@code ''}。
     * 作用于 renderParamValue 的空值分支，且仅当参数带签名类型信息（hasSignatureTypeInfo）。
     */
    private String emptyParamLiteral() {
        try {
            return PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING.equals(PluginSettings.getInstance().getState().getEmptyParamFill())
                    ? "''" : "NULL";
        } catch (Throwable t) {
            return "NULL";
        }
    }

    /** T-PT-04：参数是否带签名类型信息（XML/注解 mapper 参数行）——决定空输入是否走填充策略。 */
    private boolean hasSignatureTypeInfo(@Nullable String source) {
        return source != null && paramKindOf(source) != ParamKind.UNKNOWN;
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
        // T-SourceJumpFunnel P2-2：调用栈快照并入 meta 提示行（截断展示，悬停 tooltip 全文）
        String stackDigest = result.getStackDigest();
        String stackLine = "";
        if (stackDigest != null) {
            stackLine = "   " + MpSqlVisualizerBundle.message("panel.meta.stackDigest") + " "
                    + (stackDigest.length() > 120 ? stackDigest.substring(0, 120) + "…" : stackDigest);
        }
        // T-SqlPerf：性能建议文案单独一行（HTML 换行）。
        // T-PreviewTopOverlap F3：原文分段缓存，label 文本按宽度预算重拼（applyMetaTruncation）
        metaLineText = meta + warningLine;
        metaPerfSegments = buildPerfSegments(result);
        metaLabel.setToolTipText(meta + warningLine + stackLine
                + (stackDigest != null
                        ? "\n" + MpSqlVisualizerBundle.message("panel.meta.stackDigest") + " " + stackDigest
                        : "")
                + metaPerfSegments.stream().map(MetaPerfSegment::text).collect(java.util.stream.Collectors.joining("；")));
        metaLabel.setForeground(currentWarning != null
                ? JBColor.namedColor("ErrorForeground", JBColor.RED)
                : JBUI.CurrentTheme.Label.foreground());
        if (errorCopyButton != null) errorCopyButton.setVisible(currentWarning != null);
        // T-PreviewUx-Pack #2：渲染警示横幅（三要素：哪段没还原=渲染产物，为什么=warning 原文，
        // 能做什么=指引）；警示存在即显示，恢复即清除（降级链横幅 DEGRADED 不受本清除影响）
        if (currentWarning != null) {
            degradationBanner.show(
                    DegradationBanner.Kind.RENDER_WARNING,
                    MpSqlVisualizerBundle.message("panel.banner.renderFailed", currentWarning != null ? currentWarning : ""));
        } else {
            degradationBanner.clear(DegradationBanner.Kind.RENDER_WARNING);
        }
        // T-DollarInjectMark：`${}` 直替信息提示（最低优先——警示/降级挂起时让位，
        // 清除后下次 updateMeta 回位；✕ 关闭后会话级记忆不再弹）。只加元数据不改渲染文本。
        if (currentWarning == null && result.getXmlModel() != null && xmlDollarSubstitutions > 0
                && !dollarMarkDismissedSession) {
            degradationBanner.show(
                    DegradationBanner.Kind.DOLLAR_INJECT,
                    MpSqlVisualizerBundle.message("panel.banner.dollarInject", DOLLAR_TOKEN, xmlDollarSubstitutions));
        } else {
            degradationBanner.clear(DegradationBanner.Kind.DOLLAR_INJECT);
        }
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
            // T-UN-13（T-UiNative）：HTML 段色写死 hex → namedColor 取主题色再注入（亮值不变，
            // HTML 结构与截断逻辑不动）
            segments.add(new MetaPerfSegment(prefix + localizeWarning(warning),
                    MpvsUiColors.htmlHex(isError ? MpvsUiColors.PERF_ERROR_TEXT : MpvsUiColors.PERF_INFO_TEXT)));
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
        UiTextClipboard.copy(currentWarning);
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
        // T-AutoFold F5：面板废弃——控制台编辑器引用一并清空（防 EditorFactory 泄漏告警）
        nativeConsoleEditor = null;
        // T-M36.6-03：面板销毁——晚到的预热线程产物作废（预热线程自查后删除临时文件）
        previewDisposed = true;
        // T-Perf-04：防抖/历史 Timer 随面板停止（防晚到 flush 触碰已销毁面板状态）
        renderDebounceTimer.stop();
        pendingRender = null;
        historyAlarm.stop();
        // T-Perf-16：释放 DataSourceSelector 单例滞留引用（弹窗关闭此前不走 clear 按钮路径——
        // prepared/shared/pendingOnConsole 强引用已关闭面板与原生控制台，堆转储滞留整棵面板树）
        SqlExecutor.releaseForPanel();
        // T-PS4-14④：面板 dispose 是 JOIN 虚拟外键注册表的最后语义回收点（T-EmbedFkOrder
        // 「显式语义点回收」三点口径：登记前 registerJoinForeignKeys 自带 clear + 无 JOIN 路径
        // unloadJoinDiagram + 面板 dispose）——最后一张 JOIN 图建图的登记此前驻留到下次建图，
        // 残留期 JoinVirtualFkProvider EP 会给用户自开的 Database 图补出旧连线。runCatching
        // 照邻居清理件风格（释放失败不阻断 dispose 链）
        try {
            cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.clearRegisteredFk();
        } catch (Throwable ignored) {
            // 释放失败不阻断 dispose 链
        }
        // messageBus 连接经 connect(this) 绑定本 Disposable，Disposer.dispose 时自动取消订阅
        Disposer.dispose(this);
    }

    // ------------------------------------------------------------------
    // 测试访问（跨包测试使用；master 侧 internal → public，KOTLIN_TO_JAVA_RULES §0.1）
    // ------------------------------------------------------------------

    public Editor editorForTest() {
        return editor;
    }

    /** T-PreviewConsoleFmt 测试缝：模拟控制台文档已就绪（nativeDoc 指向该文档）。 */
    public void markNativeDocForTest(Document doc) {
        nativeEditorDoc = doc;
    }

    /** T-PreviewConsoleFmt 测试缝：挂载后回填渲染的等价入口。 */
    public void renderSqlForTest() {
        renderSql();
    }

    /** T-AutoFold F5：登记的原生控制台编辑器引用（支链①接入断言用）。 */
    Editor nativeConsoleEditorForTest() {
        return nativeConsoleEditor;
    }

    PsiFile psiFileForTest() {
        return psiFile;
    }

    /**
     * 创建预览 PSI 文件（测试缝，T-PreviewExecDs-01d）：抽取 createEditor 中
     * PsiFileFactory 创建逻辑为独立方法，供测试直接调用（绕过 EDT 回调 +
     * EditorFactory 创建）。
     *
     * <p>T-M42：PsiFileFactory.createFileFromText 在 EDT 上直接调用会触发
     * 2026.2 平台 markGenerated 树遍历 softAssertReadAccess 硬抛——包
     * ReadAction（EDT 上同步执行，语义不变）。
     */
    PsiFile createPreviewFileForTest(Project proj, String sql) {
        return ReadAction.compute(
                (com.intellij.openapi.util.ThrowableComputable<PsiFile, RuntimeException>) () -> {
                    com.intellij.openapi.fileTypes.FileType fileType =
                            com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL");
                    return PsiFileFactory.getInstance(proj).createFileFromText("preview.sql", fileType, sql);
                });
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

    /** T-UxOnboard T-UX-02：预览编辑器空态提示文案（placeholder 接线断言用）。 */
    String previewEmptyHintForTest() {
        return previewEmptyHint;
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

    /** T-RollbackSql：执行状态面板（executeAdhocSql 结果反馈断言用）。 */
    SqlResultPanel resultPanelForTest() {
        return resultPanel;
    }

    /**
     * T-F8：JOIN 图加载态接线探针（测试用）——转发到 DiagramPanel 的 loadingProbeForTest。
     *
     * <p>接线用例据此断「进入加载态 → 渲染 → 退出加载态」的时序（时序为何必须如此见
     * updateDiagramTab 与 showJoinDiagram 的 KDoc）。
     */
    void diagramLoadingProbeForTest(java.util.function.Consumer<Boolean> probe) {
        diagramPanel.loadingProbeForTest = probe;
    }

    /** T-F8：JOIN 图面板（「停转圈时图已渲染」的断言入口）。 */
    DiagramPanel diagramPanelForTest() {
        return diagramPanel;
    }

    /** T-F8：当前是否走原生图路径（「停转圈时图已换入」的另一分支——原生路径不经过 diagramPanel）。 */
    boolean hasNativeDiagramForTest() {
        return currentNativeDiagram != null;
    }

    /**
     * T-TS-05：JOIN 图标签页的加载面（测试断言用）——标签页内容必须<b>是它</b>。
     *
     * <p>与 diagramPanelForTest 的 isLoadingForTest 互补：后者只断「加载态为真」，把标签页内容改回
     * diagramTabHost（加载面不在层级里）也照样为真；真机默认原生图路径下 diagramPanel 被
     * removeAll() 摘出层级，转圈根本画不出来。故需按对象身份断结构。
     */
    JBLoadingPanel diagramLoadingHostForTest() {
        return diagramLoadingHost;
    }

    /** T-TS-05：内容区标签页容器（测试断言用；未切到标签页模式时为 null）。 */
    JTabbedPane contentTabPaneForTest() {
        return contentTabPane;
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

    /**
     * T-InjectionGate T-IG-01：当前预览语句是否含 `${}` 原文拼接段（PreviewResult.hasDollarRawSegments
     * 面板侧读口——复制可执行 SQL 的产物标记注释据此追加）。
     */
    public boolean hasDollarRawSegments() {
        return current != null && current.isHasDollarRawSegments();
    }

    /** F-8：当前预览的表名（可 null）。 */
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

    /** T-PT-02：第 index 个普通参数框的占位提示（emptyText，按类型类别）。 */
    String paramEmptyTextForTest(int index) {
        return index >= 0 && index < paramFields.size() ? paramFields.get(index).getEmptyText().getText() : null;
    }

    /** T-PT-02：第 index 个普通参数行的校验警示文案（无警示返回 null；不阻塞渲染）。 */
    String paramWarningForTest(int index) {
        JBLabel label = index >= 0 && index < paramWarnLabels.size() ? paramWarnLabels.get(index) : null;
        return label != null && label.isVisible() ? label.getToolTipText() : null;
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

    /** T-M23.2 / T-SelToggle：批量「参数列」区全选/全不选切换按钮。 */
    JButton batchToggleSelectButtonForTest() {
        return batchToggleSelectButton;
    }

    Set<String> enabledIdsForTest() {
        return new LinkedHashSet<>(enabledIds);
    }

    /** T-SelToggle：动态条件区全选/全不选切换按钮。 */
    JButton toggleSelectButtonForTest() {
        return toggleSelectButton;
    }

    JButton clearButtonForTest() {
        return clearButton;
    }

    JButton batchClearButtonForTest() {
        return batchClearButton;
    }

    /** T-PreviewBgFit 测试缝：左侧参数滚动区（背景/不透明断言用）。 */
    JBScrollPane paramScrollForTest() {
        return paramScroll;
    }

    /** T-M26.1→T-UiNative：左右分栏（平台 OnePixelSplitter，原生分隔条主题融合 + 比例记忆）。 */
    OnePixelSplitter splitPaneForTest() {
        return findSplitPane(this);
    }

    private static OnePixelSplitter findSplitPane(java.awt.Container c) {
        for (Component child : c.getComponents()) {
            // OnePixelSplitter 的 orientation 为 boolean（false = 水平）
            if (child instanceof OnePixelSplitter && !((OnePixelSplitter) child).getOrientation()) {
                return (OnePixelSplitter) child;
            }
            if (child instanceof java.awt.Container) {
                OnePixelSplitter found = findSplitPane((java.awt.Container) child);
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

    /** T-PreviewUx-Pack #2：诚实降级横幅（显隐/文案/类别断言用）。 */
    DegradationBanner degradationBannerForTest() {
        return degradationBanner;
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
