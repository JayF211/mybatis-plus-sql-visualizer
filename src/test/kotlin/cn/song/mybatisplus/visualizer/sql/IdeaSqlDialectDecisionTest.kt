package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeaSqlDialectDecisionTest {
    private fun candidate(id: String, dialect: String?) = IdeaSqlContextBridge.DataSourceCandidate(
        id, id, dialect, dialect, true,
    )
    private fun snapshot(
        origin: IdeaSqlContextBridge.ScopeOrigin,
        candidates: List<IdeaSqlContextBridge.DataSourceCandidate>,
        effective: String? = null,
    ) = IdeaSqlContextBridge.Snapshot(
        IdeaSqlContextBridge.Availability.AVAILABLE, true, origin,
        configuredScopePresent = origin == IdeaSqlContextBridge.ScopeOrigin.CONFIGURED_RESOLUTION,
        providerCandidateIds = if (origin == IdeaSqlContextBridge.ScopeOrigin.PROVIDED) candidates.map { it.stableId } else emptyList(),
        finalCandidates = candidates,
        effectiveDialectId = effective,
        configuredDialectId = null,
        immediateDialectId = null,
    )

    @Test
    fun `provider candidate is exact platform source and conflicts with effective dialect`() {
        val resolved = IdeaSqlDialectDecision.decide(snapshot = snapshot(
            IdeaSqlContextBridge.ScopeOrigin.PROVIDED, listOf(candidate("pg", "PostgreSQL")), "PostgreSQL"))
        assertEquals(DialectDecisionEngine.Source.IDEA_PROVIDER, resolved.source)
        assertEquals("PostgreSQL", resolved.dialectName)
        assertTrue(resolved.evidence.contains(DialectDecisionEngine.Source.IDEA_EFFECTIVE_DIALECT))

        val conflict = IdeaSqlDialectDecision.decide(snapshot = snapshot(
            IdeaSqlContextBridge.ScopeOrigin.PROVIDED, listOf(candidate("my", "MySQL")), "PostgreSQL"))
        assertEquals(DialectDecisionEngine.Status.CONFLICT, conflict.status)
        assertNull(conflict.dialectName)
    }

    @Test
    fun `configured multi-source conflict never chooses first datasource`() {
        val decision = IdeaSqlDialectDecision.decide(snapshot = snapshot(
            IdeaSqlContextBridge.ScopeOrigin.CONFIGURED_RESOLUTION,
            listOf(candidate("a", "MySQL"), candidate("b", "Oracle")),
        ))
        assertEquals(DialectDecisionEngine.Status.AMBIGUOUS, decision.status)
        assertEquals(DialectDecisionEngine.Source.IDEA_CONFIGURED_SCOPE, decision.source)
        assertNull(decision.dialectName)
    }

    @Test
    fun `default all sources stays project consensus rather than explicit binding`() {
        val decision = IdeaSqlDialectDecision.decide(snapshot = snapshot(
            IdeaSqlContextBridge.ScopeOrigin.DEFAULT_ALL_DATA_SOURCES,
            listOf(candidate("pg-b", "PostgreSQL"), candidate("pg-a", "PostgreSQL")),
        ))
        assertEquals(DialectDecisionEngine.Source.PROJECT_CONSENSUS, decision.source)
        assertEquals(DialectDecisionEngine.Confidence.CONSENSUS, decision.confidence)
        assertEquals(listOf("pg-a", "pg-b"), decision.dataSourceIds)
    }

    @Test
    fun `all unknown scope candidates can use effective IDEA dialect without inventing target dialect`() {
        val decision = IdeaSqlDialectDecision.decide(snapshot = snapshot(
            IdeaSqlContextBridge.ScopeOrigin.CONFIGURED_RESOLUTION,
            listOf(candidate("unknown", null)),
            effective = "Oracle",
        ))
        assertEquals(DialectDecisionEngine.Source.IDEA_EFFECTIVE_DIALECT, decision.source)
        assertEquals("Oracle", decision.dialectName)
        assertEquals(listOf("unknown"), decision.dataSourceIds)
        assertTrue(decision.evidence.contains(DialectDecisionEngine.Source.IDEA_CONFIGURED_SCOPE))
    }

    @Test
    fun `known and unknown candidates cannot be promoted to false consensus`() {
        val decision = IdeaSqlDialectDecision.decide(snapshot = snapshot(
            IdeaSqlContextBridge.ScopeOrigin.PROVIDED,
            listOf(candidate("pg", "PostgreSQL"), candidate("unknown", null)),
        ))
        assertEquals(DialectDecisionEngine.Status.AMBIGUOUS, decision.status)
        assertNull(decision.dialectName)
    }

    @Test
    fun `deleted or invalid provider cannot claim strong dialect from stale URL`() {
        val stale = IdeaSqlContextBridge.DataSourceCandidate(
            "deleted-uuid", "deleted", "MySQL", "MySQL", false)
        val decision = IdeaSqlDialectDecision.decide(snapshot = snapshot(
            IdeaSqlContextBridge.ScopeOrigin.PROVIDED, listOf(stale)))
        assertEquals(DialectDecisionEngine.Status.UNAVAILABLE, decision.status)
        assertNull(decision.dialectName)
        assertEquals(listOf("deleted-uuid"), decision.dataSourceIds)
    }

    @Test
    fun `Generic effective dialect supplies no MySQL evidence`() {
        val decision = IdeaSqlDialectDecision.decide(snapshot = snapshot(
            IdeaSqlContextBridge.ScopeOrigin.NONE, emptyList(), "GenericSQL"))
        assertEquals(DialectDecisionEngine.Status.UNAVAILABLE, decision.status)
        assertNull(decision.dialectName)
        assertEquals("MySQL", decision.compatibilityFallbackName)
    }
}
