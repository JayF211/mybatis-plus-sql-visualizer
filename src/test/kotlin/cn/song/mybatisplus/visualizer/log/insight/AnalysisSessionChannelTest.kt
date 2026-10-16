package cn.song.mybatisplus.visualizer.log.insight

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.agent.AgentCaptureState
import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSessionChannelTest {
    private fun state(token: String, sequence: Long = 0, epoch: Long = 1): AgentCaptureState =
        AgentCaptureState.parse("""{"protocolVersion":2,"runId":"run","kind":"SNAPSHOT","terminal":false,
        "captureEpoch":$epoch,"lastSequence":$sequence,"generated":$sequence,"completed":$sequence,"active":0,
        "omitted":0,"captureFailed":0,"queueDropped":0,"cleared":0,"encodingFailed":0,"transportUncertain":0,
        "written":$sequence,"sampled":0,"coverage":"INSTRUMENTED_JDBC_ONLY","captureStateToken":"$token"}""")!!

    private fun event(sequence: Long): AgentEvent = AgentEvent.parse("""{"sql":"SELECT secret FROM account",
        "ts":1000,"dn":20,"runId":"run","executionId":"execution-$sequence","sequence":$sequence,
        "captureEpoch":1,"threadId":1,"startNanos":100,"resultStatus":"SUCCESS","timestampSource":"ENTRY_WALL_MILLIS"}""")!!
    private val entry = LogSqlEntry("SELECT secret FROM account", "SELECT secret FROM account", emptyList())

    @Test
    fun `disabled or unacknowledged channel retains no events`() {
        val channel = AnalysisSessionChannel()
        channel.onEvent(event(1), entry, true)
        assertTrue(channel.snapshot().observations.isEmpty())
        channel.enable()
        channel.onEvent(event(2), entry, true)
        assertTrue(channel.snapshot().observations.isEmpty())
    }

    @Test
    fun `matching nonce sets producer watermark and preserves individual executions`() {
        val channel = AnalysisSessionChannel()
        channel.enable()
        channel.onState(state(channel.requestedToken()!!, 10))
        channel.onEvent(event(10), entry, true)
        for (i in 11L..110) channel.onEvent(event(i), entry, true)
        assertEquals(100, channel.snapshot().observations.size)
        assertEquals(100, channel.snapshot().groups.single().executions)
        assertFalse(channel.snapshot().toString().contains("secret"))
    }

    @Test
    fun `clear rotates nonce rejects old state and old buffered SQL`() {
        val channel = AnalysisSessionChannel()
        channel.enable()
        val old = channel.requestedToken()!!
        channel.onState(state(old))
        channel.onEvent(event(1), entry, true)
        channel.clear()
        channel.onState(state(old))
        channel.onEvent(event(2), entry, true)
        assertTrue(channel.snapshot().observations.isEmpty())
        channel.onState(state(channel.requestedToken()!!, 2))
        channel.onEvent(event(2), entry, true)
        channel.onEvent(event(3), entry, true)
        assertEquals(listOf(3L), channel.snapshot().observations.map { it.sequence })
    }

    @Test
    fun `screening policy is not bypassed by analysis`() {
        val channel = AnalysisSessionChannel()
        channel.enable()
        channel.onState(state(channel.requestedToken()!!))
        channel.onEvent(event(1), entry, false)
        assertTrue(channel.snapshot().observations.isEmpty())
        assertTrue("omitted_capture" in channel.snapshot().qualityIssues)
    }

    @Test
    fun `old agents and unknown timestamp source cannot yield exact observations`() {
        val channel = AnalysisSessionChannel()
        channel.enable()
        channel.onState(state(channel.requestedToken()!!))
        channel.onEvent(AgentEvent.parse("""{"sql":"SELECT 1"}""")!!, entry, true)
        channel.onEvent(event(1).copy(timestampSource = null), entry, true)
        assertTrue(channel.snapshot().observations.isEmpty())
        assertTrue("unsupported_capture" in channel.snapshot().qualityIssues)
    }

    @Test
    fun `stop retains evidence while dispose drops memory`() {
        val channel = AnalysisSessionChannel()
        channel.enable()
        channel.onState(state(channel.requestedToken()!!))
        channel.onEvent(event(1), entry, true)
        channel.disable()
        channel.onEvent(event(2), entry, true)
        assertEquals(1, channel.snapshot().observations.size)
        channel.dispose()
        assertTrue(channel.snapshot().observations.isEmpty())
    }
}
