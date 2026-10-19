---
name: project-sql-capture-no-project-required
description: T-13 SQL 监听手动启动无需等待项目运行——Option A armed 态 + manual/auto 区分 +
  设置优先（2026-08-20，多轮迭代修复完成）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_15c7f18d-3ce4-4fcb-9e13-b7a17c341f44
---

**T-13 SQL Capture No Project Required** 已实施并提交（e63d0db + 91e6939，2026-08-20）。最终采用 **Option A armed 态**方案。

## 核心设计

点击「捕捉」后**立即进入 capturing 态**（按钮显示 ⏸），无需等待控制台。桥挂载 + 轮询启动，控制台出现时自动附着 filter 开始捕 SQL。

## 状态机（3 核心标志）

| 标志 | 含义 | 按钮 |
|---|---|---|
| `capturing` | 是否开启监听（armed 或捕捉中） | true=⏸ / false=▶ |
| `isWaitingForConsole` | 是否等待控制台出现 | tooltip 提示 |
| `userArmed` | 手动开启（跨项目生命周期持久） | 决定项目停止后行为 |

## manual vs auto 区分（`userArmed` + `logAutoCaptureSuppressed`）

| 场景 | 手动开启（userArmed=true） | 自动开启（userArmed=false） | 手动+自动同时开启 |
|------|---------------------------|---------------------------|-------------------|
| 项目停止 | armed 态（⏸）持久 | 完全停止（▶） | 完全停止（▶）自动优先 |
| 项目重启 | 自动恢复捕 SQL | 再次自动捕 SQL | 再次自动捕 SQL |
| 点击停止 | 完全停止（▶） | 完全停止（▶） | 完全停止（▶） |
| 重启 IDEA（停止后） | — | **不自动启动**（suppressed） | **不自动启动**（suppressed） |

### `logAutoCaptureSuppressed` 持久化标志

| 操作 | suppressed |
|------|------------|
| 用户点"停止" + autoStart 开启 | → `true` |
| 项目启动（onConsoleAppeared） | **不变**（不清零！否则重启后 armed 态复现） |
| 项目停止（onConsoleRemoved） | **不变** |
| LogStartupCapture | **不变**（检查） |
| 用户手动点捕捉 | → `false` |
| 设置里关→开 autoStart | → `false` |

## 修复清单（SqlLogManager.kt + SqlLogPanel.kt + PluginSettingsConfigurable.kt + LogStartupCapture.kt）

- `userArmed` + `isWaitingForConsole` 字段（@Volatile，测试访问器）
- `logAutoCaptureSuppressed` 持久化设置（PluginSettings，存 XML 跨重启）
- `startCapture(manual: Boolean = false)`：无控制台时 `capturing=true` 立即生效；`manual=true` 设置 `userArmed` + 清 `logAutoCaptureSuppressed`
- `startListeningOnly()`：新方法，挂载桥+轮询但不进入 armed 态（suppressed 模式检测项目启动用）
- `onConsoleAppeared()`：守卫 `capturing && !isWaitingForConsole`；**不清除** `suppressed`（该标志只由显式用户操作清零）；`autoStart=true` 时自动捕捉
- `onConsoleRemoved()`：`!capturing || isWaitingForConsole` 跳过；`userArmed && !autoStart` → armed 态；否则完全停止
- `stopCapture()`：auto-start 开启时置 `logAutoCaptureSuppressed=true`
- `CaptureAction.actionPerformed()`：`manager.startCapture(manual = true)`
- `PluginSettingsConfigurable.apply()`：检测"关→开"变更 → 清 `suppressed` + 已打开项目进入 armed 态
- `LogStartupCapture.execute()`：检查 `logAutoCaptureSuppressed`，为 true 则调 `startListeningOnly()`（不进入 armed）；否则正常启动
- `CaptureAction.update()`：capturing 二分（▶/⏸）
- 通知气泡：已清除（用户反馈不需要）

## 图标刷新方案（关键）

**不用 `ActionToolbar.updateActionsAsync()` / `updateActionsImmediately()`**——沙盒中 `MyUpdateRunnable.run()` 检查 `!isShowing(component) && component.isDisplayable() && !myIncludeInvisible` 直接 return，`update()` 永不触发（详见 [[pitfall-actiontoolbar-refresh-api]]）。

最终方案：`UIUtil.uiTraverser(toolbar.component).preOrderDfsTraversal()` 遍历找 `ActionButton`，反射 `getAction()` 匹配 `CaptureAction`，直接 `setIcon()` + `setText()` + `repaint()`。

## 测试
T01-T13 十三用例 + 6 既有 = 19/19 全绿（SqlLogManagerBehaviorTest）。

## 踩坑（多轮迭代，2026-08-20）
1. **源文件被还原**：SqlLogManager.kt + SqlLogPanel.kt 曾被还原为旧版本，导致改动未部署到沙盒。沙盒 jar 须手动 cp 更新（prepareTestSandbox 有时不复制）。
2. **updateActionsAsync() 不刷新**：flag=false 触发 parent 检查 → return。
3. **updateActionsImmediately() 公开方法也不行**：虽跳过 parent 检查，但 `MyUpdateRunnable.run()` 中 isShowing 检查仍跳过更新（见 [[pitfall-actiontoolbar-refresh-api]]）。
4. **headless 测试 ≠ 沙盒**：isUnitTestMode 跳过 isShowing 检查，测试能更新沙盒不能。
5. **apply() 立即启动**：原代码在设置变更时立即调 startCapture→移除→但"项目打开后才开启设置"场景丢失→恢复并加 suppressed 检查。
6. **auto-start 不停止**：onConsoleRemoved 原统一回到 armed 态→改为 userArmed && !autoStart 守卫。
7. **手动+自动同时开启**：userArmed=true + logAutoCaptureOnStart=true 时，项目停止应完全停止（自动启动优先）。
8. **suppressed 阻止项目启动自动捕捉**：suppressed=true 时 LogStartupCapture 完全跳过→桥没挂载→轮询没启动→项目启动不捕捉→改为 startListeningOnly() 挂桥+轮询但不上 armed。
9. **onConsoleAppeared 清 suppressed**：原代码 onConsoleAppeared 清 suppressed→重启后 suppressed=false→又 armed→改为不清。
10. **设置优先**：用户要求 autoStart=true 时项目启动始终自动捕捉→onConsoleAppeared 中 autoStart=true 时清 suppressed + 自动捕捉。
11. **状态机复杂度**：初始设计只覆盖 happy path（3 状态），实际需要 5 状态 × 2 触发方式（手动/自动）的完整矩阵。涉及平台交互、多种触发方式、项目生命周期的功能，初始设计很难覆盖所有组合。
