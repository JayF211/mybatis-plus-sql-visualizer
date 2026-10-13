package cn.song.mybatisplus.visualizer.converter.jpa;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;

/**
 * HQL/JPQL 动态实例化表达式剥离器（T-HqlBufConcat）。
 *
 * <p>把 {@code new com.x.Dto(a, b)} / {@code new Map(loc.id AS 别名, ...)} 这类<b>构造函数表达式</b>剥离为构造实参
 * {@code a, b}——构造函数表达式只决定 Java 侧结果如何封装，没有任何 SQL 语义，必须从 SQL 产物中摘除，
 * 否则会产出 {@code SELECT new Map(...) FROM ...} 这种非法 SQL（MySQL 直接语法错误）。</p>
 *
 * <p>原实现散落在 {@code HqlConverter} 与 {@code JpqlConverter} 两处逐字重复，且各自以字面量 {@code "NEW"}
 * 做嗅探：
 * <ul>
 *   <li>Hibernate 文档与用户代码里小写 {@code new Map(...)} 是常见写法，{@code indexOf("NEW")} 大小写敏感 → 摘不掉；</li>
 *   <li>反过来 {@code "NEW"} 会命中标识符内部的 {@code NEW}（如 {@code RENEW(}）→ 误摘。</li>
 * </ul>
 * 本类收敛为单一实现：整词 + 大小写不敏感 + 要求「{@code new} 空白 类名 (}」形态，两个方向都修掉。</p>
 */
public final class NewExpressionStripper {

    private NewExpressionStripper() {
    }

    /**
     * {@code new} 关键字（整词、大小写不敏感）后接「限定类名 + 空白 + {@code (}」。
     *
     * <p>前导断言 {@code (?<![A-Za-z0-9_$.])} 排除标识符/点号前缀，使 {@code renew(}、{@code x.new(...)}
     * 不误命中。</p>
     */
    private static final Pattern NEW_EXPRESSION = Pattern.compile(
            "(?<![A-Za-z0-9_$.])new\\s+[A-Za-z_$][\\w$.]*\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * 剥离全部动态实例化表达式（括号计数法支持嵌套，递归处理多个 {@code new}）。
     *
     * @param text HQL/JPQL 文本
     * @return 剥离后的文本；无匹配或括号不匹配时原样返回
     */
    @NotNull
    public static String strip(@NotNull String text) {
        Matcher match = NEW_EXPRESSION.matcher(text);
        if (!match.find()) return text;
        // 匹配串以 '(' 结尾，故 group 结尾即开括号位置
        int newIndex = match.start();
        int parenOpen = match.end() - 1;

        int depth = 1;
        int i = parenOpen + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            i++;
        }
        if (depth != 0) return text; // 括号不匹配 → 不处理（保持原文）

        int parenClose = i - 1;
        String args = text.substring(parenOpen + 1, parenClose).trim();
        String replacement = text.substring(0, newIndex) + args + text.substring(parenClose + 1);
        return strip(replacement);
    }
}
