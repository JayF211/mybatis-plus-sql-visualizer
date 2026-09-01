package cn.song.mybatisplus.visualizer.xml;

/**
 * 条件节点（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.IfSqlNode。
 *
 * 真值判定顺序：
 *  1. 预览面板条件开关（DynamicContext.conditionResult，id = test 表达式原文）→ 强制真值；
 *  2. 否则 OGNL 求值（判真规则见 OgnlEvaluator.isTrue）；
 *  3. 表达式解析失败 → 默认展开（true，预览容错——面板开关兜底）。
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
        if (t) {
            contents.apply(context);
        }
        return t;
    }

    private boolean evaluate(DynamicContext context) {
        EvalResult result = OgnlEvaluator.evaluate(test, context.getBindings(), context.getStaticFieldValues());
        if (result instanceof EvalResult.Ok ok) {
            return OgnlEvaluator.isTrue(ok.value());
        }
        return true; // 解析失败：预览默认展开（面板开关可改）
    }
}
