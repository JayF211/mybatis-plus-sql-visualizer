# T-ErrorLocateUsability 错误定位按钮可用性判定与 Oracle 位置形态 — 开发计划

- 日期：2026-09-21 23:24
- 任务编号：T-ErrorLocateUsability（第十轮报告 §9.4 **O-49**，**P1**）
- 来源：第十轮报告 §8 DBX 增量清单 **#54**——DBX `0668bdc4`：`apps/desktop/src/lib/sql/errorPosition.ts:111-118` `sqlErrorHasMessagePosition`、`:145-148` `sqlErrorDisplayPosition`（显示与跳转**共用同一解析器**）、`:150-175` `resultSourceRange` 失败即拒绝、`ContentArea.vue:352-360`
- 性质：**可用性 + 诚实性**——修的是「按钮可见但点了没反应」这一静默形态
- 实施分支：自 master 拉任务分支 t-error-locate-usability
- 验证基线：master `03f6d25`（最近可核读数 491 类/5604 用例 0 失败 与 5577 例 0 失败，属不同批次树；实施时以当次实测为准）

---

## 一、背景与依据（现状核实，含四处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「跳不动就不给按钮」 | **未实现，且 KDoc 与实现不符（口径修正 1，本轮真缺陷）**：`ui/SqlResultPanel.kt:249-262` 的 `showErrorRich` 按 `locate != null`（`:252`）决定按钮显隐，KDoc 写「null = 锚点不可解析、按钮不出现」；但**唯一调用方恒传非 null lambda**（`ui/SqlPreviewPanel.kt:1030/1046/1055/1253/1271/1283/1392` 七处均 `onErrorRich = { info -> showErrorRichWithLocate(info) }`），而锚点解析发生在**点击时**（`:1296-1303` `locateErrorSource()` 里 `project ?: return`、`anchor ?: return` 静默返回）→ **按钮可见，点了什么都不发生** | 本任务的 P1 依据；修法极简（1 文件） |
| 2 | 「区分执行态 SQL 与源 SQL」 | **仅以文字承认，未结构化（口径修正 2）**：`ui/SqlPreviewPanel.kt:1290-1292` 的 KDoc 明写「驱动报错位置指向**被执行 SQL 文本**的字符/行，与 mapper 原文行不一一对应（动态 SQL 拼接、参数注入后行号必然错位），v1 只承诺『语句起始行』跳转」；`SqlResultPanel.kt:253-257` 的 `positionChar` **只进 tooltip 文案、不参与行计算**。全仓唯一显式两态分离在别处（`execute/MysqlClientGSuffix.kt:8` 提交文本与历史原文两态分离） | 缺口是「未结构化」而非「未意识到」——落点是把两态变成字段 |
| 3 | 「驱动无结构化位置时从消息文本解析」 | **部分实现**：`execute/ExecutionErrorInfo.kt:35` `ErrorPositionExtractor` 已解析**三种**文本 marker——`:38` PG `Position:\s*(\d+)`、`:41` `at line (\d+)`、`:44` `line:\s*(\d+)`；marker **单一性门控**（`:58-62` 恰好一个才归属）；越界钳制 `:65-68`。**无 Oracle 形态**（无 `ORA-` 数字偏移、无 `error occur at position: N`） | 补 Oracle 形态 |
| 4 | 「钳制已生效」 | **已实现但未接线（口径修正 3）**：四处投递点（`execute/DataSourceSelector.kt:2618`、`:2657`、`:2673`、`mcp/SqlVisualizerExplainTool.kt:155`）**均不传** `sqlMaxLength` → 越界钳制分支**只在单测生效** | 属「接线缺失」，随本任务一并接上 |

### 1.1 现有链（逐环节）

| 环节 | 落点 | 职责 |
|---|---|---|
| 位置提取纯函数 | `execute/ExecutionErrorInfo.kt:35` | `ErrorPositionExtractor`（三 marker + 单一性门控 + 越界钳制） |
| 富信息组装 | 同文件 `:84-116` | SQLState / errorCode（cause 链 ≤5 + nextException 链 ≤3） |
| 投递点（3 处 catch） | `execute/DataSourceSelector.kt:2618`/`:2657`/`:2673` | native 链 / executeQueries / onFailure |
| 穿参链 | `execute/SqlExecutor.kt:40`、`:95-98` → `DataSourceSelector.kt` 多处 | 默认 null |
| UI 按钮 | `ui/SqlResultPanel.kt:77-86`（创建）、`:249-262`（`showErrorRich`） | 仅错误态出现 |
| 锚点登记 | `actions/ConvertToSqlAction.kt:68-71` → `ui/SqlPreviewService.kt:106`/`:109` | 项目服务单槽 |
| MCP 侧 | `mcp/SqlVisualizerExplainTool.kt:155-161` | `errorPosition: char/line N` 附消息 |
| **对照组（同仓已做对）** | `log/SqlLogPanel.kt:1779-1782` | `e.presentation.isEnabledAndVisible = resolved != null`——**先解析、后显隐**；配 `log/EntryJumpResolver.kt:26-30` 的 `JumpTarget` 四态（Precise/NoSource/NotFound/MissingSource） |

**机制在同仓已存在**（`SqlLogPanel` 侧），错误定位侧漏用——这是本任务最省力的地方：照抄既有形态即可。

### 1.2 既有测试

`execute/ErrorPositionExtractorTest.kt`（**11 例**：PG Position 提取 / MySQL at line / line: 形态 / 多 marker 放弃归属 / 无 marker 返回 null / 越界钳制 / SQLState 与错误码 / cause 链穿透 / nextException 链 / 富信息位置字段 / 非 SQL 异常零 SQLState 不虚报）、`execute/ConnectionErrorClassifierTest.kt`、`log/EntryJumpResolverTest.kt`、`log/SqlLogPanelXmlJumpTest.kt`、`log/SqlLogPanelLiveJumpWithSourceTest.kt`、`log/HistorySqlLogPanelJumpToSourceTest.kt`。**缺口**：错误定位按钮的显隐/可用性**无 UI 测试**（`SqlResultPanel` 无对应测试类）。

## 二、目标与非目标

**目标**：① 跳转入口**先解析后显隐**——解析不出锚点就不显示按钮（对齐同仓 `SqlLogPanel` 的既有形态）；② 补 Oracle 的位置形态解析；③ 把 `sqlMaxLength` 钳制接上生产；④ 如实标注「报错位置指向执行态 SQL」。

**非目标**：不做执行态→源 SQL 的**位置换算**（动态 SQL 拼接 + 参数注入下行号必然错位，换算不可靠；现有「语句起始行」承诺维持，属诚实降级）；不改日志面板侧的跳转（那条链已做对）；不新增弹窗形态（沿用既有按钮与 tooltip）。

## 三、方案

**T-ELU-01（先解析后显隐，最小改动）**：`ui/SqlPreviewPanel.kt:1306-1307` 改为**先解析锚点、解析不出传 `null`**——`showErrorRich` 的 `locate` 参数本就支持 null（`SqlResultPanel.kt:252` 已按 null 判定），**UI 侧无需改动**。对齐 `SqlLogPanel.kt:1779-1782` 的形态。

**T-ELU-02（补 Oracle 位置形态）**：`execute/ExecutionErrorInfo.kt` 的 marker 集合增补 Oracle 形态。**先红后绿**：用真 Oracle 连接取真实报错文本再定 marker（见 §五-1），**不得凭 `ORA-` 前缀臆造**（Oracle 的错误文本里 `position: N` 出现在「table or view does not exist」这类错误里，形态与 PG 的 `Position:` 不同）。

**T-ELU-03（接线 `sqlMaxLength`）**：四处投递点（`DataSourceSelector.kt:2618`/`:2657`/`:2673`、`SqlVisualizerExplainTool.kt:155`）接上提交文本长度——使越界钳制从「仅单测生效」变为生产生效。

**T-ELU-04（两态如实标注）**：`ExecutionErrorInfo` 增字段（如 `executedSqlLength`）或显式标注来源为执行态文本，供 tooltip 如实说明「位置对应被执行的 SQL，非 mapper 原文行」。**不引入换算**。

**T-ELU-05（测试与文档）**：① 新增 `SqlResultPanel` 的显隐用例（锚点可解析 → 按钮可见；不可解析 → 不可见）——**这是本任务的核心判据**，当前该类无测试；② Oracle marker 用例（先红后绿）；③ 钳制接线用例；④ 既有 11 例全绿。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第十轮报告 §8 #54 落地备注。

## 四、验收标准

1. **锚点不可解析时按钮不出现**（用例 + 真机各一）——改前「可见但点了没反应」，改后「不可见」。
2. Oracle 位置形态可解析（用真库文本钉住）；既有三种 marker 行为不变。
3. `sqlMaxLength` 钳制在生产路径生效（用例断言越界被钳）。
4. tooltip/文案如实标注「位置指向执行态 SQL」（不宣称能定位到 mapper 原文行）。
5. 既有 11 例 + `EntryJumpResolverTest` + 日志面板三跳转测试全绿；全量 testShards + testDatabase + agent 绿。

## 五、验证清单（可经 DBX MCP 执行）

**Oracle 侧已实测可用**：`Oracle Free 23 / FREEPDB1`（`114.66.55.245:20095`，connection_id `311778f3-…`）。**纪律：共享库只读验证、禁止建删对象。**

| # | 场景 | 判据 |
|---|---|---|
| 1 | 经 Oracle 连接执行一条**必然报错**的语句（如引用不存在的表：`SELECT * FROM dbx_test.no_such_table_xyz`） | 取真实报错文本，确认是否含可解析的位置形态（本轮尚未取，**这是 T-ELU-02 的第一步**） |
| 2 | 同场景换 PG（`ce028080-…`）/ MySQL 连接 | 既有三种 marker 仍生效 |
| 3 | 真机 IDE：故意让预览执行失败且无锚点（如无项目上下文） | 按钮**不出现**（改前会出现且点了无反应） |
| 4 | 真机 IDE：正常失败且锚点可解析 | 按钮出现且点击正确跳转（回归确认） |

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **Oracle marker 臆造**（最大风险） | 若凭 `ORA-` 前缀猜形态，会写出永不命中的规则（或误命中） | T-ELU-02 强制**先取真库文本**再定 marker；先红后绿两次读数 |
| 按钮消失被当成功能退化 | 用户可能习惯「有按钮」 | 变更登记写明「解析不出位置时不再显示按钮（此前点了无反应）」；tooltip 说明原因 |
| 钳制接线改变错误文案 | 接上 `sqlMaxLength` 后超长 SQL 的位置会被钳到 SQL 长度 | 用例断言钳制边界；文案保持既有形态 |
| 两态标注引入新字段影响 MCP 输出 | `SqlVisualizerExplainTool.kt:155-161` 的 `errorPosition` 文案若变会影响 agent 侧 | 保持既有 `char/line N` 形态，仅**追加**来源说明；MCP 用例确认 |
| 无 UI 测试基建 | `SqlResultPanel` 当前无测试类，新增需建基建 | 照既有 UI 测试形态（如 `log/SqlLogPanel*Test`）搭最小夹具；若成本超预期，退为「解析结果断言 + 真机走查」两段 |

## 七、实施顺序

```
T-ELU-01（先解析后显隐，最小改动、独立可并行）→ §五-1（取 Oracle 真实报错文本）
  → T-ELU-02（补 Oracle marker，先红后绿）→ T-ELU-03（接 sqlMaxLength）→ T-ELU-04（两态标注）
  → T-ELU-05（测试与文档）→ §五 真机走查 → 收口
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-ELU-01 | （实施后填） | 改动行与「UI 侧零改动」的实证须回填 |
| T-ELU-02 | （实施后填） | Oracle 真实报错文本原文与最终 marker 须回填；两次读数须回填 |
| T-ELU-03 | （实施后填） | 四处接线点与钳制生效实证须回填 |
| T-ELU-04 | （实施后填） | 字段/标注形态与 MCP 文案影响须回填 |
| T-ELU-05 | （实施后填） | UI 用例基建形态、门禁读数须回填 |

**待验证前提（实施者先证伪再动手）**：

1. **Oracle 报错文本是否含可解析位置**——本轮**未取**（只确认了连接可用）。这是 T-ELU-02 的第一步：若 Oracle 不提供任何可解析位置，则该子任务退为「明确不支持 + 如实登记」，只做 T-ELU-01/03/04。
2. MySQL `at line N` 的语义是「SQL 文本第 N 行」还是「语句序号」——现有实现按**行号**处理（`ExecutionErrorInfo.kt:75` `isCharPosition = false`），**未核对**；实施时用真库证伪。
3. 锚点单槽（`SqlPreviewService.kt:106`）在多预览窗口场景是否被覆盖（当前 KDoc 声称「每项目至多一个预览弹窗」）——若证伪，则 T-ELU-01 的「先解析」需考虑槽被覆盖的情形。
4. `SqlResultPanel` 的 UI 用例基建成本——若搭不起来，验收 1 退为「解析层断言 + 真机走查」两段（如实登记，不宣称已自动化）。
