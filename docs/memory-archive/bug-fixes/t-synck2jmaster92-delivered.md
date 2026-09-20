---
name: t-synck2jmaster92-delivered
description: T-SyncK2JMaster92 已交付（2026-09-19，锚点 ec584e2→b9aeeba，123 提交同步；含 Java
  侧 fatal 透传形态收窄与守卫两段式口径两处结构性决策，以及三处本轮新发现的移植陷阱）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_dbd1a1c4-1b13-41bf-906b-bf73d1804fd2
---

**2026-09-19 T-SyncK2JMaster92 已交付**（feature/kotlin-to-java，worktree `mybatis-plus-sql-visualizer-kotlin-to-java`，未 push，落后 origin 15 提交）：
同步范围 `ec584e2..b9aeeba` 共 **123 提交**（原计划钉 2d40564/92 提交，作业期间 master 两度前进共 31 提交，按上轮惯例一并吸收、锚点两度改钉）。
提交链：`f7c9313` 立项计划 → `d9f4d91` 生产移植 → `263d689` 测试移植 → `3814236` 收口修复 → `b304599` 文档同步 → `056032c` post-anchor 31 提交。
门禁：**testShards 5390/0/0/6 绿（472 套件）+ testDatabase 10/0/0/0 + buildPlugin 出包 2026.9.18 + verifyGate 三目标 Compatible**（243:4for-removal+10dep+10exp+1internal / 253:6+13+10+1 / 262:7+67+10+1；各 +1 for-removal 与 262 的 +1 dep 为 master 新增 `FatalErrors` 引用 `ThreadDeath` 同源，非翻译漂移）。
内容：生产 4 新建翻译（`util/FatalErrors`、`xml/SecureXml` 492 行、`psi/PsiSnapshot`、`log/agent/AgentUrlRegistry`）+ `acp/AcpIdeaMcpTransportProviderImpl` + 49 增量 + 10 个 MCP 工具类改名；测试 19 新建 + 30 增量 + 3 ACP；`BannedApiSourceScanTest` 1035→2734 行新增五族守卫。

**本轮两处结构性决策（下轮改这些文件前必读，勿当缺陷"修正"回去）**：
1. **Java 侧 fatal 透传必须写 `throw (Error) t`**：master 的 `if (FatalErrors.isFatal(t)) throw t` 在 try 块含受检异常时不编译（javac 精确重抛分析 JLS 11.2.2 要求方法声明该受检异常，公开签名不能加 `throws`）。白名单三类全是 `Error` 子类故收窄零风险、重抛原对象不包装；守卫判定令牌是 `FatalErrors.isFatal(`，照过。
2. **fatal 守卫在 k2j 是两段式（非逐站点判定）**：master 规则「全部 `catch (…: Throwable)` 必须判 fatal 或登记豁免」按 Kotlin 树实测（74 处）；k2j 的 Java 树把每个 `runCatching` 落成 try/catch，实测 **597 处 / 119 文件**（`DataSourceSelector.java` 单文件 142 处）。故改：落地集 6 文件判定数精确相等（DataSourceSelector 16 / AgentManager 8 / AgentSession 3 / RunConfigurationProcessListener 7 / McpDbExecutor 1 / SqlPreviewService 2）+ 未判定站点总量基线 597。守卫内已写移植注说明，勿删。

**本轮新沉淀三处移植陷阱**：
- **掩码器在 Java 树上的涂白缺陷**：`maskNonCode` 的嵌套注释配平遇「注释内不成对收尾符」会把该注释**之后整个文件涂白**（仓内 2 文件命中，多涂白 145/327 行、5 处真实 catch 站点静默脱离扫描面 595→590）。修法=按扩展名选掩码口径（Java 用非嵌套配平）。**新增源码扫描守卫时必须先验"命中数非零"**——`AcpTransportScopeSourceScanTest` 已加扫描集非空自检。
- **Kotlin elvis 左值为可空成员访问的误译**：`decodedExt?.source ?: UNKNOWN` 译成 `decodedExt != null ? decodedExt.source() : UNKNOWN` 会漏内层 null 合并（非 MCP 条目的 pd 载荷无 `s` 键时 source 落 null）。正确形态 `decodedExt != null && decodedExt.source() != null ? … : UNKNOWN`。由 `SqlLogHistoryRoundTripTest` 抓出。
- **MCP 工具改名需全仓 grep 旧前缀**：`mpsv_`→`sqlv_` + toolset 类名加 `SqlVisualizer` 前缀（平台「暴露工具」页分组名取类 simpleName，`ToolsetReflection_utilKt.asTools` 字节码实证）涉及 10 类改名 + `mcp-optional.xml`/`McpToolGate.TOOL_NAMES`/`@McpTool(name)`/bundle/marketplace/19 测试/`PluginXmlStructureGuardTest` toolset 清单；非工具名语境的 `mpsv`（multipart boundary/线程名/临时目录/`urn:mpsv:` 测试串）7 处保留。

**已知未收口（如实登记，非本轮引入）**：`fatalUnwrapUnjudgedBaseline = 5`——`DataSourceSelector` 5 处含 `InvocationTargetException` 的站点未判 `cause`（反射链上目标方法抛出的 Error 被吞成 warn），需另开生产收口任务。

相关：[[project-kotlin-to-java-port-sync]]、[[project-test-conventions]]、[[pitfall-scan-guard-mask-and-sample-power]]
