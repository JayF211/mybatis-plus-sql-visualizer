package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettings.Companion.LOG_TIMESTAMP_SOURCE_LOG
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
// T-UiCoord F2：下拉按钮文本截尾省略复用 meta 行的同款纯函数（避免第二份省略实现）
import cn.song.mybatisplus.visualizer.ui.clipTailToWidth
import com.intellij.util.ui.JBUI
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
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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
 * 空态文案写入（T-AgentDiagWrap T-04，独立成顶层函数以便单测）。
 *
 * 为什么不能 `status.text = plain`：`JBList.emptyText` 是 `com.intellij.util.ui.StatusText`
 * （**不是** JLabel），内容落在 `SimpleColoredComponent` 上——它不解析 HTML，
 * 也不把 `\n` 当换行（`\n` 被当普通字符画成零宽，两段文字被无声拼接；超宽只裁不折）。
 * 平台侧唯一的多行手段是 [com.intellij.util.ui.StatusText.appendLine]：每行一个 Fragment，
 * `paintColumnInBounds` 逐 Fragment 单独绘制（**仍不折行**，所以宽度折行由
 * [wrapEmptyTextLines] 先算好）。
 *
 * @param widthPx 列表当前可用像素宽度
 * @param measure 单串像素宽度测量
 */
internal fun applyEmptyTextLines(
    status: com.intellij.util.ui.StatusText,
    text: String,
    widthPx: Int,
    measure: (String) -> Int,
) {
    status.clear()
    wrapEmptyTextLines(text, widthPx, measure).forEach { status.appendLine(it) }
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
 * 屏蔽规则存量过滤（T-SqlSuppress O-69，纯逻辑，可独立单测）：命中屏蔽指纹集的条目
 * 从列表隐藏（缓冲里仍在——删除规则后复现；闸门已丢弃的历史条目不可恢复，UI 文案明示）。
 * [suppressKeys] 与闸门同一数据源（[SqlSuppressRuleManager.suppressedKeys] 经 [SqlLogManager]
 * 同步进缓冲），空集透传（无规则常态零成本）。
 */
internal fun filterBySuppress(entries: List<LogSqlEntry>, suppressKeys: Set<String>): List<LogSqlEntry> {
    if (suppressKeys.isEmpty()) return entries
    // T-SqlSuppressUx 二段：精确键或结构键（手动开启结构匹配的规则）任一命中即滤除
    return entries.filter { it.suppressKey !in suppressKeys && it.structureKey !in suppressKeys }
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

    /** O-90：同模板计数快照（refreshList 后更新；cellRenderer 徽章与右键聚焦动作共用）。 */
    private var templateCounts: Map<String, Int> = emptyMap()

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

    // ---------- UI 组件（工具栏单行：动作图标组靠左 + 列表控制组靠右） ----------

    /**
     * SQL类型多选按钮（点击弹可勾选弹窗；文案只显示选中状态：全部 / 无 / 「查询, 更新」组合）。
     *
     * T-UiCoord F2（2026-09-21 用户定案「工具栏不要两行」）：原「SQL类型：」标签控件退役，
     * 标签键转作 tooltip 前缀（tooltip = `SQL类型：` + 当前状态**全文**）；按钮显示文本按
     * [DROPDOWN_TEXT_BUDGET_PX] 截尾省略——文本定宽封顶是多选后行宽不漂移、单行恒成立的前提。
     */
    private val typeFilterButton = JButton().apply {
        isFocusable = false
        icon = AllIcons.General.ArrowDown
        horizontalTextPosition = SwingConstants.LEFT // 图标在文字右侧（下拉按钮惯例）
        addActionListener { showTypeFilterPopup() }
    }

    /**
     * 排序方式按钮（T-LogSortModes）：点击弹单选菜单，文案显示当前方式；与「SQL类型」按钮同款式
     * （原生 LaF 扁平按钮 + 右侧下箭头），默认时间升序。取代原「耗时排序」双态切换按钮——
     * 排序恒有确定方式，不再依赖「是否检测到耗时」决定显隐。
     *
     * T-UiCoord F2：原「排序：」标签控件同样退役（标签键转 tooltip 前缀），文本同样截尾封顶。
     */
    private val sortButton = JButton().apply {
        isFocusable = false
        icon = AllIcons.General.ArrowDown
        horizontalTextPosition = SwingConstants.LEFT // 图标在文字右侧（下拉按钮惯例，同 SQL类型）
        addActionListener { showSortPopup() }
    }

    /**
     * T-SqlLogPerf（20261006-1020 计划 + 用户点名）：「滚动到底部」动作 + 尾部跟随。
     *
     * 背景：log 包原没有任何滚动控制——并发灌入时新条目只进模型、视口原地不动，用户体感
     * 「监听加载有延迟」。本动作一键跳列表底并恢复跟随；跟随态下新条目到达自动贴底，
     * 用户向上滚动阅读即自动停跟随（滚回底部/点动作恢复），与 IDE 控制台「滚动到底」习惯一致。
     * 形态走左侧动作图标组（AnAction）而非右侧控制组 JButton——控制组最坏宽有预算锁
     * （SqlLogPanelLayoutTest 防「又往控制组加控件」），图标组窄了由平台溢出承接。
     */
    internal val scrollToEndAction: AnAction = ScrollToEndAction()

    /** 尾部跟随开关（EDT 读写：滚动监听 / 刷新尾部 / 动作点击）。默认开——日志语义以「最新」为锚点。 */
    private var autoFollowTail = true

    /** 跟随判定松量（px）：视口底沿距列表底沿小于该值视为「在底部」。 */
    private val tailFollowSlackPx = 48

    /** 滚动事件归因基线（区分程序性追加与用户滚动；见 buildListPane 的 adjustment 监听）。 */
    private var lastScrollMax = 0
    private var lastScrollValue = 0

    /** 跳列表底；follow=true 同时恢复跟随态。 */
    private fun scrollToBottom(follow: Boolean) {
        if (follow) autoFollowTail = true
        if (listModel.isEmpty) return
        list.ensureIndexIsVisible(listModel.size() - 1)
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

    /**
     * T-UiCoord F2：工具栏单行宿主（布局契约断言用——单行 / 不越界 / 控件共轴）。
     * 返回的是生产实例（`buildToolbar()` 的产物），不是测试另搭的仿制品。
     */
    internal val toolbarRowForTest: JPanel? get() = toolbarRow

    /** T-UiCoord F2：工具栏右侧控制组（类型 / 排序 / 去重勾选 / 慢查询徽章）——单行契约的主断言面。 */
    internal val toolbarControlsForTest: JPanel? get() = toolbarControls

    /** T-UiCoord F2：工具栏动作图标组组件（结构断言：它必须挂在行宿主 CENTER）。 */
    internal val toolbarComponentForTest: JComponent? get() = toolbarComponent

    /** T-UiCoord F2：下拉按钮显示文本的像素预算（测试按同一预算算期望的省略文本）。 */
    internal val dropdownTextBudgetForTest: Int get() = JBUI.scale(DROPDOWN_TEXT_BUDGET_PX)

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
     * 捕捉开关动作（T-CaptureBack）：回迁面板工具栏首位（T-UN-11 曾迁工具窗标题栏，
     * 新 UI 下标题栏动作悬停才可见致入口不可发现，回归面板）。图标/文案在 [CaptureAction.update]
     * 按 manager.capturing 态输出；测试断言直接读 presentation，不依赖按钮刷新。
     */
    val captureToggleAction: AnAction = CaptureAction()

    /**
     * 工具栏动作组（T-M39：internal 供测试断言帮助入口存在性；
     * update 由工具栏生命周期驱动，headless 测试手动触发；须在 init 前声明）。
     * T-CaptureBack：捕捉开关回迁首位（捕捉/清空/设置/屏蔽管理/搜索/帮助；搜索在帮助左侧为用户指定排序）。
     * T-SqlSuppress：屏蔽管理入口插设置旁（D1 主入口；D7 图标 AllIcons.General.Filter 漏斗）。
     */
    internal val toolbarGroup = DefaultActionGroup(
        captureToggleAction, // 捕捉开关（回迁面板工具栏首位，T-CaptureBack）
        ClearAction(),
        SettingsAction(),
        ManageSuppressAction(), // T-SqlSuppress：屏蔽管理（设置旁，D1 主入口 + D7 漏斗图标）
        ShowSearchAction(), // 搜索在帮助左侧（用户指定排序）
        HelpAction(),
        scrollToEndAction, // T-SqlLogPerf：滚动到底部（尾部跟随；追加在用户指定排序之后）
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
            // T-CaptureTruth T-CT-03：捕捉态变化 → 诊断行立即跟上（起停捕捉是用户最先看的时刻）
            syncDiagnostics()
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

    // ---------- T-UiCoord F2：工具栏单行宿主字段（同样须在 init 之前——init 里 buildToolbar 会赋值；
    // 声明晚于 init 会让赋值被随后的 `= null` 初始化器覆盖回 null） ----------

    /** 工具栏单行宿主（`buildToolbar()` 产物）。 */
    private var toolbarRow: JPanel? = null

    /** 工具栏右侧控制组（EAST 宿主内那层 FlowLayout 面板）。 */
    private var toolbarControls: JPanel? = null

    /** 工具栏动作图标组组件（CENTER）——结构断言用（曾因还原反向验证探针时误删 CENTER 挂载而漏）。 */
    private var toolbarComponent: JComponent? = null

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
        // R19（T-PerfSweep 批次 2）：历史 tab 常驻——原 F-44 条件挂载（自动保存开或有历史文件）
        // 让新用户结构性不知道历史功能存在（logAutoSaveEnabled 默认 false）；文件列表空态已带
        // 「开启自动保存」引导文案，tab 常驻即引导闭环。F-44 的移除分支随 R19 废除。
        addHistoryTab()
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
            is FocusTab.Template -> addTemplateTab(focusTabs, tab.structureKey, tab.count)
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
        updateSortButtonText()
        // T-UiCoord F2（2026-09-21 用户定案「不要两行」）：工具栏**恒为单行**——
        // 左侧动作图标组（CENTER，拿剩余宽度；窄了交给平台图标溢出）+ 右侧列表控制组
        // （EAST：慢查询徽章 / SQL类型 / 排序 / 去重勾选，勾选仍在最右）。
        // BorderLayout 结构上不存在折行可能，故任意宽度下都只有一行；原 WrapLayout 流式行退役
        // ——它按「谁最后谁折」处理放不下，2026-09-16 加排序单元、09-18 加第 5 枚图标后，
        // 460px 侧边栏下「排序」单元孤行到第二行（右留 300px 空档、两行左缘差 10px）。
        val row = JPanel(BorderLayout())
        row.add(toolbar.component, BorderLayout.CENTER)
        toolbarComponent = toolbar.component

        // O-1：慢查询计数徽章。T-UiCoord F2：由「慢查询: N」全文改短徽章（复用存量键
        // log.panel.slowQueryBadge）让出一行余量，全文进 tooltip（见 updateSlowQueryLabel）。
        slowQueryLabel = JLabel().apply {
            // T-UN-13（T-UiNative）：写死红 → namedColor（亮值不变，主题可覆写）
            foreground = MpvsUiColors.SLOW_RED
            font = font.deriveFont(Font.BOLD)
            isVisible = false // 默认隐藏，有慢查询时显示
        }
        // 控制组：Flow(RIGHT) 单行右对齐；外包 Y 轴 BoxLayout + 上下 glue 使整组垂直居中
        // （FlowLayout 自身按顶部摆放，直接挂 EAST 会与图标组差几像素不共轴）
        val controls = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(slowQueryLabel)
            add(typeFilterButton)
            add(sortButton)
            add(dedupeCheckBox)
        }
        toolbarControls = controls
        val right = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(Box.createVerticalGlue())
            add(controls)
            add(Box.createVerticalGlue())
        }
        row.add(right, BorderLayout.EAST)
        toolbarRow = row
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

    /**
     * 更新 SQL类型按钮文案（全部 / 无 / 类型组合）。
     *
     * T-UiCoord F2：标签控件退役后语义由 tooltip 承载——显示文本按预算截尾省略（多选组合可能很长），
     * tooltip 恒为「SQL类型：」+ 完整状态。
     */
    private fun updateTypeFilterButton() {
        val state = when {
            activeFilter.containsAll(FILTERABLE_TYPES) -> MpSqlVisualizerBundle.message("log.filter.all")
            activeFilter.isEmpty() -> MpSqlVisualizerBundle.message("log.filter.type.none")
            else -> FILTERABLE_TYPES.filter { it in activeFilter }.joinToString(", ") { labelForType(it) }
        }
        typeFilterButton.text = clipToDropdownBudget(state, typeFilterButton)
        typeFilterButton.toolTipText = MpSqlVisualizerBundle.message("log.filter.type.label") + state
    }

    /**
     * T-UiCoord F2：下拉按钮显示文本按预算截尾省略（复用 meta 行的 [clipTailToWidth] 纯函数）。
     * 预算用 JBUI 缩放表达——文本宽度随缩放变化，写死像素会在高 DPI 下失效。
     * tooltip 恒全文，省略只发生在按钮显示层。
     */
    private fun clipToDropdownBudget(text: String, target: JComponent): String {
        val metrics = target.getFontMetrics(target.font)
        return clipTailToWidth(text, { metrics.stringWidth(it) }, JBUI.scale(DROPDOWN_TEXT_BUDGET_PX))
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
     * R19（T-PerfSweep 批次 2）：历史 tab 常驻后的幂等确保——tab 已挂即零操作。
     * 原 F-44 的「无自动保存且无历史数据时移除 tab」分支废除（结构性隐藏让新用户
     * 不知道历史功能存在；空态文案已带开启引导），调用点（设置变更监听）保留。
     */
    private fun updateHistoryTabVisibility() {
        if (historyTabIndex < 0) {
            addHistoryTab() // 幂等：Disposer 防重入守卫在 addHistoryTab 内
        }
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
                e.presentation.description = if (manager.isWaitingForConsole)
                    MpSqlVisualizerBundle.message("log.panel.capture.waiting.desc") else null
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

    /**
     * T-SqlLogPerf：「滚动到底部」动作（跳列表底 + 恢复尾部跟随）。
     * 图标复用 [AllIcons.General.ArrowDown]（本文件 SQL类型/排序按钮同款下箭头，向下语义一致）。
     */
    private inner class ScrollToEndAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.General.ArrowDown
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.scrollBottom")
            e.presentation.description = MpSqlVisualizerBundle.message("log.panel.scrollBottom.tip")
        }

        override fun actionPerformed(e: AnActionEvent) {
            scrollToBottom(follow = true)
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

    /**
     * T-SqlSuppress（O-69）：屏蔽管理入口（D1 定案主入口，插设置旁；D7 图标漏斗
     * `AllIcons.General.Filter`——262 jar 实渲染比对后用户选定，全栏无漏斗形不撞车）。
     * 打开与右键「管理屏蔽规则…」同一非模态对话框。
     */
    private inner class ManageSuppressAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.General.Filter
            e.presentation.text = MpSqlVisualizerBundle.message("log.suppress.manage")
        }

        override fun actionPerformed(e: AnActionEvent) {
            SqlSuppressManagerDialog(project).show()
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

    // ---------- T-AgentDiagWrap T-04：空态文案的「文本 → 显示」 ----------

    /**
     * 最近一次写入空态的纯文本（`\n` 分隔语义行）。宽度变化时按新宽度重排同一份文本，
     * 所以必须留住它——否则 resize 只能重排「上一次的视觉行」，再窄一档就还原不了。
     */
    private var lastEmptyText: String? = null

    /** 写入空态文案（纯文本入口，渲染层在此做折行）。 */
    private fun applyEmptyText(plain: String) {
        lastEmptyText = plain
        renderEmptyText()
    }

    /** 按当前列表可用宽度重排并逐视觉行写入（EDT）。 */
    private fun renderEmptyText() {
        val plain = lastEmptyText ?: return
        applyEmptyTextLines(list.emptyText, plain, emptyTextWidthPx()) { s -> list.getFontMetrics(list.font).stringWidth(s) }
    }

    /** 空态文案可用宽度：列表宽度扣掉自身内边距与左右余量；尚未布局（首帧 / 隐藏卡）时给兜底值。 */
    private fun emptyTextWidthPx(): Int {
        val insets = list.insets
        val w = list.width - insets.left - insets.right - EMPTY_TEXT_EDGE_PADDING_PX * 2
        return if (w <= 0) EMPTY_TEXT_FALLBACK_WIDTH_PX else w
    }

    // ---------- T-CaptureTruth T-CT-03：零产出诊断 ----------

    /**
     * 诊断刷新定时器（仅「捕捉中且零条目」时运行）。
     *
     * 为什么需要定时器：零产出意味着缓冲不发任何通知，而控制台行数/增强挂载态都在变——不主动拉取
     * 的话诊断行会永远停在「捕捉开始那一刻」的快照上，恰好在用户真正想知道「为什么还是没有」时失语。
     * 2s 与桥轮询同频（[SqlLogConsoleBridge] POLL_INTERVAL_MS）；命中条目或停止捕捉即停表，常态零开销。
     */
    private var diagnosticsTimer: javax.swing.Timer? = null

    /**
     * 诊断行同步（EDT）：更新列表空态文案 + 管理定时器生命周期。
     * 取数全部为只读内存态（buffer 快照 / 桥计数 / AgentManager 状态），不触发平台调用。
     */
    private fun syncDiagnostics() {
        val active = manager.capturing && manager.entries.snapshot().isEmpty()
        val text = if (active) {
            captureDiagnosticsText(
                CaptureDiagnostics(
                    capturing = true,
                    waitingForConsole = manager.isWaitingForConsole,
                    entryCount = 0,
                    consolesAttached = manager.consoleBridge.attachedConsoles.size,
                    linesSeen = manager.consoleBridge.consoleLinesSeen.get(),
                    enhancedStatus = runCatching {
                        AgentManager.getInstance(project).statusSummary()
                    }.getOrNull(),
                    // T-RA-03：两个「行去哪了」计数——守卫误丢行 / EP 短路后条目未落到本面板时，
                    // 它们是面板唯一可见的证据（此前生产侧零读取，故障被伪装成「控制台没输出」）。
                    replayDropped = manager.consoleBridge.replayDropCount,
                    epShortCircuited = manager.consoleBridge.epActiveShortCircuitCount.get(),
                    // T-DevAcceptFix2 ⑤：增强通道已收事件数（恒显示）——「事件到没到」的唯一判别量
                    agentEventsReceived = cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter.receivedEvents(),
                ),
            )
        } else {
            null
        }
        applyEmptyText(text ?: MpSqlVisualizerBundle.message("log.panel.empty"))
        if (active) {
            if (diagnosticsTimer == null) {
                diagnosticsTimer = javax.swing.Timer(DIAGNOSTICS_TICK_MS) { syncDiagnostics() }
                    .apply { isRepeats = true; start() }
            }
        } else {
            diagnosticsTimer?.stop()
            diagnosticsTimer = null
        }
    }

    // ---------- 条目列表 ----------

    private fun buildListPane(): JBScrollPane {
        applyEmptyText(MpSqlVisualizerBundle.message("log.panel.empty"))
        // T-AgentDiagWrap T-04：列表宽度变化即重排空态文案——否则用户拉窄/拉宽工具窗后，
        // 文案还按旧宽度折行（窄窗下会重新长出被裁掉的尾巴，等于修复只在首帧生效）。
        list.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                renderEmptyText()
            }
        })
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
        // T-SqlLogPerf：尾部跟随的用户意图归因——滚动条事件里「程序性追加」（max 增大且 value 未动，
        // 新条目进模型所致）不改跟随态；其余变化按「是否在底部」判定：滚离底部=停跟随，滚回底部=恢复。
        val scrollPane = JBScrollPane(list)
        scrollPane.verticalScrollBar.addAdjustmentListener { e ->
            val bar = e.adjustable
            val grew = bar.maximum > lastScrollMax
            val value = bar.value
            val valueUnmoved = value == lastScrollValue
            lastScrollMax = bar.maximum
            lastScrollValue = value
            if (grew && valueUnmoved) return@addAdjustmentListener // 新条目进模型的程序性增长，非用户滚动
            autoFollowTail = bar.maximum - (value + bar.visibleAmount) <= tailFollowSlackPx
        }
        return scrollPane
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
     * 1. [filterBySuppress]：按屏蔽规则指纹集滤存量（T-SqlSuppress O-69；管线最前——空集零成本）
     * 2. [filterByType]：按 [activeFilter] 筛选（全选 = 全部，含 OTHER）
     * 3. [filterByFocus]：按 [activeFocus] 聚焦（M56 T-M56-00 基础设施；null = 透传）
     * 4. [filterBySearch]：按 [searchText] 搜索（大小写不敏感）
     * 5. [applySort]：按 [sortMode] 排序（降序耗时）
     * 6. 差分渲染：T-Perf-03 增量刷新——流式追加免全量重建；无法增量时回退分批全量重建
     *
     * T-LogListFreeze-04：HTML 缓存随片段通道退役——徽章计数在渲染时直读 [dedupeCounts]，
     * 原「清缓存/选择性失效」机制一并移除。
     */
    private fun refreshList() {
        val gen = ++refreshGeneration
        // T-SqlLogPerf P5（20261006-1020 计划）：上一轮全量重建链未走完就来了新刷新时，增量差分会被
        // 「model.size != lastRendered.size」拒绝 → 每次刷新都重启重建链，风暴期列表永远停在首屏 30 条
        // （用户体感「监听加载有延迟」的实态）。进差分前同步补完剩余批次，让差分基线对齐。
        flushRebuildChainSync()
        val snapshot = LogPerfProbe.measure("LogPanel.refreshList.snapshot", { "size=${manager.entries.size()}" }) {
            manager.entries.snapshot()
        }
        // T-CaptureTruth T-CT-03：条目变化同步诊断行（有条目即还原默认空态文案并停表）
        syncDiagnostics()

        // 去重丢弃计数快照（徽章渲染数据源）：徽章在元信息行渲染时直读，无缓存失效链
        dedupeCounts = manager.entries.dedupeCountsSnapshot()
        entryCellRenderer.dedupeCounts = dedupeCounts

        // O-90：同模板计数快照（**全量条目**聚合——徽章计数不随当前过滤/搜索波动；
        // snapshot 本就为过滤链物化，structureKey 惰性缓存后单遍 O(N)）
        templateCounts = structureKeyCounts(snapshot)
        entryCellRenderer.templateCounts = templateCounts

        // T-SqlSuppress：屏蔽存量过滤插管线最前（与类型过滤同级；键集经 SqlLogManager 订阅同步）
        val filtered = filterByFocus(
            filterByType(
                filterBySuppress(snapshot, manager.entries.suppressKeys),
                activeFilter,
            ),
            activeFocus,
        )
        // T-M55-03/04：动态聚焦页签层（页签=过滤器，多开并集；离散增删走 FullRebuild，差分零改动）
        val focused = filterByFocusTabs(filtered, focusTabs)
        val searched = filterBySearch(focused, searchText)
        val newVisible = applySort(searched)
        val evicted = manager.entries.drainEvictedCount()

        val modelSizeBefore = listModel.size()
        if (!applyIncremental(newVisible, evicted)) {
            applyFullRebuild(newVisible, gen)
        }
        // T-SqlLogPerf：尾部跟随——仅当列表真的长高（新条目追加）才贴底；搜索/过滤收窄不打扰浏览位置
        if (autoFollowTail && listModel.size() > modelSizeBefore) {
            list.ensureIndexIsVisible(listModel.size() - 1)
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
     * T-SqlLogPerf P5：剩余批次游标提为字段 [rebuildRemaining]——refreshList 进差分前经
     * [flushRebuildChainSync] 同步补完在途链（旧实现链被新刷新抢占后只能整体重启，见 flushRebuildChainSync 注释）。
     */
    private fun applyFullRebuild(sorted: List<LogSqlEntry>, gen: Long) {
        lastRendered = sorted
        rebuildRemaining = null
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
        rebuildRemaining = if (sorted.size > REFRESH_FIRST_BATCH_SIZE) {
            sorted.drop(REFRESH_FIRST_BATCH_SIZE).iterator()
        } else {
            null
        }
        if (rebuildRemaining != null) {
            ApplicationManager.getApplication().invokeLater { appendRebuildChunk() }
        }
    }

    /** T-SqlLogPerf P5：在途全量重建链的剩余游标（null = 无在途链）。 */
    private var rebuildRemaining: Iterator<LogSqlEntry>? = null

    /** 追加一批（REFRESH_CHUNK_SIZE 条）；写完仍有余量则排下一拍。 */
    private fun appendRebuildChunk() {
        val remaining = rebuildRemaining ?: return
        val chunk = mutableListOf<LogSqlEntry>()
        repeat(REFRESH_CHUNK_SIZE) { if (remaining.hasNext()) chunk.add(remaining.next()) }
        listModel.addAll(chunk)
        rebuildRemaining = if (remaining.hasNext()) remaining else null
        if (rebuildRemaining != null) {
            ApplicationManager.getApplication().invokeLater { appendRebuildChunk() }
        }
    }

    /**
     * T-SqlLogPerf P5（20261006-1020 计划）：同步补完在途全量重建链。
     *
     * 旧形态：重建链在途时 `listModel.size != lastRendered.size`，增量差分必被拒绝 → 风暴期
     * 每 200ms 的新刷新都重启重建链，链永远走不完，列表停在首屏 30 条且反复 clear/addAll 白耗 EDT。
     * 补完后 model 与 lastRendered 对齐，后续刷新走增量差分。
     */
    private fun flushRebuildChainSync() {
        val remaining = rebuildRemaining ?: return
        rebuildRemaining = null
        list.ignoreRepaint = true
        val rest = remaining.asSequence().toList()
        if (rest.isNotEmpty()) listModel.addAll(rest)
        list.ignoreRepaint = false
        list.revalidate()
        list.repaint()
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

    /**
     * 排序按钮文案 = 当前方式标签。
     *
     * T-UiCoord F2：与 SQL类型按钮同构——显示文本截尾封顶，tooltip 恒为「排序：」+ 完整方式。
     */
    private fun updateSortButtonText() {
        val label = sortOptions().firstOrNull { it.mode == sortMode }?.label
            ?: MpSqlVisualizerBundle.message("log.sort.time.asc")
        sortButton.text = clipToDropdownBudget(label, sortButton)
        sortButton.toolTipText = MpSqlVisualizerBundle.message("log.sort.label") + label
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

    /**
     * 统一解析跳转目标：仅依赖捕获位置，不做任何启发式定位。
     * T-SourceJumpFunnel P0-5：判定逻辑收敛到 [EntryJumpResolver]（实时/历史两面板共用同一实现，
     * 历史面板由此获得候选回退能力），本函数只做类型转接。
     */
    internal fun resolveEntryJump(entry: LogSqlEntry): LogJumpTarget =
        when (val target = EntryJumpResolver.resolve(project, entry)) {
            is EntryJumpResolver.JumpTarget.Precise -> LogJumpTarget.Precise(target.virtualFile, target.line)
            EntryJumpResolver.JumpTarget.NoSource -> LogJumpTarget.NoSource
            is EntryJumpResolver.JumpTarget.NotFound -> LogJumpTarget.NotFound(target.sourceFile)
            EntryJumpResolver.JumpTarget.MissingSource -> LogJumpTarget.MissingSource
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

    /** 更新工具栏慢查询计数徽章（O-1 Phase C）。 */
    private fun updateSlowQueryLabel() {
        val count = slowQueryCounter.slowCount
        slowQueryLabel?.let {
            if (count > 0) {
                // T-UiCoord F2：全文「慢查询: N」（约 65px）在一行定构下吃掉余量 → 改短徽章
                // 「慢 N」（复用存量键 log.panel.slowQueryBadge），全文进 tooltip 不丢信息
                it.text = MpSqlVisualizerBundle.message("log.panel.slowQueryBadge") + " " + count
                it.toolTipText = MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", count.toString())
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

    /** R4（W3 历史工作流包）：导出当前可见行集（lastRendered=面板过滤/搜索/排序后的实际可见集）。 */
    private inner class ExportVisibleAction : AnAction(MpSqlVisualizerBundle.message("log.export.visible")) {
        override fun actionPerformed(e: AnActionEvent) {
            exportVisibleEntries()
        }
    }

    /** R4 导出：格式三选 → 文件保存 → 后台写盘 → 通知（行集=lastRendered 快照）。 */
    internal fun exportVisibleEntries() {
        val rows = lastRendered.map {
            val source = buildString {
                if (!it.sourceFile.isNullOrBlank()) {
                    append(it.sourceFile)
                    if (it.sourceLine > 0) append(":").append(it.sourceLine)
                }
            }
            SqlExportRow(
                time = SqlExportService.formatTime(it.timestamp),
                sql = it.restoredSql,
                source = source,
                durationMs = it.executionTimeMs,
            )
        }
        if (rows.isEmpty()) return
        val format = chooseExportFormat() ?: return
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
            MpSqlVisualizerBundle.message("log.export.visible"),
            "SQL (${format.ext.uppercase()})",
            format.ext,
        )
        val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
        val saved = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "sql-log.${format.ext}")
            ?: return
        val target = saved.file
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                SqlExportService.exportToFile(target, SqlExportService.format(rows, format))
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

    /** R4：导出格式三选（取消返回 null）。 */
    private fun chooseExportFormat(): SqlExportFormat? {
        val names = arrayOf("CSV", "TSV", "TXT")
        val idx = com.intellij.openapi.ui.Messages.showChooseDialog(
            project,
            MpSqlVisualizerBundle.message("log.history.export.choose"),
            MpSqlVisualizerBundle.message("log.export.visible"),
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
            names,
            names[0],
        )
        if (idx < 0) return null
        return when (idx) {
            0 -> SqlExportFormat.CSV
            1 -> SqlExportFormat.TSV
            else -> SqlExportFormat.TXT
        }
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
        // T-SqlSuppress（O-69）：屏蔽此 SQL（多选按不同指纹各成一条规则，已存在跳过）——
        // 置「删除」之后（计划定稿位），balloon 反馈带管理直达
        group.add(SuppressSqlAction(selectedEntries))
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
        // O-90：按模板聚焦（「×N 同模板」徽章达阈值才可用；过滤出该模板全部条目）
        group.add(FocusTemplateAction(entry))
        // R4（W3 历史工作流包）：导出当前可见行集（与面板过滤/搜索一致；CSV/TSV/TXT，后台写盘）
        group.add(ExportVisibleAction())
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

    /**
     * T-SqlSuppress（O-69）：屏蔽选中的 SQL。多选支持——去重后的**不同指纹**各成一条规则
     * （空白变体同指纹只建一条），已存在跳过（幂等）；达上限拒新增并提示（D6）。
     * 动作链：规则入库（监听器同步更新缓冲键集）→ refreshList（存量心跳行立即消失，D4 最强反馈）
     * → balloon 反馈附「管理屏蔽规则…」直达对话框（D4 中层）。
     */
    private inner class SuppressSqlAction(private val entries: List<LogSqlEntry>) : AnAction() {
        override fun update(e: AnActionEvent) {
            val count = entries.distinctBy { it.dedupeKey }.size
            e.presentation.text = if (count > 1) {
                MpSqlVisualizerBundle.message("log.suppress.actionSelected", count.toString())
            } else {
                MpSqlVisualizerBundle.message("log.suppress.action")
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val ruleManager = SqlSuppressRuleManager.getInstance()
            val (added, limitReached) = ruleManager.addRules(entries.distinctBy { it.dedupeKey }.map { it.restoredSql }) // F21：单次 republish
            refreshList() // addRule 的监听器已同步更新缓冲键集——存量行立即消失
            if (added > 0) showSuppressBalloon(added)
            if (limitReached) showSuppressLimitBalloon()
        }
    }

    /**
     * T-SqlSuppress：屏蔽成功 balloon（D5 定案独立通知组 logsuppress）。
     * 附「管理屏蔽规则…」动作直达对话框；测试模式跳过（对齐 [showJumpToast] 约定）。
     */
    private fun showSuppressBalloon(addedCount: Int) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(SUPPRESS_NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("log.suppress.added.title"),
                    MpSqlVisualizerBundle.message("log.suppress.added.body", addedCount.toString()),
                    NotificationType.INFORMATION,
                )
            notification.addAction(
                NotificationAction.createSimple(MpSqlVisualizerBundle.message("log.suppress.manage")) {
                    notification.expire()
                    SqlSuppressManagerDialog(project).show()
                },
            )
            notification.notify(project)
        }
    }

    /** T-SqlSuppress：达上限提示 balloon（D6：拒新增不淘汰，文案引导先删不用的规则）。 */
    private fun showSuppressLimitBalloon() {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(SUPPRESS_NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("log.suppress.added.title"),
                    MpSqlVisualizerBundle.message(
                        "log.suppress.limit",
                        SqlSuppressRuleManager.MAX_RULES.toString(),
                    ),
                    NotificationType.WARNING,
                )
                .notify(project)
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

    /**
     * O-90：按模板聚焦——右键带「×N 同模板」徽章的条目产生模板页签（structureKey 精确匹配，
     * 过滤出该模板全部条目；与 04 请求聚焦同一交互形态：条目驱动的聚焦过滤器，页签可关闭恢复）。
     * 准入 = 计数达 [TEMPLATE_BADGE_MIN_COUNT]（徽章在场才可点）；同模板重复创建幂等
     * （[addTemplateTab] 按 structureKey 判重）。徽章本体为展示性（与去重徽章先例一致——
     * 列表片段非交互组件），聚焦入口落在本动作。
     */
    private inner class FocusTemplateAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.focusTemplate")
            e.presentation.isEnabled = (templateCounts[entry.structureKey] ?: 0) >= TEMPLATE_BADGE_MIN_COUNT
        }

        override fun actionPerformed(e: AnActionEvent) {
            val n = templateCounts[entry.structureKey] ?: return
            if (n >= TEMPLATE_BADGE_MIN_COUNT) addFocusTab(FocusTab.Template(entry.structureKey, n))
        }
    }

    /** 面板销毁：释放单击预览弹窗与日志转 SQL / SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    override fun dispose() {
        // T-Perf-24：停止搜索防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop()
        // T-CaptureTruth T-CT-03：停止诊断定时器（同上，防晚到 syncDiagnostics 触碰已销毁列表）
        diagnosticsTimer?.stop()
        diagnosticsTimer = null
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
        /** T-SqlSuppress（O-69/D5）：屏蔽规则 balloon 通知组（plugin.xml 独立注册）。 */
        private const val SUPPRESS_NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.logsuppress"
        /** T-Perf-24（自 dev 移植）：搜索输入防抖窗口（毫秒）。 */
        private const val SEARCH_DEBOUNCE_MS = 200

        /**
         * T-UiCoord F2：两个下拉按钮（SQL类型 / 排序）显示文本的像素上限（@100% 缩放，用 JBUI.scale 取用）。
         *
         * 定宽封顶是「工具栏恒为单行」的前提之一：`SQL类型` 多选后文案会到「查询, 更新, 删除, 插入」，
         * 不封顶时行宽随选择漂移（旧流式行正是这么折到第二行的）。封顶后按钮宽度只由预算决定，
         * 超限截尾省略、tooltip 恒全文（见 [clipToDropdownBudget]）。
         *
         * **取值依据（2026-09-21 headless 实测，非估算）**：每个下拉按钮的固定开销 = 图标 16 + LaF 内边距 38
         * ≈ 54px（与文本无关），勾选固定 102px。用户侧边栏 460px、图标组真机量测约 133px 下：
         * 默认态（「全部」）控制组 ≈300 → 合计 433（余 27px）；多选态封顶后控制组 ≈333 → 合计 466。
         * 预算取 64（不是初版计划的 78）：78 时多选态控制组 346，超 `460 − 图标组保守下限 120 = 340`
         * 的预算锁。多选态余下约 6px 的超出量（相对图标组量测中值）由平台图标溢出兜底，
         * 属登记边界而非缺陷——见计划 §七 待验证前提 2。
         */
        private const val DROPDOWN_TEXT_BUDGET_PX = 64
        /** T-CaptureTruth T-CT-03：零产出诊断刷新间隔（与桥轮询同频 2s）。 */
        private const val DIAGNOSTICS_TICK_MS = 2000
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

/**
 * O-90：同模板计数徽章（N+1 捕获后聚类标记）——counts[structureKey] 达 [TEMPLATE_BADGE_MIN_COUNT]
 * 才显示「×N 同模板」，与去重 ⨉N 徽章相邻同落点（元信息行尾）。计数由调用方传面板全量条目的
 * [structureKeyCounts] 聚合快照（徽章计数不随当前过滤波动）；无计数/未达阈值返回 null 不显示。
 * 文案走双语 bundle（log.panel.templateBadge）；着色 [MpvsUiColors.TEMPLATE_N1]（选中白同规）。
 */
internal fun formatTemplateCountFragment(
    entry: LogSqlEntry,
    counts: Map<String, Int>,
    isSelected: Boolean,
): Pair<String, SimpleTextAttributes>? {
    val n = counts[entry.structureKey] ?: return null
    if (n < TEMPLATE_BADGE_MIN_COUNT) return null
    val color = if (isSelected) Color(0xFFFFFF) else MpvsUiColors.TEMPLATE_N1
    return MpSqlVisualizerBundle.message("log.panel.templateBadge", n) + " " to
        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
}

/** T-AgentDialectLoop：目标库角标（agent 通道由连接 URL 推断）；未知返回 null 不显示。 */
internal fun formatDatabaseFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>? {
    val db = entry.databaseName ?: return null
    val color = if (isSelected) Color(0xFFFFFF) else Color(0x00695C)
    return "[$db] " to SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
}

/**
 * T-McpAuditTrail T-MAT-05：MCP 通道徽标——`source == MCP`（MCP 执行类工具跑过的语句）才显示，
 * 其余来源返回 null 不显示（与目标库角标同规：未知即不占位，零噪音）。
 *
 * 落点与既有角标一致（时间/耗时/目标库之后、来源之前），协议同为
 * `Pair<String, SimpleTextAttributes>?`——两页渲染器（[LogEntryListCellRenderer] 与历史页
 * [HistoryCellRenderer]）共用本函数，避免「同条目两页显示不一致」。
 * 读回侧来源由 [SqlSource.MCP] 的 pd 载荷回填（见 SqlLogParamCodec）。
 */
internal fun formatMcpFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>? {
    if (entry.source != SqlSource.MCP) return null
    val color = if (isSelected) Color(0xFFFFFF) else Color(0x7B1FA2)
    return "[MCP] " to SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
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

    /** O-90：同模板计数快照（「×N 同模板」徽章渲染数据源；refreshList 随 dedupeCounts 一并更新）。 */
    var templateCounts: Map<String, Int> = emptyMap()

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
            // T-McpAuditTrail T-MAT-05：MCP 徽标（同落点：目标库后、来源前；非 MCP 返回 null 不显示）
            formatMcpFragment(entry, useSelectionFill)?.let { (t, a) -> metaLine.append(t, a) }
            formatSourceFragment(entry, useSelectionFill)?.let { (t, a) -> metaLine.append(t, a) }
            // 注：原「[⚡增强]」来源角标已按用户要求移除（增强为默认捕获通道，逐行标来源属噪音）
            formatUpdateCountFragment(entry, isSelected)?.let { (t, a) -> metaLine.append(t, a) }
            formatErrorFragment(entry)?.let { (t, a) -> metaLine.append(t, a) }
            dedupeCounts[entry.dedupeKey]?.takeIf { it > 0 }?.let { metaLine.append("⨉$it ", LIST_META_GRAY) }
            // O-90：同模板 ×N 徽章（去重徽章后相邻；≥TEMPLATE_BADGE_MIN_COUNT 才显示）
            formatTemplateCountFragment(entry, templateCounts, isSelected)?.let { (t, a) -> metaLine.append(t, a) }
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
