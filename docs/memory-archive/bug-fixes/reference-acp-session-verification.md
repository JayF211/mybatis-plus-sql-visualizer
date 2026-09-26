---
name: reference-acp-session-verification
description: 在 runIde 沙盒里验证 ACP/SSE 链路的可行路径与卡点——会话不需要登录就能起（缺的是模型凭据），②的判据日志行在哪、由谁打、EP 怎么被遍历
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_cf206a34-afa0-49b1-9d37-2d62fa9fee75
---

> **归档注记（2026-09-24 第十六次精简）**：ACP 验证清单第⑯项全四项已闭合（`24d6e33`），唯一残留＝agent 发起环需模型凭据（只有用户能提供）；本文作为「日后拿到凭据再验」的入口保留。

**20260923 晚第二轮（262/243 双沙盒 MCP 实测，全部通过，零修复）**：262 沙盒（`-PgifRecord=true`，2026.2.1，build/MPSV-GifFresh）GUI 流程——Settings 内鼠标点击**仍无效但 Tab+Space 可达**（Tab 把焦点移到右侧复选框后空格勾选「Enable MCP Server」→ 弹同意框 → 该 balloon 弹窗**鼠标可点** Enable）。**端点/token 澄清（推翻第十一轮的 401 结论）**：内置 MCP Server 的**免 token 端点是 `http://127.0.0.1:64342/stream`**（initialize 握手直连成功，serverInfo=IntelliJ IDEA MCP Server 2026.2.1）；401 的是 **64442**（router 端点，token 在内存 holder）。读数：`tools/list` 共 62 工具、**sqlv_* 恰好 10 个全在册**（O-68 门控过滤生效）；`tools/call sqlv_check_sql`（无 WHERE UPDATE）正确报 MISSING_WHERE 全表发现；`tools/call sqlv_render_sql`（xmlSnippet+sourceType=xml_mapper）正确渲染 `#{id}`→`?` 并附参数对照；idea.log 两条 `[MCP-AUDIT] status=OK` + `McpGateFilterEpRegistrar - O-68: sqlv gate filter registered` + `Running private IJ MCP session for agent acp.registry.claude-acp over Sse`（ACP 链路活着）。243 沙盒（默认 runIde，现成 IU-2024.3.7.1 沙盒带信任记忆）——插件 `PluginDescriptor` 正常加载、本轮零 ERROR；`mcp-optional.xml` 片段**整段不加载**（mcpServer 缺席 optional depends 不满足，McpGateFilter/mcpServer 日志 0 命中）＝「243 不支持 MCP」的设计口径实证；「SQL Visualizer → MCP Tools」设置页如实呈现（十工具勾选 UI + 顶部「IDEA MCP Server 设置」跳转按钮）。工具脚本：`mpsv-walk/mcp_full.py`（initialize→tools/list→tools/call 全流程，无 token）。

**在 runIde 沙盒里验 ACP/SSE 链路（2026-09-23 上午实测）**：

**会话不需要登录就能起**。沙盒里按 `Alt+Shift+1` 打开 AI Assistant 工具窗，它**直接落在 `Claude Agent`**（＝`acp.registry.claude-acp`）；在输入框发一条消息即触发启动，日志依次出现
`AcpDistributionResolver - Selected npx distribution for acp.registry.claude-acp` →
`AcpAgentUpdateService - Auto-updated agent claude-acp from 0.79.0 to 0.81.0` →
`AcpPackageInstaller - Installing package '@agentclientprotocol/claude-agent-acp@0.81.0'`（约 22 秒装完）。UI 上那个「Choose authentication method：JetBrains / Anthropic Console」**不挡会话建立**。

**② 的判据行**：`Running private IJ MCP session for agent acp.registry.claude-acp over Sse`
由 ml-llm 的 `AcpIdeaMcpProviderImpl.getMcpServer` 打（**FINER** 级，配一条 `Awaiting cancellation of private IJ MCP session for agent …`）。
**传输怎么定的（字节码实证）**：`AcpIdeaMcpTransportProvider$Companion.resolveTransport(agentId)` 的实现是
`ExtensionPointName("com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider").extensionList.firstNotNullOfOrNull { it.getTransport(agentId) }`
——**逐个遍历 EP 注册的实现、取第一个非空**，拿到 `SSE` 就走 `createSseMcpServer` 并打上面那行。故「我方 EP → impl → SSE → 日志」这条链可直接在日志里验。

**③④ 的卡点是模型凭据，不是登录**：会话起了但跑不动，日志 `AiaActivationState$outcome$2 - AIA Activation: jbai=null, byok=null, acp=AcpAuthContext(agentCount=5)`（无 JetBrains AI 许可、无 BYOK key），`QuotaManager2Impl` 持续报 `ResultDoesNotMatchConditionException`；agent 不执行任何一轮 ⇒ 不会去枚举/调用我们的工具（`sqlv_` 与 `MCP-AUDIT` 在日志里 0 命中）。要拿这两条读数，得给沙盒一个模型凭据（JetBrains AI 试用/订阅 或 Anthropic API key）——只有用户能给。

**两个查证坑**：
1. **ml-llm 的类不在 `lib/*.jar`**——ACP 相关类在 `lib/modules/intellij.ml.llm.agents.acp*.jar`（含 `…acp.embeddedMcp.jar` 里的 `AcpIdeaMcpProviderImpl`）。**只 glob `lib/*.jar` 会得出「该类不存在」的错结论**（我第一遍就这么错了），要 `**/*.jar`。
   构建用的制品在 `~/.gradle/caches/9.7.0/transforms/<hash>/com.intellij.ml.llm-<ver>/ml-llm`；装机的是 `%APPDATA%/JetBrains/IntelliJIdea<ver>/plugins/ml-llm`（两者版本可能不同，构建钉的是 `plugin("com.intellij.ml.llm", "262.9437.276")`）。
2. **沙盒的 `plugins/` 目录每次 `runIde` 重建**——退出后 `build/MPSV-GifFresh/…/plugins/ml-llm/lib/*.jar` 就没了，别在退出后去扫它。

相关：[[ml-llm-acp-signature-divergence]]、[[runide-demo-verify-workflow]]、[[pitfall-blame-environment-before-proving-path]]。
