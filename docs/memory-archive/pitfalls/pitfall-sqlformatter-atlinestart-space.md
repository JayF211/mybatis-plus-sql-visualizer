---
name: pitfall-sqlformatter-atlinestart-space
description: SQL 格式化器空格丢失双 bug——atLineStart 缺失 + formatClauseLine pendingComma/pendingSpace 未设置
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_38ce50a7-6185-4497-9ade-21708ff3c5ae
  lastUpdated: 2026-09-03
---

## 问题

`SqlFormatter` 两个独立 bug 导致关键字后空格丢失：

**Bug 1 — `atLineStart` 缺失**（影响 `format()` 和 `formatClauseLine()`）：
"其余关键字"分支未设置 `atLineStart = false` → 首个关键字后标识符粘连（如 `CASEuse_status`）。

**Bug 2 — `formatClauseLine()` 逗号后关键字缺空格**（仅影响关键字换行按钮）：
逗号处理设置 `pendingComma = true` 后未设置 `pendingSpace`，且所有关键字分支未重置 `pendingComma` → SELECT 子句中逗号后的关键字前不加空格（如 `status,CASEuse_status`）。

## 根因

**Bug 1**：`atLineStart` 初始为 `true`。`flushSpace()` 检查 `!atLineStart` 决定是否加空格。首个关键字处理后 `atLineStart` 仍为 `true` → 下一个标识符不加空格。

**Bug 2**：`formatClauseLine()` 逗号分支：
```kotlin
// 原代码（错误）
if (!pendingComma) pendingSpace = true  // pendingComma=true 时不设置 pendingSpace
```
且关键字分支未重置 `pendingComma`，导致后续标识符也跳过空格。

## 修复（SqlFormatter.kt）

**Bug 1**：两处"其余关键字"分支加 `atLineStart = false`（L122、L284）。

**Bug 2**：
1. 逗号分支改为始终 `pendingSpace = true`
2. 所有关键字分支（JOIN/NEW_LINE/AND-OR/其余）加 `pendingComma = false`

```kotlin
// 逗号分支
type == SqlTokenTypes.OPERATOR && text == "," -> {
    if (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
    sb.append(',')
    pendingComma = currentClause == "SELECT" || currentClause == "SET" || depth > 0
    atLineStart = false
    pendingSpace = true  // 始终设置
}

// 关键字分支（所有）加
pendingComma = false
```

**Why:** 格式化器状态机有 3 个相互关联的状态变量（`atLineStart`、`pendingSpace`、`pendingComma`），每个分支都必须正确维护全部状态。`pendingComma` 用于 SELECT/SET 紧凑列清单（逗号后无空格），但必须在关键字到来时重置，否则后续 token 全部丢失前导空格。

**How to apply:** 实现基于 token 的格式化器时，每个分支在 `sb.append()` 后必须：
- 设置 `atLineStart = false`（除非是换行分支应设为 `true`）
- 设置 `pendingSpace = true`（除非明确不需要空格）
- 重置 `pendingComma = false`（除非是逗号分支）
- 可用"首个关键字后标识符是否保留空格"和"逗号后关键字是否保留空格"作为回归测试判据。