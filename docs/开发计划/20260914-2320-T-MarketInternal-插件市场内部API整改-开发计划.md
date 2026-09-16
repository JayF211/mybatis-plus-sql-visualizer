# T-MarketInternal 插件市场内部 API 整改 — 开发计划

- 日期：2026-09-14 23:20
- 任务编号：T-MarketInternal
- 来源：用户指令「上传插件市场包含使用了内部 api，现在需要整改」
- 性质：**Marketplace 发布阻断级**（P0）
- 验证基线：全量 `./gradlew test` **4083 / 0 失败 / 0 错误 / 6 跳过 绿**（本轮实测，2026-09-14 22:54~23:20）

---

## 一、问题现状（Plugin Verifier 实测，非推断）

本轮实跑 `verifySmoke`（243）与 `verifyPlugin -PverifyIdes=minimal`（243 + 262），报告落在
`build/reports/pluginVerifier/`。**Internal API 确实存在，且只在 262 被检出**：

| 平台 | 判决 | Internal | Scheduled-for-removal | Deprecated | Experimental |
|---|---|---|---|---|---|
| IU-243.28141.41（编译基准） | Compatible | **0** | 3 | 3 | 14 |
| IU-262.9437.185（本机实机版） | Compatible | **6** | 3 | 52 | 14 |

262 的 6 条 Internal 全部集中在一个方法：`FeedbackReporting.pluginErrorPoolStacks()`。

```
Internal class  com.intellij.diagnostic.MessagePool                       (reference)
Internal method com.intellij.diagnostic.MessagePool.getInstance()          (invocation)
Internal method com.intellij.diagnostic.MessagePool.getFatalErrors(ZZ)     (invocation)
Internal class  com.intellij.diagnostic.AbstractMessage                    (reference)
Internal method com.intellij.diagnostic.AbstractMessage.getThrowableText() (invocation)
Internal method com.intellij.diagnostic.AbstractMessage.getMessage()       (invocation)
```

三版本共同的 3 条 scheduled-for-removal（"will be removed in a future release"）：

```
Deprecated method com.intellij.database.model.DasObject.getDbChildren(Class, ObjectKind)
    → database.DbTableSpecAdapter.toSpec(DbTable)
Deprecated method com.intellij.database.model.DasTypedObject.getDataType()
    → database.DbTableSpecAdapter.toSpec() 的列映射 lambda
Deprecated method com.intellij.database.psi.DbPsiFacade.findElement(DasObject)
    → ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.locateDbTables()
```

> 注：`build.gradle.kts` 的 `failureLevel` 只保留 `COMPATIBILITY_PROBLEMS`（M30.1 定例），
> 所以两种报告都打印 "Compatible" ——**这个 Compatible 不代表审核会过**。JetBrains 审核是独立流程，
> 2026-08-18 的拒审邮件正是冲着 Internal API 来的。

---

## 二、根因

### 2.1 Internal API 是「上一轮自作主张留的尾巴」，不是功能必需

`MessagePool` 这条链是 **T-FeedbackDlg2 第二波**（`20260914-1320` 计划）引入的：手动形态
「从 IDE 错误池导入」需要一个堆栈候选数据源，当时选了「IDE Internal Errors 对话框同一列表」，
并用 javap 只核实了**签名一致性**（243 app-client.jar 与 262 ide.impl.jar 签名一致），
**没有核实 `@ApiStatus.Internal` 标注** —— 于是 262 上被 verifier 抓了 6 条。

本轮 jar 反编译实测标注落点（`javap -v` 读 `RuntimeInvisibleAnnotations`）：

| 类 | 243（app-client.jar） | 262（intellij.platform.ide.impl.jar） |
|---|---|---|
| `com.intellij.diagnostic.MessagePool` | 无标注 | **类级 `@ApiStatus.Internal`** |
| `com.intellij.diagnostic.AbstractMessage` | 无标注 | **类级 `@ApiStatus.Internal`** |
| `com.intellij.diagnostic.IdeErrorsDialog` | 类级 Internal | 类级 Internal |
| `com.intellij.diagnostic.MessagePoolListener` | — | 类级 Internal |
| `com.intellij.diagnostic.MessagePoolAdvisor` | — | 类级 Internal |

**这就是 243 报 0、262 报 6 的机理**：标注是 262 加的（243 还没有），而我们的编译基准是 243，
本地/CI 都看不见。**单靠 verifySmoke（只跑 243）永远抓不到这类问题** —— 这是本轮暴露的方法论坑。

补充事实（决定修复方案走向）：262 上**整条诊断链都是 Internal**。`ErrorReportSubmitter`、
`IdeaLoggingEvent`、`ErrorReportSubmitter.EP_NAME`、`Logger.getInstance`、`IdeErrorsDialog` 逐类核过，
没有任何一个可作为「公开替代入口」用来列错误池。同轮 `getFatalErrors` 的三个调用方
（`ErrorMessageClustering`、`MessagePool` 自身、`ExceptionsKt`）全在平台内部。

**结论：不存在「用公开 API 读 IDE 错误池」这条路。** 必须换数据源。

### 2.2 scheduled-for-removal 是 database 插件收敛 API 的结果，且都给了等价公开替代

三个都是「旧入口仍在但已宣告将移除」，替代入口**双版本都已存在且非 Internal**（243 与 262 分别
从 `plugins/DatabaseTools/lib/database-plugin.jar` 与 `lib/modules/intellij.database.jar` 反编译核对）：

| 旧 API | 替代 | 依据 |
|---|---|---|
| `DasObject.getDbChildren(Class, ObjectKind)` | `DasObject.getDasChildren(ObjectKind)` + `JBIterable.filter(Class)` | `getDbChildren` 方法体 javap：`getDasChildren(kind).filter(clazz)`，逐字节等价 |
| `DasTypedObject.getDataType()` | `getDasType().toDataType()` | `getDataType` 方法体 javap：`getDasType().toDataType()`，等价 |
| `DbPsiFacade.findElement(DasObject)` | `DatabaseCoreUiService.getInstance().findElement(Project, DasObject)` | `DbPsiFacadeImpl.findElement` 方法体 javap：**就是**转调 `DatabaseCoreUiService.getInstance().findElement(myProject, dasObject)`；该类 243/262 均存在、非 Internal、非 deprecated |

`DatabaseCoreUiService` 在 262 位于 `modules/intellij.database.core.impl.jar`、243 位于
`database-plugin.jar` 顶层——这是 262 的模块拆分结果，不是类消失（项目已有同类先例：
`com.intellij.sql.*` 的拆分处理）。**实施时必须真机 262 复验一次，不能只看 jar。**

---

## 三、修复方案

### 3.1 波次 P0-A：Internal API 清零（必做，直接对着拒审理由）

**T-MI-01（信息面核对）**：确认 `IdeErrorsDialog` 提供的原始能力集合——列表、多选、克隆/清理、
错误详情、提交（走 `errorHandler` EP）。核对结论决定 T-MI-03 是否需要补**错误历史**能力。

**T-MI-02（关键项）**：确认 `errorHandler` EP 的触发路径在 262 上**未经内部 API**。
证据链（`ExceptionsKt.processUnhandledException` javap）：未捕获异常 → `MessagePool.addIdeFatalMessage`
→ 展示交互式 `IdeErrorsDialog` → 用户点「报告」→ 平台按归属插件解析 `com.intellij.errorHandler` EP
→ 调我们的 `IdeErrorReportSubmitter.submit(events, ...)`。这条链**全程在平台内部**，我们只实现
`ErrorReportSubmitter` 子类（唯一被点名的 `EP_NAME` 字段我们没有引用）。
> 若 T-MI-02 走通，**「报告到需求池」主链继续完全合规**，本轮只需摘掉插件自己读错误池那一段。

**T-MI-03（摘除错误池读取）**：删除 `FeedbackReporting.pluginErrorPoolStacks()` 整段
（`MessagePool.getInstance` / `getFatalErrors` / `AbstractMessage` 全部 6 条 Internal 随即消失）
及其唯一的调用方接线——`FeedbackReporting.openManualDialog()` 里的
`stackPoolProvider = { pluginErrorPoolStacks() }`。

**T-MI-04（手动形态堆栈来源改造）**：删掉 `ErrorReportDialog` 的「从 IDE 错误池导入…」按钮
（`fields.stackImportButton` 的可见性翻转、`showStackImportMenu()`、`stackPoolProvider`
构造参数、`ReportFormFields.stackImportButton` 字段与它在 `buildCenterPanel` 里的布局位）。
手动形态保留**自主粘贴**（T-FeedbackDlg3 的既得形态，堆栈区可编辑 + placeholder），
顶部加一行指向 IDE 原生错误列表的引导文案 + 可点击链接（走已有 `SafeBrowser`）。

> 方案取舍：T-MI-01 若发现 `IdeErrorsDialog` 已有可用入口（例如它自带「复制堆栈」
> 或可经 action 打开），T-MI-04 的引导文案改为「打开 IDE 错误列表」，功能退化最小。
> 但**不保留插件侧的列表读取** —— 这是本轮整改的硬要求。

**T-MI-05（文案与测试同步）**：i18n 双语删除 `error.report.dialog.stackImport` /
`.tip` / `.empty`（若无其他消费者），新增引导文案键；`ErrorReportDialogLayoutTest`
的「手动形态堆栈导入控件默认态」用例同步（断言导入按钮消失 + 新键落地）；
`FeedbackReportingContractTest` 的 `isPluginError` 用例随之删除。

**T-MI-06（PooledErrorStack 契约测试归位）**：若 `isPluginError`（纯函数）被一并删除，
其覆盖的「按包名归属过滤」语义需要在新的数据源实现里重建同名契约测试。

### 3.2 波次 P0-B：scheduled-for-removal 清零

**T-MI-07**：`DbTableSpecAdapter.toSpec()` 两处调用改等价公开入口（见 §2.2 表）。
预计无行为差异，`CrudTableSpec`/`CrudColumn` 产物逐字段不变。

**T-MI-08**：`NativeDatabaseDiagramSupport.locateDbTables()` 的
`facade.findElement(element)` 改 `DatabaseCoreUiService.getInstance().findElement(project, element)`
（**直连编译期依赖**，因为它确实跨版本同签名且非 Internal；若真机 262 报错再退反射方案）。
同文件的 `DataSourceManager.getManagers(project)` / `RawDataSource.model` / `Model.traverser()`
一并核标注状态（本轮只核了被点名的三条）。

### 3.3 波次 P1：验证链加固（防止同类问题再漏）

**T-MI-09**：把 262（乃至 253）纳入**日常**验证口径。当前 `verifySmoke` 只跑 243 是漏洞根源。
建议：日常保留 smoke 的便捷性，但在**提交前/里程碑**统一跑 `-PverifyIdes=minimal`（243+262，
本轮实测 7 分 39 秒），或在 `build.gradle.kts` 增加打印 Internal 计数的守护任务。

**T-MI-10**：`PlatformSignatureCrossVersionTest` / `PlatformSignatureChecker` 新增一批
**标注核验**入口——对本机各发行版 jar 直接读 `@ApiStatus.Internal` / `@Deprecated(forRemoval)`
标注，把「新触平台 API 必须先核标注」的铁律变成可执行的测试，而不是靠人工记。

**T-MI-11（可选，仅当 P0-A 完成后仍想保留错误池体验）**：自持错误台账。
在 `IdeErrorReportSubmitter.submit()`（本来就是平台推给我们的入口）里落一份本地环形缓冲
（内存 + 可选落盘到 `.idea/`），手动形态从**自己的台账**取候选，完全绕开平台内部错误池。
既能恢复「从历史错误里挑一条」的体验，又不触 Internal API。**建议作为二期，不阻塞本轮上架。**

---

## 四、验收标准

1. `verifyPlugin -PverifyIdes=minimal`：**243 与 262 均 0 Internal API 使用**（262 由 6 → 0）。
2. `verifyPlugin -PverifyIdes=minimal`：**243 与 262 均 0 scheduled-for-removal**（现各 3）。
3. 全量 `./gradlew test` 绿（基线 4083/0/0/6，用例数按删除/新增用例调整后同步更新）。
4. `errorHandler` EP 主链回归：Internal Errors 对话框对本插件错误点「报告」仍走官网需求池
   （真机走查）。
5. 生成一张新的 buildPlugin 产物，重跑 verifier 复核（老的 `build/distributions/*.zip`
   是整改前产物，**不能拿它去验证**）。

## 五、真机走查清单（留人工）

1. **262 真机**：实体类 / 数据库表右键「生成 CRUD SQL」——列类型、主键、自增判定与整改前一致
   （覆盖 T-MI-07 的 `getDasChildren` + `getDasType().toDataType()` 中英型名原文）。
2. **262 真机**：JOIN 关系图 B 路径（内嵌 Database 原生图）——按表名定位仍能命中、
   虚拟外键线照常（覆盖 T-MI-08 的 `DatabaseCoreUiService` 真机可用性，**这是本轮最大不确定项**）。
3. **243 真机**：同上两项（`DatabaseCoreUiService` 在 243 的加载路径不同）。
4. **手动形态报告弹窗**：堆栈区可编辑粘贴、无「从 IDE 错误池导入」按钮、引导文案可点开 IDE 错误列表。
5. **错误形态报告弹窗回归**：Internal Errors 点「报告」→ 预填只读堆栈、附件 .txt 照常。
6. 从错误池导入的历史行为**明确移除**（属本期有意变更，用户在走查时不应认为丢了功能）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| `DatabaseCoreUiService` 真机不可用 | 262 在 `modules/*.jar`、243 在顶层，加载路径不同；编译期直连存在 262 找不到类的可能 | §五-2/3 真机必走；失败即退反射探测（项目已有 `DynamicInstallSupport` 的五入口反射先例可抄） |
| 手动形态体验退化 | 用户明确抱怨过「自动预填替用户做决定」，改引导后需用户自己开 IDE 错误列表复制 | T-MI-01 先核 `IdeErrorsDialog` 是否有更好入口；二期 T-MI-11 自持台账补回体验 |
| 删除 `pluginErrorPoolStacks` 牵连测试 | `FeedbackReportingContractTest` 有 `isPluginError` 用例 | T-MI-05/06 一并处理，全量 test 兜底 |
| 验证对象错配 | 拿整改前的 zip 跑 verifier 会得出假结论 | §四-5 明确要求先 `buildPlugin` |
| `verifySmoke` 的 false negative 再次发生 | 243-only 验证看不见 262 新增标注 | T-MI-09/T-MI-10 把 262 纳入日常口径 |

## 七、实施顺序

```
T-MI-01/02（信息面核对，决定 T-MI-04 形态）
   → T-MI-03（摘除 Internal 调用，指标立刻归零）
   → T-MI-04/05/06（手动形态改造 + 文案测试同步）
   → T-MI-07/08（scheduled-for-removal 清零）
   → 全量 test + verifyMinimal 复核（指标门槛）
   → T-MI-09/10（验证链加固）
   → 真机走查 §五
   → （二期）T-MI-11 自持错误台账
```

## 八、本轮验证记录（整改前基线）

- `./gradlew test`（独立沙盒 `build/test-sandbox-internal-api`，与并行任务 t-displaymode-verify 隔离）：
  **4083 tests / 0 failed / 0 errors / 6 skipped**，BUILD SUCCESSFUL 4m47s。
- `./gradlew verifySmoke`：243 Compatible，Internal 0。耗时 2m52s。
- `./gradlew verifyPlugin -PverifyIdes=minimal`：243 + 262 均 Compatible，
  262 Internal 6 / scheduled 3 / deprecated 52。耗时 7m39s。
- 残留提醒：本轮为核查临时在 `build.gradle.kts` 的 `intellijPlatform {}` 内加过
  `sandboxContainer = build/test-sandbox-internal-api`，**收工前必须删除**（并行隔离专用，
  不得进入提交）。
## 九、实施记录（2026-09-15，分支 t-market-internal @ 独立工作树 mpsv-t-marketinternal）

按铁律登记并行任务（t-market-internal，独立工作树 build 目录天然隔离，未改 build.gradle.kts）后实施。

### T-MI-01/02 信息面核对结论（决定 T-MI-04 形态）

- `IdeErrorsDialog` **无公开打开入口**：Companion 仅 `findPluginId`/`hashMessage`，类与构造器
  262 标 Internal（243 亦 Internal），程序化打开本身就是 Internal 使用——引导只能走文案。
- `errorHandler` EP 主链确认干净：262 `ExceptionsKt.processUnhandledException` javap 实证
  未捕获异常 → `MessagePool.addIdeFatalMessage` → 平台自身弹 `IdeErrorsDialog` → 用户点
  「报告」→ 平台按归属解析 `com.intellij.errorHandler` EP → 调本插件 `ErrorReportSubmitter`
  子类。整条链在平台内部，插件侧只实现 EP，`ErrorReportSubmitter.EP_NAME` 未被插件引用。
  **「报告到需求池」主链无需改动、完全合规。**

### 代码改动（8 文件，+64/−138）

| 文件 | 改动 |
|---|---|
| `FeedbackReporting.kt` | 删 `pluginErrorPoolStacks()`/`isPluginError()`/`PLUGIN_PACKAGE`（6 条 Internal 全在此链）；`openManualDialog` 去 `stackPoolProvider` 接线；留删除原因 KDoc |
| `ErrorReportDialog.kt` | 删 `stackPoolProvider` 构造参、`showStackImportMenu()`、`applyImportedStack()`、`stackImportButton` 字段及布局位；手动分支保留可编辑粘贴；KDoc 同步 |
| `DbTableSpecAdapter.kt` | `getDbChildren(Class, kind)` → `getDasChildren(kind)` + `filterIsInstance`；`getDataType()` → `getDasType().toDataType()`（getDasType 262/243 均 @NotNull，直调无安全调用） |
| `NativeDatabaseDiagramSupport.kt` | `DbPsiFacade.findElement` → `project.getService(DbModelRegistry).findDbElement`（见下） |
| bundle 双语 | 删 `stackImport`/`.tip`/`.empty` 三键；`stackPreview.placeholder` 改来源引导（「可从 IDEA 错误通知详情或『帮助 → 显示日志』中复制」） |
| `ErrorReportDialogLayoutTest.kt` | 默认态用例改锁「三键已删 + 占位引导落地」；删导入自动勾选用例 |
| `FeedbackReportingContractTest.kt` | 删 `isPluginError` 归属判定用例 |

### T-MI-08 实施升级（比计划更干净的一步）

计划拟改 `DatabaseCoreUiService.getInstance().findElement(project, das)`（DbPsiFacadeImpl 的转调目标）。
首版照此实施后 verifier 显示该方法**本身带纯 deprecated**（243/253/262 三版本 +1 deprecated，
非 for-removal、非 Internal）。javap 证实其实现体即 `project.getService(DbModelRegistry)
.findDbElement(das)`，而 `DbModelRegistry.findDbElement(DasObject)` 在 243（database-plugin.jar）/
262（intellij.database.core.impl.jar）均 public、非 deprecated、非 Internal——遂直取链尾，
把新增 deprecated 一并消掉。253 存在性由全量 verifyPlugin 实证。

### 验证结果（最终代码状态）

`./gradlew verifyPlugin`（全量三版本，9m20s）：

| 平台 | Internal | Scheduled-for-removal | Deprecated | Experimental |
|---|---|---|---|---|
| IU-243.28141.41 | **0**（整改前 0） | **0**（前 3） | 3（持平） | 14 |
| IU-253.33514.17 | **0** | **0** | 6 | 14 |
| IU-262.9437.185 | **0**（前 6） | **0**（前 3） | 52（持平） | 14 |

**Internal 与 scheduled-for-removal 三版本双清零，deprecated 无新增**——验收标准 1/2 达成
（且超额：全量含 253，覆盖了 DatabaseCoreUiService/DbModelRegistry 的中段版本存在性）。

全量 `./gradlew test`：**4081 / 0 / 0 / 6 绿**（基线 4083 − 删除的 2 个导入相关用例）。
首轮（DbModelRegistry 换法前）同数绿；换法后换法后重跑出现 1 例
`CopyFeedbackUiTest` 剪贴板 flake（`cannot open system clipboard`，已归档的环境性假失败，与本轮改动
无关），再次重跑 **4081 / 0 / 0 / 6 全绿**证实。
