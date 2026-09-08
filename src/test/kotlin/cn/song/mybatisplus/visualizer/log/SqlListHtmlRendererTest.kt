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
        // T-ListMode：空格转 &nbsp;（BasicHTML 折叠普通空格，缩进保真）——注释内空格同步替换
        assertTrue(html.contains("<span style='color:#7F848E'>--&nbsp;note</span>"))
    }

    // ---------- T-ListMode：SQL 三种显示模式 ----------

    @Test
    fun `prepareSqlForList 单行模式返回原文`() {
        val sql = "SELECT * FROM t WHERE id = 1"
        assertEquals(sql, prepareSqlForList(sql, SqlListDisplayMode.SINGLE_LINE))
    }

    @Test
    fun `prepareSqlForList 关键字换行模式子句分行`() {
        val out = prepareSqlForList("SELECT * FROM t WHERE id = 1", SqlListDisplayMode.KEYWORD_WRAP)
        assertTrue("应产生多行", out.contains('\n'))
        assertTrue("FROM 应独立成行", out.contains("\nFROM"))
        assertTrue("WHERE 应独立成行", out.contains("\nWHERE"))
    }

    @Test
    fun `prepareSqlForList 格式化模式多行`() {
        val out = prepareSqlForList("SELECT * FROM t WHERE a = 1 AND b = 2", SqlListDisplayMode.FORMATTED)
        assertTrue("应产生多行", out.contains('\n'))
        assertTrue("AND 应缩进（缩进空格保留在文本中）", out.contains("\n    AND"))
    }

    @Test
    fun `prepareSqlForList 空串与短SQL原样不崩`() {
        assertEquals("", prepareSqlForList("", SqlListDisplayMode.KEYWORD_WRAP))
        assertEquals("", prepareSqlForList("", SqlListDisplayMode.FORMATTED))
        assertEquals("select 1", prepareSqlForList("select 1", SqlListDisplayMode.SINGLE_LINE))
    }

    @Test
    fun `空格转义为 nbsp 保缩进`() {
        // BasicHTML 折叠连续普通空格——&nbsp; 是缩进保真的实现手段
        val html = SqlListHtmlRenderer.render("a  b", colorOf)
        assertTrue(html.contains("&nbsp;"))
    }
}
