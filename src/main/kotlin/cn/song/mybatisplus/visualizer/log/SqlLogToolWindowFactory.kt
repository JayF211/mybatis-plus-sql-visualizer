package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettingsConfigurable
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
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
import java.text.SimpleDateFormat
import java.util.Date
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
    private var formatToggled = false // T-M31.15 工具栏「格式化」按钮切换态
    private val previewEditor: Editor = createPreviewEditor()
    /** T-M36.4：SQL → Java 转换 tab（粘贴 SQL → 格式化/补全 → 转换 → 可编辑 Java 高亮结果区）。 */
    private val sqlToJavaPanel = SqlToJavaPanel(project)
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
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.sqlToJava"), sqlToJavaPanel)
        add(tabs, BorderLayout.CENTER)

        manager.addUiListener { refreshList() }
        refreshList()
    }

    // ---------- 工具栏（IDEA 原生 ActionToolbar：扁平图标按钮 + tooltip） ----------

    private fun buildToolbar(): JPanel {
        // T-M31.16：暂停按钮移除（捕捉开关已覆盖——用户方案 A）
        val group = DefaultActionGroup(
            CaptureAction(),
            FormatAction(),
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

    /** T-M31.15：格式化按钮——切换预览标签页 SQL 的格式化/单行显示。 */
    private inner class FormatAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.ReformatCode
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.format")
        }

        override fun actionPerformed(e: AnActionEvent) {
            formatToggled = !formatToggled
            updatePreview(list.selectedValue)
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
            e.presentation.icon = AllIcons.General.Settings
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
                // T-M31.13/14：SQL 词法着色（插件自身 SqlHighlightColors 配色，随 IDE 主题）+ 可选时间戳前缀
                text = null
                if (entry != null) {
                    // T-M31.15：时间戳与 SQL 分行（<br>）+ 每条 SQL 末尾分号结束符 + 条目间空行
                    val timestamp = timestampHtml(entry.timestamp)
                    val sql = SqlListHtmlRenderer.render(withTerminator(entry.summary))
                    text = "<html>" + timestamp + "<br>" + sql + "<br></html>"
                    toolTipText = entry.restoredSql
                }
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
        val base = withTerminator(entry?.restoredSql ?: "")
        // T-M31.15：工具栏「格式化」按钮 toggle——格式化多行/单行切换
        val text = if (formatToggled && entry != null) SqlFormatter.format(base) else base
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

    /** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复）。 */
    private fun withTerminator(sql: String): String =
        if (sql.isEmpty() || sql.endsWith(";")) sql else sql + ";"

    /** 时间戳 HTML 前缀（设置开关 + 格式；灰字跟随主题）。 */
    private fun timestampHtml(timestamp: Long): String {
        val state = PluginSettings.getInstance().state
        if (!state.logTimestampEnabled) return ""
        val formatted = try {
            SimpleDateFormat(state.logTimestampFormat).format(Date(timestamp))
        } catch (_: IllegalArgumentException) {
            SimpleDateFormat(DEFAULT_TIMESTAMP_FORMAT).format(Date(timestamp)) // 非法格式回退
        }
        val color = toHex(EditorColorsManager.getInstance().globalScheme.getAttributes(SqlHighlightColors.COMMENT).foregroundColor)
        return "<span style='color:$color'>[$formatted] </span>"
    }

    private fun toHex(color: java.awt.Color): String {
        val r = Integer.toHexString(color.red).padStart(2, '0')
        val g = Integer.toHexString(color.green).padStart(2, '0')
        val b = Integer.toHexString(color.blue).padStart(2, '0')
        return "#$r$g$b"
    }

    private fun openPreview(entry: LogSqlEntry) {
        // T-M31.14：设置开启时预览展示格式化版（多行缩进），否则原样
        val state = PluginSettings.getInstance().state
        val base = withTerminator(entry.restoredSql)
        val sql = if (state.logFormatSql) SqlFormatter.format(base) else base
        SqlPreviewService.showResult(
            project,
            PreviewResult(
                sql = sql,
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
                        .setContents(StringSelection(withTerminator(entry.restoredSql)), null)
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

    /** 面板销毁：释放预览编辑器与 SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(previewEditor)
        sqlToJavaPanel.dispose()
    }

    companion object {
        private const val TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar"
        private const val DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }
}
