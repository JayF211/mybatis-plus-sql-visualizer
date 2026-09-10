package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.sql.ParamHint

/**
 * 日志参数还原器（T-M31-02）：把 `?` 占位符按 [LogSqlParam] 顺序替换为可执行 SQL 值。
 *
 * 引号规则（对齐 MyBatis 参数绑定语义与既有 ParameterReplacer 的引号判定经验）：
 * - 数值/布尔类型标注（Long/Integer/Double/Boolean 等）→ 原样
 * - String/时间/其他类型标注 → 引号包裹 + 内部 `'` 转义为 `''`（标准 SQL 字符串转义）
 * - null 字面量 → `NULL`
 * - 无类型标注（旧版/第三方日志）→ 启发式：数值（含科学计数）与 true/false 原样，其余引号
 *
 * 占位符数量与参数数量不匹配：多出的参数忽略，缺少的参数保留 `?`（面板警示提示）。
 */
class LogSqlRestorer {

    /** 按序替换 SQL 中 `?` 占位符；无参数时原样返回。 */
    fun restore(sql: String, params: List<LogSqlParam>): String {
        if (params.isEmpty() || sql.isEmpty()) return sql
        val positions = placeholderPositions(sql)
        if (positions.isEmpty()) return sql
        val sb = StringBuilder(sql.length + params.size * 8)
        var prev = 0
        var paramIdx = 0
        for (pos in positions) {
            if (paramIdx >= params.size) break // 参数多于占位符：忽略剩余
            sb.append(sql, prev, pos).append(renderParam(params[paramIdx]))
            prev = pos + 1
            paramIdx++
        }
        sb.append(sql, prev, sql.length)
        return sb.toString()
    }

    /** 单个参数渲染为 SQL 字面量（测试直接断言用）。 */
    fun renderParam(param: LogSqlParam): String {
        val value = param.value ?: return NULL_SQL // null 字面量（value/type 均 null）
        val type = param.type ?: return renderUntyped(value)
        return if (type in PLAIN_TYPES) value else quote(value)
    }

    private fun renderUntyped(value: String): String =
        if (value.matches(NUMERIC_REGEX) || value == TRUE || value == FALSE) value else quote(value)

    private fun quote(value: String): String = "'" + value.replace("'", "''") + "'"

    companion object {
        private const val NULL_SQL = "NULL"
        private const val TRUE = "true"
        private const val FALSE = "false"

        /**
         * 原样直出（数值/布尔）；其余类型标注一律引号（String/时间/枚举/byte[] 等）。
         *
         * 包含两类类型名：
         * - Java 类型（MyBatis 日志）：Long, Integer, String 等
         * - JDBC 类型（Hibernate 日志）：BIGINT, INTEGER, VARCHAR 等
         */
        internal val PLAIN_TYPES = setOf(
            // Java 类型（MyBatis 日志）
            "Long", "Integer", "Short", "Byte", "BigDecimal", "BigInteger", "Double", "Float", "Number",
            "long", "int", "short", "byte", "double", "float",
            "Boolean", "boolean",
            // JDBC 类型（Hibernate 日志）—— 数值/布尔型原样直出
            "BIGINT", "INTEGER", "SMALLINT", "TINYINT", "FLOAT", "DOUBLE", "NUMERIC", "DECIMAL",
            "BIT", "BOOLEAN",
            "REAL",
            // Java 全限定类型（Hibernate 偶尔输出）
            "java.lang.Long", "java.lang.Integer", "java.lang.Short", "java.lang.Byte",
            "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
            "java.math.BigDecimal", "java.math.BigInteger",
        )

        /** 数值字面量（含小数与科学计数，如 `1.0E10`）。 */
        private val NUMERIC_REGEX = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")
    }
}

/**
 * 日志参数 → 回填提示（T-M42）：数值/布尔原样直出（[LogSqlRestorer.PLAIN_TYPES]），
 * 其余类型标注引号（对齐 [LogSqlRestorer] 还原语义）；无类型标注走面板启发式（null）。
 * 供 [LogSqlEntry.toRecognizedPreview] 识别参数时生成参数行回填提示。
 */
internal fun LogSqlParam.toHint(): ParamHint = when {
    type == null -> ParamHint()
    type in LogSqlRestorer.PLAIN_TYPES -> ParamHint(quote = false)
    else -> ParamHint(quote = true)
}

/**
 * string-aware `?` 占位符定位（T-HibDualCapture）：单引号字符串字面量（`''` 转义）与
 * `--` 行注释、块注释内的 `?` 不算占位符——中文问句、PG `?|` 操作符不再被
 * 误计数/误回填。还原（[LogSqlRestorer.restore]）与预览识别计数共用同一口径，避免错位。
 * 双引号标识符内的 `?` 不在保护范围（标准 SQL 标识符不含 `?`，误伤面可忽略）。
 */
internal fun placeholderPositions(sql: String): List<Int> {
    val positions = mutableListOf<Int>()
    var inString = false
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        if (inString) {
            if (c == '\'') {
                if (i + 1 < sql.length && sql[i + 1] == '\'') {
                    i += 2
                    continue
                }
                inString = false
            }
        } else {
            when {
                c == '\'' -> inString = true
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    i++
                }
                c == '?' -> positions += i
            }
        }
        i++
    }
    return positions
}
