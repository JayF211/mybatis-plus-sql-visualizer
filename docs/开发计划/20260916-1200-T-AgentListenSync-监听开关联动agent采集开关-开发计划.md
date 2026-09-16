# T-AgentListenSync 监听开关联动 agent 采集开关——开发计划

> 立项时间：2026-09-16 12:00（会话内定案）
> 分支：feature/t-m56-01-agent-base（worktree mpsv-m56）——M56 未反向合流，改动全在本分支
> 前置：20260916 上午链路排障已闭环（resolvePid 三步回退 + Jsons 首逗号修复，2590271）

## 一、背景与问题

增强捕获（agent 字节码通道）挂载条件只看设置页开关（`enhancedCaptureEnabled && confirmed`），与工具窗口「SQL 监听」开关完全解耦。当前行为：

- 用户**手动停止监听**（`stopCapture` → `buffer.paused=true`）后，agent 仍在应用 JVM 内持续采集（参数快照/栈定位/JSON 编码）并通过 socket 发送，IDE 侧收到后在 `SqlLogEntryBuffer.addEntry` 被 `paused` 闸门丢弃——**白采集、白发送、白解析**。
- 功能不出错，但违背最小化采集语义，且高频场景浪费应用与 IDE 双侧 CPU（高频 1000+ SQL/秒约 1~3% 单核）。

agent 侧已有现成的采集开关通道：CONFIG 帧的 `disabled` 键 → `AgentState.setDisabled` → `SqlHookCallbacks` 入口短路（`isDisabled()` 检查在栈快照之前，短路即全链路停）。**联动只是把现成开关接上，不新增协议。**

## 二、目标

「SQL 监听」开关联动 agent 采集开关：停止监听 → `disabled=true`（真停采）；开启监听 → `disabled=false`（恢复采集）。三个必须处理的边界：

| # | 边界 | 不处理的后果 |
|---|---|---|
| B1 | `createSession` 握手成功后**按当前 capturing 状态立即下发一次 CONFIG** | 应用重启后新 agent 默认 `disabled=false`，监听关着也白采集（联动对「关着启动项目」场景失效） |
| B2 | `pushDesensitize` 不再写死 `disabled=false`，带上当前监听状态 | 监听关闭状态下改任意设置项（如脱敏开关），disabled 被静默重置为采集 |
| B3 | `startCapture` 下发到 agent 生效有 ≤1s 轮询窗口（agent 读循环 `soTimeout=1000`） | 开启监听后 1s 内的极端快速 SQL 可能丢——与现有控制台桥 attachFilter 轮询行为一致，**接受不修**，计划留痕 |

## 三、改动清单

### 3.1 AgentManager（project 级，挂载与会话管理）

1. 新增 `pushCaptureActive(active: Boolean)`：遍历 sessions 下发 `sendConfig(disabled = !active, desensitize = 当前设置值)`。单线程 executor 内执行（与既有下发同队列）。
2. `createSession` 成功路径（会话加入 sessions 后）：按 `SqlLogManager.getInstance(project).capturing` 下发一次初始 CONFIG（B1）。capturing 读取走 `SqlLogManager` 公开属性（现有 `capturing` 为 public 可读，若非 public 则加只读访问器）。
3. `pushDesensitize` 修正（B2）：`disabled` 参数不再写死 false，改为读当前 capturing 状态。
4. `shutdownAll` 保持现状（disabled=true 下发给将关闭的会话仅为协议完整性，会话即关）。

### 3.2 SqlLogManager（监听状态机）

1. `startCapture` 成功路径（capturing 置 true 后）：调用 `AgentManager.pushCaptureActive(true)`。
2. `stopCapture`：调用 `AgentManager.pushCaptureActive(false)`。
3. 不在 dispose 联动（project 关闭时 AgentManager.dispose 已关会话，无需下发）。

### 3.3 明确不做

- agent 侧零改动（`AgentState`/`SqlHookCallbacks`/协议帧全部现成）。
- 不做 agent 断线重连（attach 模式 ServerSocket 一次性，现状即如此，独立问题不混入本任务）。
- 不动 `SqlCaptureGate`（接管/回落语义与本联动正交）。

## 四、测试计划

`AgentManagerFallbackTest` 补用例：

1. `pushCaptureActive(true/false)` → FakeSession 收到的 CONFIG disabled 值正确（反射缝断言 AgentProtocol 帧体或 sendConfig 出参缝）。
2. B2：capturing=false 时触发 pushDesensitize → 下发的 disabled 仍为 true。
3. B1：attach 成功路径按 capturing 下发初始 CONFIG（FakeProject 环境受限则验证 sendConfig 调用缝）。

门禁：`:sql-visualizer-agent:test` + 全量 `test` 绿。

## 五、验收

1. 开监听 → 启动项目 → 执行 SQL：条目正常（回归确认）。
2. 停止监听 → 执行 SQL：agent 侧零事件发送（应用无感），IDE 列表无新增；再开监听 → 执行 SQL：条目恢复（本计划核心场景，B3 窗口内首条除外）。
3. 监听关闭状态下改设置 → agent 仍停采（B2 回归确认）。

## 六、状态

- [x] 计划立项目
- [x] 3.1 AgentManager 改动（pushCaptureActive / isListenActive / pushDesensitize 修正 B2 / attach 后初始 CONFIG B1）
- [x] 3.2 SqlLogManager 联动（startCapture 前置统一 / stopCapture）
- [x] 四、测试补齐与门禁（AgentListenSyncTest 3 例；agent 包 + 全量 test 绿）
- [x] 文档三处同步（CHANGELOG [Unreleased] / 变更记录总册 / 本文件状态勾选）

**交付**：2026-09-16 随分支提交；agent 侧零改动（CONFIG 通道与 `isDisabled()` 短路现成）。B3（开启监听 ≤1s 轮询窗口内首条 SQL 可能丢）按计划接受不修，与现有控制台桥 attachFilter 轮询行为一致。
