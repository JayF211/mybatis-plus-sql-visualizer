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

（未实施）
