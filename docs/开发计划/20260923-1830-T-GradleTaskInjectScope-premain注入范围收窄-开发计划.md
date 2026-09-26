# T-GradleTaskInjectScope（暂占 O-73）：premain 注入范围收窄（路线 A，按任务语义）

> 盘点 §2.5 立项、用户指示按路线 A 直接实施（2026-09-23）。本任务此前只有登记没有计划文档，本文档为补立。

## 一、背景

v2026.9.23 起插件含 premain 出门：`PremainRunConfigurationExtension`（EP `com.intellij.runConfigurationExtension`，plugin.xml 已注册）在**所有** Java 运行配置的 `JavaParameters` 里写 `-javaagent`。Gradle 集成再把参数经 init script 传播到 Gradle fork 出的 JavaForkOptions 任务 JVM——结果 `runIde`、`test` 等 IDE/测试任务的 fork JVM 也被挂上 agent，已判定「不合适」。现网用户受影响，范围拍板优先级高。

## 二、路线 A 定义（用户已拍板，按任务语义收窄）

- **测试任务可辨 → 不注入**：Gradle `test`/`check`/`verification` 族、Maven surefire（`surefire:test`）等；
- **runIde 类 IDE 任务按任务名排除 → 不注入**：`runIde`、`runIde2`、`buildSearchableOptions`、`prepareSandbox`（含 2.x 的 `prepareUiSandbox`）、`verifyPlugin*` 等 IDE/构建验证族；
- **保留真实应用运行任务 → 注入**：`bootRun`/`run`/`quarkusDev`、Maven `spring-boot:run` 等；
- **非 Gradle/Maven 的普通 Java 应用运行配置（Spring Boot 等 ModuleBasedConfiguration）行为完全不变**：仍走既有四闸门注入。

语义方向：**宁 skip 勿错注入**。skip 的代价是该次运行回落 attach/桥链（零劣化，既有降级路径）；错注入的代价是 agent 污染不相干 JVM（本任务要修的缺陷）。故多任务组合（如 `test bootRun`）与大小写存疑时一律按 skip 处理。

## 三、落点

1. **判定核（纯函数，新文件）** `log/agent/PremainTaskScope.kt`：
   - `decide(isExternalSystemConfiguration: Boolean, taskNames: List<String>): Decision`——输入配置形态标识 + 任务名列表，输出 `Inject` / `Skip(reason)`（照 `PremainInjection.REASON_*` 常量形态；新原因码 `external-test-task` / `external-ide-task`）；
   - 任务名归一化：小写 + 取最后一个 `:` 后段（` :app:test`→`test`、`surefire:test`→`test`）；表驱动闭集 + 文档化前缀（`test*`、`verifyPlugin*`），任一任务命中 skip 族即 skip；
   - 非 external-system 配置：直接 `Inject`（行为零突变面）。
2. **平台探针（薄壳）**：同对象 `decideFor(configuration)`——`configuration is ExternalSystemRunConfiguration` 直取 `settings.taskNames`，探针任何异常按 `Inject` 处理（回落既有行为，零劣化）。
3. **接入点**：`PremainRunConfigurationExtension.updateJavaParameters` 在 `manager.preparePremainVmOption()` **之前**先过范围闸门（skip 时不开 ServerSocket、不落盘 jar）。
4. **不收窄 `isApplicableFor`**（恒 true 保持）：在那里收窄会把 Spring Boot 等 ModuleBasedConfiguration 一并挡在门外（既有 KDoc 已论证）。
5. **零突变红线**：`PremainInjection.kt` 四闸门与其测试不动；`AgentManager.kt` 只读不改；attach 链不动；`AgentProtocolSyncTest` 帧守卫不触；plugin.xml 不动。

## 四、平台类核实结论（2026-09-23 javap 实证）

| 类 | 243（2024.3.7.1 distro） | 262（本机 IU-262.10968.63） |
|----|----|----|
| `ExternalSystemRunConfiguration` | `lib/app-client.jar`（平台核心） | `lib/intellij.platform.externalSystem.impl.jar`（平台核心） |
| `ExternalSystemTaskExecutionSettings` | 同上 | `lib/intellij.platform.externalSystem.jar`（平台核心） |
| 关键签名 | `getSettings()` / `getTaskNames(): List<String>` | 同左，逐字一致 |

**直接编译，不用反射**：243 编译 classpath 含 `app-client.jar`（同 jar 的 `RunConfigurationBase` 是本扩展既有编译依赖，编译绿即证明在 classpath）；两版本签名一致、类属平台核心（非可选插件 API），无 McpGateFilterEpBridge 那类「Marketplace verifier 未解析类」风险。**无新增反射入口 → `PlatformSignatureCrossVersionTest` 不需登记**（该护栏只核反射探测入口）。

## 五、验证与验收

- **定向测试**（新增 `PremainTaskScopeTest`，表驱动）：test/check/verification/surefire 族 skip；runIde 族 skip；bootRun/run/quarkusDev/spring-boot:run inject；普通（非 external-system）配置 inject；边界（大小写、`:test`/`:app:test` 前缀、`clean bootRun` 多任务组合、空表）。
- **反向验证**：每个 skip 分支「摘掉分支判定 → 必红」——测试家族摘除一次、IDE 家族摘除一次，各留红读数后复原复绿。
- **零突变确认**：`PremainInjectionTest`、`AgentProtocolSyncTest` 照常跑绿（定向带上）。
- **验收标准**：Gradle/Maven 测试任务与 IDE 任务的 fork JVM 不再被写入 `-javaagent`；bootRun 类应用任务注入行为与今日一致；普通 Java 运行配置行为与今日逐字节一致（同链路四闸门语义不变）。
- 纪律：只跑定向测试；禁全量/构建校验/clean/--stop/降片。

## 六、待验证前提（实施者先证伪再动手）

1. ~~平台类可编译性~~ 已核实（§四）。
2. 「external-system 配置的 taskNames 覆盖 Maven goal」（`surefire:test` 形态）——归一化取 `:` 后段天然覆盖，测试锚定。
3. 多任务组合「任一命中即 skip」是否误伤 `clean bootRun`——`clean` 不在两族表内，inject 保留，测试锚定。

## 七、真机留人工（headless 做不了）

1. **坐实 fork JVM 注入（改前基线）**：真机 runIde 沙盒起一次本插件开发的 Gradle 项目 `test` 任务，取证 fork JVM 命令行含 `-javaagent`（改前缺陷在真机的原始读数）。
2. **收窄后真机行为**：同一场景复跑，取证 test/runIde fork JVM 无 `-javaagent`、`bootRun` fork JVM 仍有；另跑一次 Spring Boot（ModuleBasedConfiguration）确认注入不变。
3. Gradle daemon 复用同 sandbox 时 init script 缓存对参数传播的影响（若有异常，查 `ExternalSystemTaskExecutionSettings.JVM_AGENT_SETUP_KEY` 路径）。

## 八、实施记录

**2026-09-23 车道 `t-premain-scope`（工作树 mpsv-premain-scope），实施完成，待真机留人工条目复核。**

### 8.1 落地产物

| 文件 | 内容 |
|------|------|
| `log/agent/PremainTaskScope.kt`（新增） | 纯函数判定核，**零平台依赖**（测试源码零 `com.intellij` 引用，天然进 testFast 快桶）。原因码 `external-test-task` / `external-ide-task`；归一化 = trim+小写+取末个 `:` 后段再 trim；测试族闭集 11 名 + `test*` 前缀，IDE 族闭集 5 名 + `verifyplugin*` 前缀（逐条依据见源内 KDoc） |
| `log/agent/PremainRunConfigurationExtension.kt` | `updateJavaParameters` 范围闸门前置（skip 早退，**不**触发 jar 落盘与 ServerSocket）；新增 `decideScope` 平台探针（`is ExternalSystemRunConfiguration` 直引 + `runCatching`→探针异常按 Inject 回落既有行为，FatalErrors 透传）。`isApplicableFor` 恒 true 不变，plugin.xml 不动 |
| `PremainTaskScopeTest.kt`（新增） | 4 测试 32 行表驱动：非 external 恒注入 / 测试族 skip / IDE 族 skip / 应用任务注入 / 大小写与 `:` 前缀与 `clean bootRun` 组合边界 / 原因码字面量钉死 |

零突变红线确认：`PremainInjection.kt`、`AgentManager.kt`、attach 链、plugin.xml 均未动。

### 8.2 验证读数（testFast 定向，全程未跑全量/构建校验）

| 轮次 | 分支状态 | 结果 | 关键读数 |
|------|---------|------|---------|
| 基线绿 | 两族分支齐全 | `BUILD SUCCESSFUL`，PremainTaskScopeTest+PremainInjectionTest+AgentProtocolSyncTest = 4+4+4 全绿 0 跳过 | compileKotlin 直引 `ExternalSystemRunConfiguration` 编译通过——**直接编译路线成立**（§四 javap 预判被编译器证实） |
| 实验 A | 摘测试族分支 | `BUILD FAILED`，2/4 红 | `external=true tasks=[test] expected:<skip:external-test-task> but was:<inject>`（归一化用例同红；IDE 族行仍绿——分支独立可证） |
| 实验 B | 摘 IDE 族分支 | `BUILD FAILED`，1/4 红 | `expected:<skip:external-ide-task> but was:<inject>`（`runIde` 行起全族红；测试族行仍绿） |
| 复原绿 | 两族分支齐全 | `BUILD SUCCESSFUL`，4+4+4 全绿 0 跳过 | PremainInjectionTest（既有四闸门）/AgentProtocolSyncTest（帧守卫）零突变确认 |

实施中发现并修掉的两个判定核缺陷（被自家表驱动用例当场拦下）：① `: m: TEST ` 带空格形态末段 trim 缺失会漏判；② `failsafe:integration-test` 归一化产物 `integration-test` 不在初版闭集——均已修并补用例。

环境噪音登记：首轮 gradle 撞代理单点掐 TLS（`SSLHandshakeException: Remote host terminated the handshake`，ProductReleasesListingValueSource），重试 1 次即过，非代码问题。

### 8.3 PlatformSignatureCrossVersionTest 登记结论

**不登记**。该护栏核的是反射探测入口的跨版本签名；本任务平台类编译期直引（编译器即签名闸门）、无新增反射面，两类签名已双版本 javap 一致（§四）。

### 8.4 真机留人工（承接 §七，headless 无法执行）

1. 改前基线坐实：真机 Gradle 项目跑 `test` 任务，取证 fork JVM 命令行含 `-javaagent`（缺陷原始形态）。
2. 收窄后走查：`test`/`runIde` fork JVM 无 `-javaagent`；`bootRun` fork JVM 仍有；Spring Boot（ModuleBasedConfiguration）注入行为不变。
3. 若 Gradle daemon 复用同 sandbox 出现参数传播异常，查 `ExternalSystemTaskExecutionSettings.JVM_AGENT_SETUP_KEY` 路径。
