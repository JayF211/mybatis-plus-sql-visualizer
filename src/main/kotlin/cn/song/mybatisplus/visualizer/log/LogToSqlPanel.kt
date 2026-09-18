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
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

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

    // T-PreviewUx-Pack #2：诚实降级横幅（面板内嵌非模态提示，不算弹窗）——还原失败/参数缺失
    // 不再仅靠单行状态红字，横幅承载三要素（哪段没还原/为什么/用户能做什么）
    private val degradationBanner = cn.song.mybatisplus.visualizer.ui.DegradationBanner()

    /**
     * T-UiNative T-UN-14：空态提示接编辑器原生 placeholder（两区锚定物均为 EditorFactory
     * 直建编辑器，实际类型 EditorEx——243 javap 核实有 `setPlaceholder(CharSequence)`），
     * 显隐由编辑器随空文档/焦点原生管理；文案键（"log.panel.hint.input" / "log.panel.hint.result"）沿用不变。
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
        // T-PreviewUx-Pack #2：底部区 = 诚实降级横幅（上）+ 状态行（下）——横幅触发即显示、
        // 恢复即隐藏（不占高度），状态行语义不变
        val southHost = JPanel(BorderLayout())
        southHost.add(degradationBanner, BorderLayout.NORTH)
        southHost.add(statusLabel, BorderLayout.SOUTH)
        add(southHost, BorderLayout.SOUTH)
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
            // T-PreviewUx-Pack #4：执行还原产物（送原生 Database 控制台链路；多条语句先勾选确认）
            ExecuteResultAction(),
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

    /**
     * T-PreviewUx-Pack #4：执行还原产物（吸收 DBX「多语句执行确认」口径）——
     * 产物拆分后**单条直通**（不增加低价值打断）；多条先弹候选勾选浮层
     * （T-CrudSql 原生形态：JBPopup 无按钮浮层，Enter/双击执行选中项、ESC 关闭、打字过滤），
     * 选中项送既有执行链路（execute/DataSourceSelector + SqlExecutor——原生 Database 控制台，
     * 凭证不出 IDE；Community 由 SqlExecutor 提示降级）。
     */
    private inner class ExecuteResultAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Execute
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.execute")
            e.presentation.isEnabled = resultEditor.document.text.isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            executeResult()
        }
    }

    /** 执行入口：拆语句 → 单条直通 / 多条勾选浮层。 */
    private fun executeResult() {
        val text = resultEditor.document.text
        if (text.isBlank()) return
        val statements = splitSqlStatements(text)
        if (statements.isEmpty()) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), error = true)
            return
        }
        if (statements.size == 1) {
            executeStatement(statements[0])
            return
        }
        // 多条：候选勾选浮层（CrudSqlPreviewFlow.showCandidates 同款范式——无按钮、
        // Enter/双击确认、ESC 关闭、速度搜索过滤；条目 = 摘要行 + 完整语句详情行）
        val rows = statements.mapIndexed { index, sql ->
            StatementCandidate(
                index = index + 1,
                keyword = cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL",
                sql = sql,
            )
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setTitle(MpSqlVisualizerBundle.message("logToSql.execute.pickTitle"))
            .setRenderer(StatementCandidateRenderer())
            .setNamerForFiltering { it.summary() }
            .setItemChosenCallback { selected ->
                // 执行延后到选择浮层关闭之后（T-CrudSql 真机修复同款：不在选择 UI 的事件栈内
                // 同步开 modeless 链路，262 实测会被吞且零异常零日志）
                ApplicationManager.getApplication().invokeLater {
                    executeStatement(selected.sql)
                }
            }
            .apply {
                // T-BatchInMerge（O-14）：同表等值批删的合并参考行——浮层原生单行提示位
                // （adText），零新弹窗零新组件形态；执行文本与复制行为零改动
                buildMergeAdText(statements)?.let { setAdText(it) }
            }
            .createPopup()
            .showInFocusCenter()
    }

    /** 执行单条语句：非查询先 DML 二次确认（与预览面板执行同交互），随后送原生控制台链路。
     *  T-UnboundedWriteGuard：确认消息经 ExecuteConfirmMessages——命中无界写特征时同一对话框
     *  列出发现（不新增弹窗形态/步骤），无发现时文案逐字节不变。
     *  弹窗白名单依据（BannedApiSourceScanTest「模态弹窗只允许白名单内既有场景」）：非查询提交前
     *  的 YesNo 确认属「用户必须做选择」场景，与 SqlPreviewPanel 执行确认同类。 */
    private fun executeStatement(sql: String) {
        val isQuery = cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.isQuery(sql)
        if (!isQuery) {
            val keyword = cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL"
            val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(sql, keyword),
                MpSqlVisualizerBundle.message("execute.confirm.dml"),
                com.intellij.openapi.ui.Messages.getWarningIcon(),
            )
            if (confirm != com.intellij.openapi.ui.Messages.YES) return
        }
        status(MpSqlVisualizerBundle.message("logToSql.execute.submitted"), error = false)
        cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute(
            project, sql, null, isQuery,
            onError = { errorMsg -> status(errorMsg, error = true) },
        )
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
            if (multi) {
                // T-StatementNaming（O-19）：语句前导注释 `-- Name: x`（或最近行注释）→ 组头附「 [名称]」；
                // 无注释 nameOf=null 后缀空串——组头逐字节维持现状（硬门禁）
                val named = cn.song.mybatisplus.visualizer.util.SqlStatementNamer.nameOf(entry.restoredSql)
                "-- ${MpSqlVisualizerBundle.message("logToSql.status.group", index + 1)}" +
                    (if (named != null) " [$named]" else "") + "\n$body"
            } else body
        }.joinToString("\n\n")
        // T-HibDualCapture：string-aware 计数，字符串/注释里的 `?` 不再误计
        val unresolved = entries.count { countPlaceholders(it.restoredSql) > 0 }
        return ConversionOutput(out, entries.size, unresolved)
    }

    /** 应用转换结果（EDT）：写结果区 + 状态行 + 诚实降级横幅。 */
    private fun applyConversion(out: ConversionOutput) {
        if (out.text == null) {
            // T-PreviewUx-Pack #2：未识别到条目 → 横幅三要素（哪段没还原=整段日志，为什么=前缀不匹配，能做什么）
            degradationBanner.show(
                cn.song.mybatisplus.visualizer.ui.DegradationBanner.Kind.RENDER_WARNING,
                MpSqlVisualizerBundle.message("logToSql.banner.noEntry"),
            )
            status(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), error = true)
            return
        }
        setDocumentText(resultEditor, out.text)
        // T-AutoFold F4-c：转换产出后按当前设置重建结果区折叠（关闭态零操作）。
        // 随 dev 的计算/应用拆分从 computeConversion（后台线程）移到此处（EDT），写后即折。
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(resultEditor, out.text)
        if (out.unresolved > 0) {
            // T-PreviewUx-Pack #2：还原不完整横幅（为什么=Parameters 缺失/残缺，能做什么=补全参数行）
            degradationBanner.show(
                cn.song.mybatisplus.visualizer.ui.DegradationBanner.Kind.RENDER_WARNING,
                MpSqlVisualizerBundle.message("logToSql.banner.unresolved", out.unresolved),
            )
        } else {
            degradationBanner.clear(cn.song.mybatisplus.visualizer.ui.DegradationBanner.Kind.RENDER_WARNING)
        }
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

    /** T-PreviewUx-Pack #2：诚实降级横幅（显隐/文案断言用）。 */
    internal fun degradationBannerForTest(): cn.song.mybatisplus.visualizer.ui.DegradationBanner = degradationBanner

    // ------------------------------------------------------------------
    // T-PreviewUx-Pack #4：多语句执行确认（候选模型 / 渲染器 / 语句拆分）
    // ------------------------------------------------------------------

    /** 候选项（纯数据，可单测）：序号 + 首关键字摘要 + 完整语句。 */
    internal data class StatementCandidate(val index: Int, val keyword: String, val sql: String) {
        /** 摘要行：`SQL #N · 关键字`（CrudSqlPreviewFlow.summary 同款 `·` 分隔风格）。 */
        fun summary(): String =
            MpSqlVisualizerBundle.message("logToSql.status.group", index) + " · " + keyword
    }

    /**
     * 候选行渲染器：主题色双行（加粗摘要 + 次要色详情），CrudSqlPreviewFlow.CandidateCellRenderer
     * 同款范式；详情行禁 HTML 解析（语句可能以 `<` 开头），按 [SqlSelectionDialog.MAX_LINE_LENGTH] 截断。
     */
    private class StatementCandidateRenderer : JPanel(BorderLayout()), ListCellRenderer<StatementCandidate> {
        private val summaryLabel = JLabel()
        private val detailLabel = JLabel()

        init {
            isOpaque = true
            border = JBUI.Borders.empty(6, 12)
            summaryLabel.font = summaryLabel.font.deriveFont(java.awt.Font.BOLD)
            detailLabel.putClientProperty("html.disable", true)
            detailLabel.foreground = UIUtil.getContextHelpForeground()
            add(summaryLabel, BorderLayout.NORTH)
            add(detailLabel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out StatementCandidate>,
            value: StatementCandidate,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component {
            summaryLabel.text = value.summary()
            val firstLine = value.sql.lineSequence().firstOrNull().orEmpty()
            detailLabel.text =
                if (firstLine.length > cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog.MAX_LINE_LENGTH) {
                    firstLine.take(cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog.MAX_LINE_LENGTH) + "…"
                } else {
                    firstLine
                }
            background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            summaryLabel.foreground = UIUtil.getListForeground()
            detailLabel.foreground = if (selected) UIUtil.getListForeground() else UIUtil.getContextHelpForeground()
            return this
        }
    }

    internal fun statementsForTest(): List<String> = splitSqlStatements(resultEditor.document.text)

    internal fun loadFromEntryForTest(entry: LogSqlEntry) = loadFromEntry(entry)

    companion object {

        /**
         * T-PreviewUx-Pack #4：结果文本 → 语句列表（纯函数）——按顶层 `;` 切分，
         * 引号字符串内的 `;` 不切分；`--` 行注释与块注释内的 `;` 不切分（LogToSqlPanel 产物
         * 组间自带 `-- SQL #N` 注释头，注释随所属语句一并保留——原生控制台接受注释）。
         * 段间空白丢弃，空列表 = 无可执行语句。
         *
         * T-SemicolonGuard（O-13）：实现收敛委托至 util/SqlStatementSplitter
         * （单实现三处历史切分合一）；新增括号深度与 BEGIN/END 块感知两项语义
         * （迁移前后既有样例输出逐段一致，守卫测试锁死）。
         */
        internal fun splitSqlStatements(sql: String): List<String> =
            cn.song.mybatisplus.visualizer.util.SqlStatementSplitter.split(sql)

        /**
         * T-BatchInMerge（O-14）：批量等值谓词合并参考文案（纯函数可测；null = 无合并集）。
         * 异构语句在场时按「N/M 条」披露（不静默丢）；展示预算截值清单时**计数后缀恒可见**
         * （先裁值再拼「…等 N 条」，N = 行数/字节超限 + 展示裁掉的值数之和——绝不无计数截断）。
         */
        internal fun buildMergeAdText(statements: List<String>): String? {
            val preview = cn.song.mybatisplus.visualizer.util.BatchPredicateMerge.mergePreview(statements) ?: return null
            val prefix = "DELETE FROM ${preview.table} WHERE ${preview.column} IN ("
            // 展示预算：前缀 + 值清单 + 收括号 ≤ 上限；后缀占位先扣（保证计数可见）
            val budget = MERGE_HINT_SQL_DISPLAY_LIMIT - prefix.length - 1
            val joined = ArrayList<String>()
            var used = 0
            var dropped = 0
            for ((index, value) in preview.values.withIndex()) {
                val cost = value.length + if (index > 0) 2 else 0
                if (used > 0 && used + cost > budget - MERGE_MORE_RESERVE) {
                    dropped = preview.values.size - joined.size
                    break
                }
                used += cost
                joined += value
            }
            val hidden = preview.hiddenValues + dropped
            val moreText = if (hidden > 0) MpSqlVisualizerBundle.message("logToSql.execute.mergeMore", hidden) else ""
            val inner = joined.joinToString(", ") + if (moreText.isNotEmpty()) ", $moreText" else ""
            val displaySql = "$prefix$inner)"
            return if (preview.coveredCount == preview.totalStatements) {
                MpSqlVisualizerBundle.message("logToSql.execute.mergeHint", preview.coveredCount, displaySql)
            } else {
                MpSqlVisualizerBundle.message(
                    "logToSql.execute.mergeHintSubset",
                    preview.totalStatements, preview.coveredCount, displaySql,
                )
            }
        }

        /** T-BatchInMerge：合并参考行里的 SQL 展示截断（防浮层单行撑爆）。 */
        private const val MERGE_HINT_SQL_DISPLAY_LIMIT = 160

        /** T-BatchInMerge：「…等 N 条」后缀的展示预算预留（截值不截计数）。 */
        private const val MERGE_MORE_RESERVE = 24

        /** T-Perf-14：大日志后台转换阈值——字符数（约 1MB）。 */
        private const val HEAVY_INPUT_CHARS = 1_000_000
        /** T-Perf-14：大日志后台转换阈值——行数（约 1000 行）。 */
        private const val HEAVY_INPUT_LINES = 1_000
        private const val TOOLBAR_ID_INPUT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.input"
        private const val TOOLBAR_ID_RESULT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.result"
    }
}
