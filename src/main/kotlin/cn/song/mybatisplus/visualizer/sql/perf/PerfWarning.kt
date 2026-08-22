package cn.song.mybatisplus.visualizer.sql.perf

/**
 * 性能警告严重度（F-2 SQL 性能分析）。
 */
enum class PerfSeverity { ERROR, WARN, INFO }

/**
 * 单条性能警告（F-2 SQL 性能分析）。
 *
 * @param ruleId      规则 ID（如 `missing_where`、`leading_wildcard_like`）
 * @param severity    严重度（ERROR / WARN / INFO）
 * @param messageKey  i18n 消息键（面板渲染时取本地化文本）
 * @param messageArgs i18n 参数（如列名、表名等）
 * @param line        警告所在行号（1-based，未知为 -1）
 */
data class PerfWarning(
    val ruleId: String,
    val severity: PerfSeverity,
    val messageKey: String,
    val messageArgs: List<String> = emptyList(),
    val line: Int = -1,
)
