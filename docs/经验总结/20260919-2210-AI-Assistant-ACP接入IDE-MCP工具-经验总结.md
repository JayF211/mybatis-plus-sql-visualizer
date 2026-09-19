# AI Assistant 的 ACP agent 接入插件 MCP 工具 — 经验总结

- 日期：2026-09-19 22:10
- 场景：让 JetBrains AI Assistant 的 ACP（Agent Client Protocol）agent 能调用本插件注册到 IDE 内置 MCP Server 的十个只读工具（`mpsv_*`）
- 结论：**Codex（`codex-acp`）与终端路线（Claude Code CLI）均可用；Claude Agent（`claude-agent-acp`）因上游缺陷不可用**，绕行方案见《20260919-2130-T-AcpMcpTransport-ACP会话MCP工具下发修复-开发计划》
- 实测环境：IDEA 2026.2.1（262）runIde 沙盒，插件 2026.9.18

---

## 一、先分清三个组件，否则排查会跑偏

| 组件 | 插件 id | 是否 IDE 内置 | 作用 |
|------|---------|--------------|------|
| MCP Server | `com.intellij.mcpServer` | **内置**（2025.2+） | 插件的十个工具经 `mcp-optional.xml` 注册到这里 |
| Agent Client Protocol | `com.intellij.platform.acp` | **内置** | 只提供「启动 ACP agent 进程 / 发现 agent」的 API，**不含 UI** |
| AI Assistant | `com.intellij.ml.llm` | **非内置** | 需从 Marketplace 安装；**ACP 的对话面板与设置页都在它里面** |

推论：**没装 AI Assistant 就没有 ACP 界面**，只装 `platform-acp-plugin` 只有 API。反之，AI Assistant 装好后，agent 侧（Codex / Claude Agent 等）的注册表与凭据是**机器级共享**的（`%APPDATA%\JetBrains\acp-agents\installed.json` + `~/.codex`、`~/.claude`），换 IDE 实例不用重装重登。

## 二、两条可用路线（步骤）

### 路线 A：Codex（推荐，实测可用）

**前提**：Codex 的 provider 走 **Responses API 且必须接受 `reasoning.summary` 字段**（见 §三，这是唯一的硬性约束）。

1. **装 AI Assistant**：`Settings → Plugins` 搜 JetBrains AI Assistant 安装；
2. **配 Codex 的 provider**（写 `~/.codex/config.toml`，或经 CC Switch 之类工具）：
   ```toml
   model_provider = "custom"
   model = "<你的模型>"

     [model_providers.custom]
       name = "<供应商名>"
       base_url = "<Responses API 基址>"
       wire_api = "responses"        # 注意：Codex 已移除 chat，只认 responses
       requires_openai_auth = true
   ```
   密钥放 `~/.codex/auth.json` 的 `OPENAI_API_KEY`；
3. **让 Codex 认识 IDE 的 MCP 服务器**（两条任一）：
   - 在 `~/.codex/config.toml` 加：
     ```toml
     [mcp_servers.idea]
       url = "http://127.0.0.1:<MCP 端口>/stream"
     ```
     （端口从 IDE 的 `Settings → Tools | MCP Server` 页取，或看 `idea.log` 的 `[BuiltInMcpStartup] ... port=`）
   - 或走 AI Assistant 的 MCP 传递（下一步）；
4. **开 MCP 传递**：`Settings → Tools | AI Assistant → Agents` 页底部把 **「Pass IntelliJ MCP server」设为 On**（对应 `acpAgents.xml` 的 `global_use_idea_mcp`，取值 OFF/ON/ON_DEMAND，**默认 ON_DEMAND**）；
5. **认证设为外部托管**：ACP 的认证开关**不在 Agents 设置页上**，而在**聊天面板的「选择身份验证方式」弹窗**里——选「**使用智能体继续**」即等价于 `MANAGED_EXTERNALLY`（agent 自己管认证，读它自己的配置文件）。也可直接写 `options/acpAgents.xml`：
   ```xml
   <entry key="acp.registry.codex-acp" value="MANAGED_EXTERNALLY" />
   ```
   若该值是 `EXPLICIT_SELECTION`，面板会反复要求选认证方式；
6. **验证**：AI 面板切到 Codex，发一句 `调用 mpsv_check_sql 检查 UPDATE sys_user SET nickname='x' WHERE id=1`。判据两条：
   - 会话记录里出现**原生 MCP 调用**形态：`{"type":"function_call","name":"mpsv_check_sql","namespace":"mcp__idea"}`，或 UI 上的 `mcp: idea/mpsv_check_sql`；
   - `idea.log` 出现 `[MCP-AUDIT] tool=mpsv_check_sql status=OK ...`。

### 路线 B：终端 + Claude Code CLI（最简单，无需 AI Assistant）

在 **IDE 内置 Terminal** 里跑 `claude`，IDE 会弹横幅提示接入，或走 `Settings → Tools | MCP Server → Clients Auto-Configuration` 对 Claude Code 点 Auto-Configure。接入后 `claude mcp list` 应显示 `idea` 为 `✓ Connected`。

**注意两个坑**：
- **端口每次会话都变**（实测 64342 / 64442 交替），IDE 通常会自动把新端点写回 `~/.claude.json`，**不要手工再叠一条**——手工加会与 IDE 写的形成 user/local 作用域冲突，取到过期端点即 401；
- 部分端口处于 restricted 模式需 token，而终端会话**不会**注入 `IJ_MCP_AUTH_TOKEN`，token 只能由接入流程写入配置。

## 三、唯一的硬性约束：provider 必须接受 `reasoning.summary`

`codex-acp` 在每一轮对话里**无条件**带上该字段（其 `dist/index.js`）：

```js
summary: disableSummary ? "none" : "auto",   // 两个值都带这个键
```

实测各端点的接受度：

| 端点 | 带 `reasoning.summary` 的请求 |
|------|------------------------------|
| `api.deepseek.com/responses` | **200** ✓ |
| `ark.cn-beijing.volces.com/api/v3/responses` | **400** `unknown field "summary"` ✗ |

**注意**：方舟是**键**不认，不是值不认——把 `model_reasoning_summary` 设成 `none` 也没用（CLI 有效、ACP 无效，因为 ACP 那行代码绕过了配置）。所以「火山方舟 + Codex 的 ACP」这个组合目前不可用；换 DeepSeek 类 provider 即通。

排查时可用这个最小对照定位问题在谁身上：

```bash
# 不带 summary → 200 说明端点活着
curl -s -X POST "<base>/responses" -H "Authorization: Bearer <key>" -H "Content-Type: application/json" \
  -d '{"model":"<model>","input":"hi","max_output_tokens":16,"reasoning":{"effort":"high"}}'
# 带 summary → 若 400 unknown field，则该 provider 不能用于 codex-acp
```

## 四、Claude Agent 为什么不行（已定位到上游）

`@agentclientprotocol/claude-agent-acp`（0.66 / 0.76 / 0.79 **三版一致**）的 `dist/acp-agent.js`：

```js
if ("type" in server && (server.type === "http" || server.type === "sse")) { /* 正确 */ }
else if (!("type" in server)) {   // ← 要求【没有】type 字段
    // 注释原文：Stdio type MCP server (with or without explicit type field)
```

而 IDE 侧 `com.agentclientprotocol.model.McpServer` 是多态 sealed 类、`Stdio` 标了 `@SerialName("stdio")`，序列化必然带 `"type":"stdio"` → **两个分支都不进，server 被静默丢弃**。注释与实现自相矛盾，属实现缺陷。

对照：`codex-acp` 的 schema `zMcpServerStdio = object({ name, command, args, env, _meta })` **无 type 约束**，zod 默认剥离未知字段 → 带 type 也能解析，所以 Codex 侧正常。

**绕行方案**已立项（`T-AcpMcpTransport`）：注册 `com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider` 扩展点，对该 agent 返回 `AcpIdeaMcpTransport.SSE`——bug 只在 stdio 分支，`type === "sse"` 的分支实现正确，且 claude-acp 已声明 `mcpCapabilities: { http: true, sse: true }`。JetBrains 自己就为 Copilot 这么干（`AcpCopilotIdeaMcpTransportProvider`）。

## 五、排查方法论（本轮真正省时间的几条）

1. **先分清「IDE 侧」与「agent 侧」**：IDE 侧全部可观测（日志 + 反编译），agent 侧看它自己的会话记录。本轮 IDE 侧逐项验证无误后，问题范围直接收缩到 agent；
2. **agent 自述是最高效的线索**：Claude Agent 直接说「`idea` is configured as an MCP server but isn't in my tool list」，一句话指明了方向；
3. **会话记录在磁盘上**：Claude 的 `~/.claude/projects/<项目>/<sessionId>.jsonl`、Codex 的 `~/.codex/sessions/<年>/<月>/<日>/rollout-*.jsonl`，里面能看到完整的工具调用与报错原文（比 UI 更可靠）；
4. **反编译定位到行**：IDE 装 `plugins/java-decompiler/lib/java-decompiler.jar`，用自带 JBR 跑 `ConsoleDecompiler` 即可解 262 的 v69 字节码；ACP 相关实现在 `ml-llm/lib/modules/intellij.ml.llm.agents.acp*.jar`；
5. **对照实验优先于推理**：同一个 stdio server，Claude 丢弃、Codex 接受，一次对照就把责任方钉死了；
6. **`codex exec` 是最快的等价验证**：它读同一份 `~/.codex/config.toml`，不用点 UI 就能验证 provider 与 MCP 配置；但**它不走 `codex-acp` 那条带 `summary` 的路径**，所以 CLI 通不代表 ACP 通——本轮正是靠这个差异定位到字段兼容问题。

## 六、相关文档

- 绕行方案：《20260919-2130-T-AcpMcpTransport-ACP会话MCP工具下发修复-开发计划》（插件仓库 `docs/开发计划/`）
- 用户侧操作指引：《JetBrains AI Assistant 搭配插件 MCP-说明文档》（文档站仓库 `docs/`）
- MCP 工具本身：插件仓库 `src/main/kotlin/.../mcp/`、`src/main/resources/META-INF/mcp-optional.xml`
