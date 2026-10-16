package cn.song.mybatisplus.visualizer.sql.insight

import org.junit.Assert.*
import org.junit.Test

/** Real counterexamples from the independent audit; intentionally added before production fixes. */
class SqlSemanticAuditRegressionTest {
    private fun analyze(sql: String, dialect: SqlInsightDialect = SqlInsightDialect.POSTGRESQL) =
        SqlSemanticAnalyzer.analyze(AnalysisSnapshot(sql, dialect))

    @Test fun `unknown star derived input remains possible bare column owner`() {
        val m = analyze("SELECT id FROM a CROSS JOIN (SELECT * FROM b) d")
        assertEquals(1, m.outputFields.size)
        assertTrue("Unknown derived columns cannot be excluded from bare-column candidates", m.outputFields.single().sources.isEmpty())
        assertTrue(m.outputFields.single().quality != AnalysisQuality.CERTAIN)
        assertTrue(m.diagnostics.any { it.code == DiagnosticCode.AMBIGUOUS_COLUMN })
        assertTrue(m.dependencies.none { it.scopeId == m.rootScopeId && it.kind == DependencyKind.DIRECT_VALUE })
    }

    @Test fun `derived alias column list renames by position not original label`() {
        val m = analyze("SELECT d.a FROM (SELECT s.a,s.b FROM s) AS d(b,a)")
        assertEquals(setOf("b"), m.outputFields.single().sources.map { it.name }.toSet())
        assertEquals(AnalysisQuality.CERTAIN, m.outputFields.single().quality)
    }

    @Test fun `derived alias duplicate and excessive names never assert an edge`() {
        for (sql in listOf("SELECT d.a FROM (SELECT s.a,s.b FROM s) AS d(a,a)",
            "SELECT d.a FROM (SELECT s.a,s.b FROM s) AS d(a,b,c)")) {
            val m = analyze(sql)
            assertTrue(sql, m.outputFields.single().sources.isEmpty())
            assertTrue(sql, m.outputFields.single().quality != AnalysisQuality.CERTAIN)
        }
    }

    @Test fun `mysql sequential temp assignment is not compared against flattened base names`() {
        val m = analyze("CREATE TEMPORARY TABLE tmp AS SELECT p AS a,q AS b FROM src; UPDATE tmp SET a=b,b=a; SELECT b FROM tmp", SqlInsightDialect.MYSQL)
        assertEquals(3, m.statementScopeIds.size)
        val output = m.outputFields.single()
        // Either implement MySQL's proven left-to-right q result, or explicitly decline it. Never claim original p.
        assertTrue("Expected proven q or explicit unknown, got $output", output.quality == AnalysisQuality.UNKNOWN ||
            (output.quality == AnalysisQuality.CERTAIN && output.sources.map { it.name }.toSet() == setOf("q")))
        assertFalse(output.quality == AnalysisQuality.CERTAIN && output.sources.any { it.name == "p" })
    }

    private fun schema(version: String, type: String) = SqlSchemaSnapshot("db", version,
        listOf(SqlTypedTable(listOf("t"), listOf(SqlTypedColumn("c", type)))))

    @Test fun `schema quoted type identifiers and enum literals retain case and whitespace`() {
        for ((before, after) in listOf("\"Foo\"" to "\"foo\"", "ENUM('a')" to "ENUM('A')", "ENUM('a  b')" to "ENUM('a b')")) {
            val diff = SqlSchemaDiffer.compare(schema("v1", before), schema("v2", after))
            assertEquals("$before -> $after", 1, diff.changes.count { it.kind == SqlSchemaChangeKind.TYPE_CHANGED })
        }
        assertTrue(SqlSchemaDiffer.compare(schema("v1", "  integer "), schema("v2", "INTEGER")).changes.isEmpty())
    }

    @Test fun `metadata table budget is checked before any table traversal`() {
        var traversed = 0
        val tables = object : AbstractList<SqlTableColumns>() {
            override val size = 1_000_000
            override fun get(index: Int): SqlTableColumns { traversed++; return SqlTableColumns(listOf("irrelevant"), listOf("id")) }
        }
        val m = SqlSemanticAnalyzer.analyze(AnalysisSnapshot("SELECT t.id FROM t", databaseContext = "db", metadataVersion = "v1",
            metadata = SqlColumnMetadata("db", "v1", tables)))
        assertTrue(m.diagnostics.any { it.code == DiagnosticCode.INPUT_BUDGET_EXCEEDED })
        assertEquals(0, traversed)
        assertTrue(m.relations.isEmpty())
    }

    @Test fun `metadata cancellation is checked while indexing irrelevant tables`() {
        var traversed = 0
        val tables = object : AbstractList<SqlTableColumns>() {
            override val size = 100
            override fun get(index: Int): SqlTableColumns { traversed++; return SqlTableColumns(listOf("t$index"), listOf("id")) }
        }
        val m = SqlSemanticAnalyzer.analyze(AnalysisSnapshot("SELECT t.id FROM t", databaseContext = "db", metadataVersion = "v1",
            metadata = SqlColumnMetadata("db", "v1", tables))) { traversed >= 5 }
        assertEquals(DiagnosticCode.CANCELLED, m.diagnostics.single().code)
        assertTrue("Read $traversed metadata rows before noticing cancellation", traversed <= 6)
        assertTrue(m.scopes.isEmpty())
    }

    @Test fun `metadata aggregate column and character budgets cover unused tables too`() {
        for (tables in listOf(
            listOf(SqlTableColumns(listOf("unused"), List(60_000) { "c$it" })),
            listOf(SqlTableColumns(listOf("unused"), listOf("x".repeat(1_048_577)))),
        )) {
            val m = SqlSemanticAnalyzer.analyze(AnalysisSnapshot("SELECT 1", databaseContext = "db", metadataVersion = "v1",
                metadata = SqlColumnMetadata("db", "v1", tables)))
            assertTrue(m.diagnostics.any { it.code == DiagnosticCode.INPUT_BUDGET_EXCEEDED })
            assertTrue(m.scopes.isEmpty())
        }
    }
}
