# T-SyncK2JMaster92 开发计划——master ec584e2..2d40564 增量同步

> 立项日期：2026-09-19　分支：feature/kotlin-to-java（worktree `mybatis-plus-sql-visualizer-kotlin-to-java`）
> 同步源：**本地 master `b9aeeba`**（原计划钉 `2d40564`，作业期间 master 两度前进共 31 提交，按上轮惯例一并吸收并把锚点改钉 `b9aeeba`——见 §8「锚点变更说明」）
> 同步范围：`git diff ec584e2..b9aeeba` —— **123 提交**；§2/§3 的量化矩阵保留原 92 提交口径（那是立项基线），实际落盘见 §8
> 上轮收官：T-SyncK2JMaster309（4f1076b，全量 testShards 5183/0/0/6 绿 + testDatabase 10/0/0/0 + buildPlugin 出包 2026.9.18 + verifyGate 三目标 Compatible，锚点 ec584e2）
> 用户指示：**同步功能、不合并分支**（方案 A 手工移植不变）；**先落本计划文件再实施**；全量测试 + 插件构建校验必须通过。

## 1. 背景与目标

锚点之后 master 完成的是「第八轮竞品建议 + 性能第八轮 + 稳定性尾款」三批次收口，共 92 提交。
体量远小于上轮（348 提交），但**性质上有一处结构性变化值得单列**：本轮引入了四条**以守卫测试为验收载体**
的治理线——T-FatalPassthrough（新增扫描规则「catch(Throwable) 块必须判 fatal 或登记豁免」）、
T-PsiCaptureProtocol（新增 psiCapture 跨线程读守卫）、T-StabilityTail（项目服务级状态复位守卫、
分片顺序依赖探针）、T-TestIsolation（共享服务状态复原夹具）。这意味着**生产移植与守卫适配互为前提**：
只搬生产代码而不同步守卫，等于把 master 的防回潮机制留在门外；只搬守卫而不补生产，守卫立刻红。

本轮目标：把 92 提交增量全部移植进 100% Java 的 k2j 生产树，同步 56 个测试文件与 16 份文档，
全量测试绿 + buildPlugin/verifyGate 三目标通过，锚点推进 2d40564。绝无 `git merge`；共享文件
（bundle/CHANGELOG/变更记录）走手工并集，k2j 自有行零丢失。

## 2. master 增量功能线（92 提交归组）

| 功能线 | 主要提交 | 内容概要 |
|---|---|---|
| L1 T-FatalPassthrough | 5cc0710 / e2517bf / 4147432 / b124a33 / d88d63a | **新件 `util/FatalErrors.kt`（53 行）**：JVM 级致命错误判据（`VirtualMachineError` / `LinkageError` / `ThreadDeath` 三类白名单，`AssertionError` 刻意不算）。落地子集 **30 处 `catch(Throwable)` 加 fatal 透传**（execute/DataSourceSelector 原生执行链含 `InvocationTargetException` cause 解包、log/agent/AgentManager、log/RunConfigurationProcessListener、mcp/McpDbExecutor、ui/SqlPreviewService 等）；守卫新增「捕获块首行必须 `isFatal` 或登记豁免表（文件+站点数+理由）」规则；T-FP-02b 独立复核整改（外层 runCatching 抵消点收口） |
| L2 T-XmlDtdHardening | 48c41a8 / 102c89f / 7e7a3e3 / 1d615d4 / 4e2b5da / 0c47108 / 3cbab4c / 2d5345f / addc590 | **新件 `xml/SecureXml.kt`（492 行，本轮最大件）**：XML 解析安全配置的**唯一实现**——关键防护 feature 收敛（T-XDH-01/02）、降级可观测告警出口、内联 DTD 子集膨胀防护（T-XDH-03，含上限常量与预扫）、子集切分状态机 + 参数实体可见化（T-XDH-07，处理指令文本不算声明）；`XmlMapperParser` / `PagesXmlParser` 两解析入口接线；两路径 DOCTYPE/XXE 回归用例 |
| L3 T-PsiCaptureProtocol | c4af37f / b74c99e / 2d659b4 / 494e835 / 99d0b00 / db03b39 | **新件 `psi/PsiSnapshot.kt`（127 行）**：PSI 跨线程两段式捕获快照（EDT 侧记 VirtualFile+Document+textRange+modificationStamp；后台侧三项校验 file→stamp→区间），纯函数内核 `validateCaptured` 与平台解耦供表驱动用例；两 gutter provider 接入（T-PCP-02 甲类）；跳转族读权限约定改硬断言 + 协议援引（T-PCP-03 乙类）；跨线程 PSI 读守卫（T-PCP-04）+ 架构文档《PSI 跨线程读取协议》（T-PCP-05）。**只读侧不做文本交叉验证**（PSI 未 commit 会被误判失效） |
| L4 T-McpRowBudget | a495d23 / 65f33a8 / 38812b8 / 9ecab4a / fafdea1 | MCP 执行工具行数预算可配：分层常量 + 钳制（T-MRB-01/02）、`mpsv_explain` 增可选 `maxRows`（T-MRB-03）、通道级 fallback + 注册与守卫（T-MRB-04/05）；接线守卫按独立复核意见消除假绿断言 |
| L5 T-McpAuditTrail | cecba97 / 7119619 / 7e7a3e3 / 065f788 / ba077e2 / f0d163e / 39a0cf9 / 1c5f20f / cf7cbf5 | MCP 执行链落历史与审计补全：`SqlSource.MCP` 新枚举值（T-MAT-03，**条目文本恒为原始 selectSql，绝不落 EXPLAIN 包装句**）+ 来源标记走 pd 载荷持久化；执行链落历史（回调注入，T-MAT-01/02/04）+ 审计行补真实入参；MCP 徽标两页渲染器同落点同协议（T-MAT-05）；T-MAT-04 回退修复（错误路径审计入参段改两段分预算）；合流收口修读端回填来源标记 + 合并引入的两处门禁红 |
| L6 T-ErrorReportRedact | 5ae7b90 / 28dc531 / bcf06b2 | 错误上报分级脱敏（D1=B）：默认只带类名 + 指纹 + 栈帧，异常消息全文与完整堆栈改**勾选附带**；`ErrorReportComposer` / `ErrorReportDialog` 预填分级切换与描述/标题双形态；指纹区分度抽样用例 |
| L7 T-DelimiterAware | 26487b8 / 3a3fa04 / af08fd0 / 1aba7a0 | `SqlStatementSplitter` 增 `DELIMITER` 指令感知（T-DA-02，界符状态机 + 行首判定）；四消费面（execute/log/mcp）脚本用例（T-DA-03）；T-DA-01 定位行为核实与两次探针实录 |
| L8 T-ParamContract2 | 95e7445 / b99381c / f5c43b1 / acbcbcc / f14e064 / 1ffcaeb / cc01c41 / bc1a2bd | 参数契约补缺：`BigInteger` 补入数值族（T-PC2-01）、`UUID` 归文本档（T-PC2-02）、XML 路径 paramN 全或无门槛（T-PC2-03）、模型侧 UNKNOWN 信号（T-PC2-04 模型侧）、**面板侧 UNKNOWN 参数执行/EXPLAIN 前提示一次**（T-PC2-04 D1=B 定案，预览链保持放行）、T-PC2-05 金样用例；新增 bundle 键 `execute.confirm.unknownParams.message` |
| L9 T-PerfSweep5 | 7ef0b0f / 61ce6ab / d7cb87f / 3508878 / 72969df / 63bf223 / 995d363 / c257c4c | 性能第八轮 16 项发现收敛：PS5-01 执行链 DDL 判定解析复用、PS5-02 确认流/回滚链 AST 共享槽、PS5-03 方言 URL 探测缓存 + 调用点收敛、PS5-04 **AgentManager 常驻面解耦**（新件 `log/agent/AgentUrlRegistry.kt` 轻量持有者，读 agentUrl 不再实例化项目级服务）、PS5-05 XML 定位/跨 ns 片段解析单槽缓存（TTL + isValid，**禁 PSI 监听红线**）、PS5-06 schema_context 单调用全扫限额（按数据源记账）、PS5-07 epActive 复位、PS5-08 弱引用、PS5-09 死分配、PS5-10 索引有界、PS5-11 冷路径、PS5-12 容量上界、PS5-13 有界、PS5-15 引导监听弹后注销 |
| L10 T-TestIsolation | 4d99e82 | 项目服务级测试状态复位——收口 SqlLogManager 共享实例泄漏族（生产侧 `SqlLogManager` 增量 + 测试夹具 `testutil/SqlLogManagerProjectState.kt`） |
| L11 T-StabilityTail | d2df2d9 / fe0d7fc / 7aba2e2 / 2370844 / 1ecd706 / 45afdf7 / f11371a / 9794ca5 / 517695f / a40c018 / 6a5ee0e / 2f098c7 / 904f28a | 竞品第八轮批次三项尾款：T-ST-01 测试脆弱前置修复（断言「环境此刻为空」绑死分片顺序 → 改 assertSame 同实例）、T-ST-02 分片顺序依赖复现与根治 + T-ST-02b apply() 取服务纳入降级面 + T-ST-02b2 取服务前判 `isDisposed`、T-ST-02c 落 `AlreadyDisposedException` 观测手段（片号/触发点/服务/项目态）、T-ST-02d 项目服务级状态复位守卫、T-ST-03 面板回滚注册链解析复用前提留档（**显式传参判定不实施**）；含两次口径更正（「根治」→ 不宣称根治）与反向断言真窗口复验 |
| L12 文档与盘点 | 1aa2010 / f7a9ac6 / a693640 / fbe310e / c24aab3 / d259051 / d33cb81 / 21dd774 / 2d40564 | 第八轮性能分析报告 + T-PerfSweep5 计划入库、竞品第八轮八份 + T-PerfSweep5 全量交付变更记录行、T-StabilityTail 计划与验收文档、两份验收文档指针登记 + 「批次收口清单」七项、盘点第 17~20 版 |

## 3. 文件级移植矩阵

### 3.1 生产代码（4 新建翻译 + 49 增量翻译 + 0 删除）

**新建 4 件（Kotlin→Java 全量转换）：**

| 包 | 文件 | 行数 | 说明 |
|---|---|---|---|
| util | `FatalErrors.java` | 53 | `object` → `public final class` + `INSTANCE` 或全静态；`isFatal` 纯函数；`t is X` → `instanceof` |
| xml | `SecureXml.java` | 492 | 本轮最大件。`internal object` → public 工具类；`XmlEntityExpansionException` 独立顶层类（k2j 惯例：与 object 同文件者按需拆件）；子集切分状态机 + 上限常量（`static final`）+ 告警出口（`Logger`） |
| psi | `PsiSnapshot.java` | 127 | 私有构造器 + 伴生 `capture` → `public static`；`sealed class Validity` + `data object Valid` + `data class Invalid` → Java 17 sealed 层级（**record 禁 extends，须降为接口/抽象类 + record 实现**，见 §5 铁律①）；`enum InvalidReason` 带构造参数直译；顶层 `internal fun validateCaptured` → `PsiSnapshotKt.validateCaptured` 门面 |
| log/agent | `AgentUrlRegistry.java` | 43 | `object` 单例 + `WeakHashMap` + `Collections.synchronizedMap`；`internal fun clearAllForTest` → `public static`（跨包测试须可见，见 §5 铁律：internal→public） |

**增量翻译 49 件（按包分组；master 路径 → k2j 落点）：**

- **log（15）**：`EntryJumpResolver` / `EntrySourceMatcher` / `HistorySqlLogPanel` / `LogTimestampParser` / `LogToSqlPanel` / `MapperXmlLocator` / `RunConfigurationProcessListener` / `SqlLogAutoSaver` / `SqlLogConsoleBridge` / `SqlLogFileReader` / `SqlLogManager` / `SqlLogPanel` / `SqlLogParamCodec` / `agent/AgentManager`
  - ⚠️ **`log/LogSqlModels.kt`（+12 行）特殊映射**：k2j 侧已拆为 `SqlSource.java`（enum）+ `LogSqlEntry.java` + `LogSqlModelsKt.java` 三件。本轮增量为 `SqlSource` 新增 `MCP` 枚举值（含大段语义注释）——**只改 `SqlSource.java`**，勿新建同名 Kt 文件（上轮同型教训在案）
- **xml（4）**：`PagesXmlParser` / `ParamKind` / `XmlMapperParser` / `XmlPreviewModel`
- **ui（7）**：`AnnotationSqlPreviewService` / `SqlPreviewPanel` / `SqlPreviewService` / `XmlParamTypeResolver` / `gutter/SqlGutterLineMarkerProvider` / `gutter/StringSqlMethodGutterLineMarkerProvider` / `highlight/DatabaseSchemaResolver`
- **mcp（6）**：`ExplainTool` / `McpAuditLog` / `McpDbExecutor` / `McpExplainFormatter` / `McpSchemaContextService` / `McpToolsetSupport`
- **sql（5）**：`DataSourceUrlProbe` / `Dialect` / `ParamQuote` / `rollback/RollbackSnapshotStore` / `rollback/RollbackSqlGenerator`
- **feedback（4）**：`BatchSubmitter` / `ErrorFingerprint` / `ErrorReportComposer` / `ErrorReportDialog`
- **execute（3）**：`DataSourceSelector` / `MetadataInvalidator` / `UnboundedWriteDetector`
- **config（2）**：`PluginSettingsPanel` / `SectionConfigurable`
- **psi（1）**：`XmlCrossNsFragmentResolver`
- **util（1）**：`SqlStatementSplitter`
- **onboarding（1）**：`OnboardingAnchorTip`

**删除 0 件**：本轮 `git diff --name-status` 零 D。

**规模实测（`git diff --numstat` 生产侧）**：新增 +2199 行 / 删除 −229 行，其中 `SecureXml` 492、`AgentManager` +129、`PsiSnapshot` 127、`McpToolsetSupport` +123、`ErrorReportDialog` +114、`McpDbExecutor` +112、`ErrorReportComposer` +107。

### 3.2 测试（56 文件：19 新建 .kt + 31 增量 .kt + 6 增量 .java）

**新建 19 件（.kt 直取 + 机械互操作适配，落 k2j `src/test/kotlin` 同名路径）：**

execute 2（`ConfirmRollbackParseShareTest` / `ConflictProbeProjectRetentionTest`）、log/agent 5（`AgentManagerLazyMonitorTest` / `AgentUrlDecoupleGuardTest` / `AgentUrlRegistryTest` / `EpActiveResetTest` / `FatalPassthroughTest`）、mcp 3（`McpAuditLogTest` / `McpHistoryIngestTest` / `McpRowBudgetTest`）、psi 1（`PsiSnapshotTest`）、sql 1（`DialectProbeCacheTest`）、ui 2（`SqlPreviewPanelRollbackParseShapeTest` / `UnknownParamGateTest`）、util 1（`FatalErrorsTest`）、xml 1（`SecureXmlTest`，652 行）、onboarding 1（`OnboardingAnchorTipListenerTest`）、**testutil 2（`AlreadyDisposedDiagnostics` / `SqlLogManagerProjectState`——T-StabilityTail/T-TestIsolation 的共享夹具，被多类引用，须先落位）**

**增量 31 件（k2j 侧为 .kt，直取 + 适配）：** config 1、convention 1、execute 2、feedback 5、log 6、log/agent 1、mcp 2、psi 1、sql/rollback 1、ui/gutter 2、ui/highlight 1、util 3、xml 2 等，以 `git diff --name-status ec584e2..2d40564 -- src/test/kotlin` 为准。

**增量 6 件（k2j 侧为 .java，须逐行增量合并、禁整取）：** `heavy/HeavyXmlParamTypeResolverTest`、`log/HistoryCellRendererTest`、`log/SqlLogAutoSaverTest`、`log/SqlLogHistoryRoundTripTest`、`log/SqlLogPanelRendererUiTest`、`xml/AnnotationSqlParserTest`。

**⚠️ 守卫大头 `convention/BannedApiSourceScanTest`（master 951→2069 行，+1118；k2j 现 1035 行）**：本轮新增五族扫描规则——`fatalPassthroughOffenders`（catch(Throwable) 必须判 fatal 或登记豁免）、`pooledThreadSites`、`psiCaptureOffenders`、`edtReadContractOffenders`、`projectServiceStateOffenders`（初始化入口写共享服务不复位即红）。k2j 侧须把扫描根 `src/main/kotlin`→`src/main/java`、`.kt`→`.java`，并把 Kotlin 专有判据改写为 Java 等价形态。**该文件是本轮验收核心：它的红/绿直接判定生产移植是否到位。**

### 3.3 资源与构建（union，k2j 自有块零丢失）

**本轮构建链零 diff**——`build.gradle.kts` / `buildSrc/` / `settings.gradle.kts` / `gradle.properties` / `gradle/libs.versions.toml` / `src/main/resources/META-INF/plugin.xml` / `mcp-optional.xml` **均未变更**（已用 `git diff --name-status` 逐一核实），无需 union 操作。唯一需注意：实施期临时沙盒行 `sandboxContainer.set(...)` 仍需在位（并行任务在跑），收官还原。

- `src/main/resources/messages/MpSqlVisualizerBundle.properties`（+8/−2）与 `_zh.properties`（+8/−2）：**5 个新键**——`execute.confirm.unknownParams.message`（T-PC2-04）、`error.report.privacy` / `error.report.dialog.attachStack.tip` / `error.report.dialog.attachMessage(.tip)`（T-ErrorReportRedact）、`logToSql.execute.mergeSkipped`（T-PS5-11）。两侧键集必须对齐，`BundleParityGuardTest` 兜底；⚠️ 禁带 BOM 写入，只用 `git checkout`/`Edit` 逐行插。
- `CHANGELOG.md`（+13）：`[Unreleased]` 段插入本轮用户可见变更；k2j 已有自己的同步台账行，**只插 master 增量行，k2j 自有行不动**。

### 3.4 文档（6 新建直取 + 10 增量 + 三册 union）

- **新建 6 件直取**：`docs/开发计划/20260919-0433-T-PerfSweep5-性能发现点收敛-开发计划.md`、`docs/开发计划/20260919-1817-T-StabilityTail-…-开发计划.md`、`docs/架构设计/PSI跨线程读取协议.md`（T-PCP-05 协议文档，本轮唯一架构文档）、`docs/调研报告/20260919-0332-插件IDEA性能影响深度分析-调研报告.md`、`docs/验收文档/20260919-2000-竞品第八轮九任务与PerfSweep5-验收文档.md`、`docs/验收文档/20260919-2030-T-StabilityTail-…-验收文档.md`
- **增量 10 件**：`docs/变更记录.md`（+3）、`docs/开发计划/20260916-1730-待完成开发计划全景盘点.md` 与本轮八份竞品任务计划（T-McpAuditTrail / T-McpRowBudget / T-FatalPassthrough / T-ErrorReportRedact / T-DelimiterAware / T-ParamContract2 / T-PsiCaptureProtocol / T-XmlDtdHardening）的 §八/§九 回填
- **三册 union**：`CHANGELOG.md` / `docs/变更记录.md`（`docs/MEMORY.md` 本轮零 diff）。**必须做两侧行集合校验**（不只看行数）——master 侧「一侧删除一侧未动」会静默吞 k2j 台账行（v2026.9.10 教训）。

**⚠️ 明确排除**：主工作区 `mybatis-plus-sql-visualizer` 当前有**未提交**改动（`docs/变更记录.md`、`src/test/kotlin/.../BannedApiSourceScanTest.kt`）——那是并行会话的在途工作，**不属于本轮同步范围**（同步源一律取已提交的 `2d40564`，禁用工作树内容）。根目录 `bash.exe.stackdump` 为环境残留，不取。

## 4. 波次与提交链

| # | 提交 | 内容 | 门禁 |
|---|---|---|---|
| C0 | — | 登记并行任务（名 `t-synck2j92`，沙盒 `build/test-sandbox-k2jsync92`），确认 k2j worktree 干净 | `parallel_tasks.py check` |
| C1 | docs: 立项 | 本计划文档 | — |
| C2 | feat: 生产 Wave1 低层 | `FatalErrors` / `SecureXml` / `PsiSnapshot` / `AgentUrlRegistry` 四新件翻译 + util/xml/psi/sql/execute 包增量 | `compileJava` 零错 |
| C3 | feat: 生产 Wave2 中高层 | log 15 件（含 `AgentManager`、`SqlSource.MCP`）+ mcp 6 件 + feedback 4 件 + config 2 件 + ui 7 件 + onboarding 1 件增量 + bundle 5 新键 | `compileJava` 零错 |
| C4 | test: 测试移植 | 19 新建 .kt + 31 增量 .kt + 6 增量 .java（含 `BannedApiSourceScanTest` 五族守卫 Java 化适配） | `compileTestKotlin --rerun-tasks` 零错 |
| C5 | test: 测试收口 | 全量 testShards 红项清账（移植偏差修正 + 守卫判据 Java 语义对齐 + 共享状态泄漏复核） | testShards 0 失败 |
| C6 | docs: 文档同步 | 6 新建直取 + 10 增量 + 三册 union + 两侧行集合校验 | 行集合校验通过 |
| C7 | chore: 收官 | 全量门禁复跑 + buildPlugin 出包 + verifyGate 三目标 + `.port-sync` 锚点推进 2d40564 + note 重写 + 还原沙盒临时行 + 计划 §8 回填 + 变更记录台账行 | 见 §7 |

## 5. 移植规则（既定清单全量生效）

1. **生产代码 100% Java，绝不 merge .kt 进生产树**；本仓 `KOTLIN_TO_JAVA_RULES.md` 为准；
2. Kotlin→Java 机械转换缺陷清单逐条生效（static import / `drop` 越界守卫 / `LinkedHashSet` 保序 / `Map.of` 拒 null / `assertEquals` 装箱 / `throws Exception` / test 前缀 / record 访问器 `x()` / `split("\n")` 丢尾空串等）；
3. 互操作铁律：① **Java record 禁 extends 只可 implements**（`PsiSnapshot.Validity` 的 sealed 层级须降为接口/抽象类 + record 实现）；② Kotlin 调 Java 方法**禁命名实参**（直取 .kt 测试须机械转位置实参）；③ Java 静态成员/嵌套类型在 Kotlin 无默认导入（须补 import）；④ 非 `get`/`is` 前缀 Java 方法不合成 Kotlin 属性；⑤ `internal` → `public`（跨包测试可见性）；⑥ 行级并集用 `git merge-file --union` 后取 mine，**union 后必须做两侧行集合校验**；
4. **基线一致性判定** `git show <sha>:<f> | diff --strip-trailing-cr - <f>`；一致文件用 `git checkout <sha> -- <paths>` 整取（注意：多 pathspec 任一失效会整体中止，须逐一复核）；
5. **守卫适配先行**：`BannedApiSourceScanTest` 的五族新规则是本轮生产的验收载体，须与生产移植同批落地并**做反向验证**（移除修复分支必红），防「扫描根不匹配 → 命中 0 文件 → 断言恒真」的静默假绿（McpScopeSourceScanTest 教训）；
6. **反射入口登记**：本轮四新件按源码实读确认是否引入新反射入口（`SecureXml`/`FatalErrors`/`PsiSnapshot`/`AgentUrlRegistry` 预期均无），如有则登记 `PlatformSignatureCrossVersionTest`；
7. gradle 前必跑 `parallel_tasks.py check`；本会话登记名 `t-synck2j92`、沙盒 `build/test-sandbox-k2jsync92`；**登记表滞留的 T-GifRec-MCP 条目不 kill、不触碰其沙盒**；收官后 `done` 注销并还原沙盒临时行；push 留用户 GUI；
8. 移植引发测试假失败先清 `build/classes` + `build/kotlin`（陈旧产物劫持三形态），再排逻辑；
9. **共享项目服务状态泄漏**（本轮高相关）：k2j 测试树同片跨类复用 `ProjectImpl`，`SqlLogManager` 共享实例须靠 `testutil/SqlLogManagerProjectState` 存复原——该夹具须先于引用它的用例落位。

## 6. 风险清单

| 风险 | 缓解 |
|---|---|
| **守卫假绿**：新五族规则扫描根不适配 → 命中 0 文件 → 断言恒真，移植缺口被绿灯掩盖 | 守卫适配后逐族做「移除修复分支必红」反向验证；报告实测命中文件数 |
| `SecureXml` 492 行状态机翻译出错（子集切分/参数实体可见化） | 以 `SecureXmlTest` 652 行表驱动用例为唯一判据；翻译后先跑该类单点绿再进全量 |
| `PsiSnapshot.Validity` sealed 层级在 Java 侧形态受限（record 禁 extends） | 降为 sealed interface + record 实现；`when` 穷尽 → `switch` 模式匹配 |
| 30 处 `catch(Throwable)` 透传漏搬 → 守卫红；或错搬导致既有降级语义变化 | 逐站点对照 master diff（含 `InvocationTargetException` cause 解包形态），以守卫 + `FatalPassthroughTest` 双判 |
| `AgentManager` +129 行（PS5-04 解耦 + PS5-07/12）改动面大且涉会话生命周期 | 与 `AgentUrlRegistryTest` / `AgentUrlDecoupleGuardTest` / `EpActiveResetTest` / `AgentManagerLazyMonitorTest` 同批落地 |
| 6 件 .java 测试被整取覆盖掉 k2j 适配行 | 一律逐行增量合并，禁 `git checkout` 整取；合并后 diff 复核 |
| `SqlSource.MCP` 误新建同名 Kt 文件（上轮同型） | 只改 `SqlSource.java`；落地后核对该枚举值单一来源 |
| T-ST-02b2 `isDisposed` 判据涉及平台 API 语义，Java 侧行为差异 | 与 `SectionConfigurable` 增量同批，落位后跑 `SectionConfigurableAutoCaptureLinkageTest` |
| 并行会话推进 master | 锚点钉 2d40564 快照，后续增量留下一轮；不碰主工作区与其他 worktree |
| 三册 union 吞 k2j 台账行 | `git merge-file --union` 后两侧行集合校验（不只看行数） |
| bundle `.properties` 被写 BOM | 只用 `git checkout`/`Edit` 逐行插，不用带 BOM 的写入工具 |
| verifyPlugin 报告目录脏状态硬崩 | 重跑前清 `build/reports/pluginVerifier` |
| 262 EDT/SlowOps 断言类测试在翻译后触雷 | 照 master 测试形态 + 既有两段式惯例（BGT 读探测 + EDT 收口） |

## 7. 验收标准

1. 全量 `testShards` **0 失败**（基线 5183/0/0/6，本轮预期净增）；`testDatabase` 维持绿或按「环境受限未验证」如实记录；
2. `buildPlugin` 成功出包；`verifyGate` 243/253/262 三目标 Compatible，被标记的弃用/移除 API 用量与 master 逐项一致（无翻译漂移）；
3. `compileJava` / `compileTestKotlin --rerun-tasks` 零错；`BannedApiSourceScanTest` 五族新规则实测命中文件数非 0 且反向验证成立；
4. k2j 自有行零丢失：CHANGELOG/变更记录/build.gradle.kts 两侧集合校验通过；bundle en/zh 键集对齐（parity 守卫绿）；
5. `.port-sync` 锚点 = `2d40564`（full SHA 以 `git rev-parse` 取真值），note 重写为本轮记录；
6. 生产树仍 100% Java（`src/main/java` 无 .kt）；无 `git merge` 痕迹（提交历史为普通提交链）；
7. 沙盒临时行已还原；并行任务已注销。

## 8. 实施记录

**锚点变更说明（两次）**：原计划锚点钉 master `2d40564`（92 提交）。实施期间 master 两度前进：
①20 提交至 `b029a78`（含 `9f4bbb9` 预热链池线程绑项目生命周期、`adbbc02` 守卫 T-ST-02d 两项阻断修复）；
②再 11 提交至 `b9aeeba`（`T-McpToolNaming` MCP 工具改名 + `T-AcpMcpTransport` ACP 传输绕行）。
按上轮 T-SyncK2JMaster309 的惯例（post-anchor 增量一并吸收、锚点改钉），两批增量全部吸收，**锚点改钉
`b9aeeba`**，同步范围 `ec584e2..b9aeeba` 共 123 提交。§2/§3 的量化矩阵保留 92 提交口径（立项基线），
实际落盘以本节为准。

提交链：
- `f7c9313` C1 立项计划
- `d9f4d91` 生产移植（四新建翻译 + 49 增量 + bundle 5 新键）
- `263d689` 测试移植（19 新建 + 30 增量 + 守卫五族 Java 化适配）
- `3814236` 收口修复（预热链池线程绑项目生命周期 + 读端来源空值合并）
- `b304599` 文档同步（18 件直取 + 三册 union）
- 后续提交：第二批次增量（MCP 工具改名 + ACP 传输）+ 本节回填 + 锚点推进 + 沙盒还原

门禁读数（收官实测）：
- 全量 **testShards 5390/0/0/6 绿**（472 套件；基线 5183 → 5390）
- **testDatabase 10/0/0/0 绿**
- **buildPlugin 出包 2026.9.18**（6.6MB；含内嵌 `agent/mps-sql-agent.jar` + `plugin.xml` + `mcp-optional.xml`
  + `acp-transport-optional.xml`；新类 `SecureXml`/`FatalErrors`/`PsiSnapshot`/`AgentUrlRegistry`/
  `AcpIdeaMcpTransportProviderImpl`/十个 `SqlVisualizer*Tool` 均已入包）
- **verifyGate 三目标 Compatible**：243 = 4 for-removal + 10 dep + 10 exp + 1 internal；
  253 = 6 + 13 + 10 + 1；262 = 7 + 67 + 10 + 1。较上轮（243: 3+10、253: 5+13、262: 6+66）各 **+1
  for-removal 与 262 的 +1 dep**——归因 = master 本轮新增的 `util/FatalErrors` 白名单引用 `ThreadDeath`
  （JDK 标记待移除），**与 master 同源、非翻译漂移**；+1 internal 来自 `ProjectEx.getEarlyDisposable()`
  （T-StabilityTail 预热取消门，master 同款调用）。

实施偏离与发现（供下轮参考）：
1. **Java 侧 fatal 透传形态必须收窄**：master 的 `if (FatalErrors.isFatal(t)) throw t` 在 try 块含受检异常时
   javac 按精确重抛分析（JLS 11.2.2）要求方法声明该受检异常，而公开签名不能加 `throws`——统一改写为
   `if (FatalErrors.isFatal(t)) throw (Error) t;`（白名单三类 `VirtualMachineError`/`LinkageError`/`ThreadDeath`
   全是 `Error` 子类，收窄零风险、重抛原对象不包装）。守卫判定令牌是 `FatalErrors.isFatal(`，该形态照过。
2. **fatal 守卫在 k2j 必须换口径（两段式）**：master 的规则是「全部 `catch (…: Throwable)` 必须判 fatal 或
   按文件+站点数登记豁免」，其豁免表按 Kotlin 树实测（74 处）。k2j 的 Java 树把每个 Kotlin `runCatching`
   落成 try/catch——实测 **597 处站点 / 119 文件**（`DataSourceSelector.java` 单文件 142 处），逐站点要求
   判定会把 597 处全判红。故改：**(a) 落地集 6 文件判定数精确相等**（DataSourceSelector 16 / AgentManager 8 /
   AgentSession 3 / RunConfigurationProcessListener 7 / McpDbExecutor 1 / SqlPreviewService 2）+ **(b) 未判定
   站点总量基线 597**（漂移即红，强制重新登记）。守卫内写了移植注说明为何与 master 口径不同，防后人误判为削弱。
3. **守卫掩码器在 Java 树上有一处真缺陷（本轮修复）**：`maskNonCode` 的嵌套注释配平在 Java 源码上遇到
   「注释里出现不成对收尾符」会把该注释**之后的整个文件涂白**——仓内 2 文件命中（作者为在 KDoc 写出定界符而
   故意拆开），多涂白 145/327 行、**5 处真实 catch 站点静默脱离扫描面**（595→590）。已改为按扩展名选掩码口径
   （Java 用非嵌套配平），并补可区分样本钉住。
4. **一处真实移植偏差（由测试暴露）**：`SqlLogFileReader` 两条头部分支把 master 的
   `decodedExt?.source ?: SqlSource.UNKNOWN` 译成只判 `decodedExt != null`，漏了**内层 null 合并**——
   非 MCP 条目的 pd 载荷无 `s` 键时 `source` 落 null（`SqlLogHistoryRoundTripTest` 用例抓出）。已修两处。
5. **psiCapture 族在 k2j 的覆盖缺口最大（如实登记）**：主判定面只有 2 处池化站点且都在豁免表内；31/33 池化
   站点的 PSI 读下沉到助手（`WrapperLocator`/`AnnotationSqlPreviewService` 等），是 master 已登记的漏报边界，
   但在 k2j 侧这是主流形态。该族只有反向验证（改坏必红）能证明判据活着。
6. **fatal 族的两段式是「总量守恒」不是「逐站点判定」**：基线不变的前提下，删掉一个已判定站点 + 新增一个
   未判定站点仍会绿；缓解只有落地集 6 文件的精确计数（37 处判定）。其余约 560 处无逐站点保护——这是决策
   明确接受的口径，如实登记。
7. **`fatalUnwrapUnjudgedBaseline = 5` 是已知生产缺口**：反射链上目标方法抛出的 Error 被吞成 warn
   （`DataSourceSelector` 5 处含 `InvocationTargetException` 的站点未判 `cause`）。登记基线 ≠ 已解决，
   需另开生产收口任务。
8. **MCP 工具改名涉及面比预期广**：10 个工具类 `git mv` + 全仓引用（`mcp-optional.xml` 10 个 FQCN、
   `McpToolGate.TOOL_NAMES`、`@McpTool(name)`、`super("sqlv_…")`、bundle 文案、marketplace 页、19 个测试、
   `PluginXmlStructureGuardTest` 的 toolset 清单）。改名后必须**全仓 grep 旧前缀确认零残留**——
   `mpsv_` 在非工具名语境（multipart boundary/线程名/临时目录名/`urn:mpsv:` 测试串）仍有 7 处保留，与 master 一致。
9. **ACP 传输绕行依赖 mcpStub 新增 3 个 ml-llm 桩**：桩源集默认 srcDir 已覆盖，新目录零构建改动即生效
   （桩产物挂 `compileJava.classpath`，不进插件 jar）。`AcpTransportScopeSourceScanTest` 是 k2j 侧唯一需适配的
   ACP 测试（扫描根 Kotlin→Java），并**额外加了「扫描集非空」自检**——直接针对「扫描根不对 → 命中 0 文件 →
   断言恒真」的静默假绿（k2j 历史上有 McpScopeSourceScanTest 的先例）。
10. **作业期间并行会话推进 master 且共享 gradle 缓存**：`verifyPlugin` 首次失败于
    `platform-loader.jar` 缺失（243 平台 transform 正被并行会话重解压，竞态）；平台落缓存后 `verifyGate` 通过。
    环境争用，非 k2j 缺陷。

