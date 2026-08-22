package cn.song.mybatisplus.visualizer.log

/**
 * SQL 表名提取器（O-1 Phase A，纯逻辑，可独立单测）：从 SQL 文本中提取第一个表名。
 *
 * 策略（性能红线 < 1ms）：
 *  - 正则匹配 `FROM`/`UPDATE`/`INTO`/`DELETE FROM` 后的第一个标识符
 *  - 去除反引号与 schema 前缀（`schema.table` → `table`）
 *  - 子查询不干扰（取首个 FROM 后的表名——即外层主表）
 *
 * 仅用于日志 ↔ 源码关联的粗略匹配，不做完整 SQL 解析。
 */
object SqlTableExtractor {

    /**
     * 匹配 FROM / UPDATE / INTO / DELETE FROM 后的第一个表名。
     * 结构：`[schema.]table` — 可选 schema 前缀（非捕获），捕获表名本体。
     */
    private val TABLE_REGEX = Regex(
        """\b(?:from|update|into|delete\s+from)\s+[`]?(?:\w+\.)?(\w+)[`]?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 从 SQL 中提取第一个表名。
     *
     * @param sql SQL 文本
     * @return 表名（小写无反引号，schema 前缀已去除）；无法提取返回 null
     */
    fun extractFirst(sql: String): String? {
        if (sql.isBlank()) return null
        val match = TABLE_REGEX.find(sql) ?: return null
        return match.groupValues[1].lowercase()
    }
}
