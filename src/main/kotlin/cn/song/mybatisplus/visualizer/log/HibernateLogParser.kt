package cn.song.mybatisplus.visualizer.log

/**
 * Hibernate/JPA 控制台日志解析器。
 *
 * 支持的日志形态：
 * - SQL 行（Hibernate: 前缀）：`Hibernate: select ...`
 * - SQL 行（注释前缀）：`/* load com.example.User */ select ...`
 * - SQL 行（纯 SQL，[recognizeBareSql]=true 时）：行首即 SELECT/INSERT/UPDATE/DELETE
 * - 参数行：`binding parameter [1] as [BIGINT] - [1]`
 *
 * 解析策略：
 * - SQL 行触发新条目开启（闭合上一条未完成的条目）
 * - 参数行追加到当前 pending 条目
 * - 下一条 SQL 行到来时自动闭合当前条目
 *
 * 与 [MyBatisLogParser] 并列运行，互不干扰。共享 [LogSqlEntry] 数据模型。
 * 产出条目的 [LogSqlEntry.source] 固定为 [SqlSource.HIBERNATE]。
 */
class HibernateLogParser(
    private val restorer: LogSqlRestorer = LogSqlRestorer(),

    /** SQL 行前缀（默认 `Hibernate:`）。 */
    var sqlPrefix: String = DEFAULT_SQL_PREFIX,

    /** 参数行前缀（默认 `binding parameter`）。 */
    var paramPrefix: String = DEFAULT_PARAM_PREFIX,

    /** 混合前缀（true=关键字宽容匹配；false=行首精确前缀匹配）。 */
    var mixedPrefix: Boolean = true,

    /** 行解析最大长度（超长截断，防控制台大输出卡顿）。 */
    var maxParseLength: Int = DEFAULT_MAX_PARSE_LENGTH,

    /** 是否识别无前缀的纯 SQL 行（行首即 SELECT/INSERT/UPDATE/DELETE）。 */
    var recognizeBareSql: Boolean = true,
) {

    private val entries = mutableListOf<LogSqlEntry>()
    private var pendingSql: String? = null
    private val pendingParams = mutableListOf<LogSqlParam>()

    /** 喂入一行控制台输出（任意线程，内部无共享可变状态——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        // 分支 1：SQL 行（Hibernate: 前缀 / 注释前缀 / 纯 SQL）
        val sql = extractSql(line)
        if (sql != null) {
            closePending()
            pendingSql = sql.take(maxParseLength)
            pendingParams.clear()
            return
        }

        // 分支 2：参数绑定行
        val param = extractBindingParam(line)
        if (param != null) {
            if (pendingSql == null) return // 无 SQL 上下文，忽略
            pendingParams += param
            return
        }

        // 分支 3：噪声行（忽略）
    }

    /** 闭合当前未完成条目（新语句到来 / 面板刷新时调用）。 */
    fun closePending(): LogSqlEntry? {
        val sql = pendingSql ?: return null
        pendingSql = null
        val entry = LogSqlEntry(
            rawSql = sql,
            restoredSql = restorer.restore(sql, pendingParams),
            params = pendingParams.toList(),
            source = SqlSource.HIBERNATE,
        )
        entries += entry
        pendingParams.clear()
        return entry
    }

    /** 取出全部已闭合条目并清空缓冲区（面板刷新用）。 */
    fun takeEntries(): List<LogSqlEntry> {
        val result = entries.toList()
        entries.clear()
        return result
    }

    // ---------- 私有方法 ----------

    /**
     * 尝试从行中提取 SQL 文本。
     * 三种模式按优先级：显式前缀 → 注释前缀 → 纯 SQL。
     * @return SQL 文本或 null（非 SQL 行）。
     */
    private fun extractSql(line: String): String? {
        // 1. 显式前缀（Hibernate:）
        val prefixed = extractAfterKeyword(line, sqlPrefix)
        if (prefixed != null && looksLikeSql(prefixed)) {
            return prefixed
        }

        // 2. 注释前缀（/* ... */ SQL）
        val commentSql = extractAfterComment(line)
        if (commentSql != null) {
            return commentSql
        }

        // 3. 纯 SQL 行（recognizeBareSql=true 时）
        if (recognizeBareSql) {
            val trimmed = line.trim()
            // 排除参数绑定行和噪声行
            if (!trimmed.startsWith("binding parameter", ignoreCase = true) &&
                BARE_SQL_PATTERN.containsMatchIn(trimmed)
            ) {
                return trimmed
            }
        }

        return null
    }

    /**
     * 尝试从行中提取参数绑定信息。
     * 格式：`binding parameter [N] as [TYPE] - [VALUE]`
     * @return LogSqlParam 或 null（非参数行）。
     */
    private fun extractBindingParam(line: String): LogSqlParam? {
        val match = if (mixedPrefix) {
            // 宽容模式：在行中搜索参数模式
            BINDING_PARAM_PATTERN.find(line)
        } else {
            // 严格模式：行首精确匹配
            BINDING_PARAM_PATTERN.matchEntire(line.trim())
        } ?: return null

        val type = match.groupValues[2].ifEmpty { null }
        val rawValue = match.groupValues[3]

        // Hibernate 输出 [null] 表示 null 字面量
        if (rawValue.equals("null", ignoreCase = true)) {
            return LogSqlParam(null, type)
        }

        // [] 表示空字符串（非 null）
        return LogSqlParam(rawValue, type)
    }

    /**
     * 提取关键字后的文本（用于显式前缀匹配）。
     */
    private fun extractAfterKeyword(line: String, keyword: String): String? {
        if (keyword.isEmpty()) return null
        val idx = if (mixedPrefix) {
            line.indexOf(keyword)
        } else {
            // 严格模式：行首精确前缀匹配（对齐 MyBatisLogParser 行为）
            if (line.startsWith(keyword)) 0 else -1
        }
        if (idx < 0) return null
        val text = line.substring(idx + keyword.length).trim()
        return text.ifEmpty { null }
    }

    /**
     * 提取注释后的 SQL 文本。
     * 格式：`/* load com.example.User */ select ...`
     */
    private fun extractAfterComment(line: String): String? {
        val match = COMMENT_PREFIX_PATTERN.find(line) ?: return null
        val afterComment = match.groupValues[1].trim()
        if (afterComment.isEmpty()) return null
        // 注释后的文本必须是 SQL 语句
        if (!looksLikeSql(afterComment)) return null
        return afterComment
    }

    /** 文本是否以 SQL 关键字开头。 */
    private fun looksLikeSql(text: String): Boolean {
        val upper = text.trimStart().uppercase()
        return upper.startsWith("SELECT") || upper.startsWith("INSERT") ||
            upper.startsWith("UPDATE") || upper.startsWith("DELETE") ||
            upper.startsWith("MERGE") || upper.startsWith("WITH")
    }

    companion object {
        const val DEFAULT_SQL_PREFIX = "Hibernate:"
        const val DEFAULT_PARAM_PREFIX = "binding parameter"
        const val DEFAULT_MAX_PARSE_LENGTH = 20000

        /**
         * 注释前缀模式：`/* load com.example.User */ select ...`
         * 组 1 = 注释后的 SQL 文本。
         */
        private val COMMENT_PREFIX_PATTERN = Regex(
            """^\s*/\*.*?\*/\s*(.*)$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 纯 SQL 行模式：行首即 SQL 关键字。
         */
        private val BARE_SQL_PATTERN = Regex(
            """^(select|insert|update|delete|merge|with)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 参数绑定行模式：`binding parameter [N] as [TYPE] - [VALUE]`
         * 组 1 = 位置索引（未使用，保留用于调试），组 2 = JDBC 类型，组 3 = 值。
         *
         * 注意：值部分使用 `(.*)` 贪婪匹配，支持值中包含 `]` 字符
         * （如 `binding parameter [1] as [VARCHAR] - [a]b]`）。
         */
        private val BINDING_PARAM_PATTERN = Regex(
            """binding parameter\s+\[(\d+)\]\s+as\s+\[(\w+)\]\s+-\s+\[(.*)\]""",
            RegexOption.IGNORE_CASE,
        )
    }
}
