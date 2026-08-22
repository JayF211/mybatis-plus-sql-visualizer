package cn.song.mybatisplus.visualizer.share.format

import cn.song.mybatisplus.visualizer.sql.ParamHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可执行 SQL 格式化器单元测试（F-8 Phase C）。
 */
class ExecutableSqlFormatterTest {

    @Test
    fun `format_数值参数_不加引号`() {
        val sql = "SELECT id FROM tbl WHERE id = ? AND age > ?"
        val result = ExecutableSqlFormatter.format(
            sql = sql,
            sources = listOf("id", "age"),
            paramValues = mapOf("id" to "1001", "age" to "18"),
            hints = listOf(ParamHint(quote = false), ParamHint(quote = false)),
        )
        assertEquals("SELECT id FROM tbl WHERE id = 1001 AND age > 18", result)
    }

    @Test
    fun `format_字符串参数_加引号`() {
        val sql = "SELECT id FROM tbl WHERE name = ? AND city = ?"
        val result = ExecutableSqlFormatter.format(
            sql = sql,
            sources = listOf("name", "city"),
            paramValues = mapOf("name" to "John", "city" to "Beijing"),
            hints = listOf(ParamHint(quote = true), ParamHint(quote = true)),
        )
        assertEquals("SELECT id FROM tbl WHERE name = 'John' AND city = 'Beijing'", result)
    }

    @Test
    fun `format_空值保留占位符`() {
        val sql = "SELECT id FROM tbl WHERE id = ? AND name = ?"
        val result = ExecutableSqlFormatter.format(
            sql = sql,
            sources = listOf("id", "name"),
            paramValues = mapOf("id" to "1001"),
            hints = listOf(ParamHint(quote = false), ParamHint(quote = true)),
        )
        assertEquals("SELECT id FROM tbl WHERE id = 1001 AND name = ?", result)
    }

    @Test
    fun `format_单引号转义防注入`() {
        val sql = "SELECT id FROM tbl WHERE name = ?"
        val result = ExecutableSqlFormatter.format(
            sql = sql,
            sources = listOf("name"),
            paramValues = mapOf("name" to "O'Brien"),
            hints = listOf(ParamHint(quote = true)),
        )
        assertEquals("SELECT id FROM tbl WHERE name = 'O''Brien'", result)
    }

    @Test
    fun `format_无参数原样返回`() {
        val sql = "SELECT id FROM tbl"
        val result = ExecutableSqlFormatter.format(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
        )
        assertEquals("SELECT id FROM tbl", result)
    }

    @Test
    fun `formatAsMrComment_生成评论格式_含前缀和来源`() {
        val sql = "SELECT id FROM user WHERE name = 'tom'"
        val comment = ExecutableSqlFormatter.formatAsMrComment(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            source = "UserService.java:42",
            prefix = "[SQL Review]",
        )
        assertTrue(comment.contains("[SQL Review]"))
        assertTrue(comment.contains("UserService.java:42"))
        assertTrue(comment.contains("```sql"))
        assertTrue(comment.contains(sql))
        assertTrue(comment.contains("```"))
    }

    @Test
    fun `formatAsMrComment_无来源_省略来源行`() {
        val sql = "SELECT id FROM user WHERE name = 'tom'"
        val comment = ExecutableSqlFormatter.formatAsMrComment(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            source = null,
        )
        assertTrue(comment.startsWith("[SQL Review]"))
        assertTrue(!comment.contains("[SQL Review] null"))
    }

    @Test
    fun `formatAsMrComment_自定义前缀`() {
        val sql = "SELECT id FROM tbl"
        val comment = ExecutableSqlFormatter.formatAsMrComment(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            prefix = "[DB Review]",
        )
        assertTrue(comment.startsWith("[DB Review]"))
    }
}
