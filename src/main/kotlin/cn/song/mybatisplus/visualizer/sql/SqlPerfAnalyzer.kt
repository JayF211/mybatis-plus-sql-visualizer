package cn.song.mybatisplus.visualizer.sql

/**
 * SQL 性能分析纯函数模块（T-SqlPerf）。
 *
 * 对 SELECT SQL 做**纯文本静态分析**，检测性能反模式并给出优化建议。
 * 零 PSI / 零 IDE 依赖（仅 Kotlin 标准库），可用普通 JUnit 直接覆盖。
 *
 * 模块内容：
 *  - [PerfLevel]：建议级别（INFO=建议优化 / WARNING=高风险）；
 *  - [PerfSuggestion]：单条性能建议；
 *  - [PerfAnalysisResult]：分析结果容器；
 *  - [SqlPerfAnalyzer]：分析器入口 + 4 类检测函数。
 *
 * 检测能力：
 *  - [SqlPerfAnalyzer.detectSelectStar]：SELECT * 检测；
 *  - [SqlPerfAnalyzer.detectNoWhereSelect]：无 WHERE 全表扫描检测；
 *  - [SqlPerfAnalyzer.detectLeadingWildcardLike]：LIKE '%xxx' 前导通配符检测；
 *  - [SqlPerfAnalyzer.detectOrderByNotInSelect]：ORDER BY 列不在 SELECT 列表检测。
 */

/**
 * 性能建议级别（T-SqlPerf）。
 */
enum class PerfLevel { INFO, WARNING }

/**
 * 单条性能建议（T-SqlPerf）。
 *
 * @param level      建议级别（INFO=建议优化 / WARNING=高风险）
 * @param category   建议类别（select_star / no_where / leading_wildcard / order_by_missing）
 * @param message    建议文案（i18n 键，面板渲染时取本地化文本）
 * @param messageArgs i18n 参数（如列名、表名等）
 */
data class PerfSuggestion(
    val level: PerfLevel,
    val category: String,
    val message: String,
    val messageArgs: List<String> = emptyList(),
)

/**
 * SQL 性能分析结果（T-SqlPerf）。
 *
 * @param suggestions 建议列表（按检测顺序）；无建议为空列表
 */
data class PerfAnalysisResult(
    val suggestions: List<PerfSuggestion>,
) {
    val hasWarning: Boolean get() = suggestions.any { it.level == PerfLevel.WARNING }
    val hasInfo: Boolean get() = suggestions.any { it.level == PerfLevel.INFO }
}

/**
 * SQL 性能分析器（T-SqlPerf，纯函数，零 PSI/零 IDE 依赖）。
 *
 * 对 SELECT SQL 做静态分析，检测性能反模式：
 *  - [detectSelectStar]：SELECT * 检测
 *  - [detectNoWhereSelect]：无 WHERE 全表扫描检测
 *  - [detectLeadingWildcardLike]：LIKE '%xxx' 前导通配符检测
 *  - [detectOrderByNotInSelect]：ORDER BY 列不在 SELECT 列表检测
 */
object SqlPerfAnalyzer {

    /**
     * 分析 SELECT SQL，返回性能建议列表。
     *
     * @param sql 完整 SELECT SQL 文本（多行或单行）
     * @return 性能分析结果（无建议为空列表，不返回 null）
     */
    fun analyze(sql: String): PerfAnalysisResult {
        if (sql.isBlank()) return PerfAnalysisResult(emptyList())
        val suggestions = mutableListOf<PerfSuggestion>()
        detectSelectStar(sql)?.let { suggestions += it }
        detectNoWhereSelect(sql)?.let { suggestions += it }
        detectLeadingWildcardLike(sql)?.let { suggestions += it }
        detectOrderByNotInSelect(sql)?.let { suggestions += it }
        return PerfAnalysisResult(suggestions)
    }

    /**
     * 检测 SELECT *（或 SELECT t.*）。
     *
     * 匹配模式：
     *  - `SELECT *` / `SELECT t.*` / `SELECT DISTINCT *`
     *  - 不匹配：子查询中的 *（仅检测最外层）
     *
     * @return SELECT * 建议；未检测到返回 null
     */
    fun detectSelectStar(sql: String): PerfSuggestion? {
        val upper = sql.uppercase()
        val selectIdx = upper.indexOf("SELECT")
        val fromIdx = upper.indexOf("FROM")
        if (selectIdx < 0 || fromIdx < 0 || fromIdx <= selectIdx) return null

        val selectSegment = sql.substring(selectIdx + "SELECT".length, fromIdx).trim()
        // 去除 DISTINCT 等修饰词
        val cleaned = selectSegment.removePrefix("DISTINCT").trim()

        // 检测 * 或 alias.*
        if (cleaned == "*" || cleaned.matches(Regex("^[\\w]+\\.\\*$"))) {
            return PerfSuggestion(
                level = PerfLevel.INFO,
                category = "select_star",
                message = "perf.select_star",
            )
        }
        return null
    }

    /**
     * 检测无 WHERE 条件的 SELECT（全表扫描风险）。
     *
     * 排除：
     *  - 含 WHERE 子句（仅检测顶层 WHERE）
     *  - 含 LIMIT 的小结果集查询（LIMIT 存在时全表扫描风险降低，仍提示但降级为 INFO）
     *
     * @return 无 WHERE 建议；有 WHERE 返回 null
     */
    fun detectNoWhereSelect(sql: String): PerfSuggestion? {
        val upper = sql.uppercase()
        if (!upper.contains("SELECT") || !upper.contains("FROM")) return null

        // 检测顶层 WHERE 存在
        val whereIdx = findTopLevelKeyword(upper, "WHERE")
        if (whereIdx >= 0) return null

        // 无 WHERE：有 LIMIT → INFO（风险降低），无 LIMIT → WARNING
        val hasLimit = findTopLevelKeyword(upper, "LIMIT") >= 0
        return PerfSuggestion(
            level = if (hasLimit) PerfLevel.INFO else PerfLevel.WARNING,
            category = "no_where",
            message = if (hasLimit) "perf.no_where_with_limit" else "perf.no_where",
        )
    }

    /**
     * 检测 LIKE '%xxx' 前导通配符（无法走索引）。
     *
     * 匹配模式：
     *  - `LIKE '%xxx'` / `LIKE "%xxx"` / `NOT LIKE '%xxx'`
     *  - 不匹配：`LIKE 'xxx%'`（后缀通配，可走索引）
     *
     * @return 前导通配符建议列表（可能多条）；未检测到返回 null
     */
    fun detectLeadingWildcardLike(sql: String): PerfSuggestion? {
        // 匹配 LIKE 后紧跟的字符串字面量
        val likePattern = Regex(
            """(?:NOT\s+)?LIKE\s+['"](%[^'"]*)['"]""",
            RegexOption.IGNORE_CASE,
        )
        val matches = likePattern.findAll(sql)
        val hasLeadingWildcard = matches.any { match ->
            val pattern = match.groupValues[1]
            pattern.startsWith("%")
        }
        return if (hasLeadingWildcard) {
            PerfSuggestion(
                level = PerfLevel.INFO,
                category = "leading_wildcard",
                message = "perf.leading_wildcard",
            )
        } else null
    }

    /**
     * 检测 ORDER BY 列不在 SELECT 列表中（文件排序风险）。
     *
     * 简化实现：
     *  - 提取 SELECT 列列表（逗号分隔，去除 AS 别名）
     *  - 提取 ORDER BY 列列表（逗号分隔，去除 ASC/DESC）
     *  - 比对：ORDER BY 列不在 SELECT 列中 → 建议
     *
     * @return ORDER BY 列缺失建议；无问题返回 null
     */
    fun detectOrderByNotInSelect(sql: String): PerfSuggestion? {
        val upper = sql.uppercase()
        val selectIdx = upper.indexOf("SELECT")
        val fromIdx = upper.indexOf("FROM")
        val orderByIdx = upper.indexOf("ORDER BY")

        if (selectIdx < 0 || fromIdx < 0 || orderByIdx < 0) return null

        // 提取 SELECT 列
        val selectSegment = sql.substring(selectIdx + "SELECT".length, fromIdx)
        val selectColumns = parseSelectColumns(selectSegment)

        // SELECT * 时 ORDER BY 列缺失不重复报
        if (selectColumns.isEmpty()) return null

        // 提取 ORDER BY 列
        val orderBySegment = sql.substring(orderByIdx + "ORDER BY".length)
        // 截断到下一个顶层关键字（LIMIT / UNION 等）
        val orderByClean = truncateAtTopLevelKeyword(orderBySegment)
        val orderByColumns = parseOrderByColumns(orderByClean)

        // 比对
        val missing = orderByColumns.filter { it !in selectColumns }
        return if (missing.isNotEmpty()) {
            PerfSuggestion(
                level = PerfLevel.INFO,
                category = "order_by_missing",
                message = "perf.order_by_missing",
                messageArgs = missing,
            )
        } else null
    }

    // ------------------------------------------------------------------
    // 私有辅助函数
    // ------------------------------------------------------------------

    /**
     * 查找顶层关键字索引（不在括号内）。
     * @return 找到返回索引；未找到返回 -1
     */
    private fun findTopLevelKeyword(upperSql: String, keyword: String): Int {
        var depth = 0
        var i = 0
        while (i < upperSql.length) {
            when (upperSql[i]) {
                '(' -> depth++
                ')' -> depth--
                else -> {
                    if (depth == 0 && upperSql.substring(i).startsWith(keyword)) {
                        // 确认是完整词（前后非标识符字符）
                        val before = upperSql.getOrNull(i - 1)
                        val after = upperSql.getOrNull(i + keyword.length)
                        if ((before == null || !before.isLetterOrDigit()) &&
                            (after == null || !after.isLetterOrDigit())
                        ) {
                            return i
                        }
                    }
                }
            }
            i++
        }
        return -1
    }

    /**
     * 解析 SELECT 段为列名集合（去除表别名前缀、AS 别名、函数包裹）。
     */
    private fun parseSelectColumns(selectSegment: String): Set<String> {
        return selectSegment.split(",").map { col ->
            var c = col.trim()
            // 去除 AS 别名
            val asIdx = c.uppercase().indexOf(" AS ")
            if (asIdx >= 0) c = c.substring(0, asIdx).trim()
            // 去除表别名前缀（alias.col → col）
            val dotIdx = c.lastIndexOf('.')
            if (dotIdx >= 0) c = c.substring(dotIdx + 1).trim()
            // 去除反引号
            c = c.removeSurrounding("`").lowercase()
            c
        }.filter { it.isNotEmpty() && it != "*" }.toSet()
    }

    /**
     * 解析 ORDER BY 段为列名集合（去除 ASC/DESC）。
     */
    private fun parseOrderByColumns(orderBySegment: String): List<String> {
        return orderBySegment.split(",").map { col ->
            var c = col.trim()
            // 去除 ASC/DESC（大写形式）
            c = c.removeSuffix("ASC").removeSuffix("DESC").trim()
            // 去除表别名前缀
            val dotIdx = c.lastIndexOf('.')
            if (dotIdx >= 0) c = c.substring(dotIdx + 1).trim()
            // 去除反引号
            c.removeSurrounding("`").lowercase()
        }.filter { it.isNotEmpty() }
    }

    /**
     * 在顶层关键字处截断（LIMIT / UNION / GROUP BY 等）。
     */
    private fun truncateAtTopLevelKeyword(segment: String): String {
        val keywords = listOf("LIMIT", "UNION", "GROUP BY", "HAVING", "OFFSET")
        var minIdx = segment.length
        val upper = segment.uppercase()
        for (kw in keywords) {
            val idx = upper.indexOf(kw)
            if (idx in 0 until minIdx) minIdx = idx
        }
        return segment.substring(0, minIdx)
    }
}
