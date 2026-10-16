package cn.song.mybatisplus.visualizer.sql.insight

import cn.song.mybatisplus.visualizer.execute.SqlAstParser
import net.sf.jsqlparser.expression.BinaryExpression
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SetOperationList
import java.util.ArrayDeque
import java.util.Locale

data class SqlOptimizationCandidate(val ruleId: String, val evidence: String, val preconditions: List<String>,
    val expression: String, val sourceRange: SqlSourceRange? = null, val statementIndex: Int = 0)
data class SqlOptimizationReport(val candidates: List<SqlOptimizationCandidate>, val diagnostics: List<SqlUsageDiagnostic>,
    val cancelled: Boolean = false, val truncated: Boolean = false)

/** Evidence-only static candidates. No rewriting, EXPLAIN, index lookup or execution. */
object SqlOptimizationAdvisor {
    fun analyze(snapshot: AnalysisSnapshot, isCancelled: () -> Boolean = { false }): SqlOptimizationReport {
        if (isCancelled()) return SqlOptimizationReport(emptyList(), listOf(SqlUsageDiagnostic("CANCELLED", "Advice cancelled.")), cancelled = true)
        if (snapshot.sql.length > 65_536) return SqlOptimizationReport(emptyList(), listOf(SqlUsageDiagnostic("INPUT_BUDGET", "Advice input budget exceeded.")), truncated = true)
        if (snapshot.dialect == SqlInsightDialect.UNKNOWN) return SqlOptimizationReport(emptyList(), listOf(SqlUsageDiagnostic("DIALECT_UNKNOWN", "Advice requires a verified dialect.")))
        val statements = try { SqlAstParser.parseStatements(snapshot.sql) } catch (_: Exception) {
            return SqlOptimizationReport(emptyList(), listOf(SqlUsageDiagnostic("PARSE_ERROR", "Strict AST parse failed; no regex-based optimization claim is made.")))
        }
        val candidates = mutableListOf<SqlOptimizationCandidate>()
        val diagnostics = mutableListOf<SqlUsageDiagnostic>()
        var work = 0
        var truncated = false
        val transforms = setOf("LOWER", "UPPER", "TRIM", "LTRIM", "RTRIM", "DATE", "YEAR", "MONTH", "ABS", "ROUND", "COALESCE")
        fun offer(rule: String, evidence: String, assumptions: List<String>, expression: String, index: Int) {
            if (candidates.size >= 100) { truncated = true; return }
            candidates += SqlOptimizationCandidate(rule, evidence, assumptions, expression, statementIndex = index)
        }
        fun inspectWhere(where: Expression, statementIndex: Int) {
            val stack = ArrayDeque<Expression>()
            stack.add(where)
            while (stack.isNotEmpty() && !isCancelled() && work++ < 10_000) {
                when (val expr = stack.removeLast()) {
                    is Function -> {
                        val parameters = expr.parameters.orEmpty()
                        if (expr.name.uppercase(Locale.ROOT) in transforms && parameters.any { it is Column })
                            offer("SI.NON_SARG_COLUMN_FUNCTION", "WHERE applies ${expr.name} to a column; an ordinary index on the raw column may not support this predicate.",
                                listOf("This is a candidate, not a query-plan finding.", "Function/expression indexes, generated columns and optimizer transformations may make this efficient.", "Validate equivalent null/collation/timezone semantics and compare actual plans before any rewrite."), expr.toString(), statementIndex)
                        stack.addAll(parameters)
                    }
                    is BinaryExpression -> { stack.add(expr.leftExpression); stack.add(expr.rightExpression) }
                    is ExpressionList<*> -> stack.addAll(expr)
                }
            }
        }
        statements.forEachIndexed { index, statement ->
            if (statement !is Select) {
                diagnostics += SqlUsageDiagnostic("ADVICE_STATEMENT_UNCOVERED", "Only SELECT structures are inspected by these rules (statement ${index + 1}).")
                return@forEachIndexed
            }
            val stack = ArrayDeque<Select>()
            stack.add(statement)
            while (stack.isNotEmpty() && !isCancelled() && work++ < 10_000) {
                when (val select = stack.removeLast()) {
                    is ParenthesedSelect -> stack.add(select.select)
                    is SetOperationList -> stack.addAll(select.selects)
                    is PlainSelect -> {
                        select.withItemsList.orEmpty().forEach { (it.parenthesedStatement as? ParenthesedSelect)?.let { child -> stack.add(child.select) } }
                        (select.fromItem as? ParenthesedSelect)?.let { stack.add(it.select) }
                        select.joins.orEmpty().forEach { (it.rightItem as? ParenthesedSelect)?.let { child -> stack.add(child.select) } }
                        select.selectItems.filter { it.expression is AllColumns }.forEach { item ->
                            offer("SI.WIDE_STAR_PROJECTION", "SELECT projects ${item.expression}; returned columns can grow with schema changes and increase transfer/materialization cost.",
                                listOf("Star is valid SQL, not a semantic error.", "Use explicit columns only when consumers do not require all fields; row width and result size are not measured here."), item.expression.toString(), index)
                        }
                        select.where?.let { inspectWhere(it, index) }
                        select.joins.orEmpty().filter { join -> !join.isNatural && join.usingColumns.isNullOrEmpty() && join.onExpressions.isNullOrEmpty() }.forEach { join ->
                            // An old-style WHERE predicate can encode the join; do not call that a proven Cartesian product.
                            if (select.where == null) offer("SI.CARTESIAN_JOIN", "Multiple FROM inputs have no ON/USING/NATURAL or WHERE predicate.",
                                listOf("Cross joins may be intentional.", "Input cardinalities and row constraints are unknown; verify intended multiplicity before adding a predicate."), join.toString(), index)
                        }
                    }
                    else -> diagnostics += SqlUsageDiagnostic("ADVICE_SELECT_UNCOVERED", "SELECT body ${select.javaClass.simpleName} is outside these rules.")
                }
            }
        }
        if (isCancelled()) return SqlOptimizationReport(emptyList(), listOf(SqlUsageDiagnostic("CANCELLED", "Advice cancelled; no partial candidates published.")), cancelled = true)
        if (work >= 10_000) truncated = true
        if (truncated) diagnostics += SqlUsageDiagnostic("ADVICE_BUDGET", "Advice traversal/candidate budget reached.")
        diagnostics += SqlUsageDiagnostic("SOURCE_RANGE_UNKNOWN", "Expressions are AST evidence; exact offsets into original SQL are not proven.")
        return SqlOptimizationReport(candidates.distinct(), diagnostics, truncated = truncated)
    }
}
