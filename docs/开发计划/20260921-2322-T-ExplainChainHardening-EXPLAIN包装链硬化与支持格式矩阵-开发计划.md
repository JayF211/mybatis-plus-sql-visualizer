# T-ExplainChainHardening EXPLAIN 包装链硬化与支持/格式矩阵 — 开发计划

- 日期：2026-09-21 23:22
- 任务编号：T-ExplainChainHardening（第十轮报告 §9.4 **O-48**，**P1**）
- 来源：第十轮报告 §8 DBX 增量清单 **#50**（`3a44507a`：`crates/dbx-sql/src/query_execution_sql.rs:230-247` `is_safe_explain_source` + `starts_with_explain_keyword_boundary`，测试 `:1073-1115`）与 **#51**（同文件 `:163-175` `supports_explain_plan`、`:178-186` `estimated_plan_format`），并入 **#49** 的四条原则（`f909f850`：只做 estimated / 能力探测与取计划共用错误 / 限额 / 降级 warnings）
- 性质：**正确性 + 一致性**——含一个已实测证实的真缺陷（PG 索引建议永不触发）
- 实施分支：自 master 拉任务分支 t-explain-chain-hardening
- 验证基线：master `03f6d25`（最近可核读数 491 类/5604 用例 0 失败 与 5577 例 0 失败，属不同批次树；实施时以当次实测为准）

---

## 一、背景与依据（现状核实，含四处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「包 EXPLAIN 前须判首关键字落在词法边界」 | **我方 IDE 路径完全没有该判定（口径修正 1）**：`ui/SqlPreviewPanel.kt:1355-1360` 取 `nativeEditorDoc?.text ?: editor?.document?.text`（**任意文本**）后直接 `dialect.renderExplainStatementIdempotent(sql)`——DML、DDL、`SELECTED …` 一律被加前缀。唯一存在的词法边界判定（`sql/Dialect.kt:171-174`）针对的是 **EXPLAIN 关键字自身**（防 `EXPLAINING_TABLE`），不是被包语句首词 | 落点明确：补被包语句的首关键字边界判定 |
| 2 | 「有方言支持表 + 输出格式表」 | **两者都没有（口径修正 2）**：支持面靠 `Dialect.kt:120` 默认 `null` + 7 处覆写（MySQL `:236`/PG `:259`/H2 `:348`/SQLite `:368`/MariaDB `:395`/DM `:422`/KingBase `:449`），**Oracle（`:269`）与 SQL Server（`:309`）未覆写 → null 降级**；**无输出格式概念**，格式硬编码在各方言字面量里；**解析侧不做任何 JSON 解析**（`mcp/McpExplainFormatter.kt:66-78` 只把驱动返回的列/行拼成文本） | 需新增显式表 + 格式枚举 |
| 3 | 「PG 建议器与 FORMAT JSON 错配」 | **成立且已实测证实（本轮主智能体用真 PG 连接验证）**：`util/ExplainIndexAdvisor.kt:42` 按方言名分流 PG 分支、`:102` 正则要求 `Seq Scan on (\w+)`；而 PG 方言渲染的是 `EXPLAIN (FORMAT JSON, VERBOSE) $sql`（`Dialect.kt:259`）。**实测读数**：对 PG 执行该语句返回**单列 JSON 文本**（列名 `QUERY PLAN`，值 `[{"Plan":{"Node Type":"Result",…}}]`）——JSON 里是 `"Node Type"` 键值，**不含 `Seq Scan on xxx` 文本形态** → **PG 索引建议永不触发**。既有测试喂的是文本树（`ExplainIndexAdvisorTest.kt:66`），所以从未暴露 | **真缺陷**，本任务的 P1 依据 |
| 4 | 「只做 estimated（analyze 永不置位）」 | **现状已满足（无修正）**：render 从不产 `ANALYZE`；双守卫在册——`execute/SqlTypeClassifier.kt:102-103`（`EXPLAIN` + `ANALYZE|ANALYSE` + DML 字并存 → 不豁免，走确认弹窗）、`mcp/McpSelectGuard.kt:38`（`analyze` 在 `FORBIDDEN_WORDS`，`EXPLAIN ANALYZE` 直接拒） | **保持不动**，列为验收项防回退 |

### 1.1 落点与消费链

| 环节 | 落点 |
|---|---|
| 接口与实现 | `sql/Dialect.kt:120`（默认 null）、`:130-134`（`renderExplainStatementIdempotent`）、`:171-174`（`startsWithExplainKeyword`）、7 处覆写、`:455-468`（`DialectRegistry` 9 方言） |
| IDE 链 | `ui/SqlPreviewPanel.kt:1348-1394`（`explainSql()`）、按钮 `:895`；结果区由平台 DBN 托管、**插件无程序化读取面**（`util/ExplainIndexAdvisor.kt:9-13` 明载，IDE 面建议条挂账） |
| MCP 链 | `mcp/SqlVisualizerExplainTool.kt:16-179`（`resolveExplainTarget :62-123`、`executeExplain :132-167`）→ `mcp/McpExplainFormatter.kt:66-78` → 索引建议 `:53-54` |
| 索引建议 | `util/ExplainIndexAdvisor.kt:35-46`（方言分支）、`:48-96`（MySQL 表格式）、`:98-115`（PG 文本树） |

**可复用的边界判定先例**：`converter/jpa/SelectClauseExpander.kt:222` + `:230-236` `isWordBoundary`（注释明写「避免 SELECT 命中 SELECTED 之类」）。**不可复用**：`execute/SqlTypeClassifier.kt:235-241` 的 `extractKeyword` 用 `takeWhile { it.isLetter() }`——`SELECT_foo` **也会被放行**（方向比 DBX 宽松）。

### 1.2 既有测试

`sql/DialectExplainTest.kt`（13 例：7 方言形态 + Oracle/SQLServer null + 幂等 4 例 + 全方言不抛）、`util/ExplainIndexAdvisorTest.kt`（10 例）、`mcp/McpSelectGuardTest.kt`（16 例）。**无** `SELECTED`/`SELECT_foo`/`SELECT(1)`/`VALUES(1)`/`TABLE users` 边界用例，**也无 PG JSON 形态用例**。

## 二、目标与非目标

**目标**：① 包 EXPLAIN 前判定被包语句的首关键字落在词法边界（`SELECTED`/`SELECT_foo` 拒，`SELECT*`/`SELECT(1)`/`VALUES(1)`/`TABLE users` 放行）；② 支持面与输出格式收敛为**显式表**，不再散在逐方言覆写里；③ **修掉 PG 索引建议永不触发的错配**；④ analyze 双守卫保持不动。

**非目标**：不新增 Oracle / SQL Server 的 EXPLAIN 支持（Oracle 需两步链 `PLAN_TABLE`、SQL Server 需会话级 `SET SHOWPLAN_XML`——**另议**，见 §八 待验证前提 3）；不改 IDE 结果区读取面（平台 DBN 托管，插件无程序化读取面）；不改 `McpSelectGuard` 的只读闸门；不做计划文本的 JSON 结构化解析（除非 §三 T-ECH-03 需要）。

## 三、方案

### 决策点 D1（**须定案**）：IDE 路径引入首关键字判定后的行为

| 选项 | 内容 | 代价 | 推荐度 |
|---|---|---|---|
| **A（严格判定，推荐）** | 首关键字不在白名单 → 不执行 EXPLAIN，给出明确提示（复用既有诚实降级文案形态） | 改变「任意文本都能点执行计划」的现状 | ★★★ |
| B（仅告警） | 仍执行，但在结果区/日志加一条降级提示 | 不改变行为，但错包仍会发到库 | ★★ |
| C（不动 IDE 链，只硬化 MCP） | MCP 侧已更严（结构白名单），只补支持/格式表 | 最小风险，但真缺陷面（错包）留着 | ★ |

**D1 建议**：**A**——错包 EXPLAIN 会把 DML/DDL 送进数据库（即使 EXPLAIN 不执行，语法错误与副作用风险仍由库侧承担），且提示比静默错包诚实。

### 任务分解

**T-ECH-01（首关键字词法边界）**：新增判定（建议 `sql/ExplainGate.kt` 或并入 `Dialect`），IDE 与 MCP **同链**调用；判定须是**显式边界**（前一字符非字母数字且非 `_`），**不得**复用 `extractKeyword` 的 `takeWhile`（会放行 `SELECT_foo`）。白名单与 DBX 对齐：放行 `SELECT`（含 `SELECT*`/`SELECT(1)`）、`VALUES`、`TABLE`、`WITH`；拒绝 `SELECTED`/`SELECT_foo`/DML/DDL。

**T-ECH-02（支持面与格式收敛为显式表）**：新增 `ExplainSupport(dialectName → statementTemplate, outputFormat)`，`outputFormat` 枚举（`JSON` / `TEXT` / `TABLE` / `XML`），由 `Dialect` 的既有覆写改为读表（**渲染产物逐字节不变**是硬要求）；`ExplainIndexAdvisor.kt:38-44` 改**读格式**而非方言名分流。

**T-ECH-03（修 PG 建议器错配）**：PG/KingBase 走 `FORMAT JSON`，建议器须按 JSON 形态取 `"Node Type"` / `"Relation Name"`（或改由 T-ECH-02 的格式枚举分派到 JSON 解析分支）。**先红后绿**：新增用例喂**实测形态**（单列 JSON 文本，见 §1.1 表 3 的实测读数），改前必红。

**T-ECH-04（守卫与文档）**：`DialectExplainTest.kt` 新增边界用例五组；`ExplainIndexAdvisorTest.kt` 新增 PG JSON 用例；**analyze 双守卫加防回退断言**（现有测试已覆盖，确认不弱化）。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第十轮报告 §8 #50/#51 落地备注。

## 四、验收标准

1. 边界判定：`SELECTED` / `SELECT_foo` / DML / DDL **拒**；`SELECT*` / `SELECT(1)` / `VALUES(1)` / `TABLE users` / `WITH …` **放行**（五组用例）。
2. 渲染产物**逐字节不变**（既有 7 方言形态断言全绿）。
3. **PG 索引建议可触发**：喂实测的 JSON 形态能产出建议（改前红、改后绿，两次读数留档）。
4. 支持/格式表与逐方言实现**一致**（守卫断言：表里的 template 与各方言渲染结果逐字节相同）。
5. analyze 双守卫未被弱化（`SqlTypeClassifier.kt:102-103`、`McpSelectGuard.kt:38` 的既有用例全绿）。
6. 全量 testShards + testDatabase + agent 绿。

## 五、验证清单（可经 DBX MCP 执行）

**PG 侧已实测可用**：`百度云 | PostgreSQL`（connection_id `ce028080-e7fb-40ea-8155-4842f6ecc611`）——本轮已用它取到 `EXPLAIN (FORMAT JSON, VERBOSE)` 的返回形态。**纪律：共享库只读验证、禁止建删对象。**

| # | 场景 | 判据 |
|---|---|---|
| 1 | PG `EXPLAIN (FORMAT JSON, VERBOSE) SELECT 1` | 返回单列 JSON 文本（本轮实测基线）；改造后建议器能解析该形态 |
| 2 | PG 真实表上的顺序扫描（如 `information_schema.tables`） | JSON 里出现 `"Node Type":"Seq Scan"` + `"Relation Name"` → 能产出索引建议 |
| 3 | MySQL / DM 侧（文本/表格式） | 建议器行为与改前一致 |
| 4 | 真机 IDE：对一条 DML 点「执行计划」 | 按 D1=A 给出明确提示、不执行 |
| 5 | 真机 MCP：`sqlv_explain` 传 DML | 与改前一致（结构白名单已拒） |

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **IDE 行为变更**（最大风险） | 引入判定后，此前能点的场景会变成提示 | D1 定案 A；变更登记写明；§五-4 真机确认提示形态 |
| 渲染产物被改坏 | T-ECH-02 把覆写改为读表，若 template 抄错会改 SQL | 验收 2「逐字节不变」+ 守卫断言表与实现一致 |
| PG JSON 形态与版本相关 | 本轮实测是 PG 侧一种形态（单列 JSON 文本）；不同 PG 版本/驱动包装可能不同 | 建议器按**容错解析**（先试 JSON，失败回落文本树），并在 §五-2 用真实表复验 |
| 支持表与实际支持面漂移 | 表是新增的单一事实源，但方言仍可能各自覆写 | 守卫断言二者一致（验收 4） |
| 过度扩张 | Oracle/SQLServer 接入是独立工程（两步链/会话级开关） | 非目标显式排除，另议 |

## 七、实施顺序

```
[D1 定案] → T-ECH-01（词法边界）→ T-ECH-02（支持/格式表，渲染逐字节不变）
  → T-ECH-03（PG 建议器修复，先红后绿）→ T-ECH-04（用例与守卫）→ §五 验证 → 收口
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| D1 定案 | （实施后填） | A/B/C 选择与理由须回填 |
| T-ECH-01 | （实施后填） | 判定落点（新文件 vs 并入 Dialect）与白名单最终形态须回填 |
| T-ECH-02 | （实施后填） | 表结构、格式枚举取值、逐字节不变实证须回填 |
| T-ECH-03 | （实施后填） | PG JSON 解析形态与两次读数须回填 |
| T-ECH-04 | （实施后填） | 用例读数、守卫断言、门禁读数须回填 |

**待验证前提（实施者先证伪再动手）**：

1. ~~PG `FORMAT JSON` 返回形态未知~~ → **已实测**：单列 JSON 文本（`QUERY CODE` 列名 `QUERY PLAN`，值 `[{"Plan":{"Node Type":"Result",…}}]`）。**仍待确认**：真实表顺序扫描时 `"Node Type"` 的确切取值（`Seq Scan`）与关系名键（`"Relation Name"`）——实施第一步用 §五-2 复验。
2. 「MCP 侧无需改动」的前提是 `McpSelectGuard.checkSingleSelect` 的结构白名单已足够严（`mcp/McpSelectGuard.kt:67-101` + `:157-167` `\b` 粗扫）——实施时须确认它对 `VALUES(1)`/`TABLE users` 这类**非 SELECT 但可 EXPLAIN** 的语句是否过严（若过严，T-ECH-01 的白名单须与它对齐，否则两链行为分叉）。
3. Oracle / SQL Server 是否同批接入——本计划默认不接（各有独立前置：Oracle 两步链、SQL Server 会话级 `SET SHOWPLAN_XML`）；若用户要求，另立计划。
4. KingBase 是否与 PG 同格式（现共用 `FORMAT JSON` 字面量）——须确认（KingBase 兼容 PG 但版本差异可能影响 JSON 结构）。
