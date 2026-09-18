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

## 二、决策点（先定后做）

**D1：UNKNOWN 的处置**（本任务唯一的产品取舍）

| 选项 | 形态 | 说明 |
|---|---|---|
| A（维持放行） | 现状不动，只在 KDoc 写明「UNKNOWN = 放行」 | 预览场景「先能跑」的价值高于严格性；`?` 占位对用户可见且语义正确 |
| B（预览放行 / 执行收紧，推荐） | 预览保持放行；**执行与 EXPLAIN 前**对含 UNKNOWN 参数的语句给一次提示（复用 N-25 注入闸门的同门控形态，不新增弹窗） | 与「无界写判定」系列的 fail-closed 姿态一致；预览与执行的风险面本就不同 |
| C（一律挡） | UNKNOWN 参数不填就不让执行 | 与现状落差最大，可能打断大量正常使用 |

**建议 B**，但**须用户定案**——它引入行为变化（执行前多一道提示）。若用户选 A，则本任务只剩 T-PC2-01/02（补类型）两项。

## 三、方案

**T-PC2-01（BigInteger 补入数值族）**：`sql/ParamQuote.kt:17-20` 的 `NUMERIC_TYPES` 增 `"BigInteger"`（与 BigDecimal 同规）→ 经既有 `XmlParamTypeResolver.kt:133` 自动落 NUMBER，mock 分派与校验随之生效（`XmlPreviewModel.kt:162`、`SqlPreviewPanel.kt:3107-3117` 的 NUMBER 档）。**依据**：BigInteger 与 BigDecimal 同属 `java.math` 数值族，当前误落 UNKNOWN 属漏收（不是设计取舍）。

**T-PC2-02（UUID 分型）**：新增 UUID 的类别归属——**建议落 TEXT**（UUID 在 SQL 里以带引号字符串形态比较，`ParamQuote` 的引号语义与 TEXT 一致），并在 `XmlParamTypeResolver.kindOfTypeText`（`:127-139`）与 `kindOf`（`:114-150`）的 `isDtoLike` 排除规则（`:147-150`）中显式放行 `java.util.UUID`。若用户判定 UUID 应单列一类（如 `ParamKind.UUID`），则须评估枚举扩容对既有 `when` 分支的连带（`XmlPreviewModel.kt:157-162` 等）——**默认走 TEXT，不扩枚举**。

**T-PC2-03（XML 路径 paramN 推导，Z-17 门槛）**：`XmlParamTypeResolver` 增 paramN 推导，**采用 Z-17 的全或无门槛**——仅当**全部参数都有显式 `@Param`** 时才生成 `param1..paramN`（对齐 MyBatis `ParamNameResolver` 语义：一旦有未命名参数，MyBatis 也用位置名，但我方保守起见只在全显式时推导，与 zMyBatis 同款「宁缺勿错」）；规则来源带版本标注（参照 zMyBatis `GENERIC_ALIAS_RULE = "mybatis-3.5.19-param-name-resolver-generic"` 的做法，我方写 `mybatis-3.5.19-paramN-全或无`）。**复用**：注解路径 `AnnotationSqlPreviewService.kt:200-203` 的实现作参照，但门槛按本任务收紧（注解路径当前语义是否需要同步收紧——**先核实**，若其现状为「有 @Param 就补」则登记差异不擅改）。

**T-PC2-04（D1 选 B 时的执行闸门）**：含 UNKNOWN 参数的语句在**执行与 EXPLAIN** 前给一次提示（复用 N-25 注入闸门 `ffa8633` 的同门控形态：`executeSql()` 内联弹窗 + 面板内不再重复弹），文案说明「N 个参数未能识别类型，将以 `?` 占位提交」。**不新增弹窗形态**（沿用既有执行前确认的位置）。

**T-PC2-05（测试与文档）**：用例——①BigInteger 参数落 NUMBER 且 mock 为数值形态（先红后绿，钉住漏收修复）；②UUID 参数落 TEXT 且带引号；③XML 路径 paramN：全 `@Param` 时生成 `param1..N`、含未命名参数时不生成（门槛边界）；④（D1 选 B）含 UNKNOWN 时执行前提示一次、第二次不再提示；⑤既有 `XmlParamKindTest` 全绿（类别语义不回归）。文档：CHANGELOG + 变更记录 + 第八轮报告 Z-17/Z-19 落地备注。

## 四、验收标准

1. BigInteger 不再落 UNKNOWN（用例 1，先红后绿）。
2. UUID 落 TEXT 且引号语义正确（用例 2）。
3. XML 路径 paramN 严格按全或无门槛（用例 3 两态）。
4. D1 选 B 时：提示只出现一次且不阻断已确认的流程；选 A 时无行为变化。
5. 既有参数契约用例全绿（`XmlParamKindTest` 等）。
6. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工）

1. 真机 XML mapper 含 `BigInteger` 参数 → 参数行按数值档呈现（占位 `42`、非数字出警示）。
2. 真机含 `UUID` 参数 → 按文本档呈现、渲染带引号。
3. 真机多参数 mapper（全 `@Param`）→ 参数行含 `param1..N`；含未命名参数时不出现 paramN。
4. （D1 选 B）真机执行含 UNKNOWN 参数的语句 → 提示一次后不再重复。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| BigInteger 补入引发引号语义变化 | 原 UNKNOWN 带引号、改 NUMBER 后裸渲染 | 这是**修正**（数值不应带引号，与 N-26 的日期引号定案同向）；用例 1 钉住，CHANGELOG 明示行为变化 |
| UUID 归类的取舍 | 落 TEXT vs 新枚举 | 默认 TEXT（不扩枚举，避免 `when` 分支连带）；用户若要求单列再评估 |
| paramN 门槛与注解路径不一致 | 两条路径语义分叉 | T-PC2-03 明确「先核实注解路径现状」，若不一致则登记差异并说明理由，不擅改既有行为 |
| D1 选 B 引入打扰 | 执行前多一道提示 | 同 N-25 门控形态：同面板只提示一次；UNKNOWN 不常见，实际触发面小 |
| 与 T-TailSweep2 交叠 | 后者在 `xml/DynamicContext`/`converter/`/`config/`，本任务在 `xml/ParamKind`/`ui/XmlParamTypeResolver`/`sql/ParamQuote` | `xml/` 同包但**不同文件**；仍按并行铁律 check/register |

## 七、实施顺序

```
D1 决策（用户）→ T-PC2-01（BigInteger，独立小件）→ T-PC2-02（UUID）→ T-PC2-03（paramN，含注解路径现状核实）→ [T-PC2-04 仅 D1 选 B] → T-PC2-05（测试与文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| D1 | （决策后填） | 选 A/B/C 及理由 |
| T-PC2-01 | （实施后填） | 引号行为变化的 CHANGELOG 落点须回填 |
| T-PC2-02 | （实施后填） | UUID 归类定案须回填 |
| T-PC2-03 | （实施后填） | 注解路径现状核实结论与门槛是否一致须回填 |
| T-PC2-04 | （实施后填） | 仅 D1 选 B 时 |
| T-PC2-05 | （实施后填） | 门禁计数须回填 |
