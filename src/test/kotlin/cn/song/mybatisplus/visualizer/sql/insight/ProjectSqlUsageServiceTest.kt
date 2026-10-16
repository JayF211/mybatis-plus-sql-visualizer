package cn.song.mybatisplus.visualizer.sql.insight

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real platform PSI without opening an IDE window or invoking UI actions. */
class ProjectSqlUsageServiceTest : LightJavaCodeInsightFixtureTestCase() {
    private val template get() = AnalysisSnapshot("", databaseContext = "db")
    private fun <T> background(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable { action() })
        waitFor(future)
        return future.get()
    }
    private fun waitFor(future: Future<*>) {
        PlatformTestUtil.waitWithEventsDispatching("SQL usage background operation", { future.isDone }, 15_000)
    }

    fun testCurrentSqlFileAndExactHostRange() {
        val file = myFixture.addFileToProject("queries/current.sql", "SELECT u.name FROM users u WHERE u.id=1")
        val current = SqlUsageSearchScope.CurrentFile(file.virtualFile)
        val service = ProjectSqlUsageService(project)
        try {
            val report = background { service.collect(current, template) }
            assertEquals(1, report.candidates.size)
            val candidate = report.candidates.single()
            assertEquals(SqlCandidateKind.SQL_FILE, candidate.kind)
            assertEquals(file.virtualFile.path, candidate.location.filePath)
            assertEquals(0, candidate.location.startOffset)
            assertEquals(file.textLength, candidate.location.endOffsetExclusive)
            assertTrue(report.usages.any { it.tableName == "users" && it.column == "name" })
        } finally { service.dispose() }
    }

    fun testJavaLiteralOnlyConcatenationAndNonConstantCoverage() {
        val file = myFixture.addFileToProject("Sample.java", "class Sample { String a = \"SELECT u.name \" + \"FROM users u\"; String b = \"SELECT * FROM \" + table; }")
        val current = SqlUsageSearchScope.CurrentFile(file.virtualFile)
        val service = ProjectSqlUsageService(project)
        try {
            val report = background { service.collect(current, template) }
            assertEquals(1, report.candidates.size)
            assertEquals("SELECT u.name FROM users u", report.candidates.single().snapshot.sql)
            assertTrue(report.candidates.single().location.startOffset > 0)
            assertTrue(report.diagnostics.any { it.code == "NON_CONSTANT_CONCATENATION" })
        } finally { service.dispose() }
    }

    fun testStaticMapperAndDynamicMapperAreDistinguished() {
        val file = myFixture.addFileToProject("UserMapper.xml", "<mapper namespace=\"Users\"><select id=\"plain\">SELECT u.name FROM users u</select><select id=\"dynamic\">SELECT * FROM users <where><if test=\"id != null\">id=#{id}</if></where></select></mapper>")
        val current = SqlUsageSearchScope.CurrentFile(file.virtualFile)
        val service = ProjectSqlUsageService(project)
        try {
            val report = background { service.collect(current, template) }
            assertEquals(1, report.candidates.size)
            assertEquals("plain", report.candidates.single().location.label)
            val skipped = report.diagnostics.single { it.code == "DYNAMIC_MAPPER_SKIPPED" }
            assertEquals("dynamic", skipped.location!!.label)
            assertTrue(skipped.location!!.startOffset > report.candidates.single().location.startOffset)
        } finally { service.dispose() }
    }

    fun testMapperEntitiesAndCdataProduceDecodedSqlWithoutInventedTokenRanges() {
        val file = myFixture.addFileToProject("EscapedMapper.xml", "<mapper namespace=\"Users\"><select id=\"escaped\">SELECT u.id FROM users u WHERE u.id &lt; 7</select><select id=\"cdata\"><![CDATA[SELECT u.name FROM users u WHERE u.id > 1]]></select></mapper>")
        val current = SqlUsageSearchScope.CurrentFile(file.virtualFile)
        val service = ProjectSqlUsageService(project)
        try {
            val report = background { service.collect(current, template) }
            assertEquals(2, report.candidates.size)
            assertTrue(report.candidates.any { it.snapshot.sql.contains("u.id < 7") })
            assertTrue(report.candidates.any { it.snapshot.sql.contains("u.id > 1") })
            assertFalse(report.diagnostics.any { it.code == "PARSE_ERROR" })
        } finally { service.dispose() }
    }

    fun testExplicitModuleAndFileBudgetDoNotClaimCompleteCoverage() {
        myFixture.addFileToProject("queries/a.sql", "SELECT a.id FROM a")
        myFixture.addFileToProject("queries/b.sql", "SELECT b.id FROM b")
        val moduleScope = SqlUsageSearchScope.ModuleFiles(module)
        val service = ProjectSqlUsageService(project)
        try {
            val report = background { service.collect(moduleScope, template) }
            assertTrue(report.candidates.any { it.snapshot.sql.contains("FROM a") })
            assertTrue(report.candidates.any { it.snapshot.sql.contains("FROM b") })
            val limited = background { service.collect(moduleScope, template, SqlUsageLimits(maxFiles = 1)) }
            assertTrue(limited.coverage.truncated)
            assertTrue(limited.coverage.visitedFiles <= 1)
        } finally { service.dispose() }
    }

    fun testOversizedFileAndCancellationAreExplicit() {
        val file = myFixture.addFileToProject("queries/large.sql", "SELECT id FROM users " + " ".repeat(200))
        val current = SqlUsageSearchScope.CurrentFile(file.virtualFile)
        val service = ProjectSqlUsageService(project)
        try {
            val report = background { service.collect(current, template, SqlUsageLimits(maxFileCharacters = 40)) }
            assertTrue(report.candidates.isEmpty())
            assertTrue(report.diagnostics.any { it.code == "FILE_SIZE_LIMIT" })
            val cancelled = background { service.collect(current, template, isCancelled = { true }) }
            assertTrue(cancelled.coverage.cancelled)
            assertTrue(cancelled.usages.isEmpty())
        } finally { service.dispose() }
    }

    fun testCallbackIsBackgroundAndNewGenerationSuppressesOldResult() {
        val file = myFixture.addFileToProject("queries/generation.sql", "SELECT u.id FROM users u")
        val current = SqlUsageSearchScope.CurrentFile(file.virtualFile)
        val service = ProjectSqlUsageService(project)
        val oldCalled = AtomicBoolean()
        val newCalled = AtomicBoolean()
        val callbackEdt = AtomicReference<Boolean>()
        var first: Future<*>? = null
        var second: Future<*>? = null
        try {
            // The writer prevents the first background read until both generations are installed.
            WriteAction.run<RuntimeException> {
                first = service.collectAsync(current, template) { oldCalled.set(true) }
                second = service.collectAsync(current, template) {
                    callbackEdt.set(ApplicationManager.getApplication().isDispatchThread)
                    newCalled.set(true)
                }
            }
            waitFor(second!!)
            assertTrue(first!!.isCancelled)
            assertFalse(oldCalled.get())
            assertTrue(newCalled.get())
            assertEquals(false, callbackEdt.get())
        } finally { service.dispose() }
    }

    fun testServiceDisposeSuppressesPendingCallbackAndRejectsNewWork() {
        val file = myFixture.addFileToProject("queries/dispose.sql", "SELECT u.id FROM users u")
        val current = SqlUsageSearchScope.CurrentFile(file.virtualFile)
        val service = ProjectSqlUsageService(project)
        val called = AtomicBoolean()
        var pending: Future<*>? = null
        WriteAction.run<RuntimeException> {
            pending = service.collectAsync(current, template) { called.set(true) }
            service.dispose()
        }
        assertTrue(pending!!.isCancelled)
        // Barrier on a fresh pooled read also proves subsequent service collection stays cancelled.
        assertTrue(background { service.collect(current, template) }.coverage.cancelled)
        assertFalse(called.get())
        try {
            service.collectAsync(current, template) { called.set(true) }
            fail("Disposed service accepted new work")
        } catch (_: IllegalStateException) { }
    }
}
