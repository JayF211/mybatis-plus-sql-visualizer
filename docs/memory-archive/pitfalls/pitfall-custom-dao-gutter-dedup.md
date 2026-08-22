---
name: pitfall-custom-dao-gutter-dedup
description: HQL gutter 图标去重——两个 provider 互相排除 + 导航 handler 直接传 PsiMethodCallExpression
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bb5d9591-b617-4ac9-9c22-22ca1ad5d96a
---

**HQL gutter 重复图标陷阱（2026-08-21 用户 demo 实测发现，多轮迭代修复）**：

### 问题

方法内有 2 个 `createQuery` 调用，却显示了 4 个重复 gutter 图标。原因是两个 provider 都在为同一个 `createQuery` 创建图标：
- `HqlGutterLineMarkerProvider` — 锚定 `createQuery` 方法名 ✅
- `CustomDaoHqlGutterLineMarkerProvider` — 因为 `createQuery` 含 `Query` 关键字，`CustomDaoHqlLocator` 误判为自定义 DAO 调用 ❌

结果：2 个调用 × 2 个 provider = 4 个重复图标。

此外，当同一个方法内有多个 `createQuery` 调用时（如 `jpqlQuery` 有两个 `em.createQuery()`），旧实现所有图标都显示第一个 SQL。

### 修复

#### 1. 跨 provider 互斥排除

`CustomDaoHqlGutterLineMarkerProvider` 必须排除标准 JPA/Hibernate 方法：
```kotlin
// 【关键排除】标准 JPA/Hibernate 方法由 HqlGutterLineMarkerProvider 负责
if (element.text in setOf("createQuery", "createNamedQuery", "createNativeQuery")) return null
```

`HqlGutterLineMarkerProvider` 处理所有三种 createQuery 方法：
```kotlin
if (element.text !in setOf("createQuery", "createNamedQuery", "createNativeQuery")) return null
```

#### 2. 导航 handler 直接传 PsiMethodCallExpression

两个 provider 的导航 handler 都应直接从锚点向上查找 `PsiMethodCallExpression`，传给 `previewHql()`/`previewCustomDaoHql()`，而非依赖 `locate()` 反查：
```kotlin
override fun navigate(e: MouseEvent?, elt: PsiElement) {
    val parent = elt.parent as? PsiReferenceExpression ?: return
    val call = parent.parent as? PsiMethodCallExpression ?: return
    SqlPreviewService.previewAsync(project, {
        service.previewHql(call)  // 直接传调用，不依赖 locate()
    }) { result -> SqlPreviewService.showResult(project, result) }
}
```

**Why:** 一个方法体内可能有多个 `createQuery` 调用（如 `jpqlQuery` 有两个 `em.createQuery()`），每个调用需要独立图标和独立 SQL 预览。`locate()` 反查可能返回方法体内第一个调用而非锚点所在的调用。

**How to apply:** 多个 provider 覆盖同一条目时，必须通过方法名白名单/黑名单明确分工。导航 handler 应直接绑定到锚点对应的 `PsiMethodCallExpression`，确保点击哪个图标就显示哪个 SQL。