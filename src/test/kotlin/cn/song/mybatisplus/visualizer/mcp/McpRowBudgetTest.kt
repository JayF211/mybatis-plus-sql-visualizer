package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-McpRowBudget 行预算可配守卫（竞品第八轮报告 §8 #37 / O-36）。
 *
 * 覆盖：
 *  1. [McpExplainFormatter.clampMaxRows] 边界表（null / 非正 / 1 / 默认值 / 硬上限 / 越界 / Int.MAX_VALUE）；
 *  2. 默认路径的输出与改造前**逐字节一致**（期望串是硬编码字面量，不引用常量——常量改名或改值即红）；
 *  3. 截断提示报的是**本次生效预算**（maxRows=10 时不许再报 200）；
 *  4. 通道层兜底 [channelRowBudget]：绕过工具层直接给超大值仍被钳制（验收 §四.3）；
 *  5. 源码级接线守卫（本仓守卫族惯例，形态见 McpScopeSourceScanTest）：工具层确实把钳制值喂给通道、
 *     通道确实按兜底值采集、登记表数值与常量一致——防「改了常量忘了接线 / 忘了登记」的静默漂移。
 */
class McpRowBudgetTest {

    // ------------------------------------------------------------------
    // 夹具：仓库根定位与源码读取
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun mainSource(relativeToMcpPackage: String): String =
        repoRoot()
            .resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/$relativeToMcpPackage")
            .readText()

    /** 剥离块注释与行注释（与 McpScopeSourceScanTest 同款）——接线守卫只关心代码实体。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    // ------------------------------------------------------------------
    // 1) 钳制边界表
    // ------------------------------------------------------------------

    @Test
    fun `行预算钳制_边界表`() {
        val cases: List<Pair<Int?, Int>> = listOf(
            null to McpExplainFormatter.DEFAULT_MAX_ROWS,
            0 to McpExplainFormatter.DEFAULT_MAX_ROWS,
            -7 to McpExplainFormatter.DEFAULT_MAX_ROWS,
            Int.MIN_VALUE to McpExplainFormatter.DEFAULT_MAX_ROWS,
            1 to 1,
            10 to 10,
            McpExplainFormatter.DEFAULT_MAX_ROWS to McpExplainFormatter.DEFAULT_MAX_ROWS,
            McpExplainFormatter.DEFAULT_MAX_ROWS + 1 to McpExplainFormatter.DEFAULT_MAX_ROWS + 1,
            McpExplainFormatter.HARD_MAX_ROWS to McpExplainFormatter.HARD_MAX_ROWS,
            McpExplainFormatter.HARD_MAX_ROWS + 1 to McpExplainFormatter.HARD_MAX_ROWS,
            Int.MAX_VALUE to McpExplainFormatter.HARD_MAX_ROWS,
        )
        for ((requested, expected) in cases) {
            assertEquals("maxRows=$requested 应钳到 $expected", expected, McpExplainFormatter.clampMaxRows(requested))
        }
    }

    /** 常量口径钉死：默认值必须还是改造前的 200（默认行为逐字节一致的前提），上限对齐 DBX 1000。 */
    @Test
    fun `行预算常量_口径钉住`() {
        assertEquals("默认预算须与改造前一致", 200, McpExplainFormatter.DEFAULT_MAX_ROWS)
        assertEquals("硬上限须对齐 DBX MAX_EXECUTE_QUERY_ROWS", 1000, McpExplainFormatter.HARD_MAX_ROWS)
        assertTrue(
            "硬上限不应小于默认预算",
            McpExplainFormatter.HARD_MAX_ROWS >= McpExplainFormatter.DEFAULT_MAX_ROWS,
        )
    }

    // ------------------------------------------------------------------
    // 2)+3) 输出形态：默认逐字节一致 / 生效预算如实报数
    // ------------------------------------------------------------------

    @Test
    fun `截断提示_默认路径逐字节一致`() {
        val raw = McpExplainFormatter.RawResult(
            headers = listOf("plan"),
            rows = listOf(listOf("row0"), listOf("row1"), listOf("row2")),
            truncated = true,
        )
        // 期望串硬编码（不引用常量）：常量被改名或改值都必须让本用例变红
        val expected = "plan\nrow0\nrow1\nrow2\n\n" +
            "[TRUNCATED] output limited to 200 rows; the actual plan may have more."
        assertEquals("默认路径输出须与 T-McpRowBudget 之前逐字节一致", expected, McpExplainFormatter.format(raw))
        assertEquals(
            "显式传默认预算须与不传参等价",
            McpExplainFormatter.format(raw),
            McpExplainFormatter.format(raw, McpExplainFormatter.DEFAULT_MAX_ROWS),
        )
    }

    @Test
    fun `截断提示_报本次生效预算`() {
        val raw = McpExplainFormatter.RawResult(
            headers = listOf("plan"),
            rows = List(10) { listOf("r") },
            truncated = true,
        )
        val text = McpExplainFormatter.format(raw, 10)
        assertTrue("应报本次生效预算: $text", text.contains("[TRUNCATED] output limited to 10 rows"))
        assertFalse("不应再报默认预算: $text", text.contains("limited to 200 rows"))
        assertTrue("未截断时不应有提示", !McpExplainFormatter.format(raw.copy(truncated = false), 10).contains("TRUNCATED"))
    }

    // ------------------------------------------------------------------
    // 4) 通道层兜底（绕过工具层）
    // ------------------------------------------------------------------

    @Test
    fun `通道层兜底_绕过工具层仍被钳制`() {
        assertEquals(
            "Int.MAX_VALUE 应钳到硬上限",
            McpExplainFormatter.HARD_MAX_ROWS,
            channelRowBudget(Int.MAX_VALUE),
        )
        assertEquals(
            "越界正值应钳到硬上限",
            McpExplainFormatter.HARD_MAX_ROWS,
            channelRowBudget(McpExplainFormatter.HARD_MAX_ROWS + 1),
        )
        assertEquals("0 应回落默认预算", McpExplainFormatter.DEFAULT_MAX_ROWS, channelRowBudget(0))
        assertEquals("负数应回落默认预算", McpExplainFormatter.DEFAULT_MAX_ROWS, channelRowBudget(-1))
        assertEquals("合法值原样透传", 10, channelRowBudget(10))
        assertEquals(
            "通道兜底与工具层共用同一钳制语义",
            McpExplainFormatter.clampMaxRows(Int.MAX_VALUE),
            channelRowBudget(Int.MAX_VALUE),
        )
    }

    // ------------------------------------------------------------------
    // 5) 接线与登记守卫
    // ------------------------------------------------------------------

    @Test
    fun `接线守卫_工具层与通道层`() {
        val tool = stripComments(mainSource("mcp/ExplainTool.kt"))
        assertTrue(
            "mpsv_explain 签名应含 maxRows 可选参数（默认值取常量）",
            tool.contains("maxRows: Int = McpExplainFormatter.DEFAULT_MAX_ROWS"),
        )
        assertTrue("工具层应把钳制值喂给前置链", tool.contains("McpExplainFormatter.clampMaxRows(maxRows)"))
        // 独立复核条件订正：原断言 `tool.contains("target.maxRows")` 是**整文件子串检查**，而
        // `target.maxRows` 在 executeQuery 调用处（下方 :132 一带）本就出现——若把格式化调用回退成
        // `format(raw)`，该守卫仍绿（假绿）。改为匹配**格式化调用点**的精确串。
        assertTrue(
            "执行与格式化都应取生效预算：格式化调用点须显式传 target.maxRows",
            tool.contains("McpExplainFormatter.format(raw, target.maxRows)"),
        )
        assertTrue(
            "执行链调用点也应取生效预算",
            tool.contains("target.maxRows"),
        )
        assertTrue(
            "工具描述应说明默认/上限口径",
            tool.contains(
                "default ${McpExplainFormatter.DEFAULT_MAX_ROWS}, hard limit ${McpExplainFormatter.HARD_MAX_ROWS}",
            ),
        )

        val channel = stripComments(mainSource("mcp/McpDbExecutor.kt"))
        assertTrue("通道应先按兜底值覆盖入参", channel.contains("val rowBudget = channelRowBudget(maxRows)"))
        assertTrue("采集应按兜底值截断", channel.contains("collect(resultSet, rowBudget)"))
    }

    /** 登记表守卫（验收 §四.4）：McpToolsetSupport 的输出预算登记表数值必须与常量一致。
     *  KDoc 本身就是登记处，故此用例**不剥注释**。 */
    @Test
    fun `登记表_与常量一致`() {
        val registration = mainSource("mcp/McpToolsetSupport.kt")
        val start = registration.indexOf("mpsv_explain：")
        assertTrue("登记表应含 mpsv_explain 条目", start >= 0)
        val end = registration.indexOf("\n *  - ", start).let { if (it < 0) registration.length else it }
        val entry = registration.substring(start, end)
        assertTrue("登记行应引用常量名: $entry", entry.contains("DEFAULT_MAX_ROWS") && entry.contains("HARD_MAX_ROWS"))
        assertTrue(
            "登记行默认值应与常量一致: $entry",
            entry.contains("默认 ≤${McpExplainFormatter.DEFAULT_MAX_ROWS}"),
        )
        assertTrue(
            "登记行上限应与常量一致: $entry",
            entry.contains("上限 ${McpExplainFormatter.HARD_MAX_ROWS}"),
        )
    }
}
