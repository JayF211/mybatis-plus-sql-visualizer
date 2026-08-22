package cn.song.mybatisplus.visualizer.share.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTML 报告格式化器单元测试（F-8 Phase B）。
 */
class HtmlReportFormatterTest {

    @Test
    fun `format_生成完整HTML结构`() {
        val sql = "SELECT id FROM user"
        val html = HtmlReportFormatter.format(
            sql = sql,
            tableName = "user",
            dialect = "MySQL",
        )
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("</html>"))
        // SQL 被 span 包裹，验证去除标签后包含原文
        val stripped = html.replace(Regex("<[^>]*>"), "")
        assertTrue(stripped.contains(sql))
        assertTrue(stripped.contains("MySQL"))
        assertTrue(stripped.contains("user"))
    }

    @Test
    fun `format_关键字高亮标签存在`() {
        val html = HtmlReportFormatter.format(
            sql = "SELECT id FROM user WHERE name = 'tom'",
            tableName = "user",
            dialect = "MySQL",
        )
        // 关键字 SELECT / FROM / WHERE 应有 kw 类
        assertTrue(html.contains("class=\"kw\""))
        // 字符串 'tom' 应有 str 类
        assertTrue(html.contains("class=\"str\""))
        // 表名 user 应有 tbl 类
        assertTrue(html.contains("class=\"tbl\""))
    }

    @Test
    fun `format_参数对照表_有参数时生成`() {
        val html = HtmlReportFormatter.format(
            sql = "SELECT id FROM user WHERE name = ? AND age = ?",
            sources = listOf("name", "age"),
            paramValues = mapOf("name" to "tom", "age" to "25"),
            tableName = "user",
        )
        assertTrue(html.contains("Parameters"))
        assertTrue(html.contains("name"))
        assertTrue(html.contains("tom"))
        assertTrue(html.contains("age"))
        assertTrue(html.contains("25"))
    }

    @Test
    fun `format_参数对照表_无参数时省略`() {
        val html = HtmlReportFormatter.format(
            sql = "SELECT id FROM user",
            sources = emptyList(),
            paramValues = emptyMap(),
            tableName = "user",
        )
        assertFalse(html.contains("Parameters"))
    }

    @Test
    fun `format_HTML特殊字符_正确转义`() {
        val html = HtmlReportFormatter.format(
            sql = "SELECT id FROM user WHERE name = '<script>'",
            tableName = "user",
        )
        // < 应转义为 &lt;
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `format_null表名和dialect_省略对应元信息`() {
        val sql = "SELECT id FROM user"
        val html = HtmlReportFormatter.format(
            sql = sql,
            tableName = null,
            dialect = null,
        )
        assertTrue(html.contains("<!DOCTYPE html>"))
        val stripped = html.replace(Regex("<[^>]*>"), "")
        assertTrue(stripped.contains(sql))
    }

    @Test
    fun `format_注释高亮_cmt类存在`() {
        val html = HtmlReportFormatter.format(
            sql = "SELECT id FROM user -- get all",
            tableName = "user",
        )
        assertTrue(html.contains("class=\"cmt\""))
    }
}
