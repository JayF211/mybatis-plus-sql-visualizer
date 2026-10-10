# T-DataSourceBadgeMode：数据源上下文角标默认仅显示数据库类型

> **来源**：用户 2026-10-09 需求「数据源上下文角标我认为默认显示数据库类型即可，用户可在配置
> 页面打开显示数据源上下文，不然默认显示的内容太多了」。经分析合理（见 §二），立项。
> **生成日**：2026-10-09；写完停下等确认，未动手。
> **占号**：O-133/R79/D135/V158（引用前实测：O 最高 O-132 / R 最高 R78 / D 最高 D134 /
> V 最高 V157）。

## 一、现状（代码实证，dev-bak `2593a54` 态）

- **生产单点**：`AgentEventAdapter.kt:59` — `databaseName = JdbcUrlDialects.badgeName(event.url)`，
  产出「方言显示名 · 连接事实」形态（如 `MySQL · jpa_demo@localhost:3306`；只有其一输出其一；
  user/password 解析期剔除绝不入字段）。桥通道恒 null。
- **渲染单点**：`SqlLogPanel.kt:2447` `formatDatabaseFragment(entry, isSelected)`（internal 顶层
  纯函数，渲染为 `[MySQL · jpa_demo@localhost:3306] ` 加粗段）——**两页渲染器（日志页
  LogEntryListCellRenderer 与历史页 HistoryCellRenderer）共用本函数**。
- **落盘为自由文本**：`SqlLogAutoSaver.kt:354` 按 `| db: <原文>` 写档、读回自由文本回环（格式
  无关）；MCP `HistorySearchService` 把它作为 db 过滤/溯源字段暴露。
- **无任何开关**：角标当前恒显全量内容。

## 二、需求评估：合理

1. **默认信息密度**：条目行元数据段（时间/耗时/目标库/来源…）已经不少；「数据库类型」是绝大多数
   场景够用的最小上下文，`库名@host:port` 属多环境用户才需要的信息——默认少即是多。
2. **粒度对齐产品姿态**：角标本就做了凭据剔除（绝不入 user/password），「默认更克制、详情可选」
   是同一方向的延伸。
3. **实现面小且有单点**：渲染层一段 + 一个设置字段 + 设置页一行 + 双语文案；生产/落盘/检索链
   全部零改动（见方案选型）。

## 三、方案（D135）：显示侧门控（生产与落盘不动）

- `PluginSettings.State` 新增 `dataSourceBadgeDetail: Boolean = false`（**默认 false=仅显示
  数据库类型**；KDoc 注明语义与默认克制口径），声明位置随 SQL 日志组既有字段。
- `formatDatabaseFragment` 加显示门控：`dataSourceBadgeDetail == false` 时取
  `db.substringBefore(" · ")`（角标分隔符唯一产自 `badgeName`，切出即纯方言段）；
  `true` 时原样全量。null 恒 null 不显示（既有语义）。
  - **已知边界（登记即可）**：仅连接事实形态（方言解析失败的罕见串，无 ` · ` 分隔符）在
    OFF 态原样保留事实串——「连方言都辨不出时保留最后上下文」优于显示空白。
- **为什么门控放显示侧而非生产侧**：`databaseName` 落盘/历史读回/MCP 检索过滤都消费全量串，
  生产侧截断会让「历史文件永久丢失连接事实、后开开关也无法回显」；显示侧门控则开关即改即生效
  （含已读回的历史条目），落盘与检索语义零变化。
- 设置页：SQL 日志设置页加一行复选框（文案键 `settings.log.dataSourceBadgeDetail`，如
  「数据源角标显示完整上下文（库名@主机:端口）」+ tip 注明「默认仅显示数据库类型」），双语
  bundle + isModified/apply/reset 同步（`paginationSimCheck` 同款先例形态）。

### 放弃项（登记）

- 生产侧按开关截断（见上，历史不可回显，否）。
- 角标悬浮 tooltip 承载完整上下文（渲染段是纯文本拼接段，非组件，tooltip 需改渲染器结构，
  超出本需求；登记后续可选）。

## 四、实施步骤

- **S1** `PluginSettings.State.dataSourceBadgeDetail`（默认 false）+ KDoc。
- **S2** `formatDatabaseFragment` 显示门控（OFF 切方言段；ON 全量；null 不显示不变）。
- **S3** 设置页复选框行 + 双语 bundle 键 + isModified/apply/reset 四处同步。
- **S4** 守卫测试（`formatDatabaseFragment` 为 internal 顶层函数可直测）：OFF 全量串渲染纯方言段 /
  ON 渲染全量 / 无 ` · ` 串原样 / null → null；**反向验证**：摘 S2 门控 → OFF 态断言必红，
  贴读数后还原。
- **S5** 计划 §八实施记录回填 + CHANGELOG 一行 + 验收文档 3.13 行标注（默认形态变更）。

## 五、验证方案

定向：新守卫测试类（或就近既有渲染器测试文件追加）+ `PluginSettingsPersistenceTest`/
`ProjectPluginSettingsTest`（新增字段兼容）；回归两类全绿。
真机走查（留验收）：默认态角标 `[MySQL]` 形态；设置页打开后 `[MySQL · jpa_demo@localhost:3306]`
形态；历史读回条目随开关即时切换；MCP `db` 过滤不受开关影响。

## 六、待验证前提（实施者先证伪再动手）

- **P1** ` · ` 分隔符唯一产自 `badgeName`（grep 全仓无第二处拼该形态），substringBefore 切分安全。
- **P2** 渲染热路径读设置的代价形态：`PluginSettings.getInstance().state` 字段直读在每 cell 重绘
  的开销可接受（仓内渲染路径已有读设置先例则照抄；若无，用渲染器级 volatile 缓存 + 设置变更
  监听失效——实施时按先例定，勿引入每 cell 监听）。
- **P3** 两页渲染器确实共用 `formatDatabaseFragment`（KDoc 声明如此，实施时以调用点核实），
  门控一处即覆盖日志/历史两页。
- **P4** 历史读回条目 `databaseName` 经自由文本回环后仍是全量串（SqlLogAutoSaver/SqlLogFileReader
  格式无关口径），显示门控对历史条目同样生效。

## 七、实施记录

（2026-10-09 实施回填，工作树 t-badgemode 基线 3535835）

### 待验证前提证伪结论（P1~P4）

- **P1 成立（附一处补充登记）**：`" · "`（U+00B7 单空格包裹）形态唯一产自 `JdbcUrlDialects.kt:74`
  （`"$dialect · $fact"`，唯一出口 `AgentEventAdapter.kt:59` → `databaseName`）；全仓其余 ` · `
  均为无关语义（CrudSqlPreviewFlow 摘要行 / LogToSqlPanel 状态行 / ExplainPlanResultView HTML
  头 / SqlSuppressManagerDialog 双空格形态），`substringBefore(" · ")` 切分安全。补充登记：
  `McpToolsetSupport.kt:283` 经 `mcpHistoryEntry(dataSourceName)` 注入的是 IDE 数据源**显示名**
  （用户自由命名）——若用户把数据源命名为含 ` · ` 的串，OFF 态会切到首段；与计划已登记
  「无分隔符串原样保留」同族（自由文本面），风险可忽略。
- **P2 成立（有直读先例）**：同一函数族 `formatDurationFragment`（SqlLogPanel.kt:2412，直读
  `state.slowQueryThresholdMs`）与 `formatTimestampFragment`（直读 `state.logTimestampFormat`）
  均在每 cell 渲染路径直读设置。照抄直读形态，零缓存零监听。
- **P3 成立**：调用点恰好两处——`SqlLogPanel.kt:2595`（日志页 LogEntryListCellRenderer）与
  `HistorySqlLogPanel.kt:1231`（历史页 HistoryCellRenderer），均走同一 internal 顶层函数；
  门控一处即覆盖两页（`McpToolsetSupport.kt:11` 仅 import 供 KDoc 引用，非调用）。
- **P4 成立**：`SqlLogAutoSaver.kt:354` 写 ` | db: $it`（全量原文）、`SqlLogFileReader.kt:217`
  自由文本读回 `databaseName`——历史读回条目仍是全量串，显示门控对其同样生效
  （守卫用例 `test数据源角标_历史读回条目随开关切换` 锁定）。

### S1~S5 落点

- **S1** `PluginSettings.kt` State 增 `dataSourceBadgeDetail: Boolean = false`（随 O-1
  showDurationInLogList 组声明，KDoc 注明默认克制口径与显示侧门控语义）。
- **S2** `SqlLogPanel.kt formatDatabaseFragment` 门控：OFF 切 `substringBefore(" · ")`、
  ON 全量、null 恒 null；KDoc 记分隔符唯一产源与边界语义。
- **S3** `PluginSettingsPanel.kt` 五处同步（字段声明 / addRow / isModifiedSqlLog / apply /
  reset）+ bundle 双语键 `settings.log.dataSourceBadgeDetail(.tip)`；复选框台账 41→42。
- **S4** `SqlLogPanelRendererUiTest` 增 5 用例（OFF 切方言段 / ON 全量 / 无分隔符原样 /
  null 恒 null / 历史读回随开关切换）；`PluginSettingsPersistenceTest` 增持久化往返用例 +
  zh/en 文案正反断言 + 计数守卫 42。
- **S5** 本节回填 + CHANGELOG（Unreleased/Changed 一行）+ 验收文档 3.13 标注。

### 读数

- **定向首跑（门控在场）**：`SqlLogPanelRendererUiTest` tests=41 failures=0 errors=0 +
  `PluginSettingsPersistenceTest` tests=17 failures=0 errors=0，全绿。
- **反向验证（RED PROBE 摘 S2 门控 → 只复跑渲染类）**：`SqlLogPanelRendererUiTest`
  41 tests completed, **2 failed**——恰好两个 OFF 态判别用例红：
  `test数据源角标_默认关仅渲染方言段`（`expected:<[MySQL[]] > but was:<[MySQL[ · jpa_demo@localhost:3…`）、
  `test数据源角标_历史读回条目随开关切换`（`expected:<[DM（达梦）[]] > but was:<[DM（达梦）[ · localhost:5236]] >`）；
  ON / 无分隔符边界 / null 用例不受摘除影响（判别面正确，守卫非空绿）。
- **还原门控复跑**：两定向类恢复全绿（41/41 + 17/17）。

### 偏差登记

- 任务指令所称「paginationCount 子复选框先例」属 T-CountSimToggle（仅立项 b4fe6f9，未实施，
  本基线不存在该形态）；实际照本树既有 `paginationSimCheck` / `logShowDurationCheck` 同款形态，
  同步点全数落位（声明/addRow/isModified/apply/reset 共五处，指令的「四处」未含字段声明）。
- `ProjectPluginSettingsTest` 零改动：`ProjectPluginSettings.State` 只承载 SQL 生成语义覆盖项，
  UI 显示类开关（耗时显示 / 列表模式 / CRUD 色 / 角标）一贯不进项目级，本字段同口径。
- `SettingsIO` 零改动：导出面是 State 子集（23 键），UI 显示偏好与 language 同类不入导出面，
  「以现值为底合并」语义对新字段天然兼容。
- 真机走查（默认态 `[MySQL]` / 打开后全量 / 历史即时切换 / MCP db 过滤不受影响）留验收，
  与 §五口径一致。
