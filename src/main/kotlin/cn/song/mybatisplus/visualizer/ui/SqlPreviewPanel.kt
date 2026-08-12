package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.EmptyValueRule
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
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
import javax.swing.JCheckBox
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

    // 左：动态条件复选框 + 参数值输入区
    private val paramPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val paramFields = mutableListOf<JBTextField>()
    private val conditionCheckBoxes = mutableListOf<JCheckBox>()
    private val conditionValueFields = mutableListOf<JBTextField>()

    // 右：可编辑 SQL 编辑器（PSI 支撑，补全用）
    private var editor: Editor? = null
    private var psiFile: PsiFile? = null
    private val editorHolder = JBPanel<JBPanel<*>>(BorderLayout())

    private var current: PreviewResult? = null
    private var compactMode = false

    // M12：动态条件启用集合（勾选状态，元素为 ConditionToggle.id）+ 参数值（按 source 绑定，取代位置绑定）
    private val enabledIds = mutableSetOf<String>()
    private val paramValues = mutableMapOf<String, String>()

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
        // 参数值按 source 初始化（空字符串；多条件复用同一 source 共享一个值）
        paramValues.clear()
        result.unresolvedParams.forEach { source -> paramValues.putIfAbsent(source, "") }
        result.previewModel?.conditionViews.orEmpty().forEach { view -> view.valueSource?.let { paramValues.putIfAbsent(it, "") } }
        // 动态条件启用集合初始化：默认按 defaultEnabled，并应用空值联动——判空（SKIP_ON_BLANK/UNKNOWN）
        // 条件值输入框为空 → 初始不勾选（不拼接）；判非 null（SKIP_ON_NULL）空值仍勾选（拼接 ''）
        enabledIds.clear()
        result.previewModel?.conditionViews.orEmpty().forEach { view ->
            val valueBlank = view.valueSource?.let { paramValues[it].isNullOrBlank() } ?: false
            val skippedByBlank = valueBlank && view.emptyRule != EmptyValueRule.SKIP_ON_NULL
            if (view.defaultEnabled && !skippedByBlank) enabledIds.add(view.id)
        }
        // 模型级重生成路径：初始单行/多行与格式化设置对齐（旧文本路径保持默认多行）
        result.previewModel?.let { compactMode = !it.formatSql }
        modeButton.text = modeButtonText()
        rebuildLeftPanel(result)
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

    /** 按当前单行/多行模式 + 动态条件启用集合 + 参数回填重渲染 SQL 编辑器。 */
    private fun renderSql() {
        val result = current ?: return
        val model = result.previewModel
        if (model != null) {
            // 模型级重生成：按动态条件启用集合重新生成 SQL，再按 source 回填参数
            val generated = SqlGenerator.generateWithParams(
                statement = model.statement,
                compact = compactMode,
                dialect = model.dialect,
                enabledConditionIds = enabledIds.toSet(),
            )
            showSql(backfillParamsBySource(generated.sql, generated.unresolvedParams))
        } else {
            // 旧文本路径（无可勾选动态条件，如批量操作）
            val base = if (compactMode) result.compactSql else result.sql
            showSql(backfillParamsBySource(base, result.unresolvedParams))
        }
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
        enabledIds.clear()
        paramValues.clear()
        paramFields.clear()
        conditionCheckBoxes.clear()
        conditionValueFields.clear()
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
    // 参数输入与回填（M12：动态条件复选框行 + 按 source 绑定 + 空值语义）
    // ------------------------------------------------------------------

    /** 重建左侧面板：动态条件复选框行 + 普通参数值输入行。 */
    private fun rebuildLeftPanel(result: PreviewResult) {
        paramPanel.removeAll()
        paramFields.clear()
        conditionCheckBoxes.clear()
        conditionValueFields.clear()
        val views = result.previewModel?.conditionViews.orEmpty()

        if (views.isEmpty() && result.unresolvedParams.isEmpty()) {
            paramPanel.add(JBLabel(MpSqlVisualizerBundle.message("panel.param.none")).apply {
                border = JBUI.Borders.empty(6, 8)
            })
            paramPanel.revalidate()
            paramPanel.repaint()
            return
        }

        val viewSources = views.mapNotNull { it.valueSource }.toSet()
        if (views.isNotEmpty()) {
            paramPanel.add(sectionLabel(MpSqlVisualizerBundle.message("panel.condition.section")))
            views.forEach { paramPanel.add(buildConditionRow(it)) }
        }
        // 普通参数行：未解析参数中不属于任何动态条件值输入框的来源（去重，同一 source 共享一个输入框）
        val ordinarySources = result.unresolvedParams.distinct().filter { it !in viewSources }
        if (ordinarySources.isNotEmpty()) {
            if (views.isNotEmpty()) paramPanel.add(sectionLabel(MpSqlVisualizerBundle.message("panel.param.section")))
            ordinarySources.forEach { paramPanel.add(buildParamRow(it)) }
        }
        paramPanel.revalidate()
        paramPanel.repaint()
    }

    /** 分区标题行。 */
    private fun sectionLabel(text: String): JBLabel = JBLabel(text).apply {
        border = JBUI.Borders.empty(6, 8, 2, 8)
    }

    /** 动态条件行：复选框（勾选开关）+（有值来源时）值输入框。 */
    private fun buildConditionRow(view: ConditionView): JBPanel<JBPanel<*>> {
        val checkBox = JCheckBox(view.label, view.id in enabledIds).apply {
            toolTipText = conditionTooltip(view)
            addActionListener {
                if (isSelected) enabledIds.add(view.id) else enabledIds.remove(view.id)
                renderSql()
            }
        }
        conditionCheckBoxes += checkBox
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(checkBox)
            if (view.valueSource != null) {
                val field = conditionValueField(view)
                add(field)
                paramFields += field
                conditionValueFields += field
            }
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    /** 动态条件值输入框：值变更联动勾选态与 SQL 重渲染。 */
    private fun conditionValueField(view: ConditionView): JBTextField = JBTextField(8).apply {
        toolTipText = "#${view.valueSource}"
        text = paramValues[view.valueSource] ?: ""
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onConditionValueChanged(view, this@apply.text)
            override fun removeUpdate(e: DocumentEvent?) = onConditionValueChanged(view, this@apply.text)
            override fun changedUpdate(e: DocumentEvent?) = onConditionValueChanged(view, this@apply.text)
        })
    }

    /** 动态条件复选框 tooltip：布尔条件源码 + 空值语义提示。 */
    private fun conditionTooltip(view: ConditionView): String = buildList {
        view.flagSource?.let { add(MpSqlVisualizerBundle.message("panel.condition.flag", it)) }
        if (view.emptyRule == EmptyValueRule.SKIP_ON_NULL) {
            add(MpSqlVisualizerBundle.message("panel.condition.skipNull"))
        }
    }.joinToString("   ")

    /**
     * 动态条件值变更（M12）：更新按 source 绑定的参数值，并按判空语义联动勾选态。
     *  - SKIP_ON_BLANK / UNKNOWN：空值 → 自动取消勾选（不拼接）；填写 → 勾选（拼接）
     *  - SKIP_ON_NULL：空值仍拼接（渲染 `''`），勾选态仅由手动切换决定
     */
    private fun onConditionValueChanged(view: ConditionView, text: String) {
        val source = view.valueSource ?: return
        paramValues[source] = text
        if (view.emptyRule != EmptyValueRule.SKIP_ON_NULL) {
            if (text.isBlank()) enabledIds.remove(view.id) else enabledIds.add(view.id)
        }
        renderSql()
    }

    /** 普通参数行：`#<source>` 标签 + 值输入框。 */
    private fun buildParamRow(source: String): JBPanel<JBPanel<*>> {
        val field = JBTextField(8).apply {
            toolTipText = "#$source"
            text = paramValues[source] ?: ""
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onParamChanged(source, this@apply.text)
                override fun removeUpdate(e: DocumentEvent?) = onParamChanged(source, this@apply.text)
                override fun changedUpdate(e: DocumentEvent?) = onParamChanged(source, this@apply.text)
            })
        }
        paramFields += field
        // FlowLayout：字段按 preferred 宽度（不随行拉伸），行紧凑
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JBLabel("#$source").apply {
                preferredSize = Dimension(140, preferredSize.height)
            })
            add(field)
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    /** 普通参数值变更：更新回填表并重渲染 SQL。 */
    private fun onParamChanged(source: String, value: String) {
        paramValues[source] = value
        renderSql()
    }

    /**
     * 按出现顺序把 SQL 中的 `?` 替换为用户输入（按 source 定位参数值，取代位置绑定，见 M12-R2）。
     * 字符串加单引号（转义 `''`）、数字原样、空值保留 `?`；
     * 判非 null 动态条件（SKIP_ON_NULL）的 source 空值渲染为 `''`（空字符串是合法值，M12）。
     */
    private fun backfillParamsBySource(sql: String, sources: List<String>): String {
        if (sources.isEmpty()) return sql
        val blankAsEmpty = current?.previewModel?.conditionViews.orEmpty()
            .filter { it.emptyRule == EmptyValueRule.SKIP_ON_NULL && it.valueSource != null }
            .mapNotNull { it.valueSource }
            .toSet()
        val sb = StringBuilder(sql.length + 32)
        var paramIndex = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '?') {
                val source = sources.getOrNull(paramIndex)
                val v = source?.let { paramValues[it] }
                sb.append(renderParamValue(v, source, blankAsEmpty))
                paramIndex++
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun renderParamValue(v: String?, source: String?, blankAsEmpty: Set<String>): String {
        if (v.isNullOrBlank()) {
            return if (source != null && source in blankAsEmpty) "''" else "?"
        }
        val trimmed = v.trim()
        return if (NUMBER_REGEX.matches(trimmed)) trimmed else "'" + v.replace("'", "''") + "'"
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
    internal fun conditionCheckboxForTest(index: Int): JCheckBox? = conditionCheckBoxes.getOrNull(index)
    internal fun conditionValueFieldForTest(index: Int): JBTextField? = conditionValueFields.getOrNull(index)
    internal fun enabledIdsForTest(): Set<String> = enabledIds.toSet()

    companion object {
        private val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")
    }
}
