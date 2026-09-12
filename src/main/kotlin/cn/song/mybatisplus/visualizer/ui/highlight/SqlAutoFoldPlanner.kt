package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.psi.TokenType

/**
 * SQL 自动折叠规划器（T-AutoFold F1）：对 SQL 全文按「顶层字段列表」规划折叠区域。
 *
 * 折叠对象（需求 §3.1，仅顶层——嵌套子查询内的列表不折叠，外层区域命中后天然覆盖内层）：
 *  - SELECT 列清单（`SELECT` 之后到 `FROM`/其他子句关键字前）；
 *  - INSERT 列清单（表名后的括号组内）；
 *  - VALUES 每组元组（各自独立判定）；
 *  - UPDATE 的 SET 清单（`SET` 之后到 `WHERE`/`ON` 前）。
 *
 * 触发条件（需求 §3.2）：列表**顶层元素数**（自身括号深度内的逗号分段数）≥ 阈值，且区域跨 ≥ 2 行
 * （同行无折叠价值——压缩单行 SQL 因此自动零区域，NORMAL 模式与竖排的语义一致按元素数判定）。
 *
 * 区域边界（需求 §3.3）：从列表首元素起、止于末元素行尾（不含换行符）——首行关键字行
 * （SELECT/VALUES 行）保持可见，折叠后 `FROM`/`)` 等结构仍在原位。末元素行内若同行还有
 * 实质内容（如压缩单行的 `FROM` 紧随），区域止于末元素，不吞后续 token。
 *
 * 输出按扫描顺序天然升序、互不重叠（单遍状态机：收集中更深的 token 不记录、外层封口后
 * 才可能开启下一列表）——[com.intellij.openapi.editor.FoldingModel.addFoldRegion] 对相交区域
 * 返回 null，规划器自保证互斥比依赖返回值可靠。
 *
 * 纯函数零平台依赖（[SqlLexer] 为普通类）→ 单测无需平台沙箱。
 */
object SqlAutoFoldPlanner {

    /** 单个折叠区域：文档偏移区间 [startOffset, endOffset) + 收起占位符（如 `… 23 列`）。 */
    data class FoldSpec(val startOffset: Int, val endOffset: Int, val placeholder: String)

    private enum class State {
        IDLE, SELECT_LIST, INSERT_STMT, INSERT_COLS, AFTER_INSERT_COLS,
        VALUES_TUPLE, AFTER_VALUES, UPDATE_STMT, SET_LIST,
    }

    /** SELECT 列清单的终止关键字（达到即列表封口；不含本 token）。 */
    private val SELECT_TERMINATORS = setOf(
        "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "WINDOW",
    )

    /** 语句起始关键字兜底：未正常封口的收集遇到下一条语句立即丢弃/封口，防跨语句错位。 */
    private val STATEMENT_STARTERS = setOf(
        "SELECT", "INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "DROP", "ALTER", "WITH",
    )

    /**
     * 规划折叠区域。
     *
     * @param text                SQL 全文（编辑器当前文本）
     * @param threshold           顶层元素数阈值（< 该值不折叠；≤ 0 防御性返回空）
     * @param placeholderTemplate 占位符模板，`{0}` 为元素数（如 `… {0} 列`）
     */
    fun plan(text: String, threshold: Int, placeholderTemplate: String): List<FoldSpec> {
        if (threshold <= 0 || text.isEmpty()) return emptyList()
        val lineStarts = ArrayList<Int>(text.length / 32 + 2)
        lineStarts.add(0)
        for (i in text.indices) {
            if (text[i] == '\n') lineStarts.add(i + 1)
        }

        val specs = ArrayList<FoldSpec>()
        var state = State.IDLE
        var depth = 0
        var baseDepth = 0
        var listStart = -1
        var lastEnd = -1
        var commas = 0

        fun resetCollect() {
            listStart = -1
            lastEnd = -1
            commas = 0
        }

        /** 列表封口：元素数与跨行条件均满足才产出区域（占位符列数 = 顶层逗号分段数）。 */
        fun finish() {
            val count = commas + 1
            if (listStart >= 0 && lastEnd > listStart && count >= threshold) {
                val end = expandToLineEnd(text, lastEnd)
                if (lineOf(lineStarts, listStart) != lineOf(lineStarts, end)) {
                    specs += FoldSpec(listStart, end, placeholderTemplate.replace("{0}", count.toString()))
                }
            }
            resetCollect()
        }

        val lexer = SqlLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val ts = lexer.tokenStart
            val te = lexer.tokenEnd
            lexer.advance()
            if (type == TokenType.WHITE_SPACE || type == SqlTokenTypes.COMMENT) continue

            if (type == SqlTokenTypes.PAREN && te - ts == 1) {
                when (text[ts]) {
                    '(' -> {
                        depth++
                        if (depth == 1) {
                            // VALUES 每组元组 / INSERT 表名后列清单：深度 0→1 的括号开启收集
                            when (state) {
                                State.INSERT_STMT -> {
                                    state = State.INSERT_COLS; baseDepth = 1; resetCollect()
                                }
                                State.AFTER_VALUES -> {
                                    state = State.VALUES_TUPLE; baseDepth = 1; resetCollect()
                                }
                                else -> {}
                            }
                        }
                    }
                    ')' -> {
                        val closingTuple = state == State.INSERT_COLS || state == State.VALUES_TUPLE
                        if (closingTuple && depth == baseDepth) {
                            finish()
                            state = if (state == State.INSERT_COLS) State.AFTER_INSERT_COLS else State.AFTER_VALUES
                        }
                        if (depth > 0) depth--
                    }
                }
                continue
            }

            if (type == SqlTokenTypes.OPERATOR && te - ts == 1 && text[ts] == ',') {
                if (isCollecting(state) && depth == baseDepth) commas++
                continue
            }

            // 分号 = 语句边界：未封口的收集直接丢弃（跨语句折叠没有语义）
            if (type == SqlTokenTypes.OPERATOR && te - ts == 1 && text[ts] == ';') {
                if (isCollecting(state)) resetCollect()
                state = State.IDLE
                continue
            }

            val isKw = type == SqlTokenTypes.KEYWORD
            val upper = if (isKw) text.substring(ts, te).uppercase() else ""

            if (isCollecting(state)) {
                if (depth != baseDepth) continue
                // 终止关键字不入列表（FROM 吞进区域即吃掉结构），先封口再回落语句状态机
                val terminates = isKw && (
                    (state == State.SELECT_LIST && upper in SELECT_TERMINATORS) ||
                        (state == State.SET_LIST && (upper == "WHERE" || upper == "ON")) ||
                        upper in STATEMENT_STARTERS
                    )
                if (terminates) {
                    finish()
                    state = State.IDLE
                    if (!isKw) continue
                } else {
                    if (listStart < 0) listStart = ts
                    lastEnd = te
                    continue
                }
            }

            if (isKw) {
                // 语句起始关键字统一重置（AFTER_VALUES 卡死防御：分号缺失的相邻语句）
                if (upper in STATEMENT_STARTERS) state = State.IDLE
                when (state) {
                    State.IDLE -> when (upper) {
                        "SELECT", "REPLACE" -> state = State.SELECT_LIST
                        "INSERT" -> state = State.INSERT_STMT
                        "UPDATE" -> state = State.UPDATE_STMT
                        "SET" -> {
                            baseDepth = 0
                            resetCollect()
                            state = State.SET_LIST
                        }
                        else -> {}
                    }
                    State.INSERT_STMT, State.AFTER_INSERT_COLS, State.AFTER_VALUES -> when (upper) {
                        "VALUES" -> state = State.AFTER_VALUES
                        "ON" -> state = State.IDLE
                        "SELECT" -> state = State.SELECT_LIST
                        else -> {}
                    }
                    State.UPDATE_STMT -> when (upper) {
                        "SET" -> {
                            baseDepth = 0
                            resetCollect()
                            state = State.SET_LIST
                        }
                        "WHERE" -> state = State.IDLE
                        else -> {}
                    }
                    else -> {}
                }
            }
        }
        return specs
    }

    private fun isCollecting(state: State): Boolean =
        state == State.SELECT_LIST || state == State.INSERT_COLS ||
            state == State.VALUES_TUPLE || state == State.SET_LIST

    /**
     * 末元素行尾扩展：末元素之后同行只剩空白到换行 → 吸收行尾空白（不含换行符），
     * 折叠后下一行原样显示 `FROM`/`)` 等结构；同行还有实质内容（压缩单行）→ 不扩展。
     */
    private fun expandToLineEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return if (i >= text.length || text[i] == '\n' || text[i] == '\r') i else from
    }

    /** offset 所在行号（lineStarts 升序，二分）。 */
    private fun lineOf(lineStarts: List<Int>, offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}
