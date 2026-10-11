# Cool Request（coolrequest-vip）agent 字节码增强 深度调研报告

- **调研日期**：2026-09-11
- **调研对象**：Cool Request（作者 houxinlin，仓库 `F:/code/Java/Github/other/houxinlin/coolrequest-vip`，HEAD `9dd3ce95`），GPL-3.0 开源 IDEA 插件
- **调研动机**：为本插件 M56「增强捕获（agent 字节码通道）」立项吸收竞品精华——Cool Request 是「IDEA 插件内嵌 Java agent + 字节码增强 + 插件↔被调试应用通信」的成熟开源先例，与本插件规划路线高度同构
- **调研方式**：只读源码分析（四路并行子智能体：attach 挂载链路 / 字节码增强核心 / 通信与 native JVMTI / 打包工程化），全部结论带 文件:行号 证据
- **关联**：本插件 M56 计划 [20260911-0710-M56-增强捕获agent字节码通道master落地与竞品精华吸收-开发计划.md](../开发计划/20260911-0710-M56-增强捕获agent字节码通道master落地与竞品精华吸收-开发计划.md)

## 0. 一句话结论

Cool Request 的 agent 定位与本插件**方向相同、路线相反**：它要「绕过 HTTP 直接反射调 Controller / 抓完整调用栈」，所以选了 **`-javaagent` premain（启动期注入，不改运行配置不可能）+ ByteBuddy/Javassist + 双向 RMI + 自研 native JVMTI**；而本插件要「运行期热挂载、零配置、不改工程」，所以选 **attach 热挂载 + 裸 ASM + loopback Socket**。**它最有价值的不是「它怎么挂 agent」，而是它反复踩坑后固化下来的工程化手法**（relocate、工具链、端口协商、异步上报、线程本地调用链），这些对本插件直接可用；它的挂载方式（premain）则恰好反证了本插件坚持 attach 路线的必要性。

## 1. 挂载链路：它其实用的是 premain，不是 attach

这是本次调研最重要的认知修正——**Cool Request 当前版本根本没有运行期 hot attach**。全仓库搜不到 `com.sun.tools.attach` / `VirtualMachine.attach` / `loadAgent` / `getNativePid`，零命中。

它靠两部分注入：

**① `RunConfigurationExtension` 启动期注入 `-javaagent`（唯一注入点）**

`src/main/java/dev/coolrequest/idea/listener/CoolRequestRunConfigurationExtension.java:20-39`，经 `plugin.xml:140-141` 注册：

其 `updateJavaParameters` 在启动参数上做四件事（`CoolRequestRunConfigurationExtension.java:20-39`）：跳过可执行 jar 启动器（:22）、先调用 agent jar 落盘（:23）、注入三个 `cool.request.*` JVM 属性（agent 路径 :26 / 监听端口 :27 / SQL hook 开关 :28）、开启动态刷新时追加 `-javaagent:<agent 路径>` 并做「已有该参数则不重复加」的去重（:33-36）。

`isApplicableFor`/`isEnabledFor` 恒返回 true（:62-71），即对用户**正常的 Run 和 Debug 都生效**，不是只对 runIde 沙盒。

**② agentmain 形同虚设（反面教材）**

v2（当前主用）`cool-request-agent-v2/src/main/java/dev/coolrequest/agent/AgentBootstrap.java` 只定义了 `premain`（:71-73），**没有 `agentmain` 方法**，但 MANIFEST 里却声明了 `Agent-Class`（build.gradle :60/86）。这意味着若对 v2 做 hot attach，会因找不到 `agentmain` 而静默失败。v1（旧版）倒是有 `agentmain`（`cool-request-agent/src/main/java/com/cool/request/agent/CoolRequestAgent.java:67-69`），但插件侧没有任何代码调用它——纯预留。

| 挂载对比 | Cool Request | 本插件 M56 |
|---|---|---|
| 挂载方式 | `-javaagent` premain（改运行配置） | `VirtualMachine.attach` hot attach（不改配置） |
| 生效时机 | 应用启动前 | 应用运行中、随时开关 |
| 拿 pid | 不需要（启动前改参） | 反射 `ProcessHandler.getNativePid()` |
| 失败模式 | 无 attach 类失败（已进启动参数就必挂） | attach 失败/远程/容器 → 回落控制台桥 |
| JEP 451 风险 | premain 不受影响 | 目标 JDK21+ 动态告警（M56 已纳入） |

**借鉴点（attach 反向印证）**：Cool Request 选 premain 的理由（「在应用启动时就埋点、避免 hot-attach 时序与权限问题」）反证了 premain 的刚性——它对「已启动进程」直接失效，等于要求「改配置重启」。本插件要「开画即用、免重启」，attach 是唯一路线，无需因为它没用 attach 就动摇；但它验证了一条可用路径：如果未来要支持「启动即捕获」，`RunConfigurationExtension` 注入 `-javaagent` 是与 attach 互补的低成本补位。

**可移植的插桩细节**：`Hooks` 只在 `-Dcool.request.hook.sql=true` 时才挂 SQL hook（`Hooks.java:18-24`），即「挂 agent」与「开 SQL 跟踪」通过 JVM 属性解耦——对应本插件「agent 已挂载 / 增强开关关 = 只做 volatile 短路」。均匀剂到 M56 的增强开关设计。

## 2. 字节码增强核心：ByteBuddy 运行时插桩 + Javassist 静态分析

### 2.1 工具分工（一写一读，互不重叠）

| 库 | 版本 | 职责 | 证据 |
|---|---|---|---|
| ByteBuddy | 1.14.8 | 运行时插桩（`AgentBuilder` + `Advice`） | `common/build.gradle:24` |
| Javassist | 3.30.2-GA | 静态读字节码、解析方法调用指令图（`dumpMethod` 功能） | `common/build.gradle:25`；`trace/TraceHandler.java:80-219` |

所有插桩统一用 `new AgentBuilder.Default(new ByteBuddy().with(TypeValidation.DISABLED))` + `Advice.to(...).on(methodMatcher)`，配合 `RedefinitionStrategy.RETRANSFORMATION` + `disableClassFormatChanges()`（`Hooks.java` 全文）——后者保证只注入方法前后增强、不改变方法签名/字段/类结构，能对已加载驱动类安全 retransform。这对本插件是**确定性的好消息**：M56 担心的「已加载驱动/动态代理类 retransform 兼容性」风险，Cool Request 用 `RETRANSFORMATION`+`disableClassFormatChanges` 组合在生产插件里实证可行。

### 2.2 SQL 拦截器矩阵（本插件最关心的竞品实现）

`Hooks.java:26-72` 静态注册四类驱动 hook，受 `cool.request.hook.sql` 开关控制：

| 驱动类（ElementMatchers.named） | 拦截方法 | Interceptor | SQL 提取方式 |
|---|---|---|---|
| `com.mysql.cj.jdbc.ClientPreparedStatement`（MySQL8） | `executeInternal` | MySQLInterceptor（:44） | 反射 MethodHandle 调 `getQuery()` 拿 `com.mysql.cj.Query` 再 `asSql()` |
| `com.mysql.jdbc.PreparedStatement`（MySQL5） | `executeInternal` | MySQL5Interceptor（:54） | MethodHandle 调 `asSql()` |
| `org.postgresql.jdbc.PgPreparedStatement` | `executeWithFlags` | PgPreparedStatementInterceptor（:63） | 直接 `String.valueOf(obj)`（PG 驱动 toString 返回完整 SQL） |
| `ru.yandex.clickhouse.ClickHouseStatementImpl` | `getInputStream` | CKInterceptor | 取 `args[0]` MethodHandle 调 `getSQL()` |

**核心洞察（本插件最重要的借鉴）**：Cool Request **没有做 setXxx 参数还原**——它绕过 `PreparedStatement.setXxx` 插桩，直接在 execute 入口「问驱动要已拼好的 SQL」（`getQuery().asSql()` / `asSql()` / `getSQL()` / `toString()`）。全仓搜 `setString/setObject/setLong` 在 `trace` 包内零命中；`parameter/ParameterConverter.java`/`StringParameterConverter.java`（合计 19 行）是**无人引用的遗留预留**接口。

这给本插件 M56 一个关键取舍信号：M56 原案「hook setXxx 逐调用跟踪实参、按实例身份还原」是成本高、易碎的一条路；Cool Request 证明了「hook execute 入口 + 驱动自带 asSql/getSQL 拿完整 SQL」是零解析、零 setXxx 插桩的捷径。**但注意它的边界**：拿到的是驱动已拼 SQL，其是否含真实参数值取决于驱动实现，且 `MethodHandle` 每帧现查（见 §5.1 性能问题）。本插件若要「参数实参直取 + 精确耗时/行数/报错」的完整字段，仍需 setXxx 跟踪或双层 hook；但对「只要 SQL 文本」的快速路径，这是个可选降级。

### 2.3 方法级追踪与完整调用链（ThreadLocal 栈 + 树双结构）

- `MethodEnterInterceptor`（`interceptor/MethodEnterInterceptor.java:34-71`）：@OnMethodEnter 建根帧 `TraceContext.init()`，@OnMethodExit `TraceContext.getAlTrace()` 取整棵树经 RMI 一次上报、再 `clear()`。
- `MethodInterceptor`（`interceptor/MethodInterceptor.java:27-55`）：@OnMethodEnter `TraceContext.push`、@OnMethodExit `pop`。
- `TraceContext`（`trace/TraceContext.java:6-39`）基于 `ThreadLocal<MethodTraceContext>`；`MethodTraceContext`（`trace/MethodTraceContext.java:27-56`）内部 `java.util.Stack<MethodTraceFrame>`，push 时既 `peek().getSubMethodTraces().add(frame)` 又 `push(frame)`——把「栈」同时物化成「子节点树」。
- **关键串联**：SQL 拦截器（`MySQLInterceptor.java:65`）与 `MethodInterceptor`（:46）共用同一 `ThreadLocal` 的 `TraceContext`，SQL 帧作为叶子节点天然融入「入口 → Controller → Service → DAO → SQL」树。`threadLocal.remove()` 防泄漏（`TraceContext.java:26`）。

这直接印证 M56 的 T-M56-05（方法级聚焦）/T-M56-06（请求级聚合）设计：per-thread 栈 + 树物化 + 共用 ThreadLocal 是低成本串联调用链的成熟范式，且比 M55 的「静态行号区间近似」更接近真实执行边界。SQL 帧融入调用链恰好是「Ultra 方法级分组」的 agent 版实现。

### 2.4 参数与方法栈采集

`frame/MethodStack.java`（:12 对 `parameter` 字段加 `@JsonSerialize(using = SafeObjectSerializer.class)`）——元素级容错序列化（`gson/SafeObjectSerializer.java:23-52`：先序列化到 TokenBuffer 草稿区，成功才提交，失败降级 `toString()`），防循环引用/惰性字段把 `StackOverflowError` 炸穿线程栈。**这条对 M56 的「参数脱敏/序列化」是现成的防护范式**。

## 3. 通信通道：对称双 RMI + native JVMTI

### 3.1 双向 RMI 注册表（两端各起一个 Registry，互为客户）

| 角色 | 类 | 位置 |
|---|---|---|
| 插件起 Registry（插件=服务器） | `PluginRMIService.init()` | `src/main/.../components/PluginRMIService.java:26-38` |
| agent 起 Registry（agent=服务器） | `RMIServer` | `common/.../rmi/RMIServer.java:38-56` |
| 插件 → agent 远程接口 | `AgentRemoteService` | `rmi/agent/AgentRemoteService.java:13-85`（16 个方法） |
| agent → 插件回传接口 | `ICoolRequestPluginAgentRMI` | `rmi/agent/ICoolRequestPluginAgentRMI.java:28-39`（6 个方法） |

**端口协商（本插件可借鉴的「端口自报」范式）**：

1. 插件在 `updateJavaParameters` 里把 `cool.request.port=<listenerPort>` 写进 `-D`（:27）；
2. agent 起自己的 RMI 端口（`AgentBootstrap.java:45`，`SocketUtils.findAvailableTcpPort()` 随机 13024-65535）；
3. agent 用 `cool.request.port` 反连插件，`RMIServer.notifyPlugin()`（:58-69）调 `rmiAgent.agentStartup(agentPort)` 把自己的端口报回；
4. 插件 `ICoolRequestPluginAgentRMIImpl.agentStartup`（:67-69）→ `CoolRequestAgentManager.register(agentTcpPort)` 存入 `Set<Integer>`（天然支持多被调试进程）。

**对本插件的取舍**：M56 用 length-prefix 自定义 JSON loopback Socket，而非 RMI。Cool Request 用 RMI 的理由（双向强类型对象调用）本插件用不上（M56 是单向事件流），且它暴露了两个 RMI 明显短板，**都是本插件应避免的**：

- **零序列化防护**：全仓搜 `ObjectInputFilter`/`jdk.serialFilter`/registry 鉴权均无实际调用——loopback 裸 Java 反序列化有注入风险，本插件自定义协议反而更安全可控。
- **每次上报都 `lookup`**：`Reporter.reporter()`（`trace/Reporter.java:22`）每次 SQL 都 `RMIFactory.createRMIAgent()` new registry + lookup，高 QPS 浪费——本插件 Socket 长连接复用规避。
- **两端 DTO 重复且漂移**：agent 侧 `rmi-model` 与插件侧 `src/main/.../rmi/agent/model` 多文件内容不一致（diff 实证）——本插件协议 schema 应抽单一共享模块。

### 3.2 native/JVMTI 能力（本插件大概率不需要，但值得了解）

`InstanceAgent.java` 是 native 声明：`getInstancesByClassName`/`getInstancesByClass`/`getClassLoader`/`getThreadContextClassLoader`。JNI 源码在仓库内 `src/main/jni/agent.c`、`hook.c`，预编译库 `resources/{cool-request-agent.dll, libcool-request-agent.so/.dylib}`。

- **捞实例**（`agent.c:110-238`）：`IterateOverInstancesOfClass`（打 tag）+ `GetObjectsWithTags`（收集）→ 构造 `Object[]`。用 tag `98765L`，收集后再 `IterateOverInstancesOfClass(JVMTI_HEAP_OBJECT_TAGGED)` 归零防污染。这是「从运行中 JVM 按类型捞所有实例」的标准 JVMTI 手法。
- **抓调用栈**（`hook.c:337-464`）：申请 `can_get_source_file_name`/`can_get_line_numbers`/`can_access_local_variables`，`GetStackTrace` 拿栈帧，`GetLineNumberTable`/`GetLocalVariableTable` 读行号与局部变量，`GetMethodName`/`GetMethodDeclaringClass` 拼方法签名——`NativeBridge.captureStack` 走这套。
- **平台限制**：`OSSupport.java:8-13` 只支持 Windows/Linux amd64 + macOS aarch64，其余 `startRMIServer` 直接不启动（`AgentBootstrap.java:79-89`，靠 `OSSupport.isSupport()`）→ 整体降级。
- **捞 Spring bean**：`JvmInstanceProvider`（`method/JvmInstanceProvider.java:44-59`）遍历 `Instrumentation.getAllLoadedClasses()` 找同名 `Class` → `InstanceAgent.getInstancesByClass`；不支持 JVMTI 的平台回落到 `SpringInstanceProvider`（用 Spring 自带的 `ApplicationContext.getBeansOfType`）。

**结论**：这套 native 能力是「绕过 HTTP 反射调 Controller」这个特色功能的地基，**本插件 SQL 捕获不需要 JVMTI**——M56 靠 ByteBuddy/ASM 插桩 JDBC/MyBatis 调用点就够。仅当未来要做「IDE 端指定对哪个 DataSource/连接实例采集」（按运行实例定位）才需借鉴 `identityHashCode` 作跨进程实例稳定标识的手法（`ObjectInstance.java:94-106`）。

## 4. 打包工程化（本插件 M56 几乎可整套照搬）

### 4.1 agent 独立子工程 + Copy 内嵌分发

`build.gradle.kts:34-59`：agent 是独立 Gradle 子项目，`shadowJar` 出 fat jar，主插件用 `copyExternalJar`（Copy 任务）在 `runIde`/`buildPlugin`/`publishPlugin` 前把它复制进 `src/main/resources/lib/`。运行时 `ResourceDecompressor.java:104-125` 用 `getClass().getResource("/lib/cool-request-agent.jar")` 读出字节流，写到 `~/.config/.cool-request/request/lib/`（`CoolRequestConfigConstant.java:35,42`）。`plugin.xml` 对 agent jar **零声明**（纯资源，非依赖）。

**这正是 M56 计划的 agent jar 落盘方案**，且 Cool Request 提供了一个反面教训：`copyData` 所有 `catch (IOException ignored)` 静默吞掉（:108-109,122）——用户会「莫名抓不到 SQL」，本插件应显式上报落盘失败并校验 jar 存在。

### 4.2 relocate 全映射（agent 必须 shade 的直接证据）

`cool-request-agent-v2/build.gradle:42-48` 的 shadowJar 配置做三件事：固定产物名、`mergeServiceFiles()` 合并 `META-INF/services`（保 ByteBuddy/Javassist SPI 不丢）、把 jackson / bytebuddy / javassist 三套库全部 relocate 到 `dev.coolrequest.agent.lib.*` 私有命名空间。

`mergeServiceFiles()` 合并 `META-INF/services`（保 ByteBuddy/Javassist SPI 不丢）。relocate 到私有命名空间的理由：agent 跑在**用户 JVM**，用户 Spring Boot 应用几乎必带 jackson（AOP 场景带 bytebuddy），不 relocate 会与用户已加载类冲突，且 `appendToSystemClassLoaderSearch`（`AgentBootstrap.java:29-38`）会让自己 jar 被父委派抢先解析到用户版本。

**对本插件是决定性结论**：M56 用 ASM，agent 在用户 JVM 跑，**必须自行 shade + relocate ASM**——不能复用 IDEA 平台那个 `org.jetbrains.org.objectweb.asm`（那是 IDE 进程内的事，帮不到注入用户 JVM 的 agent）。这与 M56 原案「ASM 须 shade」的判断一致，Cool Request 提供了「ByteBuddy/Javassist 全套 relocate 到私有命名空间 + mergeServiceFiles」的完整落地样板。

### 4.3 javax/jakarta 双变体共存（教科书级，本插件不一定需要但手法可复刻）

三层隔离：① 分模块物理隔（`javax`/`jakarta` 各 import 各的 Servlet API，永不同 classpath）；② 运行时反射探测选型（`DispatcherClassLoader.createDispatcher` 看能 `loadClass("javax.servlet.ServletRequest")` 还是 `jakarta.servlet.http.HttpServletRequest`）；③ `jakarta/build.gradle:47-58` 自定义 consumable configuration `agentApi` 绕开 variant 属性不匹配（`common/build.gradle:33` `implementation project(path: ':jakarta', configuration: 'agentApi')`）。本插件 M56 不强依赖 Servlet 版本，但「分模块 + 反射探测 + 自定义 configuration」若未来要兼容 javax/jakarta 项目可直接照搬。

### 4.4 多 JDK 兼容：`toolchain` + `--release` + `disableAutoTargetJvm`

`jakarta/build.gradle:14-27`：JDK 17 toolchain 编译（能读 Spring6/Boot3 的 class v61）+ `options.release.set(8)`（产物压回 Java8 字节码）+ `disableAutoTargetJvm()`（阻止 Gradle 自动按 toolchain 抬升 target）。所有模块（含 common）都防御性调用 `disableAutoTargetJvm()`。这实现「高版本 JDK 编译、低版本字节码产物」，让 agent 跑在 JDK8、同时支持 Spring Boot 2/3。本插件 agent 若要覆盖 JDK8~26（M56 目标），这套三件套是现成答案。

## 5. 性能与安全清单（对本插件的正反两面对照）

### 5.1 反面教材（本插件应避免）

- **每个 SQL 帧现场 `MethodHandles.lookup().findVirtual()`**（`MySQLInterceptor.java:47-52`、`MySQL5Interceptor.java:21-22`、`CKInterceptor.java:51-53`），未缓存 MethodHandle——每次 execute 都做重查找 + 分配，应静态缓存。
- **耗时用 `System.currentTimeMillis()` 而非 `nanoTime`**（毫秒精度，多处）——M56 要精确耗时须用 `nanoTime`。
- **SQL 完全无脱敏**：`Reporter.reporter`（`Reporter.java:19-28`）把原始 SQL 直接发 RMI，无密码/敏感值遮蔽——M56 的脱敏需求（数据留本机 + 敏感参数默认脱敏）是 Cool Request 缺失、本插件应做足的一块。
- **`MethodEnterInterceptor` 的 traceCallback 走同步 RMI**（`MethodEnterInterceptor.java:65-66`，直接主线程 `rmiAgent.traceCallback`）——与 `Reporter` 的异步不一致，同步跨进程调用在方法出口做是高开销失误。

### 5.2 正面教材（本插件应吸收）

- **异步 + 有界队列上报**：`Reporter`（`Reporter.java:15-38`）固定 3 线程 `ThreadPoolExecutor` + `LinkedBlockingQueue`，把 RMI IO 移出业务线程；请求上下文（`Request.processRequestContext()`，`request/Request.java:9-33`）在**提交前同步采集**，避免异步后拿不到 ThreadLocal——这正是 M56「采集入队不阻塞 + requestTag 采集时机」的实证范式。
- **插桩异常全兜底**：每个 @OnMethodEnter 都 `try/catch(Throwable ignored)`（`MySQLInterceptor.java:63` 等），确保插桩代码异常不冒泡破坏业务——对应 M56「agent 内异常静默自吞、不泄漏到应用」红线。
- **自屏蔽**：`AgentRemoteServiceImpl.addTraceMethod` 拒绝 `dev.coolrequest` 前缀（:91）、`TraceHandler.shouldSkipClass` 过滤 `java./javax./sun./jdk./com.sun./net.bytebuddy./javassist.`（:257-265）——对应本插件 `CallerSourceLocator` 的框架帧过滤规则，把「不要 hook 到自己与 JDK」落到代码。
- **ThreadLocal 清理**：`TraceContext.java:26` / `RequestContextHolder.java:4` 都 `threadLocal.remove()`——防复用线程泄漏。

## 6. 精华吸收决策表（落到本插件 M56）

| # | Cool Request 精华 | 本插件处置 | 落点 |
|---|---|---|---|
| 1 | `RETRANSFORMATION` + `disableClassFormatChanges` 对已加载驱动安全 retransform | **吸收**（实证可消除 M56 最大风险） | T-M56-01 agent 基座 |
| 2 | hook execute 入口问驱动要已拼 SQL（getQuery().asSql/getSQL/toString），免 setXxx 参数还原 | **作为快速降级路径**；完整字段（参数/耗时/行数/报错）仍需 setXxx 或双层 hook | T-M56-03 及 M56 §5.3 |
| 3 | ThreadLocal「栈+树」串联 SQL→DAO→Service→Controller 调用链 | **吸收**（对应 T-M56-05/06 的 per-thread 栈设计） | T-M56-05/06 |
| 4 | agent 独立子工程 + Copy 内嵌 + 运行时 getResource 落盘 | **吸收**（与原案一致，落盘失败须显式上报——避开其静默吞错） | T-M56-01 |
| 5 | shadowJar relocate 全量字节码库到私有命名空间 + mergeServiceFiles | **吸收**（ASM 必须自行 relocate，不能复平台 ASM） | T-M56-01 build |
| 6 | 端口自报：插件分配端口下发，agent 反连报回自身端口（支持多实例） | **部分吸收**（已是 M56 loopback Socket 端口协商的既定思路；「多被调试进程 Set 管理」值得补） | T-M56-01/02 |
| 7 | 异步有界队列 + 提交前同步采 requestTag | **吸收**（实证 M56 采集时序设计） | T-M56-02/06 |
| 8 | 插桩异常全 try/catch + 自屏蔽过滤 + ThreadLocal.remove | **吸收**（M56 红线已含，补齐自屏蔽清单） | T-M56-07 |
| 9 | 元素级容错序列化（SafeObjectSerializer + TokenBuffer 草稿） | **吸收**（脱敏/序列化防御范式） | T-M56-07 |
| 10 | `toolchain` + `--release 8` + `disableAutoTargetJvm` 多 JDK 兼容 | **吸收**（agent 覆盖 JDK8~26 的编译手法） | T-M56-01 build |
| 11 | native JVMTI（IterateOverInstancesOfClass 捞实例 / GetStackTrace 抓栈） | **不吸收**（SQL 捕获不需要；仅「按运行实例定位连接」未来可选时参考 agent.c） | 不做清单 |
| 12 | 双向 RMI 通信 | **不吸收**（M56 单向事件流用 loopback Socket 更安全可控，且 RMI 零序列化防护/每次 lookup 是短板） | 不做清单 |
| 13 | premain `-javaagent` 注入（RunConfigurationExtension） | **保留为可选补位**（非 M56 主路线；未来「启动即捕获」时与 attach 互补） | 需求池 |
| 14 | setXxx 参数还原 module 现状（未实现、仅遗留空接口） | **启示**：M56 若走 setXxx 还原，难度被竞品「绕道不做」侧面佐证——优先评估 execute 入口直取 | T-M56-03 取舍 |

## 7. 对本插件 M56 结论的增量

1. **attach 路线成立且更优**：Cool Request 没用 attach 不构成反证，反而其「premain 对已启动进程失效」的刚性 + JEP 451 只影响 attach 不影响 premain 的事实，让「attach 为主、premain 补位」的组合定位更清晰。
2. **「已加载驱动类 retransform」从风险降为已验证**：`REDEFINITION/RETRANSFORMATION` + `disableClassFormatChanges` 在它的生产插件里对 MySQL8/MySQL5/PG/ClickHouse 四驱动稳定生效，可直接写进 M56 的「retransform 兼容」对策（原案此条是待验证风险）。
3. **SQL 提取有「驱动自拼 SQL」捷径**：M56 原案只写了 setXxx 跟踪一条路；Cool Request 展示了 execute 入口直取驱动的 `asSql/getSQL/toString`，可作为「只要 SQL 文本」的轻量降级，降低「参数还原」这条最重子任务的权重。
4. **工程化全套有了落地产物**：relocate 映射、agent 子工程 + Copy 内嵌、多 JDK 三件套，均从「概念」变成「可照抄的 gradle 配置」，把 M56 T-M56-01 的工程搭建成本显著降低。

## 变更记录

- 2026-09-11 立项调研：四路并行只读源码分析 Cool Request（coolrequest-vip）agent 字节码增强，产出挂载链路/增强核心/通信与 JVMTI/工程化四维结论 + 14 项精华吸收决策表；核心认知——竞品实为 premain（`RunConfigurationExtension` 注入 `-javaagent`）而非 attach、SQL 用「驱动自拼 SQL」绕开 setXxx 还原、ByteBuddy RETRANSFORMATION+disableClassFormatChanges 已实证对已加载驱动生效、relocate/多 JDK/端口自报/异步上报四套工程化手法对本插件 M56 可直接落地；native JVMTI 与双向 RMI 明确不吸收。