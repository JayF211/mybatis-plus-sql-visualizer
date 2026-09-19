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

实施分支 `t-acp-mcp-transport`（基点 master `fe423e7`）。提交：`8b4dd8f`（T-AMT-01/02/03 + 守卫测试）、
`43b59be`（T-AMT-04 可测部分的隔离实证）、本文件所在提交（构建脚本注释 + §八/§九 回填）。

### 8.1 T-AMT-03 桩源集与编译期类型

**接线方式：扩既有 `mcpStub` 源集，不新建源集。** 理由：`testShards` 的桩挂载写在
`buildSrc/src/main/kotlin/mpv-testing.gradle.kts:233`（按源集名 `mcpStub` 引用），新建源集必须同时改
convention 插件＝越出本任务写集；扩内容则 `compileKotlin` / `compileTestKotlin` / `test` / `testShards`
四处既有接线全部自动生效。`build.gradle.kts` 只改注释（说明源集名沿用与扩内容的缘由），接线零改动。

新增三个桩文件（与既有 `McpToolset.kt` 同款形态，文件头登记取证方法与打包禁令）：

| 桩类型 | 路径 |
|---|---|
| `com.intellij.ml.llm.agents.acp.api.AcpAgentId` | `src/mcpStub/kotlin/com/intellij/ml/llm/agents/acp/api/AcpAgentId.kt` |
| `com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransport` | `src/mcpStub/kotlin/com/intellij/ml/llm/agents/acp/client/AcpIdeaMcpTransport.kt` |
| `com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider` | `src/mcpStub/kotlin/com/intellij/ml/llm/agents/acp/client/AcpIdeaMcpTransportProvider.kt` |

**签名取证（本机无 JDK 25 的 javap，改走 ClassFile API）**：jdk1.8/17/21/24 的 `javap` 对 major 69
一律报 `Unsupported class file version: 69`，而 ml-llm 模块 jar 正是 major 69。改用 gradle transforms
内 `idea-2026.2.1-win/jbr`（JBR-25.0.3）的 `javac` + `java.lang.classfile` API 写了一个一次性导出器
（`Dump`：类/父类/接口/字段/方法/注解；`Code`：逐指令反汇编），逐类导出签名。取证对象：

`%APPDATA%/JetBrains/IntelliJIdea2026.2/plugins/ml-llm/lib/modules/intellij.ml.llm.agents.acp.jar`
（插件 id `com.intellij.ml.llm`，宿主 IU-262.10968.63）。

导出结论（与桩逐条对应）：

- `AcpIdeaMcpTransportProvider`：`ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT`，父接口为空，唯一抽象方法
  `getTransport(Lcom/intellij/ml/llm/agents/acp/api/AcpAgentId;)Lcom/intellij/ml/llm/agents/acp/client/AcpIdeaMcpTransport;`
  （参数带 `@NotNull`、方法返回带 `@Nullable`，`RuntimeInvisibleParameterAnnotations` + 常量池双注解实证），
  类标 `@ApiStatus.Internal`（R1 风险源）；另有 Companion 静态 `resolveTransport(AcpAgentId)`——桩**不声明**
  该静态口（本项目只实现接口、不调用它）。
- `AcpIdeaMcpTransport`：`java.lang.Enum` 子类，枚举常量恰 `STDIO` / `SSE`，无 Companion。
- `AcpAgentId`：`ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT` + `PermittedSubclasses`（sealed interface），
  抽象属性 `rawId` / `fullId`；实现类 `AcpRegistryAgentId.getFullId()` = 常量前缀 `"acp.registry."` + `rawId`
  （字节码 `INVOKEDYNAMIC makeConcatWithConstants [acp.registry.]` 实证），与 `AcpAgentId.REGISTRY_PREFIX`
  常量一致。

**与真类的刻意偏离**：桩不声明 `sealed`——桩的唯一消费者是 headless 测试里的假 agent id，`sealed` 会让
同模块外的测试实现类无法编译；本项目不实现该接口，链接层面无影响（已在桩文件头注明）。

**R5（桩不进 jar）**：接线未动，仍只挂编译/测试 classpath，实测见 8.5。

### 8.2 T-AMT-01 EP 注册

`plugin.xml` 新增：

```xml
<depends optional="true" config-file="acp-transport-optional.xml">com.intellij.ml.llm</depends>
```

新文件 `src/main/resources/META-INF/acp-transport-optional.xml` 内注册 provider。**注册形态取 EP 属主
ml-llm 自己的写法**（`defaultExtensionNs="com.intellij"` + 点分本地名）：

```xml
<extensions defaultExtensionNs="com.intellij">
    <ml.llm.agents.acp.ideaMcpTransportProvider
        implementation="cn.song.mybatisplus.visualizer.acp.AcpIdeaMcpTransportProviderImpl"/>
</extensions>
```

拼接结果即 EP 全名 `com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider`，与 ml-llm `plugin.xml`
第 5426 行的 `<extensionPoint qualifiedName=… interface="…AcpIdeaMcpTransportProvider" dynamic="true"/>`
逐字对应，且与属主自身在第 5445 行的注册形态同形。计划 §三 建议的
`defaultExtensionNs="com.intellij.ml.llm.agents.acp"`（即 `database-optional.xml` 那种「ns=插件 id + 短名」
变体）与本写法等价，取属主同款零歧义。

### 8.3 T-AMT-02 provider 实现：取 (a)

实现类 `src/main/kotlin/cn/song/mybatisplus/visualizer/acp/AcpIdeaMcpTransportProviderImpl.kt`：
仅当 `agentId.fullId == "acp.registry.claude-acp"` 返回 `AcpIdeaMcpTransport.SSE`，其余返回 `null`。

**改判计划推荐的 (b) 的五条理由**（完整版在实现类 KDoc）：

1. 上游缺陷只在 `claude-agent-acp` 一个包内——计划 §1.4 的 `codex-acp@1.4.0` 对照实验已证其 stdio schema
   无 type 约束、带 `type: "stdio"` 亦能解析（实测通过）。(b) 的「覆盖面广」不带来额外修复面：受该 bug
   影响的 agent 就是 claude-acp 一个。
2. (b) 会给**其余全部 ACP agent**（gemini / junie / codex / 自定义）改传输：凡声明 `sse: true` 者一律从
   STDIO 切到 SSE。**R3（SSE 通道 agent 侧可达性）尚未实证**，一旦不通，(b) 把当前能用的 agent 一并改坏；
   (a) 的失败面只落在 claude-acp——它今天本就是坏的，零回归面。
3. (b) 会长期压过 JetBrains 自身对某个 agent 的传输选择（EP 是「首个非 null 胜出」，注册顺序不受本项目
   控制），(a) 把这一副作用收窄到单个 agent。
4. R4 的应对栏亦指向 (a)（「T-AMT-02 取 (a) 保守策略」）。
5. 计划 §二 的目标本就写明「当前主要针对 `acp.registry.claude-acp`」。

非目标 agent 返回 `null` = 不表态，让位给后续注册者（官方 `AcpCopilotIdeaMcpTransportProvider` 对非
Copilot agent 正是返回 null），Copilot 行为不变。

### 8.4 局部实证（R1 与 §1.5 的字节码复核）

- **`resolveTransport` 确为「首个非 null 胜出 + STDIO 兜底」**：`AcpIdeaMcpTransportProvider$Companion.resolveTransport`
  反汇编为 `EP_NAME.extensionList` 迭代 → `firstNotNullOfOrNull { it.getTransport(agentId) }` → `IFNULL` 时取
  `AcpIdeaMcpTransport.STDIO`。（这正是「返回 null 让位、返回非 null 独占」的机制来源。）
- **IDE 侧消费逻辑与计划 §1.5 引文一致**：`AcpIdeaMcpProviderImpl$getMcpServer$3$1` 反汇编为
  `resolveTransport(agentId)` → `switch { SSE -> mcpCapabilities.getSse(); STDIO -> ICONST_1 }` →
  `if (!supported) preferred = STDIO` → `switch { STDIO -> createStdioMcpServer(...); SSE -> createSseMcpServer(...) }`，
  末尾 `Logger.trace("Running private IJ MCP session for agent {} over {}", agentId, server.getClass().getSimpleName())`
  ——**验收 3 的日志文案「over Sse」取自 server 类简单名**，与本方案改走 SSE 后的预期输出吻合。
- **SSE 型 server 自足**：`createSseMcpServer(port, name, token)` 的 URL 来自
  `McpServerConnectionAddressProvider.httpUrl("/sse", port, null)`（取不到时回落字面量
  `"http://127.0.0.1:" + port + "/sse"`），并附 `HttpHeader`（token）与 projectPath 头——即 SSE 通道
  无需 agent 侧额外配置。这是 R3 的**可行性旁证，不是可达性证明**。
- **官方同款实现确实存在**：`com.intellij.ml.llm.agents.acp.registry.AcpCopilotIdeaMcpTransportProvider`
  反汇编为 `implements com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider`、公开无参构造、
  `public AcpIdeaMcpTransport getTransport(AcpAgentId)`；并在 ml-llm `plugin.xml` 第 5445 行注册于同一 EP。
- **编译产物与真接口逐字对齐**：插件 jar 内 `AcpIdeaMcpTransportProviderImpl.class` 的
  `interfaces=[com/intellij/ml/llm/agents/acp/client/AcpIdeaMcpTransportProvider]`、方法描述符
  `(Lcom/intellij/ml/llm/agents/acp/api/AcpAgentId;)Lcom/intellij/ml/llm/agents/acp/client/AcpIdeaMcpTransport;`
  与真接口抽象方法逐字一致；无默认方法桥（桩接口无默认体，故不生成 `DefaultImpls`）；class file version 61。

### 8.5 T-AMT-04 headless 读数

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `./gradlew compileKotlin compileTestKotlin` | BUILD SUCCESSFUL |
| 新增用例 | `./gradlew test --tests "cn.song.mybatisplus.visualizer.acp.*" --tests "*PluginXmlStructureGuardTest" --tests "*McpScopeSourceScanTest"` | 24 用例 0 失败（acp 9 + plugin.xml 结构 14 + mcp 作用域 2） |
| 既有门禁 | `./gradlew testShards` | BUILD SUCCESSFUL 4m36s；三片合计 **5383 用例 / 0 失败 / 0 错误 / 6 跳过** |
| 兼容门禁 | `./gradlew verifySmoke`（IU-243.28141.41） | IPGP `verifyPlugin` 与权威 `verifyGate` 均 **Compatible**；依赖树含 `(failed) com.intellij.ml.llm (optional): Unavailable`，无缺包/缺类问题；三份问题清单（deprecated / experimental / telemetry）**0 命中本任务新代码** |

**桩产物不进 jar（R5 实测）**：

```
$ ./gradlew buildPlugin && unzip -l build/distributions/mybatis-plus-sql-visualizer-2026.9.18.zip 解出的插件 jar
$ unzip -l …/mybatis-plus-sql-visualizer-2026.9.18.jar | grep -c "com/intellij/mcpserver\|com/intellij/ml/llm"
0                                    # 桩类零命中（mcpStub 与 ml-llm 两个家族都没有）
$ unzip -l … | grep -i "visualizer/acp"
cn/song/mybatisplus/visualizer/acp/AcpIdeaMcpTransportProviderImpl.class        # 只有本项目实现类
cn/song/mybatisplus/visualizer/acp/AcpIdeaMcpTransportProviderImpl$Companion.class
$ unzip -l … | grep "META-INF/.*xml"
META-INF/acp-transport-optional.xml  + 既有四个 optional 文件 + plugin.xml
```

**ml-llm 缺席时插件其余功能不受影响**：新增 `AcpTransportIsolationTest`（`BasePlatformTestCase`，跑在
243 测试沙盒）断言「ml-llm 标记类不可解析（前提）→ 本插件 provider 类仍可解析且实现 EP 声明的接口
（结论）」；plugin.xml 解析一旦失败整个插件缺席、该测试类根本跑不起来，能跑即证 optional 依赖与主文件
共存无碍。全量 testShards 绿进一步覆盖其余功能。

**未跑的 headless 项**：253 / 262 两个 verify 目标——本机 gradle 缓存只有 `ideaIU-2024.3.7.1-win` 与
`idea-2026.2.1-win`，`idea-2025.3.5-win` / `idea-2026.2.3-win` 缺失，跑默认三目标 verifyGate 需先下载
两个发行版（见 §九.3）。

## 九、实施小结：偏离、阻塞与未验证项

### 9.1 偏离计划之处

1. **T-AMT-02 取 (a) 而非推荐 (b)**——理由见 8.3 五条。
2. **桩源集扩 `mcpStub` 而非新建同款源集**——新建源集须改 `buildSrc`（`testShards` 桩挂载按源集名
   引用），越出本任务写集；代价是源集名与新增内容语义不符，已在 `build.gradle.kts` 与三个桩文件头注明。
3. **EP 注册的 `defaultExtensionNs` 形态**取属主 ml-llm 同款（`com.intellij` + 点分本地名），非计划 §三
   字面建议的 `com.intellij.ml.llm.agents.acp`；两者等价（见 8.2），取属主同款零歧义。
4. **R1 的应对栏「版本升级时由既有 verifyPlugin 门禁暴露」经实测不成立**：verifier 对
   `com.intellij.ml.llm` 走的是「optional 依赖不可用」分支（依赖树记 `(failed) … (optional): Unavailable`），
   完全不做类型解析——三个目标平台都没有 AI Assistant 插件，故这些 `@ApiStatus.Internal` 类型的漂移
   **没有任何自动化门禁**。本任务的替代措施是「桩文件头逐条登记取证方法与出处 + 8.4 的 ClassFile API
   复核手法可复跑」，并把它列为待决策项（9.3.2）。
5. **未改 `dev-support/verifier-ignored-problems.txt`**——原以为需按 mcpserver 先例补豁免行，实测不需要
   （243 目标 0 缺包问题，见 8.5）。
6. 未改盘点文档与变更记录（派活方统一处理，按要求）。

### 9.2 环境受限未验证（headless 不可验，禁止推演冒充）

| # | 未验证项 | 缺什么 |
|---|---|---|
| 1 | **R3：SSE 通道 agent 侧可达性**（本方案成败点）——已证「传输可被指定为 SSE」且 SSE server 自带 URL/token/projectPath 头，但未证 agent 进程能连通该端点 | runIde 沙盒 + AI Assistant 面板 + Claude Agent 认证 |
| 2 | 验收 1：ACP 会话工具表出现十个 `sqlv_*` 且为原生 MCP 调用（非 Bash + curl） | 同上 |
| 3 | 验收 2：调用产生 `[MCP-AUDIT]` 行 | 同上 |
| 4 | 验收 3：`idea.log` 出现 `Running private IJ MCP session for agent acp.registry.claude-acp over Sse` | 同上 |
| 5 | ml-llm **在场**时 provider 真被 EP 注册并实例化（EP 加载、类链接成功） | 装 AI Assistant 的沙盒；headless 沙盒无 ml-llm，本任务的隔离用例只证了「缺席时无碍」这一半 |
| 6 | R4：Copilot 回归（(a) 策略下对其返回 null，按 8.4 的消费逻辑不应触及，但未真机复核） | 同 #1 |
| 7 | 253 / 262 verify 目标 | 两个发行版未在本机 gradle 缓存（见 9.3.4） |

### 9.3 待派活方决策

1. **`.gitignore` 第 49 行的 `AGENTS` 规则会忽略桩目录**：Windows 文件系统大小写不敏感，`AGENTS` 命中
   `src/mcpStub/kotlin/com/intellij/ml/llm/agents/`（包路径里的 `agents` 段）。本任务用 `git add -f` 提交，
   文件已在版本库内、正常跟踪；但**后续在该目录下新增文件会被静默忽略**（`git status` 不提示）。建议加一行
   否定规则（`!src/mcpStub/**/agents/`，置于 `AGENTS` 行之后）。未改——`.gitignore` 不在本任务写集。
2. **R1 漂移护栏**：是否补一个针对 ml-llm 的跨版本签名护栏（照 `McpDbSignatureCrossVersionTest` 模式，
   或直接解析 class 文件常量池做到零平台/零运行时依赖）？本任务未做——按计划 §七.4「验证通过后补门禁与
   回归」，在 R3 真机实证前补护栏意义有限；但一旦上游修复落地（R2），这个护栏是发现「绕行已可移除」的
   唯一自动信号。
3. **上游 issue（R2）**：是否向 JetBrains 报 `claude-agent-acp` 的判断缺陷（证据＝计划 §1.3 两段代码 +
   本文件 8.4 的 IDE 侧消费逻辑）。
4. **253 / 262 verify 目标**是否需在本任务内补齐（需下载两个发行版）。

