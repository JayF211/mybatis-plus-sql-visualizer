package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import com.intellij.openapi.project.Project

/**
 * HQL → SQL 转换器（F-JPA-02 / Wave2-P1）。
 *
 * 核心设计：**保留原始 HQL 结构**，仅做安全的字符串级变换：
 *  1. 命名参数 `:param` → `?`
 *  2. 位置参数 `?1` → `?`
 *  3. IN 子句修正：`IN ?` → `IN (?)`
 *  4. NEW DTO(...) 表达式 → 提取构造参数
 *  5. JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件经 [JpaJoinOnResolver]
 *     按「PSI 注解 > 关联元数据 > 猜测降级」三级优先真实化）
 *  6. 实体名 → 表名映射（仅当有有效 TableInfo 时）
 *  7. 关联字段路径映射：`alias.field.id` → 外键列名（仅当有有效 TableInfo 时）
 *  8. 属性名 → 列名映射（仅当有有效 TableInfo 时）
 *  8.5 子查询 FROM/JOIN 中的其他实体名 → 表名（J13，需 Project；无 Project 跳过）
 *  9. SELECT 子句展开：`SELECT <别名>` → 列清单（T-HQL-SELECT-01）
 *     HQL 语义 `SELECT u` = 取整个实体，须展开为全部映射列，否则 MySQL 报 1054；
 *     无 SELECT 子句（裸 `from 实体`）补 SELECT 列清单（T-BareFromSelect）
 *     ——展开逻辑见 [SelectClauseExpander]，与 JpqlConverter 共用
 * 10. UPDATE 语句（J2）：`UPDATE 实体 [别名] SET 列 = 值[, ...] [WHERE ...]` →
 *     [UpdateStatement]（SET 对 / WHERE 解析见 [JpaUpdateParser]，不可精细解析的
 *     WHERE 整体作 Raw 条件透传）
 * 11. DELETE 语句 → [DeleteStatement]（既有行为不变）
 *
 * J19 边界（「识别 + 不破坏」）：HQL 高级特性——`INSERT INTO ... SELECT`、`WITH ... SELECT`
 * （CTE）、UNION/INTERSECT/EXCEPT、窗口函数 `OVER (...)`——在字符串管道中天然透传
 * （参数替换/实体替换按文本命中，SELECT 展开器对上述形态已短路不误改，见
 * [SelectClauseExpander]），检测到时仅在 statement 上附降级注释，不新增完整建模。
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
     * @param statement  语句模型（SelectStatement / UpdateStatement / DeleteStatement）
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
     * @param project   项目（J7 JOIN ON 注解级解析 / J13 子查询实体映射用）；
     *                  null 时两处降级为关联元数据 / 跳过，行为与历史版本一致
     * @return Result（转换后 SQL + 语句模型）
     */
    fun convert(hql: String, tableInfo: TableInfo? = null, project: Project? = null): Result {
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

        // 5. 处理 JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件真实化）
        sql = JpaJoinRewriter.rewrite(sql, table, project)

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

        // 8.5 子查询 FROM/JOIN 中其他实体名 → 表名（J13；需 Project，无则跳过）
        sql = JpaFromEntityMapper.mapSubqueryEntities(sql, table, project)

        // 9. SELECT 子句展开（T-HQL-SELECT-01 / T-BareFromSelect）
        // 必须排在步骤 5（JOIN 改写）之后——JOIN 别名只有改写后才存在，
        // 早于该步解析会取不到副表别名。DELETE/UPDATE/INSERT/CTE/UNION 内部已短路。
        sql = SelectClauseExpander.expand(sql, table)

        // 10. 检测 DELETE HQL → DeleteStatement
        if (sql.trimStart().uppercase().startsWith("DELETE")) {
            return Result(
                sql = sql,
                statement = DeleteStatement(table = table, conditions = emptyList()),
                inClauseParams = inClauseParams,
            )
        }

        // 10.5 检测 UPDATE HQL → UpdateStatement（J2）
        if (sql.trimStart().uppercase().startsWith("UPDATE")) {
            val parsed = JpaUpdateParser.parse(sql)
            if (parsed != null) {
                return Result(
                    sql = sql,
                    statement = UpdateStatement(
                        table = table,
                        sets = parsed.sets,
                        conditions = parsed.conditions,
                        tableAlias = parsed.tableAlias,
                    ),
                    inClauseParams = inClauseParams,
                )
            }
            // 头部结构异常（非法 HQL）→ 维持历史 SelectStatement 透传，不抛异常
        }

        return Result(
            sql = sql,
            statement = SelectStatement(table = table, comment = advancedConstructComment(sql)),
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
    // JOIN FETCH / 关联 JOIN（J7：改写逻辑统一在 JpaJoinRewriter，ON 条件真实化）
    // ------------------------------------------------------------------

    /**
     * J19 高级特性降级注释：检测 HQL `WITH ... SELECT`（CTE）、UNION/INTERSECT/EXCEPT、
     * `INSERT INTO ... SELECT`。这些形态在字符串管道中透传（参数替换/实体替换仍生效，
     * SELECT 展开器已短路不误改），不新增完整建模，仅附注释说明边界。
     */
    private fun advancedConstructComment(sql: String): String? {
        val head = sql.trimStart().uppercase()
        val isInsertSelect = head.startsWith("INSERT")
        val hasCte = SelectClauseExpander.hasTopLevelKeyword(sql, "WITH")
        val hasSetOp = SelectClauseExpander.hasTopLevelKeyword(sql, "UNION") ||
            SelectClauseExpander.hasTopLevelKeyword(sql, "INTERSECT") ||
            SelectClauseExpander.hasTopLevelKeyword(sql, "EXCEPT")
        return if (isInsertSelect || hasCte || hasSetOp) {
            "/* HQL 高级特性（INSERT-SELECT / CTE / UNION 等），仅做参数与实体替换透传 */"
        } else {
            null
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
