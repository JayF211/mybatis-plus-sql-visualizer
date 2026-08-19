package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 高频折叠分组器单元测试（P-06，10 用例覆盖分组/指纹/顺序/边界）。
 */
class LogFrequencyGrouperTest {

    private fun entry(sql: String, ts: Long = 0L) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
    )

    @Test
    fun `无高频全部低于阈值返回 count 1`() {
        val entries = listOf(entry("SELECT 1"), entry("SELECT 2"), entry("SELECT 3"))
        val grouped = LogFrequencyGrouper.group(entries, threshold = 3)
        assertEquals(3, grouped.size)
        grouped.forEach { assertEquals(1, it.count) }
    }

    @Test
    fun `有高频模板出现次数等于阈值合并为一条`() {
        // 同模板（完全相同的 SQL）出现 3 次 = 阈值
        val entries = listOf(
            entry("SELECT * FROM t WHERE id = 1"),
            entry("SELECT * FROM t WHERE id = 1"),
            entry("SELECT * FROM t WHERE id = 1"),
            entry("INSERT INTO t VALUES (1)"),
        )
        val grouped = LogFrequencyGrouper.group(entries, threshold = 3)
        // 高频 SELECT 合并 1 条（count=3）+ INSERT 透传（count=1）= 2 个 GroupedEntry
        assertEquals(2, grouped.size)
        val selectGroup = grouped[0]
        assertEquals(3, selectGroup.count)
        assertEquals(3, selectGroup.entries.size)
        assertEquals(1, grouped[1].count)
    }

    @Test
    fun `多个高频模板各自独立合并`() {
        val entries = listOf(
            entry("SELECT * FROM a WHERE id = 1"),
            entry("SELECT * FROM a WHERE id = 1"),
            entry("SELECT * FROM a WHERE id = 1"),
            entry("UPDATE b SET x = 1"),
            entry("UPDATE b SET x = 1"),
            entry("UPDATE b SET x = 1"),
        )
        val grouped = LogFrequencyGrouper.group(entries, threshold = 3)
        assertEquals(2, grouped.size)
        assertEquals(3, grouped[0].count)
        assertEquals(3, grouped[1].count)
    }

    @Test
    fun `指纹归一化空白差异视为相同模板`() {
        val sql1 = "SELECT * FROM t"
        val sql2 = "SELECT  *  FROM  t"
        assertEquals(LogFrequencyGrouper.fingerprint(sql1), LogFrequencyGrouper.fingerprint(sql2))
        val entries = listOf(entry(sql1), entry(sql2), entry("SELECT * FROM t"))
        val grouped = LogFrequencyGrouper.group(entries, threshold = 3)
        assertEquals(1, grouped.size)
        assertEquals(3, grouped[0].count)
    }

    @Test
    fun `指纹归一化大小写视为相同模板`() {
        val sql1 = "select * from t"
        val sql2 = "SELECT * FROM T"
        assertEquals(LogFrequencyGrouper.fingerprint(sql1), LogFrequencyGrouper.fingerprint(sql2))
        val entries = listOf(entry(sql1), entry(sql2), entry("Select * From t"))
        val grouped = LogFrequencyGrouper.group(entries, threshold = 3)
        assertEquals(1, grouped.size)
        assertEquals(3, grouped[0].count)
    }

    @Test
    fun `顺序保持合并后按首次出现排序`() {
        val entries = listOf(
            entry("INSERT INTO t VALUES (1)", ts = 1L),
            entry("SELECT * FROM a WHERE id = 1", ts = 2L),
            entry("INSERT INTO t VALUES (1)", ts = 3L),
            entry("SELECT * FROM a WHERE id = 1", ts = 4L),
            entry("INSERT INTO t VALUES (1)", ts = 5L),
            entry("SELECT * FROM a WHERE id = 1", ts = 6L),
        )
        val grouped = LogFrequencyGrouper.group(entries, threshold = 3)
        assertEquals(2, grouped.size)
        // 首次出现顺序：INSERT（ts=1）在前，SELECT（ts=2）在后
        assertEquals("INSERT INTO t VALUES (1)", grouped[0].template.restoredSql)
        assertEquals("SELECT * FROM a WHERE id = 1", grouped[1].template.restoredSql)
    }

    @Test
    fun `expanded 默认 false`() {
        val entries = listOf(entry("SELECT 1"), entry("SELECT 1"), entry("SELECT 1"))
        val grouped = LogFrequencyGrouper.group(entries, threshold = 3)
        assertEquals(1, grouped.size)
        assertFalse(grouped[0].expanded)
    }

    @Test
    fun `threshold 等于 2 时出现 2 次即合并`() {
        val entries = listOf(entry("SELECT 1"), entry("SELECT 1"))
        val grouped = LogFrequencyGrouper.group(entries, threshold = 2)
        assertEquals(1, grouped.size)
        assertEquals(2, grouped[0].count)
    }

    @Test
    fun `threshold 等于 1 时全部合并边界`() {
        val entries = listOf(entry("SELECT 1"), entry("SELECT 2"))
        val grouped = LogFrequencyGrouper.group(entries, threshold = 1)
        // 每条 SQL 指纹不同，但每条 count=1 >= 1，各自成为 GroupedEntry
        // 注意：相同指纹才会合并，不同指纹各独立
        assertEquals(2, grouped.size)
    }

    @Test
    fun `空列表返回空列表`() {
        val grouped = LogFrequencyGrouper.group(emptyList(), threshold = 3)
        assertTrue(grouped.isEmpty())
    }
}
