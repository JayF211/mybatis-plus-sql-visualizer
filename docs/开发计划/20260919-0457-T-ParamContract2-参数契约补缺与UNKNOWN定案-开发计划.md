# T-ParamContract2 参数契约补缺与 UNKNOWN 定案 — 开发计划

- 日期：2026-09-19 04:57
- 任务编号：T-ParamContract2（第八轮报告 §9.3 **N-30**，P2；原拟名「参数契约二期」——核实后改名，本任务不是从零而是「补缺 + 定案」，见 §一）
- 来源：第八轮报告 §7.2 zMyBatis 精华 **Z-17**（paramN 全或无推导门禁，`XmlMapperMethodParameterContractFactory.kt:219-236`）+ **Z-19**（Java 类型→输入形态契约表，`JavaParameterTypeContract.kt:6-77`）
- 性质：**参数面正确性补缺**——T-ParamTypeContract（N-26）已交付类型契约骨架，本任务收口两个漏收类型与一个纯决策题
- 实施分支：自 master 拉任务分支 t-param-contract-2
- 验证基线：master（testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿）

---

## 一、背景与依据（现状核实，含三处口径修正）

| # | 原建议口径 | 核实事实 | 影响 |
|---|-----------|---------|------|
| 1 | 「参数契约**二期**」（暗示从零扩建） | **骨架已落地**：`xml/ParamKind.kt:17-38` 六值枚举齐全（TEXT/NUMBER/TEMPORAL/COLLECTION/OBJECT/UNKNOWN）、`ParamTypeInfo(kind, nullable)` `:41-46`、派生引号 `quoteRequired` `:36`；解析入口 `ui/XmlParamTypeResolver.kt:60`（`resolveTypes`）/`:114`（`kindOf`）/`:127`（`kindOfTypeText`）；模型消费 `xml/XmlPreviewModel.kt:106`/`:112`/`:157-162`（按类别分派 mock 值）；已有测试 `xml/XmlParamKindTest.kt` | 本任务收窄为**补缺 + 定案** |
| 2 | 「补 UUID/BigInteger/BigDecimal 分型」 | **BigDecimal 已在册**（`sql/ParamQuote.kt:17-20` 的 `NUMERIC_TYPES` 含 `"BigDecimal"` → 经 `XmlParamTypeResolver.kt:133` 落 NUMBER）；**BigInteger 误落 UNKNOWN**（不在 `NUMERIC_TYPES`，且 `kindOf` 的 `isDtoLike` 把 `java.` 开头排除 → `:117` UNKNOWN）；**UUID 落 UNKNOWN**（全仓参数类型映射无 UUID） | 补 **BigInteger + UUID** 两个，不是三个 |
| 3 | 「paramN 泛化别名推导」（暗示全缺） | **注解路径已有**：`ui/AnnotationSqlPreviewService.kt:200-203` 生成 `param${index+1}`（KDoc `:177-181`：多参数或含 @Param 时补 `param1..N`）；日志路径亦有 `param1..N`（`log/LogEntryPreviewRecognizer.kt:23-25,36`）。**仅 XML mapper 路径缺**：`ui/XmlParamTypeResolver.kt:60-80` 只按 `@Param` 值/参数名建键，不产出 paramN | 补 XML 路径，**可复用注解路径实现作参照** |

**UNKNOWN 现状（决定第 4 项的性质）**：`#{...}` → `?` 是**无条件**替换、与类型无关（`XmlSqlRenderer.kt:80-87`）；mock 占位 `TEXT, UNKNOWN -> "1"`（`XmlPreviewModel.kt:162`）；`UNKNOWN.quoteRequired == true`（`ParamKind.kt:36`）；面板行尾校验**只对 NUMBER/TEMPORAL 出警示，UNKNOWN 恒 null**（`SqlPreviewPanel.kt:3107-3117`）；空值填充 `:3530`（`hasSignatureTypeInfo = kind != UNKNOWN`）。→ **UNKNOWN 当前 = 放行 `?` + 无提示 + 引号兜底 + 空值不填充；没有任何现存闸门**。

**@Param 读取链（既有，本任务不动）**：`XmlParamTypeResolver.kt:62-63`（`JavaPsiFacade.findClass`）→ `:64`（`findMethodsByName`）→ `:66-78`（参数遍历与类型分派、DTO 递归 `:90-100`）→ `:153-163`（`paramNameOf`：FQN 优先 `:155`、短名兜底 `:156`、无注解回退参数名 `:162`）。

## 二、决策点（D1 已定案）

**D1：UNKNOWN 的处置**（本任务唯一的产品取舍）

| 选项 | 形态 | 说明 |
|---|---|---|
| ~~A（维持放行）~~（**弃用**） | 现状不动，只在 KDoc 写明「UNKNOWN = 放行」 | 预览场景「先能跑」的价值高于严格性；`?` 占位对用户可见且语义正确 |
| **B（预览放行 / 执行收紧）— 已定案** | 预览保持放行；**执行与 EXPLAIN 前**对含 UNKNOWN 参数的语句给一次提示（复用 N-25 注入闸门的同门控形态，不新增弹窗） | 与「无界写判定」系列的 fail-closed 姿态一致；预览与执行的风险面本就不同 |
| ~~C（一律挡）~~（**弃用**） | UNKNOWN 参数不填就不让执行 | 与现状落差最大，可能打断大量正常使用 |

**D1 已定案（2026-09-19，用户认可本会话推荐）：选 B。** 备选 A/C 保留在表内并标记**弃用**（不删，留决策痕迹）。B 引入的行为变化（执行前多一道提示）须在 CHANGELOG 的 Changed 段明示。

## 三、方案

**T-PC2-01（BigInteger 补入数值族）**：`sql/ParamQuote.kt:17-20` 的 `NUMERIC_TYPES` 增 `"BigInteger"`（与 BigDecimal 同规）→ 经既有 `XmlParamTypeResolver.kt:133` 自动落 NUMBER，mock 分派与校验随之生效（`XmlPreviewModel.kt:162`、`SqlPreviewPanel.kt:3107-3117` 的 NUMBER 档）。**依据**：BigInteger 与 BigDecimal 同属 `java.math` 数值族，当前误落 UNKNOWN 属漏收（不是设计取舍）。

**T-PC2-02（UUID 分型）**：新增 UUID 的类别归属——**建议落 TEXT**（UUID 在 SQL 里以带引号字符串形态比较，`ParamQuote` 的引号语义与 TEXT 一致），并在 `XmlParamTypeResolver.kindOfTypeText`（`:127-139`）与 `kindOf`（`:114-150`）的 `isDtoLike` 排除规则（`:147-150`）中显式放行 `java.util.UUID`。若用户判定 UUID 应单列一类（如 `ParamKind.UUID`），则须评估枚举扩容对既有 `when` 分支的连带（`XmlPreviewModel.kt:157-162` 等）——**默认走 TEXT，不扩枚举**。

**T-PC2-03（XML 路径 paramN 推导，Z-17 门槛）**：`XmlParamTypeResolver` 增 paramN 推导，**采用 Z-17 的全或无门槛**——仅当**全部参数都有显式 `@Param`** 时才生成 `param1..paramN`（对齐 MyBatis `ParamNameResolver` 语义：一旦有未命名参数，MyBatis 也用位置名，但我方保守起见只在全显式时推导，与 zMyBatis 同款「宁缺勿错」）；规则来源带版本标注（参照 zMyBatis `GENERIC_ALIAS_RULE = "mybatis-3.5.19-param-name-resolver-generic"` 的做法，我方写 `mybatis-3.5.19-paramN-全或无`）。**复用**：注解路径 `AnnotationSqlPreviewService.kt:200-203` 的实现作参照，但门槛按本任务收紧（注解路径当前语义是否需要同步收紧——**先核实**，若其现状为「有 @Param 就补」则登记差异不擅改）。

**T-PC2-04（D1=B 的执行闸门，已定案实施）**：含 UNKNOWN 参数的语句在**执行与 EXPLAIN** 前给一次提示（复用 N-25 注入闸门 `ffa8633` 的同门控形态：`executeSql()` 内联弹窗 + 面板内不再重复弹），文案说明「N 个参数未能识别类型，将以 `?` 占位提交」。**不新增弹窗形态**（沿用既有执行前确认的位置）。

**T-PC2-05（测试与文档）**：用例——①BigInteger 参数落 NUMBER 且 mock 为数值形态（先红后绿，钉住漏收修复）；②UUID 参数落 TEXT 且带引号；③XML 路径 paramN：全 `@Param` 时生成 `param1..N`、含未命名参数时不生成（门槛边界）；④（D1=B）含 UNKNOWN 时执行前提示一次、第二次不再提示；⑤既有 `XmlParamKindTest` 全绿（类别语义不回归）。文档：CHANGELOG + 变更记录 + 第八轮报告 Z-17/Z-19 落地备注。

## 四、验收标准

1. BigInteger 不再落 UNKNOWN（用例 1，先红后绿）。
2. UUID 落 TEXT 且引号语义正确（用例 2）。
3. XML 路径 paramN 严格按全或无门槛（用例 3 两态）。
4. D1=B（已定案）：提示只出现一次且不阻断已确认的流程。
5. 既有参数契约用例全绿（`XmlParamKindTest` 等）。
6. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工）

1. 真机 XML mapper 含 `BigInteger` 参数 → 参数行按数值档呈现（占位 `42`、非数字出警示）。
2. 真机含 `UUID` 参数 → 按文本档呈现、渲染带引号。
3. 真机多参数 mapper（全 `@Param`）→ 参数行含 `param1..N`；含未命名参数时不出现 paramN。
4. （D1=B）真机执行含 UNKNOWN 参数的语句 → 提示一次后不再重复。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| BigInteger 补入引发引号语义变化 | 原 UNKNOWN 带引号、改 NUMBER 后裸渲染 | 这是**修正**（数值不应带引号，与 N-26 的日期引号定案同向）；用例 1 钉住，CHANGELOG 明示行为变化 |
| UUID 归类的取舍 | 落 TEXT vs 新枚举 | 默认 TEXT（不扩枚举，避免 `when` 分支连带）；用户若要求单列再评估 |
| paramN 门槛与注解路径不一致 | 两条路径语义分叉 | T-PC2-03 明确「先核实注解路径现状」，若不一致则登记差异并说明理由，不擅改既有行为 |
| D1=B 引入打扰 | 执行前多一道提示 | 同 N-25 门控形态：同面板只提示一次；UNKNOWN 不常见，实际触发面小 |
| 与 T-TailSweep2 交叠 | 后者在 `xml/DynamicContext`/`converter/`/`config/`，本任务在 `xml/ParamKind`/`ui/XmlParamTypeResolver`/`sql/ParamQuote` | `xml/` 同包但**不同文件**；仍按并行铁律 check/register |

## 七、实施顺序

```
D1 已定案（B）→ T-PC2-01（BigInteger，独立小件）→ T-PC2-02（UUID）→ T-PC2-03（paramN，含注解路径现状核实）→ T-PC2-04（D1=B，实施）→ T-PC2-05（测试与文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| D1 | —（决策已定，无代码提交） | **已定案 2026-09-19（用户认可本会话推荐）：选 B**——预览放行 + 执行与 EXPLAIN 前提示一次。理由：UNKNOWN 当前在执行链上既不校验也不填充，可能提交字面 `?`；预览与执行风险面不同。备选 A/C 保留并标记弃用 |
| T-PC2-01 | `95e7445` | BigInteger 补入 `ParamQuote.NUMERIC_TYPES`（`sql/ParamQuote.kt`），经既有 `kindOfTypeText`（NUMBER 判定复用 `ParamQuote.decide`）自动落 NUMBER。**引号行为变化（修正向）**：BigInteger 参数由「带引号」改「裸渲染」，CHANGELOG Changed 段落点见下方「待派活方接线/落笔」——`CHANGELOG.md` 与 `docs/变更记录.md` 均不在本任务写集内（后者派活方明令不改），故未落笔，文案已备 |
| T-PC2-02 | `b99381c` | UUID 归类**定案 = TEXT**（不扩枚举，避免 `XmlPreviewModel` 等既有 `when` 分支连带）。**口径修正**：计划 §三 写「在 `kindOfTypeText` 与 `kindOf` 的 `isDtoLike` 排除规则中显式放行」——经核实 `isDtoLike` 已排除 `java.*`，UUID 本就不会走 DTO 分支，真正缺口只在 `kindOfTypeText` 的文本映射，故只改一处；连带覆盖 DTO 字段与注解路径（同用 `typeInfoOf`） |
| T-PC2-03 | `b99381c` | XML 路径 paramN 按 Z-17 **全或无门槛**落地：全部参数都有显式 `@Param`（值非空白）才补 `param1..N`（单参数全显式亦补，与 MyBatis `ParamNameResolver` 的 `ParamMap` 行为一致）；含未命名参数整体不推导（宁缺勿错）。规则标注 `mybatis-3.5.19-paramN-全或无`。**注解路径现状核实结论**：`AnnotationSqlPreviewService.paramTypes` 门槛为「多参数或含 @Param 就补」，比 XML 路径宽一档——**登记差异、不擅改**（其键序已被 `XmlSqlCompletionContextResolver` 的补全上下文消费，收紧会连带补全候选变化），KDoc 与上方差异登记同步 |
| T-PC2-04 | `f5c43b1` | **本批只交付模型侧信号**（`XmlPreviewModel.unknownParamSources(sources)`，去重保序）——判定口径刻意收窄为「在 `paramTypes` 中有条目且类别 UNKNOWN」，**无类型信息（键不在 `paramTypes`）不计入**：与 T-PT-02「无类型信息不提示」同向，也避免「mapper 接口解析不到 → 每次执行都提示」的噪音面（若派活方判定无类型信息也应计入，改一处谓词即可）。**面板侧接线（executeSql/explainSql 同门控弹一次 + bundle 两键）不在写集内，未做**——现成方案见下方「待派活方接线/落笔」，越界未动 |
| T-PC2-04（面板侧接线） | `acbcbcc` | **遗留项补齐**（本工作树 `mpsv-r8j`，分支 `t-r8-pc2-04-panel`，基点 master `1aba7a0`）：按下方「待派活方接线/落笔」第 1 项的材料落地 `confirmUnknownParams` + `executeSql()`/`explainSql()` 两调用点 + bundle 双键；新增 `ui/UnknownParamGateTest` 6 例钉「提示只出现一次且不阻断」（§四验收 4）。细节与漂移见下节 |
| T-PC2-05 | `f5c43b1` | 用例落两处：`xml/XmlParamKindTest`（纯逻辑快测桶，6→9 例）覆盖 BigInteger/UUID 类型文本映射与 `unknownParamSources` 五态口径；`heavy/HeavyXmlParamTypeResolverTest`（真 PSI，9→12 例）覆盖 BigInteger/UUID 顶层参数与 DTO 字段的类别与引号、paramN 门槛四态、`param1.字段` 前缀兜底落 OBJECT。**金样调整**：X8-1/X8-2 的 `mapOf("map" to true)` 因 T-PC2-03 收进 `param1`，断言意图（键=@Param 值、无实体字段混入）不变。**先红后绿实测**：撤下 T-PC2-01/02 两处源码修复重跑 `testFast`，恰 `BigInteger归数值族_不再落UNKNOWN` 与 `UUID归文本档_带引号` 两例红、其余 7 例绿 |

### 门禁读数（2026-09-19，本工作树 mpsv-r8-pc2）

| 门禁 | 读数 | 备注 |
|------|------|------|
| 定向（`testFast --tests XmlParamKindTest`） | 9 例 / 0 失败 | 先红态 9 例 2 失败（上表 T-PC2-05） |
| 定向（`test --tests HeavyXmlParamTypeResolverTest`） | 12 例 / 0 失败 | 真 PSI，含新 3 例 |
| 全量串行（`./gradlew test`） | **5182 例 / 0 失败 / 0 错误 / 6 跳过** | 权威读数（§四验收 6） |
| 全量并行（`testShards`） | 两轮均 5182 例，失败 5 例 / 1+1 例，**失败集两轮完全不同**且全在 `log/`·`update/` 面板面（`AlreadyDisposedException` / MultiHostInjector EP 快照断言）——单类隔离复跑全绿，判定为并发负载下的既有 flake，与本任务改动无关 | 并行轮次受同机四路会话抢核放大 |
| `testDatabase` | **环境受阻未验证**：任务在跑测前失败于 `NoSuchFileException: ~/.gradle/caches/9.7.0/transforms/cfd911…/transformed/ideaIU-2024.3.7.1-win/bin/idea64.exe.vmoptions`（该共享 transform 目录 `bin/` 缺失，同机并行会话正在使用同一 IU-2024.3.7.1 分发），重试两次同错 | 本任务不触达 Database 链；且四类 DB 集成用例在本机历来绿跳 |

### 面板侧接线已补齐（T-PC2-04 遗留项，2026-09-19，工作树 `mpsv-r8j` / 提交 `acbcbcc`）

| 项 | 内容 |
|---|---|
| 落点 | `ui/SqlPreviewPanel.kt`：①字段区新增 `@Volatile private var unknownParamsConfirmedInSession`（紧邻 `dollarRawConfirmedInSession`，语义同款）；②`confirmDollarRawSegments` 之后新增 `confirmUnknownParams(proj)`；③`executeSql()` 的 `confirmDollarRawSegments` 之后（快照登记/执行之前）；④`explainSql()` 的 `confirmDollarRawSegments` 之后（`resultPanel.showLoading()` 之前） |
| bundle | `execute.confirm.unknownParams.message`（en/zh 各一键，共两键）——文案与 §八 建议一致，仅按同组三条确认键的既有排版加了 `\n\n` 段间换行（`有 {0} 个参数未能识别类型，将以 ? 占位提交。\n\n确认继续执行？`） |
| 测试 | 新增 `src/test/kotlin/.../ui/UnknownParamGateTest.kt`（6 例）：含 UNKNOWN 弹门且 NO 中止 / 确认后同面板不再弹 / NO 后再执行仍弹 / 无类型信息（接口不可解析）零影响 / EXPLAIN 同门控 / 执行与 EXPLAIN 共用一次性记忆。后续 `f14e064` 补一条 MessageFormat 代入断言（`{0}` 必须真的被代入——键缺失/模板异常时 `message()` 兜底返回原文会原样上屏，`BundleParityGuardTest` 只查占位符形态不查代入是否发生） |

**与 §八 材料的漂移**：计划给的两个调用点位置与 `confirmDollarRawSegments` 锚点在当前 master 上**未漂移**（`confirmDollarRawSegments` 仍在 861-873、`executeSql` 调用点 911、`explainSql` 调用点 1064，材料可直接套用）；`SqlPreviewPanel.kt:3107-3117`（行尾校验只覆盖 NUMBER/TEMPORAL）亦仍准确。§一 的两处行号引用有轻微漂移：`XmlPreviewModel.kt:162`（mock 占位 `TEXT, UNKNOWN -> "1"`）实为 **:176**（T-PC2-04 自身新增 `unknownParamSources` 使其下移 14 行）、`SqlPreviewPanel.kt:3530`（`hasSignatureTypeInfo`）实为 **:3529**。

**夹具形态偏离建议**（计划建议「照 `InjectionGateTest` 形态」）：`InjectionGateTest` 的 XML 夹具走生产链 `XmlPreviewService.preview(mapper, id)`，但**Light 沙盒下 mapper 接口跨文件 resolve 恒 null**（`HeavyXmlParamTypeResolverTest` KDoc 已载明），故经生产链构造出的模型 `paramTypes` 恒空、永远走不到 UNKNOWN 分支——新测试改为**直构模型**（`XmlMapperParser().parse` + `XmlPreviewModel.from(..., paramTypes = …)`，即 `XmlParamKindTest` 同款），参数类型条目的产出链（PSI → `paramTypes`）仍由 `HeavyXmlParamTypeResolverTest` 与 `XmlParamKindTest` 分别钉住，本类只钉面板侧接线。

**先红后绿实测**：接线前先落测试跑 `test --tests UnknownParamGateTest` → **6 例 5 失败**（唯一绿的是「无类型信息零影响」——非回归例，两态皆绿）；接线 + bundle 双键后同命令 → **6 例 / 0 失败 / 0 错误**。

**门禁读数**（本工作树 `mpsv-r8j`，`-Pkotlin.compiler.execution.strategy=in-process`）：

| 门禁 | 读数 |
|---|---|
| 定向（`test --tests UnknownParamGateTest`） | 6 例 / 0 失败 / 0 错误（先红态 6 例 5 失败） |
| bundle 一致性守卫（`test --tests BundleParityGuardTest`） | 5 例 / 0 失败（中英 key 全等 + 占位符规范 + 源码字面量 key 存在性三项守卫全绿） |
| 模态弹窗白名单（`test --tests BannedApiSourceScanTest`） | 22 例 / 0 失败（`SqlPreviewPanel.kt` 已在白名单内，新增弹窗点未越界） |
| 回归（`test --tests InjectionGateTest` / `--tests XmlParamKindTest`） | 14 例 / 0 失败；9 例 / 0 失败 |

**口径说明（登记，供派活方核）**：`unknownParamsConfirmedInSession` 为**执行与 EXPLAIN 共用**的单例记忆（两条入口是同一道门，与 T-InjectionGate 的 `dollarRawConfirmedInSession` 被 `executeSql`/`explainSql` 共用的既有姿态一致）；若派活方要求两条入口各自记忆，改字段为两个即可。

### 待派活方接线/落笔（越界项，本批未做）

1. **T-PC2-04 面板侧接线**——**已由上节补齐（`acbcbcc`，本工作树 `mpsv-r8j`）**，原材料留档如下（按 N-25 `ffa8633` 同门控形态）：

```kotlin
// SqlPreviewPanel：字段区
/** T-PC2-04（D1=B）：本面板实例内已确认过含 UNKNOWN 参数执行（会话级记忆）。 */
@Volatile private var unknownParamsConfirmedInSession = false

// SqlPreviewPanel：confirmDollarRawSegments 之后新增
private fun confirmUnknownParams(proj: Project): Boolean {
    val unknown = current?.xmlModel
        ?.unknownParamSources(current?.unresolvedParams.orEmpty())
        .orEmpty()
    if (unknown.isEmpty() || unknownParamsConfirmedInSession) return true
    val confirm = Messages.showYesNoDialog(
        proj,
        MpSqlVisualizerBundle.message("execute.confirm.unknownParams.message", unknown.size),
        MpSqlVisualizerBundle.message("execute.confirm.dml"),
        Messages.getWarningIcon(),
    )
    if (confirm != Messages.YES) return false
    unknownParamsConfirmedInSession = true
    return true
}
// 调用点：executeSql() 的 confirmDollarRawSegments 之后、explainSql() 的 confirmDollarRawSegments 之后
```

   bundle 两键（中英各一，`BundleParityGuardTest` 自动校验）：`execute.confirm.unknownParams.message` = `有 {0} 个参数未能识别类型，将以 ? 占位提交。确认继续执行？`。
   测试建议：照 `InjectionGateTest` 形态（`TestDialogManager` 拦截确认框 + 结果面板状态为中止观测面）——含 UNKNOWN 弹门且 NO 中止 / 确认后同面板不再弹 / 无 UNKNOWN 零影响 / EXPLAIN 同门控。

2. **CHANGELOG 落笔**（`CHANGELOG.md` 不在写集内，未动）——`[Unreleased]` 的 `### Changed` 段建议追加：

```
- **参数类型契约补缺（T-ParamContract2）**：`BigInteger` 参数此前被误判为未知类型（带引号 + 无校验），现归数值族裸渲染（`java.math` 数值族漏收修正）；`java.util.UUID` 归文本档（带引号），XML mapper 多参数在全参数都有 `@Param` 时补 `param1..N` 位置别名
```

3. **第八轮报告 Z-17/Z-19 落地备注**与 `docs/变更记录.md`：按派活方统一收口（不在本任务写集）。
