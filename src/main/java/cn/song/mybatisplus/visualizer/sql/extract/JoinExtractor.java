package cn.song.mybatisplus.visualizer.sql.extract;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * JOIN 提取器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * <p>从 SQL 文本统计 JOIN 数量并识别 JOIN 类型，支持：
 * <ul>
 *   <li>INNER JOIN / LEFT JOIN / RIGHT JOIN / FULL OUTER JOIN / CROSS JOIN / NATURAL JOIN</li>
 *   <li>简单 JOIN（等同于 INNER JOIN）</li>
 *   <li>子查询内的 JOIN（递归统计）</li>
 * </ul>
 */
public final class JoinExtractor {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final JoinExtractor INSTANCE = new JoinExtractor();

    private JoinExtractor() {
    }

    /**
     * 统计 SQL 中显式 JOIN 的总数。
     *
     * @param sql SQL 文本
     * @return JOIN 数量；无法识别返回 0
     */
    public static int count(@NotNull String sql) {
        if (sql.trim().isEmpty()) {
            return 0;
        }
        List<String> tokens = tokenize(sql);
        List<String> upperTokens = upperCase(tokens);
        int count = 0;
        for (String token : upperTokens) {
            if (token.equals("JOIN")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 返回 SQL 中各 JOIN 的类型列表（按出现顺序）。
     *
     * @param sql SQL 文本
     * @return JOIN 类型列表（"INNER" / "LEFT" / "RIGHT" / "FULL" / "CROSS" / "NATURAL" / "INNER"）
     */
    @NotNull
    public static List<String> joinTypes(@NotNull String sql) {
        if (sql.trim().isEmpty()) {
            return List.of();
        }
        List<String> tokens = tokenize(sql);
        List<String> upperTokens = upperCase(tokens);
        List<String> types = new ArrayList<>();
        int i = 0;
        while (i < upperTokens.size()) {
            String token = upperTokens.get(i);
            if (token.equals("INNER")) {
                if (i + 1 < upperTokens.size() && upperTokens.get(i + 1).equals("JOIN")) {
                    types.add("INNER");
                    i += 2;
                    continue;
                }
            } else if (token.equals("LEFT")) {
                if (i + 1 < upperTokens.size() && upperTokens.get(i + 1).equals("JOIN")) {
                    types.add("LEFT");
                    i += 2;
                    continue;
                }
            } else if (token.equals("RIGHT")) {
                if (i + 1 < upperTokens.size() && upperTokens.get(i + 1).equals("JOIN")) {
                    types.add("RIGHT");
                    i += 2;
                    continue;
                }
            } else if (token.equals("FULL")) {
                if (i + 2 < upperTokens.size()
                        && upperTokens.get(i + 1).equals("OUTER")
                        && upperTokens.get(i + 2).equals("JOIN")) {
                    types.add("FULL");
                    i += 3;
                    continue;
                } else if (i + 1 < upperTokens.size() && upperTokens.get(i + 1).equals("JOIN")) {
                    types.add("FULL");
                    i += 2;
                    continue;
                }
            } else if (token.equals("CROSS")) {
                if (i + 1 < upperTokens.size() && upperTokens.get(i + 1).equals("JOIN")) {
                    types.add("CROSS");
                    i += 2;
                    continue;
                }
            } else if (token.equals("NATURAL")) {
                if (i + 1 < upperTokens.size() && upperTokens.get(i + 1).equals("JOIN")) {
                    types.add("NATURAL");
                    i += 2;
                    continue;
                }
            } else if (token.equals("JOIN")) {
                types.add("INNER");
            }
            i++;
        }
        return types;
    }

    /**
     * 分词器：将 SQL 文本拆分为 token 列表，正确处理括号、逗号。
     */
    @NotNull
    private static List<String> tokenize(@NotNull String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '(' || c == ')' || c == ',') {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                result.add(String.valueOf(c));
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else if (c == '`') {
                // 跳过反引号
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

    @NotNull
    private static List<String> upperCase(@NotNull List<String> tokens) {
        List<String> upper = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            upper.add(t.toUpperCase());
        }
        return upper;
    }
}
