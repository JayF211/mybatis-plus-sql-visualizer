package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class XmlRenderTraceTest {
    private fun node(body: String): SqlNode = XmlMapperParser().parse(
        "<mapper namespace=\"fixture\"><select id=\"find\">$body</select></mapper>",
    ).statements.single().node

    @Test
    fun `trace is opt in and does not change rendered SQL or parameter mapping`() {
        val node = node("SELECT * FROM t <where><if test=\"id != null\">AND id = #{id}</if></where>")
        val renderer = XmlSqlRenderer()
        val original = renderer.render(node, mapOf("id" to 1))
        val traced = renderer.render(node, mapOf("id" to 1), traceEnabled = true)
        assertEquals(original.sql, traced.sql)
        assertEquals(original.mappings, traced.mappings)
        assertEquals(original.conditionResults, traced.conditionResults)
        assertNull(original.renderTrace)
        assertNotNull(traced.renderTrace)
        assertEquals("EXPANDED_STATEMENT_NODE_PATH", traced.renderTrace!!.locationKind)
    }

    @Test
    fun `same expression at different XML nodes is not collapsed`() {
        val node = node("SELECT * FROM t <if test=\"x != null\">WHERE x = #{x}</if><if test=\"x != null\">ORDER BY x</if>")
        val result = XmlSqlRenderer().render(node, mapOf("x" to 1), traceEnabled = true)
        val conditions = result.renderTrace!!.events.filter { it.kind == "IfSqlNode" }
        assertEquals(2, conditions.size)
        assertEquals(2, conditions.map { it.nodePath }.distinct().size)
        assertTrue(conditions.all { it.accepted })
    }

    @Test
    fun `foreach conditions preserve per invocation outcome`() {
        val node = node("SELECT * FROM t WHERE id IN <foreach collection=\"ids\" item=\"item\" open=\"(\" close=\")\" separator=\",\"><if test=\"item != null\">#{item}</if></foreach>")
        val result = XmlSqlRenderer().render(node, mapOf("ids" to listOf(1, null, 3)), traceEnabled = true)
        val conditions = result.renderTrace!!.events.filter { it.kind == "IfSqlNode" }
        assertEquals(listOf(true, false, true), conditions.map { it.accepted })
        assertEquals(listOf(1, 2, 3), conditions.map { it.invocation })
        assertEquals(1, conditions.map { it.nodePath }.distinct().size)
        assertEquals(2, result.mappings.size)
    }

    @Test
    fun `preview overrides are not labelled runtime evaluation`() {
        val result = XmlSqlRenderer().render(node("SELECT 1 <if test=\"x != null\">WHERE x = #{x}</if>"),
            mapOf("x" to null), conditionOverrides = mapOf("x != null" to true), traceEnabled = true)
        val condition = result.renderTrace!!.events.single { it.kind == "IfSqlNode" }
        assertEquals("PREVIEW_OVERRIDE", condition.conditionBasis)
        assertTrue(condition.accepted)
    }

    @Test
    fun `choose unvisited branch has no fabricated event`() {
        val result = XmlSqlRenderer().render(node("SELECT <choose><when test=\"x != null\">1</when><when test=\"y != null\">2</when><otherwise>3</otherwise></choose>"),
            mapOf("x" to 1, "y" to 1), traceEnabled = true)
        val conditions = result.renderTrace!!.events.filter { it.kind == "IfSqlNode" }
        assertEquals(1, conditions.size)
        assertEquals("x != null", conditions.single().expression)
    }

    @Test(expected = CancellationException::class)
    fun `cancelled trace aborts instead of publishing partial success`() {
        XmlSqlRenderer().render(node("SELECT 1"), emptyMap(), traceEnabled = true, isCancelled = { true })
    }

    @Test
    fun `oversized trace is marked truncated without changing SQL`() {
        val sql = "x".repeat(5000)
        val result = XmlSqlRenderer().render(StaticTextSqlNode(sql), emptyMap(), traceEnabled = true)
        assertEquals(sql, result.sql)
        assertTrue(result.renderTrace!!.truncated)
        assertTrue(result.renderTrace.events.all { it.appendedTemplate == null })
    }

    @Test(expected = XmlTraceBudgetExceeded::class)
    fun `deep tree is rejected before recursive rendering`() {
        var nested: SqlNode = StaticTextSqlNode("SELECT 1")
        repeat(200) { nested = MixedSqlNode(listOf(nested)) }
        XmlSqlRenderer().render(nested, emptyMap(), traceEnabled = true)
    }

    @Test
    fun `nested trim produces actual final wrapper fragment`() {
        val result = XmlSqlRenderer().render(node("SELECT * FROM t <where><if test=\"x != null\">AND x=#{x}</if></where>"),
            mapOf("x" to 1), traceEnabled = true)
        val trim = result.renderTrace!!.events.single { it.kind == "WhereSqlNode" }
        assertTrue(trim.appendedTemplate.orEmpty().startsWith("WHERE"))
        assertFalse(trim.appendedTemplate.orEmpty().contains("WHERE AND"))
    }
}
