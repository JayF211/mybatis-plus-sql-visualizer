package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.TableInfo

/**
 * SELECT 子句展开器（T-HQL-SELECT-01 逻辑自 HqlConverter 抽取共享 + T-BareFromSelect 裸表名回退）。
 *
 * HQL/JPQL 语义：`SELECT u` = 取整个实体，原样输出会被 MySQL 解析为「查询名为 u 的列」→
 * `[1054] Unknown column 'u' in 'field list'`（2026-08-31 用户实测）；
 * 无 SELECT 子句（`from WmsAsn`）= 隐式全列查询，须补 SELECT 列清单，
 * 否则预览只剩 `FROM 表名`（2026-09-04 用户实测）。
 *
 * [HqlConverter] 与 [JpqlConverter] 共用本展开器，保证两条链路输出一致。
 *
 * 展开规则（保守优先，宁可不展开也不误伤）：
 *  - 仅展开「裸别名」项：纯标识符，且该标识符在 FROM/JOIN 中登记为表别名
 *  - 列引用（含点号）、函数调用（含括号）、表达式、字面量、`*` → 原样保留
 *  - 别名对应实体列信息已知 → `alias.col1, alias.col2, ...`
 *  - 列信息不可得（JOIN 副表 / 元数据未解析）→ `alias.*` + 降级注释
 *  - 裸表名（FROM 后无别名）→ 表名本身登记为列限定符（SQL 允许 `表名.列` 限定）
 *  - JOIN 族关键字不登记为别名（防 `from t left join ...` 误产出 `SELECT left.id`）
 *  - DELETE 语句不处理（无 SELECT 子句，语义不同）
 *  - UPDATE / INSERT 语句不处理（J2 / J19：无 SELECT 子句语义；INSERT...SELECT 的
 *    SELECT 位于 FROM 之后，按「顶层 SELECT 在 FROM 前」的判定天然不该展开，
 *    显式短路防误前置列清单）
 *  - 顶层含 WITH（CTE）/ UNION / INTERSECT / EXCEPT 时不处理（J19 边界：
 *    「识别 + 不破坏」——多 SELECT/FROM 结构下别名归属判定不可靠，展开器
 *    宁可不展开也不误改 CTE/UNION 内部；语句其余字符串管道透传不受影响）
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
internal object SelectClauseExpander {

    /**
     * 展开 SQL 的 SELECT 子句。
     *
     * @param sql   已完成实体→表名、属性→列名映射的 SQL
     * @param table 主表 TableInfo（提供主表列清单）
     * @return 展开后的 SQL；无需展开或无法解析时原样返回
     */
    fun expand(sql: String, table: TableInfo): String {
        // 非 SELECT 语句无 SELECT 子句语义，直接短路：
        //  - DELETE（既有边界）
        //  - UPDATE（J2：UPDATE ... SET 无 SELECT，语义不同）
        //  - INSERT（J19：INSERT INTO ... SELECT 的 SELECT 在 FROM 之后，
        //    若不短路会被误判为「无 SELECT 子句」而错误前置列清单）
        val head = sql.trimStart().uppercase()
        if (head.startsWith("DELETE") || head.startsWith("UPDATE") || head.startsWith("INSERT")) return sql

        // J19 边界：CTE（WITH ...）与集合运算（UNION / INTERSECT / EXCEPT）多 SELECT/FROM
        // 结构下「首个顶层 SELECT/FROM」不必然属于同一查询分支，展开会误改 CTE/UNION 内部，
        // 保守整体跳过（宁可不展开也不误伤）
        if (indexOfTopLevelKeyword(sql, "WITH") >= 0 ||
            indexOfTopLevelKeyword(sql, "UNION") >= 0 ||
            indexOfTopLevelKeyword(sql, "INTERSECT") >= 0 ||
            indexOfTopLevelKeyword(sql, "EXCEPT") >= 0
        ) return sql

        val selectIdx = indexOfTopLevelKeyword(sql, "SELECT")
        val fromIdx = indexOfTopLevelKeyword(sql, "FROM")
        if (fromIdx < 0) return sql

        val hasSelect = selectIdx >= 0 && selectIdx < fromIdx
        val selectKeyword = if (hasSelect) sql.substring(selectIdx, selectIdx + "SELECT".length) else "SELECT"
        val selectRegion = if (hasSelect) sql.substring(selectIdx + "SELECT".length, fromIdx) else ""
        if (hasSelect && selectRegion.isBlank()) return sql

        // 别名 → 列清单映射（LinkedHashMap 保序，首项为主表别名）；value = null 表示降级为 alias.*
        val aliasColumns = parseAliasColumns(sql, fromIdx, table)
        if (aliasColumns.isEmpty()) return sql

        if (!hasSelect) {
            // 无 SELECT 子句：补主表列清单；列元数据不可得时降级为 alias.*（与显式 SELECT 分支一致）
            val rootAlias = aliasColumns.keys.first()
            return "$selectKeyword ${renderColumns(rootAlias, aliasColumns[rootAlias])} " + sql.substring(fromIdx)
        }

        val (distinct, itemText) = splitDistinct(selectRegion)
        val items = splitTopLevelCommas(itemText)
        if (items.isEmpty()) return sql

        var changed = false
        val rendered = items.map { raw ->
            val item = raw.trim()
            if (aliasColumns.containsKey(item)) {
                changed = true
                renderColumns(item, aliasColumns[item])
            } else {
                item
            }
        }
        if (!changed) return sql

        val distinctPart = if (distinct != null) "$distinct " else ""
        return sql.substring(0, selectIdx) +
            "$selectKeyword $distinctPart" + rendered.joinToString(", ") + " " +
            sql.substring(fromIdx)
    }

    /** 渲染单个别名的展开结果：`alias.col1, alias.col2` 或降级 `alias.*`。 */
    private fun renderColumns(alias: String, columns: List<String>?): String {
        if (columns.isNullOrEmpty()) {
            return "$alias.* /* 列信息未解析，降级为 $alias.* */"
        }
        return columns.joinToString(", ") { "$alias.$it" }
    }

    /**
     * 解析 FROM 子句区域中的「别名 → 列清单」映射。
     *
     * 主表别名取主表列清单；JOIN 副表无列元数据（TableInfo 只含主表列），降级为 `alias.*`。
     */
    private fun parseAliasColumns(sql: String, fromIdx: Int, table: TableInfo): LinkedHashMap<String, List<String>?> {
        val region = fromRegion(sql, fromIdx)
        val result = LinkedHashMap<String, List<String>?>()
        val columns = table.columns.map { it.columnName }

        // 两段式：`表名 别名` / `表名 AS 别名`。
        // JOIN 族关键字排除——`from t left join ...` 中 LEFT 不是别名，
        // 误登记会让隐式查询展开产出 `SELECT left.id`（SQL 语法错）。
        val rootPattern = Regex(
            "^\\s*([a-zA-Z_][a-zA-Z0-9_.\$]*)\\s*(?:AS\\s+)?" +
                "(?!(?:LEFT|RIGHT|INNER|OUTER|CROSS|FULL|JOIN|ON|FETCH)\\b)\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b",
            RegexOption.IGNORE_CASE,
        )
        val rootMatch = rootPattern.find(region)
        if (rootMatch != null) {
            result[rootMatch.groupValues[2]] = if (columns.isEmpty()) null else columns
        } else {
            // T-BareFromSelect：裸表名（FROM 后仅一个标识符、无别名）——
            // 表名本身登记为列限定符，否则 `from WmsAsn` 这类隐式查询补不出 SELECT 列清单。
            val bareRootPattern = Regex("^\\s*([a-zA-Z_][a-zA-Z0-9_.\$]*)", RegexOption.IGNORE_CASE)
            bareRootPattern.find(region)?.let {
                result[it.groupValues[1]] = if (columns.isEmpty()) null else columns
            }
        }

        val joinPattern = Regex(
            "\\bJOIN\\s+([a-zA-Z_][a-zA-Z0-9_.\$]*)\\s*(?:AS\\s+)?\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b",
            RegexOption.IGNORE_CASE,
        )
        for (match in joinPattern.findAll(region)) {
            val alias = match.groupValues[2]
            // 过滤误命中：JOIN 无别名时下一个词可能是 ON / WHERE 等子句关键字
            if (alias.equals("ON", ignoreCase = true) || alias.equals("WHERE", ignoreCase = true)) continue
            if (!result.containsKey(alias)) result[alias] = null
        }
        return result
    }

    /** 截取 FROM 之后、WHERE/GROUP BY/ORDER BY/HAVING/UNION/LIMIT 之前的子句区域。 */
    private fun fromRegion(sql: String, fromIdx: Int): String {
        val endKeywords = listOf("WHERE", "GROUP BY", "ORDER BY", "HAVING", "UNION", "LIMIT")
        var end = sql.length
        for (keyword in endKeywords) {
            val idx = indexOfTopLevelKeyword(sql, keyword, fromIdx + "FROM".length)
            if (idx >= 0 && idx < end) end = idx
        }
        return sql.substring(fromIdx + "FROM".length, end)
    }

    /** 剥离 SELECT 后的 DISTINCT 关键字，返回 (DISTINCT 原文或 null, 剩余文本)。 */
    private fun splitDistinct(region: String): Pair<String?, String> {
        val match = Regex("^\\s*(DISTINCT)\\s+", RegexOption.IGNORE_CASE).find(region)
            ?: return Pair(null, region)
        return Pair(match.groupValues[1], region.substring(match.value.length))
    }

    /** 按顶层逗号切分（括号内的逗号不切分）。 */
    private fun splitTopLevelCommas(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in text.indices) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    parts.add(text.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(text.substring(start))
        return parts
    }

    /**
     * 顶层关键字存在性探测（J19 边界判定共用：WITH/UNION/INTERSECT/EXCEPT）。
     * 括号内的同名词（子查询）不算。
     */
    internal fun hasTopLevelKeyword(sql: String, keyword: String): Boolean =
        indexOfTopLevelKeyword(sql, keyword) >= 0

    /**
     * 查找顶层（括号深度为 0）关键字的起始下标；未找到返回 -1。
     *
     * 括号内的同名词（如子查询中的 FROM）不参与匹配。
     */
    private fun indexOfTopLevelKeyword(sql: String, keyword: String, fromIndex: Int = 0): Int {
        val upper = sql.uppercase()
        val key = keyword.uppercase()
        var depth = 0
        for (i in 0 until fromIndex) {
            when (sql[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
        }
        var i = fromIndex
        while (i < sql.length) {
            when (sql[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
            if (depth == 0 && upper.startsWith(key, i) && isWordBoundary(sql, i, i + key.length)) {
                return i
            }
            i++
        }
        return -1
    }

    /** 判断 [start, end) 区间前后是否为标识符边界（避免 SELECT 命中 SELECTED 之类）。 */
    private fun isWordBoundary(sql: String, start: Int, end: Int): Boolean {
        fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_'
        if (start > 0 && isIdentChar(sql[start - 1])) return false
        if (end < sql.length && isIdentChar(sql[end])) return false
        return true
    }
}
