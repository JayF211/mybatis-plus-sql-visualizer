# T-SyncK2JMaster176 开发计划——master c4c5f02..ed84f5b 增量同步

> 立项日期：2026-09-17　分支：feature/kotlin-to-java（worktree `mybatis-plus-sql-visualizer-kotlin-to-java`）
> 同步源：**本地 master ed84f5b**（增量范围 `git diff c4c5f02..ed84f5b`，176 提交，358 文件，生产 +6870/-2632、测试 +6268/-843；master 侧活跃会话 t-mergedev17 在册，若合流推进 master 则本轮锚点仍钉 ed84f5b，增量留下一轮）
> 上轮收官：T-SyncK2JMaster171（159997c，全量 3975/0/0/6 绿 319 套件 + buildPlugin + verifyPlugin 三目标 Compatible，锚点 c4c5f02）
> 用户指示：同步功能、**不合并分支**（方案A手工移植不变）。

## 1. 背景与目标

锚点之后 master 经历了仅次于上轮的大增量：**M56 agent 字节码增强全链合入**（feature/t-m56-01-agent-base 整分支经 68469dd 落主，agent 子项目 36 文件 + IDE 侧 log/agent 五件 + 确认流 + 设置归位，整包对 k2j 全新）、T-UiNative 四批次原生化、增强捕获修复链五连（LogTime/SourceJump/ListenSync/DialectLoop/CudSource）、控制台并发分桶两轮（T-LogParseConc/T-HibConc）、feedback 三任务多轮（Dlg 系/Err 三轮/Batch2）、日志列表增强链（LogSortModes/原始时间/行号/搜索时间戳/ListPreviewCopy/DisplayModeToSettings/VStack·InAll/ListParamCollect）、execute/ui 修复链（ExecSlowOps/ExecDocSync/PreviewExecDs/ExplainDbl/ExplainFirstNative/MergeFollowup 四轮/JumpCapture/JumpPath/IntrUpg/ModPick/MpjColAlias）、T-TestPerf2 全量提速与 T-ReleasePerf 发版提速、以及 v2026.9.14 与 v2026.9.18-beta.1/.2/.3 四个发行。

本轮目标：把上述增量全部移植进 100% Java 的 k2j 生产树，全量测试绿 + buildPlugin/verifyPlugin 三目标通过，锚点推进 ed84f5b。绝无 git merge；共享文件（plugin.xml/bundle/build.gradle.kts/settings.gradle.kts/CHANGELOG/MEMORY/变更记录）走手工 union，k2j 自有行零丢失。

## 2. master 增量功能线（176 提交归组）

| 功能线 | 主要提交 | 内容概要 |
|---|---|---|
| L1 M56 agent 全链合入 | 68469dd 合入 + bc6351c/ae0c549/d13241d 等 09-12 系列 + cf664bc/816de03/388a56b/711370a/25f0a77/33f96fb/92bcf6f | agent 子项目整包 36 文件（Java 直取）+ settings.gradle.kts 组合构建 include + build.gradle.kts embedAgentJar 内嵌链 + 构建工具源纳管；IDE 侧 log/agent 五件（AgentEvent/AgentEventAdapter/AgentManager/AgentProtocol/JdbcUrlDialects）Kotlin→Java；EnhancedCaptureConfirmDialog 确认流（首挂征求注入确认、标题补插件名前缀、Cancel 走插件 bundle）；增强捕获默认开启/脱敏默认关闭；设置三行归位 sqlLog 区组（读写判定随迁） |
| L2 增强捕获修复链 | 17eb380/d317d78/a474447/e0acf1d/fb408b9/4f69780/d89c334 | T-AgentLogTime（LogTimeCorrelator/LogTimestampParser/LogThreadRouter 三新件：采集时刻前移+指纹相关器+线程分桶路由）；T-AgentDialectLoop（JdbcUrlDialects 九方言+目标库角标+SqlLogFileReader logTime 读回修复）；T-AgentSourceJump（插件帧误选根治+行数语义+耗时排序结构化优先+角标移除）；T-AgentListenSync（attach 后按状态下发）；agent 事件 JSON 首成员前导逗号根治（Jsons 守卫测试）；T-AgentCudSource（增删改语句源码位置捕获修复） |
| L3 控制台并发分桶 | 6626365/e7787ca | T-LogParseConc 控制台解析并发配对（线程分桶下沉/空参判别/多行时间取首行/MyBatis 续行）；T-HibConc Hibernate 分桶（MRU 无 token 归属+状态按桶隔离+影子槽按桶） |
| L4 T-JumpCapture/JumpPath/IntrUpg/ModPick | 2c7426c/18d29bb/fa79944/f40cde7 | 跳转仅依赖拦截器捕获位置（WrapperSearchService/Result 退役+SourceCaptureGuide 三按钮引导新件）；多模块路径解析与自位污染；旧拦截器解析防线+自动升级（SqlSourceMarker.isInterceptorSelfPath/needsInterceptorUpgrade）；InjectionModulePickerDialog 多模块勾选 |
| L5 T-UiNative 四批次 | 0e857ec/d2d5978/e83d0ac/45a9b16/11189c3/bb0b705/7a19c9f/5ca0b76/cce2aa3/6159828 | 剪贴板收口 UiTextClipboard 新件+OnePixelSplitter+SearchTextField+SpeedSearch；19 处模态轻提示换通知；右键菜单 ActionGroup 化+SqlListSelectionOutline 退役；JOIN 图/历史导航迁 ActionToolbar；死代码删除（SqlHistoryPanel/SqlHistoryToolWindowFactory/SqlHistorySearchBar/Timeout.html）+33 项颜色 namedColor 化集中 MpvsUiColors 新件；加载态原生件+Kotlin UI DSL v2 试点；历史二级列表 SpeedSearch 按需安装修复+渲染器高亮；守卫规则反转（钦定 updateActionsImmediately 禁反射 hack） |
| L6 日志列表增强链 | 80762b4/229bd7a/ba9a8e9/3e2d2be/88f20ed/89a04de/9e01e26/d89aad4/463941b/9ece8bc→d6bdbfd/fec7d33/afec534/01531b0/2ba2b37 | T-LogSortModes 排序方式选择器（数据类 toString 泄露修复/元信息顺序统一）；日志原始时间记录与显示切换；搜索行号/时间+右键复制时间戳；SQL 历史列表行号；T-ListPreviewCopy 默认格式化+多选批量复制；T-DisplayModeToSettings 显示模式迁设置页（ComboBoxButton 原生化四轮）；T-VStack·InAll IN 列表全量竖排；T-ListParamCollect 集合参数方括号修复（ListParamRenderer 新件） |
| L7 feedback 多轮 | 96bcb62/943494c/23a8581/c406063/d192216/9a5091f/fa1cde9/55b2e0e/09a2b18/0978eac | 报告对话框三轮（宽度/灰提示/可拖拽/堆栈勾选/手动形态自主输入/默认高收敛）；T-FeedbackErr 三轮（失败可见性/状态机落对话框/gzip 解压根治/nonJsonHint/附件降级+UTF-8 BOM/复制失败信息按钮）；T-FeedbackBatch2 批量合并一帖（BatchSubmitter 新件）；PendingReportScheduler 错峰补发新件（c406063）；T-PerfSweep2 三项（响应体有界读取/附件截断/调度器单拍链式化） |
| L8 execute/预览/ui 修复链 | c27ff31/d784356/4b12453/5cae17c/29edfff/04eb8f9/dbf0632/c145c99 | T-ExecSlowOps（DialectPreMapping 新件两段式映射）；T-ExecDocSync（document 写统一派 EDT）；T-PreviewExecDs（跨项目数据源错乱三级降级）；T-ExplainDbl 幂等包装；T-ExplainFirstNative（空名占位 Tab+EXPLAIN 裸形态）；MergeFollowup 四轮（EXPLAIN 按钮回补/gutter 右键正解/模式切换 updateUI/显示模式按钮同构） |
| L9 converter/杂项 | 524c050/556f0a2/01fb8ba/d186d5c | T-MpjColAlias 联表 lambda 列归属 join 别名（MpjLambdaConverter）；plugin.xml 根 url 挂官网；T-Intranet 换源诊断日志；T-SettingsOrder 子页 groupWeight 钉序 |
| L10 发行与提速 | e575eae/643fb56/f9307cc/74cfabf/d0c0a16/b4ef08b/26eeb09 | v2026.9.14 + v2026.9.18-beta.1/.2/.3 四发行（版本号/CHANGELOG/变更记录/发行说明/preview/update.json/changeNotes）；T-ReleasePerf verifyGate 离线化+releaseGate 一命令；T-TestPerf2 testShards 并行提速+buildCache 落 settings 层 |
| L11 文档/归档/杂物 | 6306cf8/3213a21/94c73f7/b6c0e1b/ed84f5b/df01a0c/f503ab5/42eb7f8/2567812 等 | 第五/六轮竞品调研报告+数十份计划立项入库；memory 两次精简归档搬移（bug-fixes 37 件+pitfalls 7 件）；全景审计报告；架构三册同步（WrapperSearchService 移除改 SourceCaptureGuide）；demo-09 GIF+group-qr.png；record 两脚本；.gitignore/sub-projects README |

## 3. 文件级移植矩阵

### 3.1 生产代码（18 新建翻译 + 57 增量翻译 + 2 Java 直改 + 6 删除）

**新建 18 文件（Kotlin→Java 全量转换）：**

| 包 | 文件 | 移植要点 |
|---|---|---|
| log | LogTimestampParser / LogTimeCorrelator / LogThreadRouter / ListParamRenderer / SourceCaptureGuide / SqlConsoleBridgeFilterProvider | 时间解析/指纹相关器/线程分桶/IN 竖排渲染/三按钮引导/控制台过滤器 Provider（EP 注册） |
| log/agent | AgentEvent / AgentEventAdapter / AgentManager / AgentProtocol / JdbcUrlDialects | M56 IDE 侧五件；AgentManager 挂载链最大（497 行）；AgentProtocol 协议字段与子项目 Jsons 序列化两端对齐（往返守卫同源） |
| config | EnhancedCaptureConfirmDialog | DialogWrapper 确认流；标题插件名前缀+Cancel setCancelButtonText 显式覆盖 |
| execute | DialectPreMapping | 两段式映射单入口（exec 预热离 EDT） |
| feedback | BatchSubmitter / PendingReportScheduler | 批量合并一帖+错峰补发调度器 |
| util | MpvsUiColors / UiTextClipboard | namedColor 集中 33 项/剪贴板统一收口 |

**增量翻译 57 文件：** 全清单以 `git diff --name-status c4c5f02..ed84f5b -- src/main/kotlin` 为准（M 57 件），按 Wave 分组实施。两个特殊映射：master `LogEntryPreviewRecognizer.kt`→k2j `LogEntryPreviewRecognizerKt.java`、master `LogSqlModels.kt`→k2j `LogSqlModelsKt.java`+`LogSqlEntry.java`/`LogSqlParam.java`（上轮拆分形态，增量按成员归属落对应文件，勿新建同名 Kt 文件）。

**master 侧 .java 直改 2 件：** `log/SqlLogToolWindowFactory.java`（M，k2j 同路径直 diff）；`history/SqlHistoryToolWindowFactory.java`（D，k2j 同删）。

**删除 6 件（k2j 对应 .java 同删）：** history 包三件（SqlHistoryPanel/SqlHistorySearchBar/SqlHistoryToolWindowFactory）+ log/SqlListSelectionOutline + log/WrapperSearchService + log/WrapperSearchResult（master 范围内已无此文件）+ resources/html/Timeout.html。

### 3.2 测试（112 文件：36 agent 直取 + 31 新建适配 + 44 增量 + 5 删除）

k2j 侧形态普查结论（逐文件核实）：36 件 agent 子项目测试为 Java 直取（随 C2 整包）；31 件 master 新建测试 k2j 无对应（落 .kt 或 .java 按依赖形态适配）；26 件增量在 k2j 有同名 .kt（直改 .kt）；18 件增量在 k2j 是 src/test/kotlin 下 .java（增量翻译进 .java，代表性：SqlPreviewPanelTest/LogToSqlPanelTest/SqlLogPanelLayoutTest 系）；5 件删除（SqlHistoryPanelTest.java/SqlHistorySearchBarTest.kt/SqlListSelectionOutlineTest.kt/SqlLogPanelJumpToSourceTest.java/WrapperSearchServiceTest.kt）。

处置规则（承 171 轮）：
- 新建测试引用 Java 生产类：机械适配（具名实参→位置实参、record 访问器方法调用化、`project`→`getProject()`、INSTANCE 单例）落 .kt；纯逻辑直取；
- 三族守卫增量（PluginXmlStructureGuardTest/BannedApiSourceScanTest）按 k2j 既有适配形态落（扫描根 src/main/java）；
- JUnit3 test* 前缀纪律 + lambda 合成方法坑逐文件把关；
- 并发类新测试（LogParseConcurrencyTest/HibernateLogParseConcurrencyTest 等）留意剪贴板/定时器 flake 定例。

### 3.3 资源与构建（union，k2j 自有块零丢失）

- **settings.gradle.kts：直取 master 版**（k2j 现版无自有块，master 新增 include agent + buildCache 两块即全部差异）；
- **build.gradle.kts：union**——master 新增 embedAgentJar 任务链/agent 子项目构建接线/changeNotes 9.14~9.18-beta.3 各段/T-ReleasePerf verifyGate 离线化/releaseGate/testShards（k2j 已有上轮 T-TestPerf 形态需对齐 T-TestPerf2 终态）/k2j 自有 hotswap 块零丢失，diff 复核；临时沙盒行 `sandboxContainer.set(layout.buildDirectory.dir('test-sandbox-k2jsync176'))` 实施期加入、收官提交还原；
- gradle.properties：版本 2026.9.14-beta.2→2026.9.18-beta.3，其余逐行 diff；
- plugin.xml：M +17/-6 逐条对齐（SqlConsoleBridgeFilterProvider EP/agent 相关注册/根元素 url 属性/groupWeight 钉序/feedback 增量），落地后活引用审计；
- bundle en/zh：各 +71/72 -12/13 union，BundleParityGuardTest 守卫兜底；⚠️ c8fe771 教训：勿用带 BOM 写入工具碰 .properties；
- update/preview.update.json+update.json：直取（版本 2026.9.18-beta.3）；
- marketplace-custom-page.html：直取（5efd5cc 三入口表述）；Timeout.html 随删；
- 根杂物：README.md（demo-09 GIF+内网节）/.gitignore/record 两脚本/sub-projects/README.md/docs/images 二进制——直取。

### 3.4 文档（约 170 件直取 + 三册 union）

docs/开发计划 71 件、memory-archive 44 件（bug-fixes 37+pitfalls 7）、调研报告 12 件、发行说明 7 件（6 新 1 改）、架构设计 4 件、经验总结 2 件、验收文档 1 件、instrumentation 1 件——直取为主（master 已入库形态）；**三册 union**（CHANGELOG.md +121/-11、docs/MEMORY.md +48/-5、docs/变更记录.md +52——两侧行集合校验防吞 k2j 行，不只看行数）；memory-archive 归档搬移后回补 k2j 专属行。主工作区未跟踪文件（20260916-1730 全景盘点/task-queue 说明文档）不在 git 历史内，不属同步范围。

## 4. 波次与提交链

| # | 提交 | 内容 | 门禁 |
|---|---|---|---|
| C1 | docs: 立项 | 本计划文档 | — |
| C2 | feat: agent 子项目落位 | sub-projects 整包直取 + settings.gradle.kts 直取 + build.gradle.kts agent 链 union（含临时沙盒行）+ gradle.properties 版本 | `:sql-visualizer-agent:test` 全绿（24 用例）+ embedAgentJar 出 jar |
| C3 | feat: Wave1 生产·log 低层 | 六新件（TimestampParser/TimeCorrelator/ThreadRouter/ListParamRenderer/SourceCaptureGuide/ConsoleBridgeFilterProvider）+DialectPreMapping+util 两新件+Kt 文件类两映射增量+解析器四件+log 低层增量+update 两件 | compileJava 零错 |
| C4 | feat: Wave2 生产·agent 链与 config/feedback | log/agent 五件翻译+EnhancedCaptureConfirmDialog+config 四件接线+feedback 四增量二新件 | compileJava 零错 |
| C5 | feat: Wave3 生产·面板与 UI 高层+退役 | SqlLogPanel/HistorySqlLogPanel/LogToSql/SqlToJava/SqlClickPreview/SqlPreview 族/Diagram 族/DataSourceSelector/execute 三件/ui 增量；删除六件+Timeout.html | compileJava 零错 |
| C6 | chore: 资源/构建 | plugin.xml 对齐+活引用审计、bundle union、update JSON、marketplace 页、README/.gitignore/record | buildPlugin 出包 |
| C7 | test: 测试移植 | 31 新建+44 增量+5 删除 | 全量 testShards 绿 |
| C8 | docs: 文档同步 | ~170 件直取+三册 union | 两侧行集合校验 |
| C9 | chore: 收官 | .port-sync 锚点推进 ed84f5b+note 重写+还原沙盒临时行 | verifyPlugin 三目标 Compatible |

## 5. 移植规则（既定清单全量生效）

1. 生产代码 100% Java，绝不 merge .kt 进生产树；
2. Kotlin→Java 机械转换缺陷清单（static import/drop 越界守卫/LinkedHashSet/Map.of 拒 null/assertEquals 装箱/throws Exception/test 前缀/record 访问器 x() 等 20+ 条）逐条生效；
3. 互操作铁律六条（record 禁 extends/禁命名实参/静态成员导入/非 get 前缀不合成属性/工具层反斜杠坑/merge-file --union 行级并集）；
4. 基线一致性判定 `git show <sha>:<f> | diff --strip-trailing-cr - <f>`；一致文件 `git checkout <sha> -- <paths>` 整取后逐一复核；
5. 新增反射入口登记 PlatformSignatureCrossVersionTest；Light service 显式 final；
6. gradle 前必跑 parallel_tasks.py check 并登记（本轮名 `t-synck2j176`、沙盒 `build/test-sandbox-k2jsync176`，在册活跃任务 t-mergedev17 滞留未注销——不 kill 不触碰其沙盒，只在自己的 worktree+沙盒内作业）；收官后 `done` 注销并还原沙盒临时行；push 留用户 GUI；
7. 移植引发测试假失败先清 build/classes+build/kotlin（陈旧产物劫持三形态），再排逻辑。

## 6. 风险清单

| 风险 | 缓解 |
|---|---|
| t-mergedev17 并行会话推进 master | 锚点钉 ed84f5b 快照，后续增量留下一轮；不碰主工作区与 dev worktree |
| agent 子项目组合构建首跑冷启动慢/网络 | 阿里镜像置前已就绪；代理失效走 nonProxyHosts 直连绕过存档 |
| Jsons/AgentProtocol 双端序列化两端各自绿合流即断 | 借 master 既有 JsonsEventFormatTest/AgentProtocolSyncTest 守卫整取，k2j 侧跑通为准 |
| plugin.xml 新注册类不可达（静态链不报） | C6 逐条活引用审计（类文件存在+构造器/EP 语义） |
| master 发行重构吞 k2j 三册自有行 | union 后两侧行集合校验 |
| LogSqlModels.kt 增量对应拆分文件错位 | 先 diff master 该文件增量再按成员归属映射 LogSqlModelsKt/LogSqlEntry/LogSqlParam |
| BannedApiSourceScanTest 守卫规则反转（5ca0b76）语义漂移 | 按 master 终态直取后核 k2j 扫描根适配仍在 |
| bundle .properties 被写 BOM | 只用 git checkout/git apply，不用 PowerShell 写回 |
| verifyPlugin 报告目录脏状态硬崩 | 重跑前清 build/reports/pluginVerifier |
| 262 EDT/SlowOps 断言类测试在翻译后触雷 | 照 master 测试形态+既有两段式惯例（BGT 读探测+EDT 收口）|

## 7. 验收标准

1. 全量测试 0 失败（基线 3975 → 预期 ≈4299±，套件数与 master 同源核对；agent 子项目独立口径 24+ 用例绿）；
2. buildPlugin 成功出包（2026.9.18-beta.3，含内嵌 mps-sql-agent.jar）；verifyPlugin 243/253/262 三目标 Compatible；
3. k2j 自有行零丢失：CHANGELOG/MEMORY/变更记录/build.gradle.kts/settings.gradle.kts 两侧集合校验通过；
4. .port-sync 锚点 = ed84f5b（note 字段同步重写为本轮记录）；
5. plugin.xml 活引用全部可达；bundle parity 守卫绿；删除件（history 包/Outline/WrapperSearchService/Timeout.html）主树+测试+plugin.xml 三处零残留引用。

## 8. 实施记录

提交链（滚动回填）：
- a0a7fef C1 立项计划
- 52696ce C2 agent 子项目落位（sub-projects 36 文件直取 + settings/gradle.properties/build.gradle.kts union + agent 42 用例绿 + fat jar 出包）
- d85473c C3~C5 生产移植（25 新建翻译 + 57 增量 + 9 退役删除，compileJava 绿）
- 5e36b8f C6+C8 资源构建与文档同步（plugin.xml/bundle union 648 键/update 双 JSON/文档 146 件直取/三册 union）
- 2ad5c15 C7 测试移植（31 新建 210 用例 + 44 增量 + 5 删除 + 测试缝收口）
- （本次收尾提交）C9 收官（.port-sync 锚点推进 ed84f5b + 沙盒临时行还原 + 台账行）

门禁终值：全量 testShards **4286/0/0/6 绿**（3 分片：1477/1532/1277，基线 3975→4286；master 同源口径 testShards 4299）+ buildPlugin 出包 2026.9.18-beta.3（含内嵌 mps-sql-agent.jar）+ verifyGate 三目标 Compatible（243: 7dep/10exp；253: 1 for-removal/10dep/10exp；262: 2 for-removal/56dep/10exp）。

编译期修复记录（翻译批次收口）：12→2→0 三轮——SqlConsoleBridgeFilterProvider 方法引用错类/contains(':') 字符版/FocusFilter 限定名/VirtualFile+MpSqlVisualizerBundle 缺 import/comparingLong 显式类型/ThrowableComputable Unit→Void；AgentManager throws 补 AttachNotSupportedException+import；测试侧 internal→public 可见性（setCapturingForTest/AgentManager.online）、SubmissionOutcome/failureStatusText 1 参重载、AgentEvent.withUrl、MyBatisLogParser 4 参构造、SqlLogCaptureRealtimeScenarioTest 17 参适配；测试修复两根因=J1 翻译时 restorer 传 null（Kotlin 默认参数语义纠偏，24 失败）+JUnit3 lambda 合成方法坑（NO_OP_RUNNABLE 抽字段，1 失败）。

过程记录：
- 生产翻译采用「主会话增量移植 + 五个并行子代理分批」：B（LogThreadRouter/LogParseDiag/LogTimeCorrelator/ListParamRenderer/SourceCaptureGuide）、C（agent IDE 侧七件含 AgentSession 拆分 + EnhancedCaptureConfirmDialog）、D（BatchSubmitter/PendingReportScheduler，三处适配点待 ErrorReportComposer 批次回收）、A（低层 util 五件，限流失败后由主会话补齐）、E（log 六件历史往返 8 个 Java，SqlLogParamCodec.decode 改 DecodedPayload record）、F（config+feedback 增量五件）、G（execute+ui 七件）、H（log 高层九件）。首轮 4 并发触发账户限流（A 失败），后续维持 ≤3 并发。
- 主会话直改：LogSqlEntry 17 组件 record（含 withLogTimestamp）、SqlSource.AGENT、LogSqlModelsKt（logTimeKey/logTimeParamSignature/stampLogTime）、LogEntryPreviewRecognizerKt（T-InParamRec 统一通道 + T-ListParamCollect 命名参数预填 + JDBC/Hibernate 零参数文案分流）、三解析器线程分桶化（T-LogParseConc/T-HibConc/T-AgentLogTime 采集时刻前移）、SqlSourceMarker 3~5 段宽松化 + SourceMarkerData record + isInterceptorSelfPath、SqlLogConsoleBridge 族（SqlCaptureGate 闸门/EP 首行标志/嗅探回填/自位污染防线/诊断计数上报）、FeedbackApi（SubmissionOutcome/HttpFailure/gzip 根治/附件降级）、Dialect（幂等 EXPLAIN + MySQL 家族裸形态）、MpjLambdaConverter（T-MpjColAlias 列归属重写）、UpdateSource/PluginUpdateRunner（换源诊断日志）、MpSqlVisualizerBundle（messageForIde/AUTO→DynamicBundle.getLocale）、plugin.xml（consoleFilterProvider EP/groupWeight 钉序/根 url）、bundle union（master 为底 + 8 个 k2j 自有键回补，en/zh 648 键对齐）、文档直取 146 件 + 三册 union（CHANGELOG 整取 master、变更记录按日期归位 6 行、MEMORY 回补 7 行 k2j 专属行）。
