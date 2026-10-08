package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * R49：Wrapper 布尔条件探镜（S~M 快件）——`eq(condition, col, val)` 布尔首参的求值结果标注。
 *
 * 与 R30（XML `<if>` 三态标注）同域：R30 标 XML 条件求值，本件标 Wrapper 布尔前缀。纯只读探针，
 * 渲染行为零变更——只往条件行尾追加一个淡色标注（[cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.buildConditionRow]）。
 *
 * ## V73 证伪结论（先读码后动手）
 *
 * 1. **flagSource 来源方法全集与语义分裂**（`ConditionToggle.flagSource` 四类生产者）：
 *    - `QueryWrapperParser.analyzeFlag`（[CONDITION_METHODS] 18 个方法：eq/ne/gt/ge/lt/le/eqOrIsNull/
 *      like/notLike/likeLeft/likeRight/in/notIn/between/notBetween/isNull/isNotNull/allEq，
 *      `QueryWrapperParser.kt:617-627` 实测计数）——
 *      字面量 true/false → `"true"/"false"`，运行时布尔 → 表达式原文；
 *    - `QueryWrapperParser.stripGroupFlag`（and/or/nested/not/func 组方法）——同上；
 *    - `QueryWrapperParser.parseRawTemplate`（apply/exists 布尔前缀）——同上，另有非值实参
 *      `hasFlag=true, flagSource=null` 的窄边界；
 *    - `EasyQueryConverter.toggleOf`（[Flag]）——**语义不同**：字面量 true 折叠成 `null`（
 *      `Flag(null, true)`，`EasyQueryConverter.kt:702`），只保留下 false 与运行时表达式
 *      （`:703`/`:706` 实测）。
 *    → 结论：`flagSource` 语义在生产者间**不一致**；本探镜只标注「可证明是布尔重载」的形态
 *    （`"true"/"false"` 与运行时占位符），`null` 一律不标注——宁可漏标 EasyQuery 字面量 true
 *    （它与「无布尔重载条件」在 `ConditionToggle` 上不可区分），也不把非布尔条件误标。
 * 2. **布尔重载识别率**：解析期经 [cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator]（`asValue`）
 *    已求值——字面量 true/false、可追踪变量（`boolean f = true; eq(f, …)` → `"true"`）、
 *    运行时表达式（占位符）三档全覆盖；字符串字面量（`eq("col", …)`）正确排除。
 * 3. **缺省 true 语义文案风险**：MP `condition` 参数缺省 true——运行时布尔占位符默认勾选、
 *    预览按恒真渲染。若只显示 flag 原文（tooltip），用户会误以为运行时表达式被求值成真。
 *    故本探镜把「字面量 true」与「运行时占位符（缺省 true）」**分开标注**：前者「恒真」、
 *    后者「恒真·缺省」——避免把「缺省当求值」。
 */
enum class WrapperFlagOutcome {
    /** 字面量 `true`：静态恒真。 */
    ALWAYS_TRUE,

    /** 字面量 `false`：静态恒假。 */
    ALWAYS_FALSE,

    /** 运行时布尔占位符：MP condition 缺省 true，预览按恒真渲染（缺省，非求值）。 */
    DEFAULT_TRUE,
}

/**
 * 由 [ConditionToggle.flagSource] 派生探镜结果；null = 不可证明是布尔重载（不标注）。
 *
 * 只按字面值 `"true"/"false"` 精确匹配——四类生产者对字面量的产出即这两个小写串
 * （[ExpressionEvaluator] 布尔字面量 `value.toString()`，恒小写）；其余非 null 一律按运行时占位符。
 */
fun wrapperFlagOutcome(flagSource: String?): WrapperFlagOutcome? = when (flagSource) {
    null -> null
    "true" -> WrapperFlagOutcome.ALWAYS_TRUE
    "false" -> WrapperFlagOutcome.ALWAYS_FALSE
    else -> WrapperFlagOutcome.DEFAULT_TRUE
}

/** 探镜标注文案（行尾短句）。 */
fun wrapperFlagOutcomeText(outcome: WrapperFlagOutcome): String = when (outcome) {
    WrapperFlagOutcome.ALWAYS_TRUE -> MpSqlVisualizerBundle.message("panel.wrapper.outcome.alwaysTrue")
    WrapperFlagOutcome.ALWAYS_FALSE -> MpSqlVisualizerBundle.message("panel.wrapper.outcome.alwaysFalse")
    WrapperFlagOutcome.DEFAULT_TRUE -> MpSqlVisualizerBundle.message("panel.wrapper.outcome.defaultTrue")
}

/** 探镜标注 tooltip（带出布尔标志原文，与 [wrapperFlagOutcomeText] 同渲染面）。 */
fun wrapperFlagOutcomeTooltip(flagSource: String, outcome: WrapperFlagOutcome): String =
    MpSqlVisualizerBundle.message("panel.wrapper.outcome.tooltip", flagSource, wrapperFlagOutcomeText(outcome))
