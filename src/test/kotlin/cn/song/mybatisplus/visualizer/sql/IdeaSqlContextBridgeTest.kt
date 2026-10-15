package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IdeaSqlContextBridgeTest {
    private fun raw(id: String, dialect: String? = null, url: String? = null) =
        IdeaSqlContextBridge.RawCandidate(id, id, url, dialect, true)

    @Test
    fun `provider source wins classification without merging configured scope`() {
        val snapshot = IdeaSqlContextBridge.fromRaw(IdeaSqlContextBridge.RawFacts(
            provided = listOf(raw("pg", "PostgreSQL")),
            finalCandidates = listOf(raw("pg", "PostgreSQL")),
            configuredScopePresent = true,
            effectiveDialectId = "PostgreSQL",
        ))
        assertEquals(IdeaSqlContextBridge.ScopeOrigin.PROVIDED, snapshot.scopeOrigin)
        assertEquals(listOf("pg"), snapshot.providerCandidateIds)
        assertEquals(listOf("pg"), snapshot.finalCandidates.map { it.stableId })
    }

    @Test
    fun `configured scope and default all sources stay distinguishable`() {
        val configured = IdeaSqlContextBridge.fromRaw(IdeaSqlContextBridge.RawFacts(
            finalCandidates = listOf(raw("oracle", "Oracle")), configuredScopePresent = true,
        ))
        assertEquals(IdeaSqlContextBridge.ScopeOrigin.UNAVAILABLE, configured.scopeOrigin)
        val defaultAll = IdeaSqlContextBridge.fromRaw(IdeaSqlContextBridge.RawFacts(
            finalCandidates = listOf(raw("my", "MySQL"), raw("pg", "PostgreSQL")),
        ))
        assertEquals(IdeaSqlContextBridge.ScopeOrigin.DEFAULT_ALL_DATA_SOURCES, defaultAll.scopeOrigin)
    }

    @Test
    fun `stable UUID deduplicates platform duplicates and URL never enters snapshot`() {
        val snapshot = IdeaSqlContextBridge.fromRaw(IdeaSqlContextBridge.RawFacts(
            finalCandidates = listOf(
                raw("uuid-1", url = "jdbc:postgresql://user:secret@host/db"),
                raw("uuid-1", "MySQL"),
            ),
        ))
        assertEquals(1, snapshot.finalCandidates.size)
        assertEquals("PostgreSQL", snapshot.finalCandidates.single().dialectName)
        assertTrue("snapshot must not expose JDBC URL", snapshot.toString().contains("jdbc:").not())
    }

    @Test
    fun `provider without UUID remains provider and cannot make other candidate unique`() {
        val snapshot = IdeaSqlContextBridge.fromRaw(IdeaSqlContextBridge.RawFacts(
            provided = listOf(IdeaSqlContextBridge.RawCandidate(null, "external", null, null, true)),
            finalCandidates = listOf(
                raw("pg-uuid", "PostgreSQL"),
                IdeaSqlContextBridge.RawCandidate(null, "external", null, null, true),
            ),
        ))
        assertEquals(IdeaSqlContextBridge.ScopeOrigin.PROVIDED, snapshot.scopeOrigin)
        val decision = IdeaSqlDialectDecision.decide(snapshot = snapshot)
        assertEquals(DialectDecisionEngine.Status.AMBIGUOUS, decision.status)
        assertNull(decision.dialectName)
        assertTrue("unidentified source cannot become persistent UUID", decision.dataSourceIds.any { it.startsWith("unidentified:") })
    }

    @Test
    fun `generic SQL is not promoted to MySQL evidence`() {
        assertNull(IdeaSqlContextBridge.pluginDialectName("GenericSQL"))
        assertNull(IdeaSqlContextBridge.pluginDialectName("SQL92"))
        assertEquals("SQL Server", IdeaSqlContextBridge.pluginDialectName("TSQL"))
    }

    @Test
    fun `unknown candidate remains in identity set without claiming consensus dialect`() {
        val snapshot = IdeaSqlContextBridge.fromRaw(IdeaSqlContextBridge.RawFacts(
            finalCandidates = listOf(raw("pg", "PostgreSQL"), raw("unknown", "VendorSQL")),
        ))
        assertEquals(2, snapshot.finalCandidates.size)
        assertEquals("PostgreSQL", snapshot.finalCandidates.first { it.stableId == "pg" }.dialectName)
        assertNull(snapshot.finalCandidates.first { it.stableId == "unknown" }.dialectName)
    }

    @Test
    fun `production bridge stays reflection isolated and reads effective dialect`() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "src/main/kotlin").isDirectory }
        val file = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/sql/IdeaSqlContextBridge.kt")
        val text = file.readText()
        assertTrue("DatabaseTools types must not be statically imported",
            !text.contains("import com.intellij.database") && !text.contains("import com.intellij.sql"))
        assertTrue(text.contains("getProvidedDataSources"))
        assertTrue(text.contains("getDataSources"))
        assertTrue(text.contains("getConfiguredMapping"))
        assertTrue("normal inference must read effective mapping", text.contains("getMapping\", Project::class.java"))
        assertTrue(text.contains("getUniqueId"))
        assertTrue(text.contains("getConnectionConfig"))
    }
}
