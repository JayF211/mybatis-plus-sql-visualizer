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

1. **合并决策**：要么接受 `testDatabase` 变红（诚实口径）并随即立项修复这四个类；要么先修好四个类再合并。**不建议**把断言改回 skip。
2. **修复方向**（供立项参考）：①`testNoDataSourceReportedAsUnavailable` 的「无数据源」分支依赖窗口，应在无窗口环境改走 `onError` 分支或加窗口前置；②runner 发现返回 0——查 `DbImplUtil.getAppropriateDataSourcesForExec` 对沙盒新建数据源的过滤条件；③`createTempPsiFile` 的 VFS refresh 取不到新建目录——查 `refreshAndFindFileByPath` 与 fixture 临时目录的 VFS 根关系；④`HeavyNative...` 的 `assumeTrue` 应改 `println + return`（该文件既有惯例：平台 runner 把 `AssumptionViolatedException` 记为 failure）。
3. 真机路径（原在册描述）仍成立：`runIde` + UI 配置数据源触发驱动下载，可作为修复后的端到端复验。
