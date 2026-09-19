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
 *
 * T-McpAuditTrail T-MAT-04 回退修复（独立复核）：错误路径的入参段改走 [errorArgsSummary]
 * 两段分预算，错误文本不再被入参摘要挤掉——判据见「错误路径」用例组。
 */
class McpAuditLogTest {

    /** mpsv_explain 长 SQL（摘要 33 + SQL 段远超 120，改前错误文本被整段挤掉的场景）。 */
    private val LONG_SQL = "SELECT * FROM orders WHERE " + "a = 1 AND ".repeat(30)

    /** ExplainTool 失败路径的真实错误文本形态（`[CODE] message`，92 字符）。 */
    private val EXPLAIN_ERROR =
        "[DATASOURCE_UNREACHABLE] EXPLAIN failed on 'MySQL' data source: Table 'orders' doesn't exist"

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
        // T-MAT-04 回退修复：错误路径是「摘要 + error 段」的组合串，截断同样由本路径自行收口
        val payload = errorArgsSummary(summary, EXPLAIN_ERROR)
        assertTrue("错误路径总长仍受上限约束: ${payload.length}", payload.length <= McpAuditLog.SUMMARY_LIMIT)
        assertTrue(
            "错误文本须完整在场——改前（整串 clip）SQL 段 >= 113 字符时 error 文本整段消失: $payload",
            payload.contains(EXPLAIN_ERROR),
        )
    }

    // ------------------------------------------------------------------
    // T-McpAuditTrail T-MAT-04 回退修复：错误路径的入参段
    // 改前 `McpAuditLog.clip("<摘要> error=<文本>")` 让入参摘要先占满 120 字符预算——
    // mpsv_explain 的 sqlLen >= 113 时错误文本整段消失（长 SQL 恰是最需要错误文本的场景）。
    // 修法=两段分预算（错误文本优先，摘要让位，总长仍 120 封顶），见 errorArgsSummary。
    // ------------------------------------------------------------------

    @Test
    fun `mpsv_explain长SQL的错误路径_错误文本完整且总长不超上限`() {
        val summary = executionArgsSummary("demo", "MySQL", LONG_SQL)
        assertTrue("前置：SQL 摘要已超上限（改前即 error 段被挤掉的场景）", summary.length > McpAuditLog.SUMMARY_LIMIT)
        val payload = errorArgsSummary(summary, EXPLAIN_ERROR)
        assertTrue("错误文本应逐字完整（不被入参摘要挤掉）: $payload", payload.contains(EXPLAIN_ERROR))
        assertTrue("入参摘要仍须在场（只让位不消失）: $payload", payload.startsWith("dataSource=demo"))
        assertTrue("错误路径总长不超上限: ${payload.length}", payload.length <= McpAuditLog.SUMMARY_LIMIT)
    }

    @Test
    fun `错误路径两段分预算_错误文本优先且摘要保底在场`() {
        // 极端：错误文本本身超预算——摘要退到保底长度，错误文本吃满剩余预算（仍带错误码）
        val summary = executionArgsSummary("demo", "MySQL", LONG_SQL)
        val payload = errorArgsSummary(summary, EXPLAIN_ERROR + " " + "x".repeat(300))
        assertTrue("摘要保底在场（数据源标识不消失）: $payload", payload.startsWith("dataSource=demo"))
        assertTrue("错误文本占据剩余预算且带错误码: $payload", payload.contains("[DATASOURCE_UNREACHABLE]"))
        assertTrue("错误路径总长不超上限: ${payload.length}", payload.length <= McpAuditLog.SUMMARY_LIMIT)
        assertTrue("超预算时以省略号收尾: $payload", payload.endsWith("…"))
    }

    @Test
    fun `错误路径_错误文本较短时余量还给入参摘要`() {
        val summary = executionArgsSummary("demo", "MySQL", LONG_SQL)
        val shortError = "[SQL_BLOCKED] not a single SELECT."
        val payload = errorArgsSummary(summary, shortError)
        assertTrue("短错误文本逐字完整: $payload", payload.endsWith(shortError))
        assertTrue("余量还给摘要（SQL 首段可见）: $payload", payload.contains("sql=SELECT"))
        assertTrue("错误路径总长不超上限: ${payload.length}", payload.length <= McpAuditLog.SUMMARY_LIMIT)
    }

    @Test
    fun `静态工具错误路径_摘要与错误文本同在`() {
        // 九个纯静态工具的 argsSummary 是字面量 "ok"（4 字符级），错误文本不再被它挤掉
        assertEquals("ok error=[SQL_BLOCKED] not a single SELECT.", errorArgsSummary("ok", "[SQL_BLOCKED] not a single SELECT."))
        val longError = errorArgsSummary("ok", "[DATASOURCE_UNREACHABLE] " + "x".repeat(200))
        assertTrue("静态工具错误行仍以 args=ok 起头: $longError", longError.startsWith("ok error="))
        assertTrue("长错误文本仍受上限约束: ${longError.length}", longError.length <= McpAuditLog.SUMMARY_LIMIT)
    }

    @Test
    fun `错误路径总长恒不超SUMMARY_LIMIT`() {
        for (argsLen in listOf(0, 2, 16, 40, 200)) {
            for (errorLen in listOf(0, 10, 60, 97, 120, 400)) {
                val payload = errorArgsSummary("a".repeat(argsLen), "e".repeat(errorLen))
                assertTrue(
                    "args=$argsLen error=$errorLen -> ${payload.length} 字符: $payload",
                    payload.length <= McpAuditLog.SUMMARY_LIMIT,
                )
            }
        }
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
