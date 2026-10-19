# T-SqlSplitterTsqlGuard T-SQL 控制流批次切分边缘治理 — 开发计划

- 日期：2026-09-23 01:41
- 任务编号：T-SqlSplitterTsqlGuard（第十一轮报告 §9.4 **O-60**，锚点核实后由 P1 收窄为 **P2**——头号风险已被既有实现覆盖，剩两个真实窄边缘）
- 来源：第十一轮报告 §8 DBX 增量清单 **#65**——DBX `80f8ed81`：T-SQL IF/ELSE/WHILE 控制流批次整体切分（`apps/desktop/src/lib/sql/sqlStatementRanges.ts:881,913,965` 的 `SQLSERVER_NON_BLOCK_BEGIN_KEYWORDS`、`scanSqlServerControlFlow`、`isSqlServerControlFlowBatch`，+201 行）
- 性质：**正确性**——修的是「T-SQL 脚本被误合并/误武装后整段送执行或半批送执行」的切分面
- 实施分支：自 master 拉任务分支（或按派单在在途回合分支上实施，以派单为准）
- 验证基线：master 快照 `51a62a7`（最近可核读数 testShards 5866 例 0 失败 6 跳过 + verifyGate 三目标 Compatible——实施时以当次实测为准）

---

## 一、背景与依据（现状核实，含一处重要口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「切分器对 T-SQL 方言加控制流深度扫描，防 `IF BEGIN…END` 内分号把批次切碎」 | **不成立（口径修正 1，头号风险已被覆盖）**：我方**已有**三道 T-SQL 语义——① BEGIN/END 块感知（`SqlStatementSplitter.kt:296-303` 武装 + `:216` 判定，块内顶层 `;` 不切分）；② T-SQL 批首合并 `mergeControlFlowBatches`（`:341-364`，`CONTROL_FLOW_BATCH_HEADS={"IF"}`，裸 `IF…; ELSE…;` 并批 + 失衡截断批吞后续）；③ CASE 层隔离与 `END IF/LOOP/WHILE/REPEAT` 内层终结符（T-SSP-02 及 O-47 订正）。`IF @x=1 BEGIN SELECT 1; SELECT 2; END;` 按现态代码推演＝**1 段**（不是报告担心的碎句） | 本任务从「补控制流扫描」收窄为「治理既有机制的三个边缘」 |
| 2 | DBX #65 的实质增量 | DBX 版在批首识别之外做了**三件我方没有的事**：① `BEGIN TRAN/DIALOG` 等非块 BEGIN 不算块（`SQLSERVER_NON_BLOCK_BEGIN_KEYWORDS`）；② 跨片段携带嵌套深度（`;` 缺省的 END 收尾也能闭合批次）；③ WHILE 同样作批首 | ①② 是我方真实缺口（见 1.2 推演）；③ 对我方不成立（见 §一.3） |

### 1.1 现有状态维度（逐项实测，行号对 master 快照 `51a62a7`）

| 维度 | 落点 |
|---|---|
| BEGIN/END 块感知武装（保守：后方有配对 END 才启用） | `:296-303`（主循环）、`:313-317`（尾部补判定） |
| 块内顶层 `;` 不切分 | `:216`（`parenDepth == 0 && blockDepth == 0 && !declareSection` 才切） |
| T-SQL 批首合并（IF 起头 / ELSE 并批 / 失衡吞并） | `:49`、`:325`/`:341-364`、`beginEndBalance :431-485` |
| CASE 层隔离 + `END IF/LOOP/WHILE/REPEAT` 内层终结符 | `:107-128`（主循环）、`:443-455`（balance 同口径） |
| Oracle DECLARE 声明段（O-47） | `:264-294` |
| 语句边界重置 caseDepth（**不重置 beginModeEnabled/blockDepth**） | `:221-222` |
| `nextWordIs` 词级前视（复用点） | `:499-526` |

### 1.2 缺陷推演（两个真实边缘，均为代码走查推演、待实施首步实测）

**边缘 A（BEGIN 非块形态误武装）**：`BEGIN TRAN; SELECT 1; SELECT CASE WHEN @x>0 THEN 1 ELSE 0 END AS y; SELECT 99;`
- `:296-303` 武装条件是「词 BEGIN + `!beginModeEnabled` + `hasMatchingEndAhead`」——`BEGIN TRAN` 后方只要全文任何位置存在 END 词（本例是 CASE 的 END），即武装 `blockDepth=1`；
- CASE 的 `END` 走 `caseDepth--`（`:122` CASE 层优先），`blockDepth` 永不归零；
- 后续所有顶层 `;` 不切分 → **推演 4 条语句并成 1 段**（含 `SELECT 99`），整段送执行/确认流。
- T-SQL 里 `BEGIN` 后随 `TRAN`/`TRANSACTION`/`DISTRIBUTED`/`DIALOG`/`CONVERSATION` 是事务/会话形态而非块（DBX 同表）；这些词跟着的语句里再出现任何 END 词（CASE、子查询注释、游标声明）即触发本边缘。

**边缘 B（深度归零无分号的 END 不产生边界）**：`IF @x=1 BEGIN SELECT 1; END\nSELECT 2;`
- `END` 使 `blockDepth` 1→0，但切分只认 `;`——END 与下一条语句之间的换行不是边界；
- **推演 2 条语句并成 1 段**（`IF @x=1 BEGIN SELECT 1; END\nSELECT 2`），送 JSqlParser/控制台按单语句处理。
- T-SQL 惯例 END 后分号可省（SSMS 脚本常态），此形态不罕见。

### 1.3 登记不做的形态（含理由）

| 形态 | 理由 |
|---|---|
| WHILE 作批首（DBX #65 的 `isSqlServerControlFlowBatch` 含 WHILE） | 我方批首合并的存在意义是「ELSE 起头碎句并批」（WHILE 无 ELSE）；截断批由主循环 `blockDepth` 兜底吞并，净效果与 DBX 一致。加 WHILE 批首是无行为差异的复制 |
| `GO` 批分隔符 | 独立特性（非 `;` 系界符），DBX 亦不在 #65 范围内（其口径是「GO 照切」＝既有能力）。**登记观察**，不混入本任务 |
| TRY…CATCH 中缝带 `;` 形态（`END TRY; BEGIN CATCH`） | 无 `;` 缝隙时现态即 1 段（推演）；带 `;` 时 2 段且两半各自执行必败。修法需块类型配对（BEGIN TRY 配 END TRY），复杂度对齐「栈式升级」——O-47 T-SSP-03 已有「不为不存在的语法面过度设计」定案。**登记观察 + 待验证前提 4** |

### 1.4 消费方与既有测试

- 消费方（与 O-47 计划 §1.3 同表，无新增）：`log/LogToSqlPanel.kt`（逐条执行）、`execute/SqlTypeClassifier.kt`（逐条分类→免确认门）、`reverse/SqlToJavaConverter.kt`（批量转换）、`mcp/SqlVisualizerCheckSqlTool.kt`（语句数+分类）。
- 既有测试（`SqlStatementSplitterTest.kt`，O-47 交付后 73 例）：BEGIN/END 6 例、T-SQL 批 9 例（含 `TSQL_批内CASE不误判配对失衡`）、Oracle PL/SQL 13 例、DELIMITER 八例、委托一致 4 例等——全部是本任务的回归门。

## 二、目标与非目标

**目标**：① `BEGIN TRAN/DIALOG` 等非块形态不再武装块计数（边缘 A 消除）；② 块深度归零的顶层 `END` 产生隐式语句边界、`;` 缺省的 T-SQL 脚本不再误合并（边缘 B 消除，挂 D1 决策）；既有 MySQL/Oracle/T-SQL/DELIMITER 语义**逐字节不变**（73 例回归门）。

**非目标**：不做完整 T-SQL 语法解析（保持纯函数零依赖契约）；不做 WHILE 批首 / GO / TRY-CATCH 块类型配对（§一.3）；不改渲染链与执行链语义（消费方行为随切分结果自然变化，与 O-47 同性质）。

## 三、方案

**T-STG-01（BEGIN 非块形态豁免，最小可行）**：`:296-303` 武装条件追加否定前视——`BEGIN` 后随词（跳过空白注释，复用 `nextWordIs`）∈ `{TRAN, TRANSACTION, DISTRIBUTED, DIALOG, CONVERSATION}` 时不武装。**不引入新状态**：非武装即走既有「无配对 END 按顶层切分原样」路径。类注释 `:46-49` 的口径说明同步更新。

**T-STG-02（深度归零隐式边界，挂 D1 决策）**：`flushWord()` 的 END 分支检测「`beginModeEnabled && blockDepth` 由 1 归零」时刻，置 `topBlockJustClosed` 标记；主循环下一次遇到非空白内容（且非 `;`/注释/DELIMITER 指令）时插一次切分（产出已完成块为独立段，残余续算）。边界语义推演：`END` 后跟 `;` → 空段跳过（不影响 Oracle 匿名块 `END;` 既有行为）；`END` 后跟 `$$`（DELIMITER 态）→ 走既有界符分支。**若 D1 选 B（不做）**，本任务只交付 T-STG-01，边缘 B 登记在册。

**T-STG-03（测试与文档）**：`SqlStatementSplitterTest.kt` 新增用例（**先红后绿**，改前必红）：
- 边缘 A 语料（1.2 形态）→ 4 段；
- `BEGIN TRANSACTION` / `BEGIN DIALOG` 各一例变体；
- 正对照：合法块 `BEGIN SELECT 1; END;` 仍 1 段、`IF…BEGIN…END; SELECT 2;` 仍 2 段（T-STG-01 不得误伤正常块）；
- 边缘 B 语料（1.2 形态，T-STG-02 选做时）→ 2 段；
- 回归：73 例既有全绿（重点 BEGIN/END 6 例、T-SQL 9 例、PL/SQL 13 例、DELIMITER 八例）。
文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第十一轮报告 §8 #65 落地备注。

## 四、验收标准

1. 新用例全绿且**改前必红**（先红后绿两次读数留档；边缘 B 语料在 D1 选 B 时改为「登记在册的已知行为」不设断言）。
2. 既有 73 例全绿；`SqlTypeClassifierTest` 等切分消费方定向全绿（同 testFast 快桶）。
3. 反向验证：摘掉 T-STG-01 豁免 → 边缘 A 语料转红；摘掉 T-STG-02 边界 → 边缘 B 语料转红（选做时）；还原后复绿。
4. 全量 testShards 绿（三档分层口径：定向自跑 + 收口代理统一全量）；跑前按并行铁律登记。

## 五、真机走查清单（留人工 / 或经 DBX MCP 执行）

1. **T-SQL 引擎对照**（可选）：在册 SQL Server 容器连接（真机复核轮已建 `mpsv-mssql`，127.0.0.1:14333）执行 `BEGIN TRAN; SELECT 1; COMMIT;` 确认事务形态实际可执行边界，佐证「非块 BEGIN 不并入块」的引擎事实。只读纪律：用完即弃表、不留持久对象。
2. 真机日志面板粘贴 1.2 两个语料 → 多语句浮层条数与切分一致（4 条 / 2 条）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| T-STG-02 改变既有块语义 | 归零边界对 Oracle 匿名块 / DELIMITER 存储过程等既有形态可能产生新切分点 | 73 例回归门 + §三 T-STG-03 正对照；D1 备选 B（不做）随时可退 |
| 豁免表误伤 | 某方言里 `BEGIN TRAN` 恰是合法块头（无证据，信创方言未核） | 豁免表**仅 5 词**且逐词登记出处；保守原则＝拿不准的词不进表 |
| 推演与实测偏差 | O-47 前科：推演 3 段实测 2 段 | 实施第一步＝写红用例跑改前代码，以实测段数钉住期望值（本计划推演段数全部标注「推演」） |
| 隐式边界与批首合并交互 | `IF…END` 后接 `SELECT`（无分号）先被 T-STG-02 拆开，`mergeControlFlowBatches` 再合并 ELSE——两机制叠加行为需用例钉住 | T-STG-03 补一条「拆分后合并净效果」用例（`IF…BEGIN…END\nELSE…` 无分号形态） |

## 七、实施顺序

```
红用例实测（先跑改前代码钉段数）→ T-STG-01（非块豁免）→ 反向验证
→ [D1 定案] T-STG-02（隐式边界）→ 反向验证 → 回归 73 例 → §五 → 收口
```

**D1 决策点（待定案，不阻塞 T-STG-01）**：

| 选项 | 内容 | 本会话倾向 |
|---|---|---|
| A 引入隐式边界 | 深度归零顶层 END 即语句边界（`;` 缺省形态修复） | **A**——T-SQL 脚本省分号是常态，误合并整段送执行的代价大于边界判定的复杂度；且有 73 例回归门兜底 |
| B 不做 | 边缘 B 登记在册，本任务只交付 T-STG-01 | 备选——若实施期实测发现归零点判定与既有形态冲突 |

**待验证前提（实施者先证伪再动手）**：

1. **边缘 A 的推演段数**（1 段）与**边缘 B 的推演段数**（1 段）均为代码走查推演、未实测——实施第一步先写用例跑改前代码，以实测钉住（O-47 前科：推演 3 段实测 2 段）。
2. `BEGIN` 后随词表是否需要纳入更多形态——实施时以 DBX `SQLSERVER_NON_BLOCK_BEGIN_KEYWORDS` 全表对齐（锚点只确认了 TRAN/DIALOG 两类在表中，**全表内容未逐词核实**，实施者去 DBX `sqlStatementRanges.ts:881` 抄全表再定；如 `BEGIN CATCH` 属真块对不进表）。
3. 「我方用户会粘贴含 `BEGIN TRAN` 的 T-SQL 脚本」无直接证据（日志还原链以单语句为主）——若实测发现该形态在委托链不可达，本任务降级为防御性支持并如实登记（仍建议做：消费方含用户粘贴的脚本面）。
4. TRY…CATCH 中缝带 `;` 的真实分布（`END TRY;` 写法是否常见）——未验证；登记观察，不做块类型配对。
5. T-STG-02 的归零边界与 `mergeControlFlowBatches` 的净效果（§六 风险 4）——实施时用例实测，不靠推演。

---

## 八、实施记录（2026-09-23，车道 t-tsql-splitter，基线 dev 顶 09d723e）

### 8.1 逐项落地

- **红用例实测（先于一切生产改动）**：新增 8 例（边缘 A 语料、TRANSACTION/DIALOG 变体、边缘 B 语料、无分号匿名块串接、正对照×2、拆分后合并净效果守卫），跑改前代码 **81 例 5 红**：边缘 A 实测 1 段（期望 4）、TRANSACTION 变体 1 段（期望 3）、DIALOG 变体 1 段（期望 3）、边缘 B 实测 1 段（期望 2）、无分号匿名块串接 1 段（期望 2）；正对照×2 与净效果守卫改前即绿。**推演与实测一致**（无 O-47 式偏差）。
- **T-STG-01**：武装条件追加否定前视 `!isNonBlockBeginTail(sql, i)`，`NON_BLOCK_BEGIN_TAILS` = {TRAN, TRANSACTION, DISTRIBUTED, DIALOG, CONVERSATION}（**全表未核，保守 5 词**——DBX 仓不可达，前提 2 按此登记）；实现复用既有 `nextWordIs`（冷路径：仅 BEGIN 武装候选时触发），不引入新状态。EOF 尾词补判定分支无需加豁免——`hasMatchingEndAhead(sql, length)` 在文本尾恒 false，该分支武装本就不可达。
- **T-STG-02**（D1 定案 A）：`topBlockJustClosed` 标记 + `fireImplicitBoundary()` 兑现。fire 点＝词起点/引号/圆括号/非空白符号；`;`、`--`/`/* */` 注释、DELIMITER 指令、自定义界符不触发（`;`/界符分支自身兑现边界并清标记，指令行丢弃一并清标记）。
- 反向验证：摘 T-STG-01 豁免 → A 族 3/3 转红；摘 T-STG-02 置位 → B 族 2/2 转红；还原后复绿（读数见 8.3）。
- 类注释 BEGIN/END 块感知口径同步更新（豁免词表 + 隐式边界 + 声明段例外）。

### 8.2 计划前提被证伪 / 就地更正

1. 前提 1（推演段数）：未被证伪——推演「并成 1 段」与实测一致（边缘 A、边缘 B 双语料）。
2. 前提 2（豁免全表）：DBX 仓不可达，按「全表未核，保守 5 词」执行，词表出处注释已注明。
3. **T-STG-02 置位口径就地收紧（本轮唯一更正）**：计划口径「`beginModeEnabled && blockDepth` 由 1 归零」不完整——Oracle 匿名块声明段内例程体 END（1→0）后声明段仍在吞并、由主体 BEGIN 接力（T-SSP-01 语义），照原口径置位会把 `PLSQL_匿名块含局部例程整段一条` 碎成 2 段；置位条件补 `!declareSection` 后 73 例既有全绿。
4. 前提 3（BEGIN TRAN 可达性）：消费面含日志面板用户粘贴脚本（LogToSqlPanel.splitSqlStatements 委托本实现），按防御性支持落地，如实登记。

### 8.3 定向测试读数（本任务实跑，`./gradlew testFast --tests <FQCN>`）

| 轮次 | 读数 |
|---|---|
| 改前基线 | SqlStatementSplitterTest 81 例：5 红 76 绿（GRADLE_EXIT=1） |
| T-STG-01 后 | 81 例：2 红 79 绿（B 族仍红）——T-STG-01 独立生效证据 |
| 摘 T-STG-01 豁免（反向） | A 族定向 3/3 红（BUILD FAILED） |
| T-STG-01+02 后 | 81 例全绿（XML：tests=81 failures=0 errors=0） |
| 摘 T-STG-02 置位（反向） | 81 例 2 红（恰 B 族 2 例，GRADLE_EXIT=1） |
| 还原后回归 | SqlStatementSplitterTest 81 + SqlTypeClassifierTest 66 + ExecutionTimeoutGuardTest 12 全绿（XML 三类 failures=0 errors=0） |

### 8.4 写集外改动 / 行为漂移登记

- 既有例 `TSQL_IF_ELSE双BEGIN形态整批不拆`：隐式边界先在 ELSE 前拆开、`mergeControlFlowBatches` 再并回——**段数不变（1 段）**，合并批内 `END ELSE` 变为 `END; ELSE`（批首合并以 `"; "` 连接段）。该例仅断言段数仍绿；即 §六 风险 4 预告的两机制叠加净效果，已由净效果守卫用例（`TSQL_拆分后合并净效果_IF_END_ELSE无分号`）钉住。
- 无写集外生产文件改动；`build.gradle.kts` 沙盒行临改已还原不入库。

### 8.5 未实施清单（诚实销账）

- TRY…CATCH `END TRY; BEGIN CATCH` 中缝带 `;` 形态：维持 §一.3 登记不做（无块类型配对）。
- **复合场景「已武装文本内再出现 `BEGIN TRAN`」**（如 `IF…BEGIN…END;\nBEGIN TRAN; …`）：beginModeEnabled 语句间不重置，第二个 BEGIN 走已武装路径直接计数、豁免不复查——边缘 A 的残余窄面，**登记观察**。修它须给已武装 BEGIN 计数路径每次加 5 词前视，与 T-STG-01「仅武装条件」口径不符且违背 O-47「不为不存在的语法面过度设计」先例，不混入本任务。
- WHILE 批首、`GO` 界符：维持 §一.3 登记不做。
