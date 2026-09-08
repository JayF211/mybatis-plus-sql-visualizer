package cn.song.mybatisplus.visualizer.log

/**
 * Spring JdbcTemplate 控制台日志解析器（M47）。
 *
 * 支持的日志形态（DEBUG=SQL 行，TRACE=参数行；两类来自不同类）：
 * - SQL 行（JdbcTemplate，有 SQL 文本）：`Executing prepared SQL statement [SELECT ...]`
 * - SQL 行（字符串重载）：`Executing SQL query [SELECT ...]` / `Executing SQL update [UPDATE ...]`
 * - SQL 行（存储过程）：`Calling stored procedure [{call ...}]`
 * - 参数行（StatementCreatorUtils）：
 *   `Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown`
 * - NamedParameterJdbcTemplate 单行格式：
 *   `sql = SELECT ... WHERE col = :param1 , parameters = {param1=0, param2=abc}`
 *
 * 不匹配的行（MyBatis/Hibernate/纯噪声）自然忽略。
 *
 * 参数按 `column index`（1-based）升序排列后回填 `?`。
 * 类型还原：`value class [java.lang.Integer]` → 去 `java.lang.` 前缀 → `Integer` → 查 [LogSqlRestorer.PLAIN_TYPES] 判定引号。
 * NamedParameter 格式：命名参数 `:paramName` 直接替换为 Map 中对应值（启发式判定引号）。
 *
 * 多行语句（T-JdbcMultiline）：`[...]` 内 SQL 跨行时（开括号行无闭合），首行进入 COLLECTING 态逐行累积，
 * string-aware 净括号计数（单引号字符串内 `[`/`]` 不计数，`''` 转义）归零即闭合；新语句行/参数行/超限
 * 强制闭合残句，`closePending()` 同样冲刷（LogToSqlPanel 粘贴末尾零改动受益）。
 *
 * [maxParseLength] 超长截断（防控制台大输出卡顿）。
 */
class JdbcTemplateLogParser(
    private val restorer: LogSqlRestorer = LogSqlRestorer(),

    /** 解析器启用开关（M47：设置页控制）。 */
    var enabled: Boolean = true,

    /** 行解析最大长度（超长截断，防控制台大输出卡顿）。 */
    var maxParseLength: Int = DEFAULT_MAX_PARSE_LENGTH,
) {

    private val entries = mutableListOf<LogSqlEntry>()
    private var pendingSql: String? = null
    private val pendingParams = mutableListOf<IndexedParam>()

    // ----- 多行收集态（T-JdbcMultiline：`[...]` 跨行语句首行进入，`]` 闭合/边界行/超限/closePending 离开） -----
    private var collecting = false
    private val multilineBuffer = StringBuilder()

    /** 未闭合 `[` 的净计数（`[` +1 / `]` -1，单引号字符串内不计数）。 */
    private var multilineNet = 0

    /** 单引号字符串态（跨行延续；`''` 为字符串内转义引号）。 */
    private var multilineInString = false

    /** 喂入一行控制台输出（任意线程，内部无共享可变状态——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        if (!enabled) return

        // 多行收集态：语句跨行累积中（T-JdbcMultiline）
        if (collecting) {
            if (looksLikeStatementBoundary(line)) {
                // 边界行（新语句/NamedParameter/参数行）：强制闭合残句并立即产出，
                // 防止后续行的参数误挂到残句上
                closePending()
                feedIdle(line)
            } else {
                appendCollecting(line)
            }
            return
        }
        feedIdle(line)
    }

    private fun feedIdle(line: String) {
        // 分支 1：NamedParameterJdbcTemplate 单行格式（sql = ... , parameters = {...}）
        val namedEntry = extractNamedParamEntry(line)
        if (namedEntry != null) {
            closePending()
            entries += namedEntry
            return
        }

        // 分支 2：SQL 行（动作短语 + `[` 文本）
        val action = SQL_ACTION_PATTERN.find(line)
        if (action != null) {
            val openIdx = line.indexOf('[', action.range.last + 1)
            if (openIdx >= 0 && line.substring(action.range.last + 1, openIdx).isBlank()) {
                // string-aware 净括号扫描（字符串内括号不计数）：同行闭合走快路径；
                // 未闭合进入多行收集——含 `[SELECT a[1] FROM t` 这类首个 `]` 并非消息终结符的
                // 形态（旧同行正则会把 "SELECT a[1" 误提取为完整语句）
                val probe = scanBrackets(line, openIdx + 1, net = 1, inString = false)
                closePending()
                pendingParams.clear()
                if (probe.closeIdx >= 0) {
                    pendingSql = line.substring(openIdx + 1, probe.closeIdx).take(maxParseLength)
                } else {
                    collecting = true
                    multilineBuffer.setLength(0)
                    multilineBuffer.append(line.substring(openIdx + 1))
                    multilineNet = probe.net
                    multilineInString = probe.inString
                }
                return
            }
            // 动作短语命中但无 `[`（如 `Executing prepared SQL query` 头行）→ 落到参数/噪声分支
        }

        // 分支 3：参数行
        val param = extractParamFromLine(line)
        if (param != null) {
            if (pendingSql == null) return // 无 SQL 上下文，忽略
            pendingParams += param
            return
        }

        // 分支 4：有 pendingSql 后遇到非参数非 SQL 行 → 闭合当前条目
        if (pendingSql != null && pendingParams.isNotEmpty()) {
            closePending()
        }
    }

    /** 闭合当前未完成条目（新语句到来 / 面板刷新 / 粘贴末尾时调用）。 */
    fun closePending(): LogSqlEntry? {
        if (collecting) {
            // 多行残句强制闭合（T-JdbcMultiline）——宁交未闭合残句，不整段丢弃
            val partial = multilineBuffer.toString()
            if (partial.isNotBlank()) {
                finishCollecting(partial)
            } else {
                collecting = false
                multilineBuffer.setLength(0)
                multilineNet = 0
                multilineInString = false
            }
        }
        val sql = pendingSql ?: return null
        pendingSql = null
        // 按 column index 升序排列（1-based → 0-based 由 restorer 按序处理）
        val sortedParams = pendingParams.sortedBy { it.index }
            .map { LogSqlParam(it.value, it.mappedType) }
        val entry = LogSqlEntry(
            rawSql = sql,
            restoredSql = restorer.restore(sql, sortedParams),
            params = sortedParams,
            source = SqlSource.JDBC,
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
     * 尝试从行中提取 NamedParameterJdbcTemplate 格式的完整条目（单行）。
     * 格式：`sql = <SQL> , parameters = {param1=val1, param2=val2}`
     * 命名参数 `:paramName` 直接替换为 Map 中对应值。
     */
    private fun extractNamedParamEntry(line: String): LogSqlEntry? {
        val match = NAMED_PARAM_PATTERN.find(line) ?: return null
        val sql = match.groupValues[1].trim().take(maxParseLength)
        val paramsText = match.groupValues[2].trim()
        val paramsMap = parseNamedParamsMap(paramsText)
        val restoredSql = replaceNamedParams(sql, paramsMap)
        // 收集 params 列表（按 :paramN 出现顺序）
        val params = mutableListOf<LogSqlParam>()
        NAMED_PARAM_REF.findAll(sql).forEach { refMatch ->
            val paramName = refMatch.groupValues[1]
            val value = paramsMap[paramName]
            params += LogSqlParam(value, null)
        }
        return LogSqlEntry(
            rawSql = sql,
            restoredSql = restoredSql,
            params = params,
            source = SqlSource.JDBC,
        )
    }

    /**
     * 解析 NamedParameter 格式的参数 Map 文本。
     * 格式：`param1=0, param2=abc`
     * 值可能含 `=` 或 `,`（如 JSON 字符串），按 `, paramName=` 边界切分。
     */
    private fun parseNamedParamsMap(paramsText: String): Map<String, String> {
        if (paramsText.isEmpty()) return emptyMap()
        val params = mutableMapOf<String, String>()
        // 按 `, key=` 边界切分（lookahead 保留 key）
        val entries = paramsText.split(Regex("""\s*,\s*(?=\w+\s*=)"""))
        for (entry in entries) {
            val eqIdx = entry.indexOf('=')
            if (eqIdx <= 0) continue
            val key = entry.substring(0, eqIdx).trim()
            val value = entry.substring(eqIdx + 1).trim()
            params[key] = value
        }
        return params
    }

    /**
     * 替换 SQL 中的命名参数为渲染后的字面量。
     * 无类型标注 → 启发式：数值/true/false 原样，其余引号包裹。
     */
    private fun replaceNamedParams(sql: String, params: Map<String, String>): String {
        var result = sql
        for ((name, value) in params) {
            val rendered = renderUntyped(value)
            result = result.replace(":$name", rendered)
        }
        return result
    }

    /**
     * 无类型标注的字面量渲染（启发式）。
     * 数值（含小数/科学计数）/ true/false → 原样；null → NULL；其余 → 引号包裹。
     */
    private fun renderUntyped(value: String?): String {
        if (value == null || value == "null") return "NULL"
        if (value.matches(NUMERIC_REGEX) || value == "true" || value == "false") return value
        return "'" + value.replace("'", "''") + "'"
    }

    /**
     * 尝试从行中提取参数信息。
     * 格式：`Setting SQL statement parameter value: column index N, parameter value [VALUE], value class [CLASS], SQL type TYPE`
     */
    private fun extractParamFromLine(line: String): IndexedParam? {
        val match = PARAM_LINE_PATTERN.find(line) ?: return null
        val columnIndex = match.groupValues[1].toIntOrNull() ?: return null
        val rawValue = match.groupValues[2]
        val className = match.groupValues[3]

        // null 字面量：parameter value [null], value class [null]
        if (rawValue == "null" && className == "null") {
            return IndexedParam(columnIndex, null, null)
        }

        val mappedType = mapClassToType(className)
        return IndexedParam(columnIndex, rawValue, mappedType)
    }

    /**
     * 将 Spring JdbcTemplate 输出的全限定 Java 类名映射到 PLAIN_TYPES 可识别的简名。
     *
     * - `java.lang.Integer` → `Integer`
     * - `java.math.BigDecimal` → `BigDecimal`
     * - `java.sql.Timestamp` → `Timestamp`（不在 PLAIN_TYPES → 引号包裹）
     * - 其他未知类名 → 原样返回（不在 PLAIN_TYPES → 引号包裹）
     */
    private fun mapClassToType(className: String): String? {
        if (className == "null") return null
        return className
            .removePrefix("java.lang.")
            .removePrefix("java.math.")
            .removePrefix("java.util.")
            .removePrefix("java.sql.")
            .removePrefix("java.time.")
    }

    // ---------- 多行收集态（T-JdbcMultiline） ----------

    /** COLLECTING 态边界判定：新语句行 / NamedParameter 行 / 参数行到达时强制闭合残句。 */
    private fun looksLikeStatementBoundary(line: String): Boolean =
        SQL_ACTION_PATTERN.containsMatchIn(line) ||
            NAMED_PARAM_PATTERN.containsMatchIn(line) ||
            PARAM_LINE_PATTERN.containsMatchIn(line)

    /** COLLECTING 态逐行累积；净括号计数归零即闭合（终结 `]` 前的文本即完整 SQL）。 */
    private fun appendCollecting(line: String) {
        if (multilineBuffer.length + line.length + 1 >= maxParseLength) {
            // 累积超限：强制闭合（截断语义与单行快路径一致），不吞后续语句
            closePending()
            return
        }
        val lineStart = multilineBuffer.length
        multilineBuffer.append('\n').append(line)
        val probe = scanBrackets(line, 0, multilineNet, multilineInString)
        multilineNet = probe.net
        multilineInString = probe.inString
        if (probe.closeIdx >= 0) {
            finishCollecting(multilineBuffer.substring(0, lineStart + 1 + probe.closeIdx))
        }
    }

    /** 闭合多行语句：保留内部换行、削首尾空白，回归与单行一致的 pendingSql 生命周期。 */
    private fun finishCollecting(sql: String) {
        collecting = false
        multilineBuffer.setLength(0)
        multilineNet = 0
        multilineInString = false
        pendingSql = sql.trim().take(maxParseLength)
    }

    /** 括号扫描结果：累计净计数、延续后的字符串态、净计数首次归零处的局部下标（未闭合为 -1）。 */
    private class BracketScan(val net: Int, val inString: Boolean, val closeIdx: Int)

    /**
     * string-aware 括号扫描（T-JdbcMultiline）：`[` +1 / `]` -1，单引号字符串内的括号不计数
     * （`''` 为字符串内转义引号，字符串态跨行延续）。消息终结 `]` 使净计数归零——
     * SQL 正文中的平衡/字符串内括号均不构成闭合。
     */
    private fun scanBrackets(text: String, from: Int, net: Int, inString: Boolean): BracketScan {
        var n = net
        var s = inString
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (s) {
                if (c == '\'') {
                    if (i + 1 < text.length && text[i + 1] == '\'') {
                        i += 2
                        continue
                    }
                    s = false
                }
            } else {
                when (c) {
                    '\'' -> s = true
                    '[' -> n++
                    ']' -> {
                        n--
                        if (n == 0) return BracketScan(n, s, i)
                    }
                }
            }
            i++
        }
        return BracketScan(n, s, -1)
    }

    /** 带索引的参数（column index + 值 + 映射后类型名）。 */
    private data class IndexedParam(
        val index: Int,
        val value: String?,
        val mappedType: String?,
    )

    companion object {
        const val DEFAULT_MAX_PARSE_LENGTH = 20000

        /**
         * SQL 行动作短语（T-JdbcMultiline 自旧「同行 `[...]` 正则」拆出）。
         * 配合 string-aware 括号扫描判定同行闭合（快路径）或跨行收集（慢路径）——
         * 旧式对 `[SELECT a[1] FROM t`（首个 `]` 是标识符括号、消息终结 `]` 在后续行）
         * 会把 "SELECT a[1" 误提取为完整语句。
         *
         * 匹配形态：
         * - `Executing prepared SQL statement [SQL]` / `update` / `query`
         * - `Executing SQL statement/query/update [SQL]`
         * - `Executing SQL batch update [SQL]`
         * - `Calling stored procedure [SQL]`（无 Executing 前缀）
         *
         * 不匹配：`Executing prepared SQL query`（无 `[SQL]` 的头行）
         */
        private val SQL_ACTION_PATTERN = Regex(
            """(?:Executing\s+(?:prepared\s+SQL\s+(?:statement|update|query)|SQL\s+(?:statement|query|update)|SQL\s+batch\s+update)|Calling\s+stored\s+procedure)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 参数行匹配正则。
         * 组 1 = column index，组 2 = parameter value，组 3 = value class，组 4 = SQL type。
         *
         * 值使用贪婪匹配到 `], value class` 前的最后一个 `]`，
         * 支持值本身含 `]` 字符。
         */
        private val PARAM_LINE_PATTERN = Regex(
            """Setting\s+SQL\s+statement\s+parameter\s+value:\s+column\s+index\s+(\d+),\s+parameter\s+value\s+\[(.*)],\s+value\s+class\s+\[(.*?)],\s+SQL\s+type\s+(.*)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * NamedParameterJdbcTemplate 单行格式正则。
         * 组 1 = SQL 文本，组 2 = 参数 Map 文本。
         *
         * 匹配形态：`sql = SELECT ... , parameters = {param1=0, param2=abc}`
         * SQL 使用非贪婪匹配到 ` , parameters =` 前的最后一个字符。
         */
        private val NAMED_PARAM_PATTERN = Regex(
            """sql\s*=\s*(.*?)\s*,\s*parameters\s*=\s*\{([^}]*)\}""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * SQL 中命名参数引用模式：`:paramName`（用于收集 params 列表）。
         */
        private val NAMED_PARAM_REF = Regex(""":(\w+)""")

        /** 数值字面量（含小数与科学计数）。 */
        private val NUMERIC_REGEX = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")
    }
}
