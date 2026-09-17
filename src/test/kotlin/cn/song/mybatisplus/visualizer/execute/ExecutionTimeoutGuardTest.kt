package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.SQLException
import java.sql.SQLTimeoutException

/**
 * 执行链超时护栏纯函数表驱动测试（T-ExecTimeoutGuard，O-25）。
 *
 * DBX 1ed06f2 两决策的规格锁定：预算随语句数线性增长（无 ×2 放大、无武断下限、封顶 ×8）
 * 与超时形态多路判定（标准异常/SQLState/消息签名/反射链解包）。
 * MCP 直连链接入 McpDbExecutor（RemoteStatement 镜像，反射层需真机）；
 * 共享控制台链 DBN 托管无语句句柄 v1 不覆盖（登记见 ExecutionTimeoutGuard 类注释）。
 */
class ExecutionTimeoutGuardTest {

    // ======================== 预算公式（DBX 线性 + 封顶） ========================

    @Test
    fun testSingleStatementUsesBase() {
        assertEquals("单语句=基础值", 30, ExecutionTimeoutGuard.timeoutBudgetSeconds(30, 1))
        assertEquals("语句数 0 按单语句", 30, ExecutionTimeoutGuard.timeoutBudgetSeconds(30, 0))
    }

    @Test
    fun testMultiStatementScalesLinearly() {
        assertEquals("5 条 30s 慢语句=150s（无 ×2 放大、无 60s 下限）",
            150, ExecutionTimeoutGuard.timeoutBudgetSeconds(30, 5))
        assertEquals("3 条 10s=30s", 30, ExecutionTimeoutGuard.timeoutBudgetSeconds(10, 3))
    }

    @Test
    fun testBudgetCapsAtEightFactor() {
        assertEquals("10 条语句封顶 ×8", 240, ExecutionTimeoutGuard.timeoutBudgetSeconds(30, 10))
        assertEquals("100 条语句仍 240s（防滥用）", 240, ExecutionTimeoutGuard.timeoutBudgetSeconds(30, 100))
        assertEquals("恰好 8 条=不裁（240=240）", 240, ExecutionTimeoutGuard.timeoutBudgetSeconds(30, 8))
        assertEquals("9 条起封顶", 240, ExecutionTimeoutGuard.timeoutBudgetSeconds(30, 9))
    }

    @Test
    fun testZeroBaseMeansUnlimited() {
        assertEquals("0=不限（返回 0）", 0, ExecutionTimeoutGuard.timeoutBudgetSeconds(0, 1))
        assertEquals("负值同 0", 0, ExecutionTimeoutGuard.timeoutBudgetSeconds(-5, 10))
        assertEquals("每语句超时 0=不限", 0, ExecutionTimeoutGuard.perStatementTimeoutSeconds(0))
    }

    @Test
    fun testPerStatementTimeoutIsBase() {
        assertEquals("每语句超时=基础值", 30, ExecutionTimeoutGuard.perStatementTimeoutSeconds(30))
        assertEquals("负值钳 0", 0, ExecutionTimeoutGuard.perStatementTimeoutSeconds(-1))
    }

    // ======================== 超时形态判定（三路 + 反射链解包） ========================

    @Test
    fun testStandardTimeoutException() {
        assertTrue("SQLTimeoutException 标准形态",
            ExecutionTimeoutGuard.isTimeoutLike(SQLTimeoutException("timeout")))
    }

    @Test
    fun testSqlState57014() {
        val e = SQLException("canceling statement due to statement timeout", "57014")
        assertTrue("PG query_canceled SQLState 57014", ExecutionTimeoutGuard.isTimeoutLike(e))
    }

    @Test
    fun testMessageSignatures() {
        assertTrue("MySQL 文案", ExecutionTimeoutGuard.isTimeoutLike(SQLException("Statement cancelled due to timeout")))
        assertTrue("statement timeout", ExecutionTimeoutGuard.isTimeoutLike(SQLException("statement timeout exceeded")))
        assertTrue("query timeout", ExecutionTimeoutGuard.isTimeoutLike(SQLException("Query timeout expired")))
        assertTrue("timed out 兜底", ExecutionTimeoutGuard.isTimeoutLike(SQLException("Read timed out")))
    }

    @Test
    fun testNonTimeoutIsNotMisjudged() {
        assertFalse("语法错不是超时", ExecutionTimeoutGuard.isTimeoutLike(SQLException("syntax error at or near FROM", "42601")))
        assertFalse("连接拒绝不是超时", ExecutionTimeoutGuard.isTimeoutLike(SQLException("Connection refused", "08001")))
        assertFalse("死锁不是超时", ExecutionTimeoutGuard.isTimeoutLike(SQLException("Deadlock found", "40001")))
        assertFalse("null 不是超时", ExecutionTimeoutGuard.isTimeoutLike(null))
    }

    @Test
    fun testInvocationTargetExceptionUnwrapping() {
        // 反射链形态：McpDbExecutor 的 invoke 抛 InvocationTargetException 包裹驱动超时
        val driverTimeout = SQLTimeoutException("query timeout")
        val wrapped = java.lang.reflect.InvocationTargetException(driverTimeout)
        assertTrue("InvocationTargetException 包裹的超时（targetException 路径）",
            ExecutionTimeoutGuard.isTimeoutLike(wrapped))
        val nested = RuntimeException("wrapper", java.lang.reflect.InvocationTargetException(driverTimeout))
        assertTrue("普通 cause 链 + 反射混合", ExecutionTimeoutGuard.isTimeoutLike(nested))
    }

    @Test
    fun testCauseChainDepthBounded() {
        // 自引用 cause 不死循环（深度上限 8 兜底）：非超时消息的循环链须在有界步数内判 false
        val a = SQLException("level a")
        val b = SQLException("level b", a)
        a.initCause(b) // a <-> b 循环
        assertFalse("循环链不挂死且有界判负", ExecutionTimeoutGuard.isTimeoutLike(a))
        // 循环链深处藏超时：首层消息非超时，解包若干层后命中
        val deep = SQLException("outer", SQLException("mid", SQLTimeoutException("timeout")))
        assertTrue("多层解包命中", ExecutionTimeoutGuard.isTimeoutLike(deep))
    }
}
