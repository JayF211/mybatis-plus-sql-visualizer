package cn.song.mybatisplus.visualizer.sql.extract

/**
 * 表名提取器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * 从 SQL 文本提取所有表名（去重），支持：
 *  - FROM / JOIN 子句中的表名
 *  - 子查询内的表名（递归提取）
 *  - UNION 各分支的表名
 *  - UPDATE / DELETE / INSERT 的目标表
 *  - CTE（WITH 子句）内的表名
 *  - 反引号包裹的表名（去反引号）
 */
object TableExtractor {

    // 不应当作表名的 SQL 关键字
    private val KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "ON", "AS", "BY",
        "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL",
        "SET", "VALUES", "INTO", "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET",
        "UNION", "ALL", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END",
        "EXISTS", "BETWEEN", "LIKE", "IS", "NULL", "TRUE", "FALSE", "WITH",
        "INSERT", "UPDATE", "DELETE", "USING", "FETCH", "FIRST", "NEXT",
        "ROWS", "ONLY", "OVER", "PARTITION", "RANGE", "PRECEDING", "FOLLOWING",
        "CURSOR", "FOR", "IF", "DUAL",
    )

    // 引入表名的关键字
    private val TABLE_INTRODUCERS = setOf("FROM", "JOIN", "INTO", "UPDATE")

    /**
     * 从 SQL 文本提取所有表名（去重，保留首次出现顺序）。
     *
     * 遍历所有 token，当遇到表名引入关键字（FROM/JOIN/INTO/UPDATE）时，
     * 查找其后的表名。同时处理逗号分隔的多表。
     *
     * @param sql SQL 文本
     * @return 表名列表（去重，小写）；无法识别返回空列表
     */
    fun extract(sql: String): List<String> {
        if (sql.isBlank()) return emptyList()
        val tables = linkedSetOf<String>()
        val tokens = tokenize(sql)
        val upperTokens = tokens.map { it.uppercase() }

        var i = 0
        while (i < upperTokens.size) {
            val token = upperTokens[i]
            if (token in TABLE_INTRODUCERS) {
                // 查找紧跟其后的表名，并处理逗号分隔的多表
                var j = i + 1
                while (true) {
                    val tableIdx = findImmediateTable(upperTokens, j)
                    if (tableIdx < 0) break
                    val tableName = tokens[tableIdx].lowercase()
                    if (isValidTableName(tableName)) {
                        tables.add(tableName)
                    }
                    j = tableIdx + 1
                    // 检查是否有逗号分隔的下一个表
                    if (j < upperTokens.size && upperTokens[j] == ",") {
                        j++  // 跳过逗号，继续查找
                    } else {
                        break  // 没有逗号，结束当前 FROM/JOIN 的表名扫描
                    }
                }
                i = j - 1  // 外层循环会 i++
            }
            i++
        }
        return tables.toList()
    }

    /**
     * 查找指定位置后的第一个有效表名。
     *
     * 跳过关键字，遇到 `(` 或 `,` 返回 -1（表示无直接表名）。
     */
    private fun findImmediateTable(upperTokens: List<String>, startIdx: Int): Int {
        var idx = startIdx
        while (idx < upperTokens.size) {
            val token = upperTokens[idx]
            when {
                token == "(" -> return -1  // 子查询，无直接表名
                token == "," -> return -1  // 逗号，无直接表名
                token in KEYWORDS -> idx++  // 关键字，继续查找
                else -> return idx         // 找到表名
            }
        }
        return -1
    }

    /**
     * 判断是否为合法表名（非纯数字、非空、长度合理）。
     */
    private fun isValidTableName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.all { it.isDigit() }) return false
        if (name.length > 128) return false
        val first = name.first()
        return first.isLetter() || first == '_'
    }

    /**
     * 分词器：将 SQL 文本拆分为 token 列表，正确处理括号、逗号。
     */
    private fun tokenize(sql: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '(' || c == ')' || c == ',' -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                    result.add(c.toString())
                }
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                c == '`' -> {
                    // 跳过反引号
                }
                else -> {
                    current.append(c)
                }
            }
            i++
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }
}
