package cn.song.mybatisplus.visualizer.share.format

import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer

/**
 * 可执行 SQL 格式化器（F-8 Phase C）。
 *
 * 复用 [ParameterReplacer.buildExecutableSql] 将 `?` 替换为带类型处理的字面值，
 * 额外提供 MR/PR 评论模板（Code Review 场景贴可执行 SQL + 来源）。
 */
object ExecutableSqlFormatter {

    /**
     * 生成单行可执行 SQL（替换 `?` 为字面值）。
     *
     * @param sql        含 `?` 的 SQL
     * @param sources    参数来源列表（与 `?` 按序对应）
     * @param paramValues 参数值映射（source → 值）
     * @param hints      回填提示（可空，走启发式）
     * @return 可执行 SQL
     */
    fun format(
        sql: String,
        sources: List<String>,
        paramValues: Map<String, String>,
        hints: List<ParamHint> = emptyList(),
    ): String = ParameterReplacer.buildExecutableSql(sql, sources, paramValues, hints)

    /**
     * 生成 MR/PR 评论格式（可执行 SQL + 来源前缀）。
     *
     * 模板：
     * ```
     * [SQL Review] UserService.java:42
     * ```sql
     * SELECT id, name FROM user WHERE name = 'zhangsan'
     * ```
     * ```
     *
     * @param sql        含 `?` 的 SQL
     * @param sources    参数来源列表
     * @param paramValues 参数值映射
     * @param source     来源位置（文件名:行号，可 null）
     * @param prefix     标题前缀（默认 `[SQL Review]`）
     * @param hints      回填提示
     * @return MR/PR 评论文本
     */
    fun formatAsMrComment(
        sql: String,
        sources: List<String>,
        paramValues: Map<String, String>,
        source: String? = null,
        prefix: String = "[SQL Review]",
        hints: List<ParamHint> = emptyList(),
    ): String {
        val executable = format(sql, sources, paramValues, hints)
        val sb = StringBuilder(256)
        sb.append(prefix)
        source?.let { sb.append(" ").append(it) }
        sb.append("\n```sql\n")
        sb.append(executable.trimEnd())
        sb.append("\n```")
        return sb.toString()
    }
}
