package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MCP 审计行入参摘要测试（T-McpAuditTrail T-MAT-04；纯逻辑，无平台依赖）。
 *
 * 背景（计划 §一 表 4）：审计行存在但**不含入参**——`McpToolsetSupport` 成功路径把字面量 `"ok"`
 * 当 argsSummary 传入，而 `McpAuditLog.summarize` 全仓零调用点（死代码）。本测试覆盖修好之后的
 * 摘要口径：键值拼接、换行压平、空白回落 auto、整体截断至 SUMMARY_LIMIT。
 *
 * 末尾一条是**消费点守卫**（计划 T-MAT-06 ⑤）：源码级断言生产代码真的调用了 summarize——
 * 死代码回归时该守卫先红（纯行为断言测不出「没人调用」）。
 */
class McpAuditLogTest {

    @Test
    fun `summarize 键值拼接并压平换行`() {
        assertEquals("a=1 b=x y", McpAuditLog.summarize("a" to 1, "b" to "x\ny"))
        assertEquals("k=null", McpAuditLog.summarize("k" to null))
        assertEquals("空入参得空串", "", McpAuditLog.summarize())
    }

    @Test
    fun `执行入参摘要_含dataSource与dialect与SQL首段`() {
        val summary = executionArgsSummary("demo-mysql", "MySQL", "SELECT id FROM user WHERE id = 1")
        assertEquals(
            "dataSource=demo-mysql dialect=MySQL sql=SELECT id FROM user WHERE id = 1",
            summary,
        )
    }

    @Test
    fun `执行入参摘要_空白参数回落auto且多行SQL压平`() {
        val summary = executionArgsSummary("  ", "", "SELECT id\nFROM user\nWHERE id = 1")
        assertTrue("省略=自动识别，摘要不应留空值: $summary", summary.startsWith("dataSource=auto dialect=auto "))
        assertFalse("多行 SQL 压平为单行（审计行恒单行）: $summary", summary.contains("\n"))
    }

    @Test
    fun `摘要截断至SUMMARY_LIMIT字符`() {
        // 计划 §五 走查 3：审计行入参摘要不超 120 字符
        assertEquals(120, McpAuditLog.SUMMARY_LIMIT)
        val clipped = McpAuditLog.clip("x".repeat(500))
        assertEquals("截断后 = 上限 + 省略号", McpAuditLog.SUMMARY_LIMIT + 1, clipped.length)
        assertTrue("截断以省略号收尾: $clipped", clipped.endsWith("…"))
        assertEquals("未超限原样返回", "ok", McpAuditLog.clip("ok"))
    }

    @Test
    fun `长SQL的审计摘要不超上限`() {
        val summary = executionArgsSummary("demo", "MySQL", "SELECT * FROM t WHERE " + "a = 1 AND ".repeat(50))
        assertTrue("原始摘要远长于上限: ${summary.length}", summary.length > McpAuditLog.SUMMARY_LIMIT)
        assertEquals(McpAuditLog.SUMMARY_LIMIT + 1, McpAuditLog.clip(summary).length)
    }

    @Test
    fun `消费点守卫_summarize 与执行入参摘要在生产代码被调用`() {
        val main = repoRoot().resolve("src/main/kotlin")
        val kotlinFiles = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val summarizeConsumers = kotlinFiles
            .filter { it.readText().contains("McpAuditLog.summarize(") }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') }
        assertTrue(
            "McpAuditLog.summarize 曾是全仓零调用死代码（T-MAT-04）——须保留至少一个生产消费点，实际: $summarizeConsumers",
            summarizeConsumers.isNotEmpty(),
        )
        val explainTool = main.resolve("cn/song/mybatisplus/visualizer/mcp/ExplainTool.kt")
        assertTrue("ExplainTool 文件应存在: $explainTool", explainTool.isFile)
        assertTrue(
            "mpsv_explain 的审计行须带真实入参摘要（改造前是字面量 \"ok\"）——ExplainTool 未接线",
            explainTool.readText().contains("executionArgsSummary("),
        )
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }
}
