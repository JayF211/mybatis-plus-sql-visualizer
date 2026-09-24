---
name: console-capture-zero-output-flush
description: 控制台捕获「已扫描 N 行 / 产出 0 条」的根因与修法（O-70 T-ConsolePendingFlush 定案）——按线程分桶后待闭合条目永无产出点、静默冲刷阈值与两处冲刷点、262 平台无 removeMessageFilter 的旧 filter 代际失活
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7df0cdd5-3a65-4027-a272-9744d81d0e80
---

> **归档注记（2026-09-24 第十六次精简）**：T-ConsolePendingFlush（O-70）已交付 dev `dc7e69a`（docs `f0d5360`），真机复验判据在 project-pending-plans 真机复验节续踪；残留＝Hibernate 零参语句同族未修（须另立计划）。

2026-09-23 用户报障「日常 IDE 2026.2 关闭增强捕获后控制台日志没成功捕获」，面板读数 `捕捉中 | 增强捕获: 未启用 / 控制台：已挂载 5 个，已扫描 149 行，产出 0 条`。定案任务 **T-ConsolePendingFlush（O-70）**，计划 `docs/开发计划/20260923-1505-T-ConsolePendingFlush-控制台待闭合条目停滞导致零捕获-开发计划.md`。

## 根因（行到了、规则也匹配得上，但条目没有被交出去）

`JdbcTemplateLogParser` 的条目**只有一个产出点** `closeBucketPending(bucket)`，而它的触发条件全是「**同一个线程桶里再来一行**」。于是「语句行是该线程最后一行」的形态永远不产出：

- 参数行是 TRACE 级（`StatementCreatorUtils`），工程通常只开 `logging.level.<...>JdbcTemplate: debug` → 没有参数行；
- Tomcat 按请求新建线程（现场两条业务查询分别在 `nio-8086-exec-1` / `-exec-2`），低频访问下每条语句独占一个新线程、此后不再有行；
- **实时链路从不调用 `closePending()`**——全仓只有「日志 → SQL」粘贴面板（`LogToSqlPanel.kt:363-365`）与 MCP `render_logs` 在输入末尾调。

净效果：每条语句停在自己的桶里等一个不存在的后继行，`takeEntries()` 恒空。**测试为何没抓住**：`JdbcTemplateLogParserTest.feedAll()` 喂完行后**显式调 `closePending()`**，恰好补上了生产链路缺的那一步；EP/桥用例喂的是 MyBatis 成对行（同线程自闭合）。

## 修法（静默冲刷，两处冲刷点缺一不可）

1. `LogThreadRouter`：记录每桶最近活动时间（`IdentityHashMap`，淘汰/清空时同步清理）+ `settledBuckets(settleMs, nowMs)`。
2. `JdbcTemplateLogParser.flushSettled(nowMs)`：闭合静默 ≥ 阈值的桶；`DEFAULT_SETTLE_MS = 1000`，`clock` 可注入（与 router 共用同一时钟）。
3. **逐行冲刷**：`processConsoleLine` **喂行之后**调 `flushSettled()`——顺序是关键，本行刚 touch 过自己的桶，故该桶不在冲刷名单里（参数行不会被截断），被冲的只有别的线程早已静默的语句；冲出的条目与本次喂行产出经同一次 `takeEntries()` 一并交出。
4. **轮询冲刷**：桥 `scanAll`（EDT，常规 2s/快速 500ms）在 `isCapturing() && !SqlCaptureGate.paused.get()` 时调 `flushSettledEntries()`——应用执行完最后一条 SQL 就安静下来时，**只有定时器能兜住**；没有它低频工程仍表现为零产出。
5. **并发**：filter 回调在控制台输出线程、轮询冲刷在 EDT，两者碰同一套解析器状态 → 以 `ctx` 为锁 `synchronized(ctx)` 包住（无 Swing 调用，不构成 EDT 死锁面）。

阈值代价（已知边界）：若某线程在语句行与它的参数行之间卡住 >1s（连接池耗尽等），条目会先以未还原形态产出、随后参数行按「无上下文」丢弃并计入 `LogParseDiag.paramWithoutContext`。取 1s 即为此（两步正常间隔 1ms 量级）。

## 顺带定案：262 平台面（javap 实证）

`com.intellij.execution.impl.ConsoleViewImpl` **只有 `clearMessageFilters()`，没有 `removeMessageFilter`** → 停止后再开捕捉会在同一 console 上累积摘不掉的旧 filter 代理；旧代理若继续按 console 键取到**新** ctx 就会与新代理逐行重复解析、重复计数。处置＝**挂载代际号**（`filterGeneration` 写入 ctx，`handleConsoleLine` 代际不符即惰性退出，不解析不计数）；`detachAllFilters` 里原 `WARN … FAILED to remove filter (filter will remain active!)` 改写为平台事实陈述——**它是 2026.2 的常态，不是故障，排查时曾是噪音**。`AsyncFilterRunner$HighlighterJob.analyzeLine` 传的是 `getLineStartOffset + lineText.length`，重放守卫「第二个参数=行尾偏移」的前提成立。

**Why:** 本故障的形态极具误导性——面板提示语把矛头指向「应用没开 SQL 日志」，IDE 日志显示 filter 已挂载、`capturing=true`、闸门全开，看代码也「规则明明匹配」。真正的缺口在**产出时机**，而它只在「语句行是该线程最后一行」时暴露。

**How to apply:**
- 见到「已扫描 N 行 / 产出 0 条」**先分「行是否到桥」再到「行是否产出」**：`consoleLinesSeen>0` 说明 filter 在链上（挂载面、闸门都没问题）→ 往解析器的产出条件查；`idea.log` 里 `flushSettledEntries: 静默冲刷待闭合条目` 的 INFO 是修后的锚点（有冲刷=行已到桥，问题在解析规则；无冲刷=行没到桥，回挂载面查）。
- **按线程分桶的解析器，「行已进桶」≠「条目会产出」**：任何「等下一行闭合」的状态机都要问一句「如果这是该线程最后一行怎么办」。本仓同类残留＝Hibernate 零参语句（阴影槽等双副本合并，未修，理由见计划 §八）。
- 本任务的红绿读数与两处反向验证在计划 §七（红 3 例→改走 `scanAll` 接线后红 4 例→绿 17 类 237 例）。EP 通道只有逐行冲刷、**无定时器**（EP Filter 由平台创建、无生命周期钩子）——EP 成为主通道时需补节拍。
- 相关：[[project-pending-plans]]（O-70 条目）、[[pitfall-regression-false-failure-flakes]]（本任务撞的陈旧类假失败）、[[reference-real-ide-debug-entry]]（日常 IDE 的日志与设置取值）。
