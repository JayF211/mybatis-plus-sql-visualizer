# T-StabilityTail 测试脆弱前置与分片顺序依赖根治及 PS5-02 显式传参 — 开发计划

- 日期：2026-09-19 18:17
- 任务编号：T-StabilityTail（T-ST-01 ~ T-ST-03）
- 来源：竞品第八轮批次（T-TailSweep2 + 八份计划 + T-PerfSweep5）交付时**登记未做**的三项尾款——
  ①`PluginCacheManagerCoverageTest` 脆弱前置（登记于 `docs/开发计划/20260919-0445-T-McpAuditTrail-…-开发计划.md` §九-⑨ 末段）；
  ②分片重平衡型顺序依赖的 `AlreadyDisposedException` 形态未复现（登记于同处 §九-⑨ 的「未验证 / 未宣称」②）；
  ③T-PS5-02 面板侧显式传参未接（登记于 `docs/开发计划/20260919-0433-T-PerfSweep5-…-开发计划.md` §八 偏离说明与 §九）
- 性质：**测试稳定性 + 性能尾款**——T-ST-01/02 只动测试与守卫（不触生产逻辑）；T-ST-03 是可选件、只动调用点接线
- 基线：master `c24aab3`（testShards **5362/0/0/6** + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 一、基线（实测）

| 项 | 值 |
|---|---|
| 门禁 | `testShards` 5362/0/0/6（shard1 1591 / shard2 1702 / shard3 2069）、`testDatabase` 10/0/0/0、agent 58/0/0/0 |
| 已复位的一族 | `SqlLogManager` 项目服务级状态：5 个泄漏类各加 `SqlLogManagerProjectState` 守卫（`SqlConsoleBridgeFilterProviderTest` / `EpBridgeSinglePathTest` / `McpHistoryIngestTest` / `SectionConfigurableAutoCaptureLinkageTest` / `AgentListenSyncTest`），实测各类 4 处引用（import + capture + restore + KDoc） |
| 共享面机制 | light 测试项目在**同一分片 JVM 内跨测试类复用同一个 `ProjectImpl`**（`LightPlatformTestCase.ourProject` 片内只建一次，`doSetup` 每用例只 `setLightProjectName` 改名）→ `project.getService(...)` 是**全片共享单例**，仅在项目关闭时 `dispose` |
| 分片组成 | 由 `build/test-results/**/*.xml` 的历史时长权重贪心推导 → **新增测试类即重排邻居**，潜伏的顺序依赖随之显隐 |

---

## 二、G1 测试稳定性

### T-ST-01 `PluginCacheManagerCoverageTest` 脆弱前置（P3，小）

- **现状**（`src/test/kotlin/cn/song/mybatisplus/visualizer/util/PluginCacheManagerCoverageTest.kt:139-143`）：

  ```kotlin
  fun testClearAllDoesNotInstantiatePreviewService() {
      assertNull("前置：测试项目不应已创建 SqlPreviewService", project.getServiceIfCreated(SqlPreviewService::class.java))
      PluginCacheManager.clearAll(project)
      assertNull("clearAll 不应触发 SqlPreviewService 实例化", project.getServiceIfCreated(SqlPreviewService::class.java))
  }
  ```

- **问题**：`:140` 那条 `assertNull` 不是被测行为，而是**环境前置**（「测试项目此刻还没创建过该服务」）。同片 JVM 共享 `ProjectImpl`，只要更早的类创建过 `SqlPreviewService` 即红——而 `clearAll` 什么都没做错。**判定取决于分片邻居，不取决于被测代码**。同片创建者嫌疑：`integration/PreviewEndToEndTest`、`psi/jpa/HbmNamedQueryResolverTest`（二者都 `getService(SqlPreviewService)`）。
- **本轮为何未发作**：分片恰好把创建者分到 shard3、本类分到 shard1，属巧合。
- **做法**：前置换成「记录调用前实例 → 断言调用后仍是**同一实例**」：

  ```kotlin
  val before = project.getServiceIfCreated(SqlPreviewService::class.java)
  PluginCacheManager.clearAll(project)
  assertSame("clearAll 不得触发 SqlPreviewService 实例化", before, project.getServiceIfCreated(SqlPreviewService::class.java))
  ```

  语义不变（仍能抓「clearAll 创建了服务」），去掉顺序依赖：本来就存在时断言「没被换成新实例」，本来不存在时是 `null == null`（与原意图一致）。
- **先红后绿（必做）**：临时让 `clearAll` 真的去 `getService`（或注入替身）→ 断言必须红，证明改后仍承重、不是把断言改弱。
- **注意**：该文件属 `util/` 域、非本批任何任务的写集，改动前先确认无并行会话在改它。
- **⚠️ 实施期更正（2026-09-19，实施方实测证伪本节机制归因）**：本节原写「light 测试项目同片共享 `ProjectImpl` → `:140` 红」**对本类不成立**——`PluginCacheManagerCoverageTest` 是 `HeavyPlatformTestCase`，实测**每个测试方法拿到的是不同的 Project 实例**（探针 `hash=1898437131` vs `hash=1227671987`，路径分别为 `unitTest_<方法名>_…/<方法名>_.ipr`）；两个「创建者嫌疑」类（`integration/PreviewEndToEndTest`、`psi/jpa/HbmNamedQueryResolverTest`）都是 `BasePlatformTestCase`（light），用的是**另一个** Project 实例。故**改前那个类序组合本来就是绿的**，`testShards` 分片位置对本类不可能产生影响。改动价值仍在：该断言确实在「断言环境」（受控替身实验：同用例内先 `project.getService(SqlPreviewService)` → 旧形态红、新形态绿），改后与顺序/邻居彻底无关，且将来本类若转 light 也不会中招。

### T-ST-02 分片顺序依赖的 `AlreadyDisposedException` 形态（P3，中）

- **已修的部分（勿重做）**：`SqlLogManager` 一族 5 个泄漏类已复位，且用**顺序无关探针**钉住过因果（守卫短路 → 红「共享 buffer 应无残留条目 expected:<0> but was:<2>」，2 条正是 `McpHistoryIngestTest` 入队的；守卫生效 → 绿）。
- **未宣称根治的部分**：本轮全量首跑的 5 例 `AlreadyDisposedException: Container is already disposed`（`SectionConfigurableAutoCaptureLinkageTest`×2、`AgentListenSyncTest`×1、`McpHistoryIngestTest`×2，**抛点在 `project.getService(...)`**）属**另一种症状**（容器已释放，而非状态被污染）。同族嫌疑面（共享实例捕获态 + 桥轮询）已覆盖，但**未单独复现该形态**——用同一批类名与顺序在 `test` 任务下复跑为绿。
- **任务**：
  - **T-ST-02a 先复现**：在受控条件下复现该形态。可行思路（择一或组合）：①用 `--tests` 显式固定类序，构造「先释放容器、再 `getService`」的前置；②固定分片组成（把当时的时长权重快照存下、或直接按类名分片）后重跑；③在同一 JVM 内先跑一个显式 dispose 项目的类、再跑受害者类。**复现不了就不许猜根因**。
  - **T-ST-02b 复现成功则定根因并修**：查清是谁 dispose 了容器（`LightPlatformTestCase` 的 teardown 顺序？某测试类显式 `dispose` 项目？平台在片内回收？），再定修法。
  - **T-ST-02c 复现不了则给观测手段**：在 `AlreadyDisposedException` 的抛出路径加**带类名/片号/触发点**的诊断日志（只在异常消息里补上下文，不改行为），使下次门禁若再发即可一眼定位；并明确写「不可复现，仅登记 + 已布观测」——**不得以「同族嫌疑面已复位」当作根治**。
- **T-ST-02d 防回潮守卫（建议一并做，价值高于逐次人工审计）**：本轮对「改项目服务级状态不复位」的审计是**人工逐个判定**（非泄漏清单见 `20260919-0445` §九-⑨），会随代码演进失效。建议加一条**源码扫描守卫**：`setUp`/`@Before` 内出现对 `project.getService(...)` 返回对象的写操作（赋值/改可变字段），而该类内无 `capture`/`restore`（或 `loadState`/`clearForTest` 等复位调用）→ 红。先红后绿：构造一个「改而不复位」的合成样本证明会红。

#### ⚠️ 独立复核结论（2026-09-19，复核者字节码级验证；已作为既成事实采纳）

> **实施方已用 javap 在测试所用平台 jar（`ideaIU-2024.3.7.1-win`，`lib/app-client.jar`）上独立复核下列两条**，非仅采信结论：① `LazyInstanceHolder$initialize$1$1.invokeSuspend` 的异常表确为 `from 36 to 73 → 104 Class java/lang/Throwable` / `from 90 to 101 → 104 Class java/lang/Throwable`（**无 `CancellationException` 过滤**），handler 104–121 无条件 `new CannotInitialize(instanceClass, throwable)` + `access$complete`；`tryGetInstance()` 偏移 43–56 为 `instanceof CannotInitialize` → `getInitializationError()` → `athrow`（缓存重抛）。② `ProjectImpl.isDisposed()` 偏移 0–19 = `super.isDisposed() ifne → 1`，否则读 `isTemporarilyDisposed` 字段（`ifeq → 0`）——**窗口内确为 true**，故 `isDisposed` 判据在真机语义下成立（不止测试桩）。

**T-ST-02b（`45afdf7`）的改动本身正确、必要、无副作用，但「根治」这一口径不成立。** 平台侧证据（`com/intellij/platform/instanceContainer/internal/LazyInstanceHolder`）：

- `LazyInstanceHolder$initialize$1$1` 的异常表是 `catch java.lang.Throwable`（**无 `CancellationException` 过滤**），catch 后无条件 `new CannotInitialize(instanceClass, throwable)` + `access$complete`；
- `LazyInstanceHolder.tryGetInstance()` 见 `_state instanceof CannotInitialize` 即 `throw getInitializationError()`；
- ⇒ **一次创建失败就把该服务在该项目实例上永久毒化**——此后每次 `getService` 都重抛同一个缓存异常（每次再被归一成新的 "Container is already disposed"）。
- 旁证（复核者从实施方自己的诊断日志读出）：8 条 `[T-ST-02]` WARN **全部**打印 `project.isDisposed=false`，而消息是 "Container is already disposed"——抛出时刻 `owner.isDisposed` 必为真、catch 时刻同一对象为假，只有「缓存重抛」能自洽。
- ⇒ 把取服务包进 `runCatching` **只吞掉了结果，阻止不了中毒**（它仍然发起了那次失败的创建）；同窗口再现时 `SectionConfigurableAutoCaptureLinkageTest#setUp:29` 那 3 例仍会红。**故本轮补 T-ST-02b2 前置判据。**

**不要把它「优化」回去**：删掉 `linkageAutoStartForProjects` 里取服务之前的 `isDisposed` 判据，就回到「窗口内取一次服务 = 该项目上的 `SqlLogManager` 被永久毒化」——一次失败创建即把服务 holder 打成不可用，后续所有 `getService` 都重抛缓存异常。KDoc 已把这段机制写在方法上（`config/SectionConfigurable.kt`）。

- **T-ST-02b2（本轮补做，`f11371a`）**：在取服务**之前**判 `Project.isDisposed`（平台 `ProjectImpl.isDisposed() = super.isDisposed() || isTemporarilyDisposed`，temporarily-disposed 窗口内为真）→ 跳过、**不发起**那次创建。跳过沿用既有 warn 文案，未新造。happy path 行为不变（正常项目仍取服务、仍调 `onAutoStartSettingEnabled()`、仍写回）。为可测性把循环体抽成 `internal fun linkageAutoStartForProjects(projects: List<Project>)` 测试缝（`ProjectManager.getInstance().openProjects` 是全局单例，测试无法注入）。先红后绿见 §七。
- **本批交付口径（不宣称）**：**已消除 `apply()` 穿出这一表现**（异常不再打断设置写回）+ **新增取服务前判 `isDisposed` 以不发起失败创建**；但平台侧 `LazyInstanceHolder` 的服务毒化（缓存 `CannotInitialize` 后永久重抛）与 temporarily-disposed 窗口本身**未触及**，**不宣称根治**。
- **T-ST-02b2 反向断言复验结论指针（2026-09-19，真窗口端到端，临时探针三组已删）**：**判据口径验证成立**（真窗口内 `getService` 调用数 = 0；关窗后受害调用点成功建出 `SqlLogManager`；无判据基线则关窗后受害点仍重抛缓存异常），**但「同窗口再现时那 3 例变绿」验证不成立**——受害点 `SectionConfigurableAutoCaptureLinkageTest#setUp:29` 不在守卫面内（另一调用点先创建则仍红），且 `closeProject` 的 light 分支在开窗的同一写动作里 `removeFromOpened`，窗口内该项目不在 `openProjects`，判据分支对该窗口不可达。读数与逐条依据见 §七-⑤。

---

## 三、G2 性能尾款

### T-ST-03 T-PS5-02 面板侧显式传参（P4，**可选件**）

- **现状**：共享基建已落在 `execute/UnboundedWriteDetector.kt` 的 **AST 单槽**（`parseStatementsShared`），`detect` 与 `RollbackSqlGenerator.generate` 共用 → **零调用点改动**即把一次大 DML 点击的最坏 4 次解析降到 1 次；`generateFromParsedStatements(cleaned, statements, pk)` 携解析入参的重载已就位（`generate` 委派它，无死代码）。
- **落点**（实测行号）：`ui/SqlPreviewPanel.kt` 的回滚注册链——`registerRollbackSnapshotIfNeeded` 定义在 `:833`、其中 `:848` 调 `RollbackSqlGenerator.generate(SqlCommentStripper.strip(sql))`、调用点在 `:785` 与 `:957`；确认流另有一处 `generate(sql)` 在 `:734`。
- **做法**：把确认阶段已解析的产物**显式**传给回滚链（`:848` 改走 `generateFromParsedStatements`），消除对「同文本命中缓存槽」这一隐式前提的依赖；若 `:734` 处即确认阶段解析点，同样让它复用。
- **键对齐（实施要点）**：`:848` 传入的是 `SqlCommentStripper.strip(sql)` 之后的文本，显式传递时必须保证「确认阶段产物」与「strip 后的文本」对应——否则会拿错 AST。
- **⚠️ 实施期更正（2026-09-19）**：本节原写「确认流另有一处 `generate(sql)` 在 `:734`，若即确认阶段解析点则同样复用」——**实测 `:734` 不在确认流内**，它在 `showRollbackSql()` 里、是工具栏【回滚SQL】按钮的独立动作（其 `sql` 已在 `:732` 剥过注释）；确认流的真实解析点是 `executeSql:944` / `executeAdhocSql:778` → `ExecuteConfirmMessages.build(sql, keyword)` → `UnboundedWriteDetector.detect`。
- **可选性说明**：**收益已由单槽全数拿到**，本项只是把「隐式经缓存槽复用」改成「显式沿调用流传递」，属形式统一，**没有额外性能收益**。做与不做都不影响 T-PS5-02 的读数；不做时应在计划/KDoc 保留「依赖同文本缓存命中」这条前提说明。
- **验收（若做）**：解析计数探针证明「一次点击的解析次数不增加」；`generate` 结果与独立解析逐字节一致（既有用例口径）。

---

## 四、并行与依赖（写集判定）

| 组 | 写集 | 可并行性 |
|---|---|---|
| T-ST-01 | `src/test/kotlin/.../util/PluginCacheManagerCoverageTest.kt` | 独立 |
| T-ST-02 | `src/test/kotlin/.../testutil/`、可能的 `convention/BannedApiSourceScanTest.kt`（T-ST-02d 守卫）、诊断日志落点（`log/` 或平台侧） | 与 T-ST-01 独立；与 T-ST-03 独立 |
| T-ST-03 | `ui/SqlPreviewPanel.kt`（`:848`/`:734` 调用点） | 与 T-ST-01/02 独立（**但 `ui/SqlPreviewPanel.kt` 是热点文件，开工前先确认无并行会话占用**） |

**建议执行序**：T-ST-01（小，先清）→ T-ST-02a 复现（不确定，先探）→ 视复现结果决定 T-ST-02b/c 与 T-ST-02d → T-ST-03 视需要。

---

## 五、验收

- **门禁**：`testShards` + `testDatabase` + agent 全绿；跑前 `rm -rf build/classes build/kotlin`。
- **T-ST-01**：先红后绿（证明断言仍承重）+ 在「创建者与本类同片」的组合下绿（可用 `--tests` 显式指定类序构造）。
- **T-ST-02**：**必须给出复现结论**——复现 → 根因 + 修 + 读数；不可复现 → 观测手段落地 + 明确写「不可复现、不宣称根治」。T-ST-02d 守卫先红后绿。
- **T-ST-03（若做）**：解析计数不增加 + 逐字节一致。
- 真机走查：本计划全部为测试基建与内部调用形态，**无真机面**。

---

## 六、非目标

- **不追求「分片顺序无关」这一更强性质**：分片组成由历史时长权重推导，属测试基建设计面，另议；本计划只消除**已暴露的具体顺序依赖**。
- **不改生产逻辑**（T-ST-03 若做也只改调用点接线，不改 `generate`/`detect` 语义）。
- **不动 K2J**（用户指示有分支在做）。
- 不做本轮已判定「非泄漏」的测试类改造（清单见 `20260919-0445` §九-⑨）。
- 不动发行元数据、不推送。

---

## 七、实施记录

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-ST-01 | `2370844` | `assertNull`（环境前置）→ `assertSame`（同一性比对），语义不变而去掉顺序依赖；**计划机制归因被实测证伪并已更正**（见 §T-ST-01 更正块：本类是 `HeavyPlatformTestCase`、每方法独立 Project 实例，改前该类序组合本来就绿）。先红后绿＝把 `clearAll` 的 `getServiceIfCreated(...)?.clearCache()` 临时改为 `getService(...)` → 红（`expected same:<null> was not:<…SqlPreviewService@2eea55c3>`）→ 还原绿。同文件第二处同类形态（`testFastPathHitLeavesReverseLookupEmpty…` 断言反查缓存「此刻为空」）一并改为用例自建前置（`EntityClassFinder.clearReverseLookupCache`），断言文本与强度逐字不变；另判定 `cacheSize() > 0` 一族为**反向形态**（不会因邻居假红）不动 | **T-ST-01 独立复核（2026-09-19，有条件通过 0 阻断 2 条件）**：①「语义不变」须限定为 **`before == null` 时**——`before != null` 分支下该断言**恒真**（项目服务是项目级单例，`getService` 已创建时必返回同一实例），连「clearAll 调了 getService」也测不出，属零信息而非变弱；可接受性依据＝**本用例 `before` 恒为 null**（已独立核实：`plugin.xml` 的 4 个 `postStartupActivity` 均无 `SqlPreviewService` 引用，`src/main` 所有该服务调用点均为用户动作触发），故强分支即实际执行路径、原意图完整保留。②三组先红后绿读数**不可复核**（`build/` 内本类只有绿 XML、无红跑痕迹），原理自洽但不构成可复核实证。③同类脆弱前置仍有残留（不在本任务写集）：`heavy/EntityClassFinderHeavyTest.kt:369-371`（首次 find 后直接断言 `tables == 1`、未先 clear）与 `:400`（`1 to 2`），同形、当前因 Heavy 无假红风险但未解耦。④补充边界：反向形态（`> 0`）**可能假绿**——`EntityResolver.cacheSize()` 是全局静态、片内跨类共享，邻居条目亦可通过，属「弱而不脆」、不可作承重证据。
| T-ST-02a | 见 02b/02c | **复现成功**（两条路径）：A＝受控前置 `ProjectManagerEx.forceCloseProject` 造 temporarily-disposed 窗口后首次取服务 → 与门禁**逐字同形**的链路（`AlreadyDisposedException` ← `MessageBusImpl.checkOwnerDisposed`，消息含 `containerState=disposed temporarily`）；B＝把门禁那轮的 `testShard1..3` XML 快照拷回当权重表、**同一份分片组成重跑** → 1896 例 7 失败（`SectionConfigurableAutoCaptureLinkageTest`×5 + 同族 1 + 另一族既有假失败 1） |
| T-ST-02b | `45afdf7` | **根因在生产代码**：`config/SectionConfigurable.kt:50` 的 `apply()` 里 `project.getService(SqlLogManager::class.java)` 写在 `runCatching` **之外** → 项目处于 temporarily-disposed 窗口时首次创建服务，其构造期 `messageBus.connect` 抛 `AlreadyDisposedException`（**extends `CancellationException`**）→ 归一成 "Container is already disposed" → **穿出 `apply()`** 打断设置写回；放大效应＝异常对象被服务 holder 缓存重复抛出，一次触发整类 5 例失败。修法＝守卫挪进同一 `runCatching`（1 处，happy path 逐字节不变，异常路径改为记既有 warn 并跳过该项目）。读数＝同一份分片组成重跑 **5362/0/0/6**。**写集偏离**：计划只列 `testutil/`+测试类+诊断落点，实际改生产文件——**派活方裁定接受**（根因确在生产侧、改动 1 处、happy path 无变化）。**⚠️ 口径更正（独立复核，见 §T-ST-02 复核块）**：本行原先隐含的「已根治」不成立——该修法**只吞掉结果、仍发起了那次失败的创建**，阻止不了平台侧 `LazyInstanceHolder` 的服务毒化；准确口径＝**已消除 `apply()` 穿出这一表现**，根治不宣称（前置判据见下一行 T-ST-02b2） |
| T-ST-02b2 | `f11371a` | **更正 `45afdf7` 的「根治」口径并补上真正阻止中毒的那一步**（独立复核者字节码级结论为据）：取服务**之前**先判 `Project.isDisposed`（`ProjectImpl.isDisposed()` 在 temporarily-disposed 窗口内为真——它等价于 `super.isDisposed() or isTemporarilyDisposed`）→ 跳过、**不发起**那次创建；沿用既有 warn 文案，未新造。为可测性抽 `internal fun linkageAutoStartForProjects(projects: List<Project>)` 测试缝。**先红后绿（同一改动面两次读数）**：先只抽缝不判状态 → `test --tests "…config.*"` **139/1**，唯一红＝新增用例 `testLinkage_disposedProject_skipsBeforeCreatingService`（`已 dispose 的项目不得发起服务创建… expected:<0> but was:<1>`，getService 桩调用计数 1 = 确实发起了失败创建）→ 加判据后同命令 **139/0/0/0**。新增 3 例：disposed→调用数 0 / 正常项目→仍取服务 1 次且 suppressed 清零（不误伤 happy path）/ 未判 disposed 但取服务失败→异常不穿出（02b 既有保证仍承重）。**不宣称根治**：平台侧毒化机制与 temporarily-disposed 窗口本身未触及 |
| T-ST-02c | `1ecd706` | 观测手段 `testutil/AlreadyDisposedDiagnostics.kt`：仅在抛 `AlreadyDisposedException` 时把**片号**（`test-sandbox/shardN` 反推）/ **触发点**（调用点 `类#方法:行`）/ 服务名 / 线程 / `project.isDisposed` / 项目名补进消息并记一条 WARN，**以同类型重抛**、原链路挂 cause（控制流与失败归属不变）；5 个守卫类 11 处调用点改走它 |
| T-ST-02d | `7aba2e2` + `adbbc02` | **独立复核判「有条件通过（2 项阻断）」→ 已修复**：原规则的**名字绑定只认 `getService(`**，而仓内 5 个已修泄漏类统一走 `AlreadyDisposedDiagnostics.projectService(…)`（该 wrapper 的文档正是要求改用它）→ **删掉真实泄漏类的 `restore` 行规则仍绿**（空绿）；另 4 条通用复位令牌在 `SqlLogManager` 上不存在对应方法、且复位判定是文件级 `contains` → 另一对象的同名复位可洗绿真泄漏。修复＝绑定令牌扩为 `getService(`/`projectService(` + 复位判定改「绑定承接名」（文件级 contains 移除）；验收用「能红」实证（删 restore 行 → 命中 1 处；换成他对象复位 → 仍红）。复核另纠正「仓内 0 处」是空绿、不构成精度证据。原行内容： 新增守卫「初始化入口改项目服务级状态须复位」（+436 行，**仅新增**，既有规则与豁免计数一行未动）：`setUp`/`@Before` 函数体内对 `project.getService(…)` 返回对象有写操作（成员赋值 / 变更类方法调用）而类内无复位令牌（`restore`/`restoreCaptureStateForTest`/`loadState`/`clearForTest`/`clearAll`/`resetForTest`，**刻意不含 `capture(` 与 `entries.clear()`**）→ 红；豁免用 `// project-state: <理由>`。先红后绿＝把合成泄漏样本混进**真实扫描面**（命中清单里只有注入样本 → 当时仓内 0 处）。既有 22 例 + 新增 2 例全绿即豁免计数未漂移。**已知漏报（有意不收编）**：方法体内写、helper 返回服务——收编到「类内任意写」会误判 `finally { setCapturingForTest(before) }` 那种合法复位形态 |
| T-ST-03 | `fe0d7fc` | **判定不实施显式传参**（计划标为可选件），三条硬依据：①确认阶段**不持有**可复用产物（`ExecuteConfirmMessages.build` 内部 `detect` 只回 findings、`build` 只回 `String`；真做须改 `execute/` 两个**共享 API**，而它们另有 `LogToSqlPanel.executeStatement` 与 MCP `CheckSqlTool`/`MapperCheckTool` 消费者）②**隐式前提去不掉**（`executeAdhocSql(dmlConfirmed = true)` 的回滚弹窗执行链不走确认弹窗、无确认阶段产物 → 入参只能可空、单槽兜底必须保留）③收益已全量拿到（实测）。改为写集内落地：`registerRollbackSnapshotIfNeeded` KDoc 补两条前提（确认阶段确以同一文本走过 `detect`；`SqlCommentStripper.strip` **幂等**，失效后果只多一次解析不影响回滚判定）+ `:734` 加注「本路径不在确认流内」+ 新增 `ui/SqlPreviewPanelRollbackParseShapeTest`（5 例，零 IDE fixture）钉住面板真实调用形状——**关键**：既有 `ConfirmRollbackParseShareTest` 走裸文本 `generate(sql)`，面板走 `generate(strip(sql))`，槽键命中**额外依赖 strip 幂等**，此前无用例覆盖（现用 7 种注释形态逐条钉住，含探针自证 `parseCount==2` 证明计数断言不空转）。读数 44/0/0/0 | **T-ST-03 独立复核订正（2026-09-19）**：原理由③「改成显式传递只是把前提从同文本命中挪成同文本命中」**不成立**——存在**面板侧自取槽变体**（`parseStatementsShared(cleaned)` → `generateFromParsedStatements`）从同一个 `sql` 变量算槽键，能**整条换掉 strip 幂等前提**（由 `strip` 纯函数性保证），且两个 API 都是 `internal`、与 ui 包同 Gradle 模块、无需改签名。**但「不实施」这个决定仍成立**：工具栏 `showRollbackSql()`（`:737`）无前置 detect、生产仍按双剥文本出结果，幂等前提换不掉；且收益为零性能/零语义、属 P4 可选件。另订正理由②的**归因**：`dmlConfirmed = true` **现行生产无调用点**（`RollbackSqlDialog.executeRollback` 未传它，回滚弹窗同样走确认弹窗），仅测试覆盖，故「不走确认弹窗」是测试路径而非生产路径；并订正 KDoc「失效后果只是多一次解析」对前提②偏宽（前提②失效会使命中路径用单剥 AST、miss 路径解析双剥文本，输入文本不同）。

**门禁（并轨后）**：`rm -rf build/classes build/kotlin` → `testShards` **5369/0/0/6**（5362 + T-ST-02d 2 + T-ST-03 5，精确吻合）；此后 T-ST-02b2 补 3 例（取服务前判 `isDisposed`）→ 当前 master 读数 **5372/0/0/6**（5369 + 3，BUILD SUCCESSFUL 5m31s）+ `testDatabase` 10/0/0/0 + agent 58/0/0/0（BUILD SUCCESSFUL 9m08s）。

**未验证 / 不宣称**：①T-ST-02 的「首次触发的精确落点逐案对账」——门禁那轮 5 例的完整 XML 已被后续绿跑覆盖，已证实的是**机制与触发点**（`SectionConfigurable.apply()` → `getService`），未证实门禁那轮每一例是否都由该路径触发（失败帧行号有差异：门禁版本在用例体 43/65，本轮实测在 `setUp:29`）；**⚠️ 独立复核补充（三条阻断，均按「未验证 / 不宣称」记账）**：（a）「门禁那轮每一例都由这条路径触发」**不成立**——可查到的首跑组成 `165/152/151`、5 例 3 类 vs 复现轮 `157/158/153`、7 例 3 类，**组成不同不能互证**；（b）复现轮另有 2 例 `AssertionError`（`super.setUp():27`）**不是** ADE 路径，实施方未举证、无归属；（c）**毒化机制本身**（`LazyInstanceHolder` 缓存 `CannotInitialize` 后永久重抛）已由复核者字节码级确认**仍存在**——T-ST-02b2 只是「不发起那次失败创建」，未触及平台侧毒化与 temporarily-disposed 窗口，故**不宣称根治**；②「谁让项目停在 temporarily-disposed 窗口」这一上游未深挖（修法是让调用侧不被它打断 + 不在窗口内取服务，非消除该窗口）；③T-ST-03 的单槽**并发命中率未测**（确认弹窗虽模态但 EDT 继续泵事件，别的链以别的文本填槽即 miss → 该次点击解析 2 次；后果仅性能，miss 分支已被探针自证覆盖）；④`WrapperConverterProjectScopeTest` 的 EP 注册表转储属另一族既有假失败，本批未处理；⑤T-ST-02b2 的**反向断言已在真实窗口上端到端复验完毕**（2026-09-19 补做；临时探针三个，跑完即删、未入库；窗口按平台 `ProjectManagerImpl.closeProject` 对 light 项目的原样三步打开＝`disposeEarlyDisposable()` + `setTemporarilyDisposed(true)`，实测 `isDisposed(inWindow)=true`；三组读数落 `F:/tmp/t-st-02b2-probe-baseline.txt` / `-guard.txt` / `-guard-other-trigger.txt`，各跑一次独立 JVM——毒化是项目实例级的，同 JVM 内会串台）。**结论分两层：判据口径验证成立，派生活动断言验证不成立**：

- **判据口径（「窗口内不再发起那次创建 → 不再出现缓存重抛」）：验证成立。**
  - 基线（无判据）：窗口内 `project.getService(SqlLogManager)` → `AlreadyDisposedException: Container is already disposed`，cause＝`CeProcessCanceledException` ← `AlreadyDisposedException: MessageBus(owner=Project(name=…, containerState=disposed temporarily, …) (disposed), disposeState= Alive)`（与门禁逐字同形）；关窗后 `isDisposed=false`，受害调用点（`AlreadyDisposedDiagnostics.projectService`，即那 3 例 `setUp:29` 同款调用）**仍抛**同一异常、且消息里 `project.isDisposed=false`——**「一次失败创建即永久毒化、此后重抛缓存异常」在真窗口上被直接证实**（此前只有字节码推演）。
  - 判据在位：同窗口、同受害调用点，`linkageAutoStartForProjects(真实 ProjectImpl)` 正常返回、计数代理测得 **`getService` 调用数 = 0**，关窗后受害调用点**成功**返回 `SqlLogManager` 实例——判据确实挡住了那次失败创建、holder 未被毒化。
- **派生活动断言（「同窗口再现时那 3 例变绿」）：验证不成立。** 两条独立理由，均可复核：
  - （a）**受害点不在守卫面内**：那 3 例红在 `SectionConfigurableAutoCaptureLinkageTest#setUp:29` 的 `AlreadyDisposedDiagnostics.projectService`，守卫只覆盖 `SectionConfigurable.apply()`。第三组探针（判据在位 + 另一触发点）读数：窗口内先让判据跳过，再由**另一个未受守卫的调用点**发起首次创建 → 关窗后受害调用点**仍抛** `Container is already disposed`（`project.isDisposed=false`，与 8 条诊断 WARN 逐字同形）。即窗口内任何其它调用点先创建，那 3 例仍红。
  - （b）**判据分支对该窗口不可达**：`ProjectManagerImpl.closeProject` 的 light 分支在 `setTemporarilyDisposed(true)` 的**同一写动作**里 `removeFromOpened(project)`（`ProjectManagerImpl.kt:347-350`），而 `apply()` 传的是 `ProjectManager.getInstance().openProjects` 快照（`getOpenProjects()` 即该数组，`ProjectManagerImpl.kt:245`）——**窗口内该项目根本不在列表里**，`if (project.isDisposed)` 收不到它；heavy 项目 dispose 路径同理（该分支前置条件即 `!isProjectOpened(project)`）。⇒ 门禁那 3 例的毒化源不可能是 `apply()`，补这条判据不改变它们的结局。
- **附带更正（窗口不是瞬时态）**：`TestApplicationManager.tearDownProjectAndApp` 末尾就是 `forceCloseProject(project)`（`TestApplicationManager.kt:138`）→ light 分支开窗；`LightPlatformTestCase.setUp` 的 `doSetup` 才 `setTemporarilyDisposed(false)` 关窗——窗口＝**每个测试方法之间被一直保持的区间**（每方法开一次），而测试体内的 `apply()` 在窗口内不会运行。故「谁在窗口内首次创建」只可能是窗口内的**异步 / EDT 队列 / 其它线程**调用点（main 侧 `SqlConsoleBridgeFilterProvider:27`、`LogStartupCapture:26`、`AgentManager:929/1158`、`McpToolsetSupport:203`、`SqlPreviewService:1999` 五处未受守卫），与失败族里同时出现 `AgentListenSyncTest`/`McpHistoryIngestTest`/`EpBridgeSinglePathTest` 相符。
- **对修法的影响（不淡化）**：真修本身**无副作用、happy path 不变**，对「生产态经 dispose 回调 / 设置面板触发的 `apply()`」仍有防御价值（并发关闭与迭代快照错位时判据会生效）；但**它不能作为门禁那 3 例的收口手段，也不应据此宣称「已消除该表现」**——要收口需覆盖窗口内的其它调用点（或不在窗口内首次创建任何订阅项目总线的服务）。另：02b/02b2 两轮的「先红后绿」读数（同组成重跑 5362/0/0/6）**因果性存疑**——若触发点本就异步，那两轮绿更可能是时序运气（本轮未做分片重跑，不对该点下断言）。
