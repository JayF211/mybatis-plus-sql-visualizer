---
name: project-exists-bugfix
description: 用户实测 SQL 预览缺 exists 条件——根因是 WrapperLocator 链终结点误判（exists
  条件方法与链终结点双语义撞名）；2026-10-07 由 T-ExistsChainTerminal 修复（71e54b4）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_118762b3-2149-41cd-a759-80202715ee25
---

**状态勘正（2026-10-07）**：本档此前记为「修复方案已出待用户确认实施」，但长期未实施——**现已修复**，
提交 `71e54b4`（分支 `t-exists-chain-terminal`），计划
`docs/开发计划/20261007-1639-T-ExistsChainTerminal-工厂链带参exists误判为终结点-开发计划.md`。
落地即本文档 §修复方案 的原文方案（按实参个数判），并抽为单一来源 `isExistsConditionCall`
供终结点筛选与 `initializerIsWrapperChainWithoutTerminal` 共用；金样矩阵补 S91~S95，
反向验证（守卫改恒 false → 96 例 4 失败、红点精确落四个新例）在案。
**新证据**：`var w = Wrappers.<T>lambdaQuery()...exists(sql)` 声明形态同样丢条件（此前被同一名单拒为
wrapper 变量）；`exists` 的 API 归属＝core 的 `Join` 接口 `exists(String, Object...)`（零值变参即单参）与
`exists(boolean, String, Object...)`（MPJ 自有 Join 同款），**无参** `exists()` 只在 extension 的 `ChainQuery`
（链终结点）——首轮只 javap `AbstractWrapper` 自身声明漏看继承，一度误判单参形态不存在，已勘正。
Kotlin 侧 `KtWrapperDetector` 同一名单问题按 D94=A 登记不现修。

2026-08-14 用户实测缺陷（M25 后、632 测试基线）：`taskRecordService.page(page, Wrappers.<T>lambdaQuery().like(...).exists(flag, "SELECT ... {0}", v).eq(...).orderByDesc(...))` 形态的预览 SQL 缺 `EXISTS (...)` 条件。用户安装的旧构建表现：纯 SELECT 缺条件。

**根因**（非解析器——`exists(condition, sql, v)` 条件解析 T-M15-03 早已支持，字符串拼接模板 `"a"+"b"+"c"` 经 ExpressionEvaluator.evaluatePolyadic 可正常求值）：`WrapperLocator.kt:452` `buildChainFromFactory` 的链式终结点检测——`exists` 因 Q14-③（`service.lambdaQuery()...exists()` 无参终结点）被列入 chainTerminalMethodNames；当 wrapper 是裸工厂链直接作 service 方法实参（链内无真终结点）时，中间的带参 `.exists(condition, sql, v)` 被误判为终结点 → 调用被剔除（条件丢失）+ 整链误标 existsConsumed（当前构建行为：整条 SQL 被包成 `SELECT EXISTS (...)`，已用临时复现测试确认输出）。

**现有测试漏网原因**：Q14-① `testExistsSqlSubqueryCondition` 的 fixture 链尾有 `.page(new Page<>(...))`（真终结点，`lastOrNull` 先命中 page），中间的 exists 条件得以保留。

**修复方案**（已交付开发计划，待用户确认后实施）：终结点判定加守卫——`exists` 仅当无实参（`argumentList.expressions.isEmpty()`，即 Q14-③ 终结点形态）才当终结点剔除；带参形态保留在链中作条件。其余终结点（one/list/count/page/update/remove 等）无同名条件方法不受影响；Q14-② `service.exists(wrapper)` 走外层消费方法判定不受影响。补回归测试（用户真实裸链实参形态）+ 文档三处同步 + 全量验证。

**Why:** exists 是 MP 官方 API 中「条件方法（带参）与链终结点（无参）同名」的罕见双语义撞名，方法名集合驱动的分派极易误判，且现有测试形态恰好避开了该场景。
**How to apply:** 修复后验证：Q14-① 裸链实参新用例须输出普通 SELECT 且含 `AND EXISTS (...)`、Q14-③ 无参终结点 SELECT EXISTS 包裹不回退、Q14-② 不受影响。同类风险：方法名集合（终结点/消费方法/条件方法）增改时核对双语义撞名，与 [[project-registries]] 的 selectAll 撞名（链首守卫）同属一类坑；用户 IDE 插件版本滞后于仓库 HEAD（其预览表现为旧构建行为），见 [[user-ide-environment]]。
