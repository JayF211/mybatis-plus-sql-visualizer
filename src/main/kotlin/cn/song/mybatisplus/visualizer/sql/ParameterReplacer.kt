package cn.song.mybatisplus.visualizer.sql

/**
 * 参数替换器（F-02）：处理 SQL 中残留的 `?` 占位符与参数对照表。
 *
 * 说明：绝大多数参数值在 psi 层已通过变量追踪（M2-01）内联进 SQL；
 * 残留的 `?` 表示运行时才能确定的值（如方法参数），静态插件无法取得实际值，
 * 因此提供两种兜底：
 *  - 来源注释模式：[annotate] 将 `?` 标注其来源（如 `? /*deviceId*/`）
 *  - 对照表：[buildParamTable] 生成 `-- 参数对照: [deviceId=?, stationId=?]`
 *  - 可执行 SQL：[buildExecutableSql] 将 `?` 替换为带类型处理的字面值（供复制到外部工具执行）
 */
object ParameterReplacer {

    /** 数值启发式正则（无 [ParamHint] 时判定是否加引号）。 */
    private val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")

    /**
     * 将 SQL 中的 `?` 按出现顺序标注来源注释。
     *
     * @param sql    含 `?` 的 SQL
     * @param params 参数来源列表（与 [GeneratedSql.unresolvedParams] 对应）
     * @return 标注后的 SQL（`?` 数量多于参数列表时，多余的 `?` 保持原样）
     */
    fun annotate(sql: String, params: List<String>): String {
        if (params.isEmpty()) return sql
        var index = 0
        val sb = StringBuilder(sql.length + params.size * 12)
        var i = 0
        while (i < sql.length) {
            val ch = sql[i]
            if (ch == '?' && index < params.size) {
                sb.append("? /*").append(params[index++]).append("*/")
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 生成参数对照表（供 UI 展示，如 `-- 参数对照: [deviceId=?, stationId=?]`）。
     *
     * @param params 未解析参数来源列表
     * @return 对照表文本；无参数时返回 null
     */
    fun buildParamTable(params: List<String>): String? {
        if (params.isEmpty()) return null
        val body = params.joinToString(", ") { "$it=?" }
        return "-- 参数对照: [$body]"
    }

    /**
     * 将 SQL 中的 `?` 占位符替换为带类型处理的字面值，生成可执行 SQL（供复制到 DBeaver/DataGrip 等工具直接执行）。
     *
     * 替换规则（与面板回填 [SqlPreviewPanel] 一致）：
     *  - 值未填写（空/空白）→ 保留 `?`
     *  - [ParamHint.quote] = true → 单引号包裹（String/日期列）
     *  - [ParamHint.quote] = false → 原样渲染（数值列）
     *  - [ParamHint.quote] = null → 数值启发式（纯数字原样，否则引号包裹）
     *  - 值中单引号转义为 `''`（防 SQL 注入，与面板回填一致）
     *
     * @param sql        含 `?` 的 SQL（`?` 顺序与 [sources] 一一对应）
     * @param sources    参数来源列表（与 SQL 中 `?` 按出现顺序对应）
     * @param paramValues 参数值映射（source → 用户输入值）
     * @param hints      与 [sources] 并行的回填提示（引号规则）；缺失走启发式
     * @return 替换后的可执行 SQL
     */
    fun buildExecutableSql(
        sql: String,
        sources: List<String>,
        paramValues: Map<String, String>,
        hints: List<ParamHint> = emptyList(),
    ): String {
        if (sources.isEmpty()) return sql
        val sb = StringBuilder(sql.length + 32)
        var paramIndex = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '?') {
                val source = sources.getOrNull(paramIndex)
                val value = source?.let { paramValues[it] }
                sb.append(renderValue(value, hints.getOrNull(paramIndex)))
                paramIndex++
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 单值渲染：按 [ParamHint.quote] 决定引号规则；值中单引号转义为 `''`。
     * 空值/空白 → 返回 `?`（保留占位符）。
     */
    private fun renderValue(value: String?, hint: ParamHint?): String {
        if (value.isNullOrBlank()) return "?"
        val escaped = value.replace("'", "''")
        return when {
            hint?.quote == true -> "'$escaped'"
            hint?.quote == false -> value.trim()
            else -> {
                val trimmed = value.trim()
                if (NUMBER_REGEX.matches(trimmed)) trimmed else "'$escaped'"
            }
        }
    }
}
