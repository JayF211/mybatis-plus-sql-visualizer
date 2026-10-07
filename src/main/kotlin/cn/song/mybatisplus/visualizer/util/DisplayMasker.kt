package cn.song.mybatisplus.visualizer.util

/**
 * R60（T-LaneB D95/D96）：展示/导出链字面量脱敏——规则**逐条对齐**采集侧 agent
 * `sub-projects/sql-visualizer-agent/.../util/Desensitizer.java:10-30`（V112.1 落档）：
 *
 * ①列名/参数名提示：hint 小写化后 contains 命中 [KEYWORDS] 任一项 → 打码；
 * ②值形态兜底（无 hint 也命中）：18 位身份证（前 17 位数字+末位数字/X/x）或
 *   11 位手机号（全数字且首位 1）→ 打码；打码结果固定 [MASK]，空/null 值原样返回。
 *
 * agent 是独立源集（随 jar 注入目标应用 JVM），主仓 classpath 不可引用——两份同规则文本，
 * **关键词表/形态判定/打码值改动须双侧同步**（D97 口径对齐声明：采集 agent / MCP 输出 /
 * 展示导出三面同一套双条件规则）。
 *
 * 本类纯 Kotlin 零平台依赖；开关由调用方读设置传入（[maskDisplay]），MCP 面
 * （sqlv_render_logs）与展示面（列表/预览浮层/导出）各自走各自的设置键（D96 双开关）。
 */
object DisplayMasker {

    /** 打码结果（与采集侧 Desensitizer.MASK 一致）。 */
    const val MASK = "***"

    /** 敏感关键词表（Desensitizer.java:12-16 逐项同步；改动双侧同步）。 */
    private val KEYWORDS = arrayOf(
        "password", "passwd", "pwd", "token", "secret", "credential", "apikey", "api_key",
        "access_key", "private_key", "idcard", "id_card", "mobile", "phone",
        "身份证", "手机号", "密码", "密钥", "令牌",
    )

    /**
     * 双条件打码（Desensitizer.mask 等价移植）：列名提示 contains 命中或值形态命中 → [MASK]；
     * 其余原样。空/null 值原样（不打码）。
     */
    fun mask(columnHint: String?, value: String?): String? {
        if (value == null || value.isEmpty()) {
            return value
        }
        if (columnHint != null) {
            val hint = columnHint.lowercase()
            if (KEYWORDS.any { hint.contains(it) }) {
                return MASK
            }
        }
        return if (looksLikeIdCard(value) || looksLikeMobile(value)) MASK else value
    }

    /** 18 位身份证形态（前 17 位数字 + 末位数字/X/x）。 */
    fun looksLikeIdCard(v: String): Boolean {
        if (v.length != 18) return false
        for (i in 0..16) {
            if (!Character.isDigit(v[i])) return false
        }
        val last = v[17]
        return Character.isDigit(last) || last == 'X' || last == 'x'
    }

    /** 11 位手机号形态（全数字且首位 1）。 */
    fun looksLikeMobile(v: String): Boolean {
        if (v.length != 11) return false
        for (i in v.indices) {
            if (!Character.isDigit(v[i])) return false
        }
        return v[0] == '1'
    }

    // ------------------------------------------------------------------
    // SQL 文本级扫描（展示/导出/MCP 链共用）
    // ------------------------------------------------------------------

    /** SQL 关键字黑名单（列名 hint 推导时跳过——`WHERE x = 'v'` 的 hint 是 x 不是 WHERE）。 */
    private val SQL_KEYWORDS = setOf(
        "select", "from", "where", "and", "or", "not", "in", "is", "like", "set", "values",
        "insert", "update", "delete", "into", "join", "on", "as", "by", "order", "group",
        "having", "limit", "offset", "distinct", "case", "when", "then", "else", "end",
        "between", "exists", "union", "all", "asc", "desc", "null", "true", "false",
    )

    /** 字符串字面量 `'...'`（含 `''` 转义）或裸数字形态（11 位手机号/18 位身份证）的联合扫描。 */
    private val TOKEN_REGEX = Regex("'(?:[^']|'')*'|\\b\\d{11}\\b|\\b\\d{17}[0-9Xx]\\b")

    /**
     * SQL 文本脱敏：字符串字面量按「前置最近标识符作列名 hint」双条件打码为 `'***'`；
     * 裸数字仅按值形态打码为 `***`（保留原引号形态，展示面可读）。非敏感片段逐字保留。
     *
     * 值内子串命中的形态**不**打码（对齐采集侧「参数值整体判形态」语义）——联合正则天然
     * 保证：引号字面量整体命中后其内部数字不再单独扫描。
     */
    fun maskSqlText(sql: String): String = transform(sql) { ctx ->
        when (ctx.kind) {
            TokenKind.LITERAL -> {
                val inner = ctx.text.substring(1, ctx.text.length - 1).replace("''", "'")
                if (mask(ctx.hint, inner) == MASK) "'$MASK'" else ctx.text
            }
            TokenKind.BARE_NUMBER -> MASK
        }
    }

    /** 链入口：[enabled] 开 → [maskSqlText]，关 → 原文逐字（列表/预览浮层/导出三链共用）。 */
    fun maskDisplay(sql: String, enabled: Boolean): String = if (enabled) maskSqlText(sql) else sql

    /**
     * R37 参数化产物：敏感字面量替换为 `?` 的 SQL + 脱敏参数表（值恒 [MASK]，原值不出本方法）。
     *
     * @param sql 参数化后的 SQL（非敏感字面量保留原文）
     * @param maskedParams 按出现序的脱敏参数值（恒 "***"）
     */
    data class ParameterizedSql(val sql: String, val maskedParams: List<String>)

    /**
     * R37 敏感字面量参数化（V51.3 规则）：敏感字面量（引号/裸数字）替换为 `?`，
     * 打码值进 [ParameterizedSql.maskedParams]；非敏感片段逐字保留。零敏感时 SQL 即原文、参数表空。
     */
    fun parameterize(sql: String): ParameterizedSql {
        val params = mutableListOf<String>()
        val out = transform(sql) { ctx ->
            when (ctx.kind) {
                TokenKind.LITERAL -> {
                    val inner = ctx.text.substring(1, ctx.text.length - 1).replace("''", "'")
                    if (mask(ctx.hint, inner) == MASK) {
                        params += MASK
                        "?"
                    } else {
                        ctx.text
                    }
                }
                TokenKind.BARE_NUMBER -> {
                    params += MASK
                    "?"
                }
            }
        }
        return ParameterizedSql(out, params)
    }

    /** 扫描 token 形态。 */
    private enum class TokenKind { LITERAL, BARE_NUMBER }

    /** 命中 token + 推导的列名 hint（仅 LITERAL 有；裸数字无 hint 走形态）。 */
    private class TokenContext(val text: String, val kind: TokenKind, val hint: String?)

    /** SQL 字符串字面量的引号字符。 */
    private const val SQL_QUOTE = '\''

    /** 联合扫描 + 逐 token 变换（非命中区间逐字保留）。 */
    private inline fun transform(sql: String, mapper: (TokenContext) -> String): String {
        val sb = StringBuilder(sql.length)
        var last = 0
        for (m in TOKEN_REGEX.findAll(sql)) {
            val token = m.value
            val kind = if (token.first() == SQL_QUOTE) TokenKind.LITERAL else TokenKind.BARE_NUMBER
            sb.append(sql, last, m.range.first)
            sb.append(mapper(TokenContext(token, kind, if (kind == TokenKind.LITERAL) columnHintBefore(sql, m.range.first) else null)))
            last = m.range.last + 1
        }
        sb.append(sql, last, sql.length)
        return sb.toString()
    }

    /**
     * 字面量前置上下文的列名 hint：取字面量起点前 [HINT_WINDOW] 字符内的**最后一个**
     * 标识符（跳过 SQL 关键字）——`WHERE phone = '…'` 取 phone、`IN ('a','b')` 取 IN 前的
     * 列名、`VALUES ('x')` 取最近列名或无。取不到返回 null（只走形态兜底）。
     */
    private fun columnHintBefore(sql: String, literalStart: Int): String? {
        val from = maxOf(0, literalStart - HINT_WINDOW)
        val window = sql.substring(from, literalStart)
        val identifiers = ID_REGEX.findAll(window).map { it.value }.filter { it.lowercase() !in SQL_KEYWORDS }.toList()
        return identifiers.lastOrNull()
    }

    /** 标识符（含中文列名/别名形态）。 */
    private val ID_REGEX = Regex("[A-Za-z_][A-Za-z0-9_$]*|[\\u4e00-\\u9fa5]+")

    /** 列名 hint 回看窗口（字符；覆盖 `WHERE very_long_column_name = ` 量级）。 */
    private const val HINT_WINDOW = 48
}
