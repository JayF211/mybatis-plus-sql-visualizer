package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExecutionDialectGateTest {
    private fun request(
        mode: ExecutionDialectGate.RenderMode,
        dialect: String? = "MySQL",
        intent: ExecutionDialectGate.Intent = ExecutionDialectGate.Intent.QUERY,
        rerender: Boolean = false,
        untouched: Boolean = false,
    ) = ExecutionDialectGate.Request(mode, dialect, intent, rerender, untouched)

    @Test
    fun `same target continues`() {
        assertEquals(ExecutionDialectGate.Status.MATCH,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO), "mysql").status)
    }

    @Test
    fun `manual mismatch always blocks`() {
        val verdict = ExecutionDialectGate.evaluate(
            request(ExecutionDialectGate.RenderMode.MANUAL, "Oracle"), "MySQL")
        assertEquals(ExecutionDialectGate.Status.MANUAL_CONFLICT, verdict.status)
    }

    @Test
    fun `auto mismatch rerenders only when lossless and untouched`() {
        assertEquals(ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, "MySQL",
                rerender = true, untouched = true), "PostgreSQL").status)
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, "MySQL",
                rerender = true, untouched = false), "PostgreSQL").status)
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, "MySQL"), "PostgreSQL").status)
    }

    @Test
    fun `unknown target query asks confirmation but DML and EXPLAIN block`() {
        assertEquals(ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO), null).status)
        for (intent in listOf(ExecutionDialectGate.Intent.DML, ExecutionDialectGate.Intent.EXPLAIN,
            ExecutionDialectGate.Intent.TEMPLATE)) {
            assertEquals(intent.name, ExecutionDialectGate.Status.TARGET_UNKNOWN_BLOCK,
                ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, intent = intent), null).status)
        }
    }

    @Test
    fun `unknown rendered dialect can request auto rerender but never manual overwrite`() {
        assertEquals(ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, null,
                rerender = true, untouched = true), "Oracle").status)
        assertEquals(ExecutionDialectGate.Status.MANUAL_CONFLICT,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.MANUAL, null), "Oracle").status)
    }

    @Test
    fun `session seam resolves exact selected datasource without runner display name`() {
        class Ds(private val url: String) { @Suppress("unused") fun getUrl(): String = url }
        val session = Any()
        val ds = Ds("jdbc:postgresql://localhost/app")
        try {
            ExecutionDialectGate.sessionDataSourceForTest = { if (it === session) ds else null }
            val target = ExecutionDialectGate.fromSession(session)
            assertTrue(target?.dataSource === ds)
            assertEquals("PostgreSQL", target?.dialectName)
            assertNull(target?.stableId)
        } finally {
            ExecutionDialectGate.sessionDataSourceForTest = null
        }
    }

    @Test
    fun `console seam and unknown URL keep target dialect unknown`() {
        class Ds { @Suppress("unused") fun getUrl(): String = "jdbc:vendor://localhost/app" }
        val console = Any()
        try {
            ExecutionDialectGate.consoleDataSourceForTest = { if (it === console) Ds() else null }
            val target = ExecutionDialectGate.fromConsole(console)
            assertNull(target?.dialectName)
        } finally {
            ExecutionDialectGate.consoleDataSourceForTest = null
        }
    }

    @Test
    fun `central handler keeps callers without request unchanged and blocks known conflicts`() {
        val project = projectStub()
        val target = ExecutionDialectGate.Target(Any(), "ds-1", "prod", "PostgreSQL", "PostgreSQL")
        assertTrue(DataSourceSelector.allowDialectGate(project, null, target, null, null))

        var callbackStatus: ExecutionDialectGate.Status? = null
        var error: String? = null
        val allowed = DataSourceSelector.allowDialectGate(
            project,
            request(ExecutionDialectGate.RenderMode.MANUAL, "MySQL", ExecutionDialectGate.Intent.DML),
            target,
            { callbackStatus = it.status },
            { error = it },
        )
        assertFalse(allowed)
        assertEquals(ExecutionDialectGate.Status.MANUAL_CONFLICT, callbackStatus)
        assertTrue(error.orEmpty().contains("MySQL"))
        assertTrue(error.orEmpty().contains("PostgreSQL"))
    }

    @Test
    fun `central handler unknown target blocks explain but query needs explicit confirmation`() {
        val project = projectStub()
        assertFalse(
            DataSourceSelector.allowDialectGate(
                project,
                request(ExecutionDialectGate.RenderMode.AUTO, intent = ExecutionDialectGate.Intent.EXPLAIN),
                null,
                null,
                null,
            ),
        )
        try {
            DataSourceSelector.unknownTargetQueryConfirmationForTest = { true }
            assertTrue(
                DataSourceSelector.allowDialectGate(
                    project,
                    request(ExecutionDialectGate.RenderMode.AUTO, intent = ExecutionDialectGate.Intent.QUERY),
                    null,
                    null,
                    null,
                ),
            )
        } finally {
            DataSourceSelector.unknownTargetQueryConfirmationForTest = null
        }
    }

    @Test
    fun `source wiring gates selected session before attach and active console before submit mutation`() {
        val root = repoRoot()
        val results = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt").readText()
        val console = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt").readText()
        val sessionGate = results.indexOf("ExecutionDialectGate.fromSession(session)")
        val attach = results.indexOf("prepareInConsole(", sessionGate)
        assertTrue("session gate must precede prepareInConsole/attach", sessionGate >= 0 && attach > sessionGate)
        val consoleGate = console.indexOf("ExecutionDialectGate.fromConsole(console)")
        val firstMutation = console.indexOf("stripGSuffixForSubmit", consoleGate)
        assertTrue("console gate must precede any submit document mutation", consoleGate >= 0 && firstMutation > consoleGate)
    }

    @Test
    fun `preview ordinary execute and both explain channels carry request while uncovered paths stay explicit`() {
        val root = repoRoot()
        val source = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt").readText()
        assertTrue(source.contains("ExecutionDialectGate.Intent.DML"))
        assertTrue(source.contains("ExecutionDialectGate.Intent.EXPLAIN"))
        val structuredGate = source.indexOf("DataSourceSelector.allowDialectGate")
        val structuredSubmit = source.indexOf("ExplainPanelChannel.executeStructured")
        assertTrue("structured EXPLAIN must gate before JDBC submit", structuredGate >= 0 && structuredSubmit > structuredGate)
        assertEquals(
            linkedSetOf(
                "actions.SelectedTableSqlTemplateAction.executeTemplate",
                "log.LogToSqlPanel.executeSql",
                "ui.SqlPreviewPanel.estimateDmlImpact",
                "ui.SqlPreviewPanel.executeAdhocSql",
            ),
            ExecutionDialectGate.uncoveredEntryPoints,
        )
    }

    private fun projectStub(): com.intellij.openapi.project.Project =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.intellij.openapi.project.Project::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as com.intellij.openapi.project.Project

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found")
    }
}
