# T-ErrorLocateUsability 错误定位按钮可用性判定与 Oracle 位置形态 — 开发计划

- 日期：2026-09-21 23:24
- 任务编号：T-ErrorLocateUsability（第十轮报告 §9.4 **O-49**，**P1**）
- 来源：第十轮报告 §8 DBX 增量清单 **#54**——DBX `0668bdc4`：`apps/desktop/src/lib/sql/errorPosition.ts:111-118` `sqlErrorHasMessagePosition`、`:145-148` `sqlErrorDisplayPosition`（显示与跳转**共用同一解析器**）、`:150-175` `resultSourceRange` 失败即拒绝、`ContentArea.vue:352-360`
- 性质：**可用性 + 诚实性**——修的是「按钮可见但点了没反应」这一静默形态
- 实施分支：自 master 拉任务分支 t-error-locate-usability
- 验证基线：master `03f6d25`（最近可核读数 491 类/5604 用例 0 失败 与 5577 例 0 失败，属不同批次树；实施时以当次实测为准）

---

## 一、背景与依据（现状核实，含四处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「跳不动就不给按钮」 | **未实现，且 KDoc 与实现不符（口径修正 1，本轮真缺陷）**：`ui/SqlResultPanel.kt:249-262` 的 `showErrorRich` 按 `locate != null`（`:252`）决定按钮显隐，KDoc 写「null = 锚点不可解析、按钮不出现」；但**唯一调用方恒传非 null lambda**（`ui/SqlPreviewPanel.kt:1030/1046/1055/1253/1271/1283/1392` 七处均 `onErrorRich = { info -> showErrorRichWithLocate(info) }`），而锚点解析发生在**点击时**（`:1296-1303` `locateErrorSource()` 里 `project ?: return`、`anchor ?: return` 静默返回）→ **按钮可见，点了什么都不发生** | 本任务的 P1 依据；修法极简（1 文件） |
| 2 | 「区分执行态 SQL 与源 SQL」 | **仅以文字承认，未结构化（口径修正 2）**：`ui/SqlPreviewPanel.kt:1290-1292` 的 KDoc 明写「驱动报错位置指向**被执行 SQL 文本**的字符/行，与 mapper 原文行不一一对应（动态 SQL 拼接、参数注入后行号必然错位），v1 只承诺『语句起始行』跳转」；`SqlResultPanel.kt:253-257` 的 `positionChar` **只进 tooltip 文案、不参与行计算**。全仓唯一显式两态分离在别处（`execute/MysqlClientGSuffix.kt:8` 提交文本与历史原文两态分离） | 缺口是「未结构化」而非「未意识到」——落点是把两态变成字段 |
| 3 | 「驱动无结构化位置时从消息文本解析」 | **部分实现**：`execute/ExecutionErrorInfo.kt:35` `ErrorPositionExtractor` 已解析**三种**文本 marker——`:38` PG `Position:\s*(\d+)`、`:41` `at line (\d+)`、`:44` `line:\s*(\d+)`；marker **单一性门控**（`:58-62` 恰好一个才归属）；越界钳制 `:65-68`。**无 Oracle 形态**（无 `ORA-` 数字偏移、无 `error occur at position: N`） | 补 Oracle 形态 |
| 4 | 「钳制已生效」 | **已实现但未接线（口径修正 3）**：四处投递点（`execute/DataSourceSelector.kt:2618`、`:2657`、`:2673`、`mcp/SqlVisualizerExplainTool.kt:155`）**均不传** `sqlMaxLength` → 越界钳制分支**只在单测生效** | 属「接线缺失」，随本任务一并接上 |

### 1.1 现有链（逐环节）

| 环节 | 落点 | 职责 |
|---|---|---|
| 位置提取纯函数 | `execute/ExecutionErrorInfo.kt:35` | `ErrorPositionExtractor`（三 marker + 单一性门控 + 越界钳制） |
| 富信息组装 | 同文件 `:84-116` | SQLState / errorCode（cause 链 ≤5 + nextException 链 ≤3） |
| 投递点（3 处 catch） | `execute/DataSourceSelector.kt:2618`/`:2657`/`:2673` | native 链 / executeQueries / onFailure |
| 穿参链 | `execute/SqlExecutor.kt:40`、`:95-98` → `DataSourceSelector.kt` 多处 | 默认 null |
| UI 按钮 | `ui/SqlResultPanel.kt:77-86`（创建）、`:249-262`（`showErrorRich`） | 仅错误态出现 |
| 锚点登记 | `actions/ConvertToSqlAction.kt:68-71` → `ui/SqlPreviewService.kt:106`/`:109` | 项目服务单槽 |
| MCP 侧 | `mcp/SqlVisualizerExplainTool.kt:155-161` | `errorPosition: char/line N` 附消息 |
| **对照组（同仓已做对）** | `log/SqlLogPanel.kt:1779-1782` | `e.presentation.isEnabledAndVisible = resolved != null`——**先解析、后显隐**；配 `log/EntryJumpResolver.kt:26-30` 的 `JumpTarget` 四态（Precise/NoSource/NotFound/MissingSource） |

**机制在同仓已存在**（`SqlLogPanel` 侧），错误定位侧漏用——这是本任务最省力的地方：照抄既有形态即可。

### 1.2 既有测试

`execute/ErrorPositionExtractorTest.kt`（**11 例**：PG Position 提取 / MySQL at line / line: 形态 / 多 marker 放弃归属 / 无 marker 返回 null / 越界钳制 / SQLState 与错误码 / cause 链穿透 / nextException 链 / 富信息位置字段 / 非 SQL 异常零 SQLState 不虚报）、`execute/ConnectionErrorClassifierTest.kt`、`log/EntryJumpResolverTest.kt`、`log/SqlLogPanelXmlJumpTest.kt`、`log/SqlLogPanelLiveJumpWithSourceTest.kt`、`log/HistorySqlLogPanelJumpToSourceTest.kt`。**缺口**：错误定位按钮的显隐/可用性**无 UI 测试**（`SqlResultPanel` 无对应测试类）。

## 二、目标与非目标

**目标**：① 跳转入口**先解析后显隐**——解析不出锚点就不显示按钮（对齐同仓 `SqlLogPanel` 的既有形态）；② 补 Oracle 的位置形态解析；③ 把 `sqlMaxLength` 钳制接上生产；④ 如实标注「报错位置指向执行态 SQL」。

**非目标**：不做执行态→源 SQL 的**位置换算**（动态 SQL 拼接 + 参数注入下行号必然错位，换算不可靠；现有「语句起始行」承诺维持，属诚实降级）；不改日志面板侧的跳转（那条链已做对）；不新增弹窗形态（沿用既有按钮与 tooltip）。

## 三、方案

**T-ELU-01（先解析后显隐，最小改动）**：`ui/SqlPreviewPanel.kt:1306-1307` 改为**先解析锚点、解析不出传 `null`**——`showErrorRich` 的 `locate` 参数本就支持 null（`SqlResultPanel.kt:252` 已按 null 判定），**UI 侧无需改动**。对齐 `SqlLogPanel.kt:1779-1782` 的形态。

**T-ELU-02（补 Oracle 位置形态）**：`execute/ExecutionErrorInfo.kt` 的 marker 集合增补 Oracle 形态。**先红后绿**：用真 Oracle 连接取真实报错文本再定 marker（见 §五-1），**不得凭 `ORA-` 前缀臆造**（Oracle 的错误文本里 `position: N` 出现在「table or view does not exist」这类错误里，形态与 PG 的 `Position:` 不同）。

**T-ELU-03（接线 `sqlMaxLength`）**：四处投递点（`DataSourceSelector.kt:2618`/`:2657`/`:2673`、`SqlVisualizerExplainTool.kt:155`）接上提交文本长度——使越界钳制从「仅单测生效」变为生产生效。

**T-ELU-04（两态如实标注）**：`ExecutionErrorInfo` 增字段（如 `executedSqlLength`）或显式标注来源为执行态文本，供 tooltip 如实说明「位置对应被执行的 SQL，非 mapper 原文行」。**不引入换算**。

**T-ELU-05（测试与文档）**：① 新增 `SqlResultPanel` 的显隐用例（锚点可解析 → 按钮可见；不可解析 → 不可见）——**这是本任务的核心判据**，当前该类无测试；② Oracle marker 用例（先红后绿）；③ 钳制接线用例；④ 既有 11 例全绿。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第十轮报告 §8 #54 落地备注。

## 四、验收标准

1. **锚点不可解析时按钮不出现**（用例 + 真机各一）——改前「可见但点了没反应」，改后「不可见」。
2. Oracle 位置形态可解析（用真库文本钉住）；既有三种 marker 行为不变。
3. `sqlMaxLength` 钳制在生产路径生效（用例断言越界被钳）。
4. tooltip/文案如实标注「位置指向执行态 SQL」（不宣称能定位到 mapper 原文行）。
5. 既有 11 例 + `EntryJumpResolverTest` + 日志面板三跳转测试全绿；全量 testShards + testDatabase + agent 绿。

## 五、验证清单（可经 DBX MCP 执行）

**Oracle 侧已实测可用**：`Oracle Free 23 / FREEPDB1`（`114.66.55.245:20095`，connection_id `311778f3-…`）。**纪律：共享库只读验证、禁止建删对象。**

| # | 场景 | 判据 |
|---|---|---|
| 1 | 经 Oracle 连接执行一条**必然报错**的语句（如引用不存在的表：`SELECT * FROM dbx_test.no_such_table_xyz`） | 取真实报错文本，确认是否含可解析的位置形态（本轮尚未取，**这是 T-ELU-02 的第一步**） |
| 2 | 同场景换 PG（`ce028080-…`）/ MySQL 连接 | 既有三种 marker 仍生效 |
| 3 | 真机 IDE：故意让预览执行失败且无锚点（如无项目上下文） | 按钮**不出现**（改前会出现且点了无反应） |
| 4 | 真机 IDE：正常失败且锚点可解析 | 按钮出现且点击正确跳转（回归确认） |

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **Oracle marker 臆造**（最大风险） | 若凭 `ORA-` 前缀猜形态，会写出永不命中的规则（或误命中） | T-ELU-02 强制**先取真库文本**再定 marker；先红后绿两次读数 |
| 按钮消失被当成功能退化 | 用户可能习惯「有按钮」 | 变更登记写明「解析不出位置时不再显示按钮（此前点了无反应）」；tooltip 说明原因 |
| 钳制接线改变错误文案 | 接上 `sqlMaxLength` 后超长 SQL 的位置会被钳到 SQL 长度 | 用例断言钳制边界；文案保持既有形态 |
| 两态标注引入新字段影响 MCP 输出 | `SqlVisualizerExplainTool.kt:155-161` 的 `errorPosition` 文案若变会影响 agent 侧 | 保持既有 `char/line N` 形态，仅**追加**来源说明；MCP 用例确认 |
| 无 UI 测试基建 | `SqlResultPanel` 当前无测试类，新增需建基建 | 照既有 UI 测试形态（如 `log/SqlLogPanel*Test`）搭最小夹具；若成本超预期，退为「解析结果断言 + 真机走查」两段 |

## 七、实施顺序

```
T-ELU-01（先解析后显隐，最小改动、独立可并行）→ §五-1（取 Oracle 真实报错文本）
  → T-ELU-02（补 Oracle marker，先红后绿）→ T-ELU-03（接 sqlMaxLength）→ T-ELU-04（两态标注）
  → T-ELU-05（测试与文档）→ §五 真机走查 → 收口
```

## 八、实施记录（2026-09-22 回填，T-Round10-03）

**先读这一节再看 §一~§七**：本轮实施发现计划 §一 的「真缺陷」判定与四处落点有偏差，已按实际代码更正（详见各行「偏离」）。

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-ELU-01 | 本任务不提交（主线程统一收口） | **落点由 `ui/SqlPreviewPanel.kt:1306-1307` 扩到三处**：① `showErrorRichWithLocate` 改为 `showErrorRich(info, resolveLocateAction())`——投递时解析锚点、解析不出传 null（对齐 `log/SqlLogPanel.kt:1779-1782`），原 `locateErrorSource()` 的「点击时解析 + 静默 return」形态删除，点击动作捕获投递时解析出的锚点；② **计划漏判的抹除链**：`execute/DataSourceSelector.kt` 三处投递点在 `onErrorRich` 之后**无条件**再投旧 `onError`（原 `:2620`/`:2659`/`:2675`），而 `SqlPreviewPanel` 的 `onError` 恒为 `resultPanel.showError` → `ui/SqlResultPanel.kt:231-232` 会清掉 `locateHandler` + 按钮可见性 ⇒ **端态恒为「按钮不可见」**。新增 `DataSourceSelector.deliverError` 收口（富通道优先、两者互斥、富通道抛异常回退旧通道），三处提交链 + 控制台历史错误三处（`refreshResultsComponent`/`startResultTabRetry` 早检/40 拍窗口）全部改走它；③ **计划诊断的「按钮可见但点了没反应」在生产中不成立**（实读 `:2620`/`:2659`/`:2675` 与 `:231-232` 证伪），真正形态是「按钮从不出现」；而语句级 SQL 错误走 console history 旧通道（原只投 `onError`）⇒ 本任务把这三处也接进富通道，否则定位按钮对「正常 SQL 执行失败」永不出现。UI 侧仅改 `SqlResultPanel` 的 KDoc（记录契约与抹除链），代码零改动。 |
| T-ELU-02 | 同上 | **Oracle 位置形态用插件自己的驱动栈取证，结论与计划前提不同**：`ojdbc11 23.3.0.23.09`（本仓 `build.gradle.kts:93` 声明版本）直连 `114.66.55.245:20095/FREEPDB1`（只读，`dbx_test`）取到的**纯 SQL 错误不带任何位置**——`SELECT * FROM dbx_test.no_such_table_xyz` → `ORA-00942: table or view "DBX_TEST"."NO_SUCH_TABLE_XYZ" does not exist`（无 `Position`/`line`/`at line`）；`ORA-03049` 同。**可解析位置只出现在 PL/SQL 块**：`BEGIN nonexistent_proc_xyz(); END;` → `ORA-06550: line 1, column 7:` + `PLS-00201: …`；报错语言随客户端 JVM locale（`-Duser.language=en` 出英文、默认出中文 `ORA-06550: 第 1 行, 第 7 列:`），两种形态都取自同一次真库取证。据此新增两条 marker（`ORACLE_PLSQL_LINE` 英文 / `ORACLE_PLSQL_LINE_ZH` 中文，同源同义共用 `oracle-plsql` 标签），**只取行**（`column` 是行内列号，不是执行态文本的绝对字符偏移，塞进 `positionChar` 会虚标坐标系）。**偏离 1**：计划 §五-1 让经 DBX MCP 取文本——MCP 返回的是 `ORA-00942 …` + 换行 ` error occur at position: 23`，但该行**不来自 Oracle 驱动**（全 jar 扫描 ojdbc11 无 `error occur at position` 字符串；DBX 侧 Oracle agent 是原生 exe `~/.dbx/agents/drivers/oracle/agent.exe`），故只作旁证、不作真值。**偏离 2**：计划称「无 Oracle 形态」被证伪一半——` error occur at position: N` 会被既有 `PG_POSITION` 正则命中（`(?i)\bPosition:`），但该文本不流经插件链，**不为其造 marker**（避免凭 DBX agent 文本臆造）。**偏离 3**：「marker 用例改前必红」成立——临时注释掉两条 marker 收集后 `ErrorPositionExtractorTest` 18 例中恰 2 例红（`testOraclePLSQL位置形态_英文locale` / `_中文locale`），其余 16 例绿。 |
| T-ELU-03 | 同上 | 四处投递点接上提交文本长度：`DataSourceSelector` 提交链三处传 `executedSql.length`（`:2398` 声明，整篇控制台提交文本）、控制台历史错误三处传 `sqlText?.length`、MCP `SqlVisualizerExplainTool` 传 **`target.explainSql.length`**（真正提交的包装后语句，非原始 `selectSql`——坐标系必须一致）。**偏离（如实登记，非缺陷）**：驱动器报的位置是**语句内**坐标，而上界是整篇提交文本长度 ⇒ `value ≤ max` 恒成立，钳制在生产中**不具约束力**（只是把死参数接活、不再只对单测生效）。计划 §一 口径修正 3 的「变为生产生效」应读作「接线生效」而非「钳制实际咬合」。 |
| T-ELU-04 | 同上 | `ExecutionErrorInfo` 增 `executedSqlLength: Int?`（`buildInfo` 把传入上界如实带出，**不引入换算**）；MCP 侧 `errorPosition: char/line N` 行**逐字节不变**，另起一行追加 `errorPositionSource: offset into the executed EXPLAIN statement text, not the mapper source line`。**偏离**：计划要求「tooltip 如实标注位置指向执行态 SQL」——tooltip 文案来自 bundle 键 `execute.error.locate.tooltip.start/position`（`src/main/resources/messages/MpSqlVisualizerBundle.properties:251-252` 与 `MpSqlVisualizerBundle_zh.properties:233-234`），**这两个文件不在本任务写集内**（且并行波次另有任务可能占用），故本轮**未改**；现有英文/中文文案已含「char position to source-line mapping is unreliable」，与「位置指向执行态 SQL」字面等价度不足，**登记为未完成**，需另起小任务改 bundle（中英同改，保持 key 集合与 `{0}` 占位符个数不变以过 `BundleParityGuardTest`/`BundlePlaceholderArityTest`）。 |
| T-ELU-05 | 同上 | 用例挂既有类，未新建基建：`ui/SqlResultPanelUiTest.kt` +3 例（`test错误定位按钮_无锚点时不可见` **先红用例**、`_锚点可解析时可见`、`_旧通道不得抹掉富通道的定位态`），`execute/ErrorPositionExtractorTest.kt` +7 例（Oracle 中英形态、Oracle 纯 SQL 无位置不虚报、跨行号放弃归属、`deliverError` 越界钳制+上界带出、无富通道回退、富通道异常回退）。**偏离 1**：计划称该类「4 个 JUnit3 用例」实为 **6 个**（`SqlResultPanelUiTest.kt:42/58/78/94/110/132`），且 §六「无 UI 测试基建」应删（类早已存在、夹具可复用）。**偏离 2**：验收① 的命令 `testFast --tests "*SqlResultPanelUiTest*"` **匹配 0 例**（该类 import `com.intellij.*`，不进 testFast 快桶，见 `buildSrc/src/main/kotlin/mpv-testing.gradle.kts:99-110`），实际用 `test --tests …`。**偏离 3**：判据必须走 `SqlPreviewPanel.showErrorRichWithLocateForTest`（新增 internal 缝）——直接调 `SqlResultPanel.showErrorRich(info, null)` 改前改后都绿（`:252` 本就按 null 判定），证不了「投递点是否先解析」。**偏离 4**：计划 §三/§四 把 CHANGELOG/变更记录/第十轮报告 #54 备注算作 T-ELU-05 交付，与任务说明「由 T-Round10-08 统一写」冲突——按后者执行，本轮只回填本 §八。 |
| 门禁读数 | — | 定向：`./gradlew.bat test --tests "*SqlResultPanelUiTest*" --tests "*ErrorPositionExtractorTest*" --tests "*EntryJumpResolverTest*" --tests "*SqlLogPanelXmlJumpTest*" --tests "*SqlLogPanelLiveJumpWithSourceTest*" --tests "*HistorySqlLogPanelJumpToSourceTest*"` → **53 例 0 失败**（18+9+6+4+10+6）；`--tests "cn.song.mybatisplus.visualizer.mcp.*"` → **21 类 133 例 0 失败**；`--tests "…execute.*" --tests "…ui.*"` → **139 类 1500 例 0 失败**。先红两次读数：UI 无锚点例（还原改前「恒传非 null」形态）**9 例 1 失败**、Oracle marker 例（注释掉 marker 收集）**18 例 2 失败**，两次探针均已还原（还原轮以全仓临时标记正则复核，0 命中；本行写作时该正则仅命中 `sql/Dialect.kt:80`，属 T-Round10-05 的在途标记，其后亦已还原）。**未跑**：`testShards` 全量（主流程统一跑）、`verifyGate`、runIde 真机走查（§五-3/§五-4，本任务未跑，**登记为未验证**）。 |
| 环境备注 | — | 本仓当前有 4 个并行任务活跃，共享 `build/` 被并发编译互相清空（实测 `compileTestKotlin` 大面积 `Unresolved reference`）——本任务在仓库外 init script 里把 `layout.buildDirectory` 指向 `build-MPSV-ErrorLocate` 后读数才稳定（该隔离目录实际落在 `buildSrc/build-MPSV-ErrorLocate`，**未被 gitignore 覆盖**——`git check-ignore` 无命中；还原轮已删除，现 `git status` 不再列出）；另发现 `mcpStub` 源集产物会被后续 Kotlin 编译清空（既有接线靠「产物恰好在场」，无任务依赖），临时用稳定副本喂 test 编译/运行时 classpath 规避。**该 init script 位于 `%TEMP%`，不在仓库内**。 |

**待验证前提（实施后更新）**：

1. **Oracle 报错文本是否含可解析位置**——**已证伪「纯 SQL 错误带位置」、证实「PL/SQL 块带 `line N, column M`」**（真库 + 本仓声明驱动版本直连取证，原文见 T-ELU-02 行）。计划 §五-1 指定的 DBX MCP 取证路径给出的 ` error occur at position: N` 经证不来自 Oracle 驱动，已改判为旁证。
2. MySQL `at line N` 语义——未复核（T-ELU-02 已用 Oracle 真库完成同类取证，MySQL 侧沿用既有实现与既有 11 例，未新增真库读数）。
3. 锚点单槽在多预览窗口下是否被覆盖——`SqlPreviewService.kt:2084-2098` 单例化（关旧开新）已核实，槽不被覆盖，T-ELU-01 的「投递时解析」无需考虑覆盖场景。
4. `SqlResultPanel` 的 UI 用例基建成本——已证伪「需搭基建」（类已存在），成本为 0。

