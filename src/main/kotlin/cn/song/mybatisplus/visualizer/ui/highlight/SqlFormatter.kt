package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * 轻量 SQL 格式化器（M9 增强）：把可编辑 SQL 规范化为可读格式。
 *
 * 基于 [SqlLexer] 分词，规则：
 *  - 关键字大写；主子句（SELECT/FROM/WHERE/GROUP/ORDER/HAVING/LIMIT/JOIN/UPDATE/SET/DELETE 等）各占一行
 *  - `LEFT JOIN` 等连写不换行；WHERE 顶层 `AND`/`OR` 缩进换行
 *  - 运算符两侧空格、逗号后空格、`IN (`/`EXISTS (` 等运算符关键字与括号间留空格、函数调用 `COUNT(` 不空格
 *  - 字符串/数字/标识符原文保留（不做大小写改写）
 *
 * 覆盖本插件产出的 SELECT/UPDATE/DELETE + MPJ 联表形态，不做通用 SQL 方言格式化。
 */
object SqlFormatter {

    private val NEW_LINE_KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT",
        "UPDATE", "SET", "DELETE", "INSERT", "INTO", "VALUES", "UNION", "JOIN",
        "LEFT", "RIGHT", "INNER", "FULL", "CROSS",
    )

    /** 紧跟 JOIN 的修饰关键字（`LEFT JOIN` 保持同行）。 */
    private val JOIN_QUALIFIERS = setOf("LEFT", "RIGHT", "INNER", "FULL", "CROSS")

    /** 函数名（括号前不空格：`COUNT(`）。 */
    private val FUNCTION_NAMES = setOf("COUNT", "SUM", "MAX", "MIN", "AVG", "COALESCE", "CAST", "CONVERT")

    /** 格式化 SQL 文本。 */
    fun format(sql: String): String {
        val tokens = lex(sql)
        if (tokens.isEmpty()) return sql.trim()
        val sb = StringBuilder(sql.length + 64)
        var depth = 0
        var pendingSpace = false
        var atLineStart = true
        var prevText = ""

        fun flushSpace() {
            if (pendingSpace && !atLineStart && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            pendingSpace = false
        }

        for ((type, text) in tokens) {
            val upper = text.uppercase()
            when {
                // JOIN：前一个 token 是 LEFT/RIGHT/INNER 等 → 保持同行（刷新空格）；否则新行
                type == SqlTokenTypes.KEYWORD && upper == "JOIN" -> {
                    val qualifier = prevText.uppercase() in JOIN_QUALIFIERS
                    if (qualifier) {
                        flushSpace()
                    } else if (!atLineStart) {
                        sb.append('\n')
                        atLineStart = true
                        pendingSpace = false
                    }
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 主子句 → 新行
                type == SqlTokenTypes.KEYWORD && upper in NEW_LINE_KEYWORDS -> {
                    if (!atLineStart) {
                        sb.append('\n')
                        atLineStart = true
                    }
                    pendingSpace = false
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // WHERE 顶层 AND/OR → 缩进换行
                type == SqlTokenTypes.KEYWORD && (upper == "AND" || upper == "OR") && depth == 0 -> {
                    if (!atLineStart) sb.append('\n')
                    sb.append("    ").append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 其余关键字
                type == SqlTokenTypes.KEYWORD -> {
                    flushSpace()
                    sb.append(upper)
                    pendingSpace = true
                }

                // 左括号：运算符关键字后留空格（IN (）、函数名后不留（COUNT(）
                type == SqlTokenTypes.PAREN && text == "(" -> {
                    if (prevText.uppercase() !in FUNCTION_NAMES) flushSpace()
                    sb.append('(')
                    depth++
                    pendingSpace = false
                    atLineStart = false
                }
                type == SqlTokenTypes.PAREN && text == ")" -> {
                    sb.append(')')
                    depth--
                    pendingSpace = true
                    atLineStart = false
                }

                // 逗号：", "
                type == SqlTokenTypes.OPERATOR && text == "," -> {
                    if (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                    sb.append(", ")
                    pendingSpace = false
                    atLineStart = false
                }

                // 点号：无空格
                type == SqlTokenTypes.OPERATOR && text == "." -> {
                    sb.append('.')
                    pendingSpace = false
                    atLineStart = false
                }

                // 其他运算符
                type == SqlTokenTypes.OPERATOR -> {
                    flushSpace()
                    sb.append(text)
                    pendingSpace = true
                    atLineStart = false
                }

                // 注释
                type == SqlTokenTypes.COMMENT -> {
                    if (!atLineStart) sb.append('\n')
                    sb.append(text)
                    sb.append('\n')
                    atLineStart = true
                    pendingSpace = false
                }

                // 标识符 / 字符串 / 数字 / 表名
                else -> {
                    flushSpace()
                    sb.append(text)
                    pendingSpace = true
                    atLineStart = false
                }
            }
            prevText = text
        }
        return sb.toString().trim()
    }

    /** 分词（剔除空白）。 */
    private fun lex(sql: String): List<Pair<IElementType, String>> {
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE) tokens += type to lexer.tokenText
            lexer.advance()
        }
        return tokens
    }
}
