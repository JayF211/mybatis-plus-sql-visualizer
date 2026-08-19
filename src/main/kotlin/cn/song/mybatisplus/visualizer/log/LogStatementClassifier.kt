package cn.song.mybatisplus.visualizer.log

/**
 * SQL 语句类型分类器（P-06，纯逻辑，可独立单测）：按 restoredSql 首 token 分类。
 *
 * 分类规则：
 * - 取 [LogSqlEntry.restoredSql] trim 后首 token（空格/换行/制表符分割）。
 * - `SELECT` / `WITH`（CTE）→ [StatementType.SELECT]
 * - `INSERT` → [StatementType.INSERT]
 * - `UPDATE` → [StatementType.UPDATE]
 * - `DELETE` → [StatementType.DELETE]
 * - 其他（`MERGE` / `TRUNCATE` / `CREATE` 等）→ [StatementType.OTHER]
 */
enum class StatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    OTHER,
}

object LogStatementClassifier {

    /**
     * 按 SQL 文本分类（忽略前导空白 + 大小写不敏感）。
     *
     * @param sql 还原后的可执行 SQL（[LogSqlEntry.restoredSql]）
     * @return 语句类型（永不 null：空串/纯空白 → [StatementType.OTHER]）
     */
    fun classify(sql: String): StatementType {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) return StatementType.OTHER
        // 首 token：以空白字符（空格/换行/制表符）分割
        val firstToken = trimmed.split(Regex("\\s+"))[0].uppercase()
        return when (firstToken) {
            "SELECT", "WITH" -> StatementType.SELECT
            "INSERT" -> StatementType.INSERT
            "UPDATE" -> StatementType.UPDATE
            "DELETE" -> StatementType.DELETE
            else -> StatementType.OTHER
        }
    }

    /**
     * 按条目分类（委托 [classify]；取 [LogSqlEntry.restoredSql]）。
     *
     * @param entry 日志 SQL 条目
     * @return 语句类型
     */
    fun classify(entry: LogSqlEntry): StatementType = classify(entry.restoredSql)
}
