# T-StatementNaming 语句注释命名标注 — 开发计划

- 日期：2026-09-16 03:14
- 任务编号：T-StatementNaming（第五轮新建议 **O-19**，P2；**口径经 dev 锚点核实重定形**）
- 来源：第五轮报告 §7 DBX 吸收清单 **#23**——DBX e561bd15：结果页签按 SQL 注释命名（`-- Name: x` 优先 → 最近一条非空行注释回退 → 块注释剔除，queryResultSource.ts:11~25）。报告原文设想挂「多结果页快照」，实测本插件结果页签是 DBN 原生（displayName 只读、不可命名），故重定形为「语句注释命名标注」：落在还原产物组头与 MCP 多语句输出上
- 性质：可读性增强（纯文本形态，无 UI 结构改动）
- 实施分支：自 master 拉任务分支 t-statement-naming
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

调试长脚本/mapper 多语句时，「第 3 条语句」远不如「分页查询」好认。DBX 用前导注释给结果页签命名，本插件经锚点核实有两个可比对的挂接面：①日志还原面板的多语句组间已有 `-- SQL #N` 注释头（原生控制台接受注释，现成先例）——组头可升级为 `-- SQL #N [名称]`；②MCP 四个多语句生产者（render_logs/rollback_plan/crud_sql/mapper_check）的语句分隔头同样可附名称。DBN 原生页签命名经核实不可行（pickResultTab 仅判新页签，displayName 由 DBN 控制），该面定案「不做自绘网格」（红线：不做连接管理/数据管理工具化）。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 还原组头 | splitSqlStatements 组间带 `-- SQL #N` 注释头 | `log/LogToSqlPanel.kt:610-620` |
| MCP 分隔头 | render_logs（`-- SQL #`）与 rollback_plan 硬拼；crud_sql（`-- N. KIND`）/mapper_check（`-- statement #N: id [KIND]`）变体 | `mcp/RenderLogsTool.kt:89-95`、`mcp/RollbackPlanTool.kt:59-60`、`mcp/CrudSqlTool.kt:95-98`、`mcp/MapperCheckTool.kt:93-97` |
| DBN 页签 | 原生 displayName 只读，插件仅判新页签并 selectAndFocus | `execute/DataSourceSelector.kt:2628-2655,2738-2748` |
| 缺口 | 无任何「从 SQL 前导注释提取名称」逻辑 | — |

## 三、方案

**T-SN-01（名称提取纯函数）**：新增 `util/SqlStatementNamer.kt`——前导注释区 `-- Name: x` 优先（大小写不敏感）→ 回退最近一条非空行注释 → 块注释与 `<!-- -->` XML 注释剔除；截断（名称 ≤40 字符）；无注释返回 null。DBX 三规则对齐 + XML 注释方言扩展。

**T-SN-02（还原组头接线）**：LogToSqlPanel 组头升级为 `-- SQL #N [名称]`（无名称维持现状逐字节不变）；下游消费方（确认浮层候选行、SqlTypeClassifier）不受影响（组头是注释，解析层本就忽略）。

**T-SN-03（MCP 语句标注接线）**：RenderLogsTool/RollbackPlanTool 的 `-- SQL #N` 尾附 ` [名称]`；CrudSqlTool/MapperCheckTool 变体格式同规则附名（保持各自前缀风格）；无名称零差异。

**T-SN-04（i18n + 测试 + 文档）**：表驱动——Name 优先/行注释回退/块注释剔除/XML 注释/截断/无注释 null 与输出零差异断言；CHANGELOG [Unreleased] Added、变更记录行、第五轮报告 O-19 状态回填（含 DBN 页签面定案说明）。

## 四、验收标准

1. 无注释 SQL 的全部输出路径逐字节不变（防回归断言——这是硬门禁，还原产物被下游与执行链消费）。
2. 名称提取表驱动全绿（含 `-- NAME:` 大写、行内注释不误取、块注释剔除）。
3. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 还原一段带 `-- Name: 分页查询` 前导注释的多语句 mapper：组头显示名称，逐条执行不受注释影响。
2. MCP render_logs 同款输入：语句头附名。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 组头变更破坏下游 | 组间注释被执行链/分类器消费 | 注释形态下游本就忽略；零差异断言兜底（无名称场景逐字节不变） |
| 注释语义误取 | 行内尾注释 `SELECT 1 -- 分页` 不是语句名 | 只取前导注释区与整行注释；表驱动锁行为 |
| MCP 输出膨胀 | 名称过长挤占 token | ≤40 字符截断（计数后缀可见） |

## 七、实施顺序

```
T-SN-01（提取纯函数+表驱动）→ T-SN-02（还原组头）→ T-SN-03（MCP 四处接线）
→ T-SN-04（测试+文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

（未实施）
