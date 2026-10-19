# K2J 尾款收口报告：fatalUnwrapUnjudgedBaseline=5 清偿（T-K2JTail5）

> 日期：2026-10-01。分支 feature/kotlin-to-java，起始 HEAD 7c6792d。
> 主仓 §十 由主控合并时补（本文件只落 k2j 树，同步功能不合并分支）。

## 守卫机制

`convention/BannedApiSourceScanTest.catch_Throwable块必须判fatal或登记基线` 的第三段「反射解包锚点」：扫 `execute/DataSourceSelector.java` 里**块内含 `InvocationTargetException` 解包**的 `catch (Throwable …)` 站点，块内未出现字面 `FatalErrors.isFatal(cause)` 即记 unjudged，登记基线容忍（收口一处改小一号）；同时第一段要求该文件 `FatalErrors.isFatal(` 总次数与落地集表精确相等（防删防漏登记）。

## 5 处逐处处置表（守卫报告口径行号 → 收口后行号）

| # | 文件:行（收口前 → 后） | 所在链 | 处置 | 依据 |
|---|---|---|---|---|
| 1 | 1638 → 1638 | prepareInConsole 异步等编辑器 lambda（EDT） | unwrap 后补 `if (FatalErrors.isFatal(cause)) throw (Error) cause;`，非 fatal 路径原样走 onError | T-FP-02 定例（本文件 2783 行原生链先例）；EDT 透传交平台处理器记录现场 |
| 2 | 1658 → 1662 | prepareInConsole 同步路径外层 catch | 同上（置于 disposed 特判之前） | 同上；fatal 透传给调用方捕获边界 |
| 3 | 1932 → 1939 | prepareAfterEditorResolved 外层 catch | 同上 | 同上 |
| 4 | 2844 → 2857 | executeOnConsole 外层 catch（池化线程） | 同上（置于 fCause 之前） | 同上；池化线程透传即线程终止、平台记录现场 |
| 5 | 3325 → 3341 | runRunner catch（驱动初始化反射链） | 同上（置于 LOG.warn 与 onError 之前，FatalErrors KDoc「捕获块首行」形态） | 同上；非 fatal 仍走 onError 面板降级 |

**同批第 6 处（守卫不可见）**：`catch (Exception e)` @2820 → 2830（executeQueries 兜底提交块，`execute.invoke` 反射链同源吞 Error；守卫正则只匹配 `catch (Throwable`，锚点看不见此形态）——一并按定例补判定，登记进落地集表表注。

## 基线与表变更

- `fatalUnwrapUnjudgedBaseline`：5 → **0**（KDoc 记基线变迁，锚点保留防回潮——「解包 ITE 不判 cause」形态的回归保护不放弃）。
- `fatalPassthroughGuardedFiles["execute/DataSourceSelector.java"]`：16 → **22**（+5 锚点判定 +1 catch(Exception) 判定，表注写明站点）。
- `fatalCatchSiteTotalBaseline`：660 不变（未新增捕获点，只在既有块内补判定）。

## 读数（红/绿两次）

- **红**（生产已修、落地集表已改 22、基线暂保持 5）：`catch_Throwable块必须判fatal或登记基线` FAILED——`反射捕获块解包了 InvocationTargetException 却未对解包后的 cause 判 fatal，命中 0 处：[]（期望 5 处）… expected:<5> but was:<0>`；该类 29 tests / 1 failure（① 22==22、② 660 均过，唯一红即基线失配，证 5 处全数判定、无漏改）。
- **绿**（基线改 0 复跑）：BannedApiSourceScanTest **29/0/0** + FatalPassthroughTest **8/0/0**，BUILD SUCCESSFUL。

## 行为判别测试（FatalPassthroughTest 新增两用例）

- `test执行链Runner反射调用_致命错误解包后透传`：假 runner `run()` 抛真 SOE（递归实得，非构造），`Method.invoke` 包成 ITE → 断言解包后原样透传（assertSame）且 onError 不触发。5 处锚点中唯一可同步注入站点；其余四处位于 EDT lambda/池化线程、断言侧接不住，由守卫结构断言兜底（本类既有口径）。
- `test执行链Runner反射调用_普通异常仍走面板回调降级`：RuntimeException("driver missing") → 不抛、onError 恰一次且文案含原始原因（bundle `{0}` 占位）、silent 路径清 last-used——防「顺手全改」回归。

## 提交清单

| SHA | 内容 |
|---|---|
| 5f1487f | fix: DataSourceSelector 反射解包 6 处补 isFatal(cause)（+19 行） |
| dab3fa8 | test: 守卫基线 5→0 + 落地集表 16→22 + runRunner 判别两用例（+83/−9） |

（另有并行会话 T-SyncK2JMaster70 立项纯 docs 提交 e5c5929 落于两笔之间，0 个 src 文件，与本收口无交集。）

## 遗留登记

1. **收口不回流 master**：本分支按用户明令不合并；master 后续同步吸收 5f1487f/dab3fa8 即对齐（master 侧 Kotlin 树无此 5 处形态，属 k2j 移植轨自身尾款）。
2. **catch(Exception)+ITE 形态对守卫结构性不可见**：全仓 grep 实测（`instanceof InvocationTargetException`）除 DataSourceSelector（本轮收口）外仅 McpDbExecutor 一处——其主 catch 已判 fatal（@194 sneakyThrow 透传）、errorTextOf 仅作判定后文本提取，现行无实例；形态性风险在此登记，后续若新增此类站点建议守卫扩口径或在落地集表登记。
3. **同步计划预登记冲突提示**：T-SyncK2JMaster70 计划含「fatal 基线 660→661 预登记」，其执行落在 5f1487f 之后时，落地集表数字与基线变迁 KDoc 需按本轮实际（22 处表 + 0 锚点基线）叠加，勿按旧 16 处口径回写。
