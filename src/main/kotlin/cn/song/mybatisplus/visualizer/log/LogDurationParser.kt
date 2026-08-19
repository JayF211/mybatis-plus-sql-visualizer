package cn.song.mybatisplus.visualizer.log

/**
 * SQL 耗时解析器（P-06，纯逻辑，可独立单测）：尝试从条目中解析耗时（毫秒）。
 *
 * 支持格式：
 * - **p6spy**：`1723456789012|123|statement|select ...`（`|` 分隔第二段为毫秒）
 * - **druid**：rawSql / restoredSql 中含 `executeMillis:456` 或 `"executeMillis":456`
 *
 * 注意：当前 [LogSqlEntry] 不含专用耗时字段；若日志格式不含耗时行，本模块返回 null，
 * 排序按钮隐藏（降级）。后续可在桥层扩展 [LogSqlEntry] 增加可选 `durationMs` 字段。
 */
object LogDurationParser {

    /** p6spy 格式正则：`|123|statement|` 或 `|123|result|`（第二段为毫秒整数）。 */
    private val P6SPY_REGEX = Regex("""\|(\d{1,})\|""")

    /** druid 格式正则：`executeMillis:456` 或 `"executeMillis":456`。 */
    private val DRUID_REGEX = Regex("""(?i)executeMillis["']?\s*[:=]\s*(\d+)""")

    /**
     * 尝试从条目中解析耗时（毫秒）。
     *
     * 解析顺序：先检测 p6spy 格式（rawSql 中含 `|` 分隔耗时），再检测 druid 格式。
     * 都不命中返回 null。
     *
     * @param entry 日志 SQL 条目
     * @return 耗时毫秒；无法解析返回 null
     */
    fun parse(entry: LogSqlEntry): Long? {
        // 优先：p6spy 格式（rawSql 中含 `时间戳|毫秒|statement|SQL`）
        val raw = entry.rawSql
        if (raw.contains('|')) {
            val matches = P6SPY_REGEX.findAll(raw).toList()
            // p6spy 格式：`1723456789012|123|statement|SQL`——第二段为耗时
            if (matches.size >= 2) {
                val ms = matches[1].groupValues[1].toLongOrNull()
                if (ms != null && ms >= 0) return ms
            }
            // fallback：第一段如果是小数字（毫秒）
            if (matches.isNotEmpty()) {
                val firstMs = matches[0].groupValues[1].toLongOrNull()
                if (firstMs != null && firstMs >= 0 && firstMs < 100_000_000L) return firstMs
            }
        }
        // 次选：druid 格式
        val druidMatch = DRUID_REGEX.find(raw) ?: DRUID_REGEX.find(entry.restoredSql)
        if (druidMatch != null) {
            val ms = druidMatch.groupValues[1].toLongOrNull()
            if (ms != null && ms >= 0) return ms
        }
        return null
    }

    /**
     * 检测条目是否含耗时信息（用于决定是否显示排序按钮）。
     *
     * @param entry 日志 SQL 条目
     * @return true = 含耗时信息
     */
    fun hasDurationInfo(entry: LogSqlEntry): Boolean = parse(entry) != null
}
