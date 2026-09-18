# T-WriteSafeCtx 程序性文档写入上下文收敛 — 开发计划

- 日期：2026-09-17 15:55
- 任务编号：T-WriteSafeCtx（write-safe context 收敛）
- 来源：用户贴出的真机堆栈 `java.lang.Throwable: Write-unsafe context! ... current modality=ModalityState.NON_MODAL`，栈底直指 `SqlPreviewPanel.showSql(SqlPreviewPanel.kt:1711-1712)`
- 取证：运行环境 = 243.28141.41（IDEA 2024.3.7.1，与 `build.gradle.kts:93` 编译目标一致）——本次以 gradle 缓存内的 `ideaIU-2024.3.7.1-sources.jar` 平台源码逐帧对齐，不做推理
- 性质：上下文合法性缺陷（平台断言违反），非功能逻辑错误；多数表现为 SEVERE 日志 + 插件错误上报，单测/内部模式下会硬抛
- 实施方式：任务分支 t-writesafectx（扁平分支名）
- 前置：无（master 侧 `33b068a` 为本轮引入点，尚未推送发行，可直接在 master 上前置修复）

---

## 一、根因（平台源码级证据链，逐帧可核）

### 1.1 一句话结论

**在 IDE 里「在 `WriteCommandAction` 里写文档」并不足以让这次写入合法**——合法性由这次 EDT 活动的 write-safe 资格决定；而 `invokeLater(runnable, ModalityState.any())` 排出的活动**恒定不具备**该资格。平台 API 文档原文（`TransactionGuard.java:73-76`）：

> `ModalityState#nonModal()` is always write-safe, `ModalityState#any()` is always write-unsafe.

### 1.2 堆栈逐帧对齐（左=真机帧，右=243 源码，行号一致）

| 真机帧 | 平台源码 | 事实 |
|---|---|---|
| `SqlPreviewPanel.kt:800-806` | 本仓 `SqlPreviewPanel.kt:800-806` | `invokeLater({ renderSql() }, ModalityState.any())`（T-PreviewConsoleFmt `33b068a` 引入的「挂载后回填」） |
| `ApplicationImpl$2.run(ApplicationImpl.java:421)` | `ApplicationImpl.java:409-421` | `wrapWithRunIntendedWriteActionAndModality` 的匿名 Runnable 转 `runIntendedWriteActionOnCurrentThread` |
| `AnyThreadWriteThreadingSupport.kt:216/128` | 同文件 `:215-219`/`:125-133` | 只授予 **write-intent（读意图）**，不打开"允许写模型" |
| `TransactionGuardImpl$1.run(TransactionGuardImpl.java:201)` | `TransactionGuardImpl.java:192-203` | **关键帧**：`wrapLaterInvocation` 的 `else { runnable.run() }` 分支——**排队 modality 非 write-safe 时不套 `runWithWritingAllowed`** |
| `TransactionGuardImpl.performActivity(TransactionGuardImpl.java:109)` | `TransactionGuardImpl.java:105-110` | `allowWriting = userActivity && isWriteSafeModality(ModalityState.current())`；命中 `myWritingAllowed == allowWriting` 早返回分支（=写入资格未被打开） |
| `IdeEventQueueKt.performActivity(IdeEventQueue.kt:1054)` | `IdeEventQueue.kt:1054` | `performActivity(isInputEvent(e) \|\| e is ItemEvent \|\| e is FocusEvent, ...)`——**posted runnable（InvocationEvent）不是输入事件 → userActivity=false** |
| `SqlPreviewPanel.kt:1711-1712` | 本仓同位置 | `ExecDocAutosaveGate.suppressDuring { WriteCommandAction.runWriteCommandAction(project) { nativeDoc.setText(text) } }` |
| `WriteCommandAction.java:329/341` | 同文件 | 落到 `@TestOnly` 的 `runWriteCommandAction(Project, Runnable)` 重载（Kotlin 对 Unit lambda 的解析结果，见 §三 T-WSC-05） |
| `DocumentImpl.java:1109/1114 → 717` | 同文件 | `setText` → `assertWriteAccess` → `TransactionGuardImpl.assertWriteActionAllowed` |
| `TransactionGuardImpl.java:143` | `TransactionGuardImpl.java:141-145` | `if (!myWritingAllowed && areAssertionsEnabled()) LOG.error(reportWriteUnsafeContext(...))` —— 报错点 |

`areAssertionsEnabled()` 依赖 registry `ide.require.transaction.for.model.changes`，本机 262 安装内实测该键默认值 = **true**（`lib/util-8.jar!/misc/registry.properties`）——即**生产 IDE 恒开**，不是调试开关。

### 1.3 两条被证伪的既有假设（必须写进注释，否则会再犯第三次）

1. **「走 document 写（非裸写）就安全」**——T-PreviewConsoleFmt 计划 §六 风险表原文即此判断。document 写只规避「绕过文档层的裸写」类问题（File Cache Conflict 系），与 write-safe 资格无关；本次两处**都是** `WriteCommandAction` + `document.setText`，照样报错。
2. **「`any()` 的动机是 M36.5.13 的模态期间照常执行」**——该惯例对**纯 UI 回调**成立（`SqlPreviewPanel.kt:970` 注释「纯 Swing 操作安全」），对**文档写入**恰恰相反：模态弹窗存续期改文档正是平台禁止的行为。两条语义必须分开取模态。

### 1.4 实际后果（决定优先级：不是功能中断，是纪律性错误）

- 生产日志走 `IdeaLogger.error`（`com/intellij/idea/IdeaLogger.java:143-157`）：**只记 SEVERE + `logErrorHeader` 打出「Plugin to blame: <本插件>」+ reportToFus**，不抛异常——**写入照常完成**，用户功能无感。
- 单测/内部模式走 `DefaultLogger.error`（`DefaultLogger.java:81-83`）：**抛 AssertionError**——这也是为什么面板测试全绿（测试不触发 DBN 控制台挂载链）而真机报错。
- 净损失：插件错误仪表盘被污染（挂载后回填命中率 = 每次预览一条）、真机日志噪音、以及「任何未来一处 `any()` 里的写入」随时升级为硬失败。

### 1.5 历史账（同一坑的第三、第四次）

- 第一次：M36.5.21c `invokeLater(any)` 调 `doRunQueryInConsole`（write-unsafe）→ M36.5.21d 终局改 `TransactionGuard.submitTransactionLater`。归档见 `docs/memory-archive/pitfalls/feedback-m36-native-console-misalignment.md` 第 15 条。
- 第二、三、四次即本计划 §二 清单（M36.5.x 的 contentAdded 重写、执行错误回显；`33b068a` 的挂载后回填；SqlToJava 生成码回填）。
- **前三轮都在"改那一处"，没有制度约束**——本计划的核心价值在 T-WSC-04/05（闸门 + 守卫测试），§三 的 P0 两行只是止血。

---

## 二、现状核实（写入类 `any()` 站点全清单，实测 + blame）

写入口收敛点：`SqlPreviewPanel.renderSql()`（`:1340`）是面板三条写入分支（nativeDoc / 自建 editor / createEditor 建文档）的唯一上游；`SqlToJavaPanel.setResultText`（`:1090-1094`）、`DataSourceSelector.writeConsoleOutput`（`:683-717`）为另两处独立写点。

| # | 站点 | 引入 | 写入内容 | 判定 |
|---|---|---|---|---|
| 1 | `SqlPreviewPanel.kt:800-806` | `33b068a` T-PreviewConsoleFmt | `renderSql()` → `showSql` → `setText` | **必改**（本次报错站点；每次预览必触发，因预热原文≠渲染文本） |
| 2 | `DataSourceSelector.kt:884-935` | `0e57b4b3`/`2b44cbb6`/`68ebdab1` M36.5.25c | `consoleDoc.setText(currentSql)` + `commitDocument` + `saveDocument` | **必改**（条件：`consoleDoc.text != currentSql`，故偶发） |
| 3 | `DataSourceSelector.kt:796-798` | M36.5.70 | `writeConsoleOutput` → `doc.insertString` | **必改**（执行失败回显路径；Timer 拍内触发） |
| 4 | `SqlToJavaPanel.kt:968-975` | 既有 | `render(...)` → `setResultText` → `setText` | **必改**（sql→java 生成码回填） |
| 5 | `SqlPreviewPanel.kt:843/879/970` | M36.5.x | 仅 divider/revalidate/relayout | 保留（纯 UI），加豁免标注 |
| 6 | `HistorySqlLogPanel.kt:231`、`FeedbackReporting.kt:46`、`PluginSettingsPanel.kt:1458/1493` | — | 列表重绘/通知/行内状态 | 保留（纯 UI） |
| 7 | `DataSourceSelector.kt` 其余 19 处 `any()`（417/466/1136/1245/1374/1632/1990/2024/2446/2464/2470/2493/2499/2517/2614/2717/2771/2789/2793） | — | 令牌扫描未见直接写入 | **逐个复核**（1136 为只读诊断；2464/2470 在被废弃的 transaction 块内） |
| 8 | `DynamicInstallSupport.kt:225` | — | 动态插件卸载/装载（模型级变更） | 存疑待复核（非文档写，但平台对卸载的上下文要求未核实） |

另注：`DataSourceSelector.kt:2437` 的 `submitTransactionLater` 是 M36.5.21d 的正确先例，本次不动（该 API 在 243 已 `@Deprecated`，但行为仍是 write-safe，属清理性质，见 T-WSC-05）。

---

## 三、方案

统一契约（写进新增闸门的 KDoc，作为本仓此后写文档的唯一口径）：

> **EDT 上的程序性文档写入，只有在「用户输入活动 + write-safe 模态」或「以 write-safe 模态投递的活动」中才合法。**`ModalityState.any()` 恒不安全；`ModalityState.nonModal()` 恒安全；`defaultModalityState()` 仅在**写安全上下文内创建**时才安全（`enteredModality` 记录的是进入该模态那一刻的 `myWritingAllowed` 快照）。判据 API：`Application.isDispatchThread && TransactionGuard.getInstance().isWritingAllowed()`。

### T-WSC-01（P0，报错站点止血 + 面板自愈闸门）

1. `SqlPreviewPanel.kt:800-806`：`invokeLater({ renderSql() }, ModalityState.any())` → 写安全投递。取 `ModalityState.nonModal()` 并在注释里写明取舍：本处是**文档写入**，模态弹窗存续期本就不得改文档，故放弃 `any()` 的「模态期间照常执行」语义（该语义只保留给 §二 #5 的纯 UI 回调）。
2. `renderSql()`（`:1340`）入口加自愈闸门（一层保险，覆盖全部既有/未来调用方，含去抖 `scheduleRender`、Timer 拍、回调链）：
   ```kotlin
   if (!DocWriteContext.isWriteAllowedNow()) {
       DocWriteContext.invokeLaterWriteSafe(this) { renderSql() }   // 幂等重算，重新投递不会写出过期文本
       return
   }
   ```
   **闸门必须放在 `renderSql()` 而不是 `showSql(text)`**：`showSql` 收的是调用时刻的文本快照，延迟投递会写出过期 SQL；`renderSql` 在投递时刻重算，天然 last-wins。
3. 投递一律带 Disposable 过期条件（`invokeLater(runnable, modality, Condition)` 三参重载），面板销毁即丢弃排队任务，避免 `Disposer.isDisposed` 前的空转与泄漏。

### T-WSC-02（P0，同型三处快照写）

#2/#3/#4 三处都是「用快照文本写文档」，不适用 T-WSC-01 的自愈（重投递会写出旧文本），必须**在投递时刻就是 write-safe**：

- `DataSourceSelector.kt:884`：`invokeLater({...}, ModalityState.any())` → `ModalityState.nonModal()`（换入后重写/落盘仍在该拍内完成，顺序不变）。
- `DataSourceSelector.kt:796`：同上（错误回显）。注意其上游是 Swing Timer 拍（Timer 活动同样是 userActivity=false，**即使不套 invokeLater 也不可写**），闸门式收敛见 T-WSC-03。
- `SqlToJavaPanel.kt:968`：同上。

### T-WSC-03（P1，新增统一写入门 `DocWriteContext`）

新增 `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DocWriteContext.kt`：

```kotlin
object DocWriteContext {
    /** EDT + 平台允许写模型。BGT/异常一律 false（保守方向=投递，宁延迟不违规）。 */
    fun isWriteAllowedNow(): Boolean
    /** 就地写：允许写→block()；不允许→以 nonModal 投递（可带 Disposable 过期条件）。 */
    fun runWriteSafe(expireWith: Disposable?, reason: String, block: () -> Unit)
    /** 纯投递形态：Application.invokeLater(runnable, ModalityState.nonModal()[, expireWith])。 */
    fun invokeLaterWriteSafe(expireWith: Disposable?, runnable: Runnable)
}
```

接点：`SqlPreviewPanel.renderSql()` 入口（T-WSC-01.2）、`DataSourceSelector.writeConsoleOutput` 的写临界区（`:701`）、`SqlInputEditor`/`LogToSqlPanel`/`ParamImportDialog` 等既有 `runWriteCommandAction` 站点**仅在真机复验暴露问题时**接入（避免无谓 diff）；`isWriteAllowedNow` 做成 `internal var` 可注入测试钩子。

### T-WSC-04（P1，全量审计与豁免标注）

1. §二 #7/#8 逐个复核（判据：该 lambda 直接或经函数调用写文档/PSI 模型 → 改；纯 UI → 保留）。
2. 保留 `any()` 的站点，在 `ModalityState.any()` 同行加 `// write-safe: UI-only`（守卫测试的豁免标记，见 T-WSC-05）。
3. `ExecDocAutosaveGate` KDoc 补一句边界说明：该闸门只管「抑制自动落盘」，**不提供写入合法性**——两件事别混（本轮混淆风险点之一）。

### T-WSC-05（P2，防复发守卫，本计划核心）

1. **源码扫描守卫**：扩展 `src/test/kotlin/.../convention/BannedApiSourceScanTest.kt`——新增规则：用括号配平从 `invokeLater(` 定位到匹配右括号，区间内同时出现 `ModalityState.any()` 与写入令牌（`setText(`/`insertString(`/`replaceString(`/`runWriteCommandAction`/`commitDocument(`/`saveDocument(`/`renderSql(`/`recoverConsole(`/`setResultText(`/`WriteAction.`）即失败；豁免 = 原始（未剥注释）区间内含 `write-safe: UI-only`。规则注释写明本次事故出处（同该文件既有条目风格）。
2. **行为测试**：`SqlPreviewPanelUiTest` 增一例——注入 `DocWriteContext.isWriteAllowedNow = false`，调 `renderSqlForTest()`，断言：未发生同步写入、产生一次 nonModal 投递（投递器 spy 记录模态与次数）、flush 后文档文本正确。
3. 附带清理（低优先，可并入）：`runWriteCommandAction(project) { ... }` 实测落到 `@TestOnly` 重载（`WriteCommandAction.java:329`，真机栈帧实证）——统一改成 `writeCommandAction(project).run { }` 形态或 5 参重载；如需固化为规则，加进同一守卫测试。

### T-WSC-06（收尾）

全量 testShards 绿 + testDatabase 不变——并附沙盒复现证据：修复前后各跑一次「预览一条 SQL」，`grep "Write-unsafe context" <sandbox>/log/idea.log` 由「有」变「无」。

---

## 四、验收标准

1. §二 #1~#4 全部改毕；`any()` 站点全部有「改毕 / UI-only 豁免标注 / 复核结论」三态之一，清单回填至本文档。
2. `DocWriteContext` 落地并被 `renderSql` 与 `writeConsoleOutput` 接入；闸门注释含平台契约原文与两条被证伪假设。
3. 守卫测试与行为测试落地；构造一次违规（临时把某站点改回 `any()`）能可靠失败、加豁免标记后通过。
4. 全量 test 绿；沙盒真机复验 §五 三条链路日志零 `Write-unsafe context`。

## 五、真机走查清单（留人工）

1. 预览一条多行 SQL（触发 `prepareDefaultConsole` → 控制台嵌入 → 回填）：控制台显示格式化文本（T-PreviewConsoleFmt 语义不回退），日志无 `Write-unsafe context`、无 `[M36] post-attach re-render FAILED`。
2. 执行一条失败 SQL（触发 #3 错误回显）+ 参数编辑去抖渲染（触发 `scheduleRender`）：日志同上；错误文本出现在 Output 区。
3. sql→java 生成一次（触发 #4）：结果区回填正常、日志无一例 write-unsafe。
4. 模态弹窗场景（File Cache Conflict / 数据源密码框）存续期预览一次：回填延后到弹窗关闭后发生（**预期行为**，与 `any()` 时代不同，需目视确认无"控制台永久裸单行"）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| nonModal 语义变化 | 模态弹窗存续期的回填从「立即」变「延后到弹窗关闭」 | 本处为文档写入，延后是平台要求的正确语义；走查项 4 目视兜底；注释写明取舍理由 |
| 闸门引入投递环路 | 若 `isWriteAllowedNow()` 在投递后的活动里仍为 false 则死循环 | 投递后的活动以 nonModal 排队 → `wrapLaterInvocation` 必套 `runWithWritingAllowed` → 第二次调用恒为 true（单测断言投递次数=1） |
| 写出过期文本 | 闸门错放在 `showSql(text)` 上 | 闸门定在 `renderSql()`（幂等重算）；#2~#4 三处快照写**不套闸门**，只改模态 |
| 守卫测试误报 | 括号配平跨越无关代码块 | 区间限定在 `invokeLater(` 的同类括号内；豁免标记走原文扫描；命中即输出文件:行与令牌，便于人工判读 |
| 陈旧产物劫持 | 新增 `DocWriteContext` 后首跑可能 NoSuchMethodError | 首跑前清 `build/classes` + `build/kotlin`（六犯实录） |
| 审计遗漏 | §二 #7/#8 复核靠人眼 | 复核结论必须逐条回填本文档（三态标注），不写"已复核"这种无量词结论 |

---

## 七、实施记录（2026-09-17，master 直改，未推送）

### 7.1 落地改动

| 项 | 文件:行 | 改动 |
|---|---|---|
| T-WSC-03 | `execute/DocWriteContext.kt`（新增，77 行） | `isWriteAllowedNow()` / `invokeLaterWriteSafe()` / `runWriteSafe()`；KDoc 载平台契约（nonModal 恒安全、any 恒不安全、`enteredModality` 快照语义）+ 两条被证伪假设 |
| T-WSC-01.1 | `ui/SqlPreviewPanel.kt:800-811`（nonModal 在 :811） | 挂载后回填 `ModalityState.any()` → `nonModal()`（本次报错站点） |
| T-WSC-01.2 | `ui/SqlPreviewPanel.kt:1350-1356` | `renderSql()` 入口加自愈闸：不可写 → `invokeLaterWriteSafe(this) { renderSql() }` 后 return |
| T-WSC-02.1 | `execute/DataSourceSelector.kt:800-803` | 执行错误回显（`writeConsoleOutput` → insertString）`any()` → `nonModal()` |
| T-WSC-02.2 | `execute/DataSourceSelector.kt:936-944` | contentAdded 重写 console 文档（setText+commit+save）`any()` → `nonModal()` |
| T-WSC-02.3 | `execute/DataSourceSelector.kt:1239-1259` | editor 异步等待续接 `prepareAfterEditorResolved`（内含 `:1354` WriteAction 写 console 文档）`any()` → `nonModal()` |
| T-WSC-02.4 | `log/SqlToJavaPanel.kt:967-974` | 生成码回填（`render` → `setResultText` → setText）`any()` → `nonModal()` |
| T-WSC-04 | 5 个文件共 23 处 | 纯 UI/只读站点加豁免标注：22 处 `// write-safe: UI-only（纯 Swing/只读，无文档写）`（SqlPreviewPanel 3 / DataSourceSelector 14 / PluginSettingsPanel 2 / HistorySqlLogPanel 1 / FeedbackReporting 1）+ 2 处 `// write-safe: 无 document 写（反射替换 DBN script model）`（`refreshConsoleModel` 两处） |
| T-WSC-05.1 | `convention/BannedApiSourceScanTest.kt` | 新守卫 `禁止在any模态回调里做文档写`：括号配平取 `invokeLater(` 调用体，`ModalityState.any()` + 24 个写入令牌 → 失败；豁免=调用行**向上连续注释块**含 `write-safe:` |
| T-WSC-05.2 | `execute/DocWriteContextTest.kt`（新增） | 7 例：探针判定 / 单测放行 / 不可写则投递（计数+1 且不同步写）/ 可写则就地（零投递）/ owner 已销毁丢弃投递拍 / 面板级 `renderSqlForTest` 不可写只投递 / 面板级可写零投递 |

### 7.2 验证证据

| 证据 | 结果 |
|---|---|
| 定向测试 | `DocWriteContextTest` 7/0/0/0 + `BannedApiSourceScanTest` 6/0/0/0 全绿（清 `build/classes`+`build/kotlin` 后首跑，无陈旧产物劫持） |
| 守卫**负向验真**（对照实验） | 临时把 `DataSourceSelector.kt:800` 改回 `any()` → 守卫红且报 `命中 1 处：[…DataSourceSelector.kt:800 → writeConsoleOutput(]` → 还原后绿（证明非空绿） |
| 全量 testShards | **4301/0/0/6 绿（3m8s，348 个测试类 / 3 分片）**——较基线 4293/0/0/6 增 8，全部为本计划新增用例（DocWriteContextTest 7 + 守卫 1），无回归 |
| 真机/sandbox | 未跑（见 §八 遗留） |

### 7.3 §二 清单三态回填（逐条结论）

| # | 站点 | 结论 |
|---|---|---|
| 1 | `SqlPreviewPanel:800-811` | 改毕（nonModal） |
| 2 | `DataSourceSelector:936-944` | 改毕（nonModal） |
| 3 | `DataSourceSelector:800-803` | 改毕（nonModal） |
| 4 | `SqlToJavaPanel:967-974` | 改毕（nonModal） |
| 5 | `SqlPreviewPanel:843/872/977` | UI-only 标注（分隔条/滚动/重挂，纯 Swing） |
| 6 | `HistorySqlLogPanel:229`、`FeedbackReporting:41`、`PluginSettingsPanel:1451/1488` | UI-only 标注（列表重绘/通知/设置页行内状态） |
| 7 | `DataSourceSelector` 其余 14 处 | 复核结论：11 处纯 UI（分隔条/滚动/重挂/填充布局/结果 watch/onError 状态区）标注 UI-only；1 处只读诊断（DataContext 探针 ReadAction）标注；2 处 `refreshConsoleModel`（反射替换 DBN `myScriptModel` + `splitScriptMode`）**非 document 写**故不触发本断言，但 DBN 内部是否要求 write-safe 无实证——标注留档、维持现状 |
| 8 | `DynamicInstallSupport:225` | **未复核完成**：动态插件卸载/装载属模型级变更，平台对它的上下文要求未核实；无实证报错，本计划不动（列 §八 遗留） |

### 7.4 明确未做（防下次误判"已全覆盖"）

1. `DataSourceSelector.dispatchDocWriteOnEdt`（`:3153`/`:3159`）两处 `invokeLater {}` 用 **defaultModalityState** 投递文档写：常态落到 NON_MODAL（恒 write-safe）故不报错；仅当外部模态框的 modality 非 write-safe 时才不安全，无实证。改 nonModal 会让「模态存续期」的 exec 文档同步撞上 2s 有界等待超时（T-PreviewExecDs/T-ExecDocSync3 语义），风险大于收益——**维持现状并留档**。
2. `runWriteCommandAction(project) { … }` 实测落到 `@TestOnly` 重载（真机栈帧 `WriteCommandAction.java:329`）：无功能差异，仅 API 卫生问题，未纳入本次改动。
3. 真机/sandbox 日志 grep 复验未跑（见 §八）。

---

## 八、遗留与后续

1. **真机复验（用户侧，门禁）**：`idea.log` 与沙盒日志 grep `Write-unsafe context` 零新增；预览一条多行 SQL 后控制台仍是格式化多行（确认 T-PreviewConsoleFmt 语义未因模态变化回退）；模态弹窗（File Cache Conflict）存续期预览应延后回填到弹窗关闭（预期行为，见 §五.4）。
2. `DynamicInstallSupport:225` 的上下文要求待复核（无实证报错，未动）。
3. `@TestOnly` 重载清理（低优先，可与下次 hygiene 批次合并）。
4. 本计划尚未推送：master 侧与既有 5 个未推送提交同批，推送时机由用户定。

