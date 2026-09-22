# T-AgentConfigChannel agent 控制通道失效（开关关了仍在捕获）— 开发计划

> 来源：2026-09-22 用户报障「我没有开启捕获 SQL 日志，但是被捕获了」+ 面板截图（`SQL 日志` 页签，
> 开关图标为 ▶ 未捕获，列表里却有 1 条 `[DM（达梦）]` 条目）。
> 定位依据：**沙盒真跑日志** `.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/log/idea.log`
> （2026-09-22 08:22:36 起这一轮 runIde，窗口内逐行实测）+ 帧格式字节级模拟（本计划 §二.2）。
> 相邻事项：本次只动「IDE → 应用侧的控制通道（CONFIG 帧）」与「IDE 侧入队闸门」，
> **不改** premain 启动即捕获特性、不改控制台桥/EP 通道、不改 MCP 审计落历史（见 §八 D1）。
> 编号：占用 **O-54**（O-47~O-52 已用，O-53 留空）。

## 一、现象与证据链

**现象**：面板捕获开关处于「未捕获」（▶）状态，用户从未点过捕获，但列表里出现了应用侧执行过的 SQL。

**沙盒日志实测时间线**（同一份 `idea.log`，2026-09-22）：

| 时刻 | 日志行（截断） | 说明 |
|---|---|---|
| 08:23:02 | `LogStartupCapture.execute: 触发（project=mybatis-plus-sql-visualizer-demo, logAutoCaptureOnStart=false, suppressed=false）` | **项目打开即判定不自动捕获**，开关停在 ▶ |
| 08:26:38 | `premain 注入完成（config=MybatisPlusJoinDemoApplication）：-javaagent:…mps-sql-agent-3818300621.jar=port=62299` | 用户 Run demo（dm profile，达梦库） |
| 08:26:39,068 | `增强捕获在线态切换: false -> true` | 会话建立 |
| 08:26:39,072 | `premain 会话建立：pid=70224（启动即捕获，attach 链已跳过）` | 应用侧**从 JVM 启动就采集**（设计如此） |
| 08:30:58 | `HttpRequestRunConfiguration - Executing generated-requests.http` | 用户跑 HTTP 请求触发业务查询 |
| **08:31:00,949** | **`AgentSession - 日志时间未命中 #1（回落捕获时间）：key=eebb80937e50cdfb params=1 sql=SELECT t.id,t.station_id,…`** | **`capturing=false` 下 agent 事件被入队并显示（行号 #1）** |
| 08:32:07 ~ 08:32:19 | 同样形态连续 `#2` `#3` … | 每跑一次请求就多一条 |
| 08:56:07 | `会话读循环退出（pid=70224, alive 前=true）` | 应用停止，会话结束 |

**全天实测：没有一条 `startCapture`、也没有一条 `pushCaptureActive`**（`grep -c` 于同一文件全窗口）。
即：开关从未打开，但 agent 通道一直在投递，IDE 侧一直在显示。

**来源判定（这条是 agent 通道，不是控制台解析）**：

1. 条目带 `[DM（达梦）]` 角标。该角标的唯一数据源是 `LogSqlEntry.databaseName`，而 master 上只有两处生产者：
   `log/agent/AgentEventAdapter.kt:41`（agent 事件，由 JDBC URL 子协议映射，`dm` → `DM（达梦）`，
   见 `log/agent/JdbcUrlDialects.kt`）与 `mcp/McpToolsetSupport.kt:185`（MCP 审计，值为数据源名且会另带 MCP 徽标）。
   **控制台桥与各解析器从不写该字段**（`SqlSourceMarker` 标记行只有 2~4 段，无库信息）。
2. 条目无来源角标（`[MyBatis]` 之类），符合 agent 通道（增强来源角标已按用户要求移除，见 `log/SqlLogPanel.kt:2224` 注）。
3. 带 `[DeviceInfoServiceImpl.java:42]` 调用点位置——只有 agent 通道从应用内调用栈直取
   （`sub-projects/sql-visualizer-agent/.../hook/StatementHandler.java:139-141`）。
4. 行号 `#1` 由 `SqlLogEntryBuffer.addEntry` 实时分配（`log/SqlLogEntryBuffer.kt:269`），历史文件读回恒为 0
   ——排除「历史回读/预览/快照」等非实时来源。

## 二、根因

### 1. 一句话

**IDE → 应用侧的 CONFIG 帧（禁用/脱敏/帧过滤前缀）从未被应用侧正确解析过**：应用侧读帧时先读 1 字节命令、
再读 4 字节长度，而两侧线上格式都是 `[4 字节长度][1 字节命令][body]`。于是「停止采集」的指令被解析成
`cmd=0`（非 `CMD_CONFIG=3`）直接丢弃，`AgentState.disabled` 永远停在静态初值 `false`（fail-open），
应用侧从 JVM 启动起就一直采集；而 IDE 侧入队点**不判 `capturing`**，条目照常进列表。

### 2. 字节级证据（本计划实测，可复算）

IDE 侧写帧（`log/agent/AgentManager.kt:1448-1460` `AgentSession.sendConfig`）：

```
output.writeInt(body.size); output.write(CMD_CONFIG); output.write(body)
```

应用侧读帧（`sub-projects/sql-visualizer-agent/src/main/java/.../transport/SenderLoop.java:126-128`）：

```java
final int cmd = in.readUnsignedByte();          // ← 读到的其实是 4 字节长度的高位字节
handleInbound(cmd, readPayload(in, cmd));       // readPayload 内部才是 in.readInt()
```

对 `{"disabled":true,"desensitize":false}`（37 字节）这一帧实测：

```
线上字节: 00 00 00 25 03 7b 22 64 ...
应用侧:   cmd = 0x00 → 0（≠ CMD_CONFIG=3）
          len = 0x00002503 = 9475（真实 body 仅 37 字节）
          → handleInbound 因 cmd≠3 提前 return；readFully 要读 9475 字节 → 阻塞 1s 超时
          → SocketTimeoutException 被 catch 吞掉（SenderLoop.java:129-131 注释「无入站数据」）
```

结论：**CONFIG 被静默丢弃，且读流已错位**——后续每一帧 CONFIG 同样丢，控制通道等于完全不通。

### 3. 三处「看似有、实际没有」的防线

| 防线 | 代码位置 | 为何没拦住 |
|---|---|---|
| 「下发即真停」 | `log/agent/AgentManager.kt:1102-1108` `pushCaptureActive` | 只证明**指令发出**（日志 `active=false → disabled=true（sessions=N）`），不证明应用侧**收到并生效**；下发失败也只 WARN |
| 「新会话按当前监听态校正」 | `AgentManager.kt:511-515`（attach 链）、`:837-840`（premain 回连） | 同样只保证「发出」；本次现场正是这条路径在 08:26:39 发过 CONFIG 而应用侧未生效 |
| 「帧格式守卫」 | `src/test/.../agent/AgentProtocolSyncTest.kt:59-68` | 只断言**出站**写形态（`writeInt(body.length)` / `out.writeByte(cmd)`）与「源码里出现过 handleInbound」，**对入站读顺序完全不可见**——典型空绿。应用侧唯一相关测试 `SourceJumpFunnelTest` 只调 `extractSkipPrefixes(payload)`，绕开了整层拆帧 |

> **2026-09-22 追加**：该表还该再补两行——`AgentListenSyncTest`（IDE 侧）与 `HeartbeatRhythmTest`（agent 侧）
> 都起了**真 socket**、读了**真帧**，读序也都正确，但一个只验 IDE 自己的写形态、一个只验 agent 的**出站**帧，
> **都没走 agent 的入站读路径**。这两条最容易被误当成「双端往返已覆盖」，是本缺陷长期绿灯的另一半解释。详见 §十.3。

### 4. 为什么现在才暴露

- 2026-09-18 `T-AgentListenSync`（`a5e27de`）才把「监听开关 → 应用侧采集」这条联动接上；**它只做了 IDE 侧下发**，
  应用侧解析自 2026-09-12 `a16122c`（M56-01 基座）起就是这个错位形态，从未被任何测试或真机走查覆盖过。
- 在此之前，用户侧的可感症状被两件事掩盖：① `buffer.paused` 在「点过停止」后才为 true，列表会安静下来，
  掩盖了应用侧仍在采集；② 会话 30 秒必掉线（T-AgentHeartbeat，2026-09-21 才修），条目本来也到不了列表。
- 本次现场是**从未点过捕获**的初始态（`paused=false`），于是「采集照跑 + 入队无闸门」同时成立，症状直接可见。

## 三、影响面

**受影响（用户可感）**：

1. **捕获开关不真**：开关关着（或从未打开）时，只要应用在跑且增强捕获已授权，列表就会进条目。
   已在现场的还有：`logAutoCaptureOnStart=true` 的项目（用户三份真机 IDE 配置里都是 true）点「停止捕捉」后，
   列表虽因 `paused=true` 安静，但**应用侧仍在采集**（白耗 CPU + 队列积压），与代码注释「真停，非 IDE 侧丢弃」相反。
2. **脱敏开关失效**：应用侧 `AgentState.desensitize` 默认 `true`（`AgentState.java:12`），而设置页默认
   `enhancedCaptureDesensitize=false`（`config/PluginSettings.kt:476`，语义＝采集真实参数值）。CONFIG 不通 →
   agent 条目**恒按打码默认**处理，开关怎么点都不变（含 password/手机号等敏感形态参数的语句上可见）。
3. **帧过滤前缀失效**：`sourceFrameSkipPrefixes` 下发的 `skipPrefixes` 同样从未生效，调用栈候选帧过滤不按用户登记走。
4. **应用侧采集开销**：增强捕获一旦授权，任何 Run 都全程 hook 采集（哪怕用户从未想捕获），是纯粹的浪费。

**不受影响**：控制台桥/EP 通道（`capturing` 逐行判定，见 `log/SqlConsoleBridgeFilterProvider.kt:78`）、
MCP 审计落历史（按设计不判 `capturing`）、历史面板、预览/执行链路。

**发行暴露面**：**已发行版本不受影响**——`2026.9.14`（Marketplace 在售）树里根本没有 `log/agent/AgentManager.kt`
（`git cat-file -e 2026.9.14:…AgentManager.kt` 实测 MISSING），agent 整条能力线从未随任何发行 tag 交付。
含该缺陷的是 `2026.9.18-beta.4` 及 master。因此本任务是**「首个含增强捕获的版本」的上传前置**，
与在册的 `T-ReleaseArtifactHygiene` / `T-ReleaseChecklist`（发行清账）同批处理即可，不需要热修。

## 四、修法

### T-01（P0，根因）应用侧入站帧解析改为「先长度后命令」

- 位置：`sub-projects/sql-visualizer-agent/src/main/java/.../transport/SenderLoop.java:126-131`、`:222-230`。
- 做法：入站与出站统一为 `readInt()` → 校验 `0 < len ≤ 1MB` → `readUnsignedByte()` → `readFully(len)`；
  `readPayload(in, cmd)` 不再自己读长度（消除「一帧读两次长度」的形态），并在长度非法时**抛出并走重连**，
  不再让畸形帧以「读超时」形态静默吞掉。
- 同时：`handleInbound` 对未知 cmd 记一行 WARN（现在是静默 return），畸形帧记 WARN 含长度与首字节。

### T-01b（P1，语义补全）停采指令生效时清空已排队事件

- 位置：`SenderLoop.handleInbound` + `EventBus`。
- 现状：`EventBus.QUEUE`（容量 2048）在未连接/未收到 CONFIG 期间照常入队，`SenderLoop` 连接后**先尽出队列再读入站帧**，
  且尽出循环不看 `disabled`。即使 T-01 修好，连接瞬间仍可能把「开关关闭期间采集的存量事件」补投到列表。
- 做法：应用侧 `disabled` 由 false → true 的下降沿，清空队列并计数（计数可观测）；`SenderLoop` 尽出前加一道
  `AgentState.isDisabled()` 短路。

### T-02（P0，可观测）控制通道加 ACK，让「生效」不再靠推断

- 现状：IDE 侧日志只写「我发了 disabled=true」，应用侧收到与否零证据；本次排障正是卡在这里（只能靠行为反推）。
- 做法：新增 `CMD_CONFIG_ACK`（`AgentProtocol`，取值下一个空闲号，两侧同改；旧 agent 忽略未知 cmd 天然兼容）。
  应用侧在 `handleInbound` 应用成功后回一帧 `{"disabled":…,"desensitize":…,"skipPrefixes":n}`；
  IDE 侧收到后 INFO 记一行（含 pid 与三值），并在 `sendConfig` 后 N 秒内未收到 ACK 时 WARN
  「CONFIG 未获确认——应用侧可能未生效」。`pushCaptureActive` 的日志文案同步改为「已下发」而非「已停采」。
- 收益：这一类「控制通道静默失效」从「只能靠行为反推」变成一行日志直接判定。
- **落地取值（实施后补记，勿混）**：IDE 侧 ACK 等待窗 `CONFIG_ACK_TIMEOUT_MS = 5000ms`（车道 B，语义＝「CONFIG 回执等待」）；
  应用侧 CONFIG 看门狗默认 `sqlvis.agent.configWatchdogMs = 15000ms`（车道 A，语义＝「从未收到任何 CONFIG 即自停」）。
  两者语义不同、数值不同，勿互相引用。

### T-03（P1，防御性闸门）IDE 侧入队判 `capturing`

- 位置：`log/agent/AgentManager.kt:1409-1420`（`CMD_EVENT` 分支）。
- 做法：`capturing == false` 时丢弃该事件并计数（`AtomicLong`），累计首次/每 N 条记一次 WARN
  「增强通道在监听关闭状态下投递了 N 条——控制通道可能失效」。这样即便应用侧再次失控，**面板显示也不会说谎**。
- 边界：只作用于 agent 通道入队点；MCP 审计与历史回读**不经此处**，行为不变（见 §八 D1）。
- 说明：这是「显示真相」的兜底，不能替代 T-01/T-02——应用侧的采集开销仍需 T-01 才能真正止住。

### T-04（P1，状态机对齐）关闭路径统一走一次 stop 转换

- 位置：`log/SqlLogManager.kt:437-457`（`onConsoleRemoved` 自动分支 `capturing=false`）、`:549-562`（`dispose`）。
- 现状：这两处把 `capturing` 置 false，但**不下发 CONFIG、不置 `buffer.paused`**，制造出「按钮 ▶ 但缓冲区仍收条目」的唯一状态
  （`stopCapture` 才会 paused）。修 T-03 后该状态的可见后果被闸门挡住，但状态机本身仍应对齐：两处补 `pushCaptureActive(false)`
  （`dispose` 场景服务正在销毁，改为通过一个不依赖服务存活的窄入口下发或明确记一行「会话将由 socket 关闭回收」）。

### T-05（P1，守卫自证）把空绿守卫换成行为级跨侧测试

- 新增应用侧测试（`sub-projects/sql-visualizer-agent/src/test/java/...`）：
  1. **帧格式金标准**：按 IDE 侧写帧形态（`writeInt + writeByte(cmd) + body`）构造 CONFIG 帧，断言解析出
     `cmd == CMD_CONFIG`、payload 完整、`AgentState.isDisabled() == true`（当前必红）。
  2. **环回端到端**：起 loopback ServerSocket + 真 `SenderLoop`，发 `disabled=true` 后断言
     `AgentState.isDisabled()` 为真，且随后执行一条语句**不产生事件**；再发 `disabled=false` 断言恢复。
  3. **降级路径**：发一个畸形长度帧，断言会话走重连（不静默吞）。
- 改 `AgentProtocolSyncTest.帧格式_长度前缀加单字节命令`：补**入站**读顺序断言（源码扫描仅作廉价绊线），
  并在 KDoc 里记明「本断言对入站读顺序不可见＝本轮空绿的成因」，防止后来人再把它当覆盖证据。
- **判据形态（本计划原文曾被证伪，订正于 2026-09-22）**：不要写成「入站必须 `readInt` 在 `readUnsignedByte` 之前」——
  应用侧定稿形态是**帧头 4 字节逐字节读**（`len = (len << 8) | b` 之后才 `cmd = in.readUnsignedByte()`，为的是区分
  「完全无数据」与「帧读到一半」），真实代码里**没有 `readInt`**（只出现在它的 KDoc 里，而判据先剥注释）→
  照原样落地会变成**永久假红**，并可能诱导后来人去改应用侧。实际落地判据＝「**长度读位置 vs 命令读位置**」，
  同时接受 `X.readInt()` 与 `len = (len << 8) | b` 两种规范形态（离线两侧核对：IDE 车道分支红、应用侧分支绿）。
- **反向验证（必做）**：临时回退 T-01 的解析改动 → 上述 1/2 必红；临时摘掉 T-03 的闸门 → 对应 IDE 侧用例必红。两次读数都要贴进 §实施记录。

### T-06（P2，文案真实性）订正与事实不符的注释/文案

- `AgentManager.pushCaptureActive` KDoc：「agent 侧 CONFIG 即时生效（volatile 标志）」「真停，非 IDE 侧丢弃」——订正为事实（并在 T-02 落地后改为引用 ACK 证据）。
- `SqlLogManager.startCapture/stopCapture` 内「应用侧零开销」表述同上。
- 状态行/设置页若声明「应用侧已停止采集」，一律改为「已下发停采指令（待确认/已确认）」双态。

### T-07（P2，设计定案 D2）fail-open 加看门狗

- 现状：应用侧 `AgentState.disabled` 默认 `false`（为「启动即捕获」而故意 fail-open），只有收到 CONFIG 才会被校正。
  一旦控制通道再出任何问题（格式漂移、丢帧、解析回归），症状就是本轮这样——静默、无界、用户无感。
- 建议：会话连接后 X 秒内**从未收到任何 CONFIG** → 应用侧主动停采并打一条 WARN（保留启动期捕获收益，同时把损害限制在 X 秒）。
  X 取值与「停采 vs 仅告警」待定（D2）。

### T-08（P0，真机实证追加立项，2026-09-22）连接级不得跳过包装——否则「恢复采集」永久失效

- **发现方式**：本计划的真机级端到端车道（假 IDE 探针，`dev-support/e2e-fake-ide`）实测：`CONFIG disabled=true` 的 ACK 已生效、停采窗口 0 事件 ✓，但随后下发 `disabled=false`（ACK 同样生效）后**三轮全零事件** ✗。
- **根因**：`SqlHookCallbacks.onConnection`（**连接出口**回调）在连接级判 `AgentState.isDisabled()` 并直接返回原连接 →
  **停采期间建立的连接永远不被包装**；连接池（Hikari）复用同一批连接，没有任何补偿点。后果是
  **「先跑应用、再点开始捕获」与「停采 → 恢复」两条主路径都永久捕不到**——本计划的修复（CONFIG 首次真正生效）把这个潜伏缺口激活了。
- **修法**：连接级**一律包装**，`disabled` 只做**逐调用**短路（`ConnectionHandler` / `StatementHandler` 里的 `isDisabled()`），
  与设计红线「禁用 = volatile 短路、**不卸载**」一致；原 `AgentStateBridge`（宿主 loader 看不见 agent 类时视同禁用）随之删除，
  包装失败的安全网改由 `dropToRaw` 承担，且从「静默跳过」变为**可观测**（计数 + 一次性告警）。
- **测试**：`ConfigApplyEndToEndTest` 新增「停采期间建立的连接仍被包装_恢复后同一连接即可产出事件」，钉三件事：
  ① 停采态建立的连接仍被包装；② 停采态执行照旧且**无事件**（证明「一律包装」没破坏停采静音）；③ 恢复后**同一连接**上执行立刻产出事件。
  **反向验证**：把连接级判定加回（RED PROBE）→ 新用例立刻红（报「停采态建立的连接仍须被 agent 包装」）；还原后 agent 子项目 **83 例 / 0 失败**。
- **如实登记的语句级边界**：停采期间 `prepare` 的语句仍不包装（`ConnectionHandler` 的逐调用短路在 `prepareStatement` 处即直通）。
  MyBatis 默认 `SimpleExecutor` 每条查询现 prepare、执行后关闭，故**实际不可达**；仅当使用 `ReuseExecutor` 一类复用语句的执行器时会命中，
  届时该语句在恢复后仍哑——已在本用例 KDoc 里钉住，供后续按需处理。

## 五、待验证前提（实施前先证伪，勿直接照抄）

| # | 前提 | 现状与验证方式 |
|---|---|---|
| P-1 | 应用侧 `disabled` 在 08:26:39 之后确实仍为 false（即 CONFIG 从未生效） | **行为已证**（08:31:00 仍投递事件），但应用侧无直接日志。T-02 落地后可直接读到；实施时先用一个临时探针（应用侧 CONFIG 解析失败计数）复现一次 |
| P-2 | 该错位是本轮现象的唯一原因，而非另有第二条投递路径 | **已证（2026-09-22 受控实验，见 §十.2）**：真 `SenderLoop` 两臂对照，IDE 实际写序下 `skipPrefixes`/`disabled`/`desensitize` 三键全不生效，正确写序下三键全生效——唯一变量为字节序，不存在第二条投递路径。原「实施前用 T-05 环回用例跑一次（应红）」的要求已由该实验满足 |
| P-3 | 应用侧队列补投（T-01b）在当前现场是否也发生过 | 未观测到（现场条目与请求时刻一一对应）。按「先证伪再动手」处理：T-05 的用例里加一条队列补投断言 |
| P-4 | 修复后 `desensitize` 方向的用户语义（默认关＝采集真实值） | 代码可判（`PluginSettings.kt:473-476`），但**从未真机验证过打码/不打码的实际效果**——验收需真机看一条含敏感形态参数的 SQL |
| P-5 | `SqlCaptureGate.paused`（agent 在线时桥让位）在 agent 停采后是否会把控制台通道还回来 | **已闭合**：判据改为 `online && 应用侧已确认在采`（车道 B 落地，见 §实施记录 §二），且复核独立判定新判据是旧判据的子集、不新增「两条通道同时零产出」 |

**P-1 与 P-5 之外的新增真机前提（2026-09-22 由假 IDE 探针实测，见 §实施记录 §八）**：

| # | 前提 | 实测结论 |
|---|---|---|
| P-6 | 「CONFIG 生效」＝「用户点开始后就能捕到」 | **证伪**：连接在停采期间建立时从未被包装 → 「先跑应用、再点开始捕获」永久捕不到（T-08 已修） |
| P-7 | 「停采 → 恢复」在同一连接上即可恢复产出 | 同上；T-08 修复后由新用例钉住（同一连接、新 prepare 的语句立刻产出事件） |

## 六、测试与验收

### 自动化

- 应用侧：`SenderLoopFrameTest`（金标准 + 畸形帧）、`ConfigApplyEndToEndTest`（环回真 socket → disabled → 无事件）。
- IDE 侧：`AgentManagerIntakeGateTest`（`capturing=false` 丢弃并计数；`=true` 放行；MCP/历史路径不受影响）。
- 既有守卫：`AgentProtocolSyncTest` 按 T-05 补入站断言。
- 门禁：`testFast` → 全量 `test`（基线 491 类 / ~5600 用例）→ **`:sql-visualizer-agent:test` 须先清其 build**
  （UP-TO-DATE 会给陈旧读数，见记忆 `gradle-in-process-metaspace-oom`）→ `verifyGate` 三目标。
- 反向验证两轮（T-05 内已列），读数入 §实施记录。

### 沙盒真机验收（runIde + demo，`dm` profile）

1. 开关保持 ▶（未捕获）→ Run `MybatisPlusJoinDemoApplication` → 跑一次 HTTP 请求（`generated-requests.http`）：
   **预期** 面板 0 条；`idea.log` 有会话建立 + `CONFIG ACK disabled=true`。
2. 点 ▶ 开始捕获 → 同一请求：**预期** 出现条目且带 `[DM（达梦）]` 角标与 `DeviceInfoServiceImpl.java:42` 位置。
3. 点 ⏸ 停止 → 同一请求：**预期** 面板 0 条新增；且应用侧真停（ACK + 无 EVENT 帧；理想状态是应用侧可见一条停采日志）。
4. 设置页切脱敏开/关 → 同一请求：**预期** 参数在打码/真实值之间切换（当前恒打码，是修复前必红项）。
5. 回归：增强捕获关掉（或未授权）时，控制台通道照常捕获（MySQL demo 走 `Preparing:` 日志），不得变成零捕获。

## 七、不做的事

- 不改「启动即捕获」（premain 注入）特性本身——它是 T-M56Phase2 的有意设计。
- 不动控制台桥 / EP 双轨链路、不动 MCP 审计落历史（D1 待定案）。
- 不为已发行版本做热修（发行物里没有 agent 通道，见 §三）。
- 不顺手改「SQL 列表里增强来源无角标」的展示决策（用户此前明确要求移除）。

## 八、待用户定案

- **D1**：MCP 审计条目（`mcp/McpToolsetSupport.kt:202-215`）是否也应受「SQL 日志」开关约束？
  现状按 T-McpAuditTrail 的既有边界只尊重 `buffer.paused`，即「从未点过捕获」时 MCP 执行语句会直接进列表。
  与本缺陷是同一类「开关不真」观感，但那是当时的有意决策，需用户拍板（倾向：保持现状 + 在列表上给 MCP 徽标足以区分）。
- **D2**：T-07 看门狗的 X 秒取值与行为（停采 / 仅告警）。**已按计划建议定案**：X = 15000ms（`sqlvis.agent.configWatchdogMs` 可调），行为＝应用侧自停 + 一次性 WARN。**注**：IDE 侧的 `CONFIG_ACK_TIMEOUT_MS = 5000ms` 是另一件事（回执等待窗），两者勿混（见 §四 T-02 落地取值）。

## 九、涉及文件清单

| 文件 | 改动 |
|---|---|
| `sub-projects/sql-visualizer-agent/src/main/java/.../transport/SenderLoop.java` | T-01 读帧、T-01b 队列、T-02 ACK、畸形帧告警 |
| `sub-projects/sql-visualizer-agent/src/main/java/.../transport/EventBus.java` | T-01b 清队列入口 |
| `sub-projects/sql-visualizer-agent/src/main/java/.../AgentState.java` | T-02 回执取值、T-07 看门狗状态 |
| `src/main/kotlin/.../log/agent/AgentProtocol.kt` | T-02 新增 `CMD_CONFIG_ACK` 常量与 body 编解码 |
| `src/main/kotlin/.../log/agent/AgentManager.kt` | T-02 收 ACK/超时告警、T-03 入队闸门、T-06 文案 |
| `src/main/kotlin/.../log/SqlLogManager.kt` | T-04 关闭路径对齐、T-06 文案 |
| `src/test/kotlin/.../log/agent/AgentProtocolSyncTest.kt` | T-05 补入站断言 + 空绿说明 |
| `sub-projects/sql-visualizer-agent/src/test/java/...`（新增 2 个测试类） | T-05 |
| `CHANGELOG.md` / `docs/变更记录.md` | 收口时按三处同步纪律补 `[Unreleased]` |

## 十、补充证据与实施约束（2026-09-22 独立复核会话追加）

> 追加来源：另一会话就「用户按设置登记帧过滤前缀后跳转仍落 `com.corp.engine.DmlRepository#query`」独立排障，
> 结论与本源同一根因（§二.1）。以下全为**实测读数**，非推演；用于钉死 §五 P-2 并给实施者两条约束。

### 10.1 第二条独立复现入口（症状面更广：不止「关不掉」，还有「开了不生效」）

| 报障入口 | 用户可感症状 | 复现载体 |
|---|---|---|
| 本源（§一） | 开关关着仍在捕获 | 沙盒 demo（`dm` profile）+ `generated-requests.http` |
| 本轮追加 | **设置页登记帧过滤前缀后，跳转仍落框架方法**（配置完全不起作用） | demo 仓库 `mybatis-plus-sql-visualizer-demo/selfdev-framework-demo`（8086，27 端点，带 `verify-endpoints.sh` 冒烟） |

同根因、不同可感面：本源是「控制指令**关不掉**采集」，本轮是「控制指令**改不了**判定」。
**修复后应同时消解**——这也是「整条控制通道不通」而非「只有 `disabled` 一处分支」的旁证。

### 10.2 受控实验：真 `SenderLoop` 两臂对照（钉死 §五 P-2）

**方法**：单独编译 agent 的 `transport`/`util`/`model`（剔除唯一依赖 ASM 的 `ConnectWeaver` 链；Windows 下 javac 须带
`-encoding UTF-8`），起一个假 IDE `ServerSocket` 接上**真实 `SenderLoop` 实例**，两臂发出**完全相同的 CONFIG body**，
唯一变量是字节序：

| 臂 | 发出布局 | `StackLocator.getExtraSkipPrefixes()` | `AgentState.isDisabled()` / `isDesensitize()` |
|---|---|---|---|
| A | `[len:4][cmd:1][body]`（＝IDE 实际写帧） | `length = 0`（**未生效**） | `false` / `true`（**仍是静态初值，未生效**） |
| B | `[cmd:1][len:4][body]`（＝`SenderLoop` 当前读序） | `length = 2` → `[com.corp.engine., com.corp.meta.]`（生效） | `true` / `false`（生效） |

结论：**P-2 成立且已证**；且 **`disabled` / `desensitize` / `skipPrefixes` 三键一起失效**，与 §二.3「一帧误读、整通道不通」一致。

**补充细节（防过度设计）**：错位是**确定性重复**，不是累积性流损坏——每一帧 CONFIG 都以同样方式被误读
（`readFully` 先消费掉当轮可用字节，再以读超时收场），流并未越错越乱。**因此不要为「重新同步流」写逻辑**，
把读序改对即可；T-01 中「畸形长度抛异常并走重连」是防新形态，不是用来补救当前错位。

### 10.3 防线表补两行：两个「看着像真覆盖、实际同样不可见」的守卫

| 防线 | 代码位置 | 为何没拦住 |
|---|---|---|
| 「真 socket 往返测试」 | `src/test/kotlin/.../log/agent/AgentListenSyncTest.kt:65-90` `testsendConfig_disabled帧体正确编码` | 确实起了 `ServerSocket`、确实读了真帧，但读序用的是**正确**的 `readInt()` → `readUnsignedByte()`，只验证 **IDE 自己的写形态**；从未把该帧喂给 agent 的入站读路径 |
| 「agent 侧真 socket 读帧测试」 | `sub-projects/.../src/test/java/.../transport/HeartbeatRhythmTest.java:124-130` `readFrame` | 同样用**正确**读序，验的是 agent **出站**帧的节奏；入站路径零覆盖 |

→ 对 T-05 的强化：守卫必须落在**真 `SenderLoop` 入站**上。最便宜的形态就是**往返**——用 `SenderLoop.writeFrame`
（它写的是正确的 `[len][cmd]`）写帧、由真入站读，当前必红；这比手搓字节数组更抗「写序也跟着错」的二次漂移。

### 10.4 对 T-01 的两条实施约束

1. **线上格式唯一权威 = `writeFrame`**：读序向它对齐（`[len][cmd][body]`），不要再引入第三种顺序；
   两侧常量与顺序由 `AgentProtocolSyncTest` 加**入站**断言兜住（T-05 已列）。
2. **`readPayload(in, cmd)` 的 `cmd` 形参当前未被使用**——这是「设计意图曾是 `[cmd][len]`」的残留痕迹，
   也是本次误读的诱因。修的时候顺手把签名改为 `readPayload(in, len)`（或直接内联），**消除这个误导性形参**，
   否则后来人会再次据此判错方向。

### 10.5 修复前无任何绕过手段（结论性）

- `AgentArgs` 无 `skipPrefixes` 或前缀类键（实测 grep），`port`/`logLevel` 之外无旁路；
- 全仓 `StackLocator.setExtraSkipPrefixes` 的**生产**调用点只有 `SenderLoop.handleInbound` 一处。

→ **CONFIG 不通 = 这三个设置项 100% 不可用**：设置页重填、Apply、重启应用、重启 IDE 全部无效。
排障时不必再让用户试这些（本轮已逐一排除）。

### 10.6 第二条验收路径（不依赖 dm profile / 沙盒，可脚本化）

除 §六 的沙盒真机验收外，可用 demo 仓库 `selfdev-framework-demo` 做一条与业务无关的最小回归：

| 步骤 | 操作 | 预期（修复后） |
|---|---|---|
| 1 | 起 `selfdev-framework-demo`（8086），**不登记**任何前缀，打 `/selfdev/style/static-call` | 条目位置落 `com.corp.engine.DmlRepository#query`（框架方法＝缺陷现象） |
| 2 | 设置页登记 `com.corp.engine.` + `com.corp.meta.` 后 Apply（**不重启应用**） | 同一请求改落业务帧 `SourceCaptureStyleServiceImpl#staticCall` |
| 3 | 分页端点 `/selfdev/query/paged` 重复 2 | 落 `SourceCaptureQueryServiceImpl#paged`（该路径执行链最深层在 `com.corp.meta`，须两个前缀都登记） |

该路径的价值：**第 2 步「不重启应用」正是 T-01 的判别条件**——修复前怎么 Apply 都不变（§10.5），修复后一次 Apply 即生效；
且它覆盖 `skipPrefixes` 键的端到端，与 §六 的 `disabled` 侧互补。

## 实施记录

### 一、车道与提交（2026-09-22）

| 车道 | 分支 / 工作树 | 内容 | 状态 |
|---|---|---|---|
| A 应用侧 | `t-agent-cfg-agent` / `mpsv-agent-cfg-agent` | T-01 读帧对齐、T-01b 清队列+短路、T-02 ACK、T-07 看门狗、两个新测试类（10 例） | 首提交 `9c1a18d` + 复核整改 `4f22765`，均已合入集成分支（`bd222cc` / `deda071`） |
| B IDE 侧 | `t-agent-cfg-ide` / `mpsv-agent-cfg-ide` | T-02 收 ACK+超时告警、T-03 入队闸门、T-04 关闭路径对齐、T-05 守卫换有效断言、T-06 文案、P-5 桥让位条件 | 实施中 |
| C 定位器（O-55） | `t-agent-locate` / `mpsv-agent-locate` | 见 O-55 计划 §实施记录 | 实施中 |

### 二、自动化读数

**应用侧（车道 A 定向，每轮前清 `sub-projects/sql-visualizer-agent/build`，`--tests "…agent.transport.*"` + in-process + jvmargs）**

- 最终绿：`BUILD SUCCESSFUL`，**TOTAL tests=16 failed=0**（新增 10 例 + 既有 6 例）
- 反向验证 A（摘掉尽出短路 `if`）：`16 tests completed, 1 failed` → `停采期间_队列非空也不写出事件帧`（收到 `{"sql":"SELECT stale-0",…}` 的 EVENT 帧）
- 反向验证 B（**外科式**只把读序改回错位形态）：`16 tests completed, 6 failed` = `停采生效`/`停采期间`（无 ACK）+ 金标准帧 + 畸形长度帧告警 + body 截断 + 会话走重连；还原后复跑全绿
- 逐例鉴别力（车道 A 实测 + 复核独立判定一致）：对读序**有**鉴别力 6 例、**无** 4 例（未知命令绕开拆帧层、看门狗两例、清队列一例——各自是子特性守卫，已在测试 KDoc 逐个标明，不得计入读序覆盖证据）

**IDE 侧（车道 B，分支内定向）**

- 绿：`test --tests "…log.*"` → log 包 **119 类 / 1224 用例 / 失败 2**（仅两条跨车道断言）；`AgentManagerIntakeGateTest` **15/15**
- 反向验证（一轮同时摘两处：T-03 闸门改 `if (false)` + P-5 回退 `paused.set(online)`）→ **4 红**：
  `test监听关闭_事件丢弃并计数_不入队`、`test默认判据跟随捕获态`（闸门）；`test在线但未确认_桥不封`、`test确认停采_桥不封`（P-5）。
  还原后复跑 15/15 绿、工作树与提交一致、无 `RED PROBE` 残留
- 第二笔 `deba286`（仅测试）：修掉 P-5 用例里「等中间态」的竞态假红，改为只等终态

**P-5 落地形态（已核实，写进记录以免后来人猜）**：`SqlCaptureGate.paused.set(online && captureConfirmedActive())`，
`captureConfirmedActive()` ＝「存在 alive 会话且其最近一次 ACK 为 `disabled=false`」——确认值是**值语义**、派生自会话表
（不另设布尔字段），故复位点天然齐全：会话摘除/关闭 → 不在表内；`shutdownAll`/`dispose` → 表清空；
**每次 CONFIG 下发（含 `pushDesensitize`）→ 该会话确认值清回 null（待确认）并立即重算闸门**；ACK 到达 → 写值并重算。
即「ACK `disabled=false` 才为真」；「下发成功但无 ACK」期间**桥不封**（有意取侧：宁可毫秒级双通道产出，也不接受零捕获）。

**其余（全量 / verifyGate）**：待收口统一填。

**合并态 agent 子项目全量套件（集成分支，2026-09-22，`deda071` 后）**：`BUILD SUCCESSFUL in 1m 1s`，
`17 套件 / 77 用例 / 0 失败 / 0 错误 / 0 跳过`（读数按 mtime 过滤本次 XML 统计）——证明帧读取层改动
（含半截帧走重连）没有波及 `AttachGateTest`（真子 JVM + 假 IDE）等其它套件。基线对照：K2J 记录的口径为
14 套件 / 65 用例（更早时点），本轮新增 10 例来自车道 A。

### 三、独立只读复核（2026-09-22，另一上下文，只读、未跑 gradle）

**结论**：应用侧 4 项规格逐条落地、无阻塞性缺陷；「4 例转红」的核账独立吻合（复核者判定的 4 红与作者列举逐名一致）。同时抓出 8 处，处置如下。

| 严重度 | 发现 | 处置 |
|---|---|---|
| 中 | 停采短路分支（`SenderLoop.java:155`）**零测试覆盖**——删掉该 `if` 后 9 例仍全绿；计划 §五 P-3 要求的队列补投断言未落地 | **回交车道 A**：补「置 disabled 后不再写出 EVENT 帧」用例 + 删 `if` 必红读数 |
| 中 | 看门狗自停**无自愈**：`disabled` 跨重连保持 true，只有再收到 `disabled=false` 才恢复；叠加 IDE 侧桥让位仍看「在线」→ 可能「在线但已停采」= 两条通道零产出 | **由车道 B 的 P-5 桥让位条件闭合**（`paused = online && 应用侧已确认在采`）；与验收文档 §1 #7 是同一件事 |
| 低-中 | 未知命令 stderr **逐帧打印**、无限流（与规格「记一行 WARN」字面不符） | **回交车道 A**：照 `SqlHookCallbacks` 的 `WARNED_ONCE` 范式改一次性 + 计数 |
| 低 | `SenderLoopFrameTest.java:39` KDoc 写「本类全红」与事实不符（5 例中 2 例在错位形态下仍绿） | **回交车道 A**：改准确表述并逐个标明无鉴别力用例 |
| 低 | `ConfigApplyEndToEndTest.java:49` 看门狗窗口 3000ms 相对用例前置偏小 → 潜在 flaky | **回交车道 A**：调大窗口或用例内使看门狗失效 |
| 低 | `AgentProtocolSyncTest.kt:129` 守卫锚点文本消失 → `bodyIdx < 0` 使断言静默降级（仍绿但不再锚定主循环体之前） | 已在车道 B 的 T-05 清单内（另加「找不到目标形态即失败」要求） |
| 低 | `SenderLoop.java:185` 只覆盖「完整 len 前缀」形态；帧内部分读超时仍被当「无数据」 | **已闭合**：整改把帧头改逐字节读，区分「完全无数据」（空闲，上抛读超时）与「帧读到一半」（流错位 → 非超时 IOException 走重连 + WARN），并让 body 截断用例真具鉴别力 |
| 低 | `AgentState.setDisabled` 三步非原子（volatile 非 CAS）；生产仅 SenderLoop 单线程调用 | 登记为边界（当前不可达） |

**另一处由车道 A 提出、经本计划裁决的规格外决策**：短路只保证「停采期间不投递」，**不承诺丢弃**已入队事件；停采期间入队的事件在恢复采集后会投出。裁决＝保持现状：`StatementHandler` 在停采态直接短路（`:47`/`:114`）故停采期间根本不会产生新事件，队列里可能残留的只有「停采前已在执行、停采后才完成」的在途事件——语义合法，恢复后投出无害（时间戳仍是其自身执行时刻）。不在上升沿加清空。

**复核同时确认的既有边界（本计划采纳，不改实现）**：停采帧到达的那一轮仍会先尽出 **≤64 条**存量事件（`:155-167` 先于 `:175`）。判断：这些事件发生在用户点停之前，语义合法；且 IDE 侧入队闸门（T-03）会按 `capturing=false` 丢弃，用户可见结果正确；为消除它把读帧提到尽出之前，会让事件投递多等一个 1s 读超时，得不偿失。已要求车道 A 在代码注释里写明该边界。

**复核的未验证项（如实登记）**：① 未跑 gradle，故「15 例全绿 / 4 例转红」两次读数未由复核者复现（读数出处＝车道 A 定向测试与本次补测）；② 反向验证的「回退」前提（外科式只回退读序 vs 整文件回退）此前无书面记录——**本计划自此明确为外科式**（只回退读序，其余改动保留），因为整文件回退会让 9 例全部不编译；③ 车道 B/C 内容与跨侧端到端、真机沙盒六步均未验证（收口阶段处理）。

**环境噪音登记（车道 B 实测，2026-09-22）**：新工作树首次 `./gradlew test` 曾报环境错
`No Java Runtime executable found`（IPGP `JavaRuntimePathResolver` 解析失败），**同命令重跑即恢复**；
疑与多车道并行构建共享 Gradle transform 缓存有关，非代码问题。收口门禁若撞到同类环境错，
**先原样重跑一次再判红**（本仓既有 flake 家族：共享 transform 缓存被崩溃 JVM 打坏）。

### 四、集成分支核验（三车道合入后，2026-09-22）

**跨车道契约核验通过**：IDE 侧车道在自己的分支上有两条断言**必红**（`AgentProtocolSyncTest` 的「`CMD_CONFIG_ACK` 两侧同值」与「入站读序」，因应用侧修复不在其分支），派活时即写明「合入后应自动转绿，若仍红说明应用侧修复被回退」。集成分支（`aca845b`，A+B+C 三车道合入后）实测：

```
./gradlew testFast --tests "cn.song.mybatisplus.visualizer.log.agent.*"
→ BUILD SUCCESSFUL in 2m 24s；8 套件 / 47 用例 / 0 失败 / 0 错误 / 0 跳过
```

两条断言**如期转绿**，契约成立；同批 `AgentManagerIntakeGateTest`（15 例）与 `LocatorSkipPrefixSyncTest`（3 例）在合并态全绿。

**合并顺序**：A（`bd222cc`/`deda071`）→ C（`8a88504`）→ B（`aca845b`），三路写集两两不相交、零冲突。**为不阻塞关键路径，B 的合入未等其自跑反向验证**（其实现与守卫已完整提交），反向验证改由主调度在集成分支补做。

### 五、待收口补齐

CHANGELOG `[Unreleased]` 与 `docs/变更记录.md` 三处同步（**已落**，`84f2cb9`）、验收文档 §2 读数、`testFast` 全量 / `testShards` / `verifyGate` 门禁读数、真机沙盒六步走查。

### 六、实施期被推翻的计划前提与残留

| # | 前提/规格 | 实测 | 处置 |
|---|---|---|---|
| 1 | T-05 判据「入站必须 `readInt` 在 `readUnsignedByte` 之前」 | **证伪**：应用侧定稿是帧头逐字节读（`len = (len << 8) \| b`），代码里没有 `readInt`（只在 KDoc）→ 照原样落地是永久假红 | §四 T-05 已订正为「长度读位置 vs 命令读位置」判据；落地并离线两侧核对 |
| 2 | T-06「状态行/设置页双态」 | **未做**：bundle `settings.enhancedCapture.confirm.body` 的「关闭开关即停止采集」在 ACK 落地后**已为真**，且 bundle 不在车道写集 | 登记为残留；文案无需再改，仅「双态」形态未采纳 |
| 3 | 混合版本（旧 agent 无 ACK）下的桥让位 | 应用侧未升级时确认值恒 `null` → 桥不封 → 捕获开启期间可能**持续双通道产出**（列表去重窗口折叠） | 登记为边界：属过渡态，应用侧合入后收敛为一次 ACK 往返 |
| 4 | `HeartbeatRhythmTest` 计时断言 | 本轮门禁期间命中一次（472ms > 450ms），同代码复跑 82 例全绿 | 判定为 `T-PerfSweep6` 发现 14 的**既有 flake**（首次真实发生记录）；按登记口径处置，建议收口后按该计划把容差放宽到 2× |

### 七、IDE 侧独立只读复核（2026-09-22，另一上下文）与处置

**结论：有条件通过**——规格基本逐条落地、无阻塞性缺陷、跨侧 ACK 字面契约逐字符一致（键序/无空格/布尔小写/`skipPrefixes` 为 int，两侧常量 5 由守卫锁定）。抓出 1 处用户可见文案缺口 + 2 处中风险 + 6 处低风险，处置如下。

| # | 严重度 | 发现 | 处置 |
|---|---|---|---|
| F1 | 中 | T-04 让 `onConsoleRemoved` 自动分支置 `buffer.paused=true`，而 MCP 审计唯一门就是 `buffer.paused` → 该状态下 MCP 执行语句被静默丢弃；计划 §七 原写「不动 MCP 审计（D1 待定案）」，此处事实上改了该边界 | **保留实现**（「捕获关＝列表不进新条目」正是 T-04 本意，MCP 同受约束自洽）；**登记为 D1 的新维度**：用户定案 D1 时须一并决定「项目停止态下 MCP 审计是否仍应入列表」 |
| F2 | 中 | 旧 agent / 永不回执 → 确认值恒 `null` → 桥**永久放行**，双通道长期共存；**脱敏开启时两侧 SQL 文本不同**（应用侧打码 vs 控制台原文）→ 指纹不同 → 去重不折叠 → 可能出现可见重复条目（复核标注为读码推断，未实测） | 登记为边界：增强捕获通道**从未随任何发行 tag 交付**，故「旧 agent」只存在于 beta/dev 混合版本；应用侧合入后收敛为一次 ACK 往返；每次下发已有超时 WARN 可观测 |
| F3 | 低-中 | ACK 超时 WARN 不判会话健康：socket 半死时 5s 后必报「应用侧可能未生效」，把「连接已半死」误叙成「应用侧未生效」 | **登记为残留**（不本轮改）：文案本身未失实（CONFIG 确实未获确认），仅少列「连接可能已断」一因；改生产代码会使在跑的 `verifyGate` 结论作废，性价比不足 |
| F4 | 低 | `startCapture` 顺序为 `paused=false → pushCaptureActive(true) → capturing=true`，故「点开始」后极短窗口内到达的 agent 事件被入队闸门丢弃 | 登记为边界（窗口 ≤ 一次 CONFIG 往返，通常 <1s；闸门计数可观测） |
| F5 | 低 | 抖动面宽于计划描述：**任何设置变更**都会 `SETTINGS_CHANGE_TOPIC → pushDesensitize → sendConfig` → 确认值清空并短暂放行桥 | 登记为边界（同 F2，靠去重折叠） |
| F6 | 低 | 读序判据取**全文件首个**长度读/命令读：将来更早位置出现无关 `readInt()`/`cmd = …` 会**静默对准错处**（本仓顽疾同形） | **本轮已加固**：改为「形态唯一性」判据——每种形态在源码里必须**恰好一处**，多/少一处均显式失败并提示重新对准；`AgentProtocolSyncTest` 4 例复跑绿 |
| F7 | 低 | `INTAKE_DROP_WARN_STEP`（刷屏防线）与 `CONFIG_ACK_TIMEOUT_MS` 取值无字面值断言；另称「计划未登记 IDE 侧反向验证读数」 | 后者**复核读的是旧版本**：读数已登记于 §实施记录 §二（4 红）；字面值断言登记为残留 |
| T-06 | — | 授权弹窗 bundle「关闭开关即停止采集（已挂载的捕获点会保留但不工作）」与设置页「即时生效，无需重启应用」 | **裁定：无需改**——修复后前者**已为真**（CONFIG 现在真的能到应用侧），后者对正常路径（ACK 一次往返）成立；把「待确认」写进用户文案反而引入噪音 |

**复核独立确认的正面结论（记入交付材料）**：新判据 `online && confirmed` 是旧判据的子集，只会「少封桥」不会「多封桥」→ **不新增「两条通道同时零产出且无信号」**；无永久封死；确认值复位点齐全（会话摘除/关闭、`shutdownAll`/`dispose`、每次下发转待确认）；`updateOnline`/`sweepOffline`/状态行/`epActive` 链路均未被改动（diff hunk 逐条核对）；闸门唯一生产消费者与 EP 通道自洽。**测试鉴别力逐例判定**：15 例中 11 例有鉴别力、4 例为控制例/无关例（已在测试内注明不得计入覆盖证据）；读序断言对修复前源码确判红（复核用 `9c1a18d^` 源码核对：`cmd = in.readUnsignedByte()`@127 vs `in.readInt()`@223）。

### 八、真机级端到端（假 IDE 探针，2026-09-22）——发现 T-08 并闭合 P-1

**方式**：车道 D 自建「假 IDE」探针（`dev-support/e2e-fake-ide`：loopback 按协议收发帧、可下发 CONFIG、打印 ACK 与 EVENT），
用 `-javaagent` 把 agent 注入**真实 Spring Boot + MyBatis-Plus-Join + 真实 JDBC 驱动**的 demo 应用（JDK 1.8.0_181，
`logLevel=info`），**不经 IntelliJ GUI** 即完成真机级验证。车道 D 实施到一半掉线（无活动任务、成果全未提交），
其产物由主调度原样收编并提交（`9dde292`），证据保留在 `dev-support/e2e-fake-ide/evidence/`。

| 项 | 读数 |
|---|---|
| A 停采生效 | `CONFIG disabled=true` → **ACK 到达** `{"disabled":true,…}` → 业务请求 200 → **停采窗口 3000ms 内事件数 = 0** ✓ |
| B 恢复采集 | `CONFIG disabled=false` → ACK 到达 `{"disabled":false,…}` → 业务请求 200 → **窗口内事件数 = 0** ✗（D1/D2 两轮同形） |
| **P-1 取证** | **成立**：`jstack` 25 次采样命中 1 次调用链，栈中实测 `com.github.yulichang.interceptor.MPJInterceptor.intercept(MPJInterceptor.java:85)` 位于业务帧与 JDBC 执行点之间（`evidence/p1-stack-evidence.txt`）→ 与静态推断一致，entryKey 确为 MPJ 拦截器帧 |
| 环境元数据 | `run-meta.txt` 记 agent jar sha256、demo git head `4473308`、repo head、java 版本 |

**B 轮 FAIL 的归因与处置＝T-08**（连接级跳过包装 → 恢复采集永久失效，见 §四 T-08）：已修 + 已加回归用例 + 已做反向验证（红/绿两次读数）。

**同轮门禁噪音登记**：`testShards` 首跑 17 例失败，全部为同一
`java.lang.NoSuchMethodError: AgentSession.<init>(…)`——即本仓已登记的**陈旧增量编译产物** flake
（多分支合并后测试类仍解析旧构造函数签名；车道 B 给 `AgentSession` 加了参数）。
按定例 `rm -rf build/classes build/kotlin` 后重跑即绿，**非代码缺陷**。

### 九、门禁实况（2026-09-22 收口）

| 项 | 读数 |
|---|---|
| `verifyGate` | **BUILD SUCCESSFUL**，三目标（IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63）**全 Compatible** |
| agent 子项目全量 | **83 例 / 0 失败 / 0 错误**（含新增 T-08 回归用例） |
| 主编译（`compileTestKotlin`） | BUILD SUCCESSFUL |
| `testShards` shard1 / shard2 | 通过 |
| `testShards` **shard3** | **20 例失败**——见下「跨类服务毒化」 |
| `testDatabase` | 10 例 / **2 例失败**＝`DbDriverPipelineTest` 的 `testDriverArtifactResolvesToJarsOnClasspath`（本地驱动版本集不含 ConnectorJ 9.7.0）与 `testSeedFilesLandWhereDbLooksForThem`（新工作树测试沙盒路径未播种）——**在册环境性失败**，与本轮改动无关 |
| 隔离验证 | shard3 三个受害类单独跑（含两两组合）**全绿** |

**shard3 跨类服务毒化（本轮 testShards 未达 0 失败的唯一原因）**：

- **形状**：`com.intellij.serviceContainer.AlreadyDisposedException: [T-ST-02] AlreadyDisposed@shard3
  service=SqlLogManager … project.isDisposed=false :: Container is already disposed`——**项目未 disposed，但服务容器已 disposed**；
  受害类恰是**所有使用共享 `SqlLogManager` 的类**（`AgentManagerIntakeGateTest` 15 / `McpHistoryIngestTest` 4 / `AgentManagerPremainTest` 1），
  而中间不碰该服务的类（`EpActiveResetTest` / `FatalPassthroughTest` / `LocatorSkipPrefixSyncTest` / `CrudSqlToolTest` …）**全绿**。
- **机制**（本仓已实证，见 `SqlLogManagerProjectState` KDoc 与 T-StabilityTail）：light 测试项目片内跨类复用同一 `ProjectImpl`；
  一旦有调用点在「测试方法之间的 temporarily-disposed 窗口」里发起 `SqlLogManager` 的**失败创建**，
  平台 `LazyInstanceHolder` 会缓存该失败并**在此后每次取用时永久重抛**——于是同片后续所有使用者成片变红。
- **触发源**：本轮新增测试类改变了分片时长权重 → shard3 邻居重排 → 潜伏缺口发作（本仓登记形态：「新增用例即触发」）。
- **排查已排除的两条假设（如实记录，避免后来人重走）**：① 紧邻受害者之前的 `AgentHeartbeatWindowTest`（两两同 JVM 跑绿）；
  ② 前缀子集（6 个「文件内含 SqlLogManager 且无复原工具」的类 + `AgentHeartbeatWindowTest` + 受害类）同 JVM 跑绿
  → 真凶不在「文件字面含 SqlLogManager」的类里，很可能经**生产代码间接取服务**（如控制台 EP 链路 `SqlConsoleBridgeFilterProvider`），
  该形态无法用文件级 grep 定位。证据 XML 已留存（`F:/code/Java/Gitee/SongRenShuo/shard3-fail-evidence/`）。
- **性质**：**测试基建/生产守卫的既有潜伏缺口，非本轮产品代码缺陷**（隔离全绿 + 机制与改动无关）；
  根治方向＝按 T-StabilityTail 登记补齐「窗口内未受守卫的 `getService` 调用点」（该任务已登记 5 处），
  并给测试工具 `SqlLogManagerProjectState.capture/restore` 加 `project.isDisposed` 守卫（避免工具自身在窗口内发起创建）。

### 十一、shard3 flake 根治（T-ST-02b2 生产侧收口，2026-09-22 收口后追加）

> **两层修法，第一层经实测证伪（如实记录，勿只读结论）**：先按本仓登记的「根治方向」加了 `isDisposed` 守卫，
> 重跑 `testShards` **仍 26 例失败**（形状不变）→ 定因见下「为什么第一层不够」，第二层才是根治。

**第一层（`6df02ef`，必要但不充分）**：新增 `log/SqlLogManagerLookup.kt#sqlLogManagerOrNull(project)`——
**在发起 `getService` 之前**先判 `project.isDisposed`；8 处取用点全部改走它，null 语义一律「跳过本次动作」
（审计旁路跳过、EP 不挂过滤器、事件丢弃不斩断流、耗时补全退化为未命中、自动启动分流沿用既有 warn 文案）。
**关键点：不能只靠 `runCatching`**——它只吞掉这一次的结果、仍发起了那次注定失败的创建
（T-ST-02b2 定论，见 `SectionConfigurable` 的字节码级论证）。

**为什么第一层不够（本轮门禁实测 + 本仓既有分析）**：`isDisposed` 判据对 light 测试的
**temporarily-disposed 窗口不可达**——窗口内 `project.isDisposed` 为 **false**（诊断行实测组合即
`project.isDisposed=false :: Container is already disposed`），而 `getService` 仍会因 `messageBus` owner
已 disposed 失败并永久毒化。既然「判状态」判不准，就必须在**结构上不发起创建**。

**第二层（`81196e5`，根治）**：新增 `sqlLogManagerIfCreated(project)`——只用 `getServiceIfCreated`
**读已建实例、绝不发起创建**，故**无论项目处于何种窗口都不会毒化**。读路径 6 处全部改走它
（`AgentManager` 的 `isListenActive` / `listenActiveProvider` / 事件入队、`McpToolsetSupport` 审计、
`SqlPreviewService` 耗时补全、`SqlConsoleBridgeFilterProvider`——服务未创建＝本插件从未启用捕获＝无过滤需求）；
需要**写**共享状态的两处（`LogStartupCapture` 启动期自动捕捉、`SectionConfigurable` 设置联动）仍用创建式入口
+ disposed 守卫（真实 IDE 关闭路径 `isDisposed` 为真，能挡住）。

**真凶定位的推断（如实标注为推断）**：真凶不在「文件字面含 `SqlLogManager`」的类里（前缀子集实验已证伪）→
最可能是**生产路径在某测试里被触发**并在窗口内发起创建，而那次失败被生产代码的 `runCatching` 吞掉，
故诊断行里只有受害者、没有真凶。读路径正是「任意测试都可能触达」的那一片，第二层把它整片移除。

**守卫（唯一可确定性验证的手段，因为该 flake 本身非确定）**：新增 `convention/SqlLogManagerLookupGuardTest`：
① `src/main` 下任何直接取用必须在唯一白名单文件内；② 自检——白名单文件必须**真的**含 `isDisposed` 判定，
且必须含基于 `getServiceIfCreated` 的 `sqlLogManagerIfCreated`（缺任一支即说明入口被掏空或读路径被改回创建式）。
与姊妹守卫 `BannedApiSourceScanTest` 的分工：那条管「测试类改了共享状态不复位」（状态泄漏），
本条管「取用点在 disposed 窗口发起创建」（容器中毒）——两条机制不同、现场同源，不可互相替代。

**反向验证（两臂，均临时改动后还原、无 RED PROBE 残留）**：① 把 `LogStartupCapture` 改回直接取用 →
守卫红并点名该文件；② 掏空白名单文件里的 `isDisposed` 判定 → 自检用例红。还原后守卫绿。

**读数**：第一层后 `testShards` **FAILED（26 例）**、`verifyGate` 三目标 Compatible；
第二层后的定向与 shard3 多轮读数见提交信息与验收文档。

**已知局限（如实登记）**：该 flake 非确定（同一代码重跑可绿可红），故**绿读数不能证明已治好**；
可给的最强证据是「结构上已无创建路径可被窗口命中」+ 多轮重复读数 + 守卫锁定。

**另记（并行会话的独立证实，勿当重复劳动）**：另一会话就同一报障独立排障，用「假 IDE + 真 `SenderLoop`、
两臂仅字节序不同」的受控实验独立证实本计划的根因——`[len][cmd]`（IDE 实际写序）下三键全不生效、
`[cmd][len]`（当时的读序）下三键全生效（其证据作为本文档 §十 保留）。它另指出两条同族盲守卫
（`AgentListenSyncTest` 只验 IDE 写形态、`HeartbeatRhythmTest` 只验 agent 出站帧，都未走 agent 入站读）。
