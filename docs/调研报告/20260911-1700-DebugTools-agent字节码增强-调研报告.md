# DebugTools（debug-tools）agent 字节码增强 深度调研报告

- **调研日期**：2026-09-11
- **调研对象**：DebugTools（作者 future0923，仓库 `F:/code/Java/Github/other/DebugTools`，HEAD `bc383709`，v5.2.0），GPL-3.0 开源 IDEA 插件 + agent 组合
- **调研动机**：为本插件 M56「增强捕获（agent 字节码通道）」立项吸收竞品精华——DebugTools 是「IDEA 插件内嵌 agent + **支持动态挂载（agentmain）** + SQL 语句/耗时捕获」的成熟开源先例，与本插件规划路线**高度同构**（这是继 Cool Request 之后第二个、也是更贴合的 agent 竞品）
- **调研方式**：只读源码分析（四路并行子智能体：动态挂载链路 / SQL 捕获实现 / 通信 server + 热重载 / 打包工程化），全部结论带 文件:行号 证据
- **关键背景**：本项目是「插件 + agent 分离」的**双仓库结构**——本仓库是 agent + server + attach 命令行（Maven 多模块），IDEA 主插件在独立仓库 `debug-tools-idea`（`debug-tools-attach/pom.xml:242,256-258` 硬编码了作者本机绝对路径 `/Users/weilai/Documents/debug-tools-idea`）。故「插件侧 AnAction 触发 attach」的代码本仓库看不到，但 attach 客户端逻辑（`debug-tools-core` 的 `DebugTools`）与 boot 引导（`debug-tools-boot`）已完整覆盖「拿 pid → attach → loadAgent」全链。
- **关联**：本插件 M56 计划 [20260911-0710-M56-增强捕获agent字节码通道master落地与竞品精华吸收-开发计划.md](../开发计划/20260911-0710-M56-增强捕获agent字节码通道master落地与竞品精华吸收-开发计划.md)；上一位竞品 Cool Request 调研 [20260911-1617-CoolRequest-agent字节码增强-调研报告.md](./20260911-1617-CoolRequest-agent字节码增强-调研报告.md)

## 0. 一句话结论

DebugTools 是本案第一个**真正落地动态挂载（attach + agentmain）**的竞品——它与本插件 M56 的路线一致（attach 热挂载 + 字节码增强 SQL 捕获 + loopback 通信），但暴露了一个**致命的半成品陷阱**：它的 `agentmain` 只起通信 server、**不注册 SQL transformer**（SQL 增强只在 premain 里），等于「动态挂载」对 SQL 捕获这条主线是失效的。它最有价值的是两块：**① SQL 捕获的驱动层「动态代理包装」+ 分库格式适配这套完整可抄的实现**；**② attach 参数自省编解码、JDK 版本错配容忍、通信协议三级命令路由这套工程化骨架**——这些可直接喂给 M56 的 T-M56-01/03。

## 1. 动态挂载链路：premain + agentmain 双入口（atio 已落地，但有陷阱）

### 1.1 双入口同类 + manifest 声明

| 项 | 值 | 证据 |
|---|---|---|
| Premain-Class / Agent-Class | 同一个类 `io.github.future0923.debug.tools.attach.DebugToolsAttach` | `debug-tools-attach/pom.xml:195-200` |
| Can-Redefine / Can-Retransform | 均 true | `debug-tools-attach/pom.xml:197-200` |
| premain 实现 | 全功能：tools.jar、JNI、extension jar、**SQL transformer**、hotswap、server、autoAttach | `DebugToolsAttach.java:58-85` |
| agentmain 实现 | **仅** `createExtensionJar()` + `startServer()` | `DebugToolsAttach.java:94-97` |

**核心陷阱（本次调研最重要的发现）**：`agentmain` 里**没有调用 `SqlPrintByteCodeEnhance.enhance(inst, args)`**（这个调用只在 premain 的 `:74`）。`enhance`（`SqlPrintByteCodeEnhance.java:38-49`）才是注册 `SqlDriverClassFileTransformer`（`inst.addTransformer(..., true)`）的地方。后果：**attach 进去的 agent 只起 server，不对已加载的 JDBC Driver 生效**——所谓「动态挂载后打印 SQL」只有在你用 `-javaagent` premain 启动时才成立，hot attach 的用户拿不到 SQL 捕获。

对本插件 M56 的直接启示：**M56 若要「attach 后立即捕获 SQL」，agentmain 里必须显式 `inst.addTransformer(...)` + 对已加载驱动类 `retransformClasses`**。这是 M56 原案「attach 热挂载即时生效」最容易踩空的一环，DebugTools 已经用真实代码踩过了。

### 1.2 插件侧 attach 调用链（`debug-tools-core` `DebugTools` + `debug-tools-boot`）

| 环节 | 位置 | 说明 |
|---|---|---|
| 枚举候选 JVM | `DebugTools.java:61-68` | `VirtualMachine.list()` 按 pid 精确匹配 `descriptor.id()`，匹配不到退回 `VirtualMachine.attach(pid)` |
| JDK 版本检查 | `DebugTools.java:76-86` | 取目标 `getSystemProperties()` 比对，不一致仅 `warn` 不阻断 |
| loadAgent | `DebugTools.java:87-92` | `loadAgent(agentPath, agentArgs.format())` |
| **JDK 版本错配容忍（亮点）** | `DebugTools.java:93-112` | `IOException` 含 `"Non-numeric value found"`（低版本 attach 高版本）、`AgentLoadException("0")`（高版本 attach 低版本）两个魔数**视为「attach 可能仍成功」继续连接**，`finally` 里 `detach()` |
| attach 从 IDE 剥离到子 JVM | `DebugToolsBootstrap.java:98-122` | JDK<9 加 `-Xbootclasspath/a:tools.jar`，fork `java -jar core.jar --pid ...` 子进程跑 attach |

**亮点：attach 逻辑剥离到独立 boot/core 子 JVM**——attach 时用的 JDK 可不同于 IDE 运行的 JDK，绕开「IDE 自带 JRE 无 attach 模块」问题。这与本插件「在插件进程内直接反射 attach」的做法互补：M56 若遇到平台 JDK 不带 attach 的边界，可参考其「子进程 attach + 结果回传」。

### 1.3 attach 参数契约（可直接复用的自省式编解码）

`AgentArgs.java` 用「反射把字段序列化成 `k1=v1,k2=v2`、逗号分隔」的自省式编解码：
- 输出 `AgentArgs.format()`（`:205-207`）→ 反射遍历字段拼字符串，跳过 logger 与静态常量；
- 输入 `AgentArgs.parse()`（`:141-166`）→ `split(",")` + `split("=")` 反射回填，Integer/enum 特殊处理，解析失败 `catch (Exception ignored)`；
- 字段全集（`:44-131`）：`tcpPort`/`httpPort`/`printSql`/`traceSql`/`hotswap`/`server`/`applicationName`/`projectName`/`projectPathHash`/`autoAttach`/`autoSaveSql`/`sqlRetentionDays`/`disabledPlugins`（`#` 分隔）等；
- 外部配置兜底 `loadConfigurationFile()`（`:212-241`）：`debug-tools-agent.properties` 优先级**低于** agent args（仅字段为空才取）。

这套「反射自省 + 逗号 k=v + 配置文件兜底」的 attach 参数契约，是 M56 可直接搬的轻量方案——新增参数零协议改动，比手写硬编码 args 字符串安全得多。

### 1.4 远程 attach 的真相（产品话术 vs 工程实现）

README 说「本地和远程都支持」，但**全库无 SSH/隧道/jsch/跨主机 AttachProvider**。实际是：**本地 attach 完成后，目标 JVM 开放 Netty TCP(12345) + HTTP(22222) 端口绑定 `0.0.0.0`**，IDE 通过网络直连这些端口下发命令，「远程」= 网络可达的端口，而非跨主机 attach。「配合远程 Debug」用 JPDA（`HotSwapperJpda.java:64-96`，`com.sun.jdi.connect.AttachingConnector` + `SocketAttach`），要求目标带 `-agentlib:jdwp`。**本插件不做远程，与此无关**，但印证了 M56「远程场景自动回落控制台桥」的判断方向正确。

## 2. SQL 捕获实现：驱动层「动态代理包装」+ 分库格式适配（可直接抄）

### 2.1 插桩策略：最小侵入的「动态代理包装」，非「逐方法 hook」

DebugTools 的 SQL 捕获**只 hook 一个点**——JDBC 驱动的 `Driver.connect(String, Properties)`（`SqlDriverClassFileTransformer.java:50-51,62-68`），在 `connect` 方法 `insertAfter` 注入代码，把返回的 `Connection` 用动态代理包装返回。之后：
- `Connection` 代理的 `prepareStatement`/`createStatement`/`prepareCall` 再把返回的 `Statement`/`PreparedStatement`/`CallableStatement` 包装成代理（`SqlPrintInterceptor.java:61-63,147-152`）；
- `PreparedStatement` 代理的 `setXxx`（含 `setNull`）记录参数（`:186-195`）、`execute` 系列触发打印并清理（`:241`）。

**这是比 M56 原案「hook 6~10 个固定方法」更优的思路**：只 hook 一次 `connect`，往后所有拿到该 Connection 的代码路径（含连接池复用）自动覆盖，无需对每个 setXxx/execute 方法签名做脆弱的匹配。**注意它完全不 hook MyBatis 的 StatementHandler/Executor/BoundSql**——纯驱动层兜底，MyBatis 层只留给「方法追踪」功能。

### 2.2 字节码工具分工（两库：Javassist 改驱动 + ByteBuddy 追踪）

| 库 | 用途 | 位置 |
|---|---|---|
| Javassist 3.30.2-GA | SQL 捕获：`ClassPool`/`CtClass` 改 `Driver.connect`；承插 `ClassFileTransformer` | `SqlDriverClassFileTransformer.java:20-22,47-53` |
| ByteBuddy | 方法追踪（含 MyBatis `MapperProxy.invoke`）：`AgentBuilder` + `Advice` + `REDEFINITION`+`disableClassFormatChanges` | `TraceMethodClassFileTransformer.java:38-41,88-93,322-328` |
| JNI/JVMTI（自研 C++） | 实例枚举/GC/对象大小（`VmTool`），**非**热替换 | `debug-tools-vm` |

**两条对 M56 都有价值的结论**：① SQL 捕获用 Javassist 改单方法就够了，不必上 ByteBuddy/ASM 全家桶——但 M56 选裸 ASM（无关需要 ByteBuddy 高层的 Advice），这个「轻量改一个方法」的思路同样适用于 ASM 的 `ClassVisitor`；② 字节码库必须 relocate（见 §4）。

### 2.3 参数还原：setXxx 实参 + 分库反射驱动自带 SQL，双路兜底

这是 SQL 捕获最复杂的部分，DebugTools 用「策略枚举 + 反射」消解：

- **setXxx 实参跟踪**：`StatementHandler.java:164` 每 proxy 维护 `List<Object> parameters`，`startsWith("set")` 宽泛匹配记 `args[1]`（`:191-195`），`setNull` 记 `"NULL"` 标记（`:186-190`）——**注意这是坑（见 §5.1）**：`setFetchSize`/`setMaxRows`/`setQueryTimeout` 等非参数 setter 会被误记。
- **分库 SqlFormat**：`DataSourceDriverClassEnum` 给 9 类驱动各配一条 `SqlFormat` 策略：
  - MySQL：`sta.toString()`（驱动已内联参数，只清洗 `** BYTE ARRAY DATA **`）——`DataSourceDriverClassEnum.java:34-45`
  - PostgreSQL/Kingbase：`formatStringSql(sta.toString(), parameters)`——SQL 模板 + set 实参逐 `?` 回填（`:51-64,161-187`）
  - SQLServer：反射读内部字段 `inOutParam`（逐元素 `getSetterValue`）+ `userSQL`（`:83-95`）
  - Oracle：反射 `preparedStatement.sqlObject`（`:108-115`）
  - 达梦 DM：反射 `rpstmt.originalSql`/`nativeSql`（`:120-133`）
  - ClickHouse：`sta.toString()`（`:99-103`）
  - GBase 8s：专类 `GbaseSqlSupport.extractSql` 处理字段跨版本漂移（`:74-78`）
- **回填类型判断**：`formatStringSql`（`:161-187`）遇 `?` 依 `parameters[currentParameter]`：`"NULL"`→NULL、`String`/日期类→加引号、其它→`Convert.toStr` 原样（数值无引号）。

**对本插件 M56 的价值**：这就是「通用 JDBC 捕获必须面对的现实复杂度」的完整样本——达梦/人大金仓/GBASE 的字段漂移、MySQL 依赖 toString 的脆弱性、分库反射兜底，全在这里踩过并给出解法。M56 的「参数实参直取」目标是**更优**的（hook setXxx 拿到真实类型，不靠反射驱动内部字段），但「分库格式适配枚举」这层在驱动矩阵上仍不可省，可直接借鉴其组织方式。

### 2.4 代理类反向识别真实业务包（MyBatis 场景的关键）

`getPackageNameAndProxy`（`SqlPrintInterceptor.java:315-347`）读调用栈，遇到 `jdk.proxy$...`/`com.sun.proxy.$Proxy` 时用 JNI `JvmToolsUtils.getInstances` 找代理实例 + `Proxy.getInvocationHandler` 识别是 MyBatis（`MapperProxy`/`MybatisMapperProxy` 反射读 `mapperInterface`）还是 JPA（`JdkDynamicAopProxy` 读 `proxiedInterfaces`），把 SQL 归属到真实 mapper 接口包名，用于 `sql.print.packages`/`ignore-packages` 过滤。否则捕获栈全是被代理类，过滤/归属无从下手。**这是 M56 做「调用方归属/按包过滤」时必须面对的点**——M56 用 agent 在应用线程内拿真实栈帧，比「JNI 反查代理」更干净，但「识别 MyBatis/JPA 代理还原业务包」这个思路不变。

### 2.5 数据回传：控制台 stdout + 本地文件 + 查询型 HTTP（无事件推送）

这是 DebugTools 与 M56 的**根本定位差异**：它**不是 IDEA 内实时 SQL 面板**（本仓库无任何 plugin.xml/toolWindow），SQL 回传走三条旁路：
1. 控制台 `System.out`（`LoggerHandler.java:47-52`，`infoForce` 绕过日志级别）；
2. 本地文件 `~/.debugTools/sql/...`（`SqlFileWriter.java:48-86`，格式 `-- 时间 | dbType | Xms \n SQL`）；
3. HTTP 查询接口（`/getPrintSqlType`、`/changePrintSqlType`，`DebugToolsHttpServer.java`）。

**结论**：M56 要的「loopback Socket 事件流 → SqlLogEntryBuffer 实时面板」，DebugTools 没有，需 M56 自建。但这反证了 M56 的捕获通道定位（结构化事件直取）比 DebugTools「文件/控制台轮询」高一代。

## 3. 通信 server 骨架（M56 可借鉴的协议设计）

### 3.1 双 server：Netty TCP（命令通道）+ JDK HttpServer（元数据）

| server | 技术 | 用途 |
|---|---|---|
| TCP | Netty `ServerBootstrap`，前缀 `IdleStateHandler(120s)` + `LengthFieldBasedFrameDecoder` | 双向命令通道（方法调用/热部署/追踪开关/trace） |
| HTTP | JDK 内置 `com.sun.net.httpserver.HttpServer` | 查询类只读接口（结果详情/classloader/切 SQL 开关/Spring 就绪） |

### 3.2 协议：二进制头 + 命令 byte 三级路由

帧格式（`PacketFrameDecoder.java:27-38`）：`magic(4)+version(1)+serializerAlg(1)+command(1)+resultFlag(1)+bodyLen(4)+body`，body 部分 packet 内嵌 JSON（`RunTargetMethodRequestPacket.java:54-76`）。三级分发：`Command`（byte 常量）→ `PacketCodecNetty`（command→Packet 类）→ `ServerPacketDispatcher`（`Class→Handler` 的 ConcurrentHashMap）。命令清单含 HEARTBEAT/RUN_TARGET_METHOD/RUN_GROOVY_SCRIPT/热部署 CHANGE_TRACE_METHOD/RECOVER_* 等 17 类。

**对本插件 M56 的借鉴**（M56 用裸 Socket 也适用）：
- 「固定头 magic+len+command → 按 command 找 handler」优于裸 JSON 行协议，扩展性强；
- **IO 线程与业务线程分离**：`channelRead0` 收到即 `bizPool.submit`（`ServerDispatchHandler.java:56-66`），bizPool 有界队列 + `CallerRunsPolicy` 反压（`DebugToolsTcpServer.java:74-88`）——对应 M56「采集不阻塞」红线；
- **端口前置协商**：IDE 先 `getAvailablePort()` 算好空闲端口，用 `loadAgent(agent, "port=xxx")` 参数字符串传给 agent（`DebugTools.java:90-92`），而非 attach 返回值回传——M56 已是此思路，DebugTools 提供了落地写法。

## 4. 打包工程化（与 Cool Request 几乎同构，可互相印证）

### 4.1 模块结构（10 模块，agent 全体 = debug-tools-attach）

`base`（公共底层）→ `common`（协议）/`vm`（JNI）/`hotswap-core`+14 插件/`core`（attach CLI）→ `server`（netty+http+groovy+bytebuddy+动态编译）→ `sql`/`extension`×6 → `attach`（agent 全体 fat jar）；`boot`（本地 attach 引导器，assembly jar-with-dependencies）。

### 4.2 agent 打包：shade 单 fat jar + 全量 relocate（正反两面对照）

`debug-tools-attach/pom.xml`：`maven-shade-plugin`，`<finalName>debug-tools-agent</finalName>`，`shadedArtifactAttached=false`。relocate 到统一前缀 `io.github.future0923.debug.tools.dependencies`（根 `pom.xml:49`）：`javassist`/`net.bytebuddy`/`io.netty`/`org.apache.commons`/`pl.joegreen.lambdaFromString` 五组全 relocate（`:204-225`）。编译 target 锁死 Java 8（`:175-176`）。manifestEntries 声明 Premain/Agent-Class + Can-Redefine/Can-Retransform（`:195-201`）。

**这印证了 Cool Request 调研的同一结论**：agent 打进用户 JVM，字节码库必须 relocate 到私有命名空间，否则撞类。M56 的 ASM relocate 判断再次得到第二例实证（且 DebugTools 连 Netty 都 relocate，说明「能 relocate 就 relocate」是 agent 铁律）。

### 4.3 agent jar 进分发包 + 运行时落盘

- 打包期 maven-antrun 把 shade 产物 copy 到**三处**：① IDEA 插件仓库 `src/main/resources/lib/`（**硬编码作者本机绝对路径 `/Users/weilai/Documents/...`，典型双仓单机脆弱点**）、② boot 的 resources/lib、③ dist/；extension 各子模块也各自 shade 后 copy 进 attach 的 `resources/lib/`。
- 运行时 `DebugToolsFileUtils.getLibResourceJar()`（`:48-64`）从 classpath `lib/<name>.jar` 读到临时目录 `~/.debugTools/lib`（`DEBUG_TOOLS_HOME_DIR` 可覆盖），再交给子 JVM / `System.load`。

**正面对照**：这套「构建期 copy 进 resources → 运行时 getResource 解压落盘」就是 M56 agent jar 落盘的标准范式。**反面教训**：硬编码绝对路径 + `.gitignore` 忽略 `*.jar`（产物不入库），M56 应避免（用 `${basedir}` 相对路径 + 明确产物位置）。

### 4.4 JDK 兼容

全局 target Java 8（根 `pom.xml:46-47`），无 module-info、无 `--release`；多 JDK 靠三类手段：① `maven-jdk-tools-wrapper`（`com.github.olivergondza`，`core/pom.xml:25-30`，`provided` 依赖屏蔽 JDK11+ 移出 tools.jar 的 `jdk.attach` 差异）；② `mac-tools` profile 兜老 JDK；③ 运行时反射 `addURL` 载 tools.jar + `-Xbootclasspath/a`。**M56 的「反射 attach + platformClassLoader」+ 「JDK8~26 矩阵」可对比借鉴其 `jdk-tools-wrapper` 手法**。

## 5. 性能与安全清单（正反对照）

### 5.1 反面教材（M56 应避免）

- **`startsWith("set")` 宽泛匹配**（`SqlPrintInterceptor.java:191`）：`setFetchSize`/`setMaxRows`/`setQueryTimeout` 被误记成占位符参数致 `?` 回填错位——应显式枚举 `setXxx` 条。
- **耗时 `currentTimeMillis`**（`:176,185`）：毫秒粒度——M56 精耗须 `nanoTime`。
- **每次 execute 都 `getStackTrace` + 可能触发 JNI `getInstances`**（`:349` + `JvmToolsUtils` native 遍历堆）：明显的性能热点——M56 应栈过滤缓存/短路、避免 per-execute JNI。
- **无值级脱敏**（只有包/语句级 regex 黑白名单 `DebugToolsIgnoreSqlUtils`）——M56 脱敏需求需自建。
- **历史存储无上限**（`DebugToolsResultUtils.CACHE` 永不淘汰 ConcurrentHashMap + SQL 文件按天无限追加）——M56 buffer 已有 200 条上限，不踩这个坑。
- **每次 prepareStatement 都 new 代理**（`SqlPrintInterceptor.java:146-152`）：无对象复用，高频 GC 压力。

### 5.2 正面教材（M56 应吸收）

- **classloader 可见性处理**（最关键）：注入到驱动字节码里的代码写死 `ClassLoader.getSystemClassLoader().loadClass("...SqlPrintInterceptor")`（`SqlDriverClassFileTransformer.java:62-69`），并配 `HiddenAgentClassLoader` 单测锁死「驱动 loader 看不见 agent 类时回退 system loader」契约（`SqlDriverClassFileTransformerTest.java:72-96`）。**这是 M56 最容易翻车的点**，应直接复用其「回调类 system classloader 兜底 + 针对性单测」。
- **线程安全 + 资源清理**：`MethodTrace` 全 ThreadLocal 且 `remove()`（`:43-53`）、文件写用 `ReentrantLock`（`SqlFileWriter.java:43`）、`volatile` 状态位。
- **纯常量类隔离避免 ClassFileTransformer 死锁**：`JdbcDriverClasses` 刻意不含外部库依赖（`:19-27` 注释），防 transformer 上下文触发额外类加载死锁——M56 的 transformer 注入代码同理应纯净。

## 6. 精华吸收决策表（落到 M56）

| # | DebugTools 精华 | 本插件处置 | 落点 |
|---|---|---|---|
| 1 | **attach + agentmain 动态挂载已落地**（双入口同类 + Can-Redefine/Retransform） | **吸收**（印证 M56 attach 路线）；并吸收其教训：agentmain 必须显式注册 SQL transformer + retransform | T-M56-01 |
| 2 | **「动态代理包装 Connection」替代「逐方法 hook」**（只 hook Driver.connect，往后全自动覆盖） | **高价值吸收**——比 M56 原案「hook 6~10 个固定方法」更鲁棒，含连接池复用路径 | T-M56-03（§5.3 补） |
| 3 | **分库 SqlFormat 策略枚举 + 反射驱动字段兜底**（9 类驱动含达梦/GBASE/KBase） | **吸收**——驱动兼容矩阵的组织方式，驱动自带 SQL vs setXxx 实参双路 | T-M56-03 |
| 4 | **代理类反向识别真实业务包**（getPackageNameAndProxy 还原 MyBatis/JPA mapper 包名） | **吸收**（M56 用真实栈帧更优，但「还原代理业务归属」思路不变） | T-M56-03/05 |
| 5 | **attach 参数自省式编解码「k=v,逗号」+ 配置文件兜底** | **吸收**（替代 M56 手写 args 字符串） | T-M56-01 |
| 6 | **JDK 版本错配容忍**（两个魔数视为「可能仍成功」继续连接） | **吸收**（hot attach 产品化的关键容错） | T-M56-01 |
| 7 | **协议「固定头+command 三级路由」+ IO/业务线程分离 + 有界队列反压** | **吸收**（M56 裸 Socket 也沿用其结构） | T-M56-02 |
| 8 | **端口「前置协商 + agent 参数注入」** | **吸收**（M56 已是此思路，落地写法对齐） | T-M56-01/02 |
| 9 | **字节码库全量 relocate + Java8 target + jdk-tools-wrapper** | **吸收**（M56 ASM relocate 的第二例实证） | T-M56-01 build |
| 10 | **回调类 system classloader 兜底 + HiddenAgentClassLoader 单测** | **吸收**（最易翻车点，直接复用） | T-M56-01 |
| 11 | attach 逻辑剥离到独立 core/boot 子 JVM（绕 IDE JRE 无 attach 模块） | **可选补位**（M56 主路径插件内反射 attach，遇平台边界时参考） | 需求池 |
| 12 | Netty 全家桶 + groovy 内嵌 + 自研 JVMTI + hotswap 多步 redefine 状态机 | **不吸收**（M56 只需单向 SQL 事件流，裸 Socket 更轻；hotswap 超出 SQL 捕获范围） | 不做清单 |
| 13 | 「远程」= 本地 attach + 目标开 0.0.0.0 端口 | **不吸收**（M56 不做远程，远程回落控制台桥） | 不做清单 |

## 7. 对本插件 M56 结论的增量

1. **attach 动态挂载从「本机探针验证」升到「竞品生产落地」**：DebugTools 用 `VirtualMachine.attach` + `agentmain` 双入口真实跑在生产，M56 的 attach 路线得到第二例（且是更贴合的）实证。
2. **抓到 M56 最容易踩空的陷阱**：agentmain 不注册 transformer 就「动态挂载失效」——M56 的 agentmain 必须 `inst.addTransformer(...)` + `retransformClasses` 已加载驱动，这条要写进 T-M56-01 的硬性验收。
3. **「动态代理包装 Connection」比 M56 原案「hook 6~10 个固定方法」更优**：只改一个 `Driver.connect`，全连接池路径自动覆盖，显著降低插桩脆弱面与字节码工作量，建议 M56 §5.3 采纳为主路径、原方案降为补充。
4. **驱动兼容矩阵有了完整样本**：9 类驱动（含达梦/GBASE/KBase）的分库适配策略 + 字段漂移坑，是 M56「JDBC 驱动兼容矩阵」风险对对策表的现成素材。
5. **通信协议骨架可选裸 Socket 复刻**：M56 不必上 Netty，但「固定头+command 路由+IO/业务线程分离+有界队列」这套结构，裸 Socket 同样该有。

## 变更记录

- 2026-09-11 立项调研：四路并行只读源码分析 DebugTools（debug-tools v5.2.0）agent 字节码增强，产出动态挂载/SQL 捕获/通信 server+热重载/打包工程化四维结论 + 13 项精华吸收决策表；核心认知——竞品确已落地 attach+agentmain 动态挂载（双入口同类），但 agentmain 只起 server 不注册 SQL transformer（SQL 增强仅 premain），等于动态挂载对 SQL 捕获失效（M56 反面教材）；SQL 捕获用「动态代理包装 Driver.connect」替代逐方法 hook + 9 类驱动分库适配（Javassist 改单方法）+ 代理类反向识别业务包（system classloader 兜底回调类）；通信用 Netty TCP 三级命令路由 + JDK HttpServer 元数据 + 端口前置协商；打包 shade 全量 relocate 五组依赖 + Java8 target；13 项吸收/不作清单落到 M56 T-M56-01/02/03。