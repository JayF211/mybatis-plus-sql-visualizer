package cn.song.mybatisplus.visualizer.log

/**
 * Hibernate/JPA 控制台日志解析器。
 *
 * 支持的日志形态：
 * - SQL 行（Hibernate: 前缀）：`Hibernate: select ...`（同行或独占一行等待下一行）
 * - SQL 行（注释前缀）：块注释 load com.example.User 后跟 select ...（同行或跨行注释块）
 * - SQL 行（纯 SQL，[recognizeBareSql]=true 时）：行首即 SELECT/INSERT/UPDATE/DELETE
 * - 参数行：`binding parameter [1] as [BIGINT] - [1]`
 *
 * 三态状态机：IDLE、COLLECTING、emit。
 * - IDLE：等待 SQL 行（Hibernate: / 注释块 / 纯 SQL），检测到后切换到 COLLECTING。
 * - COLLECTING：累积多行 SQL（注释块跨行 + SQL 续行），遇到新语句前缀或参数行时闭合。
 * - emit：closePending() 从 multilineBuffer 取完整 SQL 构建 LogSqlEntry。
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

    // ----- multiline 累积状态 -----
    private var collecting = false
    private var inComment = false
    private val multilineBuffer = StringBuilder()

    /** 喂入一行控制台输出（任意线程，内部无共享可变状态——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        if (collecting) {
            feedCollecting(line)
        } else {
            feedIdle(line)
        }
    }

    // ---------- IDLE 状态（等待 SQL 行） ----------

    private fun feedIdle(line: String) {
        // 1. Hibernate: 前缀（含同行 SQL）
        if (handleHibernatePrefix(line)) return

        // 2. Hibernate: 独占一行（remainder 为空）→ 进入等待态
        if (isPrefixOnly(line, sqlPrefix)) {
            closePending()
            collecting = true
            pendingParams.clear()
            return
        }

        // 3. 块注释开头
        if (line.trimStart().startsWith("/*")) {
            val (unclosed, afterComment) = hasUnclosedComment(line)
            closePending()
            collecting = true
            pendingParams.clear()
            if (unclosed) {
                inComment = true
            } else {
                if (afterComment.isNotEmpty() && looksLikeSql(afterComment)) {
                    multilineBuffer.append(afterComment)
                } else {
                    collecting = false
                }
            }
            return
        }

        // 4. 纯 SQL 行（recognizeBareSql=true 时）
        if (recognizeBareSql) {
            val trimmed = line.trim()
            if (!trimmed.startsWith(paramPrefix, ignoreCase = true) &&
                BARE_SQL_PATTERN.containsMatchIn(trimmed)
            ) {
                closePending()
                collecting = true
                multilineBuffer.append(trimmed)
                pendingParams.clear()
                return
            }
        }

        // 5. 参数绑定行
        val param = extractBindingParam(line)
        if (param != null) {
            if (pendingSql == null) return // 无 SQL 上下文，忽略
            pendingParams += param
            return
        }

        // 6. 噪声行（忽略）
    }

    // ---------- COLLECTING 状态（累积多行 SQL） ----------

    private fun feedCollecting(line: String) {
        if (inComment) {
            // 等待注释块结束符
            val endIdx = line.lastIndexOf("*/")
            if (endIdx >= 0) {
                inComment = false
                val afterComment = line.substring(endIdx + 2).trim()
                if (afterComment.isNotEmpty() && looksLikeSql(afterComment)) {
                    if (multilineBuffer.length + 1 + afterComment.length <= maxParseLength) {
                        multilineBuffer.append(" ").append(afterComment)
                    }
                }
            }
            return
        }

        // 1. 新 Hibernate: 前缀（含同行 SQL）→ 闭合当前条目，开启新收集
        if (handleHibernatePrefix(line)) return

        // 1b. Hibernate: 独占一行 → 闭合当前条目，开启新收集（handleHibernatePrefix 不处理空文本）
        if (isPrefixOnly(line, sqlPrefix)) {
            closePending()
            collecting = true
            inComment = false
            multilineBuffer.clear()
            pendingParams.clear()
            return
        }

        // 2. 新块注释开头 → 闭合当前条目，开启新收集
        if (line.trimStart().startsWith("/*")) {
            val (unclosed, afterComment) = hasUnclosedComment(line)
            closePending()
            collecting = true
            pendingParams.clear()
            if (unclosed) {
                inComment = true
            } else {
                if (afterComment.isNotEmpty() && looksLikeSql(afterComment)) {
                    multilineBuffer.append(afterComment)
                } else {
                    collecting = false
                }
            }
            return
        }

        // 3. 参数绑定行 → 追加参数（保持 COLLECTING 以支持多参数）
        val param = extractBindingParam(line)
        if (param != null) {
            pendingParams += param
            return
        }

        // 4. 已有参数后遇到非 SQL 行 → 闭合当前条目，按 IDLE 处理该行
        if (pendingParams.isNotEmpty()) {
            closePending()
            feedIdle(line)
            return
        }

        // 5. MyBatis 行或其他框架前缀 → 忽略（不属于 Hibernate SQL 续行，保持 COLLECTING 等待参数）
        // 覆盖三种形态：MP 前缀（==>）、原生（Preparing:/Parameters:）、日志框架前缀（时间戳+类名）
        if (isMybatisLogLine(line) || isLogFrameworkLine(line)) {
            return
        }

        // 6. 续行 → 追加到 buffer
        if (multilineBuffer.length < maxParseLength) {
            multilineBuffer.append(" ").append(line.trim())
        }
    }

    /** 闭合当前未完成条目（新语句到来 / 面板刷新时调用）。 */
    fun closePending(): LogSqlEntry? {
        val sql = when {
            collecting -> {
                val s = multilineBuffer.toString().trim().take(maxParseLength).ifEmpty { null }
                collecting = false
                inComment = false
                multilineBuffer.clear()
                s
            }
            else -> pendingSql
        } ?: return null
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
     * 处理 Hibernate: 前缀（含同行 SQL 或同行块注释）。
     *
     * 从 [Hibernate:] 之后的文本中进一步剥离块注释前缀，
     * 如果注释未闭合则进入 inComment 态。
     *
     * @return true 如果行包含 Hibernate: 前缀并已处理完毕。
     */
    private fun handleHibernatePrefix(line: String): Boolean {
        val afterKeyword = extractAfterKeyword(line, sqlPrefix)
        if (afterKeyword == null) return false

        if (afterKeyword.trimStart().startsWith("/*")) {
            // Hibernate: 后紧跟块注释（同行闭合或跨行）
            val (unclosed, afterComment) = hasUnclosedComment(afterKeyword)
            closePending()
            collecting = true
            pendingParams.clear()
            if (unclosed) {
                inComment = true
            } else {
                if (afterComment.isNotEmpty() && looksLikeSql(afterComment)) {
                    multilineBuffer.append(afterComment)
                } else {
                    collecting = false
                }
            }
        } else {
            closePending()
            collecting = true
            multilineBuffer.append(afterKeyword)
            pendingParams.clear()
        }
        return true
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
     * 行仅含前缀关键字（remainder 为空/纯空白）——分行形态信号。
     * 与 [MyBatisLogParser] 同名方法对齐，用于 Hibernate: 独占一行时进入等待态。
     */
    private fun isPrefixOnly(line: String, keyword: String): Boolean {
        if (keyword.isEmpty()) return false
        val idx = if (mixedPrefix) {
            line.indexOf(keyword)
        } else {
            if (line.startsWith(keyword)) 0 else -1
        }
        if (idx < 0) return false
        return line.substring(idx + keyword.length).trim().isEmpty()
    }

    /**
     * 检测行中块注释的状态。
     *
     * 返回值：
     * - Pair(true, "")：行含注释开始符但无同行结束符（未闭合）
     * - Pair(false, afterComment)：注释同行闭合，afterComment 为注释后的文本
     * - Pair(false, "")：行中无注释开始符
     */
    private fun hasUnclosedComment(line: String): Pair<Boolean, String> {
        val start = line.indexOf("/*")
        if (start < 0) return Pair(false, "")
        val end = line.lastIndexOf("*/")
        if (end < start + 2) return Pair(true, "")
        val afterComment = line.substring(end + 2).trim()
        return Pair(false, afterComment)
    }

    /** 文本是否以 SQL 关键字开头。 */
    private fun looksLikeSql(text: String): Boolean {
        val upper = text.trimStart().uppercase()
        return upper.startsWith("SELECT") || upper.startsWith("INSERT") ||
            upper.startsWith("UPDATE") || upper.startsWith("DELETE") ||
            upper.startsWith("MERGE") || upper.startsWith("WITH")
    }

    /**
     * 是否为 MyBatis/MyBatis-Plus 日志行（MP 前缀 / 原生 Preparing: / Parameters:）。
     * 多模块并发时，MyBatis 行可能混入 Hibernate 控制台，必须在 COLLECTING 态忽略。
     */
    private fun isMybatisLogLine(line: String): Boolean {
        if (MYBATIS_LINE_PATTERN.containsMatchIn(line)) return true
        // 原生格式：行中任意位置含 Preparing: 或 Parameters:
        return line.contains("Preparing:") || line.contains("Parameters:")
    }

    /**
     * 是否为日志框架前缀行（时间戳 + 日志级别 + 线程 + 类名），如 Spring Boot 默认格式：
     * `2026-08-22 00:34:11.251 DEBUG 37792 --- [nio-8083-exec-5] org.hibernate.SQL :`
     */
    private fun isLogFrameworkLine(line: String): Boolean = LOG_FRAMEWORK_PATTERN.containsMatchIn(line)

    companion object {
        const val DEFAULT_SQL_PREFIX = "Hibernate:"
        const val DEFAULT_PARAM_PREFIX = "binding parameter"
        const val DEFAULT_MAX_PARSE_LENGTH = 20000

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

        /**
         * MyBatis/MyBatis-Plus 日志行模式：
         * - MP 格式：`==>  Preparing:` / `==> Parameters:`
         * - 日志框架前缀：`2026-08-22 ... org.hibernate.SQL :`（任意日志行含类名）
         */
        private val MYBATIS_LINE_PATTERN = Regex(
            """==>\s+(Preparing|Parameters):""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 日志框架前缀模式：时间戳开头 + 日志级别（DEBUG/TRACE/INFO/WARN/ERROR）。
         * 示例：`2026-08-22 00:34:11.251 DEBUG 37792 --- [nio-8083-exec-5] org.hibernate.SQL :`
         */
        private val LOG_FRAMEWORK_PATTERN = Regex(
            """^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(DEBUG|TRACE|INFO|WARN|ERROR)""",
        )
    }
}
