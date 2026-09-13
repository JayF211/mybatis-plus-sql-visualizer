package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.PagesColumn;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.SearchTextField;
import org.jetbrains.annotations.NotNull;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
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
 * <p>搜索框一级按文件名过滤、二级按 SQL 内容/源文件过滤（emptyText 动态切换）。
 */
public class HistorySqlLogPanel extends JPanel implements Disposable {

    // ---------- 一级：文件列表 ----------
    private final DefaultListModel<HistoryFileEntry> fileListModel = new DefaultListModel<>();
    private final JBList<HistoryFileEntry> fileList = new JBList<>(fileListModel);
    private List<HistoryFileEntry> allFiles = List.of();

    // ---------- 二级：SQL 列表（复用 SqlEntryList——Ctrl+C 复制纯 SQL 链路） ----------
    private final DefaultListModel<LogSqlEntry> sqlListModel = new DefaultListModel<>();
    private final SqlEntryList sqlList = new SqlEntryList(sqlListModel);
    /** T-ClickPreview：单击出轻量预览弹窗（点击时按行捕获 SQL，锚点在行下缘不遮行），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。 */
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
    private final JButton backButton = new JButton();
    private final JButton refreshButton = new JButton();

    /** 显示模式下拉（T-ListMode）：与日志 tab 共享同一设置；变更广播 SETTINGS_CHANGE_TOPIC。 */
    private final JComboBox<String> displayModeCombo;

    /** combo 程序化回同步防重入守卫。 */
    private boolean syncingDisplayModeCombo = false;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private final Project project;

    public HistorySqlLogPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.sqlClickPreview = new SqlClickPreview(project, sqlList, i -> SqlLogPanelKt.withTerminator(sqlListModel.get(i).restoredSql()));
        backButton.setIcon(AllIcons.Actions.Back);
        backButton.setFocusable(false);
        backButton.setToolTipText(MpSqlVisualizerBundle.message("log.history.back.tip"));
        backButton.setPreferredSize(new Dimension(28, 28));
        backButton.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        backButton.addActionListener(e -> showFileList());
        backButton.setVisible(false); // 默认一级，不显示返回
        refreshButton.setIcon(AllIcons.Actions.Refresh);
        refreshButton.setToolTipText(MpSqlVisualizerBundle.message("log.history.refresh.tip"));
        refreshButton.setFocusable(false);
        refreshButton.setPreferredSize(new Dimension(28, 28));
        refreshButton.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        refreshButton.addActionListener(e -> refresh());
        this.searchDebounceTimer.setRepeats(false);
        // T-ListMode：显示模式下拉（与日志 tab 同一设置，变更经 topic 双向同步）
        this.displayModeCombo = new JComboBox<>(new String[]{
                MpSqlVisualizerBundle.message("log.list.displayMode.single"),
                MpSqlVisualizerBundle.message("log.list.displayMode.keywordWrap"),
                MpSqlVisualizerBundle.message("log.list.displayMode.formatted"),
        });
        displayModeCombo.setToolTipText(MpSqlVisualizerBundle.message("log.list.displayMode"));
        displayModeCombo.setFocusable(false);
        displayModeCombo.setSelectedIndex(PluginSettings.getInstance().sqlListDisplayMode().ordinal());
        displayModeCombo.addItemListener(e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && !syncingDisplayModeCombo) {
                SqlListDisplayMode mode = SqlListDisplayMode.values()[displayModeCombo.getSelectedIndex()];
                PluginSettings settings = PluginSettings.getInstance();
                if (!java.util.Objects.equals(settings.getState().getLogListSqlDisplayMode(), mode.name())) {
                    settings.getState().setLogListSqlDisplayMode(mode.name());
                    project.getMessageBus().syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC)
                            .settingsChanged();
                }
            }
        });
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

    /** 显示模式下拉（测试断言选中态回同步用）。 */
    public JComboBox<?> getDisplayModeComboForTest() {
        return displayModeCombo;
    }

    /** 二级列表当前行高（测试断言行高随模式重设用）。 */
    public int getSqlListFixedCellHeightForTest() {
        return sqlList.getFixedCellHeight();
    }

    /** 显示模式变更处理（topic 监听与测试共用）：行高随模式重设 + 回同步 combo + 二级可见时重绘。
     *  多行片段缓存按条目内模式键自动失效（无需显式清）。日志面板同款处理经 topic 对齐。 */
    void onDisplayModeChanged() {
        syncingDisplayModeCombo = true;
        displayModeCombo.setSelectedIndex(PluginSettings.getInstance().sqlListDisplayMode().ordinal());
        syncingDisplayModeCombo = false;
        applySqlListCellHeight();
        if (currentFile != null) {
            sqlList.repaint();
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

    private void buildUI() {
        // 顶部工具栏：返回（仅二级可见）+ 刷新 + 搜索
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        toolbar.add(backButton);
        toolbar.add(refreshButton);
        // T-ListMode：SQL 显示模式下拉（与日志 tab 同一设置，变更经 topic 双向同步）
        toolbar.add(displayModeCombo);
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
        ApplicationManager.getApplication().invokeLater(
                this::onDisplayModeChanged,
                com.intellij.openapi.application.ModalityState.any());
    }

    // ---------- 视图切换 ----------

    private void showFileList() {
        currentFile = null;
        currentEntries = List.of();
        sqlClickPreview.hide(); // 返回一级时若二级预览弹窗还开着，一并关掉
        backButton.setVisible(false);
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.files"));
        cardLayout.show(cards, CARD_FILES);
        applyFileSearch();
    }

    private void showFileContent(HistoryFileEntry fileEntry) {
        currentFile = fileEntry;
        backButton.setVisible(true);
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.empty"));
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
        backButton.setVisible(true);
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.empty"));
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
        backButton.setVisible(false);
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

    /** 二级：按 SQL 内容 / 源文件过滤。 */
    private void applySqlSearch() {
        String query = searchField.getText().trim().toLowerCase(Locale.ENGLISH);
        sqlListModel.clear();
        List<LogSqlEntry> filtered = query.isEmpty()
                ? currentEntries
                : currentEntries.stream().filter(entry -> {
                    String sql = entry.restoredSql() == null ? "" : entry.restoredSql().toLowerCase(Locale.ENGLISH);
                    boolean srcMatch = entry.sourceFile() != null
                            && entry.sourceFile().toLowerCase(Locale.ENGLISH).contains(query);
                    return sql.contains(query) || srcMatch;
                }).collect(Collectors.toList());
        filtered.forEach(sqlListModel::addElement);
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
     * 与日志页一致，字面量条目走 F4 恢复；formatSql=false 保持历史页现状不格式化。
     */
    public PreviewResult buildPreviewResult(LogSqlEntry entry) {
        return LogEntryPreviewRecognizerKt.toPreviewResult(entry, false,
                PluginSettings.getInstance().getState().getLiteralParamRecovery());
    }

    // ---------- 二级右键菜单 ----------

    private void showSqlContextMenu(MouseEvent e) {
        int idx = sqlList.locationToIndex(e.getPoint());
        if (idx < 0) {
            return;
        }
        sqlList.setSelectedIndex(idx);
        LogSqlEntry entry = sqlList.getModel().getElementAt(idx);
        if (entry == null) {
            return;
        }

        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem(MpSqlVisualizerBundle.message("log.history.copy"), AllIcons.Actions.Copy);
        copyItem.addActionListener(ev -> {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(SqlLogPanelKt.withTerminator(entry.restoredSql())), null);
        });
        menu.add(copyItem);
        JMenuItem jumpItem = new JMenuItem(MpSqlVisualizerBundle.message("log.history.jumpToSource"), AllIcons.Actions.EditSource);
        jumpItem.addActionListener(ev -> jumpToSource(entry));
        menu.add(jumpItem);
        menu.show(sqlList, e.getX(), e.getY());
    }

    /**
     * 解析跳转目标（与 [jumpToSource] 共用；测试驱动直接断言分支，不触发弹窗）。
     */
    public HistoryJumpTarget resolveJumpTarget(LogSqlEntry entry) {
        if (entry.sourceFile() == null) {
            return HjtNoSource.INSTANCE;
        }
        // M37.1：多源根容错——basePath 直拼失败后按模块 content root 匹配（SourcePathResolver）
        VirtualFile virtualFile = SourcePathResolver.resolveVirtualFile(project, entry.sourceFile());
        if (virtualFile != null) {
            return new HjtNavigate(virtualFile, Math.max(0, entry.sourceLine() - 1));
        }
        return new HjtNotFound(entry.sourceFile());
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
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）。
     */
    private void jumpToSource(LogSqlEntry entry) {
        HistoryJumpTarget target = resolveJumpTarget(entry);
        if (target instanceof HjtNoSource) {
            // 缺源码位置：旧版 F-43 保存的条目无 sourceFile，提示无法跳转
            Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("log.history.jumpToSource.noSource"),
                    MpSqlVisualizerBundle.message("log.history.jumpToSource")
            );
        } else if (target instanceof HjtNotFound nf) {
            // 文件不存在时提示（项目结构可能已变化）
            Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("log.history.jumpToSource.notFound", nf.getSourceFile()),
                    MpSqlVisualizerBundle.message("log.history.jumpToSource")
            );
        } else if (target instanceof HjtNavigate nv) {
            new OpenFileDescriptor(project, nv.getVirtualFile(), nv.getLine(), 0).navigate(true);
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

    /** T-Perf-05：搜索输入防抖窗口（毫秒）。 */
    private static final int SEARCH_DEBOUNCE_MS = 200;
}
