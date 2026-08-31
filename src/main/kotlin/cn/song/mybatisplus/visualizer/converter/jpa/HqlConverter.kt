package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.TableInfo

/**
 * HQL → SQL 转换器（F-JPA-02 / Wave2-P1）。
 *
 * 核心设计：**保留原始 HQL 结构**，仅做安全的字符串级变换：
 *  1. 命名参数 `:param` → `?`
 *  2. 位置参数 `?1` → `?`
 *  3. IN 子句修正：`IN ?` → `IN (?)`
 *  4. NEW DTO(...) 表达式 → 提取构造参数
 *  5. JOIN FETCH → LEFT JOIN（保留别名，推断 ON 条件）
 *  6. 实体名 → 表名映射（仅当有有效 TableInfo 时）
 *  7. 关联字段路径映射：`alias.field.id` → 外键列名（仅当有有效 TableInfo 时）
 *  8. 属性名 → 列名映射（仅当有有效 TableInfo 时）
 *  9. SELECT 子句展开：`SELECT <别名>` → 列清单（T-HQL-SELECT-01）
 *     HQL 语义 `SELECT u` = 取整个实体，须展开为全部映射列，否则 MySQL 报 1054
 *
 * 关键：返回的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
 * 转换器不解析条件树（conditions 为空），因此必须使用转换后的 SQL 字符串。
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
object HqlConverter {

    /**
     * HQL 转换产物。
     *
     * @param sql        转换后的 SQL 字符串（命名参数已替换为 ?）
     * @param statement  语句模型（SelectStatement 或 DeleteStatement）
     * @param inClauseParams 使用 IN 子句的参数名集合
     */
    data class Result(
        val sql: String,
        val statement: SqlStatement,
        val inClauseParams: Set<String> = emptySet(),
    )

    /**
     * 转换 HQL 字符串为 Result。
     *
     * @param hql       HQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做参数替换
     * @return Result（转换后 SQL + 语句模型）
     */
    fun convert(hql: String, tableInfo: TableInfo? = null): Result {
        val table = tableInfo ?: createFallbackTable()

        // 0. 检测 IN 子句参数（在命名参数替换前检测）
        val inClauseParams = findInClauseParams(hql)

        // 1. 替换命名参数 :param → ?
        var sql = replaceNamedParameters(hql)

        // 2. 替换位置参数 ?1 → ?
        sql = replacePositionalParameters(sql)

        // 3. 处理 IN 子句：IN ? → IN (?)
        sql = handleInClause(sql)

        // 4. 处理 NEW DTO(...) 表达式
        sql = handleNewExpression(sql)

        // 5. 处理 JOIN FETCH → LEFT JOIN
        sql = handleJoinFetch(sql)

        // 6. 处理 MEMBER OF（降级为注释提示）
        sql = handleMemberOf(sql)

        // 7. 实体名 → 表名映射
        if (!table.entityClassName.isBlank()) {
            sql = mapEntityToTable(sql, table)
            // 8. 关联字段路径映射：alias.field.id → 外键列名（须在属性映射之前）
            sql = mapAssociationPath(sql, table)
            // 9. 属性名 → 列名映射
            sql = mapPropertyToColumn(sql, table)
        } else {
            // Fallback：实体不可解析时，从 FROM 子句提取实体名替换为 unknown_table
            sql = mapFallbackEntityToTable(sql, table.tableName)
        }

        // 9. SELECT 子句展开：SELECT <别名> → 列清单（T-HQL-SELECT-01）
        // 必须排在步骤 5（JOIN FETCH 改写）之后——JOIN 别名只有改写后才存在，
        // 早于该步解析会取不到副表别名。DELETE 语句内部已短路。
        sql = expandSelectClause(sql, table)

        // 10. 检测 DELETE HQL → DeleteStatement
        if (sql.trimStart().uppercase().startsWith("DELETE")) {
            return Result(
                sql = sql,
                statement = DeleteStatement(table = table, conditions = emptyList()),
                inClauseParams = inClauseParams,
            )
        }

        return Result(
            sql = sql,
            statement = SelectStatement(table = table),
            inClauseParams = inClauseParams,
        )
    }

    // ------------------------------------------------------------------
    // 参数替换
    // ------------------------------------------------------------------

    /**
     * 替换命名参数 `:paramName` → `?`（排除 `::` 类型转换运算符）。
     */
    private fun replaceNamedParameters(hql: String): String {
        return hql.replace(Regex("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)"), "?")
    }

    /**
     * 替换位置参数 `?1`, `?2` 等 → `?`。
     */
    private fun replacePositionalParameters(hql: String): String {
        return hql.replace(Regex("\\?\\d+"), "?")
    }

    // ------------------------------------------------------------------
    // IN 子句
    // ------------------------------------------------------------------

    /**
     * 检测 HQL 中使用 IN 子句的命名参数。
     */
    private fun findInClauseParams(hql: String): Set<String> {
        val pattern = Regex("(?i)\\bIN\\s+:([a-zA-Z_][a-zA-Z0-9_]*)")
        return pattern.findAll(hql).map { it.groupValues[1] }.toSet()
    }

    /**
     * 处理 `IN ?` → `IN (?)`。
     */
    private fun handleInClause(hql: String): String {
        return hql.replace(Regex("(?i)\\bIN\\s+\\?"), "IN (?)")
    }

    // ------------------------------------------------------------------
    // NEW DTO 表达式
    // ------------------------------------------------------------------

    /**
     * 处理 `NEW com.xxx.VO(a, b)` 表达式 → 提取构造参数。
     * 使用括号计数法支持嵌套括号。
     */
    private fun handleNewExpression(hql: String): String {
        val prefix = "NEW"
        var searchStart = 0
        while (true) {
            val newIndex = hql.indexOf(prefix, searchStart)
            if (newIndex < 0) break
            val parenOpen = hql.indexOf('(', newIndex + prefix.length)
            if (parenOpen < 0) break
            var depth = 1
            var i = parenOpen + 1
            while (i < hql.length && depth > 0) {
                when (hql[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            if (depth != 0) break
            val parenClose = i - 1
            val args = hql.substring(parenOpen + 1, parenClose).trim()
            val replacement = hql.substring(0, newIndex) + args + hql.substring(parenClose + 1)
            return handleNewExpression(replacement)
        }
        return hql
    }

    // ------------------------------------------------------------------
    // JOIN FETCH
    // ------------------------------------------------------------------

    /**
     * 处理 `JOIN FETCH alias.relation [alias2]` → `LEFT JOIN relation_table alias2 ON ...`。
     *
     * 示例：`JOIN FETCH u.department d` → `LEFT JOIN department d ON d.id = u.department_id`
     */
    private fun handleJoinFetch(hql: String): String {
        // 捕获前导空白（组1），避免替换后丢失空格（如 uLEFT JOIN → u LEFT JOIN）
        val joinFetchPattern = Regex(
            "(\\s+)(LEFT|INNER)?\\s*JOIN\\s+FETCH\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)(\\s+[a-zA-Z_][a-zA-Z0-9_]*)?",
            RegexOption.IGNORE_CASE,
        )
        return joinFetchPattern.replace(hql) { matchResult ->
            val leadingWhitespace = matchResult.groupValues[1]
            val joinType = matchResult.groupValues[2].uppercase()
            val rootAlias = matchResult.groupValues[3]
            val relationName = matchResult.groupValues[4]
            val alias = matchResult.groupValues[5].trim()
            val keyword = when (joinType) {
                "INNER" -> "INNER JOIN"
                "LEFT" -> "LEFT JOIN"
                else -> "LEFT JOIN"
            }
            // 推断表名：relationName → snake_case
            val tableName = relationName.replace(Regex("([a-z])([A-Z])")) {
                "${it.groupValues[1]}_${it.groupValues[2].lowercase()}"
            }
            // 构建 ON 条件（别名只出现一次）
            val onCondition = if (alias.isNotEmpty()) {
                "$alias.id = $rootAlias.${relationName}_id /* 推断的ON条件，请验证 */"
            } else {
                "${relationName}_id = $rootAlias.${relationName}_id /* 推断的ON条件，请验证 */"
            }
            val aliasPart = if (alias.isNotEmpty()) " $alias" else ""
            "$leadingWhitespace$keyword $tableName$aliasPart ON $onCondition"
        }
    }

    // ------------------------------------------------------------------
    // MEMBER OF
    // ------------------------------------------------------------------

    /**
     * 处理 `MEMBER OF` 语法（降级为注释提示）。
     */
    private fun handleMemberOf(hql: String): String {
        if (hql.contains("MEMBER OF", ignoreCase = true)) {
            return hql.replace(
                Regex("(.+)\\s+MEMBER OF\\s+(.+)", RegexOption.IGNORE_CASE),
                "$1 /* MEMBER OF → 建议使用 IN/EXISTS 重写 */ $2",
            )
        }
        return hql
    }

    // ------------------------------------------------------------------
    // 实体名 → 表名映射
    // ------------------------------------------------------------------

    /**
     * 将 HQL 中的实体类名映射为数据库表名。
     * 仅在 TableInfo 有效时调用。
     */
    private fun mapEntityToTable(hql: String, table: TableInfo): String {
        if (table.entityClassName.isBlank()) return hql
        val entityPattern = Regex("\\b${Regex.escape(table.entityClassName)}\\b")
        return entityPattern.replace(hql, table.tableName)
    }

    // ------------------------------------------------------------------
    // 关联字段路径映射
    // ------------------------------------------------------------------

    /**
     * 处理关联字段路径：`alias.field.id` → `alias.joinColumn`。
     *
     * HQL 中关联字段路径的语义：
     * - `rti.ruleTable.id` → 关联字段 `ruleTable` 的主键，等价于外键列 `RULE_TABLE_ID`
     * - `rti.ruleTable.name` → 关联实体的 `name` 属性（需要 JOIN，暂用注释提示）
     *
     * 解析策略：
     * 1. 匹配 `alias.field.id` 三段式路径（两段式关联）
     * 2. 查找 `field` 对应的 `AssociationInfo`
     * 3. 将 `alias.field.id` 替换为 `alias.joinColumn`
     * 4. 对于 `alias.field.property`（非 id），添加注释提示需要 JOIN
     */
    private fun mapAssociationPath(hql: String, table: TableInfo): String {
        if (table.associations.isEmpty()) return hql

        var result = hql
        // 匹配三段式路径：alias.fieldName.propertyName
        val threeLevelPattern = Regex(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b"
        )

        result = threeLevelPattern.replace(result) { match ->
            val alias = match.groupValues[1]
            val fieldName = match.groupValues[2]
            val propertyName = match.groupValues[3]

            val association = table.associationByField(fieldName)
            if (association != null) {
                if (propertyName.equals("id", ignoreCase = true)) {
                    // alias.field.id → alias.joinColumn（外键列）
                    "$alias.${association.joinColumn}"
                } else {
                    // alias.field.property → 需要 JOIN，添加注释提示
                    "$alias.$fieldName.$propertyName /* 关联字段，需 JOIN ${association.targetTable} */"
                }
            } else {
                // 非关联字段，保持原样
                match.value
            }
        }

        return result
    }

    // ------------------------------------------------------------------
    // 属性名 → 列名映射
    // ------------------------------------------------------------------

    /**
     * 将 HQL 中的属性名映射为数据库列名。
     * 仅在 TableInfo 有效时调用。
     */
    private fun mapPropertyToColumn(hql: String, table: TableInfo): String {
        var result = hql
        for (column in table.columns) {
            if (column.fieldName == column.columnName) continue
            val propertyPattern = Regex("\\.\\b${Regex.escape(column.fieldName)}\\b")
            result = propertyPattern.replace(result, ".${column.columnName}")
        }
        return result
    }

    /**
     * Fallback：实体不可解析时，从 FROM 子句提取实体名替换为 unknown_table。
     * 仅替换第一个匹配（主表），避免重复替换导致 SQL 异常。
     */
    private fun mapFallbackEntityToTable(hql: String, tableName: String): String {
        val fromPattern = Regex("""\bFROM\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)
        val match = fromPattern.find(hql) ?: return hql
        val entityName = match.groupValues[1]
        return hql.replaceFirst(entityName, tableName)
    }

    // ------------------------------------------------------------------
    // SELECT 子句展开（T-HQL-SELECT-01）
    // ------------------------------------------------------------------

    /**
     * 将 SELECT 子句中的「实体别名」展开为列清单。
     *
     * HQL 语义：`SELECT u` = 取整个实体，Hibernate 会展开为该实体映射的全部列。
     * 原样输出 `SELECT u` 会被 MySQL 解析为「查询名为 u 的列」→
     * `[1054] Unknown column 'u' in 'field list'`（2026-08-31 用户实测）。
     *
     * 覆盖两类形态：
     *  1. 已有 SELECT 子句：`SELECT u FROM user u` → `SELECT u.id, u.username, ... FROM user u`
     *  2. 无 SELECT 子句（HQL 隐式查询）：`FROM user u WHERE ...` → 补 SELECT 列清单
     *
     * 展开规则（保守优先，宁可不展开也不误伤）：
     *  - 仅展开「裸别名」项：纯标识符，且该标识符在 FROM/JOIN 中登记为表别名
     *  - 列引用（含点号）、函数调用（含括号）、表达式、字面量、`*` → 原样保留
     *  - 别名对应实体列信息已知 → `alias.col1, alias.col2, ...`
     *  - 列信息不可得（JOIN 副表 / 元数据未解析）→ `alias.*` + 降级注释
     *  - DELETE 语句不处理（无 SELECT 子句，语义不同）
     *
     * @param sql   已完成实体→表名、属性→列名映射的 SQL
     * @param table 主表 TableInfo（提供主表列清单）
     * @return 展开后的 SQL；无需展开或无法解析时原样返回
     */
    private fun expandSelectClause(sql: String, table: TableInfo): String {
        // DELETE 无 SELECT 子句，且展开语义不同，直接短路
        if (sql.trimStart().uppercase().startsWith("DELETE")) return sql

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
            // 无 SELECT 子句：补主表列清单
            val rootAlias = aliasColumns.keys.first()
            val columns = aliasColumns[rootAlias] ?: return sql
            return "$selectKeyword ${renderColumns(rootAlias, columns)} " + sql.substring(fromIdx)
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

        val rootPattern = Regex(
            "^\\s*([a-zA-Z_][a-zA-Z0-9_.\$]*)\\s*(?:AS\\s+)?\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b",
            RegexOption.IGNORE_CASE,
        )
        val rootMatch = rootPattern.find(region)
        if (rootMatch != null) {
            val alias = rootMatch.groupValues[2]
            val columns = table.columns.map { it.columnName }
            result[alias] = if (columns.isEmpty()) null else columns
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

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 创建兜底 TableInfo（当实体类不可解析时使用）。
     */
    private fun createFallbackTable(): TableInfo {
        return TableInfo(
            entityClassName = "",
            tableName = "unknown_table",
            columns = emptyList(),
        )
    }
}
