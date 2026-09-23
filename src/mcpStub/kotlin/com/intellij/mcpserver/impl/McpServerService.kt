package com.intellij.mcpserver.impl

/**
 * T-McpGateDynamicFilter（O-68）编译期桩：平台 MCP 服务壳。仅为
 * [com.intellij.mcpserver.McpToolFilterProvider.applyFilters] 签名里的嵌套类型
 * [McpSessionOptions] 提供编译期 FQN（262 mcpserver.jar 字节码实证其存在性与嵌套位置；
 * 253 线无此类）。桩体永不执行、成员永不引用。
 */
class McpServerService private constructor() {

    /** 会话级选项（262 字节码实证嵌套类存在；字段面未反编译——本插件只收型不触成员）。 */
    class McpSessionOptions private constructor()
}
