# T-McpDynamicToolset 真机 ASM 不可达：日常 IDE 262 MCP 工具全失修复——开发计划

> 2026-10-05 用户反馈：「将插件打包装在日常 2026.2 idea 里，发现 mcp server 中没有本插件相关的工具」。真机取证定位唯一断点 = `McpToolsetDynamicRegistrar.shellBytes()` 抛 `NoClassDefFoundError: org/objectweb/asm/ClassWriter`（日常 IDE 262.10968 六轮日志实锤）——**20260926 全动态化（T-McpDynamicToolset）只在无 ASM 平台库的沙盒验证过，真机从未验过**，属发行级回归（2026.10.6 包若带此缺陷发布，MCP 工具线在真机结构性全失）。写完停等确认，未获确认不实施。

## 一、真机取证（2026-10-05，日常 IDE = IU 262.10968.63）

| 项 | 证据 |
|---|---|
| 安装形态 | `%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\mybatis-plus-sql-visualizer\lib\`：`mybatis-plus-sql-visualizer-2026.10.6.jar` + `asm-9.7.1.jar`（13:15 安装；与 `build/distributions/mybatis-plus-sql-visualizer-2026.10.6.zip` 逐项同构） |
| 插件加载 | idea.log 插件清单含 `cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer`；`com.intellij.mcpServer` 同在加载清单 → mcp-optional.xml 正常加载 |
| 失败日志 | idea.log 12:35~13:15 **六轮** `WARN McpToolsetDynamicRegistrar - T-McpDynamicToolset: mcpToolset EP 注册失败`；完整栈 = `java.lang.NoClassDefFoundError: org/objectweb/asm/ClassWriter`（`Caused by: java.lang.ClassNotFoundException: org.objectweb.asm.ClassWriter`）`at McpToolsetDynamicRegistrar.shellBytes$...(McpToolsetDynamicRegistrar.kt:94)` ← `registerToolset(:80)` ← `registerOnce(:65)` ← `McpGateFilterRegistrar.execute(:30)` |
| 对照成功路径 | 同会话 `INFO McpGateFilterEpBridge - O-68: sqlv gate filter registered onto mcpToolFilterProvider EP (proxy form)` → **mcp-optional 加载、McpToolset 探测、EP 注册链全通**，唯一断点 = ASM 壳生成 |
| 自带 jar 矛盾 | 安装目录 `asm-9.7.1.jar` 解包含 `org/objectweb/asm/ClassWriter.class`（45 条目）——jar 在、类在，classloader 却 CNFE |
| 平台 asm 对照 | **真机** IU 262.10968.63 lib = `intellij.libraries.asm.jar` + `intellij.libraries.asm.tools.jar`（均含 ClassWriter）；**沙盒**平台（`F:\gradle-home\...\transformed\idea-2026.2.1/2026.2.3-win\lib`）**无任何 asm jar** |
| 平台工具发现机制 | 262 mcpserver.jar javap：`ToolsetReflection_utilKt.asTools` = `toolset.javaClass` → `KClasses.getFunctions`（类层次全成员扫描）→ `getPreferredToolAnnotation` 找 `@McpTool` → 生成 `ReflectionCallableMcpTool`——**必须「继承工具类」的真类实例**，JDK Proxy 不可行（`$ProxyN` 类层次无业务方法） |

## 二、根因链（四层归因）

### 1. 直接断点：壳生成撞 `org/objectweb/asm/ClassWriter` CNFE

`McpToolsetDynamicRegistrar.registerOnce()` 探测 `Class.forName("com.intellij.mcpserver.McpToolset")` 命中 → 对 10 个工具类逐个 `shellBytes()`（`new ClassWriter(COMPUTE_MAXS)` 起手，第 94 行）→ 加载 `org/objectweb/asm/ClassWriter` 时插件 classloader 抛 CNFE → `runCatching` 捕获记 WARN，**registered 不置位，每次项目启动重试、每轮同样失败**（六轮日志实证）→ 10 个 toolset 全部未注册 → 平台 MCP 服务器清单零 `sqlv_*` 工具。

### 2. 自带 asm jar 为什么没用：平台已知包接管 + 平台 asm 对插件不可见（双落空）

真机平台 lib 有 `intellij.libraries.asm*.jar` → `org.objectweb.asm.*` 是**平台已知包** → 插件 classloader 对该包前缀 parent-first 接管，**插件自带 `lib/asm-9.7.1.jar` 的同包类被跳过**；而平台的 asm 位于 `intellij.libraries.*` 内部库 jar（对插件 classloader 的 parent 链不可见/过滤）→ 两处都拿不到 → CNFE。4bf3b6b 提交注释「ASM 9.7.1 自带进插件 lib（免平台 ASM 可达性差异）」与 build.gradle.kts:120-122 注释「插件 classloader 自成一体」是同一处**待验证前提证伪**——自带 jar 不能免平台包接管，`implementation("org.ow2.asm:asm:9.7.1")` 只保证了「编译期 + 无 asm 平台下的运行期」，救不了「有 asm 平台下的运行期」。

### 3. 为什么沙盒假绿：IPGP 沙盒平台与真机平台的产品裁剪差异

沙盒用 gradle 缓存平台 `idea-2026.2.1/2026.2.3`（IntelliJ IDEA Ultimate 发行线）——lib **无 asm jar**，平台不接管 `org.objectweb.asm` 包 → 插件自带 `asm-9.7.1.jar` 正常加载 → 动态壳注册成功。真机日常 IDE `262.10968.63`（同一构建线但含 asm 平台库）→ 接管 → CNFE。**同一 262 大版本内、沙盒与真机行为不同**——T-McpDynamicToolset 20260926 的「262 沙盒十工具端到端绿」是验证盲区假绿。

### 4. 回归历史：动态壳形态从未真机验证

| 形态 | 时间 | 真机状态 |
|---|---|---|
| XML 声明 10 toolset（基类静态 implements McpToolset） | ~20260923 | 在册（tools/list 62 工具 sqlv_* 恰 10 个，T-MarketCompatProxy 双验记录） |
| 20260926 审核被拒（243×2+251×1 verifier compatibility problems）→ T-McpDynamicToolset ASM 动态壳（4bf3b6b） | 20260926 | **仅沙盒验证，真机从未验** → 本次真机 CNFE 全失 |
| 20261005 用户打包 2026.10.6 安装日常 IDE | 20261005 | 十工具全失（本计划） |

## 三、方案设计

### S1（主修）：`McpToolsetDynamicRegistrar` 去 ASM 依赖——手写极简 class 文件生成器

壳类的字节码结构固定且极小（`final class Shell extends 工具类 implements McpToolset` + 唯一构造器 `aload_0; invokespecial 工具类.<init>; return`），无需 ASM 即可生成：

- 新增 `TinyClassFileWriter`（mcp 包内，~200 行）：按 class 文件规范直接写常量池（Class/NameAndType/Utf8/Methodref）+ 类头（V17 major 61，与现 ASM 版一致，243 JBR 21 可加载）+ 构造器方法（Code 属性）。输入 = `shellName` / 工具类 internal 名 / `McpToolset` internal 名，输出与 ASM 版**行为等价**的壳字节码（字段、访问位、接口表、方法表逐项对齐）。
- `shellBytes()` 签名与 `registerToolset()` 调用点不变，仅实现换生成器——注册链（探测→define→registerExtension）零改动。
- **零外部依赖、零构建改动、不撞平台包名**（生成器自身不 import `org.objectweb.asm.*`）；verifier 不可见（运行期生成，与现形态一致）。
- 继承链语义不变：壳继承工具类 → 平台 `KClasses.getFunctions` 类层次扫描照旧命中 `@McpTool` 方法（§一 javap 实证）；T-McpToolNaming 分组名约定（壳 simpleName 与业务类一致、`mcp.gen` 子包）不变。

### S2（构建清理）：删 asm 依赖，zip 不再带 asm jar

- 删 `build.gradle.kts` 的 `implementation("org.ow2.asm:asm:9.7.1")`（119-123 行注释块一并清理，含「自带免平台可达性差异」的错误结论注释）。
- `buildPlugin` 后验证 zip 的 `lib/` 不再含 asm jar（当前 zip 含 `asm-9.7.1.jar`，随 S1 失效冗余）。

### S3（测试与守卫）

- `McpToolsetShellFactoryTest` 改直测新生成器：断言不变（major 61 / superclass=工具类 / interfaces 含目标接口 / simpleName 前缀 / 实例化 / 三钩子与业务方法继承可调），行为等价于 ASM 版（字节码不同、语义相同）。
- **新增编译期引用守卫**（McpScopeSourceScanTest 同款扫描，或并入该文件）：断言 `src/main/kotlin/visualizer/mcp/` 无 `org.objectweb.asm` import——ASM 依赖回归（有人改回 `org.ow2.asm` 依赖 + 恢复 import）即红。
- **反向验证（必做）**：临时把生成器换回 ASM 实现（或恢复 asm import）→ 守卫与/或单测必红；恢复手写生成器 → 绿。贴两次读数。
- 定向测试：`mcp` 包全量 + PluginXmlStructureGuardTest 回归；`testFast` 归口。

### S4（真机复验，本计划验收的唯一权威）

- 修复后**用户日常 IDE（262.10968.63）重装 2026.10.6 zip** → idea.log 出现 `T-McpDynamicToolset: 10 toolsets registered onto mcpToolset EP (dynamic shell form)`（无 WARN）→ 平台 MCP 服务器 `tools/list` 十 `sqlv_*` 工具在册 → `sqlv_check_sql`/`sqlv_render_sql` 端到端调用 + `[MCP-AUDIT] status=OK` 对账（复用 `dev-support/mcp-test/mpsv-mcp-test.mjs`）。
- 沙盒（262.1/262.3 + 243）复验同绿——修复后两形态平台应一致（沙盒无 asm 平台、真机有 asm 平台，两种环境下手写生成器都不依赖平台 ASM）。
- 复验时机避开用户用 IDE 操作（记忆定例：用户在用 UI 时勿抢）。

## 四、待验证前提（实施者先证伪再动手）

1. **平台包接管机制精确形态**：`org.objectweb.asm` 被插件 classloader parent-first 接管且失败不 fallback + `intellij.libraries.*` 对插件不可见——CNFE + 真机平台有 asm jar + 沙盒平台无 asm jar 三证据强支撑，但「parent 具体哪一层、为何看不到平台 asm」的 classloader 层级细节未完全实证。若 S1 落地后仍异常，用临时诊断日志/`Class.forName` 探针确认实际加载失败点（参照「别急着判环境受限——先证明路径被走到」纪律）。
2. **手写 class 文件写入器字节码与 ASM 版行为等价**（平台 kotlin-reflect 类层次扫描 / suspend 契约 / 壳零参与）——单测 + S4 真机复验兜底。
3. **V17（major 61）在 243 沙盒 JBR 21 可加载**——与现 ASM 版同版本号，低风险，定向沙盒复验确认。
4. **沙盒与真机修复后行为一致**（沙盒无 asm 平台、真机有 asm 平台）——两环境 S4 各验一轮。
5. **zip 不再含 asm jar**（S2 后 buildPlugin 产物核验）。

## 五、范围外

- `McpGateFilterEpBridge` / `AcpTransportRegistrar`（JDK Proxy 形态，真机已成功，不受影响）。
- `CallerSourceLocator.kt` / `SourceInterceptorTemplates.kt` 帧过滤名单里的 `org.objectweb.asm` **字符串前缀**——字符串非字节码引用，无害，不动。
- 平台 253/251 及更早线：探测式静默跳过，不触达 ASM 加载，不受影响。
- `McpToolsetDynamicRegistrar` 既有登记项（F29 幂等性缺口：部分失败重试撞同 FQN LinkageError；`registered` 不置位导致每项目启动重试）——本计划不修，S1 落地后重试次数归零、风险自然缓解，登记观察。

## 六、实施记录（实施后回填）

| 时刻 | 事项 | 读数 |
|---|---|---|
| 13:35 | 计划落库，用户确认实施 | — |
| 13:36~13:40 | S1：新增 `TinyClassFileWriter`（手写 class 文件生成器，V17/常量池四条目/构造器字节码），`McpToolsetDynamicRegistrar.shellBytes` 委托之、删 asm import、KDoc 更新 | 守卫先红后绿：ASM import 在场 `testNoOrgObjectwebAsmReferenceInMcpPackage FAILED`（4 测 1 败）→ 摘除后绿；`McpToolsetShellFactoryTest` 手写生成器行为等价绿（major 61/superclass/interfaces/实例化/三钩子继承全过） |
| 13:41 | S2：删 build.gradle.kts `org.ow2.asm:asm:9.7.1` 与错误结论注释 | buildPlugin zip lib **无 asm jar**（8253292→8135271，−118KB 吻合）；主 jar 内 TinyClassFileWriter 在场、objectweb/asm 条目零 |
| 13:42~13:44 | S3：McpScopeSourceScanTest 新增 asm 引用守卫；mcp 包定向全量 | 守卫 4/0；mcp 包定向 BUILD SUCCESSFUL |
| 13:46 | testFast 回归 | mcp 相关全绿；**既有红与本项目无关**：`DialogWrapperInitOrderGuardTest` 报 `AgentManager.kt` consentAskGuardMs/lastConsentAskAtMs 初始化顺序陷阱——git stash 对净 HEAD（769a1b0）复跑同红坐实归属 T-FirstUseFreshInstallConsent R3 遗留，留原车道处置 |
| 13:55 | 遗留红随本轮修复（用户点名）：`consentAskGuardMs`/`lastConsentAskAtMs` 两属性声明上移到测试缝区块（init 块之前，原 666/670 行），零语义变化 | DialogWrapperInitOrderGuardTest 绿；AgentPremainConsentTest/AgentConsentFlowTest/AgentManagerPremainTest 绿；agent 包全量 BUILD SUCCESSFUL；testFast 复跑读数见下 |
| 13:44~ | S4：真机复验 | 待用户重装日常 IDE 262.10968 后：idea.log `T-McpDynamicToolset: 10 toolsets registered` + tools/list 十工具在册 + sqlv_check_sql 端到端 + [MCP-AUDIT] OK |

过程坑留档（本计划实施两处）：
- `TinyClassFileWriter.ConstantPool` 首版「写时惰性解析 utf8 索引」（classRef 的 lambda 内才调 utf8()）→ `writeTo` 迭代 `items` 期间 utf8 又 add → `ConcurrentModificationException`（McpToolsetShellFactoryTest 当场抓到）。改「构建时急切解析索引、writeTo 纯输出」修复——字节码生成器的常量池引用必须前向稳定，写期不得再改池。
- `0xCAFEBABE` 字面量超 Int 范围被 Kotlin 判 Long → `writeInt` 类型不匹配编译错；改 `0xCAFEBABE.toInt()`。
