package cn.song.mybatisplus.visualizer.xml;

import java.util.Set;

/**
 * bind 变量声明节点（T-M24-01）：复刻 MyBatis 3.5.19 VarDeclSqlNode（3.5.12 前名 BindSqlNode）。
 *
 * 执行到该节点时对 value 表达式求值并绑定；同名覆盖。面板覆盖名（DynamicContext.isProtected）
 * 优先——跳过求值、保留用户输入值。
 */
public final class VarDeclSqlNode implements SqlNode {

    private final String name;
    private final String expression;

    public VarDeclSqlNode(String name, String expression) {
        this.name = name;
        this.expression = expression;
    }

    public String getName() {
        return name;
    }

    public String getExpression() {
        return expression;
    }

    @Override
    public boolean apply(DynamicContext context) {
        if (context.isProtected(name)) {
            return true;
        }
        // T-M25.2：||X|| 非标准包裹——真实 OGNL 运行时会解析失败，预览兼容并按内部表达式解析
        String trimmed = expression.trim();
        if (trimmed.startsWith("||") && trimmed.endsWith("||")) {
            context.getErrors().add("bind 表达式被 || 包裹（非标准 OGNL，MyBatis 运行时会解析失败），已按内部表达式兼容解析");
        }
        EvalResult result = OgnlEvaluator.evaluate(expression, context.getBindings(), context.getStaticFieldValues());
        if (result instanceof EvalResult.Ok ok) {
            if (ok.value() == null) {
                // T-M25.2：静态常量引用求值为空 → 提示可填写覆盖值
                Set<String> refs = OgnlEvaluator.staticFieldRefs(expression);
                if (!refs.isEmpty()) {
                    context.getErrors().add("静态常量 " + String.join(", ", refs) + " 求值为空（常量类/字段不可解析或未注入，可在预览中填写覆盖值）");
                }
            }
            context.bind(name, ok.value());
        } else {
            EvalResult.Err err = (EvalResult.Err) result;
            context.getErrors().add("无法解析 bind 表达式 '" + expression + "'（" + err.message() + "，可在预览中填写覆盖值）");
            context.bind(name, null);
        }
        return true;
    }
}
