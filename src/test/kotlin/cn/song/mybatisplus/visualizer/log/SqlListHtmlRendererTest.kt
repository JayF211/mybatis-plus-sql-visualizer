package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表条目 SQL 着色单元测试（T-M31.13）。
 */
class SqlListHtmlRendererTest {

    @Test
    fun `关键字着色`() {
        val html = SqlListHtmlRenderer.render("SELECT 1")
        assertTrue(html.contains("<span style='color:#C678DD'>SELECT</span>"))
        assertTrue(html.contains("1"))
    }

    @Test
    fun `字符串与数字着色`() {
        val html = SqlListHtmlRenderer.render("WHERE name = 'abc' AND id = 42")
        assertTrue(html.contains("<span style='color:#98C379'>'abc'</span>"))
        assertTrue(html.contains("<span style='color:#D19A66'>42</span>"))
    }

    @Test
    fun `HTML 特殊字符转义`() {
        val html = SqlListHtmlRenderer.render("SELECT 'a<b&c>'")
        assertTrue(!html.contains("<b&"))
        assertTrue(html.contains("&lt;b&amp;c&gt;"))
    }

    @Test
    fun `空串渲染为空`() {
        assertEquals("", SqlListHtmlRenderer.render(""))
    }

    @Test
    fun `含注释的 SQL`() {
        val html = SqlListHtmlRenderer.render("SELECT 1 -- note")
        assertTrue(html.contains("<span style='color:#7F848E'>-- note</span>"))
    }
}
