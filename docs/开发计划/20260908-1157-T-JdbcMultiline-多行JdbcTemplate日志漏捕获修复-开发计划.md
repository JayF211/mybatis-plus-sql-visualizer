# T-JdbcMultiline：多行 JdbcTemplate 日志漏捕获修复 开发计划

> **创建日期**：2026-09-08
> **分支**：master（当前 HEAD `7aac2cb`）
> **优先级**：P1（用户项目实际日志整条漏捕获——COUNT 查询一条不进列表，捕获核心能力缺口）
> **预估工时**：1.5~2h（含全量回归；runIde 真机重放用户项目人工跟进）
> **来源**：用户反馈——一段 Spring JdbcTemplate 控制台日志没有被 SQL 日志捕获（原始样本见 §1），要求对照主 IDEA 2026.2 在售的 MyBatisCodeHelper-Pro 插件，吸收其 SQL 日志功能精华后出开发计划。对照分析已完成（§2.4），结论先行：**MyBatisCodeHelper-Pro 只支持 MyBatis `Preparing:/Parameters:` 成对格式，用户样本它同样捕获不到**；本次修复靠自己补多行缺口，对方的价值在架构与容错细节（§2.4/§3.2）。

---

## 一、需求分析

用户项目（Spring + JdbcTemplate）控制台输出（原样，SQL 部分有缩进与跨行）：

```
2026-09-08 11:21:11.066 [TID: AFFCDDCD81F34B929A55E587382D58E8] [http-nio-0.0.0.0-6608-exec-5] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:711 - Executing prepared SQL query
2026-09-08 11:21:11.066 [TID: AFFCDDCD81F34B929A55E587382D58E8] [http-nio-0.0.0.0-6608-exec-5] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - Executing prepared SQL statement [SELECT COUNT(1)
        FROM ipm_ImplementTask t
        WHERE t.deleted = 0
        AND EXISTS (SELECT 1 FROM ipm_ProjectSubject p WHERE p.id = t.projectId AND p.deleted = 0)
        AND (t.subjectId IS NULL OR t.subjectId = '' OR
             EXISTS (SELECT 1 FROM ipm_ProjectSubject s WHERE s.id = t.subjectId AND s.deleted = 0))
                    AND t.projectId IN (
                SELECT pc.id FROM ipm_ProjectSubject pc
                WHERE pc.deleted = 0 AND pc.projectClassification IN (
                     '80a9971836664x3091xaa53b78ae6837'
                )
            )
]
```

日志框架为 TLog（`[TID: ...]` 链路字段）+ `%logger Line:%L` 自定义 pattern。期望：这条 COUNT 查询进入 SQL 日志列表，正常参与去重、耗时、源码跳转与 [MP 分页计数] 标记判定。当前实际结果：一条都不出现。

## 二、现状与根因

### 2.1 捕获链路现状

`LogStartupCapture`（postStartupActivity）→ 反射桥挂 `addMessageFilter`（SqlLogConsoleBridge.kt:314-323，2026.2 独立 Filter 接口，T-M31.12）→ 逐行 `processConsoleLine`（:547-588）喂三个解析器 → `takeEntries()` 成批 `buffer.addEntry`（:319，全插件唯一写入口）。`JdbcTemplateLogParser` 三个分支（JdbcTemplateLogParser.kt:38-70）：

1. NamedParameter 单行（`sql = ... , parameters = {...}`）；
2. SQL 行：`SQL_LINE_PATTERN` 提取 `[...]` 内文本（:241-244）；
3. StatementCreatorUtils 参数行。

### 2.2 根因：SQL 正则要求同行闭合，跨行语句整体落空

`SQL_LINE_PATTERN`（JdbcTemplateLogParser.kt:242）为：

```
(?:Executing\s+(?:prepared\s+SQL\s+(?:statement|update|query)|...))\s*\[(.*)\]
```

`\]` 是必须项且 `.` 不匹配换行——**开括号与闭括号必须在同一行**。逐行走查用户样本：

| 行 | 内容形态 | 命中分支 |
|---|---|---|
| 1 | `... Executing prepared SQL query`（无 `[`） | 设计上忽略（无 SQL 文本） |
| 2 | `... Executing prepared SQL statement [SELECT COUNT(1)` | 无闭合 `]` → 正则不中 |
| 3~9 | `FROM ...` / `WHERE ...` 缩进 SQL 续行 | 三分支全不中 |
| 10 | `]`（独占一行） | 全不中 |

结果：`pendingSql` 从未建立，整条语句零产出。这不是日志前缀问题，是**逐行喂入架构下多行 `[...]` 语句无收集机制**。

### 2.3 排除项

- **TID/Line:711 前缀无关**：正则无行首锚定（`find()` 任意位置匹配），前缀再花哨都不影响命中；
- **`Executing prepared SQL query` 头行无关**：Spring 对 query/update/updateBatch 先打无 SQL 的头行再打 statement 行，头行本来就不含文本；
- **不会双记**：多行还原文本与 named 单行副本的差异已由 T-LogDedupeMpMark 的 `normalizeSqlWhitespace`（trim + 连续空白折叠，含换行）归一，去重链路天然兼容；
- **手动粘贴面板同源同漏**：`LogToSqlPanel`（:236-248）同样逐行 feed 同一解析器，用户复制控制台多行块粘贴进去也拿不到——本修复两路同时受益。

### 2.4 同类工具能力对照结论（在售版）

其 SQL 日志功能源自开源 BatsLog，机制按行为语义对照还原。

**捕获架构**：通过 `consoleFilterProvider` 稳定 EP 注册逐行 Filter。比我们的反射桥少一层平台版本适配成本——它用的 `com.intellij.execution.filters.Filter` 是稳定接口，2024~2026.2 未破坏（我们 2026.2 被 `addMessageFilter` 独立接口变更咬过一次，T-M31.12）。

**解析模型**：

- **可配置前缀配对**：`sqlPrefix`（默认 `Preparing:`）/`paramsPrefix`（默认 `Parameters:`），支持逗号分隔多前缀（`enableMixedPrefix`）；Preparing 行缓存为 lastPrepareLine，Parameters 行到来时才配对产出——**只有成对语句产出**，无参数的 SQL 单行不捕；
- **尾部公共后缀对齐降噪**：Preparing 与 Parameters 两行从尾部逐字符比较，削去共同后缀——不认识日志格式也能去掉行尾噪音（自定义 pattern 的耗时、来源等后缀），零配置通用；
- **多行容忍**：prepare+params 拼接后整体 `split("\n")` 逐行扫描，参数续行 append 到上一条（行尾 `)` 判闭合）；但 SQL 本身仍按单行取，跨行 SQL 它一样丢；
- **参数还原**：MyBatis `(value(Type))` 序列从尾向头扫描，`null(Null)` 特判；类型表 8 包装类 + 23 JDBC 类型名驱动，`Class.forName("java.lang."+type)` 真实转换后判数值/字符串——与我们 PLAIN_TYPES 同型。

**输出侧工程**（这是它最扎实的部分）：

- **批量异步写 UI**：捕获只入并发队列；单守护线程每 1s 排空，按 Project 分组，每组一次 `invokeLater` + 一次 `WriteCommandAction` 批量插入——高频日志下 EDT 写次数与 SQL 条数解耦；
- **文档自清理**：输出文档超 200k 字符删头部 50k（两处落点一致）；
- **去重**：开关 + 时间窗（key=prepare+params 原文拼接，value=count+lastTime，窗内命中 count++）——原文键正是 T-LogDedupeMpMark 刚证明会漏判的方案，我们的 `sqlFingerprint` 指纹键已优于它；
- **其余设置面**（约 22 项）：逗号分隔黑名单、超长防护（超长时控制台打超链接提示而非静默截断）、首次捕获自动弹工具窗（只弹一次）、时间戳+格式+自增序号写 `/*seq timestamp mapper*/` 头注释、删指定行尾、随项目自启（我们 LogStartupCapture 已有）。

**能力边界（关键结论）**：对日志格式零硬支持——`Executing`/`JdbcTemplate`/`prepared SQL` 均不处理，Hibernate/JdbcTemplate 格式零支持。用户样本喂给它同样一条不进。**可借鉴的精华是「怎么工程化地吃日志」，不是「这条日志的现成答案」。**

**精华对照表**：

| 精华点 | 我们现状 | 结论 |
|---|---|---|
| consoleFilterProvider 稳定 EP | 反射桥 + 版本分支适配 | P2 调研项（§3.3），不切换 |
| 可配置 sqlPrefix/paramsPrefix + 多前缀 | 已有（sqlPrefix/paramPrefix/mixedPrefix 配置） | 已对齐 |
| 尾部公共后缀对齐降噪 | 无 | P2 可选（§3.3） |
| 多行 SQL 收集 | Hibernate 有状态机，JdbcTemplate 无 | **本轮核心修复** |
| 类型驱动参数还原 | 已有（PLAIN_TYPES + 类名前缀剥离） | 已对齐 |
| 批量异步写 UI / 文档自清理 | 已有（T-Perf-03 差分刷新、EntryBuffer 淘汰） | 已对齐 |
| 指纹去重 + 时间窗 | 已有且更强（T-Perf-19/T-LogDedupeMpMark） | 已对齐 |
| 日志黑名单 | 无 | 本轮采纳（§3.2） |
| 超长 SQL 用户可见提示 | 静默截断 | P2 可选（§3.3） |

## 三、技术方案

### 3.1 改动点一（核心）：JdbcTemplateLogParser 多行收集状态机

参照 `HibernateLogParser` 已验证的 IDLE/COLLECTING 两态机（:59-114），给 `JdbcTemplateLogParser` 加最小收集态，快路径（同行闭合）行为与性能完全不变：

```kotlin
// 新增状态
private var collecting = false
private val multilineBuffer = StringBuilder()   // 从 '[' 后首字符起累积
private var multilineNetBrackets = 0            // string-aware 净括号计数
```

- **进入**：SQL 行匹配到动作短语但同行 `]` 未闭合（净计数 > 0）→ 不再 return null，`collecting = true`，把 `[` 后文本存入 buffer。头行（`Executing prepared SQL query` 无 `[`）继续忽略；
- **净括号计数 string-aware**：扫描时跟踪单引号字符串态（`''` 转义），字符串内 `[]` 不计数——`'foo]'`、SQL Server `[col]` 标识符均不干扰。比"行尾是否 `]`"启发式更准（SQL 中段某行恰以 `]` 结尾不会误闭合）；
- **累积**：COLLECTING 态逐行 `multilineBuffer.append('\n').append(line)`，同步更新净计数；超 `maxParseLength` 强制闭合（截断，现语义）；
- **闭合产出**：净计数归 0 → 剥掉末尾 `]`，`pendingSql = buffer`，走既有 pendingParams/closePending 流程（参数行在语句之后到达，Spring 先打印 statement 再设参，顺序天然正确）；
- **防失控边界**：COLLECTING 态遇到新语句行（SQL_LINE_PATTERN 动作短语命中）或 NamedParameter 行 → 按现文本强制闭合（宁错切不吞后句）；`closePending()` 对未闭合的 COLLECTING buffer 同样强制闭合——`LogToSqlPanel` 末尾的 `closePending()`（:247）零改动即受益；
- **不动的部分**：SQL_LINE_PATTERN 原正则保留做单行快路径（含 `take(maxParseLength)`）；`feed` 分支顺序、`closePending` 签名、`SqlLogConsoleBridge`/`SqlLogPanel` 全部零改动——不碰 T-LogListFreeze04 刚重构的渲染管线。

### 3.2 改动点二：日志黑名单（同类工具精华移植）

> **实施核实（2026-09-08）**：该能力已由既有 `logFilterKeywords` 等价覆盖——设置 UI（PluginSettingsPanel.kt:446）、
> buffer 层过滤（SqlLogEntryBuffer.kt:138，`contains` ignoreCase）、SqlLogManager 同步（:262）与测试齐备。
> 本轮未新增代码，同类工具的日志黑名单与本插件 `logFilterKeywords` 判为同一能力的两种命名。

- `PluginSettings` 增 `logIgnoreSqlContains: String`（默认空，逗号分隔，contains 匹配，trim 逐项）；
- `processConsoleLine` 在 `taken` 之后、`buffer.addEntry` 之前过滤（与 `pendingSource` 暂存逻辑无交集）；`BridgeLineContext` 加缓存字段，随 `refreshConsoleContexts` 刷新（对齐 T-LogPerf-04 的零逐行成本模式，不逐行读设置）；
- 设置页「SQL 日志」分组加输入框 + 中英文文案（SettingsIO 既有模式）；
- 语义：过滤发生在去重/计数/徽章链路之前，黑名单条目不进列表、不进历史自动保存、不参与去重计数。

### 3.3 P2 可选项（本轮不做，列为后续立项）

1. **prepare/params 尾部公共后缀对齐降噪**：MyBatisLogParser 对 pendingSql 行与参数行做共同后缀削减（保守阈值：公共后缀 < 短行 40% 且削后 SQL 以 `( ) ; '` 合法收尾字符结束）——应对用户自定义 pattern 的行尾噪音；
2. **超长 SQL 可见提示**：`maxParseLength` 截断发生时条目加截断徽章（沿用 dedupeCounts 徽章机制），替代静默截断；
3. **consoleFilterProvider EP 双轨捕获调研**：注册 EP 作为主通道（稳定 `execution.filters.Filter`），反射桥降级为兜底；需 runIde 真机验证 EP 与桥的双挂载去重，涉及 T-M31-03 链路改造，单独立项。

### 3.4 不采纳项与理由

- **druid SQLUtils 格式化**：对方借 druid 做方言格式化，我们引入 33MB 依赖不现实，自研 formatter 已覆盖；
- **还原 SQL 彩色回显进运行控制台**（BatsLog 的 FormatConsole + 关键字着色）：向用户运行控制台写内容侵入性强，与我们的侧边面板形态冲突；
- **原文拼接键去重**：T-LogDedupeMpMark 已实证原文键漏判，指纹键不回退；
- **移植他方 API 形态**：以行为语义为准重写，方法命名遵循本地规范，杜绝调用不存在的 API（用户铁律）。

## 四、测试计划

- `JdbcTemplateLogParserTest` 新增：
  1. 用户样本 10 行逐行 feed → 断言恰好 1 条 JDBC 条目，restoredSql 保留跨行结构（换行 + `'80a9971...'` 字符串），rawSql 无首尾 `[]`；
  2. string-aware 边界：SQL 含 `'a]'`（字符串内闭括号不闭合）、`arr[1]`（同行平衡括号）、SQL Server `[schema].[table]` 多行；
  3. 防失控：COLLECTING 中混入新 `Executing prepared SQL statement [` 行 → 前句强制闭合、后句正常；混入 NamedParameter 行同理；
  4. maxParseLength 超长多行截断；
  5. 回归：既有单行 prepared/named/参数行/存储过程用例零改动通过（快路径不变的证据）；
- `SqlLogConsoleBridgeMarkerTest`：样本经 `processConsoleLine` 端到端 → `SELECT COUNT(` 逐条判定命中 [MP 分页计数] 标记（F-44-7 语义在多行路径上仍成立）；
- `LogToSqlPanel` 粘贴路径：多行块粘贴 → 末尾 closePending 强制闭合产出；
- 日志黑名单：命中过滤 / 未命中放行 / 空设置全放行 / 与去重徽章链路无串扰；
- 全量基线：当前 3112 通过为基线，预计净增 12~15 个用例；跑 `./gradlew` 前按铁律先 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`。

## 五、风险与边界

- **行语义假设**：`[...]` 的 `]` 由 Spring 在消息末尾统一追加（`JdbcTemplate` debug 输出的 `formatStatement` 行为），多行时 `]` 只出现在最后一行行尾——string-aware 净计数不依赖此假设，双保险；
- **去重兼容**：多行 restoredSql 与 named 副本经 `normalizeSqlWhitespace` 折叠换行后判重，T-LogDedupeMpMark 键链零改动；
- **性能**：COLLECTING 仅在未闭合语句存续期间激活，逐行成本 O(行长) 扫描 + O(1) append，与现管线同级；单行快路径无任何新增开销；
- **runIde 真机**：用户项目日志含 TLog TID 字段，沙盒无法完全复现，真机重放留人工跟进（验证清单见测试沙盒验证清单.md 惯例）。

## 六、实施记录

**实施日期**：2026-09-08（当日立项当日完成）

**落地范围**：改动点一全量；改动点二核实为既有能力（见 §3.2 注记，零代码）；3.3 P2 项未实施。

**改动清单**：

- `JdbcTemplateLogParser.kt`：
  - 新增 COLLECTING 收集态（collecting/multilineBuffer/multilineNet/multilineInString 四状态字段），
    `feed` 顶层分发 `feedIdle`；收集态边界行（动作短语/NamedParameter/参数行）经 `closePending()` 强制闭合残句后重放本行，防参数误挂；
  - SQL 行分支重构：`SQL_LINE_PATTERN`（同行 `[...]` 正则）退役，改 `SQL_ACTION_PATTERN` 动作短语 +
    `scanBrackets` string-aware 净括号扫描——同行闭合走快路径（行为与旧正则对标准形态完全一致），
    未闭合进入收集；快路径闭合点由「贪婪最后一个 `]`」改为「首个平衡 `]`」，
    `[SELECT arr[1] FROM t` 形态从旧式的误提取单行变为正确进入多行收集（新增用例固化）；
  - `closePending()` 冲刷收集态残句（`LogToSqlPanel` 粘贴末尾的既有调用零改动受益）；
  - 闭合产出 `.trim()` 削首尾空白（收集机制天然引入的行首 `\n`），内部换行保留，去重经
    T-LogDedupeMpMark 的 `normalizeSqlWhitespace` 折叠，全链路兼容。
- 测试 +9：`JdbcTemplateLogParserTest` 8（用户样本 14 行原样回放、下一条语句行冲刷、字符串内 `]`、
  `''` 转义引号字符串态跨行延续、标识符平衡括号、参数行边界残句不误挂、NamedParameter 边界、超限截断）+
  `SqlLogConsoleBridgeMarkerTest` 1（样本端到端：批内两条、仅 COUNT 标 [MP 分页计数]、跨行结构保留）。

**测试结果**：定向 48/48 绿；全量 **3121/0/0/6** 绿（基线 3112 + 9，与新增用例数精确对账）。

**实施插曲**：

1. 首轮定向 6 败全为测试编写问题、非实现缺陷：续行误包 `withPrefix(...)` 日志前缀（前缀内 `[TID: null]`
   平衡括号被计入缓冲——真实控制台续行是裸文本，测试改为裸行后通过）；`takeEntries` 二次取的清空语义
   理解错（首次取已取走 COUNT 条目）；Bridge 批内应为两条（named 行自身也产出，与 T-LogDedupeMpMark
   批序语义一致）。
2. 方法名核对：对照时以行为语义为准，凡命名形态与原实现不一致者一律重写为本地规范命名，杜绝调用不存在的 API。

**遗留人工跟进**：runIde 真机以用户项目日志（TLog TID 前缀 + 跨行 SQL）重放验证，统一走 runide-demo-verify-workflow；
P2 三项（尾部公共后缀降噪、超长可见提示、consoleFilterProvider EP 双轨）按需另行立项。
