package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener;
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
// T-UiCoord F2：下拉按钮文本截尾省略复用 meta 行的同款纯函数（避免第二份省略实现）
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelKt;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具窗口面板（标签页 + 工具栏；headless 测试不布局，交互断言走条目模型层）。
 *
 * 筛选项精简改造（2026-08-27）：
 * - 类型筛选：工具栏行「SQL类型」多选按钮（弹窗勾选，替代原两行按钮组）
 * - 高频折叠：整体移除（识别重复 SQL 的价值由去重 ⨉N 徽章承接）
 * - 去重徽章：开启「移除重复的sql」后被丢弃的 SQL 在条目旁累加显示 ⨉N
 * - Ctrl+F/⌘F 搜索：默认隐藏的搜索条，快捷键唤起实时过滤（Esc 清空后再按收起）
 */
public class SqlLogPanel extends JPanel implements Disposable {

    private final Project project;
    private final SqlLogManager manager;

    private final DefaultListModel<LogSqlEntry> listModel = new DefaultListModel<>();
    private final SqlEntryList list = new SqlEntryList(listModel);
    /** 列表条目单元格渲染器（internal 供 UI 测试直接渲染、断言 HTML 输出）。 */
    private LogEntryListCellRenderer entryCellRenderer;
    /** T-ClickPreview：单击出轻量预览弹窗（点击时按行捕获 SQL，锚点在行下缘不遮行），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。
     *  T-ListPreviewCopy：预览文本经 clickPreviewSql——设置「预览时格式化还原 SQL」开启（默认）时多行格式化显示。 */
    private SqlClickPreview clickPreview;
    private final JBTabbedPane tabs = new JBTabbedPane();
    /** T-M38：日志转 SQL 转换 tab（粘贴日志 → 批量还原可执行 SQL；原「SQL 预览」被动展示改造）。 */
    private final LogToSqlPanel logToSqlPanel;
    /** T-M36.4：SQL → Java 转换 tab（粘贴 SQL → 格式化/补全 → 转换 → 可编辑 Java 高亮结果区）。 */
    private final SqlToJavaPanel sqlToJavaPanel;
    /** F-44：历史 SQL tab（读取 .idea 目录中的自动保存文件，支持源码定位）。仅 logAutoSaveEnabled 时创建。 */
    private HistorySqlLogPanel historyPanel = null;
    /** F-44：历史 tab 在 JBTabbedPane 中的索引（-1 表示未添加）。 */
    private int historyTabIndex = -1;

    private final JCheckBox dedupeCheckBox;

    /**
     * SQL类型多选按钮（点击弹可勾选弹窗；文案只显示选中状态：全部 / 无 / 「查询, 更新」组合）。
     *
     * T-UiCoord F2（2026-09-21 用户定案「工具栏不要两行」）：原「SQL类型：」标签控件退役，
     * 标签键转作 tooltip 前缀（tooltip = `SQL类型：` + 当前状态**全文**）；按钮显示文本按
     * {@link #DROPDOWN_TEXT_BUDGET_PX} 截尾省略——文本定宽封顶是多选后行宽不漂移、单行恒成立的前提。
     */
    private final JButton typeFilterButton;

    /**
     * 排序方式按钮（T-LogSortModes）：点击弹单选菜单，文案显示当前方式；与「SQL类型」按钮同款式
     * （原生 LaF 扁平按钮 + 右侧下箭头），默认时间升序。取代原「耗时排序」双态切换按钮——
     * 排序恒有确定方式，不再依赖「是否检测到耗时」决定显隐。
     *
     * T-UiCoord F2：原「排序：」标签控件同样退役（标签键转 tooltip 前缀），文本同样截尾封顶。
     */
    private final JButton sortButton;

    /** 搜索条（默认隐藏，Ctrl+F/⌘F 唤起；实时过滤 restoredSql 与摘要）。 */
    private final SearchTextField searchField;

    // ---------- 过滤/排序状态 ----------

    /** 当前激活的类型过滤集合（默认全选 = 「全部」，含 OTHER 透传）。 */
    private final Set<StatementType> activeFilter = new LinkedHashSet<>(SqlLogPanelKt.FILTERABLE_TYPES);

    /** 排序模式（T-LogSortModes：默认时间升序 = 捕获顺序）。 */
    private SortMode sortMode = SortMode.TIME_ASC;

    /** 当前搜索词（搜索条实时同步；空串 = 不过滤）。 */
    private String searchText = "";

    /**
     * M56 T-M56-00：动态聚焦过滤状态（基础设施；null = 不过滤）。
     * T-M56-02/06/07 起 agent 通道接管后由页签/请求聚合驱动写入；桥通道恒 null 与现状逐字一致。
     */
    private volatile SqlLogPanelKt.FocusFilter activeFocus = null;

    /** 去重丢弃计数快照（refreshList 后更新；cellRenderer 渲染 ⨉N 徽章用）。 */
    private Map<String, Integer> dedupeCounts = Map.of();

    // ---------- T-Perf-03（自 dev 移植）：增量刷新状态 ----------

    /** 刷新代际：新 refreshList 启动时 +1，旧分批追加链检测到代际过期即终止。 */
    private long refreshGeneration = 0L;

    /** 上一轮渲染的可见列表（同一性差分基线；由 applyIncremental/applyFullRebuild 与 listModel 保持同步）。 */
    private List<LogSqlEntry> lastRendered = List.of();

    // ---------- T-Perf-15（自 dev 移植）：manager 监听器命名引用（init 注册 / dispose 注销） ----------

    /** 缓冲变更 → 列表刷新（manager.uiNotifyListener 回调链；EDT 执行）。 */
    private final Runnable uiRefreshListener = this::refreshList;

    /** capturing 状态变化 → 刷新捕捉动作 presentation（T-CaptureBack：捕捉开关回迁面板工具栏，
     *  图标/文案由 CaptureAction.update 按 capturing 态输出）。 */
    private final Runnable capturingUiListener = new Runnable() {
        @Override
        public void run() {
            LOG.info("SqlLogPanel.capturingListener: 触发");
            ApplicationManager.getApplication().invokeLater(() -> {
                // T-UiNative T-UN-11：反射 hack（updateCaptureButtonIcon 遍历组件树强改 ActionButton 图标）
                // 退役——回归平台更新机制。真实 IDE 中工具窗可见，isShowing 满足，updateActionsImmediately
                // 正常执行；沙盒/单测中组件不可见时平台会跳过更新——测试断言直接读
                // captureToggleAction.presentation，不依赖按钮刷新。
                ActionToolbar toolbar = _toolbarRef;
                if (toolbar != null) {
                    toolbar.updateActionsImmediately();
                }
                // T-CaptureTruth T-CT-03：捕捉态变化 → 诊断行立即跟上（起停捕捉是用户最先看的时刻）
                syncDiagnostics();
            });
        }
    };

    // ---------- T-Perf-24（自 dev 移植）：搜索输入防抖 ----------

    /** 搜索输入防抖 Timer——每击键一次 O(N) 刷新 → 停顿 200ms 应用一次。 */
    private final javax.swing.Timer searchDebounceTimer;

    /** T-Perf-24（自 dev 移植）：搜索输入防抖窗口（毫秒）。 */
    private static final int SEARCH_DEBOUNCE_MS = 200;

    /**
     * T-UiCoord F2：两个下拉按钮（SQL类型 / 排序）显示文本的像素上限（@100% 缩放，用 JBUI.scale 取用）。
     *
     * 定宽封顶是「工具栏恒为单行」的前提之一：`SQL类型` 多选后文案会到「查询, 更新, 删除, 插入」，
     * 不封顶时行宽随选择漂移（旧流式行正是这么折到第二行的）。封顶后按钮宽度只由预算决定，
     * 超限截尾省略、tooltip 恒全文（见 {@link #clipToDropdownBudget}）。
     *
     * **取值依据（2026-09-21 headless 实测，非估算）**：每个下拉按钮的固定开销 = 图标 16 + LaF 内边距 38
     * ≈ 54px（与文本无关），勾选固定 102px。用户侧边栏 460px、图标组真机量测约 133px 下：
     * 默认态（「全部」）控制组 ≈300 → 合计 433（余 27px）；多选态封顶后控制组 ≈333 → 合计 466。
     * 预算取 64（不是初版计划的 78）：78 时多选态控制组 346，超 `460 − 图标组保守下限 120 = 340`
     * 的预算锁。多选态余下约 6px 的超出量（相对图标组量测中值）由平台图标溢出兜底，
     * 属登记边界而非缺陷——见计划 §七 待验证前提 2。
     */
    private static final int DROPDOWN_TEXT_BUDGET_PX = 64;
    /** T-CaptureTruth T-CT-03：零产出诊断刷新间隔（与桥轮询同频 2s）。 */
    private static final int DIAGNOSTICS_TICK_MS = 2000;

    // ---------- O-1 日志窗口差异化：慢查询计数器 + 耗时着色 ----------

    /** 慢查询计数器（O-1 Phase C）。 */
    private final SlowQueryCounter slowQueryCounter =
            new SlowQueryCounter(PluginSettings.getInstance().getState().getSlowQueryThresholdMs());
    /** 工具栏慢查询计数标签（动态更新）。 */
    private JLabel slowQueryLabel = null;

    // ---------- UI 组件（工具栏单行：动作图标组靠左 + 列表控制组靠右） ----------

    /** 工具栏引用（capturing 变化时经平台更新机制刷新动作 presentation，T-UN-11）。 */
    private ActionToolbar _toolbarRef = null;
    /** 工具栏动作组（T-M39：internal 供测试断言帮助入口存在性）。
     *  T-CaptureBack：捕捉开关回迁首位（捕捉/清空/设置/搜索/帮助；搜索在帮助左侧为用户指定排序）。 */
    public final DefaultActionGroup toolbarGroup;

    // ---------- T-M55-03/04：动态聚焦过滤页签栏字段（须在 init 块之前——Kotlin 按文本序初始化，
    // init 里 buildTopPanel 会读这些字段） ----------

    /** 当前开启的聚焦页签（EDT 读写——右键菜单/页签关闭按钮均在 EDT）。 */
    private final List<FocusTab> focusTabs = new ArrayList<>();

    /** 聚焦页签栏（工具栏行与搜索条之间；无页签时隐藏不占高度）。 */
    private final JPanel focusTabBar = new JPanel(new WrapLayout());

    /** 聚焦隐藏计数提示（聚焦视图下不匹配任何页签的条目数；无页签时隐藏）。 */
    private final JLabel focusHiddenLabel = new JLabel();

    // ---------- T-UiCoord F2：工具栏单行宿主字段（同样须在 init 之前——init 里 buildToolbar 会赋值；
    // 声明晚于 init 会让赋值被随后的 `= null` 初始化器覆盖回 null） ----------

    /** 工具栏单行宿主（`buildToolbar()` 产物）。 */
    private JPanel toolbarRow = null;

    /** 工具栏右侧控制组（EAST 宿主内那层 FlowLayout 面板）。 */
    private JPanel toolbarControls = null;

    /** 工具栏动作图标组组件（CENTER）——结构断言用（曾因还原反向验证探针时误删 CENTER 挂载而漏）。 */
    private JComponent toolbarComponent = null;

    /**
     * 诊断刷新定时器（仅「捕捉中且零条目」时运行）。
     *
     * <p>为什么需要定时器：零产出意味着缓冲不发任何通知，而控制台行数/增强挂载态都在变——不主动拉取
     * 的话诊断行会永远停在「捕捉开始那一刻」的快照上，恰好在用户真正想知道「为什么还是没有」时失语。
     * 2s 与桥轮询同频（SqlLogConsoleBridge POLL_INTERVAL_MS）；命中条目或停止捕捉即停表，常态零开销。
     */
    private javax.swing.Timer diagnosticsTimer = null;

    /** 当前打开的类型弹窗内勾选框（全选/全不选即时同步用；弹窗每次打开重建）。 */
    private List<kotlin.Pair<StatementType, JCheckBox>> typePopupCheckBoxes = List.of();

    /** T-SelToggle：弹窗底部全选/全不选切换按钮（T-SelToggle 双按钮合一；弹窗每次打开重建，与 typePopupCheckBoxes 同生命周期约定）。 */
    private JButton typePopupToggleButton = null;

    /**
     * T-SelToggle：弹窗切换按钮文案——activeFilter 全勾 → 「全不选」（点击清空），
     * 其余态（含空集）→ 「全选」（点击填满）。判定与外层「全部」文案（updateTypeFilterButton）同口径。
     */
    private String typePopupToggleLabel() {
        return MpSqlVisualizerBundle.message(
                activeFilter.containsAll(SqlLogPanelKt.FILTERABLE_TYPES)
                        ? "log.filter.selectNone"
                        : "log.filter.selectAll");
    }

    /**
     * T-SelToggle：弹窗切换按钮文案随勾选态刷新。弹窗无公共渲染下游可收口
     * （updateTypeFilterButton/refreshList 还被搜索等非弹窗链路调用），三个勾选态变更入口
     * （弹窗构建末尾、applyAllTypes 末尾、单类型复选框 listener）显式调用本函数。
     */
    private void syncTypePopupToggleLabel() {
        JButton button = typePopupToggleButton;
        if (button != null) {
            String label = typePopupToggleLabel();
            if (!label.equals(button.getText())) {
                button.setText(label);
            }
        }
    }

    /**
     * 弹出类型多选弹窗：勾选即时生效且弹窗不关闭（JCheckBox 非 menu item，
     * 点击不触发 popup 收起，支持连续勾选组合）；底部单个全选/全不选切换按钮（T-SelToggle）
     * 一键设置——同步刷新弹窗内勾选框选中态 + 按钮文案 + 列表（弹窗保持打开，立即可见）。
     */
    private void showTypeFilterPopup() {
        // T-UiNative P1a：JPopupMenu 外壳 → 平台 JBPopup（createComponentPopupBuilder 承载 JCheckBox 面板；
        // 触发源是工具栏按钮 ActionListener 无 MouseEvent，锚点沿用原 show 坐标——按钮正下方 2px）
        JPanel contentPanel = buildTypeFilterContent();
        JBPopupFactory.getInstance()
                .createComponentPopupBuilder(contentPanel, contentPanel)
                .setRequestFocus(false)
                .setCancelOnClickOutside(true)
                .createPopup()
                .show(new RelativePoint(typeFilterButton, new Point(0, typeFilterButton.getHeight() + 2)));
    }

    /**
     * T-SelToggle：构建弹窗内容（勾选框区 + 分隔线 + 底部切换按钮），赋值 typePopupCheckBoxes
     * 与 typePopupToggleButton。与 showTypeFilterPopup 拆分是纯提取（行为零变更），
     * 使测试可脱离 popup.show 直接驱动勾选框/切换按钮（headless 下弹层 show 不可靠）。
     */
    private JPanel buildTypeFilterContent() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(8, 12, 8, 12));
        List<kotlin.Pair<StatementType, JCheckBox>> checkBoxes = new ArrayList<>();
        for (StatementType type : SqlLogPanelKt.FILTERABLE_TYPES) {
            JCheckBox cb = new JCheckBox(labelForType(type), activeFilter.contains(type));
            cb.setOpaque(false);
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            cb.setFocusable(false);
            cb.addActionListener(e -> {
                if (cb.isSelected()) {
                    activeFilter.add(type);
                } else {
                    activeFilter.remove(type);
                }
                updateTypeFilterButton();
                refreshList();
                // T-SelToggle：单类型勾选即时同步切换按钮文案
                syncTypePopupToggleLabel();
            });
            checkBoxes.add(new kotlin.Pair<>(type, cb));
            // T-CrudColor：复选框前置色块图例——否则用户得去设置页才能把颜色和类型对上号。
            // 样式为 off（或该类型无标记色）时不加色块，避免出现「有图例却无标记」的错位。
            // 包一层行面板保持 checkBoxes 结构与 applyAllTypes 语义不变。
            Color tint = SqlListTypeColor.colorOf(type);
            JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            rowPanel.setOpaque(false);
            rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            rowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, cb.getPreferredSize().height + 2));
            if (tint != null) {
                rowPanel.add(new JLabel(colorSwatchIcon(tint)));
            }
            rowPanel.add(cb);
            content.add(rowPanel);
        }
        typePopupCheckBoxes = checkBoxes;
        content.add(Box.createVerticalStrut(6));
        JSeparator sep = new JSeparator();
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(sep);
        content.add(Box.createVerticalStrut(6));
        JButton toggleButton = new JButton(typePopupToggleLabel());
        toggleButton.setFocusable(false);
        toggleButton.addActionListener(e -> applyAllTypes(!activeFilter.containsAll(SqlLogPanelKt.FILTERABLE_TYPES)));
        typePopupToggleButton = toggleButton;
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(toggleButton);
        content.add(row);
        return content;
    }

    /**
     * T-CrudColor：类型色块图例图标（12×12 实心方块）。
     * 用于类型过滤弹窗——纯色块、无边框，与列表中的标记同色，起对照作用。
     */
    private Icon colorSwatchIcon(Color color) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return 12;
            }

            @Override
            public int getIconHeight() {
                return 12;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(color);
                g.fillRect(x, y, getIconWidth(), getIconHeight());
            }
        };
    }

    /** 全选/全不选（全选 = 「全部」语义，列表透传含 OTHER）：同步弹窗内勾选框 + 按钮文案 + 列表。 */
    private void applyAllTypes(boolean selected) {
        activeFilter.clear();
        if (selected) {
            activeFilter.addAll(SqlLogPanelKt.FILTERABLE_TYPES);
        }
        for (kotlin.Pair<StatementType, JCheckBox> p : typePopupCheckBoxes) {
            p.getSecond().setSelected(activeFilter.contains(p.getFirst()));
        }
        updateTypeFilterButton();
        refreshList();
        // T-SelToggle：切换按钮点击后文案翻转（此时 activeFilter 已是新态）
        syncTypePopupToggleLabel();
    }

    public SqlLogPanel(@NotNull Project project, @NotNull SqlLogManager manager) {
        super(new BorderLayout());
        this.project = project;
        this.manager = manager;

        this.dedupeCheckBox = new JCheckBox(MpSqlVisualizerBundle.message("log.panel.dedupe"));
        dedupeCheckBox.setSelected(manager.getEntries().isDedupeEnabled());
        dedupeCheckBox.addActionListener(e -> {
            boolean nowChecked = dedupeCheckBox.isSelected();
            boolean wasEnabled = manager.getEntries().isDedupeEnabled();
            if (nowChecked == wasEnabled) {
                return;
            }
            if (nowChecked) {
                manager.getEntries().setDedupeEnabled(true);
                manager.getEntries().resetDedupeCounts();
                // F-44-2 用户实测缺：勾选「移除重复的sql」后立即对已有条目去重
                // —— 此前仅清计数、不裁剪缓冲，重复 SQL 继续展示
                long removed = manager.getEntries().dedupeExisting();
                if (removed > 0) {
                    LOG.info("dedupeCheckBox: 开启去重后裁掉了 " + removed + " 条已存在重复 SQL");
                }
            } else {
                // 关闭去重：清空累计计数（语义：关闭 = 本次累计清零）
                manager.getEntries().disableDedupe();
            }
            refreshList();
        });

        this.typeFilterButton = new JButton();
        typeFilterButton.setFocusable(false);
        typeFilterButton.setIcon(AllIcons.General.ArrowDown);
        typeFilterButton.setHorizontalTextPosition(SwingConstants.LEFT); // 图标在文字右侧（下拉按钮惯例）
        typeFilterButton.addActionListener(e -> showTypeFilterPopup());

        this.sortButton = new JButton();
        sortButton.setFocusable(false);
        sortButton.setIcon(AllIcons.General.ArrowDown);
        sortButton.setHorizontalTextPosition(SwingConstants.LEFT); // 图标在文字右侧（下拉按钮惯例，同 SQL类型）
        sortButton.addActionListener(e -> showSortPopup());

        this.logToSqlPanel = new LogToSqlPanel(project);
        this.sqlToJavaPanel = new SqlToJavaPanel(project);
        this.searchField = buildSearchField();
        this.searchDebounceTimer = new javax.swing.Timer(SEARCH_DEBOUNCE_MS, e -> applySearchText());
        this.searchDebounceTimer.setRepeats(false);
        // T-ListPreviewCopy：预览文本经 clickPreviewSql——设置「预览时格式化还原 SQL」开启（默认）时多行格式化显示
        this.clickPreview = new SqlClickPreview(project, list, i -> SqlClickPreview.clickPreviewSql(
                listModel.getElementAt(i).restoredSql(),
                PluginSettings.getInstance().getState().getLogFormatSql()));
        this.captureToggleAction = new CaptureAction();
        this.toolbarGroup = new DefaultActionGroup(
                captureToggleAction, // 捕捉开关（回迁面板工具栏首位，T-CaptureBack）
                new ClearAction(),
                new SettingsAction(),
                new ShowSearchAction(), // 搜索在帮助左侧（用户指定排序）
                new HelpAction()
        );
        // T-M55-03/04：聚焦页签栏与隐藏计数提示初始态（无页签隐藏不占高度）
        this.focusTabBar.setOpaque(false);
        this.focusTabBar.setVisible(false);
        this.focusHiddenLabel.setForeground(MpvsUiColors.META_GRAY);
        this.focusHiddenLabel.setVisible(false);

        // 注册子面板到 Disposer 树：父面板 dispose 时级联释放子面板（及其 DocumentListener），
        // 避免 IDE 退出时 ObjectTree 报 Memory leak detected
        Disposer.register(this, logToSqlPanel);
        Disposer.register(this, sqlToJavaPanel);

        // 标签 1：SQL 日志（工具栏行 + 搜索条 + 条目列表）
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.add(buildTopPanel(), BorderLayout.NORTH);
        logPanel.add(buildListPane(), BorderLayout.CENTER); // 条目列表铺满剩余空间

        // 标签 2：日志转 SQL（粘贴日志 → 批量还原可执行 SQL）
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logs"), logPanel);
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logToSql"), logToSqlPanel);
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.sqlToJava"), sqlToJavaPanel);
        // F-44：开启自动保存、或 .idea 中已有历史文件时显示历史 tab
        if (PluginSettings.getInstance().getState().getLogAutoSaveEnabled() || hasHistoryFiles()) {
            addHistoryTab();
        }
        add(tabs, BorderLayout.CENTER);

        // T-M36.6：tab 重选时刷新形态下拉（设置页修改后切回即同步；不触发重生成）
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == sqlToJavaPanel) {
                sqlToJavaPanel.refreshFormatFromSettings();
            }
            // F-44：切换到历史 tab 时刷新数据
            if (historyTabIndex >= 0 && tabs.getSelectedIndex() == historyTabIndex) {
                getHistoryPanel().refresh();
            }
        });

        // F-44 历史 tab 显隐跟随设置（设置页修改后即时生效，无需切 tab）
        SettingsChangeListener settingsListener = () -> ApplicationManager.getApplication().invokeLater(() -> {
            updateHistoryTabVisibility();
            // T-LogListFreeze-04：片段渲染直读设置态（时间戳/耗时显示/慢查询阈值）——设置变更后
            // 走 refreshList 重排重绘即生效（SINGLE_LINE 直渲染；多行模式片段缓存按模式键自动失效）
            // T-ListMode：显示模式入口已迁设置页（T-DisplayModeToSettings）——设置页 apply 广播后
            // 行高随模式重设。T-MergeFollowup-03（复查二轮）：BasicListUI 的 cellHeights 布局缓存
            // <b>不归 Swing layout 管</b>——revalidate 无效；且多行模式间切换是 -1→-1（setFixedCellHeight
            // 值不变不触发 propertyChange 失效），refreshList 增量路径在内容无变化时也不产生 model
            // 结构事件——updateUI() 重装列表 UI 强制布局状态失效，是最直接可靠的失效手段。
            applyListCellHeight();
            list.updateUI();
            refreshList();
            // T-CrudColor：历史二级列表同样直读设置，重绘即生效（不重扫文件、不打断浏览位置）。
            // 仅当历史 tab 已挂载时才取面板——避免仅为重绘而强制实例化（historyPanel 惰性创建）
            if (tabs.indexOfTab(MpSqlVisualizerBundle.message("log.tab.history")) >= 0) {
                getHistoryPanel().repaintSqlList();
            }
        });
        project.getMessageBus().connect(this).subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, settingsListener);

        // T-Perf-15（自 dev 移植）：监听器改命名引用——dispose 时精确注销（此前 addUiListener/addCapturingListener
        // 只加不减，面板随 tool window content 销毁重建逐次累积）
        manager.addUiListener(uiRefreshListener);
        refreshList();

        // capturing 变化 → 刷新捕捉动作（T-UiNative T-UN-11：动作挂工具窗标题栏，presentation
        // 由 CaptureAction.update 按 capturing 态输出；监听经平台更新机制刷新，沙盒测试断言直读
        // captureToggleAction.presentation）
        manager.addCapturingListener(capturingUiListener);
    }

    private HistorySqlLogPanel getHistoryPanel() {
        if (historyPanel == null) {
            historyPanel = new HistorySqlLogPanel(project);
        }
        return historyPanel;
    }

    // ---------- T-M55-03/04：动态聚焦过滤页签栏（页签=过滤器，多开并集，可关闭；不碰差分） ----------
    // 字段（focusTabs/focusTabBar/focusHiddenLabel）声明在类头部 init 块之前（文本序初始化）

    /** 当前开启的聚焦页签（测试断言）。 */
    java.util.List<FocusTab> focusTabsForTest() {
        return List.copyOf(focusTabs);
    }

    /** 聚焦页签栏子组件数（测试断言：页签数 = chips + 前缀标签 + 计数标签）。 */
    int focusTabBarComponentCountForTest() {
        return focusTabBar.getComponentCount();
    }

    /** Kotlin {@code val focusTabBarComponentCountForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public int getFocusTabBarComponentCountForTest() {
        return focusTabBar.getComponentCount();
    }

    /** 聚焦隐藏计数提示（测试断言显隐与文案）。 */
    JLabel focusHiddenLabelForTest() {
        return focusHiddenLabel;
    }

    /** Kotlin {@code val focusHiddenLabelForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public JLabel getFocusHiddenLabelForTest() {
        return focusHiddenLabel;
    }

    /** 测试驱动：等价右键「聚焦调用方方法/按请求聚焦」添加页签。 */
    void addFocusTabForTest(FocusTab tab) {
        addFocusTab(tab);
    }

    /** 测试驱动：等价页签 ✕ 关闭。 */
    void removeFocusTabForTest(FocusTab tab) {
        removeFocusTab(tab);
    }

    private void addFocusTab(FocusTab tab) {
        List<FocusTab> next;
        if (tab instanceof FocusTab.Request request) {
            next = FocusTab.addRequestTab(focusTabs, request.requestTag());
        } else if (tab instanceof FocusTab.Method) {
            // Kotlin：focusTabs.contains(tab) ? focusTabs.toList() : focusTabs + tab
            next = new ArrayList<>(focusTabs);
            if (!next.contains(tab)) {
                next.add(tab);
            }
        } else {
            next = new ArrayList<>(focusTabs);
        }
        focusTabs.clear();
        focusTabs.addAll(next);
        rebuildFocusTabBar();
        refreshList();
    }

    private void removeFocusTab(FocusTab tab) {
        if (focusTabs.remove(tab)) {
            rebuildFocusTabBar();
            refreshList();
        }
    }

    /** 重建页签栏 chips（页签数 = 前缀标签 + N×chip + 计数标签）。 */
    private void rebuildFocusTabBar() {
        focusTabBar.removeAll();
        focusTabBar.add(new JLabel(MpSqlVisualizerBundle.message("log.focus.label")));
        for (FocusTab focusTab : focusTabs) {
            focusTabBar.add(buildFocusChip(focusTab));
        }
        focusTabBar.add(focusHiddenLabel);
        focusTabBar.setVisible(!focusTabs.isEmpty());
        if (focusTabBar.getParent() != null) {
            focusTabBar.getParent().revalidate();
            focusTabBar.getParent().repaint();
        }
    }

    /** 单个聚焦页签 chip：名称 + ✕ 关闭（关闭即摘除过滤器并刷新）。 */
    private JComponent buildFocusChip(FocusTab tab) {
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        chip.setOpaque(false);
        JLabel nameLabel = new JLabel(tab.label());
        nameLabel.setToolTipText(tab.label());
        chip.add(nameLabel);
        JButton closeButton = new JButton(AllIcons.Actions.Close);
        closeButton.setFocusable(false);
        closeButton.setToolTipText(MpSqlVisualizerBundle.message("log.focus.close"));
        closeButton.addActionListener(e -> removeFocusTab(tab));
        chip.add(closeButton);
        return chip;
    }

    /** 右键入口的聚焦页签解析（sourceFile+sourceLine → 方法行号区间页签）；解析失败返回 null。 */
    FocusTab.Method resolveCallerMethodFocus(LogSqlEntry entry) {
        CallerMethodRangeResolver.CallerMethodRange range =
                CallerMethodRangeResolver.resolve(project, entry.sourceFile(), entry.sourceLine());
        if (range == null) {
            return null;
        }
        return new FocusTab.Method(range.sourceFile(), range.startLine(), range.endLine(),
                range.className(), range.methodName());
    }

    // ---------- 顶部区域（工具栏行 + 聚焦页签栏 + 搜索条） ----------

    /** 顶部竖向容器：工具栏行（NORTH）+ 聚焦页签栏（CENTER，无页签隐藏不占高度）+ 搜索条（SOUTH）。 */
    private JPanel buildTopPanel() {
        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolbar(), BorderLayout.NORTH);
        top.add(focusTabBar, BorderLayout.CENTER);
        top.add(searchField, BorderLayout.SOUTH);
        return top;
    }

    private JPanel buildToolbar() {
        // T-M31.16：暂停按钮移除（捕捉开关已覆盖——用户方案 A）
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(TOOLBAR_ID, toolbarGroup, true);
        toolbar.setTargetComponent(this);
        _toolbarRef = toolbar; // 保存引用供 capturing 监听刷新

        updateTypeFilterButton();
        updateSortButtonText();
        // T-UiCoord F2（2026-09-21 用户定案「不要两行」）：工具栏**恒为单行**——
        // 左侧动作图标组（CENTER，拿剩余宽度；窄了交给平台图标溢出）+ 右侧列表控制组
        // （EAST：慢查询徽章 / SQL类型 / 排序 / 去重勾选，勾选仍在最右）。
        // BorderLayout 结构上不存在折行可能，故任意宽度下都只有一行；原 WrapLayout 流式行退役
        // ——它按「谁最后谁折」处理放不下，2026-09-16 加排序单元、09-18 加第 5 枚图标后，
        // 460px 侧边栏下「排序」单元孤行到第二行（右留 300px 空档、两行左缘差 10px）。
        JPanel row = new JPanel(new BorderLayout());
        row.add(toolbar.getComponent(), BorderLayout.CENTER);
        toolbarComponent = toolbar.getComponent();

        // O-1：慢查询计数徽章。T-UiCoord F2：由「慢查询: N」全文改短徽章（复用存量键
        // log.panel.slowQueryBadge）让出一行余量，全文进 tooltip（见 updateSlowQueryLabel）。
        slowQueryLabel = new JLabel();
        // T-UN-13（T-UiNative）：写死红 → namedColor（亮值不变，主题可覆写）
        slowQueryLabel.setForeground(MpvsUiColors.SLOW_RED);
        slowQueryLabel.setFont(slowQueryLabel.getFont().deriveFont(Font.BOLD));
        slowQueryLabel.setVisible(false); // 默认隐藏，有慢查询时显示
        // 控制组：Flow(RIGHT) 单行右对齐；外包 Y 轴 BoxLayout + 上下 glue 使整组垂直居中
        // （FlowLayout 自身按顶部摆放，直接挂 EAST 会与图标组差几像素不共轴）
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        controls.setOpaque(false);
        controls.add(slowQueryLabel);
        controls.add(typeFilterButton);
        controls.add(sortButton);
        controls.add(dedupeCheckBox);
        toolbarControls = controls;
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setOpaque(false);
        right.add(Box.createVerticalGlue());
        right.add(controls);
        right.add(Box.createVerticalGlue());
        row.add(right, BorderLayout.EAST);
        toolbarRow = row;
        return row;
    }

    /** 搜索条（默认隐藏；Ctrl+F/⌘F 经 ShowSearchAction 唤起）。 */
    private SearchTextField buildSearchField() {
        SearchTextField field = new SearchTextField(false); // 不启用历史弹窗
        field.setVisible(false);
        field.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.search.emptyText"));
        field.getTextEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onSearchTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onSearchTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onSearchTextChanged();
            }
        });
        // Esc 收起：文本非空时平台已注册 ACTION_CLEAR_TEXT（SearchTextField.addNotify）先清空，
        // 故仅空文本时走到这里 → 收起搜索条（满足「非空先清空，已空再收起」两段式）
        field.addKeyboardListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE && field.getText().isEmpty()) {
                    hideSearch();
                    e.consume();
                }
            }
        });
        return field;
    }

    /** Ctrl+F/⌘F 唤起搜索条（快捷键经 CommonShortcuts.getFind() 平台 keymap 自适应 mac/win）。 */
    private class ShowSearchAction extends AnAction {
        ShowSearchAction() {
            registerCustomShortcutSet(CommonShortcuts.getFind(), list);
        }

        @Override
        public void update(AnActionEvent e) {
            // 图标必须在 update 里写（工具栏 ActionButton 按 presentation 渲染；
            // 不设则渲染为占位圆圈——用户实测缺陷）
            e.getPresentation().setIcon(AllIcons.Actions.Search);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.search"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            showSearch();
        }
    }

    private void showSearch() {
        if (!searchField.isVisible()) {
            searchField.setVisible(true);
            if (searchField.getParent() != null) {
                searchField.getParent().revalidate();
                searchField.getParent().repaint();
            }
        }
        searchField.requestFocus();
    }

    private void hideSearch() {
        searchField.setText(""); // 非空时触发 DocumentListener 清过滤并刷新
        searchField.setVisible(false);
        if (searchField.getParent() != null) {
            searchField.getParent().revalidate();
            searchField.getParent().repaint();
        }
        list.requestFocusInWindow();
    }

    private void onSearchTextChanged() {
        // T-Perf-24（自 dev 移植）：防抖——停顿 200ms 才应用一次过滤
        searchDebounceTimer.restart();
    }

    private void applySearchText() {
        searchText = searchField.getText();
        refreshList();
    }

    // ---------- SQL类型多选弹窗 ----------

    /**
     * 更新 SQL类型按钮文案（全部 / 无 / 类型组合）。
     *
     * T-UiCoord F2：标签控件退役后语义由 tooltip 承载——显示文本按预算截尾省略（多选组合可能很长），
     * tooltip 恒为「SQL类型：」+ 完整状态。
     */
    private void updateTypeFilterButton() {
        String state;
        if (activeFilter.containsAll(SqlLogPanelKt.FILTERABLE_TYPES)) {
            state = MpSqlVisualizerBundle.message("log.filter.all");
        } else if (activeFilter.isEmpty()) {
            state = MpSqlVisualizerBundle.message("log.filter.type.none");
        } else {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (StatementType type : SqlLogPanelKt.FILTERABLE_TYPES) {
                if (activeFilter.contains(type)) {
                    if (!first) sb.append(", ");
                    sb.append(labelForType(type));
                    first = false;
                }
            }
            state = sb.toString();
        }
        typeFilterButton.setText(clipToDropdownBudget(state, typeFilterButton));
        typeFilterButton.setToolTipText(MpSqlVisualizerBundle.message("log.filter.type.label") + state);
    }

    /**
     * T-UiCoord F2：下拉按钮显示文本按预算截尾省略（复用 meta 行的 {@link SqlPreviewPanelKt#clipTailToWidth} 纯函数）。
     * 预算用 JBUI 缩放表达——文本宽度随缩放变化，写死像素会在高 DPI 下失效。
     * tooltip 恒全文，省略只发生在按钮显示层。
     */
    private String clipToDropdownBudget(String text, JComponent target) {
        FontMetrics metrics = target.getFontMetrics(target.getFont());
        return SqlPreviewPanelKt.clipTailToWidth(text, metrics::stringWidth, JBUI.scale(DROPDOWN_TEXT_BUDGET_PX));
    }

    // ---------- 工具栏动作 ----------

    /**
     * F-44：根据 logAutoSaveEnabled 设置 + 历史数据存在与否同步历史 tab 的显隐。
     * 开启自动保存时显示；关闭后若 .idea 中仍有历史文件，也保留 tab（只读查看已有数据）。
     * 仅当无自动保存且无历史数据时才移除 tab。
     */
    private void updateHistoryTabVisibility() {
        boolean enabled;
        try {
            enabled = PluginSettings.getInstance().getState().getLogAutoSaveEnabled();
        } catch (Throwable t) {
            enabled = false;
        }
        boolean hasHistoryData = enabled || hasHistoryFiles();
        if (hasHistoryData && historyTabIndex < 0) {
            // 添加历史 tab（T-Perf-17：Disposer 注册带防重入守卫）
            addHistoryTab();
        } else if (!hasHistoryData && historyTabIndex >= 0) {
            // 移除历史 tab（避免选中正在移除的 tab）
            if (tabs.getSelectedIndex() == historyTabIndex) {
                tabs.setSelectedIndex(0);
            }
            tabs.removeTabAt(historyTabIndex);
            historyTabIndex = -1;
            // T-Perf-17：tab 移除时从 Disposer 树摘除——否则下次重新添加时重复注册，
            // 2026.x 平台报 "Object must not be registered twice" 错误弹窗。
            // 243 平台 Disposer 无 unregister（javap 实证），dispose(historyPanel) 即摘除并执行
            // 其轻量 dispose（仅停防抖 Timer，面板组件可复用）；dispose 后重新 register 合法。
            if (historyPanelRegistered) {
                Disposer.dispose(getHistoryPanel());
                historyPanelRegistered = false;
            }
        }
    }

    /** T-Perf-17：historyPanel 当前是否已注册到 Disposer 树（防重复注册守卫标志）。 */
    private boolean historyPanelRegistered = false;

    /**
     * 添加历史 tab（T-Perf-17：Disposer 注册防重入守卫——构造期与设置页循环开关都会走到；
     * 重复注册在 2026.x 平台报 "Object must not be registered twice" 错误弹窗）。
     */
    private void addHistoryTab() {
        if (!historyPanelRegistered) {
            Disposer.register(this, getHistoryPanel());
            historyPanelRegistered = true;
        }
        historyTabIndex = tabs.getTabCount();
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.history"), getHistoryPanel());
    }

    /** F-44：检查 .idea 目录中是否存在历史 SQL 文件（F-44-4：目录名随插件更名，旧名不再读取）。 */
    private boolean hasHistoryFiles() {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return false;
        }
        File dir = new File(basePath + File.separator + ".idea" + File.separator
                + SqlLogAutoSaver.DATA_DIR_NAME + File.separator + "sql-history");
        if (!dir.isDirectory()) {
            return false;
        }
        File[] files = dir.listFiles(f -> f.getName().endsWith(".sql"));
        return files != null && files.length > 0;
    }

    /**
     * 捕捉开关动作（T-CaptureBack）：回迁面板工具栏首位（T-UN-11 曾迁工具窗标题栏，
     * 新 UI 下标题栏动作悬停才可见致入口不可发现，回归面板）。图标/文案在 CaptureAction.update
     * 按 manager.capturing 态输出；测试断言直接读 presentation，不依赖按钮刷新。
     */
    private final AnAction captureToggleAction;

    /** 对外 API（T-UiNative T-UN-11）：捕捉开关动作——SqlLogToolWindowFactory 经此挂工具窗标题栏。 */
    public AnAction getCaptureToggleAction() {
        return captureToggleAction;
    }

    /**
     * 捕捉开关（T-CaptureBack：回迁面板工具栏首位；仍经 captureToggleAction 暴露供标题栏兼容）。
     * 图标/文案在 update 按 manager.capturing 态输出（平台更新机制驱动，不再反射强改按钮）。
     */
    private class CaptureAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            if (manager.isCapturing()) {
                e.getPresentation().setIcon(AllIcons.Actions.Suspend);
                e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.stop"));
                e.getPresentation().setDescription(manager.isWaitingForConsole()
                        ? MpSqlVisualizerBundle.message("log.panel.capture.waiting.desc")
                        : null);
            } else {
                e.getPresentation().setIcon(AllIcons.Actions.Execute);
                e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.capture"));
                e.getPresentation().setDescription((String) null);
            }
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (manager.isCapturing()) {
                manager.stopCapture();
            } else {
                manager.startCapture(true);  // 手动点击：设置 userArmed 持久标志
            }
        }
    }

    private class ClearAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.GC);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.clear"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            manager.getEntries().clear();
        }
    }

    private class SettingsAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.General.Settings);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.settings"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SqlLogConfigurable.class);
        }
    }

    /**
     * T-M39：帮助入口（? 按钮，位于设置按钮旁）→ 内嵌浏览器打开本地 HTML 帮助文档。
     */
    private class HelpAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Help);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.help"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (!JBCefApp.isSupported()) {
                LOG.info("[HelpAction] JCEF not supported, falling back to system browser");
                browseOnlineDoc();
                return;
            }
            openLocalHtml();
        }

        /** 降级：系统浏览器打开在线文档。
         *  单元测试模式必须短路——BrowserUtil.browse 不走沙盒隔离，直接 ShellExecute
         *  拉起宿主机默认浏览器（HelpUiTest 真实触发 actionPerformed，定时回归任务期间
         *  周期性弹出用户 Edge，2026-09-11 真机实证）。 */
        private void browseOnlineDoc() {
            if (ApplicationManager.getApplication().isUnitTestMode()) {
                LOG.info("[HelpAction] Unit test mode, skipping system browser fallback");
                return;
            }
            BrowserUtil.browse(HELP_DOCUMENTATION_URL);
        }

        /** 内嵌浏览器打开本地 HTML 帮助页。 */
        private void openLocalHtml() {
            String htmlContent = loadText("html", "marketplace-custom-page.html");
            if (htmlContent.isBlank()) {
                LOG.warn("[HelpAction] Help HTML resource is empty, falling back to online doc");
                browseOnlineDoc();
                return;
            }
            try {
                // 暗色检测：IDE 主题为暗色时注入 class（JCEF 不读取 IDE 的 prefers-color-scheme）
                if (isDarkTheme()) {
                    htmlContent = htmlContent.replaceAll("(?i)<body>", "<body class=\"dark\">");
                    LOG.info("[HelpAction] Dark theme detected, injected .dark class");
                }
                LOG.info("[HelpAction] Opening local help HTML via loadHTML");
                HTMLEditorProvider.openEditor(project, HELP_TAB_TITLE, htmlContent);
            } catch (Throwable ex) {
                LOG.warn("[HelpAction] Failed to open local help HTML, falling back to online doc", ex);
                browseOnlineDoc();
            }
        }

        /** 检测当前 IDE 是否为暗色主题（JBColor.isBright 反映用户设置，不依赖 OS）。 */
        private boolean isDarkTheme() {
            return !JBColor.isBright();
        }

        /** 从 resources 加载文本文件。 */
        private String loadText(String basePath, String fileName) {
            try {
                InputStream stream = HelpAction.class.getClassLoader().getResourceAsStream(basePath + "/" + fileName);
                if (stream == null) {
                    return "";
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    return sb.toString();
                }
            } catch (Throwable t) {
                return "";
            }
        }
    }

    // ---------- T-CaptureTruth T-CT-03：零产出诊断 ----------

    /**
     * 诊断行同步（EDT）：更新列表空态文案 + 管理定时器生命周期。
     * 取数全部为只读内存态（buffer 快照 / 桥计数 / AgentManager 状态），不触发平台调用。
     */
    private void syncDiagnostics() {
        boolean active = manager.isCapturing() && manager.getEntries().snapshot().isEmpty();
        String text;
        if (active) {
            String enhancedStatus;
            try {
                enhancedStatus = cn.song.mybatisplus.visualizer.log.agent.AgentManager.getInstance(project).statusSummary();
            } catch (Throwable ignored) {
                enhancedStatus = null; // runCatching 等价
            }
            text = CaptureDiagnostics.captureDiagnosticsText(new CaptureDiagnostics(
                    true,
                    manager.isWaitingForConsole(),
                    0,
                    manager.getConsoleBridge().attachedConsoles.size(),
                    manager.getConsoleBridge().consoleLinesSeen.get(),
                    enhancedStatus,
                    // T-RA-03：两个「行去哪了」计数——守卫误丢行 / EP 短路后条目未落到本面板时，
                    // 它们是面板唯一可见的证据（此前生产侧零读取，故障被伪装成「控制台没输出」）。
                    manager.getConsoleBridge().getReplayDropCount(),
                    manager.getConsoleBridge().epActiveShortCircuitCount.get()));
        } else {
            text = null;
        }
        list.getEmptyText().setText(text != null ? text : MpSqlVisualizerBundle.message("log.panel.empty"));
        if (active) {
            if (diagnosticsTimer == null) {
                diagnosticsTimer = new javax.swing.Timer(DIAGNOSTICS_TICK_MS, e -> syncDiagnostics());
                diagnosticsTimer.setRepeats(true);
                diagnosticsTimer.start();
            }
        } else {
            if (diagnosticsTimer != null) {
                diagnosticsTimer.stop();
            }
            diagnosticsTimer = null;
        }
    }

    // ---------- 条目列表 ----------

    private JBScrollPane buildListPane() {
        list.getEmptyText().setText(MpSqlVisualizerBundle.message("log.panel.empty"));
        LogEntryListCellRenderer renderer = new LogEntryListCellRenderer();
        entryCellRenderer = renderer;
        list.setCellRenderer(renderer);
        applyListCellHeight();
        // T-UiNative T-UN-05（本次修复）：主日志列表构造即可见，速度搜索在此挂载（不移进
        // SqlEntryList.init——历史二级列表复用它却构造于 CardLayout 隐藏卡，构造期挂载不生效）
        ListSpeedSearch.installOn(list, element -> SqlLogPanelKt.withTerminator(element.restoredSql()));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // T-ClickPreview：先转发（仅左键；单击命中行才排程预览/双击停表并关旧预览），双击再开完整弹窗
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    clickPreview.onClick(e.getClickCount(), e.getPoint());
                }
                if (e.getClickCount() >= 2) {
                    LogSqlEntry selected = list.getSelectedValue();
                    if (selected != null) {
                        openPreview(selected);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }
        });
        return new JBScrollPane(list);
    }

    /**
     * 行高随显示模式分流（T-ListMode × T-LogListFreeze-04 权衡）：
     * SINGLE_LINE 固定行高——WideSelectionListUI 短路逐条测量走 O(1) 快路径
     * （原可变行高 × HTML 排版 = 16s EDT Freeze；片段通道后固定行高仍是流式灌入的最优路径）；
     * KEYWORD_WRAP/FORMATTED 可变行高——条目行数不等，固定高会裁行；多行片段有条目级缓存，
     * 逐条测量只剩轻量组件布局（无 HTML 解析，冻结量级不复现；真机大日志表现见计划文档）。
     * 全文查看由单击预览/双击弹窗承接。
     */
    void applyListCellHeight() {
        list.setFixedCellHeight(PluginSettings.getInstance().sqlListDisplayMode() == SqlListDisplayMode.SINGLE_LINE
                ? measureFixedCellHeight()
                : -1);
    }

    /** T-LogListFreeze-04：固定行高实测——样例条目渲染一次取首选高（两行文本 + 5,8 空边框 + 1px 分隔线）。 */
    private int measureFixedCellHeight() {
        LogSqlEntry sample = new LogSqlEntry("SELECT 1", "SELECT 1", List.of());
        Component c = entryCellRenderer.getListCellRendererComponent(list, sample, 0, false, false);
        int measured = c.getPreferredSize().height;
        if (measured > 0) {
            return measured;
        }
        return list.getFontMetrics(list.getFont()).getHeight() * 2 + 13;
    }

    // ---------- 刷新管线（类型过滤 → 聚焦过滤 → 搜索过滤 → 排序 → 渲染） ----------

    /**
     * 刷新列表（过滤 → 搜索 → 排序 → 差分渲染）。
     *
     * 执行顺序：
     * 1. filterByType：按 activeFilter 筛选（全选 = 全部，含 OTHER）
     * 2. filterByFocus：按 activeFocus 聚焦（M56 T-M56-00 基础设施；null = 透传）
     * 3. filterBySearch：按 searchText 搜索（大小写不敏感）
     * 4. applySort：按 sortMode 排序（默认时间升序）
     * 5. 差分渲染：T-Perf-03 增量刷新——流式追加免全量重建；无法增量时回退分批全量重建
     *
     * T-LogListFreeze-04：HTML 缓存随片段通道退役——徽章计数在渲染时直读 dedupeCounts，
     * 原「清缓存/选择性失效」机制一并移除。
     */
    private void refreshList() {
        long gen = ++refreshGeneration;
        List<LogSqlEntry> snapshot = manager.getEntries().snapshot();
        // T-CaptureTruth T-CT-03：条目变化同步诊断行（有条目即还原默认空态文案并停表）
        syncDiagnostics();

        // 去重丢弃计数快照（徽章渲染数据源）：徽章在元信息行渲染时直读，无缓存失效链
        dedupeCounts = manager.getEntries().dedupeCountsSnapshot();
        entryCellRenderer.dedupeCounts = dedupeCounts;

        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByFocus(
                SqlLogPanelKt.filterByType(snapshot, activeFilter), activeFocus);
        // T-M55-03/04：动态聚焦页签层（页签=过滤器，多开并集；离散增删走 FullRebuild，差分零改动）
        List<LogSqlEntry> focused = FocusTab.filterByFocusTabs(filtered, focusTabs);
        List<LogSqlEntry> searched = SqlLogPanelKt.filterBySearch(focused, searchText);
        List<LogSqlEntry> newVisible = applySort(searched);
        int evicted = manager.getEntries().drainEvictedCount();

        if (!applyIncremental(newVisible, evicted)) {
            applyFullRebuild(newVisible, gen);
        }

        // 聚焦隐藏计数提示（聚焦层单独度量：输入 filtered → 输出 focused）
        if (focusTabs.isEmpty()) {
            focusHiddenLabel.setVisible(false);
        } else {
            int hidden = filtered.size() - focused.size();
            focusHiddenLabel.setText(MpSqlVisualizerBundle.message("log.focus.hidden", String.valueOf(hidden)));
            focusHiddenLabel.setVisible(hidden > 0);
        }

        // O-1：同步慢查询阈值 + 更新计数（Phase C）
        slowQueryCounter.setThresholdMs(PluginSettings.getInstance().getState().getSlowQueryThresholdMs());
        slowQueryCounter.reset();
        for (LogSqlEntry it : snapshot) {
            slowQueryCounter.report(it.executionTimeMs());
        }
        updateSlowQueryLabel();
    }

    /**
     * M56 T-M56-00：设置动态聚焦过滤（页签/请求聚合驱动；null 或全空维度 = 取消聚焦）。
     * 桥通道不写此状态，行为与现状逐字一致。过滤值变化经 refreshList 全量差分呈现
     * （离散增删走 FullRebuild，无需特殊增量分支）。
     */
    public void setFocusFilter(SqlLogPanelKt.FocusFilter focus) {
        SqlLogPanelKt.FocusFilter next = focus != null && !focus.isPassthrough() ? focus : null;
        if (java.util.Objects.equals(next, activeFocus)) {
            return;
        }
        activeFocus = next;
        refreshList();
    }

    /**
     * T-Perf-03（自 dev 移植）：增量应用差分（同步完成，无异步追加链）。模型与 lastRendered 尺寸不一致
     * （旧异步追加链被新刷新中断/外部改动）时拒绝增量——全量重建兜底。
     */
    private boolean applyIncremental(List<LogSqlEntry> newVisible, int evicted) {
        if (listModel.size() != lastRendered.size()) {
            return false;
        }
        SqlLogPanelKt.VisibleDiff diff = SqlLogPanelKt.diffVisible(lastRendered, newVisible, evicted);
        if (diff instanceof SqlLogPanelKt.VisibleDiff.Same) {
            lastRendered = newVisible;
            return true;
        } else if (diff instanceof SqlLogPanelKt.VisibleDiff.HeadEvictAppend hea) {
            for (int i = 0; i < hea.evicted(); i++) {
                listModel.remove(0);
            }
            for (LogSqlEntry e : hea.appended()) {
                listModel.addElement(e);
            }
            lastRendered = newVisible;
            return true;
        } else if (diff instanceof SqlLogPanelKt.VisibleDiff.Insert ins) {
            for (int i = 0; i < ins.entries().size(); i++) {
                listModel.add(ins.index() + i, ins.entries().get(i));
            }
            lastRendered = newVisible;
            return true;
        } else if (diff instanceof SqlLogPanelKt.VisibleDiff.TailRemove tr) {
            for (int i = lastRendered.size() - 1; i >= tr.from(); i--) {
                listModel.remove(i);
            }
            lastRendered = newVisible;
            return true;
        }
        return false; // FullRebuild
    }

    /**
     * 全量重建（分批 EDT 写入 + generation 守卫）。
     *
     * T-Perf-03 追加链 generation 守卫（自 dev 移植）：新 refreshList 启动后旧链在新 chunk 写入前检测代际
     * 并终止——杜绝旧快照剩余条目继续追加造成的幽灵/重复条目。
     */
    private void applyFullRebuild(List<LogSqlEntry> sorted, long gen) {
        lastRendered = sorted;
        // T-LogListFreeze-01：批量抑制布局 + T-LogListFreeze-03：分批 EDT 写入
        list.setIgnoreRepaint(true);
        listModel.clear();
        if (!sorted.isEmpty()) {
            // 首屏批次（立即写入，保证快速首屏）
            List<LogSqlEntry> firstBatch = sorted.subList(0,
                    Math.min(SqlLogPanelKt.REFRESH_FIRST_BATCH_SIZE, sorted.size()));
            listModel.addAll(firstBatch);
        }
        list.setIgnoreRepaint(false);
        list.revalidate();
        list.repaint();

        // T-LogListFreeze-03：剩余批次通过 invokeLater 异步追加（每批一次 EDT 脉冲，避免长冻结）
        if (sorted.size() > SqlLogPanelKt.REFRESH_FIRST_BATCH_SIZE) {
            Iterator<LogSqlEntry> iterator = sorted
                    .subList(SqlLogPanelKt.REFRESH_FIRST_BATCH_SIZE, sorted.size()).iterator();
            Runnable appendNext = new Runnable() {
                @Override
                public void run() {
                    if (gen != refreshGeneration) {
                        return; // 新一轮刷新已启动，旧链终止（T-Perf-03 代际守卫）
                    }
                    List<LogSqlEntry> chunk = new ArrayList<>();
                    for (int i = 0; i < SqlLogPanelKt.REFRESH_CHUNK_SIZE && iterator.hasNext(); i++) {
                        chunk.add(iterator.next());
                    }
                    listModel.addAll(chunk);
                    if (iterator.hasNext()) {
                        ApplicationManager.getApplication().invokeLater(this);
                    }
                }
            };
            ApplicationManager.getApplication().invokeLater(appendNext);
        }
    }

    // ---------- 排序方式（T-LogSortModes） ----------

    /** 弹出排序方式单选菜单（与 SQL类型弹窗同款交互；当前项打勾）。 */
    private void showSortPopup() {
        JPopupMenu popup = new JPopupMenu();
        for (JRadioButtonMenuItem item : buildSortMenuItems()) {
            popup.add(item);
        }
        popup.show(sortButton, 0, sortButton.getHeight() + 2);
    }

    /**
     * 构建排序菜单项（拆出供测试断言选项齐备——headless 下 JPopupMenu.show 不可靠）。
     * 单选语义：点选即生效并刷新列表与按钮文案。
     */
    private List<JRadioButtonMenuItem> buildSortMenuItems() {
        List<JRadioButtonMenuItem> items = new ArrayList<>();
        for (SqlLogPanelKt.SortOption option : SqlLogPanelKt.sortOptions()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(option.label(), option.mode() == sortMode);
            item.setFocusable(false);
            item.addActionListener(e -> applySortMode(option.mode()));
            items.add(item);
        }
        return items;
    }

    /** 应用排序方式：更新模式 → 刷新列表 → 同步按钮文案。 */
    private void applySortMode(SortMode mode) {
        if (sortMode == mode) {
            updateSortButtonText();
            return;
        }
        sortMode = mode;
        refreshList();
        updateSortButtonText();
    }

    /**
     * 排序按钮文案 = 当前方式标签。
     *
     * T-UiCoord F2：与 SQL类型按钮同构——显示文本截尾封顶，tooltip 恒为「排序：」+ 完整方式。
     */
    private void updateSortButtonText() {
        String label = null;
        for (SqlLogPanelKt.SortOption option : SqlLogPanelKt.sortOptions()) {
            if (option.mode() == sortMode) {
                label = option.label();
                break;
            }
        }
        if (label == null) {
            label = MpSqlVisualizerBundle.message("log.sort.time.asc");
        }
        sortButton.setText(clipToDropdownBudget(label, sortButton));
        sortButton.setToolTipText(MpSqlVisualizerBundle.message("log.sort.label") + label);
    }

    /** 按当前排序模式排序条目。 */
    private List<LogSqlEntry> applySort(List<LogSqlEntry> entries) {
        switch (sortMode) {
            case TIME_DESC:
                // 时间降序
                return entries.stream()
                        .sorted(Comparator.comparingLong(this::displayTimeOf).reversed())
                        .collect(Collectors.toList());
            case DURATION_ASC:
                // 耗时：结构化字段优先（agent 通道直采），缺失时回退日志文本解析；无耗时沉底
                return entries.stream()
                        .sorted(Comparator.comparingLong((LogSqlEntry e) -> {
                            Long d = durationOf(e);
                            return d != null ? d : Long.MAX_VALUE;
                        }))
                        .collect(Collectors.toList());
            case DURATION_DESC:
                return entries.stream()
                        .sorted(Comparator.comparingLong((LogSqlEntry e) -> {
                            Long d = durationOf(e);
                            return d != null ? d : Long.MIN_VALUE;
                        }).reversed())
                        .collect(Collectors.toList());
            case SEQUENCE:
                // 行号：无行号（历史读回/round-trip）沉底
                return entries.stream()
                        .sorted(Comparator.comparingLong(e ->
                                e.sequenceNumber() > 0 ? e.sequenceNumber() : Long.MAX_VALUE))
                        .collect(Collectors.toList());
            case TIME_ASC:
            default:
                // 时间升序（默认）：与捕获顺序一致（sortedBy 稳定，等值保持入列序）
                return entries.stream()
                        .sorted(Comparator.comparingLong(this::displayTimeOf))
                        .collect(Collectors.toList());
        }
    }

    /** 条目耗时（毫秒）：结构化 executionTimeMs 优先，缺失时回退日志文本解析。 */
    private Long durationOf(LogSqlEntry entry) {
        return entry.executionTimeMs() != null ? entry.executionTimeMs() : LogDurationParser.parse(entry);
    }

    /**
     * 条目显示时间（排序口径）：与列表现行时间列一致——「日志时间」源开启且条目有原始日志时间时
     * 用之，否则用捕获时间（formatTimestampFragment 同规则），避免「看到的时间与排序依据不一致」。
     */
    private long displayTimeOf(LogSqlEntry entry) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        if (PluginSettings.LOG_TIMESTAMP_SOURCE_LOG.equals(state.getLogTimestampSource())) {
            return entry.logTimestamp() != null ? entry.logTimestamp() : entry.timestamp();
        }
        return entry.timestamp();
    }

    // ---------- 渲染辅助（F-44-2：片段通道恢复 SQL 高亮 + 耗时） ----------
    // 注：formatTimestampFragment / formatDurationFragment / formatSourceFragment 为文件级 static 函数
    // （见 SqlLogPanelKt），供 LogEntryListCellRenderer 与 HistoryCellRenderer 共用。

    /**
     * F-44-6 / T-JumpCapture：统一解析跳转目标：仅依赖捕获位置，不做任何启发式定位。
     * 有位置 → 精确 file:line（LjtPrecise）或文件已失效（LjtNotFound）；
     * MP 分页 COUNT 标记 → LjtNoSource（无用户调用点，纯提示）；
     * 无位置 → LjtMissingSource（引导开启源码捕获，SourceCaptureGuide）。
     * 原「按表名搜索 Wrapper」回退已整体移除：同表名 ≠ 同调用点，命中即误导。
     * T-SourceJumpFunnel P0-5：判定逻辑收敛到 {@link EntryJumpResolver}（实时/历史两面板共用同一实现，
     * 历史面板由此获得候选回退能力），本函数只做类型转接。
     */
    public LogJumpTarget resolveEntryJump(LogSqlEntry entry) {
        EntryJumpResolver.JumpTarget target = EntryJumpResolver.resolve(project, entry);
        if (target instanceof EntryJumpResolver.JumpTarget.Precise precise) {
            return new LjtPrecise(precise.virtualFile(), precise.line());
        }
        if (target == EntryJumpResolver.JumpTarget.NoSource.INSTANCE) {
            return LjtNoSource.INSTANCE;
        }
        if (target instanceof EntryJumpResolver.JumpTarget.NotFound notFound) {
            return new LjtNotFound(notFound.sourceFile());
        }
        return LjtMissingSource.INSTANCE;
    }

    /**
     * T-M55-01：XML 目标解析——mapperStatement 非空且 {@link MapperXmlLocator} 定位成功时返回
     * LjtMapperXml；statementId 缺失 / MP 内置语句 XML 不存在 / 多模块定位失败
     * 一律返回 null（调用方回退 Java 跳转，不劣化）。定位自带 ReadAction，EDT 直调安全。
     */
    LjtMapperXml resolveEntryXmlJump(LogSqlEntry entry) {
        String statementId = entry.mapperStatement();
        if (statementId == null || statementId.isBlank()) {
            return null;
        }
        MapperXmlLocator.Location location = MapperXmlLocator.locate(project, statementId);
        if (location == null) {
            return null;
        }
        return new LjtMapperXml(location.virtualFile(), location.line());
    }

    /** 等价右键「跳转到源码」（供 UI 测试断言精确跳转分支，避 headless 弹窗）。 */
    public void jumpToSourceForTest(LogSqlEntry entry) {
        jumpToSource(entry);
    }

    /**
     * F-44-6：日志 ↔ 源码关联跳转。
     * 仅依赖捕获位置精确跳转；无位置时弹框引导开启源码捕获（T-JumpCapture）。
     */
    private void jumpToSource(LogSqlEntry entry) {
        LogJumpTarget target = resolveEntryJump(entry);
        if (target instanceof LjtPrecise p) {
            new OpenFileDescriptor(project, p.getVirtualFile(), p.getLine(), 0).navigate(true);
        } else if (target instanceof LjtMapperXml mx) {
            // resolveEntryJump 不产出该分支（XML 目标走 resolveEntryXmlJump/JumpToMapperXmlAction）；防御性导航
            new OpenFileDescriptor(project, mx.getVirtualFile(), mx.getLine(), 0).navigate(true);
        } else if (target instanceof LjtNoSource) {
            showJumpToast(MpSqlVisualizerBundle.message("log.panel.jumpToSource.noSource"));
        } else if (target instanceof LjtNotFound nf) {
            showJumpToast(MpSqlVisualizerBundle.message("log.panel.jumpToSource.notFound", nf.getSourceFile()));
        } else if (target instanceof LjtMissingSource) {
            SourceCaptureGuide.showMissingSourceDialog(project);
        }
    }

    /**
     * T-UiNative T-UN-02：跳转无源码提示改轻通知（原模态弹窗——纯告知无需打断）。
     * 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）。
     */
    private void showJumpToast(String content) {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
        }
        try {
            new Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("log.panel.jumpToSource"),
                    content,
                    NotificationType.INFORMATION).notify(project);
        } catch (Throwable ignored) {
            // 通知失败不影响主流程
        }
    }

    /** 更新工具栏慢查询计数徽章（O-1 Phase C）。 */
    private void updateSlowQueryLabel() {
        int count = slowQueryCounter.getSlowCount();
        if (slowQueryLabel != null) {
            if (count > 0) {
                // T-UiCoord F2：全文「慢查询: N」（约 65px）在一行定构下吃掉余量 → 改短徽章
                // 「慢 N」（复用存量键 log.panel.slowQueryBadge），全文进 tooltip 不丢信息
                slowQueryLabel.setText(MpSqlVisualizerBundle.message("log.panel.slowQueryBadge") + " " + count);
                slowQueryLabel.setToolTipText(MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", Integer.toString(count)));
                slowQueryLabel.setVisible(true);
            } else {
                slowQueryLabel.setVisible(false);
            }
        }
    }

    private void openPreview(LogSqlEntry entry) {
        // T-M31.14：设置开启时预览展示格式化版（多行缩进），否则原样
        PluginSettings.State state = PluginSettings.getInstance().getState();
        // T-HistParam F3：识别通道统一——与历史 tab 共用 toPreviewResult
        // （占位符识别 / 字面量参数恢复 F4 / 回退警告分流，行为与原 openPreview 双分支逐字段一致）
        SqlPreviewService.showResult(project, LogEntryPreviewRecognizerKt.toPreviewResult(
                entry, state.getLogFormatSql(), state.getLiteralParamRecovery()), null);
    }

    private void showContextMenu(MouseEvent e) {
        int index = list.locationToIndex(e.getPoint());
        if (index < 0) {
            return;
        }
        // T-ListPreviewCopy：命中行已在多选内 → 保留多选（复制项=批量复制多条）；否则收敛为该行。
        // 删除/发送/跳转等其余菜单项恒作用于被右键的行
        list.setSelectedIndices(SqlLogPanelKt.resolveContextMenuSelection(list.getSelectedIndices(), index));
        LogSqlEntry entry = listModel.getElementAt(index);
        List<LogSqlEntry> selectedEntries = new ArrayList<>();
        for (int i : list.getSelectedIndices()) {
            selectedEntries.add(listModel.getElementAt(i));
        }
        // T-UiNative P1a：JPopupMenu+JMenuItem → 平台 DefaultActionGroup+AnAction（ActionPopupMenu 承载）
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new CopySqlAction(selectedEntries));
        // T-LogSearchRowTime：复制时间戳（所见即所复制的纯时间文本，无方括号）；
        // 显示开关关闭时无片段可复制 → 菜单项置灰（置灰逻辑在 CopyTimestampAction.update）
        String timestampClip = SqlLogPanelKt.batchCopyTimestamps(selectedEntries);
        group.add(new CopyTimestampAction(selectedEntries, timestampClip));
        group.add(new DeleteSqlAction(entry));
        // T-M37 P1：发送到 SQL → Java 转换（第三 tab）——还原 SQL 写入输入区并切换选中该 tab
        group.add(new SendToSqlToJavaAction(entry));
        // T-M38：发送到日志转 SQL（第二 tab）——条目渲染回日志文本并立即转换
        group.add(new SendToLogToSqlAction(entry));
        // O-1：日志 ↔ 源码关联跳转（Phase A）；T-M55-01：拆「跳转到 Java」「跳转到 XML」双目标
        group.add(new JumpToSourceAction(entry));
        group.add(new JumpToMapperXmlAction(entry));
        // T-M55-03：方法级聚焦（sourceFile 非空时可用；解析失败 toast 提示，无静默吞没）
        group.add(new FocusCallerMethodAction(entry));
        // T-M55-04：按请求聚焦（requestTag 非空时可用；页签 K 有界防高基数爆列表）
        group.add(new FocusRequestAction(entry));
        com.intellij.openapi.actionSystem.ActionPopupMenu popup =
                ActionManager.getInstance().createActionPopupMenu("mpvs.log.list", group);
        // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成 targetComponent 属性——显式调 setter
        popup.setTargetComponent(list);
        popup.getComponent().show(list, e.getX(), e.getY());
    }

    // ---------- T-UiNative P1a：列表右键菜单动作（原 JMenuItem 语义平移，update 设文案/灰置，actionPerformed 平移原监听体） ----------

    /** 复制 SQL：多选批量逐行拼接（batchCopySql），单选复制该行。 */
    private class CopySqlAction extends AnAction {
        private final List<LogSqlEntry> entries;

        CopySqlAction(List<LogSqlEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(SqlLogPanelKt.copyMenuText(
                    "log.panel.copy", "log.panel.copySelected", entries.size()));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            UiTextClipboard.copy(SqlLogPanelKt.batchCopySql(entries));
        }
    }

    /** 复制时间戳：显示开关关闭时无片段可复制 → update 置灰。 */
    private class CopyTimestampAction extends AnAction {
        private final List<LogSqlEntry> entries;
        private final String clip;

        CopyTimestampAction(List<LogSqlEntry> entries, String clip) {
            this.entries = entries;
            this.clip = clip;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(SqlLogPanelKt.copyMenuText(
                    "log.panel.copyTimestamp", "log.panel.copyTimestampSelected", entries.size()));
            e.getPresentation().setEnabled(!clip.isEmpty());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (!clip.isEmpty()) {
                UiTextClipboard.copy(clip);
            }
        }
    }

    /** 删除被右键的条目。 */
    private class DeleteSqlAction extends AnAction {
        private final LogSqlEntry entry;

        DeleteSqlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.delete"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            manager.getEntries().removeEntry(entry);
        }
    }

    /** 发送到 SQL → Java 转换（第三 tab）。 */
    private class SendToSqlToJavaAction extends AnAction {
        private final LogSqlEntry entry;

        SendToSqlToJavaAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.sendToReverse"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            sqlToJavaPanel.setInputSql(entry.restoredSql());
            tabs.setSelectedComponent(sqlToJavaPanel);
        }
    }

    /** 发送到日志转 SQL（第二 tab）。 */
    private class SendToLogToSqlAction extends AnAction {
        private final LogSqlEntry entry;

        SendToLogToSqlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.sendTo"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            logToSqlPanel.loadFromEntry(entry);
            tabs.setSelectedComponent(logToSqlPanel);
        }
    }

    /** 跳转到 Java 调用方（T-M55-01：与「跳转到 XML」拆分为双目标；无位置→引导捕获弹窗，无提示/文件丢失→toast）。 */
    private class JumpToSourceAction extends AnAction {
        private final LogSqlEntry entry;

        JumpToSourceAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.jumpToJava"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            jumpToSource(entry);
        }
    }

    /**
     * T-M55-01：跳转到 mapper XML 语句节点。mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用，actionPerformed 复用，单次右键只做一次索引查询）；
     * 定位失败（XML 不存在/多模块未命中）不显示，原有「跳转到 Java」承担回退，不劣化。
     */
    private class JumpToMapperXmlAction extends AnAction {
        private final LogSqlEntry entry;
        private LjtMapperXml resolved;

        JumpToMapperXmlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.jumpToXml"));
            resolved = resolveEntryXmlJump(entry);
            e.getPresentation().setEnabledAndVisible(resolved != null);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            // update 已解析成功才可见可点；防御再解析一次（menu 会话内基本命中缓存外的同结果）
            LjtMapperXml target = resolved != null ? resolved : resolveEntryXmlJump(entry);
            if (target == null) {
                return;
            }
            new OpenFileDescriptor(project, target.getVirtualFile(), target.getLine(), 0).navigate(true);
        }
    }

    /**
     * T-M55-03：聚焦调用方方法（静态行号区间近似版）——sourceFile 非空时可用；
     * 点击时解析方法行号区间并加入聚焦页签栏（解析失败 toast，不静默）。
     * Ultra 的编辑器行槽 ⚡ 入口首批不做（记录为可选增强）。
     */
    private class FocusCallerMethodAction extends AnAction {
        private final LogSqlEntry entry;

        FocusCallerMethodAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.focusCallerMethod"));
            // MP 分页 COUNT 标记无用户调用点；其余 sourceFile 非空即可用（解析成败在 action 时点判定）
            e.getPresentation().setEnabled(entry.sourceFile() != null
                    && !SourceMarkers.isMpPaginationCount(entry.sourceFile()));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            FocusTab.Method tab = resolveCallerMethodFocus(entry);
            if (tab == null) {
                showJumpToast(MpSqlVisualizerBundle.message("log.panel.focusCallerMethod.failed"));
                return;
            }
            addFocusTab(tab);
        }
    }

    /**
     * T-M55-04：按请求聚焦——requestTag 非空的条目右键即产生请求页签（同一次请求的多条 SQL
     * 共享同值，页签内呈现扁平时间序）。页签 K 有界（addRequestTab 超限挤掉最早请求页签，
     * 防长压测会话 requestTag 高基数爆页签栏）；无请求标签条目在请求聚焦下隐藏 + 计数提示
     * （与 03 方法聚焦同策略）。「最近 K 个请求标签下拉」的独立入口不另做——右键任意
     * 同标签条目等价可达，计划内「或」二选一取轻。
     */
    private class FocusRequestAction extends AnAction {
        private final LogSqlEntry entry;

        FocusRequestAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.focusRequest"));
            e.getPresentation().setEnabled(entry.requestTag() != null && !entry.requestTag().isBlank());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            String requestTag = entry.requestTag();
            if (requestTag != null && !requestTag.isBlank()) {
                addFocusTab(new FocusTab.Request(requestTag));
            }
        }
    }

    /** 面板销毁：释放单击预览弹窗与日志转 SQL / SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    @Override
    public void dispose() {
        // T-Perf-24：停止搜索防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop();
        // T-CaptureTruth T-CT-03：停止诊断定时器（同上，防晚到 syncDiagnostics 触碰已销毁列表）
        if (diagnosticsTimer != null) {
            diagnosticsTimer.stop();
        }
        diagnosticsTimer = null;
        // T-Perf-15：注销 manager 监听器（防面板销毁后仍被回调——每条新 SQL invokeLater 调已销毁面板）
        manager.removeUiListener(uiRefreshListener);
        manager.removeCapturingListener(capturingUiListener);
        clickPreview.dispose();
        logToSqlPanel.dispose();
        sqlToJavaPanel.dispose();
    }

    // ---------- 测试访问器（headless 测试断言用） ----------

    /** 当前列表模型条目数（测试断言）。 */
    public int getVisibleEntryCount() {
        return listModel.size();
    }

    /** 当前列表模型条目 restoredSql 列表（测试断言）。 */
    public List<String> getVisibleEntries() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            result.add(listModel.getElementAt(i).restoredSql());
        }
        return result;
    }

    /** 当前激活的类型过滤集合（测试断言）。 */
    public Set<StatementType> getCurrentFilter() {
        return new LinkedHashSet<>(activeFilter);
    }

    /** 当前排序模式（测试断言）。 */
    public SortMode getCurrentSortMode() {
        return sortMode;
    }

    /** 排序方式选择（测试驱动：等价用户在菜单里点选某项）。 */
    public void selectSortModeForTest(SortMode mode) {
        applySortMode(mode);
    }

    /** 排序方式按钮（测试断言文案/可见性）。 */
    public JButton getSortButtonForTest() {
        return sortButton;
    }

    /** 排序菜单项文案列表（测试断言选项齐备；构建菜单但不 show）。 */
    public List<String> getSortMenuLabelsForTest() {
        List<String> labels = new ArrayList<>();
        for (JRadioButtonMenuItem item : buildSortMenuItems()) {
            labels.add(item.getText());
        }
        return labels;
    }

    /** 当前排序方式按钮文案（测试断言）。 */
    public String getSortButtonTextForTest() {
        return sortButton.getText();
    }

    /** 去重勾选框（F-44-2 测试驱动：模拟用户勾选「移除重复的sql」验证既有条目立即去重）。 */
    public JCheckBox getDedupeCheckBoxForTest() {
        return dedupeCheckBox;
    }

    /** SQL类型多选按钮（测试断言文案）。 */
    public JButton getTypeFilterButtonForTest() {
        return typeFilterButton;
    }

    /** T-SelToggle：类型弹窗底部全选/全不选切换按钮（未打开弹窗时为 null）。 */
    public JButton getTypePopupToggleButtonForTest() {
        return typePopupToggleButton;
    }

    /**
     * T-SelToggle：headless 测试直接构建弹窗内容（不经 popup.show——无显示设备时
     * lightweight popup 不可靠），返回后 {@link #getTypePopupToggleButtonForTest()} 即非 null 可驱动。
     */
    public JPanel buildTypeFilterContentForTest() {
        return buildTypeFilterContent();
    }

    /**
     * T-UiCoord F2：工具栏单行宿主（布局契约断言用——单行 / 不越界 / 控件共轴）。
     * 返回的是生产实例（{@code buildToolbar()} 的产物），不是测试另搭的仿制品。
     */
    JPanel toolbarRowForTest() {
        return toolbarRow;
    }

    /** Kotlin {@code val toolbarRowForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public JPanel getToolbarRowForTest() {
        return toolbarRow;
    }

    /** T-UiCoord F2：工具栏右侧控制组（类型 / 排序 / 去重勾选 / 慢查询徽章）——单行契约的主断言面。 */
    JPanel toolbarControlsForTest() {
        return toolbarControls;
    }

    /** Kotlin {@code val toolbarControlsForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public JPanel getToolbarControlsForTest() {
        return toolbarControls;
    }

    /** T-UiCoord F2：工具栏动作图标组组件（结构断言：它必须挂在行宿主 CENTER）。 */
    JComponent toolbarComponentForTest() {
        return toolbarComponent;
    }

    /** Kotlin {@code val toolbarComponentForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public JComponent getToolbarComponentForTest() {
        return toolbarComponent;
    }

    /** T-UiCoord F2：下拉按钮显示文本的像素预算（测试按同一预算算期望的省略文本）。 */
    int dropdownTextBudgetForTest() {
        return JBUI.scale(DROPDOWN_TEXT_BUDGET_PX);
    }

    /** Kotlin {@code val dropdownTextBudgetForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public int getDropdownTextBudgetForTest() {
        return JBUI.scale(DROPDOWN_TEXT_BUDGET_PX);
    }

    /** 应用类型过滤组合（测试驱动：设置选中集 + 刷新按钮文案与列表）。 */
    public void applyTypeFilterForTest(Set<StatementType> types) {
        activeFilter.clear();
        activeFilter.addAll(types);
        updateTypeFilterButton();
        refreshList();
    }

    /** 搜索条（测试断言显隐/文本）。 */
    public SearchTextField getSearchFieldForTest() {
        return searchField;
    }

    /** 单击预览控制器（T-ClickPreview 测试断言：排程/取消/弹窗残留）。 */
    public SqlClickPreview getClickPreviewForTest() {
        return clickPreview;
    }

    /** 工具栏慢查询计数标签（测试断言显隐与文案）。 */
    public JLabel getSlowQueryLabelForTest() {
        return slowQueryLabel;
    }

    private static final Logger LOG = Logger.getInstance(SqlLogPanel.class);
    private static final String TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar";
    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";
    public static final String DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";
    /** T-M39：帮助文档 URL（降级系统浏览器打开）。
     *  2026-08-31 改为自建站点 jvtools.cn（可自主接入国内 CDN；
     *  当前实测：直连不通，需走系统/IDE 代理才可达，与 plugins.jetbrains.com 同）。 */
    private static final String HELP_DOCUMENTATION_URL =
            "https://mybatis-plus-sql-visualizer.jvtools.cn/features/wrapper-preview.html";
    /** T-M39：帮助标签页标题。 */
    private static final String HELP_TAB_TITLE = "SQL Visualizer - Help";

    /** 类型 → 文案。 */
    private static String labelForType(StatementType type) {
        switch (type) {
            case SELECT:
                return MpSqlVisualizerBundle.message("log.filter.select");
            case INSERT:
                return MpSqlVisualizerBundle.message("log.filter.insert");
            case UPDATE:
                return MpSqlVisualizerBundle.message("log.filter.update");
            case DELETE:
                return MpSqlVisualizerBundle.message("log.filter.delete");
            default:
                return "OTHER";
        }
    }
}
