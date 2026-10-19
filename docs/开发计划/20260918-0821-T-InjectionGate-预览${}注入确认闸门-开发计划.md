# T-InjectionGate 预览 ${} 注入确认闸门 — 开发计划

- 日期：2026-09-18 08:21
- 任务编号：T-InjectionGate（第七轮新建议 **N-25**，P1）
- 来源：第七轮报告 §7 zMyBatis 精华清单 **Z-2**——raw 字段强制勾选「I understand this value is inserted as raw SQL text」+「Why: …」溯源 tooltip（ContractParameterInputDialog.kt:98-107, :184-188）
- 性质：七环「无界写判定」叙事的交互面收尾——`${}` 是 SQL 注入面，写前预览对它只有提示没有闸门
- 实施分支：合流后自 master 拉任务分支 t-injection-gate（前置项 T-MergeDev2Master 策略决策）
- 验证基线：合流后 master 全量 testShards 绿（以合流时钉死计数为准）

---

## 一、背景与依据

zMyBatis（第七轮深度解剖）对 `${}` raw 插值的处理是「强制显式确认」：用户必须勾选理解声明才放行，且每个字段带溯源说明。本插件写前预览链对 `${}` 的现状（立项锚点核实，master 实测）是**零闸门**：`${}` 经 TokenParser 逐 token 走 OgnlEvaluator 求值，mock 绑定统一占位字符串 `"1"`（`XmlPreviewModel.kt:102-121`），替换成功后 SQL 中不留任何 `${}` 痕迹、面板无高亮无标注（唯一相关提示是求值失败时并入 warning 的 `⚠` 行，`SqlPreviewPanel.kt:2872-2887`）。执行侧唯一的确认弹窗是 DML 二次确认（`SqlPreviewPanel.kt:627-638`），`${}` 存在与否完全不参与判定；EXPLAIN 通道还显式免确认（`:697-706`），「复制可执行 SQL」旁路同样无检查（`CopyExecutableSqlAction.kt:31-64`）。

风险形态要点：`${}` 的风险不在「最终文本里还能看到 `${}`」（那只是求值失败残余），而在**「raw 拼接发生过且值是 mock/用户未确认」**——用户拿预览产物直接去执行时，raw 段已经带着 mock 值进入了可执行 SQL。闸门要拦的是这个时刻。

## 二、现状核实（master 实测，2026-09-18）

| 面 | 现状 | 位置 |
|---|---|---|
| `${}` 替换管线 | 复刻 MyBatis 3.5.19 BindingTokenParser，求值 null/失败 → 空串+记 error | `xml/TextSqlNode.kt:34-59` |
| mock 绑定 | 顶层变量统一占位 `"1"`，双写 `_parameter` 与顶层 bindings | `xml/XmlPreviewModel.kt:102-121` |
| 确认弹窗 | 仅 DML 二次确认，`executeSql()` 内联 `showYesNoDialog`，无确认抽象层 | `ui/SqlPreviewPanel.kt:627-638` |
| 执行入口 | 「执行 SQL」只在 `SqlPreviewDialog.createActions` 定义、回调 `panel.executeSql()`——弹窗与面板共用单点 | `ui/SqlPreviewDialog.kt:63-68`；`SqlPreviewPanel.kt:614` |
| 旁路 | EXPLAIN 显式免确认；复制可执行 SQL 无 `${}` 检查 | `SqlPreviewPanel.kt:697-706`；`ui/components/CopyExecutableSqlAction.kt:31-64` |
| 判定信号 | 解析期已收集 dollarTokens（`XmlPreviewModel.kt:195-201`，当前未对外暴露）；最终文本 regex `\$\{` 只能抓残余 | 同左 |

## 三、方案

**T-IG-01（渲染产物记录 raw 注入事实）**：`PreviewResult`/`XmlPreviewModel` 暴露「本次渲染发生过 `${}` raw 替换」信号——优先复用解析期 dollarTokens 暴露（改动最小：新增字段+透传），语义=`${` token 出现过即 true（无论替换成功与否）。不依赖最终文本 regex（会漏掉全部成功替换场景）。

**T-IG-02（执行闸门）**：`SqlPreviewPanel.executeSql()` 在 DML 分类判定之后追加 `${}` 检查：raw 注入事实为 true 且本会话未确认过时，弹确认框（复用 `showYesNoDialog` 形态与 warning 图标，文案走 bundle 新增双语键，说明「SQL 含 `${}` 原文拼接段（当前以模拟值填充），执行前请确认值的真实性」），NO 则 return。会话内确认一次后不重复弹（`@Volatile var` 于面板实例，随面板 dispose 释放）。弹窗克制红线：不新增弹窗形态，只在既有流程加一道门。

**T-IG-03（旁路覆盖）**：EXPLAIN（`explainSql`）与 CopyExecutableSqlAction 两个旁路同样接入信号——EXPLAIN 本就真实执行，必须同门控；复制链不做弹窗（复制不是执行），但在复制产物头部追加一行 `-- contains ${} raw segments (mock values)` 注释，让风险随产物走。

**T-IG-04（测试与文档）**：headless 面板测试（仿 `SqlPreviewPanelParamFieldTest` 形态）：含 `${}` 语句弹确认且 NO 中断、确认一次后不重复弹、无 `${}` 语句零影响、EXPLAIN 同门控、复制产物带标记注释。CHANGELOG [Unreleased] + 变更记录行 + 第七轮报告 Z-2 落地备注回填。

## 四、验收标准

1. 表驱动用例全绿：含 `${}` 弹门/确认放行/NO 中断/二次不弹；无 `${}` 行为逐字节不变（防回归断言）。
2. EXPLAIN 与复制旁路均被覆盖（含 `${}` 的 EXPLAIN 也弹门）。
3. 判定信号来自渲染期事实而非最终文本 regex（用「`${}` 成功替换为 mock 值」用例钉住）。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 含 `${tableName}` 的 mapper 预览 → 执行：应弹确认；确认后本会话内再执行同面板不重复弹。
2. 含 `${}` 语句的 EXPLAIN 按钮：应弹确认。
3. 复制可执行 SQL：产物头应带 raw 注释行。
4. 深浅色主题下确认框文案完整。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| dollarTokens 暴露改动面 | `XmlPreviewModel`/`PreviewResult` 新增字段涉及多层透传 | 字段只增不改，既有消费方零感知；透传链测试钉住 |
| 误弹烦扰 | 正常用 `${}` 写默认值的高级用户每次都要确认 | 会话级记忆一次确认；后续若反馈吵再评估设置开关（默认开） |
| Wrapper/JPA 链路无 `${}` | 该链路本无 `${}` 概念（`ExpressionEvaluator.kt:33-37` 统一降级 `?`） | 闸门只在 XML/注解预览链生效，属预期范围，文档写明 |

## 七、实施顺序

```
T-IG-01（raw 事实暴露）→ T-IG-02（执行闸门）→ T-IG-03（旁路覆盖）→ T-IG-04（测试与文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

（2026-09-18 实施，分支 t-injection-gate，基线 a842b47）

**T-IG-01（raw 注入事实暴露）**：`XmlPreviewModel` 新增 `hasDollarRawSegments`（`from` 内 collect 走查 TextSqlNode 时收集 `${` token，非空即 true；转义 `\${` 经 TokenParser 逃逸不计，与 T-DollarInjectMark 引擎口径一致），`XmlPreviewService.toResult` 透传 `PreviewResult.hasDollarRawSegments`（新增参数带默认值 false，既有构造点零影响）。XML 与注解两条预览链共用 toResult，一处透传全链受益。

**T-IG-02（执行闸门）**：`SqlPreviewPanel` 新增 `confirmDollarRawSegments(proj)`（判定 → YesNo 确认 → 会话记忆）与 `@Volatile var dollarRawConfirmedInSession`（面板实例级，随 dispose 释放）；`executeSql()` 在 DML 分类判定之后、快照登记之前调用闸门，NO 直接 return。标题沿用 `execute.confirm.dml`（同一「执行前确认」语义），消息走新键 `execute.confirm.dollarRaw.message`，`${}` 记号以 `DOLLAR_TOKEN` 作 {0} 参数传入（字面 `${}` 直接写进 bundle 会撞 MessageFormat 花括号解析，与 T-DollarInjectMark 同款处理）。面板另暴露 `hasDollarRawSegments()` 读口供复制链消费。

**T-IG-03（旁路覆盖）**：`explainSql()` 在方言判定通过后（真正要执行时）同门控——方言不支持已提前 return，未执行不弹；`CopyExecutableSqlAction` 不弹窗，含 raw 段时在产物头部追加 `DOLLAR_RAW_MARKER`（`-- contains ${} raw segments (mock values)`）注释行，无 raw 段产物逐字节不变（替换计数仍按未加标记的产物算，toast 语义不变）。

**T-IG-04（测试与文档）**：新增 `src/test/kotlin/.../ui/InjectionGateTest.kt` 14 例——信号口径 3 例（成功替换为 mock 值仍为真且产物无 `${` 残余／纯 `#{}` 与转义为假／非 XML 链恒假）、执行闸门 6 例（弹门且 NO 中止、确认放行且同面板不再弹、NO 后再执行仍弹、无 raw 段零影响、无 raw 段 DML 仍只弹既有二次确认、DML 与 raw 两门先后顺序）、EXPLAIN 2 例、复制链 3 例（标记常量文案、含 raw 段产物带标记、无 raw 段无标记）。确认框经平台 `TestDialogManager` 拦截（`Messages` 在单元测试模式直返注入答案，headless 零弹层），拦截器同时记录消息正文以断言「弹了几次／弹的是哪一道门」；「NO 中断」的可观测面 = 结果面板状态（放行才走 `resultPanel.showLoading()`，中止原样停留空态）。三处文档同步（本文件、CHANGELOG [Unreleased]、变更记录总册）。

**实施偏离与口径说明**

1. **信号语义取「解析期 token 出现过」而非「本次渲染实际替换过」**：计划 T-IG-01 明确选解析期（改动最小、与渲染状态无关）。副作用是保守方向的误报——`${}` 位于本次渲染未启用的 `<if>` 分支内时（开关关闭，产物里并无 raw 段）仍会弹门。真机若反馈吵，可改判渲染期计数（`RenderedXmlSql.dollarSubstitutions`，T-DollarInjectMark 已有）或加设置开关（默认开）。
2. **计划 §二 现状表一处与当前代码不符**：表称解析期 dollarTokens「当前未对外暴露」属实，但同表未记录 T-DollarInjectMark（2026-09-15 已交付）已在 `RenderedXmlSql.dollarSubstitutions` 暴露渲染期直替计数并被预览横幅消费（`SqlPreviewPanel.xmlDollarSubstitutions`）——即「渲染期事实」本已可得。本任务按计划口径新增解析期字段，未动既有计数（横幅语义零变化）。
3. **EXPLAIN 门控位置**：放在方言支持判定之后（而非方法入口）——方言不支持时 EXPLAIN 根本不执行，弹门属无谓打扰。
4. **`executeAdhocSql`（回滚弹窗执行链）未接闸门**：其 SQL 由 `RollbackSqlGenerator` 生成（不含 `${}` 语义），该链已有自己的显式确认（`dmlConfirmed` 参数），计划 T-IG-02/03 只点名 executeSql / explainSql / 复制三处。
5. **复制标记注释为固定英文行**：按计划原文实现（产物是 SQL 文本而非 UI 文案，标记需稳定可复现；未进 bundle，故不涉双语键）。

**真机待走查（§五，未执行）**：①含 `${tableName}` 的 mapper 预览 → 执行应弹确认、确认后同面板再执行不重复弹；②含 `${}` 语句的 EXPLAIN 应弹确认；③复制可执行 SQL 产物头带 raw 注释行；④深浅色主题下确认框文案完整。

