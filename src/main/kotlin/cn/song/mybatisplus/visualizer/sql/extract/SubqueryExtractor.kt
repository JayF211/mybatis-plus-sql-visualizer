package cn.song.mybatisplus.visualizer.sql.extract

/**
 * 子查询提取器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * 从 SQL 文本计算子查询的最大嵌套深度，支持：
 *  - WHERE 子句中的子查询（IN / EXISTS / 比较运算符后）
 *  - FROM 子句中的子查询（派生表）
 *  - SELECT 列表中的子查询（标量子查询）
 *  - UNION 各分支中的子查询
 */
object SubqueryExtractor {

    /**
     * 计算 SQL 中子查询的最大嵌套深度。
     *
     * @param sql SQL 文本
     * @return 最大嵌套深度（0=无子查询）
     */
    fun maxDepth(sql: String): Int {
        if (sql.isBlank()) return 0
        return calculateMaxDepth(sql)
    }

    /**
     * 统计 SQL 中子查询的总数（含嵌套）。
     *
     * @param sql SQL 文本
     * @return 子查询总数
     */
    fun count(sql: String): Int {
        if (sql.isBlank()) return 0
        return countSubqueries(sql)
    }

    /**
     * 计算最大嵌套深度。
     *
     * 策略：基于括号深度 + 子查询引导词检测。
     * 每个在子查询上下文的 `(` 开启新嵌套层。
     */
    private fun calculateMaxDepth(sql: String): Int {
        val tokens = tokenize(sql)
        val upperTokens = tokens.map { it.uppercase() }

        var maxDepth = 0
        var currentDepth = 0
        var i = 0

        while (i < upperTokens.size) {
            val token = upperTokens[i]
            when {
                token == "(" -> {
                    // 检查前面是否有子查询引导词
                    val prev = upperTokens.getOrNull(i - 1)
                    if (isSubqueryIntroducer(prev)) {
                        currentDepth++
                        maxDepth = maxOf(maxDepth, currentDepth)
                    }
                }
                token == ")" -> {
                    if (currentDepth > 0) {
                        currentDepth--
                    }
                }
            }
            i++
        }
        return maxDepth
    }

    /**
     * 统计子查询总数。
     */
    private fun countSubqueries(sql: String): Int {
        val tokens = tokenize(sql)
        val upperTokens = tokens.map { it.uppercase() }
        var count = 0
        var i = 0
        while (i < upperTokens.size) {
            if (upperTokens[i] == "(") {
                val prev = upperTokens.getOrNull(i - 1)
                if (isSubqueryIntroducer(prev)) {
                    count++
                }
            }
            i++
        }
        return count
    }

    /**
     * 分词器：将 SQL 文本拆分为 token 列表，正确处理括号。
     *
     * 例如：`FROM (SELECT id` → `["FROM", "(", "SELECT", "id"]`
     */
    private fun tokenize(sql: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '(' || c == ')' -> {
                    // 先保存当前累积的 token
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                    // 括号单独作为一个 token
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
                    current.append(c)
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

    /**
     * 判断前一个 token 是否为子查询引导词。
     */
    private fun isSubqueryIntroducer(prev: String?): Boolean {
        if (prev == null) return false
        return prev in setOf(
            "FROM", "IN", "EXISTS", "SELECT",
            "=", ">", "<", ">=", "<=", "<>", "!=",
            "ANY", "ALL", "SOME",
        ) || prev.endsWith("(")
    }
}
