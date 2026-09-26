// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolGate.kt 移植（增量同步 c07b8e3→bccbfc5）
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

import java.util.List;

/**
 * MCP 工具门控（T-McpToolset2 / T-MTS2-00）：插件设置页的总开关 + 工具级启用/禁用。
 *
 * <p><b>门控的两条生效路径（O-68 订正——原「平台每次 getTools 重读 isEnabled」的断言只对
 * toolset 路径成立）</b>：</p>
 * <ol>
 *   <li><b>toolset 路径</b>：{@code McpToolset.Companion.getEnabledToolsets} 按 {@code isEnabled()}
 *       过滤——全 jar 字节码扫描（262.10968 mcpserver.jar，791 类）该过滤仅被
 *       {@code ReflectionToolsProvider} 消费（基座/内部面），不在 agent 真正看到的外部会话清单路径上；</li>
 *   <li><b>外部会话清单路径</b>：{@code McpServerService.getMcpToolsFiltered} = 基座清单 +
 *       {@code McpToolFilterProvider} EP 过滤层 + 平台设置页偏好——该链<b>不经过</b>
 *       {@code isEnabled}。故 O-68 新增 SqlVisualizerToolGateFilterProvider 注册
 *       {@code com.intellij.mcpServer.mcpToolFilterProvider}（注册链见 {@link McpGateFilterRegistrar}
 *       / {@link McpGateFilterEpBridge}），以 {@link #shouldHideFromExternalToolsList}
 *       在过滤层动态隐藏自家工具。</li>
 * </ol>
 *
 * <p>设置切换在两条路径均为下一次工具列表/调用重新求值，无需重启 IDE。</p>
 *
 * <p>本对象<b>零 mcpserver 依赖</b>——McpConfigurable（config 包）可直接复用 {@link #TOOL_NAMES}
 * 构建开关 UI；测试环境（PluginSettings 服务未注册）兜底全启用，与设置默认值一致。</p>
 */
public final class McpToolGate {

    private McpToolGate() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpToolGate INSTANCE = new McpToolGate();

    /** 自家工具名前缀（命名硬约定 T-McpToolNaming 同源；O-68 过滤谓词按此识别自家工具）。 */
    public static final String TOOL_NAME_PREFIX = "sqlv_";

    /** 全部注册工具名（mcp-optional.xml 的 10 个 toolset 一一对应；设置页 UI 与守卫测试共用单一清单）。 */
    public static final List<String> TOOL_NAMES = List.of(
        "sqlv_render_sql",
        "sqlv_schema_context",
        "sqlv_explain",
        "sqlv_render_logs",
        "sqlv_check_sql",
        "sqlv_rollback_plan",
        "sqlv_perf_advice",
        "sqlv_crud_sql",
        "sqlv_reverse_sql",
        "sqlv_mapper_check"
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

    /**
     * O-68 外部会话过滤决策核（SqlVisualizerToolGateFilterProvider 谓词同款，单测直测）：
     * 该工具是否应从平台外部 MCP 会话的工具清单隐藏——只对 {@link #TOOL_NAME_PREFIX} 前缀的自家
     * 工具给判定（谓词内前置短路，外来工具零命中、透传不误伤），隐藏语义与 {@link #isEnabled}
     * 同源（总开关 ∧ 工具级禁用清单，大小写不敏感）。
     */
    public static boolean shouldHideFromExternalToolsList(String toolName) {
        return toolName.startsWith(TOOL_NAME_PREFIX) && !isEnabled(toolName);
    }
}
