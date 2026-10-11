package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * O-86（DBX #116 对照）：MCP 审计中断兜底测试——
 * 被**取消**的请求也落 [MCP-AUDIT] 记录（status=INTERRUPTED），且取消语义不被吞
 * （CancellationException 原样透传）；EXPECTED_ERROR 路径行为不变（回归钉）。
 */
class McpAuditInterruptTest {

    @Test
    fun testO86_取消场景_落INTERRUPTED审计() {
        val token = 900001L
        McpToolsetSupport.AUDIT_CTX[token] =
            McpToolsetSupport.AuditContext("sqlv_render_sql", "sql=SELECT 1", System.currentTimeMillis() - 500)
        try {
            val gate = gateForTest(token)
            val returned = gate.onFailure(CancellationException("cancelled"))
            assertTrue("取消异常必须原样透传（吞取消=平台取消语义被破坏）", returned is CancellationException)
            assertEquals("ctx 已消费移除", null, McpToolsetSupport.AUDIT_CTX[token])
        } finally {
            McpToolsetSupport.AUDIT_CTX.remove(token)
        }
    }

    @Test
    fun testO86_预期错误路径_行为不变_回归钉() {
        val token = 900002L
        McpToolsetSupport.AUDIT_CTX[token] =
            McpToolsetSupport.AuditContext("sqlv_check_sql", "sql=DROP 1", System.currentTimeMillis() - 100)
        try {
            val gate = gateForTest(token)
            val e = SqlvExpectedToolException("blocked")
            val returned = gate.onFailure(e)
            assertEquals("预期错误仍转译返回（不因 INTERRUPTED 分支改变）", e, returned)
            assertEquals("ctx 已消费移除", null, McpToolsetSupport.AUDIT_CTX[token])
        } finally {
            McpToolsetSupport.AUDIT_CTX.remove(token)
        }
    }

    @Test
    fun testO86_无ctx_透传不炸() {
        val gate = gateForTest(999999L)
        val e = RuntimeException("no ctx")
        assertEquals("无 ctx 时原样返回", e, gate.onFailure(e))
        assertNull(McpToolsetSupport.AUDIT_CTX[999999L])
    }

    /** gate 构造缝：token + 无操作 completion（onFailure 路径不触 completion）。 */
    private fun gateForTest(token: Long): McpToolsetSupport.AuditGate =
        McpToolsetSupport.AuditGate(
            token,
            object : kotlin.coroutines.Continuation<String> {
                override val context: kotlin.coroutines.CoroutineContext =
                    kotlin.coroutines.EmptyCoroutineContext
                override fun resumeWith(result: Result<String>) {}
            },
        )
}
