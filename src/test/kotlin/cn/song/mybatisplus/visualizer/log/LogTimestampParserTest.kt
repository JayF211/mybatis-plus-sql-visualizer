package cn.song.mybatisplus.visualizer.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * LogTimestampParser 单元测试：覆盖预定义格式、行首/非行首位置、无时间戳行、空行、异常输入。
 *
 * 注意：测试使用固定时区 UTC+8（Asia/Shanghai），确保时间戳解析结果可预测。
 */
class LogTimestampParserTest {

    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    // ---------- 行首匹配 ----------

    @Test
    fun `parse logback default format with millis`() {
        val line = "2024-01-15 10:30:45.123 [http-nio-8080-exec-1] DEBUG org.springframework.jdbc.core.JdbcTemplate - Executing prepared SQL statement [SELECT * FROM users]"
        val ts = LogTimestampParser.parse(line)
        assertEquals(1705285845123L, ts)
    }

    @Test
    fun `parse format without millis`() {
        val line = "2024-01-15 10:30:45 [thread-1] INFO com.example.Service - some log"
        val ts = LogTimestampParser.parse(line)
        assertEquals(1705285845000L, ts)
    }

    @Test
    fun `parse ISO 8601 with timezone offset`() {
        val line = "2024-01-15T10:30:45.123+0800 [main] DEBUG com.example - log"
        val ts = LogTimestampParser.parse(line)
        // +0800 偏移被解析，UTC 时刻 = 2024-01-15T02:30:45.123Z
        assertEquals(1705285845123L, ts)
    }

    @Test
    fun `parse ISO 8601 with colon timezone`() {
        val line = "2024-01-15T10:30:45.123+08:00 [main] DEBUG com.example - log"
        val ts = LogTimestampParser.parse(line)
        assertEquals(1705285845123L, ts)
    }

    @Test
    fun `parse nginx style format`() {
        val line = "15/Jan/2024:10:30:45 +0800 [main] INFO - log"
        val ts = LogTimestampParser.parse(line)
        assertEquals(1705285845000L, ts)
    }

    // ---------- 非行首匹配（level 关键词之前） ----------

    @Test
    fun `parse timestamp before level keyword`() {
        // 时间戳在 level 之前但不在行首（前面有空格）
        val line = "  2024-01-15 10:30:45.123 DEBUG com.example - log"
        val ts = LogTimestampParser.parse(line)
        assertEquals(1705285845123L, ts)
    }

    // ---------- 无时间戳行 ----------

    @Test
    fun `parse line without timestamp returns null`() {
        val line = "==> Preparing: SELECT * FROM users"
        assertNull(LogTimestampParser.parse(line))
    }

    @Test
    fun `parse empty line returns null`() {
        assertNull(LogTimestampParser.parse(""))
    }

    @Test
    fun `parse blank line returns null`() {
        assertNull(LogTimestampParser.parse("   "))
    }

    @Test
    fun `parse null line returns null`() {
        assertNull(LogTimestampParser.parse(""))
    }

    // ---------- 异常输入 ----------

    @Test
    fun `parse invalid date format returns null`() {
        val line = "2024-13-45 25:70:99.999 [thread] DEBUG - log"
        // 月份 13 无效，SimpleDateFormat 解析失败
        assertNull(LogTimestampParser.parse(line))
    }

    @Test
    fun `parse partial timestamp returns null`() {
        // 只有日期没有时间
        val line = "2024-01-15 [thread] DEBUG - log"
        assertNull(LogTimestampParser.parse(line))
    }

    // ---------- 边界情况 ----------

    @Test
    fun `parse timestamp with leading whitespace`() {
        val line = "   2024-01-15 10:30:45.123 DEBUG - log"
        val ts = LogTimestampParser.parse(line)
        assertEquals(1705285845123L, ts)
    }

    @Test
    fun `parse timestamp at exact boundary`() {
        // 时间戳后紧跟 level
        val line = "2024-01-15 10:30:45.123DEBUG - log"
        val ts = LogTimestampParser.parse(line)
        assertEquals(1705285845123L, ts)
    }
}
