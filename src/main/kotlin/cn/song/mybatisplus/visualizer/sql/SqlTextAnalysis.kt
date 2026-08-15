package cn.song.mybatisplus.visualizer.sql

/**
 * SQL 文本分析纯函数模块（T-M27-01）。
 *
 * 背景：M27 将 SQL **文本级**分析沉淀为零 PSI / 零 IDE 依赖的纯函数集合（仅 Kotlin 标准库），
 * 供预览编辑器补全、高亮、校验等消费方复用，并可用普通 JUnit 直接覆盖（不依赖 IDE fixture）。
 *
 * 模块内容：
 *  - [stripXmlTags]：mapper XML 标签等长空白剥离（偏移量 1:1 映射）；
 *  - [SqlClause] / [detectClause] / [detectClauseFromText]：光标位置子句推断
 *    （M16 位置感知补全语义，自 `SqlCompletionContributor` 迁移，行为逐字节不变）；
 *  - [TableAlias] / [parseTableAliases]：FROM/JOIN 表清单词法扫描（表名 + 别名）。
 */

/**
 * 把 XML 标签替换为**等长空格**（T-M27-01）：
 *  - 逐字符扫描：`<` 到下一个 `>` 的区间（含属性、自闭合、结束标签、注释 `<!-- -->`、
 *    CDATA `<![CDATA[`）整体换空格，标签间文本内容原样保留（含 `#{}` / `${}` 占位符）；
 *  - 输出与输入**等长**（1:1 偏移量映射，供其他分析按原文本偏移量定位）；
 *  - 依据：XML 文本中 `<` 必须转义（`&lt;`），故 `<` 只会出现在标签起始，算法成立；
 *    未闭合的标签残段同样换空格（保持等长）。
 */
fun stripXmlTags(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '<') {
            var j = i + 1
            while (j < text.length && text[j] != '>') j++
            // 含 `>`；未闭合时替换到文本末尾
            val end = if (j < text.length) j + 1 else j
            for (k in i until end) sb.append(' ')
            i = end
        } else {
            sb.append(text[i])
            i++
        }
    }
    return sb.toString()
}

/**
 * 光标所在位置的 SQL 子句语义（M16 位置感知补全；T-M27-01 自 `SqlCompletionContributor` 迁入本模块）。
 *
 * 语义（由 [detectClauseFromText] 推断）：
 *  - [TABLE]：FROM / JOIN（含 LEFT/RIGHT/INNER/FULL JOIN）之后 → 表名位；
 *  - [COLUMN]：WHERE / ON / HAVING / GROUP BY / ORDER BY 之后 → 列名位；
 *  - [SELECT_COLUMN]：SELECT 列位（列 + 聚合函数）；
 *  - [UNKNOWN]：无法判定 → 全量候选。
 */
enum class SqlClause { TABLE, COLUMN, SELECT_COLUMN, UNKNOWN }

/**
 * 位置判定（T-M27-01 迁移，M16.2 防御语义保留）：
 * offset clamp 到文本长度 + 空文本/异常回退 UNKNOWN——旧 PSI 未随编辑更新时 offset 可能
 * 超过新文档长度，substring 越界抛异常会让补全静默失效（回归点）。
 *
 * @param text   全量 SQL 文本
 * @param offset 光标偏移量（越界时 clamp 到 [0, text.length]）
 */
fun detectClause(text: String, offset: Int): SqlClause = runCatching {
    val safeOffset = offset.coerceIn(0, text.length)
    detectClauseFromText(text.substring(0, safeOffset))
}.getOrElse { SqlClause.UNKNOWN }

/**
 * 位置判定纯函数（T-M27-01 迁移，行为逐字节不变）：按光标前文本推断子句。
 *
 * 仅向前扫描**最后出现**的关键词（复合词优先匹配长词），判定失败回退全量：
 *  - FROM / JOIN（含 LEFT/RIGHT/INNER/FULL JOIN）之后 → [SqlClause.TABLE]；
 *  - WHERE / ON / HAVING / GROUP BY / ORDER BY 之后 → [SqlClause.COLUMN]；
 *  - SELECT 之后且其后无 FROM → [SqlClause.SELECT_COLUMN]；
 *  - 无法判定 → [SqlClause.UNKNOWN]。
 */
fun detectClauseFromText(textBeforeCaret: String): SqlClause {
    val text = textBeforeCaret.uppercase()
    // 复合词优先（GROUP BY/ORDER BY/各类 JOIN），再单关键词
    val keywords = listOf(
        "GROUP BY", "ORDER BY", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL JOIN",
        "SELECT", "FROM", "WHERE", "HAVING", "ON", "JOIN",
    )
    var lastKw: String? = null
    var lastIdx = -1
    for (kw in keywords) {
        val idx = text.lastIndexOf(kw)
        if (idx > lastIdx) {
            lastIdx = idx
            lastKw = kw
        }
    }
    return when (lastKw) {
        "FROM", "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL JOIN" -> SqlClause.TABLE
        "WHERE", "ON", "HAVING", "GROUP BY", "ORDER BY" -> SqlClause.COLUMN
        "SELECT" -> {
            // SELECT 之后若已有 FROM（当前在 SELECT 与 FROM 之间之后的列位）→ 列名位
            if (text.substring(lastIdx).contains(" FROM ")) SqlClause.COLUMN else SqlClause.SELECT_COLUMN
        }
        else -> SqlClause.UNKNOWN
    }
}

/**
 * FROM/JOIN 表清单中的一张表（T-M27-01）。
 *
 * @param table 表名（原始文本：`tableName` / `db.t` 点分 / `` `t` `` 反引号形态）
 * @param alias 表别名；无别名时为 null（如 `FROM t`、`FROM t, u` 的 `u` 等）
 */
data class TableAlias(val table: String, val alias: String?)

/**
 * 词法扫描 FROM/JOIN 表清单（T-M27-01，大小写不敏感，不做完整 SQL 解析）：
 *  - `FROM t` / `FROM t a` / `FROM t AS a`（AS 可选）；
 *  - `FROM a a1, b b1`（逗号多表）；
 *  - `LEFT|RIGHT|INNER|CROSS|FULL [OUTER] JOIN t [AS] a`（修饰词后 JOIN）；
 *  - 括号内子查询整体跳过（深度计数）；`FROM` 后直接跟 `(` 时无表名，跳过该段；
 *  - 表名 = 标识符词（字母/数字/下划线/点，或反引号包裹任意字符）；别名 = 表名后下一个
 *    非关键字词（JOIN/ON/WHERE/GROUP/ORDER/HAVING/LIMIT/SET/VALUES/AND/OR/AS/LEFT/RIGHT/
 *    INNER/CROSS/FULL/OUTER/UNION 等视为关键字终止）；
 *  - 单双引号字符串整体跳过（`'inner'` 等字面量不会误触发关键字）。
 */
fun parseTableAliases(text: String): List<TableAlias> {
    val result = mutableListOf<TableAlias>()
    var i = 0
    var inTableList = false
    while (i < text.length) {
        val c = text[i]
        if (c.isWhitespace()) {
            i++
            continue
        }
        if (c == '(') {
            // 括号内子查询整体跳过（深度计数）；子查询占表位 → 该段结束
            i = skipBalancedParen(text, i)
            inTableList = false
            continue
        }
        val word = readWord(text, i)
        if (word == null) {
            i++
            continue
        }
        val upper = word.first.uppercase()
        if (!inTableList) {
            // 主扫描：FROM / JOIN / 修饰词 [OUTER] JOIN 进入表清单位
            if (upper == "FROM" || upper == "JOIN") {
                inTableList = true
            } else if (upper in JOIN_MODIFIERS) {
                val after = consumeJoinModifier(text, i, word)
                if (after != null) {
                    inTableList = true
                    i = after
                    continue
                }
            }
            i += word.second
            continue
        }
        // 表清单位：连接符 / 修饰词 / 终止关键字 / 表名
        when {
            upper == "JOIN" -> i += word.second
            upper in JOIN_MODIFIERS -> {
                val after = consumeJoinModifier(text, i, word)
                if (after != null) i = after else {
                    // 修饰词后无 JOIN → 段终止
                    inTableList = false
                    i += word.second
                }
            }
            upper in TABLE_TERMINATORS -> {
                inTableList = false
                i += word.second
            }
            else -> {
                // 表名 + 可选别名
                val table = word.first
                var pos = skipWhitespace(text, i + word.second)
                var alias: String? = null
                val peek = readWord(text, pos)
                when {
                    // `,` / `(` / 结束 / 非词字符 → 无别名
                    peek == null -> Unit
                    // AS 别名：跳过 AS，取下一个非关键字词为别名
                    peek.first.uppercase() == "AS" -> {
                        pos = skipWhitespace(text, pos + peek.second)
                        val afterAs = readWord(text, pos)
                        if (afterAs != null && isAliasCandidate(afterAs.first.uppercase())) {
                            alias = afterAs.first
                            pos += afterAs.second
                        }
                    }
                    // 非关键字词 → 即别名
                    isAliasCandidate(peek.first.uppercase()) -> {
                        alias = peek.first
                        pos += peek.second
                    }
                    // 其余关键字（JOIN/修饰词/终止词）→ 无别名，词不消费、交回主循环判定
                    else -> Unit
                }
                i = pos
                result.add(TableAlias(table, alias))
            }
        }
    }
    return result
}

/** JOIN 修饰词（表清单位：后跟 `[OUTER] JOIN` 时是连接符，否则段终止）。 */
private val JOIN_MODIFIERS = setOf("LEFT", "RIGHT", "INNER", "CROSS", "FULL")

/**
 * 表清单终止关键字（表位/别名位）：出现即当前表清单段结束。
 * 别名不能取这些词——JOIN 与修饰词单独判定，其余在此集合。
 */
private val TABLE_TERMINATORS = setOf(
    // 任务契约列出的关键字
    "ON", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "SET", "VALUES",
    "AND", "OR", "AS", "OUTER", "UNION",
    // 常用补充（关键字终止的"等"）：子句/连接补全词
    "SELECT", "BY", "IN", "NOT", "IS", "NULL", "LIKE", "BETWEEN", "EXISTS",
    "DISTINCT", "INTO", "UPDATE", "DELETE", "INSERT", "WITH", "CASE", "WHEN",
    "THEN", "ELSE", "END", "EXCEPT", "INTERSECT", "MINUS", "ASC", "DESC",
    "USING", "NATURAL", "LATERAL", "FROM",
)

/** 别名候选：非关键字词（JOIN / 修饰词 / 终止关键字都不是别名）。 */
private fun isAliasCandidate(upper: String?): Boolean =
    upper != null && upper != "JOIN" && upper !in JOIN_MODIFIERS && upper !in TABLE_TERMINATORS

/** 跳过从 index 开始的空白，返回第一个非空白字符的索引（可能为文本末尾）。 */
private fun skipWhitespace(text: String, index: Int): Int {
    var i = index
    while (i < text.length && text[i].isWhitespace()) i++
    return i
}

/**
 * 消费 `修饰词 [OUTER] JOIN`（LEFT/RIGHT/INNER/CROSS/FULL [OUTER] JOIN）：
 * 返回消费后的索引；后随词不是 `[OUTER] JOIN` 时返回 null（不消费任何词）。
 */
private fun consumeJoinModifier(text: String, i: Int, word: Pair<String, Int>): Int? {
    var pos = skipWhitespace(text, i + word.second)
    val peek = readWord(text, pos)
    return when (peek?.first?.uppercase()) {
        "JOIN" -> pos + peek.second
        "OUTER" -> {
            pos = skipWhitespace(text, pos + peek.second)
            val join = readWord(text, pos)
            if (join?.first?.uppercase() == "JOIN") pos + join.second else null
        }
        else -> null
    }
}

/**
 * 读取 index 处的一个词（T-M27-01）：
 *  - 反引号 `` ` ``：读到配对反引号（内部任意字符，如 `` `my table` ``）；
 *  - 单/双引号：读到配对引号（SQL 双写转义 `''` / `""`）——整体作为一词跳过，
 *    `'inner'` 等字面量不会误触发关键字；
 *  - 其余：字母/数字/下划线/点序列（`tableName` / `db.t`）。
 *
 * 返回 (词, 长度)；index 越界或不是词起始字符时返回 null。
 */
private fun readWord(text: String, index: Int): Pair<String, Int>? {
    if (index >= text.length) return null
    val c = text[index]
    if (c == '`') {
        val end = text.indexOf('`', index + 1)
        return if (end < 0) null else Pair(text.substring(index, end + 1), end + 1 - index)
    }
    if (c == '\'' || c == '"') return readQuoted(text, index, c)
    if (!c.isLetterOrDigit() && c != '_' && c != '.') return null
    var j = index + 1
    while (j < text.length) {
        val ch = text[j]
        if (!ch.isLetterOrDigit() && ch != '_' && ch != '.') break
        j++
    }
    return Pair(text.substring(index, j), j - index)
}

/** 读取配对的单/双引号包裹内容（SQL 双写引号转义）；未闭合返回 null。 */
private fun readQuoted(text: String, start: Int, quote: Char): Pair<String, Int>? {
    var j = start + 1
    while (j < text.length) {
        if (text[j] == quote) {
            if (j + 1 < text.length && text[j + 1] == quote) {
                j += 2
                continue
            }
            return Pair(text.substring(start, j + 1), j + 1 - start)
        }
        j++
    }
    return null
}

/**
 * 跳过从 start（`(` 处）开始的平衡括号对（括号深度计数），返回 `)` 之后的索引；
 * 未闭合时返回文本末尾。
 */
private fun skipBalancedParen(text: String, start: Int): Int {
    var depth = 0
    var i = start
    while (i < text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i + 1
            }
        }
        i++
    }
    return text.length
}
