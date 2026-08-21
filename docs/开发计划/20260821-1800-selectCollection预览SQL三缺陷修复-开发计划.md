# selectCollection 预览 SQL 三缺陷修复 — 开发计划

| 字段 | 值 |
|------|-----|
| 计划编号 | 20260821-1800 |
| 日期 | 2026-08-21 |
| 优先级 | P0（预览 SQL 错误，用户可见） |
| 状态 | 待实施 |

---

## 一、问题描述

用户代码：

```java
MPJLambdaWrapper<UserDO> wrapper = JoinWrappers.lambda(UserDO.class)
    .selectAll(UserDO.class)
    .selectCollection("user_address", AddressDO.class, UserDTO::getAddressList)
    .leftJoin(AddressDO.class, AddressDO::getUserId, UserDO::getId)
    .eq(UserDO::getId, 1);
```

当前预览输出（错误）：

```sql
SELECT t.id, t.name, t.age, t.sex, t.head_img
FROM `user` t
LEFT JOIN (
    SELECT *
    FROM address_d_o user_address          -- ❌ Bug1: 表名错误（应为 user_address）
) user_address ON t.id = addressDO.user_id  -- ❌ Bug3: 别名错误（应为 user_address.user_id）
LEFT JOIN user_address addressDO ON addressDO.user_id = t.id  -- ❌ Bug2: 重复 JOIN（应被去重）
WHERE t.id = 1
```

预期输出：

```sql
SELECT t.id, t.name, t.age, t.sex, t.head_img
FROM `user` t
LEFT JOIN (
    SELECT * FROM user_address user_address
) user_address ON user_address.user_id = t.id
WHERE t.id = 1
```

---

## 二、根因分析

### Bug 1：子查询表名推断错误（`address_d_o` → `user_address`）

**位置**：`MpjLambdaConverter.parseCollection()` 第 758 行

```kotlin
val childTable = EntityResolver.inferDecoratedTableName(childClass)
```

**根因**：`childClass` = `"AddressDO"`（原始类名字符串），`inferDecoratedTableName` 走 snake_case 推断 → `address_d_o`。但 `AddressDO` 有 `@TableName("user_address")` 注解，实际表名应为 `user_address`。

**深层原因**：PSI 层 `ArgResolver` 对 `selectCollection` 的类参（`AddressDO.class`）未做实体解析——走通用 `resolveValueArg` 路径，求值为类名字面量 `ValueArg("AddressDO")`，丢失了 `@TableName` 注解信息。对比 `leftJoin(XxxEntity.class, ...)` 有专门的 `resolveJoinClass` 解析为 `EntityRefArg`（携带 `TableInfo`）。

### Bug 2：重复 LEFT JOIN（子查询 + 普通 join 并存）

**位置**：`MpjLambdaConverter.convert()` 第 99-106 行

```kotlin
val collectionChildClasses = collectionCalls.mapNotNull { collectionChildClassOf(it) }
// ...
if (collectionChildClasses.any { cls ->
    EntityResolver.inferDecoratedTableName(cls) == join.tableName  // ❌ 比较双方语义不一致
}) continue
```

**根因**：
- 左侧：`inferDecoratedTableName("AddressDO")` = `address_d_o`（从类名推断）
- 右侧：`join.tableName` = `user_address`（来自 leftJoin 的 `EntityRefArg.tableInfo.tableName`，即 `@TableName` 真实值）
- `address_d_o` ≠ `user_address` → 去重判定失败 → 普通 join 未被过滤

**本质**：去重比较一端是「推断表名」另一端是「真实表名」，二者来源不同，永远无法匹配。

### Bug 3：ON 条件别名不一致（`addressDO.user_id` → `user_address.user_id`）

**位置**：`MpjLambdaConverter.parseCollection()` 第 766-780 行

```kotlin
val onConditions = joinCalls
    .filter { ... }  // 按类名匹配
    .flatMap { joinCall ->
        parseJoin(joinCall, mainAlias)?.onConditions?.map { cond ->
            (cond as? Condition.ColumnComparison)?.let { cc ->
                Condition.ColumnComparison(left = cc.right, right = cc.left)  // 交换左右
            } ?: cond
        } ?: emptyList()
    }
```

**根因**：ON 条件从 leftJoin 复用，leftJoin 的别名是默认值 `addressDO`（`childClass.replaceFirstChar { it.lowercaseChar() }`）。但 selectCollection 子查询的别名是 prefix `user_address`。交换左右后 ON 变为 `t.id = addressDO.user_id`，而子查询别名是 `user_address` → 别名不一致。

---

## 三、修复方案

### 修复 1：PSI 层 selectCollection/selectAssociation 类参解析为 EntityRefArg

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/psi/ArgResolver.kt`

**改动**：

1. 在 `resolveArg` 方法中，增加对 `selectCollection` / `selectAssociation` 方法的检测：
   - 当方法名为 `selectCollection` 或 `selectAssociation` 时，类参位置（prefix 形态第 2 参 / 普通形态第 1 参）走 `resolveJoinClass` 解析为 `EntityRefArg`
   - 其余参（prefix、VO getter、map lambda）保持现有解析逻辑

2. 新增常量：
   ```kotlin
   private const val SELECT_COLLECTION = "selectCollection"
   private const val SELECT_ASSOCIATION = "selectAssociation"
   ```

3. 在 `resolveMethodCallArg` 或 `resolveArg` 入口增加分派：
   ```kotlin
   if (methodName == SELECT_COLLECTION || methodName == SELECT_ASSOCIATION) {
       return resolveCollectionCall(call, table, resolveVariables)
   }
   ```

4. 新增 `resolveCollectionCall` 方法：逐参分派解析
   - prefix 形态（第 2 参为类名）：arg[0] = ValueArg（prefix 别名），arg[1] = EntityRefArg（实体解析），arg[2] = AliasArg（VO getter），arg[3] = NestedArg（map）
   - 普通形态（第 1 参为类名）：arg[0] = EntityRefArg，arg[1] = AliasArg，arg[2] = NestedArg
   - free 形态（首参为 VO 方法引用）：保持现有逻辑（不产生子查询）

### 修复 2：去重比较改为类名直接比较

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/converter/MpjLambdaConverter.kt`

**改动**：

1. `convert()` 方法第 99-106 行，将 `collectionChildClasses` 从 `String`（类名）改为同时保留 `EntityRefArg` 的 `TableInfo`（如有）：

   ```kotlin
   // 改造前：
   val collectionChildClasses = collectionCalls.mapNotNull { collectionChildClassOf(it) }
   
   // 改造后：收集类名 + TableInfo（如有）
   val collectionChildInfos = collectionCalls.mapNotNull { call ->
       // 优先从 EntityRefArg 取 TableInfo，否则用类名
       val entityRef = if (isClassNameArg(call.args.getOrNull(1))) {
           call.args.getOrNull(1) as? WrapperArg.EntityRefArg
       } else {
           call.args.getOrNull(0) as? WrapperArg.EntityRefArg
       }
       val className = collectionChildClassOf(call) ?: return@mapNotNull null
       CollectionChildInfo(className, entityRef?.tableInfo)
   }
   ```

2. 去重比较逻辑：
   ```kotlin
   // 改造前：
   if (collectionChildClasses.any { cls ->
       EntityResolver.inferDecoratedTableName(cls) == join.tableName
   }) continue
   
   // 改造后：按类名直接比较（join 的 entityClassName vs collection 的 childClass）
   if (collectionChildInfos.any { info ->
       joinEntityClassName(joinCall) == info.className
   }) continue
   ```

   其中 `joinEntityClassName` 从 join 调用的 `EntityRefArg` 或 `ValueArg` 提取类名。

### 修复 3：ON 条件别名替换为 collection 别名

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/converter/MpjLambdaConverter.kt`

**改动**：

`parseCollection()` 方法中，复用 leftJoin 的 ON 条件后，将条件中的 leftJoin 别名替换为 collection 别名：

```kotlin
val onConditions = joinCalls
    .filter { ... }  // 按类名匹配（同修复2）
    .flatMap { joinCall ->
        val joinClause = parseJoin(joinCall, mainAlias) ?: return@flatMap emptyList<Condition>()
        val joinAlias = joinClause.alias  // leftJoin 的默认别名（如 addressDO）
        joinClause.onConditions.map { cond ->
            (cond as? Condition.ColumnComparison)?.let { cc ->
                // 替换别名为 collection 别名
                val newLeft = if (cc.right.tableAlias == joinAlias) {
                    cc.right.copy(tableAlias = alias)  // alias = collection 的 prefix 别名
                } else cc.right
                val newRight = if (cc.left.tableAlias == joinAlias) {
                    cc.left.copy(tableAlias = alias)
                } else cc.left
                // 交换左右（主表列在左）
                Condition.ColumnComparison(left = newRight.copy(tableAlias = newRight.tableAlias), right = newLeft.copy(tableAlias = newLeft.tableAlias))
            } ?: cond
        } ?: emptyList()
    }
```

**注意**：需要确认 `ColumnRef` 的 `copy` 方法是否可用（Kotlin data class 自动提供）。

### 修复 4：子查询表名优先使用 EntityRefArg 的 TableInfo

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/converter/MpjLambdaConverter.kt`

**改动**：

`parseCollection()` 方法中，子查询表名获取逻辑：

```kotlin
// 改造前：
val childTable = EntityResolver.inferDecoratedTableName(childClass)

// 改造后：优先从 EntityRefArg 取真实表名，否则走推断
val childTable = collectionTableInfo(call) ?: EntityResolver.inferDecoratedTableName(childClass)
```

其中 `collectionTableInfo` 从 `selectCollection` 调用的 `EntityRefArg` 提取 `TableInfo.tableName`。

---

## 四、改动文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `psi/ArgResolver.kt` | 修改 | 新增 selectCollection/selectAssociation 类参解析为 EntityRefArg |
| `converter/MpjLambdaConverter.kt` | 修改 | 修复 2/3/4：去重比较、ON 别名替换、子查询表名优先 EntityRefArg |
| `converter/MpjJoinExtConverterTest.kt` | 修改 | 更新现有测试 + 新增三缺陷修复验证用例 |

---

## 五、测试计划

### 5.1 更新现有测试

现有 `mpjJoin_selectCollectionPrefix子查询别名` 测试使用手工构造的 `WrapperArg.ValueArg(SqlValue.Literal("OccupyConsumptionRecordEntity"))`。修复后 PSI 层将解析为 `EntityRefArg`，但手工构造的测试仍可用（converter 层已兼容两种形态）。

**决策**：保留现有手工测试不变（converter 层同时支持 `ValueArg` 和 `EntityRefArg`），新增测试覆盖 `EntityRefArg` 形态。

### 5.2 新增测试用例

| 编号 | 测试名 | 验证目标 |
|------|--------|----------|
| T1800-1 | `mpjJoin_selectCollectionEntityRefArg子查询表名正确` | Bug1 修复：EntityRefArg 携带 @TableName 时子查询表名正确 |
| T1800-2 | `mpjJoin_selectCollectionPrefix去重普通join` | Bug2 修复：selectCollection + 同子表 leftJoin 不重复渲染 |
| T1800-3 | `mpjJoin_selectCollectionPrefixOn条件别名一致` | Bug3 修复：ON 条件别名 = collection prefix 别名 |
| T1800-4 | `mpjJoin_selectCollection普通形态EntityRefArg` | 普通形态（非 prefix）EntityRefArg 兼容 |
| T1800-5 | `mpjJoin_selectAssociationEntityRefArg不产生重复join` | selectAssociation 同路径修复验证 |

### 5.3 测试 fixture

新增 `AddressTable` fixture：

```kotlin
private val addressTable = TableInfo(
    entityClassName = "AddressDO",
    tableName = "user_address",
    columns = listOf(
        ColumnInfo("id", "id", isId = true),
        ColumnInfo("userId", "user_id"),
        ColumnInfo("tel", "tel"),
        ColumnInfo("address", "address"),
        ColumnInfo("areaId", "area_id"),
        ColumnInfo("status", "status"),
    ),
)
```

---

## 六、实施步骤

1. **Step 1**：`ArgResolver.kt` — 新增 `selectCollection` / `selectAssociation` 类参解析为 `EntityRefArg`
2. **Step 2**：`MpjLambdaConverter.kt` — 修复 4（子查询表名优先 EntityRefArg）
3. **Step 3**：`MpjLambdaConverter.kt` — 修复 2（去重比较改为类名直接比较）
4. **Step 4**：`MpjLambdaConverter.kt` — 修复 3（ON 条件别名替换）
5. **Step 5**：`MpjJoinExtConverterTest.kt` — 新增 5 个测试用例
6. **Step 6**：运行全量测试确认无回归

---

## 七、风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| PSI 层改动影响 selectAssociation | selectAssociation 同路径解析 | T1800-5 覆盖验证 |
| EntityRefArg 形态与现有 ValueArg 手工测试兼容 | 手工测试可能不匹配 | converter 层同时支持两种形态（`as?` 安全转换） |
| ON 条件别名替换影响非 prefix 形态 | 非 prefix 形态 alias = joinAlias，替换无副作用 | T1800-4 覆盖验证 |
| 去重比较改为类名后，不同类名同表名场景 | 理论上不同类名不会映射同一表（MPJ 约束） | 文档化边界，不额外处理 |
