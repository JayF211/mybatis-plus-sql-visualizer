# T-PremainReattach：增强重开对 premain 已注入进程 attach 握手失败修复

> **来源**：3.27 验收（§八）UI 端到端交真机复验后，用户真机（dev-bak 243 沙盒）复验反馈「应用跑着 → 设置勾开增强捕获」没生效。
> **日志证据**：dev-bak 243 沙盒 idea.log 2026-10-09 08:07~08:10 完整时间线（本文 §二逐条引用）。
> **生成日**：2026-10-09；写完停下等确认；**2026-10-09 用户令实施，D1+P4 口径实施完毕**（实施记录见 §九）。

## 一、背景与现象

用户复验序列（全部真机 243 沙盒，dev-bak `7122602` 构建）：

1. 项目打开 → 增强捕获确认（同意注入）；
2. Run 应用（pid=35432）→ **premain 启动即捕获**（端口 54355 前置协商、会话建立、ACK 正常）——这一段工作正常；
3. 设置**关闭**增强捕获 → 在线态 true→false，会话关闭；
4. 应用继续跑着，设置**重新勾开**增强捕获 → 期望存量进程被挂载、后续 SQL 恢复捕获；
5. **实际**：面板零捕获，「没生效」。

## 二、沙盒日志时间线（根因证据链）

| 时刻 | 日志 | 判读 |
|------|------|------|
| 08:07:11 | maybeAskConsentAtStartup: 用户同意注入 | 确认态 OK |
| 08:08:34 | premain 注入端口 54355 → 在线态 false→true → premain 会话建立 pid=35432 → attach 链已跳过 | Run 正常，premain 通道 claimed |
| 08:09:40 | 在线态 true→false + `AgentSession 会话读循环退出` | 用户关增强：会话关闭 |
| 08:10:04 | `设置变更：增强捕获重新开启——拒绝冷却已重置` + `settings-rescan: 发现已在跑的 JVM 进程（KillableColoredProcessHandler$Silent）` + `扫描完成——描述符 2 个，补发 1` + `在跑进程补挂 1 个` | **#6 settings-rescan 机制完全工作**（扫描→补发→排队全链 ✓） |
| 08:10:04 | `onProcessAppeared: 目标 pid=35432，3000ms 静默等待后挂载` | attach 链启动 |
| 08:10:07 | `loadAgent AgentLoadException(0)——按可能已挂载继续握手` | **失败点 ①**（见 §三） |
| 08:10:17 | `accept 超时：agent 未在 10s 内连接（pid=35432）——回落桥` | **失败点 ②**：agent 永远不会连新端口 |

补充旁证：08:10:07 用户已发出 HTTP 请求（`mpj/device/rightAlias`）——SQL 在进程内执行了，但 attach 未成功，条目未产出。

## 三、根因（代码级因果链）

**对「premain 已注入的进程」，关闭再开启增强捕获时，attach（loadAgent）路径结构性失效**：

1. Run 时：premain 端口 54355 监听（`openPremainChannel`），agent 侧 `SenderLoop` 回连建会话（通道 claimed）。
2. 关开关（`shutdownAll`，AgentManager.kt:1457）：会话关闭 + **`closePremainChannels()`（:1466）把 54355 ServerSocket 一并关闭**。agent 侧 `SenderLoop` 开始对 **54355** 按 1s→5s 退避**无限重连**——但端口已死，永远连不上。
3. 重开开关：上升沿 settings-rescan 扫到活 pid=35432 → `onProcessAppeared` → `attachAndOffer`（:894）：
   - `loadAgent(jar, agentArgs(新随机端口))` 抛 `AgentLoadException("0")`——agent 类已由 premain 装载，**agentmain 不重入，新端口参数没有送达 agent**；
   - 代码按「可能已挂载继续握手」开新端口 `accept` 等 10s → agent 的 SenderLoop 只认启动参数里的 54355（已死）→ **超时** → 回落桥。

**要点**：T-AgentReconnect T-03 已设计了正确语义（AgentManager.kt:1079-1080 注释：「SenderLoop 无限重连同一端口，只要监听 socket 还在，下一次回连就能重新建会话」）——但 `shutdownAll` 的关闭链把监听 socket 关了，重开时 settings-rescan 又选择了对 premain pid 无效的 loadAgent 路径。两半各自正确、拼起来断了。

## 四、方案

### D1（推荐）：关闭链保留 claimed premain 通道监听（转「暂停待重连」），上升沿 resume

- `shutdownAll` 分流：**claimed 通道**不关 ServerSocket，转「暂停待重连」态（打断 accept 线程、标 paused）；未认领/过期通道照旧关闭。
- 上升沿 settings-rescan 补挂前：pid 命中「暂停通道表」→ **resume**（重排 accept 循环，等 SenderLoop 1~5s 内重连重建会话），**跳过 loadAgent**；未命中才走现有 loadAgent 路径。
- 暂停期语义：关闭期间 agent 重连到达 → 按暂停态拒绝（保持监听让连接退避），重开后放行。
- 优点：不改 agent、不碰端口重bind、复用 T-AgentReconnect 既有重连语义。
- 代价：关闭增强后 claimed 端口继续驻留（直到 resume / 运行结束 / 项目 dispose）——需接受「关闭 ≠ 端口消失」的语义微调（见 §八拍板点）。

### D2（备选）：关闭链照旧全关，上升沿 resume 时按记录的原端口重新 bind accept

- 记录 claimed pid → 原端口映射；loadAgent 抛 AgentLoadException 后查表，新 ServerSocket **bind 原端口**等重连。
- 风险：bind 可能撞 TIME_WAIT/被占；多通道端口管理复杂。P3 验证后与 D1 二选一。

### D3（放弃）：agent 侧 agentmain 重入更新端口

- HotSpot 对已装载 agent 类的重复 loadAgent 抛 AgentLoadException 不重入（本次日志实证），新端口送不进去。不可行。

## 五、实施步骤（D1 口径）

- **S1** `PremainChannel` 增暂停态标记；`shutdownAll` 分流：claimed → 暂停（打断 accept 线程、保留 ServerSocket），未认领/过期 → 照旧 `closePremainChannel`。
- **S2** resume 入口：上升沿 settings-rescan 补挂前查暂停通道表命中 pid → resume（重建 accept 循环、清暂停标记、等重连建会话）；`onProcessAppeared` 同查（防双路径漏）。
- **S3** 暂停期重连到达处理：拒绝建会话 + 保持监听（`isEnhancedActive` 门在 accept 循环内生效），重开后第一个重连即恢复。
- **S4** `monitor` 5s 巡检适配：暂停通道豁免「未认领超时清扫」，保留兜底 TTL（项目 dispose / 运行结束信号仍关闭）。
- **S5** 守卫：单测（claimed/未认领分流、resume 跳过 loadAgent、暂停期重连拒绝语义、巡检豁免）；回归正常 Run 一次成功路径与 HTTP 客户端 resolvePid 失败降级。
- **S6** 编号落账 + 验收文档 §八 3.27 行状态推进。

## 六、验证方案

1. 单测（headless）：S5 列表。
2. 沙盒端到端（复现用户序列）：Run（premain）→ 关增强 → 再勾开 → 期望日志 `settings-rescan 补挂` 后**不再出现** AgentLoadException/accept 超时，而是 premain 通道 resume + agent 重连会话重建 + 面板产出带库角标条目。
3. 回归：正常 Run 一次成功、存量连接捕获、HTTP 客户端进程 resolvePid 失败降级、关闭增强后端口驻留的可观测性（状态行/日志）。

## 七、待验证前提（实施者先证伪再动手）

- **P1** `AgentLoadException("0")` 时 agentmain 未被调用（新端口未送达）——依据：HotSpot 对已装载 agent 类重复 loadAgent 的拒绝语义 + 本次日志 accept 超时旁证；实施前可对 pid 手工 loadAgent 复现确证。
- **P2** agent `SenderLoop` 对**启动参数端口**无限退避重连、重连目标不可被 CONFIG 更新（SenderLoop.java:87 / :29-91 注释佐证；实施前读 `sql-visualizer-agent` 子项目本体确认）。
- **P3** 关闭链确实关闭了 54355 监听（代码 `shutdownAll:1466 → closePremainChannels` 语义推断；实施时可在关后抓端口监听状态确证）。
- **P4** attach 装载（无 premain）的进程关再开**同样不恢复**（其回连端口是 attachAndOffer 一次性 ServerSocket，同病）——本轮范围只修 premain 路径，attach 场景登记后续（若 D1 实现天然覆盖则顺带，实施时判定）。

## 八、拍板点

- **关闭增强后 claimed 端口继续驻留**（D1 语义）：接受（推荐——端口上只有本机回环监听，agent 重连在关闭期被拒），还是改 D2（关闭即全关、resume 时重bind 原端口）？

## 九、实施记录

> 2026-10-09 用户令实施（dev-bak 工作树）；占号 O-129/R75/D125~D127/V148~V150。

### 待验证前提核对结果

- **P1 成立**（沙盒日志即直接证据，未再手工 loadAgent 复现）：08:10:07 AgentLoadException(0) 后 agent 侧无任何新端口建连，08:10:17 accept 超时——新端口未送达。
- **P2 成立**（SenderLoop.java 实证）：`serverPort` 为 final 构造参数，dialer 恒拨 `127.0.0.1:serverPort`；入站 CONFIG 仅 disabled/desensitize/skipPrefixes/bizJarPrefixes/stackFrames 五键，无改端口键；断线 `while(true)` 无限退避重连。
- **P3 成立**（代码实证）：`shutdownAll → closePremainChannels → closeChannel` 关监听 ServerSocket。
- **P4 属实未修**：attach 装载进程的回连端口是 `attachAndOffer` 一次性 ServerSocket，关再开同样失联（同病）；本轮范围只修 premain 路径，attach 场景登记后续（若做需 pid→端口登记表，D2 形态）。

### 实施中的设计订正（相比计划文案，均已在码注释钉住）

- **D125（S3 形态改型）暂停期重连「不 accept」而非「accept 后拒绝」**：agent 侧 SenderLoop 有 `MAX_FAST_DROPS=10`「连上即被关」放弃端口语义（SenderLoop.java:112，T-PerfSweep6 性能报告发现 9）——accept-then-close 在 1s→5s 退避下约 40s 即 PORT_ABANDONED 永久放弃端口，resume 从此失效。改为：暂停即让 accept 线程退出（哑元自连唤醒，避免 claimed 通道 30s 拍内重连被 accept 后丢弃），监听保留，agent 重连在内核 backlog 停泊（不被 accept 即不计 fast-drop），resume 后第一个 accept 取回停泊连接（HELLO 已在缓冲区，会话秒级重建）。与计划「保持监听让连接退避」意图一致，形态更安全。
- **D126（S1 收窄）pid 未知的 claimed 通道照旧关闭不暂停**：resume 查表按 pid 命中，pid=0 永不命中，保留监听只会驻留到兜底 TTL（12h）。相比计划「claimed 一律暂停」收窄暂停面。
- **D127（伴生竞态两处，实施中新修）**：
  ① `channel.pid` 登记时机前移——原在会话构建完成后赋值，而构建链里还有 CONFIG 下发/巡检懒启/广播等毫秒级步骤；关开关落在窗口内会被判 pid=0 误走关闭分支。改为 HELLO 读到即登记。
  ② 会话提交段与 `shutdownAll` 清表段互斥（新增 `sessionCommitLock`）+ 锁内提交门禁（`channel.paused||closed` 即丢弃连接）——否则清表之后新会话才 add 进表 = 复活态（用户已关增强却仍在线采，关闭语义静默失效）。定向首轮 5/12 红即此竞态实测命中。
- **S4 落地形态**：`shutdownAll` 有暂停通道驻留时巡检保持运行（`ensureMonitorStarted`），5s 扫暂停通道 pid 消亡/兜底 TTL 到期即关闭；暂停通道清空后恢复 T-PS6-08 巡检复位语义（守卫钉住双向）。

### 守卫与验证读数

- **S5 守卫**：新增 `AgentManagerPremainReattachTest` **12/0/0/0**（claimed/未认领/pid 未知三分流、暂停期停泊不建会话不关闭、resume 直连与 onProcessAppeared 收口双入口、未命中回落 attach 排队、巡检 pid 消亡/TTL 回收/健康豁免、运行结束仍关闭、巡检驻留与复位）；回归 `AgentManagerPremainTest` **15/0/0/0**。
- **反向验证两刀**（守卫纪律：摘掉修复必红）：
  - 摘 `shutdownAll` 暂停分流（退回全关）→ **9/12 红**；绿的 3 个恰为不依赖分流的用例（未认领关闭 / pid 未知关闭 / 未命中排队），判别面精确。
  - 摘 `doAttachAsync` resume 入口 → **1/12 红**，恰为 `onProcessAppeared` 收口用例（直连入口用例不受钩子摘除影响，符合分层——它钉的是 resume 机制本体）。
- **全量门禁**：testShards（243 平台，5 片，单路）读数见验收文档 §8.4 追记。

### P4 追加实施（20261009 用户令「P4 也修」，D128/V151）

- **方案落定（D128）**：计划 D2 形态——attach 握手成功即登记 `pid→端口`（`attachPorts` 表，[attachAndOffer] accept 成功处登记）；重开上升沿 `doAttachAsync` 命中即**原端口 re-bind**（`SO_REUSEADDR` 容忍旧连接 TIME_WAIT）起 accept 循环接住 agent 的 SenderLoop 对原端口的无限回连，跳过 loadAgent。re-bind 失败（端口被 ephemeral 复用等）清除失效登记回落既有 loadAgent 路径（零劣化）。
- **统一性红利**：恢复成功后按 claimed 通道入 `premainChannels` 表——该进程后续关开自动走 premain 同一套暂停/恢复语义，P4 无需自己的暂停机制（守卫用例钉住第二循环走暂停路径）。
- **表规模有界**：恢复成功消费条目 + `sweepOffline` 顺带清死 pid 条目。
- **实施中全量门禁抖出两道竞态（随 D128 一并修复，生产隐患同源）**：
  ① **backlog=1 哑元滞留**——暂停的哑元自连唤醒存在两支交错：accept 线程若在循环条件处退出（未消费哑元），哑元连接滞留 backlog 占满唯一槽位（`ServerSocket(0, 1, …)`），此后 agent 重连全部被 Windows 拒绝（单跑恒走「消费哑元」支故未暴露，门禁时序命中另一支）。修复=暂停退出由 accept 线程在 finally 里 `drainPremainBacklog`（短超时循环 accept+close 清空 pending；同时消灭「resume 后第一个 accept 吃到哑元产生 pid=0 闪断会话」的生产隐患）+ backlog 1→4。
  ② **drain 与 resume 并发互抢**——drain 的 100ms 窗口内若 resume 起新 accept 线程，旧 drain 可能把 agent 真回连 accept 后弃掉（写端报「连接已被中止」）。修复=`resumePremainChannel` 起新循环前 `join(1s)` 等旧线程（含其 drain）退出。修复后守卫 `--rerun-tasks` 连跑 3 遍全绿。
- **守卫（V151）**：`AgentManagerPremainReattachTest` +4=**16/0/0/0**（re-bind 接住回连重建会话、第二循环走暂停路径、端口被占回落、巡检清死 pid、onProcessAppeared 收口）；反向验证第三刀：摘 doAttachAsync P4 钩子恰收口用例 **1 红**（直连层用例钉机制本体，分层与 S2 反向同款）。回归：`AgentManagerPremainTest` 15/0、`AgentAttachChainTest` 7/0。
- 全量门禁读数见验收文档 §8.4 追记。

### 遗留

- 沙盒端到端（复现用户序列：Run premain → 关增强 → 再勾开 → 日志应见「恢复：重建 accept 循环」而非 AgentLoadException/accept 超时，面板产出带库角标条目）——随 3.27 UI 端到端一并交用户真机复验（新构建需重装）。
- ~~P4 attach 场景（attach 装载进程关再开不恢复）登记后续候选~~（20261009 用户令「P4 也修」已实施，见上节 D128/V151）。
