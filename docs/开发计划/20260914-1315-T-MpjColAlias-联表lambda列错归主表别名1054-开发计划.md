# T-MpjColAlias：MPJ 联表 lambda 列错归主表别名（真机 1054）- 开发计划

- 登记时间：2026-09-14 13:15（parallel_tasks T-MpjAlias，worktree wt-t-mpjalias）
- 任务类型：缺陷修复（真机用户反馈）
- 基线：master 58a2ea5（T-ExplainIdempotent 合入后）

## 一、问题现象

用户在 mybatis-plus-join-demo 的 `DeviceInfoServiceImpl.joinRightAlias()` 上打开 SQL 预览，
生成 SQL 为：

```sql
SELECT t.id, t.station_id, t.share_scheme_id, t.device_name, t.station_name
FROM car_device_info t
LEFT JOIN station_info st ON st.station_id = t.id
WHERE t.id = 1
```

执行报 `[42S22][1054] Unknown column 't.station_name' in 'field list'`。
`station_name` 属于关联表 `station_info`（别名 st），`car_device_info`（别名 t）无此列——
预览把关联实体列错归了主表别名。

源码形态（MPJ 官方 API）：

```java
JoinWrappers.lambda(DeviceInfoEntity.class)
    .selectAll(DeviceInfoEntity.class)
    .select(StationInfoEntity::getStationName)          // ← 关联实体 lambda 列
    .leftJoin(StationInfoEntity.class, "st",
            StationInfoEntity::getStationId, "t", DeviceInfoEntity::getId)
    .eq(DeviceInfoEntity::getId, 1);
```

## 二、根因

`MpjLambdaConverter.convert` 把 join/selectAs 之外的调用整体委托
`QueryWrapperParser(table, mainAlias)`，解析器对每个 lambda 列执行
`column.withTableAlias(mainAlias)`（`withTableAlias` 仅在 `tableAlias == null` 时补），
即**无条件补主表别名 "t"**——单表语义正确，MPJ 联表场景下 `select(StationInfoEntity::getStationName)`
的接收者实体类信息（`ColumnRef.entityClass`，`ArgResolver.resolveLambdaColumn` 已携带）被丢弃。

对照：`selectAs` 管线已有 `classToAlias`（`buildAliasToTable(joinCalls)` 反推）归属逻辑，
`parseSelectAs` 按 `column.entityClass` 查 join 别名；但普通 `select()`/条件/orderBy 走的
QueryWrapperParser 委托链没有这层归属。

## 三、修复方案

不改 QueryWrapperParser（单表/MPJ 共用语义不动）。在 `MpjLambdaConverter` 委托前对
`restCalls` 做一次性调用树重写（新增 `attributeJoinColumns` / `attributeColumn`）：

- `ColumnArg`：列无别名、`entityClass` 非空、≠ 主实体类、且命中 `classToAlias` → 预置
  `tableAlias = join 别名`；解析器 `withTableAlias` 仅空别名补前缀，既有归属保留。
- `NestedArg` 递归（and/or 嵌套 lambda 体内关联列）；`FuncArg.columns` 同规则。
- 字符串列（ValueArg，MPJ xxxStr 系列已含限定）不触碰；已有显式 `tableAlias` 不覆盖；
  self-join 主实体接收者列保主表别名。
- `classToAlias` 计算上移到委托前复用（原本只在 selectAs 分支之后才建）。

覆盖入口：select / eq / like / orderBy / groupBy / having 等全部经 QueryWrapperParser
列解析的方法，一处重写全链生效。

## 四、测试

`MpjJoinExtConverterTest` 新增 5 例（纯逻辑，与真机场景同形）：

1. `mpjJoin_select关联实体lambda列归属join别名`——真机 1054 复现（期望 `st.station_name`）；
2. `mpjJoin_select主实体lambda列保持主表别名`——接收者=主实体不改写；
3. `mpjJoin_select未知实体lambda列保持主表别名`——entityClass 不在 join 映射原样；
4. `mpjJoin_已带别名lambda列不覆盖`——显式 tableAlias 保留；
5. `mpjJoin_嵌套条件关联实体列归属join别名`——and(nested) 体内列递归归属。

全量：worktree 分支与合入 master 后各跑一遍（计数见变更记录）。

## 五、实施记录

- 修复：`MpjLambdaConverter.kt`（attributedRest 委托 + attributeJoinColumns/attributeColumn）。
- 测试：`MpjJoinExtConverterTest.kt` +5 例。
- worktree 分支（基线 58a2ea5，3980+5）全量：**3985/0/0/6 绿**。
- 合入 master（merge 75c0cdb，master 侧含 T-FeedbackDlg2 +2 基线 3982）后 detached 复测：
  **3987/0/0/6 绿**。主仓库默认沙盒复跑曾报 `SqlPreviewPanelTest` 5 例
  `cannot open system clipboard`——同 commit 独立沙盒全绿实锤环境型剪贴板 flake
  （并行会话占锁 + 系统剪贴板竞争，见回归假失败清单），非本改动。
- verifyFull/buildPlugin 按用户口径（发版/大功能才跑）本轮跳过；真机走查（demo
  joinRightAlias 预览列 `st.station_name`）留人工。
