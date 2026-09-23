package cn.song.mybatisplus.visualizer.log

import java.util.UUID

/**
 * SQL 屏蔽规则（O-69 T-SqlSuppress）：一条「后续不再捕获」的语句形态。
 *
 * 持久化友好（照 [cn.song.mybatisplus.visualizer.history.SqlHistoryEntry] 形态）：
 * 全部字段为基本类型 / String，XmlSerializer 可直接序列化。
 *
 * @param id        唯一标识（空白时由 [SqlSuppressRuleManager.addRule] 自动分配 UUID）
 * @param pattern   匹配模式——**规范化文本**（`normalizeSqlWhitespace` 后），存储与展示口径，
 *                  人可读、XML 可查；运行时按其指纹进 [SqlSuppressRuleManager.suppressedKeys]
 * @param sampleSql 原始还原 SQL（右键动作捕获时的 [LogSqlEntry.restoredSql]），管理对话框高亮展示用
 * @param createdAt 创建时间戳（毫秒；管理对话框列表展示）
 */
data class SqlSuppressRule(
    var id: String = "",
    var pattern: String = "",
    var sampleSql: String = "",
    var createdAt: Long = 0L,
) {
    /** 分配唯一 id（仅当当前为空白时）。 */
    fun ensureId() {
        if (id.isBlank()) id = UUID.randomUUID().toString()
    }

    /** 列表展示用 pattern 摘要：首行、截断至 [MAX_PATTERN_SUMMARY_LEN] 字符。 */
    fun patternSummary(): String {
        val firstLine = pattern.trim().split("\n").firstOrNull()?.trim() ?: ""
        return if (firstLine.length <= MAX_PATTERN_SUMMARY_LEN) firstLine
        else firstLine.substring(0, MAX_PATTERN_SUMMARY_LEN) + "…"
    }

    companion object {
        /** 列表渲染的 pattern 摘要最大字符数。 */
        const val MAX_PATTERN_SUMMARY_LEN = 80
    }
}
