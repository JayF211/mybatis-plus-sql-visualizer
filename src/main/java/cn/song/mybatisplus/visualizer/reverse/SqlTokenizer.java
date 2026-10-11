package cn.song.mybatisplus.visualizer.reverse;

import kotlin.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SQL 词法单元（SQL→Java 反向转换的词法层，P0）。
 *
 * 覆盖 SQL→Wrapper 逆向所需的词形：标识符（含反引号包裹与 `表.列` 点分）、字符串字面量、
 * 数字、单字符符号、注释（跳过不产出）。括号由 [TokenType.PAREN_LEFT]/[PAREN_RIGHT] 表达。
 */
public final class SqlTokenizer {

    /** 词法单元类型。 */
    public enum TokenType {
        /** 标识符 / 关键字 / 函数名（含反引号包裹与点分形态的原文）。 */
        WORD,

        /** 字符串字面量（单引号包裹原文，含引号）。 */
        STRING,

        /** 数字字面量（原文字面量）。 */
        NUMBER,

        /** 单字符符号（= ! &lt; &gt; ( ) , * . ; + - / % ?）。 */
        SYMBOL
    }

    /** 词法单元。 */
    public record Token(TokenType type, String text, int offset) {
    }

    private static final Set<Character> SYMBOL_CHARS = Set.of(
            '=', '!', '<', '>', '(', ')', ',', '*', '.', ';', '+', '-', '/', '%', '?');

    /** 分词。 @throws SqlReverseException 词法错误（未闭合字符串/反引号、非法字符） */
    public List<Token> tokenize(String sql) throws SqlReverseException {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
            } else if (c == '\'') {
                Pair<Integer, String> r = readQuoted(sql, i, c);
                if (r.getFirst() < 0) {
                    throw new SqlReverseException(i, SqlReverseParser.KEY_PARSE, "未闭合的字符串字面量");
                }
                tokens.add(new Token(TokenType.STRING, r.getSecond(), i));
                i = r.getFirst();
            } else if (c == '`' || c == '"') {
                // 反引号/双引号包裹的标识符（T-SqlToJavaPromote G10：双引号标识符方言宽容，标准 SQL/SQL Server）
                int end = sql.indexOf(c, i + 1);
                if (end < 0) {
                    throw new SqlReverseException(i, SqlReverseParser.KEY_PARSE, "未闭合的 " + c + " 标识符");
                }
                tokens.add(new Token(TokenType.WORD, sql.substring(i, end + 1), i));
                i = end + 1;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1)))) {
                int j = i + 1;
                // 十六进制字面量（0x1F，T-SqlToJavaPromote G10）：0 后跟 x 时按十六进制扫描
                if (c == '0' && j < sql.length() && (sql.charAt(j) == 'x' || sql.charAt(j) == 'X')) {
                    j++;
                    while (j < sql.length() && (Character.isDigit(sql.charAt(j))
                            || (sql.charAt(j) >= 'a' && sql.charAt(j) <= 'f')
                            || (sql.charAt(j) >= 'A' && sql.charAt(j) <= 'F'))) j++;
                } else {
                    while (j < sql.length() && (Character.isDigit(sql.charAt(j)) || sql.charAt(j) == '.')) j++;
                }
                tokens.add(new Token(TokenType.NUMBER, sql.substring(i, j), i));
                i = j;
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                int j = i + 1;
                // 点号仅在「后一字符不是反引号」时并入 WORD——`t.`signal`` 须拆成 `t`、`.`、`` `signal` ``
                while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_'
                        || (sql.charAt(j) == '.' && j + 1 < sql.length() && sql.charAt(j + 1) != '`'))) j++;
                tokens.add(new Token(TokenType.WORD, sql.substring(i, j), i));
                i = j;
            } else if (SYMBOL_CHARS.contains(c)) {
                tokens.add(new Token(TokenType.SYMBOL, String.valueOf(c), i));
                i++;
            } else {
                throw new SqlReverseException(i, SqlReverseParser.KEY_PARSE, "无法识别的字符 '" + c + "'");
            }
        }
        return tokens;
    }

    /** 行注释：`--` 到行尾。 */
    private static int skipLineComment(String sql, int start) {
        int j = start + 2;
        while (j < sql.length() && sql.charAt(j) != '\n') j++;
        return j;
    }

    /** 块注释：斜杠星号到星号斜杠，未闭合时消费到末尾（容忍）。 */
    private static int skipBlockComment(String sql, int start) {
        int end = sql.indexOf("*/", start + 2);
        return end < 0 ? sql.length() : end + 2;
    }

    /**
     * 读取配对引号包裹内容（SQL 双写转义 `''` + 反斜杠转义 `\'`，T-SqlToJavaPromote G10）；
     * 返回 (结束索引, 含引号原文)；未闭合返回 (-1, "")。
     */
    private static Pair<Integer, String> readQuoted(String sql, int start, char quote) {
        int j = start + 1;
        while (j < sql.length()) {
            if (sql.charAt(j) == '\\' && j + 1 < sql.length()) {
                // 反斜杠转义（MySQL `\'` / `\\`）：跳过下一个字符
                j += 2;
                continue;
            }
            if (sql.charAt(j) == quote) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return new Pair<>(j + 1, sql.substring(start, j + 1));
            }
            j++;
        }
        return new Pair<>(-1, "");
    }
}
