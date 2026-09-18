# testDatabase 驱动管线打通记录（T-TestDbDriver）

- 日期：2026-09-18
- 性质：基建债清偿记录 + 未合并说明（本分支 `t-testdb-driver` 未回合 dev，原因见 §四）
- 相关：`docs/开发计划/20260916-1730-待完成开发计划全景盘点.md` §2.3「testDatabase 驱动管线债」

---

## 一、债的形态

在册描述：`DBN 子进程驱动加载（RemoteDriver.classpath.txt 控制，非文件复制可解）——打通路径 = runIde + UI 配置数据源触发驱动下载`。表现为 `./gradlew testDatabase` 的四个 DB 集成类恒以 `ClassNotFoundException: com.mysql.cj.jdbc.Driver` 收场，各自软跳成绿。

## 二、机制（逐环字节码/探针实证）

驱动进子进程的链路是：

```
LocalDataSource.getClasspathElements()
  → myDriver.getClasspathElements()
  → 每个 ArtifactRef.getElements()
       myVersion == null → 回退 legacy 元素（恒空）
       否则 → DatabaseArtifactManager.getClasspathElements(artifactId, version)
              → DatabaseArtifactList.resolveVersion 命中清单条目
              → DatabaseArtifactLoaderImpl.getClasspathElements(av)
                从 <config>/jdbc-drivers/<artifact name>/<version> 解析 jar
  → JdbcConsoleRunContext.addJdbcDrivers 把这些 jar 写进子进程参数
       （isolate 关：直接进子进程 classpath；isolate 开：写 RemoteDriver<classpath>.classpath.txt
        临时文件并以 -Djdbc.classpath=<绝对路径> 传参）
```

**结论：驱动在子进程里可加载只有一个前提——driver 的 artifact ref 带具体版本号。**

## 三、两处断点与修复

### 断点① 平台不读 `options/databaseDrivers.xml`

真实 IDE 的「驱动 → artifact 版本」映射存在该持久化状态文件里（`@State(name = "LocalDatabaseDriverManager", storages = @Storage("databaseDrivers.xml"))`，javap 实证）。测试沙盒里路径与文件内容都对，但 `getDriver("mysql.8").artifacts` 只剩驱动定义里那条 rolling ref（`id=MySQL ConnectorJ, version=null`，来自 `database-plugin.jar` 的 `databaseDrivers/mysql-drivers.xml`）→ version=null → legacy 空 → 子进程 CNFE。

探针同时证明：把同一份 XML 手工喂给 `DatabaseDriverManagerImpl.loadState` 立刻生效（artifacts 变 `MySQL ConnectorJ@9.7.0`，classpathElements 出 2 个 jar）。

**修复**：走公有 API（`DatabaseDriverImpl.setArtifacts` + `createArtifactRef` + `DatabaseDriverManager.updateDriver`，243 javap 实证公有）在测试侧补齐版本——不反射平台内部、不碰生产代码。落在 `DbIntegrationSeedSupport.prepareDriverPipeline()`。

### 断点② `test-database-artifacts.xml` 落点错

`DatabaseArtifactLoaderImpl.getTestArtifactLists()` 经 `DatabaseDriverManagerImpl.existentTestPath(name)` 只认 `<homePath>/dbe/database/testResources/<name>` 与 `<configPath>/options/<name>`（bytecode 常量池实证），而 buildSrc 种子落在 `<configPath>/` 根 → 探针 `getTestArtifactLists() == []`，该文件从未被 DBN 读到。

另：`test-database-drivers.xml` 在单测模式被 DBN **显式忽略**（`isUnitTestMode() && name.endsWith("test-database-drivers.xml")` → 直接 return null，bytecode 实证），且原落盘用的是相对路径 `build/test-sandbox/...`（按 Gradle daemon 进程 CWD 解析，跨构建不一致）。落盘职责已归测试侧 `DbIntegrationSeedSupport`（`PathManager.getConfigPath()` 绝对锚定）。

**修复**：buildSrc 落点改 `configRoot/options/`；driver 定义不再由 buildSrc 落盘。

### 守卫

新增 `src/test/kotlin/.../execute/DbDriverPipelineTest.kt`，把链路每一环钉成断言（清单有 9.7.0 → 解析出 jar 文件 → driver 的 classpathElements 非空且含 mysql jar → 数据源的 classpathElements 非空）。**实测 2/2 绿**。管线再回退即先红，不再靠四个集成类的静默跳过掩盖。

## 四、未合并说明（本分支停在 `t-testdb-driver`，未回合 dev）

> **状态更新（2026-09-18，T-TailSweep G5 补记）**：本节已成历史留档——本分支内容已入 master
> （cherry-pick 为 `6017fe4`，见 T-TS-11），表内 7 项失败已由 T-Fix-TestDb（`3457f77`，稳定工程目录
> + 控制台/编辑器夹具 + 发现链收敛等待 + `forkEvery = 1`）清偿。**合并后的正确口径与剩余未验证边界
> 见 §六**，勿再按本节「未合并 / testDatabase 变红」的表述引用。

驱动管线打通后，原先靠「introspect 失败即软跳」静默变绿的四个 DB 集成类**首次真正执行**，暴露 7 项断言失败：

| 类 | 失败点 | 实测形态 |
|---|---|---|
| DataSourceSelectorIntegrationTest | `testNoDataSourceReportedAsUnavailable` | 无数据源分支打开 Database 工具窗时 `frame` 为 null（沙盒无窗口） |
| 同上 | `testSingleSourceDiscoverableByRunner` | introspect 成功后 `getAttachDataSourceRunners` 返回 0（数据源对平台 runner 发现不可见） |
| 同上 | `testMultiSourceDiscoveryReturnsAllRunners` | 同上 + `buildExecPsiFile` 抛 `FileSystemException: 另一个程序正在使用此文件` |
| 同上 | `testPrepareConsoleSilentlySelectsSingleSource` | `createTempPsiFile` 的 `refreshVfs: VFS not visible after refresh` → prepareConsole 120s 不就绪 |
| DbConsoleIntegrationTest | `testPrepareExecuteAndParameterChangeEndToEnd` | 同上（prepareConsole 不就绪） |
| ExecDocGateIntegrationTest | `testExecDocSavedStateAcrossPrepareReuseAndManualEdit` | 同上 |
| HeavyNativeDatabaseDiagramJoinChainTest | `testDatabaseDiagramEmbedChainWithVirtualFk` | B 路径链路不可用（`assumeTrue`，平台 runner 记为 failure） |

**判定**：这批失败是**测试侧从未验证过的平台行为假设**（沙盒缺真实 IDE 项目 / 窗口 / VFS 夹具），不是产品缺陷，也不是驱动管线的问题——驱动那一段已由 `DbDriverPipelineTest` 硬断言。但把它们改写成 skip 等于**再次掩盖**（本轮正是掩盖导致债被长期误判为「环境门控、语义正确 skip」），故本轮**不做掩盖**：分支停在 `t-testdb-driver` 不回合，交主调度/用户决定。

已做的非掩盖性改进（保留在本分支）：四个集成类的 `tearDown` 补 `DataSourceSelector.clearCachedTempPsiFileForTest()`——`DataSourceSelector` 是 object 单例，`cachedTempPsiFile` 跨用例存活（`clearPrepared` 刻意不清，见 T-ExecCopyFix F2c），而 `BasePlatformTestCase` 每用例换 fixture 临时目录，残留缓存指向已拆目录。

## 五、建议后续

> **状态更新（2026-09-18，T-TailSweep G5 补记）**：第 1 条（合并决策）已按「先修好再合并」落地
> （`3457f77` → master）；第 2 条的四个修复方向除 ④ 外均已实施（④ 的 `assumeTrue` → `println + return`
> 亦已在本分支完成）；第 3 条真机路径仍为未验证项（见 §六）。

1. **合并决策**：要么接受 `testDatabase` 变红（诚实口径）并随即立项修复这四个类；要么先修好四个类再合并。**不建议**把断言改回 skip。
2. **修复方向**（供立项参考）：①`testNoDataSourceReportedAsUnavailable` 的「无数据源」分支依赖窗口，应在无窗口环境改走 `onError` 分支或加窗口前置；②runner 发现返回 0——查 `DbImplUtil.getAppropriateDataSourcesForExec` 对沙盒新建数据源的过滤条件；③`createTempPsiFile` 的 VFS refresh 取不到新建目录——查 `refreshAndFindFileByPath` 与 fixture 临时目录的 VFS 根关系；④`HeavyNative...` 的 `assumeTrue` 应改 `println + return`（该文件既有惯例：平台 runner 把 `AssumptionViolatedException` 记为 failure）。
3. 真机路径（原在册描述）仍成立：`runIde` + UI 配置数据源触发驱动下载，可作为修复后的端到端复验。

---

## 六、T-TailSweep G5 补记（口径更正 + 两项尾款，2026-09-18）

### 6.1 口径更正：`testDatabase` 的绿不等于「全部用例已验证」

T-Fix-TestDb 合并后，`./gradlew testDatabase` 的实测口径是 **9 例 JUnit 绿（0 失败 0 skip）**。
但必须补一句：**其中 HeavyNative 的 B 路径未验证**。`HeavyNativeDatabaseDiagramJoinChainTest`
在 `component == null` 处显式软返回（`println + return`，XML `system-out` 实证
`[DB-IT] SKIP: B 路径链路不可用…`），五组断言一组都没执行却计为绿。

**正确口径：9 例 JUnit 绿，其中 HeavyNative B 路径未验证**（不是「9 例全绿 = B 路径已验证」）。
T-TS-08 后本类所在的测试类集合新增 1 例（控制台编辑器不可得的 onError 直测，见 6.4），
故最新口径为 **10 例 JUnit 绿**，未验证项不变。该口径已同步写进测试类 KDoc
（`HeavyNativeDatabaseDiagramJoinChainTest` 头部「⚠ 本类的「绿」不等于「B 路径已验证」」一节）。

### 6.2 B 路径为什么在本环境没跑（补日志后的实证）

`ui/diagram/nativefw/db/NativeDatabaseDiagramSupport.kt:72` 的降级点此前是**静默** `return null`，
是「绿≠已验证」不可诊断的根因。本轮给它与 `locateDbTables` 补日志（**只加日志，不改行为**）后，
一次跑出结论（idea.log）：

```
B path locateDbTables: wanted=[car_device_info, station_info] found=[] managers=2
  dataSources=1(dsClasses=[LocalDataSource]) modelNull=0 traverserMiss=0 roots=0 iterated=0
  dasTables=0 nameHits=0 registryMiss=0
Native Database diagram (B path) not taken: located=[] wanted=[car_device_info, station_info]
  missing=[car_device_info, station_info] —— 缺表 = 该表未在已内省的数据源模型里定位到…
```

读数含义（四段全部排除）：数据源管理器有 2 个、扫到 1 个数据源（`LocalDataSource`）；
`ds.model` **非 null**（`modelNull=0`）、traverser 可迭代（`traverserMiss=0`）、
但**模型根数为 0**（`roots=0`）→ 遍历不出任何元素（`iterated=0`、`dasTables=0`）。

结论：**本环境里挂在 `LocalDataSource` 上的 DAS 模型是空的**——测试侧走
`DatabaseConnectionManager.build(...).createBlockingNonCancellable()` 完成的是「建连 + 内省」，
而 `LocalDataSource.setModel(...)`（javap 实证：`myModel` 字段 + `setModel`/`clearModel`）
由 DBN 的**模型同步**那一步写入，测试夹具没有驱动该步。于是 `locateDbTables` 拿不到任何 `DasTable`，
B 路径按契约降级 A 路径——**降级是正确的，只是原因此前不可见**。

**未验证边界（如实登记）**：
- 本环境**无法**让 B 路径真跑（缺的是 DBN 模型同步夹具，不是 MySQL/驱动）；
  让它真跑有两条路，都超出本轮范围：①测试侧补驱动 `setModel` 的夹具（新增对 DBN 内部同步链的依赖）；
  ②产品侧把 `locateDbTables` 的取值面从 `RawDataSource.model` 换到 delegate/DbPsiFacade（改行为，需真机验证）。
- 真机（runIde 沙盒 + 真实工程）里 `LocalDataSource.getModel()` 是否带内省结果**未验证**——
  若真机同样为空，则 B 路径在真机也从不接管（功能级缺口，非测试问题），需按上面②立项。
  本轮只把「怎么判」变成可诊断（日志一行给足四段读数），不改变降级行为。

### 6.3 临时工程目录残留收口（`<tmp>/mpsql-db-itest-<ksuid>`）

残留成因见 `DbIntegrationProjectSupport` 类注释：工程目录钉在系统 temp 根、目录名刻意不带
`unitTest_` 前缀 → 平台永不清它，而 `forkEvery = 1` 下每轮恒定新增 5 个。本轮实现两条互补机制：

| 机制 | 触发点 | 实测结果 |
|---|---|---|
| 关闭钩子删自己目录（10×500ms 重试） | 每个测试 JVM 退出 | **子项全部删净，目录项本身删不掉**。占位者经对照实验排除子进程：钩子里先 `ProcessHandle.descendants()` 收掉本 JVM 全部子进程（实测 3 个，`stubborn=[]`）再删，**照样失败**；删完一轮后手工 `rmdir` 立刻成功 → 占位者是**本进程内的句柄**（最可能是平台 VFS 的文件监听），只在进程真正退出时释放，本钩子跑在退出之前，竞态在本进程内无解。故改为「删净子项 + **刻意留下 `.mpsql-owner.lock` 作孤儿标记**」 |
| 孤儿清扫（判活三规则） | 每个测试 JVM 启动 | ① 有标记 → 试抢文件锁：抢到=属主已死→连标记一起删，抢不到=属主在跑→跳过（**精确判据**，并行会话/并行分片各持自己的锁）；② 无标记**空目录**且闲置 >60s → 删（内容已删净、只余被占目录项的残骸）；③ 无标记非空且闲置 >2h → 删（本修复前遗留的老目录） |

实测：修复前 `%TEMP%` 积 30 个（其中 11 个已超 2h），机制落地后同一目录集从 30 降到个位数并
**不再随轮次增长**——每轮新增的 5 个在后续 JVM 启动时被规则①②收走（日志实证
`[DB-IT] orphan project dirs swept: N/M`，单轮最多一次清掉 18 个）。规则②是实测补出来的：
标记会被清扫链自己删掉（抢锁成功后连标记一起删），此时若目录项仍被占，剩下的空目录既无标记、
又不到 2h 阈值，只能干等——补上「空目录 + 60s 宽限」这条即闭合。

### 6.4 同期补的可诊断性用例（T-TS-09，同属本通道）

`DataSourceSelector.prepareInConsole` 的两处编辑器解析静默返回（`:1267` 同步路径 `?: return`、
`:1284` 异步路径无 else）已补 `onError` 通知：真机/测试里「控制台文档无人建编辑器」时不再永久
无回调（此前表现为面板停在「执行中」且 `errors=[]`，与 §三 断点② 观测到的「120s 不就绪」同源）。
新增直测用例 `DataSourceSelectorIntegrationTest#testPrepareConsoleWithoutConsoleEditorReportsError`
（不装编辑器夹具 → 断言 onError 到达），本环境实测命中**异步分支**并收到
`执行 SQL 失败：控制台编辑器不可得（async：400ms 内 getEditors(consoleDocument) 恒空…）`；
同步分支只在非 EDT 调用方到达，本环境（测试方法体跑在 EDT）**未覆盖**，如实登记。

**顺带查实并修掉一处夹具级硬伤（memory-disk conflict）**：新用例落位后 `testDatabase` 出现过
「本类新用例之后那一个用例必红」的稳定复现，堆栈指向
`Unexpected memory-disk conflict in tests ... mpsql-exec.sql`。逐帧取证后定性：

```
writeVfTextViaDocument:3370 fdm.saveDocument(fileDoc)
  → FileDocumentManagerImpl.doSaveDocument:377  VirtualFile.refresh(674)   ← save 内部会 refresh
    → RefreshSessionImpl.fireEventsInWriteAction → AsyncEventSupport.beforeVfsChange
      → MemoryDiskConflictResolver.beforeContentChange:57   ← 登记冲突（该事件**不带 from-save 标记**）
```

即：文档内容与目标文本不同时 `setText+save`，save 内部的 refresh 会以**非 save 来源**的 VFS
变更事件触发冲突登记，随后在事件队列 flush 时若文档/文件戳仍不等就硬抛。这与
`DbIntegrationProjectSupport.ensureExecFile` KDoc 里「isFileModified 为假时 doSaveDocument 只做
removeFromUnsaved、不触发 refresh」的表述只对**未修改**的文档成立——文档确实被改时该前提不成立。
三处改动落地后本类连跑转绿：① `ensureExecFile` 改为**有文档就只走文档写**（原实现无条件先
`File.writeText` 裸写盘，与自身 KDoc 相反）；② `releaseExecEditors` 在**释放编辑器之后再补一次落盘**
（释放编辑器会提交挂起变更把文档重新标脏，否则文档以「已改未存」进入平台 teardown）；
③ 新用例的 exec 内容**刻意与紧随其后的用例逐字相同**（内容相同则下一个用例整段跳过
`setText+save`，不再走那条登记链）。

