# T-ExplainConsoleFirst：执行计划默认回到「预览窗内嵌原生控制台」并根治只读动作取词漂移

> **创建日期**：2026-10-24 19:26
> **触发**：用户提问「如果这个执行计划直接在 sql 预览窗口里执行，是不是就没有那么多问题了，并且右上角还有蓝色的返回预览按钮，你分析下这个的可行性，也是初版执行计划的实现方式」
> **前置**：`T-DialectInferPollution` / `T-ExplainFailUx` / `T-ExplainSwitchNative`（2026-10-24，合计 12 轮换源修复，已合入 `3117f6db`）

---

## 一、背景与现状判定

### 1.1 三条执行链并存（代码实证）

| 形态 | 入口函数 | 执行者 | 取源/密码/方言/报错归属 | 开关 |
|---|---|---|---|---|
| **A 初版**：内嵌原生控制台 + 蓝色「返回预览」 | `submitExplainDirectConsole`（`SqlPreviewPanelExecution.kt:1136`） | `SqlExecutor.execute` → 平台控制台 | **全部由平台负责** | 仅剩「在控制台打开」入口可达 |
| **B 当前默认**：旁路 JDBC + 只读对话框 | `submitExplainDirect`（`:1166`） | `PreviewSideQuery` → `McpDbExecutor` | 插件自己负责 | 默认 |
| **C 结构化**：计划表格/JSON 树 | `ExplainPanelChannel.executeStructured` | `McpDbExecutor` | 插件自己负责 | `explainStructuredRender`，默认 `false`（`PluginSettings.kt:550`） |

A 的代码一行未删：`SqlExecutor.execute(..., onConsole = { embedConsole(it, EXECUTE) })`；`embedConsole` 在 `EXECUTE` 模式下自动挂 `executeViewHeader()`（`:2035`），该头部即「当前显示执行结果；返回预览可继续编辑原 SQL」+ `primaryButton(panel.backToPreview)`。

### 1.2 为什么十二轮都在打 B / C

B / C 是插件自建 JDBC 通道，因此「用哪条源、密码从哪来、方言对不对、失败怎么呈现」全部落在插件身上。而平台「选中即关联」（`DbImplUtil.getForcedDataSourceForExec` 命中即 `singletonList` 短路枚举，且无公开清除 API）使插件侧的选源列表第二次起恒为单源——这是 W1~W12 全部换源拉锯的根因（详见 `docs/经验总结/20261024-1914-执行计划取源换源十二轮拉锯与平台关联副作用-经验总结.md`）。

A 不需要自建这些：换源走平台原生 `Data Sources` 菜单（Detach + 全部源），密码走平台凭据链，方言与报错都在控制台原生呈现。

### 1.3 B 被废弃的真实原因（必须同时偿还的技术债）

`1a1cf724`（2026-10-19）commit message 原文：

> 预估影响行数/执行计划此前走共享控制台，**会把预览 SQL 顶掉，并让 `currentEditorSql()` 漂移到试算/EXPLAIN 语句**；执行 SQL 后也缺少明确的返回预览入口。

即 A 唯一的真问题是：执行时 EXPLAIN 语句被写入 `mpsql-exec.sql` 的 document，而 `nativeEditorDoc` 指向它，于是 `currentEditorSql()` 从「预览 SQL」漂移成「EXPLAIN ...」。所有只读动作都读这个函数，在 A 的执行视图里点它们会拿到 EXPLAIN 语句。

**本任务的承重墙就是偿还这笔债**——不修它，A 上线即回归。

## 二、方案

### 2.1 分派改形

- `submitExplainDirect`（默认与全部 fail-closed 回退）→ **内嵌原生控制台（形态 A）**。
- `submitExplainDirectConsole` 保留为 A 的单一实现，`submitExplainDirect` 收敛为「前置零源判定 + 转调」。
- 形态 C（结构化）继续留在 `explainStructuredRender` 设置项后（默认关，用户显式开启才走）——不动。
- 形态 B 收窄：只服务「预估影响行数」（`estimateDmlImpact`）与回滚 SQL 对话框（`RollbackSqlDialog`），不再承接 EXPLAIN。

### 2.2 取词漂移根治（W2，本任务的实质工作量）

新增 `previewSqlForReadonly()`：**执行视图在场时读预览快照**，否则读真实编辑器。

```kotlin
internal fun SqlPreviewPanel.previewSqlForReadonly(): String? =
    if (executeViewHeader != null) lastRenderedSql?.takeIf { it.isNotBlank() } ?: currentEditorSql()
    else currentEditorSql()
```

判据依据：`lastRenderedSql` 由 `showSql` 在每次预览渲染后记录（`SqlPreviewPanelSqlView.kt:713/734/747`），而控制台执行链写 `mpsql-exec.sql` **不经过 `showSql`**，故它恰好保存着「最后一次预览的 SQL」。同款判据已在 `SqlPreviewPanelExecution.kt:657`（`currentText == lastRenderedSql`）与 `:813` 使用。

改造的只读动作（5 处，全部读 `currentEditorSql()`）：

| 动作 | 位置 |
|---|---|
| 回滚 SQL | `SqlPreviewPanelExecution.kt:43` |
| 导出回放脚本 | `:162` |
| 复制为 AI 上下文 | `:290` |
| 导出可移植 SQL | `:86` |
| 预估影响行数 | `:323` |

**`executeSql` / `explainSql` 不改**——它们必须执行编辑器里的真实文本（含用户手改）。

### 2.3 明确不做

- 不自建换源 UI（W12 已定案，经验文档第一节）。
- 不删形态 C（结构化计划是差异化能力，且默认关）。
- 不为 A 补「未回填 `?`」前置拦截（`ExplainGate` 既有前置校验保留；回填核对交给数据库如实报错，错误文本比「未绑定数据源」这类误归因清楚）。

## 三、工作项

### W1 分派改形（`ui/SqlPreviewPanelExecution.kt`）

1. `submitExplainDirect` 函数体改为：Schema 校验 → **前置零源判定** → `submitExplainDirectConsole(...)`。
   - 前置只判「是否一条源都没有」（`DataSourceSelector.registeredDataSourceCount(project) == 0`）→ `showExplainErrorDialog(noDataSource, openDatabase = true)`；**不做任何选源**（不在插件侧重演 W1~W12）。
   - 保留既有 `explainTargetForTest` 测试缝语义：非 null = 有可用源，直落控制台。
   - 删除对 `PreviewSideQuery.run` 的 EXPLAIN 调用与随之而来的 `Result.Table/Blocked/Scalar` 分型分支。
2. `showExplainRawResultDialog` 改为 `@Deprecated` 保留（C 通道仍可能用到表格形态），或确认零引用后删除——实施时以 grep 结果为准。

### W2 取词根治（`ui/SqlPreviewPanelExecution.kt`）

1. 新增 `previewSqlForReadonly()`（见 2.2）。
2. 五个只读动作改调用它。
3. `lastRenderedSql` 加注释说明「执行视图下它是预览快照的唯一来源」。

### W3 测试契约反转

1. `ui/ExplainSideQueryPanelTest.kt`：整类原断言「EXPLAIN 不得换入控制台 / 经对话框展示」是本次**主动反转**的契约。
   - 改为新契约：改用 `SqlExecutor.executionAttemptForTest` 截获执行入口，断言 `submitExplainDirect` 把 `EXPLAIN ...` 交给了执行链（即控制台路径），且未弹结果对话框。
   - 保留仍成立的两条：对话框取消按钮跟随插件语言、零源时按钮直达 Database（`showExplainErrorDialog` 未变）。
2. 新增 `ui/PreviewReadonlySnapshotTest.kt`：
   - 执行视图在场（`executeViewHeader != null`）时 `previewSqlForReadonly()` 返回 `lastRenderedSql`，即使 `nativeEditorDoc` 已是 EXPLAIN 文本；
   - 非执行视图时返回编辑器真实文本（含手改）。

### W4 守卫

`convention/ExplainPanelWiringGuardTest.kt`：

- 既有「`submitExplainDirect` 至少两处复用 + 保留 `SqlExecutor.execute`」继续成立（A 就是 `SqlExecutor.execute`）。
- 新增断言：`submitExplainDirect` 不得再调 `PreviewSideQuery.run`（防形态 B 悄悄回流）。
- 新增断言：五个只读动作读 `previewSqlForReadonly()`，不得直接读 `currentEditorSql()`（反向：把任一动作改回 `currentEditorSql()` 必红）。

### W5 文档回填

本文件 `## 实施记录` + `docs/索引/开发计划.md` 状态列。

## 四、待验证前提（先证伪再动码）

| # | 前提 | 证伪方式 |
|---|---|---|
| P-1 | `lastRenderedSql` 在「执行计划」按下前后确实是预览文本（不被执行链改写） | 读 `showSql` 三处赋值 + 确认 `SqlExecutor` 链路不调 `showSql`（已初查：写 document 在 `DataSourceSelector`，不经 `showSql`） |
| P-2 | `executeViewHeader` 在执行视图期间恒非 null，且「返回预览」后归 null | 读 `embedConsole`/`backToPreview`/`detachConsole` 三处（已初查：`EXECUTE` 挂载设值、`backToPreview` 与 `detachConsole` 清空） |
| P-3 | 零源判定在 headless 测试工程里稳定为「零源」，不会让既有 `InjectionGateTest`/`UnknownParamGateTest` 的「无数据源引导」断言失效 | 跑测试；若 headless 有隐式源，则改为 `explainTargetForTest ?: boundConsoleDataSource()` 判空（保持既有语义） |
| P-4 | `SqlExecutor.execute` 在 Community/无 Database 插件下走既有 `showInfo(execute.noDatabase)`，不需要新提示 | 读 `SqlExecutor.execute` callback 分支（已核实存在） |

## 五、验收判据

1. `submitExplainDirect` 的执行体交给 `SqlExecutor.execute`（源码守卫 + 定向用例双证）。
2. `previewSqlForReadonly()` 在执行视图下返回预览快照（用例 + 反向）。
3. 定向测试全绿；三个反向探针各恰 1 红（探针必须**真删符号**，禁用注释/`false &&`）。
4. 提交态 `compileKotlin` + `compileTestKotlin` 通过（防「工作树绿、提交态红」，见经验文档第七条）。

---

## 六、实施记录

**2026-10-24 已实施，提交 `HEAD`（未推送）。**

### W1 分派改形（实际落地）

`submitExplainDirect` 现在只有三段：Schema 校验 → 零源判定 → `submitExplainDirectConsole`。

零源判定用 `DataSourceSelector.registeredDataSourceCount(project) > 0`（该函数此前已存在但零调用，本轮才接上线）；只要有一条在籍源就交给平台，**插件不枚举、不筛选、不猜**。
删除了整段 `PreviewSideQuery.run` 调用与 `Result.Table/Blocked/Scalar` 四分支分型。

顺带退役：`showExplainRawResultDialog`（`ExplainPlanDialog.kt:77`）已零引用，删除并清理四个随之无用的 import（`McpExplainFormatter`/`JBTable`/`JTable`/`DefaultTableModel`），原位置留注释记录去向。

### W2 取词根治（实际落地）

`previewSqlForReadonly()` 落在 `SqlPreviewPanelExecution.kt:585`，判据 `executeViewHeader != null`。
五处只读动作全部改用它：`showRollbackSql` / `exportReplayScript` / `copyAsAiContext` / `exportPortableSql` / `estimateDmlImpact`。
`executeSql` / `explainSql` **未改**（执行链必须执行编辑器真实文本）。

### W3 测试契约反转

- `ExplainSideQueryPanelTest` 整类重写：从「不得换入控制台」反转为「必须交给执行链」，用 `SqlExecutor.executionAttemptForTest` 截获执行入口断言 `EXPLAIN ...` 原样下发，并断言不弹结果对话框。用例数 6 → 3（旧用例中依赖旁路结果分型的 4 条随形态 B 一并退役；对话框语言绑定与零源引导两条保留）。
- 新增 `PreviewReadonlySnapshotTest`（4 例）：执行视图返回快照且**同时对照** `currentEditorSql` 已漂移、返回预览后退回编辑器文本、无执行视图时用户手改优先、快照缺失时兜底编辑器。

### W4 守卫

`ExplainPanelWiringGuardTest` 新增两条：`W2_只读动作取词必须走预览快照`（五动作逐个断言用快照且不裸读 `currentEditorSql`；另断言 `executeSql` 仍读真实编辑器）、`W1_执行计划直送不得回流旁路查询`（`submitExplainDirect` 必须转调控制台且不得出现 `PreviewSideQuery.run`）。

旧守卫按新契约修订（不是删掉）：
- `ExplainAskPasswordChannelGuardTest.直送链取源必须接模块学习回落` → 改写为 `直送链不得再做插件侧选源`（断言 body 内无 `explainFallbackDataSource`、有 `submitExplainDirectConsole`、零源判定在场）。
- `认证失败不得弹红字模态框` / `选错源失败不得弹红字模态框`：分支条数断言 `>= 2` → `>= 1`（直送链那处随形态 B 退役，呈现交给平台控制台），**呈现纪律不变**。

### 读数

- 定向 10 类 **124 / 0 / 0 / 0**（`ExplainAskPasswordChannelGuardTest` 15、`ExplainPanelWiringGuardTest` 15、`ExplainSideQueryPanelTest` 3、`PreviewReadonlySnapshotTest` 4、`InjectionGateTest` 18、`UnknownParamGateTest` 6、`ExplainStructuredSideQueryPanelTest` 1、`DmlImpactSideQueryPanelTest` 9、`RollbackSqlDialogTest` 38、`SqlPreviewSchemaGateTest` 15）。
- 反向探针三组，**各恰 1 红**（均真删/真改符号，非注释、非 `false &&`）：
  1. 把 `showRollbackSql` 取词改回 `currentEditorSql()` → `W2_只读动作取词必须走预览快照` 红；
  2. 在 `submitExplainDirect` 内插入真实 `PreviewSideQuery.run(...)` 调用 → `W1_执行计划直送不得回流旁路查询` 红（首版探针写成编译不过的形态，那次不算数，重写为可编译形态后才取到读数）；
  3. 把 `previewSqlForReadonly()` 本体改成直返 `currentEditorSql()` → `testExecuteViewReturnsPreviewSnapshotNotExecDocument` 红。
- 主/测试源编译均通过（提交态复核）。

### 未做与遗留

- **沙盒 GUI 未走查**：本轮是通道改形，真机需确认「点执行计划 → 内嵌控制台出现 EXPLAIN 结果 + 顶部蓝色返回预览」。按经验文档第九条，沙盒合成输入对 `JTextField` 不可靠，此步留给用户真机（或下次沙盒会话用日志打点 `[M36] embedConsole embedMode=EXECUTE` 自证）。
- **形态 C（结构化）未动**：仍由 `explainStructuredRender` 开关控制，默认关。若用户后续想看计划表格/索引建议，开该开关即走 `ExplainPlanDialog`，与本轮改动不冲突（`submitExplainOrStructured` 的 Structured 分支未改）。
- **`showExplainWrongTarget` / `notifyExplainAuthFailure` / `notifyExplainCancelled` 仍在**：现仅由结构化通道调用，未成死代码。
- 全量 `testShards` 与 `verifyPlugin` 未跑（本改动不动 `build.gradle.kts` / `plugin.xml` / 依赖版本，按三档分层不触发构建校验；全量留收口阶段）。

---

## 七、追加：W6 结构化通道「默认关」定案与推荐通知一次性（同日，用户报障触发）

### 7.1 用户报障

实施 W1~W5 后用户实测：**「为什么我点击执行计划还是弹窗了？」**

### 7.2 归因（配置实证，非推测）

查沙盒持久化配置：

```
.intellijPlatform/sandbox/.../config/options/mp-sql-visualizer.xml
  <component name="MpSqlVisualizerSettings">
    <option name="explainStructuredRender" value="true" />
```

而代码默认值是 `false`（`PluginSettings.kt:550`）。差异只可能来自一处：`Decision.DirectSilent` 分支里那条「启用结构化并重试」推荐通知——**它每次点执行计划都出现**（开关关着就满足推荐条件），用户顺手点过一次之后 `enableStructuredExplainAndRetry` 把开关**永久**写成 true，而界面上再没有任何「现在是实验形态」的提示。于是下次点执行计划看到 `ExplainPlanDialog` 弹窗时，用户完全无从归因，只会认为「改了半天还是在弹窗」。

W1~W5 改的是 `Decision.DirectSilent` / `DirectFallback` 那条链（`submitExplainDirect` → 内嵌控制台），**结构化分支按 §2.3「明确不做」原样保留**——用户此前的点击恰好走的是结构化分支，所以改动对他不生效。这不是实现缺陷，是 §2.3 的边界没有对用户说清。

### 7.3 产品决定（用户拍板）

**结构化通道保留，默认关。** 不退役、不删代码、不改默认值（本来就是 false）。

### 7.4 W6 实施

- 新增 `PluginSettings.State.explainStructuredOfferedShown`（默认 `false`，持久化，循 `onboardingAnchorTipShown` 先例）：推荐通知**只提示一次**，之后全程静默。该标记只关掉「主动推荐」这一条噪音源，**不阻断显式入口**——用户想开仍可去设置页勾选。
- 通知接线处加一次性判定并置位（`SqlPreviewPanelExecution.kt:1441-1443`）。
- 新增 `ExplainStructuredDefaultOffGuardTest` 三条守卫：
  1. `explainStructuredRender` 声明默认值必须是 `false`（产品决定固化，防被改回）；
  2. 一次性标记必须**真置位**（防「读了个恒 false 的字段」的假一次性——只加判定不加置位，通知照样每次都弹）；
  3. 设置页 `explainStructuredCheck` 与 bundle 键必须仍在（**「默认关」≠「拆掉通道」**，防清理时误删）。

### 7.5 读数与遗留

- 定向 10 类 **83 / 0 / 0 / 0**。
- 反向探针两组，各恰 1 红：①把默认值改回 `true` → `结构化渲染默认必须关闭` 红；②删掉一次性标记的实际置位（保留条件读取，即伪一次性）→ `启用结构化推荐通知必须只提示一次` 红。
- **构建坑（记录）**：新增 `State` 字段后增量编译留下陈旧 class，`config` 包 10 个用例报 `NoSuchMethodError: State.copy$default(...)`。`clean` 后全绿——是构建缓存问题不是产品缺陷。同类改动后若见 `copy$default` / `copy` 签名类 `NoSuchMethodError`，直接 `clean`，别去查数据类定义。
- **用户当前沙盒状态未修**：沙盒 IDE 当时在运行（PID 37808），此时改配置文件会在退出时被回写覆盖，故未直接改盘。需用户在设置页（`设置 → 工具 → MyBatis-Plus SQL Visualizer → SQL 预览` 组）取消勾选「预览面板内结构化渲染 EXPLAIN」后 Apply，或在关闭 IDE 后改该 xml。
- 沙盒 GUI 未走查（同 §六）。


