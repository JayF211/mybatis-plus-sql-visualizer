# T-ExplainFirstNative 执行计划首点结果缺失 + EXPLAIN 表格形态对齐原生 - 开发计划

## 背景与问题

用户真机反馈（IDEA 2026.2.2，IntelliJIdea2026.2/log/idea.1.log 21:06:40~21:07:47 现场留存）两个问题：

1. SQL 预览窗口首次点击「执行计划」，结果出现在 IDEA 原生控制台（Database 控制台），预览面板内不显示；再点一次预览面板才出结果。「执行 SQL」无此现象。
2. 执行计划输出是一段 MySQL `EXPLAIN FORMAT=JSON` 原始 JSON（query_block/cost_info），与 IDEA 原生 Explain Plan 的呈现不一致，要求对齐原生能力。

## 根因

问题一（日志实证，逐行对齐 21:06:44 首点与 21:06:52 二点）：

- 首次执行时 DBN 在 21:06:45,427 通过 contentAdded 事件创建的结果 Content 是**空名占位**（`contentAdded: `，displayName 为空串），网格随后异步构建、占位改名 "Result 1"。二点起步时 result watch 快照已是 `pre-existing-names=[Result 1]`，占位阶段被跳过。
- `DataSourceSelector.pickResultTab` 以「displayName 不在提交时刻快照集合」判定新结果 Tab——空名 ∉ 空快照集合恒为真，400ms 重试第 1 拍就把空名占位当成新结果，立即 selectAndFocus 并触发 `refreshResultsComponent(forceSwapIn=true)` 强制换入（21:06:46,366 tick=1），此刻网格尚未构建；重试器随即停表，网格就绪后无人补换入（tick 2~8 因 `live === lastLiveResultSwapped` 不再触发），结果只留在原生 Database 控制台。
- 附属：`attachResultsHook.contentAdded` 对空名占位同样立即换入（21:06:45,438），同属过早换入。DBN 后续各次执行不再回调该监听（全 session 仅首点 1 次 contentAdded），换入实际依赖轮询链。

问题二（DatabaseTools jar 反编译为唯一权威，262.10315.125）：

- IDEA 原生 MySQL 家族 Explain Plan 从不展示 JSON。MySQL 8.0+ 走 `MysqlExplainPlanProvider`：执行 `EXPLAIN [ANALYZE] FORMAT=TREE <语句>`，解析 `->` 缩进文本建树（MysqlTreePlanModelBuilder）；MySQL <8.0 与 MariaDB 走 `MysqlBaseRawPlanData`：执行 `EXPLAIN [EXTENDED] <语句>` 表格行（PlanRow 直读 id/select_type/table/type/key_len/rows/filtered/Extra），按 id 嵌套建树。
- 插件 M37.2 的 `EXPLAIN FORMAT=JSON` 是自创形态，全树无任何渲染器消费该 JSON，真机网格只能呈现一坨 JSON 文本。

## 修复

问题一（DataSourceSelector.kt，轮询链三处，contentAdded 监听不可靠不动其结构）：

1. `pickResultTab`：newOne 与 staleOne 候选均跳过空名 Content——空名占位不是结果，等改名后再选中（首点病灶直接修复）。
2. `attachResultsHook.contentAdded`：空名直接 return，不做过早换入。
3. `refreshResultsComponent`：每拍快照结果 Tab 名称集合，集合变化（占位改名/新增 Tab）时强制补换入一次——网格构建晚于换入时由后续拍兜底重绘。
4. `afterSubmitted` 8s 监控的 newTab 判定同步跳过空名（避免占位误判执行成功）。

问题二（Dialect.kt）：

- `MySqlDialect` / `MariaDbDialect` 的 `renderExplainStatement` 改为原生 MySQL 家族表格形态 `EXPLAIN <sql>`（5.7/8.0/8.4/MariaDB 全版本可用；FORMAT=TREE 仅 8.0.16+ 且方言无版本感知，不选）。网格直接呈现 id/select_type/table/type/key/rows/filtered/Extra 经典计划行，JSON blob 消失。
- PG/KingBase 的 FORMAT JSON 保留（PG 家族原生同为 JSON 并解析成树，非本次问题面）。
- 原 FORMAT=JSON 幂等测试样例保留——证明历史已包装文本升级后不产生双前缀。

## 测试

- `ResultTabSelectionTest` 新增空名占位三例：空名不作为新结果候选、空名不作旧 Tab 复用、Output+空名并存时等待直至改名。
- `DialectExplainTest`：MySQL/MariaDB 期望改 `EXPLAIN $sample`，幂等用例期望同步（历史 FORMAT=JSON 文本幂等样例保留）。
- 全量回归基线 4072/0/0/6（独立沙盒 build/test-sandbox-explain，并行表 t-displaymode-verify 活跃期间按铁律隔离）。

## 真机验证点（留人工）

1. 预览窗口冷启动（IDE 重启后首个 SQL）直接点「执行计划」，结果一次进预览面板。
2. 执行计划结果为经典表格行（无 JSON）；重复点击无双前缀（T-ExplainDbl 回归）。
3. 「执行 SQL」首点照常。
4. 原生 Explain Plan 树形视图（PlanView）的反射调用对齐属二期，本轮表格形态已消除 JSON blob。
