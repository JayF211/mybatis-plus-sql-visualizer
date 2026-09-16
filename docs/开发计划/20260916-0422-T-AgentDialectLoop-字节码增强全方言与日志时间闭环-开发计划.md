# T-AgentDialectLoop 开发计划：字节码增强全方言兼容与控制台日志时间闭环

> 分支：`feature/t-m56-01-agent-base`（worktree `mpsv-m56`）｜立项日期：2026-09-16
> 需求来源：用户四点——① 字节码增强捕获 SQL 的数据库方言差异性（须兼容插件方言全部数据库）；② 增强模式下仍捕获控制台日志时间并在面板显示；③ 字节码增强自带 SQL 耗时，全链路闭环；④ 性能损耗必须可忽略。

## 一、调研结论（两轮子代理深挖 + 主会话精读复核）

### 1.1 字节码增强现状（已闭环部分）

| 环节 | 现状 | 出处 |
|---|---|---|
| 织入 | 驱动类 `connect(String,Properties)` 方法出口织入 + 两层 JDK 动态代理（Connection→Statement），16 个驱动类名精确匹配 | `SqlHookTransformer.java:12`、`DriverRegistry.java:18-41`、`ProxySupport.java` |
| 耗时 | `System.nanoTime()` 在 execute 族 invoke 前后计时（含异常路径）→ `SqlEvent.durationNanos` → JSON `dn` 键 → IDE `AgentEventAdapter` `durationNanos/1_000_000` → `LogSqlEntry.executionTimeMs` → 面板耗时片段（默认开，绿/橙/红分档）——**全链已在位** | `StatementHandler.java:83,92`、`Jsons.java:16`、`AgentEventAdapter.kt:26`、`SqlLogPanel.kt:1591-1602` |
| 时间戳 | agent 侧 execute 完成时刻 `currentTimeMillis`（语义=捕获时间） | `StatementHandler.java:110` |
| 参数 | 23 个 setXxx 白名单显式枚举；LOB/流/Array 形态标注防炸栈；脱敏双条件 | `ParamCapture.java:25-29,129-137` |
| 传输 | loopback TCP 长度前缀帧（4B len + 1B cmd + JSON），2048 有界队列满则丢弃计数，单发送线程 | `EventBus.java`、`SenderLoop.java` |
| 接管/回落 | agent 在线 → `SqlCaptureGate.paused=true` 控制台解析短路；离线自动回落 | `SqlLogConsoleBridge.kt:669`、`AgentManager.kt:194-202` |

### 1.2 缺口（本次目标）

1. **方言差异性**：采集面本身是 JDBC 接口级、与数据库无关；真正的差异面在两处——
   - `DriverRegistry` 驱动类清单已覆盖 9 方言（MySQL/MariaDB/PostgreSQL/Oracle/SQLServer/H2/SQLite/达梦/KingBase）+ GBASE/HSQL，**缺 openGauss 专用驱动**（`org.opengauss.Driver`，openGauss 3.x 起自带；PG 老驱动路径已覆盖 2.x）；
   - **事件流丢失数据库类型**：`SqlEvent.url` 字段预留恒 null、`Jsons` 不序列化（`StatementHandler.java:120`、`Jsons.java:11-45`），IDE 侧无从感知目标库——面板无法展示，方言化处理无数据支撑。
2. **控制台日志时间**：agent 在线时控制台解析整链短路（`processConsoleLine:669` 直接返回），agent 条目 `logTimestamp` 恒 null——默认「日志时间」模式显示的其实是回落后的捕获时间。用户要求：增强模式下仍要捕获控制台日志时间。
3. **历史读回 bug（顺带发现，51eb01a 引入）**：`SqlLogFileReader.NEW_HEADER_REGEX` 毫秒分数为嵌套捕获组（第 4 组），`logTime` 实际是第 5 组，读取代码 `groupValues[4]` 取到的是 `.123` 这类分数串 → `toLongOrNull()` 恒 null——**logTime 从历史文件读回永远丢失**（`SqlLogFileReader.kt:29,181`，注释「4=logTime」与正则不符）。
4. **性能**：第五轮性能复查在案热点 `JdbcTemplateLogParser:230` 每行现场编译正则（`extractThreadToken` 经 `feed→bucketFor` 逐行调用）——嗅探模式同样逐行喂入该解析器，须先收口。

## 二、方案设计

### A. agent 侧 url 透传 + 驱动清单补齐（方言差异收口）

- `ConnectionHandler.invoke` 把 url 传入 `ProxySupport.wrapStatement(stmt, sql, url)` → `StatementHandler` 新增 url 字段；`emitEvent` 填 `event.url`（删除死代码访问器 `url()`）。
- `Jsons.writeEvent` 非 null 时序列化 `"url"` 键（协议演进向前兼容：IDE 侧缺失容错）。
- `DriverRegistry` 增加 `org.opengauss.Driver`。
- `ParamCapture.renderValue` 增加默认 toString 守卫：`String.valueOf` 结果形如 `类名@十六进制`（驱动私有类型未覆写 toString，如 oracle.sql.Datum 族、达梦/金仓私有类型）→ 渲染 `<简单类名>` 形态标注，防止面板出现 `oracle.sql.TIMESTAMP@1f2a` 垃圾值。

### B. IDE 侧数据库类型感知与面板角标

- `AgentEvent` 增加可选 `url` 字段（`"url"` 键，缺失容错）。
- 新增 `JdbcUrlDialects`：`jdbc:<子协议>:...` → 显示名映射（mysql→MySQL、dm→DM（达梦）、kingbase8→KingBase（人大金仓）、opengauss→openGauss、gbasedbt→GBASE 等）；未知/畸形 URL → null 不显示。映射独立于 `DialectRegistry`（badge 是事实展示，不做 MySQL 兜底——`DialectRegistry.byName` 的未知回退会误标）。
- `LogSqlEntry` 新增构造字段 `databaseName: String? = null`（M56 先例：updateCount 等同为构造字段；不入 dedupeKey，不影响去重）。
- `AgentEventAdapter.toEntry` 填 `databaseName`。
- 面板：日志页 `LogEntryListCellRenderer` 与历史页 `HistoryCellRenderer` 元信息行在耗时片段后新增 `[MySQL] ` 角标（null 不显示；teal 加粗非选中/白选中）。
- 历史持久化：`SqlLogAutoSaver` 头部追加可选项 `| db: <名>`（在 logTime 之后）；`SqlLogFileReader` 正则扩展回读。

### C. 控制台日志时间相关器（核心新增：增强模式下时间不丢）

**语义**：MyBatis/JdbcTemplate/Hibernate 在语句执行前打印日志行（含时间戳），agent 事件在执行完成时刻产生——两者天然存在「同一条 SQL」对应关系，以去重指纹（`sqlFingerprint(normalizeSqlWhitespace(restoredSql))`，两通道同构，M56 T-M56-02 已验证同 SQL 同指纹）为键关联。

- 新增 `LogTimeCorrelator`（log 包，object）：有界 FIFO 映射 fingerprint→logTime（容量 512，`synchronized LinkedHashMap`，溢出逐出最老；`take` 命中即消费）。两线程读写（控制台过滤线程 put / agent 会话线程 take），锁开销每条 SQL 一次 O(1)，可忽略。
- **嗅探模式**（`processConsoleLine` paused 分支改造）：原来直接短路，改为当且仅当「日志时间显示开 且 时间源=日志」时把行喂给既有三解析器（复用配对/时间解析逻辑，零新解析代码），产出的条目不建面板记录、仅 `put(条目.dedupeKey, 条目.logTimestamp ?: 条目.timestamp)` 后丢弃。源码标记行在嗅探态跳过（条目已丢弃，无需归属）。
- **回填**：`AgentSession.handleFrame` 事件入队前 `LogTimeCorrelator.take(条目.dedupeKey)`，命中 → `copy(logTimestamp = ...)`,未命中（控制台行尚未到达的罕见竞态/无日志时间格式）→ 保持 null，显示回落捕获时间（现状语义，不劣化）。
- **清空时机**：`AgentManager.updateOnline` 在线态翻转 false（离线/关闭/dispose）时清空，防跨运行陈旧匹配。
- **开关门控**：快照进 `ParserConfig.sniffLogTime`（挂载/设置变更时同步，T-LogPerf-04 同范式，逐行零设置读取）——用户关掉时间显示或时间源=捕获时，嗅探完全旁路，**零成本**。
- **时序说明**：MyBatis 的 Preparing/Parameters 行打印于 execute 之前，控制台链通常先于 agent 事件到达；控制台文本管道偶发滞后时 take 未命中即回落，不重试不积压（设计取舍：宁缺不错、不引入重试缓冲的内存/复杂度）。并发同 SQL 交错时可能互换毫秒级时间戳，属可接受装饰性误差。

### D. 性能收口

- `JdbcTemplateLogParser.extractThreadToken` 的级别正则提为 companion 常量（一行修，正常/嗅探双模式受益）。
- 嗅探模式成本核算（默认全开时的最坏路径）：每行 3 次 `contains` 门控（解析器既有门，纳秒级）；**每条 SQL** 一次条目构造 + FNV 指纹 O(len) + 时间戳正则（仅 closePending 时）——相对 SQL 执行本身（毫秒级）与网络 IO 可忽略；条目即弃不进面板不持久化，内存零驻留（相关器 512 上限 × 每项 ~50B ≈ 25KB 封顶）。
- agent 侧热路径零改动（计时已是最小形态：2×nanoTime + 队列 offer）；url 只是字符串引用传递与 JSON 追加一键。

### E. 历史读回 bug 修复（51eb01a 回归）

- `NEW_HEADER_REGEX` 毫秒分数改非捕获 `(?:\.\d{3})?` → `logTime` 归位第 4 组，现有读取代码与注释即正确；同时追加 `| db: (.+?)` 可选组（第 5 组）。写读顺序固定：`timestamp → logTime → db`。

## 三、任务分解

| # | 任务 | 文件 | 验收 |
|---|---|---|---|
| T-AD-01 | agent url 透传链 | ConnectionHandler/ProxySupport/StatementHandler/SqlEvent/Jsons | ConnectWeaveEndToEndTest 断言事件 url=驱动 connect 入参；JsonsTest 键位 |
| T-AD-02 | 驱动清单 + 参数安全渲染 | DriverRegistry/ParamCapture | CaptureRulesTest 矩阵含 openGauss；默认 toString 守卫用例 |
| T-AD-03 | IDE url→库名 + 角标 | AgentEvent/JdbcUrlDialects/AgentEventAdapter/LogSqlModels/SqlLogPanel/HistorySqlLogPanel | JdbcUrlDialectsTest 映射表；AgentEventAdapterTest url→databaseName；渲染器用例 |
| T-AD-04 | 日志时间相关器 + 嗅探 | LogTimeCorrelator/SqlLogConsoleBridge/SqlLogManager(ParserConfig)/AgentManager | CorrelatorTest FIFO/容量/清空；processConsoleLine 嗅探态：零条目产出+相关器命中+agent 侧回填 |
| T-AD-05 | 历史持久化 + 读回修复 | SqlLogAutoSaver/SqlLogFileReader | 读写 roundtrip（含 logTime 修复回归 + db 字段 + 旧格式兼容） |
| T-AD-06 | 性能收口 | JdbcTemplateLogParser | 既有用例全绿 + 提正则常量 |
| T-AD-07 | 文档三处同步 | 本计划回填 + CHANGELOG + docs/MEMORY.md | 交付记录/变更条目 |

## 四、测试与门禁

- agent 子项目 `:sql-visualizer-agent:test` + 主项目全量 `test`（日常门禁口径；verifyPlugin 仅发版前）。
- 并行约束：已登记 `t-agent-dialect` / sandbox `mpsv-agentdialect`（活跃并行任务 t-uinative 在跑）。
- 测试约定红线：JUnit3 test 前缀惯例对平台类；面板测试 clear+dispose；新增守卫随既有三族。

## 五、风险与非目标

- **非目标**：PSI 高亮方言写死 MySQL 的现状（`DialectPreMapping`，渲染管线问题，与捕获链无关）；T-M56-07 Web filter hook；批处理参数按批重放（既有已知限制，agent 保持末次参数快照语义）。
- **风险**：新旧版本历史文件兼容——旧插件读不了带 `| db:` 的新头部（回落旧格式解析），与 logTime 引入时的前向兼容取舍一致，可接受。
- **协议兼容**：旧 agent + 新 IDE（url 键缺失→null→无角标）、新 agent + 旧 IDE（多余键被忽略）双向安全。

## 六、交付记录

**已交付（2026-09-16，分支 feature/t-m56-01-agent-base，沙盒 mpsv-agentdialect）**。

- T-AD-01/02 ✅：url 透传链（ConnectionHandler→wrapStatement→StatementHandler→SqlEvent.url→Jsons `"url"` 键）+ openGauss 驱动登记 + ParamCapture 默认 toString 守卫。偏差：无。
- T-AD-03 ✅：AgentEvent.url（默认 null 容错）+ JdbcUrlDialects + LogSqlEntry.databaseName + 双页角标（`formatDatabaseFragment`，耗时后/来源前落位）。
- T-AD-04 ✅：LogTimeCorrelator（512 FIFO）+ processConsoleLine 嗅探分支 + ParserConfig.sniffLogTime 快照门控 + AgentManager 回填与离线清空。
- T-AD-05 ✅：`| db:` 头部扩展 + NEW_HEADER_REGEX 修复（毫秒分数改非捕获，logTime 归位第 4 组——51eb01a 读回断链顺带修复）。
- T-AD-06 ✅：LEVEL_KEYWORD_PATTERN 提常量。
- T-AD-07 ✅：本节 + CHANGELOG [Unreleased] + docs/变更记录.md。

**门禁**：agent 子项目 24 用例绿（AttachGateTest 真实 attach 硬验收含内）；主项目全量 **4223/0/0/6 绿**（+24 例）。

**计划外发现与修复（三项）**：
1. `SqlLogFileReader` logTime 读回断链（§E，51eb01a 回归）；
2. `ConnectWeaveEndToEndTest` 父优先委派遮蔽织入字节——URLClassLoader 默认父优先，测试 classpath 上的原始 FakeDriver 遮蔽织入产物，断言长期空真（真正硬验收一直在 AttachGateTest）；改子优先 loader 后织入字节真实生效，既有断言收紧行动化；
3. `HistoryCellRendererTest` 设置串染——保存值在 finally 里现读（set 后读到的是改后值）还原恒失败，HH:mm:ss 泄漏同 worker 后续类；新测试类改变分片内类顺序后暴露为 SqlLogSearchRowTimeTest 2 例失败；改 setUp 捕获/tearDown 还原。

**陈旧产物劫持再证**：LogSqlEntry 加构造字段后首跑 300+ 例 NoSuchMethodError，清 build/classes+build/kotlin 复绿（六犯记录第八犯）。
