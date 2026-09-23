package cn.song.mybatisplus.visualizer.mcp

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolInvocationMode
import com.intellij.mcpserver.impl.McpServerService
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * sqlv_* 工具的外部会话动态过滤（T-McpGateDynamicFilter，O-68）：实现平台
 * `com.intellij.mcpServer.mcpToolFilterProvider` EP，把 [McpToolGate] 的门控决策接进
 * **外部 MCP 会话的工具清单路径**。
 *
 * **为什么需要它**：2026-09-23 真机实测（IU 262.10968.63）门控关闭后 `tools/list` 照旧、
 * 调用照常——外部会话清单走 `McpServerService.getMcpToolsFiltered`（FilterProvider EP +
 * 平台设置页偏好），该链不经过 `McpToolset.isEnabled()`（`getEnabledToolsets` 全 jar 唯一
 * 消费方 `ReflectionToolsProvider` 不在此路径，字节码取证见 McpToolGate 头注）。本类即在
 * 平台自己也在用的过滤层（DisallowListBasedMcpToolFilterProvider 等）补上插件门控。
 *
 * **注册**：mcp-filter-optional.xml（根节点 `since-build="262"`——253 线无此 EP，本地
 * idea-2025.3.5 mcpserver.jar 实测；片段级门控保证 253 整文件跳过，不波及 mcp-optional.xml
 * 的十个 toolset）。本类只在 262+ 被平台实例化，不承载 253 兼容面。
 *
 * **过滤语义**：只对名字命中 [McpToolGate.TOOL_NAME_PREFIX] 且门控判定隐藏的自家工具置
 * `enabled=false`，其余工具（平台自带与其他插件）零触碰——谓词里同时做前缀与门控判定，
 * 门控开/恢复时谓词不命中，平台默认状态原样保留。零弹窗、零执行面：本类只改清单可见性，
 * 不触数据库、不触 PSI、无审计面（审计在工具调用链，见 McpAuditLog）。
 */
class SqlVisualizerToolGateFilterProvider : McpToolFilterProvider {

    override fun applyFilters(
        context: McpToolFilterProvider.McpToolFilterContext,
        clientInfo: Implementation?,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode?,
    ) {
        context.updateState(enabled = false, routerOnly = null) { tool ->
            McpToolGate.shouldHideFromExternalToolsList(tool.descriptor.name)
        }
    }

    /**
     * 显式覆写（stub 桩头同款理由）：本插件门控是设置页的静态状态，平台每次列清单都会重走
     * [applyFilters]，无需额外的更新流——空流=永不主动推送，符合「下一次列表/调用即生效」。
     */
    override fun getUpdates(
        clientInfo: Implementation?,
        scope: kotlinx.coroutines.CoroutineScope?,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode?,
    ): Flow<Nothing> = emptyFlow()
}
