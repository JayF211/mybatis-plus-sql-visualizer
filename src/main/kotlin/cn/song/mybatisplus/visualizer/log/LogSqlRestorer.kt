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
        val sb = StringBuilder(sql)
        var searchFrom = 0
        var paramIdx = 0
        while (paramIdx < params.size) {
            val placeholder = sb.indexOf('?', searchFrom)
            if (placeholder < 0) break // 参数多于占位符：忽略剩余
            val rendered = renderParam(params[paramIdx])
            sb.replace(placeholder, placeholder + 1, rendered)
            searchFrom = placeholder + rendered.length
            paramIdx++
        }
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

        /** 原样直出（数值/布尔）；其余类型标注一律引号（String/时间/枚举/byte[] 等）。 */
        internal val PLAIN_TYPES = setOf(
            "Long", "Integer", "Short", "Byte", "BigDecimal", "BigInteger", "Double", "Float", "Number",
            "long", "int", "short", "byte", "double", "float",
            "Boolean", "boolean",
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
