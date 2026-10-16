package cn.song.mybatisplus.visualizer.log.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewExecutionMatcherTest {
    private fun event(id: Long, db: String = "db", time: Long = 100) = SqlExecutionObservation("run", "execution-$id", id,
        1, time, 1, 20, 1, "shape", db, "call", null, ExecutionObservationStatus.SUCCESS)

    @Test
    fun `same shape yields multiple candidates not automatic unique pairing`() {
        val candidates = PreviewExecutionMatcher.candidates(PreviewExecutionContext("shape", "db", "call", 0, 200),
            listOf(event(1), event(2), event(3, db = "other"), event(4, time = 300)))
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { "SAME_DATABASE" in it.evidence })
    }

    @Test
    fun `unknown database does not become proof of same database`() {
        val candidates = PreviewExecutionMatcher.candidates(PreviewExecutionContext("shape", null, null, 0, 200),
            listOf(event(1), event(2, db = "other")))
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { "SAME_DATABASE" !in it.evidence && "SAME_CALL_SITE" !in it.evidence })
    }

    @Test
    fun `baseline comparison refuses incomplete or changed environment`() {
        val before = SqlWorkloadBaseline("fixture-v1", listOf(event(1)), emptySet(), 1)
        val after = SqlWorkloadBaseline("fixture-v1", listOf(event(1), event(2)), emptySet(), 2)
        val delta = SqlWorkloadBaselineComparator.compare(before, after)
        assertEquals(1, delta.executionDelta)
        assertEquals(20L, delta.cumulativeDurationDeltaNanos)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, SqlWorkloadBaselineComparator.compare(before, after.copy(contextKey = "v2")).status)
        val partial = SqlWorkloadBaselineComparator.compare(before, after.copy(qualityIssues = setOf("lost")))
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, partial.status)
        assertEquals(1, partial.executionDelta)
        assertEquals("observed_subset_delta_not_a_regression_verdict", partial.reason)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, SqlWorkloadBaselineComparator.compare(before, after.copy(generatedCount = null)).status)
    }
}
