package cn.song.mybatisplus.visualizer.sql.extract

/**
 * SELECT * 检测器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * 检测 SQL 最外层 SELECT 是否使用 `*` 或 `alias.*`。
 * 仅检测最外层 SELECT（子查询中的 * 不影响结果）。
 */
object SelectStarDetector {

    /**
     * 检测 SQL 最外层 SELECT 是否使用 `*`。
     *
     * 匹配模式：
     *  - `SELECT *` / `SELECT t.*` / `SELECT DISTINCT *` / `SELECT \`t\`.*`
     *  - 不匹配：子查询中的 *（仅检测最外层）
     *
     * @param sql SQL 文本
     * @return true 表示最外层 SELECT 使用了 *
     */
    fun detect(sql: String): Boolean {
        if (sql.isBlank()) return false
        val upper = sql.uppercase()

        // 仅检测最外层 SELECT（取第一个 SELECT 和 FROM 之间的内容）
        val selectIdx = upper.indexOf("SELECT")
        val fromIdx = upper.indexOf("FROM")

        if (selectIdx < 0 || fromIdx < 0 || fromIdx <= selectIdx) return false

        val selectSegment = sql.substring(selectIdx + "SELECT".length, fromIdx).trim()
        // 去除反引号
        val noBackticks = selectSegment.replace("`", "")
        // 去除 DISTINCT 等修饰词
        val cleaned = noBackticks
            .removePrefix("DISTINCT")
            .removePrefix("ALL")
            .trim()

        // 检测 * 或 alias.*
        return cleaned == "*" || cleaned.matches(Regex("^[\\w]+\\.\\*$"))
    }
}
