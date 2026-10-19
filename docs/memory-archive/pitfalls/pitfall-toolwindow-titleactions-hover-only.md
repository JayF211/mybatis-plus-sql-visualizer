---
name: pitfall-toolwindow-titleactions-hover-only
description: ToolWindow.setTitleActions 挂的动作在新 UI
  下悬停才渲染——主操作入口勿挂标题栏（T-CaptureBack 实录，20260918）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5a1ffd06-bdcf-4fdd-bdc8-86db0749549a
---

# 工具窗标题栏动作（setTitleActions）新 UI 悬停才可见

T-CaptureBack（20260918，提交 0239251）实录：用户报「SQL 日志面板捕捉按钮不见了」。两轮截图实证按钮**存在**但平时不渲染——只有鼠标悬停工具窗后标题栏右上角才浮现。

**Why:** T-UiNative T-UN-11（提交 45a9b16，2026-09-16）为退役「uiTraverser+反射 getAction+setIcon」刷新 hack，把捕捉开关从面板内工具栏迁到 `ToolWindow.setTitleActions`。迁移本身解决了刷新机制问题，但新 UI 下标题栏动作需悬停/激活才可见，主操作入口在用户视角「消失」，且与空态文案「点击『捕捉』」（指向面板工具栏心智）割裂。

**How to apply:**
- `ToolWindow.setTitleActions` 挂的动作在新 UI 是「悬停才渲染」形态——**主操作/主入口按钮不要挂标题栏**，放面板内工具栏；标题栏只适合次要或可接受隐藏形态的动作。
- 回迁正确形态（T-CB-01）：`toolbarGroup` 首位前置 `captureToggleAction` + 工厂删 `setTitleActions`（单一入口，避免标题栏残留重复按钮）；状态刷新沿用既有 `capturingUiListener → updateActionsImmediately` 展示链，**不退回反射 hack**（BannedApiSourceScanTest 守卫钦定 `updateActionsImmediately` 正典，方向不可反转）。
- 顺带教训：迁移动作引入硬编码中文提示（`CaptureAction.update` 的 waiting 文案）漏接既有 bundle 键 `log.panel.capture.waiting.desc`——UI 文案一律走 bundle。
- 交付验证：定向四类测试 22 例 + 全量 testShards 4394/0/0/6 绿；分支 t-captureback rebase 后 ff 合 master。
