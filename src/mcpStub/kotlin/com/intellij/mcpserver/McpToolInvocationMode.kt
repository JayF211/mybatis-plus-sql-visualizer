package com.intellij.mcpserver

/**
 * T-McpGateDynamicFilter（O-68）编译期桩：会话调用模式（262 mcpserver.jar 字节码实证，
 * 三枚举值 DIRECT / VIA_ROUTER / DIRECT_WITH_ROUTER_ENABLED；253 线无此类，同
 * McpToolFilterProvider 桩头说明）。桩体永不执行。
 */
enum class McpToolInvocationMode {
    DIRECT,
    VIA_ROUTER,
    DIRECT_WITH_ROUTER_ENABLED,
}
