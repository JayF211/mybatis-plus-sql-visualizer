---
name: pitfall-hql-locate-precision
description: HqlQueryLocator.locate() 必须返回包含 anchor 的 createQuery
  调用，而非方法体内第一个——否则多调用方法（如 jpqlQuery 两个 em.createQuery）总是显示第一个 HQL
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bb5d9591-b617-4ac9-9c22-22ca1ad5d96a
---

**HqlQueryLocator.locate() 精确匹配陷阱（2026-08-21 用户 demo 实测发现，多轮迭代修复）**：

### 问题

`locate()` 扫描方法体内全部 `PsiMethodCallExpression`，返回第一个匹配的 `createQuery` 调用。当方法内有多个 `createQuery` 调用时（如 `jpqlQuery` 有两个 `em.createQuery()`），无论光标在哪个调用上，总是返回第一个的 HQL。

此外，`locateFromCall()` 未验证第一个参数是否为字符串，导致 `cb.createQuery(Class)` 和变量引用 `cq` 被误判为 HQL（预览显示 `cq` 作为 SQL）。

### 根因

`for (call in allCalls)` 遍历找到第一个 `createQuery` 就 return，不关心 anchor 实际位于哪个调用内。`extractHqlString` 对非字符串参数（Class 字面量、CriteriaQuery 变量）也尝试提取 HQL。

### 修复：四路策略 + 字符串验证

```kotlin
fun locate(anchor: PsiElement): HqlQueryInfo? {
    // 路径 1：anchor 是 PsiMethodCallExpression → 精确匹配（验证字符串参数）
    if (anchor is PsiMethodCallExpression) {
        if (anchor.methodExpression.referenceName !in CREATE_QUERY_METHODS) return null
        if (!hasStringHqlArgument(anchor)) return null
        return locateFromCall(anchor)
    }
    // 路径 2：anchor 是 PsiMethod → 取方法体内第一个（测试场景）
    if (anchor is PsiMethod) return locateFirstInBody(anchor, null)
    // 路径 3：查找包含 anchor 的最内层 createQuery 调用
    val containingCall = findContainingCreateQueryCall(anchor)
    if (containingCall != null && hasStringHqlArgument(containingCall)) return locateFromCall(containingCall)
    // 路径 4：回退到方法体内第一个
    val method = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java) ?: return null
    return locateFirstInBody(method, null)
}
```

**hasStringHqlArgument() 必须解析变量类型**：
- 字符串字面量 → true
- 字符串拼接（PsiBinaryExpression）→ true
- **变量引用（PsiReferenceExpression）→ 解析类型，仅 String 类型视为有效**（排除 cq 等 CriteriaQuery 变量）
- Class 字面量等 → false

**locateFirstInBody 支持锚点过滤**：
```kotlin
private fun locateFirstInBody(method: PsiMethod, anchor: PsiElement?): HqlQueryInfo? {
    val body = method.body ?: return null
    val allCalls = PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
    for (call in allCalls) {
        val methodName = call.methodExpression.referenceName ?: continue
        if (methodName !in CREATE_QUERY_METHODS) continue
        if (anchor != null && !call.textRange.contains(anchor.textRange)) continue // 锚点过滤
        if (!hasStringHqlArgument(call)) continue // 验证字符串参数
        return locateFromCall(call, allCalls.toList())
    }
    return null
}
```

**Why:** IDEA LineMarkerProvider 对每个叶子元素回调 `getLineMarkerInfo`，anchor 可能位于方法体内的变量名、方法名等任意位置。变量引用必须解析类型排除非字符串变量。

**How to apply:** 所有 `locate()` 实现必须支持精确匹配——当 anchor 位于某个特定调用内时，应返回该调用的信息而非第一个。测试时需覆盖方法体内有多个同类型调用的场景。变量引用必须通过 `PsiReferenceExpression.resolve()` 解析为 `PsiVariable` 后检查 `type.canonicalText`。

---

**createNativeQuery 支持（2026-08-21 用户 `nativeQuery` 方法发现）**：

`createNativeQuery` 不在 `CREATE_QUERY_METHODS` 中 → 完全未被识别。

修复：加入方法集，新增 `previewNativeSql()` 路径（不做实体→表名映射，仅替换 `:param → ?`）。

---

**extractHqlString PsiBinaryExpression 字符串拼接（2026-08-21 用户 `orderStat` 方法发现）**：

`extractHqlString` 对 `PsiBinaryExpression`（`"..." + "..."`）返回 `firstArg.text`（含 `+` 和引号），导致 SQL 预览显示 Java 拼接语法。

修复：新增 `collectStringLiterals()` 递归收集字符串字面量并拼接。