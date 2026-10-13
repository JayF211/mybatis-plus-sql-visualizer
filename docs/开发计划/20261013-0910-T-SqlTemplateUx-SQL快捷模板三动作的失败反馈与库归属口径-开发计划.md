# T-SqlTemplateUx：SQL 快捷模板三动作的失败反馈与库归属口径（开发计划）

> 仓库：`mybatis-plus-sql-visualizer`（插件仓）
> 日期：2026-10-13
> 触发来源：文档站录制计划 A15（`sql-snippets.gif`）现场 —— 用户实测「SQL 快捷模板只有 EXPLAIN 选中表正常，其他两个功能不正常」，并附 Services 控制台日志与告警气泡截图。
> 状态：**分析已定案（含日志/源码证据）；P1 属录制侧口径、P2/P3 属插件侧改造，待定夺做哪几项。**
> 更新（2026-10-13）：**P2 已落地（f6b4d159）**，但重录实测仍静默——二轮判因定位到
> `refreshResultsComponent` 的宿主门控，**P2 补已修**；详见 §七（含 R3 前提修正）。

---

## 一、现象（用户实测）

选中 `OrderMapper.xml` 第 57 行 `FROM t_order` 里的 `t_order`（编辑器状态行显示 `57:16 (7 chars)`，是**真实 7 字符选区**），从编辑器右键菜单「SQL 快捷模板 ▸」分别点三项，控制台依次出现：

```
08:28:41  mpj_demo> EXPLAIN SELECT * FROM t_order        → [42S02][1146] Table 'mpj_demo.t_order' doesn't exist
08:28:57  mpj_demo> SELECT COUNT(*) FROM t_order         → 同上
08:29:12  mpj_demo> SELECT COUNT(*) FROM t_order         → 同上
08:29:31  mpj_demo> SELECT * FROM t_order LIMIT 10       → 同上
09:08:10  mpj_demo> SELECT COUNT(*) FROM t_order         → 同上
```

告警气泡（用户截图）：

```
⚠ SQL 快捷模板
[2026-10-13 09:08:10:964] [42S02][1146] Table 'mpj_demo.t_order' doesn't exist
[当前库=mpj_demo, 数据源=mpj_demo@localhost, 数据源默认库=mpj_demo]
```

## 二、根因（三层，全部有据）

### R1 三条动作**功能上完全一致**，失败同因：表不在所连库里

- 源码：三条共用同一基类，**唯一差异是模板字符串**——
  - `actions/SelectedTableSqlTemplateActions.kt:22-25` 抽象基类；
  - `:73` `EXPLAIN SELECT * FROM <选中>`；`:81` `SELECT COUNT(*) FROM <选中>`；`:89` `SELECT * FROM <选中> LIMIT <sampleLimit 默认10>`；
  - `:33-50` 三者共用同一 `actionPerformed` → 同一 `SqlExecutor.execute(project, sql, null, true, onError = …)` → 同一告警通知（`NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"`）。
- 库落点：`t_order` 建在 **`mp_demo`**（`mybatis-plus-sql-visualizer-demo/mybatis-plus-demo/src/main/resources/db/schema.sql:4 USE mp_demo;`、`:62 CREATE TABLE t_order`），而控制台绑定的是 **`mpj_demo`**（join-demo 的库：`user / user_2026 / user_address / area / car_device_info / station_info`）。**同一份日志里就有对照**：07:42:44 与 07:44:39 两条 `SELECT … FROM car_device_info …`（`mpj_demo` 里存在的表）**成功返回 2 rows** —— 说明控制台与库连接本身是好的，只有 `t_order` 这条打在空处。

### R2 「只有 EXPLAIN 正常」是**画面动静**的错觉，不是功能差异

沙盒日志把三条的链路摊平了（`build/MPSV-GifFresh/…/log/idea.log`）：

- 08:28:41（EXPLAIN）走的是**新建/接管控制台**那条：
  `runners=1` → `single runner, run directly` → `session created: MessageBusSession` → `attachConsole -> JdbcConsole{title=mpsql-exec.sql}` → `console db-context: 库上下文对齐：? → mpj_demo` → `native console header built` → `embed component=…buildConsoleSplitPane…` → `executeOnConsole begin (build=M36.8-exec-silent-noop)` → `executing model: … vfPsiText tail=...EXPLAIN SELECT * FROM t_order` → 提交 → `extracted console error: …1146` → `early console error detected (attempts=1)`
- 08:29:31 / 09:08:10（抽样 / COUNT）走的是**复用现成控制台**那条：
  `shared console reused (ds=mpj_demo@localhost, execute=true, liveResult=true)` → `executeOnConsole begin` → 提交 → **同样** `extracted console error` + `early console error detected`

即：EXPLAIN 那次顺手把控制台建起来并嵌进面板（**屏幕上出现了可见变化**），后两次复用既有控制台、只剩一个气泡——**「看起来只有 EXPLAIN 有反应」由此而来**。三条动作的 SQL 生成、提交、错误提取全部一致。

> 附带勘正：日志里的 `(build=M36.8-exec-silent-noop)` 是 `execute/DataSourceSelector.kt:63` 的**构建标记常量** `BUILD_STAMP`，不是「静默 no-op 模式」，别被字面误导。

### R3 真正的落差在**失败反馈的丰富度**（这条值得改）

同一次执行失败，两条路径给出**不同档次的反馈**：

| 路径 | 失败后的界面反馈 | 证据 |
|---|---|---|
| 预览面板「执行 SQL」 | 面板内**富错误行**：`定位到出错语句` / `复制错误信息` 两个动作 | 文案 `MpSqlVisualizerBundle_zh.properties:262 execute.error.locate`、`:121 panel.copyError`；渲染方 `ui/SqlPreviewPanelExecution.kt` |
| 快捷模板三动作 | **只有一条警告气泡**（纯文本，无定位/复制动作） | `actions/SelectedTableSqlTemplateActions.kt:39-46`（onError 里 `.createNotification(title, errorMsg, WARNING).notify(project)`） |

两者底层是同一条 `SqlExecutor → DataSourceSelector` 执行链（前者多一层面板态上下文），所以**能力已经有了，只是没接上**——这是 R3 的性质：不是缺功能，是同一能力在两条入口上不一致。

> **R3 前提修正（2026-10-13 二轮实测，见 §七）**：本表「快捷模板三动作只有一条警告气泡」只对
> **08:28–09:08 那一轮**成立（当时 P2 尚未提交，走 `onError` 纯文本通知）。晚一轮重录（插件 master、
> 已含 P2）里，同一类失败**连气泡都没有**——真因是晚到的控制台错误被宿主门控挡在门外，见 §7.3。

### R4（可选，可发现性）动作不校验、也不提示「库归属」

`SelectedTableSqlTemplateActions.kt:52-56` 只取选区文本，**不做标识符/库归属校验**（`docs/memory-archive/bug-fixes/reference-sql-quick-templates-actions.md:14` 亦记「选中文本不做标识符校验」）。这是有意口径（「选中即表名」），但结果是：用户要在**气泡正文里**才读到 `[当前库=mpj_demo, 数据源默认库=mpj_demo]`，才知道「表不在这个库」。

## 三、计划项

### P1（录制侧，必做，零代码）：A15 的素材口径对齐

- 片子只演示「选中表名 → 生成语句」即可（`sql-snippets.gif` 的口径本就是「生成语句」，**不要求执行出结果**）；若要顺带演示执行成功，**选中 `mpj_demo` 里存在的表**（`car_device_info` / `station_info` / `user` / `area`），或把控制台绑定切到 `mp_demo`。
- 动作只把选区文本当表名，所以换成任意存在的表都不影响演示语义。
- 落地：更新 `docs/开发计划/20261008-1106-…-开发计划.md` 里 A15 的口径（把 `t_order` 换成 `car_device_info`），成片后再回填实施记录。

### P2（插件侧，建议做）：三动作的失败反馈与预览面板拉平

- 目标：快捷模板执行失败时，除气泡外**给出与预览面板同款的富错误入口**（至少「定位到出错语句」「复制错误信息」两个动作；若弹出富错误卡片成本高，可先让气泡带上 runnable 动作）。
- 做法方向（实现时二选一，实施前先证可行性）：
  1. 复用 `ui/SqlPreviewPanelExecution.kt` 里既有的错误行渲染/动作构造，把「构造错误反馈」抽成可被 `actions/` 复用的入口；
  2. 或在 `SelectedTableSqlTemplateActions.kt:39-46` 的 `onError` 上给通知挂 `NotificationAction`（定位/复制），不新建面板。
- 判据：同一句失败 SQL，从**预览面板**与从**快捷模板动作**两条入口触发，用户拿到的可操作项一致（都能一键定位到出错语句、一键复制错误信息）。
- 不做：不改动作的语义与启用条件（选区判定仍只认「编辑器非空选区」）。

### P3（插件侧，可选）：库归属预检

- 目标：选中表在**当前绑定库**里不存在时，在错误之外给一条前置提示（例如气泡标题下加一行「选中的表不在当前绑定库（mpj_demo），可切换数据源或确认表名」）。
- **待验证前提**：绑定库的表清单是否可在动作触发时低成本取得（数据源元数据/缓存；若必须联网查询则**不做**——动作要保持轻量）。
- 若 P3 的前提不成立，则只做 R4 的文档口径（把「表名不做校验、执行失败会披露当前库」写进功能页），不改代码。

## 四、实施与验证

1. **P1 先做**（无代码，直接改录制口径并重拍 A15）。
2. P2/P3 若做：改动面仅 `actions/` 与（可能的）`ui/` 错误反馈抽取，**不动执行链**（`SqlExecutor` / `DataSourceSelector` 零改动）；定向测试：动作 `update()` 的启用判据（现无专属用例时可补 `--tests` 定向类）+ 通知动作构造的纯逻辑（若可抽出）。
3. **真机 A/B**（唯一权威）：同一句一定会失败的 SQL（如 `SELECT COUNT(*) FROM t_order`，绑定 `mpj_demo`），分别从预览面板与快捷模板动作触发，**逐项对比用户可见的可操作项**；再换 `car_device_info` 跑一次确认成功路径不受影响。
4. 反向验证：把新增的「富错误入口」短路 → 动作路径必须退回「只有气泡」（即改动确实在起作用）。

## 五、待验证前提（实施前先证伪）

1. **P2 的两条做法哪条可行**：`SqlPreviewPanelExecution.kt` 的错误行是否与面板强绑定（抽不出来的话就走通知 `NotificationAction`）。
2. **P3 的元数据可得性**：动作触发时能否零成本拿到绑定库的表清单（拿不到就不做 P3）。
3. **「只有 EXPLAIN 正常」是否还有我看不到的第四种形态**：本轮证据只有日志 + 一张截图；若用户实测**同一张表**（存在的那种）下三条动作表现仍不同，则本文件的 R2 结论要被推翻，需按新现场重开分析。

## 六、影响面与不做的事

- 影响面：P1 只改录制口径与计划文案；P2/P3 只碰 `actions/`（与可能的 `ui/` 错误反馈抽取），**不改执行链、不改动作启用条件、不改表名不校验的口径**。
- 明确不做：不把「选中文本」升级成「PSI 解析出的表名」（现在这条口径轻量、不依赖 SQL 注入是否让位，是本动作能在 mapper XML / 预览编辑器里都工作的原因）；不为 `t_order` 在 demo 侧改 schema（那是 `mp_demo` 的资产，与本次问题无关）。

---

## 七、实施记录与前提修正（2026-10-13 二轮：P2 落地后仍静默）

### 7.1 P2 已落地（f6b4d159，09:37）

按 §三 P2「做法②」实施：`actions/SelectedTableSqlTemplateActions.kt` 的失败通知已挂「定位到出错语句」
「复制错误信息」两个 `NotificationAction`（文案复用面板 key `execute.error.locate` / `panel.copyError`），
测试 `actions/SqlTemplateErrorNotificationTest.kt` 5 例。改动面仅 `actions/`，执行链零改动。

### 7.2 前提修正：实测是「连气泡都没有」，不是「只有纯文本气泡」

§二 R3 表格那行**与实测不符，以实测为准**：2026-10-13 晚 A15 重录（沙盒 `build/MPSV-GifFresh`，
插件 master）里，动作执行 `SELECT COUNT(*) FROM 对齐插件测试` 失败（DBN 正确报 `[42S02][1146]`），
**全程没有任何通知气泡/弹窗**。R3 的气泡截图来自 08:28–09:08 更早一轮（那时 P2 尚未提交，走
`onError` 纯文本通知），两者不是同一次现场。

### 7.3 二轮判因：晚到的控制台错误无人检测（`refreshResultsComponent` 被宿主门控整体早退）

调用链（`文件:行`，均为改前状态）：

1. 动作 → `SqlExecutor.execute(project, sql, null, true, onError, onErrorRich)`
   （`actions/SelectedTableSqlTemplateActions.kt:63-69`）——**onError/onErrorRich 确实传了**，
   P2 的 `notifySqlTemplateError` 接线也正确。
2. `onConsole = null` ⇒ `pendingOnConsole = null`（`execute/DataSourceSelectorConsole.kt:377`、
   `execute/DataSourceSelectorResults.kt:459`）。
3. 失败后 DBN 仍会建结果页签；`startResultTabRetry` 的每拍早检（`DataSourceSelectorResults.kt:49-64`）
   只在错误**已落 history** 时命中——连接建立慢时错误晚到（真机 17:56:49 提交 → 17:56:52 报错，约 2.1s），
   前几拍扫不到。
4. 结果页签一出现即走 `result != null` 分支（`:79-101`）停表，并调用
   `refreshResultsComponent(...)`（`:101`）兜底晚到错误。
5. **断点**：`refreshResultsComponent` 首行 `val callback = pendingOnConsole ?: return`
   （改前 `DataSourceSelectorResults.kt:955`）——无宿主路径直接早退，晚检 Timer 从未启动。
6. ⇒ `deliverError` 从未被调用（`DataSourceSelectorResults.kt:1761`）⇒ onError/onErrorRich 都没回调
   ⇒ 无任何提示。

日志实证（沙盒 `log/idea.log`）：08:28/08:29 那五条 `t_order` 失败都带
`[M36] early console error detected (attempts=1)`（错误恰在首拍前落 history ⇒ 命中 ⇒ 有气泡）；
17:56/18:38/19:13/19:31 四条 `对齐插件测试` 失败**只有 `[M36] executing model:` + 平台
`SQLErrorInfo` WARN，全无早检命中**（错误晚到 ⇒ 结果页签先出现 ⇒ 走 `refreshResultsComponent`
⇒ 被 `pendingOnConsole ?: return` 挡掉）。

### 7.4 修复（P2 补）

`execute/DataSourceSelectorResults.kt`：把 `refreshResultsComponent` 拆两段——**错误晚检恒跑**
（与宿主无关），**组件换入**用新纯判据 `refreshSwapEnabled(hasEmbedHost, hasTopBlock)` 收窄
（无宿主不摘 DBN 组件，A49 口径不回退）。改动面仅 `execute/`；不动 `SqlExecutor` /
`DataSourceSelector` 提交链，不改动作语义与启用条件。

### 7.5 验证

- 定向：`./gradlew test --tests "cn.song.mybatisplus.visualizer.execute.RefreshLateErrorWatchTest"`
  → 4 例全绿（3 例纯判据 + 1 例接线守卫）。
- 反向：把 `refreshResultsComponent` 首行改回 `pendingOnConsole ?: return` → 接线守卫
  `refreshResultsComponent_不因无宿主整体早退` 必红（读数：4 例 1 失败，失败即该守卫）。
- 未做真机 A/B（本轮约束不跑 runIde）。**待验证前提**：`refreshResultsComponent` 的 8 拍（4.8s）
  窗口是否覆盖所有「错误晚于结果页签」的真机时延——若真机仍见静默，先看该窗口是否够长。
