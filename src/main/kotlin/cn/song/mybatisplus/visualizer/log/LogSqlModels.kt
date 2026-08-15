package cn.song.mybatisplus.visualizer.log

/**
 * 日志 SQL 条目模型（T-M31）：一条 `Preparing:` 语句 + 其 `Parameters:` 参数列表。
 *
 * 来源：MyBatis 控制台日志（MP 前缀 `==>  Preparing:` / 原生 `Preparing:`），
 * 经 [MyBatisLogParser] 配对、[LogSqlRestorer] 还原后进入工具窗口面板。
 */
data class LogSqlEntry(
    /** Preparing 原文（未替换占位符）。 */
    val rawSql: String,
    /** 还原后的可执行 SQL（参数已替换；无法替换的 `?` 保留）。 */
    val restoredSql: String,
    /** 参数列表（与 SQL 中 `?` 按序对应）。 */
    val params: List<LogSqlParam>,
    /** 捕获时间戳。 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 摘要（列表展示用，单行截断）。 */
    val summary: String = restoredSql.take(MAX_SUMMARY_LENGTH),
) {
    companion object {
        private const val MAX_SUMMARY_LENGTH = 160
    }
}

/**
 * 单个日志参数：值 + 类型标注。
 *
 * @param value 参数值文本（MyBatis 打印形态，如 `abc`、`1`、`2025-01-01 12:00:00.0`）；null 表示 `null` 字面量
 * @param type 类型标注（`(Long)` 中的 Long）；null 表示无类型标注（如 `null` 字面量或旧版日志）
 */
data class LogSqlParam(
    val value: String?,
    val type: String?,
)
