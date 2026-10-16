package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.sql.Types

class SqlResultComparisonTest {
    private fun text(value: String) = SqlResultValue.Text(value)
    private fun number(value: String) = SqlResultValue.Number(BigDecimal(value))
    private fun snapshot(vararg values: SqlResultValue) = SqlResultSnapshot(
        listOf(SqlResultColumn("value", Types.VARCHAR)), values.map { listOf(it) }, contextKey = "fixture:v1",
    )

    @Test
    fun `unordered comparison preserves duplicate multiplicity`() {
        val baseline = snapshot(text("a"), text("a"), text("b"))
        val reordered = snapshot(text("b"), text("a"), text("a"))
        val different = snapshot(text("a"), text("b"), text("b"))
        assertEquals(SqlResultComparisonStatus.MATCH, SqlResultComparator.compare(baseline, reordered).status)
        assertEquals(SqlResultComparisonStatus.DIFFERENT, SqlResultComparator.compare(baseline, different).status)
    }

    @Test
    fun `SQL null is not empty text or literal null`() {
        val baseline = snapshot(SqlResultValue.Null)
        assertEquals(SqlResultComparisonStatus.DIFFERENT, SqlResultComparator.compare(baseline, snapshot(text(""))).status)
        assertEquals(SqlResultComparisonStatus.DIFFERENT, SqlResultComparator.compare(baseline, snapshot(text("null"))).status)
    }

    @Test
    fun `decimal scale does not change exact numeric equality`() {
        assertEquals(SqlResultComparisonStatus.MATCH,
            SqlResultComparator.compare(snapshot(number("1.00")), snapshot(number("1"))).status)
        assertEquals(SqlResultComparisonStatus.DIFFERENT,
            SqlResultComparator.compare(snapshot(number("1")), snapshot(text("1"))).status)
    }

    @Test
    fun `ordered comparison requires evidence of ordering`() {
        val a = snapshot(text("a"), text("b"))
        val b = snapshot(text("b"), text("a"))
        assertEquals("order_not_proven", SqlResultComparator.compare(a, b, ordered = true).reason)
        assertEquals(SqlResultComparisonStatus.DIFFERENT,
            SqlResultComparator.compare(a.copy(ordered = true), b.copy(ordered = true), ordered = true).status)
    }

    @Test
    fun `numeric tolerance only applies to ordered numeric values`() {
        val a = snapshot(number("1.01")).copy(ordered = true)
        val b = snapshot(number("1.02")).copy(ordered = true)
        assertEquals(SqlResultComparisonStatus.MATCH,
            SqlResultComparator.compare(a, b, true, BigDecimal("0.01")).status)
        assertEquals(SqlResultComparisonStatus.DIFFERENT,
            SqlResultComparator.compare(a, b, true, BigDecimal("0.001")).status)
        assertEquals("unordered_numeric_tolerance",
            SqlResultComparator.compare(a, b, false, BigDecimal("0.01")).reason)
        assertEquals("negative_tolerance", SqlResultComparator.compare(a, b, true, BigDecimal("-1")).reason)
    }

    @Test
    fun `incomplete or failed query cannot pass even when rows are equal`() {
        val a = snapshot(text("a"))
        assertEquals(SqlResultComparisonStatus.INCONCLUSIVE, SqlResultComparator.compare(a, a.copy(truncated = true)).status)
        assertEquals(SqlResultComparisonStatus.INCONCLUSIVE, SqlResultComparator.compare(a.copy(failure = "timeout"), a).status)
    }

    @Test
    fun `different or unknown context cannot pass`() {
        val a = snapshot(text("a"))
        assertEquals("context_not_comparable", SqlResultComparator.compare(a, a.copy(contextKey = "other")).reason)
        val unknown = a.copy(contextKey = "")
        assertEquals("context_not_comparable", SqlResultComparator.compare(unknown, unknown).reason)
    }

    @Test
    fun `column metadata differences are visible`() {
        val a = snapshot(text("a"))
        assertEquals("columns_differ", SqlResultComparator.compare(a, a.copy(columns = listOf(SqlResultColumn("other", Types.VARCHAR)))).reason)
        assertEquals("columns_differ", SqlResultComparator.compare(a, a.copy(columns = listOf(SqlResultColumn("value", Types.INTEGER)))).reason)
    }

    @Test
    fun `malformed rows and excessive dimensions are rejected`() {
        val a = snapshot(text("a"))
        assertEquals("invalid_or_oversized_result", SqlResultComparator.compare(a, a.copy(rows = listOf(emptyList()))).reason)
        assertEquals("invalid_or_oversized_result", SqlResultComparator.compare(a, a.copy(rows = List(SqlResultComparator.MAX_ROWS + 1) { listOf(text("a")) })).reason)
        val hugeNumber = snapshot(number("1E+1001"))
        assertEquals("invalid_or_oversized_result", SqlResultComparator.compare(hugeNumber, hugeNumber).reason)
    }

    @Test
    fun `empty successful result retains columns and is comparable`() {
        val empty = snapshot()
        assertEquals(SqlResultComparisonStatus.MATCH, SqlResultComparator.compare(empty, empty).status)
        assertEquals("row_count_differs", SqlResultComparator.compare(empty, snapshot(text("x"))).reason)
        assertEquals("invalid_or_oversized_result", SqlResultComparator.compare(empty.copy(columns = emptyList()), empty).reason)
    }
}
