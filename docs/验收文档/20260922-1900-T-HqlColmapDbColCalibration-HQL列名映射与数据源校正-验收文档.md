# T-HqlAliasColumnScope + T-DbColumnCalibration（O-57 / O-58）— 验收文档

> 批次：2026-09-22 用户报障两项（HQL 场景）。分支 `t-hql-colmap`／工作树 `mpsv-hql-colmap`，基 master `ddb694a`。
> 计划：`docs/开发计划/20260922-1422-T-HqlAliasColumnScope-HQL别名作用域属性列名映射-开发计划.md`、
> `docs/开发计划/20260922-1423-T-DbColumnCalibration-实体列名数据源校正与自动降级-开发计划.md`。
> 交付方式：sam 调度（双路并行实施 + 三轮独立只读复核 + 主调度逐轮审 diff 与收口）。
> 本文件按本仓七节体例撰写；**撰写依据＝两份计划的 §实施记录与 git 实测读数，不采信派活概述**。

---

## 一、改动总览

用户报障原文两句：

> 「detail.pickTicketId 和 detail.itemId 应该是自动转驼峰的，但是没有转」
> 「目前用户的实体类字段命名可能不标准，例如数据库字段 order_id 实体类字段却是 orderID，这样插件预览 sql 直接是错误的，
> 我希望可以借助数据源的能力来扶持，当然前提是数据源可用，如果没有可用数据源需要自动降级，并不允许出现性能问题」

| 任务 | 编号 | 性质 | 用户可见结果 |
|---|---|---|---|
| T-HqlAliasColumnScope | O-57 | 缺陷修复 | 显式实体 JOIN 的 ON 条件里，副表别名属性不再原样透传；预览 SQL 从「复制出去报 `Unknown column`」变为可执行 |
| T-DbColumnCalibration | O-58 | 缺陷修复 + 新增开关 | 实体字段名与真实列名不一致时按数据源真值校正；无数据源自动降级；热路径零遍历 |

### 改动文件（git 实测：18 改 + 4 增）

**O-57（`converter/jpa/`，6 文件）**

| 文件 | 改动 |
|---|---|
| `converter/jpa/JpaAliasTableResolver.kt` | **新增**（284 行）：`JpaAliasScope` + `JpaAliasTableResolver`（别名→TableInfo）+ `JpaAliasPropertyMapper`（替换核心，两条链共用） |
| `converter/jpa/HqlConverter.kt` | 新增 6.5 步别名作用域扫描；`mapPropertyToColumn` 改按作用域分派；新增 internal `convertWithEntityResolver` 注入入口 |
| `converter/jpa/JpqlConverter.kt` | 新增 5.5 步同款扫描；作用域重载（旧 2 参重载保留回落历史行为）；移除旧 `mappedColumnNames` 去重守卫 |
| `converter/jpa/JpaFromEntityMapper.kt` | `mapSubqueryEntities` 加 `knownTableNames` 默认参数（复用别名扫描结果，免二次 PSI 检索） |
| `test/.../HqlAliasColumnMappingTest.kt` | **新增**（396 行，**17 例**） |
| `test/.../HqlConverterTest.kt` | `customDao_joinWithNamedParam` 补 `loc.zone_id` 断言（该夹具本就有此形态却只断言 `IN (?)`） |

**O-58（`psi/` + `config/` + `ui/highlight/` + `messages/`，12 改 + 2 增）**

| 文件 | 改动 |
|---|---|
| `psi/ColumnNameCalibrator.kt` | **新增**（420 行）：纯函数核心 + 生产入口 + 匹配算法 + 预热限流 + 开关 + 测试缝 |
| `psi/EntityResolver.kt`、`psi/jpa/JpaEntityResolver.kt` | `resolve` 返回边界接入校正（缓存命中分支同样执行） |
| `psi/jpa/JpaMetadataReader.kt` | getter 侧 `@Column` 回退读取（T-07） |
| `ui/highlight/DatabaseSchemaResolver.kt` | `TableMeta` 加 `allColumnNames`（无条件收集）+ 新增零遍历入口 `columnNamesCached` |
| `config/PluginSettings.kt`、`ProjectPluginSettings.kt` | 全局 `columnCalibrationEnabled`（默认 true）+ 项目级 `columnCalibration: Boolean? = null` |
| `config/PluginSettingsPanel.kt`、`ProjectPluginSettingsConfigurable.kt` | 两个面板的复选框（项目页**照抄既有同类字段的 `JCheckBox` 形态**） |
| `config/PluginSettingsFolderConfigurable.kt` | 新设置项进设置搜索索引（10 → 11） |
| `config/SettingsIO.kt` | 新开关进配置导出/导入 |
| `resources/messages/MpSqlVisualizerBundle{,_zh}.properties` | 各 +2 键（中英成对） |
| `test/.../ColumnNameCalibratorTest.kt` | **新增**（**23 例**） |
| `test/.../DatabaseSchemaResolverSchemaScopeTest.kt`、`ProjectPluginSettingsConfigurableTest.kt`、`PluginSettingsPersistenceTest.kt`、`SettingsIOTest.kt` | 用例补充与普查计数同步（均「不只改数字」，各补真实断言） |

**越界核对**：未碰 `docs/`、未碰 `ui/SqlPreviewPanel.kt`／`ui/gutter`／`ui/diagram`。`ui/` 下唯一被改的是
`ui/highlight/DatabaseSchemaResolver.kt`，属主调度第三轮明确扩入（修健全性洞，见 §三.2）。

---

## 二、自动化验证清单

### 2.1 定向测试（还原态最终）

| 任务 | 命令要点 | 读数 |
|---|---|---|
| O-57 | `test --tests "*HqlAliasColumnMappingTest*" --tests "*HqlConverter*" --tests "*JpqlConverter*" --tests "*JpaJoinOnResolver*" --tests "*JpaSubqueryAdvancedHqlTest*"` | `BUILD SUCCESSFUL`，**12 类 / 167 例 / 0 失败 / 0 错误 / 0 跳过** |
| O-58 | 四 pattern + 显式点名 27 类 | `BUILD SUCCESSFUL`，**27 类 / 245 例 / 0 失败 / 0 错误 / 0 跳过** |

### 2.2 用户报障场景（整串黄金比对）

`HqlAliasColumnMappingTest.test用户HQL_整串黄金比对_嵌入路径逐字保留` 对用户那条 HQL 做**整串 `assertEquals`**，
实测产物（= 期望串）：

```
select distinct item.code from wms_pick_ticket pickTicket join wms_pick_ticket_detail detail
on detail.pick_ticket_id = pickTicket.id join wms_item item on item.id = detail.item_id
where pickTicket.warehouse_id = ? and pickTicket.status = 'EN'
and pickTicket.created_time >= ? and pickTicket.created_time < ? order by item.code
```

- `detail.pickTicketId` → `detail.pick_ticket_id`、`detail.itemId` → `detail.item_id`（**本批主症已消**）；
- `pickTicket.updateInfo.createdTime` → `pickTicket.created_time`（**嵌入段已按 `@Embedded` 展平去掉**，见 §六 D2 后续实施）；
- 期望串是**首跑之前写死**的纯字面量，测试文件 md5 首跑前后未变（`3bbb6dd9…`）→ **非「改成实际值就绿」**。

### 2.3 反向验证（累计 9 处，读数原文均已贴入各轮回报）

| # | 变异 | 预期必红项 | 结果 |
|---|---|---|---|
| O-57-1 | 回退 T-01（别名分派失效） | 用户 HQL 同形用例 + 跨别名用例 | 10 例中 4 红 ✓ |
| O-57-2 | 回退 T-02（正则回无别名形态） | 跨别名同名字段用例 | 4 红 ✓ |
| O-57-3 | 去掉点号空白容忍 | `test点号两侧空白_仍映射且空白原样保留` | 红（`z .createdTime` 未映射）✓ |
| O-57-4 | 去掉 `ON_CLAUSE` 的 `(?i)` | `testON区域边界_小写where与join截断` | 红（ON 区域越过小写 `where`，点名 `where bad.thing` 里的 `bad`）✓ |
| O-57-5 | 注释掉语句类型闸门 | `test尾注不落在UPDATE与DELETE语句` | 红（尾注落到 UPDATE）✓ |
| O-57-6 | 去掉字面量分支 | 字面量保护两例 | 红（`'% x .create_time %'` 复现缺陷形态）✓ |
| O-58-1 (R3a) | 让 `allColumnNames` 退化成过滤子集 | 三条（含**选错列**） | 红：`expected:<user_name> but was:<username>` ✓ |
| O-58-2 (R3b) | 校正器入口改回 `columnsCached` | 源码守卫 | 红：`[columnsCached, prefetchTables] expected:<[columnNamesCached, prefetchTables]>` ✓ |
| O-58-3 (R4-a/b/c) | 摘外键列回写 / 恢复「歧义后继续步 3」/ 去掉歧义拉黑 | 三条 | 红：`order_id_id` / `city` / `alpha`，同批对照组保持绿 ✓ |

**一处被证伪的反向验证假设（如实登记）**：主调度要求「把字面量分支放到属性分支之后 → 用例 1 也应红」，
实测**绿**——Java 正则左端优先，`find()` 取起始位置最靠前的匹配，引号起点永远在引号内标识符之前。
承重的是**交替本身**（去掉字面量分支那条确实红），不是分支顺序。实施代理未为迎合该要求伪造读数。

### 2.4 探针与残留

`grep -rn "RED PROBE" --include=*.kt src/` = **0 命中**（全仓）。

---

## 三、独立复核（写验分离）

三轮只读复核，各自独立上下文（`Explore` 类，禁止改文件、禁止跑 gradle）：

### 3.1 O-57 全量复核 —— 结论「通过、无阻塞」

抓出 5 项，**其中 1 项推翻计划声明、1 项是新代码真缺陷**：

1. **推翻「不产生回归面」**：限定符与点号之间有空白（`u .code`，合法 HQL）旧实现映射、新实现不映射 → 第四轮**改行为**（正则容忍并原样保留空白）。
2. **新代码真缺陷**：`ON_CLAUSE` 前瞻大小写敏感 → 小写 HQL（常态）下 ON 区域扫到语句末尾，会点名非 ON 限定符、并误报点号字符串字面量 → 整体改 `(?i)`。
3. 尾注落到 UPDATE 致 `JpaUpdateParser` 的 WHERE 降级为 Raw → 收窄为只对 SELECT/FROM 追加。
4. 尾注文案与下游解析器隐式耦合 → 写进 KDoc 锁死不变量。
5. 黄金串断言强度不足（`contains`）→ 升级为整串比对。

同时**澄清了我方标记的两个疑点**：`JpqlConverter` 旧去重守卫移除是**纠错**（探针证实旧守卫既漏映射又没挡住链式双重映射）；
`JpaFromEntityMapper` 的 `project == null` 守卫改动对既有调用方**逐字等价**。

### 3.2 O-57 增量复核（专看第四轮新正则）—— 结论「无阻塞」

证伪了我方担心的**全部**方向：`\s*` 跨行/跨注释、数字字面量（`1.5`/`1e-3`/`1 . 5`）无误命中；空白逐字节保留；
带空白形态幂等成立；`(?i)` 未把 `only`/`on_x`/`on(` 误当 ON 起点；`'[^']*'` 抹平在标准 `''` 转义下不外溢。

抓出 1 项**我方要求扩大出来的缺陷**：字符串字面量内的点号路径仍被替换（紧邻形态旧正则本来就改，
但 O-57 的空白容忍把面扩大了）→ 第五轮收掉（单遍交替 + 字面量分支优先，并消除两份重复正则）。

### 3.3 O-58 全量复核 —— 结论「无阻塞」

**正面验证掉我方怀疑的一批方向**（不再重复验证）：早退分支均返回入参实例本身；`TableInfo.copy` 无字段重置；
缓存存未校正产物且**命中分支也校正**；`columnNamesCached` 不触发遍历；预热在后台线程；项目级 `false` 压过全局 `true`；
bundle 键成对无幽灵键；`psi → ui.highlight` 无守卫拦阻且 KDoc 属实。

抓出 7 项，**其中 1 项推翻计划判断**：

1. **推翻「T-09 外键列校正属可选件」**：无 `@JoinColumn` 时外键列名是 `{fieldName}_id` 猜出来的、而 JOIN ON 直接消费它 →
   与用户报障同一类用户可见症状 → **第四轮补做**（只校 `name`，不碰 `referencedColumnName`）。
2. 算法「第 2 步已看出歧义仍用更弱 `fieldName` 键重判」与「宁缺不错」不符 → 收窄为歧义即停。
3. `renames` 歧义移除不粘性 → 改为永久拉黑。
4. 预热上限溢出 `clear()` 抹掉全部冷却、恰好抵消限流 → 改为淘汰最旧 + 全局在途上限 4。
5. 新开关未进配置导出/导入 → 补 `SettingsIO`。
6. 测试名实不符（`testTFS09_十一项…` 只查 10 项）+ 缺持久化往返断言 → 补齐。
7. 口径切分（getter 侧离线读取不受开关约束；MP 侧无此语义）→ 写进 KDoc。

### 3.4 主调度自审（非复核，但同等重要）

- 逐轮审 diff 与基础文件改动；**独立核实** `TableMeta` 兼容性（37 处构造点全为命名参数或 ≤3 位置参数、无按值比较断言）；
- **独立发现并追加第三轮**：校正用真值集合取自 `columnsCached`，而它是**带元数据子集**（收录条件
  `comment 非空 ∥ typeSpec 非空 ∥ notNull==true`）→ 在不完整候选集上做唯一性判定会**选错列** → 修掉；
  该判断随后被 R3a 反向验证**实测证实**（`expected:<user_name> but was:<username>`）。
- **独立核实** B 关于 MyBatis-Plus 的关键证据：`javap` 实测 `TableField` 的 `@Target({FIELD, ANNOTATION_TYPE})`、
  `TableId` 只有 `FIELD`、`TableName` 为 `{TYPE, ANNOTATION_TYPE}` → MP 无 getter 注解语义，JPA 侧独有回退**是有意的**。

---

## 四、门禁复跑

| 门禁 | 读数 | 判定 |
|---|---|---|
| `testShards`（三片） | **1904 / 2045 / 1743 = 5692 例 / 0 失败 / 0 错误 / 6 跳过**，`BUILD SUCCESSFUL in 5m 6s`（@Embedded 展平批次后复跑；前一轮 1834/2166/1690 = 5690 为展平前读数） | ✅ 通过 |
| `verifyGate`（三目标） | IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63 **全 Compatible**（待移除 API 4/5/6 处、废弃 API 9/12/66 处） | ✅ 通过 |
| `testDatabase` | 10 例 / 8 过 / **2 红＝在册环境失败**（`DbDriverPipelineTest#testDriverArtifactResolvesToJarsOnClasspath` 报文「实际版本集: [8.2.0, 8.1.0, …]」、`#testSeedFilesLandWhereDbLooksForThem` 沙盒路径未播种，与 N6 计划记录逐字一致） | ✅ 与本批零交集 |

**首跑撞在册 flake（如实登记）**：首跑 `testShards` 三片 2754 / 17 / 2919，`testShard3` **13 例全红**，
全部为 `XmlSqlCompletionContributorTest` 的 `AlreadyDisposedException`。异常栈落在**第三方与平台代码**：

```
AlreadyDisposedException: Container is already disposed
  at ComponentManagerImpl.doGetService → DomManager.getDomManager
  at com.intellij.spring.dom.SpringDomUtils.isSpringXml
  at com.intellij.spring.injection.SpringContextSchemaInjector.getLanguagesToInject
  at InjectedLanguageManagerImpl.processInPlaceInjectorsFor → InjectedLanguageUtilBase.probeElementsUpInner
```

即：测试在 caret 处触发补全，注入语言机制向上探测时调到 **Spring 插件**的注入器，它对一个**容器已销毁**的项目做 `getService`。
我方代码一行未进栈。判定依据三条：① 隔离跑 `XmlSqlCompletionContributorTest` **全绿**（`BUILD SUCCESSFUL`）；
② 栈内无我方任何类；③ 分片分布 2754/17/2919 严重不均＝新测试类改变权重（在册机制：「权重=历史 XML 同类取 mtime 最新，串行与分片互相喂养」）。
重跑（权重已被首跑刷新）三片分布回到 1834/2166/1690、**0 失败**，与在册「非确定——同代码可绿可红」一致。

**过程教训（本仓已记过的坑，本轮又踩一次）**：首跑把输出管给 `tail`，管道退出码取的是 `tail` 的 0，
**构建失败被伪装成成功**。已改为直接落文件并读 `BUILD`/`EXIT` 行。

**环境登记**：本机并行会话密集（一度 19 个 java 进程、物理内存仅剩 0.9GB）。两路共用同一工作树 `build/` 目录造成过
增量缓存互踩（`Could not close incremental caches`）与 Kotlin 编译器 internal error；一次 gradle daemon 原生 OOM；
一次共享 transform 缓存被并发进程删坏（`idea64.vmoptions`/`product-info.json` 丢失，用 `--refresh-dependencies`
非破坏性自愈，未删共享缓存、未 kill 他人进程）。**教训：文件级写集不相交 ≠ 构建级不相交**——同工作树并行必须错峰跑 gradle。

---

## 五、判定卡

| 验收口径（用户原话） | 判定 | 依据 |
|---|---|---|
| 「detail.pickTicketId / detail.itemId 应该转但没转」 | ✅ 已修 | 整串黄金比对：产物为 `detail.pick_ticket_id` / `detail.item_id`；反向验证 6 处 |
| 「实体字段命名不标准 → 预览 sql 直接是错的」 | ✅ 已修（可用数据源时） | 归一化唯一命中校正；R3a 读数证明用不完整候选集会选错列、已修 |
| 「借助数据源的能力来扶持」 | ✅ 已做 | 零 JDBC 直连，复用既有 DBN 反射层（`DatabaseSchemaResolver`），含库名维度、缓存与 `MetadataInvalidator` 失效链 |
| 「前提是数据源可用」 | ✅ | 未预热 / 未连 / 社区版 → 一律原样返回 |
| 「没有可用数据源需要自动降级」 | ✅（**仅对校正层**） | 逐字段等价 + `assertSame` 入参实例；**getter 侧离线读取不受开关约束**，见 §六 口径切分 |
| 「不允许出现性能问题」 | 🟡 部分（**无前后耗时读数**） | 结构性守卫（允许集合等号 + `tableMeta` 词边界）证明热路径只读缓存；**无真实工程耗时/遍历次数前后读数**，如实登记未验证 |

---

## 六、真机走查（未做，留人工）

**未做的原因**：需 runIde 沙盒 + 可连数据源 + 用户工程；本环境数据源模型 `roots=0`，B 路径跑不起来。

待走查项：

1. 用用户那条 HQL（或等价夹具）打开预览 → 确认 `detail.pick_ticket_id` / `detail.item_id`，复制到数据源执行不再报 `Unknown column`；
2. `@Embedded` 形态产物为 `pickTicket.created_time`（嵌入段已去掉，不再是无效的三段式）；
3. **造一个字段名与列名不一致的实体**（字段 `orderID` 而列 `order_id`）→ 预览用真实列名；
4. **断开/删除数据源** → 预览回到改造前产物；
5. 对表执行一次 `ALTER TABLE`（改列名）→ 校正结果跟随（验证 `MetadataInvalidator` 链路）；
6. **性能前后读数**（计划 §五 P-5 要求的耗时/遍历次数），本批缺失；
7. **P-1**：用户实体是否本就有 `@Column`/`@TableField` 或自定义命名策略——若有显式注解而插件仍猜错，真缺陷是「注解没读到」（指向 getter 侧）；
8. **P-9**：既有「精确键合并」两处（`DiagramSchemaEnricher` / `SqlCompletionCandidates`）在校正生效后是否出现重复或冲突。

### 口径切分（交付说明必读）

**「无数据源时行为与改造前完全一致」只对校正层成立，不能笼统套在本批整体上**：

- **数据源校正层**（`ColumnNameCalibrator`）：受开关与数据源可用性双重约束；不可用时返回入参实例本身、逐字段等价。
- **getter 侧 JPA 注解读取（T-07）**：**独立离线纠错**——读的是源码里本就写着的 `@Column`，不查数据源、**不受开关约束**，
  故无数据源时实体列名也可能与改造前不同（方向是更准）。用户「没有可用数据源要自动降级」针对的是校正层。
- **MP 侧无 getter 回退是刻意的**（`javap` 实证 `TableField` 的 `@Target` 不含 `METHOD`）。

---

## 七、后续批次（未做项与已登记边界）

### 未做（需用户定夺或另有依赖）

| 项 | 原因 |
|---|---|
| ~~**`@Embedded` 展平**（O-57 计划 §六 D2）~~ | **已实施**（用户补充预览代码后指示「再次分析后调整」）：`ColumnInfo.embeddedParent` + 映射器去嵌入段，产物 `pickTicket.created_time` |
| **O-58 T-06 校正可见信号** | 需动 `model/TableInfo.kt` 与 `ui/SqlPreviewPanel.kt`（后者另有会话在用），且该条是主调度在计划里自加的、不在用户原始需求内 |
| **O-58 T-08 缩写感知 snake 化** | 与 MP 官方策略不一致会引入「插件猜的与 MP 跑的不一样」的新错，D4 定为不纳入 |
| **O-57 T-04 SELECT 副表别名展开** | 计划判「不确定就不做」，未实测 |
| **性能前后读数 / 真机走查** | 环境不具备（见 §五、§六） |

### 已登记边界（本轮明确不改，供后续批次参考）

1. **关联路径 JOIN 的副表别名仍走未映射兜底**（`join 根别名.关联属性 副表别名`）：扫描时点 JOIN 后跟的是根别名而非实体名；
   即便在 JOIN 改写后扫描，看到的只是改写产物里的表名、按短名检索不到实体类。**与改前逐字一致、无回归**；
   要覆盖需按表名反查实体（`EntityClassFinder` 一类 API）。
2. 逗号分隔 FROM 列表（`from A a, B b`，HQL 已废弃形态）不识别别名，同样走兜底。
3. `HqlConverter.mapAssociationPath` 三段式正则仍是紧邻式（`rti . ruleTable . id` 带空白不映射，改前也不映射），
   与属性映射的空白口径不一致。
4. `testON区域边界…` 第一段（`'a.b%'`）对 `(?i)` 修复不敏感（真正钉住它的是第二段），测试整体有判别力但内部混有非判别断言。
5. `\s*` 可跨 `--` 行注释；合法 HQL 上未构造出产物出错的现实反例。
6. 尾注闸门在前导注释 / CTE / 前导括号形态下丢提示（只丢提示不丢替换）。
7. 二次转换时副表别名绑定丢失而**恰成幂等**的原因；若某根表字段名与某副表列名撞名则可能破坏幂等（未构造出实际反例）。
8. O-58 R3b 行为层不可观测（测试沙盒刻意排除 database 插件），由源码守卫钉住；守卫只钉本文件直接引用。
9. `isDatabaseAvailable`/`dataSources` 每次调用跑反射、无 memo（与既有 `columnsCached` 同量级）。
10. `primaryKeyJoinColumns` 的校正当前无消费点；`discriminatorColumn` 与副表/中间表列**不动**（语义不明确或属他表列）。
11. 预热去重键不含 project（幂等取数，最多互相抑制一个冷却窗口）。

### 待用户定案（实施时按计划建议默认值先落地，留复核）

O-58 计划 §七 D1~D5 实际取值：D1 校正结果不入缓存 / D2 注解列名参与同一算法 / D3 开关默认开启 /
D4 T-07 纳入、T-08 不纳入 / D5 一次性覆盖全部实体链路。**另需用户确认一条**：
getter 侧离线读取是否应受新开关约束（本轮取「不约束」，理由是它修的是「插件读注解不完整」这个缺陷而非偏好）。
