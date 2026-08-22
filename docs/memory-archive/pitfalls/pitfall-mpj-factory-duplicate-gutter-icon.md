---
name: pitfall-mpj-factory-duplicate-gutter-icon
description: JoinWrappers.lambda() 工厂变量赋值形态双 gutter 图标——根因是场景 D 批量 selectAll 未去重（initializerHeadIsWrapper 回溯停在 qualifier 引用），已修复（2026-08-21）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f6d268c3-ba56-42b5-86ff-722e8a707f6e
---

**MPJ 工厂变量赋值形态双 gutter 图标（2026-08-21，已修复）**：`MPJLambdaWrapper<X> wrapper = JoinWrappers.lambda(X.class).selectAll(X.class).select(...).leftJoin(...).eq(...)` 在 wrapper 声明行出现两个重复的 SQL 预览图标。

**真实根因（两轮排查）**：
- 第一轮误判为场景 C 的 `lambda` 工厂方法名未去重，修了 `isInsideWrapperVariableInitializer`（parent-chain 改 `PsiTreeUtil.getParentOfType`）——**必要但不充分**，且 LightPlatform 测试通过≠真机通过（该修复保留了，无害）。
- **第二个图标的真正来源是 `selectAll`**：它同时收录在 `BatchOperationConverter.batchOperationMethodNames`（ActiveRecord 批量方法，场景 D）和 MPJ 链方法里。场景 D 的 `initializerHeadIsWrapper(call)` 沿 `methodExpression.qualifierExpression` 回溯链首判断「是否 wrapper 链」——`JoinWrappers.lambda(...).selectAll(...)` 回溯从 selectAll 调用到 lambda 调用、再沿 lambda 的 qualifier 落到 `JoinWrappers`（PsiReferenceExpression）即循环退出，`expr` 既不是 `PsiNewExpression` 也不是 `PsiMethodCallExpression` → 返回 false → **selectAll 未被去重，标成批量锚点 → 第二个图标**。

**修复**：`initializerHeadIsWrapper` 先判断调用自身是否为工厂方法名（链头即工厂调用本身时无需再沿 qualifier 回溯）：
```kotlin
var headCall: PsiMethodCallExpression? = null
while (expr is PsiMethodCallExpression) {
    headCall = expr
    val qualifier = expr.methodExpression.qualifierExpression ?: break
    if (expr.methodExpression.referenceName in WrapperLocator.wrapperFactoryMethodNames) break
    expr = qualifier
}
return when {
    headCall?.methodExpression?.referenceName in WrapperLocator.wrapperFactoryMethodNames -> true
    expr is PsiNewExpression -> WrapperLocator.detectWrapperType(expr.type) != null
    expr is PsiMethodCallExpression -> expr.methodExpression.referenceName in WrapperLocator.wrapperFactoryMethodNames
    else -> false
}
```

**为什么第一轮测试没暴露**：断言只覆盖了 `lambda` 工厂名去重，没覆盖链上 `selectAll`。既有测试 `testInlineMpjSelectAllOnWrapperChainNotBatchAnchor` 用的是 `new MPJLambdaWrapper<>()` 链（回溯停 `PsiNewExpression` 能正确识别），**new 链 + selectAll 与工厂链 + selectAll 组合是盲区**。

**验证**：用调试测试逐一跑用户 5 种 MPJ 形态（joinRightAlias / joinExtAndSubTable / joinFromAndPage / joinOneAndCount / joinListEntity），确认全部只剩单一锚点（`wrapper` 变量声明）；`WrapperAnchorDetectorTest` + `GutterPreviewConsistencyTest` + `HeavyAnchorSemanticsTest` 全绿，全量 2m23s 全绿。改动：`WrapperAnchorDetector.kt`（initializerHeadIsWrapper）+ `WrapperAnchorDetectorTest.kt`（补 selectAll 去重断言）。

**教训**：gutter 锚点判定器里「与链方法撞名的批量方法名」是双图标高危点（selectAll 既在批量注册表又在 MPJ 链）；排查双图标须枚举全部锚点来源（可加临时调试测试遍历文件内所有 identifier 的 anchorElement 结果），不要只聚焦单一名或单项去重分支。参见 [[pitfall-jpa-gutter-anchor]]。