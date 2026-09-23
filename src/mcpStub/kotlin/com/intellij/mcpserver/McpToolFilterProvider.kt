package com.intellij.mcpserver

import com.intellij.mcpserver.impl.McpServerService
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * T-McpGateDynamicFilter（O-68）编译期桩：平台 MCP 工具过滤提供者 API 面。
 *
 * **签名权威与 javap 盲区（2026-09-23 真机事故教训）**：逐条 class 字节码解析自
 * `F:\gradle-home\caches\9.7.0\transforms\1f7754e3f527ba816037b87deb9fb648\transformed\idea-2026.2.3-win\plugins\mcpserver\lib\mcpserver.jar`
 * （IU 262.10968.63，2026-09-23；平台自带实现 DisallowListBasedMcpToolFilterProvider /
 * IndividualRegistryKeyMcpToolFilterProvider 同文件可对照）。**但字节码不含 Kotlin 空源性
 * （只在 @Metadata）——参数可空性必须以「运行时实参 + 平台自带实现的同源形态」为权威**：
 * 本桩曾因把 clientInfo 等标为非空，导致设置页无会话枚举（传 null）时实现类入口判空 NPE，
 * 整页渲染失败（idea.log SEVERE 实证，详见下 applyFilters 注释）。**253 线（idea-2025.3.5
 * 同缓存实测）无此类**——消费方为探测式程序化注册（McpGateFilterRegistrar，挂 mcp-optional.xml），
 * 253 上 Class.forName 探测失败即静默跳过，本桩与其实现类永不被加载。
 *
 * 桩体一律 `error()` 哨兵（运行时按 FQN 解析平台真类，桩本体不打包，见 build.gradle.kts
 * mcpStub 源集注释）。两方法均以**抽象**表达（不留桩默认体）——实现类两个都必须字面量覆写，
 * 类内零接口默认方法引用（253 兼容桥教训，见 McpToolset.kt 桩头注释）。
 */
interface McpToolFilterProvider {

    /**
     * 会话工具清单过滤入口：直接改写 [McpToolFilterContext] 内各工具的状态。
     * 非挂起、返回 void（262 字节码实证）。
     *
     * **三个"环境型"参数一律可空（2026-09-23 真机实证，javap 盲区教训）**：平台「公开的工具」
     * 设置页在无客户端会话时以 `clientInfo=null` 调用本方法（idea.log SEVERE 堆栈实证
     * `McpToolFilterConfigurable.createComponent → rebuildToolGroups → getMcpToolsFiltered`
     * 传入 null）。空源性只存在于 Kotlin @Metadata，**javap/字节码解析看不出**——桩若标非空，
     * 实现类方法入口带 Intrinsics 判空，平台传 null 即 NPE，设置页整面空白。以运行时实参为
     * 空源性权威：可空是唯一安全形态（可空不影响 JVM 描述符与覆写匹配）。
     */
    fun applyFilters(
        context: McpToolFilterContext,
        clientInfo: Implementation?,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode?,
    )

    /** 工具清单更新流（262 字节码实证存在默认实现；实现类仍显式覆写防兼容桥悬空）。 */
    fun getUpdates(
        clientInfo: Implementation?,
        scope: CoroutineScope?,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode?,
    ): Flow<*>

    /** 单工具状态（262 字节码实证：`enabled`/`routerOnly` 两布尔，val + getter）。 */
    class McpToolState(val enabled: Boolean, val routerOnly: Boolean)

    /**
     * 会话工具清单上下文：构造入 `Collection<McpTool>`，公开面只有
     * [updateState] 两个重载与 [onTools]/[routerOnlyTools] 两个结果集
     * （262 字节码实证——类内无 getDescriptor/getName 常量，谓词收 **McpTool 对象** 而非名字）。
     */
    class McpToolFilterContext(tools: Collection<McpTool>) {
        val onTools: Set<McpTool> get() = error("stub: resolved to the platform mcpServer plugin at runtime")
        val routerOnlyTools: Set<McpTool> get() = error("stub: resolved to the platform mcpServer plugin at runtime")

        /** 整套状态替换（262 字节码实证签名）。 */
        fun updateState(state: McpToolState, filter: (McpTool) -> Boolean): Unit =
            error("stub: resolved to the platform mcpServer plugin at runtime")

        /** 按字段增量更新（enabled/routerOnly 为 null 表示不动该字段，262 字节码实证 + $default 桥）。 */
        fun updateState(enabled: Boolean?, routerOnly: Boolean?, filter: (McpTool) -> Boolean): Unit =
            error("stub: resolved to the platform mcpServer plugin at runtime")
    }
}
