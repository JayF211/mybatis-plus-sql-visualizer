# T-McpPreviewFingerprint MCP 预览产物版本化指纹 — 开发计划

- 日期：2026-09-20 11:52
- 任务编号：T-McpPreviewFingerprint（第九轮报告 §9.3 **N-34**，P3，**含决策点 D1**）
- 来源：第九轮报告 §7.2 **Z-28**——zMyBatis 的物化产物携带版本化 SHA-256 指纹：`zmybatis-materialized-execution-v1` 做域分隔前缀、字段 4 字节大端长度前缀防边界歧义、source revisions 排序后哈希（插入序不影响）、`declaredMutation` 与 `${}` 旗标进指纹（`Materialization.kt:331`/`:366-426`）
- 性质：**产物可复核性**——不改渲染语义、不改工具行为；给产物加「同一 statement + revision + 参数 → 同一 SQL」的端到端复核键
- 实施分支：自 master 拉任务分支 t-mcp-preview-fingerprint
- 验证基线：master `f8c115d`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 决策定案（2026-09-20，用户认可本会话推荐）

| 决策点 | 定案 | 一句话理由 |
|---|---|---|
| **D1** | **A（收敛式）——条件性**：先做 **T-MPF-01（revision 来源核实）**；找得到稳定 revision → 按 A 实施；**找不到 → 退 C（不做）并如实登记** | 指纹的全部价值在「可复核」；拿不稳定值（如时间戳）冒充 revision，会让调用方拿到一个看着权威、实际不可复现的键，**比没有更糟** |
| **实施时序** | **排在首次含 MCP 的发行之后（或与该发行同批）** | MCP 目前从未交付给用户（只在 master）；在通道无人使用前加指纹属装饰 |

---

## 一、背景与依据（现状核实，含三处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「MCP 返回可能已有来源标识可扩展」 | **无任何稳定键（口径修正）**：十工具返回文本**零指纹/零版本/零 ruleId**；唯一「来源」字段是 `sourceType`，但它是**自由文本描述**（值形如 `"mapper XML (s1)"` / `"Wrapper chain"`，`mcp/McpSourceLocator.kt:46-54`、`:92/133/141/146/150/153/158/164/167/170/182/206`、`:209-223`），非枚举、非版本，**不可作端到端复核键**。审计走 `mcp/McpToolsetSupport.kt:81-95` 的 `McpAuditLog`，**只落 idea.log、不进返回体** | 指纹是**纯新增**，无既有字段可复用 |
| 2 | 「预览产物模型加指纹」 | **成立**：`xml/XmlPreviewModel.kt:57-89` 无 version/timestamp/source；`model/PreviewResult.kt:33-79` 中最接近「来源」的只有 `:72 stackDigest`、最接近「事实指纹」的只有 `:78 hasDollarRawSegments` | 需新增字段 |
| 3 | 「revision 来源」 | **未找到（口径修正，列为待验证前提）**：指纹材料（statement id + revision + 参数）中的 **revision 在本仓无对应字段**——无 VFS 时间戳/PSI 修改计数的收集点 | 落点与材料须实施时定；这是本任务的技术前提 |

### 1.1 十工具返回结构（实测落点）

| 工具 | 返回结构 | 证据 |
|---|---|---|
| `sqlv_render_sql` | `sql` + `paramTable` + `sourceType` + `table` + `warning` + `note` | `mcp/SqlVisualizerRenderSqlTool.kt:49-56` |
| `sqlv_check_sql` | — | `SqlVisualizerCheckSqlTool.kt:48-62` |
| `sqlv_render_logs` | `-- SQL #N [mybatis] log line M` 形态 | `SqlVisualizerRenderLogsTool.kt:87-100` |
| `sqlv_crud_sql` | — | `SqlVisualizerCrudSqlTool.kt:96-100` |
| `sqlv_reverse_sql` | — | `SqlVisualizerReverseSqlTool.kt:53-56` |
| `sqlv_rollback_plan` | — | `SqlVisualizerRollbackPlanTool.kt:52-74` |

（`mcp/` 目录 grep `fingerprint|ruleId|Version`：仅 3 处注释/文案，零字段。）

### 1.2 可复用的既有指纹先例

| 先例 | 形态 | 证据 |
|---|---|---|
| `feedback/ErrorFingerprint.kt:19` | `of(...)` + 24h 窗口去重——**本仓已有的指纹手法** | 同左 |
| `update/PluginVersion.kt:25` | `current()` 读 `plugin.xml` 的 `<version>`——**版本号取值点** | 同左 |

### 1.3 守卫约束（决定实现形态）

- `mcp/McpScopeSourceScanTest`：`mcpserver` 相关引用**必须留在 `mcp/` 包**；
- `mcp/McpToolsetSupport.kt:24-29`：toolset 类名的**前缀硬约定**（`SqlVisualizer<Xxx>Tool`）——新增类须遵守，否则守卫红；
- 十工具的返回文本有**逐字节断言**的测试（`RenderLogsToolTest`、`CheckSqlToolTest`、`CrudSqlToolTest`、`ReverseSqlToolTest`、`RollbackPlanToolTest`）——加字段会触这些断言，须一并更新（这是「改动面 3~11 文件」的来源）。

## 二、目标与非目标

**目标**（仅在 D1 选「做」时）：给预览产物与 MCP 返回加**版本化指纹**，使调用方可端到端复核「同一 statement + revision + 参数 → 同一 SQL」；指纹**不含** SQL 原文（只含哈希），可作为跨会话的稳定比对键。

**非目标**：不改渲染语义与工具行为；不改审计链（`McpAuditLog` 落 idea.log 的形态不动）；不把指纹做成门控或校验闸门（它是**复核键**，不是权限）；不追 zMyBatis 的 `declaredMutation` 语义（我方预览链无「声明变更」概念——`sqlv_rollback_plan` 已有自己的产物面）。

## 三、方案

### 决策点 D1（**须定案，实施前必须定**）

| 选项 | 内容 | 代价 | 推荐度 |
|---|---|---|---|
| **A（推荐，收敛式）** | 指纹生成**单点收敛**：新增一个 formatter（如 `mcp/McpPreviewFingerprintFormatter.kt`，命名遵守 `SqlVisualizer<Xxx>` 约定或经守卫确认后定），十工具在既有输出块**各加一行**调用它；预览侧字段加在 `model/PreviewResult.kt` | 2~3 文件（formatter + `PreviewResult` + 十工具接线） | ★★★ |
| **B（扩展式）** | `mcp/McpSourceLocator.kt:46-54` 的 `Outcome` 加字段 + 十工具各自拼接 | 3~11 文件，且 `Outcome` 是定位器模型，承载指纹语义不贴切 | ★★ |
| **C（不做）** | 只登记「revision 来源缺失」这一结论，待有真实需求（如 agent 侧反馈需要复核键）再做 | 0 | ★★（若 D1 的技术前提证伪，此项是诚实选择） |

**D1 定案（2026-09-20）**：选 **A（条件性）**，但**先做 T-MPF-01（revision 来源核实）**——若 revision 无法在本仓取得稳定值，则退 **C** 并如实登记（不得用「当前时间」冒充 revision，那会让指纹失去复核价值）。**实施时序**：排在首次含 MCP 的发行之后（或同批），理由见「决策定案」表。

### 任务分解（D1=A 时）

**T-MPF-01（revision 来源核实，先做）**：确认指纹材料中的 `revision` 取什么——候选：`VirtualFile.modificationStamp`、`PsiFile.modificationStamp`、或预览产物已有的某个稳定标识（`model/PreviewResult.kt:72 stackDigest` 是否可复用须判定）。**若三者都不可得或语义不合，退 D1=C。**

**T-MPF-02（指纹生成单点）**：新增指纹生成点（照 `feedback/ErrorFingerprint.kt:19` 的 hash 手法），材料与格式：
- 版本化域分隔前缀（如 `sqlv-preview-v1`）+ 各字段**长度前缀**防边界歧义（照 zMyBatis `Materialization.kt:415-426` 的形态）；
- 材料：statement 标识 + revision + 参数序列（**排序后**哈希，使插入序不影响结果）；
- 输出：短哈希（长度以「够用且不喧宾夺主」为准，建议 ≤12 字符）；
- **版本号取值**：如需入指纹，取 `update/PluginVersion.kt:25` 的 `current()`。

**T-MPF-03（预览侧字段）**：`model/PreviewResult.kt:33-79` 加指纹字段（与既有 `:72 stackDigest`、`:78 hasDollarRawSegments` 并列）；`xml/XmlPreviewModel.kt:57-89` 视需要在 `from()`（`:220` 附近）注入材料。

**T-MPF-04（MCP 十工具接线）**：十工具输出块各加一行指纹（落点：`SqlVisualizerRenderSqlTool.kt:49-56` 等，**逐工具确认落位**）；同步更新五处逐字节断言测试。

**T-MPF-05（测试与文档）**：用例——①同输入两次生成指纹**相同**；②参数顺序变化指纹**相同**（排序生效）；③参数值变化指纹**不同**；④revision 变化指纹**不同**；⑤指纹**不含** SQL 原文（可 grep 断言：指纹串不出现在产物 SQL 段中）。文档：CHANGELOG `[Unreleased]`（MCP 工具输出变化属用户可见）+ 变更记录行 + 第九轮报告 §7.2 Z-28 落地备注。

## 四、验收标准

1. 指纹确定性：同输入同 revision → 同指纹；参数顺序变化 → 同指纹（用例 ①②）。
2. 指纹判别力：参数值变化或 revision 变化 → 指纹变化（用例 ③④）。
3. 指纹**不泄漏** SQL 原文（用例 ⑤）。
4. 十工具的既有输出**除新增指纹行外逐字节不变**（五处逐字节断言测试更新后全绿，且更新仅涉及新增行）。
5. 守卫：`McpScopeSourceScanTest`、`McpToolsetSupport.kt:24-29` 的类名前缀约定均绿；全量 testShards + testDatabase + agent 绿。

## 五、真机走查清单（留人工）

1. 真机 MCP 调用 `sqlv_render_sql`：返回体含指纹行，且同一 mapper 同一参数两次调用指纹一致。
2. 真机编辑 mapper（改一个字符）后同参数调用：指纹变化（revision 面生效）。
3. IDE 内 agent 面板查看工具 schema：指纹行不影响参数 schema 的呈现。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **revision 来源不可得**（本任务最大风险） | 无 VFS/PSI 修改计数的现成收集点；若用不稳定值（时间戳）则指纹失去复核价值 | T-MPF-01 先行；不可得则退 D1=C，如实登记 |
| 逐字节断言测试大面积更新 | 五处工具测试的输出断言会红，更新时可能顺手改错别的行 | 更新时**只加指纹行**，其余行逐字节不动；用例 4 钉住 |
| 指纹长度与噪音 | 指纹过长会淹没工具返回的可读性 | 长度建议 ≤12 字符；`McpToolsetSupport.kt:21-31` 的输出预算登记表须同步（既有约定「新增工具或调整预算必须在此登记对齐」） |
| 平台返回类型限制 | MCP 工具返回类型为 `String`，指纹只能拼进文本——是否受平台约束未查证 | 列为**待验证前提 2** |
| 与 N-29 审计链重叠 | 审计行已有 dataSource/dialect/sql 摘要 | 分工写清：审计行是**人读留痕**，指纹是**机器复核键**；两者不互相替代 |

## 七、实施顺序

```
T-MPF-01（revision 来源核实——决定做/不做）→ [D1 定案]
  D1=A: T-MPF-02（指纹生成单点）→ T-MPF-03（预览侧字段）→ T-MPF-04（十工具接线 + 测试更新）
        → T-MPF-05（测试与文档）→ 真机走查 §五
  D1=C: 登记结论收口
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-MPF-01 | （实施后填） | revision 取值结论（或不可得）须回填 |
| D1 定案 | 本次更新 | **A（条件性）**：T-MPF-01 找得到稳定 revision 则实施，否则退 C；实施时序排在首次含 MCP 的发行之后 |
| T-MPF-02 | （实施后填） | 指纹格式、材料、长度、版本前缀须回填 |
| T-MPF-03 | （实施后填） | 预览侧字段落位须回填 |
| T-MPF-04 | （实施后填） | 十工具落位与五处测试更新范围须回填 |
| T-MPF-05 | （实施后填） | 五条用例读数、门禁读数须回填 |

**待验证前提（实施者先证伪再动手）**：

1. **revision 来源**——本计划未找到对应字段（无 VFS 时间戳/PSI 修改计数的收集点）。这是第一个待证伪点，决定任务做与不做。
2. MCP 工具返回类型（`String`）是否有平台约束导致指纹只能拼文本——未查证；若有结构化返回能力，指纹应以字段形态返回而非文本行。
3. 「参数序列排序后哈希」的前提是我方参数序列本身有序且稳定——若某些链的参数顺序来自 map 遍历（顺序不稳定），须先固定顺序再哈希，否则指纹不确定（用例 ② 即为该前提的判据）。
