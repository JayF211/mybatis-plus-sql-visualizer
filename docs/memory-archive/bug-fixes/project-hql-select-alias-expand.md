---
name: project-hql-select-alias-expand
description: M36.7 HQL SELECT 实体别名展开为列清单——修复 MySQL [1054] Unknown column 'u'（v2026.9.2）；2026-09-04 发现无别名裸 from 不展开缺口，待实施
metadata:
  node_type: memory
  type: project
  updated: 2026-09-04
  originSessionId: sess_release_2026_9_2
---

M36.7 (2026-08-31)：HQL `SELECT u`（实体别名）展开为列清单，修复生成的 SQL 在 MySQL 执行报 `[1054] Unknown column 'u' in 'field list'`。

**根因**：HQL 语义 `SELECT u` 表示取整个实体，`HqlConverter` 此前原样输出导致别名被当作列名。

**新增 SELECT 子句展开规则**（`HqlConverter`）：
- 裸别名且登记在 FROM/JOIN → 展开为实体全列清单
- 无 SELECT 子句 → 自动补列清单
- JOIN 副表 / 元数据缺失 → 降级 `alias.*`
- DELETE / DISTINCT / 子查询 / 聚合 / 显式列 → 不误伤

**测试**：新增 `HqlConverterSelectExpandTest`（197 行）全路径覆盖；既有 `HqlConverterComprehensiveTest`/`HqlConverterJoinFetchTest`/`HqlConverterParamTest` 同步更新；全量 2682 用例 0 失败。

**How to apply**：HQL 预览遇到 `SELECT 别名` 即可自动展开；副表元数据不可用时降级 `alias.*` 仍可在 DB 执行。

**2026-09-04 新缺口（已诊断，待实施）**：用户反馈 `getHibernateTemplate().find("from WmsAsn")` 预览只显示 `FROM wms_asn`，SELECT 列清单缺失。

- 解析链路本身成功：`previewCustomDaoHql`（SqlPreviewService）→ `resolveHqlTable(null, …)` 从 HQL 提取 `WmsAsn` → findClassByShortName → `JpaEntityResolver` 产出 4 列 TableInfo；`mapEntityToTable` 把 WmsAsn→wms_asn 成功（表名能正确显示即证据）。缺的只是展开步骤。
- **根因**：`HqlConverter.parseAliasColumns` 登记主表别名的 rootPattern 是两段式正则（`FROM 表名 别名` 两个标识符），裸表名 `from wms_asn`（无别名）rootMatch=null → aliasColumns 空 → `if (aliasColumns.isEmpty()) return sql` 短路返回 → 「无 SELECT 子句补列清单」分支永不执行。
- 影响面：所有裸 `from 实体`（无别名）的 HQL 都缺 SELECT，含 `session.createQuery("from X")` 单参形态；带别名写法（`from WmsDockBooking booking`）不受影响。
- 修复方向：parseAliasColumns 两段式不匹配时回退，把 FROM 后第一个标识符（表名本身）当别名登记，产出 `SELECT wms_asn.id, wms_asn.asn_code, ... FROM wms_asn`（表名限定是合法 SQL，与既有 renderColumns 逻辑一致）；改动集中一个函数。
- 同族问题：`JpqlConverter`（@NamedQuery/派生查询路径）完全没有 SELECT 展开逻辑，同样输入会把实体名原样输出——应作为同批次第二用例覆盖。

**相关记忆**：[[project-hql-association-xml-mapping]]（HQL 关联字段路径解析）、[[pitfall-gutter-provider-receiver-not-checked]]（本缺口的发现入口 find 通道）
