# T-DbSchemaCompletion 数据源字段补全修复与落地 开发计划

> 日期：2026-09-17 16:51
> 状态：**已实施（2026-09-17，分支 t-dbschemacompletion，基线 dev ed3e1f1）**——P0-1 + P0-2 交付，P1（库表名候选）按计划留待 P0 真机效果后决策
> 触发：用户实测——SQL 预览页只有实体类字段补全，数据库字段补全不生效，数据源配置正常
> 取证环境：日常 IDE 2026.2.3（262.10968.63，装 v2026.9.18-beta.4）+ 2024.3 实例，日志 `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log`、`idea.1.log`

## 一、现象与结论（先说结果）

用户的观察是对的，而且是**两个独立缺口叠在一起**：

1. **数据源 schema 这条路整条死了**——`DatabaseSchemaResolver` 每次调用都在取 `ObjectKind.COLUMN` 时静默早退，列遍历从未执行过一次。所以数据源侧不仅"补全候选"没有，连本已在册的"DB 注释优先/列类型富化"（M35 / T-Embed-02）也一直是空转。
2. **补全候选集里从来没有"库列"这一项**——`SqlCompletionContributor` 的候选只有实体列（`SqlCompletionContext.columns`）与 `@TableName` 实体表。数据源列只被用来覆盖**同名列的注释**，不参与候选生成。也就是说即便缺口 1 修好，库里存在而实体里没有的列仍然不会出现。

缺口 1 是 bug（且是静默 bug，日志还给出误导性结论），缺口 2 是从未实现（`SqlCompletionProvider` 里"方案 B"预留位只走到了注释层）。

## 二、缺口 1 取证：ObjectKind 根本不是 Java enum

`ui/highlight/DatabaseSchemaResolver.kt:187`：

```kotlin
val columnKind = objectKindClass.enumConstants?.firstOrNull { (it as Enum<*>).name == "COLUMN" }
    ?: return TableMeta(null, emptyMap())
```

对用户实际运行的 Database Tools 262.10968.63（`plugins/DatabaseTools/lib/`）做字节码实证：

| 检查项 | 结果 |
|---|---|
| `com/intellij/database/model/ObjectKind.class` 中出现 `java/lang/Enum` | **0 次** |
| `$VALUES` / `valueOf` / `ordinal` | 全部 0 次 |
| 超类与组织方式 | `java.lang.Object` 子类、`Comparable<ObjectKind>`、`ourKinds: Map<String,ObjectKind>`、`ourLastDatabaseKind: AtomicInteger`、`values()` |
| 常量形态 | `COLUMN` / `TABLE` / `DATABASE` / `SCHEMA` 等为 public static final 字段 |

即 DBN 的 `ObjectKind` 是**type-safe enum 手写类**，不是 `java.lang.Enum`。`Class.enumConstants` 对这种类恒返回 `null`，于是：

- `columnKind` 必为 null → `return TableMeta(null, emptyMap())` 早退；
- 这条早退**没有任何日志**（同函数末尾 `LOG.info("DatabaseSchemaResolver scan: ...")` 在 :221，早退时到不了）；
- 上层 `tryCompute` 于是打印 `table=xxx not found in N data sources`——**把"代码没查"报成了"表不存在"**。

反证在同一份代码里：不走反射的两处同类调用一直是字段访问，工作正常——`database/DbTableSpecAdapter.kt:40` `das.getDasChildren(ObjectKind.COLUMN)`、`execute/ExecConsoleContext.kt:40` `ObjectKind.DATABASE`。

真机日志铁证：本机全部 IDE 实例的日志中，`DatabaseSchemaResolver` 共 23 次调用，**`scan:` 行 0 次**，`hit=true` 0 次，全部是 "not found"。而同名的 `mpj_demo.car_device_info` / `mpj_demo.station_info` 在 MySQL 里真实存在（information_schema 实测），2024.3 实例 15:01 起连续 40 分钟、2026.2 实例 16:33/16:35 三次调用，全部报"找不到"。

顺带澄清一条噪音：2026.2 日志里成片出现的 `#c.i.d.m.m.BasicMetaModel - To many roots from database` 来自 DBX 内部 `SqlImportUtil`（它借 BasicMetaModel 的 logger 打日志，消息是 `"To many roots from " + kind` 的 indy 拼接），2024.3 实例同样有 84 次，**判定为平台噪音，与本次缺陷无关**。

## 三、缺口 2 取证：库列不在候选集

| 关注点 | 位置 | 现状 |
|---|---|---|
| 补全候选生成 | `ui/highlight/SqlCompletionContributor.kt:89-153` | 候选 = 关键字 + `ctx.columns`（实体列）+ `ctx.candidateTables`（@TableName 实体表）+ 别名；无任何数据源列 |
| 数据源列的唯一用途 | `SqlCompletionContributor.kt:126-136` | `DatabaseSchemaResolver.columnCommentsCached` 读到的注释只用于**覆盖同名列的 tailText**，不新增候选 |
| 缓存只读不填 | `DatabaseSchemaResolver.kt:52-65` | `columnCommentsCached` 只查 `metaCache`，不触发遍历（T-Perf-09 有意为之） |
| 缓存唯一填充点 | `diagram/DiagramSchemaEnricher.kt:26`，调用点 `ui/SqlPreviewPanel.kt:1135` | 只在**含 JOIN 的预览**里后台预取；截图那种 QUERY/单表预览永不填充 → 注释覆盖恒定空转（即 T-PreviewExecDs 留下的 P1-5 未实施项） |
| 补全上下文构造 | `ui/SqlPreviewPanel.kt:1323-1332`（`buildCompletionContext`） | 字段全部来自实体（`model/PreviewResult.kt:41-47`），无数据源来源 |
| 测试缝现状 | `src/test/.../DiagramSchemaEnricherTest.kt` | 经 `metaFor` 注入 TableMeta 单测富化逻辑，**反射链本身无测试**（这就是 bug 能出厂的直接原因） |

## 四、方案

### 4.1 P0-1 修复 schema 读取链（小步，可独立交付）

1. **取 ObjectKind 常量改字段访问**（抽 internal 纯函数便于测试）：

   ```kotlin
   /** DBN 的 ObjectKind 是 type-safe enum（非 java.lang.Enum），enumConstants 恒 null——按静态字段取。 */
   internal fun resolveObjectKind(kindClass: Class<*>, name: String): Any? =
       runCatching { kindClass.getField(name).get(null) }.getOrNull()
   ```

   **实施偏离（原稿写"双路兜底 values()/按名匹配"）**：DBN 的 `values()` 与名称访问器无法从字节码确定（`getName` 不在其常量池，字段名是 `myName/name` 等），按名匹配属于 API 猜测；改为**单路 + 失败即诊断**——取不到时把该类实际常量名一并写进 bail 日志（`ObjectKind.COLUMN 取不到（实际常量：...）`），API 一旦漂移一眼可见，不猜。

2. **把三处无日志早退改成必留日志**：`model == null` / `traverser() == null` / `columnKind == null` 统一置 `bail` 原因（含数据源显示名），改动点从"改 tryCompute 文案"下沉到 `collectFromDataSource` —— 函数末尾那行 `scan:` 日志由"只有查成功才打"改为**必打并携带 `bail=` 原因**；上层 `resolveFromSources` 据此区分「查不了」与「确认无此表」两句话。

   **实施新增（原稿未预见）**：`resolveFromSources` 原把空结果一律当「该数据源没有此表」**写进负缓存**。修好 ObjectKind 后这一条会立刻变成新坑——预览打开常早于数据源建连（真机 16:33 调用早于 16:35:33 建连），那一拍会把全部涉及表钉进负缓存 30s，表现为"修了还是不补全"。故 `TableMeta` 增 `scanBailed` 标记：查不了的数据源不写负缓存、不短路后续数据源、下次调用重试。这是与根因同源的第二处缺陷，必须同批修。

3. **回归测试**（新文件 `DatabaseSchemaResolverObjectKindTest`）：用测试内假类（**非 enum**、`@JvmField` 静态常量）断言 `resolveObjectKind` 能取到；并显式断言假类 `enumConstants == null`——把"非 enum 也必须能取到"的语义钉死，杜绝回退到 `enumConstants` 写法。假类必须 **public**（反射 `Field.get` 对非 public 声明类的字段会抛 IllegalAccessException，而真实 ObjectKind 是 public）。另加 `DatabaseSchemaResolverBailTest`（3 例：查不了不写负缓存 / 不挡后续数据源 / 真·未命中仍按负缓存只扫一次）。

   守卫**负向验真已做**：把 `resolveObjectKind` 临时改回 `enumConstants` → `非 enum 类也能取到具名常量` 精确变红（3 例中 1 例），证明用例咬住的是原 bug 而非环境。

交付后可观测到的即时收益（不依赖缺口 2）：JOIN 图列类型恢复 `int(11)`/`varchar(64)`、注释恢复 DB COMMENT；补全 tailText 恢复数据源注释优先。

### 4.2 P0-2 库列进补全候选（本任务主目标）

1. `SqlCompletionContext` 增 `schemaColumns: Map<String, ColumnMeta>`（默认空，向后兼容）。**偏离**：未加 `schemaTables`——库表名候选属 P1（§4.3），本批不做。
2. `SqlPreviewPanel.buildCompletionContext` 从预热的 schema 缓存填充（**只读缓存**，不在构造期遍历）；新 API `DatabaseSchemaResolver.columnsCached(project, tableNames)` 读多表并集。
3. 候选合并抽 internal 纯函数（**新文件 `SqlCompletionCandidates.kt`**，无平台依赖、表驱动可测）：`mergeColumnCandidates(ctx)` + `columnLookupElement(candidate)`：
   - 列：实体列 ∪ 库列，**实体列排前**（保持实体/SELECT 顺序），库独有列按库返回顺序追加；
   - 同名列的注释与类型取数据源（M35 口径：DB COMMENT 优先，数据库没写才回落实体注解）；
   - tailText = 注释 + **DB 类型串**（`varchar(64)`），让"这条来自库"在弹窗里可见——直接回应本次"分不清补全来源"的困惑；注释仍注册 lookup string（M16 关键词检索语义保留）。
   - **偏离**：`SqlCompletionProvider` 接口未动（列候选来源改由上下文承载，provider 仍管关键字）。
4. **预热时机前移到预览打开**（补 T-PreviewExecDs 的 P1-5）：`showResult` 与控制台就绪（`onNativeEditorReady`）各触发一次后台 `prefetchTables`，完成回 EDT 刷新上下文。涉及表取自 `TableExtractor.extract(result.sql)` + 主表（**偏离**：原稿写"取 previewModel.statement 的 from/joins"——词法提取同时覆盖原生 QUERY 预览，后者没有 statement 模型）。误提取（别名/括号）最多一次未命中，且现在不写负缓存。

   **实施新增（原稿未预见）**：`refreshCompletionContext` 只刷新自研编辑器与 PSI 载体，**不刷新原生控制台的编辑器 userData**——而控制台正是用户实际输入面（Ultimate 路径下自研编辑器已 release）。不同步它，预热完成后控制台仍读挂载那一刻的旧上下文，库里独有的列照旧补不出来（且控制台端 Editor userData 优先级高于 document 兜底，读不到新值）。已补同步。

### 4.3 P1 库表名候选（可选件，本批未做）

`DatabaseSchemaResolver.tableNamesCached(project)`：数据源粒度一次遍历建「表名集合」索引（复用 metaCache 的弱键结构 + 失效登记），供 FROM/JOIN 位补全。先看 P0-2 效果再决定是否启用，避免过度工程。**本批状态**：未实施——用户诉求是"字段补全"，列候选已覆盖；表名候选需要一次全模型遍历建索引，性价比待真机反馈。

### 4.4 实施分支与合流口径

**实施定案（偏离原稿）**：P0-1 与 P0-2 同批落在 **dev 的任务分支 `t-dbschemacompletion`**，未拆 master 小步。理由：master 线无 `MetadataInvalidator`（DDL 后 schema 缓存失效）+ 无 T-MetaCheapSource 负缓存 + 列收录条件未放宽，在 master 上单独修 ObjectKind 会让"注释/类型富化"先恢复但补全候选依旧为空，用户仍看到"只有实体字段"；且拆两条分支要在两处重复同一修复。master 侧的即时收益随 dev→master 合流一并到位（T-MergeDev2Master 在册）。

## 五、验证与验收

1. **真机（日常 IDE，装上修复版）**：打开 demo 项目 `car_device_info` / `station_info` 的 JOIN 预览，日志必须出现 `DatabaseSchemaResolver scan: table=..., tablesFound/hit=true, foundColumns=N`（这是本次修复最硬的判据，此前 23 次调用 0 次出现）。
2. **QUERY/单表预览**（截图场景）：rbac 的 `user` 预览输入 `use` 应同时看到实体列与库列；用 **没有实体类** 的库表（rbac 的 `role_menu` / `user_role`，demo 的 `wkfl_*`）验证候选确实来自数据源而非实体。
3. **注释来源**：tailText/类型串与数据库 `COLUMN_COMMENT` 一致。
4. **降级不回归**：Community 版、未配置数据源、数据源未连接三种情况下，候选与改动前一致（实体列 + 关键字）。
5. 全量 `./gradlew test`（发版前 `testShards`）绿；新增用例：ObjectKind 取法（非 enum 假类）、候选合并表驱动（实体列/库列交集与差集/同名列注释优先级/子句过滤/别名前缀）、"早退必留日志"、缓存只读不变式。
6. 文档三处同步（变更记录 / CHANGELOG / changeNotes）。

## 六、明确不做

- 不接管、不替换 Database 插件的原生补全（预览控制台是真实 JdbcConsole，原生 schema 补全本应叠加生效；本次先补插件侧，若修复后原生侧仍不出现库对象，再单独立项查 DBN 侧）。
- 不做跨库同名表消歧（沿用 T-ConsoleDbContext 的"以数据源 URL 库为准"口径）。
- 不做库列的类型/函数/方言专属候选（列 + 表两类足够覆盖本次诉求）。

## 七、风险与陷阱

| 风险 | 说明与对策 |
|---|---|
| `getField` 也可能变 | DBN 若改 getter，需回落 `values()` + 按名匹配；两路兜底同时写，别只留一条 |
| 数据源未连接时 `getModel()` 为 null | 真机实证存在该窗口（预览打开早于建连）；预热失败必须留日志 + 建连后重试，否则"刚打开预览"仍然空 |
| EDT 红线 | 禁止在 `CompletionProvider` 内调 `tableMeta`（autopopup 每个标识符字符触发一次，遍历会在 EDT 冻结——T-Perf-09 已定案）：弹窗路径只读预构建上下文 |
| 大表候选爆炸 | 列候选设上限 + 前缀过滤 + 只在 SQL 涉及的表上展开 |
| 修复后 `scan:` 仍未出现 | 说明还有第二处断裂（model/traverser 为 null），按新加的 reason 日志分支继续定位；本次已把该路径改成必留日志，不会再静默 |
| 静默失败的教训 | 本次根因能潜伏至今，靠的是"无日志早退 + 误导性 not found"；凡回落/早退分支一律要求可诊断日志，写进评审口径 |

## 九、同批伴随修复：JOIN 图字段注释缺失（用户追加报障）

**报障**：JOIN 预览页复用 IDEA Database 插件「显示图」，但我们的 JOIN 图缺字段注释（用户给的原生显示图截图里每列都有 `/* 姓名 */` 这样的注释）。

**核查（渲染链无缺陷）**：262 的图框架 `uml/lib/uml-support.jar` 里 `components/DiagramNodeItemComponent` 的常量池实证，它调用的正是我们重写的
`getItemName(Object,Object,DiagramBuilder): SimpleColoredText` 与对应三参 `getItemType`——与 `joinItemNameText` / `joinItemTypeText` 对齐，
注释由 `ColumnEntry.comment` 内联渲染成 `/* 注释 */`。所以缺的不是渲染，是**数据**：

- JOIN 图列注释有两条来源：实体（`TableInfo.columns[].comment`，含 javadoc/注解提取）与数据源富化（[DiagramSchemaEnricher]）；
- demo 工程 MPJ 侧 `UserDO`（`@TableName("\"user\"")`，对应 `mpj_demo.user`）的字段**一个注释都没写**，实体这一路为空；
- 数据源那一路随 ObjectKind 静默早退一起死（§二）——两路全空，于是 JOIN 图整片字段注释为空，而库里每列都有 COMMENT（原生显示图直接读库所以有）。
  表头注释同理（原生显示图 `user /* 用户表 */`）。

**根因同一**：修好 §4.1 的 ObjectKind 取法后，`applyTableMeta` 按列名把 DB COMMENT/类型串回填到**声明了列的实体行**上（实体没写注释就由库补上），
JOIN 图字段注释与类型串一并恢复。

**附带缺口（本次一并修）**：富化只发生在 `updateDiagramTab` 建图时一次；预览打开常早于数据源建连，那一拍 `getModel()` 为 null → 富化空手而归，
且**不重试** → 该图的字段注释会一直空着（与 §4.2 补全侧同一个「数据源晚连接」窗口）。修复：建图时记下"本次富化一点数据源信息都没拿到"
（`enriched == base` 结构相等即未改动），原生控制台就绪（数据源已连）时补刷一次该图；只在确实空手时才登记，拿到过数据就从不重跑，避免无谓重建。

**明确不做**：不把数据库独有列（实体未映射的列）画进 JOIN 图。IDEA 原生「显示图」画的是**整张表**，我们的 JOIN 图画的是**这条 SQL 参与的列**，
这是既有产品差异（表名/别名与 ON 列的语义也依赖它），不在本次范围。


## 八、实施记录

**状态**：P0-1 + P0-2 已实施（分支 `t-dbschemacompletion`，基线 dev ed3e1f1）；P1 未实施。
**门禁**：全量 `./gradlew testShards` **4829/0/0/6 绿**（基线 4817，+12 全为本次新增用例；跳过 6 = Docker 条件测试）。

改动文件：

| 文件 | 改动 |
|---|---|
| `ui/highlight/DatabaseSchemaResolver.kt` | `resolveObjectKind`（静态字段取 ObjectKind 常量，取代 `enumConstants`）、`kindConstantNames`/`dsName` 诊断、`collectFromDataSource` 三处早退改 `bail` + scan 日志必打且携带原因、`TableMeta.scanBailed`、`resolveFromSources` 查不了不写负缓存/不短路后续数据源、新增 `columnsCached` / `prefetchTables`，退役 `columnCommentsCached`（补全热路径不再回调 resolver） |
| `ui/highlight/SqlCompletionContext.kt` | 新增 `schemaColumns`（默认空，向后兼容） |
| `ui/highlight/SqlCompletionCandidates.kt` | 新文件：`ColumnCandidate` / `mergeColumnCandidates` / `columnLookupElement`（纯逻辑） |
| `ui/highlight/SqlCompletionContributor.kt` | 列候选改消费合并结果（实体列 ∪ 库列、tailText 带 DB 类型串），去掉补全期的数据源反射调用 |
| `ui/SqlPreviewPanel.kt` | 上下文携带 `columnsCached` 结果；`prefetchSchemaForCompletion` + `completionTables`（`TableExtractor` 提取 + 主表）；`showResult` / `onNativeEditorReady` 两处触发；`refreshCompletionContext` 同步原生控制台编辑器 userData |
| `src/test/java/PlatformSignatureChecker.java` | 追加第 6 项跨版本检查：`ObjectKind.COLUMN` 静态字段可读（打印 `enumConstants/isEnum` 实况）；类路径收集补 `plugins/DatabaseTools/lib(/modules)` |
| `src/test/.../update/PlatformSignatureCrossVersionTest.kt` | KDoc 登记新反射入口（约定：新增反射入口必登记本守卫） |
| `ui/diagram/DiagramSchemaEnricher.kt` | 无改动（本次仅确认其消费语义）；测试新增 `dbCommentFillsEntityColumnsWithoutComment`（实体列无注释 → DB COMMENT/类型串回填） |
| `ui/SqlPreviewPanel.kt`（JOIN 图侧） | `diagramEnrichRetryTarget`：首次富化空手时登记，控制台就绪补刷一次（§九） |
| 测试 | 新增 `DatabaseSchemaResolverObjectKindTest`(3) / `DatabaseSchemaResolverBailTest`(3) / `SqlCompletionCandidatesTest`(5)，`SqlCompletionContributorTest` 加列候选渲染断言(1) |

**真实 DBN 类取证（不依赖沙盒 GUI 的硬验证）**：用发行版自带 JBR 跑反射探针，对**本机 243（IU-243.28141.41）与 262（IU-262.10968.63，即用户日常 IDE 同版）**两份真实 `com.intellij.database.model.ObjectKind` 实测，结论一致：

```
INFO ObjectKind.COLUMN enumConstants=null isEnum=false value=column    → RESULT OK
```

即：①`enumConstants` 恒 null、`isEnum=false`（旧实现必然早退，**且 243 同样如此——不是 262 回归，是代码假设一错到底**）；②`getField("COLUMN").get(null)` 在两版上都取到值（修复路径成立）。

该验证据源已沉淀为**跨版本守卫**：`PlatformSignatureChecker` 追加第 6 项检查（`ObjectKind.COLUMN` 静态字段可读 + 打印 `enumConstants/isEnum` 实况；未捆绑 Database Tools 的发行版打印 SKIP 不计缺失），并按项目约定「新增反射入口必登记 PlatformSignatureCrossVersionTest」由 `PlatformSignatureCrossVersionTest` 在本机 243/253/262 三族上执行（实测三族均通过）。同时补了 DBN 不在 `lib` 下（在 `plugins/DatabaseTools/lib`）且依赖 `lib` 的 DynamicBundle 的类路径收集——单 DBN jar 加载会 `NoClassDefFoundError: com/intellij/DynamicBundle`。

**遗留（真机复验项，交用户）**：日志出现 `DatabaseSchemaResolver scan: ... hit=true, foundColumns>0` 与 `[T-DbSchemaCompletion] schema 预热: resolved>0`；QUERY/单表预览（截图场景）能补出实体类没有的库列；无数据源/Community 下候选与改动前一致。另：原稿"若原生 DBN 补全仍不出现库对象则单独立项"仍挂着——本次只补插件侧。
