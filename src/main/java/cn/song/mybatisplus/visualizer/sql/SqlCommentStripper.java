package cn.song.mybatisplus.visualizer.sql;

/**
 * SQL 注释剥离（复制纯 SQL —— 忽略注释）。
 *
 * <p>纯文本扫描（不依赖 PSI/IDE，任意环境可单测）：
 * <ul>
 *  <li>{@code --} 行注释：从 {@code --} 到行尾全部标记删除</li>
 *  <li>{@code /* *}{@code /} 块注释：单行/多行整段标记删除</li>
 *  <li>字符串/字符字面量内的标记不视为注释（转义引号正确处理）</li>
 *  <li>代码空行原样保留</li>
 * </ul>
 */
public final class SqlCommentStripper {

    private static final char MARK = '\u0000';

    private SqlCommentStripper() {
    }

    /** 剥离全部 SQL 注释，返回纯 SQL 文本。 */
    public static String strip(String sql) {
        char[] chars = sql.toCharArray();
        int i = 0;
        while (i < chars.length) {
            if (chars[i] == '\'') {
                i = skipQuoted(chars, i);
            } else if (chars[i] == '-' && i + 1 < chars.length && chars[i + 1] == '-') {
                i = markLineComment(chars, i);
            } else if (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '*') {
                i = markBlockComment(chars, i);
            } else {
                i++;
            }
        }
        return filterLines(chars);
    }

    private static int skipQuoted(char[] chars, int start) {
        char quote = chars[start];
        int i = start + 1;
        while (i < chars.length) {
            if (chars[i] == '\\') {
                i += 2;
            } else if (chars[i] == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return chars.length;
    }

    private static int markLineComment(char[] chars, int start) {
        int i = start;
        while (i < chars.length && chars[i] != '\n') {
            chars[i] = MARK;
            i++;
        }
        return i;
    }

    private static int markBlockComment(char[] chars, int start) {
        int i = start;
        chars[i] = MARK;
        chars[i + 1] = MARK;
        i += 2;
        while (i < chars.length) {
            if (chars[i] == '*' && i + 1 < chars.length && chars[i + 1] == '/') {
                chars[i] = MARK;
                chars[i + 1] = MARK;
                return i + 2;
            }
            if (chars[i] != '\n') chars[i] = MARK;
            i++;
        }
        return chars.length;
    }

    private static String filterLines(char[] chars) {
        StringBuilder sb = new StringBuilder(chars.length);
        int i = 0;
        while (i < chars.length) {
            int end = i;
            while (end < chars.length && chars[end] != '\n') end++;
            boolean hasCode = false;
            boolean marked = false;
            for (int j = i; j < end; j++) {
                if (chars[j] == MARK) {
                    marked = true;
                } else if (!isKotlinWhitespace(chars[j])) {
                    hasCode = true;
                }
            }
            if (hasCode || !marked) {
                StringBuilder line = new StringBuilder();
                for (int j = i; j < end; j++) {
                    if (chars[j] != MARK) line.append(chars[j]);
                }
                while (line.length() > 0 && isKotlinWhitespace(line.charAt(line.length() - 1))) {
                    line.deleteCharAt(line.length() - 1);
                }
                sb.append(line);
                if (end < chars.length) sb.append('\n');
            }
            i = end < chars.length ? end + 1 : end;
        }
        return sb.toString();
    }

    /**
     * 对齐 Kotlin {@code Char.isWhitespace()}：
     * {@code Character.isWhitespace(c) || Character.isSpaceChar(c)}。
     */
    private static boolean isKotlinWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
