---
name: reference-gutter-recent-executions-popup
description: 预览 SQL 图标右键「最近执行记录」的数据源与量级上限——读的是会话内 SQL 日志缓冲（非磁盘），20 条上限；附两套「历史」的区分与两个可增长口子
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_b5cde80a-ac48-46e7-884d-8f11955f33d5
---

2026-09-21 用户问「gutter 图标右键的最近执行记录数据从哪来、会不会越来越多导致卡顿或列表超长」。**它读的是当前会话捕获的 SQL 缓冲，不是磁盘文件**——即「SQL 日志」面板那份数据，量级被框住。

- **入口与链路**：`ui/gutter/SqlGutterLineMarkerProvider.kt` 的右键菜单（预览 SQL / 最近执行记录，`showRecentEntriesPopup`，`:171-230`）。EDT 先对锚点做快照（文件 + textRange + modificationStamp）→ 后台线程 `ReadAction` 校验锚点仍有效 → 取 `SqlLogManager.entries.snapshot()`（`log/SqlLogManager.kt:58`）→ `log/EntrySourceMatcher.match`（`:42-57`）筛选 → 回 EDT 弹列表 → 选中经 `LogEntryPreviewOpener.open` 打开预览。
- **筛选语义**：`EntrySourceMatcher` 只保留**同一文件**条目（`sameFile` 段对齐后缀互含，兼容「项目根相对含模块前缀」与「源码根包式」两种路径形态），按时间倒序，行号落在锚点 **±30 行**窗口（`DEFAULT_WINDOW`）内者优先、窗口外随后，最后 `take(20)`（`MAX_RESULTS`）。语义 =「这段代码最近实际执行过哪些 SQL」。
- **三层上限（回答「会不会越来越多」的要点）**：① 内存缓冲 `SqlLogEntryBuffer` 是 `ArrayDeque`，超 `maxEntries` 从队首淘汰（`:120-133`），值来自设置页「SQL 日志最大条数」默认 200（`PluginSettings.DEFAULT_LOG_MAX_COUNT`）；② 弹窗列表 20 条硬上限；③ 快照/筛选/排序全在后台线程，EDT 只弹 ≤20 项列表 → **该入口本身不会成为卡顿源**。
- **两个会随使用增长的口子（默认状态都不成立）**：① 「SQL 日志最大条数」是自由文本框，apply 只做 `toIntOrNull() ?: 200`，**无上下限钳制**（`PluginSettingsPanel.kt:1547`）——手填 10 万则内存、日志面板列表、每次 snapshot 拷贝线性变大（弹窗仍只显示 20 条）；② 「自动保存历史 SQL」默认**关闭**（`logAutoSaveEnabled = false`），开启后每条成功捕获的 SQL 都写盘到 `.idea/sql-visualizer/sql-history/`（默认按天文件名模板），单文件满 100MB 滚动成 `.2.sql`/`.3.sql`，保留文件数默认 30（`logAutoSaveMaxFiles`，0/负 = 不限）→ 唯一真正「越来越多」处，理论上限 30×100MB。
- **两套「历史」勿混**：gutter「最近执行记录」= 会话内**执行**缓冲（上述）；F-7 工具窗「SQL 历史」= **预览**历史，由 `SqlPreviewService.recordHistory` 每次预览写一条（SQL / 来源文件行 / 表名 / 执行耗时）到应用级 `mp-sql-visualizer.xml`，`SqlHistoryManager` 非收藏上限 200（`DEFAULT_MAX_SIZE`）、收藏上限 500（`MAX_FAVORITES`，T-Perf-19 补的上限，此前收藏区无限增长）。

相关：[[reference-sql-quick-templates-actions]]（同一条「送控制台执行」链）、[[reference-real-ide-debug-entry]]（真机取证入口）。
