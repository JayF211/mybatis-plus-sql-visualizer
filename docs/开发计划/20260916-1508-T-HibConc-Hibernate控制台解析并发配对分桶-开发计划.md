# T-HibConc Hibernate 控制台解析并发配对分桶——开发计划

> 立项时间：2026-09-16 15:08（会话内定案）
> 分支：`master`（worktree `mybatis-plus-sql-visualizer`）
> 前置：T-LogParseConc（20260916-1445，已交付 3874845）把线程分桶下沉为三解析器共享的 `LogThreadRouter` 并修掉 MyBatis/JdbcTemplate 两条链路的并发配对；**Hibernate 链路当时刻意没做**，本轮独立立项补齐
> 需求来源（用户原话）：「Hibernate 也有这个问题 独立立项」

## 一、问题

与 T-LogParseConc 同源：`HibernateLogParser` 也是**单槽状态机**（`collecting`/`inComment`/`multilineBuffer`/`pendingParams`/`pendingLogLine` 全在解析器实例上），并发应用里多线程的 Hibernate 日志按到达序混排时：

- 线程 A 的多行 SQL 块正在累积，线程 B 的 `Hibernate: ` 行或注释块到来 → `closePending()` 把 A 的残句提前闭合（参数未到齐）；
- 线程 B 的 `binding parameter` 行被追加进与 A 共享的 `pendingParams` → **参数跨线程错挂**（A 的 SQL 配 B 的值，或反之）；
- 线程 A 后续的绑定行因上下文已被 B 顶掉而丢失。

同时 T-JdbcThreadBucket / T-LogParseConc 已经证明的推论同样适用：Hibernate 的 `pendingParams` 是实例级共享，**任何并发交错都会污染参数归属**。

## 二、为什么上一轮没做（本轮要先解的结）

`HibernateLogParser` 多了一层 MyBatis/JdbcTemplate 没有的东西：**T-HibDualCapture 双副本合并**。`show-sql: true` 与 `org.hibernate.SQL: debug` 同时开启时，一条 SQL 在控制台出现两份（logger 副本 + `Hibernate: ` stdout 副本），参数绑定行只出现在两份之后；零参影子条目先进隔离槽 `quarantined`，同 `rawSql` 的参数版到达时取代它。

实测时序（`HibernateLogParserDualCopyTest` 字节级抓包，2026-09-10 demo）：

```
① 2026-… DEBUG … org.hibernate.SQL            :            ← logger 副本行（带线程 token，但无 SQL 文本）
②     /* select count(…) */ select                          ← 块注释起头，SQL 正文自此跨行累积（无 token）
③         from wms_dock_booking wmsdockboo0_                 ← 续行（无 token）
④ Hibernate:                                                 ← stdout 副本标志行（无 token）
⑤     /* select count(…) */ select                           ← 第二份 SQL 正文（无 token）
⑥ 2026-… TRACE … o.h.t.d.s.BasicSqlBinder      : binding parameter [1] as [VARCHAR] - [WH01]   ← 绑定行（带 token）
```

**关键**：正文行（②③⑤）与 stdout 标志行（④）**都不带日志前缀、提取不到线程 token**，而带 token 的①⑥分属「同一个线程」——朴素分桶会让正文行落进回退桶、绑定行落进线程桶，**双副本合并当场失效、参数错挂更严重**。

所以本轮必须先给 `LogThreadRouter` 增加一条**无 token 行的第二归属策略**：优先归属「正在收集的桶」，其次归属**最近一次出现线程 token 的桶**（MRU），最后才回退共享桶。Hibernate 的正文行紧跟在同线程的带 token 行之后（或就是它的多行呈现），MRU 是比「回退共享桶」准确得多的猜测；且今日单槽语义下这些行本来就全搅在一起，分桶后只会更准。

## 三、方案

### S1 `LogThreadRouter` 增加 MRU 归属策略（opt-in）

- 新增 `routeNoTokenToRecent: Boolean = false` 构造开关（MyBatis/JdbcTemplate 维持现行为，不动已验证的链路）。
- 无 token 行的归属顺序（开关打开时）：
  1. 正在收集（`isCollecting`）的桶——若 MRU 桶恰在其中优先它，否则取最早创建者（同一线程的多行块一定命中这里）；
  2. 否则 **MRU 桶**（最近一次出现 token 的桶）——覆盖「stdout 标志行」「块注释起头行」等紧随其后的无 token 行；
  3. 否则回退共享桶（无 token 历史时的 fail-open，等价今日单槽）。
- 有 token 时照旧分桶并刷新 MRU。

### S2 `HibernateLogParser` 状态按桶隔离

`Bucket`：`collecting` / `inComment` / `multilineBuffer` / `pendingParams` / `pendingLogLine`（原实例级字段全部下沉）；`closePending()` 与 `takeEntries()` 的「是否仍在收集」判定改为**全桶任一收集**。

**`quarantined` 保持解析器级（刻意不按桶）**：隔离槽的语义是「零参影子 vs 参数版」按 `rawSql` 合并，跨桶反而更稳（stdout 副本与 logger 副本在极端交错下可能分落两桶，槽在实例级仍能把它们合上）；分桶后同线程的正常时序里两者本就在同一桶，行为不变。

### S3 双副本与参数归属的守卫用例

- 双副本（logger + stdout + 绑定行）在**分桶开启**下仍合并为一条全参条目（沿用 `HibernateLogParserDualCopyTest` 的字节级时序，加并发变体）；
- 两线程各跑一条双副本查询（A/B 交错）→ 两条条目、参数各归其主、无跨线程错挂；
- 仅 stdout 副本（无 logger、无 token）→ 回退共享桶，行为与今日一致（不劣化）；
- 无 token 续行跟随 MRU：同线程块注释起头行 + 正文行落在同桶。

### S4 与 T-LPC-05 诊断口径对齐

Hibernate 链路的「上下文已丢的绑定行」静默丢弃同样计入 `LogParseDiag.paramWithoutContext`（与 MyBatis/JdbcTemplate 同口径，轮询周期一起上报）。

## 四、任务分解

| ID | 任务 | 涉及 | 验收 |
|---|---|---|---|
| T-HC-01 | `LogThreadRouter` 加 MRU 归属策略（opt-in + MRU 维护） | `LogThreadRouter` | 单测：MRU 归属、收集态优先、无历史回退桶、开关关闭时行为不变（MyBatis/JdbcTemplate 回归绿） |
| T-HC-02 | `HibernateLogParser` 状态按桶隔离（含 closePending/takeEntries 全桶口径） | `HibernateLogParser` | 既有 `HibernateLogParserTest` / `HibernateLogParserDualCopyTest` / `DualParserIntegrationTest` 全绿 |
| T-HC-03 | 并发配对守卫用例 | 新增 `HibernateLogParseConcurrencyTest` | 双副本合并仍一条全参；双线程交错参数各归其主；仅 stdout 副本回退语义不变 |
| T-HC-04 | 诊断口径对齐 | `HibernateLogParser` + `LogParseDiag` | 无上下文绑定行计数递增 |
| T-HC-05 | 文档三处同步 | `CHANGELOG.md`、`docs/变更记录.md`、本计划交付记录 | 条目落盘 |

## 五、测试与门禁

- 既有 Hibernate 三族用例（`HibernateLogParserTest` / `HibernateLogParserDualCopyTest` / `DualParserIntegrationTest`）必须零改动全绿——它们就是双副本契约的守门人。
- 全量 `./gradlew test :sql-visualizer-agent:test`。
- 并行：跑 gradle 前 `parallel_tasks.py check`。

## 六、风险与非目标

- **风险 1（MRU 误归属）**：无 token 行归属 MRU 桶是启发式；若期间别的线程打印了带 token 的行，可能归错桶。缓解：收集态优先（多行块期间的续行不去猜 MRU）；且今日单槽语义下这些行本就全混在一起，分桶只会更准。**不做**「按时间窗匹配」等更重的方案——收益不抵复杂度。
- **风险 2**：`quarantined` 实例级共享时，两个线程跑**同一条 SQL**可能互相取代影子。缓解：影子被取代的后果只是少一条零参重复条目（面板去重本就会折叠），不产生错误数据。
- **非目标**：不改双副本合并算法本身；不改 Hibernate 的参数渲染与时间口径；不改 `LogThreadRouter` 在 MyBatis/JdbcTemplate 上的既有策略。

## 七、真机验收清单（用户侧）

1. Spring Data JPA / Hibernate 项目开启 `show-sql` + `org.hibernate.SQL=DEBUG`，多线程（并发压测）跑带参查询；
2. 面板逐条比对：`?` 全部还原、值属于该 SQL（不再出现 A 的 SQL 配 B 的值）；
3. 同一 SQL 不应出现两条条目（双副本应合并为一条）；
4. 插件日志中 `LogParseDiag` 的「参数行无上下文丢弃」计数应为 0 或极少。

## 八、交付记录（2026-09-16）

实施范围：**T-HC-01~05 全部落地**（用户追加要求「其他 orm 如果也有这个问题 一起修复了」——已核实无遗漏，见下）。

| 任务 | 落地 |
|---|---|
| T-HC-01 | `LogThreadRouter` 增 opt-in 参数 `routeNoTokenToRecent`：无 token 行 → MRU 桶优先（开关打开时）→ 无 MRU 退「收集态桶」→ 再退共享桶；维护 `recent` 指针（淘汰/清空时同步失效）；MyBatis/JdbcTemplate 传默认 false（策略分支独立，既有链路零变化） |
| T-HC-02 | `HibernateLogParser` 状态全部按桶隔离（`Bucket`：collecting / inComment / multilineBuffer / pendingLogLine / pendingParams / pendingShadow）；`feed` 先路由再分派状态；`closePending()` 全桶冲刷 |
| T-HC-03 | 新增 `HibernateLogParseConcurrencyTest` 6 例：①两线程交错双副本（参数各归其主 + 双副本各合并一条）②A 块起头、B 整轮插入、A 绑定最后到（旧实现必错挂）③单线程双副本在分桶下仍合并一条全参 ④仅 stdout 副本无 token 历史回退共享桶 ⑤同 SQL 两线程各独立条目（影子槽按桶隔离）⑥无上下文绑定行计数 |
| T-HC-04 | IDLE 态绑定行改 `LogParseDiag.paramWithoutContext` 计数（与 MyBatis/JdbcTemplate 同口径，随桥轮询上报）；残留死字段 `pendingSql` 一并清理 |
| T-HC-05 | `CHANGELOG.md`、`docs/变更记录.md`、本交付记录 |

**「其他 ORM」核查结论（用户追问，已实测枚举）**：控制台**配对型**解析器全仓仅三个——`MyBatisLogParser`、`HibernateLogParser`、`JdbcTemplateLogParser`（`grep "MyBatisLogParser(|HibernateLogParser(|JdbcTemplateLogParser("` 命中的消费方只有 EP 过滤器 `SqlConsoleBridgeFilterProvider`、轮询桥 `SqlLogConsoleBridge`、粘贴转换 `LogToSqlPanel` 三处，同一批解析器实例化，自动继承本修复）。其余不构成配对链路：`LogDurationParser`/`ExecutionTimeParser` 是逐行耗时解析（无跨行状态）、`SqlLogFileReader` 读的是插件自存格式（头部正则，非控制台原文）、`LogStartupCapture` 只是启动触发（无解析）。故**无其他 ORM 需要另修**。

**实现中的设计取舍（写入代码 KDoc 与本记录）**：MRU 与「收集态优先」在「MRU=B 且 A 在收集」这一观测态上不可区分（既可能是 B 的下一条消息正文，也可能是 A 的续行）；按真实控制台的书写节奏（整条日志消息一次写出，跨消息交错远多于跨物理行交错）取 MRU。零参影子槽由实例级改为按桶，是并发下「同 SQL 两线程各产独立条目、影子不互相顶掉」的前提。

**门禁**：`./gradlew test :sql-visualizer-agent:test` 绿（4m29s；IDE 侧 347 类/4299 用例、agent 侧 9 类/40 用例，0 失败 0 错误）。既有 Hibernate 三族用例零改动全绿——它们是 T-HibDualCapture 双副本契约的守门人。
