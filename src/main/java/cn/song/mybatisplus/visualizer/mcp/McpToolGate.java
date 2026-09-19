// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolGate.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

import java.util.List;

/**
 * MCP 工具门控（T-McpToolset2 / T-MTS2-00）：插件设置页的总开关 + 工具级启用/禁用。
 *
 * 门控语义（262 mcpserver.jar 字节码实证）：`McpToolset.Companion.getEnabledToolsets`
 * = EP `getExtensionList()` 过滤 `isEnabled()`——每次平台 getTools/工具调用重新求值，
 * 设置切换在<b>下一次工具列表或调用即生效</b>，无需重启 IDE 或重连客户端（最差情况为
 * 已连接客户端的工具清单缓存到其下次刷新，插件侧无可控点，真机走查核实）。
 *
 * 本对象<b>零 mcpserver 依赖</b>——McpConfigurable（config 包）可直接复用 {@link #TOOL_NAMES}
 * 构建开关 UI；测试环境（PluginSettings 服务未注册）兜底全启用，与设置默认值一致。
 */
public final class McpToolGate {

    private McpToolGate() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpToolGate INSTANCE = new McpToolGate();

    /** 全部注册工具名（mcp-optional.xml 的 10 个 toolset 一一对应；设置页 UI 与守卫测试共用单一清单）。 */
    public static final List<String> TOOL_NAMES = List.of(
        "mpsv_render_sql",
        "mpsv_schema_context",
        "mpsv_explain",
        "mpsv_render_logs",
        "mpsv_check_sql",
        "mpsv_rollback_plan",
        "mpsv_perf_advice",
        "mpsv_crud_sql",
        "mpsv_reverse_sql",
        "mpsv_mapper_check"
    );

    /** 工具是否对平台可见：总开关开 且 不在工具级禁用清单（大小写不敏感比对）。 */
    public static boolean isEnabled(String toolName) {
        try {
            PluginSettings.State state = PluginSettings.getInstance().getState();
            boolean enabled = state.getMcpToolsEnabled();
            boolean notDisabled = true;
            for (String disabled : state.getMcpDisabledTools()) {
                if (disabled.equalsIgnoreCase(toolName)) {
                    notDisabled = false;
                    break;
                }
            }
            return enabled && notDisabled;
        } catch (Throwable ignored) {
            // 测试环境兜底：服务未注册时全启用（与设置默认值一致）
            return true;
        }
    }
}
