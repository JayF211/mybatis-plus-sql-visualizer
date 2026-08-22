package cn.song.mybatisplus.visualizer.sql.perf.rules

import cn.song.mybatisplus.visualizer.sql.perf.PerfRule
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.MySQLGroupConcat
import net.sf.jsqlparser.expression.operators.conditional.AndExpression
import net.sf.jsqlparser.expression.operators.conditional.OrExpression
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.expression.operators.relational.InExpression
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Join
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select

/**
 * 第二批性能规则（需要 JSqlParser AST 解析，共 8 条）。
 */
object PerfRulesBatch2 {

    val all: List<PerfRule> = listOf(
        JoinWithoutOnRule,
        SubqueryDepthRule,
        InClauseOverflowRule,
        FunctionOnColumnRule,
        OrConditionCountRule,
        DistinctWithJoinRule,
        GroupByWithoutAggregateRule,
        NoLimitLargeResultRule,
    )

    object JoinWithoutOnRule : PerfRule {
        override val ruleId = "join_without_on"
        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                val hasBadJoin = select.joins?.any { join ->
                    join.isSimpleJoinWithoutOn()
                } ?: false
                if (hasBadJoin) {
                    warnings += PerfWarning(
                        ruleId = ruleId,
                        severity = PerfSeverity.ERROR,
                        messageKey = "perf.join_without_on",
                    )
                }
            }
            return warnings.take(1)
        }
    }

    object SubqueryDepthRule : PerfRule {
        override val ruleId = "subquery_depth"
        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                val maxDepth = computeSubqueryDepth(select, 0)
                if (maxDepth > 2) {
                    warnings += PerfWarning(
                        ruleId = ruleId,
                        severity = PerfSeverity.WARN,
                        messageKey = "perf.subquery_depth",
                        messageArgs = listOf(maxDepth.toString()),
                    )
                }
            }
            return warnings.take(1)
        }
    }

    object InClauseOverflowRule : PerfRule {
        override val ruleId = "in_clause_overflow"
        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                if (select.where != null) {
                    val count = countInOverflows(select.where)
                    if (count > 0) {
                        warnings += PerfWarning(
                            ruleId = ruleId,
                            severity = PerfSeverity.WARN,
                            messageKey = "perf.in_clause_overflow",
                            messageArgs = listOf(count.toString()),
                        )
                    }
                }
            }
            return warnings.take(1)
        }
    }

    object FunctionOnColumnRule : PerfRule {
        override val ruleId = "function_on_column"
        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                if (select.where != null) {
                    val funcs = findFunctionOnColumn(select.where)
                    if (funcs.isNotEmpty()) {
                        warnings += PerfWarning(
                            ruleId = ruleId,
                            severity = PerfSeverity.WARN,
                            messageKey = "perf.function_on_column",
                            messageArgs = funcs,
                        )
                    }
                }
            }
            return warnings.take(1)
        }
    }

    object OrConditionCountRule : PerfRule {
        override val ruleId = "or_condition_count"
        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                if (select.where != null) {
                    val orCount = countOrExpressions(select.where)
                    if (orCount > 3) {
                        warnings += PerfWarning(
                            ruleId = ruleId,
                            severity = PerfSeverity.INFO,
                            messageKey = "perf.or_condition_count",
                            messageArgs = listOf(orCount.toString()),
                        )
                    }
                }
            }
            return warnings.take(1)
        }
    }

    object DistinctWithJoinRule : PerfRule {
        override val ruleId = "distinct_with_join"
        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                val isDistinct = select.distinct != null
                val hasJoin = !select.joins.isNullOrEmpty()
                if (isDistinct && hasJoin) {
                    warnings += PerfWarning(
                        ruleId = ruleId,
                        severity = PerfSeverity.INFO,
                        messageKey = "perf.distinct_with_join",
                    )
                }
            }
            return warnings.take(1)
        }
    }

    object GroupByWithoutAggregateRule : PerfRule {
        override val ruleId = "group_by_without_aggregate"
        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                val hasGroupBy = select.groupBy != null &&
                    !select.groupBy.groupByExpressionList.isNullOrEmpty()
                val hasAggregate = hasAggregateFunction(select)
                if (hasGroupBy && !hasAggregate) {
                    warnings += PerfWarning(
                        ruleId = ruleId,
                        severity = PerfSeverity.INFO,
                        messageKey = "perf.group_by_without_aggregate",
                    )
                }
            }
            return warnings.take(1)
        }
    }

    object NoLimitLargeResultRule : PerfRule {
        override val ruleId = "no_limit_large_result"
        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                val noLimit = select.limit == null
                val joinCount = select.joins?.size ?: 0
                val noWhere = select.where == null
                if (noLimit && joinCount >= 2 && noWhere) {
                    warnings += PerfWarning(
                        ruleId = ruleId,
                        severity = PerfSeverity.WARN,
                        messageKey = "perf.no_limit_large_result",
                    )
                }
            }
            return warnings.take(1)
        }
    }

    // ------------------------------------------------------------------
    // 辅助函数
    // ------------------------------------------------------------------

    /** 收集语句树中所有 PlainSelect（含子查询中的）。 */
    private fun collectSelects(ast: Statement): List<PlainSelect> {
        val result = mutableListOf<PlainSelect>()
        when (ast) {
            is PlainSelect -> result += ast
            is net.sf.jsqlparser.statement.select.SetOperationList -> {
                ast.selects.forEach { sel ->
                    unwrapPlainSelect(sel)?.let { result += it }
                }
            }
            is ParenthesedSelect -> unwrapPlainSelect(ast.select)?.let { result += it }
        }
        val subQueries = mutableListOf<PlainSelect>()
        for (ps in result) {
            extractSubQueries(ps, subQueries)
        }
        result += subQueries
        return result
    }

    private fun unwrapPlainSelect(select: Select?): PlainSelect? {
        return when (select) {
            is PlainSelect -> select
            is ParenthesedSelect -> unwrapPlainSelect(select.select)
            else -> null
        }
    }

    private fun extractSubQueries(select: PlainSelect, out: MutableList<PlainSelect>) {
        val fromItem = select.fromItem
        if (fromItem is ParenthesedSelect) {
            val sub = fromItem.select
            if (sub is PlainSelect) {
                out += sub
                extractSubQueries(sub, out)
            }
        }
        findWhereSubqueries(select.where, out)
    }

    private fun findWhereSubqueries(expr: Expression?, out: MutableList<PlainSelect>) {
        if (expr == null) return
        when (expr) {
            is ParenthesedSelect -> {
                val ps = unwrapPlainSelect(expr.select)
                if (ps != null) {
                    out += ps
                    extractSubQueries(ps, out)
                }
            }
            is AndExpression -> {
                findWhereSubqueries(expr.leftExpression, out)
                findWhereSubqueries(expr.rightExpression, out)
            }
            is OrExpression -> {
                findWhereSubqueries(expr.leftExpression, out)
                findWhereSubqueries(expr.rightExpression, out)
            }
            is InExpression -> {
                // IN (子查询) → 检查右侧是否为子查询
                findWhereSubqueries(expr.rightExpression, out)
                findWhereSubqueries(expr.leftExpression, out)
            }
            is net.sf.jsqlparser.expression.operators.relational.ExistsExpression -> {
                findWhereSubqueries(expr.rightExpression, out)
            }
            else -> {}
        }
    }

    private fun Join.isSimpleJoinWithoutOn(): Boolean {
        val hasOn = onExpressions != null && onExpressions.isNotEmpty()
        val hasUsing = usingColumns != null && usingColumns.isNotEmpty()
        return !hasOn && !hasUsing && !isNatural && !isCross && !isSemi &&
            !isRight && !isFull && !isLeft && !isSimple
    }

    private fun computeSubqueryDepth(select: PlainSelect, currentDepth: Int): Int {
        var maxDepth = currentDepth
        val fromItem = select.fromItem
        if (fromItem is ParenthesedSelect) {
            val sub = fromItem.select
            if (sub is PlainSelect) {
                maxDepth = maxOf(maxDepth, computeSubqueryDepth(sub, currentDepth + 1))
            }
        }
        select.where?.let { where ->
            findWhereSubqueryDepths(where) { depth ->
                maxDepth = maxOf(maxDepth, currentDepth + 1 + depth)
            }
        }
        return maxDepth
    }

    private fun findWhereSubqueryDepths(expr: Expression, callback: (Int) -> Unit) {
        when (expr) {
            is ParenthesedSelect -> {
                val sub = expr.select
                if (sub is PlainSelect) {
                    callback(computeSubqueryDepth(sub, 0))
                }
            }
            is AndExpression -> {
                findWhereSubqueryDepths(expr.leftExpression, callback)
                findWhereSubqueryDepths(expr.rightExpression, callback)
            }
            is OrExpression -> {
                findWhereSubqueryDepths(expr.leftExpression, callback)
                findWhereSubqueryDepths(expr.rightExpression, callback)
            }
            is InExpression -> {
                findWhereSubqueryDepths(expr.rightExpression, callback)
                findWhereSubqueryDepths(expr.leftExpression, callback)
            }
            is net.sf.jsqlparser.expression.operators.relational.ExistsExpression -> {
                findWhereSubqueryDepths(expr.rightExpression, callback)
            }
            else -> {}
        }
    }

    private fun countInOverflows(expr: Expression): Int {
        var count = 0
        when (expr) {
            is InExpression -> {
                val rightItems = expr.rightExpression
                if (rightItems is ExpressionList<*>) {
                    if (rightItems.size > 100) count++
                }
            }
            is AndExpression -> {
                count += countInOverflows(expr.leftExpression)
                count += countInOverflows(expr.rightExpression)
            }
            is OrExpression -> {
                count += countInOverflows(expr.leftExpression)
                count += countInOverflows(expr.rightExpression)
            }
            else -> {}
        }
        return count
    }

    private fun findFunctionOnColumn(expr: Expression): List<String> {
        val result = mutableListOf<String>()
        when (expr) {
            is Function -> {
                expr.parameters?.forEach { param ->
                    if (param is Column) {
                        result += expr.name + "(" + param.columnName + ")"
                    }
                }
                // Also check nested parameters for functions
                expr.parameters?.forEach { param ->
                    result += findFunctionOnColumn(param)
                }
            }
            is AndExpression -> {
                result += findFunctionOnColumn(expr.leftExpression)
                result += findFunctionOnColumn(expr.rightExpression)
            }
            is OrExpression -> {
                result += findFunctionOnColumn(expr.leftExpression)
                result += findFunctionOnColumn(expr.rightExpression)
            }
            is InExpression -> {
                result += findFunctionOnColumn(expr.leftExpression)
                result += findFunctionOnColumn(expr.rightExpression)
            }
            is net.sf.jsqlparser.expression.operators.relational.ExistsExpression -> {
                result += findFunctionOnColumn(expr.rightExpression)
            }
            // Handle comparison operators: WHERE UPPER(col) = 'value'
            is net.sf.jsqlparser.expression.operators.relational.ComparisonOperator -> {
                result += findFunctionOnColumn(expr.leftExpression)
                result += findFunctionOnColumn(expr.rightExpression)
            }
            is ParenthesedSelect -> {
                // Check inside subqueries too
                if (expr.select is PlainSelect) {
                    val ps = expr.select as PlainSelect
                    if (ps.where != null) {
                        result += findFunctionOnColumn(ps.where)
                    }
                }
            }
            else -> {}
        }
        return result.distinct()
    }

    private fun countOrExpressions(expr: Expression): Int {
        return when (expr) {
            is OrExpression -> 1 + countOrExpressions(expr.leftExpression) + countOrExpressions(expr.rightExpression)
            is AndExpression -> countOrExpressions(expr.leftExpression) + countOrExpressions(expr.rightExpression)
            else -> 0
        }
    }

    private fun hasAggregateFunction(select: PlainSelect): Boolean {
        for (item in select.selectItems) {
            val expr = item.expression
            if (expr is Function) {
                val name = expr.name.uppercase()
                if (name in AGGREGATE_FUNCTIONS) return true
            }
            if (expr is MySQLGroupConcat) return true
        }
        if (select.having != null) return true
        return false
    }

    private val AGGREGATE_FUNCTIONS = setOf(
        "COUNT", "SUM", "AVG", "MAX", "MIN", "GROUP_CONCAT",
        "STRING_AGG", "ARRAY_AGG", "BIT_AND", "BIT_OR",
    )
}
