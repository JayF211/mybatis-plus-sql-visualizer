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
| T-FP-01 | （实施后填） | |
| T-FP-02 | （实施后填） | 实际收口点数与形态 A/B 选择须回填 |
| T-FP-03 | （实施后填） | 守卫豁免清单条数与先红后绿实录须回填 |
| T-FP-04 | （实施后填） | 未收口点登记位置须回填 |
