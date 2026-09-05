# T-BareFromSelect 无别名 HQL 预览缺 SELECT 列清单修复

> 立项：2026-09-04 16:20
> 用户实测：demo `CommonDaoHibernateImpl.findAllAsn()` → `getHibernateTemplate().find("from WmsAsn")`，预览只显示 `FROM wms_asn`，SELECT 列清单缺失。

## 一、现象

```java
public List<?> findAllAsn() {
    return getHibernateTemplate().find("from WmsAsn");
}
```

当前预览输出只有 `FROM wms_asn`；期望输出 `SELECT wms_asn.id, wms_asn.asn_code, wms_asn.warehouse_id, wms_asn.create_time FROM wms_asn`。

表名 `wms_asn` 能正确显示，说明实体解析链路（`resolveHqlTable` → `findClassByShortName` → `JpaEntityResolver`）是通的，4 列元数据都在；缺的是 SELECT 展开。

## 二、根因

1. **`HqlConverter.parseAliasColumns` 只认「表名+别名」两段式**。
   主表别名登记正则要求 FROM 后跟两个标识符（`from WmsAsn asn` 才命中）。裸表名 `from wms_asn` 只有一个标识符 → 不产生任何别名条目 → `aliasColumns.isEmpty()` 直接原样返回，「无 SELECT 补列清单」分支永远走不到。
2. **JOIN 族关键字会被误登记为别名**。
   `from WmsAsn left join ...`（无主表别名）两段式会命中 `left` 当别名，展开会产出 `SELECT left.id`（SQL 语法错）。当前被根因 1 的短路掩盖，修根因 1 时须一并防住。
3. **`JpqlConverter` 完全没有 SELECT 展开**。
   `@NamedQuery` 路径（`previewNamedQuery`）用 JpqlConverter，同样输入 `from X` 只做实体名→表名替换，无隐式查询补列清单、无 `SELECT u` 展开，与 HqlConverter 输出不一致。

影响面：所有裸 `from 实体`（不带别名）的 HQL/JPQL，含 `session.createQuery("from X")` 单参形态与 `getHibernateTemplate().find("from X")`；带别名写法不受影响。

## 三、方案

把 HqlConverter 的 `expandSelectClause` 及其辅助函数（约 160 行）抽取为包内共享对象 `SelectClauseExpander`，两个转换器共用，然后修登记逻辑：

- 裸表名回退：两段式不命中时，把 FROM 后第一个标识符（表名本身）登记为列限定符，展开产出 `SELECT 表名.列, ...`（合法 SQL，与别名形态一致）。
- 两段式加 JOIN 族关键字排除（LEFT/RIGHT/INNER/OUTER/CROSS/FULL/JOIN/ON/FETCH），防关键字被当别名。
- JpqlConverter 在属性→列名映射之后调用同一展开器，对齐 HqlConverter 行为。

DELETE 短路逻辑保留在展开器内部（HqlConverter/JpqlConverter 的 DELETE 分支在展开之后判断，行为不变）。

## 四、用例与断言

| 用例 | 做法 | 断言 |
|------|------|------|
| 裸表名补列清单 | `from UserEntity`（4/6 列元数据表） | `SELECT user_entity.id, ... FROM user_entity`，不含裸实体名 |
| 裸表名 + WHERE | `from UserEntity where status = :status` | 列清单 + `where status = ?`，占位符 1 个 |
| 裸表名 + JOIN | `from UserEntity left join ... d on ...` | SELECT 用表名限定；不产出 `left.id`；FROM 原样保留 |
| 裸表名 + 表不可解析 | `convert(hql, null)` | `SELECT unknown_table.* /* 列信息未解析... */ from unknown_table` |
| 带别名回归 | 既有 `HqlConverterSelectExpandTest` 全部 | 不变（别名形态展开结果与此前一致） |
| JpqlConverter 隐式查询 | `JpqlConverter.convert("from UserEntity", userTable)` | 补出 SELECT 列清单（与 HqlConverter 同形） |
| JpqlConverter 回归 | 既有 `JpqlConverterTest`/`EndToEndTest` | contains/占位符计数断言全部不破坏 |

## 五、回归清单

- converter/jpa 全部测试类（HqlConverter* 7 个 + JpqlConverter* 2 个）
- ui/gutter 包（DemoRecognitionTest 等图标用例不涉及 SQL 文本，应不受影响）
- SqlPreviewServiceJpaTest（平台测试，若含精确 SQL 断言须同步更新期望值）

## 六、实施记录

2026-09-04 实施完成，全量定向 + 回归绿。

改动（4 文件 + 1 新增）：

- 新增 `converter/jpa/SelectClauseExpander.kt`：展开器自 HqlConverter 抽取（expand + 8 个私有辅助），HqlConverter/JpqlConverter 共用。两处新逻辑：
  - 裸表名回退：两段式别名不命中时，FROM 后第一个标识符（表名本身）登记为列限定符，隐式查询补出 `SELECT 表名.列, ...`。
  - 两段式正则加 JOIN 族关键字排除（LEFT/RIGHT/INNER/OUTER/CROSS/FULL/JOIN/ON/FETCH，IGNORE_CASE）——`from t left join ...` 中 LEFT 不再被当别名（否则会产出 `SELECT left.id` 语法错）。
  - 顺带对齐：无 SELECT 分支列元数据不可得时降级 `alias.*` + 注释（此前 `?: return sql` 原样返回，与显式 SELECT 分支的降级行为不一致）。
- `HqlConverter`：删除约 190 行私有展开代码，步骤 9 改调 `SelectClauseExpander.expand`（`createFallbackTable` 误删后已补回）。
- `JpqlConverter`：convertInternal 步骤 7.5 接入 `SelectClauseExpander.expand`，`@NamedQuery` 隐式查询与 `SELECT u` 展开对齐 HQL 路径。

测试：

- `HqlConverterSelectExpandTest` 新增第 8 节 4 用例（裸表名补列清单 / 带 WHERE / JOIN 关键字不误判 / 表不可解析降级星号）。
- `JpqlConverterTest` 新增 3 用例（裸 from 补列清单 / SELECT u 展开 / 显式列引用不展开）。
- 首轮 3 处 ComparisonFailure 均为测试期望写错（FROM 大小写——转换器保留原文大小写，SELECT 为合成大写；别名展开用别名限定 `u.id` 而非表名），修正期望后绿。
- 回归：converter/jpa 全部 + JpaDemoIntegrationTest + ui/gutter 包 + SqlPreviewServiceJpaTest + actions 包，BUILD SUCCESSFUL。

并行隔离：登记 T-BareFromSelect / sandbox=barefrom-select，build.gradle.kts 临时 sandboxContainer 已还原，已注销。
