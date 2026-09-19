package com.intellij.ml.llm.agents.acp.client

/**
 * T-AcpMcpTransport 编译期桩：IDE 私有 IJ MCP 会话下发给 ACP agent 时可选的传输方式。
 *
 * 签名权威与打包禁令见同源集 [com.intellij.ml.llm.agents.acp.api.AcpAgentId] 头注释。
 * 实测（`java.lang.classfile` 导出自 ml-llm 的 `intellij.ml.llm.agents.acp.jar`）：真类为
 * `Enum`（ACC_ENUM|ACC_FINAL|ACC_PUBLIC），常量恰两个 STDIO / SSE，无 Companion。
 *
 * 常量名与消费点（`AcpIdeaMcpProviderImpl` 字节码实证）：`resolveTransport` 未取到任何 provider
 * 返回值时默认 STDIO；随后按 `mcpCapabilities.getSse()` 兜底——SSE 不被 agent 支持时回落 STDIO。
 */
enum class AcpIdeaMcpTransport {
    STDIO,
    SSE,
}
