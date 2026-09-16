package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettings.Companion.LOG_TIMESTAMP_SOURCE_LOG
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import javax.swing.Action
import javax.swing.JComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.awt.RelativePoint
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JRadioButtonMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.JToggleButton
import javax.swing.ListModel
import javax.swing.SwingConstants
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复；T-M42 提为顶层，openPreview/右键复制/列表复制共用）。 */
internal fun withTerminator(sql: String): String =
    if (sql.isEmpty() || sql.endsWith(";")) sql else sql + ";"

/**
 * 右键菜单选中收敛（T-ListPreviewCopy）：命中行已在多选集合内 → 保留原多选（复制项=批量复制）；
 * 否则收敛为命中行（右键=切换操作目标，与改造前无条件收敛单行等价）。纯函数可独立单测，
 * 日志 tab 与历史 tab 右键菜单共用。
 */
internal fun resolveContextMenuSelection(selectedIndices: IntArray, hitIndex: Int): IntArray =
    if (hitIndex in selectedIndices) selectedIndices else intArrayOf(hitIndex)

/**
 * 批量复制文本（T-ListPreviewCopy）：选中条目升序逐条 [withTerminator] 后 `"\n"` 拼接——
 * 与 JBList 内置 Ctrl+C 多选拼接链路（itemToText 逐条 + join("\n")）逐字节同构，
 * 右键批量复制与键盘复制两条入口产物一致。
 */
internal fun batchCopySql(entries: List<LogSqlEntry>): String =
    entries.joinToString("\n") { withTerminator(it.restoredSql) }

/** 复制菜单项文案（T-ListPreviewCopy）：多选带条数走 [selectedKey]（`{0}`=条数），单选沿用 [baseKey]。 */
internal fun copyMenuText(baseKey: String, selectedKey: String, count: Int): String =
    if (count > 1) MpSqlVisualizerBundle.message(selectedKey, count.toString())
    else MpSqlVisualizerBundle.message(baseKey)

// ---------- T-LogListFreeze：列表刷新分批常量 ----------

/** 首屏立即写入条数（保证快速首屏，EDT 冻结可控）。 */
internal const val REFRESH_FIRST_BATCH_SIZE = 30

/** 每批异步追加条数（invokeLater 每批一次 EDT 脉冲）。 */
internal const val REFRESH_CHUNK_SIZE = 30

/**
 * 日志条目列表（T-M42）：覆写 JBList.itemToText——内置 Ctrl+C 复制默认返回 cellRenderer
 * 渲染文本（HTML 源码，用户实测缺陷），覆写后复制输出纯 SQL（多选逐条换行拼接、
 * CopyPasteManager 写入、Ctrl+C 快捷键注册均由 JBList 既有链路复用）。
 * open：internal 类默认 final，测试子类经公开包装访问 protected itemToText。
 */
internal open class SqlEntryList(model: ListModel<LogSqlEntry>) : JBList<LogSqlEntry>(model) {
    override fun itemToText(index: Int, element: LogSqlEntry): String = textFor(element)

    /** 速度搜索与 Ctrl+C 复制共用的条目文本。 */
    internal fun textFor(element: LogSqlEntry): String = withTerminator(element.restoredSql)
}

/**
 * 排序模式（P-06 / T-LogSortModes）：默认 [TIME_ASC]（时间升序 = 捕获顺序，与未排序时观感一致）；
 * 另支持时间降序、耗时升/降序、行号。耗时缺失的条目恒沉底（两个方向的升降序都不让空值顶到前面）。
 */
enum class SortMode { TIME_ASC, TIME_DESC, DURATION_ASC, DURATION_DESC, SEQUENCE }

/** 排序方式下拉项（文案 + 模式）。 */
internal data class SortOption(val label: String, val mode: SortMode)

/** 排序方式选项（顺序即下拉序；默认项在首位 = 时间升序）。 */
internal fun sortOptions(): List<SortOption> = listOf(
    SortOption(MpSqlVisualizerBundle.message("log.sort.time.asc"), SortMode.TIME_ASC),
    SortOption(MpSqlVisualizerBundle.message("log.sort.time.desc"), SortMode.TIME_DESC),
    SortOption(MpSqlVisualizerBundle.message("log.sort.duration.asc"), SortMode.DURATION_ASC),
    SortOption(MpSqlVisualizerBundle.message("log.sort.duration.desc"), SortMode.DURATION_DESC),
    SortOption(MpSqlVisualizerBundle.message("log.sort.sequence"), SortMode.SEQUENCE),
)

/** 类型多选弹窗可勾选的类型（OTHER 不单列；全选时 OTHER 一并透传）。 */
internal val FILTERABLE_TYPES: List<StatementType> = listOf(
    StatementType.SELECT,
    StatementType.INSERT,
    StatementType.UPDATE,
    StatementType.DELETE,
)

/**
 * 类型过滤（纯逻辑，可独立单测）：所选类型覆盖全部可筛选类型时视为「全部」，
 * 透传所有条目（含 OTHER）；否则仅保留分类命中的条目。
 */
internal fun filterByType(entries: List<LogSqlEntry>, activeTypes: Set<StatementType>): List<LogSqlEntry> {
    if (activeTypes.containsAll(FILTERABLE_TYPES)) return entries
    // T-CrudColor：改用条目级缓存属性（行为等价）——避免每次刷新都重跑分类
    // （classify 内 Regex 每次编译 Pattern），与渲染器共用同一份结果。
    return entries.filter { it.statementType in activeTypes }
}

/**
 * 搜索过滤（纯逻辑，可独立单测）：查询词忽略大小写 contains，匹配还原 SQL 与摘要
 * （摘要含参数值的还原 SQL 前缀；参数值是常见检索目标）；空白查询透传全部。
 *
 * T-LogSearchRowTime 扩展：
 * - 行号——`#5` / `5` 精确 equals [LogSqlEntry.sequenceNumber]（渲染形态 `#123`，所见即可搜；
 *   精确而非 contains：搜 `1` 不应命中全部 1xx 行；0 = 无行号条目天然不命中任何行号查询）
 * - 时间——对显示片段（[timestampDisplayText]，含方括号与设置格式，所见即所搜）contains；
 *   显示开关关闭时片段为 null，时间分支天然不参与
 * [timestampTextOf] 可注入（测试绕开设置读取），默认取当前设置下的真实显示片段。
 */
internal fun filterBySearch(
    entries: List<LogSqlEntry>,
    query: String,
    timestampTextOf: (LogSqlEntry) -> String? = ::timestampDisplayText,
): List<LogSqlEntry> {
    val q = query.trim()
    if (q.isEmpty()) return entries
    val rowNumber = q.removePrefix("#").toLongOrNull()?.takeIf { it > 0 }
    return entries.filter {
        it.restoredSql.contains(q, ignoreCase = true)
            || it.summary.contains(q, ignoreCase = true)
            || (rowNumber != null && it.sequenceNumber == rowNumber)
            || timestampTextOf(it)?.contains(q, ignoreCase = true) == true
    }
}

/**
 * 时间显示片段文本（T-LogSearchRowTime）：`[yyyy-MM-dd HH:mm:ss]` 形态（去段间距尾随空格），
 * 与列表渲染逐字节一致（所见即所搜/所复制的单一真源——格式/来源/开关语义全在
 * [formatTimestampFragment]）；开关关闭返回 null（页面上没有时间片段可搜可复制）。
 */
internal fun timestampDisplayText(entry: LogSqlEntry): String? =
    formatTimestampFragment(entry)?.first?.trim()

/**
 * 批量复制时间戳（T-LogSearchRowTime）：显示片段去方括号（用户定调：复制出纯时间文本
 * `2024-01-15 10:30:45`，不带显示装饰括号）逐行拼接，与 [batchCopySql] 同构；
 * 显示开关关闭时片段为 null 被跳过——全部关闭时返回空串（菜单项据此置灰）。
 */
internal fun batchCopyTimestamps(entries: List<LogSqlEntry>): String =
    entries.mapNotNull { timestampDisplayText(it)?.removeSurrounding("[", "]") }.joinToString("\n")

// ---------- T-Perf-03：流式增量刷新差分（纯逻辑，可独立单测） ----------

/**
 * M56 T-M56-00：动态聚焦页签过滤层（纯逻辑，插在 [filterByType] 与 [filterBySearch] 之间）。
 * [FocusFilter.method] 匹配 [LogSqlEntry.mapperStatement]（agent 通道直取 / 桥通道标记第 4 段，
 * 忽略大小写）；[FocusFilter.requestTag] 精确匹配 [LogSqlEntry.requestTag]。
 * 双条件为「且」；null 维度透传；全 null 等价不过滤。T-M56-06 起 method 扩展真实方法边界
 * `{Class}#{method}` 形态（届时在 [focusMethodKey] 统一登记来源）。
 */
internal data class FocusFilter(
    val method: String? = null,
    val requestTag: String? = null,
) {
    /** 是否为空过滤（全维度 null = 透传）。 */
    val isPassthrough: Boolean get() = method == null && requestTag == null
}

/** 条目的聚焦 method 键（当前 = mapper 语句 id；T-M56-06 扩展真实方法边界）。 */
internal fun focusMethodKey(entry: LogSqlEntry): String? = entry.mapperStatement

internal fun filterByFocus(entries: List<LogSqlEntry>, focus: FocusFilter?): List<LogSqlEntry> {
    if (focus == null || focus.isPassthrough) return entries
    return entries.filter { e ->
        val methodOk = focus.method?.let { f ->
            focusMethodKey(e)?.equals(f, ignoreCase = true) == true
        } ?: true
        val tagOk = focus.requestTag?.let { f -> f == e.requestTag } ?: true
        methodOk && tagOk
    }
}

/** 同步增量追加条数上限：超过则回退全量重建（全量重建带分批 EDT 写入，大批量更稳）。 */
internal const val MAX_SYNC_APPEND = 300

/**
 * 新旧可见列表差分结果（增量刷新决策；元素按同一性 `===` 比较——条目为不可变数据类，
 * 缓冲内同一条目恒为同一实例）。（T-Perf-03 自 dev 移植）
 */
internal sealed class VisibleDiff {
    /** 头部淘汰 `evicted` 条 + 尾部追加 `appended`（缓冲满后的流式 steady state；appended 可为空）。 */
    data class HeadEvictAppend(val evicted: Int, val appended: List<LogSqlEntry>) : VisibleDiff()
    /** 从 `index` 起插入 `entries`（尾部纯追加时 index == old.size）。 */
    data class Insert(val index: Int, val entries: List<LogSqlEntry>) : VisibleDiff()
    /** 尾部纯移除：移除 `[from, old.size)`。 */
    data class TailRemove(val from: Int) : VisibleDiff()
    /** 无变化。 */
    object Same : VisibleDiff()
    /** 无法增量（离散增删/超大追加）——全量重建。 */
    object FullRebuild : VisibleDiff()
}

/** 同一性公共前缀长度。 */
internal fun commonPrefixLength(old: List<LogSqlEntry>, new: List<LogSqlEntry>): Int {
    val n = minOf(old.size, new.size)
    var i = 0
    while (i < n && old[i] === new[i]) i++
    return i
}

/** 同一性公共后缀长度（不与 prefix 重叠）。 */
internal fun commonSuffixLength(old: List<LogSqlEntry>, new: List<LogSqlEntry>, prefix: Int): Int {
    val n = minOf(old.size, new.size) - prefix
    var i = 0
    while (i < n && old[old.size - 1 - i] === new[new.size - 1 - i]) i++
    return i
}

/** [old] 的 [aStart..] 与 [new] 的 [bStart..] 前 [length] 个元素是否同一。 */
internal fun identityPrefix(old: List<LogSqlEntry>, aStart: Int, new: List<LogSqlEntry>, bStart: Int, length: Int): Boolean {
    if (old.size - aStart < length || new.size - bStart < length) return false
    for (i in 0 until length) if (old[aStart + i] !== new[bStart + i]) return false
    return true
}

/**
 * 可见列表差分：流式灌入时把「clear+全量重建」降阶为「尾部追加 / 头部淘汰+追加」。
 *
 * 判定顺序：
 *  1. 头部淘汰对齐（evicted>0 且 old 去头后与 new 前缀同一）→ HeadEvictAppend；
 *  2. 前缀/后缀差分 → 纯插入（含尾部追加）/ 尾部纯移除 / 无变化；
 *  3. 其余（过滤、搜索、去重裁剪、排序导致的离散增删）→ FullRebuild。
 */
internal fun diffVisible(old: List<LogSqlEntry>, new: List<LogSqlEntry>, evicted: Int): VisibleDiff {
    if (evicted > 0 && old.size >= evicted && new.size >= old.size - evicted &&
        identityPrefix(old, evicted, new, 0, old.size - evicted)
    ) {
        return VisibleDiff.HeadEvictAppend(evicted, new.drop(old.size - evicted))
    }
    val prefix = commonPrefixLength(old, new)
    val suffix = commonSuffixLength(old, new, prefix)
    val removed = old.size - prefix - suffix
    val added = new.size - prefix - suffix
    return when {
        removed == 0 && added == 0 -> VisibleDiff.Same
        removed == 0 && added in 1..MAX_SYNC_APPEND -> VisibleDiff.Insert(prefix, new.subList(prefix, prefix + added))
        added == 0 && suffix == 0 -> VisibleDiff.TailRemove(prefix)
        else -> VisibleDiff.FullRebuild
    }
}

/**
 * 工具窗口面板（标签页 + 工具栏；headless 测试不布局，交互断言走条目模型层）。
 *
 * 筛选项精简改造（2026-08-27）：
 * - 类型筛选：工具栏行「SQL类型」多选按钮（弹窗勾选，替代原两行按钮组）
 * - 高频折叠：整体移除（识别重复 SQL 的价值由去重 ⨉N 徽章承接）
 * - 去重徽章：开启「移除重复的sql」后被丢弃的 SQL 在条目旁累加显示 ⨉N
 * - Ctrl+F/⌘F 搜索：默认隐藏的搜索条，快捷键唤起实时过滤（Esc 清空后再按收起）
 */
class SqlLogPanel(
    private val project: Project,
    private val manager: SqlLogManager,
) : JPanel(BorderLayout()), Disposable {

    private val listModel = DefaultListModel<LogSqlEntry>()
    private val list = SqlEntryList(listModel)
    /** 列表条目单元格渲染器（internal 供 UI 测试直接渲染、断言 HTML 输出）。 */
    private lateinit var entryCellRenderer: LogEntryListCellRenderer
    /** T-ClickPreview：单击出轻量预览弹窗（点击时按行捕获 SQL，锚点在行下缘不遮行），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。
     *  T-ListPreviewCopy：预览文本经 clickPreviewSql——设置「预览时格式化还原 SQL」开启（默认）时多行格式化显示。 */
    private val clickPreview = SqlClickPreview(project, list) {
        i -> clickPreviewSql(list.model.getElementAt(i).restoredSql, PluginSettings.getInstance().state.logFormatSql)
    }
    private val tabs = JBTabbedPane()
    /** T-M38：日志转 SQL 转换 tab（粘贴日志 → 批量还原可执行 SQL；原「SQL 预览」被动展示改造）。 */
    private val logToSqlPanel = LogToSqlPanel(project)
    /** T-M36.4：SQL → Java 转换 tab（粘贴 SQL → 格式化/补全 → 转换 → 可编辑 Java 高亮结果区）。 */
    private val sqlToJavaPanel = SqlToJavaPanel(project)
    /** F-44：历史 SQL tab（读取 .idea 目录中的自动保存文件，支持源码定位）。仅 logAutoSaveEnabled 时创建。 */
    private val historyPanel: HistorySqlLogPanel by lazy { HistorySqlLogPanel(project) }
    /** F-44：历史 tab 在 JBTabbedPane 中的索引（-1 表示未添加）。 */
    private var historyTabIndex = -1
    private val dedupeCheckBox = JCheckBox(MpSqlVisualizerBundle.message("log.panel.dedupe")).apply {
        isSelected = manager.entries.dedupeEnabled
        addActionListener {
            // 当前 UI 选中态（同步给 buffer）
            val nowChecked = isSelected
            val wasEnabled = manager.entries.dedupeEnabled
            if (nowChecked == wasEnabled) return@addActionListener
            if (nowChecked) {
                manager.entries.dedupeEnabled = true
                manager.entries.resetDedupeCounts()
                // F-44-2 用户实测缺：勾选「移除重复的sql」后立即对已有条目去重
                // —— 此前仅清计数、不裁剪缓冲，重复 SQL 继续展示
                val removed = manager.entries.dedupeExisting()
                if (removed > 0) {
                    LOG.info("dedupeCheckBox: 开启去重后裁掉了 $removed 条已存在重复 SQL")
                }
            } else {
                // 关闭去重：清空累计计数（语义：关闭 = 本次累计清零）
                manager.entries.disableDedupe()
            }
            refreshList()
        }
    }

    // ---------- 过滤/排序状态 ----------

    /** 当前激活的类型过滤集合（默认全选 = 「全部」，含 OTHER 透传）。 */
    private val activeFilter = FILTERABLE_TYPES.toMutableSet()

    /** 排序模式（T-LogSortModes：默认时间升序 = 捕获顺序）。 */
    private var sortMode = SortMode.TIME_ASC

    /** 当前搜索词（搜索条实时同步；空串 = 不过滤）。 */
    private var searchText = ""

    /**
     * M56 T-M56-00：动态聚焦过滤状态（基础设施；null = 不过滤）。
     * T-M56-02/06/07 起 agent 通道接管后由页签/请求聚合驱动写入；桥通道恒 null 与现状逐字一致。
     */
    @Volatile
    private var activeFocus: FocusFilter? = null

    /** 去重丢弃计数快照（refreshList 后更新；cellRenderer 渲染 ⨉N 徽章用）。 */
    private var dedupeCounts: Map<String, Int> = emptyMap()

    // ---------- T-Perf-03（自 dev 移植）：增量刷新状态 ----------

    /** 刷新代际：新 refreshList 启动时 +1，旧分批追加链检测到代际过期即终止。 */
    private var refreshGeneration = 0L

    /** 上一轮渲染的可见列表（同一性差分基线；由 applyIncremental/applyFullRebuild 与 listModel 保持同步）。 */
    private var lastRendered: List<LogSqlEntry> = emptyList()

    // T-Perf-15：manager 监听器命名引用（uiRefreshListener/capturingUiListener）声明于 init 前统一区块。

    // ---------- O-1 日志窗口差异化：慢查询计数器 + 耗时着色 ----------

    /** 慢查询计数器（O-1 Phase C）。 */
    private val slowQueryCounter = SlowQueryCounter(
        PluginSettings.getInstance().state.slowQueryThresholdMs,
    )

    /** 工具栏慢查询计数标签（动态更新）。 */
    private var slowQueryLabel: JLabel? = null

    // ---------- UI 组件（工具栏行：捕捉/清空/设置/帮助 + 去重勾选 + SQL类型 + 耗时排序） ----------

    /** SQL类型标签（独立于按钮——`SQL类型：[状态]`，状态文字只放按钮）。 */
    private val typeFilterLabel = JLabel(MpSqlVisualizerBundle.message("log.filter.type.label"))

    /** SQL类型多选按钮（点击弹可勾选弹窗；文案只显示选中状态：全部 / 无 / 「查询, 更新」组合）。 */
    private val typeFilterButton = JButton().apply {
        isFocusable = false
        icon = AllIcons.General.ArrowDown
        horizontalTextPosition = SwingConstants.LEFT // 图标在文字右侧（下拉按钮惯例）
        toolTipText = MpSqlVisualizerBundle.message("log.filter.type")
        addActionListener { showTypeFilterPopup() }
    }

    /** 排序标签（`排序：`，与 SQL类型标签同款式）。 */
    private val sortLabel = JLabel(MpSqlVisualizerBundle.message("log.sort.label"))

    /**
     * 排序方式按钮（T-LogSortModes）：点击弹单选菜单，文案显示当前方式；与「SQL类型」按钮同款式
     * （原生 LaF 扁平按钮 + 右侧下箭头），默认时间升序。取代原「耗时排序」双态切换按钮——
     * 排序恒有确定方式，不再依赖「是否检测到耗时」决定显隐。
     */
    private val sortButton = JButton().apply {
        isFocusable = false
        icon = AllIcons.General.ArrowDown
        horizontalTextPosition = SwingConstants.LEFT // 图标在文字右侧（下拉按钮惯例，同 SQL类型）
        toolTipText = MpSqlVisualizerBundle.message("log.sort.label")
        addActionListener { showSortPopup() }
    }

    // ---------- T-ListMode：SQL 显示模式（T-DisplayModeToSettings：入口迁设置页「列表显示」，两列表共享一键） ----------

    /** 搜索条（默认隐藏，Ctrl+F/⌘F 唤起；实时过滤 restoredSql 与摘要）。 */
    private val searchField = buildSearchField()

    // ---------- 测试访问器（headless 测试断言用） ----------

    /** 当前列表模型条目数（测试断言）。 */
    internal val visibleEntryCount: Int get() = listModel.size()

    /** 当前列表模型条目 restoredSql 列表（测试断言）。 */
    internal val visibleEntries: List<String>
        get() = (0 until listModel.size()).map { listModel.getElementAt(it).restoredSql }

    /** 当前激活的类型过滤集合（测试断言）。 */
    internal val currentFilter: Set<StatementType> get() = activeFilter.toSet()

    /** 当前排序模式（测试断言）。 */
    internal val currentSortMode: SortMode get() = sortMode

    /** 排序方式选择（测试驱动：等价用户在菜单里点选某项）。 */
    internal fun selectSortModeForTest(mode: SortMode) = applySortMode(mode)

    /** 排序按钮（测试断言文案/可见性）。 */
    internal val sortButtonForTest: JButton get() = sortButton

    /** 排序菜单项文案列表（测试断言选项齐备；构建菜单但不 show）。 */
    internal fun sortMenuLabelsForTest(): List<String> = buildSortMenuItems().map { it.text }

    /** 当前排序方式按钮文案（测试断言）。 */
    internal val sortButtonTextForTest: String get() = sortButton.text

    /** 去重勾选框（F-44-2 测试驱动：模拟用户勾选「移除重复的sql」验证既有条目立即去重）。 */
    internal val dedupeCheckBoxForTest: JCheckBox get() = dedupeCheckBox

    /** SQL类型多选按钮（测试断言文案）。 */
    internal val typeFilterButtonForTest: JButton get() = typeFilterButton

    /** T-SelToggle：类型弹窗底部全选/全不选切换按钮（未打开弹窗时为 null）。 */
    internal val typePopupToggleButtonForTest: JButton? get() = typePopupToggleButton

    /**
     * T-SelToggle：headless 测试直接构建弹窗内容（不经 `popup.show`——无显示设备时
     * lightweight popup 不可靠），返回后 [typePopupToggleButtonForTest] 即非 null 可驱动。
     */
    internal fun buildTypeFilterContentForTest(): JPanel = buildTypeFilterContent()

    /** SQL类型标签（测试断言存在性与文案）。 */
    internal val typeFilterLabelForTest: JLabel get() = typeFilterLabel

    /** 应用类型过滤组合（测试驱动：设置选中集 + 刷新按钮文案与列表）。 */
    internal fun applyTypeFilterForTest(types: Set<StatementType>) {
        activeFilter.clear()
        activeFilter.addAll(types)
        updateTypeFilterButton()
        refreshList()
    }

    /**
     * M56 T-M56-00：设置动态聚焦过滤（页签/请求聚合驱动；null 或全空维度 = 取消聚焦）。
     * 桥通道不写此状态，行为与现状逐字一致。过滤值变化经 refreshList 全量差分呈现
     * （离散增删走 FullRebuild，无需特殊增量分支）。
     */
    internal fun setFocusFilter(focus: FocusFilter?) {
        val next = focus?.takeUnless { it.isPassthrough }
        if (next == activeFocus) return
        activeFocus = next
        refreshList()
    }

    /** 搜索条（测试断言显隐/文本）。 */
    internal val searchFieldForTest: SearchTextField get() = searchField

    /** 单击预览控制器（T-ClickPreview 测试断言：排程/取消/弹窗残留）。 */
    internal val clickPreviewForTest: SqlClickPreview get() = clickPreview

    /** 工具栏慢查询计数标签（测试断言显隐与文案）。 */
    internal val slowQueryLabelForTest: JLabel? get() = slowQueryLabel

    /**
     * 捕捉开关动作（T-UiNative T-UN-11）：挂工具窗标题栏（SqlLogToolWindowFactory 调
     * ToolWindow.setTitleActions），面板工具栏不再含捕捉按钮。图标/文案在 [CaptureAction.update]
     * 按 manager.capturing 态输出；测试断言直接读 captureToggleAction.presentation，不依赖按钮刷新。
     */
    val captureToggleAction: AnAction = CaptureAction()

    /**
     * 工具栏动作组（T-M39：internal 供测试断言帮助入口存在性；
     * update 由工具栏生命周期驱动，headless 测试手动触发；须在 init 前声明）。
     * T-UiNative T-UN-11：捕捉开关迁工具窗标题栏（[captureToggleAction]），此处不再包含。
     */
    internal val toolbarGroup = DefaultActionGroup(
        ClearAction(),
        SettingsAction(),
        ShowSearchAction(), // 搜索在帮助左侧（用户指定排序）
        HelpAction(),
    )

    // ---------- T-Perf-15：manager 监听器命名引用（init 注册 / dispose 注销） ----------

    /** 缓冲变更 → 列表刷新（manager.uiNotifyListener 回调链；EDT 执行）。 */
    private val uiRefreshListener: () -> Unit = { refreshList() }

    /** capturing 状态变化 → 刷新捕捉动作 presentation（T-UiNative T-UN-11：动作挂工具窗标题栏，
     *  图标/文案由 [CaptureAction.update] 按 capturing 态输出）。 */
    private val capturingUiListener: () -> Unit = {
        LOG.info("SqlLogPanel.capturingListener: 触发")
        ApplicationManager.getApplication().invokeLater {
            // T-UiNative T-UN-11：反射 hack（updateCaptureButtonIcon 遍历组件树强改 ActionButton 图标）
            // 退役——回归平台更新机制。真实 IDE 中工具窗可见，isShowing 满足，updateActionsImmediately
            // 正常执行；沙盒/单测中组件不可见时平台会跳过更新——测试断言直接读
            // captureToggleAction.presentation，不依赖按钮刷新。
            _toolbarRef?.updateActionsImmediately()
        }
    }

    // ---------- T-M55-03/04：动态聚焦过滤页签栏字段（须在 init 块之前——Kotlin 按文本序初始化，
    // init 里 buildTopPanel 会读这些字段） ----------

    /** 当前开启的聚焦页签（EDT 读写——右键菜单/页签关闭按钮均在 EDT）。 */
    private val focusTabs = mutableListOf<FocusTab>()

    /** 聚焦页签栏（工具栏行与搜索条之间；无页签时隐藏不占高度）。 */
    private val focusTabBar = JPanel(WrapLayout()).apply { isOpaque = false; isVisible = false }

    /** 聚焦隐藏计数提示（聚焦视图下不匹配任何页签的条目数；无页签时隐藏）。 */
    private val focusHiddenLabel = JLabel().apply {
        foreground = MpvsUiColors.META_GRAY
        isVisible = false
    }

    init {
        // 注册子面板到 Disposer 树：父面板 dispose 时级联释放子面板（及其 DocumentListener），
        // 避免 IDE 退出时 ObjectTree 报 Memory leak detected
        Disposer.register(this, logToSqlPanel)
        Disposer.register(this, sqlToJavaPanel)

        // 标签 1：SQL 日志（工具栏行 + 搜索条 + 条目列表）
        val logPanel = JPanel(BorderLayout())
        logPanel.add(buildTopPanel(), BorderLayout.NORTH)
        logPanel.add(buildListPane(), BorderLayout.CENTER) // 条目列表铺满剩余空间

        // 标签 2：日志转 SQL（粘贴日志 → 批量还原可执行 SQL）
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logs"), logPanel)
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logToSql"), logToSqlPanel)
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.sqlToJava"), sqlToJavaPanel)
        // F-44：开启自动保存、或 .idea 中已有历史文件时显示历史 tab
        if (PluginSettings.getInstance().state.logAutoSaveEnabled || hasHistoryFiles()) {
            addHistoryTab()
        }
        add(tabs, BorderLayout.CENTER)

        // T-M36.6：tab 重选时刷新形态下拉（设置页修改后切回即同步；不触发重生成）
        tabs.addChangeListener {
            if (tabs.selectedComponent == sqlToJavaPanel) {
                sqlToJavaPanel.refreshFormatFromSettings()
            }
            // F-44：切换到历史 tab 时刷新数据
            if (historyTabIndex >= 0 && tabs.selectedIndex == historyTabIndex) {
                historyPanel.refresh()
            }
        }

        // F-44 历史 tab 显隐跟随设置（设置页修改后即时生效，无需切 tab）
        val settingsListener = SettingsChangeListener {
            ApplicationManager.getApplication().invokeLater {
                updateHistoryTabVisibility()
                // T-LogListFreeze-04：片段渲染直读设置态（时间戳/耗时显示/慢查询阈值）——设置变更后
                // 走 refreshList 重排重绘即生效（SINGLE_LINE 直渲染；多行模式片段缓存按模式键自动失效）
                // T-ListMode：显示模式入口已迁设置页（T-DisplayModeToSettings）——设置页 apply 广播后
                // 行高随模式重设。T-MergeFollowup-03（复查二轮）：BasicListUI 的 cellHeights 布局缓存
                // **不归 Swing layout 管**——revalidate 无效；且多行模式间切换是 -1→-1（setFixedCellHeight
                // 值不变不触发 propertyChange 失效），refreshList 增量路径在内容无变化时也不产生 model
                // 结构事件——updateUI() 重装列表 UI 强制布局状态失效，是最直接可靠的失效手段。
                applyListCellHeight()
                list.updateUI()
                refreshList()
                // T-CrudColor：历史二级列表同样直读设置，重绘即生效（不重扫文件、不打断浏览位置）。
                // 仅当历史 tab 已挂载时才取面板——避免仅为重绘而强制实例化（historyPanel 是 lazy）
                if (tabs.indexOfTab(MpSqlVisualizerBundle.message("log.tab.history")) >= 0) {
                    historyPanel.repaintSqlList()
                }
            }
        }
        project.messageBus.connect(this).subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, settingsListener)

        // T-Perf-15（自 dev 移植）：监听器改命名引用——dispose 时精确注销（此前 addUiListener/addCapturingListener
        // 只加不减，面板随 tool window content 销毁重建逐次累积）
        manager.addUiListener(uiRefreshListener)
        refreshList()

        // capturing 变化 → 刷新捕捉动作（T-UiNative T-UN-11：动作挂工具窗标题栏，presentation
        // 由 CaptureAction.update 按 capturing 态输出；监听经平台更新机制刷新，沙盒测试断言直读
        // captureToggleAction.presentation）
        manager.addCapturingListener(capturingUiListener)
    }

    // ---------- T-M55-03/04：动态聚焦过滤页签栏（页签=过滤器，多开并集，可关闭；不碰差分） ----------
    // 字段（focusTabs/focusTabBar/focusHiddenLabel）声明在类头部 init 块之前（文本序初始化）

    /** 当前开启的聚焦页签（测试断言）。 */
    internal fun focusTabsForTest(): List<FocusTab> = focusTabs.toList()

    /** 聚焦页签栏子组件数（测试断言：页签数 = chips + 前缀标签 + 计数标签）。 */
    internal val focusTabBarComponentCountForTest: Int get() = focusTabBar.componentCount

    /** 聚焦隐藏计数提示（测试断言显隐与文案）。 */
    internal val focusHiddenLabelForTest: JLabel get() = focusHiddenLabel

    /** 测试驱动：等价右键「聚焦调用方方法/按请求聚焦」添加页签。 */
    internal fun addFocusTabForTest(tab: FocusTab) = addFocusTab(tab)

    /** 测试驱动：等价页签 ✕ 关闭。 */
    internal fun removeFocusTabForTest(tab: FocusTab) = removeFocusTab(tab)

    private fun addFocusTab(tab: FocusTab) {
        val next = when (tab) {
            is FocusTab.Request -> addRequestTab(focusTabs, tab.requestTag)
            is FocusTab.Method -> if (focusTabs.contains(tab)) focusTabs.toList() else focusTabs + tab
        }
        focusTabs.clear()
        focusTabs.addAll(next)
        rebuildFocusTabBar()
        refreshList()
    }

    private fun removeFocusTab(tab: FocusTab) {
        if (focusTabs.remove(tab)) {
            rebuildFocusTabBar()
            refreshList()
        }
    }

    /** 重建页签栏 chips（页签数 = 前缀标签 + N×chip + 计数标签）。 */
    private fun rebuildFocusTabBar() {
        focusTabBar.removeAll()
        focusTabBar.add(JLabel(MpSqlVisualizerBundle.message("log.focus.label")))
        focusTabs.forEach { focusTabBar.add(buildFocusChip(it)) }
        focusTabBar.add(focusHiddenLabel)
        focusTabBar.isVisible = focusTabs.isNotEmpty()
        focusTabBar.parent?.revalidate()
        focusTabBar.parent?.repaint()
    }

    /** 单个聚焦页签 chip：名称 + ✕ 关闭（关闭即摘除过滤器并刷新）。 */
    private fun buildFocusChip(tab: FocusTab): JComponent {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { isOpaque = false }
        chip.add(JLabel(tab.label).apply { toolTipText = tab.label })
        chip.add(JButton(AllIcons.Actions.Close).apply {
            isFocusable = false
            toolTipText = MpSqlVisualizerBundle.message("log.focus.close")
            addActionListener { removeFocusTab(tab) }
        })
        return chip
    }

    /** 右键入口的聚焦页签解析（sourceFile+sourceLine → 方法行号区间页签）；解析失败返回 null。 */
    internal fun resolveCallerMethodFocus(entry: LogSqlEntry): FocusTab.Method? {
        val range = CallerMethodRangeResolver.resolve(project, entry.sourceFile, entry.sourceLine) ?: return null
        return FocusTab.Method(range.sourceFile, range.startLine, range.endLine, range.className, range.methodName)
    }

    // ---------- 顶部区域（工具栏行 + 聚焦页签栏 + 搜索条） ----------

    /** 顶部竖向容器：工具栏行（NORTH）+ 聚焦页签栏（CENTER，无页签隐藏不占高度）+ 搜索条（SOUTH）。 */
    private fun buildTopPanel(): JPanel {
        val top = JPanel(BorderLayout())
        top.add(buildToolbar(), BorderLayout.NORTH)
        top.add(focusTabBar, BorderLayout.CENTER)
        top.add(searchField, BorderLayout.SOUTH)
        return top
    }

    private fun buildToolbar(): JPanel {
        // T-M31.16：暂停按钮移除（捕捉开关已覆盖——用户方案 A）
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, toolbarGroup, true)
            .apply { targetComponent = this@SqlLogPanel }
        _toolbarRef = toolbar // 保存引用供 capturing 监听刷新

        updateTypeFilterButton()
        // 左右分区：左侧 动作图标 + SQL类型单元 + 耗时排序 + 慢查询计数；
        // 右侧「移除重复的sql」勾选（用户要求移到右侧，与 SQL类型 垂直居中平行、样式统一）
        val row = JPanel(BorderLayout())

        // 左侧流式区：WrapLayout——窄侧边栏放不下时折行且高度随之增长（原生 FlowLayout
        // preferredLayoutSize 只按单行算，折行后第二行会被裁掉——「SQL类型筛选项消失」根因）
        val left = JPanel(WrapLayout())
        left.add(toolbar.component)
        // 标签 + 按钮打包成一体：折行时「SQL类型：」与状态按钮同进退，不拆散两行
        val typeFilterUnit = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(typeFilterLabel)
            add(typeFilterButton)
        }
        left.add(typeFilterUnit)
        // 排序方式单元：与 SQL类型单元同构（标签 + 控件打包，折行时同进退）
        updateSortButtonText()
        val sortUnit = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(sortLabel)
            add(sortButton)
        }
        left.add(sortUnit)
        // T-DisplayModeToSettings：显示模式入口迁设置页「列表显示」（工具栏按钮移除）
        // O-1：工具栏慢查询计数标签
        slowQueryLabel = JLabel().apply {
            // T-UN-13（T-UiNative）：写死红 → namedColor（亮值不变，主题可覆写）
            foreground = MpvsUiColors.SLOW_RED
            font = font.deriveFont(Font.BOLD)
            isVisible = false // 默认隐藏，有慢查询时显示
        }
        left.add(slowQueryLabel)
        row.add(left, BorderLayout.CENTER)

        // 右侧：去重勾选（Flow 面板带边距，BorderLayout 垂直居中 → 与左侧图标/标签同轴平行）
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
            isOpaque = false
            add(dedupeCheckBox)
        }
        row.add(right, BorderLayout.EAST)
        return row
    }

    /** 搜索条（默认隐藏；Ctrl+F/⌘F 经 ShowSearchAction 唤起）。 */
    private fun buildSearchField(): SearchTextField {
        val field = SearchTextField(false) // 不启用历史弹窗
        field.isVisible = false
        field.getTextEditor().emptyText.text = MpSqlVisualizerBundle.message("log.search.emptyText")
        field.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onSearchTextChanged()
            override fun changedUpdate(e: DocumentEvent) = onSearchTextChanged()
        })
        // Esc 收起：文本非空时平台已注册 ACTION_CLEAR_TEXT（SearchTextField.addNotify）先清空，
        // 故仅空文本时走到这里 → 收起搜索条（满足「非空先清空，已空再收起」两段式）
        field.addKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE && field.text.isEmpty()) {
                    hideSearch()
                    e.consume()
                }
            }
        })
        return field
    }

    /** Ctrl+F/⌘F 唤起搜索条（快捷键经 CommonShortcuts.getFind() 平台 keymap 自适应 mac/win）。 */
    private inner class ShowSearchAction : AnAction() {
        init {
            registerCustomShortcutSet(CommonShortcuts.getFind(), list)
        }

        override fun update(e: AnActionEvent) {
            // 图标必须在 update 里写（工具栏 ActionButton 按 presentation 渲染；
            // 不设则渲染为占位圆圈——用户实测缺陷）
            e.presentation.icon = AllIcons.Actions.Search
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.search")
        }

        override fun actionPerformed(e: AnActionEvent) {
            showSearch()
        }
    }

    private fun showSearch() {
        if (!searchField.isVisible) {
            searchField.isVisible = true
            searchField.parent?.revalidate()
            searchField.parent?.repaint()
        }
        searchField.requestFocus()
    }

    private fun hideSearch() {
        searchField.text = "" // 非空时触发 DocumentListener 清过滤并刷新
        searchField.isVisible = false
        searchField.parent?.revalidate()
        searchField.parent?.repaint()
        list.requestFocusInWindow()
    }

    // T-Perf-24（自 dev 移植）：搜索输入防抖——每击键一次 O(N) 刷新 → 停顿 200ms 应用一次
    private val searchDebounceTimer =
        javax.swing.Timer(SEARCH_DEBOUNCE_MS) { applySearchText() }.apply { isRepeats = false }

    private fun onSearchTextChanged() {
        searchDebounceTimer.restart()
    }

    private fun applySearchText() {
        searchText = searchField.text
        refreshList()
    }

    // ---------- SQL类型多选弹窗 ----------

    /** 更新 SQL类型按钮文案：标签独立展示，按钮只显示状态（全部 / 无 / 类型组合）。 */
    private fun updateTypeFilterButton() {
        typeFilterButton.text = when {
            activeFilter.containsAll(FILTERABLE_TYPES) -> MpSqlVisualizerBundle.message("log.filter.all")
            activeFilter.isEmpty() -> MpSqlVisualizerBundle.message("log.filter.type.none")
            else -> FILTERABLE_TYPES.filter { it in activeFilter }.joinToString(", ") { labelForType(it) }
        }
    }

    /** 当前打开的类型弹窗内勾选框（全选/全不选即时同步用；弹窗每次打开重建）。 */
    private var typePopupCheckBoxes: List<Pair<StatementType, JCheckBox>> = emptyList()

    /** T-SelToggle：弹窗底部全选/全不选切换按钮（T-SelToggle 双按钮合一；弹窗每次打开重建，与 [typePopupCheckBoxes] 同生命周期约定）。 */
    private var typePopupToggleButton: JButton? = null

    /**
     * T-SelToggle：弹窗切换按钮文案——activeFilter 全勾 → 「全不选」（点击清空），
     * 其余态（含空集）→ 「全选」（点击填满）。判定与外层「全部」文案（updateTypeFilterButton）同口径。
     */
    private fun typePopupToggleLabel(): String = MpSqlVisualizerBundle.message(
        if (activeFilter.containsAll(FILTERABLE_TYPES)) "log.filter.selectNone" else "log.filter.selectAll",
    )

    /**
     * T-SelToggle：弹窗切换按钮文案随勾选态刷新。弹窗无公共渲染下游可收口
     * （updateTypeFilterButton/refreshList 还被搜索等非弹窗链路调用），三个勾选态变更入口
     * （弹窗构建末尾、applyAllTypes 末尾、单类型复选框 listener）显式调用本函数。
     */
    private fun syncTypePopupToggleLabel() {
        typePopupToggleButton?.let {
            val label = typePopupToggleLabel()
            if (it.text != label) it.text = label
        }
    }

    /**
     * 弹出类型多选弹窗：勾选即时生效且弹窗不关闭（JCheckBox 非 menu item，
     * 点击不触发 popup 收起，支持连续勾选组合）；底部单个全选/全不选切换按钮（T-SelToggle）
     * 一键设置——同步刷新弹窗内勾选框选中态 + 按钮文案 + 列表（弹窗保持打开，立即可见）。
     */
    private fun showTypeFilterPopup() {
        // T-UiNative P1a：JPopupMenu 外壳 → 平台 JBPopup（createComponentPopupBuilder 承载 JCheckBox 面板；
        // 触发源是工具栏按钮 ActionListener 无 MouseEvent，锚点沿用原 show 坐标——按钮正下方 2px）
        val contentPanel = buildTypeFilterContent()
        JBPopupFactory.getInstance().createComponentPopupBuilder(contentPanel, contentPanel).apply {
            setRequestFocus(false)
            setCancelOnClickOutside(true)
        }.createPopup().show(RelativePoint(typeFilterButton, Point(0, typeFilterButton.height + 2)))
    }

    /**
     * T-SelToggle：构建弹窗内容（勾选框区 + 分隔线 + 底部切换按钮），赋值 [typePopupCheckBoxes]
     * 与 [typePopupToggleButton]。与 [showTypeFilterPopup] 拆分是纯提取（行为零变更），
     * 使测试可脱离 `popup.show` 直接驱动勾选框/切换按钮（headless 下弹层 show 不可靠）。
     */
    private fun buildTypeFilterContent(): JPanel {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        content.border = EmptyBorder(8, 12, 8, 12)
        val checkBoxes = mutableListOf<Pair<StatementType, JCheckBox>>()
        FILTERABLE_TYPES.forEach { type ->
            val cb = JCheckBox(labelForType(type), type in activeFilter).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                isFocusable = false
                addActionListener {
                    if (isSelected) activeFilter.add(type) else activeFilter.remove(type)
                    updateTypeFilterButton()
                    refreshList()
                    // T-SelToggle：单类型勾选即时同步切换按钮文案
                    syncTypePopupToggleLabel()
                }
            }
            checkBoxes += type to cb
            // T-CrudColor：复选框前置色块图例——否则用户得去设置页才能把颜色和类型对上号。
            // 样式为 off（或该类型无标记色）时不加色块，避免出现「有图例却无标记」的错位。
            // 包一层行面板保持 checkBoxes 结构与 applyAllTypes 语义不变。
            val tint = SqlListTypeColor.colorOf(type)
            content.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, cb.preferredSize.height + 2)
                if (tint != null) add(JLabel(colorSwatchIcon(tint)))
                add(cb)
            })
        }
        typePopupCheckBoxes = checkBoxes
        content.add(Box.createVerticalStrut(6))
        content.add(JSeparator().apply { alignmentX = Component.LEFT_ALIGNMENT })
        content.add(Box.createVerticalStrut(6))
        val toggleButton = JButton(typePopupToggleLabel()).apply {
            isFocusable = false
            addActionListener { applyAllTypes(!activeFilter.containsAll(FILTERABLE_TYPES)) }
        }
        typePopupToggleButton = toggleButton
        val row = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(toggleButton)
        }
        content.add(row)
        return content
    }

    /**
     * T-CrudColor：类型色块图例图标（12×12 实心方块）。
     * 用于类型过滤弹窗——纯色块、无边框，与列表中的标记同色，起对照作用。
     */
    private fun colorSwatchIcon(color: Color): Icon = object : Icon {
        override fun getIconWidth(): Int = 12
        override fun getIconHeight(): Int = 12
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            g.color = color
            g.fillRect(x, y, iconWidth, iconHeight)
        }
    }

    /** 全选/全不选（全选 = 「全部」语义，列表透传含 OTHER）：同步弹窗内勾选框 + 按钮文案 + 列表。 */
    private fun applyAllTypes(selected: Boolean) {
        activeFilter.clear()
        if (selected) activeFilter.addAll(FILTERABLE_TYPES)
        typePopupCheckBoxes.forEach { (type, cb) -> cb.isSelected = type in activeFilter }
        updateTypeFilterButton()
        refreshList()
        // T-SelToggle：切换按钮点击后文案翻转（此时 activeFilter 已是新态）
        syncTypePopupToggleLabel()
    }

    // ---------- 工具栏动作 ----------

    /** T-Perf-17：historyPanel 当前是否已注册到 Disposer 树（防重复注册守卫标志）。 */
    private var historyPanelRegistered = false

    /**
     * 添加历史 tab（T-Perf-17：Disposer 注册防重入守卫——构造期与设置页循环开关都会走到；
     * 重复注册在 2026.x 平台报 "Object must not be registered twice" 错误弹窗）。
     */
    private fun addHistoryTab() {
        if (!historyPanelRegistered) {
            Disposer.register(this, historyPanel)
            historyPanelRegistered = true
        }
        historyTabIndex = tabs.tabCount
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.history"), historyPanel)
    }

    /**
     * F-44：根据 logAutoSaveEnabled 设置 + 历史数据存在与否同步历史 tab 的显隐。
     * 开启自动保存时显示；关闭后若 .idea 中仍有历史文件，也保留 tab（只读查看已有数据）。
     * 仅当无自动保存且无历史数据时才移除 tab。
     */
    private fun updateHistoryTabVisibility() {
        val enabled = runCatching { PluginSettings.getInstance().state.logAutoSaveEnabled }.getOrDefault(false)
        val hasHistoryData = enabled || hasHistoryFiles()
        if (hasHistoryData && historyTabIndex < 0) {
            // 添加历史 tab（T-Perf-17：Disposer 注册带防重入守卫）
            addHistoryTab()
        } else if (!hasHistoryData && historyTabIndex >= 0) {
            // 移除历史 tab（避免选中正在移除的 tab）
            if (tabs.selectedIndex == historyTabIndex) {
                tabs.selectedIndex = 0
            }
            tabs.removeTabAt(historyTabIndex)
            historyTabIndex = -1
            // T-Perf-17：tab 移除时从 Disposer 树摘除——否则下次重新添加时重复注册，
            // 2026.x 平台报 "Object must not be registered twice" 错误弹窗。
            // 243 平台 Disposer 无 unregister（javap 实证），dispose(historyPanel) 即摘除并执行
            // 其轻量 dispose（仅停防抖 Timer，面板组件可复用）；dispose 后重新 register 合法。
            if (historyPanelRegistered) {
                Disposer.dispose(historyPanel)
                historyPanelRegistered = false
            }
        }
    }

    /** F-44：检查 .idea 目录中是否存在历史 SQL 文件（F-44-4：目录名随插件更名，旧名不再读取）。 */
    private fun hasHistoryFiles(): Boolean {
        val basePath = project.basePath ?: return false
        val dir = java.io.File("$basePath${java.io.File.separator}.idea${java.io.File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${java.io.File.separator}sql-history")
        return dir.isDirectory && dir.listFiles { f -> f.extension == "sql" }?.isNotEmpty() == true
    }

    /** 工具栏引用（capturing 变化时经平台更新机制刷新动作 presentation，T-UN-11）。 */
    private var _toolbarRef: ActionToolbar? = null

    /**
     * 捕捉开关（T-UiNative T-UN-11：挂工具窗标题栏，经 [captureToggleAction] 暴露）。
     * 图标/文案在 update 按 manager.capturing 态输出（平台更新机制驱动，不再反射强改按钮）。
     */
    private inner class CaptureAction : AnAction() {
        override fun update(e: AnActionEvent) {
            if (manager.capturing) {
                e.presentation.icon = AllIcons.Actions.Suspend
                e.presentation.text = MpSqlVisualizerBundle.message("log.panel.stop")
                e.presentation.description = if (manager.isWaitingForConsole) "已开启 SQL 监听，等待项目运行" else null
            } else {
                e.presentation.icon = AllIcons.Actions.Execute
                e.presentation.text = MpSqlVisualizerBundle.message("log.panel.capture")
                e.presentation.description = null
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (manager.capturing) {
                manager.stopCapture()
            } else {
                manager.startCapture(manual = true)  // 手动点击：设置 userArmed 持久标志
            }
        }
    }

    private inner class ClearAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.clear")
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.entries.clear()
        }
    }

    private inner class SettingsAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.General.Settings
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.settings")
        }

        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SqlLogConfigurable::class.java)
        }
    }

    /** T-M39：帮助入口（? 按钮，位于设置按钮旁）→ 内嵌浏览器打开本地 HTML 帮助文档。
     *  图标用 AllIcons.Actions.Help（纯灰 #6E6E6E 问号，与设置/清空按钮同色系；
     *  ContextHelp 为灰蓝圆圈 #9AA7B0，与旁边按钮色相不一致——用户反馈）。
     *
     *  打开逻辑（2026-08-31 定稿：本地 HTML 为主路径，零网络依赖）：
     *  1. JCEF 可用 → HTMLEditorProvider.openEditor(project, title, html) 打开本地
     *     marketplace-custom-page.html；3 参重载内部走 JCEFHtmlPanel.loadHTML 渲染，
     *     内联 CSS 完整保留（data: URL 走 loadURL 会丢样式——已弃用）；
     *  2. JCEF 不可用 / 资源缺失 / 异常 → BrowserUtil.browse 系统浏览器打开在线文档。
     *
     *  历史演进（详见 docs/开发计划/20260827-1700-...-开发计划.md）：
     *  - data: URL（loadURL）→ CSS 丢失 ✗；
     *  - 在线 URL + 网络探测（直连/IDE 代理）→ 国内网络 JCEF 加载超时，且检测栈复杂 ✗；
     *  - 本地 HTML（loadHTML）→ 样式完整、秒开、零依赖 ✓（定稿）。
     *  样式保证：marketplace-custom-page.html 为纯内联 <style>，无 link/script/img/url()，
     *  不发起任何外部请求，不存在资源加载失败导致缺样式。 */
    private inner class HelpAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Help
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.help")
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!com.intellij.ui.jcef.JBCefApp.isSupported()) {
                LOG.info("[HelpAction] JCEF not supported, falling back to system browser")
                browseOnlineDoc()
                return
            }
            openLocalHtml()
        }

        /** 降级：系统浏览器打开在线文档。
         *  单元测试模式必须短路——BrowserUtil.browse 不走沙盒隔离，直接 ShellExecute
         *  拉起宿主机默认浏览器（HelpUiTest 真实触发 actionPerformed，定时回归任务期间
         *  周期性弹出用户 Edge，2026-09-11 真机实证）。 */
        private fun browseOnlineDoc() {
            if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) {
                LOG.info("[HelpAction] Unit test mode, skipping system browser fallback")
                return
            }
            com.intellij.ide.BrowserUtil.browse(HELP_DOCUMENTATION_URL)
        }

        /** 内嵌浏览器打开本地 HTML 帮助页。
         *
         * 用 3 参 openEditor（内部走 JCEFHtmlPanel.loadHTML 渲染，内联 CSS 正常；
         * 此前 4 参 data: URL 走 loadURL，JCEF 对 data: URL 的 CSS 渲染会丢失——已弃用）。
         */
        private fun openLocalHtml() {
            var htmlContent = loadText("html", "marketplace-custom-page.html")
            if (htmlContent.isBlank()) {
                LOG.warn("[HelpAction] Help HTML resource is empty, falling back to online doc")
                browseOnlineDoc()
                return
            }
            try {
                // 暗色检测：IDE 主题为暗色时注入 class（JCEF 不读取 IDE 的 prefers-color-scheme）
                if (isDarkTheme()) {
                    htmlContent = htmlContent.replace(
                        "<body>",
                        "<body class=\"dark\">",
                        ignoreCase = true
                    )
                    LOG.info("[HelpAction] Dark theme detected, injected .dark class")
                }
                LOG.info("[HelpAction] Opening local help HTML via loadHTML")
                com.intellij.openapi.fileEditor.impl.HTMLEditorProvider.openEditor(
                    project, HELP_TAB_TITLE, htmlContent,
                )
            } catch (ex: Throwable) {
                LOG.warn("[HelpAction] Failed to open local help HTML, falling back to online doc", ex)
                browseOnlineDoc()
            }
        }

        /** 检测当前 IDE 是否为暗色主题（JBColor.isBright 反映用户设置，不依赖 OS）。 */
        private fun isDarkTheme(): Boolean {
            return !com.intellij.ui.JBColor.isBright()
        }

        /** 从 resources 加载文本文件。 */
        private fun loadText(basePath: String, fileName: String): String {
            return try {
                val stream = HelpAction::class.java.classLoader.getResourceAsStream("$basePath/$fileName")
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
    }

    // ---------- 条目列表 ----------

    private fun buildListPane(): JBScrollPane {
        list.emptyText.text = MpSqlVisualizerBundle.message("log.panel.empty")
        list.cellRenderer = LogEntryListCellRenderer().also { entryCellRenderer = it }
        applyListCellHeight()
        // T-UiNative T-UN-05（本次修复）：主日志列表构造即可见，速度搜索在此挂载（不移进
        // SqlEntryList.init——历史二级列表复用它却构造于 CardLayout 隐藏卡，构造期挂载不生效）
        ListSpeedSearch.installOn(list) { element -> withTerminator(element.restoredSql) }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // T-ClickPreview：先转发（仅左键；单击命中行才排程预览/双击停表并关旧预览），双击再开完整弹窗
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    clickPreview.onClick(e.clickCount, e.point)
                }
                if (e.clickCount >= 2) {
                    list.selectedValue?.let { openPreview(it) }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })
        return JBScrollPane(list)
    }

    /**
     * 行高随显示模式分流（T-ListMode × T-LogListFreeze-04 权衡）：
     * SINGLE_LINE 固定行高——WideSelectionListUI 短路逐条测量走 O(1) 快路径
     * （原可变行高 × HTML 排版 = 16s EDT Freeze；片段通道后固定行高仍是流式灌入的最优路径）；
     * KEYWORD_WRAP/FORMATTED 可变行高——条目行数不等，固定高会裁行；多行片段有条目级缓存，
     * 逐条测量只剩轻量组件布局（无 HTML 解析，冻结量级不复现；真机大日志表现见计划文档）。
     * 全文查看由单击预览/双击弹窗承接。
     */
    internal fun applyListCellHeight() {
        list.fixedCellHeight = if (PluginSettings.getInstance().sqlListDisplayMode() == SqlListDisplayMode.SINGLE_LINE) {
            measureFixedCellHeight()
        } else {
            -1
        }
    }

    /** T-LogListFreeze-04：固定行高实测——样例条目渲染一次取首选高（两行文本 + 5,8 空边框 + 1px 分隔线）。 */
    private fun measureFixedCellHeight(): Int {
        val sample = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList())
        val c = entryCellRenderer.getListCellRendererComponent(list, sample, 0, false, false)
        val measured = c.preferredSize.height
        return if (measured > 0) measured else list.getFontMetrics(list.font).height * 2 + 13
    }

    // ---------- 刷新管线（类型过滤 → 搜索过滤 → 排序 → 渲染） ----------

    /**
     * 刷新列表（过滤 → 搜索 → 排序 → 差分渲染）。
     *
     * 执行顺序：
     * 1. [filterByType]：按 [activeFilter] 筛选（全选 = 全部，含 OTHER）
     * 2. [filterByFocus]：按 [activeFocus] 聚焦（M56 T-M56-00 基础设施；null = 透传）
     * 3. [filterBySearch]：按 [searchText] 搜索（大小写不敏感）
     * 4. [applySort]：按 [sortMode] 排序（降序耗时）
     * 5. 差分渲染：T-Perf-03 增量刷新——流式追加免全量重建；无法增量时回退分批全量重建
     *
     * T-LogListFreeze-04：HTML 缓存随片段通道退役——徽章计数在渲染时直读 [dedupeCounts]，
     * 原「清缓存/选择性失效」机制一并移除。
     */
    private fun refreshList() {
        val gen = ++refreshGeneration
        val snapshot = manager.entries.snapshot()

        // 去重丢弃计数快照（徽章渲染数据源）：徽章在元信息行渲染时直读，无缓存失效链
        dedupeCounts = manager.entries.dedupeCountsSnapshot()
        entryCellRenderer.dedupeCounts = dedupeCounts

        val filtered = filterByFocus(filterByType(snapshot, activeFilter), activeFocus)
        // T-M55-03/04：动态聚焦页签层（页签=过滤器，多开并集；离散增删走 FullRebuild，差分零改动）
        val focused = filterByFocusTabs(filtered, focusTabs)
        val searched = filterBySearch(focused, searchText)
        val newVisible = applySort(searched)
        val evicted = manager.entries.drainEvictedCount()

        if (!applyIncremental(newVisible, evicted)) {
            applyFullRebuild(newVisible, gen)
        }

        // 聚焦隐藏计数提示（聚焦层单独度量：输入 filtered → 输出 focused）
        if (focusTabs.isEmpty()) {
            focusHiddenLabel.isVisible = false
        } else {
            val hidden = filtered.size - focused.size
            focusHiddenLabel.text = MpSqlVisualizerBundle.message("log.focus.hidden", hidden.toString())
            focusHiddenLabel.isVisible = hidden > 0
        }

        // O-1：同步慢查询阈值 + 更新计数（Phase C）
        slowQueryCounter.thresholdMs = PluginSettings.getInstance().state.slowQueryThresholdMs
        slowQueryCounter.reset()
        snapshot.forEach { slowQueryCounter.report(it.executionTimeMs) }
        updateSlowQueryLabel()
    }

    /**
     * T-Perf-03（自 dev 移植）：增量应用差分（同步完成，无异步追加链）。模型与 [lastRendered] 尺寸不一致
     * （旧异步追加链被新刷新中断/外部改动）时拒绝增量——全量重建兜底。
     */
    private fun applyIncremental(newVisible: List<LogSqlEntry>, evicted: Int): Boolean {
        if (listModel.size() != lastRendered.size) return false
        return when (val diff = diffVisible(lastRendered, newVisible, evicted)) {
            is VisibleDiff.Same -> { lastRendered = newVisible; true }
            is VisibleDiff.HeadEvictAppend -> {
                repeat(diff.evicted) { listModel.remove(0) }
                diff.appended.forEach { listModel.addElement(it) }
                lastRendered = newVisible
                true
            }
            is VisibleDiff.Insert -> {
                diff.entries.forEachIndexed { i, e -> listModel.add(diff.index + i, e) }
                lastRendered = newVisible
                true
            }
            is VisibleDiff.TailRemove -> {
                for (i in lastRendered.size - 1 downTo diff.from) listModel.remove(i)
                lastRendered = newVisible
                true
            }
            is VisibleDiff.FullRebuild -> false
        }
    }

    /**
     * 全量重建（分批 EDT 写入 + generation 守卫）。
     *
     * T-Perf-03 追加链 generation 守卫（自 dev 移植）：新 refreshList 启动后旧链在新 chunk 写入前检测代际
     * 并终止——杜绝旧快照剩余条目继续追加造成的幽灵/重复条目。
     */
    private fun applyFullRebuild(sorted: List<LogSqlEntry>, gen: Long) {
        lastRendered = sorted
        // T-LogListFreeze-01：批量抑制布局 + T-LogListFreeze-03：分批 EDT 写入
        list.ignoreRepaint = true
        listModel.clear()
        if (sorted.isNotEmpty()) {
            // 首屏批次（立即写入，保证快速首屏）
            val firstBatch = sorted.take(REFRESH_FIRST_BATCH_SIZE)
            listModel.addAll(firstBatch)
        }
        list.ignoreRepaint = false
        list.revalidate()
        list.repaint()

        // T-LogListFreeze-03：剩余批次通过 invokeLater 异步追加（每批一次 EDT 脉冲，避免长冻结）
        if (sorted.size > REFRESH_FIRST_BATCH_SIZE) {
            val remaining = sorted.drop(REFRESH_FIRST_BATCH_SIZE)
            val iterator = remaining.iterator()
            fun appendNext() {
                if (gen != refreshGeneration) return // 新一轮刷新已启动，旧链终止（T-Perf-03 代际守卫）
                val chunk = mutableListOf<LogSqlEntry>()
                repeat(REFRESH_CHUNK_SIZE) { if (iterator.hasNext()) chunk.add(iterator.next()) }
                listModel.addAll(chunk)
                if (iterator.hasNext()) {
                    ApplicationManager.getApplication().invokeLater { appendNext() }
                }
            }
            ApplicationManager.getApplication().invokeLater { appendNext() }
        }
    }

    /** 按当前排序模式排序条目。 */
    // ---------- 排序方式（T-LogSortModes） ----------

    /** 弹出排序方式单选菜单（与 SQL类型弹窗同款交互；当前项打勾）。 */
    private fun showSortPopup() {
        val popup = JPopupMenu()
        buildSortMenuItems().forEach { popup.add(it) }
        popup.show(sortButton, 0, sortButton.height + 2)
    }

    /**
     * 构建排序菜单项（拆出供测试断言选项齐备——headless 下 JPopupMenu.show 不可靠）。
     * 单选语义：点选即生效并刷新列表与按钮文案。
     */
    private fun buildSortMenuItems(): List<JRadioButtonMenuItem> =
        sortOptions().map { option ->
            JRadioButtonMenuItem(option.label, option.mode == sortMode).apply {
                isFocusable = false
                addActionListener { applySortMode(option.mode) }
            }
        }

    /** 应用排序方式：更新模式 → 刷新列表 → 同步按钮文案。 */
    private fun applySortMode(mode: SortMode) {
        if (sortMode == mode) {
            updateSortButtonText()
            return
        }
        sortMode = mode
        refreshList()
        updateSortButtonText()
    }

    /** 排序按钮文案 = 当前方式标签。 */
    private fun updateSortButtonText() {
        sortButton.text = sortOptions().firstOrNull { it.mode == sortMode }?.label
            ?: MpSqlVisualizerBundle.message("log.sort.time.asc")
    }

    private fun applySort(entries: List<LogSqlEntry>): List<LogSqlEntry> = when (sortMode) {
        // 时间升序（默认）：与捕获顺序一致（sortedBy 稳定，等值保持入列序）
        SortMode.TIME_ASC -> entries.sortedBy { displayTimeOf(it) }
        SortMode.TIME_DESC -> entries.sortedByDescending { displayTimeOf(it) }
        // 耗时：结构化字段优先（agent 通道直采），缺失时回退日志文本解析；无耗时沉底
        SortMode.DURATION_ASC -> entries.sortedBy { durationOf(it) ?: Long.MAX_VALUE }
        SortMode.DURATION_DESC -> entries.sortedByDescending { durationOf(it) ?: Long.MIN_VALUE }
        // 行号：无行号（历史读回/round-trip）沉底
        SortMode.SEQUENCE -> entries.sortedBy { if (it.sequenceNumber > 0) it.sequenceNumber else Long.MAX_VALUE }
    }

    /** 条目耗时（毫秒）：结构化 executionTimeMs 优先，缺失时回退日志文本解析。 */
    private fun durationOf(entry: LogSqlEntry): Long? =
        entry.executionTimeMs ?: LogDurationParser.parse(entry)

    /**
     * 条目显示时间（排序口径）：与列表现行时间列一致——「日志时间」源开启且条目有原始日志时间时
     * 用之，否则用捕获时间（[formatTimestampFragment] 同规则），避免「看到的时间与排序依据不一致」。
     */
    private fun displayTimeOf(entry: LogSqlEntry): Long {
        val state = PluginSettings.getInstance().state
        return if (state.logTimestampSource == LOG_TIMESTAMP_SOURCE_LOG) {
            entry.logTimestamp ?: entry.timestamp
        } else {
            entry.timestamp
        }
    }

    // ---------- 渲染辅助（F-44-2：片段通道恢复 SQL 高亮 + 耗时） ----------
    // 注：formatTimestampFragment / formatDurationFragment / formatSourceFragment 为文件级 internal 函数（见本文件末尾），
    // 供 LogEntryListCellRenderer 与 SqlLogPanel 共用。

    /**
     * F-44-6 / T-JumpCapture：实时日志跳转的统一解析结果。
     * 跳转完全依赖 [SqlSourceInterceptor] 捕获的源码位置：
     * 有位置 → 精确 file:line（[Precise]）或文件已失效（[NotFound]）；
     * MP 分页 COUNT 标记 → [NoSource]（无用户调用点，纯提示）；
     * 无位置 → [MissingSource]（引导开启源码捕获，[SourceCaptureGuide]）。
     *
     * 原「按表名搜索 Wrapper」回退已整体移除：同表名 ≠ 同调用点，命中即误导。
     */
    internal sealed class LogJumpTarget {
        data class Precise(val virtualFile: VirtualFile, val line: Int) : LogJumpTarget()
        data object NoSource : LogJumpTarget()
        data class NotFound(val sourceFile: String) : LogJumpTarget()
        data object MissingSource : LogJumpTarget()
        /** T-M55-01：mapper XML 语句节点（mapperStatement 定位成功才有意义，失败回退 Java 不劣化）。 */
        data class MapperXml(val virtualFile: VirtualFile, val line: Int) : LogJumpTarget()
    }

    /** 统一解析跳转目标：仅依赖捕获位置，不做任何启发式定位。 */
    internal fun resolveEntryJump(entry: LogSqlEntry): LogJumpTarget {
        if (entry.sourceFile == null) {
            // T-AgentSourceJump：主位置缺失时仍可落候选（agent 侧主位置无调试信息场景）
            return resolveCandidateJump(entry) ?: LogJumpTarget.MissingSource
        }
        // F-44-7：MP 分页 COUNT 查询特殊标记 → 无用户调用点可跳转
        if (SourceMarkers.isMpPaginationCount(entry.sourceFile)) {
            return LogJumpTarget.NoSource
        }
        // M37.1：多源根容错——basePath 直拼失败后按模块 content root 匹配（SourcePathResolver）
        val vf = SourcePathResolver.resolveVirtualFile(project, entry.sourceFile)
        if (vf != null) {
            return LogJumpTarget.Precise(vf, maxOf(0, entry.sourceLine - 1))
        }
        // T-AgentSourceJump：主位置不在工程内（典型=误选第三方插件帧，如 PageHelper 的 PageInterceptor）
        // → 按栈序候选取首个可解析项；候选同出本次调用的真实栈，非启发式
        return resolveCandidateJump(entry) ?: LogJumpTarget.NotFound(entry.sourceFile)
    }

    /** 候选帧解析回退：栈序取首个能在工程内解析到文件的候选；全落空返回 null。 */
    private fun resolveCandidateJump(entry: LogSqlEntry): LogJumpTarget? {
        for ((file, line) in entry.sourceCandidates) {
            if (SourceMarkers.isMpPaginationCount(file)) continue
            val vf = SourcePathResolver.resolveVirtualFile(project, file) ?: continue
            return LogJumpTarget.Precise(vf, maxOf(0, line - 1))
        }
        return null
    }

    /**
     * T-M55-01：XML 目标解析——mapperStatement 非空且 [MapperXmlLocator] 定位成功时返回
     * [LogJumpTarget.MapperXml]；statementId 缺失 / MP 内置语句 XML 不存在 / 多模块定位失败
     * 一律返回 null（调用方回退 Java 跳转，不劣化）。定位自带 ReadAction，EDT 直调安全。
     */
    internal fun resolveEntryXmlJump(entry: LogSqlEntry): LogJumpTarget.MapperXml? {
        val statementId = entry.mapperStatement?.takeIf { it.isNotBlank() } ?: return null
        val location = MapperXmlLocator.locate(project, statementId) ?: return null
        return LogJumpTarget.MapperXml(location.virtualFile, location.line)
    }

    /** 等价右键「跳转到源码」（供 UI 测试断言精确跳转分支，避 headless 弹窗）。 */
    internal fun jumpToSourceForTest(entry: LogSqlEntry) = jumpToSource(entry)

    /**
     * F-44-6：日志 ↔ 源码关联跳转。
     * 仅依赖捕获位置精确跳转；无位置时弹框引导开启源码捕获（T-JumpCapture）。
     */
    private fun jumpToSource(entry: LogSqlEntry) {
        when (val target = resolveEntryJump(entry)) {
            is LogJumpTarget.Precise ->
                OpenFileDescriptor(project, target.virtualFile, target.line, 0).navigate(true)
            // resolveEntryJump 不产出该分支（XML 目标走 resolveEntryXmlJump/JumpToMapperXmlAction）；防御性导航
            is LogJumpTarget.MapperXml ->
                OpenFileDescriptor(project, target.virtualFile, target.line, 0).navigate(true)
            is LogJumpTarget.NoSource ->
                showJumpToast(MpSqlVisualizerBundle.message("log.panel.jumpToSource.noSource"))
            is LogJumpTarget.NotFound ->
                showJumpToast(MpSqlVisualizerBundle.message("log.panel.jumpToSource.notFound", target.sourceFile))
            is LogJumpTarget.MissingSource ->
                SourceCaptureGuide.showMissingSourceDialog(project)
        }
    }

    /**
     * T-UiNative T-UN-02：跳转无源码提示改轻通知（原模态弹窗——纯告知无需打断）。
     * 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）。
     */
    private fun showJumpToast(content: String) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("log.panel.jumpToSource"),
                content,
                NotificationType.INFORMATION,
            ).notify(project)
        }
    }

    /** 更新工具栏慢查询计数标签（O-1 Phase C）。 */
    private fun updateSlowQueryLabel() {
        val count = slowQueryCounter.slowCount
        slowQueryLabel?.let {
            if (count > 0) {
                it.text = MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", count.toString())
                it.isVisible = true
            } else {
                it.isVisible = false
            }
        }
    }

    private fun openPreview(entry: LogSqlEntry) {
        // T-M31.14：设置开启时预览展示格式化版（多行缩进），否则原样
        val state = PluginSettings.getInstance().state
        // T-HistParam F3：识别通道统一——与历史 tab 共用 toPreviewResult
        // （占位符识别 / 字面量参数恢复 F4 / 回退警告分流，行为与原 openPreview 双分支逐字段一致）
        SqlPreviewService.showResult(project, entry.toPreviewResult(formatSql = state.logFormatSql, literalRecovery = state.literalParamRecovery))
    }

    private fun showContextMenu(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index < 0) return
        // T-ListPreviewCopy：命中行已在多选内 → 保留多选（复制项=批量复制多条）；否则收敛为该行。
        // 删除/发送/跳转等其余菜单项恒作用于被右键的行
        list.selectedIndices = resolveContextMenuSelection(list.selectedIndices, index)
        val entry = list.model.getElementAt(index)
        val selectedEntries = list.selectedIndices.map { list.model.getElementAt(it) }
        // T-UiNative P1a：JPopupMenu+JMenuItem → 平台 DefaultActionGroup+AnAction（ActionPopupMenu 承载）
        val group = DefaultActionGroup()
        group.add(CopySqlAction(selectedEntries))
        // T-LogSearchRowTime：复制时间戳（所见即所复制的纯时间文本，无方括号）；
        // 显示开关关闭时无片段可复制 → 菜单项置灰（置灰逻辑在 CopyTimestampAction.update）
        val timestampClip = batchCopyTimestamps(selectedEntries)
        group.add(CopyTimestampAction(selectedEntries, timestampClip))
        group.add(DeleteSqlAction(entry))
        // T-M37 P1：发送到 SQL → Java 转换（第三 tab）——还原 SQL 写入输入区并切换选中该 tab
        group.add(SendToSqlToJavaAction(entry))
        // T-M38：发送到日志转 SQL（第二 tab）——条目渲染回日志文本并立即转换
        group.add(SendToLogToSqlAction(entry))
        // O-1：日志 ↔ 源码关联跳转（Phase A）；T-M55-01：拆「跳转到 Java」「跳转到 XML」双目标
        group.add(JumpToSourceAction(entry))
        group.add(JumpToMapperXmlAction(entry))
        // T-M55-03：方法级聚焦（sourceFile 非空时可用；解析失败 toast 提示，无静默吞没）
        group.add(FocusCallerMethodAction(entry))
        // T-M55-04：按请求聚焦（requestTag 非空时可用；页签 K 有界防高基数爆列表）
        group.add(FocusRequestAction(entry))
        val popup = ActionManager.getInstance().createActionPopupMenu("mpvs.log.list", group)
        // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成 targetComponent 属性——显式调 setter
        popup.setTargetComponent(list)
        popup.component.show(list, e.x, e.y)
    }

    // ---------- T-UiNative P1a：列表右键菜单动作（原 JMenuItem 语义平移，update 设文案/灰置，actionPerformed 平移原监听体） ----------

    /** 复制 SQL：多选批量逐行拼接（[batchCopySql]），单选复制该行。 */
    private inner class CopySqlAction(private val entries: List<LogSqlEntry>) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = copyMenuText("log.panel.copy", "log.panel.copySelected", entries.size)
        }

        override fun actionPerformed(e: AnActionEvent) {
            UiTextClipboard.copy(batchCopySql(entries))
        }
    }

    /** 复制时间戳：显示开关关闭时无片段可复制 → update 置灰。 */
    private inner class CopyTimestampAction(private val entries: List<LogSqlEntry>, private val clip: String) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = copyMenuText("log.panel.copyTimestamp", "log.panel.copyTimestampSelected", entries.size)
            e.presentation.isEnabled = clip.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (clip.isNotEmpty()) {
                UiTextClipboard.copy(clip)
            }
        }
    }

    /** 删除被右键的条目。 */
    private inner class DeleteSqlAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.delete")
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.entries.removeEntry(entry)
        }
    }

    /** 发送到 SQL → Java 转换（第三 tab）。 */
    private inner class SendToSqlToJavaAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.sendToReverse")
        }

        override fun actionPerformed(e: AnActionEvent) {
            sqlToJavaPanel.setInputSql(entry.restoredSql)
            tabs.selectedComponent = sqlToJavaPanel
        }
    }

    /** 发送到日志转 SQL（第二 tab）。 */
    private inner class SendToLogToSqlAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.sendTo")
        }

        override fun actionPerformed(e: AnActionEvent) {
            logToSqlPanel.loadFromEntry(entry)
            tabs.selectedComponent = logToSqlPanel
        }
    }

    /** 跳转到 Java 调用方（T-M55-01：与「跳转到 XML」拆分为双目标；无位置→引导捕获弹窗，无提示/文件丢失→toast）。 */
    private inner class JumpToSourceAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.jumpToJava")
        }

        override fun actionPerformed(e: AnActionEvent) {
            jumpToSource(entry)
        }
    }

    /**
     * T-M55-01：跳转到 mapper XML 语句节点。mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用，actionPerformed 复用，单次右键只做一次索引查询）；
     * 定位失败（XML 不存在/多模块未命中）不显示，原有「跳转到 Java」承担回退，不劣化。
     */
    private inner class JumpToMapperXmlAction(private val entry: LogSqlEntry) : AnAction() {
        private var resolved: LogJumpTarget.MapperXml? = null

        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.jumpToXml")
            resolved = resolveEntryXmlJump(entry)
            e.presentation.isEnabledAndVisible = resolved != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            // update 已解析成功才可见可点；防御再解析一次（menu 会话内基本命中缓存外的同结果）
            val target = resolved ?: resolveEntryXmlJump(entry) ?: return
            OpenFileDescriptor(project, target.virtualFile, target.line, 0).navigate(true)
        }
    }

    /**
     * T-M55-03：聚焦调用方方法（静态行号区间近似版）——sourceFile 非空时可用；
     * 点击时解析方法行号区间并加入聚焦页签栏（解析失败 toast，不静默）。
     * Ultra 的编辑器行槽 ⚡ 入口首批不做（记录为可选增强）。
     */
    private inner class FocusCallerMethodAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.focusCallerMethod")
            // MP 分页 COUNT 标记无用户调用点；其余 sourceFile 非空即可用（解析成败在 action 时点判定）
            e.presentation.isEnabled = entry.sourceFile != null &&
                !SourceMarkers.isMpPaginationCount(entry.sourceFile)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val tab = resolveCallerMethodFocus(entry)
            if (tab == null) {
                showJumpToast(MpSqlVisualizerBundle.message("log.panel.focusCallerMethod.failed"))
                return
            }
            addFocusTab(tab)
        }
    }

    /**
     * T-M55-04：按请求聚焦——requestTag 非空的条目右键即产生请求页签（同一次请求的多条 SQL
     * 共享同值，页签内呈现扁平时间序）。页签 K 有界（[addRequestTab] 超限挤掉最早请求页签，
     * 防长压测会话 requestTag 高基数爆页签栏）；无请求标签条目在请求聚焦下隐藏 + 计数提示
     * （与 03 方法聚焦同策略）。「最近 K 个请求标签下拉」的独立入口不另做——右键任意
     * 同标签条目等价可达，计划内「或」二选一取轻。
     */
    private inner class FocusRequestAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.focusRequest")
            e.presentation.isEnabled = !entry.requestTag.isNullOrBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            entry.requestTag?.takeIf { it.isNotBlank() }?.let { addFocusTab(FocusTab.Request(it)) }
        }
    }

    /** 面板销毁：释放单击预览弹窗与日志转 SQL / SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    override fun dispose() {
        // T-Perf-24：停止搜索防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop()
        // T-Perf-15：注销 manager 监听器（防面板销毁后仍被回调——每条新 SQL invokeLater 调已销毁面板）
        manager.removeUiListener(uiRefreshListener)
        manager.removeCapturingListener(capturingUiListener)
        clickPreview.dispose()
        logToSqlPanel.dispose()
        sqlToJavaPanel.dispose()
    }

    // ---------- WrapLayout（工具栏行自动换行） ----------

    /**
     * WrapLayout：FlowLayout 的换行变体——空间不足时组件自动折到下一行，且 preferred/minimum
     * 尺寸按多行计算（原生 FlowLayout.preferredLayoutSize 只按单行算，折行后第二行会被裁掉，
     * 表现为「控件消失」——2026-08-27 用户实测 SQL类型按钮选中两个类型后触发的缺陷）。
     */
    internal class WrapLayout : FlowLayout(LEFT, 4, 2) {

        override fun preferredLayoutSize(target: Container): Dimension {
            return computeSize(target, preferred = true)
        }

        override fun minimumLayoutSize(target: Container): Dimension {
            return computeSize(target, preferred = false)
        }

        private fun computeSize(target: Container, preferred: Boolean): Dimension {
            synchronized(target.treeLock) {
                val insets = target.getInsets()
                val availWidth = (target.parent?.width ?: Int.MAX_VALUE) - insets.left - insets.right
                var x = 0; var y = insets.top + vgap; var rowHeight = 0; var maxRowWidth = 0
                for (i in 0 until target.componentCount) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue
                    val d = if (preferred) m.preferredSize else m.minimumSize
                    if (x + d.width > availWidth && x > 0) {
                        maxRowWidth = maxOf(maxRowWidth, x - hgap)
                        x = 0; y += rowHeight + vgap; rowHeight = 0
                    }
                    x += d.width + hgap; rowHeight = maxOf(rowHeight, d.height)
                }
                maxRowWidth = maxOf(maxRowWidth, x - hgap)
                return Dimension(maxRowWidth + insets.left + insets.right, y + rowHeight + insets.bottom)
            }
        }

        override fun layoutContainer(target: Container) {
            synchronized(target.treeLock) {
                val insets: Insets = target.getInsets()
                val availWidth: Int = target.width - insets.left - insets.right
                var x: Int = insets.left; var y: Int = insets.top; var rowHeight = 0
                for (i in 0 until target.componentCount) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue
                    val d: Dimension = m.preferredSize
                    val rightEdge: Int = x + d.width
                    val maxRight: Int = insets.left + availWidth
                    if (rightEdge > maxRight && x > insets.left) {
                        x = insets.left; y += rowHeight + vgap; rowHeight = 0
                    }
                    m.setBounds(x, y, d.width, d.height)
                    x += d.width + hgap; rowHeight = maxOf(rowHeight, d.height)
                }
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogPanel::class.java)
        private const val TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar"
        /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"
        /** T-Perf-24（自 dev 移植）：搜索输入防抖窗口（毫秒）。 */
        private const val SEARCH_DEBOUNCE_MS = 200
        internal const val DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
        /** T-M39：帮助文档 URL（降级系统浏览器打开）。
         *  2026-08-31 改为自建站点 jvtools.cn（可自主接入国内 CDN；
         *  当前实测：直连不通，需走系统/IDE 代理才可达，与 plugins.jetbrains.com 同）。 */
        private const val HELP_DOCUMENTATION_URL =
            "https://mybatis-plus-sql-visualizer.jvtools.cn/features/wrapper-preview.html"
        /** T-M39：帮助标签页标题。 */
        private const val HELP_TAB_TITLE = "SQL Visualizer - Help"

        /** 类型 → 文案。 */
        private fun labelForType(type: StatementType): String = when (type) {
            StatementType.SELECT -> MpSqlVisualizerBundle.message("log.filter.select")
            StatementType.INSERT -> MpSqlVisualizerBundle.message("log.filter.insert")
            StatementType.UPDATE -> MpSqlVisualizerBundle.message("log.filter.update")
            StatementType.DELETE -> MpSqlVisualizerBundle.message("log.filter.delete")
            else -> "OTHER"
        }
    }
}

// =================== 文件级：列表渲染辅助（internal 渲染器与 SqlLogPanel 共用） ===================

/** 元信息灰（时间戳/来源/徽章；与原 HTML 通道 #9E9E9E 一致）。T-UN-13（T-UiNative）：改 namedColor，亮值不变。 */
private val LIST_META_GRAY = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)

/**
 * T-CrudColor：按标记形态应用「色条 / 整行淡背景」——**两个列表渲染器共用**（§1.2.1）。
 *
 * 三分支：
 *  - 关闭 / OTHER（[tint] = null）→ 与改造前逐字节一致；
 *  - [SqlListTypeColor.STYLE_BAR] → 色条着色，单元格背景走平台选中填充（色条不受影响，选中不丢色）；
 *  - [SqlListTypeColor.STYLE_BACKGROUND] → **未选中**行底色与列表背景淡混色（类型标记）；
 *    选中行交给平台选中填充（T-UN-09 描边退役——自研选中视觉整体下线，不再干预 Border）。
 */
internal fun applyCrudColorMark(
    cell: JPanel,
    colorBar: JPanel,
    normalBorder: Border,
    tint: Color?,
    isSelected: Boolean,
    list: javax.swing.JList<*>?,
) {
    val listBg = list?.background ?: cell.background
    val selectionBg = list?.selectionBackground ?: listBg
    when (SqlListTypeColor.styleOf()) {
        SqlListTypeColor.STYLE_BAR -> {
            if (tint == null) {
                setOpaqueIfChanged(colorBar, false)
            } else {
                setBackgroundIfChanged(colorBar, tint)
                setOpaqueIfChanged(colorBar, true)
            }
            setBorderIfChanged(cell, normalBorder)
            setBackgroundIfChanged(cell, if (isSelected) selectionBg else listBg)
        }
        SqlListTypeColor.STYLE_BACKGROUND -> {
            setOpaqueIfChanged(colorBar, false)
            setBorderIfChanged(cell, normalBorder)
            setBackgroundIfChanged(
                cell,
                when {
                    isSelected -> selectionBg
                    tint == null -> listBg
                    else -> SqlListTypeColor.blend(listBg, tint, SqlListTypeColor.MIX_NORMAL)
                },
            )
        }
        else -> { // STYLE_OFF
            setOpaqueIfChanged(colorBar, false)
            setBorderIfChanged(cell, normalBorder)
            setBackgroundIfChanged(cell, if (isSelected) selectionBg else listBg)
        }
    }
}

/**
 * 仅在变化时设置边框（T-CrudColor 性能守卫）。
 *
 * 实测（JBR 21）：`JComponent.setBorder(新实例)` **即使值与旧值相同也会触发 `repaint() +1`**，
 * 而传入**同一实例**则 revalidate/repaint 均为 0。渲染器逐格调用，若每格无脑赋值就会把
 * 渲染热路径变成「每格一次重绘调度」——正是 T-LogListFreeze 那类副作用累积问题的成因。
 * 用**同一性**（`!==`）比较：本函数只会在预建的两个 Border 实例间切换，身份比较即可，
 * 且避免 `CompoundBorder.equals` 的深度比较开销。
 */
private fun setBorderIfChanged(cell: JPanel, border: Border) {
    if (cell.border !== border) cell.border = border
}

/**
 * 仅在变化时设置背景（T-CrudColor 性能守卫）。
 *
 * 实测（JBR 21）：`setBackground(新 Color 实例)` 即使 RGB 相同也会触发 `repaint() +1`；
 * 传入同一实例则为 0。配合 [SqlListTypeColor.colorOf] 的实例缓存，同色格不再产生任何 repaint。
 */
private fun setBackgroundIfChanged(target: JPanel, color: Color) {
    if (target.background != color) target.background = color
}

/** 仅在变化时设置不透明（`setOpaque` 同值本身零副作用，此处为语义显式化）。 */
private fun setOpaqueIfChanged(target: JPanel, opaque: Boolean) {
    if (target.isOpaque != opaque) target.isOpaque = opaque
}


// ----------
// T-PerfSweep3 #3：SimpleDateFormat 复用——[formatTimestampFragment] 是「每可见单元格渲染 +
// 每条目搜索匹配/批量复制」的双热路径（SqlLogPanel 与 HistorySqlLogPanel 两面板共用本单点），
// 此前每次调用新建 SimpleDateFormat。SimpleDateFormat 非线程安全，照 SqlLogAutoSaver 的
// T-Perf-12 先例做 ThreadLocal + 格式串键缓存：键=格式串天然跟随设置变更（换格式=新条目，
// 无需失效钩子）；消费形态是 EDT 渲染/搜索与右键菜单，ThreadLocal 在覆盖多线程的同时免同步。
// format(Date) 每次完整重置内部 calendar，复用同一实例输出与逐次新建逐字节一致；
// 缓存规模上界=用户可配置的格式串种类数（个位数量级），非法格式串构造即抛不进缓存、
// 每次重试构造（与逐次新建同语义，仍由调用处 catch 回落默认格式）。
// ----------
private val timestampFormatterCache =
    ThreadLocal.withInitial<HashMap<String, SimpleDateFormat>> { HashMap() }

private fun timestampFormatter(formatStr: String): SimpleDateFormat =
    timestampFormatterCache.get().getOrPut(formatStr) { SimpleDateFormat(formatStr) }

/** 时间戳片段：未开启时间戳返回 null。片段文本带尾随空格做段间距（原 HTML `<span>` 尾空格语义）。 */
internal fun formatTimestampFragment(entry: LogSqlEntry): Pair<String, SimpleTextAttributes>? {
    val state = PluginSettings.getInstance().state
    if (!state.logTimestampEnabled) return null
    val ts = when (state.logTimestampSource) {
        LOG_TIMESTAMP_SOURCE_LOG -> entry.logTimestamp ?: entry.timestamp
        else -> entry.timestamp
    }
    val formatted = try {
        timestampFormatter(state.logTimestampFormat).format(Date(ts))
    } catch (_: IllegalArgumentException) {
        timestampFormatter(SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT).format(Date(ts))
    }
    return "[$formatted] " to LIST_META_GRAY
}

/** 耗时片段：颜色分档与原 HTML 通道一致（选中白/无耗时灰/慢红/偏慢橙/正常绿）；无耗时显示 "—" 占位。 */
internal fun formatDurationFragment(ms: Long?, isSelected: Boolean): Pair<String, SimpleTextAttributes> {
    val threshold = PluginSettings.getInstance().state.slowQueryThresholdMs
    val color = when {
        // T-UN-13（T-UiNative）：五档写死色 → namedColor（亮值不变）
        isSelected -> MpvsUiColors.DURATION_SELECTED
        ms == null -> MpvsUiColors.DURATION_NONE   // 灰：无耗时
        ms >= threshold -> MpvsUiColors.DURATION_SLOW // 红：慢查询
        ms >= threshold / 2 -> MpvsUiColors.DURATION_WARN // 橙：偏慢
        else -> MpvsUiColors.DURATION_OK           // 绿：正常
    }
    val text = ms?.let { "${it}ms" } ?: "—"
    return "$text " to SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
}

/** M56 T-M56-02：增强捕获角标已按用户要求移除（增强为默认通道，逐行标来源属噪音）。 */

/** T-AgentDialectLoop：目标库角标（agent 通道由连接 URL 推断）；未知返回 null 不显示。 */
internal fun formatDatabaseFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>? {
    val db = entry.databaseName ?: return null
    val color = if (isSelected) Color(0xFFFFFF) else Color(0x00695C)
    return "[$db] " to SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
}

/** M56 T-M56-05：影响行数片段（DML 语义；null 未知不显示）。 */
internal fun formatUpdateCountFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>? {
    val u = entry.updateCount ?: return null
    // 负值 = 无有效计数（JDBC getUpdateCount 的 -1 语义；旧版 agent 会误报 -1）→ 不显示
    if (u < 0) return null
    val color = if (isSelected) Color(0xFFFFFF) else Color(0x607D8B)
    return "(${u} rows) " to SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
}

/** M56 T-M56-05：报错片段（errorText 同列展示；红字截断首段，悬停完整文本在预览窗）。 */
internal fun formatErrorFragment(entry: LogSqlEntry): Pair<String, SimpleTextAttributes>? {
    val err = entry.errorText ?: return null
    val oneLine = err.replace(Regex("\\s+"), " ")
    val text = if (oneLine.length > 80) oneLine.substring(0, 80) + "…" else oneLine
    return "⛔ $text " to SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0xD32F2F))
}

/** 来源片段：未知来源返回 null；选中态走组件前景（selectionForeground），非选中灰。 */
internal fun formatSourceFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>? {
    val source = when {
        SourceMarkers.isMpPaginationCount(entry.sourceFile) -> "MP 分页计数"
        entry.sourceFile != null -> {
            // F-44-7：标记路径始终使用正斜杠（与 CallerSourceLocator 一致），不能用 File.separatorChar
            val name = entry.sourceFile.substringAfterLast('/')
            if (entry.sourceLine > 0) "$name:${entry.sourceLine}" else name
        }
        else -> return null
    }
    return "[$source] " to if (isSelected) SimpleTextAttributes.REGULAR_ATTRIBUTES else LIST_META_GRAY
}

/**
 * 日志列表条目单元格渲染器（F-44-2 重写；T-LogListFreeze-04 弃 HTML 通道）：
 * 双 [SimpleColoredComponent] 两行结构（T-TwoLine 语义保留：元信息行在上、SQL 高亮行在下）。
 * 原 JLabel+HTML 通道每次 setText 都触发 BasicHTML 解析排版，叠加可变行高逐条测量——
 * 16s EDT Freeze 报告主因；片段通道渲染只剩轻量文本测量，配合固定行高布局走 O(1) 快路径。
 * 文本原样着色（无 HTML 转义面，注入场景不复存在）；摘要构造时已在首行截断（[LogSqlEntry.summary]），无换行处理面。
 * 抽为具名 internal 类，便于单元测试直接渲染各种条目状态、锁定配色语义。
 */
internal class LogEntryListCellRenderer : JPanel(BorderLayout()), javax.swing.ListCellRenderer<Any?> {
    /** 去重丢弃计数快照（徽章渲染数据源；由 [SqlLogPanel.refreshList] 更新）。 */
    var dedupeCounts: Map<String, Int> = emptyMap()

    /** 元信息行（internal 供 UI 测试遍历片段断言文本与着色）。 */
    internal val metaLine = SimpleColoredComponent()

    /** SQL 行组件池（T-ListMode：单行模式恒 1 行；多行模式按变换后行数伸缩）。 */
    private val sqlLinePool = mutableListOf(SimpleColoredComponent())

    /** SQL 首行（SINGLE_LINE 恒为唯一 SQL 行；测试断言兼容 T-LogListFreeze-04 单行语义）。 */
    internal val sqlLine: SimpleColoredComponent get() = sqlLinePool[0]

    /** 全部 SQL 行（internal 供 UI 测试按行断言多行模式渲染）。 */
    internal val sqlLines: List<SimpleColoredComponent> get() = sqlLinePool

    private val linesPanel = JPanel(GridLayout(0, 1)).apply { isOpaque = false }

    /**
     * T-CrudColor：左侧类型色条（[SqlListTypeColor.STYLE_BAR] 下着色可见；
     * 其他样式 `isOpaque=false` 但仍**占位**——占位是刻意的：切换样式时文本左缘不位移，
     * 且左内边距 5 + 色条 3 = 原 8px，文本左缘像素位置与改造前一致。
     */
    internal val colorBar = JPanel().apply {
        preferredSize = Dimension(SqlListTypeColor.BAR_WIDTH, 1)
        isOpaque = false
    }

    /** 未选中态 Border（分隔线 + 内边距；T-UN-09 描边退役后选中态同样只用它，选中视觉归平台绘制）。 */
    internal val normalBorder: Border = BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
        EmptyBorder(5, 5, 5, 8), // 左 5 + 色条 3 = 原 8
    )

    init {
        layout = BorderLayout()
        isOpaque = true
        metaLine.isOpaque = false
        sqlLinePool[0].isOpaque = false
        linesPanel.add(metaLine)
        linesPanel.add(sqlLinePool[0])
        // T-CrudColor：色条挂 WEST——CENTER 已被组件池面板占用，WEST 空闲且零结构冲突
        add(colorBar, BorderLayout.WEST)
        add(linesPanel, BorderLayout.CENTER)
        border = normalBorder
    }

    /** 元信息行渲染文本（测试断言用：全部片段拼接、trim 段间距）。 */
    val metaTextForTest: String
        get() = buildString {
            val it = metaLine.iterator()
            while (it.hasNext()) append(it.next())
        }.trim()

    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): java.awt.Component {
        val entry = value as? LogSqlEntry

        // T-CrudColor：标记形态分派（读设置 + 纯函数；热路径零分配）
        val tint = entry?.let { SqlListTypeColor.colorOf(it.statementType) }
        // T-UN-09：选中描边退役后，三种样式的选中行统一走平台选中填充 → 前景按选中态取
        // selectionForeground（耗时着色随 formatDurationFragment 的选中白语义）
        val useSelectionFill = isSelected
        applyCrudColorMark(this, colorBar, normalBorder, tint, isSelected, list)

        val fg = if (useSelectionFill) (list?.selectionForeground ?: JBColor.foreground())
        else (list?.foreground ?: JBColor.foreground())
        metaLine.foreground = fg
        metaLine.clear()
        val lineFragments = if (entry != null) {
            // T-LogRowNumber：行号片段在最前（最高频引用锚点；0 = 无行号——历史文件/round-trip 条目）
            if (entry.sequenceNumber > 0) {
                metaLine.append("#${entry.sequenceNumber} ", LIST_META_GRAY)
            }
            // T-TwoLine：元信息行（时间戳/耗时/来源/去重徽章）在上，段间距由尾随空格承担
            formatTimestampFragment(entry)?.let { (t, a) -> metaLine.append(t, a) }
            if (PluginSettings.getInstance().state.showDurationInLogList) {
                val (t, a) = formatDurationFragment(entry.executionTimeMs, useSelectionFill)
                metaLine.append(t, a)
            }
            // T-AgentDialectLoop：目标库角标（耗时后、来源前——与「这条 SQL 跑在哪」的阅读顺序一致）
            formatDatabaseFragment(entry, useSelectionFill)?.let { (t, a) -> metaLine.append(t, a) }
            formatSourceFragment(entry, useSelectionFill)?.let { (t, a) -> metaLine.append(t, a) }
            // 注：原「[⚡增强]」来源角标已按用户要求移除（增强为默认捕获通道，逐行标来源属噪音）
            formatUpdateCountFragment(entry, isSelected)?.let { (t, a) -> metaLine.append(t, a) }
            formatErrorFragment(entry)?.let { (t, a) -> metaLine.append(t, a) }
            dedupeCounts[entry.dedupeKey]?.takeIf { it > 0 }?.let { metaLine.append("⨉$it ", LIST_META_GRAY) }
            sqlLineFragmentsFor(entry)
        } else {
            null
        }
        // SQL 高亮行在下（T-ListMode：行数随显示模式伸缩）；选中态保留 SQL 高亮原色
        ensureSqlLineCount(if (lineFragments != null) lineFragments.size else 1)
        // T-UiNative speed search 高亮：对元信息行与各 SQL 行施加匹配片段高亮（平台 applySpeedSearchHighlighting
        // 依 supply.matchingFragments 精确命中区间；fragments 文本已在上面 append，此处在片段级叠加 STYLE_SEARCH_MATCH）
        list?.let { l -> SpeedSearchUtil.applySpeedSearchHighlighting(l, metaLine, false, useSelectionFill) }
        sqlLinePool.forEachIndexed { i, comp ->
            comp.foreground = fg
            comp.clear()
            lineFragments?.getOrNull(i)?.forEach { f -> comp.append(f.text, f.attributes) }
            list?.let { l -> SpeedSearchUtil.applySpeedSearchHighlighting(l, comp, false, useSelectionFill) }
        }
        // T-ClickPreview：不设 toolTipText（悬停逐行弹气泡 = 条目多时卡顿根因），预览改单击触发
        return this
    }

    /** SQL 行组件池伸缩到 n 行（首位恒保留——[sqlLine] 测试缝；收缩移除末尾）。 */
    private fun ensureSqlLineCount(n: Int) {
        while (sqlLinePool.size < n) {
            val comp = SimpleColoredComponent()
            comp.isOpaque = false
            sqlLinePool += comp
            linesPanel.add(comp)
        }
        while (sqlLinePool.size > maxOf(1, n)) {
            linesPanel.remove(sqlLinePool.removeAt(sqlLinePool.size - 1))
        }
    }

    /**
     * SQL 行片段（T-ListMode 模式分流）：SINGLE_LINE 直渲染不缓存（固定行高 + 主题切换实时生效）；
     * 多行模式走条目级缓存（键含模式自动失效）——可变行高布局逐条测量免重复分词。
     */
    private fun sqlLineFragmentsFor(entry: LogSqlEntry): List<List<SqlTextFragment>> {
        val sql = withTerminator(entry.summary)
        val mode = PluginSettings.getInstance().sqlListDisplayMode()
        if (mode == SqlListDisplayMode.SINGLE_LINE) {
            return listOf(SqlListFragmentRenderer.renderFragments(sql))
        }
        entry.cachedListLineFragments
            ?.takeIf { entry.cachedListLineFragmentsMode == mode }
            ?.let { return it }
        return SqlListFragmentRenderer.renderLineFragments(sql, mode).also {
            entry.cachedListLineFragments = it
            entry.cachedListLineFragmentsMode = mode
        }
    }
}
