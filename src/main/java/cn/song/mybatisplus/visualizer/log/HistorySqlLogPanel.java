package cn.song.mybatisplus.visualizer.log;

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
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
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

    private final SearchTextField searchField = new SearchTextField();
    private final JButton backButton = new JButton();
    private final JButton refreshButton = new JButton();

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
        showFileContent(entry);
    }

    /** 返回一级（测试驱动；等价点击返回按钮）。 */
    public void backToFilesForTest() {
        showFileList();
    }

    /** 设置搜索词（测试驱动；等价在搜索框输入，触发 DocumentListener 实时过滤）。 */
    public void setSearchForTest(String query) {
        searchField.setText(query);
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
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.files"));
        Dimension minSize = searchField.getTextEditor().getMinimumSize();
        searchField.getTextEditor().setMinimumSize(new Dimension(200, minSize.height));
        searchField.getTextEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applySearch();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applySearch();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applySearch();
            }
        });
        toolbar.add(searchField);
        add(toolbar, BorderLayout.NORTH);

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
        // T-LogListFreeze-04：固定行高（两行恒定高——元信息行 + SQL 行，T-TwoLine 结构保留）
        sqlList.setFixedCellHeight(sqlRenderer.getListCellRendererComponent(
                sqlList, new LogSqlEntry("SELECT 1", "SELECT 1", List.of()), 0, false, false).getPreferredSize().height);
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
        currentEntries = SqlLogFileReader.INSTANCE.readFromFile(fileEntry.file());
        backButton.setVisible(true);
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.empty"));
        cardLayout.show(cards, CARD_SQL);
        applySqlSearch();
        LOG.info("HistorySqlLogPanel.showFileContent: " + fileEntry.file().getName() + " 共 " + currentEntries.size() + " 条 SQL");
    }

    // ---------- 刷新 ----------

    /** 扫描 .idea 目录：生成文件摘要列表；若正在浏览某文件且文件仍存在则保持二级并重载，否则回一级。 */
    public void refresh() {
        String dir = getHistoryDir();
        File directory = new File(dir);
        List<HistoryFileEntry> files;
        if (directory.isDirectory()) {
            File[] raw = directory.listFiles(f -> f.getName().endsWith(".sql"));
            List<File> sorted = raw == null ? List.of() :
                    Arrays.stream(raw).sorted(Comparator.comparing(File::getName)).collect(Collectors.toList());
            files = sorted.stream()
                    .map(file -> new HistoryFileEntry(file, SqlLogFileReader.INSTANCE.countInFile(file)))
                    .collect(Collectors.toList());
        } else {
            files = List.of();
        }
        allFiles = files;
        LOG.info("HistorySqlLogPanel.refresh: 目录 " + dir + " 扫描到 " + allFiles.size() + " 个历史文件");

        // 保持二级视图：当前文件仍存在则重载内容
        File keep = currentFile != null ? currentFile.file() : null;
        if (keep != null && keep.exists()) {
            currentEntries = SqlLogFileReader.INSTANCE.readFromFile(keep);
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
        String base = SqlLogPanelKt.withTerminator(entry.restoredSql());
        SqlPreviewService.showResult(project, new PreviewResult(
                base,                                  // sql
                entry.restoredSql(),                   // compactSql
                List.of(),                             // unresolvedParams
                null,                                 // paramTable
                null,                                 // wrapperType
                null,                                 // tableName
                null,                                 // warning
                List.of(),                            // columns
                Map.of(),                             // columnComments
                null,                                 // tableComment
                null,                                 // previewModel
                List.of(),                            // paramHints
                List.of(),                            // candidateTables
                null,                                 // batchModel
                null,                                 // xmlModel
                Map.of(),                             // initialParamValues
                null,                                 // perfWarnings
                null,                                 // fillClauses
                null,                                 // previousSql
                null                                  // pagesColumns
        ), null);
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
        String basePath = project.getBasePath();
        if (basePath == null) {
            return HjtNoSource.INSTANCE;
        }
        String fullPath = basePath + "/" + entry.sourceFile();
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath);
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
        sqlClickPreview.dispose();
    }

    private static final Logger LOG = Logger.getInstance(HistorySqlLogPanel.class);
    private static final String CARD_FILES = "files";
    private static final String CARD_SQL = "sql";
}
