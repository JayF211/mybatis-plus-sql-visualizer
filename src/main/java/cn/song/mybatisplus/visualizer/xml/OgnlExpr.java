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
}
