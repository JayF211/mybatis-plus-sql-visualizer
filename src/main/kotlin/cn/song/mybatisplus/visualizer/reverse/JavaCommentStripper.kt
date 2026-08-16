package cn.song.mybatisplus.visualizer.reverse

/**
 * Java 注释剥离（T-M45：复制「纯 Java 代码」——忽略反向生成的说明注释/日志）。
 *
 * 纯文本扫描（不依赖 PSI/IDE，任意环境可单测）：
 *  - `//` 行注释：整行（行首仅有空白）→ 整行删除；行尾（前面有代码）→ 删注释保留换行；
 *  - 块注释（斜杠星号成对）：单行/多行整段删除（多行块注释逐行处理，行结构保留）；
 *  - 字符串/字符字面量内的斜杠标记不视为注释（转义引号正确处理）；
 *  - 代码空行（分隔用）原样保留——只删「注释行」，不压缩代码本身。
 *
 * 实现：两遍扫描——① 注释区替换为 NUL 标记（保留换行），② 逐行过滤：
 * 含代码的行去标记 + 去行尾空白；纯标记行（注释行）整行丢弃。
 */
object JavaCommentStripper {

    /** 注释占位标记（NUL 不出现在合法 Java 源码中，可安全用作哨兵）。 */
    private const val MARK = '\u0000'

    /** 剥离全部注释，返回纯代码文本。 */
    fun strip(code: String): String {
        val chars = code.toCharArray()
        var i = 0
        while (i < chars.size) {
            when {
                chars[i] == '"' || chars[i] == '\'' -> i = skipQuoted(chars, i)
                chars[i] == '/' && i + 1 < chars.size && chars[i + 1] == '/' ->
                    i = markLineComment(chars, i)
                chars[i] == '/' && i + 1 < chars.size && chars[i + 1] == '*' ->
                    i = markBlockComment(chars, i)
                else -> i++
            }
        }
        return filterLines(chars)
    }

    /** 跳过字符串/字符字面量（`\\` 转义序列吃两个字符，遇配对引号结束；未闭合 → 扫到末尾）。 */
    private fun skipQuoted(chars: CharArray, start: Int): Int {
        val quote = chars[start]
        var i = start + 1
        while (i < chars.size) {
            when (chars[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                else -> i++
            }
        }
        return chars.size
    }

    /** 行注释：从 `//` 到行尾全部标记（换行符保留，停在 `\n` 处）。 */
    private fun markLineComment(chars: CharArray, start: Int): Int {
        var i = start
        while (i < chars.size && chars[i] != '\n') {
            chars[i] = MARK
            i++
        }
        return i
    }

    /** 块注释（斜杠星号成对）：整段标记（换行符保留）。 */
    private fun markBlockComment(chars: CharArray, start: Int): Int {
        var i = start
        chars[i] = MARK
        chars[i + 1] = MARK
        i += 2
        while (i < chars.size) {
            if (chars[i] == '*' && i + 1 < chars.size && chars[i + 1] == '/') {
                chars[i] = MARK
                chars[i + 1] = MARK
                return i + 2
            }
            if (chars[i] != '\n') chars[i] = MARK
            i++
        }
        return chars.size
    }

    /**
     * 逐行过滤：含代码的行 → 去标记 + 去行尾空白；纯标记行（注释行）→ 整行丢弃；
     * 原始空行（无标记）→ 原样保留（代码分隔结构不变）。
     */
    private fun filterLines(chars: CharArray): String {
        val sb = StringBuilder(chars.size)
        var i = 0
        while (i < chars.size) {
            var end = i
            while (end < chars.size && chars[end] != '\n') end++
            val hasCode = (i until end).any { chars[it] != MARK && !chars[it].isWhitespace() }
            val marked = (i until end).any { chars[it] == MARK }
            if (hasCode || !marked) {
                // 行内容先在局部缓冲裁剪（不触碰 sb 里已输出的换行——空行场景）
                val line = StringBuilder()
                for (j in i until end) {
                    if (chars[j] != MARK) line.append(chars[j])
                }
                while (line.isNotEmpty() && line.last().isWhitespace()) line.deleteCharAt(line.length - 1)
                sb.append(line)
                if (end < chars.size) sb.append('\n')
            }
            i = if (end < chars.size) end + 1 else end
        }
        return sb.toString()
    }
}
