---
name: project-m37-m38-competitive-flex-absorption
description: M37 竞品精华融合 + M38 Flex 精华吸收均已实施完成(2026-09-02,分支
  feature/m37-competitive-fusion,15提交)——81场景金样矩阵、7个真实缺陷修复、gutter左右键交互；testDatabase因DBN子进程类加载限制仍为skip
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d410d5ac-6c86-4184-98b4-07915d04de86
---

## M37 竞品精华融合(已发行基础)

主轴三合一闭环:写前静态预览 → 跑后日志还原 → 原地执行验证。Marketplace 33633。

## M38 Flex 精华吸收(2026-09-02 完成,P-立项-06 N-5 反向吸收)

### 来源
Mybatis-Flex-Helper(Marketplace 1.8万下载/4.32分)迭代史与真实差评 + mybatis-flex.com 语义对照表 + flex-core 1.11.8 jar 反编译 + DBX MCP「渲染导出→执行」七家族真机验证通道。Flex = **Apache License 2.0**(GitHub/Gitee LICENSE 双源确认)。

### 实施里程碑(分支 feature/m37-competitive-fusion,领先 master 15 提交)
- **M38.1** 锚点负例回归集 + 修复 2 个真实误报(826b9e5):`new Helper().lambdaQuery()` 撞名工厂调用被当服务链、普通类 `findByXxx` 被标 JPA 图标(JpaGutter 加 Repository 接口文本级守卫)
- **M38.2** 81 场景端到端金样矩阵(真实 Java 源码→PSI→converter→SQL 快照,语义基线 MP 3.5.17)+ 修复 4 个 MP 语义偏差(49e06c1/6ccc6ea):
  1. **or(Consumer) 内外连接词颠倒**:`or(w->w.eq(2).eq(3))` 渲染 `AND (2 OR 3)` → 修正为 `OR (2 AND 3)`(parseGroup 组内连接词恒 AND,外层由 orPending 表达)
  2. **ne 渲染 `!=`** 与 MP SqlKeyword.NE=`<>` 不一致(QueryWrapperParser/WrapperConverter/MpjLambdaConverter 共 5 处对齐)
  3. **负数字面量** `-1`/`-5L` 落 ExpressionEvaluator else 误判为占位符 → 新增 PsiPrefixExpression 求值 + SQL 剥离 Java 数值后缀 fFlLdD
  4. **set(false,col,val)** 布尔前缀剥离后仍渲染 SET → MP 语义应整体忽略(LambdaUpdateConverter 加 isFalseFlag)
- **M38.3** apply {i} 多绑定参数/引号转义链路验证已通(新增 S81/S82 边界场景)
- **M38.4** 触发面资产化:`docs/架构设计/预览触发面清单-说明文档.md`(9 provider 入口矩阵 + 8 形态细分 + 负例边界 + 登记约定)
- **M38.5** 描述页双场景话术(README + marketplace 页)

### gutter 左右键交互 + NPE 修复(39d5fe9)
- 重构 `SqlGutterNavigationHandler.navigate()`:按鼠标按钮区分左右键
  - **左键单击 → 直接预览 SQL**(一键路径最短)
  - **右键 → 弹出两选项菜单**(预览 SQL / 最近执行记录)
- **NPE 根因**:M37.1 方案 `BaseListPopupStep.onChosen` 在 `finalChoice=false`(悬停预览)分支误执行动作并返回 `FINAL_CHOICE`,导致 `ListPopupImpl` 抛 `NullPointerException("FINAL_CHOICE must not be null")`。修复:仅在 `finalChoice=true` 时执行动作,`finalChoice=false` 返回 `null`。同步修复最近执行记录二级列表。
- **为何不用自定义 GutterIconRenderer**:IntelliJ 2024.3 的 `LineMarkerInfo` 没有 `LineMarkerInfo(GutterIconRenderer)` 单参构造器(只有 7 参 handler 版),故用 handler + 鼠标按钮检测方案。

### EXPLAIN 真机矩阵放开达梦/金仓(ba30330)
支持矩阵(DBX 实证 2026-09-02):
- MySQL 5.7/8.4 `EXPLAIN FORMAT=JSON`(双版本复证)
- PostgreSQL 16 `EXPLAIN (FORMAT JSON, VERBOSE)`
- **金仓 KingbaseES V8** `EXPLAIN (FORMAT JSON)`(放开)
- **达梦 DM8** 原生 `EXPLAIN <sql>`(#NSET2 计划,放开)
- MariaDB 实例暂不可达(同 MySQL 语法族,标注)
- H2 `EXPLAIN PLAN FOR`、SQLite `EXPLAIN QUERY PLAN`(引擎级单测)
- Oracle(两步链)/SQLServer(会话 SET)保持 null 降级

### 已知未解决问题
**testDatabase 分片 skip**:DataSourceSelectorIntegrationTest + DbConsoleIntegrationTest 共 4 个测试,DBN `RemoteDriver` 子进程 `JdbcClassLoader` 仍 `ClassNotFoundException: com.mysql.cj.jdbc.Driver`。已尝试:驱动预置(9.7.0 版本匹配)、databaseDrivers.xml 修正、mysql jar 加测试 classpath 共 5 种方案。这是 M36.5.23d 注释明确记录的**已知未解决沙盒限制**——DBN 子进程 classpath 由内部 `RemoteDriver.classpath.txt` 控制,非文件复制可解。这 4 个测试为环境门控(`assumeTrue`),skip 是语义正确行为。打通路径:runIde + UI 配置数据源触发 DBN 驱动下载。

### 新踩坑(已入本地实践)
- **Kotlin 构造器默认参数不生成桥构造** → 加参数后增量编译下旧调用方字节码 `NoSuchMethodError`,须 `@JvmOverloads`
- **JUnit3 BasePlatformTestCase 测试方法名必须以 test 开头**(反引号名需 `test ` 起头),否则报 `TestSuite$1.warning`
- **LineMarkerInfo 在 2024.3 没有单参 GutterIconRenderer 构造器**
- **IJ 数据库驱动需通过 runIde UI 下载** 才能被 DBN 子进程加载,仅复制 jar 不够
- **doFirst 预置逻辑源路径** `.intellijPlatform/sandbox/.../config/jdbc-drivers` 在跑 runIde 后才存在

### 资产清单
- `sql/spec/SqlPreviewSpecMatrixTest` 81 场景端到端金样
- `WrapperAnchorDetectorNegativeTest` + `GutterProvidersNegativeTest`(9 provider 拉平哨兵)
- `docs/架构设计/预览触发面清单-说明文档.md`
- `testgen/TestAssetGenerator` 纯函数双形态 + 预览面板「生成测试」入口

相关:[[project-overview]] [[project-registries]] [[pitfall-slowoperations-edt-readaction]]