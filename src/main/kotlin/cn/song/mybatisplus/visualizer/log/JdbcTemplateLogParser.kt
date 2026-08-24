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
 *
 * 不匹配的行（MyBatis/Hibernate/纯噪声）自然忽略。
 *
 * 参数按 `column index`（1-based）升序排列后回填 `?`。
 * 类型还原：`value class [java.lang.Integer]` → 去 `java.lang.` 前缀 → `Integer` → 查 [LogSqlRestorer.PLAIN_TYPES] 判定引号。
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

    /** 喂入一行控制台输出（任意线程，内部无共享可变状态——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        if (!enabled) return

        // 分支 1：SQL 行（有 [SQL] 文本）
        val sql = extractSqlFromLine(line)
        if (sql != null) {
            closePending()
            pendingSql = sql.take(maxParseLength)
            pendingParams.clear()
            return
        }

        // 分支 2：参数行
        val param = extractParamFromLine(line)
        if (param != null) {
            if (pendingSql == null) return // 无 SQL 上下文，忽略
            pendingParams += param
            return
        }

        // 分支 3：有 pendingSql 后遇到非参数非 SQL 行 → 闭合当前条目
        if (pendingSql != null && pendingParams.isNotEmpty()) {
            closePending()
        }
    }

    /** 闭合当前未完成条目（新语句到来 / 面板刷新时调用）。 */
    fun closePending(): LogSqlEntry? {
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
     * 尝试从行中提取 SQL 文本（有 `[SQL]` 的语句行）。
     * 匹配前缀：Executing prepared SQL statement/query/update、Executing SQL statement/query/update、
     *          Executing SQL batch update、Calling stored procedure。
     * 无 SQL 文本的行（如 `Executing prepared SQL query`）不匹配。
     */
    private fun extractSqlFromLine(line: String): String? {
        val match = SQL_LINE_PATTERN.find(line) ?: return null
        return match.groupValues[1].take(maxParseLength)
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

    /** 带索引的参数（column index + 值 + 映射后类型名）。 */
    private data class IndexedParam(
        val index: Int,
        val value: String?,
        val mappedType: String?,
    )

    companion object {
        const val DEFAULT_MAX_PARSE_LENGTH = 20000

        /**
         * SQL 行匹配正则：提取 `[SQL]` 文本。
         * 组 1 = SQL 文本。
         *
         * 匹配形态：
         * - `Executing prepared SQL statement [SQL]`
         * - `Executing prepared SQL update [SQL]`（罕见，update 通常无 SQL 文本）
         * - `Executing SQL query [SQL]`
         * - `Executing SQL update [SQL]`
         * - `Executing SQL statement [SQL]`
         * - `Executing SQL batch update [SQL]`
         * - `Calling stored procedure [SQL]`（无 Executing 前缀，Spring Line:1157）
         *
         * 不匹配：`Executing prepared SQL query`（无 `[SQL]`）
         */
        private val SQL_LINE_PATTERN = Regex(
            """(?:Executing\s+(?:prepared\s+SQL\s+(?:statement|update|query)|SQL\s+(?:statement|query|update)|SQL\s+batch\s+update)|Calling\s+stored\s+procedure)\s*\[(.*)\]""",
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
    }
}
