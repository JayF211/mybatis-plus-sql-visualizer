package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport
import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlPreviewLanguage
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlBindView
import cn.song.mybatisplus.visualizer.xml.XmlConditionView
import cn.song.mybatisplus.visualizer.xml.XmlForEachView
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlPreviewState
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
 *  - 补全上下文（表名/列名）双载体传递（[SqlCompletionContext]）：**Editor userData 优先**（M16.2，
 *    嵌入式编辑器编辑后 PSI 可能被 commit 重建丢失 userData）+ **PSI userData 兜底**
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
    // T-M23-04：批量列级勾选复选框（INSERT/UPDATE/UPSERT 参数列，与动态条件复选框互斥——批量预览无 conditionViews）
    private val batchCheckBoxes = mutableListOf<JCheckBox>()
    private var selectAllButton: JButton? = null
    private var selectNoneButton: JButton? = null
    // T-M23.2：批量「参数列」区全选/全不选按钮
    private var batchSelectAllButton: JButton? = null
    private var batchSelectNoneButton: JButton? = null

    // 右：可编辑 SQL 编辑器（PSI 支撑，补全用）
    private var editor: Editor? = null
    private var psiFile: PsiFile? = null
    private val editorHolder = JBPanel<JBPanel<*>>(BorderLayout())

    private var current: PreviewResult? = null
    private var compactMode = false

    // M12：动态条件启用集合（勾选状态，元素为 ConditionToggle.id）+ 参数值（按 source 绑定，取代位置绑定）
    private val enabledIds = mutableSetOf<String>()
    private val paramValues = mutableMapOf<String, String>()
    // T-M24-04：XML 预览渲染状态（条件开关复用 enabledIds，id = test 表达式原文）
    private val xmlForEachCounts = mutableMapOf<String, Int>()
    private val xmlForEachNulls = mutableSetOf<String>()
    private val xmlBindOverrides = mutableMapOf<String, String>()
    private var xmlErrors = listOf<String>()
    // T-M25.3：错误信息复制按钮（warning 存在时可见，一键复制完整错误文本）
    private var errorCopyButton: JButton? = null
    private var currentWarning: String? = null

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

    /** 顶部工具栏：复制错误（有警示时可见）/ 单行多行切换 / 格式化 / 刷新（执行 SQL / 复制 SQL 在弹窗底部按钮区，M17.4）。 */
    private fun toolbar(): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        errorCopyButton = smallButton(MpSqlVisualizerBundle.message("panel.copyError")) {
            copyError()
        }.apply { isVisible = false }
        add(errorCopyButton)
        modeButton = smallButton(modeButtonText()) {
            toggleCompact()
            modeButton.text = modeButtonText()
        }
        add(modeButton)
        add(smallButton(MpSqlVisualizerBundle.message("panel.format")) { formatSql() })
        add(smallButton(MpSqlVisualizerBundle.message("panel.refresh")) { refreshView() })
    }

    /**
     * 执行 SQL（M17，M17.4 由弹窗底部按钮区调用）：取当前编辑器 SQL（含参数回填与手动编辑）
     * 交给 [cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport]——选择数据源 → 自动创建
     * 临时文件（Database 控制台绑定）→ 打开可执行。无项目/无内容时不动作；
     * Community（无 database 插件）由支持层提示降级。
     * T-M23.6：成功提示「关闭 SQL 面板」按钮 → 回调 [onClosePanelRequested]（预览弹窗关闭）。
     */
    internal fun executeSql() {
        val proj = project ?: return
        val sql = editor?.document?.text?.takeIf { it.isNotBlank() } ?: return
        ExecuteSqlSupport.execute(proj, sql, onClosePanel = { onClosePanelRequested?.invoke() })
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
        // 动态条件启用集合初始化（M13：复选框纯手动）——仅按 defaultEnabled，值空不联动勾选态
        enabledIds.clear()
        result.previewModel?.conditionViews.orEmpty().forEach { view ->
            if (view.defaultEnabled) enabledIds.add(view.id)
        }
        // T-M23-04：批量列级勾选初始化——按 BatchModel 默认勾选态（列名作 id，与条件 toggle 互斥）
        result.batchModel?.toggleColumns?.forEach { col ->
            if (col.checked) enabledIds.add(col.columnName)
        }
        // T-M24-04：XML 预览状态初始化（条件开关走 enabledIds，按启发式默认勾选；foreach/bind 独立状态）
        result.xmlModel?.let { model ->
            model.conditionViews.forEach { view ->
                if (view.defaultEnabled) enabledIds.add(view.id)
            }
            xmlForEachCounts.clear()
            xmlForEachCounts.putAll(model.defaultState().forEachCounts)
            xmlForEachNulls.clear()
            xmlBindOverrides.clear()
            xmlErrors = emptyList()
        }
        // 模型级重生成路径：初始单行/多行与格式化设置对齐（旧文本路径保持默认多行）
        result.previewModel?.let { compactMode = !it.formatSql }
        modeButton.text = modeButtonText()
        rebuildLeftPanel(result)
        refreshCompletionContext()
        renderSql()
        updateMeta(result)
    }

    /** 刷新补全上下文（表名/列名，可能随新预览变化）——M16.2 双载体：Editor userData 优先 + PSI 兜底。 */
    private fun refreshCompletionContext() {
        val ctx = buildCompletionContext()
        editor?.putUserData(SqlCompletionContext.KEY, ctx)
        psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
    }

    /** 由当前预览结果构造补全上下文（表名/列名/注释；M16.2 抽为复用函数，Editor 与 PSI 同源）。 */
    private fun buildCompletionContext(): SqlCompletionContext = SqlCompletionContext(
        tableName = current?.tableName,
        columns = current?.columns ?: emptyList(),
        tableAlias = null,
        columnComments = current?.columnComments ?: emptyMap(),
        tableComment = current?.tableComment,
    )

    /** 按当前单行/多行模式 + 动态条件启用集合 + 参数回填重渲染 SQL 编辑器。 */
    private fun renderSql() {
        val result = current ?: return
        val model = result.previewModel
        val batch = result.batchModel
        val xml = result.xmlModel
        when {
            // T-M24-04：XML 动态 SQL——渲染单源 XmlPreviewModel.renderSql(state)，勾选/次数/覆盖变更重渲染
            xml != null -> {
                val state = XmlPreviewState(
                    enabledIds = enabledIds.toSet(),
                    forEachCounts = xmlForEachCounts.toMap(),
                    forEachNulls = xmlForEachNulls.toSet(),
                    bindOverrides = xmlBindOverrides.toMap(),
                )
                val rendered = xml.renderSql(state)
                xmlErrors = rendered.errors
                val base = rendered.sql
                val sql = if (compactMode) base.replace(WHITESPACE_REGEX, " ").trim() else base
                showSql(backfillParamsBySource(sql, rendered.mappings.map { it.property }))
            }
            // T-M23-04：批量列级勾选路径——BatchModel.renderSql 按活跃列重建 + 按列序回填
            batch != null -> {
                val active = enabledIds.toSet()
                val sql = batch.renderSql(active)
                showSql(backfillParamsBySource(sql, batch.orderedSources(active), batch.orderedHints(active)))
            }
            model != null -> {
                // 模型级重生成：按动态条件启用集合重新生成 SQL（含回填提示），再按 source 回填参数
                val generated = SqlGenerator.generateWithParams(
                    statement = model.statement,
                    compact = compactMode,
                    dialect = model.dialect,
                    enabledConditionIds = enabledIds.toSet(),
                )
                showSql(backfillParamsBySource(generated.sql, generated.unresolvedParams, generated.paramHints))
            }
            else -> {
                // 旧文本路径（无可勾选动态条件，如批量操作）
                val base = if (compactMode) result.compactSql else result.sql
                showSql(backfillParamsBySource(base, result.unresolvedParams, result.paramHints))
            }
        }
    }

    /**
     * 刷新视图（M19.3）：模式**复位为与设置「SQL 格式化输出」对齐**（默认多行）后重渲染编辑器——
     * 用户实测：点刷新后 SQL 仍停留在单行模式（此前手动切换/设置快照的单行被保留）；
     * 刷新 = 回到默认状态（参数与回填保留，仅模式复位）。
     */
    fun refreshView() {
        compactMode = !formatSqlSetting()
        modeButton.text = modeButtonText()
        renderSql()
    }

    /** 设置「SQL 格式化输出」（false=单行）；环境异常兜底 true（多行）。 */
    private fun formatSqlSetting(): Boolean = runCatching {
        PluginSettings.getInstance().state.formatSql
    }.getOrDefault(true)

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
            // T-M23.6：提示改多按钮——[确定, 关闭 SQL 面板]，点「关闭 SQL 面板」（index 1）→ 回调关闭预览弹窗
            val options = arrayOf(
                MpSqlVisualizerBundle.message("dialog.ok"),
                MpSqlVisualizerBundle.message("dialog.closeSqlPanel"),
            )
            val choice = Messages.showDialog(
                it, MpSqlVisualizerBundle.message("dialog.copy.done"),
                MpSqlVisualizerBundle.message("plugin.name"), options, 0, Messages.getInformationIcon(),
            )
            if (choice == 1) onClosePanelRequested?.invoke()
        }
        return true
    }

    /**
     * T-M23.6：提示「关闭 SQL 面板」按钮回调（由预览弹窗 [cn.song.mybatisplus.visualizer.ui.SqlPreviewDialog]
     * 注入 `dispose()`；null 时提示不出现关闭按钮链路，回调为空操作）。
     */
    var onClosePanelRequested: (() -> Unit)? = null

    /** 清空展示区。 */
    fun clear() {
        current = null
        enabledIds.clear()
        paramValues.clear()
        paramFields.clear()
        conditionCheckBoxes.clear()
        conditionValueFields.clear()
        batchCheckBoxes.clear()
        xmlForEachCounts.clear()
        xmlForEachNulls.clear()
        xmlBindOverrides.clear()
        xmlErrors = emptyList()
        currentWarning = null
        errorCopyButton?.isVisible = false
        selectAllButton = null
        selectNoneButton = null
        batchSelectAllButton = null
        batchSelectNoneButton = null
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

    /** 首次创建：SqlPreviewLanguage PSI 文件 → 文档 → 可编辑编辑器，附补全上下文（Editor + PSI 双载体，M16.2）。 */
    private fun createEditor(sql: String) {
        val proj = project
        val created = if (proj != null) {
            val psi = PsiFileFactory.getInstance(proj)
                .createFileFromText("preview.mpsql", SqlPreviewLanguage, sql)
            psiFile = psi
            val doc = PsiDocumentManager.getInstance(proj).getDocument(psi)
                ?: EditorFactory.getInstance().createDocument(sql)
            EditorFactory.getInstance().createEditor(doc, proj)
        } else {
            EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql))
        }
        // M16.2：补全上下文双载体——Editor userData 为主载体（用户输入后 PSI 可能被 commit 重建而丢失 userData，
        // Editor 对象编辑期间稳定）、PSI 文件 userData 作兜底
        val ctx = buildCompletionContext()
        created.putUserData(SqlCompletionContext.KEY, ctx)
        psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
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

    /** 重建左侧面板：动态条件复选框行 + 普通参数值输入行（T-M23-04：批量列级勾选行）。 */
    private fun rebuildLeftPanel(result: PreviewResult) {
        paramPanel.removeAll()
        paramFields.clear()
        conditionCheckBoxes.clear()
        conditionValueFields.clear()
        batchCheckBoxes.clear()
        val views = result.previewModel?.conditionViews.orEmpty()
        val batchModel = result.batchModel

        if (views.isEmpty() && result.unresolvedParams.isEmpty() && batchModel == null) {
            paramPanel.add(JBLabel(MpSqlVisualizerBundle.message("panel.param.none")).apply {
                border = JBUI.Borders.empty(6, 8)
            })
            paramPanel.revalidate()
            paramPanel.repaint()
            return
        }

        // T-M23-04：批量列级勾选（toggle 列：复选框 + 值输入框，勾选决定列是否进入 SQL；
        // fixed 列：仅值输入框，恒参与）——与动态条件区互斥（批量预览无 conditionViews）
        // T-M23.2（用户要求）：参数可选场景必须有【全选/全不选】按钮
        if (batchModel != null) {
            paramPanel.add(batchSectionHeader())
            batchModel.toggleColumns.forEach { paramPanel.add(buildBatchColumnRow(it, isToggle = true)) }
            batchModel.fixedColumns.forEach { paramPanel.add(buildBatchColumnRow(it, isToggle = false)) }
            paramPanel.revalidate()
            paramPanel.repaint()
            return
        }

        // T-M24-04：XML 预览左栏——动态条件开关 + foreach 次数/null + bind 覆盖值 + 参数值输入
        val xmlModel = result.xmlModel
        if (xmlModel != null) {
            val hasControls = xmlModel.conditionViews.isNotEmpty() ||
                xmlModel.forEachViews.isNotEmpty() || xmlModel.bindViews.isNotEmpty()
            if (!hasControls && result.unresolvedParams.isEmpty()) {
                paramPanel.add(JBLabel(MpSqlVisualizerBundle.message("panel.param.none")).apply {
                    border = JBUI.Borders.empty(6, 8)
                })
                paramPanel.revalidate()
                paramPanel.repaint()
                return
            }
            if (xmlModel.conditionViews.isNotEmpty()) {
                paramPanel.add(conditionSectionHeader())
                xmlModel.conditionViews.forEach { paramPanel.add(buildXmlConditionRow(it)) }
            }
            if (xmlModel.forEachViews.isNotEmpty()) {
                paramPanel.add(sectionLabel(MpSqlVisualizerBundle.message("panel.xml.foreach.section")))
                xmlModel.forEachViews.forEach { paramPanel.add(buildXmlForEachRow(it)) }
            }
            if (xmlModel.bindViews.isNotEmpty()) {
                paramPanel.add(sectionLabel(MpSqlVisualizerBundle.message("panel.xml.bind.section")))
                xmlModel.bindViews.forEach { paramPanel.add(buildXmlBindRow(it)) }
            }
            // #{} 参数值输入（T-M25.4：foreach 展开的 __frch_ 重写名也提供输入框——标签可读化 id[0]/id[1]，
            // source 仍用原始名保证回填匹配）
            val ordinarySources = result.unresolvedParams.distinct()
            if (ordinarySources.isNotEmpty()) {
                if (hasControls) paramPanel.add(sectionLabel(MpSqlVisualizerBundle.message("panel.param.section")))
                ordinarySources.forEach { paramPanel.add(buildXmlParamRow(it)) }
            }
            paramPanel.revalidate()
            paramPanel.repaint()
            return
        }

        val viewSources = views.flatMap { it.valueSources }.toSet()
        if (views.isNotEmpty()) {
            paramPanel.add(conditionSectionHeader())
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

    /**
     * T-M23-04：批量列行——toggle 列渲染「复选框（列来源，tooltip 列名）+ 值输入框」，
     * 勾选变更重渲染（列名入 [enabledIds]）；fixed 列渲染「#来源 标签 + 值输入框」。
     */
    private fun buildBatchColumnRow(col: BatchColumn, isToggle: Boolean): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6))
        if (isToggle) {
            val checkBox = JCheckBox(col.source, col.columnName in enabledIds).apply {
                toolTipText = col.columnName
                addActionListener {
                    if (isSelected) enabledIds.add(col.columnName) else enabledIds.remove(col.columnName)
                    renderSql()
                }
            }
            batchCheckBoxes += checkBox
            row.add(checkBox)
        } else {
            row.add(paramLabel(col.source))
        }
        val field = JBTextField(8).apply {
            toolTipText = "#${col.source}"
            text = paramValues[col.source] ?: ""
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onParamChanged(col.source, this@apply.text)
                override fun removeUpdate(e: DocumentEvent?) = onParamChanged(col.source, this@apply.text)
                override fun changedUpdate(e: DocumentEvent?) = onParamChanged(col.source, this@apply.text)
            })
        }
        paramFields += field
        row.add(field)
        row.border = JBUI.Borders.empty(0, 8, 2, 8)
        return row
    }

    /** 分区标题行。 */
    private fun sectionLabel(text: String): JBLabel = JBLabel(text).apply {
        border = JBUI.Borders.empty(6, 8, 2, 8)
    }

    /**
     * 批量「参数列」分区标题行（T-M23.2，用户要求：参数可选场景必须有【全选/全不选】）：
     * 左侧分区名 + 右侧全选/全不选按钮（操作 [batchCheckBoxes]，与动态条件区按钮互不影响）。
     */
    private fun batchSectionHeader(): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel(MpSqlVisualizerBundle.message("panel.batch.columns")).apply {
            border = JBUI.Borders.empty(4, 8, 0, 8)
        }, BorderLayout.WEST)
        add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 2)).apply {
            batchSelectAllButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll")) { setAllBatchColumns(true) }
            batchSelectNoneButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectNone")) { setAllBatchColumns(false) }
            add(batchSelectAllButton)
            add(batchSelectNoneButton)
        }, BorderLayout.EAST)
        border = JBUI.Borders.empty(0, 8, 2, 8)
    }

    /**
     * 批量列全选/全不选（T-M23.2）：操作 [batchCheckBoxes] 与 [enabledIds]（列名作 id），
     * 同步复选框 UI 与 SQL 重渲染（全不选 → 空列清单形态，文档化边界）。
     */
    private fun setAllBatchColumns(enabled: Boolean) {
        val model = current?.batchModel ?: return
        model.toggleColumns.forEach { col ->
            if (enabled) enabledIds.add(col.columnName) else enabledIds.remove(col.columnName)
        }
        batchCheckBoxes.forEach { it.isSelected = enabled }
        renderSql()
    }

    /** 动态条件分区操作行（M14.2）：全选/全不选按钮（「动态条件」分区标题已移除，避免占用左栏空间）。 */
    private fun conditionSectionHeader(): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 2)).apply {
        selectAllButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll")) { setAllConditions(true) }
        selectNoneButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectNone")) { setAllConditions(false) }
        add(selectAllButton)
        add(selectNoneButton)
        border = JBUI.Borders.empty(0, 8, 2, 8)
    }

    /** 左栏分区内的紧凑按钮（比工具栏按钮更小，避免挤压复选框行）。 */
    private fun compactButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
        font = font.deriveFont(10.5f)
        margin = JBUI.insets(0, 6, 0, 6)
        addActionListener { action() }
    }

    /**
     * 全选/全不选（M13.1）：批量设置动态条件勾选态（普通参数不受影响），
     * 同步复选框 UI 与 SQL 重渲染。T-M24-04：XML 预览的条件开关（id = test 表达式）一并处理。
     */
    private fun setAllConditions(enabled: Boolean) {
        val views = current?.previewModel?.conditionViews.orEmpty()
        val xmlViews = current?.xmlModel?.conditionViews.orEmpty()
        views.forEach { if (enabled) enabledIds.add(it.id) else enabledIds.remove(it.id) }
        xmlViews.forEach { if (enabled) enabledIds.add(it.id) else enabledIds.remove(it.id) }
        conditionCheckBoxes.forEach { it.isSelected = enabled }
        renderSql()
    }

    /** 动态条件行：复选框（勾选开关）+（有值来源时）值输入框（M19.5：between 等多值条件每个 source 一个输入框）。 */
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
            // M19.5：多值条件（between 等）每个 source 一个输入框；valueSources 为空时回退单值（旧路径兼容）
            view.valueSources.ifEmpty { listOfNotNull(view.valueSource) }.forEach { source ->
                val field = conditionValueField(view, source)
                add(field)
                paramFields += field
                conditionValueFields += field
            }
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    /** 动态条件值输入框：值变更联动勾选态与 SQL 重渲染（按 source 绑定，多值条件每个来源一个输入框）。 */
    private fun conditionValueField(view: ConditionView, source: String): JBTextField = JBTextField(8).apply {        toolTipText = "#$source"
        text = paramValues[source] ?: ""
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onConditionValueChanged(view, source, this@apply.text)
            override fun removeUpdate(e: DocumentEvent?) = onConditionValueChanged(view, source, this@apply.text)
            override fun changedUpdate(e: DocumentEvent?) = onConditionValueChanged(view, source, this@apply.text)
        })
    }

    /** 动态条件复选框 tooltip：布尔条件源码提示（M13 起无判空语义提示，复选框纯手动）。 */
    private fun conditionTooltip(view: ConditionView): String = view.flagSource?.let {
        MpSqlVisualizerBundle.message("panel.condition.flag", it)
    } ?: ""

    /**
     * 动态条件值变更（M13：复选框纯手动）：仅更新按 source 绑定的参数值并重渲染，
     * 勾选态只由用户操作决定（值空保留 `?`，不自动取消勾选）。
     */
    private fun onConditionValueChanged(view: ConditionView, source: String, text: String) {
        paramValues[source] = text
        renderSql()
    }

    /**
     * T-M24-04：XML 动态条件行——复选框（label = test 表达式，id 进 [enabledIds]），
     * 与 Wrapper 动态条件交互一致（全选/全不选按钮共用）。
     */
    private fun buildXmlConditionRow(view: XmlConditionView): JBPanel<JBPanel<*>> {
        val checkBox = JCheckBox(view.label, view.id in enabledIds).apply {
            toolTipText = MpSqlVisualizerBundle.message("panel.xml.condition")
            addActionListener {
                if (isSelected) enabledIds.add(view.id) else enabledIds.remove(view.id)
                renderSql()
            }
        }
        conditionCheckBoxes += checkBox
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(checkBox)
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    /** T-M24-04：foreach 行——collection 表达式标签 + 展开次数输入（0 = 空集合）+ null 复选框。 */
    private fun buildXmlForEachRow(view: XmlForEachView): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6))
        row.add(JBLabel(MpSqlVisualizerBundle.message("panel.xml.foreach", view.collectionExpr)).apply {
            border = JBUI.Borders.empty(0, 8, 0, 0)
        })
        val countField = JBTextField(3).apply {
            text = (xmlForEachCounts[view.collectionExpr] ?: XmlPreviewModel.DEFAULT_FOREACH_SIZE).toString()
            toolTipText = "0 = 空集合（整段不输出）"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onXmlForEachCountChanged(view, this@apply.text)
                override fun removeUpdate(e: DocumentEvent?) = onXmlForEachCountChanged(view, this@apply.text)
                override fun changedUpdate(e: DocumentEvent?) = onXmlForEachCountChanged(view, this@apply.text)
            })
        }
        row.add(countField)
        paramFields += countField
        val nullBox = JCheckBox(MpSqlVisualizerBundle.message("panel.xml.foreach.null"), view.collectionExpr in xmlForEachNulls).apply {
            addActionListener {
                if (isSelected) xmlForEachNulls.add(view.collectionExpr) else xmlForEachNulls.remove(view.collectionExpr)
                renderSql()
            }
        }
        row.add(nullBox)
        row.border = JBUI.Borders.empty(0, 8, 2, 8)
        return row
    }

    /** T-M24-04：foreach 次数变更（非数字输入忽略，保持上次有效值）。 */
    private fun onXmlForEachCountChanged(view: XmlForEachView, text: String) {
        val count = text.toIntOrNull()?.coerceIn(0, 99) ?: return
        xmlForEachCounts[view.collectionExpr] = count
        renderSql()
    }

    /** T-M24-04：bind 行——表达式标签 + 覆盖值输入（留空 = 表达式自动求值）。 */
    private fun buildXmlBindRow(view: XmlBindView): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6))
        row.add(JBLabel(MpSqlVisualizerBundle.message("panel.xml.bind", "${view.name} = ${view.expression}")).apply {
            border = JBUI.Borders.empty(0, 8, 0, 0)
        })
        val field = JBTextField(8).apply {
            text = xmlBindOverrides[view.name] ?: ""
            toolTipText = MpSqlVisualizerBundle.message("panel.xml.bind", view.name)
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onXmlBindOverrideChanged(view.name, this@apply.text)
                override fun removeUpdate(e: DocumentEvent?) = onXmlBindOverrideChanged(view.name, this@apply.text)
                override fun changedUpdate(e: DocumentEvent?) = onXmlBindOverrideChanged(view.name, this@apply.text)
            })
        }
        row.add(field)
        paramFields += field
        row.border = JBUI.Borders.empty(0, 8, 2, 8)
        return row
    }

    /** T-M24-04：bind 覆盖值变更（留空移除覆盖 → 恢复表达式求值）。 */
    private fun onXmlBindOverrideChanged(name: String, text: String) {
        if (text.isEmpty()) xmlBindOverrides.remove(name) else xmlBindOverrides[name] = text
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
        // T-M19.4-01：FlowLayout 间距 (4,2) → (8,6)——参数名较长时视觉"贴太近"（用户实测）
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            add(paramLabel(source))
            add(field)
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    /**
     * T-M25.4：XML 参数行（含 foreach 展开项）——标签可读化（`__frch_id_0` → `id[0]`），
     * source 保持原始名（paramValues / backfill 按原始名匹配）。
     */
    private fun buildXmlParamRow(source: String): JBPanel<JBPanel<*>> {
        val display = displayParamName(source)
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
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            add(paramLabel(display))
            add(field)
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    /** foreach 重写名可读化：`__frch_id_0` → `id[0]`；普通参数原样。 */
    private fun displayParamName(source: String): String =
        FRCH_PARAM_REGEX.replace(source) { m -> "${m.groupValues[1]}[${m.groupValues[2]}]" }

    /**
     * 参数行标签（T-M19.4-01，用户实测：`#productInfo.getDicProtocolType()` 这类长参数名被
     * 固定 140px 标签截断显示不全）：
     *  - 宽度**按文本自适应**（FontMetrics 计算，夹在 140~300px）——短名不拉伸、长名不截断；
     *  - 文本超过 300px 上限时 tooltip 展示全名（标签本身截断显示）；
     *  - 标签超出面板宽度时由 FlowLayout 自然换行（输入框换到下一行）。
     */
    private fun paramLabel(source: String): JBLabel = JBLabel("#$source").apply {
        val text = "#$source"
        val metrics = getFontMetrics(UIUtil.getLabelFont())
        val textWidth = metrics.stringWidth(text)
        preferredSize = Dimension(textWidth.coerceIn(140, 300), preferredSize.height)
        if (textWidth > 300) toolTipText = text
    }

    /** 普通参数值变更：更新回填表并重渲染 SQL。 */
    private fun onParamChanged(source: String, value: String) {
        paramValues[source] = value
        renderSql()
    }

    /**
     * 按出现顺序把 SQL 中的 `?` 替换为用户输入（按 source 定位参数值，取代位置绑定，见 M12-R2）。
     * 值渲染按回填提示 [ParamHint]（M13）：LIKE 位置拼 `%`、String 列恒引号、数值列恒原样；
     * 提示缺失/类型未知 → 数字启发式；空值一律保留 `?`（复选框纯手动，不联动勾选态）。
     */
    private fun backfillParamsBySource(sql: String, sources: List<String>, hints: List<ParamHint> = emptyList()): String {
        if (sources.isEmpty()) return sql
        val sb = StringBuilder(sql.length + 32)
        var paramIndex = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '?') {
                val source = sources.getOrNull(paramIndex)
                val v = source?.let { paramValues[it] }
                sb.append(renderParamValue(v, hints.getOrNull(paramIndex)))
                paramIndex++
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun renderParamValue(v: String?, hint: ParamHint?): String {
        if (v.isNullOrBlank()) return "?"
        val like = hint?.likePosition
        // M19.6（用户实测）：IN 列表多值回填——逗号分割，每个值独立渲染（引号规则同单值），
        // 输入 `1,2` → `('1','2')`（原实现拼成 `'1,2'` 单值）
        if (hint?.inList == true) {
            val parts = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return "?"
            return parts.joinToString(", ") { renderSingleValue(it, hint) }
        }
        val escaped = v.replace("'", "''")
        return when {
            // LIKE 模式：恒引号 + 按位置拼 %（值本身转义）
            like != null -> when (like) {
                LikePosition.ANYWHERE -> "'%$escaped%'"
                LikePosition.LEFT -> "'%$escaped'"
                LikePosition.RIGHT -> "'$escaped%'"
                LikePosition.NONE -> "'$escaped'"
            }
            else -> renderSingleValue(v, hint)
        }
    }

    /** 单值渲染（M19.6 抽取，IN 列表各值与普通参数共用）：String 列恒引号、数值恒原样、未知走数字启发式。 */
    private fun renderSingleValue(raw: String, hint: ParamHint?): String {
        val escaped = raw.replace("'", "''")
        return when {
            hint?.quote == true -> "'$escaped'"
            hint?.quote == false -> raw.trim()
            else -> {
                val trimmed = raw.trim()
                if (NUMBER_REGEX.matches(trimmed)) trimmed else "'$escaped'"
            }
        }
    }

    // ------------------------------------------------------------------
    // 元信息
    // ------------------------------------------------------------------

    private fun updateMeta(result: PreviewResult) {
        val meta = buildList {
            result.wrapperType?.let { add("${MpSqlVisualizerBundle.message("panel.meta.type")}: ${it.name}") }
            result.tableName?.let { add("${MpSqlVisualizerBundle.message("panel.meta.table")}: $it") }
            // T-M24-04：XML 语句元信息（类型 + parameterType）
            result.xmlModel?.let { xml ->
                add("XML ${xml.kind.name.lowercase()}: ${xml.statementId}")
                xml.parameterType?.let { add("parameterType: $it") }
            }
        }.joinToString("   ")
        // T-M24-04：XML 渲染期错误并入警示（解析期错误在 PreviewResult.warning）
        val xmlWarning = result.xmlModel?.let { xml ->
            (xmlErrors).joinToString("；").ifEmpty { null }
        }
        currentWarning = xmlWarning ?: result.warning
        val warningLine = currentWarning?.let { "   ⚠ $it" } ?: ""
        metaLabel.text = meta + warningLine
        metaLabel.foreground = if (currentWarning != null) JBColor.RED else JBUI.CurrentTheme.Label.foreground()
        // T-M25.3：错误复制按钮随警示可见
        errorCopyButton?.isVisible = currentWarning != null
    }

    /**
     * T-M25.3：一键复制完整错误信息（用户实测：弹窗顶部 ⚠ 警示文本难以选中复制；
     * 复制内容为完整 warning——解析/渲染错误合并文本，非显示截断）。
     */
    fun copyError(): Boolean {
        val warning = currentWarning ?: return false
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(warning), null)
        return true
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
    /** T-M24-04：元信息标签文本（警告断言用）。 */
    internal fun metaLabelForTest(): String = metaLabel.text
    /** T-M25.3：错误复制按钮（可见性断言用）。 */
    internal fun errorCopyButtonForTest(): JButton? = errorCopyButton

    /**
     * T-M19.4-01：第 index 个普通参数行的标签（JBLabel）——布局断言用
     * （宽度自适应/tooltip 全名）。按 paramPanel 中「含 JBLabel 的 JBPanel 行」定位，
     * 与 paramFields 的顺序一致（普通参数行按 source 顺序追加）。
     */
    internal fun paramRowLabelForTest(index: Int): JBLabel? = paramPanel.components
        .filterIsInstance<JBPanel<*>>()
        .filter { row -> row.components.any { it is JBLabel } }
        .getOrNull(index)
        ?.components?.filterIsInstance<JBLabel>()?.firstOrNull()
    internal fun conditionCheckboxForTest(index: Int): JCheckBox? = conditionCheckBoxes.getOrNull(index)
    internal fun conditionValueFieldForTest(index: Int): JBTextField? = conditionValueFields.getOrNull(index)
    /** T-M23-04：第 index 个批量列级勾选复选框（toggle 列按 BatchModel 顺序）。 */
    internal fun batchCheckboxForTest(index: Int): JCheckBox? = batchCheckBoxes.getOrNull(index)
    /** T-M23.2：批量「参数列」区全选/全不选按钮。 */
    internal fun batchSelectAllButtonForTest(): JButton? = batchSelectAllButton
    internal fun batchSelectNoneButtonForTest(): JButton? = batchSelectNoneButton
    internal fun enabledIdsForTest(): Set<String> = enabledIds.toSet()
    internal fun selectAllButtonForTest(): JButton? = selectAllButton
    internal fun selectNoneButtonForTest(): JButton? = selectNoneButton

    companion object {
        private val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")
        /** T-M24-04：XML 单行视图的空白归一化（多行文本节点 / 段间空格）。 */
        private val WHITESPACE_REGEX = Regex("\\s+")
        /** T-M25.4：foreach 重写名可读化（`__frch_id_0` → `id[0]`）。 */
        private val FRCH_PARAM_REGEX = Regex("__frch_(\\w+)_(\\d+)")
    }
}
