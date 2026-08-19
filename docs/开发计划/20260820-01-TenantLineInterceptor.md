# 多租户拦截器模拟（TenantLineInnerInterceptor）开发计划

> 状态：⬜ 待实施
> 立项依据：《[20260820-全量优化与新功能开发计划-总纲](./20260820-全量优化与新功能开发计划-总纲.md)》#1，P0
> 需求来源：真实项目几乎必用多租户拦截器，当前预览输出缺少 `tenant_id` 条件/列——"预览看起来对、执行结果错"的最危险情况。

## 1. 目标与范围

在插件预览引擎中模拟 MyBatis-Plus `TenantLineInnerInterceptor` 的多租户列注入行为，使预览 SQL 与运行时 SQL 语义一致。

**已确认决策**：
- **注入位置**：中央渲染层（`SqlPreviewService`），与既有 `injectLogicDeleteCondition` / `injectOptimisticLockCondition` 串行调用；不在各 Converter 散落处理。
- **配置面**：新增「租户模拟」配置组（开关 + 租户列名），对齐 `injectLogicDelete` / `logicDeleteValue` 的命名与默认值模式。
- **方法级跳过**：PSI 扫描调用链所在方法上的 `@InterceptorIgnore(tenantLine = true)` 注解，命中则跳过租户注入。
- **INSERT 租户列**：INSERT 不走 WHERE，在列清单中追加 `tenant_id`（参考 `BatchModel` 的 `toggleColumns` 机制，固定列 + 无勾选）。
- **无法静态求值时**：追加可读占位 `tenant_id = ?` + warning，不抛异常。

**不含**（文档化边界）：
- 多租户表达式求值（`tenant_id = 123` 而非 `tenant_id = ?`）——运行时值静态不可得，恒占位。
- 动态多租户（部分表不启用租户）——静态分析无法判定表级配置，全局开关是唯一可靠语义。
- 多租户 JOIN 副表注入（MP `TenantLineInnerInterceptor` 默认只处理主表）——预览同。

## 2. 架构与数据流

```
PluginSettings.State
  ├─ injectTenantLine: Boolean = true        // 租户注入总开关
  └─ tenantColumnName: String = "tenant_id"   // 租户列名（可配置）

SqlPreviewService.preview()
  ├─ ① 解析语句（既有：QueryWrapperParser / ConverterRegistry）
  ├─ ② injectLogicDeleteCondition()           // 逻辑删除（T-M15-07）
  ├─ ③ injectOptimisticLockCondition()        // 乐观锁（T-M22-02）
  ├─ ④ injectTenantLineCondition()            // 🆕 租户注入（本次）
  │     ├─ 检查 @InterceptorIgnore(tenantLine=true) → 跳过
  │     ├─ SELECT/UPDATE/DELETE → WHERE 末尾追加 `AND tenant_id = ?`
  │     └─ INSERT → toggleColumns 追加固定 tenant_id 列（无勾选）
  ├─ ⑤ applyPageLimit()                       // 分页（T-M29-03）
  └─ buildPreviewResult()
```

**注入优先级（三层串行，SQL 语义等价，顺序无所谓）**：

| 顺序 | 注入器 | 语句类型 | 追加内容 | 配置依赖 |
|:----:|--------|----------|----------|:--------:|
| 1 | `injectLogicDeleteCondition` | SELECT / DELETE | `AND deleted = {value}` | `injectLogicDelete` |
| 2 | `injectOptimisticLockCondition` | UPDATE | `SET version = v+1` + `AND version = ?` | 无（恒生效） |
| 3 | `injectTenantLineCondition` | SELECT / UPDATE / DELETE | `AND tenant_id = ?` | `injectTenantLine` |
| 3' | `injectTenantLineCondition` | INSERT | 列清单追加 `tenant_id` | `injectTenantLine` |

**去重规则**：
- 语句已显式引用 tenant 列（WHERE 条件含 `tenant_id` / SET 含 `tenant_id` / INSERT 列含 `tenant_id`）→ 不重复注入（与乐观锁同模式，参考 `conditionReferencesColumn`）。
- 同一语句不会同时命中 logicDelete + version（二者按语句类型互斥），tenant 与二者均兼容（不同列名）。

## 3. 现状分析

### 3.1 已完成（可复用基础）

| 模块 | 已有能力 | 复用方式 |
|------|----------|----------|
| 配置持久化 | `PluginSettings.State` + `PluginSettingsConfigurable` | 新增 `injectTenantLine` / `tenantColumnName` 字段 |
| 配置 UI | `PluginSettingsPanel`（分组 + TitledSeparator + JBTextField） | 「SQL 预览」组新增租户配置行 |
| 逻辑删除注入 | `injectLogicDeleteCondition`（conditions 列表追加 Condition.Simple） | 租户注入同模式：中央渲染层追加条件 |
| 乐观锁注入 | `injectOptimisticLockCondition`（SET + WHERE 追加 + 去重检测） | 租户注入复用去重检测模式 |
| 实体解析 | `EntityResolver`（@TableLogic / @Version 识别） | **无需识别注解**——租户列由配置指定，非实体注解 |
| 批量 INSERT 列清单 | `BatchModel.toggleColumns` + `BatchOperationConverter.buildInsertBatch` | INSERT 租户列追加参考 |
| 参数回填 | `ParamHint` + `quoteHint` | `tenant_id = ?` 占位符收集来源 |
| i18n | `MpSqlVisualizerBundle.properties` + `_zh.properties` | 新增租户相关 key |
| 测试基础设施 | `SqlPreviewServiceTest`（BasePlatformTestCase + myFixture） | 同风格新增租户测试用例 |

### 3.2 未覆盖（本次需实现）

| 缺失项 | 影响 |
|--------|------|
| 租户注入开关（配置项） | 无法开启/关闭租户模拟 |
| 租户列名配置 | 无法适配自定义列名（如 `org_id`、`shop_id`） |
| SELECT/UPDATE/DELETE 租户条件追加 | 预览 SQL 缺少 `AND tenant_id = ?` |
| INSERT 租户列追加 | 预览 INSERT 缺少 `tenant_id` 列 |
| @InterceptorIgnore(tenantLine=true) 注解识别 | 方法级跳过不支持 |
| 去重检测（tenant 列已显式出现时跳过） | 可能重复注入 |

## 4. 详细实施方案

### 4.1 配置层（PluginSettings）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt`

在 `State` data class 的 `injectLogicDelete` / `logicDeleteValue` 之后新增：

```kotlin
/**
 * 多租户注入（TenantLineInnerInterceptor 模拟）：开启后 SELECT/UPDATE/DELETE 自动追加
 * `AND {tenantColumnName} = ?`、INSERT 列清单追加租户列（对齐 MP 多租户拦截器默认行为）；
 * false 时保持纯 wrapper 语义。
 */
var injectTenantLine: Boolean = true,

/**
 * 租户列名（T-xx）：注入的租户列列名（默认 tenant_id；可按业务调整，如 org_id / shop_id——
 * 对齐 MP `TenantLineInnerInterceptor.column` 配置语义）。
 */
var tenantColumnName: String = DEFAULT_TENANT_COLUMN_NAME,
```

companion object 新增常量：

```kotlin
const val DEFAULT_TENANT_COLUMN_NAME = "tenant_id"
```

### 4.2 注入层（SqlPreviewService）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt`

#### 4.2.1 新增注入方法

在 `injectOptimisticLockCondition` 之后新增 `injectTenantLineCondition`：

```kotlin
/**
 * 多租户条件注入（TenantLineInnerInterceptor 模拟）：
 *  - SELECT/UPDATE/DELETE：WHERE 末尾追加 `AND {tenantColumn} = ?`
 *    （语句已显式引用租户列时不重复注入）；
 *  - INSERT：toggleColumns 追加固定租户列（无勾选，对齐 MP INSERT 含租户列语义）。
 *
 * 仅 [PluginSettings.injectTenantLine] = true 时生效；调用链方法标注
 * `@InterceptorIgnore(tenantLine = true)` 时跳过。
 */
private fun injectTenantLineCondition(
    table: TableInfo,
    statement: SqlStatement,
    hasInterceptorIgnore: Boolean,
): SqlStatement {
    if (!PluginSettings.getInstance().state.injectTenantLine) return statement
    if (hasInterceptorIgnore) return statement
    val tenantColumn = PluginSettings.getInstance().state.tenantColumnName.ifBlank { "tenant_id" }
    // ... 按语句类型分发
}
```

#### 4.2.2 注入逻辑（伪代码）

```kotlin
return when (statement) {
    is SelectStatement -> {
        if (conditionReferencesColumn(statement.conditions, tenantColumn)) return statement
        statement.copy(
            conditions = statement.conditions + Condition.Simple(
                ColumnRef(tableAlias = statement.tableAlias, columnName = tenantColumn),
                "=", SqlValue.Placeholder("$tenantColumn (= ?)")
            )
        )
    }
    is UpdateStatement -> {
        // SET 已含 tenant 或 WHERE 已含 tenant → 跳过
        if (statement.sets.any { it.column?.columnName == tenantColumn }) return statement
        if (conditionReferencesColumn(statement.conditions, tenantColumn)) return statement
        statement.copy(
            conditions = statement.conditions + Condition.Simple(
                ColumnRef(tableAlias = statement.tableAlias, columnName = tenantColumn),
                "=", SqlValue.Placeholder("$tenantColumn (= ?)")
            )
        )
    }
    is DeleteStatement -> {
        if (conditionReferencesColumn(statement.conditions, tenantColumn)) return statement
        statement.copy(
            conditions = statement.conditions + Condition.Simple(
                ColumnRef(tableAlias = statement.tableAlias, columnName = tenantColumn),
                "=", SqlValue.Placeholder("$tenantColumn (= ?)")
            )
        )
    }
}
```

#### 4.2.3 串联调用点

在 `preview()` 方法中，紧跟 `injectOptimisticLockCondition` 之后：

```kotlin
val finalStatement = injectOptimisticLockCondition(table, baseStatement)
// 🆕 租户注入（@InterceptorIgnore 注解检测）
val hasInterceptorIgnore = hasInterceptorIgnoreAnnotation(located.chain.calls, "tenantLine")
val tenantStatement = injectTenantLineCondition(table, finalStatement, hasInterceptorIgnore)
```

然后 `tenantStatement` 传给 `applyPageLimit`（替代原 `finalStatement`）。

#### 4.2.4 @InterceptorIgnore 注解检测

```kotlin
/**
 * 检测调用链所在方法是否标注 @InterceptorIgnore(tenantLine=true)：
 * 从 anchor 向上查找包含它的 PsiMethod，检查 modifierList 注解。
 * 注解类不可解析时按短名 "InterceptorIgnore" 兜底；属性名 = "tenantLine"，
 * 值 = true 时返回 true。
 */
private fun hasInterceptorIgnoreAnnotation(calls: List<WrapperCall>, attrName: String): Boolean {
    // 从 anchor 所在 PsiElement 向上找 PsiMethod
    // 扫描 @InterceptorIgnore 注解，读取 attrName 属性布尔值
    // 任一方法命中 → true
}
```

**实现说明**：
- 从 `located.anchor` 出发，`PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)` 找到包含方法。
- 遍历方法的 `modifierList?.annotations`，按全限定名 `com.baomidou.mybatisplus.annotation.InterceptorIgnore` 或短名 `InterceptorIgnore` 匹配。
- 读取 `tenantLine` 属性值（`PsiLiteralExpression.value as? Boolean`），true 时返回 true。
- 注解类不可解析 / 属性缺失 → 兜底 false（不跳过，保持注入——保守策略，宁可多不可少）。

### 4.3 INSERT 租户列（BatchOperationConverter）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/psi/BatchOperationConverter.kt`

在 `buildInsertBatch` 方法中，当 `injectTenantDelete = true` 时追加租户列：

```kotlin
private fun buildInsertBatch(...): BatchBuild {
    // ... 既有逻辑 ...
    val tenantColumn = if (injectTenantLine) {
        PluginSettings.getInstance().state.tenantColumnName.ifBlank { "tenant_id" }
    } else null
    // 租户列追加到 toggleColumns 末尾（固定列、无勾选、恒参与）
    val finalToggleColumns = if (tenantColumn != null &&
        toggleColumns.none { it.columnName == tenantColumn }) {
        toggleColumns + BatchColumn(
            columnName = tenantColumn,
            source = "$prefix.$tenantColumn",
            quote = null,  // tenant_id 类型未知，面板走数字启发式
            checked = true
        )
    } else toggleColumns
    // ... 构建 BatchModel ...
}
```

**注意**：
- `injectTenantLine` 需透传（从 `convert` 方法签名增加参数，对齐 `injectLogicDelete` 透传模式）。
- `paramGroups` 同步追加 `tenantColumn`。
- 已有 `tenant_id` 列（实体字段）时不重复追加（检测 `toggleColumns.none { it.columnName == tenantColumn }`）。

### 4.4 UI 层（PluginSettingsPanel）

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt`

在「SQL 预览」组（`settings.section.preview`）的 `injectLogicDeleteCheck` / `logicDeleteValueField` 之后新增：

```kotlin
/** 租户注入开关：SELECT/UPDATE/DELETE 自动追加 `AND tenant_id = ?`、INSERT 列清单追加租户列。 */
private val injectTenantLineCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.injectTenantLine"))

/** 租户列名输入框（默认 tenant_id；可按业务调整，如 org_id / shop_id）。 */
private val tenantColumnNameField = JBTextField(10)
```

**布局**（在 `logicDeleteValueField` 之后追加）：

```kotlin
addRow(p, g, "", injectTenantLineCheck.apply { text = MpSqlVisualizerBundle.message("settings.injectTenantLine") })
addRow(p, g, MpSqlVisualizerBundle.message("settings.tenantColumnName"), tenantColumnNameField.apply {
    toolTipText = MpSqlVisualizerBundle.message("settings.tenantColumnName.tip")
})
```

**isModified()** 追加：

```kotlin
|| injectTenantLineCheck.isSelected != s.injectTenantLine
|| tenantColumnNameField.text.trim() != s.tenantColumnName
```

**apply()** 追加：

```kotlin
s.injectTenantLine = injectTenantLineCheck.isSelected
s.tenantColumnName = tenantColumnNameField.text.trim()
```

**reset()** 追加：

```kotlin
injectTenantLineCheck.isSelected = s.injectTenantLine
tenantColumnNameField.text = s.tenantColumnName
```

## 5. 注入优先级与去重规则详表

### 5.1 三层注入协调

```
语句 → [1.逻辑删除] → [2.乐观锁] → [3.租户] → [分页]
          │               │           │
          │               │           └─ SELECT/UPDATE/DELETE: AND tenant_id = ?
          │               │              INSERT: 列清单追加 tenant_id
          │               └─ UPDATE: SET version=v+1 + WHERE AND version=?
          └─ SELECT/DELETE: AND deleted = {value}
```

### 5.2 去重检测矩阵

| 注入器 | 去重条件（命中→跳过） | 检测方法 |
|--------|----------------------|----------|
| logicDelete | WHERE 已有 `deleted = ?` 条件 | `conditionReferencesColumn(conditions, logicDeleteColumn)` |
| optimisticLock | SET 已有 `version` 或 WHERE 已有 `version` | `sets.any { column == version }` + `conditionReferencesColumn` |
| tenantLine | WHERE 已有 `tenant_id = ?` 条件 | `conditionReferencesColumn(conditions, tenantColumn)` |
| tenantLine (INSERT) | toggleColumns 已有 `tenant_id` | `toggleColumns.none { columnName == tenantColumn }` |

### 5.3 特殊场景

- **UPDATE 同时含 @TableLogic + @Version + tenant**：三者同时注入——`SET version=v+1, deleted=?` + `WHERE ... AND deleted=? AND version=? AND tenant_id=?`（logicDelete 对 UPDATE 不注入 deleted，此处仅 version + tenant）。
  - 修正：logicDelete 仅 SELECT/DELETE，UPDATE 注入 version + tenant（两个 AND 条件）。
- **service.update(entity, wrapper) 实体更新**：`injectOptimisticLockCondition` 追加 version → `injectTenantLineCondition` 追加 tenant_id。
- **MPJ 联表**：tenant 注入仅在主表 WHERE 末尾追加（不对副表 join 注入——对齐 MP `TenantLineInnerInterceptor` 默认行为）。

## 6. @InterceptorIgnore 注解支持方案

### 6.1 MP 官方语义

```java
@InterceptorIgnore(tenantLine = true)  // 方法级：跳过租户注入
public void someMethod() {
    userService.lambdaQuery()...list();  // 该调用不追加 tenant_id
}
```

### 6.2 插件实现

插件做静态预览，只需检测**调用链所在方法**是否标注该注解：

```kotlin
// 从 anchor 向上查找 PsiMethod
val method = PsiTreeUtil.getParentOfType(located.anchor, PsiMethod::class.java) ?: return false
// 扫描注解（短名兜底）
method.modifierList?.annotations?.any { annotation ->
    annotation.nameReferenceElement?.referenceName == "InterceptorIgnore" &&
    (annotation.findAttributeValue("tenantLine") as? PsiLiteralExpression)
        ?.value as? Boolean == true
} ?: false
```

### 6.3 边界

- 注解在类级别（`@InterceptorIgnore(tenantLine = true)` 标在类上）→ 同逻辑处理（向上查找 PsiClass）。
- 注解类不可解析（粘贴片段测试）→ 短名 `InterceptorIgnore` 兜底（与 EntityResolver 同模式）。
- 多方法调用链（`service.a().b().c()`）→ 仅检测最外层包含方法（实际业务标在 service 方法上）。

## 7. 测试策略

### 7.1 测试文件

- **主体**：`src/test/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewServiceTest.kt`（追加用例，同风格）
- **配置测试**：`src/test/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPersistenceTest.kt`（追加新字段持久化断言）
- **面板测试**：`src/test/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanelBindingTest.kt`（追加 isModified/apply/reset 覆盖）

### 7.2 测试用例清单（fixture 风格，对齐既有）

| # | 用例名 | 验证点 | 预期 SQL 片段 |
|---|--------|--------|---------------|
| 1 | `testTenantLineInjectedForSelect` | SELECT 追加 `AND tenant_id = ?` | `WHERE ... AND tenant_id = ?` |
| 2 | `testTenantLineInjectedForUpdate` | UPDATE WHERE 追加 | `WHERE ... AND tenant_id = ?` |
| 3 | `testTenantLineInjectedForDelete` | DELETE WHERE 追加 | `WHERE ... AND tenant_id = ?` |
| 4 | `testTenantLineNotInjectedWhenSwitchOff` | `injectTenantLine=false` → 跳过 | 无 `tenant_id` |
| 5 | `testTenantLineNotDuplicatedWhenExplicitTenantId` | WHERE 已有 `tenant_id` → 不重复 | 仅一个 `tenant_id` |
| 6 | `testTenantLineCustomColumnName` | 列名配为 `org_id` | `AND org_id = ?` |
| 7 | `testTenantLineWithInterceptorIgnore` | `@InterceptorIgnore(tenantLine=true)` → 跳过 | 无 `tenant_id` |
| 8 | `testTenantLineWithLogicDeleteAndOptimisticLock` | 三层注入协调 | `AND deleted=0 AND version=? AND tenant_id=?` |
| 9 | `testTenantLineForMpjJoin` | MPJ 联表仅主表追加 | 主表 WHERE 有 `tenant_id`，ON 条件无 |
| 10 | `testTenantLineForEntityUpdate` | `update(entity, wrapper)` 注入 | `SET name=?, version=v+1 WHERE ... AND version=? AND tenant_id=?` |
| 11 | `testTenantLineConfigPersistence` | 新字段持久化（xml 序列化） | 读写一致 |
| 12 | `testTenantLinePanelIsModified` | 面板修改检测 | 改值 → isModified=true |
| 13 | `testTenantLinePanelApplyReset` | 面板 apply/reset | 写入配置 + 回读 |

### 7.3 关键 fixture 示例

```kotlin
fun testTenantLineInjectedForSelect() {
    myFixture.configureByText(
        "Demo.java",
        """
        public class Demo {
            public void query() {
                userService.lambdaQuery()
                    .eq(OrderEntity::getOrderCode, "ORD001")
                    .list();
            }

            static class OrderEntity {
                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;
                private String orderCode;
                // 注意：无 @TableLogic / @Version——仅 tenant 注入生效
            }

            static class UserService {
                public Object lambdaQuery() { return null; }
            }
        }
        """.trimIndent(),
    )
    val result = newService().preview(anchor())
    assertNotNull("应产出预览结果", result)
    assertTrue("应注入 tenant_id 条件：${result!!.sql}", result.sql.contains("AND tenant_id = ?"))
}
```

### 7.4 测试注意事项

- 每个用例的 Entity 需有 `@TableId`（既有惯例）。
- 租户列名为实体**不存在的列**（模拟真实 MP 行为：tenant_id 通常不在实体，由拦截器注入）。
- 如需验证 `tenant_id` 已存在时的去重，Entity 中加 `private Long tenantId;` 字段。

## 8. i18n 文案清单

### 8.1 中文（MpSqlVisualizerBundle_zh.properties）

```properties
# TenantLineInnerInterceptor 多租户模拟（T-xx）
settings.injectTenantLine=多租户实体查询/更新/删除自动追加 `AND tenant_id = ?`
settings.tenantColumnName=租户列名
settings.tenantColumnName.tip=注入的租户列列名（默认 tenant_id；可按业务调整，如 org_id / shop_id）。
```

### 8.2 英文（MpSqlVisualizerBundle.properties）

```properties
# TenantLineInnerInterceptor multi-tenant simulation (T-xx)
settings.injectTenantLine=Inject `AND tenant_id = ?` for multi-tenant entities (query/update/delete)
settings.tenantColumnName=Tenant column name
settings.tenantColumnName.tip=Column name injected as the tenant identifier (default: tenant_id; adjust per business, e.g. org_id / shop_id).
```

## 9. 风险评估与降级策略

### 9.1 风险清单

| 风险 | 概率 | 影响 | 缓解 |
|------|:----:|------|------|
| 与既有注入冲突（logicDelete / version 同列名） | 低 | 中 | tenant 列名默认 `tenant_id`，与 `deleted` / `version` 不同；配置去重检测兜底 |
| @InterceptorIgnore 检测失败（注解不可解析） | 低 | 低 | 短名兜底 + 保守策略（检测失败→注入，宁可多不可少） |
| INSERT 租户列重复（实体已有 tenant_id 字段） | 中 | 低 | `toggleColumns.none` 检测去重 |
| 用户自定义列名含特殊字符（空格等） | 极低 | 低 | trim 处理；SQL 渲染时 `quoteIfKeyword` 包裹 |
| 性能影响（每次 preview 额外扫描注解） | 低 | 低 | PSI getParentOfType 为 O(树深度)，调用链短小 |

### 9.2 降级策略

- **开关关闭**：`injectTenantLine=false` → 完全不注入（纯 wrapper 语义）。
- **列名空白**：`tenantColumnName` 空白 → 回退默认 `tenant_id`。
- **检测异常**：注解扫描抛异常 → catch 兜底返回 false（注入，保守策略）。

## 10. 文档三处同步

实施完成后同步更新：

1. **变更记录**（`docs/变更记录.md` 或 `CHANGELOG.md`）：新增「多租户拦截器模拟」条目。
2. **剩余立项**（`docs/剩余立项.md` 或对应文档）：关闭「多租户」立项。
3. **项目介绍**（`docs/项目介绍.md`）：功能矩阵新增「多租户注入」行。

## 11. 实施步骤与工时估算

### 波次划分

| 波次 | 内容 | 文件 | 测试 | 预估工时 |
|:----:|------|------|------|:--------:|
| A-1 | 配置层：PluginSettings 新增字段 + 常量 | `PluginSettings.kt` | 持久化测试 +2 | 0.5h |
| A-2 | 注入层：injectTenantLineCondition + 串联调用 | `SqlPreviewService.kt` | 核心注入测试 +6 | 1.5h |
| A-3 | @InterceptorIgnore 注解检测 | `SqlPreviewService.kt` | 注解跳过测试 +1 | 0.5h |
| A-4 | INSERT 租户列：BatchOperationConverter 透传 | `BatchOperationConverter.kt` | INSERT 注入测试 +1 | 0.5h |
| B-1 | UI 层：PluginSettingsPanel 新增配置行 | `PluginSettingsPanel.kt` | 面板 isModified/apply/reset +3 | 0.5h |
| B-2 | i18n：中英双语文案 | `MpSqlVisualizerBundle*.properties` | — | 0.25h |
| B-3 | 文档三处同步 | 变更记录/剩余立项/项目介绍 | — | 0.25h |
| C | 全量测试 + 回归 | — | 1227+ → 1240+ 全绿 | 0.5h |

**总预估**：~4.5h（含测试与文档）

### 依赖与顺序

```
A-1 (配置) ──→ A-2 (注入) ──→ A-3 (注解)
                    │
                    └──→ A-4 (INSERT)
                    
A-1 ~ A-4 完成后 ──→ B-1 (UI) ──→ B-2 (i18n) ──→ B-3 (文档)
                                    │
                                    └──→ C (全量回归)
```

## 12. 验收标准

- [ ] 设置页「SQL 预览」组新增「多租户注入」开关 + 「租户列名」输入框
- [ ] `injectTenantLine=true` 时 SELECT/UPDATE/DELETE 追加 `AND tenant_id = ?`
- [ ] `injectTenantLine=true` 时 INSERT 列清单追加 `tenant_id`
- [ ] `injectTenantLine=false` 时不注入
- [ ] 自定义列名 `org_id` 生效
- [ ] WHERE 已有 `tenant_id` 条件时不重复注入
- [ ] `@InterceptorIgnore(tenantLine=true)` 方法内调用跳过注入
- [ ] 与 logicDelete + version 三层注入协调无冲突
- [ ] 配置持久化（xml 序列化读写一致）
- [ ] 面板 isModified/apply/reset 正确
- [ ] i18n 中英双语
- [ ] 测试增量 +13，全量 1240+ 全绿
- [ ] 文档三处同步完成
