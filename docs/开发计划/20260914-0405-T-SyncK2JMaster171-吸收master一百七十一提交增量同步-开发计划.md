# T-SyncK2JMaster171 开发计划——master a0702f6..50d3fb9 增量同步

> 立项日期：2026-09-14　分支：feature/kotlin-to-java（worktree `mybatis-plus-sql-visualizer-kotlin-to-java`）
> 同步源：**本地 master 50d3fb9**（增量范围 `git diff a0702f6..50d3fb9`，171 提交；其中 origin/master 停在 cd172f5，本地另有 T-TestGuard/T-TestPerf 两笔未推提交一并吸收）
> 上轮收官：T-SyncK2JMaster23（22eb659，全量 3315/0/0/6 绿 + verifyPlugin 三目标 Compatible，锚点 a0702f6）
> 用户指示：同步功能、**不合并分支**（方案A手工移植不变）。

## 1. 背景与目标

master 在锚点之后经历了史上最大增量：2026-09-13 dev 全量合入 master（8d2c919，M37/M38 竞品融合与 M39 JPA 三波、M44 四 ORM 等长期分支内容一次落主），叠加 master 自研的 T-CrudSql 四轮、T-CrudColor 三轮、T-AutoFold、T-PickMpjErr、T-SelToggle、runIde/hotswap 职责分离、T-TestGuard/T-TestPerf 测试守卫与提速，以及 v2026.9.14-beta.1/.2 两个内测发行。文件级总量：生产源码 118 文件（36 新增 + 82 修改，约 +15.8K 行）、测试 89 文件（约 +14.4K 行）、资源/构建 8 文件、文档约 150 件。master 全量基线从 k2j 锚点期约 3315 用例增至约 3900 用例。

本轮目标：把上述增量全部移植进 100% Java 的 k2j 生产树，全量测试绿 + buildPlugin/verifyPlugin 三目标通过，锚点推进 50d3fb9。绝无 git merge；共享文件（plugin.xml/bundle/build.gradle.kts/CHANGELOG/MEMORY/变更记录）走手工 union，k2j 自有行零丢失。

## 2. master 增量功能线（171 提交归组）

| 功能线 | 主要提交 | 内容概要 |
|---|---|---|
| L1 四 ORM 转换器（M44，dev 带入） | dev 合并批次 | EasyQueryConverter / MdsConverter / QuerydslConverter / XbatisConverter 新建 + UpdateWrapperConverter 测试 + ConverterRegistry/WrapperConverter 前置派发接线 |
| L2 JPA 增强三波（M39，dev 带入） | dev 合并批次 | CRUD 操作转换（JpaCrudOperationConverter/JpaFromEntityMapper/JpaUpdateParser）、JOIN 改写（JpaJoinRewriter/JpaJoinOnResolver）、JpaQueryEnhancer、QueryByExampleConverter、SpecificationQueryConverter、JpaMetadata/JpaMetadataReader/RepositoryBaseTypes、psi/jpa 各 locator 增量、HbmNamedQueryGutterLineMarkerProvider、HbmXmlEntityResolver 增量 |
| L3 T-CrudSql 四轮 | 86ec27a/7c57609/33e490c/a072870/f66eba7 | 实体类与数据库表右键生成 CRUD SQL（CrudSqlGenerator/CrudTableSpec/DbTypeQuoteHint 三件 + GenerateCrudSqlFromEntityAction/FromDbTableAction/CrudSqlPreviewFlow + DbTableSpecAdapter）；真机反馈（弹窗回调出模态+候选分层+双击直开）；选择弹窗原生化（JBList 主题色双行渲染器+速度搜索）；JBPopup 浮层化（打字过滤/Enter/ESC） |
| L4 T-CrudColor 三轮 + T-SelToggle | 641c815/dbc45bf/e57a34e/afef857 | SQL 列表 CRUD 类型颜色标记（SqlListTypeColor/SqlListSelectionOutline 新建 + 渲染器色条融合 + PluginSettings 颜色配置）；类型过滤弹窗色块图例；渲染热路径逐格 repaint 消除；全选/全不选双按钮合一状态切换（buildTypeFilterContent 纯提取） |
| L5 错误一键上报（feedback 包） | dev 合并批次（T-ErrReport） | feedback 七文件：ErrorFingerprint/ErrorReportComposer/ErrorReportDialog/FeedbackAction/FeedbackApi/FeedbackReporting/IdeErrorReportSubmitter + plugin.xml 注册 |
| L6 log 域增量 | dev 合并批次 + master | EntrySourceMatcher（AGENT 来源判定）、SourceInterceptorTemplates、ExecutionTimeParser、ConsoleReplayGuard、LogStartupCapture、SqlClickPreview、SqlLogEntryBuffer/Manager/ConsoleBridge/AutoSaver/FileReader、HistorySqlLogPanel、SqlLogPanel、SqlToJavaPanel、LogToSqlPanel、SqlListFragmentRenderer、JdbcTemplateLogParser、LogSqlModels、SqlSourceInterceptorAutoInjector、SqlHistorySearchBar/SqlHistoryManager/SqlHistoryPanel 增量 |
| L7 预览/高亮/UI | 4a754ba + dev | T-AutoFold 字段列表自动折叠（SqlAutoFolding/SqlAutoFoldPlanner 新建 + SqlPreviewPanel/SqlPreviewService 接线）；SqlInputEditor/JavaCodeFormatter/JavaCodePreviewDialog/SqlSelectionDialog/DatabaseSchemaResolver/SqlCompletionContributor/diagram 三件/SqlDiffer/Dialect/PerfRulesBatch3 增量 |
| L8 平台/杂项 | a274e30/7218078 等 | runIde 与 runIdeHotSwap 职责分离（hotswap 起停收归 runIdeHotSwap）；T-PickMpjErr SQL转Java 选类 MPJ 异常逃逸 EDT 根治；SafeBrowser 新建；Ognl 增量（k2j 侧映射 OgnlEvaluator/OgnlExpr）；StartupUpdateCheck/UpdateSection/SettingsIO/PluginSettings(Panel)/PluginCacheManager；execute 三件与 DatabaseConsoleLauncher；psi（EntityResolver/WrapperCache/WrapperLocator/WrapperAnchorDetector/PreviewReceiverResolver/BatchOperationConverter）与 model（TableInfo/WrapperChain） |
| L9 测试守卫与提速（T-TestGuard/T-TestPerf） | a05b127 | BundleParityGuardTest/PluginXmlStructureGuardTest/BannedApiSourceScanTest 三族守卫 + test 任务全量提速配置（build.gradle.kts）。注意 BannedApiSourceScanTest 扫描路径写的是 src/main/kotlin，k2j 须适配为 src/main/java |
| L10 发行与文档 | c507833/cd172f5/aa27418/6f9bd5e/b1f5a87 等 | v2026.9.14-beta.1/.2 版本四件 + changeNotes；README 换 8 张 GIF 动图 + 验收文档新目录 + 架构文档同步 + memory 精简归档搬移 + 调研报告目录迁移；bundle en/zh 各 ±89 行；marketplace 帮助页/Timeout.html |

## 3. 文件级移植矩阵

### 3.1 生产代码（36 新建 + 82 修改）

**新建 36 文件（Kotlin→Java 全量转换）：**

| 包 | 文件 | 移植要点 |
|---|---|---|
| converter | EasyQueryConverter / MdsConverter / QuerydslConverter / XbatisConverter | 四 ORM 链式 API 识别与转换；注册进 ConverterRegistry 派发链 |
| converter/jpa | JpaCrudOperationConverter / JpaFromEntityMapper / JpaJoinOnResolver / JpaJoinRewriter / JpaQueryEnhancer / JpaUpdateParser / QueryByExampleConverter / SpecificationQueryConverter | M39 主体；record/sealed 收敛式、局部状态机收敛式按既定清单 |
| generator | CrudSqlGenerator / CrudTableSpec / DbTypeQuoteHint | CRUD SQL 生成核心 |
| actions | CrudSqlPreviewFlow / GenerateCrudSqlFromEntityAction / GenerateCrudSqlFromDbTableAction | 双入口 action + 预览流（JBPopup） |
| feedback | ErrorFingerprint / ErrorReportComposer / ErrorReportDialog / FeedbackAction / FeedbackApi / FeedbackReporting / IdeErrorReportSubmitter | 错误上报七件；IdeErrorReportSubmitter 对接平台 errorReportSubmitter EP |
| log | EntrySourceMatcher / SqlListTypeColor / SqlListSelectionOutline | 来源判定 + CRUD 颜色标记 |
| model | JpaMetadata | JPA 元数据模型 |
| psi/jpa | JpaMetadataReader / RepositoryBaseTypes | 元数据读取 + 仓储基类识别（RepositoryBaseTypes 若为顶层函数/常量按 XKt 惯例落位） |
| ui/highlight | SqlAutoFolding / SqlAutoFoldPlanner | 字段列表自动折叠 |
| util | SafeBrowser | 浏览器打开兜底 |
| ui/gutter | HbmNamedQueryGutterLineMarkerProvider | hbm 命名查询锚点 |
| database | DbTableSpecAdapter | 表右键适配 |

**修改 82 文件（增量翻译进既有 Java）：** 全清单以 `git diff --name-status a0702f6..50d3fb9 -- src/main` 为准，按 Wave 分组实施；其中 xml/Ognl.kt 的增量映射到 k2j 拆分后的 OgnlEvaluator/OgnlExpr 两文件（上轮拆分形态，勿新建 OgnlKt）。

### 3.2 测试（89 文件：23 KT 增量 + 15 JinK 增量翻译 + 51 新建适配）

k2j 侧形态普查结论：23 个 master 变更测试在 k2j 有同名 .kt（直取/增量）；15 个在 k2j 是 src/test/kotlin 目录下的 .java（增量翻译进 .java，代表性：SqlPreviewPanelTest/SqlLogEntryBufferTest/LogToSqlPanelTest/PartTreeParserTest/PluginVersionTest/CriteriaQueryConverterTest/DerivedQueryConverterTest 等）；51 个 k2j 无对应（master 新建测试为主，含 feedback 五套件、generator 两套件、四 ORM 四套件、JPA 六套件、三族守卫、SqlAutoFold 两套件等）。

处置规则：
- 新建测试若引用 Java 生产类：机械适配（具名实参→位置实参、record 访问器方法调用化、`project`→`getProject()`、SqlLogFileReader.INSTANCE 等）落为 .kt；
- 纯逻辑/无生产 API 依赖的可基本直取；
- 三族守卫：BundleParityGuardTest/PluginXmlStructureGuardTest 直取适配；**BannedApiSourceScanTest 扫描根路径 src/main/kotlin 改 src/main/java**（k2j 生产树位置）；
- JUnit3 前缀纪律（BasePlatformTestCase 系 test* 前缀 + lambda 合成方法坑）逐文件把关；
- 用例数守卫类断言（若 dev 侧 perf 红线测试含计数）按 k2j 实际口径对齐。

### 3.3 资源与构建（union，k2j 自有块零丢失）

- plugin.xml：新增 action（feedback/CRUD 双入口）、gutter provider、errorReportSubmitter EP 等注册逐条对齐；落地后跑 plugin.xml 活引用类审计（上轮教训：静态链不校验 factoryClass 存在性）；
- database-optional.xml：同上对齐；
- bundle en/zh：各 ±89 行 union，BundleParityGuardTest 守卫兜底；
- build.gradle.kts：T-TestPerf 提速配置 + a274e30 runIde/hotswap 职责分离 + changeNotes 9.14 段；k2j 自有块（hotswap 等）零丢失，diff 复核；
- gradle.properties：版本对齐 2026.9.14-beta.2；
- marketplace-custom-page.html / Timeout.html：直取；
- 文档约 150 件：直取为主（含 8 GIF + 2 PNG 二进制走 git checkout）；memory-archive 搬移按 master 归档形态收编后再回补 k2j 专属行；CHANGELOG/MEMORY/变更记录三册 union（**两侧行集合校验，防 master 发行重构吞 k2j 行重演**）；docs/调研报告 5 个未提交文件不在 git 历史内，不属同步范围。

## 4. 波次与提交链

| # | 提交 | 内容 | 门禁 |
|---|---|---|---|
| C1 | docs: 立项 | 本计划文档 | — |
| C2 | feat: Wave1 基础层 | model/psi(+jpa)/sql/xml/util/execute/database + EntrySourceMatcher 等 log 低层 | compileJava 零错 |
| C3 | feat: Wave2 converter 层 | 四 ORM + jpa 八新件 + converter 增量 | compileJava 零错 |
| C4 | feat: Wave3 generator/feedback/actions | CRUD 生成与入口 + feedback 七件 | compileJava 零错 |
| C5 | feat: Wave4 log 高层/ui/history/config/update | SqlLogPanel 族 + SqlPreviewPanel 族 + 颜色标记接线 + 设置项 | compileJava 零错 |
| C6 | chore: 资源/构建 | plugin.xml/database-optional/bundle/版本/gradle 两件/html + 活引用审计 | buildPlugin 出包 |
| C7 | test: 测试移植 | 89 文件分三批（新建适配/增量翻译/守卫） | 全量测试绿 |
| C8 | docs: 文档同步 | 直取 + 三册 union + README/GIF | 两侧行集合校验 |
| C9 | chore: 收官 | .port-sync 锚点推进 50d3fb9 + 台账 | verifyPlugin 三目标 |

## 5. 移植规则（既定清单全量生效）

1. 生产代码 100% Java，绝不 merge .kt 进生产树；
2. Kotlin→Java 机械转换缺陷清单（static import/drop 越界守卫/LinkedHashSet/Map.of 拒 null/assertEquals 装箱/throws Exception/test 前缀/record 访问器 x() 等 20+ 条）逐条生效；
3. 互操作铁律六条（record 禁 extends/禁命名实参/静态成员导入/非 get 前缀不合成属性/工具层反斜杠坑/merge-file --union 行级并集）；
4. 基线一致性判定 `git show <sha>:<f> | diff --strip-trailing-cr - <f>`；一致文件 `git checkout <sha> -- <paths>` 整取后逐一复核；
5. 新增反射入口登记 PlatformSignatureCrossVersionTest；Light service 显式 final；
6. gradle 前必跑 parallel_tasks.py check 并登记独立沙盒，终验后还原 sandboxContainer 行；push 留用户 GUI。

## 6. 风险清单

| 风险 | 缓解 |
|---|---|
| BannedApiSourceScanTest 路径假设 src/main/kotlin | 移植时改扫描根为 src/main/java（守卫语义不变） |
| plugin.xml 新注册类不可达（静态链不报、runIde 才炸） | C6 落地后逐条活引用审计（类文件存在 + 构造器/EP 语义） |
| master 发行重构吞 k2j 三册自有行 | union 后两侧行集合校验，不只看行数 |
| Ognl.kt 增量对应拆分文件错位 | 先 diff master Ognl.kt 增量再映射 OgnlEvaluator/OgnlExpr |
| dev perf 红线/计数守卫断言与 k2j 口径漂移 | 逐条核对断言对象，按 k2j 实际用例数对齐 |
| verifyPlugin 报告目录脏状态硬崩 | 重跑前清 build/reports/pluginVerifier |
| 测试提速配置改变守卫语义 | T-TestPerf 配置 union 后先跑三族守卫再跑全量 |

## 7. 验收标准

1. 全量测试 0 失败（基线 3315 → 预期 3900±，套件数与 master 同源核对）；
2. buildPlugin 成功出包（2026.9.14-beta.2）；verifyPlugin 243/253/262 三目标 Compatible；
3. k2j 自有行零丢失：CHANGELOG/MEMORY/变更记录/build.gradle.kts 两侧集合校验通过；
4. .port-sync 锚点 = 50d3fb9（50d3fb98052bdb066b8e1548c992ceb4d337a3ad）；
5. plugin.xml 活引用全部可达；bundle parity 守卫绿。

## 8. 实施记录

（实施过程中回填）
