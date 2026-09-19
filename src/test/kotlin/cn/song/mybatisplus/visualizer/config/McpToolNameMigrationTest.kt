package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.mcp.McpToolGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * MCP 工具名前缀迁移测试（T-McpToolNaming / T-MTN-02）。
 *
 * 背景：`mcpDisabledTools` 存的是**工具名字符串**，而工具名前缀由 `mpsv_` 改为 `sqlv_`——
 * 不做迁移则用户此前禁用的工具在升级后**静默复活**（旧名不再匹配新名）。迁移在
 * [PluginSettings.loadState] 一次性、幂等地完成，本用例覆盖计划 §三 要求的三种输入：
 * 纯旧名、**新旧混存**、**未知名保留**（未知名不得被丢弃或改写）。
 *
 * 另含 loadState 接线用例（防「迁移函数写对但没接线」的假绿）。
 */
class McpToolNameMigrationTest : BasePlatformTestCase() {

    /** 迁移前的十个工具名（历史留档：改名映射的输入侧）。 */
    private val legacyNames = listOf(
        "mpsv_render_sql",
        "mpsv_schema_context",
        "mpsv_explain",
        "mpsv_render_logs",
        "mpsv_check_sql",
        "mpsv_rollback_plan",
        "mpsv_perf_advice",
        "mpsv_crud_sql",
        "mpsv_reverse_sql",
        "mpsv_mapper_check",
    )

    fun test旧名全量映射为新名() {
        assertEquals(
            "十个旧名应一一映射为 McpToolGate.TOOL_NAMES 的新名",
            McpToolGate.TOOL_NAMES,
            migrateMcpDisabledTools(legacyNames),
        )
    }

    fun test新旧混存_全量收敛为新名且顺序保持() {
        val input = mutableListOf("mpsv_explain", "sqlv_mapper_check", "mpsv_render_sql")
        assertEquals(
            listOf("sqlv_explain", "sqlv_mapper_check", "sqlv_render_sql"),
            migrateMcpDisabledTools(input),
        )
    }

    fun test未知名原样保留() {
        val input = mutableListOf(
            "mpsv_explain",
            "some_other_plugin_tool",
            "sqlv_",
            "mpsv",
            "mpsvx_foo",
            "",
        )
        assertEquals(
            "未知名（含边界形态）不得被丢弃或改写，顺序保持",
            listOf("sqlv_explain", "some_other_plugin_tool", "sqlv_", "mpsv", "mpsvx_foo", ""),
            migrateMcpDisabledTools(input),
        )
    }

    fun test大小写不敏感的旧前缀同样迁移() {
        assertEquals(
            listOf("sqlv_RENDER_SQL"),
            migrateMcpDisabledTools(mutableListOf("MPSV_RENDER_SQL")),
        )
    }

    fun test幂等_迁移结果再迁移逐元素不变() {
        val once = migrateMcpDisabledTools(mutableListOf("mpsv_explain", "unknown_tool", "sqlv_render_sql"))
        assertEquals("迁移必须幂等（重复加载不得二次改写）", once, migrateMcpDisabledTools(once))
    }

    fun testloadState接线_旧配置加载即迁移() {
        val settings = PluginSettings.getInstance()
        val original = settings.state
        try {
            settings.loadState(
                PluginSettings.State(mcpDisabledTools = mutableListOf("mpsv_explain", "unknown_x")),
            )
            assertEquals(
                "loadState 必须接线迁移（否则升级后旧禁用项静默复活）",
                listOf("sqlv_explain", "unknown_x"),
                settings.state.mcpDisabledTools,
            )
        } finally {
            settings.loadState(original)
        }
    }
}
