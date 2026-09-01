package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener;
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.PagesColumn;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.ListModel;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private final JLabel typeFilterLabel = new JLabel(MpSqlVisualizerBundle.message("log.filter.type.label"));
    private final JButton typeFilterButton;
    private final JToggleButton sortButton;
    private final SearchTextField searchField;

    // ---------- 过滤/排序状态 ----------

    /** 当前激活的类型过滤集合（默认全选 = 「全部」，含 OTHER 透传）。 */
    private final Set<StatementType> activeFilter = new LinkedHashSet<>(SqlLogPanelKt.FILTERABLE_TYPES);
    /** 排序模式（默认正序）。 */
    private SortMode sortMode = SortMode.NONE;
    /** 是否含耗时信息（决定是否显示排序按钮）。 */
    private boolean hasAnyDurationInfo = false;
    /** 当前搜索词（搜索条实时同步；空串 = 不过滤）。 */
    private String searchText = "";
    /** 去重丢弃计数快照（refreshList 后更新；cellRenderer 渲染 ⨉N 徽章用）。 */
    private Map<String, Integer> dedupeCounts = Map.of();

    // ---------- O-1 日志窗口差异化：慢查询计数器 + 耗时着色 ----------

    /** 慢查询计数器（O-1 Phase C）。 */
    private final SlowQueryCounter slowQueryCounter =
            new SlowQueryCounter(PluginSettings.getInstance().getState().getSlowQueryThresholdMs());
    /** 工具栏慢查询计数标签（动态更新）。 */
    private JLabel slowQueryLabel = null;

    // ---------- UI 组件（工具栏行：捕捉/清空/设置/帮助 + 去重勾选 + SQL类型 + 耗时排序） ----------

    /** 工具栏引用（capturing 变化时刷新按钮状态）。 */
    private ActionToolbar _toolbarRef = null;
    /** 工具栏动作组（T-M39：internal 供测试断言帮助入口存在性）。 */
    public final DefaultActionGroup toolbarGroup;

    /** 当前打开的类型弹窗内勾选框（全选/全不选即时同步用；弹窗每次打开重建）。 */
    private List<kotlin.Pair<StatementType, JCheckBox>> typePopupCheckBoxes = List.of();

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
        typeFilterButton.setToolTipText(MpSqlVisualizerBundle.message("log.filter.type"));
        typeFilterButton.addActionListener(e -> showTypeFilterPopup());

        this.sortButton = new JToggleButton(MpSqlVisualizerBundle.message("log.sort.duration"));
        sortButton.setFocusable(false);
        sortButton.addActionListener(e -> toggleDurationSort());

        this.logToSqlPanel = new LogToSqlPanel(project);
        this.sqlToJavaPanel = new SqlToJavaPanel(project);
        this.searchField = buildSearchField();
        this.toolbarGroup = new DefaultActionGroup(
                new CaptureAction(),
                new ClearAction(),
                new SettingsAction(),
                new ShowSearchAction(), // 搜索在帮助左侧（用户指定排序）
                new HelpAction()
        );

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
            Disposer.register(this, getHistoryPanel());
            historyTabIndex = tabs.getTabCount();
            tabs.addTab(MpSqlVisualizerBundle.message("log.tab.history"), getHistoryPanel());
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
        SettingsChangeListener settingsListener = () -> ApplicationManager.getApplication().invokeLater(
                () -> updateHistoryTabVisibility());
        project.getMessageBus().connect(this).subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, settingsListener);

        manager.addUiListener(() -> refreshList());
        refreshList();

        // capturing 变化 → 刷新工具栏按钮图标/文案
        manager.addCapturingListener(() -> {
            LOG.info("SqlLogPanel.capturingListener: 触发");
            ApplicationManager.getApplication().invokeLater(this::updateCaptureButtonIcon);
        });
    }

    private HistorySqlLogPanel getHistoryPanel() {
        if (historyPanel == null) {
            historyPanel = new HistorySqlLogPanel(project);
        }
        return historyPanel;
    }

    // ---------- 顶部区域（工具栏行 + 搜索条） ----------

    /** 顶部竖向容器：工具栏行（NORTH）+ 搜索条（SOUTH，默认隐藏不占空间）。 */
    private JPanel buildTopPanel() {
        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolbar(), BorderLayout.NORTH);
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
        // 左右分区：左侧 动作图标 + SQL类型单元 + 耗时排序 + 慢查询计数；
        // 右侧「移除重复的sql」勾选（用户要求移到右侧，与 SQL类型 垂直居中平行、样式统一）
        JPanel row = new JPanel(new BorderLayout());

        // 左侧流式区：WrapLayout——窄侧边栏放不下时折行且高度随之增长（原生 FlowLayout
        // preferredLayoutSize 只按单行算，折行后第二行会被裁掉——「SQL类型筛选项消失」根因）
        JPanel left = new JPanel(new WrapLayout());
        left.add(toolbar.getComponent());
        // 标签 + 按钮打包成一体：折行时「SQL类型：」与状态按钮同进退，不拆散两行
        JPanel typeFilterUnit = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        typeFilterUnit.setOpaque(false);
        typeFilterUnit.add(typeFilterLabel);
        typeFilterUnit.add(typeFilterButton);
        left.add(typeFilterUnit);
        left.add(sortButton);
        // O-1：工具栏慢查询计数标签
        slowQueryLabel = new JLabel();
        slowQueryLabel.setForeground(new Color(0xD32F2F)); // 红色
        slowQueryLabel.setFont(slowQueryLabel.getFont().deriveFont(Font.BOLD));
        slowQueryLabel.setVisible(false); // 默认隐藏，有慢查询时显示
        left.add(slowQueryLabel);
        row.add(left, BorderLayout.CENTER);

        // 右侧：去重勾选（Flow 面板带边距，BorderLayout 垂直居中 → 与左侧图标/标签同轴平行）
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        right.setOpaque(false);
        right.add(dedupeCheckBox);
        row.add(right, BorderLayout.EAST);
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
        searchText = searchField.getText();
        refreshList();
    }

    // ---------- SQL类型多选弹窗 ----------

    /** 更新 SQL类型按钮文案：标签独立展示，按钮只显示状态（全部 / 无 / 类型组合）。 */
    private void updateTypeFilterButton() {
        if (activeFilter.containsAll(SqlLogPanelKt.FILTERABLE_TYPES)) {
            typeFilterButton.setText(MpSqlVisualizerBundle.message("log.filter.all"));
        } else if (activeFilter.isEmpty()) {
            typeFilterButton.setText(MpSqlVisualizerBundle.message("log.filter.type.none"));
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
            typeFilterButton.setText(sb.toString());
        }
    }

    /**
     * 弹出类型多选弹窗：勾选即时生效且弹窗不关闭（JCheckBox 非 menu item，
     * 点击不触发 popup 收起，支持连续勾选组合）；底部全选/全不选一键设置——
     * 同步刷新弹窗内勾选框选中态 + 按钮文案 + 列表（弹窗保持打开，立即可见）。
     */
    private void showTypeFilterPopup() {
        JPopupMenu popup = new JPopupMenu();
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(new javax.swing.border.EmptyBorder(8, 12, 8, 12));
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
            });
            checkBoxes.add(new kotlin.Pair<>(type, cb));
            content.add(cb);
        }
        typePopupCheckBoxes = checkBoxes;
        content.add(Box.createVerticalStrut(6));
        JSeparator sep = new JSeparator();
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(sep);
        content.add(Box.createVerticalStrut(6));
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton selectAll = new JButton(MpSqlVisualizerBundle.message("log.filter.selectAll"));
        selectAll.setFocusable(false);
        selectAll.addActionListener(e -> applyAllTypes(true));
        row.add(selectAll);
        JButton selectNone = new JButton(MpSqlVisualizerBundle.message("log.filter.selectNone"));
        selectNone.setFocusable(false);
        selectNone.addActionListener(e -> applyAllTypes(false));
        row.add(selectNone);
        content.add(row);
        popup.add(content);
        popup.show(typeFilterButton, 0, typeFilterButton.getHeight() + 2);
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
    }

    // ---------- 工具栏动作 ----------

    /** 直接更新捕捉按钮图标（绕过 ActionToolbar 更新机制——沙盒中 isShowing 检查会导致更新被跳过）。 */
    private void updateCaptureButtonIcon() {
        ActionToolbar toolbar = _toolbarRef;
        if (toolbar == null) {
            return;
        }
        boolean capturing = manager.isCapturing();
        javax.swing.Icon icon = capturing ? AllIcons.Actions.Suspend : AllIcons.Actions.Execute;
        String text = MpSqlVisualizerBundle.message(capturing ? "log.panel.stop" : "log.panel.capture");
        // 遍历工具栏组件树，找到 ActionButton 并通过 getAction() 匹配 CaptureAction
        UIUtil.uiTraverser(toolbar.getComponent())
                .preOrderDfsTraversal()
                .filter(c -> c.getClass().getSimpleName().equals("ActionButton"))
                .forEach(btn -> {
                    Object action;
                    try {
                        action = btn.getClass().getMethod("getAction").invoke(btn);
                    } catch (Throwable t) {
                        action = null;
                    }
                    if (action instanceof CaptureAction) {
                        if (btn instanceof AbstractButton) {
                            AbstractButton ab = (AbstractButton) btn;
                            ab.setIcon(icon);
                            ab.setText(text);
                            ab.repaint();
                        }
                        LOG.info("updateCaptureButtonIcon: 设置图标=" + (capturing ? "停止" : "捕捉"));
                    }
                });
    }

    /**
     * F-44：根据 logAutoSaveEnabled 设置 + 历史数据存在与否同步历史 tab 的显隐。
     * 开启自动保存时显示；关闭后若 .idea 中仍有历史文件，也保留 tab（只读查看已有数据）。
     * 仅当无自动保存且无历史数据时才移除 tab。
     */
    private void updateHistoryTabVisibility() {
        boolean enabled = runCatchingBool(
                () -> PluginSettings.getInstance().getState().getLogAutoSaveEnabled());
        boolean hasHistoryData = enabled || hasHistoryFiles();
        if (hasHistoryData && historyTabIndex < 0) {
            // 添加历史 tab
            Disposer.register(this, getHistoryPanel());
            historyTabIndex = tabs.getTabCount();
            tabs.addTab(MpSqlVisualizerBundle.message("log.tab.history"), getHistoryPanel());
        } else if (!hasHistoryData && historyTabIndex >= 0) {
            // 移除历史 tab（避免选中正在移除的 tab）
            if (tabs.getSelectedIndex() == historyTabIndex) {
                tabs.setSelectedIndex(0);
            }
            tabs.removeTabAt(historyTabIndex);
            historyTabIndex = -1;
        }
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

    private boolean runCatchingBool(BooleanSupplierWithThrowable sup) {
        try {
            return sup.get();
        } catch (Throwable t) {
            return false;
        }
    }

    @FunctionalInterface
    private interface BooleanSupplierWithThrowable {
        boolean get() throws Throwable;
    }

    private class CaptureAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            if (manager.isCapturing()) {
                e.getPresentation().setIcon(AllIcons.Actions.Suspend);
                e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.stop"));
                e.getPresentation().setDescription(manager.isWaitingForConsole() ? "已开启 SQL 监听，等待项目运行" : null);
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
                BrowserUtil.browse(HELP_DOCUMENTATION_URL);
                return;
            }
            openLocalHtml();
        }

        /** 内嵌浏览器打开本地 HTML 帮助页。 */
        private void openLocalHtml() {
            String htmlContent = loadText("html", "marketplace-custom-page.html");
            if (htmlContent.isBlank()) {
                LOG.warn("[HelpAction] Help HTML resource is empty, falling back to online doc");
                BrowserUtil.browse(HELP_DOCUMENTATION_URL);
                return;
            }
            try {
                LOG.info("[HelpAction] Opening local help HTML via loadHTML");
                HTMLEditorProvider.openEditor(project, HELP_TAB_TITLE, htmlContent);
            } catch (Throwable ex) {
                LOG.warn("[HelpAction] Failed to open local help HTML, falling back to online doc", ex);
                BrowserUtil.browse(HELP_DOCUMENTATION_URL);
            }
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

    // ---------- 条目列表 ----------

    private JBScrollPane buildListPane() {
        list.getEmptyText().setText(MpSqlVisualizerBundle.message("log.panel.empty"));
        // 多行 SQL 预览：启用可变行高（条目高度按内容自适应，支持 SQL 自动换行）
        list.setFixedCellHeight(-1);
        LogEntryListCellRenderer renderer = new LogEntryListCellRenderer();
        entryCellRenderer = renderer;
        list.setCellRenderer(renderer);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
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

    // ---------- 刷新管线（类型过滤 → 搜索过滤 → 排序 → 渲染） ----------

    /**
     * 刷新列表（过滤 → 搜索 → 排序 → 渲染）。
     */
    private void refreshList() {
        List<LogSqlEntry> snapshot = manager.getEntries().snapshot();

        // 检测是否含耗时信息（决定是否显示排序按钮）
        hasAnyDurationInfo = snapshot.stream().anyMatch(it -> LogDurationParser.hasDurationInfo(it));

        // 去重丢弃计数快照（徽章渲染数据源；丢弃不触发通知，随下一次刷新呈现）
        dedupeCounts = manager.getEntries().dedupeCountsSnapshot();
        entryCellRenderer.dedupeCounts = dedupeCounts;

        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByType(snapshot, activeFilter);
        List<LogSqlEntry> searched = SqlLogPanelKt.filterBySearch(filtered, searchText);
        List<LogSqlEntry> sorted = applySort(searched);

        listModel.clear();
        // 一次性替换全部内容：fireIntervalAdded 仅触发一次布局计算（M46：O(N²) → O(N)）
        if (!sorted.isEmpty()) {
            listModel.addAll(sorted);
        }
        updateSortButton();

        // O-1：同步慢查询阈值 + 更新计数（Phase C）
        slowQueryCounter.setThresholdMs(PluginSettings.getInstance().getState().getSlowQueryThresholdMs());
        slowQueryCounter.reset();
        for (LogSqlEntry it : snapshot) {
            slowQueryCounter.report(it.executionTimeMs());
        }
        updateSlowQueryLabel();
    }

    /** 按当前排序模式排序条目。 */
    private List<LogSqlEntry> applySort(List<LogSqlEntry> entries) {
        if (sortMode == SortMode.NONE) {
            return entries;
        }
        // DURATION_DESC
        return entries.stream()
                .sorted(Comparator.comparingLong((LogSqlEntry e) -> {
                    Long d = LogDurationParser.parse(e);
                    return d != null ? d : -1L;
                }).reversed())
                .collect(Collectors.toList());
    }

    /** 更新耗时排序按钮可见性 + 文案 + 选中态。 */
    private void updateSortButton() {
        sortButton.setVisible(hasAnyDurationInfo);
        String sortLabel = sortMode == SortMode.NONE
                ? MpSqlVisualizerBundle.message("log.sort.duration")
                : MpSqlVisualizerBundle.message("log.sort.duration.desc");
        sortButton.setText(sortLabel);
        sortButton.setToolTipText(sortLabel);
        sortButton.setSelected(sortMode == SortMode.DURATION_DESC);
    }

    /** 切换耗时排序（NONE → DURATION_DESC → NONE）。 */
    private void toggleDurationSort() {
        sortMode = sortMode == SortMode.NONE ? SortMode.DURATION_DESC : SortMode.NONE;
        refreshList();
    }

    // ---------- 渲染辅助（F-44-2：HTML 通道恢复 SQL 高亮 + 耗时） ----------

    /**
     * O-1：日志 ↔ 源码关联跳转的解析结果（internal 供 UI 测试断言「无法跳转」分支，
     * 避免 headless 下 Messages 弹窗阻塞与后台 ReferencesSearch 的不确定性）。
     */
    public LiveJumpResolution resolveLiveJump(LogSqlEntry entry) {
        String tableName = SqlTableExtractor.extractFirst(entry.restoredSql());
        return tableName == null ? LjrNoTable.INSTANCE : new LjrProceed(tableName);
    }

    /** 统一解析跳转目标：有源码位置 → 精确跳转；否则按表名搜索（尽力而为）。 */
    public LogJumpTarget resolveEntryJump(LogSqlEntry entry) {
        if (entry.sourceFile() != null) {
            String basePath = project.getBasePath();
            if (basePath == null) {
                return LjtNoSource.INSTANCE;
            }
            String fullPath = basePath + "/" + entry.sourceFile();
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(fullPath);
            if (vf != null) {
                return new LjtPrecise(vf, Math.max(0, entry.sourceLine() - 1));
            }
            return new LjtNotFound(entry.sourceFile());
        }
        LiveJumpResolution resolution = resolveLiveJump(entry);
        if (resolution instanceof LjrNoTable) {
            return LjtNoTable.INSTANCE;
        } else if (resolution instanceof LjrProceed p) {
            return new LjtSearchByTable(p.getTableName());
        }
        return LjtNoTable.INSTANCE;
    }

    /** 等价右键「跳转到源码」（供 UI 测试断言精确跳转分支，避 headless 弹窗/后台搜索）。 */
    public void jumpToSourceForTest(LogSqlEntry entry) {
        jumpToSource(entry);
    }

    /**
     * F-44-6：日志 ↔ 源码关联跳转。
     * 优先精确跳转（条目携带源码位置）；无源码位置时回退「按表名定位实体 Wrapper」。
     */
    private void jumpToSource(LogSqlEntry entry) {
        LogJumpTarget target = resolveEntryJump(entry);
        if (target instanceof LjtPrecise p) {
            new OpenFileDescriptor(project, p.getVirtualFile(), p.getLine(), 0).navigate(true);
        } else if (target instanceof LjtNoSource) {
            Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("log.panel.jumpToSource.noSource"),
                    MpSqlVisualizerBundle.message("log.panel.jumpToSource"));
        } else if (target instanceof LjtNotFound nf) {
            Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("log.panel.jumpToSource.notFound", nf.getSourceFile()),
                    MpSqlVisualizerBundle.message("log.panel.jumpToSource"));
        } else if (target instanceof LjtSearchByTable st) {
            searchByTable(st.getTableName());
        } else if (target instanceof LjtNoTable) {
            Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("log.panel.jumpToSource.noTable"),
                    MpSqlVisualizerBundle.message("log.panel.jumpToSource"));
        }
    }

    /** 按表名后台搜索实体 Wrapper 并跳转（回退路径，尽力而为）。 */
    private void searchByTable(String tableName) {
        Task.Backgroundable task = new Task.Backgroundable(
                project, "Searching Wrappers for table \"" + tableName + "\"", false) {
            private WrapperSearchResult result = WrapperSearchResult.None.INSTANCE;

            @Override
            public void run(ProgressIndicator indicator) {
                indicator.setText("Searching Wrappers...");
                result = ReadAction.compute(() -> WrapperSearchService.search(project, tableName));
            }

            @Override
            public void onSuccess() {
                WrapperSearchResult r = result;
                if (r instanceof WrapperSearchResult.Single s) {
                    WrapperSearchService.navigateTo(project, s.candidate());
                } else if (r instanceof WrapperSearchResult.Multiple m) {
                    showCandidateChooser(m.candidates());
                } else if (r instanceof WrapperSearchResult.None) {
                    Messages.showInfoMessage(
                            project,
                            MpSqlVisualizerBundle.message("log.panel.jumpToSource.noWrapper", tableName),
                            MpSqlVisualizerBundle.message("log.panel.jumpToSource"));
                }
            }
        };
        task.queue();
    }

    /** 多候选选择弹窗（O-1 Phase A）。 */
    private void showCandidateChooser(List<WrapperCandidate> candidates) {
        String[] options = candidates.stream()
                .map(it -> it.anchorMethod().getName() + "  (" + it.file().getName() + ")")
                .toArray(String[]::new);
        String tableName = candidates.isEmpty() ? "" : candidates.get(0).table().getTableName();
        String header = MpSqlVisualizerBundle.message(
                "log.panel.jumpToSource.multipleHeader", candidates.size(), tableName);
        String initial = candidates.isEmpty() ? "" : options[0];
        int choice = Messages.showChooseDialog(
                project,
                header,
                MpSqlVisualizerBundle.message("log.panel.jumpToSource.multipleTitle"),
                Messages.getQuestionIcon(),
                options,
                initial);
        if (choice >= 0 && choice < candidates.size()) {
            WrapperSearchService.navigateTo(project, candidates.get(choice));
        }
    }

    /** 更新工具栏慢查询计数标签（O-1 Phase C）。 */
    private void updateSlowQueryLabel() {
        int count = slowQueryCounter.getSlowCount();
        if (slowQueryLabel != null) {
            if (count > 0) {
                slowQueryLabel.setText(MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", Integer.toString(count)));
                slowQueryLabel.setVisible(true);
            } else {
                slowQueryLabel.setVisible(false);
            }
        }
    }

    private void openPreview(LogSqlEntry entry) {
        // T-M31.14：设置开启时预览展示格式化版（多行缩进），否则原样
        PluginSettings.State state = PluginSettings.getInstance().getState();
        // T-M42：双击弹窗自动识别参数——`?` 数 == 参数数 ⇔ 匹配识别（值预填日志参数值，初始显示与现状一致）
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry);
        if (!recognized.recognized()) {
            // 回退现状：restoredSql + 未解析警告（参数缺失/数量不匹配/纯静态 SQL）
            String base = SqlLogPanelKt.withTerminator(entry.restoredSql());
            String sql = state.getLogFormatSql() ? SqlFormatter.format(base) : base;
            String warning = entry.restoredSql().contains("?")
                    ? MpSqlVisualizerBundle.message("log.preview.unresolvedWarning")
                    : null;
            SqlPreviewService.showResult(project, buildPreviewResult(
                    sql, entry.restoredSql(), List.of(), null, null, null, warning), null);
            return;
        }
        // 识别成功：rawSql 回填参数行（param1..N + 日志值预填 + 类型引号提示），全识别无警告
        String base = SqlLogPanelKt.withTerminator(recognized.sql());
        String sql = state.getLogFormatSql() ? SqlFormatter.format(base) : base;
        SqlPreviewService.showResult(project, buildPreviewResult(
                sql,
                SqlLogPanelKt.withTerminator(recognized.compactSql()),
                recognized.unresolvedParams(),
                null,
                null,
                null,
                null,
                recognized.paramHints(),
                recognized.initialValues()), null);
    }

    private PreviewResult buildPreviewResult(
            String sql, String compactSql, List<String> unresolvedParams,
            String paramTable, WrapperType wrapperType, String tableName, String warning) {
        return buildPreviewResult(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning,
                List.of(), Map.of());
    }

    private PreviewResult buildPreviewResult(
            String sql, String compactSql, List<String> unresolvedParams,
            String paramTable, WrapperType wrapperType, String tableName, String warning,
            List<ParamHint> paramHints, Map<String, String> initialValues) {
        return new PreviewResult(
                sql,
                compactSql,
                unresolvedParams,
                paramTable,
                wrapperType,
                tableName,
                warning,
                List.of(),                            // columns
                Map.of(),                             // columnComments
                null,                                // tableComment
                null,                                // previewModel
                paramHints,                          // paramHints
                List.of(),                           // candidateTables
                null,                                // batchModel
                null,                                // xmlModel
                initialValues,                       // initialParamValues
                null,                                // perfWarnings
                null,                                // fillClauses
                null,                                // previousSql
                null                                 // pagesColumns
        );
    }

    private void showContextMenu(MouseEvent e) {
        int index = list.locationToIndex(e.getPoint());
        if (index >= 0) {
            list.setSelectedIndex(index);
        }
        LogSqlEntry entry = list.getSelectedValue();
        if (entry == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem(MpSqlVisualizerBundle.message("log.panel.copy"));
        copyItem.addActionListener(ev -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(SqlLogPanelKt.withTerminator(entry.restoredSql())), null));
        menu.add(copyItem);
        JMenuItem deleteItem = new JMenuItem(MpSqlVisualizerBundle.message("log.panel.delete"));
        deleteItem.addActionListener(ev -> manager.getEntries().removeEntry(entry));
        menu.add(deleteItem);
        // T-M37 P1：发送到 SQL → Java 转换（第三 tab）——还原 SQL 写入输入区并切换选中该 tab
        JMenuItem sendReverseItem = new JMenuItem(MpSqlVisualizerBundle.message("log.panel.sendToReverse"));
        sendReverseItem.addActionListener(ev -> {
            sqlToJavaPanel.setInputSql(entry.restoredSql());
            tabs.setSelectedComponent(sqlToJavaPanel);
        });
        menu.add(sendReverseItem);
        // T-M38：发送到日志转 SQL（第二 tab）——条目渲染回日志文本并立即转换
        JMenuItem sendLogItem = new JMenuItem(MpSqlVisualizerBundle.message("logToSql.sendTo"));
        sendLogItem.addActionListener(ev -> {
            logToSqlPanel.loadFromEntry(entry);
            tabs.setSelectedComponent(logToSqlPanel);
        });
        menu.add(sendLogItem);
        // O-1：日志 ↔ 源码关联跳转（Phase A）
        JMenuItem jumpItem = new JMenuItem(MpSqlVisualizerBundle.message("log.panel.jumpToSource"));
        jumpItem.addActionListener(ev -> jumpToSource(entry));
        menu.add(jumpItem);
        menu.show(list, e.getX(), e.getY());
    }

    /** 面板销毁：释放日志转 SQL 与 SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    @Override
    public void dispose() {
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

    /** 耗时排序按钮（测试点击用）。 */
    public JToggleButton getDurationSortLabel() {
        return sortButton;
    }

    /** 去重勾选框（F-44-2 测试驱动：模拟用户勾选「移除重复的sql」验证既有条目立即去重）。 */
    public JCheckBox getDedupeCheckBoxForTest() {
        return dedupeCheckBox;
    }

    /** SQL类型多选按钮（测试断言文案）。 */
    public JButton getTypeFilterButtonForTest() {
        return typeFilterButton;
    }

    /** SQL类型标签（测试断言存在性与文案）。 */
    public JLabel getTypeFilterLabelForTest() {
        return typeFilterLabel;
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

    /** 工具栏慢查询计数标签（测试断言显隐与文案）。 */
    public JLabel getSlowQueryLabelForTest() {
        return slowQueryLabel;
    }

    private static final Logger LOG = Logger.getInstance(SqlLogPanel.class);
    private static final String TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar";
    public static final String DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";
    /** T-M39：帮助文档 URL（降级系统浏览器打开）。 */
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
