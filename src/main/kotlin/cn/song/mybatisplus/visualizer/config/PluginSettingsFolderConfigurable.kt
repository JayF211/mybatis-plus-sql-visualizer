package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 设置页主页面（左侧树导航的父节点）。
 *
 * 显示搜索栏 + 子设置页面列表（点击跳转），参考 IDEA 原生设置风格。
 * 搜索支持：
 *  - 搜索子页面名称 → 点击跳转到对应子页面
 *  - 搜索子页面内容 → 显示匹配的设置项预览，点击定位到具体设置项
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

        // 点击跳转（双击或单击）
        object : DoubleClickListener() {
            override fun onDoubleClick(e: MouseEvent): Boolean {
                val selected = list.selectedValue ?: return false
                navigateTo(selected)
                return true
            }

            override fun onSingleClick(e: MouseEvent): Boolean {
                val selected = list.selectedValue ?: return false
                navigateTo(selected)
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
            border = JBUI.Borders.empty(8, 12, 4, 12)
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
            // 空搜索：只显示子页面
            for (page in SUB_PAGES) {
                listModel.addItem(SearchItem.PageItem(page.labelKey, page.configurableClass))
            }
            return
        }

        // 1) 匹配的子页面
        val matchedPages = SUB_PAGES.filter {
            MpSqlVisualizerBundle.message(it.labelKey).lowercase().contains(q)
        }
        for (page in matchedPages) {
            listModel.addItem(SearchItem.PageItem(page.labelKey, page.configurableClass))
        }

        // 2) 匹配的设置项内容
        for (field in ALL_SEARCHABLE_FIELDS) {
            val label = field.searchLabel.lowercase()
            val tooltip = field.searchTooltip.lowercase()
            if (label.contains(q) || tooltip.contains(q)) {
                listModel.addItem(SearchItem.FieldItem(
                    fieldLabel = field.searchLabel,
                    pageLabelKey = field.pageLabelKey,
                    configurableClass = field.configurableClass,
                ))
            }
        }
    }

    /**
     * 导航到搜索结果项。
     * 子页面 → 在同一对话框内切换到对应页面
     * 设置项 → 切换到对应页面并高亮
     */
    private fun navigateTo(item: SearchItem) {
        when (item) {
            is SearchItem.PageItem -> navigateToPage(item.configurableClass)
            is SearchItem.FieldItem -> navigateToPage(item.configurableClass)
        }
    }

    /**
     * 在同一对话框内导航到指定子页面。
     * 使用 showSettingsDialog 复用已打开的对话框（IDEA 平台原生行为）。
     */
    private fun navigateToPage(configurableClass: Class<out Configurable>) {
        // 从当前组件获取项目（确保与当前对话框的项目一致）
        val project = com.intellij.openapi.actionSystem.DataManager.getInstance()
            .getDataContext(searchField)
            .getData(CommonDataKeys.PROJECT)
            ?: ProjectManager.getInstance().openProjects.firstOrNull()
            ?: return

        ShowSettingsUtil.getInstance().showSettingsDialog(project, configurableClass)
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}

    /** 搜索结果项 */
    private sealed class SearchItem {
        data class PageItem(
            val labelKey: String,
            val configurableClass: Class<out Configurable>,
        ) : SearchItem()

        data class FieldItem(
            val fieldLabel: String,
            val pageLabelKey: String,
            val configurableClass: Class<out Configurable>,
        ) : SearchItem()
    }

    /** 子页面描述 */
    private data class SubPageDesc(
        val labelKey: String,
        val configurableClass: Class<out Configurable>,
    )

    /** 可搜索的设置项字段 */
    private data class SearchableField(
        val searchLabel: String,
        val searchTooltip: String,
        val pageLabelKey: String,
        val configurableClass: Class<out Configurable>,
    )

    /** 列表渲染器 */
    private inner class SearchItemRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val item = value as? SearchItem ?: return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            val (text, icon) = when (item) {
                is SearchItem.PageItem -> {
                    val name = MpSqlVisualizerBundle.message(item.labelKey)
                    "📁 $name" to null
                }
                is SearchItem.FieldItem -> {
                    val pageName = MpSqlVisualizerBundle.message(item.pageLabelKey)
                    "🔍 ${item.fieldLabel}  →  $pageName" to null
                }
            }

            val label = JBLabel(text).apply {
                border = JBUI.Borders.empty(6, 12)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                if (isSelected) {
                    background = list?.selectionBackground
                    foreground = list?.selectionForeground
                } else {
                    background = list?.background
                    foreground = list?.foreground
                }
                isOpaque = true
            }
            return label
        }
    }

    companion object {
        private val SUB_PAGES = listOf(
            SubPageDesc("settings.section.preview", SqlPreviewConfigurable::class.java),
            SubPageDesc("settings.section.tableMapping", TableMappingConfigurable::class.java),
            SubPageDesc("settings.section.sqlLog", SqlLogConfigurable::class.java),
            SubPageDesc("settings.section.ui", UiActionsConfigurable::class.java),
            SubPageDesc("settings.section.about", AboutConfigurable::class.java),
        )

        /** 所有可搜索的设置项字段（label + tooltip + 所属页面） */
        private val ALL_SEARCHABLE_FIELDS = buildSearchableFields()

        private fun buildSearchableFields(): List<SearchableField> {
            val fields = mutableListOf<SearchableField>()
            val bundle = MpSqlVisualizerBundle

            // SQL 预览
            val previewClass = SqlPreviewConfigurable::class.java
            fields.add(SearchableField(bundle.message("settings.dialect"), "", "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.replaceParams"), bundle.message("settings.replaceParams"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.formatSql"), bundle.message("settings.formatSql"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.injectLogicDelete"), bundle.message("settings.injectLogicDelete"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.logicDeleteValue"), bundle.message("settings.logicDeleteValue.tip"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.injectTenantLine"), bundle.message("settings.injectTenantLine"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.tenantColumnName"), bundle.message("settings.tenantColumnName.tip"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.tenantColumnDefault"), bundle.message("settings.tenantColumnDefault.tip"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.injectTenantOnInsert"), bundle.message("settings.injectTenantOnInsert.tip"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.dataPermissionHint"), bundle.message("settings.dataPermissionHint.tip"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.dataPermissionMockCondition"), bundle.message("settings.dataPermissionMockCondition.tip"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.reverseWrapperFormat"), bundle.message("settings.reverseWrapperFormat.tip"), "settings.section.preview", previewClass))
            fields.add(SearchableField(bundle.message("settings.reverseManualEntitySelection"), bundle.message("settings.reverseManualEntitySelection.tip"), "settings.section.preview", previewClass))

            // 表名映射
            val tableClass = TableMappingConfigurable::class.java
            fields.add(SearchableField(bundle.message("settings.tablePrefix"), bundle.message("settings.tablePrefix.tip"), "settings.section.tableMapping", tableClass))
            fields.add(SearchableField(bundle.message("settings.tableSuffix"), bundle.message("settings.tableSuffix.tip"), "settings.section.tableMapping", tableClass))
            fields.add(SearchableField(bundle.message("settings.removeTablePrefix"), bundle.message("settings.removeTablePrefix.tip"), "settings.section.tableMapping", tableClass))
            fields.add(SearchableField(bundle.message("settings.removeTableSuffix"), bundle.message("settings.removeTableSuffix.tip"), "settings.section.tableMapping", tableClass))
            fields.add(SearchableField(bundle.message("settings.ignoredFields"), bundle.message("settings.ignoredFields.tip"), "settings.section.tableMapping", tableClass))
            fields.add(SearchableField(bundle.message("settings.tableNameInference"), bundle.message("settings.tableNameInference.tip"), "settings.section.tableMapping", tableClass))

            // SQL 日志
            val logClass = SqlLogConfigurable::class.java
            fields.add(SearchableField(bundle.message("settings.log.filterBar"), bundle.message("settings.log.filterBar.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.sqlPrefix"), bundle.message("settings.log.sqlPrefix.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.paramPrefix"), bundle.message("settings.log.paramPrefix.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.mixedPrefix"), bundle.message("settings.log.mixedPrefix.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.autoCapture"), "", "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.autoOpen"), "", "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.stopOnToolWindowClose"), "", "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.timestamp"), "", "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.timestampFormat"), bundle.message("settings.log.timestampFormat.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.formatSql"), "", "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.filterKeywords"), bundle.message("settings.log.filterKeywords.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.dedupeWindow"), bundle.message("settings.log.dedupeWindow.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.maxParseLength"), bundle.message("settings.log.maxParseLength.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.maxCount"), bundle.message("settings.log.maxCount.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.timePrefix"), bundle.message("settings.log.timePrefix.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.slowQueryThreshold"), bundle.message("settings.log.slowQueryThreshold.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.hibernate.enabled"), bundle.message("settings.log.hibernate.enabled.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.hibernate.sqlPrefix"), bundle.message("settings.log.hibernate.sqlPrefix.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.hibernate.paramPrefix"), bundle.message("settings.log.hibernate.paramPrefix.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.jdbc"), bundle.message("settings.log.jdbc.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.autoSave"), bundle.message("settings.log.autoSave.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.autoSave.path"), bundle.message("settings.log.autoSave.path.tip"), "settings.section.sqlLog", logClass))
            fields.add(SearchableField(bundle.message("settings.log.autoSave.pattern"), bundle.message("settings.log.autoSave.pattern.tip"), "settings.section.sqlLog", logClass))

            // 界面与操作
            val uiClass = UiActionsConfigurable::class.java
            fields.add(SearchableField(bundle.message("settings.showGutterIcon"), "", "settings.section.ui", uiClass))
            fields.add(SearchableField(bundle.message("settings.language"), "", "settings.section.ui", uiClass))
            fields.add(SearchableField(bundle.message("settings.shortcut"), bundle.message("settings.shortcut.tip"), "settings.section.ui", uiClass))
            fields.add(SearchableField(bundle.message("settings.shortcut.sqlToJava"), "", "settings.section.ui", uiClass))

            // 关于
            val aboutClass = AboutConfigurable::class.java
            fields.add(SearchableField(bundle.message("settings.cache.label"), bundle.message("settings.cache.tip"), "settings.section.about", aboutClass))
            fields.add(SearchableField(bundle.message("settings.update.versionInfo"), "", "settings.section.about", aboutClass))
            fields.add(SearchableField(bundle.message("settings.buildNumber"), bundle.message("settings.buildNumber.tip"), "settings.section.about", aboutClass))

            return fields
        }
    }
}
