# T-SyncK2JMaster29 开发计划——吸收 master 二十九提交增量同步

> 立项时刻：2026-10-06 02:15；作业树：`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java`（feature/kotlin-to-java，tip `3f7134c`，与 origin 零债）。
> 用户指令：将 master 最近更新同步到 feature/kotlin-to-java，**同步功能不是合并分支**，解决冲突、全量测试并修复、确保插件构建校验通过；实际调整代码前先出本计划。
> 机制依据：K2J 端口分支同步机制（`.port-sync` 锚点 / .kt 手工翻译不合并 / 共享文件 union / 提交链八段 / 系统性坑 20 条预置），见项目记忆 project-kotlin-to-java-port-sync。

## 一、任务背景与范围裁定

- 同步范围：锚点 `4432849`（T-SyncK2JMaster22 收官值）→ master tip `7f424df`，**29 提交**。收官前与回写前各查一次两分支是否前进（坑 18）。
- 用户前提「主分支更新了多功能」**本轮成立**（上轮 Master22 曾勘正为全 docs）：本轮为真功能增量轮——生产 25 个 .kt 文件（889+/171-，新建 3）+ 测试 18 件（新建 5）+ 构建/资源 5 件 + docs 20 件（1916+）。
- 不扩面：dev（master..dev=210）与 t-statementweave 车道的增量**不在本轮**；只吸收 master 锚点区间。
- 主仓 master 工作树不切分支（主仓在 master、另有 dev/tsw worktree 各占其支），全部作业在 k2j worktree 内。

## 二、增量构成（29 提交按功能族分七族）

1. **增强捕获确认族**（T-FirstUsePremainConsent R1/R2/R3 + T-FirstUseFreshInstallConsent，`3b1f035`/`1240383`/`854d645`/`1631c47`）：`AgentManager.kt` +255（confirmEnhancedCapture 三态单点、SETTINGS_CHANGE 上升沿、maybeAskConsentAtStartup、backfillRunningProcesses 判据勘误+诊断）、`PremainRunConfigurationExtension.kt` +15、`LogStartupCapture.kt` +9（execute 顶部接线）、`RunConfigurationProcessListener.kt` +86；plugin.xml +6（agent 通知组）、bundle 双语各 +5 键；测试：`AgentPremainConsentTest`(新,444 行) + `AgentConsentFlowTest`/`AgentManagerFallbackTest`/`SourceCaptureGuideEnableFlowTest` 增量。
2. **JPA 字面量安全族**（T-JpaDeleteWhereGuard 同构普查批，`6062c26`/`968120c`/`cbb21ec`/`359df45`/`57de51a`/`aa1ed44`/`1b96f1d`）：`JpaSqlLiteralMasking.kt`(新,44)、`JpaUpdateParser.kt` +83（DELETE 头别名前瞻+parseWhere 白名单兜底）、EasyQuery/Mds/Querydsl 三 Converter、`HqlConverter.kt` +35、`JpaFromEntityMapper.kt` +11、`JpqlConverter.kt` +9、`UnboundedWriteDetector.kt` +32、`SqlAstParser.kt` +17；测试：`JpaSqlLiteralSafetyTest`(新)、`DemoJpaSqlPipelineTest`(新,Heavy 真实 demo 语料)、三链 converter 测试 + `SqlAstParserTest`/`UnboundedWriteDetectorTest`/`PreviewEndToEndTest`/`JpaUpdateConverterTest` 增量。
3. **HQL UPDATE 实体名提取族**（T-HqlUpdateEntityMap，`7f26113`/`384ce41`）：`HqlEntityNameExtractor.kt`(新,51)、`CustomDaoHqlLocator.kt`（收敛消第五份拷贝）、`HqlQueryLocator.kt` +8、`SqlPreviewService.kt` +17；测试 `HqlEntityNameExtractorTest`(新) + `HqlQueryLocatorTest` +38。
4. **MCP 动态工具集 ASM 修复族**（T-McpDynamicToolset，`818af1b`）：`TinyClassFileWriter.kt`(新,117 手写字节码生成器)、`McpToolsetDynamicRegistrar.kt` shellBytes 换实现（-39+新增调用）、build.gradle.kts 删 `org.ow2.asm:asm:9.7.1` 依赖 4 行；测试 `McpScopeSourceScanTest` 新增 testNoOrgObjectwebAsmReferenceInMcpPackage 守卫。
5. **MCP 走查收口族**（T-McpWalkFindings，`70ac7b9`）：`McpSourceLocator.kt` +35（行定位=行内首非空白+真空行毒点）、`SqlVisualizerMapperCheckTool.kt` +8（模拟误报）、`McpSchemaContextService.kt` +52（source 来源披露行）；测试 `McpSourceLocatorRenderAtLineTest`(新) + MapperCheckToolTest +55 / McpSchemaContextIndexCacheTest / McpSchemaContextScopeTest +109。
6. **UI 设置面板**（T-FramePrefixResize，`dd140b5`）：`PluginSettingsPanel.kt` +56（两前缀输入框拖拽分栏）；测试 `FrameSkipPrefixesFieldVisibilityTest` 增量。
7. **发行 v2026.10.6 与 docs**（`e009e96`/`23be09e`/`d4a5367`/`7f424df` 等）：CHANGELOG、README、发行说明 2 件、`dev-support/gitee-release.py`(新)、e2e-fake-ide evidence 3 件、docs 开发计划 10 新 + 调研报告 3 新 + `docs/MEMORY.md`、`docs/变更记录.md`。

## 三、待验证前提

| # | 前提 | 状态 |
|---|---|---|
| V-a | 四册库内 blob 口径：CHANGELOG/README k2j 侧相对锚点 **0 diff → 整册直取**；MEMORY/变更记录 三方合并（base=4432849/ours=k2j/theirs=master 三侧全 git blob）预演 **rc=0 零冲突**。工作树 CRLF 副本口径会把四册全部误判冲突（坑 18 前半本轮预演时当场复现，已勘正）；合并稿全程 blob 口径、tmp 文件按册隔离（坑 17） | ✅ 已证 |
| V-b | DemoJpaSqlPipelineTest 语料相对路径 `File("..", "mybatis-plus-sql-visualizer-demo/jpa-hibernate-demo/...")`：兄弟目录在 k2j worktree 同层 **已确认在位**；待证 gradle 测试 workingDir 解析与 master 同形态（master 侧同码在跑，预期成立） | 部分已证，门禁实证 |
| V-c | k2j `McpToolsetDynamicRegistrar.java` 的 `shellBytes(Class,Class,String)` 签名与 master 新实现（内部换 TinyClassFileWriter）可无缝对接；k2j 侧 internal 升 public 惯例（单测直触达）保留 | 待编译实证 |
| V-d | AgentManager.java +255 行翻译后 consent 族测试绿：坑 4（私有字段遮蔽→改名 xxxState）/坑 5（默认参→显式重载）/坑 14（@Service 必须 final）/坑 20（boolean is 前缀）按清单预置 | 待定向实证 |
| V-e | TinyClassFileWriter 纯 Java 手写字节码零平台依赖，直译无互操作坑（源 117 行无平台类型） | 待编译实证 |
| V-f | 测试基线漂移：上轮 6101/0/0/9 → 本轮新增测试类 5 + 存量增量，预期 +40~60 例，skip 维持 9（docker 桶） | 待门禁实证 |
| V-g | 守卫测试扫描根适配两处（坑 6 惯例）：`AgentPremainConsentTest` 源码守卫扫 LogStartupCapture、`McpScopeSourceScanTest` ASM 守卫扫 mcp 包——`src/main/kotlin`+`.kt` → `src/main/java`+`.java`，适配后须保判别力（反向验证抽查） | 待定向实证 |

## 四、实施策略与提交链（八段缩编）

- **C1** 本计划落库（k2j 分支）。
- **C2 构建链**：`build.gradle.kts` 手工删 asm 依赖 4 行（k2j 自有块——Lombok/测试 srcDir/-parameters/buildSrc 约定注释——逐一保全，先 `git diff 4432849 -- build.gradle.kts` 全量对账防 drift）；plugin.xml 与 bundle 双语按「grep k2j 专属行→无则直取」判定（预期直取，通知组与键名两侧同形）。
- **C3~C5 生产三波**（3 个 general-purpose 子代理并行，文件集不相交；并发 ≤3）：
  - **波A JPA/HQL/转换器/检测器（14 文件）**：JpaSqlLiteralMasking*、HqlEntityNameExtractor*、JpaUpdateParser、CustomDaoHqlLocator、HqlQueryLocator、HqlConverter、JpaFromEntityMapper、JpqlConverter、EasyQueryConverter、MdsConverter、QuerydslConverter、SqlAstParser、UnboundedWriteDetector、SqlPreviewService（* 为新建）。
  - **波B 捕获链（4 文件，量最大）**：AgentManager、PremainRunConfigurationExtension、LogStartupCapture、RunConfigurationProcessListener。
  - **波C MCP+UI（7 文件）**：TinyClassFileWriter*、McpToolsetDynamicRegistrar、McpSchemaContextService、McpSourceLocator、SqlVisualizerMapperCheckTool、PluginSettingsPanel、AgentMain.java（agent 子项目两侧同 Java，对照吸收 8 行）。
  - **子代理纪律**：只落文件+按坑清单自查，**不跑 gradle**（构建并发让给主控中央收口，也避免三路 gradle 互撞）；互操作坑 20 条写进派单（具名实参/INSTANCE/XxxKt/record 访问器/copy/泛型 LUB/Function0-1/sneakyThrows/字段遮蔽/默认参重载/枚举限定产出点/`@Service` final/elvis 内层 null 合并）。
  - 主控中央编译收口（compileJava）过后按波分三段提交（C3/C4/C5）。
- **C6 测试移植**（主控直做）：18 件 .kt 按测试树 .kt 混放惯例逐文件直取（4 新建+14 增量；改前核对 k2j 侧同名文件有无 k2j 专属行——McpToolTextResult 相关断言保形）；守卫扫描根适配两处（V-g）；`SettingsTruthTest` 广播点守卫按 k2j 单点同构判定。
- **C7 文档同步**（主控直做）：15 个 docs 新增逐文件直取（**禁目录级**——变更记录册在 master delta 内的教训同源）；CHANGELOG/README 整册直取（V-a）；MEMORY/变更记录 三方合并稿回写（全 blob 合并、回写后 k2j 专属行 byte-exact 校验：MEMORY 专属行数、变更记录 k2j 台账行数与合并前相等）；gitee-release.py + evidence 3 件直取。
- **C8 收官**：`.port-sync` 锚点推进 `synced_master = 7f424df` + note 重写为本轮全记录；本计划 §八实施记录回填；`docs/变更记录.md` 本轮台账行；还原 build.gradle.kts 沙盒临时行（任务专属沙盒名禁随提交入库）。

## 五、冲突解决预案

- 预演结论（V-a）：四册无真冲突。MEMORY/变更记录用三方合并稿（rc=0）；CHANGELOG/README 直取 master blob。
- 执行坑预置：三方合并 current 一律取 git blob 勿取工作树（坑 18，本轮预演已两度复现 CRLF 伪冲突）；tmp 文件按册隔离（坑 17，e2bfb8f 前科）；回写前 `git log` 查 k2j 分支是否被并行会话前进；合并后做两侧行集合校验（k2j 专属行 byte-exact），不只看行数。

## 六、门禁与验收口径

- gradle 前置：`python C:\Users\USER\.zcode\cli\parallel_tasks.py check` → 登记 `t-synck2jm29`（沙盒 `test-sandbox-k2jsynkm29`）；跑全量前 `timeout 10 docker info` 验 daemon。
- 分层三档：①各波定向（主控编译收口+consent/JPA/MCP 关键定向类）；②**权威全量**——实施完毕单派只读收口代理单路跑 `testShards`（默认 5 片）+ testExternal + agent 包，主控并行写文档不碰 src/main；③构建校验（本轮大改动必跑）：`verifyGate` 三目标（243/253/262）+ `buildPlugin`。
- `testDatabase` 前置（坑 11）：确认驱动种子链源在位（master 工作树 runIde 沙盒 jdbc-drivers），缺则先种，日志须见 `jdbc-drivers.xml pre-seeded`。
- 基线对照（上轮 Master22 终态）：testShards 6101/0/0/9 + agent 20/93/0 + verifyGate 243=4+15+46、253=6+18+44、262=7+73+44 + buildPlugin 2026.10.6.zip + testDatabase 5m17s 绿。
- BUILD FAILED 先看报告 XML tests 计数与时间戳再定性（坑 10 三形态缓存竞争）。

## 七、风险与回退

- R1 三子代理并行写同一工作树：文件集互不相交清单制；主控收口编译兜底跨批依赖。
- R2 AgentManager +255 行为最大单件翻译：坑清单预置+consent 族定向（AgentPremainConsentTest 444 行含反向守卫）。
- R3 DemoJpaSqlPipelineTest 为 Heavy 测试：桶位归属与 workingDir 语义看门禁实证（V-b），不预改测试分片脚本。
- R4 fatal 守卫基线漂移：新增 try/catch(Throwable) 站点（JpaSqlLiteralMasking 等）→ fatal 站点基线 666→N 守卫实测定案后重登记（结构性决策两处勿「修正」）。
- R5 CRLF/交错回写：全 blob 合并稿+按册隔离+回写前后置校验（V-a/坑 17/18）。
- R6 gradle 缓存竞争：三形态定性规则（坑 10），等待重试不误判红。
- 回退：分支未 push，所有提交 `git reset` 可逆；锚点未推进前 master 侧零影响；子代理产出若有中途异常状态以 git status 逐文件核对后取舍（接手掉线代理先查 RED PROBE/临时探针）。

## 八、实施记录

**收官时刻**：2026-10-06 05:5x；提交链十段 `56a5654`(C1 计划)→`0fc3f73`(C2 构建链删 asm)→`05245c5`(C3 波A 14 文件)→`d638241`(C4 波B 4 文件)→`9fe1759`(C5 波C 7 文件)→`ffe6066`(C6 测试 22 件)→`fa5c139`(C7 文档)→`5a60f04`(修复笔)→`224354c`(基线登记笔)→本笔(C8 收官)。范围收官修正：master 收官复核前进 1 笔（`0148a5f` 性能二十三轮报告，纯 docs 新增 1 件）补直取零漂移后锚点直接钉新 tip——坑 18「回写前再查分支前进」实证命中；实际吸收=**30 提交**，锚点 `4432849`→`0148a5f`。

**前提逐条回执**：V-a ✅（四册全 blob 口径零冲突，专属行逐行校验全存活）；V-b ✅（DemoJpaSqlPipelineTest 语料可达，testShards 全绿）；V-c ✅（shellBytes 对接编译+壳工厂绿+zip 无 asm jar）；V-d ✅（consent 族 26/0 绿）；V-e ✅（TinyClassFileWriter 直译零互操作坑）；V-f ✅（6146 = +45 例，在预期 +40~60 内）；V-g ✅（守卫正向 4/0+26/0，ASM 守卫反向双读数：探针在场 4 测 1 败精确点名探针文件/还原 4-0）。

**门禁终态**：testShards **6146/8/0/0**（5 片 1359/1047/1112/1245/1383 全绿；skip 8=固定 docker 桶 6+ExecWholeScriptOptionTest 2，daemon 关/活两轮稳定=显式开关口径与 daemon 无关，-1 vs 基线 9 系非确定性单例漂移如实登记）+testExternal **40/0/1→基线登记后全绿** +agent **93/0** +verifyGate 三目标 Compatible **零漂移**（243=4+15+46、253=6+18+44、262=7+73+44）+buildPlugin **2026.10.6.zip 6,848,397 字节**（lib 无 asm jar，-105,725=asm 移除与 master 818af1b 同款）+testDatabase **全绿零 skip 5m11s**。收口代理（只读）74 分钟两轮：run1 三起环境性事故（shard5 worker 病态索引挂起、mmap 536MB errno=1455、SenderLoopBackoff 负载敏感假红）复跑排除非代码问题。

**冲突与坑实录**（新增 3 条进 K2J 记忆）：
1. **tail 管道假绿二度复现**（同 5b89150）：C3 前的 compileJava「一次通过」、C6 前的 compileTestKotlin、守卫正向定向三轮「exit 0」全是管道末命令退出码——PIPESTATUS 真值复验暴露 1 生产编译错（attachFallbackNotifier 字段初始化器引用空白 final project）+3 处直取件互操作错；教训=gradle 真实退出码一律 `; echo EXIT=${PIPESTATUS[0]}`。
2. **attachFallbackNotifier 构造期装配**（翻译形态差异新坑）：Kotlin 属性初始化器可引用主构造参数（语言保证），Java 字段初始化器先于构造器体执行读空白 final 直接编译红——修法=字段纯声明+lambda 移入构造器赋值后装配。
3. **直取≠免适配**：直取 13 件只查了源码扫描根引用，漏查 Kotlin 调 Java 生产类的互操作形态（JpaSqlLiteralSafetyTest 具名实参 12 处/record 解构、AgentPremainConsentTest 函数类型变量赋 Supplier 不做 SAM 转换 1 处——字面 lambda 赋值走 setter SAM 转换合法，变量赋值不做）——直取清单固定自查项须含互操作形态 grep。
4. **fatal 守卫三层连环**：守卫单次只报首个失配（文件判定 8→9→文件判定 7→8→总量 666→674），grep 行计数对账预判两次失灵（守卫判定口径≠行计数），以守卫实测为准走完；判别力经连环红充分实证。
5. **CRLF 伪冲突**（坑 18 前半复现）：四册三方合并预演三度全红（ours/theirs 任一侧取工作树副本即整文件伪冲突），全 blob 口径 rc=0——合并稿全程 git blob 取值。
6. **并行表纪律**：一次未 register 就跑 gradle（与 t-releaseperf6 重活并存窗口）+沙盒临时行补加/还原闭环；Docker Desktop 主控补启（daemon 关闭窗口 testDatabase 跳过→补跑）。

**遗留与观察项**：①testShards skip -1 单例漂移（两轮稳定 8，基线 9 构成不可考，无因果面）；②k2j 轨既有登记项（McpSourceLocator 相对路径/line 语义两小 bug、McpExpectedBridge 243 降级语义）维持原状；③分支 push 留底随收官执行。
