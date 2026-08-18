package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 日志条目缓冲单元测试（T-M31-03，纯逻辑）。
 */
class SqlLogEntryBufferTest {

    private val buffer = SqlLogEntryBuffer(maxEntries = 3)

    private fun entry(sql: String = "SELECT 1") = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 0L,
    )

    @Test
    fun `新增条目最早在前`() {
        buffer.addEntry(entry("SELECT 1"))
        buffer.addEntry(entry("SELECT 2"))
        val snap = buffer.snapshot()
        assertEquals(2, snap.size)
        assertEquals("SELECT 1", snap[0].restoredSql)
        assertEquals("SELECT 2", snap[1].restoredSql)
    }

    @Test
    fun `超过上限裁掉最旧`() {
        buffer.addEntry(entry("SELECT 1"))
        buffer.addEntry(entry("SELECT 2"))
        buffer.addEntry(entry("SELECT 3"))
        buffer.addEntry(entry("SELECT 4"))
        val snap = buffer.snapshot()
        assertEquals(3, snap.size)
        assertEquals("SELECT 2", snap[0].restoredSql)
        assertFalse(snap.any { it.restoredSql == "SELECT 1" })
    }

    @Test
    fun `暂停态丢弃新增`() {
        buffer.setPaused(true)
        assertFalse(buffer.addEntry(entry()))
        assertTrue(buffer.snapshot().isEmpty())
        buffer.setPaused(false)
        assertTrue(buffer.addEntry(entry()))
    }

    @Test
    fun `清空与删除单条`() {
        val e1 = entry("SELECT 1")
        val e2 = entry("SELECT 2")
        buffer.addEntry(e1)
        buffer.addEntry(e2)
        buffer.removeEntry(e1)
        assertEquals(listOf("SELECT 2"), buffer.snapshot().map { it.restoredSql })
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `监听器在条目变更时通知`() {
        var notified = 0
        val listener: () -> Unit = { notified += 1 }
        buffer.addListener(listener)
        buffer.addEntry(entry("SELECT 1"))
        buffer.addEntry(entry("SELECT 2")) // 不同 SQL（去重不拦截）
        assertEquals(2, notified)
        buffer.removeListener(listener)
        buffer.addEntry(entry("SELECT 3"))
        assertEquals(2, notified)
    }

    // ---------- 去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」） ----------

    companion object {
        /** 与 SqlLogEntryBuffer 默认窗口（60000ms，T-M31.17）同步——超窗口用例时间差须大于它。 */
        private const val DEFAULT_WINDOW = 60000L
    }

    private fun entryAt(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
    )

    @Test
    fun `去重窗口内相同 SQL 丢弃且不通知`() {
        buffer.dedupeEnabled = true
        var notified = 0
        buffer.addListener { notified++ }
        assertTrue(buffer.addEntry(entryAt("SELECT 1", 0L)))
        assertFalse(buffer.addEntry(entryAt("SELECT 1", 500L))) // 窗口 1000ms 内
        assertEquals(1, notified) // 丢弃不通知
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `去重窗口外相同 SQL 保留`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertTrue(buffer.addEntry(entryAt("SELECT 1", DEFAULT_WINDOW + 100L))) // 超窗口
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `不同 SQL 不去重`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertTrue(buffer.addEntry(entryAt("SELECT 2", 100L)))
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `去重开关默认开启`() {
        assertTrue(buffer.dedupeEnabled) // 默认开（T-M31.13 用户实测后要求）
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertFalse(buffer.addEntry(entryAt("SELECT 1", 100L))) // 窗口内重复丢弃
    }

    @Test
    fun `过滤关键字命中时丢弃`() {
        buffer.filterKeywords = listOf("from_dual", "TMP_")
        assertFalse(buffer.addEntry(entryAt("SELECT * FROM from_dual", 0L)))
        assertFalse(buffer.addEntry(entryAt("SELECT * FROM TMP_LOG", 100L)))
        assertTrue(buffer.addEntry(entryAt("SELECT * FROM user", 200L)))
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `过滤关键字为空时全部保留`() {
        buffer.filterKeywords = emptyList()
        assertTrue(buffer.addEntry(entryAt("SELECT * FROM user", 0L)))
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `去重开关显式关闭时全部保留`() {
        buffer.dedupeEnabled = false
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertTrue(buffer.addEntry(entryAt("SELECT 1", 100L)))
        assertEquals(2, buffer.snapshot().size)
    }
}
