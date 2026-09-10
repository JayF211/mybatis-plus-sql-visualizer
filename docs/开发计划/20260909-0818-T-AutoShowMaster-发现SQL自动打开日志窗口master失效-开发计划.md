# T-AutoShowMaster：发现 SQL 自动打开日志窗口 master 失效 根因分析与开发计划

> **创建日期**：2026-09-09
> **分支**：master（当前 HEAD `29fbf55`）
> **优先级**：P1（设置页可见开关、用户显式勾选后零效果——纯假开关，功能可信度问题）
> **预估工时**：1.5h（移植与单测 1h + 全量回归 0.5h；runIde 真机复验留人工）
> **来源**：用户反馈原话——「新问题，发现SQL时自动打开日志窗口 功能没生效」

---

## 一、问题与症状

用户在设置页「SQL 日志」组勾选「发现 SQL 时自动打开日志窗口」（`settings.log.autoOpen`）后，运行项目触发 SQL 捕获，右侧「MP SQL 日志」工具窗口不自动展开——无论窗口此前是否打开过，勾选前后行为无差别。该设置项在设置页正常显示、可勾选、可保存（重启后状态保留），但勾选后无任何可观测效果。

## 二、根因分析

### 2.1 直接实证：master 生产代码零读取点

全仓检索 `logAutoOpenOnCapture`（设置状态字段的唯一承载名）在 master 工作树的分布：

| 文件 | 角色 |
|---|---|
| `PluginSettings.kt:172` | 字段定义 `var logAutoOpenOnCapture: Boolean = false` |
| `PluginSettingsPanel.kt:208/440/975/1044/1141` | 设置页勾选框渲染/isModified/apply/reset 双向绑定 |
| `SqlLogManagerBehaviorTest.kt:16` | 仅 KDoc 注释提及 |

**没有任何生产代码读取该字段驱动工具窗展开**。设置链路（UI→state 持久化）完整，消费链路（state→ToolWindow.show）完全缺失——典型的「写穿读断」假开关。

### 2.2 历史溯源：实现在 dev 分支，master 从未收到

git 考古还原完整时间线：

1. **T-M31.14**（`83429c2`，master）：设置页新增「SQL 日志」组 11 项，含本开关。当时配套实现在 `SqlLogManager`，自动打开的是独立日志窗口。
2. **T-M36.5 D2**（`f23cfd5`，master）：审计发现「logAutoOpenOnCapture 监听与面板创建耦合（窗口未打开永不触发）」，监听接线移入 `SqlLogManager` 修复。
3. **F-44**（`1c8bfe6`，master）：SQL 日志合并进主侧边栏 tab，独立窗口退役——**自动打开实现随独立窗口一并被删**，设置项与勾选框保留，KDoc 孤儿注释（SqlLogManager.kt:356「发现 SQL 且设置开启时自动打开日志工具窗口」）残留至今，功能自此形同虚设。
4. **M37.3**（`f7f17261`，**仅 dev**，2026-09-02）：发现 F-44 断链后复活行为——目标从独立窗口改为主工具窗 `show()`（不抢焦点），60s 防抖，默认值 false→true，新增 `SqlLogManagerAutoShowTest`（7 用例）。
5. **T-AutoShowSuppress**（`19bf2571`，**仅 dev**，2026-09-05）：补手动关闭抑制——用户关掉日志窗后本会话不再自动展开（否则防抖一过窗口弹回，往复成差评源）；设置开关 false→true 重启用沿解除抑制。

分支拓扑验证：`git branch --contains f7f17261` 仅 `dev`、`dev-backup-pre-master-merge-20260908`、`feature/r2-competition-response`——**master 不在列**。M37 系列走的是「master ▶ feature/m37-competitive-fusion」方向的合并（`91d0f48b` 立项记录），M37.3/T-AutoShowSuppress 两个增量从未回流 master。

### 2.3 结论

根因是**分支演进断层而非代码缺陷**：dev 上的修复链条（M37.3 复活 + T-AutoShowSuppress 抑制）完整且经 dev 用户验证，master 因合并方向单向（master▶dev）而持续缺失。master 的 `SqlLogManager.kt` 与 dev 版在 init 块、dispose、companion 结构上同源，自动展示相关代码可无冲突移植。

### 2.4 移植期新发现：监听器必须用 `wm.ex` 包

实施首轮编译失败定位出一个平台事实：本机 IPGP 裁剪发行版（IU-2024.3.7.1，599 个 jar 全量 python zipfile 扫描）**不含 `com.intellij.openapi.wm.ToolWindowManagerListener`**，但 `com.intellij.openapi.wm.ex.ToolWindowManagerListener` 在 `lib/app-client.jar` 中存在——dev 版源码用的正是 ex 包（`19bf2571` 原始 diff 实证），移植时误写非 ex 包 import 触发。两包监听器均提供两参 `stateChanged` 公开回调，行为无差；后续任何分支间移植涉及 `ToolWindowManagerListener` 一律以 **ex 包**为准（与 execution API 裁剪同族陷阱，javap/zipfile 实证为唯一权威）。

## 三、修复方案

**移植 dev 已验证实现**（非重新设计），一次带上两层增量：

### 3.1 M37.3 基础复活（`f7f17261` 全量）

- `SqlLogManager` 新增字段：`lastAutoShowAt`（防抖基准）、`onAutoShowRequest`（internal 测试缝，默认查 `ToolWindowManager.getToolWindow(TOOL_WINDOW_ID)` 后 `show()` 不 `activate()`——不抢编辑器焦点）；
- init 挂**无条件** `buffer.addListener(autoShowBufferListener)`——面板未创建/未订阅 UI 时首条捕获也触发（D2 老问题语义）；
- `maybeAutoShow()`：读 `logAutoOpenOnCapture` + 防抖判定 → 置时间戳 → 发展开请求；
- companion：`AUTO_SHOW_DEBOUNCE_MS = 60_000L` + 决策纯函数 `shouldAutoShow`（测试缝）；
- dispose 补 `buffer.removeListener(autoShowBufferListener)`（监听泄漏防线，对齐 T-Perf-15 惯例）。

### 3.2 T-AutoShowSuppress 手动关闭抑制（`19bf2571` 全量）

- 字段：`autoShowSuppressedByUserClose`、`logWindowWasVisible`（可见性沿基准，初值 false 防启动布局恢复误判）、`lastSeenAutoOpenOnCapture`（重启用沿基准）；
- init 订阅 `ToolWindowManagerListener.TOPIC`（两参 `stateChanged` 公开 API；三参重载 @Experimental 违反 Marketplace 约束；新 UI contentRemoved 不触发是既有 pitfall），只在「可见→不可见」转换沿置抑制；
- 设置变更监听内补 false→true 重启用沿清抑制；
- companion 纯函数 `isUserCloseTransition`；
- `shouldAutoShow` 升级为三参（enabled、elapsedMs、suppressedByUserClose）。

### 3.3 默认值翻转（`PluginSettings.kt:172`）

`logAutoOpenOnCapture` 默认 false→true，对齐 dev M37.3 产品决策（竞品「每次手动开面板」是普遍抱怨；已持久化 false 的老用户不受影响——Platform 反序列化以持久化值为准）。KDoc 注释同步改写。

### 3.4 测试移植

`SqlLogManagerAutoShowTest.kt`（dev 版原样复制，7 用例）：决策纯函数（含抑制优先）、手动关闭抑制生效/解除、转换沿四象限、设置重启用沿（含不误清）、buffer 入队触发展开、防抖合并、设置关闭不触发。master 的 `SqlLogConsoleBridge(SqlLogEntryBuffer())` 构造器、`LogSqlEntry` 四参、`SETTINGS_CHANGE_TOPIC`/`settingsChanged()` 形态已逐一核对匹配。测试落在 `cn.song.mybatisplus.visualizer.log.*`，shard1 通配已覆盖，无需登记分片。

## 四、实施步骤

1. `PluginSettings.kt`：默认值翻转 + KDoc；
2. `SqlLogManager.kt`：3.1+3.2 全量移植（字段/init/maybeAutoShow/dispose/companion + import `ToolWindowManagerListener`）；
3. 复制 `SqlLogManagerAutoShowTest.kt`（`git show dev:...`）；
4. 定向跑 `SqlLogManagerAutoShowTest` → 全量回归（`./gradlew test`；已登记并行任务 `autoshow-master-20260909`/沙盒 `MPSV-AutoShow-2026.2`，master 测试沙盒 IU-2024.3.7.1 与在途 gif 录制 IU-2026.2.1 不同目录无冲突）；
5. 单提交入库（feat 前缀，正文载根因与移植来源）。

## 五、验证口径

- 定向：`SqlLogManagerAutoShowTest` 7 用例全绿；
- 全量：master 基线 3138 绿（T-SyncK2JMaster33 锚点口径）不回退；
- 真机（留人工）：runIde 勾选开关→Run 项目→首条 SQL 日志窗自动展开（不抢焦点）；手动关闭后后续 SQL 不再弹出；设置重开解除抑制。

## 六、风险与回滚

- **风险 1**：默认值翻转改变老用户开箱行为——影响面为「未动过此开关的存量安装」，行为是产品预期方向（dev 已上线验证）；持久化过 false 的用户不受影响。
- **风险 2**：`ToolWindowManagerListener.stateChanged` 高频回调——实现仅读一次 `isVisible` + 布尔比较，无锁无分配，开销可忽略。
- **回滚**：单提交整体 revert 即可，无状态迁移/持久化格式变更。
