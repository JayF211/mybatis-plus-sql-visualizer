# T-McpWalkFindings MCP 真机十工具走查三项观察收口——开发计划

> 2026-10-05 日常 IDE（IU 262.10968.63，demo 仓库项目）对插件全部 10 个 MCP 工具做端到端真机走查：18 次调用 18 条 `[MCP-AUDIT]` 一一对应，十工具主路径全绿——这同时是 T-McpDynamicToolset 真机 ASM 修复（c7a75a3）S4 复验口径的全部要件（注册行无 WARN / 十工具在册 / 端到端 / 审计对账），**S4 视为通过**。走查留下三项观察（一项交互口径错位、一项 simulate 质量疑点、一项跨源静默解析老建议补证），本计划收口。写完停等确认，未获确认不实施。

## 一、走查取证（2026-10-05，真机十工具实测）

| # | 工具 | 结果 | 关键读数 |
|---|---|---|---|
| 1 | sqlv_check_sql | 绿 | `DELETE … OR 1=1` 判别命中 OR_TAUTOLOGY（dangerous: true）；SELECT 不报属「只查无界写」设计面 |
| 2 | sqlv_render_sql | 绿 | xmlSnippet 动态 SQL 展开正确；filePath+line 见观察 F1 |
| 3 | sqlv_render_logs | 绿 | Preparing/Parameters 双语句还原，类型标注解析正确 |
| 4 | sqlv_mapper_check | 绿 | 10 语句全盘点，include 展开、`#{}`/`${}` 计数全对；simulate 见观察 F2 |
| 5 | sqlv_reverse_sql | 绿 | QueryWrapper 链完整（select/gt/eq/orderByDesc/last） |
| 6 | sqlv_rollback_plan | 绿 | UPDATE/DELETE 双形态一致 UNABLE + 精确快照模板（fail-safe 语义，非缺陷） |
| 7 | sqlv_perf_advice | 绿 | 3 条建议全对 |
| 8 | sqlv_crud_sql | 绿 | filePath 相对路径与 FQCN 双形态一致；AUTO 主键 / `exist=false` 排除全对 |
| 9 | sqlv_explain | 绿 | 真库 MySQL EXPLAIN 全链路（mp_demo 源 `type=const, key=PRIMARY`）；不指名报 DATASOURCE_NOT_FOUND 列全 5 源 |
| 10 | sqlv_schema_context | 绿 | 双表 schema 带注释返回；指名未内省源报 SCHEMA_NOT_AVAILABLE 带诊断 |

审计对账：idea.log `[MCP-AUDIT]` 18 条 = 15 OK + 3 EXPECTED_ERROR（SOURCE_NOT_FOUND / DATASOURCE_NOT_FOUND / SCHEMA_NOT_AVAILABLE），零丢失零多余。注册行 `T-McpDynamicToolset: 10 toolsets registered onto mcpToolset EP (dynamic shell form)` 无 WARN 在册。

## 二、观察清单与根因

### F1（中）：render_sql 的 line 语义与 IDE gutter 行号错位一格

- 现象：`UserMapper.xml:25`（`<select id="selectUserListByXml" ...>` 标签行）→ `SOURCE_NOT_FOUND no mapper statement`；`line=26`（语句体首行）→ 命中渲染。agent 照编辑器 gutter 行号（gutter 图标挂在标签行）传参必踩坑。
- 根因（McpSourceLocator.kt:87,118,129-138）：`renderAtLine` 取 `document.getLineStartOffset(line-1)` 行首偏移 → `findElementAt(offset)`。标签行行首是**缩进空白 leaf**，在 XML PSI 里挂在父级 content（mapper 根 tag）下，`PsiTreeUtil.getParentOfType(element, XmlTag)` 爬到的是 `mapper` 根（不在 `XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS`）→ 循环走到 null → SOURCE_NOT_FOUND。语句体首行 leaf 挂在 select tag 内 → 命中。
- 口径确认：工具 KDoc 写「line is 1-based」未写「须指语句体内行」；错误文案也没给「试下一行」的操作提示（对比：Wrapper 链兜底文案有 retry 提示，McpSourceLocator.kt:204-209）。

### F2（中）：mapper_check 空参模拟 6/10 标 `DANGEROUS PARSE_FAILED`，且丢了解析失败的 detail

- 现象：UserMapper.xml 全文 simulate=true，10 条语句中 6 条（#1/#3/#4/#8/#9/#10）`simulate risk: DANGEROUS PARSE_FAILED`，其中 #3 渲染文本明显带 `where id = ? and deleted = 0` 却同时报 `[ERROR] missing_where`。方向是**过保守误报**（fail-closed 设计，非假绿），但 6/10 的误报率让 simulate 面的可信度打折。
- 已确认事实链：
  - `UnboundedWriteDetector.detect` 解析失败 fail closed 按最高风险处理（UnboundedWriteDetector.kt:36,124,142），**Finding 里带 `e.message`（JSqlParser 报错原文）**；
  - 但 `SqlVisualizerMapperCheckTool.mapperCheck` 输出 simulate risk 行时只打 `risk.kinds()` 枚举名（SqlVisualizerMapperCheckTool.kt:118-122），**detail 丢了**——现在想知道真因只能靠猜；
  - 同语句的 simulate 渲染文本（换行折叠成空格后）手工喂 sqlv_check_sql → `findings: none, dangerous: false`（本轮实测），解析管道本身对这段文本没有结构性问题。
- 样本归纳线索（10 条语句，供归因起点、不预设结论）：无分支/单分支 OK（#2 foreach、#5 choose 单 when、#6 bind 单条件、#7 `<where>` 硬文本+单 if），**≥2 个动态分支全 FAIL**（#1/#4 3 分支、#8/#9/#10 2 分支、#3 `<set>` 3 分支）。诱因可能在渲染器对多分支间空隙的产物形态，也可能在解析器输入侧，**以 detail 诊断为准**。

### F3（低）：schema_context 不指名数据源时跨源静默解析，成功路径不披露来源——老建议补证升级

- 现象：`tables: "user"` 不指名 → 返回的 user 表是 9 列结构（username/email/first_name/last_name/department_id…，属某个已内省数据源）；同一时刻 `sqlv_explain` 对 mp_demo@localhost 的回显证明 mp_demo.user 实际是 **13 列**（password/role_id/dept_id/version…）。**拿到的 schema 可能不是意图库的表，且输出无任何来源线索**。
- 这是 2026-09-23 首轮实测已登记的已知行为（「建议输出加 `source:` 行，未实施」），本轮拿到两套列结构对不上的铁证，从「建议」升级为「立项收口」。
- 落点：`McpSchemaContextService.buildContext` → `collectEntries`（IdentityHashMap 逐源建索引、先到先得，McpSchemaContextService.kt:164-180 一带）→ `finish` → `McpSchemaFormatter.format`。Outcome 当前只有 text/error，无 source 通道。测试替身缝在场（`modelTablesForTest`/`dataSourceUrlForTest`，:72-74），McpSchemaContextScopeTest 表驱动可直加断言。

## 三、方案设计

### S1（F1）：行定位改「行内首个非空白字符」+ 落空顺延重试

- `McpSourceLocator.renderAtLine` 的 offset 计算（:87 一带）改为：取该行**首个非空白字符**偏移（document.charsSequence 从行首向后跳过空白）；整行纯空白时顺延试下一行（最多顺延 2 行，防文档尾部空行），全部落空才报 SOURCE_NOT_FOUND。
- 效果：`line=25`（标签行）行内首非空白是 `<select` 的 token leaf（挂 select tag 内）→ 直接命中；不需要重试循环就解决主场景，顺延只兜空行。
- 错误文案同步：SOURCE_NOT_FOUND 的 mapper XML 分支补一句「pass the line of a statement tag or any line inside it」的操作提示（对齐 Wrapper 链兜底文案风格，:204-209）。
- 非 XML 面（Java Wrapper 链 / 注解 / HQL 等 12 类触发面）同样受益于「行首空白 leaf → 行内首非空白 leaf」的改善，但本轮真机只验了 XML 面，其余面以定向测试回归为准（见 §四-2）。

### S2（F2）：诊断先行——mapper_check 输出补 PARSE_FAILED 的 detail，按真因再定修法

- 第一步（本计划必做）：`mapperCheck` 的 simulate risk 行在 `PARSE_FAILED` kind 在场时追加 detail 行（`UnboundedWriteDetector.Finding` 已带 `e.message`，补一个 detail 取值通道即可，SqlVisualizerMapperCheckTool.kt:118-122 一带）。格式如 `simulate risk note: <parser message 截断>`。**改动只动输出，不动检测与渲染**。
- 第二步（按 detail 归因后实施，若真因指向渲染器则回填本计划再动手）：
  - 若 detail 显示渲染产物含异常字符/残片 → 修渲染产物形态（注意 `renderSql()` 为 preview 主链共用，§四-4）；
  - 若 detail 显示解析器对多分支空隙形态敏感 → 在 detect 入口对渲染产物做一层空白规范化（`\s+` → 单空格）再解析——输入规范化不动渲染器，主链零波及（**推荐优先方向**）；
  - 修后断言：`#3`（带 WHERE 的 UPDATE）不再误报 missing_where，6 条 PARSE_FAILED 中可解析的回落 bounded / 真有风险的保留对应 finding。
- detail 诊断行本身持久保留（不受第二步结果影响）——下次任何语句 PARSE_FAILED，输出自带归因线索。

### S3（F3）：schema_context 输出披露数据源来源

- `McpSchemaContextService.collectEntries` 命中表时记录来源数据源（sources 列表与名字的对应关系已有——alias 校验段 `names.joinToString` 同一管线，跨源命中时取命中 ds 的 display name）；Outcome 加 `sourceName: String?` 字段，`McpSchemaFormatter.format` 输出首行前加一行来源披露：
  - 显式指名：`source: mp_demo@localhost`；
  - 跨源自动解析：`source: jpa_demo@localhost (auto-resolved; pass 'dataSource' to pin one)`——把指名提示带在输出里，agent 下一次调用就能自纠。
- 未命中（SCHEMA_NOT_AVAILABLE）路径不动（诊断行已带 scanned 明细）。
- 文档同步：文档站 `features/mcp` 与 `guide/configuration` 中英四页的 schema_context 输出示例补 source 行（只改仓内源，部署随发行节奏）。

## 四、待验证前提（实施者先证伪再动手）

1. **F1 的 PSI 根因推断**（「标签行行首空白 leaf 挂父级 content → getParentOfType 爬到 mapper 根」）是实测行为反推，未实码验证——实施者先写单测复现（同构语料：带缩进标签行的 mapper XML，line=标签行）钉住现状，再改。
2. **S1 对非 XML 面（12 类触发面）无回归**：「行内首非空白」对 Java 文件同样是改善方向，但 SqlPreviewService/Hql/JPA 各定位器对 leaf 的假设未逐一核过——定向跑 `McpRenderSnippetReadActionTest` + 触达面测试类，红则回退改「仅 XML 分支应用新 offset」。
3. **F2 的「≥2 动态分支 → PARSE_FAILED」规律**是 10 条语句的小样本归纳，只作归因起点；真因以 detail 输出为准，**禁止按猜测的诱因直接改渲染器**。
4. **S2 若动渲染产物会波及 preview 主链**：`XmlPreviewModel.renderSql()` 为预览面板 / render_sql / mapper_check 三方共用，主链断言钉着现形态——动渲染器的方案必须先列受影响测试清单；输入规范化方案（detect 入口 `\s+` 折叠）不碰渲染器，优先。
5. **S3 的跨源命中名字管线**：collectEntries 里 ds 是 `Any`（IdentityHashMap 键），display name 的对应结构（平行名字列表 or 反射取）实施时核实——alias 校验段已有同管线先例，预期低风险。
6. **S1 顺延重试的边界**：最多顺延 2 行是拍板值；若 `<select>` 标签行与语句体之间出现空行 + 注释行组合（连续 2 行非语句内容），仍 SOURCE_NOT_FOUND——接受（文案已给操作提示），不做无限顺延。

## 五、范围外

- mapper_check 空参模拟升级为「真实参数形态模拟」（大工程，工具输出已提示改走 render_sql）。
- render_sql 的 Repository 调用点回落 / 文件级注解候选弹窗等价物（T-McpToolset 原计划登记一期不带，不翻案）。
- explain / rollback_plan / check_sql 的行为面（本轮实测全绿，不动）。
- 文档站部署（随发行节奏，本计划只改仓内源）。
- McpGateFilterEpBridge / AcpTransportRegistrar / TinyClassFileWriter（T-McpDynamicToolset 已收口，S4 已过）。

## 六、测试与反向验证

| 子项 | 新增/修改 | 反向验证（必做） |
|---|---|---|
| S1 | McpSourceLocator 单测：标签行命中 / 行内首非空白命中 / 尾部空行顺延 / 空白行×3 落空文案（同构 UserMapper 语料）；SOURCE_NOT_FOUND 文案断言 | 临时还原 offset 取行首旧行为 → 标签行用例必红；恢复 → 绿。贴两次读数 |
| S2 | MapperCheckToolTest：PARSE_FAILED 带 detail 行断言；若实施第二步：#3 同构语料不再误报 missing_where + 多分支语句回落断言 | 摘 detail 输出（或摘规范化）→ 对应用例必红；恢复 → 绿。贴两次读数 |
| S3 | McpSchemaContextScopeTest 表驱动：显式指名 source 行 / 跨源 auto-resolved 行含 pin 提示 / 未命中路径无 source 行 | 摘 source 披露 → 三断言必红；恢复 → 绿。贴两次读数 |
| 归口 | 定向 = mcp 包全量 + 触达面（SqlPreviewService 相关类）；testFast 冒烟归口 | — |

## 七、实施记录

> 实施时回填；多路并行时各管各文件，回填写各自工作树副本。

| 子项 | 状态 | 读数与备注 |
|---|---|---|
| S1 | 未开始 | |
| S2 | 未开始 | |
| S3 | 未开始 | |
