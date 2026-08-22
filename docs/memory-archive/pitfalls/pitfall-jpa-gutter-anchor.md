---
name: pitfall-jpa-gutter-anchor
description: JPA gutter 图标的两个锚点陷阱——①`element as? PsiMethod` 导致永远不显示 ②每个叶子都创建 marker 导致重复图标
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a4fb5d93-5d10-469d-a2f7-b9844892efec
---

**JPA gutter 两个锚点陷阱（2026-08-20 审查+用户实测发现）**：

### 陷阱①：`element as? PsiMethod` 导致 gutter 永远不显示

`JpaGutterLineMarkerProvider.getLineMarkerInfo` 直接 `element as? PsiMethod`，但 IDEA `LineMarkerProvider` 回调的 `element` 是 PSI 叶子元素（如方法名的 `PsiIdentifier`），不是 `PsiMethod` 节点本身。

**修复**：`PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)` 向上查找。

### 陷阱②：每个叶子都创建 marker 导致重复图标（更严重）

波次1修复陷阱①后，`getParentOfType` 对方法内**每个叶子元素**都返回同一个 PsiMethod → 每个叶子都创建一个 LineMarkerInfo → 同一方法显示**多个重复 gutter 图标**。

**根因**：IDEA LineMarker 框架对 PSI 树的**每个可见叶子元素**回调 `getLineMarkerInfo`。方法声明 `List<OrderEntity> findByCustomerIdAndTotalAmountBetween(Long customerId, BigDecimal min, BigDecimal max)` 有 8+ 个叶子（`List`, `OrderEntity`, `findByCustomerIdAndTotalAmountBetween`, `Long`, `customerId`...），每个都触发一次。

**修复模式（三个 provider 统一）**：
```kotlin
// 锚定方法名 PsiIdentifier —— 仅当 element 是方法名 token 时才创建 marker
if (element !is PsiIdentifier) return null
val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
if (method.nameIdentifier !== element) return null  // ← 关键守卫
```

**额外去重**：`JpqlGutterLineMarkerProvider` 增加 `isDerivedQuery(method)` 守卫，跳过派生查询方法（已由 `JpaGutterLineMarkerProvider` 负责），避免 `@Query` + `findByXxx` 方法双图标。

**Why:** IDEA LineMarker 机制遍历所有叶子元素回调 provider（参见 `WrapperAnchorDetector.anchorElement` 处理 `PsiIdentifier`、`AnnotationSqlGutterLineMarkerProvider` 处理 `PsiJavaToken`）。MP 的 `SqlGutterLineMarkerProvider` 通过 `WrapperAnchorDetector` 内部去重，不走 `getParentOfType` 强转。

**How to apply:** 所有 `LineMarkerProvider` 必须用 `method.nameIdentifier !== element` 守卫锚定方法名；多个 provider 覆盖同一类方法时需用 `isDerivedQuery` / `hasQueryAnnotation` 互斥守卫。详见 [[pitfall-gutter-dedup]]。