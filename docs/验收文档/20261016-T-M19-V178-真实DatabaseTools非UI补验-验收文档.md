# T-M19 / V178 真实 DatabaseTools 非 UI 补验

## 范围与运行前提

基线 `dev@4c7ea2c3`，独立分支 `t-m19-v178-nonui`。没有启动 runIde、GUI 验收、全量、verify、clean 或 --stop；不合并、不推送。真实平台运行测试为 IU-243.28141.41；253/262 仅独立发行物 API 检查，不将签名通过写成服务运行通过。

普通 test/testFast 的 classpath 主动排除 DatabaseTools，因此不具备证明真实 SQL PSI 的条件。`testDatabase` 是五个 `testDb*` 任务的聚合，而非一个 Test 任务。四类 Console/Selector/Diagram/ExecDoc 默认读取 demo 私有 `.idea/dataSources.xml` 或 `.local.xml`，部分依赖 `car_device_info/station_info`；本次没有运行这些读取路径。只运行既有 `testDbDriverPipeline`，并在同类追加真实平台 fixture。驱动种子读取的仅是驱动清单与 jar，不读取数据源密码文件。

`DbIntegrationProjectSupport` 提供真实平台工程目录、VFS、exec 文档及 headless editor 生命周期支持，不是数据库实现替身；它不能取消 SQL 方言扩展的初始化契约，也不能让私有配置读取变成已授权。本次无连接 fixture 使用平台默认临时工程，合成 URL 指向 loopback 端口 1，禁自动同步，不连接、不访问用户库、不安装 ForTest hook、不伪造 DbDataSource/SqlFile/session/console。

每次 Gradle 前执行 parallel_tasks.py check/register，独立 sandbox；经 gradle-gate max2、in-process 后台执行。命令模板（工程绝对路径以本树为根）为：

```text
gradlew.bat -p <本树> testDbDriverPipeline --tests cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest -PgifRecord=false -Pkotlin.compiler.execution.strategy=in-process --console=plain
```

## 实际失败链与修复

1. 首次真实 DatabaseTools 加载后，索引初始化抛 `IllegalArgumentException: Dbms not found: DM`，Light fixture 等待索引无法结束。`sql.dialect` 的 dbms 属性是平台 Dbms 标识，不是语言 ID。三版发行物均以 UNKNOWN 注册 Sql92/Generic；产品配置错误不能登记为环境受限。保留 `build/v178-first-threads.txt`、`build/v178-dm-first-red-idea.log`。
2. 改为 `dbms=UNKNOWN order=last` 后，第二轮抛 `NoSuchFieldException: INSTANCE`。真实 `DbmsExtension.InstanceBean.doGetInstance` 执行 `Class.getField("INSTANCE").get(null)`，不是 new 普通构造器。补 public static 单例（Kotlin `@JvmField`）与私有构造；保留 `build/v178-second-threads.txt`、`build/v178-dm-second-red-idea.log`。仅停止命令行包含本树的测试 worker，没有停止共享 Gradle daemon。
3. 两项修复后首份完整 XML 为 5 tests / 2 failures / 0 skipped。驱动解析、种子检查、真实断开态 session/console API 已通过。两条新 fixture 前提不成立：AWT 的 headless 标志不等于 IDEA 测试模式；无驱动数据源与未映射的 SQL 文件会被平台方言相似性规则筛掉。后续纠正 fixture 使用平台 headless 标志和真实 PostgreSQL driver/mapping，不放宽产品断言。

DM 仍是 Generic/SQL92 兼容语言，不是原生达梦 parser；插件 DM 渲染和 JDBC URL 厂商判定未变。order=last 用于不夺取平台 UNKNOWN 默认 Generic。

## 三版本实际 API 检查

既有 `PlatformSignatureChecker` 追加候选读取链精确 owner/参数/返回类型：DbDataSource.getUniqueId/getName/getQueryLanguage/isValid/getConnectionConfig，以及 RawConnectionConfig.getUrl。用每版自带 JBR 运行独立 checker，不启动平台 UI。

| 发行物 | 结果 | connection config owner |
|---|---|---|
| IU-243.28141.41 | RESULT OK，进程 0 | com.intellij.database.model.RawConnectionConfig |
| IU-253.33514.17 | RESULT OK，进程 0 | com.intellij.database.model.RawConnectionConfig |
| IU-262.10968.63 | RESULT OK，进程 0 | com.intellij.database.model.RawConnectionConfig |

日志为本会话 `call_F9VVBBVdkZxhatnUzqx5DZnO-stdout.log`，三目标退出 `[0,0,0]`。此结果不证明三版本 injected SQL PSI、运行时 provider 或真实 Runner 查询执行。

## 桥错包名与 243 scope 语义实证

第四轮真实 SqlDataSourceMappings 已返回唯一 UUID，而生产桥返回 FAILED，diagnostic=ClassNotFoundException。`build/v178-bridge-red.xml` 保存了这一首红。生产仅将 `com.intellij.database.dataSource.RawConnectionConfig` 改为三版真实 owner `com.intellij.database.model.RawConnectionConfig`，没有修改候选融合优先级或降级顺序。

第五轮真实目录 PostgreSQL → 文件 MySQL 覆盖 → 撤文件覆盖继承 PostgreSQL，以及重新取得 SqlFile 的语言均通过。读取必须等平台索引/属性推送完成；setMapping 返回不等于异步文件属性已经更新，测试调用平台 IndexingTestUtil，未人为改持久属性。

另一个计划前提被推翻：243 设置 `SqlResolveMappings=TreePattern.EMPTY` 后，真实 SqlDataSourceMappings.getDataSources 仍返回该 PG 数据源。反编译 `SqlDataSourceMappings.getDataSourcesForResolve → DbSqlUtilCore.getAppropriateDataSources` 可见该入口按有效方言筛选，不读取 SqlResolveMappings。测试现在严格比对桥与真实平台返回值，不虚构“EMPTY scope 已清空候选”。桥的 configuredScopePresent 与 CONFIGURED_RESOLUTION 分类能读到配置存在，但这不能证明最终候选已被 resolution scope 缩窄。这是 V178-1 的剩余产品语义核实项，不归入 UI 禁令或 classpath 环境限制。本轮没有擅改决策优先级。

## 最终定向结果

计数顺序为 tests/failures/errors/skipped。

| 实际执行 | 读数 | XML UTC 时间 | 证据 |
|---|---|---|---|
| 243 真实 DatabaseTools 五项，恢复桥修复后 | 5/0/0/0 | 2026-10-15T18:31:15.831Z | build/test-results/testDbDriverPipeline/TEST-cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest.xml |
| 真实模块身份/重命名/销毁定向 | 5/0/0/0 | 2026-10-15T18:27:07.144Z | build/test-results/test/TEST-cn.song.mybatisplus.visualizer.context.ModuleIdentityResolverTest.xml |
| DM 注册/既有 URL 路由定向 | 3/0/0/0 | 2026-10-15T18:31:14.605Z | build/test-results/testFast/TEST-cn.song.mybatisplus.visualizer.sql.DmDialectRegistrationWiringTest.xml |
| 撤回桥包名修复，仅真实 scope/UUID 一项 | 1/1/0/0 | 2026-10-15T18:29:11.730Z | build/v178-bridge-reverse-red.xml，ClassNotFoundException |

恢复后 DB5+DM3 同命令退出0（日志 `call_gOJWiqk1DZKvOhwvLcCEgZyi-stdout.log`），不是全量。模块5取较早同轮独立任务 XML，其后未修改模块代码/测试，不重跑冒充新读数。共13条不同定向用例，零跳过。未进入 GUI，也未向数据库提交 SQL。

DM 两次初始化失败是产品错误，已经修复，不作为环境限制销账；三版发行物的 InstanceBean 均读取 INSTANCE（262 位于 intellij.database.jar，243/253 位于 database-plugin.jar）。桥候选完整链在修前红、修后绿、撤修再红、恢复再绿，未使用 stub。映射重解析在真实 SqlFile 上断言，不只检查设置值。断开态 session/console 是真实工厂对象，但没有执行查询，不能等同 active connected Runner 双闸验收。

## 续验：关闭 configured scope 误报强证据

此节取代上文“产品语义待核”的处理状态。真实 fixture 同时注册 PostgreSQL/MySQL 两个无连接数据源，对 PG SqlFile 分别写入 EMPTY、限定 MySQL UUID、显式 ALL。UUID pattern 通过真实 `SqlImportUtil.getDataSourceName(mysql)` 与 `createDataSources` 构造（字节码确认使用 quoted uniqueId，不按显示名猜）。三项均先确认实际 provider 返回空、实际 getDataSources 只返 PG，然后调用生产桥和生产决策：修前 **3/3/0/0**，均错误输出 RESOLVED/STRONG/IDEA_CONFIGURED_SCOPE PostgreSQL。首红 `build/v178-scope-first-red.xml`。

单一安全处理：存在明确 configured mapping、但无真实 provider 时，桥将该层标为 UNAVAILABLE，决策不得通过有效方言、已学绑定或项目候选把它重新升级为已验证。手动方言与精确捕获事实仍按原优先级；真实 provider 的分支仍先于配置存在性判断。未设置 mapping 的默认 ALL 不进入降级，清除配置后恢复原默认行为。保留平台原候选供诊断，但不声称它们是 scope 已限定的集合。没有猜解 TreePattern、没有选择第一个候选。

预览状态行与详情明确说明“无法可靠解析 IDEA 已配置的解析作用域；请手动指定方言或选择明确的执行目标”，仍显示兼容预览未验证。不是宣称已经支持显式 scope 解析。

三版精确签名新增：继承的 getConfiguredMapping(VirtualFile) 擦除返回 Object、声明 owner 均 PerFileMappingsBase；getDefaultMapping 与 EMPTY/ALL 常量实际类型均 TreePattern；DataSourceProvider.getDataSources(PsiFile) 与 getProvidedDataSources 返回 List。243/253/262 新门均 RESULT OK，日志 `call_nu2myo8fCEwn3b4UJBccpI78-stdout.log`。真实 JdbcConsoleDataSourceProvider 属 virtualFileDataSourceProvider，不能混作 sql.dataSourceProvider 或伪造其非空返回；本次三配置场景真实 sql provider 返回空。

修复首次平台 **8/0/0/0**；撤回关键分支再次 **3/3/0/0**，仍错误 STRONG（`build/v178-scope-reverse-red.xml`，UTC 18:49:12）；恢复后最终真实平台 **8/0/0/0**（UTC 18:51:42.947），四类定向回归 Registry 2、Bridge 7、Decision 7、StatusText 2，合计 **18/0/0/0**，命令退出0（`call_iRtweb70Sd78Ve7fZFAbxJuv-stdout.log`）。真实测试同时验证三配置清除后恢复默认路径、手动 Oracle 和 captured MySQL 不受损、可见状态包含专用诊断。XML 观测实际 sql provider 扩展列表为 `[]`；因此非空真实 provider 在本 fixture 未覆盖，未人为注册伪 provider 换绿。纯测试仅回归既有手动/provider优先级，安全修复主证据是以上真实服务、真实模式与真实 PSI，不是手造快照。

## 证据边界

真实合成配置、真实 SqlFile、真实映射及断开态 session/console 可以不启动 runIde 验证；不等于真实连接后提交 SQL、Runner 选择 UI、active console 切库、二击重渲染、结果页、取消查询或视觉矩阵。Java/Kotlin/XML 的真实宿主注入、253/262 平台运行语义仍需独立 fixture 或获准后 UI 验证。DBX 的 MySQL8.0.12 与 PostgreSQL16.14/dbx_test 引擎语义由主控独立记录，本任务未读取其凭据，不能拼接为 IDE Runner 端到端成功。
