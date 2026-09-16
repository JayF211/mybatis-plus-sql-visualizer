# T-McpOutputBudget MCP 输出预算统一 — 开发计划

- 日期：2026-09-16 03:16
- 任务编号：T-McpOutputBudget（第五轮新建议 **O-20**，P2 小件；**口径经 dev 锚点核实重定形**）
- 来源：第五轮报告 §7 DBX 吸收清单 **#24**（大结果序列化免二次拷贝，f1c17859）。报告设想「ResultSet→List→JSON 双遍变换流式化」**不成立**——dev 实测 MCP 十工具输出全是手工 buildString 纯文本，无 JSON 序列化面；真实缺口是**预算口径不一**：SchemaContextTool（单元格 200 字符/表 ≤20）与 ExplainTool（MAX_ROWS 200/单元格 2000）有预算，PerfAdviceTool 无上限（20 条规则警告数不设限、单条文案不截断）
- 性质：MCP 输出预算补齐（agent token 成本防护）
- 实施分支：自 master 拉任务分支 t-mcp-output-budget
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

MCP 工具输出直接进 agent 上下文，无上限的输出是 token 成本与上下文污染源。DBX 的教训（Issue #7548 批执行数组返工）与本插件 N-16 的 structuredContent 核查同属「输出形态防护」家族：预算不是功能是卫生。本插件已有两个预算先例（Schema/Explain），PerfAdvice 是唯一漏网——规则引擎 20 条规则理论上可产出 20 条不设限的长文案。本任务补齐这一处，并把全部工具的预算口径落成文档（新增工具时的对齐基准），DBX #24 的「单遍序列化」精神以「预算先行」形式落地。

## 二、现状核实（dev 实测）

| 工具 | 预算现状 | 位置 |
|---|---|---|
| SchemaContextTool | 单元格 200 字符截断 + 表 ≤20（maxTables 默认 8） | `mcp/McpSchemaFormatter.kt:87-97` |
| ExplainTool | MAX_ROWS=200、单元格 2000 | `mcp/McpExplainFormatter.kt:13,16` |
| **PerfAdviceTool** | **无上限**：逐条 `[SEVERITY] ruleId (line N): 文案`，20 规则全量输出、文案不截断 | `mcp/PerfAdviceTool.kt:45-54` |
| 其余七工具 | 输出由输入规模决定（渲染=源码规模、logs=日志条数、crud/reverse=单实体），天然有界 | — |
| 统一包装 | 无统一输出长度包装（audit 只记日志摘要） | `mcp/McpToolsetSupport.kt:42-52`、`mcp/McpAuditLog.kt:19-21` |

## 三、方案

**T-MB-01（PerfAdvice 预算）**：建议条数上限（默认 30，超出显示「…等 N 条」计数后缀恒可见）+ 单条文案截断（对齐 MAX_CELL_CHARS 口径，截断提示）；SEVERITY 排序保留（高危不因截断丢失——ERROR > WARN > INFO 先裁后者）。

**T-MB-02（预算口径文档化）**：各工具输出预算表落入代码注释（McpToolsetSupport 类注释集中登记：工具名/预算/常量位置），新增工具时对齐有据；登记位守卫注释防漏（对齐 MetadataInvalidator 登记表先例）。

**T-MB-03（测试 + 文档）**：表驱动——30 条截断计数、SEVERITY 优先保留、单条截断提示、少于此数零差异；CHANGELOG [Unreleased] Changed 1 条、第五轮报告 O-20 状态回填（含重定形说明）。

## 四、验收标准

1. 少于上限时 PerfAdvice 输出逐字节不变（防回归断言）；超限时计数后缀恒可见。
2. 高危（ERROR 级）建议不因预算截断丢失。
3. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. MCP perf_advice 跑一条问题多的慢 SQL：输出截断形态与计数正确，ERROR 级在前。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 截断丢关键建议 | 预算把有用信息裁掉 | SEVERITY 排序裁剪 + 计数后缀；阈值 30 宽于正常产出 |
| 预算常量散落 | 各工具各一套，口径漂移 | T-MB-02 集中登记表 + 注释交叉引用 |

## 七、实施顺序

```
T-MB-01（PerfAdvice 预算）→ T-MB-02（口径登记）→ T-MB-03（测试+文档）
```

## 八、实施记录（实施后回填）

**2026-09-17 实施完毕（提交 ced7041，dev 分支直接交付）**

- **T-MB-01**：PerfAdviceTool 补齐全链最后一个无上限工具——条数预算 30（MAX_FINDINGS，超限按 SEVERITY 等级序裁剪 ERROR > WARN > INFO，PerfSeverity 声明序升序即高危在前、稳定排序同级保持原始命中顺序），尾部 [TRUNCATED] 计数后缀恒可见（showing 30 of N）；单条文案截断 200 字符（MAX_ADVICE_CHARS，定案依据：perf 文案本体是 bundle 英文短句全库最长约 140 字符加标识符插值，200 对齐 McpSchemaFormatter.MAX_CELL_CHARS 的 DBX 短文案口径；ExplainTool 的 2000 是计划文本单元格口径不适用）。
- **防回归硬门禁**：少于上限时零排序零裁剪，输出与无预算版本逐字节一致（排序会打散同严重度内原始命中顺序，只有真超限才付此代价）；头部计数恒为总数（诚实计数）。
- **T-MB-02**：逻辑分层拆分 perfAdvice = 分析 + renderFindings 纯函数（合成 PerfWarning 清单直测，对齐 CheckSqlTool/MapperCheckTool 分层先例）；McpToolsetSupport 类注释落输出预算登记表（工具名/预算/常量位置三列口径，MetadataInvalidator 登记表同款守卫约定：新增工具或调整预算必须登记对齐）。
- **T-MB-03**：测试 8 例（新增 5）——45 条裁剪计数/SEVERITY 优先保留（40 INFO + 尾部 5 ERROR 全保留且前移）/恰好 30 条不裁不排序/单条 250 字符截断加省略号/少于上限与无预算版逐字节一致；CHANGELOG Changed 与变更记录行随本轮文档批次同步；第五轮报告 O-20 状态回填（含重定形说明）留主会话。
- **真机走查留人工**（§五）：问题多的慢 SQL 核对截断形态与 ERROR 级在前。
