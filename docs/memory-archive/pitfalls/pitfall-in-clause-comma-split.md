---
name: pitfall-in-clause-comma-split
description: IN 子句参数回填三层修复——括号补全+inList标记+引号类型判定（JPQL/XML/MP/MPJ 全路径）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_60987762-1fde-4fe9-8079-1869188fbcbb
---

IN 子句参数回填的三层修复（2026-08-21，跨 JPQL/XML/MP/MPJ 全路径）：

**问题**：用户输入 `1,2,3` 回填 IN 参数时，面板渲染 `IN '1,2,3'`（整个字符串单引号包裹），应为 `IN (1, 2, 3)`（数值无引号，逗号分割多值）。

**三层修复**：

1. **括号补全**（JpqlConverter）：`IN :param` → 替换后 `IN ?` → `handleInClause()` 修正为 `IN (?)`
2. **inList 标记**（ParamHint）：标记 IN 参数让 `renderParamValue` 走逗号分割分支
   - JPQL：`JpqlConverter.findInClauseParams()` 检测 `IN :paramName` 模式 → `Result.inClauseParams` → `buildJpaPreviewResult` 设 `ParamHint(inList=true)`
   - XML：`SqlPreviewPanel.detectInClauseParams(sql)` 扫描 `IN (?, ?, ...)` → 参数索引集合 → `ParamHint(inList=true)`
   - MP/MPJ：`SqlGenerator.Condition.In` 分支已设 `ParamHint(inList=true)`
3. **引号类型判定**：数值参数不加引号
   - JPQL：`isNumericParam(method, name)` 从方法签名提取 `Collection<Integer>` → `quote=false`
   - XML：`XmlParamTypeResolver.resolveQuotes()` 按参数类型判定
   - MP/MPJ：`quoteHint(table, column)` 按列类型判定

**核心代码位置**：`SqlPreviewPanel.renderParamValue()` — `inList=true` 时逗号分割每个值独立渲染。

**Why**: 用户输入 `1,2,3` 时期望看到 `IN (1, 2, 3)` 而非 `IN '1,2,3'` 或 `IN ('1', '2', '3')`。三层缺一不可：无括号则整个字符串塞入单占位符，无 inList 则不逗号分割，无类型判定则数值被错误加引号。

**How to apply**: 新增 IN 参数回填路径时，确保三层都覆盖：括号、inList 标记、quote 类型。