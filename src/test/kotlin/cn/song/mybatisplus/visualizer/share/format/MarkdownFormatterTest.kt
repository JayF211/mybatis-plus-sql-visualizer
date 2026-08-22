package cn.song.mybatisplus.visualizer.share.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 格式化器单元测试（F-8 Phase A）。
 */
class MarkdownFormatterTest {

    @Test
    fun `format_普通SQL_生成代码块加元信息`() {
        val sql = "SELECT id, name FROM user WHERE name = 'zhangsan'"
        val md = MarkdownFormatter.format(
            sql = sql,
            tableName = "user",
            dialect = "MySQL",
            source = "UserService.java:42",
        )
        assertTrue(md.contains("```sql"))
        assertTrue(md.contains(sql))
        assertTrue(md.contains("```"))
        assertTrue(md.contains("user"))
        assertTrue(md.contains("MySQL"))
        assertTrue(md.contains("UserService.java:42"))
    }

    @Test
    fun `format_无参数SQL_省略参数对照`() {
        val sql = "SELECT id FROM user"
        val md = MarkdownFormatter.format(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            tableName = "user",
            dialect = "MySQL",
        )
        assertTrue(md.contains("```sql"))
        assertTrue(md.contains(sql))
        // 无参数时不应出现参数对照表
        assertTrue(!md.contains("-- 参数对照"))
    }

    @Test
    fun `format_有参数SQL_包含参数对照`() {
        val sql = "SELECT id FROM user WHERE name = ? AND age = ?"
        val md = MarkdownFormatter.format(
            sql = sql,
            sources = listOf("name", "age"),
            paramValues = mapOf("name" to "zhangsan", "age" to "25"),
            tableName = "user",
            dialect = "MySQL",
        )
        assertTrue(md.contains("```sql"))
        // 参数对照
        assertTrue(md.contains("zhangsan"))
        assertTrue(md.contains("25"))
    }

    @Test
    fun `format_SQL含特殊字符_原样保留`() {
        val sql = "SELECT id FROM user WHERE name = 'O''Brien' -- comment"
        val md = MarkdownFormatter.format(
            sql = sql,
            tableName = "user",
            dialect = "MySQL",
        )
        assertTrue(md.contains("O''Brien"))
    }

    @Test
    fun `format_空source和dialect_省略对应元信息`() {
        val sql = "SELECT id FROM user"
        val md = MarkdownFormatter.format(
            sql = sql,
            tableName = "user",
            dialect = null,
            source = null,
        )
        assertTrue(md.contains("```sql"))
        assertTrue(md.contains(sql))
    }

    @Test
    fun `formatAsTable_生成Markdown表格`() {
        val sql = "SELECT id FROM user WHERE name = ?"
        val md = MarkdownFormatter.formatAsTable(
            sql = sql,
            sources = listOf("name"),
            paramValues = mapOf("name" to "zhangsan"),
            tableName = "user",
            dialect = "MySQL",
            source = "UserService.java:42",
        )
        // 表格应有 | 分隔符
        assertTrue(md.contains("|"))
        assertTrue(md.contains("---"))
        assertTrue(md.contains("SQL"))
        assertTrue(md.contains("参数"))
    }
}
