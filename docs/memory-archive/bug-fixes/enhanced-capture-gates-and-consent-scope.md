---
name: enhanced-capture-gates-and-consent-scope
description: 增强捕获「为什么会提示」的判定链与确认框的配置目录级作用域——链上无项目类型闸门、Gradle 任务配置在未确认态静默跳过；premain 会把 agent 挂进从 IDE 启动的 Gradle 任务 JVM（判「不合适」，用户已定案路线 A 任务语义收窄实施中）；⚠️ v2026.9.23 起发布 jar 已含 premain，旧「现网不受影响」判断失效
metadata:
  node_type: memory
  type: project
  originSessionId: sess_8e0c199a-6676-4570-8373-cbb9b6b85e79
---

> **归档注记（2026-09-24 第十六次精简）**：文中「收窄方向均未实施」已被推翻——用户定案路线 A（任务语义收窄）已实施为 T-GradleTaskInjectScope（`e7112f9`，并入 dev `1597521`）：`PremainTaskScope.decide` 对 test/build 族 skip、runIde 等 IDE 族 skip、bootRun/run 等应用启动保留注入；受控实验仍在真机复验节待做。

用户问「跑 Gradle 插件项目也提示增强捕获，正常吗」（2026-09-23）时的结论与依据。**正常**：这条链上没有任何项目类型闸门（不判断 Spring Boot / MyBatis，也不判断跑的是不是业务应用）。

判定链（2026-09-23 读源码 + 本机日志实测）：

- 开关与授权都是 **IDE 配置目录级**、不随项目——`PluginSettings` 是 `@Service(Service.Level.APP)`，落在 `<config>/options/mp-sql-visualizer.xml`；`enhancedCaptureEnabled` 默认 true、`enhancedCaptureConfirmed` 默认 false。
- **弹确认框**：已开启 + 未确认 + 是「主配置启动」+ 不在 24h 拒绝冷却内（`AgentManager.onProcessAppeared`）。「主配置」= RunProfile 属于 `ModuleBasedConfiguration`（Application / Spring Boot / JUnit），判定在 `RunConfigurationProcessListener.isModuleBasedLaunch`。
- **Gradle 任务运行配置不在主配置之列**：`GradleRunConfiguration extends ExternalSystemRunConfiguration extends LocatableConfigurationBase`（243 源码实测），未确认态被静默跳过、不弹窗——T-PS4-01 刻意收窄。所以「跑 Gradle 任务时弹了确认框」= 真异常，值得往下查。
- **注入**：已开启 + 已确认 + agent jar 可用 + 端口协商成功（`PremainInjection.decide`），同样无项目类型判定。
- `ProjectTypeDetector`（Spring Boot / MyBatis 原生检测）只服务设置页手动触发的「源码拦截器注入」，**不是**这条链的闸门，别误当准入条件。

**授权状态按配置目录隔离（本机实测）**：三处真实 IDE（2024.1 / 2024.3 / 2026.2）与主仓沙盒都是 `enhancedCaptureConfirmed=true`；**每个新工作树/新沙盒是各自独立的 config**——`mpsv-round10-followup` 沙盒里该键缺省（false），跑项目就会再弹一次确认框。这是配置隔离的必然结果，不是回潮。

日志判据（在 `idea.log` 里对关键字即可定位用户看到的是哪一路）：弹窗=`onProcessAppeared: 增强已开启但未确认注入——请求确认后挂载`；Gradle 任务被跳过=`非主配置进程——静默跳过`；注入=`PremainRunConfigurationExtension - premain 注入完成`；挂载尝试=`目标 pid=…` 与 `attachToPid`。面板空态里的「增强捕获：已挂载/未授权…」与设置页状态行只是状态展示，不是弹窗。

## premain 会把 agent 挂进从 IDE 启动的 Gradle 任务 JVM

用户追问「授权后跑 Gradle 任务（runIde、test 这类 fork JVM 的任务）会把 agent 挂到那个 JVM 上，是不是不合适」（2026-09-23），判：**不合适，但不能一刀切排除**。

机制（2026-09-23 核实）：`PremainRunConfigurationExtension.isApplicableFor` 恒 true → 平台对 ExternalSystem 配置也调该扩展（`ExternalSystemRunConfigurationJavaExtension.updateVMParameters` → `JavaRunConfigurationExtensionManager.updateJavaParameters`）→ `ExternalSystemRunnableState.getJvmParametersSetup()` → `GradleTaskManager` → `JavaGradleProjectResolver.enhanceTaskProcessing` 用 init script 把 JVM 参数加到「请求任务列表里属于 `JavaForkOptions` 的任务」的 fork JVM 上。`runIde` 就是 `JavaExec`（`RunIdeTask : JavaExec()`，IntelliJ Platform Gradle Plugin 2.18.1 源码实测）。**工具窗点任务同样命中**——工具窗执行也构造 `ExternalSystemRunConfiguration` 并走 `ExternalSystemRunnableState`（`ExternalSystemUtil.runTask` 里塞 `TASK_NOTIFICATION_LISTENER_KEY`，正是 `ExternalSystemRunnableState.execute` 读的键）。平台自身把这个通道定位为调试器：`JVM_AGENT_SETUP_KEY` 里 `-javaagent:` 是**被直接忽略**的（ExternalSystemRunnableState.java:427-437）；但 Kotlin 插件确实用同一 `jvmParametersSetup` 给 test 任务加调试 agent（KotlinTestTasksResolver.kt:112），故「给 Gradle 任务 JVM 加 agent」本身不违规。

为什么不合适（判据选错：判的是「运行配置形态 + 任务是不是 JavaForkOptions」，不是「这个 JVM 是不是用户的应用」）：注入 `runIde` = 注进 IDE 进程，与同意框承诺的「在**您的应用 JVM** 内挂载捕获点」不符，对本仓更是插件把 agent 注进自己的沙盒；工具窗 `test` 命中 test worker JVM，agent 常驻 socket+线程、hook 包装失败还往 stderr 打 `[T-AgentProxyCL] hook 包装失败…` 污染测试输出；沙盒 IDE 自己用 JDBC（DatabaseTools）→ 驱动被织入 → 沙盒里查库可能回传到 dev IDE 面板（串台）；沙盒是长驻 JVM，dev IDE 关掉后 agent 仍按 1s→5s 退避重连；同意面与注入面不一致（同意只在主配置启动时征求，注入却覆盖后续所有 Gradle/Maven 任务）。硬失败风险已被设计规避：premain jar 落插件临时目录、按 CRC32 定名、ATOMIC_MOVE 覆盖、**不登记 deleteOnExit**（AgentManager.kt:985-998 注释），最坏是噪音与开销，不会把用户的构建/测试搞崩。

为什么不能一刀切排除 ExternalSystem：Gradle 用户用 `bootRun`/`run` 启动应用时 premain 是**唯一可用**通道（attach 链对 Gradle 进程失败，日志实证 `resolvePid 失败（handler=…ExternalSystemProcessHandler）——跳过挂载`，且该 handler 也不是应用 JVM）；整体排除 = 让所有用 Gradle 任务启动应用的用户静默拿不到增强捕获。

收窄方向（按推荐排序，**均未实施**）：① 按**任务语义**而非配置形态收窄——`GradleRunConfiguration` 有 `isRunAsTest` 可区分测试运行（真机 run 配置 XML 里就有 `<RunAsTest>` 字段），先排 test 类；`runIde` 这类按任务名排除；保留 `bootRun`/`run` 等应用启动任务。② 行为不动，把同意框与文档改成如实说明「会覆盖从 IDE 启动的 Gradle 任务 JVM（含测试与 runIde 这类工具任务）」，把知情同意补全，零行为风险。③ 整体排除 ExternalSystem——最不推荐。

## 影响面：已发布 jar 没有 premain

三处真实 IDE 装的是 `mybatis-plus-sql-visualizer-2026.9.18-beta.4.jar`，解开实测：**无 `Premain*` 类、plugin.xml 无 `runConfigurationExtension` 注册**（`since-build="243"`）→ 现网用户与真实 IDE 里根本不会注入任何东西，含 runIde。日志里 3 次 `premain 注入完成` 全在 `mpsv-agent-config-locate` 工作树的**沙盒**（源码构建），配置名都是 `*Application`（demo 应用），无一次是 Gradle 配置。**扫描所有 runIde 沙盒会话的 `JVM options:` 行（约 30 次会话）→ 没有任何一次出现本插件 agent jar**（出现的是 Kotlin `coroutines-javaagent`；09-22 13:12/13:24 两次还有第三方 `cool-request-agent.jar`，说明该通道别的插件在用且能生效）。→ **「已发布版是否受影响」类问题先解开 jar 看类与 plugin.xml，别只看源码**。

证据边界（2026-09-23 未实测）：没有一次「IDE 发起 Gradle 任务 → fork JVM 真拿到 `-javaagent`」的实例（真实 IDE 装的是无 premain 的 beta.4，沙盒里的 runIde 是 CLI 起的或 confirmed=false）。要坐实需受控实验：源码构建的沙盒（confirmed=true）从工具窗跑 `bootRun`，看目标 JVM 命令行与 `GradleExecutionHelper` 打印的 init script。「工具窗任务也走 getJvmParametersSetup」目前是代码可判、未真机验证。

## O-66 后的引导链口径（20260923 交付，合并 master 前在 t-guide-enhanced-first）

`SourceCaptureGuide` 缺源码引导弹框改为**状态感知**：`enabled && confirmed` 才走原「打开设置/立即注入拦截器」两出口（附 `statusSummary` 实时状态一行）；未开启/未授权时默认按钮「开启增强捕获」＝置 `enhancedCaptureEnabled=true` + 手动发布 `SETTINGS_CHANGE_TOPIC`（AgentManager 上升沿清冷却）+ 复用 `requestReauthorization` 确认流。依据：增强捕获 agent 通道本身就携带源码位置（StackLocator→`SqlEvent.sourceFile`→`AgentEventAdapter` 直通条目），是缺位置高频根因的直连出口。`requestReauthorization`/`requestConsentThenAttach` 增可选 `onOutcome` 回调（持久化仍单点在 AgentManager；前置早退不回调）。开启动作**不碰已在运行的应用**（`doAttachAsync` 调用面仅 onProcessAppeared 与确认流同意分支，无轮询扫描），话术必须写「重启应用后生效」。测试缝：`AgentConsentFlowTest` 的 consentProvider 注入 + `UIUtil.dispatchAllInvocationEvents()` 冲 invokeLater 队列，可在 BasePlatformTestCase 里驱动完整三态确认流。

相关：[[reference-real-ide-debug-entry]]（日志/配置路径）、[[runide-demo-verify-workflow]]、[[pitfall-verification-answer-must-state-evidence-limit]]（本条按该纪律分「已实测/代码可判/待实测」三层作答）。
