---
name: project-m44-orm-batch2
description: M44 其他ORM第一批实施（worktree mpsv-orm2 / feature/m44-orm-batch2，用户 /goal
  指令单智能体实施到 Querydsl）——单智能体已交付 5 转换器，SD 止血 7 测试全绿，EQ/DS/QD/XB 15 败退回修复中；新增 6 个
  Kotlin/JUnit3 编译级踩坑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_72d892cb-ed00-4438-973d-6c286c20766d
---

M44「其他 ORM 第一批」（矩阵 [[project-other-orm-research]] 的 SD/XB/EQ/DS/QD 任务，按推荐节奏 SD 止血+xbatis→EasyQuery+MDS→Querydsl）实施中，用户指令：**从 mpsv-orm 分支新建 worktree + 单个子智能体实施到 Querydsl**。git worktree `F:\code\Java\Gitee\SongRenShuo\mpsv-orm2`，分支 `feature/m44-orm-batch2`（基点 feature/m39-orm-coverage bb86675）；parallel_tasks 登记 name=m44-orm-batch2 sandbox=m44batch2（完成须 done 注销）。

**进度（2026-09-05 会话中断点）**：
- 单智能体（agent_5313ccda）已交付：新建 5 主文件（XbatisConverter 145 / EasyQueryConverter 801 / MdsConverter 464 / QuerydslConverter 578 / psi/jpa/RepositoryBaseTypes 41 统一基类名单）+ 修改 12 文件（WrapperChain 加 XBATIS/EQ_PROXY/MDS/QD 四 WrapperType、WrapperLocator、WrapperAnchorDetector 加 EQ 场景 F、JpaEntityResolver/JpaMetadataReader 注解轨、JpqlQueryLocator、SqlPreviewService convertOtherOrm 前置分支）+ 19 测试（1457 行）
- 关键 API 实证修正：**xbatis 1.10.8 无 QueryWrapper 类**（调研期 agent 假设的 `QueryWrapper.of` 是错的），真实入口 `QueryChain.of(mapper, Class)`/`create()`，包名 `cn.mybatis.mp.`（点，非坐标写法 cn.mybatis-mp）；差异方法 gte/lte→ge/le、like(LikeMode,..)→likeLeft/likeRight、andNested/orNested→and/or；终结名单 get/getOptional/paging/cursor/execute
- **集中编译 14 错已由编排者修**（勿回退）：① `GeneratedSql` 是 `cn.song...sql` 包**顶层类**非 SqlGenerator 嵌套（import 而非 SqlGenerator.GeneratedSql）；② `val x: T? = e ?: return null` 显式可空声明类型阻断智能转换——去掉显式类型即可；③ `PsiNewExpression.classReference` 无 `.type`，用 `.resolve() as? PsiClass`；④ List 赋 MutableList 字段须 `.toMutableList()`；⑤ `PsiImportStatementBase` 无 qualifiedName——`filterIsInstance<PsiImportStatement>()`；⑥ JpaMetadataReader 补 `FQN_MANY_TO_ONE`；⑦ **JUnit3 TestCase 私有助手 `assertTrue(String, Boolean)` 撞基类 JVM 签名→Accidental override 编译错**（删除助手直用 org.junit.Assert）
- **测试现状（已收官）**：15 败已修复回流（三处共性根因=collectSegments 引用层断链、xbatis 泛型形参误当实体、cn.xbatis.* 前缀宽松匹配误判变量），集中重测全绿；矩阵 SD/XB/EQ/DS/QD 共 24 行置 ✅/🟡 + 交付快照（分支顶端 d6ee636）；**全量 3353 测试 0 失败 + buildPlugin 通过**
- **收尾完成**：已提交 feature/m44-orm-batch2 并于 2026-09-05 合入 dev（合并提交 6848a4d，见 [[project-dev-merge-all-branches]]）；仍不合 master、未 push

刻意不做边界（智能体 KDoc 注明）：EQ core/String 风格与 sql-api4j、select 投影/group/having；xbatis empty/notEmpty、insert DSL；MDS insert/union/子查询；QD join 建模（降级注释）、JPAExpressions 子查询、CaseBuilder、PathBuilder；SD 反向列展开/级联 notes/prefix。

相关：[[project-other-orm-research]] [[project-m39-orm-implementation]] [[project-test-conventions]]
