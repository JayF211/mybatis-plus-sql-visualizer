---
name: t-autoshowmaster-delivery
description: 发现SQL自动打开日志窗口 master 失效修复（master 3ff30bb，3138 绿）——根因 F-44/M37.3
  分支断层，wm.ex 监听器包名陷阱
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5361d041-6f37-4c2c-9d97-515b95c961df
---

T-AutoShowMaster 已交付（2026-09-09，master `3ff30bb`，全量 3138/0/6 绿 = 基线 3131+7）。

**根因（分支断层，非代码缺陷）**：「发现 SQL 时自动打开日志窗口」`logAutoOpenOnCapture` 在 master 上写穿读断——F-44（1c8bfe6）删独立日志窗口时把自动打开实现一并删了，设置项/勾选框/SqlLogManager.kt 孤儿 KDoc 残留，生产代码零读取点。复活实现在 dev：M37.3（f7f17261，60s 防抖+show 不抢焦点+默认值翻 true）+ T-AutoShowSuppress（19bf2571，用户手动关窗本会话抑制+设置重启用沿解除）。master▶dev 单向合并从未回流，`git branch --contains` 实锤。

**修复 = 双提交自 dev 全量移植**：无条件 buffer 监听（面板未创建也触发）+ ToolWindowManagerListener 可见→不可见沿抑制 + 默认值 true。真机 runIde 验证留人工。

**移植陷阱（已入全局 pitfall-toolwindow-listener-ex-package）**：`ToolWindowManagerListener` 必须用 `com.intellij.openapi.wm.ex` 包——非 ex 包不在本机裁剪发行版（243 平台 599 jar zipfile 扫描 0 命中，ex 包在 lib/app-client.jar）。分支间移植 import 须逐行照抄源分支。

**真机复测（2026-09-09 11:24，沙盒 idea.log 实证）**：用户按「开设置→关窗→启动项目」复测仍不弹——根因**不是**代码缺陷，是 T-AutoShowSuppress 的会话抑制在起效。**用户裁定：项目启动算重新表达诉求**——T-AutoShowRecover A 项已实施（master 78cc7c2，与 B 项 stopOnToolWindowClose 开关转正恢复同笔提交，3153/0/6 绿）：onProjectProcessStarted 清 logAutoCaptureSuppressed 处同步清 autoShowSuppressedByUserClose；autoStart 关闭时短路不动抑制；+2 用例（testProjectProcessStartClearsSuppression / SkippedWhenAutoStartOff）。次生发现（同一会话日志）：demo 项目 Run 全程**零 SQL 入队**——主控制台位被 `.http` 请求控制台占用（HttpSingleResponseConsole），ConsoleViewImpl 挂 filter 后 20s 无 SQL 行被移除；**复测须走真正跑 Mapper 的入口**（测试方法/会执行 SQL 的接口），别用 .http 当触发器。

**verifyPlugin 全量三版本复验（2026-09-09 12:22，78cc7c2 之后）**：BUILD SUCCESSFUL（243/253/262 三平台致命节全零——Incompatible/Internal API usages、Missing dependencies 均无）。本轮恢复的监听器/工厂代码在 262 报告中零新增违规（按类名扫 report.html 核对）。遗留不阻断项与 T-Wia262 基线完全一致：每平台 4 处 scheduled-for-removal（SlowOperations.allowSlowOperations(String)×2 / StdLanguages.JAVA / DbPsiFacade.findElement）+ deprecated/experimental 若干（ReadAction.compute(ThrowableComputable) 族、RestartDialogImpl、WriteIntentReadAction）——这些是既有技术债非本轮引入，市场发布不拒。市场发布路径畅通，2026.9.8 可发布。

关联：[[project-dev-branch-state]]、[[runide-demo-verify-workflow]]、[[branch-dev-to-master-merge-probe]]
