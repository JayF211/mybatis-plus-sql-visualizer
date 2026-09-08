package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes
import com.intellij.psi.tree.IElementType
import com.intellij.ui.SimpleTextAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

/**
 * 列表条目 SQL 着色片段管线单元测试（T-LogListFreeze-04，承接 SqlListHtmlRendererTest 语义：
 * 关键字/字符串/数字/注释着色与空串边界——载体由 HTML span 改为 SqlTextFragment）。
 */
class SqlListFragmentRendererTest {

    private val attrOf: (IElementType) -> SimpleTextAttributes? = {
        when (it) {
            SqlTokenTypes.KEYWORD -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0xC678DD))
            SqlTokenTypes.STRING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0x98C379))
            SqlTokenTypes.NUMBER -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0xD19A66))
            SqlTokenTypes.COMMENT -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0x7F848E))
            else -> null
        }
    }

    private fun fragmentOf(sql: String, text: String) =
        SqlListFragmentRenderer.renderFragments(sql, attrOf).firstOrNull { it.text.contains(text) }

    @Test
    fun test关键字着色() {
        val f = fragmentOf("SELECT 1", "SELECT")
        assertEquals("SELECT 关键字应着主题色", Color(0xC678DD), f?.attributes?.fgColor)
        assertTrue("数字 token 应保留", SqlListFragmentRenderer.renderFragments("SELECT 1", attrOf).any { it.text == "1" })
    }

    @Test
    fun test字符串与数字着色() {
        val s = fragmentOf("WHERE name = 'abc' AND id = 42", "'abc'")
        assertEquals("字符串应着绿色", Color(0x98C379), s?.attributes?.fgColor)
        val n = fragmentOf("WHERE name = 'abc' AND id = 42", "42")
        assertEquals("数字应着橙色", Color(0xD19A66), n?.attributes?.fgColor)
    }

    @Test
    fun test特殊字符原文保留_无HTML转义面() {
        // 片段通道不做 HTML 转义——文本原样保留即安全（无 BasicHTML 解析面）
        val f = fragmentOf("SELECT 'a<b&c>'", "a<b&c>")
        assertEquals("字符串内容应原文保留", "'a<b&c>'", f?.text)
    }

    @Test
    fun test无配色类型走REGULAR属性() {
        val f = fragmentOf("WHERE name = 'abc'", "name")
        assertSame("IDENTIFIER 未配色应透传 REGULAR_ATTRIBUTES（走组件前景色）", SimpleTextAttributes.REGULAR_ATTRIBUTES, f?.attributes)
    }

    @Test
    fun test空串渲染为空() {
        assertTrue(SqlListFragmentRenderer.renderFragments("", attrOf).isEmpty())
    }

    @Test
    fun test含注释的SQL() {
        val f = fragmentOf("SELECT 1 -- note", "-- note")
        assertEquals("注释应着灰色", Color(0x7F848E), f?.attributes?.fgColor)
    }
}
