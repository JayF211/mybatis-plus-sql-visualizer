package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sqlv_rollback_plan 工具测试（T-McpToolset2 / T-MTS2-01）：主键 COMPLETE 路径、UPDATE 的
 * UNABLE 诚实拒绝（英文警示经 messageEn 渲染 bundle 既有英文键）、空输入拒绝。
 * 生成细节由 RollbackSqlGeneratorTest 覆盖。
 */
class RollbackPlanToolTest {

    private fun plan(sql: String, pk: String = "") = SqlVisualizerRollbackPlanTool().rollbackPlan(sql, pk)

    @Test
    fun `主键INSERT_评级COMPLETE并给出回滚语句`() {
        val output = plan("INSERT INTO t_user (id, name) VALUES (1, 'Tom')", pk = "id")
        assertTrue(output, output.contains("rating: COMPLETE"))
        assertTrue(output, output.contains("DELETE FROM t_user WHERE id = 1"))
    }

    @Test
    fun `无主键INSERT_评级PARTIAL并附自查模板`() {
        val output = plan("INSERT INTO t_user (id, name) VALUES (1, 'Tom')")
        assertTrue(output, output.contains("rating: PARTIAL"))
        assertTrue(output, output.contains("count check (run first): SELECT COUNT(*)"))
    }

    @Test
    fun `UPDATE_诚实UNABLE并给英文恢复指引`() {
        val output = plan("UPDATE t_user SET name = 'x' WHERE id = 1")
        assertTrue(output, output.contains("rating: UNABLE"))
        assertTrue(output, output.contains("statements: (none"))
        assertTrue("警示应渲染为英文（messageEn）", output.contains("warnings:"))
    }

    @Test
    fun `空输入_预期错误`() {
        val error = runCatching { SqlVisualizerRollbackPlanTool().rollbackPlan("", "") }.exceptionOrNull()
        assertTrue(error is com.intellij.mcpserver.McpExpectedError)
    }
}
