package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettingsConfigurable
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
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
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.plaf.basic.BasicTabbedPaneUI

/**
 * MyBatis SQL 日志工具窗口（T-M31-04，交互参考 MybatisCodeHelperPro「MyBatis Sql」面板）：
 * 双标签页（SQL 日志列表 / SQL 预览），选中标签蓝底白字（[BlueSelectedTabUi]），
 * 工具栏图标化（清空/暂停-继续/设置）；双击列表项打开现有预览弹窗（高亮/格式化/复制/执行全复用），
 * 右键菜单复制/删除单条。
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
    private val pauseButton = iconButton(AllIcons.Actions.Pause, "log.panel.pause") {
        manager.entries.setPaused(!manager.entries.paused)
        updatePauseButton()
    }
    private val tabs = JTabbedPane().apply { ui = BlueSelectedTabUi() }
    private val previewEditor: Editor = createPreviewEditor()

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
        manager.attach()
        refreshList()
    }

    // ---------- 工具栏 ----------

    private fun buildToolbar(): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        toolbar.add(iconButton(AllIcons.Actions.GC, "log.panel.clear") { manager.entries.clear() })
        toolbar.add(pauseButton)
        toolbar.add(iconButton(AllIcons.Actions.Properties, "log.panel.settings") { openSettings() })
        return toolbar
    }

    private fun iconButton(icon: javax.swing.Icon, textKey: String, action: () -> Unit): JButton =
        JButton(MpSqlVisualizerBundle.message(textKey), icon).apply {
            addActionListener { action() }
        }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable::class.java)
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
        updatePauseButton()
        updatePreview(list.selectedValue)
    }

    private fun updatePauseButton() {
        pauseButton.text = MpSqlVisualizerBundle.message(
            if (manager.entries.paused) "log.panel.resume" else "log.panel.pause",
        )
        pauseButton.icon = if (manager.entries.paused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
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
}

/**
 * 选中标签蓝底白字（T-M31.4，参考 MybatisCodeHelper-Pro 选中标签深蓝底样式）：
 * 自定义 [BasicTabbedPaneUI] 的 paintTab——选中画 MP 蓝矩形底 + 白字，未选中沿用 LAF 前景；
 * 不依赖 LAF 主题（亮/暗一致），布局计算仍走父类。
 */
private class BlueSelectedTabUi : BasicTabbedPaneUI() {

    /** Kotlin 无法访问 Java protected 字段 [BasicTabbedPaneUI.tabbedPane]，经 installUI 捕获。 */
    private var pane: JTabbedPane? = null

    override fun installUI(c: javax.swing.JComponent) {
        super.installUI(c)
        pane = c as JTabbedPane
    }

    override fun paintTab(
        g: Graphics,
        tabPlacement: Int,
        rects: Array<Rectangle>,
        tabIndex: Int,
        iconRect: Rectangle,
        textRect: Rectangle,
    ) {
        val rect = rects[tabIndex]
        val g2 = g as Graphics2D
        val selected = pane?.selectedIndex == tabIndex
        g2.color = if (selected) TAB_SELECTED_BG else pane?.foreground ?: Color.WHITE
        if (selected) {
            g2.fillRect(rect.x, rect.y, rect.width, rect.height)
            g2.color = Color.WHITE
        }
        val fm = g2.fontMetrics
        val title = pane?.getTitleAt(tabIndex) ?: return
        g2.drawString(
            title,
            rect.x + (rect.width - fm.stringWidth(title)) / 2,
            rect.y + (rect.height + fm.ascent - fm.descent) / 2,
        )
    }

    companion object {
        /** MP 蓝（与侧边栏图标 sqlLog.svg 同色）。 */
        private val TAB_SELECTED_BG = Color(0x1E9FFF)
    }
}
