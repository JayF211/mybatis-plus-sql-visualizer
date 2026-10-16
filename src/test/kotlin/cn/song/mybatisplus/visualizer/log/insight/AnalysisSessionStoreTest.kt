package cn.song.mybatisplus.visualizer.log.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSessionStoreTest {
    private fun event(sequence: Long, database: String = "db-a", duration: Long = 20) = SqlExecutionObservation(
        "run-a", "exec-$sequence", sequence, 1, 1000, 100, duration, 1,
        "same-query-shape", database, "mapper:find", null, ExecutionObservationStatus.SUCCESS,
    )
    private fun start(store: AnalysisSessionStore) = store.start(AnalysisCaptureBoundary("run-a", 1, 0))

    @Test
    fun `one hundred identical SQL executions remain one hundred events`() {
        val store = AnalysisSessionStore()
        val ticket = start(store)
        for (i in 1L..100) assertTrue(store.accept(ticket, event(i)))
        val snapshot = store.snapshot()
        assertEquals(100, snapshot.observations.size)
        assertEquals(100, snapshot.groups.single().executions)
        assertEquals(2000L, snapshot.groups.single().cumulativeDurationNanos)
        assertTrue(snapshot.qualityIssues.contains("coverage_not_proven"))
    }

    @Test
    fun `same execution identity is not counted twice`() {
        val store = AnalysisSessionStore()
        val ticket = start(store)
        assertTrue(store.accept(ticket, event(1)))
        assertFalse(store.accept(ticket, event(1)))
        assertEquals(1, store.snapshot().groups.single().executions)
    }

    @Test
    fun `database and call site partitions stay separate`() {
        val store = AnalysisSessionStore()
        val ticket = start(store)
        store.accept(ticket, event(1))
        store.accept(ticket, event(2, "db-b"))
        store.accept(ticket, event(3).copy(callSite = "mapper:other"))
        assertEquals(3, store.snapshot().groups.size)
    }

    @Test
    fun `clear invalidates old delivery and requires fresh producer boundary`() {
        val store = AnalysisSessionStore()
        val old = start(store)
        store.accept(old, event(1))
        store.clear()
        assertFalse(store.accept(old, event(2)))
        val fresh = store.start(AnalysisCaptureBoundary("run-a", 1, 2))
        assertFalse(store.accept(old, event(3)))
        assertFalse(store.accept(fresh, event(2)))
        assertTrue(store.accept(fresh, event(3)))
        assertEquals(listOf(3L), store.snapshot().observations.map { it.sequence })
    }

    @Test
    fun `stop retains existing observations but never accepts more`() {
        val store = AnalysisSessionStore()
        val ticket = start(store)
        store.accept(ticket, event(1))
        store.stop()
        assertEquals(1, store.snapshot().observations.size)
        assertFalse(store.accept(ticket, event(2)))
        assertFalse(store.snapshot().enabled)
        store.dispose()
        assertTrue(store.snapshot().observations.isEmpty())
    }

    @Test
    fun `wrong process or epoch cannot leak into session`() {
        val store = AnalysisSessionStore()
        val ticket = start(store)
        assertFalse(store.accept(ticket, event(1).copy(runId = "run-b")))
        assertFalse(store.accept(ticket, event(1).copy(captureEpoch = 0)))
        assertTrue(store.snapshot().observations.isEmpty())
    }

    @Test
    fun `bounded retention exposes eviction and does not claim complete workload`() {
        val store = AnalysisSessionStore(maxEvents = 2)
        val ticket = start(store)
        for (i in 1L..3) store.accept(ticket, event(i))
        assertEquals(listOf(2L, 3L), store.snapshot().observations.map { it.sequence })
        assertTrue("store_capacity" in store.snapshot().qualityIssues)
        assertEquals(2, store.snapshot().groups.single().executions)
    }

    @Test
    fun `unknown and truncated events stay explicitly incomplete`() {
        val store = AnalysisSessionStore()
        val ticket = start(store)
        store.accept(ticket, event(1).copy(status = ExecutionObservationStatus.UNKNOWN, truncated = true))
        assertEquals(1, store.snapshot().groups.single().unknownStatus)
        assertTrue("unknown_result" in store.snapshot().qualityIssues)
        assertTrue("truncated_event" in store.snapshot().qualityIssues)
    }

    @Test
    fun `invalid duration rejected and numeric total cannot overflow to negative`() {
        val store = AnalysisSessionStore()
        val ticket = start(store)
        assertFalse(store.accept(ticket, event(1, duration = -1)))
        store.accept(ticket, event(2, duration = Long.MAX_VALUE))
        store.accept(ticket, event(3, duration = 2))
        val snapshot = store.snapshot()
        assertEquals(Long.MAX_VALUE, snapshot.groups.single().cumulativeDurationNanos)
        assertTrue("duration_overflow" in snapshot.qualityIssues)
        assertTrue("invalid_event" in snapshot.qualityIssues)
    }

    @Test
    fun `unknown issue text is not retained and stale quality does not contaminate new session`() {
        val store = AnalysisSessionStore()
        val old = start(store)
        store.recordIssue(old, "secret SQL")
        store.recordIssue(old, "queue_dropped")
        assertFalse("secret SQL" in store.snapshot().qualityIssues)
        assertTrue("queue_dropped" in store.snapshot().qualityIssues)
        start(store)
        store.recordIssue(old, "encoding_failed")
        assertFalse("encoding_failed" in store.snapshot().qualityIssues)
        assertFalse("queue_dropped" in store.snapshot().qualityIssues)
    }
}
