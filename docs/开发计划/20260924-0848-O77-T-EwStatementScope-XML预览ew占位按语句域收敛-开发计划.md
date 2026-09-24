# O-77 T-EwStatementScope XML 预览 ew 占位按语句域收敛 — 开发计划

- **编号**：O-77（占号前已实测：`git log master --oneline` 顶=`2e4aa83`，`docs/开发计划/` 当日已有 0145=O-76 / 0240=T-TestPerf4 / 0442=O-74 / 0633=批次 / 0839=T-PerfSweep8，后者编号走 T-PS8-xx 不占 O 号；台账快照「下一号 O-77」无冲突）
- **任务名**：T-EwStatementScope
- **日期**：2026-09-24
- **状态**：待用户确认，未动码
- **来源**：用户问题反馈——demo 仓 `OrderMapper.selectOrdersByCondition`（无 ew 参数的普通 XML select）预览时出现「方法 … 无 Wrapper 参数（ew），无法展开 MP 占位」红色警示，用户预期该语句不该提示 ew 相关内容
- **基线**：master=`2e4aa83`（20260924 实测；相关四文件 master 与 dev 零差异，`git diff master dev --stat` 为空）

## 一、问题与复现

demo 仓 `mybatis-plus-demo/src/main/resources/mapper/OrderMapper.xml` 同文件四条语句：`selectByCustomSqlSegment` 与 `selectOrderWithUserByEw`（第 8、16 行）带 `${ew.customSqlSegment}`；被预览的 `selectOrdersByCondition`（第 20~37 行）通篇没有任何 `${}` 占位，接口方法参数为三个 `@Param` 标量，无 Wrapper。

在 gutter 或预览入口打开第三条语句时，面板顶部出现两条红色警示：

1. `⚠ 方法 selectOrdersByCondition 无 Wrapper 参数（ew），无法展开 MP 占位`（带复制按钮）
2. `⚠ 预览未完整还原 — … 可检查参数值或调整条件勾选后点「刷新」重试。`（i18n key `panel.banner.renderFailed`，`MpSqlVisualizerBundle_zh.properties:932`）

SQL 本身渲染正常（动态条件、foreach 都对），警示属于误报。

## 二、根因分析

警示文案唯一产出点在 `XmlEwResolver.kt:65`，其触发前提链（全部已读码核实）：

1. `XmlPreviewService.kt:50`：`preview()` 对选中的 statement 调 `resolveEwPlaceholders(fileText, parsed.namespace, statement.id, project, warnings)`；
2. `XmlPreviewService.kt:79`：`val placeholders = XmlEwExpander.placeholders(fileText)` ——**扫描范围是整个 mapper 文件文本，不是当前预览的语句**；
3. 同文件他语句（第 8、16 行）的 `${ew.customSqlSegment}` 经 `(variable, kind)` 去重后剩一个 `ew` 占位 → 列表非空，通过 `XmlEwResolver.resolve` 的 `placeholders.isEmpty()` 早退（`XmlEwResolver.kt:45`）；
4. `XmlEwResolver.kt:56`：按当前 statementId 找到方法 `selectOrdersByCondition`（三参数全 `@Param` 标量）→ `findWrapperParam` 返回 null → `XmlEwResolver.kt:65` 拼出警示「方法 selectOrdersByCondition 无 Wrapper 参数（ew），无法展开 MP 占位」。警示文本＝当前语句 id ＋ 他语句扫来的占位变量名，与截图逐字吻合；
5. warning 并入 `PreviewResult.warning`（`XmlPreviewService.kt:139`）→ `SqlPreviewPanel.kt:4311` 以 `panel.banner.renderFailed` 渲染红色横幅。

一句话根因：**MP 占位的识别域（全文）与解析域（单语句）不一致**——占位是从整个文件扫的，方法与警示却按当前语句关联，他语句的 ew 占位被错误记账到本语句头上。

### 同族缺陷（同一根因的第二、第三形态，随本任务一并收敛）

- **跨语句变量污染**：预览**带** ew 的语句时，他语句的不同变量占位（如 `${ew2.customSqlSegment}`）也会被一并 resolve 到本语句方法上——轻则误报「无 Wrapper 参数（ew2）」，重则把 `ew2` 错配到本方法的 wrapper 参数/调用处。demo 里两语句变量同名（ew）去重后只现第一形态，变量名不同的真实仓可复现第二形态。
- **跨命名空间 include 漏检**：`XmlIncludeExpander.expandRecursive:68` 注入的外部片段若含 ew 占位，该占位不在本文件 `fileText` 里，现行全文扫描漏检（不报错也不展开）。按语句域扫描（在 include 展开之后取语句文本）反而能覆盖，属顺带修复（见 P3）。

## 三、影响面

- **引入提交**：`3959a8e4`（M26-B 占位展开特性）；`git tag --contains` 实测 `2026.8.15` 起全部 tag 包含 → **发布版 2026.9.14 与所有本地构建均受影响**，非新回归。
- **波及入口**（生产调用 `XmlPreviewService.preview` 的全部路径，已 grep 实测）：SQL 预览弹窗/面板（`SqlPreviewPanel` 红横幅）、两个 gutter marker（`XmlSqlGutterLineMarkerProvider` / `PagesXmlGutterLineMarkerProvider`）、MCP render 工具（`McpSourceLocator` 同链路，warning 文本随工具出参外泄）、`ConvertToSqlAction`。
- **触发条件**：同一 mapper 文件内既有 wrapper 风格语句又有普通语句——MP 项目混写一个文件的常见形态，命中面不小。

## 四、修复方案

**方案 A（采纳）：语句域扫描。** 占位识别域对齐解析域，从「整文件文本」收窄为「当前语句文本」。

1. `XmlStatement`（`XmlMapperParser.kt:10`）增加尾置默认字段 `rawText: String? = null`。`src/test` 对 `XmlStatement` 无构造/等值引用（实测仅引 `XmlStatementKind`），另两处构造点（`AnnotationSqlParser` / `ConvertToSqlAction`）走默认值不受影响；数据类新字段影响 equals/copy，实施时 grep 确认无等值比较依赖。
2. `XmlMapperParser.parse` 在 `XmlIncludeExpander.expand(...)` 之后构造 `XmlStatement` 时以 `element.textContent` 填充。取 `textContent` 的理由：含全部后代文本与 CDATA、DOM 已解码实体（占位正则 `\$\{(\w+)\.(customSqlSegment|sqlSelect|sqlSet)\}` 不受转义影响）；且 `parse()` 时序是先就地展开 include 再建 statement（`XmlMapperParser.kt:71-72`），同文件 `<sql>` 片段与跨命名空间片段带入的占位一并计入。
3. `XmlPreviewService.preview` 改传 `statement.rawText`；`resolveEwPlaceholders` 参数 `fileText` 更名 `statementText`，null 时回落 `fileText` 保持旧路径行为不变。`XmlEwResolver` 本身不动——占位列表为空即静默早退的既有语义（`:45`）正是普通语句应有的行为。

**否决项**：

- 在 `XmlEwResolver.resolve` 内做占位归属过滤——归属判定仍需语句文本，等于绕路实现方案 A，还多传一层上下文；
- 只压制「无 Wrapper 参数」警示、保留全文绑定——跨语句 mock 错配与错误 wrapper 参数匹配的形态仍在，治标。

**改动面**：1 个数据类默认字段 + 解析器 1 处赋值 + 服务层 2~3 行调用点。`XmlEwExpander` / `XmlEwResolver` 零改动。

## 五、待验证前提（实施者先证伪再动手）

- **P1**：`element.textContent` 在 include 展开后取值包含片段文本。代码读查支持（`parse()` 先 `expand` 后构造，`expandRecursive` 就地改 DOM），但须以单测实证：语句内 `<include refid>` 同文件片段带 `${ew...}` 时语句域扫描命中。
- **P2**：`AnnotationSqlParser` / `ConvertToSqlAction` 构造的 `XmlStatement`（`rawText=null`）若进入 `resolveEwPlaceholders`，回落 `fileText` 后与现状逐字节一致。实施时 grep 调用图确认；本任务不追求这两路的收敛，只要求不回归。
- **P3**：跨命名空间 include 注入的占位由「漏检」转「检出」是行为改善，但纯文本测试环境难造（需 PSI 解析外部文件），允许登记为真机走查项不阻塞本任务；若实施中发现该路径解析时序与推断不符（如外部片段未就地展开），以实测为准收窄声明。
- **P4**：`SqlPreviewPanel` 对 warning 的展示逻辑无「依赖 ew 警示存在」的隐含分支。实施时确认 `:4311` 附近 `currentWarning` 的空值分支即可。

## 六、实施步骤

1. **测试先行（先红）**：新增回归用例（建议落在 `XmlPreviewServiceTest` 或新增同域测试类）：
   - 双语句 XML（语句 A 带 `${ew.customSqlSegment}`、语句 B 不带）：预览 B 断言无任何 ew 相关 warning；预览 A 断言展开照旧；
   - 不同变量名同族用例（A 用 `ew`、B 用 `ew2`）：互不误报；
   - include 带入占位用例（`<sql id>` 片段 + `<include>`，验 P1）。
2. 生产代码按 §四实施（`rawText` 字段 → 解析器赋值 → 服务层调用点）。
3. **反向验证**：临时把扫描范围改回 `fileText` → 新增用例必须转红，贴「摘掉修复→红 / 恢复→绿」两次读数（守卫类改动反向验证纪律）。
4. 定向回归：`XmlEwResolverTest`、`XmlEwExpanderTest`、`SqlPreviewServiceTest`、`GutterPreviewConsistencyTest` 中 ew 相关用例全绿。
5. **验证范围口径**：纯逻辑改动，跑 `testFast` + 上述定向类即可；不跑全量、不跑构建校验、不 `clean`、不 `--stop`（构建三档分层定例）。跑 gradle 前照例 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`。
6. 交付纪律：子代理实施则禁碰变更记录/CHANGELOG/MEMORY 三总册，收口由主控统一写；每完成一件可提交的立即提交。

## 七、验收标准（用户可见判据）

1. demo 仓 `OrderMapper.xml` 预览 `selectOrdersByCondition`：无任何红色警示，SQL 渲染与现在一致（用户截图场景复测）；
2. 预览 `selectByCustomSqlSegment` / `selectOrderWithUserByEw`：ew 展开行为与既有警示语义不变；
3. MCP render 工具对无占位语句的出参不再携带 ew 警示文本；
4. gutter 入口同样不再误报（同一链路，抽查一次）。

## 八、风险与回滚

- 风险低：改动面小且带默认值回落；主要回归风险在 P1/P2 两前提被证伪的形态，均以单测兜底。
- 回滚：单提交 revert 即可。

## 九、实施记录

（待实施回填。）
