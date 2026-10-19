# T-MlLlmSignatureGuard ml-llm 跨版本签名护栏 — 开发计划

- 日期：2026-09-20 11:48
- 任务编号：T-MlLlmSignatureGuard（第九轮报告 §9.4 **O-41**，P2）
- 来源：ACP 验收文档 §七-7 待决策项——**`@ApiStatus.Internal` 类型的漂移没有任何自动化门禁**：verifier 对 optional 依赖走「不可用」分支、完全不做类型解析，三个目标平台都没有 AI Assistant（依赖树逐目标记 `(failed) com.intellij.ml.llm (optional): Unavailable`），故 ACP 绕行所依赖的 internal 类型漂移不会让任何门禁转红
- 性质：**测试基建新增护栏**——不改产品代码、不改 ACP 绕行实现；为「上游修复后绕行可移除」提供唯一自动信号
- 实施分支：自 master 拉任务分支 t-ml-llm-signature-guard
- 验证基线：master `f8c115d`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0 + verifyGate 三目标 Compatible）

---

## 一、背景与依据（现状核实，含三处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「照 `McpDbSignatureCrossVersionTest` 模式补护栏」 | **成立且范式清晰**：既有两条同类护栏——`mcp/McpDbSignatureCrossVersionTest.kt`（守护 `McpDbExecutor` 的 DatabaseTools 建连/remote JDBC 链）与 `update/PlatformSignatureCrossVersionTest.kt`（守护动态安装五入口 + 第 6 项 `ObjectKind.COLUMN` 静态字段可读性） | 照抄形态即可 |
| 2 | 「跨 243/253/262 三目标」 | **不成立（重大口径修正）**：**243/253 平台没有 AI Assistant**——`acp/AcpTransportIsolationTest.kt:9-12` 明确断言 243 沙盒里 ml-llm 缺席，并用 `AcpBundledAgentUtils` 作标记类（`:52`）。护栏的版本面应写「**本机全部存在的 ml-llm 制品**」，本机实测只有**两处、且都是 262 家族的两个不同 build** | 护栏口径改为「发现式」而非「列举 243/253/262」 |
| 3 | 「桩文件已登记取证内容，可直接复用」 | **成立**：三份桩的 KDoc 登记相当完整，可直接作为断言清单（见 §1.2） | 断言四项的来源明确，不需重新取证 |

### 1.1 既有护栏的实现形态（照抄对象）

`mcp/McpDbSignatureCrossVersionTest.kt`：

- **取签名手段 = 外部进程**：各发行版自带 `jbr/bin/java(.exe)`（`:72-75`）+ `-cp <checker 类目录> McpDbSignatureChecker <发行版目录>`（`:78-83`）；**只传 checker 类目录**以规避 Windows 命令行 32K 上限（`:76-77`）；
- **发行版发现**：`~/.gradle/caches/*/transforms/*/transformed/*` 里含 `build.txt` + `lib/` 的目录（`:47-58`）+ `%LOCALAPPDATA%/Programs/IntelliJ IDEA` 兜底（`:59-64`）；族号取 `build.txt` 的 `(\d{3})\.`（`:65-68`）；
- **断言形态**：逐族跑 checker，`exitCode != 0` 计失败，最后 `assertEquals(failures.joinToString("\n\n"), 0, failures.size)`（`:33-39`）；无发行版则 `assumeTrue` 跳过（`:26`/`:37`）；
- **checker**：`src/test/java/McpDbSignatureChecker.java`（28 条入口逐条 `Class.forName(..., loader)` + `getMethod(name, types)`，`:44-86`；按内容收集 jar——含 `com/intellij/database/` 即收，`:144-169`；loader parent = 平台 loader，`:39`）；
- **铁律 KDoc**：`:9`「新增反射入口必须登记跨版本签名护栏」；文档侧 `docs/架构设计/03-开发与测试指南.md:239`（§4.8-6 McpDb 签名核查）/`:241`（§4.8-8 可选依赖隔离三件套，**本任务补护栏处**）/本任务新增 `:242`（§4.8-8 子条）。

**非同类（勿混入）**：`util/TableConfigSignatureTest.kt` 名称含 Signature 但是**缓存签名语义测试**，不是跨版本护栏。全仓 `CrossVersion` 命中仅上述两处。

### 1.2 断言清单的来源（桩 KDoc 已登记，可直接复用）

| 桩文件 | 行数 | 已登记内容 |
|---|---|---|
| `src/mcpStub/kotlin/com/intellij/platform/acp/AcpAgentId.kt` | 39 | `:11-17` 取证方法与出处（装机路径 + 宿主 `IU-262.10968.63` + JBR-25 `javac` + `java.lang.classfile` 导出）；`:25-27` **刻意偏离**（桩不声明 `sealed`）；`:33-39` 声明 `interface AcpAgentId { val rawId: String; val fullId: String }`（**路径与行号修正**：原计划写的 `com/intellij/ml/llm/agents/acp/api/AcpAgentId.kt` 目录已不存在，桩已迁至平台包） |
| `.../client/AcpIdeaMcpTransport.kt` | 16 | `:7-8` 真类为 `Enum`（ACC_ENUM\|ACC_FINAL\|ACC_PUBLIC），常量**恰** STDIO/SSE、无 Companion；`:13-16` 声明 |
| `.../client/AcpIdeaMcpTransportProvider.kt` | 27 | `:8-19` 真类为接口、标 `@ApiStatus.Internal`、无父接口、唯一抽象方法 `getTransport(AcpAgentId): AcpIdeaMcpTransport?`、另有 Companion 静态 `resolveTransport`；EP 声明在 ml-llm 自身 `plugin.xml` 第 5426 行、自带实现在 5445 行；`:21-27` 声明 |

**独立复核已做过**（262.10968.63 装机 jar 常量池实测）：`AcpAgentId` 含 `getRawId/getFullId/REGISTRY_PREFIX/"acp.registry."`；`AcpIdeaMcpTransportProvider` 含 `getTransport (AcpAgentId)AcpIdeaMcpTransport` + `org/jetbrains/annotations/ApiStatus$Internal`——**桩登记与真制品形态一致**。

**但「桩与真类签名一致」对装机制品不成立（实施期修正）**：装机制品 262.10968.97 的 provider 形参包是 `com.intellij.ml.llm.agents.acp.api`，而桩与 `AcpIdeaMcpTransportProviderImpl.kt:3` 用的是 `com.intellij.platform.acp`——平台包只对 transforms 制品 262.9437.276 成立。即**两份制品的 `AcpAgentId` 不同包**，护栏断言必须按包路径分组；该漂移的运行时后果（EP 类链接是否真的失配）**未在真机验证**，见 §八。

**本机 ml-llm 制品实测（两处，实施期复核）**：

- `~/.gradle/caches/9.7.0/transforms/6378bc26…/transformed/com.intellij.ml.llm-262.9437.276/ml-llm/lib/modules/intellij.ml.llm.agents.acp.jar`——provider 形参 = `com.intellij.platform.acp.AcpAgentId`（**该 jar 内零 `AcpAgentId.class`**，类在配对发行版 `idea-2026.2.1-win`（`IU-262.9437.185`）的 `plugins/platform-acp-plugin/lib/modules/intellij.platform.acp.jar`）
- `%APPDATA%/JetBrains/IntelliJIdea2026.2/plugins/ml-llm/lib/modules/intellij.ml.llm.agents.acp.jar`（`plugin.xml` 版本 262.10968.97，宿主 `IU-262.10968.63`）——provider 形参 = `com.intellij.ml.llm.agents.acp.api.AcpAgentId`，类在该 jar 内

两个 jar 的 `AcpIdeaMcpTransportProvider.class` 类文件 **major 版本均为 69**。

### 1.3 关键约束（决定实现路径）

- **不能用「桩类可加载」代替真类签名断言**：桩刻意与真类偏离（不 `sealed`，`AcpAgentId.kt:19-21`），且桩由本项目提供，**永远「通过」**——这正是假绿的经典形态。
- **反射加载可行性（实施期实测证伪，已改走字节码解析）**：ml-llm 类 major 69 只有 262 发行版 JBR(25) 能载；该 JBR **只有 `java.exe`、无 `javap`/`jshell`**（实测）⇒ 计划原推的「纯 Java checker + 发行版 JBR 运行 + `URLClassLoader` 反射」**不成立**，理由两条（JBR 25.0.4 实跑）：① `@ApiStatus.Internal` 是 CLASS retention（`lib/annotations.jar` 里 `ApiStatus$Internal` 自身 = `@Retention(CLASS)`），落在 `RuntimeInvisibleAnnotations`，反射读不到；② `AcpIdeaMcpTransport` 引 `kotlin/enums/EnumEntriesKt`（ml-llm 全部 jar 都不含 kotlin-stdlib），`Class.forName(...,true)`/`getEnumConstants()` 直接 `NoClassDefFoundError`。故 checker 只读 class 文件字节（常量池 + 访问标志 + 字段/方法 + 类属性），零平台依赖、用测试 JVM 自带 java 运行。
- **守卫联动**：`acp/AcpTransportScopeSourceScanTest.kt:105-107` **硬列三个桩文件路径**——若新增桩文件或改动桩清单须同步该断言；`config/PluginXmlStructureGuardTest.kt:255-273`（optional 依赖声明与 provider 注册成对断言）、`:277-283`（主描述文件不得直接引用 ml-llm 类型）在动 optional 文件时须同步（**行号修正**：原计划写的 `:235`/`:257` 实为 mcp toolset 断言与注释行）。

## 二、目标与非目标

**目标**：① 给 ACP 绕行所依赖的 ml-llm internal 类型建立跨版本签名护栏，使上游漂移**可自动发现**；② 上游修复落地时，护栏成为「绕行可移除」的唯一自动信号；③ 把「签名护栏」与「作用域守卫」的分工写清。

**非目标**：不改 ACP 绕行实现（`acp/AcpIdeaMcpTransportProviderImpl.kt` 不动）；不改桩的声明（桩与真类签名一致，不需要动）；不检测上游 `@agentclientprotocol/claude-agent-acp` 是否已修（npm 包不在本仓依赖图，无自动检测面——登记）；不替代真机走查（「ml-llm 在场时 provider 真被注册 / SSE 通道可达」仍只能真机，见 §五）。

## 三、方案

**T-MLS-01（checker，纯 Java 外部进程）**：新增 `src/test/java/MlLlmAcpSignatureChecker.java`。**实施期偏离**：不做 `URLClassLoader` 反射（实测证伪，见 §1.3），改为**只读 class 文件字节**的最小解析器（常量池/访问标志/字段/方法/类属性），零平台依赖、任意 JDK 可运行。用法：`java -cp <checker目录> MlLlmAcpSignatureChecker <制品根> <期望AcpAgentId包名> [companion根 …]`；退出码 0 = 全成立 / 1 = 有断言不成立（末行 `RESULT MISSING n`）/ 3 = 制品未携带 ml-llm ACP 模块（区分「制品不完整」与「签名漂移」）。诊断行 `PROVIDER_DECLARED_AGENT_ID_PACKAGE` / `AGENT_ID_CLASS` / `COMPANION_AGENT_ID_CLASS` 使来源与漂移可见。

**T-MLS-02（四项断言，全部来自桩已登记内容；**按包路径分组**）**：
1. `<期望包>/AcpAgentId` 为**接口**且含抽象 `getRawId()` / `getFullId()`（描述符 `()Ljava/lang/String;`）；
2. `AcpIdeaMcpTransport` 为 **`Enum`**（`ACC_ENUM` + 父类 `java/lang/Enum`）且枚举常量（`ACC_ENUM` 字段）**恰** `[STDIO, SSE]`；
3. `AcpIdeaMcpTransportProvider` 为**接口**、`getTransport` 描述符 = `(L<期望包>/AcpAgentId;)Lcom/intellij/ml/llm/agents/acp/client/AcpIdeaMcpTransport;` 且抽象，类上 `RuntimeInvisibleAnnotations` 含 `Lorg/jetbrains/annotations/ApiStatus$Internal;`（**读属性位，不用反射**——该注解是 CLASS retention）；
4. `<期望包>/AcpAgentId.REGISTRY_PREFIX` 常量（`ConstantValue`）仍为 `"acp.registry."`（对上 `AcpIdeaMcpTransportProviderImpl.kt:65` 的 `CLAUDE_ACP_ID` 拼接依据）。
   **期望包名由测试侧「制品→包名」登记表给出**（transforms → `com.intellij.platform.acp`；装机 → `com.intellij.ml.llm.agents.acp.api`），描述符声明的包与登记值不符即红（fail-closed：上游改包必须人工复核后改登记表）；AcpAgentId 类在制品内找不到时回落 companion 根并打印来源 jar。

**T-MLS-03（测试类与发现面）**：新增 `src/test/kotlin/.../acp/AcpSignatureCrossVersionTest.kt`，照 `McpDbSignatureCrossVersionTest.kt:47-87` 的发现 + 执行逻辑，**发现面**：
- `~/.gradle/caches/*/transforms/*/transformed/com.intellij.ml.llm-*/ml-llm/` 目录；
- `%APPDATA%/JetBrains/IntelliJIdea*/plugins/ml-llm/`（装机插件目录）；
- companion 根：`transforms/*/transformed/idea-*/plugins/platform-acp-plugin/lib/modules` + 装机 `%LOCALAPPDATA%/Programs/IntelliJ IDEA/plugins/platform-acp-plugin/lib/modules`（transforms 制品的 `AcpAgentId` 只在这里，按 build 中段号配对优先）。

无制品则 `assumeTrue` 跳过（照既有形态，不硬失败——因为 243/253 平台本就没有 AI Assistant）。执行用测试 JVM 自带 java（`java.home`）而非发行版 JBR——制品目录里没有 jbr，且字节码解析不需要 major-69 支持。

**T-MLS-04（分工写清）**：在测试类 KDoc 中写明与既有守卫的分工——`acp/AcpTransportScopeSourceScanTest` 管**作用域**（ml-llm 类型引用限定在 `visualizer/acp/` 包、META-INF 只允许 `acp-transport-optional.xml`、桩类型只允许在 `mcpStub`），本护栏管**签名**。并在 `docs/架构设计/03-开发与测试指南.md` §4.8-8（`:241`）后补子条（**实测落位 `:242`**）。

**T-MLS-05（文档与登记，**实施期改派**）**：CHANGELOG / `docs/变更记录.md` / 第九轮报告 §9.4 O-41 落地备注 / ACP 验收文档 §七-7 标注**四个文件均不在本任务写集**（计划与写集自相矛盾），按任务说明改由 T-Round10-08 统一写；本任务只写指南（`:242` 子条 + §3.2 类数表）与本计划 §八。ACP 验收 §七-7 的另一半（是否向 JetBrains 报 `claude-agent-acp` 判断缺陷）**留给用户决策**。

## 四、验收标准

1. 护栏在本机**实跑一次**，对本机两处 ml-llm 制品均输出 `RESULT OK` 且退出码 0（按「制品→AcpAgentId 期望包名」分组；transforms 制品的 AcpAgentId 类须由 companion 根（配对发行版 platform-acp-plugin）提供。若某处制品缺 ACP jar 则该处记 MISSING 并如实登记，不得静默通过）。
2. **先红后绿实证**（本仓守卫类已三次绿灯下失效，此项为硬要求）：临时把某一项断言的期望值改错（如把常量集改成 `[STDIO]`），确认护栏转红；还原后绿。两次读数留档。
3. 无 ml-llm 制品的环境（243/253 沙盒或 CI）走 `assumeTrue` 跳过，**不误报失败**。
4. 与既有守卫无重叠：`AcpTransportScopeSourceScanTest` 的 120 行断言面不被本任务改动（除 §1.3 的桩清单联动）。
5. 全量 testShards + testDatabase + agent 绿；新增测试类须同步 `docs/架构设计/03-开发与测试指南.md` §3.2 的类数/用例数分布表（文档级）。

## 五、真机走查清单（留人工）

本任务**不闭合**以下真机项（它们是 ACP 批次原有的未验点，护栏只能替代其中「签名漂移」一面）：

1. ml-llm **在场**时装启动：EP 注册与类链接无异常（headless 沙盒无 ml-llm，此前只证了「缺席时无碍」）。
2. **R3：SSE 通道 agent 侧可达性**——`idea.log` 出现 `Running private IJ MCP session for agent acp.registry.claude-acp over Sse`（绕行成败点）。
3. ACP 会话工具表出现十个 `sqlv_*` 且为原生 MCP 调用。
4. `[MCP-AUDIT]` 审计行出现 `tool=sqlv_* status=OK`。
5. **（2026-09-22 第二轮收口：字节码级半边已闭环，真机半边仍未验）**装机制品 262.10968.97 的 EP 接口形参包是 `com.intellij.ml.llm.agents.acp.api`，而我方 `AcpIdeaMcpTransportProviderImpl` 原只实现 `com.intellij.platform.acp` 形态——**JVM 按擦除后描述符匹配 override，故原实现在装机线上没有实现该接口的抽象方法**（EP 调用即 `AbstractMethodError`，SSE 绕行失效），而当时护栏按「制品→期望包名」分组各自成立即绿，看不见这个失配（独立复核 major⑯）。现：① 实现改为**跨形态**（两个 `AcpAgentId` 形态各声明一个重载，两条发行线各自匹配）；② 护栏补**断言⑥**——逐制品核「我方 impl 是否声明了与 provider 抽象方法逐字节相同描述符的 getTransport」，`--impl` 缺失或文件不存在一律记 MISS（fail-closed）。反向验证：摘掉 api 形态重载 → installed 制品 `RESULT MISSING 1`、用例 FAILED，而 transforms 制品仍 OK（证明按形态判别）。**仍未验**：真机上两线各自「EP 真被注册 / 不抛 AbstractMethodError / SSE 通道可达」。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **假绿**（本任务最大风险） | 若护栏断言的是桩（本项目提供）而非真制品，则永远绿 | 断言目标**必须**是外部制品目录（gradle transforms / 装机插件），严禁用桩类；验收 2 的先红后绿实证 |
| 反射加载依赖面不足 | `URLClassLoader` 的 parent 选择与依赖收集（`annotations.jar`、kotlin-stdlib、同插件其他模块）**未实测** | 列为**待验证前提 1**——实施第一步用 262 JBR 实跑一次；若依赖不足，退化为「纯 Kotlin 解析 class 常量池」（零平台依赖，参照 `AcpIdeaMcpTransport.kt:7-8` 的既有导出手法） |
| 发现面在 CI 缺失 | gradle transforms 里 `com.intellij.ml.llm-262.9437.276` 的产出机制**未在 `build.gradle.kts` 找到显式声明**（grep `ml.llm` 仅命中注释） | 列为**待验证前提 2**——若 CI 无该制品，发现面退化为装机插件目录（本地覆盖面缩小），须在 KDoc 写明覆盖范围，不得宣称「跨版本全覆盖」 |
| 覆盖范围被夸大 | 只有 262 家族两个 build，不能声称「跨 243/253/262」 | 口径修正 2 已定：写「本机全部存在的 ml-llm 制品」；KDoc 如实登记覆盖面 |
| 与桩清单守卫冲突 | 若误改桩文件会触 `AcpTransportScopeSourceScanTest.kt:100-111` 硬清单 | 本任务**不动桩**（桩签名已与真制品一致）；若确需动须同步该断言 |

## 七、实施顺序

```
T-MLS-01（checker 与反射加载实测——先用 262 JBR 跑通）→ T-MLS-02（四项断言）→ T-MLS-03（测试类与发现面）
  → 先红后绿实证（验收 2）→ T-MLS-04（分工写清）→ T-MLS-05（文档与登记）→ 真机走查 §五（留人工）
```

**顺序理由**：T-MLS-01 的「反射加载是否跑得通」是本任务的技术前提，先证伪再写断言。

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-MLS-01 | 待提交（T-Round10-09） | **反射加载可行性实测证伪 → 改走纯字节码解析**（待验证前提 1 的结论）：① `@ApiStatus.Internal` 是 CLASS retention（`lib/annotations.jar` 的 `ApiStatus$Internal` 自身 = `@Retention(CLASS)`），在 provider 上落 `RuntimeInvisibleAnnotations`——JBR 25.0.4 实跑 `getAnnotations()` 只回 `[@kotlin.Metadata(...)]`，断言 ③ 用反射**不可能**成立；② `AcpIdeaMcpTransport` 引 `kotlin/enums/EnumEntriesKt`（ml-llm 全部 jar 都不含 kotlin-stdlib，该类只在发行版 `lib/util-8.jar`），`Class.forName(...,true)` 报 `NoClassDefFoundError: kotlin/enums/EnumEntriesKt`、provider 报 `NoClassDefFoundError: com/intellij/openapi/extensions/ExtensionPointName`（裸 ml-llm 目录跑）；挂满发行版 515 个 lib jar 后两类可载且 `getEnumConstants()=[STDIO, SSE]`，但 ③ 仍读不到注解。故 checker 只读 class 文件字节（零平台依赖），并用测试 JVM 自带 java 运行（ml-llm 制品目录里没有 jbr） |
| T-MLS-02 | 待提交（T-Round10-09） | 四项断言的最终形态见 §三（断言 ③ 改为读 `RuntimeInvisibleAnnotations` 属性位；断言 ② 改为读 `ACC_ENUM` 字段而非 `getEnumConstants()`；断言 ①/④ 按期望包名分组解析）。真制品比对：装机 262.10968.97 → `AcpAgentId` 在 ml-llm jar 内（`com.intellij.ml.llm.agents.acp.api`）；transforms 262.9437.276 → 描述符指向 `com.intellij.platform.acp.AcpAgentId`，类在配对发行版 `idea-2026.2.1-win` 的 platform-acp-plugin jar 内（该 ml-llm 目录零命中），由 companion 根补挂并打印来源。**另登记一条未验事实**：装机制品的 provider 形参包（`ml.llm.agents.acp.api`）与我方 `AcpIdeaMcpTransportProviderImpl.kt:3` 实现的包（`com.intellij.platform.acp`）不同——字节码级取证属实，运行时后果（EP 类链接是否失配）**未在真机验证** |
| T-MLS-03 | 待提交（T-Round10-09） | 发现面实测命中 2 处：`~/.gradle/caches/9.7.0/transforms/6378bc26…/transformed/com.intellij.ml.llm-262.9437.276/ml-llm`、`%APPDATA%/JetBrains/IntelliJIdea2026.2/plugins/ml-llm`；companion 命中 3 处（transforms `idea-2026.2.1-win`/`idea-2026.2.3-win` 的 platform-acp-plugin + 装机 IDE 同名插件）。**未发现第三处制品形态**，未知包名一律判红（fail-closed）。`assumeTrue` 跳过路径实测：`-Duser.home=<空目录>` + `APPDATA/LOCALAPPDATA` 置空 → `run=1 failures=0 assumptionFailures=1`（跳过而非失败） |
| 先红后绿 | 待提交（T-Round10-09） | **红 A（checker 层，枚举常量期望改 `[STDIO]`）**：两处制品均 `RESULT MISSING 1`、退出码 1。**红 B（测试层，transforms 期望包名改成 `ml.llm.agents.acp.api`）**：transforms `RESULT MISSING 2`（描述符漂移 + 类找不到）、装机仍 OK、测试 `Failures: 1`——证「分组逻辑真的在起作用」。**还原后绿**：两处 `RESULT OK`、`OK (1 test)`；Gradle 定向跑 `test --tests …AcpSignatureCrossVersionTest` → `tests=1 skipped=0 failures=0 errors=0`。两次读数均已留档（见提交说明） |
| T-MLS-04 | 待提交（T-Round10-09） | 分工说明落在 `AcpSignatureCrossVersionTest` 类头 KDoc「与既有守卫的分工」段；指南补行落位 `docs/架构设计/03-开发与测试指南.md:242`（§4.8-8 子条，实测命中行；计划原写 `:205`/`:241` 系 off-by-one），并同步 §3.2 类数表（`acp` 3 → 4、磁盘类数 495 → 496，checker 非 `*Test` 不计） |
| T-MLS-05 | 待提交（T-Round10-09） | 按任务说明改派：CHANGELOG / 变更记录 / 第九轮报告 §9.4 / ACP 验收 §七-7 由 T-Round10-08 统一写（这四个文件不在本任务写集，计划原文与之矛盾，已在 §三 改口径）。上游 issue 决策（是否向 JetBrains 报 `claude-agent-acp` stdio 判断缺陷）**留给用户** |

**待验证前提（实施者先证伪再动手）**：

1. **反射加载可行性——已证伪**（结论与证据见上表 T-MLS-01 行）。改走字节码解析，且因此不再需要发行版 JBR。
2. **gradle transforms 里 ml-llm 制品的产出机制——本机存在但 CI 未判定**：本机实测该制品存在（全机仅此一处 ml-llm transforms），`build.gradle.kts` 无显式声明这条未复核；若 CI 无此制品，发现面自动退化为装机插件目录（`assumeTrue` 跳过或只覆盖一处），**不得宣称「跨版本全覆盖」**。
3. **`assumeTrue` 跳过与门禁口径——已确认**：沿用既有两条护栏的 `assumeTrue`；跳过计入 skip 不计入 failure（实测 `assumptionFailures=1`、`failures=0`），门禁读数的 skip 数会 +1（本机有制品时不跳过）。
4. **~~新增未验点（实施期发现，交真机/后续任务）~~ → 2026-09-22 第二轮收口：字节码级已闭环（独立复核 major⑯）**：装机制品 provider 形参包与我方 impl 实现的包不一致——当时护栏按「制品→期望包名」登记表如实分组、**不**断言「我方 impl 与该制品接口同形」，故该漂移不会让护栏转红（假绿）。现 ① 实现改为跨形态（两个 `AcpAgentId` 形态各一声明，两条发行线各自匹配，安全前提＝JVM 层实测（带缺席形参类型的额外重载不影响加载/实例化/接口判定/调匹配方法，仅显式方法反射失败）+ 平台层实测（两发行版 `com.intellij.openapi.extensions` 包 69 个类零方法反射））；② 护栏补断言⑥（`--impl` 核 impl 是否声明 provider 抽象方法的同描述符方法，缺失即 MISS）。**真机半边仍未验**（§五-5）：EP 真被注册 / 不抛 AbstractMethodError / SSE 通道可达。
