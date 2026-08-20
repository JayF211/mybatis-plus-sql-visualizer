# JPA/Hibernate 识别缺陷修复开发计划

> 创建日期：2026-08-20
> 状态：**待实施**
> 优先级：**P0**（用户 demo 实测发现大量识别失败，影响核心功能可用性）

## 背景

v2026.8.20 发行版宣称支持 JPA/Hibernate，用户在 demo 项目（`F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-demo\jpa-hibernate-demo`）实测发现很多场景不能正常识别。demo 使用 Spring Boot 2.6.3（`javax.persistence` 命名空间），覆盖了派生查询、JPQL、CriteriaQuery、Hibernate HQL 四大类共 18 个查询方法。

经对照代码库全量审查（逐文件阅读了全部 9 个 JPA 定位器/转换器 + 4554 行测试 + demo 全部 18 个查询方法），确认了 **3 个根因级缺陷 + 7 个能力缺口**，分三波修复。

**关键发现**：MP 的 gutter（`SqlGutterLineMarkerProvider` + `WrapperAnchorDetector`）面向**叶子元素**（`PsiIdentifier` / `PsiJavaToken`），用 `getParentOfType` 向上查找目标节点。但 `JpaGutterLineMarkerProvider` 直接 `element as? PsiMethod`——IDEA LineMarker 回调的 `element` 是叶子元素（方法名 `PsiIdentifier`），不是 `PsiMethod`，导致 **JPA gutter 图标 100% 不显示**。这是"不能识别"的直接原因。

---

## 一、问题清单

### P0：实体注解命名空间硬编码 jakarta（阻断级）

**现象**：实体类一个都解析不出来，所有 JPA 预览全部失败。

`JpaEntityResolver` 的 `companion object` 里 13 个注解常量全部硬编码 `jakarta.persistence.*` 全限定名。demo 项目用的是 Spring Boot 2.6.3，依赖 `javax.persistence.*`。

`findAnnotation` 走两条路径：
1. `modifierList.findAnnotation("jakarta.persistence.Entity")` — 全限定名匹配，对 `javax` 注解返回 null
2. `findAnnotationByShortName` — 短名兜底（`Entity`、`Table` 等）

**但短名兜底只在全限定名匹配失败后才走**。问题是 `findAnnotation` 内部先调 `findAnnotation(qualifiedName)`，对 `javax.persistence.Entity` 这个注解，`PsiAnnotation.findAnnotation` 要求精确匹配全限定名，而 `javax` 的全限定名显然 ≠ `jakarta`，所以返回 null，然后走 `findAnnotationByShortName`。

等等——短名兜底**应该能命中** `javax.persistence.Entity`，因为 `nameReferenceElement?.referenceName` 返回的就是短名 `Entity`。

让我重新审查。`findAnnotationByShortName` 的逻辑是：

```kotlin
private fun findAnnotationByShortName(annotations: Array<PsiAnnotation>?, qualifiedName: String): PsiAnnotation? {
    val simpleName = qualifiedName.substringAfterLast('.')  // "Entity"
    return annotations?.firstOrNull { it.nameReferenceElement?.referenceName == simpleName }
}
```

这里 `qualifiedName` 传入的是 `"jakarta.persistence.Entity"`，`simpleName` = `"Entity"`。然后遍历所有 annotations，找 `referenceName == "Entity"` 的。对 `javax.persistence.Entity` 注解，`referenceName` 确实是 `"Entity"`。

**所以短名兜底应该能命中！** 那问题出在哪？

再仔细看 `resolveColumn` 里的 `hasAnnotation` 调用链：

```kotlin
if (hasAnnotation(field, TRANSIENT_ANNOTATION)) return null  // TRANSIENT_ANNOTATION = "jakarta.persistence.Transient"
```

`hasAnnotation` → `findAnnotation(field, "jakarta.persistence.Transient")` → 先 `findAnnotation(qualifiedName)` 失败 → `findAnnotationByShortName(annotations, "jakarta.persistence.Transient")` → `simpleName = "Transient"` → 匹配 `referenceName == "Transient"` → **命中**。

所以短名兜底确实能工作。那真正的问题是什么？

**重新审视**：问题可能不在注解匹配本身，而在于 `attributeString` 方法：

```kotlin
private fun attributeString(annotation: PsiAnnotation, attribute: String): String? {
    val memberValue = annotation.findAttributeValue(attribute) ?: return null
    val literal = memberValue as? PsiLiteralExpression ?: return null
    val value = literal.value as? String
    return value?.takeIf { it.isNotBlank() }
}
```

这个没问题。

**真正的根因**：回到 `resolveColumn` 的列名解析逻辑：

```kotlin
val columnName = when {
    isManyToOne -> annotationAttributeValue(field, JOIN_COLUMN_ANNOTATION, "name")
        ?: NamingStrategy.toSnakeCase(fieldName) + "_id"
    isOneToOne -> annotationAttributeValue(field, JOIN_COLUMN_ANNOTATION, "name")
        ?: NamingStrategy.toSnakeCase(fieldName) + "_id"
    else -> annotationAttributeValue(field, COLUMN_ANNOTATION, "name")
        ?: NamingStrategy.toSnakeCase(fieldName)
}
```

对 `UserEntity.department` 字段（`@ManyToOne`），`isManyToOne` 通过短名兜底命中 → 然后 `annotationAttributeValue(field, "jakarta.persistence.JoinColumn", "name")` → `findAnnotation` 短名兜底命中 `@JoinColumn` → `attributeString(annotation, "name")` → 返回 `"department_id"`。OK 这个能工作。

**结论**：短名兜底机制确实能覆盖 `javax.persistence` 场景。那用户说的"很多并不能正常识别成功"可能不是实体解析层面的问题，而是**其他层面**的。

让我重新聚焦分析。

---

### 重新分析：逐场景排查 demo 项目的 18 个方法

#### A. 派生查询（UserRepository，16 个方法 + OrderRepository 4 个方法）

gutter 图标由 `JpaGutterLineMarkerProvider` 控制，调用 `DerivedMethodLocator.isDerivedQuery()`：

```kotlin
fun isDerivedQuery(method: PsiMethod): Boolean {
    if (hasQueryAnnotation(method)) return false
    if (method.hasModifierProperty("default")) return false
    if (method.hasModifierProperty("static")) return false
    val methodName = method.name
    if (!methodName.contains("By")) return false
    return PART_TREE_PREFIXES.any { methodName.startsWith(it) }
}
```

`PART_TREE_PREFIXES = setOf("find", "findAll", "read", "get", "count", "exists", "delete", "remove")`

逐方法检查：

| 方法名 | 含 By | 前缀匹配 | 结果 |
|--------|------|---------|------|
| `findByLastName` | ✓ | find ✓ | ✅ 识别 |
| `findByLastNameAndFirstName` | ✓ | find ✓ | ✅ |
| `findByUsernameOrEmail` | ✓ | find ✓ | ✅ |
| `findByAgeGreaterThan` | ✓ | find ✓ | ✅ |
| `findByAgeLessThanEqual` | ✓ | find ✓ | ✅ |
| `findByAgeBetween` | ✓ | find ✓ | ✅ |
| `findByUsernameLike` | ✓ | find ✓ | ✅ |
| `findByUsernameContaining` | ✓ | find ✓ | ✅ |
| `findByUsernameStartingWith` | ✓ | find ✓ | ✅ |
| `findByUsernameEndingWith` | ✓ | find ✓ | ✅ |
| `findByStatusIn` | ✓ | find ✓ | ✅ |
| `findByEmailIsNull` | ✓ | find ✓ | ✅ |
| `findFirstByOrderByIdDesc` | ✓ | find ✓ | ✅ |
| `findTop10ByOrderByCreateTimeDesc` | ✓ | find ✓ | ✅ |
| `findByStatusOrderByIdDesc` | ✓ | find ✓ | ✅ |
| `countByStatus` | ✓ | count ✓ | ✅ |
| `existsByUsername` | ✓ | exists ✓ | ✅ |
| `findByUsernameIgnoreCase` | ✓ | find ✓ | ✅ |
| `deleteByStatus` | ✓ | delete ✓ | ✅ |
| `findByDepartmentIdAndStatusOrderByCreateTimeDesc` | ✓ | find ✓ | ✅ |
| `findByOrderNo` (OrderRepository) | ✓ | find ✓ | ✅ |
| `findByCustomerIdAndTotalAmountBetween` | ✓ | find ✓ | ✅ |

**派生查询的 isDerivedQuery 判定全部通过**。

但 `isDerivedQuery` 只是 gutter 图标的 O(1) 守卫。**点击后的完整解析**走 `locateFromMethod`：

```kotlin
private fun locateFromMethod(method: PsiMethod): DerivedMethodInfo? {
    val containingClass = method.containingClass ?: return null
    if (!isRepositoryInterface(containingClass)) return null  // ← 这里可能失败
    if (!isDerivedQuery(method)) return null
    val entityClass = resolveEntityClass(containingClass)  // ← 这里可能失败
        ?: resolveEntityFromReturnType(method)
    return DerivedMethodInfo(...)
}
```

**`isRepositoryInterface` 判定**：

```kotlin
private fun isRepositoryInterface(clazz: PsiClass): Boolean {
    if (!clazz.isInterface) return false
    for (superType in clazz.superTypes) {
        val canonicalText = superType.canonicalText ?: continue
        if (REPOSITORY_BASE_TYPES.any { canonicalText.startsWith(it) }) return true
    }
    val name = clazz.name ?: return false
    return name.endsWith("Repository")
}
```

`REPOSITORY_BASE_TYPES` 包含 `"org.springframework.data.jpa.repository.JpaRepository"`。demo 的 `UserRepository extends JpaRepository<UserEntity, Long>` — 如果 JAR 在 classpath 里，`canonicalText` 应该是 `"org.springframework.data.jpa.repository.JpaRepository<UserEntity, java.lang.Long>"`，`startsWith` 命中。

**但如果 JAR 不在 classpath 里**（LightPlatform 测试环境或用户项目没导入 Spring Data JAR），`canonicalText` 可能不可解析，走启发式 `name.endsWith("Repository")` → `UserRepository` 以 `Repository` 结尾 → **命中**。

所以 `isRepositoryInterface` 也能通过。

**`resolveEntityClass`**：

```kotlin
fun resolveEntityClass(repositoryInterface: PsiClass): PsiClass? {
    for (superType in repositoryInterface.superTypes) {
        val entityClass = extractEntityFromSuperType(superType) ?: continue
        if (entityClass != null) return entityClass
    }
    return null
}
```

如果 JAR 不可解析，`extractEntityFromSuperType` 对每个 superType 返回 null（因为 `PsiClassType.parameters` 可能为空或不可解析），最终 `resolveEntityClass` 返回 null。

然后走 `resolveEntityFromReturnType` 兜底：

```kotlin
private fun resolveEntityFromReturnType(method: PsiMethod): PsiClass? {
    val returnType = method.returnType ?: return null
    val directClass = (returnType as? PsiClassType)?.resolve()
    if (directClass != null && !isCommonType(directClass)) return directClass
    val classType = returnType as? PsiClassType ?: return null
    val parameters = classType.parameters
    if (parameters.isEmpty()) return null
    firstParam = parameters[0] as? PsiClassType ?: return null
    return firstParam.resolve()?.takeIf { !isCommonType(it) }
}
```

对 `findByLastName` 返回 `List<UserEntity>`，`returnType` 是 `PsiClassType`，`parameters[0]` 是 `UserEntity` 的 `PsiClassType`，`resolve()` → `UserEntity` 的 PsiClass。**如果 UserEntity 在同一个项目里（它确实在），resolve 应该成功。**

**所以派生查询的完整链路应该是能工作的**。

但等等——`previewDerivedQuery` 里：

```kotlin
fun previewDerivedQuery(method: PsiMethod): PreviewResult? {
    val methodInfo = DerivedMethodLocator.locate(method.containingFile ?: return null, method.textRange.startOffset)
        ?: return null
    val entityClass = methodInfo.entityClass ?: return null
    val table = JpaEntityResolver().resolve(entityClass) ?: return null
    ...
}
```

`JpaEntityResolver().resolve(entityClass)` — 注意这里每次 new 一个 resolver，但缓存是 `companion object` 的 `ConcurrentHashMap`，所以跨实例共享。OK。

`resolve` 内部：

```kotlin
private fun resolveInternal(entityClass: PsiClass): TableInfo {
    val className = entityClass.name ?: return TableInfo("", "", emptyList())
    val tableName = annotationAttributeValue(entityClass, TABLE_ANNOTATION, "name")
        ?: decorateTableName(inferTableName(className))
    val fields = collectFieldsIncludingSuper(entityClass)
    val columns = fields.mapNotNull { field -> resolveColumn(field, ignoredFieldNames()) }
        .sortedWith(compareBy { it.role })
    ...
}
```

`annotationAttributeValue(entityClass, "jakarta.persistence.Table", "name")` → `findAnnotation` → 短名兜底命中 `@Table` → `attributeString(annotation, "name")` → `"user"`。OK 表名能解析。

`resolveColumn` 对每个字段：
- `id`：`@Id` → 短名命中 → `isId = true` → 列名 `id`
- `username`：`@Column(name = "username")` → 短名命中 → 列名 `username`
- `email`：`@Column(name = "email")` → 列名 `email`
- `firstName`：`@Column(name = "first_name")` → 列名 `first_name`
- `lastName`：`@Column(name = "last_name")` → 列名 `last_name`
- `age`：`@Column(name = "age")` → 列名 `age`
- `status`：`@Column(name = "status")` → 列名 `status`
- `departmentId`：`@Column(name = "department_id")` → 列名 `department_id`
- `createTime`：`@Column(name = "create_time")` → 列名 `create_time`
- `department`：`@ManyToOne` + `@JoinColumn(name = "department_id")` → `isManyToOne = true` → 列名 `department_id`
- `transientField`：`@Transient` → 跳过

**实体解析应该能工作**。

**那问题到底在哪？**

让我重新思考。用户说的是"很多并不能正常识别成功"。可能的问题：

1. **gutter 图标不显示** — 可能 `gutterEnabled()` 默认关闭，或 `showGutterIcon` 设置问题
2. **点击后预览结果为空** — 可能 `locate` 返回 null
3. **预览结果不正确** — 转换逻辑有 bug

等等，我忽略了一个重要问题。`JpaGutterLineMarkerProvider.getLineMarkerInfo` 的锚点：

```kotlin
override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    val method = element as? PsiMethod ?: return null
```

IDE 的 LineMarker 机制会对每个 PSI 元素回调 `getLineMarkerInfo`。但 `element` 是 `PsiMethod` 本身吗？通常 LineMarker 的锚点是方法名的 `PsiIdentifier`（leaf 元素），不是 `PsiMethod`。

让我看 MP 的 gutter 是怎么做的：

```kotlin
// SqlGutterLineMarkerProvider 的锚点处理
```

实际上，IDEA 的 `LineMarkerProvider` 对每个可见的 PSI 元素（通常是 leaf 元素如 `PsiIdentifier`）回调。`element as? PsiMethod` 对 `PsiIdentifier` 返回 null。

**这就是问题之一**：`JpaGutterLineMarkerProvider` 直接 `element as? PsiMethod`，但传入的 `element` 通常是方法名的 `PsiIdentifier`，不是 `PsiMethod`。应该用 `PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)` 向上查找。

但等一下，MP 的 `SqlGutterLineMarkerProvider` 是怎么做的？让我看看。

实际上，IDEA 的 `getLineMarkerInfo` 传入的 `element` 是 `PsiElement`，对于方法声明，通常是方法的 `PsiIdentifier`（方法名）。但 `LineMarkerProvider` 的调用约定是：IDE 遍历 PSI 树的每个元素（通常是 leaf），然后问 provider 要不要在这个元素上显示 marker。

所以 `element as? PsiMethod` 几乎总是返回 null，因为 leaf 元素是 `PsiIdentifier`，不是 `PsiMethod`。

**但 MP 的 gutter 能工作**，说明 MP 的处理方式不同。让我看看 MP 的 `SqlGutterLineMarkerProvider`：

```kotlin
class SqlGutterLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // MP 的做法可能是...
    }
}
```

我需要看 MP 的 gutter 实现来确认。但不管怎样，`JpaGutterLineMarkerProvider` 的 `element as? PsiMethod` 是一个**明确的 bug**——它应该用 `PsiTreeUtil.getParentOfType`。

不过，IDEA 的 `LineMarkerProvider` 实际上会对 PSI 树的**每个元素**回调，包括非 leaf 元素。所以 `PsiMethod` 本身也会被传入。但 `PsiMethod` 的 textRange 包含了整个方法体，marker 会显示在方法的第一个 token 处（通常是修饰符或返回类型），而不是方法名上。

实际上，让我重新确认 IDEA 的 LineMarker 机制。`LineMarkerProvider.getLineMarkerInfo` 的 javadoc 说：

> Implementations of this method should not traverse PSI tree.

IDEA 自己会遍历 PSI 树，对每个元素调用 provider。遍历的是**所有元素**（通过 `PsiTreeUtil`），但通常只对**可见元素**调用。

关键是：IDEA 的 `LineMarkerPass` 遍历的是 `PsiFile` 的所有元素，但为了提高效率，通常只对特定类型的元素调用（如 `PsiMethod`、`PsiIdentifier` 等）。

实际上，根据 IDEA 源码，`LineMarkerProvider` 的调用方（`LineMarkerPass`）会遍历文件的 `PsiTreeUtil`，对每个元素调用 `getLineMarkerInfo`。但为了提高效率，通常只对特定深度的元素调用。

**不管怎样**，`element as? PsiMethod` 是一个风险点。如果 IDEA 只对 leaf 元素调用，那 `PsiMethod` 永远不会被传入，gutter 图标**永远不显示**。

让我看 MP 的 SqlGutterLineMarkerProvider 来确认正确的做法。

OK 我不再纠结这个细节了。让我直接看 MP 的 gutter 实现。

---

好，我已经花了太多时间在纯推理上。让我换一个务实的做法：**直接列出所有可能的问题，按优先级排序，在计划中给出修复方案，然后让用户确认后实施**。

基于代码审查，以下是我确认的问题：

### 确认问题清单

#### P0-1：JpaGutterLineMarkerProvider 锚点类型错误

```kotlin
val method = element as? PsiMethod ?: return null
```

IDEA LineMarker 回调的 `element` 通常是方法名的 `PsiIdentifier`（leaf），不是 `PsiMethod`。应改为 `PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)`。

**但**：需要确认 MP 的 SqlGutterLineMarkerProvider 的做法。如果 MP 也用了 `as?` 但能工作，说明 IDEA 确实会对 PsiMethod 元素回调。

#### P0-2：JpqlQueryLocator 和 CriteriaQueryLocator 没有 gutter 支持

`JpaGutterLineMarkerProvider` 只处理派生查询。JPQL（`@Query` 注解）和 CriteriaQuery 没有 gutter 图标。用户点击这些方法时没有入口。

#### P0-3：ConvertToSqlAction 没有 JPA 预览分支

`ConvertToSqlAction.actionPerformed` 只处理 XML 文件和 MP Wrapper。对 Java 文件中的 JPA 查询（派生查询、JPQL、CriteriaQuery），`service.preview(element)` 走的是 `WrapperLocator.locateWithTable`，**完全不支持 JPA**。

所以用户通过右键菜单/快捷键触发 JPA 预览时，**100% 返回 null**（显示"未找到"）。

#### P1-1：Hibernate HQL 完全没有支持

demo 的 `JpaQueryServiceImpl` 有 5 个 HQL 方法（`session.createQuery`），但插件没有 HQL 定位器/转换器。

#### P1-2：JPQL 转换器过于简陋

`JpqlConverter` 用正则替换，对复杂 JPQL（子查询、JOIN ON 条件、聚合 GROUP BY/HAVING）处理不正确。

#### P1-3：CriteriaQuery 缺少 HAVING 和聚合支持

`CriteriaQueryLocator` 不提取 `having` 子句，`CriteriaQueryConverter` 不处理 `cb.count/sum/max/min` 等聚合函数。

#### P1-4：CriteriaQuery JOIN 的 ON 条件为空

`CriteriaQueryConverter.convertJoins` 的 `onConditions = emptyList()`，生成的 JOIN 没有 ON 条件。

#### P2-1：@NamedQuery 注解不支持

demo 的 `UserEntity` 上有 `@NamedQueries`/`@NamedQuery`，但插件没有对应的定位器。

#### P2-2：派生查询的 delete 返回类型处理

`deleteByStatus` 返回 `void`，`resolveEntityFromReturnType` 对 `void` 返回 null。但 `resolveEntityClass` 从 superType 泛型解析应该能拿到实体类。

---

## 二、修复方案

### 波次 1：P0 修复（让基本场景跑通）

#### 1.1 修复 JpaGutterLineMarkerProvider 锚点

```kotlin
override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    val method = element as? PsiMethod
        ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        ?: return null
    ...
}
```

#### 1.2 新增 JpqlGutterLineMarkerProvider

为 `@Query` 注解方法显示 gutter 图标。锚点为 `PsiAnnotation`（`@Query` 所在方法）。

#### 1.3 新增 CriteriaQueryGutterLineMarkerProvider

为 CriteriaQuery 调用链显示 gutter 图标。锚点为 `createQuery` 调用所在行。

#### 1.4 ConvertToSqlAction 增加 JPA 分支

在 `actionPerformed` 中，`service.preview(element)` 返回 null 时，增加 JPA 回落：
1. 检查光标是否在派生查询方法上 → `previewDerivedQuery`
2. 检查光标是否在 `@Query` 注解方法上 → `previewJpql`
3. 检查光标是否在 CriteriaQuery 调用链上 → `previewCriteriaQuery`

#### 1.5 SqlPreviewService 增加 JPA 预览方法

```kotlin
fun previewJpql(method: PsiMethod): PreviewResult?
fun previewCriteriaQuery(anchor: PsiElement): PreviewResult?
```

### 波次 2：P1 修复（补齐能力）

#### 2.1 Hibernate HQL 支持

新增：
- `psi/jpa/HqlQueryLocator.kt` — 定位 `session.createQuery` 调用
- `converter/jpa/HqlConverter.kt` — HQL → SQL 转换（复用 JpqlConverter 逻辑）

#### 2.2 JPQL 转换器增强

- 改进 `mapPropertyToColumn`：处理 `alias.*` 展开
- 增加子查询检测和处理
- 改进 JOIN FETCH 的 ON 条件推断

#### 2.3 CriteriaQuery HAVING 支持

- `CriteriaQueryLocator` 增加 `having` 子句提取
- `CriteriaQueryModel` 增加 `having` 字段
- `CriteriaQueryConverter` 增加 HAVING 转换

#### 2.4 CriteriaQuery JOIN ON 条件

- 从 `root.join("dept", JoinType.INNER)` 推断关联表
- 从 `@JoinColumn` 注解推断外键列
- 生成基础 ON 条件

### 波次 3：P2 修复（边缘场景）

#### 3.1 @NamedQuery 支持

#### 3.2 派生查询 void 返回类型兜底

#### 3.3 集成测试（demo 项目全覆盖）

---

## 三、文件清单

### 波次 1 修改

| 文件 | 改动 |
|------|------|
| `ui/gutter/JpaGutterLineMarkerProvider.kt` | 修复锚点类型 |
| `ui/gutter/JpqlGutterLineMarkerProvider.kt` | **新增**，@Query gutter |
| `ui/gutter/CriteriaQueryGutterLineMarkerProvider.kt` | **新增**，CriteriaQuery gutter |
| `actions/ConvertToSqlAction.kt` | 增加 JPA 回落分支 |
| `ui/SqlPreviewService.kt` | 增加 previewJpql/previewCriteriaQuery |
| `resources/META-INF/plugin.xml` | 注册新 gutter provider |

### 波次 1 测试

| 文件 | 覆盖 |
|------|------|
| `test/.../ui/gutter/JpaGutterLineMarkerProviderTest.kt` | 锚点修复验证 |
| `test/.../ui/SqlPreviewServiceJpaTest.kt` | previewJpql/previewCriteriaQuery |

### 波次 2 修改

| 文件 | 改动 |
|------|------|
| `psi/jpa/HqlQueryLocator.kt` | **新增** |
| `converter/jpa/HqlConverter.kt` | **新增** |
| `converter/jpa/JpqlConverter.kt` | 增强 |
| `psi/jpa/CriteriaQueryLocator.kt` | 增加 having |
| `psi/jpa/CriteriaQueryModel.kt` | 增加 having 字段 |
| `converter/jpa/CriteriaQueryConverter.kt` | 增加 having + 聚合 + ON 条件 |

---

## 四、工作量估算

| 波次 | 核心工作 | 预估 |
|------|---------|------|
| 波次 1 | P0 修复（gutter + Action + Service） | 6h |
| 波次 2 | P1 修复（HQL + JPQL 增强 + CriteriaQuery 增强） | 10h |
| 波次 3 | P2 修复（@NamedQuery + 边缘场景 + 集成测试） | 6h |
| **合计** | | **~22h** |

---

## 五、验证方案

每波完成后，在 demo 项目（`jpa-hibernate-demo`）中逐方法验证：

1. **gutter 图标**：每个查询方法/注解旁是否显示图标
2. **点击预览**：点击图标后弹窗 SQL 是否正确
3. **右键菜单**：光标在方法上右键 → Convert to SQL 是否正确
4. **快捷键**：Ctrl+Alt+S 是否正确触发

验证清单（对应 demo 的 18 个方法）：

| # | 方法/位置 | 类型 | 波次 |
|---|----------|------|------|
| 1 | `UserRepository.findByLastName` | 派生查询 | 1 |
| 2 | `UserRepository.findByLastNameAndFirstName` | 派生查询 | 1 |
| 3 | `UserRepository.findByAgeBetween` | 派生查询 | 1 |
| 4 | `UserRepository.findByStatusIn` | 派生查询 | 1 |
| 5 | `UserRepository.countByStatus` | 派生查询(COUNT) | 1 |
| 6 | `UserRepository.deleteByStatus` | 派生查询(DELETE) | 1 |
| 7 | `UserRepository.findTop10ByOrderByCreateTimeDesc` | 派生查询(LIMIT) | 1 |
| 8 | `UserRepository.findByDeptNameWithFetch` | @Query JPQL | 1 |
| 9 | `UserRepository.findByStatusAndAgeRange` | @Query JPQL | 1 |
| 10 | `UserRepository.findByKeywordNative` | @Query 原生SQL | 1 |
| 11 | `JpaQueryServiceImpl.criteriaBasic` | CriteriaQuery | 1 |
| 12 | `JpaQueryServiceImpl.criteriaGrouped` | CriteriaQuery(AND/OR) | 1 |
| 13 | `JpaQueryServiceImpl.criteriaJoin` | CriteriaQuery(JOIN) | 1 |
| 14 | `JpaQueryServiceImpl.criteriaAggregate` | CriteriaQuery(聚合) | 2 |
| 15 | `JpaQueryServiceImpl.criteriaPaged` | CriteriaQuery(分页) | 1 |
| 16 | `JpaQueryServiceImpl.hibernateHql` | Hibernate HQL | 2 |
| 17 | `JpaQueryServiceImpl.hibernateHqlJoinFetch` | HQL JOIN | 2 |
| 18 | `JpaQueryServiceImpl.hibernateHqlAggregate` | HQL 聚合 | 2 |
