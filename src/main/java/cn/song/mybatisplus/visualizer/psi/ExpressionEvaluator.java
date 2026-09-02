package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.model.SqlValue;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * 表达式求值器（F-02 前置）：把 Wrapper 方法实参表达式求值为 {@link SqlValue}。
 *
 * <p>分级策略：
 * <ul>
 *   <li>字面量（数字 / 字符串 / 布尔 / char / null）：求值为 {@link SqlValue.Literal}，可直接渲染进 SQL</li>
 *   <li>变量引用：追踪同文件初始化赋值（M2-01，{@code Long deviceId = 1001L;} → {@code 1001}），
 *       支持多级引用链；追踪不到（方法参数等运行时值）求值为 {@link SqlValue.Placeholder}</li>
 *   <li>其他表达式（算术 / 方法调用 / new）：求值为 {@link SqlValue.Placeholder}，渲染为 {@code ?}</li>
 * </ul>
 *
 * <p>由 Kotlin {@code object ExpressionEvaluator} 转换而来。
 */
public final class ExpressionEvaluator {

    public static final ExpressionEvaluator INSTANCE = new ExpressionEvaluator();

    /** 变量追踪最大深度，防止循环引用死循环。 */
    private static final int MAX_TRACE_DEPTH = 5;

    private ExpressionEvaluator() {
    }

    /**
     * 求值表达式。
     *
     * @param expression 实参表达式（PSI）
     * @return SQL 值模型
     */
    public static SqlValue evaluate(PsiExpression expression) {
        return INSTANCE.evaluateInternal(expression);
    }

    private SqlValue evaluateInternal(PsiExpression expression) {
        if (expression instanceof PsiLiteralExpression) {
            return evaluateLiteral((PsiLiteralExpression) expression);
        }
        if (expression instanceof PsiReferenceExpression) {
            return evaluateReference((PsiReferenceExpression) expression, 0);
        }
        // `X.class` 类字面量（MPJ join 参数）→ 求值为简单类名（如 DeviceInfoEntity）
        if (expression instanceof PsiClassObjectAccessExpression) {
            return evaluateClassLiteral((PsiClassObjectAccessExpression) expression);
        }
        // 字符串拼接表达式（`"LIMIT " + N`、`ALIAS + ".username"`）→ 静态拼接
        if (expression instanceof PsiPolyadicExpression) {
            return evaluatePolyadic((PsiPolyadicExpression) expression);
        }
        // T-Mxx：getter 方法调用（`user.getId()`）→ 字段引用格式（`user.id`）
        if (expression instanceof PsiMethodCallExpression) {
            return evaluateMethodCall((PsiMethodCallExpression) expression);
        }
        return new SqlValue.Placeholder(expression.getText());
    }

    /**
     * 求值字符串拼接表达式（清单 P11 {@code selectAs("alias.col" ...)}、P12 {@code last("LIMIT " + N)}）。
     *
     * <p>仅当连接符全为 {@code +} 且所有操作数可静态求值（字面量/可追踪变量/嵌套拼接）时拼接为字符串；
     * 否则降级为占位符（运行时值）。
     */
    private SqlValue evaluatePolyadic(PsiPolyadicExpression expr) {
        if (expr.getOperationTokenType() != JavaTokenType.PLUS) {
            return new SqlValue.Placeholder(expr.getText());
        }
        List<String> parts = new ArrayList<>();
        for (PsiExpression operand : expr.getOperands()) {
            SqlValue v = evaluateInternal(operand);
            if (v instanceof SqlValue.Literal) {
                parts.add(((SqlValue.Literal) v).getUnquoted());
            } else {
                return new SqlValue.Placeholder(expr.getText());
            }
        }
        String joined = String.join("", parts);
        return new SqlValue.Literal("'" + joined.replace("'", "''") + "'", joined);
    }

    /**
     * 求值类字面量 {@code X.class} → 简单类名。
     * 直接从文本提取（如 {@code com.foo.X.class} → {@code X}），避免依赖类解析。
     */
    private SqlValue evaluateClassLiteral(PsiClassObjectAccessExpression expr) {
        String text = expr.getText();
        String withoutSuffix = text.endsWith(".class")
                ? text.substring(0, text.length() - ".class".length())
                : text;
        String trimmed = withoutSuffix.trim();
        int lastDot = trimmed.lastIndexOf('.');
        String className = lastDot >= 0 ? trimmed.substring(lastDot + 1) : trimmed;
        return new SqlValue.Literal(className);
    }

    /** 求值字面量。 */
    private SqlValue evaluateLiteral(PsiLiteralExpression literal) {
        Object value = literal.getValue();
        if (value == null) {
            // null 字面量 → SQL NULL
            return new SqlValue.Literal("NULL");
        }
        // 字符串 → 单引号包裹（转义内部单引号）；unquoted 保留原始值供 SQL 片段场景使用
        if (value instanceof String) {
            String s = (String) value;
            return new SqlValue.Literal("'" + s.replace("'", "''") + "'", s);
        }
        // 布尔
        if (value instanceof Boolean) {
            return new SqlValue.Literal(value.toString());
        }
        // 字符 → 单引号包裹
        if (value instanceof Character) {
            return new SqlValue.Literal("'" + value + "'", value.toString());
        }
        // 数值类型（Byte/Short/Int/Long/Float/Double）
        if (value instanceof Number) {
            return new SqlValue.Literal(value.toString());
        }
        // 其他（如文本块等）按文本原样
        return new SqlValue.Literal(literal.getText());
    }

    /**
     * T-Mxx：方法调用求值——识别 getter 方法（getXxx/isXxx）为字段名。
     * <ul>
     *   <li>{@code user.getId()} → Placeholder("id")</li>
     *   <li>{@code user.getName()} → Placeholder("name")</li>
     *   <li>{@code isActive()} → Placeholder("active")</li>
     *   <li>非 getter 方法（{@code user.toString()}、{@code list.get(0)}）→ Placeholder(expression.text)</li>
     * </ul>
     *
     * <p>仅处理无实参方法（getter 特征），有实参或无前缀匹配时降级为完整表达式文本。
     * 返回纯字段名（不带 qualifier 前缀），与 SQL 列名对齐。
     */
    private SqlValue evaluateMethodCall(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) {
            return new SqlValue.Placeholder(call.getText());
        }
        // 仅处理无实参的 getter/is 方法
        if (call.getArgumentList().getExpressions().length > 0) {
            return new SqlValue.Placeholder(call.getText());
        }
        String fieldName;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            fieldName = methodName.substring(3);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            fieldName = methodName.substring(2);
        } else {
            return new SqlValue.Placeholder(call.getText());
        }
        if (fieldName.isEmpty()) {
            return new SqlValue.Placeholder(call.getText());
        }
        // 首字母小写 → 字段名（纯字段名，不带 qualifier）
        return new SqlValue.Placeholder(
                Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1));
    }

    /**
     * 求值变量引用（M2-01 变量追踪）。
     *
     * <p>沿变量的初始化表达式递归追踪：
     * <ul>
     *   <li>{@code Long deviceId = 1001L;} → 字面量求值</li>
     *   <li>{@code Long a = b; Long b = 2L;} → 多级引用链（受 {@link #MAX_TRACE_DEPTH} 保护）</li>
     *   <li>无法静态确定（方法参数等）→ 占位符</li>
     * </ul>
     */
    private SqlValue evaluateReference(PsiReferenceExpression ref, int depth) {
        if (depth > MAX_TRACE_DEPTH) {
            return new SqlValue.Placeholder(ref.getText());
        }
        PsiElement resolved = ref.resolve();
        if (!(resolved instanceof PsiVariable)) {
            return new SqlValue.Placeholder(ref.getText());
        }
        PsiExpression initializer = ((PsiVariable) resolved).getInitializer();
        if (initializer == null) {
            return new SqlValue.Placeholder(ref.getText());
        }
        if (initializer instanceof PsiLiteralExpression) {
            return evaluateLiteral((PsiLiteralExpression) initializer);
        }
        if (initializer instanceof PsiReferenceExpression) {
            return evaluateReference((PsiReferenceExpression) initializer, depth + 1);
        }
        return new SqlValue.Placeholder(ref.getText());
    }
}
