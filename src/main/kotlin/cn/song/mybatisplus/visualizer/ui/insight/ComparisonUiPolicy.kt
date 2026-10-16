package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.execute.*
import cn.song.mybatisplus.visualizer.sql.placeholderPositions

internal data class ComparisonUiApprovals(val executePair: Boolean, val functions: Boolean, val bindings: Boolean, val raw: Boolean, val unknownDialect: Boolean)

/** The UI only attests explicit user approvals plus locally verified absence of unresolved placeholders. */
internal object ComparisonUiPolicy {
    fun bindingsResolved(left: String, right: String): Boolean = listOf(left, right).all { sql ->
        placeholderPositions(sql).isEmpty() && !sql.contains("#{") && !sql.contains("\${") &&
            !Regex("(?<!:):[A-Za-z_][A-Za-z0-9_]*").containsMatchIn(sql)
    }
    fun safety(request: SqlComparisonRequest, approvals: ComparisonUiApprovals, rawPresent: Boolean): ComparisonSafetyContext? {
        if (!approvals.executePair || !approvals.functions || !approvals.bindings ||
            !bindingsResolved(request.baselineSql, request.candidateSql) || rawPresent && !approvals.raw) return null
        // A user-pasted/edited pair has no proven renderer dialect; the two unknown gates require explicit approval.
        return ComparisonSafetyContext(request.fingerprint,
            ExecutionDialectGate.rawSqlRequest(request.baselineSql), ExecutionDialectGate.rawSqlRequest(request.candidateSql),
            rawPresent, approvals.raw, true, approvals.functions, approvals.unknownDialect)
    }
}
