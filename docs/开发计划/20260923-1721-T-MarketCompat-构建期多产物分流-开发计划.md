# T-MarketCompat（O-72）：Marketplace 兼容读数清零——构建期多产物分流

> 20260923 立案，本会话（Marketplace 上传后兼容问题排查）实施。
> 前置事实全部来自实测（见 §一、§二），不再写「待验证前提」之外的推断。

## 一、问题与已证事实

用户上传 Marketplace 后，7 个校验目标里 6 个判兼容致命级（仅 262 Compatible），而本仓 `verifyGate` 长期全绿。本地已完全复现（verifier-cli 在线、无豁免，数字与 Marketplace 页面逐版相同）：

| 目标 | 兼容问题数 | 主要问题 |
|------|-----------|---------|
| 2024.3.7.1 (243) | 5 | `com.intellij.mcpserver` 9 类 + `io.modelcontextprotocol…Implementation` + `kotlin.coroutines.jvm.internal.SpillingKt` |
| 2025.3.5 (253) | 9 | 同上 mcpserver 族 + `SqlVisualizerToolGateFilterProvider`（262-only EP 引用）+ ACP 三包 |
| 2026.2.3 (262) | 0（Compatible） | 7 internal API |

**三重失明（为何门禁看不见）**：
1. `-offline` 不解析可选依赖 → 引用可选插件 API 的类整体跳过（同一 zip 离线 243 报 0、在线报 5）；
2. `-ignored-problems` 清单按包前缀成族豁免（`com.intellij.mcpserver.` + `io.modelcontextprotocol.`）——把 Marketplace 报的内容整族豁免；
3. 判定只看退出码——实测 verifier-cli **带 4 个 compatibility problems 仍退出 0**。

**三条候选修法已对照证伪**（避免无效重构，直接排除）：
- 片段级 `<idea-version since-build=…>`：加进两个可选片段、核实 zip 内确实含门控 → 243/253 读数**一字不变**（verifier 不认片段级 idea-version）；
- 注册器到桥类改反射调用（类内只剩字符串常量）→ 253 问题清单**逐字节不变**（verifier 分析 jar 内全部类，不按声明可达性裁剪）；
- 按版本拆源码产物（用户明确反对）——排除。

**结论**：verifier 按「jar 内全部类 + 在线解析的可选依赖」判定，任何「同一份 jar 喂所有 IDE」的形态都必然在旧版 IDE 上报未解析类。唯一出路 = **构建期按平台分块打包**，让旧版 IDE 的 zip 里**根本不包含**引用新 API 的类。

## 二、方案：构建期按平台分块打包（多产物分流）

核心思路：**一份源码，构建期生成两份（或多份）zip**，各自只含该平台可解析的类。MCP/ACP 相关类只进 262+ 的包；旧版包不含它们（也就没有未解析类），能力按旧版缺失。

### 分块边界（按已验证的事实）

| 类 / 资源 | 归属 |
|-----------|------|
| `mcp/` 包全部（十 toolset、McpToolsetSupport、McpToolGate、McpGateFilter*、McpFilePathResolver 等） | 262+ 包 |
| `acp/` 包（AcpIdeaMcpTransportProviderImpl） | 262+ 包 |
| `config/McpConfigurable`（设置页） | 262+ 包（反射引用平台类，但设置入口归 262 更干净） |
| `mcp-optional.xml` / `acp-transport-optional.xml` / mcp 相关 extends 声明 | 随 262+ 包 |
| 其余全部（预览/日志/回滚/方言/CRUD/补全/执行等） | 两个包都含（核心功能全版本） |

旧版包 = 核心功能（2024.3+ 全兼容）；262+ 包 = 核心 + MCP/ACP。

### 实现形态

用 **Gradle source set 分离 + 条件打包**：
- 新建 `src/main-mcp`（或 `src/mcp-integration`）source set，放 mcp/ + acp/ + McpConfigurable 的**集成接线**（若 mcp 类被核心代码引用，先做「引用收口」——把核心对 mcp 的引用改为反射/接口，见 §三 步骤 1）；
- 主 source set 不含 mcp 类；
- 构建脚本按 `-PmpvIncludeMcp=true` 决定是否把 mcp source set 编进主 jar，并动态决定 plugin.xml 里是否含 `<depends optional config-file="mcp-optional.xml">` 与 `mcp-optional.xml` 资源；
- `buildPlugin` 产出两份 zip：`…-2026.9.23.zip`（核心，since-build=243）与 `…-2026.9.23-mcp.zip`（核心+MCP，since-build=262）；
- `verifyMarketplace` 对两份分别跑：核心包应全目标 Compatible；MCP 包只需 262 目标（since-build=262 起，Marketplace 会只对 262+ 校验）。

### 需先查清的「引用收口」点（§三 步骤 1）

核心代码对 mcp 类的**直接引用**（本会话 grep 到）：`config/PluginSettings`（门控设置）、`config/McpConfigurable`（设置页）、`execute/ExecutionTimeoutGuard`（KDoc 提及）、`log/SqlLogEntryBuffer`（审计）、`util/ExplainIndexAdvisor`（KDoc 提及）。其中仅 KDoc 提及的（ExecutionTimeoutGuard / ExplainIndexAdvisor）无实际引用，其余需改为「核心只定义接口/常量，MCP 侧实现」或反射。

## 三、实施步骤

1. **引用收口**：把核心对 `mcp/` 的直接引用改为接口或反射（`SqlLogEntryBuffer` 的审计入队点、`PluginSettings` 的门控设置键），使核心 source set 编译不依赖 mcp 包；
2. **source set 分离**：新建 `src/main-mcp` 放入 mcp/ + acp/ + McpConfigurable + 两个 optional xml + plugin.xml 的 mcp 段（或构建期动态拼 plugin.xml）；
3. **构建脚本分流**：按 `-PmpvIncludeMcp` 决定主 jar 是否含 mcp source set；产出两份 zip；
4. **verifyMarketplace 适配**：核心包全目标校验；MCP 包仅 262+ 校验；
5. **回归**：定向测试 + testFast + verifyMarketplace 两份包读数 + 旧 verifyGate 三目标；
6. **文档**：计划 §实施记录回填、变更记录、CHANGELOG（对外口径不变）、记忆。

## 四、验收

- 核心包（since-build=243）：`verifyMarketplace` 全目标 **Compatible**（0 兼容问题）；
- MCP 包（since-build=262）：262 目标 Compatible；Marketplace 侧因 since-build 只对 262+ 校验；
- 旧版 IDE（243/251/252/253/261）安装核心包：无 MCP 能力、无兼容问题、其余功能不劣化；
- 262+ 安装 MCP 包：十工具 + 门控 + ACP 传输可用（真机或沙盒验证）。

## 五、待验证前提（实施者先证伪再动手）

1. 「plugin.xml 的 `<depends optional config-file>` 指向一个不存在的 config-file 会拒载整个插件」——若成立，核心包的 plugin.xml **必须**不含 mcp 段（不能只缺文件）；
2. 「构建期动态拼 plugin.xml」与「两个 source set 各自的 extends 声明」哪种形态下 verifier 与平台都能正确加载——先做最小对照；
3. 核心包在 243/251/252/253/261 上**真机**跑一遍（至少 243）确认核心功能无损。

## 六、相关

- 门禁侧已交付：`verifyMarketplace`（4624c69）——在线、无豁免、按 verdict 文本判失败，已挂 releaseGate；
- 本会话分析记录与记忆：`docs/变更记录.md` T-MarketCompatFix 行；本地记忆 pitfall-verification-blindspots §十二。
