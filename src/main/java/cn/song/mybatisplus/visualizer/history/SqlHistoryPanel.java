package cn.song.mybatisplus.visualizer.history;

import cn.song.mybatisplus.visualizer.log.SlowQueryDetector;
import cn.song.mybatisplus.visualizer.log.SqlClickPreview;
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewDialog;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.JBColor;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * T-Perf-24 死代码标注：本面板**未注册到任何工具窗口/弹窗**（F-7 视图由历史 tab 承接）。
 *
 * <p>SQL 历史记录面板（F-7）：列表渲染（摘要 + 时间戳 + 来源）+ 双击预览 + 右键菜单（收藏/复制/删除/标签）。
 *
 * <p>列表条目按时间倒序（最新在前）；收藏条目左侧显示星形图标。搜索/标签筛选由外层 [SqlHistorySearchBar] 驱动，
 * 本面板暴露 [refresh] 与 [setTagFilter] 供其调用。
 */
public class SqlHistoryPanel extends JPanel implements Disposable {

    private static final String TOOLBAR_ID = "cn.song.mybatisplus.visualizer.history.toolbar";

    private final Project project;
    private final SqlHistoryManager manager = SqlHistoryManager.getInstance();
    private final DefaultListModel<SqlHistoryEntry> listModel = new DefaultListModel<>();
    private final JBList<SqlHistoryEntry> list;
    private String tagFilter = null;
    private boolean favoritesOnly = false;
    private String searchQuery = "";
    private final SqlHistorySearchBar searchBar;
    /** T-ClickPreview：单击出轻量预览弹窗（点击时按行捕获 SQL，锚点在行下缘不遮行），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。 */
    private final SqlClickPreview clickPreview;

    public SqlHistoryPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.list = new JBList<>(listModel);
        this.clickPreview = new SqlClickPreview(project, list, i -> SqlLogPanelKt.withTerminator(listModel.get(i).getSql()));
        this.searchBar = new SqlHistorySearchBar(this::onSearch, this::onFilterChanged);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(buildToolbar(), BorderLayout.NORTH);
        topPanel.add(searchBar, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);
        add(buildListPane(), BorderLayout.CENTER);
        refresh();
    }

    private void onSearch(String query) {
        searchQuery = query;
        refresh();
    }

    private void onFilterChanged() {
        String active = searchBar.getActiveFilter();
        if (SqlHistorySearchBar.FAVORITES_FILTER.equals(active)) {
            favoritesOnly = true;
            tagFilter = null;
        } else if (active == null) {
            favoritesOnly = false;
            tagFilter = null;
        } else {
            favoritesOnly = false;
            tagFilter = active;
        }
        refresh();
    }

    private JPanel buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup(new ClearAction());
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_ID, group, true);
        toolbar.setTargetComponent(this);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        bar.add(toolbar.getComponent());
        return bar;
    }

    private class ClearAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.GC);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.clear"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            int r = Messages.showYesNoDialog(
                    project,
                    "Clear all SQL history?",
                    MpSqlVisualizerBundle.message("history.toolwindow.title"),
                    Messages.getQuestionIcon());
            if (r == Messages.YES) {
                manager.clear();
            }
            refresh();
        }
    }

    private JBScrollPane buildListPane() {
        list.getEmptyText().setText(MpSqlVisualizerBundle.message("history.panel.empty"));
        list.setFixedCellHeight(-1);
        list.setCellRenderer(new HistoryCellRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // T-ClickPreview：先转发（仅左键；单击命中行才排程预览/双击停表并关旧预览），双击再开完整弹窗
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    clickPreview.onClick(e.getClickCount(), e.getPoint());
                }
                if (e.getClickCount() >= 2) {
                    SqlHistoryEntry entry = list.getSelectedValue();
                    if (entry != null) {
                        openPreview(entry);
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

    private class HistoryCellRenderer extends DefaultListCellRenderer {
        private final Icon favoriteIcon = AllIcons.Nodes.Favorite;
        private final Icon emptyIcon = AllIcons.Nodes.EmptyNode;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SqlHistoryEntry entry) {
                String ts = new SimpleDateFormat("HH:mm:ss").format(new Date(entry.getTimestamp()));
                String summary = SqlLogPanelKt.withTerminator(entry.summary());
                String source = entry.sourceLabel();
                String durationColor;
                SlowQueryDetector.DurationLevel level = SlowQueryDetector.level(entry.getExecutionTimeMs());
                if (level == SlowQueryDetector.DurationLevel.SLOW) {
                    durationColor = "#D32F2F";
                } else if (level == SlowQueryDetector.DurationLevel.MODERATE) {
                    durationColor = "#E67E22";
                } else if (level == SlowQueryDetector.DurationLevel.FAST) {
                    durationColor = "#4CAF50";
                } else {
                    durationColor = "#9E9E9E";
                }
                String durationText = entry.getExecutionTimeMs() != null ? (entry.getExecutionTimeMs() + "ms") : "—";
                String durationPart = "  <span style='color:" + durationColor + "'>" + durationText + "</span>";
                String sourcePart = source.isBlank() ? "" : "  <span style='color:#9E9E9E'>(" + source + ")</span>";
                String tagPart = !entry.getTags().isEmpty()
                        ? "  <span style='color:#9E9E9E'>[" + String.join(", ", entry.getTags()) + "]</span>" : "";
                this.setText("<html>" + (entry.isFavorite() ? "&#9733; " : "") + "<b>" + escapeHtml(summary) + "</b>"
                        + durationPart
                        + "  <span style='color:#9E9E9E'>" + ts + "</span>" + sourcePart + tagPart + "</html>");
                // T-ClickPreview：不再设 toolTipText（悬停逐行弹气泡 = 条目多时卡顿根因），预览改单击触发
                this.setIcon(entry.isFavorite() ? favoriteIcon : emptyIcon);
            }
            this.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                    new EmptyBorder(5, 8, 5, 8)));
            return this;
        }

        private String escapeHtml(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private void openPreview(SqlHistoryEntry entry) {
        PreviewResult result = new PreviewResult(
                entry.getSql(),
                entry.getCompactSql(),
                List.of(),
                null,
                null,
                entry.getTableName().isBlank() ? null : entry.getTableName(),
                null,
                List.of(),
                java.util.Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                java.util.Map.of(),
                null,
                null,
                null,
                null);
        SqlPreviewService.showResult(project, result, null);
    }

    private void showContextMenu(MouseEvent e) {
        int index = list.locationToIndex(e.getPoint());
        if (index >= 0) {
            list.setSelectedIndex(index);
        }
        SqlHistoryEntry entry = list.getSelectedValue();
        if (entry == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();

        if (entry.isFavorite()) {
            JMenuItem unfavorite = new JMenuItem(MpSqlVisualizerBundle.message("history.menu.unfavorite"));
            unfavorite.addActionListener(ev -> {
                manager.toggleFavorite(entry.getId());
                refresh();
            });
            menu.add(unfavorite);
        } else {
            JMenuItem favorite = new JMenuItem(MpSqlVisualizerBundle.message("history.menu.favorite"));
            favorite.addActionListener(ev -> {
                manager.toggleFavorite(entry.getId());
                refresh();
            });
            menu.add(favorite);
        }

        JMenuItem copy = new JMenuItem(MpSqlVisualizerBundle.message("history.menu.copy"));
        copy.addActionListener(ev -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(entry.getSql()), null));
        menu.add(copy);

        JMenuItem addTag = new JMenuItem(MpSqlVisualizerBundle.message("history.menu.addTag"));
        addTag.addActionListener(ev -> {
            String tag = Messages.showInputDialog(
                    project,
                    MpSqlVisualizerBundle.message("history.tag.dialog.message"),
                    MpSqlVisualizerBundle.message("history.tag.dialog.title"),
                    Messages.getQuestionIcon());
            if (tag != null && !tag.isBlank()) {
                manager.addTag(entry.getId(), tag.trim());
                refresh();
            }
        });
        menu.add(addTag);

        if (!entry.getTags().isEmpty()) {
            JMenu tagMenu = new JMenu(MpSqlVisualizerBundle.message("history.menu.removeTag"));
            for (String tag : entry.getTags()) {
                JMenuItem removeTag = new JMenuItem(tag);
                removeTag.addActionListener(ev -> {
                    manager.removeTag(entry.getId(), tag);
                    refresh();
                });
                tagMenu.add(removeTag);
            }
            menu.add(tagMenu);
        }

        menu.addSeparator();

        JMenuItem delete = new JMenuItem(MpSqlVisualizerBundle.message("history.menu.delete"));
        delete.addActionListener(ev -> {
            manager.remove(entry.getId());
            refresh();
        });
        menu.add(delete);

        menu.show(list, e.getX(), e.getY());
    }

    /**
     * 刷新列表（从管理器重新拉取，应用当前筛选条件 + 搜索词）。
     */
    public void refresh() {
        List<SqlHistoryEntry> base;
        if (favoritesOnly) {
            base = manager.filterFavorites();
        } else if (tagFilter != null) {
            base = manager.filterByTag(tagFilter);
        } else {
            base = manager.getEntries();
        }
        final List<SqlHistoryEntry> filtered;
        if (searchQuery.isBlank()) {
            filtered = base;
        } else {
            String q = searchQuery.toLowerCase(java.util.Locale.ROOT);
            List<SqlHistoryEntry> f = new ArrayList<>();
            for (SqlHistoryEntry it : base) {
                if (it.getSql().toLowerCase(java.util.Locale.ROOT).contains(q)
                        || it.getCompactSql().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    f.add(it);
                }
            }
            filtered = f;
        }
        searchBar.refreshTags(manager.allTags());
        ApplicationManager.getApplication().invokeLater(() -> {
            listModel.clear();
            for (SqlHistoryEntry it : filtered) {
                listModel.addElement(it);
            }
        });
    }

    /** 设置标签筛选（null = 显示全部）。 */
    public void setTagFilter(String tag) {
        tagFilter = tag;
        refresh();
    }

    /** 设置仅显示收藏。 */
    public void setFavoritesOnly(boolean enabled) {
        favoritesOnly = enabled;
        refresh();
    }

    @Override
    public void dispose() {
        clickPreview.dispose();
    }

    // ---------- 测试访问器 ----------

    /** 当前列表模型条目数（测试断言）。 */
    int getVisibleEntryCount() {
        return listModel.size();
    }

    /** 当前列表模型条目 SQL 列表（测试断言）。 */
    List<String> getVisibleEntries() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            result.add(listModel.getElementAt(i).getSql());
        }
        return result;
    }

    /** 当前标签筛选（测试断言）。 */
    String getCurrentTagFilter() {
        return tagFilter;
    }

    /** 当前是否仅显示收藏（测试断言）。 */
    boolean isFavoritesOnly() {
        return favoritesOnly;
    }

    /** 单击预览控制器（T-ClickPreview 测试断言）。 */
    public SqlClickPreview getClickPreviewForTest() {
        return clickPreview;
    }

    /** 设置搜索词（测试用；等价于在搜索框输入）。 */
    void setSearchQueryForTest(String query) {
        searchQuery = query;
        refresh();
    }
}
