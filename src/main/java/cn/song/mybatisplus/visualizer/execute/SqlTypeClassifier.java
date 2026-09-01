package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL 语句类型分类器（M36）：区分查询类语句（免确认）与非查询类语句（需二次确认）。
 *
 * 分类规则（按首条有效语句首关键字判定）：
 *  - 查询类（免确认）：SELECT / SHOW / EXPLAIN / DESCRIBE / WITH（CTE）
 *  - DML（需确认）：INSERT / UPDATE / DELETE / MERGE
 *  - DDL（需确认）：CREATE / ALTER / DROP / TRUNCATE
 *  - 多条语句混合 → 按最高风险级别处理（需确认）
 *  - 注释（-- 单行 / * / 块注释）自动跳过
 */
public final class SqlTypeClassifier {

    /** SQL 语句类型枚举（风险升序）。 */
    public enum SqlType { QUERY, DML, DDL, UNKNOWN }

    /** 查询类关键字（免确认）。 */
    private static final Set<String> QUERY_KEYWORDS = Set.of("SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "WITH");

    /** DML 关键字（需确认）。 */
    private static final Set<String> DML_KEYWORDS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");

    /** DDL 关键字（需确认）。 */
    private static final Set<String> DDL_KEYWORDS = Set.of("CREATE", "ALTER", "DROP", "TRUNCATE");

    public static final SqlTypeClassifier INSTANCE = new SqlTypeClassifier();

    private SqlTypeClassifier() {
    }

    /**
     * 判断 SQL 是否为查询类语句（免确认直接执行）。
     *
     * @param sql SQL 文本（可能含多条语句、注释）
     * @return true=查询类（SELECT/SHOW/EXPLAIN/DESCRIBE/WITH）；false=非查询或无法判定
     */
    public static boolean isQuery(@NotNull String sql) {
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) return false;
        // 多条语句：全部为查询类才免确认；任一条非查询 → 需确认
        return statements.stream().allMatch(it -> classify(it) == SqlType.QUERY);
    }

    /**
     * 提取 SQL 首条有效语句的首关键字（大写）。
     *
     * @param sql SQL 文本
     * @return 首关键字大写（如 "SELECT"、"INSERT"），无有效语句时返回 null
     */
    @Nullable
    public static String getFirstKeyword(@NotNull String sql) {
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) return null;
        return extractKeyword(statements.get(0));
    }

    /**
     * 对单条 SQL 语句分类。
     *
     * @param statement 单条 SQL 语句（已去除注释）
     * @return 分类结果
     */
    @NotNull
    public static SqlType classify(@NotNull String statement) {
        String keyword = extractKeyword(statement);
        if (keyword == null) return SqlType.UNKNOWN;
        if (QUERY_KEYWORDS.contains(keyword)) return SqlType.QUERY;
        if (DML_KEYWORDS.contains(keyword)) return SqlType.DML;
        if (DDL_KEYWORDS.contains(keyword)) return SqlType.DDL;
        return SqlType.UNKNOWN;
    }

    /**
     * 按分号分割 SQL 为多条语句（忽略空语句），并逐条去注释。
     *
     * @param sql SQL 文本
     * @return 去注释后的非空语句列表
     */
    public static List<String> splitStatements(@NotNull String sql) {
        String[] parts = sql.split(";");
        return Arrays.stream(parts)
                .map(SqlTypeClassifier::stripComments)
                .filter(it -> !it.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * 去除 SQL 语句中的注释（-- 单行注释 + /* *\/ 块注释）。
     *
     * @param sql 原始 SQL 片段
     * @return 去注释后的文本
     */
    public static String stripComments(@NotNull String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            // -- 单行注释：跳过至行尾
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                // 跳至行尾或文本结束
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            }
            // /* */ 块注释
            else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i < sql.length()
                        && !(sql.charAt(i) == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2; // 跳过 */
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 提取语句的首关键字（大写）。跳过前导空白与左括号。
     *
     * @param statement 单条 SQL 语句（已去注释）
     * @return 首关键字大写，无有效关键字时返回 null
     */
    @Nullable
    private static String extractKeyword(@NotNull String statement) {
        String trimmed = statement.trim();
        if (trimmed.isEmpty()) return null;
        // 提取连续字母作为关键字
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetter(ch)) {
                letters.append(ch);
            } else {
                break;
            }
        }
        String result = letters.toString();
        return result.isEmpty() ? null : result.toUpperCase();
    }
}
