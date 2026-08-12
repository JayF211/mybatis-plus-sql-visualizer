package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout

/**
 * 工具窗口工厂（M3-03，F-07 工具窗口展示方式）。
 *
 * 注册 ID：`MP SQL Visualizer`（见 plugin.xml toolWindow 扩展）。
 * 内容：SQL 展示面板 + 顶部工具栏（复制 / 清空），并监听编辑器光标变化自动刷新。
 *
 * 说明：工具窗口被动展示最近一次转换结果；光标变化自动刷新通过
 * [SqlPreviewToolWindowPanel.updateFromEditor] 由编辑器监听驱动（见 plugin.xml 的
 * editorFactoryListener 扩展，M5 配置显示方式时再做开关联动）。
 */
class SqlPreviewToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SqlPreviewToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * 工具窗口内容面板：SQL 展示 + 工具栏（清空；复制/单行多行切换在面板顶部）。
 */
class SqlPreviewToolWindowPanel(private val project: Project) : JBPanel<SqlPreviewToolWindowPanel>(BorderLayout()) {

    private val previewPanel = SqlPreviewPanel(project)
    private val statusLabel = JBLabel(MpSqlVisualizerBundle.message("toolwindow.status.hint"))

    init {
        // 顶部工具栏
        val group = DefaultActionGroup().apply {
            add(ClearAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, group, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        // 中部 SQL 展示
        add(previewPanel, BorderLayout.CENTER)

        // 底部状态栏
        add(statusLabel, BorderLayout.SOUTH)
    }

    /** 展示预览结果（由 Action / 编辑器监听调用）。 */
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

    // ------------------------------------------------------------------
    // 工具栏动作
    // ------------------------------------------------------------------

    /** 清空展示区。 */
    private inner class ClearAction : AnAction(MpSqlVisualizerBundle.message("toolwindow.clear"), null, AllIcons.Actions.GC) {
        override fun actionPerformed(e: AnActionEvent) = clear()
    }
}
