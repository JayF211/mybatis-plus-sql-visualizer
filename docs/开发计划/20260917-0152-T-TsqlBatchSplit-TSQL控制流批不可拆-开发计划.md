# T-TsqlBatchSplit T-SQL 控制流批不可拆 — 开发计划

- 日期：2026-09-17 01:52
- 任务编号：T-TsqlBatchSplit（第六轮新建议 **O-28**，P3）
- 来源：第六轮报告 §7 DBX 吸收清单 **#33**——DBX `1a7b44f`：`starts_with_sqlserver_control_flow_batch`（sql.rs:1304）——`IF…ELSE`/`BEGIN…END` 控制流批在语句级拆分中保持整段不可拆，两处调用点（sql.rs:1274/1285）
- 性质：多语句拆分器正确性小件（拆错会把半截控制流送进确认流/执行，产生误导性语法错）
- 实施分支：合流后自 master 拉任务分支 t-tsql-batch-split（前置项 T-MergeDev2Master）
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

`SqlStatementSplitter`（T-SemicolonGuard O-13 收敛的单实现双入口）按分号+字符串/注释状态机拆句，对「一条逻辑语句跨多个分号」的 T-SQL 控制流批无防御：`IF EXISTS(…) BEGIN UPDATE …; END ELSE INSERT …;` 会被拆成碎句——碎片进确认流逐条弹窗（用户看到语法上不成立的半截语句）、进执行直接报语法错。DBX 的修法是批首识别：语句以控制流关键字开头即整批不拆。本插件 SQL Server 用户占比低，但拆分器是确认流与执行链共用地基，正确性小件值得做。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 拆分器 | 单实现双入口（字符串/注释状态机已处理），无控制流批概念 | `util/SqlStatementSplitter` |
| 消费方 | 三处委托（LogToSqlPanel/SqlTypeClassifier/SqlToJavaConverter），收敛后新增规则自动全链生效 | O-13 交付形态 |
| 测试 | SqlStatementSplitterTest 在位可扩充 | `src/test/kotlin/.../util/SqlStatementSplitterTest.kt` |
| T-SQL 方言面 | 七方言 EXPLAIN 含 SQL Server；确认流分类器对 T-SQL 无特化 | — |

## 三、方案

**T-TB-01（批首识别纯函数）**：Splitter 内新增控制流批判定——语句首有效关键字（复用既有去注释取关键字能力）命中 `IF`/`BEGIN`（T-SQL 形态，含 `IF EXISTS`/`IF NOT EXISTS` 前缀形态）时，整批连续吞到控制流闭合（`BEGIN…END` 配对计数；无 BEGIN 的单 `IF` 形态吞到批尾/下一个顶层边界）——与 DBX「批首识别+整段保留」同型。识别不了的形态（配对失衡）**不拆整段保留**（fail-closed：宁整勿碎）。

**T-TB-02（消费方回归核验）**：三消费方零改动；SqlTypeClassifier 对整批 T-SQL 的分类落 UNKNOWN → 需确认（fail-closed 既有行为，测试钉住）；确认弹窗文案对碎片化的消除即本任务用户可见收益。

**T-TB-03（表驱动测试）**：`IF EXISTS(…) BEGIN …; END ELSE BEGIN …; END` 整批不拆；裸 `IF …` 无 BEGIN 形态；嵌套 BEGIN/END 配对；控制流关键字出现在字符串/注释内不误判（既有状态机复用）；非 T-SQL 方言含 `IF` 函数形态（MySQL `IF()`）不误吞。

**T-TB-04（文档回填）**：CHANGELOG [Unreleased] Fixed/Test、变更记录行、第六轮报告 O-28 状态回填。

## 四、验收标准

1. 表驱动全绿；字符串/注释内关键字不误判；MySQL `IF()` 函数形态不误吞（反例必测）。
2. 三消费方 diff 为零，行为收敛在 Splitter 内部。
3. 配对失衡形态整段保留不抛异常。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

SQL Server 数据源（环境受限时以「环境受限未验证」登记）：预览面板执行含 `IF…ELSE` 批的脚本，确认弹窗以整批为单位出现一次而非碎片多次。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| BEGIN/END 配对跨字符串注释 | 计数器被字面量里的 BEGIN 骗过 | 复用既有字符串/注释状态机后再计数 |
| 非 T-SQL 方言误吞 | `IF` 在 MySQL 存储过程外罕见但存在 | 批首识别限定「IF + 条件表达式 + SQL 语句」形态且仅拆分层降级整批，实测 MySQL `IF()` 反例钉住 |
| 整批不拆导致确认粒度变粗 | 用户对整批一次确认而非逐句 | 可接受（碎片逐句确认本就是错误粒度）；fail-closed 方向正确 |

## 七、实施顺序

```
T-TB-01（批首识别）→ T-TB-02（消费方回归核验）→ T-TB-03（表驱动测试）→ T-TB-04（文档回填）
```

## 八、实施记录（实施后回填）

**已实施（2026-09-17，dev 分支随「2.2 五计划 + T-PerfSweep4 + 尾巴项」批次）**：

- **T-TB-01（批首识别纯函数）**：`SqlStatementSplitter.splitInternal` 产出段后过 `mergeControlFlowBatches` 后处理——首段首有效关键字（`firstKeywordOf`：跳空白与行/块注释）命中 **IF** 时整批不拆：①后续以 ELSE 起头的顶层段（含 ELSE IF 链）逐级并入（裸 `IF …; ELSE …;` 形态此前拆出 ELSE 起头碎句）；②批内 BEGIN/END 配对失衡（`beginEndBalance` 字符串/注释感知计数 >0，截断批）继续吞后续段直到配对或批尾。**实施偏离（批首收窄）**：批首限定 IF 不含裸 BEGIN——既有测试「无配对END按顶层切分原样_保守」钉死的 `BEGIN; …; COMMIT` MySQL 事务形态与裸 BEGIN 语义冲突，语句首 IF 无方言歧义（MySQL `IF()` 是函数不出现在语句首位）故收窄；形态良好的 T-SQL BEGIN 块本就由块感知整段保护，不损失覆盖。
- **T-TB-02（消费方回归核验）**：三消费方（LogToSqlPanel/SqlTypeClassifier/SqlToJavaConverter）零改动（合并收敛在 Splitter 内部，既有委托一致性测试复跑绿）；整批 T-SQL 分类落 UNKNOWN → 需确认 fail-closed 既有行为以 `SqlTypeClassifierTest.testTsqlControlFlowBatchFallsToUnknownNeedsConfirm` 钉死。
- **T-TB-03（表驱动测试）**：`SqlStatementSplitterTest` 新增 8 例——双 BEGIN 形态整批、裸 IF…ELSE 跨顶层分号合并、ELSE IF 链、嵌套 BEGIN/END、批后独立语句不误并、配对失衡整段保留不抛异常、字符串/注释内关键字不误判（stripped 口径）、MySQL `IF()` 函数反例不误吞。
- **T-TB-04**：CHANGELOG [Unreleased] Fixed、变更记录行、第六轮报告 §8 状态回填（随批次统一落）。
- 门禁：SqlStatementSplitterTest（含既有 30+ 例）+ SqlTypeClassifierTest + LogToSqlPanelExecuteTest 定向全绿；全量随批次终门禁。SQL Server 真机走查按「环境受限未验证」口径留档。
