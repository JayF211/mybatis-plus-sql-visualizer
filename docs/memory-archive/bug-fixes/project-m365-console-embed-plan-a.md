---
name: project-m365-console-embed-plan-a
description: 方案A（原生控制台整控台嵌入）2026-08-30 落地——JdbcConsole 无 getComponent、持久整控台强制换底、A3 rehost 去抖防死循环、lag-一轮/8s误报修复、File Cache Conflict 三方同步、沙盒验证判据
metadata:
  node_type: memory
  type: project
  originSessionId: sess_9de24d6a-8b01-432a-9b67-65c75c583bda
---

方案 A（原生 DBN 控制台嵌入，计划文档 `docs/开发计划/20260830-1334-原生控制台嵌入方案AB-开发计划.md`）2026-08-30 已落地：SqlPreviewPanel 的 A1 身份探针（`embedConsole identity` 日志 + `lastConsoleEmbeddedId`）+ A2 consoleHost 持久宿主 + A3 hierarchy 自动重挂，DataSourceSelector 的 A4 持久整控台（`swapResultIntoPersistentConsole` 强制换底，`preparedFullComponent` 始终缓存，5 处重建点改造）。`ConsoleHostTest` + 防御测试全通过。

**Why**：M36.5 反复横跳的根因是"按次重建 SplitPane"导致面板侧组件身份漂移 + DBN results refreshed 拿走组件波及显示链；DBN 结果宿主在 ServiceViewComponentWrapper，需强摘。计划文档 v1.0 曾虚标 M36.5.39"已实施"而代码缺失（本轮勘误落地）。

**How to apply**：
- `JdbcConsole` **没有** `getComponent()`（javap `intellij.database.impl.jar` 实证）；`ui.getComponent()`（RunnerLayoutUi）= 结果区，不是整控台；"整控台" = 顶部块（buildHeaderComponent 原生头部+编辑器）+ 结果区装入持久 SplitPane。
- **结果区宿主语义**：`ui.getComponent()` 的结果区默认挂在 `ServiceViewComponentWrapper`（DBN 服务视图，结果组件长期老家，非临时中转）。`swapResultIntoPersistentConsole` 只要 `liveResult.parent !== sp` 就强制摘出换入（保持 `sp.bottom == liveResult` 不变式）。**旧守卫 "parent!=null && !=sp → 跳过" 是错的**——会每拍跳过，结果永远进不了面板（16:05 日志 `result component held by DBN ... skip this tick` 数十拍）。
- **A3 rehost 去抖**：DBN 结果刷新期高频 reparent 整控台 SplitPane（parent null↔host 横跳），A3 的 `parent==null → 重挂` 无防抖会死循环（16:40 实证 2753 次刷屏 = 闪烁）。必须加 `REHOST_MIN_INTERVAL_MS`（800ms）门槛，间隔 < 门槛跳过不抢。
- **滞后一轮**：`afterSubmitted` ③ 换入**实时 `ui.getComponent()`** 而非 `preparedResultComponent`（prepare 占位，从不更新，第二次执行会覆盖真实结果）。加 `swapGeneration` 代际——真实提交时递增，旧 refresh Timer/retry Timer 回调代际不匹配即丢弃并停止防互踩。**同名 Tab 复用阈值** `REUSE_ACCEPT_TICKS` 15→4 拍（15 拍=6s 对"DBN 复用同名 Content"场景滞留太久，4 拍=1.6s 平衡防早换入旧内容与卡顿）。
- **执行A仍显示B**（M36.5.45/45b）：①预览残留 B 结果——曾加 `clearResultContents`（预览清空结果区）但导致**执行后布局塌缩**，已回退；预览保留 B 结果可接受。② `swapResultIntoPersistentConsole` 在 `current===liveResult`（DBN 复用同 DataGrid）时直接 return 不 repaint——须同实例也 `revalidate/repaint` 触发重渲染。③ native `findScriptModel null`/`run chain FAILED` 只 LOG 不调 onError——须补 onError 提示。
- **布局塌缩（M36.5.46）**：DBN results refreshed 的 reparent/rehost 让整控台 SplitPane 的 **divider 落点漂移到顶部**（编辑器被压缩到 ~90px + 底部大片空白）。根因：`swapResultIntoPersistentConsole` 换入只 `revalidate/repaint` 不重置 divider；`embedConsole` 一次性 componentListener 因首次布局 height<200（编辑器 preferred 过小）`return` 未触发。修复：换入后调 `realignSplitDivider`——height<200 时 invokeLater 下一拍再校准，有尺寸且 top 高度 < 总高 35% 则强制 `setDividerLocation(0.6)`；幂等不移除监听。
- **恒"执行中"（M36.5.47）**：`executeSql` 执行前 `showLoading`，成功回调 `onResultsReady→embedConsole` 因同实例防抖 `return`，不执行 `resultPanel.showEmpty()` → 状态栏恒"执行中"。修复：`embedConsole` 同实例分支 return 前补 `showEmpty()`（清 loading；onResultsReady 成功路径触发，失败走 onError 的 showError 不被误清）。A/B 同数据源同表同 WHERE 时 DBN 复用 Result Tab 不新建——A 结果可能=B结果（同表同 WHERE），"执行A显示B"未必是真失败，需确认 A 完整 SQL（mpsql-exec.sql 显示为简化版，无 ext ON/子表 join，若预览渲染不完整属 SQL 渲染层非嵌入问题）。
- **执行失败展示真实原因+可复制（M36.5.48/49）**：DBN 查询失败把错误写入 console history（`getConsoleView().getHistoryViewer().document`），`extractConsoleError` 反射读取。**勘误**：`startResultTabRetry` 的 `result != null`（结果复用成功）分支不要调 extractConsoleError——会把正常 SQL 片段（`WHERE t.id = 1`）误判为错误误报（日志实证）；只在超时无结果路径提取，且用 `ERROR_SIGNATURES`（sql exception/syntax error/unknown column/doesn't exist 等精确签名）+ `isSqlLine` 排除纯 SQL 行。`SqlResultPanel` 加 State 状态机（EMPTY/LOADING/ERROR/SUCCESS）：新 `showIdle()` 只清 loading/成功态**保留 ERROR**（防错误被 embedConsole 同实例 showEmpty 覆盖"一闪而过"），`embedConsole` 同实例改用 showIdle；`showEmpty` 保留总清空语义、`showLoading`（重执行）清旧错误。
- **预览切换残留上次执行错误（M36.5.58）**：预览切换（reuseSharedConsole/prepareInConsole execute=false）换入控制台组件时状态栏错误未清（M36.5.49 showIdle 保留 ERROR 是防执行中一闪而过，但预览是新的干净上下文）。修正：`prepareDefaultConsole` 的 onConsole 回调改 `{ embedConsole(it); resultPanel.showEmpty() }`——预览换入后清空状态栏；执行路径（onResultsReady/onError）不受影响。
- **8s 超时误报**：原 `!newTab || !namespaceOk` 双条件任一为假即报超时，首个 SQL 连接慢时误报；放宽为只有"既无新 Tab 又无 namespace"才提示。
- **File Cache Conflict 三方同步**：`mpsql-exec.sql`（.idea/mybatis-plus-sql-visualizer/exec/）已被 console 编辑器 hold 时，`setBinaryContent`（只改 VFS+磁盘）导致 document/磁盘 stamp 不一致 → MemoryDiskConflictResolver 弹模态（16:05:39 实证 documentStamp:1742 vs oldFileStamp:779）。必须 document 驱动：`fileDoc.setText` + `commitDocument`，仅 `fileDoc==null` 才 `setBinaryContent`。
- 沙盒验证判据：`sameAsPrev=true`、无持续 30s 的 `GAVE UP ... holderShowing=false`、连续执行结果区不消失、▶ 绿色。若 `sameAsPrev=false` → 升级 B 路线（监听 contentManager 重建）。
- BUILD_STAMP 已改 `M36.5.58-preview-status`，沙盒日志首行对不上 = 旧 jar，先重启。

关联 [[project-m36-direct-execute]] [[feedback-m36-native-console-misalignment]] [[project-m36-native-finish]]
