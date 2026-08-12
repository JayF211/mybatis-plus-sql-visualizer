package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * SQL 预览面板（M8-02/03/04 重构版，F-07/F-08）。
 *
 * 弹窗 [SqlPreviewDialog] 与工具窗口共用本面板，展示：
 *  - 顶部：元信息（Wrapper 类型 / 表名 / 风险警示）+ 工具栏（复制 / 单行多行切换 / 刷新）
 *  - 中部左右分栏：左侧参数对照表（`#{param}` 名 + 值），右侧只读 SQL 编辑器视图
 *  - 只读视图经 [LexerEditorHighlighter] + [SqlSyntaxHighlighter] 提供语法高亮与行号，
 *    配色随 IDE 亮/暗主题切换（M8-01）。
 *
 * 说明：编辑器视图生命周期在 [removeNotify] 时释放，避免泄漏（EditorFactory.releaseEditor）。
 */
class SqlPreviewPanel(private val project: Project? = null) : JBPanel<SqlPreviewPanel>(BorderLayout()) {

    private val metaLabel = JBLabel()

    // 左：参数对照表（只读）
    private val paramModel = object : DefaultTableModel(arrayOf<Any>(PARAM_COL_NAME, PARAM_COL_VALUE), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val paramTable = JBTable(paramModel).apply {
        rowSelectionAllowed = true
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        preferredScrollableViewportSize = Dimension(240, 200)
    }

    // 右：只读编辑器视图（持有以释放）
    private var editor: Editor? = null
    private val editorHolder = JBPanel<JBPanel<*>>(BorderLayout())

    private var current: PreviewResult? = null
    private var compactMode = false

    init {
        // 顶部元信息行 + 工具栏
        metaLabel.border = JBUI.Borders.empty(0, 8, 4, 8)
        val topRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(metaLabel, BorderLayout.WEST)
            add(toolbar(), BorderLayout.EAST)
            border = JBUI.Borders.empty(6, 0, 4, 8)
        }
        add(topRow, BorderLayout.NORTH)

        // 中部左右分栏：参数对照 | SQL 编辑器
        val paramPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel(MpSqlVisualizerBundle.message("panel.param.title")).apply {
                border = JBUI.Borders.empty(4, 8, 4, 8)
            }, BorderLayout.NORTH)
            add(JBScrollPane(paramTable), BorderLayout.CENTER)
            preferredSize = Dimension(260, 200)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, paramPanel, editorHolder).apply {
            resizeWeight = 0.3
            isContinuousLayout = true
            isOneTouchExpandable = true
        }
        add(split, BorderLayout.CENTER)
    }

    private lateinit var modeButton: JButton

    /**
     * 顶部工具栏：复制 / 单行多行切换 / 刷新。
     */
    private fun toolbar(): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(smallButton(MpSqlVisualizerBundle.message("panel.copy")) { copySql() })
        modeButton = smallButton(modeButtonText()) {
            toggleCompact()
            modeButton.text = modeButtonText()
        }
        add(modeButton)
        add(smallButton(MpSqlVisualizerBundle.message("panel.refresh")) { refreshView() })
    }

    /** 单行/多行切换按钮文案：显示"将要切换到的模式"。 */
    private fun modeButtonText(): String = MpSqlVisualizerBundle.message(
        if (compactMode) "panel.mode.multiline" else "panel.mode.singleline",
    )

    private fun smallButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
        font = font.deriveFont(11f)
        addActionListener { action() }
    }

    /**
     * 更新展示内容。
     *
     * @param result 预览结果
     */
    fun showResult(result: PreviewResult) {
        current = result
        showSql(if (compactMode) result.compactSql else result.sql)
        updateMeta(result)
        updateParams(result)
    }

    /** 刷新视图：按当前单行/多行模式重渲染 SQL 编辑器（保留内容与高亮）。 */
    fun refreshView() {
        current?.let { showSql(if (compactMode) it.compactSql else it.sql) }
    }

    /**
     * 单行 / 多行切换（复用 F-03 单行与格式化输出）。
     *
     * @return 切换后的模式（true=单行）
     */
    fun toggleCompact(): Boolean {
        compactMode = !compactMode
        current?.let { showSql(if (compactMode) it.compactSql else it.sql) }
        return compactMode
    }

    /**
     * 一键复制当前 SQL 到剪贴板（F-08）。
     *
     * @return 是否复制成功（无内容时返回 false）
     */
    fun copySql(): Boolean {
        val text = current?.sql ?: return false
        if (text.isBlank()) return false
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        project?.let {
            Messages.showInfoMessage(it, MpSqlVisualizerBundle.message("dialog.copy.done"), MpSqlVisualizerBundle.message("plugin.name"))
        }
        return true
    }

    /**
     * 清空展示区（工具窗口"清空"按钮）。
     */
    fun clear() {
        current = null
        releaseEditor()
        editorHolder.removeAll()
        metaLabel.text = ""
        paramModel.setRowCount(0)
        revalidate()
        repaint()
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /** 渲染 SQL 到只读编辑器视图（替换旧视图）。 */
    private fun showSql(sql: String) {
        releaseEditor()
        val document = EditorFactory.getInstance().createDocument(sql)
        val viewer = if (project != null) {
            EditorFactory.getInstance().createViewer(document, project)
        } else {
            EditorFactory.getInstance().createViewer(document)
        }
        (viewer as EditorEx).apply {
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
        editorHolder.add(viewer.component, BorderLayout.CENTER)
        editorHolder.revalidate()
        editorHolder.repaint()
        editor = viewer
    }

    /** 释放当前只读编辑器（幂等）。 */
    private fun releaseEditor() {
        editor?.let {
            EditorFactory.getInstance().releaseEditor(it)
            editor = null
        }
    }

    /** 顶部元信息：Wrapper 类型 / 表名 / 风险警示（UPDATE/DELETE 全表操作置红）。 */
    private fun updateMeta(result: PreviewResult) {
        val meta = buildList {
            result.wrapperType?.let { add("${MpSqlVisualizerBundle.message("panel.meta.type")}: ${it.name}") }
            result.tableName?.let { add("${MpSqlVisualizerBundle.message("panel.meta.table")}: $it") }
        }.joinToString("   ")
        val warningLine = result.warning?.let { "   ⚠ $it" } ?: ""
        metaLabel.text = meta + warningLine
        metaLabel.foreground = if (result.warning != null) JBColor.RED else JBUI.CurrentTheme.Label.foreground()
    }

    /** 参数对照表：未解析参数来源 → `?`（运行时值无法静态求值）。 */
    private fun updateParams(result: PreviewResult) {
        paramModel.setRowCount(0)
        result.unresolvedParams.forEach { source ->
            paramModel.addRow(arrayOf<Any>("#$source", "?"))
        }
        if (result.unresolvedParams.isEmpty()) {
            paramModel.addRow(arrayOf<Any>("-", MpSqlVisualizerBundle.message("panel.param.none")))
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        releaseEditor()
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    internal fun editorForTest(): Editor? = editor
    internal fun currentForTest(): PreviewResult? = current
    internal fun compactForTest(): Boolean = compactMode

    companion object {
        private const val PARAM_COL_NAME = "#参数"
        private const val PARAM_COL_VALUE = "值"
    }
}
