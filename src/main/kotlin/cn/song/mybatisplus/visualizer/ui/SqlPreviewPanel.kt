package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.FillClause
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.share.ui.ShareDialog
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramSupport
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.CaseFormatUtil
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.xml.XmlConditionView
import cn.song.mybatisplus.visualizer.xml.XmlForEachView
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlPreviewState
import cn.song.mybatisplus.visualizer.xml.PagesColumn
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
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
import com.intellij.ui.OnePixelSplitter
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
import java.awt.Insets
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * SQL 预览面板（M9-01/02/03 可编辑版，F-07/F-08）。
 *
 * 弹窗 [SqlPreviewDialog] 与工具窗口共用本面板，展示：
 *  - 顶部单行：元信息（Wrapper 类型 / 表名 / 风险警示，T-PreviewTopOverlap F3 按宽度预算截断）
 *    + 工具栏（压缩切换 / 格式化 / 关键字换行 / 竖向排列 / 执行计划 / 刷新 + 「更多」折叠低频动作；
 *    复制错误仅警示时可见）
 *  - 中部左右分栏：左侧**参数值输入框**（`#param` → 值，输入实时回填 SQL），右侧**可编辑 SQL 编辑器**
 *  - SQL 编辑器：`EditorFactory.createEditor`（可写）+ 原生 SQL 语法高亮（平台自动关联）；
 *    文档由原生 SQL FileType PSI 文件支撑，挂 [SqlCompletionContributor]（language="SQL" + IS_PREVIEW_EDITOR 守卫）提供补全（Ctrl+Space）
 *  - 补全上下文（表名/列名）双载体传递（[SqlCompletionContext]）：**Editor userData 优先**（M16.2，
 *    嵌入式编辑器编辑后 PSI 可能被 commit 重建丢失 userData）+ **PSI userData 兜底**
 *
 * 说明：编辑器生命周期在 [removeNotify] 时释放，避免泄漏（EditorFactory.releaseEditor）。
 */
class SqlPreviewPanel(private val project: Project? = null) : JBPanel<SqlPreviewPanel>(BorderLayout()), Disposable {

    private val metaLabel = JBLabel()

    // T-PreviewTopOverlap F1/F3：顶部行与工具栏提升为字段——布局断言（topRowForTest/toolbarForTest）
    // 与宽度预算计算（applyMetaTruncation 需工具栏理想宽）使用
    private lateinit var topRow: JBPanel<JBPanel<*>>
    private lateinit var toolbarPanel: JBPanel<JBPanel<*>>

    // T-PreviewTopOverlap F3：元信息原文缓存——metaLineText 第一行（meta+警示，纯文本）、
    // metaPerfSegments 第二行分段（可见文本+颜色）；tooltip 恒全文，label 文本按预算重拼
    private var metaLineText: String = ""
    private var metaPerfSegments: List<MetaPerfSegment> = emptyList()
    private var lastMetaBudget: Int = -1

    /** F3：perf 建议分段（可见文本 + HTML 颜色值），截断时按预算丢尾段。 */
    private data class MetaPerfSegment(val text: String, val color: String)

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
        // M36.5：去 LAF 默认 viewport 描边（左侧白色内边框根因）
        border = JBUI.Borders.empty()
    }
    private val paramFields = mutableListOf<JBTextField>()
    private val conditionCheckBoxes = mutableListOf<JCheckBox>()
    private var autoPopupDisposable: Disposable? = null
    private val conditionValueFields = mutableListOf<JBTextField>()
    // T-M23-04：批量列级勾选复选框（INSERT/UPDATE/UPSERT 参数列，与动态条件复选框互斥——批量预览无 conditionViews）
    private val batchCheckBoxes = mutableListOf<JCheckBox>()
    // T-M26.3：全选/全不选按钮回挂分区标题行右侧（IDEA 组操作惯例，T-M23.2 原生形态；
    // T-M26.2 曾固定面板右上角，长文本截断后按钮不再被推出视口）。
    // T-SelToggle：全选/全不选合并为单个状态切换按钮——全勾时文案「全不选」点击清空，
    // 其余态文案「全选」点击填满；文案随勾选态翻转（renderSql 末尾单点同步）。
    private var toggleSelectButton: JButton? = null
    private var clearButton: JButton? = null        // 动态条件分区清除按钮
    private var batchClearButton: JButton? = null    // 批量参数列分区清除按钮
    // T-M23.2：批量「参数列」区全选/全不选切换按钮（T-SelToggle 合并单按钮）
    private var batchToggleSelectButton: JButton? = null

    // 右：可编辑 SQL 编辑器（PSI 支撑，补全用）
    private var editor: Editor? = null

    /** T-M27.2：自建 autopopup 的文档监听器与调度防重入标志（平台 autopopup 对非文件编辑器不生效）。 */
    private var autoPopupListener: com.intellij.openapi.editor.event.DocumentListener? = null
    private var autoPopupScheduled = false

    /** T-M27.4：渲染 setText 期间抑制自建 autopopup（格式化替换整文档不触发补全）。 */
    private var suppressAutoPopup = false
    private var psiFile: PsiFile? = null
    /** M34 回归修复：预览 PSI 的临时 VirtualFile（PsiDocumentManager 追踪需真实 VFS 文件），releaseEditor 时删除。 */
    @Volatile
    private var previewTempFile: com.intellij.openapi.vfs.VirtualFile? = null
    /** T-M36.6-03：后台预热产物（vf+PSI 就绪对），createEditor 优先消费——EDT 零慢操作。 */
    @Volatile
    private var prewarmedTempFile: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? = null
    /** T-GifRerecord：预热失败标志——await 快速降级用，避免 EDT 空等 2s 后再走降级路径。 */
    @Volatile
    private var prewarmFailed = false
    /** T-M36.6-03：面板释放标志——预热线程晚于 dispose 完成时丢弃产物并删除文件。 */
    @Volatile
    private var previewDisposed = false
    /** T-PreviewExecDs-01c：已 setMapping 方言的预览 vf（同文件幂等短路，避免每次预览重设方言触发 push all dumb）。 */
    @Volatile
    private var previewDialectMappedVf: com.intellij.openapi.vfs.VirtualFile? = null
    /** F-46：当前代码位置（文件名:行号），来源列默认值；null 表示未知（日志/历史等无锚点场景）。 */
    private var sourceLocation: String? = null
    private val editorHolder = JBPanel<JBPanel<*>>(java.awt.GridLayout(1, 1)).apply {
        // M36.5.25e：GridLayout(1,1) 强制唯一子组件（内嵌控制台）fill——BorderLayout CENTER
        // 受子组件 preferredSize 影响导致 editor 不 fill（实际高度≈preferred ~375，15 行 SQL
        // 被截断）；GridLayout 按可用空间均分强制填满，editor 高度自适应窗口大小，无需固定
        // minimumSize 兜底。首次塌缩兜底由 editorComponentRaw 的 minimumSize 承担。
        isOpaque = false
    }

    // M36.5：SQL 执行状态面板（主面板 SOUTH——仅在有状态/错误内容时可见，避免空白占位）
    private val resultPanel = SqlResultPanel().apply {
        isVisible = false
    }

    /** M36：已内嵌的原生控制台 UI 组件（清空/释放时级联 dispose）。 */
    private var embeddedConsole: JComponent? = null

    /**
     * M36.5.39-A2：持久包装容器——console 组件统一嵌入此容器、容器整体挂入 editorHolder。
     * DBN results refreshed 期间会临时拿走 console 组件，旧的直挂方式（editorHolder.add(console)）
     * 会把 editorHolder 的显示链一并波及（holderShowing=false → 右侧空白）；隔离后 DBN 只动
     * consoleHost 子层，editorHolder/ThemedSplitPane 显示链稳定。
     */
    private val consoleHost = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply { isOpaque = false }

    /** M36.5.39-A1：最近一次嵌入 console 的身份 id（identityHashCode）——sameAsPrev 探针判定用。 */
    private var lastConsoleEmbeddedId: Int? = null

    /** M36.5.44：上次 re-host 时刻（去抖用）——DBN 结果刷新期高频 reparent，需最小间隔抑制死循环。 */
    private var lastRehostAt: Long = 0L

    /** T-RefreshDbSteal：当前挂接的 rehost HierarchyListener 引用——detach/dispose 时显式移除。
     *  旧实现「监听随组件废弃，无需显式解绑」的假设在共享控制台场景不成立：组件被新面板复用后
     *  旧面板的监听仍存活，parent==null 时会把共享控制台抢回已废弃面板（真机日志 6 连抢实锤）。 */
    private var rehostListener: java.awt.event.HierarchyListener? = null

    /** T-RefreshDbSteal：去抖窗口内被跳过的释放事件 → 已调度延迟复查（单次标志，防重复调度）。 */
    private var rehostRetryScheduled = false

    // M36.4：原生编辑器文档引用（参数回填/格式化时使用）
    /** 原生编辑器的文档引用（内嵌后参数变更同步用）。 */
    private var nativeEditorDoc: com.intellij.openapi.editor.Document? = null

    /**
     * T-AutoFold F5：原生控制台编辑器引用（onNativeEditorReady 时登记）——
     * showSql nativeDoc 支链的程序性折叠应用目标；detachConsole/clear/dispose 时清空，
     * 防止 EditorFactory 泄漏告警。recoverConsole 失联路径经 embedConsole 换新组件后
     * 由下一次 onNativeEditorReady 重新登记。
     */
    private var nativeConsoleEditor: com.intellij.openapi.editor.Editor? = null

    private var current: PreviewResult? = null
    private var compactMode = false

    /**
     * T-PreviewDisplayMode：编辑器显示模式。
     *
     * 编辑器文本是「模型派生视图」——参数输入/条件勾选/foreach 次数/压缩切换等任何变更都会
     * [renderSql] 全量重建并整篇覆盖文档，因此**一次性文档变换必被下一次重建抹掉**（早期三个
     * 格式按钮直接 setText 的写法：竖向排列/关键字换行无条件丢失，格式化仅在设置开启且非压缩
     * 时因 [showSql] 里那句 format() 而"看似保留"）。模式改存状态、由 [applyDisplayMode] 在
     * 渲染管线中统一套用，重建后形态保持。
     */
    private var displayMode = PreviewDisplayMode.NORMAL

    /** T-PreviewDisplayMode：预览编辑器显示模式（见 [displayMode] 的 KDoc）。 */
    enum class PreviewDisplayMode { NORMAL, FORMAT, KEYWORD_WRAP, VERTICAL }

    // O-4：上一次预览的 SQL（会话缓存，供 Compare 并排对比用）
    private var previousSql: String? = null

    // F-1：JOIN 关系图（仅 SELECT 含 JOIN 时显示标签页）
    private val diagramPanel = DiagramPanel(project)
    // T-Dgm-04：标签页宿主——原生图框架组件（joinDiagramNative=true 且 Diagrams 插件可用）与
    // 自绘 DiagramPanel 共用此容器按需切换；切换时旧原生编辑器即时 dispose 防 FileEditor 泄漏
    private val diagramTabHost = javax.swing.JPanel(java.awt.BorderLayout())
    private var currentNativeDiagram: JComponent? = null
    // F-45：maintainPage 列信息（仅 <pages> 预览非 null 时显示标签页）
    private var columnInfoPanel: JComponent? = null
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

    // T-Perf-04：参数击键路径 renderSql 防抖（Swing Timer，150ms，模式同 historyAlarm）
    /** 待执行的渲染任务（最后一次调度者生效——防抖语义）。 */
    private var pendingRender: (() -> Unit)? = null
    private val renderDebounceTimer = javax.swing.Timer(RENDER_DEBOUNCE_MS) { flushDebouncedRender() }.apply { isRepeats = false }

    /**
     * 调度防抖渲染： keystroke 路径用——150ms 停顿后只渲染一次，最终结果与逐键全量渲染一致。
     *
     * 单元测试模式（isUnitTestMode，headless 无 EDT 泵）同步执行：既有测试依赖「改参数值 →
     * 文档即时更新」的同步语义，且挂起的 Timer 在后续测试泵 EDT 时迟到触发会触碰已销毁面板
     * 并触发 TransactionGuard "Write-unsafe context" 日志断言（交叉污染）。生产行为不变。
     */
    private fun scheduleRender(task: () -> Unit) {
        pendingRender = task
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app != null && app.isUnitTestMode) {
            flushDebouncedRender()
            return
        }
        renderDebounceTimer.restart()
    }

    /** Timer 动作：执行最后调度的渲染任务（EDT）。 */
    private fun flushDebouncedRender() {
        val task = pendingRender
        pendingRender = null
        task?.invoke()
    }

    init {
        // 顶部元信息行 + 工具栏
        // T-PreviewTopOverlap F1：metaLabel 从 WEST 迁 CENTER——BorderLayout 的 WEST/EAST 各按
        // 理想宽整块贴边绝不收缩，两偏好宽之和超容器宽时直接互相重叠（用户真机截图实锤：元信息
        // 文字压进按钮行）；CENTER 只拿剩余宽度，与 EAST 结构性零重叠。宽度不足由 F3 截断兜底。
        // （合并曾取 dev T-GifHeader 两行布局为基底，用户定案「更多」折叠菜单模式——单行方案转正。）
        metaLabel.border = JBUI.Borders.empty(0, 8, 4, 8)
        topRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(metaLabel, BorderLayout.CENTER)
            toolbarPanel = toolbar()
            add(toolbarPanel, BorderLayout.EAST)
            // M36.5：收边距与原生工具行对齐（上 6→4，右 8→8 保留）
            border = JBUI.Borders.empty(4, 8, 2, 8)
        }
        add(topRow, BorderLayout.NORTH)
        // T-PreviewTopOverlap F3：宽度预算截断跟随尺寸变化（首显 + 拉伸/缩窄；宽度未变短路）
        topRow.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = applyMetaTruncation()
            override fun componentShown(e: java.awt.event.ComponentEvent) = applyMetaTruncation()
        })

        // 中部左右分栏：参数输入 | SQL 编辑器/原生控制台
        // T-M26.3：按钮回挂分区标题行右侧（conditionSectionHeader/batchSectionHeader），
        // 左面板即参数滚动区（T-M26.2 的固定操作行已拆除——长文本按视口截断后按钮不再被推出视口）
        val split = OnePixelSplitter(false, "mpvs.preview.splitter", 0.3f).apply {
            firstComponent = paramScroll
            secondComponent = editorHolder
        }
        // M36.5：删除 verticalSplit 下半区——控制台已内嵌右侧，底部仅保留状态行
        //（resultPanel SOUTH，无内容时隐藏不占高度，错误/执行状态正常显示）
        add(split, BorderLayout.CENTER)
        add(resultPanel, BorderLayout.SOUTH)

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
        // T-Dgm-04：标签页实际展示 diagramTabHost（内嵌原生组件或 diagramPanel）
        diagramTabHost.isOpaque = false
        diagramTabHost.add(diagramPanel, java.awt.BorderLayout.CENTER)

        // T-M36.6-03：预热线程提前创建临时 SQL 文件 + VFS refresh + PSI 解析（后台线程慢操作合法），
        // createEditor（EDT）优先消费预热产物——243 平台 EDT/EDT-ReadAction 均禁止慢操作
        //（SlowOperations ERROR_EDT/ERROR_RA），面板打开期的 refreshAndFindFileByIoFile SEVERE 由此消除
        if (project != null) {
            prewarmPreviewTempFile()
        }
    }

    /** T-M36.6-03 / T-PreviewFlash：后台预热预览临时文件（占位空内容，createEditor 就绪后按实际 SQL 同步）。 */
    private fun prewarmPreviewTempFile() {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (previewTempFile != null || previewDisposed) return@executeOnPooledThread
            runCatching {
                // T-PreviewExecDs-01a：预览临时文件迁项目内 .idea/sql-visualizer/preview/
                // ——系统 Temp 不在数据源关联范围，Database 插件不解析（M36.5.12 exec 同教训，
                // 预览路径此前未跟进；旧实现 09:34:40 起持续预热失败后回退裸文档，补全/注释全灭）。
                val io = projectPreviewFile() ?: run {
                    prewarmFailed = true
                    LOG.warn("[M36] prewarm FAILED: project preview file unavailable (project/basePath null or mkdirs false)")
                    return@executeOnPooledThread
                }
                // T-GifRerecord：VFS 忙时 refreshAndFindFileByIoFile 偶发查不到新文件（2026.2 沙盒
                // 录制期实证）——旧实现只重试一次仍持续失败（两项目同开 VFS 忙窗口）；T-PreviewExecDs-01b
                // 加重试到 3 次带退避，失败置 prewarmFailed 让 await 快速返回，不空等预算。
                val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                var vf = lfs.refreshAndFindFileByIoFile(io)
                var retries = 0
                while (vf == null && retries < 3 && !previewDisposed) {
                    Thread.sleep(100L * (retries + 1))
                    vf = lfs.refreshAndFindFileByIoFile(io)
                    retries++
                }
                if (vf == null) {
                    prewarmFailed = true
                    LOG.warn("[M36] prewarm FAILED: VFS refresh null after ${retries + 1} tries (${io.path}, exists=${io.exists()})")
                    return@executeOnPooledThread
                }
                if (previewDisposed) {
                    java.io.File(vf.path).delete()
                    return@executeOnPooledThread
                }
                val proj = project ?: return@executeOnPooledThread
                // T-PreviewExecDs-01c：方言映射须在 findFile/PSI 创建前（映射决定 PSI 语言，
                // 创建后再映射需 push 重建；exec 路径同款顺序）
                mapPreviewDialectBeforePsi(proj, vf)
                val psi = com.intellij.openapi.application.ReadAction.compute<PsiFile, Throwable> {
                    com.intellij.psi.PsiManager.getInstance(proj).findFile(vf)
                } ?: run {
                    prewarmFailed = true
                    LOG.warn("[M36] prewarm FAILED: PsiManager.findFile null for ${vf.name}")
                    return@executeOnPooledThread
                }
                previewTempFile = vf
                prewarmedTempFile = vf to psi
            }.onFailure { e ->
                prewarmFailed = true
                LOG.warn("[M36] prewarm FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * T-PreviewExecDs-01a：项目内预览临时文件——`<project>/.idea/sql-visualizer/preview/mpsql-preview.sql`。
     * 独立子目录（exec/ 互不污染，同 M36.5.13 语义）。文件在项目内容内，Database 插件才能解析
     * schema 补全/注释。VFS 安全写入三态（document 驱动 > setBinaryContent > FileUtil 兜底），
     * 杜绝 MemoryDiskConflictResolver 的 File Cache Conflict 弹窗（exec 路径 M36.5.13 同款）。
     */
    @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun projectPreviewFile(): java.io.File? {
        val proj = project ?: return null
        val base = proj.basePath ?: return null
        val dir = java.io.File(
            base,
            ".idea${java.io.File.separator}${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}${java.io.File.separator}preview",
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val f = java.io.File(dir, "mpsql-preview.sql")
        val existing = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(f)
        if (existing != null) {
            val ok = runCatching {
                com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                    val fileDoc = com.intellij.openapi.fileEditor.FileDocumentManager
                        .getInstance().getDocument(existing)
                    when {
                        fileDoc != null -> fileDoc.setText("") // 预热占位空内容，createEditor 装配时按实际 SQL 填充
                        fileDoc == null -> existing.setBinaryContent("".toByteArray())
                    }
                }
                true
            }.getOrDefault(false)
            if (ok) return f
        }
        com.intellij.openapi.util.io.FileUtil.writeToFile(f, "")
        return f
    }

    /**
     * T-PreviewExecDs-01c：预览文件方言预映射（exec 路径 DataSourceSelector.mapDialectBeforePsi 同款）——
     * PSI 创建时即按映射方言解析，Generic SQL 下 Database 补全不触发。映射匹配与 exec 一致：
     * SqlDialectMappings.getAvailableValues() 里 language.id == "MySQL" 的实例（MyBatis-Plus 预览
     * 场景即 MySQL；插件自定义 Dialect 与 Database 方言映射无现成对应，硬编码与 exec 对齐）。
     * 同 vf 已映射过即幂等短路（避免每次预览点 setMapping 触发 SqlDialectPusher push all dumb）。
     *
     * T-ExecSlowOps F1：反射段收编进 [DialectPreMapping]，本方法仅保留「面板级进程内幂等短路」语义 +
     * 成功回写 [previewDialectMappedVf]。真机实锤：本方法在 [prewarmPreviewTempFile] 后台线程内调用，
     * 旧实现反射直调 `setMapping` → 平台 `handleMappingChange → reparseFiles → runWriteAction →
     * VcsVFSListener.beforeContentsChange` 的 `assertEventDispatchThread` 硬抛
     * RuntimeExceptionWithAttachments（SEVERE ×3）。DialectPreMapping.ensureMapped 两段式：
     * 稳态（workspace.xml 映射幸存/本进程已设）读探测直接跳过，仅项目首见经 invokeLater 派 EDT 执行
     * 映射并 CountDownLatch 有界等待——映射写归 EDT、慢操作归 BGT，两侧 SEVERE 同消。
     */
    private fun mapPreviewDialectBeforePsi(proj: Project, vf: com.intellij.openapi.vfs.VirtualFile) {
        val inProcessMapped = previewDialectMappedVf?.isValid == true && previewDialectMappedVf === vf
        if (cn.song.mybatisplus.visualizer.execute.DialectPreMapping.ensureMapped(proj, vf, inProcessMapped)) {
            previewDialectMappedVf = vf
        }
    }

    /**
     * T-PreviewFlash：EDT 上阻塞等待预热产物（有界，避免与预热线程竞态导致 createEditor 无文件可用）。
     * 等待超时（理论上仅在预热线程失败/极端慢时）返回 null，由调用方走既有失败提示。
     * T-GifRerecord：预热线程已判定失败时立即返回 null（快速降级），不再空等 2s 预算。
     */
    private fun awaitPrewarmedTempFile(): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? {
        var waited = 0L
        while (true) {
            val ready = prewarmedTempFile?.also { prewarmedTempFile = null }
            if (ready != null) return ready
            if (prewarmFailed) return null
            if (waited >= PREWARM_WAIT_MS) return null
            try {
                Thread.sleep(PREWARM_POLL_MS)
                waited += PREWARM_POLL_MS
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    /** T-DarkTheme：主题变更回调——重绘面板（命名色已自动更新，仅需触发 UI 刷新）。 */
    private fun lookAndFeelChanged() {
        revalidate()
        repaint()
    }

    private lateinit var modeButton: JButton

    /**
     * 顶部工具栏：复制错误（有警示时可见，永不折叠——错误反馈链路一环）/ 压缩多行切换 / 格式化 /
     * 关键字换行 / 刷新 / 更多（T-PreviewTopOverlap F2：导入参数/差异对比/分享三个低频动作折叠进
     * 「更多」JBPopup 菜单；执行 SQL / 复制 SQL 在弹窗底部按钮区，M17.4）。
     */
    private fun toolbar(): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        errorCopyButton = smallButton(MpSqlVisualizerBundle.message("panel.copyError")) {
            if (copyError()) {
                // T-ExecCopyFix：复制成功反馈（对齐 SqlClickPreview.flashCopied 对号语义）
                errorCopyButton?.let { cn.song.mybatisplus.visualizer.ui.flashCopied(it) }
            }
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
        // T-VStack：竖向排列（INSERT 列清单/VALUES 逐行竖排 + 值行尾列名配对标注）
        add(smallButton(MpSqlVisualizerBundle.message("panel.vertical")) { formatVertical() })
        // M37.2：EXPLAIN（真实执行计划，静态规则标注同屏对照）。
        // T-MergeFollowup-01：master T-PreviewTopOverlap 重构按钮行时把 dev 的 add 行删掉而注释残留，
        // explainSql() 全树零调用（合并语义丢失无冲突标记实锤）——补回，保持内联高频位（对齐 M37M38 文档 §2.3）。
        add(smallButton(MpSqlVisualizerBundle.message("panel.explain")) { explainSql() })
        add(smallButton(MpSqlVisualizerBundle.message("panel.refresh")) { refreshView() })
        // T-PreviewTopOverlap F2：「更多」折叠菜单入口（低频动作收纳）
        add(smallButton(MpSqlVisualizerBundle.message("panel.more")) { showMoreActionsPopup() })
    }

    /** F2：「更多」折叠菜单的三项低频动作（文案键沿用原工具栏按钮，执行体一字不差）。 */
    private fun moreActions(): List<Pair<String, () -> Unit>> = listOf(
        MpSqlVisualizerBundle.message("panel.param.import") to { importParamsFromClipboard() },
        MpSqlVisualizerBundle.message("panel.compare") to { showSqlDiff() },
        MpSqlVisualizerBundle.message("panel.share") to { showShareDialog() },
    )

    /**
     * F2：弹出「更多」折叠菜单（锚定按钮下方；JBPopupFactory + BaseListPopupStep 路线为本项目
     * 现网先例：SqlClickPreview / DataSourceSelector）。show 依赖真实屏幕，测试只构造 step 不弹层。
     */
    private fun showMoreActionsPopup() {
        val actions = moreActions()
        val anchor = toolbarPanel.components.filterIsInstance<JButton>().lastOrNull() ?: return
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createListPopup(moreActionsStep(actions))
            .show(com.intellij.ui.awt.RelativePoint(anchor, java.awt.Point(0, anchor.height)))
    }

    /** F2：构造折叠菜单 step（独立可测——items 与 onChosen 委托可直接断言，无需真实弹层）。 */
    private fun moreActionsStep(actions: List<Pair<String, () -> Unit>>): com.intellij.openapi.ui.popup.util.BaseListPopupStep<String> =
        object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<String>(null, actions.map { it.first }) {
            override fun onChosen(
                selectedValue: String?,
                finalChoice: Boolean,
            ): com.intellij.openapi.ui.popup.PopupStep<*>? {
                actions.firstOrNull { it.first == selectedValue }?.let { it.second() }
                return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE
            }
        }

    /**
     * 执行 SQL（M36）：
     *  1. SQL 类型分类 → 非查询语句二次确认
     *  2. 创建临时上下文文件 → 原生数据源弹窗 → 选中后自动执行
     *  3. 结果在打开的 Database 编辑器下方原生展示
     *
     * Community（无 database 插件）由 [SqlExecutor] 提示降级。
     * M36.5.4：入口/中止日志——沙盒排查执行问题时此前无任何面板侧日志（静默 return 难定位）。
     */
    internal fun executeSql() {
        val proj = project ?: return
        // M36.4：优先取原生编辑器文档，兜底自研编辑器
        val sql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
            ?: editor?.document?.text?.takeIf { it.isNotBlank() }
            ?: run {
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                    .warn("[M36] executeSql aborted: no SQL text (nativeDoc=${nativeEditorDoc != null}, editor=${editor != null})")
                return
            }
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] executeSql: len=${sql.length}, prepared=${SqlExecutor.isPrepared()}")

        // 1. SQL 类型分类 → 非查询语句二次确认
        val isQuery = SqlTypeClassifier.isQuery(sql)
        if (!isQuery) {
            val keyword = SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL"
            val confirm = Messages.showYesNoDialog(
                proj,
                MpSqlVisualizerBundle.message("execute.confirm.dml.message", keyword),
                MpSqlVisualizerBundle.message("execute.confirm.dml"),
                Messages.getWarningIcon(),
            )
            if (confirm != Messages.YES) return
        }

        // 2. 控制台已就绪（M36.5 默认展示）→ 同步 SQL 到控制台文档后直接执行；
        //    未就绪 → 走准备+执行合一全链（准备完成后控制台内嵌右侧）
        resultPanel.showLoading()
        if (SqlExecutor.isPrepared()) {
            // T-RefreshDbSteal F4：isPrepared 只证明插件级共享控制台存在，不证明它还挂在本面板——
            // 失联时 executePrepared 会把 SQL 执行到别的弹窗/Database 工具窗的控制台（症状③）。
            // 先走恢复链把控制台重嵌（prepareConsole 复用链同步 SQL + onConsole 重嵌），
            // 仍就绪才执行——恢复链自身失败时 isPrepared 已被全链重建路径覆盖，回退下方
            // selectAndExecute 全链兜底
            if (!consoleAttachedToMe()) {
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                    .warn("[M36] executeSql: prepared console detached from this panel → recover before execute")
                recoverConsole(sql)
                if (SqlExecutor.isPrepared() && !consoleAttachedToMe()) {
                    // 恢复未落地（异步链未回调）：放弃 executePrepared，落全链（全链会重建/复用
                    // 共享控制台并经 onConsole 重嵌本面板），避免打在别人的控制台上
                    SqlExecutor.execute(
                        proj, sql, null, isQuery,
                        onConsole = { consoleComponent -> embedConsole(consoleComponent, previewMode = true) },
                        onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
                        onError = { errorMsg ->
                            resultPanel.showError(errorMsg)
                        },
                    )
                    return
                }
            }
            // 执行前同步当前 SQL（用户可能改过参数/手动编辑）
            nativeEditorDoc?.let { doc ->
                if (doc.text != sql) {
                    WriteCommandAction.runWriteCommandAction(proj) { doc.setText(sql) }
                }
            }
            val executed = SqlExecutor.executePrepared(
                proj,
                // M36.5.2：执行触发成功后换入完整控制台（结果区执行后才出现）
                onResultsReady = { fullConsole -> embedConsole(fullConsole) },
                onError = { errorMsg ->
                    resultPanel.showError(errorMsg)
                },
            )
            if (executed) return
        }
        SqlExecutor.execute(
            proj, sql, null, isQuery,
            onConsole = { consoleComponent -> embedConsole(consoleComponent, previewMode = true) },
            onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
            // M36.3：错误回调——执行链任何环节失败时从「执行中」状态恢复并显示错误
            onError = { errorMsg ->
                resultPanel.showError(errorMsg)
            },
        )
    }

    /**
     * M37.2：真实 EXPLAIN——按当前方言把预览 SQL 包装为 EXPLAIN 语句，走执行链提交原生控制台。
     * 结果展示与普通执行一致（内嵌控制台结果 Tab）；静态性能规则标注保留在顶部 meta 区，同屏对照。
     * EXPLAIN 归免确认类（[SqlTypeClassifier]），无 DML 二次确认；方言不支持（null）→ 提示降级。
     */
    internal fun explainSql() {
        val proj = project ?: return
        val sql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
            ?: editor?.document?.text?.takeIf { it.isNotBlank() } ?: return
        val dialect = current?.previewModel?.dialect ?: MySqlDialect
        // T-ExplainDbl：幂等包装——首次执行后 EXPLAIN 语句已在控制台文档中，再点一次
        // 不在已包装文本上二次加前缀（重复前缀 → MySQL 1064）
        val explainSql = dialect.renderExplainStatementIdempotent(sql)
        if (explainSql == null) {
            // T-UiNative T-UN-02：方言不支持提示改轻通知（原模态弹窗——纯告知无需打断）
            if (!ApplicationManager.getApplication().isUnitTestMode) {
                runCatching {
                    Notification(
                        NOTIFICATION_GROUP_ID,
                        MpSqlVisualizerBundle.message("panel.explain"),
                        MpSqlVisualizerBundle.message("panel.explain.unsupported", dialect.name),
                        NotificationType.INFORMATION,
                    ).notify(proj)
                }
            }
            return
        }
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M37.2] explainSql: dialect=${dialect.name}, len=${explainSql.length}")
        resultPanel.showLoading()
        SqlExecutor.execute(
            proj, explainSql, null, true,
            onConsole = { consoleComponent -> embedConsole(consoleComponent, previewMode = true) },
            onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
            onError = { errorMsg ->
                resultPanel.showError(errorMsg)
            },
        )
    }

    /**
     * M36.5：原生控制台编辑器就绪统一处理——注入补全上下文、记录文档引用、
     * 释放自研编辑器（原生控制台占据右侧）。
     */
    private fun onNativeEditorReady(consoleEditor: com.intellij.openapi.editor.Editor) {
        val ctx = buildCompletionContext()
        consoleEditor.putUserData(SqlCompletionContext.KEY, ctx)
        consoleEditor.document.let { SqlCompletionContributor.registerContext(it, ctx) }
        nativeEditorDoc = consoleEditor.document
        // T-AutoFold F5：登记控制台编辑器引用（折叠应用目标）；就绪即按当前文档内容应用一次
        //（prepareConsole 链可能已同步 SQL 进文档，早于下一次 showSql）
        nativeConsoleEditor = consoleEditor
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(
            consoleEditor,
            consoleEditor.document.text,
        )
        if (editor != null) {
            releaseEditor()
            editorHolder.removeAll()
        }
    }

    /**
     * M36.5：发起控制台默认准备（面板展示时调用，不执行）——
     * Ultimate 下原生控制台即刻可见（▶ 工具栏/Tx Auto/Playground 等 DBN 原生元素常驻预览面板）；
     * Community 下静默回退自研编辑器；无数据源静默引导（不弹错误打断预览）。
     *
     * M36.5.25g 试过回退"预览即 DBN 控制台"——但用户明确要求**预览阶段也显示 DBN 工具栏**
     *（▶ 工具栏需始终可见），故恢复 prepareConsole 调用。
     */
    /**
     * M36.5：发起控制台默认准备（面板展示时调用，不执行）——
     * Ultimate 下原生控制台即刻可见（▶ 工具栏/Tx Auto/Playground 等 DBN 原生元素常驻预览面板）；
     * Community 下静默回退自研编辑器；无数据源静默引导（不弹错误打断预览）。
     *
     * M36.5.25g 试过回退"预览即 DBN 控制台"——但用户明确要求**预览阶段也显示 DBN 工具栏**
     *（▶ 工具栏需始终可见），故恢复 prepareConsole 调用。
     *
     * T-RefreshDbSteal F4：兼作失联恢复入口 [recoverConsole]——回退编辑器在场时以回退编辑器
     * 的文本发起准备（准备链复用共享控制台会同步该 SQL），面板无任何编辑器时静默跳过。
     */
    private fun prepareDefaultConsole() {
        val proj = project ?: return
        val sql = editor?.document?.text?.takeIf { it.isNotBlank() } ?: return
        SqlExecutor.prepareConsole(
            proj, sql,
            // M36.5.58：预览换入控制台组件后**清空状态栏**——预览是干净上下文，不应残留上次
            // 执行的结果/错误（用户"执行第二个 SQL 后预览第一个，状态栏还显示之前的错误"）。
            // 嵌入后 showEmpty 清空；执行路径走 onResultsReady（成功）清 / onError（失败）显示，
            // 与本预览回调互不影响。
            onConsole = { consoleComponent ->
                embedConsole(consoleComponent)
                resultPanel.showEmpty()
                // T-PreviewConsoleFmt：控制台挂在这个 exec 文件上（其文档就是该文件文档），而文件
                // 内容由预览链预热写成**预览原文**（裸单行）——挂载后不重渲染，用户看到的就是裸
                // SQL（真机 20260917 日志：showSql createEditor len=274/16 行 → 预热原文 → attach
                // 后控制台显示 1 行）。嵌入完成即按当前渲染文本回填一次；必须排在此处而非
                // onNativeEditorReady —— showSql 的 nativeDoc 分支要求 consoleAttachedToMe()
                // （= embeddedConsole 已进 consoleHost），挂载回调那一刻尚未满足。
                ApplicationManager.getApplication().invokeLater({
                    if (com.intellij.openapi.util.Disposer.isDisposed(this)) return@invokeLater
                    runCatching { renderSql() }.onFailure { e ->
                        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                            .warn("[M36] post-attach re-render FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }, com.intellij.openapi.application.ModalityState.any())
            },
            onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
            // 默认准备失败（无数据源等）静默——不打扰预览；执行时再走全链提示
            onError = null,
        )
    }

    /**
     * M36.4：将拼合的原生控制台 UI 组件内嵌到右侧 SQL 显示栏。
     * M36.5.39-A1：入口身份探针——`sameAsPrev=true` 判定方案 A 路线有效（DBN results refreshed
     * 后传入的是同一对象），false 则需升级 B 路线（见「原生控制台嵌入方案AB-开发计划」决策树）。
     * M36.5.39-A2：嵌入统一走持久 consoleHost（容器只此一份），DBN 拿走组件时只清空 host 子层，
     * editorHolder 显示链不断裂。
     * M36.5.39-A3：组件被 DBN 完全释放（parent==null）后由 hierarchy 监听自动重挂，不等轮询。
     */
    private fun embedConsole(consoleComponent: JComponent, previewMode: Boolean = false) {
        val id = System.identityHashCode(consoleComponent)
        val prev = lastConsoleEmbeddedId
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] embedConsole identity: id=$id, prevEmbedded=$prev, sameAsPrev=${prev == id}, sameInstance=${consoleComponent === embeddedConsole}, previewMode=$previewMode")
        if (consoleComponent === embeddedConsole) {
            // M36.5.47：同实例防抖时**不清空结果、不重挂**（防闪烁），但必须清除"执行中"
            // loading 态——A 执行成功后 onResultsReady 回调同一实例（复用共享 console），
            // 原实现直接 return 导致 resultPanel 停留 loading，状态栏恒"执行中..."（用户
            // 实测"A 执行后停滞"）。结果区已在 console 组件内显示，清状态条即执行完成标志。
            // M36.5.49：用 showIdle() 而非 showEmpty()——只清 loading/成功态，**保留错误态**，
            // 避免执行失败后 showError 被后续 results refreshed 的同实例回调覆盖（"一闪而过"）。
            resultPanel.showIdle()
            // M36.5.77：execute 路径（previewMode=false）即使同实例也要确保 divider 展开——
            // preview 时折叠为 1.0；execute 后必须恢复 0.6 显示结果区。
            if (!previewMode && consoleComponent is javax.swing.JSplitPane) {
                ApplicationManager.getApplication().invokeLater({
                    if (consoleComponent.height > 0) {
                        consoleComponent.setDividerLocation(0.6)
                        consoleComponent.revalidate()
                    }
                }, com.intellij.openapi.application.ModalityState.any())
            }
            return
        }
        // T-RefreshDbSteal F3：旧组件的 rehost 监听必须显式解绑——共享控制台场景旧监听残活
        // 会把控制台抢回已废弃面板（真机 6 连抢实锤），detachConsole 内统一处理
        detachConsole()
        embeddedConsole = consoleComponent
        lastConsoleEmbeddedId = id
        // 去边框防白边（内嵌组件/容器可能自带 border）
        consoleComponent.border = JBUI.Borders.empty()
        consoleComponent.isOpaque = false
        // A2：host 挂入 editorHolder（clear/removeAll 后重挂，容器实例保持唯一）
        editorHolder.removeAll()
        editorHolder.add(consoleHost, java.awt.BorderLayout.CENTER)
        consoleHost.removeAll()
        consoleHost.add(consoleComponent, java.awt.BorderLayout.CENTER)
        attachRehostListener(consoleComponent)
        if (consoleComponent is javax.swing.JSplitPane) {
            val sp = consoleComponent
            // M36.5.51：预览（execute=false）时也强制 divider 60%——一次性 componentListener
            // 的 `if (height<200) return` 在布局初期塌缩时不触发，导致"预览 SQL 编辑器塌缩"，
            // 执行后才被 swap 的 realignSplitDivider 恢复（用户截图：预览布局问题，点执行后恢复）。
            // invokeLater 下一拍组件已在树中，强制 60%（编辑器主导），不依赖 resize 时机。
            ApplicationManager.getApplication().invokeLater({
                if (sp.height > 0) {
                    // M36.5.77：预览（previewMode=true）折叠底部（divider=1.0），隐藏无内容的
                    // Console output 空框；execute 路径展开底部（divider=0.6）。
                    sp.setDividerLocation(if (previewMode) 1.0 else 0.6)
                    sp.revalidate()
                    sp.repaint()
                }
                // M36.5.75：预览路径也安装 Output 控制台填充布局（幂等——执行路径 realign
                // 时重复调用直接跳过）。根因与修复见 DataSourceSelector.installOutputHistoryFillLayout。
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector
                    .installOutputHistoryFillLayout(sp, com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java))
            }, com.intellij.openapi.application.ModalityState.any())
            sp.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    // M36.6.2：无条件首触强制 60%——旧条件 getDividerLocation()<=0 在首次布局后恒
                    // false（divider 已是 preferred 推算的正值，只是落点错误），保险从未触发，
                    // 分隔落点随构造时序漂移（用户三张截图实证：塌缩/截断/窄条三种形态）
                    if (sp.height < 200) return
                    // M36.5.77：preview 路径维持折叠（divider=1.0），execute 路径首触强制 0.6
                    sp.setDividerLocation(if (previewMode) 1.0 else 0.6)
                    sp.removeComponentListener(this)
                }
            })
        } else {
            // M36.5.25：顶部块（非 SplitPane，首次预览 executeAfterPrepare=false）——
            // 编辑器组件可能按 preferred=0 塌缩为 0 高（SQL 被 Console output 占位遮住），
            // 获尺寸后强制重排一次（与 SplitPane divider 修复同机制）
            consoleComponent.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    consoleComponent.revalidate()
                    consoleComponent.repaint()
                    consoleComponent.removeComponentListener(this)
                }
            })
        }
        // M36.5.49：用 showIdle 而非 showEmpty——换入新控制台组件时若刚显示了 showError
        //（SQL 失败），showEmpty 会清掉错误（用户实测"SQL 错误无具体原因/一闪而过"）。
        // showIdle 只清 loading/成功态，保留 ERROR 态；组件换入成功的结果区已就绪。
        resultPanel.showIdle()
        revalidate()
        repaint()
    }

    /**
     * T-RefreshDbSteal F4：所有权判定——本面板记录的控制台实例是否仍在自己的 consoleHost 树内。
     * false = 控制台已被其他面板收养/或 DBN 释放后未重挂成功，此时任何"写入 nativeEditorDoc /
     * executePrepared"都是打在别人的控制台上（三症状公共前置条件）。
     */
    internal fun consoleAttachedToMe(): Boolean {
        val console = embeddedConsole ?: return false
        return console.parent != null && consoleHost.isAncestorOf(console)
    }

    /**
     * T-RefreshDbSteal F4：失联恢复——共享控制台已不在本面板时重新走准备链把它要回来。
     * `prepareConsole` 的 onConsole 回调经 [embedConsole] 幂等重嵌（M36.5.5 复用链会同步 SQL
     * 进控制台文档），准备链失败静默（与 prepareDefaultConsole 同语义），本面板此时已落在
     * 回退编辑器上，显示不悬空。
     */
    private fun recoverConsole(sqlForRecovery: String) {
        if (project == null) return
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] recoverConsole triggered: len=${sqlForRecovery.length}, embedded=${System.identityHashCode(embeddedConsole)}")
        prepareDefaultConsole()
    }

    /**
     * M36.5.39-A3：DBN 释放监听——仅当组件被完全释放（parent==null，DBN 不再持有）且仍是当前
     * 嵌入实例时调度重挂；parent!=null 表示 DBN 临时 reparent（results refreshed 中转），不抢，
     * 避免竞争（方案 A 铁律）。
     *
     * M36.5.44 去抖修正：DBN 在 results refreshed 窗口内会**高频** reparent 整控台 SplitPane
     *（parent 在 null↔consoleHost 间横跳），若每次 parent==null 都立即重挂，会导致
     * "重挂→DBN 拿走→重挂…"无限循环（沙盒 16:40 实证：`console component released by DBN` 在
     * 毫秒级刷屏 2753 次，页面持续闪烁）。加入最小重挂间隔 [REHOST_MIN_INTERVAL_MS]：DBN 正忙于
     * reparent 时（两次释放间隔 < 门槛）跳过，待它稳定后再挂回——真正释放（DBN 不再碰）时
     * 间隔必然 > 门槛，兜底不丢。幂等重试仍由 [rehostConsoleForTest] 保证。
     *
     * T-RefreshDbSteal 两处修正：
     * ① 监听器引用保存到 [rehostListener]，[detachConsole]/[dispose] 显式移除——共享控制台被
     *   新面板接管后，旧面板的监听不再存活抢组件（真机 6 连抢根因）；
     * ② 去抖 skip 不再静默丢弃：DBN dance 收尾恰落在窗口内时旧实现永久悬空（零日志黑洞），
     *   现调度单次延迟复查（[REHOST_RETRY_DELAY_MS] 后仍失联且仍是当前实例 → 幂等重挂）。
     */
    private fun attachRehostListener(component: JComponent) {
        rehostListener?.let { old -> component.removeHierarchyListener(old) }
        val listener = java.awt.event.HierarchyListener { e ->
            val parentChanged = (e.changeFlags and java.awt.event.HierarchyEvent.PARENT_CHANGED.toLong()) != 0L
            if (!parentChanged || e.component.parent != null || e.component !== embeddedConsole) return@HierarchyListener
            val now = System.currentTimeMillis()
            val since = now - lastRehostAt
            if (since in 0 until REHOST_MIN_INTERVAL_MS) {
                // T-RefreshDbSteal：去抖 skip 有日志 + 单次延迟复查兜底（旧实现静默 return 是
                // 控制台永久悬空的零日志黑洞——「间隔必然 > 门槛」假设被 DBN dance 收尾证伪）
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                    .info("[M36] re-host debounced (since=${since}ms, id=${System.identityHashCode(e.component)}) → retry scheduled")
                scheduleRehostRetry(e.component as? JComponent ?: return@HierarchyListener)
                return@HierarchyListener
            }
            lastRehostAt = now
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .info("[M36] console component released by DBN (id=${System.identityHashCode(e.component)}) → re-host (since=${since}ms)")
            // ModalityState.any()：模态弹窗 pump 期间重挂照常执行（纯 Swing 操作安全，M36.5.13 教训）
            ApplicationManager.getApplication().invokeLater({
                rehostConsoleForTest(e.component as? JComponent ?: return@invokeLater)
            }, com.intellij.openapi.application.ModalityState.any())
        }
        rehostListener = listener
        component.addHierarchyListener(listener)
    }

    /** T-RefreshDbSteal：去抖窗口内被跳过的释放 → 延迟复查一次（仍失联则幂等重挂）。
     *  单发 Swing Timer（flashCopied 同款范式）：invokeLater 无延迟，复查会永远落在去抖
     *  窗口内被自己的 `since` 拦截，等于从未重试。 */
    private fun scheduleRehostRetry(component: JComponent) {
        if (rehostRetryScheduled) return
        rehostRetryScheduled = true
        javax.swing.Timer(REHOST_RETRY_DELAY_MS.toInt()) {
            rehostRetryScheduled = false
            // 复查条件与监听器同款：仍是当前嵌入实例且仍无 parent（被别的容器收养则不抢）
            if (component !== embeddedConsole || component.parent != null) return@Timer
            val since = System.currentTimeMillis() - lastRehostAt
            if (since in 0 until REHOST_MIN_INTERVAL_MS) return@Timer
            lastRehostAt = System.currentTimeMillis()
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .info("[M36] re-host retry fired (id=${System.identityHashCode(component)}, since=${since}ms)")
            rehostConsoleForTest(component)
        }.apply {
            isRepeats = false
            start()
        }
    }

    /**
     * M36.5.39-A3：重挂（同步逻辑抽出可单测）。仅接受当前嵌入实例且尚未挂回 host 的组件；
     * 返回 true = 已重挂。
     */
    internal fun rehostConsoleForTest(component: JComponent): Boolean {
        if (component !== embeddedConsole || component.parent === consoleHost) return false
        consoleHost.removeAll()
        consoleHost.add(component, java.awt.BorderLayout.CENTER)
        consoleHost.revalidate()
        consoleHost.repaint()
        return true
    }

    /**
     * M36：摘除已内嵌的控制台组件（清空预览时调用；JdbcConsole 生命周期由 IDE 管理）。
     * M36.5.39-A2：只清空 consoleHost 子层，容器保留——再次嵌入复用同一容器。
     * T-RefreshDbSteal：同时移除本面板挂接的 rehost 监听——共享控制台被新面板接管后，
     * 本面板的监听必须随之失效（旧实现不移除，僵尸监听把控制台抢回废弃面板）。
     */
    private fun detachConsole() {
        embeddedConsole?.let {
            rehostListener?.let { l -> it.removeHierarchyListener(l) }
            consoleHost.remove(it)
        }
        // T-AutoFold F5：控制台组件摘除即清编辑器引用（防 EditorFactory 泄漏；新组件就绪时经
        // onNativeEditorReady 重新登记）
        nativeConsoleEditor = null
        embeddedConsole = null
        rehostListener = null
    }

    private fun smallButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
        font = font.deriveFont(11f)
        addActionListener { action() }
    }

    /** 压缩/多行切换按钮文案：显示"将要切换到的模式"。 */
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
        // 模型级重生成路径：初始压缩/多行与格式化设置对齐（旧文本路径保持默认多行）
        result.previewModel?.let { compactMode = !it.formatSql }
        modeButton.text = modeButtonText()
        rebuildLeftPanel(result)
        refreshCompletionContext()
        renderSql()
        updateMeta(result)
        // F-1：更新 JOIN 图标签页（仅 SELECT 含 JOIN 时显示）
        updateDiagramTab(result)
        // F-45：更新列信息标签页（仅 <pages> 预览时显示）
        updateColumnInfoTab(result)
        // O-4：缓存当前 SQL 供下次 Compare
        previousSql = editor?.document?.text
        // M36.5：默认发起原生控制台准备（Ultimate 即刻展示原生控制台；Community 静默回退）
        prepareDefaultConsole()
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
            // T-Perf-09：schema 富化（数据源全量遍历）移后台预取——数千表冷缓存时 EDT 直跑冻结秒级；
            // 富化完成回 EDT 再展示。当前 PreviewResult 被替换（新预览）时作废迟到的应用。
            // T-Embed-02：数据源可用时把列类型/注释升级为 DB 真实值（int(11)/DB COMMENT），缺席静默回落
            val stmt = statement as SelectStatement
            val proj = project
            if (proj == null) {
                showJoinDiagram(JoinDiagramGenerator.buildDiagramModel(stmt))
                return
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                val enriched = try {
                    cn.song.mybatisplus.visualizer.ui.diagram.DiagramSchemaEnricher.enrich(
                        proj,
                        JoinDiagramGenerator.buildDiagramModel(stmt),
                    )
                } catch (t: Throwable) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                        .warn("[T-Perf-09] diagram schema enrich failed, showing un-enriched model", t)
                    JoinDiagramGenerator.buildDiagramModel(stmt)
                }
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (current !== result || project?.isDisposed == true) return@invokeLater
                    showJoinDiagram(enriched)
                }
            }
        } else {
            // 无 JOIN：移除 diagram 标签页（恢复纯 SQL 视图）
            removeDiagramTab()
            diagramPanel.clear()
            clearNativeDiagram()
        }
    }

    /**
     * T-Dgm-04：按设置与 Diagrams 插件可用性渲染 JOIN 图。
     *
     * 原生路径：快照登记 → UmlEditorProvider.createEditor → 组件嵌入 [diagramTabHost]
     * （缩放/布局/折叠/导出全框架托管）；Diagrams 插件缺席或框架异常（buildTabComponent 返回 null）
     * 自动降级自绘 [DiagramPanel]，与 Community 行为一致。
     */
    private fun showJoinDiagram(model: DiagramModel) {
        val nativeEnabled = PluginSettings.getInstance().state.joinDiagramNative
        val native = if (nativeEnabled) {
            // T-Embed-03 降级链：Database 原生图（真实 DbTable + 虚拟外键 JOIN 线）优先，
            // 数据源缺席/表未定位/框架异常回落 A 路径自实现 Provider，再回落自绘渲染器
            (project?.let {
                cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport
                    .buildTabComponent(it, model, MpSqlVisualizerBundle.message("diagram.tab.title"), this)
            })
                ?: NativeJoinDiagramSupport.buildTabComponent(project, model, MpSqlVisualizerBundle.message("diagram.tab.title"), this)
        } else {
            null
        }
        clearNativeDiagram()
        diagramTabHost.removeAll()
        if (native != null) {
            currentNativeDiagram = native
            diagramTabHost.add(native, java.awt.BorderLayout.CENTER)
        } else {
            diagramTabHost.add(diagramPanel, java.awt.BorderLayout.CENTER)
            diagramPanel.showDiagram(model)
        }
        diagramTabHost.revalidate()
        diagramTabHost.repaint()
    }

    /** 释放并移除当前原生图组件（内部 FileEditor 经 clientProperty 定位后 dispose）。 */
    private fun clearNativeDiagram() {
        currentNativeDiagram?.let {
            // 两条原生路径的 clientProperty key 不同，各 dispose 一次（未命中为 no-op）
            NativeJoinDiagramSupport.disposeNativeEditor(it)
            cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.disposeNativeEditor(it)
            diagramTabHost.remove(it)
        }
        currentNativeDiagram = null
    }

    /** 确保内容区为标签页模式（含 SQL + 可选的 JOIN 图）。 */
    private fun ensureTabbedContent(addDiagramTab: Boolean = true) {
        val tabPane = contentTabPane
        if (tabPane != null) return // 已切换
        // 从当前布局中取出 split（SQL 视图）
        val center = (layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
        if (center != null) remove(center)
        // 构建标签页
        val pane = JTabbedPane().apply {
            addTab(MpSqlVisualizerBundle.message("panel.tab.sql"), center)
            if (addDiagramTab) {
                addTab(MpSqlVisualizerBundle.message("diagram.tab.title"), diagramTabHost)
            }
        }
        // F-42 修复：渲染完成时组件可能处于 hidden（标签页未被选中），swapContent/revalidate
        // 对 hidden 组件无效——切到"JOIN 图"标签页时强制刷新，使图像真正显示
        if (addDiagramTab) {
            pane.addChangeListener {
                if (pane.selectedIndex == 1 && currentNativeDiagram == null) {
                    diagramPanel.refreshAfterVisible()
                }
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

    // ------------------------------------------------------------------
    // F-45：maintainPage 列信息标签页
    // ------------------------------------------------------------------

    /**
     * F-45：根据当前预览结果更新列信息标签页。
     * 仅当 result.pagesColumns 非 null 时显示"列信息"标签页；否则移除标签页（如有）。
     */
    private fun updateColumnInfoTab(result: PreviewResult) {
        if (result.pagesColumns != null) {
            ensureTabbedContent(addDiagramTab = false)
            val tabPane = contentTabPane ?: return
            // 已添加则不重复添加
            if (!hasTabTitle(tabPane, COLUMN_INFO_TAB_TITLE)) {
                val panel = buildColumnInfoPanel(result.pagesColumns)
                tabPane.addTab(COLUMN_INFO_TAB_TITLE, panel)
            }
        } else {
            removeColumnInfoTab()
        }
    }

    /** 构建列信息面板（列标识/标题/类型/可见性 表格）。 */
    private fun buildColumnInfoPanel(columns: List<PagesColumn>): JComponent {
        val header = arrayOf(
            MpSqlVisualizerBundle.message("pages.columns.id"),
            MpSqlVisualizerBundle.message("pages.columns.title"),
            MpSqlVisualizerBundle.message("pages.columns.dataType"),
            MpSqlVisualizerBundle.message("pages.columns.visible"),
        )
        val data = columns.map {
            arrayOf(it.id, it.title, it.dataType, if (it.visible) "✅" else "❌")
        }.toTypedArray()
        val table = com.intellij.ui.table.JBTable(object : javax.swing.table.DefaultTableModel(data, header) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }).apply {
            autoResizeMode = com.intellij.ui.table.JBTable.AUTO_RESIZE_ALL_COLUMNS
            tableHeader.reorderingAllowed = false
        }
        return JBScrollPane(table)
    }

    /** 移除列信息标签页。 */
    private fun removeColumnInfoTab() {
        val tabPane = contentTabPane ?: return
        // 从末尾开始找列信息标签页（可能在 JOIN 图之后）
        for (i in tabPane.tabCount - 1 downTo 0) {
            if (tabPane.getTitleAt(i) == COLUMN_INFO_TAB_TITLE) {
                tabPane.removeTabAt(i)
                break
            }
        }
        // 只剩 SQL 标签页时恢复纯 SQL 视图
        if (tabPane.tabCount == 1) {
            val sqlContent = tabPane.getComponentAt(0)
            remove(tabPane)
            add(sqlContent, BorderLayout.CENTER)
            contentTabPane = null
            revalidate()
            repaint()
        }
    }

    /** 检查标签页是否已存在指定标题。 */
    private fun hasTabTitle(tabPane: JTabbedPane, title: String): Boolean {
        for (i in 0 until tabPane.tabCount) {
            if (tabPane.getTitleAt(i) == title) return true
        }
        return false
    }

    /** 刷新补全上下文（表名/列名，可能随新预览变化）——M16.2 双载体：Editor userData 优先 + PSI 兜底。
     *  M34 回归修复：原生 SQL 高亮迁移后 Editor/PSI 对象在预览切换间复用（文档不变），
     *  三级回退的 Document→Context 映射需随上下文刷新同步更新，否则落入旧预览的过期表/列。 */
    private fun refreshCompletionContext() {
        val ctx = buildCompletionContext()
        editor?.putUserData(SqlCompletionContext.KEY, ctx)
        psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
        editor?.document?.let { SqlCompletionContributor.registerContext(it, ctx) }
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

    /**
     * JDBC 命名参数（NamedParameterJdbcTemplate `:name`）按名回填。
     *
     * 当 [PreviewResult.namedParamHints] 非空时，把 SQL 中 `:name` 按用户输入的参数值整体替换
     * （同名参数所有出现共用一值，对齐 NamedParameterJdbcTemplate 语义）；`?` 占位符不受影响，
     * 由调用方继续走 [backfillParamsBySource] 按序回填。
     * 命名参数提示为空时原样返回（兼容既有 `?` 顺序回填路径）。
     */
    private fun applyNamedParams(sql: String): String {
        val result = current ?: return sql
        if (result.namedParamHints.isEmpty()) return sql
        return ParameterReplacer.replaceNamedParams(sql, paramValues, result.namedParamHints)
    }

    /** 按当前压缩/多行模式 + 动态条件启用集合 + 参数回填重渲染 SQL 编辑器。 */
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
                // JDBC 命名参数按名回填（先），再对残留 `?` 按序回填
                showSql(backfillParamsBySource(applyNamedParams(generated.sql), generated.unresolvedParams, generated.paramHints))
            }
            else -> {
                // 旧文本路径（无可勾选动态条件，如批量操作）
                val base = if (compactMode) result.compactSql else result.sql
                // JDBC 命名参数按名回填（先），再对残留 `?` 按序回填
                showSql(backfillParamsBySource(applyNamedParams(base), result.unresolvedParams, result.paramHints))
            }
        }
        // T-SelToggle：勾选态一切变更均以 renderSql 收尾——切换按钮文案在此单点同步
        syncToggleSelectLabels()
    }

    /**
     * 刷新视图（M19.3）：模式**复位为与设置「SQL 格式化输出」对齐**（默认多行）后重渲染编辑器——
     * 用户实测：点刷新后 SQL 仍停留在压缩模式（此前手动切换/设置快照的压缩被保留）；
     * 刷新 = 回到默认状态（参数与回填保留，仅模式复位）。
     *
     * T-PreviewDisplayMode：显示模式一并复位为 NORMAL（即回到设置派生形态——设置开启为格式化、
     * 关闭为原样），与「刷新=回到默认状态」语义一致，竖排/关键字换行不跨刷新残留。
     */
    fun refreshView() {
        // T-RefreshDbSteal：刷新是唯一常规触达共享控制台文档的按钮（WriteCommandAction），
        // 入口记 4 态快照——真机复现时对照判归属
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] refreshView entry: compactMode=$compactMode, nativeDoc=${nativeEditorDoc != null}, editor=${editor != null}, embedded=${System.identityHashCode(embeddedConsole)}, attachedToMe=${consoleAttachedToMe()}")
        compactMode = !formatSqlSetting()
        displayMode = PreviewDisplayMode.NORMAL
        modeButton.text = modeButtonText()
        renderSql()
    }

    /** 设置「SQL 格式化输出」（false=压缩）；环境异常兜底 true（多行）。 */
    private fun formatSqlSetting(): Boolean = runCatching {
        PluginSettings.getInstance().state.formatSql
    }.getOrDefault(true)

    /**
     * T-VPairAnno：竖向排列 VALUES 行尾配对列名标注开关；环境异常兜底 true（保持默认标注）。
     * 与 [formatSqlSetting] 同构——保存后的下一次渲染生效，不做已开弹窗即时推送。
     */
    private fun verticalPairAnnotationSetting(): Boolean = runCatching {
        PluginSettings.getInstance().state.verticalPairAnnotation
    }.getOrDefault(true)

    /**
     * T-PreviewDisplayMode：按当前显示模式变换渲染文本（[showSql] 的唯一收敛点调用）。
     *
     * 优先级：压缩模式最高——压缩语义是"全部挤一行"，而模式变换会引入换行，二者相抵故短路；
     * 其余按模式分派：NORMAL 交「SQL 格式化输出」设置决定（保持设置关闭时不格式化的既有语义）、
     * FORMAT 强制格式化、KEYWORD_WRAP / VERTICAL 走对应变换（二者对非 INSERT 已内含 format，
     * 无需叠加）。
     */
    private fun applyDisplayMode(sql: String): String {
        if (compactMode) return sql
        return when (displayMode) {
            PreviewDisplayMode.NORMAL -> if (formatSqlSetting()) SqlFormatter.format(sql) else sql
            PreviewDisplayMode.FORMAT -> SqlFormatter.format(sql)
            PreviewDisplayMode.KEYWORD_WRAP -> SqlFormatter.formatClauseLine(sql)
            PreviewDisplayMode.VERTICAL -> SqlFormatter.formatVertical(sql, verticalPairAnnotationSetting())
        }
    }

    /**
     * T-PreviewDisplayMode：切换编辑器显示模式并重渲染（格式族按钮与 Ctrl+Alt+L 快捷键的统一入口）。
     *
     * 与旧实现的区别：不再直接对文档做一次性变换，而是记录模式后交渲染管线产出——这样参数输入、
     * 条件勾选、foreach 次数增减等任何重建都不会再把竖排/关键字换行形态抹掉。
     *
     * 压缩模式下切换格式族模式会一并退出压缩：否则模式被压缩短路、点击无可见效果（且"压缩态 +
     * 已格式化文档"是自相矛盾的中间态）。
     */
    fun setDisplayMode(mode: PreviewDisplayMode) {
        displayMode = mode
        if (compactMode) {
            compactMode = false
            if (::modeButton.isInitialized) modeButton.text = modeButtonText()
        }
        renderSql()
    }

    /**
     * 格式化当前编辑器 SQL（关键字大写 / 子句换行 / 条件缩进，[SqlFormatter]）。
     * T-PreviewDisplayMode：等价于「格式化」按钮——设 FORMAT 模式后由渲染管线套用（形态在参数
     * 变更后保持）；不再需要直接改文档，故改走状态路径。
     */
    fun formatSql() {
        setDisplayMode(PreviewDisplayMode.FORMAT)
    }

    /** 关键字换行格式化：子句顶格换行、同行内容压一行（[SqlFormatter.formatClauseLine]）。 */
    private fun formatClauseLine() {
        setDisplayMode(PreviewDisplayMode.KEYWORD_WRAP)
    }

    /**
     * 竖向排列格式化：INSERT 列清单/VALUES 逐行竖排、值行尾标注配对列名（[SqlFormatter.formatVertical]）。
     * T-VPairAnno：标注是否追加由设置 verticalPairAnnotation 控制（默认开）。
     */
    fun formatVertical() {
        setDisplayMode(PreviewDisplayMode.VERTICAL)
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

    /** 压缩 / 多行切换。T-PreviewDisplayMode：不改显示模式——切回多行时既有模式（如竖排）恢复。 */
    fun toggleCompact(): Boolean {
        compactMode = !compactMode
        renderSql()
        return compactMode
    }

    /** 一键复制当前 SQL（取编辑器当前内容，含手动编辑与参数回填）。 */
    fun copySql(): Boolean {
        // M36.4：优先取原生编辑器文档，兜底自研编辑器
        val raw = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
            ?: editor?.document?.text?.takeIf { it.isNotBlank() }
            ?: return false
        // 复制时剥离 SQL 注释（-- 行注释 + /* */ 块注释），与 Java 代码复制对齐
        val text = SqlCommentStripper.strip(raw)
        UiTextClipboard.copy(text)
        project?.let {
            // T-UiNative T-UN-02：复制成功提示改轻通知 + 「关闭 SQL 面板」动作（T-M23.6 原双按钮
            // 模态改为非模态——点动作等价原 index==1 分支，回调关闭预览弹窗）；
            // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
            if (!ApplicationManager.getApplication().isUnitTestMode) {
                runCatching {
                    val notification = Notification(
                        NOTIFICATION_GROUP_ID,
                        MpSqlVisualizerBundle.message("plugin.name"),
                        MpSqlVisualizerBundle.message("dialog.copy.done"),
                        NotificationType.INFORMATION,
                    )
                    notification.addAction(
                        NotificationAction.createSimpleExpiring(
                            MpSqlVisualizerBundle.message("dialog.closeSqlPanel"),
                        ) { onClosePanelRequested?.invoke() },
                    )
                    notification.notify(it)
                }
            }
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
    // O-4：SQL 并排对比（IDEA 原生 Diff，SQL 语法高亮）
    // ------------------------------------------------------------------

    /**
     * O-4：使用 IDEA 原生 Diff 对比当前 SQL 与上次预览 SQL。
     * 无上次预览时 toast 提示。
     *
     * 接入方式：DiffContentFactory.createEditable() 创建可编辑内容 + SimpleDiffRequest 包装 → DiffManager.showDiff。
     * 原生 diff 支持 SQL 语法高亮、行级对比、同步滚动、可编辑、F7 跳转差异等 IDEA 标准能力。
     */
    fun showSqlDiff() {
        val proj = project ?: return
        // M36.4：优先取原生编辑器文档，兜底自研编辑器
        val currentSql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
            ?: editor?.document?.text?.takeIf { it.isNotBlank() }
            ?: return
        val oldSql = previousSql ?: run {
            showDiffToast(MpSqlVisualizerBundle.message("preview.diff.noPrevious"))
            return
        }
        // IDEA 原生 Diff（createEditable → 两侧均可编辑，SQL 文件类型 → 语法高亮）
        val diffContentFactory = com.intellij.diff.DiffContentFactory.getInstance()
        val sqlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL")
        val leftContent: com.intellij.diff.contents.DocumentContent = diffContentFactory.createEditable(proj, oldSql, sqlFileType)
        val rightContent: com.intellij.diff.contents.DocumentContent = diffContentFactory.createEditable(proj, currentSql, sqlFileType)
        val request = com.intellij.diff.requests.SimpleDiffRequest(
            MpSqlVisualizerBundle.message("panel.diff.dialogTitle"),
            leftContent,
            rightContent,
            MpSqlVisualizerBundle.message("panel.diff.previous"),
            MpSqlVisualizerBundle.message("panel.diff.current"),
        )
        com.intellij.diff.DiffManager.getInstance().showDiff(proj, request)
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
        toggleSelectButton = null
        batchToggleSelectButton = null
        paramPanel.removeAll()
        // O-4：重置会话缓存
        previousSql = null
        // T-PreviewDisplayMode：显示模式随面板清空复位（与其余会话状态一致，不跨预览残留）
        displayMode = PreviewDisplayMode.NORMAL
        releaseEditor()
        editorHolder.removeAll()
        metaLabel.text = ""
        // T-PreviewTopOverlap F3：元信息原文缓存随清空复位（下次 updateMeta 重建）
        metaLineText = ""
        metaPerfSegments = emptyList()
        lastMetaBudget = -1
        // F-1：清除图集 + 恢复纯 SQL 视图
        removeDiagramTab()
        diagramPanel.clear()
        clearNativeDiagram()
        // M36：清空结果面板 + 摘除内嵌控制台
        resultPanel.clear()
        detachConsole()
        // M36.4：重置原生编辑器文档引用
        nativeEditorDoc = null
        // T-AutoFold F5：控制台编辑器引用随清空复位
        nativeConsoleEditor = null
        // M36.5：释放已准备控制台引用（下次预览重新准备）
        SqlExecutor.clearPrepared()
        // T-M36.6-03：clear 释放了临时文件/预热产物——面板重用时重新预热（幂等，保持零慢操作路径）
        if (project != null) {
            prewarmPreviewTempFile()
        }
        revalidate()
        repaint()
    }

    // ------------------------------------------------------------------
    // 编辑器（PSI 支撑 + 可编辑 + 高亮）
    // ------------------------------------------------------------------

    /** 渲染 SQL 到可编辑编辑器；已存在时更新文档（保留焦点/撤销）。 */
    private fun showSql(sql: String) {
        // T-RefreshDbSteal：三分支走查埋点——真机故障期症状②（左参数不再同步右侧 SQL）的
        // 丢失路径必经此方法（三个静默分支之一吞掉了渲染），带分支标签定位
        val fLogger = com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        // T-MpjFill：追加 fill 附加查询段（MPJ 1.5.1+）——在主 SQL 之后渲染 fill 附加 SELECT
        val withFill = appendFillSection(sql)
        // T-PreviewDisplayMode：显示模式在渲染管线内统一套用（唯一收敛点——XML/批量/模型/旧文本
        // 四条重建路径都经此处，故参数输入/勾选/foreach 变更后形态保持）
        val text = applyDisplayMode(withFill)
        // M36.4：优先写入原生编辑器文档（内嵌后）
        val nativeDoc = nativeEditorDoc
        if (nativeDoc != null) {
            // T-RefreshDbSteal F4：控制台失联时不写"别人的文档"（症状②：参数改动同步进
            // 被抢走的控制台，本面板视觉零变化）——改走失联恢复把控制台要回来；恢复链
            // 内部会同步 SQL 进控制台文档（reuseSharedConsole setText），本次渲染不丢
            if (!consoleAttachedToMe()) {
                fLogger.warn("[M36] showSql nativeDoc detached (attachedToMe=false) → recoverConsole")
                recoverConsole(text)
                return
            }
            if (nativeDoc.text != text) {
                fLogger.info("[M36] showSql branch=nativeDoc: len=${text.length}, attachedToMe=${consoleAttachedToMe()}")
                suppressAutoPopup = true
                try {
                    WriteCommandAction.runWriteCommandAction(project) { nativeDoc.setText(text) }
                } finally {
                    suppressAutoPopup = false
                }
            }
            // T-AutoFold F4-a：nativeDoc 支链的程序性折叠（关闭态 applyTo 内部即清除短路）
            nativeConsoleEditor?.let { foldTarget ->
                if (!foldTarget.isDisposed) {
                    cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(foldTarget, text)
                }
            }
            return
        }
        val existing = editor
        if (existing != null) {
            fLogger.info("[M36] showSql branch=editor: len=${text.length}, nativeDoc=null")
            if (existing.document.text != text) {
                suppressAutoPopup = true
                try {
                    WriteCommandAction.runWriteCommandAction(project) { existing.document.setText(text) }
                } finally {
                    suppressAutoPopup = false
                }
            }
            // T-AutoFold F4-a：自建编辑器支链
            cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(existing, text)
            return
        }
        fLogger.info("[M36] showSql branch=createEditor: len=${text.length}, attachedToMe=${consoleAttachedToMe()}")
        // T-RefreshDbSteal F4：createEditor 是 UX 悬崖（无 DBN 工具栏、形态突变带行号）——
        // 控制台插件级就绪但已失联时，先走恢复链重嵌，恢复链自身按回退编辑器保显示
        if (!consoleAttachedToMe() && SqlExecutor.isPrepared()) {
            fLogger.warn("[M36] createEditor fallback while prepared console detached → recoverConsole")
            recoverConsole(text)
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

    /** 首次创建：原生 SQL FileType PSI → 文档 → 可编辑编辑器，附补全上下文（Editor + PSI 双载体，M16.2）。 */
    private fun createEditor(sql: String) {
        // T-RefreshDbSteal：createEditor 是症状①的特征终点（自建编辑器带行号，DBN 控制台无），
        // 但真机故障期它零日志（71s 空窗）——带栈埋点定调用人路径，兜底断言非用户路径
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] createEditor: len=${sql.length}, nativeDoc=${nativeEditorDoc != null}, embedded=${System.identityHashCode(embeddedConsole)}", Throwable("createEditor path"))
        val proj = project
        val sqlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            .getStdFileType("SQL")
        val created = if (proj != null) {
            // T-PreviewFlash：有界等待预热产物——createTempFile + refresh + findFile 全移到后台线程，
            // EDT 不再触发 WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言（allowSlowOperations(GENERIC)
            // 压不住：SlowOperations.startSection 只在非 EDT 线程压栈，EDT 上该逃生舱是空壳，见开发计划 §3.1 反编译）。
            // T-PreviewDocLock2 F3：临时文件支链从「必得」改「尽力」——awaitPrewarmedTempFile 超时/拿不到
            // 产物、或 releaseEditor 后未经 clear() 二次 createEditor 时（prewarmedTempFile 一次性、已置 null），
            // 原实现直接 throw IllegalStateException 表现为「预览打不开」。改为降级到纯内存文档编辑器
            // （复用下面 project==null 分支的模板：createDocument(sql)），代价是降级态无 PSI → 无自建高亮/
            // 补全（平台默认裸编辑），功能降级优于硬失败——主路径（project 非空且预热就绪）行为逐字节不变。
            // （dev 侧 T-GifRerecord 同题降级版无 ReadAction 包裹，按超集原则整取 master 版。）
            // T-PreviewExecDs-01d：降级链第二级 = 内存 PSI（PsiFileFactory.createFileFromText，
            // 无 VFS 文件但保留补全上下文载体）——项目内文件预热失败时不再一步掉到裸 document；
            // 第三级（原兜底）才是纯内存文档编辑器（无 PSI，补全/注释灭）。
            val fallbackDoc = { msg: String ->
                createInMemoryPreviewPsi(sql) ?: run {
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                        .warn("[M36] preview temp file unavailable, fall back to bare in-memory editor: $msg")
                    psiFile = null
                    EditorFactory.getInstance().createDocument(sql)
                }
            }
            val doc = runCatching {
                val (vFile, psi) = awaitPrewarmedTempFile()
                    ?: throw IllegalStateException("无法创建 SQL 预览临时文件")
                previewTempFile = vFile
                psiFile = psi
                // T-PreviewDocLock：262 平台给「PSI 文件 → 文档」解析加了运行期读权限断言（243 只有
                // @RequiresReadLock 注解，官方 243 sources jar 核实），故 EDT 鼠标事件里裸调即报内部错误：
                // 单次调用固定 4 条 SEVERE「Plugin to blame: SQL Visualizer」——vf→Document 解析 2 条
                //（FileDocumentManager.getDocument 顶部的 EditorLockFreeTyping.assertReadAccess +
                // FileDocumentManagerBase.getDocument 首行的 softAssertReadAccess）与 Document/PSI
                // 文本长度校验 2 条（PsiFileImpl.getTextLength → view provider content → getDocument）。
                // 生产模式因 Logger.shouldRethrow 只认控制流异常而不抛（功能未断），但已是 SEVERE + FUS 上报，
                // 且 UsefulTestCase 模式下直接抛 AssertionError（平台抬版跑测试即红）。处置同 T-M42（同文件
                // createPreviewFileForTest 包 PsiFileFactory.createFileFromText 的先例）：EDT 上同步执行
                // ReadAction，语义不变。约束：lambda 内只能做纯内存模型读——EDT 进 ReadAction 会 check out
                // write-intent lock，塞同步 VFS refresh 会复现 T-PreviewWiLock 的 write-safe 硬抛。
                @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                val d = ReadAction.compute<com.intellij.openapi.editor.Document, Throwable> {
                    com.intellij.psi.PsiDocumentManager.getInstance(proj).getDocument(psi)
                } ?: throw IllegalStateException("临时 SQL 文件无关联文档")
                // T-M36.6-03：预热文件为占位空内容 → 首次装配按实际 SQL 填充并同步 PSI
                //（同 EDT tick 内完成，编辑器尚未显示，无闪烁）；同步路径文件内容=SQL，幂等跳过
                if (d.text != sql) {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(proj) {
                        d.setText(sql)
                        com.intellij.psi.PsiDocumentManager.getInstance(proj).commitDocument(d)
                    }
                }
                d
            }.getOrElse { fallbackDoc(it.message ?: it.javaClass.simpleName) }
            EditorFactory.getInstance().createEditor(doc, proj, sqlFileType, false)
        } else {
            EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql))
        }
        // M16.2：补全上下文双载体——Editor userData 为主载体（用户输入后 PSI 可能被 commit 重建而丢失 userData，
        // Editor 对象编辑期间稳定）、PSI 文件 userData 作兜底
        val ctx = buildCompletionContext()
        created.putUserData(SqlCompletionContext.KEY, ctx)
        psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
        // M34 回归修复：三级回退的 Document→Context 映射须在此登记（首次创建时 editor 尚不存在，
        // refreshCompletionContext 的 editor?.document 为 null，只能在此写入）——否则 commitDocument
        // 重建 PSI 后补全落入空映射，实体表/列补全静默失效（documentContexts 恒空、level 3 死路径）。
        created.document.let { SqlCompletionContributor.registerContext(it, ctx) }
        (created as EditorEx).apply {
            // F-CASE：自研 SQL 高亮器（SqlLexer 将 CASE/WHEN/THEN/ELSE/END 标记为 KEYWORD，
            // 字段名标记为 IDENTIFIER → 双击选择不会跨 token 粘连；不依赖 Database 插件）
            highlighter = LexerEditorHighlighter(
                SqlSyntaxHighlighter(), EditorColorsManager.getInstance().globalScheme
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
        // T-AutoFold F4-a：createEditor 支链（含内存文档降级，同一函数收口）——新编辑器按初始内容应用
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(created, sql)
    }

    /**
     * T-PreviewExecDs-01d：降级链第二级——内存 PSI 预览文档（无项目内 VFS 文件，但保留
     * [SqlCompletionContext] 补全载体，避免掉到裸 document 后补全/注释全灭）。createFileFromText
     * 包 ReadAction（T-M42 同机制先例：EDT 直调触发 markGenerated softAssertReadAccess 硬抛）。
     * 失败返回 null，由调用方回落到第三级裸文档。
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun createInMemoryPreviewPsi(sql: String): com.intellij.openapi.editor.Document? {
        val proj = project ?: return null
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        return runCatching {
            val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL")
            val psi = com.intellij.openapi.application.ReadAction.compute<PsiFile, Throwable> {
                com.intellij.psi.PsiFileFactory.getInstance(proj)
                    .createFileFromText("mpsql-preview-memory.sql", fileType, sql)
            }
            psiFile = psi
            LOG.info("[M36] in-memory PSI preview document created (len=${sql.length})")
            com.intellij.psi.PsiDocumentManager.getInstance(proj).getDocument(psi)
                ?: EditorFactory.getInstance().createDocument(sql)
        }.onFailure { e ->
            LOG.warn("[M36] in-memory PSI preview fallback FAILED: ${e.message}")
            psiFile = null
        }.getOrNull()
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
            val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .getStdFileType("SQL")
            PsiFileFactory.getInstance(proj)
                .createFileFromText("preview.sql", fileType, sql)
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
        // T-RefreshDbSteal：createEditor 兜底断言的对称端点——谁释放了自建编辑器决定了
        // 下一次 showSql 走哪个分支，旧实现零日志
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] releaseEditor: editor=${System.identityHashCode(editor)}, nativeDoc=${nativeEditorDoc != null}", Throwable("releaseEditor path"))
        editor?.let {
            // T-M52：监听器由 Disposable 管理，dispose 即移除（无需手动 removeDocumentListener）
            autoPopupListener = null
            autoPopupDisposable?.let { d -> Disposer.dispose(d) }
            autoPopupDisposable = null
            EditorFactory.getInstance().releaseEditor(it)
            editor = null
        }
        psiFile = null
        // M34 回归修复：临时 SQL 文件删除（java.io 删除免 write action；deleteOnExit 兜底）
        previewTempFile?.let { vFile -> java.io.File(vFile.path).delete() }
        previewTempFile = null
        // T-M36.6-03：预热产物作废（面板重用时由 clear() 重新预热；dispose 由 previewDisposed 兜底）
        prewarmedTempFile = null
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
        toggleSelectButton = null
        clearButton = null
        batchToggleSelectButton = null
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
     * 右侧单个全选/全不选切换按钮（T-SelToggle；操作 [batchCheckBoxes]，与动态条件区按钮互不影响）。
     */
    private fun batchSectionHeader(): JBPanel<JBPanel<*>> {
        batchToggleSelectButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll")) {
            setAllBatchColumns(!batchCheckBoxes.all { it.isSelected })
        }
        batchClearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear")) { clearBatchValues() }
        return sectionHeaderRow(
            MpSqlVisualizerBundle.message("panel.batch.columns"),
            listOf(batchToggleSelectButton!!, batchClearButton!!),
        )
    }

    /**
     * 批量列全选/全不选（T-M23.2）：操作 [batchCheckBoxes] 与 [enabledIds]（列名作 id），
     * 同步复选框 UI 与 SQL 重渲染（全不选 → 空列清单形态，文档化边界）。
     * T-SelToggle：由单个切换按钮按当前勾选态取反调用。
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
     * 动态条件分区标题行（T-M26.3）：TitledSeparator「动态条件」+ 右侧单个全选/全不选切换按钮
     * （T-M23.2 形态回归，IDEA 组操作惯例，T-SelToggle 双按钮合一；XML 条件开关同样复用，见 T-M24-04）。
     */
    private fun conditionSectionHeader(): JBPanel<JBPanel<*>> {
        toggleSelectButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll")) {
            setAllConditions(!conditionCheckBoxes.all { it.isSelected })
        }
        clearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear")) { clearConditionValues() }
        return sectionHeaderRow(
            MpSqlVisualizerBundle.message("panel.condition.section"),
            listOf(toggleSelectButton!!, clearButton!!),
        )
    }

    /** 左栏分区内的紧凑按钮（比工具栏按钮更小，避免挤压复选框行）。 */
    private fun compactButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
        font = font.deriveFont(10.5f)
        margin = JBUI.insets(0, 6, 0, 6)
        addActionListener { action() }
    }

    /**
     * T-SelToggle：切换按钮文案判定——复选框非空且全部勾选 → 「全不选」（点击清空），
     * 其余一切态（空集/部分勾选）→ 「全选」（点击填满）。以复选框 UI 态为基准
     * （enabledIds 跨场景共享可能残留本场景外 id，与用户所见不一致）。
     */
    private fun toggleLabel(boxes: List<JCheckBox>): String =
        MpSqlVisualizerBundle.message(
            if (boxes.isNotEmpty() && boxes.all { it.isSelected }) "panel.condition.selectNone"
            else "panel.condition.selectAll",
        )

    /**
     * T-SelToggle：切换按钮文案随勾选态刷新——挂在 [renderSql] 末尾单点收口
     * （三个勾选入口——切换按钮自身、批量列行/动态条件行/XML 条件行 listener——均以 renderSql 收尾）。
     * 文案未变化不 setText，避免每次重渲染触发标题行 FlowLayout 无谓重排。
     */
    private fun syncToggleSelectLabels() {
        toggleSelectButton?.let {
            val label = toggleLabel(conditionCheckBoxes)
            if (it.text != label) it.text = label
        }
        batchToggleSelectButton?.let {
            val label = toggleLabel(batchCheckBoxes)
            if (it.text != label) it.text = label
        }
    }

    /**
     * 全选/全不选（M13.1）：批量设置动态条件勾选态（普通参数不受影响），
     * 同步复选框 UI 与 SQL 重渲染。T-M24-04：XML 预览的条件开关（id = test 表达式）一并处理。
     * T-SelToggle：由单个切换按钮按当前勾选态取反调用。
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
     * T-Perf-04：击键路径——重渲染防抖（同 [onParamChanged]）。
     */
    private fun onConditionValueChanged(view: ConditionView, source: String, text: String) {
        paramValues[source] = text
        scheduleRender { renderSql() }
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
     *
     * T-Perf-04：击键路径每键两次全量渲染——整个处理器防抖（停顿后以最终文本执行一次；
     * handler 内部的两段渲染依赖 xmlMappings 中间态，保持同步语义不拆散）。
     */
    private fun onXmlForEachValuesChanged(view: XmlForEachView, text: String) {
        scheduleRender { applyXmlForEachValues(view, text) }
    }

    /** foreach 值应用（由防抖 Timer 调度；原 onXmlForEachValuesChanged 主体）。 */
    private fun applyXmlForEachValues(view: XmlForEachView, text: String) {
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
        // T-HibDualCapture：8 列放不下 16 字符日期值（视口滚到尾部被当成「值截断」），下限放宽到 16 列
        val field = JBTextField(16).apply {
            text = paramValues[source] ?: ""
            // 长值超框宽时光标钉回首字符，视口停在头部；完整值以 tooltip 兜底
            if (text.isNotEmpty()) caretPosition = 0
            toolTipText = paramTooltip(source, text)
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onParamFieldText(source, this@apply)
                override fun removeUpdate(e: DocumentEvent?) = onParamFieldText(source, this@apply)
                override fun changedUpdate(e: DocumentEvent?) = onParamFieldText(source, this@apply)
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

    /** 普通参数值变更：更新回填表并重渲染 SQL（T-Perf-04：重渲染防抖——逐键全量重生成+格式化
     *  +重分词在大 SQL 下构成卡顿；paramValues 即时更新，渲染合并到停顿后一次）。 */
    private fun onParamChanged(source: String, value: String) {
        paramValues[source] = value
        scheduleRender { renderSql() }
        saveToHistory()
    }

    /**
     * T-HibDualCapture：参数框文本变更（含导入回填等程序化 setText 触发的文档事件）——
     * tooltip 随值同步，其余走既有回填链路。
     */
    private fun onParamFieldText(source: String, field: JBTextField) {
        field.toolTipText = paramTooltip(source, field.text)
        onParamChanged(source, field.text)
    }

    /** T-HibDualCapture：参数框 tooltip——`#source = 完整值`，框宽放不下的长值以此兜底可见。 */
    private fun paramTooltip(source: String, value: String?): String =
        "#$source" + if (value.isNullOrEmpty()) "" else " = $value"


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
        // T-Perf-23：单趟遍历计数（原 toList+indexOf 每次 O(C) 拷贝 + 线性查找）
        var index = 0
        for (key in paramValues.keys) {
            if (key == source) return index
            index++
        }
        return -1
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
            isEmptyClipboard -> {
                // T-Perf-20：空剪贴板属用户输入类提示——改面板内行内警示（元信息行红字），
                // 不再弹模态错误框（toast 语义：下次 updateMeta 覆盖）
                val hint = MpSqlVisualizerBundle.message("panel.param.import.result.empty")
                currentWarning = hint
                metaLabel.text = "⚠ $hint"
                metaLabel.toolTipText = hint
                metaLabel.foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
                errorCopyButton?.isVisible = true
            }
            unmatched == 0 -> {
                // T-UiNative T-UN-02：导入结果改轻通知（原模态弹窗——纯告知无需打断）
                runCatching {
                    Notification(
                        NOTIFICATION_GROUP_ID,
                        title,
                        MpSqlVisualizerBundle.message("panel.param.import.result.success", matched),
                        NotificationType.INFORMATION,
                    ).notify(proj)
                }
            }
            else -> {
                // T-UiNative T-UN-02：部分匹配改轻通知（原模态警示框；WARNING 保留原警示图标语义）
                runCatching {
                    Notification(
                        NOTIFICATION_GROUP_ID,
                        title,
                        MpSqlVisualizerBundle.message(
                            "panel.param.import.result.partial", matched, unmatched, unmatchedKeys.joinToString(", "),
                        ),
                        NotificationType.WARNING,
                    ).notify(proj)
                }
            }
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

    /** P-05：Timer 动作——实际写回缓存（由 historyAlarm 调度）。
     * T-Perf-24：序列化/持久化写挪 pooled 线程（EDT 只做取值快照）。 */
    private fun flushHistoryToCache() {
        val key = historyCacheKey() ?: return
        val snapshot = paramValues.toMap()
        // headless 单测：同步执行保持既有测试语义
        if (ApplicationManager.getApplication().isUnitTestMode) {
            paramHistoryCache.save(key, snapshot)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            paramHistoryCache.save(key, snapshot)
        }
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
     *
     * T-Perf-04：单趟 O(N) 扫描——旧实现对每个 `?` 复制并大写化整个前缀（O(L×P)）；
     * 现改为就地回扫（跳过空白 → `(` → 可选一个空格 → `IN`，与旧 trimEnd+endsWith 语义一致），
     * 零分配。
     */
    internal fun detectInClauseParams(sql: String): Set<Int> {
        val result = mutableSetOf<Int>()
        var paramIndex = 0
        for (i in sql.indices) {
            if (sql[i] != '?') continue
            var j = i - 1
            while (j >= 0 && sql[j].isWhitespace()) j--
            if (j >= 0 && sql[j] == '(') {
                j--
                if (j >= 0 && sql[j] == ' ') j-- // "IN (" 单空格形态（"IN(" 直接判 N/I）
                if (j >= 1 && (sql[j] == 'N' || sql[j] == 'n') && (sql[j - 1] == 'I' || sql[j - 1] == 'i')) {
                    result.add(paramIndex)
                }
            }
            paramIndex++
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
        // T-SqlPerf：性能建议文案单独一行（HTML 换行）。
        // T-PreviewTopOverlap F3：原文分段缓存，label 文本按宽度预算重拼（applyMetaTruncation）
        metaLineText = meta + warningLine
        metaPerfSegments = buildPerfSegments(result)
        metaLabel.toolTipText = meta + warningLine + metaPerfSegments.joinToString("；") { it.text }
        metaLabel.foreground = if (currentWarning != null) {
            // T-DarkTheme：IDE 标准错误前景命名色（亮暗主题自适应；暗色下非亮红不刺眼）
            JBColor.namedColor("ErrorForeground", JBColor.RED)
        } else {
            JBUI.CurrentTheme.Label.foreground()
        }
        // T-M25.3：错误复制按钮随警示可见
        errorCopyButton?.isVisible = currentWarning != null
        // F3：复制错误按钮显隐改变工具栏理想宽，预算随显隐重算（lastMetaBudget 复位后应用）
        lastMetaBudget = -1
        applyMetaTruncation()
    }

    /**
     * T-PreviewTopOverlap F3：元信息宽度预算截断。
     *
     * 预算 = topRow 宽 − 工具栏理想宽 − topRow 边距 − metaLabel 左右边距；未布局（width<=0）跳过，
     * 等组件首显/拉伸时由 ComponentListener 触发。超预算时 meta 行中段省略（复用 T-M26.3 ellipsize），
     * perf 行丢尾段加省略号（HTML 标签外截断）；tooltip 恒全文。宽度未变短路防重算抖动。
     */
    private fun applyMetaTruncation() {
        if (metaLineText.isEmpty() && metaPerfSegments.isEmpty()) return
        val width = topRow.width
        if (width <= 0) {
            // 未布局（showResult 先于首帧布局 / headless 测试）：先展示全文，布局后由 ComponentListener 按预算重截
            metaLabel.text = fullDisplayText()
            return
        }
        val budget = (width - toolbarPanel.preferredSize.width - topRow.insets.let { it.left + it.right } - 16)
            .coerceAtLeast(MIN_META_BUDGET_PX)
        if (budget == lastMetaBudget) return
        lastMetaBudget = budget
        val metrics = metaLabel.getFontMetrics(metaLabel.font)
        metaLabel.text = if (metaPerfSegments.isEmpty()) {
            if (metrics.stringWidth(metaLineText) <= budget) metaLineText
            else ellipsize(metaLineText, metrics, budget)
        } else {
            val line1 = if (metrics.stringWidth(metaLineText) <= budget) metaLineText else ellipsize(metaLineText, metrics, budget)
            "<html>$line1<br>${perfLineHtml(budget, metrics)}</html>"
        }
    }

    /** F3：全文展示文本（未布局/预算充裕时）——meta 行 + perf 行原着色 markup，与 T-SqlPerf 原渲染一致。 */
    private fun fullDisplayText(): String {
        if (metaPerfSegments.isEmpty()) return metaLineText
        val markup = metaPerfSegments.joinToString("；") { "<font color=\"${it.color}\">${it.text}</font>" }
        return "<html>$metaLineText<br>   $markup</html>"
    }

    /** F3：perf 行预算内重拼——逐段（含「；」分隔宽）按像素累计，放得下保留原着色；放不下丢尾段并加省略号。 */
    private fun perfLineHtml(budget: Int, metrics: FontMetrics): String {
        val segments = metaPerfSegments
        if (segments.isEmpty()) return ""
        val indent = "   "
        var used = metrics.stringWidth(indent)
        val rendered = StringBuilder(indent)
        var kept = 0
        segments.forEachIndexed { index, segment ->
            val piece = (if (index > 0) "；" else "") + segment.text
            val pieceWidth = metrics.stringWidth(piece)
            if (kept > 0 && used + pieceWidth > budget) return@forEachIndexed
            if (kept == 0 && used + pieceWidth > budget) {
                // 首段放不下：首段文本尾部省略至预算，后续段全弃（HTML 标签外截断，tooltip 恒全文）
                val text = ellipsize(segment.text, metrics, (budget - used).coerceAtLeast(MIN_META_BUDGET_PX / 2))
                rendered.append("<font color=\"").append(segment.color).append("\">").append(text).append("</font>")
                return rendered.toString()
            }
            rendered.append(if (index > 0) "；" else "")
                .append("<font color=\"").append(segment.color).append("\">").append(segment.text).append("</font>")
            used += pieceWidth
            kept++
        }
        if (kept < segments.size) {
            rendered.append("<font color=\"").append(segments[kept - 1].color).append("\">…</font>")
        }
        return rendered.toString()
    }

    /**
     * F2/T-SqlPerf：构建性能建议分段（可见文本 + 颜色）。ERROR/WARN 红前缀 ⚠，INFO 蓝前缀 ℹ。
     */
    private fun buildPerfSegments(result: PreviewResult): List<MetaPerfSegment> {
        val warnings = result.perfWarnings ?: return emptyList()
        if (warnings.isEmpty()) return emptyList()
        return warnings.map { warning ->
            val isError = warning.severity == PerfSeverity.ERROR || warning.severity == PerfSeverity.WARN
            val prefix = if (isError) "⚠ " else "ℹ "
            MetaPerfSegment(
                text = prefix + localizeWarning(warning),
                // T-UN-13（T-UiNative）：HTML 段色写死 hex → namedColor 取主题色再注入（亮值不变，
                // HTML 结构与截断逻辑不动）
                color = MpvsUiColors.htmlHex(if (isError) MpvsUiColors.PERF_ERROR_TEXT else MpvsUiColors.PERF_INFO_TEXT),
            )
        }
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
        UiTextClipboard.copy(warning)
        return true
    }

    override fun removeNotify() {
        super.removeNotify()
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] panel removeNotify: embedded=${System.identityHashCode(embeddedConsole)}")
        releaseEditor()
    }

    /**
     * T-DarkTheme：释放主题监听（messageBus.connect(this) 随 Disposable 销毁自动取消订阅）。
     * 工具窗口模式下面板可能长期存活，由 [SqlPreviewDialog] 或工具窗口工厂调用释放。
     */
    override fun dispose() {
        // T-RefreshDbSteal：面板废弃——移除 rehost 监听（共享控制台可能已被新面板接管，
        // 本面板的监听不得残活抢组件）；embedConsole 对 null 安全
        runCatching { detachConsole() }
        // T-AutoFold F5：面板废弃——控制台编辑器引用一并清空（防 EditorFactory 泄漏告警）
        nativeConsoleEditor = null
        // T-M36.6-03：面板销毁——晚到的预热线程产物作废（预热线程自查后删除临时文件）
        previewDisposed = true
        // T-Perf-04：防抖/历史 Timer 随面板停止（防晚到 flush 触碰已销毁面板状态）
        renderDebounceTimer.stop()
        pendingRender = null
        historyAlarm.stop()
        // T-Perf-16：释放 DataSourceSelector 单例滞留引用（弹窗关闭此前不走 clear 按钮路径——
        // prepared/shared/pendingOnConsole 强引用已关闭面板与原生控制台，堆转储滞留整棵面板树）
        SqlExecutor.releaseForPanel()
        // messageBus 连接经 connect(this) 绑定本 Disposable，Disposer.dispose 时自动取消订阅
        Disposer.dispose(this)
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    internal fun editorForTest(): Editor? = editor

    /** T-PreviewConsoleFmt 测试缝：模拟控制台文档已就绪（nativeDoc 指向该文档）。 */
    internal fun markNativeDocForTest(doc: com.intellij.openapi.editor.Document) {
        nativeEditorDoc = doc
    }

    /** T-PreviewConsoleFmt 测试缝：挂载后回填渲染的等价入口。 */
    internal fun renderSqlForTest() = renderSql()
    /** T-AutoFold F5：登记的原生控制台编辑器引用（支链①接入断言用）。 */
    internal fun nativeConsoleEditorForTest(): Editor? = nativeConsoleEditor
    internal fun psiFileForTest(): PsiFile? = psiFile
    internal fun currentForTest(): PreviewResult? = current
    internal fun compactForTest(): Boolean = compactMode
    /** T-PreviewDisplayMode：当前显示模式（参数变更后保持性断言用）。 */
    internal fun displayModeForTest(): PreviewDisplayMode = displayMode
    /** M36.5.2：内嵌控制台组件（同实例防抖/替换断言用）。 */
    internal fun embedConsoleForTest(component: JComponent) = embedConsole(component)
    internal fun embeddedConsoleForTest(): JComponent? = embeddedConsole
    /** M36.5.39-A2：持久包装容器 + 显示链宿主（ConsoleHostTest 断言用）。 */
    internal fun consoleHostForTest(): JComponent = consoleHost
    internal fun editorHolderForTest(): JComponent = editorHolder
    /** M36.5.39-A1：最近一次嵌入 console 的身份 id（sameAsPrev 探针断言用）。 */
    internal fun lastConsoleEmbeddedIdForTest(): Int? = lastConsoleEmbeddedId
    /** T-RefreshDbSteal F3：摘除控制台 + 移除 rehost 监听（生命周期断言用）。 */
    internal fun detachConsoleForTest() = detachConsole()
    /** T-RefreshDbSteal F4：所有权判定（卫队断言用，见 RefreshDbStealTest）。 */
    internal fun consoleAttachedToMeForTest(): Boolean = consoleAttachedToMe()
    /** T-RefreshDbSteal F3：当前挂接的 rehost 监听是否仍在组件上（解绑断言用）。 */
    internal fun rehostListenerAttachedForTest(): Boolean {
        val console = embeddedConsole ?: return false
        val listener = rehostListener ?: return false
        return console.hierarchyListeners.contains(listener)
    }

    // ------------------------------------------------------------------
    // F-46：来源位置（来源列默认值）
    // ------------------------------------------------------------------

    /** 设置当前代码位置（文件名:行号），来源列默认显示此值。 */
    fun setSourceLocation(loc: String?) { sourceLocation = loc }

    /** 当前代码位置（文件名:行号），null 表示未知。 */
    fun currentSourceLocation(): String? = sourceLocation

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

    // ------------------------------------------------------------------
    // T-PreviewTopOverlap F4：顶部行布局 / 折叠菜单测试访问
    // ------------------------------------------------------------------

    /** 顶部行（metaLabel + 工具栏宿主，布局断言用）。 */
    internal fun topRowForTest(): JBPanel<JBPanel<*>> = topRow

    /** 工具栏（直显按钮集合断言用）。 */
    internal fun toolbarForTest(): JBPanel<JBPanel<*>> = toolbarPanel

    /** 「更多」折叠菜单项文案（顺序与弹层一致，断言用；show 依赖真实屏幕故只暴露文案与 step）。 */
    internal fun moreActionsForTest(): List<String> = moreActions().map { it.first }

    /** 「更多」折叠菜单 step（onChosen 委托断言用，无需真实弹层）。 */
    internal fun moreActionsStepForTest(): com.intellij.openapi.ui.popup.util.BaseListPopupStep<String> =
        moreActionsStep(moreActions())

    /** F3：宽度预算截断手动触发（headless 无 componentResized，测试直调）。 */
    internal fun applyMetaTruncationForTest() = applyMetaTruncation()

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

    /** T-HibDualCapture：第 index 个普通参数框的 tooltip（`#source = 值`），供长值兜底断言。 */
    internal fun paramFieldTooltipForTest(index: Int): String? = paramFields.getOrNull(index)?.toolTipText
    /** T-HibDualCapture：第 index 个普通参数框的光标位置（应钉 0，视口停在值头部）。 */
    internal fun paramFieldCaretForTest(index: Int): Int = paramFields.getOrNull(index)?.caretPosition ?: -1
    internal fun conditionCheckboxForTest(index: Int): JCheckBox? = conditionCheckBoxes.getOrNull(index)
    internal fun conditionValueFieldForTest(index: Int): JBTextField? = conditionValueFields.getOrNull(index)
    /** T-M23-04：第 index 个批量列级勾选复选框（toggle 列按 BatchModel 顺序）。 */
    internal fun batchCheckboxForTest(index: Int): JCheckBox? = batchCheckBoxes.getOrNull(index)
    /** T-M23.2 / T-SelToggle：批量「参数列」区全选/全不选切换按钮。 */
    internal fun batchToggleSelectButtonForTest(): JButton? = batchToggleSelectButton
    internal fun enabledIdsForTest(): Set<String> = enabledIds.toSet()
    internal fun toggleSelectButtonForTest(): JButton? = toggleSelectButton
    internal fun clearButtonForTest(): JButton? = clearButton
    internal fun batchClearButtonForTest(): JButton? = batchClearButton
    /** T-M26.1→T-UiNative：左右分栏（平台 OnePixelSplitter，原生分隔条主题融合 + 比例记忆）。 */
    internal fun splitPaneForTest(): OnePixelSplitter? {
        fun find(c: java.awt.Container): OnePixelSplitter? {
            for (child in c.components) {
                if (child is OnePixelSplitter && !child.orientation) return child
                if (child is java.awt.Container) find(child)?.let { return it }
            }
            return null
        }
        return find(this)
    }
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
        /** F-45：列信息标签页标题。 */
        private const val COLUMN_INFO_TAB_TITLE = "列信息"

        /** T-PreviewTopOverlap F3：元信息宽度预算下限（px）——极窄时保底可读，超出仍由 ellipsize 收。 */
        private const val MIN_META_BUDGET_PX = 80

        /** M36.5.44：re-host 最小重挂间隔（ms）——DBN 结果刷新期高频 reparent，低于此间隔视为"还在被 DBN 处理"，跳过不抢，防死循环刷新闪烁。 */
        private const val REHOST_MIN_INTERVAL_MS = 800L

        /** T-RefreshDbSteal：去抖窗口内被跳过的释放 → 延迟复查间隔（ms）。取与去抖门槛同值：
         *  复查时刻距最近一次重挂必然已越过门槛，`since` 复查不会误拦。 */
        private const val REHOST_RETRY_DELAY_MS = 800L

        private val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")
        /** T-M24-04：XML 压缩视图的空白归一化（多行文本节点 / 段间空格）。 */
        private val WHITESPACE_REGEX = Regex("\\s+")

        /** T-Perf-04：参数击键 renderSql 防抖窗口（毫秒）。 */
        private const val RENDER_DEBOUNCE_MS = 150

        /** T-PreviewFlash：EDT 等待预热产物的轮询间隔与超时（毫秒）。 */
        const val PREWARM_POLL_MS = 20L
        /** T-PreviewSlow F1：把「EDT 同步自旋等预热产物」的预算从 2000ms 砍到 60ms——
         * 冷启动时预热线程（同步 VFS refresh + PSI findFile）常跑几百毫秒到数秒，EDT 干等
         * 到 2000ms 会把延迟烧进 UI 线程（真机日志双击→createEditor 达 3.07s）。降到经验值
         * 60ms：预热线程已就绪（面板再开/热态）立刻命中走 PSI 主路径，冷启动第一拍直接降级
         * 纯内存编辑器不烧 EDT；预热完成后下次打开即自愈走回 PSI 高亮/补全路径。 */
        const val PREWARM_WAIT_MS = 60L
    }
}
