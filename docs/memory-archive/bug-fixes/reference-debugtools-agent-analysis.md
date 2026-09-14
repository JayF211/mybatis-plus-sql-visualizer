---
name: reference-debugtools-agent-analysis
description: DebugTools（debug-tools v5.2.0）agent 字节码增强竞品深度调研（2026-09-11，四路子智能体只读源码，报告 20260911-1700 已入 docs/开发计划）——第一个真正落地 attach+agentmain 动态挂载的竞品，但 agentmain 只起 server 不注册 SQL transformer（动态挂载对 SQL 捕获失效的反面教材）；SQL 捕获用「动态代理包装 Driver.connect」替代逐方法 hook + 9 类驱动分库适配
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_be651068-5325-46ff-b3ec-329c7fe950de
---

# DebugTools（debug-tools）agent 字节码增强竞品调研（2026-09-11）

报告：`docs/开发计划/20260911-1700-DebugTools-agent字节码增强-调研报告.md`（master 未提交）。仓库 `F:/code/Java/Github/other/DebugTools`（GPL-3.0，v5.2.0，HEAD bc383709），作者 future0923。四路并行源码分析，结论带 文件:行号 证据。是继 [[reference-coolrequest-agent-analysis]] 之后第二个（更贴合的）agent 竞品，对 [[project-m56-agent-bytecode]] M56 立项的吸收来源。

## 关键背景

本项目是「插件 + agent 分离」双仓库结构——本仓库只有 agent+server+attach 命令行（Maven 10 模块），IDEA 主插件在独立仓库 debug-tools-idea（`debug-tools-attach/pom.xml:242,256-258` 硬编码作者本机 `/Users/weilai/Documents/...`）。故「插件侧 AnAction 触发」看不到，但 attach 客户端（debug-tools-core 的 `DebugTools`）与 boot 引导完整覆盖「拿 pid→attach→loadAgent」。

## 最关键认知（修正/补强 M56）

1. **第一个真正落地动态挂载的竞品**：`VirtualMachine.attach` + `agentmain` 双入口落地（`DebugToolsAttach` 同一类，manifest `debug-tools-attach/pom.xml:195-200` 声明 Premain/Agent-Class + Can-Redefine/Can-Retransform）。attach 调用链：`DebugTools.main`（`core/DebugTools.java:40-118`）`VirtualMachine.list()` 按 pid 匹配→`attach`→版本比对只 warn→`loadAgent(agentPath, args.format())`。

2. **致命半成品陷阱（M56 最该抄的教训）**：`agentmain`（`DebugToolsAttach.java:94-97`）只做 `createExtensionJar()`+`startServer()`，**不调用 `SqlPrintByteCodeEnhance.enhance(inst,args)`**（该调用只在 premain :74；enhance 才是 `inst.addTransformer(..., true)` 注册 SQL transformer 处）。后果=attach 进去的 agent 不起 SQL 捕获，只有 `-javaagent` premain 启动才生效。**M56 的 agentmain 必须显式 addTransformer + 对已加载驱动 retransformClasses**，写进 T-M56-01 硬性验收。

3. **「动态代理包装 Connection」比 M56 原案「hook 6~10 个固定方法」更优**：只 hook 一个点 `Driver.connect(String,Properties)`（`SqlDriverClassFileTransformer.java:50-51,62-68`，insertAfter 注入包装返回 Connection）→ Connection 代理的 prepareStatement/createStatement/prepareCall 再包装 Statement → PreparedStatement 代理的 setXxx 记参数、execute 触发打印。全连接池复用路径自动覆盖，无逐方法脆弱匹配。建议 M56 §5.3 采纳主路径、原逐方法方案降补充。

4. **9 类驱动分库适配**（`DataSourceDriverClassEnum` 策略枚举 + 反射驱动内部字段）：MySQL `toString()`、PG/KBase 模板+setXxx 实参逐 `?` 回填（`formatStringSql` :161-187）、SQLServer 反射 `inOutParam`/`userSQL`、Oracle `sqlObject`、达梦 `rpstmt.originalSql`、GBase 专类 `GbaseSqlSupport` 处理字段漂移。是 M56「JDBC 驱动兼容矩阵」风险的现成素材。

5. **代理类反向识别真实业务包**（`getPackageNameAndProxy` SqlPrintInterceptor.java:315-347）：遇 `jdk.proxy.$Proxy` 用 JNI getInstances+`Proxy.getInvocationHandler` 复盘 MyBatis `MapperProxy`/JPA `JdkDynamicAopProxy` 的真实 mapper 包名，否则过滤/归属无从下手。M56 用真实栈帧更干净但思路不变。

## 可照搬工程化骨架

- **attach 参数自省式编解码**（`AgentArgs.format/parse` :141-207）：反射字段拼 `k1=v1,k2=v2` 逗号分隔，新增参数零协议改动；`loadConfigurationFile`（:212-241）`debug-tools-agent.properties` 兜底且优先级低于 args。
- **JDK 版本错配容忍**（`DebugTools.java:93-112`）：`IOException` 含 `Non-numeric value found`（低 attach 高）与 `AgentLoadException("0")`（高 attach 低）两魔数视为「可能仍成功」继续连接，`finally detach()`——hot attach 产品化关键容错。
- **协议「固定头+command 三级路由」**（`PacketFrameDecoder.java:27-38` magic+len+command → `PacketCodecNetty` command→Packet → `ServerPacketDispatcher` Class→Handler）+ **IO/业务线程分离**（`ServerDispatchHandler.java:56-66` bizPool 有界队列 CallerRunsPolicy 反压）——M56 裸 Socket 也沿用此结构。
- **端口前置协商**：IDE 先 `getAvailablePort()` 算好空闲端口，经 `loadAgent(agent, "port=xxx")` 参数注入（`DebugTools.java:90-92`），非 attach 返回值回传。
- **回调类 classloader 可见性**（最易翻车点）：注入驱动字节码写死 `ClassLoader.getSystemClassLoader().loadClass("...SqlPrintInterceptor")`（`SqlDriverClassFileTransformer.java:62-69`）+ `HiddenAgentClassLoader` 单测锁死契约（`.java:72-96`）。M56 直接复用。
- **字节码库全量 relocate**（`debug-tools-attach/pom.xml:204-225`）：javassist/bytebuddy/io.netty/org.apache.commons/lambdaFromString 五组 relocate 到 `io.github.future0923.debug.tools.dependencies`——M56 ASM relocate 的第二例实证。
- **agent 落盘范式**：antrun build 期 copy 进 `resources/lib/` → 运行时 `DebugToolsFileUtils.getLibResourceJar`（:48-64）getResource 落盘 `~/.debugTools/lib`。反面=antrun 硬编码作者绝对路径 + `.gitignore` 忽略 `*.jar`（产物不入库）。

## 明确不吸收

- **Netty 全家桶 + groovy 内嵌 + 自研 JVMTI（VmTool.cpp）+ hotswap 多步 redefine 状态机**：M56 只需单向 SQL 事件流，裸 Socket 更轻；hotswap 超出 SQL 捕获范围。
- **「远程」= 本地 attach + 目标开 0.0.0.0 端口**（Netty TCP 12345 + HTTP 22222 bind 不指定 host）：M56 不做远程，远程回落控制台桥。

## 反面教材（M56 应避免）

`startsWith("set")` 宽泛匹配误记 setFetchSize/setMaxRows 致 `?` 回填错位（应显式枚举 setXxx）；耗时用 currentTimeMillis（应 nanoTime）；每次 execute getStackTrace + 可能 JNI getInstances 遍历堆是热点；无值级脱敏；历史无上限（永不淘汰 ConcurrentHashMap + SQL 文件按天无限追加）；每次 prepareStatement 都 new 代理无复用。

## 与 M56 增量

attach 动态挂载从「本机探针」升到「竞品生产落地」；抓到 agentmain 不注册 transformer 的陷阱；「动态代理包装」替代逐方法 hook 降字节码工作量；驱动兼容 9 类样本 + 通信协议骨架可裸 Socket 复刻。相关三竞品线：[[reference-coolrequest-agent-analysis]]（premain 反证）、MBCHP（见 [[t-jdbcmultiline-plan]]）。