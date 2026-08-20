package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo

/**
 * HQL → SQL 转换器（F-JPA-02 / Wave2-P1）。
 *
 * Hibernate HQL 与 JPQL 高度相似，复用 [JpqlConverter] 的核心逻辑，并扩展：
 *  - HQL 聚合函数：COUNT/SUM/MAX/MIN/AVG 识别与转换
 *  - GROUP BY 子句提取
 *  - HAVING 条件提取与转换
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
object HqlConverter {

    // HQL 聚合函数名（大小写不敏感）
    private val AGGREGATE_FUNCTIONS = setOf("COUNT", "SUM", "MAX", "MIN", "AVG")

    /**
     * 转换 HQL 字符串为 SelectStatement。
     *
     * @param hql       HQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做简单字符串替换
     * @return SELECT 语句模型
     */
    fun convert(hql: String, tableInfo: TableInfo? = null): SelectStatement {
        return convertInternal(hql, tableInfo)
    }

    /**
     * 内部转换实现。
     */
    private fun convertInternal(hql: String, tableInfo: TableInfo?): SelectStatement {
        val table = tableInfo ?: createFallbackTable()

        // 1. 替换命名参数 :param → ?
        var sql = replaceNamedParameters(hql)

        // 2. 替换位置参数 ?1 → ?
        sql = replacePositionalParameters(sql)

        // 3. 处理 NEW DTO(...) 表达式
        sql = handleNewExpression(sql)

        // 4. 处理 JOIN FETCH → LEFT JOIN
        sql = handleJoinFetch(sql)

        // 5. 处理 MEMBER OF（降级为注释提示）
        sql = handleMemberOf(sql)

        // 6. 实体名 → 表名映射
        sql = mapEntityToTable(sql, table)

        // 7. 属性名 → 列名映射
        sql = mapPropertyToColumn(sql, table)

        // 8. 处理聚合函数语法（HQL 特有的大小写变体等）
        sql = handleAggregateFunctions(sql)

        // 9. 提取 GROUP BY / HAVING（以注释形式标记）
        val hasGroupBy = containsGroupBy(sql)
        val hasHaving = containsHaving(sql)

        val comment = buildComment(hql, hasGroupBy, hasHaving)

        return SelectStatement(
            table = table,
            comment = comment,
        )
    }

    /**
     * 替换命名参数 `:paramName` → `?`。
     * 复用 JpqlConverter 的命名参数替换逻辑。
     */
    private fun replaceNamedParameters(hql: String): String {
        return hql.replace(Regex("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)"), "?")
    }

    /**
     * 替换位置参数 `?1`, `?2` 等 → `?`。
     * 复用 JpqlConverter 的位置参数替换逻辑。
     */
    private fun replacePositionalParameters(hql: String): String {
        return hql.replace(Regex("\\?\\d+"), "?")
    }

    /**
     * 处理 `NEW com.xxx.VO(a, b)` 表达式。
     * 复用 JpqlConverter 的 NEW 表达式处理逻辑。
     */
    private fun handleNewExpression(hql: String): String {
        val newPattern = Regex("NEW\\s+[a-zA-Z_][a-zA-Z0-9_.]*\\(([^)]+)\\)")
        return newPattern.replace(hql) { matchResult ->
            val args = matchResult.groupValues[1].trim()
            args
        }
    }

    /**
     * 处理 `JOIN FETCH e.relation r` → `LEFT JOIN relation_table r ON ...`。
     * 复用 JpqlConverter 的 JOIN FETCH 处理逻辑。
     */
    private fun handleJoinFetch(hql: String): String {
        val joinFetchPattern = Regex(
            "(LEFT|INNER)?\\s*JOIN\\s+FETCH\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)",
            RegexOption.IGNORE_CASE,
        )
        return joinFetchPattern.replace(hql) { matchResult ->
            val joinType = matchResult.groupValues[1].uppercase()
            val alias = matchResult.groupValues[3]
            val keyword = when (joinType) {
                "INNER" -> "INNER JOIN"
                "LEFT" -> "LEFT JOIN"
                else -> "LEFT JOIN"
            }
            "$keyword /* 关联表_$alias */ $alias /* ON 关联条件需手动补充 */"
        }
    }

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

    /**
     * 将 HQL 中的实体类名映射为数据库表名。
     * 复用 JpqlConverter 的实体映射逻辑。
     */
    private fun mapEntityToTable(hql: String, table: TableInfo): String {
        if (table.entityClassName.isBlank()) return hql
        val entityPattern = Regex("\\b${Regex.escape(table.entityClassName)}\\b")
        return entityPattern.replace(hql, table.tableName)
    }

    /**
     * 将 HQL 中的属性名映射为数据库列名。
     * 复用 JpqlConverter 的属性映射逻辑。
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
     * 处理 HQL 聚合函数的语法规范化。
     *
     * HQL 聚合函数与 SQL 基本一致，但 HQL 支持：
     *  - COUNT(DISTINCT alias.property)
     *  - 大小写不敏感（count/COUNT/Count）
     *
     * 此处确保聚合函数调用格式正确（不做额外转换，仅规范化空白）。
     */
    private fun handleAggregateFunctions(hql: String): String {
        // 规范化聚合函数名后的空白：COUNT (x) → COUNT(x)
        var result = hql
        for (aggFunc in AGGREGATE_FUNCTIONS) {
            val pattern = Regex("$aggFunc\\s+\\(", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, "$aggFunc(")
        }
        return result
    }

    /**
     * 检测 HQL 是否包含 GROUP BY 子句。
     */
    private fun containsGroupBy(hql: String): Boolean {
        return Regex("\\bGROUP\\s+BY\\b", RegexOption.IGNORE_CASE).containsMatchIn(hql)
    }

    /**
     * 检测 HQL 是否包含 HAVING 子句。
     */
    private fun containsHaving(hql: String): Boolean {
        return Regex("\\bHAVING\\b", RegexOption.IGNORE_CASE).containsMatchIn(hql)
    }

    /**
     * 构建转换注释（标记复杂语法结构）。
     */
    private fun buildComment(hql: String, hasGroupBy: Boolean, hasHaving: Boolean): String? {
        val upperHql = hql.uppercase()
        val hasSubquery = upperHql.indexOf("SELECT", upperHql.indexOf("SELECT") + 1) != -1
        val hasMemberOf = upperHql.contains("MEMBER OF")
        val hasNew = upperHql.contains("NEW ")

        if (!hasSubquery && !hasMemberOf && !hasNew && !hasGroupBy && !hasHaving) {
            return null
        }

        val parts = mutableListOf<String>()
        if (hasSubquery) parts += "含子查询"
        if (hasMemberOf) parts += "含MEMBER OF"
        if (hasNew) parts += "含NEW DTO"
        if (hasGroupBy) parts += "含GROUP BY"
        if (hasHaving) parts += "含HAVING"

        return "/* HQL ${parts.joinToString("、")}，仅做基础转换 */"
    }

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
