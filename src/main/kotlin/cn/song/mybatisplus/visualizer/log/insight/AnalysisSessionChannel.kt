package cn.song.mybatisplus.visualizer.log.insight

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.agent.AgentCaptureState
import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.RequestEvent
import cn.song.mybatisplus.visualizer.log.agent.TransactionEvent
import cn.song.mybatisplus.visualizer.log.agent.TraceEventEnvelope

/** Per socket session. Disabled until the user opts in; delayed control frames cannot authorize old events. */
class AnalysisSessionChannel(private val store: AnalysisSessionStore = AnalysisSessionStore()) {
    private var requested = false
    private var awaitingBoundary = false
    private var ticket: AnalysisSessionTicket? = null
    private var controlToken: String? = null
    private val traces = RequestTraceStore()

    @Synchronized
    fun requestedToken(): String? = controlToken.takeIf { requested }

    @Synchronized
    fun enable() {
        store.clear()
        traces.clear()
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
        traces.clear()
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
    fun recordFailure(reason: String) {
        ticket?.let { store.recordIssue(it, reason) }
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
    fun onRequest(event: RequestEvent) {
        if (acceptsControl(event.envelope)) traces.accept(event)
    }

    @Synchronized
    fun onTransaction(event: TransactionEvent) {
        if (acceptsControl(event.envelope)) traces.accept(event)
    }

    private fun acceptsControl(envelope: TraceEventEnvelope): Boolean {
        val current = ticket ?: return false
        return requested && envelope.captureStateToken == controlToken &&
            envelope.runId == current.boundary.runId && envelope.captureEpoch == current.boundary.captureEpoch
    }

    fun requests(): List<ObservedRequest> = traces.snapshot(store.snapshot())

    @Synchronized
    fun transactions(): List<TransactionEvent> = traces.transactionEvents()

    fun snapshot(): AnalysisSessionSnapshot = store.snapshot()

    @Synchronized
    fun dispose() {
        requested = false
        awaitingBoundary = false
        ticket = null
        store.dispose()
        traces.clear()
    }
}
