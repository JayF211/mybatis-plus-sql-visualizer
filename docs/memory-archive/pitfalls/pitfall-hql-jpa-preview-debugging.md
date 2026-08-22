---
name: pitfall-hql-jpa-preview-debugging
description: HQL/JPA SQL 预览调试踩坑记录——单元测试无法覆盖的集成问题
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bb5d9591-b617-4ac9-9c22-22ca1ad5d96a
---

## 2026-08-21/22 HQL/JPA SQL 预览调试踩坑记录

### 核心教训

**单元测试 ≠ 集成测试**：单个函数的单元测试通过 ≠ IDE 环境中正常工作。必须写端到端测试（`PreviewEndToEndTest`），调用 `service.previewHql(call)` 模拟完整链路。

### 踩坑清单

#### 1. 正则替换 + 操作原始字符串 = 重复输出

`Regex.replace(hql) { hql.replaceFirst(old, new) }` 在回调中操作原始字符串导致重复替换。正确做法：`regex.find(hql)?.let { hql.replaceFirst(old, new) }`。

**影响文件**：`converter/jpa/HqlConverter.kt`（`mapFallbackEntityToTable`）

#### 2. 字符串拼接表达式被当作字面量

`"%" + username + "%"` 以 `"` 开头和结尾，被 `sqlValue()` 误判为字符串字面量。修复：增加 `!trimmed.contains("+")` 前置条件。

**影响文件**：`converter/jpa/CriteriaQueryConverter.kt`（`sqlValue`）

#### 3. PSI BinaryExpression 未正确处理

`extractHqlString()` 对 `PsiBinaryExpression` 直接返回 `firstArg.text`（含 `+` 和引号）。必须递归收集所有字符串字面量。

**影响文件**：`psi/jpa/HqlQueryLocator.kt`（`extractHqlString`、`collectStringLiterals`）

#### 4. 多 LineMarkerProvider 冲突

添加 `createNativeQuery` 支持时，必须同时修改：
- `HqlQueryLocator.CREATE_QUERY_METHODS`
- `HqlGutterLineMarkerProvider` 匹配条件
- `CustomDaoHqlGutterLineMarkerProvider` 排除条件
- `SqlPreviewService` 新增 `previewNativeSql` 路径

遗漏任何一个都会导致功能异常或双图标。

#### 5. 位置参数名是索引

`info.parameters[i].paramName` 对于位置参数是索引（"1"、"2"），不是变量名。应使用 `valueExpr`。

**影响文件**：`ui/SqlPreviewService.kt`（`previewHql`）

#### 6. 参数类型推断不全

`buildJpaPreviewResult()` 仅在 `inClauseParams.isNotEmpty()` 时生成 `paramHints`。应始终根据方法签名生成。

#### 7. CriteriaQuery 嵌套谓词重复提取

`cb.equal()` 在 `cb.not()` 内被重复提取。修复：`isNestedInParentPredicate()` 检查。

**影响文件**：`psi/jpa/CriteriaQueryLocator.kt`

#### 8. IN 子句链式调用值丢失

`cb.in(root.get("status")).value(statuses)` 中 `statuses` 在 `.value()` 调用上。修复：`extractInValuesFromChain()`。

#### 9. 测试环境 vs IDE 环境差异

单元测试通过但 IDE 不通过的原因：
- fixture 类有 `@Table` 注解但实际项目类可能跨模块
- PSI gutter 框架行为在测试中不可测
- 多个 provider 的交互在测试中不可测

**必须补充端到端测试**：`PreviewEndToEndTest`。

### 端到端测试模式

```kotlin
class PreviewEndToEndTest : BasePlatformTestCase() {
    private lateinit var service: SqlPreviewService
    override fun setUp() {
        super.setUp()
        service = SqlPreviewService(myFixture.project)
    }
    fun testXxx() {
        myFixture.configureByText("Xxx.java", """...""")
        val call = findMethodCall("createQuery")
        val result = service.previewHql(call)
        // 验证 SQL + 参数
    }
}
```

### 相关记忆

- [[wave-parallel-development]] — 本次调试采用了 3 波并行 Agent 开发
- [[agent-parallel-test-isolation]] — 并行测试铁律
- [[feedback-doc-style-no-ai-flavor]] — 文档风格要求
- 完整开发计划文档：`docs/开发计划/20260822-0000-HQL-JPA-SQL预览调试-开发经验与踩坑记录.md`
