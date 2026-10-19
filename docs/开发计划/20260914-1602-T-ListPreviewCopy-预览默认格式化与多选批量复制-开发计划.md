# T-ListPreviewCopy：预览默认格式化与多选批量复制 开发计划

> **创建日期**：2026-09-14
> **分支**：t-list-preview-copy（自 master `8aa5261` 新建 worktree `wt-t-list-preview-copy`）
> **优先级**：P1（用户真机反馈三件套，交互体验直接缺陷）
> **预估工时**：3h（预览格式化 1h + 多选防误弹 0.5h + 批量复制 1h + 测试 0.5h）
> **来源**：用户需求原话——「一：sql日志和sql历史的sql列表，点击预览的sql应该默认格式化显示；二：如果同时选择多个sql不要在出现单机预览的效果了；三：选择多个sql需要支持批量复制，多条sql一次性复制」

---

## 一、需求分析

### 1.1 三个需求的用户场景

| # | 需求 | 现状痛点 |
|---|---|---|
| 一 | 点击预览的 SQL 默认格式化显示 | 单击预览浮层显示挤一行的还原 SQL 原文；列表已按显示模式格式化（截图：模式=格式化），点开预览却退回原文，视觉不一致且长 SQL 难读 |
| 二 | 多选时不再出现单击预览 | Ctrl/Shift 多选第二行起，每次单击仍排程 300ms 后弹单行预览浮层，多选操作被浮层干扰 |
| 三 | 多选支持批量复制 | 右键「复制 SQL」前 `selectedIndex = index` 把多选收敛为单行，只能复制被右键的那一条；内置 Ctrl+C 虽支持多选拼接但入口不可见 |

### 1.2 需求一语义决策：跟随既有「预览时格式化还原 SQL」设置

「默认格式化」不新造开关——设置页已有 `logFormatSql`（文案 `settings.log.formatSql`＝「预览时格式化还原 SQL」，**默认 true**，现仅作用于日志 tab 双击完整预览与还原匹配链）。统一语义：**所有预览面（单击浮层 × 2 页 + 双击弹窗 × 2 页）都跟随该开关**，默认开即「默认格式化显示」，显式关掉的用户保留原文偏好。相比硬编码恒格式化：不产生「设置了不格式化但浮层仍格式化」的矛盾；相比跟随列表显示模式：SINGLE_LINE 模式用户点开预览仍是原文单行，与需求字面冲突。

历史 tab 双击预览 `buildPreviewResult` 现硬编码 `formatSql = false`（T-HistParam F3 时为「保持历史页现状」），同样改为跟随 `logFormatSql`，消除两页不一致。

### 1.3 已定决策

1. **格式化管线**：`SqlFormatter.format(withTerminator(sql))`——与 `toPreviewResult` 的 `formatIfNeeded(withTerminator(...))` 同序（先补分号再格式化，生产已验证的分号处理路径）。
2. **多选判定**：`list.selectedIndices.size > 1`——`onClick` 入口拦截 + `show()` 定时器回调兜底（延迟 300ms 窗口内补选第二行的竞态）。
3. **右键选中收敛规则**（通用右键语义）：命中行已在多选集合内 → **保留多选**（此时复制=批量）；否则收敛为命中行（右键=切换操作目标）。删除/发送/跳转等其余菜单项恒作用于被右键的行。
4. **批量复制文本**：`selectedIndices` 升序逐条 `withTerminator(restoredSql)` 后 `"\n"` 拼接——与 JBList 内置 Ctrl+C 多选拼接（`itemToText` 逐条 + `StringUtil.join("\n")`）逐字节同构，两条入口产物一致。
5. **死代码面板同接线**：`SqlHistoryPanel`（T-Perf-24 未注册）的 `SqlClickPreview` lambda 一并换新 helper，防未来复活时行为分叉。
6. **不扩多选语义到其他菜单项**：批量删除/批量发送不在本次需求内，保持单行行为。

## 二、现状与接线点

### 2.1 单击预览文本供应链

- `SqlClickPreview`（`log/SqlClickPreview.kt`）构造参数 `sqlAt: (Int) -> String?`，点击时捕获、定时器到点直用。
- 三个使用点，均供应原文：`SqlLogPanel.kt:223`、`HistorySqlLogPanel.kt:70`（均 `withTerminator(...restoredSql)`）；`SqlHistoryPanel.kt:63`（`withTerminator(...sql)`，死代码）。
- 双击弹窗：日志 tab `SqlLogPanel.openPreview` 已传 `formatSql = state.logFormatSql`；历史 tab `HistorySqlLogPanel.buildPreviewResult` 硬编码 `false`。

### 2.2 单击排程链（需求二拦截点）

面板 MouseAdapter 仅转发左键 → `SqlClickPreview.onClick(clickCount, point)`：双击停表关旧；命中行 `sqlAt(index)` 捕获后 `timer.restart()`（300ms）；空白区停表关旧。**选中态检查目前完全缺席**。`show()` 由 Timer 回调触发，读 `pendingSql` 弹窗。

### 2.3 右键菜单链（需求三改造点）

- 日志 tab `SqlLogPanel.showContextMenu`：`if (index >= 0) list.selectedIndex = index`（无条件收敛单选）→ 复制项只复制 `list.selectedValue` 一条。
- 历史 tab `HistorySqlLogPanel.showSqlContextMenu`：`sqlList.selectedIndex = idx` 同款收敛，复制项同样单条。
- 多选能力本身已具备：两列表均默认 `MULTIPLE_INTERVAL_SELECTION`；`SqlEntryList.itemToText` 覆写已使内置 Ctrl+C 输出纯 SQL（多选拼接为平台链路）。

## 三、改动清单

| 文件 | 改动 |
|---|---|
| `log/SqlClickPreview.kt` | ① 新增顶层 `internal fun clickPreviewSql(sql: String, formatSql: Boolean)`（formatSql 时 `SqlFormatter.format(withTerminator(sql))`，否则 `withTerminator(sql)`）；② `onClick` 入口多选守卫（selectedIndices>1 → 停表+关旧）；③ `show()` 弹出前多选兜底守卫；④ 类 KDoc 交互契约补两条 |
| `log/SqlLogPanel.kt` | ① `clickPreview` lambda 换 `clickPreviewSql(..., state.logFormatSql)`；② 新增顶层纯函数 `resolveContextMenuSelection(selectedIndices, hitIndex)` 与 `batchCopySql(entries)`；③ `showContextMenu` 应用两函数，复制项多选时文案带条数；④ 死代码不变 |
| `log/HistorySqlLogPanel.kt` | ① `sqlClickPreview` lambda 同换；② `buildPreviewResult` `formatSql = state.logFormatSql`；③ `showSqlContextMenu` 同日志 tab 应用两纯函数 |
| `history/SqlHistoryPanel.kt` | `clickPreview` lambda 换 `clickPreviewSql(..., state.logFormatSql)`（死代码保持编译一致） |
| `log/LogEntryPreviewRecognizer.kt` | 仅 KDoc：「HistorySqlLogPanel 传 formatSql=false（历史页现状不格式化）」改为跟随 logFormatSql 的表述 |
| `messages/MpSqlVisualizerBundle.properties` + `_zh` | 新增 `log.panel.copySelected=Copy Selected SQL ({0})`／`复制选中 SQL（{0} 条）`、`log.history.copySelected` 同款 |
| 测试 | `SqlClickPreviewTest`（多选守卫×2 + clickPreviewSql 两态）、`SqlLogListCopyTest`（batchCopySql 拼接语义 + resolveContextMenuSelection 两分支）、`HistorySqlLogPanelPreviewTest`（格式化跟随设置：关=原断言、开=多行） |

## 四、实施步骤与测试

### 4.1 步骤

1. `SqlClickPreview.kt`：helper + 双守卫 + KDoc。
2. `SqlLogPanel.kt`：纯函数 + lambda + 菜单改造。
3. `HistorySqlLogPanel.kt` + `SqlHistoryPanel.kt` + `LogEntryPreviewRecognizer.kt` KDoc。
4. i18n 双语 4 键。
5. 测试三文件扩充/修正。

### 4.2 单测清单

- `SqlClickPreviewTest`：
  - 多选状态下命中行单击不排程、不残留弹窗（守卫②）；
  - 多选状态下空白区单击仍关旧预览（守卫不遮蔽既有取消语义）；
  - `clickPreviewSql` 开=多行格式化+分号、关=原文+分号（钉 PluginSettings，测后还原）。
- `SqlLogListCopyTest`：
  - `batchCopySql` 多条 `"\n"` 拼接、单条=原文+分号、分号不重复（与内置 Ctrl+C 语义同构断言）；
  - `resolveContextMenuSelection`：命中行在集合内返回原数组（保留多选）/ 不在返回单元素（收敛）。
- `HistorySqlLogPanelPreviewTest`：
  - 钉 `logFormatSql=false` 保持既有字面量断言（识别通道与格式化正交）；
  - 新增钉 `true` 断言 `result.sql` 为多行格式化（含 `\n` 且 `SqlFormatter.format` 幂等形态）。

### 4.3 全量回归与真机

- worktree 全量 `test` 绿后合并 master；主仓合并后清 `build/classes`+`build/kotlin` 防陈旧产物劫持再复跑全量。
- 真机走查（留人工，headless 不可覆盖）：①格式化开/关两态下单击浮层与双击弹窗四预览面；②Ctrl 多选第 2/3 行无浮层闪现、单选恢复后浮层正常；③多选右键复制粘贴出多条带分号 SQL；④右键空白处/单行右键行为不变。

## 五、交付时同步项

- CHANGELOG.md Unreleased：Added（批量复制）+ Changed（预览默认格式化 / 多选不再弹预览）。
- docs/MEMORY.md 计划台账行；docs/变更记录.md 同日行。
- 计划文档 §实施记录回填。

## 六、风险与边界

- **格式化性能**：`SqlFormatter.format` 为词法单遍+StringBuilder，单击时逐条执行一次，长 SQL（万字符级）毫秒级，无 EDT 风险。
- **`locationToIndex` 空白区语义**：返回最近行索引——命中行不在多选内时收敛为该行，与改造前行为一致（原代码无条件收敛），无回归。
- **`buildContent` 折叠**：`SqlAutoFolding.applyTo` 按当前全文规划折叠区，格式化多行文本为其常规输入（双击弹窗同源），无新风险。
- **i18n parity 守卫**：新键双语成对新增，bundle parity 测试自动覆盖。
- **并行会话**：主仓有未提交 feedback 文件改动与本任务文件零交集；`T-DisplayModeToSettings` 计划（显示模式迁设置页）未实施，本任务不依赖显示模式，无冲突。

## 实施记录

- 分支 t-list-preview-copy（worktree wt-t-list-preview-copy；并行任务 T-ExecSlowOps-Merge 活跃期，按并行铁律登记 T-ListPreviewCopy + build.gradle.kts 临时 `sandboxContainer = mpsv-lpc-sb`，跑完已还原不随提交）。
- 落地与计划 §三零偏差：`clickPreviewSql` 顶层 helper + `onClick`/`show` 双守卫（SqlClickPreview.kt）；日志/历史/死代码三面板 lambda 换装；`buildPreviewResult` formatSql 改读 `logFormatSql`；`resolveContextMenuSelection`/`batchCopySql`/`copyMenuText` 纯函数收口两页右键菜单；i18n 双语 4 键（log.panel.copySelected / log.history.copySelected）。
- 测试 +9：SqlClickPreviewTest 4（多选不排程/恢复单选恢复排程/clickPreviewSql 关=原文开=多行）——**多选恢复用例首跑暴露新坑**：断言后挂起定时器 300ms 到点在 EDT 真弹带编辑器的预览浮窗，fixture teardown 报 Editor hasn't been released（TraceableDisposable$DisposalException），修复=测试内 finally `cp.dispose()` 停表；SqlLogListCopyTest 4（batchCopySql 拼接/单条分号/空列表 + resolveContextMenuSelection 保留/收敛两分支）；HistorySqlLogPanelPreviewTest +1（logFormatSql=true 双击预览多行；setUp 钉 false 隔离既有识别断言）。
- worktree 独立沙盒全量裸命令 **4033/0/0/6 绿**（4m01s，基线 master 8aa5261 4024+9）。verifyFull/buildPlugin 按口径跳过；真机走查四步留人工（§4.3）。合入后复跑与 docs 同步见变更记录总册同日行。
- 合入 master（merge 280eddc，跨 T-ExecSlowOps/T-ReportL10n2/T-FeedbackDlg3 合流，自动合并零冲突）：主仓清 build/classes+build/kotlin 防陈旧产物后复跑全量 **4039/0/0/6 绿**（5m37s，4028 基线 + FeedbackDlg3 +2 + 本任务 +9）。docs 同步（变更记录总册同日行 + MEMORY 台账行 + 本节回填）随 docs 提交入册。

