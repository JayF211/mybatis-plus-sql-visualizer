package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 设置页主页面（左侧树导航的父节点）。
 *
 * 参考 IDEA 原生设置风格：
 *  - 顶部搜索栏
 *  - 子页面列表（图标 + 名称 + 描述）
 *  - 搜索时显示匹配的设置项（名称 + 所属页面 + 当前值预览）
 */
class PluginSettingsFolderConfigurable : Configurable {

    private val searchField = SearchTextField()
    private val listModel = DefaultListModel<SearchItem>()
    private val list = JBList(listModel)

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        rebuildList("")

        list.cellRenderer = SearchItemRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.fixedCellHeight = 56  // 固定行高，匹配 IDEA 风格

        // 点击跳转（双击导航到子页面）
        object : DoubleClickListener() {
            override fun onDoubleClick(e: MouseEvent): Boolean {
                list.selectedValue?.let { navigateTo(it) }
                return true
            }
        }.installOn(list)

        // 搜索过滤
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildList(searchField.text)
            override fun removeUpdate(e: DocumentEvent) = rebuildList(searchField.text)
            override fun changedUpdate(e: DocumentEvent) = rebuildList(searchField.text)
        })

        // 搜索面板
        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 16, 6, 16)
            add(searchField, BorderLayout.CENTER)
        }

        // 列表面板
        val scrollPane = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
        }

        return JPanel(BorderLayout()).apply {
            add(searchPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    /**
     * 重建列表：先显示匹配的子页面，再显示匹配的设置项内容。
     */
    private fun rebuildList(query: String) {
        val q = query.trim().lowercase()
        listModel.removeAllElements()

        if (q.isEmpty()) {
            for (page in SUB_PAGES) {
                listModel.addElement(SearchItem.PageItem(page.labelKey, page.descKey, page.icon, page.configurableClass))
            }
            return
        }

        // 1) 匹配的子页面
        val matchedPages = SUB_PAGES.filter {
            MpSqlVisualizerBundle.message(it.labelKey).lowercase().contains(q)
                || MpSqlVisualizerBundle.message(it.descKey).lowercase().contains(q)
        }
        for (page in matchedPages) {
            listModel.addElement(SearchItem.PageItem(page.labelKey, page.descKey, page.icon, page.configurableClass))
        }

        // 2) 匹配的设置项内容
        for (field in ALL_SEARCHABLE_FIELDS) {
            val label = field.searchLabel.lowercase()
            val tooltip = field.searchTooltip.lowercase()
            if (label.contains(q) || tooltip.contains(q)) {
                listModel.addElement(SearchItem.FieldItem(
                    fieldLabel = field.searchLabel,
                    fieldTooltip = field.searchTooltip,
                    pageLabelKey = field.pageLabelKey,
                    configurableClass = field.configurableClass,
                ))
            }
        }
    }

    private fun navigateTo(item: SearchItem) {
        val focusHint = when (item) {
            is SearchItem.PageItem -> null
            is SearchItem.FieldItem -> item.fieldLabel
        }
        navigateToPage(item.configurableClass, focusHint)
    }

    /**
     * 在同一对话框内导航到指定子页面，并聚焦目标控件。
     *
     * 从宿主 SettingsEditor 取得当前 Settings（同一设置对话框），用 settings.select 切换树选择；
     * 切换完成后聚焦目标控件并闪烁高亮，让用户感知目标位置。取不到 Settings 时才回退打开设置对话框。
     */
    private fun navigateToPage(configurableClass: Class<out Configurable>, focusHint: String? = null) {
        val settings = currentSettings() ?: run {
            // 未在已打开的设置对话框内，回退为打开设置对话框
            val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
            ShowSettingsUtil.getInstance().showSettingsDialog(project, configurableClass)
            return
        }

        val target = settings.find(configurableClass) ?: return
        val callback = settings.select(target)
        if (focusHint != null) {
            // 切换完成后异步聚焦目标控件，确保页面已显示、控件已创建
            callback.doWhenDone { focusTarget(target, focusHint) }
        }
    }

    /**
     * 获取当前已打开设置对话框的 [Settings] 实例。
     *
     * 主设置面板由 SettingsEditor 承载，SettingsEditor 实现 UiDataProvider 并对外提供 Settings.KEY。
     * 优先通过 DataManager 从 searchField 的真实组件 DataContext 取（会沿组件树收集 UiDataProvider 数据）；
     * 失败则沿组件树反射读取 SettingsEditor.settings 字段兜底。
     * SettingsEditor 为 @ApiStatus.Internal，用 Class.forName 反射避免编译期依赖内部 API。
     */
    private fun currentSettings(): Settings? {
        runCatching { Settings.KEY.getData(DataManager.getInstance().getDataContext(searchField)) }
            .getOrNull()
            ?.let { return it }
        // 兜底：沿组件树反射读取 SettingsEditor.settings 字段（反射类名，避免直接引用 internal 类型）
        val editorClass = runCatching { Class.forName("com.intellij.openapi.options.newEditor.SettingsEditor") }.getOrNull() ?: return null
        var comp: Component? = searchField
        while (comp != null) {
            if (editorClass.isInstance(comp)) {
                return runCatching {
                    val field = editorClass.getDeclaredField("settings")
                    field.isAccessible = true
                    field.get(comp) as? Settings
                }.getOrNull()
            }
            comp = comp.parent
        }
        return null
    }

    /**
     * 聚焦目标设置控件：页面切换后在真实面板中查找目标字段，滚动到可见区域并闪烁高亮。
     * 页面切换为异步，延迟到 EDT 调度以确保布局完成。
     */
    private fun focusTarget(target: Configurable, focusHint: String) {
        javax.swing.SwingUtilities.invokeLater {
            javax.swing.SwingUtilities.invokeLater {
                runCatching {
                    // 页面已显示，createComponent() 返回真实面板（SectionConfigurable 已缓存）
                    val root = target.createComponent() ?: return@invokeLater
                    val matched = findComponent(root, focusHint)
                    if (matched != null) {
                        // 滚动到可见区域，让用户感知目标位置
                        (matched as? javax.swing.JComponent)?.scrollRectToVisible(matched.bounds)
                        flashHighlight(matched)
                        root.revalidate(); root.repaint()
                    }
                }.onFailure { /* 忽略聚焦失败，不影响导航 */ }
            }
        }
    }

    /**
     * 递归查找匹配 hint 的控件。
     * 优先匹配输入框/复选框（用户可直接操作），其次匹配标签。
     * @return 匹配的控件，未找到返回 null
     */
    private fun findComponent(container: Container, hint: String): Component? {
        for (comp in container.components) {
            val matched = when {
                comp is javax.swing.text.JTextComponent ->
                    comp.toolTipText?.contains(hint, ignoreCase = true) == true
                comp is javax.swing.AbstractButton ->
                    comp.text.contains(hint, ignoreCase = true)
                comp is JBLabel ->
                    comp.text.contains(hint, ignoreCase = true)
                else -> false
            }
            if (matched) return comp
            if (comp is Container) {
                findComponent(comp, hint)?.let { return it }
            }
        }
        return null
    }

    /**
     * 短暂高亮目标控件：黄底闪烁 + 橙色边框，持续约 1.5 秒。
     */
    private fun flashHighlight(comp: Component) {
        comp.requestFocusInWindow()
        if (comp !is JComponent) return
        val originalBg = comp.background
        val originalOpaque = comp.isOpaque
        val originalBorder = comp.border
        comp.isOpaque = true
        comp.border = javax.swing.BorderFactory.createLineBorder(java.awt.Color(0xFF9C00), 2)
        var count = 0
        val timer = javax.swing.Timer(250) {
            count++
            if (count % 2 == 1) {
                comp.background = java.awt.Color(0xFFE599)
            } else {
                comp.background = originalBg
            }
            comp.repaint()
            if (count >= 6) {
                comp.background = originalBg
                comp.isOpaque = originalOpaque
                comp.border = originalBorder
                (it.source as? javax.swing.Timer)?.stop()
            }
        }
        timer.isRepeats = true
        timer.start()
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}

    /** 搜索结果项 */
    private sealed class SearchItem {
        abstract val configurableClass: Class<out Configurable>

        data class PageItem(
            val labelKey: String,
            val descKey: String,
            val icon: Icon,
            override val configurableClass: Class<out Configurable>,
        ) : SearchItem()

        data class FieldItem(
            val fieldLabel: String,
            val fieldTooltip: String,
            val pageLabelKey: String,
            override val configurableClass: Class<out Configurable>,
        ) : SearchItem()
    }

    /** 子页面描述 */
    private data class SubPageDesc(
        val labelKey: String,
        val descKey: String,
        val icon: Icon,
        val configurableClass: Class<out Configurable>,
    )

    /** 可搜索的设置项字段 */
    private data class SearchableField(
        val searchLabel: String,
        val searchTooltip: String,
        val pageLabelKey: String,
        val configurableClass: Class<out Configurable>,
    )

    /**
     * 列表渲染器 — 参考 IDEA 原生设置风格。
     * 子页面：图标 + 名称（粗体）+ 描述（灰色小字）
     * 设置项：图标 + 名称 + 所属页面路径
     */
    private class SearchItemRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val item = value as? SearchItem
                ?: return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            val bg = if (isSelected) list?.selectionBackground else list?.background
            val fg = if (isSelected) list?.selectionForeground else list?.foreground

            return when (item) {
                is SearchItem.PageItem -> renderPageItem(item, bg, fg, isSelected)
                is SearchItem.FieldItem -> renderFieldItem(item, bg, fg, isSelected)
            }
        }

        private fun renderPageItem(
            item: SearchItem.PageItem,
            bg: java.awt.Color?,
            fg: java.awt.Color?,
            isSelected: Boolean,
        ): Component {
            val panel = JPanel(GridBagLayout()).apply {
                background = bg
                isOpaque = true
                border = EmptyBorder(JBUI.insets(8, 16))
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            // 图标
            val iconLabel = JBLabel(item.icon).apply {
                border = EmptyBorder(JBUI.insetsRight(12))
            }

            // 名称 + 描述
            val name = MpSqlVisualizerBundle.message(item.labelKey)
            val desc = MpSqlVisualizerBundle.message(item.descKey)

            val textPanel = JPanel(GridBagLayout()).apply {
                isOpaque = false
            }

            val nameLabel = JBLabel(name).apply {
                font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
                foreground = fg
            }

            val descLabel = JBLabel(desc).apply {
                font = JBUI.Fonts.smallFont()
                foreground = if (isSelected) fg else UIUtil.getContextHelpForeground()
            }

            textPanel.add(nameLabel, GridBagConstraints().apply {
                gridx = 0; gridy = 0; anchor = GridBagConstraints.WEST; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            })
            textPanel.add(descLabel, GridBagConstraints().apply {
                gridx = 0; gridy = 1; anchor = GridBagConstraints.WEST; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets =
                JBUI.insetsTop(2)
            })

            panel.add(iconLabel, GridBagConstraints().apply {
                gridx = 0; gridy = 0; anchor = GridBagConstraints.NORTHWEST
            })
            panel.add(textPanel, GridBagConstraints().apply {
                gridx = 1; gridy = 0; anchor = GridBagConstraints.WEST; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            })

            return panel
        }

        private fun renderFieldItem(
            item: SearchItem.FieldItem,
            bg: java.awt.Color?,
            fg: java.awt.Color?,
            isSelected: Boolean,
        ): Component {
            val panel = JPanel(GridBagLayout()).apply {
                background = bg
                isOpaque = true
                border = EmptyBorder(JBUI.insets(8, 16))
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            // 图标
            val iconLabel = JBLabel(AllIcons.Actions.Search).apply {
                border = EmptyBorder(JBUI.insetsRight(12))
            }

            // 字段名 + 所属页面
            val pageName = MpSqlVisualizerBundle.message(item.pageLabelKey)

            val textPanel = JPanel(GridBagLayout()).apply {
                isOpaque = false
            }

            val fieldLabel = JBLabel(item.fieldLabel).apply {
                font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
                foreground = fg
            }

            val pathLabel = JBLabel(pageName).apply {
                font = JBUI.Fonts.smallFont()
                foreground = if (isSelected) fg else UIUtil.getContextHelpForeground()
            }

            textPanel.add(fieldLabel, GridBagConstraints().apply {
                gridx = 0; gridy = 0; anchor = GridBagConstraints.WEST; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            })
            textPanel.add(pathLabel, GridBagConstraints().apply {
                gridx = 0; gridy = 1; anchor = GridBagConstraints.WEST; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets =
                JBUI.insetsTop(2)
            })

            panel.add(iconLabel, GridBagConstraints().apply {
                gridx = 0; gridy = 0; anchor = GridBagConstraints.NORTHWEST
            })
            panel.add(textPanel, GridBagConstraints().apply {
                gridx = 1; gridy = 0; anchor = GridBagConstraints.WEST; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            })

            return panel
        }
    }

    companion object {
        private val SUB_PAGES = listOf(
            SubPageDesc("settings.section.preview", "settings.section.preview.desc", AllIcons.Nodes.Class, SqlPreviewConfigurable::class.java),
            SubPageDesc("settings.section.tableMapping", "settings.section.tableMapping.desc", AllIcons.Nodes.DataTables, TableMappingConfigurable::class.java),
            SubPageDesc("settings.section.sqlLog", "settings.section.sqlLog.desc", AllIcons.Nodes.Console, SqlLogConfigurable::class.java),
            SubPageDesc("settings.section.ui", "settings.section.ui.desc", AllIcons.Nodes.Plugin, UiActionsConfigurable::class.java),
            SubPageDesc("settings.section.about", "settings.section.about.desc", AllIcons.Nodes.Favorite, AboutConfigurable::class.java),
        )

        /** 所有可搜索的设置项字段 */
        private val ALL_SEARCHABLE_FIELDS = buildSearchableFields()

        private fun buildSearchableFields(): List<SearchableField> {
            val fields = mutableListOf<SearchableField>()
            val b = MpSqlVisualizerBundle

            // SQL 预览
            val p = SqlPreviewConfigurable::class.java
            fields.add(SearchableField(b.message("settings.dialect"), "", "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.replaceParams"), b.message("settings.replaceParams"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.formatSql"), b.message("settings.formatSql"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.injectLogicDelete"), b.message("settings.injectLogicDelete"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.logicDeleteValue"), b.message("settings.logicDeleteValue.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.injectTenantLine"), b.message("settings.injectTenantLine"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.tenantColumnName"), b.message("settings.tenantColumnName.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.tenantColumnDefault"), b.message("settings.tenantColumnDefault.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.injectTenantOnInsert"), b.message("settings.injectTenantOnInsert.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.dataPermissionHint"), b.message("settings.dataPermissionHint.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.dataPermissionMockCondition"), b.message("settings.dataPermissionMockCondition.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.reverseWrapperFormat"), b.message("settings.reverseWrapperFormat.tip"), "settings.section.preview", p))
            fields.add(SearchableField(b.message("settings.reverseManualEntitySelection"), b.message("settings.reverseManualEntitySelection.tip"), "settings.section.preview", p))

            // 表名映射
            val t = TableMappingConfigurable::class.java
            fields.add(SearchableField(b.message("settings.tablePrefix"), b.message("settings.tablePrefix.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.tableSuffix"), b.message("settings.tableSuffix.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.removeTablePrefix"), b.message("settings.removeTablePrefix.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.removeTableSuffix"), b.message("settings.removeTableSuffix.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.ignoredFields"), b.message("settings.ignoredFields.tip"), "settings.section.tableMapping", t))
            fields.add(SearchableField(b.message("settings.tableNameInference"), b.message("settings.tableNameInference.tip"), "settings.section.tableMapping", t))

            // SQL 日志
            val l = SqlLogConfigurable::class.java
            fields.add(SearchableField(b.message("settings.log.filterBar"), b.message("settings.log.filterBar.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.sqlPrefix"), b.message("settings.log.sqlPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.paramPrefix"), b.message("settings.log.paramPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.mixedPrefix"), b.message("settings.log.mixedPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoCapture"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoOpen"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.stopOnToolWindowClose"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.timestamp"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.timestampFormat"), b.message("settings.log.timestampFormat.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.formatSql"), "", "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.filterKeywords"), b.message("settings.log.filterKeywords.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.dedupeWindow"), b.message("settings.log.dedupeWindow.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.maxParseLength"), b.message("settings.log.maxParseLength.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.maxCount"), b.message("settings.log.maxCount.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.timePrefix"), b.message("settings.log.timePrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.slowQueryThreshold"), b.message("settings.log.slowQueryThreshold.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.hibernate.enabled"), b.message("settings.log.hibernate.enabled.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.hibernate.sqlPrefix"), b.message("settings.log.hibernate.sqlPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.hibernate.paramPrefix"), b.message("settings.log.hibernate.paramPrefix.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.jdbc"), b.message("settings.log.jdbc.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoSave"), b.message("settings.log.autoSave.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoSave.path"), b.message("settings.log.autoSave.path.tip"), "settings.section.sqlLog", l))
            fields.add(SearchableField(b.message("settings.log.autoSave.pattern"), b.message("settings.log.autoSave.pattern.tip"), "settings.section.sqlLog", l))

            // 界面与操作
            val u = UiActionsConfigurable::class.java
            fields.add(SearchableField(b.message("settings.showGutterIcon"), "", "settings.section.ui", u))
            fields.add(SearchableField(b.message("settings.language"), "", "settings.section.ui", u))
            fields.add(SearchableField(b.message("settings.shortcut"), b.message("settings.shortcut.tip"), "settings.section.ui", u))
            fields.add(SearchableField(b.message("settings.shortcut.sqlToJava"), "", "settings.section.ui", u))

            // 关于
            val a = AboutConfigurable::class.java
            fields.add(SearchableField(b.message("settings.cache.label"), b.message("settings.cache.tip"), "settings.section.about", a))
            fields.add(SearchableField(b.message("settings.update.versionInfo"), "", "settings.section.about", a))
            fields.add(SearchableField(b.message("settings.buildNumber"), b.message("settings.buildNumber.tip"), "settings.section.about", a))

            return fields
        }
    }
}
