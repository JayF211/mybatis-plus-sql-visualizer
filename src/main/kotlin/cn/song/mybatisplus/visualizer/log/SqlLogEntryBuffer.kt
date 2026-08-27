package cn.song.mybatisplus.visualizer.log

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 日志条目缓冲（T-M31-03，纯逻辑，不依赖平台）：条目列表（最早在前、上限裁剪）、
 * 暂停态、去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」——相同 SQL 在窗口内重复丢弃）、
 * 监听器登记——线程安全（任意线程可 [addEntry]，监听器回调不在此处调度）。
 *
 * 与平台壳 [SqlLogManager] 分离：本类可纯 JUnit 测试（无 Application 依赖）。
 */
class SqlLogEntryBuffer(
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /** 最大条目数（可变——运行时动态调整上限，超出自动淘汰最早）。 */
    @Volatile
    var maxEntries: Int = maxEntries

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val entries = ArrayDeque<LogSqlEntry>()

    @Volatile
    var paused: Boolean = false
        internal set

    /** 去重开关（默认开——用户实测后要求默认勾选，T-M31.13）。 */
    @Volatile
    var dedupeEnabled: Boolean = true

    /**
     * 去重丢弃计数（键 = restoredSql 原文，与 [isDuplicate] 匹配同源）：去重开启期间每丢弃
     * 一条相同 SQL 计数 +1，供列表「⨉N」徽章展示该 SQL 被去掉过几次。
     * 仅在 [dedupeEnabled] 为 true 时累计；[clear] 与 [resetDedupeCounts] 归零。
     */
    private val dedupeCounts = ConcurrentHashMap<String, Int>()

    /** 去重窗口（毫秒）：相同还原 SQL 在该窗口内重复出现时丢弃后到者。 */
    @Volatile
    var dedupeWindowMs: Long = DEFAULT_DEDUPE_WINDOW_MS

    /** 过滤关键字（T-M31.14，`;` 分隔后传入）：还原 SQL 包含任一关键字则丢弃。 */
    @Volatile
    var filterKeywords: List<String> = emptyList()

    /** 条目被淘汰时的回调（最早条目超出上限时被移除）；用于自动保存历史 SQL（F-43）。 */
    var onEvicted: ((LogSqlEntry) -> Unit)? = null

    /** 新条目入队（最早在前，追加到队尾；暂停态丢弃；过滤关键字命中丢弃；去重窗口内相同 SQL 丢弃并计数；超过上限裁掉最旧）。 */
    fun addEntry(entry: LogSqlEntry): Boolean {
        if (paused) return false
        if (isFiltered(entry.restoredSql)) return false
        if (dedupeEnabled && isDuplicate(entry)) {
            dedupeCounts.merge(entry.restoredSql, 1, Int::plus)
            return false
        }
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > maxEntries) {
                val evicted = entries.removeFirst()
                onEvicted?.invoke(evicted)
            }
        }
        notifyChanged()
        return true
    }

    private fun isFiltered(sql: String): Boolean =
        filterKeywords.isNotEmpty() && filterKeywords.any { sql.contains(it, ignoreCase = true) }

    private fun isDuplicate(entry: LogSqlEntry): Boolean {
        val now = entry.timestamp
        return entries.firstOrNull { it.restoredSql == entry.restoredSql && now - it.timestamp < dedupeWindowMs } != null
    }

    /** 最早在前的快照（正序）。 */
    fun snapshot(): List<LogSqlEntry> = synchronized(entries) { entries.toList() }

    /** 清空全部（去重计数一并归零——徽章与列表内容同生命周期）。 */
    fun clear() {
        synchronized(entries) { entries.clear() }
        resetDedupeCounts()
        notifyChanged()
    }

    /** 去重丢弃计数归零（清空列表、面板重新开启去重时调用）。 */
    fun resetDedupeCounts() {
        dedupeCounts.clear()
    }

    /** 指定 SQL 的去重丢弃次数（无丢弃记录返回 0）。 */
    fun dedupeCount(sql: String): Int = dedupeCounts[sql] ?: 0

    /** 去重丢弃计数快照（键 = restoredSql，值 = 丢弃次数；供面板渲染徽章）。 */
    fun dedupeCountsSnapshot(): Map<String, Int> = dedupeCounts.toMap()

    /** 删除单条（面板右键菜单）。 */
    fun removeEntry(entry: LogSqlEntry) {
        synchronized(entries) { entries.remove(entry) }
        notifyChanged()
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 200
        private const val DEFAULT_DEDUPE_WINDOW_MS = 60000L
    }
}
