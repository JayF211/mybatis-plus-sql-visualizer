package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CancellationException

class SqlResultCollectorTest {
    private fun collect(sql: String, maxRows: Int = 1000, maxCharacters: Int = 4096): SqlResultSnapshot {
        Class.forName("org.h2.Driver")
        return DriverManager.getConnection("jdbc:h2:mem:insight_${UUID.randomUUID()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use {
                    SqlResultCollector.collect(it, "local-fixture-v1", maxRows = maxRows, maxCharacters = maxCharacters)
                }
            }
        }
    }

    @Test
    fun `real JDBC preserves null empty and literal null independently`() {
        val result = collect("SELECT CAST(NULL AS VARCHAR) AS a, '' AS b, 'null' AS c")
        assertFalse(result.truncated)
        assertEquals(SqlResultValue.Null, result.rows.single()[0])
        assertEquals(SqlResultValue.Text(""), result.rows.single()[1])
        assertEquals(SqlResultValue.Text("null"), result.rows.single()[2])
    }

    @Test
    fun `real JDBC preserves decimals without double rounding`() {
        val result = collect("SELECT CAST(123456789012345678.123 AS DECIMAL(21,3)) AS amount")
        assertEquals("123456789012345678.123", (result.rows.single().single() as SqlResultValue.Number).value.toPlainString())
    }

    @Test
    fun `extra row records truncation instead of claiming equal prefixes`() {
        val complete = collect("SELECT X FROM SYSTEM_RANGE(1,2)")
        val capped = collect("SELECT X FROM SYSTEM_RANGE(1,3)", maxRows = 2)
        assertEquals(2, capped.rows.size)
        assertTrue(capped.truncated)
        assertEquals(SqlResultComparisonStatus.INCONCLUSIVE, SqlResultComparator.compare(complete, capped).status)
    }

    @Test
    fun `exact row limit is not truncated`() {
        val result = collect("SELECT X FROM SYSTEM_RANGE(1,2)", maxRows = 2)
        assertEquals(2, result.rows.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `single large cell respects character budget`() {
        val result = collect("SELECT REPEAT('x',10000) AS value_text", maxCharacters = 32)
        assertTrue(result.truncated)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `unsupported binary types do not become comparable strings`() {
        val result = collect("SELECT CAST(X'01FF' AS VARBINARY) AS value_bytes")
        assertTrue(result.failure.orEmpty().startsWith("unsupported_jdbc_type:"))
        assertEquals(SqlResultComparisonStatus.INCONCLUSIVE, SqlResultComparator.compare(result, result).status)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates without a partial success`() {
        Class.forName("org.h2.Driver")
        DriverManager.getConnection("jdbc:h2:mem:insight_cancel_${UUID.randomUUID()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { result ->
                    SqlResultCollector.collect(result, "fixture", checkCancelled = { throw CancellationException() })
                }
            }
        }
    }
}
