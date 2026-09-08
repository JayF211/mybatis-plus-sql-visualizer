package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.log.SlowQueryDetector
import cn.song.mybatisplus.visualizer.log.SqlClickPreview
import cn.song.mybatisplus.visualizer.log.withTerminator
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewDialog
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListModel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * SQL 历史记录面板（F-7）：列表渲染（摘要 + 时间戳 + 来源）+ 双击预览 + 右键菜单（收藏/复制/删除/标签）。
 *
 * <p>列表条目按时间倒序（最新在前）；收藏条目左侧显示星形图标。搜索/标签筛选由外层 [SqlHistorySearchBar] 驱动，
 * 本面板暴露 [refresh] 与 [setEntries] 供其调用。
 */
class SqlHistoryPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val manager = SqlHistoryManager.getInstance()
    private val listModel = DefaultListModel<SqlHistoryEntry>()
    private val list = JBList<SqlHistoryEntry>(listModel)
    /** T-ClickPreview：单击出轻量预览弹窗（内容 = 完整 SQL），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。 */
    private val clickPreview = SqlClickPreview(list) { list.selectedValue?.let { withTerminator(it.sql) } }

    /** 当前筛选模式（null = 全部）。 */
    private var tagFilter: String? = null
    private var favoritesOnly = false
    private var searchQuery: String = ""

    private val searchBar: SqlHistorySearchBar = SqlHistorySearchBar(
        onSearch = { query ->
            searchQuery = query
            refresh()
        },
        onFilterChange = { onFilterChanged() },
    )

    /** 筛选下拉变更回调（读取搜索 bar 当前状态更新面板筛选条件）。 */
    private fun onFilterChanged() {
        when (searchBar.activeFilter) {
            SqlHistorySearchBar.FAVORITES_FILTER -> {
                favoritesOnly = true
                tagFilter = null
            }
            null -> {
                favoritesOnly = false
                tagFilter = null
            }
            else -> {
                favoritesOnly = false
                tagFilter = searchBar.activeFilter
            }
        }
        refresh()
    }

    // ---------- 测试访问器 ----------

    /** 当前列表模型条目数（测试断言）。 */
    internal val visibleEntryCount: Int get() = listModel.size()

    /** 当前列表模型条目 SQL 列表（测试断言）。 */
    internal val visibleEntries: List<String>
        get() = (0 until listModel.size()).map { listModel.getElementAt(it).sql }

    /** 当前标签筛选（测试断言）。 */
    internal val currentTagFilter: String? get() = tagFilter

    /** 当前是否仅显示收藏（测试断言）。 */
    internal val isFavoritesOnly: Boolean get() = favoritesOnly

    /** 单击预览控制器（T-ClickPreview 测试断言）。 */
    internal val clickPreviewForTest: SqlClickPreview get() = clickPreview

    /** 设置搜索词（测试用；等价于在搜索框输入）。 */
    internal fun setSearchQueryForTest(query: String) {
        searchQuery = query
        refresh()
    }

    init {
        val topPanel = JPanel(BorderLayout())
        topPanel.add(buildToolbar(), BorderLayout.NORTH)
        topPanel.add(searchBar, BorderLayout.SOUTH)
        add(topPanel, BorderLayout.NORTH)
        add(buildListPane(), BorderLayout.CENTER)
        refresh()
    }

    // ---------- 工具栏 ----------

    private fun buildToolbar(): JPanel {
        val group = DefaultActionGroup(
            ClearAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, group, true)
            .apply { targetComponent = this@SqlHistoryPanel }
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        bar.add(toolbar.component)
        return bar
    }

    private inner class ClearAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.clear")
        }

        override fun actionPerformed(e: AnActionEvent) {
            Messages.showYesNoDialog(
                project,
                "Clear all SQL history?",
                MpSqlVisualizerBundle.message("history.toolwindow.title"),
                Messages.getQuestionIcon(),
            ).let { if (it == Messages.YES) manager.clear() }
            refresh()
        }
    }

    // ---------- 列表 ----------

    private fun buildListPane(): JBScrollPane {
        list.emptyText.text = MpSqlVisualizerBundle.message("history.panel.empty")
        list.fixedCellHeight = -1
        list.cellRenderer = HistoryCellRenderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // T-ClickPreview：先转发 clickCount（单击排程预览 / 双击停表并关旧预览），双击再开完整弹窗
                clickPreview.onClick(e.clickCount)
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
        return JBScrollPane(list)
    }

    /** 列表单元格渲染器：星形图标 + SQL 摘要 + 时间戳 + 来源。 */
    private inner class HistoryCellRenderer : javax.swing.DefaultListCellRenderer() {
        private val favoriteIcon: Icon = AllIcons.Nodes.Favorite
        private val emptyIcon: Icon = AllIcons.Nodes.EmptyNode

        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val entry = value as? SqlHistoryEntry
            text = null
            if (entry != null) {
                val ts = SimpleDateFormat("HH:mm:ss").format(Date(entry.timestamp))
                val summary = withTerminator(entry.summary())
                val source = entry.sourceLabel()
                val durationColor = when (SlowQueryDetector.level(entry.executionTimeMs)) {
                    SlowQueryDetector.DurationLevel.SLOW -> "#D32F2F"
                    SlowQueryDetector.DurationLevel.MODERATE -> "#E67E22"
                    SlowQueryDetector.DurationLevel.FAST -> "#4CAF50"
                    SlowQueryDetector.DurationLevel.NONE -> "#9E9E9E"
                }
                val durationText = entry.executionTimeMs?.let { "${it}ms" } ?: "—"
                val durationPart = "  <span style='color:$durationColor'>$durationText</span>"
                val sourcePart = if (source.isNotBlank()) "  <span style='color:#9E9E9E'>($source)</span>" else ""
                val tagPart = if (entry.tags.isNotEmpty())
                    "  <span style='color:#9E9E9E'>[${entry.tags.joinToString(", ")}]</span>" else ""
                this.text = "<html>" +
                    "${if (entry.favorite) "&#9733; " else ""}" +
                    "<b>${escapeHtml(summary)}</b>" +
                    durationPart +
                    "  <span style='color:#9E9E9E'>$ts</span>" +
                    sourcePart + tagPart +
                    "</html>"
                // T-ClickPreview：不再设 toolTipText（悬停逐行弹气泡 = 条目多时卡顿根因），预览改单击触发
                icon = if (entry.favorite) favoriteIcon else emptyIcon
            }
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, com.intellij.ui.JBColor.border()),
                EmptyBorder(5, 8, 5, 8),
            )
            return this
        }

        private fun escapeHtml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    // ---------- 预览 ----------

    private fun openPreview(entry: SqlHistoryEntry) {
        val result = PreviewResult(
            sql = entry.sql,
            compactSql = entry.compactSql,
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = null,
            tableName = entry.tableName.ifBlank { null },
        )
        SqlPreviewService.showResult(project, result)
    }

    // ---------- 右键菜单 ----------

    private fun showContextMenu(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index >= 0) list.selectedIndex = index
        val entry = list.selectedValue ?: return
        val menu = JPopupMenu()

        // 收藏/取消收藏
        if (entry.favorite) {
            menu.add(JMenuItem(MpSqlVisualizerBundle.message("history.menu.unfavorite")).apply {
                addActionListener { manager.toggleFavorite(entry.id); refresh() }
            })
        } else {
            menu.add(JMenuItem(MpSqlVisualizerBundle.message("history.menu.favorite")).apply {
                addActionListener { manager.toggleFavorite(entry.id); refresh() }
            })
        }

        // 复制
        menu.add(JMenuItem(MpSqlVisualizerBundle.message("history.menu.copy")).apply {
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(entry.sql), null)
            }
        })

        // 添加标签
        menu.add(JMenuItem(MpSqlVisualizerBundle.message("history.menu.addTag")).apply {
            addActionListener {
                val tag = Messages.showInputDialog(
                    project,
                    MpSqlVisualizerBundle.message("history.tag.dialog.message"),
                    MpSqlVisualizerBundle.message("history.tag.dialog.title"),
                    Messages.getQuestionIcon(),
                )?.trim()
                if (!tag.isNullOrBlank()) {
                    manager.addTag(entry.id, tag)
                    refresh()
                }
            }
        })

        // 移除标签（子菜单）
        if (entry.tags.isNotEmpty()) {
            val tagMenu = JMenu(MpSqlVisualizerBundle.message("history.menu.removeTag"))
            entry.tags.forEach { tag ->
                tagMenu.add(JMenuItem(tag).apply {
                    addActionListener { manager.removeTag(entry.id, tag); refresh() }
                })
            }
            menu.add(tagMenu)
        }

        menu.addSeparator()

        // 删除
        menu.add(JMenuItem(MpSqlVisualizerBundle.message("history.menu.delete")).apply {
            addActionListener { manager.remove(entry.id); refresh() }
        })

        menu.show(list, e.x, e.y)
    }

    // ---------- 刷新 / 筛选 ----------

    /**
     * 刷新列表（从管理器重新拉取，应用当前筛选条件 + 搜索词）。
     */
    fun refresh() {
        val base: List<SqlHistoryEntry> = when {
            favoritesOnly -> manager.filterFavorites()
            tagFilter != null -> manager.filterByTag(tagFilter!!)
            else -> manager.entries
        }
        val filtered = if (searchQuery.isBlank()) base
        else base.filter {
            it.sql.contains(searchQuery, ignoreCase = true) ||
                it.compactSql.contains(searchQuery, ignoreCase = true)
        }
        // 同步标签下拉选项
        searchBar.refreshTags(manager.allTags())
        ApplicationManager.getApplication().invokeLater {
            listModel.clear()
            filtered.forEach { listModel.addElement(it) }
        }
    }

    /**
     * 设置标签筛选（null = 显示全部）。
     */
    fun setTagFilter(tag: String?) {
        tagFilter = tag
        refresh()
    }

    /**
     * 设置仅显示收藏。
     */
    fun setFavoritesOnly(enabled: Boolean) {
        favoritesOnly = enabled
        refresh()
    }

    override fun dispose() {
        clickPreview.dispose()
    }

    companion object {
        private const val TOOLBAR_ID = "cn.song.mybatisplus.visualizer.history.toolbar"
    }
}
