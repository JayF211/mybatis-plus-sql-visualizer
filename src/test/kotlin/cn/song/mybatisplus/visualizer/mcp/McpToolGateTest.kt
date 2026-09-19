package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * MCP 工具门控测试（T-McpToolset2 / T-MTS2-00）：
 *  - McpToolGate 语义：总开关、工具级禁用、大小写不敏感、默认启用；
 *  - 十个 toolset 类的三钩子：isEnabled 跟随门控、isExperimental=false / alwaysIncluded=false 恒定
 *    （防新工具类漏覆写漂移——按工具拆类后 253 桥安全的关键不变量）。
 */
class McpToolGateTest : BasePlatformTestCase() {

    private val state get() = PluginSettings.getInstance().state

    override fun tearDown() {
        // 恢复默认，避免污染同 suite 其他用例（应用级服务跨用例存活）
        state.mcpToolsEnabled = true
        state.mcpDisabledTools.clear()
        super.tearDown()
    }

    fun test默认全启用() {
        McpToolGate.TOOL_NAMES.forEach { assertTrue(McpToolGate.isEnabled(it)) }
    }

    fun test总开关关_全部不可见() {
        state.mcpToolsEnabled = false
        McpToolGate.TOOL_NAMES.forEach { assertFalse(McpToolGate.isEnabled(it)) }
    }

    fun test工具级禁用_仅该工具不可见() {
        state.mcpDisabledTools.add("sqlv_explain")
        assertFalse(McpToolGate.isEnabled("sqlv_explain"))
        assertTrue(McpToolGate.isEnabled("sqlv_render_sql"))
        assertTrue(McpToolGate.isEnabled("sqlv_mapper_check"))
    }

    fun test禁用清单大小写不敏感() {
        state.mcpDisabledTools.add("MPSC_RenderLogs".let { "sqlv_render_logs".uppercase() })
        assertFalse(McpToolGate.isEnabled("sqlv_render_logs"))
    }

    fun test十个工具类钩子跟门控且稳定集恒定() {
        val toolsets = mapOf(
            "sqlv_render_sql" to SqlVisualizerRenderSqlTool(),
            "sqlv_schema_context" to SqlVisualizerSchemaContextTool(),
            "sqlv_explain" to SqlVisualizerExplainTool(),
            "sqlv_render_logs" to SqlVisualizerRenderLogsTool(),
            "sqlv_check_sql" to SqlVisualizerCheckSqlTool(),
            "sqlv_rollback_plan" to SqlVisualizerRollbackPlanTool(),
            "sqlv_perf_advice" to SqlVisualizerPerfAdviceTool(),
            "sqlv_crud_sql" to SqlVisualizerCrudSqlTool(),
            "sqlv_reverse_sql" to SqlVisualizerReverseSqlTool(),
            "sqlv_mapper_check" to SqlVisualizerMapperCheckTool(),
        )
        assertEquals("toolset 类清单与 McpToolGate.TOOL_NAMES 对齐", McpToolGate.TOOL_NAMES, toolsets.keys.toList())
        toolsets.forEach { (name, toolset) ->
            assertTrue("默认应启用：$name", toolset.isEnabled())
            assertEquals("稳定集恒定（isExperimental）：$name", false, toolset.isExperimental())
            assertEquals("稳定集恒定（alwaysIncluded）：$name", false, toolset.alwaysIncluded())
        }
        state.mcpToolsEnabled = false
        toolsets.forEach { (name, toolset) -> assertFalse("总开关关 → 不可见：$name", toolset.isEnabled()) }
    }
}
