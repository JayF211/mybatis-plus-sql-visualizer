package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport
import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.FillClause
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.share.ui.ShareDialog
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel
import cn.song.mybatisplus.visualizer.ui.diff.DiffHighlighter
import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlPreviewLanguage
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.CaseFormatUtil
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlConditionView
import cn.song.mybatisplus.visualizer.xml.XmlForEachView
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlPreviewState
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.Disposable
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
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
class SqlPreviewPanel(private val project: Project? = null) : JBPanel<SqlPreviewPanel>(BorderLayout()), Disposable {

    private val metaLabel = JBLabel()

    // T-M26.6：格式化快捷键动作（跟随 IDE keymap 的 Reformat Code，兜底 Ctrl+Alt+L）——
    // 注册到面板整体，焦点在 SQL 编辑器或参数输入框时均可触发；每次触发取当前 editor 格式化
    private val reformatAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            formatSql()
        }
    }

    // 左：动态条件复选框 + 参数值输入区
    private val paramPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    // 顶部对齐容器：BoxLayout Y_AXIS 在视口高于内容时默认垂直居中；
    // 用 BorderLayout.NOWRAP 包裹后固定参数行在顶部（用户要求"向上靠"）
    private val paramTopAlign = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(paramPanel, BorderLayout.NORTH)
        isOpaque = false
    }
    // T-M26.3：滚动区提升为字段——截断上限（标签列/复选框文本）按视口宽度计算，
    // 保证行宽不超出视口（标题行右侧按钮不被推出）
    private val paramScroll = JBScrollPane(paramTopAlign).apply {
        preferredSize = Dimension(240, 200)
        isOpaque = false
        viewport.isOpaque = false
    }
    private val paramFields = mutableListOf<JBTextField>()
    private val conditionCheckBoxes = mutableListOf<JCheckBox>()
    private var autoPopupDisposable: Disposable? = null
    private val conditionValueFields = mutableListOf<JBTextField>()
    // T-M23-04：批量列级勾选复选框（INSERT/UPDATE/UPSERT 参数列，与动态条件复选框互斥——批量预览无 conditionViews）
    private val batchCheckBoxes = mutableListOf<JCheckBox>()
    // T-M26.3：全选/全不选按钮回挂分区标题行右侧（IDEA 组操作惯例，T-M23.2 原生形态；
    // T-M26.2 曾固定面板右上角，长文本截断后按钮不再被推出视口）。
    private var selectAllButton: JButton? = null
    private var selectNoneButton: JButton? = null
    private var clearButton: JButton? = null        // 动态条件分区清除按钮
    private var batchClearButton: JButton? = null    // 批量参数列分区清除按钮
    // T-M23.2：批量「参数列」区全选/全不选按钮
    private var batchSelectAllButton: JButton? = null
    private var batchSelectNoneButton: JButton? = null

    // 右：可编辑 SQL 编辑器（PSI 支撑，补全用）
    private var editor: Editor? = null

    /** T-M27.2：自建 autopopup 的文档监听器与调度防重入标志（平台 autopopup 对非文件编辑器不生效）。 */
    private var autoPopupListener: com.intellij.openapi.editor.event.DocumentListener? = null
    private var autoPopupScheduled = false

    /** T-M27.4：渲染 setText 期间抑制自建 autopopup（格式化替换整文档不触发补全）。 */
    private var suppressAutoPopup = false
    private var psiFile: PsiFile? = null
    private val editorHolder = JBPanel<JBPanel<*>>(BorderLayout())

    private var current: PreviewResult? = null
    private var compactMode = false

    // O-4：差异高亮状态（会话级缓存上一次 SQL + diff 开关）
    /** O-4：上一次渲染的 SQL（会话缓存，用于 diff 对比）。 */
    private var previousSql: String? = null
    /** O-4：差异高亮开关（默认开启）。 */
    private var diffHighlightEnabled = true

    // F-1：JOIN 关系图（仅 SELECT 含 JOIN 时显示标签页）
    private val diagramPanel = DiagramPanel(project)
    /** 内容区卡片：null=无内容 / "sql"=SQL编辑 / "sql+diagram"=SQL编辑 + JOIN图标签页。 */
    private var contentPane: JBPanel<JBPanel<*>>? = null
    private var contentTabPane: JTabbedPane? = null

    // M12：动态条件启用集合（勾选状态，元素为 ConditionToggle.id）+ 参数值（按 source 绑定，取代位置绑定）
    private val enabledIds = mutableSetOf<String>()
    private val paramValues = mutableMapOf<String, String>()
    // T-M24-04：XML 预览渲染状态（条件开关复用 enabledIds，id = test 表达式原文；
    // T-M26.4：bind 覆盖状态已移除——bind 值由「参数」区输入统一承担）
    private val xmlForEachCounts = mutableMapOf<String, Int>()
    private val xmlForEachNulls = mutableSetOf<String>()
    private var xmlErrors = listOf<String>()
    /** T-M25.4：最近一次 XML 渲染的参数映射（集合值输入按当前渲染键回填）。 */
    private var xmlMappings = emptyList<cn.song.mybatisplus.visualizer.xml.ParameterMapping>()
    // T-M25.3：错误信息复制按钮（warning 存在时可见，一键复制完整错误文本）
    private var errorCopyButton: JButton? = null
    private var currentWarning: String? = null

    // P-05：参数历史缓存 + debounce 写回
    private val paramHistoryCache = ParamHistoryCache()
    /** P-05：debounce 写回调度器（Swing Timer，500ms 延迟，restart 实现防抖）。 */
    private val historyAlarm = javax.swing.Timer(500) { flushHistoryToCache() }.apply { isRepeats = false }

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
        // T-M26.3：按钮回挂分区标题行右侧（conditionSectionHeader/batchSectionHeader），
        // 左面板即参数滚动区（T-M26.2 的固定操作行已拆除——长文本按视口截断后按钮不再被推出视口）
        val split = ThemedSplitPane(paramScroll, editorHolder).apply {
            resizeWeight = 0.3
            isContinuousLayout = true
            isOneTouchExpandable = false
            dividerSize = 3
            background = UIUtil.getPanelBackground()
        }
        add(split, BorderLayout.CENTER)

        // T-M26.6：注册格式化快捷键——组件级 custom shortcut 命中即消费按键，
        // 不与 IDE 全局 Reformat Code 双触发（预览语言无 formatter，全局动作本不可用）
        reformatAction.registerCustomShortcutSet(
            CustomShortcutSet(*reformatShortcuts()),
            this,
        )

        // T-DarkTheme：主题切换时重绘（工具窗口模式下实时响应；命名色自动更新，仅需触发重绘）
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { lookAndFeelChanged() })

        // F-1：注册图集面板到 Disposer 树（级联释放后台渲染任务）
        Disposer.register(this, diagramPanel)
    }

    /** T-DarkTheme：主题变更回调——重绘面板（命名色已自动更新，仅需触发 UI 刷新）。 */
    private fun lookAndFeelChanged() {
        revalidate()
        repaint()
    }

    private lateinit var modeButton: JButton

    /**
     * T-M26.1：分隔条主题化分栏——Swing JSplitPane 的分隔条由 LAF 的 SplitPaneUI 绘制：
     * 平台 LAF 委托 Metal，MetalSplitPaneDivider 直接以 Metal 主题浅色（controlColor）填充，
     * 与 JSplitPane/divider 的 setBackground 均无关，深色主题下呈现白色竖条纹割裂感。
     * 此处覆写 paint，在 LAF 绘制完成后把分隔条区域覆盖为面板底色（亮/暗主题均与面板
     * 融为一体；拖拽/布局行为不变，拖拽中分隔条移动时本覆盖随 dividerLocation 更新）。
     */
    private class ThemedSplitPane(left: JComponent, right: JComponent) :
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right) {
        override fun paint(g: Graphics) {
            super.paint(g)
            // 注意：dividerLocation 因 setDividerLocation(int/double) 重载无法作 Kotlin 属性合成，须显式 getter
            val loc = getDividerLocation()
            if (loc < 0) return
            g.color = UIUtil.getPanelBackground()
            g.fillRect(loc, 0, getDividerSize(), height)
        }
    }

    /** 顶部工具栏：复制错误（有警示时可见）/ 单行多行切换 / 格式化 / 导入（P-05）/ 刷新（执行 SQL / 复制 SQL 在弹窗底部按钮区，M17.4）。 */
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
        // M-KeywordWrap：关键字换行（子句顶格换行、同行内容压一行）
        add(smallButton(MpSqlVisualizerBundle.message("panel.keywordWrap")) { formatClauseLine() })
        // P-05：导入按钮（默认点击 = 从剪贴板导入）
        add(smallButton(MpSqlVisualizerBundle.message("panel.param.import")) { importParamsFromClipboard() })
        // O-4：差异高亮开关（本次 vs 上次预览）
        add(smallButton(MpSqlVisualizerBundle.message("panel.diff")) { toggleDiffHighlight() })
        // O-4：SQL Diff 并排对比
        add(smallButton(MpSqlVisualizerBundle.message("panel.compare")) { showSqlDiff() })
        // F-8：分享按钮（Markdown / HTML / 可执行 SQL）
        add(smallButton(MpSqlVisualizerBundle.message("panel.share")) { showShareDialog() })
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
        // 参数值按 source 初始化（T-M42：优先取预填初始值——日志识别 `param1` → 日志参数值；
        // 其余空字符串；多条件复用同一 source 共享一个值）
        paramValues.clear()
        result.unresolvedParams.forEach { source -> paramValues.putIfAbsent(source, result.initialParamValues[source] ?: "") }
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
            xmlErrors = emptyList()
            // T-M25.5：预渲染提取 bind 自动求值结果（参数行初始显示求值后的值，用户可覆盖）
            val initial = model.renderSql(XmlPreviewState(
                enabledIds = enabledIds.toSet(),
                forEachCounts = xmlForEachCounts.toMap(),
            ))
            initial.bindValues.forEach { (name, value) ->
                if (paramValues[name].isNullOrEmpty()) paramValues[name] = value
            }
        }
        // P-05：历史召回（在 initialParamValues 之后，用户输入之前）
        recallFromHistory()
        // 模型级重生成路径：初始单行/多行与格式化设置对齐（旧文本路径保持默认多行）
        result.previewModel?.let { compactMode = !it.formatSql }
        modeButton.text = modeButtonText()
        rebuildLeftPanel(result)
        refreshCompletionContext()
        renderSql()
        // O-4：差异高亮（本次 vs 上次预览）
        if (diffHighlightEnabled) {
            applyDiffHighlight(result.previousSql)
        }
        updateMeta(result)
        // F-1：更新 JOIN 图标签页（仅 SELECT 含 JOIN 时显示）
        updateDiagramTab(result)
        // O-4：缓存当前 SQL 供下次 diff
        previousSql = editor?.document?.text
    }

    /**
     * F-1：根据当前预览结果更新 JOIN 图标签页。
     *
     * 仅当 previewModel.statement 为 [SelectStatement] 且含非空 joins 时显示"JOIN 图"标签页；
     * 否则移除标签页（如有）。PlantUML 文本由 [JoinDiagramGenerator] 生成，渲染由 [DiagramPanel] 后台完成。
     */
    private fun updateDiagramTab(result: PreviewResult) {
        val statement = result.previewModel?.statement
        val hasJoins = statement is SelectStatement && statement.joins.isNotEmpty()
        if (hasJoins) {
            // 确保内容区已切换为标签页模式
            ensureTabbedContent()
            // 构建结构化模型并展示
            val model = JoinDiagramGenerator.buildDiagramModel(statement as SelectStatement)
            diagramPanel.showDiagram(model)
        } else {
            // 无 JOIN：移除 diagram 标签页（恢复纯 SQL 视图）
            removeDiagramTab()
            diagramPanel.clear()
        }
    }

    /** 确保内容区为标签页模式（含 SQL + 可选的 JOIN 图）。 */
    private fun ensureTabbedContent() {
        val tabPane = contentTabPane
        if (tabPane != null) return // 已切换
        // 从当前布局中取出 split（SQL 视图）
        val center = (layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
        if (center != null) remove(center)
        // 构建标签页
        val pane = JTabbedPane().apply {
            addTab(MpSqlVisualizerBundle.message("panel.tab.sql"), center)
            addTab(MpSqlVisualizerBundle.message("diagram.tab.title"), diagramPanel)
        }
        // F-42 修复：渲染完成时组件可能处于 hidden（标签页未被选中），swapContent/revalidate
        // 对 hidden 组件无效——切到"JOIN 图"标签页时强制刷新，使图像真正显示
        pane.addChangeListener {
            if (pane.selectedIndex == 1) {
                diagramPanel.refreshAfterVisible()
            }
        }
        add(pane, BorderLayout.CENTER)
        contentTabPane = pane
        revalidate()
        repaint()
    }

    /** 移除 JOIN 图标签页（恢复纯 SQL 视图）。 */
    private fun removeDiagramTab() {
        val tabPane = contentTabPane ?: return
        val sqlContent = tabPane.getComponentAt(0)
        remove(tabPane)
        add(sqlContent, BorderLayout.CENTER)
        contentTabPane = null
        revalidate()
        repaint()
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
        // T-M27.4：联表候选表（XML 预览经 resolveXml 注入；预览补全表名候选）
        candidateTables = current?.candidateTables.orEmpty(),
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
                    // T-M26.4：bind 覆盖已由「参数」区输入承担（回填层覆盖），渲染走表达式自动求值
                    bindOverrides = emptyMap(),
                )
                val rendered = xml.renderSql(state)
                xmlErrors = rendered.errors
                xmlMappings = rendered.mappings
                // T-M25.5：bind 自动求值结果并入参数回填（用户手动输入优先，清空则恢复自动值）
                rendered.bindValues.forEach { (name, value) ->
                    if (paramValues[name].isNullOrEmpty()) paramValues[name] = value
                }
                val base = rendered.sql
                val sql = if (compactMode) base.replace(WHITESPACE_REGEX, " ").trim() else base
                val sources = rendered.mappings.map { it.property }
                // T-M29.5：参数回填引号按 mapper 方法参数 Java 类型判定（XmlParamTypeResolver 注入
                // model.paramQuotes：DTO 字段 LocalDate→引号/Integer→裸渲染，贴近真实绑定效果）；
                // 未命中（Map/无类型信息）→ 兜底引号（T-M29.4：日期/字符串列安全，数值列隐式转换等价）
                val inListIndices = detectInClauseParams(sql)
                val xmlHints = sources.mapIndexed { index, source ->
                    ParamHint(quote = xml.quoteFor(source) ?: true, inList = index in inListIndices)
                }
                showSql(backfillParamsBySource(sql, sources, xmlHints))
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

    /** 关键字换行格式化：子句顶格换行、同行内容压一行（[SqlFormatter.formatClauseLine]）。 */
    private fun formatClauseLine() {
        val editor = editor ?: return
        val formatted = SqlFormatter.formatClauseLine(editor.document.text)
        if (formatted != editor.document.text) {
            WriteCommandAction.runWriteCommandAction(project) { editor.document.setText(formatted) }
        }
    }

    /**
     * T-M26.6：格式化快捷键集——当前激活 keymap 中 Reformat Code（Ctrl+Alt+L）的绑定
     * （跟随用户自定义键位）；keymap 无绑定时兜底默认 Ctrl+Alt+L。
     * 注：2025.3 平台 IdeActions 无 ReformatCode 常量，用动作 id 字符串。
     */
    private fun reformatShortcuts(): Array<Shortcut> {
        val fromKeymap = KeymapManager.getInstance().activeKeymap.getShortcuts("ReformatCode")
        if (fromKeymap.isNotEmpty()) return fromKeymap
        return arrayOf(
            KeyboardShortcut(
                KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_MASK or InputEvent.ALT_MASK),
                null,
            ),
        )
    }

    /** 单行 / 多行切换。 */
    fun toggleCompact(): Boolean {
        compactMode = !compactMode
        renderSql()
        return compactMode
    }

    /** 一键复制当前 SQL（取编辑器当前内容，含手动编辑与参数回填）。 */
    fun copySql(): Boolean {
        val raw = editor?.document?.text?.takeIf { it.isNotBlank() } ?: return false
        // 复制时剥离 SQL 注释（-- 行注释 + /* */ 块注释），与 Java 代码复制对齐
        val text = SqlCommentStripper.strip(raw)
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
     * O-4：一键复制可执行 SQL（替换 `?` 为带类型处理的字面值，复制到剪贴板 + toast 提示）。
     * 委托 [CopyExecutableSqlAction] 处理替换逻辑与通知。
     */
    fun copyExecutableSql() {
        CopyExecutableSqlAction(project).execute(this)
    }

    /**
     * F-8：显示分享选项对话框（Markdown / HTML / 可执行 SQL）。
     * 委托 [cn.song.mybatisplus.visualizer.share.ui.ShareDialog]。
     */
    fun showShareDialog() {
        ShareDialog(project, this).show()
    }

    // ------------------------------------------------------------------
    // O-4：差异高亮（本次 vs 上次预览）
    // ------------------------------------------------------------------

    /**
     * O-4：对当前编辑器应用差异高亮（本次 SQL vs [oldSql]）。
     * 首次预览（oldSql=null）不显示差异。
     *
     * @param oldSql 上一次预览的 SQL（null = 首次预览）
     */
    private fun applyDiffHighlight(oldSql: String?) {
        val ed = editor ?: return
        if (oldSql.isNullOrEmpty()) return
        val newSql = ed.document.text
        // 后台计算 diff（< 100ms for < 1000 lines），EDT 渲染高亮
        ApplicationManager.getApplication().executeOnPooledThread {
            val diff = SqlDiffer.computeDiff(oldSql, newSql)
            ApplicationManager.getApplication().invokeLater {
                if (!ed.isDisposed) {
                    DiffHighlighter.highlight(ed, diff, newSql)
                }
            }
        }
    }

    /** O-4：切换差异高亮开关。 */
    fun toggleDiffHighlight() {
        diffHighlightEnabled = !diffHighlightEnabled
        val ed = editor ?: return
        if (!diffHighlightEnabled) {
            DiffHighlighter.clear(ed.markupModel)
        } else {
            applyDiffHighlight(previousSql)
        }
    }

    /**
     * O-4：显示 SQL 并排 Diff 弹窗（当前 SQL vs 上次预览 SQL）。
     * 无上次预览时 toast 提示。
     */
    fun showSqlDiff() {
        val proj = project ?: return
        val currentSql = editor?.document?.text?.takeIf { it.isNotBlank() } ?: return
        val oldSql = previousSql ?: run {
            showDiffToast(MpSqlVisualizerBundle.message("preview.diff.noPrevious"))
            return
        }
        SqlDiffDialog(
            project = proj,
            leftSql = oldSql,
            rightSql = currentSql,
            leftTitle = MpSqlVisualizerBundle.message("panel.diff.previous"),
            rightTitle = MpSqlVisualizerBundle.message("panel.diff.current"),
        ).show()
    }

    /** O-4：显示 diff 相关 toast 通知。 */
    private fun showDiffToast(message: String) {
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) return
        val proj = project ?: return
        runCatching {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, com.intellij.notification.NotificationType.INFORMATION)
                .notify(proj)
        }
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
        xmlErrors = emptyList()
        currentWarning = null
        errorCopyButton?.isVisible = false
        selectAllButton = null
        selectNoneButton = null
        batchSelectAllButton = null
        batchSelectNoneButton = null
        paramPanel.removeAll()
        // O-4：清除差异高亮 + 重置会话缓存
        editor?.let { DiffHighlighter.clear(it.markupModel) }
        previousSql = null
        releaseEditor()
        editorHolder.removeAll()
        metaLabel.text = ""
        // F-1：清除图集 + 恢复纯 SQL 视图
        removeDiagramTab()
        diagramPanel.clear()
        revalidate()
        repaint()
    }

    // ------------------------------------------------------------------
    // 编辑器（PSI 支撑 + 可编辑 + 高亮）
    // ------------------------------------------------------------------

    /** 渲染 SQL 到可编辑编辑器；已存在时更新文档（保留焦点/撤销）。 */
    private fun showSql(sql: String) {
        // T-MpjFill：追加 fill 附加查询段（MPJ 1.5.1+）——在主 SQL 之后渲染 fill 附加 SELECT
        val withFill = appendFillSection(sql)
        // T-M27.4：渲染自动格式化（设置开启且非单行模式）——XML 动态标签展开残留的空行/缩进
        // 由格式化器归一（关键字大写、主子句顶格、列清单换行缩进、AND 缩进、子查询缩进）；
        // 单行模式保持紧凑（格式化会引入换行）
        val text = if (formatSqlSetting() && !compactMode) SqlFormatter.format(withFill) else withFill
        val existing = editor
        if (existing != null) {
            if (existing.document.text != text) {
                suppressAutoPopup = true
                try {
                    WriteCommandAction.runWriteCommandAction(project) { existing.document.setText(text) }
                } finally {
                    suppressAutoPopup = false
                }
            }
            return
        }
        createEditor(text)
    }

    /**
     * T-MpjFill：追加 fill 附加查询段（MPJ 1.5.1+）。
     *
     * 在主 SQL 之后追加 fill 附加 SELECT 渲染（每 fill 调用独立一行），
     * 以注释头 `-- fill additional query (MPJ 1.5.1+)` 标识。
     * 无 fill 调用时原样返回。
     */
    private fun appendFillSection(sql: String): String {
        val result = current ?: return sql
        val fills = result.fillClauses ?: return sql
        if (fills.isEmpty()) return sql
        val dialect = result.previewModel?.dialect ?: MySqlDialect
        val sb = StringBuilder(sql)
        sb.append("\n\n-- ").append(MpSqlVisualizerBundle.message("panel.fill.section")).append("\n")
        fills.forEach { fill ->
            sb.append(SqlGenerator.generateFill(fill, dialect))
            sb.append("\n")
        }
        return sb.toString()
    }

    /** 首次创建：SqlPreviewLanguage PSI 文件 → 文档 → 可编辑编辑器，附补全上下文（Editor + PSI 双载体，M16.2）。 */
    private fun createEditor(sql: String) {
        val proj = project
        val created = if (proj != null) {
            val psi = createPreviewFileForTest(proj, sql)
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
        // T-M27.1：编辑器组件链补 DataProvider——2025.3 平台编辑器内部改为 UiCompatibleDataProvider
        // （仅新 UI 兼容查询路径生效），传统 DataManager.getDataContext 沿父链只认 DataProvider 接口，
        // 面板编辑器获得焦点时 Ctrl+Space 的 CodeCompletionAction 拿不到 CommonDataKeys.EDITOR
        // （断言 editor != null 静默失败）→ 补全无反应（用户实测）。外层包装 DataProvider 提供
        // EDITOR：真实 IDE 的 DataManagerImpl 沿组件父链查找即命中，快捷键路径恢复
        editorHolder.add(
            object : JBPanel<JBPanel<*>>(BorderLayout()), DataProvider {
                override fun getData(dataId: String): Any? =
                    if (CommonDataKeys.EDITOR.`is`(dataId)) created else null
            }.apply { add(created.component, BorderLayout.CENTER) },
            BorderLayout.CENTER,
        )
        editorHolder.revalidate()
        editorHolder.repaint()
        editor = created
        bindAutoPopup(created)
    }

    /**
     * 创建预览 PSI 文件（测试缝）：抽取 [createEditor] 中的 PsiFileFactory 创建逻辑为独立方法，
     * 供测试直接调用（绕过 EDT 回调 + EditorFactory 创建）。
     *
     * T-M42（用户实测堆栈）：PsiFileFactory.createFileFromText 在 EDT 上直接调用会触发
     * 2026.2 平台 markGenerated 树遍历 softAssertReadAccess 硬抛 RuntimeExceptionWithAttachments——
     * 包 ReadAction（EDT 上同步执行，语义不变）。
     *
     * @param proj 当前项目（非 null）
     * @param sql  预览 SQL 文本
     * @return 创建的 PsiFile
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    internal fun createPreviewFileForTest(proj: Project, sql: String): PsiFile {
        return ReadAction.compute<PsiFile, Throwable> {
            PsiFileFactory.getInstance(proj)
                .createFileFromText("preview.mpsql", SqlPreviewLanguage, sql)
        }
    }

    /**
     * T-M27.2：自建输入自动补全（用户实测：面板内输入字符无自动提示，与 Java 代码编辑器行为不一致）。
     *
     * 平台 autopopup 链路（TypedHandler → CompletionAutoPopupHandler → AutoPopupController →
     * CodeCompletionHandlerBase(autopopup=true)）对**非项目文件编辑器**不生效——`CompletionProgressIndicator`
     * 的 autopopup 流程显示**空弹窗**（contributor 未被调度；探针实证：手动模式 contributor 正常被调用、
     * forParameters 含本插件 contributor，仅 autopopup 模式未调度，与语言无关——Java 文件同为空）。
     * 修复：面板监听文档输入（标识符字符），直接调**手动模式**补全处理器，绕开平台
     * autopopup 调度。关键参数：构造第 4 参 explicit=true（探针实证：false 时 contributor
     * 不被调度、弹空窗）+ 补全前 commitDocument（未 commit 时前缀按旧 PSI 文本计算误过滤）。
     * 防回环：有效 lookup 激活/删除/非标识符/调度中均不重复触发（平台空弹窗不拦截，
     * 由自建补全接管）。
     */
    private fun bindAutoPopup(created: Editor) {
        val listener = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                if (suppressAutoPopup) return
                if (event.newLength == 0 || event.oldLength > event.newLength) return
                val ed = editor ?: return
                if (ed.isDisposed || project == null) { return }
                // 平台 autopopup 会先弹**空弹窗**（contributor 未调度）——空弹窗不拦截，
                // 由自建补全接管；仅"已有有效项"的补全（lookup 选中插入回环）拦截
                val active = LookupManager.getActiveLookup(ed)
                if (active != null && active.items.isNotEmpty()) { return }
                if (autoPopupScheduled) { return }
                // 插入的最后一个字符为标识符（字母/数字/_）才弹（中文输入等场景不触发）。
                // 用**事件插入位置**而非 caret——DocumentListener 在 caret 移动前触发（真实输入同时序）
                val insertEnd = event.offset + event.newLength
                if (insertEnd <= 0) return
                val prev = ed.document.getText(TextRange.create(insertEnd - 1, insertEnd)).firstOrNull() ?: return
                if (!prev.isLetterOrDigit() && prev != '_') { return }
                autoPopupScheduled = true
                ApplicationManager.getApplication().invokeLater {
                    autoPopupScheduled = false
                    val ed2 = editor ?: return@invokeLater
                    val proj = project ?: return@invokeLater
                    if (ed2.isDisposed) return@invokeLater
                    val active2 = LookupManager.getActiveLookup(ed2)
                    if (active2 != null && active2.items.isNotEmpty()) return@invokeLater
                    try {
                        // 先提交文档（PSI 同步）——补全前缀按编辑器文档计算，未 commit 时用旧 PSI
                        // 文本算前缀导致误过滤（如 "FROM Stbl" 按旧文本 "FROM tbl" 前缀算成 "t"）
                        com.intellij.psi.PsiDocumentManager.getInstance(proj).commitDocument(ed2.document)
                        CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
                            .invokeCompletion(proj, ed2, 1, false)
                    } catch (t: Throwable) {
                        // 补全异常不影响输入（弹窗失败下次输入重试）
                    }
                }
            }
        }
        // T-M52：双参数版（Disposable 管理监听生命周期，releaseEditor 时自动移除）
        if (autoPopupDisposable != null) Disposer.dispose(autoPopupDisposable!!)
        autoPopupDisposable = Disposer.newDisposable("SqlPreviewPanel.autoPopup")
        created.document.addDocumentListener(listener, autoPopupDisposable!!)
        autoPopupListener = listener
    }

    /** 释放当前编辑器（幂等）。 */
    private fun releaseEditor() {
        editor?.let {
            // T-M52：监听器由 Disposable 管理，dispose 即移除（无需手动 removeDocumentListener）
            autoPopupListener = null
            autoPopupDisposable?.let { d -> Disposer.dispose(d) }
            autoPopupDisposable = null
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
        // T-M26.3：按钮随场景重建（标题行右侧）——先清空上次场景的残留引用
        selectAllButton = null
        selectNoneButton = null
        clearButton = null
        batchSelectAllButton = null
        batchSelectNoneButton = null
        batchClearButton = null
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
        // T-M23.2（用户要求）：参数可选场景必须有【全选/全不选】按钮（标题行右侧，T-M26.3 回归）
        if (batchModel != null) {
            paramPanel.add(batchSectionHeader())
            // T-M26.3：fixed 列标签参与列宽统一（toggle 列复选框文本独立截断上限）
            val labelWidth = labelColumnWidth(batchModel.fixedColumns.map { "#${it.source}" })
            batchModel.toggleColumns.forEach { paramPanel.add(buildBatchColumnRow(it, isToggle = true)) }
            batchModel.fixedColumns.forEach { paramPanel.add(buildBatchColumnRow(it, isToggle = false, labelWidth)) }
            paramPanel.revalidate()
            paramPanel.repaint()
            return
        }

        // T-M24-04：XML 预览左栏——动态条件开关 + foreach 次数/null + 参数值输入
        // T-M26.4：bind 分区已删除——bind 求值结果由「参数」区回显（输入覆盖、清空恢复自动值）
        val xmlModel = result.xmlModel
        if (xmlModel != null) {
            val hasControls = xmlModel.conditionViews.isNotEmpty() || xmlModel.forEachViews.isNotEmpty()
            if (!hasControls && result.unresolvedParams.isEmpty()) {
                paramPanel.add(JBLabel(MpSqlVisualizerBundle.message("panel.param.none")).apply {
                    border = JBUI.Borders.empty(6, 8)
                })
                paramPanel.revalidate()
                paramPanel.repaint()
                return
            }
            // #{} 参数值输入（T-M25.4：foreach 展开项不再单独建行——集合值由 foreach 输入框统一回填）
            val ordinarySources = result.unresolvedParams.distinct().filter { !it.startsWith("__frch_") }
            // T-M26.3：两列行标签列宽统一（foreach/参数按最长可见标签自适应 140~260）
            // T-M26.5：foreach 行标签即集合表达式（分区标题已含「foreach 集合」语义）
            val labelWidth = labelColumnWidth(
                xmlModel.forEachViews.map { it.collectionExpr } +
                    ordinarySources.map { "#$it" },
            )
            if (xmlModel.conditionViews.isNotEmpty()) {
                paramPanel.add(conditionSectionHeader())
                xmlModel.conditionViews.forEach { paramPanel.add(buildXmlConditionRow(it)) }
            }
            if (xmlModel.forEachViews.isNotEmpty()) {
                paramPanel.add(sectionHeaderRow(MpSqlVisualizerBundle.message("panel.xml.foreach.section")))
                xmlModel.forEachViews.forEach { paramPanel.add(buildXmlForEachRow(it, labelWidth)) }
            }
            if (ordinarySources.isNotEmpty()) {
                if (hasControls) paramPanel.add(sectionHeaderRow(MpSqlVisualizerBundle.message("panel.param.section")))
                ordinarySources.forEach { paramPanel.add(buildParamRow(it, labelWidth)) }
            }
            paramPanel.revalidate()
            paramPanel.repaint()
            return
        }

        val viewSources = views.flatMap { it.valueSources }.toSet()
        // 普通参数行：未解析参数中不属于任何动态条件值输入框的来源（去重，同一 source 共享一个输入框）
        val ordinarySources = result.unresolvedParams.distinct().filter { it !in viewSources }
        val labelWidth = labelColumnWidth(ordinarySources.map { "#$it" })
        if (views.isNotEmpty()) {
            paramPanel.add(conditionSectionHeader())
            views.forEach { paramPanel.add(buildConditionRow(it)) }
        }
        if (ordinarySources.isNotEmpty()) {
            if (views.isNotEmpty()) paramPanel.add(sectionHeaderRow(MpSqlVisualizerBundle.message("panel.param.section")))
            ordinarySources.forEach { paramPanel.add(buildParamRow(it, labelWidth)) }
        }
        paramPanel.revalidate()
        paramPanel.repaint()
    }

    /**
     * T-M23-04：批量列行——toggle 列渲染「复选框（列来源，tooltip 列名）+ 值输入框」，
     * 勾选变更重渲染（列名入 [enabledIds]）；fixed 列渲染「#来源 标签 + 值输入框」。
     * T-M26.3：fixed 列标签按统一列宽（labelWidth）构建。
     */
    private fun buildBatchColumnRow(col: BatchColumn, isToggle: Boolean, labelWidth: Int = 0): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6))
        if (isToggle) {
            val checkBox = JCheckBox(checkBoxText(col.source), col.columnName in enabledIds).apply {
                toolTipText = if (checkBoxText(col.source) != col.source) col.source else col.columnName
                addActionListener {
                    if (isSelected) enabledIds.add(col.columnName) else enabledIds.remove(col.columnName)
                    renderSql()
                }
            }
            batchCheckBoxes += checkBox
            row.add(checkBox)
        } else {
            row.add(paramLabel(col.source, labelWidth))
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

    /**
     * T-M26.3：分区标题行（IDEA Group Header 惯例）——CENTER 为 TitledSeparator（短标题 + 分隔线），
     * EAST 为可选操作按钮组（全选/全不选回挂此处，T-M23.2 形态回归；T-M26.2 曾固定面板右上角，
     * 长文本截断后按钮不再被推出视口）。
     */
    private fun sectionHeaderRow(title: String, buttons: List<JButton> = emptyList()): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(BorderLayout())
        row.add(TitledSeparator(title), BorderLayout.CENTER)
        if (buttons.isNotEmpty()) {
            row.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 2)).apply {
                buttons.forEach { add(it) }
            }, BorderLayout.EAST)
        }
        row.border = JBUI.Borders.empty(0, 8, 2, 8)
        return row
    }

    /**
     * 批量「参数列」分区标题行（T-M23.2 回归，T-M26.3 TitledSeparator 化）：左侧分区标题 +
     * 右侧全选/全不选按钮（操作 [batchCheckBoxes]，与动态条件区按钮互不影响）。
     */
    private fun batchSectionHeader(): JBPanel<JBPanel<*>> {
        batchSelectAllButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll")) { setAllBatchColumns(true) }
        batchSelectNoneButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectNone")) { setAllBatchColumns(false) }
        batchClearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear")) { clearBatchValues() }
        return sectionHeaderRow(
            MpSqlVisualizerBundle.message("panel.batch.columns"),
            listOf( batchClearButton!!,batchSelectAllButton!!, batchSelectNoneButton!!),
        )
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

    /**
     * 动态条件分区标题行（T-M26.3）：TitledSeparator「动态条件」+ 右侧全选/全不选按钮
     * （T-M23.2 形态回归，IDEA 组操作惯例；XML 条件开关同样复用，见 T-M24-04）。
     */
    private fun conditionSectionHeader(): JBPanel<JBPanel<*>> {
        selectAllButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll")) { setAllConditions(true) }
        selectNoneButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectNone")) { setAllConditions(false) }
        clearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear")) { clearConditionValues() }
        return sectionHeaderRow(
            MpSqlVisualizerBundle.message("panel.condition.section"),
            listOf(clearButton!!,selectAllButton!!, selectNoneButton!!),
        )
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
        val rawLabel = view.label
        val displayLabel = checkBoxText(rawLabel)
        val checkBox = JCheckBox(displayLabel, view.id in enabledIds).apply {
            // T-M26.3：标签被截断时 tooltip 展示表达式原文（否则保留 flagSource 提示）
            toolTipText = if (displayLabel != rawLabel) rawLabel else conditionTooltip(view)
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

    /** 清除按钮：清空动态条件分区所有参数值（不影响勾选态）。 */
    private fun clearConditionValues() {
        val views = current?.previewModel?.conditionViews.orEmpty()
        views.forEach { view ->
            view.valueSources.ifEmpty { listOfNotNull(view.valueSource) }.forEach { source ->
                paramValues[source] = ""
                syncParamFieldText(source, "")
            }
        }
        renderSql()
        saveToHistory()
    }

    /** 清除按钮：清空批量参数列分区所有参数值（不影响勾选态）。 */
    private fun clearBatchValues() {
        val model = current?.batchModel ?: return
        (model.toggleColumns + model.fixedColumns).forEach { col ->
            paramValues[col.source] = ""
            syncParamFieldText(col.source, "")
        }
        renderSql()
        saveToHistory()
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
        saveToHistory()
    }

    /**
     * T-M24-04：XML 动态条件行——复选框（label = test 表达式，id 进 [enabledIds]），
     * 与 Wrapper 动态条件交互一致（全选/全不选按钮共用）。
     */
    private fun buildXmlConditionRow(view: XmlConditionView): JBPanel<JBPanel<*>> {
        val rawLabel = view.label
        val displayLabel = checkBoxText(rawLabel)
        val checkBox = JCheckBox(displayLabel, view.id in enabledIds).apply {
            // T-M26.3：标签被截断时 tooltip 展示 test 表达式原文（否则保留条件提示）
            toolTipText = if (displayLabel != rawLabel) rawLabel else MpSqlVisualizerBundle.message("panel.xml.condition")
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

    /** T-M25.4：foreach 行——**集合表达式标签 + 集合值输入框**（逗号分隔，如 `2,3` → 展开两项并回填）+ null 复选框（T-M26.5：标签即集合表达式，分区标题已含「foreach 集合」语义）。 */
    private fun buildXmlForEachRow(view: XmlForEachView, labelWidth: Int): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6))
        row.add(columnLabel(view.collectionExpr, labelWidth).apply {
            border = JBUI.Borders.empty(0, 8, 0, 0)
        })
        val valuesField = JBTextField(8).apply {
            toolTipText = "集合值（逗号分隔，如 2,3；留空 = 默认展开 ${XmlPreviewModel.DEFAULT_FOREACH_SIZE} 项）"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onXmlForEachValuesChanged(view, this@apply.text)
                override fun removeUpdate(e: DocumentEvent?) = onXmlForEachValuesChanged(view, this@apply.text)
                override fun changedUpdate(e: DocumentEvent?) = onXmlForEachValuesChanged(view, this@apply.text)
            })
        }
        row.add(valuesField)
        paramFields += valuesField
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

    /**
     * T-M25.4：foreach 集合值变更——逗号分隔值解析为展开项并**自动回填**：
     * `2,3` → 展开 2 项 + `IN ( 2 , 3 )`；留空 → 恢复默认展开次数并清空该 foreach 的参数值（`?` 保持）。
     * **先渲染一次刷新键清单**（逐字符输入时中间态 counts 会改变渲染键数量，直接取旧键会漏填），
     * 再按新键填值二次渲染。
     */
    private fun onXmlForEachValuesChanged(view: XmlForEachView, text: String) {
        val item = view.item ?: return
        val values = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        xmlForEachCounts[view.collectionExpr] =
            if (values.isEmpty()) XmlPreviewModel.DEFAULT_FOREACH_SIZE else values.size
        renderSql() // 第一次渲染：刷新 xmlMappings 为当前 counts 对应的键
        val keys = xmlMappings.map { it.property }.filter { it.startsWith("__frch_${item}_") }
        if (values.isEmpty()) {
            keys.forEach { paramValues.remove(it) }
        } else {
            keys.forEachIndexed { i, key ->
                if (i < values.size) paramValues[key] = values[i] else paramValues.remove(key)
            }
        }
        renderSql() // 第二次渲染：按值回填
    }

    /** 普通参数行：`#<source>` 标签 + 值输入框。 */
    private fun buildParamRow(source: String, labelWidth: Int): JBPanel<JBPanel<*>> {
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
            add(paramLabel(source, labelWidth))
            add(field)
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    /**
     * 参数行标签（T-M19.4-01 自适应 + T-M26.3 统一列宽）：`#source` 超列宽时中段省略显示、
     * tooltip 全名（IDEA 长文本惯例，用户 T-M19.4-01"长参数名显示不全"诉求由 tooltip 兜底）。
     */
    private fun paramLabel(source: String, columnWidth: Int): JBLabel = columnLabel("#$source", columnWidth)

    /**
     * T-M26.3：两列行标签——统一列宽（输入框左缘对齐，IDEA 表单惯例），
     * 文本超列宽时中段省略（保留尾部）+ tooltip 全名。
     */
    private fun columnLabel(fullText: String, columnWidth: Int): JBLabel = JBLabel(fullText).apply {
        val metrics = getFontMetrics(UIUtil.getLabelFont())
        if (metrics.stringWidth(fullText) > columnWidth) {
            toolTipText = fullText
            text = ellipsize(fullText, metrics, columnWidth)
        }
        preferredSize = Dimension(columnWidth, preferredSize.height)
    }

    /**
     * T-M26.3：两列行标签列宽——按最长可见标签自适应，**上限跟随左栏视口宽度**
     * （标签列 + 输入框约 90px + 间距边距 38px 不超出视口，保证标题行右侧按钮不被推出；
     * 默认 240 面板 → 上限约 109，宽面板放宽至 260 封顶；未布局/测试环境取 260）。
     */
    private fun labelColumnWidth(texts: List<String>): Int {
        if (texts.isEmpty()) return 140
        val metrics = getFontMetrics(UIUtil.getLabelFont())
        val viewport = paramScroll.viewport.width
        val maxWidth = if (viewport <= 0) 260 else (viewport - 128).coerceIn(80, 260)
        return texts.maxOf { metrics.stringWidth(it) }.coerceIn(80, maxWidth)
    }

    /** T-M26.3：长文本中段省略——保留尾部（bind 字段名/参数名尾部有意义），超宽时显示 `头…尾`。 */
    private fun ellipsize(text: String, metrics: FontMetrics, maxWidth: Int): String {
        val mark = "\u2026"
        if (metrics.stringWidth(text) <= maxWidth) return text
        val tailLen = (text.length * 0.3).toInt().coerceAtLeast(4).coerceAtMost(text.length - 1)
        val tail = text.takeLast(tailLen)
        var head = text.length - tailLen
        while (head > 0 && metrics.stringWidth(text.take(head) + mark + tail) > maxWidth) head--
        return text.take(head) + mark + tail
    }

    /**
     * T-M26.3：复选框标签截断上限——跟随视口宽度（文本 + 图标约 20px + 边距 36px 不超出视口，
     * 保证标题行右侧按钮不被推出；默认 240 面板 → 约 181；未布局/测试环境取 280）。
     */
    private fun checkBoxMaxWidth(): Int {
        val viewport = paramScroll.viewport.width
        return if (viewport <= 0) 280 else (viewport - 56).coerceIn(100, 280)
    }

    /** 复选框字体（与渲染一致，截断度量用——UIUtil.getLabelFont 与复选框字体在 headless 下可能不一致）。 */
    private val checkBoxFont: Font by lazy { JCheckBox().font }

    /** T-M26.3：复选框标签截断——test 表达式等长文本超上限时中段省略显示（tooltip 原文由调用方处理）。 */
    private fun checkBoxText(raw: String, maxWidth: Int = checkBoxMaxWidth()): String {
        val metrics = getFontMetrics(checkBoxFont)
        return if (metrics.stringWidth(raw) > maxWidth) ellipsize(raw, metrics, maxWidth) else raw
    }

    /** 普通参数值变更：更新回填表并重渲染 SQL。 */
    private fun onParamChanged(source: String, value: String) {
        paramValues[source] = value
        renderSql()
        saveToHistory()
    }

    // ------------------------------------------------------------------
    // P-05：参数批量导入 / 历史召回
    // ------------------------------------------------------------------

    /**
     * P-05.1：弹参数导入手动输入弹窗（工具栏按钮触发）。
     * 默认回显当前 SQL 参数（JSON 格式），用户可在此基础上修改后导入。
     * 用户粘贴/编辑文本后走 [ParamImportParser.parse] → [importParams] 回填。
     */
    fun importParamsFromClipboard() {
        val proj = project ?: return
        // 有参数时预填当前 paramValues（JSON 格式），无参数时弹空框 + 占位示例
        val preFill = if (paramValues.isEmpty()) null else serializeParamsToJson()
        ParamImportDialog(proj, preFill) { input ->
            importParams(ParamImportParser.parse(input))
        }.show()
    }

    /**
     * P-05.1：将当前 paramValues 序列化为 JSON 字符串（自实现，不依赖 Jackson）。
     * 过滤空值参数（未填写的不显示），key 按插入顺序排列。
     */
    private fun serializeParamsToJson(): String {
        val entries = paramValues.entries
            .filter { it.value.isNotEmpty() }
            .joinToString(",\n") { (key, value) ->
                val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                """  "${key}": "${escaped}""""
            }
        return if (entries.isEmpty()) "" else "{\n$entries\n}"
    }

    /**
     * P-05：按 source 模糊匹配导入参数值并回填。
     *
     * 模糊匹配规则：key ≡ source 或 key ≡ `#source`。
     * 回填 paramValues + 更新对应输入框文本 + 触发 renderSql()。
     * 未匹配项计入通知。
     *
     * @param params 导入的参数映射（key → value）
     */
    internal fun importParams(params: Map<String, String>) {
        if (params.isEmpty()) {
            showImportResult(0, 0, emptyList(), isEmptyClipboard = true)
            return
        }
        val allSources = buildAllSources()
        var matched = 0
        val unmatchedKeys = mutableListOf<String>()
        params.forEach { (key, value) ->
            val source = resolveSource(key, allSources)
            if (source != null) {
                paramValues[source] = value
                syncParamFieldText(source, value)
                matched++
            } else {
                unmatchedKeys += key
            }
        }
        renderSql()
        showImportResult(matched, unmatchedKeys.size, unmatchedKeys, isEmptyClipboard = false)
    }

    /** P-05：构建当前面板所有参数 source 集合（用于模糊匹配）。 */
    private fun buildAllSources(): Set<String> {
        val result = current ?: return emptySet()
        val sources = linkedSetOf<String>()
        sources += result.unresolvedParams
        result.previewModel?.conditionViews?.forEach { view ->
            view.valueSources.ifEmpty { listOfNotNull(view.valueSource) }.forEach { sources += it }
        }
        result.batchModel?.toggleColumns?.forEach { sources += it.source }
        result.batchModel?.fixedColumns?.forEach { sources += it.source }
        result.xmlModel?.forEachViews?.forEach { sources += it.collectionExpr }
        return sources
    }

    /**
     * P-05.1：模糊匹配 source（三级 fallback）。
     *
     *  1. 精确命中（key ≡ source）
     *  2. 去 `#` 前缀精确命中（key ≡ `#source`）
     *  3. 驼峰/下划线互转命中（`userName` ≡ `user_name`）
     *
     * 精确匹配优先，互转匹配兜底，避免风格差异导致漏匹配。
     */
    private fun resolveSource(key: String, sources: Set<String>): String? {
        // 1. 精确命中
        if (sources.contains(key)) return key
        // 2. 去 # 前缀精确命中
        val stripped = if (key.startsWith("#")) key.substring(1) else key
        if (sources.contains(stripped)) return stripped
        // 3. 驼峰/下划线互转命中
        val snake = CaseFormatUtil.camelToSnake(stripped)
        if (sources.contains(snake)) return snake
        val camel = CaseFormatUtil.snakeToCamel(stripped)
        if (sources.contains(camel)) return camel
        return null
    }

    /** P-05：同步参数输入框文本（导入回填时更新 UI 显示）。 */
    private fun syncParamFieldText(source: String, value: String) {
        val idx = currentParamFieldIndex(source)
        if (idx >= 0) {
            paramFields.getOrNull(idx)?.text = value
        }
    }

    /** P-05：按 source 查找 paramFields 索引（与 paramValues 按 source 绑定顺序一致）。 */
    private fun currentParamFieldIndex(source: String): Int {
        // paramFields 按 buildParamRow/buildBatchColumnRow/buildXmlForEachRow 追加顺序排列，
        // 与 unresolvedParams + 条件值来源的顺序一致；但 source 可能出现在多个场景，
        // 此处按 paramValues 键顺序定位（与输入框构建顺序一致）
        val sources = paramValues.keys.toList()
        return sources.indexOf(source)
    }

    /**
     * P-05：显示导入结果通知。
     *
     * @param matched    匹配数
     * @param unmatched  未匹配数
     * @param unmatchedKeys 未匹配的 key 列表
     * @param isEmptyClipboard 是否剪贴板无有效内容（true=空内容提示，false=匹配结果提示）
     */
    private fun showImportResult(matched: Int, unmatched: Int, unmatchedKeys: List<String>, isEmptyClipboard: Boolean) {
        // 测试环境跳过弹窗（Messages 在 test mode 下触发 TestDialog 拦截器抛异常）
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val title = MpSqlVisualizerBundle.message("panel.param.import.result.title")
        val proj = project ?: return
        when {
            isEmptyClipboard -> Messages.showErrorDialog(
                proj, MpSqlVisualizerBundle.message("panel.param.import.result.empty"), title,
            )
            unmatched == 0 -> Messages.showInfoMessage(
                proj, MpSqlVisualizerBundle.message("panel.param.import.result.success", matched), title,
            )
            else -> Messages.showMessageDialog(
                proj, MpSqlVisualizerBundle.message(
                    "panel.param.import.result.partial", matched, unmatched, unmatchedKeys.joinToString(", "),
                ), title, Messages.getWarningIcon(),
            )
        }
    }

    /** P-05：计算当前预览的缓存键（无表名返回 null，不缓存）。 */
    private fun historyCacheKey(): String? {
        val result = current ?: return null
        val tableName = result.tableName
        val rawSql = result.sql
        return paramHistoryCache.computeKey(tableName, rawSql)
    }

    /**
     * P-05：从历史缓存召回参数值（showResult 后调用）。
     *
     * 命中时按 source 回填（用户已输入值优先，不覆盖）。
     * 召回成功显示淡入提示。
     */
    private fun recallFromHistory() {
        val key = historyCacheKey() ?: return
        val history = paramHistoryCache.recall(key) ?: return
        if (history.isEmpty()) return
        var applied = 0
        history.forEach { (source, value) ->
            // 用户已输入值优先（不覆盖）
            if (paramValues[source].isNullOrEmpty()) {
                paramValues[source] = value
                syncParamFieldText(source, value)
                applied++
            }
        }
        if (applied > 0) {
            renderSql()
        }
    }

    /**
     * P-05：保存当前参数值到历史缓存（debounce 500ms）。
     *
     * 每次击键 restart 计时器（取消前次 + 重新调度），避免频繁序列化。
     */
    private fun saveToHistory() {
        historyAlarm.restart()
    }

    /** P-05：Timer 动作——实际写回缓存（由 historyAlarm 调度）。 */
    private fun flushHistoryToCache() {
        val key = historyCacheKey() ?: return
        paramHistoryCache.save(key, paramValues.toMap())
    }

    /**
     * 按出现顺序把 SQL 中的 `?` 替换为用户输入（按 source 定位参数值，取代位置绑定，见 M12-R2）。
     * 值渲染按回填提示 [ParamHint]（M13）：LIKE 位置拼 `%`、String 列恒引号、数值列恒原样；
     * 提示缺失/类型未知 → 数字启发式；空值一律保留 `?`（复选框纯手动，不联动勾选态）。
     */
    /**
     * 检测 SQL 中 IN 子句的参数索引。
     *
     * 扫描 `IN (?, ?, ...)` 模式，返回 IN 子句内 `?` 的参数索引集合。
     * 面板回填时这些参数按逗号分割多值渲染。
     */
    private fun detectInClauseParams(sql: String): Set<Int> {
        val result = mutableSetOf<Int>()
        var paramIndex = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '?') {
                // 检查前面是否有 IN ( 关键字
                val prefix = sql.substring(0, i).trimEnd().uppercase()
                if (prefix.endsWith("IN (") || prefix.endsWith("IN(")) {
                    result.add(paramIndex)
                }
                paramIndex++
            }
            i++
        }
        return result
    }

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
        // T-SqlPerf：性能建议文案单独一行（HTML 换行，避免与右侧工具栏重叠）
        val perfLine = buildPerfLine(result)
        val displayText = if (perfLine.isNotEmpty()) {
            // HTML 渲染：meta+warning 第一行，perf 第二行
            "<html>$meta$warningLine<br>$perfLine</html>"
        } else {
            meta + warningLine
        }
        metaLabel.text = displayText
        metaLabel.toolTipText = meta + warningLine + perfLine
        metaLabel.foreground = if (currentWarning != null) {
            // T-DarkTheme：IDE 标准错误前景命名色（亮暗主题自适应；暗色下非亮红不刺眼）
            JBColor.namedColor("ErrorForeground", JBColor.RED)
        } else {
            JBUI.CurrentTheme.Label.foreground()
        }
        // T-M25.3：错误复制按钮随警示可见
        errorCopyButton?.isVisible = currentWarning != null
    }

    /**
     * F-2：构建性能警告展示文本（拼接为单行摘要，供元信息标签展示）。
     * ERROR/WARN 标红前缀 ⚠，INFO 前缀 ℹ；多条警告以分号分隔。
     */
    private fun buildPerfLine(result: PreviewResult): String {
        val warnings = result.perfWarnings ?: return ""
        if (warnings.isEmpty()) return ""
        val sb = StringBuilder()
        warnings.forEach { warning ->
            val isError = warning.severity == PerfSeverity.ERROR || warning.severity == PerfSeverity.WARN
            val prefix = if (isError) "⚠ " else "ℹ "
            val localized = localizeWarning(warning)
            // HTML 颜色区分：ERROR/WARN 红色，INFO 蓝色
            val color = if (isError) "#E05555" else "#3B82F6"
            sb.append("   <font color=\"").append(color).append("\">").append(prefix).append(localized).append("</font>")
            if (warning != warnings.last()) sb.append("；")
        }
        return sb.toString()
    }

    /**
     * F-2：本地化单条性能警告（取 i18n 文案 + 参数替换）。
     */
    private fun localizeWarning(warning: PerfWarning): String {
        val args = warning.messageArgs.toTypedArray()
        return if (args.isNotEmpty()) {
            MpSqlVisualizerBundle.message(warning.messageKey, *args)
        } else {
            MpSqlVisualizerBundle.message(warning.messageKey)
        }
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

    /**
     * T-DarkTheme：释放主题监听（messageBus.connect(this) 随 Disposable 销毁自动取消订阅）。
     * 工具窗口模式下面板可能长期存活，由 [SqlPreviewDialog] 或工具窗口工厂调用释放。
     */
    override fun dispose() {
        // messageBus 连接经 connect(this) 绑定本 Disposable，Disposer.dispose 时自动取消订阅
        Disposer.dispose(this)
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    internal fun editorForTest(): Editor? = editor
    internal fun psiFileForTest(): PsiFile? = psiFile
    internal fun currentForTest(): PreviewResult? = current
    internal fun compactForTest(): Boolean = compactMode

    // ------------------------------------------------------------------
    // O-4：预览弹窗 UX 增强（外部组件访问面板状态）
    // ------------------------------------------------------------------

    /** O-4：当前编辑器 SQL 文本（含手动编辑与参数回填）；编辑器未创建时返回 null。 */
    fun editorDocumentText(): String? = editor?.document?.text

    /** O-4：当前预览的未解析参数来源列表（与 SQL 中 `?` 按出现顺序对应）。 */
    fun unresolvedParams(): List<String> = current?.unresolvedParams ?: emptyList()

    /** O-4：当前参数值快照（source → 用户输入值）。 */
    fun paramValuesSnapshot(): Map<String, String> = paramValues.toMap()

    /** F-8：当前预览的表名（可 null）。 */
    fun currentTableName(): String? = current?.tableName

    /** F-8：当前预览的方言名称（可 null）。 */
    fun currentDialect(): String? = current?.previewModel?.dialect?.name
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
    internal fun clearButtonForTest(): JButton? = clearButton
    internal fun batchClearButtonForTest(): JButton? = batchClearButton
    /** T-M26.1：左右分栏 JSplitPane（分隔条背景断言用）。 */
    internal fun splitPaneForTest(): JSplitPane? = components.filterIsInstance<JSplitPane>().firstOrNull()
    /** T-M26.6：格式化快捷键 action（注册断言 + 触发格式化测试用）。 */
    internal fun reformatActionForTest(): AnAction = reformatAction
    /** P-05：参数导入入口（测试调用用，绕过剪贴板）。 */
    internal fun importParamsForTest(params: Map<String, String>) = importParams(params)
    /** P-05：参数历史缓存（大小断言用）。 */
    internal fun paramHistoryCacheForTest(): ParamHistoryCache = paramHistoryCache
    /** P-05：当前 paramValues 快照（断言用）。 */
    internal fun paramValuesForTest(): Map<String, String> = paramValues.toMap()
    /** P-05：强制同步刷写历史缓存（测试用，绕过 debounce Timer）。 */
    internal fun flushHistoryForTest() = flushHistoryToCache()
    /** P-05.1：当前 paramValues 序列化为 JSON（测试调用用）。 */
    internal fun serializeParamsToJsonForTest(): String = serializeParamsToJson()

    companion object {
        /** O-4：通知分组 id（plugin.xml 注册，diff/copy 共用）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        private val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")
        /** T-M24-04：XML 单行视图的空白归一化（多行文本节点 / 段间空格）。 */
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
