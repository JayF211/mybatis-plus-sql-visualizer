---
name: pitfall-hibernate-parser-strict-idx
description: HibernateLogParser.extractAfterKeyword 严格模式下 idx 偏移量翻倍 bug（2026-08-21）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_9e5ca82c-6f90-4edd-83de-ff5b686eb544
---

**BUG**：`HibernateLogParser.extractAfterKeyword()` 严格模式（`mixedPrefix=false`）下，`idx = keyword.length` 导致 `line.substring(idx + keyword.length)` 偏移量翻倍，无法匹配行首带前缀的行。

**根因**：`MyBatisLogParser` 严格模式下 `idx = 0`（行首匹配，偏移量 0），但 `HibernateLogParser` 误写为 `idx = keyword.length`（=10），然后 `line.substring(10 + 10)` 跳过 20 字符。

**修复**：严格模式下 `idx = 0`（与 MyBatisLogParser 一致）。

**正确模式**：
```kotlin
val idx = if (mixedPrefix) {
    line.indexOf(keyword)
} else {
    if (line.startsWith(keyword)) 0 else -1  // ← 0 不是 keyword.length
}
```

**Why:** 严格模式语义是「关键字在行首」，位置为 0。`substring(0 + keyword.length)` 正确跳过前缀。

**How to apply:** 所有「行首匹配前缀」的解析器，严格模式 idx 一律为 0。参见 [[project-hibernate-jpa-support]]。