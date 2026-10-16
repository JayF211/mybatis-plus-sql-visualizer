package cn.song.mybatisplus.visualizer.log.insight

import cn.song.mybatisplus.visualizer.log.agent.RequestEvent
import cn.song.mybatisplus.visualizer.log.agent.TraceEventEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTraceStoreTest {
    private fun request(kind: String, sequence: Long, generated: Long = 3) = RequestEvent(
        TraceEventEnvelope("run", "nonce", 1, sequence, 1, 1000), kind, "request", null,
        100, if (kind == "END") 100 else null, if (kind == "END") "SUCCESS" else null,
        "SERVLET_THREAD_ONLY", null, false, generated, generated, generated, 0, 0,
    )
    private fun sql(id: Long, start: Long) = SqlExecutionObservation("run", "exec-$id", id, 1, 1000,
        start, 40, 1, "same-shape", "database", "call", null, ExecutionObservationStatus.SUCCESS, requestId = "request")
    private fun snapshot(events: List<SqlExecutionObservation>) = AnalysisSessionSnapshot(1, true, events, emptyList(),
        setOf("coverage_not_proven"), 0)

    @Test
    fun `request lifecycle and SQL events compose without inventing whole request coverage`() {
        val store = RequestTraceStore()
        store.accept(request("START", 1))
        store.accept(request("END", 2))
        val observed = store.snapshot(snapshot(listOf(sql(1, 100), sql(2, 120), sql(3, 180)))).single()
        assertEquals(100L, observed.intervalUnionNanos)
        assertEquals(3, observed.repeatedQueries.single().executions)
        assertEquals("REPEATED_QUERY_CANDIDATE", observed.repeatedQueries.single().classification)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, RequestSqlBudget.evaluate(observed.evidence, SqlRequestBudget(5, 1000)).status)
        assertEquals(SqlBudgetStatus.FAIL, RequestSqlBudget.evaluate(observed.evidence, SqlRequestBudget(2, 1000)).status)
    }

    @Test
    fun `end before start does not close until matching start arrives`() {
        val store = RequestTraceStore()
        store.accept(request("END", 2, generated = 0))
        assertEquals(false, store.snapshot(snapshot(emptyList())).single().evidence.began)
        store.accept(request("START", 1, generated = 0))
        assertEquals(true, store.snapshot(snapshot(emptyList())).single().evidence.began)
    }

    @Test
    fun `MDC label alone never attaches SQL to request identity`() {
        val store = RequestTraceStore()
        store.accept(request("START", 1))
        store.accept(request("END", 2))
        val labelled = sql(1, 100).copy(requestId = null, requestTag = "request")
        assertTrue(store.snapshot(snapshot(listOf(labelled))).single().evidence.observations.isEmpty())
    }

    @Test
    fun `request capacity and duplicate control are visible and bounded`() {
        val store = RequestTraceStore(maxRequests = 1)
        store.accept(request("START", 1))
        store.accept(request("START", 1).copy(requestId = "duplicate"))
        assertEquals("request", store.snapshot(snapshot(emptyList())).single().requestId)
        store.accept(request("START", 3).copy(requestId = "next"))
        val next = store.snapshot(snapshot(emptyList())).single()
        assertEquals("next", next.requestId)
        assertTrue("control_capacity" in next.evidence.qualityIssues)
    }
}
