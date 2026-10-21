package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.model.SqlFragment;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** 片段中用户自写、无可追溯来源的占位符所用的中性来源名（保持参数表与 `?` 数量对齐）。 */
    private static final String UNKNOWN_PARAM = "?";

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
        // M38.2（语义对照矩阵实证）：一元负/正号 + 数值字面量（`-1`、`-1.5`）→ 静态字面量——
        // Java 没有负数字面量 token，`-1` 是 PsiPrefixExpression，落 else 分支会被误判为运行时占位符
        if (expression instanceof PsiPrefixExpression) {
            return evaluatePrefix((PsiPrefixExpression) expression);
        }
        return new SqlValue.Placeholder(expression.getText());
    }

    /**
     * 一元 +/- 表达式求值（M38.2）：仅当操作数是数值字面量时静态求值（{@code -1} → {@code -1}），
     * 其余（变量/方法调用取负）降级为占位符。
     */
    private SqlValue evaluatePrefix(PsiPrefixExpression expr) {
        if (expr.getOperand() == null) return new SqlValue.Placeholder(expr.getText());
        SqlValue inner = evaluateInternal(expr.getOperand());
        if (!(inner instanceof SqlValue.Literal)) return new SqlValue.Placeholder(expr.getText());
        String operandText = expr.getOperand() instanceof PsiLiteralExpression lit ? lit.getText() : null;
        if (operandText == null) return new SqlValue.Placeholder(expr.getText());
        if (!operandText.matches("-?\\d+(\\.\\d+)?([fFlLdD])?")) return new SqlValue.Placeholder(expr.getText());
        // SQL 数值字面量不带 Java 后缀（`5L` → `5`、`1.5f` → `1.5`），与 evaluateLiteral 数值路径一致
        String plain = operandText.replaceAll("[fFlLdD]+$", "");
        if (expr.getOperationTokenType() == JavaTokenType.MINUS) {
            return new SqlValue.Literal("-" + plain, "-" + plain);
        }
        if (expr.getOperationTokenType() == JavaTokenType.PLUS) {
            return new SqlValue.Literal(plain, plain);
        }
        return new SqlValue.Placeholder(expr.getText());
    }

    /**
     * 求值字符串拼接表达式（清单 P11 {@code selectAs("alias.col" ...)}、P12 {@code last("LIMIT " + N)}）。
     *
     * <p>仅当连接符全为 {@code +} 且所有操作数可静态求值（字面量/可追踪变量/嵌套拼接）时拼接为字符串；
     * 否则降级为占位符（运行时值），同时尽力给出该表达式的<b>原始 SQL 片段</b>形态（{@link #fragmentOf}）。</p>
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
                return new SqlValue.Placeholder(expr.getText(), fragmentOf(expr));
            }
        }
        String joined = String.join("", parts);
        return new SqlValue.Literal("'" + joined.replace("'", "''") + "'", joined);
    }

    /**
     * 拼接表达式的「原始 SQL 片段」形态（T-UpdateSetSqlConcat）：静态段保留、运行期段写 {@code ?}，
     * 同时按文本顺序记录每个占位符的来源（片段里的 {@code ?} 不登记进参数表就会让面板回填整体错位）。
     *
     * <p>例：{@code "amount = amount * " + rate} → 文本 {@code amount = amount * ?}、来源 {@code [rate]}。</p>
     *
     * <p>来源按<b>真实占位符位点</b>收敛：字符串字面量/注释内的 {@code ?} 不计（{@code first("/*+ ...(" + ms + ") *&#47;")}
     * 的 {@code ?} 在块注释里，不能凭空多出一个来源——那会让面板把后面的值填错位）。</p>
     *
     * <p>只在整体是字符串拼接时才给值——数值算术（{@code 1 + n}）不是 SQL 片段，给 null 以免拼出 {@code 1?}。</p>
     */
    private SqlFragment fragmentOf(PsiPolyadicExpression expr) {
        if (expr.getOperationTokenType() != JavaTokenType.PLUS) return null;
        if (!isStringConcat(expr)) return null;
        StringBuilder text = new StringBuilder();
        // 文本位点 → 来源（仅用于最终按真实占位符位点过滤；注释/引号内的会被丢弃）
        List<Integer> markPositions = new ArrayList<>();
        List<String> markSources = new ArrayList<>();
        for (PsiExpression operand : expr.getOperands()) {
            SqlValue v = evaluateInternal(operand);
            if (v instanceof SqlValue.Literal lit) {
                // 字面量里用户自写的 `?` 也是一次占位（注释/引号内的会被最终过滤掉）
                String unquoted = lit.getUnquoted();
                for (int index = 0; index < unquoted.length(); index++) {
                    if (unquoted.charAt(index) == '?') {
                        markPositions.add(text.length() + index);
                        markSources.add(UNKNOWN_PARAM);
                    }
                }
                text.append(unquoted);
            } else if (v instanceof SqlValue.Placeholder ph) {
                SqlFragment nested = ph.getFragment();
                if (nested != null) {
                    // 嵌套拼接：按嵌套文本的**真实占位符位点**逐位对齐来源（引号/注释内的 `?` 不参与）
                    List<Integer> realOffsets = PlaceholderPositionsKt.placeholderPositions(nested.getText());
                    List<String> nestedParams = nested.getParams();
                    for (int i = 0; i < nestedParams.size(); i++) {
                        if (i < realOffsets.size()) {
                            markPositions.add(text.length() + realOffsets.get(i));
                            markSources.add(nestedParams.get(i));
                        }
                    }
                    text.append(nested.getText());
                } else {
                    // 运行期段：写 `?` 并登记来源
                    markPositions.add(text.length());
                    markSources.add(ph.getSource());
                    text.append('?');
                }
            }
        }
        Set<Integer> effective = new HashSet<>(PlaceholderPositionsKt.placeholderPositions(text.toString()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < markPositions.size(); i++) {
            if (effective.contains(markPositions.get(i))) {
                out.add(markSources.get(i));
            }
        }
        return new SqlFragment(text.toString(), out);
    }

    /**
     * 是否为字符串拼接：类型可解析时按 {@code String} 判定（Java 里 {@code String + 任意类型} 结果恒为 String），
     * 类型解析不到（light fixture 缺 classpath 等）时回落到「任一操作数静态求值为字符串/字符字面量」
     * ——判据取求值结果而非 PSI 字面量，这样变量追踪到的静态前缀（{@code String p = "a = "; p + x}）也能识别。
     */
    private boolean isStringConcat(PsiPolyadicExpression expr) {
        PsiType type = expr.getType();
        if (type != null && type.equalsToText("java.lang.String")) return true;
        for (PsiExpression operand : expr.getOperands()) {
            SqlValue value = evaluateInternal(operand);
            // 字符串/字符字面量的 raw 带单引号（见 SqlValue.Literal KDoc），数值原样
            if (value instanceof SqlValue.Literal lit && lit.getRaw().startsWith("'")) return true;
        }
        return false;
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
