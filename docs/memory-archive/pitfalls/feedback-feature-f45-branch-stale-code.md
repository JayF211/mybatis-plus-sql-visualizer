---
name: feedback-feature-f45-branch-stale-code
description: feature/f45 分支有预先存在的编译错误（引用已删除的类），已通过创建存根修复
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_911e7169-91a5-4c49-a147-49f42312791e
---

## feature/f45 分支预先存在的编译问题

**分支**：feature/f45-maintainPage-xml-preview
**日期**：2026-08-26

### 问题

feature/f45 分支的 SqlPreviewPanel.kt 引用了已删除的类：
- `cn.song.mybatisplus.visualizer.ui.diff.DiffHighlighter` — 删除于 commit 21405ee
- `cn.song.mybatisplus.visualizer.ui.highlight.SqlPreviewLanguage` — M34 重构后移除
- `cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter` — M34 重构后移除
- `cn.song.mybatisplus.visualizer.ui.diff.SqlDiffDialog` — 不存在
- `JoinDiagramGenerator.generatePlantUml()` — 方法不存在（应为 `buildDiagramModel`）

这些是 feature 分支合并 master 前就存在的问题，不是合并引入的。

### 修复方式

创建了 4 个存根类（功能为空，仅满足编译）：
- `ui/diff/DiffHighlighter.kt` — no-op highlight/clear
- `ui/diff/SqlDiffDialog.kt` — DialogWrapper 存根
- `ui/highlight/SqlPreviewLanguage.kt` — Language("MPSQL") 存根
- `ui/highlight/SqlSyntaxHighlighter.kt` — SyntaxHighlighterBase 存根

修复 `generatePlantUml` → `buildDiagramModel` 调用。
添加缺失的 `setSourceLocation`/`currentSourceLocation` 方法到 SqlPreviewPanel。

**Why:** feature 分支从某个时间点分出后，master 的 M34 重构删除了这些类，但 feature 分支未同步更新
**How to apply:** 在 feature/f45 分支上工作时，注意这些存根类功能为空；后续应考虑用 master 的原生 SQL 高亮方案替换

### 合并后状态（2026-08-26）

master 合并到 feature/f45 后，存根类仍被 SqlPreviewPanel.kt 引用（feature 分支版本保留了这些引用），编译仍通过。注意：
- master 的 SqlPreviewPanel.kt 已移除这些引用（改用原生 SQL FileType）
- feature 分支的 SqlPreviewPanel.kt 仍保留存根引用（F-45 功能需要）
- 合并后 ort 策略自动保留了 feature 分支版本（因为 master 删除的是 F-45 相关代码，feature 分支有新增代码）
