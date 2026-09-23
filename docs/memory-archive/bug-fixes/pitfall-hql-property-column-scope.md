---
name: pitfall-hql-property-column-scope
description: HQL/JPQL 属性→列名映射只覆盖根实体——副表别名属性原样透传致预览 SQL 不可执行；含 @Embedded
  路径展平缺口（用户已挂起）与「嵌入展平让外层字段名从列清单消失」的机制
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0a35f48a-3dd2-4390-b2c0-a6ed27321d0e
---

# HQL 别名作用域：属性→列名映射只覆盖根实体（O-57）

用户报障（2026-09-22，附预览面板截图）：HQL 预览产物里
`JOIN WMS_PICK_TICKET_DETAIL detail ON detail.pickTicketId = pickTicket.id` 与 `item.id = detail.itemId`，
其中 `pickTicketId` / `itemId` **未转成列名**（应为 `pick_ticket_id` / `item_id`），用户原话「应该是自动转驼峰的，但是没有转」。
复制出去执行即 `Unknown column`——预览 SQL 直接是错的。

## 一条最有诊断价值的线索：同一条 SQL 里三种待遇

| 片段 | 归属 | 结果 |
|---|---|---|
| `pickTicket.warehouse_id` | 根实体属性 `warehouseId` | ✅ 已转列名 |
| `pickTicket.updateInfo.created_time` | 根实体嵌入对象内层字段 | ⚠️ 内层转了、外层没去掉（见下「@Embedded」节） |
| `detail.pickTicketId` / `detail.itemId` | **副表**别名属性 | ❌ 原样透传 |

规律＝**根实体的属性会被映射，副表别名的属性不会被映射**。

## 根因（代码实证）

- `HqlConverter.mapPropertyToColumn`（`converter/jpa/HqlConverter.kt`）**只遍历根实体** `table.columns`
  （`table` ＝ `HqlConverter.convert` 的 `tableInfo` 入参，调用方 `ui/SqlPreviewService.kt` 的 `resolveHqlTable`
  按 FROM 子句实体解析），副表实体的列清单**从未进入循环**。
- 替换正则是 `\.<fieldName>`，**不含别名**——所以跨别名同名字段还会被误伤。
- 副表属性落在两条链的缝里：`JpaJoinOnResolver`/`JpaJoinRewriter` 只处理**关联路径 JOIN**（`join alias.relation`），
  对显式实体 JOIN（`JOIN <实体名> alias ON ...`）按设计原样保留（`JpaJoinOnResolverTest` 有「显式表名 JOIN 应原样保留」断言）；
  属性映射又只覆盖根实体。
- **旁证（最省力的落点）**：唯一会解析副表实体的 `JpaFromEntityMapper`（`PsiShortNamesCache` 按短名查 +
  `JpaEntityResolver().resolve`）**只取了 `result.tableName`，把已算好的 `columns` 就地丢弃**。
- **缺陷在既有测试语料里可见却无断言**：`HqlConverterTest.customDao_joinWithNamedParam` 的夹具里本来就有
  `left join WmsLocation loc on loc.zoneId = z.id`，但只断言了 `IN (?)` —— 绿灯不构成「副表属性已正确映射」的证据。

## 用户补充的 HQL 原文（2026-09-22，钉死计划原「形态未知」前提）

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
```
形态＝**显式实体 JOIN + 用户手写 ON**，`WmsPickTicketDetail` 上是普通字段 `pickTicketId`/`itemId`（非 `@ManyToOne`）。
三条被排除的插件侧来源（结论仍成立）：`JpaJoinOnResolver` 三个 ON 生成分支的操作数方向都与截图相反；
全仓无任何非 snake 的 `Id` 后缀拼接；`mapAssociationPath` 的三段式路径需要根表命中关联，而 `pickTicket` 不是根表关联。

## 修法要点（含两条防回归约束）

- 建「别名 → TableInfo」映射表（扫描 `FROM/JOIN <标识符> [AS] <别名>`），属性映射改为按别名分派；
  正则收紧为别名限定，**但必须允许别名与属性名之间存在中间段**（`\b<别名>\.((?:标识符\.)*)<fieldName>\b`）。
- **未映射别名的兜底口径**：某别名解析不出实体时**回落现状行为**（仍按根实体列清单替换），
  而不是「一律不替换」——这样改动对既有产物是严格收窄 + 新增，不产生「原本会被替换、现在不替换」的回归面。
- `JpqlConverter` 有同名 `mapPropertyToColumn`，两条链必须同步，不可只改一条。

## @Embedded 路径展平缺口（**用户已明确挂起**，2026-09-22）

截图里 `pickTicket.updateInfo.createdTime` 是**无效 SQL**：SQL 语义下 `pickTicket.updateInfo` 被解析为「库.表」。
正确产物应为 `pickTicket.created_time`（`@Embedded` 是 JPA 的字段展平，不产生中间列）。

**机制（只读核实）**：`JpaMetadataReader.flattenEmbeddedColumns` / `flattenEmbeddableClass` 展平出来的列，
其 `fieldName` **只取内层字段名**（`createdTime`），列清单里**没有 `updateInfo` 这一项**；
于是属性映射只能改到内层，外层 `updateInfo.` 段无人处理。

**用户定案**：先挂起，等补充更多预览代码后再分析调整。因此 O-57 的正则收紧**必须保持该形态现状产物逐字不变**
（仍是 `pickTicket.updateInfo.created_time`），并写成显式回归断言——**不得顺手把 `updateInfo.` 去掉**。

## 关联

- 与 [[project-db-schema-calibration]]（O-58）**根因/文件/验证完全独立、可并行**：本项是「副表属性根本没进映射循环」（纯 PSI，不需要数据源），
  O-58 是「进了映射循环但猜错了列名」（需要数据源）。**两者不能互相绕过**——命名完全标准时本项的错照旧；本项的错与列名对错无关。
