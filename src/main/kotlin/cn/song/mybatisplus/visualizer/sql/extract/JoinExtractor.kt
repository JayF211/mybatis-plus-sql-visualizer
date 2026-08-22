package cn.song.mybatisplus.visualizer.sql.extract

/**
 * JOIN 提取器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * 从 SQL 文本统计 JOIN 数量并识别 JOIN 类型，支持：
 *  - INNER JOIN / LEFT JOIN / RIGHT JOIN / FULL OUTER JOIN / CROSS JOIN / NATURAL JOIN
 *  - 简单 JOIN（等同于 INNER JOIN）
 *  - 子查询内的 JOIN（递归统计）
 */
object JoinExtractor {

    /**
     * 统计 SQL 中显式 JOIN 的总数。
     *
     * @param sql SQL 文本
     * @return JOIN 数量；无法识别返回 0
     */
    fun count(sql: String): Int {
        if (sql.isBlank()) return 0
        val tokens = tokenize(sql)
        val upperTokens = tokens.map { it.uppercase() }
        var count = 0
        for (token in upperTokens) {
            if (token == "JOIN") {
                count++
            }
        }
        return count
    }

    /**
     * 返回 SQL 中各 JOIN 的类型列表（按出现顺序）。
     *
     * @param sql SQL 文本
     * @return JOIN 类型列表（"INNER" / "LEFT" / "RIGHT" / "FULL" / "CROSS" / "NATURAL" / "INNER"）
     */
    fun joinTypes(sql: String): List<String> {
        if (sql.isBlank()) return emptyList()
        val tokens = tokenize(sql)
        val upperTokens = tokens.map { it.uppercase() }
        val types = mutableListOf<String>()
        var i = 0
        while (i < upperTokens.size) {
            when (upperTokens[i]) {
                "INNER" -> {
                    if (i + 1 < upperTokens.size && upperTokens[i + 1] == "JOIN") {
                        types.add("INNER")
                        i += 2
                        continue
                    }
                }
                "LEFT" -> {
                    if (i + 1 < upperTokens.size && upperTokens[i + 1] == "JOIN") {
                        types.add("LEFT")
                        i += 2
                        continue
                    }
                }
                "RIGHT" -> {
                    if (i + 1 < upperTokens.size && upperTokens[i + 1] == "JOIN") {
                        types.add("RIGHT")
                        i += 2
                        continue
                    }
                }
                "FULL" -> {
                    if (i + 2 < upperTokens.size && upperTokens[i + 1] == "OUTER" && upperTokens[i + 2] == "JOIN") {
                        types.add("FULL")
                        i += 3
                        continue
                    } else if (i + 1 < upperTokens.size && upperTokens[i + 1] == "JOIN") {
                        types.add("FULL")
                        i += 2
                        continue
                    }
                }
                "CROSS" -> {
                    if (i + 1 < upperTokens.size && upperTokens[i + 1] == "JOIN") {
                        types.add("CROSS")
                        i += 2
                        continue
                    }
                }
                "NATURAL" -> {
                    if (i + 1 < upperTokens.size && upperTokens[i + 1] == "JOIN") {
                        types.add("NATURAL")
                        i += 2
                        continue
                    }
                }
                "JOIN" -> {
                    types.add("INNER")
                }
            }
            i++
        }
        return types
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
