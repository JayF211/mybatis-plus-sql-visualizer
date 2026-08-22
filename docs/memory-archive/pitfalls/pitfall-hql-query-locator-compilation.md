---
name: pitfall-hql-query-locator-compilation
description: HqlQueryLocator.kt 预存编译错误——PsiTreeUtil.collectElementsOfType 返回 Collection 非 List，传 List 参数须 .toList()（2026-08-21 修复）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c02dfa84-be52-4c4a-bf53-8d022454ab5e
---

**HqlQueryLocator.kt:138 编译错误（2026-08-21 修复）**：`PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)` 返回 `(Mutable)Collection<PsiMethodCallExpression!>`，而 `locateFromCall(call, allCalls)` 参数类型是 `List<PsiMethodCallExpression>?`。Kotlin 类型不匹配编译失败。

**修复**：`return locateFromCall(call, allCalls.toList())`

**关联**：[[project-hibernate-jpa-support]]（JPA/Hibernate 支持）