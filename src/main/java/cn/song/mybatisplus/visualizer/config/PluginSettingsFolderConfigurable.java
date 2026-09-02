package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.options.ex.Settings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import org.jetbrains.annotations.NotNull;

/**
 * 设置页主页面（左侧树导航的父节点）。
 *
 * 参考 IDEA 原生设置风格：
 *  - 顶部搜索栏
 *  - 子页面列表（图标 + 名称 + 描述）
 *  - 搜索时显示匹配的设置项（名称 + 所属页面 + 当前值预览）
 */
public class PluginSettingsFolderConfigurable implements Configurable {

    private final SearchTextField searchField = new SearchTextField();
    private final DefaultListModel<SearchItem> listModel = new DefaultListModel<>();
    private final JBList<SearchItem> list = new JBList<>(listModel);

    @Override
    public String getDisplayName() {
        return MpSqlVisualizerBundle.message("plugin.name");
    }

    @NotNull
    @Override
    public JComponent createComponent() {
        rebuildList("");

        list.setCellRenderer(new SearchItemRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(56); // 固定行高，匹配 IDEA 风格

        // 点击跳转（双击导航到子页面）
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(MouseEvent e) {
                SearchItem selected = list.getSelectedValue();
                if (selected != null) navigateTo(selected);
                return true;
            }
        }.installOn(list);

        // 搜索过滤
        searchField.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuildList(searchField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuildList(searchField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                rebuildList(searchField.getText());
            }
        });

        // 搜索面板
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(JBUI.Borders.empty(10, 16, 6, 16));
        searchPanel.add(searchField, BorderLayout.CENTER);

        // 列表面板
        JBScrollPane scrollPane = new JBScrollPane(list);
        scrollPane.setBorder(JBUI.Borders.empty());

        JPanel root = new JPanel(new BorderLayout());
        root.add(searchPanel, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);
        return root;
    }

    /**
     * 重建列表：先显示匹配的子页面，再显示匹配的设置项内容。
     */
    private void rebuildList(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        listModel.removeAllElements();

        if (q.isEmpty()) {
            for (SubPageDesc page : SUB_PAGES) {
                listModel.addElement(new SearchItem.PageItem(page.labelKey, page.descKey, page.icon, page.configurableClass));
            }
            return;
        }

        // 1) 匹配的子页面
        for (SubPageDesc page : SUB_PAGES) {
            if (MpSqlVisualizerBundle.message(page.labelKey).toLowerCase(Locale.ROOT).contains(q)
                || MpSqlVisualizerBundle.message(page.descKey).toLowerCase(Locale.ROOT).contains(q)) {
                listModel.addElement(new SearchItem.PageItem(page.labelKey, page.descKey, page.icon, page.configurableClass));
            }
        }

        // 2) 匹配的设置项内容
        for (SearchableField field : ALL_SEARCHABLE_FIELDS) {
            String label = field.searchLabel.toLowerCase(Locale.ROOT);
            String tooltip = field.searchTooltip.toLowerCase(Locale.ROOT);
            if (label.contains(q) || tooltip.contains(q)) {
                listModel.addElement(new SearchItem.FieldItem(
                    field.searchLabel,
                    field.searchTooltip,
                    field.pageLabelKey,
                    field.configurableClass));
            }
        }
    }

    private void navigateTo(SearchItem item) {
        String focusHint = item instanceof SearchItem.FieldItem
            ? ((SearchItem.FieldItem) item).fieldLabel
            : null;
        navigateToPage(item.configurableClass, focusHint);
    }

    /**
     * 在同一对话框内导航到指定子页面，并聚焦目标控件。
     *
     * 从宿主 SettingsEditor 取得当前 Settings（同一设置对话框），用 settings.select 切换树选择；
     * 切换完成后聚焦目标控件并闪烁高亮，让用户感知目标位置。取不到 Settings 时才回退打开设置对话框。
     */
    private void navigateToPage(Class<? extends Configurable> configurableClass, String focusHint) {
        Settings settings = currentSettings();
        if (settings == null) {
            // 未在已打开的设置对话框内，回退为打开设置对话框
            Project[] projects = ProjectManager.getInstance().getOpenProjects();
            if (projects.length == 0) return;
            ShowSettingsUtil.getInstance().showSettingsDialog(projects[0], configurableClass);
            return;
        }

        Configurable target = settings.find(configurableClass);
        if (target == null) return;
        settings.select(target).doWhenDone(() -> {
            if (focusHint != null) {
                focusTarget(target, focusHint);
            }
        });
    }

    /**
     * 获取当前已打开设置对话框的 {@link Settings} 实例。
     *
     * 主设置面板由 SettingsEditor 承载，SettingsEditor 实现 UiDataProvider 并对外提供 Settings.KEY。
     * 优先通过 DataManager 从 searchField 的真实组件 DataContext 取（会沿组件树收集 UiDataProvider 数据）；
     * 失败则沿组件树反射读取 SettingsEditor.settings 字段兜底。
     * SettingsEditor 为 @ApiStatus.Internal，用 Class.forName 反射避免编译期依赖内部 API。
     */
    private Settings currentSettings() {
        try {
            Settings fromData = Settings.KEY.getData(DataManager.getInstance().getDataContext(searchField));
            if (fromData != null) return fromData;
        } catch (Throwable ignored) {
            // 忽略，走反射兜底
        }

        // 兜底：沿组件树反射读取 SettingsEditor.settings 字段（反射类名，避免直接引用 internal 类型）
        Class<?> editorClass;
        try {
            editorClass = Class.forName("com.intellij.openapi.options.newEditor.SettingsEditor");
        } catch (Throwable t) {
            return null;
        }

        Component comp = searchField;
        while (comp != null) {
            if (editorClass.isInstance(comp)) {
                try {
                    java.lang.reflect.Field field = editorClass.getDeclaredField("settings");
                    field.setAccessible(true);
                    Object value = field.get(comp);
                    if (value instanceof Settings) return (Settings) value;
                } catch (Throwable ignored) {
                    // 忽略，返回 null
                }
                return null;
            }
            comp = comp.getParent();
        }
        return null;
    }

    /**
     * 聚焦目标设置控件：页面切换后在真实面板中查找目标字段，滚动到可见区域并闪烁高亮。
     * 页面切换为异步，延迟到 EDT 调度以确保布局完成。
     */
    private static void focusTarget(Configurable target, String focusHint) {
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            try {
                // 页面已显示，createComponent() 返回真实面板（SectionConfigurable 已缓存）
                JComponent root = target.createComponent();
                if (root == null) return;
                Component matched = findComponent(root, focusHint);
                if (matched != null) {
                    // 滚动到可见区域，让用户感知目标位置
                    if (matched instanceof JComponent) {
                        ((JComponent) matched).scrollRectToVisible(matched.getBounds());
                    }
                    flashHighlight(matched);
                    root.revalidate();
                    root.repaint();
                }
            } catch (Throwable ignored) {
                // 忽略聚焦失败，不影响导航
            }
        }));
    }

    /**
     * 递归查找匹配 hint 的控件。
     * 优先匹配输入框/复选框（用户可直接操作），其次匹配标签。
     *
     * @return 匹配的控件，未找到返回 null
     */
    private static Component findComponent(Container container, String hint) {
        for (Component comp : container.getComponents()) {
            boolean matched;
            if (comp instanceof JTextComponent) {
                String tip = ((JTextComponent) comp).getToolTipText();
                matched = tip != null && tip.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT));
            } else if (comp instanceof AbstractButton) {
                String text = ((AbstractButton) comp).getText();
                matched = text != null && text.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT));
            } else if (comp instanceof JBLabel) {
                String text = ((JBLabel) comp).getText();
                matched = text != null && text.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT));
            } else {
                matched = false;
            }
            if (matched) return comp;
            if (comp instanceof Container) {
                Component deeper = findComponent((Container) comp, hint);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }

    /**
     * 短暂高亮目标控件：黄底闪烁 + 橙色边框，持续约 1.5 秒。
     */
    private static void flashHighlight(Component target) {
        target.requestFocusInWindow();
        if (!(target instanceof JComponent)) return;

        JComponent comp = (JComponent) target;
        Color originalBg = comp.getBackground();
        boolean originalOpaque = comp.isOpaque();
        Border originalBorder = comp.getBorder();

        final Color flash = new JBColor(0xFFFAE6, 0x3A3320);
        comp.setOpaque(true);
        comp.setBorder(BorderFactory.createLineBorder(new JBColor(0xE6C068, 0x6B5B2E), 2));

        int[] count = {0};
        Timer timer = new Timer(250, ev -> {
            count[0]++;
            if (count[0] % 2 == 1) {
                comp.setBackground(flash);
            } else {
                comp.setBackground(originalBg);
            }
            comp.repaint();
            if (count[0] >= 6) {
                comp.setBackground(originalBg);
                comp.setOpaque(originalOpaque);
                comp.setBorder(originalBorder);
                ((Timer) ev.getSource()).stop();
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
    }

    @Override
    public void reset() {
    }

    /** 搜索结果项 */
    private abstract static class SearchItem {

        final Class<? extends Configurable> configurableClass;

        SearchItem(Class<? extends Configurable> configurableClass) {
            this.configurableClass = configurableClass;
        }

        static final class PageItem extends SearchItem {

            final String labelKey;
            final String descKey;
            final Icon icon;

            PageItem(String labelKey, String descKey, Icon icon, Class<? extends Configurable> configurableClass) {
                super(configurableClass);
                this.labelKey = labelKey;
                this.descKey = descKey;
                this.icon = icon;
            }
        }

        static final class FieldItem extends SearchItem {

            final String fieldLabel;
            final String fieldTooltip;
            final String pageLabelKey;

            FieldItem(String fieldLabel,
                      String fieldTooltip,
                      String pageLabelKey,
                      Class<? extends Configurable> configurableClass) {
                super(configurableClass);
                this.fieldLabel = fieldLabel;
                this.fieldTooltip = fieldTooltip;
                this.pageLabelKey = pageLabelKey;
            }
        }
    }

    /** 子页面描述 */
    private static final class SubPageDesc {

        final String labelKey;
        final String descKey;
        final Icon icon;
        final Class<? extends Configurable> configurableClass;

        SubPageDesc(String labelKey, String descKey, Icon icon, Class<? extends Configurable> configurableClass) {
            this.labelKey = labelKey;
            this.descKey = descKey;
            this.icon = icon;
            this.configurableClass = configurableClass;
        }
    }

    /** 可搜索的设置项字段 */
    private static final class SearchableField {

        final String searchLabel;
        final String searchTooltip;
        final String pageLabelKey;
        final Class<? extends Configurable> configurableClass;

        SearchableField(String searchLabel,
                        String searchTooltip,
                        String pageLabelKey,
                        Class<? extends Configurable> configurableClass) {
            this.searchLabel = searchLabel;
            this.searchTooltip = searchTooltip;
            this.pageLabelKey = pageLabelKey;
            this.configurableClass = configurableClass;
        }
    }

    /**
     * 列表渲染器 — 参考 IDEA 原生设置风格。
     * 子页面：图标 + 名称（粗体）+ 描述（灰色小字）
     * 设置项：图标 + 名称 + 所属页面路径
     */
    private static class SearchItemRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list,
                                                      Object value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            if (!(value instanceof SearchItem)) {
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
            SearchItem item = (SearchItem) value;

            Color bg = isSelected
                ? (list != null ? list.getSelectionBackground() : null)
                : (list != null ? list.getBackground() : null);
            Color fg = isSelected
                ? (list != null ? list.getSelectionForeground() : null)
                : (list != null ? list.getForeground() : null);

            if (item instanceof SearchItem.PageItem) {
                return renderPageItem((SearchItem.PageItem) item, bg, fg, isSelected);
            }
            return renderFieldItem((SearchItem.FieldItem) item, bg, fg, isSelected);
        }

        private static Component renderPageItem(SearchItem.PageItem item, Color bg, Color fg, boolean isSelected) {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(bg);
            panel.setOpaque(true);
            panel.setBorder(new EmptyBorder(JBUI.insets(8, 16)));
            panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            // 图标
            JBLabel iconLabel = new JBLabel(item.icon);
            iconLabel.setBorder(new EmptyBorder(JBUI.insetsRight(12)));

            // 名称 + 描述
            String name = MpSqlVisualizerBundle.message(item.labelKey);
            String desc = MpSqlVisualizerBundle.message(item.descKey);

            JPanel textPanel = new JPanel(new GridBagLayout());
            textPanel.setOpaque(false);

            JBLabel nameLabel = new JBLabel(name);
            nameLabel.setFont(JBUI.Fonts.label().deriveFont(Font.BOLD));
            nameLabel.setForeground(fg);

            JBLabel descLabel = new JBLabel(desc);
            descLabel.setFont(JBUI.Fonts.smallFont());
            descLabel.setForeground(isSelected ? fg : UIUtil.getContextHelpForeground());

            textPanel.add(nameLabel, new GridBagConstraints(
                0, 0, 1, 1, 1.0, 0.0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, JBUI.emptyInsets(), 0, 0));
            textPanel.add(descLabel, new GridBagConstraints(
                0, 1, 1, 1, 1.0, 0.0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, JBUI.insetsTop(2), 0, 0));

            panel.add(iconLabel, new GridBagConstraints(
                0, 0, 1, 1, 0.0, 0.0,
                GridBagConstraints.NORTHWEST, GridBagConstraints.NONE, JBUI.emptyInsets(), 0, 0));
            panel.add(textPanel, new GridBagConstraints(
                1, 0, 1, 1, 1.0, 0.0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, JBUI.emptyInsets(), 0, 0));

            return panel;
        }

        private static Component renderFieldItem(SearchItem.FieldItem item, Color bg, Color fg, boolean isSelected) {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(bg);
            panel.setOpaque(true);
            panel.setBorder(new EmptyBorder(JBUI.insets(8, 16)));
            panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            // 图标
            JBLabel iconLabel = new JBLabel(AllIcons.Actions.Search);
            iconLabel.setBorder(new EmptyBorder(JBUI.insetsRight(12)));

            // 字段名 + 所属页面
            String pageName = MpSqlVisualizerBundle.message(item.pageLabelKey);

            JPanel textPanel = new JPanel(new GridBagLayout());
            textPanel.setOpaque(false);

            JBLabel fieldLabel = new JBLabel(item.fieldLabel);
            fieldLabel.setFont(JBUI.Fonts.label().deriveFont(Font.BOLD));
            fieldLabel.setForeground(fg);

            JBLabel pathLabel = new JBLabel(pageName);
            pathLabel.setFont(JBUI.Fonts.smallFont());
            pathLabel.setForeground(isSelected ? fg : UIUtil.getContextHelpForeground());

            textPanel.add(fieldLabel, new GridBagConstraints(
                0, 0, 1, 1, 1.0, 0.0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, JBUI.emptyInsets(), 0, 0));
            textPanel.add(pathLabel, new GridBagConstraints(
                0, 1, 1, 1, 1.0, 0.0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, JBUI.insetsTop(2), 0, 0));

            panel.add(iconLabel, new GridBagConstraints(
                0, 0, 1, 1, 0.0, 0.0,
                GridBagConstraints.NORTHWEST, GridBagConstraints.NONE, JBUI.emptyInsets(), 0, 0));
            panel.add(textPanel, new GridBagConstraints(
                1, 0, 1, 1, 1.0, 0.0,
                GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, JBUI.emptyInsets(), 0, 0));

            return panel;
        }
    }

    private static final List<SubPageDesc> SUB_PAGES = List.of(
        new SubPageDesc("settings.section.ui", "settings.section.ui.desc", AllIcons.Nodes.Plugin, UiActionsConfigurable.class),
        new SubPageDesc("settings.section.preview", "settings.section.preview.desc", AllIcons.Nodes.Class, SqlPreviewConfigurable.class),
        new SubPageDesc("settings.section.tableMapping", "settings.section.tableMapping.desc", AllIcons.Nodes.DataTables, TableMappingConfigurable.class),
        new SubPageDesc("settings.section.sqlLog", "settings.section.sqlLog.desc", AllIcons.Nodes.Console, SqlLogConfigurable.class),
        new SubPageDesc("settings.section.about", "settings.section.about.desc", AllIcons.Nodes.Favorite, AboutConfigurable.class));

    /** 所有可搜索的设置项字段 */
    private static final List<SearchableField> ALL_SEARCHABLE_FIELDS = buildSearchableFields();

    private static List<SearchableField> buildSearchableFields() {
        List<SearchableField> fields = new java.util.ArrayList<>();

        // SQL 预览
        Class<? extends Configurable> p = SqlPreviewConfigurable.class;
        fields.add(new SearchableField(msg("settings.dialect"), "", "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.replaceParams"), msg("settings.replaceParams"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.formatSql"), msg("settings.formatSql"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.injectLogicDelete"), msg("settings.injectLogicDelete"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.logicDeleteValue"), msg("settings.logicDeleteValue.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.injectTenantLine"), msg("settings.injectTenantLine"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.tenantColumnName"), msg("settings.tenantColumnName.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.tenantColumnDefault"), msg("settings.tenantColumnDefault.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.injectTenantOnInsert"), msg("settings.injectTenantOnInsert.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.dataPermissionHint"), msg("settings.dataPermissionHint.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.dataPermissionMockCondition"), msg("settings.dataPermissionMockCondition.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.reverseWrapperFormat"), msg("settings.reverseWrapperFormat.tip"), "settings.section.preview", p));
        fields.add(new SearchableField(msg("settings.reverseManualEntitySelection"), msg("settings.reverseManualEntitySelection.tip"), "settings.section.preview", p));

        // 表名映射
        Class<? extends Configurable> t = TableMappingConfigurable.class;
        fields.add(new SearchableField(msg("settings.tablePrefix"), msg("settings.tablePrefix.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.tableSuffix"), msg("settings.tableSuffix.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.removeTablePrefix"), msg("settings.removeTablePrefix.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.removeTableSuffix"), msg("settings.removeTableSuffix.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.ignoredFields"), msg("settings.ignoredFields.tip"), "settings.section.tableMapping", t));
        fields.add(new SearchableField(msg("settings.tableNameInference"), msg("settings.tableNameInference.tip"), "settings.section.tableMapping", t));

        // SQL 日志
        Class<? extends Configurable> l = SqlLogConfigurable.class;
        fields.add(new SearchableField(msg("settings.log.filterBar"), msg("settings.log.filterBar.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.sqlPrefix"), msg("settings.log.sqlPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.paramPrefix"), msg("settings.log.paramPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.mixedPrefix"), msg("settings.log.mixedPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoCapture"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoOpen"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.stopOnToolWindowClose"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.timestamp"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.timestampFormat"), msg("settings.log.timestampFormat.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.formatSql"), "", "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.filterKeywords"), msg("settings.log.filterKeywords.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.dedupeWindow"), msg("settings.log.dedupeWindow.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.maxParseLength"), msg("settings.log.maxParseLength.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.maxCount"), msg("settings.log.maxCount.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.timePrefix"), msg("settings.log.timePrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.slowQueryThreshold"), msg("settings.log.slowQueryThreshold.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.hibernate.enabled"), msg("settings.log.hibernate.enabled.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.hibernate.sqlPrefix"), msg("settings.log.hibernate.sqlPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.hibernate.paramPrefix"), msg("settings.log.hibernate.paramPrefix.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.jdbc"), msg("settings.log.jdbc.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoSave"), msg("settings.log.autoSave.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoSave.path"), msg("settings.log.autoSave.path.tip"), "settings.section.sqlLog", l));
        fields.add(new SearchableField(msg("settings.log.autoSave.pattern"), msg("settings.log.autoSave.pattern.tip"), "settings.section.sqlLog", l));

        // 界面与操作
        Class<? extends Configurable> u = UiActionsConfigurable.class;
        fields.add(new SearchableField(msg("settings.showGutterIcon"), "", "settings.section.ui", u));
        fields.add(new SearchableField(msg("settings.language"), "", "settings.section.ui", u));
        fields.add(new SearchableField(msg("settings.shortcut"), msg("settings.shortcut.tip"), "settings.section.ui", u));
        fields.add(new SearchableField(msg("settings.shortcut.sqlToJava"), "", "settings.section.ui", u));

        // 关于
        Class<? extends Configurable> a = AboutConfigurable.class;
        fields.add(new SearchableField(msg("settings.cache.label"), msg("settings.cache.tip"), "settings.section.about", a));
        fields.add(new SearchableField(msg("settings.update.versionInfo"), "", "settings.section.about", a));
        fields.add(new SearchableField(msg("settings.buildNumber"), msg("settings.buildNumber.tip"), "settings.section.about", a));

        return fields;
    }

    private static String msg(String key) {
        return MpSqlVisualizerBundle.message(key);
    }
}
