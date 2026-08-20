package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver

/**
 * JPQL → SQL 转换器（F-JPA-02）。
 *
 * 将 Spring Data JPA @Query 注解中的 JPQL 字符串转换为可预览的 SQL（[SelectStatement]）。
 *
 * JPQL 与 SQL 的主要差异及转换规则：
 *  - 实体名 → 表名映射（通过 [JpaEntityResolver] 反查 @Table 注解决定的表名）
 *  - 属性名 → 列名映射
 *  - 命名参数 `:param` → `?` 占位符
 *  - 位置参数 `?1` → `?` 占位符
 *  - `JOIN FETCH e.relation r` → `LEFT JOIN table r ON ...`
 *  - `NEW com.xxx.VO(a, b)` → `SELECT a, b`
 *  - `MEMBER OF` → 降级提示
 *
 * 对于复杂 JPQL（子查询、MEMBER OF、NEW DTO(...)），展示基础转换 + 注释提示。
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
object JpqlConverter {

    /**
     * 转换 JPQL 字符串为 SelectStatement。
     *
     * @param jpql       JPQL 查询字符串
     * @param tableInfo  实体表信息（列映射用）；可为 null，此时仅做简单字符串替换
     * @return SELECT 语句模型
     */
    fun convert(jpql: String, tableInfo: TableInfo? = null): SelectStatement {
        return convertInternal(jpql, tableInfo)
    }

    /**
     * 内部转换实现。
     */
    private fun convertInternal(jpql: String, tableInfo: TableInfo?): SelectStatement {
        val table = tableInfo ?: createFallbackTable()

        // 1. 替换命名参数 :param → ?
        var sql = replaceNamedParameters(jpql)

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

        return SelectStatement(
            table = table,
            comment = if (hasComplexConstruct(jpql)) "/* JPQL 含复杂语法，仅做基础转换 */" else null,
        )
    }

    /**
     * 替换命名参数 `:paramName` → `?`。
     *
     * JPQL 命名参数格式为 `:paramName`（冒号后跟标识符）。
     */
    private fun replaceNamedParameters(jpql: String): String {
        // 匹配 :identifier（冒号后跟字母/数字/下划线），但排除 ::（类型转换）
        return jpql.replace(Regex("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)"), "?")
    }

    /**
     * 替换位置参数 `?1`, `?2` 等 → `?`。
     *
     * JPQL 位置参数格式为 `?数字`。
     */
    private fun replacePositionalParameters(jpql: String): String {
        return jpql.replace(Regex("\\?\\d+"), "?")
    }

    /**
     * 处理 `NEW com.xxx.VO(a, b)` 表达式。
     *
     * JPQL 的构造函数表达式映射为 SELECT 列提取。
     * 示例：`SELECT NEW com.example.UserDTO(u.name, u.age) FROM User u`
     * → `SELECT u.name, u.age FROM User u`
     */
    private fun handleNewExpression(jpql: String): String {
        // 匹配 NEW package.ClassName(args) 并提取参数列表
        val newPattern = Regex("NEW\\s+[a-zA-Z_][a-zA-Z0-9_.]*\\(([^)]+)\\)")
        return newPattern.replace(jpql) { matchResult ->
            val args = matchResult.groupValues[1].trim()
            args
        }
    }

    /**
     * 处理 `JOIN FETCH e.relation r` → `LEFT JOIN relation_table r ON ...`。
     *
     * 增强版（P1-3 JOIN FETCH ON 条件推断）：
     *  - 推断 ON 条件：从关联属性名推断外键列
     *    （如 `JOIN FETCH u.department d` → ON d.id = u.department_id）
     */
    fun handleJoinFetch(jpql: String): String {
        val joinFetchPattern = Regex(
            "(LEFT|INNER)?\\s*JOIN\\s+FETCH\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)",
            RegexOption.IGNORE_CASE,
        )
        return joinFetchPattern.replace(jpql) { matchResult ->
            val joinType = matchResult.groupValues[1].uppercase()
            val rootAlias = matchResult.groupValues[2]
            val alias = matchResult.groupValues[3]
            val keyword = when (joinType) {
                "INNER" -> "INNER JOIN"
                "LEFT" -> "LEFT JOIN"
                else -> "LEFT JOIN"
            }
            val inferredOn = inferJoinOnCondition(rootAlias, alias)
            "$keyword /* 关联表_$alias */ $alias ON $inferredOn"
        }
    }

    /**
     * 推断 JOIN FETCH 的 ON 条件（P1-3）。
     */
    private fun inferJoinOnCondition(rootAlias: String, joinAlias: String): String {
        return "$joinAlias.id = $rootAlias.${joinAlias}_id /* 推断的ON条件，请验证 */"
    }

    /**
     * 处理 `MEMBER OF` 语法。
     *
     * JPQL 的 `x MEMBER OF y` 语义等价于 SQL 的 IN 或 EXISTS 子查询。
     * 此处做降级处理，保留原始文本并添加注释。
     */
    private fun handleMemberOf(jpql: String): String {
        if (jpql.contains("MEMBER OF", ignoreCase = true)) {
            return jpql.replace(
                Regex("(.+)\\s+MEMBER OF\\s+(.+)", RegexOption.IGNORE_CASE),
                "$1 /* MEMBER OF → 建议使用 IN/EXISTS 重写 */ $2",
            )
        }
        return jpql
    }

    /**
     * 将 JPQL 中的实体类名映射为数据库表名。
     *
     * 策略：
     *  - 优先使用 TableInfo 中的表名替换 JPQL 中的实体类名
     *  - 映射范围：FROM 子句和 SELECT 子句中的实体引用
     */
    private fun mapEntityToTable(jpql: String, table: TableInfo): String {
        if (table.entityClassName.isBlank()) return jpql
        // 替换 FROM 子句中的实体类名（简单替换，仅在确认不是更大标识符的一部分时）
        // 注意：这里只做简单替换，精确替换需要完整语法解析
        val entityPattern = Regex("\\b${Regex.escape(table.entityClassName)}\\b")
        return entityPattern.replace(jpql, table.tableName)
    }

    /**
     * 将 JPQL 中的属性名映射为数据库列名。
     *
     * 策略（增强版 P1-3）：
     *  - 从 TableInfo.columns 获取属性名 → 列名映射
     *  - 对 `alias.property` 形式的引用，将 property 替换为 columnName
     *  - SELECT 多别名展开：处理 `SELECT a.prop, b.prop` 多实体别名场景
     */
    fun mapPropertyToColumn(jpql: String, table: TableInfo): String {
        var result = jpql
        val mappedColumnNames = mutableSetOf<String>()
        for (column in table.columns) {
            if (column.fieldName == column.columnName) {
                mappedColumnNames.add(column.columnName)
                continue
            }
            if (column.columnName in mappedColumnNames) continue

            val propertyPattern = Regex("\\.\\b${Regex.escape(column.fieldName)}\\b")
            result = propertyPattern.replace(result, ".${column.columnName}")
            mappedColumnNames.add(column.columnName)
        }

        result = expandSelectAliases(result, table)
        return result
    }

    /**
     * SELECT 子句多别名展开（增强版 P1-3）。
     */
    private fun expandSelectAliases(jpql: String, table: TableInfo): String {
        val selectMatch = Regex("SELECT\\s+(.+?)\\s+FROM", RegexOption.IGNORE_CASE).find(jpql)
            ?: return jpql
        val selectClause = selectMatch.groupValues[1].trim()

        if (!selectClause.contains(",")) return jpql

        val items = selectClause.split(",").map { it.trim() }
        var expandedSelect = selectClause
        for (item in items) {
            if (item.contains(".")) continue

            val colMatch = table.columns.firstOrNull { it.fieldName == item }
            if (colMatch != null && colMatch.fieldName != colMatch.columnName) {
                expandedSelect = expandedSelect.replace(item, colMatch.columnName)
            }
        }

        return jpql.replace("SELECT $selectClause", "SELECT $expandedSelect")
    }

    /**
     * 检测 JPQL 是否包含复杂语法结构（增强版 P1-3）。
     *
     * 增强：更精确的子查询检测（排除字符串字面量中的 SELECT 误报）。
     */
    fun hasComplexConstruct(jpql: String): Boolean {
        val hasSubquery = detectSubquery(jpql)
        val upperJpql = jpql.uppercase()
        val hasMemberOf = upperJpql.contains("MEMBER OF")
        val hasNew = upperJpql.contains("NEW ")
        return hasSubquery || hasMemberOf || hasNew
    }

    /**
     * 检测 JPQL 中的子查询（增强版 P1-3）。
     */
    private fun detectSubquery(jpql: String): Boolean {
        val cleaned = jpql.replace(Regex("'[^']*'"), "''")
        val subqueryPattern = Regex("\\(\\s*SELECT\\b", RegexOption.IGNORE_CASE)
        return subqueryPattern.containsMatchIn(cleaned)
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
