---
name: pitfall-criteriaquery-in-clause
description: CriteriaQuery IN 子句必须支持链式调用 .value() 并提供占位符——否则显示空 IN () 无法输入值
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bb5d9591-b617-4ac9-9c22-22ca1ad5d96a
---

**CriteriaQuery IN 子句占位符陷阱（2026-08-21 用户 demo `criteriaGrouped` 方法发现）**

### 问题

`Predicate statusIn = cb.in(root.get("status")).value(statuses)` 预览显示 `status IN ()`（空 IN 子句），面板无法输入值。

### 根因

`buildIn()` 只提取 `cb.in(...)` 的直接参数，不处理链式调用 `.value(statuses)`。当无直接参数时，`values` 为空列表 → 渲染 `IN ()`。

### 修复

**CriteriaQueryLocator.buildIn()**：
```kotlin
private fun buildIn(call: PsiMethodCallExpression, args: Array<PsiExpression>, negated: Boolean): PredicateModel {
    val fieldName = args.getOrNull(0)?.let { extractFieldNameFromArg(it) } ?: return PredicateModel.Raw(call.text)
    // 直接参数：cb.in(root.get("a"), v1, v2, ...)
    val directValues = if (args.size > 1) args.drop(1).map { it.text } else emptyList()
    // 链式调用：cb.in(root.get("a")).value(statuses)
    val chainedValues = extractInValuesFromChain(call)
    val values = (directValues + chainedValues).ifEmpty {
        // 无值时添加占位符标记，面板可输入多值
        listOf("__IN_PLACEHOLDER__")
    }
    return PredicateModel.In(fieldName, negated, values)
}

private fun extractInValuesFromChain(call: PsiMethodCallExpression): List<String> {
    val parent = call.parent as? PsiMethodCallExpression ?: return emptyList()
    val parentName = parent.methodExpression.referenceName ?: return emptyList()
    if (parentName != "value") return emptyList()
    return parent.argumentList.expressions.map { it.text }
}
```

**CriteriaQueryConverter.convertPredicate()**：
```kotlin
is PredicateModel.In -> {
    val rawValues = predicate.valueExpressions
    val values = if (rawValues == listOf("__IN_PLACEHOLDER__")) {
        listOf(SqlValue.Placeholder("statuses"))
    } else {
        rawValues.map { sqlValue(it) }
    }
    Condition.In(column = ..., negated = ..., values = values)
}
```

**Why:** CriteriaQuery API 支持 `cb.in(field).value(collection)` 链式调用，PSI 树中 `.value()` 是独立的方法调用而非 `cb.in()` 的直接参数。面板回填时 IN 参数需标记 `inList=true`，按逗号分割多值。

**How to apply:** 处理 CriteriaQuery 链式调用时，需检查父级/子级 PSI 节点提取完整参数。IN 子句无值时必须提供占位符（`?`）以便面板输入。