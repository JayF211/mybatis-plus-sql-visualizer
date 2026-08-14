package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 脚本构建器测试（T-M24-01）：DOM → SqlNode 树——
 * 标签分派（if/where/set/trim/foreach/choose/bind）、CDATA 按文本处理、
 * 未知元素与 choose 多 otherwise 报错（对齐 MyBatis parseDynamicTags）。
 */
class XmlScriptBuilderTest {

    private fun element(xml: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream("<root>$xml</root>".toByteArray()))
            .documentElement

    private fun parse(xml: String): MixedSqlNode =
        XmlScriptBuilder().parse(element(xml)) as MixedSqlNode

    @Test
    fun `文本与if混合解析`() {
        val node = parse("""SELECT * FROM t<if test="a != null"> WHERE a = 1</if> tail""")
        assertEquals(3, node.children.size)
        assertTrue(node.children[0] is StaticTextSqlNode)
        val ifNode = node.children[1] as IfSqlNode
        assertEquals("a != null", ifNode.test)
        assertTrue(node.children[2] is StaticTextSqlNode)
    }

    @Test
    fun `含dollar的文本为动态节点`() {
        val node = parse("""SELECT ${'$'}{col} FROM t""")
        assertTrue(node.children[0] is TextSqlNode)
        assertTrue(TextSqlNode.isDynamic("${'$'}{col}"))
    }

    @Test
    fun `CDATA按纯文本处理`() {
        val node = parse("""<![CDATA[ WHERE age < 18 ]]>""")
        assertEquals(1, node.children.size)
        val text = (node.children[0] as StaticTextSqlNode).text
        assertTrue(text.contains("WHERE age < 18"))
    }

    @Test
    fun `各动态标签解析为对应节点`() {
        val node = parse(
            "<where>WHERE 1=1</where>" +
                "<set>SET a=1</set>" +
                "<trim prefix=\"P\" suffixOverrides=\",\">x,</trim>" +
                "<foreach collection=\"list\" item=\"x\" separator=\",\">#{x}</foreach>" +
                "<bind name=\"n\" value=\"'a' + x\"/>" +
                "<choose><when test=\"t == 1\">A</when><otherwise>B</otherwise></choose>",
        )
        assertEquals(6, node.children.size)
        assertTrue(node.children[0] is WhereSqlNode)
        assertTrue(node.children[1] is SetSqlNode)
        assertTrue(node.children[2] is TrimSqlNode)
        val foreach = node.children[3] as ForeachSqlNode
        assertEquals("list", foreach.collectionExpression)
        assertTrue(node.children[4] is VarDeclSqlNode)
        val choose = node.children[5] as ChooseSqlNode
        assertEquals(1, choose.whenNodes.size)
        assertTrue(choose.defaultNode != null)
    }

    @Test
    fun `choose缺otherwise时default为null`() {
        val node = parse("""<choose><when test="t == 1">A</when></choose>""")
        val choose = node.children[0] as ChooseSqlNode
        assertTrue(choose.defaultNode == null)
    }

    @Test
    fun `未知元素报错`() {
        assertThrows(XmlScriptException::class.java) { parse("""<else>oops</else>""") }
    }

    @Test
    fun `choose多otherwise报错`() {
        assertThrows(XmlScriptException::class.java) {
            parse("""<choose><when test="a">A</when><otherwise>B</otherwise><otherwise>C</otherwise></choose>""")
        }
    }

    @Test
    fun `foreach属性读取`() {
        val node = parse("""<foreach collection="ids" item="id" index="i" open="(" close=")" separator="," nullable="true">#{id}</foreach>""")
        val foreach = node.children[0] as ForeachSqlNode
        assertEquals("ids", foreach.collectionExpression)
        assertEquals("id", foreach.item)
        assertEquals("i", foreach.index)
        assertEquals("(", foreach.open)
        assertEquals(")", foreach.close)
        assertEquals(",", foreach.separator)
    }
}
