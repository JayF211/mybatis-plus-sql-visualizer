package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel
import cn.song.mybatisplus.visualizer.psi.jpa.ExpressionModel
import cn.song.mybatisplus.visualizer.psi.jpa.GroupConnector
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel
import cn.song.mybatisplus.visualizer.psi.jpa.SubqueryModel
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.openapi.project.Project

/**
 * CriteriaQuery → SqlStatement 转换器（F-JPA-02，T-J4/T-J6/T-J7/T-J17）。
 *
 * 将 [CriteriaQueryModel] 中间表示转换为 [SelectStatement] / [UpdateStatement] / [DeleteStatement]，
 * 供 [cn.song.mybatisplus.visualizer.sql.SqlGenerator] 渲染为 SQL。
 *
 * CriteriaBuilder 方法 → Condition 映射：
 *  - cb.equal(a, b)              → Condition.Simple(col, "=", value)
 *  - cb.notEqual(a, b)           → Condition.Simple(col, "!=", value)
 *  - cb.greaterThan/gt(a, b)     → Condition.Simple(col, ">", value)
 *  - cb.greaterThanOrEqualTo/ge  → Condition.Simple(col, ">=", value)
 *  - cb.lessThan/lt(a, b)        → Condition.Simple(col, "<", value)
 *  - cb.lessThanOrEqualTo/le     → Condition.Simple(col, "<=", value)
 *  - cb.between(a, lo, hi)       → Condition.Between(col, false, lo, hi)
 *  - cb.isNull(a)                → Condition.Unary(col, "IS NULL")
 *  - cb.isNotNull(a)             → Condition.Unary(col, "IS NOT NULL")
 *  - cb.like(a, pattern)         → Condition.Like(col, false, pattern, NONE；Criteria 模式自带 %)
 *  - cb.like(a, p, escape)       → Condition.Raw("col LIKE p ESCAPE e")（Condition 无 escape 字段）
 *  - cb.notLike(a, pattern)      → Condition.Like(col, true, pattern, NONE)
 *  - cb.in(a).value(b)           → Condition.In(col, false, listOf(b))
 *  - cb.isTrue/isFalse(a)        → Condition.Simple(col, "=", TRUE/FALSE)
 *  - cb.conjunction/disjunction  → Condition.Raw("1 = 1" / "1 = 0")
 *  - cb.exists(sq)               → Condition.Raw("EXISTS (SELECT ...)")
 *  - path.get("x").in(sq)        → Condition.Raw("x IN (SELECT ...)")
 *  - cb.and(p1, p2)              → Condition.Group(AND, listOf(p1, p2))
 *  - cb.or(p1, p2)               → Condition.Group(OR, listOf(p1, p2))
 *  - cb.not(p)                   → Condition.Group(AND, listOf(p), negated=true)
 *  - 函数表达式左值（LOWER/ABS/CASE/算术等）→ ExpressionComparison → Condition.Simple 列位放表达式文本
 *
 * UPDATE / DELETE（T-J6）：CriteriaUpdate.set(...) → UpdateStatement.sets；
 * CriteriaDelete → DeleteStatement。
 *
 * 无状态单例，纯函数逻辑，可独立单测；不支持的能力以 Raw/注释降级，绝不抛异常。
 */
object CriteriaQueryConverter {

    /**
     * 转换 CriteriaQueryModel 为 SqlStatement。
     *
     * @param model CriteriaQueryLocator 输出的中间模型
     * @param overrideTable 可选的表信息覆盖（测试/外部传入预解析表信息用）；
     *                      null 时按 model.entityClass 或 model.entityClassName 解析
     * @param project 调用方项目（T-TS-07）：实体类不可解析时的**类名兜底推断**按该项目读取
     *   项目级表配置；null = 未提供，兜底推断沿用「首个打开项目」的既有语义（纯单测/无项目上下文）。
     *   JOIN/子查询的类名兜底（[convertJoins]/[renderSubQuery]）恒无 PsiClass，全靠本参数定型
     * @return 按 model.operation 返回 Select / Update / Delete 语句模型；
     *         无法解析实体时返回全 Raw 降级的 SELECT
     */
    fun convert(
        model: CriteriaQueryModel,
        overrideTable: TableInfo? = null,
        project: Project? = null,
    ): SqlStatement {
        val tableInfo = overrideTable ?: resolveTableInfo(model, project)
        return when (model.operation) {
            CriteriaOperation.UPDATE -> convertUpdate(model, tableInfo, project)
            CriteriaOperation.DELETE -> DeleteStatement(
                table = tableInfo,
                conditions = convertPredicates(model.predicates, tableInfo, project),
            )
            CriteriaOperation.SELECT -> convertSelect(model, tableInfo, project)
        }
    }

    private fun convertSelect(model: CriteriaQueryModel, tableInfo: TableInfo, project: Project?): SelectStatement {
        val conditions = convertPredicates(model.predicates, tableInfo, project)
        val groupBy = convertGroupBy(model.groupBys, tableInfo)
        val having = convertHaving(model.havings, tableInfo, project)
        val orderBy = convertOrderBy(model.orderBys, tableInfo)
        val joins = convertJoins(model.joins, tableInfo, project)
        val selectColumns = convertSelectColumns(model.selectColumns, tableInfo)

        return SelectStatement(
            table = tableInfo,
            selectColumns = selectColumns,
            conditions = conditions,
            groupBy = groupBy,
            having = having,
            orderBy = orderBy,
            joins = joins,
            distinct = model.distinct,
        )
    }

    /** CriteriaUpdate → UpdateStatement（T-J6）：set 子句转 SetClause，where 谓词转 conditions。 */
    private fun convertUpdate(model: CriteriaQueryModel, tableInfo: TableInfo, project: Project?): UpdateStatement {
        val conditions = convertPredicates(model.predicates, tableInfo, project)
        val sets = model.setClauses.map { clause ->
            SetClause(
                column = ColumnRef(columnName = resolveColumnName(clause.fieldName, tableInfo)),
                value = sqlValue(clause.valueExpression ?: "null"),
            )
        }
        return UpdateStatement(table = tableInfo, sets = sets, conditions = conditions)
    }

    // ------------------------------------------------------------------
    // 表信息解析
    // ------------------------------------------------------------------

    /**
     * 解析实体表信息。
     *
     * 优先通过 entityClass PsiClass 走 JpaEntityResolver 解析（@Table/@Column 注解，其内部
     * 自带 `entityClass.project`）；不可解析时按命名策略由类名推断表名——此路须 [project]
     * （T-TS-07：显式传入后不再受「首个打开项目」影响）。
     */
    private fun resolveTableInfo(model: CriteriaQueryModel, project: Project?): TableInfo {
        val entityClass = model.entityClass
        if (entityClass != null) {
            return JpaEntityResolver().resolve(entityClass)
        }
        val className = model.entityClassName ?: model.resultClassName ?: "UnknownEntity"
        return TableInfo(
            entityClassName = className,
            tableName = JpaEntityResolver.inferDecoratedTableName(className, project),
            columns = emptyList(),
        )
    }

    /** 子查询实体表信息（仅类名可推断，列映射为空，按命名策略兜底；[project] 见 [convert]）。 */
    private fun resolveSubTableInfo(sub: SubqueryModel, project: Project?): TableInfo {
        val className = sub.entityClassName ?: return TableInfo(
            entityClassName = "UnknownEntity",
            tableName = "unknown_entity",
            columns = emptyList(),
        )
        return TableInfo(
            entityClassName = className,
            tableName = JpaEntityResolver.inferDecoratedTableName(className, project),
            columns = emptyList(),
        )
    }

    // ------------------------------------------------------------------
    // SELECT 列转换
    // ------------------------------------------------------------------

    /**
     * 转换 SELECT 列为 ColumnRef 列表。
     *
     * 规则：
     *  - 空列表 → 空（converter 层全列语义，生成器展开为 SELECT *）
     *  - 普通列 → 按字段名查找实体列映射，找不到时按命名策略兜底
     *  - 聚合列 → ColumnRef.function 设置为聚合函数名：
     *    - COUNT(*) → function="COUNT", columnName="*"
     *    - SUM(field) → function="SUM", columnName=解析后列名
     *    - countDistinct → rawExpr="COUNT(DISTINCT col)"（T-J17）
     *    - sumAsLong/sumAsDouble → rawExpr="SUM(CAST(col AS BIGINT/DOUBLE))"（T-J17）
     *    - 聚合内层复杂表达式 → rawExpr="AVG(COALESCE(age, 0))" 等（T-J17）
     *  - 表达式列（CASE/COALESCE/CONCAT/算术等）→ rawExpr（T-J17）
     */
    private fun convertSelectColumns(
        columns: List<cn.song.mybatisplus.visualizer.psi.jpa.SelectColumn>,
        tableInfo: TableInfo,
    ): List<ColumnRef> {
        if (columns.isEmpty()) return emptyList()
        return columns.map { sel ->
            // 聚合函数处理
            if (sel.aggregate != null) {
                val agg = sel.aggregate
                val compositeExpr: String? = when {
                    agg.castType != null -> {
                        val col = agg.fieldName?.let { resolveColumnName(it, tableInfo) } ?: "*"
                        "${agg.function}(CAST($col AS ${agg.castType}))"
                    }
                    agg.distinct -> {
                        val col = agg.fieldName?.let { resolveColumnName(it, tableInfo) } ?: "*"
                        "${agg.function}(DISTINCT $col)"
                    }
                    else -> agg.expression?.let { "${agg.function}(${renderExpression(it, tableInfo)})" }
                }
                if (compositeExpr != null) {
                    return@map ColumnRef(columnName = compositeExpr, rawExpr = compositeExpr, alias = sel.alias)
                }
                val columnName = when {
                    agg.fieldName == null -> "*" // COUNT(*) 语义
                    else -> resolveColumnName(agg.fieldName, tableInfo)
                }
                return@map ColumnRef(
                    columnName = columnName,
                    function = agg.function,
                    alias = sel.alias,
                )
            }

            // 复杂表达式列（CASE / COALESCE / CONCAT / (a + b) 等，T-J17）
            if (sel.expression != null) {
                val exprText = renderExpression(sel.expression, tableInfo)
                return@map ColumnRef(columnName = exprText, rawExpr = exprText, alias = sel.alias)
            }

            // 普通列处理
            val fieldName = sel.fieldName ?: return@map ColumnRef(columnName = "*")
            val columnName = resolveColumnName(fieldName, tableInfo)
            ColumnRef(columnName = columnName, alias = sel.alias)
        }
    }

    // ------------------------------------------------------------------
    // WHERE 条件转换
    // ------------------------------------------------------------------

    /**
     * 转换谓词列表为 Condition 列表。
     *
     * [project] 仅用于子查询的类名兜底表名推断（T-TS-07），一路透传到 [renderSubQuery]。
     */
    private fun convertPredicates(
        predicates: List<PredicateModel>,
        tableInfo: TableInfo,
        project: Project?,
    ): List<Condition> {
        return predicates.map { convertPredicate(it, tableInfo, project) }
    }

    /**
     * 转换单个 PredicateModel 为 Condition（[project] 见 [convertPredicates]）。
     */
    private fun convertPredicate(
        predicate: PredicateModel,
        tableInfo: TableInfo,
        project: Project?,
    ): Condition = when (predicate) {
        is PredicateModel.Simple -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            Condition.Simple(column, predicate.op, sqlValue(predicate.valueExpression))
        }

        is PredicateModel.IsNull -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            val op = if (predicate.negated) "IS NOT NULL" else "IS NULL"
            Condition.Unary(column, op)
        }

        is PredicateModel.Between -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            Condition.Between(
                column = column,
                negated = false,
                lo = sqlValue(predicate.loExpression),
                hi = sqlValue(predicate.hiExpression),
            )
        }

        is PredicateModel.Like -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            val op = if (predicate.negated) "NOT LIKE" else "LIKE"
            if (predicate.escapeChar != null) {
                // escapeChar 重载：Condition 模型无 escape 字段，整段 Raw（T-J17）
                Condition.Raw(
                    "${column.qualifiedName} $op ${valueToText(predicate.patternExpression)} " +
                        "ESCAPE ${valueToText(predicate.escapeChar)}",
                )
            } else {
                // Criteria 的 LIKE 模式自带通配符，位置语义为 NONE（不二次包裹 %）
                Condition.Like(
                    column = column,
                    negated = predicate.negated,
                    pattern = sqlValue(predicate.patternExpression),
                    position = LikePosition.NONE,
                )
            }
        }

        is PredicateModel.In -> {
            val column = resolveColumnRef(predicate.fieldName, tableInfo)
            val rawValues = predicate.valueExpressions
            // 处理占位符标记（无值时面板可输入多值）
            val values = if (rawValues == listOf("__IN_PLACEHOLDER__")) {
                listOf(SqlValue.Placeholder("statuses"))
            } else {
                rawValues.map { sqlValue(it) }
            }
            Condition.In(
                column = column,
                negated = predicate.negated,
                values = values,
            )
        }

        is PredicateModel.Group -> {
            val connector = when (predicate.connector) {
                GroupConnector.AND -> Connector.AND
                GroupConnector.OR -> Connector.OR
            }
            Condition.Group(
                connector = connector,
                children = predicate.children.map { convertPredicate(it, tableInfo, project) },
                negated = predicate.negated,
            )
        }

        // 表达式比较（T-J17）：LOWER(x) = 'a' / ABS(x) > 1 / LOWER(x) IS NULL 等
        is PredicateModel.ExpressionComparison -> convertExpressionComparison(predicate, tableInfo)

        // 表达式 BETWEEN（T-J17）：Condition 无对应节点，整段 Raw
        is PredicateModel.ExpressionBetween -> Condition.Raw(
            "${renderExpression(predicate.left, tableInfo)} BETWEEN " +
                "${expressionToText(predicate.lo, tableInfo)} AND ${expressionToText(predicate.hi, tableInfo)}",
        )

        // 布尔常量（T-J17）：conjunction=恒真 / disjunction=恒假
        is PredicateModel.BooleanConstant -> Condition.Raw(if (predicate.value) "1 = 1" else "1 = 0")

        // 列对列比较（Join.on / root 与 join 列互比，T-J7）
        is PredicateModel.ColumnEquals -> Condition.ColumnComparison(
            left = ColumnRef(columnName = resolveColumnName(predicate.leftFieldName, tableInfo)),
            right = ColumnRef(columnName = resolveColumnName(predicate.rightFieldName, tableInfo)),
        )

        // EXISTS 子查询（T-J17/J13）：有界渲染，降级注释
        is PredicateModel.Exists -> {
            val subSql = renderSubQuery(predicate.subQuery, project)
            if (subSql != null) {
                Condition.Raw((if (predicate.negated) "NOT " else "") + "EXISTS $subSql")
            } else {
                Condition.Raw(degradedSubqueryComment(predicate.subQuery))
            }
        }

        // 列 IN 子查询（T-J17/J13）
        is PredicateModel.InSubQuery -> {
            val subSql = renderSubQuery(predicate.subQuery, project)
            if (subSql != null) {
                val col = resolveColumnName(predicate.fieldName, tableInfo)
                val op = if (predicate.negated) "NOT IN" else "IN"
                Condition.Raw("$col $op $subSql")
            } else {
                Condition.Raw(degradedSubqueryComment(predicate.subQuery))
            }
        }

        is PredicateModel.Raw -> {
            Condition.Raw(predicate.expressionText)
        }
    }

    /** 表达式比较 → Condition（IS NULL 走 Unary；值右值走 Simple；其余整段 Raw）。 */
    private fun convertExpressionComparison(
        predicate: PredicateModel.ExpressionComparison,
        tableInfo: TableInfo,
    ): Condition {
        val leftSql = renderExpression(predicate.left, tableInfo)
        return when (predicate.op) {
            "IS NULL", "IS NOT NULL" -> Condition.Unary(ColumnRef(columnName = leftSql), predicate.op)
            "LIKE", "NOT LIKE" -> Condition.Simple(
                column = ColumnRef(columnName = leftSql),
                op = predicate.op,
                value = exprToSqlValue(predicate.right),
            )
            else -> {
                val right = predicate.right
                if (right is ExpressionModel.Value) {
                    Condition.Simple(
                        column = ColumnRef(columnName = leftSql),
                        op = predicate.op,
                        value = sqlValue(right.expressionText),
                    )
                } else {
                    // 右值也是表达式（列/函数/CASE）→ Condition 无对应节点，整段 Raw
                    Condition.Raw("$leftSql ${predicate.op} ${expressionToText(right, tableInfo)}")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // GROUP BY 转换
    // ------------------------------------------------------------------

    /**
     * 转换 GroupByModel 列表为 ColumnRef 列表。
     */
    private fun convertGroupBy(
        groupBys: List<cn.song.mybatisplus.visualizer.psi.jpa.GroupByModel>,
        tableInfo: TableInfo,
    ): List<ColumnRef> {
        return groupBys.map { groupBy ->
            val columnName = resolveColumnName(groupBy.fieldName, tableInfo)
            ColumnRef(columnName = columnName)
        }
    }

    // ------------------------------------------------------------------
    // HAVING 转换
    // ------------------------------------------------------------------

    /**
     * 转换 HAVING 谓词列表为单个 Condition（AND 连接）。
     *
     * 规则：
     *  - 空列表 → null（无 HAVING）
     *  - 单条件 → 直接使用
     *  - 多条件 → AND 连接的 Group
     */
    private fun convertHaving(
        havings: List<PredicateModel>,
        tableInfo: TableInfo,
        project: Project?,
    ): Condition? {
        if (havings.isEmpty()) return null
        val conditions = havings.map { convertPredicate(it, tableInfo, project) }
        return if (conditions.size == 1) {
            conditions[0]
        } else {
            Condition.Group(
                connector = Connector.AND,
                children = conditions,
            )
        }
    }

    // ------------------------------------------------------------------
    // ORDER BY 转换
    // ------------------------------------------------------------------

    /**
     * 转换 OrderByModel 列表为 OrderByItem 列表。
     */
    private fun convertOrderBy(
        orderBys: List<cn.song.mybatisplus.visualizer.psi.jpa.OrderByModel>,
        tableInfo: TableInfo,
    ): List<OrderByItem> {
        return orderBys.map { ob ->
            val column = resolveColumnRef(ob.fieldName, tableInfo)
            OrderByItem(column = column, asc = ob.asc)
        }
    }

    // ------------------------------------------------------------------
    // JOIN 转换
    // ------------------------------------------------------------------

    /**
     * 转换 JoinModel 列表为 JoinClause 列表。
     *
     * 规则（T-J7）：
     *  - 显式 ON（Join.on(...)）非空时优先渲染，跳过外键推断；
     *    列别名归属按 get 调用接收者变量判定（join 变量 → join 别名，root/未知 → 主表）
     *  - 无显式 ON → 既有外键推断：alias.id = root_<fk>_column
     *  - fetch 调用与 join 同形渲染（EAGER 抓取合并入主查询的静态近似）
     *
     * T-TS-07：join 表名只有类名字符串（[cn.song.mybatisplus.visualizer.psi.jpa.JoinModel]
     * 不带 PsiClass），恒走类名兜底推断——[project] 是唯一取值口径来源（见 [convert]）。
     */
    private fun convertJoins(
        joins: List<cn.song.mybatisplus.visualizer.psi.jpa.JoinModel>,
        tableInfo: TableInfo,
        project: Project?,
    ): List<JoinClause> {
        return joins.map { join ->
            val entityClassName = join.entityClassName ?: join.fieldName
            val tableName = JpaEntityResolver.inferDecoratedTableName(entityClassName, project)
            // 别名优先级：显式别名 > join 返回值变量名 > 关联属性名首字母小写
            val alias = join.alias ?: join.variableName
                ?: join.fieldName.replaceFirstChar { it.lowercaseChar() }

            val onConditions = if (join.onPredicates.isNotEmpty()) {
                join.onPredicates.map { convertOnPredicate(it, tableInfo, join, alias, project) }
            } else {
                inferJoinOnConditions(join, tableInfo, alias)
            }

            JoinClause(
                type = join.joinType,
                tableName = tableName,
                alias = alias,
                onConditions = onConditions,
            )
        }
    }

    /** 显式 ON 谓词转换：ColumnEquals 按变量归属补表别名，其余谓词走通用转换（[project] 见 [convert]）。 */
    private fun convertOnPredicate(
        predicate: PredicateModel,
        tableInfo: TableInfo,
        join: cn.song.mybatisplus.visualizer.psi.jpa.JoinModel,
        joinAlias: String,
        project: Project?,
    ): Condition {
        if (predicate is PredicateModel.ColumnEquals) {
            return Condition.ColumnComparison(
                left = ColumnRef(
                    tableAlias = aliasForVariable(predicate.leftVariable, join, joinAlias),
                    columnName = resolveColumnName(predicate.leftFieldName, tableInfo),
                ),
                right = ColumnRef(
                    tableAlias = aliasForVariable(predicate.rightVariable, join, joinAlias),
                    columnName = resolveColumnName(predicate.rightFieldName, tableInfo),
                ),
            )
        }
        return convertPredicate(predicate, tableInfo, project)
    }

    /** get 接收者变量 → 表别名：join 变量 → join 别名；root/未知 → 主表（无别名）。 */
    private fun aliasForVariable(
        variable: String?,
        join: cn.song.mybatisplus.visualizer.psi.jpa.JoinModel,
        joinAlias: String,
    ): String? = when (variable) {
        null -> null
        join.variableName -> joinAlias
        else -> null
    }

    /**
     * 推断 CriteriaQuery JOIN 的 ON 条件（显式 ON 缺省时的回退）。
     *
     * 启发式规则（JPA @JoinColumn 默认命名）：
     *  - root.join("department") → 从主实体 department 属性推断外键列
     *    - 查找主实体 TableInfo 中名为 "department" 的字段
     *    - 若无精确匹配，按命名策略生成：department → department_id
     *  - ON 条件: alias.id = root_fk_column
     */
    private fun inferJoinOnConditions(
        join: cn.song.mybatisplus.visualizer.psi.jpa.JoinModel,
        tableInfo: TableInfo,
        alias: String,
    ): List<Condition> {
        val fieldName = join.fieldName

        // 策略 1：在主实体列映射中查找同名字段
        val matchedColumn = tableInfo.columnByField(fieldName)
        if (matchedColumn != null) {
            return listOf(
                Condition.ColumnComparison(
                    left = ColumnRef(columnName = "id", tableAlias = alias),
                    right = ColumnRef(columnName = matchedColumn.columnName),
                ),
            )
        }

        // 策略 2：按命名策略推断外键列名
        val inferredFkColumn = NamingStrategy.toSnakeCase(fieldName) + "_id"
        return listOf(
            Condition.ColumnComparison(
                left = ColumnRef(columnName = "id", tableAlias = alias),
                right = ColumnRef(columnName = inferredFkColumn),
            ),
        )
    }

    // ------------------------------------------------------------------
    // 子查询渲染（T-J17/J13 有界实现）
    // ------------------------------------------------------------------

    /**
     * 渲染子查询为 `(SELECT col FROM t WHERE ...)` 文本。
     *
     * 有界：degradedReason 非空（correlate / 多层嵌套）或谓词无法全部静态渲染时返回 null，
     * 由调用方降级注释。占位符以 `?` 文本呈现（不参与面板参数收集，属已知边界）。
     *
     * [project] 透传给子查询表名的类名兜底推断（T-TS-07，子查询模型无 PsiClass）。
     */
    private fun renderSubQuery(sub: SubqueryModel, project: Project?): String? {
        if (sub.degradedReason != null) return null
        val subTable = resolveSubTableInfo(sub, project)
        val selectColumn = sub.selectFieldName?.let { resolveColumnName(it, subTable) } ?: "*"
        val whereParts = sub.predicates.map { predicateToSqlText(it, subTable) }
        // 有界：谓词无法全部渲染（嵌套子查询等）→ 整体降级
        if (whereParts.any { it == null }) return null
        return buildString {
            append("(SELECT ").append(selectColumn).append(" FROM ").append(subTable.tableName)
            val parts = whereParts.filterNotNull()
            if (parts.isNotEmpty()) append(" WHERE ").append(parts.joinToString(" AND "))
            append(")")
        }
    }

    /** 不可静态渲染子查询的注释降级文本。 */
    private fun degradedSubqueryComment(sub: SubqueryModel): String =
        "/* 子查询暂不支持静态解析${sub.degradedReason?.let { "：$it" } ?: ""} */"

    /**
     * 谓词 → SQL 文本（子查询 WHERE 渲染用）。
     * EXISTS / IN 子查询节点在此返回 null（子查询内不再嵌套子查询——有界边界）。
     */
    private fun predicateToSqlText(predicate: PredicateModel, tableInfo: TableInfo): String? = when (predicate) {
        is PredicateModel.Simple ->
            "${resolveColumnName(predicate.fieldName, tableInfo)} ${predicate.op} ${valueToText(predicate.valueExpression)}"

        is PredicateModel.IsNull ->
            "${resolveColumnName(predicate.fieldName, tableInfo)} ${if (predicate.negated) "IS NOT NULL" else "IS NULL"}"

        is PredicateModel.Between ->
            "${resolveColumnName(predicate.fieldName, tableInfo)} BETWEEN " +
                "${valueToText(predicate.loExpression)} AND ${valueToText(predicate.hiExpression)}"

        is PredicateModel.Like -> {
            val op = if (predicate.negated) "NOT LIKE" else "LIKE"
            val escape = predicate.escapeChar?.let { " ESCAPE ${valueToText(it)}" } ?: ""
            "${resolveColumnName(predicate.fieldName, tableInfo)} $op ${valueToText(predicate.patternExpression)}$escape"
        }

        is PredicateModel.In -> {
            val op = if (predicate.negated) "NOT IN" else "IN"
            val values = predicate.valueExpressions.joinToString(", ") { valueToText(it) }
            "${resolveColumnName(predicate.fieldName, tableInfo)} $op ($values)"
        }

        is PredicateModel.ColumnEquals ->
            "${resolveColumnName(predicate.leftFieldName, tableInfo)} ${predicate.op} " +
                resolveColumnName(predicate.rightFieldName, tableInfo)

        is PredicateModel.BooleanConstant -> if (predicate.value) "1 = 1" else "1 = 0"

        is PredicateModel.ExpressionComparison -> {
            val left = renderExpression(predicate.left, tableInfo)
            when (predicate.op) {
                "IS NULL", "IS NOT NULL" -> "$left ${predicate.op}"
                else -> "$left ${predicate.op} ${expressionToText(predicate.right, tableInfo)}"
            }
        }

        is PredicateModel.ExpressionBetween ->
            "${renderExpression(predicate.left, tableInfo)} BETWEEN " +
                "${expressionToText(predicate.lo, tableInfo)} AND ${expressionToText(predicate.hi, tableInfo)}"

        is PredicateModel.Group -> {
            val children = predicate.children.map { predicateToSqlText(it, tableInfo) }
            if (children.any { it == null }) return null
            val joined = children.filterNotNull().joinToString(" ${predicate.connector.name} ")
            if (joined.isEmpty()) null else if (predicate.negated) "NOT ($joined)" else "($joined)"
        }

        is PredicateModel.Raw -> predicate.expressionText

        // 子查询内嵌子查询：有界边界，返回 null 令外层整体降级
        is PredicateModel.Exists, is PredicateModel.InSubQuery -> null
    }

    // ------------------------------------------------------------------
    // 表达式渲染（T-J17）
    // ------------------------------------------------------------------

    /**
     * ExpressionModel → SQL 文本。
     *
     * 函数标记映射：
     *  - ADD/SUB/MUL/DIV → (a + b) 等括号中缀；MOD → MOD(a, b)；NEG → (-a)
     *  - CAST → CAST(a AS 类型)（modifier 携带目标类型）
     *  - TRIM → TRIM([规格 FROM] a)（modifier 携带 Trimspec）
     *  - COUNT → COUNT(a)，无参时 COUNT(*)
     *  - NOT → NOT (a)
     *  - 其余原样 NAME(a, b, ...)
     */
    private fun renderExpression(expr: ExpressionModel, tableInfo: TableInfo): String = when (expr) {
        is ExpressionModel.Column -> resolveColumnName(expr.fieldName, tableInfo)

        is ExpressionModel.Value -> valueToText(expr.expressionText)

        is ExpressionModel.Raw -> expr.expressionText

        is ExpressionModel.Function -> {
            val args = expr.args.map { renderExpression(it, tableInfo) }
            when (expr.function) {
                "ADD" -> "(${args.getOrElse(0) { "" }} + ${args.getOrElse(1) { "" }})"
                "SUB" -> "(${args.getOrElse(0) { "" }} - ${args.getOrElse(1) { "" }})"
                "MUL" -> "(${args.getOrElse(0) { "" }} * ${args.getOrElse(1) { "" }})"
                "DIV" -> "(${args.getOrElse(0) { "" }} / ${args.getOrElse(1) { "" }})"
                "MOD" -> "MOD(${args.getOrElse(0) { "" }}, ${args.getOrElse(1) { "" }})"
                "NEG" -> "(-${args.getOrElse(0) { "" }})"
                "NOT" -> "NOT (${args.joinToString(", ")})"
                "CAST" -> "CAST(${args.getOrElse(0) { "" }} AS ${expr.modifier ?: "VARCHAR"})"
                "TRIM" ->
                    if (expr.modifier != null) "TRIM(${expr.modifier} FROM ${args.getOrElse(0) { "" }})"
                    else "TRIM(${args.getOrElse(0) { "" }})"
                "COUNT" -> "COUNT(${args.joinToString(", ").ifEmpty { "*" }})"
                else -> "${expr.function}(${args.joinToString(", ")})"
            }
        }

        is ExpressionModel.Case -> renderCase(expr, tableInfo) ?: "/* CASE 表达式暂不支持静态渲染 */"
    }

    /** CASE 表达式渲染；条件/结果无法渲染时返回 null（调用方降级注释）。 */
    private fun renderCase(expr: ExpressionModel.Case, tableInfo: TableInfo): String? {
        val base = expr.simpleExpr?.let { renderExpression(it, tableInfo) }
        val parts = expr.whens.map { caseWhen ->
            val conditionSql = if (base != null) {
                caseWhen.condition?.let { renderExpression(it, tableInfo) }
            } else {
                caseWhen.predicate?.let { predicateToSqlText(it, tableInfo) }
            } ?: return null
            val resultSql = caseWhen.result?.let { renderExpression(it, tableInfo) } ?: return null
            "WHEN $conditionSql THEN $resultSql"
        }
        if (parts.isEmpty()) return null
        return buildString {
            append("CASE ")
            if (base != null) append(base).append(" ")
            append(parts.joinToString(" "))
            expr.otherwise?.let { append(" ELSE ").append(renderExpression(it, tableInfo)) }
            append(" END")
        }
    }

    /** 值源码文本 → SQL 文本（字面量原样含引号；变量/占位符 → `?`）。 */
    private fun valueToText(expression: String): String {
        val value = sqlValue(expression)
        return when (value) {
            is SqlValue.Literal -> value.raw
            is SqlValue.Placeholder -> "?"
        }
    }

    /** 表达式 → SQL 文本（Value 走值求值，其余走表达式渲染）。 */
    private fun expressionToText(expr: ExpressionModel, tableInfo: TableInfo): String = when (expr) {
        is ExpressionModel.Value -> valueToText(expr.expressionText)
        else -> renderExpression(expr, tableInfo)
    }

    /** 表达式右值 → SqlValue（Simple/Like 条件的参数面板语义保留）。 */
    private fun exprToSqlValue(expr: ExpressionModel): SqlValue = when (expr) {
        is ExpressionModel.Value -> sqlValue(expr.expressionText)
        else -> SqlValue.Placeholder("expression")
    }

    // ------------------------------------------------------------------
    // 列名解析
    // ------------------------------------------------------------------

    /**
     * 解析实体字段名 → ColumnRef。
     *
     * 优先从 TableInfo 的列映射查找（按 fieldName）；找不到时按驼峰转下划线兜底。
     */
    private fun resolveColumnRef(fieldName: String, tableInfo: TableInfo): ColumnRef {
        val columnName = resolveColumnName(fieldName, tableInfo)
        return ColumnRef(columnName = columnName)
    }

    /**
     * 解析实体字段名 → 数据库列名。
     */
    private fun resolveColumnName(fieldName: String, tableInfo: TableInfo): String {
        val matched = tableInfo.columnByField(fieldName)
        return matched?.columnName ?: NamingStrategy.toSnakeCase(fieldName)
    }

    // ------------------------------------------------------------------
    // 值表达式求值
    // ------------------------------------------------------------------

    /**
     * 把 PSI 表达式文本转为 SqlValue。
     *
     * 简单启发式：
     *  - 字符串字面量（单/双引号包裹）→ Literal（去引号，渲染时重新包裹）
     *  - 数字字面量 → Literal（去 L/F/D 类型后缀）
     *  - null → Literal("NULL")
     *  - true/false/TRUE/FALSE → Literal（统一大写布尔字面量）
     *  - 其他 → Placeholder（运行时值）
     */
    private fun sqlValue(expression: String): SqlValue {
        val trimmed = expression.trim()

        // null 字面量
        if (trimmed == "null") return SqlValue.Literal("NULL")

        // 布尔（isTrue/isFalse 产物为 TRUE/FALSE，统一大写）
        if (trimmed.equals("true", ignoreCase = true) || trimmed.equals("false", ignoreCase = true)) {
            return SqlValue.Literal(trimmed.uppercase())
        }

        // 字符串字面量（单引号或双引号，且不含 + 拼接）
        // 注意：含 + 的表达式（如 "%" + username + "%）是运行时拼接，应视为占位符
        if (!trimmed.contains("+") &&
            ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
                (trimmed.startsWith("\"") && trimmed.endsWith("\"")))
        ) {
            val unquoted = trimmed.substring(1, trimmed.length - 1)
            return SqlValue.Literal(
                raw = "'" + unquoted.replace("'", "''") + "'",
                unquoted = unquoted,
            )
        }

        // 数字（且不含 + 拼接）
        if (!trimmed.contains("+") && trimmed.matches(CQC_NUMERIC_LITERAL)) {
            // 去除类型后缀 L/F/D
            val numeric = trimmed.trimEnd('L', 'l', 'F', 'f', 'D', 'd')
            return SqlValue.Literal(numeric)
        }

        // 运行时值 → 占位符（含变量引用、字符串拼接、方法调用等）
        return SqlValue.Placeholder(trimmed)
    }
    // T-Perf-23：函数体内 Regex(...) 提为常量（热路径免重复编译 Pattern）
    private val CQC_NUMERIC_LITERAL = Regex("^-?\\d+(\\.\\d+)?[LlFfDd]?$")

}
