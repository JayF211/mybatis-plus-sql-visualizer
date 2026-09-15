package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings

/**
 * MCP 工具门控（T-McpToolset2 / T-MTS2-00）：插件设置页的总开关 + 工具级启用/禁用。
 *
 * 门控语义（262 mcpserver.jar 字节码实证）：`McpToolset.Companion.getEnabledToolsets`
 * = EP `getExtensionList()` 过滤 `isEnabled()`——每次平台 getTools/工具调用重新求值，
 * 设置切换在**下一次工具列表或调用即生效**，无需重启 IDE 或重连客户端（最差情况为
 * 已连接客户端的工具清单缓存到其下次刷新，插件侧无可控点，真机走查核实）。
 *
 * 本对象**零 mcpserver 依赖**——McpConfigurable（config 包）可直接复用 [TOOL_NAMES]
 * 构建开关 UI；测试环境（PluginSettings 服务未注册）兜底全启用，与设置默认值一致。
 */
object McpToolGate {

    /** 全部注册工具名（mcp-optional.xml 的 10 个 toolset 一一对应；设置页 UI 与守卫测试共用单一清单）。 */
    val TOOL_NAMES: List<String> = listOf(
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

    /** 工具是否对平台可见：总开关开 且 不在工具级禁用清单（大小写不敏感比对）。 */
    fun isEnabled(toolName: String): Boolean = runCatching {
        val state = PluginSettings.getInstance().state
        state.mcpToolsEnabled && state.mcpDisabledTools.none { it.equals(toolName, ignoreCase = true) }
    }.getOrDefault(true)
}
