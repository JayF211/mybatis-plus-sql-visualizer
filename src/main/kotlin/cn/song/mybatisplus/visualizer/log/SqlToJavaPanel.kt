package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
import cn.song.mybatisplus.visualizer.reverse.ReverseJoin
import cn.song.mybatisplus.visualizer.reverse.ReverseWrapperFormat
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter
import cn.song.mybatisplus.visualizer.ui.SqlInputEditor
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.java.JavaSyntaxHighlighterFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * 「SQL → Java」tab 面板（T-M36.4）：粘贴 SQL → 格式化/自动补全 → 转换 →
 * 可编辑 Java 高亮结果区。
 *
 * 布局（Splitter 上下分栏 + IDEA 原生 ActionToolbar）：
 *  - 上：SQL 输入区（[SqlInputEditor]：SQL 高亮 + 打字自动弹补全）；工具栏：转换 / 格式化 / 清空；
 *  - 下：Java 结果区（可编辑 + Java 词法高亮）；工具栏：复制代码 / 清空；
 *  - 底部：状态行（错误红字 / 提示灰字）。
 *
 * 转换流程复用 [SqlToJavaConverter] 共享编排（与编辑器 Action 同一链路）；实体查找
 * 后台 ReadAction（2025.3+ 慢操作守卫）；多候选弹 [SqlSelectionDialog]。
 */
class SqlToJavaPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val inputEditor = SqlInputEditor(project, "")
    private val resultEditor: Editor = createJavaEditor()
    private val statusLabel = JBLabel(" ").apply {
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** T-M36.6：生成形态下拉（与设置页 reverseWrapperFormat 双向同步：写穿持久化 + tab 重选刷新）。 */
    private val formatCombo = JComboBox<FormatOption>().apply {
        model = DefaultComboBoxModel(formatOptions())
        addActionListener {
            if (suppressFormatSync) return@addActionListener
            val value = (selectedItem as? FormatOption)?.persisted
            if (value != null && value != settingsState().reverseWrapperFormat) {
                // 写穿式持久化（对齐语言下拉 M19.1 实证模式）——面板是设置值的快捷入口
                settingsState().reverseWrapperFormat = value
                regenerateWithNewFormat()
            }
        }
    }

    /** T-M36.7：调用类输入（Service/Mapper 类名，非必选——留空生成纯表达式）。 */
    private val callerField = JBTextField(12).apply {
        emptyText.text = MpSqlVisualizerBundle.message("reverse.panel.callerHint")
    }

    /** T-M36.7：返回 VO 类名输入（非必选，仅 SELECT 生效——MPJ selectVoList/listVo）。 */
    private val voField = JBTextField(8).apply {
        emptyText.text = MpSqlVisualizerBundle.message("reverse.panel.voHint")
    }

    /** 设置 → 下拉同步期间的抑制标志（refreshFormatFromSettings 不触发重生成）。 */
    private var suppressFormatSync = false

    /** T-M36.6：上次成功解析缓存（输入文本未变时切换形态只重生成，不重新 parse/查实体）。 */
    private var cacheText: String? = null
    private var cacheResult: SqlReverseResult? = null
    private var cacheCandidates: List<SqlToJavaConverter.EntityCandidate>? = null
    private var cacheSubEntities: Map<String, JavaCodeGenerator.EntityTarget> = emptyMap()

    init {
        // 上：输入区（工具栏 + 编辑器）
        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(buildInputToolbar(), BorderLayout.NORTH)
        inputPanel.add(inputEditor.component, BorderLayout.CENTER)

        // 下：结果区（工具栏 + Java 编辑器）
        val resultPanel = JPanel(BorderLayout())
        resultPanel.add(buildResultToolbar(), BorderLayout.NORTH)
        resultPanel.add(resultEditor.component, BorderLayout.CENTER)

        val splitter = Splitter(true, 0.5f).apply {
            firstComponent = inputPanel
            secondComponent = resultPanel
            isShowDividerControls = false
        }
        add(splitter, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        refreshFormatFromSettings()
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private fun buildInputToolbar(): JPanel {
        val group = DefaultActionGroup(
            ConvertAction(),
            FormatAction(),
            ClearInputAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID_INPUT, group, true)
            .apply { targetComponent = this@SqlToJavaPanel }
        // T-M39 增量：选项（形态下拉/调用类/VO）独立成行——原 BorderLayout.EAST + FlowLayout
        // 三控件挤一行，窄侧边栏下形态下拉被挤压截断（用户截图只见「→ 字符串列名）」）
        val bar = JPanel(BorderLayout())
        bar.add(toolbar.component, BorderLayout.NORTH)
        bar.add(buildOptionsRow(), BorderLayout.SOUTH)
        return bar
    }

    /**
     * 选项行：形态下拉独占整行（高频、选项文本长须完整显示）+ 调用类/VO 各半行
     * （低频可选，占位提示截断无妨）——GridBag 拉伸，窄窗口各自收缩互不挤压。
     */
    private fun buildOptionsRow(): JPanel = JPanel(GridBagLayout()).apply {
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insets(0, 4, 4, 4)
        }
        c.gridx = 0
        c.gridwidth = 2
        add(formatCombo, c)
        c.gridy = 1
        c.gridwidth = 1
        c.weightx = 1.0
        add(callerField, c)
        c.gridx = 1
        add(voField, c)
    }

    private fun buildResultToolbar(): JPanel {
        val group = DefaultActionGroup(
            CopyAction(),
            ClearResultAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID_RESULT, group, true)
            .apply { targetComponent = this@SqlToJavaPanel }
        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
        }
    }

    /** 转换：parse（EDT 纯函数）→ 实体查找（后台 ReadAction）→ 分发（设置形态）。 */
    private inner class ConvertAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Execute
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.convert")
        }

        override fun actionPerformed(e: AnActionEvent) {
            convert()
        }
    }

    /** 格式化输入区 SQL（SqlFormatter 幂等；程序性写入不触发补全）。 */
    private inner class FormatAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.ReformatCode
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.format")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val text = inputEditor.text
            if (text.isBlank()) return
            inputEditor.setSql(cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter.format(text))
        }
    }

    private inner class ClearInputAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.clearInput")
        }

        override fun actionPerformed(e: AnActionEvent) {
            inputEditor.setSql("")
        }
    }

    private inner class CopyAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Copy
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.copy")
            e.presentation.isEnabled = resultEditor.document.text.isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(resultEditor.document.text), null)
            status(MpSqlVisualizerBundle.message("reverse.copy.done"), error = false)
        }
    }

    private inner class ClearResultAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("reverse.panel.clearResult")
        }

        override fun actionPerformed(e: AnActionEvent) {
            setResultText("")
            status(" ", error = false)
        }
    }

    // ------------------------------------------------------------------
    // 转换流程
    // ------------------------------------------------------------------

    private fun convert() {
        val text = inputEditor.text
        if (text.isBlank()) {
            status(MpSqlVisualizerBundle.message("reverse.panel.empty"), error = true)
            return
        }
        val result = try {
            SqlToJavaConverter.parse(text)
        } catch (ex: SqlReverseException) {
            status(
                MpSqlVisualizerBundle.message(ex.key, ex.detail) +
                    "（第 ${SqlToJavaConverter.errorLine(text, ex.offset)} 行）",
                error = true,
            )
            return
        }
        // T-M36.6：缓存成功解析（输入文本未变时切换形态复用）
        cacheText = text
        cacheResult = result
        val format = reverseFormat()
        if (format == ReverseWrapperFormat.QUERY) {
            cacheCandidates = null
            render(result, emptyList(), format)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val payload = try {
                ReadAction.compute<Pair<List<SqlToJavaConverter.EntityCandidate>, Map<String, List<SqlToJavaConverter.EntityCandidate>>>, Throwable> {
                    val candidates = SqlToJavaConverter.findCandidates(project, result.tableName)
                    // T-M37 P1：副表**全部**候选（alias → 列表；零/无别名 → 空），EDT 层按 joins 顺序分发
                    val subCandidates = SqlToJavaConverter.findSubEntityCandidates(project, result.joins)
                    candidates to subCandidates
                }
            } catch (t: Throwable) {
                emptyList<SqlToJavaConverter.EntityCandidate>() to emptyMap()
            }
            ApplicationManager.getApplication().invokeLater {
                cacheCandidates = payload.first
                // 副表候选按 joins 顺序逐个处理：多候选弹窗（onSelect 里继续下一个），
                // ≤1 直接并入，全部处理完统一 render（EDT 异步回调链）
                resolveSubEntities(payload.second, result.joins) { subEntities ->
                    cacheSubEntities = subEntities
                    render(result, payload.first, format, subEntities)
                }
            }
        }
    }

    /**
     * T-M37 P1：副表实体按 joins 顺序逐表解析（EDT 线程）——
     *  - 候选数 > 1：弹 [SqlSelectionDialog]（displayTexts 仿主表候选格式「类名（表 表名）」+ 文件名），
     *    选中后写入 subEntities 并在 onSelect 里继续处理后续副表（异步回调链）；
     *  - 候选数 == 1：直接并入；== 0 或该 join 无别名：跳过（该表列回落字符串列名，现状）；
     *  - 全部处理完 → [onComplete]（最后统一 render；副表选择结果同时进 cacheSubEntities，
     *    切换形态重生成时复用不再弹）。
     */
    private fun resolveSubEntities(
        subCandidates: Map<String, List<SqlToJavaConverter.EntityCandidate>>,
        joins: List<ReverseJoin>,
        onComplete: (Map<String, JavaCodeGenerator.EntityTarget>) -> Unit,
    ) {
        // 按 joins 顺序的别名列表（无别名的 join 无候选，直接跳过）
        val aliases = joins.mapNotNull { it.alias }
        val subEntities = mutableMapOf<String, JavaCodeGenerator.EntityTarget>()
        var index = 0
        fun next() {
            while (index < aliases.size) {
                val alias = aliases[index]
                index++
                val candidates = subCandidates[alias].orEmpty()
                when (candidates.size) {
                    0 -> continue // 未找到实体 → 该表列回落字符串列名
                    1 -> {
                        toSubEntity(candidates[0])?.let { subEntities[alias] = it }
                        continue
                    }
                    else -> {
                        // 多候选弹窗（EDT；displayTexts 仿主表候选用法）——选完继续处理后续副表
                        val displayTexts = candidates.map { c ->
                            SqlSelectionDialog.displayText(
                                "${c.psiClass.name}（表 ${c.tableInfo.tableName}）",
                                c.psiClass.containingFile?.name ?: "",
                            )
                        }
                        SqlSelectionDialog(project, candidates, displayTexts) { selected ->
                            toSubEntity(selected)?.let { subEntities[alias] = it }
                            next()
                        }.show()
                        return
                    }
                }
            }
            onComplete(subEntities)
        }
        next()
    }

    /** 副表候选 → 生成用实体目标（类名缺失则跳过该表——回落字符串列名）。 */
    private fun toSubEntity(
        candidate: SqlToJavaConverter.EntityCandidate,
    ): JavaCodeGenerator.EntityTarget? {
        val className = candidate.psiClass.name ?: return null
        return JavaCodeGenerator.EntityTarget(
            className = className,
            columnToField = candidate.tableInfo.columns.associate { it.columnName to it.fieldName },
        )
    }

    /**
     * 供 SQL 日志列表联动（T-M37 P1）：写入输入区，并清空结果区与状态行提示
     * （不自动触发转换——用户点「转换」或切换形态后生成）。
     */
    fun setInputSql(sql: String) {
        inputEditor.setSql(sql)
        setResultText("")
        status(" ", error = false)
    }

    /**
     * T-M36.6：切换形态 → 用当前输入与缓存立即重生成（输入文本未变不重新 parse/查实体）；
     * 无缓存（QUERY 路径未查过实体）或输入已变化 → 走完整转换流程。
     */
    private fun regenerateWithNewFormat() {
        val text = inputEditor.text
        if (text.isBlank()) return
        if (cacheText != text || cacheResult == null) {
            convert()
            return
        }
        val format = reverseFormat()
        val candidates = cacheCandidates
        if (format == ReverseWrapperFormat.QUERY || candidates == null) {
            // QUERY 形态或从未查过实体（QUERY 切换来）→ 重新走完整流程（同步/后台自适应）
            if (format == ReverseWrapperFormat.QUERY) {
                render(cacheResult!!, emptyList(), format)
            } else {
                convert()
            }
            return
        }
        render(cacheResult!!, candidates, format, cacheSubEntities)
    }

    /** T-M37：多表 JOIN 状态提示（生成 MPJ 形态说明）。 */
    private fun joinStatus(result: SqlReverseResult, base: String): String =
        if (result.joins.isNotEmpty()) {
            "$base（多表 JOIN 已生成 MPJ 形态）"
        } else {
            base
        }

    /** 结果区写文本（WriteCommandAction——平台线程检查强制 write action）。 */
    private fun setResultText(text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            resultEditor.document.setText(text)
        }
    }

    /** T-M36.7：调用上下文（调用类 + VO 均非必选；留空返回 null → 纯表达式）。 */
    private fun buildInvocation(): JavaCodeGenerator.InvocationTarget? {
        val caller = callerField.text.trim()
        if (caller.isEmpty()) return null
        val vo = voField.text.trim().ifEmpty { null }
        return JavaCodeGenerator.InvocationTarget(caller, vo)
    }

    /** 结果分发：0 候选回落 QueryWrapper；1 候选直接生成；多候选选择后生成。 */
    private fun render(
        result: SqlReverseResult,
        candidates: List<SqlToJavaConverter.EntityCandidate>,
        format: ReverseWrapperFormat,
        subEntities: Map<String, JavaCodeGenerator.EntityTarget> = emptyMap(),
    ) {
        val invocation = buildInvocation()
        val generate = { candidate: SqlToJavaConverter.EntityCandidate? ->
            SqlToJavaConverter.generateCode(result, candidate, format, invocation, subEntities)
        }
        when (candidates.size) {
            0 -> {
                setResultText(generate(null))
                status(joinStatus(result, MpSqlVisualizerBundle.message("reverse.panel.status.noEntity")), error = false)
            }
            1 -> {
                setResultText(generate(candidates[0]))
                status(
                    joinStatus(
                        result,
                        MpSqlVisualizerBundle.message("reverse.panel.status.entity", candidates[0].psiClass.name ?: ""),
                    ),
                    error = false,
                )
            }
            else -> {
                val displayTexts = candidates.map { c ->
                    SqlSelectionDialog.displayText(
                        "${c.psiClass.name}（表 ${c.tableInfo.tableName}）",
                        c.psiClass.containingFile?.name ?: "",
                    )
                }
                SqlSelectionDialog(project, candidates, displayTexts) { selected ->
                    setResultText(generate(selected))
                    status(
                        MpSqlVisualizerBundle.message("reverse.panel.status.entity", selected.psiClass.name ?: ""),
                        error = false,
                    )
                }.show()
            }
        }
    }

    /** 反向转换配置形态（测试环境 settings 未注册时默认 AUTO）。 */
    private fun reverseFormat(): ReverseWrapperFormat = runCatching {
        ReverseWrapperFormat.fromPersisted(settingsState().reverseWrapperFormat)
    }.getOrDefault(ReverseWrapperFormat.AUTO)

    private fun settingsState(): PluginSettings.State = PluginSettings.getInstance().state

    /**
     * 形态下拉选项（值与设置页同源；label 复用 settings.reverseWrapperFormat.* keys）。
     * 必须为 data class（JDK17 JComboBox 按 equals 匹配，M19 实证）。
     */
    private data class FormatOption(val persisted: String, val label: String) {
        override fun toString(): String = label
    }

    private fun formatOptions(): Array<FormatOption> = arrayOf(
        FormatOption(ReverseWrapperFormat.AUTO.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.auto")),
        FormatOption(ReverseWrapperFormat.QUERY.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.query")),
        FormatOption(ReverseWrapperFormat.LAMBDA.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.lambda")),
        FormatOption(ReverseWrapperFormat.WRAPPERS.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.wrappers")),
        FormatOption(ReverseWrapperFormat.MPJ_LAMBDA.persistedValue, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.mpjLambda")),
    )

    /** 从设置刷新下拉选中（面板创建 / tab 重选时调用；不触发重生成）。 */
    fun refreshFormatFromSettings() {
        val current = settingsState().reverseWrapperFormat
        val option = formatOptions().firstOrNull { it.persisted == current } ?: formatOptions().first()
        if ((formatCombo.selectedItem as? FormatOption)?.persisted != option.persisted) {
            suppressFormatSync = true
            try {
                formatCombo.selectedItem = option
            } finally {
                suppressFormatSync = false
            }
        }
    }

    /** 测试访问器：当前形态下拉选中值。 */
    internal fun selectedFormatForTest(): String =
        (formatCombo.selectedItem as? FormatOption)?.persisted ?: ""

    /** 测试访问器：形态下拉组件（布局断言——选项行 GridBag 拉伸）。 */
    internal fun formatComboForTest(): JComboBox<*> = formatCombo

    /** 测试访问器：模拟用户下拉选择（触发写穿 + 重生成完整链路）。 */
    internal fun selectFormatForTest(persisted: String) {
        formatCombo.selectedItem = formatOptions().first { it.persisted == persisted }
    }

    /** 测试访问器：调用类输入框。 */
    internal fun callerFieldForTest(): JBTextField = callerField

    /** 测试访问器：返回 VO 输入框。 */
    internal fun voFieldForTest(): JBTextField = voField

    /** 状态行（错误红字 / 提示主题色）。 */
    private fun status(message: String, error: Boolean) {
        statusLabel.foreground = if (error) {
            // IDE 标准错误前景命名色（亮暗主题自适应；243 HighlighterColors 无 ERROR 常量）
            JBColor.namedColor("ErrorForeground", JBColor.RED)
        } else {
            EditorColorsManager.getInstance().globalScheme.getAttributes(HighlighterColors.TEXT).foregroundColor
        }
        statusLabel.text = message
    }

    // ------------------------------------------------------------------
    // Java 结果编辑器（可编辑 + 词法高亮）
    // ------------------------------------------------------------------

    private fun createJavaEditor(): Editor {
        val editor = EditorFactory.getInstance()
            .createEditor(EditorFactory.getInstance().createDocument(""), project)
        (editor as EditorEx).apply {
            highlighter = LexerEditorHighlighter(
                JavaSyntaxHighlighterFactory.getSyntaxHighlighter(JavaLanguage.INSTANCE, project, null),
                EditorColorsManager.getInstance().globalScheme,
            )
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
        }
        // 可编辑（用户需求：改实体类名/参数后复制）；不回写 SQL（单向编辑边界）
        return editor
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        inputEditor.dispose()
        EditorFactory.getInstance().releaseEditor(resultEditor)
    }

    private var disposed = false

    // ------------------------------------------------------------------
    // internal 测试访问器（仿 SqlPreviewPanel.panelForTest 约定）
    // ------------------------------------------------------------------

    /** 测试访问器：触发转换（走完整流程，含异步实体查找路径）。 */
    internal fun convertForTest() = convert()

    /** 测试访问器：结果区文本。 */
    internal fun resultTextForTest(): String = resultEditor.document.text

    /** 测试访问器：状态行文本。 */
    internal fun statusForTest(): String = statusLabel.text

    /** 测试访问器：输入区组件（setSql/text 为公开 API）。 */
    internal fun inputEditorForTest(): SqlInputEditor = inputEditor

    companion object {
        private const val TOOLBAR_ID_INPUT = "cn.song.mybatisplus.visualizer.reverse.input.toolbar"
        private const val TOOLBAR_ID_RESULT = "cn.song.mybatisplus.visualizer.reverse.result.toolbar"
    }
}
