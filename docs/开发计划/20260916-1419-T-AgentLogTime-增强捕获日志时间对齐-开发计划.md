# T-AgentLogTime 增强捕获日志时间与控制台对齐——开发计划

> 立项时间：2026-09-16 14:19（会话内定案）
> 分支：`master`（M56 增强捕获全链已合流，worktree `mybatis-plus-sql-visualizer`；不回到 `feature/t-m56-01-agent-base`——该分支内容已被 master 覆盖）
> 前置：T-AgentDialectLoop（0422 计划）建立了「控制台日志时间相关器」；本轮用户实测**日志时间与控制台实际日志行对不上**
> 需求来源（用户原话）：「增强捕获应该也捕获日志时间，目前增强捕获捕获到的日志时间和控制台实际日志时间没有对上」

## 一、问题

增强捕获条目在面板上显示的时间，与同一条 SQL 在控制台日志行里的时间不一致。

两条显示路径（`LogSqlPanel.formatTimestampFragment`）：

1. `entry.logTimestamp`（日志时间，来自控制台日志行）——**默认时间源**；
2. 回落 `entry.timestamp`（agent 采集时刻）。

增强通道的 `logTimestamp` 不是采集来的，而是**派生**的：控制台链在接管期间只嗅探不产出，把「指纹 → 日志行时间」暂存进 `LogTimeCorrelator`，agent 事件入队前按同一指纹取回回填（`SqlLogConsoleBridge.kt:732` 嗅探、`AgentManager.kt:379` 回填）。取不回就回落 `entry.timestamp`，而后者是 **execute 执行完成时刻**（`StatementHandler.java:122` 位于 `finally` 内的 `emitEvent`）——回落即「比日志行晚一个 SQL 耗时」。

## 二、根因（实证）

用一个临时探针走真实链路（`processConsoleLine` 嗅探态 → `LogTimeCorrelator` → `AgentEventAdapter.toEntry` → `take(dedupeKey)`），对照同批日志行在非接管态产出的控制台条目：

| # | 形态 | 回填 | 证据（两侧还原 SQL） |
|---|---|---|---|
| ① | 无参 SQL，PREPARING 行后无后续语句 | 未命中 | 控制台侧连条目都没闭合（`closePending` 只在「下一条语句到来/面板刷新」时触发），相关器存量 0 |
| ② | String + Long 参数 | 命中 | 时间精确一致 |
| ③ | 布尔参数 | 未命中 | agent `WHERE flag = 'true'` vs 控制台 `WHERE flag = true` |
| ④ | Timestamp 参数 | 命中 | 时间精确一致 |
| ⑤ | 参数个数不匹配（agent 少捕一个） | 未命中 | agent 保持 `?` 不还原（严格守卫），控制台尽力还原成 `a = 1 AND b = 2` |
| ⑥ | 字符串字面量含 `?` | 命中 | — |
| ⑦ | 多行 SQL（控制台按物理行分行） | 未命中 | 控制台 `SELECT id, name`（**只有首行**）vs agent 完整三行 |
| ⑧ | 块注释内含 `?` | 未命中 | 控制台跳过注释内 `?`，agent 计数把它算进去 → 守卫触发不还原 |
| ⑨ | null 参数 | 命中 | — |
| ⑩ | 无参 SQL，后跟另一条语句 | 命中 | 但真机时序是先 take 后 put（见下） |

3 个根因，互相叠加：

**R1 相关器的键要求两侧还原 SQL 逐字符相等，而两侧是两套独立渲染器。** 键是 `sqlFingerprint(normalizeSqlWhitespace(restoredSql))`：控制台侧由 `LogSqlRestorer` 还原，agent 侧由 `AgentEventAdapter.restore` 还原。两者口径分叉即失配——布尔（③：`LogSqlRestorer.PLAIN_TYPES` 收 Boolean 直出，agent 的 `isNumericType` 不含 Boolean 而加引号）、占位符计数（⑧：控制台 `placeholderPositions` 跳过 `--`、`/* */` 注释内的 `?`，agent 的 `countPlaceholders` 不跳）、参数个数守卫（⑤：`AgentEventAdapter.restore` 要求「占位符数 == 参数数」才还原，否则整句不还原；`LogSqlRestorer` 是尽力还原）。⑧⑤ 又叠加在「一侧还原、另一侧不还原」上，必然不等。

**R2 两侧发现「同指纹」这件事从未真验过。** `AgentEventAdapterTest.dedupeKey与桥通道一致_同一SQL同指纹` 是**手写**一条 restoredSql 与控制台侧字面量比对，等于自证；没有任何用例真的把 `Preparing/Parameters` 两行喂进控制台解析链、再与 agent 适配器比键。这正是本项目反复栽过的「两侧各自绿、集成不通」。

**R3 回落的那个值本身语义就不对。** `event.timestamp` 取在 execute **之后**（0422 计划里就写着「语义=捕获时间（execute 完成时刻）」），而控制台日志行打印在 execute **之前**。所以 ①②③⑤⑦⑧ 任一失配，用户看到的就是「晚一个执行耗时」的时间——毫秒级快查看不出，慢查/首查就是秒级偏差，正是「对不上」。

**附带发现（非本问题，但同源）**：⑦ 说明纯控制台模式下**多行 SQL 也只保留首行**（`MyBatisLogParser.feed` 分支 6 把续行当噪声丢弃），这是既有显示缺陷，只是被增强通道「agent 有完整 SQL」的对比暴露出来。

## 三、方案（分层，L1 单独就能消症状）

### L1 采集时刻前移到 execute 前（必修，最小改动，直接消症状）

`executeWithCapture` 里 `startNanos` 同点多取一次墙钟，随 `durationNanos` 一起传给 `emitEvent`，`event.timestamp` 用它。回落的「捕获时间」于是**天然≈控制台 PREPARING 行时间**（打印在 execute 调用前，差在毫秒内），R3 消除、失配基本不可见。

- 语义收口：面板「时间源=捕获时间」的含义变为「语句开始执行时刻」——更准，且与耗时字段（执行区间）自洽；设置说明/变更记录文案随之更新。
- 去重窗口副作用（须验）：`SqlLogEntryBuffer.kt:199` 用 `now - entry.timestamp < dedupeWindowMs`（默认 60s）判重，时间前移使差值略增 → 极端长查询（≥60s）会漏判重复。默认档位可忽略，测试里加一条边界断言即可。
- 无参 SQL（①/⑩）的「日志时间来得比事件晚」不再需要单独处理：L1 让回落值就够准。**这决定了本轮不改 `MyBatisLogParser` 的闭合时机**。

### L2 相关器键口径改造（核心：让日志时间真的取得到）

不再拿「还原后的 SQL」当键（那要求两套渲染器逐字符一致），改用**两侧同源的原材料**：

- 主键 `K1 = sqlFingerprint(normalizeSqlWhitespace(rawSql))`——控制台侧 = PREPARING 行的 SQL 原文，agent 侧 = `prepareStatement` 收到的原文；都不经过参数还原，绕开 R1 的全部三类分叉（③⑤⑧ 从此不再影响命中）。
- 次级判别 `K2 = 参数值序列`（`params.map { it.value }`）——仅用于同一条 SQL 多次执行时挑对那条，**不做强制相等断言**。
- 结构：`LinkedHashMap<String, ArrayDeque<Long>>`，同键**排队**而非覆盖（现状 `put` 先 `remove` 再放，同键连续执行时先来的时间被顶掉）；`take` 先在同键队列里找首个 K2 相等项，找不到退回队首；出队时丢弃超出时间窗（建议 30s）的陈旧项，防跨运行错配。**实施修正**：陈旧判定用**入册时刻**（put 时记本机时钟）而非日志行时间——应用 JVM 时区/时钟与 IDE 不一致时日志行时间整体偏移，旧判法会把全部条目误判陈旧、日志时间静默全失（见 §八 交付记录）。
- 不做的事：`LogSqlEntry.dedupeKey` 语义**不动**（面板去重口径与桥通道一致，T-M56-02 既定）；也不改 agent 侧的严格还原守卫（它对**显示**正确性有价值——少捕一个参数时整体不还原，好过把后续参数错位前移；显示的 restoredSql 与关联用的键就此分开）。

### L3 嗅探与实际显示解耦（语义归位）

`sniffLogTime` 现状 = 「时间显示开 且 时间源=日志」（`SqlLogManager.kt:512`、`SqlConsoleBridgeFilterProvider.kt:68`）。日志时间是**数据**，不该由显示偏好开关决定采不采；用户选「捕获时间」时相关器不填，之后切回「日志时间」历史条目一片空白。改为：增强激活（`SqlCaptureGate.paused`）即嗅探，显示开关只管渲染。成本沿用 0422 计划核算（每行 3 次 contains 门控 + 每条 SQL 一次条目构造与指纹，条目即弃），可忽略。

### L4 残余失配可见（诊断）

相关器 miss 计数 + 首次若干次 miss 打印（键、rawSql 前 80 字符）到插件日志。当前失配**全静默**，真机只能靠猜——本轮排障就吃过这个亏。多行 SQL 这类只能靠 L5 解决的失配，靠它一眼定位。

### L5 多行 SQL 续行（P1，独立可回滚，含 R2 之外的价值）

`MyBatisLogParser` 支持 SQL 续行：PREPARING 行之后、出现 Parameters/新 PREPARING 前，若行内既无日志时间前缀也无日志级别关键词（`DEBUG/INFO/WARN/ERROR/TRACE`）、非 `<==`/`==>` 开头，则并进当前 SQL（受 `maxParseLength` 约束，遇空行终止）。收益面超出本问题（纯控制台模式也终于能显示完整多行 SQL）；风险面同样最大（误并入业务日志行），故单列任务、可独立回滚。

## 四、任务分解

| ID | 任务 | 涉及 | 验收 |
|---|---|---|---|
| T-LT-01 | agent 采集时刻前移（L1） | `StatementHandler.java`、`SqlEvent` 注释 | `EventBuilderTest`/`ConnectWeaveEndToEndTest` 断言事件时刻落在 execute 前后界内 |
| T-LT-02 | 相关器键口径与结构改造（L2） | `LogTimeCorrelator`、`SqlLogModels`（键函数）、`SqlLogConsoleBridge`（嗅探 put）、`AgentManager`（take 回填） | `LogTimeCorrelatorTest`：排队/时间窗/次级判别优先/容量/清空 |
| T-LT-03 | 跨通道端到端守卫（R2，把探针转正） | 新增 `AgentLogTimeParityTest` | ②③④⑤⑥⑧⑨ 形态全命中且时间等于日志行时间；①⑦ 按既定语义记录（回落值=execute 开始时刻） |
| T-LT-04 | 嗅探与显示开关解耦（L3） | `SqlLogManager`(ParserConfig)、`SqlConsoleBridgeFilterProvider` | 时间源=捕获时仍写相关器；时间显示关时仍写相关器且不渲染 |
| T-LT-05 | miss 计数与首 N 次打印（L4） | `LogTimeCorrelator`、`AgentManager` | 单测断言计数与幂等；真机日志可读 |
| T-LT-06 | 多行 SQL 续行（L5，**用户 2026-09-16 定「先不做，保留在册」**） | `MyBatisLogParser` + 解析器族测试 | 续行并入、噪声行不并入、`maxParseLength` 与空行边界 |
| T-LT-07 | 文档三处同步 | `CHANGELOG.md`、`docs/变更记录.md`、设置说明（「捕获时间」=执行开始时刻） | 条目落盘 + 本计划回填交付记录 |

顺序：T-LT-01 先行（独立可用、单提交）；T-LT-02/03 一组（改造与守卫同提交，避免出现「改完无守卫」的中间态）；T-LT-04/05 紧随；T-LT-06 视验收情况单独决策。

## 五、测试与门禁

- 主项目全量 `test` + `:sql-visualizer-agent:test`（日常门禁口径；`verifyPlugin`/`buildPlugin` 仅发版前）。
- 三族守卫照旧（bundle parity、plugin.xml 结构、禁用 API 扫描）；新增守卫随既有族入册。
- 面板/平台类用例遵守既有约定（JUnit3 `test` 前缀、clear+dispose）。
- 测试先行：先落 T-LT-03 的 ②③⑤⑦⑧ 形态（当前必红），再动 T-LT-02。
- 并行：跑 `./gradlew` 前 `parallel_tasks.py check`，有活跃任务则登记独立沙盒。

## 六、风险与非目标

- **风险 1（L2）**：键从 restoredSql 换成 rawSql 后，同一条 SQL 不同参数会共用主键——靠 K2 次级判别 + 队首兜底；最坏情形是拿到同键相邻一次的时间（毫秒~秒级），且 L1 已保证回落值本身够准，风险收敛为「装饰性误差」。0422 计划对此已定「宁缺不错」，本轮改为「先准确、后退化、绝不留空」。
- **风险 2（L5）**：续行启发式可能吞掉紧随 SQL 之后的业务日志行。缓解：日志时间前缀/级别关键词/`<==`/`==>` 四类终止条件 + 空行终止；仍不放心就砍掉此任务（不影响 L1~L4 的交付）。
- **非目标**：不改控制台通道的显示与去重口径；不改 agent 协议（不新增字段——`ts` 语义调整即可，IDE 侧无需感知）；不追求「严格等于日志行时间」（L1 的毫秒级近似已满足「对得上」）。
- **非目标**：无参 SQL 的日志时间「来得比事件晚」不单独治（L1 已覆盖其可见症状）。

## 七、真机验收清单（用户侧）

1. 增强捕获开启 + 时间显示开 + 时间源=日志（默认档）；
2. 各跑一条：无参查询、带布尔参数查询、多行 SQL 查询、慢查询（>1s）、同一 SQL 连续执行三次；
3. 比对面板时间与控制台对应日志行时间：档位到秒时应当一致；带 `.SSS` 格式时应只差毫秒级；
4. 顺带看 `<项目>.log` 里的 miss 计数——若仍非零，键与 rawSql 片段会直接指出是哪种失配（多行 SQL 未续行 / 驱动改写 SQL）。

## 八、交付记录（2026-09-16）

实施范围：**T-LT-01~05 + T-LT-07 全部落地；T-LT-06（L5 多行 SQL 续行）经用户明确「先不做，但保留在计划中」暂缓**。

| 任务 | 落地 |
|---|---|
| T-LT-01 | `StatementHandler.executeWithCapture` 与 `nanoTime` 同点取 `System.currentTimeMillis()` 并透传 `emitEvent`；`SqlEvent.timestamp` 注释改「execute 开始那一刻」；新增 `StatementEventTimeTest`（慢语句 80ms，断言采集时刻落在开始处、耗时仍真实计入） |
| T-LT-02 | `LogSqlModels` 新增 `logTimeKey`（占位符版 rawSql 指纹）/`logTimeParamSignature`/`stampLogTime`（回填唯一入口）；`LogTimeCorrelator` 改 `LinkedHashMap<键, ArrayDeque<Item>>` + 同键 FIFO + 签名优先配对 + 30s 时间窗 + 512 键/单键 8 条上限 + `missCount`；嗅探 put 与 agent 回填两侧换键 |
| T-LT-03 | 新增 `AgentLogTimeParityTest`（12 例，真实控制台链 + 真实适配器 + 真实回填入口）；`AgentEventAdapterTest` 那条自证式「同指纹」用例加注并指向本守卫；`SqlCaptureGateSniffTest` 键口径同步 |
| T-LT-04 | 删 `ParserConfig.sniffLogTime` 及 `BridgeLineContext.sniffLogTime`、两处派生（`SqlLogManager.parserConfig` / `SqlConsoleBridgeFilterProvider`）、桥的同步点；接管态无条件嗅探；新增平台守卫 `AgentSniffDecoupleTest`（显示关 / 时间源=捕获 时仍采集） |
| T-LT-05 | `AgentSession.logTimeMiss`：前 3 条打印键+参数签名+SQL 片段，其后每 200 条打累计（含相关器存量）；`LogTimeCorrelator.missCount()` |
| T-LT-07 | `CHANGELOG.md`（[Unreleased] Fixed + Changed 各一条）、`docs/变更记录.md` 新增行、中英文 bundle 的「捕获时间」说明改为「语句开始执行时刻」 |

实施中的两处踩坑（已在变更记录留痕）：① 相关器 30s 时间窗把守卫用例里写死的历史日志时间当陈旧项丢掉 → 夹具改用当前时刻生成；并把陈旧判定从「日志行时间比墙钟」改为「**入册时刻**比墙钟」（put 时记本机时钟）——应用 JVM 时区/时钟与 IDE 不一致时日志行时间整体偏移，旧判法会把全部条目误判陈旧、日志时间静默全失；② `compileKotlin` 报 UP-TO-DATE 而测试编译仍解析旧签名 → 清 `build/classes`+`build/kotlin` 后正常（陈旧产物劫持老问题）。

**门禁**：`./gradlew test :sql-visualizer-agent:test` 绿（5m55s；IDE 侧 344 类/4275 用例、agent 侧 9 类/40 用例，0 失败 0 错误）。

L5 未做的后果已被 L1 抵消：多行 SQL 的日志时间走回落，回落值＝执行开始时刻（与日志行毫秒级之差），用户可见的「对不上」不再出现。

