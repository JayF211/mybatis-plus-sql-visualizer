package cn.song.mybatisplus.visualizer.sql

/**
 * EXPLAIN 包装前的**首关键字词法边界判定**（T-ExplainChainHardening T-ECH-01 / O-48）。
 *
 * 根因（第十轮报告 §8 #50，DBX `3a44507a` `is_safe_explain_source` +
 * `starts_with_explain_keyword_boundary` 对齐）：IDE 路径此前对编辑器里的**任意文本**加 EXPLAIN
 * 前缀——`DELETE FROM t`、`CREATE TABLE …`、标识符 `SELECTED` 一律被包成
 * `EXPLAIN DELETE FROM t` 送进数据库。EXPLAIN 虽不执行被包语句，但语法错误与解析副作用仍由库侧
 * 承担，且「点了没报错但结果无意义」比明确拒绝更不诚实。
 *
 * 与 [Dialect.renderExplainStatementIdempotent] 里既有的 `startsWithExplainKeyword` 分工：
 *  - 本 object 的 [startsWithExplainKeyword]：判**包装后的语句**是否已是 EXPLAIN（幂等，防双前缀）；
 *  - 本 object 的 [isExplainableSource]：判**被包语句**的首关键字是否可 EXPLAIN（本任务新增）。
 * 两者都在这里，是因为它们是同一类「词法边界」判定——放一处便于对照，避免再次出现
 * 「一处有边界判定、另一处取任意文本」的口径分叉。
 *
 * **不复用** `execute/SqlTypeClassifier.extractKeyword`（`takeWhile { it.isLetter() }`）：
 * 它会把 `SELECT_foo` 的前缀 `SELECT` 当首关键字 → 放行（方向比 DBX 宽松），正是本任务要拒的形态。
 */
object ExplainGate {

    /**
     * 可被 EXPLAIN 的**首关键字白名单**（小写；与 DBX `is_safe_explain_source` 对齐）。
     *
     * 只列「只读查询表达式」的起始关键字——`SELECT`（含 `SELECT*`/`SELECT(1)`）、`VALUES`
     * （`VALUES (1),(2)` 是合法只读查询）、`TABLE`（PG 的 `TABLE t` 简写）、`WITH`（CTE）。
     * DML/DDL/会话类（INSERT/UPDATE/DELETE/CREATE/…）与未知词一律不在册 → 拒绝。
     */
    private val ALLOWED_FIRST_KEYWORDS = setOf("select", "values", "table", "with")

    /** 首关键字字符集：字母/数字/下划线/`$`（SQL 标识符字符）——取连续段即「第一个词」。 */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /**
     * 取语句的**首个词**（小写）；取不到（空/纯注释/纯符号）返回 null。
     *
     * 取词前跳过：前导空白、前导 `(`（括号化的查询表达式 `(SELECT …) UNION (SELECT …)`——
     * 括号内不可能是可执行的 DML/DDL 形态）、以及前导注释（`-- …` / `/* … */` / `# …`）。
     * 跳过注释是必要的：`-- 查用户\nSELECT …` 这类预览文本在 mapper 里很常见，按「首个非空白
     * 字符不是词」判会误拒；而 MCP 侧的结构白名单（JSqlParser）本来就接受带前导注释的 SELECT，
     * 不跳过会让两链对同一文本给出相反结论。
     */
    fun firstKeyword(sql: String): String? {
        var i = 0
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> i++
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> i = skipToLineEnd(sql, i)
                c == '#' -> i = skipToLineEnd(sql, i)
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> i = skipBlockComment(sql, i)
                else -> {
                    if (!isWordChar(c)) return null
                    var j = i
                    while (j < n && isWordChar(sql[j])) j++
                    return sql.substring(i, j).lowercase()
                }
            }
        }
        return null
    }

    private fun skipToLineEnd(sql: String, from: Int): Int {
        var i = from
        while (i < sql.length && sql[i] != '\n') i++
        return i
    }

    /** 跳过块注释；未闭合（`/*` 后无 `*/`）→ 视作注释延续到结尾（与 SQL 引擎的容忍一致）。 */
    private fun skipBlockComment(sql: String, from: Int): Int {
        val end = sql.indexOf("*/", from + 2)
        return if (end < 0) sql.length else end + 2
    }

    /**
     * 被包语句是否可 EXPLAIN（首关键字落在 [ALLOWED_FIRST_KEYWORDS] 且词边界完整）。
     *
     * 词边界由「取连续词字符段」天然保证：`SELECTED`/`SELECT_foo` 的整段是 `selected`/`select_foo`
     * （不在白名单）→ 拒；`SELECT*`/`SELECT(1)`/`VALUES(1)`/`TABLE users` 的首段是
     * `select`/`values`/`table` → 放行。
     */
    fun isExplainableSource(sql: String?): Boolean =
        firstKeyword(sql ?: "") in ALLOWED_FIRST_KEYWORDS

    /**
     * 语句是否已以 EXPLAIN 关键字开头——紧随字符须非字母/数字/下划线（词边界），
     * 避免 `EXPLAINING_TABLE` 之类标识符误判（T-ExplainDbl 幂等包装判定，语义与迁移前逐字一致）。
     */
    fun startsWithExplainKeyword(sql: String): Boolean {
        if (!sql.startsWith("EXPLAIN", ignoreCase = true)) return false
        return sql.length == 7 || (!sql[7].isLetterOrDigit() && sql[7] != '_')
    }

    /**
     * EXPLAIN 提交判定结果（T-ECH-01）：两类拒绝理由**必须可区分**——「语句不可 EXPLAIN」与
     * 「方言不接入 EXPLAIN」的提示文案不同，合并成一个 null 会让用户拿到错的解释。
     */
    sealed interface SubmitPlan {
        /** 可提交的 EXPLAIN 语句（已包装文本原样提交，未包装文本按方言模板包装）。 */
        data class Submit(val statement: String) : SubmitPlan

        /** 被包语句首关键字不在白名单（[firstKeyword] 为实际取到的词，null = 取不到）。 */
        data class NotExplainable(val firstKeyword: String?) : SubmitPlan

        /** 本方言不接入 EXPLAIN。 */
        data class DialectUnsupported(val dialectName: String) : SubmitPlan
    }

    /**
     * EXPLAIN 提交判定——**IDE 链的唯一决策点**（`SqlPreviewPanel.explainSql`）。
     *
     * 判定序：① 已以 EXPLAIN 开头（幂等场景，是本插件上一次的包装产物）→ 原样提交；
     * ② 被包语句首关键字不在白名单 → [SubmitPlan.NotExplainable]；③ 方言不接入 → [SubmitPlan.DialectUnsupported]。
     *
     * 幂等分支**跳过**白名单是刻意的：包装产物首词是 `EXPLAIN`（不在白名单），若一并判会让
     * 「再点一次执行计划」被误拒（T-ExplainDbl 回归）。
     */
    fun planExplainSubmit(dialect: Dialect, sql: String): SubmitPlan {
        val trimmed = sql.trim()
        if (!startsWithExplainKeyword(trimmed) && !isExplainableSource(trimmed)) {
            return SubmitPlan.NotExplainable(firstKeyword(trimmed))
        }
        val statement = dialect.renderExplainStatementIdempotent(trimmed)
            ?: return SubmitPlan.DialectUnsupported(dialect.name)
        return SubmitPlan.Submit(statement)
    }
}
