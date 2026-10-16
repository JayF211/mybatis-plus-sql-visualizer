package cn.song.mybatisplus.visualizer.log.insight

/** These are candidates only. Matching SQL shape never proves execution identity. */
data class PreviewExecutionContext(
    val fingerprint: String,
    val databaseIdentity: String?,
    val callSite: String?,
    val fromMillis: Long,
    val untilMillis: Long,
)

data class PreviewExecutionCandidate(val execution: SqlExecutionObservation, val evidence: Set<String>, val score: Int)

object PreviewExecutionMatcher {
    fun candidates(context: PreviewExecutionContext, observations: List<SqlExecutionObservation>): List<PreviewExecutionCandidate> {
        require(context.fromMillis <= context.untilMillis)
        if (context.fingerprint.isBlank()) return emptyList()
        return observations.asSequence().filter {
            it.startedAtMillis in context.fromMillis..context.untilMillis && it.fingerprint == context.fingerprint &&
                (context.databaseIdentity == null || it.databaseIdentity == context.databaseIdentity)
        }.map {
            val facts = linkedSetOf("SAME_SHAPE", "WITHIN_SELECTED_TIME")
            if (context.databaseIdentity != null && it.databaseIdentity == context.databaseIdentity) facts += "SAME_DATABASE"
            if (context.callSite != null && it.callSite == context.callSite) facts += "SAME_CALL_SITE"
            PreviewExecutionCandidate(it, facts, facts.size)
        }.sortedWith(compareByDescending<PreviewExecutionCandidate> { it.score }.thenByDescending { it.execution.startedAtMillis })
            .take(200).toList()
    }
}

data class SqlWorkloadBaseline(
    val contextKey: String,
    val events: List<SqlExecutionObservation>,
    val qualityIssues: Set<String>,
    val generatedCount: Int?,
)

data class SqlWorkloadDelta(
    val status: SqlBudgetStatus,
    val executionDelta: Int?,
    val cumulativeDurationDeltaNanos: Long?,
    val addedShapes: Set<String>,
    val removedShapes: Set<String>,
    val reason: String,
)

object SqlWorkloadBaselineComparator {
    fun compare(before: SqlWorkloadBaseline, after: SqlWorkloadBaseline): SqlWorkloadDelta {
        val beforeShapes = before.events.map { it.fingerprint }.toSet()
        val afterShapes = after.events.map { it.fingerprint }.toSet()
        fun unknown(reason: String) = SqlWorkloadDelta(SqlBudgetStatus.INCONCLUSIVE, null, null,
            afterShapes - beforeShapes, beforeShapes - afterShapes, reason)
        if (before.contextKey.isBlank() || before.contextKey != after.contextKey) return unknown("context_mismatch")
        val incomplete = before.qualityIssues.isNotEmpty() || after.qualityIssues.isNotEmpty() ||
            before.generatedCount != before.events.size || after.generatedCount != after.events.size
        fun duration(events: List<SqlExecutionObservation>): Long? {
            var total = 0L
            for (event in events) {
                if (event.durationNanos < 0 || Long.MAX_VALUE - total < event.durationNanos) return null
                total += event.durationNanos
            }
            return total
        }
        val oldDuration = duration(before.events) ?: return unknown("duration_overflow")
        val newDuration = duration(after.events) ?: return unknown("duration_overflow")
        val executionDelta = after.events.size - before.events.size
        return SqlWorkloadDelta(if (incomplete) SqlBudgetStatus.INCONCLUSIVE else SqlBudgetStatus.PASS,
            executionDelta, newDuration - oldDuration, afterShapes - beforeShapes, beforeShapes - afterShapes,
            if (incomplete) "observed_subset_delta_not_a_regression_verdict" else "comparison_complete_not_a_performance_guarantee")
    }
}
