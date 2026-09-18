# T-McpRowBudget MCP 执行工具行数预算可配 — 开发计划

- 日期：2026-09-19 04:47
- 任务编号：T-McpRowBudget（第八轮报告 §9.4 **O-36**，P2）
- 来源：第八轮报告 §8 DBX 增量清单 **#37**——DBX `5278594`：MCP 执行工具加可选 `max_rows`（常量上限 + `clamp(1,1000)` + 默认 100，静态断言恒小于驱动上限；批量执行维持更紧默认 100）
- 性质：**MCP 工具参数面增量**——不改执行语义、不动只读闸门，只把「取多少行」从硬编码变成可配 + 双层钳制
- 实施分支：自 master 拉任务分支 t-mcp-row-budget
- 验证基线：master（testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿；实施时以当次实测为准）

---

## 一、背景与依据（现状核实，含一处口径修正）

| # | 原建议口径 | 核实事实 | 影响 |
|---|-----------|---------|------|
| 1 | 「MCP 执行工具加 max_rows」 | 唯一执行类工具是 `mpsv_explain`（`mcp/ExplainTool.kt:18`），行上限**硬编码**：`ExplainTool.kt:118` 直接传 `McpExplainFormatter.MAX_ROWS`（`mcp/McpExplainFormatter.kt:13` = 200）；工具签名（`ExplainTool.kt:32-35`）只有 `selectSql / dataSource / dialect` 三参，**无 `max_rows`** | 目标工具唯一且明确；改动面小 |
| 2 | 「批量执行维持更紧默认」 | **本插件无对应物**：`McpSelectGuard.kt:78-83` 显式拒绝多语句（`statements.size != 1` → deny），`mpsv_explain` 只接受单条 SELECT | 批量分支**不适用**，立项时显式写明，不硬造 |
| 3 | （无此预期） | **钳制层级当前缺失**：`McpDbExecutor.kt:120` 的 `maxRows` 参数被**完全信任**——`collect()` 在 `:221` 直接按传入值截断（`if (rows.size >= maxRows)`），内部零 clamp | 需双层钳制，见 §三 |

**现成范式（本仓已有一例，照抄即可）**：`mcp/McpSchemaFormatter.kt:40-44` `clampMaxTables(requested: Int?): Int`（null/非正 → 默认 8；超上限 → 20；否则原样）+ `SchemaContextTool.kt:30` 参数默认值 `maxTables: Int = McpSchemaFormatter.DEFAULT_MAX_TABLES` + `:31` 内调用 clamp。这正是 DBX #37 的「默认值 + 常量上限钳制」形态。

**预算登记表（硬约束）**：`mcp/McpToolsetSupport.kt:21-31` 的 KDoc 是十工具输出预算的**集中登记处**，其中 `:26-27` 明写 `mpsv_explain` 行 ≤200、单元格 2000 字符；同处 KDoc 声明「新增工具或调整预算必须在此登记对齐」——改预算必须同步该处。

**其他相关常量**：`McpExplainFormatter.kt:16` `MAX_CELL_CHARS = 2000`；`:57` 的 `truncated` 提示文案**硬引用了 `MAX_ROWS`**，改名须同步。

## 二、目标与非目标

**目标**：`mpsv_explain` 的行数上限可由调用方按需指定，且无论传入什么值都不会突破通道硬上限。

**非目标**：不做批量分支（单语句工具，见 §一 #2）；不改 `mpsv_schema_context` 等其余工具的既有预算（除非顺带统一命名，那属可选）；不动 `McpSelectGuard` 只读闸门；不动超时参数（`timeoutSeconds` 另有其面）。

## 三、方案

**T-MRB-01（常量更名与分层）**：`McpExplainFormatter.MAX_ROWS = 200` 更名 `DEFAULT_MAX_ROWS`（值不变，保持默认行为逐字节一致），新增 `HARD_MAX_ROWS`（建议 1000，对齐 DBX 常量）。同步 `:57` 提示文案对 `MAX_ROWS` 的引用。**注意**：改名会触碰既有测试对常量名的引用，需一并更名（grep 全仓 `MAX_ROWS`）。

**T-MRB-02（钳制纯函数）**：新增 `McpExplainFormatter.clampMaxRows(requested: Int?): Int`，形态照抄 `clampMaxTables`（`McpSchemaFormatter.kt:40-44`）：null/非正 → `DEFAULT_MAX_ROWS`；超 `HARD_MAX_ROWS` → `HARD_MAX_ROWS`；否则原样。放在纯逻辑层（可裸 JUnit 直测，进 `testFast` 桶）。

**T-MRB-03（工具参数面）**：`ExplainTool` 增第 4 个可选参数 `maxRows: Int = McpExplainFormatter.DEFAULT_MAX_ROWS`（默认值写进签名，与 `SchemaContextTool.kt:30` 同规），`:118` 传 `clampMaxRows(maxRows)`；`@McpDescription`（`ExplainTool.kt:19-26`）补 `max_rows` 说明——DBX #37 亦要求「工具描述与文档同步」，本仓对应落点是 `@McpDescription` 与 `src/main/resources/html/marketplace-custom-page.html` 的 MCP 行（若该行列举了工具参数）。

**T-MRB-04（通道层兜底）**：`McpDbExecutor.executeQuery`（`:120`）内部对 `maxRows` 再做一次 clamp（或加静态断言 `maxRows <= HARD_MAX_ROWS`）。**依据**：`McpSelectGuard.kt:10` KDoc 已宣示该通道是「未来任何执行类工具共用」——工具层钳制 + 通道层兜底双保险，才与 DBX「静态断言恒小于驱动上限」的护栏语义对齐；避免未来新增执行工具时漏钳制。

**T-MRB-05（预算登记与测试）**：`McpToolsetSupport.kt:21-31` 登记表同步（`mpsv_explain` 行改为「默认 200 / 上限 1000」）。用例：①`clampMaxRows` 边界表驱动（null / 0 / 负数 / 1 / 200 / 1000 / 1001 / Int.MAX_VALUE）；②`ExplainTool` 传 `maxRows=10` 时实际取回 ≤10 行且 `truncated` 正确；③不传参时行为与改前**逐字节一致**（默认 200）；④通道层兜底：绕过工具层直接调 `McpDbExecutor` 传超大值仍被钳制。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第八轮报告 §8 #37 落地备注。

## 四、验收标准

1. `mpsv_explain` 不传 `maxRows` 时行为与改前逐字节一致（默认 200，`truncated` 提示文案不变）。
2. 传入合法值（如 10）时按该值截断；传入越界值（0/负/超大）时钳到默认或硬上限，且**不抛错**。
3. 通道层兜底生效：直接调 `McpDbExecutor` 传 `Int.MAX_VALUE` 仍被钳制（用例 ④）。
4. `McpToolsetSupport.kt` 登记表与实际常量一致（防登记漂移）。
5. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工）

1. 真机 MCP 调用 `mpsv_explain` 不传 `max_rows` → 返回行数与截断提示与改前一致。
2. 真机传 `max_rows=10` → 返回 ≤10 行；传 `max_rows=100000` → 返回 ≤1000 行且无异常。
3. MCP 客户端（IDE 内 agent 面板）能看到新参数的 schema 描述。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 常量改名波及面 | `MAX_ROWS` 可能被测试或多处引用 | 实施前 grep 全仓引用点，一并更名；用例 1 钉住默认行为不变 |
| 参数面破坏兼容 | 新增参数改变 MCP 工具 schema | 可选参数 + 默认值，旧调用方零感知（`SchemaContextTool` 已有同规先例） |
| 钳制语义分歧 | 「越界」是抛错还是钳制 | 定案**钳制不抛错**（对齐 `clampMaxTables` 既有语义，工具面报错会打断 agent 流程） |
| 登记表漂移 | 改了常量忘了登记 KDoc | 用例 4 断言一致 |

## 七、实施顺序

```
T-MRB-01（常量分层与更名）→ T-MRB-02（clamp 纯函数）→ T-MRB-03（工具参数面）→ T-MRB-04（通道兜底）→ T-MRB-05（登记与测试）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-MRB-01 | （实施后填） | 更名波及点清单须回填 |
| T-MRB-02 | （实施后填） | |
| T-MRB-03 | （实施后填） | 市场页是否需同步须回填 |
| T-MRB-04 | （实施后填） | 兜底形态（clamp vs 静态断言）结论须回填 |
| T-MRB-05 | （实施后填） | 门禁计数须回填 |
