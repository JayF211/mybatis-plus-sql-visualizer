---
name: pitfall-test-fixture-shape-mismatch
description: 测试夹具/替身形态与真机形态不一致致假绿——同一能力的两条来源路径（结构化字段 vs 文本解析）只覆盖一条、替身类可访问性优于真机类，单测全绿而真机首次使用即失效
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1818de3a-dbb9-4d6a-a3e7-39842ab1b0df
---

2026-09-16 M56 增强捕获真机排障会话内**同一形态连中三例**（都是「单测绿、真机废」）：

1. **结构化字段 vs 文本解析**（耗时排序）：面板排序按钮可见性与排序取值长期只看「从日志文本里解析出的耗时」（p6spy `ts|ms|statement|SQL`、druid 行）。既有排序测试**正好用 p6spy 文本构造条目**，所以从未失败；而增强捕获通道的耗时是**结构化 `executionTimeMs`**、SQL 文本内根本没有 → 真机上按钮恒隐藏、排序取值全空。修复=统一走 `durationOf(entry) = executionTimeMs ?: LogDurationParser.parse(it)`，并补「仅结构化耗时」用例锁死。
2. **替身类可访问性优于真机类**（pid 解析）：测试用可访问的匿名 `Process` 子类 → `getMethod("pid").invoke()` 正常；真机是 JDK package-private 的 `ProcessImpl` → 抛 `IllegalAccessException`（详见 [[pitfall-reflect-jdk-internal-class-access]]）。
3. **协议只有单端格式断言**（事件 JSON）：agent 写出侧无格式守卫 → `{,"sql":...}` 非法 JSON 真机全量丢弃（详见 [[pitfall-dual-end-protocol-roundtrip-gap]]）。

**Why:** 单测通过被当成「能力已验证」，于是排障方向被引向别处（本轮真机排障为此多花数轮：先怀疑开关、再怀疑挂载、最后才到 JSON）。夹具形态是隐式假设——没人写下来，也没人检查它与真机的差距。

**How to apply:**
- 写测试构造输入时先问「真机上这份数据从哪来、什么形态」：同一语义有**两条来源**（结构化字段 / 文本解析、直采 / 兜底解析）时，两条都要有用例；只覆盖一条即等于没覆盖真机路径。
- 替身（fake/stub）必须与真机同**可访问性等级**：真机实现是 package-private/JDK 内部类时，替身不要用可访问的匿名子类顶上（会掩盖反射访问检查）。
- 跨越进程/序列化边界的产出，必须有**格式守卫或往返用例**（不是只测「字段映射对不对」）。
- 排障信号：某能力「单测从未失败、真机第一次用就废」，优先怀疑夹具形态而非业务逻辑。

关联：[[pitfall-reflect-jdk-internal-class-access]]、[[pitfall-dual-end-protocol-roundtrip-gap]]、[[project-m56-agent-bytecode]]。
