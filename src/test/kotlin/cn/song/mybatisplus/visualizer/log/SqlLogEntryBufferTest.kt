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
    fun `新增条目最新在前`() {
        buffer.addEntry(entry("SELECT 1"))
        buffer.addEntry(entry("SELECT 2"))
        val snap = buffer.snapshot()
        assertEquals(2, snap.size)
        assertEquals("SELECT 2", snap[0].restoredSql)
        assertEquals("SELECT 1", snap[1].restoredSql)
    }

    @Test
    fun `超过上限裁掉最旧`() {
        buffer.addEntry(entry("SELECT 1"))
        buffer.addEntry(entry("SELECT 2"))
        buffer.addEntry(entry("SELECT 3"))
        buffer.addEntry(entry("SELECT 4"))
        val snap = buffer.snapshot()
        assertEquals(3, snap.size)
        assertEquals("SELECT 4", snap[0].restoredSql)
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
        buffer.addEntry(entry())
        buffer.addEntry(entry())
        assertEquals(2, notified)
        buffer.removeListener(listener)
        buffer.addEntry(entry())
        assertEquals(2, notified)
    }
}
