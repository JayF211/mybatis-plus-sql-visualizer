# T-SemicolonGuard 字面量分号状态机与切分收敛 — 开发计划

- 日期：2026-09-15 09:05
- 任务编号：T-SemicolonGuard（第四轮新建议 **O-13**，P2，实施中发现可顺带收编 O-13 的「收敛」红利）
- 来源：第四轮竞品调研报告 §7 DBX 吸收清单 #16——DBX `sql_editability.rs` has_top_level_semicolon（quote 栈 + 括号深度状态机），字面量 `'00390360;081111'` 内分号不再误判多语句（7cbd141）；配套「按方言保留必需标识符引号」（28e2568）
- 性质：健壮性收敛（先差分证明、后统一实现）
- 实施分支：基于 dev 开任务分支 t-semicolon-guard
- 验证基线：dev 全量 `./gradlew testShards` 4246/0/0/6 绿

---

## 一、背景与依据

MyBatis 日志还原的参数值大量是业务字符串——订单号、时间串（`'2026-09-15 08:00:00;flag=1'`）、含分号的 JSON。单/多语句判定切错一根分号，轻则预览形态碎裂，重则把半条 SQL 送进执行确认流。DBX 在 2026-09-14 刚为同类问题修过（quote 栈 + 括号深度状态机），其修复样例直接可用作差分测试集。

盘点本插件后发现真正的问题不是「没有状态机」，而是**状态机有三套**：

1. `LogToSqlPanel.splitSqlStatements`（T-PreviewUx-Pack #4 交付）：单/双引号 + 行/块注释 + 反斜杠转义，顶层分号切分——质量高，但**无括号深度、无 BEGIN/END 复合语句感知**；
2. `SqlTypeClassifier.splitStatements`（M36 时代）：独立实现，行为细节与前者未必一致；
3. `RollbackSqlGenerator`：JSqlParser `parseStatements`（注释已声明 parser 权威，不动）。

三套切分并存意味着：同一段文本在不同链路可能得到不同语句数——分类说「单条免确认」而切分切出三条的错位不是不可能。本任务做两件事：把 DBX 的增量语义（括号深度、复合语句）补进来；把 1/2 收敛为单一实现，3 维持 parser 权威不动。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 切分实现 A | splitSqlStatements：状态机含 inSingleQuote/inDoubleQuote/inLineComment/inBlockComment + 反斜杠转义，顶层 `;` 切分，注释随语句保留；无括号深度、无 BEGIN/END | `log/LogToSqlPanel.kt:606-660` |
| 切分实现 B | splitStatements：独立实现（细节待差分），isQuery/getFirstKeyword 消费 | `execute/SqlTypeClassifier.kt` |
| 切分实现 C | JSqlParser parseStatements，注释声明权威 | `sql/rollback/RollbackSqlGenerator.kt:40-48` |
| 消费面 | LogToSqlPanel 执行/预览链、SqlTypeClassifier 确认流、（回归面）RollbackSqlGenerator | 同上 |
| 缺口 | 括号深度（`(1;2)` 形态的存储过程参数?）、BEGIN...END 块内分号、三实现一致性无守卫 | — |

## 三、方案

**T-SG-01（差分测试先行）**：对现有 A/B 两实现写差分测试集——DBX 样例回放（`'00390360;081111'`、块注释内分号、转义引号、空语句段）+ 自建业务样例（日志还原真实形态）。如实记录 A/B 行为差异清单落 §八。**若差分发现行为不一致，先判定哪个是对的（以执行链语义为准），修复属于本任务范围。**

**T-SG-02（统一实现）**：新增 `util/SqlStatementSplitter.kt`（object，纯函数零依赖）：在 A 的状态机基础上补括号深度跟踪与 BEGIN/END 块感知（块内 `;` 不切分，大小写不敏感，字符串内的 BEGIN/END 不算）；A、B 改薄委托到统一实现，各自公开 API 与返回形态不变（消费方零改动）。RollbackSqlGenerator 维持 JSqlParser 不动，KDoc 注明三处分工（UI 链统一 splitter / 回滚链 parser 权威）。

**T-SG-03（行为锁死）**：差分测试集转为守卫测试——统一实现输出与迁移前 A 的输出逐段一致（有 T-SG-01 判定的修复项除外，逐项列明）；SqlTypeClassifierTest/LogToSqlPanelTest 既有断言零回归。

**T-SG-04（文档同步）**：CHANGELOG [Unreleased] Changed（「三套切分收敛为单一状态机」）、变更记录总册行、第四轮报告 §8.4 O-13 状态回填。

## 四、验收标准

1. 差分测试集（DBX 样例 + 业务样例 ≥30 条）在统一实现上全绿，A/B 迁移前后输出一致性有断言锁死。
2. BEGIN/END 与括号深度新增语义有正反例（`BEGIN ...; ... END` 整体一条；字符串内 BEGIN 不触发块态）。
3. 消费方（LogToSqlPanel/SqlTypeClassifier）代码量净减少（删实现换委托），既有测试零回归。
4. 全量 test 绿（基线 4246/0/0/6 + 新增数），跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 日志还原一条含分号参数值（时间串带后缀/JSON）的真实 SQL：预览语句数正确、执行确认流正常。
2. 存储过程调用形态（有环境时）：BEGIN/END 块不被切碎；无环境则如实标注未验证。
3. 多语句确认浮层（T-PreviewUx-Pack #4）对混合样例的候选数与切分一致。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 统一实现引入行为漂移 | A 的既有消费者（多语句确认浮层）依赖注释随语句保留等细节 | T-SG-03 逐段一致性断言；KDoc 把「注释保留」写成契约 |
| BEGIN/END 误判 | MySQL 方言 BEGIN...END 与 Oracle PL/SQL 块形态差异 | 块感知仅在不切分方向起作用（保守：识别不到配对 END 就按顶层切分原样） |
| 差分暴露历史 bug 牵连修复范围 | T-SG-01 可能发现 B 的既有错误行为已被测试锁死 | 以执行链语义为准逐项定夺，修复项在 §八 列明并同步测试 |
| 范围膨胀 | 顺手优化引号方言（反引号/双引号语义按方言不同） | 反引号处理仅与既有 A 实现对齐，不做方言级增强（DBX 28e2568 的「按方言保留引号」属预览渲染链，另行立项） |

## 七、实施顺序

```
T-SG-01（差分测试，暴露差异）→ T-SG-02（统一实现+委托迁移）
→ T-SG-03（守卫锁死）→ T-SG-04（文档同步）→ 真机走查 §五
```

## 八、实施记录（实施后回填；含 T-SG-01 差异清单）

（未实施）
