# T-StabilityTail 测试脆弱前置与分片顺序依赖及 PS5-02 显式传参 — 验收文档

- 日期：2026-09-19
- 任务编号：T-StabilityTail（T-ST-01 / T-ST-02 / T-ST-02d / T-ST-03）
- 计划文档：`docs/开发计划/20260919-1817-T-StabilityTail-测试脆弱前置与分片顺序依赖及PS5-02显式传参-开发计划.md`
- 实施基线：master `c24aab3`（testShards **5362/0/0/6** + testDatabase 10/0/0/0 + agent 58/0/0/0）——即竞品第八轮九任务并轨后的读数
- 实施形态：sam 四路并行工作树，19:33 四路并入 master（`a4ffb70` t-st-fragile-precond / `bb86065` t-st-shard-order / `acd821c` t-st-orphan-guard / `978e996` t-st-ps5-02-wire）

**结论前置**：四项全部落地——T-ST-01 脆弱前置收口；T-ST-02 复现成功、根因定位到**生产代码**并落地两步修法与观测手段；T-ST-02d 源码扫描守卫入库；T-ST-03 按计划判定**不实施**，改为前提留档 + 形状守卫。**T-ST-02 不宣称根治**：独立复核（字节码级）先证伪「根治」口径，随后的真窗口反向断言复验进一步表明该判据对 light 测试的 temporarily-disposed 窗口**不可达**，不改变门禁那 3 例的结局。门禁 `testShards` **5372/0/0/6**（并轨时 5369，T-ST-02b2 补 3 例后为 5372）+ `testDatabase` 10/0/0/0 + agent 58/0/0/0，全绿。本批**无真机面**（见 §六）。

## 一、改动总览

### 1. T-ST-01 脆弱前置收口（`2370844`，1 文件 +19/-4）

`util/PluginCacheManagerCoverageTest.kt` 两处「断言环境」改为「用例自建前置」，断言强度与文本逐字不变：

- `testClearAllDoesNotInstantiatePreviewService`：`:140` 的 `assertNull("前置：测试项目不应已创建 SqlPreviewService")` 断言的是**环境**（测试项目此刻还没创建过该服务）而非被测行为。改为「记录调用前实例 → `clearAll` → `assertSame` 同一实例」：服务本不存在时是 `null == null`（与原意图一致），已存在时断言「没被换成新实例」。仍能抓「`clearAll` 创建了服务」。
- `testFastPathHitLeavesReverseLookupEmptyWhileResolverAccumulates`：同类形态（断言反查缓存「此刻为空」），改为用例自建前置 `EntityClassFinder.clearReverseLookupCache(project)`（`psi/EntityClassFinder.kt:189`，`internal`）。断言文本与强度未动。

**实施期更正计划机制归因（实测证伪）**：计划 §T-ST-01 原写「light 测试项目同片共享 `ProjectImpl` → `:140` 红」——实测**对本类不成立**：本类是 `HeavyPlatformTestCase`，每个测试方法拿到**不同的 Project 实例**（探针 `hash=1898437131` vs `hash=1227671987`，路径分别为 `unitTest_<方法名>_…/<方法名>_.ipr`）；两个「创建者嫌疑」类（`integration/PreviewEndToEndTest`、`psi/jpa/HbmNamedQueryResolverTest`）都是 light 类，用的是另一个实例。故**改前那个类序组合本来就是绿的**，分片位置对本类不可能产生影响。改动价值仍在：该断言确实在断言环境（受控替身实验：同用例内先 `project.getService(SqlPreviewService)` → 旧形态红、新形态绿），改后与顺序/邻居彻底无关，将来本类若转 light 也不会中招。另判定 `cacheSize() > 0` 一族为**反向形态**（不会因邻居假红），不动。

### 2. T-ST-02 分片顺序依赖的 `AlreadyDisposedException`（本批重点，口径经两轮更正）

涉及四个提交：`1ecd706`（观测手段）→ `45afdf7`（第一步修）→ `f11371a`（第二步修，即 T-ST-02b2）；文档口径更正见 `9794ca5` / `517695f` / `a40c018` / `2f098c7` / `6a5ee0e` / `904f28a`。

**复现结论（T-ST-02a）：受控条件下复现成功，两条路径。** A＝用 `ProjectManagerEx.forceCloseProject` 造 temporarily-disposed 窗口后首次取服务，复现出与门禁**逐字同形**的链路（外层 `ComponentManagerImpl.kt:1670` 的 "Container is already disposed" + 最内 `MessageBusImpl.kt:224` 的 `MessageBus(owner=…, containerState=disposed temporarily, …)`）。B＝把门禁那轮的 `testShard1..3` XML 快照拷回当权重表、**同一份分片组成**重跑 → 1896 例 / 7 失败（`SectionConfigurableAutoCaptureLinkageTest`×5 + `PluginSettingsPanelBindingTest`×1 + `WrapperConverterProjectScopeTest`×1，末者为另一族既有假失败）。自然触发路径未复现（同批类名/顺序隔离复跑恒绿）。

**根因在生产代码**：`config/SectionConfigurable.kt:50`（修前行号）的 `apply()` 联动循环里，`val manager = project.getService(SqlLogManager::class.java)` 写在 `runCatching` **之外**。完整事实链：

```
项目处于 temporarily-disposed 窗口（ProjectImpl.isDisposed == true，实例容器未释放）
→ getService(SqlLogManager) 首次创建该服务
→ SqlLogManager.<init> 订阅 project.messageBus（SqlLogManager.kt:164）
→ MessageBusImpl.checkOwnerDisposed 判 owner.isDisposed 为真
  → AlreadyDisposedException（MessageBusImpl.kt:224；该类 extends CancellationException）
→ 容器服务创建链把该 CancellationException 归一成
  AlreadyDisposedException("Container is already disposed")（ComponentManagerImpl.kt:1670）
→ 穿出 apply()，打断设置写回，并把测试打成失败
```

放大效应＝异常对象被服务 holder 缓存后重复抛出，一次触发整类 5 例失败。

**修法两步**：

- ①（`45afdf7`）把取服务与消费合并进**同一个** `runCatching`——异常不再穿出 `apply()`；happy path 逐字节不变，异常路径从「穿出」变为「记一条既有 warn 并跳过该项目」。
- ②（`f11371a`）在取服务**之前**先判 `Project.isDisposed` 并跳过——**不发起**那次注定失败的创建。为可测性把循环体抽成 `internal fun linkageAutoStartForProjects(projects: List<Project>)` 测试缝（`ProjectManager.getInstance().openProjects` 是全局单例，测试无法注入），`apply()` 改传 `openProjects.toList()`。跳过沿用既有 warn 文案，未新造。KDoc 写明毒化机制与「勿优化回去」的理由。

**独立复核（字节码级）证伪「根治」**：平台 `LazyInstanceHolder$initialize$1$1` 的异常表为 `catch java.lang.Throwable`（**无 `CancellationException` 过滤**），catch 后无条件 `new CannotInitialize(instanceClass, throwable)` + `access$complete`；`tryGetInstance()` 见 `_state instanceof CannotInitialize` 即 `throw getInitializationError()`。⇒ **一次创建失败就把该服务在该项目实例上永久毒化**，此后每次 `getService` 都重抛同一缓存异常。故第 ① 步只吞掉结果、**仍发起了那次失败的创建**，阻止不了中毒；第 ② 步才是「不发起」的那一步。旁证：实施方自己采到的 8 条 `[T-ST-02]` WARN **全部**打印 `project.isDisposed=false` 而消息是 "Container is already disposed"——抛时 `owner.isDisposed` 必真、catch 时同一对象为假，只有「缓存重抛」能自洽。复核者另在测试所用平台 jar（`ideaIU-2024.3.7.1-win` / `lib/app-client.jar`）上用 `javap` 独立复核了异常表与 `ProjectImpl.isDisposed()` 两条事实（后者 = `super.isDisposed() || isTemporarilyDisposed`，窗口内确为 true，故判据在真机语义下成立、不止测试桩）。

**反向断言真窗口端到端复验（`2f098c7` / `904f28a`，三组探针各独立 JVM，探针跑完即删未入库）**——结论分两层，**判据口径成立、派生活动断言不成立**：

- **判据口径（「窗口内不再发起那次创建 → 不再出现缓存重抛」）：验证成立。** 基线（无判据）窗口内 `getService` 抛 "Container is already disposed"、关窗后受害调用点（`AlreadyDisposedDiagnostics.projectService`，即那 3 例 `setUp:29` 同款调用）**仍抛**且消息里 `project.isDisposed=false`；判据在位时同窗口同受害调用点计数代理测得 **`getService` 调用数 = 0**、关窗后受害调用点**成功**建出 `SqlLogManager`。毒化机制**首次在真实窗口上被直接证实**（此前仅字节码推演）。
- **派生断言（「同窗口再现时那 3 例变绿」）：验证不成立。** 两条独立理由：(a) **受害点不在守卫面内**——红在 `SectionConfigurableAutoCaptureLinkageTest#setUp:29`，守卫只覆盖 `SectionConfigurable.apply()`；第三组探针（判据在位 + 另一未受守卫的调用点先创建）读数显示关窗后受害调用点**仍抛**同一异常。(b) **判据分支对该窗口不可达**——`ProjectManagerImpl.closeProject` 的 light 分支在置 `setTemporarilyDisposed(true)` 的**同一写动作**里 `removeFromOpened(project)`（`ProjectManagerImpl.kt:347-350`），而 `apply()` 传的是 `openProjects` 快照（`getOpenProjects()` 即该数组，`ProjectManagerImpl.kt:245`）——**窗口内该项目根本不在列表里**，`if (project.isDisposed)` 收不到它；heavy 项目 dispose 路径同理。
- **附带更正：该窗口不是瞬时态**，是**测试方法之间被一直保持的区间**——`TestApplicationManager.tearDownProjectAndApp` 末尾即 `forceCloseProject(project)`（`TestApplicationManager.kt:138`）→ light 分支开窗；`LightPlatformTestCase.setUp` 的 `doSetup` 才 `setTemporarilyDisposed(false)` 关窗（每方法开一次）。测试体内的 `apply()` 在窗口内不会运行，故触发点只能是窗口内的**异步 / EDT 队列 / 其它线程**调用点。

**本批交付口径（不宣称）**：已消除 `apply()` 穿出这一表现 + 新增取服务前判 `isDisposed` 以不发起失败创建；平台侧 `LazyInstanceHolder` 服务毒化与 temporarily-disposed 窗口本身**未触及**，**不宣称根治**，也**不作为门禁那 3 例的收口手段**。修法本身无副作用、happy path 不变，对「生产态经 dispose 回调 / 设置面板触发的 `apply()`」仍有防御价值（并发关闭与迭代快照错位时判据会生效）。

**观测手段（`1ecd706`，T-ST-02c）**：新增 `testutil/AlreadyDisposedDiagnostics.kt`——仅在抛 `AlreadyDisposedException` 时把**片号**（从 `test-sandbox/shardN` 反推）/ **触发点**（栈里第一个本仓测试源码帧 `类#方法:行`）/ 服务名 / 线程 / `project.isDisposed` / 项目名补进消息并记一条 WARN，**以同类型异常重抛**、原链路挂 cause（控制流与失败归属不变）。落地时 5 个守卫类的 **11 处** `project.getService(SqlLogManager)` 调用点改走它（`f11371a` 新增用例后现为 12 处）。

### 3. T-ST-02d 防回潮守卫（`7aba2e2`，1 文件 **+436/-0 仅新增**）

`convention/BannedApiSourceScanTest.kt` 新增规则「初始化入口改项目服务级状态须复位」。判据：某测试类的 `setUp` / `@Before` 函数体内对 `project.getService(…)` 返回对象有写操作（成员赋值或变更类方法调用），而**类内**没有任何复位令牌（`SqlLogManagerProjectState.restore(` / `.restoreCaptureStateForTest(` / `.loadState(` / `.clearForTest(` / `.clearAll(` / `.resetForTest(`）→ 红。豁免用写入行上方连续注释块里的 `// project-state: <理由>`。

- 复位令牌**刻意不含** `capture(`（只 capture 不 restore 正是「存了快照忘了还原」的半成品）与 `entries.clear()`（清条目不等于复位状态——修复前的 `SqlConsoleBridgeFilterProviderTest` tearDown 里正有 `clear()`，而泄漏的是 `dedupeEnabled`）。
- 既有规则与豁免计数一行未动：该文件 `@Test` 由 22 例（提交前，另有 1 处 `@Test` 字样在 KDoc 里）增至 **24 例**，即「既有 22 + 新增 2」。
- **已知漏报（有意不收编，KDoc 与自证用例双向钉住）**：方法体内写、helper 返回服务、多行赋值、复位在某分支而泄漏在另一分支。收编到「类内任意写」会误判 `finally { setCapturingForTest(before) }` 那种合法复位形态（仓内已有此形态）。

### 4. T-ST-03 PS5-02 面板侧显式传参：判定不实施（`fe0d7fc`）

T-PS5-02 的收益（一次大 DML 点击的最坏 4 次解析降到 1 次）已由 `execute/UnboundedWriteDetector.kt` 的 **AST 单槽**（`parseStatementsShared`）全数拿到，T-ST-03 只是把「隐式经缓存槽复用」改成「显式沿调用流传递」的形式统一，**无额外性能收益**。三条硬依据：

1. **确认阶段不持有可复用产物**：`ExecuteConfirmMessages.build` 内部经 `detect` 解析后只回 findings（`WriteRisk`），AST 不外传；`build` 只回 `String`。真做须改 `execute/` 包**两个共享 API**，而它们另有 `LogToSqlPanel.executeStatement` 与 MCP `CheckSqlTool` / `MapperCheckTool` 两条链的消费者——超出本任务写集。
2. **隐式前提去不掉**：`executeAdhocSql(dmlConfirmed = true)` 的回滚弹窗执行链**不走确认弹窗**，无确认阶段产物 → 入参只能可空、单槽兜底必须保留，即便改也只多一条并存路径。
3. **收益已全量拿到**：面板内自取槽（`parseStatementsShared(cleaned)` → `generateFromParsedStatements`）读的是同一个槽，只把前提从「同文本命中」挪成「同文本命中」，零语义零性能变化。

**落地改为写集内留档 + 形状守卫**：

- `ui/SqlPreviewPanel.kt` `registerRollbackSnapshotIfNeeded` 的 KDoc 补齐「同文本命中单槽」的**两条前提**——①确认阶段确以同一文本走过 `detect`；②`SqlCommentStripper.strip` **幂等**（面板传 `strip(sql)`，`generate` 内部再剥一次，键成 `strip(strip(sql))`，幂等成立才落回同一槽）。并写明任一前提失效的后果**只是多一次解析**（不影响回滚判定正确性），以及显式传参的出口是 `generateFromParsedStatements`。`showRollbackSql()` 内加注「本路径不在确认流内」。
- 新增 `ui/SqlPreviewPanelRollbackParseShapeTest`（普通 JUnit，**零 IDE fixture**，5 例）钉住面板**真实调用形状**：既有 `ConfirmRollbackParseShareTest` 走裸文本 `generate(sql)`，而面板走的是 `generate(strip(sql))`——槽键命中**额外依赖 strip 幂等**，此前无用例覆盖（全仓 `SqlCommentStripper` 相关测试类里只有本组提到幂等）。7 种注释形态逐条钉住，含「槽未命中必重解析」的探针自证（`parseCount` 1 → 2，证明计数断言不空转）。

**计划落点归属订正**：计划 §三 原写「确认流另有一处 `generate(sql)` 在 `:734`，若即确认阶段解析点则同样复用」——**实测 `:734` 不在确认流内**，它在 `showRollbackSql()` 里、是工具栏【回滚SQL】按钮的独立动作（其 `sql` 已在上一行剥过注释）；确认流的真实解析点是 `executeSql` / `executeAdhocSql` → `ExecuteConfirmMessages.build(sql, keyword)` → `UnboundedWriteDetector.detect`。

### 5. 计划自身被证伪两处（实施期实测发现，已在计划内更正）

| 计划原表述 | 实测结论 |
|---|---|
| §T-ST-01：「light 项目同片共享 `ProjectImpl` 导致 `:140` 断言红」 | 对本类**不成立**——本类是 `HeavyPlatformTestCase`、每测试方法独立 Project 实例，两个「创建者嫌疑」类是 light 类用另一实例，改前该类序组合本来就绿 |
| §T-ST-03：「确认流另有一处 `generate(sql)` 在 `:734` 可复用」 | 落点归属错——`:734`（现 master `:737`）在 `showRollbackSql()` 工具栏按钮动作里，不在确认流内 |

## 二、自动化验证清单

各路的定向读数（红=修复前/构造违规，绿=修复后）。

| 路 | 命令 | 读数 |
|---|---|---|
| T-ST-01 | 定向（`util.PluginCacheManagerCoverageTest`） | **先红后绿**：把 `clearAll` 的 `getServiceIfCreated(...)?.clearCache()` 临时改为 `getService(...)` → 红（`expected same:<null> was not:<…SqlPreviewService@2eea55c3>`）→ 还原绿。受控替身实验：creator-first 条件下旧形态红、新形态绿；反查缓存被污染时旧形态红（`expected:<0> but was:<58>`）、自建前置后绿 |
| T-ST-02a | 受控前置 + 同组成重跑 `testShards` | 复现成功两条路径（A 与门禁逐字同形；B 同组成 **1896 例 / 7 失败**，组成 shard1=157 / shard2=158 / shard3=153 类） |
| T-ST-02c | 5 类定向复跑 | **22/0/0**；受控探针下消息为 `[T-ST-02] AlreadyDisposed@mpsv-st2 trigger=probe.controlled service=SqlLogManager caller=…ZzTmpDiagnosticsProbeTest#testDiagnosticsCarriesContext:19 thread=AWT-EventQueue-0 project.isDisposed=true project=DiagnosticsCarriesContext :: Container is already disposed` |
| T-ST-02b | 同一份分片组成重跑 `testShards` | **5362/0/0/6** |
| T-ST-02b2 | `test --tests "…config.*"`（同一改动面两次读数） | **先红 139/1**——唯一红＝新增用例 `testLinkage_disposedProject_skipsBeforeCreatingService`（`已 dispose 的项目不得发起服务创建… expected:<0> but was:<1>`，桩调用计数 1 = 确实发起了失败创建）；加判据后 **139/0/0/0**。新增 3 例：disposed→调用数 0 / 正常项目→仍取服务 1 次且 suppressed 清零（不误伤 happy path）/ 未判 disposed 但取服务失败→异常不穿出（第 ① 步保证仍承重） |
| T-ST-02b2（反向断言） | 真 temporarily-disposed 窗口三组探针，各独立 JVM | 基线（无判据）受害点关窗后仍抛且 `isDisposed=false`；判据在位窗口内 `getService` 调用数 **0**、关窗后受害点成功建出服务；判据在位 + 另一触发点 → 受害点仍抛。读数落 `F:/tmp/t-st-02b2-probe-baseline.txt` / `-guard.txt` / `-guard-other-trigger.txt` |
| T-ST-02d | 定向（`convention.BannedApiSourceScanTest`） | **先红后绿**：把合成泄漏样本混进**真实扫描面**（命中清单里只有注入样本 → 当时仓内 0 处）；**24 例 0 失败**（既有 22 + 新增 2，即豁免计数未漂移） |
| T-ST-03 | 定向（新类 + 4 个既有类） | **44/0/0/0** = 新类 5/5 + `ConfirmRollbackParseShareTest` 5 + `UnboundedWriteDetectorTest` 19 + `ExecuteConfirmMessagesTest` 6 + `RollbackSqlPanelTest` 9 |

## 三、独立复核

本批的独立复核集中在 T-ST-02，方式是**只读的字节码级验证**与**真窗口反向断言复验**，两者都产生了对交付口径的实质更正（非背书）。

### 1. 字节码级复核（`9794ca5` / `a40c018`，已作为既成事实采纳）

复核者在实施方所用平台 jar（`ideaIU-2024.3.7.1-win` / `lib/app-client.jar`）上独立 `javap` 复核两条事实，未仅采信结论：

- `LazyInstanceHolder$initialize$1$1.invokeSuspend` 异常表实测 = `from 36 to 73 -> 104 Class java/lang/Throwable` 与 `from 90 to 101 -> 104 Class java/lang/Throwable`（**无 `CancellationException` 过滤**），handler 104–121 无条件 `new CannotInitialize` + `access$complete`；`tryGetInstance()` 偏移 43–56 = `instanceof CannotInitialize` → `getInitializationError()` → `athrow`（缓存重抛）。
- `ProjectImpl.isDisposed()` 偏移 0–19 = `super.isDisposed() ifne → 1`，否则读 `isTemporarilyDisposed` 字段（`ifeq → 0`）→ temporarily-disposed 窗口内确为 true，故 `isDisposed` 判据在真机语义下成立（不止测试桩）。

**处置**：`45afdf7` 的「根治」口径不成立，交付口径改为「已消除 `apply()` 穿出这一表现」，并补 `f11371a` 那一步真正阻止发起失败创建的判据。

### 2. 真窗口反向断言复验（`2f098c7` / `904f28a`）

三组探针在真实 temporarily-disposed 窗口（按平台 `ProjectManagerImpl.closeProject` 对 light 项目的原样三步打开＝`disposeEarlyDisposable()` + `setTemporarilyDisposed(true)`，实测 `isDisposed(inWindow)=true`）上端到端复验。**推翻性结论**：判据口径成立，但派生活动断言（那 3 例变绿）不成立，理由见 §一-2。附带更正「窗口非瞬时态」与「02b/02b2 两轮先红后绿的因果性存疑」。

### 3. 独立复核登记的三条阻断（均按「未验证 / 不宣称」记账，非交付阻断）

- (a)「门禁那轮每一例都由这条路径触发」**不成立**——可查到的首跑组成 `165/152/151`、5 例 3 类 vs 复现轮 `157/158/153`、7 例 3 类，**组成不同不能互证**。
- (b) 复现轮另登记 2 例 `AssertionError`（`super.setUp():27`）**不是** ADE 路径，实施方未举证、无归属。
- (c) 毒化机制本身仍存在，平台侧未触及，**不宣称根治**。

### 4. 复核者未复核、我方仅转述的部分

- T-ST-01 / T-ST-02d / T-ST-03 三路未见独立复核记录（计划 §七 只有实施记录与实施期更正，无写验分离的复核块）。
- T-ST-01 的探针读数（`hash=1898437131` vs `hash=1227671987`）、T-ST-02a 的 A 路径复现、T-ST-02b2 的先红 `139/1`、T-ST-02d 的合成样本注入——均**无产物留档**（探针已删、XML 已被后续绿跑覆盖），本文只能转述计划与提交报文。
- 本批门禁的**当次跑**未由本文复跑（按任务约束不跑 gradle），5372 读数取自 `docs/变更记录.md` 与盘点文档的记载。


### 4. T-ST-02d 守卫规则 —— **有条件通过（2 项阻断）→ 已修复**

独立验证者（只读，用 Python 复刻规则逻辑跑真样本 + 全仓扫描）判定：规则**内部自洽、与自身规格一致、零副作用**，
但「该守卫已覆盖已审计的泄漏形态」这一**价值结论不成立**——若验收口径是「守卫能防住本轮 5 个已修泄漏类的复发」，
则为**不通过**。两条阻断：

1. **规则对仓内主流取服务形态完全不可见**：那 5 个已修泄漏类的 `getService(` 计数全为 0
   （`SectionConfigurableAutoCaptureLinkageTest` 的 1 处在 KDoc 里），它们统一走
   `AlreadyDisposedDiagnostics.projectService(project, X::class.java, …)`（全仓 13 处）——而该 wrapper 的文档
   正是要求测试类把 `project.getService(X::class.java)` 换成它；规则的名字绑定靠 `body.indexOf("getService(")`，
   wrapper 形态一个都绑不上。**实证**：把 `SqlConsoleBridgeFilterProviderTest` 的 `restore` 行整行删掉
   （原样复现修复前的泄漏）→ 规则仍 **offenders=0 绿**；仅改写成 `manager = project.getService(...)` 才红。
2. **4 条通用复位令牌是纯漏报通道**：`.loadState(` / `.clearForTest(` / `.clearAll(` / `.resetForTest(` 在
   `SqlLogManager` 上**不存在对应方法**，而复位判定是**文件级 `contains`、不与服务对象绑定** → 四个
   「另一个对象的复位」样本（`PluginSettings.loadState` / `JoinFkRegistry.clearForTest` /
   `PluginCacheManager.clearAll` / `FirstCaptureGuide.resetForTest`）把真泄漏**全部洗成绿**。

**修复（`adbbc02`，1 文件 +315/−41）**：①名字绑定令牌扩为 `["getService(", "projectService("]`，
wrapper 形态同样登记；②复位判定改「绑定承接名」（`hasBoundServiceReset`：`<承接名>.<复位口>(…)` 或
`SqlLogManagerProjectState.restore(<承接名>, …)`），文件级 `contains` 整块移除。
**验收用「能红」实证**：删 `restore` 行 → `命中 1 处：[…SqlConsoleBridgeFilterProviderTest.kt:31 → manager.entries.dedupeEnabled =]`；
把复位换成另一对象的 `resetForTest()` → 仍报 1 处；反向不丢覆盖面（`RollbackSnapshotStore` + `store.clearForTest()` → 0，
去掉复位行 → 1，证明该 0 非空绿）。既有 24 例与豁免计数零漂移。

**验证者另纠正的两处口径**：①「仓内 0 处」是**空绿**（写命中本身为 0），**不构成精度证据**——精度证据只取合成样本
（该表述已在 KDoc 改写）；②任务概述里「>1 顶层类的测试文件 2 个」不成立（实测 6 个），但**每个文件带初始化入口的
顶层类都只有 1 个**，故文件级判定的残留当前无实际影响。另登记：`restoreCaptureStateForTest` 是 3/15 字段的
**部分复位**，已从令牌排除；自证补了 `testSources()` 非空断言（否则路径解析失败会空屏绿）。

### T-ST-03 复核订正

**T-ST-03 独立复核订正（2026-09-19）**：原理由③「改成显式传递只是把前提从同文本命中挪成同文本命中」**不成立**——存在**面板侧自取槽变体**（`parseStatementsShared(cleaned)` → `generateFromParsedStatements`）从同一个 `sql` 变量算槽键，能**整条换掉 strip 幂等前提**（由 `strip` 纯函数性保证），且两个 API 都是 `internal`、与 ui 包同 Gradle 模块、无需改签名。**但「不实施」这个决定仍成立**：工具栏 `showRollbackSql()`（`:737`）无前置 detect、生产仍按双剥文本出结果，幂等前提换不掉；且收益为零性能/零语义、属 P4 可选件。另订正理由②的**归因**：`dmlConfirmed = true` **现行生产无调用点**（`RollbackSqlDialog.executeRollback` 未传它，回滚弹窗同样走确认弹窗），仅测试覆盖，故「不走确认弹窗」是测试路径而非生产路径；并订正 KDoc「失效后果只是多一次解析」对前提②偏宽（前提②失效会使命中路径用单剥 AST、miss 路径解析双剥文本，输入文本不同）。

### T-ST-01 复核结论

**T-ST-01 独立复核（2026-09-19，有条件通过 0 阻断 2 条件）**：①「语义不变」须限定为 **`before == null` 时**——`before != null` 分支下该断言**恒真**（项目服务是项目级单例，`getService` 已创建时必返回同一实例），连「clearAll 调了 getService」也测不出，属零信息而非变弱；可接受性依据＝**本用例 `before` 恒为 null**（已独立核实：`plugin.xml` 的 4 个 `postStartupActivity` 均无 `SqlPreviewService` 引用，`src/main` 所有该服务调用点均为用户动作触发），故强分支即实际执行路径、原意图完整保留。②三组先红后绿读数**不可复核**（`build/` 内本类只有绿 XML、无红跑痕迹），原理自洽但不构成可复核实证。③同类脆弱前置仍有残留（不在本任务写集）：`heavy/EntityClassFinderHeavyTest.kt:369-371`（首次 find 后直接断言 `tables == 1`、未先 clear）与 `:400`（`1 to 2`），同形、当前因 Heavy 无假红风险但未解耦。④补充边界：反向形态（`> 0`）**可能假绿**——`EntityResolver.cacheSize()` 是全局静态、片内跨类共享，邻居条目亦可通过，属「弱而不脆」、不可作承重证据。

## 四、门禁复跑

并轨后先 `rm -rf build/classes build/kotlin` 再跑。读数链条（**三个数字别混**）：

| 时点 | 门禁 | 读数 | 出处 |
|---|---|---|---|
| 实施基线（前一批「竞品第八轮九任务」并轨后） | `testShards` | **5362/0/0/6**（shard1 1591 / shard2 1702 / shard3 2069） | 计划 §一；`变更记录.md` 第八轮行；`20260919-0445-T-McpAuditTrail` §门禁 |
| 本批四路并轨时（19:33） | `testShards` | **5369/0/0/6**（BUILD SUCCESSFUL 9m08s）= 5362 + T-ST-02d 2 + T-ST-03 5，精确吻合 | 计划 §七「门禁（并轨后）」 |
| T-ST-02b2 补 3 例后（20:01 起） | `testShards` | **5372/0/0/6**（BUILD SUCCESSFUL 5m31s）= 5369 + 3，精确吻合 | `变更记录.md`；盘点第 20 版 |

**当前 master 读数是 5372**——它是「前一批 5362 + 本批新增 10 例」的累加值（2 + 5 + 3）。计划 §七 那一行仍写着并轨当时的 5369，未随 T-ST-02b2 更新；权威口径以 `变更记录.md` 与盘点为准。

| 门禁 | 读数 | 备注 |
|---|---|---|
| `testShards` | **5372 例 / 0 失败 / 0 错误 / 6 跳过** | 6 跳过为既有类 |
| `testDatabase` | **10 例 / 0 失败 / 0 错误 / 0 跳过** | 4 个 DB 集成类 |
| `:sql-visualizer-agent:test` | **58 例 / 0 失败 / 0 错误** | 本批未改 agent 子项目源码 |

**本轮零失败，无需三步证伪**（分片重平衡 / 剪贴板 / 陈旧产物三类假失败均未出现）。

## 五、判定卡

| 任务 | 判定 | 依据 |
|---|---|---|
| T-ST-01 脆弱前置 | **达成** | 两处「断言环境」改为用例自建前置；断言强度与文本逐字不变；先红后绿 + 受控替身实验；计划机制归因被实测证伪并更正 |
| T-ST-02a 复现 | **达成** | 受控条件下复现成功（A 与门禁逐字同形；B 同组成重跑 1896/7）；自然触发未复现，如实登记 |
| T-ST-02b 根因与修 | **达成（口径更正）** | 根因定位到生产代码 `SectionConfigurable.apply()` 取服务在 `runCatching` 之外；修法①落地；**「根治」口径被字节码级复核证伪，改为「已消除 `apply()` 穿出这一表现」** |
| T-ST-02b2 前置判据 | **达成（不宣称根治）** | 取服务前判 `isDisposed` 不发起失败创建；先红 139/1 → 后绿 139/0/0/0；真窗口反向断言证明判据口径成立、但**对 light 测试窗口不可达**、不改变门禁那 3 例结局 |
| T-ST-02c 观测手段 | **达成** | `AlreadyDisposedDiagnostics` 补片号/触发点/服务/线程/项目态后同类型重抛；11 处调用点改走它 |
| T-ST-02d 防回潮守卫 | **达成** | 新规则 +436 行仅新增，既有规则与豁免计数未动；先红（合成样本注入真实扫描面）后绿；两条已知漏报有意不收编并双向钉住 |
| T-ST-03 PS5-02 显式传参 | **不做（决策记录）** | 三条硬依据（确认阶段不持有产物 / 隐式前提去不掉 / 收益已全量拿到）；改为前提留档 + `SqlPreviewPanelRollbackParseShapeTest` 5 例钉住面板真实调用形状 |
| 计划自身更正 | **已处理** | 两处错误归因（T-ST-01 共享 Project 机制、T-ST-03 `:734` 落点归属）已在计划与 `变更记录` 内更正 |

**总判定：通过**——四项全部落地或按决策记录不做，两处计划错误归因已更正，独立复核的实质更正（「根治」口径）已采纳并落到交付口径与文档，全量门禁（`testShards` 5372/0/0/6 + `testDatabase` 10/0/0/0 + agent 58/0/0/0）全绿。**T-ST-02 明确不宣称根治、不作为门禁那 3 例的收口手段**（收口缺口见 §七-2）。**本批无真机面。**

## 六、真机走查（留人工）

**本批无真机面**——计划 §五 已明写：全部改动为测试基建与内部调用形态。逐项说明：

- T-ST-01：只改测试断言形态（`util/` 域），无生产行为变化。
- T-ST-02：生产侧只改 `SectionConfigurable.apply()` 的异常降级面与取服务前的跳过判据，happy path 逐行不变（正常项目仍取服务、仍调 `onAutoStartSettingEnabled()`、仍写回）；要真机复现 temporarily-disposed 窗口下的行为需人为制造项目关闭竞态，属不可稳定构造的场景。
- T-ST-02c / T-ST-02d：测试侧观测件与源码扫描守卫，零生产面。
- T-ST-03：判定不实施，只加 KDoc 前提与测试类，生产调用形状未变。

故本批不留人工走查项。

## 七、后续批次与决策记录

### 1. T-ST-03 决策记录：PS5-02 面板侧显式传参不做

- **决策=不做**，理由三条见 §一-4（改动面大于收益）。
- **前提已留档**（`registerRollbackSnapshotIfNeeded` KDoc）：①确认阶段确以同一文本走过 `detect`；②`SqlCommentStripper.strip` **幂等**——这条依赖此前无人写出、也无用例覆盖，现由 `SqlPreviewPanelRollbackParseShapeTest` 的 7 种注释形态逐条钉住。两条前提任一失效的后果**只是多一次解析**，不影响回滚判定正确性。
- **若日后要做**：出口是 `RollbackSqlGenerator.generateFromParsedStatements(cleaned, statements, pk)`；写集 = `execute/` 包两个共享 API（`ExecuteConfirmMessages.build` / `UnboundedWriteDetector.detect`）的签名，连带 `LogToSqlPanel.executeStatement` 与 MCP `CheckSqlTool` / `MapperCheckTool` 消费者。
- **未测项**：单槽**并发命中率未测**——确认弹窗虽模态但 EDT 继续泵事件，别的链以别的文本填槽即 miss（该次点击解析 2 次）；后果仅性能，miss 分支已被探针自证覆盖。

### 2. T-ST-02 的收口缺口（登记，本批未做）

要真正收口「窗口内首次创建订阅项目总线的服务」，需覆盖窗口内**其它未受守卫的调用点**——main 侧共五处（**5 个文件 / 6 个调用点**，`AgentManager` 占 `:929` 与 `:1158` 两行）：

| 文件 | 行 |
|---|---|
| `log/SqlConsoleBridgeFilterProvider.kt` | 27 |
| `log/LogStartupCapture.kt` | 26 |
| `log/agent/AgentManager.kt` | 929、1158 |
| `mcp/McpToolsetSupport.kt` | 203 |
| `ui/SqlPreviewService.kt` | 1999 |

（已受守卫的是 `config/SectionConfigurable.kt:94`。）另：**「谁让项目停在 temporarily-disposed 窗口」这一上游未深挖**——本批修法是让调用侧不被它打断 + 不在窗口内取服务，非消除该窗口。

### 3. 02b / 02b2 两轮「先红后绿」的因果性存疑（登记）

既然触发点本就可能在异步 / EDT 队列上，那两轮「同组成重跑 5362/0/0/6」的绿更可能是时序运气，而非「守卫挡住了那 3 例」。本轮未做分片重跑，不对该点下断言。**门禁那轮 5 例的完整 XML 已被后续绿跑覆盖**，逐案对账无法补做；已证实的只是**机制与触发点**（`SectionConfigurable.apply()` → `getService`），未证实门禁那轮每一例都由该路径触发（失败帧行号也有差异：门禁版本在用例体 43/65，复现轮实测在 `setUp:29`）。

### 4. 非目标（计划明列，本批未做）

- 不追求「分片顺序无关」这一更强性质——分片组成由历史时长权重贪心推导（**新增测试类即重排邻居**），属测试基建设计面，另议；本批只消除已暴露的具体顺序依赖。
- 不改生产逻辑（T-ST-03 未做，其余只动测试与守卫）；不动 K2J；不动发行元数据；不推送。
- `WrapperConverterProjectScopeTest` 的 EP 注册表转储属另一族既有假失败，本批未处理。
