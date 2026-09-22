package cn.song.mybatisplus.visualizer.ui.inlay

import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer
import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * INSERT 目标列 inlay 规划器（T-InsertColumnInlay O-44b）：对 SQL 全文规划「值表达式 → 目标列名」提示位点。
 *
 * 规划对象（仅 INSERT 语句，语句类型闸门在 provider 侧按预览模型 `xmlModel.kind` 判定）：
 *  - `INSERT INTO t (c1, c2) VALUES (v1, v2)`：每组 VALUES 元组内**逐表达式**挂 `c1` / `c2`；
 *  - `INSERT INTO t (c1, c2) SELECT p1, p2 FROM …`：逐投影挂目标列名；
 *  - **多组元组各自挂**（`foreach` 生成的批量 INSERT 逐行标注）——与 [SqlAutoFoldPlanner] 的
 *    「每组元组独立判定」口径一致；行内提示是**只读显示层**，重复列名不改变 SQL 语义。
 *
 * **投影去重规则（照抄 DBX `insertValueHints.ts:286` `selectProjectionAlias` 的语义，判据按我方词法形态重定义）**：
 * 投影项已自带**同名列别名**时丢弃该条提示，避免「投影本身就写着 `AS a`，旁边再标一个 `a`」的重复。
 * 别名 = 尾随 `AS <name>`，或**隐式别名**（表达式后跟一个独立标识符，如 `count(*) cnt`）。
 * **点名不算别名**：裸列名（`SELECT a`）与限定名末段（`SELECT t.a`）都**不是**别名——前者无别名，
 * 后者的 `a` 是限定名的一部分（若按「末段即别名」判定，`SELECT t.a` 会被误当成自带别名 `a` 而丢掉提示）。
 *
 * **静态不可判一律不提示**（宁缺勿错，与 `ParamKind` 的 UNKNOWN 口径一致），本规划器覆盖的放弃面：
 *  - 无列清单（`INSERT INTO t VALUES …` / `INSERT INTO t SELECT …`）——目标列不可知；
 *  - 列清单元素非纯列名（函数/表达式/嵌套括号，如 `INSERT INTO t (a, CONCAT(b,c))`）；
 *  - VALUES 元组元素数与列数不等（畸形/动态列清单）；
 *  - `INSERT … SELECT` 的投影数与列数不等；
 *  - 投影为 `*` / `t.*`（展开列数不可判）；
 *  - 元组/列清单未闭合（截断文本）。
 *
 * 纯函数零平台依赖（[SqlLexer] 为普通类）→ 单测无需平台沙箱。
 */
object InsertColumnInlayPlanner {

    /** 单条提示：[offset] = 值表达式 / 投影在全文中的起始偏移（inlay 落点），[columnName] = 目标列名。 */
    data class Hint(val offset: Int, val columnName: String)

    /** 语句起始关键字：扫描中遇到即认定上一条语句已结束（分号缺失的相邻语句防御）。 */
    private val STATEMENT_STARTERS = setOf(
        "SELECT", "INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "DROP", "ALTER", "WITH",
    )

    /** `INSERT … SELECT` 投影列表的终止关键字（达到即封口，不含本 token）。 */
    private val SELECT_TERMINATORS = setOf(
        "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "WINDOW",
    )

    /**
     * 不能作为「隐式别名前驱」的关键字：这些关键字本身是投影的**前导修饰**而非表达式，
     * 其后紧跟的标识符是投影主体（`SELECT DISTINCT a` 的 `a` 不是别名）。
     */
    private val NON_VALUE_ENDER_KEYWORDS = setOf("DISTINCT", "ALL", "UNIQUE", "TOP", "AS")

    private class Tok(val type: IElementType, val start: Int, val end: Int) {
        /**
         * 惰性缓存的大写形态（T-PerfSweep6 T-PS6-01）。
         *
         * 同一 token 在一次规划里可能被**多次**取大写：语句起始关键字判定、`SELECT` 终止关键字判定、
         * 别名前驱判定、列名/别名比较都走 [wordUpper]。原先每次比较都是
         * `substring + uppercase` 两次分配（`text()` 一次、`uppercase()` 一次）；缓存后每个 token
         * 至多分配一次，重复比较零分配。
         *
         * 注：Kotlin 无参 `uppercase()` 即 `Locale.ROOT` 语义（本仓既已如此），此处不额外传 locale。
         */
        var upper: String? = null
    }

    /**
     * T-PerfSweep6 T-PS6-01 测试缝：[plan] 的**实际执行**次数（缓存命中不计入）。
     *
     * 生产链路零读取——它存在的唯一理由是让「stamp 未变不重算」这条缓存契约可被直接观测
     * （[InsertColumnInlayPlanCache] 命中时根本不会走到本函数）。
     */
    internal var planInvocationCount: Int = 0
        private set

    /** 规划提示位点；按扫描顺序天然升序。 */
    fun plan(sql: String): List<Hint> {
        planInvocationCount++
        if (sql.isEmpty()) return emptyList()
        val toks = tokenize(sql)
        if (toks.isEmpty()) return emptyList()
        val hints = ArrayList<Hint>()
        var i = 0
        while (i < toks.size) {
            val next = if (isWord(sql, toks[i], "INSERT")) parseInsert(sql, toks, i, hints) else i + 1
            i = if (next > i) next else i + 1
        }
        return hints
    }

    // ------------------------------------------------------------------
    // 语句级
    // ------------------------------------------------------------------

    /**
     * 解析一条 INSERT；命中列清单 + VALUES/SELECT 才写入 [out]（局部收集，半途放弃不留残条）。
     *
     * @return 继续扫描的 token 下标（语句已结束处；不可判时返回已消费到的位置）
     */
    private fun parseInsert(sql: String, toks: List<Tok>, insertIdx: Int, out: MutableList<Hint>): Int {
        // INSERT 之后必须是 `INTO` 或直接是表名（`INSERT INTO` / `INSERT IGNORE INTO`）——
        // 排除「标识符恰好叫 insert」（如 `SELECT insert, count(x) FROM t`）造成的假语句头
        val first = insertIdx + 1
        if (first >= toks.size) return first
        if (!isWord(sql, toks[first], "INTO") && nameOf(sql, toks[first]) == null) return first

        // 列清单 = INSERT 之后第一个括号组（表名与 INSERT 修饰关键字都不含括号）
        var openIdx = -1
        var i = first
        while (i < toks.size) {
            val t = toks[i]
            if (isParen(sql, t, '(')) {
                openIdx = i
                break
            }
            if (isStatementBoundary(sql, t)) return i
            i++
        }
        if (openIdx < 0) return i

        val read = readNameList(sql, toks, openIdx) ?: return openIdx + 1
        val columns = read.first
        val closeIdx = read.second
        if (columns.isEmpty()) return closeIdx + 1

        val next = closeIdx + 1
        if (next >= toks.size) return closeIdx + 1
        val local = ArrayList<Hint>()
        val consumed = when (wordUpper(sql, toks[next])) {
            "VALUES", "VALUE" -> readValuesTuples(sql, toks, next, columns, local)
            "SELECT" -> readSelectProjections(sql, toks, next, columns, local)
            else -> return closeIdx + 1
        }
        out += local
        return consumed
    }

    /** 列清单：元素必须是**单 token 纯列名**，否则返回 null（整条 INSERT 放弃提示）。 */
    private fun readNameList(sql: String, toks: List<Tok>, openIdx: Int): Pair<List<String>, Int>? {
        val names = ArrayList<String>()
        var elementText: String? = null
        var i = openIdx + 1
        while (i < toks.size) {
            val t = toks[i]
            when {
                isParen(sql, t, '(') -> return null // 列清单内嵌套括号（如 PARTITION (p0)）→ 非纯列名清单
                isParen(sql, t, ')') -> {
                    val name = elementText ?: return null
                    names += name
                    return names to i
                }
                isComma(sql, t) -> {
                    val name = elementText ?: return null
                    names += name
                    elementText = null
                }
                else -> {
                    if (elementText != null) return null // 多 token 元素（函数/表达式）→ 放弃
                    elementText = nameOf(sql, t) ?: return null
                }
            }
            i++
        }
        return null // 未闭合
    }

    /**
     * VALUES 元组：每组独立判定；元素数与列数相等才产出提示，否则跳过该组（不产出）。
     *
     * @return 继续扫描的 token 下标
     */
    private fun readValuesTuples(
        sql: String,
        toks: List<Tok>,
        valuesIdx: Int,
        columns: List<String>,
        out: MutableList<Hint>,
    ): Int {
        var i = valuesIdx + 1
        while (i < toks.size) {
            if (!isParen(sql, toks[i], '(')) break
            val tuple = readValueTuple(sql, toks, i) ?: break
            val starts = tuple.first
            if (starts.size == columns.size) {
                for (k in starts.indices) out += Hint(starts[k], columns[k])
            }
            i = tuple.second
            if (i < toks.size && isComma(sql, toks[i])) {
                i++
                continue
            }
            break
        }
        return i
    }

    /**
     * 单个 VALUES 元组：返回各顶层元素的起始偏移与「闭括号之后」的下标；空元素/未闭合返回 null。
     */
    private fun readValueTuple(sql: String, toks: List<Tok>, openIdx: Int): Pair<List<Int>, Int>? {
        val starts = ArrayList<Int>()
        var elementStart = -1
        var depth = 1
        var i = openIdx + 1
        while (i < toks.size) {
            val t = toks[i]
            when {
                isParen(sql, t, '(') -> {
                    if (elementStart < 0) elementStart = t.start
                    depth++
                }
                isParen(sql, t, ')') -> {
                    depth--
                    if (depth == 0) {
                        if (elementStart < 0) return null
                        starts += elementStart
                        return starts to (i + 1)
                    }
                }
                isComma(sql, t) && depth == 1 -> {
                    if (elementStart < 0) return null
                    starts += elementStart
                    elementStart = -1
                }
                else -> if (elementStart < 0) elementStart = t.start
            }
            i++
        }
        return null
    }

    /**
     * `INSERT … SELECT` 投影列表：投影数与列数相等才产出；逐投影按别名去重、`*` 不产出。
     *
     * @return 继续扫描的 token 下标
     */
    private fun readSelectProjections(
        sql: String,
        toks: List<Tok>,
        selectIdx: Int,
        columns: List<String>,
        out: MutableList<Hint>,
    ): Int {
        val elements = ArrayList<IntRange>()
        var start = -1
        var depth = 0
        var i = selectIdx + 1
        while (i < toks.size) {
            val t = toks[i]
            when {
                isParen(sql, t, '(') -> {
                    if (start < 0) start = i
                    depth++
                }
                isParen(sql, t, ')') -> {
                    if (depth == 0) break // 意外右括号（外层已收口）→ 列表结束
                    depth--
                }
                depth == 0 && isComma(sql, t) -> {
                    if (start < 0) return i // 空投影 → 放弃
                    elements += start until i
                    start = -1
                }
                depth == 0 && (wordUpper(sql, t) in SELECT_TERMINATORS || isStatementBoundary(sql, t)) -> {
                    break
                }
                else -> if (start < 0) start = i
            }
            i++
        }
        if (start >= 0) elements += start until i
        if (elements.isEmpty() || elements.size != columns.size) return i

        for (k in elements.indices) {
            val element = elements[k]
            if (isStarProjection(sql, toks, element)) continue
            val alias = aliasOf(sql, toks, element)
            // 投影已自带同名列别名 → 丢弃该条（其余投影照常提示）
            if (alias != null && alias.equals(columns[k], ignoreCase = true)) continue
            out += Hint(toks[element.first].start, columns[k])
        }
        return i
    }

    // ------------------------------------------------------------------
    // 投影别名（去重判据）
    // ------------------------------------------------------------------

    /**
     * 投影自带别名（`AS x` 或隐式别名 `expr x`）；无别名返回 null。
     *
     * 隐式别名要求前驱 token 与末尾标识符**之间有空白**，且前驱是「值结尾」——
     * `t.a` 的 `.` 不是值结尾，故限定名末段不会被当成别名（点名不算）。
     */
    private fun aliasOf(sql: String, toks: List<Tok>, element: IntRange): String? {
        if (element.last == element.first) return null // 单 token 投影无别名
        val last = toks[element.last]
        val lastText = nameOf(sql, last) ?: return null
        val prev = toks[element.last - 1]
        if (isWord(sql, prev, "AS")) return lastText
        if (sql.substring(prev.end, last.start).isNotBlank()) return null // 无空白 → 同一 token 序列（如 `t.a`）
        return if (isValueEnder(sql, prev)) lastText else null
    }

    /** 前驱 token 是否可作「隐式别名」的表达式结尾。 */
    private fun isValueEnder(sql: String, t: Tok): Boolean = when (t.type) {
        SqlTokenTypes.STRING, SqlTokenTypes.NUMBER,
        SqlTokenTypes.IDENTIFIER, SqlTokenTypes.TABLE,
        -> true
        SqlTokenTypes.PAREN -> sql[t.start] == ')'
        SqlTokenTypes.OPERATOR -> sql.substring(t.start, t.end) in VALUE_ENDER_OPERATORS
        SqlTokenTypes.KEYWORD -> wordUpper(sql, t) !in NON_VALUE_ENDER_KEYWORDS
        else -> false
    }

    /** `*` / `t.*` 投影：展开列数不可判，不提示。 */
    private fun isStarProjection(sql: String, toks: List<Tok>, element: IntRange): Boolean {
        val last = toks[element.last]
        return last.type == SqlTokenTypes.OPERATOR && sql.substring(last.start, last.end) == "*"
    }

    // ------------------------------------------------------------------
    // 词法工具
    // ------------------------------------------------------------------

    private fun tokenize(sql: String): List<Tok> {
        val out = ArrayList<Tok>(sql.length / 4 + 8)
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            lexer.advance()
            if (type == TokenType.WHITE_SPACE || type == SqlTokenTypes.COMMENT) continue
            if (end <= start) continue
            out += Tok(type, start, end)
        }
        return out
    }

    private fun text(sql: String, t: Tok): String = sql.substring(t.start, t.end)

    /** token 的大写形态（T-PS6-01：按 [Tok.upper] 记忆，重复比较零分配）。 */
    private fun wordUpper(sql: String, t: Tok): String {
        val cached = t.upper
        if (cached != null) return cached
        val upper = sql.substring(t.start, t.end).uppercase()
        t.upper = upper
        return upper
    }

    private fun isWord(sql: String, t: Tok, word: String): Boolean = wordUpper(sql, t) == word

    private fun isParen(sql: String, t: Tok, c: Char): Boolean =
        t.type == SqlTokenTypes.PAREN && t.end - t.start == 1 && sql[t.start] == c

    private fun isComma(sql: String, t: Tok): Boolean =
        t.type == SqlTokenTypes.OPERATOR && t.end - t.start == 1 && sql[t.start] == ','

    private fun isStatementBoundary(sql: String, t: Tok): Boolean =
        (t.type == SqlTokenTypes.OPERATOR && t.end - t.start == 1 && sql[t.start] == ';') ||
            wordUpper(sql, t) in STATEMENT_STARTERS

    /**
     * token 文本作为列名 / 别名的取值：剥反引号与方括号（词法器把 `` `x` `` / `[x]` 整段作一个 token）、
     * 去双引号；不是「裸标识符形态」返回 null（数字、字符串、运算符、含空白的段）。
     *
     * 不按 token 类型判定：`SqlLexer` 的表名启发式会把 `INTO t (a, …)` 里的 `a` 标成 TABLE，
     * 关键字集合内的裸列名（如 `key`）会被标成 KEYWORD——两者都是合法列名，须一并接受。
     */
    private fun nameOf(sql: String, t: Tok): String? {
        val raw = text(sql, t).trim()
        if (raw.isEmpty()) return null
        val stripped = when {
            raw.length >= 2 && (raw.first() == '`' || raw.first() == '[') && (raw.last() == '`' || raw.last() == ']') ->
                raw.substring(1, raw.length - 1)
            raw.length >= 2 && raw.first() == '"' && raw.last() == '"' -> raw.substring(1, raw.length - 1)
            else -> raw
        }
        if (stripped.isEmpty()) return null
        if (!stripped[0].isLetter() && stripped[0] != '_' && stripped[0] != '$') return null
        if (stripped.any { !it.isLetterOrDigit() && it != '_' && it != '$' }) return null
        return stripped
    }

    /** 可作表达式结尾的运算符：`*`（`count(*) x`）、`?`（未回填占位符）、`%`（`a % b x`）。 */
    private val VALUE_ENDER_OPERATORS = setOf("*", "?", "%")
}
