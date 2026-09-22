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

## 五、待验证前提（实施前先证伪，勿直接照抄）

| # | 前提 | 现状与验证方式 |
|---|---|---|
| P-1 | 应用侧 `disabled` 在 08:26:39 之后确实仍为 false（即 CONFIG 从未生效） | **行为已证**（08:31:00 仍投递事件），但应用侧无直接日志。T-02 落地后可直接读到；实施时先用一个临时探针（应用侧 CONFIG 解析失败计数）复现一次 |
| P-2 | 该错位是本轮现象的唯一原因，而非另有第二条投递路径 | **已证（2026-09-22 受控实验，见 §十.2）**：真 `SenderLoop` 两臂对照，IDE 实际写序下 `skipPrefixes`/`disabled`/`desensitize` 三键全不生效，正确写序下三键全生效——唯一变量为字节序，不存在第二条投递路径。原「实施前用 T-05 环回用例跑一次（应红）」的要求已由该实验满足 |
| P-3 | 应用侧队列补投（T-01b）在当前现场是否也发生过 | 未观测到（现场条目与请求时刻一一对应）。按「先证伪再动手」处理：T-05 的用例里加一条队列补投断言 |
| P-4 | 修复后 `desensitize` 方向的用户语义（默认关＝采集真实值） | 代码可判（`PluginSettings.kt:473-476`），但**从未真机验证过打码/不打码的实际效果**——验收需真机看一条含敏感形态参数的 SQL |
| P-5 | `SqlCaptureGate.paused`（agent 在线时桥让位）在 agent 停采后是否会把控制台通道还回来 | 代码可判（`:945` 按在线态置位），但「agent 在线但已停采」这一新常态下桥是否产出，需在 T-01 修复后真机确认（否则会从「误采集」变成「零捕获」） |

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
- **D2**：T-07 看门狗的 X 秒取值与行为（停采 / 仅告警）。

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

（未实施）
