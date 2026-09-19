---
name: t-inparamrec
description: T-InParamRec IN 字面量 SQL 预览参数未识别——已交付 master
  d98e4e9（20260916），三预览入口收编 toPreviewResult 统一识别通道
metadata:
  node_type: memory
  type: project
  originSessionId: sess_654e670d-8553-4dd4-93fd-5b743805c7d3
---

**T-InParamRec 已交付 master d98e4e9（2026-09-16，全量 test + agent 门禁绿，分支/工作树已清理）**。

用户反馈「in 的参数在 sql 日志中没有正常识别出参数」附实录日志与预览截图。**根因不是解析并发错挂**（与 [[project-m56-agent-bytecode]] 系列的 T-LogParseConc/T-HibConc 无关）：实录 SQL 的值全部由上游代码内联进文本，零 `?` 占位符、零绑定行——属「字面量 SQL」。项目本有 `LiteralParamRecovery` 字面量恢复方案（默认开），但三个预览入口中**源码跳转反查入口 `LogEntryPreviewOpener`（M37.1 独立实现，EntrySourceMatcher.kt）漏接**，识别失败回退后左栏「无未解析参数」、值不可编辑。

**修复**：`LogEntryPreviewOpener` 删独立识别/回退双分支，收编 `entry.toPreviewResult(formatSql, literalRecovery)` 统一识别通道（日志 tab / 历史 tab / 跳转反查三口同源）。守卫 `InLiteralRecoveryRegressionTest` 4 例（用户实录多行 SQL 回归）。

**Why**: 多入口并行发展的功能，扩展识别通道时只收编了部分入口——「同一能力多入口」的收编清单须 git grep 全部调用点核对。

**How to apply**: 动 `LogSqlEntry` → 预览链路时，三个入口统一走 `toPreviewResult`，勿再长独立分支。定案：IN 列表内空串 `''` 保持字面量不识别（防空值输入渲染 `?` 无法往返）。实录中 `Executing prepared SQL update` 头行无 `[SQL]` 文本不产生条目（既有语义）。
