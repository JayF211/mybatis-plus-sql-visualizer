package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlPreviewLanguage
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JSplitPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * SQL 预览面板（M9-01/02/03 可编辑版，F-07/F-08）。
 *
 * 弹窗 [SqlPreviewDialog] 与工具窗口共用本面板，展示：
 *  - 顶部：元信息（Wrapper 类型 / 表名 / 风险警示）+ 工具栏（复制 / 单行多行切换 / 刷新）
 *  - 中部左右分栏：左侧**参数值输入框**（`#param` → 值，输入实时回填 SQL），右侧**可编辑 SQL 编辑器**
 *  - SQL 编辑器：`EditorFactory.createEditor`（可写）+ `LexerEditorHighlighter` 语法高亮；
 *    文档由 `SqlPreviewLanguage` 的 PSI 文件支撑，挂 [SqlCompletionContributor] 提供补全（Ctrl+Space）
 *  - 补全上下文（表名/列名）经 PSI userData 传递（[SqlCompletionContext]）
 *
 * 说明：编辑器生命周期在 [removeNotify] 时释放，避免泄漏（EditorFactory.releaseEditor）。
 */
class SqlPreviewPanel(private val project: Project? = null) : JBPanel<SqlPreviewPanel>(BorderLayout()) {

    private val metaLabel = JBLabel()

    // 左：参数值输入区（每个未解析参数一行）
    private val paramPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val paramFields = mutableListOf<JBTextField>()

    // 右：可编辑 SQL 编辑器（PSI 支撑，补全用）
    private var editor: Editor? = null
    private var psiFile: PsiFile? = null
    private val editorHolder = JBPanel<JBPanel<*>>(BorderLayout())

    private var current: PreviewResult? = null
    private var compactMode = false
    private var paramValues = mutableListOf<String>()

    init {
        // 顶部元信息行 + 工具栏
        metaLabel.border = JBUI.Borders.empty(0, 8, 4, 8)
        val topRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(metaLabel, BorderLayout.WEST)
            add(toolbar(), BorderLayout.EAST)
            border = JBUI.Borders.empty(6, 0, 4, 8)
        }
        add(topRow, BorderLayout.NORTH)

        // 中部左右分栏：参数输入 | SQL 编辑器
        val paramScroll = JBScrollPane(paramPanel).apply {
            preferredSize = Dimension(240, 200)
            isOpaque = false
            viewport.isOpaque = false
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, paramScroll, editorHolder).apply {
            resizeWeight = 0.3
            isContinuousLayout = true
            isOneTouchExpandable = false
            dividerSize = 3
            // 分隔条用 IDE 面板底色（避免亮/暗主题下出现白色割裂带）
            background = UIUtil.getPanelBackground()
        }
        add(split, BorderLayout.CENTER)
    }

    private lateinit var modeButton: JButton

    /** 顶部工具栏：复制 / 单行多行切换 / 刷新。 */
    private fun toolbar(): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(smallButton(MpSqlVisualizerBundle.message("panel.copy")) { copySql() })
        modeButton = smallButton(modeButtonText()) {
            toggleCompact()
            modeButton.text = modeButtonText()
        }
        add(modeButton)
        add(smallButton(MpSqlVisualizerBundle.message("panel.format")) { formatSql() })
        add(smallButton(MpSqlVisualizerBundle.message("panel.refresh")) { refreshView() })
    }

    private fun smallButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
        font = font.deriveFont(11f)
        addActionListener { action() }
    }

    /** 单行/多行切换按钮文案：显示"将要切换到的模式"。 */
    private fun modeButtonText(): String = MpSqlVisualizerBundle.message(
        if (compactMode) "panel.mode.multiline" else "panel.mode.singleline",
    )

    // ------------------------------------------------------------------
    // 展示与渲染
    // ------------------------------------------------------------------

    /**
     * 更新展示内容。
     *
     * @param result 预览结果
     */
    fun showResult(result: PreviewResult) {
        current = result
        paramValues = MutableList(result.unresolvedParams.size) { "" }
        rebuildParamPanel(result)
        refreshCompletionContext()
        renderSql()
        updateMeta(result)
    }

    /** 刷新 PSI 文件上的补全上下文（表名/列名，可能随新预览变化）。 */
    private fun refreshCompletionContext() {
        val psi = psiFile ?: return
        psi.putUserData(
            SqlCompletionContext.KEY,
            SqlCompletionContext(
                tableName = current?.tableName,
                columns = current?.columns ?: emptyList(),
                tableAlias = null,
            ),
        )
    }

    /** 按当前单行/多行模式 + 参数回填重渲染 SQL 编辑器。 */
    private fun renderSql() {
        val base = current?.let { if (compactMode) it.compactSql else it.sql } ?: return
        showSql(backfillParams(base, paramValues))
    }

    /** 刷新视图（保留参数与模式，重渲染编辑器）。 */
    fun refreshView() = renderSql()

    /** 格式化当前编辑器 SQL（关键字大写 / 子句换行 / 条件缩进，[SqlFormatter]）。 */
    fun formatSql() {
        val editor = editor ?: return
        val formatted = SqlFormatter.format(editor.document.text)
        if (formatted != editor.document.text) {
            WriteCommandAction.runWriteCommandAction(project) { editor.document.setText(formatted) }
        }
    }

    /** 单行 / 多行切换。 */
    fun toggleCompact(): Boolean {
        compactMode = !compactMode
        renderSql()
        return compactMode
    }

    /** 一键复制当前 SQL（取编辑器当前内容，含手动编辑与参数回填）。 */
    fun copySql(): Boolean {
        val text = editor?.document?.text?.takeIf { it.isNotBlank() } ?: return false
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        project?.let {
            Messages.showInfoMessage(it, MpSqlVisualizerBundle.message("dialog.copy.done"), MpSqlVisualizerBundle.message("plugin.name"))
        }
        return true
    }

    /** 清空展示区。 */
    fun clear() {
        current = null
        paramValues.clear()
        paramFields.clear()
        paramPanel.removeAll()
        releaseEditor()
        editorHolder.removeAll()
        metaLabel.text = ""
        revalidate()
        repaint()
    }

    // ------------------------------------------------------------------
    // 编辑器（PSI 支撑 + 可编辑 + 高亮）
    // ------------------------------------------------------------------

    /** 渲染 SQL 到可编辑编辑器；已存在时更新文档（保留焦点/撤销）。 */
    private fun showSql(sql: String) {
        val existing = editor
        if (existing != null) {
            if (existing.document.text != sql) {
                WriteCommandAction.runWriteCommandAction(project) { existing.document.setText(sql) }
            }
            return
        }
        createEditor(sql)
    }

    /** 首次创建：SqlPreviewLanguage PSI 文件 → 文档 → 可编辑编辑器，附补全上下文。 */
    private fun createEditor(sql: String) {
        val proj = project
        val created = if (proj != null) {
            val psi = PsiFileFactory.getInstance(proj)
                .createFileFromText("preview.mpsql", SqlPreviewLanguage, sql)
            psi.putUserData(
                SqlCompletionContext.KEY,
                SqlCompletionContext(
                    tableName = current?.tableName,
                    columns = current?.columns ?: emptyList(),
                    tableAlias = null,
                ),
            )
            psiFile = psi
            val doc = PsiDocumentManager.getInstance(proj).getDocument(psi)
                ?: EditorFactory.getInstance().createDocument(sql)
            EditorFactory.getInstance().createEditor(doc, proj)
        } else {
            EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql))
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
        editorHolder.removeAll()
        editorHolder.add(created.component, BorderLayout.CENTER)
        editorHolder.revalidate()
        editorHolder.repaint()
        editor = created
    }

    /** 释放当前编辑器（幂等）。 */
    private fun releaseEditor() {
        editor?.let {
            EditorFactory.getInstance().releaseEditor(it)
            editor = null
        }
        psiFile = null
    }

    // ------------------------------------------------------------------
    // 参数输入与回填
    // ------------------------------------------------------------------

    /** 重建左侧参数输入区：`#<source>` 标签 + 值输入框。 */
    private fun rebuildParamPanel(result: PreviewResult) {
        paramPanel.removeAll()
        paramFields.clear()
        val params = result.unresolvedParams
        if (params.isEmpty()) {
            paramPanel.add(JBLabel(MpSqlVisualizerBundle.message("panel.param.none")).apply {
                border = JBUI.Borders.empty(6, 8)
            })
        } else {
            params.forEachIndexed { index, source ->
                val field = JBTextField(8).apply {
                    toolTipText = "#$source"
                    document.addDocumentListener(object : DocumentListener {
                        override fun insertUpdate(e: DocumentEvent?) = onParamChanged(index, this@apply.text)
                        override fun removeUpdate(e: DocumentEvent?) = onParamChanged(index, this@apply.text)
                        override fun changedUpdate(e: DocumentEvent?) = onParamChanged(index, this@apply.text)
                    })
                }
                // FlowLayout：字段按 preferred 宽度（不随行拉伸），行紧凑
                val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                    add(JBLabel("#$source").apply {
                        preferredSize = Dimension(140, preferredSize.height)
                    })
                    add(field)
                    border = JBUI.Borders.empty(0, 8, 2, 8)
                }
                paramPanel.add(row)
                paramFields += field
            }
        }
        paramPanel.revalidate()
        paramPanel.repaint()
    }

    /** 参数值变更：更新回填表并重渲染 SQL。 */
    private fun onParamChanged(index: Int, value: String) {
        if (index in paramValues.indices) {
            paramValues[index] = value
            renderSql()
        }
    }

    /**
     * 按顺序把 SQL 中的 `?` 替换为用户输入的参数值。
     * 字符串加单引号（转义 `''`）、数字原样、空值保留 `?`。
     */
    private fun backfillParams(sql: String, values: List<String>): String {
        if (values.isEmpty()) return sql
        val sb = StringBuilder(sql.length + 32)
        var valueIndex = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '?') {
                val v = values.getOrNull(valueIndex)
                sb.append(if (v.isNullOrBlank()) "?" else renderParamValue(v))
                valueIndex++
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun renderParamValue(value: String): String {
        val trimmed = value.trim()
        return if (NUMBER_REGEX.matches(trimmed)) trimmed else "'" + value.replace("'", "''") + "'"
    }

    // ------------------------------------------------------------------
    // 元信息
    // ------------------------------------------------------------------

    private fun updateMeta(result: PreviewResult) {
        val meta = buildList {
            result.wrapperType?.let { add("${MpSqlVisualizerBundle.message("panel.meta.type")}: ${it.name}") }
            result.tableName?.let { add("${MpSqlVisualizerBundle.message("panel.meta.table")}: $it") }
        }.joinToString("   ")
        val warningLine = result.warning?.let { "   ⚠ $it" } ?: ""
        metaLabel.text = meta + warningLine
        metaLabel.foreground = if (result.warning != null) JBColor.RED else JBUI.CurrentTheme.Label.foreground()
    }

    override fun removeNotify() {
        super.removeNotify()
        releaseEditor()
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    internal fun editorForTest(): Editor? = editor
    internal fun psiFileForTest(): PsiFile? = psiFile
    internal fun currentForTest(): PreviewResult? = current
    internal fun compactForTest(): Boolean = compactMode
    internal fun paramFieldForTest(index: Int): JBTextField? = paramFields.getOrNull(index)

    companion object {
        private val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")
    }
}
