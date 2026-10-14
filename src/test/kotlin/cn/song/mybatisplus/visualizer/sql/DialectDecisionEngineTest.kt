package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialectDecisionEngineTest {
    private fun ds(id: String, dialect: String?) = DialectDecisionEngine.Candidate(id, dialect)

    @Test
    fun manualValueWinsWithoutBorrowingProjectEvidence() {
        val decision = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            manualDialect = "Oracle", capturedDialect = "MySQL",
            explicitScope = true, scopeCandidates = listOf(ds("one", "PostgreSQL")),
        ))
        assertEquals(DialectDecisionEngine.Status.MANUAL, decision.status)
        assertEquals("Oracle", decision.dialectName)
        assertEquals(DialectDecisionEngine.Source.MANUAL, decision.source)
        assertTrue(decision.dataSourceIds.isEmpty())
    }

    @Test
    fun liveCapturedDialectIsExactButDecisionContainsNoUrlOrCredentials() {
        val decision = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            capturedDialect = "PostgreSQL", ideaDialect = "MySQL",
            projectCandidates = listOf(ds("mysql", "MySQL")),
        ))
        assertEquals(DialectDecisionEngine.Status.RESOLVED, decision.status)
        assertEquals(DialectDecisionEngine.Confidence.EXACT, decision.confidence)
        assertEquals(DialectDecisionEngine.Source.CAPTURED_URL, decision.source)
        assertEquals("PostgreSQL", decision.dialectName)
        assertFalse(decision.toString().contains("jdbc:"))
    }

    @Test
    fun explicitScopeAndIdeaDialectDisagreementIsConflict() {
        val decision = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            explicitScope = true, scopeCandidates = listOf(ds("my", "MySQL")),
            ideaDialect = "PostgreSQL",
        ))
        assertEquals(DialectDecisionEngine.Status.CONFLICT, decision.status)
        assertNull(decision.dialectName)
        assertEquals(listOf("my"), decision.dataSourceIds)
        assertTrue(decision.evidence.containsAll(listOf(
            DialectDecisionEngine.Source.IDEA_SCOPE, DialectDecisionEngine.Source.IDEA_DIALECT,
        )))
    }

    @Test
    fun multipleSourcesSameDialectKeepCandidatesWithoutInventingSingleBinding() {
        val decision = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            explicitScope = true,
            scopeCandidates = listOf(ds("b", "PostgreSQL"), ds("a", "PostgreSQL")),
        ))
        assertEquals(DialectDecisionEngine.Status.RESOLVED, decision.status)
        assertEquals(DialectDecisionEngine.Confidence.CONSENSUS, decision.confidence)
        assertEquals("PostgreSQL", decision.dialectName)
        assertEquals(listOf("a", "b"), decision.dataSourceIds)
    }

    @Test
    fun mixedScopeNeverPicksFirstCandidateAndOrderDoesNotMatter() {
        val a = ds("a", "MySQL")
        val b = ds("b", "PostgreSQL")
        val first = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            explicitScope = true, scopeCandidates = listOf(a, b),
        ))
        val reverse = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            explicitScope = true, scopeCandidates = listOf(b, a),
        ))
        assertEquals(first, reverse)
        assertEquals(DialectDecisionEngine.Status.AMBIGUOUS, first.status)
        assertNull(first.dialectName)
    }

    @Test
    fun defaultAllSourcesIsNotAnExplicitBinding() {
        val decision = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            explicitScope = false, scopeCandidates = listOf(ds("ignored", "MySQL")),
            projectCandidates = listOf(ds("pg-b", "PostgreSQL"), ds("pg-a", "PostgreSQL")),
        ))
        assertEquals(DialectDecisionEngine.Source.PROJECT_CONSENSUS, decision.source)
        assertEquals(DialectDecisionEngine.Confidence.CONSENSUS, decision.confidence)
        assertEquals(listOf("pg-a", "pg-b"), decision.dataSourceIds)
    }

    @Test
    fun projectConflictCannotBeOverriddenByLastAgentUrl() {
        val decision = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            projectCandidates = listOf(ds("a", "MySQL"), ds("b", "Oracle")),
            lastAgentDialect = "MySQL", singleModuleProven = false,
        ))
        assertEquals(DialectDecisionEngine.Status.AMBIGUOUS, decision.status)
        assertNull(decision.dialectName)
    }

    @Test
    fun moduleLearningBeatsProjectWideGuessButNotExplicitIdeaDialect() {
        val learned = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            learnedModule = ds("module-pg", "PostgreSQL"),
            projectCandidates = listOf(ds("my", "MySQL"), ds("pg", "PostgreSQL")),
        ))
        assertEquals("PostgreSQL", learned.dialectName)
        assertEquals(DialectDecisionEngine.Source.LEARNED_MODULE, learned.source)
        val configured = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            ideaDialect = "Oracle", learnedModule = ds("module-pg", "PostgreSQL"),
        ))
        assertEquals("Oracle", configured.dialectName)
        assertEquals(DialectDecisionEngine.Source.IDEA_DIALECT, configured.source)
    }

    @Test
    fun oldHistoryBadgeAloneIsUnavailableNotExact() {
        val decision = DialectDecisionEngine.decide(DialectDecisionEngine.Input())
        assertEquals(DialectDecisionEngine.Status.UNAVAILABLE, decision.status)
        assertEquals(DialectDecisionEngine.Confidence.FALLBACK, decision.confidence)
        assertNull(decision.dialectName)
        assertEquals("MySQL", decision.compatibilityFallbackName)
        assertTrue(decision.evidence.isEmpty())
    }

    @Test
    fun unknownCandidateAndGenericDialectAreNotAcceptedAsExact() {
        val decision = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            explicitScope = true, scopeCandidates = listOf(ds("unknown", null)),
            ideaDialect = "Generic SQL",
        ))
        assertEquals(DialectDecisionEngine.Status.UNAVAILABLE, decision.status)
        assertNull(decision.dialectName)
    }

    @Test
    fun actualExecutionTargetIsCheckedSeparatelyFromRenderingVote() {
        val manual = DialectDecisionEngine.decide(DialectDecisionEngine.Input(manualDialect = "Oracle"))
        assertEquals(DialectDecisionEngine.TargetStatus.MANUAL_CONFLICT,
            DialectDecisionEngine.checkTarget(manual, "MySQL").status)
        assertEquals(DialectDecisionEngine.TargetStatus.MATCH,
            DialectDecisionEngine.checkTarget(manual, "Oracle").status)
        val automatic = DialectDecisionEngine.decide(DialectDecisionEngine.Input(ideaDialect = "PostgreSQL"))
        assertEquals(DialectDecisionEngine.TargetStatus.AUTO_RERENDER_REQUIRED,
            DialectDecisionEngine.checkTarget(automatic, "MySQL").status)
        assertEquals(DialectDecisionEngine.TargetStatus.TARGET_UNKNOWN,
            DialectDecisionEngine.checkTarget(automatic, null).status)
        val unavailable = DialectDecisionEngine.decide(DialectDecisionEngine.Input())
        assertEquals(DialectDecisionEngine.TargetStatus.AUTO_RERENDER_REQUIRED,
            DialectDecisionEngine.checkTarget(unavailable, "MySQL").status)
    }
}
