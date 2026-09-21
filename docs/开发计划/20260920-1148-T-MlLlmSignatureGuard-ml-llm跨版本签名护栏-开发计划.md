# T-MlLlmSignatureGuard ml-llm 跨版本签名护栏 — 开发计划

- 日期：2026-09-20 11:48
- 任务编号：T-MlLlmSignatureGuard（第九轮报告 §9.4 **O-41**，P2）
- 来源：ACP 验收文档 §七-7 待决策项——**`@ApiStatus.Internal` 类型的漂移没有任何自动化门禁**：verifier 对 optional 依赖走「不可用」分支、完全不做类型解析，三个目标平台都没有 AI Assistant（依赖树逐目标记 `(failed) com.intellij.ml.llm (optional): Unavailable`），故 ACP 绕行所依赖的 internal 类型漂移不会让任何门禁转红
- 性质：**测试基建新增护栏**——不改产品代码、不改 ACP 绕行实现；为「上游修复后绕行可移除」提供唯一自动信号
- 实施分支：自 master 拉任务分支 t-ml-llm-signature-guard
- 验证基线：master `22d7c49`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0 + verifyGate 三目标 Compatible）

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
- **铁律 KDoc**：`:9`「新增反射入口必须登记跨版本签名护栏」；文档侧 `docs/架构设计/03-开发与测试指南.md:205`/`:241`。

**非同类（勿混入）**：`util/TableConfigSignatureTest.kt` 名称含 Signature 但是**缓存签名语义测试**，不是跨版本护栏。全仓 `CrossVersion` 命中仅上述两处。

### 1.2 断言清单的来源（桩 KDoc 已登记，可直接复用）

| 桩文件 | 行数 | 已登记内容 |
|---|---|---|
| `src/mcpStub/kotlin/com/intellij/ml/llm/agents/acp/api/AcpAgentId.kt` | 33 | `:11-17` 取证方法与出处（装机路径 + 宿主 `IU-262.10968.63` + JBR-25 `javac` + `java.lang.classfile` 导出）；`:19-21` **刻意偏离**（桩不声明 `sealed`）；`:27-33` 声明 `interface AcpAgentId { val rawId: String; val fullId: String }` |
| `.../client/AcpIdeaMcpTransport.kt` | 16 | `:7-8` 真类为 `Enum`（ACC_ENUM\|ACC_FINAL\|ACC_PUBLIC），常量**恰** STDIO/SSE、无 Companion；`:13-16` 声明 |
| `.../client/AcpIdeaMcpTransportProvider.kt` | 27 | `:8-19` 真类为接口、标 `@ApiStatus.Internal`、无父接口、唯一抽象方法 `getTransport(AcpAgentId): AcpIdeaMcpTransport?`、另有 Companion 静态 `resolveTransport`；EP 声明在 ml-llm 自身 `plugin.xml` 第 5426 行、自带实现在 5445 行；`:21-27` 声明 |

**独立复核已做过**（262.10968.63 装机 jar 常量池实测）：`AcpAgentId` 含 `getRawId/getFullId/REGISTRY_PREFIX/"acp.registry."`；`AcpIdeaMcpTransportProvider` 含 `getTransport (AcpAgentId)AcpIdeaMcpTransport` + `org/jetbrains/annotations/ApiStatus$Internal`——**桩登记与真制品一致**。

**本机 ml-llm 制品实测（两处）**：

- `~/.gradle/caches/9.7.0/transforms/6378bc26…/transformed/com.intellij.ml.llm-262.9437.276/ml-llm/lib/modules/intellij.ml.llm.agents.acp.jar`
- `%APPDATA%/Roaming/JetBrains/IntelliJIdea2026.2/plugins/ml-llm/lib/modules/intellij.ml.llm.agents.acp.jar`（宿主 `build.txt` = `IU-262.10968.63`）

两个 jar 的 `AcpIdeaMcpTransportProvider.class` 类文件 **major 版本均为 69**。

### 1.3 关键约束（决定实现路径）

- **不能用「桩类可加载」代替真类签名断言**：桩刻意与真类偏离（不 `sealed`，`AcpAgentId.kt:19-21`），且桩由本项目提供，**永远「通过」**——这正是假绿的经典形态。
- **反射加载可行性**：ml-llm 类 major 69 只有 262 发行版 JBR(25) 能载；该 JBR **只有 `java.exe`、无 `javap`/`jshell`**（实测）⇒ 只能照 McpDb 模式「纯 Java checker（`--release 17` 字节码）+ 发行版 JBR 运行 + `URLClassLoader` 反射」，或改走纯 Kotlin 解析 class 常量池（零平台依赖）。
- **守卫联动**：`acp/AcpTransportScopeSourceScanTest.kt:100-111` **硬列三个桩文件路径**——若新增桩文件或改动桩清单须同步该断言；`config/PluginXmlStructureGuardTest.kt:235`/`:257`（optional 依赖声明与 provider 注册成对断言、主描述文件不得引用 ml-llm 类型）在动 optional 文件时须同步。

## 二、目标与非目标

**目标**：① 给 ACP 绕行所依赖的 ml-llm internal 类型建立跨版本签名护栏，使上游漂移**可自动发现**；② 上游修复落地时，护栏成为「绕行可移除」的唯一自动信号；③ 把「签名护栏」与「作用域守卫」的分工写清。

**非目标**：不改 ACP 绕行实现（`acp/AcpIdeaMcpTransportProviderImpl.kt` 不动）；不改桩的声明（桩与真类签名一致，不需要动）；不检测上游 `@agentclientprotocol/claude-agent-acp` 是否已修（npm 包不在本仓依赖图，无自动检测面——登记）；不替代真机走查（「ml-llm 在场时 provider 真被注册 / SSE 通道可达」仍只能真机，见 §五）。

## 三、方案

**T-MLS-01（checker，纯 Java 外部进程）**：新增 `src/test/java/MlLlmAcpSignatureChecker.java`，照 `McpDbSignatureChecker.java` 形态：`main(发行版目录或 jar 目录)` → 收集 ml-llm 制品 jar → `URLClassLoader` 反射校验 → 输出 `RESULT OK` / `MISSING n` + 退出码。

**T-MLS-02（四项断言，全部来自桩已登记内容）**：
1. `AcpAgentId` 为**接口**且含抽象 `getRawId()` / `getFullId()`；
2. `AcpIdeaMcpTransport` 为 **`Enum`** 且 `getEnumConstants()` **恰为 `[STDIO, SSE]`**（常量增减即红——这正是「绕行前提变化」的信号）；
3. `AcpIdeaMcpTransportProvider.getTransport(AcpAgentId)` 返回类型为 `AcpIdeaMcpTransport`，且类上存在 `@ApiStatus.Internal`；
4. 常量 `"acp.registry."` 前缀仍可读（对上我方 `AcpIdeaMcpTransportProviderImpl.kt:65` 的 `CLAUDE_ACP_ID` 拼接依据）。

**T-MLS-03（测试类与发现面）**：新增 `src/test/kotlin/.../acp/AcpSignatureCrossVersionTest.kt`，照 `McpDbSignatureCrossVersionTest.kt:47-87` 的发现 + 执行逻辑，**发现面改为**：
- `~/.gradle/caches/*/transforms/*/transformed/com.intellij.ml.llm-*/ml-llm/` 目录；
- `%APPDATA%/JetBrains/IntelliJIdea*/plugins/ml-llm/`（装机插件目录）。

无制品则 `assumeTrue` 跳过（照既有形态，不硬失败——因为 243/253 平台本就没有 AI Assistant）。

**T-MLS-04（分工写清）**：在测试类 KDoc 中写明与既有守卫的分工——`acp/AcpTransportScopeSourceScanTest` 管**作用域**（ml-llm 类型引用限定在 `visualizer/acp/` 包、META-INF 只允许 `acp-transport-optional.xml`、桩类型只允许在 `mcpStub`），本护栏管**签名**。并在 `docs/架构设计/03-开发与测试指南.md` 的护栏清单（`:205`/`:241` 附近）补一行。

**T-MLS-05（文档与登记）**：CHANGELOG（测试基建类，按既有惯例视口径决定是否入）；变更记录行；第九轮报告 §9.4 O-41 落地备注；ACP 验收文档 §七-7 的待决策项标注为「已由本任务闭合一半」（护栏落地），另一半（是否向 JetBrains 报 `claude-agent-acp` 判断缺陷）**留给用户决策**。

## 四、验收标准

1. 护栏在本机**实跑一次**，对本机两处 ml-llm 制品均输出 `RESULT OK` 且退出码 0（若某处制品缺 ACP jar 则该处记 MISSING 并如实登记，不得静默通过）。
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
| T-MLS-01 | （实施后填） | 反射加载实测结论（parent/依赖面）须回填；若退化走常量池解析须说明 |
| T-MLS-02 | （实施后填） | 四项断言的最终形态与真制品比对结果须回填 |
| T-MLS-03 | （实施后填） | 发现面实际命中的制品清单须回填 |
| 先红后绿 | （实施后填） | 两次读数须回填 |
| T-MLS-04 | （实施后填） | 分工说明落位与指南补行行号须回填 |
| T-MLS-05 | （实施后填） | 上游 issue 决策须回填（用户定） |

**待验证前提（实施者先证伪再动手）**：

1. **反射加载可行性未实测**——`URLClassLoader` 的 parent 选择与依赖收集是否足够，本计划未跑（只做了静态分析）。这是第一个待证伪点。
2. **gradle transforms 里 ml-llm 制品的产出机制未确认**（`build.gradle.kts` 无显式声明）——若它不由 IPGP 自动下载，则 CI 与部分机器无此制品，护栏覆盖面须相应收窄并如实登记。
3. 「`assumeTrue` 跳过」与我方门禁口径是否一致——既有两条护栏都用 `assumeTrue`（`McpDbSignatureCrossVersionTest.kt:26`/`:37`），沿用即可；但须确认跳过不计入「失败」的读数口径（否则门禁读数的 skip 数会变，须登记）。
