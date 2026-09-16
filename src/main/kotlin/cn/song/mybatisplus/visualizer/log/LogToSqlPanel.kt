package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * 「日志转 SQL」tab 面板（T-M38）：粘贴 MyBatis 日志（`Preparing:` / `Parameters:` 行）→
 * 批量还原为可执行 SQL。复用 [MyBatisLogParser] 配对与 [LogSqlRestorer] 还原（与自动捕获同链路，
 * 前缀/混合/截断配置同源读取设置）；一段日志含多组语句时全部转换，组间 `-- SQL #N` 注释分隔。
 *
 * 布局（Splitter 上下分栏 + IDEA 原生 ActionToolbar，对齐「SQL → Java」tab）：
 *  - 上：日志输入区（普通编辑器——日志文本含前缀/时间戳，不设 SQL 词法高亮）；工具栏：转换 / 清空；
 *  - 下：SQL 结果区（可编辑 + SQL 词法高亮）；工具栏：复制 / 压缩↔多行 / 清空；
 *  - 底部：状态行（错误红字 / 提示灰字）。
 *
 * 转换是 EDT 纯函数（文本解析无 PSI/实体查找），同步执行无需异步；
 * 参数不足（还原后仍含 `?`）以红字警告提示。
 */
class LogToSqlPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val inputEditor: Editor = createInputEditor()
    private val resultEditor: Editor = createResultEditor()
    private val statusLabel = JBLabel(" ").apply {
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /**
     * T-UiNative T-UN-14：空态提示接编辑器原生 placeholder（两区锚定物均为 EditorFactory
     * 直建编辑器，实际类型 EditorEx——243 javap 核实有 `setPlaceholder(CharSequence)`），
     * 显隐由编辑器随空文档/焦点原生管理；原自摆 inputHintLabel/resultHintLabel 删除，
     * 文案键（"log.panel.hint.input" / "log.panel.hint.result"）沿用不变。
     */
    private val inputPlaceholder: String = MpSqlVisualizerBundle.message("log.panel.hint.input")
    private val resultPlaceholder: String = MpSqlVisualizerBundle.message("log.panel.hint.result")

    /** 输出格式切换态：true=多行格式化（默认，用户确认）；false=日志原样压缩。 */
    private var formatToggled = true

    init {
        // 上：日志输入区（工具栏 + 编辑器——空文档由编辑器原生 placeholder 显示引导文案）
        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(buildInputToolbar(), BorderLayout.NORTH)
        inputPanel.add(inputEditor.component, BorderLayout.CENTER)

        // 下：SQL 结果区（工具栏 + 编辑器）
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
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private fun buildInputToolbar(): JPanel {
        val group = DefaultActionGroup(
            ConvertAction(),
            ClearInputAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID_INPUT, group, true)
            .apply { targetComponent = this@LogToSqlPanel }
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(toolbar.component)
        }
    }

    private fun buildResultToolbar(): JPanel {
        val group = DefaultActionGroup(
            CopyAction(),
            ToggleFormatAction(),
            ClearResultAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID_RESULT, group, true)
            .apply { targetComponent = this@LogToSqlPanel }
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(toolbar.component)
        }
    }

    private inner class ConvertAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Execute
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.convert")
        }

        override fun actionPerformed(e: AnActionEvent) {
            convert()
        }
    }

    private inner class ClearInputAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.clearInput")
        }

        override fun actionPerformed(e: AnActionEvent) {
            setDocumentText(inputEditor, "")
        }
    }

    private inner class CopyAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Copy
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.copy")
            e.presentation.isEnabled = resultEditor.document.text.isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            UiTextClipboard.copy(resultEditor.document.text)
            status(MpSqlVisualizerBundle.message("logToSql.copy.done"), error = false)
        }
    }

    /** 压缩/多行切换：立即用当前输入重转（空输入不动作）。 */
    private inner class ToggleFormatAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.ReformatCode
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.toggleFormat")
        }

        override fun actionPerformed(e: AnActionEvent) {
            formatToggled = !formatToggled
            if (inputEditor.document.text.isNotBlank()) convert()
        }
    }

    private inner class ClearResultAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.clearResult")
        }

        override fun actionPerformed(e: AnActionEvent) {
            setDocumentText(resultEditor, "")
            // T-AutoFold F4-c：清空后同步清除折叠区域
            cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(resultEditor, "")
            status(" ", error = false)
        }
    }

    /** T-Perf-14：后台转换进行中标志（防重入）。 */
    private val converting = java.util.concurrent.atomic.AtomicBoolean(false)

    // ------------------------------------------------------------------
    // 转换流程
    // ------------------------------------------------------------------

    /**
     * 逐行解析输入日志 → 批量还原（每组 withTerminator + 格式化/压缩切换）→ 写结果区。
     *
     * 三解析器并列：MyBatis + Hibernate + JdbcTemplate 同时喂行，各自识别匹配的格式，结果合并输出。
     * 一段混合日志（同时含多种格式）也能正确转换。
     *
     * T-Perf-14：大日志（超过 [HEAVY_INPUT_CHARS] / [HEAVY_INPUT_LINES] 阈值，粘贴数 MB 日志场景）
     * 解析 + 逐条格式化移 [Task.Backgroundable] 后台 + 进度，EDT 不再全量解析冻结；
     * 小输入保持同步（即时反馈）。防重入：后台转换进行中忽略再次触发。
     */
    private fun convert() {
        val text = inputEditor.document.text
        if (text.isBlank()) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.empty"), error = true)
            return
        }
        // 前缀/混合/截断与自动捕获同源（每次转换即时读取，改设置立即生效）
        val parsers = buildParsers()
        val heavy = text.length > HEAVY_INPUT_CHARS || text.count { it == '\n' } >= HEAVY_INPUT_LINES
        if (!heavy) {
            applyConversion(computeConversion(text, parsers))
            return
        }
        if (!converting.compareAndSet(false, true)) return
        status(MpSqlVisualizerBundle.message("logToSql.status.converting"), error = false)
        object : Task.Backgroundable(
            project,
            MpSqlVisualizerBundle.message("logToSql.panel.convertingTask"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val out = computeConversion(text, parsers)
                ApplicationManager.getApplication().invokeLater {
                    converting.set(false)
                    if (project.isDisposed) return@invokeLater
                    applyConversion(out)
                }
            }
        }.queue()
    }

    /** 三解析器装配（转换配置每次转换即时读取）。 */
    private fun buildParsers(): Triple<MyBatisLogParser, HibernateLogParser, JdbcTemplateLogParser> {
        val state = PluginSettings.getInstance().state
        return Triple(
            MyBatisLogParser(
                sqlPrefix = state.logSqlPrefix,
                paramPrefix = state.logParamPrefix,
                mixedPrefix = state.logMixedPrefix,
                maxParseLength = state.logMaxParseLength,
            ),
            HibernateLogParser(
                sqlPrefix = state.logHibernateSqlPrefix,
                paramPrefix = state.logHibernateParamPrefix,
                mixedPrefix = state.logMixedPrefix,
                maxParseLength = state.logMaxParseLength,
            ),
            JdbcTemplateLogParser(
                enabled = state.logJdbcEnabled,
                maxParseLength = state.logMaxParseLength,
            ),
        )
    }

    /** 纯计算：逐行喂三解析器 + 逐条还原/格式化（后台线程安全——不触碰 Swing）。 */
    private fun computeConversion(
        text: String,
        parsers: Triple<MyBatisLogParser, HibernateLogParser, JdbcTemplateLogParser>,
    ): ConversionOutput {
        val (myBatisParser, hibernateParser, jdbcParser) = parsers
        text.lineSequence().forEach { line ->
            myBatisParser.feed(line)
            hibernateParser.feed(line)
            jdbcParser.feed(line)
        }
        myBatisParser.closePending()
        hibernateParser.closePending()
        jdbcParser.closePending()
        val entries = myBatisParser.takeEntries() + hibernateParser.takeEntries() + jdbcParser.takeEntries()
        if (entries.isEmpty()) return ConversionOutput(null, 0, 0)
        val multi = entries.size > 1
        val out = entries.mapIndexed { index, entry ->
            val base = withTerminator(entry.restoredSql)
            val body = if (formatToggled) SqlFormatter.format(base) else base
            if (multi) "-- ${MpSqlVisualizerBundle.message("logToSql.status.group", index + 1)}\n$body" else body
        }.joinToString("\n\n")
        // T-HibDualCapture：string-aware 计数，字符串/注释里的 `?` 不再误计
        val unresolved = entries.count { countPlaceholders(it.restoredSql) > 0 }
        return ConversionOutput(out, entries.size, unresolved)
    }

    /** 应用转换结果（EDT）：写结果区 + 状态行。 */
    private fun applyConversion(out: ConversionOutput) {
        if (out.text == null) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), error = true)
            return
        }
        setDocumentText(resultEditor, out.text)
        // T-AutoFold F4-c：转换产出后按当前设置重建结果区折叠（关闭态零操作）。
        // 随 dev 的计算/应用拆分从 computeConversion（后台线程）移到此处（EDT），写后即折。
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(resultEditor, out.text)
        status(
            if (out.unresolved > 0) {
                MpSqlVisualizerBundle.message("logToSql.status.unresolved", out.unresolved)
            } else {
                MpSqlVisualizerBundle.message("logToSql.status.converted", out.entriesCount)
            },
            error = out.unresolved > 0,
        )
    }

    /** 转换结果（null text = 无条目）。 */
    private data class ConversionOutput(val text: String?, val entriesCount: Int, val unresolved: Int)

    /** T-M38：列表「发送到日志转 SQL」落点——条目渲染回日志文本填入输入区并立即转换。 */
    internal fun loadFromEntry(entry: LogSqlEntry) {
        setDocumentText(inputEditor, entry.toLogText())
        convert()
    }

    /** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复）。 */
    private fun withTerminator(sql: String): String =
        if (sql.isEmpty() || sql.endsWith(";")) sql else sql + ";"

    private fun setDocumentText(editor: Editor, text: String) {
        if (editor.document.text == text) return
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText(text)
        }
    }

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
    // 编辑器
    // ------------------------------------------------------------------

    /** 日志输入编辑器：普通文本（日志含前缀/时间戳等噪声，SQL 高亮会误导）；行号开。 */
    private fun createInputEditor(): Editor {
        val editor = EditorFactory.getInstance()
            .createEditor(EditorFactory.getInstance().createDocument(""), project)
        (editor as EditorEx).apply {
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = false
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
            setPlaceholder(inputPlaceholder) // T-UiNative T-UN-14：空态提示原生件
        }
        return editor
    }

    /** SQL 结果编辑器：可编辑（微调参数后复制）+ 原生 SQL 语法高亮。 */
    private fun createResultEditor(): Editor {
        val editor = EditorFactory.getInstance()
            .createEditor(EditorFactory.getInstance().createDocument(""), project)
        (editor as EditorEx).apply {
            // 原生 SQL 高亮器
            val sqlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .getStdFileType("SQL")
            if (sqlFileType != null) {
                val sqlHighlighter = com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
                    .getSyntaxHighlighter(sqlFileType, project, null)
                if (sqlHighlighter != null) {
                    highlighter = LexerEditorHighlighter(sqlHighlighter, EditorColorsManager.getInstance().globalScheme)
                }
            }
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
            setPlaceholder(resultPlaceholder) // T-UiNative T-UN-14：空态提示原生件
        }
        return editor
    }

    /** T-UiNative T-UN-14：输入区空态提示（原 hintLabel 测试缝，断言 placeholder 文案接线用）。 */
    internal fun inputPlaceholderForTest(): String = inputPlaceholder

    /** T-UiNative T-UN-14：结果区空态提示。 */
    internal fun resultPlaceholderForTest(): String = resultPlaceholder

    override fun dispose() {
        if (disposed) return
        disposed = true
        EditorFactory.getInstance().releaseEditor(inputEditor)
        EditorFactory.getInstance().releaseEditor(resultEditor)
    }

    private var disposed = false

    // ------------------------------------------------------------------
    // internal 测试访问器（仿 SqlToJavaPanel *ForTest 约定）
    // ------------------------------------------------------------------

    internal fun setInputForTest(text: String) = setDocumentText(inputEditor, text)

    internal fun convertForTest() = convert()

    internal fun toggleFormatForTest() {
        formatToggled = !formatToggled
    }

    internal fun resultTextForTest(): String = resultEditor.document.text

    /** T-AutoFold F4-c：结果区编辑器（折叠区域断言用）。 */
    internal fun resultEditorForTest(): Editor = resultEditor

    internal fun statusForTest(): String = statusLabel.text

    internal fun loadFromEntryForTest(entry: LogSqlEntry) = loadFromEntry(entry)

    companion object {

        /** T-Perf-14：大日志后台转换阈值——字符数（约 1MB）。 */
        private const val HEAVY_INPUT_CHARS = 1_000_000
        /** T-Perf-14：大日志后台转换阈值——行数（约 1000 行）。 */
        private const val HEAVY_INPUT_LINES = 1_000
        private const val TOOLBAR_ID_INPUT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.input"
        private const val TOOLBAR_ID_RESULT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.result"
    }
}
