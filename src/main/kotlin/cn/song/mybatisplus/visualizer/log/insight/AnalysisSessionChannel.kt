package cn.song.mybatisplus.visualizer.log.insight

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.agent.AgentCaptureState
import cn.song.mybatisplus.visualizer.log.agent.AgentEvent

/** Per socket session. Disabled until the user opts in; delayed control frames cannot authorize old events. */
class AnalysisSessionChannel(private val store: AnalysisSessionStore = AnalysisSessionStore()) {
    private var requested = false
    private var awaitingBoundary = false
    private var ticket: AnalysisSessionTicket? = null
    private var controlToken: String? = null

    @Synchronized
    fun requestedToken(): String? = controlToken.takeIf { requested }

    @Synchronized
    fun enable() {
        store.clear()
        requested = true
        controlToken = java.util.UUID.randomUUID().toString().replace("-", "")
        awaitingBoundary = true
        ticket = null
    }

    @Synchronized
    fun disable() {
        requested = false
        awaitingBoundary = false
        ticket = null
        store.stop()
    }

    @Synchronized
    fun clear() {
        store.clear()
        ticket = null
        if (requested) controlToken = java.util.UUID.randomUUID().toString().replace("-", "")
        awaitingBoundary = requested
    }

    @Synchronized
    fun isRequested(): Boolean = requested

    @Synchronized
    fun onState(state: AgentCaptureState) {
        if (!requested || state.captureStateToken != controlToken) return
        if (awaitingBoundary) {
            val boundary = AgentAnalysisProjection.boundary(state) ?: return
            ticket = store.start(boundary)
            awaitingBoundary = false
        }
        val current = ticket ?: return
        if (state.runId != current.boundary.runId || state.captureEpoch != current.boundary.captureEpoch) {
            store.recordIssue(current, "scope_incomplete")
            store.stop()
            ticket = null
            awaitingBoundary = true
            return
        }
        for (issue in AgentAnalysisProjection.qualityIssues(state)) store.recordIssue(current, issue)
    }

    @Synchronized
    fun onEvent(event: AgentEvent, entry: LogSqlEntry, policyAllows: Boolean) {
        val current = ticket ?: return
        if (!requested) return
        if (!policyAllows) {
            store.recordIssue(current, "omitted_capture")
            return
        }
        val observation = AgentAnalysisProjection.observation(event, entry)
        if (observation == null) store.recordIssue(current, "unsupported_capture")
        else store.accept(current, observation)
    }

    @Synchronized
    fun disconnected() {
        ticket?.let { store.recordIssue(it, "disconnected") }
        store.stop()
        ticket = null
        awaitingBoundary = requested
    }

    @Synchronized
    fun snapshot(): AnalysisSessionSnapshot = store.snapshot()

    @Synchronized
    fun dispose() {
        requested = false
        awaitingBoundary = false
        ticket = null
        store.dispose()
    }
}
