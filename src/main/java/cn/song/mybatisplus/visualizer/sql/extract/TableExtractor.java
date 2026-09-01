package cn.song.mybatisplus.visualizer.sql.extract;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 表名提取器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * <p>从 SQL 文本提取所有表名（去重），支持：
 * <ul>
 *   <li>FROM / JOIN 子句中的表名</li>
 *   <li>子查询内的表名（递归提取）</li>
 *   <li>UNION 各分支的表名</li>
 *   <li>UPDATE / DELETE / INSERT 的目标表</li>
 *   <li>CTE（WITH 子句）内的表名</li>
 *   <li>反引号包裹的表名（去反引号）</li>
 * </ul>
 */
public final class TableExtractor {

    /** 不应当作表名的 SQL 关键字 */
    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "ON", "AS", "BY",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL",
            "SET", "VALUES", "INTO", "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET",
            "UNION", "ALL", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END",
            "EXISTS", "BETWEEN", "LIKE", "IS", "NULL", "TRUE", "FALSE", "WITH",
            "INSERT", "UPDATE", "DELETE", "USING", "FETCH", "FIRST", "NEXT",
            "ROWS", "ONLY", "OVER", "PARTITION", "RANGE", "PRECEDING", "FOLLOWING",
            "CURSOR", "FOR", "IF", "DUAL"
    );

    /** 引入表名的关键字 */
    private static final Set<String> TABLE_INTRODUCERS = Set.of("FROM", "JOIN", "INTO", "UPDATE");

    private TableExtractor() {
    }

    /**
     * 从 SQL 文本提取所有表名（去重，保留首次出现顺序）。
     *
     * <p>遍历所有 token，当遇到表名引入关键字（FROM/JOIN/INTO/UPDATE）时，
     * 查找其后的表名。同时处理逗号分隔的多表。
     *
     * @param sql SQL 文本
     * @return 表名列表（去重，小写）；无法识别返回空列表
     */
    @NotNull
    public static List<String> extract(@NotNull String sql) {
        if (sql.trim().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        List<String> tokens = tokenize(sql);
        List<String> upperTokens = upperCase(tokens);

        int i = 0;
        while (i < upperTokens.size()) {
            String token = upperTokens.get(i);
            if (TABLE_INTRODUCERS.contains(token)) {
                // 查找紧跟其后的表名，并处理逗号分隔的多表
                int j = i + 1;
                while (true) {
                    int tableIdx = findImmediateTable(upperTokens, j);
                    if (tableIdx < 0) {
                        break;
                    }
                    String tableName = tokens.get(tableIdx).toLowerCase();
                    if (isValidTableName(tableName)) {
                        tables.add(tableName);
                    }
                    j = tableIdx + 1;
                    // 检查是否有逗号分隔的下一个表
                    if (j < upperTokens.size() && upperTokens.get(j).equals(",")) {
                        j++; // 跳过逗号，继续查找
                    } else {
                        break; // 没有逗号，结束当前 FROM/JOIN 的表名扫描
                    }
                }
                i = j - 1; // 外层循环会 i++
            }
            i++;
        }
        return new ArrayList<>(tables);
    }

    /**
     * 查找指定位置后的第一个有效表名。
     *
     * <p>跳过关键字，遇到 {@code (} 或 {@code ,} 返回 -1（表示无直接表名）。
     */
    private static int findImmediateTable(@NotNull List<String> upperTokens, int startIdx) {
        int idx = startIdx;
        while (idx < upperTokens.size()) {
            String token = upperTokens.get(idx);
            if (token.equals("(")) {
                return -1; // 子查询，无直接表名
            } else if (token.equals(",")) {
                return -1; // 逗号，无直接表名
            } else if (KEYWORDS.contains(token)) {
                idx++; // 关键字，继续查找
            } else {
                return idx; // 找到表名
            }
        }
        return -1;
    }

    /**
     * 判断是否为合法表名（非纯数字、非空、长度合理）。
     */
    private static boolean isValidTableName(@NotNull String name) {
        if (name.trim().isEmpty()) {
            return false;
        }
        if (allDigits(name)) {
            return false;
        }
        if (name.length() > 128) {
            return false;
        }
        char first = name.charAt(0);
        return Character.isLetter(first) || first == '_';
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

    private static boolean allDigits(@NotNull String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
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
