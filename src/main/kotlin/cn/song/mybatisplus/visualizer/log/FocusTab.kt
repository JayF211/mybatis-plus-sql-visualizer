package cn.song.mybatisplus.visualizer.log

/**
 * T-M55-03/T-M55-04 共用：动态聚焦过滤页签模型（页签是过滤器不是分组——不碰 VisibleDiff
 * 差分与缓冲分组模型，呈现保持扁平时间序）。
 *
 * - [Method]：调用方方法行号区间近似聚焦（[CallerMethodRangeResolver] 静态解析
 *   sourceFile+sourceLine 所在 PsiMethod 的行号区间；命名 `FooService#bar`）；
 * - [Request]：请求标签聚焦（[LogSqlEntry.requestTag]，同一次请求的多条 SQL 共享同值）。
 *
 * 多开语义 = 并集过滤（条目命中任一开启页签即可见）；无对应维度的条目（无 sourceFile /
 * 无 requestTag）天然不命中任何页签，在聚焦视图下隐藏（面板有计数提示）。
 */
internal sealed class FocusTab {
    abstract val label: String

    /** 调用方方法聚焦（行号区间含两端；[sourceFile] 已规约化——正斜杠、段对齐比较）。 */
    data class Method(
        val sourceFile: String,
        val startLine: Int,
        val endLine: Int,
        val className: String,
        val methodName: String,
    ) : FocusTab() {
        override val label: String get() = "$className#$methodName"
    }

    /** 请求标签聚焦（精确匹配）。 */
    data class Request(val requestTag: String) : FocusTab() {
        override val label: String get() = requestTag
    }
}

/** 单条目是否命中单个聚焦页签。 */
internal fun entryMatchesFocusTab(entry: LogSqlEntry, tab: FocusTab): Boolean = when (tab) {
    is FocusTab.Method ->
        entry.sourceFile != null && entry.sourceLine > 0 &&
            EntrySourceMatcher.sameFile(EntrySourceMatcher.normalize(entry.sourceFile), tab.sourceFile) &&
            entry.sourceLine in tab.startLine..tab.endLine
    is FocusTab.Request -> entry.requestTag != null && entry.requestTag == tab.requestTag
}

/**
 * 聚焦页签过滤（纯逻辑，可独立单测；插在既有 filterByFocus 与 filterBySearch 之间的再一层）：
 * 无页签 = 透传；有页签 = 命中任一页签的条目保留（并集），其余隐藏。
 */
internal fun filterByFocusTabs(entries: List<LogSqlEntry>, tabs: List<FocusTab>): List<LogSqlEntry> =
    if (tabs.isEmpty()) entries
    else entries.filter { entry -> tabs.any { entryMatchesFocusTab(entry, it) } }

/** 请求聚焦页签上限（K 有界——长压测会话 requestTag 高基数，防页签栏爆列表）。 */
internal const val FOCUS_REQUEST_TAB_MAX = 8

/**
 * 新增请求页签的准入（纯函数）：去重、K 有界——超限挤掉最早的请求页签（method 页签不受影响，
 * 用户显式聚焦的操作方法不被自动页签顶掉），返回收缩后的**新**列表（调用方 clear+addAll 回写，
 * 不得原样返回入参可变列表——别名回写会自清空）。
 */
internal fun addRequestTab(tabs: List<FocusTab>, requestTag: String): List<FocusTab> {
    val tag = requestTag.trim()
    if (tag.isEmpty()) return tabs.toList()
    if (tabs.any { it is FocusTab.Request && it.requestTag == tag }) return tabs.toList()
    val requestTabs = tabs.filterIsInstance<FocusTab.Request>()
    val others = tabs.filter { it !is FocusTab.Request }
    val next = requestTabs + FocusTab.Request(tag)
    val trimmed = if (next.size > FOCUS_REQUEST_TAB_MAX) next.drop(next.size - FOCUS_REQUEST_TAB_MAX) else next
    return others + trimmed
}
