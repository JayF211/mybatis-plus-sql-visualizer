package cn.song.mybatisplus.visualizer.xml;

import java.util.function.Function;

/**
 * 通用 token 解析器（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.parsing.GenericTokenParser。
 *
 * - \openToken / \closeToken 为转义：去掉反斜杠、原样输出；
 * - 有 openToken 无 closeToken：剩余部分原样输出（不报错）；
 * - 空输入返回原样。
 */
public final class TokenParser {

    public static final TokenParser INSTANCE = new TokenParser();

    private TokenParser() {
    }

    /** 扫描 openToken..closeToken，每个 token 内容经 handler 转换后回填。 */
    public static String parse(String text, String openToken, String closeToken, Function<String, String> handler) {
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        int offset = 0;
        int start = text.indexOf(openToken, offset);
        while (start > -1) {
            if (start > 0 && text.charAt(start - 1) == '\\') {
                // 转义：去掉反斜杠原样输出 openToken
                result.append(text, offset, start - 1).append(openToken);
                offset = start + openToken.length();
            } else {
                result.append(text, offset, start);
                offset = start + openToken.length();
                int end = text.indexOf(closeToken, offset);
                while (end > -1) {
                    if (end > offset && text.charAt(end - 1) == '\\') {
                        result.append(text, offset, end - 1).append(closeToken);
                        offset = end + closeToken.length();
                        end = text.indexOf(closeToken, offset);
                    } else {
                        break;
                    }
                }
                if (end == -1) {
                    result.append(text, start, text.length());
                    offset = text.length();
                } else {
                    result.append(handler.apply(text.substring(offset, end)));
                    offset = end + closeToken.length();
                }
            }
            start = text.indexOf(openToken, offset);
        }
        result.append(text, offset, text.length());
        return result.toString();
    }
}
