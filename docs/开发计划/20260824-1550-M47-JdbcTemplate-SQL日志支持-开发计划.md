# M47：Spring JdbcTemplate SQL 日志支持 — 开发计划

> **创建日期**：2026-08-24
> **优先级**：P1（扩展日志捕获覆盖面，用户刚需）
> **涉及文件**：8 个文件，改动量 ~350 行
> **数据来源**：用户真实日志 + Spring Framework 6.x 源码（JdbcTemplate.java / StatementCreatorUtils.java）

---

## 问题分析

### 现状

当前插件日志捕获支持两种 ORM 框架：

| 框架 | 解析器 | SQL 行标识 | 参数行标识 | 来源枚举 |
|------|--------|-----------|-----------|----------|
| MyBatis-Plus | `MyBatisLogParser` | `Preparing:` | `Parameters:` | `SqlSource.MYBATIS` |
| Hibernate/JPA | `HibernateLogParser` | `Hibernate:` | `binding parameter` | `SqlSource.HIBERNATE` |

**覆盖缺口**：大量 Spring 项目使用原生 `JdbcTemplate` / `NamedParameterJdbcTemplate` 直接执行 SQL（非 MyBatis/Hibernate 体系），插件无法捕获。

### JdbcTemplate 日志格式（用户真实日志）

```
2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:711 - Executing prepared SQL query
2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - Executing prepared SQL statement [SELECT "owner", ... FROM ipm_RunPlanProject WHERE (("deleted" = ?) AND ("runPlanId" = ?))]
2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] TRACE org.springframework.jdbc.core.StatementCreatorUtils Line:227 - Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown
2026-08-24 15:46:42.446 [TID: null] [cache-preheat-1] TRACE org.springframework.jdbc.core.StatementCreatorUtils Line:227 - Setting SQL statement parameter value: column index 2, parameter value [7d0fb6b845074c4a89f56480077fbf51], value class [java.lang.String], SQL type unknown
```

### 与现有解析器的关键差异

| 维度 | MyBatis | Hibernate | JdbcTemplate |
|------|---------|-----------|--------------|
| SQL 行类名 | 无（原生）/ 含 mapper 全名 | `org.hibernate.SQL` | `org.springframework.jdbc.core.JdbcTemplate` |
| SQL 行格式 | `Preparing: SQL` | `Hibernate: SQL` | `Executing prepared SQL statement [SQL]` |
| 参数行类名 | 无（同行） | `org.hibernate.type.descriptor.jdbc` | `org.springframework.jdbc.core.StatementCreatorUtils` |
| 参数行格式 | `Parameters: 1(Long), abc(String)` | `binding parameter [1] as [BIGINT] - [1]` | `Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown` |
| 参数排序 | 日志顺序 | 位置索引 `[N]` | 显式 `column index N`（1-based） |
| 类型信息 | `(Long)` 简写 | `[BIGINT]` JDBC 类型名 | `value class [java.lang.Integer]` 全限定 Java 类名 |
| 多行 SQL | 无 | 有（需 COLLECTING 态） | 无（单行 `[SQL]`） |
| 无参语句 | 仅 `Preparing:` 行 | 仅 `Hibernate:` 行 | `Executing prepared SQL query`（无 SQL 文本） |

**核心区别**：JdbcTemplate 的参数行来自**不同类**（`StatementCreatorUtils`），且携带**1-based column index** 和**全限定 Java 类名**——解析器需独立匹配，参数按 column index 排序后回填。

---

## JdbcTemplate 日志全量格式（Spring Framework 6.x 源码实证）

### SQL 行（DEBUG 级别，类 `org.springframework.jdbc.core.JdbcTemplate`）

| 触发方法 | 日志消息 | SQL 文本 |
|----------|----------|----------|
| `execute(PreparedStatementCreator)` Line:643 | `Executing prepared SQL statement` | 有（`[SQL]`） |
| `query(PreparedStatementCreator, ResultSetExtractor)` Line:711 | `Executing prepared SQL query` | **无** |
| `queryForObject` / `queryForList` 等 | `Executing prepared SQL query` | **无** |
| `update(PreparedStatementCreator)` Line:964 | `Executing prepared SQL update` | **无** |
| `execute(String sql)` Line:445 | `Executing SQL statement [SQL]` | 有 |
| `query(String sql, ResultSetExtractor)` Line:469 | `Executing SQL query [SQL]` | 有 |
| `update(String sql)` Line:561 | `Executing SQL update [SQL]` | 有 |
| `batchUpdate(String sql, ...)` Line:1036 | `Executing SQL batch update [SQL]` | 有 |
| `batchUpdate(String sql, List<Object[]>, ...)` Line:1095 | `Executing SQL batch update [SQL] with a batch size of N` | 有 |
| `call(CallableStatementCreator, ...)` Line:1157 | `Calling stored procedure [SQL]` | 有 |

**关键发现**：
- `query`/`update` 的 PreparedStatementCreator 重载（Line:711/964）**不输出 SQL 文本**——仅输出 `Executing prepared SQL query/update`
- `query(String sql, ...)` / `update(String sql, ...)` 的字符串 SQL 重载（Line:469/561）**输出 SQL 文本**
- 两类前缀需同时匹配：`Executing prepared SQL statement`（有 SQL）和 `Executing prepared SQL query`（无 SQL，仅标记）

### 参数行（TRACE 级别，类 `org.springframework.jdbc.core.StatementCreatorUtils`）

源码（Line:230-234）：
```java
if (logger.isTraceEnabled()) {
    logger.trace("Setting SQL statement parameter value: column index " + paramIndex +
            ", parameter value [" + inValueToUse +
            "], value class [" + (inValueToUse != null ? inValueToUse.getClass().getName() : "null") +
            "], SQL type " + (sqlTypeToUse == SqlTypeValue.TYPE_UNKNOWN ? "unknown" : Integer.toString(sqlTypeToUse)));
}
```

格式：`Setting SQL statement parameter value: column index {N}, parameter value [{VALUE}], value class [{CLASS}], SQL type {TYPE}`

- `paramIndex`：**1-based** column index
- `VALUE`：参数值的 toString；`null` 字面量输出 `[null]`
- `CLASS`：全限定 Java 类名（`java.lang.Integer`）；null 值时输出 `[null]`
- `TYPE`：整数 SQL 类型码或 `unknown`

---

## 解决方案概览

| 改动 | 文件 | 说明 |
|------|------|------|
| ① 新增 `SqlSource.JDBC` | LogSqlModels.kt | 枚举扩展 |
| ② 新增 `JdbcTemplateLogParser` | log/JdbcTemplateLogParser.kt | 核心解析器 |
| ③ 新增 `JdbcTemplateLogParserTest` | test/log/JdbcTemplateLogParserTest.kt | 单元测试（~20 用例） |
| ④ 桥接双解析器 → 三解析器 | SqlLogConsoleBridge.kt | attachFilter 处新增 jdbcParser |
| ⑤ ParserConfig 新增 JDBC 配置 | SqlLogManager.kt | `jdbcEnabled` 字段 |
| ⑥ PluginSettings 新增开关 | PluginSettings.kt | `logJdbcEnabled`（默认开） |
| ⑦ 设置页新增复选框 | PluginSettingsPanel.kt | 日志捕获分组 |
| ⑧ Bundle i18n | 2 个 properties | 新增键值 |

**预期效果**：插件可捕获 JdbcTemplate 控制台日志，还原可执行 SQL，与 MyBatis/Hibernate 并列显示在日志窗口。

---

## 数据流

```
控制台输出行
    ↓ SqlLogConsoleBridge.attachFilter → LogFilterProxyHandler
    ↓ 喂行到三个解析器（各自忽略不匹配的行）
    ├─ MyBatisLogParser.feed(line)      → SqlSource.MYBATIS
    ├─ HibernateLogParser.feed(line)    → SqlSource.HIBERNATE
    └─ JdbcTemplateLogParser.feed(line) → SqlSource.JDBC  ← 新增
    ↓ takeEntries() → buffer.addEntry()
    ↓ SqlLogPanel 列表展示（按 SqlSource 着色/标记）
```

---

## 改动清单

### 改动 ①：LogSqlModels.kt — 新增 `SqlSource.JDBC`

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogSqlModels.kt`

在 `SqlSource` 枚举中新增：

```kotlin
/** Spring JdbcTemplate 控制台日志（`Executing prepared SQL statement [SQL]` / `Setting SQL statement parameter value: ...` 格式）。 */
JDBC,
```

---

### 改动 ②：新增 `JdbcTemplateLogParser.kt` — 核心解析器

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/log/JdbcTemplateLogParser.kt`

#### 设计要点

1. **两阶段匹配**：先匹配 SQL 行（提取 `[SQL]`），再匹配参数行（按 column index 收集）
2. **参数排序**：参数行按 `column index` 升序排列后回填 `?`（1-based → 0-based 索引映射）
3. **类型还原**：`value class [java.lang.Integer]` → 去掉 `java.lang.` 前缀 → `Integer` → 查 `PLAIN_TYPES` 判定引号
4. **无参语句**：`Executing prepared SQL query`（无 SQL 文本）→ 忽略（无法还原）
5. **噪声隔离**：MyBatis/Hibernate 行自然不匹配（无前缀关键字）

#### SQL 行匹配正则

```kotlin
// 匹配有 SQL 文本的语句行
// 示例：...Executing prepared SQL statement [SELECT ... FROM ... WHERE ...]
// 示例：...Executing SQL query [SELECT ...]
// 示例：...Executing SQL update [UPDATE ...]
private val SQL_LINE_PATTERN = Regex(
    """Executing\s+(?:prepared\s+SQL\s+(?:statement|query|update)|SQL\s+(?:statement|query|update)|SQL\s+batch\s+update|Calling\s+stored\s+procedure)\s*\[(.*)\]""",
    RegexOption.IGNORE_CASE,
)
```

> **注意**：`Executing prepared SQL query` 无 `[SQL]` 文本——正则要求 `[...]` 故不匹配，自然忽略。

#### 参数行匹配正则

```kotlin
// 匹配参数行
// 示例：...Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown
private val PARAM_LINE_PATTERN = Regex(
    """Setting\s+SQL\s+statement\s+parameter\s+value:\s+column\s+index\s+(\d+),\s+parameter\s+value\s+\[(.*?)],\s+value\s+class\s+\[(.*?)],\s+SQL\s+type\s+(.+)""",
    RegexOption.IGNORE_CASE,
)
```

> **注意**：`parameter value [...]` 使用非贪婪 `.*?`，但值可能含 `]`（如 `abc]`）。实际 Spring 输出 `toString()`，`]` 罕见。若值含 `]` 导致截断，fallback 用贪婪匹配到 `], value class` 前的最后一个 `]`。
> 
> **改进方案**：使用两个正则——先非贪婪，若 class 捕获为空则改用贪婪回溯。

#### 状态机

```
IDLE ──(匹配 SQL 行)──→ COLLECTING ──(匹配参数行)──→ COLLECTING
                                              ──(匹配新 SQL 行 / 非参数非 SQL 行)──→ 闭合 → IDLE
```

- **IDLE**：等待 SQL 行
- **COLLECTING**：已捕获 SQL，收集参数行；遇到新 SQL 行时闭合当前条目并开启新收集

#### 参数类型还原

```kotlin
// value class [java.lang.Integer] → Integer → PLAIN_TYPES 命中 → 裸渲染
// value class [java.lang.String]  → String  → PLAIN_TYPES 未命中 → 引号
// value class [null]              → null 字面量 → NULL
private fun mapType(className: String?): String? {
    if (className == null || className == "null") return null
    // 去掉 java.lang. 前缀
    return className.removePrefix("java.lang.")
        .removePrefix("java.math.")
        .removePrefix("java.util.")
        .removePrefix("java.sql.")
}
```

#### 完整类结构

```kotlin
class JdbcTemplateLogParser(
    private val restorer: LogSqlRestorer = LogSqlRestorer(),
    var enabled: Boolean = true,
    var maxParseLength: Int = DEFAULT_MAX_PARSE_LENGTH,
) {
    private val entries = mutableListOf<LogSqlEntry>()
    private var pendingSql: String? = null
    private val pendingParams = mutableListOf<IndexedParam>()

    fun feed(line: String) { ... }
    fun closePending(): LogSqlEntry? { ... }
    fun takeEntries(): List<LogSqlEntry> { ... }

    private data class IndexedParam(val index: Int, val value: String?, val type: String?)

    companion object {
        const val DEFAULT_MAX_PARSE_LENGTH = 20000
        // SQL 行正则（有 [SQL] 文本）
        private val SQL_LINE_PATTERN = Regex(...)
        // 参数行正则
        private val PARAM_LINE_PATTERN = Regex(...)
    }
}
```

---

### 改动 ③：新增 `JdbcTemplateLogParserTest.kt` — 单元测试

**文件**：`src/test/kotlin/cn/song/mybatisplus/visualizer/log/JdbcTemplateLogParserTest.kt`

#### 测试用例清单（~20 个）

| # | 测试场景 | 输入 | 预期 |
|---|---------|------|------|
| 1 | 基本 prepared statement + 2 参数 | 真实日志片段 | 1 条 entry，SQL 正确，2 参数按序 |
| 2 | 无参 prepared statement | `Executing prepared SQL statement [SELECT 1]` | 1 条 entry，params 为空 |
| 3 | `Executing prepared SQL query`（无 SQL 文本） | `...Executing prepared SQL query` | 0 条（忽略） |
| 4 | `Executing SQL query [SQL]`（字符串重载） | `...Executing SQL query [SELECT ...]` | 1 条 entry |
| 5 | `Executing SQL update [SQL]` | `...Executing SQL update [UPDATE ...]` | 1 条 entry |
| 6 | `Executing SQL batch update [SQL]` | `...Executing SQL batch update [UPDATE ...]` | 1 条 entry |
| 7 | `Calling stored procedure [SQL]` | `...Calling stored procedure [{call ...}]` | 1 条 entry |
| 8 | null 参数值 | `parameter value [null], value class [null]` | LogSqlParam(null, null) |
| 9 | 参数 column index 乱序 | index 2 先于 index 1 | 按 index 排序后正确回填 |
| 10 | 全限定类型名映射 | `java.lang.Integer` → Integer | 裸渲染（无引号） |
| 11 | 全限定类型名映射 | `java.lang.String` → String | 引号包裹 |
| 12 | 全限定类型名映射 | `java.math.BigDecimal` → BigDecimal | 裸渲染 |
| 13 | 全限定类型名映射 | `java.sql.Timestamp` → Timestamp | 引号包裹 |
| 14 | 多条 SQL 连续 | 2 条 statement + 各自参数 | 2 条 entry |
| 15 | MyBatis 行混入 | `Preparing: SELECT ...` | 0 条（JdbcTemplate 忽略） |
| 16 | Hibernate 行混入 | `Hibernate: select ...` | 0 条（JdbcTemplate 忽略） |
| 17 | 日志框架前缀（时间戳+DEBUG+类名） | 完整真实日志行 | 正确提取 |
| 18 | 超长 SQL 截断 | > 20000 字符 | 截断到 maxParseLength |
| 19 | disabled 时 | enabled=false | feed 不产出任何 entry |
| 20 | 参数值含特殊字符 | `O'Brien` | 引号正确转义为 `O''Brien` |
| 21 | 参数值含方括号 | `abc[def]` | 正确解析（贪婪 fallback） |
| 22 | `SQL type unknown` | 类型 unknown | 依赖 value class 判定 |
| 23 | 具体 SQL type 码 | `SQL type 12` (= VARCHAR) | 优先使用 SQL type 判定 |
| 24 | round-trip 验证 | entry.toLogText() 后再解析 | 等价 entry |

---

### 改动 ④：SqlLogConsoleBridge.kt — 三解析器

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogConsoleBridge.kt`

#### 4.1 attachFilter 处新增 jdbcParser

在 `attachFilter` 方法中，创建 `jdbcParser` 并喂行：

```kotlin
val jdbcParser = JdbcTemplateLogParser(
    enabled = config.jdbcEnabled,
    maxParseLength = config.maxParseLength,
)
// ... Proxy handler 中：
myBatisParser.feed(line)
if (hibernateEnabled) hibernateParser.feed(line)
if (jdbcEnabled) jdbcParser.feed(line)
// ...
val taken = myBatisParser.takeEntries() + hibernateParser.takeEntries() + jdbcParser.takeEntries()
```

#### 4.2 配置同步

每次喂行前同步 `jdbcParser.enabled` 和 `jdbcParser.maxParseLength`（对齐 T-xx 设置生效性审计策略）。

---

### 改动 ⑤：SqlLogManager.kt — ParserConfig 新增 JDBC 配置

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogManager.kt`

#### 5.1 ParserConfig 新增字段

```kotlin
data class ParserConfig(
    // ... 现有字段 ...
    // JDBC 解析器配置
    val jdbcEnabled: Boolean = true,
)
```

#### 5.2 parserConfig() 装配

```kotlin
private fun parserConfig(): ParserConfig {
    val state = PluginSettings.getInstance().state
    return ParserConfig(
        // ... 现有 ...
        jdbcEnabled = state.logJdbcEnabled,
    )
}
```

---

### 改动 ⑥：PluginSettings.kt — 新增状态字段

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt`

在 Hibernate 配置区域之后新增：

```kotlin
// ---------- Spring JdbcTemplate 日志捕获 ----------

/** 启用 Spring JdbcTemplate 日志解析（默认开）。 */
var logJdbcEnabled: Boolean = true,
```

---

### 改动 ⑦：PluginSettingsPanel.kt — 新增复选框

**文件**：`src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt`

在 Hibernate 启用复选框之后新增 JdbcTemplate 启用复选框：

```kotlin
private val logJdbcCheckBox = JCheckBox()

// 在 SQL 日志分组中，Hibernate 复选框行之后：
addRow(p, g, MpSqlVisualizerBundle.message("settings.log.jdbc"), logJdbcCheckBox)

// isModified()：
|| logJdbcCheckBox.isSelected != s.logJdbcEnabled

// apply()：
s.logJdbcEnabled = logJdbcCheckBox.isSelected

// reset()：
logJdbcCheckBox.isSelected = s.logJdbcEnabled
```

---

### 改动 ⑧：Bundle 文件 — 新增 i18n 键

**文件**：`src/main/resources/messages/MpSqlVisualizerBundle.properties`

```properties
settings.log.jdbc=Enable Spring JdbcTemplate log parsing
settings.log.jdbc.tip=Capture SQL logs from org.springframework.jdbc.core.JdbcTemplate (DEBUG) and StatementCreatorUtils (TRACE). Requires logging.level.org.springframework.jdbc.core=TRACE in your app.
```

**文件**：`src/main/resources/messages/MpSqlVisualizerBundle_zh.properties`

```properties
settings.log.jdbc=启用 Spring JdbcTemplate 日志解析
settings.log.jdbc.tip=捕获 org.springframework.jdbc.core.JdbcTemplate（DEBUG）和 StatementCreatorUtils（TRACE）输出的 SQL 日志。需在应用中配置 logging.level.org.springframework.jdbc.core=TRACE。
```

---

## 参数回填规则

### 类型映射表

| value class 输出 | 简化后 | PLAIN_TYPES 命中 | 渲染方式 |
|------------------|--------|-----------------|----------|
| `java.lang.Integer` | `Integer` | ✓ | 裸渲染 |
| `java.lang.Long` | `Long` | ✓ | 裸渲染 |
| `java.lang.String` | `String` | ✗ | 引号 `'...'` |
| `java.math.BigDecimal` | `BigDecimal` | ✓ | 裸渲染 |
| `java.sql.Timestamp` | `Timestamp` | ✗ | 引号 `'...'` |
| `java.time.LocalDate` | `LocalDate` | ✗ | 引号 `'...'` |
| `null` | — | — | `NULL` |

### 参数排序

JdbcTemplate 参数行含 `column index`（1-based），按升序排列后依次回填 `?`：

```
column index 2 → value "abc" → 第 2 个 ?
column index 1 → value 0     → 第 1 个 ?
排序后：index 1 先回填，index 2 后回填
```

### 占位符与参数数量不匹配

- 参数多于占位符：忽略剩余参数（对齐现有逻辑）
- 参数少于占位符：剩余 `?` 保留（面板警示）

---

## 噪声隔离

| 行类型 | JdbcTemplateLogParser 行为 | 原因 |
|--------|---------------------------|------|
| `==>  Preparing: SELECT ...` | 忽略（不匹配正则） | MyBatis 前缀 |
| `==> Parameters: 1(Long)` | 忽略 | MyBatis 前缀 |
| `Hibernate: select ...` | 忽略 | Hibernate 前缀 |
| `binding parameter [1] as [TYPE] - [VALUE]` | 忽略 | Hibernate 参数格式 |
| `2026-08-24 ... DEBUG ... JdbcTemplate ... Executing prepared SQL query` | 忽略（无 `[SQL]`） | 无 SQL 文本 |
| `2026-08-24 ... DEBUG ... JdbcTemplate ... Executing prepared SQL statement [SELECT ...]` | **匹配** | 目标行 |
| `2026-08-24 ... TRACE ... StatementCreatorUtils ... Setting SQL statement parameter value: ...` | **匹配** | 目标行 |

---

## 改动影响范围

| 改动 | 文件 | 影响范围 | 回归风险 |
|------|------|----------|----------|
| 枚举扩展 | LogSqlModels.kt | +1 枚举值 | 无 |
| 新增解析器 | JdbcTemplateLogParser.kt | 新文件 | 无（独立类） |
| 新增测试 | JdbcTemplateLogParserTest.kt | 新文件 | 无 |
| 桥接扩展 | SqlLogConsoleBridge.kt | +1 parser 实例 | 低（条件启用，独立 feed） |
| 配置扩展 | SqlLogManager.kt | +1 字段 | 无 |
| 状态扩展 | PluginSettings.kt | +1 字段 | 无（默认 true，旧 XML 取默认） |
| 设置页 | PluginSettingsPanel.kt | +1 复选框 | 无 |
| i18n | 2 个 properties | +2 键 | 无 |

**无回归风险**：
- 新解析器独立运行，不修改现有 MyBatis/Hibernate 解析逻辑
- 默认启用，但仅匹配 JdbcTemplate 特有前缀，不影响既有捕获
- 旧版设置 XML 无 `logJdbcEnabled` 字段 → 取默认值 true（与 Hibernate 一致策略）

---

## 验证方案

### 单元测试（JUnit）

1. 运行 `./gradlew test --tests "cn.song.mybatisplus.visualizer.log.JdbcTemplateLogParserTest"`
2. 确认 ~24 个用例全绿
3. 确认既有 `MyBatisLogParserTest` / `HibernateLogParserTest` 不受影响

### 集成验证（IDE 沙盒）

1. 启动 Spring Boot 项目（含 JdbcTemplate 操作）
2. 配置 `logging.level.org.springframework.jdbc.core=TRACE`
3. 插件开启日志捕捉
4. 触发 JdbcTemplate 操作 → 确认日志窗口出现 `SqlSource.JDBC` 条目
5. 双击条目 → 确认预览弹窗正确展示还原 SQL + 参数
6. 混合场景：同时有 MyBatis + JdbcTemplate 操作 → 确认两者均被捕获，互不干扰

### 边界验证

1. null 参数 → 还原为 `NULL`
2. 参数乱序 → 按 column index 正确回填
3. 无参语句 → 正确还原（params 为空）
4. 超长 SQL → 截断到 maxParseLength
5. 关闭 `logJdbcEnabled` → JdbcTemplate 行不再被捕获

---

## 工作量估算

| 改动 | 行数 | 耗时 |
|------|------|------|
| ① SqlSource 枚举 | 2 行 | 2 min |
| ② JdbcTemplateLogParser | ~180 行 | 90 min |
| ③ JdbcTemplateLogParserTest | ~250 行 | 60 min |
| ④ SqlLogConsoleBridge 扩展 | ~20 行 | 15 min |
| ⑤ ParserConfig 扩展 | ~5 行 | 5 min |
| ⑥ PluginSettings 扩展 | ~5 行 | 5 min |
| ⑦ PluginSettingsPanel 扩展 | ~15 行 | 15 min |
| ⑧ Bundle i18n | ~6 行 | 5 min |
| **合计** | **~483 行** | **~3.3 工时** |

---

## 后续可选增强（不在本次范围）

- **NamedParameterJdbcTemplate**：命名参数 `:paramName` 格式，需额外解析器
- **JdbcClient**（Spring 6.1+）：新 API，日志格式可能不同
- **批量参数**：`batchUpdate` 的 `List<Object[]>` 参数组，当前仅捕获单组
- **存储过程**：`Calling stored procedure [{call ...}]` 的 OUT 参数处理
