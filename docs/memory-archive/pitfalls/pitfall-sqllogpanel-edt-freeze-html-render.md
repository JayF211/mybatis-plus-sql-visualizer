---
name: pitfall-sqllogpanel-edt-freeze-html-render
description: SqlLogPanel.refreshList 在 EDT 同步 addAll 大量日志触发 JLabel HTML 解析 + 全量
  preferred size 计算，导致 25s EDT freeze
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fa48ca12-96a0-4c37-a0e9-94015f3b6a94
---

`SqlLogPanel.refreshList` (line 753) 在 EDT 上调用 `DefaultListModel.addAll()` 一次性灌入大量 SQL 日志，触发 `fireIntervalAdded` → `WideSelectionListUI.updateLayoutState` → 对**所有行**逐一调用 `getListCellRendererComponent` 计算 preferred size。

`LogEntryListCellRenderer.getListCellRendererComponent` (line 1207) 对 `JLabel.setText(...)` 设文本时，SQL 日志中的 `<` `>`（如 `WHERE id < 100`）触发 `BasicHTML.createHTMLView` → `BasicHTML$Renderer.<init>`，每条日志都走一遍 HTML 解析器。

**Why:** 无批量抑制 + HTML 渲染 + 全量 layout 计算，三者叠加导致 EDT 阻塞 25 秒（采样 18.5s，CPU 仅 7%，纯 CPU 密集在 HTML 解析 + 布局）。

**已修复（2026-09-03，提交 2bd4673，dev 分支 → 09-03 合入 master 0413d6a）:**
- T-01 批量布局抑制：`list.ignoreRepaint = true` → `addAll` → `false` + `revalidate()` + `repaint()`
- T-02 渲染缓存：`LogSqlEntry.cachedListHtml` 惰性计算，layout pass 命中缓存不重复渲染；`refreshList` 清空全部缓存
- T-03 分批 EDT 写入：首屏 `REFRESH_FIRST_BATCH_SIZE=30` 条立即写入，剩余 `REFRESH_CHUNK_SIZE=30` 条通过 `invokeLater` 分批追加
- T-05 新增 `LogListFreezeTest` 6 用例（缓存命中/失效/独立/分批常量）

**遗留备选 T-04（待 T-06 验证后决定）:**
- JTextPane + StyledDocument 替代 HTML，彻底消除 `BasicHTML$Renderer`，保留 per-token 着色

**How to apply (未实施路径):**
1. 备选方案：JTextPane + StyledDocument 替代 `<html>` 渲染
2. 真实环境验证须 runIde 截图（单测通过 ≠ EDT 不卡）

相关文件：`SqlLogPanel.kt:736-775` (refreshList)、`SqlLogPanel.kt:1190-1217` (LogEntryListCellRenderer)、`LogSqlModels.kt:62-68` (cachedListHtml)、`LogListFreezeTest.kt`。