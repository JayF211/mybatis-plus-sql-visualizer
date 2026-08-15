package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettingsConfigurable
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/**
 * MyBatis SQL 日志工具窗口（T-M31-04）：双标签页（SQL 日志列表 / SQL 预览），
 * 全部使用 IDEA 原生组件（[JBTabbedPane] 主题化标签页 + ActionToolbar 扁平图标工具栏 + tooltip，
 * T-M31.6 用户要求 UI 与 IDE 原生风格统一）；双击列表项打开现有预览弹窗
 * （高亮/格式化/复制/执行全复用），右键菜单复制/删除单条。
 */
class SqlLogToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setTitle(MpSqlVisualizerBundle.message("log.toolwindow.title"))
        val manager = project.getService(SqlLogManager::class.java)
        val panel = SqlLogPanel(project, manager)
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** 工具窗口面板（标签页 + 工具栏；headless 测试不布局，交互断言走条目模型层）。 */
class SqlLogPanel(
    private val project: Project,
    private val manager: SqlLogManager,
) : JPanel(BorderLayout()), Disposable {

    private val listModel = DefaultListModel<LogSqlEntry>()
    private val list = JBList(listModel)
    private val tabs = JBTabbedPane()
    private val previewEditor: Editor = createPreviewEditor()
    private val dedupeCheckBox = JCheckBox(MpSqlVisualizerBundle.message("log.panel.dedupe")).apply {
        isSelected = manager.entries.dedupeEnabled
        addActionListener { manager.entries.dedupeEnabled = isSelected }
    }

    init {
        // 标签 1：SQL 日志（工具栏 + 条目列表）
        val logPanel = JPanel(BorderLayout())
        logPanel.add(buildToolbar(), BorderLayout.NORTH)
        logPanel.add(buildListPane(), BorderLayout.CENTER)

        // 标签 2：SQL 预览（选中条目的还原 SQL，只读高亮）
        val previewPanel = JPanel(BorderLayout())
        previewPanel.add(previewEditor.component, BorderLayout.CENTER)

        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logs"), logPanel)
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.preview"), previewPanel)
        add(tabs, BorderLayout.CENTER)

        manager.addUiListener { refreshList() }
        refreshList()
    }

    // ---------- 工具栏（IDEA 原生 ActionToolbar：扁平图标按钮 + tooltip） ----------

    private fun buildToolbar(): JPanel {
        val group = DefaultActionGroup(
            CaptureAction(),
            PauseAction(),
            ClearAction(),
            SettingsAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, group, true)
            .apply { targetComponent = this@SqlLogPanel }

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        bar.add(toolbar.component)
        bar.add(dedupeCheckBox)
        return bar
    }

    private inner class CaptureAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = if (manager.capturing) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
            e.presentation.text = MpSqlVisualizerBundle.message(
                if (manager.capturing) "log.panel.stop" else "log.panel.capture",
            )
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (manager.capturing) manager.stopCapture() else manager.startCapture()
        }
    }

    private inner class PauseAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = if (manager.entries.paused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
            e.presentation.text = MpSqlVisualizerBundle.message(
                if (manager.entries.paused) "log.panel.resume" else "log.panel.pause",
            )
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.entries.setPaused(!manager.entries.paused)
        }
    }

    private inner class ClearAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.clear")
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.entries.clear()
        }
    }

    private inner class SettingsAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Properties
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.settings")
        }

        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable::class.java)
        }
    }

    // ---------- 条目列表 ----------

    private fun buildListPane(): JBScrollPane {
        list.emptyText.text = MpSqlVisualizerBundle.message("log.panel.empty")
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val entry = value as? LogSqlEntry
                text = entry?.summary ?: ""
                toolTipText = entry?.restoredSql ?: ""
                return this
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    list.selectedValue?.let { openPreview(it) }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })
        // 选中联动 SQL 预览标签页
        list.addListSelectionListener { updatePreview(list.selectedValue) }
        return JBScrollPane(list)
    }

    // ---------- SQL 预览标签页 ----------

    private fun createPreviewEditor(): Editor {
        val editor = EditorFactory.getInstance()
            .createEditor(EditorFactory.getInstance().createDocument(""), project)
        (editor as EditorEx).apply {
            highlighter = LexerEditorHighlighter(
                SqlSyntaxHighlighter(),
                EditorColorsManager.getInstance().globalScheme,
            )
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
        }
        editor.document.setReadOnly(true)
        return editor
    }

    private fun updatePreview(entry: LogSqlEntry?) {
        val document = previewEditor.document
        val text = entry?.restoredSql ?: ""
        if (document.text != text) {
            document.setReadOnly(false)
            document.setText(text)
            document.setReadOnly(true)
        }
    }

    // ---------- 刷新与动作 ----------

    private fun refreshList() {
        val snapshot = manager.entries.snapshot()
        listModel.clear()
        snapshot.forEach { listModel.addElement(it) }
        updatePreview(list.selectedValue)
    }

    private fun openPreview(entry: LogSqlEntry) {
        SqlPreviewService.showResult(
            project,
            PreviewResult(
                sql = entry.restoredSql,
                compactSql = entry.restoredSql,
                unresolvedParams = emptyList(),
                paramTable = null,
                wrapperType = null,
                tableName = null,
                warning = if ('?' in entry.restoredSql) {
                    MpSqlVisualizerBundle.message("log.preview.unresolvedWarning")
                } else {
                    null
                },
            ),
        )
    }

    private fun showContextMenu(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index >= 0) list.selectedIndex = index
        val entry = list.selectedValue ?: return
        val menu = JPopupMenu()
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("log.panel.copy")).apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(entry.restoredSql), null)
                }
            },
        )
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("log.panel.delete")).apply {
                addActionListener { manager.entries.removeEntry(entry) }
            },
        )
        menu.show(list, e.x, e.y)
    }

    /** 面板销毁：释放预览编辑器（manager 生命周期由项目 @Service 管理）。 */
    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(previewEditor)
    }

    companion object {
        private const val TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar"
    }
}
