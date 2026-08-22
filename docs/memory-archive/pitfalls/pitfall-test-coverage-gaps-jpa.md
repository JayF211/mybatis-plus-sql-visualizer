---
name: pitfall-test-coverage-gaps-jpa
description: JPA 功能测试覆盖率不足的三个典型 gap——①只验证"有没有"不验证"多不多/对不对" ②无负面/互斥测试 ③子智能体自写自测盲区
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a4fb5d93-5d10-469d-a2f7-b9844892efec
---

**JPA 功能测试覆盖率不足的典型 gap（2026-08-20 三个严重 bug 均未被测试拦截）**：

### Gap ①：只验证"有没有"，不验证"多不多/对不对"

`CriteriaQueryGutterLineMarkerProviderTest.testCriteriaMethodCallProducesMarker` 只断言 `assertNotNull(provider.getLineMarkerInfo(createQueryId))`（能产生 marker 就行），**没验证方法体内其他标识符是否也产生了重复 marker**。

`SqlPreviewServiceJpaTest.testPreviewJpql_jpqlQuery` 只断言 `assertNotNull(result)` + `sql.contains("FROM")`，**没验证 `unresolvedParams` 的具体值**。

**Why:** 子智能体写代码+写测试，倾向于验证自己写的代码路径能跑通（happy path），不主动找边界/负面场景。

**How to apply:** 测试必须包含负面断言（"其他位置不应产生 X"）+ 值精确断言（`assertEquals(expectedList, result.params)`），而非仅 `assertNotNull`。

### Gap ②：无互斥测试

`JpqlGutterLineMarkerProviderTest` 只验证 @Query 方法能产生 marker，**没验证 `@Query` + `findByXxx` 方法不被两个 provider 同时标记**。

**How to apply:** 多个 provider 覆盖同类方法时，必须写互斥测试（"provider A 产生 → provider B 不产生"）。

### Gap ③：headless 测试无法验证 UI 行为

gutter 图标数量、参数交互式替换等 UI 行为在 headless 测试中完全不可测——只能验证转换逻辑正确性。

**Why:** headless 测试无 PSI 树遍历（无 LineMarker daemon），无法模拟 IDEA 编辑器的 LineMarker 回调。

**How to apply:** 发行前必须在 IDEA 真机中逐方法验证（gutter 图标数量 + 点击预览 + 参数交互），headless 测试仅覆盖逻辑层。