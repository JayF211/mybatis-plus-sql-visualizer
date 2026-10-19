# T-ParamTypeContract 参数面板类型契约升级 — 开发计划

- 日期：2026-09-18 08:23
- 任务编号：T-ParamTypeContract（第七轮新建议 **N-26**，P1~P2）
- 来源：第七轮报告 §7 zMyBatis 精华清单 **Z-3+Z-4+Z-13**——方法签名推断 `ExpectedInputType` + 严格解码器（JavaAnnotationParameterContractFactory.kt:238-298）、foreach/对象参数占位提示启发式（ParameterInputDialog.kt:234-244）、空输入策略设置与执行后复制（ZMyBatisSettings.kt:32-41）
- 性质：参数面板从「猜」升级为「按签名」——mock 值分派、输入校验、占位提示三类体验共建
- 实施分支：合流后自 master 拉任务分支 t-param-type-contract（前置项 T-MergeDev2Master 策略决策）
- 验证基线：合流后 master 全量 testShards 绿（以合流时钉死计数为准）

---

## 一、背景与依据

zMyBatis 的参数输入按方法签名推断期望类型并严格解码（错误带 offset），foreach/集合参数按命名启发式给占位提示。本插件参数面板（立项锚点核实，master 实测）的读取链**已经存在**——`XmlParamTypeResolver` 走 namespace → `JavaPsiFacade.findClass` → 按 statement id 找方法 → 参数列表+类型，注解链 `@Param` 也已解析（含 param1..N 补齐）——但类型信息目前只被加工成一个布尔映射 `paramQuotes`（数值类不加引号、其余加引号），输入控件是清一色 `JBTextField` 无类型校验无日期控件，mock 值不分类型统一 `"1"`，占位提示靠 tooltip 不靠 emptyText。类型契约任务的实质是**把现有 `Map<String, Boolean>` 契约升级为类型类别契约**，读取代码全部复用。

## 二、现状核实（master 实测，2026-09-18）

| 面 | 现状 | 位置 |
|---|---|---|
| @Param 解析 | 已解析：`@Param` 值 > 实际参数名；多参补 param1..N | `ui/AnnotationSqlPreviewService.kt:174-198` |
| 签名读取 | namespace → findClass → 按 id 找方法 → 参数列表+类型 | `ui/XmlParamTypeResolver.kt:32-52` |
| 类型消费 | 仅一处：`paramQuotes` 布尔引号映射（`ParamQuote.decide`：数值类 false 其余 true，日期无特判落引号档） | `XmlParamTypeResolver.kt:47-65`；`sql/ParamQuote.kt:11-37`；消费 `xml/XmlPreviewModel.kt:68-83` |
| 输入控件 | 每参数一行 `#source` 标签 + 纯 `JBTextField`，无校验/日期控件/formatter | `ui/SqlPreviewPanel.kt:2434-2454` |
| 提示 | 无 emptyText，仅 tooltip | `SqlPreviewPanel.kt:2440,2534-2536` |
| mock 策略 | 顶层变量统一 `"1"`；foreach 默认 `MockIterable(2)` | `xml/XmlPreviewModel.kt:102-121,125` |
| 面板测试 | 极薄：2 个用例（tooltip/caret 细节）；类型解析有 Heavy 测试 | `ui/SqlPreviewPanelParamFieldTest.kt:35,51`；`heavy/HeavyXmlParamTypeResolverTest.kt` |
| 架构约束 | `XmlPreviewModel` 所在 xml 层刻意零 PSI/IDE 依赖（独立单测） | `XmlPreviewModel.kt:41` 注释 |

## 三、方案

**T-PT-01（契约升级：布尔 → 轻量类型类别）**：`XmlParamTypeResolver` 输出从 `Map<String, Boolean>` 扩为类型类别枚举 `ParamKind { TEXT, NUMBER, TEMPORAL, COLLECTION, OBJECT, UNKNOWN }`（含 nullable 标记）， PSI 类型映射规则：String/CharSequence→TEXT、整型浮点族+BigDecimal→NUMBER、`java.time.*`+`java.util.Date`→TEMPORAL、List/Collection/数组→COLLECTION、DTO/Map→OBJECT。`paramQuotes` 布尔通道保留为派生视图（NUMBER→false 其余 true），既有消费方 `quoteFor` 零改动。注解侧 `paramNames` 同步扩为名字+类别对。

**T-PT-02（输入校验与占位提示）**：`buildParamRow`（`SqlPreviewPanel.kt:2434`）按类别挂 emptyText（NUMBER→`数字，如 42`、TEMPORAL→`日期，如 2026-01-01 12:00:00`、COLLECTION→`[1, 2, 3]`、OBJECT→`{"id": 1}`——zMyBatis 命名启发式并入 UNKNOWN 兜底：参数名 s 结尾（排除 status/address 等）或含 list/ids → 数组提示）；NUMBER 输入非数字、TEMPORAL 非可解析日期时行尾淡色警示标（不阻塞渲染——校验是提示不是闸门，渲染链 `renderValue` 行为不动）。日期控件（DatePicker）列为可选增强，v1 只做文本+校验，避免新增控件形态拉大 diff。

**T-PT-03（mock 分派升级）**：类型类别以**字符串/枚举形式**传入 xml 层（`XmlPreviewModel.mockBindings`），mock 生成按类别分派：NUMBER→`1`、TEMPORAL→`'2026-01-01 00:00:00'`、TEXT 维持、COLLECTION 维持 `MockIterable(2)`、OBJECT 维持 MockObject——严守「xml 层零 PSI 依赖」约束（类别枚举定义放 xml 层，psi 层只做映射，`XmlPreviewModelTest` 等独立单测不受影响）。

**T-PT-04（空输入策略设置）**：设置页参数分组新增「空参数填充策略」单选（NULL 字面量 / 空字符串，默认 NULL），渲染回填时空输入按策略出 `NULL`/`''`。不引入「执行后自动复制」（与既有复制链交互未验证，登记观察）。

**T-PT-05（测试与文档）**：面板级 headless 测试（emptyText 按类别渲染、NUMBER/TEMPORAL 校验警示、mock 分派按类别出值、空输入策略两档）；`HeavyXmlParamTypeResolverTest` 扩类别映射用例；CHANGELOG [Unreleased] + 变更记录行 + 第七轮报告 Z-3/Z-4/Z-13 落地备注回填。

## 四、验收标准

1. 类别映射 Heavy 用例全绿（含日期无特判修复：TEMPORAL 引号行为显式定案）；`quoteFor` 派生通道行为与升级前逐字节一致（防回归断言）。
2. xml 层单测保持零 PSI 依赖可独立跑（testFast 通道继续覆盖该层）。
3. 面板 headless 用例全绿；无类型信息（UNKNOWN）时行为与现状一致。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 含 Integer/Date/List/DTO 参数的 mapper：emptyText 分类别正确、mock 值分派正确。
2. NUMBER 参数输入 `abc`：行尾警示出现但预览仍可渲染。
3. 设置页切空输入策略：渲染回填即时生效。
4. 深浅色主题下警示标可见性。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| PSI 类型映射盲区 | 泛型嵌套/自定义类型别名/ Kotlin 类型呈现形态 | 映射不到一律 UNKNOWN（行为=现状），宁缺勿错 |
| 契约双形态过渡期混乱 | paramQuotes 布尔与新类别并存 | 布尔通道显式标注为派生视图，T-PT-01 一次交付不留双轨长期并存 |
| 面板行构建点唯一 | `buildParamRow` 是全部参数行的唯一构建点，改动集中 | 集中是优点；headless 测试覆盖四类别行形态 |

## 七、实施顺序

```
T-PT-01（契约升级）→ T-PT-03（mock 分派）→ T-PT-02（校验与占位）→ T-PT-04（空输入策略）→ T-PT-05（测试与文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

- 实施分支：t-param-type-contract（git 工作树 mpsv-ptc，基线 a842b47 = dev 合流后 master）。
- 交付范围：T-PT-01 ~ T-PT-05 全部条目，测试与文档同步。

### T-PT-01 契约升级（布尔 → 类型类别）

- 新增 `xml/ParamKind.kt`：`ParamKind { TEXT, NUMBER, TEMPORAL, COLLECTION, OBJECT, UNKNOWN }`
  + `ParamTypeInfo(kind, nullable)`。**枚举刻意落 xml 层**（`XmlPreviewModel` 及其单测零 PSI/IDE 依赖的架构约束不变），psi 层只做映射。
- `XmlParamTypeResolver`：新增 `resolveTypes`（主输出）与内部映射 `kindOf/kindOfTypeText/typeInfoOf`；
  `resolveQuotes` 降为派生包装（`kind.quoteRequired`），既有 Heavy 用例零改动。
- `XmlPreviewModel`：新增 `paramTypes` 字段与 `paramTypeFor/kindFor`（精确匹配优先、前缀兜底，与 `quoteFor` 同规则）；
  `from(...)` 新增 `paramTypes` 参数，非空时 `paramQuotes` 由其派生（**布尔通道 = 派生视图**，`quoteFor` 实现零改动）。
- 映射清单：String/CharSequence→TEXT；整型浮点族原语与包装类 + BigDecimal→NUMBER（判定复用 `ParamQuote.decide`，杜绝两份数值名单漂移）；
  `java.time.*` 与 `java.util.Date`（简单名 Date/Calendar 兼容）→TEMPORAL；List/Collection/Set/Queue 族与数组→COLLECTION；Map 族与用户项目 DTO→OBJECT；其余一律 UNKNOWN。
- 收窄两处（宁缺勿错）：`byte[]`/`Byte[]` 按 BLOB 语义落 UNKNOWN（不按集合提示展开）；枚举、boolean、char、`java.sql.Timestamp` 落 UNKNOWN（映射不到即现状）。
- 键形态定案：**DTO 参数不产出顶层键**（键 = `p.字段名` 路径，与升级前引号映射键形态逐键一致）；
  故 OBJECT 类别实际由 Map 参数（键 = 参数名）与注解路径的 DTO 参数承载。
- 注解侧：`AnnotationSqlPreviewService.paramNames(List)` 扩为 `paramTypes(Map)`（名字 + 类别，`param1..N` 按位置继承类别）；
  `AnnotationSqlParser.parse` 追加 `paramTypes` 可选参数；`XmlSqlCompletionContextResolver` 的补全参数名改取 `paramTypes(...).keys`（键序与旧列表逐项一致）。
- **TEMPORAL 引号行为显式定案**：`quoteRequired = true`（引号档）——升级前 `LocalDate` 即落引号档（未命中数值集），
  本次把「日期无特判」升格为显式规则，行为逐值不变（防回归断言：`resolveQuotes` 与 `kind.quoteRequired` 逐键相等 + `quoteFor` 与显式布尔通道逐值相等）。

### T-PT-03 mock 分派

- `XmlPreviewModel.mockBindings` 占位值按类别分派：NUMBER→`1`、TEMPORAL→`'2026-01-01 00:00:00'`（带引号字面量，`${}` 直替后即为合法 SQL 字面量）、
  COLLECTION→`MockIterable(2)`、OBJECT→`MockObject()`、TEXT/UNKNOWN→`"1"`（现状）。
- 无类型信息（含 DTO 顶层键缺席）一律维持 `"1"`——验收 3 的「行为与现状一致」由此成立。
- 已知边界：COLLECTION 类别参数若出现在 `${}` 直替里会展开为 `MockIterable` 的 toString（`${集合}` 本身不是合法 SQL 用法，登记观察）。

### T-PT-02 占位提示与校验

- `buildParamRow` 按类别挂 `emptyText`（NUMBER/TEMPORAL/COLLECTION/OBJECT 四档；TEXT 不提示）；
  UNKNOWN 走参数命名启发式（zMyBatis `ParameterInputDialog` 同款）：`s` 结尾或含 list/ids/array/collection → 数组提示，
  单数词排除表 26 词（status/address/class/business…），`ss` 结尾天然排除。
- 行尾淡色警示标：NUMBER 非数字、TEMPORAL 非可解析日期时显示 `⚠` + tooltip 说明（`JBColor` 明暗双档）；
  **校验是提示不是闸门**——`renderValue`/`renderSingleValue` 渲染链零改动，值照原样进 SQL。
- 日期控件（DatePicker）按计划列为可选增强，v1 只做文本 + 校验。

### T-PT-04 空参数填充策略

- `PluginSettings.State` 新增 `emptyParamFill`（`NULL` 默认 / `EMPTY_STRING`）；设置页 SQL 预览分组新增单选（双语键 5 个）；
  `isModifiedPreview/apply/reset` 三处接线 + 测试缝 `selectEmptyParamFill`。
- **实施偏离（一处，显式定案）**：策略仅对**带 mapper 签名类型信息**的参数行生效；无类型信息路径（日志还原/JPA/旧文本路径等）
  的空输入保留 `?` 占位。依据 = §四 验收 3「无类型信息（UNKNOWN）时行为与现状一致」，
  且既有契约用例 `SqlPreviewPanelTest.testParamBackfillEmptyKeepsPlaceholder`（留空保留 `?`）为设计态；
  计划原文「空输入按策略出 NULL/''」未限定范围，此处按验收标准收窄。
- 未引入「执行后自动复制」（计划明示）。

### T-PT-05 测试与文档

- 新增 `xml/XmlParamKindTest`（6 例，纯逻辑进 testFast）：派生引号视图、精确/前缀命中、mock 分派六类别、无类型信息现状一致。
- 新增 `ui/SqlPreviewPanelParamKindTest`（5 例，headless）：emptyText 四类别 + UNKNOWN 启发式（含排除词）、
  NUMBER/TEMPORAL 警示与「不阻塞渲染」、空输入策略两档（含 IN 列表形态与有值输入不受影响）。
- `heavy/HeavyXmlParamTypeResolverTest` 扩 2 例：真实 PSI 六类别映射（含枚举/byte[]/嵌套 DTO/Map/List/原语 nullable）、
  `resolveQuotes` 派生一致性逐键断言。
- `config/PluginSettingsPanelBindingTest` 扩 1 例：空参数填充策略 isModified/apply/reset 读写链。
- 文档：`docs/变更记录.md` 总册行 + `CHANGELOG.md [Unreleased]` 条目 + 本节回填。

### 门禁与现状核实勘误

- 门禁：`./gradlew testFast` 绿；全量 `./gradlew testShards` **4961/0/0/6 绿**（本分支基线计数，含新增 14 例）。
- §二 现状核实表结论成立（读取链已存在、类型只被加工成布尔引号映射、输入控件清一色 JBTextField、mock 统一 `"1"`），
  但两处行号锚点已随并行交付漂移，以当前代码为准：`buildParamRow` 实为 `SqlPreviewPanel.kt:2843`（表内 2434）、
  `ParamQuote.decide` 实为 `sql/ParamQuote.kt:29`（表内 47-65）；`MockIterable` 定义在 `xml/Ognl.kt:28`（表内指向 XmlPreviewModel）。

