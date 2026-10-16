package cn.song.mybatisplus.visualizer.log.insight

import cn.song.mybatisplus.visualizer.log.agent.RequestEvent
import cn.song.mybatisplus.visualizer.log.agent.TraceEventEnvelope
import cn.song.mybatisplus.visualizer.log.agent.TransactionEvent
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTraceCapacityTest {
    private fun envelope(sequence: Long) = TraceEventEnvelope("run", "token", 1, sequence, 1, 1000)
    private fun request(parent: String?) = RequestEvent(envelope(1), "START", "request", parent,
        1, null, null, "SERVLET_THREAD_ONLY", null, false, 0, 0, 0, 0, 0)

    @Test
    fun `large control strings rejected rather than retained under SQL byte budget`() {
        val store = RequestTraceStore()
        store.accept(request("x".repeat(256 * 1024)))
        val sql = AnalysisSessionSnapshot(1, true, emptyList(), emptyList(), emptySet(), 0)
        assertTrue(store.snapshot(sql).isEmpty())
        store.accept(TransactionEvent(envelope(2), "COMMIT", "x".repeat(256 * 1024), "lease", "tx", null,
            null, "SUCCESS", "LOCAL_COMMIT", false, null, null, "PROXIED_CONNECTION_ONLY"))
        assertTrue(store.transactionEvents().isEmpty())
    }

    @Test
    fun `request error class is not retained in diagnostic facts`() {
        val store = RequestTraceStore()
        store.accept(request(null).copy(errorClass = "sensitive-class"))
        val sql = AnalysisSessionSnapshot(1, true, emptyList(), emptyList(), emptySet(), 0)
        assertTrue(store.snapshot(sql).single().start?.errorClass == null)
    }
}
