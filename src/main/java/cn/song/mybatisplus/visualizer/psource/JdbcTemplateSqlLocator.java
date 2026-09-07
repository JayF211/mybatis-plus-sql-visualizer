package cn.song.mybatisplus.visualizer.psource;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import kotlin.Pair;

/**
 * JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串定位器（JDBC 模板 SQL 字符串预览）。
 *
 * <p>识别 Spring JDBC 模板方法的字符串 SQL 实参，返回聚合后的 SQL 调用信息。</p>
 *
 * <p>识别规则（对齐用户两段真实示例）：</p>
 * <ul>
 *  <li>示例一：{@code String sql = "SELECT ..." + "..."; ... .queryForList(sql, paramMap)} →
 *      实参为局部变量引用，沿 initializer 聚合字面量拼接</li>
 *  <li>示例二：{@code StringBuilder sql = new StringBuilder(); sql.append(querySelect)...;
 *      .selectListPagination(X.class, sql.toString(), paramMap)} →
 *      实参为 {@code sql.toString()}（方法调用），定位到 StringBuilder 变量后聚合 append 链</li>
 * </ul>
 *
 * <p>识别策略（方法名 O(1) 判定 + 实参结构分析）：</p>
 * <ol>
 *  <li>方法名 ∈ JDBC_TEMPLATE_METHODS（大小写不敏感）</li>
 *  <li>第 1 实参为字符串表达式 / StringBuilder 变量 / 字符串拼接 / {@code sql.toString()} 调用</li>
 *  <li>非字符串实参（回调重载等）→ 返回 null</li>
 * </ol>
 *
 * <p>入口兼容 PsiIdentifier（gutter 锚点）与 PsiMethodCallExpression（action 精确匹配）。</p>
 */
public final class JdbcTemplateSqlLocator {

    /** Kotlin object 单例兼容入口。 */
    public static final JdbcTemplateSqlLocator INSTANCE = new JdbcTemplateSqlLocator();

    private JdbcTemplateSqlLocator() {
    }

    /** JDBC 模板 SQL 调用信息（定位器产物）。 */
    public record JdbcTemplateSqlCall(
            /** 方法名（queryForList / update / queryForObject ...）。 */
            String methodName,
            /** 字符串实参表达式（可能 null = 无法定位到字符串）。 */
            PsiExpression sqlExpr,
            /** 拼接聚合后的 SQL 文本（含可能残留的运行时值占位）。 */
            String sqlString,
            /** StringBuilder 变量名（sqlExpr 为 `sql.toString()` 等变量引用时非 null）。 */
            String builderVariable,
            /** true = 完全静态可聚合；false = 含运行时值（降级提示）。 */
            boolean isLiteral,
            /** 参数实参表达式（Map / 对象，未使用，保留扩展）。 */
            PsiExpression parameterExpression,
            /** 源方法调用表达式（供预览服务引用）。 */
            PsiMethodCallExpression callExpression
    ) {
    }

    /**
     * 定位 JdbcTemplate SQL 字符串调用。
     *
     * @param anchor 光标所在 PSI 元素（PsiIdentifier / PsiMethodCallExpression / 任意）
     * @return 定位结果；未命中返回 null
     */
    public static JdbcTemplateSqlCall locate(PsiElement anchor) {
        PsiMethodCallExpression call = resolveCallExpression(anchor);
        if (call == null) {
            return null;
        }
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) {
            return null;
        }
        if (!JdbcTemplateMethodDetector.isStringSqlMethodCandidate(methodName)) {
            return null;
        }
        return locateFromCall(call);
    }

    /**
     * 从方法调用表达式定位 SQL 字符串。
     */
    public static JdbcTemplateSqlCall locateFromCall(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) {
            return null;
        }
        if (!JdbcTemplateMethodDetector.isStringSqlMethodCandidate(methodName)) {
            return null;
        }

        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) {
            return null;
        }

        // 分析第 1 个字符串形态实参：定位 SQL 字符串表达式（T-DaoSql-02——
        // Spring JDBC 模板 SQL 即首参，行为不变；自研框架 (Class, sql, ...) 形态命中第 2 参）
        Pair<PsiExpression, String> resolved = resolveSqlArgument(args);
        if (resolved == null) {
            return null;
        }
        PsiExpression sqlExpr = resolved.getFirst();
        String builderVar = resolved.getSecond();

        // 参数实参：SQL 实参之后的一个（queryForList(sql, paramMap) → paramMap；
        // selectList(Class, sql, params) 形态首参为 Class，参数在 sql 后——保留扩展位）
        int sqlIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == sqlExpr) {
                sqlIndex = i;
                break;
            }
        }
        PsiExpression paramArg = sqlIndex >= 0 && sqlIndex + 1 < args.length ? args[sqlIndex + 1] : null;

        // 聚合为单一 SQL 文本
        SqlStringConcatenator.ConcatenationResult concatResult = SqlStringConcatenator.concat(sqlExpr);
        if (concatResult == null) {
            return null;
        }

        return new JdbcTemplateSqlCall(
                methodName,
                sqlExpr,
                concatResult.sql(),
                builderVar,
                concatResult.complete(),
                paramArg,
                call
        );
    }

    // ------------------------------------------------------------------
    // 调用表达式解析
    // ------------------------------------------------------------------

    /** 从锚点解析出 PsiMethodCallExpression（向上查找最内层包含 anchor 的方法调用）。 */
    private static PsiMethodCallExpression resolveCallExpression(PsiElement anchor) {
        // anchor 自身是方法调用
        if (anchor instanceof PsiMethodCallExpression) {
            return (PsiMethodCallExpression) anchor;
        }
        // anchor 是方法名 PsiIdentifier → 取父链 PsiReferenceExpression → 祖父 PsiMethodCallExpression
        if (anchor instanceof PsiIdentifier) {
            PsiElement parent = anchor.getParent();
            if (parent instanceof PsiReferenceExpression) {
                PsiElement grandParent = parent.getParent();
                if (grandParent instanceof PsiMethodCallExpression) {
                    PsiMethodCallExpression gpc = (PsiMethodCallExpression) grandParent;
                    String refName = gpc.getMethodExpression().getReferenceName();
                    if (anchor.getText().equals(refName)) {
                        return gpc;
                    }
                }
            }
        }
        // anchor 在其他位置 → 向上查找包含它的方法调用
        return PsiTreeUtil.getParentOfType(anchor, PsiMethodCallExpression.class, false);
    }

    // ------------------------------------------------------------------
    // SQL 表达式定位
    // ------------------------------------------------------------------

    /**
     * 从实参列表解析出 SQL 字符串表达式（T-DaoSql-02）：取第一个字符串形态实参。
     *
     * <p>处理形态：</p>
     * <ul>
     *  <li>字面量 / 拼接表达式 → 直接作为 SQL 表达式</li>
     *  <li>{@code sql.toString()} 调用 → qualifier 解析为变量，作为 StringBuilder / 字符串变量</li>
     *  <li>变量引用 → 直接作为 SQL 表达式（concat 内部沿 initializer 追踪）</li>
     *  <li>Class 字面量 / Map / 集合等非字符串形态（自研框架首参）→ 跳过看下一实参</li>
     * </ul>
     *
     * @return Pair(SQL 表达式, StringBuilder 变量名)；无字符串形态实参返回 null
     */
    private static Pair<PsiExpression, String> resolveSqlArgument(PsiExpression[] args) {
        for (PsiExpression arg : args) {
            if (arg instanceof PsiLiteralExpression) {
                if (((PsiLiteralExpression) arg).getValue() instanceof String) {
                    return new Pair<>(arg, null);
                }
            } else if (arg instanceof PsiBinaryExpression || arg instanceof PsiPolyadicExpression) {
                return new Pair<>(arg, null);
            } else if (arg instanceof PsiMethodCallExpression) {
                // `sql.toString()` 等：qualifier 是变量引用
                Pair<PsiExpression, String> resolved = resolveToStringCall((PsiMethodCallExpression) arg);
                if (resolved != null) {
                    return resolved;
                }
            } else if (arg instanceof PsiReferenceExpression) {
                return new Pair<>(arg, null);
            }
            // 其余（Class 字面量 PsiClassObjectAccessExpression / 数组等）→ 跳过
        }
        return null;
    }

    /**
     * 解析 `sql.toString()` / `sql.append(x).toString()` 等调用链：
     * 取 qualifier（最终持有变量），作为 SQL 表达式。
     */
    private static Pair<PsiExpression, String> resolveToStringCall(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) {
            return null;
        }
        if (!"toString".equals(methodName) && !"append".equals(methodName)) {
            return null;
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) {
            return null;
        }
        // 沿链向上：`sql.append(x).toString()` → 最终 qualifier 仍是 `sql`
        while (qualifier instanceof PsiMethodCallExpression) {
            qualifier = ((PsiMethodCallExpression) qualifier).getMethodExpression().getQualifierExpression();
            if (qualifier == null) {
                break;
            }
        }
        if (!(qualifier instanceof PsiReferenceExpression)) {
            return null;
        }
        PsiReferenceExpression ref = (PsiReferenceExpression) qualifier;
        String builderVar = ref.getReferenceName();
        return new Pair<>(ref, builderVar);
    }
}
