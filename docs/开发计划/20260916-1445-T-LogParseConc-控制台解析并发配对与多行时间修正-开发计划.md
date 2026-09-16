# T-LogParseConc 控制台解析并发配对与多行时间口径修正——开发计划

> 立项时间：2026-09-16 14:45（会话内定案）
> 分支：`master`（worktree `mybatis-plus-sql-visualizer`）
> 需求来源（用户原话）：「非增强模式下，当控制台 SQL 较多时候，SQL 日志时间和控制台的对不上」＋「并发情况下 SQL 日志失败严重不正确」——附控制台原文日志（1200 行，12 个 `http-nio-8088-exec-*` 线程交错，含 `selectPage` / `selectPage_mpCount` 成对出现）与面板截图两张
> 前置：同一工作区尚有 T-AgentLogTime（增强通道日志时间对齐）改动未提交；本计划不依赖它，但改的是同一批解析器文件，实施时须与之一起提交或调整顺序

## 一、问题（两个症状）

**① 非增强模式、控制台行数多时，条目时间与控制台日志行对不上。**
截图实证：面板 `#6 [2026-09-16 14:19:13.596]` vs 控制台 `2026-09-16 14:19:13.387`——**差 209ms**。控制台行越多、管道越滞后，偏差越大。

**② 并发下条目严重不正确。**
面板截图中 SQL 正文被塞进整行日志：

```
SELECT id,username,password
FROM user
ORDER BY id DESC
LIMIT 2026 - 09 - 16 14 : 35 : 00.413 DEBUG 36516 [http-nio-8088-exec-9] UserMapper.selectPage [] - ==> Parameter ...
```

控制台原文显示：每条 `selectPage` 是 `==>  Preparing: … LIMIT ?` 后跟 `==> Parameters: 10(Long)`；`selectPage_mpCount` 的 `Parameters:` 行**内容为空**（`==> Parameters: ` 后无内容），且这些行在 12 个线程之间高度交错。

## 二、根因（探针实证）

用临时探针把用户控制台原文喂进真实解析器（`MyBatisLogParser` / `JdbcTemplateLogParser`），跑完即删。四条结论：

### R1（症状②主因）`MyBatisLogParser` 是**单槽状态机、无线程分桶**

同一批日志同时包含 12 个线程的语句，而该解析器只有一份 `pendingSql`/`pendingParams`。另一线程的 `Preparing:` 行到来即 `closePending()` 把本线程**尚未收到参数**的 SQL 提前闭合（参数为空），本线程稍后到达的 `Parameters:` 行因 `pendingSql == null` 被静默丢弃。

探针（纯交错，无空行）：

```
exec-9 ==>  Preparing: SELECT id FROM user WHERE name = ? AND id = ?     ← 2 个参数
exec-2 ==>  Preparing: SELECT id FROM t WHERE a = ?
exec-2 ==> Parameters: 999(Long)
exec-9 ==> Parameters: abc(String), 42(Long)
```
实测产出：exec-9 的条目 **params=[]（参数全丢、`?` 未还原）**，exec-2 的条目 params=999。这是历史反馈「有些参数没有正常识别出来」在 MyBatis 链路上的同一根因。

**对照**：`JdbcTemplateLogParser` 早在 **T-JdbcThreadBucket**（2026-09-15）就按线程 token 分桶修掉了同类问题——`MyBatisLogParser`（以及 `HibernateLogParser`）**从未跟进**，只有 JdbcTemplate 一条链路有分桶。

### R2（症状②放大器）空 `Parameters:` 行的「等下一行」兼容态**跨语句残留**

`feed()` 里 `isPrefixOnly(line, "Parameters:")`（`Parameters:` 后无内容）会置 `paramPrefixMatched = true` 进入「等待下一行作为参数文本」态。该状态：

- **只在实际消费掉一行后才复位**，期间即使穿插了别的语句行（分支 1 处理 `Preparing:` 时**不复位**它）也一直挂着；
- 判定位置在分支 4（正常 `Parameters:` 行）**之前**，所以下一行哪怕是另一线程**完整的** `Parameters: 10(Long)` 行，也会被整行吞成参数文本。

探针（用户原文序列，最典型）：

```
exec-9 ==>  Preparing: SELECT id,username,password FROM user ORDER BY id DESC LIMIT ?
exec-2 ==>  Preparing: SELECT COUNT(*) AS total FROM user
exec-2 ==> Parameters:                       ← 空行，进入等待态
exec-9 ==> Parameters: 10(Long)              ← 整行被吞成 exec-2 那条的参数文本
```
实测产出：

| 条目 | rawSql | params |
|---|---|---|
| 1 | `SELECT id,username,password … LIMIT ?` | **[]（本该是 10）** |
| 2 | `SELECT COUNT(*) AS total FROM user` | **[`2026-09-16 14:34:59.940 DEBUG 36516 [http-nio-8088-exec-9] UserMapper.selectPage [] - ==> Parameters: 10`]（整行日志）** |

另一个变体（空行之后先来另一线程的 `Preparing:` 行、再来其 `Parameters:` 行——两句都被毁）：

```
exec-2 ==>  Preparing: SELECT COUNT(*) AS total FROM user
exec-2 ==> Parameters:                                            ← 空行 → 进入「等下一行」态
exec-9 ==>  Preparing: SELECT id FROM user WHERE id = ?           ← 走分支 1 闭合上一条；等待态未复位
exec-9 ==> Parameters: 42(Long)                                   ← 整行被吞成 exec-9 那条的参数文本
```
实测产出（4 行输入只产出 2 条）：

```
[1] rawSql   = SELECT id FROM user WHERE id = ?
    restored = SELECT id FROM user WHERE id = 2026-09-16 14:34:59.941 DEBUG 36516 [exec-9] UserMapper.selectPage [] - ==> Parameters: 42
    params   = [整行日志]（type=Long）
```
→ **整行日志被当作参数值替换进了 `?`**，正是截图现象；同时该行本该承载的参数信息被毁。

MP 分页场景（`selectPage` + `selectPage_mpCount`，COUNT 查询打印空 `Parameters:` 行）正是 R1+R2 的最坏组合——用户日志里满屏都是。

### R3（症状①）多行 SQL 条目**没有日志时间**，回落到捕获时间

非增强模式的时间来源是条目 `logTimestamp`（从日志行原文解析），解析不到才回落 `entry.timestamp`（= 插件处理该行时的时刻）。而 `JdbcTemplateLogParser` 的多行收集路径（**T-JdbcMultiline**）在 `finishCollecting()` 里**只设 `pendingSql`，从不设 `pendingLogLine`**——多行条目 `logTimestamp` 恒为 null，只能回落捕获时间。控制台积压时回落值与日志行时间拉开（截图 209ms），行越多差越大。

探针（用户截图同款 Spring 多行格式）：

```
2026-09-16 14:19:13.387 [TID: …] [http-nio-…-exec-5] DEBUG …JdbcTemplate Line:643 - Executing prepared SQL statement [SELECT
    T.id AS businessId,
    …
  WHERE T.status = 1]
```
实测：多行条目 `ts=null`，而紧随其后的单行语句 `ts=14:19:13.600`（解析正常）。→ 症状①只出在多行 SQL 上，与「控制台 SQL 较多」只是相关性（多行 SQL 通常更长、更容易跨行）。

### R4 同类隐患盘点（一并纳入本次范围）

| 隐患 | 位置 | 后果 |
|---|---|---|
| Hibernate 链路同样不分桶 | `HibernateLogParser`（无线程 token 概念，`pendingLogLine` 三处设点） | 并发 Hibernate 应用同 R1 |
| MyBatis 不支持 SQL 续行 | `MyBatisLogParser.feed` 分支 6 把续行当噪声丢弃 | 多行 mapper SQL 只留首行；增强模式下还与 agent 的完整 SQL 指纹不等（T-AgentLogTime 已知残余 L5/T-LT-06） |
| 参数行落到「无 pendingSql」时静默丢弃 | `MyBatisLogParser.feed` 分支 4 / `JdbcTemplateLogParser.feedIdle` 分支 3 | 参数丢失无任何痕迹，只能靠用户肉眼发现 |
| 多行残句强制闭合无时间 | `JdbcTemplateLogParser.closeBucketPending` 的 `partial` 路径 | 同 R3 |
| 条目顺序与实际执行顺序不一致（序号跳号） | 上述串联结果 | 用户在面板看到「跳号 + SQL 错」，无法判断哪些可信 |

## 三、方案

### S1 MyBatis（及 Hibernate）接入线程分桶——主修复

把 `JdbcTemplateLogParser` 里已跑通的线程 token 提取 + 分桶状态机**下沉为共享机制**，三个解析器统一使用（避免第三次复制粘贴）：

- 抽 `LogThreadRouter`（log 包）：`tokenOf(line)`（复用现 `extractThreadToken` 的锚点/窗口/排除规则）＋ 桶表（`LinkedHashMap<token, bucket>`，空闲桶淘汰上限 `DEFAULT_MAX_BUCKETS`）＋ 无线程行的归属策略（多行收集态优先，否则回退共享默认桶，fail-open）。
- `MyBatisLogParser`：`pendingSql`/`pendingLogLine`/`pendingParams`/`prefixMatched`/`paramPrefixMatched` 全部按桶隔离；桶内状态机逻辑保持现状（改动面最小）。
- `HibernateLogParser`：同款接入（其行同样带线程段）。
- `JdbcTemplateLogParser`：改为复用共享 router（行为不变，防两份实现漂移）——建议同期做，若有回归风险可留作后续。

### S2 空 `Parameters:` 行不再吞下整行——语义修正

现状「`Parameters:` 空 remainder → 下一行即参数文本」是为兼容「参数打印在下一行」的日志配置，不能简单删除，改为**加判别**：

- 下一行若带日志行特征（行首时间戳 / 日志级别关键词 / `[线程]` 段 / `==>`、`<==` 前缀 / 自身是 `Preparing:`、`Parameters:` 行）→ 判为「该语句**无参数**」：立即 `closePending()`，并把该行按正常流程重新喂入（不能吞）；
- 其余行（无任何日志前缀的裸文本）→ 维持旧行为当作参数文本。
- 同时：该等待态**随桶隔离**（S1 顺带解决跨线程残留），并在消费后无条件复位。

参数行本身也补一条对称守卫：**参数文本不得包含日志前缀**（含时间戳/级别的行即便被当作参数文本也丢弃并计数），作为最后一道防污染。

### S3 多行 SQL 的时间补全

- `JdbcTemplateLogParser`：`finishCollecting()` 记录**开括号行（首行）**为 `pendingLogLine`；`closeBucketPending` 的多行残句强制闭合路径同样记录（取该次收集的首行）。
- `MyBatisLogParser` 若同期做续行（S4），同样以**首行**为 `pendingLogLine`。
- 口径统一写死一句话：**日志时间恒取「语句首行」的时间**。

### S4 MyBatis 多行 SQL 续行（**用户 2026-09-16 定：本期做**）

用户上一轮对 L5/T-LT-06 定「先不做，保留在计划中」，当时影响面仅限「增强模式下日志时间走回落」。本轮起多行 SQL 是症状①的直接原因之一（Spring 侧必须修），且 MyBatis 侧的多行 mapper SQL 也一直只显示首行——**用户本次明确「L5 也本期做」**，故与 T-LPC-04 同期实施。

判别式与 S2 同源：续行不含日志前缀（时间戳/级别/线程段/`==>`、`<==`），遇日志行即终止；空行终止；受 `maxParseLength` 约束。落地后 `AgentLogTimeParityTest` 里「多行 SQL 未命中（待 L5 落地）」的用例须改判为**命中**（两通道 rawSql 恢复同形）。

### S5 静默失效可见化

- 解析器内计数并在插件日志一次性留痕（每类首次 + 每 N 次累计）：`参数行无上下文丢弃`、`空 Parameters 等待态吞行`、`多行残句强制闭合`、`参数文本含日志前缀被拒`。
- 面板不做新 UI（非目标），但「参数数量与占位符数量不一致」的既有提示因此有了可查的根因链。

## 四、任务分解

| ID | 任务 | 涉及 | 验收 |
|---|---|---|---|
| T-LPC-01 | 抽 `LogThreadRouter`（token 提取 + 分桶 + 淘汰 + 无线程行归属） | 新增 log/LogThreadRouter.kt；`JdbcTemplateLogParser` 改为复用 | router 单测：三种日志形态 token 提取、超限淘汰、无线程行归属收集态桶 |
| T-LPC-02 | `MyBatisLogParser` 按桶隔离状态机（主修复） | `MyBatisLogParser` | 用户原文交错序列：两条 SQL 各带自己的参数，无 `?` 未还原、无整行日志入参 |
| T-LPC-03 | 空 `Parameters:` 行判别式（S2） | `MyBatisLogParser`（＋`HibernateLogParser` 同款判定） | 空行 + 下一行是日志行 → 空参闭合且该行不被吞（其自身条目照常产出）；空行 + 下一行是裸参数文本 → 旧行为不变 |
| T-LPC-04 | 多行条目时间补全（S3） | `JdbcTemplateLogParser`（finishCollecting / 残句闭合） | 多行条目 `logTimestamp` = 首行时间；残句路径同 |
| T-LPC-05 | 参数行无上下文/含日志前缀的守卫 + 计数留痕（S5） | 三解析器 | 静默丢弃变计数；含前缀的"参数"被拒 |
| T-LPC-06 | `HibernateLogParser` 接入分桶（S1 收尾） | `HibernateLogParser` | 并发 Hibernate 日志用例 |
| T-LPC-07 | MyBatis 多行续行（S4，**本期做**） | `MyBatisLogParser` + 测试 | 续行并入、噪声/日志前缀行不并入、`maxParseLength`/空行边界；增强模式该形态改判命中（更新 `AgentLogTimeParityTest` 的已知未命中用例） |
| T-LPC-08 | 文档三处同步 | `CHANGELOG.md`、`docs/变更记录.md`、本计划回填交付记录 | 条目落盘 |

顺序：T-LPC-01 → 02/03（一组，主修复与守卫同提交）→ 04 → 05 → 06 → 07 → 08。

## 五、测试与门禁

- 把本轮 5 个探针**转正为回归用例**（`LogParseConcurrencyTest`）：
  1. 用户原文交错序列（`selectPage`/`selectPage_mpCount` 各 1 条 + 空 Parameters 行）→ 两条 SQL 各带自己的参数；
  2. 纯交错（无空行）→ 后到线程的参数不丢；
  3. 空 Parameters 行 + 下一行是另一线程完整 `Parameters:` 行 → 空参闭合、该行不被吞、无整行日志入参；
  4. Jdbc 多行 SQL 条目 `logTimestamp` = 首行时间；
  5. Jdbc 单行条目 `logTimestamp` 正常（对照组，防修反）。
- 既有解析器族用例（`MyBatisLogParser*`、`JdbcTemplateLogParserTest`、`HibernateLogParserTest`、`DualParserIntegrationTest`、`SqlCaptureGateSniffTest`、`AgentLogTimeParityTest`）全量回归——**本次改动同时影响增强嗅探链路的条目质量**。
- 全量 `./gradlew test :sql-visualizer-agent:test`（日常门禁口径）；面板/平台类遵守既有约定。
- 并行：跑 gradle 前 `parallel_tasks.py check`，有活跃任务则登记独立沙盒。

## 六、风险与非目标

- **风险 1**：分桶改变既有配对时序，可能出现「条目产出时机后移」（同一线程的语句要等下一行或 `closePending` 才闭合）。缓解：`closePending()` 遍历全部桶（与 JdbcTemplate 现实现一致），面板刷新路径零改动。
- **风险 2**：S2 判别式若过严，会误伤「参数打印在下一行且该行恰好带 `[]` 或级别词」的形态。缓解：判别只认「行首时间戳 / 级别关键词 / `[线程]` + `==>`、`<==` 前缀」四类强特征，其余放行；并配套用例双向锁死。
- **风险 3**：S4 续行并入可能吞掉紧随 SQL 之后的业务日志行。缓解：四类终止条件 + 空行终止 + `maxParseLength`；仍不放心可砍掉 T-LPC-07（不影响 01~06 的交付）。
- **非目标**：不改面板显示与去重口径；不改增强通道协议；不引入按请求聚合的新模型（分桶粒度就到线程）。
- **非目标**：不追求「条目顺序完全等于执行顺序」（并发下控制台本身的到达序就不等于执行序），只保证**每条条目的 SQL 与参数自洽**。

## 七、真机验收清单（用户侧）

1. 用引发本次反馈的同一并发脚本压测（12 线程 MP 分页循环）；
2. 面板逐条比对：`LIMIT ?` 必须还原为 `LIMIT 10`，SQL 正文里不得再出现 `DEBUG …`/`==>` 字样；
3. 时间列（`.SSS` 格式）与控制台对应日志行逐条比对，多行 SQL 也要对得上；
4. 抽查「参数数量与占位符数量不一致」提示是否还出现（应显著减少）；
5. 插件日志中 T-LPC-05 的计数项应为 0 或极少。
