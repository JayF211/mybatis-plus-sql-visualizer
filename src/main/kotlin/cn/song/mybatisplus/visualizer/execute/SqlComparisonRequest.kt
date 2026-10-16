package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.mcp.McpSelectGuard
import java.security.MessageDigest

/** Authorization belongs to an exact pair and a caller-owned target generation, not a mutable editor. */
data class SqlComparisonRequest private constructor(
    val baselineSql: String,
    val candidateSql: String,
    val targetIdentity: String,
    val targetGeneration: Long,
    val maxRows: Int,
    val timeoutSeconds: Int,
    val fingerprint: String,
) {
    private val executionAuthorized = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun authorizeExecution() { executionAuthorized.set(true) }
    internal fun claimExecution(): Boolean = executionAuthorized.compareAndSet(true, false)

    fun matches(baseline: String, candidate: String, target: String, generation: Long): Boolean =
        baselineSql == baseline && candidateSql == candidate && targetIdentity == target && targetGeneration == generation

    companion object {
        fun prepare(
            baselineSql: String,
            candidateSql: String,
            targetIdentity: String,
            targetGeneration: Long,
            maxRows: Int = 1000,
            timeoutSeconds: Int = 10,
        ): Preparation {
            if (targetIdentity.isBlank() || targetGeneration < 0) return Preparation.Rejected("target_required")
            if (maxRows !in 1..SqlResultComparator.MAX_ROWS || timeoutSeconds !in 1..60) {
                return Preparation.Rejected("invalid_budget")
            }
            for (sql in listOf(baselineSql, candidateSql)) {
                val verdict = McpSelectGuard.checkSingleSelect(sql)
                if (!verdict.allowed) return Preparation.Rejected("select_required")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in listOf(baselineSql, candidateSql, targetIdentity, targetGeneration.toString(), maxRows.toString(), timeoutSeconds.toString())) {
                val bytes = part.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(0.toByte())
                digest.update(bytes)
            }
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
            return Preparation.Ready(SqlComparisonRequest(baselineSql, candidateSql, targetIdentity, targetGeneration, maxRows, timeoutSeconds, fingerprint))
        }
    }

    sealed interface Preparation {
        data class Ready(val request: SqlComparisonRequest) : Preparation
        data class Rejected(val reason: String) : Preparation
    }
}

/** SELECT can call volatile functions: read-shape validation never substitutes explicit consent. */
class SqlComparisonConsent(private val request: SqlComparisonRequest) {
    private var consumed = false

    @Synchronized
    fun consume(
        explicitlyApproved: Boolean,
        currentBaseline: String,
        currentCandidate: String,
        currentTarget: String,
        currentGeneration: Long,
    ): SqlComparisonRequest? {
        if (consumed) return null
        consumed = true
        return request.takeIf {
            explicitlyApproved && it.matches(currentBaseline, currentCandidate, currentTarget, currentGeneration)
        }?.also { it.authorizeExecution() }
    }
}
