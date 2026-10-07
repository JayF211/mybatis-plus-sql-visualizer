package cn.song.mybatisplus.visualizer.reverse

/**
 * SQL 词法单元（SQL→Java 反向转换的词法层，P0）。
 *
 * 覆盖 SQL→Wrapper 逆向所需的词形：标识符（含反引号包裹与 `表.列` 点分）、
 * 字符串字面量（SQL 双写引号转义）、数字、单字符符号、注释（`--` / `/* */`，跳过不产出）。
 * 括号不单独计数——由 [SqlTokenizer.Token.type] 的 [TokenType.PAREN_LEFT]/[PAREN_RIGHT]
 * 表达，解析器自行维护深度。
 */
internal class SqlTokenizer {

    /** 词法单元。 */
    data class Token(
        val type: TokenType,
        val text: String,
        val offset: Int,
    )

    enum class TokenType {
        /** 标识符 / 关键字 / 函数名（含反引号包裹与点分形态的原文）。 */
        WORD,

        /** 字符串字面量（单引号包裹原文，含引号）。 */
        STRING,

        /** 数字字面量（原文字面量）。 */
        NUMBER,

        /** 单字符符号（= ! < > ( ) , * . ; + - / %）。 */
        SYMBOL,
    }

    /**
     * 分词。
     *
     * @throws SqlReverseException 词法错误（未闭合字符串/反引号、非法字符），key 走 [SqlReverseParser.KEY_PARSE]
     */
    fun tokenize(sql: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> i = skipLineComment(sql, i)
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> i = skipBlockComment(sql, i)
                c == '\'' -> {
                    val (end, text) = readQuoted(sql, i, c)
                    if (end < 0) throw SqlReverseException(i, SqlReverseParser.KEY_PARSE, "未闭合的字符串字面量")
                    tokens.add(Token(TokenType.STRING, text, i))
                    i = end
                }
                c == '`' || c == '"' -> {
                    // 反引号/双引号包裹的标识符（T-SqlToJavaPromote G10：双引号标识符方言宽容，标准 SQL/SQL Server）
                    val end = sql.indexOf(c, i + 1)
                    if (end < 0) throw SqlReverseException(i, SqlReverseParser.KEY_PARSE, "未闭合的 $c 标识符")
                    tokens.add(Token(TokenType.WORD, sql.substring(i, end + 1), i))
                    i = end + 1
                }
                c.isDigit() || (c == '.' && i + 1 < sql.length && sql[i + 1].isDigit()) -> {
                    var j = i + 1
                    // 十六进制字面量（0x1F，T-SqlToJavaPromote G10）：0 后跟 x 时按十六进制扫描
                    if (c == '0' && j < sql.length && (sql[j] == 'x' || sql[j] == 'X')) {
                        j++
                        while (j < sql.length && (sql[j].isDigit() || sql[j] in 'a'..'f' || sql[j] in 'A'..'F')) j++
                    } else {
                        while (j < sql.length && (sql[j].isDigit() || sql[j] == '.')) j++
                    }
                    tokens.add(Token(TokenType.NUMBER, sql.substring(i, j), i))
                    i = j
                }
                c.isLetterOrDigit() || c == '_' -> {
                    var j = i + 1
                    // 点号仅在「后一字符不是反引号」时并入 WORD——`t.`signal`` 须拆成 `t`、`.`、`` `signal` ``
                    // 三个 token（consumeQualified 会按 `.` 合并），否则反引号列会被 consumeSelectAlias 误当别名吞掉
                    while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_' ||
                            (sql[j] == '.' && j + 1 < sql.length && sql[j + 1] != '`'))) j++
                    tokens.add(Token(TokenType.WORD, sql.substring(i, j), i))
                    i = j
                }
                c in "=!<>(),*.;+-/%?" -> {
                    tokens.add(Token(TokenType.SYMBOL, c.toString(), i))
                    i++
                }
                else -> throw SqlReverseException(i, SqlReverseParser.KEY_PARSE, "无法识别的字符 '$c'")
            }
        }
        return tokens
    }

    /** 行注释：`--` 到行尾（P0 支持，SQL 生成器输出不含注释，手写 SQL 常见）。 */
    private fun skipLineComment(sql: String, start: Int): Int {
        var j = start + 2
        while (j < sql.length && sql[j] != '\n') j++
        return j
    }

    /** 块注释：斜杠星号到星号斜杠，未闭合时消费到末尾（容忍）。 */
    private fun skipBlockComment(sql: String, start: Int): Int {
        val end = sql.indexOf("*/", start + 2)
        return if (end < 0) sql.length else end + 2
    }

    /**
     * 读取配对引号包裹的内容（SQL 双写转义 `''` + 反斜杠转义 `\'`，T-SqlToJavaPromote G10）；
     * 返回 (结束索引, 含引号原文)。未闭合返回 (-1, "")。
     */
    private fun readQuoted(sql: String, start: Int, quote: Char): Pair<Int, String> {
        var j = start + 1
        while (j < sql.length) {
            if (sql[j] == '\\' && j + 1 < sql.length) {
                // 反斜杠转义（MySQL `\'` / `\\`）：跳过下一个字符
                j += 2
                continue
            }
            if (sql[j] == quote) {
                if (j + 1 < sql.length && sql[j + 1] == quote) {
                    j += 2
                    continue
                }
                return Pair(j + 1, sql.substring(start, j + 1))
            }
            j++
        }
        return Pair(-1, "")
    }
}
