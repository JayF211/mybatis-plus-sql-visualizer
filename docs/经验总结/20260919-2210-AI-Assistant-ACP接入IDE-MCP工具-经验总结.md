# AI Assistant 的 ACP agent 接入插件 MCP 工具 — 经验总结与操作指引

- 日期：2026-09-19 22:10（22:40 合并文档站《JetBrains AI Assistant 搭配插件 MCP-说明文档》）
- 场景：让 JetBrains AI Assistant 的 ACP（Agent Client Protocol）agent 能调用本插件注册到 IDE 内置 MCP Server 的十个只读工具（`sqlv_*`）
- 结论：**Codex（`codex-acp`）与终端路线（Claude Code CLI）均可用；Claude Agent（`claude-agent-acp`）因上游缺陷不可用**，绕行方案见《20260919-2130-T-AcpMcpTransport-ACP会话MCP工具下发修复-开发计划》
- 实测环境：IDEA 2026.2.1（262）runIde 沙盒，插件 2026.9.18
- 文档定位：本文件是**插件侧**的权威源（机制 + 步骤 + 排查方法论），文档站「MCP Server」页面的用户向内容由它提炼；站点侧的《JetBrains AI Assistant 搭配插件 MCP-说明文档》已并入本文
- 工具名说明：下文统一用**定案后的 `sqlv_*`**（见《T-McpToolNaming》）；**当前发行版（2026.9.18）注册的仍是 `mpsv_*`**，改名随该任务发布

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

**前提**：Codex 的 provider 走 **Responses API 且必须接受 `reasoning.summary` 字段**（见 §四，这是唯一的硬性约束）。

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
   - 或走 AI Assistant 的 MCP 传递（见 §三）；
4. **开 MCP 传递**：`Settings → Tools | AI Assistant → Agents` 页底部把 **「Pass IntelliJ MCP server」设为 On**（对应 `acpAgents.xml` 的 `global_use_idea_mcp`）；
5. **认证设为外部托管**：见 §三；
6. **验证**：AI 面板切到 Codex，发一句 `调用 sqlv_check_sql 检查 UPDATE sys_user SET nickname='x' WHERE id=1`。判据两条：
   - 会话记录里出现**原生 MCP 调用**形态：`{"type":"function_call","name":"sqlv_check_sql","namespace":"mcp__idea"}`，或 UI 上的 `mcp: idea/sqlv_check_sql`；
   - `idea.log` 出现 `[MCP-AUDIT] tool=sqlv_check_sql status=OK ...`。

### 路线 B：终端 + Claude Code CLI（最简单，无需 AI Assistant）

在 **IDE 内置 Terminal** 里跑 `claude`，IDE 会弹横幅提示接入，或走 `Settings → Tools | MCP Server → Clients Auto-Configuration` 对 Claude Code 点 Auto-Configure。接入后 `claude mcp list` 应显示 `idea` 为 `✓ Connected`。

**注意两个坑**：
- **端口每次会话都变**（实测 64342 / 64442 交替），IDE 通常会自动把新端点写回 `~/.claude.json`，**不要手工再叠一条**——手工加会与 IDE 写的形成 user/local 作用域冲突，取到过期端点即 401；
- 部分端口处于 restricted 模式需 token，而终端会话**不会**注入 `IJ_MCP_AUTH_TOKEN`，token 只能由接入流程写入配置。

## 三、免认证：认证方式与两个 MCP 传递开关

### 3.1 认证：把认证交给 agent 自己管

AI Assistant 的 ACP 面板会先问用哪种认证方式，三个选项性质完全不同：

| 选项 | 需要什么凭据 | 能否用本地自定义配置 |
| --- | --- | --- |
| JetBrains | JetBrains AI 订阅（要登录账号） | 否——由 JetBrains 提供模型 |
| Anthropic Console | Anthropic API Key | 否——固定走 Anthropic 官方 |
| **使用智能体继续** | **不需要任何凭据** | **是**——认证与模型全交给 agent 自己 |

第三项的内部值是 `MANAGED_EXTERNALLY`，设置完成后界面显示为「**由智能体管理**」：IDE 完全不参与认证，agent 进程按自己的配置文件取端点与密钥（Claude Code 读 `~/.claude/settings.json` 的 `ANTHROPIC_BASE_URL` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_MODEL`，Codex 读 `~/.codex/`）。

**UI 入口只有聊天面板那个弹窗**——`Settings → Tools | AI Assistant → Agents` 页上**只有 MCP 传递开关，没有 per-agent 认证项**（易踩）。面板没给该选项时直接写配置：

```xml
<!-- %APPDATA%\JetBrains\<产品><版本>\options\acpAgents.xml -->
<entry key="acp.registry.claude-acp" value="MANAGED_EXTERNALLY" />
```

`MANAGED_EXTERNALLY` = 由智能体管理（免认证）；`EXPLICIT_SELECTION` = 每次都要显式选（面板会反复要求认证）。改完**重启 IDE** 生效。

**生效判据**：面板认证行显示 `Configured authentication: Managed by agent`，模型选择器显示的是 agent 自己配置里的模型名。

### 3.2 MCP 传递开关一：IDE 设置页

`Settings → Tools | AI Assistant → Agents` 页底部的 MCP 传递项，落盘在 `options/acpAgents.xml`：

| 设置项 | 配置键 | 取值 | 默认 |
| --- | --- | --- | --- |
| 传递 IntelliJ MCP 服务器 | `global_use_idea_mcp` | `OFF` / `ON` / `ON_DEMAND` | **`ON_DEMAND`** |
| 传递自定义 MCP 服务器 | `global_use_custom_mcp` | 布尔 | `true` |

`ON_DEMAND` 的语义是「有活跃 MCP 工具时才传」——代码原文：

```java
if (config.getUseIdeaMcp() == IdeaMcpMode.ON_DEMAND
        && !McpServerService.hasActiveMcpTools(toolFilter, invocationMode)) {
    trace("No active MCP tools available for agent ... in ON_DEMAND mode, skipping IJ MCP server");
    return null;   // 跳过，agent 拿不到任何工具
}
```

所以**默认值 `ON_DEMAND` 在插件工具未被判定为「活跃」时会静默跳过**。要稳定生效，把它设为 **`ON`**。

### 3.3 MCP 传递开关二：`~/.jetbrains/acp.json`（更容易被忽略）

除设置页外，ACP 还有一份**独立的配置文件**，路径写死在插件代码里：

```java
Path userHomeAcpJson = SystemProperties.getUserHome()
    .resolve(".jetbrains").resolve("acp.json").normalize();
```

即 **`~/.jetbrains/acp.json`**（Windows：`C:\Users\<你>\.jetbrains\acp.json`）。它带 JSON Schema 校验，可在 IDE 编辑器里直接编辑：

```json
{
  "default_mcp_settings": {
    "use_idea_mcp": true,
    "use_custom_mcp": true,
    "idea_mcp_allowed_tools": []
  },
  "agent_servers": { },
  "remote": { }
}
```

| 字段 | 含义 | 默认 |
| --- | --- | --- |
| `use_idea_mcp` | 是否把 IntelliJ MCP 服务器暴露给 agent | **`false`** |
| `use_custom_mcp` | 是否把用户自配的 MCP 服务器暴露给 agent | `true` |
| `idea_mcp_allowed_tools` | 允许的工具名清单；**不写 = 全部工具** | 不写 |

官方 schema 对 `use_idea_mcp` 的描述原文是 *"If true, expose the IntelliJ MCP server to agents by default. Default: false."*——**默认关**；作用范围是 *"applied to all local agents unless overridden by agent-specific configuration"*。

**优先级**（`LocalAcpAgentConfig.fromServerConfig` 原文）：`agent_servers.<名称>.use_idea_mcp` > `default_mcp_settings.use_idea_mcp` > 都为空则回落到设置页那个开关。最小可用配置：

```json
{ "default_mcp_settings": { "use_idea_mcp": true } }
```

改完**重启 IDE 或新建聊天**生效。

### 3.4 开关都到位后会发生什么

IDE 会为该 agent 起一个**私有 MCP 会话**并放进 ACP 的 `session/new` 参数，日志可见：

```
AcpIdeaMcpProviderImpl - Running private IJ MCP session for agent acp.registry.claude-acp over Stdio
```

传输方式由扩展点 `com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider` 决定；agent 不支持 SSE 时回落到 **Stdio**。

**另一条不依赖 `use_idea_mcp` 的路**：把 IDE MCP 服务器加进 `Settings → Tools | AI Assistant → Model Context Protocol (MCP)` 页（AI Assistant 的 MCP **客户端**清单，落盘 `options/llm.mcpServers.xml`）——ACP 组装会话参数时会读它（`McpClientRegistry.getConfigurations`），由 `use_custom_mcp` 控制（**默认 true**）。做法：点「Import from Claude」从 `~/.claude.json` 导入（通常已有终端路线写入的 `idea` 条目），或「Add server」手动加。两个注意点：① 勾选页面底部「Automatically enable new and changed MCP servers」并确认 Status 列是启用态；② 导入的 `idea` 条目是**按端口**的 URL，IDE 重启换端口后即失效。

## 四、唯一的硬性约束：provider 必须接受 `reasoning.summary`

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

## 五、Claude Agent 为什么不行（已定位到上游）

`@agentclientprotocol/claude-agent-acp`（0.66 / 0.76 / 0.79 **三版一致**）的 `dist/acp-agent.js`：

```js
if ("type" in server && (server.type === "http" || server.type === "sse")) { /* 正确 */ }
else if (!("type" in server)) {   // ← 要求【没有】type 字段
    // 注释原文：Stdio type MCP server (with or without explicit type field)
```

而 IDE 侧 `com.agentclientprotocol.model.McpServer` 是多态 sealed 类、`Stdio` 标了 `@SerialName("stdio")`，序列化必然带 `"type":"stdio"` → **两个分支都不进，server 被静默丢弃**。注释与实现自相矛盾，属实现缺陷。

对照：`codex-acp` 的 schema `zMcpServerStdio = object({ name, command, args, env, _meta })` **无 type 约束**，zod 默认剥离未知字段 → 带 type 也能解析，所以 Codex 侧正常。

**绕行方案**已立项（`T-AcpMcpTransport`）：注册 `com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider` 扩展点，对该 agent 返回 `AcpIdeaMcpTransport.SSE`——bug 只在 stdio 分支，`type === "sse"` 的分支实现正确，且 claude-acp 已声明 `mcpCapabilities: { http: true, sse: true }`。JetBrains 自己就为 Copilot 这么干（`AcpCopilotIdeaMcpTransportProvider`）。

## 六、官方文档参考（中文版）

> **https://www.jetbrains.com/zh-cn/help/idea/mcp-server.html**
> 标题「MCP 服务器 | IntelliJ IDEA 文档」，对应 IDEA 2026.2。

| 官方小节 | 要点 |
| --- | --- |
| 启用 MCP 服务器插件 | 该功能依赖 MCP 服务器插件，默认已集成并启用；不可用时到 `设置 → 插件 → 已安装` 勾选 |
| 启用 MCP 服务器 | `设置 \| 工具 \| MCP Server` 勾选「启用 MCP 服务器」→ 确认第三方访问提示 → 应用 |
| 外部客户端设置 | 三个入口：「客户端自动配置」、「项目级配置」、「手动配置」（复制 SSE / Stdio / HTTP 流配置） |
| 支持的工具 | 按类别列出平台自带工具（分析、代码洞察、数据库、调试器、执行、文件、格式化、读取、重构、搜索、终端、VCS 等） |

**「暴露工具」路径**：`设置 | 工具 | MCP 服务器 | 暴露工具`——逐工具启用/禁用清单所在页，也是本插件十个 `sqlv_*` 工具出现的地方。

**官方文档未覆盖、属本文增量**：官方页面只讲 MCP 服务器本身与「怎么把客户端接上去」，**不含 AI Assistant 的 ACP 路线**——「使用智能体继续 / 由智能体管理」、`acpAgents.xml` 的认证状态、两个 MCP 传递开关都是 AI Assistant 侧机制。

## 七、键名与文案速查

| 用途 | 键名 | 中文文案 |
| --- | --- | --- |
| ACP 设置页显示名 | `settings.acp.agents.display.name` | 智能体 |
| 认证弹窗标题 | `acp.agent.auth.request.title` | 选择身份验证方式 |
| 认证：交给 agent（免认证） | `acp.agent.auth.option.continue` | 使用智能体继续 |
| 认证已托管的状态 | `acp.settings.auth.managed.externally` | 由智能体管理 |
| MCP：传递 IDE 服务器 | `global_use_idea_mcp`（设置页）/ `default_mcp_settings.use_idea_mcp`（acp.json） | 传递 IntelliJ MCP 服务器（开 / 关 / 按需） |
| MCP：传递自定义服务器 | `global_use_custom_mcp` / `use_custom_mcp` | 传递自定义 MCP 服务器 |

**存储位置对照**：认证方式按 **IDE 实例**存（`options/acpAgents.xml`，纯设置、不含凭据，换实例可整份复制）；agent 注册表（`%APPDATA%\JetBrains\acp-agents\installed.json`）与 agent 自身凭据（`~/.claude`、`~/.codex`）是**机器级**的，换实例自动识别。

## 八、排查方法论（本轮真正省时间的几条）

1. **先分清「IDE 侧」与「agent 侧」**：IDE 侧全部可观测（日志 + 反编译），agent 侧看它自己的会话记录。本轮 IDE 侧逐项验证无误后，问题范围直接收缩到 agent；
2. **agent 自述是最高效的线索**：Claude Agent 直接说「`idea` is configured as an MCP server but isn't in my tool list」，一句话指明了方向；
3. **会话记录在磁盘上**：Claude 的 `~/.claude/projects/<项目>/<sessionId>.jsonl`、Codex 的 `~/.codex/sessions/<年>/<月>/<日>/rollout-*.jsonl`，里面能看到完整的工具调用与报错原文（比 UI 更可靠）；
4. **反编译定位到行**：IDE 装 `plugins/java-decompiler/lib/java-decompiler.jar`，用自带 JBR 跑 `ConsoleDecompiler` 即可解 262 的 v69 字节码；ACP 相关实现在 `ml-llm/lib/modules/intellij.ml.llm.agents.acp*.jar`；
5. **对照实验优先于推理**：同一个 stdio server，Claude 丢弃、Codex 接受，一次对照就把责任方钉死了；
6. **`codex exec` 是最快的等价验证**：它读同一份 `~/.codex/config.toml`，不用点 UI 就能验证 provider 与 MCP 配置；但**它不走 `codex-acp` 那条带 `summary` 的路径**，所以 CLI 通不代表 ACP 通——本轮正是靠这个差异定位到字段兼容问题。

## 九、相关文档

- 绕行方案：《20260919-2130-T-AcpMcpTransport-ACP会话MCP工具下发修复-开发计划》
- 命名收敛：《20260919-2230-T-McpToolNaming-MCP工具分组与命名收敛-开发计划》
- MCP 工具实现：`src/main/kotlin/.../mcp/`、`src/main/resources/META-INF/mcp-optional.xml`
- 文档站用户向页面：`features/mcp.md` 与 `en/features/mcp.md`（工具名已同步为 `sqlv_*`）
