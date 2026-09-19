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
| T-ST-01 | `2370844` | `assertNull`（环境前置）→ `assertSame`（同一性比对），语义不变而去掉顺序依赖；**计划机制归因被实测证伪并已更正**（见 §T-ST-01 更正块：本类是 `HeavyPlatformTestCase`、每方法独立 Project 实例，改前该类序组合本来就绿）。先红后绿＝把 `clearAll` 的 `getServiceIfCreated(...)?.clearCache()` 临时改为 `getService(...)` → 红（`expected same:<null> was not:<…SqlPreviewService@2eea55c3>`）→ 还原绿。同文件第二处同类形态（`testFastPathHitLeavesReverseLookupEmpty…` 断言反查缓存「此刻为空」）一并改为用例自建前置（`EntityClassFinder.clearReverseLookupCache`），断言文本与强度逐字不变；另判定 `cacheSize() > 0` 一族为**反向形态**（不会因邻居假红）不动 |
| T-ST-02a | 见 02b/02c | **复现成功**（两条路径）：A＝受控前置 `ProjectManagerEx.forceCloseProject` 造 temporarily-disposed 窗口后首次取服务 → 与门禁**逐字同形**的链路（`AlreadyDisposedException` ← `MessageBusImpl.checkOwnerDisposed`，消息含 `containerState=disposed temporarily`）；B＝把门禁那轮的 `testShard1..3` XML 快照拷回当权重表、**同一份分片组成重跑** → 1896 例 7 失败（`SectionConfigurableAutoCaptureLinkageTest`×5 + 同族 1 + 另一族既有假失败 1） |
| T-ST-02b | `45afdf7` | **根因在生产代码**：`config/SectionConfigurable.kt:50` 的 `apply()` 里 `project.getService(SqlLogManager::class.java)` 写在 `runCatching` **之外** → 项目处于 temporarily-disposed 窗口时首次创建服务，其构造期 `messageBus.connect` 抛 `AlreadyDisposedException`（**extends `CancellationException`**）→ 归一成 "Container is already disposed" → **穿出 `apply()`** 打断设置写回；放大效应＝异常对象被服务 holder 缓存重复抛出，一次触发整类 5 例失败。修法＝守卫挪进同一 `runCatching`（1 处，happy path 逐字节不变，异常路径改为记既有 warn 并跳过该项目）。读数＝同一份分片组成重跑 **5362/0/0/6**。**写集偏离**：计划只列 `testutil/`+测试类+诊断落点，实际改生产文件——**派活方裁定接受**（根因确在生产侧、改动 1 处、happy path 无变化） |
| T-ST-02c | `1ecd706` | 观测手段 `testutil/AlreadyDisposedDiagnostics.kt`：仅在抛 `AlreadyDisposedException` 时把**片号**（`test-sandbox/shardN` 反推）/ **触发点**（调用点 `类#方法:行`）/ 服务名 / 线程 / `project.isDisposed` / 项目名补进消息并记一条 WARN，**以同类型重抛**、原链路挂 cause（控制流与失败归属不变）；5 个守卫类 11 处调用点改走它 |
| T-ST-02d | `7aba2e2` | 新增守卫「初始化入口改项目服务级状态须复位」（+436 行，**仅新增**，既有规则与豁免计数一行未动）：`setUp`/`@Before` 函数体内对 `project.getService(…)` 返回对象有写操作（成员赋值 / 变更类方法调用）而类内无复位令牌（`restore`/`restoreCaptureStateForTest`/`loadState`/`clearForTest`/`clearAll`/`resetForTest`，**刻意不含 `capture(` 与 `entries.clear()`**）→ 红；豁免用 `// project-state: <理由>`。先红后绿＝把合成泄漏样本混进**真实扫描面**（命中清单里只有注入样本 → 当时仓内 0 处）。既有 22 例 + 新增 2 例全绿即豁免计数未漂移。**已知漏报（有意不收编）**：方法体内写、helper 返回服务——收编到「类内任意写」会误判 `finally { setCapturingForTest(before) }` 那种合法复位形态 |
| T-ST-03 | `fe0d7fc` | **判定不实施显式传参**（计划标为可选件），三条硬依据：①确认阶段**不持有**可复用产物（`ExecuteConfirmMessages.build` 内部 `detect` 只回 findings、`build` 只回 `String`；真做须改 `execute/` 两个**共享 API**，而它们另有 `LogToSqlPanel.executeStatement` 与 MCP `CheckSqlTool`/`MapperCheckTool` 消费者）②**隐式前提去不掉**（`executeAdhocSql(dmlConfirmed = true)` 的回滚弹窗执行链不走确认弹窗、无确认阶段产物 → 入参只能可空、单槽兜底必须保留）③收益已全量拿到（实测）。改为写集内落地：`registerRollbackSnapshotIfNeeded` KDoc 补两条前提（确认阶段确以同一文本走过 `detect`；`SqlCommentStripper.strip` **幂等**，失效后果只多一次解析不影响回滚判定）+ `:734` 加注「本路径不在确认流内」+ 新增 `ui/SqlPreviewPanelRollbackParseShapeTest`（5 例，零 IDE fixture）钉住面板真实调用形状——**关键**：既有 `ConfirmRollbackParseShareTest` 走裸文本 `generate(sql)`，面板走 `generate(strip(sql))`，槽键命中**额外依赖 strip 幂等**，此前无用例覆盖（现用 7 种注释形态逐条钉住，含探针自证 `parseCount==2` 证明计数断言不空转）。读数 44/0/0/0 |

**门禁（并轨后）**：`rm -rf build/classes build/kotlin` → `testShards` **5369/0/0/6**（5362 + T-ST-02d 2 + T-ST-03 5，精确吻合）+ `testDatabase` 10/0/0/0 + agent 58/0/0/0（BUILD SUCCESSFUL 9m08s）。

**未验证 / 不宣称**：①T-ST-02 的「首次触发的精确落点逐案对账」——门禁那轮 5 例的完整 XML 已被后续绿跑覆盖，已证实的是**机制与触发点**（`SectionConfigurable.apply()` → `getService`），未证实门禁那轮每一例是否都由该路径触发（失败帧行号有差异：门禁版本在用例体 43/65，本轮实测在 `setUp:29`）；②「谁让项目停在 temporarily-disposed 窗口」这一上游未深挖（修法是让调用侧不被它打断，非消除该窗口）；③T-ST-03 的单槽**并发命中率未测**（确认弹窗虽模态但 EDT 继续泵事件，别的链以别的文本填槽即 miss → 该次点击解析 2 次；后果仅性能，miss 分支已被探针自证覆盖）；④`WrapperConverterProjectScopeTest` 的 EP 注册表转储属另一族既有假失败，本批未处理。
