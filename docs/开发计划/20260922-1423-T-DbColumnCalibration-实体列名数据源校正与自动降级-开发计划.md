# T-DbColumnCalibration 实体字段→列名映射的数据源校正（可用则校正 / 不可用自动降级 / 零性能回归）— 开发计划

> 来源：2026-09-22 用户报障第二项（与 `T-HqlAliasColumnScope` 同轮，独立成题）——
> 原话「目前用户的实体类字段命名可能不标准，例如数据库字段 `order_id` 实体类字段却是 `orderID`，
> 这样插件预览 sql 直接是错误的，我希望可以借助数据源的能力来扶持，当然前提是数据源可用，
> 如果没有可用数据源需要自动降级，并不允许出现性能问题」。
> 定位依据：映射产出点与命名策略逐行实读 + `DatabaseSchemaResolver`（既有数据源 schema 解析层）
> 能力与缓存/失效/降级面完整盘点 + 既有「合并但不校正」两处消费点实读 + `EntityResolver` 里
> **既有的 `CommentSource.SCHEMA` 预留钩子**（方案 B）实证。
> 相邻事项：与 `T-HqlAliasColumnScope`（O-57）**根因、文件、验证完全独立**，可并行——
> 那项修的是「副表属性根本不参与映射」，本项修的是「参与映射但猜错了列名」。
> 编号：占用 **O-58**。

## 一、需求与判定口径

用户要的是三层意思，验收必须逐层对上：

1. **能校正**：实体字段名与真实列名不一致时，预览 SQL 用真实列名；
2. **可用性前提**：只有数据源可用（连得上、能看见那张表）才校正；
3. **不可用自动降级**：无数据源 / 社区版 / 表看不见 / 查不了 → 行为与「没有这个功能」完全一致；
4. **零性能回归**：不得因为引入校正而在预览或补全热路径上新增数据源遍历。

## 二、现状底账（代码实证）

### 2.1 映射的唯一产出点：注解优先，否则命名策略盲猜

| 链路 | 位置 | 列名来源 |
|---|---|---|
| MyBatis-Plus 实体 | `src/main/kotlin/.../psi/EntityResolver.kt:150-151` | `annotationValue(field, TABLE_FIELD) ?: NamingStrategy.toSnakeCase(fieldName)` |
| JPA / HQL 实体 | `src/main/kotlin/.../psi/jpa/JpaEntityResolver.kt:167+ resolveColumns(field: PsiField, ...)` | `@Column(name)` ?: snake 兜底（关联外键另见 `JpaMetadataReader.readToOneJoinColumns`） |

`NamingStrategy.toSnakeCase`（`src/main/kotlin/.../util/NamingStrategy.kt:20-30`）**逐字符**处理：
非首位的大写字母前一律插 `_`，然后全部小写。其 KDoc 自认边界：

> 已知边界：连续缩写（如 `stationID`）会得到 `station_i_d`，与 MP 默认行为一致，可接受。

**这条边界正是用户举的例子命中的形态**：`orderID` → `order_i_d`。真实列若是 `order_id`，
产出即错。同时注意：MP 官方默认策略给出的是同一个 `order_i_d`，所以「本例在用户工程里真跑得起来」
反过来说明用户侧**另有列名来源**（显式注解 / 自定义命名策略 / getter 侧注解）——见 §三 H1/H4。

### 2.2 一个已实证的缺口：JPA 注解只从字段读，getter 上的注解完全不可见

`JpaEntityResolver.resolveColumns(field: PsiField, ...)` 的入参是 `PsiField`，全部注解读取都经
`field.modifierList`（`JpaMetadataReader` 同款）。全仓没有任何「按属性访问（getter）读取 JPA 注解」的实现
（`psi/` 下的 getter 相关代码只有 `ArgResolver.getterToProperty`——那是「getter 名 → 属性名」的
字符串反解，与读注解无关）。

于是 JPA 的 `@Access(AccessType.PROPERTY)` 或「注解写在 getter 上」的工程里，插件读不到 `@Column(name)`，
**静默回落到 snake 盲猜**。这与用户描述的「字段命名不标准」在现象上不可区分，但根因完全不同。

### 2.3 数据源能力：**没有任何 JDBC 调用**，全部经 IntelliJ Database (DBN) 反射

- 主力解析器 `src/main/kotlin/.../ui/highlight/DatabaseSchemaResolver.kt`（708 行）：
  - `:336 tableMeta(project, tableName): TableMeta(comment, columns: Map<String, ColumnMeta>, scanBailed)`
    ——**按表名取真实列名集合**（外加注释 / typeSpec / notNull）；
  - `:71 columnsCached(project?, tableNames): Map<String, ColumnMeta>`——**零遍历**缓存读（补全热路径用）；
  - `:122 prefetchTables(project?, tableNames): Int`——后台预热。
- 缓存（全手写 `WeakHashMap` + TTL，全仓无 `CachedValue`）：
  `metaCache`（弱键挂数据源对象，无 TTL）、`negativeCache`（30s TTL，512 上限，溢出整体清空）、
  `dsDatabaseCache`（数据源 → URL 库名，带 `NO_DATABASE` 哨兵）。
- **库名维度已具备**：`:187 databaseNameOf(ds)` + P7 注释（「同名表的解析结论按库不同，
  故缓存键与匹配条件都要带库名」），口径复用 `execute/JdbcUrlDatabase`。取不到库名时退回按名匹配。
- 失效约定：`src/main/kotlin/.../execute/MetadataInvalidator.kt:189 register(label, action)`，
  其 KDoc（`:19-23`）**明文强制**：「新增 schema 缓存必须在此登记」。已登记 3 项
  （`metaCache` / `negativeCache` / `McpSchemaContextService.indexCache`）。
- 性能红线：`resolveFromSources` 的遍历**命中后不 break**（`:668-673` KDoc），代价随 schema 规模线性；
  既有全部调用点都靠「后台线程 + 缓存」控时延，范式见
  `ui/SqlPreviewPanel.kt:2196-2212 prefetchSchemaForCompletion`（`executeOnPooledThread` +
  `current === result` 快照判据 + 完成后 `invokeLater` 刷新）。
- 社区版兼容：反射隔离，探测 `com.intellij.database.psi.DataSourceManager` 失败即 `null`
  （`:439-442`），天然降级，无 `Optional`、无异常外抛。
- 生产 DB 访问**不持有连接、不配凭证**：唯一远程执行在 `mcp/McpDbExecutor.kt:238-243`
  （经 DBN `RemoteConnection`），且 `mcp/SqlVisualizerSchemaContextTool.kt:47-48` 对外承诺
  「凭证永不出 IDE」。

### 2.4 已有「合并」但没有「校正」

两处消费点都以**实体侧猜测出的列名**为精确键去 DB 列里查，查不到就静默回落：

| 位置 | 行为 |
|---|---|
| `ui/diagram/DiagramSchemaEnricher.kt:64-83 mergeColumns(entityColumns, dbColumns)` | `:69 dbColumns[column.name]` 精确取类型/注释；DB 独有列追加在后 |
| `ui/highlight/SqlCompletionCandidates.kt:28-46 mergeColumnCandidates` | 同名列用 DB 元数据覆盖注释/类型；DB 独有列进候选 |

**全仓没有任何地方做「用真实列集合反证/纠正猜测值」这一步**。这正是本任务的空白点。

### 2.5 既有预留钩子：项目自己早就规划了这条路

`psi/EntityResolver.kt:185-196`：

```
* SCHEMA（数据源 schema 列/表注释，方案 B 接入后自动覆盖一切代码内注释）最高，
* 当前实现 schema 返回 null——实际生效顺序 = 注解 > javadoc > 行注释。
...
null, // SCHEMA 预留：方案 B 接入后注入
```

同族的「方案 B」措辞还出现在 `ui/highlight/DatabaseSchemaResolver.kt:9`（M35 落地为反射隔离）
与 `ui/highlight/SqlCompletionProvider.kt:6-10`。**本需求就是这条既有设计意图的兑现**，
实施时应沿用它（把 SCHEMA 源接上），而不是另起一套平行机制。

### 2.6 设置面：无列名策略配置，无数据源校正开关

- `config/PluginSettings.kt`（81 个字段）里与 DB 相关的只有 `logJdbcEnabled` / `jdbcSqlPreviewEnabled` 两个解析开关；
- `config/ProjectPluginSettings.kt:37-70` 有 `dialect` / `tableNameInference` / `tablePrefix` / `tableSuffix` /
  `ignoredFields` 等，**没有列名策略项**（`tableNameInference` 只作用于表名）；
- 唯一的 DB 持久化状态 `execute/DataSourceSettings.kt` 只存 `defaultDataSourceId` / `defaultDataSourceName`
  （无 URL、无凭证），且未暴露到设置面板。

→ 本任务需要**新增一个开关**（全局 + 项目级双轨，沿用既有 `PluginSettings` / `ProjectPluginSettings` 模式）。

## 三、为什么猜测一定会错（四类不可判定来源）

校正层要解决的不是「把 snake 算法改聪明」，而是**猜测与真值之间信息不足**。四类来源：

| # | 来源 | 现象 | 数据源能否解决 |
|---|---|---|---|
| H1 | JPA 注解在 getter / `@Access(PROPERTY)` 上 | 插件读不到 `@Column(name)` → 回落 snake 盲猜 | ✅ 能（按归一化名匹配到真列） |
| H2 | 连续大写缩写 | `orderID` → `order_i_d`；真实常为 `order_id` | ✅ 能 |
| H3 | 语义改名 | 字段 `qty` ↔ 列 `quantity`；`createTime` ↔ `created_time` | ✅ 能（仅当归一化后仍同名则不能，见 §四 T-03） |
| H4 | 全局命名策略非默认 | MP `map-underscore-to-camel-case: false`、自定义 `INamingStrategy`、Hibernate 物理命名策略覆写 | ✅ 能 |
| H5 | 语义完全无关的改名（`field1` ↔ `col_a`） | 任何猜测都无解 | ❌ 不能——**校正层也不许猜**，如实保持原样（宁缺不错） |

**结论**：H1~H4 用「真值仲裁」一次性覆盖，不必逐条加启发式；H5 明确不做。
这也是「借助数据源」相对「改 snake 算法」的根本优势。

## 四、设计

### T-01（P0）新增列名校正器：`TableInfo` × 真实列集合 → 校正后的 `TableInfo`

- 新文件 `src/main/kotlin/.../psi/ColumnNameCalibrator.kt`（无状态单例，纯函数核心 + 注入式取数缝）。
- 接口形状（**核心必须是可注入的纯函数**，否则无法在无 Database 插件的单测环境里测）：

  ```kotlin
  // 取数缝：表名 → 真实列名集合；null = 查不了（数据源不可用 / 未缓存 / scanBailed）
  fun calibrate(table: TableInfo, realColumns: Set<String>?): TableInfo
  // 生产入口：内部只读 DatabaseSchemaResolver 缓存，未命中投递后台预热后原样返回
  fun calibrate(project: Project?, table: TableInfo): TableInfo
  ```

- 应用位置：`EntityResolver.resolve` 与 `JpaEntityResolver.resolve` 的**返回边界**
  （两个产出点的下游全链路受益：HQL/JPQL、MP Wrapper、EasyQuery、MDS、Querydsl、Criteria、ER 图）。
- **缓存与实例同一性（关键设计决定）**：
  - `EntityResolver.cache`（`psi/EntityResolver.kt:431-443`，键 `EntityCacheKey(fileUrl, fileIdentity,
    classQualifiedName, configSignature)`）**继续只存未校正的 PSI 产物**；
  - 校正在 `resolve` 返回前作为一步**廉价后处理**执行（对 DB 列集合做几次 map 查找，无遍历）；
  - 这样 DDL 变更的失效面**只有** DB 列缓存一处（`DatabaseSchemaResolver` 已登记 `MetadataInvalidator`），
    不必给实体缓存键扩 DB 签名，也不必新增失效登记项；
  - ⚠️ 代价：每次 `resolve` 返回的是**新对象**而非缓存里那一个。是否有消费方依赖 `TableInfo` 实例同一性
    必须先核实（见 §五 P-4）。
- 若实施中发现必须缓存校正结果，则退回备选方案：校正结果进 `EntityResolver` 缓存，**同时**
  在 `MetadataInvalidator.register` 登记一条「DDL 后清实体缓存」的动作（两条路线二选一定案，见 §七 D1）。

### T-02（P0）匹配算法：只在能唯一确定时改，否则原样不动

按优先级逐列判定（对 `TableInfo.columns` 的每个 `ColumnInfo`）：

| 步 | 条件 | 动作 |
|---|---|---|
| 1 | `columnName` 在真实列集合中**精确命中** | 保留（含大小写按库实际形态回写，见 T-04） |
| 2 | 真实列集合中存在**唯一**一列，其归一化形式（小写 + 去 `_`/`-`）与 `columnName` 归一化形式相同 | **采用该真实列名** |
| 3 | 真实列集合中存在**唯一**一列，其归一化形式与 `fieldName` 归一化形式相同 | 采用该真实列名（H1/H2 的主要命中路径：`orderID`→`orderid`↔`order_id`→`orderid`） |
| 4 | 命中 ≥2 列（歧义）或 0 列 | **原样保留**，可选附诊断（T-06） |

- 只改 `columnName`，**不动** `fieldName` / `isId` / `fieldType` / `comment` / `role` 等其余字段
  （消费方按 `fieldName` 索引，改了会连锁）。
- 显式注解来的列名（`@TableField("...")` / `@Column(name=...)`）是否也参与校正？
  **建议参与**（注解也可能写错/与库不同步），但第 1 步精确命中时必然保留，实际只在注解与库不一致时生效。
  → 见 §七 D2。

### T-03（P0）性能约束：热路径零遍历，未命中只投递预热

- 生产入口**只调 `columnsCached`**（零遍历），命中则校正，未命中则：
  1. 把表名投进一个「在途预热集合」（`ConcurrentHashMap.newKeySet()`，按表名去重防风暴）；
  2. 投一次 `executeOnPooledThread { prefetchTables(...) }`（表数设上限，沿用 MCP 侧 `maxTables = 20` 的量级）；
  3. **本次调用原样返回未校正结果**（不阻塞、不等待）；
  4. 预热完成后按 `SqlPreviewPanel.prefetchSchemaForCompletion` 的范式刷新（`current === result` 快照判据）。
- 明确禁止：EDT 上遍历、`tableMeta` 同步调用、任何形式的轮询等待、无上限的表名批量预热。
- 必须有**前后读数**（§五 P-5），不接受「有缓存所以没问题」这种无读数结论。

### T-04（P1）大小写与引号：校正结果要跟真实库形态一致

- 真实列名按库实际大小写回写（Oracle/PG 折叠族尤其重要——参见既有
  `T-IdentifierFoldingFamily`（O-51）的折叠方向族结论：PG 族目录来源含大写即需加引号）。
- 校正层**不负责**加引号，只负责给出正确的大小写形态；引号由既有 `Dialect` 渲染链处理。
  实施时需确认 `ColumnInfo.columnName` 的大小写在下游是否被引号策略正确消费（§五 P-6）。

### T-05（P1）降级等价性：这是本需求的 P0 断言，不是「顺便测一下」

- 数据源不可用（社区版 / 未配 / 未连）/ 表查不到 / `scanBailed = true` / 开关关闭
  → 返回的 `TableInfo` 必须与**改动前逐字段相同**（含 `columns` 顺序与全部字段值）。
- 测试形态：同一夹具跑「校正层关闭」与「取数缝恒返回 null」两条路径，断言 `TableInfo` 相等
  （`data class` 的 `equals` 可用），而不是只断言「SQL 文本没变」。
- 反向验证（必做）：把归一化分支（T-02 第 2/3 步）临时删掉 → 校正用例必红，降级用例仍绿。

### T-06（P1）开关与可见性

- 新增设置项（全局 `PluginSettings` + 项目级 `ProjectPluginSettings` 双轨，UI 落
  `PluginSettingsPanel` / `ProjectPluginSettingsConfigurable`，文案走既有 bundle 键模式）。
- 默认值：**建议默认开启**（只在能唯一确定真值时改变结果，且改变的方向是纠错），
  但涉及「预览产物会随环境变化」的预期管理 → 见 §七 D3。
- 校正发生时给一次可发现的信号：预览诊断区/注释附一条
  `/* 列名已按数据源校正：order_i_d → order_id */`（形态照既有降级注释），
  否则用户无从判断「这条 SQL 为什么和昨天不一样」。

### T-07（P2，离线补充，可选）读取 getter 侧 JPA 注解

- 针对 §2.2 的 H1：`@Column` / `@TableField` 写在 getter（或类上 `@Access(PROPERTY)`）时，
  插件当前完全读不到。补一条「字段无注解 → 查同名 getter 的注解」的回退读取。
- 与数据源**互补**：数据源不可用（社区版）时它是唯一能救 H1 的手段。
- 风险：JPA 属性访问语义（字段与 getter 注解冲突时以 `@Access` 为准）细节多，容易做出半套语义。
  → 见 §七 D4（是否纳入本轮）。

### T-08（P2，离线补充，可选）缩写感知的 snake 化

- 把 `toSnakeCase` 从「逐大写插 `_`」改为业界通行规则
  （`([a-z0-9])([A-Z])` 与 `([A-Z]+)([A-Z][a-z])` 两段），使 `orderID` → `order_id`、`URLPath` → `url_path`。
- **这是有回归风险的行为变更**：KDoc 明写「与 MP 默认行为一致」，且存量测试可能按
  `order_i_d` 形态断言；MP 官方默认不变的前提下，改成不一致会引入「插件猜的与 MP 跑的不一样」的新错。
- 结论倾向：**不作为默认行为**，仅作为数据源不可用时的可选增强；→ 见 §七 D4。

## 五、待验证前提

| # | 前提 | 验证方式 |
|---|---|---|
| P-1 | 用户工程里 `WmsPickTicketDetail` 的真实列名与实体字段名，以及是否存在 `@Column`/`@TableField`/自定义命名策略 | **必须先核实再定实现重心**。若实体上有显式注解而插件仍猜错，则真正的缺陷是「注解没读到」（指向 H1/getter，T-07），而不是「需要数据源」；若确实无注解、纯靠命名策略，则本任务（数据源校正）是正解。核实途径：用户确认 / dbx MCP 真库核对（用户定例：需真库验证先问 dbx MCP） |
| P-2 | `NamingStrategy.toSnakeCase("orderID")` 的实际产出 | 代码可判（逐字符插 `_`）应为 `order_i_d`；实施时贴一次实测读数，不要凭推导 |
| P-3 | 「归一化命中」在真实工程里能覆盖多少错配 | 需要一个真实工程的**差异清单**（对每个实体算「插件猜测列名 vs 库真实列名」的差集），否则算法阈值是拍脑袋。建议先做一次性统计脚本/临时探针跑一个真实工程，把命中率贴进 §实施记录，再定 T-02 是否够用 |
| P-4 | 是否有消费方依赖 `TableInfo` 实例同一性 | 需 grep 全部 `TableInfo` 消费点，确认没有 `IdentityHashMap` / `===` / 以实例为缓存键的用法（`QuerydslConverter.kt:572` 的 `tableCache` 按 `PsiClass` 键，安全）。若存在，则走 T-01 的备选方案（缓存校正结果） |
| P-5 | 校正层的性能开销 | 必须有真实工程**前后读数**（预览打开耗时 / schema 遍历次数 / 缓存命中率）。没有读数不允许收口 |
| P-6 | 校正后的列名大小写能否被下游引号策略正确消费 | 代码可判 + 一次多方言（MySQL/Oracle/PG）真机或单测核对；Oracle/PG 折叠族是本项的风险点 |
| P-7 | `DatabaseSchemaResolver.tableMeta` 在多 schema 同名表下是否取对库 | 代码可判：库名维度已具备（`:187 databaseNameOf` + P7 注释 + `dsDatabaseCache`），取不到库名时退回按名匹配。实施时用「同名表跨库」的注入式单测确认匹配条件确实带了库名 |
| P-8 | 社区版（无 Database 工具窗口）下确实零行为变化 | 代码可判（反射探测失败即 `null`），但需一条**真实断言**：LightPlatform 测试环境（无 `com.intellij.database`）跑全量测试应逐字节等价 |
| P-9 | 既有「精确键合并」两处（`DiagramSchemaEnricher` / `SqlCompletionCandidates`）在校正生效后是否出现重复或冲突 | 校正后实体列名与 DB 列名一致，原本「DB 独有列追加在后」的分支不再触发——需确认这是期望行为而非回归 |

## 六、验收

**自动化**：

1. 纯函数单测 `ColumnNameCalibratorTest`（注入式取数缝，无需 Project）：
   - 精确命中保留；归一化唯一命中校正（`order_i_d`→`order_id`、`qty` 无候选时不动）；
   - 歧义（两列归一化同形）→ 不动；0 候选 → 不动；
   - `realColumns = null`（查不了）→ **逐字段等于输入**；
   - `columns` 顺序不变；只改 `columnName`，其余字段逐字段不变。
2. 降级等价测试（T-05）：开关关闭 / 取数缝 null 两条路径与改动前基线相等（用 `TableInfo.equals`）。
3. 反向验证两轮：删归一化分支 → 校正用例必红；把取数缝改成「无候选时也改写」→ 降级用例必红。
   两次读数贴 §实施记录。
4. 全量 `test`（基线 491 类 / ~5600 用例）+ `testShards` 三片 + `testDatabase`。
   社区版环境（LightPlatform，无 Database 插件）下全绿是 P-8 的判据。

**真机（runIde 沙盒 + 真实数据源 + 用户工程或 demo）**：

5. 造一个字段名与列名不一致的实体（如字段 `orderID` 而列 `order_id`），预览 SQL 用真实列名；
6. 断开/删除数据源 → 预览回到改动前产物（与基线逐字符一致）；
7. 对表执行一次 `ALTER TABLE`（改列名）→ 校正结果跟随（验证 `MetadataInvalidator` 链路生效）；
8. 性能：打开预览的耗时与 schema 遍历次数，前后各一次读数（P-5）。

## 七、待用户定案

- **D1 缓存路线**：校正结果不缓存（`EntityResolver` 缓存继续存未校正产物 + 校正作廉价后处理）
  vs 校正结果入缓存 + 新增 `MetadataInvalidator` 登记项。**建议前者**（失效面最小）。
- **D2 校正范围**：是否也校正「显式注解给出的列名」（`@TableField` / `@Column`）？
  建议**是**（注解也会与库不同步），但需接受「注解写错时预览与源码不一致」的预期变化。
- **D3 默认开关**：默认开（纠错优先）vs 默认关（产物稳定性优先，用户手动开启）。
- **D4 离线补充是否纳入本轮**：T-07（读 getter 侧注解）与 T-08（缩写感知 snake 化）——
  建议 **T-07 纳入**（H1 是真缺陷且数据源不可用时无解），**T-08 不纳入默认行为**（与 MP 官方策略不一致会引入新错）。
- **D5 作用面**：先只落 HQL/JPQL 链路（用户报障场景）验收，还是一次性覆盖全部实体链路
  （MP Wrapper / EasyQuery / MDS / Querydsl / Criteria / ER 图）？**建议一次性**（同一产出点，分批改反而要维护两套语义），
  但验收按链路逐项走。

## 八、不做的事

- **不引入 JDBC 直连**：不新增驱动依赖、不建连接池、不持有 `java.sql.Connection`、
  不做 `DatabaseMetaData.getColumns()`——项目红线是「凭证永不出 IDE」
  （`mcp/SqlVisualizerSchemaContextTool.kt:47-48` 的对外承诺）。
- **不做 H5**：语义完全无关的改名（`field1` ↔ `col_a`）一律不猜，保持原样（宁缺列不出错误列，
  沿用 `JpaFromEntityMapper` 的既有原则）。
- **不把校正结果写回源码**（不生成/修改 `@TableField` / `@Column`）。
- **不做反向**：不依据 DB 列清单反推实体应有哪些字段。
- **不新增 schema 遍历**：任何新增取数都只能走 `columnsCached` 缓存读 + `prefetchTables` 后台预热。
- 不改 `DatabaseSchemaResolver` 的既有遍历语义与 `scanBailed` 判据（它是被复用方，不是本次改造对象）。

## 九、涉及文件清单

| 文件 | 改动 |
|---|---|
| `src/main/kotlin/.../psi/ColumnNameCalibrator.kt`（新增） | T-01~T-04 核心：匹配算法 + 缓存读 + 预热投递 + 降级 |
| `src/main/kotlin/.../psi/EntityResolver.kt` | T-01 应用点（`resolve` 返回边界）+ 兑现 `CommentSource.SCHEMA` 预留钩子（`:185-196`） |
| `src/main/kotlin/.../psi/jpa/JpaEntityResolver.kt` | T-01 应用点（`resolve` 返回边界）；T-07 时改 `resolveColumns` 的注解来源 |
| `src/main/kotlin/.../psi/jpa/JpaMetadataReader.kt` | T-07（若纳入）：getter 侧注解回退读取 |
| `src/main/kotlin/.../config/PluginSettings.kt` | T-06 全局开关 |
| `src/main/kotlin/.../config/ProjectPluginSettings.kt` | T-06 项目级开关 |
| `src/main/kotlin/.../config/PluginSettingsPanel.kt` / `ProjectPluginSettingsConfigurable.kt` | T-06 UI + bundle 文案 |
| `src/main/resources/.../*.properties` | T-06 文案键（中英） |
| `src/test/kotlin/.../psi/ColumnNameCalibratorTest.kt`（新增） | 六、1~3 全部用例 + 反向验证 |
| `CHANGELOG.md` / `docs/变更记录.md` | 收口时三处同步 |

## 十、与相邻任务的分界

| | `T-HqlAliasColumnScope`（O-57） | 本任务（O-58） |
|---|---|---|
| 错在哪 | 副表别名的属性**根本没进映射循环** | 进了映射循环，但**猜错了列名** |
| 需要数据源吗 | 不需要（纯 PSI） | 需要（可用则校正，不可用降级） |
| 改哪些文件 | `converter/jpa/HqlConverter.kt`、`JpqlConverter.kt`、`JpaFromEntityMapper.kt` | `psi/ColumnNameCalibrator.kt`（新）、`psi/EntityResolver.kt`、`psi/jpa/JpaEntityResolver.kt`、`config/*` |
| 能互相绕过吗 | 不能：命名完全标准时 O-57 的错照旧 | 不能：O-57 的错与列名对错无关 |

写集无交集，可并行；但**同一次真机验收建议一起做**（同一份 HQL 同时验证两处修复）。

## 十一、用户补充（2026-09-22 14:37）——开工口径

用户 2026-09-22 交代：**除 `@Embedded` 展平项（见 O-57 计划 §六 D2）待其补充预览代码后再分析外，
其余项现在开工**。对本任务的含义：

1. **§五 P-1（用户实体是否有显式注解）不阻塞开工**——数据源校正的正当性不依赖它：
   无论错因是「注解在 getter 上没读到」还是「纯命名策略猜错」，真值仲裁都能覆盖。
   P-1 仍需在真机验收阶段核实一次（它决定 T-07 是否要一并实施），但不作为编码前置。
2. **T-08（缩写感知 snake 化）默认不实施**，理由见 §四 T-08 与 §七 D4；本轮只落 T-01~T-06。
3. **D1~D5 按计划文档的建议默认值先实施**（D1 选「校正结果不入缓存」、D2 选「也校正显式注解给出的列名」、
   D3 选「默认开启」、D4 选「T-07 纳入 / T-08 不纳入」、D5 选「一次性覆盖全部实体链路」），
   实施记录里显式标注这些默认值待用户复核，用户改口径则回改。
4. **性能红线**：§五 P-5 的前后读数必须做，不接受「有缓存所以没问题」。
   本机当前有多个并行会话在跑 gradle，读数须记录测量时刻与并行状态。

## 实施记录

### 交付方式

sam 调度：单实施代理四轮迭代（初版 + 复核后三轮，其中第三轮由主调度在自审时发现健全性洞后追加），
主调度逐轮审 diff 与基础文件改动、一轮独立只读复核（写验分离），全量门禁由主调度串行跑。
分支 `t-hql-colmap`，工作树 `mpsv-hql-colmap`，基 master `c68dbc8`。

### 任务落地（对照 §四，含 D1~D5 实际取值）

| 任务 | 落地形态 |
|---|---|
| T-01 | 新增 `psi/ColumnNameCalibrator.kt`。应用点＝`EntityResolver.resolve` 与 `JpaEntityResolver.resolve` 的**返回边界**（下游全链路受益）。**D1 取值＝校正结果不入缓存**：`EntityResolver` 既有缓存继续只存未校正 PSI 产物，校正在 `resolve` 返回前作为廉价后处理执行（**缓存命中分支同样执行**），故 DDL 失效面只需 `DatabaseSchemaResolver.invalidateAll`（已登记 `MetadataInvalidator`），未新增登记项 |
| T-02 | 匹配算法：① 精确命中保留 → ② 真实列集合中**唯一**一列与 `columnName` 归一化同形 → 采用 → ③ 同上但以 `fieldName` 归一化同形 → 采用 → ④ 0 候选或歧义 → 原样。归一化＝小写 + 去 `_`/`-`。**只改 `columnName`**，其余 12 个 `ColumnInfo` 字段与 `columns` 顺序逐项不变。**D2 取值＝显式注解给出的列名参与同一算法**（精确命中必然保留，只在注解与库不一致时生效） |
| T-03 | 生产入口**只调** `columnNamesCached`（零遍历缓存读），未命中投递一次后台预热（`executeOnPooledThread`）后原样返回，不阻塞不等待不轮询。源码扫描守卫把允许集合钉成**等号**（`{columnNamesCached, prefetchTables}`），并有一条词边界断言扫整份剥注释源码防 `tableMeta` 以任何写法混入 |
| T-04 | 真实列名按库实际大小写回写；校正层**不负责加引号**（引号仍由既有 `Dialect` 链处理） |
| T-05 | `realColumns == null` / 空集合 / 开关关闭 → 返回**入参实例本身**（`assertEquals` 全字段 + `assertSame` 双断言） |
| T-06 | 全局 `columnCalibrationEnabled`（**D3 取值＝默认开启**）+ 项目级 `columnCalibration: Boolean? = null`（null=跟随全局）；项目页控件**照抄既有同类字段的 `JCheckBox` 形态**（`replaceParams`/`formatSql`/`injectLogicDelete`）。新设置项已进设置搜索索引。**校正可见信号未做**（见「未做」） |
| T-07 | **D4 取值＝T-07 纳入、T-08 不纳入**。`JpaMetadataReader` 在字段侧无 `@Column`/`@TableField` 时回退读同名 getter（`getXxx`/`isXxx`，无参方法）上的 `@Column(name)`；范围收窄为只做 `@Column` 的 name 回退。MP 侧**不加**对应能力（见下「口径切分」） |
| T-09（原判可选，本轮**补做**） | 见「计划自身被证伪」第 1 条 |
| （第三轮追加） | **健全性修复**：校正用的真值集合原取自 `columnsCached`，而它是**带元数据子集**（`collectFromDataSource` 的收录条件是 `comment 非空 ∥ typeSpec 非空 ∥ notNull==true`）。在不完整候选集上做唯一性判定会**选错列**，故给 `TableMeta` 加 `allColumnNames`（无条件收集、`columns` 内容逐字节不变）+ 新增同构零遍历入口 `columnNamesCached` |
| （第四轮追加） | ① 外键列校正；② 算法收窄；③ `renames` 歧义永久拉黑；④ 预热限流；⑤ `SettingsIO` 导出/导入；⑥ 口径切分；⑦ 测试名实与覆盖缺口。逐条见下 |

### 计划自身被证伪 / 需更正之处

| # | 计划原文 | 实测结论 |
|---|---|---|
| 1 | §四 T-09「`AssociationInfo.joinColumn` 校正属**可选件**，做与不做都不影响四层验收」 | **判断被推翻**。独立复核证明：无 `@JoinColumn` 时外键列名是 `{fieldName}_id` **猜**出来的，而 JOIN ON 直接消费它 → 「列清单已校正、ON 仍是猜测列名」→ JOIN 预览照样报 unknown column，**与用户报障「预览 sql 直接是错误的」同一类用户可见症状**，正落在第二个需求范围内。第四轮**补做**（只校 `name`，**不碰 `referencedColumnName`**——那指向目标表的列，本表真值集合对它没有仲裁权） |
| 2 | §四 T-03「表数设上限，沿用 MCP 侧 `maxTables = 20` 的量级」 | **不成立**。该上限只是 map 容量上界，溢出时 `clear()` 把**所有时间戳一起抹掉**，等于重置全部冷却、恰好抵消自己的限流。第四轮改为**淘汰最旧一条**（按插入序而非时间戳最小值——同毫秒会让「最旧」退化成随机淘汰）+ 新增**全局在途上限**（`MAX_IN_FLIGHT_PREFETCH = 4`，超限本次不投递、窗口后自然重试） |
| 3 | §四 T-02「两步都要求『唯一』才动手，故安全性质不受影响」 | **表述失实**：第 2 步已看出歧义时仍会用更弱的 `fieldName` 键走第 3 步强行选中一列。复核给了具体反例（real = `{home_city, homecity, city}`、`fieldName=city`、`columnName=home-city` → 改写成 `city` 错列）。第四轮收窄为「**无候选**才降级到 `fieldName` 键，**歧义即停**」 |
| 4 | §四 T-01「只改 `columnName`」 | 需补一条：`logicDeleteColumn`/`versionColumn`/`compositeIdColumns` 持的是**同一个列名**，不回写会让同一 `TableInfo` 内同列出现两个名字（`@TableLogic` 注入的 `AND deleted = 0` 会与列清单不一致）。已回写；且 `renames` 的歧义移除**不粘性**（同一旧名映射 V1→V2→V1 时会留下 V1），第四轮改为**永久拉黑** |
| 5 | §五 P-5「必须有性能前后读数」 | **未取得**：本环境无真实大 schema 工程可测。热路径零遍历由**结构性守卫**（允许集合等号 + `tableMeta` 词边界断言）与定向读数间接证明，**但无耗时/遍历次数前后读数**，如实登记为未验证 |
| 6 | §五 P-1「需先核实用户实体是否存在显式注解」 | **未核实**（不阻塞编码，见 §十一.1）；仍待真机验收阶段确认，它决定 T-07 的实际收益面 |
| 7 | §七 D2「建议也校正显式注解给出的列名」 | 落地后与「精确命中必然保留」等价——只在注解与库不一致时才产生行为，**无独立开关**，D2 实际不产生可分辨差异（登记） |

### 写集偏离裁定

| 偏离 | 裁定 |
|---|---|
| `config/` 实际 **5** 个文件（计划 §九 写 4） | **接受**：多出的 `PluginSettingsFolderConfigurable.kt` 是「新设置项进设置搜索索引」所必需（`ProjectPluginSettingsConfigurableTest` 的 P3 路由用例要求新键在索引里） |
| `ui/highlight/DatabaseSchemaResolver.kt` + 其测试 | **接受（主调度第三轮明确扩入）**：健全性修复必须落在真值来源处 |
| `config/SettingsIO.kt` + `SettingsIOTest` | **接受（第四轮⑤）**：同类项目级字段都在导出/导入清单内，新项漏掉会让配置导出丢设置 |
| `psi/jpa/JpaMetadataReader.kt` getter 回退 | **接受（T-07）**：字段侧读不到 `@Column` 时静默回落盲猜，属既有缺口 |

### 口径切分（交付说明必读）

**「无数据源时行为与改造前完全一致」这句话只对校正层成立，不能笼统套在本任务整体上**：

- **数据源校正层**（`ColumnNameCalibrator`）：受开关与数据源可用性双重约束；无数据源 / 表看不见 / 查不了 /
  开关关闭 → 返回入参实例本身，逐字段等价。
- **getter 侧 JPA 注解读取（T-07）**：**独立离线纠错**——读的是源码里本就写着的 `@Column`，不查数据源、
  不受本开关约束，故**无数据源时实体列名也可能与改造前不同（方向是更准）**。用户「没有可用数据源要自动降级」
  的要求针对的是校正层，不是这条离线读取。已写进 `ColumnNameCalibrator` 与 `JpaMetadataReader` 的 KDoc。
- **MP 侧无 getter 回退是刻意的**：`mybatis-plus-annotation-3.5.17` 的 `TableField` 经 `javap` 实证
  `@Target({FIELD, ANNOTATION_TYPE})`（`TableName` 为 `{TYPE, ANNOTATION_TYPE}`，`TableId` 只有 `FIELD`）
  ——`METHOD` 不在合法目标里，MP 按字段建映射，「`@TableField` 写 getter」既编译不过、MP 也不读。
  给 MP 侧加回退等于实现 MP 自己都没有的语义。

### 读数

- **定向（还原态最终）**：`BUILD SUCCESSFUL`，27 类 / **245 例 / 0 失败 / 0 错误 / 0 跳过**
  （含 `ColumnNameCalibratorTest` 23、`DatabaseSchemaResolver` 五类全绿——`TableMeta` 加字段的影响面、
  `SettingsIOTest` 11、`PluginSettingsPersistenceTest` 15、`ProjectPluginSettingsConfigurableTest` 18 等）
- **降级等价核心不变量（读数点名）**：`精确命中的列名原样保留` 含
  `assertSame("无改动时应返回入参实例本身", input, output)` → PASS；本轮新增的
  `外键列精确命中时原样保留` 同类 `assertSame` 亦 PASS。
- **反向验证（三处，读数原文均已贴入回报）**：
  1. **R3a**（让 `allColumnNames` 退化成过滤子集）→ 三条红，关键那条正是**选错列**：
     `被过滤的列本身是真实列 → 精确命中 → 保持原样 expected:<user_name> but was:<username>`；
  2. **R3b**（校正器入口改回 `columnsCached`）→ 源码守卫红：
     `[columnsCached, prefetchTables] expected:<[columnNamesCached, prefetchTables]>`；
  3. **R4-a/b/c**（摘外键列回写 / 恢复「歧义后继续步 3」/ 去掉歧义拉黑）→ 三条红，断言消息分别为
     `expected:<order_id> but was:<order_id_id>`、`expected:<home-city> but was:<city>`、
     `expected:<x> but was:<alpha>`；同批对照组（精确命中保持、无候选时按 fieldName 命中、唯一映射照常回写）保持绿。
- **探针**：`grep "RED PROBE"` 写集内 0 命中。
- **全量门禁**：见 §十二。

### 已登记边界

1. **R3b 行为层不可观测**：校正器生产分支要求 `project != null` 且 `isDatabaseAvailable()`，而测试沙盒**刻意排除** database 插件（为稳定模拟社区版降级），故「改校正器入口」这一变异只能由源码守卫观测。**未**为此外加测试缝（沙盒排除是有意的稳定化设计，动它风险大于收益）；行为层判据由 R3a 承担。
2. **守卫只钉本文件直接引用**：经其他文件的遍历 helper 间接取数可绕过；未扩扫描面。
3. `isDatabaseAvailable` / `dataSources` 每次调用跑反射、无 memo——与既有 `columnsCached` 同量级，未改。
4. `primaryKeyJoinColumns` 的校正当前**无消费点**（只被搬运），校正它只保持对象自洽、无用户可见收益。
5. `discriminatorColumn` **不动**：判别列位于继承层次的根表，根实体下是本表列、子类实体下属根表，插件无法从注解判断当前类是否根 → 拿本表真值集合去改它属于「拿错表的列名去改」。同理 `secondaryTables[].pkJoinColumns`（副表列）、`collectionAssociations` / `joinTable` 的列（中间表/目标表）一律不动。
6. 预热去重键**不含 project**：预热是幂等取数，多项目同名表最多互相抑制一个冷却窗口、不会漏预热；加上 project 会让键空间随打开项目数线性膨胀、把 20 条容量摊薄。
7. 新开关在全局页的行标签为空串，搜索项跳到「节」而非控件本身。

### 未做 / 未验证

- **T-06 校正可见信号未做**：`/* 列名已按数据源校正：… */` 需把校正记录带出校正器，承载它的
  `model/TableInfo.kt` 与渲染它的 `ui/SqlPreviewPanel.kt` **都不在本轮写集内**（后者另有会话在用），
  且该条是主调度在计划里自加的、不在用户原始需求内 → 登记为未做。
- **T-08（缩写感知 snake 化）未做**（D4 取值）：与 MP 官方策略不一致会引入「插件猜的与 MP 跑的不一样」的新错。
- **性能前后读数未取得**（§五 P-5），见上「计划自身被证伪」第 5 条。
- **真机验收未做**：runIde 沙盒 + 真实数据源 + 用户工程（含 P-1 显式注解核实、P-9 既有「精确键合并」两处校正后是否出现重复/冲突）。
- **D1~D5 按计划建议的默认值先实施，待用户复核**（D1 校正结果不入缓存 / D2 注解列名参与 / D3 默认开启 / D4 T-07 纳入、T-08 不纳入 / D5 一次性覆盖全部实体链路）。

### §十二 全量门禁

**读数（2026-09-22）**：

| 门禁 | 读数 | 判定 |
|---|---|---|
| `testShards` 三片 | **5752 例 / 0 失败 / 0 错误 / 6 跳过**（1813 / 2002 / 1937）——**合并 master `4ff2651` 后的合并态复跑**（@Embedded 展平批次单独跑时为 5692 例 1904/2045/1743，master 侧带来 +60 例） | ✅ |
| `verifyGate` 三目标 | IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63 **全 Compatible**（待移除 API 4/5/6 处、废弃 API 9/12/66 处） | ✅ |
| `testDatabase` | 10 例 8 过 **2 红＝在册环境失败** | ✅ 与改动零交集 |

**首跑撞在册 flake（如实登记）**：首跑 `testShards` 三片 2754 / 17 / 2919，`testShard3` **13 例全红**，全部为
`XmlSqlCompletionContributorTest` 的 `AlreadyDisposedException`。异常栈落在**第三方与平台代码**：测试在 caret 处触发补全，
注入语言机制向上探测时调到 **Spring 插件**的 `SpringContextSchemaInjector`，它对一个**容器已销毁**的项目做
`DomManager.getDomManager` → `getService`（我方代码一行未进栈）。判定依据三条：① 隔离跑该测试类**全绿**；
② 栈内无我方任何类；③ 分片分布严重不均＝新测试类改变权重（在册机制：「权重=历史 XML 同类取 mtime 最新，串行与分片互相喂养」）。
重跑（权重已被首跑刷新）三片分布回到 1834/2166/1690、**0 失败**，与在册「非确定——同代码可绿可红」一致。

**过程教训**：首跑把输出管给 `tail`，管道退出码取的是 `tail` 的 0，**构建失败被伪装成成功**（本仓已记过的坑）。已改为直接落文件读 `BUILD`/`EXIT` 行。

