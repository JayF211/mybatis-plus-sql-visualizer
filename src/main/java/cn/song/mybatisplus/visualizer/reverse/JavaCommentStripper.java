package cn.song.mybatisplus.visualizer.reverse;

/**
 * Java 注释剥离（T-M45：复制「纯 Java 代码」——忽略反向生成的说明注释/日志）。
 *
 * 纯文本扫描（不依赖 PSI/IDE，任意环境可单测）：行注释与块注释逐行剥离，字符串/字符字面量内的
 * 斜杠标记不视为注释；代码空行原样保留——只删「注释行」，不压缩代码本身。
 */
public final class JavaCommentStripper {

    /** 单例（Kotlin object 转换）。 */
    public static final JavaCommentStripper INSTANCE = new JavaCommentStripper();

    /** 注释占位标记（NUL 不出现在合法 Java 源码中，可安全用作哨兵）。 */
    private static final char MARK = '\u0000';

    private JavaCommentStripper() {
    }

    /** 剥离全部注释，返回纯代码文本。 */
    public static String strip(String code) {
        char[] chars = code.toCharArray();
        int i = 0;
        while (i < chars.length) {
            if (chars[i] == '"' || chars[i] == '\'') i = skipQuoted(chars, i);
            else if (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '/') i = markLineComment(chars, i);
            else if (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '*') i = markBlockComment(chars, i);
            else i++;
        }
        return filterLines(chars);
    }

    /** 跳过字符串/字符字面量。 */
    private static int skipQuoted(char[] chars, int start) {
        char quote = chars[start];
        int i = start + 1;
        while (i < chars.length) {
            if (chars[i] == '\\') i += 2;
            else if (chars[i] == quote) return i + 1;
            else i++;
        }
        return chars.length;
    }

    /** 行注释：从 `//` 到行尾全部标记（换行符保留）。 */
    private static int markLineComment(char[] chars, int start) {
        int i = start;
        while (i < chars.length && chars[i] != '\n') {
            chars[i] = MARK;
            i++;
        }
        return i;
    }

    /** 块注释（斜杠星号成对）：整段标记（换行符保留）。 */
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

    /**
     * 逐行过滤：含代码的行 → 去标记 + 去行尾空白；纯标记行（注释行）→ 整行丢弃；
     * 原始空行（无标记）→ 原样保留（代码分隔结构不变）。
     */
    private static String filterLines(char[] chars) {
        StringBuilder sb = new StringBuilder(chars.length);
        int i = 0;
        while (i < chars.length) {
            int end = i;
            while (end < chars.length && chars[end] != '\n') end++;
            boolean hasCode = false;
            boolean marked = false;
            for (int j = i; j < end; j++) {
                if (chars[j] != MARK && !Character.isWhitespace(chars[j])) hasCode = true;
                if (chars[j] == MARK) marked = true;
            }
            if (hasCode || !marked) {
                StringBuilder line = new StringBuilder();
                for (int j = i; j < end; j++) {
                    if (chars[j] != MARK) line.append(chars[j]);
                }
                while (line.length() > 0 && Character.isWhitespace(line.charAt(line.length() - 1))) {
                    line.deleteCharAt(line.length() - 1);
                }
                sb.append(line);
                if (end < chars.length) sb.append('\n');
            }
            i = end < chars.length ? end + 1 : end;
        }
        return sb.toString();
    }
}
