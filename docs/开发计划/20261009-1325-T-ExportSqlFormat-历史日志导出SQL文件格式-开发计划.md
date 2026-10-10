# T-ExportSqlFormat：历史/日志导出增加 SQL 文件格式（可执行脚本导出）

> **来源**：用户 2026-10-09 需求「历史与日志导出怎么没有导出 sql 文件的」——现状导出为
> CSV/TSV/TXT 行集形态，无可执行脚本形态。经分析合理（见 §二），立项。
> **生成日**：2026-10-09；写完停下等确认，未动手。
> **占号**：O-131/R77/D131/V154（引用前实测：O 最高 O-130 / R 最高 R76 / D 最高 D130 /
> V 最高 V153，均已消耗）。
> **分析来源**：子代理现状盘点报告（20261009），主控审定。

## 一、现状（子代理盘点，file:line 实证）

导出三入口全部汇聚 `SqlExportService`（写盘 `File.writeText` UTF-8）：

| 入口 | 动作 | 行集 | 格式 |
|------|------|------|------|
| 历史面板右键「导出 SQL」 | `HistorySqlLogPanel.ExportSqlAction`（:808） | 选中集，未选中=当前可见集 | chooseExportFormat(:870) 三选 CSV/TSV/TXT |
| 日志面板右键「导出」 | `SqlLogPanel.ExportVisibleAction`（:1820） | `lastRendered` 可见集快照 | 同款三选(:1876) |
| 预览面板「导出可移植 SQL」 | `SqlPreviewPanel.exportPortableSql`（:1130） | 单语句 CRUD+回滚链（方言标注） | 三格式（O-91 产物，**不在本需求范围**） |

关键现成件：导出的 SQL 已是 **`restoredSql`（参数回填后可执行）**（LogSqlRestorer.kt:26-40，
参数缺失保留 `?`）；分号终止符现成（`withTerminator`，SqlLogPanel.kt:91-92，已 `;` 不重复）；
TXT 格式的「`-- === SQL #N ===` 注释头 + SQL + 空行」装配（formatTxt:81-93）与 .sql 目标形态
只差**分号 + 文件头计数**。测试面仅服务层 `SqlExportServiceTest`（5 例）。

## 二、需求评估：合理

用户导出后要在数据库客户端直接执行的诉求真实存在；现状 TXT 已 90% 是该形态（只差分号），
第四格式是同一惯例的自然延伸而非新通道；实现面小（服务层一分支 + 两面板各两行 + 文案）。

## 三、方案（D131）

- **入口形态：既有「格式三选」对话框扩为四选（CSV/TSV/TXT/SQL），不新增菜单项**——O-91 已定
  「导出不新造文件格式通道」惯例；入口/交互/通知链零新增。
- **默认选中 SQL（用户拍板 20261009）**：格式对话框预选项从 CSV 改为 SQL；默认保存名随之
  `sql-export-<date>.sql`（用户拍板采用根治方案——不叫 `sql-history.sql`/`sql-log.sql`，
  避免被存进自动保存目录后当历史文件读回/被保留上限清理；`<date>` 取 `yyyyMMdd-HHmmss`
  防同日静默覆盖）。
- **内容形态（新增 `SqlExportService.formatSql`）**：文件头注释 `-- Exported N SQL statements
  by MyBatis-Plus SQL Visualizer at <time>` + 逐条沿用 TXT 注释头（time/source/duration）+
  `withTerminator` 分号终止的 SQL + 空行分隔。分号逻辑与 `SqlLogPanel.withTerminator` 单一真源
  对齐（下沉或引用，勿两份拷贝）。
- **参数形态：只导 `restoredSql`（回填后可执行）**，参数缺失条目如实保留 `?`；「保留占位」
  形态不做（无既有惯例，且参数缺失本就保留）。
- **与「导出可移植 SQL」的边界（写死防顺手统一）**：那是预览面板单语句方言标注导出（内容源=
  方言生成链）；本需求是历史/日志行集原文合集（内容源=restoredSql，无方言改写）。内容源、
  行集语义、注释头三者不同，不合并互不替代，仅共用写盘与通知链。

## 四、拍板点（20261009 用户已拍板）

- **A 入口形态**：✅ 四选扩格式 + **默认选中 SQL**。
- **B 默认文件名**：✅ `sql-export-<date>.sql` 根治（不与历史文件名空间重叠）。
- **C BOM**：✅ 不引（与既有 CSV 导出同口径）。

## 五、实施步骤

- **S1** `SqlExportFormat` 增 `SQL("sql")`；`format()` 加分支；新增 `formatSql(rows)`
  （分号真源与 withTerminator 对齐）。
- **S2** 两面板 `chooseExportFormat` 各加第四项（数组+分支，各 ~3 行），**默认选中 SQL**；
  默认保存名改 `sql-export-<yyyyMMdd-HHmmss>.sql`（两面板各自拼接）。
- **S3** bundle：`log.export.visible` 双语文案 `(CSV/TSV/TXT)` → `(CSV/TSV/TXT/SQL)`
  （en:1136 / zh:1140）；新增键预计 0~1 个。
- **S4** `SqlExportServiceTest` 增 formatSql 用例：单条带分号不重复 / 无分号补 `;` /
  注释头字段与 TXT 同构 / 多条序号连续 / 空行集。
- **S5** 反向验证：临时把 formatSql 分支指回 formatTxt → 新增断言必红，贴两次读数后还原。
- **S6** 计划 §八实施记录回填 + CHANGELOG 一行。

## 六、验证方案

定向 `--tests "cn.song.mybatisplus.visualizer.log.SqlExportServiceTest"`；回归同类全绿。
真机走查（留验收）：历史面板搜索收窄后右键导出选 SQL → 文件可被数据库客户端直接执行；
日志面板同构；通知含条数与路径。

## 七、待验证前提（实施者先证伪再动手）

- **P1** `File.writeText` 默认 UTF-8（Kotlin stdlib 契约，以 jar/源码复核勿凭记忆）。
- **P2** `restoredSql` 参数缺失保留 `?`（LogSqlRestorer.kt:22 KDoc 声明）——导出文件出现 `?`
  属预期，验收文案写明。
- **P3** `Messages.showChooseDialog` 第四项 index==3 返回语义与现分支同构（0~2 现状实证，
  第 4 项推断，运行确认）。
- **P4** 回写污染仅发生在用户手动把导出文件存入自动保存目录时（目录扫描只认 .sql 扩展名，
  HistorySearchService.kt:113-118 实证）。

## 八、实施记录

（2026-10-09 回填；实施于分支 `t-exportsqlformat` 独立工作树，S1~S6 全做完）

### 待验证前提证伪结论（P1~P4 全证实，无一被推翻）

- **P1 ✅**：以项目实际解析的 kotlin-stdlib **1.9.22** jar 字节码复核（非凭记忆）——`javap -c kotlin.io.FilesKt__FileReadWriteKt`：`writeText$default` 在 charset 缺省时 `getstatic kotlin/text/Charsets.UTF_8`。默认 UTF-8 且无 BOM，既有 `exportToFile` 零改动即合规。
- **P2 ✅**：代码级复核 `LogSqlRestorer.restore`（LogSqlRestorer.kt:34-39）——`if (paramIdx >= params.size) break` 后 `sb.append(sql, prev, sql.length)` 把占位符之后的剩余 SQL 原样追加，`?` 保留；与 KDoc :21 声明一致。导出文件出现 `?` 属预期（验收文案口径）。
- **P3 ✅**：243 平台源码实证（`ideaIC-243.28141.41-sources.jar`）：`Messages.showChooseDialog` → `MessagesServiceImpl.showChooseDialog:231-245` → `ChooseDialog.getSelectedIndex()`（ChooseDialog.java:136-141）＝exitCode 0 时返回下拉所选**下标**（N 项即 0..N-1，第四项=3），非 OK（取消/关窗）一律 -1。运行语义与现分支同构，`idx < 0` 挡板原样有效。
- **P4 ✅（附口径补强）**：`HistorySearchService.search(dir):113-118` 只认 `.sql` 扩展名（大小写不敏感）——读回污染仅发生在用户手动把导出文件存进自动保存目录时，证实。补强一处口径：`SqlLogAutoSaver` 保留上限清理按**名族**过滤（:145「非匹配 .sql 不计数、不参与删除候选」），`sql-export-*` 即便被手动放进该目录也不会被清理；但历史页**读回是扩展名制**、不看文件名——「防清理」成立，「防读回」仅靠用户不手动存入，与拍板方案认知一致。

### 读数（定向 `--tests cn.song.mybatisplus.visualizer.log.SqlExportServiceTest`）

- 新增 6 例后首跑 **11 例 1 红**：测试自身行数断言算错（结尾 `\n\n` 产生两个尾随空元素，`expected:<5> but was:<6>`）——修测试非修产品；复跑 **11/0/0/0 全绿**（BUILD SUCCESSFUL 16s）。
- **反向验证（S5）**：临时把 `format()` 的 SQL 分支指回 `formatTxt`（REVERSE-PROBE 标记）→ **恰 4 红**：`testSql_单条无分号_文件头计数与补分号`（:73）/`testSql_注释头字段与TXT同构`（:102）/`testSql_空行集_仅文件头`（:114）/`testSql_多条序号连续`（:125），读数 `tests="11" failures="4" errors="0"`；`testSql_带分号不重复` 探针下保绿（TXT 形态本就不产双分号——该用例判别的是「补分号」契约，如实登记非全量判别）；还原后复跑 **11/0/0/0**，探针残留 grep 0。
- 首轮 **编译红**（偏差①的现场证据）：`compileKotlin FAILED`——`SqlExportService.kt:162:87 'when' expression must be exhaustive. Add the 'SQL' branch or an 'else' branch`（formatPortable）。

### 实施偏差（如实登记）

1. **`formatPortable`（O-91 可移植导出）被枚举扩员波及**：计划盘点只覆盖 `format()` 的分发，漏了同枚举分发的 `formatPortable`——加 `SQL` 后该 `when` 不再穷尽，编译红。处置＝按 §三边界（可移植通道不提供 SQL 选项、不合并互不替代）加**显式防御分支** `SQL -> error("formatPortable: SQL format is not offered …")`：UI 侧 SqlPreviewPanel 自带三选对话框（SqlPreviewPanel.kt:1182-1184）永远产不出 SQL，分支不可达；显式失败防未来误接入时静默伪装成 TXT。
2. **注释头提取共用**：formatTxt/formatSql 的逐条注释头（`-- === SQL #N ===` + time/source/duration 行）抽成私有 `appendSqlCommentHeader` 单源；分号真源直接复用同包顶层 `withTerminator`（SqlLogPanel.kt:91-92）——零下沉零复制。
3. 默认保存名收成服务层 helper `SqlExportService.defaultSqlExportFileName()`（`sql-export-<yyyyMMdd-HHmmss>.sql`，`Locale.ROOT`），两面板 SQL 格式时调用；CSV/TSV/TXT 保持 `sql-history./sql-log.${ext}` 原名随 ext。
4. 过程事故（当场修复）：用 sed 改 properties 时剥掉了全文件 CRLF，立即 `git checkout` 还原后改用编辑工具重做（改后 `file` 报 with CRLF、SqlExportService.kt 新旧行 CR 数=行数 193/193 实证无损）。

### 其他

- bundle：en:1136 / zh:1140 `log.export.visible` 双语加 `/SQL`；新增键 **0** 个（计划预估 0~1 命中下界）。
- 真机走查（§六）留验收：历史面板搜索收窄后右键导出选 SQL → 数据库客户端直执；日志面板同构；通知含条数与路径；参数缺失条目 `?` 的验收提示口径见 P2。
