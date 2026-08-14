package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * include 展开测试（T-M24-03）：同文件 refid 内联展开、property 静态替换（只作用于 `${}`）、
 * 嵌套 include 与 property 继承/覆盖、refid 动态 `${}`、循环保护、跨命名空间报错降级。
 */
class XmlIncludeExpanderTest {

    private fun renderSql(mapper: String): RenderedXmlSql {
        val parsed = XmlMapperParser().parse(mapper)
        val statement = parsed.statements.single()
        return XmlPreviewModel.from(statement, parsed.errors).renderSql()
    }

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun `property静态替换_只作用于dollar`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="cols">${'$'}{alias}.id, ${'$'}{alias}.name</sql>
              <select id="s1">SELECT <include refid="cols"><property name="alias" value="t1"/></include> FROM t</select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT t1.id, t1.name FROM t", compact(result.sql))
    }

    @Test
    fun `property不替换hash参数`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="w">a = #{v} AND ${'$'}{alias} = 1</sql>
              <select id="s1">SELECT * FROM t WHERE <include refid="w"><property name="alias" value="t1"/></include></select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT * FROM t WHERE a = ? AND t1 = 1", compact(result.sql))
        assertEquals("v", result.mappings.single().property)
    }

    @Test
    fun `动态refid_按property解析`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="cols">id, name</sql>
              <select id="s1">SELECT <include refid="${'$'}{c}"><property name="c" value="cols"/></include> FROM t</select>
            </mapper>
        """.trimIndent())
        // refid 中的 ${c} 由 property 静态替换后查表展开
        assertEquals("SELECT id, name FROM t", compact(result.sql))
    }

    @Test
    fun `property继承与覆盖`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="inner">${'$'}{alias}.id</sql>
              <sql id="outer"><include refid="inner"><property name="alias" value="t2"/></include></sql>
              <select id="s1">SELECT <include refid="outer"><property name="alias" value="t1"/></include> FROM t</select>
            </mapper>
        """.trimIndent())
        // 内层 include 的 property 覆盖外层同名（对齐 MyBatis：后定义覆盖）
        assertEquals("SELECT t2.id FROM t", compact(result.sql))
    }

    @Test
    fun `include循环_深度保护报错`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <sql id="a"><include refid="a"/>x</sql>
              <select id="s1">SELECT <include refid="a"/> FROM t</select>
            </mapper>
        """.trimIndent())
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        assertTrue(m.errors.any { it.contains("too deep") || it.contains("cycle") })
        // 循环展开不残留 include（渲染不报 Unknown element）
        assertEquals("SELECT x FROM t", compact(m.renderSql().sql))
    }

    @Test
    fun `跨命名空间refid_报错并移除`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t <include refid="other.SqlId"/></select>
            </mapper>
        """.trimIndent())
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        assertTrue(m.errors.any { it.contains("other.SqlId") })
        assertEquals("SELECT * FROM t", compact(m.renderSql().sql))
    }

    @Test
    fun `同一片段多语句复用_互不污染`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <sql id="cols">id, name</sql>
              <select id="a">SELECT <include refid="cols"/> FROM t1</select>
              <select id="b">SELECT <include refid="cols"/> FROM t2</select>
            </mapper>
        """.trimIndent())
        val a = XmlPreviewModel.from(parsed.statements.first { it.id == "a" }, parsed.errors).renderSql()
        val b = XmlPreviewModel.from(parsed.statements.first { it.id == "b" }, parsed.errors).renderSql()
        assertEquals("SELECT id, name FROM t1", compact(a.sql))
        assertEquals("SELECT id, name FROM t2", compact(b.sql))
    }

    @Test
    fun `include片段中的动态标签正常渲染`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="cond">
                <if test="status != null">AND status = #{status}</if>
              </sql>
              <select id="s1">SELECT * FROM t WHERE 1 = 1 <include refid="cond"/></select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT * FROM t WHERE 1 = 1 AND status = ?", compact(result.sql))
        assertEquals("status", result.mappings.single().property)
    }
}
