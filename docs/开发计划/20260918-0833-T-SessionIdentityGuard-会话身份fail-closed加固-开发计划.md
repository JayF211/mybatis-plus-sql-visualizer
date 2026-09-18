# T-SessionIdentityGuard 会话身份 fail-closed 加固 — 开发计划

- 日期：2026-09-18 08:33
- 任务编号：T-SessionIdentityGuard（第七轮报告 §9.4 **O-33**，P2；原拟定名「会话恢复身份 fail-closed」——核实后改名收窄为「仍在猜的分支加固」，跨 IDE 重启恢复面定案不适用）
- 来源：第七轮报告 §7 zMyBatis 精华清单 **Z-6**——持久化以「稳定数据源 UUID + 显式 schema」为身份，恢复校验失败即弃而非猜测（ConsoleCacheService.kt:43-52）
- 性质：执行环正确性——共享会话复用与数据源记忆层仍有四处「静默猜」，与 T-ConsoleDbContext「URL 库为准」定案同方向收尾
- 实施分支：合流后自 master 拉任务分支 t-session-identity-guard（前置项 T-MergeDev2Master 策略决策）
- 验证基线：合流后 master 全量 testShards 绿（以合流时钉死计数为准）

---

## 一、背景与依据

zMyBatis 对会话身份的原则是「校验失败即弃，绝不猜」。本插件锚点核实（master 实测）的结论：**冷启动与执行代纪层面已是 fail-closed**（跨项目缓存拒绝、exec 代际计数作废挂起提交、`findScriptModel` null 明确报错、run 失败清记忆），跨 IDE 重启本就没有会话恢复逻辑（重启即冷选，无「恢复猜错」问题）——这两面不适用。真正的缺口集中在**复用与记忆层**的四个「静默猜」分支：

| # | 猜测点 | 现状行为 | 位置 |
|---|---|---|---|
| 1 | 共享控制台复用身份 | 只比 last-used **显示名** + `console.isValid()`——数据源删除/重建后旧 console 仍 valid 即照常复用，直到连接失败才暴露（attach 路径有 `probeConsoleValidity` 含 `dsInFacade` 探测，复用热路径**不调**） | `DataSourceSelector.kt:370,374-376`（对照 attach `:1306-1325`） |
| 2 | last-used 记忆命中 | 同名多条数据源时 `firstOrNull { displayName == last }` 静默取首条，无提示 | `DataSourceSelector.kt:307` |
| 3a | 库上下文对齐 | URL 无库时**静默跳过对齐**（保留 DBN 文件映射/历史记忆库——真机「钉错库」事故形态仍可复现） | `execute/ExecConsoleContext.kt:35` |
| 3b | schema 设置 | `switchSchema` 失败回退 `setSearchPath` **强设不校验目标库存在性**；`ObjectKind.DATABASE` 硬编码对 PG 等 schema 型方言是否正确未验证 | `ExecConsoleContext.kt:40,42-45` |
| 4 | attach 路径探测失败 | 仅 disposed 快速失败；`dsInFacade=false` 等失效重试 3×300ms 后 warn **继续** | `DataSourceSelector.kt:1311-1325` |

辅助事实：`DataSourceSettings`（项目级持久化 `defaultDataSourceId`=UUID + `getUniqueId()` 反射封装）已存在但**全仓零调用**——UUID 身份能力已备未接（M36 遗留）。

## 三、方案

**T-SI-01（复用身份接 UUID 探测）**：`reuseSharedConsole` 复用判定从「显示名相等 + isValid」升级为「显示名相等 + isValid + `probeConsoleValidity` 的 `dsInFacade` 分量」（复用 attach 路径既有探测，不重造）；`dsInFacade=false` 时弃共享会话、清 last-used 记忆、走重新选择（fail-closed 与 attach 同规）。探测成本可接受（复用是低频路径），必要时复用既有有界重试节奏。

**T-SI-02（记忆键升级 UUID）**：last-used 记忆从纯显示名升级为「UUID 优先 + 名字回退」双字段（`PropertiesComponent` 键新增一个，旧键读取保留兼容一个版本）；命中顺序：UUID 精确命中 → 名字命中且**唯一** → 否则弃记忆走弹窗（同名多条不再静默取首条）。`DataSourceSettings.getUniqueId()` 反射封装直接复用。

**T-SI-03（对齐 fail-open 两点收口）**：`ExecConsoleContext.alignToDataSourceDatabase`：①URL 无库时不再静默跳过——照常尝试对齐，失败则 `describeContext`/预览头标注「未能确定默认库，当前=X」（诊断可见化，不阻塞执行）；②`setSearchPath` 强设后读回校验（`select current_database()`/`current_schema()` 轻量读回），不一致时醒目告警并附实际库（是否阻断执行挂起：v1 只告警不阻断，避免破坏只读场景）。`ObjectKind.DATABASE` 硬编码的 PG/schema 型方言行为列为 T-SI-05 真机验证项，验证后决定是否分支。

**T-SI-04（测试与文档）**：`ExecConsoleContextTest`/`DataSourceSelector` 相关测试扩用例：复用探测拒绝（dsInFacade=false 弃会话）、UUID 命中/名字唯一命中/同名歧义弃记忆、URL 无库标注文案、读回不一致告警。CHANGELOG [Unreleased] + 变更记录行 + 第七轮报告 Z-6 落地备注回填。

**T-SI-05（真机验证项，不占开发量）**：PG/schema 型方言下 `ObjectKind.DATABASE` 对齐行为实录（正确/需分支两态记录）；数据源删除后复用路径的拒绝表现。

## 四、验收标准

1. 四个猜测点各有对应用例钉住新行为：复用拒绝、歧义弃记忆、无库标注、读回告警。
2. 唯一数据源静默直跑、DBN 正常对齐、正常复用三条主路径行为逐字节不变（防回归断言）。
3. 旧版 last-used 名字记忆兼容读取（升级用户无感），一版后废弃计划在 CHANGELOG 注明。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 预览执行后删除数据源 → 再次预览：不复用旧会话、重新弹选择（而非连接失败才报错）。
2. 建两条同名数据源 → 预览：弹选择而非静默取首条；选定后 UUID 记忆命中。
3. URL 无库数据源 + DBN 映射钉库场景：预览头/错误上下文显示「当前=X」标注。
4. PG 数据源 schema 对齐行为实录（T-SI-05）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| probe 成本进热路径 | 复用路径每次探测 facade | probe 是内存级查询（DbPsiFacade.find 反查），有界重试仅失效时触发；用例断言复用耗时无回退 |
| UUID 反射封装兼容 | `getUniqueId()` 反射在 243/253/262 行为差异 | 该封装 M36 期已做跨版本实测（遗留代码），接线前重跑三版本探测用例；拿不到 UUID 时回退名字逻辑（行为=现状） |
| 读回校验开销 | 每次对齐多一条 `select current_database()` | 仅在 `setSearchPath` 强设路径（switchSchema 失败回退分支）读回，正常对齐不增查询 |
| 告警疲劳 | URL 无库场景每次预览都标 | 标注只进 describeContext 与预览头一行元信息，不弹窗不加 `⚠` 级别 |

## 七、实施顺序

```
T-SI-01（复用接探测）→ T-SI-02（UUID 记忆）→ T-SI-03（对齐收口）→ T-SI-04（测试与文档）→ 真机走查 §五（含 T-SI-05）
```

## 八、实施记录（实施后回填）

实施分支 t-session-identity-guard（合流后基线 8a3a6ff），全部条目 T-SI-01 ~ T-SI-04 落地，
T-SI-05 为真机验证项（不占开发量，见下）。

**立项核实表的两处修正**（计划 §一 表格为立项时实测，实施时逐条复核）：

1. 表 #1「复用判定只比显示名 + `console.isValid()`，数据源删除/重建后旧 console 仍 valid」——
   该前提不成立。243/262 双版本 `javap -c` 实证：`JdbcConsole.isValid()` =
   `super.isValid() && isTargetValid()`，而 `isTargetValid()` =
   `DataSourceUtil.isDataSourceValid(facade, getDataSource())`，该方法字节码即
   `facade.findDataSource(ds.getUniqueId()) != null`——**`isValid()` 早已内含 `dsInFacade` 分量**。
   故 T-SI-01 的探测在现行 DBN 下与 `isValid()` 同源（非新增判据），真实收益是两点：
   身份被拒时把三分量快照进 warn 日志（此前只有一句 `shared console invalid`，看不出是哪一环），
   以及**清 last-used 记忆**（旧实现只丢会话不清记忆，下一轮仍按显示名静默命中同名数据源——
   这才是残留的「猜」）。判据仍按计划接成独立分量（`reuseIdentityAccepted`），
   DBN 若在后续版本把 facade 反查移出 `isValid()`，复用路径不会随之失守。
2. 表 #2/#3a/#3b/#4 的行为描述与当前代码一致，仅行号漂移（#1 由 :370/:374-376 移到 :393/:397-411，
   #2 由 :307 移到 :314-337，#4 由 :1311-1325 移到 :1338-1356）；#3a/#3b 行号未变。

**T-SI-01（复用身份接探测）**：`reuseSharedConsole` 复用判定接 `probeConsoleValidity` 三分量，
新增纯函数 `DataSourceSelector.reuseIdentityAccepted(identity)`（`identity == null` 为探测降级，
放行——探测失败不得把正常复用打成每次重建连接）。拒绝时 `dropSharedConsole()`，
且仅当 `identity.dsInFacade == false`（数据源已从 facade 消失）才 `forgetLastRunner(project)`；
disposed 等其它失效不误清用户记忆（避免为一次 IDE 清扫让用户多点一次选择）。

**T-SI-02（记忆键升级 UUID）**：新增 `PropertiesComponent` 键
`mpsql.visualizer.last.runner.id`（数据源 UUID），旧键 `mpsql.visualizer.last.runner`（显示名）
读写保留——旧键兼容读取一个版本，废弃计划已写进 CHANGELOG。命中顺序落在纯函数
`resolveLastUsedRunnerIndex(memory, runnerNames, dataSourceNameById)`：UUID 精确命中
（经 `DataSourceSettings.getDataSourceById` + `getName` 反射封装取该 UUID 的**当前**名，
数据源改名后仍命中——这是 UUID 相对显示名的增益）→ 名字唯一命中（旧版记忆兼容路径）→
未命中/同名多条（`ambiguous=true`，调用方清记忆走弹窗）。写入侧：控制台建好后经
`consoleDataSourceId(console)`（`getDataSource()` + `DataSourceSettings.getUniqueId` 反射封装）
取 UUID；弹窗路径尚无控制台时**清掉身份键**而不是留着上一轮的 UUID（身份说谎比没有身份更糟）；
`runRunner` 静默失败与身份拒绝两处统一走 `forgetLastRunner` 清双键。

**T-SI-03（对齐 fail-open 两点收口）**：①URL 无库不再静默跳过——`undeterminedDiagnostic`
如实标注「未能确定默认库（数据源 URL 未指定库：…），当前=X」，`describeContext` 的上下文行
同步把「数据源默认库=?」哑值换成同标记（该行是错误提示可见面，风险表要求的「只进
describeContext、不弹窗不加警告级」按此落地）；②`switchSchema` 未生效退回 `setSearchPath`
强设后读回校验（`readBackMismatch`，大小写不敏感），不一致时 `LOG.warn` + 返回带
`库上下文对齐未生效` 标记的诊断串（目标/实际/命名空间/数据源/是否走强设），v1 只告警不阻断。

**实施偏离（一处，已按诚实口径记录）**：计划写「`select current_database()`/`current_schema()`
轻量读回」——243/262 双版本 javap 实证 `JdbcConsole` 与 `DatabaseSession` 均**无同步语句 API**
（无 `getConnection`/`executeQuery` 一类入口，`DatabaseConnectionPoint` 亦无 `getDatabaseName`），
走 `DatabaseConnectionManager.build + createBlocking` 建连跑查询对「控制台就绪即对齐」这条
同步准备链过重（会引入建连延迟与失败面）。故 v1 读回面 = DBN 会话状态
（`getSearchPath().getCurrent()` + `getCurrentNamespace()` 附注），服务端实读回列 T-SI-05。
另：`ObjectKind.DATABASE` 硬编码对 PG 等 schema 型方言是否正确，按计划留 T-SI-05 真机判定，
本轮未分支（`getCurrentNamespace()` 只作诊断附注、不参与判定，避免 PG 的 schema 名与库名
不同造成假告警）。

**T-SI-04（测试与文档）**：新增 `SessionIdentityGuardTest` 23 例（复用身份 4 / 记忆命中 9 /
读回校验与告警 6 / 无库标注 4），全部走纯函数面故进默认 test 与 testFast 快测桶；
`ExecConsoleContextDegradedTest` 补 1 例（降级分支不得带 URL 无库标记，防两种「取不到库」混为一谈）。
验收 2 的三条防回归断言在位：唯一数据源静默直跑（空记忆恒「未命中且非歧义」，不干扰
`runners.size==1 && shouldBeChosenIfAlone` 直跑分支）、DBN 正常对齐（`contextLine` 有库分支
逐字节等于旧格式）、正常复用（三分量全绿放行）。bundle 零新增键（诊断文案沿用
`ExecConsoleContext` 既有硬编码中文诊断惯例，与 `describeContext` 原格式同源），
故无 en/zh 双语与占位符面变更；未新增任何模态弹窗。

**门禁**：testFast 2652/0/0/6 绿（含新类 23 例）；testShards 4971/0/0/6 绿
（shard1 1867 / shard2 1579 / shard3 1525，本轮无 flake，未触发隔离复跑）；
跑前已按主调度统一登记，未自行调用 parallel_tasks.py。

**T-SI-05 真机验证项（待走查，未验证）**：

1. PG/schema 型方言下 `ObjectKind.DATABASE` 对齐行为实录——正确（无需分支）或需按方言分流，
   两态都要留实录；顺带确认 PG 场景 `readBackMismatch` 不产生假告警。
2. 数据源删除后复用路径的拒绝表现：预览执行后删除数据源 → 再次预览应「不复用旧会话、重新弹选择」
   （而非等连接失败才报错），且 idea.log 有 `shared console identity rejected` 行。
3. 建两条同名数据源 → 预览应弹选择而非静默取首条；选定后 UUID 记忆命中（日志带 `memory id=`）。
4. URL 无库数据源 + DBN 映射钉库场景：预览头/错误上下文出现「当前=X」标注（`未能确定默认库`）。
5. 服务端实读回（`select current_database()`）在真实连接上的行为——本轮未实现（见实施偏离），
   真机确认「会话状态读回」是否足以覆盖「强设成功但服务端未切换」的形态；若不足，另立任务补建连读回。

