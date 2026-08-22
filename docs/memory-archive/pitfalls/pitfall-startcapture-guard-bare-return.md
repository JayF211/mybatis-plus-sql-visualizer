---
name: pitfall-startcapture-guard-bare-return
description: 自动捕获守卫不能裸 return——跳过捕捉但不挂载检测机制，主控制台出现时永远检测不到（图标不刷新闭环断裂）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e44980e0-11ad-4600-a146-699b6fe7550c
---

`SqlLogManager.startCapture()` 加「无主运行控制台守卫」时，第一版直接 `return`——跳过了整个方法：既没进捕捉态，也没执行 `bridge.attach`（contentAdded 监听/轮询检测）。实证链（2026-08-18 沙盒日志）：守卫触发后日志只剩 startCapture 重试，**再无任何 scanAll/contentAdded**，用户启动项目运行时无机制感知「主控制台出现了」，图标永不变（onConsoleAppeared 依赖 scanAll/contentAdded 触发，而它们依赖 attach）。

**Why:** 「守卫跳过捕捉」≠「不需要任何检测」——自动捕捉的完整闭环是：打开项目（不监听）→ **检测机制常驻** → 主控制台出现 → onConsoleAppeared → 自动进捕捉态。守卫只应跳过「进入捕捉态」，仍须挂载桥监听（attach + startPolling）等待项目运行。

**How to apply:** 守卫分支写成「不进入捕捉态但挂载监听」：`if (!hasMainConsole()) { if (bridge.attach(project)) bridge.startPolling(); return }`；测试补断言「无主控制台时 attach 仍被调用」防回归。相关：[[project-architecture]]、[[pitfall-execution-api-missing]]

## T-13 延伸（2026-08-20 实施）：手动捕捉意图在 onConsoleAppeared 丢失

守卫挂载监听已修，但第二段断裂：用户手动点击捕捉后 `onConsoleAppeared()` 只检查 `logAutoCaptureOnStart`——手动路径此处即 return，控制台出现也不捕捉（`docs/开发计划/20260820-1617-SQL监听手动启动无需等待项目-开发计划.md`）。

修复：加 `userRequestedCapture` 布尔标志——`startCapture()` 无控制台时置 true；`onConsoleAppeared()` 多一个条件分支识别它（`!autoStart && !userRequestedCapture` 才 return）；`stopCapture()`/`dispose()` 清除。6 测试 T01-T06 全绿，1403 全量 0 failure。

**Why:** 「守卫挂载监听」解决了「检测机制常驻」，但「谁告诉 onConsoleAppeared 该启动了」是独立问题——设置项 `logAutoCaptureOnStart` 只承载自动启动意图，手动点击的意图需要独立标志位传递。

## 第二轮（2026-08-20）：等待态进 startCapture 后 UI 不刷新——refreshToolbar() 缺失

第一轮修完后，沙盒日志证实：`startCapture()` 无控制台分支桥 attach 成功，但按钮仍显示"捕捉"——用户以为没反应。根因：等待态分支末尾 `return` 前没有 `refreshToolbar()`，UI 从未收到状态变更通知，`CaptureAction.update()` 不触发，文案不切换为"等待中..."。

实证链：日志显示 `startCapture: 无运行中的主控制台——挂载监听等待项目运行（用户已请求捕捉）` 之后无 `refreshToolbar` 行；对比有控制台路径（`capturing=true` 后第 106 行有 `refreshToolbar()`）。

**修复：** 两处补 `refreshToolbar()`：
1. `startCapture()` 无控制台分支末尾（`return` 前）——通知 UI 切换「等待中...」文案
2. `stopCapture()` 等待态→空闲转换处（`if (!capturing) { refreshToolbar(); return }`）——通知 UI 恢复「捕捉」文案

**Why:** 状态机闭环 = 状态变量变更 + UI 通知。`capturing` 变化靠 `refreshToolbar()` 触发 `updateActionsAsync()`，但**中间态**（`isWaitingForConsole`）的变化同样依赖此路径。任何状态变量变更后都必须调 `refreshToolbar()`，否则 UI 与逻辑态不同步。

**How to apply:** 状态机有中间态时，每个出口分支末尾都须调 `refreshToolbar()`。测试断言「按钮文案随状态切换」（headless 可断言 `manager.isWaitingForConsole` 间接验证）。相关：[[pitfall-actiontoolbar-refresh-api]]

## 附带修复：SqlInputEditor.resolveSqlFileType() 缺失

`SqlPreviewPanelTest` 5 处调用 `SqlInputEditor.resolveSqlFileType()`，但主源从未定义该 companion 方法 → 整个 test module 编译阻断。补 `companion object { fun resolveSqlFileType() = Language.findLanguageByID("SQL") }`。

**Why:** DynamicUnloadFix 计划（20260820-12）移除自定义语言改内置 SQL，测试先引用新方法但主源未同步提交。教训：开发计划跨会话并行时，测试引用的 API 必须优先落地到主源。

## 第三轮（2026-08-20）：源文件被还原——改动从未部署到沙盒

第二轮修完后用户仍反馈"没效果"。排查发现 **SqlLogManager.kt 和 SqlLogPanel.kt 都被还原为原版代码**——`userRequestedCapture`/`isWaitingForConsole` 字段、`refreshToolbar()` 调用全部消失。沙盒 jar（13:21 构建）运行的是旧代码，按钮点击永远进旧路径（`onConsoleAppeared` 只检查 `logAutoCaptureOnStart`）。

**实证链：**
1. `javap -p SqlLogManager.class` 确认沙盒 jar 中有 `userRequestedCapture`/`isWaitingForConsole` 字段（说明某次构建包含改动）
2. 但 `grep "userRequestedCapture" SqlLogManager.kt` 源码中字段消失 → 源码被还原
3. 沙盒日志（13:14-13:24 会话）完全没有 `startCapture: 调用开始` 条目 → 用户点击按钮但 `actionPerformed` 未触发（旧代码工具栏无此功能，或工具窗口未打开）

**Why:** 跨会话开发时，前一会话的源码改动可能在会话切换/文件合并/IDE 操作中被还原。**沙盒 jar 不会自动跟踪源码变更**——它只在 `prepareTestSandbox`/`build` 时复制一次。源码被还原后，后续构建出的 jar 也是旧代码。

**How to apply:** 每次跑测试/验证前，先用 `grep` 关键字段名确认源码未被还原；部署后 `javap` 验证 jar 包含改动。相关：[[agent-verification]]

## 第四轮（2026-08-20）：prepareTestSandbox 不复制新 jar——须手动部署

第三轮重新应用全部改动后，`./gradlew build` 成功、定向测试 12/12 全绿。但用户仍反馈"没效果"。排查发现 **`prepareTestSandbox` 没有把新 jar 复制到沙盒**——沙盒 jar 时间戳仍是 13:21，而 `build/libs/` 中 jar 已是 13:37。

**实证链：**
1. `ls -la sandbox/.../lib/*.jar` 时间戳 13:21 vs `build/libs/*.jar` 时间戳 13:37
2. `./gradlew clean prepareTestSandbox` 后沙盒 jar 时间戳仍不变
3. 手动 `cp build/libs/*.jar sandbox/.../lib/*.jar` 后时间戳更新

**Why:** IPGP 的 `prepareTestSandbox` task 可能基于 UP-TO-DATE 检查跳过复制——源码已编译但 sandbox 目录未变化时，task 认为无需重新部署。`clean` 清掉 `build/` 但沙盒目录未被清理，task 仍可能跳过。

**How to apply:** 沙盒部署后必须手动验证：
1. 对比 `build/libs/*.jar` 与 `sandbox/.../lib/*.jar` 时间戳是否一致
2. 不一致时手动 `cp` 覆盖
3. 或用 `javap -p` 验证沙盒 jar 包含预期字段/方法

## 第五轮（2026-08-20）：按钮视觉反馈不足——加通知气泡 + Refresh 图标

第四轮手动部署后代码已生效（日志有 `startCapture: 调用开始` + `refreshToolbar`），但用户仍反馈"没反应"——原因是按钮从 Execute 图标（▶）变为 Refresh 图标（↻）+ 文案"等待中..."，变化不够明显，用户未察觉。

**修复：**
1. `CaptureAction.update()` 等待态改用 `AllIcons.Actions.Refresh`（区别于 ▶ 执行 / ⏸ 停止）
2. 进入等待态时弹出通知气泡："等待项目运行——控制台出现后自动开始捕捉"
3. 从等待态进入捕捉态时弹出通知气泡："已检测到运行控制台，自动开始捕捉 SQL"
4. `CaptureAction.actionPerformed()` 等待态点击 → `stopCapture()`（取消等待）

**Why:** 工具栏按钮空间有限，图标变化（▶→↻）和文案变化（"捕捉"→"等待中..."）可能被用户忽略。通知气泡提供明确、不可错过的视觉反馈，同时日志记录便于诊断。

**最终验证：**
- 定向测试 12/12 全绿（6 既有 + 6 新增 T01-T06）
- 全量测试 1425 tests，2 failure（在 `SqlPreviewServiceTest`，与捕捉功能无关，是既有问题：`dto.getPhone()` vs `phone`、`startTime` 值来源）
- 沙盒 jar 手动部署验证通过（`javap` 确认包含所有改动）

## 第六轮（2026-08-20）：Option A armed 态 + manual/auto 区分

第五轮后用户确认"图标不刷新"但通知气泡已出来——代码实际生效。用户提出 **Option A**：点击捕捉后按钮应直接显示 ⏸（capturing=true），而非 ↻（中间态）。

**状态机重构：**
- `startCapture(manual: Boolean = false)`：无控制台时 `capturing=true` 立即生效（armed 态）
- `userArmed` 持久标志：仅 `manual=true` 设置，跨控制台生命周期持久
- `onConsoleRemoved()`：`userArmed=true && !autoStart` → armed 态；否则完全停止
- `onConsoleAppeared()`：守卫改为 `capturing && !isWaitingForConsole`

**Why:** 用户心理模型是"点击捕捉=开启监听"，按钮应显示"停止"（⏸）而非"等待"（↻）。`userArmed` 区分手动/自动：手动开启持久（项目停止不关），自动开启随项目生命周期。

## 第七轮（2026-08-20）：手动+自动同时开启 → 无法自动关闭

用户反馈：手动开启 + "项目启动时自动开始监听"同时启用时，项目停止后无法自动关闭。

**根因：** `onConsoleRemoved()` 只检查 `userArmed`，手动点击后 `userArmed=true`，即使自动启动开启也会回到 armed 态。

**修复：** `onConsoleRemoved()` 守卫改为 `userArmed && !autoStart`：
```kotlin
if (userArmed && !autoStart) {
    // 手动开启 + 未启用自动启动 → armed 态（持久）
} else {
    // 自动启动开启（或同时开启）→ 完全停止
}
```

**规则：** 自动启动开启时，项目生命周期优先于手动 armed 态。

**最终验证：** 测试 18/18 全绿（T01-T12，新增 T12 覆盖手动+自动同时开启场景）。

## 经验总结：为什么改了 7 轮

**根本原因：状态机复杂度超出初始设计。** 开发计划只描述了最简路径（点击→等待→自动捕捉），实际状态空间是 5 状态 × 2 触发方式（手动/自动）：

| 轮次 | 问题 | 为什么之前没发现 |
|------|------|----------------|
| ① | 开发计划落地 | 计划只覆盖 happy path |
| ② | 图标不刷新 | 没料到 ActionToolbar 在沙盒中 isShowing=false 跳过更新 |
| ③ | 项目停止应回到 armed 态 | 初始设计"停止=完全停止" |
| ④ | 自动启动应随项目停止 | 手动/自动行为差异未在计划中区分 |
| ⑤ | 手动+自动同时开启 | 组合场景未在计划中考虑 |

**核心教训：** 涉及平台交互（ActionToolbar 内部守卫）、多种触发方式（手动/自动）、项目生命周期（启动/停止）的功能，初始设计很难覆盖所有组合。需要在沙盒中实际验证每个状态转换。

**How to apply:** 设计状态机时，枚举所有触发方式（手动/自动/组合）× 所有生命周期事件（启动/停止/重启）的完整矩阵，为每个单元格定义行为。相关：[[project-sql-capture-no-project-required]]、[[pitfall-actiontoolbar-refresh-api]]

## 第八轮（2026-08-20）：重启 IDEA 后自动启动不尊重用户"停止"——持久化抑制标志

第七轮后用户反馈：开启"项目启动时自动开始监听"后点击"停止"，重启 IDEA 后监听直接开启了（项目还没启动）。

**根因（沙盒日志实证）：**
```
16:09:33  LogStartupCapture.execute: 触发（logAutoCaptureOnStart=true）
16:09:33  startCapture: 进入 armed 态
16:09:33  LogStartupCapture: 已成功进入捕捉态    ← 无条件启动
```

`LogStartupCapture`（postStartupActivity）每次项目打开都无条件调 `startCapture()`，不检查用户是否之前显式停止。内存中的 `capturing`/`userArmed` 在重启后丢失。

**修复：** 新增持久化设置 `logAutoCaptureSuppressed`（存于 `mp-sql-visualizer.xml`）：

| 时机 | 行为 |
|------|------|
| 用户点击"停止" + 自动启动开启 | 置 `suppressed=true` |
| `LogStartupCapture` 触发 | 检查 `suppressed`，为 true 则跳过 |
| 项目启动（控制台出现） | 清 `suppressed=false` |
| 用户手动点击捕捉 | 清 `suppressed=false` |

**Why:** `postStartupActivity` 在每次项目打开时触发（包括 IDEA 重启后），而内存状态不跨重启。用户"停止"意图必须持久化到 XML 才能跨重启生效。

**How to apply:** 涉及 `postStartupActivity` 的自动启动逻辑，必须用持久化标志（而非内存状态）来判断用户是否已显式禁止。相关：[[pitfall-settings-persist-inplace]]

## 第九轮（2026-08-20）：项目已打开后才开启自动监听 → 项目启动不自动捕捉

第八轮后用户反馈：开启自动监听后，项目启动不会自动开启监听。

**根因（沙盒日志实证）：**
```
16:14:55  LogStartupCapture: 触发（logAutoCaptureOnStart=false）← 项目打开时设置关
16:44:58  LogStartupCapture: 触发（logAutoCaptureOnStart=false）← 仍未开
```

`LogStartupCapture`（postStartupActivity）只在项目打开时触发一次。如果用户在项目**已打开之后**才开启"自动监听"设置，`LogStartupCapture` 不会重新触发 → 桥没挂载 → 轮询没启动 → 项目启动时 `onConsoleAppeared` 不会被调用。

**修复：** `PluginSettingsConfigurable.apply()` 恢复"关→开"检测逻辑，但增加 `suppressed` 标志检查：
```kotlin
if (!autoCaptureBefore && logAutoCaptureOnStart && !logAutoCaptureSuppressed) {
    openProjects.forEach { it.getService(SqlLogManager::class.java).startCapture() }
}
```

**Why:** `LogStartupCapture` 是 `postStartupActivity`，只在项目打开时触发一次。设置变更（apply）是用户在项目打开后修改设置的唯一入口，必须在此处检测"关→开"变更并立即进入 armed 态。但需尊重 `suppressed` 标志（用户已显式停止）。

**How to apply:** `postStartupActivity` 只触发一次，不响应后续设置变更。自动启动逻辑必须在两处都有入口：`LogStartupCapture`（项目打开时）+ `PluginSettingsConfigurable.apply()`（设置变更时）。相关：[[project-sql-capture-no-project-required]]

## 第十轮（2026-08-20）：onConsoleAppeared 清 suppressed → 重启后 armed 态复现

第九轮后用户反馈：重启 IDEA 后（项目未启动），监听又自动开启了。

**根因（沙盒日志实证）：**
```
17:53:23  onConsoleAppeared → 清 suppressed=false  ← 不该清！
17:53:47  onConsoleRemoved → 停止（suppressed 仍为 false）
17:54:08  LogStartupCapture → suppressed=false → 又 armed！
```

`onConsoleAppeared()` 清了 `suppressed`，项目停止后 `suppressed=false`，重启后 `LogStartupCapture` 又进入 armed 态。

**修复：**
- `onConsoleAppeared()` **移除** `suppressed=false` 清零逻辑
- `LogStartupCapture` 在 `suppressed=true` 时调 `startListeningOnly()`（新方法：挂桥+轮询，不进入 armed 态）而非直接跳过
- `onConsoleAppeared()` 新增 `suppressed` 检查：为 true 则跳过自动捕捉

**Why:** `suppressed` 只应由显式用户操作清零（手动点捕捉 / 设置里开关切换），不应被自动生命周期事件清零。但桥仍需挂载+轮询以检测项目启动。

**最终验证：** 测试 19/19 全绿（T01-T13）。

## 第十一轮（2026-08-20）：设置优先——suppressed 不应阻止项目启动自动捕捉

第十轮后用户反馈：项目启动后不会自动开启监听（设置已开启）。用户要求**设置优先**。

**根因：** `onConsoleAppeared()` 检查 `suppressed=true` 后跳过自动捕捉，但 `logAutoCaptureOnStart=true` 时项目启动应始终自动捕捉。

**修复：** `onConsoleAppeared()` 中 `autoStart=true` 时**清除** `suppressed` + 自动捕捉：
```kotlin
if (autoStart) PluginSettings.getInstance().state.logAutoCaptureSuppressed = false
```

**Why:** 用户显式"停止"仅在"项目未运行"期间有效。项目一旦启动，`logAutoCaptureOnStart` 设置应优先——这是用户对"自动监听"的核心期望。`suppressed` 只阻止项目打开时的 armed 态，不阻止项目启动时的自动捕捉。

**最终验证：** 测试 19/19 全绿（T01-T13）。

## 经验总结（最终）：为什么改了 11 轮

**根本原因：状态机复杂度超出初始设计。** 开发计划只描述了最简路径（点击→等待→自动捕捉），实际状态空间是 5 状态 × 2 触发方式（手动/自动）× 跨重启持久化。

| 轮次 | 问题 | 根因 |
|------|------|------|
| ① | 开发计划落地 | 计划只覆盖 happy path |
| ② | 图标不刷新 | ActionToolbar 沙盒中 isShowing=false 跳过更新 |
| ③ | 项目停止应回到 armed 态 | 初始设计"停止=完全停止" |
| ④ | 自动启动应随项目停止 | 手动/自动行为差异未区分 |
| ⑤ | 手动+自动同时开启 | 组合场景未考虑 |
| ⑥ | Option A armed 态 | 用户心理模型"点击=开启" |
| ⑦ | 手动+自动无法关闭 | onConsoleRemoved 未区分 manual/auto |
| ⑧ | 重启后不尊重"停止" | 内存状态不跨重启，需持久化标志 |
| ⑨ | 项目打开后开启设置无效 | postStartupActivity 只触发一次 |
| ⑩ | onConsoleAppeared 清 suppressed | 自动事件不应清用户标志 |
| ⑪ | 设置优先 | suppressed 不应阻止项目启动自动捕捉 |

**核心教训：** 涉及平台交互（ActionToolbar 内部守卫）、多种触发方式（手动/自动）、项目生命周期（启动/停止/重启）、跨重启持久化的功能，初始设计无法覆盖所有组合。必须：
1. 枚举所有触发方式 × 生命周期事件 × 持久化维度的完整矩阵
2. 在沙盒中实际验证每个状态转换（headless 测试 ≠ 沙盒）
3. 部署后手动验证 jar 内容（`javap`）和时间戳

相关：[[project-sql-capture-no-project-required]]、[[pitfall-actiontoolbar-refresh-api]]
