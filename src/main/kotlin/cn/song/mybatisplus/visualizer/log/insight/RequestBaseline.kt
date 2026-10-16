package cn.song.mybatisplus.visualizer.log.insight

import java.security.MessageDigest

/** Explicit user-owned snapshot, not proof of whole-request coverage or an automatically persisted history. */
data class RequestBaseline(
    val schemaVersion: Int = 1,
    val environmentLabel: String,
    val contextDigest: String,
    val sourceRunDigest: String,
    val sourceRequestDigest: String,
    val shapeCounts: Map<String, Int>,
    val executions: Int,
    val cumulativeNanos: Long,
    val errors: Int,
    val coverageIssues: Set<String>,
    val complete: Boolean,
)

object RequestBaselines {
    sealed interface BuildResult {
        data class Ready(val baseline: RequestBaseline) : BuildResult
        data class Rejected(val reason: String) : BuildResult
    }
    private val fingerprint = Regex("[0-9a-f]{64}")
    private val label = Regex("[\\p{L}\\p{N} _.-]{1,64}")
    private val privateWords = Regex("(?i)(password|passwd|secret|token|credential|jdbc|https?|用户名|密码|口令)")

    fun capture(evidence: SqlRequestEvidence, environmentLabel: String, contextKey: String,
        isCancelled: () -> Boolean = { false }): BuildResult {
        if (isCancelled()) return BuildResult.Rejected("cancelled")
        if (!safeLabel(environmentLabel)) return BuildResult.Rejected("unsafe_environment_label")
        if (contextKey.isBlank() || contextKey.length > 1024 || evidence.runId.isBlank() || evidence.runId.length > 160 ||
            evidence.requestId.isBlank() || evidence.requestId.length > 160) return BuildResult.Rejected("invalid_context")
        if (evidence.observations.size > 10_000 || evidence.qualityIssues.size > 128) return BuildResult.Rejected("event_budget")
        val events = ArrayList<SqlExecutionObservation>()
        val seen = mutableSetOf<String>()
        val issues = linkedSetOf<String>()
        for (event in evidence.observations) {
            if (isCancelled()) return BuildResult.Rejected("cancelled")
            if (event.runId != evidence.runId || event.requestId != evidence.requestId || event.executionId.isBlank() || event.sequence <= 0 || event.durationNanos < 0) {
                issues += "invalid_scope_event"
                continue
            }
            if (!fingerprint.matches(event.fingerprint)) return BuildResult.Rejected("non_digest_fingerprint")
            if (seen.add(event.executionId)) events += event
        }
        val verdict = RequestSqlBudget.evaluate(evidence, SqlRequestBudget(Int.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE))
        // Persist only controlled reasons. Caller-defined quality strings may contain SQL or credentials.
        issues += verdict.reasons
        if (evidence.qualityIssues.isNotEmpty()) issues += "capture_quality_incomplete"
        val counts = events.groupingBy { it.fingerprint }.eachCount()
        if (counts.size > 1000) return BuildResult.Rejected("shape_budget")
        if (isCancelled()) return BuildResult.Rejected("cancelled")
        return BuildResult.Ready(RequestBaseline(environmentLabel = environmentLabel, contextDigest = digest(contextKey),
            sourceRunDigest = digest(evidence.runId), sourceRequestDigest = digest(evidence.requestId), shapeCounts = counts,
            executions = verdict.observedExecutions, cumulativeNanos = verdict.observedCumulativeNanos, errors = verdict.observedErrors,
            coverageIssues = issues, complete = verdict.status == SqlBudgetStatus.PASS && issues.isEmpty()))
    }

    /**
     * An incomplete baseline is an observed subset, not an absolute budget: it proves neither PASS nor FAIL.
     * Complete imported baselines are user assertions, not authenticated capture evidence. Evaluate an explicit
     * absolute user budget separately with RequestSqlBudget; never derive it from missing baseline observations.
     */
    fun evaluate(baseline: RequestBaseline, actual: SqlRequestEvidence, contextKey: String): SqlBudgetVerdict {
        if (contextKey.isBlank() || baseline.contextDigest != digest(contextKey))
            return SqlBudgetVerdict(SqlBudgetStatus.INCONCLUSIVE, setOf("baseline_context_mismatch"), 0, 0, 0)
        val complete = baseline.complete && baseline.coverageIssues.isEmpty()
        val comparison = if (complete) {
            RequestSqlBudget.evaluate(actual, SqlRequestBudget(baseline.executions, baseline.cumulativeNanos,
                baseline.errors, baseline.shapeCounts.keys))
        } else {
            // Use the normal scope validator for metrics, but never use an incomplete subset as a threshold.
            val observed = RequestSqlBudget.evaluate(actual, SqlRequestBudget(Int.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE))
            val actualShapes = actual.observations.asSequence().filter {
                it.runId == actual.runId && it.requestId == actual.requestId && it.executionId.isNotBlank() &&
                    it.sequence > 0 && it.durationNanos >= 0
            }.map { it.fingerprint }.toSet()
            observed.copy(status = SqlBudgetStatus.INCONCLUSIVE,
                reasons = observed.reasons + setOf("baseline_incomplete", "observed_subset_delta_not_regression"),
                addedShapes = actualShapes - baseline.shapeCounts.keys, missingShapes = baseline.shapeCounts.keys - actualShapes)
        }
        return comparison.copy(observedExecutionDelta = comparison.observedExecutions - baseline.executions,
            observedCumulativeNanosDelta = comparison.observedCumulativeNanos - baseline.cumulativeNanos,
            observedErrorDelta = comparison.observedErrors - baseline.errors)
    }

    internal fun safeLabel(value: String) = label.matches(value) && !privateWords.containsMatchIn(value)
    internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
