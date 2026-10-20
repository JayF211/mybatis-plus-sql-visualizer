package cn.song.mybatisplus.visualizer.model;

/**
 * R49：Wrapper 布尔条件探镜（S~M 快件）——{@code eq(condition, col, val)} 布尔首参的求值结果标注。
 *
 * <p>与 R30（XML {@code <if>} 三态标注）同域：R30 标 XML 条件求值，本件标 Wrapper 布尔前缀。纯只读探针，
 * 渲染行为零变更——只往条件行尾追加一个淡色标注（SqlPreviewPanel.buildConditionRow）。
 *
 * <h2>V73 证伪结论（先读码后动手）</h2>
 *
 * <ol>
 * <li><b>flagSource 来源方法全集与语义分裂</b>（{@code ConditionToggle.flagSource} 四类生产者）：
 *   <ul>
 *   <li>{@code QueryWrapperParser.analyzeFlag}（18 个条件方法：eq/ne/gt/ge/lt/le/eqOrIsNull/
 *       like/notLike/likeLeft/likeRight/in/notIn/between/notBetween/isNull/isNotNull/allEq）——
 *       字面量 true/false → {@code "true"/"false"}，运行时布尔 → 表达式原文；</li>
 *   <li>{@code QueryWrapperParser.stripGroupFlag}（and/or/nested/not/func 组方法）——同上；</li>
 *   <li>{@code QueryWrapperParser.parseRawTemplate}（apply/exists 布尔前缀）——同上，另有非值实参
 *       {@code hasFlag=true, flagSource=null} 的窄边界；</li>
 *   <li>{@code EasyQueryConverter.toggleOf}（Flag）——<b>本件收口前语义不同</b>：字面量 true 曾被折叠成
 *       {@code null}（原 {@code Flag(null, true)}），只保住 false 与运行时表达式。<b>R49 收口已统一</b>：
 *       字面量 true 现与 false 一样携带源码文本（{@code Flag("true", true)}），null 归位「非布尔重载」单一语义
 *       ——故本探镜对 EasyQuery 字面量 true 同样标注「恒真」，四条生产者口径一致。</li>
 *   </ul>
 *   → 准则：{@code flagSource} 非 null ＝ 可证明是布尔重载（标注），null ＝ 不标注（不猜）。</li>
 * <li><b>布尔重载识别率</b>：解析期经 ExpressionEvaluator（asValue）已求值——字面量 true/false、
 *     可追踪变量（{@code boolean f = true; eq(f, …)} → {@code "true"}）、运行时表达式（占位符）三档全覆盖；
 *     字符串字面量（{@code eq("col", …)}）正确排除。</li>
 * <li><b>缺省 true 语义文案风险</b>：MP {@code condition} 参数缺省 true——运行时布尔占位符默认勾选、
 *     预览按恒真渲染。若只显示 flag 原文（tooltip），用户会误以为运行时表达式被求值成真。
 *     故本探镜把「字面量 true」与「运行时占位符（缺省 true）」<b>分开标注</b>：前者「恒真」、
 *     后者「恒真·缺省」——避免把「缺省当求值」。</li>
 * </ol>
 */
public enum WrapperFlagOutcome {
    /** 字面量 {@code true}：静态恒真。 */
    ALWAYS_TRUE,

    /** 字面量 {@code false}：静态恒假。 */
    ALWAYS_FALSE,

    /** 运行时布尔占位符：MP condition 缺省 true，预览按恒真渲染（缺省，非求值）。 */
    DEFAULT_TRUE,
}
