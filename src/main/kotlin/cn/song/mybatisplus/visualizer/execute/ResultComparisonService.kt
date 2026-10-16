package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.mcp.McpSelectGuard
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.util.FatalErrors
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Facts/approvals supplied by the preview confirmation, bound to the exact prepared request. */
data class ComparisonSafetyContext(
    val requestFingerprint: String,
    val baselineDialect: ExecutionDialectGate.Request,
    val candidateDialect: ExecutionDialectGate.Request,
    val rawSegmentsPresent: Boolean,
    val rawSegmentsApproved: Boolean,
    val parameterBindingsResolved: Boolean,
    val selectFunctionSideEffectsApproved: Boolean,
    val unknownDialectApproved: Boolean = false,
)

data class ComparisonTargetState(val identity: String, val generation: Long, val dialectName: String?)
data class ComparisonExecutionResult(
    val baseline: SqlResultSnapshot?, val candidate: SqlResultSnapshot?,
    val comparison: SqlResultComparison, val effectiveMaxRows: Int,
    /** Two sequential reads are not a single database snapshot and may observe concurrent changes. */
    val consistency: String = "SEQUENTIAL_READS_NOT_ATOMIC",
)

class ComparisonCancellation {
    private val cancelled = AtomicBoolean()
    @Volatile private var cancelStatement: (() -> Unit)? = null
    fun cancel() { cancelled.set(true); cancelStatement?.invoke() }
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("comparison_cancelled") }
    internal fun attach(action: (() -> Unit)?) {
        cancelStatement = action
        if (cancelled.get()) action?.invoke()
    }
}

/** Injectable session is internal: public callers use the project helper, never bypass target checks. */
internal interface ComparisonQuerySession : AutoCloseable {
    fun query(sql: String, contextKey: String, maxRows: Int, timeoutSeconds: Int,
              cancellation: ComparisonCancellation, validate: () -> Unit): SqlResultSnapshot
}

class ResultComparisonService internal constructor(
    private val open: (ComparisonCancellation, () -> Unit) -> ComparisonQuerySession,
    private val currentTarget: () -> ComparisonTargetState?,
) {
    /** Blocking core; the project helper dispatches it to the pooled executor. */
    fun execute(
        authorized: SqlComparisonRequest,
        safety: ComparisonSafetyContext,
        ordered: Boolean = false,
        cancellation: ComparisonCancellation = ComparisonCancellation(),
    ): ComparisonExecutionResult {
        val rows = cn.song.mybatisplus.visualizer.mcp.channelRowBudget(authorized.maxRows)
        fun inconclusive(reason: String, left: SqlResultSnapshot? = null, right: SqlResultSnapshot? = null) =
            ComparisonExecutionResult(left, right, SqlResultComparison(SqlResultComparisonStatus.INCONCLUSIVE,
                reason, left?.rows?.size ?: 0, right?.rows?.size ?: 0), rows)
        if (!authorized.claimExecution()) return inconclusive("consent_not_consumed_or_reused")
        fun validate() {
            cancellation.check()
            val target = currentTarget() ?: throw ComparisonRejected("target_unavailable")
            if (target.identity != authorized.targetIdentity || target.generation != authorized.targetGeneration)
                throw ComparisonRejected("target_changed")
            if (safety.requestFingerprint != authorized.fingerprint) throw ComparisonRejected("approval_mismatch")
            if (!safety.selectFunctionSideEffectsApproved) throw ComparisonRejected("select_side_effect_consent_required")
            if (!safety.parameterBindingsResolved) throw ComparisonRejected("parameters_unresolved")
            if (safety.rawSegmentsPresent && !safety.rawSegmentsApproved) throw ComparisonRejected("raw_segments_unapproved")
            for ((sql, dialect) in listOf(authorized.baselineSql to safety.baselineDialect, authorized.candidateSql to safety.candidateDialect)) {
                if (!McpSelectGuard.checkSingleSelect(sql).allowed || placeholderPositions(sql).isNotEmpty())
                    throw ComparisonRejected("select_or_binding_guard")
                if (dialect.intent != ExecutionDialectGate.Intent.QUERY) throw ComparisonRejected("query_intent_required")
                when (ExecutionDialectGate.evaluate(dialect, target.dialectName).status) {
                    ExecutionDialectGate.Status.MATCH -> Unit
                    ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM,
                    ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM ->
                        if (!safety.unknownDialectApproved) throw ComparisonRejected("dialect_confirmation_required")
                    else -> throw ComparisonRejected("dialect_conflict")
                }
            }
        }
        var left: SqlResultSnapshot? = null
        var right: SqlResultSnapshot? = null
        return try {
            validate()
            open(cancellation, ::validate).use { session ->
                validate()
                val context = authorized.targetIdentity + ":" + authorized.targetGeneration
                left = session.query(authorized.baselineSql, context, rows, authorized.timeoutSeconds, cancellation, ::validate)
                validate()
                if (left!!.failure != null || left!!.truncated) return inconclusive("baseline_incomplete", left)
                right = session.query(authorized.candidateSql, context, rows, authorized.timeoutSeconds, cancellation, ::validate)
                validate()
                // No unique ordering proof exists in a generic SELECT. ORDER BY text alone is insufficient.
                ComparisonExecutionResult(left, right, SqlResultComparator.compare(left!!, right!!, ordered), rows)
            }
        } catch (failure: Throwable) {
            var cause = failure
            while (cause is java.lang.reflect.InvocationTargetException) cause = cause.targetException
            if (FatalErrors.isFatal(cause)) throw cause
            if (cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
            inconclusive(when (cause) {
                is ComparisonRejected -> cause.message ?: "rejected"
                is CancellationException, is InterruptedException -> "cancelled"
                else -> "execution_failed:${cause.javaClass.simpleName}"
            }, left, right)
        }
    }
}
internal class ComparisonRejected(reason: String) : IllegalStateException(reason)
