package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 耗时解析器单元测试（P-06，6 用例覆盖 p6spy/druid 格式 + 边界）。
 */
class LogDurationParserTest {

    private fun entry(rawSql: String, restoredSql: String = rawSql.substringAfter("|statement|").ifEmpty { rawSql }) =
        LogSqlEntry(
            rawSql = rawSql,
            restoredSql = restoredSql,
            params = emptyList(),
            timestamp = 0L,
        )

    @Test
    fun `p6spy 格式解析耗时`() {
        // p6spy: "1723456789012|123|statement|select ..."
        val raw = "1723456789012|123|statement|select * from user"
        val e = entry(raw, "select * from user")
        assertEquals(123L, LogDurationParser.parse(e))
    }

    @Test
    fun `druid 格式解析耗时`() {
        // druid: 含 executeMillis:456
        val raw = """WallStatEx-{...,"executeMillis":456,...}"""
        val e = entry(raw, "SELECT * FROM t")
        assertEquals(456L, LogDurationParser.parse(e))
    }

    @Test
    fun `无耗时信息返回 null`() {
        val raw = "SELECT * FROM user WHERE id = 1"
        val e = entry(raw, raw)
        assertNull(LogDurationParser.parse(e))
    }

    @Test
    fun `空串返回 null`() {
        val e = entry("", "")
        assertNull(LogDurationParser.parse(e))
    }

    @Test
    fun `hasDurationInfo true 当 p6spy 格式`() {
        val raw = "1723456789012|456|statement|select 1"
        val e = entry(raw, "select 1")
        assertTrue(LogDurationParser.hasDurationInfo(e))
    }

    @Test
    fun `hasDurationInfo false 当普通 SQL`() {
        val raw = "SELECT * FROM t"
        val e = entry(raw, raw)
        assertFalse(LogDurationParser.hasDurationInfo(e))
    }
}
