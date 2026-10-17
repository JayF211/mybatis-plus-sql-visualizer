package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** UI023：同源缓存正例及异源/异库/复杂语句拒绝借用的反例。 */
class SchemaValidationTargetTest : BasePlatformTestCase() {
    private val source = Any()
    private val target = SchemaValidationGate.Target(source, "mp_demo")
    private val sql = "UPDATE user SET usernme='x' WHERE id=71"

    override fun setUp() {
        super.setUp()
        DatabaseSchemaResolver.invalidateAll()
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { "mp_demo" }
    }

    override fun tearDown() {
        try {
            DatabaseSchemaResolver.dataSourceDatabaseForTest = null
            DatabaseSchemaResolver.invalidateAll()
            DatabaseSchemaResolver.clearNegativeCache()
        } finally {
            super.tearDown()
        }
    }

    private fun warm(ds: Any = source, columns: Set<String> = setOf("id", "username")) {
        DatabaseSchemaResolver.resolveFromSources("user", listOf(ds)) { _, _ ->
            DatabaseSchemaResolver.TableMeta(
                "users", columns.associateWith { DatabaseSchemaResolver.ColumnMeta(null, "varchar(64)") },
                allColumnNames = columns,
            )
        }
    }

    fun testExactTargetActuallyChecksWarmedColumns() {
        warm()
        val checked = SchemaValidationGate.check(project, sql, target) as SchemaValidationGate.Outcome.Checked
        assertEquals(1, checked.issues.size)
        assertEquals("usernme", (checked.issues.single() as cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator.UnknownColumn).column)
    }

    fun testColdTargetAndMissingTargetAreSkipped() {
        assertSame(SchemaValidationGate.Outcome.Skipped, SchemaValidationGate.check(project, sql, target))
        warm()
        assertSame(SchemaValidationGate.Outcome.Skipped, SchemaValidationGate.check(project, sql, null))
    }

    fun testDifferentSourceSameTableNeverFallsBack() {
        warm(Any())
        assertSame(SchemaValidationGate.Outcome.Skipped, SchemaValidationGate.check(project, sql, target))
    }

    fun testChangedDatabaseNeverUsesOldDatabaseCache() {
        warm()
        assertSame(
            SchemaValidationGate.Outcome.Skipped,
            SchemaValidationGate.check(project, sql, target.copy(database = "other_db")),
        )
    }

    fun testTargetHitIgnoresOtherSourcesConflictingColumns() {
        warm(Any(), setOf("id", "usernme"))
        warm()
        val checked = SchemaValidationGate.check(project, sql, target) as SchemaValidationGate.Outcome.Checked
        assertEquals(1, checked.issues.size)
    }

    fun testQualifiedComplexAndContextChangingStatementsAreSkipped() {
        warm()
        for (text in listOf(
            "UPDATE other_db.user SET usernme='x' WHERE id=71",
            "UPDATE user SET usernme=(SELECT username FROM user) WHERE id=71",
            "UPDATE user SET usernme='x' FROM other_table WHERE id=71",
            "UPDATE user JOIN other_table ON user.id=other_table.id SET usernme='x'",
            "SELECT usernme FROM user JOIN other_table ON user.id=other_table.id",
            "USE other_db; UPDATE user SET usernme='x' WHERE id=71",
            "not valid sql !",
        )) {
            assertSame(text, SchemaValidationGate.Outcome.Skipped, SchemaValidationGate.check(project, text, target))
        }
    }

    fun testOnlyProvenMysqlOrMariaDbCurrentDatabaseIsAccepted() {
        assertEquals("mp_demo", ExecConsoleContext.schemaValidationDatabase("jdbc:mysql://localhost/mp_demo", "mp_demo"))
        assertEquals("mp_demo", ExecConsoleContext.schemaValidationDatabase("jdbc:mariadb://localhost/mp_demo", "mp_demo"))
        assertNull(ExecConsoleContext.schemaValidationDatabase("jdbc:mysql://localhost/mp_demo", "other_db"))
        assertNull(ExecConsoleContext.schemaValidationDatabase("jdbc:mysql://localhost/mp_demo", null))
        assertNull(ExecConsoleContext.schemaValidationDatabase("jdbc:mysql://localhost/", "mp_demo"))
        assertNull(ExecConsoleContext.schemaValidationDatabase("jdbc:postgresql://localhost/mp_demo", "mp_demo"))
    }
}
