package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression

/**
 * 转换 Action（M3-02，F-04 右键菜单 + F-05 快捷键）。
 *
 * 触发方式（plugin.xml 注册）：
 *  - 编辑器右键菜单：EditorPopupMenu → "MP-SQL-Preview → Convert to SQL"
 *  - 快捷键：默认 `Ctrl+Alt+S`（Windows/Linux）/ `Cmd+Option+S`（macOS）
 *
 * 行为（T-M23.7）：
 *  - **选择代码块**（selection 非空）→ 扫描选择区内全部 Wrapper 链首：0 个 → 未找到提示；
 *    1 个 → 直接预览；多个 → [SqlSelectionDialog] 选择后预览；
 *  - 光标处 → 取光标 PSI 元素 → [SqlPreviewService.preview]（内部定位增强：eq/list 调用处也可触发）。
 * 未找到 Wrapper 时给出友好提示，不抛异常。
 */
class ConvertToSqlAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val service = project.getService(SqlPreviewService::class.java)

        // 分支 A（T-M23.7）：选择代码块 → 扫描选择区内链首（后台线程），0/1/多分发
        val selectionModel = editor.selectionModel
        if (selectionModel.hasSelection()) {
            val selStart = selectionModel.selectionStart
            val selEnd = selectionModel.selectionEnd
            ApplicationManager.getApplication().executeOnPooledThread {
                val chainHeads = try {
                    ReadAction.compute<List<PsiMethodCallExpression>, Throwable> {
                        WrapperLocator.chainHeadCallsInRange(file, selStart, selEnd)
                    }
                } catch (t: Throwable) {
                    emptyList()
                }
                ApplicationManager.getApplication().invokeLater {
                    when (chainHeads.size) {
                        0 -> SqlPreviewService.showResult(project, null)
                        1 -> preview(service, chainHeads[0], project)
                        else -> SqlSelectionDialog(project, chainHeads) { head ->
                            preview(service, head, project)
                        }.show()
                    }
                }
            }
            return
        }

        // 分支 B（现状）：光标处定位（内部增强：eq/list 调用处同样可触发）
        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset) ?: return

        // PSI 转换在后台线程执行（EDT 上慢操作会被 2025.3+ 禁止），回调在 EDT 渲染
        SqlPreviewService.previewAsync(project, {
            service.preview(element)
        }) { result ->
            // 统一展示入口（M10-03）：按显示方式（F-16）弹窗 / 工具窗口 / 两者；
            // result 为 null 时弹友好提示
            SqlPreviewService.showResult(project, result)
        }
    }

    /** 预览单个链首调用（后台转换，EDT 渲染）。 */
    private fun preview(service: SqlPreviewService, anchor: PsiElement, project: Project) {
        SqlPreviewService.previewAsync(project, { service.preview(anchor) }) { result ->
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

