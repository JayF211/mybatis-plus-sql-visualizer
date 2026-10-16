package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplainPlanInsightsTest {
    private fun raw(json: String, truncated: Boolean = false) = McpExplainFormatter.RawResult(listOf("QUERY PLAN"), listOf(listOf(json)), truncated)

    @Test
    fun `estimated rows and cost never become actual measurements`() {
        val report = ExplainPlanInsights.inspect(raw("""[{"Plan":{"Node Type":"Seq Scan","Relation Name":"t","Plan Rows":100,"Total Cost":12.3}}]"""), ExplainOutputFormat.JSON)!!
        val operator = report.operators.single()
        assertEquals("sequentialScan", operator.explanationKey)
        assertEquals(100L, operator.estimatedRows)
        assertEquals(12.3, operator.estimatedCost)
        assertNull(operator.actualRowsPerLoop)
        assertNull(operator.actualTimePerLoopMs)
        assertFalse(report.hasActualMeasurements)
    }

    @Test
    fun `actual measurements remain per loop and do not overwrite estimates`() {
        val report = ExplainPlanInsights.inspect(raw("""[{"Plan":{"Node Type":"Nested Loop","Plan Rows":5,"Actual Rows":7,"Actual Loops":3,"Actual Total Time":2.5}}]"""), ExplainOutputFormat.JSON)!!
        val operator = report.operators.single()
        assertEquals(5L, operator.estimatedRows)
        assertEquals(7.0, operator.actualRowsPerLoop)
        assertEquals(3.0, operator.actualLoops)
        assertEquals(2.5, operator.actualTimePerLoopMs)
        assertTrue(report.hasActualMeasurements)
    }

    @Test
    fun `actual only plan does not fabricate estimated rows`() {
        val report = ExplainPlanInsights.inspect(raw("""[{"Plan":{"Node Type":"Limit","Actual Rows":2}}]"""), ExplainOutputFormat.JSON)!!
        assertNull(report.operators.single().estimatedRows)
        assertEquals(2.0, report.operators.single().actualRowsPerLoop)
    }

    @Test
    fun `unsupported truncated and malformed plans return no interpretation`() {
        assertNull(ExplainPlanInsights.inspect(raw("garbage"), ExplainOutputFormat.JSON))
        assertNull(ExplainPlanInsights.inspect(raw("[]", truncated = true), ExplainOutputFormat.JSON))
        assertNull(ExplainPlanInsights.inspect(raw("[]"), ExplainOutputFormat.TABLE))
    }
}
