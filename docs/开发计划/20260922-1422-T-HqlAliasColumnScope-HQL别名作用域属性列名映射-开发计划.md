# T-HqlAliasColumnScope HQL 别名作用域属性→列名映射（JOIN 副表属性原样透传）— 开发计划

> 来源：2026-09-22 用户报障（HQL 场景，附预览面板截图）——预览产物里
> `JOIN WMS_PICK_TICKET_DETAIL detail ON detail.pickTicketId = pickTicket.id` 与
> `JOIN WMS_ITEM item ON item.id = detail.itemId`，其中 `pickTicketId` / `itemId`
> 未转成列名（应为 `pick_ticket_id` / `item_id`），用户原话「应该是自动转驼峰的，但是没有转」。
> 定位依据：`HqlConverter` 属性映射实现逐行实读 + `JpaFromEntityMapper` / `JpaJoinOnResolver`
> 两条候选来源逐一排除 + 既有测试夹具反证（缺陷在测试语料里可见却无断言）。
> 相邻事项：与同轮报障 `T-DbColumnCalibration`（命名不规范 + 数据源校正）**根因、文件、验证完全独立**，
> 可并行；本项只动 `converter/jpa/` 的属性映射与别名表，不动实体解析、不动数据源。
> 编号：占用 **O-57**。

## 一、现象

用户提供的预览面板截图（HQL 场景，`jepu_wms` 数据源）：

```sql
SELECT
    DISTINCT item.code
FROM WMS_PICK_TICKET pickTicket
JOIN WMS_PICK_TICKET_DETAIL detail ON detail.pickTicketId = pickTicket.id
JOIN WMS_ITEM item ON item.id = detail.itemId
WHERE pickTicket.warehouse_id = ?
    AND pickTicket.status = 'EN'
    AND pickTicket.updateInfo.created_time >= ?
    AND pickTicket.updateInfo.created_time < ?
ORDER BY item.code
```

同一份 SQL 里出现了三种不同待遇，这是本缺陷最重要的线索：

| 片段 | 归属 | 转换结果 | 判定 |
|---|---|---|---|
| `pickTicket.warehouse_id` | 根实体属性 `warehouseId` | 已转列名 | ✅ |
| `pickTicket.status` | 根实体属性（同名） | 无需转换 | ✅ |
| `pickTicket.updateInfo.createdTime` | 根实体嵌入对象内字段 `createdTime` | **嵌入段已按 `@Embedded` 展平去掉**（§六 D2 已实施） | ✅ |
| `detail.pickTicketId` | **副表** `WmsPickTicketDetail` 的属性 | **原样透传** | ❌ 本缺陷 |
| `detail.itemId` | **副表** `WmsPickTicketDetail` 的属性 | **原样透传** | ❌ 本缺陷 |

规律很清楚：**根实体的属性会被映射，副表别名的属性不会被映射**。

## 二、根因

### 1. 属性→列名映射只遍历根实体的列清单，且完全不看别名

`src/main/kotlin/cn/song/mybatisplus/visualizer/converter/jpa/HqlConverter.kt:371-379`：

```kotlin
private fun mapPropertyToColumn(hql: String, table: TableInfo): String {
    var result = hql
    for (column in table.columns) {                       // ← table = 根实体
        if (column.fieldName == column.columnName) continue
        val propertyPattern = Regex("\\.\\b${Regex.escape(column.fieldName)}\\b")   // ← 无别名限定
        result = propertyPattern.replace(result, ".${column.columnName}")
    }
    return result
}
```

两个结构性事实：

- `table` 是**根实体**的 `TableInfo`（`HqlConverter.convert` 的 `tableInfo` 入参，调用方
  `ui/SqlPreviewService.kt:1813 resolveHqlTable` 按 FROM 子句的实体解析）。副表实体的列清单
  **从未进入这个循环**。
- 替换正则是 `\.<fieldName>`，**不含别名**。也就是说：即便根实体恰好也有同名字段，替换会打到
  全 SQL 每一处 `.同名字段`，包括别的别名下的那一处（跨别名误伤，见 §四 T-02）。

### 2. 唯一的非根实体处理只取表名，列清单被就地丢弃

`src/main/kotlin/cn/song/mybatisplus/visualizer/converter/jpa/JpaFromEntityMapper.kt:52-64`
（J13「子查询 FROM/JOIN 中其他实体名 → 表名」）：

```kotlin
clazz?.let {
    JpaEntityResolver().resolve(it).tableName          // ← 只留 tableName
        .takeIf { name -> name.isNotBlank() && name != "unknown_table" }
}
```

`JpaEntityResolver().resolve(clazz)` 产出的 `TableInfo` **本身就带着完整 `columns`**，
这里只取了 `.tableName` 就把对象丢了——「副表列清单」其实算过一次，只是没有传下去。
这是本任务最省力的落点。

### 3. JOIN ON 真实化解析器对「显式实体 JOIN」形态刻意不改写

`JpaJoinOnResolver` / `JpaJoinRewriter` 处理的是**关联路径 JOIN**（`join alias.relation`），
对 `JOIN <实体名> alias ON ...` 显式形态按设计原样保留
（`src/test/.../JpaJoinOnResolverTest.kt:226-233` 有断言：
`assertTrue("显式表名 JOIN 应原样保留", sql.contains("left join t_location loc on loc.zone_id = u.id"))`）。
所以 ON 里的属性名既没被 ON 生成逻辑碰过，也没被属性映射逻辑碰过——**落在两条链的缝里**。

### 4. 缺陷在既有测试语料里可见，但没有任何断言覆盖

`src/test/kotlin/cn/song/mybatisplus/visualizer/converter/jpa/HqlConverterTest.kt:169-185`：

```kotlin
val hql = "select count(1) from WmsZone z " +
    "left join WmsLocation loc on loc.zoneId = z.id " +        // ← 副表属性，就是本缺陷形态
    "where loc.id in (:locIdList) and z.beAutoZone = true"
...
assertTrue("SQL 含 IN (?)", result.sql.contains("in (?)"))     // ← 只断言了 IN
```

夹具里 `loc.zoneId` 与根实体属性 `z.beAutoZone` 并存，是天然的对照样本，但断言只落在 `IN (?)` 上
——**绿灯不构成「副表属性已正确映射」的证据**。这条测试是本任务的最小复现素材。

## 三、影响面

- **产出的 SQL 直接不可执行**：`detail.pickTicketId` 在库里不存在（真列是 `pick_ticket_id`），
  复制出去执行即报 `Unknown column`。用户报障的正是这个「预览 SQL 直接是错误的」。
- **命中面 = 所有显式实体 JOIN 的 HQL/JPQL**：凡是在 ON 条件或 WHERE 里引用了副表别名的属性，
  该属性就不会被映射。多表 JOIN 越多，错处越多。
- **不受影响的**：根实体属性映射（截图里 `warehouseId` 正常）、参数替换、实体名→表名替换、
  SELECT 展开、纯 SQL（无实体元数据）链路。
- **与命名是否规范无关**：即便实体字段命名完全标准（`pickTicketId` → `pick_ticket_id` 是
  snake 策略的正确答案），副表属性一样不会被映射。所以本缺陷**不能靠数据源校正绕过**，
  必须单独修（这也是它与 `T-DbColumnCalibration` 必须分成两个任务的理由）。

## 四、修法

### T-01（P0，根因）建立「别名 → TableInfo」映射表，属性映射改为按别名分派

- 位置：`HqlConverter.kt:106-116`（步骤 7/8/9 编排）、`:371-379`（`mapPropertyToColumn`）、
  `JpaFromEntityMapper.kt:52-64`（实体名→表名扫描，就地扩成「实体名→TableInfo」）。
- 做法：
  1. 把 `JpaFromEntityMapper` 的扫描结果从 `Map<实体名, String 表名>` 扩为
     `Map<实体名, TableInfo>`（`JpaEntityResolver().resolve(clazz)` 的产物整份留下）；
  2. 新增「别名收集」步骤：解析 `FROM <标识符> [AS] <别名>` 与 `JOIN <标识符> [AS] <别名>`
     （含用户显式 ON 的形态），把别名绑到对应实体的 `TableInfo`；无别名的表用表名自身作键；
  3. `mapPropertyToColumn` 改为遍历「别名 → 列清单」，替换正则加别名前缀；
     根实体保留既有「无别名裸属性」（如 `WHERE status = ?`）的隐式作用域兜底。
- 收益：一次改动同时覆盖根实体与全部副表别名；`JpaEntityResolver` 的解析结果本来就在算，无新增解析成本。

### T-02（P0，必须与 T-01 同批）替换正则收紧为别名限定，消除跨别名误伤

- 现状：`\.<fieldName>` 全局替换（`HqlConverter.kt:375`）。当两张表各有一个同名属性但映射到不同列时，
  现状会把**另一张表的**引用一起改掉；引入别名表后如果不收紧，误伤面只会放大。
- 做法：`\b<别名>\.<fieldName>\b`（别名经 `Regex.escape`）；无别名裸属性单独一条低优先规则。
- 反向验证（必做）：造一张两表同名不同列（如 `a.createdTime → created_time`、
  `b.createdTime → gmt_create`）的夹具，断言各自映射正确；把正则改回无别名形态 → 必红。

### T-03（P1）别名作用域的边界与幂等

- 需处理的形态：`FROM T t` / `FROM T AS t` / `JOIN T t` / `JOIN T AS t` / 无别名；
  别名与关键字冲突（`on`/`where`/`join`…，可复用 `JpaJoinRewriter.normalizeAlias` 的
  关键字表 `JpaJoinRewriter.kt:140-148`）；
  同一实体多次 JOIN（自连接）——按别名分别绑定，不合并。
- 幂等要求：替换产物是列名（小写 snake），不得再次命中别名规则；重复调用结果一致。

### T-04（P1）确认 SELECT 展开是否需要副表列清单

- `SelectClauseExpander.expand(sql, table)`（`HqlConverter.kt:124`）当前只吃根实体 `table`。
  `SELECT detail`（取整个副表实体）这类形态是否也在用户群里出现、是否也需展开为副表列清单，
  实施时先实测再决定是否纳入（**不确定就不做**，只登记为已知边界）。

### T-05（P1）测试与反向验证

新增 `HqlAliasColumnMappingTest`：

1. **用户截图同形复现**（最小复现，改前必红）：两段式实体 JOIN + ON 引用副表属性 →
   断言 `detail.pick_ticket_id` / `detail.item_id`；
2. **既有语料补断言**：给 `HqlConverterTest.customDao_joinWithNamedParam` 补
   `assertTrue(result.sql.contains("loc.zone_id"))`（改前必红）；
3. **跨别名同名字段**（T-02 的判据）：两表同名不同列，各自正确；
4. **隐式作用域不回归**：裸属性（`WHERE status = ?`）仍映射到根实体列；
5. **自连接**：同一实体两个别名，各自映射；
6. **无 Project / 实体不可解析**：保持现状（原样透传，不猜）。

反向验证（必做，读数贴 §实施记录）：把 T-01 改回「只遍历根实体列清单」→ 用例 1/2/3 必红；
把 T-02 的正则改回无别名形态 → 用例 3 必红。

### T-06（P2）ON 未映射时给出可诊断信号

现状是静默透传，用户只能靠肉眼看出来。做法：当 ON 条件里出现「无法归属到任何已知别名」
的 `别名.属性` 形态时，附一条一次性注释（沿用既有降级注释风格，如
`/* 以下属性未映射到列名，请核对实体注解 */`）。**不改变 SQL 语义**，只提升可发现性。

## 五、待验证前提

| # | 前提 | 验证方式 |
|---|---|---|
| P-1 | **用户 HQL 原文形态未知**（截图只有预览产物）。已排除三条插件侧来源：`JpaJoinOnResolver` 三个 ON 生成分支的文本方向与截图都不符（`$joinAlias.$ref = $rootAlias.$fk` / `$rootAlias.$ref = $joinAlias.$fk`）；全仓 grep 无任何「非 snake 的 `Id` 后缀拼接」；`mapAssociationPath` 的三段式路径需要根表命中关联，`pickTicket` 不是根表关联。因此 `detail.pickTicketId` 最可能是**用户手写的 ON 条件**（JPA 2.1 实体 JOIN `JOIN <Entity> alias ON ...`），实体 `WmsPickTicketDetail` 上有普通字段 `pickTicketId`/`itemId`。 | **实施第一步必须先复现**：按截图 SQL 反推一份 HQL 夹具，在改代码前跑出与截图一致的错误产物（红）。若复现不出，说明来源判断有误，须回头重新定位（此时本任务的所有 T-01~T-03 仍成立，因为「副表属性不映射」是代码可证的独立事实，见 §二.1） |
| P-2 | 备选来源：用户实体 `WmsPickTicketDetail` 的 `pickTicket` 是 `@ManyToOne @JoinColumn(name = "pickTicketId")` 关联字段，ON 由插件生成 | 若 P-1 的夹具复现不出，按此假设再造一份夹具（含 `@ManyToOne` 的 PsiClass 夹具，照 `JpaJoinOnResolverTest` 的 LightPlatform 形态）。注意此假设下 ON 的操作数顺序与截图相反，需要先解释顺序差异 |
| P-3 | 用户「自动转驼峰」措辞的准确含义 | 判断为「属性名→列名」（camel→snake 方向），与截图里 `warehouseId→warehouse_id` 的既有行为一致。实施前向用户确认一次，避免修错方向 |
| P-4 | 副表实体的 `TableInfo` 在 HQL 预览链路上确实可取（`JpaEntityResolver` 能解析到 `WmsPickTicketDetail`） | 代码可判：`JpaFromEntityMapper.kt:59-61` 已经在用同一路径取 `tableName`，说明类能解析到；实施时用夹具实测列清单非空即可 |
| P-5 | 别名收集是否会与 `JpaJoinRewriter` 的 JOIN 改写冲突（改写后别名可能新增/变化） | 步骤顺序上属性映射（第 9 步）在 JOIN 改写（第 5 步）之后，别名收集必须在改写**之后**做。实施时用「关联路径 JOIN + 显式实体 JOIN 混用」的夹具验证 |

## 六、顺带发现（D2 已按用户后续指示实施，见本节末尾）

### D2：嵌入对象路径未展平，产出三段式无效 SQL

截图里 `pickTicket.updateInfo.created_time` 是**无效 SQL**：SQL 语义下
`pickTicket.updateInfo` 被解析为 `库.表`，`created_time` 才是列。正确产物应为
`pickTicket.created_time`（`@Embedded` 是 JPA 的字段展平，不产生中间列）。

- 依据：`JpaEntityResolver.kt:278-319 flattenEmbeddedColumns` 产出的 `ColumnInfo` 是**展平后的
  内层字段**（`fieldName = spec.fieldName`，即 `createdTime`），列清单里**没有** `updateInfo` 这一项；
  于是 `mapPropertyToColumn` 只能改到内层的 `createdTime`，外层的 `updateInfo` 无人处理。
- 与 `T-HqlAliasColumnScope` 同族（都是「HQL 路径段 → 列名」的映射不完整），但**落点不同**
  （本项在嵌入展平与路径消费侧），是否合并实施请用户定案。
- 若纳入，需同时确认：`@AttributeOverride` 覆写后的列名、`@EmbeddedId` 复合主键路径、
  以及「用户确实写了三段式路径」这一前提（同 P-1，需先复现）。


### D2 后续实施（2026-09-22，用户补充预览代码后指示「再次分析后调整」）

**已实施**。用户 2026-09-22 先要求「先待定，等我补充预览 sql 时的代码再次分析后调整」，随后补齐了预览侧
Java 代码（见 §十一.1），挂起条件解除，故本轮一并做掉。

- **机制**：`ColumnInfo` 新增 `embeddedParent: String?`（展平列的**嵌入对象字段名**，按该文件既有约定
  「尾部追加带默认值」追加）；`JpaEntityResolver.flattenEmbeddedColumns` 展平时写入该字段；
  `JpaAliasPropertyMapper` 据**列自身的 `embeddedParent`** 判断是否去掉中间段——
  `pickTicket.updateInfo.createdTime` → `pickTicket.created_time`（嵌入段 `updateInfo.` 去掉）。
- **为什么用列元数据而不是猜**：若按「中间段不是本表字段就丢掉」来判，会误删别的合法中间段。
  判据取自列自己记录的嵌入来源，非嵌入列（`embeddedParent == null`）恒不丢段。
- **判据收紧**：只比中间段的**首段**——嵌套 `@Embedded` 展平后列名已按 `@AttributeOverride` 链算准，
  整条中间段都该丢；而 `embeddedParent` 记录的是最外层那个字段。
- **测试**：整串黄金比对的期望产物改为 `pickTicket.created_time`；新增
  `test嵌入路径展平_嵌入段去掉而列名用覆写值`（含 `@Column(name="created_time")` 覆写与未覆写 snake 兜底两形态）
  与 `test非嵌入中间段_保留不删`（「不靠猜测丢中间段」的反向判据）。
- **反向验证**：把 `isFlattenedEmbeddedPath` 分支恒置 false（即恢复「保留嵌入段」）→
  展平用例与整串黄金比对必红；读数见 §十二。
- **原「必须保持现状」的硬约束（§11.2）随之作废**——那是用户挂起期间的临时约束，条件已解除。

## 七、验收

**自动化**：

1. `HqlAliasColumnMappingTest` 六例全绿；反向验证两轮（T-01 回退必红、T-02 回退必红），
   两次读数贴 §实施记录；
2. 既有 `HqlConverter*Test` / `JpqlConverterTest` / `JpaJoinOnResolverTest` 全绿
   （重点看 `JpaJoinOnResolverTest:226-233` 的「显式表名 JOIN 原样保留」断言——本任务不得破坏它：
   该断言保护的是「不做无据改写」，而属性→列名映射是有据的，两者不冲突，但需实测确认）；
3. 全量 `test`（基线 491 类 / ~5600 用例）与 `testShards` 三片。

**真机（runIde 沙盒 + 用户工程或 demo）**：

4. 用 P-1 的复现 HQL 打开预览 → 副表属性显示为列名；复制 SQL 到数据源执行 → 不再报 `Unknown column`；
5. 根实体属性映射、参数回填、JOIN 别名均不回归。

## 八、不做的事

- 不引入 JDBC / 数据源依赖（本任务纯 PSI 元数据，与 `T-DbColumnCalibration` 分界）。
- 不猜列名：副表实体解析不到时维持原样透传，不做命名策略盲猜（沿用
  `JpaFromEntityMapper` 的既有原则「猜错表名比保留实体名更误导」）。
- 不改 `JpaJoinOnResolver` 的三级 ON 解析策略与降级文本（存量测试按此断言）。
- 不改 SELECT 展开器的既有短路边界（T-04 只做「是否纳入」的判定，不顺手扩范围）。

## 九、涉及文件清单

| 文件 | 改动 |
|---|---|
| `src/main/kotlin/.../converter/jpa/HqlConverter.kt` | T-01 别名表接入、T-02 正则收紧、T-03 边界、T-06 诊断注释 |
| `src/main/kotlin/.../converter/jpa/JpqlConverter.kt` | T-01/T-02 同名 `mapPropertyToColumn`（`:302`）同款处理——两条链路共用行为，不可只改一条 |
| `src/main/kotlin/.../converter/jpa/JpaFromEntityMapper.kt` | T-01 扫描结果由「表名」扩为「TableInfo」 |
| `src/test/kotlin/.../converter/jpa/HqlAliasColumnMappingTest.kt`（新增） | T-05 六例 + 反向验证 |
| `src/test/kotlin/.../converter/jpa/HqlConverterTest.kt` | T-05 用例 2 补断言 |
| `CHANGELOG.md` / `docs/变更记录.md` | 收口时三处同步 |

## 十一、用户补充（2026-09-22 14:37）——P-1 已证实，@Embedded 项挂起

### 11.1 HQL 原文（用户提供，Java 侧拼接的预览代码）

```java
String hql = "select distinct item.code"
        + " from WmsPickTicket pickTicket"
        + " join WmsPickTicketDetail detail on detail.pickTicketId = pickTicket.id"
        + " join WmsItem item on item.id = detail.itemId"
        + " where pickTicket.warehouseId = :warehouseId"
        + " and pickTicket.status = 'EN'"
        + " and pickTicket.updateInfo.createdTime >= :startTime"
        + " and pickTicket.updateInfo.createdTime < :endTime"
        + " order by item.code";
List<String> itemCodes = commonDao.findByQuery(hql,
        new String[]{"warehouseId", "startTime", "endTime"},
        new Object[]{warehouseId, startTime, endTime});
```

**§五 P-1 判定：证实。** 形态就是「显式实体 JOIN + 用户手写 ON」，`WmsPickTicketDetail` 上有普通字段
`pickTicketId` / `itemId`（非 `@ManyToOne` 关联字段）。三条被排除的插件侧来源结论不变。
因此实施不再需要「先猜再复现」——**直接用这段 HQL 造夹具**，改前应产出与截图逐字一致的错误产物。

另附一条与映射无关但影响复现的旁证：该查询只有一列，Hibernate 返回标量本身而非 `Object[]`
（用户代码注释已写明），所以复现时只看预览产物、不要按 `Object[]` 反推。

### 11.2 边界：`pickTicket.updateInfo.createdTime`（**该约束已被 2026-09-22 后续指示取代**，见 §六 D2 后续实施）

用户 2026-09-22 明确：`@Embedded` 展平问题**先待定**，等补充更多预览代码后再分析调整。

对 T-02 正则收紧的硬约束：现状 `\.<fieldName>` 会把 `pickTicket.updateInfo.createdTime`
改成 `pickTicket.updateInfo.created_time`（外层 `updateInfo` 保留，本身仍是无效 SQL，见 §六 D2）。
收紧为别名限定后，**必须仍然命中这一形态**，即规则要允许别名与属性名之间存在中间段：

```
\b<别名>\.((?:[a-zA-Z_][a-zA-Z0-9_]*\.)*)<fieldName>\b   →   <别名>.$1<columnName>
```

收紧前/后该形态的产物必须逐字相同（作为 T-05 的一条显式回归断言）。
**不得**顺手把 `updateInfo.` 去掉——那是 §六 D2 的范围，本轮不动。

### 11.3 未映射别名的兜底口径（防回归）

若某个别名在 PSI 侧解析不出实体（无索引 / 类找不到 / 局部类），**该别名回落现状行为**
（即仍按根实体列清单替换），而不是「一律不替换」。这样本任务对既有产物是严格收窄+新增，
不产生「原本会被替换、现在不替换」的回归面。

### 11.4 §七 验收的补充

- 复现夹具直接用 11.1 的 HQL，断言 `detail.pick_ticket_id` / `detail.item_id`；
- 同时断言 `pickTicket.updateInfo.created_time` 保持原样（11.2 的回归线）；
- `pickTicket.warehouse_id` / `pickTicket.status` / `item.code` 不变。

## 实施记录

### 交付方式

sam 调度：单实施代理五轮迭代（初版 + 复核后四轮），主调度逐轮审 diff、两轮独立只读复核（写验分离），
全量门禁由主调度串行跑。分支 `t-hql-colmap`，工作树 `mpsv-hql-colmap`，基 master `c68dbc8`。

### 任务落地（对照 §四）

| 任务 | 落地形态 |
|---|---|
| T-01 | 新增 `converter/jpa/JpaAliasTableResolver.kt`（`JpaAliasScope` + `JpaAliasTableResolver` + `JpaAliasPropertyMapper`）。扫描时点=**JOIN 改写之后、实体名→表名替换之前**（前者保证改写新造的副表别名可见，后者保证 FROM/JOIN 后的实体标识符还在、PSI 短名检索能命中）。根实体直接绑传入的 `table`（免二次 PSI），其余实体按短名检索后 `JpaEntityResolver().resolve`，同实体名 memo 一次。解析失败静默跳过、不猜列名 |
| T-02 | 替换核心 `JpaAliasPropertyMapper.mapProperties`：按限定符分派到 `scope.byAlias[限定符] ?: rootTable`，**未解析别名回落根实体列清单**（= 改前行为，故对既有产物是严格收窄 + 新增）。`HqlConverter` 与 `JpqlConverter` 共用这一份实现 |
| T-03 | 覆盖 `FROM T t` / `FROM T AS t` / `JOIN T t` / `JOIN T AS t` / 无别名 / 自连接（同实体两别名各自绑定，`putIfAbsent` 先到先得）；替换产物是列名，不再命中规则，幂等 |
| T-04 | **未纳入**（SELECT 副表别名展开）：按计划「不确定就不做」登记为边界，未实测 |
| T-05 | 新增 `HqlAliasColumnMappingTest`（**17 例**）；`HqlConverterTest.customDao_joinWithNamedParam` 补 `loc.zone_id` 断言（该夹具本就有此形态却只断言 `IN (?)`，改前必红） |
| T-06 | ON 未映射限定符尾注（最小形态）：只对「在 FROM/JOIN 中**根本没出现过**」的限定符报警，「出现过但实体解析不出」保持沉默（属已知降级，报警会淹没真实信号）；只对 SELECT/FROM 语句追加 |
| （第五轮追加） | 属性映射**跳过单引号字面量**：`PROPERTY_OR_LITERAL` 单遍交替（字面量分支在前、无捕获组），命中字面量即原样返回。紧邻形态旧正则本来就会改字面量（既有缺陷类），但 T-02 的空白容忍把这个面扩大了，故一并收掉 |

### 计划自身被证伪 / 需更正之处

| # | 计划原文 | 实测结论 |
|---|---|---|
| 1 | §五 P-1「用户 HQL 形态未知，实施第一步必须先复现」 | **已被用户补充材料证实**：形态＝显式实体 JOIN + 用户手写 ON，`WmsPickTicketDetail` 上是普通字段 `pickTicketId`/`itemId`。P-1 不再是待验证前提，见 §十一.1 |
| 2 | §十一.3「不产生『原本会被替换、现在不替换』的回归面」 | **被独立复核证伪**：限定符与点号之间有空白时（`u .code`，合法 HQL）旧实现映射、新实现不映射。第四轮**改行为**（正则容忍并原样保留点号周围空白）使该声明重新成立，而不是改声明 |
| 3 | §四 T-06「附一次性注释」 | 实测落到 UPDATE 上会让 `JpaUpdateParser` 的 WHERE 降级为 Raw（条件树渲染退化），收窄为只对 SELECT/FROM 追加 |
| 4 | §十一.2「收紧后必须仍命中 `pickTicket.updateInfo.createdTime` 这一形态」 | 成立且已由**整串黄金比对**钉死（见下） |
| 5 | 复核阶段的一个**主调度假设**也被证伪 | 主调度要求「把字面量分支放到属性分支之后 → 用例也应红」，实测**绿**：Java 正则左端优先，`find()` 取起始位置最靠前的匹配，而引号起点永远在引号内标识符之前。承重的是**交替本身**（去掉字面量分支那条变异确实红），不是分支顺序。实施代理未为迎合该要求伪造读数，如实登记 |

### 写集偏离裁定（均在计划 §九 清单外，逐条裁定）

| 偏离 | 裁定 |
|---|---|
| `JpaFromEntityMapper.mapSubqueryEntities` 加 `knownTableNames` 默认参数 | **接受**：复用别名扫描已解析出的「实体名→表名」，免二次 PSI 检索；默认空表使既有调用点零改动 |
| `JpqlConverter` 加 internal 作用域重载、旧 2 参重载保留 | **接受**：两条链行为一致的要求需要同一份替换核心；旧重载回落 `JpaAliasScope.EMPTY` = 历史行为 |
| `HqlConverter.convertWithEntityResolver`（internal） | **接受**：供无 Project 的单测注入实体解析（LightPlatform 环境无索引）；生产入口仍是 `convert(..., project)` 形态，且由 `testHql链路_生产入口project形态` 覆盖 |
| `JpqlConverter` 旧 `mappedColumnNames` 去重守卫**移除** | **接受（属纠错非回归）**：复核用探针证实旧守卫**既漏映射**（`statusText→status` 时 `u.statusText` 根本不替换）**又没挡住链式双重映射**（`a→b`、`b→c` 时 `x.a` 被错改成 `x.c`）；新实现单遍、按原文命中、不链式。复核结论已写入 KDoc 留痕 |
| `JpaFromEntityMapper` 的 `project == null` 守卫由早退改为「无已知表名才早退」 | **接受**：复核逐层追认对既有调用方**逐字等价**（生产 `project == null` 走的注入形态唯一键是根实体名，而该扫描器明确过滤 `it != table.entityClassName`）。KDoc 已写明「该守卫只在注入形态下才会真正放开」 |

### 读数

- **定向（还原态最终）**：`BUILD SUCCESSFUL`，12 类 / **167 例 / 0 失败 / 0 错误 / 0 跳过**
  （`HqlAliasColumnMappingTest` 17、`HqlConverterTest` 17、`HqlConverterAssociationTest` 8、
  `HqlConverterComprehensiveTest` 23、`HqlConverterJoinFetchTest` 4、`HqlConverterNewDtoTest` 2、
  `HqlConverterParamTest` 4、`HqlConverterSelectExpandTest` 27、`JpaJoinOnResolverTest` 14、
  `JpaSubqueryAdvancedHqlTest` 15、`JpqlConverterEndToEndTest` 5、`JpqlConverterTest` 31）
- **反向验证（五处，读数原文均已贴入各轮回报）**：
  1. 回退 T-01（别名分派失效）→ 10 例中 4 例红，含用户 HQL 同形用例与跨别名用例；
  2. 回退 T-02（正则回无别名形态）→ 同样 4 例红；
  3. 去掉点号空白容忍 → `test点号两侧空白_仍映射且空白原样保留` 红（产物 `z .createdTime` 未映射）；
  4. 去掉 `ON_CLAUSE` 的 `(?i)` → `testON区域边界_小写where与join截断` 红（ON 区域越过小写 `where`，
     把 `where bad.thing = 1` 里的 `bad` 当 ON 限定符点名）；
  5. 注释掉语句类型闸门 → `test尾注不落在UPDATE与DELETE语句` 红（尾注落到 UPDATE 上）；
  6. 去掉字面量分支 → 字面量保护两例红（`'% x .create_time %'` 复现复核报的缺陷形态）。
- **用户报障复现（整串黄金比对，首跑即相等）**：
  ```
  select distinct item.code from wms_pick_ticket pickTicket
  join wms_pick_ticket_detail detail on detail.pick_ticket_id = pickTicket.id
  join wms_item item on item.id = detail.item_id
  where pickTicket.warehouse_id = ? and pickTicket.status = 'EN'
    and pickTicket.updateInfo.created_time >= ? and pickTicket.updateInfo.created_time < ?
  order by item.code
  ```
  `detail.pickTicketId`/`detail.itemId` → `detail.pick_ticket_id`/`detail.item_id`（本任务主症）；
  `pickTicket.updateInfo.created_time` **外层 `updateInfo.` 保留、逐字不变**（@Embedded 展平是另一项，用户已挂起）。
  该期望串是**首跑之前写死**的纯字面量（测试文件 md5 首跑前后未变 `3bbb6dd9…`），首跑即整串相等——非「改成实际值就绿」。
- **探针**：`grep -rn "RED PROBE" --include=*.kt src/` = 0 命中（全仓）。
- **全量门禁**：见 §十二。

### 已登记边界（本轮明确不改）

1. **关联路径 JOIN 的副表别名仍走未映射兜底**（`join 根别名.关联属性 副表别名`）：扫描时点 JOIN 后跟的是根别名而非实体名；即便在 `JpaJoinRewriter` 改写后扫描，看到的只是改写产物里的表名，按短名检索不到实体类。故 `where d.createdTime = ...` 这类关联路径副表属性仍不映射——**与改前逐字一致，无回归**。要覆盖需按表名反查实体（`EntityClassFinder` 一类 API），本轮按「不扩改动面」未做。
2. **逗号分隔 FROM 列表**（`from A a, B b`，HQL 已废弃形态）不识别别名，同样走兜底。
3. `HqlConverter.mapAssociationPath`（`:376-378`）的三段式正则仍是**紧邻式**，`rti . ruleTable . id` 带空白不映射（改前也不映射、非回归）；与属性映射的空白口径不一致，登记为边界，未顺手统一。
4. `testON区域边界…` 的第一段（`'a.b%'` 那条）对 `(?i)` 修复**不敏感**（字面量保护已消除 `a.b`），真正钉住 `(?i)` 的是第二段（`:187-192`，由变异读数证实）；第三段只测已知限定符过滤。测试整体有判别力，但内部混有非判别断言。
5. `\s*` 可跨 `--` 行注释：`where z.id = 1 -- detail\n  .createdTime` 会把限定符取成注释里的末词，再按未知限定符回落根表。合法 HQL 上未构造出产物出错的现实反例，登记为 `\s*` 唯一的真实粗糙面。
6. 尾注闸门在前导注释 `/* c */ select`、CTE `with x as (...) select`、前导 `(` 形态下**丢提示**（只丢提示不丢替换），与既有 head 判据口径一致，属刻意保守。
7. `\s*` 使副表别名绑定在二次转换时丢失（实体名已换成表名、PSI 查不到），退化为只看根表——**这恰是幂等成立的原因**；若某根表字段名与某副表列名撞名则可能破坏幂等（未构造出实际反例）。
8. 尾注文案与下游解析器存在隐式耦合：注释文本内不得出现 `FROM`/`JOIN` 后紧跟空白+标识符（`SelectClauseExpander.joinPattern` 与 `JpaFromEntityMapper.ENTITY_REFERENCE` 都是纯文本正则、不识别注释）。现文案 `FROM/JOIN，` 满足，已写进 KDoc 锁死。

### 未做 / 未验证

- **T-04（SELECT 副表别名展开）未做**，登记边界（见上第 1 条相关）。
- **真机走查未做**（需 runIde 沙盒 + 用户工程或 demo），留人工。
- **@Embedded 展平（§六 D2）已实施**（用户补充预览代码后指示调整，见 §六 D2 后续实施）；原「产物逐字不变」的临时约束已作废。

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

