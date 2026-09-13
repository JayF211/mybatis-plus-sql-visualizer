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

    // ---------- T-ListMode：显示模式变换与多行拆分（承接 SqlListHtmlRendererTest 三模式映射语义） ----------

    @Test
    fun test模式变换映射_单行原文() {
        val sql = "SELECT a FROM t WHERE b = 1"
        assertEquals("SINGLE_LINE 应原文直出", sql, prepareSqlForList(sql, SqlListDisplayMode.SINGLE_LINE))
    }

    @Test
    fun test模式变换映射_关键字换行与格式化() {
        val sql = "SELECT a, b FROM t WHERE x = 1 AND y = 2"
        val kw = prepareSqlForList(sql, SqlListDisplayMode.KEYWORD_WRAP)
        val fm = prepareSqlForList(sql, SqlListDisplayMode.FORMATTED)
        assertTrue("关键字换行应含换行", kw.contains('\n'))
        assertTrue("格式化应含换行", fm.contains('\n'))
        assertTrue("AND 同行（关键字换行）与 AND 独立行（格式化）应产出不同文本", kw != fm)
    }

    @Test
    fun test模式变换映射_空串() {
        assertEquals("", prepareSqlForList("", SqlListDisplayMode.SINGLE_LINE))
        assertEquals("", prepareSqlForList("", SqlListDisplayMode.KEYWORD_WRAP))
        assertEquals("", prepareSqlForList("", SqlListDisplayMode.FORMATTED))
    }

    @Test
    fun test多行片段按行拆分_属性继承() {
        val lines = SqlListFragmentRenderer.renderLineFragments(
            "SELECT a FROM t WHERE b = 1",
            SqlListDisplayMode.KEYWORD_WRAP,
            attrOf,
        )
        assertTrue("关键字换行应拆出多行（实际 ${lines.size} 行）", lines.size >= 3)
        assertTrue("首行含 SELECT 片段", lines[0].any { it.text.contains("SELECT") })
        assertTrue("FROM 独立成行", lines.any { line -> line.firstOrNull()?.text == "FROM" })
        assertTrue("WHERE 独立成行", lines.any { line -> line.firstOrNull()?.text == "WHERE" })
        val fromLine = lines.first { line -> line.firstOrNull()?.text == "FROM" }
        assertEquals(
            "FROM 关键字应着主题色（属性随行拆分继承）",
            Color(0xC678DD),
            fromLine.first().attributes.fgColor,
        )
    }

    @Test
    fun test多行片段按行拆分_空串单空行() {
        val lines = SqlListFragmentRenderer.renderLineFragments("", SqlListDisplayMode.FORMATTED, attrOf)
        assertEquals("空 SQL 变换后仍为空——拆分恒单空行", 1, lines.size)
        assertTrue(lines[0].isEmpty())
    }
}
