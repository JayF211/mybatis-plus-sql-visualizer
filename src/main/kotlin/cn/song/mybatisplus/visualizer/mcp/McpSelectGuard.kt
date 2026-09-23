package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.execute.SqlAstParser
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SetOperationList

/**
 * T-McpToolset 只读白名单守卫（sqlv_explain / 未来任何执行类工具共用）。
 *
 * fail closed（计划 §三-2）：非 SELECT / 多语句 / 解析失败 / 锁读 / SELECT INTO 一律拒绝。
 * 双重判定（DBX「风险分类器 + 关键字扫描任一命中即算写」的只读子集）：
 *  1. 关键字粗扫（词边界、大小写不敏感）：写/DDL/会话类动词与锁读/落盘形态；
 *  2. JSqlParser 结构判定：单语句 + Select 子类 + 无 FOR UPDATE + 无 INTO。
 *
 * 纯逻辑层：只依赖 JSqlParser 5.3（运行时既有依赖），无 IDE 类型。
 * JSqlParser API 均经本地 5.3 jar javap 核实（Select.getForUpdateTable / PlainSelect.getIntoTables /
 * PlainSelect.getIntoTempTable / CCJSqlParserUtil.parseStatements→Statements.getStatements）。
 */
object McpSelectGuard {

    /** 输入长度硬上限（防恶意超长输入拖垮解析器）。 */
    const val MAX_SQL_LENGTH = 200_000

    /** 结构化判定结果。[allowed]=false 时 [errorCode]/[reason] 必非空。 */
    data class Verdict(val allowed: Boolean, val errorCode: String? = null, val reason: String? = null)

    /**
     * 写/DDL/会话类动词（词边界粗扫；标识符如 last_update 因下划线不构成词边界不会误伤）。
     * 刻意不含：desc（ORDER BY DESC 合法方向）、replace（REPLACE() 纯读函数）、comment（常见列名）——
     * 对应的写语句形态由 JSqlParser 结构判定兜底拒绝（fail closed 双保险分工）。
     *
     * T-SGE-01（O-61）：[SCALAR_FUNCTION_FORM_WORDS] 中的词在「后随可选空白 + (」的标量函数
     * 形态下豁免（同名列在 [FORBIDDEN_WORDS] 内的其余形态照拒）。
     */
    private val FORBIDDEN_WORDS = listOf(
        "insert", "update", "delete", "merge", "upsert", "call", "exec", "execute",
        "create", "alter", "drop", "truncate", "rename", "grant", "revoke",
        "commit", "rollback", "savepoint", "release", "lock", "unlock",
        "set", "use", "kill", "shutdown", "describe", "show", "analyze",
        "optimize", "repair", "flush", "purge", "load", "unload", "handler", "do", "listen",
        "notify", "reindex", "vacuum", "cluster", "copy", "checkpoint", "discard",
        "import", "export", "admin",
    )

    /**
     * 同名标量函数豁免表（T-SGE-01，O-61）：表内词命中后若后随「可选空白 + (」即视为
     * 同名标量函数（纯读）跳过，判据对齐 DBX 只读边界豁免 `WRITE_VERBS_WITH_SCALAR_FUNCTION_FORMS
     * = ["replace","insert"]`（`crates/dbx-sql/src/query_execution_sql.rs:263-313`，含方言空格）。
     *
     * - insert：MySQL `INSERT(str,pos,len,newstr)` 串函数（8.0 手册 14.4 字符串函数）；
     * - truncate：MySQL `TRUNCATE(X,D)` 数学函数（8.0 手册 12.6.2 Mathematical Functions）——
     *   FORBIDDEN_WORDS 全表「词后随 ( 函数形态」排查（计划 §七 前提 2）唯一的第三个同形词
     *   （replace 已整词豁免），MySQL/PG/Oracle 再无其余同形。
     *
     * 安全边界（计划 §一.2）：粗扫是结构闸（解析 → 单语句 → Select → 锁读/INTO）之后的
     * 第二道闸，能到达粗扫的输入必已解析为单条 Select——真实写语句到不了这一步，
     * 「后随 ( 豁免」不可能放行任何真实写语句；update 无同名标量函数，不豁免（DBX 同款保守口径）。
     */
    private val SCALAR_FUNCTION_FORM_WORDS = setOf("insert", "truncate")

    /** 锁读 / 落盘形态（词边界粗扫；FOR XML/JSON 是 SQL Server 合法读形态，不在表内）。 */
    private val FORBIDDEN_PHRASES = listOf(
        "for update", "for share", "lock in share mode", "into outfile", "into dumpfile",
        "into var", "into @", "for browse",
    )

    /**
     * 校验 [sql] 是单条纯查询（SELECT / UNION 等 [Select] 形态，含 CTE）。
     * 判定序固定：空 → 超长 → 解析 → 单语句 → Select 结构 → 锁读/INTO → 关键字粗扫（剥离字面量后）。
     */
    fun checkSingleSelect(sql: String?): Verdict {
        if (sql.isNullOrBlank()) {
            return deny(McpToolErrors.INVALID_ARGUMENT, "selectSql is required (a single SELECT statement).")
        }
        val trimmed = sql.trim()
        if (trimmed.length > MAX_SQL_LENGTH) {
            return deny(
                McpToolErrors.INVALID_ARGUMENT,
                "selectSql exceeds the maximum length of $MAX_SQL_LENGTH characters.",
            )
        }
        // 双保险判定序：结构判定优先（错误码语义准确：写语句 READ_ONLY、形态违规 SQL_BLOCKED），
        // 关键字粗扫兜底（拦结构判定覆盖不到的可疑词面）；粗扫在剥离字面量后的文本上进行。
        // O-61 §八：解析走 SqlAstParser 单一入口（开 allowSquareBracketQuotation，共享单例 features）——
        // SQL Server 方括号 SELECT（产品方言层自产形态）从「解析失败按写拒绝」变为可进入既有结构判定；
        // 解析失败仍 fail-closed 拒绝（Throwable 含 StackOverflowError 等恶意构造输入场景，语义不动）
        val statements = try {
            SqlAstParser.parseStatements(trimmed)
        } catch (e: Throwable) {
            // 解析失败按写处理（fail closed），不暴露解析器内部细节给 agent；
            // 捕 Throwable：解析器对恶意构造输入可能抛 StackOverflowError 等 Error
            return deny(
                McpToolErrors.SQL_BLOCKED,
                "selectSql could not be parsed as a single standard SELECT statement; " +
                    "refusing to execute (fail-closed policy).",
            )
        }
        if (statements.size != 1) {
            return deny(
                McpToolErrors.SQL_BLOCKED,
                "multiple statements are not allowed (got ${statements.size}); submit exactly one SELECT.",
            )
        }
        val statement: Statement = statements[0]
        if (statement !is Select) {
            return deny(
                McpToolErrors.READ_ONLY,
                "only SELECT statements are allowed; got ${statement?.javaClass?.simpleName ?: "unknown"}.",
            )
        }
        // 锁读双字段判定（JSqlParser 5.3 javap 实证）：FOR UPDATE t 落 forUpdateTable，
        // 裸 FOR UPDATE/FOR SHARE 等落 forMode（UPDATE/SHARE/NO_KEY_UPDATE/KEY_SHARE）
        if (statement.forUpdateTable != null || statement.forMode != null) {
            return deny(McpToolErrors.SQL_BLOCKED, "locking reads (FOR UPDATE/SHARE) are not allowed.")
        }
        if (hasIntoClause(statement)) {
            return deny(
                McpToolErrors.SQL_BLOCKED,
                "SELECT INTO (file/temp-table side effects) is not allowed.",
            )
        }
        val masked = stripLiterals(trimmed).lowercase()
        scanWords(masked)?.let { return it }
        scanPhrases(masked)?.let { return it }
        return Verdict(allowed = true)
    }

    /**
     * 剥离字符串字面量（'…'、"…"）与反引号/方括号标识符内容——粗扫只在结构层文本上进行，
     * 避免 `LIKE '%update%'`、`WHERE col = 'drop'` 等合法查询被关键字粗扫误杀；
     * 字面量内夹带的可疑内容（如 `'; DROP TABLE'`）由 JSqlParser 结构判定兜底（整条必须
     * 解析为单条 Select，多语句/写语句照拒）。剥不清（引号不配对）则按原文粗扫（fail closed）。
     */
    private fun stripLiterals(sql: String): String {
        val masked = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val quote = when (c) {
                '\'', '"', '`' -> c
                '[' -> ']'
                else -> null
            }
            if (quote == null) {
                // 行注释/块注释一并剥离（注释文本同样可含关键字）
                if (c == '-' && i + 1 < sql.length && sql[i + 1] == '-') {
                    while (i < sql.length && sql[i] != '\n') i++
                    continue
                }
                if (c == '/' && i + 1 < sql.length && sql[i + 1] == '*') {
                    val end = sql.indexOf("*/", i + 2)
                    i = if (end < 0) sql.length else end + 2
                    continue
                }
                masked.append(c)
                i++
                continue
            }
            masked.append(quote)
            i++
            while (i < sql.length) {
                if (quote != '[' && sql[i] == quote && i + 1 < sql.length && sql[i + 1] == quote) {
                    i += 2 // 转义的引号（'' / "" / ``）
                    continue
                }
                if (sql[i] == quote) {
                    i++
                    break
                }
                i++
            }
            masked.append(quote)
        }
        return masked.toString()
    }

    private fun scanWords(lower: String): Verdict? {
        for (word in FORBIDDEN_WORDS) {
            for (match in Regex("\\b${Regex.escape(word)}\\b").findAll(lower)) {
                // T-SGE-01：同名标量函数形态（后随可选空白 + (）豁免；其余形态照拒，
                // 同一词多处命中时任一处非函数形态即拒（fail closed）
                if (word in SCALAR_FUNCTION_FORM_WORDS && isScalarFunctionForm(lower, match.range.last)) {
                    continue
                }
                return deny(
                    McpToolErrors.READ_ONLY,
                    "statement contains the non-read-only keyword '$word'; only SELECT is allowed.",
                )
            }
        }
        return null
    }

    /** 词命中点后方是否「可选空白 + (」（DBX `WRITE_VERBS_WITH_SCALAR_FUNCTION_FORMS` 同款判据，含方言空格）。 */
    private fun isScalarFunctionForm(lower: String, matchEnd: Int): Boolean {
        var i = matchEnd + 1
        while (i < lower.length && lower[i].isWhitespace()) i++
        return i < lower.length && lower[i] == '('
    }

    private fun scanPhrases(lower: String): Verdict? {
        for (phrase in FORBIDDEN_PHRASES) {
            if (lower.contains(phrase)) {
                return deny(
                    McpToolErrors.SQL_BLOCKED,
                    "statement contains the restricted clause '$phrase'; only plain reads are allowed.",
                )
            }
        }
        return null
    }

    /** 顶层及 UNION 各分支的 INTO 判定（CTE 包装在 Select 自身，into 仍落在 PlainSelect）。 */
    private fun hasIntoClause(select: Select): Boolean = when (select) {
        is PlainSelect -> !select.intoTables.isNullOrEmpty() || select.intoTempTable != null
        is SetOperationList -> select.selects.filterIsInstance<PlainSelect>().any { branch ->
            !branch.intoTables.isNullOrEmpty() || branch.intoTempTable != null
        }
        else -> false
    }

    private fun deny(code: String, reason: String): Verdict = Verdict(allowed = false, errorCode = code, reason = reason)
}
