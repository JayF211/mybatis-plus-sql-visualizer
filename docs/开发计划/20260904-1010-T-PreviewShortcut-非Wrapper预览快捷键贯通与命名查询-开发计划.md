# T-PreviewShortcut：非 Wrapper 预览快捷键贯通与命名查询 开发计划

> **创建日期**：2026-09-04
> **分支**：`feature/jdbc-template-sql-preview` 已合入 master（151d56e）；本计划在 master 上直接实施
> **优先级**：P2（快捷入口缺失：HQL/JPQL、自定义 DAO HQL、JDBC 模板已有 gutter 图标，仅快捷键链路未贯通；`@NamedQuery` 实体级连入口都没有）
> **预估工时**：5~8h
> **来源**：用户反馈"HQL 很多地方只有 SQL 预览图标，无法通过快捷键快速预览 SQL"+ 逐类核对 JPA/JDBC 模板后确认同源缺口 + `@NamedQuery` 实体级无任何预览入口

---

## 一、问题与全貌

### 1.1 全景盘点

按"预览类型 × 图标 × 快捷键"逐一核对（demo 项目 `jpa-hibernate-demo` + 合并后的 JDBC 模板特性）：

| 预览类型 | 图标 | 快捷键 | 状态 |
|----------|:---:|:---:|------|
| MyBatis-Plus Wrapper 链 | ✅ | ✅ `preview()` | 通 |
| `@Select` 注解 | ✅ | ✅ `showAnnotationCandidates` | 通 |
| JPA `@Query` / 派生查询 / CriteriaQuery | ✅ | ✅ `tryJpaFallback` | 通 |
| **HQL/JPQL 运行时**（`session/em.createQuery`、`createNativeQuery`、`createNamedQuery`） | ✅ `HqlGutterLineMarkerProvider` | ❌ | **缺口** |
| **自定义 DAO HQL**（`findByQuery` 等） | ✅ `CustomDaoHqlGutterLineMarkerProvider` | ❌ | **缺口** |
| **JDBC Template**（`jdbcTemplate.queryForList` 等） | ✅ `JdbcTemplateSqlGutterLineMarkerProvider` | ❌ | **缺口** |
| **实体级 `@NamedQuery`/`@NamedQueries`** | ❌ | ❌ | **更深缺口**（`NamedQueryLocator` 有定位器+测试，主代码无任何消费） |

### 1.2 根因：快捷键 dispatch 链路未接入非 Wrapper 定位器

`ConvertToSqlAction.actionPerformed`（`ConvertToSqlAction.kt:138-149`）光标分支兜底顺序：

```
service.preview(element)          → 仅 MyBatis-Plus Wrapper（SqlPreviewService.kt:162）
  ├─ 命中 → 直接预览
  └─ 未命中 → tryJpaFallback(...)   → 仅 @Query / 派生 / CriteriaQuery
       └─ 未命中 → showAnnotationCandidates（@Select 注解）
```

三个环节**都不含** HQL/JPQL 运行时、自定义 DAO HQL、JDBC 模板、实体级 `@NamedQuery`：

1. `SqlPreviewService.preview()`（:162）只走 `WrapperLocator`；`createQuery("/JPQL")`、`jdbcTemplate.queryForList(...)` 不含 Wrapper，恒返回 null。
2. `tryJpaFallback`（:156-180）只查 `@Query` / 派生 / CriteriaQuery；上述四类均不命中，返回 false。
3. 落到 `showAnnotationCandidates` 扫 `@Select` → 一无所有 → `showResult(null)`"未找到"。

而这几类的预览能力**早已存在**，只挂在 gutter 点击上：`previewHql`（:472）、`previewCustomDaoHql`（:534）、`JdbcTemplateSqlPreviewService.preview`。`@NamedQuery` 则是连 gutter/preview 都没接。**快捷键与点击走两条互不相通的链路**，即"只有图标、无快捷键"的直接原因。

### 1.3 与既有路径不误撞的实证

- CriteriaQuery `cb.createQuery(Class)` / `em.createQuery(cq)`：`HqlQueryLocator.hasStringHqlArgument` 要求首参为 String，CriteriaQuery 已被排除（`HqlQueryLocator.kt:170-191`），仍走原兜底。
- `update()` 无需改：四类都在 Java 文件，快捷键本就可见可用，缺的只是**路由**。

---

## 二、技术方案

### 2.1 快捷键 dispatch 贯通（HQL/JPQL + 自定义 DAO HQL + JDBC 模板，核心）

在 `ConvertToSqlAction` 光标分支回调里，`preview()` 返回 null 后、`tryJpaFallback` 之前，按序插入三个短路兜底；链式写法避免嵌套过深：

```kotlin
if (result != null) {
    SqlPreviewService.showResult(project, result, sourceLocation)
} else if (!tryHqlFallback(service, project, element, sourceLocation)
           && !tryNamedQueryFallback(service, project, element, sourceLocation)
           && !tryJdbcTemplateFallback(project, element, sourceLocation)
           && !tryJpaFallback(service, project, element, sourceLocation)) {
    showAnnotationCandidates(project, file, editor, sourceLocation)
}
```

三个新私有方法（放 `tryJpaFallback` 旁，风格一致）：

```kotlin
/** HQL 兜底（T-PreviewShortcut）：标准 HQL/JPQL 运行时字符串调用 + 自定义 DAO HQL。 */
private fun tryHqlFallback(service: SqlPreviewService, project: Project, element: PsiElement, sourceLocation: String?): Boolean {
    if (HqlQueryLocator.locate(element) != null) {
        SqlPreviewService.previewAsync(project, { service.previewHql(element) }) { r ->
            SqlPreviewService.showResult(project, r, sourceLocation)
        }
        return true
    }
    if (CustomDaoHqlLocator.locate(element) != null) {
        SqlPreviewService.previewAsync(project, { service.previewCustomDaoHql(element) }) { r ->
            SqlPreviewService.showResult(project, r, sourceLocation)
        }
        return true
    }
    return false
}

/** JDBC 模板兜底：jdbcTemplate.queryForList 等 → JdbcTemplateSqlPreviewService.preview。 */
private fun tryJdbcTemplateFallback(project: Project, element: PsiElement, sourceLocation: String?): Boolean {
    if (JdbcTemplateSqlLocator.locate(element) != null) {
        SqlPreviewService.previewAsync(project, { JdbcTemplateSqlPreviewService.preview(element, project) }) { r ->
            SqlPreviewService.showResult(project, r, sourceLocation)
        }
        return true
    }
    return false
}
```

要点：判定与转换分离（`locate()` 先判、`previewAsync` 后台转换），与 `tryJpaFallback` 的 `hasQueryAnnotation`+`previewJpql` 模式一致；三者定位器均接受任意 PSI 元素（`locate(anchor)` 路径 3 扫宿主方法体），光标落在调用行即可。需新增 import：`psi.jpa.HqlQueryLocator`、`psi.jpa.CustomDaoHqlLocator`、`psource.JdbcTemplateSqlLocator`、`psource.JdbcTemplateSqlPreviewService`。

### 2.2 实体级 `@NamedQuery` 预览（补齐入口）

`NamedQueryLocator` 已有定位能力（`locate(entityClass)` 返回 List），但主代码无人消费。补齐四件：

**(a) `NamedQueryInfo` 携带注解 + 按名查找**

`NamedQueryLocator.kt`：
- `NamedQueryInfo` 增 `annotation: PsiAnnotation? = null`（在 `extractNamedQuery` 处回填，供"光标下的那一条"定位与取实体类）。
- 新增 `findByQueryName(project, queryName): NamedQueryInfo?`：取查询名 `substringBefore('.')` 前缀推断实体类，`JavaPsiFacade.getInstance(project).findClasses(prefix, projectScope)` 解析，`locate(cls).firstOrNull { it.name == queryName }`（JPA 命名查询名默认 `<实体简单名>.<方法名>`，无 PsiSearchHelper 依赖，API 均为 `EntityClassFinder` 已验证过的稳定用法）。

**(b) `SqlPreviewService.previewNamedQuery(anchor)`**

`PsiTreeUtil.getParentOfType(anchor, PsiClass)` → `NamedQueryLocator.locate` → 取光标所在 `@NamedQuery` 对应那条（`annotation` 匹配），`JpaEntityResolver().resolve(class)` 得表，`JpqlConverter.convert(query, table)` 转 SQL，`buildJpaPreviewResult(..., method=null, ...)` 构建（`method` 可空已支持，数值判定回退全引号）。

**(c) `previewHql` 对 `createNamedQuery("name")` 按名解析**

在 `previewHql` 增分支：`createQueryMethodName == "createNamedQuery"` 时用 `NamedQueryLocator.findByQueryName(project, info.hql)` 解析真实 JPQL；命中 → 走 `JpqlConverter` 转换并构建；未命中（跨文件/命名不规范）→ 回退现有"把名字当 HQL 转换"行为，优雅降级。

**(d) gutter（新文件 `NamedQueryGutterLineMarkerProvider`）**

仿 `JpqlGutterLineMarkerProvider`：锚点 `JavaTokenType.AT`，父 `PsiAnnotation` 短名 `NamedQuery`（`@NamedQueries` 容器内部子注解自带 `@` token，逐个锚定；容器本身跳过）。点击 → `previewNamedQuery(annotation)`。plugin.xml 注册（`<codeInsight.lineMarkerProvider language="JAVA" .../>`）。

### 2.3 选择区路径（本次不改）

选择区分支（:84-120）只扫 Wrapper + `@Select`，与 JPA 一致，非本计划范围。

---

## 三、实施步骤

| # | 任务 | 说明 |
|---|------|------|
| 1 | （已完成）合并 jdbc-template 分支 | `151d56e`，零冲突；本计划在其之上实施 |
| 2 | 改 `NamedQueryLocator.kt` | `NamedQueryInfo.annotation` + 回填 + `findByQueryName(project, name)`（JavaPsiFacade + projectScope） |
| 3 | 改 `SqlPreviewService.kt` | 增 `previewNamedQuery(anchor)`；`previewHql` 加 `createNamedQuery` 按名解析分支 |
| 4 | 改 `ConvertToSqlAction.kt` | 新增 `tryHqlFallback` / `tryNamedQueryFallback` / `tryJdbcTemplateFallback` 并链入；补 import |
| 5 | 新建 `NamedQueryGutterLineMarkerProvider.kt` | 锚 `@` token 的 "NamedQuery" 注解；plugin.xml 注册 |
| 6 | 补测试 | 见第四节 |
| 7 | 跑测试 | 跑 gradlew 前先 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`；先跑定向新建用例，再 actions/psi/jpa/ui/gutter/psource 相关包全量 |
| 8 | 收尾 | 提交信息 `feat: T-PreviewShortcut 非Wrapper预览快捷键贯通+@NamedQuery实体级预览`；CHANGELOG / docs/MEMORY.md 三处同步；**收尾前先提交/暂存工作区未提交的 JOIN 进行中代码**（build.gradle.kts / ui/diagram / SqlPreviewPanel 等） |

---

## 四、测试计划

| 用例 | 做法 | 断言 |
|------|------|------|
| 光标在 HQL 行 → 走 previewHql | `configureByText` 建 Java 含 `em.createQuery("select u from U u")`，光标落行触发 action | 路由到 HQL 分支（`HqlQueryLocator.locate != null`），不落 showAnnotationCandidates |
| 光标在自定义 DAO 行 → previewCustomDaoHql | `commonDao.findByQuery(hql, ...)` | CustomDaoHqlLocator 命中 |
| 光标在 JDBC 模板行 → previewJdbc | `jdbcTemplate.queryForList("select ...", ...)` | `JdbcTemplateSqlLocator.locate != null`，路由 OK |
| `createNativeQuery("SQL")` 命中 HQL | `em.createNativeQuery("SELECT * FROM t")` | 走 previewHql native 分支 |
| `createNamedQuery("name")` 按名解析 | 同文件/可解析实体 `findByQueryName` 命中 | `previewHql` 返回真实 JPQL 转换 SQL，不再是名字字符串当 HQL |
| `createNamedQuery` 解析不到降级 | 名称前缀无对应实体类 | 回退现有行为，不抛异常 |
| 实体级 `@NamedQuery` 快捷键 | 光标落实体类 `@NamedQuery(...)` 注解内，触发 action | 走 `tryNamedQueryFallback` → previewNamedQuery 返回 SQL |
| 实体级 `@NamedQuery` gutter | 光标/点击实体类 `@NamedQuery` @ token | LineMarker 命中，点击 previewNamedQuery |
| CriteriaQuery 不误抢 | `cb.createQuery(UserEntity.class)` + `em.createQuery(cq)` | `HqlQueryLocator.locate` null，仍走原 CriteriaQuery 兜底 |
| Wrapper 链回归 | 既有 Wrapper 用例 | `preview()` 优先命中，行为不变 |
| 空方法兜底 | 普通空方法体 | 落到 showAnnotationCandidates → 未找到，不抛异常 |

`NamedQueryLocatorTest` 现有用例保持通过（`annotation` 字段可空，不破坏构造）。`@NamedQuery` 转换正确性沿用 JpqlConverter 既有测试。

---

## 五、风险与回滚

- **风险中低**：dispatch 改动收敛在 `ConvertToSqlAction` 单文件约 30 行（只增短路分支，不动既有定位器/服务实现）；`@NamedQuery` 改动新增 NamedQueryInfo 字段（可空，向后兼容）+ 新 gutter + 新 preview 方法，不动既有 Jpql/CriteriaQuery 路径。
- **createNamedQuery 跨文件解析是 best-effort**：`findByQueryName` 依赖 JPA 命名约定 `<实体>.<方法>`，解析不到时优雅降级为既有行为（不抛、不挂起），不影响其它路径。
- **工作区遗留**：master 工作区仍有未提交的 JOIN 进行中改动，实施中不触碰该区域（SqlPreviewPanel/diagram），收尾前需用户确认如何处置（提交或暂存）。
- **回滚**：各功能点独立 commit，revert 单个即可。