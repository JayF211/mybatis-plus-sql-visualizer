package com.intellij.ml.llm.agents.acp.client

import com.intellij.ml.llm.agents.acp.api.AcpAgentId

/**
 * T-AcpMcpTransport 编译期桩：ACP 会话「私有 IJ MCP 会话传输方式」扩展点接口。
 *
 * 签名权威与打包禁令见同源集 [com.intellij.ml.llm.agents.acp.api.AcpAgentId] 头注释。实测形态：
 *  - 真类为**接口**（ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT），标 `@ApiStatus.Internal`（R1 风险源），
 *    父接口为空，唯一抽象方法 `getTransport(AcpAgentId): AcpIdeaMcpTransport?`（返回可空：参数字面量
 *    带 `@NotNull`，方法返回带 `@Nullable`，常量池双注解实证）；另有 Companion 提供静态
 *    `resolveTransport(AcpAgentId)`（本桩不声明——本项目只实现接口、不调用该静态口）。
 *  - EP 声明（ml-llm 自身 plugin.xml 第 5426 行）：
 *    `<extensionPoint qualifiedName="com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider"
 *      interface="com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider" dynamic="true"/>`；
 *    ml-llm 自带实现 `AcpCopilotIdeaMcpTransportProvider`（同 EP，注册在同一文件第 5445 行）。
 *  - 消费逻辑（`AcpIdeaMcpProviderImpl$getMcpServer$3$1` 字节码实证）：`firstNotNullOfOrNull`
 *    取**首个非 null** 的 provider 返回值；返回 null 即让位给后续注册者（官方 Copilot provider
 *    对非 Copilot agent 正是返回 null）。
 */
interface AcpIdeaMcpTransportProvider {
    /**
     * 返回该 agent 应使用的传输方式；返回 null 表示「不表态」，由后续 provider 或 IDE 默认
     * （STDIO，或按 agent 能力回落）决定。
     */
    fun getTransport(agentId: AcpAgentId): AcpIdeaMcpTransport?
}
