package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
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
class SqlPreviewPanel(private val project: Project? = null) : JBPanel<SqlPreviewPanel>(BorderLayout()), Disposable {

    private val metaLabel = JBLabel()

    // T-PreviewTopOverlap F1/F3：顶部行与工具栏提升为字段——布局断言（topRowForTest/toolbarForTest）
    // 与宽度预算计算（applyMetaTruncation 需工具栏理想宽）使用
    private lateinit var topRow: JBPanel<JBPanel<*>>
    private lateinit var toolbarPanel: JBPanel<JBPanel<*>>

    // T-PreviewTopOverlap F3：元信息原文缓存——metaLineText 第一行（meta+警示，纯文本）、
    // metaPerfSegments 第二行分段（可见文本+颜色）；tooltip 恒全文，label 文本按预算重拼
    private var metaLineText: String = ""

    /**
     * F3 分段缓存（P5 复核修正）：meta 行**主体**（meta + 警示行）——宽度预算下恒优先保留。
     * 与 [metaLineDigest] 分段缓存的原因见 [metaLineWithinBudget]（摘要不得挤掉主体）。
     */
    private var metaLineHead: String = ""

    /** F3 分段缓存（P5 复核修正）：meta 行尾部的调用栈内联摘要——预算不足时先截它。 */
    private var metaLineDigest: String = ""
    private var metaPerfSegments: List<MetaPerfSegment> = emptyList()
    private var lastMetaBudget: Int = -1

    /**
     * T-SourceJumpFunnel P6 修复：meta 行 tooltip 的**基线文本**（[updateMeta] 每次预览重建）。
     *
     * 行内提示态（参数导入空剪贴板）需要临时改写可见行与 tooltip，但 tooltip 是「悬停见完整调用链」
     * 的唯一载体（§3.3 后半句），不能被提示覆写丢掉——故提示态按「提示 + 换行 + 基线」拼接，
     * 本字段是拼接的基线来源（也保证提示重复触发时幂等，不会叠加自身）。
     */
    private var metaTooltipBase: String = ""

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
    private val paramFields = mutableListOf<JBTextField>()
    // T-PT-02：普通参数行行尾校验警示标（与 paramFields 同序，仅 buildParamRow 产出）
    private val paramWarnLabels = mutableListOf<JBLabel>()
    private val conditionCheckBoxes = mutableListOf<JCheckBox>()
    // R30 条件求值探镜：XML 条件行尾的三态结果标注（条件 id → 标签），renderSql 每轮刷新（零监听器）
    private val xmlConditionOutcomeLabels = mutableMapOf<String, JBLabel>()
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

    /**
     * T-M27.2：自建 autopopup 的调度防重入标志（平台 autopopup 对非文件编辑器不生效）。
     * T-RA-02：监听器本体不再用字段记账——T-M52 起移除职责全在 [autoPopupDisposable]
     * （`addDocumentListener(listener, disposable)`，dispose 即摘），原 `autoPopupListener`
     * 字段自此只写不读，已删；细节见 [bindAutoPopup]。
     */
    private var autoPopupScheduled = false

    /** T-M27.4：渲染 setText 期间抑制自建 autopopup（格式化替换整文档不触发补全）。 */
    private var suppressAutoPopup = false
    private var psiFile: PsiFile? = null
    /** M34 回归修复：预览 PSI 的临时 VirtualFile（PsiDocumentManager 追踪需真实 VFS 文件），releaseEditor 时删除。 */
    @Volatile
    private var previewTempFile: com.intellij.openapi.vfs.VirtualFile? = null
    /**
     * T-FixN2：本面板**已创建**的预览临时文件登记表（[projectPreviewFile] 建文件即登记，
     * [releaseEditor] 统一回收）。与 [previewTempFile] 的分工：后者是「当前在用/最近一次预热」
     * 的那一个（唯一名字后仍可能被竞态里晚到的预热覆盖），本表是「本面板创建过的全部」——
     * 覆盖「预热已发布但从未被装配消费」与「两次预热竞态中晚到的那一个」两类无主文件。
     *
     * 只登记本面板自己创建的文件（含跨会话同名残留的兜底复用分支），故 [releaseEditor] 的删除
     * **不会**误删其它面板/其它 IDE 会话正在使用的预览文件（同项目多开进程各有自己的 pid 段）。
     */
    private val previewTempFilesCreated = java.util.concurrent.ConcurrentHashMap.newKeySet<java.io.File>()

    /**
     * T-PerfSweep6 T-PS6-04：目录级兜底清扫的「本面板已扫过」闸门（首次解析预热目录时扫一次）。
     * 原子置位：`projectPreviewFile` 可能被预热线程与 EDT 同时触达。
     */
    private val previewDirSwept = java.util.concurrent.atomic.AtomicBoolean(false)
    /** T-M36.6-03：后台预热产物（vf+PSI 就绪对），createEditor 优先消费——EDT 零慢操作。 */
    @Volatile
    private var prewarmedTempFile: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? = null

    /**
     * T-PerfTail #2：预热产物异步载体（消除 EDT sleep-poll）——prewarmPreviewTempFile 每次
     * 预热新建（clear 重预热随之更新），createEditor 热态同步消费、冷态注册回调回 EDT 装配。
     */
    @Volatile
    private var prewarmAwait: PrewarmAwait<Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>>? = null
    /** T-GifRerecord：预热失败标志——await 快速降级用，避免 EDT 空等 2s 后再走降级路径。 */
    @Volatile
    private var prewarmFailed = false

    /**
     * T-PrewarmLateRecover F1/F2：预热轮次令牌——每次发起预热（构造期 / [clear] 重预热 / 横幅重试）
     * 自增，预热线程发布产物时比对。不等即「本轮已被后来的一轮取代」：产物不入字段（否则旧轮的
     * 迟到产物会覆盖新轮刚交付的那份），文件仍由 [releaseEditor] 按登记表回收。
     */
    @Volatile
    private var prewarmRound = 0

    /**
     * T-PrewarmLateRecover F1：当前编辑器文档是**降级产物**时的身份标记（主路径装配成功置 null）。
     * 迟到产物换装前用它确认「此刻显示的确实是那份降级文档」——不比对身份就可能把用户已经
     * 在用的主路径文档误换掉。
     */
    private var degradedPreviewDocument: com.intellij.openapi.editor.Document? = null

    /**
     * T-PrewarmLateRecover F1：插件最后一次写进编辑器文档的 SQL 文本——换装判据里的「用户没动过」。
     * 三条写入支链（原生控制台 / 既有编辑器 / 新建编辑器）都要更新，漏一条即把插件的重渲染
     * 误判成用户编辑（换装被永久挡掉）。
     */
    private var lastRenderedSql: String? = null

    /**
     * T-PrewarmLateRecover F1：预热相关回调的 EDT 派发通道（写安全模态）。
     *
     * 与 [prewarmPreviewTempFile] 里 PrewarmAwait 的 dispatcher 是同一份实现：迟到产物换装也要
     * 写文档（`WriteCommandAction { d.setText }`），故必须同款 `defaultModalityState()`——
     * `any()` 恒 write-unsafe（T-FixN1 的 KDoc 有平台侧逐帧依据）。
     */
    private val prewarmDispatcher: (Runnable) -> Unit = { runnable ->
        ApplicationManager.getApplication().invokeLater(
            runnable,
            com.intellij.openapi.application.ModalityState.defaultModalityState(),
        )
    }
    /** T-M36.6-03：面板释放标志——预热线程晚于 dispose 完成时丢弃产物并删除文件。
     *  T-StabilityTail 起语义扩为「放弃预热」：面板 dispose 与**项目开始销毁**（见
     *  [prewarmLifecycleGate]）都置它，预热线程在下一个检查点放弃（不再取服务）。 */
    @Volatile
    private var previewDisposed = false

    /**
     * T-StabilityTail（窗口内异步泄漏治理）：预热任务的「项目开始销毁」取消门。
     *
     * 注册进 `ProjectEx.getEarlyDisposable()` 的 Disposable 树（见 [bindPrewarmToProjectLifecycle]），
     * 项目开始销毁时由平台回收并置 [previewDisposed]，使构造期/`clear()` 派发的池线程在窗口内
     * 不再取服务。面板 dispose 时解除登记（不留指向本面板的项目树引用）；null = 未挂或已解除。
     */
    private var prewarmLifecycleGate: Disposable? = null
    /** T-PreviewExecDs-01c：已 setMapping 方言的预览 vf（同对象幂等短路，避免每次预览重设方言触发 push all dumb）。
     *  T-FixN2 起该 vf 是**预览目录**（不再是单个文件）——见 [mapPreviewDialectBeforePsi]。 */
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

    /**
     * T-UxOnboard T-UX-02：预览编辑器空态引导（原生 placeholder，显隐由编辑器随空文档管理）。
     * 常态下预览内容即编辑器全文、placeholder 不可见；用户清空文档后原生提示接管。
     */
    private val previewEmptyHint: String = MpSqlVisualizerBundle.message("panel.preview.emptyHint")

    /** M36：已内嵌的原生控制台 UI 组件（清空/释放时级联 dispose）。 */
    private var embeddedConsole: JComponent? = null

    /**
     * R33：结构化 EXPLAIN 回调代际计数——每次发起新的 EXPLAIN 结构化提交/新预览/清空都递增，
     * 在途 pooled 回调（JDBC 可能慢于用户操作）按提交时捕获的代际判过期，过期即丢弃：
     * 慢计划绝不覆盖用户已经看到的新预览/新提交（与 DataSourceSelector 的 swapGeneration 同思路）。
     */
    private var explainStructuredGeneration = 0

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
    private var diagramEnrichRetryTarget: PreviewResult? = null
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
    // O-78 T-ForeachItemRender：foreach 集合值权威状态（collectionExpr → 逗号分隔解析后的值序）——
    // 播种收进 renderSql 帧内按新鲜映射清单进行，值不再依赖「渲染后同步读键清单」
    private val xmlForEachValues = mutableMapOf<String, List<String>>()
    private val xmlForEachNulls = mutableSetOf<String>()
    // R22 T-02/T-03：勾选态与 foreach 行的组件登记（快照枚举 + 召回视觉同步；随 rebuild 清场）
    private val xmlForEachNullBoxes = mutableListOf<JCheckBox>()
    private val xmlForEachValueFields = mutableListOf<Pair<String, JBTextField>>()
    private var xmlErrors = listOf<String>()
    /** T-M25.4：最近一次 XML 渲染的参数映射（集合值输入按当前渲染键回填）。 */
    private var xmlMappings = emptyList<cn.song.mybatisplus.visualizer.xml.ParameterMapping>()
    // T-M25.3：错误信息复制按钮（warning 存在时可见，一键复制完整错误文本）
    private var errorCopyButton: JButton? = null
    private var currentWarning: String? = null

    /**
     * T-PSG-04（N-33）：最近一次参数回填的「`?` 位点数 vs 参数条数」对账标注——不等时非 null。
     *
     * 只在 [backfillParamsBySource] 里按串感知位点数与 sources 条数计算（每次回填都重算，不留
     * 陈旧值）；执行前由 [warnOnPlaceholderMismatch] 读走并如实登记日志。**不阻断**任何流程
     * （与我方既有 `${}` 闸门「确认/标注」档一致，不引入拒绝档）。
     */
    private var placeholderReconciliation: String? = null

    // T-PreviewUx-Pack #2：诚实降级横幅——渲染警示（RENDER_WARNING）与 T-PreviewExecDs
    // 降级链层级提示（DEGRADED）统一收口；降级提示优先级高于渲染警示（见 DegradationBanner.Kind）
    private val degradationBanner = DegradationBanner().apply {
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
    private var dollarRawConfirmedInSession = false

    /**
     * T-PC2-04（D1=B 定案）：本面板实例内已确认过含 UNKNOWN 参数执行（会话级记忆）。
     *
     * 语义与 [dollarRawConfirmedInSession] 同款（同面板确认一次后不再重复弹；执行与 EXPLAIN
     * 共用同一记忆，两条入口是同一道门）；记忆随面板实例存活，面板 dispose 即失效。
     * @Volatile：执行入口可能在非 EDT 触发（弹窗按钮回调），读写可见性保守处理。
     */
    @Volatile
    private var unknownParamsConfirmedInSession = false

    // P-05：参数历史缓存 + debounce 写回
    private val paramHistoryCache = ParamHistoryCache()
    /** P-05：debounce 写回调度器（Swing Timer，500ms 延迟，restart 实现防抖）。 */
    private val historyAlarm = javax.swing.Timer(500) { flushHistoryToCache() }.apply { isRepeats = false }

    // R22 T-04：命名方案下拉（rebuildLeftPanel 随参数区重建；syncSchemeCombo 填充/刷新）
    private var schemeCombo: JComboBox<String>? = null
    // O-95 S3：绑定数据源指示行标签（rebuildLeftPanel 重建；renderSql 尾部单点刷新）
    private var bindStateLabel: JBLabel? = null
    /** 程序化填 combo 模型时置位（抑制 addActionListener 的用户切换语义）。 */
    private var schemeSyncingCombo = false
    /** 上次已同步到下拉的激活方案名——对话框回调时比对判定「激活方案被对话框切走」。 */
    private var lastShownActiveScheme: String = ParamHistoryCache.DEFAULT_SCHEME
    /** 管理方案对话框（MODELESS，单实例防堆叠；关闭后由可见性判断重建）。 */
    private var schemeManagerDialog: ParamSchemeManagerDialog? = null

    // O-88：预设行折叠件（平台标准 HideableTitledPanel，Run Config「Before launch」区同款）。
    // 折叠态=companion 静态字段（声明见伴生对象 [Companion.presetSectionCollapsed]）：
    // 跨预览重建且跨面板实例保留——gutter 换语句走「关旧弹窗、开新面板实例」（真机走查实证：
    // 实例字段在新实例归零、折叠态丢失），静态化后新实例初始态继承；不跨重启持久化、无设置开关。
    /**
     * 当前预设折叠件实例——[HideableTitledPanel] 公共面无状态变更监听器（装饰器私有），
     * 用户点标题折叠的变化以「重建时读 [HideableTitledPanel.isExpanded] 回写」的懒惰捕获跟进；
     * 折叠件随 paramPanel.removeAll 摘除后实例仍持有最后视觉态，跨 clear/rebuild 均可恢复。
     */
    private var presetFold: HideableTitledPanel? = null

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
        // T-PreviewUx-Pack #2：诚实降级横幅（面板内嵌非模态提示，不算弹窗）——渲染警示 /
        // T-PreviewExecDs 降级链层级提示统一收口（触发即显示、恢复即隐藏，不占高度）
        val northHost = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(topRow, BorderLayout.NORTH)
            add(degradationBanner, BorderLayout.SOUTH)
        }
        add(northHost, BorderLayout.NORTH)
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
    private fun prewarmPreviewTempFile() {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        // T-PerfTail #2：预热线程产出 PrewarmAwait 完成态（成功 complete / 失败 fail）——
        // 消费侧（createEditor）不再 EDT sleep-poll
        // T-FixN1（N1 写安全）：派发模态由 any() 改 defaultModalityState()（写安全模态）。回调体是
        // resolvePreviewEditorSource 的 onReady/onFallback → assemblePreviewEditor，其内**写文档**
        // （WriteCommandAction { d.setText(sql) } + commitDocument）——而 any() **恒 write-unsafe**：
        // TransactionGuardImpl 的 myWriteSafeModalities 只预置 nonModal→true（构造期），
        // wrapLaterInvocation 只对 isWriteSafeModality(state)==true 的 runnable 套 runWithWritingAllowed
        // （any() 走 else 分支直接 run，myWritingAllowed 保持 false），于是 DocumentImpl.assertWriteAccess
        // → assertWriteActionAllowed 在 myWritingAllowed=false 时 LOG.error「Write-unsafe context!」
        // （真机必现：重开预览第 2 次即被平台 Suppressed 计数；243 平台 jar 反编译逐帧实证）。
        // 代价：模态对话框挂起期间派发推迟到模态关闭——面板本就在模态框后面（用户看不见），观感代价
        // 接近零。且冷态派发发生在预热池线程（无模态进度上下文），defaultModalityState() 常态落
        // NON_MODAL（恒 write-safe），与 11e2671 选 any() 的唯一理由「派发不被模态挡住、EDT 零阻塞」
        // 不冲突——该提交信息里没有「必须在模态对话框挂起期间完成装配」的场景。
        // 边界：控制台挂载/回填链路（M36.5，本文件 onConsole 回调的 post-attach 重渲染等站点）的
        // any()/nonModal() 是另一条链路，不在本改动范围。
        // T-PrewarmLateRecover F1：轮次令牌与迟到产物接收方都必须在预热线程可能发布**之前**就位
        // （令牌晚了会让旧轮产物盖掉新轮；接收方晚了会漏掉「超时已放弃、产物随后到达」这一态）。
        val round = ++prewarmRound
        val await = PrewarmAwait<Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>>(
            PREWARM_WAIT_MS,
            prewarmDispatcher,
        )
        await.onLateProduct { product -> prewarmDispatcher { tryUpgradeToLateProduct(product) } }
        prewarmAwait = await
        // T-StabilityTail：把「项目开始销毁」接到取消门——池线程不得比项目活得更久
        bindPrewarmToProjectLifecycle()
        ApplicationManager.getApplication().executeOnPooledThread {
            // T-PrewarmBudget（2026-09-20）：预热**自身耗时**打点起点——此前全链无完成打点，
            // 真机实测报告只能靠「方言映射行出现」与「临时文件被删」两条间接判据夹逼出区间
            // （最紧一次 ∈ (280ms, 1071ms]），无法定 `PREWARM_WAIT_MS` 预算。本打点即补这个洞：
            // 预热完成/失败时各记一行带耗时，用于在空闲机取样本后定预算。
            val prewarmStartedAt = System.nanoTime()
            if (previewTempFile != null || prewarmAbandoned()) {
                await.fail(IllegalStateException("prewarm skipped: disposed or already prepared"))
                return@executeOnPooledThread
            }
            runCatching {
                // T-PreviewExecDs-01a：预览临时文件迁项目内 .idea/sql-visualizer/preview/
                // ——系统 Temp 不在数据源关联范围，Database 插件不解析（M36.5.12 exec 同教训，
                // 预览路径此前未跟进；旧实现 09:34:40 起持续预热失败后回退裸文档，补全/注释全灭）。
                val io = projectPreviewFile() ?: run {
                    prewarmFailed = true
                    await.fail(IllegalStateException("project preview file unavailable"))
                    LOG.warn("[M36] prewarm FAILED in ${prewarmElapsedMs(prewarmStartedAt)}ms: project preview file unavailable (project/basePath null or mkdirs false)")
                    return@executeOnPooledThread
                }
                // T-GifRerecord：VFS 忙时 refreshAndFindFileByIoFile 偶发查不到新文件（2026.2 沙盒
                // 录制期实证）——旧实现只重试一次仍持续失败（两项目同开 VFS 忙窗口）；T-PreviewExecDs-01b
                // 加重试到 3 次带退避，失败置 prewarmFailed 让 await 快速返回，不空等预算。
                val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                var vf = lfs.refreshAndFindFileByIoFile(io)
                var retries = 0
                while (vf == null && retries < 3 && !prewarmAbandoned()) {
                    Thread.sleep(100L * (retries + 1))
                    vf = lfs.refreshAndFindFileByIoFile(io)
                    retries++
                }
                if (vf == null) {
                    prewarmFailed = true
                    await.fail(IllegalStateException("VFS refresh null after ${retries + 1} tries"))
                    LOG.warn("[M36] prewarm FAILED in ${prewarmElapsedMs(prewarmStartedAt)}ms: VFS refresh null after ${retries + 1} tries (${io.path}, exists=${io.exists()})")
                    return@executeOnPooledThread
                }
                if (prewarmAbandoned()) {
                    java.io.File(vf.path).delete()
                    await.fail(IllegalStateException("disposed during prewarm"))
                    return@executeOnPooledThread
                }
                val proj = project ?: return@executeOnPooledThread
                // T-StabilityTail：紧贴取服务再判一次——从上一处判定到这里之间项目可能已开始销毁
                // （closeProject 的写动作与本线程并行）；窗口内首次创建服务失败会把该服务在该项目
                // 实例上永久毒化（`LazyInstanceHolder` 缓存 CannotInitialize 后每次 getService 重抛，
                // 见 config/SectionConfigurable.kt KDoc），故宁可少取一次服务。
                if (prewarmAbandoned()) {
                    await.fail(IllegalStateException("abandoned before service lookup"))
                    return@executeOnPooledThread
                }
                // T-PreviewExecDs-01c：方言映射须在 findFile/PSI 创建前（映射决定 PSI 语言，
                // 创建后再映射需 push 重建；exec 路径同款顺序）
                mapPreviewDialectBeforePsi(proj, vf)
                // T-StabilityTail：判据放读动作**内部**才管用——平台读动作可被 closeProject 的写动作
                // 阻塞，放行时窗口已开（`ProjectImpl.isDisposed()` 在窗口内为真）；在外面判会漏掉这条窄缝，
                // 而漏掉的代价就是下面 `PsiManagerImpl.findFile → FileManagerImpl.dispatchPendingEvents`
                // 把 "Project is already disposed" 记成 ERROR（测试夹具里升格为 TestLoggerAssertionError）。
                val psi = com.intellij.openapi.application.ReadAction.compute<PsiFile?, Throwable> {
                    if (prewarmAbandoned()) null else com.intellij.psi.PsiManager.getInstance(proj).findFile(vf)
                }
                if (psi == null) {
                    // 放弃（项目/面板已销毁）与真失败分开记账：前者不置 prewarmFailed、不记 WARN——
                    // 它是正常生命周期事件，不是预热故障
                    if (prewarmAbandoned()) {
                        await.fail(IllegalStateException("abandoned during findFile"))
                        return@executeOnPooledThread
                    }
                    prewarmFailed = true
                    await.fail(IllegalStateException("PsiManager.findFile null for ${vf.name}"))
                    LOG.warn("[M36] prewarm FAILED in ${prewarmElapsedMs(prewarmStartedAt)}ms: PsiManager.findFile null for ${vf.name}")
                    return@executeOnPooledThread
                }
                previewTempFile = vf
                publishPrewarmProduct(await, vf to psi, prewarmStartedAt, round)
            }.onFailure { e ->
                prewarmFailed = true
                await.fail(e)
                LOG.warn(
                    "[M36] prewarm FAILED in ${prewarmElapsedMs(prewarmStartedAt)}ms: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        }
    }

    /**
     * T-PreviewDocLock2 F3 回归修复：预热产物**发布**——字段与 await 必须拿到**同一个 Pair 实例**。
     *
     * 消费侧 [assemblePreviewEditor] 以「引用相等 + 随即置 null」表达「产物尚未被消费」，
     * 故发布侧不能把 `vf to psi` 写两遍（原实现 `prewarmedTempFile = vf to psi` 与
     * `await.complete(vf to psi)` 各造一个实例 → `===` 恒假 → 主路径恒判「产物不可用」，
     * 预览对话框每次打开都降级到内存 PSI 并挂降级横幅）。抽成本方法后「同一实例」由单次
     * 构造保证，并由 SqlPreviewPanelPrewarmConsumeTest 以真实消费链（预热产物就绪 → showResult
     * → 编辑器文档落在项目内临时文件上）锁死该不变式。
     */
    private fun publishPrewarmProduct(
        await: PrewarmAwait<Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>>,
        product: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>,
        startedAtNanos: Long,
        round: Int,
    ) {
        // T-PrewarmBudget（2026-09-20）：预热**成功**耗时打点——消费侧预算 `PREWARM_WAIT_MS` 只有在
        // 「预热耗时 < 预算」时才可能走主路径；此前无此打点，预算取值只能靠间接夹逼。判读口径：
        // 本行耗时 > PREWARM_WAIT_MS ⇒ 该次打开必然落 onFallback 降级（可与紧随其后的
        // `[M36] in-memory PSI preview document created` 对照）。
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info(
                "[M36] prewarm done in ${prewarmElapsedMs(startedAtNanos)}ms " +
                    "(file=${product.first.name}, psi=${product.second.name})",
            )
        // T-PrewarmLateRecover F1：旧轮产物不入字段（见 [prewarmRound]）——文件仍由 [releaseEditor]
        // 按登记表回收，不会漏删。
        if (round != prewarmRound) {
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .info("[M36] prewarm product dropped: stale round=$round (current=$prewarmRound)")
            return
        }
        prewarmedTempFile = product
        // T-PrewarmLateRecover F1：`complete` 返回 false = 消费侧已放弃（超时以 null 完成 / 失败），
        // 此时产物走迟到通道换装——这正是「首开预热 3016ms、预算 500ms」那类场景的恢复出口。
        if (!await.complete(product)) {
            prewarmDispatcher { tryUpgradeToLateProduct(product) }
        }
    }

    /** T-PrewarmBudget：预热打点用耗时（毫秒）；`nanoTime` 差值，不受系统时钟回拨影响。 */
    private fun prewarmElapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000L

    /**
     * T-StabilityTail：把「项目开始销毁」接到预热取消门（[prewarmAbandoned] 的项目侧判据）。
     *
     * **为什么挂 `ProjectEx.getEarlyDisposable()`**——平台 KDoc 原文即此用途：
     * 「a Disposable that will be disposed right after container started to be disposed … In unit test
     *   mode light project is not disposed, but this disposable is disposed for each test. So, you don't
     *   need to have another disposable and can use this one instead.」
     * 两个触发点：①真机关闭项目——`ProjectManagerImpl.closeProject` 非 light 分支在写动作内先
     * `disposeEarlyDisposable()` 再 `startDispose()`；②light 测试夹具的**每个测试方法 tearDown**——
     * 同方法 light 分支把 `disposeEarlyDisposable()` 与 `setTemporarilyDisposed(true)` 放进同一写动作
     * （`ProjectManagerImpl.kt:345-352`），后者正是「池线程在 temporarily-disposed 窗口内首次取服务 →
     * 服务被永久毒化」的时点。
     *
     * **为什么不挂 `Disposer.register(project, …)`**：light 项目的**主** disposable 在片内跨测试方法
     * 一直存活（`setTemporarilyDisposed(false)` 只重建 earlyDisposable），挂主树等于整轮不触发。
     *
     * **取消而非 join**：门一置，预热线程在其下一个检查点放弃——不再发起 `SqlDialectMappings` /
     * `PsiManager` 取服务，于是不会把「窗口内首次创建服务失败」的异常喂给平台 holder。不等待的两个理由：
     * 项目关闭路径上 join 会把在途预热的耗时加进用户可感的关闭时间；且平台对该 Disposable 的 dispose
     * 有硬要求——「must be self-contained and isolated (getting services is forbidden)」，本实现只置标志。
     *
     * **降级**：earlyDisposable 树的取入口是 `@ApiStatus.Internal`（Marketplace 审核对 internal
     * API 用法要求作者响应，20260923），且项目已 dispose 时抛 `AlreadyDisposedException`——取树改走
     * **反射**（字节码零平台 internal 类型引用；方法在 ProjectImpl 上实现，KDoc 口径不变），并用
     * [Disposer.tryRegister]（父已销毁即返回 false，不抛）加外层 runCatching 兜底；反射失败或
     * 挂不上时静默返回，行为退回改动前，预热主链不受影响。
     */
    private fun bindPrewarmToProjectLifecycle() {
        if (prewarmLifecycleGate != null) return // 已挂：clear() 重预热不重复登记
        val proj = project ?: return
        val gate = Disposable { previewDisposed = true }
        val registered = runCatching {
            // T-MarketCompatProxy：getEarlyDisposable 标 @ApiStatus.Internal 且无公开等价——
            // 反射取树（仅此一处调用），消除 verifier 的 internal API usage。
            val early = runCatching {
                proj.javaClass.getMethod("getEarlyDisposable").invoke(proj) as? Disposable
            }.getOrNull() ?: return@runCatching false
            Disposer.tryRegister(early, gate)
        }.getOrDefault(false)
        if (registered) prewarmLifecycleGate = gate
    }

    /**
     * T-StabilityTail：预热线程的「放弃」判据——面板已销毁 或 项目已开始销毁。
     *
     * 项目侧判据取 `Project.isDisposed`：平台口径为 `super.isDisposed() || isTemporarilyDisposed`
     * （`ProjectImpl.isDisposed()`，243/262/2026.2 三版 javap 同形），temporarily-disposed 窗口内为真。
     * 它在这里是**门判据的补充**而非「盲目守卫」：真正的修法是上面那道生命周期取消门（让池线程
     * 不再比项目活得久），本判据只用来收掉「判定通过后、取服务前项目被并发关闭」的窄缝——那条缝
     * 平台侧无锁可挡（读动作会被 closeProject 的写动作阻塞，放行时窗口已开）。
     */
    private fun prewarmAbandoned(): Boolean = previewDisposed || project?.isDisposed == true

    /**
     * T-PreviewExecDs-01a：项目内预览临时文件——`<project>/.idea/sql-visualizer/preview/`。
     * 独立子目录（exec/ 互不污染，同 M36.5.13 语义）。文件在项目内容内，Database 插件才能解析
     * schema 补全/注释。VFS 安全写入三态（document 驱动 > setBinaryContent > FileUtil 兜底），
     * 杜绝 MemoryDiskConflictResolver 的 File Cache Conflict 弹窗（exec 路径 M36.5.13 同款）。
     *
     * T-FixN2：文件名由固定 `mpsql-preview.sql` 改为**每次预热唯一**（见 [nextPreviewTempFileName]）。
     * 固定名的缺陷链（真机必现，第二轮复核 3 次询问/2 次弹窗）：预热重写同一路径，而上一轮装配
     * 写进该文件 document 的真 SQL **从未落盘**（`assemblePreviewEditor` 只 `setText`，该文档不走
     * `saveDocument`），于是磁盘恒为预热占位空内容 + 内存文档是「已修改未保存」；平台
     * `MemoryDiskConflictResolver.beforeContentChange` 判据（243 sources 实证）正是
     * 「VFileContentChangeEvent 且该路径有 cached document 且 isDocumentUnsaved 且
     * documentStamp != oldFileStamp」——三条全中 → 弹模态「File Cache Conflict」抢焦点；
     * 用户选「Load File System Changes」即 `reloadFromDisk(document)` 把内存里的真 SQL 换成磁盘
     * 的占位空内容（表现为编辑区只剩占位文案，点【刷新】重新渲染才恢复）。
     * 唯一文件名让每次预热都落在**没有任何 document 的全新路径**上 → 首次创建裸写安全
     * （exec 路径 T-ExecDocSync3 同款判据：VFS 无该条目时不可能存在 document），冲突链从根上消失。
     * existing 分支保留为兜底（跨会话残留的同名文件 / 同 pid 复用），仍是 VFS 安全写三态。
     *
     * 建文件即登记进 [previewTempFilesCreated]，由 [releaseEditor] 回收（本面板文件数不随预览次数增长）。
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
        // T-PerfSweep6 T-PS6-04（发现 4）：本面板首次解析预热目录时做一次目录级兜底清扫
        // （年龄 > 7 天 / 条数 > 200），只认本插件自有命名——见 [sweepStalePreviewFiles]。
        // 每次面板生命周期只扫一次（清扫是兜底，不必每次预览都付一遍 listFiles）。
        if (previewDirSwept.compareAndSet(false, true)) {
            sweepStalePreviewFiles(dir)
        }
        // T-FixN2 × T-InsertColumnInlay O-44b 合并口径：文件名**唯一**（每次预热换名，见上）且**由
        // inlay 作用域锚点同一份常量生成**——锚点靠文件名形态判定「是不是本面板预览编辑器」，
        // 名字来源与形态判据同处 [PreviewInlayScope]，两处各自硬编码字面量会在改名时静默打穿锚点
        // （inlay 不报错，只是永不出现）。
        val f = java.io.File(dir, nextPreviewTempFileName())
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
            if (ok) {
                previewTempFilesCreated.add(f)
                return f
            }
        }
        com.intellij.openapi.util.io.FileUtil.writeToFile(f, "")
        previewTempFilesCreated.add(f)
        return f
    }

    /**
     * T-PreviewExecDs-01c：预览文件方言预映射（exec 路径 DataSourceSelector.mapDialectBeforePsi 同款）——
     * PSI 创建时即按映射方言解析，Generic SQL 下 Database 补全不触发。映射匹配与 exec 一致：
     * SqlDialectMappings.getAvailableValues() 里 language.id == "MySQL" 的实例（MyBatis-Plus 预览
     * 场景即 MySQL；插件自定义 Dialect 与 Database 方言映射无现成对应，硬编码与 exec 对齐）。
     * 同对象已映射过即幂等短路（避免每次预览点 setMapping 触发 SqlDialectPusher push all dumb）。
     *
     * T-ExecSlowOps F1：反射段收编进 [DialectPreMapping]，本方法仅保留「面板级进程内幂等短路」语义 +
     * 成功回写 [previewDialectMappedVf]。真机实锤：本方法在 [prewarmPreviewTempFile] 后台线程内调用，
     * 旧实现反射直调 `setMapping` → 平台 `handleMappingChange → reparseFiles → runWriteAction →
     * VcsVFSListener.beforeContentsChange` 的 `assertEventDispatchThread` 硬抛
     * RuntimeExceptionWithAttachments（SEVERE ×3）。DialectPreMapping.ensureMapped 两段式：
     * 稳态（workspace.xml 映射幸存/本进程已设）读探测直接跳过，仅项目首见经 invokeLater 派 EDT 执行
     * 映射并 CountDownLatch 有界等待——映射写归 EDT、慢操作归 BGT，两侧 SEVERE 同消。
     *
     * T-FixN2：映射对象由「文件」改为**父目录**（文件名每次预热唯一后，按文件映射会让每个新文件名
     * 都判「未映射」→ 每轮预热白付一次 setMapping/push all dumb，且 workspace.xml 的
     * SqlDialectMappings 状态按文件路径逐个累积、只增不减）。平台 `PerFileMappingsBase.getMappingInner
     * → getMappingForHierarchy` 自下而上逐级查表（243 sources 实证：`for (cur = file; cur != null;
     * cur = cur.getParent())`），故**目录映射被其下所有文件继承**，一次映射覆盖全部预览文件
     * （含每次新名字）；[previewDialectMappedVf] 随之记「已映射的那个目录 vf」，
     * 身份判定（幂等短路）比较的是目录——目录 vf 在 VFS 里按路径唯一且本面板不删目录，跨预热稳定。
     * 目录 vf 取不到（极端）时退回按文件映射，语义同改动前。
     */
    private fun mapPreviewDialectBeforePsi(proj: Project, vf: com.intellij.openapi.vfs.VirtualFile) {
        val target = previewDialectMappingTarget(vf)
        val inProcessMapped = previewDialectMappedVf?.isValid == true && previewDialectMappedVf === target
        if (cn.song.mybatisplus.visualizer.execute.DialectPreMapping.ensureMapped(proj, target, inProcessMapped)) {
            previewDialectMappedVf = target
        }
    }

    /**
     * T-PerfTail #2：预热产物消费决策（替代原 `awaitPrewarmedTempFile` 的 EDT sleep-poll）。
     *
     *  - **热态（结果已出）**：同步取产物装配——主路径（成功）或快速降级（失败，原 prewarmFailed
     *    快速返回语义），EDT 零等待；
     *  - **冷态（预热线程未完成）**：`scheduleOnce` 注册完成回调，超时预算语义保留
     *    （PrewarmAwait 内 completeOnTimeout，预算=PREWARM_WAIT_MS），回调统一回 EDT 装配——
     *    EDT 零阻塞，超时/失败走降级链（T-PreviewExecDs 三级降级语义保留）；
     *  - **单元测试模式**：无真实 EDT 泵，为保留既有测试「showResult 返回时编辑器即就绪」语义，
     *    退回同步有界等待（awaitOrNullForTest，生产路径不走）。
     *
     * @return 产物（热态主路径非 null）；null = 本调用不装配（回调挂起中）或快速降级。
     *         消费装配由调用方按 `assembled` 标志区分。
     */
    private fun resolvePreviewEditorSource(sql: String): Pair<Boolean, Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>?> {
        val await = prewarmAwait
        if (await == null) return true to null
        if (await.isDone()) return true to await.joinOrNull()
        if (ApplicationManager.getApplication()?.isUnitTestMode == true) {
            return true to await.awaitOrNullForTest()
        }
        // 冷态：注册回调（幂等），本次渲染不装配（编辑器挂起至回调完成，≤ 超时预算）
        await.scheduleOnce(
            onReady = { pair ->
                if (!previewDisposed) assemblePreviewEditor(sql, pair)
            },
            onFallback = {
                if (!previewDisposed) assemblePreviewEditor(sql, null)
            },
        )
        return false to null
    }

    /** T-DarkTheme：主题变更回调——重绘面板（命名色已自动更新，仅需触发 UI 刷新）。 */
    private fun lookAndFeelChanged() {
        revalidate()
        repaint()
    }

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
    internal fun showRollbackSql() {
        val sql = currentEditorSql()?.let { SqlCommentStripper.strip(it) }?.takeIf { it.isNotBlank() } ?: return
        val kind = SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL"
        // T-ST-03 核实：本路径是【回滚SQL】按钮的独立动作（工具栏 :687），**不在执行确认流内**——
        // 此处 generate 是该次点击的唯一一次解析（确认流的解析点在 executeSql/executeAdhocSql 的
        // ExecuteConfirmMessages.build 处），故无「确认阶段产物」可显式传入。
        val result = cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.generate(sql)
        val entry = project?.getService(cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore::class.java)
            ?.findBySql(sql)
        if (result.rating == cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.Rating.UNABLE &&
            result.snapshotSelectSql == null
        ) {
            val proj = project ?: return
            val reasons = result.warnings.joinToString("\n") { warning ->
                if (warning.messageArgs.isEmpty()) MpSqlVisualizerBundle.message(warning.messageKey)
                else MpSqlVisualizerBundle.message(warning.messageKey, *warning.messageArgs.toTypedArray())
            }
            Messages.showInfoMessage(
                proj,
                reasons,
                MpSqlVisualizerBundle.message("rollback.dialog.title"),
            )
            return
        }
        RollbackSqlDialog(project, this, result, kind, entry).show()
    }

    /**
     * O-91（缩范围首版）：当前预览的 CRUD SQL + 回滚链产物串成可移植脚本导出。
     *
     * 语句本体不改写（生成侧方言降级链已保证「标准语法优先」），导出只串联＋标注：
     * 每条语句前 `-- dialect: X | kind: CRUD/ROLLBACK | upsert: 形态或 no` 注释头；
     * 格式走 R4 既有三格式通道（[cn.song.mybatisplus.visualizer.log.SqlExportService.formatPortable]）。
     * 回滚链诚实拒绝（UPDATE/DELETE 无 before-image、UPSERT 三形态等）时导出面不造回滚行
     * （与 [showRollbackSql] 的 UNABLE 弹说明同口径，不降级造半成品）。
     * 写盘在后台线程（对齐 SqlLogPanel R4 导出流程）。
     */
    internal fun exportPortableSql() {
        val sql = currentEditorSql()?.let { SqlCommentStripper.strip(it) }?.takeIf { it.isNotBlank() } ?: return
        val dialect = current?.previewModel?.dialect ?: MySqlDialect
        val rows = cn.song.mybatisplus.visualizer.log.PortableSqlExport
            .collectRows(sql, dialect, current?.batchModel)
        if (rows.isEmpty()) return
        val format = choosePortableExportFormat() ?: return
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
            MpSqlVisualizerBundle.message("panel.portableExport"),
            "SQL (${format.ext.uppercase()})",
            format.ext,
        )
        val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
        val saved = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "sql-portable.${format.ext}")
            ?: return
        val target = saved.file
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                cn.song.mybatisplus.visualizer.log.SqlExportService
                    .exportToFile(target, cn.song.mybatisplus.visualizer.log.SqlExportService.formatPortable(rows, format))
            }.onSuccess {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("log.history.export.done", rows.size, target.absolutePath),
                        com.intellij.notification.NotificationType.INFORMATION,
                    ).notify(project)
            }.onFailure {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("log.history.export.failed", it.message ?: ""),
                        com.intellij.notification.NotificationType.ERROR,
                    ).notify(project)
            }
        }
    }

    /** O-91：可移植导出格式三选（取消返回 null；形态对齐 SqlLogPanel R4 同款对话框）。 */
    private fun choosePortableExportFormat(): cn.song.mybatisplus.visualizer.log.SqlExportFormat? {
        val names = arrayOf("CSV", "TSV", "TXT")
        val idx = com.intellij.openapi.ui.Messages.showChooseDialog(
            project,
            MpSqlVisualizerBundle.message("log.history.export.choose"),
            MpSqlVisualizerBundle.message("panel.portableExport"),
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
            names,
            names[0],
        )
        if (idx < 0) return null
        return when (idx) {
            0 -> cn.song.mybatisplus.visualizer.log.SqlExportFormat.CSV
            1 -> cn.song.mybatisplus.visualizer.log.SqlExportFormat.TSV
            else -> cn.song.mybatisplus.visualizer.log.SqlExportFormat.TXT
        }
    }

    /**
     * T-RollbackSql F4：临时 SQL 执行（回滚弹窗专用）——复用 [executeSql] 的既有管线
     * （分类 → DML 二次确认 → isPrepared 快路径/全链），T-RefreshDbSteal F4 所有权卫队
     * 对这条路径天然生效；执行完毕结果反馈走既有 [resultPanel]。
     *
     * 刻意不走 [cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute] 从弹窗直调全链：
     * 全链建临时 .sql 并 attachConsole，可能把正嵌在预览面板里的共享控制台偷走。
     *
     * @param dmlConfirmed    DML 二次确认豁免（回滚弹窗「执行回滚」已过用户显式确认链）
     * @param rollbackOfEntry 本次执行本身是回滚语句时指向被回滚的原快照条目（F6 留痕链）
     */
    internal fun executeAdhocSql(
        sql: String,
        dmlConfirmed: Boolean = false,
        rollbackOfEntry: cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry? = null,
    ) {
        val proj = project ?: return
        if (sql.isBlank()) return
        val isQuery = SqlTypeClassifier.isQuery(sql)
        // O-64/T-RollbackIdentityParse（P19/D2=A）：identity 包装产物整批直跑。识别特征=
        // 含 `SET IDENTITY_INSERT … ON|OFF` 语句（本插件自产回滚批次的封闭形态）；普通多语句
        // 产物保持现态（Statements 选择层默认行为不变），避免行为突变。生产入口仅本函数
        // （回滚弹窗「执行回滚/执行快照查询」），executeSql 的用户编辑器执行链不接旗标。
        val wholeScriptExec = cn.song.mybatisplus.visualizer.sql.IdentityInsertStatements.containsStatement(sql)
        if (!isQuery && !dmlConfirmed) {
            val keyword = SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL"
            val confirm = Messages.showYesNoDialog(
                proj,
                cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(sql, keyword),
                MpSqlVisualizerBundle.message("execute.confirm.dml"),
                Messages.getWarningIcon(),
            )
            if (confirm != Messages.YES) return
        }
        // P2-F5/F6：经执行按钮执行的 UPDATE/DELETE 执行前登记快照条目（回滚语句登记留痕链）
        registerRollbackSnapshotIfNeeded(sql, rollbackOfEntry)

        resultPanel.showLoading()
        if (SqlExecutor.isPrepared()) {
            if (!consoleAttachedToMe()) {
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                    .warn("[TRollbackSql] executeAdhocSql: prepared console detached → recover before execute")
                recoverConsole(sql)
                if (SqlExecutor.isPrepared() && !consoleAttachedToMe()) {
                    SqlExecutor.execute(
                        proj, sql, null, isQuery,
                        onConsole = { consoleComponent -> embedConsole(consoleComponent, previewMode = true) },
                        onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
                        onError = { errorMsg -> resultPanel.showError(errorMsg) },
            onErrorRich = { info -> showErrorRichWithLocate(info) },
                        wholeScriptExec = wholeScriptExec,
                    )
                    return
                }
            }
            // 快路径同步执行 SQL 进控制台文档（executePrepared 执行的就是控制台文档；
            // 回滚执行后下一次 renderSql 会按预览 SQL 同步回来）
            nativeEditorDoc?.let { doc ->
                if (doc.text != sql) {
                    WriteCommandAction.runWriteCommandAction(proj) { doc.setText(sql) }
                }
            }
            val executed = SqlExecutor.executePrepared(
                proj,
                onResultsReady = { fullConsole -> embedConsole(fullConsole) },
                onError = { errorMsg -> resultPanel.showError(errorMsg) },
            onErrorRich = { info -> showErrorRichWithLocate(info) },
                wholeScriptExec = wholeScriptExec,
            )
            if (executed) return
        }
        SqlExecutor.execute(
            proj, sql, null, isQuery,
            onConsole = { consoleComponent -> embedConsole(consoleComponent, previewMode = true) },
            onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
            onError = { errorMsg -> resultPanel.showError(errorMsg) },
            onErrorRich = { info -> showErrorRichWithLocate(info) },
            wholeScriptExec = wholeScriptExec,
        )
    }

    /**
     * T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目（内存 store，LogSqlEntry 不动字段）。
     * 回滚语句（rollbackOfEntry 非 null）也登记——parentExecutionId 指向被回滚的原执行，
     * 形成 dbx `rollback_of` 语义的留痕链；仅登记可解析到目标表的语句（解析失败登记无意义）。
     *
     * **T-PS5-02 / T-ST-03 解析复用前提（实测核实，勿默认撤销）**：下面 [RollbackSqlGenerator.generate]
     * 走 `UnboundedWriteDetector.parseStatementsShared` 的 AST 单槽，与确认阶段
     * （[executeSql]/[executeAdhocSql] 的 `ExecuteConfirmMessages.build` → `UnboundedWriteDetector.detect`）
     * 共用同一次解析——命中判据是**同文本内容全等**，故此处**依赖两条前提**：
     * ①确认阶段确实以同一文本走了一遍 `detect`（UPDATE/DELETE 必经 DML 二次确认，成立；
     * **豁免参数路径** `executeAdhocSql(dmlConfirmed = true)` 不走确认弹窗、退化为本链自解析一次——
     * 但该参数**现行生产无调用点**（`RollbackSqlDialog.executeRollback` 未传它，回滚弹窗同样走确认弹窗），
     * 仅由测试覆盖，故此退化目前只存在于测试路径）；
     * ②[SqlCommentStripper.strip] 幂等——此处传入的是 `strip(sql)`，而 `detect` 内部键是 `strip(sql)`、
     * `generate` 内部还会再剥一次（键成 `strip(strip(sql))`），幂等成立才落回同一槽。
     * 失效后果分档：**前提①失效只是多一次解析**（槽未命中即重解析同一文本，结果不变）；
     * **前提②失效则不同**——命中路径用单剥文本的 AST、未命中路径解析双剥文本，两条路径输入文本不同、
     * 结果可能随缓存命中状态而变（故由 `SqlPreviewPanelRollbackParseShapeTest` 的「独立 vs 共享逐字节一致」兜底）。
     * 前提由该测试类逐条钉住（含「槽未命中必重解析」的探针自证）。
     *
     * 面板侧若需脱离该隐式前提，出口是 `RollbackSqlGenerator.generateFromParsedStatements(cleaned, statements, pk)`；
     * **T-ST-03 独立复核订正**：理由原写「真正显式传递须改 `execute/` 包的两个共享 API」——不是唯一路线：
     * 面板侧**自取槽变体**（`parseStatementsShared(cleaned)` → `generateFromParsedStatements`）从同一个 `sql`
     * 变量算槽键，能**整条换掉前提②**（由 `strip` 的纯函数性保证，与幂等无关），且两个 API 都是 `internal`、
     * 与 ui 包同 Gradle 模块，无需改签名。**但结论仍是不实施**：工具栏路径 [showRollbackSql] 无前置 detect、
     * 生产仍按双剥文本出结果，幂等前提**换不掉**；且收益为零性能/零语义（T-PS5-02 的收益已由单槽全数拿到），
     * 属计划明标的 P4 可选件。
     */
    private fun registerRollbackSnapshotIfNeeded(
        sql: String,
        rollbackOfEntry: cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry?,
    ) {
        val proj = project ?: return
        val keyword = SqlTypeClassifier.getFirstKeyword(sql) ?: return
        if (keyword != "UPDATE" && keyword != "DELETE") return
        val store = proj.getService(cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore::class.java)
        // O-59 T-IIR-01：把**当前生效方言**随快照登记进去——取的是 [SqlPreviewService.currentDialect]
        // （= `DialectRegistry.effective(project)`，与预览/执行链同源，含 T-DialectAutoInfer 的数据源
        // 推断，不新造解析）。此前两处登记点都不传该参数 → `SnapshotEntry.dialect` 恒 null →
        // `RollbackSqlGenerator.backfillInsert` 的 `wrapIdentity` 恒假 → 回滚侧 identity 包装在
        // **生产链上永不触发**（T-Round10 独立复核 blocker②：用户在 SQL Server 上 DELETE 后粘贴快照
        // 生成回填 INSERT 仍报 544）。取不到时保持 null（= 未知不包装，O-52 D1=A 契约不变）。
        val dialect = proj.getService(SqlPreviewService::class.java).currentDialect()
        if (rollbackOfEntry != null) {
            store.register(
                sql, rollbackOfEntry.targetTable, null,
                parentExecutionId = rollbackOfEntry.executionId,
                dialect = dialect,
            )
            return
        }
        // T-PS5-02：传入已剥注释文本——键对齐靠 strip 幂等（详见本函数 KDoc 的两条前提）
        val analyzed = cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.generate(SqlCommentStripper.strip(sql))
        val table = analyzed.targetTable ?: return
        store.register(sql, table, analyzed.snapshotSelectSql, analyzed.setColumns, dialect = dialect)
    }

    /** 当前编辑器 SQL 文本（原生编辑器文档优先，兜底自研编辑器；两处皆空 null）。 */
    private fun currentEditorSql(): String? =
        nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
            ?: editor?.document?.text?.takeIf { it.isNotBlank() }

    /**
     * T-InjectionGate T-IG-02：`${}` 原文拼接段的执行确认闸门（[executeSql]/[explainSql] 共用）。
     *
     * 判定信号取自 [PreviewResult.hasDollarRawSegments]（解析期 `${` token 收集的渲染期事实，
     * 不是最终文本 regex——`${}` 成功替换为模拟值后文本里已无痕迹，regex 会漏掉全部成功场景）。
     * 含 raw 段且本面板未确认过 → 弹确认框（复用 DML 二次确认同款 YesNo 形态与 warning 图标，
     * 标题同 [MpSqlVisualizerBundle] 的 `execute.confirm.dml`，消息说明 raw 段当前填的是模拟值）；
     * 用户选 NO → 中止；选 YES → 记会话级记忆，本面板后续执行不再重复弹。
     *
     * 范围：只在 XML/注解预览链生效（Wrapper/JPA/日志还原链无 `${}` 概念，该字段恒 false）。
     *
     * @return true = 放行（无 raw 段 / 已确认过 / 本次确认通过）
     */
    private fun confirmDollarRawSegments(proj: Project): Boolean {
        if (current?.hasDollarRawSegments != true) return true
        if (dollarRawConfirmedInSession) return true
        val confirm = Messages.showYesNoDialog(
            proj,
            MpSqlVisualizerBundle.message("execute.confirm.dollarRaw.message", DOLLAR_TOKEN),
            MpSqlVisualizerBundle.message("execute.confirm.dml"),
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return false
        dollarRawConfirmedInSession = true
        return true
    }

    /**
     * T-PC2-04（D1=B 定案）：含「签名类型未能识别」参数的语句执行确认闸门
     * （[executeSql]/[explainSql] 共用，形态与 [confirmDollarRawSegments] 同款）。
     *
     * 判定信号取自 [XmlPreviewModel.unknownParamSources]（`paramTypes` 有条目但类别 UNKNOWN：
     * 枚举 / `boolean` / `byte[]` 等——类型读到了却映射不到类别，绑定层只能以字面 `?` 提交）。
     * **无类型信息不计入**（mapper 接口不可解析时键不在 `paramTypes`）——与 T-PT-02「无类型信息
     * 不提示」同向，也避免「接口解析不到 → 每次执行都提示」的噪音面（口径见该函数 KDoc）。
     *
     * 预览链**保持放行**（D1=B：预览与执行风险面不同），只在执行/EXPLAIN 前提示一次；用户选 NO
     * → 中止；选 YES → 记会话级记忆，本面板后续执行与 EXPLAIN 都不再重复弹（不阻断已确认流程）。
     * 范围：只在 XML 预览链生效（Wrapper/JPA/日志链无 `xmlModel`，恒放行）。
     *
     * @return true = 放行（无 UNKNOWN 参数 / 已确认过 / 本次确认通过）
     */
    private fun confirmUnknownParams(proj: Project): Boolean {
        val unknown = current?.xmlModel
            ?.unknownParamSources(current?.unresolvedParams.orEmpty())
            .orEmpty()
        if (unknown.isEmpty() || unknownParamsConfirmedInSession) return true
        val confirm = Messages.showYesNoDialog(
            proj,
            MpSqlVisualizerBundle.message("execute.confirm.unknownParams.message", unknown.size),
            MpSqlVisualizerBundle.message("execute.confirm.dml"),
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return false
        unknownParamsConfirmedInSession = true
        return true
    }

    /**
     * T-PSG-04（N-33）：`?` 位点数与参数条数对账的**非阻断**标注。
     *
     * 结论由 [backfillParamsBySource] 在回填时算出（串感知位点数 vs 参数条数），此处排在两道
     * 确认门之后、快照登记/执行之前读走并如实登记日志——不弹窗、不中止（我方既有档位是确认/标注，
     * 不是拒绝）。产物侧的同款对账标注见 `CopyExecutableSqlAction` 的头部标记。
     */
    private fun warnOnPlaceholderMismatch() {
        val mismatch = placeholderReconciliation ?: return
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .warn("[T-PSG-04] executeSql: $mismatch")
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
                cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(sql, keyword),
                MpSqlVisualizerBundle.message("execute.confirm.dml"),
                Messages.getWarningIcon(),
            )
            if (confirm != Messages.YES) return
        }
        // T-InjectionGate T-IG-02：`${}` raw 拼接段确认门（DML 分类判定之后追加——预览产物里的
        // raw 段带的是模拟值，执行前须用户确认值真实性；NO 直接中止，不落快照不执行）
        if (!confirmDollarRawSegments(proj)) return
        // T-PC2-04（D1=B）：签名类型未识别参数确认门（D1=B 的「执行/EXPLAIN 前提示一次」——
        // 预览链保持放行；排在 `${}` 门之后、快照登记/执行之前，NO 直接中止）
        if (!confirmUnknownParams(proj)) return
        // T-PSG-04（N-33）：`?` 位点数与参数条数对账——与上面两道确认门同址，但**不阻断**
        // （不等时只如实登记日志；产物侧的标注见 CopyExecutableSqlAction 头部标记形态）
        warnOnPlaceholderMismatch()
        // T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目（回滚弹窗据此提供粘贴回填工作流）
        registerRollbackSnapshotIfNeeded(sql, null)

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
                        onErrorRich = { info -> showErrorRichWithLocate(info) },
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
                onErrorRich = { info -> showErrorRichWithLocate(info) },
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
            onErrorRich = { info -> showErrorRichWithLocate(info) },
        )
    }

    /**
     * T-ErrorLocate T-ELU-01：执行调用点共用的富错误回调——**先解析锚点、再决定按钮显隐**。
     *
     * 诚实降级定案：驱动报错位置（Position/at line）指向**被执行 SQL 文本**的字符/行，
     * 与 mapper 原文行不一一对应（动态 SQL 拼接、参数注入后行号必然错位），v1 只承诺
     * 「语句起始行」跳转 + 按钮 tooltip 如实说明，绝不虚标精确跳转。
     * 锚点来自 [SqlPreviewService.lastPreviewSourceAnchor]（预览触发时登记）。
     */
    private fun locateErrorSource(proj: com.intellij.openapi.project.Project, anchor: SqlPreviewService.SourceAnchor) {
        runCatching {
            com.intellij.openapi.fileEditor.OpenFileDescriptor(proj, anchor.file, anchor.line0, 0).navigate(true)
        }
    }

    /**
     * T-ErrorLocateUsability T-ELU-01（O-49）：**先解析锚点，再决定是否给按钮**。
     *
     * 缺陷（KDoc 与实现不符）：[SqlResultPanel.showErrorRich] 按 `locate != null` 决定按钮显隐，
     * 而唯一调用方此前**恒传非 null lambda**（锚点解析发生在点击时，`project ?: return` /
     * `anchor ?: return` 静默返回）→ **按钮可见，点了什么都不发生**。现把解析提到显隐之前：
     * 锚点解析不出（无项目上下文 / 本会话尚未登记锚点 / 源文件已失效）→ 传 null，按钮不出现。
     *
     * 对齐同仓已做对的先例 [cn.song.mybatisplus.visualizer.log.SqlLogPanel]（先解析、后显隐）。
     * 顺带把「文件已失效」也算作不可解析——跳不动的按钮不给，比给了点不动诚实。
     */
    private fun errorLocateAction(): (() -> Unit)? {
        val proj = project ?: return null
        val anchor = runCatching { proj.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor }
            .getOrNull() ?: return null
        if (!anchor.file.isValid) return null
        return { locateErrorSource(proj, anchor) }
    }

    /** T-ErrorLocate：执行调用点共用的富错误回调（错误呈现 + **锚点可解析时**才附定位动作）。 */
    private fun showErrorRichWithLocate(info: cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo) {
        resultPanel.showErrorRich(info, errorLocateAction())
        maybeProbeDdlEffective(info)
    }

    /**
     * T-DdlRecover（O-15）T-DR-02：「失败不等于未生效」——执行 DDL 撞连接类断开时，数据库侧
     * 可能已隐式提交。判定链：连接类异常（SQLState 08xxx / vendor 码 / 签名兜底）∧ 可能改元数据
     * （MetadataInvalidator 既有预判复用）→ 提取 CREATE 目标表名 → 后台退避探测（500ms/1.5s/3s
     * 三次，等 DBN 自动重连窗口）→ 命中呈现警示条、未命中 tooltip 如实注记。
     * 探测/提取任一失败维持原错误呈现（诚实降级）；警示无新弹窗（弹窗克制红线）。
     */
    private fun maybeProbeDdlEffective(info: cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo) {
        val proj = project ?: return
        if (!cn.song.mybatisplus.visualizer.execute.ConnectionErrorClassifier.isConnectionError(info)) return
        val sql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
            ?: editor?.document?.text?.takeIf { it.isNotBlank() } ?: return
        if (!cn.song.mybatisplus.visualizer.execute.MetadataInvalidator.mayChangeMetadata(sql)) return
        val target = cn.song.mybatisplus.visualizer.execute.DdlTargetExtractor.extractCreateTarget(sql) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            var hit = false
            for (delayMs in longArrayOf(500, 1500, 3000)) {
                Thread.sleep(delayMs)
                val meta = runCatching {
                    cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.tableMeta(proj, target)
                }.getOrNull() ?: break
                if (meta.comment != null || meta.columns.isNotEmpty()) {
                    hit = true
                    break
                }
            }
            val probedAt = java.time.LocalTime.now().withNano(0).toString()
            ApplicationManager.getApplication().invokeLater({
                if (hit) {
                    resultPanel.showDdlEffectiveWarning(target, probedAt)
                } else {
                    resultPanel.noteDdlProbeMissed()
                }
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }

    /**
     * M37.2：真实 EXPLAIN——按当前方言把预览 SQL 包装为 EXPLAIN 语句，走执行链提交原生控制台。
     * 结果展示与普通执行一致（内嵌控制台结果 Tab）；静态性能规则标注保留在顶部 meta 区，同屏对照。
     * EXPLAIN 归免确认类（[SqlTypeClassifier]），无 DML 二次确认；方言不支持（null）→ 提示降级。
     *
     * T-ExplainChainHardening T-ECH-01（O-48，D1=A 严格判定）：包装前先判**被包语句**的首关键字
     * 是否落在 [ExplainGate] 白名单——此前对编辑器里的任意文本加前缀（`DELETE FROM t`、
     * `CREATE TABLE …`、标识符 `SELECTED` 一律被包成 EXPLAIN 送进库），现改为明确提示、不执行。
     * 判据只看「未包装」的文本：已以 EXPLAIN 开头的文本是本插件上一次的包装产物（T-ExplainDbl
     * 幂等场景），不适用白名单，否则二次点击会被误拒。
     */
    internal fun explainSql() {
        val proj = project ?: return
        val sql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
            ?: editor?.document?.text?.takeIf { it.isNotBlank() } ?: return
        val dialect = current?.previewModel?.dialect ?: MySqlDialect
        // T-ECH-01：提交判定（幂等 → 首关键字白名单 → 方言支持面），两类拒绝理由分别提示
        val explainSql = when (val plan = ExplainGate.planExplainSubmit(dialect, sql)) {
            is ExplainGate.SubmitPlan.NotExplainable -> {
                notifyExplainNotExplainable(proj, plan.firstKeyword)
                return
            }
            is ExplainGate.SubmitPlan.DialectUnsupported -> {
                // T-UiNative T-UN-02：方言不支持提示改轻通知（原模态弹窗——纯告知无需打断）
                if (!ApplicationManager.getApplication().isUnitTestMode) {
                    runCatching {
                        Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("panel.explain"),
                            MpSqlVisualizerBundle.message("panel.explain.unsupported", plan.dialectName),
                            NotificationType.INFORMATION,
                        ).notify(proj)
                    }
                }
                return
            }
            is ExplainGate.SubmitPlan.Submit -> {
                // R23：二段式方言（Oracle）的计划不在本次语句的返回行里——提交前轻通知告知另查路径
                //（描述的是方言固有形态，不预设执行成败；SQL Server 无 postPlanQuery，控制台多结果页签自明）
                cn.song.mybatisplus.visualizer.sql.ExplainSupport.entryFor(dialect.name)?.postPlanQuery?.let { followUp ->
                    if (!ApplicationManager.getApplication().isUnitTestMode) {
                        runCatching {
                            Notification(
                                NOTIFICATION_GROUP_ID,
                                MpSqlVisualizerBundle.message("panel.explain"),
                                MpSqlVisualizerBundle.message("panel.explain.planRegistered", followUp),
                                NotificationType.INFORMATION,
                            ).notify(proj)
                        }
                    }
                }
                plan.statement
            }
        }
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M37.2] explainSql: dialect=${dialect.name}, len=${explainSql.length}")
        // T-InjectionGate T-IG-03：EXPLAIN 本就真实执行——`${}` raw 段同门控（旁路不得绕开执行闸门）；
        // 方言不支持（上方已 return，未执行）不弹
        if (!confirmDollarRawSegments(proj)) return
        // T-PC2-04（D1=B）：EXPLAIN 同门控（旁路不得绕开执行闸门，与 T-IG-03 同款姿态）
        if (!confirmUnknownParams(proj)) return
        // T-PSG-04（N-33）：对账非阻断标注与 executeSql 同址同款（EXPLAIN 也是执行旁路）
        warnOnPlaceholderMismatch()
        // R33：面板 EXPLAIN 双通道分派（结构化渲染为增量——判定/执行/渲染任一环不成立都
        // fail-closed 回退直送通道，双通道并存不替换既有行为，见 submitExplainOrStructured）
        // 代际先递增（所有路径）——作废前一次提交的在途结构化回调
        explainStructuredGeneration++
        submitExplainOrStructured(proj, dialect, explainSql)
    }

    /**
     * R33：EXPLAIN **直送通道**（既有行为原样收敛）——包装语句交原生 Database 控制台执行，
     * 结果区内嵌控制台（embedConsole）。结构化通道的初始回退与「回到原生控制台」入口都复用
     * 本函数：直送链只此一份，绝不新增第二套执行链。
     */
    private fun submitExplainDirect(proj: Project, explainSql: String) {
        resultPanel.showLoading()
        SqlExecutor.execute(
            proj, explainSql, null, true,
            onConsole = { consoleComponent -> embedConsole(consoleComponent, previewMode = true) },
            onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
            onError = { errorMsg ->
                resultPanel.showError(errorMsg)
            },
            onErrorRich = { info -> showErrorRichWithLocate(info) },
        )
    }

    /**
     * R33：面板 EXPLAIN 双通道分派（自建执行链路线，批次 9 核实报告 §三 V41 定案）。
     *
     * 判定（[ExplainPanelChannel.decide]，纯逻辑）：开关开 ∧ 输出格式 ∈ {TABLE, JSON}
     * ∧ database 插件在场 ∧ 预览会话已绑数据源 → 结构化通道；开关关/其余方言（TEXT/XML，
     * 含 R23 Oracle 二段式与 SQL Server XML——不硬解析红线）→ 设计内直送（静默）；无数据源/
     * 无插件 → fail-closed 回退直送＋一条轻提示（不弹窗）。执行/渲染异常同样回退直送——
     * **结构化通道绝不成为 EXPLAIN 的唯一路径**。
     *
     * 数据源取当前预览会话已绑数据源（循 T-ConsoleDbContext 既有选择形态：预览用哪条
     * 数据源，EXPLAIN 就在同一条上跑）；JDBC 全程 pooled 线程（禁 EDT），结果经 EDT 回调
     * 换入结构化视图，代际计数防过期回调覆盖新预览。
     */
    private fun submitExplainOrStructured(proj: Project, dialect: cn.song.mybatisplus.visualizer.sql.Dialect, explainSql: String) {
        val settings = PluginSettings.getInstance().state
        val boundDs = runCatching {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource()
        }.getOrNull()
        val format = cn.song.mybatisplus.visualizer.sql.ExplainSupport.formatFor(dialect.name)
        // R33 真机走查修复：编辑器文本就是回填链的终点产物（参数击键防抖重渲染），此处残留
        // `?`（串感知位点，字符串/注释里的 `?` 不计）即真未填——不下发结构化探测（字面 `?`
        // 送 JDBC = 语法错），按响亮失败回退并明示「存在未回填参数」
        val hasUnfilledParams = placeholderPositions(explainSql).isNotEmpty()
        when (
            val verdict = cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.decide(
                enabled = settings.explainStructuredRender,
                format = format,
                databaseAvailable = cn.song.mybatisplus.visualizer.mcp.McpDbExecutor.isDatabaseAvailable(),
                hasBoundDataSource = boundDs != null,
                hasUnfilledParams = hasUnfilledParams,
            )
        ) {
            cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.DirectSilent ->
                submitExplainDirect(proj, explainSql)

            is cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.DirectFallback -> {
                notifyExplainStructuredFallback(proj, verdict.reason, detail = null)
                submitExplainDirect(proj, explainSql)
            }

            cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.Structured -> {
                val ds = boundDs
                val outFormat = format
                // 防御：判定与取值之间环境可能变化（数据源失效等）——缺一即回退，不硬解包
                if (ds == null || outFormat == null) {
                    submitExplainDirect(proj, explainSql)
                    return
                }
                resultPanel.showLoading()
                val generation = explainStructuredGeneration
                cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.executeStructured(
                    proj, ds, explainSql,
                    timeoutSeconds = settings.executionTimeoutSeconds,
                    onPlan = { outcome ->
                        if (com.intellij.openapi.util.Disposer.isDisposed(this) || generation != explainStructuredGeneration) {
                            return@executeStructured
                        }
                        // 索引建议联动（ExplainIndexAdvisor 规则集的面板侧消费——只提示不执行；
                        // 建议器异常不连坐计划渲染，降级为无建议）
                        val hints = runCatching {
                            cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.advise(
                                dialect.name, outcome.raw.headers, outcome.raw.rows,
                            )
                        }.getOrDefault(emptyList())
                        embedExplainView(
                            ExplainPlanResultView(
                                dialectName = dialect.name,
                                dataSourceName = outcome.dataSourceName,
                                elapsedMs = outcome.elapsedMs,
                                raw = outcome.raw,
                                format = outFormat,
                                hints = hints,
                                onBackToConsole = { submitExplainDirect(proj, explainSql) },
                            ),
                        )
                        resultPanel.showIdle()
                    },
                    onFailed = { errorText ->
                        if (com.intellij.openapi.util.Disposer.isDisposed(this) || generation != explainStructuredGeneration) {
                            return@executeStructured
                        }
                        notifyExplainStructuredFallback(proj, null, detail = errorText)
                        submitExplainDirect(proj, explainSql)
                    },
                )
            }
        }
    }

    /**
     * R33：结构化 EXPLAIN 视图换入结果区（consoleHost 同位换入，循 embedConsole 的宿主形态；
     * 先 detachConsole 解绑旧控制台的 rehost 监听——残活会把控制台抢回已废弃面板，6 连抢实锤）。
     * 下一次控制台嵌入/预览/清空都会自然替换本视图，双通道并存不互斥。
     */
    private fun embedExplainView(component: JComponent) {
        detachConsole()
        editorHolder.removeAll()
        editorHolder.add(consoleHost, java.awt.BorderLayout.CENTER)
        consoleHost.removeAll()
        consoleHost.add(component, java.awt.BorderLayout.CENTER)
        component.border = JBUI.Borders.empty()
        revalidate()
        repaint()
    }

    /**
     * R33：结构化通道回退轻提示（fail-closed 姿态的一部分——落回直送通道并告知原因，
     * 不弹窗打断）。[reason] 非 null = 环境性回退（无数据源/无插件）；null = 执行失败，
     * [detail] 携带驱动错误文本。
     */
    private fun notifyExplainStructuredFallback(
        proj: Project,
        reason: cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason?,
        detail: String?,
    ) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            val why = when (reason) {
                cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.NO_DATA_SOURCE ->
                    MpSqlVisualizerBundle.message("panel.explain.structured.reason.noDataSource")
                cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.NO_DATABASE_PLUGIN ->
                    MpSqlVisualizerBundle.message("panel.explain.structured.reason.noDatabasePlugin")
                cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.UNFILLED_PARAMS ->
                    MpSqlVisualizerBundle.message("panel.explain.structured.reason.unfilledParams")
                null -> detail ?: ""
            }
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("panel.explain"),
                MpSqlVisualizerBundle.message("panel.explain.structured.fallback", why),
                NotificationType.INFORMATION,
            ).notify(proj)
        }
    }

    /**
     * T-ExplainChainHardening T-ECH-01（O-48）：被包语句不可 EXPLAIN 时的轻通知（同
     * [panel.explain.unsupported] 形态——纯告知无需打断，不新增弹窗）。文案给出**判定依据**
     * （实际首关键字）而不只是结论，用户据此能自己改语句。
     */
    private fun notifyExplainNotExplainable(proj: com.intellij.openapi.project.Project, keyword: String?) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("panel.explain"),
                MpSqlVisualizerBundle.message(
                    "panel.explain.notExplainable",
                    keyword ?: MpSqlVisualizerBundle.message("panel.explain.notExplainable.unknown"),
                ),
                NotificationType.INFORMATION,
            ).notify(proj)
        }
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
        // T-DbSchemaCompletion：控制台就绪 = 数据源已 attach/建连，此刻的 schema 预热最容易命中
        //（预览打开常早于建连，那时 getModel() 还是 null）
        current?.let { prefetchSchemaForCompletion(it) }
        // T-DbSchemaCompletion：同一窗口下 JOIN 图也一样——首次富化空手（模型未加载）时补刷一次，
        // 否则那张图会永远停在实体注释上（库里有 COMMENT，用户看到的是"JOIN 图没有字段注释"）。
        // 只补一次：target 命中即置空；富化确实拿到数据时从不登记，不会无谓重建图。
        val retryTarget = current
        if (retryTarget != null && diagramEnrichRetryTarget === retryTarget) {
            diagramEnrichRetryTarget = null
            updateDiagramTab(retryTarget)
        }
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
                // T-PreviewFast P7：请求→控制台可见（第三段锚点，用户感知的「预览快不快」）
                runCatching {
                    val req = SqlPreviewService.lastPreviewRequestMs
                    if (req > 0) {
                        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                            .info("[T-PreviewFast] request→console-visible: ${System.currentTimeMillis() - req}ms")
                    }
                }
                // T-PreviewConsoleFmt：控制台挂在这个 exec 文件上（其文档就是该文件文档），而文件
                // 内容由预览链预热写成**预览原文**（裸单行）——挂载后不重渲染，用户看到的就是裸
                // SQL（真机 20260917 日志：showSql createEditor len=274/16 行 → 预热原文 → attach
                // 后控制台显示 1 行）。嵌入完成即按当前渲染文本回填一次；必须排在此处而非
                // onNativeEditorReady —— showSql 的 nativeDoc 分支要求 consoleAttachedToMe()
                // （= embeddedConsole 已进 consoleHost），挂载回调那一刻尚未满足。
                // T-WSC-01：此处回调**会写文档**（renderSql → showSql → document.setText），故必须用
                // write-safe 模态——原 ModalityState.any() 恒 write-unsafe，真机报 Write-unsafe context
                // （见 DocWriteContext KDoc 与计划 20260917-1555 §一）。nonModal 的代价是模态弹窗存续期
                // 回填延后到弹窗关闭——这正是平台对「改文档」要求的语义。
                ApplicationManager.getApplication().invokeLater({
                    if (com.intellij.openapi.util.Disposer.isDisposed(this)) return@invokeLater
                    runCatching { renderSql() }.onFailure { e ->
                        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                            .warn("[M36] post-attach re-render FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }, com.intellij.openapi.application.ModalityState.nonModal())
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
                // write-safe: UI-only（纯 Swing/只读，无文档写）
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
            // write-safe: UI-only（纯 Swing/只读，无文档写）
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
            // write-safe: UI-only（纯 Swing/只读，无文档写）
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
    private fun refreshCompletionContext() {
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
    private fun attachPreviewStatementKind(target: Editor? = editor) {
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
    private fun prefetchSchemaForCompletion(result: PreviewResult) {
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
    private fun buildCompletionContext(): SqlCompletionContext = SqlCompletionContext(
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
    private fun renderSql() {
        // T-WSC-01：写安全自愈闸——renderSql 是面板全部文档写入的唯一上游（showSql 三条分支
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
                val base = rendered.sql
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
     */
    private fun appendCountSection(sql: String): String {
        val result = current ?: return sql
        val countSql = result.countSql ?: return sql
        return StringBuilder(sql)
            .append("\n\n-- ").append(MpSqlVisualizerBundle.message("panel.pagination.count.section")).append("\n")
            .append(countSql)
            .append("\n")
            .toString()
    }

    /** 首次创建：原生 SQL FileType PSI → 文档 → 可编辑编辑器，附补全上下文（Editor + PSI 双载体，M16.2）。 */
    private fun createEditor(sql: String) {
        // T-RefreshDbSteal：createEditor 是症状①的特征终点（自建编辑器带行号，DBN 控制台无），
        // 但真机故障期它零日志（71s 空窗）——带栈埋点定调用人路径，兜底断言非用户路径
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] createEditor: len=${sql.length}, nativeDoc=${nativeEditorDoc != null}, embedded=${System.identityHashCode(embeddedConsole)}", Throwable("createEditor path"))
        val proj = project
        if (proj == null) {
            assemblePreviewEditor(sql, null)
            return
        }
        // T-PerfTail #2：预热产物消费决策——热态同步装配（主路径/快速降级，EDT 零等待）、
        // 冷态注册回调回 EDT 装配（超时预算语义保留，EDT 零阻塞）、单元测试模式同步有界等待
        val (resolved, prewarmed) = resolvePreviewEditorSource(sql)
        if (!resolved) {
            // T-PrewarmBudget：装配延后至预热回调（冷态/预算内未就绪）——这期间编辑区本来是**纯空面板**
            // （真机连拍实测：亮像素计数 0，冷启空窗 584~926ms 肉眼可见），故补一个轻量「准备中」占位；
            // 回调走到 assemblePreviewEditor 时它会 removeAll 掉，无需另行清理。
            showPreviewPreparingPlaceholder()
            return
        }
        assemblePreviewEditor(sql, prewarmed)
    }

    /**
     * T-PrewarmBudget：预热等待期的编辑区占位。
     *
     * 装配延后到预热回调时，编辑区原本是**纯空面板**——真机连拍实测亮像素计数 0，冷启动空窗
     * 584~926ms 肉眼可见。这里放一个居中的轻量提示，回调装配时由 [assemblePreviewEditor] 的
     * `editorHolder.removeAll()` 自然替换，无需另行清理。
     *
     * 用 `GridBagLayout` 居中而非直接 add：`editorHolder` 是 `GridLayout(1,1)`，直接加会把标签拉满
     * 整格（文字贴左上角），与「准备中」应有的视觉不符。
     */
    private fun showPreviewPreparingPlaceholder() {
        val box = JBPanel<JBPanel<*>>(java.awt.GridBagLayout())
        box.add(
            JBLabel(MpSqlVisualizerBundle.message("panel.preview.preparing")),
            java.awt.GridBagConstraints().apply {
                anchor = java.awt.GridBagConstraints.CENTER
                weightx = 1.0
                weighty = 1.0
            },
        )
        editorHolder.removeAll()
        editorHolder.add(box)
        editorHolder.revalidate()
        editorHolder.repaint()
    }

    /** T-PrewarmBudget：等待期占位（断言用）——显示路径的观测口（容器观测口复用既有的 [editorHolderForTest]）。 */
    internal fun showPreparingPlaceholderForTest() = showPreviewPreparingPlaceholder()

    /**
     * T-PerfTail #2：编辑器装配（原 createEditor 后半抽取，同步主路径与异步回调共用同一收口）。
     *
     * @param prewarmed 预热产物（项目内文件 + PSI）；null → 降级链（内存 PSI → 裸 document，
     *   T-PreviewExecDs 降级顺序与诚实降级横幅层级提示保留）。主路径对 `prewarmedTempFile`
     *   做**一次性消费**（同引用校验）——releaseEditor 后二次 createEditor 时产物已被消费，
     *   与原 await 一次性语义一致地走降级。
     */
    private fun assemblePreviewEditor(sql: String, prewarmed: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>?) {
        val proj = project
        val sqlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            .getStdFileType("SQL")
        val doc = if (proj != null) {
            // T-PreviewFlash：createTempFile + refresh + findFile 全在后台线程，
            // EDT 不再触发 WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言（allowSlowOperations(GENERIC)
            // 压不住：SlowOperations.startSection 只在非 EDT 线程压栈，EDT 上该逃生舱是空壳，见开发计划 §3.1 反编译）。
            // T-PreviewDocLock2 F3：临时文件支链「尽力」——产物缺失（预热超时/失败/已消费）时
            // 降级到内存 PSI → 裸文档（功能降级优于硬失败——主路径行为逐字节不变）。
            // T-PreviewDocLock2 F3：`===` 是「本次交付的产物就是字段里那份未被消费的产物」——
            // 实例由 [publishPrewarmProduct] 单次构造保证（发布侧写两遍 `vf to psi` 会让本判定恒假）。
            if (prewarmed != null && prewarmedTempFile === prewarmed) {
                prewarmedTempFile = null
                runCatching {
                    val (vFile, psi) = prewarmed
                    // T-PreviewUx-Pack #2：主路径成功（项目内文件 + PSI 就绪）——清除降级横幅
                    // T-PrewarmLateRecover F2：动作一并摘除（横幅已清，留着会被后续 RENDER_WARNING 继承）
                    degradationBanner.onRetry = null
                    degradationBanner.clear(DegradationBanner.Kind.DEGRADED)
                    // T-PrewarmLateRecover F1：主路径装配 → 当前文档不再属于降级态
                    degradedPreviewDocument = null
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
                }.getOrElse { fallbackPreviewDocument(sql, it.message ?: it.javaClass.simpleName) }
            } else {
                fallbackPreviewDocument(sql, "prewarmed product unavailable (timeout/failed/consumed)")
            }
        } else {
            null
        }
        val created = if (proj != null && doc != null) {
            EditorFactory.getInstance().createEditor(doc, proj, sqlFileType, false)
        } else {
            EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql))
        }
        // M16.2：补全上下文双载体——Editor userData 为主载体（用户输入后 PSI 可能被 commit 重建而丢失 userData，
        // Editor 对象编辑期间稳定）、PSI 文件 userData 作兜底
        val ctx = buildCompletionContext()
        created.putUserData(SqlCompletionContext.KEY, ctx)
        psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
        // T-InsertColumnInlay O-44b：新编辑器同样写语句类型载体（复用路径由 refreshCompletionContext
        // 覆盖，新建路径在此覆盖——两条路径都必须写，漏一条即该路径上 inlay 静默不出现）。
        // 显式传 created：此处 editor 字段仍指向上一个编辑器，默认取字段会写错对象
        attachPreviewStatementKind(created)
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
            // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
            EditorWhitespaceSettings.applyTo(this)
            setPlaceholder(previewEmptyHint) // T-UxOnboard T-UX-02：空态提示原生件（LogToSqlPanel T-UN-14 同款）
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
     * T-PreviewExecDs-01d / T-PerfTail #2：预览文档降级链（第二级 → 第三级）——
     * 第二级 = 内存 PSI（PsiFileFactory.createFileFromText，无 VFS 文件但保留补全上下文载体）；
     * 第三级（兜底）= 纯内存文档编辑器（无 PSI，补全/注释灭）。
     * T-PreviewUx-Pack #2：降级链层级横幅——触发时明示当前层级与恢复指引（不破坏降级顺序）。
     * T-PrewarmLateRecover F1：产出的文档登记为 [degradedPreviewDocument]（迟到产物换装的身份判据）；
     * F2：横幅挂上「重试」动作。
     */
    private fun fallbackPreviewDocument(sql: String, msg: String): com.intellij.openapi.editor.Document {
        // 第二级：内存 PSI（保留补全上下文载体）
        createInMemoryPreviewPsi(sql)?.let { memoryPsiDoc ->
            showDegradedBanner("panel.banner.degradedPsi")
            degradedPreviewDocument = memoryPsiDoc
            return memoryPsiDoc
        }
        // 第三级：裸 document（内存 PSI 也失败）
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .warn("[M36] preview temp file unavailable, fall back to bare in-memory editor: $msg")
        psiFile = null
        showDegradedBanner("panel.banner.degradedPlain")
        return EditorFactory.getInstance().createDocument(sql).also { degradedPreviewDocument = it }
    }

    /**
     * T-PrewarmLateRecover F2：挂降级横幅并接上「重试」动作。
     *
     * 动作覆盖的是 F1 够不到的那一类——预热**硬失败**（VFS refresh 失败 / findFile null /
     * 项目内目录建不出来）：没有产物可等，只能由用户点一次重新臂预热。
     */
    private fun showDegradedBanner(messageKey: String) {
        degradationBanner.onRetry = { retryPrewarm() }
        degradationBanner.show(
            DegradationBanner.Kind.DEGRADED,
            MpSqlVisualizerBundle.message(messageKey),
        )
    }

    /**
     * T-PrewarmLateRecover F1：迟到产物换装——把已经装配好的降级编辑器换成项目内文件背书的编辑器。
     *
     * 这是「首开预热超过预算 → 降级挂横幅 → 产物随后到达」的恢复出口（实测形态：预算 500ms、
     * 冷启首开预热 3016ms）。**调用方负责线程归位**（[prewarmDispatcher] 回 EDT，写安全模态）。
     *
     * 换装判据（全部满足才动，任一条不满足就静默保持现状——产物留在 [prewarmedTempFile] 里，
     * 留给后续 [createEditor] 消费）：
     *  - 面板与项目未销毁；
     *  - 已有编辑器，且当前文档就是降级装配的那一份（[degradedPreviewDocument] 身份相等）；
     *  - 文档文本与插件最后一次写入一致（[lastRenderedSql]）——用户动过就不换，避免抢走编辑现场。
     *    该条**只在自动换装时**生效：用户显式点「重试」时按 [userRequested] 跳过，改把当前文档文本
     *    作为新装配的 SQL（内容不丢）——否则用户编辑过之后重试就成了按不出反应的假动作。
     *
     * 换装走主路径装配（[assemblePreviewEditor]），因此横幅由它统一清除；光标与焦点尽量复位
     * （文本逐字相同，逻辑位置可直接搬）。
     */
    internal fun tryUpgradeToLateProduct(
        product: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>,
        userRequested: Boolean = false,
    ) {
        if (previewDisposed || project?.isDisposed == true) return
        val current = editor ?: return
        val degradedDoc = degradedPreviewDocument ?: return
        if (current.document !== degradedDoc) return
        val text = current.document.text
        if (!userRequested && text != lastRenderedSql) {
            // 用户已经在编辑（或另有渲染尚未落定）：不抢编辑现场，产物留给下一次装配
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .info("[M36] late prewarm product kept: document edited since last render (len=${text.length})")
            return
        }
        val caret = runCatching { current.caretModel.logicalPosition }.getOrNull()
        val hadFocus = runCatching { current.contentComponent?.isFocusOwner == true }.getOrDefault(false)
        disposeCurrentEditorOnly()
        // 与 [assemblePreviewEditor] 的「引用相等 + 随即置 null」消费判定对齐：本次要消费的就是这一份
        prewarmedTempFile = product
        assemblePreviewEditor(text, product)
        if (caret != null) runCatching { editor?.caretModel?.moveToLogicalPosition(caret) }
        if (hadFocus) runCatching { editor?.contentComponent?.requestFocusInWindow() }
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] late prewarm product consumed: editor upgraded to ${product.first.name}")
    }

    /**
     * T-PrewarmLateRecover F2：横幅「重试」——重新臂一轮预热，产物到达后原地换装并清横幅。
     *
     * 两条出口都接上：预算内就绪走 [PrewarmAwait.scheduleOnce] 的 onReady；超预算到达走
     * [PrewarmAwait.onLateProduct]（预热线程发布时发现消费侧已放弃）——所以重试**不会**因为
     * 「预热又慢了一次」而白点。失败（硬失败）时什么都不做：横幅与动作保留，用户可再点。
     *
     * 重臂本身受 [prewarmRound] 保护：新轮自增令牌，旧轮迟到产物不再入字段（P9 记录的
     * 「两次预热竞争 previewTempFile」即由该令牌挡住）。
     */
    internal fun retryPrewarm() {
        if (previewDisposed || project == null) return
        // 上一轮若已有产物躺着（迟到但没人消费），直接换装，不必再预热一轮
        prewarmedTempFile?.let { pending ->
            tryUpgradeToLateProduct(pending, userRequested = true)
            if (degradedPreviewDocument == null) return
        }
        // 降级态下「当前在用文件」这个指针没有意义（降级文档不在项目文件上），而 [prewarmPreviewTempFile]
        // 的入口守卫见它非空即判「已备好」直接 fail——主路径设了指针之后才失败的那条窄路（如
        // `PsiDocumentManager.getDocument` 返回 null）会让重试变成空动作。这里只丢指针、不删文件：
        // 文件仍在 [previewTempFilesCreated] 登记表里，[releaseEditor] 统一回收。
        previewTempFile = null
        prewarmFailed = false
        prewarmPreviewTempFile()
        prewarmAwait?.scheduleOnce(
            onReady = { product ->
                if (!previewDisposed) tryUpgradeToLateProduct(product, userRequested = true)
            },
            // 超时/失败：保持现状（横幅与动作仍在）——迟到通道会在产物真正到达时收尾
            onFallback = {},
        )
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
                    .createFileFromText(PreviewInlayScope.MEMORY_PREVIEW_FILE_NAME, fileType, sql)
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
                // write-safe: 同 SqlInputEditor.bindAutoPopup——本拍只由**用户输入活动**派生
                // （程序性写入经 suppressAutoPopup 抑制，不调度补全），无进度模态时平台取模态链
                // （defaultModalityImpl）落 NON_MODAL（恒 write-safe，见 DocWriteContext KDoc）；
                // commitDocument 只做 PSI 同步、不改文档文本。改投递为 nonModal 会把补全弹窗延后到
                // 模态关闭后，输入期补全退化。
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
        // T-M52：双参数版（Disposable 管理监听生命周期，releaseEditor 时自动移除）。
        // T-RA-02：移除职责只归 autoPopupDisposable——不要再用字段存 listener 做「记账」，
        // 那会让「谁负责摘监听」出现两个说法（T-M52 前的手动 removeDocumentListener 已删，
        // 二次移除会报 "Can't remove document listener"）。重新注册前先 dispose 旧 disposable，
        // 防同一文档挂上两个 listener。
        if (autoPopupDisposable != null) Disposer.dispose(autoPopupDisposable!!)
        autoPopupDisposable = Disposer.newDisposable("SqlPreviewPanel.autoPopup")
        created.document.addDocumentListener(listener, autoPopupDisposable!!)
    }

    /**
     * T-PrewarmLateRecover F1：只释放当前编辑器（含 autopopup 监听），不动临时文件与预热产物。
     *
     * 从 [releaseEditor] 抽出——迟到产物换装要在**保留**临时文件与产物的前提下换掉编辑器，
     * 两条路径共用同一段释放逻辑，避免「谁负责 releaseEditor」出现两个说法（Editor 泄漏会被
     * 平台报 "Memory leak detected"）。
     */
    private fun disposeCurrentEditorOnly() {
        editor?.let {
            // T-M52：监听器由 Disposable 管理，dispose 即移除（无需手动 removeDocumentListener，
            // 也不再有 listener 字段需要置 null——T-RA-02 删除）
            autoPopupDisposable?.let { d -> Disposer.dispose(d) }
            autoPopupDisposable = null
            EditorFactory.getInstance().releaseEditor(it)
            editor = null
        }
        // T-PrewarmLateRecover F1：编辑器没了，降级文档身份随之失效
        degradedPreviewDocument = null
    }

    /** 释放当前编辑器（幂等）。 */
    private fun releaseEditor() {
        // T-RefreshDbSteal：createEditor 兜底断言的对称端点——谁释放了自建编辑器决定了
        // 下一次 showSql 走哪个分支，旧实现零日志
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] releaseEditor: editor=${System.identityHashCode(editor)}, nativeDoc=${nativeEditorDoc != null}", Throwable("releaseEditor path"))
        disposeCurrentEditorOnly()
        psiFile = null
        // M34 回归修复：临时 SQL 文件删除（java.io 删除免 write action；deleteOnExit 兜底）
        previewTempFile?.let { vFile -> java.io.File(vFile.path).delete() }
        previewTempFile = null
        // T-FixN2：唯一文件名后本面板**创建过的全部**预览临时文件统一回收——除上面那个「当前在用」
        // 的以外，还含「预热已发布但从未被装配消费」（面板关闭/clear 早于消费）与「两次预热竞态中
        // 晚到的那一个」（clear 时旧预热仍在途、previewTempFile 被后来者覆盖）两类无主文件，
        // 否则 preview/ 目录会随预览次数只增不减。清理策略三选一取「按面板生命周期删除」：
        // 保留上限/文件池两条都要跨面板判断「谁在用」（同项目多开进程另有自己的文件），
        // 误删在用的预览文件会立刻复现 File Cache Conflict；本表只装本面板自己建的文件，天然无误删面。
        // T-PerfSweep6 T-PS6-04③：抽成 [recoverPreviewTempFiles]，与 [dispose] 共用同一份回收逻辑
        recoverPreviewTempFiles()
        // T-M36.6-03：预热产物作废（面板重用时由 clear() 重新预热；dispose 由 previewDisposed 兜底）
        prewarmedTempFile = null
    }

    /**
     * T-PerfSweep6 T-PS6-04③：回收本面板创建过的全部预览临时文件（幂等）。
     *
     * 抽成独立方法是为了让 [releaseEditor] 与 [dispose] 共用同一份回收逻辑——此前只有
     * `removeNotify` → [releaseEditor] 一条链会走到，而三种宿主（弹窗 / 工具窗 / 嵌入控制台）下
     * `dispose` 与 `removeNotify` 的先后未穷尽，dispose 先到即漏删。
     */
    private fun recoverPreviewTempFiles() {
        previewTempFilesCreated.forEach { f -> runCatching { f.delete() } }
        previewTempFilesCreated.clear()
    }

    /**
     * T-PerfSweep6 T-PS6-04（性能报告发现 4）：预览目录级兜底清扫（**每个面板启动时一次**）。
     *
     * **为什么需要**：唯一命名（T-FixN2）把「覆盖写同一路径」变成「每次新建一个文件」，异常路径
     * （kill / 崩溃 / 未走 `removeNotify` / 未走 `dispose`）留下的文件不再被任何后续预览复用，
     * `preview/` 只增不减。三条兜底按性价比取二（计划 §三 T-PS6-04）：
     *  ① **年龄**：mtime 早于 [STALE_PREVIEW_MAX_AGE_MS]（7 天）的残留直接删；
     *  ② **条数**：本插件命名的文件超过 [PREVIEW_DIR_MAX_FILES]（200）时按 mtime 从最旧删到上限。
     *  （第三条「dispose 补同款回收」落在 [recoverPreviewTempFiles]，见其调用点。）
     *
     * **宁漏删不误删**：只删**本插件自有命名**的文件——判据直接复用建名处的
     * [PreviewInlayScope.isPrimaryPreviewFileName]（与生成端同一份形态常量，杜绝判据漂移）；
     * 用户自己放进该目录的任何文件、二级内存名（`mpsql-preview-memory.sql`）、原生控制台临时文件
     * （`mpsql-exec*.sql`）都不匹配即不触碰（照 `SqlLogAutoSaver.pruneRetainedFiles` 的
     * familyRegex「先按名过滤再删」先例）。目录不存在/不可读时静默返回——清扫是兜底，
     * 不得影响预览主链。
     */
    private fun sweepStalePreviewFiles(dir: java.io.File) {
        val files = runCatching {
            dir.listFiles { f -> f.isFile && PreviewInlayScope.isPrimaryPreviewFileName(f.name) }
        }.getOrNull() ?: return
        if (files.isEmpty()) return
        val now = System.currentTimeMillis()
        files.filter { now - it.lastModified() > STALE_PREVIEW_MAX_AGE_MS }
            .forEach { runCatching { it.delete() } }
        val rest = files.filter { it.exists() }
        if (rest.size > PREVIEW_DIR_MAX_FILES) {
            rest.sortedBy { it.lastModified() }
                .take(rest.size - PREVIEW_DIR_MAX_FILES)
                .forEach { runCatching { it.delete() } }
        }
    }

    // ------------------------------------------------------------------
    // 参数输入与回填（M12：动态条件复选框行 + 按 source 绑定 + 空值语义）
    // ------------------------------------------------------------------

    /** 重建左侧面板：动态条件复选框行 + 普通参数值输入行（T-M23-04：批量列级勾选行）。 */
    private fun rebuildLeftPanel(result: PreviewResult) {
        paramPanel.removeAll()
        paramFields.clear()
        paramWarnLabels.clear()
        conditionCheckBoxes.clear()
        xmlConditionOutcomeLabels.clear()
        conditionValueFields.clear()
        batchCheckBoxes.clear()
        xmlForEachNullBoxes.clear()
        xmlForEachValueFields.clear()
        // T-M26.3：按钮随场景重建（标题行右侧）——先清空上次场景的残留引用
        toggleSelectButton = null
        clearButton = null
        batchToggleSelectButton = null
        batchClearButton = null
        val views = result.previewModel?.conditionViews.orEmpty()
        val batchModel = result.batchModel
        // R22 T-04：先算 hasParams——方案行插在首个早退之后，xml 无控件早退场景必须覆盖
        // （xml 无控件时无参数可存召回，不插方案行，避免「方案下拉 + 无参数提示」同屏）
        val xmlModelForSchemeRow = result.xmlModel
        val xmlHasControls = xmlModelForSchemeRow != null &&
            (xmlModelForSchemeRow.conditionViews.isNotEmpty() || xmlModelForSchemeRow.forEachViews.isNotEmpty())

        if (views.isEmpty() && result.unresolvedParams.isEmpty() && batchModel == null && !xmlHasControls) {
            // O-95 S3：无参数早退同样保留绑定指示行（常驻语义——用户此时最需要看到「有没有绑定」）
            paramPanel.add(buildBindStateRow())
            paramPanel.add(JBLabel(MpSqlVisualizerBundle.message("panel.param.none")).apply {
                border = JBUI.Borders.empty(6, 8)
            })
            paramPanel.revalidate()
            paramPanel.repaint()
            return
        }

        // R22 T-04：方案行（设计定稿位置——首个早退之后、batchModel 分支之前）
        paramPanel.add(buildSchemeRow())
        // O-95 T-PreviewEdtGuard S3：绑定数据源常驻指示行（预设行之下，灰字文本行——D2=A，
        // 不自创图形形态；绑定态=R33 结构化视图头部同源数据 boundConsoleDataSource，
        // 未绑定=R33 回退通知同源文案族）
        paramPanel.add(buildBindStateRow())

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

    // ------------------------------------------------------------------
    // R22 T-04：命名方案下拉 + 管理对话框接入（设计定稿：方案行 / 防串桶 / 对话框回调）
    // ------------------------------------------------------------------

    /**
     * R22 T-04：方案行——「预设: [下拉] [管理预设…]」（compactButton 同字号，非自创形态）。
     * 下拉项为原始方案名（默认方案 = 空串内部名），显示名经 bundle 本地化
     * （默认方案显示名与用户命名可撞名，故渲染器映射而非直接用显示名作条目）。
     * O-88：整行收进平台标准折叠件 [HideableTitledPanel]，标题复用 `panel.scheme.label`，
     * 缺省展开；折叠时内容区 setVisible(false) 不占竖向空间。折叠态存静态字段
     * （[presetSectionCollapsed]，跨预览重建且跨面板实例保留、不持久化）。
     */
    private fun buildSchemeRow(): JComponent {
        // O-88：懒惰捕获——上一份折叠件实例若被用户点标题折叠过，先回写静态字段再重建
        // （HideableTitledPanel 公共面无监听器；摘除后实例仍持有最后视觉态，clear/rebuild 均不丢）
        presetFold?.let { presetSectionCollapsed = !it.isExpanded() }
        val combo = JComboBox<String>()
        schemeCombo = combo
        combo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = displaySchemeName(value ?: ParamHistoryCache.DEFAULT_SCHEME)
        }
        combo.addActionListener {
            if (schemeSyncingCombo) return@addActionListener
            val raw = combo.selectedItem as? String ?: return@addActionListener
            if (raw != paramHistoryCache.activeSchemeName()) switchToScheme(raw)
        }
        syncSchemeCombo()
        val manageButton = compactButton(MpSqlVisualizerBundle.message("panel.scheme.manage")) {
            openSchemeManagerDialog()
        }
        val schemeRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            name = SCHEME_ROW_NAME
            add(JBLabel(MpSqlVisualizerBundle.message("panel.scheme.label")))
            add(combo)
            add(manageButton)
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
        // O-88：adjustWindow=false——预览面板嵌在弹窗/工具窗内容区，折叠/展开不得调整窗口尺寸
        // （源码语义：true 时 adjustWindow 会按内容尺寸改祖先 Window 大小，Run Config 弹窗专用）
        return HideableTitledPanel(
            MpSqlVisualizerBundle.message("panel.scheme.label"),
            false,
            schemeRow,
            !presetSectionCollapsed,
        ).also { presetFold = it }
    }

    /** R22 T-04：按 cache 当前态重建下拉模型与选中项（程序化填充，抑制切换语义）。 */
    private fun syncSchemeCombo() {
        val combo = schemeCombo ?: return
        schemeSyncingCombo = true
        try {
            val model = DefaultComboBoxModel<String>()
            model.addElement(ParamHistoryCache.DEFAULT_SCHEME)
            paramHistoryCache.schemeNames().forEach { model.addElement(it) }
            combo.model = model
            val active = paramHistoryCache.activeSchemeName()
            combo.selectedItem = active
            lastShownActiveScheme = active
        } finally {
            schemeSyncingCombo = false
        }
    }

    // ------------------------------------------------------------------
    // O-95 T-PreviewEdtGuard S3：绑定数据源常驻指示行（预设行之下，灰字文本行）
    // ------------------------------------------------------------------

    /**
     * S3 指示行数据源名（null = 未绑定/取不到）。测试缝优先——无 Database 插件的 headless
     * 用例经 [bindStateNameProvider] 构造绑定态；生产走 [cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSourceName]
     * （T-ConsoleDbContext 同源链：共享/已备控制台 + isValid 先探 + DBN 取名，全程 fail-closed）。
     */
    internal var bindStateNameProvider: (() -> String?)? = null

    private fun boundDataSourceNameForDisplay(): String? =
        bindStateNameProvider?.invoke()
            ?: runCatching {
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSourceName()
            }.getOrNull()

    /**
     * S3 指示行文案：绑定=「已绑定：<源名>」（panel.bindstate.bound，数据源名与 R33 结构化
     * 视图头部同源）；未绑定=「未绑定数据源（在控制台执行一次查询即可绑定）」（panel.bindstate.
     * unbound，与 R33 回退通知 panel.explain.structured.reason.noDataSource 同源文案族）。
     */
    private fun bindStateText(): String {
        val name = boundDataSourceNameForDisplay()
        return if (name != null) MpSqlVisualizerBundle.message("panel.bindstate.bound", name)
        else MpSqlVisualizerBundle.message("panel.bindstate.unbound")
    }

    /**
     * S3 指示行（灰字文本行，D2=A 定案——轻量形态禁自创图形）：contextHelp 灰字与同面板
     * 既有灰字形态一致（degradation 提示行同款 UIUtil.getContextHelpForeground）。
     */
    private fun buildBindStateRow(): JComponent {
        val label = JBLabel(bindStateText()).apply {
            name = BIND_STATE_LABEL_NAME
            foreground = UIUtil.getContextHelpForeground()
        }
        bindStateLabel = label
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            name = BIND_STATE_ROW_NAME
            add(label)
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    /**
     * S3 绑定态刷新：renderSql 尾部单点收口（循 R30 条件探镜「零监听器」口径——控制台上下文
     * 变更链〔attach/reuse/clearPrepared〕的既有 post-attach 渲染/清空路径都会走 renderSql，
     * 不另立监听器；纯文本读，write-safe 闸重投递亦无副作用）。
     */
    private fun refreshBindStateRow() {
        val label = bindStateLabel ?: return
        label.text = bindStateText()
    }

    /**
     * R22 T-04：切换方案（下拉/对话框回调共用）——清态 → [ParamHistoryCache.switchScheme] →
     * [recallFromHistory]。清场含可见字段文本与勾选框视觉态（新方案快照未覆盖的键不得残留旧值）；
     * 末尾无条件 renderSql：召回未命中时 SQL 也必须反映清空后的参数态。
     */
    private fun switchToScheme(name: String) {
        clearParamStateForSchemeSwitch()
        paramHistoryCache.switchScheme(name)
        lastShownActiveScheme = name
        recallFromHistory()
        renderSql()
    }

    /** R22 T-04：方案切换清场（等价 clearStateForTest 的状态面 + 可见组件视觉态回默认）。
     *  顺序敏感：先清可见字段文本（DocumentListener 回写空值——onParamChanged 无条件 put，
     *  先清 map 会被复写），状态集清场放最后，保证 paramValues 干净、召回「已输入值优先」不误判。 */
    private fun clearParamStateForSchemeSwitch() {
        paramFields.forEach { it.text = "" }
        xmlForEachValueFields.forEach { it.second.text = "" }
        // 编程式 isSelected 不触发 ActionListener，enabledIds 不受影响（末尾直接清）
        (conditionCheckBoxes + batchCheckBoxes).forEach { it.isSelected = false }
        xmlForEachNullBoxes.forEach { it.isSelected = false }
        enabledIds.clear()
        paramValues.clear()
        xmlForEachValues.clear()
        xmlForEachNulls.clear()
    }

    /** R22 T-04：管理方案对话框工厂（快照与回调在此接线——面板供给，对话框不知面板）。 */
    private fun createSchemeManagerDialog(proj: Project): ParamSchemeManagerDialog =
        ParamSchemeManagerDialog(
            proj,
            paramHistoryCache,
            snapshotProvider = {
                val key = historyCacheKey()
                if (key == null) null else key to buildHistorySnapshot()
            },
            onSchemesChanged = { onSchemesChangedFromDialog() },
        )

    /** R22 T-04：管理方案对话框（MODELESS 单实例——可见时置前，不堆叠）。 */
    private fun openSchemeManagerDialog() {
        val proj = project ?: return
        schemeManagerDialog?.let { existing ->
            if (existing.isVisible) {
                existing.toFront()
                return
            }
        }
        createSchemeManagerDialog(proj).also {
            schemeManagerDialog = it
            it.show()
        }
    }

    /** R22 T-04：对话框回调——同步下拉；激活方案被对话框变更（另存为自动切换）时整面重召回。 */
    private fun onSchemesChangedFromDialog() {
        val active = paramHistoryCache.activeSchemeName()
        val activeChanged = active != lastShownActiveScheme
        syncSchemeCombo()
        if (activeChanged) switchToScheme(active)
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
                putClientProperty(PROP_TOGGLE_ID, col.columnName) // R22 T-02：快照枚举/召回同步的 id 锚点
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
            putClientProperty(PROP_TOGGLE_ID, view.id) // R22 T-02：快照枚举/召回同步的 id 锚点
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
            putClientProperty(PROP_TOGGLE_ID, view.id) // R22 T-02：快照枚举/召回同步的 id 锚点（XML 条件行）
            addActionListener {
                if (isSelected) enabledIds.add(view.id) else enabledIds.remove(view.id)
                renderSql()
            }
        }
        conditionCheckBoxes += checkBox
        val outcomeLabel = JBLabel().apply {
            foreground = UIUtil.getContextHelpForeground()
        }.also { xmlConditionOutcomeLabels[view.id] = it }
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(checkBox)
            // T-ExprAstPrecheck T-EP-03：表达式超出支持面时补淡色标注（提示，不改展开行为）
            xmlConditionAnnotation(view)?.let { add(it) }
            // R30 探镜：本轮求值结果标注（真/假/未求值；占位空串，renderSql 首轮刷新）
            add(outcomeLabel)
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    /**
     * R30 条件求值探镜：渲染后按本轮判定结果刷新各条件行尾的三态标注（真/假/未求值）。
     * 只读探针——读 [XmlPreviewModel.conditionOutcomes] 判定产物，不参与渲染文本；零监听器
     * （刷新挂在 renderSql 单点，勾选/参数/foreach 变更后的每轮渲染自动跟上）。
     */
    private fun syncXmlConditionOutcomeLabels(xml: XmlPreviewModel, rendered: RenderedXmlSql) {
        if (xmlConditionOutcomeLabels.isEmpty()) return
        val outcomes = xml.conditionOutcomes(rendered)
        xmlConditionOutcomeLabels.forEach { (id, label) ->
            val outcome = outcomes[id] ?: return@forEach
            val text = when (outcome) {
                ConditionOutcome.TRUE -> MpSqlVisualizerBundle.message("panel.xml.outcome.true")
                ConditionOutcome.FALSE -> MpSqlVisualizerBundle.message("panel.xml.outcome.false")
                ConditionOutcome.UNEVALUATED -> MpSqlVisualizerBundle.message("panel.xml.outcome.unevaluated")
            }
            label.text = text
            label.toolTipText = MpSqlVisualizerBundle.message("panel.xml.outcome.tooltip", text)
        }
    }

    /**
     * T-ExprAstPrecheck T-EP-03：条件行的支持面标注（SUPPORTED 返回 null 不占位）。
     * 用上下文提示色（淡）以与条件标签区分；理由进 tooltip，行内只留档位短句。
     */
    private fun xmlConditionAnnotation(view: XmlConditionView): JBLabel? {
        val text = when (view.support) {
            ExprSupport.SUPPORTED -> return null
            ExprSupport.UNSUPPORTED_SYNTAX -> MpSqlVisualizerBundle.message("panel.xml.expr.unsupported")
            ExprSupport.EVAL_FALLBACK -> MpSqlVisualizerBundle.message("panel.xml.expr.fallback")
        }
        return JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            toolTipText = xmlConditionAnnotationTooltip(view)
        }
    }

    /** 标注 tooltip：按理由码取文案，附细节（方法名 / 静态字段引用 / 解析错误消息）。 */
    private fun xmlConditionAnnotationTooltip(view: XmlConditionView): String {
        val detail = view.supportDetail.orEmpty()
        return when (view.supportReason) {
            ExprReason.SYNTAX -> MpSqlVisualizerBundle.message("panel.xml.expr.reason.syntax", detail)
            ExprReason.UNKNOWN_METHOD -> MpSqlVisualizerBundle.message("panel.xml.expr.reason.unknownMethod", detail)
            ExprReason.NARROW_CARRIER -> MpSqlVisualizerBundle.message("panel.xml.expr.reason.narrowCarrier", detail)
            ExprReason.STATIC_FIELD -> MpSqlVisualizerBundle.message("panel.xml.expr.reason.staticField", detail)
            null -> view.label
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
        xmlForEachValueFields += view.collectionExpr to valuesField // R22 T-03：召回时按 expr 同步字段文本
        val nullBox = JCheckBox(MpSqlVisualizerBundle.message("panel.xml.foreach.null"), view.collectionExpr in xmlForEachNulls).apply {
            putClientProperty(PROP_FOREACH_NULL, view.collectionExpr) // R22 T-02：召回同步的 expr 锚点
            addActionListener {
                if (isSelected) xmlForEachNulls.add(view.collectionExpr) else xmlForEachNulls.remove(view.collectionExpr)
                renderSql()
            }
        }
        xmlForEachNullBoxes += nullBox // R22 T-02：召回视觉同步登记
        row.add(nullBox)
        row.border = JBUI.Borders.empty(0, 8, 2, 8)
        return row
    }

    /**
     * T-M25.4：foreach 集合值变更——逗号分隔值解析为展开项并**自动回填**：
     * `2,3` → 展开 2 项 + `IN ( 2 , 3 )`；留空 → 恢复默认展开次数并清空该 foreach 的参数值（`?` 保持）。
     *
     * O-78 T-ForeachItemRender：值以 collectionExpr 为键存入 [xmlForEachValues]，播种收进
     * [renderSql]（[syncForEachItemValues]）——原「渲染后同步读 xmlMappings 取键」在 write-unsafe
     * （防抖 Timer）上下文里 renderSql 被重投递、键清单滞后一拍，值按旧键播种丢尾巴
     * （真机形态 `IN ('1','2','4',?)`）；状态化后渲染延迟与否都按最新状态出图。
     *
     * T-Perf-04：击键路径防抖（停顿后以最终文本执行一次）语义不变。
     */
    private fun onXmlForEachValuesChanged(view: XmlForEachView, text: String) {
        scheduleRender { applyXmlForEachValues(view, text) }
    }

    /** foreach 值应用（由防抖 Timer 调度）：存状态 + 设展开次数 + 单次渲染（播种在渲染帧内）。 */
    private fun applyXmlForEachValues(view: XmlForEachView, text: String) {
        val values = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        xmlForEachValues[view.collectionExpr] = values
        xmlForEachCounts[view.collectionExpr] =
            if (values.isEmpty()) XmlPreviewModel.DEFAULT_FOREACH_SIZE else values.size
        renderSql()
        saveToHistory() // R22 T-03：foreach 权威态变更触发历史保存（原缺口③——权威态仅间接入库且不触发）
    }

    /**
     * O-78：foreach 值播种——在 renderSql 帧内（[xmlMappings] 刚按当前状态刷新，恒新鲜）按
     * collectionExpr → view.item 反查前缀回填/清除 `__frch_<item>_*` 参数值；多出键清除语义与
     * 原实现一致（留空 → `?` 保持）。
     */
    private fun syncForEachItemValues(xml: XmlPreviewModel) {
        if (xmlForEachValues.isEmpty()) return
        xmlForEachValues.forEach { (expr, values) ->
            val item = xml.forEachViews.firstOrNull { it.collectionExpr == expr }?.item ?: return@forEach
            val keys = xmlMappings.map { it.property }.filter { it.startsWith("__frch_${item}_") }
            if (values.isEmpty()) {
                keys.forEach { paramValues.remove(it) }
            } else {
                keys.forEachIndexed { i, key ->
                    if (i < values.size) paramValues[key] = values[i] else paramValues.remove(key)
                }
            }
        }
    }

    /** 普通参数行：`#<source>` 标签 + 值输入框（T-PT-02：按签名类型类别挂占位提示与行尾校验警示）。 */
    private fun buildParamRow(source: String, labelWidth: Int): JBPanel<JBPanel<*>> {
        val kind = paramKindOf(source)
        // T-PT-02：行尾淡色警示标（校验是提示不是闸门——值照原样渲染，渲染链 renderValue 行为不动）
        val warnLabel = JBLabel("").apply {
            foreground = PARAM_WARN_FOREGROUND
            isVisible = false
        }
        // T-HibDualCapture：8 列放不下 16 字符日期值（视口滚到尾部被当成「值截断」），下限放宽到 16 列
        val field = JBTextField(16).apply {
            text = paramValues[source] ?: ""
            emptyText.text = paramEmptyText(source, kind)
            // 长值超框宽时光标钉回首字符，视口停在头部；完整值以 tooltip 兜底
            if (text.isNotEmpty()) caretPosition = 0
            toolTipText = paramTooltip(source, text)
            updateParamWarning(warnLabel, kind, text)
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onParamFieldText(source, this@apply, warnLabel, kind)
                override fun removeUpdate(e: DocumentEvent?) = onParamFieldText(source, this@apply, warnLabel, kind)
                override fun changedUpdate(e: DocumentEvent?) = onParamFieldText(source, this@apply, warnLabel, kind)
            })
        }
        paramFields += field
        paramWarnLabels += warnLabel
        // T-M19.4-01：FlowLayout 间距 (4,2) → (8,6)——参数名较长时视觉"贴太近"（用户实测）
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            add(paramLabel(source, labelWidth))
            add(field)
            add(warnLabel)
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }
    }

    // ------------------------------------------------------------------
    // T-PT-02：按签名类型类别的占位提示与校验（提示不是闸门——渲染链零改动）
    // ------------------------------------------------------------------

    /** 参数 source 的类型类别（无类型信息 → UNKNOWN，行为=升级前）。 */
    private fun paramKindOf(source: String): ParamKind =
        current?.xmlModel?.kindFor(source) ?: ParamKind.UNKNOWN

    /** 占位提示（emptyText）：有类型信息按类别；UNKNOWN 走参数命名启发式（zMyBatis 同款，仅提示）。 */
    private fun paramEmptyText(source: String, kind: ParamKind): String = when (kind) {
        ParamKind.NUMBER -> MpSqlVisualizerBundle.message("panel.param.emptyText.number")
        ParamKind.TEMPORAL -> MpSqlVisualizerBundle.message("panel.param.emptyText.temporal")
        ParamKind.COLLECTION -> MpSqlVisualizerBundle.message("panel.param.emptyText.collection")
        ParamKind.OBJECT -> MpSqlVisualizerBundle.message("panel.param.emptyText.object")
        ParamKind.TEXT -> ""
        ParamKind.UNKNOWN -> if (looksLikeCollectionName(source)) {
            MpSqlVisualizerBundle.message("panel.param.emptyText.collection")
        } else {
            ""
        }
    }

    /**
     * 集合命名启发式（T-PT-02，zMyBatis ParameterInputDialog 同款）：参数名以 `s` 结尾
     * （排除 status/address 等单数词）或含 list/ids/array/collection → 按数组提示。
     */
    private fun looksLikeCollectionName(source: String): Boolean {
        val name = source.substringAfterLast('.').lowercase()
        if (name in COLLECTION_NAME_EXCEPTIONS) return false
        if (name.contains("list") || name.contains("ids") || name.contains("array") || name.contains("collection")) {
            return true
        }
        return name.endsWith("s") && !name.endsWith("ss")
    }

    /** 行尾校验警示（NUMBER 非数字 / TEMPORAL 非可解析日期）：淡色标记 + tooltip 说明，不阻塞渲染。 */
    private fun updateParamWarning(label: JBLabel, kind: ParamKind, value: String?) {
        val message = paramWarningOf(kind, value)
        label.text = if (message == null) "" else PARAM_WARN_MARK
        label.toolTipText = message
        label.isVisible = message != null
    }

    private fun paramWarningOf(kind: ParamKind, value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        return when (kind) {
            ParamKind.NUMBER -> if (NUMBER_REGEX.matches(trimmed)) null
            else MpSqlVisualizerBundle.message("panel.param.warn.number")
            ParamKind.TEMPORAL -> if (DATE_REGEX.matches(trimmed)) null
            else MpSqlVisualizerBundle.message("panel.param.warn.temporal")
            else -> null
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

    /**
     * T-M26.3：长文本中段省略——保留尾部（bind 字段名/参数名尾部有意义），超宽时显示 `头…尾`。
     *
     * 度量以 `(String) -> Int` 注入（[ellipsizeMiddle] 纯函数），使 meta 行的宽度预算组装
     * （[metaLineWithinBudget]）可脱 FontMetrics 单测。
     */
    private fun ellipsize(text: String, metrics: FontMetrics, maxWidth: Int): String =
        ellipsizeMiddle(text, { metrics.stringWidth(it) }, maxWidth)

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
     * T-PT-02：行尾校验警示随值同步（默认参数无警示标，调用方为 buildParamRow）。
     */
    private fun onParamFieldText(
        source: String,
        field: JBTextField,
        warnLabel: JBLabel? = null,
        kind: ParamKind = ParamKind.UNKNOWN,
    ) {
        field.toolTipText = paramTooltip(source, field.text)
        warnLabel?.let { updateParamWarning(it, kind, field.text) }
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
        // T-Perf-20：空剪贴板提示是**纯行内状态更新**（不弹任何窗），与下面的通知分支性质不同，
        // 故前置到测试模式闸门之前——闸门只为避开 Messages/TestDialog 拦截器，行内提示无此依赖，
        // 前置后该提示态可被 headless 测试驱动（T-SourceJumpFunnel P6 的 tooltip 契约据此断言）。
        if (isEmptyClipboard) {
            showEmptyClipboardHint()
            return
        }
        // 测试环境跳过弹窗（Messages 在 test mode 下触发 TestDialog 拦截器抛异常）
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val title = MpSqlVisualizerBundle.message("panel.param.import.result.title")
        val proj = project ?: return
        when {
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

    /**
     * T-Perf-20：空剪贴板（未识别到有效参数）的**行内**提示——元信息行红字 + 错误复制按钮可见，
     * 不弹模态框（toast 语义：下次 [updateMeta] 覆盖）。
     *
     * T-SourceJumpFunnel P6 修复：提示态 tooltip 由「覆写」改「拼接」——[updateMeta] 写入的
     * [metaTooltipBase] 是「悬停见完整调用链」的唯一载体（meta 行可见文本受 F3 宽度预算截断），
     * 旧写法 `metaLabel.toolTipText = hint` 使提示态悬停只剩提示本身、完整链丢失；而提示文案本就在
     * 可见行上全文可见，覆写并未换来任何信息。拼接以提示为首行（与可见行一致），基线随其后；
     * 基线取自缓存字段而非当前 tooltip，故重复触发幂等（不会把自身叠加进去）。
     *
     * P10 修复（2026-09-20 真机走查发现）：提示此前**只写** `metaLabel.text`，不进 [metaLineHead] /
     * [metaLineText]。而 `errorCopyButton.isVisible = true` 会改变工具栏理想宽 → topRow 重排 →
     * 紧接着的 [applyMetaTruncation]（预算变了故不短路）用旧的 `metaLineText` 把提示**当场覆盖**——
     * 真机走查实测「提示态根本看不到」（红字与复制按钮都没出现），即本项此前只在单测里成立。
     * 现把提示写进同一套分段缓存并复位 [lastMetaBudget]，可见行统一由 [applyMetaTruncation] 收口渲染
     * （含宽度预算与 perf 段），提示因此参与布局且不再被覆盖；`toast 语义`不变——下次 [updateMeta]
     * 重写分段缓存即覆盖。
     */
    private fun showEmptyClipboardHint() {
        val hint = MpSqlVisualizerBundle.message("panel.param.import.result.empty")
        currentWarning = hint
        val hintLine = "⚠ $hint"
        metaLineHead = hintLine
        metaLineDigest = ""
        metaLineText = hintLine
        lastMetaBudget = -1
        metaLabel.toolTipText = if (metaTooltipBase.isEmpty()) hint else "$hint\n$metaTooltipBase"
        metaLabel.foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
        errorCopyButton?.isVisible = true
        applyMetaTruncation()
    }

    /** P-05：计算当前预览的缓存键（R22 T-01 起：无表名以 SQL 文本规范化指纹为键，恒非 null）。 */
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
     *
     * R22 T-02/T-03：快照特殊键一并还原——`__chk__:<id>`=1/0 勾选态（1 加 0 移，可复现
     * 「关掉默认勾选」的方案）、`__fnull__:<expr>`=1 foreach null 勾选、`__fev__:<expr>`=
     * 逗号分隔 foreach 权威值（还原权威 map + 同步可见输入框）。还原后 renderSql 收敛。
     */
    internal fun recallFromHistory() {
        val key = historyCacheKey() ?: return
        val history = paramHistoryCache.recall(key) ?: return
        if (history.isEmpty()) return
        var applied = 0
        var stateRestored = false
        history.forEach { (source, value) ->
            when {
                source.startsWith(SNAPSHOT_CHK) -> {
                    val id = source.removePrefix(SNAPSHOT_CHK)
                    if (value == "1") enabledIds.add(id) else enabledIds.remove(id)
                    stateRestored = true
                }
                source.startsWith(SNAPSHOT_FNULL) -> {
                    val expr = source.removePrefix(SNAPSHOT_FNULL)
                    if (value == "1") xmlForEachNulls.add(expr) else xmlForEachNulls.remove(expr)
                    stateRestored = true
                }
                source.startsWith(SNAPSHOT_FEV) -> {
                    val expr = source.removePrefix(SNAPSHOT_FEV)
                    val values = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (values.isNotEmpty()) {
                        xmlForEachValues[expr] = values
                        xmlForEachValueFields.firstOrNull { it.first == expr }?.second?.text = value
                        stateRestored = true
                    }
                }
                // 用户已输入值优先（不覆盖）
                else -> if (paramValues[source].isNullOrEmpty()) {
                    paramValues[source] = value
                    syncParamFieldText(source, value)
                    applied++
                }
            }
        }
        if (stateRestored) syncToggleChecksFromState()
        if (applied > 0 || stateRestored) {
            renderSql()
        }
    }

    /** R22 T-02：把当前状态集同步到可见勾选框（召回路径——勾选框初始态在渲染时定型，
     *  召回发生在面板已建之后，须显式回写 isSelected）。 */
    private fun syncToggleChecksFromState() {
        (conditionCheckBoxes + batchCheckBoxes).forEach { cb ->
            val id = cb.getClientProperty(PROP_TOGGLE_ID) as? String ?: return@forEach
            cb.isSelected = id in enabledIds
        }
        xmlForEachNullBoxes.forEach { cb ->
            val expr = cb.getClientProperty(PROP_FOREACH_NULL) as? String ?: return@forEach
            cb.isSelected = expr in xmlForEachNulls
        }
    }

    /** R22 T-02：参数历史快照构建——paramValues 之外并入勾选态与 foreach 权威态：
     *  - `__chk__:<id>` = "1"/"0"：条件/批量勾选**全量 1/0 编码**（可见组件逐个枚举 + enabledIds
     *    残留项补 "1"）——只存 "1" 无法复现「关掉默认勾选」的方案；
     *  - `__fnull__:<expr>` = "1"：foreach null 勾选（默认态=未勾，只存勾选侧）；
     *  - `__fev__:<expr>` = 逗号分隔值：foreach 集合权威值（空值不存=默认展开）。 */
    internal fun buildHistorySnapshot(): Map<String, String> {
        val snapshot = paramValues.toMutableMap()
        val seenToggles = mutableSetOf<String>()
        (conditionCheckBoxes + batchCheckBoxes).forEach { cb ->
            val id = cb.getClientProperty(PROP_TOGGLE_ID) as? String ?: return@forEach
            seenToggles += id
            snapshot["$SNAPSHOT_CHK$id"] = if (id in enabledIds) "1" else "0"
        }
        enabledIds.forEach { if (it !in seenToggles) snapshot["$SNAPSHOT_CHK$it"] = "1" }
        xmlForEachNulls.forEach { snapshot["$SNAPSHOT_FNULL$it"] = "1" }
        xmlForEachValues.forEach { (expr, values) ->
            if (values.isNotEmpty()) snapshot["$SNAPSHOT_FEV$expr"] = values.joinToString(",")
        }
        return snapshot
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
     * T-Perf-24：序列化/持久化写挪 pooled 线程（EDT 只做取值快照）。
     * R22 T-04 防串桶：取快照（EDT）与写回（pooled 线程）之间用户可能切换方案——
     * 快照时捕获激活方案名随行落库（saveForScheme），不再依赖写回时刻的激活态。 */
    private fun flushHistoryToCache() {
        val key = historyCacheKey() ?: return
        val snapshot = buildHistorySnapshot()
        val scheme = paramHistoryCache.activeSchemeName()
        // headless 单测：同步执行保持既有测试语义
        if (ApplicationManager.getApplication().isUnitTestMode) {
            paramHistoryCache.saveForScheme(scheme, key, snapshot)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            paramHistoryCache.saveForScheme(scheme, key, snapshot)
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
     *
     * O-81 T-XmlListParamSplit：`IN` 与 `(` 之间从「可选一个空格」放宽为**任意空白串**——
     * mapper 排版 `IN\n(`（先红读数实证：升级前漏判 → 集合参数整串单值 `'PI,WP'`）不再漏。
     * 多 `?` IN 列表仍仅首位命中（foreach 已有逐项回填通道，扩面会双拆，语义钉住不变）。
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
                while (j >= 0 && sql[j].isWhitespace()) j--
                if (j >= 1 && (sql[j] == 'N' || sql[j] == 'n') && (sql[j - 1] == 'I' || sql[j - 1] == 'i')) {
                    result.add(paramIndex)
                }
            }
            paramIndex++
        }
        return result
    }

    /**
     * T-PSG-03（N-33）：按 source 回填 `?` 占位符——位点判定走 [placeholderPositions]
     * （string-aware），字符串字面量与 `--` / 块注释内的 `?` 不再被替换（改前朴素逐字符
     * `c == '?'` 会错位：引号内 `?` 吃掉了本属真占位符的参数值）。
     *
     * 值渲染语义不变（[renderParamValue] / [renderSingleValue] 一行未动）；不含这类 `?` 的
     * SQL 回填结果与改前逐字节一致。
     *
     * T-PSG-04：同一趟记下「位点数 vs 参数条数」对账结论（[placeholderReconciliation]），
     * 执行前由 [warnOnPlaceholderMismatch] 如实标注——不阻断。
     */
    private fun backfillParamsBySource(sql: String, sources: List<String>, hints: List<ParamHint> = emptyList()): String {
        if (sources.isEmpty()) return sql
        val positions = placeholderPositions(sql)
        placeholderReconciliation = PlaceholderCardinality.mismatchMarker(positions.size, sources.size)
        if (positions.isEmpty()) return sql
        val sb = StringBuilder(sql.length + 32)
        var paramIndex = 0
        var prev = 0
        for (pos in positions) {
            sb.append(sql, prev, pos)
            val source = sources.getOrNull(paramIndex)
            val v = source?.let { paramValues[it] }
            sb.append(renderParamValue(v, hints.getOrNull(paramIndex), source))
            paramIndex++
            prev = pos + 1
        }
        sb.append(sql, prev, sql.length)
        return sb.toString()
    }

    private fun renderParamValue(v: String?, hint: ParamHint?, source: String? = null): String {
        // 未填写（paramValues 未播种的 source，非标准路径）保留 `?` 兜底提示
        if (v == null) return "?"
        // T-PT-04 / O-79：空输入按设置策略出 ?（默认占位档）/ NULL / ''——**仅对带签名类型信息的
        // 参数**生效；无类型信息（UNKNOWN）恒 `?` 占位提示（宁缺勿错）
        if (v.isBlank()) return if (hasSignatureTypeInfo(source)) emptyParamLiteral() else "?"
        val like = hint?.likePosition
        // M19.6（用户实测）：IN 列表多值回填——逗号分割，每个值独立渲染（引号规则同单值），
        // 输入 `1,2` → `('1','2')`（原实现拼成 `'1,2'` 单值）
        if (hint?.inList == true) {
            val parts = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // 逗号分割后无有效值（如 `,` 形态）：按空输入档处理
            if (parts.isEmpty()) return if (hasSignatureTypeInfo(source)) emptyParamLiteral() else "?"
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

    /**
     * T-PT-04 / O-79：空参数填充字面量——按设置「空参数填充策略」出 `?`（默认占位档，保留待填
     * 提示，SQL 不臆造语义）/ `NULL` / `''`。作用于 [renderParamValue] 的空值分支，且仅当参数带
     * 签名类型信息（[hasSignatureTypeInfo]）；未知策略值按占位档兜底。
     */
    private fun emptyParamLiteral(): String = when (PluginSettings.getInstance().state.emptyParamFill) {
        PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING -> "''"
        PluginSettings.EMPTY_PARAM_FILL_NULL -> "NULL"
        else -> "?"
    }

    /** T-PT-04：参数是否带签名类型信息（XML/注解 mapper 参数行）——决定空输入是否走填充策略。 */
    private fun hasSignatureTypeInfo(source: String?): Boolean =
        source != null && paramKindOf(source) != ParamKind.UNKNOWN

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
        // T-SourceJumpFunnel P2-2：调用栈快照并入 meta 提示行（截断展示，悬停 tooltip 全文）。
        // 文案组装抽为纯函数 [buildStackDigestMetaText]（同文件顶层，行为逐字不变）——§3.3 验收项
        // 单测直断截断长度/截断标记/全文节点，无需真机目视。
        val stackText = buildStackDigestMetaText(result.stackDigest)
        // T-SqlPerf：性能建议文案单独一行（HTML 换行）。
        // T-PreviewTopOverlap F3：原文分段缓存，label 文本按宽度预算重拼（applyMetaTruncation）
        // T-SourceJumpFunnel P5 修复：调用栈摘要（stackText.inlineLine）并入**可见行**——此前只进
        // tooltipText，验收项前半句「meta 行显示调用栈」在可见行上落空（真机走查第 1 步看不到）。
        // 该行受 F3 宽度预算约束，故按**主体 / 摘要**两段缓存（见 metaLineHead / metaLineDigest）：
        // 预算不足时先截摘要、主体仅在自身超预算时才中段省略（复核 20260920 修正——把摘要直接拼进
        // 单段文本会让 F3 的中段省略在窄窗下连主体一起挤掉，只剩「…」+ 栈底帧，优先级倒挂）。
        // 完整调用链仍恒在悬停 tooltip 内（tooltip 不受预算影响）。
        metaLineHead = meta + warningLine
        metaLineDigest = stackText.inlineLine
        metaLineText = metaLineHead + metaLineDigest
        metaPerfSegments = buildPerfSegments(result)
        metaTooltipBase = meta + warningLine + stackText.inlineLine + stackText.tooltipTail +
            metaPerfSegments.joinToString("；") { it.text }
        metaLabel.toolTipText = metaTooltipBase
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
    /** R22 T-04 测试缝：程序化切换方案（等价下拉用户切换：清态→switchScheme→召回→渲染）。 */
    internal fun switchSchemeForTest(name: String) = switchToScheme(name)
    /** R22 T-04 测试缝：对话框回调链（同步下拉 + 激活变更整面重召回）。 */
    internal fun onSchemesChangedForTest() = onSchemesChangedFromDialog()

    companion object {
        /** O-4：通知分组 id（plugin.xml 注册，diff/copy 共用）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /**
         * O-88：预设分区是否处于折叠态（缺省展开=false；重建时由当前折叠件实例懒惰回写，见 [buildSchemeRow]）。
         * 静态而非实例：gutter 换语句=关旧弹窗、开新面板实例，实例字段在新实例归零丢折叠态（真机走查实证）；
         * 跨面板实例保留、不持久化（无 PropertiesComponent/磁盘写入）。@Volatile：读写入口可能非 EDT，可见性保守处理。
         */
        @Volatile
        private var presetSectionCollapsed = false

        /** O-88 测试缝：折叠态静态字段读（跨实例保留用例断言用）。 */
        internal fun presetCollapsedForTest(): Boolean = presetSectionCollapsed

        /** O-88 测试缝：折叠态静态字段复位（setUp 调用，防静态态跨用例/跨测试类串扰）。 */
        internal fun resetPresetCollapsedForTest() {
            presetSectionCollapsed = false
        }

        /** R22 T-04：方案行 panel.name 锚点——方案行也是「含 JBLabel 的 JBPanel」且插在 paramPanel
         *  头部，[paramRowLabelForTest] 按契约只取普通参数行，须按此排除（否则布局断言序号错位）。 */
        private const val SCHEME_ROW_NAME = "mpsv.schemeRow"

        /** O-95 S3：绑定数据源指示行/标签 panel.name 锚点（测试定位 + paramRowLabelForTest 排除同口径）。 */
        private const val BIND_STATE_ROW_NAME = "mpsv.bindStateRow"
        private const val BIND_STATE_LABEL_NAME = "mpsv.bindStateLabel"

        /** R22 T-02/T-03：参数历史快照特殊键前缀（勾选态/foreach null/foreach 权威值；普通参数键
         *  不以 `__` 开头——键空间约定，ParamHistoryCache 对值透明）。 */
        private const val SNAPSHOT_CHK = "__chk__:"
        private const val SNAPSHOT_FNULL = "__fnull__:"
        private const val SNAPSHOT_FEV = "__fev__:"

        /** R22 T-02：勾选框 client property 锚点（快照枚举 + 召回视觉同步）。 */
        private const val PROP_TOGGLE_ID = "mpsv.toggleId"
        private const val PROP_FOREACH_NULL = "mpsv.forEachNull"

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

        /**
         * T-PT-02：TEMPORAL 参数的可解析日期形态——`yyyy` / `yyyy-M-d` / 追加 ` HH:mm[:ss[.SSS]]`
         * （`T` 分隔兼容 ISO 形态；只做提示，不阻塞渲染）。
         */
        private val DATE_REGEX = Regex(
            "\\d{4}(-\\d{1,2}(-\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?)?)?",
        )

        /** T-PT-02：行尾校验警示标（淡色，深浅主题各一档）。 */
        private const val PARAM_WARN_MARK = "⚠"
        private val PARAM_WARN_FOREGROUND = JBColor(0xB8860B, 0xD9A343)

        /**
         * T-PT-02：集合命名启发式的单数词排除表——以 `s` 结尾但语义为单数（状态/地址等），
         * 不做数组提示（宁缺勿错：提示只是占位文案）。
         */
        private val COLLECTION_NAME_EXCEPTIONS = setOf(
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

/**
 * 调用栈快照的两段文案产物（纯数据，无组件依赖）。
 *
 * @property inlineLine meta 提示行内联摘要（`   调用栈: <摘要>`，超长截断；无快照时为空串）
 * @property tooltipTail 悬停 tooltip 追加的全文节点（`\n调用栈: <完整链>`；无快照时为空串）
 */
internal data class StackDigestMetaText(val inlineLine: String, val tooltipTail: String)

/** 调用栈摘要字符上限：超过即截断到该长度并追加 [STACK_DIGEST_ELLIPSIS]。 */
internal const val STACK_DIGEST_MAX_CHARS = 120

/** 调用栈摘要截断标记。 */
internal const val STACK_DIGEST_ELLIPSIS = "…"

/**
 * T-SourceJumpFunnel P2-2：把调用栈快照 [digest] 组装成「meta 行内联摘要（截断展示）」与
 * 「tooltip 全文节点（完整链不截断）」两段文案；[digest] 为 null 时两段均为空串。
 *
 * 由 [SqlPreviewPanel.updateMeta] 调用（内联摘要进 tooltipText 首行，全文节点换行追加其后）。
 * 抽出为顶层 internal 纯函数仅为让 §3.3 验收项（截断长度/截断标记/全文节点）可直接单测断言，
 * 组装表达式与原内联实现逐字一致，不改任何展示行为。
 */
internal fun buildStackDigestMetaText(digest: String?): StackDigestMetaText {
    if (digest == null) return StackDigestMetaText(inlineLine = "", tooltipTail = "")
    val label = MpSqlVisualizerBundle.message("panel.meta.stackDigest")
    val summary =
        if (digest.length > STACK_DIGEST_MAX_CHARS) {
            digest.substring(0, STACK_DIGEST_MAX_CHARS) + STACK_DIGEST_ELLIPSIS
        } else {
            digest
        }
    return StackDigestMetaText(
        inlineLine = "   " + label + " " + summary,
        tooltipTail = "\n" + label + " " + digest,
    )
}

// ------------------------------------------------------------------
// T-SourceJumpFunnel P5 复核修正：meta 行宽度预算组装（纯逻辑，脱 FontMetrics 可直测）
// ------------------------------------------------------------------

/**
 * 调用栈摘要在 meta 行里至少要拿到的宽度（px）：约等于标签「   调用栈:」自身宽度 @13pt，
 * 低于此值就整段不放摘要——宁可让主体独占预算，也不留一个看不出是什么的孤零零省略号。
 */
internal const val META_DIGEST_MIN_PX = 56

/**
 * T-M26.3 中段省略的纯函数形态（[SqlPreviewPanel.ellipsize] 逐字同源）：保留尾部 30%，
 * 超宽时显示 `头…尾`。[widthOf] 是文本像素宽度度量——注入后本函数与
 * [metaLineWithinBudget] 都能脱 FontMetrics 单测（测试用「1 字符 = 1px」替身即可）。
 */
internal fun ellipsizeMiddle(text: String, widthOf: (String) -> Int, maxWidth: Int): String {
    val mark = "\u2026"
    if (widthOf(text) <= maxWidth) return text
    val tailLen = (text.length * 0.3).toInt().coerceAtLeast(4).coerceAtMost(text.length - 1)
    val tail = text.takeLast(tailLen)
    var head = text.length - tailLen
    while (head > 0 && widthOf(text.take(head) + mark + tail) > maxWidth) head--
    return text.take(head) + mark + tail
}

/**
 * 尾部截断（保留头部）——用于调用栈摘要：摘要**栈顶在左**（[buildStackDigestMetaText] 的摘要自栈顶
 * 起截），尾部是信息量最低的栈底帧，故超宽时截尾，而非 [ellipsizeMiddle] 的中段省略。
 */
internal fun clipTailToWidth(text: String, widthOf: (String) -> Int, maxWidth: Int): String {
    if (widthOf(text) <= maxWidth) return text
    val mark = "\u2026"
    var end = text.length
    while (end > 0 && widthOf(text.take(end) + mark) > maxWidth) end--
    return if (end <= 0) "" else text.take(end) + mark
}

/**
 * T-SourceJumpFunnel P5 复核修正：meta 行在宽度预算内的组装——**主体优先，摘要吃剩余宽度**。
 *
 * P5 把调用栈摘要并入可见行后，若仍把「主体 + 摘要」当**单段文本**交给 [ellipsizeMiddle]
 * （保留尾部 30%），窄窗下主体会被整段挤掉、可见行只剩「…」+ 栈底帧——主体（表名 / wrapper 类型）
 * 是这一行的主信息，栈底恰是调用链里信息量最低的一端，优先级倒挂。复核（20260920）实测默认宽度
 * 预算仅够 15~25 字符，而摘要上限 120 字符，故必然触发。
 *
 * 组装规则：主体自身超预算 → 主体中段省略（F3 原行为）；主体放得下 → 摘要按剩余宽度截尾；
 * 剩余不足 [META_DIGEST_MIN_PX] → 整段丢弃摘要。[digest] 为空（无调用栈快照）时逐字退回 F3
 * 原行为，渲染不变。
 *
 * @param full   完整行（主体 + 摘要）——放得下即整段返回（与改前逐字节一致）
 * @param head   主体（meta + 警示行）
 * @param digest 调用栈内联摘要（`   调用栈: <摘要>`；无快照时为空串）
 */
internal fun metaLineWithinBudget(
    full: String,
    head: String,
    digest: String,
    widthOf: (String) -> Int,
    budget: Int,
): String {
    if (widthOf(full) <= budget) return full
    if (digest.isEmpty()) return ellipsizeMiddle(head, widthOf, budget)
    val remain = budget - widthOf(head)
    if (remain < META_DIGEST_MIN_PX) return ellipsizeMiddle(head, widthOf, budget)
    return head + clipTailToWidth(digest, widthOf, remain)
}
