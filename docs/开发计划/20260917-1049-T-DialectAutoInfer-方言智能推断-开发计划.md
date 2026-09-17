# T-DialectAutoInfer 方言智能推断 开发计划

> 日期：2026-09-17 10:49
> 状态：立项待实施
> 前置调研：本计划成文前已实测代码位（文件:行 见各节）

## 一、需求

当前设置中「数据库方言」默认硬编码 `MySQL`（`PluginSettings.State.DEFAULT_DIALECT`）。插件已有捕获增强（M56 agent 字节码增强，connect 事件携带 JDBC URL），具备自动识别目标库类型的能力。需求：

1. 方言默认改为**智能推断**：跟随实际数据源（agent 捕获的连接 URL 为首选依据）。
2. 设置页**显示当前推断结果**（如「当前推断：PostgreSQL（来自最近连接）」）。
3. 用户手动选定具体方言后，按用户指定为准，不再覆盖；可随时切回智能推断。

## 二、现状实测（代码位）

| 关注点 | 位置 | 现状 |
|---|---|---|
| 方言持久化 | `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt:36,424` | `var dialect: String = DEFAULT_DIALECT`，`DEFAULT_DIALECT = "MySQL"` |
| 方言注册表 | `sql/Dialect.kt:381-411` | 9 方言，`byName` 未知名称回退 `MySqlDialect` |
| 渲染期消费 | `ui/SqlPreviewService.kt:1560-1568` | `currentDialect()` = `byName(effectiveDialectName())`，项目级优先回退全局 |
| PSI 层消费 | `psi/PsiDialect.kt:15` | `currentDialect()` runCatching 兜底 MySQL |
| 项目级覆盖 | `config/ProjectPluginSettings.kt:40,84-85` | `dialect` 空串=不覆盖，`effectiveDialect()` 项目级优先 |
| 设置 UI（旧面板） | `config/PluginSettingsPanel.kt:182,440,1113,1198,1300` | `JComboBox(DialectRegistry.dialects.map{it.name})` |
| 设置 UI（新页） | `config/ProjectPluginSettingsConfigurable.kt:43-57,77,83-84` | Kul DSL combo，空串回显全局值 |
| 推断数据源 | `log/agent/AgentEvent.kt:35` + `log/agent/AgentEventAdapter.kt:38` | agent connect 事件携带 `url`；`JdbcUrlDialects.displayName(url)` 已能从子协议映射 16 种显示名（未知→null） |
| 导入导出 | `config/SettingsIO.kt:30,74` | `dialect` 字段已在 JSON 序列化白名单 |
| 搜索索引 | `config/PluginSettingsFolderConfigurable.kt:410` | `settings.dialect` 已注册 SearchableField |

推断基础件 `JdbcUrlDialects`（`log/agent/JdbcUrlDialects.kt`）是事实展示层（面板角标用），**不能**直接照搬做渲染配置——其映射名（如「DM（达梦）」）与 `DialectRegistry` 的 `Dialect.name` 不是同一命名空间，须新增子协议→`Dialect.name` 的映射或复用同一张表加双列。

## 三、方案定案

### 3.1 数据模型（PluginSettings.State）

```kotlin
/** 方言模式：true=智能推断（默认，跟随捕获到的数据源）；false=用户手动指定 state.dialect。 */
var dialectAuto: Boolean = true,
/** 手动方言（仅 dialectAuto=false 时生效；沿用现字段与既有持久化，迁移零成本）。 */
var dialect: String = DEFAULT_DIALECT,   // DEFAULT_DIALECT 语义降级为「手动模式默认值」
```

- **不加新字段 `dialect` 的「AUTO 魔法值」**：`dialect` 字段现被 `byName` 直接消费，注入哨兵值会污染 9 处消费点的空值语义（`byName` 回退 MySQL 的行为被 PSI 层兜底逻辑依赖）；用独立布尔开关正交表达模式，旧字段语义收窄为「手动值」，持久化/导入导出/项目级覆盖全部兼容。
- **迁移**：旧用户无 `dialectAuto` 字段 → XmlSerializer 缺省 true。语义变化点：老用户从未动过方言（值=出厂 MySQL）将自动进入推断模式；动过方言的用户值原样保留但仍被切到推断模式——**这是有意的产品行为**（推断结果与其手动值通常一致，且推断更准），CHANGELOG 明示。

### 3.2 推断链（新文件 `sql/DialectInference.kt`）

```kotlin
object DialectInference {
    /** 推断结果：dialect 命中（Dialect.name），basis 为空=未命中。 */
    data class Result(val dialectName: String?, val basis: String?)
    fun infer(project: Project?): Result
}
```

优先级从强到弱：

1. **agent 最近连接 URL**：`AgentManager` 最近一次 connect 事件的 `url` → 子协议 → `Dialect.name`（新增 `SUBPROTOCOL_TO_DIALECT` 映射：`postgresql`→PostgreSQL、`dm`→DM、`kingbase8`→KingBase…与 `JdbcUrlDialects.SUBPROTOCOL_NAMES` 同键空间，命名空间改为方言名）。basis=「最近连接 jdbc:xxx」。
   - 多数据源混连：取**最近一次** connect（捕获时间序），与预览面板「当前执行的是哪条连接」语义一致；不做按 SQL 归属连接的复杂路由（一期不做，见 §六）。
2. **项目数据源**：DatabaseView/`DatabaseConnection` API（Ultimate-only，runCatching 包裹，Community 静默跳过）取首个已连接数据源的 URL，同上映射。basis=「数据源 xxx」。
3. **未命中**：`dialectName=null` → 消费方回退 `MySqlDialect`（保持现兜底不变），设置页显示「当前推断：未检测到连接，暂按 MySQL」。

纯逻辑层（子协议→方言名映射、优先级合并）与平台查询层分离，映射表进单测。

### 3.3 消费点改造（全部收口到统一读取函数）

新增 `PluginSettings.effectiveRenderDialect(project): Dialect`（或挂 `DialectRegistry`）：

```kotlin
fun effectiveRenderDialect(): Dialect {
    if (!state.dialectAuto) return DialectRegistry.byName(state.dialect)
    return DialectInference.infer(null).dialectName?.let { DialectRegistry.byName(it) }
        ?: DialectRegistry.byName(null)  // 未命中回退 MySQL，与旧行为一致
}
```

- `SqlPreviewService.effectiveDialectName()`（:1565）：`dialectAuto=false` 时走旧逻辑（项目级覆盖→全局手动值）；`dialectAuto=true` 时项目级 `dialect` 仅在项目**显式关闭**其自身 auto 时生效——项目级设置页同步加同一开关（`ProjectPluginSettings.State` 加 `dialectAuto: Boolean?`，null=跟随全局，保持其「空=不覆盖」语义）。
- `PsiDialect.currentDialect()`（:15）：改调统一读取（保留 runCatching 兜底 MySQL）。
- `DialectPreMapping`：执行前映射固定 set MySQL 的既有逻辑**不动**（那是 GenericSQL PSI 解析需要，与渲染方言正交），仅确认推断结果非 MySQL 时不必改映射——现状即「预映射只服务解析」，零改动。

### 3.4 设置 UI

两处 UI 同步（旧面板 + 新 Kul DSL 页，须保持同文案键同形态，遵照既有 UI 一致性约定）：

- `settings.dialect` 下拉框顶部加首项 **「智能推断（跟随数据源）」**（bundle 新键 `settings.dialect.auto`）。
- combo 选中「智能推断」→ `dialectAuto=true`；选中具体方言 → `dialectAuto=false` + 写 `dialect`。
- 下方加只读状态行（新键 `settings.dialect.inferred`：「当前推断：%s」）：`dialectAuto=true` 时显示 `DialectInference.infer` 结果（含 basis）；手动模式显示「手动指定：%s」。状态行在 `doValidate`/refresh 时惰性刷新（infer 含平台查询，不做监听器常驻）。
- isModified 判定（`PluginSettingsPanel.kt:1113`）同步纳入 `dialectAuto`。

### 3.5 i18n / 文档三处同步

- bundle 双语各加 ≥3 键（auto 项、推断状态行、推断 basis 模板）。
- `SettingsIO.kt` 白名单加 `dialectAuto`。
- 文档站设置页说明 + CHANGELOG `[Unreleased] Changed` 行。

## 四、任务分解

| # | 任务 | 内容 | 预估 |
|---|---|---|---|
| T-DAI-01 | 推断基础件 | `DialectInference` 对象：子协议→方言名映射表（对齐 `JdbcUrlDialects` 键空间扩展）+ 优先级合并纯函数 + 单测（含未知/畸形 URL/多连接取最近） | 0.5d |
| T-DAI-02 | 平台查询层 | agent 最近 connect URL 读取（AgentManager 已有事件流，查是否已有 connect 事件缓存，无则加最近值缓存）；数据源 API 探测（runCatching，Community 降级） | 0.5d |
| T-DAI-03 | 设置模型 | `dialectAuto` 字段 + 项目级 `dialectAuto: Boolean?` + `effectiveRenderDialect()` 统一读取 + SettingsIO 白名单 | 0.5d |
| T-DAI-04 | 消费点收口 | `SqlPreviewService` / `PsiDialect` / `ProjectPluginSettings.effectiveDialect()` 改统一读取；确认 `DialectPreMapping` 零改动 | 0.5d |
| T-DAI-05 | 设置 UI | 双 UI 加「智能推断」项 + 推断状态行 + isModified/apply/reset 三处同步 | 0.5d |
| T-DAI-06 | i18n+文档 | bundle 双语、文档站设置页、CHANGELOG | 0.25d |
| T-DAI-07 | 测试与门禁 | 单测（推断链/迁移兼容/effective 读取）+ 全量 testShards + runIde 真机验证（MySQL/PG 双数据源切换、无连接回退、旧配置升级路径） | 0.5d |

依赖：01→02→03→04 并行 05；06 尾随；07 收口。串行总量约 3.25d。

## 五、验收标准

1. 全新安装（无任何配置）：默认智能推断；连 MySQL 库显示推断 MySQL，换 PG 数据源后新预览按 PG 渲染（分页/引用符/保留字引用正确）。
2. 设置页状态行实时反映推断结果与依据；无连接时显示回退提示。
3. 手动选 Oracle → 推断不再生效；切回「智能推断」恢复。
4. 旧配置升级：手动方言值保留；`dialectAuto` 缺省 true 生效。
5. 项目级覆盖：项目方言页同样有 auto 项，null=跟随全局语义不变。
6. 全量 testShards 绿；三族守卫测试（bundle parity / plugin.xml / 禁用 API）不受影响。

## 六、明确不做（一期边界）

- 不做按 SQL 语句归属连接的精细方言路由（多数据源混连时统一取最近连接）。
- 不做推断结果的自动写回（推断只影响运行期取值，不落盘，避免状态漂移）。
- 不改 `DialectRegistry.byName` 的 MySQL 回退语义（PSI 层兜底依赖它）。

## 七、风险与陷阱预判

- **命名空间双轨**：`JdbcUrlDialects` 显示名（角标用）≠ `Dialect.name`（渲染用），必须两张映射或一表双列，禁止复用错位——否则达梦/人大金仓等中文名方言直接 `byName` 失配回退 MySQL。
- **`DialectPreMapping` 只 set MySQL**：推断出非 MySQL 时 GenericSQL 解析层仍是 MySQL 语义——现状即如此（手动选 Oracle 也一样），属既有边界，不扩大范围但验收说明里要写清。
- **DataClassShouldBeImmutable State**：新字段加进既有 `data class State`，XmlSerializer 缺省值迁移依赖构造默认值，勿用 `lateinit`。
- **设置页 infer 的平台调用**：数据源 API 探测可能触发读操作，状态行刷新放 EDT 惰性 + runCatching，不进 apply 关键路径。

## 八、实施记录

- 2026-09-17 feature/dialect-auto-infer 工作树实施（主仓 docs 立项 c1fde05，分支六提交 6d5bb34..本提交）：
  - T-DAI-01/02（6d5bb34）：`UrlSubprotocolDialects`（子协议→渲染方言名，与 JdbcUrlDialects 展示名双轨）+ `DialectInference` 优先级合并 + `AgentSession.lastConnectionUrl` 会话记忆 + `AgentManager.lastConnectionUrl()` 活跃会话优先读取 + `DataSourceUrlProbe` 数据源反射探测（262.3 javap 实锤 DataSourceManager→RawConnectionConfig.getUrl）+ 10 例单测绿
  - T-DAI-03/04（823fa9e）：`PluginSettings.dialectAuto`（默认 true）/`ProjectPluginSettings.dialectAuto: Boolean?`（null=跟随全局）/`ProjectPluginSettings.effectiveDialectAuto` + `DialectRegistry.effective` 统一读取 + SettingsIO 导入导出白名单 + `SqlPreviewService.currentDialect`/`PsiDialect.currentDialect` 收口（MySQL 兜底保留）
  - T-DAI-05（25d98d2）：双 UI 哨兵项改造 + 推断状态只读行 + bundle 双语 4 键
  - T-DAI-06：CHANGELOG [Unreleased] Changed 行 + 本节
  - 勘误（对照 §三 偏离）：计划中「effectiveRenderDialect 挂 PluginSettings」落位为 `DialectRegistry.effective`（注册表自持，避免 config→sql 反向依赖）；推断状态行 basis 仅显示 URL 原文（未做 i18n 模板插值，信息等价）
  - 明确不做边界与 §六 一致（DialectPreMapping 零改动实测确认）
