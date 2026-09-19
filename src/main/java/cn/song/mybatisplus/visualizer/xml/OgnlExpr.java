package cn.song.mybatisplus.visualizer.xml;

/**
 * 表达式 AST（T-M24-02）：OGNL 受限子集的抽象语法树节点。
 */
public sealed interface OgnlExpr
        permits OgnlExpr.Literal, OgnlExpr.ListLiteral, OgnlExpr.RootRef,
        OgnlExpr.StaticFieldRef, OgnlExpr.Chain, OgnlExpr.Unary, OgnlExpr.Binary {

    /** 字面量（数字 / 字符串 / 布尔 / null / 列表）。 */
    record Literal(Object value) implements OgnlExpr {}

    /** 列表字面量（{a, b}）。 */
    record ListLiteral(java.util.List<OgnlExpr> items) implements OgnlExpr {}

    /** 根变量引用（从 bindings 取值的起始标识符）。 */
    record RootRef(String name) implements OgnlExpr {}

    /** 静态字段引用（T-M25.2）：{@code @com.xxx.Class@FIELD}。 */
    record StaticFieldRef(String className, String fieldName) implements OgnlExpr {}

    /** 引用链：基础表达式（RootRef 或字面量）+ 后缀步骤。 */
    record Chain(OgnlExpr base, java.util.List<ChainStep> steps) implements OgnlExpr {}

    /** 一元运算（! / not / -）。 */
    record Unary(String op, OgnlExpr operand) implements OgnlExpr {}

    /** 二元运算（and / or / == / != / < ...）。 */
    record Binary(String op, OgnlExpr left, OgnlExpr right) implements OgnlExpr {}

    /**
     * 表达式支持面分档（T-ExprAstPrecheck T-EP-01）：把「求值失败/静默降级」提前成声明性判定。
     *
     * <p>支持面清单不另造语义，直接取自求值器既有事实：解析器能产出的 AST 节点与运算符、
     * {@code OgnlEvaluator} 方法白名单、{@code OgnlEvaluator} 的属性/getter 访问语义。</p>
     */
    enum ExprSupport {
        /** 语法与语义都在受限子集内（含属性链、下标、getter 形态、载体无关的白名单方法）。 */
        SUPPORTED,

        /** 语法即超出受限子集（三元 / 赋值 / lambda / 静态方法 / new 等），解析返回 {@link ParseResult.Err}。 */
        UNSUPPORTED_SYNTAX,

        /** 语法可解析，但含求值期降级形态（未知方法链 / 载体受限的白名单方法 / 静态字段引用）——求值会返回 null 而不报错。 */
        EVAL_FALLBACK,
    }

    /** 分档理由码（T-EP-03 条件行标注 tooltip 用；UI 侧映射 bundle 文案，xml 层不持有文案）。 */
    enum ExprReason {
        /** 解析失败（{@link ExprSupport#UNSUPPORTED_SYNTAX} 的唯一理由）。 */
        SYNTAX,

        /** 未知方法调用：不在白名单内且非 getter 形态，求值静默返回 null。 */
        UNKNOWN_METHOD,

        /** 白名单方法在接收者载体上无实现（如 {@code length()} 只对字符串有实现），求值静默返回 null。 */
        NARROW_CARRIER,

        /** 静态字段引用：取值由 PSI 层注入，xml 层无法判定可用性，未注入时求值为 null。 */
        STATIC_FIELD,
    }

    /**
     * 分类结论：档位 + 理由码 + 细节（方法名 / {@code @类@字段} / 解析错误消息）。
     * {@link ExprSupport#SUPPORTED} 时 {@code reason} 与 {@code detail} 均为 null。
     *
     * @param support 支持面档位
     * @param reason  分档理由码（SUPPORTED 时为 null）
     * @param detail  理由细节（方法名 / {@code @类@字段} / 解析错误消息）
     */
    record ExprClassification(ExprSupport support, ExprReason reason, String detail) {

        /** Kotlin 默认参数等价重载：{@code reason} / {@code detail} 缺省为 null（SUPPORTED 档形态）。 */
        public ExprClassification(ExprSupport support) {
            this(support, null, null);
        }
    }
}
