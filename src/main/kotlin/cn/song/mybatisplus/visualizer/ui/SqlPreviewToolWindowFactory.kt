package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton

/**
 * 工具窗口工厂（M3-03，F-07 工具窗口展示方式）。
 *
 * 注册 ID：`MP SQL Visualizer`（见 plugin.xml toolWindow 扩展，与 [SqlToolWindow.ID] 一致）。
 * 内容：顶部粘贴输入区（转换按钮）+ SQL 预览面板 + 底部状态栏。
 *
 * 触发方式：
 *  - 粘贴 Wrapper 代码 → 点「转换」（创建 Java PSI 片段后走转换链路，M9 增强）
 *  - 右键菜单/快捷键转换时按显示方式（F-16）推送结果到本面板（[SqlToolWindow.showResult]）
 */
class SqlPreviewToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SqlPreviewToolWindowPanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * 工具窗口内容面板：粘贴输入 + SQL 预览 + 工具栏（清空）。
 */
class SqlPreviewToolWindowPanel(private val project: Project) : JBPanel<SqlPreviewToolWindowPanel>(BorderLayout()) {

    private val previewPanel = SqlPreviewPanel(project)
    private val statusLabel = JBLabel(MpSqlVisualizerBundle.message("toolwindow.status.hint"))

    private val inputArea = JBTextArea(4, 40).apply {
        isEditable = true
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        emptyText.text = MpSqlVisualizerBundle.message("toolwindow.input.hint")
    }

    init {
        // 顶部工具栏（清空）+ 粘贴输入区 + 转换按钮
        val group = DefaultActionGroup().apply {
            add(ClearAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, group, true)
        toolbar.targetComponent = this

        val convertButton = JButton(MpSqlVisualizerBundle.message("toolwindow.convert")).apply {
            addActionListener { convertPastedText() }
        }
        val inputRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBScrollPane(inputArea).apply { preferredSize = Dimension(300, 90) }, BorderLayout.CENTER)
            add(convertButton, BorderLayout.EAST)
        }
        val top = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(inputRow, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)

        // 中部 SQL 预览
        add(previewPanel, BorderLayout.CENTER)

        // 底部状态栏
        add(statusLabel, BorderLayout.SOUTH)
    }

    /** 展示预览结果（由 Action / 转换按钮调用）。 */
    fun showResult(result: PreviewResult) {
        previewPanel.showResult(result)
        statusLabel.text = MpSqlVisualizerBundle.message(
            "toolwindow.status.generated",
            java.time.LocalTime.now().withNano(0),
        )
    }

    /** 清空展示区。 */
    fun clear() {
        previewPanel.clear()
        statusLabel.text = MpSqlVisualizerBundle.message("toolwindow.status.cleared")
    }

    /**
     * 转换粘贴的代码：以 Java PSI 片段创建文件，走统一转换链路（M9 增强）。
     * 片段无需完整类定义——实体类不可解析时按类名命名策略兜底表名。
     */
    private fun convertPastedText() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) {
            statusLabel.text = MpSqlVisualizerBundle.message("toolwindow.status.emptyInput")
            return
        }
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("Paste.java", JavaLanguage.INSTANCE, text)
        val result = project.getService(SqlPreviewService::class.java).preview(file)
        if (result != null) {
            showResult(result)
        } else {
            statusLabel.text = MpSqlVisualizerBundle.message("toolwindow.status.notFound")
        }
    }

    // ------------------------------------------------------------------
    // 工具栏动作
    // ------------------------------------------------------------------

    /** 清空展示区。 */
    private inner class ClearAction : AnAction(MpSqlVisualizerBundle.message("toolwindow.clear"), null, AllIcons.Actions.GC) {
        override fun actionPerformed(e: AnActionEvent) = clear()
    }
}
