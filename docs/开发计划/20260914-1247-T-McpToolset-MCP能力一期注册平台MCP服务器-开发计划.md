# T-McpToolset：MCP 能力一期（注册平台 MCP Server 体系）开发计划

- 立项日期：2026-09-14
- 任务性质：新能力立项（N-12，AI agent 生态位卡位）
- 依据文档：`docs/调研报告/DBX 数据库工具调研（内部资料库）` §5（工具面/架构/验收标准）、`docs/调研报告/20260914-0317-IDEA-ACP与平台AI原生能力-调研报告.md` §3.2/§5.2/§5.4（主轨修订依据）、`docs/调研报告/20260914-0211-竞品横向对比与功能建议-第三轮调研报告.md` §8.3 N-12
- 目标：三个只读 MCP 工具（sqlv_render_sql / sqlv_schema_context / sqlv_explain）注册进 IDEA 平台内置 MCP Server，253/262 自动生效，243 明确不支持并写进发行说明

## 一、架构定案（ACP 专项修订后）

**主轨：注册进平台 MCP Server 体系**（`com.intellij.mcpServer` 插件，253/262 内置、1,808 万下载）。自建 stdio launcher 移出一期。

- 注册面：`mcpServer.mcpToolset` 扩展点 + `@McpTool/@McpDescription/@McpToolHints` 注解 DSL（官方 README 明文支持下游插件注册，intellij-community `plugins/mcp-server/README.md` 为 canonical reference）
- 载体：`SqlVisualizerToolset : McpToolset` 单 toolset 三方法
- 可选依赖：`<depends optional="true" config-file="mcp-optional.xml">com.intellij.mcpServer</depends>`（照 kotlin-optional.xml / database-optional.xml / diagram-optional.xml 三先例，plugin.xml:66-78 既有模式）
- 243 策略：MCP Server 1.0.30 旧 EP（`org.jetbrains.mcpserverplugin.McpTool`，HTTP handler 形态，接口包名全不同）薄适配成本 > 收益，**明确不支持**；发行说明与 README 写明「MCP 面需 IDEA 2025.2+（内置）或已装 MCP Server 插件的 2024.3/2025.1」
- 生命周期坑：`isExperimental` 默认 true，须显式覆写为 false 才进稳定工具集（McpToolset.kt KDoc / README 2.3）
- 一次注册自动覆盖全部 client 面：AI Assistant、Junie、经 `use_idea_mcp` 开启的 ACP agent、外部 Claude Code/Cursor 等

## 二、工具面（P1 三工具，全部只读）

| 工具 | 入参 | 出参 | 实现锚点 |
|------|------|------|---------|
| sqlv_render_sql | 项目相对文件路径 + 行号；或源码片段 + 触发面类型 | 渲染后可执行 SQL + 触发面类型 + 降级说明 | 复用 13 类 gutter 定位与 `ui/SqlPreviewService` 渲染链；MCP 线程 → ReadAction（既有线程模型纪律） |
| sqlv_schema_context | 数据源别名 + database/schema + 表名白名单（max_tables 默认 8、上限 20，参照 DBX 口径） | 紧凑表列上下文（含注释） | `ui/highlight/DatabaseSchemaResolver` 既有通道；数据源凭证不出 IDE |
| sqlv_explain | 数据源别名 + 单条 SELECT | 计划文本 | `ui/SqlPreviewPanel.explainSql()` 同链（`renderExplainStatement` 七方言派发，含金仓/达梦） |

三工具名保留 `sqlv_` 前缀（命名空间天然隔离，不与平台 toolsets 冲突）。

## 三、安全红线（DBX 四原则的只读子集）

1. `@McpToolHints(readOnlyHint=TRUE, destructiveHint=FALSE, idempotentHint=TRUE)` 平台原生只读声明
2. SELECT 白名单：JSqlParser（运行时既有依赖）解析校验，非 SELECT / 多语句 / 解析失败一律拒绝（fail closed）
3. 零弹窗：agent 触发不弹任何确认框，拒绝走 `McpExpectedError`（错误文案 agent 可读，参照 DBX `MCP_READ_ONLY` / `SQL_BLOCKED` 语义）
4. 不提供任何连接管理工具（与「不做连接管理」产品定案一致）
5. 审计日志：每次调用记 idea.log（时间、工具、入参摘要、结果状态）
6. 数据源不可达 / 项目未索引：明确报错不挂死；会话空闲超时自动回收（参照 DBX 30 分钟口径）

## 四、实施顺序

1. `mcp-optional.xml` + plugin.xml 可选依赖声明 + toolset 骨架（编译期与 MCP Server 插件解耦，未装环境自动降级）
2. sqlv_render_sql（价值最高，纯复用渲染链，竞品 MCP 给不了的确定性渲染）
3. sqlv_schema_context（max_tables 限幅 + 紧凑格式化 + 截断提示）
4. sqlv_explain（SELECT 校验 + 既有执行链）
5. 审计日志 + `isExperimental=false` + README「外部 agent 接入指南」（`claude mcp add` 与 AI Assistant `use_idea_mcp` 两种姿势）
6. 测试 + 真机验证 + verifyPlugin

## 五、测试与验收（对齐 DBX 专项 §5.3 验收标准）

- 单测：三工具入参校验 / 只读守卫 / 降级文案 / max_tables 限幅 / 未装 MCP Server 时 toolset 不注册
- 越权用例全拒：UPDATE / DDL / 多语句夹带 / 非白名单数据源 → 结构化错误码
- 真机：ZCode 与 Claude Code 实测三工具可用；无数据源 / 未索引明确报错不挂死
- 全流程零主动弹窗；审计日志可查
- verifyPlugin 三目标 Compatible（大功能口径）
- 排序依赖：**排 T-MergeR2IntoMaster 之后**（渲染链锚点在 master 侧持续演进，避免在漂移基线上开发）

## 六、范围外（明确不做）

- P2（sqlv_restore_log 日志还原 / sqlv_recent_executions 执行记录 / CRUD 与资产生成工具）：P1 真机验证后按用户反馈排序
- P3 写通道：无限期搁置；若开必须继承 DBX 验证过的四原则（中央只读策略硬上限 / 客户端环境变量不可放大 / 用户确认与执行逐字绑定 / 解锁窗口只存内存）
- 自建 stdio server：移出一期，二期评估
- N-14 AI SQL 分析：另立任务（OpenAI 兼容单路径 + PasswordSafe 存 key；「ride 平台 LLM API」已被 ACP 专项证伪）

## 七、实施记录（2026-09-14 交付）

### 7.1 核实到的真实 API 面（jar 为唯一权威）

全部签名 javap 自本机 IDEA 2026.2.1 内置 MCP Server 插件
`C:\Users\USER\AppData\Local\Programs\IntelliJ IDEA\plugins\mcpserver\lib\mcpserver.jar`
（IU-262.10315.125；253 分支 Marketplace 包 253.28294.336 的 EP 名与接口包名相同，见 ACP 调研报告 §3.2）。与计划文档记载的差异点：

| 计划记载 | jar 实证 | 结论 |
|---|---|---|
| `@McpTool/@McpDescription/@McpToolHints` | `com.intellij.mcpserver.annotations.McpTool(name, title)`、`McpDescription(description)`、`McpToolHints(readOnlyHint, destructiveHint, idempotentHint, openWorldHint)`，四属性**全部带默认值**（AnnotationDefault 字节码 = UNSPECIFIED） | 一致；openWorldHint 可省 |
| hint 三值枚举 | `McpToolHintValue { UNSPECIFIED, TRUE, FALSE }` | 一致 |
| `McpExpectedError` | `com.intellij.mcpserver.McpExpectedError(String, JsonObject?)` extends Exception，getter `getMcpErrorText()`；另有静态捷径 `McpToolKt.mcpFail(message, JsonObject?)`（$default mask=2 证实第二参默认 null） | 一致 |
| `mcpServer.mcpToolset` EP | mcpserver.jar META-INF/plugin.xml `<extensionPoint name="mcpToolset" interface="com.intellij.mcpserver.McpToolset" dynamic="true"/>`；EP 名 `com.intellij.mcpServer.mcpToolset`（McpToolset$Companion 字节码 ldc 实证） | 一致 |
| `isExperimental` 默认 true | javap -c 三钩子默认实现：`isEnabled=iconst_1 / isExperimental=iconst_1 / alwaysIncluded=iconst_0`，且均为 Java default 方法 | 一致；工具集显式覆写 false |
| Project 获取 | `McpCallInfoKt.getProject(CoroutineContext)` / `getProjectOrNull`（KDoc 的 `coroutineContext.project`） | 一致 |
| 工具方法形态 | 平台自带 ReadToolset.read_file 实证：`suspend fun` + 可序列化参数 + 返回 String；注解出现在 RuntimeVisibleAnnotations 表（**RUNTIME retention 是桩的硬约束**） | 一致 |

`McpToolCallResult.text()/error()` 工厂存在（`(String, JsonObject)` + $default），但注解 DSL 的 suspend 方法直接返回 String 即可（平台自带工具集同款），未使用。

### 7.2 编译期接线（本机构建辅助）

基线平台 IU 2024.3.7.1（243）不含 mcpServer 插件；262 内置 mcpserver.jar 类文件为 **major 69（Java 25）**，JDK 17 编译读不了（javac 17/21 报「错误的版本 69.0」，与 build.gradle.kts T-GifRecordSwitch 注释同源实证；Marketplace 253 包类文件同样超出 JDK17 可读范围，1.0.30 虽可读但为旧 EP 形态不可用）。故弃 `compileOnly(files(...))`，采用**本地桩源码（stub source set）**：

- `src/mcpStub/kotlin/com/intellij/mcpserver/`（KGP 自定义 sourceSet 约定目录）：`McpToolset.kt`（接口三钩子）、`McpExpectedError.kt`、`annotations/McpToolAnnotations.kt`、**`McpCallInfo.kt`**——文件名即契约：Kotlin 扩展属性静态绑定到声明文件类，桩文件必须命名 `McpCallInfo.kt` 使编译产物同为 `McpCallInfoKt`，`coroutineContext.project` 生成的静态调用才能在运行时命中平台真类；
- 桩 @Retention 必须 RUNTIME（真实注解在 RuntimeVisibleAnnotations 表，运行时框架反射扫描）；
- 接线（防 task 依赖环）：`compileKotlin.libraries.from(mcpStub.output)`（主编译任务挂桩产物），mcpStub 编译类路径复用 main 的 compileClasspath（平台/stdlib/JSqlParser），**不反向依赖**——桩产物只进编译 classpath，不进插件 jar 与运行时（运行时类型由平台内置插件提供；插件 jar 若携带 com.intellij.mcpserver.* 会与平台类冲突）。

### 7.3 实现摘要

- 分层：纯逻辑层 `mcp/McpToolErrors / McpSelectGuard / McpSchemaFormatter / McpExplainFormatter / McpDialectDetector / McpRenderInput`（零 mcpServer/com.intellij 类型，单测无需桩 classpath）+ 适配层 `SqlVisualizerToolset / McpSourceLocator / McpSchemaContextService / McpDbExecutor / McpAuditLog`（注解声明、ReadAction、转发）。
- 安全红线六条落地：readOnly/destructive/idempotent 三 hint 显式声明；SELECT 白名单双保险（JSqlParser 结构判定优先：单语句/Select 子类/锁读 `getForUpdateTable() + getForMode()`（JSqlParser 5.3 javap 实证，裸 FOR UPDATE 落 forMode）/无 SELECT INTO；关键字粗扫兜底，粗扫前剥离字符串字面量与注释防误杀 `LIKE '%update%'`）；零弹窗（拒绝全走 McpExpectedError，EXPLAIN 建连显式 `setAskPassword(false)`）；不提供连接管理；`[MCP-AUDIT]` 审计行（时间/工具/状态/耗时/入参摘要）；无数据源/别名不匹配/未索引（DumbService）/introspection 未完成均结构化报错不挂死。
- `sqlv_render_sql`：文件+行号 → ReadAction 内 13 类触发面定位链（复刻 ConvertToSqlAction 光标分支：mapper XML / MyBatis 注解 / Wrapper 链 / HQL / 自定义 DAO / JdbcTemplate / @Query / 派生 / Criteria / 方法体字符串 SQL），多候选零弹窗取首个并附说明；mapper XML 片段直渲（XmlPreviewService text 形态入口）。裁剪：Repository 调用点回落与文件级注解候选弹窗不带；Java 片段无 PSI 锚点不支持（渲染链是 PSI 语义的）。
- `sqlv_schema_context`：复用 DatabaseSchemaResolver 通道（弱引用缓存）逐表取表/列注释+类型+NOT NULL，max_tables 默认 8 上限 20（DBX 口径），截断附提示；别名仅做存在性校验与错误提示（现有通道按表名全局匹配，不按数据源过滤）。
- `sqlv_explain`：白名单 → 数据源别名定位（复用 DataSourceManager 反射链，未给别名且多数据源时报错列名）→ 方言解析（显式别名表精确匹配 > JDBC URL 推断 > MySQL 默认）→ `DialectRegistry.byName(...).renderExplainStatement` 七方言派发 → **新建 headless 执行通道**（既有 SqlExecutor 链是 UI 控制台形态：弹数据源选择框+异步回调+结果进原生控制台，违反零弹窗红线且拿不到文本）：反射 `DatabaseConnectionManager.getInstance().build(project, ds).setAskPassword(false).createBlocking()` → `getRemoteConnection()` → RemoteStatement/ResultSet 采集行列（243/262 javap 同形实证；Method 一律从声明接口拿，T-Embed-10 铁律）→ McpExplainFormatter 文本化（行数 200/单元格 2000 预算）。
- i18n：工具错误/描述面向 agent，英文常量不进 bundle，零新 bundle 键。
- 243 明确不支持（旧 EP `org.jetbrains.mcpserverplugin.McpTool` HTTP handler 形态适配成本>收益），发行说明由主会话发版时写明「MCP 面需 IDEA 2025.2+（内置）或装 253+ MCP Server 插件的环境」。

### 7.4 测试

- 新增：`McpSelectGuardTest` 14 例（越权全拒矩阵 + 字面量/词边界防误杀）、`McpSchemaFormatterTest` 6 例（限幅/去重/截断/预算）、`McpRenderInputTest` 11 例、`McpDialectDetectorTest` 5 例、`McpToolErrorsTest` 1 例、`McpDbSignatureCrossVersionTest` 1 例（跨版本签名护栏）；`PluginXmlStructureGuardTest` +2（mcp 成对注册守卫 + 主描述文件不得引用 mcp 类型）。
- 签名护栏（铁律 6）：`McpDbExecutor` 全部反射入口（数据源发现/建连/remote JDBC 三链 18 个方法）登记 `McpDbSignatureChecker` + `McpDbSignatureCrossVersionTest`，对本机 243/253/262 发行版用各自 JBR 核验（checker 按内容收集含 com/intellij/database/ 的插件 jar——243 在 grid-core-impl、262 EAP 在 grid-core-plugin、装机版在 connectivity，拆分形态漂移大，按内容收集形态无关；URLClassLoader parent 用平台 loader 保 java.sql 可见）。
- 全量门禁：**4204/0/0/6 绿**（独立工作树 mpsv-mcptoolset，并行表登记 t-mcptoolset / sandbox build/test-sandbox-mcptoolset）。

### 7.5 verifyPlugin 三目标门禁定案（2026-09-15 集成链终验补记）

首度三目标实跑（T-MergeR2IntoMaster 集成链 t-fourplans-20260914）暴露 253/243 两类真问题，逐一定案：

- **IU-253（实修）**：253 分支 Marketplace 包的 `McpToolset` 接口**没有 isEnabled/alwaysIncluded 默认方法**（262 有）——桩按 262 全默认体建模时，K2（jvm-default=all-compatibility）为「实现类未覆写的接口默认方法」在类内生成兼容桥，桥内 invokespecial 引用接口默认方法 → 253 报未解析。修复=桩三钩子改**抽象**（强制实现类字面量覆写 isEnabled=true / isExperimental=false / alwaysIncluded=false，与 262 默认值一致），类内零接口默认方法引用，253/262 双清。教训：**跨版本桩必须按「最小公共接口面」建模**，按最新版建模会在旧版生成悬空引用。
- **IU-243（重构部分清除 + 豁免收尾）**：① `SpillingKt`——K2 协程状态机 null-out 内在函数（`nullOutSpilledVariable`），高阶 suspend（`audit(tool, block)` 的 lambda 参数在挂起点死值占槽）结构性必然发射，243 平台 stdlib（Kotlin 2.0.x）无此类。重构（explain 挂起帧单槽化：project/数据源/方言打包 `ExplainTarget`，audit 的 tool/started 打包 `AuditTicket`；explain 前置链改非挂起）清除了 lambda 侧引用，`audit` 本体属高阶形态不可消除。② `com.intellij.mcpserver.*` 三类缺类：optional 插件类型在 243 本不存在，预期内。两者在 243 均**永不可达**（mcp-optional.xml 整文件不加载、适配类零实例化）→ `dev-support/verifier-ignored-problems.txt`（-ignored-problems，版本段 `.*` 通配免维护）豁免，门禁不计失败、报告留痕。实证：`-external-prefixes` 对「Package not found」与方法级 unresolved 两类问题**无效**；`-ignored-problems` 有效（243 单目标 online/offline 双跑 exit 0）。
- **作用域守卫**：新增 `McpScopeSourceScanTest` 2 例——mcpserver 引用只许出现在 `visualizer/mcp/` 包、META-INF 除 mcp-optional.xml 外零类引用（XML 注释剥离后判）。引用越包=243 真正走到缺类路径，豁免即失效须回退。
- **终验**：verifyPlugin 三目标通过（243 豁免 2 项留痕 / 253 Compatible / 262 Compatible，deprecated·experimental 量级与基线一致）+ buildPlugin 出包（zip 内 com/intellij/mcpserver 桩零泄漏）+ 全量 test 绿。过程坑留档：marketplace 元数据接口对 TLS 连接间歇性掐断（同 endpoint 代理/直连 curl 双通，重试即过）；中断的验证器进程会锁 `~/.pluginVerifier/extracted-plugins`，清理后重跑（发行工作流既有坑的 verifier 变体）。

### 7.6 留人工的真机走查清单

1. IDEA 2026.2.1 真机：Settings | Tools | MCP Server 显示 sqlv_render_sql / sqlv_schema_context / sqlv_explain 三工具（非实验区），`claude mcp add` 或 AI Assistant `use_idea_mcp` 后外部 agent 可见可调。
2. sqlv_render_sql：Wrapper 链行 / mapper XML 语句行 / @Select 注解行 / JPA 派生查询行四种入参各一调，返回 SQL 与 IDE 预览一致。
3. sqlv_explain：MySQL 数据源跑一条 SELECT 返回计划表格文本；UPDATE/多语句/FOR UPDATE 各一调确认结构化拒绝（MCP_READ_ONLY/SQL_BLOCKED）。
4. sqlv_schema_context：多数据源项目不给 dataSource 参数 → 错误列可选名；introspection 未连接的表 → SCHEMA_NOT_AVAILABLE 不挂死。
5. 审计：idea.log grep `[MCP-AUDIT]` 每次调用一行。
6. 243 环境插件加载无异常（mcp-optional.xml 不加载，三工具缺席其余功能正常）。

