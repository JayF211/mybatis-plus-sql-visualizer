package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ExplainPwdPrompt W3 守卫（2026-10-24）：`McpDbExecutor.executeQuery` 的 `askPassword`
 * 通道表态对账。
 *
 * 背景：`askPassword` 是**必填**参数（编译期强迫每个调用点表态），但编译期挡不住「显式传错」——
 * 面板 UI 通道（结构化执行计划/预估行数）若被改回 false，数据源未存密码时退回「红字不弹框」
 * 缺陷（沙盒实测 2026-10-23）；MCP 工具通道若被改成 true，程序性调用弹模态密码框即挂死
 * （零弹窗红线）。本守卫把三个调用点的表态钉死在源码上，反向：改掉任一表态必红。
 */
class ExplainAskPasswordChannelGuardTest : BannedApiScanSupport() {

    private fun sourceOf(suffix: String): String =
        mainSources().firstOrNull { it.first.endsWith(suffix) }?.second
            ?: error("源文件不在快照中：$suffix")

    @Test
    fun `面板执行计划结构化通道必须允许询问密码`() {
        val code = sourceOf("execute/ExplainPanelChannel.kt")
        assertTrue(
            "ExplainPanelChannel 的 McpDbExecutor.executeQuery 须带 askPassword = true" +
                "（UI 上下文：数据源未存密码时交平台原生密码框，T-ExplainPwdPrompt）",
            code.contains("askPassword = true"),
        )
    }

    @Test
    fun `预估行数与EXPLAIN直送旁路必须允许询问密码`() {
        val code = sourceOf("ui/PreviewSideQuery.kt")
        assertTrue(
            "PreviewSideQuery 的 McpDbExecutor.executeQuery 须带 askPassword = true" +
                "（UI 上下文，T-ExplainPwdPrompt）",
            code.contains("askPassword = true"),
        )
    }

    @Test
    fun `MCP工具通道必须保持零弹窗`() {
        val code = sourceOf("mcp/SqlVisualizerExplainTool.kt")
        assertTrue(
            "SqlVisualizerExplainTool 的 McpDbExecutor.executeQuery 必须显式 askPassword = false" +
                "（MCP 零弹窗红线：程序性调用弹模态密码框即挂死，T-ExplainPwdPrompt）",
            code.contains("askPassword = false"),
        )
    }

    @Test
    fun `提交链必须带lastused取源回落`() {
        val code = sourceOf("ui/SqlPreviewPanelExecution.kt")
        assertTrue(
            "submitExplainOrStructured 必须接 lastUsedExplainDataSource 回落" +
                "（绑定链 runner 枚举被方言路由卡死时，结构化通道靠 last-used 记忆取源到达，T-ExplainPwdPrompt W4）",
            code.contains("lastUsedExplainDataSource"),
        )
    }
}
