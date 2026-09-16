# T-DdlPostInvalidate DDL 执行后兜底失效 — 开发计划

- 日期：2026-09-16 03:10
- 任务编号：T-DdlPostInvalidate（第五轮新建议 **O-17**，P2 小件，半天量级）
- 来源：第五轮报告 §7 DBX #15 上游加固回灌；**口径经 dev 锚点核实修正**——报告原文写「补执行前预判」，实测 T-DdlCacheInvalidate 的 mayChangeMetadata 预判**已在 executeOnConsole 入口在位**（调用点在主链与 runWhenSmart 之前），真实缺口是「执行后兜底」：预判漏判（存储过程/触发器隐式 DDL、解析失败的残缺语句碰巧执行成功）或执行中途才发生结构变化时，缓存带着旧结构活到下一次预判命中
- 性质：缓存一致性小补丁（幂等，重复失效无害）
- 实施分支：自 master 拉任务分支 t-ddl-post-invalidate
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

DBX 上游把 `sql_may_change_object_metadata` 从「执行后失效」加固为「执行前主动判定 + 执行后失效」双段（origin/main query.rs:2873/4177），理由注释点破「失败的 DDL 可能已经隐式提交」（Oracle）。本插件现状只有前段：入口处 mayChangeMetadata 命中即失效（`DataSourceSelector.kt:2242`），此后无论执行成败不再失效。补上后段的语义是：任何执行收尾（**成功与失败都算**）都兜底失效一次——成本是一次幂等清缓存，收益是把「隐式提交/过程内 DDL/预判漏判」三类窗口全部关死。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 执行前预判（已在位，勿重做） | executeOnConsole 入口调 invalidateAfterExecution（内部先 mayChangeMetadata 命中才失效），runCatching 包裹不吞执行结果 | `execute/DataSourceSelector.kt:2242-2243` |
| 失效动作 | register 表唯一登记 DatabaseSchemaResolver.metaCache → invalidateAll()（synchronized clear） | `execute/MetadataInvalidator.kt:54-59`、`ui/highlight/DatabaseSchemaResolver.kt:95-97` |
| 执行收尾点 | 错误上报点（refreshResultsComponent 每拍 / 40 拍超时收口）与成功呈现点均已知 | `execute/DataSourceSelector.kt:783-794,2703-2717,2772-2790` |
| 守卫注释 | 「新增缓存漏登记」防漏登记注释在位 | `ui/highlight/DatabaseSchemaResolver.kt:87` |

## 三、方案

**T-DP-01（执行后兜底）**：在执行监控收尾处（成功呈现与失败/超时收口两点）调 `MetadataInvalidator.invalidateAfterExecution(sql)`——沿用既有函数（内部预判命中才动作，天然幂等）；两点均 runCatching 包裹不吞结果；**失败路径也要调**（对齐「失败也要失效」口径）。

**T-DP-02（测试 + 文档）**：表驱动——预判命中时入口与收尾双调不重复动作（幂等断言）、失败路径同样失效、register 表守卫；CHANGELOG [Unreleased] Fixed 1 条（缓存一致性口径）、第五轮报告 O-17 状态回填（含口径修正说明）。

## 四、验收标准

1. 同一语句执行链全程失效次数有界（入口 1 + 收尾 1），无重复动作副作用。
2. 执行失败/超时路径缓存同样被失效（防回归断言）。
3. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 控制台跑一条 `CALL`/过程类语句（内部改表结构）后立即跑 EXPLAIN：结构为新结构（旧结构缓存已被兜底清掉）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 收尾点多处上报重复调 | 错误上报点有轮询重试形态 | 失效幂等 + 每拍去重基线（lastReportedError 先例）同款防抖：仅状态迁移沿触发 |
| 收尾点不在 EDT / 线程归位 | invalidateAll 是 synchronized map clear，线程安全 | 保持 runCatching + 不碰 UI；不新增 EDT 依赖 |

## 七、实施顺序

```
T-DP-01（收尾两点接线）→ T-DP-02（测试+文档）
```

## 八、实施记录（实施后回填）

**2026-09-17 实施完毕（提交 4167a69，dev 分支直接交付）**

- **T-DP-01**：startResultTabRetry 三收尾点（早检错误上报/结果 Tab 成功呈现/40 拍超时收口）调 runPostExecutionInvalidate——复用 MetadataInvalidator.invalidateAfterExecution（内部预判命中才动作+动作幂等），入口+收尾双调全程失效次数有界（1+1）；成功与失败/超时都失效（DBX：失败的 DDL 可能已隐式提交），把「过程内 DDL/预判漏判/中途结构变化」三类窗口关死；sqlText 自 executeOnConsole 入口捕获（与入口失效钩子同取值口径）穿参 afterSubmitted。
- **T-DP-02**：幂等断言测试 1 例（入口+收尾双调动作计数精确、非 DDL 零动作）；CHANGELOG Fixed（缓存一致性口径）与变更记录行随本轮文档批次同步；第五轮报告 O-17 状态回填（含口径修正说明）留主会话。
- **真机走查留人工**（§五）：过程类语句改表结构后立即 EXPLAIN 验证新结构。
