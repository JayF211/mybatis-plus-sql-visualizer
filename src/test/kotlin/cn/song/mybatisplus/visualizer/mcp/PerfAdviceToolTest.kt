package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mpsv_perf_advice 工具测试（T-McpToolset2 / T-MTS2-01）：发现输出（英文渲染）、
 * 无发现输出、空输入拒绝。规则细节由 PerfRule 系列测试覆盖。
 */
class PerfAdviceToolTest {

    private fun advise(sql: String) = PerfAdviceTool().perfAdvice(sql)

    @Test
    fun `SELECT星号_给出发现清单`() {
        val output = advise("SELECT * FROM t_user WHERE id = 1")
        assertTrue(output, output.contains("finding"))
        assertTrue("发现行带严重度标记", output.contains("["))
    }

    @Test
    fun `无发现语句_如实输出通过`() {
        val output = advise("SELECT id FROM t_user WHERE id = 1")
        assertTrue(output, output.contains("No performance findings"))
    }

    @Test
    fun `空输入_预期错误`() {
        val error = runCatching { PerfAdviceTool().perfAdvice("") }.exceptionOrNull()
        assertTrue(error is com.intellij.mcpserver.McpExpectedError)
    }
}
