---
name: m41-toolbar-i18n-cleanup
description: M41 完成——SQL 预览工具栏按钮国际化修复、性能警告栏布局分离、Copy Executable SQL 按钮移除
metadata:
  node_type: memory
  type: project
  originSessionId: sess_79d9d9ca-f51f-435d-a4d5-c2483a8b4dda
---

M41 完成（2026-08-23）：SQL 预览工具栏国际化 + 布局修复。

**问题一：性能提示与按钮重叠**
- 根因：`topRow` 用 `BorderLayout`，`metaLabel`（WEST）与 `toolbar()`（EAST）共享一行，长文本挤压按钮区域。
- 修复：性能警告写入独立 `perfWarningLabel`（`BorderLayout.SOUTH`），独占一行不参与水平竞争。
- 代码：`SqlPreviewPanel.kt` `init` 块 + `updateMeta()` 分离 `perfLine`。

**问题二：Diff/Compare 按钮英文硬编码**
- 根因：`smallButton("Diff")` / `smallButton("Compare")` 硬编码英文。
- 修复：改用 `MpSqlVisualizerBundle.message("panel.diff")` / `"panel.compare"`，双 bundle 新增对应键。

**问题三：Copy Executable SQL 按钮冗余**
- 根因：编辑器文本已在 PSI 层内联参数（M2-01），`unresolvedParams()` 通常为空 → 两按钮输出完全一致。
- 修复：移除工具栏按钮，保留 `copyExecutableSql()` 方法（分享功能内部调用）。

**问题四：中文 bundle 性能警告翻译缺失**
- 根因：英文 bundle 有 23 条 perf 规则，中文仅翻译 5 条。
- 修复：补齐全部 23 条中文翻译（含 `perf.order_by_without_limit`、`perf.missing_where` 等）。

**问题五：`panel.copyExecutableSql` 中文键缺失**
- 根因：中文 bundle 缺少 `panel.copyExecutableSql` 键，`MpSqlVisualizerBundle.message()` 回退到英文。
- 修复：添加 `panel.copyExecutableSql=复制可执行 SQL`（但随后因按钮冗余被移除，键保留供分享功能内部使用）。

**教训**：i18n 完整性需与代码同步维护——新增 perf 规则时应同时更新双 bundle；`MpSqlVisualizerBundle.message()` 是唯一合法文案来源，禁止硬编码。