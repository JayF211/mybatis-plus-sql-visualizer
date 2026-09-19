# T-MysqlGStrip MySQL 执行链 `\G` 后缀剥离 — 开发计划

- 日期：2026-09-18 08:31
- 任务编号：T-MysqlGStrip（第七轮报告 §9.4 **O-32**，P3；原拟定名「`\G` 提交剥离锚点核实」——核实后定案「有轻量挂点可做」转实施）
- 来源：第七轮报告 §8 DBX 吸收清单 v6 **#35**——提交前剥离行尾 `\G`/`\g`，提交文本与 `lastExecutedSql` 两态分离（DBX `4644015`，sqlStatementRanges.ts:35~49）
- 性质：执行链小件——用户从 MySQL CLI 习惯带来的 `\G` 后缀当前原样透传服务端报 1064
- 实施分支：合流后自 master 拉任务分支 t-mysql-g-strip（前置项 T-MergeDev2Master 策略决策）
- 验证基线：合流后 master 全量 testShards 绿（以合流时钉死计数为准）

---

## 一、背景与依据

用户从 MySQL CLI 带过来的 `SELECT 1\G`（垂直输出后缀）粘进预览面板/控制台执行时会报语法错。DBX 的做法：仅剥最后一语句行尾的 `\G`/`\g`（保留尾分号），注释/字符串字面量内的 `\G` 不碰，提交文本剥离、历史记录保留原文（两态分离有 spec 断言）。锚点核实的定案：**提交文本源头 100% 在插件侧**（面板编辑器 → sql 参数 → exec 文件/控制台文档），不存在平台注入路径，插件提交前剥离即可，不必碰平台。平台侧（DBN）对 `\G` 的行为本仓库无法证实，按「透传服务端报 1064」假设处理，真机走查验证。

## 二、现状核实（master 实测，2026-09-18）

| 面 | 现状 | 位置 |
|---|---|---|
| 提交文本来源 | 面板「执行」取编辑器原文（仅分类+DML 确认，不改写）；EXPLAIN 是唯一会改写文本的入口（加方言前缀+trim 尾分号） | `ui/SqlPreviewPanel.kt:614-692`（文本取 617-618）；`:699-732` |
| 提交链 | `SqlExecutor` → `DataSourceSelector`，SQL 全程参数原文透传；写入 `.idea/sql-visualizer/exec/mpsql-exec.sql` 后 attachConsole；实际提交两链（原生 `doRunQueryInConsole`/兜底 `executeQueries`），提交前 `editor.document.text` 原文同步进 console vf | `execute/SqlExecutor.kt:32-95`；`execute/DataSourceSelector.kt:2994-3058,2411-2499,2313-2329` |
| 通用预处理 hook | **无**（全链仅 EXPLAIN 包装、显示/复制层补分号不进提交链、历史读取侧剥尾分号） | grep 实证 |
| `\G` 处理 | 插件侧零处理（全量 grep 零命中） | 同上 |
| 历史落盘 | 两条历史链（SqlHistoryManager/SqlLogHistoryFull）记的都是提交前原文，与控制台提交无预处理耦合 | `history/SqlHistoryManager.kt:54-64`；`log/SqlLogAutoSaver.kt:270-303` |
| 旧旁路 | `ExecuteSqlSupport` → 系统临时文件 + 用户手动 Ctrl+Enter（平台原生行为，插件零干预） | `execute/ExecuteSqlSupport.kt:55-70` |

## 三、方案

**T-MG-01（剥离纯函数）**：`execute` 包新增 `MysqlClientGSuffix.strip(sqlText): String`——DBX 规则照抄：仅当**最后一个语句**的行尾是 `\G`/`\g`（允许尾随空白与可选分号在 `\G` 之前/之后的形态：`SELECT 1\G`、`SELECT 1;\G`）时剥离该后缀；行内容以 `--`/`#` 注释开头或匹配处于字符串字面量内的 `\G` 不碰（v1 用行级判定：只看最后一条语句的末行，不做全文字符串状态机——宁漏剥勿错剥，漏剥=维持现状报错，错剥=改变用户 SQL）；返回剥前原文与剥离标记两态。

**T-MG-02（挂点：提交链总口）**：挂 `DataSourceSelector` 文本进入 exec 文件/控制台文档的写点（`reuseSharedConsole` 的 setText 与 `writeVfTextViaDocument`、`executeOnConsole` 提交前同步）之前，对 sql 实参做 strip——编辑器显示文本同步回写剥离后文本（保证「所见=所执行」，避免 DBX 当年没有的显示/执行分叉）；历史链记剥离前原文（两态分离）。仅在 MySQL 系方言（按 runner/数据源方言判定，拿不到方言时默认执行剥离——`\G` 在其他方言同样是语法错，剥离无害）生效。

**T-MG-03（测试与文档）**：`MysqlClientGSuffixTest` 表驱动（DBX 三组：`\G`/`\g`、带尾分号、`SELECT 1; \G` 分号后命令、注释行不碰、字符串字面量 `SELECT '\G'` 不碰）；提交链挂点 headless 用例（剥离后进 exec 文件、历史记原文两态断言）。CHANGELOG [Unreleased] + 变更记录行 + 第七轮报告 #35 落地备注回填。

## 四、验收标准

1. 表驱动用例全绿；字符串字面量/注释场景零改动（防错剥断言）。
2. 历史链记录原文、提交文本剥离的两态分离有测试钉住。
3. 非 MySQL 方言行为不变（或同样无害剥离，按 T-MG-02 定案实现写明）。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 预览 `SELECT 1\G` 执行：不再 1064，结果正常返回。
2. `SELECT '\G'` 执行：原样透传（不误剥）。
3. 平台侧假设验证：剥离后平台控制台正常执行；顺带记录 DBN 对未剥离 `\G` 的真实表现（补文档）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 误剥字符串内 `\G` | 行级判定不看全文状态机 | 规则限定「最后一条语句的末行行尾」，字符串内 `\G` 几乎不可能落此位置；用例钉住 |
| 方言判定不可得 | 拿不到 runner 方言时的分支 | 默认剥离（`\G` 非任何方言合法语法，剥离无害），写明定案 |
| 显示/执行一致性 | 剥离后回写编辑器涉及文档写纪律 | 复用 T-ExecDocSync 系列既有同步收口（setText→commit→saveDocument 同临界区），不新开写路径 |

## 七、实施顺序

```
T-MG-01（剥离纯函数+表驱动）→ T-MG-02（提交链挂点）→ T-MG-03（测试与文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

实施日期 2026-09-18，分支 t-mysql-g-strip（工作树 mpsv-mgs，基线 master 8a3a6ff）。

### T-MG-01 剥离纯函数

新增 `execute/MysqlClientGSuffix.kt`（纯函数，零平台依赖，进 testFast 快桶）：

- `Result(original, submitText, stripped)` 两态结果——`submitText` 进 exec 文件/控制台文档，
  `original` 留给历史链；
- `strip`：末行 = 全文最后一个非空白字符所在行，`matchEntire` 匹配 `^(.*?)(;?)[ \t]*\\[gG][ \t]*(;?)$`
  （行尾锚定；分号在 `\G` 前/后皆保留，`\G` 两侧空白吞掉；末行只有 `\G` 命令时整行删除）；
  注释行（`--`/`#` 开头）提前返回；只移除 `\G` 与紧邻空白，其余字节（含尾随换行）原样保留；
- `stripForDialect` / `isMySqlFamily`：方言为 null/空白 → 按 MySQL 处理（默认剥离，§三定案）；
- `dialectFromUrl`：取 `://` 前 scheme 段末节（`jdbc:mysql://…` → `mysql`），非 URL scheme 形态
  （Oracle thin `@host:1521:SID`）返回 null → 走默认剥离分支。

### T-MG-02 提交链挂点

`execute/DataSourceSelector.kt` 两处写点前插一层 strip（既有写路径零改动）：

1. `chooseAndPrepare` 顶部：`val submitSql = stripGSuffixForSubmit(sql, null).submitText`，
   下游 `reuseSharedConsole` / `createTempPsiFile` / `getAttachDataSourceRunners` 三处改用
   `submitSql`。此点数据源尚未选定（runner 弹窗在下方），方言不可得 → 默认剥离；
2. `executeOnConsole` 顶部：从控制台编辑器文档取文本 → 剥 → 剥后文本经既有
   `dispatchDocWriteOnEdt`（→ `writeVfTextViaDocument`：setText→commit→saveDocument 同临界区、
   BGT 派 EDT）回写编辑器文档，保证所见=所执行；下方 vf 同步块（runWhenSmart 内
   `WriteAction.compute { writeVfTextViaDocument }`）同以剥后文本兜底（回写失败时提交内容
   仍恒定无 `\G`）；`executedSql`（MetadataInvalidator 与 afterSubmitted 用）取剥后文本。

方言判定：新增 `ExecConsoleContext.dataSourceUrl(console)`，复用本对象既有的
`JdbcConsole.getDataSource().getUrl()` 面（`alignToDataSourceDatabase` 同源 API），零新反射。

历史链零改动：预览历史（`SqlPreviewService.recordHistory` → `SqlHistoryManager`）与日志历史
（`SqlLogAutoSaver`）都在执行链上游记原文，提交文本剥离不触碰它们（两态分离由此成立）。

### T-MG-03 测试与文档

- `MysqlClientGSuffixTest`（纯逻辑，18 例）：DBX 三组（`\G`/`\g`、带尾分号、`SELECT 1; \G`
  分号后命令）+ 尾随空白/换行/多行语句/独立 `\G` 行 + 防错剥（注释行、`SELECT '\G'`、
  `SELECT 'x\g'`、字面量与行尾 `\G` 并存、多行字符串、非末行、非行尾）+ 方言门/URL 解析表驱动；
- `MysqlClientGSuffixSubmitChainTest`（headless，4 例）：剥后文本经生产写路径
  （`WriteAction.compute { writeVfTextViaDocument }`）落 exec 文件 + `SqlHistoryManager`
  记 `original` 的两态断言 + 方言不可得默认剥离 + 非 MySQL 方言不剥 + 无后缀不回归。
  沙盒默认 test 任务无 Database 插件（`chooseAndPrepare` 早退），故走 `stripGSuffixForSubmit`
  测试缝 + 生产写路径复现（ExecTempFileFixTest / ExecDocSync2Test 既有口径）。

文档三处：`docs/变更记录.md` 总册顶部追加一行、`CHANGELOG.md` `[Unreleased] Fixed` 追加一条、
本 §八 回填。

### 门禁与验真

- testFast **2646/0/0/6** 绿（含新纯逻辑类）；
- 全量 testShards **4969/0/0/6** 绿（本分支实测口径，含本任务新增 22 例）；
  首跑 testShard2 有 7 例 `AlreadyDisposedException: Container is already disposed`
  （PluginSettingsPanelBindingTest / SectionConfigurableAutoCaptureLinkageTest /
  SqlConsoleBridgeFilterProviderTest / SqlLogListFreezeGuardTest）——并行工作树下的已知非确定性
  失败，隔离复跑四个类全绿，重跑 testShards 全量绿（按环境 flake 记录，与本任务无交集）；
- 负向验真：把 `strip` 的 `matchEntire` 改成 `find`（破行尾锚定）→ 4 例防错剥用例精确变红
  （`testStringLiteralNotTouched` / `testStringLiteralInsideLastLineKeptWhileSuffixStripped` /
  `testMultilineStringLiteralEndingWithSuffix` / `testSuffixNotAtLineEndNotTouched`），复原后全绿。

### 实施偏离

1. **方言判定输入改为数据源 JDBC URL**：计划原文写「按 runner/数据源方言判定」，实测
   `getRunnerName(runner)` 取的是 runner 的 `getDisplayName`（数据源名如 `jpa_demo@localhost`），
   不含方言信息；DBN 侧取 DBMS 名需新增反射面（`LocalDataSource.getDbms()` 未在本仓库验证过）。
   改用数据源 JDBC URL 子协议段（既有已验证 API 面），判定语义等价且零新反射。
2. **验收 §3 取「非 MySQL 方言不剥」分支**：验收原文给了「行为不变」与「同样无害剥离」两选项，
   本实现取前者（已知非 MySQL 方言不剥离），仅「方言不可得」走默认剥离——与 §三定案一致。
3. **计划 §二 行号漂移**（以当前代码为准）：提交文本来源现为 `ui/SqlPreviewPanel.kt:818-901`
   （`executeSql`，文本取 :821-822；`executeAdhocSql` :717-）；EXPLAIN 现为 `:969-1003`。
   §二 其余各行（提交链位置、无通用预处理 hook、插件侧零 `\G` 处理、两条历史链记原文）
   经复核实测一致。

### 未验证项 / 真机待走查

- §五 三条真机走查留人工：预览执行 `SELECT 1\G` 不再 1064；`SELECT '\G'` 原样透传；
  剥离后平台控制台正常执行，并顺带记录 DBN 对未剥离 `\G` 的真实表现。
- 平台侧对 `\G` 的行为本仓库无法证实（沿用「透传服务端报 1064」假设）。
- testDatabase 通道（有 Database 插件的 DB 集成链）本任务未新增用例：`executeOnConsole` 的
  方言分支依赖真实数据源 URL，headless 无法构造；如需端到端钉住可后续在
  `ExecDocGateIntegrationTest` 形态上补一条（本机 DB 集成链走绿跳的既有口径未变）。
- 观察记录（既有平台行为，非本任务引入）：`writeVfTextViaDocument` 落盘在本机 Windows 按
  系统行分隔符写出 CRLF（`ExecDocSync2Test` 只断言单行内容故此前未暴露）；本任务落盘比对
  用例已按行分隔符归一。
