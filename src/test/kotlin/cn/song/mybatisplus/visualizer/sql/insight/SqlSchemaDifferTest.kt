package cn.song.mybatisplus.visualizer.sql.insight

import org.junit.Assert.*
import org.junit.Test

class SqlSchemaDifferTest {
    private fun snapshot(version: String, columns: List<SqlTypedColumn>, db: String = "db") =
        SqlSchemaSnapshot(db, version, listOf(SqlTypedTable(listOf("users"), columns)))

    @Test fun `column additions removals and type changes carry impacted host mappings`() {
        val before = snapshot("v1", listOf(SqlTypedColumn("id", "INT"), SqlTypedColumn("name", "VARCHAR(20)"), SqlTypedColumn("old", "INT")))
        val after = snapshot("v2", listOf(SqlTypedColumn("id", "INT"), SqlTypedColumn("name", "VARCHAR(80)"), SqlTypedColumn("fresh", "TEXT")))
        val location = SqlSourceLocation("/project/UserMapper.xml", 20, 150, 7, "getUser")
        val usage = SqlObjectUsageAnalyzer.analyze(listOf(SqlUsageCandidate(AnalysisSnapshot("SELECT u.name,u.old FROM users u", databaseContext = "db"), location, SqlCandidateKind.MAPPER_STATEMENT)))
        val diff = SqlSchemaDiffer.compare(before, after, usage)
        assertTrue(diff.comparable)
        assertEquals(setOf(SqlSchemaChangeKind.TYPE_CHANGED, SqlSchemaChangeKind.COLUMN_ADDED, SqlSchemaChangeKind.COLUMN_REMOVED), diff.changes.map { it.kind }.toSet())
        assertEquals(location, diff.changes.single { it.kind == SqlSchemaChangeKind.TYPE_CHANGED }.affected.single().location)
        assertTrue(diff.changes.single { it.kind == SqlSchemaChangeKind.COLUMN_ADDED }.affected.isEmpty())
    }

    @Test fun `missing snapshot identity version and unknown types do not invent differences`() {
        val a = snapshot("v1", listOf(SqlTypedColumn("id", null)))
        val b = snapshot("v2", listOf(SqlTypedColumn("id", "INT")))
        assertTrue(SqlSchemaDiffer.compare(null, b).changes.isEmpty())
        assertFalse(SqlSchemaDiffer.compare(a, b.copy(databaseContext = "other")).comparable)
        assertFalse(SqlSchemaDiffer.compare(a, b.copy(version = "v1")).comparable)
        val unknown = SqlSchemaDiffer.compare(a, b)
        assertTrue(unknown.changes.isEmpty())
        assertTrue(unknown.diagnostics.any { it.code == "TYPE_UNKNOWN" })
    }

    @Test fun `quoted case remains distinct and unknown usage context is incomplete`() {
        val a = snapshot("v1", listOf(SqlTypedColumn("\"Id\"", "INT")))
        val b = snapshot("v2", listOf(SqlTypedColumn("id", "INT")))
        val usage = SqlObjectUsageAnalyzer.analyze(listOf(SqlUsageCandidate(AnalysisSnapshot("SELECT u.id FROM users u"), SqlSourceLocation("/x", 0, 10), SqlCandidateKind.SQL_FILE)))
        val result = SqlSchemaDiffer.compare(a, b, usage)
        assertEquals(2, result.changes.size)
        assertTrue(result.changes.all { it.affected.isEmpty() })
        assertFalse(result.impactComplete)
    }

    @Test fun `cross database usage cannot claim exhaustive mapping impact`() {
        val a = snapshot("v1", listOf(SqlTypedColumn("id", "INT")))
        val b = snapshot("v2", listOf(SqlTypedColumn("id", "BIGINT")))
        val usage = SqlObjectUsageAnalyzer.analyze(listOf(SqlUsageCandidate(AnalysisSnapshot("SELECT u.id FROM users u", databaseContext = "other"), SqlSourceLocation("/other", 0, 10), SqlCandidateKind.SQL_FILE)))
        val report = SqlSchemaDiffer.compare(a, b, usage)
        assertTrue(report.changes.single().affected.isEmpty())
        assertFalse(report.impactComplete)
        assertTrue(report.diagnostics.any { it.code == "IMPACT_COVERAGE_PARTIAL" })
    }

    @Test fun `nullability changes expose both values without inventing unknown transitions`() {
        for ((before, after) in listOf(false to true, true to false)) {
            val diff = SqlSchemaDiffer.compare(snapshot("v1", listOf(SqlTypedColumn("id", "INT", before))),
                snapshot("v2", listOf(SqlTypedColumn("id", "INT", after))))
            val change = diff.changes.single()
            assertEquals(SqlSchemaChangeKind.NULLABILITY_CHANGED, change.kind)
            assertEquals(before, change.beforeNullable)
            assertEquals(after, change.afterNullable)
            assertEquals("INT", change.beforeType)
            assertEquals("INT", change.afterType)
        }
        val unknown = SqlSchemaDiffer.compare(snapshot("v1", listOf(SqlTypedColumn("id", "INT", null))),
            snapshot("v2", listOf(SqlTypedColumn("id", "INT", true))))
        assertTrue(unknown.changes.none { it.kind == SqlSchemaChangeKind.NULLABILITY_CHANGED })
        val typeOnly = SqlSchemaDiffer.compare(snapshot("v1", listOf(SqlTypedColumn("id", "INT", true))),
            snapshot("v2", listOf(SqlTypedColumn("id", "BIGINT", true)))).changes.single()
        assertNull(typeOnly.beforeNullable)
        assertNull(typeOnly.afterNullable)
    }

    @Test fun `duplicate snapshots and cancellation do not publish partial diffs`() {
        val a = snapshot("v1", listOf(SqlTypedColumn("id", "INT")))
        val b = snapshot("v2", listOf(SqlTypedColumn("id", "BIGINT")))
        assertFalse(SqlSchemaDiffer.compare(a, b.copy(tables = b.tables + b.tables)).comparable)
        val cancelled = SqlSchemaDiffer.compare(a, b, isCancelled = { true })
        assertTrue(cancelled.changes.isEmpty())
        assertEquals("CANCELLED", cancelled.diagnostics.single().code)
    }
}
