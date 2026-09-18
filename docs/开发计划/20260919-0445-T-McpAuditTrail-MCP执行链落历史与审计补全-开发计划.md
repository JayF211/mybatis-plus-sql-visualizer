# T-McpAuditTrail MCP 执行链落历史与审计补全 — 开发计划

- 日期：2026-09-19 04:45
- 任务编号：T-McpAuditTrail（第八轮报告 §9.3 **N-29**，P1；原拟名「MCP 双通道审计闭环」——核实后改名收窄，见 §一 口径修正）
- 来源：第八轮报告 §8 DBX 增量清单 **#38**——DBX 的 MCP 通道执行 SQL 后落历史（HistoryEntry 带 `source:"mcp"`、耗时、成败、error），前端容错识别 + 历史列表显「MCP」徽标，风险类别复用既有分类器（DBX `0f6db4b` + `5465295`）
- 性质：**审计可见性 + 证据链补全**——不新增执行能力，把已发生的执行与调用如实留痕
- 实施分支：自 master 拉任务分支 t-mcp-audit-trail
- 验证基线：master（T-TailSweep2 交付后，testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿；实施时以当次实测为准）

---

## 一、背景与依据（现状核实，含四处口径修正）

立项前的三路锚点核实推翻了原始建议的四处前提，**本计划的形态按核实结果重写**：

| # | 原建议口径 | 核实事实 | 影响 |
|---|-----------|---------|------|
| 1 | 「MCP **十工具**的执行类调用落历史」 | **十个工具里只有 1 个有执行面**：`mpsv_explain`（`mcp/ExplainTool.kt:18`）。其余九个为纯静态分析（PSI/JSqlParser/文本），`mcp/CheckSqlTool.kt:14-16` KDoc 明示「纯文本判定，绝不执行」 | 范围从「十工具」收窄为「执行类工具（当前仅 `mpsv_explain`）」 |
| 2 | 落「执行过的 SQL」 | 执行的不是用户原句，而是 `DialectRegistry.byName(dialectName).renderExplainStatement(selectSql)` 包出的 `EXPLAIN <select>`（`ExplainTool.kt:102`），原始 `selectSql` 只作参数存在、当前零留痕 | 落历史必须落**原始 `selectSql`**，见 §三 T-MAT-02 |
| 3 | 复用既有「来源」字段即可 | 历史模型有**两套**：预览历史 `SqlHistoryEntry`（`history/SqlHistoryEntry.kt:22-33`，**无任何 source 字段**）与日志历史 `LogSqlEntry`（`log/LogSqlModels.kt:26-115`，**已有** `SqlSource` 枚举 `:50` = MYBATIS/HIBERNATE/JDBC/AGENT/UNKNOWN）。但后者**根本不持久化**：`.sql` 头部 `source:` 段被 `SqlLogFileReader.kt:241-249` `parseSource()` 占用为「源码文件:行号」，读回路径 `:195-215`/`:218-236` **从不设置 `source` 字段** → round-trip 后必然退化为 `UNKNOWN` | 「加个枚举值」不够，必须同时解决持久化通道，见 T-MAT-03 |
| 4 | 「每调用已有 `[MCP-AUDIT]` 审计」 | 审计行存在但**不含入参**：`mcp/McpToolsetSupport.kt:68` 成功路径把字面量 `"ok"` 当 `argsSummary` 传入，`mcp/McpAuditLog.kt:24` 的 `summarize(vararg pairs)` 工具函数**全仓零调用点**（grep 仅定义处） | 「审计闭环」的一半是空的，须补入参接线，见 T-MAT-04 |

**现成资产（决定本计划成本低的原因）**：

- **执行链已有单点入口**：`mcp/McpDbExecutor.kt:120` `executeQuery(project, dataSource, sql, maxRows, timeoutSeconds)`，`:151` `REMOTE_STATEMENT.executeQuery(sql)`；全仓唯一调用点是 `ExplainTool.kt:117-121`。`McpSelectGuard.kt:10` KDoc 已把该通道定义为「mpsv_explain / **未来任何执行类工具共用**」——挂这一层，未来新增执行工具自动获得历史。
- **面板已有徽标渲染范式**：`log/HistorySqlLogPanel.kt:858-859` 目标库角标、`:855` 时间戳、`:856` 耗时、`:860` 来源、`:850-851` 行号，全部走 `log/SqlLogPanel.kt` 的 `internal` 片段工厂（`formatDatabaseFragment:1959`、`formatUpdateCountFragment:1974`、`formatErrorFragment:1984`、`formatSourceFragment:1993`），协议统一为 `Pair<String, SimpleTextAttributes>?`。加「MCP」徽标是**填空式增量**。
- **扩展载荷通道已存在且容错**：`-- pd: <base64> --` 行（写端 `log/SqlLogAutoSaver.kt:371-375`、编解码 `log/SqlLogParamCodec.kt:72`/`:119`），`decode` 全程 `runCatching` 失败返 null、读端降级为普通注释行（`SqlLogFileReader.kt:175-179`）。
- **分类器已是多消费方成熟资产**，无需新建：风险/类型面 `execute/SqlTypeClassifier.kt:67` `classify(): SqlType`（QUERY/DML/DDL/UNKNOWN，含只读二级证明 fail-closed `:96-108`）；渲染染色面 `log/LogStatementClassifier.kt:30` `classify(): StatementType`。

**两条硬约束**：

1. `src/test/kotlin/.../log/SqlLogHistoryRoundTripTest.kt:68` 断言 `.sql` 头部**逐字符相等**——任何新增头部段都会打断该测试；走 `pd:` 载荷则不打断。
2. 染色链对 `OTHER` 返回 null（`log/SqlListTypeColor.kt:103`）→ 若落 `EXPLAIN` 包装句，首 token 为 `EXPLAIN` 落入 `OTHER`（`LogStatementClassifier.kt:47`）→ **无染色**，#38 宣称的「风险类别复用分类器」当场失效。

## 二、目标与非目标

**目标**：`mpsv_explain` 的每次真实执行在 IDE 内留下可查痕迹（历史面板可见、带来源徽标、带耗时与成败），且 MCP 审计行带上真实入参摘要。

**非目标**：不给其余九个工具造历史（它们不执行，无历史可言）；不新建独立 MCP 历史存储（`SqlHistoryManager` 的面板不读它、`HistorySqlLogPanel` 只读 `.sql` 文件，另起一套会让徽标无处渲染）；不改执行语义与门控（`McpSelectGuard` 强只读闸门原样）；不动 MCP 工具签名（参数面变更归 T-McpRowBudget）。

## 三、方案

**T-MAT-01（执行链落历史，回调注入形态）**：`McpDbExecutor` 暴露 `onExecuted` 钩子（执行完成/失败后回调，携带原始 sql、耗时、成败、errorText、dataSource 名），由 `ExplainTool` 侧接线到 `log/SqlLogManager` 的既有入库路径。**理由**：`McpDbExecutor` 是纯反射、无 IDE 服务依赖的层（headless 可测性靠这个），直接依赖 `SqlLogManager` 会污染该边界。异常路径的 `SQLException` 文本已有可采点（`McpDbExecutor.kt:158-173`）。

**T-MAT-02（落原始 SQL，不落 EXPLAIN 包装句）**：入库内容为原始 `selectSql`；是否已 EXPLAIN 包装作为独立标记记录（不体现在 SQL 文本里）。**依据**：落包装句会让 `LogStatementClassifier` 判 `OTHER` → `SqlListTypeColor:103` 返回 null → 无染色（§一 硬约束 2）。

**T-MAT-03（`SqlSource.MCP` + 持久化走 pd 载荷）**：
- `log/LogSqlModels.kt:6-17` 枚举增 `MCP` 常量（零破坏，默认值仍 `UNKNOWN`）。
- **持久化不走头部 `source:` 段**（该段语义已被 `parseSource` 占用为源码位置）——走 `pd:` 载荷：`SqlLogParamCodec.kt` 增短键、`DecodedPayload` 加字段、读端 `SqlLogFileReader.kt:206-215` 回填。依据：`decode` 失败降级、旧文件无该键自然为 null、不改 `countInFile`（`:83-101` 只数头部行）、不打断 `SqlLogHistoryRoundTripTest.kt:68`。
- **配套必改**：`SqlLogAutoSaver.kt:371-374` 的 pd 写入条件当前只判 params/占位符/六个扩展字段——须追加「`source == MCP`」，否则一条无参数、无占位符、无 mapperStatement 的 MCP SELECT 会**丢掉来源标记**（这正是本任务的存在意义）。
- 向后兼容：旧版读到带 `MCP` 的新文件不受影响（未知键进 `DecodedPayload` 可选字段）；新版读旧文件 `source` 仍为 `UNKNOWN`（与现状一致）。

**T-MAT-04（审计行补真实入参）**：`McpToolsetSupport.kt:68` 把字面量 `"ok"` 换成经 `McpAuditLog.summarize`（`:24`，当前零调用）产出的摘要——工具名之外的 `dataSource` / `dialect` / SQL 首段（受既有 `SUMMARY_LIMIT=120` 截断）。**脱敏口径**：只记 SQL 文本摘要，不记参数值（对齐 T-RA 系列「不落异常 message」的脱敏惯例）；失败路径补记 errorText 类型而非原文全文。

**T-MAT-05（面板徽标）**：新增 `formatMcpFragment`，照 `formatDatabaseFragment`（`SqlLogPanel.kt:1959`）的 `Pair<String, SimpleTextAttributes>?` 协议，插在 `HistorySqlLogPanel.kt:858-859`（目标库角标）之后、`:860`（来源）之前。未知/非 MCP 返回 null 不显示（与目标库角标同规）。日志页同类渲染器（`SqlLogPanel.kt:2080-2090`）是否同步：先核实该处渲染的是否同一模型，是则一并接。

**T-MAT-06（测试与文档）**：用例覆盖——①`mpsv_explain` 执行后历史出现条目且 `source=MCP`；②round-trip 后 `source` 仍为 `MCP`（钉住 T-MAT-03 的持久化，这是当前 `SqlSource` 的既有缺陷面）；③无参数/无占位符场景 pd 行仍写出（钉住 T-MAT-03 的配套必改）；④染色：MCP 条目的 `statementType` 不为 `OTHER`（钉住 T-MAT-02）；⑤审计行含入参摘要、`summarize` 不再是死代码（补消费点守卫）；⑥失败路径落 `errorText`。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第八轮报告 §8 #38 落地备注。

## 四、验收标准

1. `mpsv_explain` 成功执行一次 → 历史面板可见该条、带 MCP 徽标与耗时；IDE 重启后仍在（持久化成立）。
2. `source=MCP` 经 `.sql` round-trip 后**不退化**为 `UNKNOWN`（对照当前 `SqlSource` 的既有缺陷，先红后绿）。
3. 无参数、无占位符的 SELECT 场景来源标记不丢（T-MAT-03 配套必改的专属用例）。
4. 其余九个工具行为逐字节不变（无历史写入、审计行格式除入参段外不变）。
5. `SqlLogHistoryRoundTripTest` 等既有头部断言测试全绿（未走头部段）。
6. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工）

1. 真机 `mpsv_explain` 执行 → 历史面板出现条目、徽标与耗时正确；重启 IDE 后条目仍在且徽标未退化为无。
2. 无参数 mapper 的 SELECT 走 MCP EXPLAIN → 来源标记保留。
3. idea.log 的 `[MCP-AUDIT]` 行含 dataSource/dialect/SQL 摘要，且摘要已截断（不超 120 字符）。
4. 历史面板筛选（类型过滤）对 MCP 条目行为正常（不因 `source` 新值异常）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 跨层依赖污染 | `McpDbExecutor` 引入 IDE 服务依赖会破坏其 headless 边界 | 走 `onExecuted` 回调注入（T-MAT-01），依赖留在工具层 |
| 落历史写入性能 | 每次 EXPLAIN 多一次磁盘写 | 复用 `SqlLogAutoSaver` 既有节流/批量路径，不新开写通道 |
| 头部格式硬门禁 | 误改头部段会打断 `RoundTripTest:68` | 明确走 `pd:` 载荷；用例 5 作为回归门 |
| 旧版兼容 | 新版文件被旧版插件读 | 未知 pd 键在旧读端降级为注释行（既有机制），SQL 正文无损 |
| 染色失效 | 落包装句导致无染色 | T-MAT-02 落原始 SQL + 用例 4 钉住 |

## 七、实施顺序

```
T-MAT-02（落原始 SQL 口径先行，决定下游）→ T-MAT-01（回调钩子 + 接线）→ T-MAT-03（枚举 + pd 持久化 + 配套必改）→ T-MAT-05（徽标）→ T-MAT-04（审计入参）→ T-MAT-06（测试与文档）→ 真机走查 §五
```

T-MAT-04 与 01/03/05 写集不相交（`mcp/McpToolsetSupport.kt` / `mcp/McpAuditLog.kt` vs 其余），可并行；T-MAT-03 与 T-MAT-05 同触 `log/` 域但不同文件（`SqlLogParamCodec`/`SqlLogFileReader`/`SqlLogAutoSaver` vs `SqlLogPanel`/`HistorySqlLogPanel`）。

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-MAT-01 | （实施后填） | 回调签名与 headless 可测性结论须回填 |
| T-MAT-02 | （实施后填） | |
| T-MAT-03 | （实施后填） | pd 短键命名与 `SqlLogAutoSaver` 条件改动须回填 |
| T-MAT-04 | （实施后填） | `summarize` 接线后的截断实测须回填 |
| T-MAT-05 | （实施后填） | 日志页是否同步的核实结论须回填 |
| T-MAT-06 | （实施后填） | 门禁计数与先红后绿实录须回填 |
