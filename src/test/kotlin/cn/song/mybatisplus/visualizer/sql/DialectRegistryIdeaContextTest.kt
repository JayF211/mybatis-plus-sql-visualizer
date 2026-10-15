package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DialectRegistryIdeaContextTest : BasePlatformTestCase() {
    private lateinit var snapshot: PluginSettings.State

    override fun setUp() {
        super.setUp()
        snapshot = PluginSettings.getInstance().state.copy()
        PluginSettings.getInstance().state.dialectAuto = true
        IdeaSqlContextBridge.actualSqlFileForTest = { true }
        DataSourceUrlProbe.probeCandidatesForTest = { emptyList() }
        DataSourceUrlProbe.invalidate(project)
    }

    override fun tearDown() {
        IdeaSqlContextBridge.actualSqlFileForTest = null
        IdeaSqlContextBridge.rawFactsForTest = null
        DataSourceUrlProbe.probeCandidatesForTest = null
        DataSourceUrlProbe.invalidate(project)
        PluginSettings.getInstance().loadState(snapshot)
        super.tearDown()
    }

    fun testProviderScopeFeedsProductionRegistryDecision() {
        myFixture.configureByText("Repo.java", "class Repo {}")
        IdeaSqlContextBridge.rawFactsForTest = { _, _, _ ->
            IdeaSqlContextBridge.RawFacts(
                provided = listOf(IdeaSqlContextBridge.RawCandidate(
                    "pg-uuid", "pg", "jdbc:postgresql://localhost/app", "PostgreSQL", true)),
                finalCandidates = listOf(IdeaSqlContextBridge.RawCandidate(
                    "pg-uuid", "pg", "jdbc:postgresql://localhost/app", "PostgreSQL", true)),
                configuredScopePresent = true,
                effectiveDialectId = "PostgreSQL",
            )
        }
        val decision = DialectRegistry.decision(project, myFixture.file.virtualFile, myFixture.file)
        assertEquals(DialectDecisionEngine.Source.IDEA_PROVIDER, decision.source)
        assertEquals("PostgreSQL", decision.dialectName)
        assertEquals(listOf("pg-uuid"), decision.dataSourceIds)
        assertEquals("普通 SQL 文件无需额外宿主 VirtualFile", "PostgreSQL",
            DialectRegistry.decision(project, null, myFixture.file).dialectName)
    }

    fun testDefaultAllSourcesRemainsConsensusNotExplicitBinding() {
        myFixture.configureByText("Repo.java", "class Repo {}")
        IdeaSqlContextBridge.rawFactsForTest = { _, _, _ ->
            IdeaSqlContextBridge.RawFacts(
                finalCandidates = listOf(
                    IdeaSqlContextBridge.RawCandidate("b", "b", null, "Oracle", true),
                    IdeaSqlContextBridge.RawCandidate("a", "a", null, "Oracle", true),
                ),
                effectiveDialectId = "GenericSQL",
            )
        }
        val decision = DialectRegistry.decision(project, myFixture.file.virtualFile, myFixture.file)
        assertEquals(DialectDecisionEngine.Source.PROJECT_CONSENSUS, decision.source)
        assertEquals(DialectDecisionEngine.Confidence.CONSENSUS, decision.confidence)
        assertEquals(listOf("a", "b"), decision.dataSourceIds)
    }
}
