# T-AgentHeartbeat 增强捕获会话 30 秒必掉线 + 诊断行不换行 — 开发计划

> 来源：2026-09-21 用户沙盒实跑反馈「SQL 日志没有正常捕获到」，面板状态行显示
> 「增强捕获：应用侧心跳中断（进程可能已卡死），已回落控制台捕获」。
> 定位依据：沙盒日志 `.intellijPlatform/sandbox/.../IU-2024.3.7.1/log/idea.log`（2026-09-21 全天四轮运行）
> + agent 侧心跳节奏实测探针（本轮新做，读数见 §二.3）。
> 相邻事项：本次修复只动「心跳节奏 / 在线窗口 / 重连通道」三处，不改捕获语义、不改控制台桥链路；
> 面板换行只动渲染层，不动 [captureDiagnosticsText] 的取数契约。

## 一、现象与影响

- **现象**：沙盒里启动 demo（`EasyCodeDemoApplication`）后，增强捕获会话在建立后 **恰好 30 秒**被摘除，
  状态行落到「应用侧心跳中断（进程可能已卡死），已回落控制台捕获」；此后整轮运行再无任何条目。
- **两条通道同时归零**，但原因完全不同（这是本轮最花时间的地方）：
  1. **增强通道**：会话被误杀，且**无法重连**（§二.2、§二.4）——这是缺陷；
  2. **控制台通道**：demo 工程 `dev` 档根本不打印 MyBatis SQL，控制台确实没有可捕的料——**这不是缺陷**
     （既有结论见记忆 `reference-demo-console-sql-logging`；现场读数「已挂载 5 个，已扫描 88 行，产出 0 条」与此一致）。
- **影响面**：所有「应用启动后 30 秒内没有 SQL 事件」的运行都必掉线。应用启动本身通常就要 20~40 秒，
  所以**首轮查询几乎必然落在掉线之后**——对用户而言就是「一条都捕不到」。这不是边角场景，是主路径。
- **回归性质**：由 2026-09-18 的 `a628a3b`（T-RA-01 心跳窗口落地）引入。该提交之前在线态只看 socket 是否断开，
  会话永远不会因心跳过期被摘除，所以 agent 侧心跳节奏的偏差被**掩盖了整整一个版本周期**。
- **量化（沙盒日志四轮运行，全部命中）**：

  | 轮次 | 会话建立 | 在线态 true→false | 存活 |
  |------|----------|-------------------|------|
  | 1 | 13:25:01,561 | 13:25:31,570 | 30.0s |
  | 2 | 13:26:47,575 | 13:27:21,622 | 34.0s |
  | 3 | 13:41:49,544 | 13:42:23,103 | 33.6s |
  | 4 | 15:56:19,066 | 15:56:49,071 | 30.0s |

  （>30s 的三轮是巡检 `scheduleWithFixedDelay(5s)` 的量化误差，不是第二个成因。）
- **用户侧后果（第 4 轮实测）**：应用 JVM 存活 15:56:19 → 16:03:53（**7 分 34 秒**，以 16:03:53 的
  `scanAll: 主控制台已移除` 为终点），而增强会话在 15:56:49 就已摘除且**再未重建**——
  用户在这 7 分半里打的每一次请求（15:56:47 / 15:56:52 / 15:56:54 / 15:56:55 …）都落在「两条通道都死」的窗口里。

## 二、根因（三段证据链，逐段可复现）

### 1. IDE 侧：在线窗口 30s 被真正执行了（这是新行为，不是老代码）

`AgentProtocol.ONLINE_WINDOW_MS = 30_000L`（`AgentProtocol.kt:25`），`AgentManager` 在 T-RA-01 后按它判定
会话健康（`AgentManager.kt:150` / `:157`：`alive && (now - lastSeen) <= heartbeatWindowMs`），
巡检 `sweepOffline()` 每 5s 摘除不健康会话（`AgentManager.kt:845-861`），摘除时 `dead.any { it.alive }`
为真即记为「心跳过期」（`:849`）→ 状态行走 `heartbeatLost` 文案（`:1042-1048`）。

也就是说：**只要 30 秒内没有任何入站帧（EVENT/HEARTBEAT/HELLO），会话必被摘除**。

### 2. agent 侧：心跳实际是 **60.6 秒**一发，不是协议写的 10 秒

`SenderLoop.java` 主循环每轮做三件事（`:36-79`）：

1. 事件尽出（`burst < 64`）；
2. 读入站 CONFIG —— `socket.setSoTimeout(1000)`（`:33`），**无数据时这一读要阻塞满 1000ms**；
3. `if (burst == 0) { Thread.sleep(200); idleMs += slept; if (idleMs >= 10_000) 发心跳 }`（`:68-75`）。

问题在第 3 步：`idleMs` 记的是**假定耗时**（每次 +200ms），而不是真实流逝时间。每轮实际耗时约
1000（CONFIG 读超时）+ 200（sleep）≈ **1200ms**，却只记 200ms——于是「静默 10s 发心跳」需要
50 轮 ≈ **60 秒**才凑够 `idleMs >= 10_000`。

**实测读数**（本轮新做探针：`ServerSocket(0,1,loopback)` + `EventBus.start(port)` + 读帧打时间戳，
复刻 IDE 侧 accept 形态；100s 观察窗）：

```
[probe] +17ms     cmd=HELLO      gapSincePrev=17ms
[probe] +60633ms  cmd=HEARTBEAT  gapSincePrev=60616ms   heartbeats=1
[probe] 观察窗口 100s 到，退出
```

HELLO 后 **60.6 秒**才来第一条心跳，100 秒窗口内只有这一条。即：**心跳节奏比协议声明的慢 6 倍，
比 IDE 的在线窗口慢一倍**——首条心跳永远赶不上 30 秒窗口，会话必然在第一条心跳之前被摘除。

> 附带一条：入站 CONFIG 会把 `idleMs` 清零（`:63`），所以「点开始捕捉」下发 CONFIG 反而把心跳又推后一轮，
> 但这不是主因（第 4 轮 startCapture 在 15:56:41，会话仍死在 15:56:19 起算的 30 秒处）。

### 3. 重连通道不存在：掉了就是永久掉了

agent 侧设计上是无限重连的（`SenderLoop` 断线按 1s→5s 退避重连同一端口，`EventBus` 注释明确写了这点），
但 IDE 侧**只 accept 一次**：

- premain 通道 `acceptPremain()`（`AgentManager.kt:653-669`）`accept()` 到一条连接后立即
  `finally { channel.server.close(); premainChannels.remove(channel) }` —— 监听 socket 被关掉，
  agent 的退避重连只会撞 connection refused；
- attach 链 `attachAndOffer()`（`AgentManager.kt:542-571`）同样是 `ServerSocket.use { ... server.accept() }` 一次性；
- 而重新 attach 的唯一触发点是 `onProcessAppeared`（进程启动事件），应用已经在跑，不会再触发；
  `sweepOffline` 还会把该 pid 从幂等表摘掉（`:854`）。

结果：**任何一次会话摘除，对该次应用运行而言都是终局**。第 4 轮日志里 15:56:49 之后到应用结束（16:03:53）
再无任何 `premain 会话建立` / `attachToPid` 记录，与代码结论一致。

### 4. 守卫为什么没拦住（空绿）

`AgentProtocolSyncTest.帧格式_长度前缀加单字节命令`（`AgentProtocolSyncTest.kt:64-65`）唯一的「心跳」断言是
`assertTrue(loop.contains("10_000L"))` —— 它只证明**源码里存在这个字面量**，对「有效节奏是否 ≤ 在线窗口」
完全不可见。两侧常量各自漂移、跨侧不变量（节奏 < 窗口）无人守，这正是本缺陷能长期存在的结构原因。

### 5. 用户看不到可执行的提示（第二个诉求的由来）

面板空态第二行由 `captureDiagnosticsText`（`CaptureDiagnostics.kt:62`）拼成
`"$channels\n$hint"`，其中 `hint` 正是这条现场最该被读到的话：
「控制台有输出但未见 MyBatis SQL 日志——请确认应用已开启 SQL 日志输出（mapper 包日志级别为 debug），或启用增强捕获」。

但 Swing 的 `JLabel`（`JBList` 空态文本就是一个 `JBLabel`）**不把 `\n` 当换行**——截图里两段被连成一行，
在面板右边界被截断（`…产出 0 条｜重放守卫已丢弃 8 行控制台有输出但…`）。
即：**能解释「为什么捕不到」的那句话，用户根本读不到**，而同一行前半段的「心跳中断」错误叙事又把人往错方向引。
`log.panel.empty`（双语 bundle，`MpSqlVisualizerBundle_zh.properties:443`）同样用 `\n`，有同样的问题。

## 三、修法

### T-01（P0，根因）agent 心跳改为按真实流逝时间计

`SenderLoop.java` 第 3 步不再累加假定的 200ms，改为记录**上一条出站帧的时刻**，每轮比较墙钟：

- `lastOutboundMs` 在每次 `writeFrame`（事件/心跳/HELLO）后更新；
- 每轮循环（无论 `burst` 是否为 0）判 `System.currentTimeMillis() - lastOutboundMs >= heartbeatIntervalMs` → 发心跳；
- 连接建立、写完 HELLO 之后**立即补发一条心跳**——这样即便首轮迭代被 CONFIG 读超时拖住，
  IDE 侧的 `lastSeen` 也从头就是新鲜的，在线窗口不再依赖首轮迭代速度；
- 心跳间隔改由可注入值提供（系统属性如 `sqlvis.agent.heartbeatMs`，默认 10000），
  供 T-02 的快测驱动（默认值不变，生产行为不受影响）。

保留单发送线程模型（事件与心跳共用一条连接，IO 串行是既有设计，不引入第二条写线程）。

### T-02（P0，反漂移）把「节奏 < 窗口」变成跨侧不变量并加真守卫

- IDE 侧在线窗口不再写死 30s 与心跳间隔各自为政：窗口改为按心跳间隔推导并留 ≥3 倍余量
  （心跳 10s → 窗口 30s，值不变，但**推导关系写进代码**，任一侧改动都会体现在另一侧）；
- 测试替换掉 `contains("10_000L")` 这条空绿断言，新增两类：
  1. **跨侧常量守卫**（源码文本扫描，与既有 `AgentProtocolSyncTest` 同款）：断言
     agent 侧心跳间隔常量与 IDE 侧窗口常量满足 `window >= 3 * interval`——两侧任一方漂移即转红；
  2. **节奏实测**（真起 agent `EventBus` + 探针 ServerSocket，用 T-01 的可注入间隔压到 300ms）：
     断言「HELLO 后 1.5 倍间隔内收到第一条心跳」且「相邻心跳间隔 ≤ 1.5 倍设定值」——
     这条用例如果放回修复前的实现，读数会是 6 倍间隔，必红（反向验证见 §五）。

### T-03（P1，韧性）premain 通道支持重连，会话摘除不再等于终局

- `acceptPremain` 改为**循环 accept**（同一 `ServerSocket` 持续监听），每次接受后建会话；
  同 pid 已有活动会话时先关旧会话再挂新（幂等，沿用 `onPremainConnected` 现有判据的语义）；
- 通道生命周期从「accept 一次即关」改为**绑定到本次运行**：以控制台移除（`SqlLogManager.onConsoleRemoved`，
  即项目停止语义的既有承载点，见 `RunConfigurationProcessListener` 类注释）为关闭信号，
  另设一个长 TTL 兜底；重连次数设上限（如 32）防异常抖动下的热循环；
- 泄漏面必须收口：accept 线程持有的 socket 注册进项目 disposer 树（Disposer 树是既有踩坑，
  见全局记忆 `pitfall` 条目），`shutdownAll` / 项目 dispose 时一并关闭；
- 收尾验证：连跑两次运行 + 手工 kill 会话（或在测试里直接 `session.close()`）后确认能自动恢复，
  且 `premainChannels` 不随运行次数增长（`MAX_PREMAIN_CHANNELS = 4` 的上限不应被撞到）。

### T-04（P0，用户第二个诉求）诊断行与空态文案改 HTML 换行

- 渲染层（`SqlLogPanel.syncDiagnostics`，`SqlLogPanel.kt:1240` 与 `buildListPane`，`:1255`）把纯文本改成 HTML：
  `<html><body style='width:Npx'>…<br>…</body></html>`——仓内已有同款先例
  （`EnhancedCaptureConfirmDialog.kt:92` 用 `JBLabel` + `<html><body style='width:480px'>` 做自动换行）；
- 换行点按语义给：增强通道 / 控制台通道各占一行，丢弃与短路计数并到控制台那行，
  最后的「下一步提示」单独一行（这是用户真正要读的）；
- **不改** `captureDiagnosticsText` 的取数契约与单测（它仍返回带 `\n` 的纯文本，由渲染层转 HTML），
  这样既有 5 例诊断用例零改动；`log.panel.empty` 的 `\n` 一并走同一转换；
- 宽度取值需在沙盒里按工具窗实际宽度确认（见 §四 P4）。

### T-05（P1，文案真实性）状态行不再断言它不知道的原因

「应用侧心跳中断（进程可能已卡死）」在这次现场是**错误叙事**：进程活得好好的（又跑了 7 分钟），
只是第一条心跳还没到。改法：

- 文案降级为可核实的表述（例：「已 30 秒未收到应用侧心跳」），去掉「进程可能已卡死」这种因果断言；
- 补一态区分「刚连上、首条心跳未到」与「曾有心跳、之后停摆」——前者是连接早期，
  后者才是应用侧无响应，二者对用户的处置建议不同（等一等 / 去看应用进程）。

## 四、待验证前提

| # | 前提 | 验证方式 | 状态 |
|---|------|----------|------|
| P1 | 修复后首条心跳 ≤ 1 倍间隔内到达（探针复测） | 探针重跑，断言 HELLO→HEARTBEAT 间隔 | **待验**（修复前读数 60.6s，已实测） |
| P2 | 应用启动期（Spring Boot 正在初始化、类加载密集）sender 线程仍按节奏发心跳 | 沙盒实跑，读 `idea.log` 的在线态切换时间戳；应用启动期无法用纯 JVM 探针覆盖 | **待验** |
| P3 | 保持 premain 端口常开不产生端口/线程/会话泄漏 | 连续两次运行后检查 `premainChannels` 规模与线程名 `mps-agent-premain-*` 残留；`shutdownAll` 后端口应释放 | **待验** |
| P4 | `JBList` 空态文本（`JBLabel`）渲染 HTML 且 `body width` 生效、窄工具窗不截断 | 沙盒截图（空态 + 有诊断两态，工具窗拉到最窄再截一次） | **待验**（Swing 标准行为 + 仓内先例，但窄窗表现未验） |
| P5 | 修复前「应用侧心跳中断」文案只在真停摆时出现 | 修复后连跑，确认不再有误报；必要时用测试注入短窗口驱动 | **待验** |
| P6 | 增强通道在本 demo 上确实能捕到 SQL（本轮**没有**任何 SQL 事件样本，因为会话从未活到用户查询） | 修复后沙盒实跑：应用启动 → 打 `http://localhost:8088/user/getAll` → 面板应出现条目 | **未验**——本轮无法证伪也无法证实，属修复后的必做验收项 |

> P6 是本次最需要盯的一条：本轮所有读数都只能证明「通道死得早」，**证不了「通道活着时能捕到 SQL」**。
> 若修复后仍零产出，下一步要查的是 JDBC 织入在 MySQL Connector/J 8.x 上的命中情况，而不是心跳。

## 五、测试

1. **T-02 跨侧常量守卫**：源码扫描两侧常量，`window >= 3 * interval`。
   **反向验证**：把 agent 侧间隔改回 60_000 或把窗口改回 10_000 → 必红（贴两次读数）。
2. **T-01 节奏实测**（真起 `EventBus`，间隔压到 300ms）：HELLO 后 ≤450ms 内收到首条心跳；
   相邻心跳间隔 ≤450ms；观察窗 3s 内至少 6 条心跳。
   **反向验证**：把 `SenderLoop` 的计时改回 `idleMs += slept` 形态 → 读数退化为 ~6 倍间隔，必红。
3. **T-03 重连**：`AgentManagerPremainTest` 现有夹具上加一例——建会话 → 主动 `close()` →
   同一 pid 再次回连 → 断言新会话建立、`sessions.size == 1`、`online == true`；
   再加一例断言重复回连不撞 `MAX_PREMAIN_CHANNELS`。
4. **T-04 渲染**：`CaptureDiagnosticsTest` 既有 5 例零改动（纯函数契约不变）；
   新增渲染层用例断言 HTML 串含 `<br>`、且 `\n` 已全部转成 `<br>`（含 `log.panel.empty`）。
5. **T-05 文案**：`AgentStatusTextTest` / `AgentHeartbeatWindowTest` 按新文案更新断言，
   并新增「首条心跳未到」与「曾有心跳后停摆」两态可区分的用例。
6. **定向回归**：`log` 包 + `log/agent` 包全量；agent 子项目 `./gradlew :sql-visualizer-agent:test`。
7. 构建校验（`verifyGate` / `verifyPlugin` / `buildPlugin`）在全部改动完成后统一跑一次。

## 六、真机验收口径

沙盒 `./gradlew runIde` + `easy-code-demo`：

1. 启动 `EasyCodeDemoApplication`，等应用起来（观察控制台出现 `Started EasyCodeDemoApplication`）；
2. **不碰面板**，静等 **2 分钟以上**；
3. 读 `idea.log`：`增强捕获在线态切换: true -> false` **0 条**（修复前：30 秒处必有一条）；
4. 面板状态行应显示「已挂载 pid=…」，**不得**出现「心跳中断」；
5. 打 `http://localhost:8088/user/getAll`（IDE HTTP Client 或浏览器）→ 面板应出现条目（P6）；
6. 面板空态诊断行截图：提示语应**完整可见且换行**，不被右边界截断（P4）；
7. 手工制造一次掉线（停应用再启、或临时 kill 会话）→ 增强通道应能自行恢复（T-03）。

## 七、未收编项与边界

- **控制台通道零产出不是缺陷**：demo 工程 `dev` 档（`application-dev.yml:35` 用 `Slf4jImpl` +
  `:45-47` 的 `logging.level` 整段注释 + `logback-spring.xml` 不在 classpath 根）本就不打印 MyBatis SQL。
  本计划不改 demo 工程，也不改控制台桥的识别规则；验证控制台链路时按既有记忆的配方临时开 mapper DEBUG 或切 prod。
- **「已挂载 N 个控制台」含 HTTP Client 控制台**（现场 5 个里多数是它），对「应用控制台有没有输出」这个判断是噪音。
  属可选打磨，本次不做。
- **首条心跳之前的窗口**：T-01 的「连上即补发心跳」消除了大部分风险，但若 agent 侧 `SenderLoop`
  线程本身起不来（极端场景），IDE 侧仍会在 30 秒后摘除——这条不再做额外兜底，因为摘除后的恢复由 T-03 承接。
- **`a628a3b` 之前的会话永不摘除**这一行为不回退：心跳窗口本身是对的（半死会话封口控制台桥是更糟的形态），
  本次修的是两侧节奏不匹配，不是取消窗口。

## 实施记录

| 项 | 内容 |
|----|------|
| 代码 | 待实施 |
| 测试 | 待实施 |
| 真机 | 待实施 |

### T-04（空态诊断文案换行）实施记录

| 项 | 内容 |
|----|------|
| 代码 | `CaptureDiagnostics.kt` 新增纯函数 `wrapEmptyTextLines(text, widthPx, measure)`（像素宽度贪心折行，中文按字符、英文优先空白处断，宽度 clamp 到 200px）；`SqlLogPanel.kt` 新增顶层 `applyEmptyTextLines(status, text, widthPx, measure)`（`clear()` + 逐视觉行 `appendLine`）与面板内 `applyEmptyText` / `renderEmptyText` / `emptyTextWidthPx`；两处渲染点（`syncDiagnostics`、`buildListPane`）改走 `applyEmptyText`，宽度取 `list.width` 扣内边距、首帧兜底 400px、挂 `ComponentListener` 在 resize 时重排。`captureDiagnosticsText` 语义行切三行（捕捉中+增强 / 控制台+计数 / 提示）。commit `37a7e9a` |
| 测试 | 定向绿：`CaptureDiagnosticsTest` 17 例（+6 折行用例）、新增 `EmptyTextRenderTest` 4 例（用真实 `StatusText`，不 mock），全 0 失败。反向验证：把渲染点改回 `status.setText(纯文本)`（修复前形态）→ `EmptyTextRenderTest` 4 例红；逐字还原 → 绿 |
| 真机 | **未做**——窄窗截断/换行观感只能在 `./gradlew runIde` 沙盒截图确认，本次未跑，P4 仍**待验** |
| 前提推翻 | 计划 §四 P4 写的「`JBList` 空态文本（`JBLabel`）渲染 HTML」**实测不成立**：`JBList.getEmptyText()` 返回 `com.intellij.util.ui.StatusText`（2024.3.7.1 与 2026.2.1 两个发行版 `javap` 双向确认），内容落在 `SimpleColoredComponent` 上——不解析 HTML、不把 `\n` 当换行、超宽只裁不折。故本任务**未走 HTML 路线**（照原计划会把 `<html><body …>` 字面量画到面板上），改走「自算折行 + `StatusText.appendLine` 逐视觉行写入」；宽度声明（`body style='width:Npx'`）随之作废，改为按 `list.width` 折行 |
