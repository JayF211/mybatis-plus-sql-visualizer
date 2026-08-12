package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiJavaFile

/**
 * 转换 Action（M3-02，F-04 右键菜单 + F-05 快捷键）。
 *
 * 触发方式（plugin.xml 注册）：
 *  - 编辑器右键菜单：EditorPopupMenu → "MP-SQL-Preview → Convert to SQL"
 *  - 快捷键：默认 `Ctrl+Alt+S`（Windows/Linux）/ `Cmd+Option+S`（macOS）
 *
 * 行为：取光标处 PSI 元素 → [SqlPreviewService.preview] → 弹窗展示 SQL。
 * 未找到 Wrapper 时给出友好提示，不抛异常。
 */
class ConvertToSqlAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset) ?: return

        // PSI 转换在后台线程执行（EDT 上慢操作会被 2025.3+ 禁止），回调在 EDT 渲染
        SqlPreviewService.previewAsync(project, {
            project.getService(SqlPreviewService::class.java).preview(element)
        }) { result ->
            // 统一展示入口（M10-03）：按显示方式（F-16）弹窗 / 工具窗口 / 两者；
            // result 为 null 时弹友好提示
            SqlPreviewService.showResult(project, result)
        }
    }

    // update() 读取 PSI，必须在后台线程执行（EDT 上请求 PSI_FILE 会抛异常）
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // 仅在 Java 文件中可用（M1 聚焦 Java PSI；Kotlin 支持后续里程碑）
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = file is PsiJavaFile
    }

    companion object {
        /** 插件动作 ID（plugin.xml 中注册）。 */
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.ConvertToSql"
    }
}
