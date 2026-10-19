---
name: t-refreshdbsteal-plan
description: 预览刷新后内嵌 Database 控制台丢失三症状根因（2026-09-09
  真机日志+字节码双实锤）与五路修复方案——多弹窗僵尸监听抢占共享控制台 + 静默回退黑洞（计划定稿未实施）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33967f87-fcc9-48d6-aedd-706706443e28
---

**T-RefreshDbSteal（2026-09-09 立项，计划定稿未实施）**：用户真机（IDE 2026.2.1 + 插件 v2026.9.8）反馈预览弹窗点「刷新」后：内嵌 Database 控制台（DBN 工具栏）消失变行号回退编辑器、左侧参数不再同步、执行 SQL 落 Database 工具窗。计划文件 `docs/开发计划/20260909-1530-T-RefreshDbSteal-预览刷新后内嵌Database丢失-开发计划.md`。

**根因定性（三症状一家族）**：`SqlPreviewService.showResult` 每次新建 modeless 弹窗（用户 14:50→14:53 连开 6 个不关）→ 每个旧弹窗留**永不移除**的 rehost HierarchyListener + `pendingOnConsole` 静态单槽，三方抢全插件唯一的共享 DBN 控制台 Swing 组件。真机日志实锤：14:53:27 新弹窗 embed 时旧弹窗监听 6 连抢（`re-host since=116324ms`×6，since 精确对上旧面板 lastRehostAt）。刷新按钮是唯一对共享控制台文档做 WriteCommandAction 的常规按钮（触发 SqlDialectPusher dumb dance 制造抢占窗口）且自身零日志。

**静默黑洞清单**（任一失手面板无日志落坏态）：rehost 去抖 800ms 内 skip 无日志无重试（SqlPreviewPanel.kt:617）；`swapResultIntoPersistentConsole ?: return false`；`reuseSharedConsole` 六个前置 `?: return false`；`prepareDefaultConsole` 两个早退；`showSql→createEditor` 回退零日志。坏态截图鉴定：行号+拼写波浪线 = createEditor 自研编辑器独有（DBN 控制台编辑器无行号）；createEditor 精确调用栈现有日志不可考，埋点后销项。

**五路修复**：F1 埋点（createEditor 带栈、showSql 三分支、refreshView 入口、去抖 skip）→ F3 监听生命周期（引用保存 + detachConsole/dispose 移除 + skip 后单次延迟复查）→ F4 面板所有权卫队 `consoleHost.isAncestorOf(embeddedConsole)`（showSql/executeSql/createEditor 三处，失联先 recoverConsole 重嵌）→ F2 弹窗单例化关旧开新（治本灭僵尸军团）→ F5 与 T-ExecCopyFix 合流。

**版本核实法**：真机部署 jar 反编译（javap 输出含 NUL 被 grep 判二进制，须 `grep -a`）确认 refreshView 字节码与 2b9f7c8 一致，排除版本漂移。用户真机日志在 `%LOCALAPPDATA%/JetBrains/IntelliJIdea2026.2/log/`，**持续轮转**（10MB/卷，分析窗口会被轮转改名——先 stat 各卷覆盖时间窗再 grep）。executeSql 入口日志无条件打，真机「执行进 IDEA 控制台」时段零 executeSql 日志 → 该执行不是插件按钮发起的（或早于截图时段）。

相关：[[t-dscover-t-clickpreview05-delivery]]（executeAfterPrepare 门控/silent 分流）、[[t-previewflash-plan]]（同一工作树在途改动 + dumb dance 实证）、[[t-execcopyfix-parallel-plan]]（并行会话同族计划）。
