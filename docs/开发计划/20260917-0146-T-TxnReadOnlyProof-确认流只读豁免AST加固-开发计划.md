# T-TxnReadOnlyProof 确认流只读豁免 AST 加固 — 开发计划

- 日期：2026-09-17 01:46
- 任务编号：T-TxnReadOnlyProof（第六轮新建议 **N-21**，P1）
- 来源：第六轮报告 §7 DBX 吸收清单 **#27**——DBX `e9be6d0` 手工事务「已证只读则隐藏提交/回滚」：**只读证明引擎复用 + 逐语句数量/位置配对 + 任何不匹配 fail-closed**（query.rs:5393、sql_risk.rs:1110）
- 性质：执行确认流（安全边界）正确性加固——关键字分类法存在真实豁免盲区
- 实施分支：合流后自 master 拉任务分支 t-txn-read-only-proof（前置项 T-MergeDev2Master）
- 验证基线：合流后 master 全量 testShards 绿（以 T-MergeDev2Master 钉死计数为准）

---

## 一、背景与依据

DBX #27 的本质不是「隐藏按钮」这个 UX 形态，而是「**只读豁免必须逐语句证明且 fail-closed**」。立项锚点核实（dev 实测）发现两件事：①本插件无手动事务模式（主链 `setAutoCommit` 零命中），DBX 的按钮形态无落点且按红线不引入；②`SqlTypeClassifier.isQuery` **已经是**逐语句全量证明（`statements.all { classify(it) == QUERY }`，混合按最高风险、UNKNOWN 不豁免）——骨架与 DBX 同型。但关键字分类法留有**三个真实豁免盲区**，全部落在 QUERY 名单内：

| 盲区 | 形态 | 实际行为 |
|---|---|---|
| 可写 CTE | `WITH del AS (DELETE FROM t …) SELECT * FROM del` | 首关键字 WITH → 免确认 → **真执行 DELETE** |
| EXPLAIN ANALYZE | `EXPLAIN ANALYZE INSERT/UPDATE/DELETE …`（PG 语义：ANALYZE 会**真实执行**被修饰语句） | 首关键字 EXPLAIN → 免确认 → **真执行写** |
| SELECT INTO | PG `SELECT … INTO new_table`（建表=DDL）；MySQL `SELECT … INTO OUTFILE`（写服务端文件） | 首关键字 SELECT → 免确认 → **真执行写** |

确认流是本插件「可回滚性」七环的安全边界，豁免被绕过即边界失效——这是 P1 的依据。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 分类骨架 | isQuery=拆句后逐条 classify 全 QUERY 才豁免；混合/UNKNOWN 一律确认（fail-closed 已在位） | `execute/SqlTypeClassifier.kt`（isQuery/classify） |
| QUERY 名单 | `SELECT/SHOW/EXPLAIN/DESCRIBE/DESC/WITH`——纯首关键字匹配，无二级证明 | `execute/SqlTypeClassifier.kt` QUERY_KEYWORDS |
| 拆句 | 已收敛委托 util/SqlStatementSplitter（T-SemicolonGuard 单实现双入口） | `execute/SqlTypeClassifier.kt` 尾部委托 |
| 确认流消费方 | 三处共用 ExecuteConfirmMessages.build（无危险发现也弹既有 dml 文案，形态不动） | `ui/SqlPreviewPanel.kt:704,812`（executeSql/executeAdhocSql，`!isQuery && !dmlConfirmed` 门控）、`log/LogToSqlPanel.kt` executeStatement |
| 手动事务 | 无（setAutoCommit 主链零命中）——DBX 按钮形态不适用，定案不引入 | — |

## 三、方案

**T-TR-01（只读二级证明纯函数）**：`SqlTypeClassifier` 内新增 `proveReadOnly(statement): Boolean`（或 classify 内联加固，保持 isQuery/classify 签名与消费方零改动）——对首关键字命中 QUERY 名单的语句做二级门控，三盲区任一命中即改判非豁免（返回非 QUERY 或 UNKNOWN）：

1. `WITH` 前缀：CTE 定义闭合后尾随主语句或任一 CTE 体内出现 INSERT/UPDATE/DELETE/MERGE 关键字 → 不豁免。实现取**保守宽门控**优先（词法级扫描，宁错弹不放过）；JSqlParser AST 精确化作为可选增强步骤，词法门控先行交付。
2. `EXPLAIN/ANALYSE` 前缀：后续出现 ANALYZE/ANALYSE 且修饰对象为 DML 关键字 → 不豁免。
3. `SELECT` 前缀：出现 `INTO OUTFILE|DUMPFILE`（MySQL）或 `INTO <identifier>` 建表形态（PG）→ 不豁免（`INTO @var` 会话变量豁免维持）。

证明不了（词法歧义/解析失败）一律维持现状确认弹窗——fail-closed 与 DBX 同规。

**T-TR-02（消费方零改动验证）**：三处确认流 `!isQuery` 门控与 `ExecuteConfirmMessages.build` 逐字节不动；豁免收窄只发生在分类器内部。文案零新增（弹窗克制红线：不新增形态不新增步骤）。

**T-TR-03（表驱动测试）**：`SqlTypeClassifierTest` 扩充——三盲区各正反例（可写 CTE 拦截 / 普通 `WITH x AS (SELECT …) SELECT` 仍豁免；`EXPLAIN ANALYZE DELETE` 拦截 / `EXPLAIN SELECT` 仍豁免；`SELECT INTO OUTFILE` 拦截 / `SELECT INTO @v` 仍豁免）；混合脚本、UNKNOWN、注释内关键字不误判回归。

**T-TR-04（文档回填）**：CHANGELOG [Unreleased] Fixed（确认流只读豁免盲区收窄）、变更记录行、第六轮报告 N-21 状态回填、DBX 清单 #27 落地备注。

## 四、验收标准

1. 三盲区正反例表驱动全绿；既有豁免路径（SELECT/SHOW/EXPLAIN 普通形态）行为逐字节不变（防回归断言）。
2. 消费方三处 diff 为零（豁免收窄只在分类器内部生效）。
3. 词法门控对「注释/字符串字面量内出现写关键字」不误弹（拆句去注释既有能力复用，字符串字面量场景以测试钉住已知边界，未覆盖场景如实登记）。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 预览面板执行 `WITH del AS (DELETE …) SELECT …`：应弹确认而非直接执行；普通 CTE 查询仍直通。
2. `EXPLAIN ANALYZE INSERT …`：应弹确认。
3. 回滚弹窗「执行回滚」链（dmlConfirmed 豁免）不受影响。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 词法宽门控误弹 | 正常 CTE 查询内含写关键字字面量（如列名 `deleted_at`） | 门控按关键字边界匹配非子串；误弹代价=多一次确认（安全侧倾斜可接受），测试钉典型形态 |
| 方言语义差异 | 三盲区行为按 PG/MySQL 主流语义判定，其他方言可能不一致 | 门控按「宁可多确认」方向设计，与方言无关地 fail-closed；真机走查覆盖 PG/MySQL |
| WITH 解析复杂度 | 嵌套 CTE/括号配对词法判定不准 | v1 采用「WITH 开头且正文含写关键字即不豁免」最宽门控，交付后按误弹率再收窄 |

## 七、实施顺序

```
T-TR-01（二级证明纯函数+表驱动）→ T-TR-02（消费方零改动验证）→ T-TR-03（测试扩充）
→ T-TR-04（文档回填）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

**已实施（2026-09-17，dev 分支随「2.2 五计划 + T-PerfSweep4 + 尾巴项」批次）**：

- **T-TR-01**：`SqlTypeClassifier.classify` 命中 QUERY 名单后追加 `proveReadOnly(statement, keyword)` 二级证明——WITH 路径 `words.drop(1)` 出现 DML 字即不豁免；EXPLAIN 路径 ANALYZE|ANALYSE 与 DML 字并存即不豁免（`EXPLAIN (ANALYZE, BUFFERS) DELETE` 变体同拦）；SELECT 路径 `intoWrites` 逐 INTO 判后随 token（OUTFILE/DUMPFILE/标识符→不豁免，@ 会话变量维持豁免，INTO 居尾畸形 fail-closed）。词法门控三件：`maskQuoted`（字符串字面量 '' 转义 + `…"…" 引号标识符整体屏蔽）+ `WORD_REGEX` 字边界（列名 deleted_at 不误判）+ `INTO_WORD_REGEX`（POINT 内含 INTO 不误配）。JSqlParser AST 精确化按计划为可选增强，v1 词法先行未做。
- **T-TR-02**：三处确认流消费方 diff 为零（豁免收窄只在分类器内部，classify 签名不变）；文案零新增。
- **T-TR-03**：`SqlTypeClassifierTest` 新增 19 例——三盲区各正反例（可写 CTE 3 拦+普通 CTE/字面量内关键字/列名 deleted_at 3 放行；EXPLAIN ANALYZE 3 拦+普通 EXPLAIN/ANALYZE SELECT 2 放行；SELECT INTO 4 拦+@var 放行）+ 防回归（普通 SELECT 分类仍 QUERY、可写 CTE 判 UNKNOWN 非 DML、混合脚本、注释内关键字）。
- **T-TR-04**：CHANGELOG [Unreleased] Fixed、变更记录行、第六轮报告 §8 状态回填（随批次统一落）。
- 门禁：SqlTypeClassifierTest 43 例定向绿（含既有 24 例）；全量 testShards 随批次终门禁。
- 真机走查 §五三条留人工。
