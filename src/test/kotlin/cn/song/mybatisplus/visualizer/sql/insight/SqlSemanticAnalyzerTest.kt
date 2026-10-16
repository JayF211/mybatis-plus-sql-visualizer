package cn.song.mybatisplus.visualizer.sql.insight

import org.junit.Assert.*
import org.junit.Test

class SqlSemanticAnalyzerTest {
    private fun analyze(sql: String, dialect: SqlInsightDialect = SqlInsightDialect.POSTGRESQL) =
        SqlSemanticAnalyzer.analyze(AnalysisSnapshot(sql, dialect))

    private fun origins(model: SqlSemanticModel, index: Int = 0): Set<String> =
        model.outputFields[index].sources.map { column ->
            val relation = model.relations.single { it.id == column.relationId }
            "${relation.name}:${relation.alias}.${column.name}"
        }.toSet()

    @Test fun `qualified projection excludes join filter and group columns`() {
        val m = analyze("SELECT u.name AS label FROM users u JOIN teams t ON u.team_id=t.id WHERE t.enabled=1 GROUP BY u.name, t.kind ORDER BY label")
        assertEquals(setOf("users:u.name"), origins(m))
        assertEquals("label", m.outputFields.single().name)
        assertEquals(setOf(TableRole.READ), m.relations.first().roles)
        assertEquals(2, m.dependencies.single { it.kind == DependencyKind.JOIN }.sources.size)
        assertEquals(setOf("enabled"), m.dependencies.single { it.kind == DependencyKind.FILTER }.sources.map { it.name }.toSet())
        assertEquals(2, m.dependencies.count { it.kind == DependencyKind.GROUP })
        assertEquals(m.outputFields.single().sources, m.dependencies.single { it.kind == DependencyKind.ORDER }.sources)
        assertEquals(AnalysisQuality.CERTAIN, m.quality)
    }

    @Test fun `bare ambiguous and unknown qualifier never guess value edges`() {
        for (sql in listOf("SELECT id FROM users u JOIN teams t ON u.team_id=t.id", "SELECT ghost.id FROM users u", "SELECT users.id FROM users u")) {
            val m = analyze(sql)
            assertTrue(sql, m.outputFields.single().sources.isEmpty())
            assertTrue(sql, m.dependencies.none { it.kind == DependencyKind.DIRECT_VALUE })
            assertTrue(sql, m.quality != AnalysisQuality.CERTAIN)
        }
        assertTrue(analyze("SELECT id FROM users u, teams t").diagnostics.any { it.code == DiagnosticCode.AMBIGUOUS_COLUMN })
    }

    @Test fun `schema and self join occurrences stay distinct`() {
        val m = analyze("SELECT a.id, b.id, c.id FROM s1.users a JOIN s1.users b ON a.parent_id=b.id JOIN s2.users c ON b.id=c.id")
        assertEquals(3, m.relations.size)
        assertEquals(3, m.outputFields.flatMap { it.sources }.map { it.relationId }.distinct().size)
        assertEquals(setOf("s1.users:a.id"), origins(m, 0))
        assertEquals(setOf("s2.users:c.id"), origins(m, 2))
        assertTrue(m.relations.all { it.databaseContext == null })
    }

    @Test fun `cte explicit names chain and same name base preserve origins`() {
        val m = analyze("WITH users(x) AS (SELECT b.id FROM public.users b), next_cte AS (SELECT u.x AS renamed FROM users u) SELECT n.renamed FROM next_cte n")
        assertEquals(setOf("public.users:b.id"), origins(m))
        assertEquals(3, m.scopes.size)
        assertEquals(2, m.relations.count { it.kind == RelationKind.CTE })
        assertEquals(1, m.relations.count { it.kind == RelationKind.TABLE })
        assertEquals(AnalysisQuality.CERTAIN, m.quality)
    }

    @Test fun `derived scope alias shadowing does not bind outer table`() {
        val m = analyze("SELECT x.id, d.id FROM outer_table x JOIN (SELECT x.id FROM inner_table x) d ON x.id=d.id")
        assertEquals(setOf("outer_table:x.id"), origins(m, 0))
        assertEquals(setOf("inner_table:x.id"), origins(m, 1))
        assertEquals(2, m.scopes.size)
    }

    @Test fun `recursive forward cte and duplicate outputs have diagnostics without fabricated base table`() {
        val recursive = analyze("WITH RECURSIVE r AS (SELECT r.id FROM r) SELECT r.id FROM r")
        assertTrue(recursive.diagnostics.any { it.code == DiagnosticCode.RECURSIVE_CTE })
        assertTrue(recursive.relations.isEmpty())
        val forward = analyze("WITH a AS (SELECT b.id FROM b), b AS (SELECT t.id FROM t) SELECT a.id FROM a")
        assertTrue(forward.diagnostics.any { it.code == DiagnosticCode.INVALID_CTE })
        assertTrue(forward.outputFields.single().sources.isEmpty())
        assertTrue(forward.relations.none { it.kind == RelationKind.TABLE && it.name == "b" })
        val duplicate = analyze("WITH c AS (SELECT t.id AS x, t.other AS x FROM t) SELECT c.x FROM c")
        assertTrue(duplicate.outputFields.single().sources.isEmpty())
        assertTrue(duplicate.diagnostics.any { it.code == DiagnosticCode.AMBIGUOUS_COLUMN })
    }

    @Test fun `star never invented even for qualified table`() {
        for (sql in listOf("SELECT * FROM t", "SELECT t.* FROM t", "WITH c AS (SELECT * FROM t) SELECT c.id FROM c")) {
            val m = analyze(sql)
            assertTrue(m.diagnostics.any { it.code == DiagnosticCode.STAR_REQUIRES_METADATA })
            assertTrue(m.outputFields.all { it.sources.isEmpty() })
        }
    }

    @Test fun `insert select aligns explicit target columns and separates filters`() {
        val m = analyze("INSERT INTO archive (user_id, title) SELECT u.id, u.name FROM users u WHERE u.active=1")
        val target = m.relations.single { TableRole.WRITE in it.roles }
        assertEquals("archive", target.name)
        val assignments = m.dependencies.filter { it.kind == DependencyKind.ASSIGNMENT }
        assertEquals(listOf("user_id", "title"), assignments.map { it.targetColumn!!.name })
        assertEquals(listOf("id", "name"), assignments.map { it.sources.single().name })
        assertTrue(assignments.none { it.sources.any { c -> c.name == "active" } })
    }

    @Test fun `insert missing columns mismatch star and upsert do not invent assignments`() {
        for (sql in listOf(
            "INSERT INTO archive SELECT u.id FROM users u",
            "INSERT INTO archive (id, name) SELECT u.id FROM users u",
            "INSERT INTO archive (id) SELECT * FROM users",
            "INSERT INTO archive (id) SELECT u.id FROM users u ON CONFLICT (id) DO NOTHING",
        )) {
            val m = analyze(sql)
            assertTrue(sql, m.dependencies.none { it.kind == DependencyKind.ASSIGNMENT })
            assertTrue(sql, m.quality != AnalysisQuality.CERTAIN)
        }
    }

    @Test fun `single update value assignments and row filter are separate`() {
        val m = analyze("UPDATE users u SET name=u.backup_name, score=u.score+1 WHERE u.id=7")
        assertEquals(setOf(TableRole.READ, TableRole.WRITE), m.relations.single().roles)
        val writes = m.dependencies.filter { it.kind == DependencyKind.ASSIGNMENT }
        assertEquals(listOf("name", "score"), writes.map { it.targetColumn!!.name })
        assertEquals(listOf("backup_name", "score"), writes.map { it.sources.single().name })
        assertEquals("id", m.dependencies.single { it.kind == DependencyKind.FILTER }.sources.single().name)
    }

    @Test fun `delete is not a value assignment`() {
        val m = analyze("DELETE FROM users WHERE id=7")
        assertEquals(setOf(TableRole.READ, TableRole.DELETE), m.relations.single().roles)
        assertTrue(m.dependencies.none { it.kind == DependencyKind.ASSIGNMENT || it.kind == DependencyKind.DIRECT_VALUE })
        assertTrue(m.outputFields.isEmpty())
    }

    @Test fun `unsupported multi table dml and set operations are explicit`() {
        for (sql in listOf("UPDATE users SET name=t.name FROM teams t WHERE users.id=t.id", "DELETE FROM users USING teams WHERE users.id=teams.id", "SELECT t.id FROM t UNION SELECT u.id FROM u")) {
            val m = analyze(sql)
            assertTrue(sql, m.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_STATEMENT })
            assertTrue(m.dependencies.none { it.kind == DependencyKind.ASSIGNMENT })
        }
    }

    @Test fun `quoted identifiers retain case and mysql quoting works`() {
        val pg = analyze("SELECT \"U\".\"Id\" FROM \"Users\" \"U\"")
        assertEquals(setOf("\"Users\":\"U\".\"Id\""), origins(pg))
        assertTrue(analyze("SELECT u.\"Id\" FROM \"Users\" \"U\"").outputFields.single().sources.isEmpty())
        val mysql = analyze("SELECT `u`.`id` FROM `users` `u`", SqlInsightDialect.MYSQL)
        assertEquals(setOf("`users`:`u`.`id`"), origins(mysql))
    }

    @Test fun `unknown dialect parse failure and unsupported expression are not certain`() {
        assertEquals(DiagnosticCode.UNSUPPORTED_DIALECT, analyze("SELECT t.id FROM t", SqlInsightDialect.UNKNOWN).diagnostics.first().code)
        assertTrue(analyze("SELEC FROM").diagnostics.any { it.code == DiagnosticCode.PARSE_ERROR })
        val m = analyze("SELECT (SELECT x.id FROM x) AS nested FROM t")
        assertTrue(m.outputFields.single().sources.isEmpty())
        assertTrue(m.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_EXPRESSION })
    }

    @Test fun `ranges are explicitly unknown for comments repeated unicode and edited sql`() {
        for (sql in listOf("/* 重复 id */ SELECT t.id, t.id AS \"中文\" FROM t", "SELECT\n  t.id, t.id\nFROM t", "-- edited\nSELECT t.other AS id FROM t")) {
            val m = analyze(sql)
            assertEquals(sql, m.snapshot.sql)
            assertTrue(m.outputFields.all { it.sourceRange == null })
            assertTrue(m.dependencies.all { it.sourceRange == null })
            assertTrue(m.diagnostics.any { it.code == DiagnosticCode.SOURCE_RANGE_UNKNOWN })
        }
    }

    @Test fun `budget and cancellation publish no partial semantic graph`() {
        val oversized = analyze("SELECT " + "1".repeat(65_536))
        assertTrue(oversized.diagnostics.any { it.code == DiagnosticCode.INPUT_BUDGET_EXCEEDED })
        val cancelled = SqlSemanticAnalyzer.analyze(AnalysisSnapshot("SELECT t.id FROM t")) { true }
        assertEquals(DiagnosticCode.CANCELLED, cancelled.diagnostics.single().code)
        var checkpoints = 0
        val interrupted = SqlSemanticAnalyzer.analyze(AnalysisSnapshot("SELECT a.id, b.id FROM a JOIN b ON a.id=b.id")) { ++checkpoints > 6 }
        assertEquals(DiagnosticCode.CANCELLED, interrupted.diagnostics.single().code)
        for (m in listOf(oversized, cancelled, interrupted)) {
            assertTrue(m.scopes.isEmpty())
            assertTrue(m.relations.isEmpty())
            assertTrue(m.dependencies.isEmpty())
        }
    }

    @Test fun `quoted dotted identifier is not confused with multipart qualifier`() {
        val m = analyze("SELECT \"a.b\".id, a.b.id FROM tab \"a.b\" JOIN a.b ON \"a.b\".id=a.b.id")
        assertTrue(m.outputFields.isEmpty())
        assertTrue(m.dependencies.isEmpty())
        assertTrue(m.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_DIALECT })
        val harmless = analyze("/* \"a.b\" */ SELECT t.id, 'a.b' AS text FROM t")
        assertEquals(setOf("t:null.id"), origins(harmless))
    }

    @Test fun `mysql earlier assigned values and unsupported quote forms do not get false origins`() {
        val m = analyze("UPDATE t SET a=b, c=a", SqlInsightDialect.MYSQL)
        assertEquals(1, m.dependencies.count { it.kind == DependencyKind.ASSIGNMENT })
        assertTrue(m.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_EXPRESSION })
        val brackets = analyze("SELECT [u].[id] FROM [users] [u]")
        assertTrue(brackets.outputFields.single().sources.isEmpty())
        assertTrue(brackets.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_DIALECT })
    }

    @Test fun `join using and natural remain explicitly partial`() {
        for (sql in listOf("SELECT a.id FROM a JOIN b USING(id)", "SELECT a.id FROM a NATURAL JOIN b")) {
            val m = analyze(sql)
            assertTrue(m.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_CLAUSE })
            assertTrue(m.dependencies.none { it.kind == DependencyKind.JOIN })
        }
    }

    @Test fun `unsupported expression cannot leak partially resolved value columns`() {
        val m = analyze("SELECT t.id + abs(t.other) AS x FROM t")
        assertTrue(m.outputFields.single().sources.isEmpty())
        assertTrue(m.dependencies.none { it.kind == DependencyKind.DERIVED_VALUE })
        assertTrue(m.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_EXPRESSION })
    }

    @Test fun `snapshots carry context versions and there is no sql only result cache`() {
        val a = AnalysisSnapshot("SELECT t.id FROM t", databaseContext = "db-a", metadataVersion = "m1", scenarioId = "s1", sourceVersion = "v1")
        val b = a.copy(databaseContext = "db-b", metadataVersion = "m2", scenarioId = "s2", sourceVersion = "v2")
        val first = SqlSemanticAnalyzer.analyze(a)
        val second = SqlSemanticAnalyzer.analyze(b)
        assertEquals(a, first.snapshot)
        assertEquals(b, second.snapshot)
        assertEquals("db-a", first.relations.single().databaseContext)
        assertEquals("db-b", second.relations.single().databaseContext)
    }
}
