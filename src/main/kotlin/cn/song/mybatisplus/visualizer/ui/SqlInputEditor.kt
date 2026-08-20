package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlPreviewLanguage
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * SQL 可编辑输入编辑器（T-M36.4 工具窗口「SQL → Java」tab 输入区）。
 *
 * 接线全部复用 SqlPreviewPanel 的实证先例：
 *  - `SqlPreviewLanguage` PSI 文件支撑 → 挂 [SqlCompletionContributor] 提供补全；
 *  - DataProvider 包装补 EDITOR（M27.1：2025.3 平台编辑器改 UiCompatibleDataProvider 后
 *    传统 DataManager 沿父链只认 DataProvider，不包装则 Ctrl+Space/自动补全拿不到编辑器）；
 *  - **自建 autopopup**（M27.2）：平台 autopopup 对非项目文件编辑器不生效（空弹窗、
 *    contributor 未调度）——监听文档输入（标识符字符），commitDocument 后以
 *    explicit=true 调手动模式补全处理器；[setSql] 等程序性写入经 [suppress] 抑制（M27.4）；
 *  - Editor 生命周期由 [dispose] 释放（对齐 SqlLogPanel.dispose）。
 */
class SqlInputEditor(
    private val project: Project,
    initialSql: String,
) : Disposable {

    private var psiFile: PsiFile? = null

    private var autoPopupListener: com.intellij.openapi.editor.event.DocumentListener? = null
    private var autoPopupScheduled = false

    /** 程序性写入（setSql）期间抑制自建 autopopup（对齐 T-M27.4）。 */
    private var suppress = false

    private val editorHolder = JBPanel<JBPanel<*>>(BorderLayout())

    val editor: Editor = createEditor(initialSql)

    companion object {
        /** 解析 SQL 文件类型（Database 插件可用时返回 SQL 语言，否则 null —— 测试环境无 Database 插件）。 */
        fun resolveSqlFileType(): com.intellij.lang.Language? =
            com.intellij.lang.Language.findLanguageByID("SQL")
    }

    /** DataProvider 包装后的组件（供面板布局；补全经此拿 CommonDataKeys.EDITOR）。 */
    val component: JComponent = editorHolder

    val text: String get() = editor.document.text

    /** 创建编辑器：PSI 文件 → 文档 → 可编辑编辑器 + 高亮 + 补全上下文双载体 + autopopup。 */
    private fun createEditor(sql: String): Editor {
        val created = if (project.isDisposed) {
            EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql))
        } else {
            val psi = PsiFileFactory.getInstance(project)
                .createFileFromText("input.mpsql", SqlPreviewLanguage, sql)
            psiFile = psi
            val doc = PsiDocumentManager.getInstance(project).getDocument(psi)
                ?: EditorFactory.getInstance().createDocument(sql)
            EditorFactory.getInstance().createEditor(doc, project)
        }
        (created as EditorEx).apply {
            highlighter = LexerEditorHighlighter(
                SqlSyntaxHighlighter(),
                EditorColorsManager.getInstance().globalScheme,
            )
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
        }
        // 补全上下文双载体（M16.2）：Editor userData 为主载体（用户输入后 PSI 可能被
        // commit 重建而丢失 userData，Editor 对象编辑期间稳定）、PSI 文件 userData 兜底
        val ctx = SqlCompletionContext(null, emptyList(), null, emptyMap(), null, emptyList())
        created.putUserData(SqlCompletionContext.KEY, ctx)
        psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
        // DataProvider 包装补 EDITOR（M27.1 实证，见类注释）
        editorHolder.add(
            object : JBPanel<JBPanel<*>>(BorderLayout()), DataProvider {
                override fun getData(dataId: String): Any? =
                    if (CommonDataKeys.EDITOR.`is`(dataId)) created else null
            }.apply { add(created.component, BorderLayout.CENTER) },
            BorderLayout.CENTER,
        )
        bindAutoPopup(created)
        return created
    }

    /**
     * 程序性替换全文（格式化/清空/转换回填）——suppress 抑制自建 autopopup（T-M27.4
     * 实证：整文档替换触发 documentChanged，不抑制会弹补全）。
     */
    fun setSql(sql: String) {
        val document = editor.document
        if (document.text == sql) return
        suppress = true
        try {
            // 文档写入经 WriteCommandAction（平台线程检查强制 write action）
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(sql)
            }
            editor.caretModel.moveToOffset(0)
        } finally {
            suppress = false
        }
    }

    /** 自建 autopopup（T-M27.2 实证接线，逻辑照抄 SqlPreviewPanel.bindAutoPopup）。 */
    private fun bindAutoPopup(created: Editor) {
        val listener = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                if (suppress) return
                if (event.newLength == 0 || event.oldLength > event.newLength) return
                val ed = editor
                if (ed.isDisposed || project.isDisposed) return
                // 已有有效项的 lookup（用户选中插入回环）不重复调度
                val active = LookupManager.getActiveLookup(ed)
                if (active != null && active.items.isNotEmpty()) return
                if (autoPopupScheduled) return
                // 仅标识符字符触发（中文输入等场景不弹）
                val insertEnd = event.offset + event.newLength
                if (insertEnd <= 0) return
                val prev = ed.document.getText(TextRange.create(insertEnd - 1, insertEnd)).firstOrNull() ?: return
                if (!prev.isLetterOrDigit() && prev != '_') return
                autoPopupScheduled = true
                ApplicationManager.getApplication().invokeLater {
                    autoPopupScheduled = false
                    val ed2 = editor
                    if (ed2.isDisposed || project.isDisposed) return@invokeLater
                    val active2 = LookupManager.getActiveLookup(ed2)
                    if (active2 != null && active2.items.isNotEmpty()) return@invokeLater
                    try {
                        // 先提交文档（PSI 同步）——补全前缀按编辑器文档计算，未 commit 时按旧 PSI
                        // 文本算前缀导致误过滤
                        PsiDocumentManager.getInstance(project).commitDocument(ed2.document)
                        CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
                            .invokeCompletion(project, ed2, 1, false)
                    } catch (t: Throwable) {
                        // 补全异常不影响输入（弹窗失败下次输入重试）
                    }
                }
            }
        }
        created.document.addDocumentListener(listener, this)
        autoPopupListener = listener
    }

    /** 释放编辑器（幂等）。 */
    override fun dispose() {
        if (released) return
        released = true
        autoPopupListener?.let { l -> editor.document.removeDocumentListener(l) }
        autoPopupListener = null
        EditorFactory.getInstance().releaseEditor(editor)
        psiFile = null
    }

    private var released = false

    companion object {
        /** 解析 SQL 文件类型（测试环境无 Database 插件时返回 null）。 */
        @JvmStatic
        fun resolveSqlFileType(): com.intellij.openapi.fileTypes.FileType? {
            return com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .findFileTypeByName("SQL")
        }
    }
}
