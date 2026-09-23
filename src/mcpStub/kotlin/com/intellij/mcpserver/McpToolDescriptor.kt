package com.intellij.mcpserver

/**
 * T-McpGateDynamicFilter（O-68）编译期桩：工具描述符。262 mcpserver.jar 字节码实证字段面
 * 为 `name/title/description/category/fullyQualifiedName/inputSchema/outputSchema/annotations`
 * 八属性；本插件只消费 [name]（过滤谓词按 `sqlv_` 前缀识别自家工具），其余不入桩。
 * 桩体永不执行。
 */
class McpToolDescriptor(val name: String)
