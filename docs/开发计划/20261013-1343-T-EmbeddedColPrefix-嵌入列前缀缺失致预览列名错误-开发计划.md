# T-EmbeddedColPrefix：@Embeddable 嵌入列前缀缺失，预览列名与真库不一致（开发计划）

> 仓库：`mybatis-plus-sql-visualizer`（插件仓，当前分支 `master`）
> 日期：2026-10-13
> 触发来源：用户 2026-10-13 报障 —— HQL `inv.qty.baseQty` 被预览成 `AND inv.base_qty > 0`，
> 真库列名是 `qty_base_qty`（「缺了一部分变量」）；同一句里 `inv.moveTool.palletNo` 也被预览成
> `inv.pallet_no`。用户贴出实体上的三个 `@Embedded` 字段（`moveTool` / `sku` / `qty`）。
> 状态：**根因已定位到具体代码（§2.3）；但「前缀从哪来」是推断，需用户侧材料确认（§七.1）。
> 本轮只出计划、不动代码。**

---

## 一、需求

1. `@Embedded` 展平列的预览列名与**真库实际列名**一致——用户工程里 `qty.baseQty` 对应
   `qty_base_qty`、`moveTool.palletNo` 对应 `move_tool_pallet_no`。
2. 同一份列名同时驱动 WHERE 段属性映射与 SELECT 子句展开，两处必须一起正确（§2.5）。
3. 不改动「嵌入段从 SQL 路径里去掉」这一既有正确行为（§2.2）。
4. 既有「无前缀」工程（JPA 默认命名）行为逐字不变——仓内既有用例与 demo 全按无前缀断言（§2.3）。

## 二、现状与实证

### 2.1 报障形态

用户 HQL（`+` 拼接字面量，走自定义 DAO 通道）：

```java
return commonDao.findByQuery(
    "from WmsInventory inv "
  + "where inv.warehouseId = :warehouseId "
  + "and inv.moveTool.palletNo = :palletNo "
  + "and inv.qty.baseQty > 0 "
  + "order by inv.id",
  new String[]{"warehouseId", "palletNo"}, new Object[]{...});
```

实体（用户贴出的字段）：

```java
@Embedded protected WmsMoveTool moveTool;
@Embedded protected WmsSku sku;
@Embedded protected WmsInventoryQty qty;
```

预览产物（用户截图）：`AND inv.pallet_no = ?` / `AND inv.base_qty > 0`。
用户给出的真库列名：`inv.qty_base_qty`（`moveTool.palletNo` 未逐字给出正确值，按同一规则应为
`inv.move_tool_pallet_no`——见 §七.1）。

### 2.2 代码定位：错在 `columnName`，不在「嵌入段去留」

 `inv.qty.baseQty` 在 `JpaAliasPropertyMapper.mapProperties`（`converter/jpa/JpaAliasTableResolver.kt:249`）
里被切成 qualifier `inv` / 中间段 `qty.` / 属性 `baseQty`：

- `column = table.columnByField("baseQty")` → 展平列（`fieldName=baseQty`、`columnName=base_qty`、
  `embeddedParent=qty`）；
- `isFlattenedEmbeddedPath("qty.", column)`（同文件 `:325`）为真 → 命中 `:267` 分支，输出
  `qualifier + column.columnName` = **`inv.base_qty`**。

即「去掉嵌入段」这一步是**按设计**做的，也是对的：嵌入对象没有自己的表，`inv.qty` 留在 SQL 里
会被当成「库.表」限定（该设计口径写在 `model/TableInfo.kt` 的 `ColumnInfo.embeddedParent` KDoc 里）。
真正错的是 `column.columnName` 这个值本身。

### 2.3 根因：嵌入列名的推导链写死了「无前缀」这一种约定

`columnName` 在 `psi/jpa/JpaMetadataReader.kt` 的 `flattenEmbeddableClass` 里算：
`@AttributeOverride`（`:741-744` 三级键查找）> 内层字段 `@Column(name)` > `NamingStrategy.toSnakeCase(fieldName)`
（`:750`，兜底；`util/NamingStrategy.kt:20` 就是单纯驼峰转下划线）。该函数 KDoc 明写
「嵌套 @Embedded 递归展平（**JPA 无隐式列名前缀**）」。

也就是插件的假设是：**嵌入列的列名 = 内层字段名本身**（`street` 而非 `address_street`）。
仓内既有用例正是这么断言的：

- `EntityResolverEmbeddedTest`：`@Embedded AddressVo address`（`city` 有 `@Column(name="city_name")`、
  `street` 裸字段）断言 `columnByField("street").columnName == "street"`；
- `HqlBarePropertyMappingTest:38`：`ColumnInfo("beDelete", "BE_DELETE", embedded = true, embeddedParent = "updateInfo")`
  ——列名 `BE_DELETE` 同样不带 `updateInfo_` 前缀。

而用户工程的真库列名带前缀 `qty_base_qty`。该前缀只有两个可能来源：

1. **`@AttributeOverride`**（类级或字段级）显式覆写 —— 插件**已支持**（见 §2.4），用户贴出的字段上没有；
2. **Hibernate 侧命名策略**：`ImplicitNamingStrategyComponentPathImpl`
   （`hibernate.implicit_naming_strategy=component-path`）、Hibernate 4 的
   `DefaultComponentSafeNamingStrategy`，或工程里自定义的 Implicit/Physical NamingStrategy
   —— 插件**完全不可见**：实测全仓无任何 `application*.yml/properties` 读取，也无
   `implicit_naming_strategy` / `naming_strategy` 字样（`grep` 为空）。

结论：插件只实现了「无前缀」一种约定，**既无开关也无探测**能表达「组件路径前缀」约定；
实体本身也不携带该信息，所以只要工程用的是前缀约定，嵌入列名就必错，且是静默错。

### 2.4 已排除的两个方向

- **不是 `@AttributeOverride` 读取缺失**：类级（`psi/jpa/JpaEntityResolver.kt:103`）与字段级（`:308`）
  都读，合并顺序字段级优先；键支持「实体根点路径 / 嵌入对象相对点路径 / 裸名」三种形态
  （`JpaMetadataReader.flattenEmbeddableClass` 的 `:741`）。且覆写命中时**不再叠前缀**
  （`fromAttributeOverride` 门控）。若用户实体确有覆写，产物会是覆写名而不是 `base_qty`
  → 需用户确认实体是否含类级覆写（§七.1）。
- **不是「嵌入段没去掉」**：见 §2.2，去掉是对的。

### 2.5 影响面：一处根因、两处产物

`columnName` 同时喂两条链路：

- WHERE / 条件段的属性映射：`JpaAliasPropertyMapper.mapProperties`（上文）；
- SELECT 子句展开：`converter/jpa/SelectClauseExpander.kt:122` 用 `table.columns.map { it.columnName }`
  渲染列清单（`SELECT inv` 展开为整份列清单时，嵌入列同样按 `columnName` 落笔）。

故修在**元数据层（列名推导）**一处即两处同修；Wrapper 渲染、批量模板、补全等共用列清单的链路
一并受益，不需要在 mapper 里再打补丁。

## 三、方案与取舍

目标：把「嵌入列前缀」从写死的「无」变成**可判定的一档**，并且**只作用于 snake 兜底那一档**
——`@AttributeOverride` 命中或内层 `@Column(name)` 命中时仍以显式名为准（与既有语义一致）。
落点与既有 Spring Data `@Embedded(prefix=...)` 同构：`psi/jpa/JpaEntityResolver.kt:308-330` 已有
`sdPrefix`，且正是「非覆写才拼」——新约定复用同一位置，不引入第二套拼接逻辑。

| 候选 | 判断 |
|---|---|
| **A. 读工程 Hibernate 命名配置自动判定**（推荐） | 扫描工程内 `application*.yml/.yaml/.properties`，命中 component-path 类取值（`spring.jpa.properties.hibernate.implicit_naming_strategy` / `hibernate.implicit_naming_strategy` = `component-path` 或 `...ImplicitNamingStrategyComponentPathImpl`；老式 `spring.jpa.hibernate.naming*`）即该工程按前缀约定。用户零操作即可命中；风险是配置形态多，须白名单 + 保守判定（不认识就回落默认） |
| **B. 插件设置项**（兜底 + 可显式纠正） | 设置加「嵌入列命名：无前缀（JPA 默认）/ 组件路径前缀」。A 判不出时（自定义 NamingStrategy 类、非标准配置、多 profile）给用户一个明确开关。默认「无前缀」保住既有行为 |
| C. 以真库列名为准 | 预览已绑定数据源时读 JDBC metadata 的列清单来校正。最权威，但把转换器绑上 DB、离线不可用，与现状（转换器零 DB 依赖）冲突 → 本计划不做，登记为后续候选 |
| D. 不静默猜 | 凡嵌入列名是「snake 兜底」推出来的（既无覆写也无 `@Column`），在预览里挂一条可读提示，让「列名可能不准」可见。可与 A/B 并行，成本低 |

**推荐 A + B（默认仍「无前缀」）**，D 作为可选加固。这样：JPA 默认工程零回归；用户工程若能命中配置
则自动正确；命不中也有显式开关；实在判不出来时至少提示不静默。

## 四、边界矩阵

| 形态 | 期望产物 |
|---|---|
| 无前缀约定（JPA 默认，现状） | `qty.baseQty` → `inv.base_qty`（**逐字不变**） |
| 组件路径前缀约定 | `qty.baseQty` → `inv.qty_base_qty` |
| 内层字段带 `@Column(name="...")` | 用该显式名，两档都不叠前缀 |
| `@AttributeOverride` 命中（类级/字段级，三种键形态） | 用覆写名，两档都不叠前缀 |
| SD `@Embedded(prefix="biz_")` | 沿用既有：非覆写才拼（`biz_street` 不回归）；与 Hibernate 前缀**互斥**，不得叠成 `biz_qty_` |
| 两级嵌链 `a.b.c` | 前缀取整条嵌入字段链还是仅最外层 → **待验证**（§七.3） |
| 无别名裸路径（`qty.baseQty` 无 `inv.`） | 走既有 `:261` 分支产裸列名；前缀规则同受 |
| 读不到配置 / 设置未开 | 维持无前缀（不猜） |

## 五、改动清单（定案后细化）

1. 新增嵌入列前缀来源（建议 `psi/jpa/` 或 `util/`，单一职责）：
   - A：新增工程 Hibernate 命名配置探测（扫 `application*.yml/.yaml/.properties`，白名单取值 →
     `EMBEDDED_PREFIX` / `NONE`），取不到即 `NONE`；
   - B：`PluginSettings` 加一项（枚举：`NONE` / `COMPONENT_PATH`），与 A 的合成顺序建议
     「显式设置 > 配置探测 > 默认 NONE」。
2. `JpaMetadataReader.flattenEmbeddableClass`（`:710-758`）的兜底档接前缀：
   `override ?: innerColumn ?: prefix(嵌入字段链) + snakeCase(fieldName)`；KDoc「JPA 无隐式列名前缀」
   更正为「可配置/可探测的约定」。
3. `psi/jpa/JpaEntityResolver.kt`（`:308-330`）与 `psi/EntityResolver.kt`（`:232` 附近）的
   `sdPrefix` 落点复用为新前缀来源之一（保持「非覆写才拼」与互斥语义）。
4. 预览提示（可选 D）：嵌入列名来自兜底时挂说明。
5. 测试：见 §六。
6. `CHANGELOG.md`：`[Unreleased]`（`Fixed` 或 `Added`，取决于是否带设置项）。

## 六、验证方案

### 6.1 定向测试

- `psi/EntityResolverEmbeddedTest`：加「组件路径前缀」档用例（`@Embedded AddressVo address` →
  `address_street`；有覆盖写时不叠），并确认无前缀档既有断言逐字不变。
- `psi/jpa/JpaEntityResolverMappingDeepTest`、`converter/jpa/HqlBarePropertyMappingTest`、
  `HqlAliasColumnMappingTest`、`ui/SqlPreviewServiceJpaEmbeddedNestedPathTest`：回归。
- 复刻用户形态的端到端用例：HQL `from WmsInventory inv where inv.qty.baseQty > 0`
  → 产物含 `inv.qty_base_qty`、不含 `inv.base_qty`；同句加 `inv.moveTool.palletNo`
  → `inv.move_tool_pallet_no`。

### 6.2 反向验证

把前缀档短路（前缀恒为空）→ 新增的 component-path 用例必须**变红**，贴两次读数；
同时确认无前缀档用例仍绿（证明是「加了一档」而不是「换了一档」）。

### 6.3 不做

真机 / runIde / 全量 / 构建校验（按验证三档分层，留待收口）；本轮只出计划、**不改代码**。

## 七、待验证前提（推断的机制，实施前先证伪）

1. **用户工程的前缀到底从哪来**——本计划的最大不确定点。需要用户提供其一：
   ①`WmsInventory` 实体**完整**源码（含类级注解，确认有没有 `@AttributeOverrides`）；
   ②`application*.yml/properties` 里的 Hibernate/JPA 命名配置；
   ③该表真实列清单（`desc wms_inventory` / `SHOW COLUMNS`）。
   本计划的 mechanism（Hibernate component-path 约定）是**推断**：插件只看到实体，推不出命名策略。
2. **组件路径前缀的确切拼接形态**：本计划按 `snake(嵌入字段) + "_" + snake(内层字段)`
   （`qty` + `_` + `base_qty` = `qty_base_qty`）假设；也需排除按**类名**或按**整条链**拼接的变体。
   用户只给了 `qty_base_qty` 一例，`moveTool` 那处未逐字给出。
3. **两级以上嵌入链的前缀取法**：`a.b.c` 该产 `a_b_c` 还是 `b_c`——需查 Hibernate 语义或实测。
4. **`@AttributeOverride` 与 Hibernate 前缀的叠加语义**：覆写名是否已含前缀、要不要再叠。
   本计划取「覆写即最终名，不叠」；若用户工程按「覆写名 + 前缀」书写，则规则要改。
5. **老式 `DefaultComponentSafeNamingStrategy` 与自定义策略类的可探测性**：配置白名单能否覆盖；
   覆盖不到就只能靠设置项 B。
6. **配置文件的定位口径**：多模块 `src/main/resources`、`.yml` vs `.yaml` vs `.properties`、
   profile 变体（`application-dev.yml`）——需定一个最低可用子集，避免扫全项目拖慢。

## 八、实施记录（2026-10-13）

采用推荐方案 **A + B**（默认 AUTO → 无配置回落无前缀，既有工程零回归）。

### 8.1 机制核对（读 hibernate-core 源码，非猜）

`~/.m2/repository/org/hibernate/hibernate-core/5.6.4.Final/hibernate-core-5.6.4.Final-sources.jar`：

- `ImplicitNamingStrategyComponentPathImpl.transformAttributePath`：递归把父路径与当前属性用 `_`
  连接，用的是**原始属性名**（`qty` + `_` + `baseQty`），下划线化交给 PhysicalNamingStrategy；
  多级嵌入逐段累积（`a` + `b` + `c` → `a_b_c`）。KDoc 自述是 Hibernate 4
  `DefaultComponentSafeNamingStrategy` 的移植。
- `ImplicitNamingStrategyJpaCompliantImpl.transformAttributePath`：只取末端属性（`getProperty()`）。
- `ImplicitNamingStrategyLegacyJpaImpl` / `LegacyHbmImpl`：只改写 join table / join column 命名，
  **不覆写** `transformAttributePath` ⇒ 内置策略里**只有 component-path 一档**会给基本列加嵌入前缀。

→ 探测白名单据此收敛为「类名 `ImplicitNamingStrategyComponentPathImpl` /
`DefaultComponentSafeNamingStrategy` + 关键字 `component-path`」；§七.2/§七.3 两个待验证前提由源码坐实
（前缀 = 驼峰段以 `_` 连接后整体 snake 化；多级取**整条链**）。

### 8.2 改动文件

- 新增 `psi/jpa/EmbeddedColumnNaming.kt`：档位枚举（`NONE` / `COMPONENT_PATH`）+
  `EmbeddedColumnNamingResolver`（**显式设置 > 工程配置探测 > 默认 NONE**）。
- 新增 `psi/jpa/HibernateNamingConfigProbe.kt`：扫 `application*.yml/.yaml/.properties`，命中组件路径
  命名策略写法即判该工程用前缀约定；`indicatesComponentPath(text)` 为纯函数（先剔整行 `#`/`!` 注释
  再匹配，可单测），工程扫描结果按 `PsiModificationTracker.MODIFICATION_COUNT` 缓存。
- `psi/jpa/JpaMetadataReader.kt`：`flattenEmbeddedColumns` / `flattenEmbeddableClass` 增 `naming`
  与 `snakePathPrefix` 参数；兜底列名改走新私有 `embeddedFallbackColumnName`。
  **只影响兜底档**——`@AttributeOverride` / 内层 `@Column(name)` 命中时两档都仍以显式名为准。
- `psi/jpa/JpaEntityResolver.kt`：JPA 轨展平时按 `EmbeddedColumnNamingResolver.resolve(field.project)`
  解析并下传。（MP 轨 `EntityResolver` 不传 → 保持 NONE，行为不变。）
- `config/PluginSettings.kt`：`EMBEDDED_NAMING_AUTO/NONE/COMPONENT_PATH` 常量 + `State.embeddedColumnNaming`
  （默认 AUTO）。
- `config/PluginSettingsPanel.kt` / `PluginSettingsPanelSections.kt` / `PluginSettingsFolderConfigurable.kt`：
  下拉控件 + 选项 + `isModified` / `apply` / `reset` 三处绑定 + 设置搜索索引（照 `reverseWrapperFormat` 既有形态）。
- 双语 bundle：`settings.embeddedColumnNaming{,.tip,.auto,.none,.componentPath}`（中英同键）。
- 计划本文档、`CHANGELOG.md`。

### 8.3 定向读数

16 个类（新增 2 + 嵌入/映射/转换回归 9 + 设置守卫 5）：

```
./gradlew test --rerun-tasks --tests "…psi.jpa.JpaEmbeddedColumnNamingTest" --tests "…psi.jpa.HibernateNamingConfigProbeTest"
  --tests "…psi.EntityResolverEmbeddedTest" --tests "…psi.jpa.JpaEntityResolverMappingDeepTest"
  --tests "…converter.jpa.HqlBarePropertyMappingTest" --tests "…converter.jpa.HqlAliasColumnMappingTest"
  --tests "…converter.jpa.DerivedQueryConverterTest" --tests "…converter.jpa.JpaUpdateConverterTest"
  --tests "…ui.SqlPreviewServiceJpaEmbeddedNestedPathTest" --tests "…converter.jpa.DemoJpaSqlPipelineTest"
  --tests "…config.SettingsRealityGuardTest" --tests "…i18n.BundleParityGuardTest" --tests "…config.SettingsTruthTest"
  --tests "…config.PluginSettingsPanelBindingTest" --tests "…config.SettingsIOTest" --tests "…config.PluginSettingsPersistenceTest"
```

**219 例 0 失败 0 错误**（含新增 `JpaEmbeddedColumnNamingTest` 8 例、`HibernateNamingConfigProbeTest` 7 例），
BUILD SUCCESSFUL（GRADLE_EXIT=0）。设置守卫三家族均绿（`SettingsRealityGuardTest` 断言一「写而不读」/
断言二「摆而不绑」/断言四「幽灵键」、`BundleParityGuardTest` 中英同键、`SettingsTruthTest`）。

> 坑（已按既有经验处置）：给带默认值的 `Data class State` 加字段后，首次运行出现 3 个类成片
> `NoSuchMethodError: PluginSettings$State.copy$default(...)` —— 旧测试类未重编译；`--rerun-tasks`
> 强制重编译后消失（新增的两个类因是新编译，自始为绿）。

### 8.4 反向验证

把 `embeddedFallbackColumnName` 的 `COMPONENT_PATH` 档短路成 `NONE`（前缀恒空）→
`JpaEmbeddedColumnNamingTest` **3 例变红**（组件路径前缀档 / 两级嵌入链 / 端到端产物），
而**无前缀档 2 例、自动档 1 例、覆写优先 1 例、内层 `@Column` 优先 1 例保持绿**，GRADLE_EXIT=1。
即证「加了一档」而非「换了一档」，既有工程零回归。还原后复跑上表 219/0/0。

### 8.5 未做 / 边界

- 真机 / runIde / 全量测试 / 构建校验（按验证三档分层，留待收口）。
- **用户工程 AUTO 能否命中仍未验**（§七.1）：取决于其组件路径策略是否写在 `application*.yml/properties`
  里。写在自定义 NamingStrategy 类内、或表列名由历史 DDL 定型而实体另有 `@Column`，AUTO 都判不出——
  此时用户把设置切到「组件路径前缀」即可（B 的存在意义）。
- MP 轨（`EntityResolver`）刻意保持 NONE：那是插件对 MP 实体的便利展平，不属 Hibernate 命名语义。
- 方案 C（按真库列清单校正）未做，仍在候选。

### 8.6 追加（同日）：用户补充「没有用 @AttributeOverride」→ 补一条不依赖配置的兜底

用户明确没有用 `@AttributeOverride`，即前缀确实来自 Hibernate 命名策略（A 未必能探测到）。据此把原
「§八 未做」里的方案 C 做了**最小版**——扩展既有的「按数据源校正列名」（`psi/ColumnNameCalibrator.kt`）：

- 发现：该机制的 KDoc 明把「工程自定义命名策略」列为适用场景，但对**嵌入列失效**——它靠
  `normalize`（小写 + 去 `_`/`-`）比对，而 `base_qty` 与 `qty_base_qty` 归一化后是 `baseqty` 与
  `qtybaseqty`，差的是一整段前缀，三步算法必然 0 候选。
- 修法：列名校正循环里，对 `embedded == true` 的列在既有三步 0 命中后增补一档候选——用
  `ColumnInfo.embeddedPath`（实体根起的嵌入段驼峰路径，`qty.baseQty` → `qtyBaseQty`）的 snake 形态
  （`qty_base_qty`）去真实列集合里**唯一**命中（精确命中，或归一化键唯一）。沿用「宁缺不错」：
  非嵌入列不适用、0 候选/≥2 候选不改写、**当前列名已是库中真名（含精确命中）不得被路径候选抢走**、
  命中值等于当前值也返回 null（保住「无改动返回入参实例」不变量）。
- 测试：`ColumnNameCalibratorTest` +5（缺前缀校正 / 大小写按库回写 / 候选不存在或归一化歧义不改写 /
  当前列名已在库中不介入 / 非嵌入列不适用）。首轮 **2 例红**，其一正是「库里真有 `base_qty`」被路径候选
  抢走——漏了「第 1 步无需改写」那道闸；补闸后全绿。该红线由 `assertSame`（实例恒等）守住，勿退化。
- 意义：预览一旦绑定数据源（且 schema 缓存预热完成），这种「命名策略未知 + 实体无注解」的场景
  **零设置自愈**；未绑数据源时仍由 A（配置探测）/ B（设置项）兜。
- 未做的仍是方案 C 的完整版（按真库列清单校正**一切**猜测列名，含语义改名）——那超出本轮范围。


