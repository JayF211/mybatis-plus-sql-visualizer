---
name: t-m56-agent-delivery
description: M56 增强捕获 agent
  字节码通道一期交付（feature/t-m56-01-agent-base，2026-09-12）——agent fat jar + attach 全链
  PoC 门禁实测过 + IDE 集成 + EP 双轨；全量 3359 用例绿 + 三目标 Compatible
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3da5e6cf-62af-447f-aa90-8df08c999579
---

M56 增强捕获（agent 字节码通道）一期已交付，分支 `feature/t-m56-01-agent-base`（worktree `F:/code/Java/Gitee/SongRenShuo/mpsv-m56`，基于 T-M56-00 栈式叠加，未合 master——用户要求最终自行合并）。提交：`a16122c`（agent 基座）+ `4623a12`（IDE 集成）+ `bea1751`（文档）。

- **agent 子项目**（`agent/`，Java 8 fat jar，无 shadow 插件——自写 RelocateAsmMain 用 ASM 重定位自身+agent 类引用）：织入=ASM Tree API 每方法出口零分支直线 invokestatic；织入期判定回调类对定义 loader 可见性（不可见整类不织入）。**AdviceAdapter 反射内联方案因帧合并/VerifyError 组合坑弃用**。
- **关键踩坑**（细节见变更记录 2026-09-12 行）：①relocator 须同时重写 agent 类的 ASM 引用（否则 NCDFE）；②hook 须返回包装连接+checkcast（void 侧效调用包装结果被丢弃）；③instrument transform 传斜杠内部名；④fat jar 并发首定义竞争→AgentMain `<clinit>` eager 加载规避；⑤State 数据类加字段后 copy$default 增量中间态假象（全清产物复绿）。
- **门禁实测**：AttachGateTest 真 attach 子 JVM（childapp 独立源集，cp=h2+childapp 不含 agent 类）→ agentmain retransform 已加载 H2 驱动 → SQL 事件 loopback 到达 + 二次 loadAgent 幂等（AgentLoadException("0")）+ 单会话单 HELLO。agent 6 类/20 用例全绿。
- **IDE 侧**：AgentManager（惰性挂载 3s 去抖不进 EDT/容错/幂等表/在线态/回落）、SqlCaptureGate 接管闸门（桥空转/恢复）、AgentEventAdapter（dedupeKey 与桥一致）、渲染三片段（⚡增强/(N rows)/⛔报错）、设置页开关+确认框（JEP 451 话术）、consoleFilterProvider EP 双轨（epActive 首行标志粘滞→桥轮询跳过挂载）。
- **验证**：IDE 全量 273 类/3359 用例/0 失败/6 跳过；verifyPlugin 243/253/262 Compatible；产物含内嵌 agent jar（6.43MB）。
- **JDK8 门禁已闭环（原执行偏差①）**：用户本机有 JDK8（`D:/HuanJing/Java/jdk1.8.0_181`），AttachGateTest 参数化双目标（`sqlvis.jdk8home` 可指定，缺席 Assume 跳过）——childapp 与 ChildApp 降 Java 8 字节码（pid 改 ManagementFactory 兼容）、目标驱动 H2 1.4.200（2.x 是 Java 11 字节码，独立配置 `h2jdk8` 系统属性下发路径）；**17 客户端→8 目标跨版本 attach 实测过**。注意：JDK8 目标**首次** loadAgent 即抛 `AgentLoadException: 0`（非仅二次挂载），生产 AgentManager 的容忍语义已覆盖。
- **留人工/二期**：runIde 真机走查（确认框/接管回落/Hikari/压测）、Docker 驱动矩阵（本机无 Docker）、T-M56-07 Web 过滤器 hook（MDC 兜底已做）。关联 [[project-m56-agent-bytecode]]、[[t-m56-00-delivery]]。
- **构建形态定案（2026-09-12 用户咨询后确认）**：agent 子项目保持 Gradle + `--release 8` 产物，不换 Maven、不升 JDK17 字节码。理由：agent jar 加载方是**用户应用 JVM**（非 IDE JBR），17 字节码会让 JDK 8/11 应用直接 UnsupportedClassVersionError → 增强捕获对这部分用户永久无效（MP 用户群 Java 8 占比高）；Maven 换构建对用户零感知但丢失与主项目 Gradle 的任务依赖图（embedAgentJar 桥接成本>收益）。自动打包链路已验证可用：`buildPlugin` → `processResources` → `embedAgentJar` → `:agent:agentFatJar` 一条命令全链，产物插件 jar 内含 `agent/mps-sql-agent.jar`；agent 测试（含 attach 门禁）刻意不挂打包链，留 `test`/CI。若将来 agent 需要 17-only API 再考虑双产物（agent8/agent17 按目标 JVM 选）。
