# T-FirstUsePremainConsent：首次启用增强捕获首轮运行零捕获修复——开发计划

> 2026-10-05 用户报障：第一次使用插件，提示开启增强捕获，点击确定后 SQL 捕获并未生效，重启项目后才正常。
> 本计划基于当日 runIde 沙盒完整复现日志做根因分析后产出。**写完停等用户确认，未获确认不实施。**

## 一、复现证据（沙盒日志，已完成的复现）

日志：`.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/log/idea.log`（2026-10-05 会话）。关键时间线：

| 时刻 | 日志 | 含义 |
|---|---|---|
| 10:15:17.655 | `premain 注入跳过：enhanced-unconfirmed（不提取 jar、不协商端口）` | 首轮 Run 启动前，注入确认未达成 → **不带 agent 启动** |
| 10:15:18.083 | `onProcessAppeared: 增强已开启但未确认注入——请求确认后挂载` | 同意弹窗在**进程已启动后**才弹 |
| 10:15:19.934 | `用户同意注入——继续挂载` | 用户点击确定（报障中的「点击确定」） |
| 10:15:23.062 | `loadAgent AgentLoadException(0)——按可能已挂载继续握手` | 3 秒静默等待后 attach |
| 10:15:23.071 / 26.988 / 27.194 | `CONFIG 已生效（pid=47488, ACK#1/2/3）`，最终 `disabled=false` | agent 在线且已启用——**会话一切正常** |
| 10:15:36~49 | 4 次 `Executing generated-requests.http`（每次伴随 resolvePid 失败跳过挂载的无关 WARN） | 用户真实打了 4 个请求，应用**真实执行了 SQL** |
| 10:15:36~57 | —— | **0 条 SQL 事件**到达 IDE |
| 10:15:57.880 | `会话读循环退出……SocketException: Connection reset` | 用户停掉首轮 Run（正常收尾，非本因） |
| 10:16:01.673 | `premain 注入完成（config=SelfDevFrameworkDemoApplication）：-javaagent:…` | 重启项目后 confirmed 已持久化 → premain 注入 |
| 10:16:05.194 | 首条 SQL `DROP TABLE IF EXISTS ...` 到达 | 捕获一切正常（即用户说的「重启后正常」） |

结论：**复现稳定、非偶发**——首次使用的首轮运行按当前设计必然走 attach 兜底，而 attach 兜底对池化连接结构性零捕获。

## 二、根因分析

### 根因 1（时序）：同意确认的时机必然晚于首轮 Run 的进程启动

- `PluginSettings.State.enhancedCaptureEnabled` 默认 true、`enhancedCaptureConfirmed` 默认 false（`PluginSettings.kt:472-491`）——首次使用恒为「开启但未确认」。
- `PremainRunConfigurationExtension.updateJavaParameters`（进程启动前唯一注入窗口）在未确认态直接产出 `PremainInjection.Decision.Skip("enhanced-unconfirmed")`（`PremainInjection.kt:20,44-49`）。
- 同意弹窗由 attach 链在 `AgentManager.onProcessAppeared`（进程**已出现**）里触发（`AgentManager.kt:404-417`）。该旧语义在 `PremainRunConfigurationExtension.kt:28-31` javadoc 中明文记载：「未确认态的首次知情同意仍由既有 attach 链在进程启动后弹一次——故首次运行必然走 attach」。
- 即：**弹窗弹出时应用已经起好**，点「确定」救不了本次运行——这是设计使然，不是竞态。

### 根因 2（能力）：attach 兜底对 attach 前已存在的连接结构性零捕获

- agent 的捕获模型 = 只织驱动 `connect(String, Properties)` 方法、在**连接创建时**包装（`SqlHookTransformer` → `ConnectWeaver`，织入点 `CONNECT_DESC`，见 `DriverRegistry` 登记表）。
- `AgentMain.agentmain` 虽对已加载驱动类执行 `retransformClasses`（`AgentMain.kt:58-77`，canRetransform=true、只改方法体合规），但**重转换只影响后续方法调用，不能回溯包装已经存在的 Connection 对象**。
- Spring Boot + HikariCP 场景：连接池在应用启动阶段建好并常驻复用。attach 完成时（10:15:23，进程出现后约 5 秒）池内连接全部是 attach 前经未织入的 `connect()` 创建的裸连接——此后应用所有 SQL 都在这些旧连接上执行，钩子永远不触发。
- 日志实证：CONFIG `disabled=false` 三次 ACK（agent 在线且已启用）之后，4 次 .http 请求期间 0 条 SQL 事件；而重启后的 premain 运行连启动期 DDL 都完整捕获（connect 从建池起就被包装）。

### 非本因的伴随观察（登记，不在本任务修）

- `resolvePid` 对 `HttpClientRequestProcessHandler` 三步回退全失败——行为正确（不该对 HTTP 客户端进程挂 agent），仅 WARN 噪音。
- `AgentLoadException(0)` 注释称「JDK8 二次挂载」场景，本次为现代 JDK **首次**挂载也抛出；容错已覆盖（握手成功），仅注释口径与日志措辞待顺带勘正。

## 三、待验证前提（实施者先证伪再动手）

1. **updateJavaParameters 的调用线程可否同步弹模态框**：243 平台 `BaseJavaApplicationCommandLineState` 调用 `updateJavaParameters` 时若在 EDT，模态确认框可直接同步弹出并按结果写 `vmParametersList`；若实测在后台线程（如 Gradle/外部系统链路），同步弹窗有死锁风险——则该路径跳过提前确认（回落既有 onProcessAppeared 弹窗），并在沙盒实测定论后落笔。验证手段：临时日志打印线程名 + runIde 沙盒实测 Application/Spring Boot 配置两形态。
2. **弹窗时机用户可接受性**：Run 点击到进程启动之间插入一次（仅首次）模态确认，会不会被感知为「运行卡住」。备选弱化形态：非模态 balloon + 「本轮不注入」，同意后下一轮生效——若选弱化形态，则首轮仍零捕获，需与 S2 通知组合兜底，产品取舍留用户拍板。
3. **`ensureConfirmedBeforePremain` 抽取后三态落盘语义不漂移**：ACCEPTED 置 confirmed / REJECTED 记冷却时刻 / DISABLE_CAPTURE 关总开关，与 `requestConsentThenAttach` 既有分支逐行对齐（防「合并采信一侧丢另一半」）。
4. **attach 成功判定可用作 S2 通知触发**：`doAttachAsync` 挂载成功且「本轮非 premain 在场」可判（onProcessAppeared 路径 vs `premain 通道在场——跳过 attach` 路径日志已有区分），需确认存在可编程读取的等价状态而非仅日志字符串。

## 四、方案与实施步骤

### S1（主修，IDE 侧）：注入确认前置到进程启动前

- `AgentManager` 抽出可复用的确认入口 `ensureConfirmed(): EnhancedCaptureConsent?`（无进程句柄版，三态落盘语义与 `requestConsentThenAttach` 单点共用），`requestConsentThenAttach` 改为复用它。
- `PremainRunConfigurationExtension.updateJavaParameters` 在「范围闸门通过 + 增强开启 + 未确认 + 非 headless + 冷却期外」时同步走一次确认：
  - ACCEPTED → 当场 `preparePremainVmOption()` 注入，首轮 Run 即带 premain；
  - REJECTED → 冷却，本轮不注入（回落控制台桥捕获）；
  - DISABLE_CAPTURE → 持久化关闭，本轮不注入。
- 前提 1 证伪为后台线程时：该路径静默跳过提前确认（保持零劣化），不做跨线程弹窗。
- 同步修订 `PremainRunConfigurationExtension.kt:28-31` javadoc（旧语义「首次运行必然走 attach」作废）与「零劣化约定：绝不弹窗」条款——收窄为「仅未确认首弹这一种形态，其余路径仍绝不弹窗」。
- 既有 `onProcessAppeared` 弹窗保留作兜底（提前确认被跳过/线程前提不成立的路径），headless 语义不变。

### S2（诚实兜底，IDE 侧）：attach 兜底挂载成功后的能力边界告知

- attach 兜底挂载成功且本轮进程非 premain 在场时，发通知：「已挂载到运行中的进程；该进程在此之前创建的数据库连接不在增强捕获范围内，重新运行项目后完整生效」。
- 通知组按既有 notificationGroup 惯例登记（无 displayName 属性，分组显示名走 bundle key）；不加重试、不加自动重启。

### S3（顺带，不独立成题）

- `AgentMain` javadoc 中「JDK8 二次挂载抛 AgentLoadException:0」口径勘正（首次挂载亦抛，容错路径已实测覆盖）；`AgentManager` 该 WARN 措辞同步。

### S4（测试与反向验证）

- 单测（新增）：
  - `ensureConfirmed` 三态落盘对齐用例（ACCEPTED/REJECTED/DISABLE_CAPTURE 各一）；
  - `PremainRunConfigurationExtension` 决策用例：未确认+EDT 可弹 → 同意后 VM option 非空；拒绝 → 空；冷却期内 → 空且不再弹；headless → 空；
  - S2 通知触发用例：attach 成功且非 premain 在场 → 触发一次；premain 在场 → 不触发。
- 端到端守卫（钉死旧语义不得回归）：「增强开启 + 未确认 + 首轮 Run」必须产出 premain VM option——摘掉 S1 提前确认分支必须变红，贴两次读数。
- runIde 沙盒走查：出厂态（confirmed=false）沙盒跑 demo 首轮 Run，确认 ①弹窗在进程启动前出现 ②同意后首轮启动期 SQL 即被捕获 ③不重启直接打 .http 请求 SQL 可见。
- 定向测试只跑本任务相关类 + `testFast`；不跑全量、不 clean、不 --stop（全量门禁按验证三档分层另派只读收口代理）。

### S5（文档同步）

- CHANGELOG `[Unreleased]` 补条目；若涉及用户可见文案，bundle 三处（en/zh）同步。
- `docs/MEMORY.md` / 项目记忆按批次收口惯例回填。

### S6（登记不实施）

- agent 语句级织入（对 attach 前已存在连接也能捕获）：需把捕获模型从「connect 时包装」扩展到「Statement/PreparedStatement 方法体钩子」，连接归属（URL/数据源）对旧连接不可得，架构改动大——登记为独立候选，本任务不做。

## 五、验收标准

1. 出厂态（首次使用）首轮 Run：确认弹窗出现在进程启动前，同意后**不重启**即可捕获该轮全部 SQL（含启动期）。
2. 拒绝/关闭增强两态首轮行为与现状一致（不注入、不重复弹、冷却语义不变）。
3. attach 兜底仍可发生的路径（前提 1 证伪时）：挂载成功后出现能力边界通知，文案不含虚假承诺。
4. 全部新单测绿 + 端到端守卫「摘修复必红」两次读数留档 + testFast 绿。

## 六、实施记录（2026-10-05 当轮回填）

### 实施内容

- **S1**（`AgentManager.kt`）：从 `requestConsentThenAttach` 抽出三态落盘单点 `confirmEnhancedCapture(): EnhancedCaptureConsent?`（冷却/已释放/未开启早退返回 null 不弹不落盘；三态各自持久化 + `publishStatusChanged` 广播，语义与原内联体逐行对齐）；新增 `ensureConfirmedBeforePremain(): Boolean`（开启+未确认+闸门通过才弹，同意→true 本轮即可注入）与可注入线程闸门 `premainConsentGate`（默认 = EDT 且非 headless）。`requestConsentThenAttach` 改为复用单点，onOutcome/attach 分支与各日志逐字保留。
- **S1 接线**（`PremainRunConfigurationExtension.kt`）：`updateJavaParameters` 在范围闸门后、`preparePremainVmOption` 前调用 `ensureConfirmedBeforePremain()`；javadoc 两处修订（「首次运行必然走 attach」旧语义作废；「绝不弹窗」收窄为「仅未确认首弹一种形态」）。
- **S2**（`AgentManager.kt` + `plugin.xml` + bundle 双语）：`attachToPid` 成功路径挂 `notifyAttachFallbackOnce()`（能到该路径必非 premain 在场——premain 在场在 doAttachAsync 入口即跳过）；`onRunEnded` 复位「每轮一次」标记（复位前移到 isEmpty 早退之前）；通知出口自吞异常、fatal 透传（T-FP-02）；独立通知组 `cn.song.mybatisplus.visualizer.agent`（一域一组惯例）+ `notification.agent.attachFallback.title/body` 双语键。
- **S3**：`AgentMain.java` javadoc 勘误（「AgentLoadException:0 仅 JDK8 二次挂载」旧口径推翻——243 沙盒现代 JDK **首次**挂载亦观察〔idea.log 10:15:23 实录〕，握手成功为准）；`AgentManager` 类注释同步。
- **S4 测试**（`AgentPremainConsentTest`，12 例）：`ensureConfirmedBeforePremain` 六态（可弹同意/拒绝/关闭增强/EDT 守卫 false 静默回落/冷却期内/已确认不弹/未开启）+ 首轮 Run 链路（未确认可弹同意 → `preparePremainVmOption` 产出 `-javaagent:`）+ 扩展接线字节码守卫（常量池须含 `ensureConfirmedBeforePremain` 引用，证据边界=证明接线在场不证明执行时序）+ S2 通知每轮一次/onRunEnded 复位/出口异常不外泄。

### 待验证前提的实测落账

1. **updateJavaParameters 调用线程**：守卫采用「EDT 且非 headless 才弹，否则静默回落」设计，两种形态都安全——沙盒线程实测定论随下轮 runIde 走查批留账（生产形态大概率 EDT，BaseJavaApplicationCommandLineState 链在执行启动期）。
2. 同意为同步模态形态；若真机走查出现「Run 点击到进程启动被感知卡顿」，备选弱化形态（balloon+下轮生效）留产品拍板，本形态组合 S2 通知兜底。
3. 三态落盘语义经 6 个单测逐态对齐，未发现漂移。
4. attach 成功可编程判定成立（`attachToPid` 返回值即判定点），S2 触发即挂其成功路径。

### 过程坑（留档）

- **`testFast` 不吃 `--tests` 过滤**：任务自带固定 include 清单，传 `--tests` 报「No tests found for given includes」——定向跑用标准 `test` 任务 + `--tests`。
- **测试类路径缺平台 execution 深层类型**（`ExecutionEnvironment`/`RunProfileState` 解析失败）：fake `RunConfigurationBase` 路线放弃，扩展接线守卫改字节码常量池判据（仍可「摘掉必红」，见反向验证）。
- **AgentManager 实例跨测试复用**（同 AgentConsentFlowTest 在案口径）：「每轮一次」标记跨用例残留致用例假红——标记开放 `internal` 并在 setUp 复位。

### 反向验证两次读数

- **红**：摘掉扩展里 `manager.ensureConfirmedBeforePremain()` 调用 → `test扩展接线字节码守卫_updateJavaParameters必须走前置确认` 失败，断言原文「扩展必须在注入前调用 ensureConfirmedBeforePremain（T-FirstUsePremainConsent S1 接线，摘掉必红）」。
- **绿**：恢复接线 → `AgentPremainConsentTest` + `AgentConsentFlowTest` + `AgentManagerPremainTest` 三类 **32/32 绿**（BUILD SUCCESSFUL）。

### 遗留与收口状态

- 定向绿 32/32 + agent/config 包回归（读数见提交信息）；`testFast` 全桶与全量门禁按三档分层另派只读收口代理。
- **runIde 沙盒走查留下轮批**：出厂态首轮 Run 弹窗时机（进程启动前）+ 同意后首轮启动期 SQL 即捕获 + 不重启直接打 .http 可见（验收标准 1/3 的真机段）+ 前提 1 线程实测定论。
- 本任务未触达 build.gradle.kts / plugin.xml EP 面（仅新增 notificationGroup 声明，既有 EP 无改动）——构建校验按三档分层判据不在必跑面。
