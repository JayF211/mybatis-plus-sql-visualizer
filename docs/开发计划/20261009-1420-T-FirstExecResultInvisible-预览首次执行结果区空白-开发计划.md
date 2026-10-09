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

（空——实施启动后回填）
