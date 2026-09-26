---
name: pitfall-agent-heartbeat-cadence-vs-online-window
description: 增强捕获会话建立后恰好 30 秒必掉线的根因链——agent 心跳记账耗时而非真实耗时（实测 60.6s vs 声明 10s），IDE 在线窗口 30s 由 T-RA-01 真正执行后必然误杀；且掉线后无法重连（premain 只 accept 一次）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_9714f3d4-9ce3-476d-a607-23791869aab3
---

2026-09-21 用户沙盒实跑报「SQL 日志没有正常捕获到」，面板显示「增强捕获：应用侧心跳中断（进程可能已卡死），已回落控制台捕获」。根因链三段，**每段都可独立复现**：

**1. agent 侧心跳节奏比声明慢 6 倍。** `SenderLoop.java` 主循环每轮：① 事件尽出；② 读入站 CONFIG（`socket.setSoTimeout(1000)`，无数据时**阻塞满 1000ms**）；③ `if (burst == 0) { Thread.sleep(200); idleMs += slept; if (idleMs >= 10_000) 发心跳 }`。问题在 ③：`idleMs` 累加的是**假定耗时**（每轮 +200ms），而每轮真实耗时约 1200ms → 凑够「静默 10s」要 50 轮 ≈ **60 秒**。独立探针直连真实 agent jar 实测：`HELLO` 在 +17ms 到达，**第一条 `HEARTBEAT` 在 +60,633ms**，100 秒窗口内只有这一条。

**2. IDE 侧在线窗口 30s 由 T-RA-01（`a628a3b`，2026-09-18）真正执行了。** `AgentProtocol.ONLINE_WINDOW_MS = 30_000L`，`AgentManager.isSessionHealthy = alive && (now - lastSeen) <= heartbeatWindowMs`，`sweepOffline()` 每 5s 摘除不健康会话。**首条心跳永远赶不上 30 秒窗口 → 会话必在建立后 30 秒被误杀**（应用启动本身常要 20~40 秒，故首轮查询几乎必然落在掉线之后）。沙盒日志四轮运行全部命中：30.0 / 34.0 / 33.6 / 30.0 秒（>30 的是 5s 巡检量化误差，不是第二个成因）。

**回归性质**：`a628a3b` 之前在线态只看 socket 是否断开，会话永不因心跳过期被摘除 → agent 侧这个节奏偏差**被掩盖了一个版本周期**。

**3. 掉线后无法重连，对该次应用运行是终局。** `acceptPremain()` 与 `attachAndOffer()` 都是 `ServerSocket.use { accept() }` 一次性，accept 到就 `finally { server.close(); premainChannels.remove(channel) }`；重新 attach 的唯一触发点是 `onProcessAppeared`（进程启动事件），应用已在跑不会再触发；`sweepOffline` 还把该 pid 从幂等表摘掉。而 agent 侧 `SenderLoop` 设计上是无限退避重连同一端口——**只是撞在一个已关闭的端口上**。现场：应用 JVM 存活 15:56:19 → 16:03:53（7 分 34 秒），增强会话 15:56:49 摘除后**再无任何 `premain 会话建立` / `attachToPid` 记录**。

**Why:** 两条通道（增强 + 控制台）同时零产出，用户看到的是「一条都捕不到」；而状态行把原因叙述成「进程可能已卡死」——进程其实健康又跑了 7 分半，**这是代码无从得知的因果断言**，把人往错方向引。诊断行的另一半（「控制台有输出但未见 MyBatis SQL 日志…」）才是真答案，却被 Swing 截断（见下）。

**How to apply:**
- **判「会话为何掉线」先看存活时长**：恰好等于/略大于在线窗口值（30s）→ 心跳节奏问题；明显更短 → 看 socket 断开类异常。
- **守卫为什么没拦住**：`AgentProtocolSyncTest` 对心跳的唯一断言是 `assertTrue(loop.contains("10_000L"))`——只证明源码里存在该字面量，对「有效节奏是否 ≤ 在线窗口」完全不可见（典型空绿）。**跨侧不变量（节奏 < 窗口）此前无人守**，两侧常量各自漂移。
- **修法方向**：心跳按真实流逝时间计（记上一条出站帧时刻比墙钟，连接后立即补发一条）、窗口由心跳间隔推导并留 ≥3 倍余量、premain 通道改循环 accept 支持重连（生命周期绑到本次运行，以 `onConsoleRemoved` 为关闭信号）。
- **另一个被同一条诊断行掩盖的问题**：`captureDiagnosticsText` 返回 `"$channels\n$hint"`，而 `JBList` 空态**不是 `JLabel`**——**不把 `\n` 当换行**，两段连成一行在面板右边界截断，被截掉的正是可执行的提示。**（2026-09-21 实施期实测推翻本条原写法）**：空态实为 `StatusText`，**HTML 路线（`<html><body style='width:Npx'>` + `<br>`）不可行**，改走 `appendLine` + 自算折行——见 [[pitfall-statustext-no-html-no-wrap]]（该档已订正「空态是 JLabel」的旧表述）。
- 相关：[[reference-demo-console-sql-logging]]（控制台零产出是 demo 配置使然，不是缺陷）、[[runide-demo-verify-workflow]]（沙盒日志位置与操作配方）、[[pitfall-guard-covers-audited-targets]]（守卫空绿的通用形态）。

## 修复后真机沙盒验收（2026-09-21 两轮，`runIde` + `MybatisPlusDemoApplication`）

六项改动全部落地并合入 master，**沙盒实测确认修复成立**：

- **心跳不再掉线**：会话建立后 3 分多钟 `增强捕获在线态切换` 只有建立那一条、`会话读循环退出` 0 条（修复前 30.0 秒必掉）。
- **真停摆才回落**：ntdll 冻结应用 JVM 后，摘除点 = 最后一条心跳 + 30 秒窗口（22:43:27 冻结 → 22:43:43,844 摘除）——**窗口仍在真正执行**，不是被放宽掉的。
- **掉线能自恢复**：恢复 JVM 后**约 1 秒**重建 premain 会话（T-03 循环 accept 的兑现；修复前端口已关会永久失联）。
- **增强通道确实能捕到 SQL**（原「唯一未验前提」已闭合）：打接口 → 面板出现条目。**通道归属由代码决定、不靠猜**：增强在线时 `SqlCaptureGate.paused=true`，`processConsoleLine` 首行即 `return emptyList()`（`SqlLogConsoleBridge.kt:774`）→ 控制台桥不可能产出该条目。
- **通道回收**：`onRunEnded` 在运行结束时关掉**恰好 1 个** premain 通道，重跑后挂起数**恒为 1**，不随运行次数堆积。
- **诊断行换行**：三行独立、提示语完整可见（`log.panel.empty` 的 `\n` 也渲染成两行）。**注意**：原始分辨率下容易误判成一行，**必须裁剪放大再读**。

**两条曾疑似缺陷、经实验证伪**（勿重复怀疑）：
- 「掉线窗口内闸门打开 → 控制台桥复读历史行」——**不成立**。冻结 40 秒（触发摘除+闸门开）再恢复，**无任何新条目**。桥的唯一取行路径是平台 filter（`consoleLinesSeen` 是 filter 回调计数、非文本轮询），replayGuard 又在闸门判定之前。
- 「同一接口打多次却没新条目」——**属去重预期行为，不是丢条目**。`SqlLogEntryBuffer.addEntry` 命中 `dedupeEnabled && isDuplicate` 时**丢弃该条不入列表**，只把丢弃数记到已有条目的 **`×N` 徽标**上（`recordDedupeDrop` + `onDedupeCountsChanged`）。**验「一次请求一条条目」前必须先取消勾选面板的「移除重复的 SQL」**（默认勾选），否则重复请求本就不该出现新条目。
- 另：应用每次重启若都是 `premain 注入完成`（从未走 attach 链），则 `attach 链未获重连` 不会被触发——该路径不在实际执行面上。
