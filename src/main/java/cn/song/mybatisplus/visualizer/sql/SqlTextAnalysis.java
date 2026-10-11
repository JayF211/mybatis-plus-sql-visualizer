package cn.song.mybatisplus.visualizer.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 文本分析纯函数模块（T-M27-01）。
 *
 * <p>背景：M27 将 SQL <b>文本级</b>分析沉淀为零 PSI / 零 IDE 依赖的纯函数集合（仅标准库），
 * 供预览编辑器补全、高亮、校验等消费方复用，并可用普通 JUnit 直接覆盖（不依赖 IDE fixture）。
 *
 * <p>模块内容：
 *  - {@link #stripXmlTags}：mapper XML 标签等长空白剥离（偏移量 1:1 映射）；
 *  - {@link SqlClause} / {@link #detectClause} / {@link #detectClauseFromText}：光标位置子句推断；
 *  - {@link TableAlias} / {@link #parseTableAliases}：FROM/JOIN 表清单词法扫描（表名 + 别名）。
 */
public final class SqlTextAnalysis {

    private SqlTextAnalysis() {}

    /**
     * 把 XML 标签替换为<b>等长</b>占位（T-M27-01；T-XmlCmpl3 语义替身修正，20261010）：
     *  - 逐字符扫描：{@code <} 到下一个 {@code >} 的区间（含属性、自闭合、结束标签、注释 {@code <!-- -->}、
     *    CDATA {@code <![CDATA[}）整体占位，标签间文本内容原样保留（含 {@code #{}} / {@code ${}} 占位符）；
     *  - 输出与输入<b>等长</b>（1:1 偏移量映射，供其他分析按原文本偏移量定位）；
     *  - <b>动态控制流开标签 {@code <where>}/{@code <set>} 换等长的关键字替身 {@code WHERE}/{@code SET}（不足补空格）</b>——
     *    纯空白占位会把 WHERE/SET 从文本中抹掉，两处连锁误判（用户真机实证，160 块 where 内
     *    裸列补全零候选）：①{@link #detectClause} 把光标位置误判 TABLE（FROM 成最后可见关键字→
     *    列名分支整体跳过）；②{@link #parseTableAliases} 的表清单吞掉 WHERE 终止词，把 where 内
     *    第一个裸词（如 {@code deleted}）误判为 FROM 表的别名（候选被 {@code 别名.} 前缀污染）；
     *  - 闭合标签与其余标签（if/include/trim/foreach/choose/bind 等无子句关键字语义）照旧空白；
     *  - 依据：XML 文本中 {@code <} 必须转义（{@code &lt;}），故 {@code <} 只会出现在标签起始，算法成立；
     *    未闭合的标签残段同样占位（保持等长）。
     */
    public static String stripXmlTags(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '<') {
                int j = i + 1;
                while (j < text.length() && text.charAt(j) != '>') j++;
                // 含 `>`；未闭合时替换到文本末尾
                int end = j < text.length() ? j + 1 : j;
                String keyword = clauseKeywordOf(text, i, end);
                if (keyword != null) {
                    // 前导空格与左侧词分隔（防 `user<where>` 剥成 `userWHERE` 粘连词），尾部补空格保等长
                    sb.append(' ');
                    sb.append(keyword);
                    for (int k = 0; k < end - i - keyword.length() - 1; k++) sb.append(' ');
                } else {
                    for (int k = i; k < end; k++) sb.append(' ');
                }
                i = end;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 开标签 {@code <where>}/{@code <set>}（含带属性形态，大小写不敏感）→ 大写关键字替身（调用方补空格保等长）；
     * 闭合标签、注释/CDATA/声明、其余标签 → null（纯空白占位）。
     */
    private static String clauseKeywordOf(String text, int start, int end) {
        if (start + 1 >= end) return null;
        char second = text.charAt(start + 1);
        if (second == '/' || second == '!' || second == '?') return null;
        if (!Character.isLetter(second)) return null;
        int j = start + 1;
        while (j < end && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_' || text.charAt(j) == '-')) j++;
        switch (text.substring(start + 1, j).toLowerCase(Locale.ROOT)) {
            case "where":
                return "WHERE";
            case "set":
                return "SET";
            default:
                return null;
        }
    }

    /**
     * 位置判定（T-M27-01 迁移，M16.2 防御语义保留）：
     * offset clamp 到文本长度 + 空文本/异常回退 UNKNOWN。
     *
     * @param text   全量 SQL 文本
     * @param offset 光标偏移量（越界时 clamp 到 [0, text.length]）
     */
    public static SqlClause detectClause(String text, int offset) {
        try {
            int safeOffset = Math.max(0, Math.min(offset, text.length()));
            return detectClauseFromText(text.substring(0, safeOffset));
        } catch (Exception e) {
            return SqlClause.UNKNOWN;
        }
    }

    /**
     * 位置判定纯函数（T-M27-01 迁移，行为逐字节不变）：按光标前文本推断子句。
     */
    public static SqlClause detectClauseFromText(String textBeforeCaret) {
        String text = textBeforeCaret.toUpperCase(Locale.ROOT);
        List<String> keywords = List.of(
                "GROUP BY", "ORDER BY", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL JOIN",
                "SELECT", "FROM", "WHERE", "HAVING", "ON", "JOIN");
        String lastKw = null;
        int lastIdx = -1;
        for (String kw : keywords) {
            int idx = text.lastIndexOf(kw);
            if (idx > lastIdx) {
                lastIdx = idx;
                lastKw = kw;
            }
        }
        if (lastKw == null) return SqlClause.UNKNOWN;
        switch (lastKw) {
            case "FROM":
            case "JOIN":
            case "LEFT JOIN":
            case "RIGHT JOIN":
            case "INNER JOIN":
            case "FULL JOIN":
                return SqlClause.TABLE;
            case "WHERE":
            case "ON":
            case "HAVING":
            case "GROUP BY":
            case "ORDER BY":
                return SqlClause.COLUMN;
            case "SELECT":
                return text.substring(lastIdx).contains(" FROM ") ? SqlClause.COLUMN : SqlClause.SELECT_COLUMN;
            default:
                return SqlClause.UNKNOWN;
        }
    }

    /**
     * 词法扫描 FROM/JOIN 表清单（T-M27-01，大小写不敏感，不做完整 SQL 解析）。
     */
    public static List<TableAlias> parseTableAliases(String text) {
        List<TableAlias> result = new ArrayList<>();
        int i = 0;
        boolean inTableList = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                i = skipBalancedParen(text, i);
                inTableList = false;
                continue;
            }
            Word word = readWord(text, i);
            if (word == null) {
                i++;
                continue;
            }
            String upper = word.text.toUpperCase(Locale.ROOT);
            if (!inTableList) {
                if (upper.equals("FROM") || upper.equals("JOIN")) {
                    inTableList = true;
                } else if (JOIN_MODIFIERS.contains(upper)) {
                    Integer after = consumeJoinModifier(text, i, word);
                    if (after != null) {
                        inTableList = true;
                        i = after;
                        continue;
                    }
                }
                i += word.len;
                continue;
            }
            if (upper.equals("JOIN")) {
                i += word.len;
            } else if (JOIN_MODIFIERS.contains(upper)) {
                Integer after = consumeJoinModifier(text, i, word);
                if (after != null) {
                    i = after;
                } else {
                    inTableList = false;
                    i += word.len;
                }
            } else if (TABLE_TERMINATORS.contains(upper)) {
                inTableList = false;
                i += word.len;
            } else {
                String table = word.text;
                int pos = skipWhitespace(text, i + word.len);
                String alias = null;
                Word peek = readWord(text, pos);
                if (peek == null) {
                    // 无别名
                } else if (peek.text.toUpperCase(Locale.ROOT).equals("AS")) {
                    pos = skipWhitespace(text, pos + peek.len);
                    Word afterAs = readWord(text, pos);
                    if (afterAs != null && isAliasCandidate(afterAs.text.toUpperCase(Locale.ROOT))) {
                        alias = afterAs.text;
                        pos += afterAs.len;
                    }
                } else if (isAliasCandidate(peek.text.toUpperCase(Locale.ROOT))) {
                    alias = peek.text;
                    pos += peek.len;
                }
                i = pos;
                result.add(new TableAlias(table, alias));
            }
        }
        return result;
    }

    /** JOIN 修饰词（表清单位：后跟 [OUTER] JOIN 时是连接符，否则段终止）。 */
    private static final Set<String> JOIN_MODIFIERS = Set.of("LEFT", "RIGHT", "INNER", "CROSS", "FULL");

    /** 表清单终止关键字（表位/别名位）：出现即当前表清单段结束。 */
    private static final Set<String> TABLE_TERMINATORS = Set.of(
            "ON", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "SET", "VALUES",
            "AND", "OR", "AS", "OUTER", "UNION",
            "SELECT", "BY", "IN", "NOT", "IS", "NULL", "LIKE", "BETWEEN", "EXISTS",
            "DISTINCT", "INTO", "UPDATE", "DELETE", "INSERT", "WITH", "CASE", "WHEN",
            "THEN", "ELSE", "END", "EXCEPT", "INTERSECT", "MINUS", "ASC", "DESC",
            "USING", "NATURAL", "LATERAL", "FROM");

    /** 别名候选：非关键字词。 */
    private static boolean isAliasCandidate(String upper) {
        return upper != null && !upper.equals("JOIN") && !JOIN_MODIFIERS.contains(upper) && !TABLE_TERMINATORS.contains(upper);
    }

    /** 跳过从 index 开始的空白，返回第一个非空白字符的索引（可能为文本末尾）。 */
    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    /**
     * 消费 {@code 修饰词 [OUTER] JOIN}：返回消费后的索引；后随词不是 [OUTER] JOIN 时返回 null。
     */
    private static Integer consumeJoinModifier(String text, int i, Word word) {
        int pos = skipWhitespace(text, i + word.len);
        Word peek = readWord(text, pos);
        if (peek == null) return null;
        String p = peek.text.toUpperCase(Locale.ROOT);
        if (p.equals("JOIN")) return pos + peek.len;
        if (p.equals("OUTER")) {
            pos = skipWhitespace(text, pos + peek.len);
            Word join = readWord(text, pos);
            if (join != null && join.text.toUpperCase(Locale.ROOT).equals("JOIN")) return pos + join.len;
            return null;
        }
        return null;
    }

    /**
     * 读取 index 处的一个词：
     *  - 反引号：读到配对反引号；
     *  - 单/双引号：读到配对引号（SQL 双写转义）；
     *  - 其余：字母/数字/下划线/点序列。
     * 返回 (词, 长度)；index 越界或不是词起始字符时返回 null。
     */
    private static Word readWord(String text, int index) {
        if (index >= text.length()) return null;
        char c = text.charAt(index);
        if (c == '`') {
            int end = text.indexOf('`', index + 1);
            return end < 0 ? null : new Word(text.substring(index, end + 1), end + 1 - index);
        }
        if (c == '\'' || c == '"') return readQuoted(text, index, c);
        if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') return null;
        int j = index + 1;
        while (j < text.length()) {
            char ch = text.charAt(j);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.') break;
            j++;
        }
        return new Word(text.substring(index, j), j - index);
    }

    /** 读取配对的单/双引号包裹内容（SQL 双写引号转义）；未闭合返回 null。 */
    private static Word readQuoted(String text, int start, char quote) {
        int j = start + 1;
        while (j < text.length()) {
            if (text.charAt(j) == quote) {
                if (j + 1 < text.length() && text.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return new Word(text.substring(start, j + 1), j + 1 - start);
            }
            j++;
        }
        return null;
    }

    /**
     * 跳过从 start（{@code (} 处）开始的平衡括号对，返回 {@code )} 之后的索引；未闭合时返回文本末尾。
     */
    private static int skipBalancedParen(String text, int start) {
        int depth = 0;
        int i = start;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        return text.length();
    }

    /** 词（文本 + 长度）。 */
    private static final class Word {
        final String text;
        final int len;

        Word(String text, int len) {
            this.text = text;
            this.len = len;
        }
    }
}
