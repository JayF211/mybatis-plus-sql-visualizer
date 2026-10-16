package cn.song.mybatisplus.visualizer.sql.insight

import org.junit.Assert.*
import org.junit.Test

class SqlObjectUsageAnalyzerTest {
    private fun candidate(sql: String, db: String? = "db", path: String = "/project/Mapper.java") = SqlUsageCandidate(
        AnalysisSnapshot(sql, databaseContext = db), SqlSourceLocation(path, 10, 120, 5, "findUsers"), SqlCandidateKind.JAVA_CONSTANT)

    @Test fun `usage distinguishes projection predicate write and host location`() {
        val report = SqlObjectUsageAnalyzer.analyze(listOf(candidate("SELECT u.name FROM users u WHERE u.active=1"), candidate("UPDATE users SET name=backup WHERE id=1")))
        val name = SqlObjectUsageAnalyzer.findUsages(report, SqlObjectQuery("db", listOf("users"), "name"))
        assertEquals(2, name.references.size)
        assertTrue(name.references.any { DependencyKind.DIRECT_VALUE in it.dependencyKinds })
        assertTrue(name.references.any { DependencyKind.ASSIGNMENT in it.dependencyKinds })
        val active = SqlObjectUsageAnalyzer.findUsages(report, SqlObjectQuery("db", listOf("users"), "active"))
        assertEquals(setOf(DependencyKind.FILTER), active.references.single().dependencyKinds)
        assertEquals(10, active.references.single().location.startOffset)
        assertEquals("/project/Mapper.java", active.references.single().location.filePath)
    }

    @Test fun `schema database and quoted identities do not collapse`() {
        val report = SqlObjectUsageAnalyzer.analyze(listOf(candidate("SELECT u.id FROM s.users u"), candidate("SELECT u.id FROM s.users u", "other"), candidate("SELECT u.id FROM \"Users\" u")))
        assertEquals(1, SqlObjectUsageAnalyzer.findUsages(report, SqlObjectQuery("db", listOf("s", "users"), "id")).references.size)
        assertTrue(SqlObjectUsageAnalyzer.findUsages(report, SqlObjectQuery("db", listOf("users"), "id")).references.isEmpty())
        assertEquals(1, SqlObjectUsageAnalyzer.findUsages(report, SqlObjectQuery("db", listOf("\"Users\""), "id")).references.size)
        assertTrue(SqlObjectUsageAnalyzer.findUsages(report, SqlObjectQuery(null, listOf("s", "users"))).references.isEmpty())
    }

    @Test fun `cte virtual names are not physical use and unknown context is incomplete`() {
        val report = SqlObjectUsageAnalyzer.analyze(listOf(candidate("WITH c AS (SELECT u.name FROM users u) SELECT c.name FROM c", null)))
        assertTrue(report.usages.none { it.tableName == "c" })
        val matches = SqlObjectUsageAnalyzer.findUsages(report, SqlObjectQuery("db", listOf("users")))
        assertFalse(matches.complete)
        assertTrue(matches.references.isEmpty())
        assertTrue(matches.diagnostics.any { it.code == "USAGE_CONTEXT_UNKNOWN" })
    }

    @Test fun `budget and cancellation report partial coverage without claiming absence`() {
        val all = listOf(candidate("SELECT a.id FROM a"), candidate("SELECT b.id FROM b"))
        val limited = SqlObjectUsageAnalyzer.analyze(all, limits = SqlUsageLimits(maxCandidates = 1))
        assertTrue(limited.coverage.truncated)
        assertEquals(1, limited.candidates.size)
        val cancelled = SqlObjectUsageAnalyzer.analyze(all, { true })
        assertTrue(cancelled.coverage.cancelled)
        assertTrue(cancelled.usages.isEmpty())
    }
}
