# T-McpDynamicToolset：Marketplace 审核拒绝再调整——MCP 工具线全动态化（零平台静态引用）

> 立项：2026-09-26。触发：v2026.9.23 Marketplace 审核拒绝邮件（Plugin Verifier compatibility issues）。
> 本计划为调整方案，**待用户确认后实施**。

## 一、背景与实证读数

### 1.1 审核失败主因（用户后台版本页确认＋本地逐字复现）

Marketplace 拒绝理由＝Plugin Verifier 在 **243 与 251** 上的 compatibility problems。本地用上传原包
（Gitee 附件 sha1 `ebe367d3`，8,000,431 字节）在线口径（无豁免、解析可选依赖）对 243 复现，
读数与 Marketplace 页面**逐字一致**：

- **IU-243 共 2 块**：
  1. `Method McpToolsetSupport.audit(...) references an unresolved class kotlin.coroutines.jvm.internal.SpillingKt`
     ——K2 对「suspend 函数内 catch McpExpectedError」结构性生成 spill，243 平台 stdlib 缺该类；
  2. `Package 'com.intellij.mcpserver' is not found along with its 3 classes`——
     **McpExpectedError**（audit/throwExpected/AuditTicket.expectedError 三处引用）、
     **McpCallInfoKt**（requireProject 的 projectOrNull 扩展）、
     **McpToolset**（基类 implements）。
- **IU-251 共 1 块**：同上 Package 块（T-Mkt251Probe 在案）。
- **报文关键细节**：`com.intellij.mcpserver.annotations` 子包（@McpTool/@McpDescription/@McpToolHints）
  **未出现在报文里**——10 个工具类上的平台注解在 243 的 mcpServer 旧形态（1.0.30）里**可解析**，
  不是问题源。251 同族口径（基线在案块与 243 同源）。

### 1.2 与上轮审核的关系

上轮（20260923 第一轮）拒绝因＝Internal API usage 7 条；T-MarketCompatProxy 代理化后 262 internal
清零、253 归零，随新包重新上传即本轮被拒——internal 已消，compatibility problems 成为唯一被点名项。
三条问题块在 `dev-support/verifier-marketplace-baseline.txt` 均有登记（当时定性「单包形态下不可消除」）。

### 1.3 263 EAP 在案 1 条（复现中）

基线在案备忘：263 EAP 上 Marketplace 实测报 1 条 compatibility problem（当时本地无发行版未查明）。
本轮已下载 263.5701.42（当前 Marketplace 审核线 EAP，与 JPA Buddy 兼容窗口圈定 build 一致），
verifier 复现进行中，结果与处置补入 §5。

## 二、约束与方案选型

### 2.1 用户约束（20260926 明示）

1. **消除所有兼容性问题**（不是申诉豁免，是真消除）；
2. **单包**（不出多版本/多渠道包）；
3. **继续用平台 mcpServer 能力**（不自实现 MCP 协议栈，保留 IDE 工具→MCP 服务器、平台总开关、
   agent 零配置发现、ACP 通道等平台生态）；
4. **since-build 保持 243**（不收窄兼容区间）。

### 2.2 方案空间

| 方案 | 结论 |
|------|------|
| A. since-build 收紧 253 | 一行改动可归零，但等价于放弃 2024.3~2025.2 用户（违反约束 4），**否** |
| B. MCP 自实现（自家 HTTP server） | 可彻底归零，但放弃平台 MCP 生态与零配置发现（违反约束 3），**否** |
| C. 申诉（false positive 回复） | 邮件给了通道，但模板语气硬、且 263 正式版发布后同因再来，**只作并行动作不作主路径** |
| D. **全动态化：jar 字节码零 `com.intellij.mcpserver.*` 类引用，运行期 ASM 生成壳类注册** | 四约束全满足，**选型** |

方案 D 的机理：Marketplace verifier 只做**静态字节码分析**（扫插件 jar 内全部类），运行期生成的
类不在扫描面内。上轮 T-MarketCompatProxy 已用 Proxy+反射把 FilterProvider/ACP impl 做到字节码零引用
（253 归零实证）；本轮把最后一块——EP 接口实现类（McpToolsetSupport implements McpToolset）——
用「极简 ASM 壳类」同样动态化。上轮「EP 接口实现类无法消除」的结论被打破：XML 声明形态确实无法消除，
但**程序化注册（McpGateFilterEpBridge 先例，真机验证过）＋运行期生成实现类**可以。

## 三、技术方案

### 3.1 引用面清单与处置（逐条）

| # | 引用点（全部集中在 McpToolsetSupport 基类） | 处置 |
|---|------|------|
| 1 | `implements McpToolset` | 基类摘掉接口，改自有抽象基类；接口由**运行期 ASM 壳类**实现（§3.3） |
| 2 | `catch McpExpectedError` / `throw McpExpectedError`（audit/throwExpected/AuditTicket） | 换自有异常 `SqlvExpectedToolException`；壳层边界（三钩子外的 invoke 转发不可用——见 §3.3 形态 B）改由壳内 catch 自有异常→**反射构造**平台 McpExpectedError 抛出。audit 的 catch 同步改自有异常→**SpillingKt 块随之消失**（catch 平台类是 spill 成因） |
| 3 | `coroutineContext.projectOrNull`（McpCallInfoKt 扩展） | 反射调 `McpCallInfoKt.projectOrNull(Context)`（Class.forName 字符串），解析失败回退报 PROJECT_NOT_READY |
| 4 | 10 工具类上的 `@McpTool/@McpDescription/@McpToolHints` 注解 | **保留不动**——243/251 报文证实 annotations 包可解析；253+/262 本就存在。零改动 |
| 5 | mcp-optional.xml 的 10 条 `mcpServer.mcpToolset` XML 声明 | **整段删除**（XML 声明类名=静态引用形态，必须由注册器替代）；postStartupActivity 声明保留 |

### 3.2 业务层形态（编译期，jar 内静态类）

- `McpToolsetSupport` 改名/改造为纯本地抽象基类（建议新名 `SqlvToolsetBase`，旧名退役）：
  - 三钩子改自有签名：`isEnabled()`/`isExperimental()`/`alwaysIncluded()`（无接口，普通覆写）；
  - `audit`/`throwExpected`/`requireProject` 签名不变（10 个工具类**业务零改动**），
    内部实现全反射化（§3.1 #2/#3）；
  - 附带收益：audit 的 K2 spill（SpillingKt）与「243 测试 JVM 缺 SpillingKt」的测试分层限制
    （T-MTS2-05）一并消失，挂起链可进普通 JUnit。
- 新增 `SqlvExpectedToolException`（自有 RuntimeException）＋`McpToolErrors` 出参不变。
- `McpAuditLog`/`McpToolGate`/各工具实现类：**零改动**。

### 3.3 动态壳与注册链（运行期）

注册器 `McpToolsetDynamicRegistrar`（postStartupActivity 触发，替代 XML 声明）：

1. **探测**：`Class.forName("com.intellij.mcpserver.McpToolset")` 成功 → 新形态 mcpServer 在场
   （243/251 旧形态/无 mcpServer 时静默跳过，与现状「片段不加载」等价）；
2. **生成**：对 10 个工具类各生成一个壳类
   `class <Tool>Gen extends <业务工具类> implements <McpToolset>`
   ——**只生成三钩子覆写**（直接 invoke 自家基类方法），工具方法不生成、靠继承：
   - kotlin-reflect 扫壳类 `memberFunctions` 含继承的工具方法（声明在业务类，带平台注解与
     正规 @Metadata/Kotlin suspend 状态机——**suspend 契约、参数 schema、注解读取全部复用
     编译期产物，壳不碰**）；
   - 生成类名保持 `SqlVisualizer` 前缀硬约定（T-McpToolNaming：分组名=simpleName），如
     `SqlVisualizerCheckSqlToolGen`；
   - McpExpectedError 转译点：工具方法由平台经 KFunction 反射调用（不走壳代码），平台直接
     invoke 业务 suspend 方法——**业务方法抛自有异常**，平台 catch 不到 McpExpectedError 会不会
     当 Internal error？——**转译必须下沉到业务基类**：`audit` 在 catch 自有异常后经反射构造
     McpExpectedError rethrow（业务方法字节码里出现 McpExpectedError 引用？——**会**！
     修正：转译用 `throwReflectExpected(msg)` 帮助方法：内部反射构造+`throw`（字节码 athrow
     不要求类型引用——**不成立**：Kotlin `throw` 编译为 athrow 栈顶对象，无类型检查，方法体
     不产生 McpExpectedError 类型引用（catch 块才有 catch_type 表项）；但 `throw e as
     McpExpectedError` 会有 checkcast——**避免 cast、用 Object 接收直接 athrow**（Kotlin 层写
     `throw (Throwable) 构造结果` 即无平台类型引用）。审计侧 `e.mcpErrorText` 同步反射读）。
3. **注册**：`ExtensionPointName.create<Any>("com.intellij.mcpServer.mcpToolset").getPoint(null)
   .registerExtension(壳实例)`（McpGateFilterEpBridge 同款 API 路径，真机验证过）；
4. **门控**：三钩子经基类走 McpToolGate——ReflectionToolsProvider 路径与现状一致；
   外部会话过滤层（McpGateFilterEpBridge）保留不动。

### 3.4 配套清理

- mcpStub 桩源集：MCP 侧桩类型（McpToolset/McpExpectedError/McpCallInfo/annotations 四文件）
  **退役**（编译期不再需要平台 MCP 类型）；ml.llm/ACP 桩保留（ACP 通道未动）。
  build.gradle.kts 与 mpv-testing 的 mcpStub 挂载随内容清理（源集名保留不动接线）。
- `dev-support/verifier-ignored-problems.txt`：MCP 桩族豁免行（「永不可达」缺类）退役清理。
- `dev-support/verifier-marketplace-baseline.txt`：三条 243/251 条目删除；基线机制与
  MarketVerdictChecker 保留（空基线全绿＝防回归门禁）。
- 守卫测试调整：McpToolsetStubSignatureCrossVersionTest 等 MCP 桩签名守卫随桩退役重审；
  新增壳生成器单测（生成类→Java 反射断言 implements/三钩子/类名前缀）＋反向验证
  （注册器探测失败路径：无 mcpServer 环境零注册零异常）。

## 四、待验证前提（实施者先证伪再动手）

| # | 前提 | 验证方法 | 失败回退 |
|---|------|----------|----------|
| V1 | kotlin-reflect 对「无 @Metadata 壳类 extends 有 @Metadata 业务类」的 `memberFunctions`/`findAnnotation`/`KParameter.name` 读取正常（参数 schema 名可读） | 262 沙盒 runIde：tools/list 断言 sqlv_* 十工具 schema 与现状逐字段一致 | 若参数名退化：壳类复制业务类 @Metadata 注解（d1 改类名）或回到全方法桥接生成（§3.3 大形态） |
| V2 | 平台经 KFunction 反射调用业务 suspend 方法，透传 Continuation 契约成立（挂起/恢复正常） | 262 沙盒真机调 render_sql/explain（有真实挂起链的工具） | 回退同 V1 大形态 |
| V3 | 业务基类 `throw (Throwable) 反射构造结果` 字节码零平台类型引用；verifier 不再报 | 改造后 verifyMarketplace 全目标跑，compatibility-problems.txt 空 | — |
| V4 | `mcpToolset` EP 接受程序化 registerExtension(Any)（EP 实例化时机/门控链不拒 Proxy 等价物） | 262 沙盒：工具清单出现十工具＋平台「暴露工具」页分组正常（SqlVisualizer 前缀） | 退路：改用平台 `McpServerService` 注册 API（反射），再不行回方案 B/C 决策 |
| V5 | 253 线（内置旧形态 mcpServer）探测静默跳过，行为与现状等价（MCP 不可用口径不变） | 253 沙盒 runIde 零异常＋日志无注册行 | — |
| V6 | 263 审核线目标保持零块（已实测 263.5701.42 Compatible，见 §5） | 改造后 263 目标复跑＋263 正式版发布后纳入校验 | 若正式版报新块，单独立项处置 |
| V7 | 反射构造 McpExpectedError 的构造器签名跨 253/262 稳定（253 Marketplace 包 253.28294.336） | javap 两版本 jar 比对（F:\gradle-home transforms 有 253/262） | 构造器差异按版本分支字符串选择 |

## 五、263 EAP 复现读数（已回填 20260926）

verifier 1.410 在线口径对上传原包 × **IU-263.5701.42**（当前 Marketplace 审核线 EAP build，
与 JPA Buddy 兼容窗口圈定一致）实测：

- **verdict＝`Compatible`，零 compatibility problems**（无 compatibility-problems.txt）；
  scheduled for removal 6 条 / deprecated 71 / experimental 92（均属不阻断 warning 面）。
- 基线在案备忘「263 EAP（263.5153.40）报 1 条」**随 EAP build 演进消失**——9/23 时点的旧 EAP
  报数不复现于当前审核线。结论：**V6 无需改造即成立**；263 正式版（预计 2026.11）发布后按
  备忘纳入校验目标复跑即可。
- 归零面收窄为 **243×2 ＋ 251×1**（251 为 T-Mkt251Probe 在案同族块，实施 B3 时随终验一并复现）。

## 六、实施批次

- **B1 业务层解耦**：SqlvToolsetBase（原 McpToolsetSupport 反射化）+SqlvExpectedToolException
  +反射 projectOrNull/McpExpectedError 构造；基类桩退役；定向测试（MCP 纯函数层全量+新增基类单测）。
- **B2 动态壳与注册器**：ASM 壳生成器（自带 asm jar 入插件 lib，避免平台 ASM 可达性差异）+
  McpToolsetDynamicRegistrar；mcp-optional.xml 删 10 条声明；壳生成器单测+反向验证。
- **B3 门禁与基线收敛**：verifyGate/verifyMarketplace 跑五目标（243/251/253/262/263——251 与 263
  以 probe 形态进目标或临时手跑）；baseline 三条删除；ignored-problems 清理；**全目标
  compatibility problems 必须为空**（V3/V6 终验）。
- **B4 真机验收**：262 沙盒 runIde 十工具全量连通（复用 mpsv-mcp-test.mjs，64342 通道不变）+
  [MCP-AUDIT] 对账+「暴露工具」页分组名核查；253/243 沙盒静默验证（V5）。
- **B5 重传链**：新版本号（实施完成当日日期，当前=2026.9.26 或顺延）→发行 8 处同步（MCP 对外口径
  定例照旧：按「新增 MCP 能力」表述）→releaseGate→Gitee 双通道发行→Marketplace 上传→回读 updates。
  **并行：回复审核邮件**（说明 compatibility 均为可选能力在旧版 IDE 的可选依赖形态问题、
  运行期已双隔离、新版本将从字节码层彻底消除静态引用——修复前置表态，不承诺日期）。

## 七、验收标准

1. verifyMarketplace（在线+无豁免）五目标 verdict 全部 `Compatible`，compatibility-problems.txt 零块；
2. 262 沙盒真机：十工具连通读数与 20260923 实测基线一致（MCP-AUDIT 全 OK/EXPECTED_ERROR 对账）；
3. 243/251/253 无 mcpServer 新形态环境：零注册、零异常、其余功能零影响；
4. 插件 jar 内 `com.intellij.mcpserver` 字符串仅存在于 Class.forName 字符串常量（守卫测试钉死）。

## 八、风险与回退

- **主风险**：V1/V2（kotlin-reflect 对生成类的解析）——262 沙盒一次 runIde 即可证伪，失败回退
  §3.3 大形态（全方法桥接+@Metadata 复制）或方案 B/C 用户决策；
- ASM 自带 jar 与平台 ASM 隔离（插件 classloader），无冲突面；
- 动态生成类绕过静态验证的形态属「合规实现」（注册的是真实实现 McpToolset 接口的运行期类，
  行为与 XML 声明等价），非对抗性规避；
- 若 B3 全绿但 Marketplace 审核仍拒（非 verifier 因素），转申诉主路径（邮件回复已有论证骨架）。

## 九、实施记录

（实施时回写）
