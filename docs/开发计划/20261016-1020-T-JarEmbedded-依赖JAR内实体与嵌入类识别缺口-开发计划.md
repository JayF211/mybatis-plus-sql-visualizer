# T-JarEmbedded：依赖 JAR 内实体/嵌入类的识别缺口（开发计划）

> 仓库：`mybatis-plus-sql-visualizer`（插件仓，当前分支 `master`）
> 日期：2026-10-16
> 触发来源：用户 2026-10-16 报障 —— HQL 里 `inventory.qty.baseQty` / `inventory.sku.inventoryStatus` /
> `itemKey.lotInfo.receivedDate` 三处嵌入段路径的预览列名都不对（丢前缀），并称
> 「**jar 包里的嵌套类没有正常识别**」。
> 状态：**线一（前缀缺失）已确证＝已修未发布；线二（JAR 场景）现有信息不足以定位，故计划第一步是取证。
> 本轮只出计划、不动代码。**

---

## 一、需求

1. 依赖 JAR（无源码）里提供的实体、`@Embeddable` 嵌入类、嵌套类、`@MappedSuperclass` 父类，
   插件都要能识别到与源码类同等的信息（列清单、嵌入展平、命名档位判定）。
2. 嵌入列命名（`@Embedded` 前缀）在上述场景下也要能判对，而不是只有源码工程才成立。
3. 不引入第二套元数据读取机制，不把 HQL/JPQL 转换主链改复杂。

## 二、现状与实证

### 2.1 截图产物逐条对照（这部分**确证**）

| HQL 写法 | 截图产物 | 组件路径约定的正确产物 | 判定 |
|---|---|---|---|
| `inventory.qty.baseQty` | `inventory.base_qty` | `inventory.qty_base_qty` | 丢 `qty_` 前缀 |
| `inventory.sku.inventoryStatus` | `inventory.inventory_status` | `inventory.sku_inventory_status` | 丢 `sku_` 前缀 |
| `itemKey.lotInfo.receivedDate` | `itemKey.received_date` | `itemKey.lot_info_received_date` | 丢 `lot_info_` 前缀 |
| `inventory.sku.itemKeyId`（JOIN ON 左值） | `inventory.item_key_id` | `inventory.sku_item_key_id` | 同族 |
| `inventory.operationStatus` / `beLockStatus` | `operation_status` / `be_lock_status` | 同 | 普通列，正常 |
| `inventory.frozenStatus` | `inventory.frozenStatus`（**未映射**） | — | 见 §2.4，推断非缺陷 |

三条嵌入列的共同形态是：**嵌入段被正确去掉、前缀没加上**——这正是 `T-EmbeddedColPrefix` 已修的口径
（`psi/jpa/JpaMetadataReader.kt` 的 `embeddedFallbackColumnName`，COMPONENT_PATH 档按
`snake(嵌入段路径) + snake(内层字段)` 拼），而该修复**尚未发布**（本地提交 `8aa92d4d` 起，
`860d1511` / `4653b97b` / `22fe4045` 续）。

**所以截图本身可以完全用「用户装的版本没有该修复」解释。** 但用户强调「jar 里的类」，下面逐条查
JAR 场景是否**另有**断点——因为即便装上含修复的版本，JAR 场景仍可能因**没有信号源**而判不出前缀。

### 2.2 JAR 场景的代码级边界（逐条带证据）

1. **表名 → 实体反查只扫工程源码**：`psi/EntityClassFinder.kt:41`、`:88`、`:97` 三处一律
   `GlobalSearchScope.projectScope(project)`。依赖 JAR 里的实体**反查不到**。已知消费方：
   `reverse/SqlToJavaConverter.kt:232`（SQL→Java 反向转换）。
2. **前向解析（HQL `FROM 实体名`）走 allScope**：`ui/SqlPreviewServiceEnhancements.kt:510-511` 用
   `JavaPsiFacade.findClass(name, GlobalSearchScope.allScope(project))` → JAR 里的实体**能**解析。
   这与截图「列名确实被映射出来了（说明实体读到了）」一致。**两条路的 scope 口径不一致**，是本轮
   最值得收口的结构性问题。
3. **class 文件 PSI 的注解判定比源码脆**：`psi/jpa/JpaMetadataReader.kt:147` 的 `findFlex` 第三轨
   靠 `annotation.nameReferenceElement?.referenceName` 兜底（`:154`），而 **class 文件 PSI 没有
   nameReferenceElement**（那是源码 PSI 的东西）→ 该轨对 JAR 类恒不命中；前两轨走
   `findAnnotation(fqn)`（按注解全名），要求注解类在 classpath。同类按全名判定的还有
   `isLikelyMappedEntityClass`（`:526` `annotation.qualifiedName ?: continue`）与
   `isSpringDataEmbeddedField`（`:204`、`springDataEmbeddedPrefixOf` 一带的 `:645`）。javax/jakarta
   混用、或注解类不在 classpath 时会整轨漏判。
4. **嵌入列命名的配置探测（A 通道）在实体来自 JAR 时无信号源**：`HibernateNamingConfigProbe`
   的 `moduleResourcesDir` 靠「自实体文件路径上溯找最近的 `pom.xml` / `build.gradle*`」定位模块；
   JAR 条目路径（`…/wms-model.jar!/cn/song/…`）不是本地文件，`LocalFileSystem.findFileByPath` 取不到
   → 回落「工程全扫」。而**命名策略往往根本不在用户工程里**（在共享模型 JAR 自己的模块/构建里），
   于是 A 无论怎么扫都判不出来。
5. **C 通道（按真库列名校正）在本场景不可用**：截图预览面板明确写着「**未绑定数据源**（在控制台执行
   一次查询即可绑定）」→ `ColumnNameCalibrator` 取不到真实列集合，按设计原样返回、零改动。
6. **无「同名双份」串味风险**（排除项）：`JpaEntityResolver` 缓存键含
   `file.virtualFile?.url` + `System.identityHashCode(file)`（`:75`），源码与 JAR 两份同名类不会互相命中。

### 2.3 结论：两条线，别混

- **线一（已确证、已修未发布）**：截图三条错名 ＝ 嵌入段前缀缺失。`8aa92d4d` 起已支持
  （A 配置探测 / B 设置项 / C 数据源校正 三通道）。用户版本没有它 → 升级即应消失（前提是 A 或 C 能命中）。
- **线二（本次要补）**：**JAR 场景的识别面与信号源**——§2.2 的 ① ② ③ ④ 四条。用户说的
  「jar 里的嵌套类没正常识别」最可能落在其中之一，但**现有信息不足以判定是哪一个**：
  截图能证明实体被解析到了（列名被映射），却证明不了「哪些类来自 JAR」「命名策略写在哪」。
  故本计划第一步是**取证**（§四 W0），不先动手改。

### 2.4 `frozenStatus` 未映射（单列排查，不作本轮目标）

`inventory.frozenStatus` 原样透传，而同一句里的 `operationStatus`/`beLockStatus` 都映射成了下划线列名。
最简解释：该字段显式声明了 `@Column(name = "frozenStatus")`（列名与字段名逐字相同，映射器按
`fieldName == columnName` 原样返回）。也可能是 `@Transient` 或注解写在 getter（`@Access(PROPERTY)`）
上——`ColumnNameCalibrator` 的 KDoc 正把「注解写在 getter / `@Access(PROPERTY)`」列为已知读不到的形态。
**三种解释都不改变本计划的范围**，登记待用户实体源码确认（§六.4）。

## 三、方案与取舍

### 3.1 W0 先取证：造「类在 JAR 里」的载体，逐形态实测

仓内已有先例可照抄：`selfdev-shared-lib`（普通 JAR 模块，非 Boot 应用）+ `selfdev-framework-demo`
（依赖它的 Boot 应用），当初就是为「SQL 执行点在 JAR 里」这类 JAR 场景造的。本轮用同一手法造
**四种形态**，各测「插件能读到什么」：

| 形态 | 造法 | 要测的读数 |
|---|---|---|
| J1 实体与嵌入类都在 JAR | 模型 JAR 提供 `@Entity` + `@Embeddable`；宿主工程只依赖它 | 列清单是否含嵌入展平列、`embeddedParent/embeddedPath` 是否写入、A 探测结果 |
| J2 实体在源码、**嵌入类在 JAR** | 宿主写 `@Entity`，字段类型来自模型 JAR | 同 J1（这是最常见的拆分方式） |
| J3 嵌入类型是 JAR 里的**嵌套类** | `WmsItemKey.LotInfo` 这种静态嵌套类放 JAR | `type.resolve()` 与 `clazz.allFields` 是否正常 |
| J4 `@MappedSuperclass` 父类在 JAR | 父类放 JAR、子实体在源码 | 父类字段是否进列清单（`collectFieldsIncludingSuper` 沿 `superClass` 递归） |

判据（都要拿到**读数**，不接受推断）：插件端 headless 断言（读 demo 源码 + jar 依赖的既有手法见
`converter/jpa/DemoJpaSqlPipelineTest`）+ 必要时 `runIde` 真机走查。

### 3.2 预置修法（按取证结果取舍，不预先全做）

- **W1 给 A 扩信号源**：除工程内 `application*.yml/.properties`，再扫 classpath / JAR 侧的命名策略声明——
  `META-INF/persistence.xml` 的 `hibernate.implicit_naming_strategy`、`hibernate.cfg.xml`、以及 JAR 内
  `application*.yml`。理由：共享模型 JAR 常自带这些声明，而用户工程里**根本没有**（§2.2.4）。
- **W2 反查 scope 对齐**：`EntityClassFinder` 由 `projectScope` 改为「源码优先 + 库兜底」，并定
  「同名双份取源码」的择优规则；顺带审 §2.2.2 两条路 scope 口径不一致的问题。影响面＝按表名找实体的
  全部链路（SQL→Java 反向、实体选择候选等），需评估大工程全库扫描的性能（缓存 + 限流）。
- **W3 class 文件注解判定补齐**：把「按注解全名 / `nameReferenceElement`」的判定改成对 JAR 类也成立的
  形态（`findFlex` 前两轨 + 文本兜底），逐个过 `isLikelyMappedEntityClass` / `isSpringDataEmbeddedField` /
  `springDataEmbeddedPrefixOf`。这是「JAR 类识别退化」的最可能的单点。
- **W4（可选）预览侧可发现提示**：嵌入列名由兜底推出时给一次提示（带「设置 → 嵌入列命名」入口），
  与既有「未绑定数据源」提示同域——避免用户再次把它当缺陷上报。

### 3.3 不做

不改 HQL/JPQL 转换主链；不引入「读字节码注解」的新框架（平台 PSI 已能给出 class 文件的注解，问题在
**判定口径**而非取数能力）；不为 JAR 场景新增设置开关（能用现有「嵌入列命名」档位表达的就不加）。

## 四、改动清单（取证后细化）

- **W0（取证，必做）**：demo 仓新增 JAR 载体（模型 JAR + 宿主工程，照 `selfdev-shared-lib` 手法），
  四形态各一份；插件仓加 headless 断言与真机走查记录；产出「哪一环断」的实测表。
- W1~W3：按 W0 结果落地（预计至少 W3；若命名策略确实只在 JAR 内声明，则 W1 必做）。
- W4：视用户是否会再报同类而定。
- `CHANGELOG.md`、本计划 §七 回填。

## 五、验证方案

- **定向**：`psi/jpa/JpaEntityResolverMappingDeepTest`、`psi/EntityResolverEmbeddedTest`、
  `converter/jpa/*`（嵌入相关）、`ui/gutter/Demo*Anchor*Test`（锚点普查）；W0 新增用例自带读数。
- **反向验证**：凡新增「JAR 形态判定」的分支，摘掉即必须变红（贴两次读数）；既有源码形态用例保持绿
  （证明是「加一档」不是「换一档」）。
- **真机**：JAR 载体的四形态走查（`runIde`），确认预览列名与真库一致；demo 侧真跑建表对照
  （照 `jpa-embedded-prefix-demo` 的 `/columns` 手法）。
- 不跑全量/门禁（留待收口）。

## 六、待验证前提（**先证伪再动手**）

1. 用户的 `WmsInventory` / `WmsInventoryQty` / `WmsSku` / `WmsItemKey.LotInfo` **到底哪些在 JAR、
   哪些在源码**——需用户给出工程结构（模块划分）或 `mvn dependency:tree` 片段。这是线二成立与否的前提。
2. 用户的命名策略**写在哪**：工程 `application*.yml`、自定义 `ImplicitNamingStrategy` 类、还是 JAR 内
   声明（`persistence.xml` / JAR 自带 yml）——决定 A 能否命中、W1 是否必做。
3. 用户装的插件版本（About 对话框里的「内部构建时间戳」）——确认截图是否就是「修复前」，避免重复定位。
4. `frozenStatus` 未映射的真实原因（显式 `@Column` / `@Transient` / getter 注解）——需实体源码；
   **不属本轮目标**。
5. class 文件 PSI 上 `findAnnotation(fqn)` 的实际命中率（javax/jakarta 两档、注解类不在 classpath 档）——
   W0 实测，别凭直觉。
6. `EntityClassFinder` 扩到库 scope 的性能影响（大工程全库类扫描）——实测 + 缓存评估后再定 W2 形态。
7. **「JAR 里的嵌套类」的准确含义**：用户原话可能指「嵌入类」（`@Embeddable`）或「嵌套类」
   （`static class`）——J3 两种都要造，别只按一种理解做。

## 七、实施记录

### 7.1 W1 已实施（2026-10-16，master）

**关键收窄（用户回话后）**：用户确认「这些实体类都在 JAR 里」。回看截图产物即可证**读 JAR 类本身是通的**
——`inventory.operation_status` / `be_lock_status` / `item_key_id` 这些列都映射出来了，说明实体解析、
class 文件注解读取、`@Embedded` 展平三条都成立；缺的只是「这个工程用了 component-path」这个**信号**。
故 §四 W0 里「JAR 类读取能力取证」暂缓（截图本身已是反证），直接做 W1。

**改动**：`HibernateNamingConfigProbe` 的扫描面分两档——

- **模块已知**（实体在工程源码里，能上溯找到模块标记）：行为不变，只看该模块 `src/main/resources`
  （含 `META-INF/`），防兄弟模块配置外溢；
- **模块不可知**（实体来自依赖 JAR：jar 条目路径不是本地文件，`LocalFileSystem.findFileByPath`
  取不到模块根）：退到**工程级信号**，三条互补的路——
  1. `application*.{yml,yaml,properties}` 按扩展名索引扫（能覆盖 Boot 的连字符键 `naming.implicit-strategy`）；
  2. 按**设置键** `implicit_naming_strategy` 走词索引找**任意文件**（不限文件类型，`META-INF/persistence.xml`、
     `orm.xml`、自定义 properties 一并覆盖；命中后读该文件文本再走同一判据）；
  3. 词索引在**Java 源码**里找 `ImplicitNamingStrategyComponentPathImpl` /
     `DefaultComponentSafeNamingStrategy` 类名——`props.put("hibernate.implicit_naming_strategy", X.class)`
     这类**代码式配置**在 `application*.yml` 里根本看不见，是此前最大的盲区。

判据本身未改（`indicatesComponentPath` 仍是纯文本标记匹配 + 剔整行注释），故纯逻辑用例不回归。

**测试**：新增 `HibernateNamingConfigProbeProjectTest` 3 例（源码式配置命中 / 按设置键命中
`persistence.xml` / 无信号回落无前缀）；既有 `HibernateNamingConfigProbeTest`（纯逻辑 + 模块上溯）不回归。

**读数**：10 个类 **125 例 0 失败 0 错误**（含 `SettingsRealityGuardTest`、demo 锚点门禁
`DemoEmbeddedPrefixReproAnchorTest`、`CustomDaoHqlLocatorTest` / `HqlQueryLocatorTest`、
`ColumnNameCalibratorTest` 等回归）。

**反向验证**：把「模块不可知 → 工程级信号」整档短路为 `false` → `HibernateNamingConfigProbeProjectTest`
**2 例红**（源码式配置 / `persistence.xml`），「无信号回落无前缀」保持绿，`GRADLE_EXIT=1`；
还原后复跑 125/0/0。

**踩坑（记录）**：本仓平台版本（243）里 `PsiElementProcessor` 在 `com.intellij.psi.search` 而非
`com.intellij.psi`；`PsiSearchHelper.processElementsWithWord` 只接受 `TextOccurenceProcessor`
（`execute(element, offsetInElement)`）；且 `FilenameIndex.getFilesByName` 返回 `Array<PsiFile>` 而非
`Collection<VirtualFile>`（与 `getAllFilesByExt` 不同）。另：按文件名查表在 light 测试里看不到 fixture
文件（`getFilesByName` 那条用例首轮红），改走「设置键词索引」后既更通用也可测。

**未做**：W0 的 JAR 载体取证（改由子智能体在 demo 仓补，见 §7.2）；W2（`EntityClassFinder` 库兜底）与
W3（class 文件注解判定加固）——W2 仍是真缺口（表名→实体反查在 JAR 场景下找不到），但与本轮报障的
预览列名无关，另立批次。

### 7.1b W1b 已实施（同日）：**自定义** `ImplicitNamingStrategy` 的语义识别

用户追问「自定义的 `ImplicitNamingStrategy` 实现类能不能自动识别」。答案是**能，但必须按语义特征识别，
不能见到自定义策略就算**——多数自定义策略只改**表名**（加前缀/去后缀），对嵌入列毫无影响；那种工程若被
当成组件路径档，反而会把列名改错。

判定依据（从 hibernate-core 5.6.4 源码取的 API 事实）：`AttributePath` 上只有
`getParent()` / `getFullPath()` / `getDepth()` 能拿到**父段/整条路径**，内置组件路径策略正是
`getProperty()` + `getParent()` 递归拼接；只取末端属性名（`getProperty()`）的策略不会碰这三个方法。
故新判据＝**文件既参与隐式命名（`implements`/`extends` 命名策略类型）又用到 `AttributePath` 的
父段/全路径**。

**测试**：`HibernateNamingConfigProbeProjectTest` +2——「自定义组件路径策略被语义识别」（递归取
`getParent()` 拼 `_` → 判是）与「只改表名的自定义策略不误判」（只重写 `determinePrimaryTableName`、
不碰 `AttributePath` → 判否）。**5 例全绿**。

**已知边界（如实登记，勿当已解决）**：自定义策略类若在**依赖 JAR** 里（工程无源码）→ 探测不到，仍需
设置项/数据源校正；自定义策略若用**别的连接符或形态**（不是 `_` 连接 + snake 化）→ 插件按内置
component-path 口径渲染仍可能与真库不同（本轮不猜连接符）；判据是文本特征，不解析控制流。

### 7.1c 索引维护（同日）

顺手补齐 `docs/索引/开发计划.md`：原 **11 份漏登记**（含本计划、T-EmbeddedColPrefix、T-HqlBufConcat、
T-SqlTemplateCaret/Ux、T-DialectQuoteWarn、T-PerfSweep25/1719、竞品对比第二十六轮/第五~十三轮、
T-PrewarmWilDeadlock）＋ **1 条失效链接**（`20261015-1830-T-SqlPreviewPrepareFreeze` 已被改名成
`20261015-1842-T-PrewarmWilDeadlock` 且原文件已删，索引未跟上）。补齐后复核：**目录 553 份计划 /
索引 553 条引用 / 漏登记 0 / 失效链接 0 / 重复 0**。

### 7.2 demo 仓 JAR 复现载体（已完成，子智能体交付 `74cc257`）

模型 JAR 模块 `jpa-jar-model-lib`（`@Entity` + 三个 `@Embedded` + `WmsItemKey` 内的静态嵌套类
`LotInfo`，嵌入字段零注解）+ 宿主 Boot 模块 `jpa-jar-model-demo`（端口 8088 / 库 `jpa_demo` /
表 `wms_inventory_jar_demo`），命名约定备了「`application.yml` 里写」与「Java 代码里
`props.put(...)`（`@Profile("code-naming")`）」两份，分别对应 W1 的两条信号路。

**真跑读数**（子智能体实测）：
```
create table wms_inventory_jar_demo (
    id, item_key_lot_info_received_date, qty_base_qty, sku_inventory_status, warehouse_id, ...)
/jar-model/columns → ["id","item_key_lot_info_received_date","qty_base_qty","sku_inventory_status","warehouse_id"]
/jar-model/query（plain / buffer）→ 各 rowCount=1；两种命名配置下 hibernate 属性均生效
```

**勘误（子智能体纠正本计划原文）**：三层嵌入路径的真库列名是 **`item_key_lot_info_received_date`**，
不是本文早先写的 `lot_info_received_date`——组件路径把**整条链** `itemKey.lotInfo.receivedDate` 都并进列名
（与 §8.1 从 hibernate-core 源码得出的「多级取整条链」一致，是本文原稿手误）。

### 7.3 W2 已实施（同日）：表名→实体反查的库兜底

**缺口**：`EntityClassFinder` 的两条查找路（候选名快路径 + `@TableName` 全类扫描慢路径）此前一律用
`GlobalSearchScope.projectScope`，于是**依赖 JAR 里的实体一个都反查不到**（用户场景＝实体类都在 JAR 里）。
影响面＝所有「按表名找实体」的链路（已知消费方 `reverse/SqlToJavaConverter.kt`，SQL→Java 反向转换）。

**改法（性能优先，明确不做越线的事）**：

- 快路径补**库兜底**：项目 scope 0 命中后，再按**候选类名**在库 scope 找一遍，命中后同样按表名精确
  校验；源码命中时行为逐字不变（库兜底只在 0 命中时执行）。代价上界＝4 个候选名 × 一次索引查询，
  且 `EntityResolver.resolve` 自身有「文件 + 类名 + 配置签名」三维缓存，重复解析零成本。
- **不做**「全库逐类扫描」的慢路径库兜底：那要遍历全库类名并逐类读注解/解析（O(库类数) 的 PSI 访问），
  会把「表名无对应实体」这条此前反馈过卡顿的路径成本推高——**性能红线**。本轮先写过「表名 token 预筛
  短名 + 扫全库」的版本，复核后撤掉（弱 token 如 `sys`/`rel` 会匹配大量库类名，成本仍不可控）。
- scope 选型用 `allScope` 而非 `notScope(projectScope)`：后者会把 `isSearchInModuleContent` 一并取反，
  挂在模块上的库根可能被排除（实测库类查不到）；`allScope` 语义明确，重复查项目类的代价可忽略。

**测试**（`EntityClassFinderIntegrationTest`，`HeavyPlatformTestCase`）：

- **库兜底正向**：给测试模块挂上**本机 JDK 当 SDK**，再在环境里探测一个真实库类、按插件自己的口径
  反推表名，断言 `find(表名)` 能把它找回来；并断言**库命中不触发慢路径全量扫描**（性能口径钉成断言）。
- **快路径命中不触发慢扫描**（同口径，任何环境都成立）。
- 既有 9 条（`@TableName` 命中、去首段推断、缓存命中零扫描、缓存清除重扫、多候选顺序、VO/DTO 排除等）不回归。

**读数**：6 个类 **63 例 0 失败 0 错误**（`EntityClassFinderHeavyTest` 17、`SqlToJavaConverterTest` 22、
`PluginCacheManagerCoverageTest` 5、`SettingsRealityGuardTest` 4、`HibernateNamingConfigProbeProjectTest` 5、
`EntityClassFinderIntegrationTest` 10）。

**反向验证**：摘掉库兜底那一档（`find` 里删掉 `findByCandidateNames(..., libraryScope())` 调用）→
`testLibraryEntityFoundByCandidateNameFallback` **恰 1 例红**（`表名 object → []`），其余 9 条保持绿，
`GRADLE_EXIT=1`；还原后复跑 63/0/0。

**踩坑（记录备查）**：`IdeaTestUtil` 与 `com.intellij.JavaTestUtil` 都**不在插件测试 classpath** 上；
`HeavyPlatformTestCase` 默认**不挂任何 SDK**；`ModuleRootManager.setSdk` 会校验注册表，须先
`ProjectJdkTable.addJdk`（并在 tearDown 里 `removeJdk` 防污染）；`LibraryTablesRegistrar.createLibrary`
必须在写动作内；测试 JVM **无 javac**（临时 jar 造不出来）；「库源码根」形态的类不进索引。

