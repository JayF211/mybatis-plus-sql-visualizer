package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.SearchTextField
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * 历史 SQL 面板（F-44）：读取 .idea/mybatis-plus-sql-visualizer/sql-history/ 文件展示。
 *
 * 展示：SQL 摘要 | 耗时 | 执行时间 | 源码位置
 * 双击 → 预览弹窗
 * 右键 → 复制 SQL / 跳转到源码
 */
class HistorySqlLogPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val listModel = DefaultListModel<LogSqlEntry>()
    private val list = SqlEntryList(listModel)
    private val searchField = SearchTextField()
    private var allEntries: List<LogSqlEntry> = emptyList()

    init {
        buildUI()
        refresh()
    }

    private fun buildUI() {
        // 顶部工具栏：刷新 + 搜索
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.add(JButton(MpSqlVisualizerBundle.message("log.history.refresh")).apply {
            toolTipText = MpSqlVisualizerBundle.message("log.history.refresh.tip")
            addActionListener { refresh() }
        })
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.empty")
        searchField.textEditor.minimumSize = Dimension(200, searchField.textEditor.minimumSize.height)
        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = applySearch()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = applySearch()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = applySearch()
        })
        toolbar.add(searchField)
        add(toolbar, BorderLayout.NORTH)

        // 列表
        list.cellRenderer = HistoryCellRenderer()
        list.fixedCellHeight = -1
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
    }

    /** 从 .idea 目录读取历史文件并刷新列表。 */
    fun refresh() {
        val dir = getHistoryDir()
        allEntries = SqlLogFileReader.readAll(dir)
        applySearch()
        LOG.info("HistorySqlLogPanel.refresh: 读取 ${allEntries.size} 条历史 SQL（目录: $dir）")
    }

    /** 应用搜索过滤。 */
    private fun applySearch() {
        val query = searchField.text.trim().lowercase()
        listModel.clear()
        val filtered = if (query.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { entry ->
                entry.restoredSql.lowercase().contains(query) ||
                    entry.sourceFile?.lowercase()?.contains(query) == true
            }
        }
        filtered.forEach { listModel.addElement(it) }
    }

    private fun getHistoryDir(): String {
        val basePath = project.basePath ?: return ""
        return "$basePath${File.separator}.idea${File.separator}mybatis-plus-sql-visualizer${File.separator}sql-history"
    }

    private fun openPreview(entry: LogSqlEntry) {
        val base = withTerminator(entry.restoredSql)
        SqlPreviewService.showResult(
            project,
            cn.song.mybatisplus.visualizer.model.PreviewResult(
                sql = base,
                compactSql = entry.restoredSql,
                unresolvedParams = emptyList(),
                paramTable = null,
                wrapperType = null,
                tableName = null,
                warning = null,
            ),
        )
    }

    private fun showContextMenu(e: MouseEvent) {
        val idx = list.locationToIndex(e.point)
        if (idx < 0) return
        list.selectedIndex = idx
        val entry = list.model.getElementAt(idx) ?: return

        val menu = JPopupMenu()
        menu.add(JMenuItem(MpSqlVisualizerBundle.message("log.history.copy"), AllIcons.Actions.Copy).apply {
            addActionListener {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(java.awt.datatransfer.StringSelection(withTerminator(entry.restoredSql)), null)
            }
        })
        menu.add(JMenuItem(MpSqlVisualizerBundle.message("log.history.jumpToSource"), AllIcons.Actions.EditSource).apply {
            addActionListener { jumpToSource(entry) }
        })
        menu.show(list, e.x, e.y)
    }

    /**
     * 跳转到产生该 SQL 的源码位置。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）。
     */
    private fun jumpToSource(entry: LogSqlEntry) {
        if (entry.sourceFile == null) {
            Messages.showInfoMessage(
                project,
                MpSqlVisualizerBundle.message("log.history.jumpToSource.noSource"),
                MpSqlVisualizerBundle.message("log.history.jumpToSource"),
            )
            return
        }
        val basePath = project.basePath ?: return
        val fullPath = "$basePath${File.separator}${entry.sourceFile}"
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)
        if (virtualFile != null) {
            val line = maxOf(0, entry.sourceLine - 1)
            OpenFileDescriptor(project, virtualFile, line, 0).navigate(true)
        } else {
            // 文件不存在时提示（项目结构可能已变化）
            Messages.showInfoMessage(
                project,
                MpSqlVisualizerBundle.message("log.history.jumpToSource.notFound", entry.sourceFile),
                MpSqlVisualizerBundle.message("log.history.jumpToSource"),
            )
        }
    }

    override fun dispose() {}

    companion object {
        private val LOG = Logger.getInstance(HistorySqlLogPanel::class.java)
    }
}

/**
 * 历史 SQL 列表单元格渲染器：展示耗时 + 执行时间 + 源码位置 + SQL 摘要。
 */
private class HistoryCellRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val entry = value as? LogSqlEntry
        if (entry != null) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date(entry.timestamp))
            val duration = entry.executionTimeMs?.let { "${it}ms" } ?: "—"
            val source = entry.sourceFile?.let { file ->
                val name = file.substringAfterLast(File.separatorChar)
                if (entry.sourceLine > 0) "$name:${entry.sourceLine}" else name
            } ?: "unknown"
            this.text = "[$duration] [$timestamp] [$source] ${entry.summary}"
            toolTipText = entry.restoredSql
            foreground = durationColor(entry.executionTimeMs, isSelected, list)
        }
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, com.intellij.ui.JBColor.border()),
            javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8),
        )
        return this
    }

    private fun durationColor(ms: Long?, isSelected: Boolean, list: javax.swing.JList<*>?): java.awt.Color {
        if (isSelected) return list?.selectionForeground ?: com.intellij.ui.JBColor.foreground()
        if (ms == null) return list?.foreground ?: com.intellij.ui.JBColor.foreground()
        return when {
            ms >= 1000 -> com.intellij.ui.JBColor.RED
            ms >= 500 -> com.intellij.ui.JBColor.ORANGE
            else -> list?.foreground ?: com.intellij.ui.JBColor.foreground()
        }
    }
}
