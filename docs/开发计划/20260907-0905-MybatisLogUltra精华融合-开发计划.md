# Mybatis Log Ultra 精华融合 开发计划

- **任务ID**：M55（子任务 T-M55-01 ~ T-M55-04）
- **立项时间**：2026-09-07 09:05
- **分支基线**：`feature/r2-competition-response`（worktree mpsv-r2，R2 九任务已合并态）；实施时各子任务按并行约定拆独立分支与沙盒
- **前置**：N-10 对标调研报告（[20260906-0230-MybatisLogUltra对标-调研报告.md](./20260906-0230-MybatisLogUltra对标-调研报告.md)）；2026-09-07 功能细节深挖采集（updates API 逐版 changeNotes、文档站 i18n 全量功能文案、演示 GIF 帧、GitHub 仓库——原始素材在本地临时目录不入仓库）；同日三候选功能代码落点扫描（本仓库只读调研，全文内引用处均为 文件:行号 实证）
- **与 N-10 结论的关系**：N-10 §3.3 曾结论「无需另立开发计划，防御性验证即可」。本次经用户决策做深度调研后升级为功能吸收立项——深挖修正了两处低估（方法级聚焦完成度、请求级聚合实现形态），并新增一处闭环关系识别（Restful 工具与请求边界的联动），吸收价值大于原判断。

## 1. 依据

### 1.1 深挖采集对 N-10 认知的三处修正

1. **方法级聚焦完成度被低估**。形态实证（文档站 howItWorks 三模式文案 + 演示 GIF 帧核实）：编辑器中 Service/Controller 方法行槽 ⚡ 图标点击后，日志工具窗右侧动态追加可关闭独立页签，页签名 `{Class}#{method}`（实证样例 `EmployeeServiceImpl#getAllEmployee`），页签内只滚动展示该方法执行期 SQL。它与 Marketplace listing 六大特性中的「方法级分组」（按 Mapper 方法名组织日志、可见执行次数）是**两个不同特性**——上轮报告混述且未将其列入吸收清单。
2. **请求级聚合实现形态修正**。Filter by Request 是工具窗固定 Tab，内容为**扁平时间序列过滤视图而非树形分组**；请求边界并非纯 agent 推断，而是由内嵌 Restful Api Tool 的 Send 动作产生（发起点即边界），失败请求（HTTP 500）的 SQL 照样捕获（JDBC 层与响应码无关）。「聚合」实为「按请求标签过滤」，不需要分组数据模型。
3. **Restful Api Tool 是真实内嵌 HTTP 客户端**（Params/Authorization/Headers/Body 四页签、Response/Console 双页签、Status/Time 展示、多开命名请求 Tab），且是请求级聚合的采集触发器。本插件明确不吸收该工具（IDEA Ultimate 自带 HTTP Client，重复造轮子），但「执行动作产生请求边界」的思路可借——对应 T-M55-04 用请求上下文标签替代。

### 1.2 吸收决策表

| # | Ultra 特性（证据锚点） | 决策 | 任务 | 理由 |
|---|----------------------|------|------|------|
| 1 | XML/Java 双目标跳转（i18n 双键 + 行槽 hover 图标 + 4.0.15 notes） | 吸收 | T-M55-01 | 成本最小：拦截器模板的 `ms` 入参现成可用只是从未输出；本插件 XML 引擎已有 mapper 解析底子 |
| 2 | SQL 执行报错展示（4.0.14 notes，自述「暂不支持参数绑定报错」） | 吸收并反超 | T-M55-02 | 调试价值高、控制台桥旁路成本低；参数绑定报错形态在 JDBC 异常词表覆盖范围内，可一并支持 |
| 3 | 方法级聚焦（动态 `{Class}#{method}` 页签） | 吸收（静态行号区间近似版） | T-M55-03 | 上轮低估；无 agent 也可实现——本插件条目已携带 sourceFile/sourceLine，PSI 解析方法行号区间即可 |
| 4 | 请求级聚合（扁平过滤视图 + Send 产生边界） | 吸收轻量版 | T-M55-04 | 形态修正后确认为过滤视图而非分组模型，无需重构平铺列表与差分渲染 |
| 5 | Mapper 方法名分组计数 | 需求池（P3） | — | 与既有 ⨉N 去重徽章部分重叠，独立价值待用户声音验证 |
| 6 | SQL 与剪贴板对比（Compare with Clipboard） | 需求池（P3） | — | 本插件已有 SqlDiffer，挂右键入口成本低，待需求池排期 |
| 7 | Fold Lines Like This 折叠同类行 | 需求池（P3） | — | dedupeKey 指纹已有底子，但 ⨉N 徽章 + 类型过滤已覆盖主要场景 |
| 8 | Restful Api Tool 内嵌 HTTP 客户端 | 明确不吸收 | — | IDEA Ultimate 自带 HTTP Client；做请求边界靠 T-M55-04 的上下文标签 |
| 9 | Arthas 远程 / agent 字节码增强 | 维持不吸收（Arthas 远程）；agent 通道另立 | — | N-10 §3.2 结论更新：Arthas 远程仍不吸收；agent 字节码增强经用户决策与实测调研升级立项为 [M56 agent 字节码增强捕获通道开发计划](./20260907-1013-agent字节码增强捕获通道-开发计划.md)——本表四任务消费端两通道共用，发射端按 M56 升级 |
| 10 | 关键词过滤 / Pause Output | 已具备 | — | Ctrl+F 实时搜索 + 类型过滤；捕捉暂停态已有 |

## 2. 公共约定：执行期标记行格式演化（T-M55-01/04 共同遵守）

现状：`SQLVIS-SOURCE|<相对路径>|<行号>` 三段，严格整行正则（`log/SqlSourceMarker.kt:15-34`，生成端模板 `log/SourceInterceptorTemplates.kt:74`，文档副本 `docs/instrumentation/SqlSourceInterceptor.kt:57`）。

演化约定：

- `|` 分段**尾部追加**，段位次固定：第 4 段 = statementId（`namespace.id`），第 5 段 = requestTag（如 `GET /api/user`）。
- 解析端正则改为宽松匹配，3~5 段皆容，缺段为 null——**用户项目里已注入的旧拦截器（3 段）在插件升级后照常工作**（`SqlSourceInterceptorAutoInjector.kt:99-101,115-120` 幂等跳过已存在文件，旧模板不会被自动替换，这是常态而非异常；升级引导走设置页提示，不在本里程碑）。
- 文档副本与模板同步改动；历史持久化头（`-- source:` 写 `log/SqlLogAutoSaver.kt:213-214`、读 `log/SqlLogFileReader.kt:145-183`）同步扩展，旧历史文件无新字段可读回。
- 实施协调：01 先行落第 4 段，04 在其上落第 5 段；若并行，04 的标记格式改动以 01 为合并前置。

## 3. 子任务

### 3.1 T-M55-01 跳转双目标：XML mapper（P1）

**现状**：跳转仅 Java 调用行精确跳转（`SqlLogPanel.kt:1037-1055 resolveEntryJump` → `SourcePathResolver` → `OpenFileDescriptor.navigate`）+ 表名搜 Wrapper 回退。条目与标记行均不携带 statement id（模板 `beforeQuery/beforeUpdate` 入参里的 `MappedStatement ms` 完全未使用，`SourceInterceptorTemplates.kt:55-67`）；无「namespace+id → 磁盘 mapper XML 文件」定位。XML 方向的半成品已存在：namespace+statementId → mapper 接口方法（`ui/XmlEwResolver.kt:50-56`）、XML 文件内 statement 解析（`xml/XmlMapperParser.kt:48-66`）、XML 文件内部 gutter（`ui/gutter/XmlSqlGutterLineMarkerProvider.kt:33-97`）。

**方案六层改动**：

| 层 | 文件 | 改动 |
|----|------|------|
| 发射端 | `log/SourceInterceptorTemplates.kt:55-59,71-76` + `docs/instrumentation/SqlSourceInterceptor.kt:57` | `emitSource` 追加 `ms.getId()`；拦截器模板与文档副本同步 |
| 格式 | `log/SqlSourceMarker.kt:19-33` | format/parse 扩第 4 段，正则宽松化（§2 约定） |
| 消费端 | `log/SqlLogConsoleBridge.kt:564-569` + `BridgeLineContext`（:541-549） | `pendingSource` 从 `Pair<String,Int>` 扩为携带 statementId 的小数据类，绑定进条目 |
| 模型 | `log/LogSqlModels.kt:24-81` | `LogSqlEntry` 加 `mapperStatement: String?` |
| 定位器（新建） | `log/MapperXmlLocator.kt` | statementId 拆 namespace.id → FileTypeIndex 查 mapper XML（仿 `psi/jpa/HbmXmlEntityResolver.kt:93` 的 FileTypeIndex 先例）→ `XmlMapperParser` 校验 namespace 匹配 → PSI 定位语句 XmlTag 行号（tag 判定沿 `XmlSqlGutterLineMarkerProvider.kt:39-42` 范式）；全程 ReadAction/后台线程 |
| 跳转 | `log/SqlLogPanel.kt:1028-1112,1260-1265` + `log/HistorySqlLogPanel.kt:374-412` | `LogJumpTarget` sealed class 加 `MapperXml` 分支；右键菜单拆「跳转到 Java」「跳转到 XML」两项（mapperStatement 非空且定位成功才显示后者），XML 定位失败回退 Java 不劣化；历史面板同套 |

历史持久化 round-trip（`SqlLogAutoSaver`/`SqlLogFileReader`）同步扩展。

**测试设计**：`SqlSourceMarkerTest` 补 4 段格式与 3 段兼容；桥 `SqlLogConsoleBridgeMarkerTest` 补 statementId 绑定；`MapperXmlLocatorTest`（fixture mapper XML：命中/namespace 不匹配/无 XML/多模块同名）；跳转 Action 测试；历史 round-trip 测试（`SqlLogHistoryRoundTripTest` 扩展）。

**验收标准**：

- [ ] 携带 statementId 的条目可右键直跳 mapper XML 的 SQL 节点；XML 不存在（注解 SQL/MP 内置语句）时回退 Java，无任何劣化
- [ ] 模拟旧拦截器 3 段标记输出的全链路回归绿（幂等不升级场景）
- [ ] 历史保存/读回 statementId 不丢失，旧历史文件可读
- [ ] 新增用例全绿 + 全量回归绿

**风险**：MP 内置 MappedStatement 的 id 形态（Wrapper CRUD 走 MP 内置语句，XML 通常不存在——回退即可，实施期以 demo 实测记录形态）；多模块同名 mapper XML 命中顺序（首批取第一个 + LOG.info，记录已知边界，不弹选择器）；Kotlin 调用方条目同样受益（跳 XML 与调用方语言无关）。

### 3.2 T-M55-02 SQL 执行报错同列（P1）

**现状**：全链路对异常行零解析零保留——`processConsoleLine`（`SqlLogConsoleBridge.kt:558-598`）里非标记行喂三解析器不匹配即静默丢弃，条目模型（`log/LogSqlModels.kt:24-81`）无异常字段。唯一相邻先例：execute 链的 `execute/DataSourceSelector.kt:522-552` 有控制台错误签名提取（`ERROR_SIGNATURES` 词表 + `isSqlLine` 防误报守卫），可作识别策略参考。

**方案**：

| 层 | 文件 | 改动 |
|----|------|------|
| 识别器（新建） | `log/SqlErrorLineRecognizer.kt` | 纯函数；词表策略仿 `ERROR_SIGNATURES`：MyBatis `###` 形态、`org.springframework.dao.*`、`BadSqlGrammarException`、`SQLException`、JdbcTemplate/Hibernate 异常签名、参数绑定异常（PreparedStatement setter 报错——Ultra 4.0.14 自述不支持的边界，本插件一并覆盖形成反超）；多行堆栈取首行入 errorText、紧随堆栈行聚合（行数上限）；廉价前置判定（常量词表 contains），守住逐行 Filter 热路径（T-Perf 红线，对照 docs/性能分析/20260902-0054） |
| 桥 | `log/SqlLogConsoleBridge.kt` | `BridgeLineContext` 加 `pendingError` 暂存与最近条目引用；`processConsoleLine` 加异常分支——旁路记录，`LogFilterProxyHandler` 恒返回 null 约束不变（不改控制台输出） |
| 缓冲 | `log/SqlLogEntryBuffer.kt:62-88` | 追加「原地更新」能力：错误到达时反向绑定时间窗内同控制台最近条目，`cachedListHtml` 失效 + 单条差分刷新（不动 VisibleDiff 算法本体，`SqlLogPanel.kt:138-206`） |
| 模型 | `log/LogSqlModels.kt` | `LogSqlEntry` 加 `errorText: String?`；`dedupeKey` 不含 errorText（SQL 指纹稳定，错误绑定去重代表条目） |
| 渲染 | `log/SqlLogPanel.kt:1406-1414 computeRowHtml` + `openPreview` :1178-1222 | 列表行错误段（红字/警示标记）；预览详情展示完整错误文本 |
| i18n | bundle | 错误标记相关文案 en/zh |

**测试设计**：识别器单测（多形态错误行 fixture：MyBatis `###`、Spring DAO、JDBC、参数绑定、非 SQL 行不误报）；桥集成测试（Preparing→Parameters→异常行的时序绑定）；缓冲原地更新与缓存失效；渲染测试；去重交互（错误绑定代表条目、折叠态标记）。

**验收标准**：

- [ ] SQL 执行报错与对应条目同列展示（列表行可见 + 详情全文）
- [ ] 参数绑定报错可展示（覆盖 Ultra 自述不支持的场景）
- [ ] 无错误行的行为零变化（既有解析/渲染/去重回归绿）
- [ ] 热路径不回退：识别器为 O(词表) 廉价判定，性能红线相关测试（SqlLogConsoleBridgePerfTest 等）绿
- [ ] 新增用例全绿 + 全量回归绿

**风险**：时序反向关联是全新机制——异常打印在 Preparing/Parameters 之后，条目可能已被去重丢弃或缓冲淘汰（淘汰后错误丢弃 + LOG.debug，不阻塞主链）；错误块与多语句交错时的归组歧义（按「最近一条」绑定 + 词表守卫，误绑成本低于漏绑）；逐行识别的热路径成本（词表前置判定 + 早退）。

### 3.3 T-M55-03 方法级聚焦页签（P2）

**方案**（静态行号区间近似版——无 agent 的等价实现）：

- 入口：日志条目右键「聚焦调用方方法」（`sourceFile` 非空时可用）。Ultra 的编辑器方法行槽 ⚡ 入口需要静态扫描 Service 方法生成图标，成本高且与 gutter 现有体系冲突，首批不做（记录为可选增强）。
- 方法区间解析：sourceFile+sourceLine → PsiFile → 定位包含该行号的 PsiMethod → 方法体行号区间。首批仅 Java 调用方（Kotlin 文件记已知边界，沿 F-4 MVP 先例口径）。
- 面板：`SqlLogPanel` 顶部加**动态过滤页签栏**（可关闭、可多开，命名 `FooService#bar`）；过滤管线插一层 `filterByFocus`（`SqlLogPanel.kt:869-970` 的 filterByType→filterBySearch→applySort 之间）——页签是过滤器不是分组，VisibleDiff 差分（:138-206）与既有性能优化（T-Perf-03/T-LogListFreeze）零接触。
- 与 T-M55-04 共用页签基础设施（focus 类型：method / request），基础设施由本任务建立。
- 无 sourceFile 条目在聚焦视图下隐藏 + 计数提示（i18n）。

**测试设计**：方法区间解析测试（fixture Java 类：方法内行号/方法外行号/嵌套类/重载）；过滤纯函数测试；页签交互测试（多开/关闭/与类型过滤搜索叠加）。

**验收标准**：

- [ ] 条目右键聚焦后仅显示该方法行号区间内的条目，页签可关闭可多开
- [ ] 聚焦视图与类型过滤/搜索正确叠加；无 sourceFile 条目隐藏有计数提示
- [ ] 既有刷新管线性能测试全绿（差分零改动）
- [ ] 新增用例全绿 + 全量回归绿

**风险**：行号区间近似与运行期真实方法边界的差异（lambda/匿名类按所在 PsiMethod 区间处理，覆盖场景记录）；行号一致性（拦截器取运行栈行号 = 编译源码行号，理论一致，真机抽查确认）；历史面板是否共用页签（首批仅实时面板）。

### 3.4 T-M55-04 请求级聚合过滤视图（P2，格式前置依赖 T-M55-01）

**方案**（轻量过滤版，对齐 Ultra 实际形态——扁平过滤而非分组）：

- 模板扩展（`log/SourceInterceptorTemplates.kt`）：Spring Web 环境经 `RequestContextHolder.currentRequestAttributes()` 取 requestURI + method 拼请求标签（`GET /api/user`），全程 try-catch 兜底，非 Web/无上下文/异常 → 空段。MDC traceId 是否优先读取在实施期评估（缺省不读——MDC key 无跨项目约定）。
- 格式：第 5 段 requestTag（§2 约定）；消费端绑定 `LogSqlEntry.requestTag: String?`；历史 round-trip 扩展。
- 面板：与 03 共用页签机制，提供「按请求聚焦」动态页签（或近 K 个请求标签的下拉，实施期按交互定）；无请求上下文条目归「无请求标签」可见性策略同 03。
- 呈现扁平时间序，不做树形/分组头——不碰差分与缓冲分组模型。

**测试设计**：拦截器模板源码串断言（RequestContextHolder 守卫与兜底分支）；5 段标记解析与缺段兼容；round-trip；过滤视图测试。

**验收标准**：

- [ ] Web 请求内执行的 SQL 带请求标签，可通过页签/下拉聚焦单请求视图
- [ ] 非 Web 线程（定时任务/启动期/异步池）零异常、零标记（模板兜底守卫）
- [ ] 请求边界零运行时注册（无会话管理，纯标签过滤）
- [ ] 新增用例全绿 + 全量回归绿

**风险**：RequestContextHolder 在异步子线程拿不到请求上下文（已知边界文档化——该场景条目落「无请求标签」组）；标记行再增一字段（成本可忽略）；requestTag 高基数（长压测会话产生大量标签——页签/下拉只列最近 K 个，防止 UI 爆列表，沿 MAX_CANDIDATES 先例）。

## 4. 实施顺序与并行协调

1. **波次一（串行）**：T-M55-01 → T-M55-02。两者都动 `SqlLogConsoleBridge.processConsoleLine`（标记分支/异常分支同函数）与 `LogSqlModels`，串行实施避免同文件冲突；01 先行因其格式扩展是 04 的前置。
2. **波次二（可并行）**：T-M55-03（页签基础设施，与桥链路无交集）∥ T-M55-04（依赖波次一合并后的格式约定）。
3. 各任务独立分支 + 并行沙盒登记（check → register --name --sandbox → done 必带 --name）；构建命令与沙盒隔离约定沿 project-test-conventions。
4. **真机验证**（不作为合入门禁，交付清单记录）：跳 XML（demo mapper）、报错同列（demo 故意 SQL 错误）、聚焦页签、请求标记（demo Web 请求各一例）——统一走 runide-demo-verify-workflow。

## 5. 里程碑总验收

- [ ] 四子任务验收标准全过；全量回归绿；01~04 无扩展点变更（不触 plugin.xml），按约定跳过全量 verifyPlugin
- [ ] 标记行格式演化向后兼容（旧 3 段拦截器全链路回归专项通过）
- [ ] 真机抽查四项完成并留档
- [ ] 文档三处同步：本计划 §变更记录回填、docs/变更记录.md 追加、受影响说明文档（预览触发面清单/日志功能文档）更新
- [ ] 对外文案附带项（随下次发行，非本里程碑代码任务）：README/Marketplace 显性强调「免费 + 精确跳转 + 静态预览 + 执行报错同列」，压 Ultra 付费墙与能力空白（N-10 §3.3 建议）

## 6. 不做清单与需求池（防范围蔓延）

- 需求池 P3：Mapper 方法名分组计数、SQL 与剪贴板对比（SqlDiffer 挂入口）、同类行折叠——待用户声音排期，不顺手做。
- 明确不吸收：Restful HTTP 客户端（Ultimate 自带）、Arthas 远程、agent 字节码增强（产品伦理，N-10 结论维持）、关键词过滤/Pause Output（已具备）。
- 升级引导：旧拦截器用户的模板升级提示（设置页入口）不在本里程碑，记录为后续候选。

## 风险总表

| 风险 | 影响 | 对策 |
|------|------|------|
| 旧拦截器幂等不升级（用户项目残留 3 段标记） | 新功能对存量用户不可见 | 解析端 3~5 段兼容为一等验收项；升级引导列后续候选 |
| 异常行反向关联的时序歧义 | 错误绑错条目 | 「最近一条 + 时间窗」绑定 + 词表防误报；淘汰场景显式丢弃留日志 |
| processConsoleLine 热路径膨胀 | 性能红线回退 | 01/02 改动均走廉价前置判定；Perf 测试纳入各任务定向回归 |
| 页签过滤与差分渲染交互 | 冻结/闪烁回归 | 页签=过滤层不碰 VisibleDiff；LogListFreezeTest 纳入定向回归 |
| demo 实测形态与预期偏差（MP 内置语句 id、请求标签格式） | 方案返工 | 各任务真机抽查先行于文档定稿；偏差按增量补记 |

## 变更记录

- 2026-09-07 M55 立项：Mybatis Log Ultra 精华融合（N-10 后深化调研——updates API 逐版 changeNotes/文档站 i18n/演示 GIF 帧/GitHub 四源证据 + 本仓库代码落点扫描；认知修正三处：方法级聚焦完成度被低估、请求级聚合实为扁平过滤视图、Restful 工具是请求边界触发器；四子任务 T-M55-01 跳转双目标 / 02 执行报错同列并反超参数绑定边界 / 03 方法级聚焦静态近似版 / 04 请求级聚合过滤版 + 标记行格式演化公共约定 + P3 需求池与不吸收清单）。
