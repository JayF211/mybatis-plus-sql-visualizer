package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
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
 */
class PluginSettingsFolderConfigurable : Configurable {

    private val searchField = SearchTextField()
    private val listModel = DefaultListModel<SubPageItem>()
    private val list = JBList(listModel)

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        // 初始化子页面列表
        listModel.addElement(SubPageItem("settings.section.preview", SqlPreviewConfigurable::class.java))
        listModel.addElement(SubPageItem("settings.section.tableMapping", TableMappingConfigurable::class.java))
        listModel.addElement(SubPageItem("settings.section.sqlLog", SqlLogConfigurable::class.java))
        listModel.addElement(SubPageItem("settings.section.ui", UiActionsConfigurable::class.java))
        listModel.addElement(SubPageItem("settings.section.about", AboutConfigurable::class.java))

        // 列表渲染器
        list.cellRenderer = SubPageRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // 点击跳转
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 || e.button == MouseEvent.BUTTON1) {
                    val selected = list.selectedValue ?: return
                    navigateTo(selected.configurableClass)
                }
            }
        })

        // 搜索过滤
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterList()
            override fun removeUpdate(e: DocumentEvent) = filterList()
            override fun changedUpdate(e: DocumentEvent) = filterList()
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

    private fun filterList() {
        val query = searchField.text.trim().lowercase()
        listModel.removeAllElements()
        val allItems = listOf(
            SubPageItem("settings.section.preview", SqlPreviewConfigurable::class.java),
            SubPageItem("settings.section.tableMapping", TableMappingConfigurable::class.java),
            SubPageItem("settings.section.sqlLog", SqlLogConfigurable::class.java),
            SubPageItem("settings.section.ui", UiActionsConfigurable::class.java),
            SubPageItem("settings.section.about", AboutConfigurable::class.java),
        )
        for (item in allItems) {
            val label = MpSqlVisualizerBundle.message(item.labelKey).lowercase()
            if (query.isEmpty() || label.contains(query)) {
                listModel.addElement(item)
            }
        }
    }

    private fun navigateTo(configurableClass: Class<out Configurable>) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, configurableClass)
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}

    /** 子页面项（i18n key + 目标 Configurable 类） */
    private data class SubPageItem(
        val labelKey: String,
        val configurableClass: Class<out Configurable>,
    ) {
        val displayName: String get() = MpSqlVisualizerBundle.message(labelKey)
    }

    /** 列表渲染器：显示页面名称 */
    private inner class SubPageRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val item = value as? SubPageItem ?: return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val label = JBLabel(item.displayName).apply {
                border = JBUI.Borders.empty(8, 12)
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
}
