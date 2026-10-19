# T-PreviewSideQuery：预览面板只读动作不替换编辑器

2026-10-19。来源：用户实测「预估影响行数」后，预览窗口里的 SQL 被 COUNT 试算语句顶掉，必须点「刷新」才能回到原预览；用户进一步指出其他执行类按钮可能也有同样问题，要求先出开发计划。

## 一、现象与影响面

点击「预估影响行数」后：

- 右侧编辑器内容从原 `UPDATE t_order ...` 变成 `SELECT COUNT(*) FROM t_order WHERE id = ...`；
- 左侧参数面板仍是原预览的参数，右侧 SQL 已不是原 SQL；
- `currentEditorSql()` 读的是 `nativeEditorDoc`，此时指向 COUNT 语句，**再点「执行 SQL」会执行 COUNT 而不是原 UPDATE**；
- 结果网格虽然出来了（截图中 COUNT = 1），但用户要去编辑/继续执行原 SQL，只能点「刷新」调 `refreshView()`，这是一条不显眼的逃生通道。

影响面盘点（按预览面板入口）：

| 入口 | 代码路径 | 现状 | 本次处置 |
|---|---|---|---|
| 预估影响行数 | `estimateDmlImpact`（`SqlPreviewPanelExecution.kt:142`）→ `SqlExecutor.execute`（:182）→ `embedConsole(EXECUTE)`（:184） | 共享控制台文档被 COUNT 覆盖 | P1 改为只读旁路查询 + 内联结果，不换编辑器 |
| 执行计划（结构化） | `explainSql`（:874）→ `submitExplainOrStructured`（:1059）→ `embedExplainView`（:1201） | `editorHolder` 被 `ExplainPlanResultView` 顶替 | P2 改为对话框/独立面板展示，编辑器不动 |
| 执行计划（直送/回退） | `submitExplainDirect`（:967）→ `SqlExecutor.execute`（:980）→ `embedConsole` | 共享控制台文档被 EXPLAIN 语句覆盖 | P2 改为只读旁路查询/对话框；保留显式「在控制台打开」 |
| 执行计划「回到原生控制台」 | `onBackToConsole`（:1175）→ `submitExplainDirect` | 显式换回控制台 | 保留为显式逃生入口，但改为独立查看方式（P2 可选） |
| 执行 SQL（预览工具栏） | `executeSql`（:662）→ 三条执行链（:735/:755/:768）→ `embedConsole` | 控制台文档变成待执行 SQL，结果网格在面板内 | P3a 保留控制台（结果网格是主输出），补显式「返回预览」；P3b 可选改为页签式结果区 |
| 回滚对话框执行 | `RollbackSqlDialog`（:243/:278）→ `executeAdhocSql`（:324）→ `embedConsole` | 同上（显式执行回滚脚本） | 与执行 SQL 同分类；本次不单独改 |
| 面板打开默认控制台 | `prepareDefaultConsole`（:1523，`SqlPreviewPanelResultView.kt:227` 调用） | 预览 SQL 就写在原生控制台文档里 | 不改，但计划中明确这是架构前提 |
| 刷新 | `refreshView`（`SqlPreviewPanelSqlView.kt:279`）→ `renderSql()` | 把预览 SQL 重新写回控制台文档 | 保留为恢复动作，不当作修复手段 |
| 回滚/导入参数/差异/分享/导出/复制 AI 上下文 | 「更多」菜单其余项 | 不触碰控制台 | 无需改动 |

## 二、根因与现状机制（代码证据）

1. **预览编辑器在 Ultimate 下就是原生控制台文档。** `showResult` 末尾调用 `prepareDefaultConsole()`（`SqlPreviewPanelResultView.kt:227`）；`prepareDefaultConsole`（`SqlPreviewPanelExecution.kt:1523`）通过 `embedConsole(consoleComponent, PREVIEW)`（:1539）把原生控制台 SplitPane 挂进 `editorHolder`/`consoleHost`。控制台挂在 `mpsql-exec.sql` 上，其文档内容由预览链写为预览 SQL（同函数 1549-1551 的注释）。
2. **所有 `SqlExecutor.execute` 都在共享控制台上执行。** `embedConsole`（:1589）换入的组件就是那个控制台；执行时 SQL 被写进控制台文档，因此“预览 SQL”被覆盖。估算/EXPLAIN/执行 SQL 都走这条链，所以都会顶掉预览内容。
3. **`currentEditorSql()` 优先读 `nativeEditorDoc`**（:487-489）。控制台换入后 `nativeEditorDoc` 是控制台文档，读到的就是试算/执行语句，导致「执行 SQL」目标漂移。
4. **结构化 EXPLAIN 换了另一种替换方式。** `embedExplainView`（:1201）同样清空 `editorHolder` 并换入 `ExplainPlanResultView`；它不写控制台文档，但同样让预览 SQL 离开视野。
5. **「刷新」是唯一恢复入口。** `refreshView`（`SqlPreviewPanelSqlView.kt:279`）调 `renderSql()`，把预览 SQL 重新写回当前编辑器/控制台文档；用户必须知道这个按钮。
6. **已有的只读旁路查询能力**：`McpDbExecutor.executeQuery`（`mcp/McpDbExecutor.kt:171`）对绑定数据源执行单条只读查询，返回 `RawResult`（headers/rows），支持超时与 `onExecuted` 审计；结构化 EXPLAIN 已用它（`ExplainPanelChannel.executeStructured`，`execute/ExplainPanelChannel.kt:116`）。`McpSelectGuard.checkSingleSelect`（`mcp/McpSelectGuard.kt:73`）提供只读语句护栏；`DataSourceSelector.boundConsoleDataSource()`（:311）取当前绑定数据源；`ExecutionDialectGate.fromDataSource` 可在无会话时判定目标方言（结构化 EXPLAIN 的 :1072-1078 已用）。

## 三、待验证前提（实施前逐条证伪）

1. **`McpDbExecutor.executeQuery` 的适用范围**：确认可对任意单条 SELECT 执行、不产生写副作用、超时语义与结构化 EXPLAIN 一致；确认它是否内部再过 `McpSelectGuard`，没有则在调用前显式 `checkSingleSelect`。
2. **绑定数据源可得性**：`boundConsoleDataSource()` 在预览打开后是否稳定非空；为空时沿用结构化 EXPLAIN 的“先选数据源、只绑定不执行”路径（`promptDataSourceThenRetryStructured` 同款）还是直接提示，需定案。
3. **方言闸无会话判定**：`ExecutionDialectGate.fromDataSource(boundDs)` + `evaluate` 是否足以在旁路查询前 fail-closed；结构化 EXPLAIN 已走过，复核 `RERENDER_UNAVAILABLE` 等分支在无 console 时的行为。
4. **COUNT 结果形态**：`RawResult` 首行首列是否稳定为数字；空结果、非数字、`truncated`、超时分别如何展示；`DmlImpactEstimator.Probe.rowCap` 的上限文案如何并入内联结果。
5. **执行 SQL 的目标漂移边界**：如果 P1/P2 不再换控制台，`currentEditorSql()` 是否始终返回预览 SQL；P3a 保留控制台时，如何显式区分“当前执行目标”和“预览 SQL”。
6. **`ExplainPlanResultView` 脱离 `consoleHost` 的可用性**：`onBackToConsole` 为 null 时按钮隐藏（`ExplainPlanResultView.kt:89`）；在 `DialogWrapper` 中布局、历史对比、索引建议是否正常。
7. **直送 EXPLAIN 的 TEXT/XML 形态**：`McpDbExecutor` 对 Oracle 二段式/SQL Server XML 的 `channelNote` 结果能否在对话框/表格里读取；读不到时是否诚实回退“在控制台打开”。
8. **线程与生命周期**：旁路查询在池线程、EDT 只做结果落地；`Disposer.isDisposed(this)` 守卫；对话框关闭/面板关闭后的回调处理。
9. **窄面板布局**：内联结果行放进左侧参数区还是结果区；350px 视口下不出现横向滚动（沿用 `FieldFillSimulationPanelTest` 的布局断言方式）。
10. **243/262 双版本**：`McpDbExecutor`/`ExecutionDialectGate` 的反射链已在结构化 EXPLAIN 使用，复核两版分发无差异；新增 UI 组件不触碰 262 专有 API。

## 四、方案设计

### P1：预估影响行数（本次核心，方案 3）

- 新增 `PreviewSideQuery`（建议落在 `ui/PreviewSideQuery.kt` 或 `execute/` 下的纯服务）：
  - 输入：`Project`、目标数据源、只读 SQL、超时、`maxRows`；
  - 前置：`McpSelectGuard.checkSingleSelect(probeSql)` + `ExecutionDialectGate.evaluate(fromDataSource(ds))`；
  - 执行：`McpDbExecutor.executeQuery(...)`，池线程，EDT 回调；
  - 输出：密封结果 `Scalar(value)` / `Table(raw)` / `Error(text)`，失败不抛到 UI 线程。
- `estimateDmlImpact` 改为：
  1. `DmlImpactEstimator.plan(sql, dialect)` 生成 COUNT 试算（语义不变，仍走只读护栏）；
  2. 取 `boundConsoleDataSource()`；无绑定则走既有“选择数据源后重试”路径（只绑定不执行）；
  3. 调 `PreviewSideQuery`，**不调用** `SqlExecutor.execute` / `embedConsole` / `onEditor`；
  4. 成功：在左侧参数区（或结果区顶部）显示一行灰色结果，例如
     `预估影响行数：12`，tooltip 放试算 SQL、目标表、上限文案（复用 `dml.impact.probe.*`）；
     行数上限时显示 `预估影响行数：≤12（DELETE LIMIT 上限）`；
  5. 失败：同一行显示红色错误，编辑器与参数保持原样；
  6. 清除 loading，保留原有“试算语句/目标表/上限”轻通知（或收进 tooltip，按文案克制原则定）。
- 验收核心：调用前后 `nativeEditorDoc?.text` / `currentEditorSql()` 完全不变；「执行 SQL」仍指向原 UPDATE。

### P2：执行计划（结构化 + 直送）

- 结构化通道：把 `embedExplainView(view)` 改为 `ExplainPlanDialog(view)`（`DialogWrapper`，非模态），或把 `ExplainPlanResultView` 放进预览面板的独立页签/悬浮面板；编辑器不动。
- 直送通道：优先用 `PreviewSideQuery` 取计划行（TABLE/JSON 形态），在同一个对话框/表格里展示；TEXT/XML 或取不到结构化行时，显示只读文本对话框。
- `onBackToConsole` 保留为显式动作：打开一个独立控制台/独立页签执行 EXPLAIN，不再默认顶掉预览编辑器。
- 历史捕获、索引建议、计划 diff、方言闸全部沿用现有链路，不改变语义。

### P3：执行 SQL（显式执行，单独定边界）

- P3a（建议先做）：保留原生控制台作为执行结果主输出，但补一个显式的「返回预览」动作/横幅，调用 `refreshView()`；同时在面板模型里保留预览 SQL，避免「执行 SQL」目标在用户不知情时漂移。
- P3b（可选，需用户确认）：把 `editorHolder` 改成页签式（预览 SQL / 控制台结果 / 计划视图），任何动作只切换页签、不替换预览页签；这是更大改动，建议在 P1/P2 稳定后单独评估。
- 回滚对话框执行与执行 SQL 同属显式执行，按 P3a 的返回入口处理，不单独改执行链。

### 明确不做

- 不改 `prepareDefaultConsole` 的共享控制台架构（P1/P2 绕开它，而不是拆它）。
- 不在 UI 线程直接 JDBC；只复用 `McpDbExecutor` 这条既有只读通道。
- 不改 `DmlImpactEstimator` 的改写边界与只读语义。
- 不删除「刷新」按钮；它仍是从任何残留状态恢复的兜底。

## 五、实施步骤（文件级落点）

1. 新增 `src/main/kotlin/.../ui/PreviewSideQuery.kt`（或 `execute/PreviewSideQuery.kt`）：
   - `sealed interface SideResult { Scalar; Table; Error }`
   - `fun run(project, dataSource, sql, timeoutSeconds, maxRows, onResult)`；
   - 前置 `McpSelectGuard` + `ExecutionDialectGate`，池线程执行，EDT 回调。
2. `SqlPreviewPanelExecution.kt`：
   - `estimateDmlImpact` 改为调用 `PreviewSideQuery`，删除 `SqlExecutor.execute`/`embedConsole` 调用；结果落到新内联行。
   - `submitExplainOrStructured` 的结构化分支改 `ExplainPlanDialog`；`submitExplainDirect` 改旁路查询/对话框。
   - P3a：执行 SQL 路径增加「返回预览」入口（或横幅按钮）。
3. `SqlPreviewPanelParamPanel.kt` / `SqlPreviewPanelResultView.kt`：
   - 新增内联结果行（`impactResultLabel`），灰色正文、错误红色；窄宽度换行；重建面板时清空。
   - 结果行的 tooltip 复用 `dml.impact.probe.*` 文案。
4. 新增 `ExplainPlanDialog.kt`（或复用 `DialogWrapper`）承载 `ExplainPlanResultView`；`onBackToConsole` 改为独立查看入口。
5. bundle：
   - `dml.impact.inline`（`预估影响行数：{0}` / `≤{0}`）、`dml.impact.inline.error`、`explain.dialog.title`、
   - P3a `execute.backToPreview` 等；zh/en 同步。
6. 测试：见 §六。

## 六、测试与验收

定向（独立沙盒，跑前 `parallel_tasks.py check` + register；`--no-configuration-cache`）：

```text
./gradlew test --tests "*PreviewSideQueryTest" --tests "*DmlImpactEstimatorTest" \
  --tests "*SqlPreviewPanelExecutionTest" --tests "*ExplainPanelChannelTest" \
  --tests "*FieldFillSimulationPanelTest" --tests "*ExecutionDialectGateTest"
```

必须覆盖：

- **编辑器不变**：调 `estimateDmlImpact` 前后 `currentEditorSql()` / `nativeEditorDoc.text` 一致；「执行 SQL」仍拿到原 UPDATE。
- **内联结果**：COUNT=12 显示 `预估影响行数：12`；`rowCap` 显示 `≤N`；空结果/非数字/超时显示红色错误；loading 被清除。
- **EXPLAIN 不换编辑器**：结构化与直送路径都不改 `editorHolder` 的预览页；对话框/独立面板可关闭，关闭后预览仍可编辑。
- **只读护栏**：`McpSelectGuard` 拒绝非 SELECT；方言闸在旁路查询前生效；无绑定数据源走“选择后重试”而不静默执行。
- **反向验证**：
  - 把 `estimateDmlImpact` 改回 `SqlExecutor.execute`/`embedConsole` → “编辑器不变”用例必红；
  - 去掉内联结果行 → 结果视读断言必红；
  - 去掉 `McpSelectGuard` → 非 SELECT 用例必红。
- **真机**：243 沙盒多数据源，点「预估影响行数」→ 结果内联、编辑器原样；点「执行计划」→ 计划在对话框/独立面板；点「执行 SQL」→ 结果网格 + 可见的「返回预览」；全程不再需要手动刷新。
- 不跑全量/构建校验；发版前按三档分层跑 `releaseGate`。

## 七、待拍板事项

1. 内联结果放**左侧参数区底部**还是**结果区顶部**（推荐参数区底部，靠近触发按钮，且不影响结果区现有布局）。
2. EXPLAIN 结果用**非模态对话框**还是**独立页签**（推荐对话框，改动小；页签留 P3b）。
3. P3a 是否保留「执行 SQL」的控制台替换（推荐保留，补「返回预览」）；P3b 页签式是否列入下一期。
4. 试算/EXPLAIN 的轻通知是保留还是收进内联行 tooltip（推荐保留试算通知，EXPLAIN 收进对话框）。

## 八、实施记录

2026-10-19 实施完成（主提交 `1a1cf724`，内联 tooltip 补充 `4b81e09f`，结构化覆盖 `1bcc3d70`，收口修正 `df480301`，无绑定重试 `a7cb698a`，返回预览修正 `a93bf7ca`）。按推荐拍板：内联结果放左侧参数区底部；EXPLAIN 用非模态对话框；执行 SQL 保留结果视图并补「返回预览」；试算通知保留，EXPLAIN 详情进对话框。

### P1 预估影响行数

- 新增 `ui/PreviewSideQuery.kt`：单条 SELECT 先过 `McpSelectGuard`，再按 `ExecutionDialectGate` 对绑定数据源做方言校验，池线程走 `McpDbExecutor.executeQuery`，EDT 回调；全程不调 `SqlExecutor.execute`/`embedConsole`。
- `estimateDmlImpact` 改为只读旁路查询；结果写入左侧参数区底部的灰色内联行（`预估影响行数：N` / `≤N`），tooltip 携带试算 SQL/目标表/上限，失败红色内联展示；不可试算时用原因覆盖旧结果，避免显示过期行数；编辑器与控制台文档保持不变。
- 无绑定数据源时复用既有数据源选择器：`DataSourceSelector.prepareConsole(executeAfterPrepare=false)` 只绑定不执行，`awaitDataSourceBoundThen` 轮询绑定落地后重试一次预估；取消/超时就地红色提示，不再二次弹选择器。
- 只读护栏：`PreviewSideQueryTest` 覆盖非 SELECT 输入在数据源访问前直接返回 `Blocked`。

### P2 执行计划

- 新增 `ui/ExplainPlanDialog.kt`：非模态 `DialogWrapper` 承载 `ExplainPlanResultView` / 直送结果表格 / 错误说明。
- 结构化通道从 `embedExplainView` 改为 `showExplainDialog`；`embedExplainView` 已删除。新增 `ExplainStructuredSideQueryPanelTest`：通过 `ExplainPanelChannel.structuredRunnerForTest` 注入计划结果，经真实 `submitExplainOrStructured` 分派，验证编辑器/控制台不变、结果进对话框。
- 直送/回退通道改走 `PreviewSideQuery`（`scalar=false`，`requireSelectGuard=false`，Oracle/SQL Server 带 `channelNote`），结果显示在对话框；失败时保留显式「在控制台打开」入口。
- 原控制台路径保留为 `submitExplainDirectConsole`，仅由「在控制台打开」/结构化视图的 `onBackToConsole` 显式调用；`ExplainPanelWiringGuardTest` 同步改为「对话框展示、不得再 embedExplainView」。
- 补齐 `panel.explain.openConsole` 中英文案（`在控制台打开` / `Open in Console`），并新增两条断言：双语 bundle 存在该键、失败对话框按钮文案等于本地化文案且不等于原始 key。

### P3a 执行 SQL

- `embedConsole(EXECUTE)` 在 `consoleHost` 顶部加执行视图头部：灰色说明 + 「返回预览」按钮；`detachConsole` 一并清空头部。
- 真机发现旧实现点「返回预览」只重渲染 SQL、头部仍留着；已改为 `backToPreview()`：先移除头部再 `refreshView()`，并补 `ConsoleHostTest` 用例（9/0）。
- 显式执行/回滚仍保留原生控制台结果网格；P3b 页签式结果区未实施（计划中列为下一期）。

### 定向读数

17 个受影响的测试类合计 **155/0/0/0**，其中：

| 测试类 | tests |
|---|---:|
| `DmlImpactSideQueryPanelTest` | 5 |
| `PreviewSideQueryTest` | 1 |
| `ExplainSideQueryPanelTest` | 2 |
| `ExplainStructuredSideQueryPanelTest` | 1 |
| `ExecutionDialectGateTest` | 17 |
| `ExplainPanelWiringGuardTest` | 11 |
| `ConsoleHostTest` | 9 |
| `PrepareSingleFlightGateTest` | 26 |
| `GuardSelfTestCoverageMetaGuardTest` | 8 |
| `DialectStatusRefreshWiringGuardTest` | 4 |
| `ExecuteSqlErrorHandlingTest` | 16 |
| `ExplainPanelChannelTest` | 12 |
| `FieldFillSimulationPanelTest` | 13 |
| `AiContextV2PanelTest` | 5 |
| `SqlPreviewTopRowLayoutTest` | 9 |
| `DmlImpactEstimatorTest` | 13 |
| `FieldFillSimulationTest` | 3 |

### 反向验证

- P1：临时把 `estimateDmlImpact` 改回 `embedConsole` → **2 红**（编辑器/控制台不变两条用例）。
- P2：临时删掉直送结果对话框 → `ExplainSideQueryPanelTest` **1 红**；临时删掉结构化 `showExplainDialog` → `ExplainStructuredSideQueryPanelTest` **1 红**。
- P3a：临时删掉执行视图头部 → `ConsoleHostTest` **2 红**（返回预览入口 + 宿主组件计数）。

### 真机走查（243 沙盒，2026-10-19）

- 沙盒 `build/MPSV-MasterMerge-UI243` 用 dev 包启动；在役 jar（17:49）已核含 `PreviewSideQuery`/`ExplainPlanDialog` 与 `panel.explain.openConsole`、`panel.backToPreview`、`dml.impact.*` 文案。
- **执行计划**：在 SELECT 预览点「执行计划」，出现独立的「执行计划」窗口（490,207,1430,873），预览窗口仍在；对话框显示本地化「在控制台打开」按钮。实际计划被沙盒数据源凭据阻断（`Access denied for user 'root'@'localhost' (using password: NO)`；demo 配置为 root/root），属环境凭据未配置，不是插件路径失败。
- **执行 SQL / 返回预览**：点「在控制台打开」后控制台嵌入，出现「当前显示执行结果；返回预览可继续编辑原 SQL」头部和「返回预览」按钮；沙盒连接仍被同一凭据问题阻断。旧包点返回只重渲染 SQL、头部仍在，本轮已改为 `backToPreview()` 移除头部（headless `ConsoleHostTest` 9/0 覆盖）。
- **预估影响行数**：在 SELECT 预览点该动作，左侧内联行显示红色「仅 UPDATE/DELETE 语句有影响面预演」，编辑器未被 COUNT 顶掉（NotApplicable 路径真机通过）。
- 成功 COUNT 的真机路径未完成：沙盒数据源无密码（Access denied），且 `mybatis-plus-demo`/临时夹具里的简单 UPDATE 调用在该沙盒未被预览定位器识别；成功路径由 `DmlImpactSideQueryPanelTest` 5/0 的测试缝覆盖，`McpDbExecutor` 真实连库仍待有凭据的真机补验。

### 边界与未做

- 无绑定数据源路径已实现「选择器只绑定 + 绑定后重试」；用户取消/超时/绑定失败时只就地提示，不自动执行任何 SQL。
- P3b（页签式结果区）未实施；执行 SQL 仍是「控制台结果视图 + 返回预览」。
- 真机走查为**部分通过**：EXPLAIN 对话框、执行视图头部/返回入口、预估 NotApplicable 已验证；成功 COUNT 受沙盒数据源凭据阻断，待有口令的环境补验。
- 定向测试中的 `McpDbExecutor` 通过 `PreviewSideQuery.runnerForTest` 桩替换；真实执行链未在 headless 下连库。
