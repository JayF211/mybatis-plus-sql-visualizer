---
name: pitfall-hql-converter-structure
description: HqlConverter 必须保留原始 HQL 结构——不能用碎片化正则替换，否则 SELECT 列/JOIN/WHERE 全部丢失
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bb5d9591-b617-4ac9-9c22-22ca1ad5d96a
---

**HqlConverter 字符串替换陷阱（2026-08-21 用户 demo 实测发现，经多轮迭代修复）**：

### 问题

`HqlConverter.convert()` 返回 `SelectStatement`，`previewHql()` 将 statement 传给 `SqlGenerator.generate()` 重新生成 SQL。但 `SelectStatement` 的 `selectColumns`/`conditions`/`joins` 全是空的（转换器只做字符串替换不填充这些字段），导致生成 `SELECT FROM unknown_table`。

即使改为返回 `Result(sql, statement)` 包含转换后字符串，旧的碎片化正则替换仍然损坏 SQL 结构：
- `handleJoinFetch` 替换 `JOIN FETCH u.department d` 后残留别名 `d`（别名出现两次）且缺少 `ON` 关键词
- `mapEntityToTable` fallback（entityClassName 为空）直接跳过替换 → SQL 仍含 `UserEntity`
- 整个 SQL 被重复拼接

### 根因

`HqlConverter` 用多个独立正则依次替换 HQL 字符串，每步都可能破坏上一步的结果。

### 修复：保留原始 HQL 结构

**核心原则**：转换器仅做安全的字符串级变换，**不**重构 SQL：

1. `:param → ?`（命名参数）
2. `?1 → ?`（位置参数）
3. `IN ? → IN (?)`
4. `NEW DTO(...)` → 提取构造参数（括号计数法支持嵌套）
5. `JOIN FETCH → LEFT JOIN`（正确捕获别名，输出格式：`$keyword $tableName$aliasPart ON $onCondition`）
6. 仅当 `entityClassName.isNotBlank()` 时做实体→表名映射 + 属性→列名映射
7. **Fallback**（entityClassName 空白）：新增 `mapFallbackEntityToTable`，从 FROM 子句提取实体名替换为 `unknown_table`

**JOIN FETCH 修复细节**：
```kotlin
val aliasPart = if (alias.isNotEmpty()) " $alias" else ""
val onCondition = if (alias.isNotEmpty()) {
    "$alias.id = $rootAlias.${relationName}_id /* 推断的ON条件，请验证 */"
} else {
    "${relationName}_id = $rootAlias.${relationName}_id /* 推断的ON条件，请验证 */"
}
"$keyword $tableName$aliasPart ON $onCondition"
```

**JOIN FETCH 正则必须捕获前导空白**：
- 错误：`(LEFT|INNER)?\s*JOIN\s+FETCH\s+...` 中 `\s*` 消费前导空格 → 替换后 `uLEFT JOIN`（缺空格）
- 正确：`(\s+)(LEFT|INNER)?\s*JOIN\s+FETCH\s+...` 捕获前导空白为组1，替换时 `$leadingWhitespace$keyword ...`

**mapFallbackEntityToTable 必须用 find() 不用 replace()**：
- 错误：`fromPattern.replace(hql) { hql.replaceFirst(entityName, tableName) }` → 每个 match 都操作原始字符串，导致 SQL 重复
- 正确：`fromPattern.find(hql) ?: return hql; hql.replaceFirst(entityName, tableName)` → 仅替换第一个 FROM 实体

**表名解析——PsiShortNamesCache 按短名查找**：
- `JavaPsiFacade.findClass()` 需要全限定名，短名（如 `UserEntity`）找不到 → 返回 `unknown_table`
- 正确：`PsiShortNamesCache.getInstance(project).getClassesByName(shortName, scope)` 返回数组，取 `.firstOrNull()`
- 代码位置：`SqlPreviewService.resolveHqlTable()` + `findClassByShortName()`

**复制 SQL 时剥离注释（SqlCommentStripper）**：
- `SqlPreviewPanel.copySql()` 复制前调用 `SqlCommentStripper.strip(raw)` 剥离 `--` 行注释和 `/* */` 块注释
- 实现参考 `JavaCommentStripper`（reverse 包），纯文本扫描，字符串字面量内的标记不视为注释
- 文件：`sql/SqlCommentStripper.kt`

**CriteriaQueryLocator 谓词提取陷阱（2026-08-21 迭代发现）**：

`extractPredicate` 对非 `PsiMethodCallExpression` 的表达式降级为 `PredicateModel.Raw(expr.text)`，导致 `cq.where(predicates.toArray(new Predicate[0]))` 中 `predicates.toArray(...)` 出现在 WHERE 子句。

修复：仅处理 `CB_CONDITION_METHODS` 中的方法名，其他方法调用和变量引用返回 null：
```kotlin
private fun extractPredicate(expr: PsiExpression?): PredicateModel? {
    if (expr == null) return null
    if (expr is PsiMethodCallExpression) {
        val methodName = expr.methodExpression.referenceName
        if (methodName in CB_CONDITION_METHODS || methodName in setOf("and", "or", "not")) {
            return extractPredicateFromCall(expr)
        }
        return null  // 排除 toArray 等 Java 集合工具
    }
    if (expr is PsiReferenceExpression) return null  // 变量引用不生成 Raw 谓词
    return null
}
```

**CriteriaQueryConverter sqlValue 字符串拼接陷阱（2026-08-21 用户 demo `criteriaGrouped` 方法发现）**：

`sqlValue("%" + username + "%")` 被当作字符串字面量（以 `"` 开头结尾），直接渲染为 `'%"+ username + "%'` 而非 `?` 占位符。

修复：含 `+` 的表达式视为运行时拼接 → `SqlValue.Placeholder`：
```kotlin
// 字符串字面量（不含 + 拼接）
if (!trimmed.contains("+") && ((trimmed.startsWith("'") && trimmed.endsWith("'")) || ...))
// 数字（不含 + 拼接）
if (!trimmed.contains("+") && trimmed.matches(...))
```

**CriteriaQueryLocator 嵌套谓词重复提取（同一轮发现）**：

`cb.not(cb.equal(status, 0))` 被提取两次：外层 `cb.not()` 生成 `Group(AND, [Simple(status,="= 0")], negated=true)`，内层 `cb.equal()` 生成 `Simple(status, "=" 0)` → SQL 出现 `NOT (status = 0) AND status = 0` 矛盾。

修复：新增 `isNestedInParentPredicate(call)` 检查，父级是 `and/or/not` 时跳过：
```kotlin
if (methodName in CB_CONDITION_METHODS && isCriteriaBuilderCall(call)) {
    if (!isNestedInParentPredicate(call)) {
        extractPredicate(call)?.let { predicates += it }
    }
}
private fun isNestedInParentPredicate(call: PsiMethodCallExpression): Boolean {
    val parent = call.parent as? PsiMethodCallExpression ?: return false
    val parentName = parent.methodExpression.referenceName ?: return false
    return parentName in setOf("and", "or", "not")
}
```

**Why:** HQL 列清单/WHERE 条件/ORDER BY 等结构无法从 SelectStatement 空字段重建，必须保留原始字符串。CriteriaQueryLocator 仅处理 CriteriaBuilder 条件方法，排除 Java 集合工具方法和变量引用，且跳过嵌套在 and/or/not 内的谓词。

**How to apply:** 任何 HQL/JPQL → SQL 转换器都应保留原始结构，仅做参数替换和名称映射。转换后 SQL 字符串直接用于面板显示（`result.sql`），不从 statement 模型重新生成。JOIN FETCH 正则替换时注意保留前导空白；多个 match 场景用 find() 只替换第一个。实体类查找用 PsiShortNamesCache（短名）而非 JavaPsiFacade（需 FQN）。CriteriaQuery 谓词提取时跳过嵌套在 and/or/not 内的调用；sqlValue 对含 + 的表达式视为占位符。