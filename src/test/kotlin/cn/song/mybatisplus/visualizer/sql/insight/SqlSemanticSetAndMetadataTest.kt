package cn.song.mybatisplus.visualizer.sql.insight

import org.junit.Assert.*
import org.junit.Test

class SqlSemanticSetAndMetadataTest {
    private val metadata = SqlColumnMetadata("db", "v1", listOf(
        SqlTableColumns(listOf("s", "users"), listOf("name", "id", "enabled")),
        SqlTableColumns(listOf("teams"), listOf("id", "label")),
    ))
    private fun analyze(sql: String, schema: SqlColumnMetadata? = metadata, db: String = "db", version: String = "v1") =
        SqlSemanticAnalyzer.analyze(AnalysisSnapshot(sql, databaseContext = db, metadataVersion = version, metadata = schema))

    @Test fun `union matches projection positions never aliases or table sets`() {
        val m = analyze("SELECT a.id AS x,a.name AS y FROM a UNION ALL SELECT b.code AS y,b.label AS x FROM b")
        assertEquals(listOf("x", "y"), m.outputFields.map { it.name })
        assertEquals(listOf(setOf("id", "code"), setOf("name", "label")), m.outputFields.map { p -> p.sources.map { it.name }.toSet() })
        assertTrue(m.dependencies.none { it.kind == DependencyKind.DEDUPLICATION })
        assertEquals(AnalysisQuality.CERTAIN, m.quality)
    }

    @Test fun `union distinct records dedup separately and width mismatch yields no guessed alignment`() {
        val m = analyze("SELECT a.id FROM a UNION SELECT b.code FROM b")
        assertEquals(setOf("id", "code"), m.outputFields.single().sources.map { it.name }.toSet())
        assertEquals(1, m.dependencies.count { it.kind == DependencyKind.DEDUPLICATION })
        val bad = analyze("SELECT a.id,a.name FROM a UNION ALL SELECT b.code FROM b")
        assertTrue(bad.outputFields.isEmpty())
        assertTrue(bad.diagnostics.any { it.code == DiagnosticCode.COLUMN_COUNT_MISMATCH })
    }

    @Test fun `star expands schema order for each self join instance`() {
        val m = analyze("SELECT b.*,a.id FROM s.users a JOIN s.users b ON a.id=b.id")
        assertEquals(listOf("name", "id", "enabled", "id"), m.outputFields.map { it.name })
        val ids = m.outputFields.map { it.sources.single().relationId }
        assertEquals(ids[0], ids[1])
        assertNotEquals(ids[0], ids[3])
        assertEquals(AnalysisQuality.CERTAIN, m.quality)
    }

    @Test fun `cte star maps underlying projections and unqualified star keeps from order`() {
        val m = analyze("WITH c AS (SELECT u.id AS k,u.name AS title FROM s.users u) SELECT c.*,t.* FROM c JOIN teams t ON c.k=t.id")
        assertEquals(listOf("k", "title", "id", "label"), m.outputFields.map { it.name })
        assertEquals(listOf("id", "name", "id", "label"), m.outputFields.map { it.sources.single().name })
        val all = analyze("SELECT * FROM teams t CROSS JOIN s.users u")
        assertEquals(listOf("id", "label", "name", "id", "enabled"), all.outputFields.map { it.name })
    }

    @Test fun `metadata identity version exact qualified name and ambiguity are checked`() {
        for (m in listOf(analyze("SELECT u.* FROM s.users u", db = "other"), analyze("SELECT u.* FROM s.users u", version = "v2"), analyze("SELECT u.* FROM users u"))) {
            assertTrue(m.outputFields.all { it.sources.isEmpty() })
            assertTrue(m.quality != AnalysisQuality.CERTAIN)
        }
        val duplicate = metadata.copy(tables = metadata.tables + metadata.tables.first())
        assertTrue(analyze("SELECT * FROM s.users", duplicate).outputFields.all { it.sources.isEmpty() })
        val unknown = analyze("SELECT u.missing FROM s.users u")
        assertTrue(unknown.outputFields.single().sources.isEmpty())
    }

    @Test fun `using star is not expanded as concatenated duplicate columns`() {
        val m = analyze("SELECT * FROM s.users u JOIN teams t USING(id)")
        assertTrue(m.outputFields.all { it.sources.isEmpty() })
        assertTrue(m.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_CLAUSE })
    }

    @Test fun `metadata permits unique bare column but no metadata does not guess`() {
        assertEquals("name", analyze("SELECT name FROM s.users u JOIN teams t ON u.id=t.id").outputFields.single().sources.single().name)
        assertTrue(analyze("SELECT id FROM s.users u JOIN teams t ON u.id=t.id").outputFields.single().sources.isEmpty())
        assertTrue(analyze("SELECT name FROM s.users u JOIN teams t ON u.id=t.id", null).outputFields.single().sources.isEmpty())
    }
}
