package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import com.intellij.openapi.project.Project

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
 *  - `JOIN FETCH e.relation r` / `JOIN e.relation r` → `LEFT/INNER JOIN table r ON ...`
 *    （J7：ON 条件经 [JpaJoinOnResolver] 按「PSI 注解 > 关联元数据 > 猜测降级」三级真实化）
 *  - `UPDATE <Entity> [alias] SET ... [WHERE ...]` → [UpdateStatement]（J2）
 *  - `NEW com.xxx.VO(a, b)` → `SELECT a, b`
 *  - `MEMBER OF` → 降级提示
 *
 * 对于复杂 JPQL（子查询、MEMBER OF、NEW DTO(...)），展示基础转换 + 注释提示。
 *
 * J13 子查询边界：EXISTS / IN (SELECT ...) 依赖字符串透传——命名/位置参数替换、
 * 实体表名替换、属性列名替换均为全文正则，子查询内同名符号天然覆盖；
 * 有 Project 时由 [JpaFromEntityMapper] 额外映射子查询 FROM 中的其他实体名。
 *
 * J19 高级特性边界（「识别 + 不破坏」）：UNION/INTERSECT/EXCEPT、WITH CTE、
 * INSERT...SELECT、窗口函数 OVER(...) 在本管道中原样透传（参数/实体替换仍生效，
 * SELECT 展开器已短路不误改），检测到时仅附降级注释，不新增完整建模。
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
object JpqlConverter {
    // T-Perf-23：函数体内 Regex(...) 提为常量（热路径免重复编译 Pattern）
    private val JPQL_NAMED_PARAM_TO_Q = Regex("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)")
    private val JPQL_POSITIONAL_PARAM_TO_Q = Regex("\\?\\d+")
    private val JPQL_IN_SPACE_Q = Regex("(?i)\\bIN\\s+\\?")
    private val JPQL_MEMBER_OF_PATTERN = Regex("(.+)\\s+MEMBER OF\\s+(.+)", RegexOption.IGNORE_CASE)


    /**
     * JPQL 转换产物：转换后的 SQL 字符串（含 ? 占位符）+ 语句模型。
     *
     * @param sql        转换后的 SQL 字符串（命名参数已替换为 ?，可直接用于面板参数回填）
     * @param statement  语句模型（SelectStatement / UpdateStatement / DeleteStatement，用于 previewModel）
     * @param inClauseParams 使用 IN 子句的参数名集合（面板回填时逗号分割多值）
     */
    data class Result(
        val sql: String,
        val statement: SqlStatement,
        val inClauseParams: Set<String> = emptySet(),
    )

    /**
     * 转换 JPQL 字符串为 SQL + 语句模型。
     *
     * @param jpql      JPQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做简单字符串替换
     * @param project   项目（J7 JOIN ON 注解级解析 / J13 子查询实体映射用）；
     *                  null 时两处降级为关联元数据 / 跳过，行为与历史版本一致
     * @return Result（转换后 SQL + 语句模型）
     */
    fun convert(jpql: String, tableInfo: TableInfo? = null, project: Project? = null): Result {
        return convertInternal(jpql, tableInfo, project)
    }

    /**
     * 内部转换实现。
     *
     * 关键设计：返回的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
     * JPQL 转换器在字符串层面完成参数替换（:param → ?），但不会解析条件树
     * （conditions 为空），因此必须使用转换后的 SQL 字符串而非重新生成。
     */
    private fun convertInternal(jpql: String, tableInfo: TableInfo?, project: Project?): Result {
        val table = tableInfo ?: createFallbackTable()

        // 0. 检测 IN 子句参数（在命名参数替换前检测 :paramName）
        val inClauseParams = findInClauseParams(jpql)

        // 1. 替换命名参数 :param → ?
        var sql = replaceNamedParameters(jpql)

        // 2. 替换位置参数 ?1 → ?
        sql = replacePositionalParameters(sql)

        // 2.5 处理 IN 子句：IN ? → IN (?)（集合参数应展开为多占位符）
        sql = handleInClause(sql)

        // 3. 处理 NEW DTO(...) 表达式
        sql = handleNewExpression(sql)

        // 4. 处理 JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件真实化）
        sql = JpaJoinRewriter.rewrite(sql, table, project)

        // 5. 处理 MEMBER OF（降级为注释提示）
        sql = handleMemberOf(sql)

        // 5.5 别名作用域解析（O-57）：须在实体名→表名替换之前扫描（那时实体名还在 SQL 里），
        // 又须在第 4 步 JOIN 改写之后（改写新增的副表别名只有那时才存在）。
        // 与 [HqlConverter] 同款编排，两条链行为一致
        val scope = JpaAliasTableResolver.resolveAliases(sql, table, project)

        // 6. 实体名 → 表名映射
        sql = mapEntityToTable(sql, table)

        // 6.5 子查询 FROM/JOIN 中其他实体名 → 表名（J13；需 Project，无则跳过）。
        // 别名作用域已解析出实体→表名，透传给扫描器复用，免二次 PSI 检索
        sql = JpaFromEntityMapper.mapSubqueryEntities(sql, table, project, scope.entityTableNames)

        // 7. 属性名 → 列名映射（按别名作用域分派，副表别名同样映射）
        sql = mapPropertyToColumn(sql, table, scope)

        // 7.5 SELECT 子句展开（T-BareFromSelect）：与 HqlConverter 共用展开器，
        // 补齐隐式查询（`from Entity` 无 SELECT）的列清单与 `SELECT u` 裸别名展开，
        // 使 @NamedQuery 路径与 HQL 路径输出一致。DELETE/UPDATE/INSERT/CTE/UNION 内部已短路。
        sql = SelectClauseExpander.expand(sql, table)

        // 8. 检测 DELETE JPQL → DeleteStatement
        if (sql.trimStart().uppercase().startsWith("DELETE")) {
            // T-JpaDeleteWhereGuard：WHERE 接进条件树（与 HqlConverter 同款，两条链行为一致）——
            // 此前 conditions 恒空，buildJpaWarning 对带 WHERE 的 DELETE 误报 BlockAttack 全表删除
            val parsedDelete = JpaUpdateParser.parseDelete(sql)
            return Result(
                sql = sql,
                statement = DeleteStatement(
                    table = table,
                    conditions = parsedDelete?.conditions ?: emptyList(),
                ),
                inClauseParams = inClauseParams,
            )
        }

        // 8.5 检测 UPDATE JPQL → UpdateStatement（J2）
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
            // 头部结构异常（非法 JPQL）→ 维持历史 SelectStatement 透传，不抛异常
        }

        return Result(
            sql = sql,
            statement = SelectStatement(
                table = table,
                comment = if (hasComplexConstruct(jpql) || hasAdvancedConstruct(sql)) {
                    "/* JPQL 含复杂语法，仅做基础转换 */"
                } else {
                    null
                },
            ),
            inClauseParams = inClauseParams,
        )
    }

    /**
     * 检测 JPQL 中使用 IN 子句的命名参数（`:paramName` 跟在 `IN` 关键字后）。
     *
     * 匹配 `IN :paramName` 模式，返回参数名集合。
     * 面板回填时这些参数需按逗号分割多值渲染为 `IN (v1, v2, ...)`。
     */
    fun findInClauseParams(jpql: String): Set<String> {
        val pattern = Regex("(?i)\\bIN\\s+:([a-zA-Z_][a-zA-Z0-9_]*)")
        return pattern.findAll(jpql).map { it.groupValues[1] }.toSet()
    }

    /**
     * 替换命名参数 `:paramName` → `?`。
     *
     * JPQL 命名参数格式为 `:paramName`（冒号后跟标识符）。
     */
    private fun replaceNamedParameters(jpql: String): String {
        // 匹配 :identifier（冒号后跟字母/数字/下划线），但排除 ::（类型转换）
        return jpql.replace(JPQL_NAMED_PARAM_TO_Q, "?")
    }

    /**
     * 替换位置参数 `?1`, `?2` 等 → `?`。
     *
     * JPQL 位置参数格式为 `?数字`。
     */
    private fun replacePositionalParameters(jpql: String): String {
        return jpql.replace(JPQL_POSITIONAL_PARAM_TO_Q, "?")
    }

    /**
     * 处理 `IN ?` → `IN (?)`。
     *
     * 参数替换后 `IN :param` 变为 `IN ?`，但 SQL 标准写法是 `IN (?)`。
     * 此处在字符串层面修正，确保面板参数回填时正确展开为多占位符。
     */
    private fun handleInClause(jpql: String): String {
        return jpql.replace(JPQL_IN_SPACE_Q, "IN (?)")
    }

    /**
     * 处理 `NEW com.xxx.VO(a, b)` 表达式。
     *
     * JPQL 的构造函数表达式映射为 SELECT 列提取。
     * 示例：`SELECT NEW com.example.UserDTO(u.name, u.age) FROM User u`
     * → `SELECT u.name, u.age FROM User u`
     *
     * 支持嵌套括号（如 `NEW DTO(o.id, COUNT(o), SUM(o.totalAmount))`）——
     * 使用括号计数法找到匹配的闭合括号，避免正则 `[^)]+` 在嵌套括号处截断。
     */
    private fun handleNewExpression(jpql: String): String {
        val prefix = "NEW"
        var searchStart = 0
        while (true) {
            val newIndex = jpql.indexOf(prefix, searchStart)
            if (newIndex < 0) break
            // 找到 NEW 后的第一个 '('
            val parenOpen = jpql.indexOf('(', newIndex + prefix.length)
            if (parenOpen < 0) break
            // 括号计数法找到匹配的 ')'
            var depth = 1
            var i = parenOpen + 1
            while (i < jpql.length && depth > 0) {
                when (jpql[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            if (depth != 0) break // 括号不匹配，跳过
            val parenClose = i - 1
            // 提取括号内的参数列表
            val args = jpql.substring(parenOpen + 1, parenClose).trim()
            // 替换 `NEW ClassName(args)` → `args`
            val replacement = jpql.substring(0, newIndex) + args + jpql.substring(parenClose + 1)
            return handleNewExpression(replacement) // 递归处理多个 NEW（罕见）
        }
        return jpql
    }

    // ------------------------------------------------------------------
    // JOIN FETCH / 关联 JOIN（J7：改写逻辑统一在 JpaJoinRewriter，ON 条件真实化）
    // ------------------------------------------------------------------

    /**
     * J19 高级特性检测（「识别」半边）：顶层 WITH（CTE）/ UNION / INTERSECT / EXCEPT
     * 或 INSERT...SELECT 头部。这些形态在管道中透传且 SELECT 展开器已短路（不破坏），
     * 检测结果仅用于附加降级注释。
     */
    fun hasAdvancedConstruct(sql: String): Boolean {
        val head = sql.trimStart().uppercase()
        if (head.startsWith("INSERT")) return true
        return SelectClauseExpander.hasTopLevelKeyword(sql, "WITH") ||
            SelectClauseExpander.hasTopLevelKeyword(sql, "UNION") ||
            SelectClauseExpander.hasTopLevelKeyword(sql, "INTERSECT") ||
            SelectClauseExpander.hasTopLevelKeyword(sql, "EXCEPT")
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
                JPQL_MEMBER_OF_PATTERN,
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
     *
     * 本重载不携带别名作用域（回落「只看根实体列清单」的历史行为）；
     * 转换管道内一律走 [mapPropertyToColumn] 的作用域重载。
     *
     * O-57 去掉了旧实现里的 `mappedColumnNames` 守卫（逐列「已映射列名不再处理」）：
     * 独立复核用探针证实它**两头不讨好**——既漏映射（列 `statusText→status` 时
     * `u.statusText` 不替换），又没挡住链式双重映射（`a→b, b→c` 时 `x.a` 变成 `x.c`）；
     * 新实现单遍扫描、按属性名查列、不做链式替换，该守卫已无存在理由，不要恢复。
     */
    fun mapPropertyToColumn(jpql: String, table: TableInfo): String =
        mapPropertyToColumn(jpql, table, JpaAliasScope.EMPTY)

    /**
     * 作用域重载（O-57）：替换核心见 [JpaAliasPropertyMapper]（与 [HqlConverter] 共用同一份实现），
     * 副表别名的属性按该实体列清单映射。
     */
    internal fun mapPropertyToColumn(jpql: String, table: TableInfo, scope: JpaAliasScope): String {
        val mapped = JpaAliasPropertyMapper.mapProperties(jpql, table, scope)
        return expandSelectAliases(mapped, table)
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
     *
     * 字面量口径复用 [JpaAliasPropertyMapper.STRING_LITERAL]（本仓唯一一份单引号字面量正则），
     * 避免各处各写一份 `'[^']*'` 后语义漂移。
     */
    private fun detectSubquery(jpql: String): Boolean {
        val cleaned = jpql.replace(JpaAliasPropertyMapper.STRING_LITERAL, "''")
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
