package cn.song.mybatisplus.visualizer.log.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestSqlBudgetTest {
    private val budget = SqlRequestBudget(2, 100, 0)
    private fun event(id: Long) = SqlExecutionObservation("run", "execution-$id", id, 1, 100, 100,
        20, 1, "fp", "db", "call", "request", ExecutionObservationStatus.SUCCESS)
    private fun evidence(vararg observations: SqlExecutionObservation) = SqlRequestEvidence(
        "request", "run", true, true, 10, observations.size, 0, 0, true, true, observations.toList(),
    )

    @Test
    fun `complete scope can pass and zero SQL requires explicit terminal proof`() {
        assertEquals(SqlBudgetStatus.PASS, RequestSqlBudget.evaluate(evidence(event(1)), budget).status)
        assertEquals(SqlBudgetStatus.PASS, RequestSqlBudget.evaluate(evidence(), budget).status)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE,
            RequestSqlBudget.evaluate(evidence().copy(expectedExecutions = null), budget).status)
    }

    @Test
    fun `tail loss and process disappearance cannot pass`() {
        val partial = evidence(event(1)).copy(expectedExecutions = 2)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, RequestSqlBudget.evaluate(partial, budget).status)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE,
            RequestSqlBudget.evaluate(evidence(event(1)).copy(ended = false, endWatermark = null), budget).status)
    }

    @Test
    fun `end before events is pending until count converges`() {
        val ended = evidence().copy(expectedExecutions = 1)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, RequestSqlBudget.evaluate(ended, budget).status)
        assertEquals(SqlBudgetStatus.PASS, RequestSqlBudget.evaluate(ended.copy(observations = listOf(event(1))), budget).status)
    }

    @Test
    fun `synchronous HTTP waiting for worker SQL does not prove cross thread coverage`() {
        assertEquals(SqlBudgetStatus.INCONCLUSIVE,
            RequestSqlBudget.evaluate(evidence(event(1)).copy(crossThreadCoverageProven = false), budget).status)
    }

    @Test
    fun `observed lower bound can fail even with incomplete coverage`() {
        val partial = evidence(event(1), event(2), event(3)).copy(ended = false, crossThreadCoverageProven = false)
        assertEquals(SqlBudgetStatus.FAIL, RequestSqlBudget.evaluate(partial, budget).status)
    }

    @Test
    fun `wrong request or process cannot create budget failure for another scope`() {
        val wrong = evidence(event(1).copy(requestTag = "other", durationNanos = 1000))
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, RequestSqlBudget.evaluate(wrong, budget).status)
        assertEquals(0, RequestSqlBudget.evaluate(wrong, budget).observedExecutions)
    }

    @Test
    fun `duplicate delivery counts once`() {
        val duplicated = evidence(event(1)).copy(observations = listOf(event(1), event(1)))
        val verdict = RequestSqlBudget.evaluate(duplicated, budget)
        assertEquals(SqlBudgetStatus.PASS, verdict.status)
        assertEquals(1, verdict.observedExecutions)
    }

    @Test
    fun `unknown result loss and active children prevent success`() {
        val cases = listOf(
            evidence(event(1).copy(status = ExecutionObservationStatus.UNKNOWN)),
            evidence(event(1)).copy(lossCount = null),
            evidence(event(1)).copy(activeChildren = 1),
            evidence(event(1)).copy(supportedCapture = false),
            evidence(event(1)).copy(qualityIssues = setOf("sampled")),
            evidence(event(1)).copy(endWatermark = 0),
        )
        for (case in cases) assertEquals(SqlBudgetStatus.INCONCLUSIVE, RequestSqlBudget.evaluate(case, budget).status)
    }

    @Test
    fun `known error exceeds zero error budget`() {
        val verdict = RequestSqlBudget.evaluate(evidence(event(1).copy(status = ExecutionObservationStatus.ERROR)), budget)
        assertEquals(SqlBudgetStatus.FAIL, verdict.status)
        assertTrue("error_budget_exceeded" in verdict.reasons)
    }
}
