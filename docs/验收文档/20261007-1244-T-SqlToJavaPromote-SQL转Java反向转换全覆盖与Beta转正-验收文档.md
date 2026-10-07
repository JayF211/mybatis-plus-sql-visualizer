# T-SqlToJavaPromote SQL→Java 反向转换全覆盖与 Beta 转正 验收文档

- **验收时间**：20261007 12:44（master `3c19aa6` 时点实测）
- **计划**：`docs/开发计划/20261006-1020-SQL转Java反向转换全覆盖增强与Beta转正-开发计划.md`（§十 实施记录已回填）
- **实施分支**：`t-sqltojava-promote`（工作树 `mybatis-plus-sql-visualizer-t-sqltojava-promote`，合并后已清退）→ **并入 master `7d7b8cd`**（`master..origin/t-sqltojava-promote=0` rev-list 实测，零残留）
- **验收方式**：SQL 层往返门禁（用户点名「Java 转 SQL 再 SQL 转 Java」的测试化落地）+ 全量 testShards 分片门禁

## §一 提交清单（分支自有 8 笔，按实施序）

| 提交 | 里程碑 | 内容 |
|---|---|---|
| `0cb9c8e` | M1 门禁基建 | 语料库 70 条四档基线 + `ReverseBridge`（反向→正向桥接 + INSERT 测试渲染器 + 词法安全规范化）+ `ReverseRoundTripGateTest` 四档断言；生产侧仅 `SqlReverseResult` 字段增量（tableAlias/distinct/groupBy/having/unions/forUpdate + `ReverseUnion`）与三 body 主表别名捕获，行为零变化 |
| `6db3179` | M2 G1~G6 | GROUP BY→`.groupBy(...)`、HAVING→`.having("...")`、DISTINCT 建模、FOR UPDATE→`.last(...)`（与 LIMIT 合并单 last）、聚合/函数列收集原文（**G5 语义修复**）、INSERT NULL→Java `null`；语料升档 7 条 |
| `8ee6e0c` | M3 G7~G11 | tokenizer 方言宽容（双引号标识符/`0x` 十六进制/反斜杠转义）+ DATE/TIMESTAMP ANSI 字面量 + `<=>`/算术/ILIKE 降级透传 + UNION [ALL] 解析（SQL 往返通、Java 生成层降级注释）+ G7 参数化 apply；语料升档 9 条 |
| `4769a77` | M4 生成层 | 全语料生成代码不崩断言（多表删除需实体上下文显式豁免） |
| `5d11133` | M5 转正 | bundle en/zh `log.tab.sqlToJava` 去 Beta + CHANGELOG [Unreleased] Added 两条/Changed 两条 |
| `93b174f` | 收口 | 计划 §十 实施记录回填（含并行 daemon 争用/OOM 环境波折留痕） |
| `76f41ca` | 对齐 | master 32 笔并入分支（零冲突，三处重叠文件双侧在场校验） |
| `4efc369` | 对齐 | 反馈分类文案 `feedback.category.sql-to-java` 中英双包统一箭头写法（与页签/菜单命名一致） |

## §二 能力面落地（缺口台账 G1~G12 处置）

| 台账项 | 处置 | 终态 |
|---|---|---|
| G1 GROUP BY | **落地** | 解析建模 `groupBy` 列清单 → `.groupBy(...)`（lambda 映射/字符串回落，MP 3.5.17 javap 实证 `AbstractWrapper.groupBy(R...)`） |
| G2 HAVING | **落地** | 聚合表达式经 Raw 降级 → `.having("...")`（javap 实证 `having(String, Object...)`） |
| G3 DISTINCT | **落地** | 建模 `distinct=true` → `.select("DISTINCT a, b")` 字符串单串（MP core 无 selectDistinct，javap 实证） |
| G4 FOR UPDATE | **落地** | 建模 `forUpdate` → `.last("FOR UPDATE")`（与 LIMIT 并存合并单 last） |
| G5 聚合/函数列 | **落地（语义修复）** | 原来整体放弃致往返 COUNT 语义丢失；现收集原文+AS 别名 → `.select("expr AS alias")` 字符串（不产 selectAs） |
| G6 INSERT NULL | **落地** | 建模 `Literal("NULL")` → Java `null`（此前报错） |
| G7 参数化 apply | **落地** | 「表达式 比较符 字面量」→ `apply("expr = {0}", 值)`（RAW_PARAM_REGEX 值/表达式分离） |
| G8 UNION | **SQL 层落地 / Java 层降级** | 解析 `UNION [ALL] 子查询`（原文承载），往返经 `UnionClause` 渲染还原；Java 生成层降级注释（MP core 无 union() 方法 javap 实证、MPJ jar 本地无货=实证受阻按预案降级，不产不存在的 API） |
| G9 UPDATE JOIN | **维持报错** | MPJ 1.5.6 updateJoin 签名本地不可得（实证受阻），维持报错不猜测 |
| G10 方言宽容 | **落地** | 双引号/`0x`/`\'` 转义/DATE-TIMESTAMP 字面量进 tokenizer+parseValue；`<=>`/算术/ILIKE 改 KEY_UNSUPPORTED 走 Raw 降级（「任何条件都不挡路」） |
| G11 EXISTS/子查询 IN | **确认钉住** | Raw 降级路径语料钉住（RAW_DEGRADE 档） |
| G12 不适用类 | **维持报错** | DDL/INSERT..SELECT/无 FROM 标量 SELECT 等 Wrapper 语境不适用类（5 条 PARSE_ERROR 档全为此类） |

## §三 验证证据

1. **SQL 层往返门禁**（`ReverseRoundTripGateTest`，全语料四档断言）：最终分布 **ROUND_TRIP 52 / PARSE_OK 7 / RAW_DEGRADE 6 / PARSE_ERROR 5**（70 条）。实施起点基线为 38/10/3/19——报错档 19→5，收敛的 14 条全部转 ROUND_TRIP（13 条）或 RAW_DEGRADE/结构断言（算术/`<=>`/ILIKE 降级+FOR UPDATE 建模断言），先红后绿逐条留痕于语料文件与实施记录。
2. **reverse 全族回归**：279 例多轮绿（M2 轮 4 处既有断言随 G5 行为变化更新为新语义并登记；M3 轮零既有断言破坏）。
3. **生成层覆盖**（M4）：凡可解析语料（65 条非 PARSE_ERROR）走 JavaCodeGenerator 必产非空代码不抛异常。
4. **全量门禁**：实施期 shard1~5 + testExternal 分片全绿（合计 6186 例；docker 三态按惯例 skip）。**合并后首全量 `6218/0/0/9`（543 类 parity，3m16s）已由 T-StackHoverUi 统一门禁复跑通过**（`3c19aa6` 登记在读）——含本功能合并后的最终态，权威读数以此为准。
5. **过程环境波折（均环境性、留痕）**：并行会话 t-sqllogperf 期间共享 gradle daemon 两次意外消失；testExternal IU-253 目标 JVM 页面文件 OOM（errno=1455，内存回落后自愈）；FatalPassthroughTest 全量并发 flaky（单跑绿自证非回归）；残留 Test Executor 锁沙盒（taskkill 清理后复跑通过）。

## §四 UI 验收

本任务 UI 面改动范围：工具窗页签文案（去 Beta）与生成代码内容（新能力产物）——面板构建/交互/渲染逻辑零改动，UI 验收=自动化 UI 回归读数 + 文案面实测 + 真机走查清单（待执行，见 §六 遗留）。

### 4.1 自动化 UI 测试读数（master `3c19aa6` 时点复跑，61 例全绿）

| 测试类 | 例数 | 覆盖面 |
|---|---|---|
| `log.SqlToJavaPanelTest` | 55 | 面板转换逻辑+批量拆分/错误隔离+页签文案断言（`log.tab.sqlToJava` 非 blank，key 在场=去 Beta 后资源加载正常）+实体多候选自动推断判定（uniqueMain/singleSub 等 5 形态） |
| `log.SqlToJavaPanelUiTest` | 4 | **真实面板构建**：①面板构建出输入/结果编辑器、空态提示、生成形态下拉（不崩、下拉有可选项）②粘贴 SQL 转换→生成 QueryWrapper 且格式化渲染含换行 ③ClassPicker 弹窗选取类后渲染图标与类名 ④非空标签与下拉有正首选尺寸 |
| `actions.ConvertSqlToJavaActionTest` | 2 | 编辑器选中 SQL → 动作入口（splitStatements/parseBatch 链） |

全量门禁同批绿（含 `config.PluginSettingsPanelBindingTest` 对设置页 sqlToJava 快捷键标签的 binding 断言、`HelpUiTest` 资源加载断言）——bundle 两行 value 改动（去 Beta）零 key 变化，资源链零回归。

### 4.2 文案面实测（master `3c19aa6`）

- 页签：`MpSqlVisualizerBundle.properties:526` / `_zh.properties:507` 均为 `SQL → Java`（`(Beta)` 已去，grep 实测）；
- 反馈分类：`feedback.category.sql-to-java` 中英双包统一箭头写法（`4efc369`，与页签/菜单「发送到 SQL → Java 转换」命名一致）；
- 帮助页 html/md 与 plugin.xml：grep 实测零功能 Beta 表述。

### 4.3 真机走查清单（runIde 沙盒 + demo 项目，随 D41 窗口执行）

1. 工具窗四页签观感：「SQL → Java」无 Beta 后缀（en/zh 双语各验一次）；
2. 面板输入本计划新增形态 SQL（GROUP BY+HAVING 聚合 / DISTINCT / 聚合列 / INSERT NULL / UNION / 双引号标识符各一）→ 生成代码展示（注释头含 UNION 降级提示、聚合列回落字符串 select）；
3. 编辑器选中 SQL → 右键「发送到 SQL → Java 转换」入口同链路；
4. 多候选实体弹窗（SqlSelectionDialog）/调用上下文（Service/Mapper+VO）与新能力组合（GROUP BY 在 MPJ 形态的 lambda 列）；
5. 设置页「默认 Wrapper 形态」五选项 × 新能力 SQL 的往返观感。

## §五 行为变化登记（CHANGELOG 已同步）

1. **聚合/函数列语义修复**（G5）：`SELECT COUNT(*) AS cnt` 等此前 select 生成整体放弃（往返变全列查询、聚合语义丢失），现收集原文生成 `select("COUNT(*) AS cnt")`——CHANGELOG [Unreleased] Changed 已登记。
2. **页签去 Beta**：`log.tab.sqlToJava` en/zh 两行 `SQL → Java (Beta)`→`SQL → Java`；反馈分类文案箭头统一（`4efc369`）。
3. **4 处既有断言更新**（M2 轮先红后绿）：函数列收集（原 assertNull→收集原文）、DISTINCT 建模（原不收集→distinct=true+收集）、GROUP BY/HAVING 建模（原报错→建模断言）、聚合列回落字符串 select（原别名零泄漏→回落字符串断言）。

## §六 遗留项

1. **runIde 真机走查**（demo 项目「Java 转 SQL 再 SQL 转 Java」真实链路 5~8 条，清单见 §4.3）：实施期环境页面文件 OOM 波折后未启动沙盒，登记随 **D41 统一真机窗口**执行；测试层已覆盖 SQL 往返+生成层完整性+UI 自动化 61 例，真机走查为 PSI 链路端到端增强验证。
2. **G8 UNION Java 生成层**：现为降级注释提示；MPJ jar 可得（本地缓存或依赖入库）后可升级真 `union()` 生成——前置=MPJ 签名 javap 实证。
3. **G9 UPDATE JOIN**：维持报错；MPJ updateJoin 签名可实证后可评估放开。
4. **MCP `sqlv_reverse_sql` 工具描述**：当时 grep 无「支持范围」表述未动；后续如需随新能力更新文案可随批次搭车。
