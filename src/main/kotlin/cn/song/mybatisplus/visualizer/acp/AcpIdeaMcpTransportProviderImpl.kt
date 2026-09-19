package cn.song.mybatisplus.visualizer.acp

import com.intellij.ml.llm.agents.acp.api.AcpAgentId
import com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransport
import com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider

/**
 * T-AcpMcpTransport：让 ACP 会话（Claude Agent）以 **SSE** 形式拿到 IDE 私有 IJ MCP 会话，
 * 绕过上游 `@agentclientprotocol/claude-agent-acp` 的 stdio 判断缺陷。
 *
 * **缺陷本体**（0.66.0 / 0.76.0 / 0.79.0 三版一致，`dist/acp-agent.js`）：
 * ```
 * if ("type" in server && (server.type === "http" || server.type === "sse")) { … }
 * else if (!("type" in server)) { …stdio… }   // 注释写「有无显式 type 都行」，条件却要求没有 type
 * ```
 * 而 AI Assistant 序列化 stdio server 必带 `"type": "stdio"`（`com.agentclientprotocol.model.McpServer$Stdio`
 * 标 `@SerialName("stdio")`）→ 两个分支都不进，**静默丢弃**。故 agent 工具表里没有任何 MCP 服务器。
 *
 * **绕行原理**：同一段代码的 `type === "sse"` 分支实现正确，而 `claude-agent-acp` 的 initialize
 * 响应声明 `mcpCapabilities: { http: true, sse: true }`。IDE 侧的选择逻辑（`AcpIdeaMcpProviderImpl$getMcpServer$3$1`
 * 字节码实证）：
 * ```
 * preferred = AcpIdeaMcpTransportProvider.resolveTransport(agentId)   // firstNotNullOfOrNull，首个非 null 胜出
 * supported = when (preferred) { SSE -> mcpCapabilities.sse; STDIO -> true }
 * if (!supported) preferred = STDIO                                  // 能力不支持时自动回落
 * ```
 * 注册本 provider 返回 SSE，即让私有 IJ MCP 会话改走 SSE 分支。
 *
 * **取值策略取 (a) 而非计划推荐的 (b)**（T-AMT-02，理由如下，结论以本注释为准）：
 *  - 上游缺陷**只在 claude-agent-acp 这一个包里**：计划 §1.4 的对照实验已证 `codex-acp@1.4.0` 的
 *    stdio schema 无 type 约束、带 `type: "stdio"` 亦能解析（实测通过）。即 (b) 的「覆盖面广」
 *    并不带来额外修复面——受该 bug 影响的 agent 就是 claude-acp 一个。
 *  - 而 (b) 会给**其余全部 ACP agent**（gemini / junie / codex / 自定义）改传输：凡声明
 *    `sse: true` 者一律从 STDIO 切到 SSE。**R3（SSE 通道 agent 侧可达性）尚未实证**，一旦不通，
 *    (b) 就把当前能用的 agent 一并改坏；(a) 的失败面只落在 claude-acp——它今天本就是坏的，
 *    零回归面。
 *  - (b) 还会长期压过 JetBrains 自身对某个 agent 的传输选择（EP 是「首个非 null 胜出」，
 *    注册顺序不受我们控制），(a) 把这一副作用收窄到单个 agent。
 *  - R4 的应对栏亦指向 (a)（「T-AMT-02 取 (a) 保守策略」）。
 *  若后续 SSE 通道经真机实证可达且稳定，再按计划 §七.4 评估是否放宽到 (b)。
 *
 * **不表态语义**：非目标 agent 返回 null，让位给后续注册者（官方 `AcpCopilotIdeaMcpTransportProvider`
 * 对非 Copilot agent 正是返回 null），Copilot 行为完全不变。
 *
 * **加载隔离**：本类只被 `META-INF/acp-transport-optional.xml` 引用，而该文件仅在
 * `com.intellij.ml.llm` 插件在场时加载（`plugin.xml` 的 `<depends optional="true" config-file=…>`）——
 * AI Assistant 缺席（含基线 243 与全部 CI 目标平台）时整文件不加载，本类永不被实例化/链接。
 * 编译期类型来自 mcpStub 桩源集（桩产物不进插件 jar）。
 */
class AcpIdeaMcpTransportProviderImpl : AcpIdeaMcpTransportProvider {

    override fun getTransport(agentId: AcpAgentId): AcpIdeaMcpTransport? =
        if (agentId.fullId == CLAUDE_ACP_ID) AcpIdeaMcpTransport.SSE else null

    companion object {
        /**
         * Claude Agent 的完整 agent id。字面量取值有两处权威实证：
         *  - `AcpBundledAgentUtils.CLAUDE_ACP_ID = "acp.registry.claude-acp"`（ml-llm 的
         *    `intellij.ml.llm.agents.acp.jar` 常量池，ACC_STATIC|ACC_FINAL 字符串常量）；
         *  - `AcpRegistryAgentId.getFullId()` = 常量前缀 `"acp.registry."` + rawId（字节码实证），
         *    与 `AcpAgentId.REGISTRY_PREFIX` 一致。
         * 故意内联字面量而不引 `AcpBundledAgentUtils`：少一个桩类型即少一处版本漂移面，
         * 而该常量的取值已由上述两处实证钉死。
         */
        const val CLAUDE_ACP_ID: String = "acp.registry.claude-acp"
    }
}
