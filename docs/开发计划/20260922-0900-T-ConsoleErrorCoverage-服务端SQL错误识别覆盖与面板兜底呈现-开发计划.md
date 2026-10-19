# T-ConsoleErrorCoverage 服务端 SQL 错误识别覆盖与面板兜底呈现 — 开发计划

- 日期：2026-09-22 09:00
- 任务编号：T-ConsoleErrorCoverage（对应待分析问题清单 **P15**，编号已占用勿重复分配）
- 来源：**用户真机报告**（2026-09-22 08:48，原文「sql预览面板 运行sql有错误 但是面板没有直接显示 只在Output页面显示了，之前的话在sql预览窗口下方位置应该会显示错误原因并可以一键复制的」）
- 性质：**可用性 + 覆盖面缺陷**——面板错误反馈通道对一类服务端错误完全不可见，且漏检后给出的归因方向是错的
- 实施分支：自 master 拉任务分支 t-console-error-coverage
- 验证基线：master `0338da3`（最近可核读数 491 类/5604 用例 0 失败，属 T-AgentHeartbeat 批次树；实施时以当次实测为准）

---

## 一、现象与证据

用户贴出的 Output 页文本（同一 SQL 连跑两次，08:48:11 与 08:48:28，两次表现相同）：

```
[2026-09-22 08:48:11] Connected
mpj_demo> SELECT addr.tel, addr.address, a.province FROM "user" t
              LEFT JOIN user_address addr
              RIGHT JOIN area a
              WHERE addr.tel LIKE '%1%' AND a.province <= '1'
[2026-09-22 08:48:12] [42000][1064] You have an error in your SQL syntax; check the manual that
                      corresponds to your MySQL server version for the right syntax to use near '"user" t
[2026-09-22 08:48:12] LEFT JOIN user_address addr
[2026-09-22 08:48:12] RIGHT JOIN area a
[2026-09-22 08:48:12] WHERE addr.tel LIKE '%' at line 5
```

（08:48:28 那次与上完全相同，中间夹一条 `use mpj_demo` → `completed in 7 ms`。）

**三条可核事实**：

1. 面板下方错误条（`SqlResultPanel` 红字 + 「复制错误信息」按钮）两次都没出现。
2. 错误原文确实进了控制台 Output 页——即 DBN 自己把服务端报文渲染出来了，**不是错误丢了**。
3. **关键反证**：Output 里没有本插件自己写的那一行。插件一旦识别到错误，会 `writeConsoleOutput("执行失败: $err\n")` 把错误同时写进同一个 Output（`execute/DataSourceSelector.kt:851-857`）。用户贴出的文本里没有 `执行失败:` 行 → `onError` **从未被调用** → 这不是「显示了但被后续回调覆盖」，而是「压根没识别出来」。

> 第 3 条依赖用户截图是否完整；即使截图有裁剪，它与下面 §2.2 的判据实测互相印证，结论不变。

## 二、根因

### 2.1 面板错误条的唯一来源：轮询扫文本

服务端 SQL 错误（语法错、约束冲突、表不存在…）**不会以 Java 异常形式穿过插件链**：`doRunQueryInConsole` / `executeQueries` 只要提交成功就正常返回，失败由 DBN 自己渲染进控制台。插件识别它的唯一手段是**轮询扫描控制台历史文档的文本**：

| 环节 | 落点 | 职责 |
|---|---|---|
| 基线 | `execute/DataSourceSelector.kt:2407`（读长 `:560-568`） | 提交前记 `consoleErrorBaseline = consoleHistoryLength(console)` |
| 扫描器 | 同文件 `:612-651` `extractConsoleError` | 只扫基线**之后**新增的文本，取末尾命中的一行 + 后 3 行 |
| 判据 | `:654-659` `ERROR_SIGNATURES`（14 条短语）+ `:662-671` `isSqlLine` | 行内 `contains` 命中签名，且该行不是纯 SQL 行 |
| 早检 ① | `:2888-2899`（结果 Tab 重试，400ms×40/100 拍） | 每拍调一次 `extractConsoleError` |
| 早检 ② | `:846-860`（结果刷新，600ms×8 拍） | 同上；命中则写 Output + `onError` |
| 兜底 | `:2946-2978`（40 拍 = 16s 到期） | 取不到真实错误 → 给笼统提示 |
| 面板呈现 | `ui/SqlPreviewPanel.kt:1250`/`:1268`/`:1280` → `ui/SqlResultPanel.kt:217-236` | 红字 + `panel.copyError` 复制按钮 |

### 2.2 判据是「词表 contains」，这条错误一条都不含

`ERROR_SIGNATURES` 全文：`sql exception` / `syntax error` / `sqlstate` / `unknown column` / `unknown table` / `doesn't exist` / `does not exist` / `access denied` / `deadlock` / `error code` / `broken pipe` / `connection refused` / `[日语错误]` / `sql（` / `exception in thread`。

用户这条 MySQL 报文**一条都不含**：

- 它写的是 `You have an error in your SQL syntax;`——**不是** `syntax error`；
- SQLSTATE 以数字形态 `[42000]` 出现，**没有** `sqlstate` 这个词；
- 也没有 `error code` 字样（是 `[42000][1064]`）。

→ `extractConsoleError` 返回 null → 无 `onError` → 面板无红字、无复制按钮。

**判据实测**（把 `:654-671` 的判据逐字复刻成脚本跑样例；复刻脚本不是权威，实施时应以真 Kotlin 用例复核，见 §六-1）：

| 样例 | 结果 |
|---|---|
| **用户本次真机** `[42000][1064] You have an error in your SQL syntax …` | **漏检** |
| MySQL `Unknown column 'u.username' in 'field list'`（1054） | 命中（历史成功案例） |
| MySQL `Table 'x' doesn't exist`（1146） | 命中 |
| PG `ERROR: relation "x" does not exist` / Oracle `ORA-00942: table or view does not exist` | 命中 |
| Oracle `ORA-00933: SQL command not properly ended` | **漏检** |
| 达梦 `无效的表或视图名[USER_X]` | **漏检** |
| MySQL `Duplicate entry '1' for key 'PRIMARY'`（1062） | **漏检** |
| MySQL `Cannot delete or update a parent row: a foreign key constraint fails`（1451） | **漏检** |

### 2.3 历史为什么「看起来能用」

M36.5 期间真机验证过的错误**恰好全是 `Unknown column` 系**。计划文档留存的日志读数是
`extracted console error: [42S22][1054] Unknown column 't.station_name' in 'field list'` 与
`early console error detected (attempts=1)`（`docs/开发计划/20260830-1334-原生控制台嵌入方案AB-开发计划.md:569/598/966-967`）——**没有一条语法错样例**。

另一处旁证：`docs/变更记录.md:137`（T-ExplainDbl）记录 MySQL `[42000][1064]` 那次，用户当时看到的是「等待结果超时」提示，而不是错误原文——同一条漏检路径。

> **口径**：这是**既有覆盖面缺口**，不是本次改动引入的回归。用户感知为「以前会显示」，很可能来自 `Unknown column` 类错误的成功记忆。§十 待验证前提 1 给出一条可证伪路径。

### 2.4 漏检之后用户看到什么

16s 后走兜底（`:2946-2978`）：`realError == null` 且本次未报过提示 → `execute.timeout.hint`（「SQL 已提交执行，但等待结果超时……请检查 IDEA 主窗口是否有密码输入框；若连接一直未建立，打开 Database 工具窗口 → 右键数据源 → 属性 → 测试连接/下载驱动」）。

也就是说：**面板给出的归因方向是错的**——把一条语法错说成连接/密码问题。若 8s 监控那一路的 `executionHintReported` 已置位，面板则会从头到尾停在加载态。（此段为代码可判，未真机取证；复现时一并记录，见 §十 前提 3。）

## 三、候选修法对照

| 方案 | 内容 | 代价 / 风险 | 推荐 |
|---|---|---|---|
| **A 结构性判据**（主） | 识别 DBN 给服务端错误加的**前缀形态** `[SQLSTATE][errorCode]`（`[42S22][1054]`、`[42000][1064]`、`[23000][1062]`）——跨方言、不依赖词表；再补几条高频短语族 | 1 文件、纯函数、可单测 | ★★★ |
| **B 兜底呈现**（主） | 40 拍到期、无签名命中、但 history 确有新增文本时，把**新增文本减去本次 SQL 回显后的残余**当错误呈现，而不是笼统超时提示 | 需与回显做差集（`startResultTabRetry` 已持有 `sqlText`）；判据要防「正常回显被当错误」 | ★★★ |
| C 原生通道 | 改用 DBN 自身的错误处理面替代文本扫描 | 内部 API、反射面大、跨版本风险；`database-plugin.jar` 内实证存在 `com.intellij.database.dialects.DatabaseErrorHandler` 与 `BaseDatabaseErrorHandler$ExecutionErrorInfo`，但需先 javap 定形 | ★（本轮只登记，见 §十 前提 4） |

A 与 B 互补：A 让「认得出的错误」立刻显示原文，B 保证「认不出的错误」也不再静默。**两者都不改 UI 结构**，只改识别与呈现内容。

## 四、方案

**T-CEC-01（取证先行）**：给 `extractConsoleError` 的**未命中**路径加一条有界诊断日志（如 `[T-CEC] console tail (no signature): …`，只打基线之后新增文本的尾部若干行，行数与字符数双上限）。目的：实施者先拿到**真实 history 文本形态**（是否带 `[时间戳]` 前缀、错误是否跨行、SQLSTATE 前缀是否在场），再定判据——不照用户截图猜。

**T-CEC-02（结构性判据 + 短语族）**：`ERROR_SIGNATURES` 由「短语词表」升级为「结构性判据 + 短语词表」：

- **主判据**：行内出现 `[<5 位 SQLSTATE>][<数字错误码>]` 形态。实现用轻量字符扫描或正则，**必须容忍行首时间戳前缀**（不得整行锚定 `^`）；
- **短语族补充**（不带 SQLSTATE 前缀的形态）：`you have an error in your sql syntax`、`sql command not properly ended`、`duplicate entry`、`foreign key constraint`、`cannot add or update a child row`、`ORA-`、达梦/金仓中文报错形态——**后两类先由 T-CEC-01 取真机文本再定，禁止臆造**；
- **反误报保持不变**：`isSqlLine` 沿用；新增一条——排除本插件自己写的 `执行失败:` 回显行，避免自反馈；
- 摘录窗口由 4 行放宽到 8 行，并剥掉每行的 `[时间戳]` 前缀（显示与一键复制都更干净）。

**T-CEC-03（兜底呈现）**：`startResultTabRetry` 的 40 拍到期分支（`:2946-2978`）改为三档：有真实错误 → 原文；**无签名命中但 history 有新增非回显文本 → 呈现该文本**，并附一句如实说明（「未能识别为已知错误形态，以下为控制台新增输出」）；两者都没有 → 才落既有 timeout / credential / indexing 提示。

**T-CEC-04（测试）**：`execute/ConsoleErrorExtractTest.kt`（现 6 例）扩成矩阵——真机样例回归 + 各方言错误样例 + 反例（正常 SQL 回显、历史误报样例 `WHERE t.id = 1`、含方括号的正常 SQL、插件自写的 `执行失败:` 行）+ 基线语义不回归。**反向验证**：临时移除新判据 → 真机样例用例必须变红，贴两次读数。

**T-CEC-05（文档）**：CHANGELOG `[Unreleased]`、`docs/变更记录.md`、`docs/验收文档/20260920-待分析问题清单.md`（P15 落结论）、本计划 §九 实施记录回填。

## 五、验收标准

1. 用户这条真机 SQL 在面板下方显示**错误原文** + 复制按钮可用（单测 + 真机各一）。
2. §2.2 表中 4 条「漏检」样例全部转为命中；4 条历史「命中」样例行为不变（防回归）。
3. 反例不误报：正常 SQL 回显、`WHERE t.id = 1`、插件自写的 `执行失败:` 行均不产生错误呈现。
4. 兜底路径生效：构造「新增文本无任何已知签名」的场景，面板呈现该文本（不再是 timeout 提示）。
5. 既有 `ConsoleErrorExtractTest` 6 例 + `ExecuteSqlErrorHandlingTest` 16 例 + `ResultTabSelectionTest` 16 例全绿；全量 testShards + testDatabase 绿。

## 六、验证清单

1. **判据矩阵先跑单测**：`./gradlew test --tests "*ConsoleErrorExtractTest*"`；真机样例文本取自 §一 原文（逐字）。
2. **真机走查（正）**：runIde 沙盒用 §一 的 SQL 打 MySQL（`mpj_demo`）——确认 ① 面板红字出现且可复制 ② Output 同时出现 `执行失败: …` 行 ③ 在 16s 内出现（不必等超时）。
3. **真机走查（反）**：一条**正确** SQL 执行后，面板不出现错误行。
4. **真机走查（兜底）**：构造一条能被库拒绝、但报文不含任何已知签名的语句，确认面板呈现控制台新增文本而非笼统提示。
5. **门禁**：全量 testShards + testDatabase；构建校验（`verifyGate`/`buildPlugin`）在全部改动完成后**只跑一次**。

## 七、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 结构性判据误命中 | 用户 SQL 里恰好出现 `[42000][1064]` 形态文本 | 判据只在「基线之后新增文本」+ 非 SQL 回显行上生效；反例用例钉住 |
| 兜底呈现把正常输出当错误 | 40 拍窗口内 DBN 可能写入非错误的新增文本 | 与 `sqlText` 做差集 + 反例用例；文案如实标注「未能识别为已知错误形态」 |
| 真机文本形态与截图不一致 | 用户贴的文本来源未定（见 §十 前提 2） | T-CEC-01 先取证；判据不锚定行首，容忍前缀 |
| 摘录放宽后复制文本变长 | 面板单行显示 + tooltip 完整 | 不改 `SqlResultPanel` 的既有单行 + tooltip 形态，只改文本内容 |
| 中文/方言短语臆造 | 达梦、金仓报错形态我方无实测样本 | 只认 T-CEC-01 真机取到的形态；取不到就不加（宁漏勿造） |

## 八、实施顺序

```
T-CEC-01（取证日志）→ 真机复现取文本 → T-CEC-02（判据，先红后绿）
  → T-CEC-03（兜底呈现）→ T-CEC-04（测试矩阵 + 反向验证）→ T-CEC-05（文档）
  → §六 真机走查 → 收口（构建校验只跑一次）
```

写集：`execute/DataSourceSelector.kt`（T-CEC-01/02/03 同文件，串行）+ `execute/ConsoleErrorExtractTest.kt`（T-CEC-04）+ 文档三处。**与在册的 T-ExplainChainHardening（O-48）、T-ErrorLocateUsability（O-49）撞 `ui/SqlPreviewPanel.kt`**——本任务不改该文件（错误呈现入口已就绪），若实施中出现需要改它的情形，须与上述两任务串行。

## 九、实施记录（实施后回填）

**实施方式**：独立工作树 `mybatis-plus-sql-visualizer-cec` + 分支 `t-console-error-coverage`（基 `origin/master` `b4cf2ec`），并行隔离沙盒 `MPSV-ConsoleErr`（临时行跑完已还原、未入库）。

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-CEC-01 | `1b54350` | `logUnrecognizedConsoleTail`：未命中判据时记新增文本尾部（6 行 / 600 字符双上限，每次执行至多一条——`lastUnrecognizedTail` 去重 + 执行起点复位）。**真机原文未取到**：本环境无法驱动真机执行失败 SQL（见下「未做」），故日志形态按用户贴出的 Output 报文设计（容忍 `[时间戳]` 前缀），不依赖它 |
| T-CEC-02 | `1b54350` | 判据 = `SQLSTATE_PREFIX`（`[5位][数字]`）+ `ORACLE_CODE`（`ORA-\d{5}`）+ 短语族（原 14 条 **逐字不动** + MySQL 语法错原文 1 条 + 厂商固定报文 5 条）；`isSqlLine` 的防误报改走同一判据；新增自写回显排除（`EXEC_FAILURE_ECHO_PREFIX` 常量与 `writeConsoleOutput` 共用，不再两处字面量）；摘录 4→8 行、逐行剥 `[时间戳]` 与（DBN 实读的）提示符。**偏离**：计划里写的「达梦/金仓中文报错形态」**未加**——按计划自身的纪律（先取真机文本再定，禁止臆造），本环境取不到真机文本，故不加；`ORA-` 走正则而非短语（`ORA-00933` 之类的 5 位码更精确） |
| T-CEC-03 | `1b54350` | `extractConsoleResidue`：新增文本去掉「本次 SQL 回显」（剥时间戳/提示符后是 SQL 子串，长度 ≥4）+ DBN 状态行（connected / completed in / executing / fetching / cancelled）后，取尾部 6 行 / 600 字符；40 拍到期分支改为「真错误 → 原文；无判据命中但有残余 → 残余（前缀 `execute.error.unknownShape`）；两者皆无 → 既有 timeout/credential/indexing 提示」。`executionHintReported` 去重语义保持不变（残余路径同样置位） |
| T-CEC-04 | `1b54350` | `ConsoleErrorExtractTest` 6 → **14 例**（真机样例回归 / 跨方言结构性判据 5 样例 / 三条反例：带方括号的正常 SQL 回显、插件自写 `执行失败:` 回显、历史误报样例 `WHERE t.id = 1` / 四条残余用例含「提示符读不到」退化路径与**成功 DML 摘要反例**——最后一条是收口前自查补的：DML 与工具语句成功同样走满 40 拍窗口，`1 row affected` 不得当残余）。**反向验证两读数**（在补第 14 例之前做的，故读数为 13 例）：临时摘除结构性判据 + 新增短语族（`RED PROBE`）→ **13 例 2 失败**，失败点名恰为 `testUserRealMachineMysqlSyntaxErrorDetected` 与 `testStructuralJudgementCoversDialects`，其余 11 例（含全部反例）仍绿；逐字还原（sha256 复核 `291fd712…` 一致）→ **13 例 0 失败**；补第 14 例后复跑 **14 例 0 失败**，并在收口门禁的分片里再绿一次（shard2 内 14/0） |
| T-CEC-05 | （文档提交） | CHANGELOG `[Unreleased]` §Fixed 首条 + `docs/变更记录.md` 新行 + 待分析问题清单 P15 落结论 + 本计划 §九/§十 |

**验证读数**：定向三类 `ConsoleErrorExtractTest` **14/0**（6 原有 + 8 新增）+ `ExecuteSqlErrorHandlingTest` 16/0 + `ResultTabSelectionTest` 16/0。

**门禁（`testShards` + `testDatabase`，收口轮）**：三片 **5617 例 / 0 失败 / 0 错误 / 6 跳过**（shard1 1845、shard2 1887、shard3 1885；`BUILD FAILED` 仅由 `testDatabase` 的两条环境失败触发）。`testDatabase` 10 例 8 过 2 红——两条均为**在册环境失败**：`DbDriverPipelineTest#testDriverArtifactResolvesToJarsOnClasspath`（报文「artifact 清单应能解析 MySQL ConnectorJ@9.7.0（实际版本集: [8.2.0, 8.1.0, …]）」）与 `#testSeedFilesLandWhereDbLooksForThem`，与 `docs/开发计划/20260921-1637-N6-JOIN图原生路径连线缺失-开发计划.md:307-311` 记录的**同两条、逐字同报文**，该处已做基线对照实验（还原全部改动 → 同样 2 例失败、同样断言文本）判为环境/夹具前置。本轮另证两点：① 失败面（驱动制品版本索引 + 种子落点）与本改动零交集，testDatabase 内其余 8 例（DBN 集成类）全过；② 种子/沙盒路径由 `mpv-testing.gradle.kts:224/245/293` 显式指定为 `build/test-sandbox/...`，与临时 `sandboxContainer` 行无关。

**收口轮之前的两次环境性中断（如实登记，非代码问题）**：① 我停掉首个门禁进程时撞上 Gradle 正在清理共享 transform 缓存 → 243 distro transform 产物被删空（`lib/*.jar` 归零），`compileJava` 报 `com.intellij.*` 不存在；② 随后又逢并行会话重建该缓存，`IDE by path … is invalid: Build number is not found` 与 `Timeout waiting to lock Artifact transforms cache`（并行会话 7 路在跑）。两次都按既有纪律**等待重试**（不删共享缓存、不 kill 他人进程），缓存重建后恢复；期间仅清理了**自己**的残留进程（被 kill 的 gradle wrapper，命令行含本工作树路径）。第 3 轮门禁另有 11 例红（`SectionConfigurableAutoCaptureLinkageTest` ×8 全类红在 `super.setUp()`、`AgentManagerPremainTest#test回连次数达上限_通道退休` ×1、`DbDriverPipelineTest` ×2），第 4 轮（本读数）前 9 例全部消失——属在册的「分片顺序依赖/共享 SqlLogManager 项目服务状态/`awaitTrue` 超时红」假失败族（`docs/开发计划/20260919-0445`、`20260919-1817`、`20260921-1630` 均有登记与三步证伪先例）。

**未做（如实登记，留人工）**：§六-2/3/4 的**真机走查**未执行——需要 runIde 沙盒 + 一个可连的 MySQL 数据源并手动点「执行 SQL」，本会话无法完成（自动化侧已由 14 例判据矩阵 + 反向验证覆盖）。§十 的前提 1/2/3 因此**仍未证伪**（其中前提 2 的答案已由代码侧消解：判据只容忍、不依赖时间戳前缀；前提 1 的兜底路径已由 T-CEC-01 的取证日志承接——真机复现时先看 `[T-CEC] console tail` 一行即可定性）。

**顺带观察（既有行为，非本次引入）**：用户日志里 `use mpj_demo` → `completed in 7 ms` 这类「无结果 Tab 的语句」会走满 40 拍窗口并落回 `execute.timeout.hint`（把成功执行的工具语句说成超时）。本次改动不动这条路径（残余会被「回显 + 状态行」全部剔除 → 仍落既有提示），登记为独立议题，未立项。

## 十、待验证前提（实施者先证伪再动手）

1. **「历史成功样例今天仍能显示」**——真机复跑一条 `Unknown column` 类错误。若它今天也不再显示，说明还有第二条断链（最可能的形态：`consoleErrorBaseline` 语义被 DBN 的文档重写打破——基线大于文档长度时 `:632` 直接 `return null`，此后每拍都空转），此时 T-CEC-02 不足以闭环，须先查基线路径。
2. **用户贴出的 `[时间戳]` 前缀是否属于 DBN 写入 history 的原文**——历史真机日志里的提取读数是 `[42S22][1054] Unknown column …`（**无**时间戳）。若前缀不属 history，则用户截图另有来源（例如另一个客户端）。判据设计须容忍前缀，但**不依赖**它。
3. **8s 监控是否已置位 `executionHintReported`**（决定 16s 后用户看到的是 timeout 提示还是持续加载态）——复现时记录，用于把「用户看到什么」写准。
4. **`DatabaseErrorHandler` 原生通道是否可作为 B 的替代**（本轮只登记不实施）——需 javap 定形 + 243/262 双版本核对；若可行再单独立项。

## 附：顺带观察（不在本任务范围）

被执行 SQL 用的是 `FROM "user" t` 双引号标识符，而目标库是 MySQL。本插件 MySQL 方言的标识符引用是**反引号**（`sql/Dialect.kt:222` 的 `MySqlDialect`，`:229` `quoteIdentifier`），故该引号形态**不是**本插件 MySQL 方言生成的；疑似来自 H2 系 SQL（日志还原）在 MySQL 上执行，或用户手工编辑。**来源未核实**，仅登记为观察项，需真机确认后再决定是否立项——勿照此推断为插件缺陷。
