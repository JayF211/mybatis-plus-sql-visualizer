# T-BuildScriptSplit 构建脚本拆分分治 — 开发计划

- 日期：2026-09-17 08:17
- 任务编号：T-BuildScriptSplit
- 来源：用户指令「build.gradle.kts 文件目前非常大 是否可以进行拆分并保留原功能逻辑不变 现在分析需求 生成开发计划文件」
- 性质：纯构建脚本结构重构——**零产品源码改动、零任务名变更、零命令行用法变更**；全部块逐字搬运（注释随迁），行为不变由产物字节比对 + 门禁全绿证明
- 实施分支：master（本工作区）；与 T-MergeDev2Master 的先后关系见 §七风险 R1（两序皆可行，立项实施时定夺）
- 验证基线：拆分前捕获四项指纹（plugin.xml 字节 / tasks --all 清单 / testShards 分区日志行 / 配置缓存复用），拆分后逐项比对 + testShards 全量绿 + verifySmoke 绿

---

## 一、背景与依据

build.gradle.kts 现为 1073 行单文件，混装五类关注点：依赖声明、IntelliJ 平台配置、兼容验证门禁、测试分片基建、发版链路（含 changeNotes 纯数据 ~170 行）。本项目双分支工作流下该文件是两支同颊改动的高频冲突点（dev 现为 1111 行、与 master 差 105 行），每次 T-* 构建类任务都在同一大文件上做局部手术；changeNotes 每次发行都要编辑构建逻辑文件本身。拆分目标不是减行数（逐字搬运总行数近似不变），而是：单文件 1073 → ~300 行按关注点分治、发版数据文件离开构建逻辑、双分支合流面从「单文件交叉 diff」收敛为「以 master 结构为准回植 dev 增量」。

## 二、现状核实（2026-09-17 实测）

### 2.1 master build.gradle.kts 1073 行内容分布（行号即拆分搬运清单）

| 行段 | 内容 | 行数 | 拆分去向 |
|---|---|---|---|
| 1~44 | imports / plugins / gifRecord 开关与发布闸门 | 44 | root（保留） |
| 46~117 | dependencies：测试依赖 + jackson 约束 + jsqlparser + intellijPlatform 平台依赖 | 72 | root（保留） |
| 119~124 | repositories（与 settings 双处并存是 PREFER_PROJECT 默认语义，**不去重**） | 6 | root（保留） |
| 128~187 | intellijPlatform 扩展配置：instrumentCode / searchableOptions / sandboxContainer（:152~186 pluginVerification 部分迁出） | 60 | root 30 + verification |
| 189~205 | verifyPlugin 代理注入 + offline + 豁免清单 | 17 | mpv-verification |
| 207~309 | verifyGate 任务（verifier-cli 直驱） | 103 | mpv-verification |
| 311~320 | kotlin 工具链 + JavaCompile 编码 | 10 | root（保留） |
| 322~349 | withType\<Test\> 类路径剥离（vue/database）+ 堆覆盖 | 28 | mpv-testing |
| 352~364 | withType\<Jar\> MPV-Build 时间戳 | 13 | root（保留，打包身份） |
| 366~429 | tasks{}：plainFjpf 编译打包 / hotswapServerStop / runIde 配置 | 64 | mpv-runide |
| 431~618 | patchPluginXml changeNotes（**纯数据 ~170 行正文**） | 188 | 外置数据文件（见 3.2） |
| 620~643 | verifySmoke / verifyFull / releaseGate | 24 | mpv-verification |
| 645~670 | writeUpdateManifest | 26 | mpv-release |
| 673~701 | 分片辅助：parseTestSuiteAttrs / listSuiteXmls / testShardCount / demo 路径 | 29 | mpv-testing |
| 703~969 | intellijPlatformTesting：runIdeHotSwap（64）/ testShards 分区+注册（110）/ testDatabase（93） | 267 | runide 64 + testing 203 |
| 971~1023 | testShards 聚合报告任务 | 53 | mpv-testing |
| 1025~1029 | mustRunAfter 时序钉死两行 | 5 | root body（保留，见 3.3） |
| 1031~1046 | tasks.test mpvs.* 透传 + DB 集成三类排除 | 16 | mpv-testing |
| 1048~1063 | agent 内嵌（evaluationDependsOn + embedAgentJar + sourceSets 接线） | 16 | root（保留） |
| 1065~1073 | T-TestPerf-03 结论注释 | 9 | mpv-testing（随 testing 语境迁） |

### 2.2 dev 分支对该文件的独有增量（合流回植清单，`git diff master dev -- build.gradle.kts` 实测）

1. mcpStub 源集创建块（`val mcpStub: SourceSet by sourceSets.creating` + sourceSets 配置，~20 行）→ 拆分后落 **root**；
2. compileKotlin / compileTestKotlin / test 三处 mcpStub 桩产物挂载（~18 行）→ 落 **root**；
3. testShardN 注册体内的 mcpStub classpath 一行 → 落 **mpv-testing**；
4. `testImplementation(libs.snakeyaml)` 一行 → 落 **root** dependencies；
5. verify 目标 dev 仍为 2026.2.1（master 已追 2026.2.3，T-VerifyIde2623 遗留）→ 合流时**以 master 为准**。

### 2.3 关键环境事实

- Gradle 9.7.0 + IPGP 2.18.1 + changelog 2.5.0 + 配置缓存开启（org.gradle.configuration-cache=true）；文件内多处注释标注配置缓存约束（执行期 lambda 不得引用脚本对象等），逐字搬运即可继承合规性，改写则可能破坏。
- buildSrc 目录不存在，属首次引入；`.gitignore` 已有全局 `build/`，buildSrc/build 自动覆盖。
- 版本目录 gradle/libs.versions.toml 在用；buildSrc 是独立构建，不吃根 settings 的 pluginManagement，插件依赖版本须在 buildSrc/build.gradle.kts 显式钉死（与 settings 同值并注释声明来源）。

## 三、方案

### 3.1 拆分形态：buildSrc 预编译脚本插件（convention plugins）×4 + 根脚本瘦身

预编译脚本插件保留完整类型安全访问器（`intellijPlatform { }` / `patchPluginXml { }` / `runIde { }` / `intellijPlatformTesting { }` 在 convention 内照常生成），是唯一不需要把现有代码改写成字符串式查找（`tasks.named("...")`）的拆分形态——**改动量最小即风险最小**。

目标结构（行数为估值）：

```
build.gradle.kts                        ~280 行：plugins 应用 / gifRecord 闸门 / dependencies /
                                        repositories / intellijPlatform 核心配置 / kotlin+JavaCompile+Jar /
                                        agent 内嵌 / mustRunAfter 钉死
buildSrc/
  build.gradle.kts                      ~15 行：kotlin-dsl + IPGP/changelog 插件依赖（版本钉死 2.18.1/2.5.0）
                                        + repositories（gradlePluginPortal/mavenCentral，代理沿用 gradle.properties）
  src/main/kotlin/
    mpv-verification.gradle.kts         ~210 行：pluginVerification 三态 / verifyPlugin 代理 /
                                        verifyGate / verifySmoke / verifyFull / releaseGate
    mpv-testing.gradle.kts              ~310 行：withType<Test> / tasks.test / 分片辅助与分区 /
                                        testShard1..N / testDatabase / testShards 聚合 / T-TestPerf-03 结论注释
    mpv-runide.gradle.kts               ~135 行：plainFjpf / hotswapServerStop / runIde / runIdeHotSwap
    mpv-release.gradle.kts              ~40 行：patchPluginXml（changeNotes 外置读取）+ writeUpdateManifest
gradle/plugin-change-notes.html         ~170 行：changeNotes 数据（发版只编辑此文件）
```

root `plugins {}` 块按序应用四个 convention（`id("mpv-verification")` 等，无需版本号）；convention 内各自 `plugins { id("org.jetbrains.intellij.platform") }`（mpv-release 另加 changelog）以取得扩展访问器。`gifRecord`、`testShardCount` 等属性在需要的 convention 内各自重读（providers 读取幂等，不引入跨脚本共享状态）。

### 3.2 changeNotes 外置必须用 providers.fileContents（配置缓存失效语义不能丢）

现状：changeNotes 是脚本内字符串字面量，编辑它即脚本变化 → 配置缓存自动失效 → 下次 buildPlugin 重新 patch plugin.xml。若简单改成配置期 `file(...).readText()`，文件内容变化**不会**使配置缓存失效，发行时改了 html 仍打进旧 changeNotes——这是本拆分唯一可能引入的行为回归点。正确写法：

```kotlin
patchPluginXml {
    changeNotes.set(
        providers.fileContents(layout.projectDirectory.file("gradle/plugin-change-notes.html"))
            .asText.map { it.trim() },
    )
}
```

fileContents provider 把文件登记为配置缓存输入（编辑即失效）；`.trim()` 与现 `trimIndent()` 对产出的效果一致（首尾空白剥离，验收用 plugin.xml 字节比对兜底）。

### 3.3 明确保留项（零行为边界的锚点）

1. **gifRecord 发布闸门**位置与报错文案原样留 root（脚本最前，配置期 fail-fast）。
2. **mustRunAfter 两行留 root body**：它横跨三个 convention 注册的任务（buildPlugin/verifyGate/testShards），root body 在全部 convention 应用之后执行，天然满足「注册后再钉时序」；搬进任一 convention 反而引入应用顺序依赖。
3. **evaluationDependsOn + embedAgentJar + sourceSets 接线留 root**：构建身份级接线，且 dev 合流增量 1/2 要回植于此。
4. **任务名/组/description/日志文案逐字保留**：testShardN START/END 时间戳、SHARD TOTAL 行、verifyGate START/END 行是并行审计锚点（T-TestPerf-05），文案变化会破坏事后核对惯例。
5. **repositories 双处并存不动**：root 的 repositories 块在 PREFER_PROJECT 默认语义下是实际生效方，去掉属行为变更，不在本任务范围。
6. **T-ListParamCollect 临时 sandboxContainer 行**原样保留（任务收尾另行还原，与本拆分解耦）。
7. **所有注释随块迁移**，仅「见上方/见下方」类位置性措辞允许微调；注释是取证文档（字节码实证、缺陷机制记录），搬运时精简改写视同破坏。

### 3.4 落选方案（决策记录）

- **apply(from = "xxx.gradle.kts") 外部脚本**：外部脚本不生成类型安全访问器，每块都要改写成 `tasks.named`/`the<>()` 字符串式查找——改动量最大、IDE 支持最差、配置缓存坑最多。否决。
- **build-logic included build**（settings includeBuild 的 convention 插件形态）：增量特性略优于 buildSrc，但对单项目仓库属过度工程，多一组 settings/plugManagement 接线。否决，留 buildSrc。
- **分片贪心逻辑抽取为纯 Kotlin 类补单测**：现有逻辑三轮实测稳定且已带确定性断言语境，重写即引入回归面。本任务只搬运不改写；抽取留作后续独立任务（不立项）。
- **维持单文件仅外置 changeNotes**：收益 -170 行但不解决分治诉求。changeNotes 外置作为拆分的第一子步吸收进来（T-BS-01）。

## 四、实施子任务

**T-BS-00（基线捕获）**：跑前并行登记 check（铁律）→ `./gradlew buildPlugin`（当前 HEAD 产物）→ 解压 zip 取 patchPluginXml 后的 plugin.xml 字节存临时目录（不入库）→ `./gradlew tasks --all` 清单存档 → `./gradlew testShards -PtestShardCount=3 --dry-run` 捕获分区日志行（`testShards: 3 shards duration-balanced, loads=...`）→ 连续两次 `./gradlew help` 确认第二次「Reusing configuration cache」。

**T-BS-01（buildSrc 骨架 + changeNotes 外置，先独立见效）**：建 buildSrc（仅 build.gradle.kts，暂无 convention）+ `gradle/plugin-change-notes.html`（内容 = 现 trimIndent 内文逐字节）+ patchPluginXml 改 providers.fileContents 读取。验证：buildPlugin 后 plugin.xml 与 T-BS-00 基线字节一致。此步独立合入，即使后续子步中止也已收益 -170 行。

**T-BS-02（mpv-verification）**：搬 189~205 + 207~309 + 620~643 + 128~187 的 pluginVerification 段。验证：`./gradlew verifySmoke` 绿（含 buildPlugin → verifyGate 链路）。

**T-BS-03（mpv-testing）**：搬 322~349 + 673~701 + 778~969 + 971~1023 + 1031~1046 + 1065~1073。验证：`./gradlew help` 配置通过 + 分区日志行与基线一致 + tasks --all 含 testShard1..3/testDatabase/testShards。

**T-BS-04（mpv-runide）**：搬 366~429 + 703~777（runIdeHotSwap）。验证：tasks --all 含 runIdeHotSwap/hotswapServerStop/plainFjpfJar；此任务链无法沙盒内冒烟，留真机走查（§六）。

**T-BS-05（mpv-release + root 瘦身收口）**：搬 645~670（writeUpdateManifest）→ root 清理为 3.1 目标形态。验证：终局门禁全套——buildPlugin 产物 plugin.xml 与 zip 条目清单和基线比对、testShards 全量 0 失败 0 错误、verifySmoke 绿、tasks --all 与基线一致、配置缓存复用、gifRecord 闸门负测试（`./gradlew buildPlugin -PgifRecord=true` 配置期失败且文案一致；**不得**实际执行 writeUpdateManifest，会覆写已发行 update/update.json，其验证靠 tasks 清单与代码审阅）。

**T-BS-06（文档同步 + 台账）**：03 篇 :37 changeNotes 注入描述更新（改为「显式配置、内容外置 gradle/plugin-change-notes.html」——该行现描述「从 CHANGELOG.md 注入」本就与 M18.2 实况不符，顺手纠正）；`grep -rn "build.gradle.kts" docs/发行说明/` 枚举发行两文档提及 changeNotes 编辑位置的行并同步；变更记录.md 总册行 + CHANGELOG.md [Unreleased] Changed 行；记忆 project-pending-plans 补本计划条目。每子任务一个独立提交（`build: T-BuildScriptSplit ...`），保证可 bisect。

## 五、验收标准

1. `./gradlew tasks --all` 任务集合与 T-BS-00 基线完全一致（名字/组/描述不变）。
2. buildPlugin 产物：patchPluginXml 后 plugin.xml 与基线**字节一致**（changeNotes 外置等价性证明），zip 条目名清单一致。
3. `./gradlew testShards` 全量绿（0 失败 0 错误）且分区日志行与基线一致。
4. `./gradlew verifySmoke` 绿。
5. 连续两次构建第二次复用配置缓存；`buildPlugin -PgifRecord=true` 配置期失败报错文案与现状一致。
6. 拆分纯搬运可审计：各 convention 文件与 build.gradle.kts git 历史对应块逐字对得上（注释随迁），零逻辑改写。
7. 03 篇与发行文档同步完成；全程零产品源码/测试源码改动（`git diff --stat` 仅 build.gradle.kts、buildSrc/、gradle/plugin-change-notes.html、docs）。

## 六、真机走查清单（留人工）

1. 用户下次真机会话跑 `./gradlew runIde`：启动日志无 RegionUrlMapper SecurityException 噪音（plainFjpf 链路在拆分后仍生效——runIde classpath 追加与 threadFactory 注入随块迁移的实证）。
2. 可选：runIdeHotSwap 本地清单热替换全链走一遍（自更新免重启），确认 mpv-runide 迁移无损。

## 七、风险

| # | 风险 | 说明 | 缓解 |
|---|---|---|---|
| R1 | dev 合流冲突放大 | dev 对本文件有 2.2 五处独有增量且 T-MergeDev2Master 未做；先拆分则该文件合流从交叉 diff 变结构对齐 | 两序皆可行：**先合流后拆分**（拆分一次成型，推荐若 T-MergeDev2Master 近期执行）；**先拆分后合流**则按 2.2 清单机械回植（以 master 结构为准，verify 目标维持 2026.2.3）。立项时定夺 |
| R2 | convention 内访问器生成失败 | intellijPlatformTesting/patchPluginXml 扩展在预编译脚本内的访问器生成依赖 buildSrc classpath 正确 | T-BS-01 先以最小骨架验证编译，失败块退回 root 保留（拆分粒度可降级、方案不推翻） |
| R3 | 配置缓存语义回归 | 搬运中改写执行期 lambda 可能破坏「不得引用脚本对象」约束；changeNotes 读取方式选错丢失效语义 | 逐字搬运铁律 + 3.2 fileContents 定案 + 每步 help 冒烟 + 终局复用验证 |
| R4 | buildSrc 引入固定开销 | 首次编译 ~30~60s 一次性；buildSrc 变更使配置缓存失效（与今天改 build.gradle.kts 等价，无额外常态成本） | 接受；gradlePluginPortal 依赖解析走既有代理 |
| R5 | 并行会话互踩 | 本机多会话并行开发惯例 | 每次 gradle 命令前 parallel_tasks.py check + register/done（铁律）；每个子任务独立提交，误伤可 bisect |
| R6 | 262 录制态不可全验 | gifRecord=true 下 test 源码编译不过（既有事实），录制态四件套整体切换只能配置期验证 | 只做闸门负测试（fail-fast 文案一致）；录制态真机验证留 GIF 录制任务顺带覆盖 |

## 八、实施顺序

```
T-BS-00（基线四指纹）→ T-BS-01（buildSrc 骨架 + changeNotes 外置，独立见效点）
→ T-BS-02（verification）→ T-BS-03（testing）→ T-BS-04（runide）
→ T-BS-05（release + root 收口 + 终局门禁全套）→ T-BS-06（文档同步 + 台账）
```

每步之间可独立中止：T-BS-01 后中止 = 单文件 -170 行收益落袋；T-BS-0x 任一步验证不过 = 该 convention 退回 root，其余步不受牵连。

## 九、实施记录（2026-09-17 已实施交付，t-buildscriptsplit 六提交 7e3e40f..b586aa1 + 台账提交，工作树 mybatis-plus-sql-visualizer-bs）

**已实施交付（2026-09-17，master @ dc047bd 锚点，分支 t-buildscriptsplit）**：

- **T-BS-00 ✅**：基线四指纹捕获——分区行 `testShards: 3 shards duration-balanced, loads=shard1=115.0s/115classes shard2=115.0s/115classes shard3=115.0s/115classes`、tasks 清单、plugin.xml 33153 字节、zip 7 条目、配置缓存复用。附实测认知：指纹行只在真实配置时打印（配置缓存复用时静默），捕获须 `--no-configuration-cache`。
- **T-BS-01 ✅（7e3e40f）**：buildSrc 骨架 + changeNotes 外置；**新坑**：`providers.fileContents().asText` 走 JVM 平台默认字符集，本机 GBK daemon 把 UTF-8 读成乱码（「集合」→「闆嗗悎」，plugin.xml 膨胀 2679 字节暴露）——改 `asBytes` + UTF-8 显式解码后 plugin.xml 与基线逐字节一致。
- **T-BS-02 ✅（909db98）**：mpv-verification 六块迁移；预编译脚本访问器（intellijPlatform/pluginVerification/named\<VerifyPluginTask\>/register\<JavaExec\>）一次编译全生成，**R2 风险实证通过**；verifySmoke 绿（IU-243 Compatible，2m45s）+ 分区指纹一致。
- **T-BS-03 ✅（bf3c404）**：mpv-testing 迁移；分区指纹与基线逐字节一致（同提交同输入同分区）；verifyGate/releaseGate/testShards/testShard1/testDatabase/hotswapServerStop/runIdeHotSwap/writeUpdateManifest 等 10 任务注册点查全部通过。
- **T-BS-04 ✅（7db1953）**：mpv-runide 四块迁移 + 注册点查通过（runIde 噪音修复与热替换链路真机走查留 §六）。
- **T-BS-05 ✅（b586aa1）**：mpv-release + root 收口 **195 行**（1073→195）；终局门禁全套绿：plugin.xml 的 change-notes CDATA 与 html 逐字节一致（version/sinceBuild/zip 7 条目不变）+ testShards 全量 **4279/0/0/6**（3 片真并行，5m33s）+ verifySmoke Compatible + gifRecord 闸门负测试（配置期 fail-fast，文案与基线一致）+ 配置缓存连续两次复用。
- **T-BS-06 ✅（本提交）**：03 篇 :37（顺带纠正该行原本「从 CHANGELOG.md 注入」的过期描述）、Gitee 发行工作流 :45、Marketplace 流程 :65/:358、变更记录总册行（倒序插顶）、CHANGELOG [Unreleased] Changed 行、本节回填。

**勘误与偏离（诚实记录）**：

1. **§3.2 被实测修正**：计划担心的「fileContents 不登记配置缓存输入导致陈旧」不成立——fileContents 是执行期懒读，编辑 html 即生效；真正的坑是字符集（asText 平台默认 → 必须 asBytes + UTF-8）。方案形态不变，实现按实测落地。
2. **验收标准 1 换径**：`tasks --all` 在基线 dc047bd 即因 embedAgentJar→agentFatJar 的 asmFat 配置解析独占锁报错失败（Gradle 9.7 既有问题，`--no-configuration-cache` 同败，非本拆分引入），「清单一致」改由逐任务 `help --task` 点查 + 分区指纹承担；基线与拆分后同位同因失败本身即一致性证据。既有问题按零行为边界不修，留档待需用时另立项。
3. **基线产物文件灭失**：T-BS-00 存临时目录的 plugin.xml/zip-names 在 T-BS-05 复验前被 Windows 临时目录自动清理——改用更强证明（当前产物 CDATA 与 html 数据源逐字节对照）；T-BS-01/02 阶段的间接基线字节比对已闭环前期等价性，链条无断点。
4. **testShards 绝对数 4279 vs beta.3 4299**：差 20 系 cbec1ee..dc047bd 间 18 文件测试源演化（390+/612-，T-VStack·InAll 重做与 t-uinative 合入）所致——T-VStack·InAll 台账记载同测试源时期串行全量即 4279，与本次分片全量吻合，非拆分丢测。

**遗留**：dev 分支 build.gradle.kts 同款单文件结构留待 T-MergeDev2Master——合流时以 master（本拆分后形态）为准，按计划 §2.2 清单回植 dev 五处独有增量（mcpStub 源集三处→root、testShardN 桩挂载一行→mpv-testing、snakeyaml→root dependencies；verify 目标维持 master 2026.2.3）。
