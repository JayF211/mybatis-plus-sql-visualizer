package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter

/**
 * T-ExplainChainHardening T-ECH-01：EXPLAIN 包装链的**被包语句首关键字**词法边界判定（O-48）。
 *
 * 背景（第十轮报告 §8 #50，DBX `crates/dbx-sql/src/query_execution_sql.rs` 的 `is_safe_explain_source`
 * + `starts_with_explain_keyword_boundary`）：包装前的唯一词法判定此前针对的是 **EXPLAIN 关键字自身**
 * （同文件的 `startsWithExplainKeyword`，防 `EXPLAINING_TABLE` 被当已包装文本），被包语句可以是任意文本——
 * IDE 链取编辑器原文（`SqlPreviewPanel.explainSql`），`UPDATE …` / `DROP TABLE …` / `SELECTED …`
 * 都会被加前缀送进库。本 object 在包装前补上被包语句的首关键字判定，口径与 DBX 对齐。
 *
 * 白名单：`SELECT`（含 `SELECT*`/`SELECT(1)` 无空白形态）、`VALUES`、`TABLE`、`WITH`。
 * **词边界是显式判定**（[startsWithWord]：命中词之后紧随字符须非字母/数字/`_`）——不复用
 * [cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier] 的 `extractKeyword`
 * （`takeWhile { it.isLetter() }` 会把 `SELECT_foo` 截成 `SELECT` 放行，方向比 DBX 宽松）；
 * 判据形态参照 [cn.song.mybatisplus.visualizer.converter.jpa.SelectClauseExpander] 的 `isWordBoundary` 先例。
 *
 * **整段文本判定（不止首条语句）**：多语句脚本按顶层分号切分后**每条**都要过白名单。只判首条会漏掉
 * `SELECT 1; DROP TABLE t`——包装成 `EXPLAIN SELECT 1; DROP TABLE t` 后第二条 DDL 仍会真实执行
 * （D1=A 要堵的正是「错包把 DML/DDL 送进数据库」）。切分复用 [SqlStatementSplitter.splitStripped]
 * （注释/字符串/括号/BEGIN-END 感知，与 [cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.isQuery]
 * 同一实现，不另造切分器）。
 *
 * 取词前先跳过前导空白、`--`/`/* */` 注释与可选的左括号（PG 允许 `EXPLAIN (SELECT 1)` 形态）——
 * 直接 `startsWith("SELECT")` 会把以注释开头的合法文本判拒（属功能回退）。
 *
 * 消费点（**同链两处**，缺一即两链行为分叉）：
 *  - [Dialect.renderExplainStatementIdempotent]（IDE 面板链）——本门排在幂等短路**之后**，
 *    已包装文本原样返回的既有价值（T-ExplainDbl 防双前缀）不被本门打掉；
 *  - [cn.song.mybatisplus.visualizer.mcp.SqlVisualizerExplainTool] 的 `resolveExplainTarget`
 *    （MCP 链走的是非幂等 [Dialect.renderExplainStatement]）。
 *
 * 边界（有意为之，非缺陷）：`WITH` 只判首关键字，不展开 CTE 体（`WITH del AS (DELETE …) SELECT …`
 * 形态与 DBX 白名单同口径放行）——EXPLAIN 只做 estimated，不执行被包语句；写 CTE 的拦截由既有
 * 双守卫承担（[cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier] 的只读二级证明、
 * [cn.song.mybatisplus.visualizer.mcp.McpSelectGuard] 的关键字粗扫），本门不改其语义。
 */
object ExplainGate {

    /** 可被 EXPLAIN 包装的首关键字白名单（大写；与 DBX `is_safe_explain_source` 对齐）。 */
    val EXPLAINABLE_KEYWORDS: Set<String> = setOf("SELECT", "VALUES", "TABLE", "WITH")

    /**
     * 文本（可含多条语句与注释）是否**整体**为可 EXPLAIN 形态。
     *
     * @param sql SQL 文本（编辑器原文或 MCP 入参）
     * @return true=每条语句首关键字均命中白名单；空文本或任一条不命中 → false（fail closed，不包装）
     */
    fun isExplainableSource(sql: String): Boolean {
        val statements = SqlStatementSplitter.splitStripped(sql)
        if (statements.isEmpty()) return false
        return statements.all { isExplainableStatement(it) }
    }

    /**
     * 首条语句的首关键字（大写；跳过前导空白/注释/左括号）——提示文案用（`UPDATE` 之类）。
     *
     * @return 首词大写形态；无词（空文本 / 纯注释）返回 null
     */
    fun firstKeyword(sql: String): String? {
        val head = skipLeadingTrivia(sql) ?: return null
        val end = head.indexOfFirst { !isWordChar(it) }
        val word = if (end < 0) head else head.substring(0, end)
        return word.ifEmpty { null }?.uppercase()
    }

    /** 单条语句（切分产物，注释已剥离）是否可 EXPLAIN。 */
    private fun isExplainableStatement(statement: String): Boolean {
        val head = skipLeadingTrivia(statement) ?: return false
        return EXPLAINABLE_KEYWORDS.any { startsWithWord(head, it) }
    }

    /**
     * 词边界判定：`text` 以 [keyword] 开头（忽略大小写）且紧随字符非字母/数字/`_`
     * （`text` 恰好等于词长时亦成立）。
     *
     * 与 [Dialect.renderExplainStatementIdempotent] 的 `startsWithExplainKeyword` 同款判据，
     * 区别只在于判定对象是**被包语句首词**而非 EXPLAIN 关键字自身。
     */
    private fun startsWithWord(text: String, keyword: String): Boolean {
        if (!text.startsWith(keyword, ignoreCase = true)) return false
        return text.length == keyword.length || !isWordChar(text[keyword.length])
    }

    /** 词内字符：字母/数字/`_`（`SELECT_foo` 因 `_` 属词内字符而整词取出，不命中白名单）。 */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * 跳过前导空白、`--` 行注释、`/* */` 块注释与可选的左括号，返回剩余文本。
     *
     * @return 首个有效字符起的子串；整段皆为 trivia 时返回 null
     */
    private fun skipLeadingTrivia(text: String): String? {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c.isWhitespace() -> i++
                c == '-' && next == '-' -> while (i < text.length && text[i] != '\n') i++
                c == '/' && next == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                }
                // 左括号：`(SELECT 1)` 形态（PG 允许 `EXPLAIN (SELECT 1)`）——剥一层再看首词
                c == '(' -> i++
                else -> return text.substring(i)
            }
        }
        return null
    }
}
