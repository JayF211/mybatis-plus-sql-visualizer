package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import kotlin.Pair;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 轻量 SQL 格式化器（M9 增强，T-M27.4 缩进模型重构）：把可编辑 SQL 规范化为标准可读格式。
 *
 * 基于 {@link SqlLexer} 分词，规则：
 *  - 关键字大写；主子句（SELECT/FROM/WHERE/GROUP/ORDER/HAVING/LIMIT/JOIN/UPDATE/SET/DELETE 等）各占一行
 *  - `LEFT JOIN` 等连写不换行；顶层 `AND`/`OR` 缩进换行
 *  - SELECT/SET 后列清单换行（列缩进 = 本行缩进 + 4）
 *  - 子查询（括号内换行后）内容缩进 = 起始行 + 4、闭括号对齐起始行（EXISTS 子查询标准格式）
 *  - 运算符两侧空格、逗号后空格、`IN (`/`EXISTS (` 等运算符关键字与括号间留空格、函数调用 `COUNT(` 不空格
 *  - 空白（含 XML 动态标签展开残留空行）剔除
 *  - 字符串/数字/标识符原文保留（不做大小写改写）
 *
 * 覆盖本插件产出的 SELECT/UPDATE/DELETE + MPJ 联表形态，不做通用 SQL 方言格式化。
 */
public final class SqlFormatter {

    public static final SqlFormatter INSTANCE = new SqlFormatter();

    /** 主子句关键字（各占一行）。 */
    private static final Set<String> NEW_LINE_KEYWORDS = Set.copyOf(Arrays.asList(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT",
            "UPDATE", "SET", "DELETE", "INSERT", "INTO", "VALUES", "UNION", "JOIN",
            "LEFT", "RIGHT", "INNER", "FULL", "CROSS"
    ));

    /** 紧跟 JOIN 的修饰关键字（`LEFT JOIN` 保持同行）。 */
    private static final Set<String> JOIN_QUALIFIERS = Set.copyOf(Arrays.asList("LEFT", "RIGHT", "INNER", "FULL", "CROSS"));

    /** 函数名（括号前不空格：`COUNT(`）。T-M29.3：补 CONCAT（MP LIKE 常用）/TRIM_SCALE（项目自定义）及常见函数。 */
    private static final Set<String> FUNCTION_NAMES = Set.copyOf(Arrays.asList(
            "COUNT", "SUM", "MAX", "MIN", "AVG", "COALESCE", "CAST", "CONVERT",
            "CONCAT", "TRIM_SCALE", "NULLIF", "IFNULL", "LENGTH", "SUBSTRING", "SUBSTR", "REPLACE",
            "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM", "ROUND", "ABS", "FLOOR", "CEIL", "CEILING",
            "NOW", "CURDATE", "DATE_FORMAT", "STR_TO_DATE", "GROUP_CONCAT", "IF", "JSON_EXTRACT",
            "DATEDIFF", "DATEADD"
    ));

    /** 格式化 SQL 文本。 */
    public static String format(String sql) {
        List<Pair<IElementType, String>> tokens = lex(sql);
        if (tokens.isEmpty()) {
            return sql.trim();
        }
        StringBuilder sb = new StringBuilder(sql.length() + 64);
        FmtState st = new FmtState(sb);
        for (Pair<IElementType, String> entry : tokens) {
            IElementType type = entry.getFirst();
            String text = entry.getSecond();
            String upper = text.toUpperCase();
            if (type == SqlTokenTypes.KEYWORD && upper.equals("JOIN")) {
                boolean qualifier = JOIN_QUALIFIERS.contains(st.prevText.toUpperCase());
                if (qualifier) {
                    st.flushSpace();
                } else if (!st.atLineStart) {
                    st.newline(st.subIndent());
                }
                st.currentClause = "JOIN";
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.KEYWORD && NEW_LINE_KEYWORDS.contains(upper)) {
                if (!st.atLineStart) {
                    st.newline(st.subIndent());
                }
                st.pendingSpace = false;
                st.currentClause = upper;
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
                // SELECT/SET 后列清单换行（列缩进 = 本行缩进 + 4）
                if (upper.equals("SELECT") || upper.equals("SET")) {
                    st.newline(st.lineIndent + 4);
                }
            } else if (type == SqlTokenTypes.KEYWORD && (upper.equals("AND") || upper.equals("OR"))) {
                if (st.depth == 0) {
                    if (!st.atLineStart) {
                        st.newline(4);
                    }
                } else if (st.parenNewlined) {
                    if (!st.atLineStart) {
                        st.newline(st.parenIndents.peekLast() + 8);
                    }
                } else {
                    st.flushSpace();
                }
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.KEYWORD) {
                st.flushSpace();
                sb.append(upper);
                st.pendingSpace = true;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.PAREN && text.equals("(")) {
                if (!FUNCTION_NAMES.contains(st.prevText.toUpperCase())) {
                    st.flushSpace();
                }
                sb.append('(');
                st.depth++;
                st.parenIndents.addLast(st.lineIndent);
                st.parenNewlined = false;
                st.pendingSpace = false;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.PAREN && text.equals(")")) {
                if (st.parenNewlined && st.depth > 0) {
                    st.newline(st.parenIndents.peekLast());
                }
                sb.append(')');
                st.depth--;
                if (!st.parenIndents.isEmpty()) {
                    st.parenIndents.removeLast();
                }
                st.parenNewlined = false;
                st.pendingSpace = true;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(",")) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                sb.append(',');
                if (st.depth == 0 && (st.currentClause.equals("SELECT") || st.currentClause.equals("SET")) && !st.atLineStart) {
                    st.newline(4);
                } else {
                    st.pendingSpace = true;
                    st.atLineStart = false;
                }
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(".")) {
                sb.append('.');
                st.pendingSpace = false;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.OPERATOR) {
                st.flushSpace();
                sb.append(text);
                st.pendingSpace = true;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.COMMENT) {
                if (!st.atLineStart) {
                    sb.append('\n');
                }
                sb.append(text);
                sb.append('\n');
                st.atLineStart = true;
                st.pendingSpace = false;
            } else {
                st.flushSpace();
                sb.append(text);
                st.pendingSpace = true;
                st.atLineStart = false;
            }
            st.prevText = text;
        }
        return sb.toString().trim();
    }

    /**
     * 关键字换行格式化（M-KeywordWrap）：只在主子句关键字处换行，子句内容同行。
     *
     * 与 {@link #format(String)}（全展开）互补——前者用于阅读细节，后者用于快速总览。
     */
    public static String formatClauseLine(String sql) {
        List<Pair<IElementType, String>> tokens = lex(sql);
        if (tokens.isEmpty()) {
            return sql.trim();
        }
        StringBuilder sb = new StringBuilder(sql.length() + 64);
        FmtClauseState st = new FmtClauseState(sb);
        for (Pair<IElementType, String> entry : tokens) {
            IElementType type = entry.getFirst();
            String text = entry.getSecond();
            String upper = text.toUpperCase();
            if (type == SqlTokenTypes.KEYWORD && upper.equals("JOIN")) {
                boolean qualifier = JOIN_QUALIFIERS.contains(st.prevText.toUpperCase());
                if (qualifier) {
                    st.flushSpace();
                } else if (st.depth == 0 && !st.atLineStart) {
                    st.newline();
                }
                st.currentClause = "JOIN";
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.KEYWORD && NEW_LINE_KEYWORDS.contains(upper)) {
                if (st.depth == 0) {
                    if (!st.atLineStart) {
                        st.newline();
                    }
                    st.pendingSpace = false;
                } else {
                    st.flushSpace();
                }
                st.currentClause = upper;
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
                st.pendingComma = false;
            } else if (type == SqlTokenTypes.KEYWORD && (upper.equals("AND") || upper.equals("OR"))) {
                st.pendingSpace = false;
                if (!st.atLineStart) {
                    sb.append(' ');
                    sb.append(' ');
                }
                sb.append(upper);
                st.atLineStart = false;
                st.pendingSpace = true;
                st.pendingComma = false;
            } else if (type == SqlTokenTypes.KEYWORD) {
                st.flushSpace();
                sb.append(upper);
                st.pendingSpace = true;
                st.atLineStart = false;
                st.pendingComma = false;
            } else if (type == SqlTokenTypes.PAREN && text.equals("(")) {
                if (!FUNCTION_NAMES.contains(st.prevText.toUpperCase())) {
                    st.flushSpace();
                }
                sb.append('(');
                st.depth++;
                st.pendingSpace = false;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.PAREN && text.equals(")")) {
                st.pendingSpace = false;
                sb.append(')');
                st.depth--;
                st.pendingSpace = true;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(",")) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                sb.append(',');
                // F-CASE：始终设置 pendingSpace=true，后续关键字通过 flushSpace() 加空格；
                // 标识符通过 pendingComma 标记跳过空格（紧凑列清单）
                st.pendingComma = st.currentClause.equals("SELECT") || st.currentClause.equals("SET") || st.depth > 0;
                st.atLineStart = false;
                st.pendingSpace = true;
            } else if (type == SqlTokenTypes.OPERATOR && text.equals(".")) {
                sb.append('.');
                st.pendingSpace = false;
                st.pendingComma = false;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.OPERATOR) {
                st.flushSpace();
                sb.append(text);
                st.pendingSpace = true;
                st.pendingComma = false;
                st.atLineStart = false;
            } else if (type == SqlTokenTypes.COMMENT) {
                if (!st.atLineStart) {
                    sb.append('\n');
                }
                sb.append(text);
                sb.append('\n');
                st.atLineStart = true;
                st.pendingSpace = false;
                st.pendingComma = false;
            } else {
                if (st.pendingComma) {
                    st.pendingComma = false;
                } else {
                    st.flushSpace();
                }
                sb.append(text);
                st.pendingSpace = true;
                st.atLineStart = false;
            }
            st.prevText = text;
        }
        return sb.toString().trim();
    }

    /** 分词（剔除空白——含 XML 动态标签展开残留空行）。 */
    private static List<Pair<IElementType, String>> lex(String sql) {
        SqlLexer lexer = new SqlLexer();
        lexer.start(sql, 0, sql.length(), 0);
        List<Pair<IElementType, String>> tokens = new java.util.ArrayList<>();
        while (true) {
            IElementType type = lexer.getTokenType();
            if (type == null) {
                break;
            }
            if (type != TokenType.WHITE_SPACE) {
                tokens.add(new Pair<>(type, lexer.getTokenText()));
            }
            lexer.advance();
        }
        return tokens;
    }

    /** {@link #format} 的局部可变状态（等价 Kotlin 局部函数 + 闭包捕获）。 */
    private static final class FmtState {
        final StringBuilder sb;
        int depth = 0;
        boolean pendingSpace = false;
        boolean atLineStart = true;
        String prevText = "";
        int lineIndent = 0;
        final ArrayDeque<Integer> parenIndents = new ArrayDeque<>();
        boolean parenNewlined = false;
        String currentClause = "";

        FmtState(StringBuilder sb) {
            this.sb = sb;
        }

        void flushSpace() {
            if (pendingSpace && !atLineStart && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            pendingSpace = false;
        }

        /** 换行 + 缩进（更新当前行缩进；括号内标记已换行）。 */
        void newline(int indent) {
            sb.append('\n');
            for (int i = 0; i < indent; i++) {
                sb.append(' ');
            }
            lineIndent = indent;
            atLineStart = true;
            pendingSpace = false;
            if (depth > 0) {
                parenNewlined = true;
            }
        }

        /** 子查询内新行缩进（起始行 + 4；顶层 0）。 */
        int subIndent() {
            return depth > 0 ? parenIndents.peekLast() + 4 : 0;
        }
    }

    /** {@link #formatClauseLine} 的局部可变状态。 */
    private static final class FmtClauseState {
        final StringBuilder sb;
        boolean pendingSpace = false;
        boolean atLineStart = true;
        String prevText = "";
        String currentClause = "";
        int depth = 0;
        boolean pendingComma = false;

        FmtClauseState(StringBuilder sb) {
            this.sb = sb;
        }

        void flushSpace() {
            if (pendingSpace && !atLineStart && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            pendingSpace = false;
        }

        /** 换行顶格（仅顶层触发；括号内不换行）。 */
        void newline() {
            sb.append('\n');
            atLineStart = true;
            pendingSpace = false;
        }
    }

    private SqlFormatter() {
    }
}
