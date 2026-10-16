package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlComparisonRequestTest {
    private fun request(a: String = "SELECT 1", b: String = "SELECT 2"): SqlComparisonRequest =
        (SqlComparisonRequest.prepare(a, b, "test-database-identity", 7) as SqlComparisonRequest.Preparation.Ready).request

    @Test
    fun `two selects produce immutable scoped request`() {
        val request = request()
        assertTrue(request.matches("SELECT 1", "SELECT 2", "test-database-identity", 7))
        assertEquals(64, request.fingerprint.length)
        assertNotEquals(request.fingerprint, request("SELECT 2", "SELECT 1").fingerprint)
    }

    @Test
    fun `writes locking reads and multi statements rejected on either side`() {
        for (sql in listOf("DELETE FROM t", "SELECT * FROM t FOR UPDATE", "SELECT 1; SELECT 2", "EXPLAIN ANALYZE SELECT 1")) {
            assertTrue(SqlComparisonRequest.prepare(sql, "SELECT 1", "ds", 1) is SqlComparisonRequest.Preparation.Rejected)
            assertTrue(SqlComparisonRequest.prepare("SELECT 1", sql, "ds", 1) is SqlComparisonRequest.Preparation.Rejected)
        }
    }

    @Test
    fun `consent is single use even after cancellation`() {
        val consent = SqlComparisonConsent(request())
        assertNull(consent.consume(false, "SELECT 1", "SELECT 2", "test-database-identity", 7))
        assertNull(consent.consume(true, "SELECT 1", "SELECT 2", "test-database-identity", 7))
    }

    @Test
    fun `changed sql or target invalidates consent`() {
        assertNull(SqlComparisonConsent(request()).consume(true, "SELECT 3", "SELECT 2", "test-database-identity", 7))
        assertNull(SqlComparisonConsent(request()).consume(true, "SELECT 1", "SELECT 2", "other", 7))
        assertNull(SqlComparisonConsent(request()).consume(true, "SELECT 1", "SELECT 2", "test-database-identity", 8))
    }

    @Test
    fun `approved exact request consumed once`() {
        val consent = SqlComparisonConsent(request())
        assertNotNull(consent.consume(true, "SELECT 1", "SELECT 2", "test-database-identity", 7))
        assertNull(consent.consume(true, "SELECT 1", "SELECT 2", "test-database-identity", 7))
    }

    @Test
    fun `unknown target and invalid budgets rejected before execution`() {
        assertTrue(SqlComparisonRequest.prepare("SELECT 1", "SELECT 2", "", 1) is SqlComparisonRequest.Preparation.Rejected)
        assertTrue(SqlComparisonRequest.prepare("SELECT 1", "SELECT 2", "ds", -1) is SqlComparisonRequest.Preparation.Rejected)
        assertTrue(SqlComparisonRequest.prepare("SELECT 1", "SELECT 2", "ds", 1, maxRows = 0) is SqlComparisonRequest.Preparation.Rejected)
        assertTrue(SqlComparisonRequest.prepare("SELECT 1", "SELECT 2", "ds", 1, timeoutSeconds = 0) is SqlComparisonRequest.Preparation.Rejected)
    }
}
