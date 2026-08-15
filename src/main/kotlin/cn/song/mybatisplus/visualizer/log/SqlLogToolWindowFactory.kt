package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/**
 * MyBatis SQL 日志工具窗口（T-M31-04，交互参考 MybatisCodeHelperPro「MyBatis Log」面板）：
 * 自动监听运行控制台 Preparing/Parameters → 条目列表（最新在上），双击打开现有预览弹窗
 * （高亮/格式化/复制/执行全复用），右键菜单复制/删除单条。
 */
class SqlLogToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setTitle(MpSqlVisualizerBundle.message("log.toolwindow.title"))
        val manager = project.getService(SqlLogManager::class.java)
        val panel = SqlLogPanel(project, manager)
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        content.setDisposer(manager)
        toolWindow.contentManager.addContent(content)
    }
}

/** 工具窗口面板（列表 + 工具栏；headless 测试不布局，交互断言走条目模型层）。 */
class SqlLogPanel(
    private val project: Project,
    private val manager: SqlLogManager,
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<LogSqlEntry>()
    private val list = JBList(listModel)
    private val pauseButton = JButton(MpSqlVisualizerBundle.message("log.panel.pause"))

    init {
        // 工具栏：清空 / 暂停-继续
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        toolbar.add(
            JButton(MpSqlVisualizerBundle.message("log.panel.clear")).apply {
                addActionListener { manager.entries.clear() }
            },
        )
        toolbar.add(
            pauseButton.apply {
                addActionListener {
                    manager.entries.setPaused(!manager.entries.paused)
                    updatePauseButton()
                }
            },
        )
        add(toolbar, BorderLayout.NORTH)

        // 条目列表：摘要单行 + 悬停完整 SQL；空态提示
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
        add(JBScrollPane(list), BorderLayout.CENTER)

        manager.addUiListener { refreshList() }
        manager.attach()
        refreshList()
    }

    private fun refreshList() {
        val snapshot = manager.entries.snapshot()
        listModel.clear()
        snapshot.forEach { listModel.addElement(it) }
        updatePauseButton()
    }

    private fun updatePauseButton() {
        pauseButton.text = MpSqlVisualizerBundle.message(
            if (manager.entries.paused) "log.panel.resume" else "log.panel.pause",
        )
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
}
