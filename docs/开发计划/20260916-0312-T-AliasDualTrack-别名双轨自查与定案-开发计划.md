# T-AliasDualTrack 别名双轨自查与定案 — 开发计划

- 日期：2026-09-16 03:12
- 任务编号：T-AliasDualTrack（第五轮新建议 **O-18**，P2；自查定案型任务，预期小）
- 来源：第五轮报告 §7 DBX 吸收清单 **#22**——DBX 8373f15（修 issue #9173）：copy-INSERT 列名清单用结果显示名（含别名），元数据过滤（生成列剔除/主键判定）用 source_columns 实名，两轨分离互不污染（data_grid_sql.rs:531，测试 :4030）。报告口径：先自查 crud_sql/reverse_sql 别名场景，踩坑升 P0，未踩坑登记为生成约定
- 性质：自查 + 防御测试锁定（大概率「无坑定案」收尾）
- 实施分支：自 master 拉任务分支 t-alias-dual-track
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

DBX 在「从查询结果生成 INSERT」场景踩了别名坑：展示别名进了列名清单、元数据判定却用实名，两轨混用导致生成语句列名错乱。本插件的对应面是 MCP 的 crud_sql 与 reverse_sql。经 dev 锚点核实，**大坑不存在的证据已经比较硬**：crud_sql 只从 MP 实体类（PSI）生成、没有查询结果/SELECT 入口（入参仅 filePath/className）；reverse_sql 用自研解析器把「实名与 AS 别名」保留为两个独立字段，lambda 形态生成 `select(实名ref, "别名")`——双轨分离是 T-M46 的设计遗产。剩余风险收窄到两处回落路径：SELECT 列为表达式/计算列时实体映射失败整串回落字符串（可含表别名前缀）、rawMode 的 `"expr AS alias"` 整串形态。本任务把这两处钉死为「有意行为」并上防御测试，形成定案记录。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| crud_sql 列来源 | 仅 PSI 实体适配（GenerateCrudSqlFromEntityAction.entitySpec），无 SELECT/查询结果入口——DBX 坑的前提不存在 | `mcp/CrudSqlTool.kt:54-83`（入参 `:44-47`） |
| reverse_sql 别名双轨 | SelectColumn(expr 原文, alias) 两字段分离；别名捕获/consumeSelectAlias | `mcp/reverse/SqlReverseParser.kt:1319,470-491` |
| 生成端分流 | lambda 模式 `selectAs(实名ref, 别名)`（VO 形态同构）；rawMode 整串保留 | `mcp/reverse/JavaCodeGenerator.kt:871-888`（rawMode `:872-875`） |
| 实名归属 | renderSelectRef 拆别名前缀/剥反引号；resolveColumn 歧义返回 null 回落字符串 | `mcp/reverse/JavaCodeGenerator.kt:829-861` |
| 回落风险点 | 表达式列实体映射失败 → 整串回落（可含表别名前缀）；未映射列回落字符串实名 | `mcp/reverse/JavaCodeGenerator.kt:843-850` |

## 三、方案

**T-AD-01（逐路径定案审查）**：按「lambda 生成/rawMode 回落/表达式列回落/WHERE·ORDER BY 列（用实名 ColumnRef，SqlReverseParser.kt:1202-1203）」四路径逐条断言实名/别名归属，产出定案记录（无坑/有坑 + 证据行号）写入实施记录。

**T-AD-02（防御测试）**：表驱动锁定——`SELECT psn_no AS psn FROM t` 生成 `select(PsnNo, "psn")` 类 lambda 形态；表达式列 `SELECT a+b AS total` 回落形态断言（字符串实名与别名分离呈现）；WHERE/ORDER BY 混用别名列时取实名。若审查发现实际行为与预期不符 → 转 T-AD-03。

**T-AD-03（条件分支：踩坑修复）**：仅 T-AD-02 抓到实错时启用——按 DBX 双轨原则修（展示名与实名分离传递，元数据判定走实名），并把本任务优先级升 P0 重排；未触发则本子任务作废留档。

**T-AD-04（文档同步）**：定案结论回填第五轮报告 O-18（「未踩坑/已踩坑」二选一）；CHANGELOG 仅在踩坑修复时记 Fixed。

## 四、验收标准

1. 四路径定案记录完整（含证据行号），O-18 在报告中可关闭。
2. 防御测试表驱动全绿，回落路径行为被断言锁定（后续重构碰坏即红）。
3. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. MCP reverse_sql 传一条含 `AS` 别名与表达式列的真实 mapper SQL：生成 Java 的 select 列实名/别名形态符合定案记录。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 自查流于形式 | 「看起来没问题」不等于锁死了 | 防御测试是硬产出；无测试不得关单 |
| rawMode 被误判为坑 | 整串保留是有意行为（用户要求原样） | 定案记录注明「有意行为，非缺陷」，测试按现状锁 |

## 七、实施顺序

```
T-AD-01（定案审查）→ T-AD-02（防御测试）→（条件）T-AD-03 → T-AD-04（回填）
```

## 八、实施记录（实施后回填）

（未实施）
