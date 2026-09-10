# T-HibDualCapture：Hibernate 日志同语句产出双条目致参数分裂、`?` 残留（根因定位与治理）开发计划

> **创建日期**：2026-09-10
> **分支**：master（工作树另有 logrowndedupe-fix 会话在途——`SqlLogEntryBuffer`/去重口径归对方，本计划 F1 只动解析器层，互不重叠；`build.gradle.kts` 沙盒隔离行也是对方的，实施时沿用其 sandboxContainer 跑测试即可）
> **优先级**：P1（SQL 日志主链路数据完整性缺陷：同一条查询在列表里出现两条，一条参数丢失、`?` 残留并误导报警，用户点开哪条全凭运气）
> **预估工时**：F1 解析器隔离合并 3~4h（必做，第一笔提交）；F2 参数框显示 1~2h、F3 提示分流 1h（第二笔）
> **来源**：用户真机反馈（2026-09-10）三张沙盒截图——①预览参数框里日期值只剩 `01-01T00:00`（年份没了）而 SQL 里是全值；②`legacy_item` 查询值全部内联、显示「无未解析参数」；③`user` 查询两个 `?` 没替换，红字报「存在未替换的 ? 占位符：日志参数数量不匹配」，左侧又是「无未解析参数」。本文全部结论出自真实复现：把 demo 工程原样起在临时目录（`--server.port=8085`），stdout 重定向落盘后逐行喂真实解析器跑通，无推测成分
> **实施状态**：F1（解析器隔离槽合并）+ F3（string-aware 占位符口径 + 警示文案分流）已实施并提交；F2（参数框加宽/caret 钉首/tooltip 带值）已实施，改动在共享文件 `SqlPreviewPanel.kt` 上、与 T-PreviewTopOverlap / T-ExecSilentNoop 在途未提交改动同文件，留在工作树随其后提交（配套 UI 测试 `SqlPreviewPanelParamFieldTest` 同留工作树）。runIde 真机走查（双击两条列表行只应剩一条全参、参数框显示全值）留人工。

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-10 | F1+F3 实施：`HibernateLogParser` 零参闭合条目入隔离槽（同 rawSql 后继条目取代影子、不同 SQL 先入列保序、`takeEntries` 仅在无语句收集中冲刷，桥接逐行取件节奏下不提前泄出）；`placeholderPositions`（字符串字面量/行注释/块注释内 `?` 不计）落 `LogSqlRestorer.kt` 单点，`restore` 自裸 indexOf 改按位替换与计数同口径，`toRecognizedPreview`/`LogToSqlPanel`/`SqlLogPanel` 三处接入；`SqlLogPanel.openPreview` 警示文案分流（零参→引导开 TRACE 的新键 `log.preview.unresolvedNoBinding`）。测试：新增 `HibernateLogParserDualCopyTest` 7 用例（双副本合一带参/零参/仅 stdout 两语句不误并/仅 logger/绑定缺失/顺序保持/末尾冲刷），`HibernateLogParserTest` 双副本用例改判合并语义，`LogEntryPreviewRecognizerTest` 字面量问号口径反转 + 注释/转义用例，`DualParserIntegrationTest.feedDual` 收尾对齐 LogToSqlPanel 顺序；全量 3252/0/1（唯一失败 `XmlPreviewPanelTest.testXmlBindValuesOnlyInParamSection` 经 stash 验证与本次改动无关，为在途工作既有问题） |

---

## 一、现象与复现实证

### 1.1 三张截图对应的关系

三条预览都来自 `jpa-hibernate-demo`（表别名 `wmsdockboo0_`/`userentity0_` 是 Hibernate 计算别名，走 [HibernateLogParser]）。②是 HQL 字面量查询（无参数无绑定行），行为本来就正确，不展开。真正的病灶是 ①和③——它们其实是**同一条查询的两条不同条目**：用户双击列表里「参数完整」那条得到 ①，双击「零参数」那条得到 ③。

### 1.2 复现环境与控制台字节结构

demo 配置（`application.yml`）同时开了 `spring.jpa.show-sql: true` 和 `logging.level.org.hibernate.SQL: debug`，加 `format_sql`/`use_sql_comments`，绑定行 `org.hibernate.type.descriptor.sql: trace`。Spring Boot 2.6.3 + Hibernate 5.6.4。抓到的原始输出（每条查询执行一次）：

```
2026-09-10 15:26:45.936 DEBUG 66892 --- [nio-8085-exec-1] org.hibernate.SQL                        : 
    /* select ...（多行 HQL 注释）... */ select
        count(case when wmsdockboo0_.status='F' then 0 end) as col_0_0_,
        ...多行格式化 SQL，结尾 and (wmsdockboo0_.atd is not null)）
Hibernate: 
    /* select ...（与上一块逐字符相同的多行 SQL）... */
2026-09-10 15:26:45.956 TRACE 66892 --- [nio-8085-exec-1] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [WH01]
2026-09-10 15:26:45.958 TRACE 66892 --- [...] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [TIMESTAMP] - [2026-01-01T00:00]
2026-09-10 15:26:45.972 TRACE 66892 --- [...] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [TIMESTAMP] - [2026-12-31T00:00]
```

关键点：**同一条 SQL 在控制台出现两次**（logger 副本带时间戳前缀、stdout 副本以 `Hibernate: ` 独占一行开头，其后 SQL 从下一行开始），绑定行只出现一次且在两份 SQL 块之后。绑定行里的日期值**年份完整**（`2026-01-01T00:00`）。

### 1.3 喂真实解析器的复现结果

把上述控制台文件逐行按 `SqlLogConsoleBridge.processConsoleLine` 的顺序（MyBatis→Hibernate→JdbcTemplate，逐行 takeEntries）喂真实解析器，`/jpa/hib/headData` 等 6 条查询共产出 **12 个条目，每条查询恰好两条**：

| 条目 | rawSql | `?` 数 | 参数数 | recognized | 预览表现 |
|---|---|---|---|---|---|
| 偶数位（logger 副本） | 完整 SQL | 3 | **0** | false | `?` 残留 + 「数量不匹配」红字 + 「无未解析参数」= 截图③ |
| 奇数位（stdout 副本） | 同一 SQL | 3 | 3 | true，`initialValues={param1=WH01, param2=2026-01-01T00:00, param3=2026-12-31T00:00}` | SQL 填全值 = 截图① |

（完整输出存 `build/test-results/test/TEST-*.ScratchRealConsoleReproTest.xml`，2026-09-10 15:35 一轮。临时诊断测试文件已被并行会话清理，实施时按 §四 重写正式测试。）

面板层去重为什么不拦：`LogSqlEntry.dedupeKey = sqlFingerprint(normalizeSqlWhitespace(restoredSql))`，零参数条目的 restoredSql 带 `?`、参数条目是填值后的文本，指纹不同，两条都活。被「裁掉了 N 条」拦掉的是字面量查询（两副本 restoredSql 相同，第二条被去重丢弃）——这正是 ② 只剩一条的原因。

---

## 二、根因定位

### 2.1 主因：`Hibernate: ` stdout 副本把 logger 副本提前闭合，参数全部挂到了第二份上

[HibernateLogParser] 的三态机在双副本时序下的推演（每一分支都可在代码里对号）：

1. logger 副本首行是时间戳前缀行（`org.hibernate.SQL : ` 尾部为空），IDLE 态判为噪声跳过；
2. 下一行 `    /* select` 以块注释开头 → `feedIdle` 分支 3 进入 COLLECTING，注释同行未闭 → `inComment=true`；注释在 `... is not null */ select` 行闭合，`select` 起的 SQL 主体逐行进 `multilineBuffer`；
3. `Hibernate: ` 独占一行到达 → `feedCollecting` 分支 1b（`isPrefixOnly` 命中）→ **`closePending()` 此刻产出条目 A：完整 SQL + 0 参数**——绑定行根本还没来；
4. stdout 副本的多行 SQL 重新进 buffer；三条 `binding parameter` 行追加进 `pendingParams`；
5. 下一条查询的 logger 行到达 → 分支 4（有参数后遇非参数行）→ `closePending()` 产出条目 B：同一 SQL + 全部参数。

也就是说每条查询天然裂成「零参数 + 全参数」两条，`LogSqlRestorer.restore` 对零参数条目原样返回（`params.isEmpty()` 短路），`?` 原样留在 SQL 里；[LogEntryPreviewRecognizer] 里 `placeholderCount(3) != params.size(0)` → `recognized=false` → 参数行空 → 「无未解析参数」，同时 `log.preview.unresolvedWarning` 红字提示把锅甩给「日志参数数量不匹配」。用户视角就是：同一条 SQL 抓了两次，一次丢参数一次正常，点错那条还挨一个误导性警告。

这是配置组合（show-sql + SQL logger DEBUG，双路打印）触发的设计盲区，不是用户的日志配错——双路打印在生产里极常见，解析器必须健壮。

### 2.2 截图①的「年份丢了」：数据没丢，是输入框太窄显示不下

复现输出证明 `initialValues` 里是全值（`2026-01-01T00:00`，16 字符），SQL 回填也是全值。但 [SqlPreviewPanel.buildParamRow]（`:2052`）建的是 `JBTextField(8)`——**8 列宽**，水平放不下 16 字符，Swing 窄视口下文本区滚动到哪一段取决于光标/视口位置，截图里恰好露出的是尾部 11 字符（`2026-01-01T00:00` 去掉 `2026-` 正好是 `01-01T00:00`，第三个参数同理）。tooltip 也只写 `#paramN` 不写值，用户没有任何途径看到全值，自然判定「值没填对」。

### 2.3 次因（顺带记录，不阻塞）：`?` 计数不避字符串字面量

[LogEntryPreviewRecognizer.toRecognizedPreview] 用 `rawSql.count { it == '?' }` 数占位符，SQL 字符串/注释里的 `?`（含 PG 的 `?|` 操作符）会被多算，造成假性「数量不匹配」。本次截图未踩中，但同属「数量不匹配」误报面，宜一并收口（string-aware 计数，复用 [JdbcTemplateLogParser.scanBrackets] 的单引号跳过思路）。

---

## 三、修复方案

### F1 解析器层：零参数闭合条目进「隔离槽」，同 SQL 后继条目到达即合并（必做）

改动点收敛在 [HibernateLogParser] 单类：

- 新增 `private var quarantined: LogSqlEntry? = null`；
- `closePending()` 产出条目 E 时：若 `quarantined != null && quarantined.rawSql == E.rawSql` → 丢弃 quarantined（它是同一语句的零参数 logger 副本，E 是带参 stdout 副本或后来的完整副本），只收 E；若 E 自身 `params.isEmpty()` → **不直接入列，放进 quarantined**（它可能是将被 stdout 副本覆盖的影子），否则正常入列并清空 quarantined；
- `takeEntries()`（面板刷新/桥接取件）与外部 `closePending()` 触达时先把 quarantined 冲刷进 entries，保证流末尾不丢条目。

各配置组合下的行为推演（实施时逐条落断言）：

| 配置组合 | 现行为 | F1 后 |
|---|---|---|
| show-sql + SQL logger DEBUG（本次病例） | 每查询两条（0 参 + 全参） | 每查询一条全参 |
| 仅 show-sql（logger 关） | 字面量查询两条（去重拦一条）| 不变语义（隔离槽按 rawSql 相等合并，仍一条） |
| 仅 logger DEBUG + 绑定 TRACE | 单条全参 | 不变 |
| logger DEBUG、绑定行缺失（TRACE 未开） | 单条 0 参 + 警告 | 不变（数据真没有，警告是正当的） |

并发交错（多线程请求两查询的块互相穿插）只破坏「相邻性」假设的最坏情形：退化为现行为（多一条零参数行），不产生数据丢失——隔离槽按 rawSql 全等匹配，不同 SQL 永不误并。

刻意不做的事：不去解析 `show-sql` 配置、不试图在 `Hibernate: ` 行到来时预判「后面还有副本」（不可知）；也不动 [SqlLogEntryBuffer] 的去重与行号账目（logrowndedupe-fix 会话的辖区，F1 在更上游把双份消灭，等于替它减负）。

### F2 预览参数框：加宽 + 全值 tooltip + 光标钉头部（截图①根治）

[buildParamRow] 三处小改：

- 列宽 8 → 自适应下限 16（或沿用 `labelColumnWidth` 的视口预算思路给输入框也留足宽）；
- `text` 赋值后 `caretPosition = 0`，视口钉在头部（首字符可见），杜绝「看到尾巴当缺值」；
- tooltip 由 `#paramN` 扩为 `#paramN = <当前完整值>`，值变更时同步（`onParamChanged` 里 update）。

### F3 警示文案分流 + 占位符 string-aware 计数

- `params.isEmpty()` 与 `params.size != placeholderCount` 分开给文案：前者提示「未捕获到参数绑定日志（Hibernate 需 TRACE 级 `org.hibernate.type.descriptor.sql`）」，把用户引向正确的日志配置，而不是笼统的「数量不匹配」；
- `?` 计数换 string-aware 版本（跳过单引号字面量与注释），消除 2.3 的假性误报。

### 不修的边界

- `null` 参数预填空串、回填显示 `?` 与还原 `NULL` 不一致——既有计划已留档的边界，本次不扩；
- 手改 SQL 后参数框与 SQL 不再严格对应——既有语义，不动。

---

## 四、测试计划（测试先行）

新建 `HibernateLogParserDualCopyTest`（纯单元，喂行风格对齐 [HibernateLogParserTest]）：

1. §1.2 时序按行喂入 → 断言**一个条目**、3 参数、restoredSql 含 `'2026-01-01T00:00'`；
2. 仅 show-sql 两连发字面量查询 → 两条都在（隔离槽冲刷正确，不误并不同 SQL）；
3. logger-only 带绑定 → 一条全参；
4. 绑定缺失 → 一条 0 参；
5. 流末尾（无后续语句）→ `takeEntries()` 冲刷隔离槽，末条不丢；
6. 并发交错最坏情形 → 允许多一条，断言不丢参数条目、不误并不同 SQL；
7. string-aware 计数：`WHERE note = '什么?' AND id = ?` + 1 参数 → recognized=true。

UI 侧（F2）：参数框断言测试对齐既有 `SqlPreviewPanelImportTest` 风格，断言 tooltip 拼装与 `caretPosition==0`；列宽策略若走视口预算需布局断言。

全量回归 + runIde 真机双击两条列表行验收（只应剩一条，且参数框显示全值）。

## 五、风险与协调

- **共享工作树**：logrowndedupe-fix 在途（`SqlLogEntryBuffer`/`build.gradle.kts` 沙盒行），F1 不碰其文件；跑测试沿用其 sandboxContainer 登记，开跑前照例 `parallel_tasks.py check`。
- **demo 工程工作树有未提交半成品**（`JpaQueryController.criteriaTuple` 编译不过，jpa-demo-fix 会话正在改）：复现/验证一律走 `git archive` 导出副本，禁止动用户工作树。
- F1 改的是解析器产出语义，[LogToSqlPanel] 日志转 SQL 的 round-trip（`toLogText` 单语句渲染→重解析）不经过双副本时序，不受影响；`SqlLogCaptureRealtimeScenarioTest` 等既有场景测试需全量过一遍确认无口径漂移。

## 六、开放问题

1. 隔离槽容量只留 1 条（相邻合并）够用；若真机出现「logger 副本与 stdout 副本之间穿插大量噪声」导致合并失效的实锤，再评估扩成小环形队列（当前无证据，不做）。
2. MyBatis 解析器是否也存在同族双副本形态（logback 同时输出 SQL 到两处）——本次未观测到，立项备忘：真机遇 MyBatis 重复条目再套用隔离槽方案。
