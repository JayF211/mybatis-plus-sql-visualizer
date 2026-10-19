# T-RollbackSql：预览窗口【回滚SQL】按钮（dbx 精华融合）开发计划

> **创建日期**：2026-09-10
> **分支**：master（HEAD `8afe07a`；工作树仅 T-PerfBatch 计划文档未跟踪，无在途代码改动）
> **优先级**：P2（新需求，非缺陷；执行链路近年经历 T-DsCover / T-ExecCopyFix / T-RefreshDbSteal 三轮治理，本计划必须骑在既有卫队之上，不得绕行）
> **预估工时**：P1 一期 6~8h（文本推导回滚 + 回滚视图 + 执行复用）+ P2 二期 6~8h（执行前快照 + 留痕），分两期提交
> **来源**：用户新需求（2026-09-10）——预览窗口新增【回滚SQL】按钮，吸收开源项目 dbx（t8y2/dbx，Rust/Tauri 桌面级多数据库工具，调研基线 commit `dbaa640`）的 SQL 回滚精华

---

## 一、调研结论：dbx 回滚机制与本插件场景映射

### 1.1 dbx 回滚双引擎（全部源码实证，无 binlog 路线）

dbx 的回滚不走 MySQL binlog 解析（全库 grep 无命中，flashback 仅作为 Oracle 只读禁止词出现），也不做 SQL 文本机械反转，而是两条真实生产线：

**引擎 A：DML 回滚 = 执行前 before-image 快照反推**（`crates/dbx-core/src/data_grid_sql.rs:1581 build_data_grid_rollback_statements`）。用户在数据网格里改/删/增行时，内存里始终持有从未修改的原始行快照，保存时用它反推反向 SQL：

| 正向 | 反向 | 定位方式 |
|---|---|---|
| INSERT（新行） | DELETE | 主键等值；自增/生成列主键值未知时**宁可放弃不生成**（:1861 注释原话"Generated or trigger-populated keys are unknown until after INSERT"） |
| DELETE（删行） | INSERT | 原始行快照全列回填；MySQL 有主键时合批（500 行 / 256KB 上限，:41-42） |
| UPDATE（改列） | UPDATE | SET 旧值，WHERE = 主键 **AND 各被改列=新值**（乐观锁谓词，只有库内状态仍等于写入后状态才回滚得动，防覆盖他人后续修改） |

无主键兜底：整行谓词（NULL→IS NULL、MySQL 文本列 BINARY、JSON 列 CAST），配套 keyless guard——保存前服务端 `SELECT COUNT(*)` 校验谓词只匹配 ≤1 行，否则拒绝（:325-336）。ClickHouse 直接不生成回滚（:1595）。

**引擎 B：DDL 回滚 = 结构化 diff 求逆**（`schema_diff.rs:1259 RollbackGraph`）。把正向 diff 的结构化数据逐字段求逆（added↔removed、source↔target 全量互换），喂给与正向完全相同的 SQL 生成器——保证正反方言一致；外加 `rollback∘rollback == forward` 一致性自检与 Complete/Incomplete 评级，前端对 incomplete 回滚**禁止执行**。

**贯穿两引擎的安全设计**（这是 dbx 最值得吸收的部分，比任何一段代码都值钱）：

1. 有 before-image 才生成回滚——只在"用户亲眼见过原始数据"的场景生成，不靠 binlog 补课或文本反转赌博；
2. 预览优先不自动执行，执行前 window.confirm + 生产库二次确认；
3. 执行即留痕——回滚 SQL 入历史库，执行回滚再入历史（`operation="ROLLBACK"`、`rollback_of` 指向原记录），形成可审计链条；
4. 完整性评级 + 评级不足阻断执行；
5. 上限防呆（合批 500 行/256KB、无主键 COUNT 校验）。

### 1.2 本插件场景映射与能力矩阵

关键差异：dbx 的 before-image 来自数据网格的内存结果集；本插件预览窗口里的 SQL 来自应用日志捕获（`LogSqlEntry.restoredSql`），**应用已经执行过它，插件没有 before-image**。诚实的能力矩阵：

| 预览 SQL 类型 | 无快照（P1，日志已执行） | 有快照（P2，插件执行按钮造出 before-image） |
|---|---|---|
| INSERT（主键值显式在 SQL 中） | DELETE 按全列出列等值谓词 → 可生成 | 同左，且可从元数据优先主键定位 |
| INSERT 多行 VALUES | 批量 DELETE（500/256KB 上限） | 同左 |
| INSERT（主键列未出现在 SQL，自增主键） | 整行谓词兜底，评级降级 + COUNT 自查模板 | 主键从元数据补全 |
| UPDATE | SET 新值已知但**原值不可考** → 无法自动生成，给恢复指引 | 反向 UPDATE（SET 旧值 + 乐观锁 WHERE）→ 完整可执行 |
| DELETE | 被删行数据不可考 → 无法自动生成，给恢复指引 | INSERT 全列回填 → 完整可执行 |
| MERGE / DDL | 不支持，说明原因 | 不支持（DDL diff 求逆依赖 schema diff 管线，插件无此基建，超出本期） |

结论：P1 覆盖 INSERT 类（可推导）+ UPDATE/DELETE 的诚实拒绝与指引；P2 在插件自己的执行按钮链路上造 before-image（执行 UPDATE/DELETE 前先 SELECT 快照受影响行），把 dbx 的核心哲学"有快照才回滚"完整落地——这是本计划真正的精华吸收点，dbx 对编辑器里手写的任意 DML 同样无解，我们不假装能解。

---

## 二、方案设计

### 2.1 F1：RollbackSqlGenerator 核心生成器（P1）

新文件 `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/rollback/RollbackSqlGenerator.kt`，配套 `RollbackResult(rating, statements, warnings, countCheckSql)`。

- **解析**：JSqlParser 5.3（`build.gradle.kts:54` 已是唯一运行时非平台依赖，先例 `sql/SqlPerfAnalyzer.kt`）。INSERT → `InsertStatement` 取表名/列清单/VALUES；UPDATE/DELETE → 取表名/WHERE/SET 列，供评级与指引模板使用。
- **多语句**：先 `SqlTypeClassifier.splitStatements`（SqlTypeClassifier.kt:73）切分逐条处理；语句类型判定复用 `SqlTypeClassifier.getFirstKeyword/classify`（:46-65），不另造轮子。
- **INSERT→DELETE 生成**：列出列全等值 AND 谓词；NULL→`IS NULL`；字符串转义；多行 VALUES 合批 DELETE，上限对齐 dbx（500 语句 / 256KB，超限截断并记 warning）。
- **输入净化**：取文本先过 `SqlCommentStripper`（`copySql` 先例 SqlPreviewPanel.kt:1183）。
- **UPDATE/DELETE 路径**：不生成可执行回滚；产出恢复指引（binlog/备份恢复提示）+ 可选 `SELECT * FROM t WHERE 原WHERE` 自查模板（对「尚未执行、打算手动跑」的用户有真实价值）。
- **主键偏好留缝**：生成器接口接收可选 `primaryKeyColumns: List<String>` 参数（P2 快照场景从元数据传入后优先主键定位），P1 调用方传 null 走整行谓词。

### 2.2 F2：完整性评级（dbx RollbackCompleteness 的三档化）

`Complete`（可直接执行）/ `Partial`（可执行但带风险警示，如整行谓词可能误匹配多行）/ `Unable`（不可自动回滚）。dbx 是两档（Complete/Incomplete），我们加中间档的原因：本插件存在"可执行但需先自查 COUNT"的诚实中间态。Unable 不进回滚视图，点按钮直接 `Messages.showInfoDialog` 说明原因与人工恢复路径——不让用户面对一个只有警告横幅的空视图。

### 2.3 F3：回滚视图 UI（P1）

- **入口**：`SqlPreviewPanel.toolbar()`（SqlPreviewPanel.kt:381-407）在「分享」之前加 `add(smallButton(MpSqlVisualizerBundle.message("panel.rollbackSql")) { showRollbackSql() })`。按钮为纯文本 JButton，沿用面板惯例。预览面板唯一宿主是 SqlPreviewDialog（无工具窗内嵌形态），改一处全场景生效。
- **展示**：新建 `RollbackSqlDialog`（MODELESS DialogWrapper，仿 SqlPreviewDialog 惯例）：顶部评级横幅（Complete 绿 / Partial 橙警示语），主体只读编辑器渲染回滚 SQL，底部动作「复制」「执行回滚」「关闭」。
- **刻意不做的设计**：不把回滚 SQL 写进预览面板 native 文档、不切换面板显示模式——`showSql` 三分支管线刚在 T-RefreshDbSteal 修完所有权卫队，回滚视图走独立弹窗零侵入该管线，不参与共享控制台的 rehost 战场。
- **i18n**：`panel.rollbackSql` 及横幅/按钮/失败文案键，两份 bundle（`messages/MpSqlVisualizerBundle.properties` panel.executeSql :79 附近 + `_zh` :84 附近）。文案固化在按钮构建时的行为与现有按钮一致，无需额外刷新逻辑。

### 2.4 F4：执行回滚链路复用（P1，与既有卫队咬合）

「执行回滚」**不得**从 RollbackSqlDialog 直接走 `SqlExecutor.execute` 全链——全链会建临时 .sql 并 attachConsole 到共享控制台槽位，可能把正嵌在预览面板里的控制台偷走（T-RefreshDbSteal 6 连抢的复刻风险）。正确路径：回滚弹窗持预览面板引用，新增 `SqlPreviewPanel.executeAdhocSql(sql: String)`——复用 `executeSql()` 的既有管线（SqlPreviewPanel.kt:418-496）：`SqlTypeClassifier.isQuery` 分类 → DML 确认框（:431-441 已有，正好承担 dbx 的 confirm 语义）→ `isPrepared` 快路径或全链，T-RefreshDbSteal F4 的所有权卫队对这条路径天然生效。执行完毕结果反馈走既有 `resultPanel`。

### 2.5 F5：执行前快照 before-image（P2，真精华落地）

- **触发点**：`executeSql()` 分类为 DML 且关键字为 UPDATE/DELETE 时（:432 拿到分类后），执行前先对同一数据源跑 `SELECT * FROM t WHERE 原WHERE`，行上限 200，超限标记 truncated（评级降 Partial 并拒绝整表级回滚）。
- **取回结果的路线抉择（实施前 javap 定夺）**：a) 反射 DBN 结果 API 程序化读行；b) 反射 `LocalDataSource` 拿 JDBC 连接自跑快照 SELECT。**倾向 b**：一石二鸟——`DatabaseMetaData.getPrimaryKeys` 顺便补全 F1 的主键定位，快照行直接拿 `ResultSet`，完全绕开 DBN 控制台（零 rehost 风险）。`LocalDataSource` 在非导出包，须 `Class.forName` + 平台 classloader（先例 `ui/highlight/DatabaseSchemaResolver.kt:36`、`ExecuteSqlSupport.kt:22`）；插件运行时零依赖铁律不动摇，JDBC 驱动由 IDE 数据源自带。
- **快照存储**：project 级内存服务 `RollbackSnapshotStore`（keyed by 执行序号/时间戳），LogSqlEntry 是 data class 不动字段，快照走旁路。会话内存即可，本期不引入持久化。
- **快照回滚生成**：DELETE→INSERT（列名取 ResultSetMetaData，多行合批 500/256KB）；UPDATE→反向 UPDATE（SET 旧值，WHERE 主键+新值乐观锁谓词，dbx 原样吸收）。
- **统一入口**：回滚视图取结果时优先查快照（Complete 级），无快照回退 F1 文本推导——用户无感知分层。

### 2.6 F6：留痕（P2，最小化）

快照 store 记录 `parentExecutionId`（dbx rollback_of 指针语义的对应物）；执行回滚后回滚弹窗横幅置「已执行」状态 + `LOG.info` 埋点。dbx 的 SQLite 历史库不在本期引入。

### 2.7 边界（明确不做）

- **binlog 回滚**：dbx 也没做；JVM 侧方案（mysql-binlog-connector-java + ROW 格式 binlog + REPLICATION 权限）工程量大且权限门槛高，如需覆盖"应用已执行的任意 UPDATE/DELETE 事后恢复"场景，另立项。
- **DDL 回滚**：dbx 的 RollbackGraph 依赖完整 schema diff 管线（列/索引/FK/触发器 diff + 拓扑排序 + 重命名检测），插件无此基建，抽不动。
- **Oracle Flashback**：dbx 仓库中不存在此能力。

---

## 三、实施顺序

**P1 一期（独立提交）**：

1. F1 生成器 + F2 评级（纯逻辑，先行，单测覆盖）；
2. F3 回滚视图 UI + i18n（依赖 F1 的 RollbackResult）；
3. F4 executeAdhocSql + 回滚弹窗执行按钮接线；
4. 全量回归 + 真机走查（runIde 留人工）。

**P2 二期（独立提交，依赖 P1 落地后评估）**：

5. F5 前置：LocalDataSource 反射路线 javap 核实（不可达则快照降级为「快照 SQL 模板 + 用户手动执行后粘贴结果」的半自动方案）；
6. F5 快照捕获 + 快照回滚生成 + F6 留痕；
7. testcontainers 往返验证 + 回归。

---

## 四、测试与验收

### 4.1 单测（先测后改）

- `RollbackSqlGeneratorTest`：单行/多行 INSERT→批量 DELETE（含 500/256KB 上限截断）、全列出列谓词、NULL→IS NULL、字符串转义（单引号/反斜杠）、注释剥离、MERGE/DDL→Unable、UPDATE/DELETE→指引模板、多语句切分、主键偏好参数生效。
- 评级逻辑：整行谓词→Partial 带 COUNT 自查模板；主键谓词→Complete。
- UI 测试仿 `SqlPreviewPanelRendererUiTest` 惯例：Unable 时不弹视图只出消息框；回滚弹窗按钮委托正确。
- `executeAdhocSql`：仿 T-RefreshDbSteal 面板级测试法——未嵌控制台时走卫队 recover 分支，断言不写他人文档。

### 4.2 集成验证（testcontainers 已在 testImplementation，mysql/pg 现成）

往返法（借 dbx `rollback∘rollback == forward` 思路变体）：建表 → 执行正向 INSERT/UPDATE/DELETE → 生成回滚 → 执行回滚 → 断言表数据归零或还原。P1 场景验 INSERT 路径，P2 场景验快照 UPDATE/DELETE 路径。

### 4.3 验收标准

- 预览 INSERT 语句点【回滚SQL】→ 产出可执行 DELETE，评级 Complete；
- 预览 UPDATE/DELETE（无快照）→ 诚实告知不可自动回滚并给指引，不产出危险半成品；
- 经执行按钮执行的 UPDATE/DELETE（P2）→ 回滚视图给出完整反向 SQL，执行后数据还原；
- 全量回归绿（当前基线约 3188 tests），verifyPlugin 三版本 Compatible（发版门禁例行）。

---

## 五、API 权威核实清单与风险

| # | 疑点 | 核实方式 |
|---|---|---|
| 1 | JSqlParser 5.3 的 InsertStatement/Values/UpdateSet API（5.x 对 4.x 有破坏性变化） | 本地 jar javap，先例 SqlPerfAnalyzer.kt 用法对照 |
| 2 | `LocalDataSource` 反射可达方法（getUrl / 连接获取） | javap DBN jar（`com.intellij.database`），262/243 双平台 |
| 3 | `SqlExecutor.execute` 参数签名与全链副作用 | 反编译现有 SqlExecutor.kt 即源码可见，实施时通读 |
| 4 | DML 确认框 `execute.confirm.dml` 键复用还是新增回滚专用文案 | bundle 现状核对，倾向复用+新增回滚语义键 |

风险：① 快照 SELECT 与正向 DML 之间有时间窗（他人在此窗改动数据），乐观锁 WHERE 已兜底，横幅须向用户言明；② 整行谓词在无主键大宽表上误匹配风险，Partial 评级 + COUNT 自查模板是 P1 的诚实交代，真正的服务端 COUNT 校验（dbx keyless guard）随 P2 快照链路补上；③ 跑 gradlew 前例行 check 并行任务登记表。

---

## 附录：dbx 调研要点存档

- 仓库本地克隆：`C:\Users\USER\AppData\Local\Temp\dbx`（main @ `dbaa640`，v0.6.9，Rust + Tauri 2 + Vue 3.5）。
- 核心文件：`crates/dbx-core/src/data_grid_sql.rs`（DML 反推，单文件 7000+ 行）、`schema_diff.rs`（RollbackGraph :1259）、前端 `apps/desktop/src/components/QueryHistory.vue`（回滚入口 :359）与 `useDataGridEditor.ts`（保存编排 :1803）。
- dbx 实现回滚零第三方回滚库依赖，SQL 解析用 `sqlparser 0.62` 仅做语句切分，回滚生成本身是字符串模板拼装——对 JVM 侧移植反而友好（JSqlParser 同样只做解析，生成走自家模板）。
- dbx 的联合纠偏计划（`correction.rs:354`）里 data compare 产生的 DML 回滚只是注释占位——再次印证其哲学：没见过原始数据就不给可执行回滚。

---

## 六、实施记录（2026-09-12 交付）

> 分支 `feature/r2-competition-response`（worktree `mpsv-r2`），P1/P2 两期合并一次交付（用户定案：verifyPlugin 三目标放最后一次）。

### 6.1 交付清单（F1~F6 全数落地）

| 功能 | 落位 | 说明 |
|---|---|---|
| F1 生成器 | `sql/rollback/RollbackSqlGenerator.kt` | INSERT→DELETE 全列等值谓词（NULL→IS NULL）；UPDATE/DELETE 诚实拒绝 + 恢复指引 + 自查/快照共用模板；`primaryKeyColumns` 可选参数；上限防呆 500 语句/256KB；`parseSnapshotTsv` + `generateFromSnapshotRows`（P2） |
| F2 评级 | `RollbackResult(rating, statements, warnings, countCheckSql, …)` | 三档 Complete/Partial/Unable；warnings 为本地化键结构（PerfWarning 同款），UI 层渲染 |
| F3 回滚视图 | `ui/RollbackSqlDialog.kt` + 面板【回滚SQL】按钮 | MODELESS DialogWrapper；评级横幅（绿/橙/红）+ 只读 SQL 编辑器（随弹窗 disposable 释放）+ 底部「执行回滚/复制/关闭」 |
| F4 执行复用 | `SqlPreviewPanel.executeAdhocSql(sql, dmlConfirmed, rollbackOfEntry)` | 复用 executeSql 管线（卫队天然生效）；DML 二次确认内置 |
| F5 快照 | `sql/rollback/RollbackSnapshotStore.kt`（project light service） | **降级半自动方案**（见 6.2）；快照登记/指纹查找/粘贴回填三模式（REVERT_INSERT/BACKFILL_INSERT/REVERT_UPDATE） |
| F6 留痕 | store `markRollbackExecuted` + `parentExecutionId` | 执行回滚后条目置 ROLLBACK_EXECUTED + 横幅「已执行」+ LOG.info 埋点；回滚 DML 自身也登记，parent 指向被回滚的原执行 |

i18n：`panel.rollbackSql` + `rollback.*` 34 键，en/zh 双 bundle 同步。

### 6.2 关键定夺（javap/测试实证）

1. **F5 路线定夺：程序化 JDBC 快照不可达，走计划预设降级**。262 发行包实证：`AbstractDataSource` 无任何连接方法；连接建立收敛到 `DatabaseConnectionManager.build(project, point).createBlocking()` → `GuardedRef<DatabaseConnection>` → `DatabaseConnectionCore.getRemoteConnection()`，而其返回类型 `com.intellij.database.remote.jdbc.RemoteConnection` **不在任何本地发行 jar**（remote 驱动运行时按需加载）——反射桥最后一跳无可用 API，依「生成代码严禁调用不存在的 API」铁律放弃路线 b，落计划 §三.5 预设的「快照 SQL 模板 + 用户粘贴结果」半自动方案：回滚弹窗内【执行快照查询】经 executeAdhocSql 跑 `SELECT * FROM t WHERE 原WHERE`（结果落面板控制台网格，用户亲眼取得 before-image）→ 复制网格（TSV）→ 粘贴 + 可选主键标记 → 生成完整回滚。dbx「有 before-image 才回滚」哲学完整保留。
2. **Unable 也可进视图（对 §2.2 的演进）**：可解析的 UPDATE/DELETE（Unable 但有快照工作流）打开弹窗（红色横幅 + 指引 + 快照区）；仅完全不可推导（解析失败/MERGE/DDL/SELECT）维持「点按钮只出消息框」。理由：降级方案下粘贴回填是 UPDATE/DELETE 的唯一回滚入口，堵在消息框外工作流无处安放；且视图非空、不存在「只有警告横幅的空视图」。
3. **评级口径**：按 §4.1 细则执行——整行谓词 → Partial + COUNT 自查模板；主键定位/快照回填带主键 → Complete。§4.3「预览 INSERT 评级 Complete」的达成路径为主键定位（P2 快照）场景，P1 无主键元数据时整行谓词恒 Partial（诚实口径优先）。
4. **多语句混合任一 Unable → 整体 Unable**：部分回滚比不回滚更危险。

### 6.3 JSqlParser 5.3 实证坑（全数 javap/单测双验，勿回退）

- `getSetUpdateSets()/getDuplicateUpdateSets()` 运行时可空（Kotlin 平台类型判空内建硬抛 `must not be null`）——一律先 `val x: List<UpdateSet>? =` 收窄。
- `Values extends Select` 且 `Insert.getValues()` 实现是 `(Values) getSelect()` 强转——对 INSERT…SELECT 直调 CCE；判别一律走 `getSelect() as? Values`。
- 单行 INSERT 的 `Values.expressions` 为**扁平** ExpressionList，多行为**嵌套**（每元素一 ExpressionList 行）——按首元素类型归一。
- `StringValue.getValue()` 保留原始转义形态（`O''Brien`），渲染只补外引号、再转义即四引号。
- `DateValue.toString` 渲染 `{d '…'}` JDBC 转义（MySQL 不接受）——按 java.sql 值格式化为带引号字面量。

### 6.4 测试与验证

- `RollbackSqlGeneratorTest` 35 用例（§4.1 清单全数 + P2 回填/TSV/乐观锁）；`RollbackSnapshotStoreTest` 7 用例（登记/指纹/覆盖/LRU/留痕终态）；`RollbackSqlPanelTest` 9 用例（工具栏按钮/快照登记/查询跳过/留痕链/全链降级/卫队不抢他面控制台）；`RollbackSqlDialogTest` 7 用例（横幅分层/粘贴生成/解析失败拒绝/DELETE 回填/已执行横幅/Partial 自查）；`RollbackRoundTripTest` 5 用例（H2 MySQL 模式往返：INSERT→DELETE 清空、DELETE→INSERT 还原含 NULL、UPDATE→反向还原 + 乐观锁二次执行 no-op）。
- 全量回归 **303 类 / 3878 用例 / 0 失败 / 0 错误 / 6 跳过** 一次通过（基线 298/3815/0/6 + 本任务 5 套件 63 用例，零破坏）；verifyPlugin 三目标（243/253/262）Compatible。
- runIde 真机走查（按钮点击/粘贴工作流/深色主题横幅）留人工。
- 并行沙盒：登记 t-rollbacksql / MPSV-Rollback（`-ProllbackDev=true`，与 gifRecord 同款机制临时新增，交付前已还原）。
