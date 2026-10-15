package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
