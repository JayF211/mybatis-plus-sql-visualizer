package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

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

        // T-M24-04：XML mapper 文件 → 预览光标所在语句（不在语句内 → 首个语句）
        if (file is XmlFile) {
            previewXmlStatement(project, file, editor)
            return
        }

        val service = project.getService(SqlPreviewService::class.java)

        // 分支 A（T-M23.7）：选择代码块 → 扫描选择区内 SQL 候选（wrapper 链首 + 批量方法），0/1/多分发
        val selectionModel = editor.selectionModel
        if (selectionModel.hasSelection()) {
            val selStart = selectionModel.selectionStart
            val selEnd = selectionModel.selectionEnd
            ApplicationManager.getApplication().executeOnPooledThread {
                // T-M23.8 增量修复（EDT 慢操作）：候选扫描 + 弹窗显示文本（摘要/行文本，含 PSI 解析）
                // 一并后台 ReadAction 预计算——SqlSelectionDialog 构造只做纯 UI 渲染（EDT 上 PSI 解析
                // 会被 2025.3+ SlowOperations 守卫禁止，实测 SEVERE "Slow operations are prohibited on EDT"）
                val payload = try {
                    ReadAction.compute<Pair<List<PsiMethodCallExpression>, List<String>>, Throwable> {
                        val heads = WrapperLocator.sqlCandidatesInRange(file, selStart, selEnd)
                        heads to heads.map { head ->
                            SqlSelectionDialog.displayText(
                                WrapperLocator.sqlCandidateSummary(head),
                                WrapperLocator.sqlCandidateLineText(head),
                            )
                        }
                    }
                } catch (t: Throwable) {
                    emptyList<PsiMethodCallExpression>() to emptyList()
                }
                val (chainHeads, displayTexts) = payload
                ApplicationManager.getApplication().invokeLater {
                    when (chainHeads.size) {
                        0 -> SqlPreviewService.showResult(project, null)
                        1 -> preview(service, chainHeads[0], project)
                        else -> SqlSelectionDialog(project, chainHeads, displayTexts) { head ->
                            // T-M23.8：选中确定 → 编辑器定位（光标到行首 + 滚动居中 + 整行选中）→ 预览 SQL
                            locateEditorTo(editor, head)
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

    /**
     * T-M24-04：XML mapper 语句预览——光标所在语句优先，否则文件首个语句；
     * 转换后台执行（EDT 禁止 PSI 慢操作），结果统一展示入口。
     */
    private fun previewXmlStatement(project: Project, file: XmlFile, editor: Editor) {
        val element = file.findElementAt(editor.caretModel.offset) ?: run {
            SqlPreviewService.showResult(project, null)
            return
        }
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
        var statement = tag
        while (statement != null && statement.name !in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS) {
            statement = statement.parentTag
        }
        val target = statement
            ?: PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .firstOrNull { it.name in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS }
        if (target == null) {
            SqlPreviewService.showResult(project, null)
            return
        }
        SqlPreviewService.previewAsync(project, {
            XmlPreviewService.preview(file.text, target.getAttributeValue("id"))
        }) { result ->
            SqlPreviewService.showResult(project, result)
        }
    }

    /** 预览单个链首调用（后台转换，EDT 渲染）。 */
    private fun preview(service: SqlPreviewService, anchor: PsiElement, project: Project) {
        SqlPreviewService.previewAsync(project, { service.preview(anchor) }) { result ->
            SqlPreviewService.showResult(project, result)
        }
    }

    /**
     * T-M23.8：编辑器定位跳转到候选调用所在行（IDEA Find 定位风格）——
     * 光标到行首 + 滚动居中 + 整行选中高亮；失败 runCatching 兜底（不中断预览）。
     */
    private fun locateEditorTo(editor: Editor, call: PsiMethodCallExpression) {
        runCatching {
            val (start, end) = WrapperLocator.lineOffsetsOf(call)
            editor.caretModel.moveToOffset(start)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER_UP)
            editor.selectionModel.setSelection(start, end)
        }
    }

    // update() 读取 PSI，必须在后台线程执行（EDT 上请求 PSI_FILE 会抛异常）
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Java 文件（Wrapper 链）与 XML mapper 文件（T-M24-04：动态 SQL 语句）均可用
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = file is PsiJavaFile || file is XmlFile
    }

    companion object {
        /** 插件动作 ID（plugin.xml 中注册）。 */
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.ConvertToSql"
    }
}

