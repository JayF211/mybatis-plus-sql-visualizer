package cn.song.mybatisplus.visualizer.sql.extract;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 子查询提取器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * <p>从 SQL 文本计算子查询的最大嵌套深度，支持：
 * <ul>
 *   <li>WHERE 子句中的子查询（IN / EXISTS / 比较运算符后）</li>
 *   <li>FROM 子句中的子查询（派生表）</li>
 *   <li>SELECT 列表中的子查询（标量子查询）</li>
 *   <li>UNION 各分支中的子查询</li>
 * </ul>
 */
public final class SubqueryExtractor {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final SubqueryExtractor INSTANCE = new SubqueryExtractor();

    private static final Set<String> SUBQUERY_INTRODUCERS = Set.of(
            "FROM", "IN", "EXISTS", "SELECT",
            "=", ">", "<", ">=", "<=", "<>", "!=",
            "ANY", "ALL", "SOME"
    );

    private SubqueryExtractor() {
    }

    /**
     * 计算 SQL 中子查询的最大嵌套深度。
     *
     * @param sql SQL 文本
     * @return 最大嵌套深度（0=无子查询）
     */
    public static int maxDepth(@NotNull String sql) {
        if (sql.trim().isEmpty()) {
            return 0;
        }
        return calculateMaxDepth(sql);
    }

    /**
     * 统计 SQL 中子查询的总数（含嵌套）。
     *
     * @param sql SQL 文本
     * @return 子查询总数
     */
    public static int count(@NotNull String sql) {
        if (sql.trim().isEmpty()) {
            return 0;
        }
        return countSubqueries(sql);
    }

    /**
     * 计算最大嵌套深度。
     *
     * <p>策略：基于括号深度 + 子查询引导词检测。
     * 每个在子查询上下文的 {@code (} 开启新嵌套层。
     */
    private static int calculateMaxDepth(@NotNull String sql) {
        List<String> tokens = tokenize(sql);
        List<String> upperTokens = upperCase(tokens);

        int maxDepth = 0;
        int currentDepth = 0;
        int i = 0;

        while (i < upperTokens.size()) {
            String token = upperTokens.get(i);
            if (token.equals("(")) {
                // 检查前面是否有子查询引导词
                String prev = getOrNull(upperTokens, i - 1);
                if (isSubqueryIntroducer(prev)) {
                    currentDepth++;
                    maxDepth = Math.max(maxDepth, currentDepth);
                }
            } else if (token.equals(")")) {
                if (currentDepth > 0) {
                    currentDepth--;
                }
            }
            i++;
        }
        return maxDepth;
    }

    /**
     * 统计子查询总数。
     */
    private static int countSubqueries(@NotNull String sql) {
        List<String> tokens = tokenize(sql);
        List<String> upperTokens = upperCase(tokens);
        int count = 0;
        int i = 0;
        while (i < upperTokens.size()) {
            if (upperTokens.get(i).equals("(")) {
                String prev = getOrNull(upperTokens, i - 1);
                if (isSubqueryIntroducer(prev)) {
                    count++;
                }
            }
            i++;
        }
        return count;
    }

    /**
     * 分词器：将 SQL 文本拆分为 token 列表，正确处理括号。
     *
     * <p>例如：{@code FROM (SELECT id} → {@code ["FROM", "(", "SELECT", "id"]}
     */
    @NotNull
    private static List<String> tokenize(@NotNull String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '(' || c == ')') {
                // 先保存当前累积的 token
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                // 括号单独作为一个 token
                result.add(String.valueOf(c));
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else if (c == '`') {
                // 跳过反引号
                current.append(c);
            } else {
                current.append(c);
            }
            i++;
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 判断前一个 token 是否为子查询引导词。
     */
    private static boolean isSubqueryIntroducer(@Nullable String prev) {
        if (prev == null) {
            return false;
        }
        return SUBQUERY_INTRODUCERS.contains(prev) || prev.endsWith("(");
    }

    @Nullable
    private static String getOrNull(@NotNull List<String> list, int index) {
        return (index >= 0 && index < list.size()) ? list.get(index) : null;
    }

    @NotNull
    private static List<String> upperCase(@NotNull List<String> tokens) {
        List<String> upper = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            upper.add(t.toUpperCase());
        }
        return upper;
    }
}
