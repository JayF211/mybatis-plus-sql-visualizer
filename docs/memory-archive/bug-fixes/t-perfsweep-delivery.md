---
name: t-perfsweep-delivery
description: T-PerfSweep 已交付——性能分析 20260910-0345 的 dev 遗留四热点清扫（dev
  fb73825+0a855bc，2026-09-11，全量 3696/0/6 绿）：重放守卫惰性化/热路径 Regex/dedupeCounts 4096
  上限/CriteriaQuery 文件级门控；性能分析正文 M54 表在 dev 不成立已附补注
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e0e07d2a-d172-4423-9c52-10427eff7a48
---

《docs/性能分析/20260910-0345-IDEA性能影响-性能分析.md》（master 299607e 快照）复制进 dev 时**逐项核实**：dev 已独立落地 T-Perf-01~24 全系列（3737cdd~6a5dd5d + 487d8b 合并保留），正文「M54 十五项未落地」在 dev 完全不成立——分析文档是 master 视角，跨分支引用前必须按分支核实（已写进该文档 §九补注，含逐项证据表）。真实开放项仅四个，全部收编 T-PerfSweep（dev fb73825，2026-09-11，全量 3696/0/6 绿，与 [[t-perfbatch-plan]] 同会话）：

- **F1 重放守卫**：dev 经 T-SyncK2JMaster14 移植了 master 的 ConsoleReplayGuard，热点同样成立。decide 行 0 参数改惰性提供者（只有混合态分支求值）、反射 Method 挂载时一次解析、行 0 按 stamp 缓存；`ConsoleReplayGuardTest` 有「流式/纯重放零调用、混合态恰一次」惰性断言。
- **F2 Regex**：JdbcTemplateLogParser named 参数切分正则提常量（T-Perf-23 漏点）、ExecutionTimeParser 自定义 timePrefix 正则 CHM 缓存。
- **F3 dedupeCounts**：换插入序 LinkedHashMap + 4096 上限淘汰最旧。**计数键只在丢弃时产生**（入队条目不产生键）——测试首版把入队当计数键写错，构造须「每键入队+重复丢弃」。
- **F4 CriteriaQuery 门控**：criteria 构建方法名无特征，T-Perf-02 名字预筛不可复制；用文件级 `"riteria"` 子串 + modificationStamp 单条目缓存（零泄漏），无 criteria 文件整体跳过 locate 全块扫描。

**IJPG 2.18.1 并行沙盒注入点**：`sandboxContainer` 属性在顶层 `intellijPlatform {}` 扩展（instrumentCode 所在块，javap 实证 IntelliJPlatformExtension.getSandboxContainer()）；放进 dependencies 块（:57 intellijPlatform）报 Unresolved reference。历史写法见 55c6c6f。

**Why**: master 视角的性能分析不能直接当 dev 的待办清单——dev 与 master 的 M54 落地状态完全相反。
**How to apply**: 引用跨分支分析文档先做分支适用性核实；重放守卫等 master 新代码经 sync 移植后要重扫热点是否随之而来。相关 [[t-perfbatch-plan]] [[project-dev-branch-state]]
