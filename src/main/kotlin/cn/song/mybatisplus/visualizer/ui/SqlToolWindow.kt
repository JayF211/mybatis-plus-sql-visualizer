package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 工具窗口桥接（F-07）：动作/监听向工具窗口推送结果。
 *
 * 工具窗口 ID 与 plugin.xml `<toolWindow id="MP SQL Visualizer">` 保持一致。
 */
object SqlToolWindow {

    const val ID = "MP SQL Visualizer"

    /** 是否跟随编辑器光标实时转换（工具栏开关，默认开）。 */
    @Volatile
    var isFollowEditor: Boolean = true

    /** 查找工具窗口内容面板（未创建时返回 null）。 */
    fun findPanel(project: Project): SqlPreviewToolWindowPanel? {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return null
        return tw.contentManager.getContent(0)?.component as? SqlPreviewToolWindowPanel
    }

    /** 推送预览结果到工具窗口并前置显示（窗口未创建时忽略）。 */
    fun showResult(project: Project, result: PreviewResult) {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        val panel = findPanel(project) ?: return
        tw.show()
        panel.showResult(result)
    }
}
