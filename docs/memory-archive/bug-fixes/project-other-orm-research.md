---
name: project-other-orm-research
description: 候选 ORM 框架适配调研定稿（2026-09-05 四路 jar 反编译）——矩阵文件已建
  docs/架构设计/其他ORM官方API覆盖矩阵.md；xbatis/EasyQuery/Querydsl/SpringDataJDBC 值得立项，jOOQ
  缓做 MVP，Jimmer 与 Flex 同判暂缓；SD/XB/EQ/DS/QD 已由 project-m44-orm-batch2 实施中
metadata:
  node_type: memory
  type: project
  originSessionId: sess_72d892cb-ed00-4438-973d-6c286c20766d
---

候选 ORM 框架调研于 2026-09-05 完成（四路智能体并行，11 个 jar 从 Maven Central 拉取 javap 为唯一权威，研究 jar 在 `F:\code\orm-research-jars\` 临时目录）。产出《docs/架构设计/其他ORM官方API覆盖矩阵.md》（commit 338f26e，任务号 SD/JO/QD/EQ/JM/XB/DS/FM/DV/BS 预分配）。**SD/XB/EQ/DS/QD 实施已启动**（同日 [[project-m44-orm-batch2]]，worktree mpsv-orm2 / feature/m44-orm-batch2，单智能体模式）。

**立项决策表**（门槛：Converter 增量 < MP 系 15%，M37.5 Flex 判例）：
- **Spring Data JDBC/R2DBC（4.1.1）**：现状「侥幸可用」——receiver 家族判定与 snake_case 双向凑巧；两处 ❗ 错误输出链路须止血：① JDBC `@Column` 只有 value 属性而插件只读 name→列名系统性丢失；② JDBC `@Query`（不同包、无 nativeQuery 属性）被当 JPQL 喂 JpqlConverter 产出错误 SQL。另 @Table(value 简写) 表名丢失、@MappedCollection 集合字段被当普通列。止血期 <5%（名单对齐+value 属性+@Query 路由 native 透传+集合字段跳过），完整 5-15%。
- **xbatis（原 mybatis-mp，cn.xbatis 1.10.8）**：MP 同构 <5%，最易；旧坐标 cn.mybatis-mp 冻结 1.8.1-RC3 需双坐标识别。
- **Easy-Query（3.2.15）**：core 风格与 MP 9/10 同构（布尔重载/and-or 闭包全对应）；官方主推 proxy 风格（APT 生成 XxxProxy，lambda 块内 void 注册式，7.5/10）需 lambda 收集器增强；注解与 JPA 几乎同形可复制 JpaEntityResolver 轨道；框架自带 toSQL() 语义核对成本低。8-12%，立项。
- **Querydsl（5.1.0）**：条件与 MP 几乎 1:1，Q 类只有属性名→列名走 JPA 实体注解全复用 JPA 管线，预览定位=JPQL 语义（对齐 HqlConverter）；唯一新件=表达式树 walker。10-20%。⚠ org.querydsl 坐标已从 Central 下架，社区线在 openfeign fork（5.0.1~7.6，javax→jakarta 断代在 6.x）。
- **jOOQ（3.21.8）**：DSLContext 941 方法/39 步进接口/Field 359 签名；条件方法名可映射但生成代码（零注解纯常量）基建+API 面大，25-35% 缓做；若做只立 MVP 子集（where 核心+join.on+DML 三件套，其余 Raw）。
- **Jimmer（0.9.96）**：谓词一等对象+varargs+隐式 join+接口实体（注解在 getter 方法上）→ 3.5/10 同构度，>15% 暂缓（Flex 同判）；重启条件=用户反馈集中或 Criteria 族表达式树基建落地（与 QD4 共用）。
- **长尾**：MyBatis Dynamic SQL 2.0.0（P0 候选，手写 DSL+规整 AST 无生成代码依赖，RenderingStrategy 占位符与插件方言组对齐）；Fluent MyBatis（P1，生成类链+where.and/or 字段式入口需专用模型，低频维护）；dbVisitor（P1，注解 SQL+lambda 混合）；BeetlSQL（P2，md 模板复用 XML 轨道静态化）；sqltoy/lazy（P2/P3 边界）。

放弃项：NoSQL 家族、OpenJPA/DataNucleus、Exposed/Ktorm/GORM（待 Kotlin PSI）。

**M44 第一批已实施（2026-09-05，worktree mpsv-orm2 / 分支 feature/m44-orm-batch2，基于 feature/m39-orm-coverage，2 提交 2049d70+ab51d47 未合并）**：单智能体实施 SD 止血（SD6/SD7/SD8/SD10，两处 ❗ 错误输出链路已修）+ xbatis（XB1/XB2）+ Easy-Query proxy MVP（EQ1/EQ4~EQ10）+ MyBatis Dynamic SQL（DS1~DS5）+ Querydsl MVP（QD1/QD2/QD4/QD5），矩阵勾行 39 行置 ✅/🟡（个别行按任务号整行翻转、深度以 §0 交付快照为准）；全量 3353 测试 0 失败 + buildPlugin 通过。执行踩坑：① 多框架共用的 collectSegments 必须处理 PSI「引用层」（call.parent 是 methodExpression 而非外层调用），只回溯调用层会断链丢 where/orderBy 全部段；② stub fixture 把类放进 cn.xbatis.core 包后所有类型 FQN 都命中 cn.xbatis. 前缀 → detectWrapperType 须「前缀+链类简单名」双重判定，否则 like(LikeMode.LEFT,...) 实参字段被误判链变量；③ QueryChain.of(mapper, X.class) 的 Class 字面量实参优先于返回类型泛型推断（LightPlatform 下推断为类型形参 T）；④ 分支基于 feature/m39-orm-coverage 时 master 后续 docs 提交不在分支上，矩阵勾行前须 git checkout <master 提交> -- 取入。剩余待立项：SD 完整期/EQ core/QD 子查询（QD6）/jOOQ/Jimmer/长尾。

建议节奏：M44 第一批=SD 止血+xbatis；M44/45=EasyQuery+MDS；M45=Querydsl。相关：[[project-orm-gap-analysis]] [[project-m37-competitive-fusion]]
