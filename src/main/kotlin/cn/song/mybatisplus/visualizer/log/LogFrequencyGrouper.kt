package cn.song.mybatisplus.visualizer.log

/**
 * 高频折叠分组条目（P-06）：同一 SQL 模板出现次数 >= 阈值时合并为一条。
 *
 * @property template 模板条目（展示用，取首次出现的条目）
 * @property count 出现次数
 * @property entries 全部相同模板条目（含各自时间戳；正序）
 * @property expanded 展开态（UI 状态；true=展开显示全部，false=折叠仅显示模板）
 */
data class GroupedEntry(
    val template: LogSqlEntry,
    val count: Int,
    val entries: List<LogSqlEntry>,
    var expanded: Boolean = false,
)

/**
 * SQL 高频折叠分组器（P-06，纯逻辑，可独立单测）：按 SQL 模板指纹分组，
 * 出现次数 >= 阈值的合并为 [GroupedEntry]。
 *
 * 分组规则：
 * - 指纹 = [LogSqlEntry.restoredSql] 去除多余空白（`\\s+` → 单空格）+ 转小写。
 * - 相同指纹出现 >= threshold → 合并为 [GroupedEntry]。
 * - 合并后仅保留第一条位置，后续相同模板条目隐藏（展开后显示）。
 * - 不同时间戳的相同模板：保留各自时间戳（展开后可见）。
 */
object LogFrequencyGrouper {

    /** 默认高频阈值（同一模板出现次数 >= 该值即合并）。 */
    const val DEFAULT_THRESHOLD = 3

    /**
     * 按 SQL 模板指纹分组，出现次数 >= [threshold] 的合并为 [GroupedEntry]。
     *
     * @param entries 原始条目（正序，最早在前）
     * @param threshold 高频阈值（默认 [DEFAULT_THRESHOLD]；>= 2 有意义）
     * @return 分组后列表（保持原始顺序，高频组合并为一条；低频条目 count=1）
     */
    fun group(entries: List<LogSqlEntry>, threshold: Int = DEFAULT_THRESHOLD): List<GroupedEntry> {
        if (entries.isEmpty()) return emptyList()

        // 第一遍：按指纹计数
        val countByFingerprint = LinkedHashMap<String, Int>()
        for (entry in entries) {
            val fp = fingerprint(entry.restoredSql)
            countByFingerprint[fp] = (countByFingerprint[fp] ?: 0) + 1
        }

        // 第二遍：按首次出现顺序构建分组（高频合并，低频透传）
        val result = mutableListOf<GroupedEntry>()
        val consumed = mutableSetOf<String>() // 已处理指纹
        for (entry in entries) {
            val fp = fingerprint(entry.restoredSql)
            if (fp in consumed) continue // 已处理，跳过
            consumed += fp
            val count = countByFingerprint[fp] ?: 1
            if (count >= threshold) {
                // 高频：收集全部相同指纹条目
                val sameEntries = entries.filter { fingerprint(it.restoredSql) == fp }
                result += GroupedEntry(
                    template = sameEntries.first(),
                    count = sameEntries.size,
                    entries = sameEntries,
                )
            } else {
                // 低频：各条目独立透传
                val sameEntries = entries.filter { fingerprint(it.restoredSql) == fp }
                sameEntries.forEach { e ->
                    result += GroupedEntry(template = e, count = 1, entries = listOf(e))
                }
            }
        }
        return result
    }

    /**
     * 计算 SQL 模板指纹（归一化：去多余空白 + 小写）。
     *
     * 归一化后 `SELECT * FROM t` 与 `SELECT  *  FROM  t` 视为相同模板，
     * `select * from t` 与 `SELECT * FROM T` 同样视为相同（大小写不敏感）。
     *
     * @param sql 还原后的可执行 SQL
     * @return 归一化指纹字符串
     */
    fun fingerprint(sql: String): String =
        sql.trim().replace(Regex("\\s+"), " ").lowercase()
}
