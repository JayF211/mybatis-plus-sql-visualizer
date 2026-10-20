package cn.song.mybatisplus.visualizer.share;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sharing is a lossy diagnostic projection, never an executable SQL rewrite or a secrecy guarantee.
 *
 * <p>master 的 {@code object ShareContentProjection} 的 Java 承载（全 static 形态）。</p>
 */
public final class ShareContentProjection {

    private ShareContentProjection() {
    }

    public static final int MAX_INPUT_CHARS = 262_144;
    public static final int MAX_OUTPUT_BYTES = 16_384;

    public record SqlProjection(@Nullable String sql, int maskedValues, int maskedIdentifiers, boolean omitted) {
    }

    /** Unknown quoting, malformed tokens and over-budget input omit the entire SQL section. */
    public static SqlProjection sql(String input) {
        return sql(input, false);
    }

    public static SqlProjection sql(String input, boolean includeIdentifiers) {
        if (input.length() > MAX_INPUT_CHARS) {
            return omitted();
        }
        StringBuilder out = new StringBuilder();
        Map<String, String> identifiers = new LinkedHashMap<>();
        int[] values = {0};
        int i = 0;
        java.util.function.Function<String, String> identifier = raw -> {
            if (includeIdentifiers) {
                return raw;
            }
            String cached = identifiers.get(raw);
            if (cached != null) {
                return cached;
            }
            String next = "identifier_" + (identifiers.size() + 1);
            identifiers.put(raw, next);
            return next;
        };
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                i++;
            } else if (input.startsWith("--", i)) {
                int end = input.indexOf('\n', i + 2);
                i = end < 0 ? input.length() : end + 1;
                out.append(' ');
            } else if (input.startsWith("/*", i)) {
                int end = input.indexOf("*/", i + 2);
                if (end < 0) {
                    return omitted();
                }
                int nested = input.indexOf("/*", i + 2);
                if (nested >= 0 && nested < end) {
                    return omitted();
                }
                i = end + 2;
                out.append(' ');
            } else if (c == '#') {
                return omitted();
            } else if (c == '\'') {
                boolean closed = false;
                i++;
                while (i < input.length()) {
                    if (input.charAt(i) == '\\') {
                        return omitted();
                    }
                    if (input.charAt(i) == '\'' && i + 1 < input.length() && input.charAt(i + 1) == '\'') {
                        i += 2;
                    } else if (input.charAt(i) == '\'') {
                        i++;
                        closed = true;
                        break;
                    } else {
                        i++;
                    }
                }
                if (!closed) {
                    return omitted();
                }
                out.append('?');
                values[0]++;
            } else if (c == '"' || c == '`' || c == '[') {
                int start = i;
                char close = c == '[' ? ']' : c;
                boolean closed = false;
                i++;
                while (i < input.length()) {
                    char cc = input.charAt(i);
                    if (cc == '\\' || cc == '\n' || cc == '\r') {
                        return omitted();
                    }
                    if (cc == close && i + 1 < input.length() && input.charAt(i + 1) == close) {
                        i += 2;
                    } else if (cc == close) {
                        i++;
                        closed = true;
                        break;
                    } else {
                        i++;
                    }
                }
                if (!closed) {
                    return omitted();
                }
                // Double quotes can denote values in some modes; quoted content is never copied.
                String named = identifier.apply("quoted:" + input.substring(start, i));
                out.append(includeIdentifiers ? "identifier" : named);
            } else if (c == '$' || c == '@') {
                return omitted();
            } else if (c == ':' && i + 1 < input.length() && input.charAt(i + 1) == ':') {
                out.append("::");
                i += 2;
            } else if (c == ':') {
                i++;
                int start = i;
                while (i < input.length() && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_')) {
                    i++;
                }
                if (i == start) {
                    return omitted();
                }
                out.append('?');
                values[0]++;
            } else if (Character.isDigit(c)) {
                i++;
                while (i < input.length()) {
                    char cc = input.charAt(i);
                    if (!(Character.isLetterOrDigit(cc) || "+.-_".indexOf(cc) >= 0)) {
                        break;
                    }
                    if ((cc == '+' || cc == '-') && (i == 0 || ("eE".indexOf(input.charAt(i - 1)) < 0))) {
                        break;
                    }
                    i++;
                }
                out.append('?');
                values[0]++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < input.length() && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_')) {
                    i++;
                }
                String word = input.substring(start, i);
                if (i < input.length() && input.charAt(i) == '\'') {
                    return omitted();
                }
                String upper = word.toUpperCase(Locale.ROOT);
                if (VALUE_KEYWORDS.contains(upper)) {
                    out.append('?');
                    values[0]++;
                } else if (STRUCTURAL_KEYWORDS.contains(upper)) {
                    // 结构词保留：FROM/WHERE/JOIN 等在各主流方言都是保留字，未加引号不能作列名。
                    out.append(upper);
                } else {
                    // Without a dialect grammar, keyword-looking names can still be identifiers.
                    out.append(identifier.apply(word));
                }
            } else if ("(),.;?=<>!+-*/%|&^~".indexOf(c) >= 0) {
                out.append(c);
                i++;
            } else {
                return omitted();
            }
            if (out.length() > MAX_OUTPUT_BYTES) {
                return omitted();
            }
        }
        String text = out.toString().trim();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            return omitted();
        }
        return new SqlProjection(text, values[0], identifiers.size(), false);
    }

    /** Plan/table identifiers are deliberately omitted by default; user inclusion is a separate choice. */
    public static @Nullable String identifier(@Nullable String value) {
        return identifier(value, false);
    }

    public static @Nullable String identifier(@Nullable String value, boolean include) {
        if (value == null || !include || value.length() > 128 || !IDENTIFIER.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    private static SqlProjection omitted() {
        return new SqlProjection(null, 0, 0, true);
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_.$]{0,127}");
    private static final Set<String> VALUE_KEYWORDS = Set.of("NULL", "TRUE", "FALSE");

    /**
     * 结构关键词白名单（T-AiContextLegibility A）：只收 MySQL/PostgreSQL/Oracle 等主流方言的
     * 保留字——未加引号时不能作为列名，因此原样输出不会把真实标识符当关键词泄漏。
     */
    private static final Set<String> STRUCTURAL_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "WITH", "EXPLAIN",
            "FROM", "WHERE", "AND", "OR", "NOT", "AS", "ON", "IN", "IS",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS",
            "UNION", "ALL", "DISTINCT", "GROUP", "BY", "ORDER", "HAVING", "ASC", "DESC",
            "LIMIT", "OFFSET", "CASE", "WHEN", "THEN", "ELSE", "END",
            "EXISTS", "BETWEEN", "LIKE", "INTO", "VALUES", "SET");
}
