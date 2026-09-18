# T-DelimiterAware DELIMITER 感知与定位行为核实 — 开发计划

- 日期：2026-09-19 04:55
- 任务编号：T-DelimiterAware（第八轮报告 §9.4 **O-39**，P3；原拟名「DELIMITER 定位锚点核实」——核实后**改名并收窄落点**，见 §一）
- 来源：第八轮报告 §8 DBX 增量清单 **#39**——DBX `4699295`（issue #9485）：光标停在 `delimiter` 行时执行定位错误，修法为「取其后的首条语句，无则取前一条」（`sqlStatementRanges.ts:2208` `mysqlDelimiterDirectiveCursorRange`，集入 `statementRangeAtCursor`）
- 性质：**先核实、后补缺**——插件自研面与 DBX 原生面分离，原建议的落点在核实后需要换位置
- 实施分支：自 master 拉任务分支 t-delimiter-aware
- 验证基线：master（testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿）

---

## 一、背景与依据（现状核实，含两处口径修正）

| # | 原建议口径 | 核实事实 | 影响 |
|---|-----------|---------|------|
| 1 | 「核实插件控制台有无光标定位点，有则补 delimiter 行处理」 | **半对**。插件自研层**无光标定位面**：提交文本恒为整篇文档（预览链 `ui/SqlPreviewPanel.kt:885-887` 取 `document.text`；已备控制台链 `execute/DataSourceSelector.kt:2367`/`:2379`/`:2427` 取 `editor.document.text`；日志链 `log/LogToSqlPanel.kt:216`），grep `selectionModel`/`getSelectedText` 在预览/日志面板 **0 命中**。**但语句级定位实际由 DBX 原生 `findScriptModel(editor, execOption)` 承担**（`DataSourceSelector.kt:2523-2529` 反射调用、实调 `:2568`；`execOption` 取 `DatabaseSettings.getDefaultExecOption()` `:1403-1412`） | 「在控制台链补 delimiter 处理」**可能无从下手**（原生行为不在我方代码里）→ 先做行为核实 |
| 2 | （无此预期） | **插件自研拆分器无 DELIMITER 感知**：`util/SqlStatementSplitter.kt:27`（`split():43`/`splitStripped():50`）的状态机只处理引号/注释/括号深度/`BEGIN…END` 配对/T-SQL `IF` 批首（`:9-23`、`:216-239`），全文无 `DELIMITER` 关键字（grep `-i delimiter src/main/kotlin` 仅命中 `SourceInterceptorTemplates.kt:131-133` 的无关字符串工具） | **这才是插件可控的真实缺口**：粘贴含 `DELIMITER $$` 的存储过程时自研切分会产出碎句 |

**自研拆分器的消费面**（决定缺口影响范围）：`log/LogToSqlPanel.kt:218-250`（多语句勾选浮层）、`execute/SqlTypeClassifier.kt:40`/`:53`（类型分类与确认流）、`mcp/CheckSqlTool.kt:46`（MCP 静态核查）、`execute/ExecutionTimeoutGuard.kt:38`（超时预算语句计数）。**控制台提交链不走它**（走 DBX 原生）。

**诊断探针已在读光标（但不参与决策）**：`DataSourceSelector.kt:1036-1046` 调 `ScriptModelUtil.getSelectedStatementsRange(client, editor, execOption)`，日志打印 `caret=${editor.caretModel.offset}`、`execSelection=…`（`:1172`），但整段在 `LOG.isDebugEnabled` 下（`:1005` 早退）——**只诊断、不决策**。这给「真机核实」提供了现成的取证入口。

**最优落点（若要做提交前预处理）**：`DataSourceSelector.stripGSuffixForSubmit(sqlText, dataSourceUrl)`（`:3332`）+ 纯函数 `execute/MysqlClientGSuffix.kt`（`:47`/`:70`）——`\G` 剥离（T-MysqlGStrip `87b6160`）就在这一层，形态是「提交前预处理 + 回写文档」（`:2367-2374`），有现成测试范式（`MysqlClientGSuffixTest`/`MysqlClientGSuffixSubmitChainTest`）。DELIMITER 处理可挂同族。

## 二、目标与非目标

**目标**：①**核实**控制台在 delimiter 行执行时的实际行为（原生 DBX 负责，须有结论）；②补插件自研拆分器对 `DELIMITER` 指令的感知，消除碎句。

**非目标**：不改 DBX 原生执行语义（不在我方代码里，也不应绕过）；不重造语句定位（原生 `findScriptModel` 已承担）；不动 `\G` 剥离的既有行为。

## 三、方案

**T-DA-01（真机行为核实，先做，可能得出「无需动作」）**：用现成探针（`LOG.isDebugEnabled` 打开 `[M36]` 诊断行）在真机 MySQL 数据源上实录四种光标位置下的执行范围：①光标在 `DELIMITER $$` 行；②光标在指令后的语句内；③光标在 `$$` 结尾行；④光标在普通语句。**产出**：实录 + 判定——若原生已正确处理（DBX 自身支持 delimiter，其 batch/DELIMITER 自动转批在既往调研中已有记载），则本项**结案为「原生已覆盖、无需动作」**，仅登记；若原生确有偏差，则登记为平台侧限制（我方不可修）或按用户决策另议。**这一项是「先证伪再动手」的应用——不先假定缺口存在。**

**T-DA-02（自研拆分器 DELIMITER 感知）**：`util/SqlStatementSplitter.kt` 状态机增 `DELIMITER <token>` 指令识别：识别到指令后，后续语句边界改用该 token（而非 `;`），直到下一条 `DELIMITER` 指令或文本结束；`DELIMITER` 指令行本身**不作为独立语句**产出（当前会自成一段且残留 `$$`）。**约束**：①保持 `split()`/`splitStripped()` 既有签名与「注释随语句保留」语义；②非 MySQL 族方言是否识别（`DELIMITER` 是 MySQL 客户端指令，其他方言无此概念）——建议**无条件识别**（文本层面即可判定，不影响其他方言的正常分号语义，因为只有出现指令才切换）；③`BEGIN…END` 配对与 T-SQL 批首逻辑不得回归。

**T-DA-03（消费面回归）**：四个消费面各补用例——①`LogToSqlPanel` 多语句勾选浮层对含 DELIMITER 脚本的切分正确（不再碎句）；②`SqlTypeClassifier` 对存储过程整体判 `DDL`（而非把 `DELIMITER` 判成未知 + 碎句）；③`mcp/CheckSqlTool` 不再把 `$$` 残留当语句；④`ExecutionTimeoutGuard` 语句计数正确（防预算被碎句放大）。

**T-DA-04（可选，用户定：提交前预处理）**：若 T-DA-01 结论为「原生有偏差且用户希望我方兜底」，则在 `stripGSuffixForSubmit` 同族增 `DELIMITER` 指令剥离/规范化。**默认不做**——控制台链由原生负责，我方插手的收益与风险都需用户确认。

**T-DA-05（文档）**：CHANGELOG + 变更记录 + 第八轮报告 §8 #39 落地备注（含「我方可控面在自研拆分器、控制台链归原生」的口径说明）。

## 四、验收标准

1. T-DA-01 有实录与明确判定（原生已覆盖 / 有偏差 / 不可判），不得以「未验证」含糊结案——**若环境受限确实测不到，按项目惯例显式写「环境受限未验证」并说明缺什么**。
2. `SqlStatementSplitter` 对含 `DELIMITER $$` 的存储过程脚本切分正确：指令行不产出、`$$` 不残留、过程体为一条语句（先红后绿）。
3. 既有切分用例全绿（分号语义、注释保留、`BEGIN…END` 配对、T-SQL `IF` 批首四条不回归）。
4. 四个消费面各有对应用例（T-DA-03）。
5. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工）

1. 真机 MySQL 控制台粘贴含 `DELIMITER $$` 的存储过程 → 四种光标位置执行行为实录（T-DA-01）。
2. 日志面板粘贴同一脚本 → 多语句勾选浮层显示正确（不碎句）。
3. MCP `mpsv_check_sql` 对同一脚本 → 不报 `$$` 残留。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 原生行为不可控 | 控制台链的定位在 DBX 内，我方无法修 | T-DA-01 先核实再定；结论若为平台限制则如实登记（不硬造兜底） |
| 拆分器改动回归 | 状态机是全仓唯一拆分实现，四处消费 | 验收 3 的四条既有语义作回归门；先红后绿 |
| 方言误判 | 非 MySQL 文本含 `DELIMITER` 字样（如注释） | 只在**行首指令形态**识别（对齐 MySQL 客户端语义），注释内不识别 |
| 与 T-TailSweep2 交叠 | 后者 T-FS-* 在 `xml/`、`converter/`、`config/`，与本任务 `util/`、`log/`、`mcp/` 不交 | 仍按并行铁律 check/register |

## 七、实施顺序

```
T-DA-01（真机核实，先做——可能结案）→ T-DA-02（拆分器感知）→ T-DA-03（消费面回归）→ [T-DA-04 用户定] → T-DA-05（文档）
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-DA-01 | （实施后填） | 四种光标位置实录与判定结论（本项结论决定 T-DA-04 是否启动） |
| T-DA-02 | （实施后填） | 状态机改动点与先红后绿实录 |
| T-DA-03 | （实施后填） | 四消费面用例数 |
| T-DA-04 | （实施后填） | 用户决策与（若做）落点 |
| T-DA-05 | （实施后填） | |
