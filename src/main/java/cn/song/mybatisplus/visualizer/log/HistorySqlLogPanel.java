package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.SearchTextField;
import org.jetbrains.annotations.NotNull;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.swing.DefaultListModel;
import javax.swing.JPanel;
import javax.swing.event.DocumentListener;

/**
 * 历史 SQL 面板（F-44-3 两级导航）：读取 .idea/sql-visualizer/sql-history/ 文件展示
 * （F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再读取）。
 *
 * <p>展示层级（用户要求）：
 * <ol>
 *   <li><b>一级（默认）</b>：文件列表——每行 = 文件名 | 文件大小 | SQL 条数；双击进入二级；</li>
 *   <li><b>二级</b>：单个文件的 SQL 列表——每行 = 耗时 + 执行时间 + 源码位置 + SQL 摘要（高亮）；
 *       双击 SQL → 预览弹窗；右键 → 复制 SQL / 跳转到源码；顶部「返回」回一级。</li>
 * </ol>
 *
 * <p>搜索框一级按文件名过滤、二级按 SQL 内容/行号/时间/源文件过滤（emptyText 动态切换）。
 */
public class HistorySqlLogPanel extends JPanel implements Disposable {

    // ---------- 一级：文件列表 ----------
    private final DefaultListModel<HistoryFileEntry> fileListModel = new DefaultListModel<>();
    private final JBList<HistoryFileEntry> fileList = new JBList<>(fileListModel);
    private List<HistoryFileEntry> allFiles = List.of();

    // ---------- 二级：SQL 列表（复用 SqlEntryList——Ctrl+C 复制纯 SQL 链路） ----------
    private final DefaultListModel<LogSqlEntry> sqlListModel = new DefaultListModel<>();
    private final SqlEntryList sqlList = new SqlEntryList(sqlListModel);
    /** 二级速度搜索是否已安装。构造于 CardLayout 隐藏卡时安装不生效（真机 key 流不达、焦点不在），
     *  故延后到 showFileContent/loadFileContentSync 切卡可见后安装（T-UiNative T-UN-05）。 */
    private boolean sqlSpeedSearchInstalled = false;
    /** T-ClickPreview：单击出轻量预览弹窗（点击时按行捕获 SQL，锚点在行下缘不遮行），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。
     *  T-ListPreviewCopy：预览文本经 clickPreviewSql——设置「预览时格式化还原 SQL」开启（默认）时多行格式化显示。 */
    private final SqlClickPreview sqlClickPreview;
    private HistoryFileEntry currentFile = null;
    private List<LogSqlEntry> currentEntries = List.of();

    // ---------- T-Perf-05：后台任务代际 + 搜索防抖 ----------

    /** 后台刷新/加载代际：新任务启动即作废旧任务的 EDT apply。 */
    private int bgGeneration = 0;

    /** T-Perf-05：搜索防抖（每击键 O(N) clear+add → 停顿 200ms 应用一次）。 */
    private final javax.swing.Timer searchDebounceTimer =
            new javax.swing.Timer(SEARCH_DEBOUNCE_MS, e -> applySearch());

    private final SearchTextField searchField = new SearchTextField();

    // ---------- T-UiNative T-UN-10：顶部导航动作（原手摆 28×28「仿平台按钮」JButton 平移） ----------

    /** 导航工具栏引用（层级切换后即时刷新 Back 可见性；T-UN-10）。 */
    private ActionToolbar _navToolbarRef = null;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private final Project project;

    public HistorySqlLogPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        // T-UiNative P0：一级文件列表打字即搜（平台 ListSpeedSearch，按文件名匹配）
        ListSpeedSearch.installOn(fileList, HistoryFileEntry::getDisplayName);
        this.sqlClickPreview = new SqlClickPreview(project, sqlList, i -> SqlClickPreview.clickPreviewSql(
                sqlListModel.getElementAt(i).restoredSql(),
                PluginSettings.getInstance().getState().getLogFormatSql()));
        this.searchDebounceTimer.setRepeats(false);
        buildUI();
        refresh();
    }

    // ---------- 测试访问器（F-44-3 两级导航真实场景测试） ----------

    /** 一级文件列表模型条目（测试断言：文件名/大小/条数）。 */
    public List<HistoryFileEntry> getFileListEntries() {
        List<HistoryFileEntry> result = new ArrayList<>();
        for (int i = 0; i < fileListModel.size(); i++) {
            result.add(fileListModel.getElementAt(i));
        }
        return result;
    }

    /** 二级 SQL 列表模型条目（测试断言）。 */
    public List<LogSqlEntry> getSqlListEntries() {
        List<LogSqlEntry> result = new ArrayList<>();
        for (int i = 0; i < sqlListModel.size(); i++) {
            result.add(sqlListModel.getElementAt(i));
        }
        return result;
    }

    /** 当前是否处于一级文件列表视图（测试断言）。 */
    public boolean isShowingFiles() {
        return currentFile == null;
    }

    /** 打开指定文件进入二级（测试驱动；等价双击文件）。 */
    public void openFileForTest(HistoryFileEntry entry) {
        loadFileContentSync(entry);
    }

    /** 返回一级（测试驱动；等价点击返回按钮）。 */
    public void backToFilesForTest() {
        showFileList();
    }

    /** 设置搜索词（测试驱动；等价在搜索框输入，立即应用过滤——生产路径 DocumentListener 走防抖）。 */
    public void setSearchForTest(String query) {
        searchField.setText(query);
        applySearch();
    }

    /** 二级列表当前行高（测试断言行高随模式重设用）。 */
    public int getSqlListFixedCellHeightForTest() {
        return sqlList.getFixedCellHeight();
    }

    /** 二级列表实例（测试断言 getCellBounds 行高与渲染 preferred 一致——布局缓存失效防回归用）。 */
    public javax.swing.JList<?> getSqlListForTest() {
        return sqlList;
    }

    /** 显示模式变更处理（topic 监听与测试共用）：行高随模式重设 + 二级可见时全量重建。
     *  多行片段缓存按条目内模式键自动失效（无需显式清）。日志面板同款处理经 topic 对齐。
     *  T-MergeFollowup-03（复查二轮）：BasicListUI 的 cellHeights 布局缓存不归 Swing layout 管
     *  （revalidate 无效），多行模式间切换 -1→-1 也不触发 setFixedCellHeight 的 propertyChange——
     *  updateUI() 重装列表 UI 强制布局状态失效（与日志页同款），applySqlSearch 全量重建作为双保险。
     *  T-DisplayModeToSettings：入口迁设置页「列表显示」，此处仅响应设置广播。 */
    void onDisplayModeChanged() {
        applySqlListCellHeight();
        sqlList.updateUI();
        if (currentFile != null) {
            applySqlSearch();
        }
    }

    /**
     * 行高随显示模式分流（T-ListMode × T-LogListFreeze-04 权衡，与日志 tab SqlLogPanel.applyListCellHeight 同款）：
     * SINGLE_LINE 固定行高走 O(1) 布局快路径；KEYWORD_WRAP/FORMATTED 可变行高（条目行数不等），
     * 多行片段有条目级缓存，逐条测量只剩轻量组件布局。
     */
    void applySqlListCellHeight() {
        Component c = sqlList.getCellRenderer().getListCellRendererComponent(
                sqlList, new LogSqlEntry("SELECT 1", "SELECT 1", List.of()), 0, false, false);
        sqlList.setFixedCellHeight(
                PluginSettings.getInstance().sqlListDisplayMode() == SqlListDisplayMode.SINGLE_LINE
                        ? c.getPreferredSize().height
                        : -1);
    }

    /** 二级 SQL 列表单击预览控制器（T-ClickPreview 测试断言）。 */
    public SqlClickPreview getSqlClickPreviewForTest() {
        return sqlClickPreview;
    }

    /** 返回一级（原 backButton）：图标 Back、tooltip 沿用 log.history.back.tip；可见性 = 二级列表（update 输出）。 */
    private class BackAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Back);
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.history.back.tip"));
            e.getPresentation().setVisible(!isShowingFiles());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            showFileList();
        }
    }

    /** 刷新（原 refreshButton）：图标 Refresh、tooltip 沿用 log.history.refresh.tip。 */
    private class RefreshAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Refresh);
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.history.refresh.tip"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            refresh();
        }
    }

    /** 层级切换后即时刷新导航工具栏（Back 可见性走 update；组件不可见时平台跳过，无副作用）。 */
    private void refreshNavToolbar() {
        ActionToolbar toolbar = _navToolbarRef;
        if (toolbar != null) {
            toolbar.updateActionsImmediately();
        }
    }

    private void buildUI() {
        // 顶部工具栏：返回（仅二级可见）+ 刷新 + 搜索
        // T-UiNative T-UN-10：手摆按钮 → 平台 ActionToolbar（Back 可见性由 BackAction.update 按当前层级输出）
        ActionToolbar navToolbar = ActionManager.getInstance()
                .createActionToolbar("mpvs.history.nav", new DefaultActionGroup(new BackAction(), new RefreshAction()), true);
        navToolbar.setTargetComponent(this);
        _navToolbarRef = navToolbar;
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        toolbar.add(navToolbar.getComponent());
        // T-DisplayModeToSettings：显示模式入口迁设置页「列表显示」（工具栏按钮移除）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.files"));
        Dimension minSize = searchField.getTextEditor().getMinimumSize();
        searchField.getTextEditor().setMinimumSize(new Dimension(200, minSize.height));
        searchField.getTextEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                scheduleSearch();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                scheduleSearch();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                scheduleSearch();
            }
        });
        toolbar.add(searchField);
        add(toolbar, BorderLayout.NORTH);

        // T-ListMode：设置变更（含模式切换广播）→ 清二级列表渲染缓存并重绘；
        // connect(this) 随面板 Disposer 树自动注销。invokeLater 显式 ModalityState.any()
        // （设置页红线：模态弹窗存续期默认 modality 会挂起回调）
        project.getMessageBus().connect(this).subscribe(
                PluginSettings.SETTINGS_CHANGE_TOPIC,
                (cn.song.mybatisplus.visualizer.config.SettingsChangeListener) this::onSettingsChanged);

        // 一级：文件列表
        fileList.getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.files.empty"));
        HistoryFileCellRenderer fileRenderer = new HistoryFileCellRenderer();
        fileList.setCellRenderer(fileRenderer);
        // T-LogListFreeze-04：固定行高（单行恒定高）——JList 布局走 O(1) 快路径，不再逐条调渲染器测量
        fileList.setFixedCellHeight(fileRenderer.getListCellRendererComponent(
                fileList, new HistoryFileEntry(new File("sample.sql"), 1), 0, false, false).getPreferredSize().height);
        fileList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    HistoryFileEntry selected = fileList.getSelectedValue();
                    if (selected != null) {
                        showFileContent(selected);
                    }
                }
            }
        });
        cards.add(new JBScrollPane(fileList), CARD_FILES);

        // 二级：SQL 列表
        sqlList.getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.entries.empty"));
        HistoryCellRenderer sqlRenderer = new HistoryCellRenderer();
        sqlList.setCellRenderer(sqlRenderer);
        applySqlListCellHeight();
        sqlList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // T-ClickPreview：先转发（仅左键；单击命中行才排程预览/双击停表并关旧预览），双击再开完整弹窗
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    sqlClickPreview.onClick(e.getClickCount(), e.getPoint());
                }
                if (e.getClickCount() >= 2) {
                    LogSqlEntry selected = sqlList.getSelectedValue();
                    if (selected != null) {
                        openPreview(selected);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showSqlContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showSqlContextMenu(e);
                }
            }
        });
        cards.add(new JBScrollPane(sqlList), CARD_SQL);
        add(cards, BorderLayout.CENTER);
    }

    /** 设置变更入口（SETTINGS_CHANGE_TOPIC）：EDT + ModalityState.any() 后走 onDisplayModeChanged。 */
    private void onSettingsChanged() {
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater(
                this::onDisplayModeChanged,
                com.intellij.openapi.application.ModalityState.any());
    }

    // ---------- 视图切换 ----------

    private void showFileList() {
        currentFile = null;
        currentEntries = List.of();
        sqlClickPreview.hide(); // 返回一级时若二级预览弹窗还开着，一并关掉
        refreshNavToolbar(); // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = false）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.files"));
        cardLayout.show(cards, CARD_FILES);
        applyFileSearch();
    }

    private void showFileContent(HistoryFileEntry fileEntry) {
        currentFile = fileEntry;
        // T-UiNative T-UN-05：二级 SQL 列表切卡可见后重挂速度搜索（构造于隐藏卡时 installOn
        // 仍不生效的兜底；标记防重复装——平台 installOn 重复挂载同一列表不幂等）
        if (!sqlSpeedSearchInstalled) {
            sqlSpeedSearchInstalled = true;
            ListSpeedSearch.installOn(sqlList, entry ->
                    SqlLogPanelKt.withTerminator(entry.restoredSql()));
        }
        refreshNavToolbar(); // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.sql"));
        cardLayout.show(cards, CARD_SQL);
        // T-Perf-05：立即清空旧文件内容（大文件后台读取期间不残留旧文件条目，防误操作）
        currentEntries = List.of();
        applySqlSearch();
        final int gen = ++bgGeneration;
        new Task.Backgroundable(project, MpSqlVisualizerBundle.message("log.history.load.progress"), true) {
            @Override
            public void run(com.intellij.openapi.progress.ProgressIndicator indicator) {
                final List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(fileEntry.file());
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (gen != bgGeneration || project.isDisposed()) {
                        return;
                    }
                    currentEntries = entries;
                    applySqlSearch();
                    LOG.info("HistorySqlLogPanel.showFileContent: " + fileEntry.file().getName()
                            + " 共 " + entries.size() + " 条 SQL");
                });
            }
        }.queue();
    }

    /** 测试缝：同步加载文件内容（生产路径 showFileContent 已后台化，headless 测试需要确定性）。 */
    private void loadFileContentSync(HistoryFileEntry fileEntry) {
        currentFile = fileEntry;
        // T-UiNative T-UN-05：二级 SQL 列表可见后确保速度搜索安装（生产 showFileContent 已装，
        // 此同步路径 headless 下 CardLayout 不见真机 showing，marker 判定可能不触发，故统一补装）
        if (!sqlSpeedSearchInstalled) {
            sqlSpeedSearchInstalled = true;
            ListSpeedSearch.installOn(sqlList, entry ->
                    SqlLogPanelKt.withTerminator(entry.restoredSql()));
        }
        refreshNavToolbar(); // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.sql"));
        cardLayout.show(cards, CARD_SQL);
        currentEntries = SqlLogFileReader.INSTANCE.readFromFile(fileEntry.file());
        applySqlSearch();
    }

    // ---------- 刷新 ----------

    /**
     * T-CrudColor：仅重绘二级 SQL 列表（不重扫文件、不重载列表）。
     *
     * 设置页改了配色/标记样式后由 SqlLogPanel 的设置变更监听调用——渲染器直读设置，
     * 重绘即生效；走 repaint 而非 {@link #refresh} 是为了不打断用户当前浏览位置、不触发磁盘扫描。
     */
    void repaintSqlList() {
        sqlList.repaint();
    }

    /**
     * 扫描 .idea 目录：生成文件摘要列表；若正在浏览某文件且文件仍存在则保持二级并重载，否则回一级。
     *
     * T-Perf-05：目录扫描 + countInFile / readFromFile 全部移入 {@link Task.Backgroundable}
     * （100MB 自动保存文件的逐行扫描在 EDT 直跑必然长冻结）；EDT 只做结果应用。
     * 代际守卫：新一轮刷新/加载启动后，旧后台任务的 apply 自动作废。
     */
    public void refresh() {
        final int gen = ++bgGeneration;
        final File keepFile = currentFile != null ? currentFile.file() : null;
        new Task.Backgroundable(project, MpSqlVisualizerBundle.message("log.history.scan.progress"), true) {
            @Override
            public void run(com.intellij.openapi.progress.ProgressIndicator indicator) {
                final List<HistoryFileEntry> scanned = scanHistoryFiles(getHistoryDir());
                final List<LogSqlEntry> keepReload = keepFile != null && keepFile.exists()
                        ? SqlLogFileReader.INSTANCE.readFromFile(keepFile)
                        : null;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (gen != bgGeneration || project.isDisposed()) {
                        return;
                    }
                    applyRefresh(scanned, keepReload);
                });
            }
        }.queue();
    }

    /** 测试缝：同步刷新（生产路径 refresh() 已后台化；headless 测试需要确定性）。 */
    void refreshForTest() {
        bgGeneration++; // 作废在途后台 apply（构造期 refresh() 的后台扫描可能尚未完成）
        File keepFile = currentFile != null ? currentFile.file() : null;
        applyRefresh(scanHistoryFiles(getHistoryDir()),
                keepFile != null && keepFile.exists() ? SqlLogFileReader.INSTANCE.readFromFile(keepFile) : null);
    }

    /** 目录扫描 + 条目计数（纯 IO，后台执行）。 */
    private List<HistoryFileEntry> scanHistoryFiles(String dir) {
        File directory = new File(dir);
        List<File> files;
        if (directory.isDirectory()) {
            File[] raw = directory.listFiles(f -> f.getName().endsWith(".sql"));
            files = raw == null ? List.of() :
                    Arrays.stream(raw).sorted(Comparator.comparing(File::getName)).collect(Collectors.toList());
        } else {
            files = List.of();
        }
        List<HistoryFileEntry> result = new ArrayList<>();
        for (File file : files) {
            result.add(new HistoryFileEntry(file, SqlLogFileReader.INSTANCE.countInFile(file)));
        }
        return result;
    }

    /** 应用扫描结果（EDT；仅刷新/加载代际最新者执行）。 */
    private void applyRefresh(List<HistoryFileEntry> scanned, List<LogSqlEntry> keepReload) {
        allFiles = scanned;
        LOG.info("HistorySqlLogPanel.refresh: 扫描到 " + allFiles.size() + " 个历史文件");

        // 保持二级视图：当前文件仍存在则重载内容
        if (keepReload != null) {
            currentEntries = keepReload;
            applySqlSearch();
            return;
        }
        // 否则回一级（默认展示文件列表）
        currentFile = null;
        currentEntries = List.of();
        refreshNavToolbar(); // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = false）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.files"));
        cardLayout.show(cards, CARD_FILES);
        applyFileSearch();
    }

    // ---------- 搜索过滤 ----------

    /** T-Perf-05：搜索输入防抖调度（DocumentListener 入口）。 */
    private void scheduleSearch() {
        searchDebounceTimer.restart();
    }

    private void applySearch() {
        if (currentFile != null) {
            applySqlSearch();
        } else {
            applyFileSearch();
        }
    }

    /** 一级：按文件名过滤。 */
    private void applyFileSearch() {
        String query = searchField.getText().trim().toLowerCase(Locale.ENGLISH);
        fileListModel.clear();
        List<HistoryFileEntry> filtered = query.isEmpty()
                ? allFiles
                : allFiles.stream()
                .filter(it -> it.getDisplayName().toLowerCase(Locale.ENGLISH).contains(query))
                .collect(Collectors.toList());
        filtered.forEach(fileListModel::addElement);
    }

    /**
     * 二级：按 SQL 内容 / 行号 / 时间 / 源文件过滤（T-LogSearchRowTime 与日志页 filterBySearch
     * 同款语义：行号 {@code #5}/{@code 5} 精确 equals、时间按显示片段 contains（所见即所搜，开关关闭不参与）；
     * 源文件为历史页特有检索目标，保留独立分支。
     */
    private void applySqlSearch() {
        String q = searchField.getText().trim();
        List<LogSqlEntry> filtered;
        if (q.isEmpty()) {
            filtered = currentEntries;
        } else {
            Long rowNumber = null;
            String rowRaw = q.startsWith("#") ? q.substring(1) : q;
            try {
                rowNumber = Long.parseLong(rowRaw);
            } catch (NumberFormatException ignored) {
                // 非纯数字查询 → 行号维度不参与
            }
            final Long rowNumberFinal = rowNumber;
            filtered = currentEntries.stream().filter(entry -> {
                String lowerQ = q.toLowerCase(Locale.ENGLISH);
                if (containsIgnoreCase(entry.restoredSql(), lowerQ)
                        || containsIgnoreCase(entry.summary(), lowerQ)) {
                    return true;
                }
                if (rowNumberFinal != null && entry.sequenceNumber() == rowNumberFinal) {
                    return true;
                }
                String ts = SqlLogPanelKt.timestampDisplayText(entry);
                if (ts != null && ts.toLowerCase(Locale.ENGLISH).contains(lowerQ)) {
                    return true;
                }
                return entry.sourceFile() != null
                        && entry.sourceFile().toLowerCase(Locale.ENGLISH).contains(lowerQ);
            }).collect(Collectors.toList());
        }
        fillSqlList(filtered);
    }

    /**
     * T-SqlHistoryFull：列表分批填充（复用实时面板 applyFullRebuild 范式）——列表模型全量同步
     * 填充在条目千百级时产生上千次模型变更事件 + 布局失效，是历史打开卡顿主源。
     * 改为：ignoreRepaint 批量抑制布局 + 首屏 30 条立即写入 + 剩余每 30 条 invokeLater 追加；
     * gen 代际守卫——新一轮 fillSqlList（重新加载/搜索/刷新）启动即终止旧分块链，杜绝幽灵/重复条目。
     */
    private void fillSqlList(List<LogSqlEntry> entries) {
        final int gen = ++bgGeneration;
        sqlList.setIgnoreRepaint(true);
        sqlListModel.clear();
        if (!entries.isEmpty()) {
            for (LogSqlEntry e : entries.subList(0, Math.min(REFRESH_FIRST_BATCH_SIZE, entries.size()))) {
                sqlListModel.addElement(e);
            }
        }
        sqlList.setIgnoreRepaint(false);
        sqlList.revalidate();
        sqlList.repaint();

        if (entries.size() > REFRESH_FIRST_BATCH_SIZE) {
            java.util.Iterator<LogSqlEntry> iterator =
                    entries.subList(REFRESH_FIRST_BATCH_SIZE, entries.size()).iterator();
            Runnable[] appendNext = new Runnable[1];
            appendNext[0] = () -> {
                if (gen != bgGeneration || project.isDisposed()) return; // 新一轮已启动，旧链终止
                List<LogSqlEntry> chunk = new ArrayList<>();
                for (int i = 0; i < REFRESH_CHUNK_SIZE && iterator.hasNext(); i++) {
                    chunk.add(iterator.next());
                }
                for (LogSqlEntry e : chunk) {
                    sqlListModel.addElement(e);
                }
                if (iterator.hasNext()) {
                    ApplicationManager.getApplication().invokeLater(appendNext[0]);
                }
            };
            ApplicationManager.getApplication().invokeLater(appendNext[0]);
        }
    }

    private static boolean containsIgnoreCase(String text, String lowerQ) {
        return text != null && text.toLowerCase(Locale.ENGLISH).contains(lowerQ);
    }

    private String getHistoryDir() {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return "";
        }
        return basePath + File.separator + ".idea" + File.separator + SqlLogAutoSaver.DATA_DIR_NAME + File.separator + "sql-history";
    }

    private void openPreview(LogSqlEntry entry) {
        SqlPreviewService.showResult(project, buildPreviewResult(entry), null);
    }

    /**
     * 双击预览的 PreviewResult 构建（T-HistParam F3，internal 供 UI 测试不弹窗断言识别结果）：
     * 与日志 tab 共用 toPreviewResult 统一识别通道——v2 历史条目（rawSql/params 齐全）参数行
     * 与日志页一致，字面量条目走 F4 恢复；T-ListPreviewCopy：formatSql 改跟随设置
     * 「预览时格式化还原 SQL」（logFormatSql，默认开=默认格式化），消除与日志 tab 双击预览的不一致。
     */
    public PreviewResult buildPreviewResult(LogSqlEntry entry) {
        return LogEntryPreviewRecognizerKt.toPreviewResult(entry,
                PluginSettings.getInstance().getState().getLogFormatSql(),
                PluginSettings.getInstance().getState().getLiteralParamRecovery());
    }

    // ---------- 二级右键菜单 ----------

    private void showSqlContextMenu(MouseEvent e) {
        int idx = sqlList.locationToIndex(e.getPoint());
        if (idx < 0) {
            return;
        }
        // T-ListPreviewCopy：与日志 tab 同款选中收敛——命中行已在多选内保留（复制项=批量复制多条），
        // 否则收敛为该行；跳转等其余菜单项恒作用于被右键的行
        sqlList.setSelectedIndices(SqlLogPanelKt.resolveContextMenuSelection(sqlList.getSelectedIndices(), idx));
        LogSqlEntry entry = sqlListModel.getElementAt(idx);
        List<LogSqlEntry> selectedEntries = new ArrayList<>();
        for (int i : sqlList.getSelectedIndices()) {
            selectedEntries.add(sqlListModel.getElementAt(i));
        }

        // T-UiNative P1a：JPopupMenu+JMenuItem → 平台 DefaultActionGroup+AnAction（ActionPopupMenu 承载）
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new CopySqlAction(selectedEntries));
        // T-LogSearchRowTime：复制时间戳（与日志页同款——纯时间文本无方括号；显示关闭时置灰，
        // 置灰逻辑在 CopyTimestampAction.update）
        String timestampClip = SqlLogPanelKt.batchCopyTimestamps(selectedEntries);
        group.add(new CopyTimestampAction(selectedEntries, timestampClip));
        group.add(new JumpToSourceAction(entry));
        // T-M55-01：拆「跳转到 XML」双目标（与日志页同套）
        group.add(new JumpToMapperXmlAction(entry));
        com.intellij.openapi.actionSystem.ActionPopupMenu popup =
                ActionManager.getInstance().createActionPopupMenu("mpvs.history.sql", group);
        // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成 targetComponent 属性——显式调 setter
        popup.setTargetComponent(sqlList);
        popup.getComponent().show(sqlList, e.getX(), e.getY());
    }

    // ---------- T-UiNative P1a：二级右键菜单动作（原 JMenuItem 语义平移，update 设图标/文案/灰置） ----------

    private class CopySqlAction extends AnAction {
        private final List<LogSqlEntry> entries;

        CopySqlAction(List<LogSqlEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Copy);
            e.getPresentation().setText(SqlLogPanelKt.copyMenuText(
                    "log.history.copy", "log.history.copySelected", entries.size()));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            UiTextClipboard.copy(SqlLogPanelKt.batchCopySql(entries));
        }
    }

    private class CopyTimestampAction extends AnAction {
        private final List<LogSqlEntry> entries;
        private final String clip;

        CopyTimestampAction(List<LogSqlEntry> entries, String clip) {
            this.entries = entries;
            this.clip = clip;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Copy);
            e.getPresentation().setText(SqlLogPanelKt.copyMenuText(
                    "log.history.copyTimestamp", "log.history.copyTimestampSelected", entries.size()));
            e.getPresentation().setEnabled(!clip.isEmpty());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (!clip.isEmpty()) {
                UiTextClipboard.copy(clip);
            }
        }
    }

    /** 跳转到 Java 调用方（T-M55-01：与「跳转到 XML」拆分为双目标，与日志页同套）。 */
    private class JumpToSourceAction extends AnAction {
        private final LogSqlEntry entry;

        JumpToSourceAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.EditSource);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.history.jumpToJava"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            jumpToSource(entry);
        }
    }

    /**
     * T-M55-01：跳转到 mapper XML 语句节点。mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用）；定位失败不显示，「跳转到 Java」承担回退，不劣化。
     */
    private class JumpToMapperXmlAction extends AnAction {
        private final LogSqlEntry entry;
        private HjtMapperXml resolved;

        JumpToMapperXmlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.FileTypes.Xml);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.history.jumpToXml"));
            resolved = resolveXmlJumpTarget(entry);
            e.getPresentation().setEnabledAndVisible(resolved != null);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            HjtMapperXml target = resolved != null ? resolved : resolveXmlJumpTarget(entry);
            if (target == null) {
                return;
            }
            new OpenFileDescriptor(project, target.getVirtualFile(), target.getLine(), 0).navigate(true);
        }
    }

    /**
     * 解析跳转目标（与 {@link #jumpToSource(LogSqlEntry)} 共用；测试驱动直接断言分支，不触发弹窗）。
     * T-SourceJumpFunnel P0-5：判定收敛到 {@link EntryJumpResolver}（与实时面板同一实现）——
     * 主位置不在工程内（自研框架汇聚门面帧）时按候选栈序回退，本面板此前无此能力。
     */
    public HistoryJumpTarget resolveJumpTarget(LogSqlEntry entry) {
        EntryJumpResolver.JumpTarget target = EntryJumpResolver.resolve(project, entry);
        if (target instanceof EntryJumpResolver.JumpTarget.Precise precise) {
            return new HjtNavigate(precise.virtualFile(), precise.line());
        }
        if (target == EntryJumpResolver.JumpTarget.NoSource.INSTANCE) {
            return HjtNoSource.INSTANCE;
        }
        if (target instanceof EntryJumpResolver.JumpTarget.NotFound notFound) {
            return new HjtNotFound(notFound.sourceFile());
        }
        // MissingSource：历史页无源码位置语义与 NoSource 同路（引导开启源码捕获）
        return HjtNoSource.INSTANCE;
    }

    /**
     * T-M55-01：XML 目标解析——与日志页 {@code SqlLogPanel.resolveEntryXmlJump} 同语义。
     * mapperStatement 非空且 {@link MapperXmlLocator} 定位成功才返回；否则 null（回退 Java，不劣化）。
     */
    public HjtMapperXml resolveXmlJumpTarget(LogSqlEntry entry) {
        String statementId = entry.mapperStatement();
        if (statementId == null || statementId.isBlank()) {
            return null;
        }
        MapperXmlLocator.Location location = MapperXmlLocator.locate(project, statementId);
        if (location == null) {
            return null;
        }
        return new HjtMapperXml(location.virtualFile(), location.line());
    }

    /**
     * 跳转到产生该 SQL 的源码位置（测试驱动；等价右键「跳转到源码」）。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）。
     */
    public void jumpToSourceForTest(LogSqlEntry entry) {
        jumpToSource(entry);
    }

    /**
     * 跳转到产生该 SQL 的源码位置。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）；
     * T-JumpCapture：无捕获位置时弹框引导开启源码捕获拦截器。
     */
    private void jumpToSource(LogSqlEntry entry) {
        HistoryJumpTarget target = resolveJumpTarget(entry);
        if (target instanceof HjtNoSource) {
            // 缺源码位置（旧版 F-43 条目 / 未启用拦截器捕获）：引导开启源码捕获（T-JumpCapture）
            SourceCaptureGuide.showMissingSourceDialog(project);
        } else if (target instanceof HjtNotFound nf) {
            // 文件不存在时提示（项目结构可能已变化）；T-UiNative T-UN-02：改轻通知（原模态弹窗）
            // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                try {
                    new Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("log.history.jumpToSource"),
                            MpSqlVisualizerBundle.message("log.history.jumpToSource.notFound", nf.getSourceFile()),
                            NotificationType.INFORMATION).notify(project);
                } catch (Throwable ignored) {
                    // 通知失败不影响主流程
                }
            }
        } else if (target instanceof HjtNavigate nv) {
            new OpenFileDescriptor(project, nv.getVirtualFile(), nv.getLine(), 0).navigate(true);
        } else if (target instanceof HjtMapperXml mx) {
            // resolveJumpTarget 不产出该分支（XML 目标走 resolveXmlJumpTarget/JumpToMapperXmlAction）；防御性导航
            new OpenFileDescriptor(project, mx.getVirtualFile(), mx.getLine(), 0).navigate(true);
        }
    }

    @Override
    public void dispose() {
        // T-Perf-05：面板销毁停止防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop();
        sqlClickPreview.dispose();
    }

    private static final Logger LOG = Logger.getInstance(HistorySqlLogPanel.class);
    private static final String CARD_FILES = "files";
    private static final String CARD_SQL = "sql";

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** T-Perf-05：搜索输入防抖窗口（毫秒）。 */
    private static final int SEARCH_DEBOUNCE_MS = 200;

    /** T-SqlHistoryFull：二级列表分批填充——首屏立即写入条数（与实时面板同值，快速首屏）。 */
    private static final int REFRESH_FIRST_BATCH_SIZE = 30;

    /** T-SqlHistoryFull：二级列表分批填充——每块 invokeLater 追加条数（防 EDT 长冻结）。 */
    private static final int REFRESH_CHUNK_SIZE = 30;
}
