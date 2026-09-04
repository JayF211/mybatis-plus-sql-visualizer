---
name: project-o4-diff-native
description: O-4 差异对比改用 IDEA 原生 Diff（DiffManager.showDiff），移除自研行内高亮与 SqlDiffDialog
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0f51a5f9-dd81-4575-8c93-cc772c7c4cde
---

## 变更背景（2026-08-26）

M30-M33 计划中 O-4 包含三项：差异高亮（行内背景色）、SQL Diff（并排对比弹窗）、一键复制可执行 SQL。实施后发现行内高亮效果不佳（EffectType.BOXED 画边框非背景、alpha=40 太淡），自研 SqlDiffDialog（双 JBTextArea）体验远不如 IDEA 原生 Diff。

## 最终方案

- **移除**：差异高亮（Diff 按钮）+ `DiffHighlighter.kt` + `SqlDiffDialog.kt`
- **保留并增强**：Compare 按钮改用 `DiffContentFactory.createEditable()` + `SimpleDiffRequest` + `DiffManager.showDiff()`
- **按钮名**：`并排对比` → `差异对比`（中英文 bundle 同步）
- **可编辑**：`createEditable` 替代 `create`，差异对比视图左右两侧均可编辑 SQL
- **SQL 语法高亮**：Diff 内容使用 `FileTypeManager.getStdFileType("SQL")`，两侧原生高亮

## 关键 API

```kotlin
val diffContentFactory = DiffContentFactory.getInstance()
val sqlFileType = FileTypeManager.getInstance().getStdFileType("SQL")
val leftContent: DocumentContent = diffContentFactory.createEditable(project, oldSql, sqlFileType)
val rightContent: DocumentContent = diffContentFactory.createEditable(project, currentSql, sqlFileType)
val request = SimpleDiffRequest(title, leftContent, rightContent, leftTitle, rightTitle)
DiffManager.getInstance().showDiff(project, request)
```

**Why:** IDEA 原生 Diff 支持行级高亮、同步滚动、折叠相同行、F7 跳转差异、SQL 语法高亮、可编辑——远优于自研方案。
**How to apply:** 后续如需对比功能，统一走 `DiffManager.showDiff()` 路径，不再自研弹窗。