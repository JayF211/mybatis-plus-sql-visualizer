package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PreviewSideQuery：只读护栏/方言闸必须先于数据源访问，成功映射与失败解包需可 headless 覆盖。
 *
 * 复盘补充（20261019）：此前该类的测试缝 [PreviewSideQuery.runnerForTest] 直接回调
 * [PreviewSideQuery.Result]，真链上的「原始结果 → Result」映射（标量解析、表格映射、
 * InvocationTargetException 解包）与方言闸分支零覆盖——260 行真机走查里出现的
 * `Unknown column ...` 失败信息正是这条解包链的产物。
 */
class PreviewSideQueryTest : BasePlatformTestCase() {

    /** 必须是 public：生产反射按 getUrl 取目标方言。 */
    class MySqlTarget {
        @Suppress("unused")
        fun getUrl(): String = "jdbc:mysql://localhost:3306/demo"
    }

    class PostgresTarget {
        @Suppress("unused")
        fun getUrl(): String = "jdbc:postgresql://localhost:5432/demo"
    }

    private fun request(
        rendered: String = "MySQL",
        intent: ExecutionDialectGate.Intent = ExecutionDialectGate.Intent.QUERY,
    ) = ExecutionDialectGate.Request(
        renderMode = ExecutionDialectGate.RenderMode.AUTO,
        renderedDialectName = rendered,
        intent = intent,
        canLosslesslyRerender = false,
        userTextUntouched = true,
    )

    fun testNonSelectIsBlockedBeforeDataSourceAccess() {
        var result: PreviewSideQuery.Result? = null
        PreviewSideQuery.run(
            project,
            Any(),
            "UPDATE t_order SET status = 1 WHERE id = 1",
            request(),
            scalar = true,
            requireSelectGuard = true,
        ) { result = it }
        assertTrue("非 SELECT 必须被只读护栏阻断：$result", result is PreviewSideQuery.Result.Blocked)
        assertTrue(
            "阻断原因不能为空",
            (result as PreviewSideQuery.Result.Blocked).message.isNotBlank(),
        )
    }

    fun testGuardBlockNeverReachesRunner() {
        var runnerCalls = 0
        PreviewSideQuery.runnerForTest = { _, _, _, _, _ -> runnerCalls++ }
        try {
            var result: PreviewSideQuery.Result? = null
            PreviewSideQuery.run(
                project, MySqlTarget(), "DELETE FROM t_order WHERE id = 1", request(),
                scalar = true, requireSelectGuard = true,
            ) { result = it }
            assertTrue("非 SELECT 必须在护栏处阻断：$result", result is PreviewSideQuery.Result.Blocked)
            assertEquals("护栏阻断后不得再访问数据源", 0, runnerCalls)
        } finally {
            PreviewSideQuery.runnerForTest = null
        }
    }

    fun testDialectMismatchBlockedBeforeRunner() {
        var runnerCalls = 0
        PreviewSideQuery.runnerForTest = { _, _, _, _, _ -> runnerCalls++ }
        try {
            var result: PreviewSideQuery.Result? = null
            PreviewSideQuery.run(
                project, PostgresTarget(), "SELECT COUNT(*) FROM t_order", request("MySQL"),
                scalar = true, requireSelectGuard = true,
            ) { result = it }
            assertTrue("MySQL 渲染送进 PostgreSQL 数据源必须 fail-closed：$result", result is PreviewSideQuery.Result.Blocked)
            assertFalse(
                "方言不一致的 COUNT 绝不能送进目标库",
                (result as PreviewSideQuery.Result.Blocked).message.isBlank(),
            )
            assertEquals("方言闸阻断后不得执行查询", 0, runnerCalls)
        } finally {
            PreviewSideQuery.runnerForTest = null
        }
    }

    fun testMatchingDialectReachesRunner() {
        var runnerCalls = 0
        PreviewSideQuery.runnerForTest = { _, _, sql, scalar, cb ->
            runnerCalls++
            assertTrue("COUNT 走单值通道", scalar)
            assertTrue("SQL 应原样下传：$sql", sql.contains("COUNT(*)"))
            cb(PreviewSideQuery.scalarResult(raw(listOf(listOf("3")))))
        }
        try {
            var result: PreviewSideQuery.Result? = null
            PreviewSideQuery.run(
                project, MySqlTarget(), "SELECT COUNT(*) FROM t_order", request("MySQL"),
                scalar = true, requireSelectGuard = true,
            ) { result = it }
            assertEquals("方言一致应进入执行通道", 1, runnerCalls)
            assertEquals(3L, (result as PreviewSideQuery.Result.Scalar).value)
        } finally {
            PreviewSideQuery.runnerForTest = null
        }
    }

    fun testScalarResultTrimsAndParsesNumber() {
        val result = PreviewSideQuery.scalarResult(raw(listOf(listOf(" 42 "))))
        assertTrue("带空白数字应解析成功：$result", result is PreviewSideQuery.Result.Scalar)
        assertEquals(42L, (result as PreviewSideQuery.Result.Scalar).value)
    }

    fun testScalarResultRejectsNonNumeric() {
        val result = PreviewSideQuery.scalarResult(raw(listOf(listOf("abc"))))
        assertTrue("非数字必须标 Failed：$result", result is PreviewSideQuery.Result.Failed)
        assertTrue((result as PreviewSideQuery.Result.Failed).message.contains("numeric"))
    }

    fun testScalarResultRejectsEmptyRowsAndBlankCell() {
        assertTrue(PreviewSideQuery.scalarResult(raw(emptyList())) is PreviewSideQuery.Result.Failed)
        assertTrue(PreviewSideQuery.scalarResult(raw(listOf(listOf("")))) is PreviewSideQuery.Result.Failed)
    }

    fun testFailureMessageUnwrapsInvocationTargetException() {
        val wrapped = java.lang.reflect.InvocationTargetException(
            IllegalStateException("Unknown column 'username' in 'field list'"),
        )
        assertEquals(
            "反射通道失败必须解包目标异常信息",
            "Unknown column 'username' in 'field list'",
            PreviewSideQuery.failureMessage(wrapped),
        )
    }

    fun testFailureMessageFallsBackToSimpleName() {
        assertEquals("IllegalStateException", PreviewSideQuery.failureMessage(IllegalStateException()))
    }

    fun testTableResultMapping() {
        val raw = raw(listOf(listOf("1", "SIMPLE")))
        assertSame(raw, (PreviewSideQuery.tableResult(raw) as PreviewSideQuery.Result.Table).raw)
    }

    private fun raw(rows: List<List<String>>) = McpExplainFormatter.RawResult(listOf("v"), rows)
}
