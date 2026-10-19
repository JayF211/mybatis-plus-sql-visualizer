# T-ConsolePendingFlush（O-70）：控制台待闭合条目停滞导致零捕获

- 日期：2026-09-23 15:05
- 任务编号：O-70（T-ConsolePendingFlush）
- 来源：用户报障「日常 IDE 2026.2 关闭增强捕获后，控制台日志没成功捕获」（附带控制台片段 + 面板截图）
- 性质：解析器**产出时机**缺陷，不是解析规则缺陷——行都到了、规则也匹配，但条目永远没有被交出去

---

## 一、现象与复现坐标

| 项 | 值 |
|---|---|
| IDE | IU 2026.2（`%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2`），插件 2026.9.23（当日 14:24 安装） |
| 被测工程 | `F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-demo` 的 `selfdev-framework-demo`（8086，run config `SelfDevFrameworkDemoApplication`） |
| IDE 日志 | `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log`（14:47–14:48 段） |
| 插件设置 | `%APPDATA%\JetBrains\IntelliJIdea2026.2\options\mp-sql-visualizer.xml`（落盘项仅 `logAutoCaptureOnStart=true` 等三项 → `logJdbcEnabled` 取默认 true） |

面板读数（用户截图，14:48:19 之后）：

```
捕捉中｜增强捕获: 未启用
控制台：已挂载 5 个，已扫描 149 行，产出 0 条｜重放守卫已丢弃 7 行
控制台有输出但未见 MyBatis SQL 日志——请确认应用已开启 SQL 日志输出（mapper 包日志级别为 debug），或启用增强捕获
```

该工程**刻意**让 SQL 落到控制台可比对：`selfdev-framework-demo/src/main/resources/application.yml` 里 `logging.level.org.springframework.jdbc.core.JdbcTemplate: debug`，注释写明「引擎自身不打印 SQL，这里靠 Spring 的 JdbcTemplate DEBUG 日志暴露真正执行的语句」——即控制台通道在本例中**有料可捕**，零产出不是「应用没开 SQL 日志」。

## 二、证据链（全部实测，按时间序）

**1）捕获态、挂载面、解析器配置都没问题**

```
14:47:43,008 SqlLogManager - startCapture: 调用开始（capturing=false, waiting=false, manual=false, ...）
14:47:43,009 SqlLogConsoleBridge - attachFilter: filter(com.intellij.execution.filters.Filter) attached to com.intellij.execution.impl.ConsoleViewImpl
14:47:43,009 SqlLogManager - startCapture: 已开始捕捉 SQL（capturing=true, consoles=3）
```
无 `增强捕获在线态切换` 的再置位（14:47:12 `true -> false` 后一直 `enabled=false` → `AgentManager.onProcessAppeared` 逐次「跳过挂载」、`premain 注入跳过：enhanced-disabled`），故 `SqlCaptureGate.paused` 为 false、EP 未取得信任（面板未显示短路计数）。插件设置文件无 `logJdbcEnabled` 落盘项 → JDBC 解析器为启用态。

**2）控制台里确实有 SQL 行，且分布在两个线程上**（用户提供的控制台片段）

```
14:48:06.127 DEBUG 72660 --- [nio-8086-exec-1] o.s.jdbc.core.JdbcTemplate : Executing prepared SQL query
14:48:06.128 DEBUG 72660 --- [nio-8086-exec-1] o.s.jdbc.core.JdbcTemplate : Executing prepared SQL statement [SELECT p.id, ... FROM biz_project_plan p WHERE p.status = ?]
14:48:18.048 DEBUG 72660 --- [nio-8086-exec-2] o.s.jdbc.core.JdbcTemplate : Executing prepared SQL statement [SELECT p.id, ... WHERE p.status = ?]
```

**3）解析规则本身能命中**（Python 复刻 Java 正则对上述原文实测）：`SQL_ACTION_PATTERN` 命中 `Executing prepared SQL statement`，动作短语与 `[` 之间为单个空白（走同行闭合快路径），净括号扫描在行尾 `]` 归零 → `pendingSql` 会被正确置位。

**4）2026.2 平台的 filter 面**（`javap` 实证 `intellij.platform.lang.impl.jar` / `intellij.platform.lang.jar`，262 构建）：
- `ConsoleViewImpl` 有 `addMessageFilter(Filter)`，**没有** `removeMessageFilter`，只有 `clearMessageFilters()` → 日志里 4 条 `detachAllFilters: FAILED to remove filter ... (filter will remain active!)` 是平台事实，不是临时异常；
- `AsyncFilterRunner$HighlighterJob.analyzeLine` 传的是 `applyFilter(lineText, lineStartOffset + lineText.length())` → 重放守卫「第二个参数=行尾偏移」的前提成立。

**5）两条通道的门都开着**：`buffer.paused=false`（startCapture 首行复位）、`sqlCaptureGate.paused=false`、`capturing=true`、`epActive=false`——所以「零产出」只能发生在解析器的**产出时机**上。

## 三、根因

`JdbcTemplateLogParser` 的条目**只有一个产出点**：`closeBucketPending(bucket)`，而它的触发条件全部是「**同一个线程桶里再来一行**」：

```
feedIdle 分支 2（语句行）→ closeBucketPending(bucket) 只在「本条语句行到来」时闭合上一条
feedIdle 分支 4（噪声行）→ 仅当 bucket.pendingParams.isNotEmpty() 才闭合
closePending()          → 需外部调用
```

按线程分桶（T-JdbcThreadBucket / T-LogParseConc）之后，「同线程的下一行」在本场景**永不到来**：

- 参数行是 TRACE 级的 `Setting SQL statement parameter value`（`StatementCreatorUtils`）——工程只开了 `JdbcTemplate: debug`，**没有参数行**；
- Tomcat 线程池按请求分配线程：日志里两条业务查询分别在 `exec-1`、`exec-2`——低频访问时**每个请求一个新线程**，该线程此后不再有行；
- 实时链路**从不调用 `closePending()`**（全仓仅「日志 → SQL」粘贴面板 `LogToSqlPanel.kt:363-365` 与 MCP `render_logs` 在输入末尾调用）。

于是每条语句都停在自己的桶里等一个永不到来的闭合行，`takeEntries()` 永远取不到 → 面板「已扫描 149 行 / 产出 0 条」。补充两条对照事实：schema 初始化那段 DDL（`spring.sql.init` 走 `ScriptUtils` 的裸 `Statement`）**不经 JdbcTemplate、控制台里没有**，所以那 149 行里真正可捕的正是上述两条各自独占线程的语句；面板提示语指向「mapper 包日志级别」也属误导（本例根本没有 MyBatis 日志）。

**为什么既有测试没抓住**：`JdbcTemplateLogParserTest.feedAll()` 在喂完行后**显式调 `closePending()`**（`src/test/kotlin/.../JdbcTemplateLogParserTest.kt:20-24`），恰好补上了生产链路缺失的那一步；EP/桥的集成用例（`EpBridgeSinglePathTest`）喂的又是 MyBatis 的 `Preparing`+`Parameters` 成对行（同线程自闭合）。两条测试路径都绕过了「语句行之后再无同线程行」这一形态。

## 四、改动清单

1. `log/LogThreadRouter.kt`
   - 每个桶记录**最近活动时间**（`IdentityHashMap<B, Long>`，桶淘汰/清空时一并清理）；
   - 新增 `settledBuckets(settleMs, nowMs = clock())`：返回「已静默 ≥ settleMs」的桶（这些桶后续的参数行（若有）早已到达，闭合是安全的）。
2. `log/JdbcTemplateLogParser.kt`
   - 新增 `flushSettled(nowMs)`：闭合全部静默桶的待产出条目（条目进本解析器 `entries`，由调用方的 `takeEntries()` 随行取走）；
   - `processConsoleLine` 逐行入口在喂行前先 `flushSettled()`——同控制台线程执行、零并发，且冲刷出的条目与本次喂行产出的条目经同一次 `takeEntries()` 一并交出去（不需要额外接线）。
   - 静默阈值 `DEFAULT_SETTLE_MS = 1000`。
3. `log/SqlLogConsoleBridge.kt`
   - **轮询静默冲刷**：`scanAll`（EDT，常规 2s/快速 500ms）在捕捉中且非增强接管时调 `flushSettledEntries()`，把「应用之后一行都不再打」的待闭合条目交出去——没有这一拍，安静下来的应用仍然零产出；
   - **并发互斥**：filter 回调（控制台输出线程）与轮询冲刷（EDT）会碰同一套解析器状态，统一用 `synchronized(context)` 包住；
   - **挂载代际号**（`BridgeLineContext.generation`）：2026.2 无单 filter 移除，stop→start 在同一 console 上会留下摘不掉的旧代理；旧代理若再按 console 键取到新上下文，就会与新代理逐行重复解析、重复计数。旧代理按代际失活（行计数也顺带不再重复计）；
   - `detachAllFilters` 的 `FAILED to remove` WARN 改为事实陈述（平台不支持单 filter 移除 → 已代际失活），不再暗示「filter 还活着」；
   - 冲刷留 INFO 痕（真机验证锚点：有冲刷=行已到桥，问题在解析；无冲刷=行没到桥，回挂载面查）。
   - 覆盖面注记：EP 通道（[SqlConsoleBridgeFilterProvider]）也走 [processConsoleLine]，故逐行冲刷同样生效；
     但它没有定时器（EP Filter 由平台创建、无生命周期钩子），「应用安静后再无新行」这一形态在 EP 单路
     产出时仍会等到下一次平台喂行才闭合。当前真机 EP 未取得信任（面板无短路计数），实际产出通道是桥；
     若日后 EP 成为主通道，需为它补一个冲刷节拍（挂在既有的 EP 注册/会话巡检上）。

## 五、测试与反向验证

- 新增 `ConsoleSettleFlushTest`（纯逻辑 + 桥桩控制台两类）：
  1. 两条语句分属两个线程桶、无参数行 → 静默冲刷后两条都产出（修复前：0 条）；
  2. 单条语句独占线程、之后再无行 → 桥轮询冲刷产出（修复前：0 条）；
  3. 阈值判别力：语句行后 **未满** 阈值时冲刷不得产出；到达参数行后再冲刷 → 参数完整还原（防「冲刷吃掉参数」）；
  4. 老行为保绿：同线程两条语句，第一条仍在第二条到达时产出（无回归）；
  5. 代际失活：旧代理在重挂后不再解析/计数。
- 反向验证：摘掉 `processConsoleLine` 的冲刷调用与 `scanAll` 的冲刷调用 → 用例 1/2 必红，贴两次读数。
- 保绿复跑：`JdbcTemplateLogParserTest`、`EpBridgeSinglePathTest`、`HibernateLogParserTest`、`MyBatisLogParserTest`、`CaptureDiagnosticsTest`。

## 六、待验证前提（实施者先证伪）

1. 「语句行之后不再有同线程行」是零产出的**唯一**原因。真机复验若仍零产出：先看 `scanAll` 的冲刷 INFO 是否出现——出现=行已到解析器（往解析规则查）；不出现=行压根没进桥（往挂载面/控制台归属查）。
2. 静默阈值 1s 的边界：TRACE 参数行正常在语句行后 1ms 内到达（`JdbcTemplate.execute` 先打 DEBUG 语句行、随后 `psc.createPreparedStatement` 里逐参数打 TRACE）；只有同一线程在这两步之间卡住（连接池耗尽）超过阈值才会把参数留到条目之外，表现是 SQL 里 `?` 未还原 + `参数行无上下文丢弃` 计数上升——这是本方案的已知代价，取 1s 是因为它以「同线程静默一秒」为判据，误伤面极小。
3. 平台侧 `addMessageFilter` 加的自定义 filter 会被逐行调用：已由面板「已扫描 149 行」反证（该计数由桥 filter 回调自增）。

## 七、实施记录

- 修复落盘：`LogThreadRouter`（桶活动时间戳 + `settledBuckets` + 淘汰/清空时同步清理）、`JdbcTemplateLogParser`（`flushSettled` + `DEFAULT_SETTLE_MS=1000` + 注入时钟）、`SqlLogConsoleBridge`（`processConsoleLine` 喂行后冲刷 / `scanAll` 轮询冲刷 / `synchronized(ctx)` / 挂载代际号 / 措辞订正）、新增 `ConsoleSettleFlushTest`。
- 测试命令与读数（均为 `./gradlew test --tests …` 定向；同批 17 个类）：

### 红绿双读数

| 读数 | 命令/形态 | 结果 |
|---|---|---|
| 红 1（首版用例） | 摘掉三处修复（逐行冲刷、轮询冲刷、代际守卫） | `5 tests completed, 3 failed`（轮询用例当时是直调冲刷方法，摘掉 `scanAll` 接线不影响它 → 暴露该用例的**接线覆盖缺口**，遂改走 `scanAll`） |
| 红 2（终版用例） | 同上 | `5 tests completed, 4 failed`（`逐行冲刷`/`轮询冲刷`/`参数不截断`/`旧代际失活` 红；`同线程两条语句` 保绿——它验的是既有语义不变，本就不该受修复影响） |
| 绿（终版用例 + 保绿复跑） | 还原三处修复，清 `build/classes` + `build/kotlin` 后一次跑全 | **17 个类 237 例、0 失败 0 错误、BUILD SUCCESSFUL（2m32s）**；其中新增 `ConsoleSettleFlushTest` 5/5 |

### 实施期间的假失败留痕（已登记形态，供复跑者省一轮排查）

还原修复后首跑出现 52（`JdbcTemplateLogParserTest`）+12（`SqlLogConsoleBridgeMarkerTest`）+4（`LogThreadRouterTest`）+3（`SqlCaptureGateSniffTest`）+2（`LogParseConcurrencyTest`）例失败，报文全是 `NoSuchMethodError` 指向**旧构造器签名**（`JdbcTemplateLogParser.<init>(LogSqlRestorer, boolean, int, int, DefaultConstructorMarker)` / `LogThreadRouter.<init>(Function0, …)`）——即「Kotlin 增量编译未重编测试类」的陈旧类假失败（见记忆 `pitfall-regression-false-failure-flakes`）。清 `build/classes` + `build/kotlin` 后同命令全绿，与代码无关。

### 待验证前提的结论

1. 待验证前提 1（唯一原因）：代码层与现场证据一致（本场景两条语句分属两个线程桶、无参数行、实时链路不调 `closePending()`），红读数证明「不冲刷即零产出」；真机复验的判据已埋好（冲刷 INFO 痕）。
2. 待验证前提 2（阈值代价）：以「同线程静默 1 秒」为判据，参数行（1ms 量级）不会被截断——单测 `参数行在阈值内到达_不被冲刷截断` 双向固定（阈值内到达→随条目还原；溢出形态见 KDoc 已知代价）。
3. 待验证前提 3（平台逐行调用）：平台侧已由面板「已扫描 149 行」反证；本轮不再重复验证。


## 八、同类残留（本次不修的依据，登记待办）

- **MyBatis**：无参语句 MyBatis 仍会打空 `Parameters:` 行（`PreparedStatementLogger` 对 execute 族方法在 debug 打开时无条件打 `Parameters: `），条目在**同线程内**即闭合 → 不受本缺陷影响，不修。
- **Hibernate**：语句行之后没有绑定参数行时，条目落 `pendingShadow` 阴影槽等双副本合并，闭合点同样只在「同桶再来一行」——同缺陷类。未一并修的原因：静默冲刷在此必须先定案「零参阴影到期是否即产出」，那等于改 T-HibDualCapture 的双捕获合并语义，需要单独计划与真机数据。

## 九、变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-23 15:05 | 立项：现场取证（IDE 日志 14:47–14:48 段 + 面板读数 + 控制台片段 + javap 平台实证）+ 根因表述（按线程分桶后待闭合条目没有产出点）+ 改动清单 + 三条待验证前提。 |
| 2026-09-23 15:35 | 实施：`LogThreadRouter` / `JdbcTemplateLogParser` / `SqlLogConsoleBridge` 三处改动 + 新增 `ConsoleSettleFlushTest`（5 例）；反向验证红 3 → 红 4、清陈旧类后绿 17 类 237 例；§七 回填读数与假失败留痕。 |
| 2026-09-23 15:4x | 提交：`efe2c73`（5 文件 +553/−52：三处生产改动 + 新测试类 + 本计划）。 |
| 待办 | 真机复验（日常 IDE 2026.2 起 `SelfDevFrameworkDemoApplication` → 面板是否出现 JdbcTemplate 条目 + `idea.log` 的冲刷 INFO 痕）。 |

