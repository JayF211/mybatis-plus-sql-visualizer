package cn.song.mybatisplus.visualizer.sql

/**
 * 参数替换器（F-02）：处理 SQL 中残留的 `?` 占位符与参数对照表。
 *
 * 说明：绝大多数参数值在 psi 层已通过变量追踪（M2-01）内联进 SQL；
 * 残留的 `?` 表示运行时才能确定的值（如方法参数），静态插件无法取得实际值，
 * 因此提供两种兜底：
 *  - 来源注释模式：[annotate] 将 `?` 标注其来源（如 `? /*deviceId*/`）
 *  - 对照表：[buildParamTable] 生成 `-- 参数对照: [deviceId=?, stationId=?]`
 */
object ParameterReplacer {

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
}
