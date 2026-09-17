# T-DmlAliasGuard DML 目标表禁别名防回归 — 开发计划

- 日期：2026-09-17 01:50
- 任务编号：T-DmlAliasGuard（第六轮新建议 **O-27**，P3；立项口径据实修正：原表述「生成时按语义位抑制自动别名」——dev 实测 crud_sql/reverse_sql 生成链**零别名生成**（`" AS "` 大小写甄别后零命中），不存在 DBX `9bf52da` 修的那个坑；落点修正为「防御测试锁定现状 + 双轨约定登记」，防止未来重入）
- 来源：第六轮报告 §7 DBX 吸收清单 **#30**——DBX `9bf52da`（closes #9186）：DML 目标位补全抑制自动 `AS xx`（INSERT 目标不收别名、DELETE/UPDATE 目标别名是语法错），语义位区分而非关键字前缀 hack
- 性质：资产生成正确性防回归约定（小件）
- 实施分支：合流后自 master 拉任务分支 t-dml-alias-guard（前置项 T-MergeDev2Master）
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

DBX 刚修的 #9186 是「生成/补全面给 DML 目标表自动加别名导致语法错」——别名只对 SELECT/FROM/JOIN 源合法，INSERT 目标根本不收别名，DELETE/UPDATE 目标带别名是部分方言语法错。本插件生成链现状没有这个坑（核实见 §二），但「没踩过」和「不会再踩」是两回事：crud_sql/reverse_sql/回滚 SQL 三条生成链未来任何重构（比如加格式化、加方言改写）都可能引入别名逻辑。DBX 用语义位模型解决了它，我们用**防回归守卫+成文约定**低成本锁死同一风险——这是 P3 小件的合理形态。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| crud_sql 生成 | INSERT/UPDATE/DELETE/SELECT 四件套直拼表名列名，无别名逻辑 | `mcp/CrudSqlTool.kt`（`" AS "` 零真实命中） |
| reverse_sql 生成 | SQL→MP Java 代码方向，产物为 Java 非 SQL，无别名面 | `mcp/ReverseSqlTool.kt` |
| 回滚 SQL 生成 | UPDATE/DELETE … WHERE 直拼，无别名 | `sql/rollback/RollbackSqlGenerator.kt` |
| 实体右键 CRUD | 同 crud 链 | `actions/GenerateCrudSqlFromEntityAction*` |
| 结论 | 三条 SQL 生成链零别名现状成立；DBX 坑未踩 | — |

## 三、方案

**T-DG-01（守卫测试）**：新增生成产物形态守卫——对 crud_sql 四件套样例（含多表 JOIN 的 SELECT 与单表 DML）、RollbackSqlGenerator 产物断言：**DML 目标位（INSERT INTO / UPDATE / DELETE FROM 后紧跟的表名）与表名之间无 `AS` 别名**；SELECT 源位别名行为不约束（现状就没有，未来若加属新决策）。表驱动覆盖 MySQL/PG/达梦三方言格式化形态（如方言改写引入别名即刻红灯）。

**T-DG-02（约定成文）**：在三条生成链类头注释与本文档登记双轨约定：**别名只允许出现在查询源位（FROM/JOIN），DML 目标位禁别名**——未来任何生成链改动先撞守卫测试。

**T-DG-03（文档回填）**：CHANGELOG [Unreleased] Test 行、变更记录行、第六轮报告 O-27 状态回填。

## 四、验收标准

1. 守卫测试对三条生成链 × 三方言形态全绿；人为注入别名形态能红灯（守卫有效性自证）。
2. 生产行为零改动（本计划只加测试与约定，不改码）。
3. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

不适用（纯守卫与约定，无用户可感知变化；随下次发版真机过一遍 crud_sql 产物即可）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 守卫过窄漏 future 形态 | 未来生成链重构后产物形态变化守卫测不到 | 守卫断言的是「目标位无 AS」这一不变量而非产物逐字节，重构容忍度高 |
| 方言格式化差异 | 关键字大小写/换行导致匹配失灵 | 断言前先过既有格式化归一（或用宽松正则），测试内注明口径 |

## 七、实施顺序

```
T-DG-01（守卫测试）→ T-DG-02（约定成文）→ T-DG-03（文档回填）
```

## 八、实施记录（实施后回填）

**已实施（2026-09-17，dev 分支随「2.2 五计划 + T-PerfSweep4 + 尾巴项」批次）**：

- **T-DG-01**：新增 `generator/DmlAliasGuardTest` 4 用例——①crud 四件套（含逻辑删除变体，`CrudStatement.result.sql` 最终渲染形态）× MySQL/PG/达梦三方言目标位无别名；②RollbackSqlGenerator 三路产物（INSERT→DELETE / UPDATE / DELETE 的 statements + countCheckSql + snapshotSelectSql）无别名；③SELECT 源位（FROM/JOIN 含子查询源）不受约束（未来加别名属新决策）；④守卫有效性自证——INSERT/UPDATE/DELETE 目标位注入 `AS x`（大小写两形态）必红。判定内核：空白归一 + 目标位正则（含引号标识符形态）+ 目标 token 后 `\s+AS\s+\w` 检出，断言不变量非产物逐字节。
- **T-DG-02**：双轨约定成文三处——`CrudSqlGenerator` 类头（约定全文：别名只允许查询源位 FROM/JOIN，DML 目标位禁别名，INSERT 目标不收别名、UPDATE/DELETE 目标别名是部分方言语法错）、`RollbackSqlGenerator` 类头（产物现状零别名 + 引用主约定）、`CrudSqlTool` 类头（MCP 出口覆盖登记）。
- **T-DG-03**：CHANGELOG [Unreleased] Test 行、变更记录行、第六轮报告 §8 状态回填（随批次统一落）。
- 生产行为零改动（只加测试与注释）；门禁：DmlAliasGuardTest 定向绿；全量随批次终门禁。
