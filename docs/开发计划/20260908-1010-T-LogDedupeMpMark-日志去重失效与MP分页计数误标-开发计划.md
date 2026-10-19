# T-LogDedupeMpMark：SQL 日志去重失效与 MP 分页计数误标 开发计划

> **创建日期**：2026-09-08
> **分支**：master（当前 HEAD `0219f91`）
> **优先级**：P1（日志列表核心正确性：去重形同虚设 + 来源标签误命中，用户日常高频可见）
> **预估工时**：2~3h（含全量回归；runIde 真机验证人工跟进）
> **来源**：用户反馈——达梦方言项目（Spring JdbcTemplate + NamedParameterJdbcTemplate），开启「移除重复的sql」后列表仍大量重复；同时 [MP 分页计数] 标记出现异常命中，数据 SELECT 被标成计数查询（红框截图）。用户猜测与之前的自动拼接分隔符有关，已核实排除（§2.4）。

---

## 一、需求分析

背景流量：`cache-preheat` 启动预热线程循环跑分页查询，每页一次 count + 一次数据查询，控制台同一秒内产出大量日志。列表上两个症状：

1. **去重开启仍整屏重复**：截图里相邻条目尾部完全一致（`...b009f81a8' ) );` 连续两行同文），勾选「移除重复的sql」没有拦住；
2. **[MP 分页计数] 异常命中**：本应只标 `SELECT COUNT(*)` 计数查询的标记，落到了 `SELECT "owner", ...` 全列数据查询上。

列表上每组重复呈现「数据 SELECT、COUNT、COUNT、误标 SELECT」的固定节律，与下文根因里「一次物理执行产出两条副本、副本与相邻语句同批标记」严格对应。

## 二、现状与根因

### 2.1 捕获链路现状

控制台每行经 `processConsoleLine`（SqlLogConsoleBridge.kt:538 附近）喂三个解析器，随后 `takeEntries()` 取出**本行产生的全部条目**成批，逐条 `buffer.addEntry`（SqlLogConsoleBridge.kt:319，全插件唯一写入口）。

用户应用一次物理查询打两路日志（控制台原文为证）：

- 仓储层 TRACE：`sql = SELECT ... WHERE ("deleted" = :param1) ... , parameters = {param1=0, ...}` —— `JdbcTemplateLogParser` 分支 1 单行成条（JdbcTemplateLogParser.kt:104-123），SQL 经 `groupValues[1].trim()`；
- Spring DEBUG：`Executing prepared SQL statement [SELECT ... = ? ...]`（分支 2 存入 pendingSql，:51-55）+ StatementCreatorUtils TRACE 参数行（分支 3）—— 由**下一语句的首行**触发 `closePending()` 闭合（:73-88），SQL 只 `take(maxParseLength)`，**不 trim**。

于是每次物理执行产出两条副本：named 副本先到、prepared 副本随后闭合；每页共 4 条（count×2 + 数据×2），即截图里 COUNT 成对、SELECT 成对的来源。

### 2.2 根因一：跨格式副本仅差首尾空白，精确相等去重永不命中

去重判定键是 `restoredSql` **全文精确相等**：运行时 `isDuplicate`（SqlLogEntryBuffer.kt:124）、勾选时 `dedupeExisting`（:88）皆如此。而两条副本的还原文本恰好只差首尾空白——named 分支 trim 过，prepared 分支尾随空格原样保留（控制台原文 `... )  ]` 方括号前两个空格）。参数还原两侧一致（数值原样、字符串引号，渲染规则同源），唯独空白不对齐，`==` 永远为 false。

窗口与时间戳都不是障碍：去重窗口 60 秒，`timestamp` 取捕获时刻（`System.currentTimeMillis()`），成对副本毫秒级相邻。**障碍只在文本不等**。第二张截图相邻两行尾部同文（同参数同语句），正是这对漏网副本。

### 2.3 根因二：MP 计数标记按「批」判定，殃及同批数据查询

SqlLogConsoleBridge.kt:572-580：

```kotlin
val isCountQuery = taken.any { it.restoredSql.trimStart().startsWith("SELECT COUNT(", ignoreCase = true) }
if (isCountQuery) {
    src = SourceMarkers.mpPaginationCount()
}
val result = taken.map { e -> e.copy(sourceFile = src?.first, ...) }
```

`taken` 是**同一行喂入后**三解析器产出条目的并集。named 行触发 `closePending()` 时批内常见两条（刚闭合的 prepared 副本 + 本条 named 副本）。只要批内有一条 COUNT，`any` 命中，整批全部打上 `__MP_PAGINATION_COUNT__`——红框里 `[MP 分页计数] SELECT "owner", ...` 就是与 COUNT 副本同批入列的数据查询。

连带伤害：误标条目同时被 `isMpPaginationCount` 判成 `LogJumpTarget.NoSource`（SqlLogPanel.kt:872-874），丢失源码跳转能力。

### 2.4 排除项

- **自动拼接分隔符无关**：`withTerminator`（SqlLogPanel.kt:73-74）是显示与复制层统一追加分号，从不写回 `entry.restoredSql`，不参与去重键；
- **达梦方言无关**：log 包内无任何 dialect 引用，捕获、解析、去重链路不感知方言；
- **启动捕获回放双捕排除**：`addEntry` 全插件仅 bridge:319 一处调用，启动捕获与实时监听共用同一管线，不存在第二条写入路径。

### 2.5 附带观察（本轮不改，范围确认见 §3.4）

cache-preheat 对不同 runPlanId 反复执行同形状 SQL：参数不同，按现语义（键含还原后的参数值）**不属于重复**，设计如此、与 MybatisCodeHelper-Pro 对齐。但 summary 在 160 字符处截断（`MAX_SUMMARY_LENGTH`）叠加列表宽度裁剪，把 SQL 尾部的参数差异切出视野，视觉上整屏同文——「开了去重怎么还这么多一样的」的观感有相当一部分来自这里，而非真重复。

## 三、技术方案

### 3.1 改动点一：去重键空白规范化

新增顶层函数（放 LogSqlModels.kt，与 `withTerminator` 同风格）：

```kotlin
/** 去重判定键：trim + 连续空白折叠为单空格（跨日志格式副本仅差首尾空白的场景判重命中）。 */
internal fun dedupeKey(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")
```

接入四处，判定与展示必须同键：

- `SqlLogEntryBuffer.isDuplicate`（:124）与 `dedupeExisting`（:88）改 key 比较；
- `dedupeCounts` 的写入与快照以 key 为键（`dedupeCounts.merge(dedupeKey(entry.restoredSql), ...)`，两处）；
- 徽章查找 SqlLogPanel.kt:1307 同步改 `dedupeCounts[dedupeKey(entry.restoredSql)]`。

`restoredSql` 本体不动：历史自动保存文件、列表展示、预览、日志转 SQL round-trip 全部不受影响。

### 3.2 改动点二：MP 计数标记改逐条判定

SqlLogConsoleBridge.kt:572-580 的批级 `any` 拆到条目级：

```kotlin
val result = taken.map { e ->
    val eSrc = src ?: if (e.restoredSql.trimStart().startsWith("SELECT COUNT(", ignoreCase = true)) {
        SourceMarkers.mpPaginationCount()
    } else null
    e.copy(sourceFile = eSrc?.first, sourceLine = eSrc?.second ?: 0, executionTimeMs = ...)
}
```

`pendingSource`（插件源码拦截器注入的标记）保持批级优先不变：有真实源码标记时计数检测让位，与现状语义一致。

### 3.3 兼容与边界

- 规范化只折叠空白，不改大小写、不去分号：COUNT 前缀判定不受影响，达梦带引号标识符的大小写原样保留；
- 若 prepared 路径参数行缺失导致 `?` 残留，两副本等值失败仍会成对入列——已有参数不足警示兜底，不在本轮扩大；
- 60 秒窗口、「参数不同不合并」两条语义不变。

### 3.4 范围确认项（默认不做，留用户决策）

- **shape 级去重开关**（忽略参数值）：能治 preheat 整屏同形观感，但会把真实分页数据一并吞掉，与现有精确语义冲突，默认不提供；
- **prepared 副本入列抑制**（named 行已还原同一语句时跳过相邻 prepared 行）：每页 4 条变 2 条，更彻底，但跨行状态机改动面大；规范化去重已覆盖窗口内成对副本，收益边际小；
- **列表可见性**：参数差异被截断的观感问题，T-ClickPreview 单击预览落地后可看全文，暂不另做。

### 3.5 前置协调（多智能体铁律）

SqlLogPanel.kt 当前有 T-ClickPreview / T-LogListFreeze04 的未提交改动（git status M），徽章查找改动点（:1307）恰在该文件。实施顺序：SqlLogEntryBuffer.kt / SqlLogConsoleBridge.kt / LogSqlModels.kt 工作区干净可先动；SqlLogPanel.kt 一行改动等前序任务提交后进行，或基于其上顺延但提交必须分开。跑 gradlew 前 `python parallel_tasks.py check` 照常。

## 四、实施步骤

| # | 任务 | 位置 |
|---|------|------|
| 1 | 新增 `dedupeKey` 顶层函数 | LogSqlModels.kt |
| 2 | 判重/裁剪/计数键改规范化 | SqlLogEntryBuffer.kt:88、:124 及 dedupeCounts 写入两处 |
| 3 | MP 计数标记逐条判定 | SqlLogConsoleBridge.kt:572-580 |
| 4 | 徽章查找同步规范化键 | SqlLogPanel.kt:1307（前序任务提交后） |
| 5 | 单测扩充（§五） | 4 个测试文件 |
| 6 | 全量回归 | `./gradlew test --no-build-cache --no-configuration-cache` 基线绿 |
| 7 | runIde 真机验证（人工跟进项） | 统一走 runide-demo-verify-workflow：达梦项目控制台确认去重生效、无误标 |
| 8 | 文档三处同步（实施后） | 变更记录.md 顶插 + 本计划实施记录 + docs/MEMORY.md 变更记录行 |

## 五、测试计划

buffer / bridge / parser 均无平台依赖，纯 JUnit 覆盖：

| 测试文件 | 用例 |
|---------|------|
| `SqlLogEntryBufferTest` | 规范化判重：尾随空白差、内部连续空白差、首行缩进差均命中；不同参数不判重；60s 窗口外不判重；`dedupeExisting` 同规则裁剪；⨉N 计数按规范化键累加、快照键可被 `dedupeKey(restoredSql)` 命中 |
| `JdbcTemplateLogParserTest` | 回放用户控制台序列（named TRACE 行 → `Executing prepared SQL query` → prepared statement 行 → 2×参数行 → 下一语句 named 行）：断言产出两条副本 restoredSql 仅空白差、还原值一致；named 行触发 pending 闭合的批序 |
| `SqlLogConsoleBridgeMarkerTest` | COUNT 副本与数据 SELECT 同批：仅 COUNT 得 MP 标记、数据查询无标记；纯数据批无标记；pendingSource 存在时优先源码标记（现状语义回归） |
| `SqlLogPanelDedupeToggleTest` | 勾选去重后跨格式副本被裁掉、徽章渲染键一致 |

回归风险点：规范化键会波及 `SqlLogPanelDedupeToggleTest` 既有键口径断言；bridge 逐条标记波及 `SqlLogPanelLiveJumpWithSourceTest` 中 MP 标记相关的跳转断言，跑全量时重点看这两处。

## 六、风险与回滚

改动集中在判定键与标记赋值，条目数据本体不动，历史文件与上下游转换不受影响。分两个独立提交（去重键规范化 / MP 标记逐条化），可单独 revert。

---

## 实施记录

（2026-09-08 实施）

**并行任务影响核查**（用户提醒前置核查）：实施前 T-ClickPreview（c9d7fe2）与 T-LogListFreeze04（9223f44）已提交，工作区干净、无活跃并行任务。T-LogListFreeze04 移植的 T-Perf-19 已把**去重计数键**指纹化（`LogSqlEntry.dedupeKey` = `sqlFingerprint(restoredSql)`，徽章查找 SqlLogPanel.kt 已接 `entry.dedupeKey`），但**去重匹配仍是原文精确相等**（isDuplicate / dedupeExisting）——根因一依然存在；对方在变更记录里显式标注「isDuplicate 仍原文精确相等，T-LogDedupeMpMark 修复空间不受占用」，无冲突。bridge 批级 `any` 误标原样未动。

**方案调整**（相对 §3.1）：不新建 `dedupeKey(sql)` 顶层函数，改为把空白规范化并入既有 `sqlFingerprint` 链——新增 `normalizeSqlWhitespace`（LogSqlModels.kt，trim + 连续空白折叠），`LogSqlEntry.dedupeKey` 改 `sqlFingerprint(normalizeSqlWhitespace(restoredSql))`，计数键/徽章键自动对齐零额外接线；SqlLogPanel.kt **零改动**（避开对方刚重构的渲染管线）。isDuplicate / dedupeExisting 改按预计算 `dedupeKey` 比较（16 字符指纹串比较，兼得 T-Perf-06 临界区结构与性能）。SqlLogConsoleBridge.kt 批级 `any` 拆逐条判定，pendingSource 优先级不变。

**测试**：SqlLogEntryBufferTest +5（尾随空白/首部内部连续空白判重、空白差异副本计数并键、大小写内容不变式、dedupeExisting 空白裁剪）；JdbcTemplateLogParserTest +1（用户控制台序列全回放：双路副本还原值一致、原文仅差首尾空白）；SqlLogConsoleBridgeMarkerTest +3（COUNT 与数据同批两向序仅 COUNT 标记、纯 COUNT 批照常标记）；SqlLogPanelDedupeToggleTest +1（勾选后跨格式副本立即裁剪+徽章归并）。

**实施插曲**：① 首次定向回归中 2 个新 marker 用例失败且表现为旧批级行为——javap 证实编译产物已是新逻辑（`eSrc` 局部变量在），同字节码重跑即绿，判定为 Kotlin 增量编译陈旧类偶发（全局已知陷阱 pitfall-kotlin-incremental），处置：`clean` 后全量重编译回归；② `clean` 全量首跑 test worker 执行至 107 用例崩溃（沙盒重建偶发，xml 平台测试类被记「无法执行」），warm 重跑 BUILD SUCCESSFUL——与本次改动无关，全量以 warm 重跑结果为准。

**结果**：全量 3112/0/0/6 绿（基线 3102 + 净增 10）；runIde 真机验证（达梦项目控制台确认去重生效、无误标）留人工跟进，统一走 runide-demo-verify-workflow。
