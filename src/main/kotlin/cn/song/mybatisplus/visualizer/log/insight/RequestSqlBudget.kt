package cn.song.mybatisplus.visualizer.log.insight

enum class SqlBudgetStatus { PASS, FAIL, INCONCLUSIVE }

data class SqlRequestEvidence(
    val requestId: String,
    val runId: String,
    val began: Boolean,
    val ended: Boolean,
    val endWatermark: Long?,
    val expectedExecutions: Int?,
    val lossCount: Long?,
    val activeChildren: Int?,
    val crossThreadCoverageProven: Boolean,
    val supportedCapture: Boolean,
    val observations: List<SqlExecutionObservation>,
    val qualityIssues: Set<String> = emptySet(),
)

data class SqlRequestBudget(val maxExecutions: Int, val maxCumulativeNanos: Long, val maxErrors: Int = 0)

data class SqlBudgetVerdict(
    val status: SqlBudgetStatus,
    val reasons: Set<String>,
    val observedExecutions: Int,
    val observedCumulativeNanos: Long,
    val observedErrors: Int,
)

object RequestSqlBudget {
    fun evaluate(evidence: SqlRequestEvidence, budget: SqlRequestBudget): SqlBudgetVerdict {
        require(budget.maxExecutions >= 0 && budget.maxCumulativeNanos >= 0 && budget.maxErrors >= 0)
        val reasons = linkedSetOf<String>()
        val seen = HashSet<String>()
        val events = evidence.observations.filter {
            val valid = it.runId == evidence.runId && it.requestId == evidence.requestId &&
                it.executionId.isNotBlank() && it.sequence > 0 && it.durationNanos >= 0
            if (!valid) reasons += "invalid_scope_event"
            valid && seen.add(it.executionId)
        }
        var duration = 0L
        for (event in events) {
            if (Long.MAX_VALUE - duration < event.durationNanos) {
                reasons += "duration_overflow"
                duration = Long.MAX_VALUE
                break
            }
            duration += event.durationNanos
        }
        val errors = events.count { it.status == ExecutionObservationStatus.ERROR }
        val exceeded = events.size > budget.maxExecutions || duration > budget.maxCumulativeNanos || errors > budget.maxErrors
        if (events.size > budget.maxExecutions) reasons += "execution_budget_exceeded"
        if (duration > budget.maxCumulativeNanos) reasons += "duration_budget_exceeded"
        if (errors > budget.maxErrors) reasons += "error_budget_exceeded"
        if (evidence.requestId.isBlank() || evidence.runId.isBlank()) reasons += "scope_identity_missing"
        if (!evidence.began || !evidence.ended) reasons += "scope_not_closed"
        if (!evidence.crossThreadCoverageProven) reasons += "cross_thread_coverage_unknown"
        if (!evidence.supportedCapture) reasons += "capture_coverage_unknown"
        if (evidence.endWatermark == null || evidence.endWatermark < 0) reasons += "terminal_watermark_missing"
        if (evidence.expectedExecutions == null || evidence.expectedExecutions != events.size) reasons += "terminal_count_mismatch"
        if (evidence.lossCount == null || evidence.lossCount != 0L) reasons += "loss_not_zero_or_unknown"
        if (evidence.activeChildren == null || evidence.activeChildren != 0) reasons += "children_not_closed"
        if (evidence.endWatermark != null && events.any { it.sequence > evidence.endWatermark }) reasons += "event_after_terminal_watermark"
        if (events.any { it.status == ExecutionObservationStatus.UNKNOWN }) reasons += "unknown_result"
        if (events.any { it.truncated }) reasons += "truncated_event"
        if (evidence.qualityIssues.isNotEmpty()) reasons += "capture_quality_incomplete"
        // A lower bound can prove a violation, but cannot prove compliance when coverage is missing.
        val status = when {
            "invalid_scope_event" in reasons || "scope_identity_missing" in reasons -> SqlBudgetStatus.INCONCLUSIVE
            exceeded -> SqlBudgetStatus.FAIL
            reasons.isNotEmpty() -> SqlBudgetStatus.INCONCLUSIVE
            else -> SqlBudgetStatus.PASS
        }
        return SqlBudgetVerdict(status, reasons, events.size, duration, errors)
    }
}
