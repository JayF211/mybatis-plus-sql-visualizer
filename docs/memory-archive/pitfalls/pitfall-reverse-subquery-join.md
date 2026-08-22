---
name: pitfall-reverse-subquery-join
description: SQL→Java 反向解析器子查询 JOIN / FROM 子查询 / ext 条件 lambda / MConsumer 子查询形态全链路修复（2026-08-21）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c02dfa84-be52-4c4a-bf53-8d022454ab5e
---

**SQL→Java 反向解析器子查询 JOIN / FROM 子查询 / ext 条件 / MConsumer 形态全链路修复（2026-08-21）**

## 问题 1：子查询 JOIN / FROM 子查询报"表名格式不正确"

`SqlReverseParser.parseJoin()` L369 和 `parseSelectBody()` L77 调用 `parseTableName(cursor)`，硬要求 WORD token——`(SELECT ...) alias` 以 `(`（SYMBOL）开头 → 报错。

**修复**：
- 新增 `parseSubqueryTable(cursor)` — depth 计数捕获完整括号 + `consumeJoinAlias` 消费别名
- 新增 `parseJoinTableExpression(cursor)` — 返回 `Triple<String, String?, JoinSubQuery?>`（表文本, alias, 子查询结构）
- 修改 `parseJoin()` L374 和 `parseSelectBody()` L77-82 检测 `(` 分流

## 问题 2：ext 条件 JOIN 回落字符串（Case 1）

`canLambdaJoin` 的 `joinKeyComparison` 要求 `singleOrNull()`，ext 多条件时返回 null → 回落字符串。

**修复**：
- 新增 `JoinKeyInfo(key, extConditions)` + `extractJoinKey()` 替代 `joinKeyComparison()`
- `canLambdaJoin` 放宽为"恰好 1 个 ColumnComparison + 其余副表 Simple 条件"
- `renderJoins` 拆分 key/ext：无 ext → 3 参 `leftJoin(Class, left, right)`，有 ext → 4 参 `leftJoin(Class, left, right, ext -> ext.eq(...))`
- ext 操作符用 `opMethod(ext.op)` 映射（`=`→eq, `>=`→ge），**不可硬编码 `"ext.eq"`**

## 问题 3：MConsumer 子查询 JOIN 回落字符串（Case 2）

子查询 JOIN 无法 lambda 化 → 整个 wrapper 从 `MPJLambdaWrapper` 降级为 `MPJQueryWrapper`。

**修复**：
- 新增 `JoinSubQuery(tableName, alias, selectAll, selectColumns, whereConditions)` 数据类
- 新增 `parseSubqueryStructure(subqueryText, alias)` — 递归调用 `SqlReverseParser.parse()` 解析子查询内部结构，检查是否为简单单表查询（无 JOIN、无嵌套子查询）
- `parseSubqueryTable` 返回 `Pair<String, JoinSubQuery?>`（文本 + 结构）
- `ReverseJoin` 新增 `subQuery: JoinSubQuery?` 字段
- 新增 `renderMConsumerJoin(join, entity)` — 生成 `leftJoin(SubClass.class, t -> t.setAlias("tt").selectAll().ge(Sub::getX, val), Sub::getY, Main::getZ)`
- `renderJoins` 优先尝试 MConsumer 形态，其次 ext lambda，最后回落字符串

**MConsumer 匹配逻辑**：
1. `join.subQuery != null && entity != null`
2. 三档匹配副实体：`entity.subEntities[subQuery.alias]`（key 匹配）→ `className.equals(alias)` → `className.contains(tableName 推导)`
3. 提取 join key（ON 等值列对）+ leftField/rightField 映射
4. 构建 MConsumer 链：`t.setAlias("tt")` → `t.selectAll()` 或 `t.select(Sub::getX, ...)` → `t.ge(Sub::getX, val)`

**MConsumer 链格式陷阱**：`consumerChain` builder 已添加 `.` 连接符，`selectExpr` 和 `whereChain` 不可带 `t.` 前缀。正确：`t.setAlias("tt").selectAll().ge(...)`，错误：`t.setAlias("tt").t.selectAll().t.ge(...)`

## 关键陷阱（测试/实现）

1. **`columnToField` key 必须用 snake_case**（匹配 SQL 列名），value 用 camelCase（Java 字段名）。`canLambdaJoin` 直接查 `sub.columnToField[sqlColumnName]`，camelCase key 查不到 → 误回落字符串
2. **多行 SQL 子查询断言**：`contains("(SELECT")` 失败——`javaEscape` 将 `\n` 转为 `\\n`。修复：`contains("(") && contains("SELECT")`
3. **join key right 字段归属**：`canLambdaJoin` 只查主实体 `entity.columnToField`，不查其他副实体。如 `t2.id = t1.area_id` 中 `area_id` 在 address 副实体 → 回落字符串是正确行为
4. **`ON 1 = 1` 无效**：`1` 是 NUMBER token → `parseColumnRef` 要求 WORD → 报错。测试只能用合法条件
5. **select 列归属**：`t.model` 通过 `resolveColumn` 回退扫描副实体匹配 → 生成 `BikeDeviceProductInfoEntity::getModel`（非主实体）
6. **嵌套子查询**：`parseSubqueryStructure` 递归解析，含嵌套子查询时返回 null → 回落字符串形态
7. **旧测试断言更新**：3 个旧测试期望字符串形态，Case 1 后生成 lambda → 断言需更新为 4 参 lambda
8. **Wrapper 类型不匹配**：`effectiveKind` 只检查 `canLambdaJoin` 时，MConsumer 子查询被误判为不可 lambda 化 → 整个 wrapper 降级为 `MPJQueryWrapper`。修复：条件增加 `|| canMConsumerJoin(it, entity)`，新增 `canMConsumerJoin()` 函数委托 `renderMConsumerJoin() != null`
9. **MConsumer 链 `t.` 前缀**：`selectExpr`/`whereChain` 不可带 `t.` 前缀（builder 已加 `.`）。测试断言也要对应：`t.select(` → `.select(`, `t.ge(` → `.ge(`

## 改动文件清单

- `SqlReverseParser.kt` — `parseSubqueryTable()`, `parseJoinTableExpression()`, `parseSubqueryStructure()`, `JoinSubQuery`, `ReverseJoin.subQuery`
- `JavaCodeGenerator.kt` — `JoinKeyInfo`, `extractJoinKey()`, `renderMConsumerJoin()`, 放宽 `canLambdaJoin`, `renderJoins` 三分支
- `SqlReverseParserTest.kt` — 6 个子查询 JOIN 测试 + 3 个 MConsumer 结构解析测试
- `JavaCodeGeneratorTest.kt` — 1 个 raw join + 3 个 ext lambda 测试
- `SqlToJavaRoundTripTest.kt` — 4 个端到端 + 2 个 MConsumer 端到端测试
- `HqlQueryLocator.kt` — `allCalls.toList()` 修复预存编译错误（Collection → List）

**关联记忆**：[[project-reverse-join-fix]]（JOIN 修复总览）、[[project-reverse-sql-java-improvements]]（反向链路质量大修）

**实施计划**：`docs/开发计划/20260821-1500-子查询JOIN与FROM子查询反向解析修复-开发计划.md`