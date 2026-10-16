package cn.song.mybatisplus.visualizer.log.insight

import org.junit.Assert.*
import org.junit.Test

class RequestShapeBudgetTest {
    private fun evidence(shape: String = "a") = SqlRequestEvidence("request", "run", true, true, 1, 1, 0, 0, true, true,
        listOf(SqlExecutionObservation("run", "e", 1, 1, 1, 1, 10, 1, shape, null, null, null,
            ExecutionObservationStatus.SUCCESS, requestId = "request")))
    private fun budget(vararg shapes: String) = SqlRequestBudget(10, 1000, expectedShapeSet = shapes.toSet())

    @Test fun `added shape proves failure even when capture is incomplete`() {
        val v = RequestSqlBudget.evaluate(evidence("new").copy(ended = false, crossThreadCoverageProven = false), budget("old"))
        assertEquals(SqlBudgetStatus.FAIL, v.status)
        assertEquals(setOf("new"), v.addedShapes)
        assertEquals(setOf("old"), v.missingShapes)
        assertTrue("unexpected_shape_observed" in v.reasons)
    }

    @Test fun `missing shape only fails for proven complete coverage`() {
        val complete = RequestSqlBudget.evaluate(evidence(), budget("a", "b"))
        assertEquals(SqlBudgetStatus.FAIL, complete.status)
        val partial = RequestSqlBudget.evaluate(evidence().copy(qualityIssues = setOf("sampled")), budget("a", "b"))
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, partial.status)
        assertEquals(setOf("b"), partial.missingShapes)
        assertFalse("expected_shape_missing" in partial.reasons)
    }

    @Test fun `matching shape cannot launder incomplete evidence and absent shape policy is compatible`() {
        assertEquals(SqlBudgetStatus.PASS, RequestSqlBudget.evaluate(evidence(), budget("a")).status)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, RequestSqlBudget.evaluate(evidence().copy(expectedExecutions = 2), budget("a")).status)
        val legacy = RequestSqlBudget.evaluate(evidence(), SqlRequestBudget(10, 1000))
        assertTrue(legacy.addedShapes.isEmpty() && legacy.missingShapes.isEmpty())
        assertEquals(SqlBudgetStatus.PASS, legacy.status)
    }

    @Test fun `wrong request cannot create structural failure`() {
        val original = evidence("new")
        val wrong = original.copy(observations = original.observations.map { it.copy(requestId = "foreign") })
        val v = RequestSqlBudget.evaluate(wrong, budget("old"))
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, v.status)
        assertTrue(v.addedShapes.isEmpty())
    }
}
