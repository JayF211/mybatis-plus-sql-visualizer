# T-AgentConfigChannel + T-AgentSourceLocateStale 交付验收文档

> 交付：2026-09-22｜分支 `t-agent-config-locate`（车道 A `t-agent-cfg-agent` / 车道 B `t-agent-cfg-ide` / 车道 C `t-agent-locate` 三路合入）
> 来源：用户报障「我没有开启捕获 sql 日志，但是被捕获了」+ 续报「并且捕获的源码位置也不对」
> 计划：`docs/开发计划/20260922-0906-T-AgentConfigChannel-…-开发计划.md`、`docs/开发计划/20260922-0917-T-AgentSourceLocateStale-…-开发计划.md`
> **本文件在收口时补齐实测读数**：§2 自动化读数、§3 真机走查结果。

## 1. 本次交付的用户可见变更

| # | 变更 | 修复前（用户视角） | 修复后 |
|---|---|---|---|
| 1 | **捕获开关恢复为真开关** | 面板开关关着（或从未打开），只要应用在跑且增强捕获已授权，SQL 日志列表就会进条目 | 开关关着时列表不进条目；应用侧同时真停采（不再白耗采集开销） |
| 2 | **「停止捕捉」真正停到应用侧** | IDE 只是停止显示，应用 JVM 内仍在采集（与注释「真停」相反） | 应用侧收到停采指令并生效；IDE 侧日志可见「应用侧已确认」 |
| 3 | **脱敏开关恢复可用** | 开关怎么点都不变——应用侧恒按打码默认处理 | 打码/不打码跟随设置页开关 |
| 4 | **帧过滤前缀恢复可用** | 设置里登记的自研框架前缀从未生效，调用栈候选帧仍含框架帧 | 下发生效，候选帧按登记过滤 |
| 5 | **控制通道可观测** | 指令发没发出去、应用侧收没收到，日志都看不出来（排障只能靠行为反推） | 应用侧回执（ACK）+ IDE 侧超时告警：未确认即 WARN 点名 |
| 6 | **源码位置不再被钉死** | MPJ 用户一个应用进程内所有条目显示同一个位置（首个查询的位置），点击跳转到同类里**另一个方法** | 每条条目的位置各自正确，与注入拦截器打出的 `SQLVIS-SOURCE` 标记行一致 |
| 7 | **「在线但已停采」不再变成零捕获** | （隐患）桥让位给增强通道后，若应用侧实际已停采，两条通道同时零产出且无信号 | 桥的让位条件改为「应用侧已确认在采」，未确认时控制台兜底照常产出 |

## 2. 自动化验证（收口时填读数）

| 项 | 命令 | 结果 |
|---|---|---|
| 应用侧定向 | `./gradlew :sql-visualizer-agent:test --tests "cn.song.mybatisplus.sqlvis.agent.transport.*"` | 待填 |
| 定位器定向 | `./gradlew :sql-visualizer-agent:test --tests "cn.song.mybatisplus.sqlvis.agent.util.*"` | 待填 |
| IDE 侧定向 | `./gradlew testFast --tests "cn.song.mybatisplus.visualizer.log.agent.*"` | 待填 |
| 全量 | `./gradlew test`（基线 491 类 / ~5600 用例） | 待填 |
| 平台门禁 | `verifyGate`（三目标 Compatible） | 待填 |
| 反向验证 | 各车道回退修复后必红的两轮读数 | 见各计划 §实施记录 |

## 3. 真机/沙盒走查（需人工执行，沙盒 runIde + demo `dm` profile）

准备：`./gradlew runIde`（沙盒）+ demo 工程 `mybatis-plus-join-demo` 勾 `dm` Maven profile 运行。

1. **开关关着不采集**：捕获开关保持 ▶（未捕获）→ Run demo → `GET /mpj/device/rightAlias`。
   预期：SQL 日志列表 0 条；`idea.log` 可见会话建立与「应用侧已确认」类日志（若未确认则有超时 WARN）。
2. **开关打开才采集**：点 ▶ 开始捕获 → 同一请求。
   预期：列表出现条目，带 `[DM（达梦）]` 角标与源码位置。
3. **停止真停**：点 ⏸ → 同一请求。
   预期：列表无新增；应用侧停采（ACK 可证）。
4. **脱敏开关生效**：设置页切换脱敏 → 跑一条含敏感形态参数（如手机号/密码列）的语句。
   预期：参数在打码 `***` 与真实值之间切换。
5. **源码位置正确**（O-55）：同一进程内先 `GET /mpj/device/rightAlias`（真值 42 行）再 `GET /mpj/device/extSub`（真值 63 行）。
   预期：两条条目的位置分别是 `DeviceInfoServiceImpl.java:42` / `:63`，且与控制台 `SQLVIS-SOURCE|…|<行号>` 标记行逐条一致（修复前第二条会错成 42）。
6. **回归**：把增强捕获关掉（或换非 MPJ 工程 `mybatis-plus-demo`）→ 控制台通道照常捕获，不得变成零捕获。

## 4. 已知边界 / 不在本次交付范围

- **MCP 审计条目不受捕获开关约束**（`mcp/McpToolsetSupport.recordMcpExecution` 按设计只尊重 `buffer.paused`）：从未点过捕获时，MCP 执行过的只读语句仍会进列表。属既有决策，待用户定案（计划 §八 D1）。
- **两侧定位器的跳过规则仍是两份实现**（未合并为共享常量），只加了跨侧同步守卫与差异登记；同一执行若两侧取栈时机不同仍可能给出不同位置，已在 KDoc 明示（计划 §八 D3）。
- **看门狗取值**：`sqlvis.agent.configWatchdogMs` 默认 15000ms，行为＝超时未见 CONFIG 则应用侧自停 + 一次性 WARN（计划 §八 D2 按此定案，可改）。
- **不属于本次交付、勿重复验收**：控制台桥 / EP 双轨链路、MCP 工具链、预览与执行链路、回滚与快照、SQL 历史。

## 5. 回归基线

- 全量测试基线：491 类 / ~5600 用例（收口时按实测更新）。
- 平台门禁：`verifyGate` 三目标（243 / 253 / 262）全 Compatible。
- 受影响面：`sub-projects/sql-visualizer-agent`（agent 运行时）+ `log/agent`（IDE 侧会话与入队）+ `log/SqlLogManager`（状态机）+ `log/CallerSourceLocator`（IDE 侧定位规则）。控制台解析、面板渲染、预览/执行链路未改。
