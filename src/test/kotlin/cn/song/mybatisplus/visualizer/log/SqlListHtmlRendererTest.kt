package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表条目 SQL 着色单元测试（T-M31.13）。
 */
class SqlListHtmlRendererTest {

    private val colorOf: (com.intellij.psi.tree.IElementType) -> String? = {
        when (it) {
            cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes.KEYWORD -> "#C678DD"
            cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes.STRING -> "#98C379"
            cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes.NUMBER -> "#D19A66"
            cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes.COMMENT -> "#7F848E"
            else -> null
        }
    }

    @Test
    fun `关键字着色`() {
        val html = SqlListHtmlRenderer.render("SELECT 1", colorOf)
        assertTrue(html.contains("<span style='color:#C678DD'>SELECT</span>"))
        assertTrue(html.contains("1"))
    }

    @Test
    fun `字符串与数字着色`() {
        val html = SqlListHtmlRenderer.render("WHERE name = 'abc' AND id = 42", colorOf)
        assertTrue(html.contains("<span style='color:#98C379'>'abc'</span>"))
        assertTrue(html.contains("<span style='color:#D19A66'>42</span>"))
    }

    @Test
    fun `HTML 特殊字符转义`() {
        val html = SqlListHtmlRenderer.render("SELECT 'a<b&c>'", colorOf)
        assertTrue(!html.contains("<b&"))
        assertTrue(html.contains("&lt;b&amp;c&gt;"))
    }

    @Test
    fun `空串渲染为空`() {
        assertEquals("", SqlListHtmlRenderer.render("", colorOf))
    }

    @Test
    fun `含注释的 SQL`() {
        val html = SqlListHtmlRenderer.render("SELECT 1 -- note", colorOf)
        assertTrue(html.contains("<span style='color:#7F848E'>-- note</span>"))
    }
}
