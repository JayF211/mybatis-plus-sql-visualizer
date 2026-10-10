package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.execute.ConsoleEmbedMode
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
// T-M53-II：`installOutputHistoryFillLayout` 已搬为 execute 包内的同包扩展函数（原 DataSourceSelector 成员），
// 跨包调用点需显式导入。
import cn.song.mybatisplus.visualizer.execute.installOutputHistoryFillLayout
import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.FillClause
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.sql.ExplainGate
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.PlaceholderCardinality
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator
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
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope
import cn.song.mybatisplus.visualizer.util.CaseFormatUtil
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.xml.ExprReason
import cn.song.mybatisplus.visualizer.xml.ExprSupport
import cn.song.mybatisplus.visualizer.xml.ConditionOutcome
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql
import cn.song.mybatisplus.visualizer.xml.XmlConditionView
import cn.song.mybatisplus.visualizer.xml.XmlForEachView
import cn.song.mybatisplus.visualizer.xml.ParamKind
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
import com.intellij.ui.HideableTitledPanel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
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
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
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
// T-M53-II：构造期属性放宽为 internal——同包扩展函数（拆出的协作文件）需要读取它；
// 可见性只在模块内变化，调用点语义不变。
class SqlPreviewPanel(internal val project: Project? = null) : JBPanel<SqlPreviewPanel>(BorderLayout()), Disposable {

    internal val metaLabel = JBLabel()

    // T-PreviewTopOverlap F1/F3：顶部行与工具栏提升为字段——布局断言（topRowForTest/toolbarForTest）
    // 与宽度预算计算（applyMetaTruncation 需工具栏理想宽）使用
    internal lateinit var topRow: JBPanel<JBPanel<*>>
    private lateinit var toolbarPanel: JBPanel<JBPanel<*>>

    // T-PreviewTopOverlap F3：元信息原文缓存——metaLineText 第一行（meta+警示，纯文本）、
    // metaPerfSegments 第二行分段（可见文本+颜色）；tooltip 恒全文，label 文本按预算重拼
    internal var metaLineText: String = ""

    /**
     * F3 分段缓存（P5 复核修正）：meta 行**主体**（meta + 警示行）——宽度预算下恒优先保留。
     * 与 [metaLineDigest] 分段缓存的原因见 [metaLineWithinBudget]（摘要不得挤掉主体）。
     */
    internal var metaLineHead: String = ""

    /** F3 分段缓存（P5 复核修正）：meta 行尾部的调用栈内联摘要——预算不足时先截它。 */
    internal var metaLineDigest: String = ""
    private var metaPerfSegments: List<MetaPerfSegment> = emptyList()
    internal var lastMetaBudget: Int = -1

    /**
     * T-SourceJumpFunnel P6 修复：meta 行 tooltip 的**基线文本**（[updateMeta] 每次预览重建）。
     *
     * 行内提示态（参数导入空剪贴板）需要临时改写可见行与 tooltip，但 tooltip 是「悬停见完整调用链」
     * 的唯一载体（§3.3 后半句），不能被提示覆写丢掉——故提示态按「提示 + 换行 + 基线」拼接，
     * 本字段是拼接的基线来源（也保证提示重复触发时幂等，不会叠加自身）。
     *
     * T-StackHoverUi 起栈文本移出 tooltip（见 [stackHover]）：本基线只含 meta/警示/perf，
     * 「完整调用链」改由悬停弹层承接。
     */
    internal var metaTooltipBase: String = ""

    /**
     * T-StackHoverUi：调用栈悬停弹层控制器（调试器 Frames 风格，D84 拍板 A 案）。
     *
     * 栈文本在悬停面的唯一载体——旧实现把截断摘要+全文重复拼进 tooltip、超长单行折成文本墙；
     * 声明序在 init 块之前（构造期 NPE 固定检查项：init 内 attach/register 均读本字段）。
     */
    private val stackHover = StackFrameHover(project)

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
    internal val paramPanel = JBPanel<JBPanel<*>>().apply {
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
    internal val paramScroll = JBScrollPane(paramTopAlign).apply {
        preferredSize = Dimension(240, 200)
        // T-PreviewBgFit（问题一）：本面板自身给出面板底色——左侧参数区整条链是透明的
        //（paramTopAlign/viewport 均非不透明，M36.5 起用于与父容器融合），底色一直靠祖先容器提供；
        // T-UiNative P0a 把自绘 ThemedSplitPane（当时显式设了 UIUtil.getPanelBackground()）换成平台
        // OnePixelSplitter 时丢了那层背景，深色主题下参数行下方直接透出 splitter 的近黑背景。
        // 恢复祖先背景之外再让本滚动区成不透明面板灰，今后再换容器也不会复发。
        isOpaque = true
        background = com.intellij.util.ui.UIUtil.getPanelBackground()
        viewport.isOpaque = false
        // M36.5：去 LAF 默认 viewport 描边（左侧白色内边框根因）
        border = JBUI.Borders.empty()
    }
    internal val paramFields = mutableListOf<JBTextField>()
    // T-PT-02：普通参数行行尾校验警示标（与 paramFields 同序，仅 buildParamRow 产出）
    internal val paramWarnLabels = mutableListOf<JBLabel>()
    internal val conditionCheckBoxes = mutableListOf<JCheckBox>()
    // R30 条件求值探镜：XML 条件行尾的三态结果标注（条件 id → 标签），renderSql 每轮刷新（零监听器）
    internal val xmlConditionOutcomeLabels = mutableMapOf<String, JBLabel>()
    internal var autoPopupDisposable: Disposable? = null
    internal val conditionValueFields = mutableListOf<JBTextField>()
    // T-M23-04：批量列级勾选复选框（INSERT/UPDATE/UPSERT 参数列，与动态条件复选框互斥——批量预览无 conditionViews）
    internal val batchCheckBoxes = mutableListOf<JCheckBox>()
    // T-M26.3：全选/全不选按钮回挂分区标题行右侧（IDEA 组操作惯例，T-M23.2 原生形态；
    // T-M26.2 曾固定面板右上角，长文本截断后按钮不再被推出视口）。
    // T-SelToggle：全选/全不选合并为单个状态切换按钮——全勾时文案「全不选」点击清空，
    // 其余态文案「全选」点击填满；文案随勾选态翻转（renderSql 末尾单点同步）。
    internal var toggleSelectButton: JButton? = null
    internal var clearButton: JButton? = null        // 动态条件分区清除按钮
    internal var batchClearButton: JButton? = null    // 批量参数列分区清除按钮
    // T-M23.2：批量「参数列」区全选/全不选切换按钮（T-SelToggle 合并单按钮）
    internal var batchToggleSelectButton: JButton? = null

    // 右：可编辑 SQL 编辑器（PSI 支撑，补全用）
    internal var editor: Editor? = null

    /**
     * T-M27.2：自建 autopopup 的调度防重入标志（平台 autopopup 对非文件编辑器不生效）。
     * T-RA-02：监听器本体不再用字段记账——T-M52 起移除职责全在 [autoPopupDisposable]
     * （`addDocumentListener(listener, disposable)`，dispose 即摘），原 `autoPopupListener`
     * 字段自此只写不读，已删；细节见 [bindAutoPopup]。
     */
    internal var autoPopupScheduled = false

    /** T-M27.4：渲染 setText 期间抑制自建 autopopup（格式化替换整文档不触发补全）。 */
    internal var suppressAutoPopup = false
    internal var psiFile: PsiFile? = null
    /** M34 回归修复：预览 PSI 的临时 VirtualFile（PsiDocumentManager 追踪需真实 VFS 文件），releaseEditor 时删除。 */
    @Volatile
    internal var previewTempFile: com.intellij.openapi.vfs.VirtualFile? = null
    /**
     * T-FixN2：本面板**已创建**的预览临时文件登记表（[projectPreviewFile] 建文件即登记，
     * [releaseEditor] 统一回收）。与 [previewTempFile] 的分工：后者是「当前在用/最近一次预热」
     * 的那一个（唯一名字后仍可能被竞态里晚到的预热覆盖），本表是「本面板创建过的全部」——
     * 覆盖「预热已发布但从未被装配消费」与「两次预热竞态中晚到的那一个」两类无主文件。
     *
     * 只登记本面板自己创建的文件（含跨会话同名残留的兜底复用分支），故 [releaseEditor] 的删除
     * **不会**误删其它面板/其它 IDE 会话正在使用的预览文件（同项目多开进程各有自己的 pid 段）。
     */
    internal val previewTempFilesCreated = java.util.concurrent.ConcurrentHashMap.newKeySet<java.io.File>()

    /**
     * T-PerfSweep6 T-PS6-04：目录级兜底清扫的「本面板已扫过」闸门（首次解析预热目录时扫一次）。
     * 原子置位：`projectPreviewFile` 可能被预热线程与 EDT 同时触达。
     */
    internal val previewDirSwept = java.util.concurrent.atomic.AtomicBoolean(false)
    /** T-M36.6-03：后台预热产物（vf+PSI 就绪对），createEditor 优先消费——EDT 零慢操作。 */
    @Volatile
    internal var prewarmedTempFile: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? = null

    /**
     * T-PerfTail #2：预热产物异步载体（消除 EDT sleep-poll）——prewarmPreviewTempFile 每次
     * 预热新建（clear 重预热随之更新），createEditor 热态同步消费、冷态注册回调回 EDT 装配。
     */
    @Volatile
    internal var prewarmAwait: PrewarmAwait<Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>>? = null
    /** T-GifRerecord：预热失败标志——await 快速降级用，避免 EDT 空等 2s 后再走降级路径。 */
    @Volatile
    internal var prewarmFailed = false

    /**
     * T-PrewarmLateRecover F1/F2：预热轮次令牌——每次发起预热（构造期 / [clear] 重预热 / 横幅重试）
     * 自增，预热线程发布产物时比对。不等即「本轮已被后来的一轮取代」：产物不入字段（否则旧轮的
     * 迟到产物会覆盖新轮刚交付的那份），文件仍由 [releaseEditor] 按登记表回收。
     */
    @Volatile
    internal var prewarmRound = 0

    /**
     * T-PrewarmLateRecover F1：当前编辑器文档是**降级产物**时的身份标记（主路径装配成功置 null）。
     * 迟到产物换装前用它确认「此刻显示的确实是那份降级文档」——不比对身份就可能把用户已经
     * 在用的主路径文档误换掉。
     */
    internal var degradedPreviewDocument: com.intellij.openapi.editor.Document? = null

    /**
     * T-PrewarmLateRecover F1：插件最后一次写进编辑器文档的 SQL 文本——换装判据里的「用户没动过」。
     * 三条写入支链（原生控制台 / 既有编辑器 / 新建编辑器）都要更新，漏一条即把插件的重渲染
     * 误判成用户编辑（换装被永久挡掉）。
     */
    internal var lastRenderedSql: String? = null

    /**
     * T-PrewarmLateRecover F1：预热相关回调的 EDT 派发通道（写安全模态）。
     *
     * 与 [prewarmPreviewTempFile] 里 PrewarmAwait 的 dispatcher 是同一份实现：迟到产物换装也要
     * 写文档（`WriteCommandAction { d.setText }`），故必须同款 `defaultModalityState()`——
     * `any()` 恒 write-unsafe（T-FixN1 的 KDoc 有平台侧逐帧依据）。
     */
    internal val prewarmDispatcher: (Runnable) -> Unit = { runnable ->
        ApplicationManager.getApplication().invokeLater(
            runnable,
            com.intellij.openapi.application.ModalityState.defaultModalityState(),
        )
    }
    /** T-M36.6-03：面板释放标志——预热线程晚于 dispose 完成时丢弃产物并删除文件。
     *  T-StabilityTail 起语义扩为「放弃预热」：面板 dispose 与**项目开始销毁**（见
     *  [prewarmLifecycleGate]）都置它，预热线程在下一个检查点放弃（不再取服务）。 */
    @Volatile
    internal var previewDisposed = false

    /**
     * T-StabilityTail（窗口内异步泄漏治理）：预热任务的「项目开始销毁」取消门。
     *
     * 注册进 `ProjectEx.getEarlyDisposable()` 的 Disposable 树（见 [bindPrewarmToProjectLifecycle]），
     * 项目开始销毁时由平台回收并置 [previewDisposed]，使构造期/`clear()` 派发的池线程在窗口内
     * 不再取服务。面板 dispose 时解除登记（不留指向本面板的项目树引用）；null = 未挂或已解除。
     */
    internal var prewarmLifecycleGate: Disposable? = null
    /** T-PreviewExecDs-01c：已 setMapping 方言的预览 vf（同对象幂等短路，避免每次预览重设方言触发 push all dumb）。
     *  T-FixN2 起该 vf 是**预览目录**（不再是单个文件）——见 [mapPreviewDialectBeforePsi]。 */
    @Volatile
    internal var previewDialectMappedVf: com.intellij.openapi.vfs.VirtualFile? = null
    /** F-46：当前代码位置（文件名:行号），来源列默认值；null 表示未知（日志/历史等无锚点场景）。 */
    private var sourceLocation: String? = null
    internal val editorHolder = JBPanel<JBPanel<*>>(java.awt.GridLayout(1, 1)).apply {
        // M36.5.25e：GridLayout(1,1) 强制唯一子组件（内嵌控制台）fill——BorderLayout CENTER
        // 受子组件 preferredSize 影响导致 editor 不 fill（实际高度≈preferred ~375，15 行 SQL
        // 被截断）；GridLayout 按可用空间均分强制填满，editor 高度自适应窗口大小，无需固定
        // minimumSize 兜底。首次塌缩兜底由 editorComponentRaw 的 minimumSize 承担。
        isOpaque = false
    }

    // M36.5：SQL 执行状态面板（主面板 SOUTH——仅在有状态/错误内容时可见，避免空白占位）
    internal val resultPanel = SqlResultPanel().apply {
        isVisible = false
    }

    /**
     * T-UxOnboard T-UX-02：预览编辑器空态引导（原生 placeholder，显隐由编辑器随空文档管理）。
     * 常态下预览内容即编辑器全文、placeholder 不可见；用户清空文档后原生提示接管。
     */
    internal val previewEmptyHint: String = MpSqlVisualizerBundle.message("panel.preview.emptyHint")

    /** M36：已内嵌的原生控制台 UI 组件（清空/释放时级联 dispose）。 */
    internal var embeddedConsole: JComponent? = null

    /**
     * R33：结构化 EXPLAIN 回调代际计数——每次发起新的 EXPLAIN 结构化提交/新预览/清空都递增，
     * 在途 pooled 回调（JDBC 可能慢于用户操作）按提交时捕获的代际判过期，过期即丢弃：
     * 慢计划绝不覆盖用户已经看到的新预览/新提交（与 DataSourceSelector 的 swapGeneration 同思路）。
     */
    internal var explainStructuredGeneration = 0

    /**
     * M36.5.39-A2：持久包装容器——console 组件统一嵌入此容器、容器整体挂入 editorHolder。
     * DBN results refreshed 期间会临时拿走 console 组件，旧的直挂方式（editorHolder.add(console)）
     * 会把 editorHolder 的显示链一并波及（holderShowing=false → 右侧空白）；隔离后 DBN 只动
     * consoleHost 子层，editorHolder/ThemedSplitPane 显示链稳定。
     */
    internal val consoleHost = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply { isOpaque = false }

    /** M36.5.39-A1：最近一次嵌入 console 的身份 id（identityHashCode）——sameAsPrev 探针判定用。 */
    internal var lastConsoleEmbeddedId: Int? = null

    /** M36.5.44：上次 re-host 时刻（去抖用）——DBN 结果刷新期高频 reparent，需最小间隔抑制死循环。 */
    internal var lastRehostAt: Long = 0L

    /** T-RefreshDbSteal：当前挂接的 rehost HierarchyListener 引用——detach/dispose 时显式移除。
     *  旧实现「监听随组件废弃，无需显式解绑」的假设在共享控制台场景不成立：组件被新面板复用后
     *  旧面板的监听仍存活，parent==null 时会把共享控制台抢回已废弃面板（真机日志 6 连抢实锤）。 */
    internal var rehostListener: java.awt.event.HierarchyListener? = null

    /** T-RefreshDbSteal：去抖窗口内被跳过的释放事件 → 已调度延迟复查（单次标志，防重复调度）。 */
    internal var rehostRetryScheduled = false

    // M36.4：原生编辑器文档引用（参数回填/格式化时使用）
    /** 原生编辑器的文档引用（内嵌后参数变更同步用）。 */
    internal var nativeEditorDoc: com.intellij.openapi.editor.Document? = null

    /**
     * T-AutoFold F5：原生控制台编辑器引用（onNativeEditorReady 时登记）——
     * showSql nativeDoc 支链的程序性折叠应用目标；detachConsole/clear/dispose 时清空，
     * 防止 EditorFactory 泄漏告警。recoverConsole 失联路径经 embedConsole 换新组件后
     * 由下一次 onNativeEditorReady 重新登记。
     */
    internal var nativeConsoleEditor: com.intellij.openapi.editor.Editor? = null

    internal var current: PreviewResult? = null
    internal var compactMode = false

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
    /**
     * T-F8：JOIN 图标签页的加载面——包住整个 [diagramTabHost]，转圈盖在标签页内容之上。
     *
     * 为什么加载面必须在宿主层而不是图面板内部：唯一真正的耗时段是 [updateDiagramTab] 的
     * **后台 schema 富化**（冷缓存按 schema 规模线性），而原生图路径（默认设置
     * `joinDiagramNative = true`，Ultimate 真机默认）下 [showJoinDiagram] 的
     * `diagramTabHost.removeAll()` 会把 [diagramPanel] 摘出层级——面板内部自摆的转圈在默认配置下
     * 根本不可见（审计项 F8：T-UN-14 接的转圈从未出现过，showLoading/hideLoading 全仓零调用点）。
     * 宿主层对原生/自绘两条渲染路径都可见，是唯一能覆盖真机默认路径的加载面。
     *
     * 转圈层由平台 [JBLoadingPanel] 提供（独立 loadingLayer 盖在内容层之上，不替换内容，
     * 2 参构造 startDelayMs=-1 使 startLoading/stopLoading 同步改可见性）。
     * Disposable 挂本面板（[JBLoadingPanel] 的 2 参构造要求父 Disposable）。
     */
    private val diagramLoadingHost = JBLoadingPanel(BorderLayout(), this)
    private var currentNativeDiagram: JComponent? = null
    /**
     * T-DbSchemaCompletion：待补刷富化的图（首次富化一点数据源信息都没拿到时记下）。
     *
     * 仅存"上一次富化空手而归"的那个 [PreviewResult]——数据源建连（原生控制台就绪）后据此补刷一次，
     * 刷完置空；新预览替换 current 时旧值自然失配，无需额外清理。
     */
    internal var diagramEnrichRetryTarget: PreviewResult? = null
    // F-45：maintainPage 列信息（仅 <pages> 预览非 null 时显示标签页）
    private var columnInfoPanel: JComponent? = null
    /** 内容区卡片：null=无内容 / "sql"=SQL编辑 / "sql+diagram"=SQL编辑 + JOIN图标签页。 */
    private var contentPane: JBPanel<JBPanel<*>>? = null
    private var contentTabPane: JTabbedPane? = null

    // M12：动态条件启用集合（勾选状态，元素为 ConditionToggle.id）+ 参数值（按 source 绑定，取代位置绑定）
    internal val enabledIds = mutableSetOf<String>()
    internal val paramValues = mutableMapOf<String, String>()
    // T-M24-04：XML 预览渲染状态（条件开关复用 enabledIds，id = test 表达式原文；
    // T-M26.4：bind 覆盖状态已移除——bind 值由「参数」区输入统一承担）
    internal val xmlForEachCounts = mutableMapOf<String, Int>()
    // O-78 T-ForeachItemRender：foreach 集合值权威状态（collectionExpr → 逗号分隔解析后的值序）——
    // 播种收进 renderSql 帧内按新鲜映射清单进行，值不再依赖「渲染后同步读键清单」
    internal val xmlForEachValues = mutableMapOf<String, List<String>>()
    internal val xmlForEachNulls = mutableSetOf<String>()
    // R22 T-02/T-03：勾选态与 foreach 行的组件登记（快照枚举 + 召回视觉同步；随 rebuild 清场）
    internal val xmlForEachNullBoxes = mutableListOf<JCheckBox>()
    internal val xmlForEachValueFields = mutableListOf<Pair<String, JBTextField>>()
    private var xmlErrors = listOf<String>()
    /** T-M25.4：最近一次 XML 渲染的参数映射（集合值输入按当前渲染键回填）。 */
    internal var xmlMappings = emptyList<cn.song.mybatisplus.visualizer.xml.ParameterMapping>()
    // T-M25.3：错误信息复制按钮（warning 存在时可见，一键复制完整错误文本）
    internal var errorCopyButton: JButton? = null
    internal var currentWarning: String? = null

    /**
     * T-PSG-04（N-33）：最近一次参数回填的「`?` 位点数 vs 参数条数」对账标注——不等时非 null。
     *
     * 只在 [backfillParamsBySource] 里按串感知位点数与 sources 条数计算（每次回填都重算，不留
     * 陈旧值）；执行前由 [warnOnPlaceholderMismatch] 读走并如实登记日志。**不阻断**任何流程
     * （与我方既有 `${}` 闸门「确认/标注」档一致，不引入拒绝档）。
     */
    internal var placeholderReconciliation: String? = null

    // T-PreviewUx-Pack #2：诚实降级横幅——渲染警示（RENDER_WARNING）与 T-PreviewExecDs
    // 降级链层级提示（DEGRADED）统一收口；降级提示优先级高于渲染警示（见 DegradationBanner.Kind）
    internal val degradationBanner = DegradationBanner().apply {
        // T-DollarInjectMark：✕ 关闭 → 会话级记忆（本 IDE 会话不再弹；测试经 resetDollarMarkForTest 复位）
        onDismiss = {
            dollarMarkDismissedSession = true
            clear(DegradationBanner.Kind.DOLLAR_INJECT)
        }
    }

    /** T-DollarInjectMark：最近一次 XML 渲染的 `${}` 直替计数（非 XML 路径恒 0）。 */
    private var xmlDollarSubstitutions = 0

    /**
     * T-InjectionGate T-IG-02：本面板实例内已确认过 `${}` raw 段执行（会话级记忆）。
     *
     * 同一面板确认一次后不再重复弹（常用 `${orderBy}` 场景的确认疲劳缓解）；记忆随面板实例
     * 存活——面板 dispose 即失效（工具窗换预览内容不重置，与 T-DollarInjectMark 的 IDE 会话级
     * 关闭记忆同向）。@Volatile：执行入口可能在非 EDT 触发（弹窗按钮回调），读写可见性保守处理。
     */
    @Volatile
    internal var dollarRawConfirmedInSession = false

    /**
     * T-PC2-04（D1=B 定案）：本面板实例内已确认过含 UNKNOWN 参数执行（会话级记忆）。
     *
     * 语义与 [dollarRawConfirmedInSession] 同款（同面板确认一次后不再重复弹；执行与 EXPLAIN
     * 共用同一记忆，两条入口是同一道门）；记忆随面板实例存活，面板 dispose 即失效。
     * @Volatile：执行入口可能在非 EDT 触发（弹窗按钮回调），读写可见性保守处理。
     */
    @Volatile
    internal var unknownParamsConfirmedInSession = false

    // P-05：参数历史缓存 + debounce 写回
    internal val paramHistoryCache = ParamHistoryCache()
    /** P-05：debounce 写回调度器（Swing Timer，500ms 延迟，restart 实现防抖）。 */
    internal val historyAlarm = javax.swing.Timer(500) { flushHistoryToCache() }.apply { isRepeats = false }

    // R22 T-04：命名方案下拉（rebuildLeftPanel 随参数区重建；syncSchemeCombo 填充/刷新）
    internal var schemeCombo: JComboBox<String>? = null
    // O-95 S3：绑定数据源指示行标签（rebuildLeftPanel 重建；renderSql 尾部单点刷新）
    internal var bindStateLabel: JBLabel? = null
    /** 程序化填 combo 模型时置位（抑制 addActionListener 的用户切换语义）。 */
    internal var schemeSyncingCombo = false
    /** 上次已同步到下拉的激活方案名——对话框回调时比对判定「激活方案被对话框切走」。 */
    internal var lastShownActiveScheme: String = ParamHistoryCache.DEFAULT_SCHEME
    /** 管理方案对话框（MODELESS，单实例防堆叠；关闭后由可见性判断重建）。 */
    internal var schemeManagerDialog: ParamSchemeManagerDialog? = null

    // O-88：预设行折叠件（平台标准 HideableTitledPanel，Run Config「Before launch」区同款）。
    // 折叠态=companion 静态字段（声明见伴生对象 [Companion.presetSectionCollapsed]）：
    // 跨预览重建且跨面板实例保留——gutter 换语句走「关旧弹窗、开新面板实例」（真机走查实证：
    // 实例字段在新实例归零、折叠态丢失），静态化后新实例初始态继承；不跨重启持久化、无设置开关。
    /**
     * 当前预设折叠件实例——[HideableTitledPanel] 公共面无状态变更监听器（装饰器私有），
     * 用户点标题折叠的变化以「重建时读 [HideableTitledPanel.isExpanded] 回写」的懒惰捕获跟进；
     * 折叠件随 paramPanel.removeAll 摘除后实例仍持有最后视觉态，跨 clear/rebuild 均可恢复。
     */
    internal var presetFold: HideableTitledPanel? = null

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
    internal fun scheduleRender(task: () -> Unit) {
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
        // T-PreviewUx-Pack #2：诚实降级横幅（面板内嵌非模态提示，不算弹窗）——渲染警示 /
        // T-PreviewExecDs 降级链层级提示统一收口（触发即显示、恢复即隐藏，不占高度）
        // T-DevAcceptFix ①（20261006 验收）：绑定指示行迁出参数区——挂「更多」按钮（工具栏 EAST
        // 末位）正下方右对齐常驻薄行，与右侧内嵌控制台头部数据源显示相邻；init 一次构建常驻，
        // 不随参数区重建（常驻语义不再依赖参数区是否建行），refreshBindStateRow 单点刷新链不变。
        val northHost = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(topRow, BorderLayout.NORTH)
            add(buildBindStateRow(), BorderLayout.CENTER)
            add(degradationBanner, BorderLayout.SOUTH)
        }
        add(northHost, BorderLayout.NORTH)
        // T-PreviewTopOverlap F3：宽度预算截断跟随尺寸变化（首显 + 拉伸/缩窄；宽度未变短路）
        topRow.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = applyMetaTruncation()
            override fun componentShown(e: java.awt.event.ComponentEvent) = applyMetaTruncation()
        })

        // T-StackHoverUi：调用栈悬停弹层（调试器 Frames 风格）——meta 行悬停承接完整调用链，
        // 弹层生命周期随面板 Disposer 树级联释放（弹窗关闭即清）
        Disposer.register(this, stackHover)
        stackHover.attach(metaLabel)

        // 中部左右分栏：参数输入 | SQL 编辑器/原生控制台
        // T-M26.3：按钮回挂分区标题行右侧（conditionSectionHeader/batchSectionHeader），
        // 左面板即参数滚动区（T-M26.2 的固定操作行已拆除——长文本按视口截断后按钮不再被推出视口）
        val split = OnePixelSplitter(false, "mpvs.preview.splitter", 0.3f).apply {
            firstComponent = paramScroll
            secondComponent = editorHolder
            // T-PreviewBgFit（问题一）：恢复 T-UiNative P0a 换组件时丢掉的容器背景——
            // 原 ThemedSplitPane 显式设过 `background = UIUtil.getPanelBackground()`，OnePixelSplitter
            // 版本漏了它，左侧透明参数区于是透出分栏自身背景（深色主题近黑）。
            // T-DarkTheme：命名色按主题解析，浅/深主题各自正确（主题切换由 LafManagerListener 触发重绘）。
            background = com.intellij.util.ui.UIUtil.getPanelBackground()
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
        // T-F8：加载面在宿主层（标签页内容 = 加载面板 → diagramTabHost），并把它交给图面板出图
        //（面板自身的 contentLayer 在原生路径下不可见，见 diagramLoadingHost KDoc）
        diagramLoadingHost.add(diagramTabHost, BorderLayout.CENTER)
        diagramPanel.bindHostLoadingPanel(diagramLoadingHost)

        // T-M36.6-03：预热线程提前创建临时 SQL 文件 + VFS refresh + PSI 解析（后台线程慢操作合法），
        // createEditor（EDT）优先消费预热产物——243 平台 EDT/EDT-ReadAction 均禁止慢操作
        //（SlowOperations ERROR_EDT/ERROR_RA），面板打开期的 refreshAndFindFileByIoFile SEVERE 由此消除
        if (project != null) {
            prewarmPreviewTempFile()
        }
    }

    /** T-M36.6-03 / T-PreviewFlash：后台预热预览临时文件（占位空内容，createEditor 就绪后按实际 SQL 同步）。 */
    private lateinit var modeButton: JButton

    /**
     * 顶部工具栏：复制错误（有警示时可见，永不折叠——错误反馈链路一环）/ 压缩多行切换 / 格式化 /
     * 关键字换行 / 竖向排列 / 执行计划 / 刷新 / 更多（T-PreviewTopOverlap F2：导入参数/差异对比/分享
     * 三个低频动作折叠进「更多」JBPopup 菜单；执行 SQL / 复制 SQL 在弹窗底部按钮区，M17.4。
     * T-UiCoord F1：回滚 SQL 亦折叠进「更多」，直显按钮 8 → 7 枚）。
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
        // T-UiCoord F1（2026-09-21 用户要求）：回滚 SQL 折叠进「更多」——低频动作（只对
        // INSERT/UPDATE/DELETE 有意义）不再与格式化/刷新同列直显。**推翻** T-RollbackSql F3 与
        // T-MergeR2IntoMaster §3.2 的「一键直达 / 执行计划之后刷新之前」定案，改判依据见
        // docs/开发计划/20260921-1338-T-UiCoord-…-开发计划.md §三 F1；动作体 showRollbackSql() 一字未改。
        add(smallButton(MpSqlVisualizerBundle.message("panel.refresh")) { refreshView() })
        // T-PreviewTopOverlap F2：「更多」折叠菜单入口（低频动作收纳）
        add(smallButton(MpSqlVisualizerBundle.message("panel.more")) { showMoreActionsPopup() })
    }

    /**
     * 「更多」折叠菜单的四项低频动作（文案键沿用原工具栏按钮，执行体一字不差）。
     *
     * T-UiCoord F1：回滚 SQL 置于**首位**——四项里它最常用，而折叠会降低可发现性，
     * 首项是成本最低的补偿（对齐「菜单按使用频率排序」）。
     */
    private fun moreActions(): List<Pair<String, () -> Unit>> = listOf(
        MpSqlVisualizerBundle.message("panel.rollbackSql") to { showRollbackSql() },
        MpSqlVisualizerBundle.message("panel.param.import") to { importParamsFromClipboard() },
        MpSqlVisualizerBundle.message("panel.compare") to { showSqlDiff() },
        MpSqlVisualizerBundle.message("panel.share") to { showShareDialog() },
        MpSqlVisualizerBundle.message("panel.portableExport") to { exportPortableSql() },
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
                // N4 修复（2026-09-21）：动作体**一律延后到弹层销毁之后**执行。
                // 三项动作（导入参数 / 差异对比 / 分享）都会弹**模态对话框**，此前在 onChosen 内同步弹，
                // 平台判为焦点风险并记 SEVERE——原文即修法：「Showing dialogs in PopupStep.onChosen can
                // result in focus issues. Please put the dialog showing code into invokeLater…」
                // （`#c.i.u.p.u.PopupImplUtil`，栈指向本方法 :891 → importParamsFromClipboard:3482）。
                // **真机后果比日志噪音严重**：第一次点「确定」被弹层收走，用户感受是「点了没反应」。
                // 本方法返回 FINAL_CHOICE 后弹层即销毁，故 invokeLater 的体在弹层已销毁时才跑。
                val action = actions.firstOrNull { it.first == selectedValue }?.second
                if (action != null) {
                    ApplicationManager.getApplication().invokeLater { action() }
                }
                return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE
            }
        }

    /**
     * T-RollbackSql：回滚 SQL 视图入口（面板【回滚SQL】按钮）。
     *
     * 生成结果分层（计划 §2.2）：Complete/Partial 打开 [RollbackSqlDialog]；可解析的
     * UPDATE/DELETE（Unable 但有快照工作流）同样进视图——人工恢复指引 + 粘贴快照生成回滚；
     * 完全不可推导（解析失败/MERGE/DDL/SELECT）不弹视图，仅消息框说明原因与人工恢复路径。
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
            xmlForEachValues.clear()
            xmlForEachNulls.clear()
            xmlErrors = emptyList()
            xmlDollarSubstitutions = 0
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
        // T-DbSchemaCompletion：后台预热本文档涉及表的数据源 schema，完成后再刷一次上下文
        //（数据源列进补全候选；不依赖 JOIN 图，落 T-PreviewExecDs 遗留的 P1-5）
        prefetchSchemaForCompletion(result)
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
    internal fun updateDiagramTab(result: PreviewResult) {
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
            // T-F8：加载态包住「后台富化 + 回 EDT 换页」整段（showJoinDiagram 本体与 locateDbTables
            // 都在 EDT 同步跑，同一 EDT 事件内 startLoading 画不出来，故起止点必须跨事件）。
            // 这里只「进」；「出」的主收口点是 showJoinDiagram 末尾（正常路径两条分支都过它）。
            // T-TS-FixA：主收口点只在 showJoinDiagram **被进入**时生效——建模型抛在调用之前时
            // 它不会执行，故两条分支各自的 catch 内还必须显式摘圈（异常路径的收口点）。
            diagramPanel.showLoading()
            if (proj == null) {
                // T-TS-05：buildDiagramModel 移入 try——抛在 showJoinDiagram 调用**之前**时，
                // showJoinDiagram 的 finally 根本没机会执行，转圈会留在宿主层。
                try {
                    showJoinDiagram(JoinDiagramGenerator.buildDiagramModel(stmt))
                } catch (t: Throwable) {
                    // T-TS-FixA：本 catch 是这条分支的**唯一**收尾方——buildDiagramModel 是
                    // showJoinDiagram 的**实参**，实参求值期抛出时方法根本没进入，其 finally
                    // （唯一收口点）不会执行；此处不摘圈，上面 showLoading() 打开的转圈就永留宿主层，
                    // 直到下一次预览或整图卸载（对照后台富化分支：那边同样在异常时显式摘圈，
                    // 两条分支对同一异常的处理必须对称）。
                    // 线程：updateDiagramTab 由 showResult 在 EDT 上同步调用，本 catch 亦在 EDT，
                    // 直接摘即可——invokeLater 归位只服务于跨线程的后台富化分支。
                    diagramPanel.hideLoading()
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                        .warn("[T-F8] join diagram build failed (no project), loading cleared here", t)
                }
                return
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                // T-TS-05：建模型同样在 try 内——本拍一旦抛，pooled 线程终止、下面的 invokeLater
                // 永不投递，转圈就永远留在宿主层（收尾方只剩 showJoinDiagram，而它不会被调用）。
                val base = try {
                    JoinDiagramGenerator.buildDiagramModel(stmt)
                } catch (t: Throwable) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                        .warn("[T-F8] join diagram model build failed (background)", t)
                    // 回 EDT 摘转圈：作废判定同下面那拍——转圈属于「当前加载」，不能摘后一次预览的
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        if (current === result && project?.isDisposed != true) diagramPanel.hideLoading()
                    }
                    return@executeOnPooledThread
                }
                val enriched = try {
                    cn.song.mybatisplus.visualizer.ui.diagram.DiagramSchemaEnricher.enrich(proj, base)
                } catch (t: Throwable) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                        .warn("[T-Perf-09] diagram schema enrich failed, showing un-enriched model", t)
                    base
                }
                // T-DbSchemaCompletion：本次富化是否一点数据源信息都没拿到（结构相等 = 没改动）。
                // 命中就说明模型未加载（预览打开常早于数据源建连）——记下来供数据源就绪后的补刷。
                val contributed = enriched != base
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    // T-F8：作废分支**不得**摘转圈——迟到的作废回调若停加载态，会把后一次预览
                    // 刚打开的转圈一起摘掉（转圈是「当前加载」的状态，不是本次回调的状态）。
                    // 此时加载态的归属方是后一次预览：它要么自己收尾（showJoinDiagram 末尾），
                    // 要么走无 JOIN 分支（unloadJoinDiagram → diagramPanel.clear() 复位）。
                    if (current !== result || project?.isDisposed == true) return@invokeLater
                    if (!contributed) diagramEnrichRetryTarget = result
                    // T-TS-05：建图异常不逃逸到平台事件泵——逃逸只会让平台记一条无上下文的错，
                    // 而转圈已由 showJoinDiagram 的 finally 摘下；这里补一条带上下文的 warn 便于定位。
                    try {
                        showJoinDiagram(enriched)
                    } catch (t: Throwable) {
                        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                            .warn("[T-F8] join diagram build failed (post-enrich), loading cleared by finally", t)
                    }
                }
            }
        } else {
            // 无 JOIN：整图卸载（移除 diagram 标签页 + 释放原生图 + 回收虚拟外键登记）
            removeDiagramTab()
            unloadJoinDiagram()
        }
    }

    /**
     * T-Dgm-04：按设置与 Diagrams 插件可用性渲染 JOIN 图。
     *
     * 原生路径：快照登记 → UmlEditorProvider.createEditor → 组件嵌入 [diagramTabHost]
     * （缩放/布局/折叠/导出全框架托管）；Diagrams 插件缺席或框架异常（buildTabComponent 返回 null）
     * 自动降级自绘 [DiagramPanel]，与 Community 行为一致。
     *
     * T-F8：本方法末尾是加载态的**主收口点**——两条调用分支（[updateDiagramTab] 的无 project
     * 同步分支与后台富化回 EDT 分支）正常路径都经此，故「摘转圈」写在这里而不是各调用点：结构上
     * 保证转圈一定晚于渲染被摘（原生图建图/自绘测量都在本方法内同步完成），调用方也不会漏收尾。
     * T-TS-FixA：主收口点只覆盖「进得来」的情形——建模型抛在**调用之前**（实参求值期）时本方法
     * 不会被进入，故两条分支的 catch 内各自还有一层异常收口（见 [updateDiagramTab]）。
     *
     * T-TS-05：收尾改成 `try { … } finally`——原生框架建图（`buildTabComponent` 反射链/文件编辑器
     * 创建）与自绘测量都可能抛，原先的「顺序执行 + 末尾摘转圈」在异常逃逸时直接跳过摘转圈那行，
     * 用户看到的是转圈永远留在宿主层（且异常还会顺着 EDT 事件泵往上冒）。finally 保证「进过加载态
     * 就一定出」，异常照旧向上抛给调用方的带日志兜底。
     */
    private fun showJoinDiagram(model: DiagramModel) {
        try {
            // T-TS-05 测试缝：注入一个会抛的 render 复现建图异常路径（正常为 null）。
            // 为什么需要缝：结构合法的模型在测试沙盒里几乎不抛（同 DiagramPanel.renderGuarded 的
            // 单独成函数理由），异常兜底路径否则只能靠人工改线上代码才验得出来。
            val injectedRender = diagramRenderForTest
            if (injectedRender != null) {
                injectedRender(model)
                return
            }
            val nativeEnabled = PluginSettings.getInstance().state.joinDiagramNative
            // T-EmbedFkOrder F1：**必须先清旧图、再建新图**——旧图 dispose 会清空 JOIN 虚拟外键注册表
            //（JoinFkRegistry），顺序颠倒会把新图刚登记的 ON 条件整批抹掉：Database 原生图于是只剩节点
            // 没有连线（真机实证：同一面板里"一张图有连线、另一张没有"；含子查询的走 wrapper 路径不读
            // 注册表，故不受影响）。清理集中在此处（建图之前），登记之后不再有人清它。
            clearNativeDiagram()
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
        } finally {
            // T-F8：图已换入 → 退出加载态（渲染失败时 showDiagram 内部已落错误页并复位，此处幂等）。
            // T-TS-05：异常路径同样必须走到这里——否则转圈永留宿主层。
            diagramPanel.hideLoading()
        }
    }

    /**
     * T-TS-05 异常兜底测试缝：非 null 时 [showJoinDiagram] 的换图动作改走它（注入会抛的 render）。
     *
     * 只在测试里赋值；生产恒 null（多一次空判，无语义影响）。
     */
    internal var diagramRenderForTest: ((DiagramModel) -> Unit)? = null

    /**
     * 释放并移除当前原生图组件（内部 FileEditor 经 clientProperty 定位后 dispose）。
     *
     * T-EmbedFkOrder F2：本方法**不**清理 JOIN 虚拟外键注册表——它可能在建新图之前被调用（此时
     * 新图尚未登记，清了也无害），也可能在换图/卸载时被调用（此时若清会把刚登记的抹掉）。
     * 注册表的清理职责在明确语义点（三点口径）：登记前自带 clear、[unloadJoinDiagram]（无 JOIN/
     * 面板清空）与面板 [dispose]（T-PS4-14④）。
     */
    private fun clearNativeDiagram() {
        currentNativeDiagram?.let {
            // 两条原生路径的 clientProperty key 不同，各 dispose 一次（未命中为 no-op）
            NativeJoinDiagramSupport.disposeNativeEditor(it)
            cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.disposeNativeEditor(it)
            diagramTabHost.remove(it)
        }
        currentNativeDiagram = null
    }

    /** T-EmbedFkOrder F2：整图卸载（无 JOIN/面板清空）——释放原生图并回收虚拟外键登记。 */
    private fun unloadJoinDiagram() {
        clearNativeDiagram()
        diagramPanel.clear()
        cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.clearRegisteredFk()
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
                // T-F8：标签页内容是加载面板（内含 diagramTabHost）——转圈盖住整页，
                // 原生/自绘两条路径共用同一个加载面
                addTab(MpSqlVisualizerBundle.message("diagram.tab.title"), diagramLoadingHost)
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
        // O-85：列信息表恢复 Ctrl+A 全选（IDEA 全局 Ctrl+A 被「选中文本」语义抢占、
        // JBTable 默认 selectAll 绑定在此场景不生效——显式绑 InputMap/ActionMap）。
        val selectAllKey = javax.swing.KeyStroke.getKeyStroke("control pressed A")
        table.setInputMap(javax.swing.JComponent.WHEN_FOCUSED, javax.swing.InputMap().apply {
            put(selectAllKey, "mpsv.selectAll")
        })
        table.actionMap.put("mpsv.selectAll", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                table.selectAll()
            }
        })
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
    internal fun refreshCompletionContext() {
        val ctx = buildCompletionContext()
        editor?.putUserData(SqlCompletionContext.KEY, ctx)
        psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
        editor?.document?.let { SqlCompletionContributor.registerContext(it, ctx) }
        // T-InsertColumnInlay O-44b：预览语句类型载体随上下文一同刷新（同一收口，两处渲染路径都过这里）——
        // INSERT 目标列 inlay 的语句类型闸门据此判定；非 XML 预览（xmlModel 为 null）写 null 即清除，
        // 防复用编辑器上残留上一次预览的语句类型
        attachPreviewStatementKind()
        // T-DbSchemaCompletion：预览实际输入面是原生控制台（Ultimate），它的上下文只在
        // onNativeEditorReady 挂过一次——不在此同步，schema 预热完成后控制台仍读旧上下文，
        // 库里独有的列照旧补不出来（且控制台端编辑器 userData 优先级高于 document 兜底）。
        nativeConsoleEditor?.let { console ->
            console.putUserData(SqlCompletionContext.KEY, ctx)
            SqlCompletionContributor.registerContext(console.document, ctx)
        }
    }

    /**
     * T-InsertColumnInlay O-44b：把当前预览的语句类型写到预览编辑器（Editor + PSI 文件双载体）。
     *
     * 这是 `xmlModel.kind` 的**首个读点**（此前零消费者）——INSERT 目标列 inlay 的语句类型闸门
     * 由 provider 读该载体判定，`kind != INSERT` 即零提示。取 `xmlModel` 而非 `SqlTypeClassifier`：
     * 语句类型是解析期事实（`XmlMapperParser.kindOf`），非 XML 预览链无此模型（写 null = 不提示，
     * 见计划 §二 非目标与 provider KDoc 的边界登记）。
     *
     * 写 null 而非「不写」：预览编辑器在预览切换间被复用（`showSql` 的 existing 分支），
     * 残留上一次预览的语句类型会让复用编辑器继续按旧类型产提示。
     */
    internal fun attachPreviewStatementKind(target: Editor? = editor) {
        val kind = current?.xmlModel?.kind
        target?.let { PreviewInlayScope.attach(it, kind) }
        psiFile?.let { PreviewInlayScope.attach(it, kind) }
    }

    /**
     * T-DbSchemaCompletion：补全 schema 预热涉及的表——SQL 里出现的表（含 JOIN）+ 预览主表。
     *
     * 表名取自 [TableExtractor]（纯词法，无 PSI）；解析不到时只剩主表兜底。带别名/括号的误提取
     * 会按「确认没有此表」记入负缓存（TTL 30s，DDL/提交后随 MetadataInvalidator 一起失效），
     * 代价只是 30s 内同表不再重扫、少几个补全候选，无害（「查不了」的情形不写负缓存，见
     * [DatabaseSchemaResolver.resolveFromSources] 的 scanBailed 分支）。
     */
    private fun completionTables(result: PreviewResult?): List<String> {
        if (result == null) return emptyList()
        return (cn.song.mybatisplus.visualizer.sql.extract.TableExtractor.extract(result.sql) +
            listOfNotNull(result.tableName))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * T-DbSchemaCompletion：后台预热「本文档涉及的表」的数据源 schema，完成后刷新补全上下文。
     *
     * 快照判据与 T-Perf-09 的 JOIN 富化同款（`current === result` 才应用）——新预览打开即作废迟到结果。
     * 数据源未连接时 `getModel()` 为 null → 解析 0 张且不留负缓存，控制台就绪（数据源已连）时会经
     * [onNativeEditorReady] 再触发一次，覆盖「预览早于建连」的窗口（真机日志实证该窗口存在）。
     *
     * 补全热路径不做任何遍历：本方法只在后台把结果填进 [DatabaseSchemaResolver] 缓存，
     * 之后 [buildCompletionContext] 只读缓存（T-Perf-09 红线）。
     */
    internal fun prefetchSchemaForCompletion(result: PreviewResult) {
        val proj = project ?: return
        val tables = completionTables(result)
        if (tables.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = runCatching {
                cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.prefetchTables(proj, tables)
            }.getOrDefault(0)
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .info("[T-DbSchemaCompletion] schema 预热: tables=${tables.size}, resolved=$resolved, list=$tables")
            if (resolved == 0) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (current !== result || project?.isDisposed == true) return@invokeLater
                refreshCompletionContext()
            }
        }
    }

    /** 由当前预览结果构造补全上下文（表名/列名/注释；M16.2 抽为复用函数，Editor 与 PSI 同源）。 */
    internal fun buildCompletionContext(): SqlCompletionContext = SqlCompletionContext(
        tableName = current?.tableName,
        columns = current?.columns ?: emptyList(),
        tableAlias = null,
        columnComments = current?.columnComments ?: emptyMap(),
        tableComment = current?.tableComment,
        // T-M27.4：联表候选表（XML 预览经 resolveXml 注入；预览补全表名候选）
        candidateTables = current?.candidateTables.orEmpty(),
        // T-DbSchemaCompletion：数据源 schema 列（库里存在而实体类没有的列也是候选）。
        // 只读预热缓存（columnsCached 不触发遍历）；未预热/无数据源时空 Map = 改动前语义。
        schemaColumns = cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
            .columnsCached(project, completionTables(current)),
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
    /**
     * R12 T-PaginationSim：模型级重渲染后把分页 LIMIT 行带回主 SQL 尾部。
     *
     * 面板勾选/参数变更走 XmlPreviewModel.renderSql / SqlGenerator 重建——模型层 SQL 是分页后处理
     * **之前**的形态（服务端 applyPaginationShared 只作用于整条 PreviewResult），LIMIT 行会在此丢失
     * （20261008 dev-bak 验收实测：分页参数行与 COUNT 段都在、主 SQL 尾部无 LIMIT）。服务端把 LIMIT
     * 行存进 [PreviewResult.paginationLimitLine]，此处原样带回；主 SQL 已含 LIMIT（手写语句）时跳过。
     */
    private fun withPaginationLimitLine(baseSql: String): String =
        appendPaginationLimitLine(baseSql, current?.paginationLimitLine)

    internal fun renderSql() {        // T-WSC-01：写安全自愈闸——renderSql 是面板全部文档写入的唯一上游（showSql 三条分支
        // 都写文档：nativeDoc / 自建 editor / createEditor），而写入合法性取决于「这次 EDT 活动
        // 是否 write-safe」（见 DocWriteContext KDoc）。非 write-safe（如 invokeLater(any) 排出的
        // 活动、Timer 拍）时重投递到 nonModal 的下一拍；renderSql 幂等重算，不会写出过期文本
        // ——这正是闸门放在 renderSql 而非 showSql(text) 的原因（后者收的是调用时刻的快照）。
        if (!DocWriteContext.isWriteAllowedNow()) {
            DocWriteContext.invokeLaterWriteSafe(this, "SqlPreviewPanel.renderSql") { renderSql() }
            return
        }
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
                // O-78：foreach 值按本帧新鲜映射清单播种（写安全闸重投递时与映射刷新同帧延迟，
                // 值状态→渲染单向流动，不再有「渲染后同步读键清单」的错位窗口）
                syncForEachItemValues(xml)
                xmlDollarSubstitutions = rendered.dollarSubstitutions
                // R30 探镜：本轮条件判定结果刷新条件行尾三态标注（只读观测，不参与渲染文本）
                syncXmlConditionOutcomeLabels(xml, rendered)
                // T-M25.5：bind 自动求值结果并入参数回填（用户手动输入优先，清空则恢复自动值）
                rendered.bindValues.forEach { (name, value) ->
                    if (paramValues[name].isNullOrEmpty()) paramValues[name] = value
                }
                val base = withPaginationLimitLine(rendered.sql)
                val sql = if (compactMode) base.replace(WHITESPACE_REGEX, " ").trim() else base
                val sources = rendered.mappings.map { it.property }
                // T-M29.5：参数回填引号按 mapper 方法参数 Java 类型判定（XmlParamTypeResolver 注入
                // model.paramQuotes：DTO 字段 LocalDate→引号/Integer→裸渲染，贴近真实绑定效果）；
                // 未命中（Map/无类型信息）→ 兜底引号（T-M29.4：日期/字符串列安全，数值列隐式转换等价）
                // O-81 T-XmlListParamSplit：提示组装抽为 xmlBackfillHints——签名集合参数
                // （List<String> 等）无条件按逗号多值渲染 + 引号按元素类别派生，
                // 形态探测（detectInClauseParams）降级为无签名信息参数的兜底通道
                val inListIndices = detectInClauseParams(sql)
                val xmlHints = xmlBackfillHints(xml, sources, inListIndices, xmlMappings)
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
                showSql(backfillParamsBySource(applyNamedParams(withPaginationLimitLine(generated.sql)), generated.unresolvedParams, generated.paramHints))
            }
            else -> {
                // 旧文本路径（无可勾选动态条件，如批量操作）
                val base = if (compactMode) result.compactSql else result.sql
                // JDBC 命名参数按名回填（先），再对残留 `?` 按序回填
                // T-JpaParamOrder F3：JPA 占位符名序非空时按名回填（同名占位复用同值，
                // 与 setParameter 书写顺序解耦）；hints 在服务端已与该 sources 序平行
                val sources = result.placeholderParamNames.ifEmpty { result.unresolvedParams }
                showSql(backfillParamsBySource(applyNamedParams(base), sources, result.paramHints))
            }
        }
        // T-SelToggle：勾选态一切变更均以 renderSql 收尾——切换按钮文案在此单点同步
        syncToggleSelectLabels()
        // O-95 S3：绑定数据源指示行同帧刷新（控制台 attach/reuse/clear 链的既有渲染路径即刷新通道）
        refreshBindStateRow()
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
    /**
     * T-PreviewFast P6：显示模式变换 **单槽缓存**——同一 (SQL, 模式, 压缩态, 格式化设置) 组合
     * 只做一次 SqlFormatter 变换。命中场景：挂载后回填渲染与紧随其后的参数/勾选重渲染
     * （内容未变时反复走格式化管线是纯浪费，大 SQL 上尤甚）。
     */
    private var formatCacheKey: String? = null
    private var formatCacheValue: String? = null

    private fun applyDisplayMode(sql: String): String {
        if (compactMode) return sql
        val key = "$displayMode|$compactMode|${formatSqlSetting()}|$sql"
        formatCacheValue?.let { cached -> if (formatCacheKey == key) return cached }
        val out = when (displayMode) {
            PreviewDisplayMode.NORMAL -> if (formatSqlSetting()) SqlFormatter.format(sql) else sql
            PreviewDisplayMode.FORMAT -> SqlFormatter.format(sql)
            PreviewDisplayMode.KEYWORD_WRAP -> SqlFormatter.formatClauseLine(sql)
            PreviewDisplayMode.VERTICAL -> SqlFormatter.formatVertical(sql, verticalPairAnnotationSetting())
        }
        formatCacheKey = key
        formatCacheValue = out
        return out
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
        val before = lastRenderedSql
        setDisplayMode(PreviewDisplayMode.FORMAT)
        notifyIfFormatUnchanged(before)
    }

    /**
     * T-DdlLogPreview S3（20261006 第三轮走查，D76 用户裁决「可以给」）：格式化后文本**零变化**时
     * 给一条轻提示。
     *
     * 立项动因：`DROP TABLE IF EXISTS x ;` 这类**本就单行**（无换行点）的语句点「格式化」看不到任何
     * 变化，用户读成「格式化坏了」——实测即由此报障。判据 = 渲染文本前后一致（[lastRenderedSql]
     * 是渲染管线唯一产物，含格式化结果）；同文本往返说明「已是最可读形态」，明确告知即可。
     * 与既有轻提示同规格：[NOTIFICATION_GROUP_ID] + INFORMATION，非模态；单测环境静默（不产生通知）。
     */
    private fun notifyIfFormatUnchanged(before: String?) {
        val after = lastRenderedSql
        if (before == null || after == null || before != after) return
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("panel.format"),
                MpSqlVisualizerBundle.message("panel.format.alreadyFormatted"),
                NotificationType.INFORMATION,
            ).notify(project)
        }
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
        xmlConditionOutcomeLabels.clear()
        conditionValueFields.clear()
        batchCheckBoxes.clear()
        xmlForEachNullBoxes.clear()
        xmlForEachValueFields.clear()
        xmlForEachCounts.clear()
        xmlForEachValues.clear()
        xmlForEachNulls.clear()
        xmlErrors = emptyList()
        xmlDollarSubstitutions = 0
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
        metaLineHead = ""
        metaLineDigest = ""
        metaPerfSegments = emptyList()
        lastMetaBudget = -1
        // T-SourceJumpFunnel P6：tooltip 基线同步复位（行内提示态据此拼接，不复用上一份预览的残留）
        metaTooltipBase = ""
        metaLabel.toolTipText = null
        // T-StackHoverUi：调用栈悬停弹层随清空复位（旧弹层即关，下次预览按新数据重弹）
        stackHover.updateDigest(null)
        // F-1：清除图集 + 恢复纯 SQL 视图（整图卸载含虚拟外键回收）
        removeDiagramTab()
        unloadJoinDiagram()
        // M36：清空结果面板 + 摘除内嵌控制台
        resultPanel.clear()
        detachConsole()
        // R33：作废在途结构化 EXPLAIN 回调 + 清掉结构化视图残留（consoleHost 子层换空，
        // 下次预览重新走 prepareConsole 正常换入控制台；editorHolder 清空在本函数开头已做）
        explainStructuredGeneration++
        consoleHost.removeAll()
        // M36.4：重置原生编辑器文档引用
        nativeEditorDoc = null
        // T-AutoFold F5：控制台编辑器引用随清空复位
        // T-PreviewLineCommentDash O-134：复位先减计数（detachConsole 已覆盖常规路径，此处兜底直清分支）
        nativeConsoleEditor?.let(EditorCommentShortcuts::uninstall)
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
    internal fun showSql(sql: String) {
        // R33：新预览作废在途结构化 EXPLAIN 回调（慢计划不覆盖用户已切换的新预览）
        explainStructuredGeneration++
        // T-RefreshDbSteal：三分支走查埋点——真机故障期症状②（左参数不再同步右侧 SQL）的
        // 丢失路径必经此方法（三个静默分支之一吞掉了渲染），带分支标签定位
        val fLogger = com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        // T-MpjFill：追加 fill 附加查询段（MPJ 1.5.1+）——在主 SQL 之后渲染 fill 附加 SELECT
        val withFill = appendFillSection(sql)
        // R12 T-PaginationSim：追加分页 COUNT 包裹段（D1=A 预览附第二条语句，对齐捕获侧 COUNT 独立条目口径）
        val withFillAndCount = appendCountSection(withFill)
        // T-PreviewDisplayMode：显示模式在渲染管线内统一套用（唯一收敛点——XML/批量/模型/旧文本
        // 四条重建路径都经此处，故参数输入/勾选/foreach 变更后形态保持）
        val text = applyDisplayMode(withFillAndCount)
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
                    // T-PreviewFast P4：程序性写入开闸——否则本次写入会触发执行文件的 1.5s 防抖
                    // 自动落盘（内容刚由本写入落盘，再写一遍纯属自己与自己抢）
                    cn.song.mybatisplus.visualizer.execute.ExecDocAutosaveGate.suppressDuring {
                        WriteCommandAction.runWriteCommandAction(project) { nativeDoc.setText(text) }
                        // T-PS4-12 定案①：同临界区落盘（照 DataSourceSelector.writeVfTextViaDocument
                        // 手法，isDocumentUnsaved → saveDocument）——闸门前提「内容由本写入落盘」
                        // 在此成真，formatted 文本不再滞留 modified-unsaved（第七轮审计发现本体）。
                        //
                        // 「磁盘存原文还是 formatted」核实结论（T-PreviewConsoleFmt F1/F2 +
                        // T-PreviewFast P1/P3 定例）：挂载后磁盘跟随 document 是既定语义——
                        //  1) ConsoleFmt F1 定例 = 控制台挂载后渲染链把 formatted 回填进控制台
                        //     文档，文档内容（=控制台所见）即权威；
                        //  2) T-PreviewFast P1 `execFileContentMatches` 定例 = 有已加载 document
                        //     时以 document 文本为准（注释原文「也是落盘会写入的内容」）；
                        //  3) 「磁盘存预览原文」仅是预热路径的写入，且只发生在文档未挂编辑器时
                        //     （ConsoleFmt F2 + PreviewFast P3：文档打开时预热跳过内容刷新）；
                        //  4) 落盘 formatted 不改变执行语义：执行链执行前同步的是控制台文档内容，
                        //     用户手改兜底（F5）落盘的同样是 document 内容。
                        // 故本写入落盘 formatted 与既有定例一致；孤儿文档（getFile null，测试缝
                        // createDocument 形态）无盘可落，自然跳过。
                        val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        val vf = fdm.getFile(nativeDoc)
                        if (vf != null && fdm.isDocumentUnsaved(nativeDoc)) {
                            fdm.saveDocument(nativeDoc)
                        }
                    }
                } finally {
                    suppressAutoPopup = false
                }
            }
            // T-PrewarmLateRecover F1：记录插件最后一次写入的文本——迟到产物换装据此判「用户没动过」
            lastRenderedSql = text
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
            // T-PrewarmLateRecover F1：记录插件最后一次写入的文本（同上）
            lastRenderedSql = text
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
        // T-PrewarmLateRecover F1：新建支链同样记录（装配后文档文本即 text）
        lastRenderedSql = text
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

    /**
     * R12 T-PaginationSim：追加分页 COUNT 包裹段（D1=A 预览附第二条语句）。
     *
     * 在主 SQL（含 fill 段）之后以注释头并列渲染 COUNT 包裹 SQL（对齐捕获侧 MP_PAGINATION_COUNT
     * 独立条目口径——运行时 COUNT 是分页查询之外的独立语句）。无 COUNT 产出时原样返回。
     *
     * T-DevAcceptFix ③（20261006 验收）：两条语句各自以 `;` 收尾——原实现主 SQL 尾直拼注释头，
     * 复制到控制台执行时分页语句与 COUNT 语句粘连（用户实验收实测）；已带分号不重复追加。
     */
    private fun appendCountSection(sql: String): String {
        val result = current ?: return sql
        val countSql = result.countSql ?: return sql
        val terminatedMain = sql.trimEnd().let { if (it.endsWith(";")) it else "$it;" }
        val terminatedCount = countSql.trimEnd().let { if (it.endsWith(";")) it else "$it;" }
        return StringBuilder(terminatedMain)
            .append("\n\n-- ").append(MpSqlVisualizerBundle.message("panel.pagination.count.section")).append("\n")
            .append(terminatedCount)
            .append("\n")
            .toString()
    }

    /** 首次创建：原生 SQL FileType PSI → 文档 → 可编辑编辑器，附补全上下文（Editor + PSI 双载体，M16.2）。 */
    internal var bindStateNameProvider: (() -> String?)? = null

    internal val checkBoxFont: Font by lazy { JCheckBox().font }

    /** T-M26.3：复选框标签截断——test 表达式等长文本超上限时中段省略显示（tooltip 原文由调用方处理）。 */
    internal fun updateMeta(result: PreviewResult) {
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
        // T-SourceJumpFunnel P2-2：调用栈快照并入 meta 提示行（截断展示；T-StackHoverUi 起
        // 完整链归悬停弹层）。
        // 文案组装抽为纯函数 [buildStackDigestInlineLine]（同文件顶层）——§3.3 验收项
        // 单测直断截断长度/截断标记，无需真机目视。
        val stackInline = buildStackDigestInlineLine(result.stackDigest)
        // T-SqlPerf：性能建议文案单独一行（HTML 换行）。
        // T-PreviewTopOverlap F3：原文分段缓存，label 文本按宽度预算重拼（applyMetaTruncation）
        // T-SourceJumpFunnel P5 修复：调用栈摘要并入**可见行**——验收项前半句「meta 行显示调用栈」
        // 在可见行上成立。该行受 F3 宽度预算约束，故按**主体 / 摘要**两段缓存（见 metaLineHead /
        // metaLineDigest）：预算不足时先截摘要、主体仅在自身超预算时才中段省略（复核 20260920 修正
        // ——把摘要直接拼进单段文本会让 F3 的中段省略在窄窗下连主体一起挤掉，只剩「…」+ 栈底帧，
        // 优先级倒挂）。
        // T-StackHoverUi：完整调用链不再进 tooltip（旧实现把截断摘要+全文重复拼进 tooltip，
        // 超长单行折成文本墙）——改由 [stackHover] 悬停弹层承接（调试器 Frames 风格）。
        metaLineHead = meta + warningLine
        metaLineDigest = stackInline
        metaLineText = metaLineHead + metaLineDigest
        metaPerfSegments = buildPerfSegments(result)
        metaTooltipBase = meta + warningLine +
            metaPerfSegments.joinToString("；") { it.text }
        metaLabel.toolTipText = metaTooltipBase
        stackHover.updateDigest(result.stackDigest)
        metaLabel.foreground = if (currentWarning != null) {
            // T-DarkTheme：IDE 标准错误前景命名色（亮暗主题自适应；暗色下非亮红不刺眼）
            JBColor.namedColor("ErrorForeground", JBColor.RED)
        } else {
            JBUI.CurrentTheme.Label.foreground()
        }
        // T-M25.3：错误复制按钮随警示可见
        errorCopyButton?.isVisible = currentWarning != null
        // T-PreviewUx-Pack #2：渲染警示横幅（三要素：哪段没还原=渲染产物，为什么=warning 原文，
        // 能做什么=指引）；警示存在即显示，恢复即清除（降级链横幅 DEGRADED 不受本清除影响）
        if (currentWarning != null) {
            degradationBanner.show(
                DegradationBanner.Kind.RENDER_WARNING,
                MpSqlVisualizerBundle.message("panel.banner.renderFailed", currentWarning ?: ""),
            )
        } else {
            degradationBanner.clear(DegradationBanner.Kind.RENDER_WARNING)
        }
        // T-DollarInjectMark：`${}` 直替信息提示（最低优先——警示/降级挂起时让位，
        // 清除后下次 updateMeta 回位；✕ 关闭后会话级记忆不再弹）。只加元数据不改渲染文本。
        if (currentWarning == null && result.xmlModel != null && xmlDollarSubstitutions > 0 &&
            !dollarMarkDismissedSession
        ) {
            degradationBanner.show(
                DegradationBanner.Kind.DOLLAR_INJECT,
                MpSqlVisualizerBundle.message("panel.banner.dollarInject", DOLLAR_TOKEN, xmlDollarSubstitutions),
            )
        } else {
            degradationBanner.clear(DegradationBanner.Kind.DOLLAR_INJECT)
        }
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
    internal fun applyMetaTruncation() {
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
        val widthOf = { s: String -> metrics.stringWidth(s) }
        val line1 = metaLineWithinBudget(metaLineText, metaLineHead, metaLineDigest, widthOf, budget)
        metaLabel.text = if (metaPerfSegments.isEmpty()) {
            line1
        } else {
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
        // O-88 复验修复：dispose 链捕获折叠态——懒惰回写（buildSchemeRow 开头）只在同实例触发参数区
        // 重建时执行，真机「收起→取消→换语句」走 DialogWrapper.dispose→Disposer 级联释放面板，
        // 全程无 buildSchemeRow 调用，静态字段从未写 true。面板自身是 Disposable 且经
        // Disposer.register(宿主 disposable, this) 挂树（弹窗见 SqlPreviewDialog，工具窗同惯例），
        // 宿主任何关闭路径（取消/Alt+F4/父级释放）级联触发本方法——在此读当前折叠件视觉态回写
        // 静态字段（真值语义=取反，与懒惰捕获行一致）。懒惰捕获行保留，覆盖刷新等重建路径。
        presetFold?.let { presetSectionCollapsed = !it.isExpanded() }
        // T-RefreshDbSteal：面板废弃——移除 rehost 监听（共享控制台可能已被新面板接管，
        // 本面板的监听不得残活抢组件）；embedConsole 对 null 安全
        runCatching { detachConsole() }
        // T-AutoFold F5：面板废弃——控制台编辑器引用一并清空（防 EditorFactory 泄漏告警）
        // T-PreviewLineCommentDash O-134：面板销毁减计数（detachConsole 已覆盖常规路径，此处兜底直清分支）
        nativeConsoleEditor?.let(EditorCommentShortcuts::uninstall)
        nativeConsoleEditor = null
        // T-M36.6-03：面板销毁——晚到的预热线程产物作废（预热线程自查后删除临时文件）
        previewDisposed = true
        // T-PerfSweep6 T-PS6-04③（性能报告发现 4）：面板 dispose 补一次同款临时文件回收。
        // 此前回收实际只由 removeNotify → releaseEditor 触发，而三种宿主（弹窗 / 工具窗 / 嵌入控制台）
        // 下 dispose 与 removeNotify 的先后未穷尽——dispose 先到时残留文件无人回收。本方法幂等，
        // 两条路径都走到也无副作用（集合已清空即零动作）。
        recoverPreviewTempFiles()
        // T-StabilityTail：解除预热取消门在项目 earlyDisposable 树上的登记——面板先于项目销毁时
        // 不留指向本面板的引用（项目先销毁时该 gate 已被平台回收，Disposer 对已销毁件幂等）
        prewarmLifecycleGate?.let { runCatching { Disposer.dispose(it) } }
        prewarmLifecycleGate = null
        // T-Perf-04：防抖/历史 Timer 随面板停止（防晚到 flush 触碰已销毁面板状态）
        renderDebounceTimer.stop()
        pendingRender = null
        historyAlarm.stop()
        // T-Perf-16：释放 DataSourceSelector 单例滞留引用（弹窗关闭此前不走 clear 按钮路径——
        // prepared/shared/pendingOnConsole 强引用已关闭面板与原生控制台，堆转储滞留整棵面板树）
        SqlExecutor.releaseForPanel()
        // T-PS4-14④：面板 dispose 是 JOIN 虚拟外键注册表的最后语义回收点（T-EmbedFkOrder
        // 「显式语义点回收」三点口径：登记前 registerJoinForeignKeys 自带 clear + 无 JOIN 路径
        // unloadJoinDiagram + 面板 dispose）——最后一张 JOIN 图建图的登记此前驻留到下次建图，
        // 残留期 JoinVirtualFkProvider EP 会给用户自开的 Database 图补出旧连线。runCatching
        // 照邻居清理件风格（释放失败不阻断 dispose 链）
        runCatching {
            cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.clearRegisteredFk()
        }
        // messageBus 连接经 connect(this) 绑定本 Disposable，Disposer.dispose 时自动取消订阅
        Disposer.dispose(this)
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    internal fun editorForTest(): Editor? = editor

    /** T-PSG-04 测试缝：最近一次回填的「位点数 vs 参数条数」对账标注（相等为 null）。 */
    internal fun placeholderReconciliationForTest(): String? = placeholderReconciliation

    /** T-PreviewConsoleFmt 测试缝：模拟控制台文档已就绪（nativeDoc 指向该文档）。 */
    internal fun markNativeDocForTest(doc: com.intellij.openapi.editor.Document) {
        nativeEditorDoc = doc
    }

    /** T-PreviewConsoleFmt 测试缝：挂载后回填渲染的等价入口。 */
    internal fun renderSqlForTest() = renderSql()
    /** T-AutoFold F5：登记的原生控制台编辑器引用（支链①接入断言用）。 */
    internal fun nativeConsoleEditorForTest(): Editor? = nativeConsoleEditor
    internal fun psiFileForTest(): PsiFile? = psiFile

    /**
     * T-PreviewDocLock2 F3 回归守护：预热载体（异步完成态）——测试据此有界等待预热出结果
     * （headless 无 EDT 泵，预热跑在池线程），再驱动真实消费链断言分支走向。
     */
    internal fun prewarmAwaitForTest(): PrewarmAwait<Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>>? =
        prewarmAwait

    /**
     * T-PreviewDocLock2 F3 回归守护：预热产物字段（[publishPrewarmProduct] 写入、消费即置 null）。
     * 断言点：①预热完成后字段与 await 交付值**同实例**（发布侧不变式）；②消费后字段被清空（一次性语义）。
     */
    internal fun prewarmedTempFileForTest(): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? =
        prewarmedTempFile

    /** T-PreviewDocLock2 F3 回归守护：释放编辑器（**不**触发 [clear] 的重预热）——「产物已被消费」降级分支断言用。 */
    internal fun releaseEditorForTest() = releaseEditor()

    /**
     * T-PerfSweep6 T-PS6-04 测试缝：对给定目录执行一次目录级兜底清扫（生产入口在
     * [projectPreviewFile] 首次解析预热目录时）。测试用临时目录即可直测「按年龄/条数回收」
     * 与「非本插件命名零删除」两条判据。
     */
    internal fun sweepStalePreviewFilesForTest(dir: java.io.File) = sweepStalePreviewFiles(dir)
    /** T-PrewarmLateRecover F1：当前是否处于「降级文档」态（换装判据里的身份字段）。 */
    internal fun degradedDocumentForTest(): com.intellij.openapi.editor.Document? = degradedPreviewDocument

    /**
     * T-ErrorLocateUsability T-ELU-01（O-49）：错误定位动作的**显隐判据**缝——
     * 锚点可解析 → 非 null（按钮出现）；不可解析 → null（按钮不出现）。
     * 这是本任务修复点的直接可断言面（改前恒非 null）。
     */
    internal fun errorLocateActionForTest(): (() -> Unit)? = errorLocateAction()

    /** T-ELU-01：富错误回调缝（端到端断言「锚点不可解析 → 定位按钮不出现」）。 */
    internal fun showErrorRichWithLocateForTest(info: cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo) =
        showErrorRichWithLocate(info)

    /** T-PrewarmLateRecover F1：插件最后一次写入编辑器文档的文本（换装判据里的「用户没动过」）。 */
    internal fun lastRenderedSqlForTest(): String? = lastRenderedSql

    /**
     * T-PrewarmLateRecover F1：驱动迟到产物通道——与「预热线程发布时发现消费侧已放弃
     * （`PrewarmAwait.complete` 返回 false）」走同一条代码路径（[tryUpgradeToLateProduct]）。
     */
    internal fun onLatePrewarmProductForTest(product: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>) =
        tryUpgradeToLateProduct(product)

    /** T-PrewarmLateRecover F2：横幅「重试」动作入口（等价于点击横幅上的动作）。 */
    internal fun retryPrewarmForTest() = retryPrewarm()

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
    /** T-UxOnboard T-UX-02：预览编辑器空态提示文案（placeholder 接线断言用）。 */
    internal fun previewEmptyHintForTest(): String = previewEmptyHint
    /** M36.5.39-A1：最近一次嵌入 console 的身份 id（sameAsPrev 探针断言用）。 */
    internal fun lastConsoleEmbeddedIdForTest(): Int? = lastConsoleEmbeddedId
    /** T-RefreshDbSteal F3：摘除控制台 + 移除 rehost 监听（生命周期断言用）。 */
    internal fun detachConsoleForTest() = detachConsole()
    /** T-RefreshDbSteal F4：所有权判定（卫队断言用，见 RefreshDbStealTest）。 */
    internal fun consoleAttachedToMeForTest(): Boolean = consoleAttachedToMe()
    /** T-RollbackSql：执行状态面板（executeAdhocSql 结果反馈断言用）。 */
    internal fun resultPanelForTest(): SqlResultPanel = resultPanel

    /**
     * T-F8：JOIN 图加载态接线探针（测试用）——转发到 [DiagramPanel.loadingProbeForTest]。
     *
     * 接线用例据此断「进入加载态 → 渲染 → 退出加载态」的时序（时序为何必须如此见
     * [updateDiagramTab] 与 [showJoinDiagram] 的 KDoc）。
     */
    internal fun diagramLoadingProbeForTest(probe: (Boolean) -> Unit) {
        diagramPanel.loadingProbeForTest = probe
    }

    /** T-F8：JOIN 图面板（「停转圈时图已渲染」的断言入口）。 */
    internal fun diagramPanelForTest(): DiagramPanel = diagramPanel

    /** T-F8：当前是否走原生图路径（「停转圈时图已换入」的另一分支——原生路径不经过 [diagramPanel]）。 */
    internal fun hasNativeDiagramForTest(): Boolean = currentNativeDiagram != null

    /**
     * T-TS-05：JOIN 图标签页的加载面（测试断言用）——标签页内容必须**是它**。
     *
     * 与 [diagramPanelForTest] 的 `isLoadingForTest` 互补：后者只断「加载态为真」，把标签页内容改回
     * [diagramTabHost]（加载面不在层级里）也照样为真；真机默认原生图路径下 [diagramPanel] 被
     * `removeAll()` 摘出层级，转圈根本画不出来。故需按对象身份断结构。
     */
    internal fun diagramLoadingHostForTest(): JBLoadingPanel = diagramLoadingHost

    /** T-TS-05：内容区标签页容器（测试断言用；未切到标签页模式时为 null）。 */
    internal fun contentTabPaneForTest(): javax.swing.JTabbedPane? = contentTabPane

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

    /**
     * T-JpaParamOrder F3：复制可执行 SQL 的回填 sources——JPA 占位符名序非空时优先
     * （按名回填口径），否则回落 [unresolvedParams]，与面板渲染（renderSql else 分支）同口径。
     */
    fun backfillSources(): List<String> {
        val cur = current ?: return emptyList()
        return cur.placeholderParamNames.ifEmpty { cur.unresolvedParams }
    }

    /** O-4：当前参数值快照（source → 用户输入值）。 */
    fun paramValuesSnapshot(): Map<String, String> = paramValues.toMap()

    /**
     * T-InjectionGate T-IG-01：当前预览语句是否含 `${}` 原文拼接段（[PreviewResult.hasDollarRawSegments]
     * 面板侧读口——复制可执行 SQL 的产物标记注释据此追加）。
     */
    fun hasDollarRawSegments(): Boolean = current?.hasDollarRawSegments == true

    /** F-8：当前预览的表名（可 null）。 */
    fun currentTableName(): String? = current?.tableName

    /** F-8：当前预览的方言名称（可 null）。 */
    fun currentDialect(): String? = current?.previewModel?.dialect?.name
    internal fun paramFieldForTest(index: Int): JBTextField? = paramFields.getOrNull(index)
    /** T-M24-04：元信息标签文本（警告断言用）。 */
    internal fun metaLabelForTest(): String = metaLabel.text

    /**
     * T-SourceJumpFunnel §3.3：元信息标签 tooltip（「悬停见完整链」断言用）。
     *
     * headless 下无法产生真实鼠标悬停，但 Swing 悬停展示的正是本属性文本，故断言它即锁定
     * 悬停内容契约（真实悬停动作本身不在自动化范围）。
     */
    internal fun metaTooltipForTest(): String? = metaLabel.toolTipText

    /** T-StackHoverUi：当前喂给悬停弹层的栈快照（接线断言用——tooltip 不再承载栈文本）。 */
    internal fun stackDigestForTest(): String? = stackHover.currentDigestForTest()
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

    /**
     * N4：构造 step 的注入口——真实三项动作都会弹模态（单测禁弹），故用无副作用的替身项断言
     * 「命中真实项时**不同步**执行动作」这条新契约。
     */
    internal fun moreActionsStepWithForTest(items: List<Pair<String, () -> Unit>>): com.intellij.openapi.ui.popup.util.BaseListPopupStep<String> =
        moreActionsStep(items)

    /** F3：宽度预算截断手动触发（headless 无 componentResized，测试直调）。 */
    internal fun applyMetaTruncationForTest() = applyMetaTruncation()

    /**
     * T-M19.4-01：第 index 个普通参数行的标签（JBLabel）——布局断言用
     * （宽度自适应/tooltip 全名）。按 paramPanel 中「含 JBLabel 的 JBPanel 行」定位，
     * 与 paramFields 的顺序一致（普通参数行按 source 顺序追加）。
     * R22 T-04：方案行同为「含 JBLabel 的 JBPanel」且插在头部，按 [SCHEME_ROW_NAME]
     * 排除——访问器契约是「普通参数行」，不含方案行（否则序号错位、断言取到方案行标签）。
     * O-95 S3：绑定指示行同形状（含 JBLabel 的 JBPanel），按 BIND_STATE_ROW_NAME 同口径排除。
     */
    internal fun paramRowLabelForTest(index: Int): JBLabel? = paramPanel.components
        .filterIsInstance<JBPanel<*>>()
        .filter {
            row -> row.name != SCHEME_ROW_NAME && row.name != BIND_STATE_ROW_NAME && row.components.any { it is JBLabel }
        }
        .getOrNull(index)
        ?.components?.filterIsInstance<JBLabel>()?.firstOrNull()

    /** T-HibDualCapture：第 index 个普通参数框的 tooltip（`#source = 值`），供长值兜底断言。 */
    internal fun paramFieldTooltipForTest(index: Int): String? = paramFields.getOrNull(index)?.toolTipText
    /** T-HibDualCapture：第 index 个普通参数框的光标位置（应钉 0，视口停在值头部）。 */
    internal fun paramFieldCaretForTest(index: Int): Int = paramFields.getOrNull(index)?.caretPosition ?: -1
    /** T-PT-02：第 index 个普通参数框的占位提示（emptyText，按类型类别）。 */
    internal fun paramEmptyTextForTest(index: Int): String? = paramFields.getOrNull(index)?.emptyText?.text
    /** T-PT-02：第 index 个普通参数行的校验警示文案（无警示返回 null；不阻塞渲染）。 */
    internal fun paramWarningForTest(index: Int): String? =
        paramWarnLabels.getOrNull(index)?.takeIf { it.isVisible }?.toolTipText
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

    /** T-PreviewBgFit 测试缝：左侧参数滚动区（背景/不透明断言用）。 */
    internal fun paramScrollForTest(): JBScrollPane = paramScroll

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

    /** T-PreviewUx-Pack #2：诚实降级横幅（显隐/文案/类别断言用）。 */
    internal fun degradationBannerForTest(): DegradationBanner = degradationBanner
    /** P-05：当前 paramValues 快照（断言用）。 */
    internal fun paramValuesForTest(): Map<String, String> = paramValues.toMap()
    /** P-05：强制同步刷写历史缓存（测试用，绕过 debounce Timer）。 */
    internal fun flushHistoryForTest() = flushHistoryToCache()
    /** P-05.1：当前 paramValues 序列化为 JSON（测试调用用）。 */
    internal fun serializeParamsToJsonForTest(): String = serializeParamsToJson()

    /** R22 T-02/T-03 测试缝：历史快照构建（含勾选态/foreach 权威态特殊键）。 */
    internal fun buildHistorySnapshotForTest(): Map<String, String> = buildHistorySnapshot()
    /** R22 T-02/T-03 测试缝：召回（含勾选态/foreach 权威态还原）。 */
    internal fun recallFromHistoryForTest() = recallFromHistory()
    /** R22 T-02 测试缝：可见勾选框选中态（按 client property id/expr 查）。 */
    internal fun toggleCheckSelectedForTest(id: String): Boolean? =
        (conditionCheckBoxes + batchCheckBoxes).firstOrNull { it.getClientProperty(PROP_TOGGLE_ID) == id }?.isSelected
    /** R22 T-02 测试缝：foreach null 勾选框选中态。 */
    internal fun forEachNullSelectedForTest(expr: String): Boolean? =
        xmlForEachNullBoxes.firstOrNull { it.getClientProperty(PROP_FOREACH_NULL) == expr }?.isSelected
    /** R22 T-03 测试缝：foreach 值字段文本。 */
    internal fun forEachValueFieldTextForTest(expr: String): String? =
        xmlForEachValueFields.firstOrNull { it.first == expr }?.second?.text
    /** R22 T-02/T-03 测试缝：权威状态集（召回断言用）。 */
    internal fun forEachNullsForTest(): Set<String> = xmlForEachNulls.toSet()
    internal fun forEachValuesForTest(): Map<String, List<String>> = xmlForEachValues.toMap()
    /** R22 T-02 测试缝：程序化切换条件勾选（同步可见勾选框，等价用户点击的状态面）。 */
    internal fun toggleCheckForTest(id: String, selected: Boolean) {
        if (selected) enabledIds.add(id) else enabledIds.remove(id)
        (conditionCheckBoxes + batchCheckBoxes).firstOrNull { it.getClientProperty(PROP_TOGGLE_ID) == id }?.isSelected = selected
    }
    /** R22 T-02 测试缝：程序化切换 foreach null 勾选。 */
    internal fun toggleForEachNullForTest(expr: String, selected: Boolean) {
        if (selected) xmlForEachNulls.add(expr) else xmlForEachNulls.remove(expr)
        xmlForEachNullBoxes.firstOrNull { it.getClientProperty(PROP_FOREACH_NULL) == expr }?.isSelected = selected
    }
    /** R22 T-03 测试缝：程序化设置 foreach 权威值（同步可见字段文本）。 */
    internal fun setForEachValuesForTest(expr: String, values: List<String>) {
        xmlForEachValues[expr] = values
        xmlForEachValueFields.firstOrNull { it.first == expr }?.second?.text = values.joinToString(",")
    }
    /** R22 T-03 测试缝：直驱 applyXmlForEachValues（等价字段 listener 链路的状态面）。 */
    internal fun applyXmlForEachValuesForTest(expr: String, text: String) {
        applyXmlForEachValues(XmlForEachView(expr), text)
    }
    /** R22 T-02 测试缝：清空权威状态（模拟新会话；组件在场便于断言视觉同步）。 */
    internal fun clearStateForTest() {
        enabledIds.clear()
        paramValues.clear()
        xmlForEachValues.clear()
        xmlForEachNulls.clear()
    }
    /** R22 T-02 测试缝：快照灌入当前键的历史缓存（模拟既有方案）。 */
    internal fun loadSnapshotForTest(snapshot: Map<String, String>) {
        val key = historyCacheKey() ?: return
        paramHistoryCache.save(key, snapshot)
    }
    /** R22 测试缝：当前预览 SQL。 */
    internal fun historyCacheKeyForTest(): String? = historyCacheKey()
    /** R22 T-04 测试缝：方案下拉（在场/选中态断言）。 */
    internal fun schemeComboForTest(): JComboBox<String>? = schemeCombo

    /** O-88：当前预设折叠件实例（null=本轮参数区未建预设行——无参数早退/清空后）。 */
    internal fun presetFoldForTest(): HideableTitledPanel? = presetFold

    /** O-88：按 SCHEME_ROW_NAME 锚点取折叠内容里的方案行（折叠态下 visible=false 仍可取到）。 */
    internal fun schemeRowForTest(): JComponent? = presetFold?.let { findSchemeRowDeep(it) }

    /** O-95 S3 测试缝：绑定指示行标签（null=本轮参数区未建指示行）。 */
    internal fun bindStateLabelForTest(): JBLabel? = bindStateLabel

    /** O-95 S3 测试缝：参数区组件序列（指示行在预设行之下的次序断言用，BoxLayout Y_AXIS 视觉序=组件序）。 */
    internal fun paramPanelComponentsForTest(): List<java.awt.Component> = paramPanel.components.toList()

    private fun findSchemeRowDeep(root: java.awt.Container): JComponent? {
        for (child in root.components) {
            if (child.name == SCHEME_ROW_NAME) return child as? JComponent
            if (child is java.awt.Container) findSchemeRowDeep(child)?.let { return it }
        }
        return null
    }
    /**
     * 按 source 锚点取可见输入框（T-DevAcceptFix ⑤：召回回写用，禁插入序对位——见 [syncParamFieldText]）。
     */
    internal fun paramFieldForSourceForTest(source: String): JBTextField? =
        paramFields.firstOrNull { it.getClientProperty(PROP_FIELD_SOURCE) == source }

    /** R22 T-04 测试缝：程序化切换方案（等价下拉用户切换：清态→switchScheme→召回→渲染）。 */
    internal fun switchSchemeForTest(name: String) = switchToScheme(name)
    /** R22 T-04 测试缝：对话框回调链（同步下拉 + 激活变更整面重召回）。 */
    internal fun onSchemesChangedForTest() = onSchemesChangedFromDialog()

    companion object {
        /** O-4：通知分组 id（plugin.xml 注册，diff/copy 共用）。 */
        internal const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /**
         * O-88：预设分区是否处于折叠态（缺省折叠=true——20261006 T-DevAcceptFix 验收反馈定案，
         * 预设是低频操作不应默认展开占竖向空间；重建时由当前折叠件实例懒惰回写，见 [buildSchemeRow]）。
         * 静态而非实例：gutter 换语句=关旧弹窗、开新面板实例，实例字段在新实例归零丢折叠态（真机走查实证）；
         * 跨面板实例保留、不持久化（无 PropertiesComponent/磁盘写入）。@Volatile：读写入口可能非 EDT，可见性保守处理。
         */
        @Volatile
        internal var presetSectionCollapsed = true

        /** O-88 测试缝：折叠态静态字段读（跨实例保留用例断言用）。 */
        internal fun presetCollapsedForTest(): Boolean = presetSectionCollapsed

        /** O-88 测试缝：折叠态静态字段复位（setUp 调用，防静态态跨用例/跨测试类串扰）。 */
        internal fun resetPresetCollapsedForTest() {
            presetSectionCollapsed = true
        }

        /** R22 T-04：方案行 panel.name 锚点——方案行也是「含 JBLabel 的 JBPanel」且插在 paramPanel
         *  头部，[paramRowLabelForTest] 按契约只取普通参数行，须按此排除（否则布局断言序号错位）。 */
        internal const val SCHEME_ROW_NAME = "mpsv.schemeRow"

        /** O-95 S3：绑定数据源指示行/标签 panel.name 锚点（测试定位 + paramRowLabelForTest 排除同口径）。 */
        internal const val BIND_STATE_ROW_NAME = "mpsv.bindStateRow"
        internal const val BIND_STATE_LABEL_NAME = "mpsv.bindStateLabel"

        /** R22 T-02/T-03：参数历史快照特殊键前缀（勾选态/foreach null/foreach 权威值；普通参数键
         *  不以 `__` 开头——键空间约定，ParamHistoryCache 对值透明）。 */
        internal const val SNAPSHOT_CHK = "__chk__:"
        internal const val SNAPSHOT_FNULL = "__fnull__:"
        internal const val SNAPSHOT_FEV = "__fev__:"

        /** R22 T-02：勾选框 client property 锚点（快照枚举 + 召回视觉同步）。 */
        internal const val PROP_TOGGLE_ID = "mpsv.toggleId"

        /**
         * T-DevAcceptFix ⑤（20261006 验收）：参数输入框的 source 锚点——召回回写按锚点显式映射
         * （[syncParamFieldText]），禁以 paramValues 插入序对位 paramFields 行序（方案切换后
         * 快照键序与面板构建序可漂移 → 回写错位）。
         */
        internal const val PROP_FIELD_SOURCE = "mpsv.paramSource"
        internal const val PROP_FOREACH_NULL = "mpsv.forEachNull"

        /** F-45：列信息标签页标题。 */
        private const val COLUMN_INFO_TAB_TITLE = "列信息"

        /** T-PreviewTopOverlap F3：元信息宽度预算下限（px）——极窄时保底可读，超出仍由 ellipsize 收。 */
        private const val MIN_META_BUDGET_PX = 80

        /** M36.5.44：re-host 最小重挂间隔（ms）——DBN 结果刷新期高频 reparent，低于此间隔视为"还在被 DBN 处理"，跳过不抢，防死循环刷新闪烁。 */
        internal const val REHOST_MIN_INTERVAL_MS = 800L

        /** T-RefreshDbSteal：去抖窗口内被跳过的释放 → 延迟复查间隔（ms）。取与去抖门槛同值：
         *  复查时刻距最近一次重挂必然已越过门槛，`since` 复查不会误拦。 */
        internal const val REHOST_RETRY_DELAY_MS = 800L

        internal val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")
        /** T-M24-04：XML 压缩视图的空白归一化（多行文本节点 / 段间空格）。 */
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * T-PT-02：TEMPORAL 参数的可解析日期形态——`yyyy` / `yyyy-M-d` / 追加 ` HH:mm[:ss[.SSS]]`
         * （`T` 分隔兼容 ISO 形态；只做提示，不阻塞渲染）。
         */
        internal val DATE_REGEX = Regex(
            "\\d{4}(-\\d{1,2}(-\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?)?)?",
        )

        /** T-PT-02：行尾校验警示标（淡色，深浅主题各一档）。 */
        internal const val PARAM_WARN_MARK = "⚠"
        internal val PARAM_WARN_FOREGROUND = JBColor(0xB8860B, 0xD9A343)

        /**
         * T-PT-02：集合命名启发式的单数词排除表——以 `s` 结尾但语义为单数（状态/地址等），
         * 不做数组提示（宁缺勿错：提示只是占位文案）。
         */
        internal val COLLECTION_NAME_EXCEPTIONS = setOf(
            "status", "address", "class", "business", "progress", "process", "access", "alias",
            "analysis", "radius", "series", "species", "news", "pass", "glass", "mass", "press",
            "stress", "success", "express", "compass", "canvas", "atlas", "bias", "lens",
            "physics", "politics",
        )

        /** T-Perf-04：参数击键 renderSql 防抖窗口（毫秒）。 */
        private const val RENDER_DEBOUNCE_MS = 150

        /**
         * T-DollarInjectMark：`${}` 直替提示的会话级关闭记忆（✕ 后本 IDE 会话所有预览面板
         * 不再弹——常用 `${orderBy}` 场景的横幅疲劳缓解；面板关闭/切换文件不重置）。
         */
        private var dollarMarkDismissedSession = false

        /** T-DollarInjectMark：横幅文案里的 `${}` token（技术记号非语言文案，双语言共用，作 {0} 参数传入）。 */
        internal const val DOLLAR_TOKEN = "\${}"

        /** 测试复位：清除 `${}` 提示的会话级关闭记忆。 */
        internal fun resetDollarMarkForTest() {
            dollarMarkDismissedSession = false
        }

        /** T-PreviewFlash：EDT 等待预热产物的轮询间隔与超时（毫秒）。 */
        const val PREWARM_POLL_MS = 20L
        /**
         * T-PrewarmBudget（2026-09-20 据实测重定）：预热产物的等待预算——消费侧 [PrewarmAwait]
         * `completeOnTimeout` 用它决定「等多久放弃、改走降级装配」。
         *
         * **取值 500ms 的依据（真机 16 次打开 / 3 会话，见
         * `docs/验收文档/20260920-预热耗时采样与预算建议.md`）**：预热耗时（打点
         * `[M36] prewarm done in Xms`）冷启动首开 233/239/360ms（n=3），同会话复开
         * 116/133/184ms（n=13）——**旧值 60ms 一次都覆盖不到（16/16 全降级）**。500ms 覆盖观测到的
         * 冷启最大 360ms（约 40% 余量）与全部复开（2.7 倍余量）。
         *
         * **旧值 60ms 的理由已失效**：其原注释称「EDT 干等到 2000ms 会把延迟烧进 UI 线程（真机日志
         * 双击→createEditor 达 3.07s）」——那是**旧的同步 sleep 轮询实现**（`awaitPrewarmedTempFile`）；
         * 自 T-PerfTail #2 改成异步后，冷态 `scheduleOnce` 注册回调即返回，**EDT 根本不等待**，
         * 该预算只决定装配质量与等待时长，不再有「省 EDT 时间」的作用。
         *
         * **已知不覆盖**：重负载（CPU 72%~99%）下冷启曾观测到约 1s——那种情况仍会降级，且正确的解法
         * 是让预热更快（`t-fix-n2` 的父目录映射已把每次打开都要付的 `setMapping`/push-all dumb 整条去掉，
         * 本轮实测 `Push all [SqlDialectPusher]` 计数 0），而不是无限抬高预算。
         * 抬高预算的代价是等待期变长，已由 [showPreviewPreparingPlaceholder] 的占位覆盖观感。
         */
        const val PREWARM_WAIT_MS = 500L

        /**
         * T-PerfSweep6 T-PS6-04（性能报告发现 4）：预热目录残留清扫的**年龄**上限（7 天）。
         *
         * 取值口径：残留来自 kill / 崩溃 / 未走 removeNotify 的会话，跨会话（IDE 重启）后本插件
         * 不会再复用它们；7 天足以覆盖「用户当周内重开项目仍可能被兜底复用」的窗口
         * （`projectPreviewFile` 的 existing 分支），又不会让残留无限驻留。
         */
        internal const val STALE_PREVIEW_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

        /**
         * T-PerfSweep6 T-PS6-04：预热目录内本插件命名的文件**条数**上限（超出按 mtime 删最旧）。
         *
         * 200 = 常规使用下永远撞不到（一次预览最多留 1~2 个文件），只兜「异常路径反复泄漏」的底。
         */
        internal const val PREVIEW_DIR_MAX_FILES = 200

        /**
         * T-FixN2 × T-InsertColumnInlay O-44b：生成下一次预热的唯一临时文件名。
         *
         * **形态（`mpsql-preview-<pid>-<seq>.sql`，例 `mpsql-preview-12480-3.sql`）的唯一来源是
         * [PreviewInlayScope]**：名字由该对象按「形态常量 + 形态判据」同处一地给出，本面板只供运行期
         * 变量（pid / 序号）。**为什么必须同源**：inlay 作用域锚点靠文件名判定「这个编辑器是不是本
         * 面板预览编辑器」，而一级预览文件名每轮预热都不同（见下），故锚点按**形态**匹配——两侧各自
         * 硬编码字面量会在改名时静默打穿锚点（锚点不报错，只是 inlay 永不出现）。
         *
         * **为什么每次预热必须换名**：固定名（原 `mpsql-preview.sql`）下预热重写同一路径，而该路径
         * 的 document 里存着上一轮装配写入的真 SQL（未落盘）→ 平台 `MemoryDiskConflictResolver`
         * 判定「内存与磁盘均变」→ 模态「File Cache Conflict」抢焦点；选「Load File System Changes」
         * 还会把真 SQL 换成磁盘上的占位空内容。换名后每次预热落在全新路径（无任何 document），
         * 冲突链从根上消失（详见 [projectPreviewFile]）。
         *
         * **为什么带 pid**：同项目可被多个 IDE 进程同时打开（各自一份本目录），进程内自增序号在
         * 两个进程里会重号——重号会让「按面板生命周期删除」误删另一会话正在用的文件。
         * pid 在**存活进程间唯一**，故存活会话之间不可能撞名；与已退出会话的同名残留相遇时
         * 只会走 [projectPreviewFile] 的兜底复用分支（无 document → 仍无冲突）。
         * 序号 + pid 都是可读、可定位的：目录里一眼看出「哪个 IDE 进程的第几次预览」。
         */

        /** T-FixN2：本 IDE 进程号（文件名的会话段；取不到退化为 `0`，仍是合法文件名）。 */
        private val PREVIEW_TEMP_FILE_OWNER: String =
            runCatching { ProcessHandle.current().pid().toString() }.getOrDefault("0")

        /** T-FixN2：本 JVM 内预览序号（跨面板共享，保证同进程内每个预热文件名唯一）。 */
        private val PREVIEW_TEMP_FILE_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        /** T-FixN2：生成下一次预热的唯一临时文件名（形态与判据同源，见 [PreviewInlayScope]）。 */
        internal fun nextPreviewTempFileName(): String = PreviewInlayScope.previewTempFileName(
            PREVIEW_TEMP_FILE_OWNER,
            PREVIEW_TEMP_FILE_SEQ.incrementAndGet(),
        )

        /**
         * T-FixN2：预览文件的方言映射对象——**父目录**（取不到父目录时退回文件本身，语义同改动前）。
         *
         * 唯一文件名后按文件映射会让每个新名字都判「未映射」：每轮预热白付一次 setMapping
         * （`SqlDialectPusher` push all dumb）+ workspace.xml 的 `SqlDialectMappings` 状态按文件路径
         * 逐个累积。平台 `PerFileMappingsBase.getMappingInner → getMappingForHierarchy` 自下而上
         * 逐级查表（`for (cur = file; cur != null; cur = cur.getParent())`），故目录映射被其下所有
         * 文件继承（平台自身也按目录记账：`installDeleteUndo` 对 `file.isDirectory()` 显式收集其下
         * 全部子条目），一次映射覆盖该目录下**全部**预览文件（含每轮的新名字）。
         *
         * 抽成 internal 纯函数仅为让「映射对象是目录、且目录 vf 实例跨文件名稳定」可直接单测断言
         * （真机/单测环境无 Database 插件时 `SqlDialectMappings` 类不可加载，映射本身不可断言）。
         */
        internal fun previewDialectMappingTarget(vf: com.intellij.openapi.vfs.VirtualFile): com.intellij.openapi.vfs.VirtualFile =
            vf.parent ?: vf
    }
}

// ------------------------------------------------------------------
// T-SourceJumpFunnel P2-2（§3.3 验收项单测抽取）：调用栈快照文案组装
// ------------------------------------------------------------------

/** 调用栈摘要字符上限：超过即截断到该长度并追加 [STACK_DIGEST_ELLIPSIS]。 */
internal const val STACK_DIGEST_MAX_CHARS = 120

/** 调用栈摘要截断标记。 */
internal const val STACK_DIGEST_ELLIPSIS = "…"




