---
name: t-refreshdbsteal-delivery
description: T-RefreshDbSteal 已交付——多弹窗僵尸 rehost 监听抢共享控制台三症状根治（master
  707e76a，3188/0/6 绿，真机复验留人工）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33967f87-fcc9-48d6-aedd-706706443e28
---

T-RefreshDbSteal 于 2026-09-09 交付（master 707e76a）。三症状（预览点刷新后 DBN 工具栏消失 / 左参数不再同步右侧 SQL / 执行 SQL 跑到 IDEA 控制台）同根：`SqlPreviewService.showResult` 每次 new 一个 MODELESS `SqlPreviewDialog` 从不关闭，旧弹窗面板的 rehost HierarchyListener 残活，把插件级单例共享控制台抢回废弃面板（真机日志 6 连抢、`since=116324ms` 匹配旧面板 lastRehostAt 实锤）。

四组修复：F2 弹窗单例化（showResult 关旧开新，`doDispose()` 包装平台 protected 的 dispose，项目级 ConcurrentHashMap+WeakReference 登记表）；F3 监听生命周期（监听引用入字段，detachConsole/dispose 显式移除，去抖 skip 后单发 800ms Swing Timer 延迟复查幂等重挂）；F4 所有权卫队（`consoleAttachedToMe`，showSql/executeSql/createEditor 三处失联先 recoverConsole，执行侧恢复未落地落全链兜底）；F1 埋点（createEditor/releaseEditor 带栈 + showSql 三分支标签 + refreshView 4 态快照）。

落地时与计划的三处偏差：去抖复查必须用 Timer 不能用 invokeLater（无延迟会被自身 since 复查永久拦下）；`DialogWrapper.dispose` 是 protected 须同类包装；测试面板收尾必须 `clear()` 否则 EditorImpl "hasn't been released"（RefreshDbStealTest 两个用例各踩一次）。

测试 8 用例（RefreshDbStealTest），全量 3188/0/6 绿。**Why:** 真机复验（计划 §4.3 五条）与 createEditor 实际调用栈定性仍待用户刷新复现后由 F1 埋点回填；沙盒日志是错误 session 教训见 [[t-refreshdbsteal-plan]]，`.http` 占控制台陷阱见 [[branch-dev-to-master-merge-probe]]。**How to apply:** 后续动共享控制台/预览弹窗代码时先读计划 §七 实施记录；改 rehost 去抖逻辑勿回退成 invokeLater。
