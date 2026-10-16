package cn.song.mybatisplus.visualizer.xml

import cn.song.mybatisplus.visualizer.sql.insight.SqlInsightDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlScenarioMatrixTest {
    private fun model(body: String): XmlPreviewModel {
        val statement = XmlMapperParser().parse("<mapper namespace=\"fixture\"><select id=\"find\">$body</select></mapper>").statements.single()
        return XmlPreviewModel.from(statement, emptyList())
    }

    @Test
    fun `stored toggle drives scenario shape without mutating model defaults`() {
        val model = model("SELECT id FROM t <where><if test=\"id != null\">AND id=#{id}</if></where>")
        val before = model.renderSql()
        val result = XmlScenarioMatrix.render(model, listOf(
            XmlScenarioInput("included", mapOf("__chk__:id != null" to "1", "id" to "12")),
            XmlScenarioInput("excluded", mapOf("__chk__:id != null" to "0", "id" to "12")),
        ), SqlInsightDialect.POSTGRESQL)
        assertTrue(result[0].sql.orEmpty().contains("12"))
        assertEquals(1, result[0].parameterCount)
        assertFalse(result[1].sql.orEmpty().contains("WHERE"))
        assertEquals(before, model.renderSql())
        assertTrue(result.all { it.warnings.any { warning -> warning.contains("preview toggles") } })
    }

    @Test
    fun `foreach actual values use renderer binding rather than global numeric suffix`() {
        val model = model("SELECT id FROM t WHERE id IN <foreach collection=\"a\" item=\"x\" open=\"(\" close=\")\" separator=\",\">#{x}</foreach> OR id IN <foreach collection=\"b\" item=\"y\" open=\"(\" close=\")\" separator=\",\">#{y}</foreach>")
        val result = XmlScenarioMatrix.render(model, listOf(XmlScenarioInput("two-loops", mapOf(
            "__fev__:a" to "10,20", "__fev__:b" to "30,40",
        ))), SqlInsightDialect.POSTGRESQL).single()
        assertEquals(4, result.parameterCount)
        for (value in listOf("10", "20", "30", "40")) assertTrue(result.sql.orEmpty(), result.sql.orEmpty().contains(value))
        assertFalse(result.sql.orEmpty().contains("?"))
    }

    @Test
    fun `empty collection and null collection remain distinct shapes`() {
        val model = model("SELECT id FROM t <foreach collection=\"ids\" item=\"x\" open=\"WHERE id IN (\" close=\")\" separator=\",\">#{x}</foreach>")
        val results = XmlScenarioMatrix.render(model, listOf(
            XmlScenarioInput("empty", mapOf("__fev__:ids" to "")),
            XmlScenarioInput("null", mapOf("__fnull__:ids" to "1")),
        ), SqlInsightDialect.POSTGRESQL)
        assertEquals(0, results[0].parameterCount)
        assertFalse(results[0].sql.orEmpty().contains("IN"))
        assertTrue(results[1].warnings.size > results[0].warnings.size)
    }

    @Test
    fun `dollar substitution and derived bind use actual scenario scalar values`() {
        val model = model("<bind name=\"p\" value=\"'%' + name + '%'\"/>SELECT id FROM t WHERE name LIKE #{p} ORDER BY " + '$' + "{orderBy}")
        val results = XmlScenarioMatrix.render(model, listOf(
            XmlScenarioInput("one", mapOf("name" to "alice", "orderBy" to "id")),
            XmlScenarioInput("two", mapOf("name" to "bob", "orderBy" to "created_at")),
        ), SqlInsightDialect.POSTGRESQL)
        assertTrue(results[0].sql.orEmpty(), results[0].sql.orEmpty().contains("%alice%"))
        assertTrue(results[0].sql.orEmpty().contains("ORDER BY id"))
        assertTrue(results[1].sql.orEmpty().contains("%bob%"))
        assertTrue(results[1].sql.orEmpty().contains("ORDER BY created_at"))
    }

    @Test
    fun `nested collection product stops at render work budget`() {
        val model = model("SELECT <foreach collection=\"a\" item=\"x\" separator=\",\"><foreach collection=\"b\" item=\"y\" separator=\",\">#{x}</foreach></foreach>")
        val values = (1..200).joinToString(",")
        val result = XmlScenarioMatrix.render(model, listOf(XmlScenarioInput("bounded", mapOf("__fev__:a" to values, "__fev__:b" to values))),
            SqlInsightDialect.POSTGRESQL).single()
        assertEquals("render_budget", result.failure)
        assertEquals(null, result.sql)
    }

    @Test
    fun `invalid scenario fails independently`() {
        val results = XmlScenarioMatrix.render(model("SELECT 1 <if test=\"x != null\">WHERE x=#{x}</if>"), listOf(
            XmlScenarioInput("bad", mapOf("__chk__:x != null" to "maybe")),
            XmlScenarioInput("good", mapOf("__chk__:x != null" to "0")),
        ), SqlInsightDialect.POSTGRESQL)
        assertEquals("invalid_scenario", results[0].failure)
        assertEquals(null, results[1].failure)
    }
}
