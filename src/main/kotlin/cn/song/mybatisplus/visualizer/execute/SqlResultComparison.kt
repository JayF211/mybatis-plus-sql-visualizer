package cn.song.mybatisplus.visualizer.execute

import java.math.BigDecimal

/** Result values retain SQL NULL independently of both empty text and the literal "null". */
sealed interface SqlResultValue {
    data object Null : SqlResultValue
    data class Text(val value: String) : SqlResultValue
    data class Number(val value: BigDecimal) : SqlResultValue
}

data class SqlResultColumn(val name: String, val jdbcType: Int)

data class SqlResultSnapshot(
    val columns: List<SqlResultColumn>,
    val rows: List<List<SqlResultValue>>,
    val truncated: Boolean = false,
    val failure: String? = null,
    val ordered: Boolean = false,
    val contextKey: String = "",
)

enum class SqlResultComparisonStatus { MATCH, DIFFERENT, INCONCLUSIVE }

data class SqlResultComparison(
    val status: SqlResultComparisonStatus,
    val reason: String,
    val baselineRows: Int,
    val candidateRows: Int,
    val differingRows: Int? = null,
)

object SqlResultComparator {
    const val MAX_ROWS = 10_000
    const val MAX_COLUMNS = 256
    const val MAX_TEXT_CHARS = 4 * 1024 * 1024

    fun compare(
        baseline: SqlResultSnapshot,
        candidate: SqlResultSnapshot,
        ordered: Boolean = false,
        absoluteTolerance: BigDecimal = BigDecimal.ZERO,
    ): SqlResultComparison {
        fun outcome(status: SqlResultComparisonStatus, reason: String, differences: Int? = null) =
            SqlResultComparison(status, reason, baseline.rows.size, candidate.rows.size, differences)

        if (absoluteTolerance.signum() < 0) {
            return outcome(SqlResultComparisonStatus.INCONCLUSIVE, "negative_tolerance")
        }
        if (baseline.failure != null || candidate.failure != null) {
            return outcome(SqlResultComparisonStatus.INCONCLUSIVE, "execution_failed")
        }
        if (baseline.truncated || candidate.truncated) {
            return outcome(SqlResultComparisonStatus.INCONCLUSIVE, "truncated_result")
        }
        if (!withinBudget(baseline) || !withinBudget(candidate)) {
            return outcome(SqlResultComparisonStatus.INCONCLUSIVE, "invalid_or_oversized_result")
        }
        if (baseline.contextKey.isBlank() || baseline.contextKey != candidate.contextKey) {
            return outcome(SqlResultComparisonStatus.INCONCLUSIVE, "context_not_comparable")
        }
        if (ordered && (!baseline.ordered || !candidate.ordered)) {
            return outcome(SqlResultComparisonStatus.INCONCLUSIVE, "order_not_proven")
        }
        // Approximate equality is not transitive; sorting or greedy matching is not a multiset proof.
        if (!ordered && absoluteTolerance.signum() != 0) {
            return outcome(SqlResultComparisonStatus.INCONCLUSIVE, "unordered_numeric_tolerance")
        }
        if (baseline.columns != candidate.columns) {
            return outcome(SqlResultComparisonStatus.DIFFERENT, "columns_differ")
        }
        if (baseline.rows.size != candidate.rows.size) {
            return outcome(SqlResultComparisonStatus.DIFFERENT, "row_count_differs")
        }
        if (ordered) {
            val differences = baseline.rows.indices.count { row ->
                baseline.rows[row].indices.any { col ->
                    !equalValue(baseline.rows[row][col], candidate.rows[row][col], absoluteTolerance)
                }
            }
            return if (differences == 0) outcome(SqlResultComparisonStatus.MATCH, "ordered_equal", 0)
            else outcome(SqlResultComparisonStatus.DIFFERENT, "ordered_values_differ", differences)
        }
        val remaining = HashMap<List<SqlResultValue>, Int>()
        for (row in baseline.rows) {
            val key = canonicalRow(row)
            remaining[key] = (remaining[key] ?: 0) + 1
        }
        var differences = 0
        for (row in candidate.rows) {
            val key = canonicalRow(row)
            val count = remaining[key] ?: 0
            if (count == 0) differences++
            else if (count == 1) remaining.remove(key)
            else remaining[key] = count - 1
        }
        return if (differences == 0) outcome(SqlResultComparisonStatus.MATCH, "multiset_equal", 0)
        else outcome(SqlResultComparisonStatus.DIFFERENT, "multiset_values_differ", differences)
    }

    private fun withinBudget(snapshot: SqlResultSnapshot): Boolean {
        if (snapshot.columns.isEmpty() || snapshot.columns.size > MAX_COLUMNS || snapshot.rows.size > MAX_ROWS) return false
        var characters = 0L
        for (column in snapshot.columns) characters += column.name.length
        for (row in snapshot.rows) {
            if (row.size != snapshot.columns.size) return false
            for (value in row) {
                characters += when (value) {
                    SqlResultValue.Null -> 0
                    is SqlResultValue.Text -> value.value.length
                    is SqlResultValue.Number -> {
                        // Huge exponents can otherwise cause allocations during canonicalization/subtraction.
                        if (value.value.precision() > 1000 || value.value.scale() !in -1000..1000) return false
                        value.value.precision()
                    }
                }
                if (characters > MAX_TEXT_CHARS) return false
            }
        }
        return characters <= MAX_TEXT_CHARS
    }

    private fun canonicalRow(row: List<SqlResultValue>): List<SqlResultValue> = row.map {
        if (it is SqlResultValue.Number) SqlResultValue.Number(it.value.stripTrailingZeros()) else it
    }

    private fun equalValue(a: SqlResultValue, b: SqlResultValue, tolerance: BigDecimal): Boolean =
        if (a is SqlResultValue.Number && b is SqlResultValue.Number) {
            a.value.subtract(b.value).abs().compareTo(tolerance) <= 0
        } else a == b
}
