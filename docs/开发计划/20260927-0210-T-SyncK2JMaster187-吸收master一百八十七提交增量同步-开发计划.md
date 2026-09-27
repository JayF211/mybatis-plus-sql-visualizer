# T-SyncK2JMaster187 开发计划——master c07b8e3..bccbfc5 增量同步

> 立项：2026-09-27 02:10，用户指令「切换到 feature/kotlin-to-java，将主分支最近更新的内容同步过来并解决冲突、全量测试并修复问题、确保插件构建校验通过；同步功能不是合并分支，先出开发计划再实施」。
> 工作树：`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java`（分支 feature/kotlin-to-java，起点 40626f7 = T-SyncK2JMaster184 收官提交，与 origin 同步、工作树干净）。
> 锚点：`.port-sync` synced_master = **c07b8e3**（2026-09-23 T-SyncK2JMaster184 收官），本轮目标锚点 = **bccbfc5**（本地 master 当前 tip，含 13 笔未推送提交——按「同步范围量到实际 HEAD」定例钉本地 tip，不钉 origin/master；`.port-sync` 文件头注释写 origin/master，本轮在 note 里同时记录两值）。

## 1. 背景与目标

k2j 分支把 master 的 Kotlin 生产代码手工翻译成 100% Java，坚持「同步功能，不合并分支」：master 的 .kt 绝不进生产树；共享文件（build.gradle.kts / plugin.xml / bundle / CHANGELOG / MEMORY / 变更记录）走手工 union。增量范围 `c07b8e3..bccbfc5` 共 **187 提交**（102 docs + 30 fix + 19 feat + 4 perf + 3 test + 3 build + 合并与杂项），289 文件 +19398/−1213。

本轮交付四件事：①生产/测试/资源/文档全量移植且编译绿；②全量测试修复到 0 失败（testShards + testDatabase + agent 子项目）；③构建校验通过（buildPlugin 出包 + verifyGate 三目标 Compatible）；④锚点推进与计划/台账回填写（C8 收官）。

## 2. master 增量功能线（187 提交归组）

| 功能线 | 大致提交 | 落点 |
|---|---|---|
| T-McpDynamicToolset MCP 工具线全动态化 | 4bf3b6b、cd15454、5a1f6ca | mcp/McpToolsetDynamicRegistrar 新建（ASM 运行期壳类 + 程序化注册，jar 字节码零平台 MCP 引用）；mcp-optional.xml 面收窄；测试 McpToolsetShellFactoryTest/McpAuditGateTest |
| T-MarketCompatProxy 动态代理化 | 66964f7 | mcp 桥接族：McpExpectedBridge/McpGateFilterEpBridge/McpExpectedToolException（SqlvExpectedToolException）新建，兼容问题 14→2 + getEarlyDisposable 反射化 |
| T-McpGateDynamicFilter（O-68） | 4d10db1 | mcp/McpGateFilterRegistrar 新建 + McpConfigurable/McpToolGate（门控接平台过滤 EP） |
| T-McpFilePathUnify | fecd7cb、1872464 | mcp/McpFilePathResolver 新建 + 三个带 filePath 工具统一路径口径 |
| T-SqlSuppress（O-69）屏蔽规则 + 走查返修 | 0bc99b9..728577e 等 | log 三新建（SqlSuppressRule/SqlSuppressRuleManager/SqlSuppressManagerDialog）+ SqlLogEntryBuffer/SqlLogManager/SqlLogPanel 接入 + bundle 双语 + 通知组 |
| T-XmlInlineSql（O-72）+ O-77/O-78/O-81 | 395b933..7a7f3fe | xml 两新建（XmlInlineSqlDetector/XmlDirectText）+ ui 三新建（XmlInlineSqlPreviewService/XmlParamBackfillHints/gutter 行标记）+ XmlMapperParser/XmlPreviewModel/XmlParamTypeResolver/XmlPreviewService |
| T-ToolSiteBizFrame（M2/M3/M4） | 2eed8f9..7a6fc81 | agent 子项目 StackLocator/SenderLoop/EventBus + IDE 侧 AgentManager/AgentProtocol + 设置面板 bizJarPrefixes |
| T-PrefixValidate | 5f11629 | config/SourceFramePrefixesValidator 新建 + PluginSettingsPanel 前缀校验与一键修复 |
| identity 族（O-52 延伸/O-64 D1D2/O-59 返修） | c718e3d、8eb2c0a、fda14d5 等 | sql/IdentityInsertStatements 新建 + SqlExecutor/UnboundedWriteDetector/RollbackSqlGenerator/Dialect/execute 整批直跑（SqlAstParser 新建配套） |
| T-SqlSplitterTsqlGuard（O-60） | 4859a9d | util/SqlStatementSplitter（BEGIN 非块豁免 + 顶层 END 隐式边界） |
| T-DialectXcFamily（O-63） | 24e43f0 | sql/Dialect + DialectInference（OceanBase/openGauss）+ SqlPerfAnalyzer/ExplainSupport 联动 |
| T-SelectGuardScalarExempt（O-61） | 2616bc4 | mcp/McpSelectGuard 标量函数豁免 + 解析入口收拢 |
| O-66/O-49/O-70/O-71 杂项修复 | bc56ec5、133b4a2、dc7e69a、f025a67 等 | log/SourceCaptureGuide、SqlLogConsoleBridge、JdbcTemplateLogParser、converter/jpa/JpaAliasTableResolver、psi/jpa/HbmXmlEntityResolver、ui/SqlPreviewPanel 等 |
| T-GradleTaskInjectScope + T-PS7-02 | e7112f9、44ff97f | log/agent/PremainTaskScope 新建 + AgentManager（退避复位按建连时刻） |
| acp 行重构 | 本轮内 | acp/AcpIdeaMcpTransportProviderImpl 删除 → acp/AcpTransportRegistrar 新建 |
| T-BuildConcurrency | 3 笔 build + gradle.properties | gradle.properties（version 2026.9.23、Kotlin 编译 in-process 常驻、daemon jvmargs 统一 4096m/1536m）+ dev-support/gradle-gate.ps1 |
| 发行 v2026.9.23 | 420efb8、9aff126 | CHANGELOG、update/×2、plugin-change-notes.html、README、发行说明 |
| 纯文档（调研/计划/验收/走查） | 其余约 100 | docs 83 件直取 + CHANGELOG/MEMORY/变更记录 union |

## 3. 文件级移植矩阵（生产 78：19 新建 + 58 增量 + 1 删除；测试 84：28 新建 + 55 增量 + 1 删除；agent 8；资源 6；构建 5；dev-support 10；docs 83 + 根文档 union）

### W1 低层/基础面（22 件，含 4 新建）
sql：Dialect、DialectInference、ExplainSupport、SqlPerfAnalyzer、rollback/RollbackSqlGenerator（M5）+ IdentityInsertStatements*（新）；util：BatchPredicateMerge、SqlStatementSplitter（M2）；xml：ParamKind、XmlMapperParser、XmlPreviewModel（M3）+ XmlDirectText*、XmlInlineSqlDetector*（新2）；psi/jpa：HbmXmlEntityResolver（M）；converter/jpa：JpaAliasTableResolver（M）；database：DbTableSpecAdapter（M）；config：PluginSettings、PluginSettingsPanel、PluginSettingsFolderConfigurable、McpConfigurable（M4）+ SourceFramePrefixesValidator*（新）；actions：ConvertToSqlAction（M）。（*=新建翻译）

### W2 捕获链/agent 面（26 件，含 6 新建 + 1 删除）
acp：AcpIdeaMcpTransportProviderImpl（删）→ AcpTransportRegistrar*（新）；execute：ConnectionErrorClassifier、DataSourceSelector、MetadataInvalidator、SqlExecutor、UnboundedWriteDetector（M5）+ SqlAstParser*（新）；log：CallerSourceLocator、JdbcTemplateLogParser、LogSqlModels、LogThreadRouter、SourceCaptureGuide、SqlLogConsoleBridge、SqlLogEntryBuffer、SqlLogFileReader、SqlLogManager、SqlLogPanel（M10）+ SqlSuppressRule*、SqlSuppressRuleManager*、SqlSuppressManagerDialog*（新3）；log/agent：AgentManager、AgentProtocol、JdbcUrlDialects、PremainRunConfigurationExtension（M4）+ PremainTaskScope*（新）。

### W3 高层 MCP/UI 面（30 件，含 9 新建）
mcp：McpDialectDetector、McpSchemaContextService、McpSelectGuard、McpSourceLocator、McpToolGate、McpToolsetSupport（M6）+ McpExpectedBridge*、McpFilePathResolver*、McpGateFilterEpBridge*、McpGateFilterRegistrar*、McpToolsetDynamicRegistrar*、SqlvExpectedToolException*（新6）+ SqlVisualizer 十工具（CheckSql/CrudSql/Explain/MapperCheck/PerfAdvice/RenderLogs/RenderSql/ReverseSql/RollbackPlan/SchemaContext，M10）；ui：SqlPreviewPanel、SqlPreviewService、XmlParamTypeResolver、XmlPreviewService、highlight/DatabaseSchemaResolver（M5）+ XmlInlineSqlPreviewService*、XmlParamBackfillHints*、gutter/XmlInlineSqlGutterLineMarkerProvider*（新3）。

### W4 agent 子项目直取 + 测试移植
agent 子项目 8 件全 .java 直取（=master 逐字节；生产 3：EventBus/SenderLoop/StackLocator，测试 5：ConfigApplyEndToEndTest/SenderLoopBackoffTest/SenderLoopFrameTest/SourceJumpFunnelTest/StackLocatorToolSiteTest；已核实本范围 agent 零 .kt 变更）。src/test 84 件：28 新建 + 55 增量 + 1 删除——.kt 测试直取（测试树混放），但调 Java 生产类的调用点须过互操作适配（禁具名实参、`.INSTANCE`、顶层函数走 `XxxKt` 门面）；守卫测试按惯例适配扫描根到 `src/main/java` + `.java`（本轮守卫类：CatalogCrudDialectWiringGuardTest、IdentityWholeScriptWiringGuardTest、McpGateFilterRegistrationGuardTest、StackLocatorToolSiteTest 等逐个核对）。

### W5 资源/构建/文档
资源 6 件：plugin.xml、mcp-optional.xml、acp-transport-optional.xml、bundle en/zh、marketplace-custom-page.html——以 master 侧为准 union（k2j 侧若有历史专属行须保留，两侧行集合校验）。构建 5 件：build.gradle.kts、buildSrc mpv-testing/mpv-verification/MarketVerdictChecker、gradle.properties——union 时保留 k2j 三块（test kotlin srcDir / `-parameters` / Lombok 备选注释）。文档：docs 83 件直取 + CHANGELOG.md / docs/MEMORY.md / docs/变更记录.md 三册 union + README.md、update/×2、plugin-change-notes.html、dev-support 10 件、.zcodeignore。

## 4. 波次与提交链

- 并行登记：gradle 前先 `python C:/Users/USER/.zcode/cli/parallel_tasks.py check`，然后 `register --name t-synck2j187 --sandbox test-sandbox-k2jsync187`；构建沙盒临时行加在顶层 `intellijPlatform {}` 块（`sandboxContainer.set(layout.buildDirectory.dir("test-sandbox-k2jsync187"))`），C8 还原。master 侧 gradle.properties 已常驻 `kotlin.compiler.execution.strategy=in-process`（本轮 union 带入），无需再传 `-P`。
- 提交链八段：C1 立项计划（本文件）→ C2 构建链 union → C3 W1 → C4 W2 → C5 W3 → C6 agent+测试 → C7 资源/文档 → C8 收官（锚点推进 + 沙盒临时行还原 + 计划 §8 回填 + 变更记录台账行）。波内修复提交顺延编号。
- 波内并行 ≤3 个 general-purpose 子代理；子代理只跑定向测试、明令不跑全量/不构建校验/不 clean/不 --stop；单工作树作业，子代理直接改 k2j 树。**波次划分后必须拿 `git diff --name-status c07b8e3..master -- src/main/kotlin` 全集对账**（上轮波次清单曾漏 2 件被测试车道抓出）。
- 每波收口做一次中央编译（compileJava + compileTestJava + compileTestKotlin），跨批依赖在编译门暴露。
- 全量门禁：全部波次+移植完成后单派只读收口代理跑权威全量（testShards + testDatabase + `:sql-visualizer-agent:test` + buildPlugin + verifyGate 三目标），主控并行写文档收尾；收口期间不碰 src/main。testDatabase 前置：`timeout 10 docker info` 验 daemon 活性 + 确认 runIde 沙盒 jdbc-drivers 种子源在位（机制记忆第 11 条，缺则从 master 工作树 runIde 沙盒复制，日志应出现 `jdbc-drivers.xml pre-seeded`）。
- 作业期间 master 若再前进：post-anchor 增量按惯例一并吸收；吸收不了（时间不够）则如实登记留下轮，锚点只推到本轮实际吸收的 HEAD。

## 5. 移植规则（既定清单全量生效）

记忆 `project-kotlin-to-java-port-sync` 12 条系统性坑逐条生效，重点：①互操作族（Kotlin 调 Java 生产类：禁具名实参/`.INSTANCE`/顶层函数走 `XxxKt`/record 访问器无 get 前缀/data class copy 手工重建/泛型 LUB 显式标注元素类型）；②Kotlin 函数类型字段落 Function0/Function1，禁 SAM；③受检异常透传用 sneakyThrow（override 不能新增 throws）；④私有字段与 Kotlin 属性同名遮蔽 → 改名 xxxState；⑤Kotlin 默认参落显式重载；⑥守卫测试扫描根适配 src/main/java + .java；⑦elvis 内层 null 合并不许漏（`x != null && x.y() != null`）；⑧改名/删除全仓 grep 旧名（本轮：AcpIdeaMcpTransportProviderImpl 删除、SqlvExpectedToolException 系改名族）；⑨新枚举产出点写 `EnumName.CONST` 限定形态；⑩测试红先看 XML 计数与时间戳再判红（BUILD FAILED 三形态=没跑）；⑪testDatabase 种子链前置；⑫kotlinx 载体 Companion 三件套 + `-parameters` 不可移除。

结构性决策两处勿改：fatal 透传 `throw (Error) t`；fatal 守卫两段式（落地集 6 文件判定数精确相等 + 未判定站点总量基线——上轮 636，本轮新增 catch 站点按守卫读数重登记并留移植注）。k2j build.gradle.kts 三块（test kotlin srcDir / `-parameters` / Lombok 注释）union 时保留。共享文件 union 后做两侧行集合校验（k2j 台账行 byte-exact 比对）；CHANGELOG `[Unreleased]` 与 `### Changed` 子标题每版本唯一。

## 6. 待验证前提（实施者先证伪再动手）

1. **agent 子项目 8 件纯 Java 直取**——已核实（`git diff --name-only c07b8e3..master -- sub-projects/sql-visualizer-agent | grep -c '\.kt$'` = 0），实施时按 `git checkout master -- <路径>` 直取。
2. **ASM 动态注册器可直译**——McpToolsetDynamicRegistrar 用 ASM 生成运行期壳类，Kotlin 侧若用扩展函数/具名实参调 ASM API，Java 侧按坑①展开；壳类字节码形态以 McpToolsetShellFactoryTest 断言为准（该测试随 C6 移植后即成对照）。
3. **桥接类反射签名**——McpExpectedBridge/McpGateFilterEpBridge/McpTransportRegistrar 是 master 侧为规避 IPGP 裁剪 API/平台兼容问题引入的反射桥，Java 侧照 `Class.forName` 显式传平台 classloader + 接口 cast 虚分派形态翻译（勿用 invoke 直调 package-private 实现类）。
4. **acp 行替换面**——AcpIdeaMcpTransportProviderImpl 删除后全仓 grep 旧名清引用（plugin.xml/acp-transport-optional.xml/测试/守卫登记表）；AcpTransportRegistrar 的 EP 注册 FQCN 与直取后的 xml 一致。
5. **fatal 基线 636 必漂移**——本轮 log/mcp/config 新增大量 runCatching/catch(Throwable)（SqlSuppress 族、动态注册器、桥接类），收口时以守卫读数重登记，不许硬凑旧值；落地集 6 文件若判定数变化同样重登记。
6. **verifyGate 读数对齐目标换锚**——上轮前提是「与上轮逐项相同」，但本轮 master 自带 T-MarketCompatProxy（兼容问题 14→2），master 侧 verifier 读数已改善；k2j 的验收口径是**与 master 侧同轮读数一致**（for-removal/deprecated/internal/experimental 逐项对账），不再以 k2j 上轮读数为锚。experimental 读数可能因新 hint/gutter EP 增长，逐条归因。
7. **测试树 28 件新建可直取**——前提是它们引用的生产 API 在 k2j 侧同名同签名；互操作编译不过的允许改写为 Java 等价（309 轮有先例），但断言语义逐条保留。
8. **版本号 2026.9.18→2026.9.23**——gradle.properties union 后 k2j buildPlugin 出包名变为 mybatis-plus-sql-visualizer-2026.9.23.zip，验收按新名核对。
9. **`SqlvExpectedToolException` 命名**——master 侧新建名即 Sqlv 前缀（与工具名 sqlv_ 对齐），k2j 照抄不另改名；但 k2j 树内若有历史 McpExpectedError 与之并存，须核对两者分工（ExpectedError=fatal 门面 / ExpectedToolException=工具层）照 master 关系直译。
10. **锚点差恰为 187 提交**——已核实（c07b8e3..bccbfc5 = 187）。作业期间 master 前进按 §4 末条处理。

## 7. 验收门禁（收口代理只读执行，主控不碰 src/main）

1. compileJava / compileTestJava / compileTestKotlin 全绿；
2. testShards 全量 0 失败（预期规模 ≈534 类/6064 用例量级 + 本轮净增，跳过数以 docker 关闭态 6 为参照）；统计按 mtime 过滤本轮 XML；
3. testDatabase 5 套件 0 失败（前置：docker info 活性 + jdbc-drivers 种子两行日志在位）；
4. `:sql-visualizer-agent:test` 全绿且与 master 侧读数一致（本轮 master 侧 agent 93 例）；
5. buildPlugin 出包 mybatis-plus-sql-visualizer-2026.9.23.zip；
6. verifyGate 三目标 Compatible，读数与 master 侧同轮逐项对账（前提 6）；
7. 守卫读数：fatal 基线重登记后的守卫测试全绿；元守卫登记表如需对齐 k2j 源码事实，逐条登记；
8. 反向验证：守卫修复必须「摘掉修复行→必红」贴两次读数；六例红以上轮形态为参照。

## 8. 实施记录（收官回填）

**提交链（十二段）**：C1 计划 c0750ee → C2 构建链 026738c → C3 W1 292fec5 → C4 W2 9e3bb87 → C5 W3 ab4ecf9 → C6 测试移植 6aa9be3 → 中央编译修复 48fb239 → C7 资源/文档 5c82919 → 守卫基线重登记 2a03e72 → 门禁六簇修复 26b3857 →（C8 收官 = 本文件回填 + `.port-sync` 推进 + 沙盒还原 + 变更记录台账行）。

**波次实落**（对账后零遗漏）：W1 22 件清单实落 26 个 Java 文件（k2j 一拆多：OceanBaseDialect/OpenGaussDialect 新建、UrlSubprotocolDialects 独立、ParamTypeInfo/XmlStatement 拆件增量）；W2 26 件实落 33 文件（acp 三件套拆分、SqlAstParser 新建、AgentSession 落点修正）；W3 30 件实落 30 文件（McpGateFilterRegistrar 拆 EpRegistrar）。测试 84 件 + agent 8 件逐字节（git hash-object 全 IDENTICAL）+ docs 80 件直取 + dev-support 10 件直取。

**编译收口**：compileJava 首轮 6 错三处（SqlPreviewPanel lambda 捕获非终局变量、PremainTaskScope Decision 嵌套前缀、SqlPerfAnalyzer INSTANCE 契约）修后绿；compileTestKotlin 首轮 204 错 13 文件（具名实参/INSTANCE 补点/record 访问器/构造器补槽/字段遮蔽/`)`缺括号/Kt 门面）修后绿；三链最终全绿。

**门禁与守卫发现**（全量首轮 testShards 166 败/26 类，六簇全收口）：
1. 主根因＝`SqlSuppressRuleManager` 漏 final（@Service 轻服务平台强制，146 败连锁；`DataSourceSettings` 同病一并修）——**新坑：Kotlin class 隐式 final，Java 侧 @Service 类必须显式写**。
2. **新坑（本地独立复现实证）**：jsqlparser 5.3 `CCJSqlParserUtil.parseStatements` 同 JVM 首败抛异常、次败**静默返回 null**——master 侧 Kotlin 非空类型 intrinsic 转抛被 catch(Exception) fail-closed 接管，Java 直译丢判空即 NPE；修法＝`SqlAstParser` 出口 `Objects.requireNonNull` + UnboundedWriteDetector/MetadataInvalidator seam 判空（3 败，修后本地 107/107 绿）。
3. 守卫登记三处尾款：fatal 基线 636→**660**（真读数，KDoc 补 597→615→636→660 链）、ConvertToSqlAction PSI 豁免 2→**3**（master 侧已登记 4；O-72 载体候选新增站点）、SettingsRealityGuard prefix 两控件白名单照 **master 86876ed**（t-testperf7 分支，master 侧同红的先行修复）补登记。
4. ErrorPositionWiringGuard 豁免键口径＝相对仓库根（含 `src/main/java/` 前缀）。
5. MultiHostInjector 表断言 13 败判定为簇 1 下游污染（fixture setup 中断致注入器栈失衡），不修码。
6. **McpAuditGateTest 桥接挂起点死锁（本轮最重发现，全量两度挂死）**：k2j 把 master 的 suspend audit 折叠为同步 ToolBlock 承载，测试桥接层 `runBlocking { block() }` 内的 `yield()` 挂起 lambda 恢复被派回创建方事件循环，而创建方正阻塞在内层 joinBlocking——恢复永不到达（线程转储实证：Default worker 全空闲、主线程停内层 parkNanos；换 `Dispatchers.Default`、去外层 runBlocking 均无效）。定案=块内挂起点改 `delay(50)`（恢复经桥接层事件循环自身定时器兑现，与创建方上下文零耦合）；叠加 243 测试 JVM 平台 stdlib 2.0.21 遮蔽项目 stdlib 致 DebugProbes 读挂起帧报 Debug metadata version mismatch——按 testDatabase 显式绿跳口径撞墙跳过并打印原因，统一 stdlib 环境仍真跑。反向验证：`yield` 形态两度复现挂死、delay+绿跳形态定向 15 测试全绿。
7. **SqlResultPanelUiTest JUnit3 幻影测试方法**：`showErrorRich` 调用点的内联零参 lambda 合成 `testXxx$lambda$0` 被 JUnit3 判为非 public 测试方法（309 轮同坑），全文件共 6 处抽 `emptyLocateAction()` 工厂挂名后绿。
反向验证：fatal 基线改前红（expected 636 was 660）改后定向 29 测试绿；jsqlparser null 先精确复现 3 败再修复转绿；死锁形态两度全量复现、delay+绿跳形态定向绿且终局全量未再挂死。

**前提核销（计划 §6）**：①agent 8 件纯 Java 直取 ✓（0 .kt 变更实测）；②ASM 注册器直译 ✓（壳类经 McpToolsetShellFactoryTest 断言）；③反射桥形态 ✓（引用面对账：代码级 mcpserver/iomcp 引用为零，Class.forName 字符串与 master 同位同串）；④acp 替换面 ✓（旧名全仓 grep 零残留）；⑤fatal 基线漂移 ✓（636→660 重登记）；⑥verifyGate 对齐目标换锚 ✓（读数见下）；⑦28 件新测试直取 ✓（互操作适配零断言改动）；⑧版本号 ✓（出包 2026.9.23.zip）；⑨SqlvExpectedToolException 照抄 ✓；⑩锚点 187 提交 ✓（作业期间 master 未前进，post-anchor 漂移零）。

**最终门禁读数**：compileJava/compileTestJava/compileTestKotlin 三链全绿；testShards **6087/0/0/9**（4 片 1438/1484/1500/1665，时间 172/203/161/142s，预期对照行逐片在案）；testDatabase 0 失败（5 套件，jdbc 种子在位、docker 关闭态秒跳）；agent 子项目 20 套件 93 用例 0 失败（=master 侧 93）；buildPlugin 出包 mybatis-plus-sql-visualizer-2026.9.23.zip（6,946,892 字节）；verifyGate 三目标 Compatible——243=4 for-removal+15 dep+46 exp、253=6+18+44、262=7+73+44（for-removal/experimental 与上轮持平＝无翻译漂移；deprecated +5/+5/+6 逐条归因 master 本轮新 API 面：jsqlparser 5.3 Parenthesis 弃用、ExtensionPointName.getPoint(AreaInstance)（MCP/ACP 程序化注册）、Disposer.isDisposed、ActionToolbar.updateActionsImmediately）。

**收官提交**：C8 = 本文件回填 + `.port-sync` 锚点推进 bccbfc5 + build.gradle.kts 沙盒临时行还原 + 变更记录台账行（含本轮读数）。
