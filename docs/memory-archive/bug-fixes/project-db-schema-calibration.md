---
name: project-db-schema-calibration
description: 实体字段→列名映射的数据源校正（O-58）——插件数据源能力的完整底账（零 JDBC 调用、全走平台 DBN
  反射）、命名策略盲猜缺陷类、性能红线与失效登记约定、四类不可判定来源
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0a35f48a-3dd2-4390-b2c0-a6ed27321d0e
---

# 数据源 schema 校正（O-58）：能力底账与设计约束

用户报障（2026-09-22）：「实体类字段命名可能不标准，例如数据库字段 `order_id` 实体类字段却是 `orderID`，
这样插件预览 sql 直接是错误的，我希望可以借助数据源的能力来扶持，**当然前提是数据源可用，如果没有可用数据源需要自动降级，并不允许出现性能问题**」。

## 四层验收口径（缺一不可）

1. 能校正：实体字段名与真实列名不一致时，预览 SQL 用真实列名；
2. 可用性前提：只有数据源可用（连得上、看得见那张表）才校正；
3. 不可用自动降级：无数据源 / 社区版 / 表看不见 / 查不了 → 行为与「没有这个功能」**完全一致**；
4. 零性能回归：热路径不得新增数据源遍历。

## 现状：映射的唯一产出点是「注解优先，否则命名策略盲猜」

- MyBatis-Plus 侧：`psi/EntityResolver.kt` 的 `resolveColumn` → `@TableField` ?: `NamingStrategy.toSnakeCase(fieldName)`。
- JPA 侧：`psi/jpa/JpaEntityResolver.kt` 的 `resolveColumns(field: PsiField, …)` → `@Column(name)` ?: snake 兜底。
- `util/NamingStrategy.kt toSnakeCase` **逐字符**处理：非首位大写字母前一律插 `_` 再全小写。
  其 KDoc 自认边界：「连续缩写（如 `stationID`）会得到 `station_i_d`，与 MP 默认行为一致，可接受」——
  **`orderID` → `order_i_d` 正是用户举的例子命中的形态**。注意：MP 官方默认策略给出的是同一个 `order_i_d`，
  所以「本例在用户工程里真跑得起来」反过来说明用户侧另有列名来源（显式注解 / 自定义命名策略 / getter 侧注解）。

## 一处已实证的真缺口：JPA 注解只从字段读

`JpaEntityResolver.resolveColumns` 的入参是 `PsiField`，全部注解读取都经 `field.modifierList`。
全仓**没有任何「按属性访问（getter）读取 JPA 注解」的实现**（`psi/` 下的 getter 相关代码只有 `ArgResolver.getterToProperty`——
那是「getter 名 → 属性名」的字符串反解，与读注解无关）。
于是 `@Access(AccessType.PROPERTY)` 或「注解写在 getter 上」的工程里读不到 `@Column(name)`，
**静默回落到 snake 盲猜**——与「命名不标准」在现象上不可区分，但根因不同。

## 为什么猜测一定会错：四类不可判定来源

| # | 来源 | 数据源能否解决 |
|---|---|---|
| H1 | JPA 注解在 getter / `@Access(PROPERTY)` 上（插件读不到） | ✅ 能 |
| H2 | 连续大写缩写（`orderID` → `order_i_d`，真实常为 `order_id`） | ✅ 能 |
| H3 | 语义改名（`qty` ↔ `quantity`） | ✅ 能（归一化后仍同名则不能） |
| H4 | 全局命名策略非默认（MP `map-underscore-to-camel-case: false`、自定义 `INamingStrategy`、Hibernate 物理命名策略） | ✅ 能 |
| H5 | 语义完全无关的改名（`field1` ↔ `col_a`） | ❌ **不能，且校正层也不许猜**——保持原样（宁缺列不出错误列） |

**关键结论**：H1~H4 用「真值仲裁」一次性覆盖，不必逐条加启发式；H5 明确不做。
这是「借助数据源」相对「改 snake 算法」的根本优势——改算法只能治 H2 且会引入「插件猜的与 MP 跑的不一样」的新错。

## 数据源能力底账（本仓实测，供任何 schema 相关任务复用）

- **全仓零 JDBC 调用**：无 `java.sql.Connection` / `DriverManager` / 连接池 / 驱动依赖；
  `DatabaseMetaData` / `getColumns(` / `getPrimaryKeys(` / `getTables(` / `getSchemas(` / `getCatalogs(` 全仓零命中。
  生产 DB 访问**不持有连接、不配凭证**（唯一远程执行在 `mcp/McpDbExecutor.kt` 经平台 DBN `RemoteConnection`，
  且对外承诺「凭证永不出 IDE」）。**这是红线：校正层不得引入 JDBC 直连。**
- 表结构自省**全部经平台 DBN 反射**，主力是 `ui/highlight/DatabaseSchemaResolver.kt`：
  - `columnsCached(project?, tableNames): Map<String, ColumnMeta>` —— **零遍历**缓存读（补全热路径就用它）；
  - `prefetchTables(project?, tableNames): Int` —— 后台预热；
  - `tableMeta(project, tableName): TableMeta(comment, columns, scanBailed)` —— **会触发遍历，热路径禁用**。
- 缓存与失效（全手写 `WeakHashMap` + TTL，**全仓无 `CachedValue`**）：`metaCache`（弱键挂数据源对象，无 TTL）、
  `negativeCache`（30s TTL / 512 上限）、`dsDatabaseCache`（数据源 → URL 库名，带 `NO_DATABASE` 哨兵）；
  **库名维度已具备**（同名表在不同库解析结论不同，缓存键与匹配条件都带库名，口径复用 `execute/JdbcUrlDatabase`）。
- **失效登记约定（强制）**：`execute/MetadataInvalidator.kt` 的 `register(label, action)`，其 KDoc 明文
  「新增 schema 缓存必须在此登记」。已登记 3 项（`metaCache` / `negativeCache` / `McpSchemaContextService.indexCache`）。
- 性能红线：`resolveFromSources` 的遍历**命中后不 break**，代价随 schema 规模线性；
  既有调用点全靠「后台线程 + 缓存」控时延，范式见 `ui/SqlPreviewPanel.kt` 的 `prefetchSchemaForCompletion`
  （`executeOnPooledThread` + `current === result` 快照判据 + 完成后 `invokeLater` 刷新）。
- 社区版兼容：反射隔离，探测 `com.intellij.database.psi.DataSourceManager` 失败即 `null`（天然降级，无 `Optional`、无异常外抛）。
- **两处「合并」但没有「校正」**：`ui/diagram/DiagramSchemaEnricher.mergeColumns` 与
  `ui/highlight/SqlCompletionCandidates.mergeColumnCandidates` 都以**实体侧猜测列名**为精确键去 DB 列里查，
  查不到就静默回落——**全仓没有任何地方做「用真实列集合反证/纠正猜测值」**。这正是本任务的空白点。
- **既有预留钩子**：`psi/EntityResolver.kt` 的 `CommentSource.SCHEMA`（KDoc「数据源 schema 列/表注释，方案 B 接入后自动覆盖一切代码内注释」，
  现为 `null` 占位）——本任务是兑现它，应沿用而非另起机制。
- 设置面：**无列名策略配置项、无数据源校正开关**；`config/ProjectPluginSettings.kt` 的 `tableNameInference` 只作用于表名。

## 设计要点（写进计划）

- 新增 `psi/ColumnNameCalibrator.kt`：核心是**可注入的纯函数**
  `calibrate(table: TableInfo, realColumns: Set<String>?): TableInfo`（`null` = 查不了），
  否则无 Database 插件的单测环境测不了；生产入口 `calibrate(project?, table)` 只读 `columnsCached`。
- 匹配优先级（只在能唯一确定时改）：精确命中 → 保留；真实列集合中存在**唯一**一列其归一化形式
  （小写 + 去 `_`/`-`）与 `columnName` 归一化相同 → 采用；再与 `fieldName` 归一化比对（H1/H2 主命中路径）；
  歧义或 0 候选 → **原样保留**。只改 `columnName`，其余字段一律不动（消费方按 `fieldName` 索引）。
- 应用位置：两个产出点（`EntityResolver.resolve` / `JpaEntityResolver.resolve`）的**返回边界**，下游全链路受益。
- 缓存路线（D1 默认值）：`EntityResolver` 既有缓存**继续只存未校正产物**，校正当作返回前的廉价后处理
  → DDL 失效面只有 DB 列缓存一处（已登记 `MetadataInvalidator`），不必给实体缓存键扩 DB 签名。
  **副作用须先核实**：`resolve` 每次返回新对象而非缓存里那一个——要确认没有消费方依赖 `TableInfo` 实例同一性。
- 性能：未命中只把表名投进「在途预热集合」（去重防风暴）+ 投一次 `prefetchTables`，**本次调用原样返回未校正结果**
  （不阻塞、不等待、不轮询）；禁止 EDT 上遍历、禁止任何形式的等待。
- 降级等价性是本任务的 P0 断言：**用 `TableInfo` 的 `equals` 断言整个对象逐字段相等**，而不是只断言 SQL 文本没变。
- 大小写：真实列名按库实际大小写回写，校正层**不负责加引号**（引号由既有 `Dialect` 渲染链处理；
  Oracle/PG 折叠族是风险点，参考已交付的 T-IdentifierFoldingFamily O-51）。

## 关联

- 与 [[pitfall-hql-property-column-scope]]（O-57）**完全独立可并行**，见该文件「关联」节。
- 真机验收阶段需核实「用户实体是否本就有显式注解」——若有而插件仍猜错，真缺陷是「注解没读到」（指向 H1/getter），
  而非「需要数据源」；该核实不阻塞编码。
