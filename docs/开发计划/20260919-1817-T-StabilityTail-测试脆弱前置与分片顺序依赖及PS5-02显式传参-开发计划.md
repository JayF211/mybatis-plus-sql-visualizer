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
| T-ST-01 | （实施后填） | 先红后绿读数须回填 |
| T-ST-02a | （实施后填） | 复现结论（成功 / 不可复现）须回填 |
| T-ST-02b/c | （实施后填） | 根因与修法，或观测手段与「不宣称根治」口径须回填 |
| T-ST-02d | （实施后填） | 守卫规则描述与误报边界须回填 |
| T-ST-03 | （实施后填） | 做 / 不做（可选件）与理由须回填 |
