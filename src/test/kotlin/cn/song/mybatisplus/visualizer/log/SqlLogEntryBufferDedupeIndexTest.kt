package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SqlLogPerf P6（20261006-1020 计划）：去重索引等价性守卫。
 *
 * isDuplicate 从锁内 O(n) 全表扫描改 dedupeKey→最近时间戳的 O(1) 查表（65 条/秒并发灌入下
 * 三通道写线程与 EDT snapshot 抢同一把锁，扫描是热路径大头）。索引必须与旧全表扫描**逐字等价**——
 * 本文件每个用例对应一个索引维护面（入队 upsert / 头部淘汰回扫 / dedupeExisting 重建 /
 * dedupeEnabled 翻 true 重建 / clear 清空 / removeEntry 单键重算），断言全部按**旧语义可推演的
 * 期望值**写：任一维护面漏账，对应用例必红（摘掉修复行 → 必红，反向验证）。
 */
class SqlLogEntryBufferDedupeIndexTest {

    /** 窗口内同键重复必丢且计数；超窗重入必收（索引 upsert 面 + 窗口语义基线）。 */
    @Test
    fun `窗口内重复丢弃计数_超窗重入允许`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM dup WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertFalse("窗口内重复应丢弃", b.addEntry(entry(sql, 30_000)))
        assertEquals("丢弃计数 1", 1, b.dedupeCount(sql))
        assertTrue("超窗（70s > 60s 窗口）重入应允许", b.addEntry(entry(sql, 70_000)))
        assertFalse("重入后再来窗口内重复应丢弃", b.addEntry(entry(sql, 100_000)))
        assertEquals("两次丢弃计数累计 2", 2, b.dedupeCount(sql))
    }

    /** 头部淘汰回扫面：被淘汰者非该键最新时，索引不得误删/误留旧时间戳。 */
    @Test
    fun `淘汰非最新同键条目_索引保持最近时间戳`() {
        val b = SqlLogEntryBuffer(maxEntries = 2)
        val sql = "SELECT * FROM evict WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertTrue("超窗重入", b.addEntry(entry(sql, 70_000)))
        assertTrue(b.addEntry(entry("SELECT * FROM other WHERE id = 9", 71_000))) // 挤掉 A@0（头部）
        assertEquals("容量 2：A@0 被淘汰", 2, b.snapshot().size)
        // 索引若误留 A@0（未回扫）→ 100-0=100s 超窗被收；正确基线（队内 A@70s）→ 30s < 60s 丢弃
        assertFalse("淘汰后索引应保持队内最近时间戳 A@70s（新条目落窗口内必丢）", b.addEntry(entry(sql, 100_000)))
    }

    /** dedupeExisting 重建面：手动裁剪只留最早一条，索引必须随队重建（不得留被裁掉的最新时间戳）。 */
    @Test
    fun `手动去重裁剪后_索引随队重建`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM manual WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertTrue("超窗重入", b.addEntry(entry(sql, 70_000)))
        assertEquals("裁剪掉后到者", 1, b.dedupeExisting())
        assertEquals("队内只剩最早一条", 0, b.snapshot().first().timestamp)
        // 索引若误留被裁掉的 A@70s → 100-70=30s < 60s 被丢；正确基线（队内 A@0）→ 100s 超窗必收
        assertTrue("裁剪后索引应回到队内时间戳 A@0（超窗新条目必收）", b.addEntry(entry(sql, 100_000)))
    }

    /** dedupeEnabled 翻 true 重建面：关闭期间入队不维护索引，重开必须全量补账。 */
    @Test
    fun `去重开关重开_索引全量补账`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM toggle WHERE id = 1"
        b.dedupeEnabled = true
        assertTrue(b.addEntry(entry(sql, 0)))
        b.dedupeEnabled = false
        assertTrue("关闭期间不去重", b.addEntry(entry(sql, 70_000)))
        b.dedupeEnabled = true
        // 索引若未补账（仍 A@0）→ 80-0=80s 超窗被收；正确基线（队内 A@70s）→ 10s < 60s 必丢
        assertFalse("重开后索引应补账到队内最近 A@70s（窗口内必丢）", b.addEntry(entry(sql, 80_000)))
    }

    /** clear 清空面：索引随队清空，清空后同键立即重入必收。 */
    @Test
    fun `清空后索引随队清空_同键立即重入允许`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM wipe WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        b.clear()
        assertTrue("清空后同键重入应允许（索引不得残留旧时间戳）", b.addEntry(entry(sql, 10_000)))
    }

    /** removeEntry 单键重算面：删除该键最新条目后，索引必须回到队内余条的时间戳。 */
    @Test
    fun `删除单条后_索引回扫队内余条`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM remove WHERE id = 1"
        b.dedupeEnabled = false // 先关去重装两个同键条目（超窗形态）
        b.addEntry(entry(sql, 0))
        b.addEntry(entry(sql, 70_000))
        b.dedupeEnabled = true
        // removeEntry 按同一性删除（buffer 入库的是带序号 copy，外部须持快照实例——SqlLogEntryBufferTest 同口径）
        val storedSecond = b.snapshot().last { it.timestamp == 70_000L }
        b.removeEntry(storedSecond)
        // 索引若误留被删的 A@70s → 80-70=10s < 60s 被丢；正确基线（队内 A@0）→ 80s 超窗必收
        assertTrue("删除最新同键后索引应回扫到队内 A@0（超窗新条目必收）", b.addEntry(entry(sql, 80_000)))
    }

    /** 等价性基线的锚定断言：窗口语义逐字不变（now - latest < window，边界等值不丢）。 */
    @Test
    fun `窗口边界等值不丢弃`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM edge WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertTrue("now-latest == window（60s）不小于窗口 → 不丢", b.addEntry(entry(sql, 60_000)))
    }

    private fun entry(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
    )
}
