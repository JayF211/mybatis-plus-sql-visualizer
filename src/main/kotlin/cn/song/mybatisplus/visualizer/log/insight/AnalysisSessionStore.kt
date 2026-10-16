package cn.song.mybatisplus.visualizer.log.insight

import java.util.ArrayDeque

/** Immutable projection; query text and bound values do not belong in the default analysis store. */
data class SqlExecutionObservation(
    val runId: String,
    val executionId: String,
    val sequence: Long,
    val captureEpoch: Long,
    val startedAtMillis: Long,
    val startNanos: Long,
    val durationNanos: Long,
    val threadId: Long?,
    val fingerprint: String,
    val databaseIdentity: String?,
    val callSite: String?,
    val requestTag: String?,
    val status: ExecutionObservationStatus,
    val truncated: Boolean = false,
    val requestId: String? = null,
    val parentRequestId: String? = null,
    val connectionId: String? = null,
    val transactionId: String? = null,
)

enum class ExecutionObservationStatus { SUCCESS, ERROR, UNKNOWN }

data class AnalysisCaptureBoundary(val runId: String, val captureEpoch: Long, val afterSequence: Long)
data class AnalysisSessionTicket(val generation: Long, val boundary: AnalysisCaptureBoundary)

data class SqlWorkloadGroup(
    val fingerprint: String,
    val databaseIdentity: String?,
    val callSite: String?,
    val executions: Int,
    val cumulativeDurationNanos: Long,
    val maximumDurationNanos: Long,
    val p95DurationNanos: Long,
    val errors: Int,
    val unknownStatus: Int,
)

data class AnalysisSessionSnapshot(
    val generation: Long,
    val enabled: Boolean,
    val observations: List<SqlExecutionObservation>,
    val groups: List<SqlWorkloadGroup>,
    val qualityIssues: Set<String>,
    val retainedBytes: Long,
)

/** The ticket must come from an acknowledged producer boundary, not arrival time at the IDE. */
class AnalysisSessionStore(
    private val maxEvents: Int = 10_000,
    private val maxBytes: Long = 64L * 1024 * 1024,
) {
    private var generation = 0L
    private var enabled = false
    private var ticket: AnalysisSessionTicket? = null
    private var bytes = 0L
    private val events = ArrayDeque<SqlExecutionObservation>()
    private val identities = HashSet<String>()
    private val issues = linkedSetOf("coverage_not_proven")

    init {
        require(maxEvents in 1..100_000)
        require(maxBytes in 1024..256L * 1024 * 1024)
    }

    @Synchronized
    fun start(boundary: AnalysisCaptureBoundary): AnalysisSessionTicket {
        require(boundary.runId.isNotBlank() && boundary.captureEpoch >= 0 && boundary.afterSequence >= 0)
        reset()
        enabled = true
        return AnalysisSessionTicket(generation, boundary).also { ticket = it }
    }

    @Synchronized
    fun stop() {
        enabled = false
        ticket = null
        generation++
        issues += "stopped_partial_scope"
    }

    /** Clearing invalidates authorization to accept events; a new producer handshake is required. */
    @Synchronized
    fun clear() {
        reset()
    }

    @Synchronized
    fun dispose() {
        reset()
        issues += "disposed"
    }

    @Synchronized
    fun recordIssue(expected: AnalysisSessionTicket, issue: String) {
        if (expected != ticket || !enabled) return
        if (issue in KNOWN_ISSUES) issues += issue
    }

    @Synchronized
    fun accept(expected: AnalysisSessionTicket, event: SqlExecutionObservation): Boolean {
        if (!enabled || ticket != expected) return false
        val boundary = expected.boundary
        if (event.runId != boundary.runId || event.captureEpoch != boundary.captureEpoch || event.sequence <= boundary.afterSequence) return false
        if (!valid(event)) {
            issues += "invalid_event"
            return false
        }
        if (event.executionId in identities) return false
        val cost = estimateBytes(event)
        if (cost > maxBytes) {
            issues += "store_capacity"
            return false
        }
        while (events.size >= maxEvents || bytes + cost > maxBytes) {
            val removed = events.removeFirst()
            identities.remove(removed.executionId)
            bytes -= estimateBytes(removed)
            issues += "store_capacity"
        }
        events.addLast(event)
        identities += event.executionId
        bytes += cost
        if (event.truncated) issues += "truncated_event"
        if (event.status == ExecutionObservationStatus.UNKNOWN) issues += "unknown_result"
        return true
    }

    fun snapshot(): AnalysisSessionSnapshot {
        val captured = synchronized(this) {
            AnalysisSessionSnapshot(generation, enabled, events.toList(), emptyList(), issues.toSet(), bytes)
        }
        val retained = captured.observations
        val snapshotIssues = captured.qualityIssues.toMutableSet()
        val groups = retained.groupBy { Triple(it.fingerprint, it.databaseIdentity, it.callSite) }.map { (key, entries) ->
            val durations = entries.map { it.durationNanos }.sorted()
            var total = 0L
            for (duration in durations) {
                if (Long.MAX_VALUE - total < duration) {
                    snapshotIssues += "duration_overflow"
                    total = Long.MAX_VALUE
                    break
                }
                total += duration
            }
            SqlWorkloadGroup(key.first, key.second, key.third, entries.size, total, durations.last(),
                durations[((durations.size * 95 + 99) / 100 - 1).coerceAtLeast(0)],
                entries.count { it.status == ExecutionObservationStatus.ERROR },
                entries.count { it.status == ExecutionObservationStatus.UNKNOWN })
        }.sortedByDescending { it.cumulativeDurationNanos }
        return captured.copy(groups = groups, qualityIssues = snapshotIssues.toSet())
    }

    private fun reset() {
        generation++
        enabled = false
        ticket = null
        events.clear()
        identities.clear()
        bytes = 0
        issues.clear()
        issues += "coverage_not_proven"
    }

    private fun valid(event: SqlExecutionObservation): Boolean =
        event.executionId.length in 1..160 && event.runId.length in 1..160 && event.sequence > 0 &&
            event.captureEpoch >= 0 && event.startedAtMillis >= 0 && event.durationNanos >= 0 &&
            event.fingerprint.length in 1..128 && listOf(event.databaseIdentity, event.callSite, event.requestTag,
                event.requestId, event.parentRequestId, event.connectionId, event.transactionId).all { it == null || it.length <= 1024 }

    private fun estimateBytes(event: SqlExecutionObservation): Long = 192L + 2L *
        (event.executionId.length + event.runId.length + event.fingerprint.length +
            (event.databaseIdentity?.length ?: 0) + (event.callSite?.length ?: 0) + (event.requestTag?.length ?: 0) +
            (event.requestId?.length ?: 0) + (event.parentRequestId?.length ?: 0) +
            (event.connectionId?.length ?: 0) + (event.transactionId?.length ?: 0))

    companion object {
        private val KNOWN_ISSUES = setOf("queue_dropped", "encoding_failed", "transport_uncertain", "capture_failed",
            "omitted_capture", "sampled", "missing_terminal", "scope_incomplete", "unsupported_capture", "disconnected")
    }
}
