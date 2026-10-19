---
name: pitfall-agent-config-channel-never-applied
description: 增强捕获 CONFIG 控制通道从未生效的根因——IDE 出站帧是 [4字节长度][1字节命令][body]，应用侧 SenderLoop 却先读 1 字节当命令再读 4 字节当长度，停采/脱敏/帧过滤前缀三条指令全部落空；含真 SenderLoop 双布局受控实验、MCP 审计 D1 定案（不受捕获开关约束）与「MCP 工具只受自己开关控制」的实测结论
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bef52d81-b9f2-456b-95bc-3e62d26f3e58
---

2026-09-22 用户报障「我没有开启捕获 SQL 日志，但是被捕获了」（面板开关为 ▶ 未捕获，列表里却有 agent 条目）。**根因＝IDE→应用侧的控制通道（CONFIG 帧）从未被正确解析过**，属自 2026-09-12 M56-01 基座起就存在的形态，2026-09-18 `T-AgentListenSync`（`fb408b9`）把「监听开关 → 应用侧采集」接上后才以用户可见症状暴露。

**帧格式错位（字节级实测，可复算）**：两侧线上格式都是 `[4 字节长度][1 字节命令][body]`——
`AgentManager.kt` 的 `AgentSession.sendConfig` 写 `writeInt(body.size); write(CMD_CONFIG); write(body)`，
`AgentSession` 读循环也是先 `readInt()` 再 `readUnsignedByte()`。而应用侧
`sub-projects/sql-visualizer-agent/.../transport/SenderLoop.java` 的入站读**顺序相反**：
`final int cmd = in.readUnsignedByte(); handleInbound(cmd, readPayload(in, cmd))`，`readPayload` 内部才 `readInt()`。
对 37 字节的 `{"disabled":true,"desensitize":false}` 实测：`cmd` 解析成 `0`（≠ `CMD_CONFIG=3`）→ `handleInbound` 提前 return；
`len` 解析成 `9475` → `readFully` 阻塞 1s 超时 → `SocketTimeoutException` 被 catch 吞掉（注释还写着「无入站数据」）。
**后果**：`AgentState.disabled` 永远停在静态初值 `false`（fail-open，为「启动即捕获」而故意），应用侧从 JVM 启动起持续采集；
`desensitize`（默认 true）与 `skipPrefixes` 同样从未生效。

**为什么症状只在「从未点过捕获」时可见**：IDE 侧 agent 事件入队点（`AgentManager` 的 `CMD_EVENT` 分支 → `entries.addEntry`）
**不判 `capturing`**，唯一的门是 `buffer.paused`。而 `buffer.paused` 初值 false、只有 `stopCapture()` 才置 true，
且 `stopCapture()` 在 `!capturing && !userArmed` 时直接 return。所以「点过停止」的用户看到列表安静（假象：应用侧仍在白采集），
「从未点过」的用户直接看到条目进来。

**现场证据（沙盒日志逐行实测）**：`<repo>/.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/log/idea.log`
——08:23:02 `LogStartupCapture: logAutoCaptureOnStart=false`；**全天 0 条 `startCapture`、0 条 `pushCaptureActive`**；
08:26:39 premain 会话建立（pid=70224，日志原文「启动即捕获」）；08:31:00 出现 `AgentSession - 日志时间未命中 #1（回落捕获时间）：… sql=SELECT …`。

**Why:** 三处「看似有防线、实际没有」的地方值得记住：①`pushCaptureActive` 只证明**指令发出**（日志 `active=false → disabled=true（sessions=N）`），
不证明应用侧收到并生效，下发失败也只 WARN；②新会话建立时的「按当前监听态校正」同样只保证发出；
③`AgentProtocolSyncTest.帧格式_长度前缀加单字节命令` 只断言**出站**写形态与「源码里出现过 handleInbound」，
**对入站读顺序完全不可见**（空绿）；应用侧唯一相关测试只调 `extractSkipPrefixes(payload)`，绕开整层拆帧。

**How to apply:**
- 判「开关不真 / 指令没生效」类报障：先按 `[库角标]` 定通道——`LogSqlEntry.databaseName` 在 master 上**只有 agent 事件与 MCP 审计两处生产者**
  （控制台桥与各解析器从不写它，`SqlSourceMarker` 标记行也只有 2~4 段无库信息）；再在沙盒 `idea.log` 里 `grep startCapture/pushCaptureActive` 计数。
- 修法（已立项 **T-AgentConfigChannel / O-54**，计划 `docs/开发计划/20260922-0906-…`）：入站帧改为「先长度后命令」并让畸形帧走重连（不再静默吞）、
  停采下降沿清空 `EventBus` 队列（否则连接瞬间会补投关闭期间采集的存量事件）、**加 CONFIG ACK 让「生效」不再靠推断**、
  IDE 侧入队补 `capturing` 闸门（保证面板显示不说谎）、关闭路径状态机对齐、空绿守卫换行为级环回测试 + 反向验证、fail-open 加看门狗。
- **发行暴露面**：已发行 `2026.9.14` 树里 `log/agent/` 文件数为 0（`git ls-tree -r --name-only 2026.9.14 | grep -c log/agent/` 实测），
  agent 整条能力线从未随任何发行 tag 交付 → 本项是「首个含增强捕获版本」的上传前置，不需热修。
  **⚠️ 但内测 tag 里有（20260922 实测）**：`2026.9.18-beta.4` 的 `SenderLoop.java` 第 60–61 行仍是旧读序
  （`readUnsignedByte` 在前、`readPayload` 内才 `readInt`），即**本缺陷就装在那个已在 Gitee 上的 beta.4 包里**（该包头条功能正是「增强捕获升级为默认通道」），
  同轮 T-08（`e4e13f4`）与 O-55 定位缓存也都不在 beta.4 里；已备好的发行提交 `1420673`（09-18）同样早于这三个修复四天。
  → **「把 beta.4 转成正式版」之前必须先叠上这些修复**，否则等于把「关不掉的捕获开关」连同默认开启的通道发给正式版用户；
  内容口径三选一（纯 beta.4 ／ beta.4 + 捕获修复 ／ 直接用 1420673）见 [[project-release-workflow]]。

**✅ 已实施（2026-09-22，集成分支 `t-agent-config-locate`，三车道并行）**
- 应用侧 `t-agent-cfg-agent`：`51f94a6`（读帧对齐 + 停采清队列 + `CMD_CONFIG_ACK=5` + 看门狗）+ `4a1c22d`（复核整改）。
  入站统一 `readInt → 校验 0<len≤1MB → readUnsignedByte → readFully`；**帧头逐字节读**以区分「完全无数据」（空闲，上抛读超时）
  与「帧读到一半」（流已错位 → 非超时 IOException 走重连 + WARN）——后者闭合了复核提的「半截帧超时被当无数据」残余。
  看门狗默认 `sqlvis.agent.configWatchdogMs=15000`（超时未见 CONFIG 即应用侧自停 + 一次性 WARN）。
- IDE 侧 `t-agent-cfg-ide`：`f7f0be9`（入队闸门 + ACK 接收/超时 WARN + 关闭路径对齐 + 桥让位条件 + 守卫换有效断言）。
- **桥让位条件改 `online && 应用侧已确认在采`**（原只看 socket 在线）——否则「在线但已停采」时桥被封 + agent 静默 = 两条通道零产出且无信号。
- 反向验证读数：应用侧 16 例全绿，外科式回退读序 6 例转红（含 `SocketTimeoutException: Accept timed out`＝「静默吞、不重连」签名）、摘尽出短路 1 例转红；
  定位器侧 agent util 20/20 + IDE 守卫 3/3。
- 复核（写验分离、另一上下文只读）结论：应用侧 4 项规格逐条落地、「4 例转红」核账独立吻合；8 处发现中 4 处回交整改
  （短路分支零覆盖 / 未知命令逐帧打印 / KDoc 失实 / 看门狗窗口致 flaky），3 处登记为边界。
- **两处超时常量勿混**：应用侧看门狗 15000ms（`sqlvis.agent.configWatchdogMs`，语义「从未收到 CONFIG 即自停」）
  vs IDE 侧 `CONFIG_ACK_TIMEOUT_MS = 5000ms`（语义「ACK 回执等待」）。

**✅ D1 已定案（2026-09-22 用户）——MCP 只受自己那个开关控制，不受「SQL 日志」捕获按钮限制**
用户原话口径：MCP 只受设置页「启用 SQL Visualizer MCP 工具」限制，**不能**受 SQL 日志捕获按钮限制。**两侧实测确认**：
- **MCP 工具本身**：`McpToolGate.isEnabled` 只读 `mcpToolsEnabled` + 工具级禁用清单（`mcpDisabledTools`），**从不看捕获开关** → 无需改。
- **MCP 审计（落 SQL 历史）**：原先走 `SqlLogEntryBuffer.addEntry`，而该口第一行就是 `if (paused) return false`
  → 用户点过「停止捕捉」或项目停止（T-04 让 `onConsoleRemoved` 自动分支置 `paused=true`）时 MCP 条目被**静默吞掉** ✗。
  **已落地（`a295408`）**：新增 `SqlLogEntryBuffer.addAuditEntry`（**跳过 `paused` 门**；关键字过滤/去重窗口/上限淘汰保留——它们是列表展示偏好不是开关），
  `recordMcpExecution` 改走它。**反向验证**：改回 `addEntry` → 新增对照用例 `test记录MCP执行_不受捕获开关约束_paused期间照样落条目` 必红
  （`expected:<1> but was:<0>`），还原后 `McpHistoryIngestTest` 5 例 0 失败。对照用例写成**两臂**（paused 期间普通条目被拦下 + 审计条目照样落库），防空真。

**✅ 收口（2026-09-22）**：六步沙盒走查 **①~⑥ 全通过**——①开关关着不采集（请求 200 而面板仍空）、
②点开始后能捕到、③停止真停（请求仍执行而面板无新增）、④脱敏打码观感（面板 `WHERE p.owner = '***'` vs 真实值两种读数）、
⑤位置 42/63 与控制台标记行逐条一致、⑥增强捕获关掉后控制台回落不零捕获。用户确认「问题已解决」，验收关闭。
真机读数与走查配方见验收文档 `docs/验收文档/20260922-0941-…-验收文档.md` §3 与 [[runide-demo-verify-workflow]]。

**✅ 合并、推送与门禁（2026-09-22）**：三车道 + T-08 + T-ST-02b2 经集成分支 `t-agent-config-locate` 合入 master
（合并提交 `a2b973a`，收口登记 `1158e75`，MCP 审计 `a295408`）；集成分支已删（0 笔未合并）。
**已推送 origin/master**（`5824ea0..bb5dca5`，推送债 0）。最终态门禁：`testShards` 三片全绿（163+173+159 套件 /
**5645 用例** / 0 失败，含 MCP 审计修复）、`verifyGate` 三目标（IU-243/253/262）全 Compatible、agent 子项目 83 例 0 失败；
`testDatabase` 2 例在册环境失败。
**同轮真机级探针另发现并修复一处 P0（T-08，`e4e13f4`）**：`SqlHookCallbacks.onConnection` 在**连接出口**即判
`disabled` 返回原连接 → 停采期间建立的连接**永不包装**（连接池复用、无补偿点）→「先跑应用、再点开始捕获」与
「停采→恢复」两条主路径永久捕不到。**修法＝连接级一律包装，`disabled` 只做逐调用短路**——这正是设计红线
「禁用 = volatile 短路、**不卸载**」的本意，原先的连接级跳过是与红线不一致的额外优化；原 loader 兜底
`AgentStateBridge` 随之删除，包装失败改由 `dropToRaw` 承担（自吞 + 计数 + 一次性告警，从「静默跳过」变**可观测**）。
**该缺陷只可能靠真机级 e2e 抓出**：单元测试全绿、两份独立只读复核都没看见它（它不在控制通道里，在连接包装层）。
**该轮遗下的 7 项残留已立 T-AgentResiduals（O-56，计划 `docs/开发计划/20260922-1410-…`）**——R1 IDE 侧跳过表补齐 / R2 测试侧 `@After` 取服务 / R3 心跳容差 / R4 IDE 侧复核 3 条低风险 / R5 T-08 语句级边界 / R6 testDatabase 2 例 / R7 真机探针纳门禁；用户要求「现在收」且**先做两个真缺陷 O-48/O-49**。

**R5 已定案选 B（20260922 用户问「长远看 A 还是 B」，采纳推荐）——「开关只在执行点判定」**：去掉 `ConnectionHandler` 顶层的
`isDisabled()` 短路（`StatementHandler.invoke` 的逐调用短路成为唯一闸门），让**停采期间 `prepare` 的语句在恢复后也能产出**。
**这条定案的通用价值**：A（保持「连接级也看开关」）留下的是**静默失败**——不报错不提示，用户只觉得「这查询怎么不显示」，
与本条记录的 T-08 是同一族（连接级跳过导致「先跑应用再点开始捕获」永久失效），而 T-08 同样只有真机探针抓到；
B 让规则从三层各有一套窗口收敛为一条，并与 T-08 的修法（连接级一律包装、只在调用处短路）**同构**——否则未来读代码的人必问
「为什么连接一律包、语句却看状态」，那正是下一个缺陷的温床；且 B 可被确定性用例钉住，A 只能写进 KDoc「边界」。
代价：停采态每个 `prepareStatement` 多一次代理分配（小对象、只在已授权增强捕获的进程里），兼容面非新增（捕获开着时本就交代理）；
两个小尾巴＝`dropToRaw` 的一次性告警在关闭态被消耗（改为不占名额）、关闭态分配开销在性能复查里登记一次。

相关：[[pitfall-agent-heartbeat-cadence-vs-online-window]]（同一条 agent 链路的另一缺陷）、
[[pitfall-agent-locator-cache-stale-line]]（同轮报障的第二处缺陷）、[[reference-real-ide-debug-entry]]（沙盒日志位置）。
