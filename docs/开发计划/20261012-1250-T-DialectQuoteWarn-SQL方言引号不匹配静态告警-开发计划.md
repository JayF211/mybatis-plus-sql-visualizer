# T-DialectQuoteWarn：标识符引号与方言不匹配的静态告警 —— 开发计划

> 立项 2026-10-12。来源：录制 A11 时发现 demo 的 `@TableName("\"user\"")` 让 MySQL EXPLAIN 报
> `[42000][1064] … near '"user" t' at line 11`，用户问「能不能通过优化插件解决」。
> 结论见下「背景」，**不做 SQL 改写，只做告警**。

## 一、背景与结论（三重取证）

demo 的 `UserDO` 写成 `@TableName("\"user\"")`（达梦风格双引号；同模块另有 `schema-dm.sql`
用 `CREATE TABLE "user"`，MySQL 版 `schema.sql` 用反引号）。插件渲染出 `FROM "user" t`，
MySQL 默认把 `"` 当字符串字面量 → 语法错。三处独立取证一致：

| 取证 | 结论 | 出处 |
|---|---|---|
| MP 核心 3.5.17（sources jar） | `@TableName` 的值经 `initTableName` 只做 dbConfig 变换（驼峰/大小写），`tableFormat` 默认 `%s` 不加引号，`setTableName` 原样存 | `metadata/TableInfoHelper.java` 第 234 / 239 / 259 / 271 / 279 行 |
| MPJ 1.5.8（sources jar） | `append(wrapper.getTableName())`，值取自 MP 的 `tableInfo.getTableName()`，同样原样拼 | `extension/apt/AptAbstractWrapper.java` |
| 实测 | 插件渲染的 `FROM "user" t` 交 MySQL 报 1064 | 本轮 A11 录制现场 |

**即：运行时 MyBatis-Plus 发的也是 `FROM "user"`，demo 这条查询在真机上本来就跑不通，插件只是如实画了出来。**

**因此本任务明确不改写 SQL**：把引号改写成反引号会让预览偏离真实执行的 SQL——而「看清真正执行的
SQL」是本插件不可让渡的承诺；更糟的是会把用户代码里的真 bug 静默抹平（上线才炸）。
**做的是**：把这种情况变成预览里一条显式告警，让它**可见**而不是等 EXPLAIN 报一个看不懂的 1064。

## 二、目标与非目标

**目标**
1. 新增一条静态规则 `dialect_quote_mismatch`：SQL 里出现「引号字符不在当前方言的标识符引号集合内」时给出告警。
2. 告警出现在预览面板既有的静态规则区（与「LIKE 前导通配符」「ORDER BY 无 LIMIT」同一处）。
3. 顺带流到 MCP 的 `sqlv_perf_advice`（agent 也能拿到）——这是走规则引擎而不是在渲染层判的主要理由。

**非目标**
- 不改写/规范化 SQL 文本（理由见上）。
- 不碰 demo 仓库的 `@TableName`（跨库取舍由用户定夺：反引号会让达梦变体失效）。
- 不改 `SqlGenerator.renderTableName` 的既有行为。

## 三、现状落点

| 落点 | 位置 | 说明 |
|---|---|---|
| 规则接口 | `src/main/kotlin/.../sql/perf/PerfRule.kt` | `ruleId` + `check(sql, ast)`；**当前拿不到方言** |
| 规则严重度 | `.../sql/perf/PerfWarning.kt` | `PerfSeverity{ERROR, WARN, INFO}` 已有 ERROR |
| 引擎 | `.../sql/perf/PerfRuleEngine.kt` | `register/registerAll` + `apply(sql, ast)`；单规则 try-catch 隔离 |
| 规则实现 | `.../sql/perf/rules/PerfRulesBatch{1,2,3}.kt` | 三批共 20 条；Batch1 是正则规则、Batch2 含 AST 规则 |
| 注册与入口 | `.../sql/SqlPerfAnalyzer.kt` | `init` 注册三批；`analyze(sql)` 带 **SQL 文本缓存**（LRU 200） |
| 调用点 | `mcp/SqlVisualizerPerfAdviceTool.kt:60`、`mcp/SqlVisualizerMapperCheckTool.kt:132`、`ui/SqlPreviewService.kt:1273` | 三处都拿得到项目/方言 |
| 方言引号 | `.../sql/Dialect.kt` | 反引号：MySql(373) / MariaDb(594) / OceanBase(713)；双引号：PostgreSQL(397) / Oracle(434) / H2(528) / Sqlite(566) / Dm(625) / KingBase(667) / OpenGauss(754)；方括号：SqlServer(464)；**基类 `Dialect`(40) 不引用（原样返回）** |
| 文案 | `src/main/resources/messages/MpSqlVisualizerBundle{,_zh}.properties` | 键形如 `perf.order_by_without_limit` |
| 既有测试 | `src/test/kotlin/.../sql/perf/PerfRulesBatch1Test.kt`、`PerfRulesBatch2AstTest.kt`、`sql/SqlPerfAnalyzerTest.kt` | 照此风格补测 |

## 四、方案（四处改动）

**1) 引擎接方言（最小爆炸半径）**
- `PerfRuleEngine.apply(sql, ast, dialect: Dialect? = null)`——加**带默认值的第三参**，既有调用点不改也能编译。
- 新增可选接口 `DialectAwarePerfRule`（同文件或 `PerfRule.kt`）：`fun checkWithDialect(sql, ast, dialect): List<PerfWarning>`。
  引擎内 `if (rule is DialectAwarePerfRule) rule.checkWithDialect(...) else rule.check(sql, ast)`。
  **为什么用子接口而不是改 `PerfRule.check` 签名**：现有 20 条规则与两批测试一行都不用动，风险面小。
- 三个调用点补传方言（`SqlPreviewService` 已有 `currentDialect()`；两个 MCP 工具从 `projectPath` 解析）。

**2) 新规则** `rules/PerfRulesBatch4.kt`（新文件，不碰既有三批）
- `ruleId = "dialect_quote_mismatch"`，`severity = ERROR`。
- 判据：SQL 里出现「**已知标识符引号字符**（反引号 / 双引号 / 方括号）包裹的标识符，而该字符不在当前方言的**可接受引号集合**内」→ 告警。
- 各方言可接受集合（**按实际宽容度定，宁可不报也不误报**）：

  | 方言 | 可接受 | 依据 |
  |---|---|---|
  | MySQL / MariaDB / OceanBase | `{反引号}` | 双引号仅在 `ANSI_QUOTES` 下是标识符引号，默认不是 |
  | PostgreSQL / Oracle / DM / KingBase / OpenGauss | `{双引号}` | 反引号不是标识符引号 |
  | SQL Server | `{方括号, 双引号}` | `QUOTED_IDENTIFIER` **默认 ON**，双引号合法——**不能把双引号判错** |
  | SQLite / H2 | `{双引号, 反引号, 方括号}` | 两者都收多种引号 |
  | 基类 `Dialect`（兜底） | **不判** | 无方言上下文时宁可不报 |
- 引号字符来源：给 `Dialect` 加 `open val identifierQuotes: Set<Char>`（默认空集=不判），11 个方言类各覆盖一行。
  **不要**用 `quoteIdentifier("x").first()` 反推——基类返回不加引号的原文，反推会得到字母 `x`。
- 实现建议：优先走 AST（`Table.getName()` / 列名保留原始引号时最稳，天然不误伤字符串字面量里的引号）；
  AST 为 null 时降级到正则（须先剥离单引号字符串字面量）。**两种路径都要有测试**。
- 文案：`perf.dialect_quote_mismatch`，中英各一条，内容要让 **agent 也能读懂**（MCP 会原样返回），
  例如「标识符用了双引号，当前方言 MySQL 需反引号（`` `user` ``）——该语句在真实库上会报语法错；
  这是语句本身的写法问题，插件不会替你改写」。

**3) 缓存键带方言（**必须做，否则串味**）**
`SqlPerfAnalyzer.analyze(sql, dialect)` 的缓存键改为 `sql + '\u0000' + (dialect?.name ?: "")`。
不做的话：先分析 MySQL 下的 `FROM "user"`（有告警）再分析 PG 下的同一条（应无告警）会命中旧缓存返回错结果。

**4) 注册与文案**
`SqlPerfAnalyzer.init` 增 `PerfRuleEngine.registerAll(PerfRulesBatch4.all)`；两个 bundle 加键。

## 五、待验证前提（实施者先证伪再动手）

1. **JSqlParser 是否保留原始引号**：`Table.getName()` 对 `FROM "user" t` 返回的是 `"user"` 还是 `user`？
   若不保留 → AST 路径拿不到引号，规则只能走正则。**先写一个 5 行单测确认**，再决定实现路径。
2. **三个调用点是否都拿得到方言**：`SqlPreviewService` 有 `currentDialect()`（第 1460 行）✓；
   两个 MCP 工具需确认能从 `projectPath` 解析出项目并取方言，**取不到时传 null（规则自动不判）**，不得硬编码 MySQL。
3. **`PerfSeverity.ERROR` 在预览面板的渲染样式**：确认红色警示的取色/图标与既有 ERROR 规则一致（不要新造视觉）。
4. **既有测试是否依赖 `ruleCount()`**：`SqlPerfAnalyzerTest` 若有「规则数=20」类断言，需同步改。

## 六、陷阱

- **SQL Server 的 `QUOTED_IDENTIFIER` 默认 ON**、**SQLite/H2 收多种引号**：把「双引号」一律判错会在这三类上误报。
- **缓存串味**（见四·3）。
- **字符串字面量里的引号**：`WHERE name = 'say "hi"'` 里的 `"` 不是标识符引号，**不得**触发告警。
- **反引号在 MySQL 里可能出现在标识符内部**（罕见），判据以「首尾成对」为准，不做逐字符扫描。
- 告警文案要**同时面向人和 agent**（MCP `sqlv_perf_advice` 原样返回），别写成只有 UI 才懂的短语。

## 七、实施步骤（每步可独立提交）

1. `Dialect.identifierQuotes`（基类默认空集 + 11 个方言覆盖）+ 单测：断言各方言集合正确、基类为空。
2. `PerfRuleEngine.apply` 加方言参数 + `DialectAwarePerfRule` 子接口；**既有 20 条规则与测试零改动**（跑定向测试证明）。
3. `PerfRulesBatch4` 新规则 + `PerfRulesBatch4Test`（正例：MySQL 遇双引号报错；反例：PG 遇双引号不报、MySQL 遇反引号不报、SQL Server 遇双引号不报、SQLite 遇反引号不报、字符串字面量里的引号不报）。
4. `SqlPerfAnalyzer`：缓存键带方言 + 注册 Batch4 + `SqlPerfAnalyzerTest` 补「同 SQL 不同方言不串味」用例。
5. 三个调用点传方言 + 各自定向测试。
6. 中英 bundle 两条文案。
7. CHANGELOG `[Unreleased]` 增一条（`### Added` 或 `### Fixed`，按仓内既有分类口径）。

## 八、验证要求

- **定向测试**：`--tests "cn.song.mybatisplus.visualizer.sql.perf.*"` 与 `--tests "cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzerTest"`；**不跑全量、不跑构建校验、不 clean、不 --stop**。
- **反向验证（必做）**：临时把新规则的判据短路（恒返回空列表）→ 步骤 3 的正例必须变红，贴两次读数；再还原。
  合成样本只能证「规则看得见我造的形态」，故正例必须包含**真实形态**：本轮实测那条
  `SELECT t.id, … FROM "user" t LEFT JOIN user_address addressDO …`（A11 现场原文）。
- 报告里禁止出现「全量绿」这类没跑过的读数。

## 九、后续（不在本任务范围，落地后另起）

- **站点侧计数要跟着改**：`features/mcp.md` 与 MCP 主图 `mcp-overview.svg` 都写着「性能建议（**20 条**规则）」，
  本任务落地后应变 **21 条**（另一仓 `mybatis-plus-sql-visualizer-docs`，按「断言须对源码核实」的规矩同改）。
- 插件 README 的能力描述若提到规则数，按 README 同步轮一并核。
- demo 侧 `@TableName` 的跨库取舍仍待用户定夺。

## 十、实施记录（2026-10-12）

### 待验证前提的证伪结果

1. **JSqlParser 是否保留原始引号——保留**。对 `FROM "user" t` / `` FROM `user` t `` / `FROM [user] t` 实测
   `Table.getName()` 分别返回 `"user"` / `` `user` `` / `[user]`；`Column.getColumnName()` 对 `t."station_id"`
   返回 `"station_id"`；`TablesNamesFinder.getTables()` 亦保留引号（JSqlParser 5.3 实测）。
   → **AST 路径成立**，规则以 AST 为主路径、正则仅作降级（§四·2 的「若不保留只能走正则」未触发）。
2. **三个调用点都拿得到方言**。`SqlPreviewService` 有 `currentDialect()`（第 1460 行）直接传；两个 MCP
   工具经 `McpExpectedBridge.currentProjectOrNull(coroutineContext)`（**非挂起**）取项目，取到才
   `DialectRegistry.effective(project)`，取不到传 `null`（规则自动不判，未硬编码 MySQL）。
3. **`PerfSeverity.ERROR` 渲染与既有 ERROR 规则一致**。`SqlPreviewPanelResultView.buildPerfSegments` 对
   ERROR/WARN 同给红色 `⚠` 前缀（`MpvsUiColors.PERF_ERROR_TEXT`），未新造视觉。
4. **无测试依赖 `ruleCount()`**。全仓 grep 只有 `PerfRuleEngine.ruleCount()` 定义与
   `SqlSuppressManagerDialog.ruleCountForTest()`（无关），无「规则数=20」断言，无需同步。
5. **基类 `quoteIdentifier` 原样返回**（`Dialect.kt:40`）——已按 §四·2 新增 `identifierQuotes` 属性，
   未用 `quoteIdentifier("x").first()` 反推。

### 落地与提交（master，未 push）

- `e0fd930` 步骤 1：`Dialect.identifierQuotes`（基类空集 + 11 方言覆盖）+ `PerfRulesBatch4Test` 引号集合断言。
- `a3f587e` 步骤 2+3：`DialectAwarePerfRule` 子接口、`PerfRuleEngine.apply` 方言参、
  `PerfRulesBatch4.dialect_quote_mismatch`（AST 优先 + 正则降级）与 `PerfRulesBatch4Test`（18 例）。
- `0e71c64` 步骤 4：`SqlPerfAnalyzer` 注册 Batch4 + 缓存键带方言 + `SqlPerfAnalyzerTest` 补 2 例。
- `72564e8` 步骤 5+6+7：三调用点传方言、中英 bundle 文案、CHANGELOG `[Unreleased]` 增 Added。

### 定向测试读数

`./gradlew test --tests "…sql.perf.*" --tests "…sql.SqlPerfAnalyzerTest" -Pkotlin.compiler.execution.strategy=in-process`：

- 改动后（绿）：PerfRulesBatch1Test 26、PerfRulesBatch2AstTest 19、PerfRulesBatch4Test 18、
  SqlPerfAnalyzerTest 12，**0 失败**。
- 反向验证（临时把 `checkWithDialect` 短路为恒返回空列表）：30 例中 **6 例失败**，恰为全部正例——
  AST 与正则两条真实形态、MySQL 双引号列名、MySQL 方括号、PG 反引号、缓存不串味用例；反例全绿。
  还原后重跑恢复全绿。合成样本之外，正例含 A11 现场原文，证明规则看得见**真实形态**。

### 未做/存疑

- 站点侧（另一仓 `mybatis-plus-sql-visualizer-docs`）「20 条→21 条」同步按 §九 属后续独立任务，未动。
- 插件内 MCP 工具描述与「No performance findings」文案的 20→21 计数已顺带更新（属本仓调用点文件）。
- 未跑全量测试/构建校验（按任务约定）；未部署、未 push。
