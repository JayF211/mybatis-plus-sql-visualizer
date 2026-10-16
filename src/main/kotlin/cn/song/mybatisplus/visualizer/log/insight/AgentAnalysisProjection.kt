package cn.song.mybatisplus.visualizer.log.insight

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.agent.AgentCaptureState
import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import java.security.MessageDigest

object AgentAnalysisProjection {
    fun observation(event: AgentEvent, entry: LogSqlEntry): SqlExecutionObservation? {
        val runId = event.runId ?: return null
        val executionId = event.executionId ?: return null
        val sequence = event.sequence ?: return null
        val epoch = event.captureEpoch ?: return null
        val nanos = event.startNanos ?: return null
        if (event.timestampSource != "ENTRY_WALL_MILLIS") return null
        return SqlExecutionObservation(
            runId, executionId, sequence, epoch, event.timestamp, nanos, event.durationNanos, event.threadId,
            digest(entry.structureKey), entry.databaseName?.let(::digest),
            listOfNotNull(event.sourceFile, event.methodKey).joinToString(":").takeIf { it.isNotBlank() }?.let(::digest),
            event.requestTag?.let(::digest),
            when (event.resultStatus) {
                "SUCCESS" -> ExecutionObservationStatus.SUCCESS
                "ERROR" -> ExecutionObservationStatus.ERROR
                else -> ExecutionObservationStatus.UNKNOWN
            },
            requestId = event.requestId,
            parentRequestId = event.parentRequestId,
            connectionId = event.connectionId,
            transactionId = event.transactionId,
        )
    }

    fun boundary(state: AgentCaptureState): AnalysisCaptureBoundary? {
        if (state.protocolVersion != 2L || state.kind == "STOP") return null
        return AnalysisCaptureBoundary(state.runId?.takeIf { it.isNotBlank() } ?: return null,
            state.captureEpoch ?: return null, state.lastSequence ?: return null)
    }

    fun qualityIssues(state: AgentCaptureState): Set<String> = buildSet {
        val counters = listOf(
            state.queueDropped to "queue_dropped", state.encodingFailed to "encoding_failed",
            state.transportUncertain to "transport_uncertain", state.captureFailed to "capture_failed",
            state.omitted to "omitted_capture", state.sampled to "sampled", state.cleared to "scope_incomplete",
        )
        for ((value, reason) in counters) if (value == null || value > 0) add(reason)
        if (state.protocolVersion != 2L || state.coverage != "INSTRUMENTED_JDBC_ONLY") add("unsupported_capture")
        if (state.requestTracing == true || state.transactionTracing == true) {
            val controlLoss = listOf(state.controlDropped, state.controlCleared, state.controlEncodingFailed,
                state.controlTransportUncertain, state.controlHookFailed)
            if (controlLoss.any { it == null || it > 0 }) add("scope_incomplete")
        }
        // Run-level terminal counts do not establish request lifecycle or complete driver coverage.
        add("scope_incomplete")
    }

    fun shapeFingerprint(sql: String): String = digest(cn.song.mybatisplus.visualizer.log.structureFingerprint(sql))

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
