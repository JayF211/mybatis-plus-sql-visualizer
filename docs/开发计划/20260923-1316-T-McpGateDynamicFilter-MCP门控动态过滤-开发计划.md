# T-McpGateDynamicFilter（O-68）：MCP 门控动态过滤（McpToolFilterProvider EP）

## 一、立项背景（2026-09-23 真机实测 + 字节码取证）

### 1.1 缺陷现象（用户报障 → 实测复现）

用户在日常 IDE 关闭插件设置页「启用 SQL Visualizer MCP 工具」（总开关 + 十工具全不勾，应用后 `IntelliJIdea2026.2/options/mp-sql-visualizer.xml` 落盘 `mcpToolsEnabled="false"`，13:02 mtime），随后：

- 外部 MCP 客户端**新连接**（非缓存）`tools/list` 仍返回全部 10 个 `sqlv_*`（13:03 实测，82 工具照旧）；
- `tools/call sqlv_render_logs` **照常执行成功**，idea.log 落 `[MCP-AUDIT] tool=sqlv_render_logs status=OK`（13:05 实测）。

即：插件门控（`McpToolGate`）对平台外部 MCP 会话完全未生效，设置页承诺的「关闭后插件全部工具从平台 MCP Server 消失；下一次工具列表或调用即生效，无需重启」不成立。

### 1.2 根因（mcpserver.jar 字节码取证，本机 262.10968.63 的 `plugins/mcpserver/lib/mcpserver.jar`，791 类全扫）

- 外部会话工具清单路径 = `McpServerService.getMcpToolsFiltered`：基座清单 + **`McpToolFilterProvider` EP 过滤层** + 设置页偏好（「公开的工具」页的已启用/仅路由器复选框）。该链**不调用** `getEnabledToolsets`。
- `getEnabledToolsets`（即 `McpToolset.isEnabled()` 唯一消费口，791 类中仅 `McpToolset$Companion` 定义 + `impl.ReflectionToolsProvider` 调用）不在外部清单路径上——`ReflectionToolsProvider` 疑似只在 `McpServerService` 初始化时构建基座清单用一次（本 IDE 会话 12:48:47 启动时门控仍开，故基座含全部 10 工具；13:02 关门控后无任何路径重读 `isEnabled`）。
- 平台在过滤层留了**官方动态过滤口**：EP `com.intellij.mcpServer.mcpToolFilterProvider`（`McpToolFilterProvider.Companion.getEP` 的 ldc 字符串实证，与 `mcpToolset` 同族驼峰命名），接口核心 = `applyFilters(McpToolFilterContext, …)`；`McpToolFilterContext` 持 `tools`（`Iterable<McpTool>`）并以 `toolStates: Map<McpTool, McpToolState>` 表达逐工具状态；`McpFilteredToolsListProvider` 构造参数含 `useFiltersFromEP`，并经 `addExtensionPointListener` + `MutableStateFlow` 订阅过滤层变化（`Emitting MCP tools update: reason=…`）——预期支持动态生效。

### 1.3 用户定案（2026-09-23）

采用 `McpToolFilterProvider` EP 方案（官方扩展口，而非继续依赖已被证实不在外部路径上的 `isEnabled`）。

## 二、目标与非目标

**目标**：插件设置页「启用 SQL Visualizer MCP 工具」总开关 + 工具级禁用清单，对平台外部 MCP 会话（SSE / Streamable HTTP、路由器模式）**真正生效**——关闭后 `sqlv_*` 从 `tools/list` 消失、直呼被拒，且**无需重启 IDE**（预期：下一次工具列表刷新/新会话即生效，与现状文案口径一致）。

**非目标**：
- 不改平台「公开的工具」页偏好层的行为（已启用/仅路由器是用户在平台页的手动配置，与插件门控正交，各自独立生效）;
- 不动 `McpToolset.isEnabled()` 三钩子（`ReflectionToolsProvider` 路径仍可能消费它，保留无害，且一工具一 toolset 类的结构不变）。

## 三、方案

1. **纯逻辑决策核**（零平台依赖，沿用 `McpToolGate` 的设计理由——243 测试平台无 mcpserver 插件，mcp-optional.xml 整文件不加载，单测必须能裸跑）：在 `McpToolGate` 增加决策函数（输入工具名，输出 `KEEP / HIDE` 之类的三态或布尔），复用现有 `TOOL_NAMES`/`isEnabled` 口径，含大小写不敏感比对。
2. **平台适配薄壳**：新增 `mcp/SqlVisualizerToolGateFilterProvider`（命名守 `SqlVisualizer` 前缀约定）实现 `McpToolFilterProvider`：`applyFilters` 遍历 context 的 tools，仅对 name 以 `sqlv_` 开头的工具按决策核改写 `McpToolState`，**其余工具原样透传不误伤**。
3. **注册**：mcp-optional.xml `<mcpServer.mcpToolFilterProvider implementation="…"/>`。253 线 EP 存在性是决策点，见 §六-1。
4. **mcpStub 桩源集扩充**：`McpToolFilterProvider` + `McpToolFilterContext` + `McpToolState`（+ 签名涉及的 `McpToolInvocationMode` / `io.modelcontextprotocol.kotlin.sdk.types.Implementation` / `CoroutineScope` 等，按 §六-2 定案的反编译签名照抄）。
5. **文案与注释订正（行为闭环验收后落）**：
   - bundle `settings.mcp.enable.master.desc`（中英两份）：承诺改回与实际行为一致；
   - `McpToolGate.kt` / `McpToolsetSupport.kt` KDoc 中「平台每次 getTools 重新求值……真机走查核实」的断言订正为分路径事实（isEnabled 只在 ReflectionToolsProvider 路径，外部路径走 FilterProvider EP）；
   - `McpConfigurable.kt:26` 附近「平台侧工具过滤仍是另一独立维度，本页只管插件自有工具」补一句正解指引。
6. **文档站同步（随本计划一并交付，见 §七）**。

## 四、改动清单（文件级）

| 文件 | 动作 |
|------|------|
| `mcp/SqlVisualizerToolGateFilterProvider.kt` | 新增（薄壳，mcp-optional 加载域） |
| `mcp/McpToolGate.kt` | 增纯逻辑决策函数 + KDoc 订正 |
| `META-INF/mcp-optional.xml` | 增 EP 注册（或改程序化注册，见 §六-1） |
| `build.gradle.kts`（mcpStub 区） | 桩源集补新类型 |
| `McpToolsetSupport.kt` / `McpConfigurable.kt` | KDoc/注释订正 |
| `messages/MpSqlVisualizerBundle(.properties/_zh)` | `settings.mcp.enable.master.desc` 订正 |
| `PlatformSignatureCrossVersionTest`（如判登记） | 新平台接口实现按「跨版本签名护栏」定例评估 |
| 新增纯逻辑单测 | 决策核：门控开/关/单工具禁用/非 sqlv 工具透传 |
| 文档站仓 4 页 + docs/变更记录.md | 见 §七 |

注意：动 `build.gradle.kts` 触发「构建校验」档（大改动判据命中），验证预算按三档分层上浮。

## 五、验证与验收

1. **反向验证（红线，逐条两读数）**：
   - 修复后：门控关 → 新连接 `tools/list` 无 `sqlv_*`；直呼 `sqlv_render_logs` 被拒；门控开 → 全部恢复；
   - 摘掉 FilterProvider 注册 → 复现 §1.1 缺陷（列表照旧）——证明效力来自本 EP 而非平台缓存波动；
   - 非 sqlv 工具数量前后不变（82 ↔ 82−10+82 恢复，逐字对账，防误伤平台工具）。
2. **定向**：决策核单测（testFast 桶，零平台依赖）。
3. **真机走查**：本机 262.10968 + demo 项目；脚本 `C:\Users\USER\AppData\Local\Temp\mpsv-mcp-test.mjs` 可复用（`MCP_BASE` 环境变量可指定端口）；重点：设置切换 → **不重启** → 新连接观察列表/调用；已连接旧客户端的可见时机如实记录（预期由平台的 StateFlow 推送或客户端刷新决定，写进验收注记）。
4. **构建校验**：因动 build.gradle.kts（mcpStub），按三档分层跑 verifyPlugin/verifyGate（发版前档）。
5. **253/262 双线**：253 分支包或沙盒验证 EP 注册不炸加载（若走 §六-1 的 B 路线则验证 Class.forName 探测与注册）。

## 六、待验证前提（实施者先证伪再动手）

1. **253 线（IDEA 2025.2）是否存在 `com.intellij.mcpServer.mcpToolFilterProvider` EP**：
   - 存在 → mcp-optional.xml 直接声明（A 路线）；
   - 不存在 → 声明式注册会在 253 炸整个 optional 配置文件加载，改 **B 路线**：程序化注册（启动时 `Class.forName("com.intellij.mcpserver.McpToolFilterProvider")` 探测 + `ExtensionPointName` 反射 `registerExtension`，IPGP 反射桥先例；类加载域仍收在 mcp-optional 域内）。**本机 262.10968 与沙盒 262.9437 双点先验 EP 名一致后再动手**。
2. **`applyFilters` 精确签名**：已实证 context 首参 + `Implementation`（clientInfo）参与；是否 suspend、是否有 `CoroutineScope`/`McpToolInvocationMode` 参数、返回类型（Unit 改写 context？还是返回工具清单？）——反编译 `McpToolFilterProvider.class` 方法描述符定案后再写壳。`McpToolState` 的字段/枚举值（能否表达「隐藏 vs 禁用」）同样反编译定案。
3. **过滤合成语义**：多个 FilterProvider 与设置页偏好的叠加顺序；字节码中 `getMcpToolsFiltered` 的 `none` 分支语义（无 provider 认领 = 保留？）——决定我们「只动 sqlv_*、其余透传」的写法是否天然安全，用真机非 sqlv 工具数量对账钉住。
4. **生效时机与调用路径**：直呼 `tools/call` 是否与列表同源被滤（字节码上 `getMcpToolsFiltered` 应为同源，真机验收钉住）；已连接客户端的更新推送形态（`Emitting MCP tools update` 的 reason 枚举）。
5. **基座清单是否会话级快照**（12:48 启动时门控开 → 13:02 关 → 无重读的现象一致）：若是，`isEnabled()` 三钩子在基座构建后就不再被外部路径消费——KDoc 订正按此口径写；重启判别实验（带关闭态重启 IDE）可作实施前顺手证伪。
6. **mcpStub 桩与运行期真类的签名一致性**：桩只是编译期占位（不进产物 jar），但方法描述符必须与运行期一致，否则 262 真机 `AbstractMethodError`/`NoSuchMethodError`——按 `McpDbSignatureCrossVersionTest` 先例评估是否加签名护栏断言。

## 七、文档站同步（随本计划一并）

**背景**：文档站当前在册两笔 MCP 口径（`64f1cbc`）中「设置页门控」相关表述继承自插件的错误承诺，需等本任务行为闭环后统一订正；且文档站**尚未部署**（wrangler 认证待用户侧处理），订正编辑完可一次部署。

| 文件 | 调整 |
|------|------|
| `features/mcp.md` + `en/features/mcp.md` | 「设置页门控」节：开关独立性说明保留；「关掉插件开关……`sqlv_*` 从工具清单消失」句按闭环后事实改写（生效时机如实：新连接/刷新即生效，已连接客户端时机如实记录） |
| `guide/configuration.md` + `en/guide/configuration.md` | MCP 节开关独立性一条同步订正 |
| 文档站仓 `docs/变更记录.md` | 表顶追加一行（标注部署状态） |

## 八、实施记录

### 8.1 §六 待验证前提的证伪结果（全部动手前完成）

- **前提 1（253 EP 存在性）→ 证伪，A/B 路线均未采用，改走「探测式程序化注册」**。本地三条线 mcpserver.jar 实测：`idea-2025.3.5`（253）**无** `McpToolFilterProvider` 类；`idea-2026.2.1`（262.9437）与 `idea-2026.2.3`（262.10968）**均有且签名逐字节一致**。原计划的 A′ 变体（独立片段 `mcp-filter-optional.xml` + 根节点 `since-build="262"` + 第二条 optional depends）**实测踩雷**：`verifyGate`/`verifyPlugin` 报 `There is a duplicated dependency on 'com.intellij.mcpServer'` 结构缺陷——同插件依赖不可声明两次（三个目标线一致），且双片段加载形态本身不可靠。**最终路线**：删除独立片段与第二条 depends，改为 `McpGateFilterRegistrar`（ProjectActivity，挂既有 mcp-optional.xml）内 `Class.forName("com.intellij.mcpserver.McpToolFilterProvider")` 探测，命中才经 `McpGateFilterEpBridge`（独立顶层 object，262-only 类型只在探测通过后触达）做 `ExtensionPointName.getPoint(null).registerExtension(...)` 类型化注册；253 探测失败静默跳过。**修正了原计划「A 路线直接声明」的判断——片段级 since-build 并不能规避 depends 重复这一结构约束。**
- **前提 2（applyFilters 签名）→ 钉死**：`(McpToolFilterContext, Implementation, McpServerService.McpSessionOptions, McpToolInvocationMode): void`，非挂起（262 字节码方法描述符）。
- **前提 3（McpToolState 形态）→ 钉死**：`enabled: Boolean` + `routerOnly: Boolean` 两字段 data class；`McpToolFilterContext` 公开面仅 `updateState(state, filter)` / `updateState(enabled?, routerOnly?, filter)` 两个重载 + `onTools`/`routerOnlyTools` 结果集；类内**无** `getDescriptor`/`getName` 常量 ⇒ 谓词收 **McpTool 对象**（取名走 `tool.descriptor.name`，`McpToolDescriptor.name` 字段实证）。
- **前提 4（活路径）→ 钉死**：平台自带两个实现 `DisallowListBasedMcpToolFilterProvider` / `IndividualRegistryKeyMcpToolFilterProvider`（设置页偏好即走此层），EP 名 `com.intellij.mcpServer.mcpToolFilterProvider` 取自 `McpToolFilterProvider$Companion.getEP` 的 ldc 常量。
- **前提 5（调用方）→ 钉死**：`McpServerService.getMcpToolsFiltered`（`$i$a$-filter-McpServerService$getMcpToolsFiltered$*` 合成类实证）+ `McpFilteredToolsListProvider`（订阅 `ExtensionPointListener` + `MutableStateFlow`，`Emitting MCP tools update: reason=…`）。
- **前提 6（桩签名一致性）→ 真机事故实证，代价一条**：见 8.3。

### 8.2 实现与验证读数

- 新增：`SqlVisualizerToolGateFilterProvider`（薄壳）、`McpGateFilterRegistrar`（探测注册）、`McpGateFilterEpBridge`（类型化注册桥）、`McpGateFilterRegistrationGuardTest`（结构守卫 3 例）、`SqlVisualizerToolGateFilterProviderTest`（空参回归）；`McpToolGate` 增 `TOOL_NAME_PREFIX` + `shouldHideFromExternalToolsList` 决策核与 KDoc 分路径订正；`McpToolsetSupport`/`McpConfigurable` KDoc 订正；`mcp-optional.xml` 挂 registrar；`dev-support/verifier-ignored-problems.txt` 增 253 未解析类豁免规则。
- **反向验证（红线）**：① 篡改 `TOOL_NAME_PREFIX`（`sqlv_`→`sqlvX_`）→ `McpToolGateTest` 恰 2 例红（`test外部过滤决策_总开关关_自家全隐藏外来不误伤`、`test外部过滤决策_工具级禁用_仅该工具隐藏`），其余 6 例不受牵连；还原后 8/8 绿。② 空 clientInfo 用例在桩改可空前以**编译期红**呈现（`Null cannot be a value of a non-null type 'Implementation'`）——缺陷形态本身即读数。
- **定向测试**：`McpToolGateTest` 8/8 绿、`McpGateFilterRegistrationGuardTest` 3/3 绿、`SqlVisualizerToolGateFilterProviderTest` 1/1 绿。
- **构建校验（大改动档）**：`verifyGate` 三目标——IU-262.10968 ≈ 12 分钟（含 3 目标并行）。**待回填**：重构后 verifyGate 结论（重构前一轮为 262 Compatible / EXIT=0，但带 duplicated-dependency 结构缺陷，已作废）。
- **装机形态**：待 `buildPlugin` 出 zip 交用户真机复验（设置页非空白 + 门控关→列表空 + 门控开→十个工具回）。

### 8.3 真机事故：设置页整面空白（自伤，非发布缺陷）

- **现象**：用户装含未提交改动的构建后，「工具 → MCP 服务器 → 公开的工具」右侧整面空白；禁用插件即恢复。
- **根因（idea.log SEVERE 堆栈实证，14:01:46 / 14:02:52 两次）**：`NullPointerException: Parameter specified as non-null is null: method SqlVisualizerToolGateFilterProvider.applyFilters, parameter clientInfo`，栈为 `applyFilters ← McpServerService.getMcpToolsFiltered ← McpToolFilterConfigurable.rebuildToolGroups ← createComponent ← ConfigurableCardPanel`。**平台在设置页无客户端会话时以 `clientInfo=null` 调用过滤 EP**，而编译桩把参数标成非空 → 实现类入口 `Intrinsics.checkNotNullParameter` 判空 NPE → 卡片创建失败整页空白。
- **方法论教训（已写入桩头）**：**javap/字节码解析看不到 Kotlin 空源性（只在 @Metadata）**——桩头原自述「签名权威＝javap 字节码」，正是本次缺口的来源。空源性权威升级为「运行时实参 + 平台自带实现的同源形态」；三个环境型参数一律可空（可空只影响元数据、不影响 JVM 描述符与覆写匹配）。
- **豁免/边界**：该缺陷只存在于未提交的开发构建，**对外发布的 2026.9.23（不含 O-68 代码）无此问题**（Marketplace 上架仍是 2026.9.14，2026.9.23 尚未上线）。

### 8.4 文档站同步

- `features/mcp.md` + `en/`、`guide/configuration.md` + `en/`：门控节订正为「经平台工具过滤扩展动态摘除、下一次列表/新会话生效」；文档站仓 `docs/变更记录.md` 追行。提交号与部署状态待回填。
