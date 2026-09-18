# T-SyncK2JMaster309 开发计划——master 9def321..550b72e 增量同步

> 立项日期：2026-09-19　分支：feature/kotlin-to-java（worktree `mybatis-plus-sql-visualizer-kotlin-to-java`）
> 同步源：**本地 master 550b72e**（增量范围 `git diff 9def321..550b72e`，309 提交，503 文件：生产 157 件 +14343/-851、测试 165 件 +19020/-349、docs 140 件、构建/资源/杂项 41 件；master 侧零删除；master 侧 T-TailSweep Wave1/Wave2 已收官在 HEAD，登记表滞留条目均过期，锚点仍钉 550b72e 快照防并行合流漂移）
> 上轮收官：T-SyncK2JMaster176（0d75687，全量 testShards 4286/0/0/6 绿 + buildPlugin 出包 2026.9.18-beta.3 + verifyGate 三目标 Compatible，锚点 9def321）
> 用户指示：同步功能、**不合并分支**（方案A手工移植不变）；**先落本计划文件再实施**。

## 1. 背景与目标

锚点之后 master 完成了仅次于双轮合流的最大增量：**T-MergeDev2Master dev 全量合流落 master**（双树归一，唯一例外就是本 k2j 树——本轮即为补上这最后一棵）：MCP 工具集全链（mcp/ 19 生产新件 + McpConfigurable + mcp-optional.xml + mcpStub 编译桩 4 件 + 设置子页扩容）、执行链安全护栏（UnboundedWriteDetector/MetadataInvalidator/RollbackSnapshotStore/RollbackSqlGenerator/RollbackSqlDialog/ExecutionTimeoutGuard）、CRUD generator 与 SQL 切分命名族（SqlStatementSplitter/SqlStatementNamer/BatchPredicateMerge/ExplainIndexAdvisor）、T-SettingsTruth、T-PerfSweep4 十三项、O 系六连（IncludeCrossNs/ExprAstPrecheck/MysqlGStrip/SessionIdentityGuard/InjectionGate/ParamTypeContract）；**T-M56Phase2-Premain** 启动即捕获（premain 注入两新件 + agent 子项目 StackDigest/SourceJump funnel 增量）；**T-ReachAudit 七项**（心跳窗口/JOIN 图错误态/诊断计数/只写不读清理/审计工具 audit_reachability.py 入库）；**T-DialectAutoInfer** 方言智能推断（DialectInference/DataSourceUrlProbe/JdbcUrlDatabase + 设置双页改造）；**T-TailSweep Wave1/Wave2**（T-TS-01~13 收尾债清偿）；**T-TestDbDriver** testDatabase 驱动管线（buildSrc 约定插件重构 + database-lab）；dev 合流后修复三连（T-FixWriteSafe/T-Fix-SettingsScope/T-F8）；以及 v2026.9.18 正式版发行。

本轮目标：把上述增量全部移植进 100% Java 的 k2j 生产树，全量测试绿 + buildPlugin/verifyGate 三目标通过，锚点推进 550b72e。绝无 git merge；共享文件（build.gradle.kts/buildSrc/plugin.xml/bundle/CHANGELOG/MEMORY/变更记录）走手工 union，k2j 自有行零丢失。

## 2. master 增量功能线（309 提交归组）

| 功能线 | 主要提交 | 内容概要 |
|---|---|---|
| L1 dev→master 全量合流 | 147cb85 前半程 + 7c3d5ce/f44cf0a/f3e890e 回合 + 6b3e31e/271983a/92a6f50 等 | MCP 工具集全链 19 新件（CheckSql/CrudSql/Explain/MapperCheck/PerfAdvice/RenderLogs/RenderSql/ReverseSql/RollbackPlan/SchemaContext 十工具 + McpDbExecutor/McpDialectDetector/McpExplainFormatter/McpRenderInput/McpSchemaContextService/McpSchemaFormatter/McpSelectGuard/McpSourceLocator/McpToolErrors/McpToolGate/McpToolsetSupport/McpAuditLog 底座）+ McpConfigurable 设置子页 + mcp-optional.xml + mcpStub 桩 4 件；护栏链 UnboundedWriteDetector/MetadataInvalidator/ExecutionTimeoutGuard/ConnectionErrorClassifier/ExecutionErrorInfo + 回滚链 RollbackSnapshotStore/RollbackSqlGenerator/RollbackSqlDialog；切分命名族 SqlStatementSplitter/SqlStatementNamer/BatchPredicateMerge/ExplainIndexAdvisor；O 系 XmlCrossNsFragmentResolver/CrossNamespaceFragments/ParamKind/MysqlClientGSuffix/ExecConsoleContext/ExecuteConfirmMessages/DocWriteContext/ExecDocAutosaveGate；T-SettingsTruth + T-PerfSweep4 十三项（StatementHandler/SqlEvent/SenderLoop/Jsons agent 增量随之） |
| L2 T-M56Phase2-Premain | b503c42/c5dcb70/a11bd75 等 | PremainInjection/PremainRunConfigurationExtension 两新件（-javaagent 注入 + 端口前置协商 + premain 专用 jar 落盘 + 幂等/attach 跳过）+ SqlLogToolWindowFactory.java（master 侧 .java）直改 + agent 侧 StackDigest 新件/StackLocator 增量/三新测试 |
| L3 T-ReachAudit 七项 | a628a3b/f6112af/b5b588a/e6eda8c/dc4305c/858205e/3a92b5e | 心跳窗口（半死会话不再永久封口控制台桥）/JOIN 图错误态接线/重放丢弃与 EP 短路计数（CaptureDiagnostics）/只写不读残留删除/DiffType.CHANGE 退役/两守卫+四轴审计工具入库（dev-support/audit_reachability.py） |
| L4 T-TestDbDriver + T-Fix-TestDb | 6017fe4/3457f77 + buildSrc 五件 + database-lab 四件 | testDatabase 驱动管线打通：构建逻辑搬 buildSrc 约定插件（mpv-testing/mpv-release/mpv-runide/mpv-verification）+ database-lab（docker compose 国产方言库样例/DM8 与 Kingbase 载入脚本）+ McpDbSignatureChecker.java/PlatformSignatureChecker.java 增量 + docker-compose-dialects.yml |
| L5 dev 合流后修复三连 | 04c46b6/d200a66/7780339/563b3f9/94af8c3/e95a42a | T-FixWriteSafe（dispatchDocWriteOnEdt 写体过 write-safe 闸门）/设置导入按现值为底合并 + 项目级表设置按实体所属项目取值/T-F8 JOIN 图加载态抬到标签页宿主层 |
| L6 T-DialectAutoInfer | 29dd055/4f2de15/c9b93dc/be23010 等 | 方言智能推断：DialectInference 优先级合并 + DataSourceUrlProbe 反射探测（262.3 javap 实锤，社区版静默降级）+ JdbcUrlDatabase + PluginSettings.dialectAuto/ProjectPluginSettings.dialectAuto(null=跟随全局)/effectiveDialectAuto/DialectRegistry.effective + SettingsIO 白名单 + 设置双页 UI 改造 + bundle 4 新键 |
| L7 T-TailSweep Wave1/Wave2 | cbb7b2b/8d1d9c3/542b2d6/5999cbd/518d2a9/a062587/a7c3742 | T-TS-01 premain 回连建会话点补 clockMs；T-TS-03/04 write-unsafe 族收口+守卫扩形态；T-TS-05 F8 加载态宿主面结构断言+showJoinDiagram 异常兜底；T-TS-08/09 testDatabase 尾款；T-TS-06/07 守卫断言与 PSI 兜底补 project；T-TS-13 项目级设置语义与呈现收口 |
| L8 捕获真值链随合流入 | 92a6f50/c5dcb70/b503c42 + dev 系 | T-CaptureTruth 七项（log 新件族：CaptureDiagnostics/FirstCaptureGuide/EntryJumpResolver/CallerMethodRangeResolver/MapperXmlLocator/SqlErrorLineRecognizer/FocusTab）/T-ConsentTruth 注入确认三态/T-ClipboardIso（FakeClipboardInstaller.java 测试缝） |
| L9 v2026.9.18 发行与杂物 | 3bb5f7b/fb2609f/dc02ad7/24aafed 等 | 正式版 2026.9.18（版本号/CHANGELOG/发行说明/update 双 JSON/plugin-change-notes.html 外置/marketplace 页）；libs.versions.toml、.run、.gitignore、README、ci-verify.ps1/check_competitor_watch.ps1、verifier 豁免清单；docs 140 件（全景盘点 9~12 版/竞品第七轮调研/TailSweep 各计划/验收文档/memory-archive 搬移） |

## 3. 文件级移植矩阵

### 3.1 生产代码（62 新建翻译 + 90 增量翻译 + 1 Java 直改 + mcpStub 4 件直取 + 0 删除）

**新建 62 文件（Kotlin→Java 全量转换），按 Wave 分组：**

| Wave | 包 | 文件 |
|---|---|---|
| C3 低层 | util | BatchPredicateMerge / ExplainIndexAdvisor / SqlStatementNamer / SqlStatementSplitter |
| C3 低层 | xml / psi | CrossNamespaceFragments / XmlCrossNsFragmentResolver |
| C3 低层 | sql | DataSourceUrlProbe / DialectInference / rollback/RollbackSnapshotStore / rollback/RollbackSqlGenerator |
| C3 低层 | execute | ConnectionErrorClassifier / DocWriteContext / ExecConsoleContext / ExecDocAutosaveGate / ExecuteConfirmMessages / ExecutionErrorInfo / ExecutionTimeoutGuard / JdbcUrlDatabase / MetadataInvalidator / MysqlClientGSuffix / UnboundedWriteDetector |
| C4 捕获链 | log | CallerMethodRangeResolver / CaptureDiagnostics / EntryJumpResolver / FirstCaptureGuide / FocusTab / MapperXmlLocator / SqlErrorLineRecognizer |
| C4 捕获链 | log/agent | PremainInjection / PremainRunConfigurationExtension |
| C4 MCP | mcp | CheckSqlTool / CrudSqlTool / ExplainTool / MapperCheckTool / McpAuditLog / McpDbExecutor / McpDialectDetector / McpExplainFormatter / McpRenderInput / McpSchemaContextService / McpSchemaFormatter / McpSelectGuard / McpSourceLocator / McpToolErrors / McpToolGate / McpToolsetSupport / PerfAdviceTool / RenderLogsTool / RenderSqlTool / ReverseSqlTool / RollbackPlanTool / SchemaContextTool |
| C4 MCP | config | McpConfigurable |
| C5 高层 | ui | DegradationBanner / PrewarmAwait / RollbackSqlDialog |
| C5 高层 | ui/highlight | SqlCompletionCandidates |
| C5 高层 | actions | SelectedTableSqlTemplateActions / SqlTemplateShortcutConflictCheck |
| C5 高层 | 其他 | onboarding/OnboardingAnchorTip / update/UpdateZipGuard |

**增量翻译 90 文件：** 全清单以 `git diff --name-status 9def321..550b72e -- src/main/kotlin` 为准（M 90 件：log 16、xml 11、ui 8、config 8、ui/highlight 5、psi 5+jpa 2、log/agent 4、util 3、ui/diagram 3+nativefw 2+db 1、sql 3、execute 3、converter 2+jpa 3、feedback 2、update 2、model 2、ui/diff 1、ui/components 1、reverse 1、generator 1、actions 1）。⚠️ 上轮两处特殊映射本轮复查：master `LogEntryPreviewRecognizer.kt`→k2j `LogEntryPreviewRecognizerKt.java`、`LogSqlModels.kt`→`LogSqlModelsKt.java`+`LogSqlEntry.java`/`LogSqlParam.java`（增量按成员归属落对应文件，勿新建同名 Kt 文件）。

**master 侧 .java 直改 1 件：** `log/SqlLogToolWindowFactory.java`（M，k2j 同路径直 diff）。

**mcpStub 4 件（Kotlin 直取，非生产树）：** `src/mcpStub/kotlin/com/intellij/mcpserver/` 下 McpCallInfo/McpExpectedError/McpToolset/annotations/McpToolAnnotations——编译桩源集定性（对平台 MCP API 的编译期替身），随 master 形态 Kotlin 直取 + buildSrc/根构建 sourceSets 接线直取。

**删除 0 件：** master 本轮范围无删除（git diff name-status 零 D），上轮退役件（history 包/Outline/WrapperSearchService 等）master 侧已不在，无新增残留风险。

### 3.2 测试（165 文件：161 Kotlin + 3 Java + 1 资源）

- 161 Kotlin（107 新建 + 54 增量，以 `git diff --name-status 9def321..550b72e -- src/test/kotlin` 为准）：新建件落 .kt（引用 Java 生产类做机械适配：具名实参→位置实参、record 访问器方法调用化、`project`→`getProject()`、INSTANCE 单例）；54 增量件按 k2j 侧实际形态落同名 .kt 或 src/test/kotlin 下 .java；
- 3 Java：`McpDbSignatureChecker.java`（A）/`PlatformSignatureChecker.java`（M，⚠️ 新增反射入口登记 DataSourceUrlProbe）/`testutil/FakeClipboardInstaller.java`（A）直取；
- 测试资源 1 件：`docker-compose-dialects.yml`（M）直取；
- 纪律：JUnit3 test* 前缀、lambda 合成方法坑、剪贴板/定时器 flake 定例、三族守卫（BundleParity/PluginXmlStructure/BannedApi）按 k2j 扫描根 src/main/java 适配。

### 3.3 资源与构建（union，k2j 自有块零丢失）

- **buildSrc 整包新引入**（k2j 现无 buildSrc）：`build.gradle.kts` + 4 约定插件（mpv-testing/mpv-release/mpv-runide/mpv-verification）直取 master 终态；**k2j 根 build.gradle.kts 自有块逐块并进新形态**（srcDir(src/test/kotlin)/-parameters/Lombok 注释/hotswap/gifRecord 注释块等——master 本轮把构建逻辑搬进了约定插件，k2j 自有块落点逐个判断：测试源集与编译参数进 mpv-testing 或根文件、verifyGate/releaseGate 对齐 mpv-verification/mpv-release）；
- settings.gradle.kts：本轮范围零 diff，不动；
- gradle.properties：2026.9.18-beta.3→**2026.9.18**，其余逐行 diff；
- gradle/libs.versions.toml（M）+ gradle/plugin-change-notes.html（A）直取；
- plugin.xml（M）+ mcp-optional.xml（A）：MCP toolset/EP 注册、premain RunConfigurationExtension、方言推断设置页逐条对齐，落地后活引用审计；
- bundle en/zh（M）：MCP 键族 + 方言推断键 + TailSweep/SettingsTruth 键 union，BundleParityGuardTest 兜底；⚠️ 勿用带 BOM 写入工具碰 .properties；
- update 双 JSON（M）+ marketplace 页（M）直取（版本 2026.9.18）；
- database-lab 4 A + dev-support 3A2M（⚠️ verifier-ignored-problems.txt 与 run-verifier.sh union 时核对 k2j 扫描根适配行）+ .run（M）+ .gitignore（M）+ README（M）直取；
- sub-projects 12 件（5M src + 1M test + 6A：StackDigest 新件 + 三新测试 + sqlvistest 两件）Java 直取；
- 临时沙盒行 `sandboxContainer.set(layout.buildDirectory.dir('test-sandbox-k2jsync309'))` 实施期在位，收官还原。

### 3.4 文档（140 件直取 + 三册 union）

docs 下 140 件（开发计划 88、memory-archive 22、验收文档 9、调研报告 7、架构设计 5、发行说明 4、经验总结 1、设计规范 1 + 变更记录/MEMORY/测试沙盒清单等）直取为主；**三册 union**（CHANGELOG.md / docs/MEMORY.md / docs/变更记录.md——两侧行集合校验防吞 k2j 台账行，不只看行数）。主工作区未跟踪文件（20260919-0117 第八轮竞品调研报告）不在 git 历史内，不属同步范围。

## 4. 波次与提交链

| # | 提交 | 内容 | 门禁 |
|---|---|---|---|
| C1 | docs: 立项 | 本计划文档 | — |
| C2 | feat: 构建链 | buildSrc 整包 + 根构建 union + gradle.properties/libs.versions/plugin-change-notes + database-lab/dev-support/.run/.gitignore + sub-projects 12 件（含临时沙盒行） | agent 子项目 test 全绿 + 既有生产 compileJava 绿 |
| C3 | feat: 生产 Wave1 低层 | util/xml/psi/sql/execute 四包 21 新件 + 各包 M 增量 | compileJava 零错 |
| C4 | feat: 生产 Wave2 捕获链+MCP | log 7 新 + premain 2 新 + mcp 22 新（含 McpConfigurable）+ log/config 增量 + SqlLogToolWindowFactory.java 直改 + mcpStub 直取 | compileJava 零错 |
| C5 | feat: 生产 Wave3 高层+资源 | ui/actions/onboarding/update 7 新 + ui/feedback/converter/update 增量 + plugin.xml/mcp-optional.xml/bundle/update JSON/marketplace union | compileJava 零错 + 活引用审计 |
| C6 | test: 测试移植 | 161 Kotlin + 3 Java + 1 资源 | 全量 testShards + testDatabase |
| C7 | docs: 文档同步 | 140 件直取 + 三册 union + README | 两侧行集合校验 |
| C8 | chore: 收官 | 全量门禁修复收口 + .port-sync 锚点推进 550b72e + note 重写 + 计划 §8 回填 + 变更记录台账行 + 还原沙盒临时行 | buildPlugin 出包 2026.9.18 + verifyGate 三目标 Compatible |

## 5. 移植规则（既定清单全量生效）

1. 生产代码 100% Java，绝不 merge .kt 进生产树；**mcpStub 例外定性**：编译桩源集非生产代码，Kotlin 直取随 master 形态；
2. Kotlin→Java 机械转换缺陷清单（static import/drop 越界守卫/LinkedHashSet/Map.of 拒 null/assertEquals 装箱/throws Exception/test 前缀/record 访问器 x() 等 20+ 条，KOTLIN_TO_JAVA_RULES.md 为准）逐条生效；
3. 互操作铁律六条（record 禁 extends/禁命名实参/静态成员导入/非 get 前缀不合成属性/工具层反斜杠坑/merge-file --union 行级并集）；
4. 基线一致性判定 `git show <sha>:<f> | diff --strip-trailing-cr - <f>`；一致文件 `git checkout <sha> -- <paths>` 整取后逐一复核；
5. **新增反射入口登记 PlatformSignatureCrossVersionTest**：本轮 DataSourceUrlProbe（DataSourceManager→RawConnectionConfig.getUrl）必登记；premain 注入链如涉反射同登记；
6. gradle 前必跑 parallel_tasks.py check 并登记（本轮名 `t-synck2j309`、沙盒 `build/test-sandbox-k2jsync309`；登记表滞留 T-TS-* 五条目**不 kill 不触碰其沙盒**，只在自己的 worktree+沙盒内作业）；收官后 `done` 注销并还原沙盒临时行；push 留用户 GUI；
7. 移植引发测试假失败先清 build/classes+build/kotlin（陈旧产物劫持三形态），再排逻辑；
8. **testDatabase 首次入 k2j**：master 门禁 9/0/0/0；k2j 侧环境不具备时如实记「环境受限未验证」，禁拿受限环境读数冒充验证通过。

## 6. 风险清单

| 风险 | 缓解 |
|---|---|
| 并行会话推进 master | 锚点钉 550b72e 快照，后续增量留下一轮；不碰主工作区与其他 worktree |
| buildSrc 约定插件重构后任务名/行为漂移 | C2 先 `help --task` 点查 testShards/testDatabase/verifyGate/releaseGate 四任务在位再动生产 |
| k2j 自有构建块在重构中被吞 | 根 build.gradle.kts 两侧 diff 逐块核对（test kotlin srcDir/-parameters/Lombok/hotswap/沙盒行）；三册行集合校验 |
| mcp/ 全新链 22 件 + EP 注册不可达（静态链不报） | C5 逐条活引用审计（类文件存在+构造器/EP 语义+mcp-optional.xml 挂载点） |
| mcpStub 源集接线在 k2j 无 Kotlin main 形态下失效 | sourceSets 配置直取后编译冒烟；桩只被 mcp/ 生产码引用，compileJava 即验 |
| 107 件新建测试直取引用 Java API 机械适配量大 | 按 log/execute/mcp/config/ui 分批+编译驱动；三族守卫扫描根适配先行 |
| PlatformSignatureChecker.java M 吞 k2j 适配行 | M 件逐行 diff 后手工并集，禁整取 |
| 变更记录/CHANGELOG 并行撞车 | union 后两侧行集合校验 |
| bundle .properties 被写 BOM | 只用 git checkout/git apply，不用 PowerShell 写回 |
| verifyPlugin 报告目录脏状态硬崩 | 重跑前清 build/reports/pluginVerifier |
| 262 EDT/SlowOps 断言类测试在翻译后触雷 | 照 master 测试形态+既有两段式惯例（BGT 读探测+EDT 收口） |
| testDatabase 依赖 docker 方言库环境 | 环境不具备按「环境受限未验证」如实记录（§5 规则 8） |

## 7. 验收标准

1. 全量测试 0 失败（基线 4286 → 预期 ≈5117±，套件数与 master 同源核对；agent 子项目独立口径全绿）；
2. buildPlugin 成功出包（**2026.9.18**，含内嵌 agent jar 含 premain 入口）；verifyPlugin 243/253/262 三目标 Compatible；
3. k2j 自有行零丢失：CHANGELOG/MEMORY/变更记录/build.gradle.kts/buildSrc 两侧集合校验通过；
4. .port-sync 锚点 = 550b72e（note 字段同步重写为本轮记录）；
5. plugin.xml 活引用全部可达（mcp/premain/方言推断注册）；bundle parity 守卫绿；反射入口（DataSourceUrlProbe）已登记 PlatformSignatureCrossVersionTest。

## 8. 实施记录

提交链（滚动回填）：
- （实施时回填）
