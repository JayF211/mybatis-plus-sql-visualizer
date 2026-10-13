---
name: project-m37-competitive-fusion
description: M37/M38 竞品融合分支（worktree mpsv-m37 独占）——六里程碑+master 48 提交已合入并全量验证(2026-09-04)：EXPLAIN 达梦/金仓真机矩阵、81 场景金样、7 缺陷修复；logAutoOpen 弹回缺口待用户拍板
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d410d5ac-6c86-4184-98b4-07915d04de86
---

M37「P-立项-06 竞品精华融合」六个里程碑已于 2026-09-02 在 git worktree `F:\code\Java\Gitee\SongRenShuo\mpsv-m37`（分支 `feature/m37-competitive-fusion`，基点 master 910118c）实现并分里程碑提交（2116b82 / 2536745 / f7f1726 / 4f9f55f / 4fc66cb / b610b07 / eab3195 / ba30330），全量平台测试绿 + UI 分片 testShard1/2 绿（UpdateSectionTest 一例负载 flaky 单独跑通过）。EXPLAIN 真机矩阵已用 DBX 通道实证并放开达梦/金仓（ba30330）。**2026-09-05 分支已合入 dev**（dev 全量合并，合并提交 84eb34f，JpaGutter 冲突弃本分支前置守卫保 dev 三锚点结构，见 [[project-dev-merge-all-branches]]）；仍未合 master、未 push。**gutter 反查/自动展开/生成测试按钮的 runIde 沙盒视觉验证未做**（技术上可 computer-use 自动化，M36 系列先例；只有沙盒内配数据源密码才需人工）。

**2026-09-04 T-MergeM37：master（48 提交，JOIN 图原生框架 T-Dgm/T-Embed 全链 + JdbcTemplate/@NamedQuery 预览 + v2026.9.4）已反向合入本分支**——该分支长期被 worktree `mpsv-m37` 独占，git 操作须在该目录做（主工作区 checkout 会报 already used by worktree）。合并提交 3f89933（git 实际冲突仅 4 文件）+ 修复 96e19f2 + 交付记录 e510821，分支现领先 origin 56 提交**未 push**。冲突裁决：JpaGutter 双守卫收敛为 master `DerivedMethodLocator.isRepositoryMethod` 单入口；marketplace 页保 master 删 HEADER/LOGO + M37.0 TAGLINE；设置面板复选框取 master 基线 22。语义冲突 1 处：历史回环测试按 master T-SqlDuration 读回剥分号行为对齐（`SqlLogFileReader` 读端 `removeSuffix(";")`）。全量 3200/0 绿，verifyPlugin IU-243/253/262 通过。收尾追加（同日）：bundle 重复 key `gutter.pages.accessible.name` 去重（28bf18a——中英各两处、值相同，保留 55 行与 tooltip 成组处，删后追加重复，行为零变化）；功能使用说明落盘 `docs/架构设计/M37M38新增功能-说明文档.md`（e80e27b，入口/用法/EXPLAIN 方言矩阵/已知边界，仓库 docs/MEMORY.md 索引已登记并补漏登的预览触发面清单行）。

EXPLAIN 真机矩阵（DBX，2026-09-02）：MySQL 5.7/8.4 `EXPLAIN FORMAT=JSON` 双版本通过；PG 16 `EXPLAIN (FORMAT JSON, VERBOSE)` 通过；金仓 V8 `EXPLAIN (FORMAT JSON)` 通过（连 VERBOSE 变体未测，渲染保守用 FORMAT JSON）；DM8 原生 `EXPLAIN <sql>` 通过（#NSET2 计划）；MariaDB 实例暂不可达（同 MySQL 语法族）。Oracle/SQLServer 保持 null 降级。

关键实现事实：
- M37.2 EXPLAIN：`Dialect.renderExplainStatement(sql): String?`（null 降级）——MySQL/MariaDB `EXPLAIN FORMAT=JSON`、PG `(FORMAT JSON, VERBOSE)`、H2 `PLAN FOR`、SQLite `QUERY PLAN`；Oracle/SQLServer/DM/KingBase null（KingBase 真机验证后才放开）。按钮复用 SqlExecutor.execute 全链，EXPLAIN 后控制台文档会变为 EXPLAIN 语句（复用路径 setText 既有行为，已知取舍）。
- M37.3：`logAutoOpenOnCapture`（默认已改 true）的自动展开行为是**复活**——F-44 合并 Tab 时实现被删但设置项/勾选残留（"形同虚设"），挂在 buffer 无条件 listener（修 D2 语义），60s 防抖，`SqlLogManager.shouldAutoShow` 纯函数测试缝。
- **2026-09-05 logAutoOpen 两问已核实（评估模式，未改代码）**：①「默认开是否合理」——成立（竞品"每次手动开面板"抱怨驱动；三缓冲=60s 防抖/show 不抢焦点/升级前已持久化 false 者保留），但实现有真缺口：`shouldAutoShow` 只看 60s 防抖、无手动关闭记忆，用户关窗后下一条 SQL（≥60s）弹回反复骚扰=未来差评源。②「触发前提」——已确认仅在捕捉态生效，双门禁：Filter 挂载守卫 `isCapturing()`（SqlLogConsoleBridge.attachFilter 开头，桥由 SqlLogManager 接线 `{ capturing }`；`buffer.addEntry` 全库唯一调用点=Filter 回调）+ stopCapture 置 `buffer.paused=true` 安全网（addEntry 首行丢弃）。精确语义：开关开但从未捕捉（logAutoCaptureOnStart 也关）永不弹；「停止捕捉」= 会话级逃生门，同时断捕获与自动展开；捕捉态含"启动时自动捕捉"自动进入。细节：去重丢弃条目仍 notify（徽章计数）可触发监听，filter 关键字丢弃不 notify。
- **2026-09-05 T-AutoShowSuppress 已交付（用户拍板选了手动关闭抑制方案；b7a36b1 立项 / 19bf257 实施 / bba0e4f 文档）**：用户手动关闭日志窗→本会话抑制自动展开（`autoShowSuppressedByUserClose` 会话标志 = `shouldAutoShow` 新第三参）；检测用两参 `stateChanged(ToolWindowManager)` + 「可见→不可见」转换沿（`isUserCloseTransition` 纯函数，自动展开只产生反向沿不误判）；设置开关 false→true 重启用沿解除抑制（防"重开设置不生效"）；**开始捕捉不解除**（捕捉≠窗口可见性诉求，设计取舍）；项目服务重启自然重置。javap 实证补充：243 平台类在 **app-client.jar**（app.jar 没有 ToolWindowManagerListener）；javap 普通输出未标 default 关键字，须 `-v` 看 flags（无 ACC_ABSTRACT 即 default）——该接口 9 方法全 default，Kotlin 仅覆写两参即可。测试 SqlLogManagerAutoShowTest 4→7、全量 3203/0。真机验证（实点窗口关闭按钮）待 runIde 轮次。附带发现：**`logStopOnToolWindowClose` 在本分支无消费方**（仅设置面板 checkbox + State 字段；实现在 joinfw 并行分支未合入本分支）。
- M37.1：`log/EntrySourceMatcher.kt`（反查窗口匹配 `|Δ|≤30`，窗口内优先+同文件兜底非排除）、`SourcePathResolver`（模块 content root 多源根容错）、`LogEntryPreviewOpener`（gutter 无面板实例的预览入口）；gutter 点击改为两级 ListPopup，首项保持原预览。
- M37.4：`testgen/TestAssetGenerator` 纯函数双形态；SQL 脚本落盘 `src/test/resources/sql-visualizer/`。
- M37.5 Flex：**暂缓**（报告 `20260902-0120-MyBatis-Flex支持可行性-调研报告.md`）——两段式条件 API（where(QueryColumn)→Builder.eq）与 MP 单段式结构性不同、同名 QueryWrapper 类锚点误解析风险；重启条件在报告第五节。

新踩坑（已入本地实践）：
- Kotlin 构造器默认参数**不生成桥构造**——加参数后增量编译下旧调用方字节码（既有测试）NoSuchMethodError，须 `@JvmOverloads`（新旧双名同模板策略的新证例，[[project-m36-native-finish]] 同类思路）。
- 并行任务沙盒隔离：`parallel_tasks.py check` 提示活跃任务时，`build.gradle.kts` 顶层 intellijPlatform 加 `sandboxContainer.set(layout.buildDirectory.dir("<登记沙盒名>"))` 临时隔离，用完还原。
- junit 反引号中文测试名若断言期望写错（如 capitalize/尾部断言骨架），失败信息在 XML 但 message 可能只有 AssertionError 无 diff——需看行号回源码核对期望。

M38「Flex 精华吸收」也已于 2026-09-02 实施完成（826b9e5 / 49e06c1 / 6ccc6ea / 7d85a5c），全量 test + testShard1/2 绿（testDatabase 需 Docker，本机未启动未跑）。**M38 修复的真实缺陷（均由新回归矩阵复现）**：
1. gutter 锚点两处误报——`new Helper().lambdaQuery()`（qualifier 为 new 表达式的撞名工厂调用被当服务链）、普通类 PartTree 方法名 `findByXxx` 被标 JPA 图标（JpaGutter 已加 Repository 接口文本级守卫）；
2. **or(Consumer) 内外连接词颠倒**——`or(w->w.eq(2).eq(3))` 渲染 `AND (2 OR 3)`，应为 `OR (2 AND 3)`（parseGroup 组内连接词恒 AND、外层由 orPending 表达）；
3. **ne 渲染 `!=`** 与 MP SqlKeyword.NE=`<>` 不一致（QueryWrapperParser/WrapperConverter/MpjLambdaConverter 共 5 处对齐）；
4. **负数字面量** `-1`/`-5L` 落 ExpressionEvaluator else 误判为占位符（新增 PsiPrefixExpression 求值 + SQL 剥离 Java 数值后缀 fFlLdD）；
5. **set(false, col, val)** 布尔前缀剥离后仍渲染 SET（MP 语义应整体忽略，LambdaUpdateConverter 加 isFalseFlag）；
6. last("LIMIT 5 OFFSET 10") 的 OFFSET 丢失、having(String) 字符串版静默丢弃（均修复）。
已知未修：字符串版 QueryWrapper **消费形态**条件丢失（`QueryWrapper w = new...eq("id",3); service.remove(w)` 输出 DELETE 无 WHERE）——deleteConsumed 分支硬编码 QueryWrapperParser，字符串列链需走 ConverterRegistry 分派，属两套 parser 合并工程，记录于矩阵文件尾注。M38 资产：`sql/spec/SqlPreviewSpecMatrixTest` 81 场景端到端金样（真实 Java 源码→preview→SQL 快照，语义基线 MP 3.5.17，禁用实际输出重生成期望）、`WrapperAnchorDetectorNegativeTest` + `GutterProvidersNegativeTest`（9 provider 拉平哨兵）、`docs/架构设计/预览触发面清单-说明文档.md`。注意：BasePlatformTestCase（JUnit3）测试方法名必须以 test 开头，反引号名需 `test ` 起头否则报 TestSuite$1.warning。

相关：[[project-overview]] [[project-registries]] [[feedback-popup-chooserbuilder-single-click]]
