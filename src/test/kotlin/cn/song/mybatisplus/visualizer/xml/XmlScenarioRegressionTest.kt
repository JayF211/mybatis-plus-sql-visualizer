package cn.song.mybatisplus.visualizer.xml

import cn.song.mybatisplus.visualizer.sql.insight.AnalysisSnapshot
import cn.song.mybatisplus.visualizer.sql.insight.SqlSemanticAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmlScenarioRegressionTest {
    private fun result(sql: String, count: Int = 1, condition: Boolean = true) = XmlScenarioResult("case", sql, count,
        mapOf("id != null" to condition), null, SqlSemanticAnalyzer.analyze(AnalysisSnapshot(sql)), emptyList())

    @Test
    fun `identical known scenario can pass and changed shape fails`() {
        val original = result("SELECT t.id FROM t WHERE t.id = 1")
        val baseline = XmlScenarioRegression.baseline(original)!!
        assertEquals(ScenarioRegressionStatus.PASS, XmlScenarioRegression.compare(listOf(baseline), listOf(original)).single().status)
        val changed = result("SELECT t.id FROM t WHERE t.id > 1")
        assertEquals(ScenarioRegressionStatus.FAIL, XmlScenarioRegression.compare(listOf(baseline), listOf(changed)).single().status)
    }

    @Test
    fun `expected condition and parameter arity are independently checked`() {
        val baseline = XmlScenarioRegression.baseline(result("SELECT t.id FROM t"))!!
        val actual = result("SELECT t.id FROM t", count = 2, condition = false)
        assertEquals(setOf("parameter_count", "condition_results"),
            XmlScenarioRegression.compare(listOf(baseline), listOf(actual)).single().differences.toSet())
    }

    @Test
    fun `missing or unknown analysis never passes regression`() {
        val baseline = XmlScenarioRegression.baseline(result("SELECT t.id FROM t"))!!
        assertEquals(ScenarioRegressionStatus.INCONCLUSIVE, XmlScenarioRegression.compare(listOf(baseline), emptyList()).single().status)
        assertNull(XmlScenarioRegression.baseline(result("SELECT * FROM t")))
        assertNull(XmlScenarioRegression.baseline(result("SELECT t.id FROM t").copy(failure = "failed")))
    }
}
