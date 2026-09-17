---
name: pitfall-jdbc-execute-rows-semantics
description: JDBC execute() 返回语义与 getUpdateCount() 的 -1——execute() 返 true=首结果为 ResultSet（getUpdateCount() 恒 -1 无意义）、返 false 才是更新计数；条件写反会让每条 SELECT 上报「(-1 rows)」
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1818de3a-dbb9-4d6a-a3e7-39842ab1b0df
---

2026-09-16 用户实测现象：增强捕获列表里**每条 SELECT 尾部都挂 `(-1 rows)`**，用户问「这代表什么意思」。

根因是 JDBC 语义与条件写反：`Statement.execute()` **返回 true 表示首个结果是 ResultSet**（此时 `getUpdateCount()` 恒返回 -1——查询本就没有影响行数）、**返回 false 才表示首个结果是更新计数**（此时 `getUpdateCount()` 才是真实行数）。采集侧（agent `hook/EventBuilder.extractUpdateCount`）条件误写成 `Boolean.TRUE.equals(result)`，于是每条 SELECT 都把哨兵 -1 当行数上报。

修复口径（双端守卫）：条件改 `Boolean.FALSE` + 统一 `nonNegative()` 负值守卫——负值＝无有效计数，agent 不上报、IDE 侧 `formatUpdateCountFragment` 也 `if (u < 0) return null`（防旧版 agent 残留误报）。**0 是合法计数**（0 行被修改）须照常显示；batch 的 `SUCCESS_NO_INFO = -2` 沿用既有跳过语义。

**Why:** `-1` 在 JDBC 里是「没有更新计数」的哨兵值，不是「负一行」；把哨兵当数据展示，用户会以为插件算错或数据库异常。同类哨兵还有 batch 的 -2/-3。
**How to apply:** 任何从 JDBC 取计数的代码，先按 `execute()` 布尔语义分流再读 `getUpdateCount()`，负值统一转 null（不要「原值透传」）；测试须覆盖四态——ResultSet 结果不上报 / 更新计数上报 / 负值不上报 / 0 照常显示。本轮该方法是**完全没有测试**的（agent 侧补 `EventBuilderTest` 6 例才锁死）。

关联：[[project-m56-agent-bytecode]]（展示侧守卫在 IDE 的 `formatUpdateCountFragment`）。
