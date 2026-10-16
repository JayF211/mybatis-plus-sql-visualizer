package cn.song.mybatisplus.visualizer.sql.insight

import org.junit.Assert.*
import org.junit.Test

class SqlOptimizationAdvisorTest {
    private fun advice(sql: String) = SqlOptimizationAdvisor.analyze(AnalysisSnapshot(sql))

    @Test fun `three structural rules include evidence and prerequisites not rewrite claims`() {
        val result = advice("SELECT * FROM users u CROSS JOIN teams t; SELECT u.id FROM users u WHERE lower(u.name)='bob'")
        assertEquals(setOf("SI.WIDE_STAR_PROJECTION", "SI.CARTESIAN_JOIN", "SI.NON_SARG_COLUMN_FUNCTION"), result.candidates.map { it.ruleId }.toSet())
        assertTrue(result.candidates.all { it.expression.isNotBlank() && it.evidence.isNotBlank() && it.preconditions.isNotEmpty() && it.sourceRange == null })
        val function = result.candidates.single { it.ruleId == "SI.NON_SARG_COLUMN_FUNCTION" }
        assertEquals(1, function.statementIndex)
        assertTrue(function.preconditions.any { it.contains("indexes") })
    }

    @Test fun `projection functions constant functions comments and strings do not fabricate where evidence`() {
        for (sql in listOf("SELECT lower(u.name) FROM users u WHERE u.id=1", "SELECT u.id FROM users u WHERE u.name=lower('BOB')", "SELECT 'SELECT * WHERE lower(u.id)' FROM users u", "/* SELECT * */ SELECT u.id FROM users u")) {
            assertTrue(sql, advice(sql).candidates.isEmpty())
        }
    }

    @Test fun `join predicate old style where and count star are not wide or cartesian findings`() {
        for (sql in listOf("SELECT u.id FROM users u JOIN teams t ON u.team_id=t.id", "SELECT u.id FROM users u,teams t WHERE u.team_id=t.id", "SELECT count(*) FROM users u")) {
            assertTrue(sql, advice(sql).candidates.isEmpty())
        }
    }

    @Test fun `unparseable input and cancellation never fallback to regex claims`() {
        assertTrue(advice("SELEC * WHERE lower(x)").candidates.isEmpty())
        assertEquals("PARSE_ERROR", advice("SELEC * WHERE lower(x)").diagnostics.single().code)
        val result = SqlOptimizationAdvisor.analyze(AnalysisSnapshot("SELECT * FROM a,b")) { true }
        assertTrue(result.cancelled)
        assertTrue(result.candidates.isEmpty())
    }
}
