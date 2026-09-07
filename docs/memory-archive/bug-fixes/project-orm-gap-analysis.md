---
name: project-orm-gap-analysis
description: 四框架功能缺口调研定稿（2026-09-04 两轮）——JPA 写路径零支持、save 撞 MP 批量转换器输出错误表名；二轮 jar javap 全量 API 盘点新建 JPA-Hibernate 覆盖矩阵 + 任务 J14~J23；MP/MPJ 矩阵 8 行已回填
metadata:
  node_type: memory
  type: project
  originSessionId: sess_72d892cb-ed00-4438-973d-6c286c20766d
---

四框架（MP/MPJ/JPA/Hibernate）功能缺口调研于 2026-09-04 完成两轮，计划文件 `docs/开发计划/20260904-2340-ORM框架功能覆盖缺口分析与补全-开发计划.md`（任务号 J1~J23/M1~M4/C1~C3，里程碑 M39~M43）。**实施已启动（2026-09-05）**：worktree mpsv-orm / 分支 feature/m39-orm-coverage，Wave1 3f42eb6 + Wave2 cca224e 已提交、Wave3 修复中，进度与执行踩坑见 [[project-m39-orm-implementation]]（用户明确该分支不合并 master）。

**二轮（四智能体并行，本地 jar javap 权威 + 官方文档 + releases）**：
- 新建《docs/架构设计/JPA-Hibernate官方API覆盖矩阵.md》——8 分类面约 200 行 API 条目逐行挂任务号，与 MP-MPJ 矩阵同风格，后续按行交付。
- 新增任务 J14~J23：J14 主键生成策略（@GeneratedValue 四策略，J1 INSERT 正确性的前置）、J15 fetch 与锁（@EntityGraph P0——fetch join 查询当前整体丢 JOIN；@Lock→FOR UPDATE；@Fetch/@BatchSize）、J16 存储过程与命名原生查询（@Procedure 包名陷阱：org.springframework.data.jpa.repository.query.Procedure）、J17 Criteria 函数与子查询全集、J18 实体映射深水区（@Column insertable/updatable、@ElementCollection、@Inheritance 三策略、@SecondaryTable）、J19 HQL 6.x 高级特性（CTE/UNION/窗口/INSERT-SELECT）、J20 QBE、J21 HibernateTemplate 全族、J22 返回类型与投影、J23 Querydsl（文档化边界倾向）。
- 权威事实：PartTree$Type 枚举 27 项=派生查询关键字权威清单（3.5.10/4.0.3 javap 一致）；queryBy/searchBy/streamBy 是 3.2 新增官方前缀；Hibernate 6.4 字节码证实 createSQLQuery/createCriteria 已移除；org.hibernate.annotations 39 注解插件消费 0 个；插件支持的 HibernateTemplate 4 方法全部带 Deprecated 标志、bulkUpdate 是模板唯一 DML 入口；插件 Criteria 名单中 notIn（jakarta 3.1 无此方法）/getRestriction 为死条目。
- MP/MPJ 时效：3.5.17/1.5.9 截至 2026-09-04 仍最新（MPJ 1.5.9 实为 2026-07-10 发布，矩阵原「2026-08」笔误已改）；API 面无「jar 有矩阵无」硬遗漏，MP-MPJ 矩阵已回填 8 行过期（fill/Db/Tenant/DynamicTableName/DataPermission/BlockAttack/Kotlin/ktQuery）+ 新增 5 行（LogicDeleteByIdWithFill、@OrderBy/@SqlCondition、allEqStr、MPJBaseJoin、setUpdateEntity 族）。
- 本地 jar 位置：~/.m2/repository 下 spring-data-jpa 3.2.12、spring-data-commons 至 4.0.3、jakarta.persistence-api 3.1.0+2.2.3(javax)、hibernate-core 6.4.10、spring-orm 6.1.15、MP 3.5.15、MPJ 1.5.6/1.5.8（均低于矩阵基线，增量靠 tag 源码/releases 核验）。

核心结论（均经代码核实）：

- **JPA 写路径整体为零**：Spring Data CRUD API（save/findById/findAll/existsById/deleteById 等）与 EntityManager persist/merge/remove/find、JpaSpecificationExecutor、CriteriaUpdate/Delete、@Modifying 全项目零引用；查询面六入口完整（converter 359 + psi 146 + 集成 23 用例）。
- **JPA save 撞 MP 批量转换器**：WrapperAnchorDetector 按方法名锚定 → BatchOperationConverter → `isLikelyEntity` 类名含 "Entity" 即命中 → MP EntityResolver 只认 @TableName、回退类名驼峰转下划线 → JPA 实体（@Table(name="t_user") 的 UserEntity）被输出 `INSERT INTO user_entity` + 字段名当列名，错误 SQL 比不识别更误导。findById/findAll 则完全无锚点。
- **JpqlConverter/HqlConverter 仅 DELETE 特判**，UPDATE JPQL/HQL 走 SELECT 管道（statement 恒 SelectStatement、无 SET 展开）；demo 已有 updateStatusByJpql/hibernateHqlUpdate 真实用例。
- **断链/半成品**：queryBy/searchBy/streamBy（Parser 有、Locator 门控无）；findAll()/count() 无 By 不识别（死条目）；IgnoreCase 解析不生效（buildCondition ignoreCase 未用）；派生 IN 未传 inClauseParams；Criteria distinct(true) 死条目；JOIN ON 三路全猜测不读 @JoinColumn（仅派生嵌套属性用真实 associations）；.hbm.xml 只解析第一 <class> 且 FilenameIndex "*.hbm.xml" 通配符疑似不匹配；NamedQuery FQN 写死 javax.persistence。
- **覆盖矩阵 4 行过期**（实际已交付仍标 ⬜）：MPJ fill、F-6 拦截器模拟（Tenant/DynamicTableName/DataPermission）、Db kit；Kotlin 为 🟡 MVP（KtWrapperDetector 文本正则）。@KeySequence 仍零支持。
- demo 工程 `mybatis-plus-sql-visualizer-demo/jpa-hibernate-demo`（Maven，19 文件）全为查询面，写路径无一例——计划 C1 要求补 UserWriteService 语料；JpaDemoIntegrationTest 23 用例亦全查询。
- J1 方案锚点：BatchOperationConverter 加 receiver 家族感知（JpaRepository 系基类复用 DerivedMethodLocator 识别），表列换 JpaEntityResolver 源；UPDATE 复用 MP 侧现成 UpdateStatement 模型，缺的只是 JPA 解析入口。
- M1（字符串版 QueryWrapper 消费形态条件丢失）依赖 [[project-m37-competitive-fusion]] 分支先合并。

相关：[[project-models]] [[project-extension-points]] [[project-m37-competitive-fusion]]
