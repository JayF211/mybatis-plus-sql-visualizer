package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor
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
import com.intellij.openapi.editor.ex.EditorEx
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
 *  - 原生 SQL FileType PSI 文件支撑 → 挂 [SqlCompletionContributor]（language="SQL" + IS_PREVIEW_EDITOR 守卫）提供补全；
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

    private var autoPopupScheduled = false

    /** 程序性写入（setSql）期间抑制自建 autopopup（对齐 T-M27.4）。 */
    private var suppress = false

    private val editorHolder = JBPanel<JBPanel<*>>(BorderLayout())

    val editor: Editor = createEditor(initialSql)

    /** DataProvider 包装后的组件（供面板布局；补全经此拿 CommonDataKeys.EDITOR）。 */
    val component: JComponent = editorHolder

    val text: String get() = editor.document.text

    /** 创建编辑器：原生 SQL FileType PSI → 文档 → 可编辑编辑器 + 高亮 + 补全上下文双载体 + autopopup。
     *
     * T-Perf-07：`PsiFileFactory.createFileFromText` 在 EDT 直调触发 262 平台 read access 硬抛
     * （markGenerated 树遍历）——包 [ReadAction.compute]（EDT 上同步语义不变），
     * 对齐 [JavaCodeFormatter] 与 SqlPreviewPanel 后台预热的既有修复（T-M42 漏点）。 */
    private fun createEditor(sql: String): Editor {
        val sqlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            .getStdFileType("SQL")
        val created = if (project.isDisposed || sqlFileType == null) {
            EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql))
        } else {
            @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
            val psi = com.intellij.openapi.application.ReadAction.compute<PsiFile, Throwable> {
                PsiFileFactory.getInstance(project)
                    .createFileFromText("input.sql", sqlFileType, sql)
            }
            psiFile = psi
            val doc = PsiDocumentManager.getInstance(project).getDocument(psi)
                ?: EditorFactory.getInstance().createDocument(sql)
            EditorFactory.getInstance().createEditor(doc, project, sqlFileType, false)
        }
        (created as EditorEx).apply {
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
            // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
            EditorWhitespaceSettings.applyTo(this)
        }
        // 补全上下文双载体（M16.2）：Editor userData 为主载体 + PSI 文件 userData 兜底
        val ctx = SqlCompletionContext(null, emptyList(), null, emptyMap(), null, emptyList())
        created.putUserData(SqlCompletionContext.KEY, ctx)
        psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
        // DataProvider 包装补 EDITOR（M27.1，实证，见类注释）
        editorHolder.add(
            object : JBPanel<JBPanel<*>>(BorderLayout()), DataProvider {
                override fun getData(dataId: String): Any? =
                    if (CommonDataKeys.EDITOR.`is`(dataId)) created else null
            }.apply { add(created.component, BorderLayout.CENTER) },
            BorderLayout.CENTER,
        )
        bindAutoPopup(created)
        // T-AutoFold F4-d：构造初始内容按当前设置应用折叠（仅程序性写入重算，用户打字不触发）
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(created, sql)
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
        // T-AutoFold F4-d：程序性写入后按当前设置重建折叠（用户逐字打字不经过此处，不重算）
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(editor, sql)
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
                // write-safe: 本拍只由**用户输入活动**派生——documentChanged 在用户键入/粘贴的那次
                // 活动内同步派发（本编辑器的唯一程序性写入 setSql 经 suppress 抑制，不调度补全），
                // 无进度模态时平台取模态链（defaultModalityImpl：进度模态 → 上下文模态 → NON_MODAL）
                // 落 NON_MODAL（恒 write-safe，见 DocWriteContext KDoc）；且 commitDocument 只把文档
                // 变更同步进 PSI、不改文档文本。改投递为 nonModal 会把补全弹窗延后到模态关闭后，
                // 用户输入期间的补全语义退化——故按平台语义显式声明豁免而非改投递。
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
    }

    /** 释放编辑器（幂等）。 */
    override fun dispose() {
        if (released) return
        released = true
        // T-RA-04 判定：listener 经 addDocumentListener(listener, this) 以本对象为父 Disposable
        // 注册，宿主 SqlToJavaPanel 已把它注册进 Disposer 树（SqlToJavaPanel.kt:180
        // `Disposer.register(this, inputEditor)`），释放时由平台自动摘除 listener——
        // 因此这里既不能也不必手动 removeDocumentListener（listener 已被自动移除后再摘会抛
        // "Can't remove document listener"）。原先用于记账的 autoPopupListener 字段全仓零读取，
        // 已随本次审计删除，注册方式与生命周期约束不变。
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
