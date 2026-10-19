# T-AgentResiduals 交付在册残留收口 — 开发计划

> 来源：2026-09-22 交付 **T-AgentConfigChannel（O-54）+ T-AgentSourceLocateStale（O-55）** 收口后，
> 两份独立只读复核 + 真机级 e2e + 本机门禁实测累计登记 **7 项残留**（均不阻塞该交付；交付本体已合入并推送
> master `bb5dca5`，门禁 testShards 495 套件 / 5645 用例 / 0 失败 + verifyGate 三目标 Compatible）。
> 用户 2026-09-22 决定：**现在收**。
> 相邻事项：两个**真缺陷** `T-ExplainChainHardening (O-48)` / `T-ErrorLocateUsability (O-49)` 另有既有计划
> （`docs/开发计划/20260921-2322-…` / `20260921-2324-…`，均 P1），用户要求**先做那两个**；本计划写集与它们
> 不相交（那两个都改 `ui/SqlPreviewPanel.kt` 须彼此串行，与本文各项无交集），**可并行**。
> 编号：占用 **O-56**（O-53 留空，O-54/O-55 已用）。

## 一、残留清单与修法

| # | 残留 | 现状与依据（文件:行） | 修法 | 成本 |
|---|---|---|---|---|
| R1 | **IDE 侧跳过表未与 agent 侧同集** | `src/main/kotlin/.../log/CallerSourceLocator.kt#isFrameworkFrame` 仅 15 项；agent 侧 `sub-projects/.../util/StackLocator.java#FRAME_SKIP_PREFIXES` 多出驱动族（`dm.jdbc.`/`oracle.jdbc.`/`com.mysql.`…）、连接池（`com.alibaba.druid.`/`com.mchange.`/`org.apache.commons.dbcp2.`/`org.apache.tomcat.`）、AOP（`net.sf.cglib.`/`org.aspectj.`/`org.objectweb.asm.`/`org.jboss.`）与一批「补收」（`com.github.pagehelper.`/`org.mybatis.`/`net.sf.jsqlparser.`/`com.alibaba.`/`org.apache.`/`com.google.`/`com.fasterxml.`/`io.netty.`/`io.lettuce.`/`redis.clients.`/`io.micrometer.`/`io.seata.`/`org.slf4j.`/`ch.qos.`/`org.ehcache.`/`net.sf.ehcache.`/`com.github.benmanes.`/`org.quartz.`/`com.netflix.`/`org.elasticsearch.`/`com.mongodb.`）共 30+ 项 → **同一执行两侧可能给出不同位置**（标记行指到框架帧） | 把 IDE 侧那份**补齐到与 agent 侧同集**（纯数据同步，**不引任何依赖**——IDE 侧是降级主路径，绝不可依赖 agent 代码，见 O-55 计划 §八 D1 定案）；同步更新 `LocatorSkipPrefixSyncTest` 的差异登记 | ~30 分钟 + 门禁 |
| R2 | **shard3 flake 的最后一个来源：测试侧 `@After` 取服务** | `testutil/SqlLogManagerProjectState` 的调用方在 `@After` 里经 `logManager()`（`AlreadyDisposedDiagnostics.projectService`）取服务做复原，而 `@After` 正是 temporarily-disposed 窗口高发时段；服务容器一次创建失败即永久毒化（T-ST-02b2 已证） | 让快照**持有 manager 引用**、`@After` 只 `restore(snapshot)` 不再取服务；**同步改姊妹守卫** `convention/BannedApiSourceScanTest`（它认的是双参 `restore(承接名, snapshot)` 形态）；涉及 5~7 个使用该工具的测试类 | ~1 小时 + 门禁 |
| R3 | **`HeartbeatRhythmTest` 容差 1.5× 偏紧** | `sub-projects/sql-visualizer-agent/src/test/java/.../transport/HeartbeatRhythmTest.java` 的 `TOLERANCE`；本机门禁实测命中一次（472ms > 450ms，同代码复跑 5645 例全绿）——属 `T-PerfSweep6` 发现 14 的登记处置 | 容差放宽到 **2×**（并注明依据：并行构建下调度抖动可达百毫秒级） | 几分钟 |
| R4 | **IDE 侧复核的 3 条低风险** | ① `AgentManager` 的 ACK 超时 WARN 不判会话健康——socket 半死时把「连接已断」误叙成「应用侧未生效」（复核 F3）；② `INTAKE_DROP_WARN_STEP` / `CONFIG_ACK_TIMEOUT_MS` 无字面值断言（F7）；③ 旧 agent（无 ACK）混合版本下桥永久放行 → 双通道共存（F2） | ① 超时 WARN 补判会话健康并分文案；② 补两条字面值断言；③ 仅在 KDoc/CHANGELOG 记边界（不改行为，靠去重折叠） | ~40 分钟 |
| R5 | **T-08 的语句级边界** | `sub-projects/.../hook/ConnectionHandler.java` 的逐调用 `isDisabled()` 短路在 `prepareStatement` 处即直通 → 停采期间 prepare 的语句在恢复后仍不包装（MyBatis 默认 `SimpleExecutor` 每条查询现 prepare，**实际不可达**；仅 `ReuseExecutor` 一类会命中） | **已定案选 B**（见 §五 D1）：① 删 `ConnectionHandler.invoke` 顶层的 `isDisabled()` 短路（三分支包装逻辑与其它方法直通不变）；② `StatementHandler.invoke` 的逐调用短路**保留为唯一闸门**（停采期间照样零产出、参数不累积）；③ `SqlHookCallbacks.dropToRaw` 一次性告警改为**停采态只计数不打印、不占名额**；④ KDoc 写明「禁用＝volatile 短路」这条红线现落在**执行点**，连接/语句包装一律在位；⑤ 新增确定性用例（停采态 prepare → 下发 `disabled=false` → 执行同一语句 → 产出事件；对照臂：停采期间执行不产出）+ 反向验证（加回顶层短路必红） | ~30 分钟 + 门禁 |
| R6 | **`testDatabase` 2 例在册环境失败** | `execute/DbDriverPipelineTest` 的 `testDriverArtifactResolvesToJarsOnClasspath`（本地驱动版本集不含 ConnectorJ 9.7.0）与 `testSeedFilesLandWhereDbLooksForThem`（新工作树沙盒路径未播种） | 先判定是否纯环境性（干净环境复现），再决定「修判据」或「登记为环境前置」；**依赖本机 Database 插件/驱动环境** | 依赖环境 |
| R7 | **真机级探针未纳门禁** | `dev-support/e2e-fake-ide`（假 IDE + `-javaagent` 注入真实 Spring Boot/MPJ 应用，本轮靠它抓到 T-08）现为手动运行 | 包一个 gradle 任务（如 `e2eFakeIde`）挂到收口清单，作为**发布前置**的可脚本化验收（不挂默认 `testShards`，避免拖慢门禁） | ~1 小时 |

## 二、待验证前提（实施前先证伪，勿直接照抄）

| # | 前提 | 验证方式 |
|---|---|---|
| P-1 | R1 补齐后标记行行号**只变准不变错** | 在 demo（join，dm profile）上跑 `rightAlias`/`extSub`，对照面板位置与 `SQLVIS-SOURCE` 标记行；改动前后各取一次读数 |
| P-2 | R2 的「快照持有引用」不会让 `capture/restore` 语义退化（服务未创建时应为 no-op 而非报错） | 先写一条用例：服务未创建时 `capture` 返回可空快照、`restore` 静默跳过；再改实现 |
| P-3 | R3 放宽到 2× 不会掩盖真实节奏退化 | 改前后各跑一轮 `HeartbeatRhythmTest`，并保留「退回累加计时形态必红」的反向验证读数 |
| P-4 | R5 若选 B，「停采期间 prepare、恢复后执行」在 `ReuseExecutor` 下确实恢复产出 | 构造用例（`ConfigApplyEndToEndTest` 同款真 socket + 真 `SenderLoop`）钉住 |
| P-5 | R6 两例确为环境性（非判据错） | 干净环境/基线提交复跑对照；若基线也红则按环境前置登记，不动判据 |
| P-6 | R7 的探针可稳定复跑（不依赖特定端口/时序） | 连续跑 2 轮，两轮读数一致才纳门禁 |

## 三、执行顺序与写集

```
① 两个真缺陷（用户要求先做）：T-ExplainChainHardening (O-48) → T-ErrorLocateUsability (O-49)
   —— 两者都改 ui/SqlPreviewPanel.kt，须串行
② 本计划 R1 + R2（写集互不相交：CallerSourceLocator+同步守卫 / testutil+姊妹守卫+5~7 测试类）
   —— 与 ① 也不相交，可并行
③ R3（agent 子项目测试）+ R4（AgentManager 等）
④ R5（待 D1 定案）、R6（依赖环境）、R7（构建脚本 + dev-support）
```

**写集冲突提示**：R1 与 R2 都碰测试目录但**文件不相交**；R2 与 O-55 计划的收口测试（`LocatorSkipPrefixSyncTest`）不相交（R1 才碰它）；R7 碰 `build.gradle.kts`（与并行会话的临时 sandboxContainer 改动同文件，实施前先 `git status` 确认）。

## 四、不做的事

- 不引入「两侧共享常量」（D3 已定案保持两份，见 O-55 计划 §八）——R1 只是**数据同步**，不建依赖。
- 不改 MCP 审计的既有门（D1 已定案：只受 MCP 工具开关，不受捕获开关；本轮已落地 `addAuditEntry`）。
- 不动 `testShards` 的分片权重/分片规则（flake 的正解是消除毒化来源，不是重排分片）。

## 五、待用户定案

- **D1：已定案（2026-09-22 用户）——选 B（改为「只在执行级短路」）。**
  理由（长远视角）：① 消除的是一类**静默失败**（停采期间 prepare 的语句恢复后永不产出，不报错不提示——与刚交付的 T-08 同族，而 T-08 只有真机探针才抓到）；② 规则从「连接级/语句级/执行级三层各看一次开关」收敛为**一条**「开关只在执行点判定」；③ 与 T-08 的修法同构（连接级一律包装、只在调用处短路），避免后来人问「为什么连接一律包、语句却看状态」——那正是下一个缺陷的温床；④ 可被确定性用例钉住，而 A 的缺口只能写进 KDoc，文档里的边界最后都会变成报障。
  代价与压法：停采态每个 `prepareStatement` 多一次代理分配（小对象，且只在已授权增强捕获的进程内）→ 在性能复查里登记一次；「关闭态会不会被代理搞坏」不是新增面（捕获开着时本来就把代理交给应用）→ 仅在 KDoc 写明。**另修** `dropToRaw` 的一次性告警名额被停采态失败消耗的问题（停采态只计数不打印、不占名额）。
  实施：见 R5 行的具体做法与用例。

## 六、验收判据

- 每项：定向测试绿 + **反向验证**（摘掉修复分支必红）读数；R1/R2/R4 另需门禁（`testShards` + `verifyGate`）重跑。
- 全计划收口：`testShards` 三片 0 失败 + `verifyGate` 三目标 Compatible + agent 子项目 0 失败；
  R1 需附「面板位置 vs 标记行」改动前后对照读数；R6 若判环境性需附基线对照读数。

## 实施记录

### 一、车道与提交（2026-09-22，分支 `t-agent-residuals`，已合入 master `b0bde1a`）

| 项 | 提交 | 要点 |
|---|---|---|
| R1 | `345cd3c` | IDE 侧 `CallerSourceLocator` 跳过表补齐到与 agent 侧同集（15→58 项）+ 文档示例同步 + 守卫覆盖 |
| R1b | `e601602` | **同步生效副本**：注入模板 `SourceInterceptorTemplates` 的 Java `isFrameworkFrame` 补齐同集 + 守卫扩到**全部 4 份副本**（含「四份都在扫描面内」防静默消失用例）+ KDoc 列清副本与消费者 + 迁移前提（存量已注入拦截器幂等不升级） |
| R2 | `54bc1fa` | 测试侧 `@After` 不再取服务（快照持有 manager 引用）+ 姊妹守卫 `BannedApiSourceScanTest` 判据同步 |
| R3 | `40d6386` | 心跳容差 2× **+ 形态判据移到观察窗内相邻间隔中位数（1.5×）** |
| R4 | `cc0e6e2` | ACK 超时 WARN 补判会话健康（分文案）+ `INTAKE_DROP_WARN_STEP`/`CONFIG_ACK_TIMEOUT_MS` 字面值断言 |
| R5 | `eec37d3` | 按 D1 选 B：删 `ConnectionHandler` 顶层短路、`StatementHandler` 成唯一闸门、`dropToRaw` 告警名额修正 + 确定性用例 |
| R7 | `b388adf` | 真机探针包成 `e2eFakeIde` 任务并挂 `releaseGate` |

### 二、反向验证读数（逐项红→绿）

- **R1/R1b**：整表回退 → 守卫 2 例红（`注入模板提取到的前缀过少…14 项`）；**精细对照**（只摘 `dm.jdbc.` 与 `com.alibaba.druid.`，计数仍 ≥55 绕过自检）→ **集合差异判据本身转红**（`expected:<[]> but was:<[dm.jdbc., com.alibaba.druid.]>`），证明承重的是差异判据而非计数阈值；恢复绿。
- **R2**：(a) 复位判据回退成旧双参口径 → `TST02d自证` 红，且**真实站点** `EpBridgeSinglePathTest.kt:40,41` / `SqlConsoleBridgeFilterProviderTest.kt:31` 被报出；(b) 把一个真实 `tearDown` 改回再取服务 → 新增结构规则红（命中 `SectionConfigurableAutoCaptureLinkageTest.kt:42`）；恢复绿。
- **R3**：累加计时探针连跑 2 轮均红（中位数 620ms / 626ms > 450ms）；恢复后 `cleanTest` 强制重跑 2 轮绿。
- **R4**：文案三态判据摘掉 + 常量改 25L → 3 例红；**仅改常量、不动测试源码**（测试类未重编译）→ 字面值用例仍红（证明反射形态不 stale）；恢复绿。
- **R5**：顶层短路加回 → 新用例红，实测包装者变成驱动自己的 `FakeDriver$FakeStatementHandler`（缺陷签名）；移除后绿。
- **R7**：连跑 3 轮，窗口内读数逐值一致（0/2/1/1/0、PASS=26 FAIL=0、`primary` 行号 92/42/63/103、`CONFIG_ACK=5`）；第 3 轮刻意取慢跑（44.7s vs 23.8s）读数仍一致。

### 三、计划前提被实测推翻/修正

| # | 前提 | 实测 | 处置 |
|---|---|---|---|
| P-3 | 「容差放宽到 2×」即可吸收抖动 | **推翻**：2× 单次上限会**掩盖**累加形态退化（单次间隔在 600ms 上下时红时绿；心跳条数判据对它不敏感） | 补强：形态判据移到**观察窗内相邻间隔的中位数（1.5×）**——累加形态中位数 ≈620ms 必红，抖动不影响中位数 |
| — | R1 的「IDE 侧＝降级主路径」表述 | **修正**：`CallerSourceLocator` 在 `src/main` **无调用点**；真正打印标记行的是**注入模板** `SourceInterceptorTemplates.kt:146` 的第 4 份副本（原先不在守卫覆盖内） | 追加 R1b 同步生效副本 + 守卫扩到全部 4 份副本；并写明迁移前提 |

### 四、新发现（只登记不处理）

- **`CallerSourceLocator` 疑为死代码**：`grep -rn "CallerSourceLocator" src/main` 仅 4 处命中，3 处为注释/KDoc（`HistorySqlLogPanel.kt:840`、`SqlLogPanel.kt:2128`、`SqlLogConsoleBridge.kt:584`），唯一代码命中是**其自身声明** `CallerSourceLocator.kt:28`；无反射/类字面量引用；非 docs 引用者只有其单测与本守卫。疑为 F-44-6 时代遗留（已被「注入模板 + agent 定位器」两条链取代）→ 是否并入 reachability 审计待定。

### 五、未做 / 留真机

- **R1b 的真机效果**：**已在真栈上验证（2026-09-22 收口补充）**——把仓内既有的**真机 jstack 采样**（`dev-support/e2e-fake-ide/evidence/p1-stack-evidence.txt`，JDK 8 + MyBatis-Plus-Join 真实应用、`/mpj/device/rightAlias` 调用链）的帧序列喂给三份跳过表，取「首个非跳过且行号有效的帧」：

  | 跳过表 | 选中帧 | 落点 |
  |---|---|---|
  | 旧模板表（13 项，R1b 前） | `com.github.yulichang.interceptor.MPJInterceptor.intercept` | `com/github/yulichang/interceptor/MPJInterceptor.java:85` —— **框架帧**（用户点跳转即落进第三方库） |
  | 新模板表（58 项，R1b 后） | `cn.song.mpjdemo.service.impl.DeviceInfoServiceImpl.joinRightAlias` | `cn/song/mpjdemo/service/impl/DeviceInfoServiceImpl.java:42` —— **真实用户调用点**（与面板侧 agent 通道给出的 `:42` 一致） |
  | agent 侧 `StackLocator`（56 项） | 同上用户帧 | `:42`（与注入模板同结论，佐证「两侧同集」） |

  注入拦截器位于 MPJ 外层 / 内层两种位置分别试算，结论相同。三份表前缀集已逐项核对：`agent ⊆ 插件侧 = 注入模板`，插件侧仅多两条（自身包 `cn.song.mybatisplus.visualizer.` 与 `$Proxy`——agent 侧把 `$Proxy` 写成 `contains` 判定，语义等价），与守卫 `LocatorSkipPrefixSyncTest` 登记的差异集合一致。

  **仍未做**：真实 IDE 里的**观察**（标记行文本随注入物产出）——需先按迁移前提**重注入**（删 `SqlSourceInterceptor.java` 后重新注入，或手工补前缀），存量副本幂等不升级；这一步是用户侧 GUI 动作（含注入同意弹窗），未代跑。
- **R6**（`testDatabase` 2 例）——**本工作树已绿**（驱动清单与种子链播种成功，见 §六）；其他环境若缺 Database 插件/驱动仍会红，口径保留。
- 宽前缀（`com.alibaba.` / `org.apache.` / `com.google.`）可能跳过用户落在这些包下的业务类——与 agent 侧同取舍，模板注释已提示可自行删行。

### 六、门禁（master 工作树，合并态）

在 `mpsv-agent-config-locate`（master，含车道 1 合并 `95a4ebd` + 车道 2 合并 `b0bde1a`）跑全量四步：

| 步骤 | 读数 | 结论 |
|---|---|---|
| `:sql-visualizer-agent:test` | **84 例 0 失败**（`--rerun` 强制重跑，45s；注意该子项目的 HTML 报告会被增量运行覆盖成「1 例」，须重跑后再读） | 绿 |
| `cleanTestShards testShards` | **498 套件 / 5697 例 / 0 失败 / 6 跳过**（跳过 6 例＝`DialectDockerVerificationTest` 六个真库方言套件，无 Docker 时的既有预期跳过） | 绿（首跑红，见下） |
| `verifyGate` | 243.28141.41 / 253.33514.17 / 262.10968.63 **三目标全 Compatible** | 绿 |
| `testDatabase` | **10 例 0 失败** | 绿（R6 在册 2 例 `DbDriverPipelineTest` 在本工作树亦绿） |

**门禁首跑红两例（一条真信号、一条 flake，逐条处置）**：

1. **`EnumReachabilityGuardTest`（真信号，已修）**：报 `ExplainSupport.kt:19 ExplainOutputFormat.XML —— 死常量（零引用）`——车道 1 为「SQL Server 未接入的 XML 计划形态」预留了占位枚举值，被仓内既有守卫判死。**修法＝删常量**（含 KDoc 与 `ExplainIndexAdvisor` 分派注释），**不**走守卫白名单（本处属投机预留，非「有产出计划的占位」）。**红→绿读数**：修前 `testShard2` 报 `EnumReachabilityGuardTest.kt:258` 断言失败；删后定向重跑 `EnumReachabilityGuardTest` 3 例 + `DialectExplainTest` 19 例 + `ExplainIndexAdvisorTest` 18 例 = **40 例 0 失败**，随后全量分片绿。
2. **`EntityClassFinderIntegrationTest.testVoDtoExcludedFromDownstreamCandidates`（flake，重跑绿）**：`IndexNotReadyException`（`DumbServiceImpl`，索引未就绪时读 `JavaShortClassNameIndex`）——该类不在本轮任何车道的写集内，重跑全量分片即绿。登记为「索引就绪竞态」类 flake，非本交付引入。

**门禁期间环境噪音（登记，不计入结论）**：首跑 `testDatabase` 3 例 `UnsatisfiedLinkError: no freetype in system library path: …\ideaIU-2024.3.7.1-win\jbr\bin`（2026-09-18 已诊断过的**共享 Gradle transform 缓存被并行会话重抽**形态，本机同时有多个并行会话在跑）；第二次重跑转为 `could not open …\jbr\lib\jvm.cfg`（缓存正在被重抽，测试 JVM 起不来）。等缓存稳定（`lib=37`、`jbr/bin=132`、`jvm.cfg` 就位）后第三次重跑即 **10 例全绿**。同类形态见 `docs/MEMORY.md`「回归假失败清单」。

