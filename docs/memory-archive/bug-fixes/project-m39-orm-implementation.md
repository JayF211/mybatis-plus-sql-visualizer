---
name: project-m39-orm-implementation
description: M39 ORM 缺口补全实施完成（worktree mpsv-orm / feature/m39-orm-coverage 三提交 3f42eb6/cca224e/151a55e，用户明确不合并 master，全量 3321 测试绿 + buildPlugin 通过）；分波文件独占模式与波次执行踩坑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_72d892cb-ed00-4438-973d-6c286c20766d
---

M39「ORM 框架功能覆盖补全」（计划与任务号 [[project-orm-gap-analysis]]，J1~J23 / 里程碑 M39~M43）实施于 git worktree `F:\code\Java\Gitee\SongRenShuo\mpsv-orm`，分支 `feature/m39-orm-coverage`。用户指令：多智能体并行、铁律登记、文件互斥、测试验收后提交 git、**不合并主分支**。parallel_tasks 登记 name=m39-orm-coverage sandbox=m39orm（完成须 done 注销）。

**进度（2026-09-05 会话中断点）**：
- master f724fbf：两份覆盖矩阵 + 开发计划文档已提交
- **Wave1（3f42eb6，38 文件 +8600 行、129 测试）**：四智能体并行——A 线 J2/J13/J19（JpaUpdateParser UPDATE→UpdateStatement、JpaJoinOnResolver JOIN ON 三级解析+JpaJoinRewriter、JpaFromEntityMapper 子查询实体映射、HQL 高级特性透传保护+SelectClauseExpander 短路）；B 线 J17/J6/J4（Criteria 函数全集/单层子查询/CriteriaUpdate+Delete→语句模型/notIn+getRestriction 死条目清理）；C 线 J7/J8/J14/J18（JpaMetadataReader 三轨注解读取，TableInfo/AssociationInfo/ColumnInfo **只做加法**：软删除族/@GeneratedValue/复合主键/嵌入展平/@ElementCollection/@Inheritance）；D 线 J3/J4/J11/J12/J21（query/search/stream 前缀+无 By 门控、IgnoreCase→LOWER、hbm 多 class+FileTypeIndex、NamedQuery 双 FQN、DaoQueryKind 分类 ENTITY_OP/QBC）
- **Wave2（cca224e）**：E 线 J1/J6/J21/M2——新 `JpaCrudOperationConverter`（Spring Data CRUD/EntityManager/HibernateTemplate 三家族统一转换器，IDENTITY 主键不进 INSERT 列）+ SqlPreviewService JPA CRUD 派发（**修复 save 撞 MP 转换器输出错误表名问题**，receiver 家族×方法名双重消歧）+ WrapperAnchorDetector 场景 E + @KeySequence；H 线 C1/C2 demo 写路径语料（demo 工程 4 新文件 + JpaDemoIntegrationTest 23→36 用例）
- **Wave3（151a55e，已提交）**：J5 SpecificationQueryConverter / J20 QueryByExampleConverter / J10·J15·J16·J22 JpaQueryEnhancer（Pageable/Limit 静态求值、@Lock FOR UPDATE、@EntityGraph JOIN 注入、@Procedure CALL、Page count SQL 展示）/ J9 调用点动作路径，27 测试；F 退回修复的 8 个失败全清（4 个是实现缺陷：projectionNote 无 JDK 解析态、@Procedure 空输出参数注释、调用点 pageNotes 二次分析矛盾、Pageable 本体进参数行）
- 文档已同步：JPA-Hibernate 矩阵勾行 83→✅、39→🟡；开发计划实施记录已回填
- **终验（2026-09-05）**：全量 `gradlew test` 3321 用例 0 失败；`buildPlugin` 产出 mybatis-plus-sql-visualizer-2026.9.4.zip（6.0M）；parallel_tasks 已 done 注销；分支领先 master 3 提交，实施时按用户指令不合并——**2026-09-05 用户改要求 dev 全量合并，本分支已合入 dev**（合并提交 487d8ee，见 [[project-dev-merge-all-branches]]），仍未合 master、未 push

**未做（按计划留档）**：J23 Querydsl（文档化边界）、M1 字符串版 QueryWrapper 消费（依赖 M37/M38 分支合并）、M3 Kotlin PSI（独立立项）、J18 SQL 注入层、J19 高级特性建模（透传保护已做）、jakarta @Version 与软删除注解的 SQL 注入未接。

**多智能体分波模式（实测有效，复用模板）**：按文件所有权严格互斥分波；SqlPreviewService.kt 是枢纽，其改动必须串行占波（Wave2 E、Wave3 F 各独占一波）；子智能体一律禁跑 gradle/禁 git，编排者每波集中编译+测试+修复+提交。每波 Kotlin 编译错 6~13 个属常态（import 缺失、nullable 不匹配、var 重赋值、List 重载解引用），可由编排者直接清。

**波次执行踩坑（实证）**：
- 本机代理 127.0.0.1:7890 会挂（netstat 无 LISTEN），但直连 Maven Central/JetBrains 仓库正常——gradle 命令加 `-Dhttp.nonProxyHosts=*` 绕过 gradle.properties 的代理配置即可（已同步 [[project-test-conventions]]）
- **Kotlin 块注释嵌套**：KDoc 里字面 `/*`（通配示例 `session/*entityManager` 实证）会开嵌套注释吞掉文件尾，报「Unclosed comment」在 EOF——AI 生成文档时通配/路径示例要避开 `/*`
- `private companion object` 在 standalone object 内非法；object 属性初始化按声明序，前面属性引用后面声明的属性报「Variable must be initialized」——常量须在被引用属性之前声明
- `XmlFileType` 在 262 平台不可直接引用（IPGP 裁剪）——用 `FileTypeManager.getInstance().getStdFileType("XML")`（项目已有 getStdFileType("SQL") 同款惯例）
- `FilenameIndex.getFilesByName(project, "*.hbm.xml", ...)` 通配符按字面名匹配不生效——用 FileTypeIndex + 文件名后缀过滤
- 断言失败消息只显示期望不显示实际时，写临时探针测试打印实际值再删（PartTreeParser isOrderByAt 反逻辑 bug 即此法定位：`findAllByOrderByXxx` 在 Subject 的 By 处被误跳、OrderBy 内部 By 被当分隔符，产 `createTimeDesc = ?` 假谓词且丢 ORDER BY——修复为 `position>=5 && regionMatches(position-5,"OrderBy",0,7)`，既有 findTop10 测试只断言 LIMIT 10 才一直没暴露）
- PartTreeParser `decamelize` 只降首段驼峰（"CreateTime"→"createTime" 非 "create_time"），OrderByPart.propertyPath 断言勿写错
- heredoc 传 python 的 `\\n` 会被吃成真换行——补丁脚本用 `chr(92)+'n'` 构造反斜杠序列，且写文件放断言全部通过之后
- 集中测试批量断言失败时，从 build/test-results/test/*.xml 解析完整 failure body（比控制台 tail 全），原路退回各智能体修复效率最高

相关：[[project-orm-gap-analysis]] [[project-test-conventions]] [[project-m37-competitive-fusion]]
