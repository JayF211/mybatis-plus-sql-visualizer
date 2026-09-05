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

    /** T-Perf-03：自上次 drain 以来的头部淘汰条数（面板增量刷新对齐用；drain 约定单消费方——面板 EDT 刷新）。 */
    private val evictedSinceDrain = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    var paused: Boolean = false
        internal set

    /** 去重开关（默认开——用户实测后要求默认勾选，T-M31.13）。 */
    @Volatile
    var dedupeEnabled: Boolean = true

    /**
     * 去重丢弃计数（T-Perf-19：键 = restoredSql 指纹 [sqlFingerprint]，非 SQL 原文——
     * 原文键在条目淘汰后文本永久驻留，长会话可达数十 MB）：去重开启期间每丢弃
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

    /** 新条目入队（最早在前，追加到队尾；暂停态丢弃；过滤关键字命中丢弃；去重窗口内相同 SQL 丢弃并计数；超过上限裁掉最旧）。
     *
     * 去重丢弃分支也调用 [notifyChanged]——徽章数据已变，UI 必须即时刷新（用户实测：
     * 不通知则 ⨉N 徽章要等下一条 SQL 入列才更新，「不及时」）。
     *
     * T-Perf-06：去重判定与入队合并进同一 `synchronized(entries)` 临界区——此前 [isDuplicate]
     * 在锁外无锁遍历 ArrayDeque，与 EDT 清空/删除（clear/removeEntry）并发修改会触发
     * ConcurrentModificationException，经 LogFilterProxyHandler（无 catch）上浮为 IDE 红色报错弹窗。 */
    fun addEntry(entry: LogSqlEntry): Boolean {
        if (paused) return false
        if (isFiltered(entry.restoredSql)) return false
        var duplicate = false
        val evictedEntries = mutableListOf<LogSqlEntry>()
        synchronized(entries) {
            if (dedupeEnabled && isDuplicate(entry)) {
                duplicate = true
            } else {
                entries.addLast(entry)
                while (entries.size > maxEntries) {
                    evictedEntries.add(entries.removeFirst())
                    evictedSinceDrain.incrementAndGet() // T-Perf-03：头部淘汰计数（面板增量刷新）
                }
            }
        }
        if (duplicate) {
            dedupeCounts.merge(entry.dedupeKey, 1, Int::plus)
            notifyChanged() // ← 徽章计数变了 → UI 必须即时刷新
            return false
        }
        // T-Perf-12：onEvicted 回调移出 synchronized(entries)——回调链（自动保存磁盘写）
        // 在锁内同步执行会阻塞 EDT snapshot() 抢同一把锁（控制台输出热路径互相卡）
        evictedEntries.forEach { onEvicted?.invoke(it) }
        notifyChanged()
        return true
    }

    /**
     * 手动触发去重裁剪（用户勾选「移除重复的sql」时）：扫描现有 entries，
     * 时间窗内相同 SQL 保留最早一条、丢弃后续；丢弃数累加到 [dedupeCounts]
     * （与运行时去重共享计数——徽章反映「本次去重开启以来的总丢弃量」，含开启前已存在的重复）。
     *
     * @return 本次裁剪移除的条目数（面板日志/测试断言）。
     */
    fun dedupeExisting(): Int {
        if (!dedupeEnabled) return 0
        var removed = 0
        synchronized(entries) {
            if (entries.size < 2) return@synchronized
            val keep = mutableListOf<LogSqlEntry>()
            for (entry in entries) {
                val duplicate = keep.firstOrNull {
                    it.restoredSql == entry.restoredSql &&
                        kotlin.math.abs(entry.timestamp - it.timestamp) < dedupeWindowMs
                }
                if (duplicate != null) {
                    dedupeCounts.merge(entry.dedupeKey, 1, Int::plus)
                    removed++
                } else {
                    keep += entry
                }
            }
            if (removed > 0) {
                entries.clear()
                entries.addAll(keep)
            }
        }
        if (removed > 0) notifyChanged()
        return removed
    }

    /**
     * 关闭去重：清空累计计数（语义：「关闭」= 清零；再次开启时从零起算）。
     * 若原本未开启或计数本就为空，跳过通知（避免无意义 EDT 调度）。
     */
    fun disableDedupe() {
        if (!dedupeEnabled) return
        dedupeEnabled = false
        val hadCounts = dedupeCounts.isNotEmpty()
        resetDedupeCounts()
        if (hadCounts) notifyChanged()
    }

    private fun isFiltered(sql: String): Boolean =
        filterKeywords.isNotEmpty() && filterKeywords.any { sql.contains(it, ignoreCase = true) }

    /** 去重判定（T-Perf-06：**必须在 `synchronized(entries)` 内调用**——无锁遍历 ArrayDeque 会与清空/删除并发 CME）。 */
    private fun isDuplicate(entry: LogSqlEntry): Boolean {
        val now = entry.timestamp
        return entries.firstOrNull { it.restoredSql == entry.restoredSql && now - it.timestamp < dedupeWindowMs } != null
    }

    /** 最早在前的快照（正序）。 */
    fun snapshot(): List<LogSqlEntry> = synchronized(entries) { entries.toList() }

    /**
     * T-Perf-03：取走自上次调用以来的头部淘汰条数并归零。
     * 单消费方约定（面板 EDT 刷新）：与 [snapshot] 搭配，用于增量刷新的头部对齐；
     * 其他调用方调用会偷走面板的对齐信息（回退全量重建，正确性不受影响）。
     */
    fun drainEvictedCount(): Int = evictedSinceDrain.getAndSet(0)

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

    /** 指定 SQL 的去重丢弃次数（参数为还原 SQL 原文，内部按指纹查；无丢弃记录返回 0）。 */
    fun dedupeCount(sql: String): Int = dedupeCounts[sqlFingerprint(sql)] ?: 0

    /** 去重丢弃计数快照（键 = [sqlFingerprint] 指纹，值 = 丢弃次数；供面板渲染徽章）。 */
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
