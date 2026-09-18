# T-ExprAstPrecheck 表达式求值声明性不支持 — 开发计划

- 日期：2026-09-18 08:29
- 任务编号：T-ExprAstPrecheck（第七轮报告 §9.4 **O-31**，P2）
- 来源：第七轮报告 §7 zMyBatis 精华清单 **Z-7**——OGNL AST 白名单「只解析不求值」，超出支持面标注「声明性不支持」而非硬算失败（IsolatedOgnlAstAdmission.kt:21-40）
- 性质：预览可解释性——把「求值失败/静默错」变成「声明性不支持」，错误可预期
- 实施分支：合流后自 master 拉任务分支 t-expr-ast-precheck（前置项 T-MergeDev2Master 策略决策）
- 验证基线：合流后 master 全量 testShards 绿（以合流时钉死计数为准）

---

## 一、背景与依据

zMyBatis 的 Leap 管线在求值前用 AST 白名单（18 种节点）静态判定表达式是否落在支持面，超出即拒绝并说明理由。本插件的 OGNL 求值器（立项锚点核实，master 实测）**已经是手写递归下降的结构化 AST**（Lexer/Parser/AST 四段，`OgnlExpr` sealed interface），加结构预检层是顺水推舟。核实同时发现了预检层最有价值的改进点：**if test 求值失败时静默默认展开 true，不记任何错误**（`IfSqlNode.kt:20-23`）——用户看不到「这个条件其实没算出来」，预览产物与真实运行时可能就此分叉而无人知晓。`${}`/bind/foreach 的失败则已记 errors 走 `⚠` 警示行，形态健康。

## 二、现状核实（master 实测，2026-09-18）

| 面 | 现状 | 位置 |
|---|---|---|
| 求值器结构 | 手写 Lexer/Parser/AST，`ParseResult.Err`/`EvalResult.Err` 双轨结果，parse 缓存（上限 1024） | `xml/Ognl.kt:72,439-542,548-799` |
| 支持面 | 属性导航/索引/算术比较/逻辑/in+列表字面量/白名单方法（size/length/isEmpty/contains/containsKey/startsWith/endsWith/toString）/getter 推断/静态字段（PSI 注入）；三元/赋值/lambda/静态方法/new parse 即 Err | `Ognl.kt:17-22,279-426,688-718` |
| 静默降级点① | if test 失败 → 默认 true 不记 errors（面板开关是第一真值源，此处是开关缺席兜底） | `xml/IfSqlNode.kt:20-23`；`XmlPreviewModel.kt:95-96` |
| 静默降级点② | 未知方法调用 → 静默返回 null 不报 Err（`known != null || name in KNOWN_METHODS` 使白名单方法在不匹配载体上也归「合法 null」） | `Ognl.kt:393-426`（白名单 :424-426） |
| AST 遍历模板 | 同文件已有三个遍历器 `collectStaticRefs`/`collectRoots`/`collectPaths` 可照抄结构 | `Ognl.kt:161-269` |
| 条件行 UI | `XmlConditionView`（xml 层数据结构，零 PSI） | `XmlPreviewModel.kt:10-14` |
| 测试形态 | `OgnlEvaluatorTest` 18 个主题分组方法（非表驱动），是行为锚点 | `OgnlEvaluatorTest.kt:14-21,27-218` |

## 三、方案

**T-EP-01（AST 结构分类器）**：`Ognl.kt` 内新增 `classify(text): ExprSupport`（照抄 collectStaticRefs 遍历结构），返回三档：`SUPPORTED`（语法+语义均在支持面）/ `UNSUPPORTED_SYNTAX`（parse 即 Err：三元/静态方法/new 等）/ `EVAL_FALLBACK`（语法可解析但含运行期降级形态：未知方法链、静态字段引用等——求值会走 null 降级档）。支持面清单直接从代码提取：AST 节点类型枚举 + `evalBinary` op 集合 + KNOWN_METHODS，不新造语义。

**T-EP-02（if test 静默点收口）**：`IfSqlNode` 求值失败分支从「静默 true」改为「默认 true + 记 errors（文案：『条件表达式无法求值，已按 true 展开——可在预览面板用开关覆盖』）」，与 `${}`/bind 的错误形态对齐。面板开关覆盖行为不动（第一真值源语义保留）。这是本任务对预览正确性的实质修复。

**T-EP-03（条件行声明性标注）**：`XmlConditionView` 增加分类标注字段（xml 层字符串），条件行 UI 按 `UNSUPPORTED_SYNTAX`/`EVAL_FALLBACK` 渲染淡色标注（「不支持的表达式形态：三元运算」/「含运行期降级调用」），tooltip 给理由。预检在 `XmlPreviewModel` 构建 conditions 时对每个 test 表达式跑 `classify`（纯 xml 层，零 PSI）。不改展开行为——标注是提示，行为由开关与 T-EP-02 的错误记录管。

**T-EP-04（测试与文档）**：新开 `OgnlExprClassifyTest`（表驱动：expr → 预期三档），不混入现有 18 个锚点方法；T-EP-02 的错误记录用 `XmlPreviewModelTest`/`IfSqlNodeTest` 扩用例钉住；「白名单方法+不匹配载体」归 `EVAL_FALLBACK` 档的边界用例（`map.length()` 形态）显式测试。CHANGELOG [Unreleased] + 变更记录行 + 第七轮报告 Z-7 落地备注回填。

## 四、验收标准

1. 三档分类表驱动全绿；`OgnlEvaluatorTest` 既有 18 方法零改动全绿（求值行为不变）。
2. if test 失败不再静默（errors 可见），面板开关覆盖路径行为逐字节不变。
3. xml 层零 PSI 依赖保持（classify 纯 AST 遍历，testFast 通道可跑）。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. test 写三元表达式的 mapper：条件行显示「不支持」标注 + `⚠` 行出现（T-EP-02）。
2. test 写 `list.size() > 0`：SUPPORTED 无标注；`list.remove(0)`：EVAL_FALLBACK 标注。
3. 深浅色主题标注可见性。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 分类器与求值器漂移 | 支持面演进后分类清单忘记同步 | 分类清单常量与 KNOWN_METHODS 同文件定义；用「classify(SUPPORTED) 的表达式求值必须非 Err」属性测试抽样钉住 |
| T-EP-02 改变既有静默行为 | 依赖静默 true 的用户预览突然多警示行 | 本质是正确性修复；文案明示开关可覆盖；CHANGELOG 记行为变更 |
| 静态字段形态跨层 | 静态字段值由 PSI 层注入，xml 层 classify 只见引用形态 | 静态字段引用归 EVAL_FALLBACK 档（xml 层无法判定可用性），不越层判定 |

## 七、实施顺序

```
T-EP-01（分类器+表驱动测试）→ T-EP-02（if test 静默点收口）→ T-EP-03（条件行标注）→ T-EP-04（文档回填）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

（未实施）
