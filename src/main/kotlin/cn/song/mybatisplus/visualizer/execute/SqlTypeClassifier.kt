package cn.song.mybatisplus.visualizer.execute

/**
 * SQL 语句类型分类器（M36）：区分查询类语句（免确认）与非查询类语句（需二次确认）。
 *
 * 分类规则（按首条有效语句首关键字判定）：
 *  - 查询类（免确认）：SELECT / SHOW / EXPLAIN / DESCRIBE / WITH（CTE）
 *  - DML（需确认）：INSERT / UPDATE / DELETE / MERGE
 *  - DDL（需确认）：CREATE / ALTER / DROP / TRUNCATE
 *  - 多条语句混合 → 按最高风险级别处理（需确认）
 *  - 注释（-- 单行 / /* */ 块注释）自动跳过
 */
object SqlTypeClassifier {

    /** SQL 语句类型枚举（风险升序）。 */
    enum class SqlType { QUERY, DML, DDL, UNKNOWN }

    /** 查询类关键字（免确认）。 */
    private val QUERY_KEYWORDS = setOf("SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "WITH")

    /** DML 关键字（需确认）。 */
    private val DML_KEYWORDS = setOf("INSERT", "UPDATE", "DELETE", "MERGE")

    /** DDL 关键字（需确认）。 */
    private val DDL_KEYWORDS = setOf("CREATE", "ALTER", "DROP", "TRUNCATE")

    /**
     * 判断 SQL 是否为查询类语句（免确认直接执行）。
     *
     * @param sql SQL 文本（可能含多条语句、注释）
     * @return true=查询类（SELECT/SHOW/EXPLAIN/DESCRIBE/WITH）；false=非查询或无法判定
     */
    fun isQuery(sql: String): Boolean {
        val statements = splitStatements(sql)
        if (statements.isEmpty()) return false
        // 多条语句：全部为查询类才免确认；任一条非查询 → 需确认
        return statements.all { classify(it) == SqlType.QUERY }
    }

    /**
     * 提取 SQL 首条有效语句的首关键字（大写）。
     *
     * @param sql SQL 文本
     * @return 首关键字大写（如 "SELECT"、"INSERT"），无有效语句时返回 null
     */
    fun getFirstKeyword(sql: String): String? {
        val first = splitStatements(sql).firstOrNull() ?: return null
        return extractKeyword(first)
    }

    /**
     * 对单条 SQL 语句分类。
     *
     * @param statement 单条 SQL 语句（已去除注释）
     * @return 分类结果
     */
    fun classify(statement: String): SqlType {
        val keyword = extractKeyword(statement) ?: return SqlType.UNKNOWN
        return when {
            QUERY_KEYWORDS.contains(keyword) -> SqlType.QUERY
            DML_KEYWORDS.contains(keyword) -> SqlType.DML
            DDL_KEYWORDS.contains(keyword) -> SqlType.DDL
            else -> SqlType.UNKNOWN
        }
    }

    /**
     * 按分号分割 SQL 为多条语句（忽略空语句），并逐条去注释。
     *
     * T-SemicolonGuard（O-13）：实现收敛委托至 util/SqlStatementSplitter（与
     * LogToSqlPanel/SqlToJavaConverter 单实现合一）；顺带修复两个历史缺陷——字符串内
     * 分号误切（`'a;b'`）、字符串内 `--` 被误剥注释（差异清单见开发计划 T-SG-01）。
     *
     * @param sql SQL 文本
     * @return 去注释后的非空语句列表（段已 trim）
     */
    internal fun splitStatements(sql: String): List<String> =
        cn.song.mybatisplus.visualizer.util.SqlStatementSplitter.splitStripped(sql)

    /**
     * 去除 SQL 语句中的注释（-- 单行注释 + /* */ 块注释）。
     *
     * @param sql 原始 SQL 片段
     * @return 去注释后的文本
     */
    internal fun stripComments(sql: String): String {
        val sb = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                // -- 单行注释：跳过至行尾
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    // 跳至行尾或文本结束
                    while (i < sql.length && sql[i] != '\n') i++
                }
                // /* */ 块注释
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i < sql.length && !(sql[i] == '*' && i + 1 < sql.length && sql[i + 1] == '/')) i++
                    i += 2 // 跳过 */
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * 提取语句的首关键字（大写）。跳过前导空白与左括号。
     *
     * @param statement 单条 SQL 语句（已去注释）
     * @return 首关键字大写，无有效关键字时返回 null
     */
    private fun extractKeyword(statement: String): String? {
        val trimmed = statement.trimStart()
        if (trimmed.isEmpty()) return null
        // 提取连续字母作为关键字
        val letters = trimmed.takeWhile { it.isLetter() }
        return letters.ifEmpty { null }?.uppercase()
    }
}
