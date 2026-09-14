---
name: reference-coolrequest-agent-analysis
description: Cool Request（coolrequest-vip）agent
  字节码增强竞品深度调研（2026-09-11，四路子智能体只读源码，报告 20260911-1617 已入 docs/开发计划）——关键认知=竞品实为
  premain 非 attach、SQL 用「驱动自拼 SQL」绕开 setXxx 还原、ByteBuddy
  RETRANSFORMATION+disableClassFormatChanges 实证已加载驱动可插桩；relocate/多
  JDK/端口自报/异步上报四套工程化手法可落 M56
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_be651068-5325-46ff-b3ec-329c7fe950de
---

# Cool Request（coolrequest-vip）agent 字节码增强竞品调研（2026-09-11）

报告：`docs/开发计划/20260911-1617-CoolRequest-agent字节码增强-调研报告.md`（master 未提交）。仓库 `F:/code/Java/Github/other/houxinlin/coolrequest-vip`（GPL-3.0，HEAD 9dd3ce95）；公开发布源 = github.com/houxinlin/cool-request（Marketplace plugin/23555 官方登记，2026-09-11 经 API 核验）。泳式四路并行源码分析，结论带 文件:行号 证据。是对 [[project-m56-agent-bytecode]] M56 立项的竞品吸收来源。

## 最关键认知（修正 M56 假设）

1. **竞品根本没用 attach**——全仓零命中 `com.sun.tools.attach`/`VirtualMachine.attach`/`loadAgent`/`getNativePid`。它走 `-javaagent` premain：`CoolRequestRunConfigurationExtension.updateJavaParameters`（:20-39，plugin.xml:140 注册）在用户 Run/Debug 启动前把 `-javaagent:` 塞进 VM 参数，对已启动进程失效。
2. **agentmain 形同虚设（反面坑）**：v2 `AgentBootstrap.java` 只有 premain（:71-73）**没有 agentmain 方法**，却声明了 `Agent-Class`（build.gradle:60/86）——对 v2 hot attach 会静默失败。若本插件同时支持 premain+attach，务必保证 Agent-Class 指向的类真有 `agentmain(String, Instrumentation)`。
3. **SQL 提取有捷径（降低 M56 参数还原权重）**：竞品没做 setXxx 参数还原（trace 包内搜 setXxx 零命中，ParameterConverter 是 19 行无人引用遗留），而是 hook 驱动 execute 入口直接问驱动要已拼 SQL——MySQL8 `ClientPreparedStatement.executeInternal`→`getQuery().asSql()`、MySQL5 `PreparedStatement.asSql()`、PG `PgPreparedStatement.executeWithFlags`→`toString()`、ClickHouse `getInputStream`→`args[0].getSQL()`（Hooks.java:26-72）。「只要 SQL 文本」可用作轻量降级，但拿到的 SQL 是否含真实值取决于驱动实现，且其 MethodHandle 每帧现查是性能坏习惯。
4. **「已加载驱动类 retransform」从风险降为已验证**：竞品用 ByteBuddy `AgentBuilder.Default(...TypeValidation.DISABLED)` + `RedefinitionStrategy.RETRANSFORMATION` + `disableClassFormatChanges()` 对 MySQL8/MySQL5/PG/ClickHouse 四类驱动生产稳定插桩。

## 工程化四件套（M56 可照搬）

- **agent 独立子工程 + Copy 内嵌**：shadowJar 出 fat jar → `copyExternalJar`（build.gradle.kts:34-59）复制到 `src/main/resources/lib/` → 运行时 `ResourceDecompressor.copyData`（:104-125）`getResource("/lib/...")` 落盘 `~/.config/.cool-request/request/lib/`。**反面教训**：其落盘失败 `catch(IOException ignored)` 静默吞——本插件须显式上报。
- **relocate 全量字节码库**（build.gradle:42-48）：`relocate("net.bytebuddy","dev.coolrequest.agent.lib.net.coolbytebuddy")`/`javassist`/`jackson` + `mergeServiceFiles()`。**决定性结论**：agent 跑用户 JVM 必须自行 shade+relocate，不能复用 IDE 平台的 `org.jetbrains.org.objectweb.asm`（那是 IDE 进程内的事）——印证 M56 原案「ASM 须 shade」。
- **多 JDK 三件套**（jakarta/build.gradle:14-27）：`toolchain(JDK17)` 编译 + `options.release.set(8)` 产物压回 Java8 字节码 + `disableAutoTargetJvm()` 阻止 Gradle 自动按 toolchain 抬 target（所有模块防御性调用）。实现「高版本编译、低版本字节码产物」，agent 跑 JDK8 同时支持 Boot2/3，M56 覆盖 JDK8~26 的现成答案。
- **端口自报 + 异步上报**：插件 `NetUtils.findAvailableSocketPort()` 分配端口经 `-Dcool.request.port` 下发 → agent 起自己 RMI 端口后 `agentStartup(agentPort)` 反连报回 → 插件 `Set<Integer>` 管理多实例（天然支持多被调试进程）。`Reporter`（:15-38）固定 3 线程 `ThreadPoolExecutor` 把 RMI IO 移出业务线程，requestTag 在提交前同步采（Request.processRequestContext）防异步后拿不到 ThreadLocal。

## 明确不吸收

- **native JVMTI**（`InstanceAgent` native + `src/main/jni/agent.c` 的 `IterateOverInstancesOfClass`+`GetObjectsWithTags` 捞实例、`hook.c` 的 `GetStackTrace` 抓栈）——是竞品「绕过 HTTP 反射调 Controller」特色功能地基，SQL 捕获不需要。仅未来「按运行实例定位 DataSource」才参考其 `identityHashCode` 跨进程实例标识（ObjectInstance.java:94-106）。
- **双向 RMI**：零序列化防护（全仓无 ObjectInputFilter/registry 鉴权，裸反序列化有注入风险）+ `Reporter` 每次上报都 new registry+lookup（:22）是短板——本插件 loopback Socket 单向事件流更安全可控。
- **premain 主路线**：本插件要「开画即用免重启」，attach 是唯一路线；premain（RunConfigurationExtension 注入 -javaagent）仅保留为未来「启动即捕获」的可选补位。

## 与 M56 的增量

attach 路线更优（竞品 premain 对已启动进程失效反证）；「已加载驱动 retransform」从待验证风险→已实证；SQL 提取有「驱动自拼 SQL」捷径可降参数还原子任务权重；relocate/多 JDK/端口自报/异步上报四套工程化从概念变成可照抄 gradle 配置。相关 [[reference-fernflower-decompile]]（MBCHP 反编译同属竞品吸收线）。