package com.intellij.mcpserver

/**
 * T-McpGateDynamicFilter（O-68）编译期桩：会话工具句柄。本插件只消费 [descriptor]
 * （262 mcpserver.jar 字节码实证 `getDescriptor(): McpToolDescriptor`）；`call(JsonObject,
 * Continuation)` 等其余成员本插件不引用故不入桩——桩以最小签名面收窄维护成本。桩体永不执行。
 */
interface McpTool {

    val descriptor: McpToolDescriptor
}
