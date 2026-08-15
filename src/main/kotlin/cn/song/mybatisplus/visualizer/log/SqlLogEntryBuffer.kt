package cn.song.mybatisplus.visualizer.log

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 日志条目缓冲（T-M31-03，纯逻辑，不依赖平台）：条目列表（最新在前、上限裁剪）、
 * 暂停态、去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」——相同 SQL 在窗口内重复丢弃）、
 * 监听器登记——线程安全（任意线程可 [addEntry]，监听器回调不在此处调度）。
 *
 * 与平台壳 [SqlLogManager] 分离：本类可纯 JUnit 测试（无 Application 依赖）。
 */
class SqlLogEntryBuffer(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val entries = ArrayDeque<LogSqlEntry>()

    @Volatile
    var paused: Boolean = false
        private set

    /** 去重开关（默认关——用户手动勾选后才启用，T-M31.7 用户要求）。 */
    @Volatile
    var dedupeEnabled: Boolean = false

    /** 去重窗口（毫秒）：相同还原 SQL 在该窗口内重复出现时丢弃后到者。 */
    @Volatile
    var dedupeWindowMs: Long = DEFAULT_DEDUPE_WINDOW_MS

    /** 新条目入队（最新在前；暂停态丢弃；去重窗口内相同 SQL 丢弃；超过上限裁掉最旧）。 */
    fun addEntry(entry: LogSqlEntry): Boolean {
        if (paused) return false
        if (dedupeEnabled && isDuplicate(entry)) return false
        synchronized(entries) {
            entries.addFirst(entry)
            while (entries.size > maxEntries) entries.removeLast()
        }
        notifyChanged()
        return true
    }

    private fun isDuplicate(entry: LogSqlEntry): Boolean {
        val now = entry.timestamp
        return entries.firstOrNull { it.restoredSql == entry.restoredSql && now - it.timestamp < dedupeWindowMs } != null
    }

    /** 最新在前的快照。 */
    fun snapshot(): List<LogSqlEntry> = synchronized(entries) { entries.toList() }

    /** 清空全部。 */
    fun clear() {
        synchronized(entries) { entries.clear() }
        notifyChanged()
    }

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
        private const val DEFAULT_DEDUPE_WINDOW_MS = 1000L
    }
}
