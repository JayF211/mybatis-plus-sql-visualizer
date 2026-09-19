# T-AcpMcpTransport ACP 会话 MCP 工具下发修复（SSE 传输绕行） — 开发计划

- 日期：2026-09-19 21:30
- 任务编号：T-AcpMcpTransport
- 来源：文档站 MCP 演示录制过程中的阻塞排查——《20260919-1500-MCP演示动图与新版UI补录-开发计划》（文档站仓库）§M1-c 需要「AI Assistant 的 ACP 面板内由 agent 调用 `sqlv_*` 工具」，实测调不通，逐层排查后定位到传输方式
- 性质：**上游缺陷绕行 + 互操作修复**——不改工具语义、不改门控、不改 MCP 工具签名，只改 IDE 向 ACP agent 下发 MCP 服务器时所选的传输方式
- 实施分支：建议自 master 拉 `t-acp-mcp-transport`；本轮排查在录制工作树 `mpsv-gif-rec`（分支 `t-gif-rec-mcp`）完成
- 验证基线：master（实施时以当次实测为准）

---

## 一、背景与依据（三层排查，根因已定位到上游 agent）

### 1.1 现象

AI Assistant 的 ACP 会话（Claude Agent）里，agent 的工具表**没有任何 MCP 服务器**。agent 自述原文：

> 「I don't have `sqlv_check_sql` in my tool list.」「`idea` is configured as an MCP server but isn't in my tool list.」

会话记录实证（`~/.claude/projects/F--code-Java-Gitee-SongRenShuo-easy-code-demo/*.jsonl`）：agent 全程只用 `Bash` / `Grep` / `Write`，需要 `sqlv_*` 时**自己用 Bash + curl 手工做 MCP 握手**（先报 `Unable to determine the target project`，补 `projectPath` 后才成功）。

### 1.2 IDE 侧逐项排除（结论：IDE 侧无误）

| # | 环节 | 结论 | 证据 |
|---|------|------|------|
| 1 | 设置开关 | 已开 | `acpAgents.xml` 的 `global_use_idea_mcp=ON`（重启后保留）；`~/.jetbrains/acp.json` 的 `default_mcp_settings.use_idea_mcp=true` |
| 2 | 自定义 MCP 列表 | 能进参数 | 日志 `AcpLocalSessionLifecycleManager - Exposing configured MCP servers for agent acp.registry.claude-acp: Stdio(name=jetbrains, command=npx, args=[-y, @jetbrains/mcp-proxy], env=[])` |
| 3 | 私有 IJ MCP 会话 | 确实创建并进入列表 | 反编译 `AcpLocalSessionLifecycleManager.processMcpServers`：末尾 `if (it != null) { mcpServers.add(it); }`，随后进 `SessionCreationParameters(cwd, mcpServers, ...)` |
| 4 | stdio 命令本身 | **实测可跑通** | `StdioRunnerUtilKt.createStdioMcpServerCommandLine()` 拼的是 `<IDE 启动器> stdioMcpServer` + 环境变量 `IJ_MCP_SERVER_PORT` / `IJ_MCP_SERVER_PROJECT_PATH` / token。手动执行 `<distro>/bin/idea.exe stdioMcpServer`（`IJ_MCP_SERVER_PORT=64342`）→ stderr 输出 `Proxy transports started`，桥接正常 |

### 1.3 根因（上游 agent 侧的判断缺陷）

**AI Assistant 侧**：`com.agentclientprotocol.model.McpServer` 是多态 sealed 类，`Stdio` 子类标了 `@SerialName("stdio")`（`McpServer.java:110`），故序列化必然带判别字段 `"type": "stdio"`。

**agent 侧**：`@agentclientprotocol/claude-agent-acp`（0.66.0 / 0.76.0 / 0.79.0 **三个版本一致**）`dist/acp-agent.js`：

```js
if ("type" in server && (server.type === "http" || server.type === "sse")) { /* http/sse，正确 */ }
else if (!("type" in server)) {     // ← 要求【没有】type 字段
    // 注释原文：Stdio type MCP server (with or without explicit type field)
    mcpServers[server.name] = { type: "stdio", command: server.command, ... };
}
```

**注释与实现自相矛盾**：注释明示「有无显式 type 字段都行」，条件却写成 `!("type" in server)`。带 `type: "stdio"` 的 server 两个分支都不进 → **静默丢弃**。这解释了 1.1 的全部现象。

### 1.4 对照实验（证明该 bug 是 agent 特有，不是协议或 IDE 问题）

`codex-acp@1.4.0` 的 schema 对 stdio **无 type 约束**：

```js
var zMcpServer = union([
  zMcpServerHttp.and(object({ type: literal("http") })),
  zMcpServerSse.and(object({ type: literal("sse") })),
  zMcpServerAcp.and(object({ type: literal("acp") })),
  zMcpServerStdio                    // object({ name, command, args, env, _meta }) — 无 type 字段要求
]);
```

zod 默认剥离未知字段 → 带 `type: "stdio"` 亦能解析。**实测通过**（2026-09-19 21:17，Codex CLI 走同一份 `~/.codex/config.toml`）：

```
mcp: idea/sqlv_check_sql started
mcp: idea/sqlv_check_sql (completed)
返回原文: [{"type":"text","text":"statements: 1\nstatement #1: DML\ndangerous: false\nfindings: none"}]
```

同轮 IDE 日志落审计行 `[MCP-AUDIT] tool=sqlv_check_sql status=OK elapsedMs=5 args=ok`。**说明插件注册的十个工具在 Codex 侧可被正常发现与调用**——问题不在插件、不在 MCP Server 插件、不在工具注册。

### 1.5 绕行的可行性与依据

**传输选择逻辑**（反编译 `AcpIdeaMcpProviderImpl.getMcpServer`）：

```java
AcpIdeaMcpTransport preferredTransport = AcpIdeaMcpTransportProvider.Companion.resolveTransport(agentId);
boolean isSupported = switch (preferredTransport) { case SSE -> mcpCapabilities.getSse(); case STDIO -> true; };
if (!isSupported) preferredTransport = STDIO;
// 之后按 SSE 或 STDIO 建 server
```

**关键机会三条**：

1. **bug 只在 stdio 分支**——`type === "sse"` 的分支实现正确；
2. **claude-agent-acp 声明了 SSE 能力**：其 initialize 响应 `mcpCapabilities: { http: true, sse: true }`（`dist/acp-agent.js`）；
3. **EP 对外开放，且官方已用同一招**：`ml-llm` 的 `plugin.xml` 声明 `<extensionPoint qualifiedName="com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider" interface="...AcpIdeaMcpTransportProvider" dynamic="true" />`，并自带一个实现：

```java
// AcpCopilotIdeaMcpTransportProvider —— Copilot 强制走 SSE
public AcpIdeaMcpTransport getTransport(AcpAgentId agentId) {
    return !Objects.equals(agentId, AcpBundledAgentUtils.INSTANCE.getCOPILOT_ACP_ID())
        ? null : AcpIdeaMcpTransport.SSE;
}
```

即：**让 IDE 以 SSE 型下发私有 IJ MCP 会话，即可绕过 stdio 分支的缺陷**。

## 二、目标与非目标

**目标**：AI Assistant 的 ACP 会话（当前主要针对 `acp.registry.claude-acp`）里，agent 的工具表能出现插件注册的十个 `sqlv_*` 工具，且调用产生 `[MCP-AUDIT]` 审计行。

**非目标**：不改 MCP 工具签名与语义（归各自既有任务）；不改门控（`McpToolGate` / `McpToolsetSupport.isEnabled` 原样）；不改 `McpAuditLog` 与审计格式；**不修上游**（`claude-agent-acp` 的判断缺陷由 JetBrains 侧修复，本任务只做绕行）；不改终端路线（`claude mcp list` 那条已验证可用，与 ACP 无关）。

## 三、方案

**T-AMT-01（注册传输 provider，可选依赖隔离）**：`plugin.xml` 增加 `<depends optional="true" config-file="acp-transport-optional.xml">com.intellij.ml.llm</depends>`，在该配置文件内以 `defaultExtensionNs="com.intellij.ml.llm.agents.acp"` 注册 `<ideaMcpTransportProvider implementation="...AcpIdeaMcpTransportProviderImpl"/>`。**理由**：ml-llm 不在场时整文件不加载，插件零影响——与既有 `mcp-optional.xml`（`com.intellij.mcpServer`）、`database-optional.xml` 同款隔离手法。

**T-AMT-02（provider 实现：返回 SSE）**：实现类对目标 agent 返回 `AcpIdeaMcpTransport.SSE`。取值策略二选一，实施时定：
- (a) **仅对 `acp.registry.claude-acp` 返回 SSE**，其余返回 null（最保守，最小影响面）；
- (b) **对所有 agent 返回 SSE**——由 IDE 侧 `mcpCapabilities.getSse()` 兜底，不支持时自动回落 STDIO，行为安全且覆盖面广。

推荐 (b)，理由：`resolveTransport` 是「首个非 null 者胜出」，返回 null 会让其他注册者（含官方 Copilot provider）继续生效；无条件返回 SSE 不会破坏 Copilot（它本来就要 SSE），而能力不支持的 agent 由 IDE 回落，无副作用。

**T-AMT-03（编译期类型走桩源集）**：provider 需引用 `AcpAgentId` / `AcpIdeaMcpTransport` / `AcpIdeaMcpTransportProvider` 三个 ml-llm 内部类。沿用既有 **`mcpStub` 桩源集**手法（`build.gradle.kts` 中 `mcpStub` 为 `com.intellij.mcpserver` 提供编译期签名的同款做法）：新增/扩展桩源集提供这三个类型的签名，**桩产物不进插件 jar**，运行时由平台真类提供。

**T-AMT-04（验证）**：沙盒内切到 Claude Agent，发一句触发 `sqlv_*` 调用的话，验收两条——会话记录里出现原生 MCP 调用（非 `Bash` + curl），`idea.log` 出现新的 `[MCP-AUDIT]` 行。同时回归：ml-llm 缺席环境（或不注册该 EP 时）插件行为不变。

## 四、验收标准

1. ACP 会话（Claude Agent）中 agent 工具表含十个 `sqlv_*`，且能直接调用（会话记录可见 `mcp__idea__sqlv_*` 形态的工具调用，而非 Bash + curl）；
2. 调用产生 `[MCP-AUDIT]` 行，`status=OK`；
3. 日志出现 `Running private IJ MCP session for agent acp.registry.claude-acp over Sse`（传输由 Stdio 变为 Sse）；
4. ml-llm 不在场时不加载该配置文件，插件其余功能与既有测试全绿；
5. `verifyPlugin` 与既有门禁（testShards）通过。

## 五、真机走查清单（留人工）

- 沙盒（runIde 2026.2.1）内 AI Assistant 面板选 Claude Agent，认证为「由智能体管理」，发一句 `用 sqlv_check_sql 检查 UPDATE sys_user SET nickname='x' WHERE id=1`；
- 核对会话记录与 `idea.log`；
- 另需确认 SSE 通道在沙盒内的可达性（IDE 私有会话改走 SSE 后，agent 侧能否连上——这是本方案唯一未实证的环节）。

## 六、风险

| # | 风险 | 影响 | 应对 |
|---|------|------|------|
| R1 | `AcpIdeaMcpTransportProvider` 接口标了 `@Internal` | 跨 IDE 版本可能变更 | optional config-file 隔离 + 桩源集；版本升级时由既有 verifyPlugin 门禁暴露 |
| R2 | 上游修复后本绕行成为冗余 | 长期维护成本 | issue 报 JetBrains（证据即 §1.3 两段代码）；上游修复后可评估移除 |
| R3 | **SSE 通道未实证**——§1.5 只证明「传输可被指定为 SSE」，未证明 agent 侧能连通 SSE 型私有会话 | 方案可能仍不通 | T-AMT-04 先行验证；若不通，回退到终端路线（已验证） |
| R4 | 改动触及 AI Assistant 集成面 | 可能影响其他 agent 的 MCP 下发 | T-AMT-02 取 (a) 保守策略；或按 (b) 并回归 Copilot |
| R5 | 桩源集扩展不当导致桩产物入 jar | 运行期与平台类冲突 | 沿用 `mcpStub` 既有接线（只挂编译 classpath，不进 runtime/打包），复用其守卫测试 |

## 七、实施顺序

1. T-AMT-03（桩源集与编译期类型）——先让代码能编译；
2. T-AMT-01 + T-AMT-02（EP 注册与 provider 实现）——最小可验证闭环；
3. **T-AMT-04 先行验证**（沙盒内切 Claude Agent 实测）——R3 是本方案成败点，早验早回退；
4. 验证通过后补门禁与回归，再考虑是否扩展到其他 agent。

## 八、实施记录

（待实施后回填）

## 九、实施小结：偏离、阻塞与未验证项

（待实施后回填）
