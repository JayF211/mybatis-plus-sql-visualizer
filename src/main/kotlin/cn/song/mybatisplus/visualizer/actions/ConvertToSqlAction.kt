package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService
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
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
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

        // T-M25-03：光标在 MyBatis SQL 注解内 → 注解预览（@Select/@Update/Provider 族）
        val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java, false)
        if (annotation != null && AnnotationSqlPreviewService.isSqlAnnotation(annotation)) {
            SqlPreviewService.previewAsync(project, {
                AnnotationSqlPreviewService.preview(annotation, project)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
            return
        }

        // PSI 转换在后台线程执行（EDT 上慢操作会被 2025.3+ 禁止），回调在 EDT 渲染
        SqlPreviewService.previewAsync(project, {
            service.preview(element)
        }) { result ->
            if (result != null) {
                // 统一展示入口（M10-03）：按显示方式（F-16）弹窗 / 工具窗口 / 两者
                SqlPreviewService.showResult(project, result)
            } else {
                // M26-A：Wrapper 未命中 → 回落文件级 SQL 注解候选（Mapper 接口场景）
                showAnnotationCandidates(project, file)
            }
        }
    }

    /**
     * M26-A：文件级 SQL 注解候选——收集文件内全部 @Select/@Update 等注解（0/1/多分发，
     * 仿 T-M23.7 选择区候选交互）；摘要/行文本后台 ReadAction 预计算（EDT 慢操作守卫）。
     */
    private fun showAnnotationCandidates(project: Project, file: com.intellij.psi.PsiFile) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val payload = try {
                ReadAction.compute<Pair<List<PsiAnnotation>, List<String>>, Throwable> {
                    val annotations = AnnotationSqlPreviewService.annotationCandidates(file)
                    annotations to annotations.map { anno ->
                        val method = PsiTreeUtil.getParentOfType(anno, PsiMethod::class.java)
                        SqlSelectionDialog.displayText(
                            "${method?.name ?: "?"}（${anno.qualifiedName?.substringAfterLast('.')}）",
                            AnnotationSqlPreviewService.sqlSnippet(anno),
                        )
                    }
                }
            } catch (t: Throwable) {
                emptyList<PsiAnnotation>() to emptyList()
            }
            val (annotations, displayTexts) = payload
            ApplicationManager.getApplication().invokeLater {
                when (annotations.size) {
                    0 -> SqlPreviewService.showResult(project, null)
                    1 -> previewAnnotation(project, annotations[0])
                    else -> SqlSelectionDialog(project, annotations, displayTexts) { selected ->
                        previewAnnotation(project, selected)
                    }.show()
                }
            }
        }
    }

    /** 预览单个 SQL 注解（后台转换，EDT 渲染）。 */
    private fun previewAnnotation(project: Project, annotation: PsiAnnotation) {
        SqlPreviewService.previewAsync(project, {
            AnnotationSqlPreviewService.preview(annotation, project)
        }) { result ->
            SqlPreviewService.showResult(project, result)
        }
    }

    /**
     * T-M24-04/M25-01：XML mapper 语句预览——光标所在语句优先；不在语句内时
     * 收集全部语句（0/1/多分发，多候选走 [SqlSelectionDialog]，仿 Java 选择区候选交互）。
     */
    private fun previewXmlStatement(project: Project, file: XmlFile, editor: Editor) {
        val element = file.findElementAt(editor.caretModel.offset)
        val tag = element?.let { PsiTreeUtil.getParentOfType(it, XmlTag::class.java) }
        var statement = tag
        while (statement != null && statement.name !in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS) {
            statement = statement.parentTag
        }
        if (statement != null) {
            previewXml(project, file, statement)
            return
        }
        // 光标不在语句内 → 全部语句候选（T-M25-01）
        val statements = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS }
        when (statements.size) {
            0 -> SqlPreviewService.showResult(project, null)
            1 -> previewXml(project, file, statements[0])
            else -> {
                ApplicationManager.getApplication().executeOnPooledThread {
                    // EDT 慢操作守卫：摘要/行文本后台 ReadAction 预计算（仿 T-M23.8 Java 候选）
                    val displayTexts = try {
                        ReadAction.compute<List<String>, Throwable> {
                            statements.map { tag ->
                                val line = tag.containingFile.viewProvider.document
                                    ?.getLineNumber(tag.textRange.startOffset)?.plus(1) ?: 0
                                val text = tag.text?.trim().orEmpty()
                                SqlSelectionDialog.displayText(
                                    "${tag.name} ${tag.getAttributeValue("id").orEmpty()}（第 ${line} 行）",
                                    text,
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        emptyList()
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (displayTexts.size != statements.size) {
                            SqlPreviewService.showResult(project, null)
                            return@invokeLater
                        }
                        SqlSelectionDialog(project, statements, displayTexts) { selected ->
                            previewXml(project, file, selected)
                        }.show()
                    }
                }
            }
        }
    }

    /** 预览单个 XML 语句（后台转换，EDT 渲染；T-M25.2 传 project 解析静态常量引用）。 */
    private fun previewXml(project: Project, file: XmlFile, tag: XmlTag) {
        SqlPreviewService.previewAsync(project, {
            XmlPreviewService.preview(file.text, tag.getAttributeValue("id"), project)
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

