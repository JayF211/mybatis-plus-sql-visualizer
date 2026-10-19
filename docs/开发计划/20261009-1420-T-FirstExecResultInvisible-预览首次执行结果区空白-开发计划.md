# T-FirstExecResultInvisible：预览首次点「执行 SQL」结果区空白（二击正常）

> **来源**：用户 2026-10-09 真机报障——打开预览 → 首次点执行 → 下方结果区空白 → 再点一次正常。
> **生成日**：2026-10-09；调查=子代理 H（执行链全读，file:line）；计划落盘即排实施（用户令「生成计划并排队」）。
> **占号**：O-135/R81/D138~D139/V161~V162（引用前实测：O 最高 O-134 / R 最高 R80 / D 最高 D137 /
> V 最高 V160）。
> **同族先行**：`docs/开发计划/20260910-0920-T-ExecSilentNoop-*`（§五 F3/F4 仍开放）。

## 一、根因候选（按可能性排序；F0 诊断先行，见 §四）

执行链关键时序：打开预览 `prepareDefaultConsole`（SqlPreviewPanel.kt:1876-1918）→ 异步准备链
`DataSourceSelector.chooseAndPrepare(executeAfterPrepare=false)`（:366-474，远程 PG 会话需数秒）→
`prepareAfterEditorResolved`（:1685-2084：`executionGeneration++` :1800、整控台 SplitPane 预建
:1989-1991、onConsole 内嵌 :2039、**divider 折叠 1.0** :2044-2053）。
点执行 `executeSql`（:1411-1503）：已备→`executePrepared`；**未备→走全链 `selectAndExecute`
再建第二条临时文件/会话/控制台（:397 无任何单飞/去重）**。

- **H1（主嫌）双准备链竞态**：冷启首开 + 立即点执行时两条链并发——准备链 `executionGeneration++`
  （:1800）落进执行链代际捕获（:2817）与取消判定（:2823/:2968）之间 → 提交被取消（日志
  `[M36] execution submit cancelled`）；即使已提交，准备链 onConsole 换入从未执行的全新控制台并
  折叠（:2039/:2044-2053）、`showEmpty()` 清「执行中」——用户见「无 loading 无报错下方空白」。
  第二次点击两链已收敛 → `executePrepared` 正常。**与现象完全吻合**。
- **H2 结果页签/网格晚建错过换入窗口**：`startResultTabRetry`（:3257-3400，40 拍 16s）超时走
  `execute.timeout.hint`（非空白，弱匹配）；`refreshResultsComponent` 8 拍 4.8s 内网格未建即停
  （「事后无人补换入」，T-ExplainFirstNative :3205-3207 记录过该形态）。
- **H3 T-ExecSilentNoop 残留族**：非 demoted 仍走 native 链的静默口子（该计划 §五.1/§五.4 未结），
  若撞上呈 8s 凭据提示（非纯空白，弱匹配）。

## 二、方案

### F1（结构性主修，无条件做）：准备链单飞 + 执行意图排队（落 DataSourceSelector）

- `prepareInFlight: AtomicBoolean` + `pendingExecute: AtomicBoolean`；
- `chooseAndPrepare` 入口（:376 后）：`prepareInFlight` 已置位且本次 `executeAfterPrepare=true` →
  只置 `pendingExecute`、`callback(true)`、返回（**不再建第二条临时文件/会话**）；
- `prepareAfterEditorResolved` 完成准备（:1797-1804 后）消费 `pendingExecute` → 按 executePrepared
  口径调 `executeOnConsole`（onResultsReady 齐），替代现 :2056-2065 的单点判定；
- `finally` 复位 `prepareInFlight`。复用路径（reuseSharedConsole :480-617，同步）不受影响。
- 无论 H1 是否终审定案都是纯增益（消除重复会话/连接）。

### F3（布局纪律，无条件做）：折叠/展开归口 embedMode

`embedConsole` 的 previewMode 布尔兼了「预览」与「全链执行」两语义（全链 onConsole :1466/:1495
传 previewMode=true 导致执行换入也折叠）——改显式 `embedMode`（PREVIEW/EXECUTE），折叠仅在
PREVIEW 且结果区无内容时执行（:2044-2053、:596-605、:1993-1995 三处收口），EXECUTE 恒展开。

### F2（H2 成立时才做）：换入窗口加固

`refreshResultsComponent` 非 dumb 窗口 8 拍→20 拍（12s）；`startResultTabRetry` 命中后保留
select 重试一代际。视 F0 结论取舍。

### D138/D139 归档

D138=F1+F3 无条件双笔；D139=F2 条件笔（F0 判定 H2 成立才实施，否则销案留档）。

## 三、F0 诊断判定表（实施者先跑，用既有日志定案）

最小复现：runIde 沙盒重启（清 sharedConsole）→ 打开预览（远程 PG）→ **1~2 秒内**点执行 →
等 20s，读 idea.log：

| 日志组合 | 归因 | 修哪个 |
|---|---|---|
| `[M36] execution submit cancelled`（:2824/:2969）或准备链 session created（:411）晚于执行链 begin（:2821） | H1 | F1（+F3 保险） |
| `native query SUBMITTED`（:2992）+ `result tab not appeared after 40 attempts`（:3351） | H3 | 续作 T-ExecSilentNoop F3/F4 |
| SUBMITTED + retry 命中 + `results refreshed`（:1191）有日志但视图空白 | H2/布局 | F2（+F3） |

现有埋点已够 F0；如需补只在 `prepareAfterEditorResolved` 入口/出口各加一行 INFO（不进热路径）。

## 四、实施步骤

- S1 F1 单飞+排队（DataSourceSelector）。
- S2 F3 embedMode 三处收口。
- S3 单测（纯逻辑）：pendingExecute 排队消费 / 单飞拒绝第二链 / embedMode 折叠判定。
- S4 反向验证：临时注释 pendingExecute 排队分支 → 复现场景重现 `execution submit cancelled`
  （红），还原消失（绿），贴读数。
- S5 F2 视 F0 结论取舍（成立才做，否则销案留档）。
- S6 计划 §八回填 + CHANGELOG 一行 + 与 T-ExecSilentNoop 计划互链状态更新。

## 五、验证方案

定向单测；runIde 沙盒五场景（对齐 T-ExecSilentNoop §3.5）：①冷启首开 1s 内点执行（预期一次
可见或给真实报错）②同 SQL 连续两次 ③执行后重开预览再执行 ④多数据源弹窗路径首执行 ⑤执行中
快速切换预览条目。沙盒 UI 序列由用户真机走查承担（沙盒自动点击不可靠为已知形态）。

## 六、待验证前提（实施者先证伪）

- **P1** H1 的落地顺序假设未被受控实验证实——F0 判定表先行，F2/F3 视结论取舍，F1 无条件。
- **P2** 「首次打开必然 cold」依赖 IDE 重启后首开；若用户实为重开预览（复用路径 :611），时序
  形态不同——F0 日志先确认分支。
- **P3** 结果区「空白」的确切形态（空占位/折叠到 0/停留编辑器）未截图核实——复现时截屏比对，
  三形态分别指向 H1 三个子环节。
- **P4** 实施分支须基于含 attach/XML 补全修复的 dev-bak 最新基线重跑 F0。

## 七、实施记录

**实施**：分支 `t-firstexec`（基线 dev-bak `c3a86bd`，P4 满足）；代码笔 `1501ebf`
（F1+F3+单测+接线守卫，2026-10-09）。

### 落点与形态（对照 §二）

- **F1**：新增纯逻辑件 `execute/PrepareSingleFlightGate.kt`（`PrepareSingleFlightGate` +
  `QueuedExecuteIntent` + `PrepareGateDecision`，JDK 原子类、无 IDE 类型，可单测）。
  `chooseAndPrepare` 入口单飞判定（ExecuteSqlSupport 可用性检查后、**共享复用之前**——冷启时
  sharedConsole 尚未落地，复用必不命中，单飞是唯一闸门）；`prepareAfterEditorResolved` 完成
  准备（缓存落地后）消费排队意图，按 executePrepared 口径调 `executeOnConsole`
  （onResultsReady=意图 onConsole：提交后完整 UI 换入 + 面板 EXECUTE 内嵌展开）。单飞位复位
  覆盖全部链终结路径：复用收敛 / tempFile fail / runners empty / 弹窗取消 / 编辑器不可得
  （sync+async）/ 两处历史静默 `return`（scriptModel==null、editorComponentRaw==null）/
  onFailure 兜底。
- **F3**：`embedConsole` 的 `previewMode: Boolean` 退役为 `ConsoleEmbedMode`（PREVIEW/EXECUTE）。
  折叠归口 `shouldCollapseDivider(resultAreaHasContent)`——EXECUTE 恒展开；PREVIEW 仅结果区
  无内容时折叠，内容探针 `resultContents(ui)`（排除 Output 输出页），DS 侧两处与面板侧共用
  同一 `currentResultAreaHasContent()` 判据防两侧漂移。三处收口：prepareAfterEditorResolved
  （原 :2044-2053）/ reuseSharedConsole（原 :596-605）/ embedConsole 本体含 resize 首触
  （原 :1993-1995 及 :2011）。全链执行 onConsole 五处（executeSql 两处、executeAdhocSql 两处、
  EXPLAIN 直送一处）+ executePrepared onResultsReady 两处显式 EXECUTE（旧形态传
  previewMode=true 导致执行换入也折叠）；预览准备链（prepareDefaultConsole）显式 PREVIEW。

### 偏差注记（对 §二的超集，均有因）

1. 排队意图不只 `pendingExecute: AtomicBoolean`——拦截时额外快照回调（`QueuedExecuteIntent`：
   onConsole/onEditor/onError/onErrorRich/wholeScriptExec）。原因：面板在 executeSql 已
   showLoading 并拿到 callback(true)，意图回调若丢弃，排队执行失败即静默卡死（状态条恒
   「执行中」）——按 ErrorPositionWiringGuardTest 同族「实现有、接线漏」教训前置防住。
2. 三处收口之外，EXPLAIN 直送链与 executeAdhocSql 两处同形态调用点一并归口 EXECUTE——同属
   「全链执行换入也折叠」的 bug 形态，「EXECUTE 恒展开」原则的必然覆盖。
3. 预览链在飞时**不拦截**后续预览准备（PASS_THROUGH 直通不取所有权、不消费排队意图）——
   拦截会让切换预览条目的 SQL 同步丢失（比竞态更糟）；H1 主场景（冷启首开 1~2s 内点执行）
   单飞完全覆盖。消费点与复位点都以门所有权为前提（ownsPrepareGate 参数贯穿）。
4. 60s 卡死逃生：单飞位悬挂超窗（runner 失败无 session 回调等历史挂死族，见 M36.5.5 实证）
   视作死链接管放行并清陈旧排队——防执行按钮从此永久排队；远程 PG 会话数秒级，窗口充裕。
5. F1 消费点位于 UI 拼装之前，故 :2056-2065 的单点 `if (executeAfterPrepare)` 判定改为
   `executeNow`（本链显式执行 ∨ 排队意图就位），折叠判定同源（executeNow 恒不折叠）。

### 测试（S3）

`PrepareSingleFlightGateTest`：单飞拒绝第二链并排队 / 排队一次性消费 / 连点后到覆盖 /
预览直通不取所有权 / finishPrepare 复位 / 卡死逃生 / embedMode 折叠判定三态（纯逻辑）+
接线守卫（源码扫描照 ErrorPositionWiringGuardTest 先例：8 个 embedConsole 调用点模式显式、
DS 两处折叠归口 shouldCollapseDivider、F1 入口与消费点在位）。

### 反向验证（S4）

临时注释 `PrepareSingleFlightGate.tryEnter` 的排队分支（`pendingIntent = intent` +
`pendingExecute.set(true)` 两行）→ `单飞_在飞时执行意图排队不再进第二链` 预期红（consume
返回 null）；还原 → 预期绿。接线守卫用例不受影响（守卫只扫源码形态）。

### F0 判定结论（S5）

本环境不承担 runIde UI 序列复现（沙盒自动点击不可靠为已知形态），F0 判定表落地为 §八用户
真机走查清单；判定表三行日志判据全部为现有埋点，无需新埋点（§三的「如需补只在
prepareAfterEditorResolved 入口/出口各加一行 INFO」不实施——消费点已有一行 INFO：
`[M36][T-FirstExec F1] queued execute intent consumed after prepare`）。
**F2 销案留档**：H2 行无现场日志证据，不动 refreshResultsComponent（8 拍）/startResultTabRetry
（40 拍）拍数；若走查日志命中 H2 行（SUBMITTED + retry 命中 + `results refreshed` 有日志但
视图空白）再启 F2（8 拍→20 拍 + retry 命中后保留 select 重试一代际）。

### §六待验证前提证伪/确认（P1~P4）

- **P1（H1 落地顺序未受控证实）**：成立且保留——F1 无条件实施不依赖 H1 终审（消除重复
  会话/连接纯增益）；H1 定案归 §八走查，日志判据：`[M36] execution submit cancelled` 或
  准备链 `session created` 晚于执行链 `executeOnConsole begin`。
- **P2（冷启 vs 重开复用分支）**：静态复核定案——复用路径（reuseSharedConsole）同步收敛
  不建链，无双链竞态形态；若用户实为重开预览（复用分支），空白另有归因（H2/H3），走查
  清单场景②③覆盖该分支的日志定案。
- **P3（空白三形态未截图核实）**：成立——走查清单要求复现时截屏比对（折叠到 0 / 空占位 /
  停留编辑器分别指向 H1 的提交被取消 / 结果组件被换掉 / showEmpty 清「执行中」三子环节）。
- **P4（基线）**：满足——本工作树即 dev-bak 最新基线 `c3a86bd`（含 attach/XML 补全修复），
  F0 无需基于旧基线重跑。

### 与 T-ExecSilentNoop 互链

其 §四.1 实施记录尾部已补关联注记：本计划 F1 消除「双准备链竞态 → 提交被取消」这一静默
出口触发面；其 §3.3 F3（数据源可见性）/§3.4 F4（执行范围）仍开放、不受本计划影响；
本计划 §八判定表 H3 行仍指向其 F3/F4 续作。

### 运行读数（定向测试 + 反向验证）

门禁窗口（t-batchmerge 合并态）结束后补跑，登记 `t-firstexec`（沙盒 `t-firstexec-sb`）后执行。

- **定向测试绿**：`./gradlew test --tests "cn.song.mybatisplus.visualizer.execute.PrepareSingleFlightGateTest"`
  → `BUILD SUCCESSFUL in 2m 14s`（首次含主源码+测试全量编译，编译零错误）；
  XML 读数 `tests=12 skipped=0 failures=0 errors=0`。12 用例 = 单飞空闲持门 / 在飞排队不进
  第二链 / 连点后到覆盖 / 预览直通不取所有权 / finishPrepare 复位 / 卡死逃生 / embedMode 三态
  / 接线守卫三例（全链 embed 调用点显式 EXECUTE、DS 折叠归口、F1 入口与消费点在位）。
- **反向验证红**：临时注释 `PrepareSingleFlightGate.tryEnter` 排队两行
  （`pendingIntent = intent` / `pendingExecute.set(true)`）→ `BUILD FAILED`，
  `12 tests completed, 2 failed`，红的正是预告两例：
  `单飞_在飞时执行意图排队不再进第二链`（`AssertionError: 排队标记应在位`）与
  `排队_同窗口连点后到意图覆盖先到`（`AssertionError: 最新用户意图优先 expected
  same:<QueuedExecuteIntent@53fe15ff> was not:<null>`）；其余 10 例（含接线守卫三例）保持绿
  ——守卫只认源码形态，不受门逻辑改动影响，红面精确落在排队语义上。
- **还原复绿**：恢复两行后复跑 → `BUILD SUCCESSFUL in 17s`，
  `tests=12 skipped=0 failures=0 errors=0`（工作树与 HEAD 逐字节一致，无探针残留）。

## 八、验收注记（F0 诊断判定表 → 用户真机走查清单）

runIde UI 序列自动点击在沙盒不可靠（已知形态），F0 由用户真机承担。**最小复现**：IDE 重启
（清 sharedConsole）→ 打开预览（远程 PG）→ **1~2 秒内**点「执行 SQL」→ 等 20s → 读
idea.log + 截屏。

判定表（§三表格原样，日志判据不动）：

| 日志组合 | 归因 | 处置 |
|---|---|---|
| `[M36] execution submit cancelled`（:2824/:2969）或准备链 session created（:411）晚于执行链 begin（:2821） | H1 | F1 已实施——若仍复现，登记新计划（附日志） |
| `native query SUBMITTED`（:2992）+ `result tab not appeared after 40 attempts`（:3351） | H3 | 续作 T-ExecSilentNoop §五 F3/F4（互链见 §七） |
| SUBMITTED + retry 命中 + `results refreshed`（:1191）有日志但视图空白 | H2 | 启 F2（本计划销案留档：refreshResultsComponent 8 拍→20 拍 + retry 保留一代际） |

走查清单（对齐 T-ExecSilentNoop §3.5 五场景）：

1. **冷启首开 1s 内点执行**（F1 主场景）——预期一次可见结果或真实报错；日志锚点
   `[M36][T-FirstExec F1] prepare chain in flight: execute intent queued`（排队生效）+
   `queued execute intent consumed after prepare`（消费生效）。
2. **同 SQL 连续两次**——两击均正常。
3. **执行后重开预览再执行**（复用分支，P2 定案依据）。
4. **多数据源弹窗路径首执行**（弹窗取消后再执行的门复位路径）。
5. **执行中快速切换预览条目**（PREVIEW 有内容不折叠的 F3 守卫）。

每场景记录：日志锚点行 + 结果区截屏（P3 三形态比对：折叠到 0 / 空占位 / 停留编辑器）。
