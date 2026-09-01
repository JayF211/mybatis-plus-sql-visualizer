package cn.song.mybatisplus.visualizer.log;

/**
 * SQL 语句类型分类器（P-06，纯逻辑，可独立单测）：按 restoredSql 首 token 分类。
 *
 * 分类规则：
 * - 取 LogSqlEntry.restoredSql trim 后首 token（空格/换行/制表符分割）。
 * - `SELECT` / `WITH`（CTE）→ StatementType.SELECT
 * - `INSERT` → StatementType.INSERT
 * - `UPDATE` → StatementType.UPDATE
 * - `DELETE` → StatementType.DELETE
 * - 其他（`MERGE` / `TRUNCATE` / `CREATE` 等）→ StatementType.OTHER
 */
public final class LogStatementClassifier {
    public static final LogStatementClassifier INSTANCE = new LogStatementClassifier();

    private LogStatementClassifier() {
    }

    /**
     * 按 SQL 文本分类（忽略前导空白 + 大小写不敏感）。
     *
     * @param sql 还原后的可执行 SQL（LogSqlEntry.restoredSql）
     * @return 语句类型（永不 null：空串/纯空白 → StatementType.OTHER）
     */
    public static StatementType classify(String sql) {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) return StatementType.OTHER;
        // 首 token：以空白字符（空格/换行/制表符）分割
        String firstToken = trimmed.split("\\s+")[0].toUpperCase();
        switch (firstToken) {
            case "SELECT":
            case "WITH":
                return StatementType.SELECT;
            case "INSERT":
                return StatementType.INSERT;
            case "UPDATE":
                return StatementType.UPDATE;
            case "DELETE":
                return StatementType.DELETE;
            default:
                return StatementType.OTHER;
        }
    }

    /**
     * 按条目分类（委托 classify；取 LogSqlEntry.restoredSql）。
     *
     * @param entry 日志 SQL 条目
     * @return 语句类型
     */
    public static StatementType classify(LogSqlEntry entry) {
        return classify(entry.restoredSql());
    }
}
