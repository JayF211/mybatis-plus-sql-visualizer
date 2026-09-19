# T-FatalPassthrough 错误通道 fatal 透传与 `catch(Throwable)` 收敛 — 开发计划

- 日期：2026-09-19 04:51
- 任务编号：T-FatalPassthrough（第八轮报告 §9.4 **O-38** 的**拆分后半**，P2；原 O-38「错误通道脱敏诊断 + fatal 透传」经核实拆为两项，本计划承接 fatal 面，脱敏面见 T-ErrorReportRedact）
- 来源：第八轮报告 §7.2 zMyBatis 精华 **Z-21**——失败只留 cause 链末端类名、`VirtualMachineError`/`LinkageError`/`ThreadDeath` 白名单放行不吞（`XmlMapperPreparationEngine.kt:380-392`）
- 性质：**错误语义正确性**——防止 JVM 级致命错误被降级成「一条 warn 日志」而丢失崩溃现场
- 实施分支：自 master 拉任务分支 t-fatal-passthrough
- 验证基线：master（testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿）

---

## 一、背景与依据（现状核实）

**核实结论：本仓 fatal 白名单完全空白，且存在大量会吞掉 fatal 的捕获点。**

| 项 | 实测 | 证据 |
|---|---|---|
| fatal 白名单 | **零**——grep `VirtualMachineError|LinkageError|ThreadDeath|rethrowFatal|fatal`（`src/`，`*.kt`）→ **0 命中** | 全仓 |
| `catch (…: Throwable)` | **74 处**——会把 `VirtualMachineError`/`LinkageError` 一并吞成 warn/UI 提示，不 rethrow | grep 计数 |
| `catch (…: Exception)` | 47 处 | 同上 |
| 代表捕获点（吞后仅 warn） | `execute/DataSourceSelector.kt:2584-2600`（原生执行链，吞后 `LOG.warn` + `onError`）、`:2602-2604`/`:3220`/`:3237`/`:3248`/`:3254`/`:3262`/`:3856-3887` | 同上 |
| 代表捕获点（agent 侧） | `log/agent/AgentManager.kt:293/373/390/542/576/621/859/1014/1038/1078`（10 处）、`log/RunConfigurationProcessListener.kt:71/83/91/105/113/125/142`（7 处） | 同上 |

**为什么值得做**：`OutOfMemoryError`、`StackOverflowError`、`NoClassDefFoundError`（LinkageError 子类）被吞成一条 warn 之后，用户侧看到的是「功能没反应」或「执行失败：xxx」，而真正的 JVM 级故障现场（栈、类加载失败链）只剩一条日志行；且被吞后**流程继续**，可能在下游以更不可解的形式再次失败（这与本项目 T-ReachAudit 系列「静默失效」是同族问题，只是发生在错误通道本身）。

**边界（重要）**：本任务**不是**「把 74 处 catch 全改掉」——其中绝大多数（反射探测、平台 API 兼容降级、可选能力探测）吞掉普通异常是**设计意图**（对齐 `runCatching` 的既有惯例）。本任务只处理「**捕获面覆盖了 fatal 错误类型**」这一层。

**对照事实**：`onFailure {}` 空实现 0 处、完全空 catch 仅 1 处（`log/SqlLogFileReader.kt:264`）——说明既有代码在「不吞普通异常」上纪律良好，缺口单在 fatal 维度。

## 二、目标与非目标

**目标**：JVM 级致命错误（`VirtualMachineError` 及其子类含 `StackOverflowError`/`OutOfMemoryError`、`LinkageError`、`ThreadDeath`）在跨捕获边界时**不被静默吞掉**，而是透传到平台/进程级错误处理。

**非目标**：不动普通异常的降级语义（反射探测、可选能力探测的吞掉行为**保持原样**）；不新建统一错误封装（现状「日志 + 通知 + `onError`/`onErrorRich` + `errorHandler` EP」四通道各有职责，强行统一风险大于收益——该结论已由锚点核实给出，登记为文档项）；不碰 agent 侧 SQL 参数脱敏开关（`config/PluginSettings.kt:455-456`，那是采集面而非错误面）。

## 三、方案

**T-FP-01（fatal 判据纯函数）**：新增 `util/FatalErrors.kt`——`object FatalErrors { fun isFatal(t: Throwable): Boolean }`，白名单为 `VirtualMachineError` / `LinkageError` / `ThreadDeath`（对齐 zMyBatis 同款三类，含注释说明「为何这三类不该被吞」）。放纯逻辑层，可裸 JUnit 直测（进 `testFast` 桶）。

**T-FP-02（收敛 `catch (Throwable)` 的处置形态）**：**不逐处改 74 处**，而是给出一个可复用的收敛形态并在**高风险子集**落地：
- 形态 A（推荐，改动小）：在捕获块首行加 `if (FatalErrors.isFatal(t)) throw t`（或在既有统一 helper 内加）。
- 形态 B：把 `catch (t: Throwable)` 改为 `catch (t: Exception)`——但这会**改变**对 `Error` 的既有捕获面，需逐处评估（有些地方确实需要接住 `Error` 做清理），故不作为默认。
- **落地子集**（按「吞掉后危害最大 + 属长驻链路」排序）：①执行链 `execute/DataSourceSelector.kt`（`:2584-2600` 及同族）；②agent 长驻链 `log/agent/AgentManager.kt`（10 处）；③`log/RunConfigurationProcessListener.kt`（7 处）。其余点登记清单，不强行一次收口。

**T-FP-03（静态守卫测试，防回潮）**：新增源码扫描守卫（照 `convention/BannedApiSourceScanTest.kt` 既有形态）——扫描 `catch (…: Throwable)` 块，要求块内出现 `isFatal` 判定或显式豁免注释（豁免白名单需列文件:行 + 理由，且**豁免清单必须可解释**）。**先红后绿**：先在子集外的某处构造一个无判定的 `catch (Throwable)` 证明守卫会红。

**T-FP-04（文档与登记）**：①`docs/架构设计/` 错误通道一节补「四通道职责 + fatal 透传约定」（口径核实明确不做统一封装，此处只做文档化）；②登记清单：未收口的 `catch (Throwable)` 点（约 60 处）列出文件:行与豁免理由，进计划 §八 或专项登记；③CHANGELOG `[Unreleased]` + 变更记录行 + 第八轮报告 §7.2 Z-21 落地备注。

## 四、验收标准

1. `FatalErrors.isFatal` 表驱动用例覆盖：三类 fatal 及其子类（`StackOverflowError`/`OutOfMemoryError`/`NoClassDefFoundError`）判 true；`Exception`/`RuntimeException`/自定义异常判 false。
2. 落地子集的每个捕获点有「fatal 透传」用例或断言（构造 fatal 子类抛出 → 断言被 rethrow 而非吞）。
3. 守卫测试先红后绿，且豁免清单每项带理由。
4. 普通异常降级行为**逐字节不变**：反射探测/可选能力探测的既有吞掉路径用例全绿（防「顺手全改」回归）。
5. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工）

1. 构造一次可复现的 `StackOverflowError`（或 `NoClassDefFoundError`）场景（如故意让某可选依赖缺失）→ 确认错误以平台级错误呈现（Internal Errors 对话框/进程级），而非仅一条 warn。
2. agent 链路 fatal 场景：应用侧崩溃时 IDE 侧行为如实（不假装「在线」——与 T-RA-01 心跳窗口的判定联动观察）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 误伤既有降级 | 把设计意图的吞掉改成抛出，破坏可选能力降级 | 只改「捕获面覆盖 fatal」这一层；验收 4 以既有降级用例作回归门 |
| 透传后崩溃面外扩 | fatal 透传到 UI 线程可能引发更多报错 | 透传目标交给平台（`errorHandler` EP 已有），不自建处理；先在小范围（执行链 + agent 链）验证 |
| 守卫误报 | 74 处逐个人工判定成本高 | 守卫只要求「判定或豁免注释」，豁免清单允许分批补齐（本任务给出首批 + 登记余量） |
| 与并行会话冲突 | `execute/`、`log/agent/` 是高频改动域 | 实施前按并行铁律 check/register；写集与 T-TailSweep2 的 T-FS-* 无交（后者在 `xml/`、`converter/`、`config/`、`convention/BannedApiSourceScanTest.kt`——**注意 T-FS-06 同触守卫文件，须串行**） |

## 七、实施顺序

```
T-FP-01（判据纯函数）→ T-FP-02（子集落地，执行链 → agent 链 → 监听链）→ T-FP-03（守卫，先红后绿）→ T-FP-04（文档与登记）→ 真机走查 §五
```

与 T-TailSweep2 的 T-FS-06（同改 `convention/BannedApiSourceScanTest.kt`）**必须串行**——建议在 T-FS-06 之后实施本计划，或本守卫另建文件。

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-FP-01 | `5cc0710` | `util/FatalErrors.kt`：`isFatal` 纯函数，白名单 `VirtualMachineError`/`LinkageError`/`ThreadDeath`（KDoc 逐类说明为何不该吞，并写明「其余 Error（如 AssertionError）刻意不算 fatal」）。用例 `FatalErrorsTest` 5 例（表驱动三类及子类 true / 普通异常与白名单外 Error false / **真递归撞栈**得到的真实 `StackOverflowError` / 反射 `InvocationTargetException` 须解包后判 / 形态 A 片段只放行 fatal）。 |
| T-FP-02 | `e2517bf` | 形态 A（`if (FatalErrors.isFatal(t)) throw t`）落地 **30 处 / 3 文件**：`execute/DataSourceSelector.kt` 13、`log/agent/AgentManager.kt` 10、`log/RunConfigurationProcessListener.kt` 7。原生执行链的捕获点先解包 `InvocationTargetException.targetException` 再判（只判外层会漏判目标方法抛出的 fatal）。行为用例 `FatalPassthroughTest` 3 例（`AgentSession.sendConfig` 站点：真 SOE 与 `NoClassDefFoundError` 原样透传且**不执行降级副作用**——`alive` 不置 false、不触发状态回调；`IOException` 仍 `alive=false` + 状态回调，防「顺手全改」回归）。 |
| T-FP-03 | `4147432` | 守卫落在 `convention/BannedApiSourceScanTest.kt`（同文件 T-FS-06 已重构完毕，串行约束满足）：扫全部 `catch (…: Throwable)` 块（嵌套块独立成站点、注释与字符串经 `maskNonCode` 涂白），要求块内出现 `FatalErrors.isFatal(` 或文件在豁免表登记。**豁免 44 处 / 18 文件**（文件 + 站点数 + 理由；逐处行号清单在 `fatalPassthroughOffenders` KDoc）；已落地 3 文件不得进豁免表且站点须全带判定；另设反射解包锚点。**先红后绿实录**：临时移除 `RunConfigurationProcessListener.kt:88` 判定 → 守卫红（报「命中 1 处：[…RunConfigurationProcessListener.kt:88]」），还原后绿；行为用例同样先红（移除 `AgentManager` `sendConfig` 判定 → 2 例红、`IOException` 例仍绿）。自证用例覆盖三态 + 边界（嵌套内层、注释里的判定字样、`catch (…: Exception)`、Java 模板字符串里的 `catch (Throwable t)`）。 |
| T-FP-04 | 见本文件所在提交 | 未收口点登记位置 = 守卫 `throwableCatchExemptions`（44 处 / 18 文件，逐条理由；**T-FP-02b 后为 43 处 / 17 文件**——`ui/SqlPreviewService.kt` 收口移入已落地表，见 §十）+ 本表；「四通道职责 + fatal 透传约定」落在 `util/FatalErrors.kt` KDoc。计划 §T-FP-04①（`docs/架构设计/` 错误通道一节）与 ③（CHANGELOG `[Unreleased]` + 变更记录行 + 第八轮报告 Z-21 备注）落在**写集外文件**，未改——见 §九。 |

## 九、实施后补充（偏离与未验证项）

**偏离**

1. **T-FP-04①③ 未做**：`docs/架构设计/` 与 `CHANGELOG.md`/`docs/变更记录.md` 不在本任务写集（派活方硬约束：只改写集内文件、不碰变更记录与盘点文档）。文档内容改落在 `util/FatalErrors.kt` KDoc（fatal 判据 + 透传用法 + 反射解包形态 + 四通道各司其职、不做统一封装的结论）与本节；第八轮报告 Z-21 的落地备注、CHANGELOG 条目需派活方另行补。
2. **豁免机制形态偏离计划原文**：计划写的是「块内出现 `isFatal` 判定**或显式豁免注释**」，实施改为「判定**或守卫内豁免登记**（文件 + 站点数 + 理由）」。原因：44 处未收口点分布在 18 个文件（`log/`、`actions/`、`ui/` 是高频改动域），加注释会越写集；而行号级白名单会因并行会话的合法编辑漂移把守卫误报成红（计划 §六 风险表已列此风险）。行号清单仍逐处登记在守卫 KDoc 供人工核对，站点数漂移即红（新增无判定捕获点必须补判定或重新登记）。
3. **写集内 `log/SqlLogFileReader.kt` 与 `config/PluginSettings.kt` 零改动**：前者两处 `catch (_: Exception) { null }`（时间戳解析回落）捕获面不含 `Error`，后者全文件无 catch 块（计划 §二 明确不碰 `:455-456` 脱敏开关，那是采集面而非错误面）。

**未验证 / 环境受限**

- §五-1「构造一次可复现的 `StackOverflowError`/`NoClassDefFoundError` 场景 → 确认以平台级错误呈现（Internal Errors 对话框/进程级）」：**环境受限未验证**——缺 runIde 沙盒内人工确认 IDE 弹窗/Internal Errors 的条件。测试级已走通「透传而非吞」这一半：`FatalPassthroughTest` 用**真递归撞栈**产生的真实 `StackOverflowError`（带真实现场栈）与 `NoClassDefFoundError` 断言被原样抛出且不执行降级副作用；「平台侧呈现形态」仍属人工复核项。
- §五-2「agent 链路 fatal 场景 + 与 T-RA-01 心跳窗口联动观察」：**环境受限未验证**（同需真机；测试侧只能证明抛点透传，证明不了 IDE 侧状态行文案）。
- **残留边界（初版登记口径有误，已由 §十 订正）**：若抛点的外层是 `runCatching {}` 或 `invokeLater` lambda，本任务按计划只处理 `catch (…: Throwable)` 块，透传后的 fatal 仍会被外层接住或交给平台 EDT 处理器。初版本节写作「已核实的具体残留（均在写集外，未改）」，经独立复核证伪——其中 `execute/DataSourceSelector.kt` 的 3 处同族 `runCatching` 抵消点**在写集内且未登记**（初版只登记了写集外的两处），`log/agent/AgentManager.kt` 的 `resolvePid` 外层 `runCatching` 亦在写集内（初版行号 `:836` 是基线行号，实际 `:847`）。完整订正与处置见 §十。
- **未纳入行为用例的站点（结构断言兜住）**：`AgentManager` 确认框（`requestConsentThenAttach`）与 `DataSourceSelector` 原生执行链 `submitTransactionLater` 段的抛点位于 `invokeLater` / 事务 lambda 内，fatal 交给平台 EDT 处理器（这正是期望的「平台级呈现」路径，只是无法在单测断言侧捕获——故这些站点只有结构断言，无行为用例）。

**计数订正**：`catch (…: Throwable)` 实测 74 处（`grep -rn "catch (.*: Throwable)" src/main/kotlin --include=*.kt`，与 §一 一致）；未收口 **44 处**（§T-FP-03 原文估「约 60 处」）。另 `log/SourceInterceptorTemplates.kt:99` 的 `catch (Throwable t)` 位于**注入 agent 的 Java 源码模板字符串**里，不是 Kotlin 站点（守卫自证用例已钉住该边界）。§十 收口 `ui/SqlPreviewService.kt:2100` 后为**未收口 43 处 / 已落地 31 处**。

## 十、独立复核整改（T-FP-02b，2026-09-19 复核后回填）

独立复核（只读）对 §八/§九 提出 2 项阻断 + 1 条条件项，本节逐项回填「改了什么、为什么、验证读数」。

### 10.1 阻断 1：`writeConsoleOutput` 的两处透传被同一函数的外层 `runCatching` 抵消

**事实核实（改前实测）**：`execute/DataSourceSelector.kt` 的 doc-append 捕获块与 `ConsoleViewContentType` 字段回退捕获块的 `if (FatalErrors.isFatal(e)) throw e` 落在**同一函数的外层** `runCatching { … }.onFailure { LOG.info("[M36] writeConsoleOutput FAILED: …") }` 内（改前 `:725` 起、`:793-795` 收尾；改后 `:728` 起、`.onFailure` `:796-807`，两处捕获块 `:763`/`:779`）——fatal 被转成一行 INFO 后执行链带病继续，正是捕获块上方新增注释声称的反面。**2/30 属结构性无效透传**。

**改法**（与文件既有风格一致的最小改动）：外层 `.onFailure` 块首按原生执行链同款形态解包再判——

```kotlin
}.onFailure { e ->
    val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
    if (FatalErrors.isFatal(cause)) throw cause
    LOG.info("[M36] writeConsoleOutput FAILED: ${e.message}")
}
```

**逐条推演**：
- 非 fatal 异常（含 `ClassNotFoundException` 这类可选能力缺失）→ 解包结果非 fatal → 仍只记原 INFO 行，函数返回 `Unit`，**调用方行为逐字不变**（`execute/DataSourceSelector.kt` 的 `invokeLater(…, nonModal())` lambda（改后 `:855-858`）继续执行 `onError?.invoke(err)`）；
- 函数内 `runCatching` 体内的三处非局部 `return`（`:727`/`:759`/`:786`）→ 走 `Result.success`，`onFailure` 不触发，返回值/调用方不变；
- fatal → 解包后原样抛出 → 穿出 `invokeLater` runnable → 平台 EDT 处理器记录现场（不再降级成一行日志）；该路径下 `onError?.invoke(err)` 不执行，属 fatal 场景的可接受代价（错误本身已由平台级通道呈现）；
- 反射链（`Method.invoke` 把目标 fatal 包进 `InvocationTargetException`）必须解包，只判外层会漏判——已由新用例钉住。

**为可测改可见性**：`writeConsoleOutput` 由 `private` 改 `internal`（同文件 `execFileContentMatches` / `isExecDocOpenInEditor` 先例），否则行为用例无处注入。无 API 面变化（`object` 内部函数）。

### 10.2 阻断 2：§九 残留枚举与写集归属不实

**订正内容**：初版 §九「已核实的具体残留（**均在写集外**，未改）」的写集归属错误——同族 `runCatching` 抵消点实际分布如下（行号为 2026-09-19 复核时 HEAD `c21e331` 的基线行号；括注为 T-FP-02b 改动后的最终行号）：

| # | 抵消点（最终行号） | 被抵消的透传 | 写集 | 处置 |
|---|---|---|---|---|
| 1 | `execute/DataSourceSelector.kt:796`（`writeConsoleOutput` 外层 `runCatching`，改前 `.onFailure` `:793`） | 改后 `:763`、`:779`（改前 `:760`/`:776`） | **写集内**（初版未登记） | 已改（§10.1） |
| 2 | `execute/DataSourceSelector.kt:3813`（cache 命中路径刷新内容，改前 `:3801`） | `projectExecFile` 写盘链 fatal | **写集内**（初版未登记） | 已改（§10.4） |
| 3 | `execute/DataSourceSelector.kt:3874`（`prewarmExecTempFileInner` 写盘，改前 `:3859`） | `projectExecFile` 写盘链 fatal | **写集内**（初版未登记） | 已改（§10.4） |
| 4 | `log/agent/AgentManager.kt:877`（`resolvePid` 外层 `runCatching` 的 `getOrElse`，改前 `.getOrNull`） | `:871`（诊断回退捕获块） | **写集内**（初版以 `:836` 基线行号登记为「写集外」） | 已改（§10.4） |
| 5 | `ui/SqlPreviewService.kt:2121`（预热链唯一调用方 `runCatching { prewarmExecTempFile(...) }`，改前 `:2113`） | 预热链全部 fatal 透传（改后 6 处） | 写集外（初版已登记） | 已改（§10.4，否则 #2/#3 仍无效） |
| 6 | `execute/DataSourceSelector.kt` `writeConsoleOutput` 内层 `runCatching`（改后 `:739`/`:749`/`:774`：`getProject` 探测 / 滚动回调 / `Class.forName` 探测） | 同上（内层自吞，不达外层） | 写集内（初版未登记） | **登记不改**：属「可选能力探测 / 纯 UI 滚动」降级族，与守卫豁免口径一致（`Class.forName` 探针缺类抛 `ClassNotFoundException` 是普通异常，属设计意图降级） |
| 7 | `execute/DataSourceSelector.kt:2408`（`executeOnConsole` 外层 `runCatching`，改前 `:2396`） | 字面上包住改后 `:2603`/`:2624`（改前 `:2591`/`:2612`），但这两个抛点位于 `executeOnPooledThread`（改后 `:2468`）/ `invokeLater`（改后 `:2576`）lambda 内，**运行时不同线程、不同时刻**，不经该 `runCatching` | 写集内（初版未登记） | **登记不改**：非真抵消点（词法嵌套 ≠ 运行时捕获），透传仍达平台处理器（§九 未纳入行为用例站点条同结论） |
| 8 | `ui/SqlPreviewService.kt:2120-2132` 同族外层 `executeOnPooledThread` 内的 `runCatching` | 同 #5 | 写集外 | 已改（同 #5） |

**通用表述与已改/未改分清**：①**通用面**——本任务只保证 `catch (…: Throwable)` 块内的判定，`runCatching {}`（含 `onFailure`/`getOrElse`/`getOrNull`）与 `invokeLater` lambda 是**独立于捕获块的抵消面**，需逐处判定；②**已改**（写集内 4 处 + 唯一调用方 1 处，见上表 #1-#5）；③**登记未改**（#6 内层探测族、#7 词法嵌套非真抵消）；④**写集外仍存**（下一轮收敛面，见 10.5）。

### 10.3 条件项：`ui/SqlPreviewService.kt:2100` 收口（原豁免理由不实）

**判定依据（收口而非改写理由）**：该站点是 `previewAsync` 的 `ReadAction.compute { compute() }` 捕获块，位于**预览主链**（用户点击/快捷键 → PSI 转换 → 弹窗），不是可选能力探测；`catch (e: Throwable) { null }` 使任何失败（含 fatal）都返回 null → `showResult(null)` 弹「未找到可转换的 Wrapper」（`SqlPreviewService.kt:2044-2057`）——**归因错误且零日志**（真机排障无入口）。原豁免理由「可选能力探测失败返回 null（预览主链降级）」把主链写成探测，属借豁免放走真该收口的站点。

**改法**：捕获块首判 fatal 透传；非 fatal 仍返回 null（既有降级语义逐字不变），并补一条 WARN 留排查锚点。守卫登记随之从豁免表移入 `landedFatalPassthroughFiles`（豁免口径 KDoc 同步补「主链降级不算可选能力探测」边界，防回潮）。

### 10.4 `:3801`/`:3859`（改后 `:3813`/`:3874`）判定：改（非登记）

**判定：该改**——两处是 `prewarmExecTempFile` 链上的**写盘**路径（`projectExecFile` 写 VFS/落盘），失败语义是「预热跳过」（非契约性降级），fatal 上浮不破坏更外层契约（`prewarmExecTempFile` 是 fire-and-forget 投机性预热，调用方只有 `SqlPreviewService.previewAsync` 一处，且其 `runCatching` 一并放行 fatal → 池线程平台处理器记录现场）。故三处同批改：`:3813`（`.onFailure` 判 fatal，改前 `:3801`）、`:3874`（`.getOrElse` 判 fatal，改前 `:3859`）、`SqlPreviewService.kt:2121`（`.onFailure` 判 fatal，改前 `:2113`，使 #2/#3 与预热链既有 4 处透传真正到达平台）。同理改 `AgentManager.resolvePid`（`:847` 的 `.getOrNull()` → `:877` 的 `.getOrElse { … }`，非 fatal 仍返回 null；fatal 经 `doAttachAsync → onProcessAppeared → RunConfigurationProcessListener:135` 再透传）。非 fatal 路径逐处推演：`:3813` 仍 warn「refresh-content failed」并继续；`:3874` 仍 warn「writeFile failed」并 `return`（跳过预热）；`resolvePid` 仍返回 null；`SqlPreviewService:2121` 仍 warn「dispatch failed」。四处改动均**未改变**非 fatal 分支的控制流与日志文案。

### 10.5 未改动的登记项（下一轮收敛面）

1. `execute/DataSourceSelector.kt` `writeConsoleOutput` 内层 `runCatching`（改后 `:739`/`:749`/`:774`，探测族，见上表 #6）。
2. `executeOnConsole` 内 `runCatching` 族（改后 `:2383`/`:2399`/`:2408`/`:2440`/`:2479`/`:2483`/`:2488`/`:2490`/`:2496`/`:2512`/`:2549` 等）：均为反射探测/模型构建降级，fatal 落成 `null` 分支后由下游 `if (!nativeRun)` 回退——「fatal 穿过 `runCatching`」整体收敛留待下一轮（§九「残留边界」条同族）。
3. 写集外文件的 `runCatching` 抵消面（`ui/`、`log/` 其它文件）：本任务未逐一枚举，需专项扫描（`runCatching` + 内层透传）后登记。

### 10.6 验证读数（T-FP-02b）

**先红后绿（`writeConsoleOutput` 外层边界）**——先落测试 + 可见性改动（`internal`，无行为变化），跑 `FatalPassthroughTest`：

```
FatalPassthroughTest > test控制台输出_文档读取的链接错误须透传 FAILED
    junit.framework.AssertionFailedError: … expected same:<java.lang.NoClassDefFoundError: cn/song/…/EventBus> was not:<null>
FatalPassthroughTest > test控制台输出_反射包装的fatal须解包后透传 FAILED
    junit.framework.AssertionFailedError: … expected same:<java.lang.StackOverflowError> was not:<null>
6 tests completed, 2 failed          ← 日志侧实证吞掉：`[M36] writeConsoleOutput FAILED: cn/song/…/EventBus?`、`… FAILED: null?`
```

改后（同一命令）：`6 tests completed, 0 failed`。新增 3 例：反射包装（`InvocationTargetException` → 解包判 fatal）fatal 原样透传、非反射路径 `NoClassDefFoundError` 原样透传、`IOException` 仍只记 INFO 不抛（防「顺手全改」回归）。行为用例只覆盖可注入缝，`catch (…: Throwable)` 块内的判定仍由守卫的结构断言逐站点兜住。

**定向回归（改后，验收命令）**：

```
./gradlew test --tests "…log.agent.FatalPassthroughTest" --tests "…util.FatalErrorsTest" \
  --tests "…convention.BannedApiSourceScanTest" --tests "cn.song.mybatisplus.visualizer.execute.*" \
  -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1024m"
→ BUILD SUCCESSFUL；TOTAL tests=308 skipped=0 failures=0 errors=0
   （FatalPassthroughTest 6/0/0/0、FatalErrorsTest 5/0/0/0、BannedApiSourceScanTest 18/0/0/0、
     execute.* 27 类 279/0/0/0）
```

守卫（`BannedApiSourceScanTest`，含 fatal 规则与豁免/已落地表保鲜检查）**未因本轮改动变红**：豁免计数由 44 处/18 文件订正为 43 处/17 文件（`ui/SqlPreviewService.kt` 移入已落地表 31 处/4 文件），守卫 KDoc 行号清单同步订正。

### 10.7 提交与改动面

- 本轮提交：见 `git log --oneline -1`（T-FP-02b 单提交）。
- 改动文件：`execute/DataSourceSelector.kt`（`writeConsoleOutput` 可见性 + 外层 onFailure `:796-807`；`:3813`；`:3874`）、`log/agent/AgentManager.kt`（`resolvePid` 外层 `:877`）、`ui/SqlPreviewService.kt`（`:2100` 收口 + `:2121` 调用方）、`convention/BannedApiSourceScanTest.kt`（豁免表/已落地表/行号清单/豁免口径）、`log/agent/FatalPassthroughTest.kt`（+3 例）、本计划文档。
- 未推送（派活方统一管理）；未动 `docs/变更记录.md`、盘点文档、`build.gradle.kts`。

