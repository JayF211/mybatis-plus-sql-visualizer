# T-ForeachCollectionAlias foreach 集合快捷别名与补全候选 — 开发计划

- 日期：2026-09-20 11:42
- 任务编号：T-ForeachCollectionAlias（第九轮报告 §9.3 **N-32**，P1）
- 来源：第九轮报告 §7.2 **Z-26**——zMyBatis 逐字复刻 MyBatis 3.5.19 `ParamNameResolver.wrapToMapIfCollection`：仅「单参数且无 `@Param`」时 `T[]`→`array`、`List`→`collection`+`list`、`Collection`→`collection`，且 `GeneratedAlias` 携带 `ruleId = "mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection"`（`XmlMapperMethodParameterContractFactory.kt:24-25`、`:253-300`）
- 性质：**渲染语义补缺 + 补全体验**——不改既有优先级、不改 `ForeachSqlNode` 求值顺序
- 实施分支：自 master 拉任务分支 t-foreach-collection-alias
- 验证基线：master `f8c115d`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 一、背景与依据（现状核实，含三处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「我方不支持 `list`/`collection`/`array` → 预览会展开失败」 | **不成立（重大口径修正）**：预览面板对 collection 表达式**原文全文注入** `MockIterable`——`xml/XmlPreviewModel.kt:154-160`：`bindings[view.collectionExpr] = MockIterable(...)`，键就是表达式原文，**写什么都能展开**。故 `collection="list"` 在预览里本来就能用 | **预览侧增益远小于预期**；本任务的重心从「修预览」转为「补全候选 + 真引擎对照覆盖 + 别名键补齐」 |
| 2 | 「别名支持」的落点应在 `ForeachSqlNode` | **不建议改**：`ForeachSqlNode.kt:59-84` 的 `evaluateIterable` 是「`bindings[collectionExpression]` 全文精确命中优先 → 否则 `OgnlEvaluator.evaluate`」的两级结构，改动会破坏既有优先级 | 落点改到**绑定构造侧**（与 paramN 门槛同址），见 §三 |
| 3 | 「我方已有别名推导机制」 | **无任何 ParamNameResolver 式别名推导**（全仓无 `"array"`/`"collection"`/`"list"` 别名注入点）；**且无机器可读 ruleId 机制**——唯一带口径后缀的溯源标注是 `ui/XmlParamTypeResolver.kt:78` 的 KDoc 文本 `mybatis-3.5.19-paramN-全或无`；全仓机器可读 ruleId 家族只有性能规则（`sql/perf/PerfRule.kt:21` + `PerfWarning.kt:18`，20 条），与渲染语义零交集 | ruleId 沿用**KDoc 文本约定**（不新建机制） |

### 1.1 现状细节（全部实测）

**foreach 求值链**：

- 构建：`xml/XmlScriptBuilder.kt:70` `collectionExpression = element.getAttribute("collection")`
- 求值：`xml/ForeachSqlNode.kt:59-84` `evaluateIterable`
- 视图收集：`xml/XmlPreviewModel.kt:306-309` `forEachViews += XmlForEachView(...)`
- 预览注入：`xml/XmlPreviewModel.kt:154-160`（全文注入，见口径修正 1）

**collection 表达式形态支持面**：

| 形态 | 现状 | 证据 |
|---|---|---|
| 变量名（顶层 binding） | 支持 | `ForeachSqlNode.kt:60` 精确命中；否则 OGNL `Ognl.kt:436` `RootRef -> bindings[expr.name]` |
| `list` / `collection` / `array` | **面板预览内「能用」**（全文注入，非别名推导） | `XmlPreviewModel.kt:154-160` |
| map 键（`m['k']` / `m.k`） | 支持 | `Ognl.kt:531` `is Map<*,*> -> current[name]`；下标 `ChainStep.Index`（`Ognl.kt:59`） |
| `@Param` 名 / `paramN` | 支持（作为语句根出现时） | `ui/XmlParamTypeResolver.kt:104-108`；`ui/AnnotationSqlPreviewService.kt:203-208` |
| `map.keys` | **不支持**（property 落 `current["keys"]` → null） | `Ognl.kt:531` 无 `keys` 特判 |

**补全侧缺口（本任务的主要真实收益点）**：`ui/highlight/XmlSqlCompletionContributor.kt:141-144` 对 `collection` 属性**只给 `paramNames`（方法参数名 + paramN），不给 `list`/`collection`/`array` 候选**。

**真引擎对照资产的覆盖缺口**：`xml/fidelity/RealEngineFidelityTest.kt:20-22` 明确「绕开面板 mock 层」直接喂 bindings，故 **ParamNameResolver 的别名行为在现有回归资产里完全无覆盖**——缺口是「不可见」而非「不存在」。

**既有命名启发式（仅提示，非语义，不冲突）**：`ui/SqlPreviewPanel.kt:3243-3250` `looksLikeCollectionName`（含 list/ids/array/collection 或以 s 结尾）。

## 二、目标与非目标

**目标**：① 单集合参数无 `@Param` 时，`collection="list"`/`"collection"`/`"array"` 在**非预览链**（真引擎语义面）也被正确解析；② `collection` 属性的补全给出这三个快捷名；③ 把该行为钉进真引擎对照资产，防漂移；④ 别名出处带版本化规则标注。

**非目标**：不改 `ForeachSqlNode.evaluateIterable` 的两级优先级（口径修正 2）；不新建机器可读 ruleId 机制（沿用 KDoc 文本约定）；不做 `map.keys` 特判（属 OGNL 子集另一面，登记不立项）；不改面板的全文注入行为（它是预览能用的原因，动了反而退化）。

## 三、方案

**T-FCA-01（别名键补齐，XML 路径）**：在 `ui/XmlParamTypeResolver.kt:104-108` 的 paramN 门槛**旁**补 `array`/`collection`/`list` 键，条件与 zMyBatis 同款保守门槛——**单参数 ∧ 无 `@Param` ∧ `kind == COLLECTION`**；`List` 双写 `collection` + `list`、`T[]` 写 `array`、`Collection` 写 `collection`（确切键集见「待验证前提」）。注解路径同址 `ui/AnnotationSqlPreviewService.kt:203-208`。

**T-FCA-02（规则溯源标注）**：在 T-FCA-01 落点旁按既有文本约定加 KDoc 标注 `mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection`（照 `ui/XmlParamTypeResolver.kt:78` 的 `mybatis-3.5.19-paramN-全或无` 形态）。**不新建 ruleId 字段**。

**T-FCA-03（补全候选）**：`ui/highlight/XmlSqlCompletionContributor.kt:141-144` 在 `collection` 属性的候选中追加 `list`/`collection`/`array`（**排序**：参数名在前、快捷名在后，避免挤压既有候选；不改变 `paramNames` 的既有顺序）。

**T-FCA-04（真引擎对照覆盖，本任务的价值锚）**：在 `xml/fidelity/RealEngineFidelityTest.kt` 新增场景（照既有 25 条一致面的表驱动形态 `:235-365`）：单 `List` 参数无 `@Param` 的 `<foreach collection="list">` / `"collection"`，与 `T[]` 参数的 `"array"`，断言真引擎 SQL 与自研 SQL 逐字节相等 + 参数属性名序列相等（`:71`/`:74-77` 既有断言形态）。**这是把「别名行为」从不可见变可见的唯一手段**。

**T-FCA-05（测试与文档）**：定向用例——①`collection` 属性补全候选含三个快捷名（`XmlSqlCompletionContributor` 既有测试形态）；②单参数无 `@Param` 时别名键存在、有多参数或含 `@Param` 时**不存在**（全或无门槛的负向用例）；③既有 `HeavyXmlParamTypeResolverTest` / `ForeachSqlNodeTest` / `XmlPreviewModelTest` 全绿（防回退）。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第九轮报告 §7.2 Z-26 落地备注。

## 四、验收标准

1. `collection="list"` / `"collection"` / `"array"` 在真引擎对照场景下自研渲染与真引擎**逐字节相等**（T-FCA-04 的用例绿）。
2. 全或无门槛成立：多参数或含显式 `@Param` 时**不**生成快捷别名键（负向用例钉住）。
3. `collection` 属性补全候选含三个快捷名，且既有参数名候选的顺序与内容不变。
4. 别名规则标注（`mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection`）落在源码 KDoc 中，可 grep 到。
5. 既有 foreach / 参数契约相关测试类全绿；全量 testShards + testDatabase + agent 绿（跑前按并行铁律登记）。

## 五、真机走查清单（留人工）

1. 真机打开含 `<foreach collection="list">` 的 mapper，预览正常展开（防回退——面板全文注入路径不受本任务影响，此项是回归确认）。
2. 真机在 `collection="` 处触发补全，可见三个快捷名且不遮挡参数名候选。
3. 真机含多参数（部分无 `@Param`）的 mapper：不出现快捷别名键、预览行为与改前一致。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **收益被高估**（本任务最大风险） | 预览侧本来就能用（全文注入），若把本任务理解为「修预览」会做出无收益的改动 | 任务重心已按口径修正 1 重定为「补全候选 + 真引擎覆盖 + 别名键补齐」；验收 1 的判据落在真引擎对照而非面板 |
| 破坏既有优先级 | 若误改 `ForeachSqlNode.evaluateIterable` 会推翻「精确命中优先」 | 非目标显式排除；落点限定绑定构造侧 |
| 别名键集与真引擎不符 | MyBatis 3.5.19 的确切键集（`array` vs `collection`+`list` 双写）须以真引擎为准 | T-FCA-04 的真引擎对照用例即为判据——**先跑对照再定键集**（待验证前提 1） |
| 真引擎驱动不可用 | `RealEngineFidelityTest.kt:44-48` 在 jar 缺失时**硬失败**（非跳过） | 实施前先跑一次 `testFast` 确认真引擎 jar 定位（`build.gradle.kts:93-96`：经 `testImplementation(libs.mp.core)` 传递带入 3.5.19） |
| 补全候选污染 | 快捷名可能挤压既有候选顺序 | 参数名在前、快捷名在后；用例断言既有顺序不变 |

## 七、实施顺序

```
T-FCA-04（先建真引擎对照场景，用它定键集——先红后绿）→ T-FCA-01（别名键补齐）→ T-FCA-02（规则标注）
  → T-FCA-03（补全候选）→ T-FCA-05（测试与文档）→ 真机走查 §五
```

**顺序理由**：T-FCA-04 先行是「先建判据再改代码」——键集口径由真引擎实测决定，而不是照抄竞品文本。

## 八、实施记录（实施后回填）

分支 `t-round9`（基线 master `3397829`）。

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-FCA-04 | `57eeb1d` | 一致面 25 → 28 条（P31 单 List 别名 `list`、P32 别名 `collection`、P33 单 `T[]` 别名 `array`）+ 新用例「别名键集钉」。**键集实测与计划转述一致**（见下）。**关键偏离**：驱动直连 `MappedStatement.getBoundSql`（绕过 `MapperMethod`），`ParamNameResolver` 的包装**不会自动发生**——真引擎侧必须由测试侧反射调引擎自身的 `wrapToMapIfCollection`（类位置按 3.5.19 `javap` 实测定为 `org.apache.ibatis.reflection.ParamNameResolver`，非旧版 `session` 包）。裸 List 参数下 `collection="list"` 实测抛 `java.lang.UnsupportedOperationException`（集合参数被 MetaObject 包成 `CollectionWrapper`，非 Map 参数无 `_parameter` fallback），该读数作为「别名键只由 ParamNameResolver 产生」的反向证据留在用例里。 |
| T-FCA-01 | `6f04e9a` | 落 `ui/XmlParamTypeResolver.collectionAliasTypes`，由 `resolveTypes` 末尾追加。**计划前提 2 证伪**：既有 `paramN` 门槛是「全部参数都有显式 `@Param`」（`:104`），与别名门槛「单参数 ∧ 无显式 `@Param`」**互补而非同址复用**，故独立成函数、不共用判定。额外收窄：`Iterable` 在既有 `COLLECTION_SIMPLE_NAMES` 里但**不是** `java.util.Collection`，真引擎不包装 → 显式排除（`NON_COLLECTION_SIMPLE_NAMES`）。 |
| T-FCA-02 | `6f04e9a` | 落 `ui/AnnotationSqlPreviewService.previewParamTypes`（`paramTypes` + 别名），两处预览调用点（`:102`/`:132`）改用。**偏离**：别名**刻意不并入 `paramTypes`** —— 该函数键序是补全候选来源（`XmlSqlCompletionContextResolver:122` 取 `keys` 作 `paramNames`，被 `test` 与 `collection` 共用），并入即污染 `test` 候选；别名改走「预览绑定」渠道，补全侧由 T-FCA-03 按属性收窄。规则标注（`mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection`）落两处 KDoc。 |
| T-FCA-03 | `6f04e9a` | `ui/highlight/XmlSqlCompletionContributor`：`addOgnlCompletions` 增 `collectionAliases` 形参，`collection` 属性为真、`test` 为假（**计划前提 3 成立且比担心更紧**：`:87` 的分派 `test`/`collection` 共用 `addOgnlCompletions`，故按属性名收窄）。快捷名**排在参数名与字段名之后**（用例逐项断言 `[entity, id, name, list, collection, array]`，既有顺序与内容不变）。三个名字无条件列出（本贡献器不做 PSI 签名解析，拿不到「单参数/集合形态」），确切键集由 `collectionAliasTypes` + 真引擎对照钉住。 |
| T-FCA-05 | `6f04e9a` | 用例：`HeavyXmlParamTypeResolverTest` +3（逐形态正向、门槛负向四类、预览联动）、`XmlSqlCompletionContributorTest` +2（候选与顺序、`test` 不污染）、`AnnotationPreviewServiceTest` +2（`previewParamTypes` 与 `paramTypes` 的边界判别、注解 `<script>` foreach 闭环）。定向读数见 §九。 |

**真引擎实测键集（T-FCA-04 判据，`mybatis-3.5.19.jar` 隔离 loader 实调）**：`List` → `collection` + `list` + 实参名；`Collection` 非 `List`（Set/Queue 族）→ `collection` + 实参名（**无 `list`**）；`T[]` → `array` + 实参名（**无 `collection`**）；Map / POJO / 标量 → 原对象返回不包装；实参名缺席（`useActualParamName=false`）→ 仅 `collection` + `list`。**计划的键集假设全部成立**，未被推翻。

**待验证前提（实施者先证伪再动手）**：

1. **MyBatis 3.5.19 `wrapToMapIfCollection` 的确切键集**——`List` 是否同时生成 `collection` 与 `list` 两个键、`T[]` 是否只生成 `array`、`Collection`（非 List）是否只生成 `collection`。**本计划的键集来自竞品源码复刻的转述，未经我方真引擎实测**；T-FCA-04 先行就是为了证伪它。→ **成立**（实测见 §八键集，且与 `mybatis-3.5.19-sources.jar` 源码逐支一致）。
2. 「`paramN` 门槛同址可复用」的前提是「单参数无 `@Param`」这一条件在我方现有代码里已可判定（`ui/XmlParamTypeResolver.kt:104-108` 现有分支形态）——实施时须读该处全文确认，本计划只定位到行号区间。→ **证伪**：`:104` 的条件是「全部参数都有显式 `@Param`」，与「单参数 ∧ 无 `@Param`」互补，不可复用（详见 §八 T-FCA-01 行）。
3. 「补全候选只影响 `collection` 属性」的前提是 `XmlSqlCompletionContributor.kt:141-144` 的作用域确实限定在该属性——实施时须确认上下文判定，否则会把快捷名灌进所有属性候选。→ **成立但需显式收窄**：`:87` 是 `test` 与 `collection` 共用分支，直接在 `:141-144` 的 else 分支追加会同时灌进 `test`，已按属性名收窄并留反向用例。

## 九、定向测试读数（实施后回填）

| 读数 | 命令 / 范围 | 结果 |
|------|------------|------|
| T-FCA-04 先红 | `RealEngineFidelityTest`（键集钉首跑） | 红 1 条：`Map 参数不包装` 断言写错（`wrapToMapIfCollection` 对非集合**原样返回入参**，Map 入参返回值仍是 Map，不能用「是否 Map」判定）；改为引用同一判定后绿。裸 List 反向读数首跑也红（实际抛 `UnsupportedOperationException`，非推断的 `ReflectionException`），按实测改正后绿 |
| T-FCA-04 后绿 | `RealEngineFidelityTest` | 5 用例 0 失败（一致面 28 条 + 已知差异 6 条 + 版本钉 + P20 归因 + 别名键集钉） |
| 新增定向（正向） | `XmlSqlCompletionContributorTest` / `HeavyXmlParamTypeResolverTest` / `AnnotationPreviewServiceTest` / `RealEngineFidelityTest` | 15 / 15 / 14 / 5 用例，**0 失败 0 跳过**（共 49） |
| 反向验证（三处修复分支同时摘除/放宽） | ① `resolveTypes` 去掉别名注入 ② 预览调用点退回 `paramTypes` ③ 补全收窄放宽为恒真 | 红 3 条，逐条对上：`testCollectionAliasKeysByParamShape`、`testCollectionAliasFeedsPreviewBindings`、`test15TestAttributeHasNoCollectionAliases`；注解侧判别器（`paramProps` 断言）单独复核亦红。还原后三文件 sha256 与改前一致、全绿 |
| 既有相关类回归 | `ForeachSqlNodeTest` / `ForeachInnerIfErrorTest` / `XmlPreviewModelTest` / `XmlParamKindTest` / `XmlSqlCompletionContextResolverTest` / `HeavySqlCompletionContextResolverTest` / `UnknownParamGateTest` / `SqlPreviewPanelParamKindTest` / `AnnotationSqlCompletionContributorTest` / `XmlPreviewServiceTest` | 82 用例 0 失败 |
| 全量 | `testShards` | **5412 / 0 / 0 / 6**（分片 1802 + 1964 + 1646，BUILD SUCCESSFUL，3m07s）。基线 5383/0/0/6 → 本路新增 8 例（Heavy +3、补全 +2、注解预览 +2、真引擎 +1）；余量为同工作树另一路（T-PlaceholderSpliceGate）的用例 |

**注解侧「预览本来就能用」的实测确认**（计划 §一 口径修正 1 成立）：把预览调用点退回 `paramTypes` 后，注解 `<script>` 的 `collection="list"` foreach **照常展开**（面板对 collection 表达式原文全文注入 mock 迭代），别名键的真实增量是「`list`/`collection` 进模型参数属性与类型视图」——判别器因此改钉 `paramProps` 而非 SQL 文本。

**未覆盖（如实登记）**：注解 `<script>` 内的 `collection` 属性补全**未加**快捷名——`AnnotationSqlCompletionContributor` 走的是 `SourceSqlSuggestionFactory.OGNL_ATTRIBUTE_PATTERN`（对 `<script>` 文本的单个正则，`test` 与 `collection` 不区分、拿不到属性名），要收窄须新增捕获组；计划 T-FCA-03 只点名 XML 贡献器，故未扩面。

## 十、收口门禁（由 Astra 统一跑）

`verifyGate` / `buildPlugin` / `verifyPlugin` 与 `testDatabase` 读数由收口阶段统一回填（本路按任务纪律不重复跑）。

**文档计数漂移（未改，留收口处理）**：本路把一致面 25 条 → 28 条，以下三处 docs 的「31 场景 / 25 场景」计数已过期——`docs/架构设计/03-开发与测试指南.md:89`、`docs/架构设计/02-核心流程详解.md:267`、`docs/架构设计/01-项目架构总览.md:363`；`docs/调研报告/20260919-T-FidelityEval-自研与真引擎已知差异清单.md:38` 的「用例构成（4 个）」与 T-REC 计划里的「25 条一致面」表述同源受影响。

