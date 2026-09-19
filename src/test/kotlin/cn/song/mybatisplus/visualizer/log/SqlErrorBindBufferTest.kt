package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-M55-02：[SqlLogEntryBuffer] 错误反向绑定（原地更新最近条目）测试：
 * 绑定命中 / 缓冲为空丢弃 / 时间窗超窗丢弃 / 同文本幂等 / 去重指纹稳定 / 监听通知。
 */
class SqlErrorBindBufferTest {

    private fun buffer(vararg sqls: String, timestamp: Long = System.currentTimeMillis()): SqlLogEntryBuffer {
        val b = SqlLogEntryBuffer(10)
        b.dedupeEnabled = false
        sqls.forEach { sql ->
            assertTrue(b.addEntry(LogSqlEntry(sql, sql, emptyList(), timestamp)))
        }
        return b
    }

    @Test
    fun `错误绑定最近条目_原地更新`() {
        val b = buffer("SELECT 1", "SELECT 2")
        val before = b.snapshot()
        val err = "java.sql.SQLException: Unknown column 'x' in 'where clause'"
        assertEquals(SqlLogEntryBuffer.ErrorBindResult.BOUND, b.bindErrorToRecent(err))

        val after = b.snapshot()
        assertEquals("条目数不变（原地更新非追加）", before.size, after.size)
        assertNull("更早条目不受影响", after[0].errorText)
        assertEquals("最近条目携带错误文本", err, after[1].errorText)
        assertTrue("替换为 copy 实例（面板刷新靠实例更替重渲染）", after[1] !== before[1])
        // SQL 字段逐字保留
        assertEquals(before[1].restoredSql, after[1].restoredSql)
        assertEquals(before[1].sequenceNumber, after[1].sequenceNumber)
    }

    @Test
    fun `错误文本为空_丢弃`() {
        val b = buffer("SELECT 1")
        assertEquals(SqlLogEntryBuffer.ErrorBindResult.DROPPED_BLANK, b.bindErrorToRecent("  "))
    }

    @Test
    fun `缓冲为空_显式丢弃`() {
        val b = SqlLogEntryBuffer()
        assertEquals(SqlLogEntryBuffer.ErrorBindResult.DROPPED_EMPTY, b.bindErrorToRecent("java.sql.SQLException: boom"))
    }

    @Test
    fun `超时间窗_显式丢弃不误绑`() {
        val b = buffer("SELECT 1", timestamp = System.currentTimeMillis() - 10 * 60_000L)
        b.errorBindWindowMs = 5 * 60_000L
        val result = b.bindErrorToRecent("java.sql.SQLException: boom")
        assertEquals("条目远早于窗口 → 丢弃", SqlLogEntryBuffer.ErrorBindResult.DROPPED_WINDOW_EXPIRED, result)
        assertNull("条目不被写入错误", b.snapshot()[0].errorText)
    }

    @Test
    fun `窗口内正常绑定`() {
        val b = buffer("SELECT 1", timestamp = System.currentTimeMillis())
        b.errorBindWindowMs = 5 * 60_000L
        assertEquals(SqlLogEntryBuffer.ErrorBindResult.BOUND, b.bindErrorToRecent("java.sql.SQLException: boom"))
        assertEquals("java.sql.SQLException: boom", b.snapshot()[0].errorText)
    }

    @Test
    fun `同文本幂等重绑_实例不再替换`() {
        val b = buffer("SELECT 1")
        val err = "java.sql.SQLException: boom"
        b.bindErrorToRecent(err)
        val boundInstance = b.snapshot()[0]
        assertEquals(SqlLogEntryBuffer.ErrorBindResult.BOUND, b.bindErrorToRecent(err))
        assertSame("幂等重绑不得再替换实例", boundInstance, b.snapshot()[0])
    }

    @Test
    fun `去重指纹稳定_errorText不入指纹`() {
        val b = buffer("SELECT 1")
        val keyBefore = b.snapshot()[0].dedupeKey
        b.bindErrorToRecent("java.sql.SQLException: boom")
        assertEquals("dedupeKey 不含 errorText（SQL 指纹稳定，错误绑定代表条目）", keyBefore, b.snapshot()[0].dedupeKey)
    }

    @Test
    fun `绑定成功触发监听_丢弃不触发`() {
        val b = buffer("SELECT 1")
        var notified = 0
        b.addListener { notified++ }
        b.bindErrorToRecent("java.sql.SQLException: boom")
        assertEquals("绑定成功 → 内容监听触发（面板差分刷新）", 1, notified)
        notified = 0
        b.bindErrorToRecent("java.sql.SQLException: boom")
        assertEquals("幂等重绑不重复通知", 0, notified)
        b.bindErrorToRecent("   ")
        assertEquals("丢弃分支不通知", 0, notified)
    }

    @Test
    fun `去重开启时错误绑定代表条目_徽章计数不受影响`() {
        val b = SqlLogEntryBuffer()
        b.dedupeEnabled = true
        assertTrue(b.addEntry(LogSqlEntry("SELECT 1", "SELECT 1", emptyList())))
        // 重复 SQL 被去重丢弃（⨉1），代表条目仍在缓冲
        assertTrue(!b.addEntry(LogSqlEntry("SELECT 1", "SELECT 1", emptyList())))
        assertEquals(1, b.dedupeCount("SELECT 1"))
        b.bindErrorToRecent("java.sql.SQLException: boom")
        assertEquals("错误落在代表条目上", "java.sql.SQLException: boom", b.snapshot()[0].errorText)
        assertEquals("去重计数不受绑定影响", 1, b.dedupeCount("SELECT 1"))
    }
}
