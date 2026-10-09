# T-PreviewExecDsDialect：预览执行选择器按数据源方言出 Runner（DM 等非 MySQL 数据源无法从预览执行）

- 占号：O-136/R82/D140/V163（引用前已实测 docs/开发计划 未占用）
- 日期：2026-10-09 晚
- 载体建议：dev-bak 直提（单点小改）或独立工作树；触发场景＝用户在达梦（DM）等非 MySQL 数据源场景下使用「执行 SQL」
- 关联：本计划由 20261009 深夜沙盒复验直接产出（DM 驱动/数据源已在沙盒建好并连通，证据链见下）

## 一、现象（沙盒实测，20261009 深夜）

1. 沙盒（243）里已建好达梦数据源 `mpj_dm@114.66.55.245`（驱动 Dameng DM8 = 本机 `.m2` 的 DmJdbcDriver18-8.1.3.140.jar，Class `dm.jdbc.driver.DmDriver`）：
   - Database 工具窗树中出现该数据源；展开触发内省成功（mpj_demo schema、tables 6）。
   - 数据源属性里 **Test Connection = Succeeded**（DBMS: DM DBMS 8.1.3.12，Driver: dm.jdbc.driver.DmDriver 8.1.3.140，Ping 65 ms）。
   - 该数据源自己的 Query Console 执行设备查询（`SELECT … FROM car_device_info t LEFT JOIN station_info st … WHERE (t.id = 1);`）**返回结果行**（充电桩A-01，1 row，1s 207ms）。
2. 同一条 SQL（来自历史条目、参数已回填 `t.id = 1`、无占位符、可执行形态）在插件预览面板点「执行 SQL」：
   - 弹出的「选择数据源」列表只有 5 个 MySQL 数据源（jpa/mpj/mp/kotlin/selfdev @localhost）+ All，**没有 DM 数据源**。
   - 选择任一 MySQL 数据源则因表不存在报 `[42S02][1146] Table 'mp_demo.car_device_info' doesn't exist`。
3. 附带正面证据：预览执行链路本身是通的——首击即渲染内嵌结果区（Output/Result 1 + 按 SQL 解析出的列 + 即时状态/错误行），T-FirstExecResultInvisible 的修复行为在真机成立。

## 二、根因链（代码定位，实施前先证伪 P1）

- 预览执行走 `DataSourceSelector` 反射平台 `DatabaseRunners.getAttachDataSourceRunners(psiFile, sql, sessionConsumer)` 枚举 Runner（`DataSourceSelector.kt` §getAttachDataSourceRunners）。
- 平台按 **psiFile 的 SQL 方言**过滤 Runner：预览的 SQL 草稿（`mpsql-exec.sql` / console 虚拟文件）由插件创建，方言经 `DialectPreMapping.ensureMapped` **固定映射为 MySQL** ⇒ 平台只为 MySQL 系数据源建 Runner ⇒ DM（及一切非 MySQL 方言）数据源被排除。
- 对照证据：同一 DM 数据源的原生 Query Console（方言=DM）能执行同一 SQL 并出结果行 ⇒ 数据源/驱动/网络全通，堵点只在「Runner 枚举的方言匹配」。

## 三、目标

- 预览「执行 SQL」的数据源选择器**按所选/可选数据源的方言出 Runner**：DM 数据源可被选中并首击出结果数据。
- 不回归：MySQL 数据源的既有预览执行路径（含占位符参数面板、上次使用记忆）零变化。

## 四、方案方向与决策点

- **S1（主方案）：控制台文件方言跟随目标数据源**。在预览执行准备链里，按候选数据源的方言设置 SQL 草稿文件的 dialect/language（DM 数据源 ⇒ DM 方言），使平台 Runner 枚举命中。
  - D1 方言来源：优先数据源在 DAS 的方言（introspection 的 databasePlatform）；兜底用插件 `Dialect.kt` 的 URL/驱动推断（DM 推断已存在并在真机验证过）。
  - D2 落点：虚拟文件创建时（`mpsql-exec.sql` 首建）按「上次使用/默认数据源」设定；选择器弹出前如目标方言≠当前方言则重建/切换 psiFile。须保证执行用的 psiFile 与高亮展示一致。
  - D3 兜底提示：Runner 列表为空时，把现在的「等待结果超时」类提示改为可行动文案（「没有匹配该 SQL 方言的数据源」），不再让用户等 8s 超时。
- **S2（否决备选）：绕开平台 Runner 自建执行通道**（自管 JDBC 连接）。改动面与风险远大于收益（放弃 DAS 的参数/结果/事务生态），不采纳，仅留档。
- 决策点 D4（范围）：是否同时放开「多语句脚本（分页 SQL+COUNT）」在 DM 上的执行。DM 支持与 MySQL 同形的 `LIMIT`，但占位符未填时的行为需实测（见 P4）；如复杂可先支持单语句，多语句维持现状并在文案注明。

## 五、待验证前提（实施者先证伪再动手）

- **P1**：`getAttachDataSourceRunners` 的过滤依据确为 psiFile 的 language/dialect（读 243 平台源码或做最小实验：手工把草稿文件方言改成 DM 后观察 Runner 是否出现）。
- **P2**：方言=DM 的草稿文件 + DM 数据源 ⇒ Runner 出现且执行出结果行（沙盒可直接实验，DM DS/驱动已就位）。
- **P3**：方言跟随数据源后，MySQL 数据源路径零回归（mp_demo/mpj_demo 预览执行仍首击出结果；占位符参数面板行为不变）。
- **P4**：DM 方言下分页双语句脚本（`LIMIT ?,?` + COUNT）的可执行性；占位符未填时的报错形态（决定 D4 的范围收敛）。
- **P5**：`DialectPreMapping` 的「一切映射到 MySQL」在预览链的其他读点（高亮/格式化/执行计划）是否被共享——若共享，方言跟随数据源需要限定作用域，避免高亮链被带偏。

## 六、验收清单（拟）

- **V1**：沙盒对 DM 数据源预览执行——选择器出现 DM 数据源，**单击一次「执行 SQL」即出结果行**（充电桩A-01）。
- **V2**：MySQL 回归——mp_demo/mpj_demo 预览执行仍首击出结果；`testFast` 相关定向零回归。
- **V3**：无可匹配数据源时的提示文案可行动（指明方言不匹配，而非超时）。
- **V4**：守卫/单测：方言跟随逻辑的单测（DM DS ⇒ DM 方言草稿；MySQL DS ⇒ MySQL）+ 反向验证（摘除跟随 ⇒ DM Runner 消失）。

## 七、运行读数

（实施时回填：定向测试读数、反向验证读数、沙盒 V1~V4 截图与 idea.log 关键行）

## 八、影响面 / 不做

- 不改捕获链（agent/桥）、不改 MCP 的数据源匹配、不改 MySQL 既有执行行为。
- 不在本计划内解决「DAS 控制台对未配凭据数据源不产结果」的一般性问题（凭据属数据源配置，用户配置后即通；本轮已在沙盒实证 DM DS 配好后可出结果）。
- 达梦 ADD_BATCH/CLEAR_BATCH 织入面缺失（T-DmWeave 已登记的织入局限）与本计划无关，不在此处理。
