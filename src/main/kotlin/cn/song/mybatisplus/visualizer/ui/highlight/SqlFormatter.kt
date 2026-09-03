package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * 轻量 SQL 格式化器（M9 增强，T-M27.4 缩进模型重构）：把可编辑 SQL 规范化为标准可读格式。
 *
 * 基于 [SqlLexer] 分词，规则：
 *  - 关键字大写；主子句（SELECT/FROM/WHERE/GROUP/ORDER/HAVING/LIMIT/JOIN/UPDATE/SET/DELETE 等）各占一行
 *  - `LEFT JOIN` 等连写不换行；顶层 `AND`/`OR` 缩进换行
 *  - SELECT/SET 后列清单换行（列缩进 = 本行缩进 + 4）
 *  - 子查询（括号内换行后）内容缩进 = 起始行 + 4、闭括号对齐起始行（EXISTS 子查询标准格式）
 *  - 运算符两侧空格、逗号后空格、`IN (`/`EXISTS (` 等运算符关键字与括号间留空格、函数调用 `COUNT(` 不空格
 *  - 空白（含 XML 动态标签展开残留空行）剔除
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

    /** 函数名（括号前不空格：`COUNT(`）。T-M29.3：补 CONCAT（MP LIKE 常用）/TRIM_SCALE（项目自定义）及常见函数。 */
    private val FUNCTION_NAMES = setOf(
        "COUNT", "SUM", "MAX", "MIN", "AVG", "COALESCE", "CAST", "CONVERT",
        "CONCAT", "TRIM_SCALE", "NULLIF", "IFNULL", "LENGTH", "SUBSTRING", "SUBSTR", "REPLACE",
        "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM", "ROUND", "ABS", "FLOOR", "CEIL", "CEILING",
        "NOW", "CURDATE", "DATE_FORMAT", "STR_TO_DATE", "GROUP_CONCAT", "IF", "JSON_EXTRACT",
        "DATEDIFF", "DATEADD",
    )

    /** 格式化 SQL 文本。 */
    fun format(sql: String): String {
        val tokens = lex(sql)
        if (tokens.isEmpty()) return sql.trim()
        val sb = StringBuilder(sql.length + 64)
        var depth = 0
        var pendingSpace = false
        var atLineStart = true
        var prevText = ""
        // T-M27.4 缩进模型：lineIndent = 当前行缩进；parenIndents = 括号起始行缩进栈
        // （子查询内容缩进 = 起始行 + 4；闭括号对齐起始行）；parenNewlined = 括号内已换行
        var lineIndent = 0
        val parenIndents = ArrayDeque<Int>()
        var parenNewlined = false
        // T-M29.2：当前主子句（SELECT/SET 列清单逗号换行；LIMIT/GROUP/ORDER 等逗号保持同行）
        var currentClause = ""

        fun flushSpace() {
            if (pendingSpace && !atLineStart && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            pendingSpace = false
        }

        /** 换行 + 缩进（更新当前行缩进；括号内标记已换行）。 */
        fun newline(indent: Int) {
            sb.append('\n')
            repeat(indent) { sb.append(' ') }
            lineIndent = indent
            atLineStart = true
            pendingSpace = false
            if (depth > 0) parenNewlined = true
        }

        /** 子查询内新行缩进（起始行 + 4；顶层 0）。 */
        fun subIndent(): Int = if (depth > 0) parenIndents.last() + 4 else 0

        for ((type, text) in tokens) {
            val upper = text.uppercase()
            when {
                // JOIN：前一个 token 是 LEFT/RIGHT/INNER 等 → 保持同行；否则新行（子查询内缩进）
                type == SqlTokenTypes.KEYWORD && upper == "JOIN" -> {
                    val qualifier = prevText.uppercase() in JOIN_QUALIFIERS
                    if (qualifier) {
                        flushSpace()
                    } else if (!atLineStart) {
                        newline(subIndent())
                    }
                    currentClause = "JOIN"
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 主子句 → 新行（顶层顶格；子查询内 = 起始行 + 4）
                type == SqlTokenTypes.KEYWORD && upper in NEW_LINE_KEYWORDS -> {
                    if (!atLineStart) newline(subIndent())
                    pendingSpace = false
                    currentClause = upper
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                    // SELECT/SET 后列清单换行（列缩进 = 本行缩进 + 4）
                    if (upper == "SELECT" || upper == "SET") newline(lineIndent + 4)
                }

                // AND/OR：顶层 → 换行缩进 4；子查询内（括号内已换行）→ 换行缩进 起始行+8；
                // 条件括号内（未换行）→ 保持同行
                type == SqlTokenTypes.KEYWORD && (upper == "AND" || upper == "OR") -> {
                    when {
                        depth == 0 -> if (!atLineStart) newline(4)
                        parenNewlined -> if (!atLineStart) newline(parenIndents.last() + 8)
                        else -> flushSpace()
                    }
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 其余关键字
                type == SqlTokenTypes.KEYWORD -> {
                    flushSpace()
                    sb.append(upper)
                    pendingSpace = true
                    atLineStart = false
                }

                // 左括号：运算符关键字后留空格（IN (）、函数名后不留（COUNT(）；记录起始行缩进
                type == SqlTokenTypes.PAREN && text == "(" -> {
                    if (prevText.uppercase() !in FUNCTION_NAMES) flushSpace()
                    sb.append('(')
                    depth++
                    parenIndents.addLast(lineIndent)
                    parenNewlined = false
                    pendingSpace = false
                    atLineStart = false
                }
                // 右括号：子查询闭括号（括号内已换行）→ 换行对齐起始行；否则同行
                type == SqlTokenTypes.PAREN && text == ")" -> {
                    if (parenNewlined && depth > 0) newline(parenIndents.last())
                    sb.append(')')
                    depth--
                    if (parenIndents.isNotEmpty()) parenIndents.removeLast()
                    parenNewlined = false
                    pendingSpace = true
                    atLineStart = false
                }

                // 逗号：SELECT/SET 列清单（顶层）→ 逗号保留行尾 + 换行缩进 4；其余顶层（LIMIT 参数/
                // GROUP BY/ORDER BY 列表）→ 保持同行；括号内（函数参数/IN 列表）→ 同行
                // T-M29.2：原实现顶层逗号只换行不输出逗号——列清单/分页参数逗号丢失（非法 SQL）
                type == SqlTokenTypes.OPERATOR && text == "," -> {
                    if (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                    sb.append(',')
                    if (depth == 0 && (currentClause == "SELECT" || currentClause == "SET") && !atLineStart) {
                        newline(4)
                    } else {
                        pendingSpace = true
                        atLineStart = false
                    }
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

    /**
     * 关键字换行格式化（M-KeywordWrap）：只在主子句关键字处换行，子句内容同行。
     *
     * 与 [format]（全展开）互补——前者用于阅读细节，后者用于快速总览。
     * 规则：
     *  - 关键字大写；主子句（SELECT/FROM/WHERE/GROUP/ORDER/HAVING/LIMIT/JOIN/UPDATE/SET/DELETE 等）各占一行
     *  - SELECT/SET 后列清单同行，逗号无空格（`id,password,age`）
     *  - WHERE 后条件同行，AND/OR 前双空格（`=?  AND status=?`）
     *  - LIMIT/GROUP/ORDER 参数同行，逗号+空格
     *  - JOIN 修饰符（LEFT/RIGHT/INNER/FULL/CROSS）与 JOIN 同行
     *  - 括号内内容同行（子查询不展开）
     *  - 运算符两侧空格、字符串/数字/标识符原文保留
     *  - 空白剔除
     */
    fun formatClauseLine(sql: String): String {
        val tokens = lex(sql)
        if (tokens.isEmpty()) return sql.trim()
        val sb = StringBuilder(sql.length + 64)
        var pendingSpace = false
        var atLineStart = true
        var prevText = ""
        var currentClause = ""
        var depth = 0  // 括号嵌套深度（括号内不换行 — 子查询同行）
        // SELECT/SET 列清单中刚经过逗号 → 下一个标识符前不空格（逗号紧贴列名）
        var pendingComma = false

        fun flushSpace() {
            if (pendingSpace && !atLineStart && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            pendingSpace = false
        }

        /** 换行顶格（仅顶层触发；括号内不换行）。 */
        fun newline() {
            sb.append('\n')
            atLineStart = true
            pendingSpace = false
        }

        for ((type, text) in tokens) {
            val upper = text.uppercase()
            when {
                // JOIN：前一个 token 是 LEFT/RIGHT/INNER 等 → 保持同行；否则顶层新行
                type == SqlTokenTypes.KEYWORD && upper == "JOIN" -> {
                    val qualifier = prevText.uppercase() in JOIN_QUALIFIERS
                    if (qualifier) {
                        flushSpace()
                    } else if (depth == 0 && !atLineStart) {
                        newline()
                    }
                    currentClause = "JOIN"
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 主子句 → 顶层新行（括号内不换行 — 子查询同行，保留前导空格）
                type == SqlTokenTypes.KEYWORD && upper in NEW_LINE_KEYWORDS -> {
                    if (depth == 0) {
                        if (!atLineStart) newline()
                        pendingSpace = false
                    } else {
                        flushSpace()
                    }
                    currentClause = upper
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // AND/OR：不换行，保持同行（前导双空格 — 子句分隔视觉醒目）
                type == SqlTokenTypes.KEYWORD && (upper == "AND" || upper == "OR") -> {
                    // 清除待处理空格，手动添加双空格（不依赖 flushSpace 的单空格）
                    pendingSpace = false
                    if (!atLineStart) {
                        sb.append(' ')
                        sb.append(' ')
                    }
                    sb.append(upper)
                    atLineStart = false
                    pendingSpace = true
                }

                // 其余关键字
                type == SqlTokenTypes.KEYWORD -> {
                    flushSpace()
                    sb.append(upper)
                    pendingSpace = true
                    atLineStart = false
                }

                // 左括号：运算符关键字后留空格（IN (）；函数名后不留（COUNT(）；深度+1
                type == SqlTokenTypes.PAREN && text == "(" -> {
                    if (prevText.uppercase() !in FUNCTION_NAMES) flushSpace()
                    sb.append('(')
                    depth++
                    pendingSpace = false
                    atLineStart = false
                }
                // 右括号：同行，深度-1（不 flushSpace — 避免 `1 )` 前多余空格）
                type == SqlTokenTypes.PAREN && text == ")" -> {
                    pendingSpace = false
                    sb.append(')')
                    depth--
                    pendingSpace = true
                    atLineStart = false
                }

                // 逗号：SELECT/SET 列清单或括号内 → 逗号紧贴（无空格）；其余 → 逗号+空格
                type == SqlTokenTypes.OPERATOR && text == "," -> {
                    if (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                    sb.append(',')
                    pendingComma = currentClause == "SELECT" || currentClause == "SET" || depth > 0
                    atLineStart = false
                    if (!pendingComma) pendingSpace = true
                }

                // 点号：无空格
                type == SqlTokenTypes.OPERATOR && text == "." -> {
                    sb.append('.')
                    pendingSpace = false
                    pendingComma = false
                    atLineStart = false
                }

                // 其他运算符
                type == SqlTokenTypes.OPERATOR -> {
                    flushSpace()
                    sb.append(text)
                    pendingSpace = true
                    pendingComma = false
                    atLineStart = false
                }

                // 注释
                type == SqlTokenTypes.COMMENT -> {
                    if (!atLineStart) sb.append('\n')
                    sb.append(text)
                    sb.append('\n')
                    atLineStart = true
                    pendingSpace = false
                    pendingComma = false
                }

                // 标识符 / 字符串 / 数字 / 表名
                else -> {
                    if (pendingComma) {
                        // SELECT/SET 逗号后紧贴列名（无空格）
                        pendingComma = false
                    } else {
                        flushSpace()
                    }
                    sb.append(text)
                    pendingSpace = true
                    atLineStart = false
                }
            }
            prevText = text
        }
        return sb.toString().trim()
    }

    /** 分词（剔除空白——含 XML 动态标签展开残留空行）。 */
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
