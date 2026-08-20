# IService/BaseMapper 内置方法——隐式 this 与链式调用修复

**创建时间**：2026-08-20
**优先级**：P0（用户实测阻断：gutter 图标有但预览失败 / 无图标不识别）
**影响范围**：BatchOperationConverter 实体解析 + WrapperAnchorDetector 锚点检测 + 测试

---

## 问题描述

### 问题一：`this.getById(1)` 有图标但预览失败

用户代码（在 ServiceImpl 内部直接调用）：
```java
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    public User getOne(Long id) {
        return this.getById(id);  // ← gutter 图标有，点击报"未找到 Wrapper"
    }
}
```

**症状**：
- gutter 图标正常显示 ✅（`WrapperAnchorDetector` 场景 D 正确识别 `getById` 为批量操作锚点）
- 点击预览 → 报错"未在光标处找到MyBatis-Plus Wrapper调用或IService/BaseMapper 内置方法" ❌

**根因链路**：
1. `SqlPreviewService.preview()` → WrapperLocator 返回 null（非 Wrapper 链）→ 进入批量操作兜底分支 ✅
2. `BatchOperationConverter.convert()` → `resolveEntityTable()` 解析实体失败 → 返回 null ❌

**实体解析失败的具体原因**：
- `resolveEntityTable()` 对 `this.getById(id)` 走步骤 3（service qualifier 泛型）
- `resolveServiceEntityClass()` 第 275 行：
  ```kotlin
  val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return null
  ```
- **隐式 `this` 调用时 `qualifierExpression` 为 null** → 直接返回 null ❌
- 步骤 4（`resolveQualifierEntityClass()`）同样要求 qualifier 非 null → 也返回 null ❌
- 最终 `resolveEntityTable()` 返回 null → 预览失败

### 问题二：`getBaseMapper().selectById(1)` 无图标不识别

用户代码：
```java
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    public User findOne(Long id) {
        return getBaseMapper().selectById(id);  // ← 无 gutter 图标，不识别
    }
}
```

**症状**：无 gutter 图标，无法触发预览。

**根因**（双重缺陷）：
1. **`selectById` 未注册**：`BATCH_OPERATIONS` 只有 `selectByIds`（复数），缺少 `selectById`（单数）及其他 BaseMapper 常用方法
2. **链式调用 qualifier 不支持**：即使注册了 `selectById`，`resolveServiceEntityClass()` 要求 qualifier 是 `PsiReferenceExpression`（变量引用），但 `getBaseMapper()` 是 `PsiMethodCallExpression`（方法调用）→ `as? PsiReferenceExpression` 转换失败 → 返回 null

---

## 影响范围

### 问题一影响
所有在 ServiceImpl/Mapper 内部使用 `this.getById()` / `this.save()` / `this.removeById()` 等隐式 this 调用的场景。

### 问题二影响
所有通过中间方法调用 BaseMapper/IService 方法的链式场景：
- `getBaseMapper().selectById(id)`
- `getBaseMapper().selectList(wrapper)`
- `getBaseMapper().insert(entity)`
- `this.getBaseMapper().selectById(id)`
- 任何 `mapperFactory.getMapper().selectById(id)` 形态

以及所有未注册的 BaseMapper 方法：
- `selectById`（单数，MP 核心方法）
- `selectList`、`selectMaps`、`selectObjs`、`selectCount`
- `selectPage`、`selectMapsPage`

---

## 修复方案

### 方案概述：三处改动

1. **补全 BaseMapper 方法注册表** — 新增 `selectById`/`selectList` 等方法名
2. **新增"隐式 this"实体解析路径** — 当 qualifier 为 null 时，从 containing class 的父类/接口泛型参数解析实体
3. **新增"方法调用 qualifier"实体解析路径** — 当 qualifier 是方法调用时，从方法返回类型解析实体

### 改动点 1：补全 `BATCH_OPERATIONS` 注册表

**文件**：`src/main/kotlin/.../psi/BatchOperationConverter.kt`

在 `BATCH_OPERATIONS` map 中新增 BaseMapper 核心方法：

```kotlin
// 新增 BaseMapper 核心方法（MP 3.5.x）
"selectById" to Operation.SELECT,      // T-Mxx：单条查询（WHERE 主键 = ?）
"selectList" to Operation.SELECT,      // T-Mxx：列表查询（无 WHERE）
"selectMaps" to Operation.SELECT,      // T-Mxx：Map 列表（无 WHERE）
"selectObjs" to Operation.SELECT,      // T-Mxx：Object 列表（无 WHERE）
"selectCount" to Operation.SELECT,     // T-Mxx：计数（SELECT COUNT(*)）
```

同步更新 `SINGLE_ID_SELECT_METHODS`：

```kotlin
private val SINGLE_ID_SELECT_METHODS = setOf(
    "getById", "getOptById",
    "selectById",  // 新增：与 getById 同主键等值模板
)
```

**设计决策**：
- `selectList` / `selectMaps` / `selectObjs` → 全列 SELECT（无 WHERE），走 `selectAll` 类似模板
- `selectCount` → SELECT COUNT(*)
- `selectById` → 主键等值 SELECT，与 `getById` 同模板
- 不注册 `selectPage` / `selectMapsPage`（返回 Page 类型，需要分页模板，留后续）

### 改动点 2：新增"隐式 this"实体解析

**文件**：`src/main/kotlin/.../psi/BatchOperationConverter.kt`

在 `resolveEntityTable()` 步骤 3 中，当 `resolveServiceEntityClass()` 返回 null 时，新增回退逻辑：

```kotlin
// 3. service qualifier 泛型（getById(id) 等单参非实体场景）
val entityFromService = resolveServiceEntityClass(call)
if (entityFromService != null) return EntityResolver().resolve(entityFromService)

// 3b. 隐式 this 回退（this.getById(id) —— qualifierExpression 为 null）
val entityFromImplicitThis = resolveEntityFromImplicitThis(call)
if (entityFromImplicitThis != null) return EntityResolver().resolve(entityFromImplicitThis)
```

新增函数 `resolveEntityFromImplicitThis()`：

```kotlin
/**
 * 隐式 this 调用的实体解析（T-Mxx）：
 * 当 `this.getById(id)` 时 qualifierExpression 为 null，从 containing class 的
 * 父类/接口泛型参数解析实体（ServiceImpl<M, T> 的 T、IService<T> 的 T）。
 *
 * 高置信度：方法名已在 BATCH_OPERATIONS 白名单中 + 调用者自身是 ServiceImpl/IService 子类。
 */
private fun resolveEntityFromImplicitThis(call: PsiMethodCallExpression): PsiClass? {
    // 仅当 qualifierExpression 为 null（隐式 this）时触发
    if (call.methodExpression.qualifierExpression != null) return null
    val containingClass = call.methodExpression.containingClass ?: return null
    // 沿父类/接口链取最后一个类型参数（ServiceImpl<M, User> → User）
    for (superType in containingClass.superTypes) {
        val params = superType.parameters
        val candidate = params.lastOrNull() as? PsiClassType ?: continue
        val resolved = candidate.resolve() ?: continue
        val qualifiedName = resolved.qualifiedName ?: continue
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) continue
        return resolved
    }
    return null
}
```

**关键设计**：
- 仅当 `qualifierExpression` 为 null 时触发（避免误伤有显式 qualifier 的调用）
- 取 `superTypes.lastOrNull()` 与 `resolveServiceEntityClass()` 步骤 3b 逻辑一致
- 排除 `java.`/`javax.` 包前缀（防基础类型误判）
- 不做 `isLikelyEntity` 限定——方法名在白名单中 + containing class 是 service 子类 → 高置信度

### 改动点 3：新增"方法调用 qualifier"实体解析

**文件**：`src/main/kotlin/.../psi/BatchOperationConverter.kt`

在 `resolveEntityTable()` 步骤 3b（隐式 this 回退）之后，新增步骤 3c：

```kotlin
// 3c. 方法调用 qualifier 回退（getBaseMapper().selectById(id) —— qualifier 是方法调用）
val entityFromMethodQualifier = resolveEntityFromMethodQualifier(call)
if (entityFromMethodQualifier != null) return EntityResolver().resolve(entityFromMethodQualifier)
```

新增函数 `resolveEntityFromMethodQualifier()`：

```kotlin
/**
 * 方法调用 qualifier 的实体解析（T-Mxx）：
 * 当 `getBaseMapper().selectById(id)` 时 qualifier 是 PsiMethodCallExpression，
 * 从方法返回类型解析实体（BaseMapper<User> 的 U → User）。
 *
 * 适用：getBaseMapper() / getMapper() / mapperFactory.getMapper() 等返回 Mapper 类型的方法。
 */
private fun resolveEntityFromMethodQualifier(call: PsiMethodCallExpression): PsiClass? {
    val qualifier = call.methodExpression.qualifierExpression as? PsiMethodCallExpression ?: return null
    // 解析 qualifier 方法的返回类型
    val returnType = qualifier.methodExpression.type ?: return null
    val classType = returnType as? PsiClassType ?: return null
    // 返回类型自身泛型参数（BaseMapper<User> → User）
    val entityType = classType.parameters.firstOrNull() as? PsiClassType ?: return null
    val resolved = entityType.resolve() ?: return null
    val qualifiedName = resolved.qualifiedName ?: return null
    if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) return null
    return resolved
}
```

**关键设计**：
- 仅当 qualifier 是 `PsiMethodCallExpression` 时触发
- 从 qualifier 方法的返回类型取首泛型参数（`BaseMapper<User>` → `User`）
- 排除 `java.`/`javax.` 包前缀
- 不做 `isLikelyEntity` 限定——方法名在白名单中 + qualifier 返回 Mapper 类型 → 高置信度

### 改动点 4：`resolveServiceEntityClass()` 内部增强

**文件**：`src/main/kotlin/.../psi/BatchOperationConverter.kt`

当前 `resolveServiceEntityClass()` 第 275 行：
```kotlin
val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return null
```

此函数仅在 qualifier 是变量引用时有效。对于隐式 this 和方法调用 qualifier，由改动点 2/3 在外层处理，本函数保持不变（单一职责）。

---

## 实施步骤

### 步骤 1：新增测试用例（TDD 先行）

在 `SqlPreviewServiceTest.kt` 新增以下测试：

#### 测试 1：`this.getById(id)` 隐式 this 调用

```kotlin
fun testImplicitThisGetById() {
    // 在 ServiceImpl 内部 this.getById(id) —— 隐式 this，qualifierExpression 为 null
    myFixture.configureByText(
        "Demo.java",
        """
        public class Demo {
            static class User {
                private Long id;
                private String name;
            }
            static class UserServiceImpl extends BaseService<User> {
                public User getOne(Long id) {
                    return this.getById(id);
                }
            }
            static class BaseService<T> {
                public T getById(Long id) { return null; }
            }
        }
        """.trimIndent(),
    )
    val result = newService().preview(anchor())
    assertNotNull("this.getById(id) 应产出预览结果", result)
    assertTrue("隐式 this.getById 应转 SELECT", result!!.sql.startsWith("SELECT"))
    assertTrue("应包含 WHERE id = ?", result.sql.contains("WHERE id = ?"))
}
```

#### 测试 2：`getBaseMapper().selectById(id)` 链式调用

```kotlin
fun testGetBaseMapperSelectById() {
    // getBaseMapper().selectById(id) —— qualifier 是方法调用
    myFixture.configureByText(
        "Demo.java",
        """
        public class Demo {
            static class User {
                private Long id;
                private String name;
            }
            static class UserMapper {
                public User selectById(Long id) { return null; }
            }
            static class UserServiceImpl {
                public UserMapper getBaseMapper() { return null; }
                public User findOne(Long id) {
                    return getBaseMapper().selectById(id);
                }
            }
        }
        """.trimIndent(),
    )
    val result = newService().preview(anchor())
    assertNotNull("getBaseMapper().selectById(id) 应产出预览结果", result)
    assertTrue("selectById 应转 SELECT", result!!.sql.startsWith("SELECT"))
    assertTrue("应包含 WHERE id = ?", result.sql.contains("WHERE id = ?"))
}
```

#### 测试 3：`getBaseMapper().selectList(wrapper)` 全列查询

```kotlin
fun testGetBaseMapperSelectList() {
    myFixture.configureByText(
        "Demo.java",
        """
        public class Demo {
            static class User {
                private Long id;
                private String name;
            }
            static class UserMapper {
                public java.util.List<User> selectList(Object wrapper) { return null; }
            }
            static class UserServiceImpl {
                public UserMapper getBaseMapper() { return null; }
                public java.util.List<User> findAll() {
                    return getBaseMapper().selectList(null);
                }
            }
        }
        """.trimIndent(),
    )
    val result = newService().preview(anchor())
    assertNotNull("getBaseMapper().selectList() 应产出预览结果", result)
    assertTrue("selectList 应转 SELECT", result!!.sql.startsWith("SELECT"))
    assertTrue("应包含 FROM user", result.sql.contains("FROM user"))
}
```

#### 测试 4：`this.save(entity)` 隐式 this 插入

```kotlin
fun testImplicitThisSave() {
    myFixture.configureByText(
        "Demo.java",
        """
        public class Demo {
            static class User {
                private Long id;
                private String name;
            }
            static class UserServiceImpl extends BaseService<User> {
                public boolean create(User user) {
                    return this.save(user);
                }
            }
            static class BaseService<T> {
                public boolean save(T entity) { return true; }
            }
        }
        """.trimIndent(),
    )
    val result = newService().preview(anchor())
    assertNotNull("this.save(entity) 应产出预览结果", result)
    assertTrue("save 应转 INSERT", result!!.sql.startsWith("INSERT"))
}
```

**预期**：以上 4 个测试在当前代码下**全部失败**（assertNotNull 不通过），修复后通过。

### 步骤 2：补全 `BATCH_OPERATIONS` 注册表

在 `BatchOperationConverter.kt`：
1. `BATCH_OPERATIONS` map 新增 5 个方法
2. `SINGLE_ID_SELECT_METHODS` 新增 `"selectById"`

### 步骤 3：新增 `resolveEntityFromImplicitThis()` 函数

在 `BatchOperationConverter.kt` 新增函数 + 在 `resolveEntityTable()` 步骤 3 后调用。

### 步骤 4：新增 `resolveEntityFromMethodQualifier()` 函数

在 `BatchOperationConverter.kt` 新增函数 + 在 `resolveEntityTable()` 步骤 3b 后调用。

### 步骤 5：全量测试

```bash
./gradlew clean test
```

预期：
- 4 个新增测试通过
- 存量测试不受影响（原有实体解析路径优先执行）

### 步骤 6：提交

按 commit-rhythm 约定：增量测试 → 全量 → 全绿后提交。

---

## 验证清单

- [ ] `this.getById(id)` — 隐式 this → SELECT 预览成功
- [ ] `this.save(entity)` — 隐式 this → INSERT 预览成功
- [ ] `this.removeById(id)` — 隐式 this → DELETE 预览成功
- [ ] `getBaseMapper().selectById(id)` — 链式调用 → SELECT 预览成功
- [ ] `getBaseMapper().selectList(wrapper)` — 链式调用 → SELECT 全列预览成功
- [ ] `getBaseMapper().insert(entity)` — 链式调用 → INSERT 预览成功
- [ ] `userService.getById(id)` — 原有路径不受影响
- [ ] `new LambdaQueryWrapper<X>().eq(...)` — Wrapper 链不受影响
- [ ] gutter 图标在 `this.getById` / `getBaseMapper().selectById` 行正常显示
- [ ] 存量 1227 测试全绿

---

## 风险评估

- **低风险**：新增注册表条目和实体解析路径不影响原有路径（原有路径在 `resolveEntityTable()` 中优先执行）
- **低风险**：`resolveEntityFromImplicitThis()` 仅当 qualifier 为 null 时触发，不会误伤显式 qualifier 调用
- **低风险**：`resolveEntityFromMethodQualifier()` 仅当 qualifier 是方法调用时触发，不会误伤变量引用
- **无回归**：所有存量测试使用含 "Entity" 后缀的实体类，走原有路径不受影响

---

## 附录：实体解析路径完整顺序（修复后）

```
resolveEntityTable(call):
  1. 集合参数泛型（List<Entity>）
  2. 实体参数类型（save(entity) / updateById(entity)）
  3. service qualifier 泛型（service.getById(id) —— qualifier 是变量引用）
     3b. 隐式 this 回退（this.getById(id) —— qualifier 为 null）
     3c. 方法调用 qualifier 回退（getBaseMapper().selectById(id) —— qualifier 是方法调用）
  4. qualifier 实体类型（ActiveRecord entity.insert()）
```
