---
name: pitfall-psource-chained-append-ordering
description: SqlStringConcatenator 链式 append 多层共享同一 PSI startOffset，排序须用 textLength 作次键
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_2b694b14-73b8-4570-ae2e-f9e6fb449455
---

# 链式 append 排序陷阱（SqlStringConcatenator）

**现象**：`sql.append("AND x LIKE '%").append(username).append("%' ")` 预览时运行时值丢失，前后字符串粘连成 `LIKE '%AND...'`。

**根因**：PSI 链式调用中外层/中层/内层 `append` 的 `startOffset` **相同**（都从 `sql` 起）。`sortedBy { startOffset }` 无法区分，保留 `collectElementsOfType` 返回顺序（外层先于内层），拼接顺序颠倒。同时原过滤只接受 qualifier 为 `PsiReferenceExpression` 的直接 append，链式外层 qualifier 是 `PsiMethodCallExpression` 被全部过滤。

**修复**（`bb0da3c`）：
1. 加 `resolveRootVariable` 沿 qualifier 链回溯根变量，正确处理链式调用
2. 排序改用 `sortedWith(compareBy({ it.textRange.startOffset }, { it.textLength }))`——startOffset 相同时按文本长度升序（内层最短 → 排前面）

**Why**：PSI 链式方法调用 `a.b().c().d()` 中各层 `PsiMethodCallExpression` 的 textRange 都从 `a` 起、到最外层 `)` 止，startOffset 完全相同；只有 textLength 能区分嵌套层级。

**How to apply**：遍历 PSI 链式调用时，若需按源码出现顺序处理各层，不能仅靠 startOffset 排序，须加 textLength（或 endOffset）作次键。