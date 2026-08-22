---
name: pitfall-hql-param-extraction
description: HQL 参数名提取——链式调用无变量赋值时，须直接从 HQL 字符串提取参数名
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bb5d9591-b617-4ac9-9c22-22ca1ad5d96a
---

**HQL 参数名提取陷阱（2026-08-21 用户 demo 实测发现）**：

### 问题

`previewHql()` 的参数名来自 `HqlQueryLocator.locate()` 的 `info.parameters`，而 `locateFromCall()` 通过 `findAssignedVariable(call)` 追踪 Query 变量上的 `setParameter` 调用。

对于链式调用（无变量赋值）场景：
```java
entityManager.createQuery("... :username ...").setParameter("username", username);
```
`findAssignedVariable(call)` 返回 null（没有 `Query q = ...` 赋值）→ `queryVars` 为空 → 无参数被追踪 → 参数栏空白。

### 修复

`previewHql()` / `previewCustomDaoHql()` 在 `info.parameters` 为空时，直接从 HQL 字符串提取参数名：
```kotlin
val paramNames = info.parameters.map { it.paramName }.ifEmpty {
    extractParamNamesFromHql(info.hql)
}

private fun extractParamNamesFromHql(hql: String): List<String> {
    val namedParams = Regex("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)").findAll(hql)
        .map { it.groupValues[1] }.toList()
    val positionalParams = Regex("\\?(\\d+)").findAll(hql)
        .map { "param${it.groupValues[1]}" }.toList()
    return (namedParams + positionalParams).distinct()
}
```

### 位置参数名必须从 setValue 的 valueExpr 提取

`extractParamNamesFromHql` 生成 `param1`/`param2` 是错误的。正确做法：

```kotlin
val paramNames = info.parameters.map { if (it.isPositional) it.valueExpr else it.paramName }.ifEmpty {
    extractParamNamesFromHql(info.hql)
}
```

- `HqlParameter.isPositional=true` 时 `paramName` 是索引（"1","2"），`valueExpr` 是变量名（"username","minAge"）
- 应显示变量名而非索引

### 参数类型自动推断（quote 判定）

`buildJpaPreviewResult` 的 `paramHints` 不应仅在 `inClauseParams.isNotEmpty()` 时填充。正确做法：

```kotlin
val paramHints = if (paramNames.isNotEmpty()) {
    paramNames.map { name ->
        val inList = name in inClauseParams
        val isNumeric = isNumericParam(method, name)
        ParamHint(quote = !isNumeric, inList = inList)
    }
} else emptyList()
```

- `isNumericParam` 根据方法签名参数类型判定（Integer/Long→数值→不加引号，String→加引号）
- 无法判定类型时默认加引号（保守策略）

**HqlQueryLocator.locate() 精确匹配与表名解析（2026-08-21 迭代）**：

`locate()` 必须返回包含 anchor 的 createQuery 调用（而非方法体内第一个），且需验证字符串参数：

```kotlin
fun locate(anchor: PsiElement): HqlQueryInfo? {
    if (anchor is PsiMethodCallExpression) { // 直接传 PsiMethodCallExpression（gutter 导航场景）
        if (anchor.methodExpression.referenceName !in CREATE_QUERY_METHODS) return null
        if (!hasStringHqlArgument(anchor)) return null
        return locateFromCall(anchor)
    }
    if (anchor is PsiMethod) return locateFirstInBody(anchor, null) // 测试场景
    val containingCall = findContainingCreateQueryCall(anchor) // gutter identifier 场景
    if (containingCall != null && hasStringHqlArgument(containingCall)) return locateFromCall(containingCall)
    ...
}
```

**hasStringHqlArgument() 必须解析变量类型**：
- 字符串字面量 → true
- 字符串拼接（PsiBinaryExpression）→ true
- **变量引用（PsiReferenceExpression）→ 解析类型，仅 String 类型视为有效**（排除 cq 等 CriteriaQuery 变量被误判为 HQL）
- Class 字面量等 → false

**表名解析**：`JavaPsiFacade.findClass()` 需要全限定名 → 用 `PsiShortNamesCache.getClassesByName(shortName, scope)` 按短名查找。

**Why:** HQL 字符串本身包含参数引用（`:paramName` 或 `?1`/`?2`），不依赖 `setParameter` 追踪也能提取参数名。位置参数的值表达式（valueExpr）包含实际变量名，应作为显示名。gutter 导航必须精确匹配用户点击的调用，不能总是返回第一个。变量引用必须解析类型排除非字符串变量。

**How to apply:** 所有 HQL/JPQL 预览入口都应实现 fallback 参数提取——当 setParameter 追踪失败时，从 HQL 文本直接解析。命名参数用正则 `(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)`，位置参数用 `\?(\d+)`。位置参数显示名用 valueExpr（变量名）。参数类型推断基于方法签名参数类型，String→加引号，数值→不加。gutter 导航 handler 直接传 PsiMethodCallExpression 给 previewHql()，不依赖 locate() 反查。表名查找用 PsiShortNamesCache。