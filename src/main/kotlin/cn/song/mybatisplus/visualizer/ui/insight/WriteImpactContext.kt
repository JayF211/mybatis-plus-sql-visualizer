package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator
import cn.song.mybatisplus.visualizer.log.agent.TransactionEvent
import cn.song.mybatisplus.visualizer.log.insight.SqlExecutionObservation
import cn.song.mybatisplus.visualizer.sql.Dialect

/** Frozen preview evidence. Building a COUNT plan never executes it. */
internal data class WriteImpactContext(
    val sql: String,
    val documentStamp: Long,
    val previewGeneration: Long,
    val target: String?,
    val predicate: String?,
    val countProbe: String?,
    val rowCap: Long?,
    val limitation: String,
) {
    fun matches(sql: String, stamp: Long, generation: Long): Boolean =
        this.sql == sql && documentStamp == stamp && previewGeneration == generation

    fun describe(transaction: TransactionEvent?, execution: SqlExecutionObservation?, notice: String): String = buildString {
        appendLine(notice)
        appendLine("sqlVersion=$documentStamp/$previewGeneration")
        appendLine("SQL:\n$sql")
        appendLine("target=${target ?: "UNKNOWN"}\nWHERE=${predicate ?: "UNKNOWN"}")
        appendLine("COUNT probe (NOT EXECUTED):\n${countProbe ?: "UNAVAILABLE"}\nrowCap=${rowCap ?: "UNKNOWN"}; limitation=$limitation")
        val sameRunConnection = transaction != null && execution != null && transaction.envelope.runId == execution.runId &&
            transaction.connectionId != null && transaction.connectionId == execution.connectionId
        appendLine("association=${if (sameRunConnection) "SAME_RUN_CONNECTION_ONLY_NOT_EXECUTION_PROOF" else "MANUAL_NOTE_NOT_MATCHED"}")
        appendLine("transaction: run=${transaction?.envelope?.runId ?: "UNKNOWN"}; connection=${transaction?.connectionId ?: "UNKNOWN"}; tx=${transaction?.transactionId ?: "UNKNOWN"}; request=${transaction?.requestId ?: "UNKNOWN"}; kind=${transaction?.kind ?: "UNKNOWN"}; outcome=${transaction?.outcome ?: "UNKNOWN"}; coverage=${transaction?.coverage ?: "UNKNOWN"}")
    }

    companion object {
        fun capture(sql: String, stamp: Long, generation: Long, dialect: Dialect?): WriteImpactContext {
            val plan = dialect?.let { DmlImpactEstimator.plan(sql, it) }
            val probe = (plan as? DmlImpactEstimator.Plan.Applicable)?.probe
            return WriteImpactContext(sql, stamp, generation, probe?.targetTable, probe?.whereText, probe?.probeSql, probe?.rowCap,
                (plan as? DmlImpactEstimator.Plan.NotApplicable)?.reasonKey ?: if (dialect == null) "dialect_unknown" else "COUNT_IS_AN_ESTIMATE_NOT_COMMIT; PREDICATE_DISPLAY_MAY_BE_TRUNCATED")
        }
    }
}
