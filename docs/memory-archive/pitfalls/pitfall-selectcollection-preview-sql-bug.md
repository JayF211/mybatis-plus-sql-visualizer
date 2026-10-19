---
name: pitfall-selectcollection-preview-sql-bug
description: selectCollection/selectAssociation 类参必须解析为 EntityRefArg（PSI 层），否则子查询表名推断错误/重复 JOIN/ON 别名不一致（2026-08-21 已修复，commit bfdd238）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0f76781d-7b55-4784-8e56-f549f79a5023
---

`selectCollection` / `selectAssociation` 预览 SQL 三缺陷已修复（2026-08-21，commit bfdd238，1574 测试全绿）：

**根因**：PSI 层 `ArgResolver` 对 selectCollection/selectAssociation 的类参未做实体解析（走通用 `resolveValueArg` 求值为类名字面量 ValueArg），丢失 @TableName 注解。对比 leftJoin 有专门 `resolveJoinClass` → `EntityRefArg`。

**Bug 1 — 子查询表名推断错误**：`parseCollection()` 用 `inferDecoratedTableName("AddressDO")` → `address_d_o`，实际 `@TableName("user_address")`。修复：`collectionTableInfo(call)?.tableName` 优先取 EntityRefArg 真实表名。

**Bug 2 — 重复 LEFT JOIN**：`convert()` 去重比较 `inferDecoratedTableName(cls)` vs `join.tableName`，二者来源不同永远不匹配。修复：改为类名直接比较 `joinEntityClassName(call) == cls`。

**Bug 3 — ON 条件别名不一致**：ON 从 leftJoin 复用，leftJoin 别名 `addressDO`，collection 别名 prefix `user_address`。修复：复用时替换 ON 条件中的别名为 collection 别名。

**关键教训**：新增 MPJ 方法的 PSI 解析时，若方法携带类参（`XxxEntity.class`），必须用 `resolveJoinClass` 解析为 EntityRefArg（携带 @TableName 注解），不能走通用 `resolveValueArg` 只取类名字面量。prefix 形态检测（`isPrefixForm`）必须兼容 EntityRefArg 在第 2 参的情况（`isClassNameArg` 对 EntityRefArg 返回 false）。

**文件**：`psi/ArgResolver.kt`（新增 `isCollectionClassArg`/`isCollectionVoGetter`/`isPrefixForm`），`converter/MpjLambdaConverter.kt`（新增 `collectionTableInfo`/`joinEntityClassName`，修复 parseCollection + convert 去重）。