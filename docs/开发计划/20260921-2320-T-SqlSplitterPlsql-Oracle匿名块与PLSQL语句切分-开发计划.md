# T-SqlSplitterPlsql Oracle 匿名块与 PL/SQL 语句切分 — 开发计划

- 日期：2026-09-21 23:20
- 任务编号：T-SqlSplitterPlsql（第十轮报告 §9.4 **O-47**，**P1**）
- 来源：第十轮报告 §8 DBX 增量清单 **#52**——DBX `ffe1e3a2`：Oracle 匿名块含局部例程的语句切分，前后端双实现（`crates/dbx-sql/src/sql.rs:2535-2560` 作用域栈推进、`:2650-2660` `OraclePlSqlScope`（Object/Declaration/RoutineHeader/Routine/Block/Case）；`apps/desktop/src/lib/sql/sqlStatementRanges.ts:1988-2020`）
- 性质：**正确性**——不改渲染语义；修的是「合法 Oracle 脚本被切碎后逐条提交」这一真实执行面
- 实施分支：自 master 拉任务分支 t-sql-splitter-plsql
- 验证基线：master `03f6d25`（最近可核读数 491 类/5604 用例 0 失败（T-AgentHeartbeat 收口）与 5577 例 0 失败（T-TemplateKeyDrop 收口）——**两者属不同批次树，实施时以当次实测为准**）

---

## 一、背景与依据（现状核实，含两处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「按 `;` 朴素切分会把匿名块切碎」 | **成立但机制更具体（口径修正 1）**：我方**已有** BEGIN/END 块感知（不是「没有」），但模型是「**单次武装 + 全文单一深度计数**」——`SqlStatementSplitter.kt:220` 的武装条件含 `!beginModeEnabled`，首个 `BEGIN` 之后**永不重新武装**；`blockDepth` 是**全文一个整数**，无 per-routine 归属；`CASE…END` 的 `END` 会被 `flushWord()`（`:89-95`）与 `beginEndBalance`（`:361`）当作块尾递减 | 落点是**补作用域语义**，不是从零写块感知 |
| 2 | 「碎句只影响展示」 | **不成立（口径修正 2，更严重）**：`log/LogToSqlPanel.kt:601` 的 `splitSqlStatements` 委托给本切分器，而 `:218-222` `executeResult()` **按切分结果逐条 `executeStatement` 提交**——碎句会**真的被逐条执行**。`execute/SqlTypeClassifier.kt:194-195` 亦按切分**逐条分类**（影响免确认门判定） | 本任务从「显示问题」升级为**执行正确性问题**，这是 P1 的依据 |

### 1.1 现有状态维度（逐项实测）

| 维度 | 落点 |
|---|---|
| 单/双引号（含反斜杠转义、`''` 加倍） | `:116-137`（`:9-11` 注释） |
| `--` 行注释 / `/* */` 块注释 | `:151-162`；保留/剥离分流 `:56`/`:63` |
| 括号深度 `parenDepth` | `:186-197`，判定 `:177` |
| **BEGIN/END 块感知 `blockDepth`** | `:74-96`、`:220-227`、`:237-245`，判定 `:142`/`:177` |
| T-SQL 批首控制流合并（`CONTROL_FLOW_BATCH_HEADS = setOf("IF")`） | `:40`、`:265-288`、`:350-392` |
| DELIMITER 指令感知 | `:46`、`:140-150`、`:207-219`、`:313-318` |
| 顶层 `;` 为界 / trim / 空段跳过 / 无分号尾段容错 | `:175-185`、`:246-249` |

**无 Oracle PL/SQL 处理**：全文无 `DECLARE` / `IS`/`AS` 前向声明 / `CASE…END` 与块 `END` 的区分 / SQL*Plus `/` 终结符（`DECLARE` 只出现在词表 `sql/SqlKeywords.kt:49/148/221`）。

### 1.2 缺陷推演（三个具体形态）

输入 `DECLARE … PROCEDURE p IS BEGIN NULL; END; BEGIN p; END;`：

1. 局部例程的 `END` 处 `blockDepth` 归 0 → 紧随的 `;` **切段**（`:177-180`）；
2. 外层 `BEGIN…END` 成第二段；
3. 末尾 `END` 成第三段。

**推演结果：一条合法匿名块被切成 3 段**（每段都会被 `LogToSqlPanel` 逐条提交）。

另两个形态：**`CASE … END CASE`** 的 `END` 被当块尾递减 → 块内 `CASE` 同样切碎；**前向声明 `PROCEDURE p IS;`**（无 `BEGIN`）直接触发 `;` 切分，无任何拦截。

### 1.3 消费方全清单（碎句影响面）

| 消费方 | 影响 | 证据 |
|---|---|---|
| `log/LogToSqlPanel.kt:601` → `:218-222` | **按切分逐条执行**（硬证据） | 同左 |
| `execute/SqlTypeClassifier.kt:194-195`（`splitStripped`）→ `:39-44`/`:52-55`/`:67-78` | 逐条分类 → 影响免确认门；上层 `ui/SqlPreviewPanel.kt:961/1004/1207/1209/1092`、`log/LogToSqlPanel.kt:232/263/265` | 同左 |
| `reverse/SqlToJavaConverter.kt:57-58` → `actions/ConvertSqlToJavaAction.kt:57`、`log/SqlToJavaPanel.kt:781` | 批量转换逐条解析，碎句必失败 | 同左 |
| `mcp/SqlVisualizerCheckSqlTool.kt:46` | 语句数 + 分类 | 同左 |
| `execute/ExecutionTimeoutGuard.kt:38-44` | `statementCount` 放大超时预算——**未找到生产调用方**（仅测试使用，注释自述「消费方接线时按…」） | 同左 |

### 1.4 既有测试（`SqlStatementSplitterTest.kt`，463 行）

- BEGIN/END：`:155` 块内分号不切分、`:164` 嵌套逐层配对、`:170` 大小写不敏感、`:176` 字符串内 BEGIN 不算、`:182` 无配对 END 保守原样、`:189` 块结束后恢复顶层切分；
- T-SQL：`:281-341`（IF/ELSE 双 BEGIN、裸 IF、ELSE IF 链、嵌套、批后独立语句、失衡、字符串内不误判、MySQL `IF()` 不误吞）；
- Oracle：**仅 MERGE**（`:355`/`:367`），**无 PL/SQL 用例**；
- DELIMITER 存储过程：`:376-385` 脚本 + `:388-462` 八例（MySQL 客户端形态）；
- 委托一致守卫：`:234/241/248`、迁移一致 `:255`。

## 二、目标与非目标

**目标**：合法 Oracle 匿名块（含局部例程）与含 `CASE` 的 PL/SQL 块被切分为**单条语句**，不再被逐条提交；既有 MySQL DELIMITER / T-SQL 批 / 引号注释语义**逐字节不变**。

**非目标**：不做完整 Oracle 语法解析（只做「语句边界」判定，保持 `:32` 的纯函数零依赖契约）；不改渲染链与预览语义；不引入 SQL*Plus 专有的 `/` 终结符支持**除非** §八 待验证前提 3 证伪为必要；不动 `ExecutionTimeoutGuard`（其消费方未接线，属另一议题）。

## 三、方案

**T-SSP-01（DECLARE 段整体吞并，最小可行）**：`DECLARE` 起头且后方存在配对 `END` 时，整段吞到末个 `END;`——复用既有前视模式（`:398-432`）。覆盖「匿名块 + 局部例程 + 前向声明」三种形态。

**T-SSP-02（CASE 计数隔离）**：`CASE … END` 不参与块配对（`CASE` 与 `END CASE`/`END` 的配对自成一层，不递减 `blockDepth`）。落点 `flushWord()`（`:89-95`）与 `beginEndBalance`（`:361`）。

**T-SSP-03（完整方案，可选升级）**：轻量作用域栈（Object/Declaration/RoutineHeader/Routine/Block/Case），对齐 DBX `OraclePlSqlScope` 的形态。**默认不做**——T-SSP-01+02 若已覆盖全部用例则按最小可行收口，栈式实现留作后续（避免为不存在的语法面过度设计）。

**T-SSP-04（测试与文档）**：`SqlStatementSplitterTest.kt` 新增用例（**先红后绿**，改前必红）：
- 匿名块含局部 `PROCEDURE`（推演形态）→ 整段一条；
- 匿名块含前向声明 `PROCEDURE p IS;` → 整段一条；
- 块内 `CASE…END` → 不切碎；
- 多个匿名块串接 `BEGIN…END; BEGIN…END;` → **两条**（不是一条也不是四条）；
- 回归：既有 6 例 BEGIN/END + T-SQL 8 例 + DELIMITER 八例全绿。
文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第十轮报告 §8 #52 落地备注。

## 四、验收标准

1. 上述五组新用例全绿，且**改前必红**（先红后绿两次读数留档）。
2. 既有 BEGIN/END（6 例）、T-SQL 批（8 例）、DELIMITER 存储过程（八例）、委托一致（4 例）全绿。
3. `LogToSqlPanel` 执行链对匿名块**只提交一条**（可用既有 headless 测试形态断言切分段数）。
4. `SqlTypeClassifier` 对匿名块的分类不再按碎片产出（免确认门行为如实登记）。
5. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工 / 或经 DBX MCP 执行）

1. **Oracle 匿名块端到端**：DBX MCP 有可用 Oracle 连接（`Oracle Free 23 / FREEPDB1` @ 114.66.55.245:20095，connection_id `311778f3-…`；19c 服务名不通）——可经此连接验证 `DECLARE … BEGIN … END;` 的实际可执行形态与切分结果是否一致。**纪律：该库为共享测试库，只读验证、禁止建删对象。**
2. 真机日志面板粘贴含匿名块的脚本 → 多语句浮层显示为**一条**（不碎句）。
3. 真机控制台粘贴同脚本 → 实际提交的语句数与切分一致。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **改语义连带执行面**（最大风险） | 碎句变整段会改变 `LogToSqlPanel` 的执行条数与 `SqlTypeClassifier` 的分类结果——后者影响**免确认门** | 验收 3/4 显式断言两处行为；变更登记写明「匿名块此前被切碎执行，现整段提交」 |
| 吞并过度 | DECLARE 段吞并若边界算错，会把后续独立语句一起吃进来 | 用例 4（多匿名块串接）专测边界；保守原则＝拿不准就按现状切（宁碎不吞错） |
| 破坏既有 DELIMITER 形态 | 两者都在「多语句脚本」面 | DELIMITER 八例与迁移一致用例作为回归门 |
| 真机场景零证据 | 我方**当前无 Oracle 匿名块的真机或测试证据**（锚点核实确认） | §五-1 经 DBX MCP 补证据；若证明我方用户场景中不存在该形态，则本任务降级为「防御性支持」并如实登记 |
| 过度设计 | 直接上作用域栈会引入未验证的复杂度 | T-SSP-03 默认不做，以用例覆盖度为升级判据 |

## 七、实施顺序

```
T-SSP-01（DECLARE 段吞并）→ T-SSP-02（CASE 计数隔离）→ 用例先红后绿（T-SSP-04）
  → [按覆盖度决定是否 T-SSP-03 栈式升级] → §五 验证 → 收口
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-SSP-01 | （实施后填） | 吞并边界的实现形态与复用点须回填 |
| T-SSP-02 | （实施后填） | CASE 隔离的落点与既有 BEGIN/END 用例影响须回填 |
| T-SSP-03 | （实施后填） | 是否升级为栈式、依据须回填 |
| T-SSP-04 | （实施后填） | 五组用例先红后绿两次读数、门禁读数须回填 |

**待验证前提（实施者先证伪再动手）**：

1. **多个匿名块串接的吞并边界**（`BEGIN…END; BEGIN…END;`）——本计划推演为「两条」，但未实测；实施第一步应先写该用例跑改前代码，确认现状切几条。
2. 「我方用户确实会用到 Oracle 匿名块」**零证据**（锚点核实结论）——若 §五-1 的验证显示该形态在我方场景中罕见，本任务可降级为防御性支持（仍建议做，因为碎句**会被执行**，代价是错误执行）。
3. **JDBC 直连是否接受含 `/` 终结符的脚本**——`/` 是 SQL*Plus 专有，直连通常不接受。若证伪为不需要，则不做 `/` 支持（本计划默认不做）。
4. `CASE` 的两种收尾（`END CASE` 与裸 `END`）在 Oracle 与 T-SQL 下形态不同，须按方言区分还是统一处理——实施时用真机或文档确认。
