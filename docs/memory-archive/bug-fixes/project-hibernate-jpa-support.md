---
name: project-hibernate-jpa-support
description: Hibernate/JPA 全面支持已交付（2026-08-20）——4 阶段开发，已合并 master，1682 测试全绿
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a4fb5d93-5d10-469d-a2f7-b9844892efec
---

插件从 MP/MPJ 扩展到 Hibernate/JPA 的全面支持已交付（2026-08-20），已合并到 master 分支（commit `3754e48` + `1e62f09` + `5bec5b6`）。

**三种 JPA 查询风格全部实现**：
1. Spring Data JPA 推导查询（`findByLastNameAndFirstName`）——PartTree 解析器拆方法名，27 种条件类型
2. CriteriaBuilder 强类型查询（`cb.equal(root.get("name"), "John")`）——PSI 解析调用链
3. JPQL / `@Query` 注解——实体名→表名映射 + 参数占位符替换

**架构决策**：纯静态分析（不依赖运行时 Hibernate SessionFactory）。JPA 注解通过 PSI 直接读取。

**4 阶段交付**：
- 阶段一（~16h）：JpaEntityResolver + PartTreeParser + DerivedQueryConverter + DerivedMethodLocator
- 阶段二（~12h）：CriteriaQueryLocator + CriteriaQueryConverter
- 阶段三（~8h）：JpqlQueryLocator + JpqlConverter
- 阶段四（~8h）：JpaGutterLineMarkerProvider + SqlPreviewService 集成 + plugin.xml + i18n

**关键修复（合并 master 后）**：
- `parseSubject` 解析顺序：先操作关键字 → Distinct → Top/First（Spring Data 语法 `findDistinctBy...`）
- `matchConditionKeyword` 最长匹配优先（`NotContaining` 不被 `Containing` 截断）
- `IgnoreCase` 在 `OrderBy` 之后时需优先剥离
- `convertPredicates` 单个 PredicatePart 时平铺条件（实现多行 AND 缩进）
- `SqlGenerator` 对保留字 `user` 加反引号，测试期望值同步修正

**已知缺陷（待修复）**：嵌套对象 JOIN 查询不支持（如 `findByAddressCity` 降级为 Condition.Raw）。开发计划见 `docs/开发计划/20260820-0017-JPA嵌套对象JOIN查询支持-开发计划.md`。

**后 v2026.8.20 扩展（2026-08-21 已完成）**：
- **嵌套对象 JOIN 支持（T0017）**：已完成。
- **测试体系加固（T0031）**：已完成。
- **自定义 DAO HQL 识别（2026-08-21 新增）**：`CustomDaoHqlLocator`（三重启发：DAO 后缀 + 方法名关键字 + HQL 内容模式）+ `HqlGutterLineMarkerProvider`（标准 createQuery）+ `CustomDaoHqlGutterLineMarkerProvider`（自定义 DAO）。支持 `commonDao.findByQuery("HQL", "param", value)` 形态。
- **EntityManager.createQuery 全覆盖（2026-08-21）**：`HqlQueryLocator.extractEntityInfo()` 三级 fallback（二参 Entity.class → 类型参数 → FROM 子句提取）+ `HqlConverter` 保留原始 HQL 结构（修复 `SELECT FROM unknown_table` 问题）。

**Demo 项目全量兼容（2026-08-21 确认）**：分析 3 个子模块共 78 个查询模式（派生查询 33 + @Query 19 + @NamedQuery 2 + EntityManager.createQuery 2 + CriteriaQuery 10 + Session.createQuery 7 + Specification 1 + Wrapper 25 + XML 6 + @Select 6），全部兼容。

**HQL/JPA SQL 日志捕获（2026-08-21 完成，feature/hibernate-log-capture 分支）**：
- **双解析器并列架构**：`SqlLogConsoleBridge.attachFilter()` 创建 `MyBatisLogParser` + `HibernateLogParser`，每行控制台输出同时喂入两个解析器，各自独立产出 `LogSqlEntry` 汇入同一 `SqlLogEntryBuffer`，零耦合互不干扰
- **HibernateLogParser** 支持 3 种 SQL 行格式（`Hibernate:` 前缀 / `/* comment */` 注释前缀 / 纯 SQL 行）+ 参数绑定行（`binding parameter [N] as [TYPE] - [VALUE]`）
- **LogSqlEntry.source** 枚举（MYBATIS / HIBERNATE / UNKNOWN）标记条目来源
- **LogSqlRestorer.PLAIN_TYPES** 追加 18 个 JDBC 类型名（BIGINT, INTEGER, VARCHAR 等）
- **共享设置**：`mixedPrefix` 和 `maxParseLength` 对两个解析器全局生效（故意设计为通用解析行为）
- **独立设置**：`logSqlPrefix`（MyBatis）与 `logHibernateSqlPrefix`（Hibernate）各自独立配置
- 2012 测试全绿（新增 45 个测试用例）
- **LogToSqlPanel 手动粘贴支持（2026-08-21 完成）**：`convert()` 方法从单 MyBatis 解析器改为双解析器并列（MyBatis + Hibernate 同时喂行），用户粘贴 Hibernate 日志或混合日志均可自动识别转换，无需手动选择格式。新增 4 个测试（Hibernate 格式/null 参数/注释前缀/混合格式）

**HQL 预览参数识别与表名解析（2026-08-21 修复）**：
- **参数识别**：`previewHql()` 优先使用 `HqlQueryLocator` 追踪的 `setParameter` 参数；若为空（链式调用无变量赋值），直接从 HQL 字符串提取（`:paramName` + `?N` → `paramN`）。代码：`SqlPreviewService.extractParamNamesFromHql()`
- **表名解析**：`resolveHqlTable()` 三级 fallback — ①PsiClass 直接解析 ②`entityClassName`（来自 `extractEntityInfo` 或 FROM 子句提取）→ `JavaPsiFacade.findClass()`（需 FQN）③`PsiShortNamesCache.getClassesByName()`（短名查找）。**注意**：`JavaPsiFacade.findClass()` 需要全限定名，短名找不到；`PsiShortNamesCache` 是按短名查找的正确 API
- **实体名 FROM 子句提取**：`extractEntityNameFromHql()` 用正则 `\bFROM\s+([A-Z][a-zA-Z0-9_]*)\b` 从 HQL 提取实体类名

**复制 SQL 剥离注释（2026-08-21 新增）**：
- `SqlCommentStripper`（`sql/SqlCommentStripper.kt`）剥离 `--` 行注释和 `/* */` 块注释
- `SqlPreviewPanel.copySql()` 复制前调用 `SqlCommentStripper.strip(raw)`，与 Java 代码复制行为对齐

**Why:** 用户主动请求扩展 ORM 覆盖面，Hibernate/JPA 是 Java 生态最主流的 ORM 框架。

**How to apply:** 新增 JPA 功能时遵循静态分析路线（PSI 解析 → 模型转换 → SqlGenerator 渲染）；关联解析需从 JpaEntityResolver 获取 AssociationInfo；JOIN 构建需查 `TableInfo.associationByField()`。HQL 预览使用转换后 SQL 字符串（`result.sql`），不从 SelectStatement 重新生成。实体类查找用 PsiShortNamesCache（短名）而非 JavaPsiFacade（需 FQN）。SQL 日志捕获新增 ORM 格式时，仿照双解析器模式新增独立 Parser 类，共享 `LogSqlEntry` 数据模型。