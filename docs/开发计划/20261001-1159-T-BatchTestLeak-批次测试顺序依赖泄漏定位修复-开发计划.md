# T-BatchTestLeak 批次测试顺序依赖隔离泄漏定位与修复 — 开发计划

- **日期**：2026-10-01
- **编号**：O-93（编号台账实测未占用后正式占号；用户令 20261001「待立项 立项」）
- **动机**（T-TestPerf11 §计划外发现 B 原锚点，`docs/开发计划/20261001-0253-T-TestPerf11-全量测试墙钟九次压缩-开发计划.md:116`）：R3 轮 `SqlPreviewServiceBatchTest` 5 例 `ON DUPLICATE KEY UPDATE`/反引号↔双引号引用 ComparisonFailure；**同码同测 R1b/R2 全绿、定向隔离复跑 29s 全绿**——同 JVM 内某前序测试类污染方言/引用全局态且未还原；权重画像变化→贪心装箱顺序变化→间歇暴露。**危害＝全量门禁失败集合顺序敏感（3~8 浮动），侵蚀门禁权威性**（本轮判据被迫按「⊆ 3 既有红 + 5 泄漏例」执行）。
- **成本**：M（定位为主，修复随定位结果）
- **状态**：已立项待实施（用户令 20261001）；TestPerf11 轮 grep 未命中显式全局写点，疑经配置/缓存单例

---

## 一、已知事实（锚点实测，实施前重锚）

| # | 事实 | 出处 |
|---|---|---|
| ① | 泄漏形态=方言/引用判定被污染（`ON DUPLICATE KEY UPDATE` upsert 形态与反引号↔双引号标识符引用是 MySQL↔PG 族方言分叉点） | TestPerf11 计划 `:116` |
| ② | 同码同测在 R1b/R2 两轮全绿；定向隔离（单独跑该类）29s 全绿——非代码缺陷，是**同 JVM 前序类遗留全局态** | 同上 |
| ③ | 触发条件=权重画像变化→贪心装箱顺序变化（TestPerf11 S1 刷新 548→559 类后暴露），即**任何改变类顺序的因素都可能复现/隐藏它** | 同上 |
| ④ | 本轮 grep 未命中显式全局写点——疑经配置/缓存单例（静态初始化、服务容器、Settings 快照、dialect 注册表缓存等） | 同上 |

## 二、方案（三子任务，定位先行）

| 子任务 | 内容 | 写集 |
|---|---|---|
| **T-1 污染源定位** | ①**确定性重放**：按全量分片的类执行顺序，二分前驱类集（逐步扩大「先跑 N 个前驱类→再跑 SqlPreviewServiceBatchTest」）直到最小复现集；②**全局态候选盘点**：枚举该测试触达链上的静态可变状态（方言注册/缓存、dedupeKey 相关全局、Settings 快照、服务容器单例——grep+读码清单化，逐个与最小复现集对账） | 测试代码只读+新增定位用探针（用后即删） |
| **T-2 修复** | 按定位结果三选一：a) 前序类补隔离还原（tearDown 还原全局态）；b) 被测类改无序依赖（不读可变全局/显式注入）；c) 框架级隔离（@TestInstance/新 JVM 分桶——**最后手段**，动分片基建须主控确认）。**禁「调测试顺序」式遮掩** | `src/test` 为主；若涉 src/main 全局态治理另行报批 |
| **T-3 守卫** | 防回归：该类纳入确定性顺序探针（CI 轮固定先跑最小复现前驱集再跑本类，断言全绿），或修复形态本身使顺序无关 | 测试 |

## 三、验收

1. **反向验证**：定位出的污染源还原（摘隔离/还原）→ 最小复现序列先红；修复后绿；
2. 修复后：最小复现序列 + 定向隔离 + **全量两轮**（换装箱序验证：临时微调权重复排一版跑一轮）全绿——失败集合判据从「⊆ 既有红+泄漏例」收敛回**确定性失败集合**；
3. 收口全量门禁（testShards 5 片+agent+verifyGate 三目标）。

## 四、决策点

| 编号 | 决策点 | 推荐 |
|---|---|---|
| D1 | 修复策略 | **A=按定位结果默认前序类补还原**（最小侵入）；B=被测类去全局化；C=框架级分桶隔离（最后手段，动基建须主控确认） |
| D2 | 若定位出 src/main 的真全局态缺陷（如缓存未按 ClassLoader 键隔离——F30 同族） | **A=随本任务一并修**（带反向验证）；B=拆独立任务 |

## 五、待验证前提（实施第一步即核销）

1. 「污染源为全局/缓存单例」系本轮 grep 未命中的**猜测**——实施 T-1 以最小复现集实证（可能被证伪为其他形态，如线程局部/文件缓存）；
2. 5 泄漏例集合在装箱序变化后**可稳定复现**——若复现不了，先冻结一份已知触发顺序的类清单再定位；
3. TestPerf11 轮「R3 轮 5 例」的既有红×3（TopRowLayout/XmlPreviewPanel/EnumReachability）已由批次 9 门禁修复——本任务的失败集合基线以**当前 dev tip 重测**为准，勿沿用旧清单。

## 六、实施记录

- **20261001 实施完成**（工作树 `mybatis-plus-sql-visualizer-o93leak`，分支 `t-o93leak`，基线 `a044b77`）。
- **T-1 定位结论**：污染源＝`PaginationSimTest`（`src/test/kotlin/cn/song/mybatisplus/visualizer/ui/PaginationSimTest.kt`）——方言矩阵用例 `testDialectMatrixLimitShapeForPaginatedMapperSelect` 逐个 `setManualDialect("MySQL"/"PostgreSQL"/"Oracle")`（写全局 `PluginSettings.state.dialect`），tearDown 只还原 `dialectAuto`/`paginationSimEnabled`，**残值 "Oracle" 泄漏**。放大器＝`DialectRegistry.effective` 旧实现（`Dialect.kt:843`）：project=null（psi 渲染链 `BatchOperationConverter:166 → psi currentDialect`）+ auto=true 时仍 `return byName(manualName)`，手动残值劫持 auto 模式 → Oracle 方言下 upsert 降级纯 INSERT + 反引号变双引号，恰好 BatchTest 5 例。
- **最小复现集**：单前驱类 `{PaginationSimTest}` + `SqlPreviewServiceBatchTest` 同 JVM（`--tests` 组合实测：修复前 42 例中恰好 5 例 ComparisonFailure，失败集合与全量门禁泄漏例一致；修复一后 42 例全绿）。
- **待验证前提核销**：①「全局/缓存单例猜测」证实为「全局 PluginSettings 残值 + effective null-project 分支」组合，非缓存/线程局部；②复现稳定（同组合两轮同失败集合）；③基线以 dev tip 重测。
- **T-2 修复（D1=A + D2=A）**：
  - 修复一（commit `c91e39e`）：`PaginationSimTest.tearDown` 补还原 `state.dialect = DEFAULT_DIALECT`；
  - 修复二（src/main，D2=A 标红提醒主控）：`DialectRegistry.effective` project=null 且 auto=true 时改回退 `MySqlDialect`（不再取手动残值；auto=false 手动语义不变）。旧 KDoc 标注「有意为之」系放大器根源——生产用户「手动切回 auto」后 psi 批量渲染（gutter）仍吃残值方言，与面板预览（project 路径走推断）分叉，属真实语义缺陷，已随任务修。
- **T-3 守卫**：新增 `DialectStaleManualLeakGuardTest`（同 JVM 内显式写残值→断言 effective 回 MySQL / 手动模式对照不变 / 批量 upsert 预览免疫污染）。反向验证：修复二落地前探针 2 例红（effective 返回 OracleDialect + upsert 被污染渲染）、对照组绿；修复后全绿。定向回归（探针+BatchTest+PaginationSim+DialectProbeCache+DialectInference+ConfigTest+ProjectSettingsTest+SettingsRealityGuard+JpaCrud）全绿。
- **偏离**：分片基建未动（未走 C 案）；验收 3 的收口全量门禁由主控统一安排（本任务只跑定向）。
