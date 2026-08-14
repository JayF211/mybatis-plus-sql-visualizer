package cn.song.mybatisplus.visualizer.xml

/**
 * 通用 token 解析器（T-M24-01）：复刻 MyBatis 3.5.19 `org.apache.ibatis.parsing.GenericTokenParser`。
 *
 * - `\openToken` / `\closeToken` 为转义：去掉反斜杠、原样输出；
 * - 有 openToken 无 closeToken：剩余部分原样输出（不报错）；
 * - 空输入返回原样。
 */
object TokenParser {

    /** 扫描 `openToken..closeToken`，每个 token 内容经 [handler] 转换后回填。 */
    fun parse(text: String, openToken: String, closeToken: String, handler: (String) -> String): String {
        if (text.isEmpty()) return text
        val result = StringBuilder()
        var offset = 0
        var start = text.indexOf(openToken, offset)
        while (start > -1) {
            if (start > 0 && text[start - 1] == '\\') {
                // 转义：去掉反斜杠原样输出 openToken
                result.append(text, offset, start - 1).append(openToken)
                offset = start + openToken.length
            } else {
                result.append(text, offset, start)
                offset = start + openToken.length
                var end = text.indexOf(closeToken, offset)
                while (end > -1) {
                    if (end > offset && text[end - 1] == '\\') {
                        result.append(text, offset, end - 1).append(closeToken)
                        offset = end + closeToken.length
                        end = text.indexOf(closeToken, offset)
                    } else {
                        break
                    }
                }
                if (end == -1) {
                    result.append(text, start, text.length)
                    offset = text.length
                } else {
                    result.append(handler(text.substring(offset, end)))
                    offset = end + closeToken.length
                }
            }
            start = text.indexOf(openToken, offset)
        }
        result.append(text, offset, text.length)
        return result.toString()
    }
}
