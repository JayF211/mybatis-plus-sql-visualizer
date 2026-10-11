package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * MCP 工具门控测试（T-McpToolset2 / T-MTS2-00；R14 T-HistorySearch 起十一工具）：
 *  - McpToolGate 语义：总开关、工具级禁用、大小写不敏感、默认启用；
 *  - 十一个 toolset 类的三钩子：isEnabled 跟随门控、isExperimental=false / alwaysIncluded=false 恒定
 *    （防新工具类漏覆写漂移——按工具拆类后 253 桥安全的关键不变量）；
 *  - 注册三触点对账守卫（R14）：TOOL_NAMES ↔ 动态注册器 TOOL_CLASSES 命名约定一一对应、
 *    每工具描述键双语在场（新增工具漏配即红）。
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

    // ---- T-McpGateDynamicFilter（O-68）：外部会话过滤决策核 ----------------------------

    fun test外部过滤决策_总开关开_自家与外来工具全不隐藏() {
        (McpToolGate.TOOL_NAMES + listOf("search_text", "git_status", "execute_sql_query"))
            .forEach { assertFalse("不应隐藏：$it", McpToolGate.shouldHideFromExternalToolsList(it)) }
    }

    fun test外部过滤决策_总开关关_自家全隐藏外来不误伤() {
        state.mcpToolsEnabled = false
        McpToolGate.TOOL_NAMES.forEach { assertTrue("应隐藏：$it", McpToolGate.shouldHideFromExternalToolsList(it)) }
        // 前缀近似但非本插件命名的工具（平台自带/其他插件）零命中——透传不误伤
        listOf("search_text", "git_status", "sqlvX_y", "sqlv", "sqlv2_render_sql")
            .forEach { assertFalse("不应误伤：$it", McpToolGate.shouldHideFromExternalToolsList(it)) }
    }

    fun test外部过滤决策_工具级禁用_仅该工具隐藏() {
        state.mcpDisabledTools.add("sqlv_explain")
        assertTrue(McpToolGate.shouldHideFromExternalToolsList("sqlv_explain"))
        assertFalse(McpToolGate.shouldHideFromExternalToolsList("sqlv_render_sql"))
        assertFalse(McpToolGate.shouldHideFromExternalToolsList("search_text"))
    }

    fun test十一个工具类钩子跟门控且稳定集恒定() {
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
            "sqlv_history_search" to SqlVisualizerHistorySearchTool(),
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

    // ---- R14 T-HistorySearch：注册三触点对账守卫 ----------------------------------

    /**
     * 动态注册器工具类清单 ↔ 门控工具名单一清单对账（R14 增设——此前 TOOL_CLASSES 为 private
     * 无对账，摘掉任一侧清单新增工具即静默不注册/不显示）。对账靠 T-McpToolNaming 命名约定：
     * SqlVisualizer<CamelTail>Tool → sqlv_<snake_tail>，顺序一一对应。
     */
    fun test注册器工具类清单与工具名清单按命名约定一一对账() {
        assertEquals(
            "TOOL_CLASSES 与 TOOL_NAMES 数量对齐",
            McpToolGate.TOOL_NAMES.size,
            McpToolsetDynamicRegistrar.toolClassesForTest().size,
        )
        McpToolsetDynamicRegistrar.toolClassesForTest().forEachIndexed { index, cls ->
            val tail = cls.simpleName.removePrefix("SqlVisualizer").removeSuffix("Tool")
            assertTrue("工具类必须按 SqlVisualizer<Xxx>Tool 命名：${cls.simpleName}", tail.isNotEmpty())
            val snake = tail.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
            assertEquals(
                "TOOL_CLASSES[$index]=${cls.simpleName} 应对应 TOOL_NAMES[$index]",
                "sqlv_$snake",
                McpToolGate.TOOL_NAMES[index],
            )
        }
    }

    /** 每个工具的设置页描述键双语 bundle 在场（漏配 = 设置页文案抛 MissingResourceException）。 */
    fun test每工具有设置页描述键() {
        McpToolGate.TOOL_NAMES.forEach { toolName ->
            val key = "settings.mcp.tool.desc." +
                toolName.removePrefix("sqlv_").replace("_", "")
            val text = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(key)
            assertTrue("描述键 $key 应有非空文案", text.isNotBlank() && text != key)
        }
    }
}
