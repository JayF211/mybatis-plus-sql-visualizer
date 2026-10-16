package cn.song.mybatisplus.visualizer.sql.insight

import org.junit.Assert.*
import org.junit.Test

class SqlSemanticExpressionsTest {
    private fun model(sql: String) = SqlSemanticAnalyzer.analyze(AnalysisSnapshot(sql))
    private fun names(columns: Set<SemanticColumn>) = columns.map { it.name }.toSet()

    @Test fun `functions and aggregate arguments map values but count star does not invent them`() {
        val m = model("SELECT lower(t.name), coalesce(t.a,t.b), sum(t.amount), count(*), count(t.id) FROM t")
        assertEquals(listOf(setOf("name"), setOf("a", "b"), setOf("amount"), emptySet<String>(), emptySet<String>()), m.outputFields.map { names(it.sources) })
        assertEquals(setOf("id"), names(m.outputFields[4].conditionSources))
        assertEquals(AnalysisQuality.CERTAIN, m.quality)
    }

    @Test fun `case switch and when are conditions not branch values`() {
        val m = model("SELECT CASE WHEN t.enabled=1 THEN t.a ELSE t.b END AS v, CASE t.kind WHEN 1 THEN t.c ELSE 0 END AS w FROM t")
        assertEquals(setOf("a", "b"), names(m.outputFields[0].sources))
        assertEquals(setOf("enabled"), names(m.outputFields[0].conditionSources))
        assertEquals(setOf("c"), names(m.outputFields[1].sources))
        assertEquals(setOf("kind"), names(m.outputFields[1].conditionSources))
        assertTrue(m.dependencies.filter { it.kind == DependencyKind.DERIVED_VALUE }.none { e -> e.sources.any { it.name == "enabled" } })
        assertEquals(2, m.dependencies.count { it.kind == DependencyKind.CONDITION })
    }

    @Test fun `filtered aggregates separate predicate influence from amount`() {
        val m = model("SELECT sum(t.amount) FILTER (WHERE t.active=1), count(*) FILTER (WHERE t.active=1) FROM t")
        assertEquals(setOf("amount"), names(m.outputFields[0].sources))
        assertEquals(setOf("active"), names(m.outputFields[0].conditionSources))
        assertTrue(m.outputFields[1].sources.isEmpty())
        assertEquals(setOf("active"), names(m.outputFields[1].conditionSources))
    }

    @Test fun `correlated aggregate scalar resolves explicit outer alias without leaking filter into value`() {
        val m = model("SELECT u.id, (SELECT max(o.amount) FROM orders o WHERE o.user_id=u.id) AS total FROM users u")
        assertEquals(setOf("amount"), names(m.outputFields[1].sources))
        assertEquals(setOf("user_id", "id"), names(m.outputFields[1].conditionSources))
        assertEquals(AnalysisQuality.CERTAIN, m.outputFields[1].quality)
        assertEquals("orders", m.relations.single { it.id == m.outputFields[1].sources.single().relationId }.name)
    }

    @Test fun `scalar values require one column and proven at most one row`() {
        val constant = model("SELECT (SELECT 7) AS v FROM t")
        assertEquals(AnalysisQuality.CERTAIN, constant.outputFields.single().quality)
        val multi = model("SELECT (SELECT o.amount FROM orders o) FROM users u")
        assertTrue(multi.outputFields.single().sources.isEmpty())
        assertTrue(multi.diagnostics.any { it.code == DiagnosticCode.SCALAR_CARDINALITY_UNKNOWN })
        val badWidth = model("SELECT (SELECT max(o.a),max(o.b) FROM orders o) FROM users u")
        assertTrue(badWidth.outputFields.single().sources.isEmpty())
        assertTrue(badWidth.diagnostics.any { it.code == DiagnosticCode.COLUMN_COUNT_MISMATCH })
    }

    @Test fun `inner alias shadows outer and unqualified correlated guess is rejected`() {
        val m = model("SELECT (SELECT max(u.amount) FROM orders u WHERE u.user_id=1) FROM users u")
        assertEquals("orders", m.relations.single { it.id == m.outputFields.single().sources.single().relationId }.name)
        val unsafe = model("SELECT (SELECT max(missing) FROM orders o) FROM users u")
        assertTrue(unsafe.outputFields.single().sources.isEmpty())
        assertTrue(unsafe.diagnostics.any { it.code == DiagnosticCode.AMBIGUOUS_COLUMN })
    }

    @Test fun `exists projection has condition sources not nested values`() {
        val m = model("SELECT EXISTS (SELECT 1 FROM orders o WHERE o.user_id=u.id) FROM users u")
        assertTrue(m.outputFields.single().sources.isEmpty())
        assertEquals(setOf("user_id", "id"), names(m.outputFields.single().conditionSources))
    }

    @Test fun `unresolved correlated predicate downgrades scalar output instead of claiming certainty`() {
        val m = model("SELECT (SELECT max(o.amount) FROM orders o WHERE ghost.id=o.id) FROM users u")
        assertTrue(m.outputFields.single().sources.isEmpty())
        assertTrue(m.outputFields.single().quality != AnalysisQuality.CERTAIN)
        assertTrue(m.diagnostics.any { it.code == DiagnosticCode.UNKNOWN_RELATION })
    }

    @Test fun `unknown functions remain opaque and cancellation drops expanded graph`() {
        val m = model("SELECT custom_secret(t.a) + t.b FROM t")
        assertTrue(m.outputFields.single().sources.isEmpty())
        assertTrue(m.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_EXPRESSION })
        var calls = 0
        val cancelled = SqlSemanticAnalyzer.analyze(AnalysisSnapshot("SELECT CASE WHEN t.a=1 THEN lower(t.b) ELSE upper(t.c) END FROM t")) { ++calls > 8 }
        assertEquals(DiagnosticCode.CANCELLED, cancelled.diagnostics.single().code)
        assertTrue(cancelled.scopes.isEmpty())
    }
}
