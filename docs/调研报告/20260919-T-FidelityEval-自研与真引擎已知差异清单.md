# 自研渲染器与真 MyBatis 引擎 —— 已知差异清单（T-FidelityEval / T-TailSweep2 G1）

- 日期：2026-09-19
- 任务：T-TailSweep2 T-FS-03 产出②（对照集产出之一）
- 性质：**登记性文档**——只记录事实与判定，不改渲染主链、不立项
- 对照集：`src/test/kotlin/cn/song/mybatisplus/visualizer/xml/fidelity/RealEngineFidelityTest.kt`
- 驱动：`src/test/kotlin/cn/song/mybatisplus/visualizer/xml/fidelity/RealEngineDriver.kt`（T-FS-01）
- 读数基线来源：T-FS-01 临时探针 `FidelityProbeTest` 的 31 条 P0~P30 实测（`./gradlew testFast`，tests=1/failures=0/errors=0，2026-09-19 06:41 本机）；探针按自身 KDoc「不入库」已删除，读数全文见本文 §7
- 相关：`docs/调研报告/20260918-T-FidelityEval-高保真求值模式评估报告.md`（§1.2 缺陷账 15 条 / §3.2 spike q1~q7 / §8 定案）

## 0. 结论

31 条场景里 **25 条两侧逐字节一致**（SQL 文本 + `#{}` 参数属性名序列），**6 条登记为已知差异**（P0 / P14 / P15 / P16 / P20 / P30）。这 6 条按成因分三类：

| 类别 | 条目 | 性质 |
|---|---|---|
| 我方更宽容 / 超集 | P0、P14、P15 | 真引擎硬失败（`BuilderException`）而我方降级可用——**不是缺陷，换成真引擎反而少能力** |
| 我方在册缺口 | P16 | 真引擎硬失败、我方展开**且不记 errors**（静默）——T-FS-07 已立项，可修 |
| 求值器能力边界 | P20、P30 | P20 是求值器属性访问不支持 POJO 载体（真缺口，当前生产链不可达）；P30 只差一条提示行 |

6 条里 **可修 2 条**（P16 = T-FS-07，约 0.5 人天；P20 = 求值器加 POJO 属性访问，0.5~1 人天但需先有触达场景），其余 4 条**不建议修**（修的方向是「对齐真引擎的硬失败」，等于主动降低可用性）。

## 1. 对照协议与覆盖（T-FS-02）

同一批**真实值**分别喂两侧，绕开面板 mock 层：

| 侧 | 入口 | 参数形态 |
|---|---|---|
| 真引擎 | `RealEngineDriver.render(mapperXml, ns, id, params)` → `MappedStatement.getBoundSql` | 真实对象：`Map` / `List<Integer>` / DTO 实例 / `Integer` / `null` |
| 自研 | `XmlSqlRenderer().render(node, bindings, staticFieldValues = …)` | `_parameter` + 顶层变量 + `_databaseId` 键形态（对齐 `XmlPreviewModel.mockBindings`），值同为真实对象 |

**断言面只覆盖标签语义**：SQL 文本、`#{}` 参数属性名序列（顺序即 `?` 位点，foreach 内为 `__frch_<名>_<n>`）。辅助面只断言自研侧 `errors` 是否为空（一致面要求为空）。**不覆盖**：mock 值语义（引号 / 类型 / `?` 占位形态）、面板回填链、`${ew.customSqlSegment}`、MCP 出口链、性能面——按 T-FS-02 纪律，这些差异若一律断言不等，对照集会被淹没而失去判别力。

空白面例外：真引擎保留 mapper 原文空白（换行/缩进原样进 SQL），自研按 `DynamicContext.appendSql` 的「段间单空格」拼接（对齐 3.5.13+ 的 `StringJoiner(" ")`），且段内自带前导空格时会出现连续双空格（如 `SELECT * FROM t  AND c = ?`）。故断言前对**两侧**做 `\s+` → 单空格归一，归一只动空白、不动 token 与 `?` 位点。这是本文登记的唯一非逐字节环节，不视为缺陷。

桶归属：对照集落 `testFast` 快桶（测试源码零 `com.intellij` 字面引用），与前序探针的桶归属一致——真引擎驱动是纯 JVM 操作，mybatis jar 在 testFast 的 classpath 上可达（实测已跑通）。

用例构成（4 个）：① 一致面 25 条逐字节相等；② 已知差异 6 条按登记形态断言；③ 引擎版本钉住（见下）；④ P20 归因钉（见 §4）。

驱动不可用时的口径：**硬失败**（不 skip）。依据：① `RealEngineDriver` 自身 KDoc 明写「调用方断言红，禁止静默跳过：跳过=对照集形同不存在」；② 真引擎 jar 经 `testImplementation(libs.mp.core)`（`com.baomidou:mybatis-plus-core:3.5.17`）传递带入 `org.mybatis:mybatis:3.5.19`，已在测试依赖图内——仓内软跳先例（`DialectDockerVerificationTest` 探测 Docker daemon）的适用前提是「被测能力不在本机依赖图内」，此处不成立；③ 对照集是回归资产，静默降级即退化为装饰。

引擎版本钉住：真引擎版本由**传递依赖**决定（本仓不显式声明 `org.mybatis:mybatis`，构建改动为零），mybatis-plus 升级会静默换掉被对照的引擎版本。故对照集另有版本钉住用例：jar 文件名 + 隔离 loader 载入类的包 `implementationVersion` 两路核验 3.5.19，失败消息直指「对照结论需重新评估」。期望值与自研管线声称复刻的目标版本同源（`xml/XmlSqlRenderer.kt:52` 的 KDoc 字面量；生产侧无常量可引，故在测试内显式钉住并注释交叉引用）。

## 2. 差异清单（6 条）

### D1 / P0 无 DOCTYPE

- 场景：`<mapper namespace="n">…</mapper>` 不带 DOCTYPE，`SELECT * FROM t WHERE a = #{a}`
- 真引擎：抛 `org.apache.ibatis.builder.BuilderException: Error creating document instance. Cause: SAXParseException; lineNumber: 1; columnNumber: 23; 文档根元素 "mapper" 必须匹配 DOCTYPE 根 "null"`
- 自研：`sql=[SELECT * FROM t WHERE a = ?] props=[a] errors=[]`
- 成因：真引擎 `XMLMapperBuilder` 经 XPathParser 校验 DOCTYPE；自研自建 DOM 解析器在 T-M25.1（`d5dcd55`）改为「接受官方 DOCTYPE + 三重 XXE 防护」，对缺失 DOCTYPE 不做强制。
- 判定：**我方更宽容**，不是缺陷。
- 可修：不建议（收紧=降低对片段式 mapper 的可用性）。
- 成本量级：若强行对齐需在解析期加 DOCTYPE 校验，约 0.5 人天，方向是反的。

### D2 / P14 `||flag||` 包裹

- 场景：`<if test="||flag||"> AND x = 1</if>`，`flag=true`
- 真引擎：抛 `BuilderException: Error evaluating expression '||flag||'. Cause: ExpressionSyntaxException: Malformed OGNL expression: ||flag||`
- 自研：`sql=[SELECT * FROM t AND x = 1] props=[] errors=[]`
- 成因：`xml/Ognl.kt` 的 `unwrapOrBars`（递归剥 `||…||` 壳）是社区非标准写法的兼容能力；`VarDeclSqlNode`（`bind`）同款剥壳并额外记一条提示。
- 判定：**我方超集能力**（评估报告 §1.2 #3 的「半条」）——换成真引擎等于少一个能力。
- 可修：不建议。
- 成本量级：不适用。

### D3 / P15 顶层 `if` 非法表达式

- 场景：`<if test="a === b"> AND x = 1</if>`
- 真引擎：抛 `BuilderException: Error evaluating expression 'a === b'. Cause: ExpressionSyntaxException: Malformed OGNL expression: a === b`
- 自研：`sql=[SELECT * FROM t AND x = 1] props=[] errors=[条件表达式无法求值，已按 true 展开——可在预览面板用开关覆盖：'a === b'（unexpected character '=' at 4）]`
- 成因：失败形态的产品决策不同——真引擎硬失败（预览将不可用），自研降级为「展开 + 记 errors 走 ⚠ 警示行」（T-EP-02 落地，`IfSqlNode.kt:20-29`）。
- 判定：降级是有意的，且**失败可见**（不再是评估报告 §1.4 记的静默展开）。不是缺陷。
- 可修：不建议（改为硬失败会破坏预览可用性）。
- 成本量级：不适用（对齐方向成本约 0.5 人天，但不做）。

### D4 / P16 `foreach` 体内 `if` 非法表达式（在册缺口）

- 场景：`<foreach collection="ids" item="id" open="(" separator="," close=")"><if test="id === 1">#{id}</if></foreach>`
- 真引擎：抛 `BuilderException: Error evaluating expression 'id === 1'. Cause: ExpressionSyntaxException: Malformed OGNL expression: id === 1`
- 自研：`sql=[SELECT * FROM t WHERE id IN ( ? , ? )] props=[__frch_id_0, __frch_id_1] errors=[]`——展开但**无任何错误提示**
- 成因：`xml/PrefixedContext` / `FilteredContext` 各自持有 `DynamicContext.errors` 且未转发，`foreach` 体内的求值失败落进子上下文后丢弃。同一失败在顶层 `if`（D3）能记 errors，只有这一处静默。
- 判定：**真缺陷**（唯一与真引擎硬失败相对、且用户不可见的一处）。
- 可修：可修，已立项 **T-TailSweep2 T-FS-07**（`DynamicContext.errors` 转 open + 两个子上下文按既有 `bindings`/`staticFieldValues` 同款委托覆写）。
- 成本量级：约 5 行 + 同一表达式同一原因的去重，**0.5 人天**（含用例）。
- 联动：T-FS-07 落地后本清单该条与对照集的 `P16 errors 为空` 断言会同时变红 → 属预期，届时同步改。

### D5 / P20 DTO 属性在 `test` 中（专项判定见 §4）

- 场景：`<if test="u.name != null"> AND name = #{u.name}</if>`，`u` 为 DTO 实例（`ProbeDto("a")`）
- 真引擎：`sql=[SELECT * FROM t AND name = ?] props=[u.name]`（判真展开）
- 自研：`sql=[SELECT * FROM t] props=[] errors=[]`（判假，整段不展开，且无提示）
- 成因：自研求值器的属性访问只覆盖 `MockObject` / `Map` / `Collection` / `Array` / `CharSequence`，其余载体一律 `else -> null`（`xml/Ognl.kt:529-549`，`else` 分支在 548 行）——真实 DTO/POJO 实例的属性取不到。
- 判定：**自研求值器的能力缺口**（不是探针构造问题，也不是真引擎行为差异）；但当前生产链不可达，见 §4。
- 可修：可修（加反射 getter/字段访问分支），但**建议先不修**——先等触达场景出现（详见 §4 修法建议）。
- 成本量级：0.5~1 人天（反射可见性、`getX()`/`isX()` 命名、访问异常吞掉策略、缓存与性能面 + 用例）。

### D6 / P30 `${}` 求值为空

- 场景：`SELECT * FROM ${tbl} WHERE a = 1`，`tbl=null`
- 真引擎：`sql=[SELECT * FROM WHERE a = 1] props=[]`（静默空串）
- 自研：`sql=[SELECT * FROM WHERE a = 1] props=[] errors=[模拟参数下表达式 ${tbl} 求值为空（MP 占位 ${ew...} 等需真实参数值）]`
- 成因：真引擎无提示能力（它直接产出 BoundSql）；自研在预览场景需要「这个占位没算出来」的可见性，故多一条 errors。
- 判定：SQL 与参数队列完全一致，**只差一条提示行**，属提示面差异而非语义差异。
- 可修：无需修。
- 成本量级：不适用。

## 3. 差异汇总表

| 编号 | 场景 | 真引擎 | 自研 | 类别 | 可修 | 成本 |
|---|---|---|---|---|---|---|
| D1 | P0 无 DOCTYPE | `BuilderException` | 正常渲染 | 我方宽容 | 否（不建议） | 0.5 人天（方向反） |
| D2 | P14 `\|\|flag\|\|` | `ExpressionSyntaxException` | 剥壳渲染 `AND x = 1` | 我方超集 | 否 | — |
| D3 | P15 顶层 `if` 非法表达式 | `BuilderException` | 展开 + errors | 我方降级（可见） | 否（不建议） | 0.5 人天（方向反） |
| D4 | P16 `foreach` 体内 `if` 非法表达式 | `BuilderException` | 展开 + **无 errors** | 在册缺口 | **是**（T-FS-07） | 0.5 人天 |
| D5 | P20 DTO 属性在 `test` | 判真展开 | 判假不展开 | 求值器能力边界 | 是（暂缓） | 0.5~1 人天 |
| D6 | P30 `${}` 求值为空 | 静默空串 | 同 SQL + 一条提示 | 提示面 | 否 | — |

## 4. P20 专项判定

**归属：第 ② 类——自研求值器的真缺口（属性访问不支持 POJO 载体）；但影响面为 0（当前生产链不可达）。** 不是第 ① 类（mock 值语义差异）也不是第 ③ 类（探针构造问题）。

证据链：

1. 探针构造无误。`P20` 给两侧喂的是**同源真实值**：真引擎 `params = mapOf("u" to ProbeDto("a"))`，自研 `bindings = {u: ProbeDto("a"), _parameter: {u: ProbeDto("a")}, _databaseId: null}`（`bindings()` 辅助函数，与 `XmlPreviewModel.mockBindings` 的键形态一致）。`u` 确实在自研 bindings 里，不存在「结构里没有该属性」的情形。
2. 根因在求值器的属性访问载体面。`OgnlExpr.Chain(RootRef("u"), [Property("name")])` 求值走 `OgnlEvaluator.property(current, "name")`：`current` 是 `ProbeDto`，不匹配 `MockObject` / `Map` / `Collection` / `Array` / `CharSequence` 任一分支，落 `else -> null`（`xml/Ognl.kt:529-549`）→ `null != null` 判假 → `if` 不展开。真引擎经 OGNL 反射取 `getName()`/字段，判真。
3. 最小复现已固化进对照集：`RealEngineFidelityTest.P20 归因：求值器属性访问的载体面不含 POJO` 断言同一表达式在两种载体下结果翻转——`ProbeDto` → `EvalResult.Ok(false)`，`MockObject(mapOf("name" to "a"))` → `EvalResult.Ok(true)`。载体一换即翻，证明缺口在载体面而非表达式解析。
4. 生产链不可达。面板与 MCP 两个出口都走 `XmlPreviewModel`：`renderSql` 把**全部** `IfSqlNode`（含 `choose` 的 when、含 `foreach` 体内的 if——`collect` 对 `ForeachSqlNode.contents` 递归）收进 `conditionViews`，并把它们整表作为 `conditionOverrides` 传入（`xml/XmlPreviewModel.kt:121-129`）；`IfSqlNode.apply` 的第一真值源就是该覆盖（`xml/IfSqlNode.kt:15`），**表达式求值只在开关缺席时发生**，而面板路径不存在缺席。出口清单：`ui/XmlPreviewService.kt:54/124`、`mcp/MapperCheckTool.kt:93-94`、`xml/AnnotationSqlParser.kt:36`——三处都经 `XmlPreviewModel.from(...)`，无一处把真实 POJO 喂进 bindings（`mockBindings` 的取值只有 `MockObject` / `MockIterable` / `"1"` / `1` / 日期字面量 / bind 覆盖值，`xml/XmlPreviewModel.kt:131-151/157-163`）。
   顺带说明：即便在面板路径上表达式真的被求值，`u.name` 也照样取不到——那时 `u` 的值是 mock 字符串 `"1"`（`variables` 收集到根 `u` → UNKNOWN 类别 → `"1"`），`property("1", "name")` 同样返回 null。**所以这不是「mock 值语义」差异（第 ① 类），而是求值器对 POJO 载体没有访问能力（第 ② 类）**；差别只在「谁来触发」，而生产路径根本不触发。
5. 影响面。当前为 0。风险点是**将来**：若引入「按真实参数求值」的模式（评估报告 §2.1 P2 阶段）或让某个消费者直接把 POJO bindings 交给 `XmlSqlRenderer`，`test` 里的 DTO 属性会**静默判假**（errors 也为空，与 D4 同款静默形态），预览会少展开一段而无人察觉。

修法建议（不在本任务实施）：

- 在 `OgnlEvaluator.property()` 的 `else` 分支前补一个 POJO 分支：按 `getX()` / `isX()` 反射取 getter，退化为公开字段访问，失败返回 null；方法调用同理（`method()` 的未知方法分支可对 POJO 走反射白名单）。
- 必须先定两件事，否则修了会引入新分叉：① 反射可见性（`setAccessible` 在 JDK 模块化下的边界，用户类在插件 classloader 之外）；② 访问失败是「静默 null」还是「记 errors」——按 D4 的教训，静默 null 会让缺口继续隐形。
- 成本 0.5~1 人天（含 `OgnlEvaluatorTest` 用例与 `classify()` 的载体分档同步：新增 POJO 载体后 `CARRIER_AGNOSTIC` / `METHOD_CARRIERS` 的判定面要一并更新，否则 T-EP-01 分档会误报降级）。
- 触发条件建议：出现第一个真实触达场景（真实参数求值模式立项）时随该任务一起做，**不单独立项**。

## 5. 负向验证（对照集判别力实证）

做法：临时让自研渲染器在 `trim` 标签语义上偏离——`xml/TrimSqlNode.kt` 的 `applyPrefix` 中把 `for (toRemove in prefixesToOverride)` 改为 `for (toRemove in emptyList<String>())`（等价于「prefixOverrides 永不裁剪」），跑对照集，再还原。命中 P4 / P5 / P24 三条逐字节一致场景（都含 `where` 或自定义 `prefixOverrides`）。

三次实测（`./gradlew testFast --tests "cn.song.mybatisplus.visualizer.xml.fidelity.RealEngineFidelityTest" -Pkotlin.compiler.execution.strategy=in-process`，均为最终态：对照集 4 用例，含版本钉住与 P20 归因钉）：

改前（基线）：

```
> Task :testFast
BUILD SUCCESSFUL in 2m 20s
```

`build/test-results/testFast/TEST-cn.song.mybatisplus.visualizer.xml.fidelity.RealEngineFidelityTest.xml`：

```xml
<testsuite name="cn.song.mybatisplus.visualizer.xml.fidelity.RealEngineFidelityTest"
           tests="4" skipped="0" failures="0" errors="0"
           timestamp="2026-09-18T23:39:42.454Z" time="3.286">
```

改后（故意偏离 `trim` 语义）：

```
> Task :testFast
RealEngineFidelityTest > 一致面 25 条：SQL 文本与参数队列逐字节相等 FAILED
4 tests completed, 1 failed
BUILD FAILED in 50s
```

失败详情（同上 XML 的 `<failure>`）：

```xml
<testsuite … tests="4" skipped="0" failures="1" errors="0"
           timestamp="2026-09-18T23:41:09.032Z" time="2.335">
java.lang.AssertionError: 标签语义一致面出现 3 条偏差：
P4 where + set trim: SQL 不一致
    真引擎=[UPDATE t SET a = ? WHERE a = ?]
    自研  =[UPDATE t SET a = ? WHERE AND a = ?]
P5 trim custom: SQL 不一致
    真引擎=[SELECT * FROM t WHERE x = ? ORDER BY id]
    自研  =[SELECT * FROM t WHERE AND x = ? ORDER BY id]
P24 include with property: SQL 不一致
    真引擎=[SELECT * FROM t WHERE status = ?]
    自研  =[SELECT * FROM t WHERE AND status = ?]
```

还原后：

```
> Task :testFast
BUILD SUCCESSFUL in 41s
```

```xml
<testsuite name="cn.song.mybatisplus.visualizer.xml.fidelity.RealEngineFidelityTest"
           tests="4" skipped="0" failures="0" errors="0"
           timestamp="2026-09-18T23:42:10.105Z" time="2.238">
```

`git diff --stat src/main` 为空（渲染主链无残留改动）。

结论：对照集能抓标签语义偏差——红在正确场景、报出两侧读数并指明偏差条数，不是装饰。

## 6. 复跑方式

```
./gradlew testFast --tests "cn.song.mybatisplus.visualizer.xml.fidelity.RealEngineFidelityTest" \
  -Pkotlin.compiler.execution.strategy=in-process
```

- 真引擎 jar 不在 `build.gradle.kts` 里显式声明——经 `testImplementation(libs.mp.core)`（mybatis-plus-core:3.5.17）传递带入 `org.mybatis:mybatis:3.5.19`，复核命令：`./gradlew dependencies --configuration testRuntimeClasspath`；版本漂移由对照集的版本钉住用例兜底。
- jar 定位三路兜底见 `RealEngineDriver.locateMybatisJar`（宿主 classpath 资源 → `java.class.path` → Gradle 缓存）。
- 真引擎日志已置 `NoLoggingImpl`，测试输出无 mybatis 噪音。
- 全量门禁仍是 `test` / `testShards`（本类自动进分片）。

## 7. 基线读数全表（31 条，探针原文）

`REAL` 为真引擎（`BoundSql.getSql()` 空白归一 + `getParameterMappings().property` 序列），`MINE` 为自研（`XmlSqlRenderer().render`）。

| 场景 | REAL | MINE | 归类 |
|---|---|---|---|
| P0 no DOCTYPE | `BuilderException`（强制 DOCTYPE） | `sql=[SELECT * FROM t WHERE a = ?] props=[a] errors=[]` | D1 |
| P1 if Integer 0 / Map param | `sql=[SELECT * FROM t] props=[]` | 同 | 一致 |
| P2 if Integer 1 | `sql=[SELECT * FROM t AND c = ?] props=[count]` | 同 | 一致 |
| P3 choose/when/otherwise | `sql=[SELECT * FROM t ORDER BY b] props=[]` | 同 | 一致 |
| P4 where + set trim | `sql=[UPDATE t SET a = ? WHERE a = ?] props=[a, a]` | 同 | 一致 |
| P5 trim custom | `sql=[SELECT * FROM t WHERE x = ? ORDER BY id] props=[x]` | 同 | 一致 |
| P6 foreach List\<Integer\> | `sql=[SELECT * FROM t WHERE id IN ( ? , ? , ? )] props=[__frch_id_0, __frch_id_1, __frch_id_2]` | 同 | 一致 |
| P7 foreach DTO item props | `sql=[SELECT * FROM t WHERE u.name = ? OR u.name = ?] props=[__frch_u_0.name, __frch_u_1.name]` | 同 | 一致 |
| P8 foreach Map entry index=key | `sql=[SELECT * FROM t WHERE ? = ? AND ? = ?] props=[__frch_k_0, __frch_v_0, __frch_k_1, __frch_v_1]` | 同 | 一致 |
| P9 nested foreach | `sql=[SELECT * FROM t WHERE ( gid IN ( ? , ? ) OR gid IN ( ? ) )] props=[__frch_i_1, __frch_i_2, __frch_i_4]` | 同 | 一致 |
| P10 bind + like | `sql=[SELECT * FROM t WHERE name LIKE ?] props=[like]` | 同 | 一致 |
| P11 dollar direct | `sql=[SELECT * FROM orders WHERE c = ?] props=[c]` | 同 | 一致 |
| P12 static field `@java.lang.Integer@MAX_VALUE` | `sql=[SELECT * FROM t WHERE a = 2147483647] props=[]` | 同 | 一致 |
| P13 static field local class | `sql=[SELECT * FROM t WHERE a = PROBE_FLAG] props=[]` | 同 | 一致 |
| P14 or-bars wrap | `ExpressionSyntaxException: Malformed OGNL` | `sql=[SELECT * FROM t AND x = 1] props=[] errors=[]` | D2 |
| P15 invalid expr in if (top level) | `BuilderException` | `sql=[… AND x = 1] errors=[条件表达式无法求值，已按 true 展开…]` | D3 |
| P16 invalid expr in foreach body if | `BuilderException` | `sql=[SELECT * FROM t WHERE id IN ( ? , ? )] props=[__frch_id_0, __frch_id_1] errors=[]` | D4 |
| P17 empty string truthiness | `sql=[SELECT * FROM t AND x = 1] props=[]` | 同 | 一致 |
| P18 or value semantics (a='' or b='x') | `sql=[SELECT * FROM t AND x = 1] props=[]` | 同 | 一致 |
| P19 and value semantics (a='' and b=null) | `sql=[SELECT * FROM t AND x = 1] props=[]` | 同 | 一致 |
| P20 DTO property in test | `sql=[SELECT * FROM t AND name = ?] props=[u.name]` | `sql=[SELECT * FROM t] props=[] errors=[]` | D5 |
| P21 pojo as _parameter | `sql=[SELECT * FROM t AND name = ?] props=[name]` | 同 | 一致 |
| P22 empty collection foreach | `sql=[SELECT * FROM t WHERE id IN] props=[]` | 同 | 一致 |
| P23 escaped hash | `sql=[SELECT * FROM t WHERE c = #{literal}] props=[]` | 同 | 一致 |
| P24 include with property | `sql=[SELECT * FROM t WHERE status = ?] props=[status]` | 同 | 一致 |
| P25 foreach index on list | `sql=[SELECT * FROM t WHERE (i = ? AND id = ?) OR (i = ? AND id = ?)] props=[__frch_i_0, __frch_id_0, __frch_i_1, __frch_id_1]` | 同 | 一致 |
| P26 jdbcType attribute in token | `sql=[SELECT * FROM t WHERE a = ? AND b = ?] props=[a, b]` | 同 | 一致 |
| P27 if null-safe == null | `sql=[SELECT * FROM t WHERE a IS NULL] props=[]` | 同 | 一致 |
| P28 if set/list size | `sql=[SELECT * FROM t AND id IN ( ? )] props=[__frch_id_0]` | 同 | 一致 |
| P29 nested property chain map | `sql=[SELECT * FROM t AND n = ?] props=[q.name]` | 同 | 一致 |
| P30 dollar null value | `sql=[SELECT * FROM WHERE a = 1] props=[]` | 同 SQL + `errors=[模拟参数下表达式 ${tbl} 求值为空…]` | D6 |

一致 25 条 / 差异 6 条。

## 8. 未覆盖面（有意留白）

- `${ew.customSqlSegment}` 等 MP wrapper 占位（需 wrapper 对象，非引擎能力，见评估报告 §1.2 #15）
- mock 值语义面（引号策略 / 类型推断 / `?` 占位形态）——真引擎按真实 Java 类型绑定，两者对 mock 值的策略本就各自定义（评估报告 §1.3 q2 实录）
- 面板回填链、MCP 出口链、性能面、条件开关交互模型（评估报告 §2.1 P5 的模型层冲突）
- 真引擎在**用户项目 classpath** 上的可用性（本对照集用测试 classpath 上的 3.5.19；方案 A 的 P3 阶段问题，已 no-go）
