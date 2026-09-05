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

    // ---------- F-43：onEvicted 回调 ----------

    @Test
    fun `onEvicted 回调在淘汰时触发`() {
        val buf = SqlLogEntryBuffer(maxEntries = 2)
        val evicted = mutableListOf<String>()
        buf.onEvicted = { e -> evicted.add(e.restoredSql) }
        buf.addEntry(entry("SELECT 1"))
        buf.addEntry(entry("SELECT 2"))
        buf.addEntry(entry("SELECT 3")) // 淘汰 SELECT 1
        assertEquals(listOf("SELECT 1"), evicted)
    }

    @Test
    fun `onEvicted=null 时无异常`() {
        val buf = SqlLogEntryBuffer(maxEntries = 1)
        buf.onEvicted = null
        buf.addEntry(entry("SELECT 1"))
        buf.addEntry(entry("SELECT 2")) // 淘汰 SELECT 1，无回调
        assertEquals(1, buf.snapshot().size)
        assertEquals("SELECT 2", buf.snapshot()[0].restoredSql)
    }

    @Test
    fun `多条目连续淘汰触发多次回调`() {
        val buf = SqlLogEntryBuffer(maxEntries = 1)
        val evicted = mutableListOf<String>()
        buf.onEvicted = { e -> evicted.add(e.restoredSql) }
        buf.addEntry(entry("SELECT 1"))
        buf.addEntry(entry("SELECT 2"))
        buf.addEntry(entry("SELECT 3"))
        buf.addEntry(entry("SELECT 4"))
        assertEquals(listOf("SELECT 1", "SELECT 2", "SELECT 3"), evicted)
    }

    @Test
    fun `onEvicted 回调顺序为最早到最新`() {
        val buf = SqlLogEntryBuffer(maxEntries = 2)
        val evicted = mutableListOf<String>()
        buf.onEvicted = { e -> evicted.add(e.restoredSql) }
        buf.addEntry(entry("A"))
        buf.addEntry(entry("B"))
        buf.addEntry(entry("C")) // 淘汰 A
        buf.addEntry(entry("D")) // 淘汰 B
        buf.addEntry(entry("E")) // 淘汰 C
        assertEquals(listOf("A", "B", "C"), evicted)
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
    fun `去重窗口内相同 SQL 丢弃且通知徽章`() {
        // F-44-2：用户实测「重复值计数更新的也不及时」——徽章数据变了必须通知 UI，
        // 本用例断言「首条入列通知 + 末尾丢弃通知」共 2 次。
        buffer.dedupeEnabled = true
        var notified = 0
        buffer.addListener { notified++ }
        assertTrue(buffer.addEntry(entryAt("SELECT 1", 0L)))
        assertFalse(buffer.addEntry(entryAt("SELECT 1", 500L))) // 窗口 1000ms 内
        assertEquals(2, notified) // 入列 1 次 + 丢弃通知 1 次
        assertEquals(1, buffer.snapshot().size)
        assertEquals(1, buffer.dedupeCount("SELECT 1"))
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

    // ---------- 去重丢弃计数（列表 ⨉N 徽章数据源） ----------

    @Test
    fun `去重丢弃计数随丢弃递增`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃 1 次
        buffer.addEntry(entryAt("SELECT 1", 200L)) // 丢弃 2 次
        assertEquals(2, buffer.dedupeCount("SELECT 1"))
        assertEquals(2, buffer.dedupeCountsSnapshot()[sqlFingerprint("SELECT 1")]) // T-Perf-19：键=SQL指纹
    }

    @Test
    fun `去重计数键为还原SQL原文_不同SQL互不干扰`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 2", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃
        assertEquals(1, buffer.dedupeCount("SELECT 1"))
        assertEquals(0, buffer.dedupeCount("SELECT 2"))
        assertEquals(0, buffer.dedupeCount("SELECT 3")) // 无记录返回 0
    }

    @Test
    fun `clear 归零去重计数`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃 1 次
        assertEquals(1, buffer.dedupeCount("SELECT 1"))
        buffer.clear()
        assertEquals(0, buffer.dedupeCount("SELECT 1"))
    }

    @Test
    fun `resetDedupeCounts 归零而不清列表`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃 1 次
        buffer.resetDedupeCounts()
        assertEquals(0, buffer.dedupeCount("SELECT 1"))
        assertEquals(1, buffer.snapshot().size) // 列表条目保留
    }

    @Test
    fun `去重关闭期间不累计计数`() {
        buffer.dedupeEnabled = false
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 去重关闭，正常入列
        assertEquals(0, buffer.dedupeCount("SELECT 1"))
    }

    @Test
    fun `去重丢弃触发监听器`() {
        // F-44-2：徽章数据变了必须通知 UI（实测反馈「不及时」是缺陷）
        buffer.dedupeEnabled = true
        var notified = 0
        buffer.addListener { notified++ }
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃
        assertEquals(2, notified) // 第一条入列 + 第二条丢弃
    }

    // ---------- F-44-2：dedupeExisting / disableDedupe ----------

    @Test
    fun `dedupeExisting 按窗口裁剪既有条目`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        // 先关闭去重以便添加多条重复条目（验证 dedupeExisting 自身的去重逻辑，而非 addEntry 的）
        buf.dedupeEnabled = false
        buf.dedupeWindowMs = 1000L
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 1", 200L))
        buf.addEntry(entryAt("SELECT 1", 400L))
        buf.addEntry(entryAt("SELECT 2", 800L))
        // 不动缓冲，先确认现有 4 条都在
        assertEquals(4, buf.snapshot().size)
        // 用户勾选「移除重复的sql」→ 启用 dedupeEnabled 后调用 dedupeExisting
        buf.dedupeEnabled = true
        val removed = buf.dedupeExisting()
        // SELECT 1 三个 → 留首条、丢 2 个；SELECT 2 不重复 → 0 个；总移除 2
        assertEquals(2, removed)
        assertEquals(2, buf.snapshot().size)
        assertEquals(0L, buf.snapshot()[0].timestamp) // 首条保留
        assertEquals("SELECT 2", buf.snapshot()[1].restoredSql)
        assertEquals(2, buf.dedupeCount("SELECT 1"))
    }

    @Test
    fun `dedupeExisting 关闭时不生效`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = false
        buf.dedupeWindowMs = 1000L
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 1", 100L))
        assertEquals(0, buf.dedupeExisting())
        assertEquals(2, buf.snapshot().size)
    }

    @Test
    fun `dedupeExisting 单条不触发`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = true
        buf.addEntry(entryAt("SELECT 1", 0L))
        assertEquals(0, buf.dedupeExisting())
        assertEquals(1, buf.snapshot().size)
    }

    @Test
    fun `dedupeExisting 超窗口保留所有`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        // 同上：先关闭以让多条重复都入列
        buf.dedupeEnabled = false
        buf.dedupeWindowMs = 1000L
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 1", 2000L)) // 超 1s 窗口
        assertEquals(2, buf.snapshot().size)
        // 再开启 dedupeEnabled 后调用 dedupeExisting，验证窗口判定
        buf.dedupeEnabled = true
        assertEquals(0, buf.dedupeExisting())
        assertEquals(2, buf.snapshot().size)
    }

    @Test
    fun `disableDedupe 清空累计计数`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = false
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 1", 100L))
        // 关闭态 → 入列，不累计计数
        assertEquals(0, buf.dedupeCount("SELECT 1"))
        buf.dedupeEnabled = true
        buf.addEntry(entryAt("SELECT 1", 200L)) // 现在窗口内，去重 +1
        assertEquals(1, buf.dedupeCount("SELECT 1"))
        buf.disableDedupe()
        assertEquals(0, buf.dedupeCount("SELECT 1"))
        assertFalse(buf.dedupeEnabled)
    }

    @Test
    fun `disableDedupe 重复调用幂等`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = true
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.disableDedupe()
        // 二次调用不应抛异常/重置状态报错
        buf.disableDedupe()
        assertEquals(0, buf.dedupeCount("SELECT 1"))
    }
}
