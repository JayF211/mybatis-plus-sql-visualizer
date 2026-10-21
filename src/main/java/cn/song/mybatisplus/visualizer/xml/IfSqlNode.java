package cn.song.mybatisplus.visualizer.xml;

/**
 * 条件节点（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.IfSqlNode。
 *
 * 真值判定顺序：
 *  1. 预览面板条件开关（DynamicContext.conditionResult，id = test 表达式原文）→ 强制真值；
 *  2. 否则 OGNL 求值（判真规则见 OgnlEvaluator.isTrue）；
 *  3. 表达式无法求值 → 默认展开（true，预览容错——面板开关兜底），并记 DynamicContext.errors
 *     使「这个条件其实没算出来」在预览里可见（T-ExprAstPrecheck T-EP-02，与 ${}/bind 错误形态对齐）。
 */
public final class IfSqlNode implements SqlNode {

    private final SqlNode contents;
    private final String test;

    public IfSqlNode(SqlNode contents, String test) {
        this.contents = contents;
        this.test = test;
    }

    public SqlNode getContents() {
        return contents;
    }

    public String getTest() {
        return test;
    }

    @Override
    public boolean apply(DynamicContext context) {
        Boolean truth = context.conditionResult(test);
        boolean t = truth != null ? truth : evaluate(context);
        // R30 探镜：登记本轮判定结果（只读观测，不改渲染语义——真值来源不受影响）
        context.recordConditionOutcome(test, t);
        if (t) {
            contents.apply(context);
        }
        return t;
    }

    private boolean evaluate(DynamicContext context) {
        // T-ExprAstPrecheck：表达式引用了静态读不到的对象属性载体 → 预警（求值照旧，展开行为不变）
        java.util.Set<String> unknownPaths = OgnlEvaluator.unsupportedPropertyPaths(test, context.getBindings());
        if (!unknownPaths.isEmpty()) {
            context.getErrors().add("条件表达式无法静态读取对象属性 " + String.join(", ", unknownPaths)
                    + "：'" + test + "'；请核对运行时 SQL 或在预览面板覆盖条件");
        }
        EvalResult result = OgnlEvaluator.evaluate(test, context.getBindings(), context.getStaticFieldValues());
        if (result instanceof EvalResult.Ok ok) {
            return OgnlEvaluator.isTrue(ok.value());
        }
        EvalResult.Err err = (EvalResult.Err) result;
        // T-EP-02：不再静默 true——展开行为不变（面板开关是第一真值源，开关缺席时预览容错），
        // 但错误必须可见，否则预览产物与真实运行时可能就此分叉而无人知晓
        context.getErrors().add("条件表达式无法求值，已按 true 展开——可在预览面板用开关覆盖：'" + test + "'（" + err.message() + "）");
        return true;
    }
}
