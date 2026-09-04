package cn.song.mybatisplus.visualizer.psource;

import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * SQL 字符串拼接聚合器（JDBC 模板 SQL 字符串预览）：把分散在字面量 `+` 拼接 /
 * StringBuilder 分片 / 变量引用中的 SQL 片段聚合为单一文本。
 *
 * <p>两路实现：</p>
 * <ul>
 *  <li>路径 A — 字面量 `+` 拼接（`"SELECT " + "..."`）→ 递归收集 {@code PsiLiteralExpression} 并 join</li>
 *  <li>路径 B — StringBuilder 聚合（{@code new StringBuilder().append(...)} 或
 *      {@code StringBuilder sql = new StringBuilder(); sql.append(querySelect)...}）→
 *      收集同一方法体内对目标变量的 {@code append} 调用（按 offset 排序），递归聚合每个实参</li>
 * </ul>
 *
 * <p>变量引用沿 initializer 递归追踪（不跨方法体），含运行时值时标记 {@code complete=false}
 * 并在结果中保留占位标记。</p>
 *
 * <p>零 resolve（仅结构遍历 + initializer 读取），单测可注入假 PSI。</p>
 */
public final class SqlStringConcatenator {

    /** Kotlin object 单例兼容入口。 */
    public static final SqlStringConcatenator INSTANCE = new SqlStringConcatenator();

    private SqlStringConcatenator() {
    }

    /** 字符串聚合结果。 */
    public record ConcatenationResult(
            /** 聚合后的 SQL 文本（含可能残留的运行时值占位标记）。 */
            String sql,
            /** true = 完全静态可聚合；false = 含运行时值（预览降级）。 */
            boolean complete
    ) {
    }

    /** 运行时值占位标记前缀（预览 warning 与 SQL 中保留位置）。 */
    public static final String DYNAMIC_PLACEHOLDER_PREFIX = "/*运行时值*/";

    /** 变量 initializer 追踪最大深度（防循环引用）。 */
    private static final int MAX_TRACE_DEPTH = 5;

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 聚合表达式为 SQL 文本。
     *
     * @param expr SQL 字符串实参表达式（字面量 / 拼接 / 变量引用 / StringBuilder 变量）
     * @return 聚合结果；无法聚合返回 {@code null}
     */
    public static ConcatenationResult concat(PsiExpression expr) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof PsiLiteralExpression) {
            return concatLiteral((PsiLiteralExpression) expr);
        }
        if (expr instanceof PsiBinaryExpression || expr instanceof PsiPolyadicExpression) {
            return concatPolyadic(expr);
        }
        if (expr instanceof PsiReferenceExpression) {
            return concatReference((PsiReferenceExpression) expr);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 路径 A — 字面量 + 拼接
    // ------------------------------------------------------------------

    private static ConcatenationResult concatLiteral(PsiLiteralExpression literal) {
        Object value = literal.getValue();
        if (!(value instanceof String)) {
            return null;
        }
        return new ConcatenationResult((String) value, true);
    }

    private static ConcatenationResult concatPolyadic(PsiExpression expr) {
        if (!(expr instanceof PsiPolyadicExpression)) {
            return null;
        }
        PsiPolyadicExpression polyadic = (PsiPolyadicExpression) expr;
        if (polyadic.getOperationTokenType() != JavaTokenType.PLUS) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        boolean complete = true;
        for (PsiExpression operand : polyadic.getOperands()) {
            ConcatenationResult r = concat(operand);
            if (r == null) {
                // 不可静态求值的操作数（运行时值）→ 标记降级 + 保留占位
                complete = false;
                parts.add(DYNAMIC_PLACEHOLDER_PREFIX + operand.getText() + "*/");
            } else {
                if (!r.complete()) {
                    complete = false;
                }
                parts.add(r.sql());
            }
        }
        return new ConcatenationResult(String.join("", parts), complete);
    }

    // ------------------------------------------------------------------
    // 变量引用（路径 A/B 共享）
    // ------------------------------------------------------------------

    private static ConcatenationResult concatReference(PsiReferenceExpression ref) {
        return concatReference(ref, 0);
    }

    private static ConcatenationResult concatReference(PsiReferenceExpression ref, int depth) {
        if (depth > MAX_TRACE_DEPTH) {
            return null;
        }
        PsiElement resolved = ref.resolve();
        if (!(resolved instanceof PsiVariable)) {
            return null;
        }
        PsiVariable variable = (PsiVariable) resolved;
        PsiExpression initializer = variable.getInitializer();
        if (initializer == null) {
            return null;
        }
        if (initializer instanceof PsiLiteralExpression) {
            return concatLiteral((PsiLiteralExpression) initializer);
        }
        if (initializer instanceof PsiBinaryExpression || initializer instanceof PsiPolyadicExpression) {
            return concatPolyadic(initializer);
        }
        if (initializer instanceof PsiReferenceExpression) {
            return concatReference((PsiReferenceExpression) initializer, depth + 1);
        }
        if (initializer instanceof PsiNewExpression) {
            return concatNewStringBuilder((PsiNewExpression) initializer, variable, depth);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 路径 B — StringBuilder 聚合
    // ------------------------------------------------------------------

    /**
     * 聚合 `new StringBuilder(...)` 初值 + 同一方法体内全部 `append` 调用。
     *
     * @param newExpr      `new StringBuilder(...)` 表达式
     * @param variable     持有该 new 表达式的变量（用于查找 append 调用）
     * @param depth        当前追踪深度
     */
    private static ConcatenationResult concatNewStringBuilder(
            PsiNewExpression newExpr,
            PsiVariable variable,
            int depth
    ) {
        List<String> parts = new ArrayList<>();
        boolean complete = true;

        // 初值（`new StringBuilder("SELECT ...")`）
        PsiExpressionList argumentList = newExpr.getArgumentList();
        PsiExpression[] args = argumentList != null ? argumentList.getExpressions() : null;
        if (args != null && args.length > 0) {
            PsiExpression initExpr = args[0];
            ConcatenationResult r = concat(initExpr);
            if (r == null) {
                complete = false;
                parts.add(DYNAMIC_PLACEHOLDER_PREFIX + initExpr.getText() + "*/");
            } else {
                if (!r.complete()) {
                    complete = false;
                }
                parts.add(r.sql());
            }
        }

        // 查找同一方法体内对目标变量的 `append` 调用
        List<ConcatenationResult> appendResults = collectAppendCalls(variable, depth);
        for (ConcatenationResult r : appendResults) {
            if (!r.complete()) {
                complete = false;
            }
            parts.add(r.sql());
        }

        String sql = String.join("", parts);
        if (sql.isBlank() && appendResults.isEmpty()) {
            return null;
        }
        return new ConcatenationResult(sql, complete);
    }

    /**
     * 收集同一方法体内对目标变量的 `append` 调用（按 offset 排序）。
     *
     * <p>查找范围：目标变量所在方法体（PsiCodeBlock），过滤
     * {@code methodExpression.referenceName == "append"} 且 qualifier 解析到目标变量的调用。</p>
     */
    private static List<ConcatenationResult> collectAppendCalls(PsiVariable variable, int depth) {
        PsiMethod method = PsiTreeUtil.getParentOfType(variable, PsiMethod.class);
        if (method == null) {
            return Collections.emptyList();
        }
        PsiElement body = method.getBody();
        if (body == null) {
            return Collections.emptyList();
        }
        String varName = variable.getName();
        if (varName == null || varName.isBlank()) {
            return Collections.emptyList();
        }

        List<PsiMethodCallExpression> matched = new ArrayList<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression.class)) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null || !"append".equals(methodName)) {
                continue;
            }
            // qualifier 可以是直接变量引用，也可以是链式方法调用（sql.append("A").append(x)）——
            // 沿 qualifier 链回溯到根变量，判定是否为目标变量
            PsiVariable rootVar = resolveRootVariable(call.getMethodExpression().getQualifierExpression());
            if (rootVar == null) {
                continue;
            }
            if (rootVar == variable) {
                matched.add(call);
            }
        }
        // 按 startOffset 升序；链式调用中多层 append 共享同一 startOffset（都从 `sql` 起），
        // 此时按文本长度升序（内层最短 → 排前面），保证 append 实参按正确顺序拼接
        matched.sort(Comparator.comparingInt((PsiMethodCallExpression c) -> c.getTextRange().getStartOffset())
                .thenComparingInt(PsiMethodCallExpression::getTextLength));

        List<ConcatenationResult> results = new ArrayList<>();
        for (PsiMethodCallExpression call : matched) {
            PsiExpression[] args = call.getArgumentList().getExpressions();
            if (args.length == 0) {
                continue;
            }
            PsiExpression argExpr = args[0];
            ConcatenationResult r = concat(argExpr);
            if (r == null) {
                results.add(new ConcatenationResult(
                        DYNAMIC_PLACEHOLDER_PREFIX + argExpr.getText() + "*/",
                        false
                ));
            } else {
                results.add(r);
            }
        }
        return results;
    }

    /**
     * 沿 qualifier 链回溯根变量。
     *
     * <p>处理两种形态：</p>
     * <ul>
     *  <li>直接调用 `sql.append(...)` → qualifier 是 PsiReferenceExpression → 直接解析</li>
     *  <li>链式调用 `sql.append("A").append(x).append("B")` → 外层 qualifier 是 PsiMethodCallExpression →
     *      递归取 qualifier 的 qualifier，直到回溯到 PsiReferenceExpression</li>
     * </ul>
     *
     * @param qualifier 方法调用的 qualifier 表达式（可能 null）
     * @return 根变量；无法解析返回 null
     */
    private static PsiVariable resolveRootVariable(PsiExpression qualifier) {
        PsiExpression current = qualifier;
        while (current != null) {
            if (current instanceof PsiReferenceExpression) {
                PsiElement resolved = ((PsiReferenceExpression) current).resolve();
                return resolved instanceof PsiVariable ? (PsiVariable) resolved : null;
            }
            if (current instanceof PsiMethodCallExpression) {
                current = ((PsiMethodCallExpression) current).getMethodExpression().getQualifierExpression();
                continue;
            }
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 递归收集 PSI 树中全部 `PsiLiteralExpression` 的字符串值（路径 A 兜底）。
     * 用于处理嵌套拼接表达式。
     */
    public static List<String> collectStringLiterals(PsiElement element) {
        if (element instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) element).getValue();
            if (!(value instanceof String)) {
                return Collections.emptyList();
            }
            return Collections.singletonList((String) value);
        }
        List<String> out = new ArrayList<>();
        for (PsiElement child : element.getChildren()) {
            out.addAll(collectStringLiterals(child));
        }
        return out;
    }
}
