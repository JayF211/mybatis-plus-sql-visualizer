package cn.song.mybatisplus.visualizer.sql.insight

import org.junit.Assert.*
import org.junit.Test

class SqlSemanticMergeAndScriptTest {
    private fun analyze(sql: String) = SqlSemanticAnalyzer.analyze(AnalysisSnapshot(sql))
    private fun names(p: SemanticProjection) = p.sources.map { it.name }.toSet()

    @Test fun `merge update insert and delete branches keep predicates out of assignments`() {
        val m = analyze("MERGE INTO target t USING source s ON t.id=s.id WHEN MATCHED AND s.dead=1 THEN DELETE WHEN MATCHED AND s.active=1 THEN UPDATE SET name=s.title WHEN NOT MATCHED THEN INSERT (id,name) VALUES (s.id,s.title)")
        assertEquals(SqlOperation.MERGE, m.scopes.single { it.id == m.rootScopeId }.operation)
        val writes = m.dependencies.filter { it.kind == DependencyKind.ASSIGNMENT }
        assertEquals(3, writes.size)
        assertEquals(setOf("id", "name"), writes.map { it.targetColumn!!.name }.toSet())
        assertTrue(writes.all { it.branchId != null })
        assertEquals(2, writes.map { it.branchId }.distinct().size)
        assertTrue(writes.none { e -> e.sources.any { it.name == "active" || it.name == "dead" } })
        assertTrue(m.dependencies.any { it.kind == DependencyKind.MERGE_MATCH })
        assertTrue(m.relations.any { it.name == "target" && TableRole.DELETE in it.roles && TableRole.WRITE in it.roles })
    }

    @Test fun `merge derived source maps projected column rather than relation name`() {
        val m = analyze("MERGE INTO target t USING (SELECT u.id AS k,u.name AS title FROM users u) s ON t.id=s.k WHEN MATCHED THEN UPDATE SET name=s.title")
        val edge = m.dependencies.single { it.kind == DependencyKind.ASSIGNMENT }
        assertEquals("name", edge.sources.single().name)
        assertEquals("users", m.relations.single { it.id == edge.sources.single().relationId }.name)
    }

    @Test fun `merge ambiguous value and invalid target do not emit guessed assignment`() {
        val ambiguous = analyze("MERGE INTO target t USING source s ON t.id=s.id WHEN MATCHED THEN UPDATE SET name=id")
        assertTrue(ambiguous.dependencies.none { it.kind == DependencyKind.ASSIGNMENT })
        val invalid = analyze("MERGE INTO target t USING source s ON t.id=s.id WHEN MATCHED THEN UPDATE SET s.name=s.title")
        assertTrue(invalid.dependencies.none { it.kind == DependencyKind.ASSIGNMENT })
        assertTrue(invalid.diagnostics.any { it.code == DiagnosticCode.UNKNOWN_COLUMN })
    }

    @Test fun `temp ctas insert and later select preserve column level ordered origins`() {
        val m = analyze("CREATE TEMP TABLE stage AS SELECT u.id AS k,u.name AS v FROM users u; INSERT INTO stage (k,v) SELECT a.code,a.title FROM archive a; SELECT s.v,s.k FROM stage s")
        assertEquals(3, m.statementScopeIds.size)
        assertEquals(listOf(setOf("name", "title"), setOf("id", "code")), m.outputFields.map(::names))
        assertTrue(m.relations.any { it.kind == RelationKind.TEMPORARY })
        assertTrue(m.outputFields.flatMap { it.sources }.none { c -> m.relations.single { it.id == c.relationId }.kind == RelationKind.TEMPORARY })
    }

    @Test fun `temp declared columns and conditional update retain old plus new possible sources`() {
        val m = analyze("CREATE TEMP TABLE stage (id INT, a INT, b INT); INSERT INTO stage(id,a,b) SELECT u.id,u.old_value,u.new_value FROM users u; UPDATE stage SET a=b WHERE id=1; SELECT a,b FROM stage")
        assertEquals(setOf("old_value", "new_value"), names(m.outputFields[0]))
        assertEquals(AnalysisQuality.CONDITIONAL, m.outputFields[0].quality)
        assertEquals(setOf("new_value"), names(m.outputFields[1]))
        assertEquals(AnalysisQuality.CERTAIN, m.outputFields[1].quality)
    }

    @Test fun `unknown write contaminates temporary column and is not repaired by reading`() {
        val m = analyze("CREATE TEMP TABLE stage AS SELECT u.id,u.a FROM users u; UPDATE stage SET a=unknown_function(a); SELECT a,id FROM stage")
        assertEquals(AnalysisQuality.UNKNOWN, m.outputFields[0].quality)
        assertTrue(m.outputFields[0].sources.isEmpty())
        assertEquals(setOf("id"), names(m.outputFields[1]))
    }

    @Test fun `drop recreate has new lifecycle and prior statement projections stay immutable`() {
        val m = analyze("CREATE TEMP TABLE stage AS SELECT u.id FROM users u; SELECT id FROM stage; DROP TABLE stage; CREATE TEMP TABLE stage AS SELECT a.code AS id FROM archive a; SELECT id FROM stage")
        assertEquals(5, m.statementScopeIds.size)
        val earlier = m.scopes.single { it.id == m.statementScopeIds[1] }.projections.single()
        assertEquals(setOf("id"), names(earlier))
        assertEquals(setOf("code"), names(m.outputFields.single()))
        assertTrue(analyze("CREATE TEMP TABLE stage AS SELECT u.id FROM users u; DROP TABLE stage; SELECT id FROM stage").outputFields.single().sources.isEmpty())
    }

    @Test fun `unknown statement and invalid update target poison temporary state`() {
        val invalid = analyze("CREATE TEMP TABLE stage AS SELECT u.a FROM users u; UPDATE stage SET ghost.a=7; SELECT a FROM stage")
        assertTrue(invalid.outputFields.single().sources.isEmpty())
        assertEquals(AnalysisQuality.UNKNOWN, invalid.outputFields.single().quality)
        val unknown = analyze("CREATE TEMP TABLE stage AS SELECT u.a FROM users u; ALTER TABLE stage ADD COLUMN b INT; SELECT a FROM stage")
        assertTrue(unknown.outputFields.single().sources.isEmpty())
        assertEquals(AnalysisQuality.UNKNOWN, unknown.outputFields.single().quality)
    }

    @Test fun `full delete clears temp origins while filtered delete retains possible remaining origins`() {
        val full = analyze("CREATE TEMP TABLE stage AS SELECT u.a FROM users u; DELETE FROM stage; SELECT a FROM stage")
        assertTrue(full.outputFields.single().sources.isEmpty())
        val partial = analyze("CREATE TEMP TABLE stage AS SELECT u.a FROM users u; DELETE FROM stage WHERE a=1; SELECT a FROM stage")
        assertEquals(setOf("a"), names(partial.outputFields.single()))
    }

    @Test fun `script cancellation yields no partial statement scope ids`() {
        var n = 0
        val m = SqlSemanticAnalyzer.analyze(AnalysisSnapshot("CREATE TEMP TABLE stage AS SELECT u.id FROM users u; SELECT id FROM stage")) { ++n > 10 }
        assertTrue(m.statementScopeIds.isEmpty())
        assertTrue(m.scopes.isEmpty())
        assertEquals(DiagnosticCode.CANCELLED, m.diagnostics.single().code)
    }
}
