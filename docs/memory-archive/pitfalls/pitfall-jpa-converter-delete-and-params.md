---
name: pitfall-jpa-converter-delete-and-params
description: JPQL 转换器的三个陷阱——①DELETE JPQL 被当 SELECT 处理 ②native SQL 命名参数不转换 ③预览缺
  previewModel 导致参数不可交互
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a4fb5d93-5d10-469d-a2f7-b9844892efec
---

**JPQL 转换器三个陷阱（2026-08-20 用户 demo 实测发现）**：

### 陷阱①：DELETE JPQL 被当 SELECT 处理

`JpqlConverter.convert` 始终返回 `SelectStatement`，即使 JPQL 是 `DELETE FROM ...`。导致 `deleteByAgeLessThanJpql` 等 DELETE 方法渲染为 SELECT。

**修复**：转换后检测 `sql.trimStart().uppercase().startsWith("DELETE")` → 返回 `DeleteStatement(table, conditions = emptyList())`。

### 陷阱②：native SQL 命名参数不转换

`@Query(value = "... WHERE username LIKE CONCAT('%', :keyword, '%') ...", nativeQuery = true)` 的原生 SQL 直接展示，`:keyword` 未被转换为 `?`。

**修复**：新增 `convertNativeSqlParameters(nativeSql)` 将 `:paramName` → `?`（正则 `(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)`，排除 `::` 类型转换）。

### 陷阱③：预览缺 previewModel → 参数不可交互

JPA 预览方法（`previewDerivedQuery`/`previewJpql`/`previewCriteriaQuery`）返回 `PreviewResult` 时 `previewModel = null` + `paramTable = null`，面板无法做交互式参数替换（输入参数后 SQL 不变化）。

**根因**：MP 路径走 `buildPreviewResult` 创建 `SqlPreviewModel(statement, dialect, views)` + `ParameterReplacer.buildParamTable`，JPA 路径完全绕过了这层。

**修复**：新增 `buildJpaPreviewResult` 统一构建器，与 MP 路径对齐：`generateWithParams` + `collectConditionViews` + `SqlPreviewModel` + `ParameterReplacer.buildParamTable`。

**参数名来源**：JPQL/派生查询的参数名来自方法签名（`@Param` 注解值 > 参数名），而非 SQL 生成器。原因：JpqlConverter/DerivedQueryConverter 不解析条件树（conditions 为空），SQL 生成器无法从空条件中提取参数来源。

**Why:** JPA 转换器只做字符串替换（实体名→表名、参数占位符替换），不构建条件树。与 MP 路径的 QueryWrapperParser 解析条件树不同。

**How to apply:** 所有预览路径必须返回 `previewModel`（条件视图）+ `paramTable`（参数对照表）以支持交互式参数替换；JPQL/派生查询参数名从方法签名提取。