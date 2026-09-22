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
| 8 | **「先跑应用、再点开始捕获」与「停采→恢复」不再永久失效**（T-08，真机发现） | 控制通道修好后暴露：停采期间建立的连接**从未被包装**（连接池复用、无补偿点）→ 用户开着捕获关跑应用、之后点开始捕获，一条都捕不到 | 连接级一律包装，`disabled` 只做逐调用短路；恢复后**同一连接**上执行即产出事件 |

## 2. 自动化验证（收口时填读数）

| 项 | 命令 | 结果 |
|---|---|---|
| 应用侧定向 | `:sql-visualizer-agent:test --tests "…agent.transport.*"` | **16 例全绿**（新 10 + 既有 6）；反向 A 摘尽出短路 1 红、反向 B 外科式回退读序 6 红 |
| 定位器定向 | `:sql-visualizer-agent:test --tests "…agent.util.*"` | **20/20 绿**（Convergence 8 + Staleness 5 + Diagnose 7）；反向 A 回退 T-01 → 5 例 4 红（`expected:<63> but was:<42>`，与报障同形）、反向 B 仅摘 MPJ 前缀仍绿、反向 C 守卫 2 红 |
| IDE 侧定向 | `testFast --tests "…log.agent.*"`（集成分支） | **8 套件 / 47 用例 / 0 失败 / 0 错误 / 0 跳过**；含 `AgentManagerIntakeGateTest` 15 例、`LocatorSkipPrefixSyncTest` 3 例 |
| **跨车道契约核验** | 同上（A+B+C 合入后） | **两条跨车道断言如期由红转绿**（`CMD_CONFIG_ACK` 两侧同值、入站读序）；派活时即写明「仍红＝应用侧修复被回退」 |
| 合并态 agent 子项目全量 | `:sql-visualizer-agent:test` | **18 套件 / 82 用例 / 0 失败 / 0 错误 / 0 跳过**（含真子 JVM 的 `AttachGateTest`） |
| 主编译 | `compileTestKotlin` | `BUILD SUCCESSFUL in 11m 36s`（含平台依赖与多车道并发拖慢） |
| 全量 | `testShards`（基线 491 类 / ~5600 用例） | **全绿**：shard1 166 套件 / shard2 160 / shard3 169，**失败文件 0**（`BUILD SUCCESSFUL in 3m36s`）；另 shard3 单独连续两轮绿（`2m47s` / `3m3s`） |
| 平台门禁 | `verifyGate`（三目标 Compatible） | **三目标全 Compatible**（IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63，`BUILD SUCCESSFUL in 2m32s`，T-ST-02b2 第二层后重跑） |
| 独立复核 | 应用侧（另一上下文、只读） | 4 项规格逐条落地、无阻塞性缺陷；「4 例转红」核账独立吻合；抓出 8 处（4 处回交整改、1 处并入 IDE 侧、3 处登记边界） |
| 独立复核 | IDE 侧（另一上下文、只读） | 有条件通过：规格基本逐条落地、跨侧 ACK 字面契约逐字符一致；15 例闸门测试中 11 例有鉴别力；抓出 1 处用户可见文案缺口（裁定无需改）+ 2 中 + 6 低（处置见计划 §实施记录 §七） |
| **真机级端到端**（假 IDE 探针 + `-javaagent` 注入真实 Spring Boot/MPJ 应用，不经 GUI） | `dev-support/e2e-fake-ide/run-e2e.sh` | **停采生效 ✓**（`disabled=true` 的 ACK 到达 + 3000ms 窗口 0 事件）；**恢复采集 ✗→已修**（`disabled=false` 的 ACK 到达但三轮 0 事件 → 定位为 T-08 连接级跳过包装，已修并加回归用例）；**P-1 取证成立**（jstack 实测 `MPJInterceptor.intercept` 在调用栈上）；证据见 `dev-support/e2e-fake-ide/evidence/` |
| 门禁（首跑噪音） | — | 首跑 17 例 `NoSuchMethodError: AgentSession.<init>` 系**陈旧增量编译产物** flake（多分支合并后测试类解析旧构造签名），清 `build/classes`+`build/kotlin` 后即绿 |
| **门禁（shard3 跨类服务毒化 → 已根治）** | T-ST-02b2 两层修法（`6df02ef` / `81196e5`） | 首跑 20 例、第一层后 26 例失败 → 定因「`isDisposed` 判据对 light 测试的 temporarily-disposed 窗口不可达」→ 第二层把读路径改走 `getServiceIfCreated`（**结构上不发起创建**）→ **shard3 连续三轮绿 + 全量 testShards 三片全绿**。该 flake 非确定，绿读数不构成证明；结构性论据 + 多轮读数 + 扫描守卫为准（见计划 §十一） |

## 3. 真机/沙盒走查（需人工执行，沙盒 runIde + demo `dm` profile）

**准备（在集成分支工作树 `mpsv-agent-config-locate` 内执行）**

```bash
# 1) 起沙盒（首次会下平台发行物，较慢；后续走缓存）
./gradlew runIde -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1024m"
# 2) 沙盒内打开 demo 工程 F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo
#    并在 Maven 面板勾 dm profile（或直接改 application.yml 的 spring.profiles.active）
# 3) 运行 MybatisPlusJoinDemoApplication（应用端口 8082）
# 4) 触发业务 SQL（两个端点分别对应真值 42 与 63 行）
curl http://localhost:8082/mpj/device/rightAlias
curl http://localhost:8082/mpj/device/extSub
```

> 自动化替代：车道 D 的「假 IDE 探针」用 `-javaagent` 把 agent 注入同一个 demo 应用（不经 IDE GUI），
> 已覆盖「下发停采后应用侧真的停采」与「entryKey/位置取证」两项；**面板观感与开关交互仍需人眼**。

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
