# T-SelectGuardScalarExempt MCP SELECT 白名单标量函数豁免 — 开发计划

- 日期：2026-09-23 01:41
- 任务编号：T-SelectGuardScalarExempt（第十一轮报告 §9.4 **O-61**，锚点核实后由 P1 收窄为 **P2**——REPLACE 已豁免，真实缺口仅 `INSERT(` 一处；体量小但带安全论证与文档订正，独立成计划）
- 来源：第十一轮报告 §8 DBX 增量清单 **#69**——DBX `4975b72b`：只读边界豁免标量函数 REPLACE()/INSERT()（`crates/dbx-sql/src/query_execution_sql.rs:263-313` `WRITE_VERBS_WITH_SCALAR_FUNCTION_FORMS=["replace","insert"]`，带括号形态豁免、含方言空格；update 仍判写；测试 `:1786-1831`）
- 性质：**正确性**（MCP 面误拒）——`SELECT` 里的 MySQL `INSERT()` 串函数被白名单误杀
- 实施分支：自 master 拉任务分支（或按派单在在途回合分支上实施，以派单为准）
- 验证基线：master 快照 `c07b8e3`

---

## 一、背景与依据（现状核实，含两处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「三处判定面若按整词扫 INSERT/REPLACE 会被误杀或误放，须先锚点核实」 | **核实完成，面收窄为一处（口径修正 1）**：① `McpSelectGuard`——`FORBIDDEN_WORDS` 含 `insert`、**刻意不含 `replace`**（`:31` 注释自证「replace（REPLACE() 纯读函数）」）→ REPLACE 半边**已豁免**，INSERT 半边是真实缺口；② `SqlTypeClassifier`（执行确认流）——DML 判定**首关键字驱动**（`:50`/`:74`），`SELECT INSERT(...)` 首词 SELECT → 不误判，仅 WITH 豁免词扫描有一处窄边缘（§一.3）；③ `sqlv_check_sql`（`SqlVisualizerCheckSqlTool`）——组合切分器 + SqlTypeClassifier + JSqlParser AST 写风险，Select 形态不产出写发现 → 无缺口 | 落点只剩 `McpSelectGuard.scanWords` 一处词匹配豁免 |
| 2 | 「粗扫在解析之前跑，误拒无法被结构判定纠正」 | **不成立（口径修正 2）**：实际判定序是**解析 → 单语句 → Select 结构 → 锁读 → INTO → 粗扫（剥离字面量后）→ 放行**（`McpSelectGuard.kt:66-104`）——粗扫是**第二道闸**。由此得出**安全论证**：能到达粗扫的输入必已解析为单条 Select，真实写语句根本到不了这一步，故对粗扫加「后随 `(` 豁免」**不可能放行任何真实写语句** | 豁免的安全边界由结构闸保证，实现可以放心做窄 |
| 3 | （锚点新发现，非报告口径） | **KDoc 判定序描述失实**：`McpSelectGuard.kt:52` 写「空 → 超长 → 关键字粗扫 → 解析 → …」（粗扫在解析前），与实现（解析优先、粗扫兜底，`:68-70` 注释亦如此自述）**顺序相反** | 文档缺陷随本任务一并订正 |

### 1.1 缺陷形态（唯一）

`SELECT INSERT(name, 1, 2, 'x') FROM t`（MySQL `INSERT(str,pos,len,newstr)` 串函数）→ 解析为单条 Select ✓ → 粗扫 `\binsert\b`（`:165`）命中函数名 → **拒 `READ_ONLY`**。同理 `SELECT REPLACE(...)` 改前已放行（正对照）。

**误拒的实际影响**：MCP 工具 `sqlv_explain`（内经 McpSelectGuard 的调用链）拒绝合法读查询，agent 侧收到「contains the non-read-only keyword 'insert'」——语义误导（语句根本不是写）。

### 1.2 豁免判据（对齐 DBX）

`insert` 后随「可选空白 + `(`」即豁免（`INSERT (` 与 `INSERT(` 都豁免）。**update 不豁免**（DBX 同款：MySQL 接受 `UPDATE (t)` 表形态；虽然结构闸已保证它到不了粗扫，保守从 DBX 口径）。其余词不动。

### 1.3 登记观察（不修）

`SqlTypeClassifier` 的 WITH 豁免词扫描（`:101` `words.drop(1).none { it in DML_KEYWORDS }`）对 `WITH x AS (SELECT INSERT(a,1,2,'x') FROM t) SELECT * FROM x` 会因词表命中 INSERT 而不豁免 → 走 DML 二次确认。**方向安全（多确认一次，不放行任何东西）**、纯 UX 边缘。若实施时顺手：把豁免判据做成两处共用（须保留词表上下文判定，`WITH x AS (DELETE FROM t…)` 真写形态必须仍然不豁免）——挂 D2，默认只登记。

## 二、目标与非目标

**目标**：`McpSelectGuard` 粗扫对 `insert` 的「后随可选空白 + `(`」形态豁免（DBX #69 同款判据）；`replace` 整词豁免维持不动；既有判定序、错误码、其余 FORBIDDEN_WORDS 逐字节不变；`:52` KDoc 判定序描述订正为与实现一致。

**非目标**：不动结构闸（解析/单语句/Select/锁读/INTO 五道）与其错误码语义；不改 `sqlv_check_sql`/`SqlTypeClassifier` 的生产逻辑（D2 若定案另说）；不为 update/delete 做同类豁免（无形态证据）。

## 三、方案

**T-SGE-01（词匹配豁免）**：`scanWords`（`:162-172`）对 `FORBIDDEN_WORDS` 中的 `insert` 特判——`Regex` 命中后再看命中点后方是否「可选空白 + `(`」，是则跳过该词（或直接把 insert 从通用循环拆出单判）。KDoc（`:29-33`）同步登记豁免形态与出处（DBX `WRITE_VERBS_WITH_SCALAR_FUNCTION_FORMS` 同款 + 本计划 §一.2 安全论证）。

**T-SGE-02（测试，先红后绿）**：`McpSelectGuardTest`（在册测试类）新增：
- **先红**：`SELECT INSERT('hello', 1, 0, 'X')` → 改前 `READ_ONLY` 拒、改后放行；
- **正对照（改前即绿）**：`SELECT REPLACE(a, 'b', 'c') FROM t` 放行（钉住既有豁免不回退）；
- **安全回归**：`INSERT INTO t VALUES(1)`、`insert into t select …` 仍拒（结构闸 READ_ONLY，非粗扫路径）；`UPDATE t SET x=1` 仍拒；`UPDATE (t) …` 形态仍拒；
- **相邻形态**：`SELECT update_time FROM t` 仍放行（词边界既有行为）、列名带 `insert` 前缀（`insert_log`）仍放行（`\b` 不命中）；
- 常规回归：既有 McpSelectGuard 用例全绿。

**T-SGE-03（KDoc 订正 + 文档）**：`:52` 判定序描述改为「空 → 超长 → 解析 → 单语句 → Select 结构 → 锁读/INTO → 关键字粗扫（剥离字面量后）」；CHANGELOG `[Unreleased]` Fixed 行 + 变更记录行 + 第十一轮报告 §8 #69 落地备注。

## 四、验收标准

1. 先红用例改前必红、改后绿（两次读数留档）；正对照与安全回归全绿。
2. 反向验证：摘掉豁免分支 → 先红用例转红、正对照不受影响；还原后复绿。
3. 既有 `McpSelectGuardTest` 全绿；`ExplainGateTest` 相关跨链用例（`包含性_白名单接受的输入必过首关键字判定`）不受影响。
4. `:auto` MCP 面定向测试全绿（`test --tests "cn.song.mybatisplus.visualizer.mcp.*"`，在册读数 22 类 140 例）；全量由收口代理统一跑。

## 五、真机走查清单（2026-09-23 §八收拢后作废重写；两项均**真机留人工**——实施车道无法起真机 IDE/MCP）

1. **T-SGE-01 可选实调**：经 MCP 实调 `sqlv_explain` 传 `SELECT INSERT('ab',1,0,'X')` 观察改前拒/改后通——真机留人工（headless 单测已覆盖同一判定链）。
2. **§八 收拢必须实调**：经 MCP 实调 `sqlv_select` 传方括号 SELECT（如 `SELECT * FROM [user] WHERE [id] = 1`）取改前拒/改后通各一次读数——真机留人工。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 豁免打开写语句通路 | 理论担忧 | §一.2 安全论证：粗扫是结构闸之后的第二道闸，能到粗扫者必已解析为单条 Select；真实写语句到不了粗扫。安全回归用例钉住 |
| 豁免过宽（`INSERT (` 含空格形态） | `INSERT (a,b)` 是否可能是写语句的起头 | 主流方言 INSERT 语法必须 `INSERT INTO`/`INSERT INTO … SELECT`，无裸 `INSERT (` 写形态；DBX 同款含空格豁免已在生产验证 |
| 两处词表漂移 | McpSelectGuard 与 SqlTypeClassifier 的 DML 词表语义不同步（后者含 MERGE 前者含 DDL 词） | 本任务只动前者；D2 若做共用判据再统一登记（观察项） |

## 七、实施顺序

```
T-SGE-02 红用例先跑改前代码（取先红读数）→ T-SGE-01 豁免实现 → 反向验证
→ T-SGE-03 KDoc/文档 → §四 收口（D2 默认只登记 §一.3）
```

**D2 决策点（待定案，不阻塞主线）**：SqlTypeClassifier 的 WITH 豁免词扫描是否一并加同型豁免——本会话倾向**默认不做、登记观察**（方向安全的 UX 边缘，且两处词表口径不同，强行共用判据会引入耦合）。

**待验证前提（实施者先证伪再动手）**：

1. **误拒形态的实测**：§1.1 的 `SELECT INSERT(...)` 误拒是代码走查推演（判定序 + 词表逐行读过），未跑过真实 `checkSingleSelect`——实施第一步先跑改前代码取先红读数，若实测放行（推演证伪）则本任务整体降级为「不适用」并登记。
2. **update/delete 在粗扫位置的可匹配形态为零**的推断——实施时补一条「确认无同类误拒」的语料扫描（对 FORBIDDEN_WORDS 全表过一遍「词后随 `(` 的函数形态」清单，MySQL/PG/Oracle 函数名与写动词同形的只有 insert/replace 两个；若有第三个，扩表并登记出处）。
3. `ExplainGate` 对 `SELECT INSERT(...)` 的路径（首词 select 在白名单 + 结构判定）——推演放行、无缺口；实施时以 ExplainGateTest 既有语料间接回归，不新增断言（写集外）。

## 八、增量登记：MCP SELECT 闸门解析入口收拢（2026-09-23 并入，性能第十轮 D1 定案）

**来源**：性能第十轮调研报告 §5 F15 / `T-PerfSweep7` 计划决策点 D1 定案（2026-09-23 用户确认）——「目的地 A、载体 O-61」：`McpSelectGuard` 的解析收拢不进性能 sweep，作为**本任务增量范围**随批实施（本就动 `McpSelectGuard.kt`，KDoc 判定序订正与收拢同文件同批）。

**增量内容**：`checkSingleSelect` 的 `CCJSqlParserUtil.parseStatements(trimmed)`（`:68`）改走 `SqlAstParser.parseStatements`（开启 `allowSquareBracketQuotation`，共享单例 features）——效果：SQL Server 方括号 SELECT（产品方言层自产形态，`Dialect.quoteIfNeeded`）从「解析失败按写拒绝」变为可进入既有结构判定。

**随批安全清单（缺一不收口）**：

1. **负样本四组判据**（方括号开启后仍拒）：多语句走私 `SELECT * FROM [user]; DROP TABLE [x]`；`SELECT ... INTO [t] FROM [s]`；锁读 `SELECT * FROM [t] FOR UPDATE`；字面量剥离后粗扫命中形态。每组先红后绿或对照闸门既有读数。
2. **Throwable/StackOverflowError fail-closed 路径原样保留**（`:69-77` 注释语义不动），补一条恶意构造输入仍 deny 的回归。
3. **§五 真机走查清单随之作废重写**：本增量必须真机 MCP 实调 `sqlv_select` 传方括号 SELECT（改前拒/改后通各取一次读数）。
4. **用户可见变更**：CHANGELOG `[Unreleased]` 条目（闸门能力扩展属用户可感知，非性能清扫）。

**风险对齐**：本增量的安全论证范式沿用 §六 前两行（结构闸在先、粗扫在后，解析面扩大不改变结构判定集）；`SqlAstParser` 现只暴露多语句版，闸门语义（`statements.size != 1` 判定）不变。

## 九、实施记录（2026-09-23 实施后回填，车道 t-guard-parse，基线 cb7e764）

### 9.1 逐项落地

- **T-SGE-01**：`scanWords` 改 `findAll` 逐命中判定——`SCALAR_FUNCTION_FORM_WORDS = {"insert", "truncate"}` 内的词，命中点后随「可选空白 + `(`」即跳过该命中（多处命中时任一处非函数形态即拒，fail closed）；其余词与其余形态逐字节不动（拒绝文案不变）。`replace` 整词豁免维持。
- **§七 前提 2 扩表**：FORBIDDEN_WORDS 全表「词后随 ( 的函数形态」排查（MySQL/PG/Oracle 内建函数逐词核对）——与写动词同形的标量函数仅 **replace**（已整词豁免）、**insert**（本任务）、**truncate**（MySQL `TRUNCATE(X,D)` 数学函数，8.0 手册 12.6.2 Mathematical Functions）三个；truncate 随本批入豁免表并留出处。update/PG 命令族（vacuum/cluster/listen/notify/reindex/copy/checkpoint/discard 等）无同名标量函数，不豁免。jshell 对 jar 实测：`SELECT TRUNCATE(price,2) FROM t` 解析为 PlainSelect（粗扫可达，误拒形态真实存在）。
- **§一 前提核实（非证伪）**：先红读数证实 `SELECT INSERT('hello',1,0,'X')` 走到粗扫被拒（READ_ONLY）——解析为单条 Select 的推演成立，主半不降级。
- **§八 收拢**：`checkSingleSelect` 解析改走 `SqlAstParser.parseStatements`（internal 同模块，开 `allowSquareBracketQuotation` 共享单例 features）；`CCJSqlParserUtil` import 移除；`catch (Throwable)` fail-closed 路径逐字未动；错误码语义不变。
- **T-SGE-03 KDoc 订正**：判定序描述改为「空 → 超长 → 解析 → 单语句 → Select 结构 → 锁读/INTO → 关键字粗扫（剥离字面量后）」，与实现一致。
- **D2**：按定案不做，维持 §一.3 登记观察。

### 9.2 测试读数（`./gradlew testFast --tests <FQCN>` 除注明外）

| 轮次 | 代码态 | 读数 |
|---|---|---|
| 先红 | 改前 | McpSelectGuardTest 28 例 3 红：①`SELECT INSERT('hello',1,0,'X')` → **MCP_READ_ONLY**（粗扫命中 'insert'，主半误拒实锤）②方括号 SELECT 放行用例 → SQL_BLOCKED（解析失败）③方括号粗扫兜底用例 → SQL_BLOCKED（期望 READ_ONLY） |
| 豁免后 | T-SGE-01 落地 | 29 例 2 红——新增 TRUNCATE 豁免用例绿（`TRUNCATE(price,2)`/`TRUNCATE (x,0)` 均放行）；余 2 红均为 §八 待收拢项 |
| 反向验证 | 豁免表临时置空 | 恰 INSERT/TRUNCATE 两用例转红（均 READ_ONLY，误拒复现）＋§八 2 红；REPLACE 正对照/安全回归/相邻形态保持绿；还原后 R4 全绿即「还原后复绿」读数 |
| 收拢后全绿 | O-61 完整 | McpSelectGuardTest 29 例全绿（含方括号放行、负样本四组、fail-closed 回归）；`test --tests "cn.song.mybatisplus.visualizer.mcp.*"` + ExplainGateTest 平台桶 **28 类 183 例全绿**（跨链包含性用例不受影响） |

负样本四组读数（收拢后）：多语句走私 `SELECT * FROM [user]; DROP TABLE [x]` → SQL_BLOCKED（多语句分支）；`SELECT [a] INTO [bak] FROM [s]` → SQL_BLOCKED（INTO 分支）；`SELECT * FROM [t] FOR UPDATE` → SQL_BLOCKED（锁读分支）；`SELECT 1 FROM [t] WHERE analyze = 1` → READ_ONLY（剥离后粗扫兜底）。前三组改前经 fail-closed 同判拒（对照闸门既有读数），第四组改前 SQL_BLOCKED 改后 READ_ONLY（真先红）。

### 9.3 计划前提被证伪 / 就地更正

- 前提 1/3 核实成立，无证伪。前提 2 如实扩表：第三个同形词 truncate 存在（出处见 9.1），已按「有就扩表并登记出处」处置。

### 9.4 未做项与移交

- CHANGELOG `[Unreleased]` Fixed 行、变更记录行、第十一轮报告 §8 #69 落地备注、§八第 4 条用户可见变更条目——**主控统一写**（车道禁改三文件）。
- §五 两项真机 MCP 实调——**真机留人工**（本车道无法起真机），清单已重写进 §五。
- 红线族零增（未新增 Timer/Thread.sleep/Executors/现场正则/新 EP；`isScalarFunctionForm` 为手写字符扫描，非正则）。
- 写集外改动：无（`build.gradle.kts` 沙盒行临改在提交前还原，不入库）。
