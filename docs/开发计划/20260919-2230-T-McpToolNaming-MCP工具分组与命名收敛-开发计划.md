# T-McpToolNaming MCP 工具分组与命名收敛 — 开发计划

- 日期：2026-09-19 22:30
- 任务编号：T-McpToolNaming
- 来源：用户 2026-09-19 反馈——「MCP Server 里我的插件 MCP 太分散了，不容易被用户看到，是不是分组名应该带上插件名，并且工具名前缀也调整下，现在是 mpsv 好像，可以考虑改成 sqlv」（补充：「或者 sv 开头也可以考虑」）
- **用户裁决（2026-09-19，本计划定案依据）**：分组收敛走**方案 B**（类名加插件名前缀，**保留平台侧逐工具门控**）；工具名前缀定为 **`sqlv_`**。原候选「方案 A 合并单类」与「`sv_` 前缀」均不采用，仅作对照留档（见 §三）
- 本轮范围：**仅立项出计划，不实施**（用户明确）。实施另起任务时按 §七 顺序执行
- 性质：**可发现性改进 + 破坏性重命名**。分组收敛只影响 UI 呈现；前缀重命名是面向 agent 的**公开契约变更**，牵动设置迁移与全部文档
- 实施分支：建议自 master 拉 `t-mcp-tool-naming`
- 验证基线：master（实施时以当次实测为准）

---

## 一、背景与依据（两处现状核实，决定方案形态）

### 1.1 「太分散」的根因：分组名就是 toolset 类名，而当前是一工具一类

平台「暴露工具」页按工具描述符的 **category** 分组，而 category 的 `shortName` 直接取自 **toolset 类的 simpleName**（反编译 `mcpserver.jar` → `com/intellij/mcpserver/impl/util/ToolsetReflection_utilKt.asTools`）：

```java
String var10002 = $this$asTools.getSimpleName();       // ← UI 上的分组名
String var10003 = $this$asTools.getQualifiedName();
McpToolCategory category = new McpToolCategory(var10002, var10003, isExperimental, alwaysIncluded);
```

`McpToolCategory` 的字段即 `(shortName, fullyQualifiedName, isExperimental, alwaysIncluded)`，`McpToolFilterConfigurableKt.buildCategoryGroups` 以它为 key 分组渲染。

而本插件当前是**一工具一 toolset 类**（`RenderSqlTool`、`CheckSqlTool`、`CrudSqlTool`…共 10 个），因此 UI 上呈现为**10 个互不相邻的分组**——这就是用户看到的「分散」。

> 该设计是**刻意的**，见 `mcp/McpToolsetSupport.kt:18-22` 与 `mcp-optional.xml` 头注：平台 per-tool 门控只有 **toolset 粒度**（`McpToolset.isEnabled()`），所以「一工具一 toolset 类 + 基类三钩子字面量覆写」是拿到**平台侧逐工具开关**的唯一办法。分组分散是它的代价。

**补充事实**：`McpToolset` 接口本身**没有名字属性**（反编译确认只有 `isEnabled` / `isExperimental` / `alwaysIncluded` 三个 default 方法），所以**无法通过接口自定义分组名**——分组名只能由类名决定。

### 1.2 前缀重命名的影响面

- 工具名在代码里是**显式写死**的：10 处 `@McpTool(name = "mpsv_xxx", title = "...")`（`mcp/` 包各 Tool 类）。
- **门控设置按工具名存**：`mcp/McpToolGate.kt:33-35` 读 `state.mcpToolsEnabled`（总开关）+ `state.mcpDisabledTools`（**名字列表**）。改名后旧名字不再匹配 → **用户此前禁用的工具会全部变回启用**，需迁移。
- **全仓引用 68 个文件**（`grep -rl mpsv_` 去 build）：README、CHANGELOG、`docs/变更记录.md`、`docs/架构设计/`、历次开发计划等。
- **文档站另有一处**：`features/mcp.md` 与 `en/features/mcp.md` 的「十个工具」表格逐个列出 `mpsv_*` 名字，属对外公开面。

## 二、目标与非目标

**目标**：① 让插件在「暴露工具」页里**一眼可辨识、成组相邻**（方案 B：10 个 toolset 类名统一加 `SqlVisualizer` 前缀）；② 工具名前缀由 `mpsv_` 改为 **`sqlv_`**，并在同一次发行内完成设置迁移与文档同步。

**非目标**：不改工具语义、参数与只读契约（归各自既有任务）；不改 `[MCP-AUDIT]` 审计格式；不改门控机制本身（`McpToolGate` 逻辑原样，只处理键名迁移）；不引入新工具。

## 三、方案

分两个**可独立取舍**的子项，先定 A 再定 B。

### T-MTN-01 分组收敛（**定案：方案 B**）

| 方案 | 做法 | UI 效果 | 代价 | 采纳 |
|------|------|---------|------|------|
| A 合并单类 | 10 个 `@McpTool` 方法并入一个 toolset 类（如 `SqlVisualizerToolset`） | **1 个分组**，组名即类名 | **平台侧 per-tool 门控塌缩为 1 个开关**——「暴露工具」页无法逐工具启停，插件设置页的逐工具勾选与平台列表不再对应 | ✗ 不采用 |
| **B 类名加前缀** | 保留一工具一类，类名统一加插件名前缀 | 仍是 10 组，但**组名自带插件名且字典序相邻**，扫视即知同属一插件 | 改动小、**门控能力零损失** | **✓ 定案** |
| C 嵌套类 | 把 Tool 类收进同一外部类的嵌套类 | `getSimpleName()` 对内层类只返回内层名，**分组名不变** | 无效 | ✗ 不采用 |

**定案依据**：平台侧逐工具门控（插件设置页「MCP 工具」子页的总开关 + 逐工具勾选 ↔ 平台「暴露工具」页逐项状态）是本插件 MCP 门控的既有卖点，A 方案会把它塌缩为整组开关，属能力回退；B 以最小改动达成用户要的「可辨识 + 相邻」。

**落地做法（B）**：10 个 toolset 类统一加前缀 `SqlVisualizer`——

```
RenderSqlTool       → SqlVisualizerRenderSqlTool
CheckSqlTool        → SqlVisualizerCheckSqlTool
CrudSqlTool         → SqlVisualizerCrudSqlTool
…（其余七个同理）
```

基类 `mcp/McpToolsetSupport.kt` 与 `src/main/resources/META-INF/mcp-optional.xml` 中十处 `implementation` 的全限定类名同步修改；类名只影响分组名，**不改工具名**（工具名归 T-MTN-02）。

### T-MTN-02 前缀重命名（**定案：`sqlv_`**）

| 候选 | 优点 | 缺点 | 采纳 |
|------|------|------|------|
| **`sqlv_`** | 与插件品牌一致（插件名 SQL Visualizer、内部命名 `SqlVisualizer*`），语义可读 | 比 `sv_` 长一字符 | **✓ 定案** |
| `sv_` | 最短 | 过于泛化，多插件共存时易与别家前缀混淆；可读性弱 | ✗ 不采用 |
| 保持 `mpsv_` | 零迁移 | 用户已明确要改 | ✗ 不采用 |

**定案依据**：`sqlv_` 与插件既有品牌一致——插件名即 SQL Visualizer，内部类/动作命名均为 `SqlVisualizer*`，工具名前缀与之对齐后，agent 侧看到的工具名与插件身份一致；`sv_` 过短且泛化，在「一台 IDE 挂多个 MCP 工具集」的场景下辨识度不足。

**改名映射**（十个工具，一一对应）：

```
mpsv_render_sql      → sqlv_render_sql
mpsv_schema_context  → sqlv_schema_context
mpsv_explain         → sqlv_explain
mpsv_render_logs     → sqlv_render_logs
mpsv_check_sql       → sqlv_check_sql
mpsv_rollback_plan   → sqlv_rollback_plan
mpsv_perf_advice     → sqlv_perf_advice
mpsv_crud_sql        → sqlv_crud_sql
mpsv_reverse_sql     → sqlv_reverse_sql
mpsv_mapper_check    → sqlv_mapper_check
```

**实施要点**：

1. **代码**：10 处 `@McpTool(name = ...)` 批量改名；`mcp-optional.xml` 若有名字相关注释同步；
2. **设置迁移（必做，否则用户已禁用的工具会静默复活）**：`PluginSettings` 加载时对 `mcpDisabledTools` 做一次**旧名→新名映射**（`mpsv_x` → `sqlv_x`），一次性、幂等；迁移逻辑需单测覆盖（含「新旧混存」「未知名保留」两种输入）；
3. **文档同步**：插件仓库 68 个引用文件中的**活文档**（README / `docs/架构设计/` / `docs/变更记录.md`）更新；**历次开发计划与 memory-archive 不改**（历史留档，改了反而失真），在 `docs/变更记录.md` 记一行「旧名已废弃」即可；
4. **文档站同步**：`features/mcp.md` + `en/features/mcp.md` 的十工具表逐个改名，并在页内加一句「旧前缀 `mpsv_` 已更名为 `sqlv_`」（对外契约变更必须让用户看到）；
5. **CHANGELOG**：按破坏性变更书写（`Changed` 段 + 迁移说明），因为已配置过 agent 提示词/白名单的用户需要同步改名。

## 四、验收标准

1. 「暴露工具」页中本插件的十个分组**全部相邻且组名含插件名**（`SqlVisualizer*`，方案 B）；
2. 十个工具在 MCP 服务端仍可被 `tools/list` 拉到，且新前缀正确；调用产生 `[MCP-AUDIT]` 且工具名为新名；
3. **设置迁移有效**：升级前被禁用的工具，升级后仍为禁用（单测 + 真机各验一次）；
4. 平台侧逐工具门控在 B 方案下**行为不变**（设置页勾选 ↔ 平台列表一致）；
5. 插件仓库活文档与文档站中英两页的名字全部更新，无遗留旧名；
6. 既有门禁（testShards / verifyPlugin）通过。

## 五、真机走查清单（留人工）

- 沙盒内 `Settings → Tools | MCP Server → Exposed Tools` 目视分组名与相邻性；
- 插件设置页「MCP 工具」子页逐工具勾选 ↔ 平台列表状态一致；
- 通过任一 agent（终端 `claude mcp list` 或 ACP）实调一次新名工具，确认审计行工具名为新名。

## 六、风险

| # | 风险 | 影响 | 应对 |
|---|------|------|------|
| R1 | 改名破坏用户既有配置（agent 提示词、MCP 白名单、`mcpDisabledTools`） | 用户侧静默失效 | 设置迁移（T-MTN-02 要点 2）+ CHANGELOG 破坏性变更说明 + 文档站显式提示旧名 |
| R2 | 类名改造牵连测试与守卫——工具级门控测试**直接实例化 Tool 类**（见 `build.gradle.kts` 中 mcpStub 接线注释：「test 编译同样需要桩（工具级门控测试直接实例化 Tool 类）」） | 改名后编译失败或守卫失配 | 类名与测试引用同步改；改完跑 testShards 全量，不只看单模块 |
| R3 | 类名加前缀后与平台其他 toolset 类名风格不一致 | 观感 | 前缀统一为插件名，且该命名只影响本插件自己的类 |
| R4 | 文档同步遗漏（68 个文件 + 文档站两页） | 新旧名并存，用户困惑 | 验收标准 5 显式检查；活文档与历史留档区别对待 |
| R5 | 改名与 T-AcpMcpTransport 等并行任务在同一批文件上冲突 | 合并冲突 | 两个任务分属不同文件面（本任务动 `mcp/*Tool.kt` 的注解与类名，T-AcpMcpTransport 动 build.gradle.kts 与新增 EP 配置），实施时先合并 master 再改 |

## 七、实施顺序

> 方案已定案（**B + `sqlv_`**）。以下为另起实施任务时的执行顺序；**本轮不实施**。

1. T-MTN-01 类名改造（纯机械：10 个 toolset 类加 `SqlVisualizer` 前缀 + `mcp-optional.xml` 十处全限定名，编译即验）；
2. T-MTN-02 工具名改造（10 处 `@McpTool(name = ...)`）+ **设置迁移 + 单测**（迁移是本任务唯一有状态的部分，单独一轮，先写测试再改代码）；
3. 文档同步（插件仓库活文档 → 文档站中英两页 → CHANGELOG 破坏性变更段）；
4. 真机走查（第五节三项）。

## 八、实施记录

（待实施后回填）

## 九、实施小结：偏离、阻塞与未验证项

（待实施后回填）
