---
name: project-m47-jdbc-template-log
description: M47 已完成——Spring JdbcTemplate SQL 日志支持（2026-08-24），含标准 JdbcTemplate + NamedParameterJdbcTemplate 两种格式，修复 LogToSqlPanel 遗漏
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4f0d1a6c-564d-4dd8-89ac-0b4f8099ce13
---

## M47：Spring JdbcTemplate SQL 日志支持

**状态**：✅ 已完成并实施至 master（commit `2d8b0d1` + 修复 `7f4741e` + NamedParameter `c611ebb`）
**优先级**：P1
**工作量**：~720 行（含修复），10+2 文件，2480 测试全绿

### 核心实现

1. **新增 `SqlSource.JDBC` 枚举值**（LogSqlModels.kt）
2. **新增 `JdbcTemplateLogParser.kt`**（~180 行）— 两阶段匹配：SQL 行正则 → 收集参数行 → 按 `column index` 排序回填
3. **三解析器架构**：`SqlLogConsoleBridge.attachFilter` 同时创建 MyBatis + Hibernate + JdbcTemplate 解析器，各自忽略不匹配行
4. **`ParserConfig` 新增 `jdbcEnabled`**（SqlLogManager.kt）
5. **`PluginSettings` 新增 `logJdbcEnabled`**（默认 true）
6. **设置页新增复选框**（PluginSettingsPanel.kt）
7. **Bundle i18n**：`settings.log.jdbc` + `.tip` 中英文

### 关键正则（修复后）

- SQL 行：`(?:Executing\s+(?:prepared\s+SQL\s+(?:statement|update|query)|SQL\s+(?:statement|query|update)|SQL\s+batch\s+update)|Calling\s+stored\s+procedure)\s*\[(.*)\]` — 注意 `Calling stored procedure` 无 `Executing` 前缀
- 参数行：`Setting\s+SQL\s+statement\s+parameter\s+value:\s+column\s+index\s+(\d+),\s+parameter\s+\[(.*)],\s+value\s+class\s+\[(.*?)],\s+SQL\s+type\s+(.*)` — 值用贪婪匹配支持含 `]` 的值
- **NamedParameter 单行格式**（commit c611ebb）：`sql\s*=\s*(.*?)\s*,\s*parameters\s*=\s*\{([^}]*)\}` — 匹配 `sql = SELECT ... :param1 ... , parameters = {param1=0, param2=uuid}`

### 类型映射规则

`java.lang.Integer` → 去前缀 → `Integer` → 查 `PLAIN_TYPES` → 裸渲染
`java.sql.Timestamp` → `Timestamp` → 不在 PLAIN_TYPES → 引号包裹
`null` 字面量 → value class 均为 `null` → `NULL`

### 修复：LogToSqlPanel 遗漏（commit 7f4741e）

**问题**：M47 在 `SqlLogConsoleBridge`（自动捕获）加了三解析器，但 `LogToSqlPanel.convert()`（日志转 SQL 面板）遗漏——只创建 MyBatis + Hibernate 两个解析器，粘贴 JdbcTemplate 格式日志显示「未解析到 Preparing/Parameters 行」。
**修复**：`convert()` 新增 `jdbcParser` 实例 + 喂行 + `closePending()`；错误提示更新为三种格式说明。
**教训**：新增解析器时需同时检查两个入口——自动捕获（SqlLogConsoleBridge）和手动转换（LogToSqlPanel）。

### NamedParameterJdbcTemplate 格式（commit c611ebb）

**用户真实日志**：`Line:243 - sql = SELECT ... WHERE col = :param1 , parameters = {param1=0, param2=uuid}`
- 单行格式（非多行），命名参数 `:paramN`，参数以 Map 形式输出
- 按 `, key=` 边界切分参数（支持值含 `=` / `,`）
- 命名参数直接替换为渲染值（启发式：数值裸渲染，其余引号）
- 来源可能是 NamedParameterJdbcTemplate 或自定义日志拦截器

### 构建陷阱

新增 `PluginSettings.State` 字段后，旧编译缓存导致 `NoSuchMethodError: copy$default`。**解决**：`./gradlew clean test --no-configuration-cache`。

**Why**: 大量 Spring 项目使用原生 JdbcTemplate（非 MyBatis/Hibernate），插件需支持其 SQL 日志格式。
**How to apply**: 解析器独立运行，仅匹配 JdbcTemplate 特有前缀。新增解析器时需同步更新两个入口（自动捕获 + 日志转 SQL 面板）。State 字段变更后需 clean rebuild。