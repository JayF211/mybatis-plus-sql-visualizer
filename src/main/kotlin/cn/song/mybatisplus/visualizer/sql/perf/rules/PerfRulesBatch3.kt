package cn.song.mybatisplus.visualizer.sql.perf.rules

import cn.song.mybatisplus.visualizer.sql.perf.PerfRule
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.operators.conditional.AndExpression
import net.sf.jsqlparser.expression.operators.conditional.OrExpression
import net.sf.jsqlparser.expression.operators.relational.Between
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select

/**
 * 第三批性能规则（高级规则，共 6 条）。
 */
object PerfRulesBatch3 {

    /** 所有第三批规则实例。 */
    val all: List<PerfRule> = listOf(
        CorrelatedSubqueryRule,
        ImplicitTypeConversionRule,
        BetweenOnDatesRule,
        NestedSubqueryInSelectRule,
        OffsetPaginationRule,
        MultipleGroupByOrdersRule,
    )

    /**
     * `correlated_subquery`：关联子查询（引用外层列，每行执行一次）。
     */
    object CorrelatedSubqueryRule : PerfRule {
        override val ruleId = "correlated_subquery"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                if (select is PlainSelect) {
                    val outerAliases = collectTableAliases(select)
                    if (hasCorrelatedSubquery(select, outerAliases)) {
                        warnings += PerfWarning(
                            ruleId = ruleId,
                            severity = PerfSeverity.WARN,
                            messageKey = "perf.correlated_subquery",
                        )
                    }
                }
            }
            return warnings.take(1)
        }
    }

    /**
     * `implicit_type_conversion`：列与字面量类型不匹配。
     */
    object ImplicitTypeConversionRule : PerfRule {
        override val ruleId = "implicit_type_conversion"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                if (select is PlainSelect && select.where != null) {
                    val mismatches = findTypeMismatches(select.where)
                    if (mismatches.isNotEmpty()) {
                        warnings += PerfWarning(
                            ruleId = ruleId,
                            severity = PerfSeverity.WARN,
                            messageKey = "perf.implicit_type_conversion",
                            messageArgs = mismatches,
                        )
                    }
                }
            }
            return warnings.take(1)
        }
    }

    /**
     * `between_on_dates`：BETWEEN 日期范围查询。
     */
    object BetweenOnDatesRule : PerfRule {
        override val ruleId = "between_on_dates"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                if (select is PlainSelect && select.where != null) {
                    val betweenCount = countBetweenExpressions(select.where)
                    if (betweenCount > 0) {
                        warnings += PerfWarning(
                            ruleId = ruleId,
                            severity = PerfSeverity.INFO,
                            messageKey = "perf.between_on_dates",
                            messageArgs = listOf(betweenCount.toString()),
                        )
                    }
                }
            }
            return warnings.take(1)
        }
    }

    /**
     * `nested_subquery_in_select`：SELECT 子句子查询。
     */
    object NestedSubqueryInSelectRule : PerfRule {
        override val ruleId = "nested_subquery_in_select"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                if (select is PlainSelect) {
                    val count = countSelectSubqueries(select)
                    if (count > 0) {
                        warnings += PerfWarning(
                            ruleId = ruleId,
                            severity = PerfSeverity.INFO,
                            messageKey = "perf.nested_subquery_in_select",
                            messageArgs = listOf(count.toString()),
                        )
                    }
                }
            }
            return warnings.take(1)
        }
    }

    /**
     * `offset_pagination`：大偏移量分页。
     */
    object OffsetPaginationRule : PerfRule {
        override val ruleId = "offset_pagination"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                if (select is PlainSelect) {
                    val offsetValue = getOffsetValue(select)
                    if (offsetValue > 1000) {
                        warnings += PerfWarning(
                            ruleId = ruleId,
                            severity = PerfSeverity.INFO,
                            messageKey = "perf.offset_pagination",
                            messageArgs = listOf(offsetValue.toString()),
                        )
                    }
                }
            }
            return warnings.take(1)
        }
    }

    /**
     * `multiple_group_by_orders`：复杂 GROUP BY + ORDER BY 组合。
     */
    object MultipleGroupByOrdersRule : PerfRule {
        override val ruleId = "multiple_group_by_orders"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            if (ast == null) return emptyList()
            val warnings = mutableListOf<PerfWarning>()
            collectSelects(ast).forEach { select ->
                if (select is PlainSelect) {
                    val groupByCount = select.groupBy?.groupByExpressions?.size ?: 0
                    val orderByCount = select.orderByElements?.size ?: 0
                    if (groupByCount >= 3 && orderByCount >= 2) {
                        warnings += PerfWarning(
                            ruleId = ruleId,
                            severity = PerfSeverity.INFO,
                            messageKey = "perf.multiple_group_by_orders",
                        )
                    }
                }
            }
            return warnings.take(1)
        }
    }

    // ------------------------------------------------------------------
    // 辅助函数
    // ------------------------------------------------------------------

    private fun collectSelects(ast: Statement): List<Select> {
        val result = mutableListOf<Select>()
        when (ast) {
            is PlainSelect -> result += ParenthesedSelect(ast)
            is net.sf.jsqlparser.statement.select.SetOperationList -> ast.selects.forEach { result += it }
            is ParenthesedSelect -> result += ast
        }
        return result
    }

    private fun collectTableAliases(select: PlainSelect): Set<String> {
        val aliases = mutableSetOf<String>()
        select.fromItem?.let { fromItem ->
            if (fromItem is net.sf.jsqlparser.schema.Table) {
                fromItem.alias?.let { aliases += it.name.lowercase() }
                aliases += fromItem.name.lowercase()
            }
        }
        select.joins?.forEach { join ->
            join.rightItem?.let { right ->
                if (right is net.sf.jsqlparser.schema.Table) {
                    right.alias?.let { aliases += it.name.lowercase() }
                    aliases += right.name.lowercase()
                }
            }
        }
        return aliases
    }

    private fun hasCorrelatedSubquery(select: PlainSelect, outerAliases: Set<String>): Boolean {
        var found = false
        if (select.fromItem is ParenthesedSelect) {
            val sub = (select.fromItem as ParenthesedSelect).select
            if (sub is PlainSelect && sub.where != null) {
                found = referencesOuterAlias(sub.where, outerAliases)
            }
        }
        if (!found) {
            found = checkWhereForCorrelated(select.where, outerAliases)
        }
        return found
    }

    private fun checkWhereForCorrelated(expr: Expression?, outerAliases: Set<String>): Boolean {
        if (expr == null) return false
        return when (expr) {
            is ParenthesedSelect -> {
                val sub = expr.select
                if (sub is PlainSelect && sub.where != null) {
                    referencesOuterAlias(sub.where, outerAliases)
                } else false
            }
            is AndExpression -> checkWhereForCorrelated(expr.leftExpression, outerAliases) ||
                checkWhereForCorrelated(expr.rightExpression, outerAliases)
            is OrExpression -> checkWhereForCorrelated(expr.leftExpression, outerAliases) ||
                checkWhereForCorrelated(expr.rightExpression, outerAliases)
            else -> false
        }
    }

    private fun referencesOuterAlias(expr: Expression, outerAliases: Set<String>): Boolean {
        var found = false
        when (expr) {
            is Column -> {
                expr.table?.let { tbl ->
                    if (tbl.name.lowercase() in outerAliases) found = true
                }
            }
            is AndExpression -> found = referencesOuterAlias(expr.leftExpression, outerAliases) ||
                referencesOuterAlias(expr.rightExpression, outerAliases)
            is OrExpression -> found = referencesOuterAlias(expr.leftExpression, outerAliases) ||
                referencesOuterAlias(expr.rightExpression, outerAliases)
            is ParenthesedSelect -> {
                val sub = expr.select
                if (sub is PlainSelect && sub.where != null) {
                    found = referencesOuterAlias(sub.where, outerAliases)
                }
            }
            else -> {}
        }
        return found
    }

    private fun findTypeMismatches(expr: Expression): List<String> {
        val result = mutableListOf<String>()
        val mismatchPattern = Regex("""(`?\w+`?)\s*=\s*'([^']*)'""")
        val matches = mismatchPattern.findAll(expr.toString())
        matches.forEach { match ->
            val col = match.groupValues[1]
            if (col.lowercase().matches(Regex(".*(id|num|count|age|qty|price|amount|code|status).*"))) {
                result += col
            }
        }
        return result.distinct()
    }

    private fun countBetweenExpressions(expr: Expression): Int {
        return when (expr) {
            is Between -> 1
            is AndExpression -> countBetweenExpressions(expr.leftExpression) + countBetweenExpressions(expr.rightExpression)
            is OrExpression -> countBetweenExpressions(expr.leftExpression) + countBetweenExpressions(expr.rightExpression)
            else -> 0
        }
    }

    private fun countSelectSubqueries(select: PlainSelect): Int {
        var count = 0
        for (item in select.selectItems) {
            if (item.expression is ParenthesedSelect) count++
        }
        return count
    }

    private fun getOffsetValue(select: PlainSelect): Long {
        val offset = select.offset?.offset ?: return 0
        return if (offset is Number) offset.toLong() else 0
    }
}
