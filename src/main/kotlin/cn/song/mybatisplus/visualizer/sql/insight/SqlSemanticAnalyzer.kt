package cn.song.mybatisplus.visualizer.sql.insight

import cn.song.mybatisplus.visualizer.execute.SqlAstParser
import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.operators.relational.Between
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression
import net.sf.jsqlparser.expression.operators.relational.InExpression
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.*
import net.sf.jsqlparser.statement.update.Update
import java.util.Locale

/** Offline analysis only. Uses the existing strict parser unchanged; never executes SQL or loads IDE state. */
object SqlSemanticAnalyzer {
    /** Cancellation is checked before/after the existing bounded parser and during semantic traversal. */
    fun analyze(snapshot: AnalysisSnapshot, isCancelled: () -> Boolean = { false }): SqlSemanticModel =
        Analysis(snapshot, isCancelled).run()

    private class Analysis(private val snapshot: AnalysisSnapshot, private val isCancelled: () -> Boolean) {
        private class Halt(val code: DiagnosticCode) : RuntimeException()
        private var work = 0
        private var expressionDepth = 0
        private fun checkpoint() {
            if (isCancelled()) throw Halt(DiagnosticCode.CANCELLED)
            if (++work > 10_000 || nextScope > 128) throw Halt(DiagnosticCode.INPUT_BUDGET_EXCEEDED)
        }
        private val scopes = mutableListOf<SemanticScope>()
        private val relations = mutableListOf<SemanticRelation>()
        private val dependencies = mutableListOf<SemanticDependency>()
        private val diagnostics = mutableListOf<SemanticDiagnostic>()
        private var nextScope = 0

        private data class Binding(
            val relation: SemanticRelation,
            val qualifiers: Set<String>,
            /** null means physical table with unknown schema; empty means unresolved virtual relation. */
            val outputs: List<SemanticProjection>?,
        )
        private data class Cte(val scopeId: String?, val outputs: List<SemanticProjection>)
        private data class Frame(val id: String, val parent: String?, val label: String, val bindings: MutableList<Binding> = mutableListOf(),
            val correlation: Frame? = null, var ctes: Map<String, Cte> = emptyMap(), var reserved: Set<String> = emptySet(), var coalescedJoin: Boolean = false)
        private data class Resolved(val sources: Set<SemanticColumn>, val quality: AnalysisQuality = AnalysisQuality.CERTAIN,
            val derived: Boolean = false, val conditions: Set<SemanticColumn> = emptySet())
        private val aggregates = setOf("SUM", "AVG", "MIN", "MAX", "COUNT")
        private val scalarFunctions = setOf("ABS", "LOWER", "UPPER", "TRIM", "LTRIM", "RTRIM", "LENGTH", "CHAR_LENGTH", "COALESCE", "NULLIF", "CONCAT", "ROUND", "FLOOR", "CEIL", "CEILING", "REPLACE")
        private fun known(q: AnalysisQuality) = q == AnalysisQuality.CERTAIN || q == AnalysisQuality.CONDITIONAL

        fun run(): SqlSemanticModel = try {
            checkpoint()
            if (snapshot.sql.length > 65_536) throw Halt(DiagnosticCode.INPUT_BUDGET_EXCEEDED)
            analyzeInput()
        } catch (halt: Halt) {
            // Never publish half-built scopes/edges after cancellation or a budget failure.
            scopes.clear()
            relations.clear()
            dependencies.clear()
            diagnostics.clear()
            diagnostic(halt.code, if (halt.code == DiagnosticCode.CANCELLED) "Analysis was cancelled." else "Analysis input/traversal budget exceeded.", q = AnalysisQuality.UNKNOWN)
            model(null)
        }

        private fun analyzeInput(): SqlSemanticModel {
            if (snapshot.dialect == SqlInsightDialect.UNKNOWN) {
                diagnostic(DiagnosticCode.UNSUPPORTED_DIALECT, "Only verified MySQL/PostgreSQL identifier rules are supported.")
                return model(null)
            }
            if (hasQuotedDottedIdentifier(snapshot.sql)) {
                diagnostic(DiagnosticCode.UNSUPPORTED_DIALECT,
                    "JSqlParser 5.3 may split a quoted dotted identifier into multipart names; lineage is withheld.")
                return model(null)
            }
            val statement: Statement = try {
                SqlAstParser.parseSingle(snapshot.sql)
            } catch (_: Exception) {
                diagnostic(DiagnosticCode.PARSE_ERROR, "The strict SQL parser could not parse one complete statement.")
                return model(null)
            }
            checkpoint()
            val root = when (statement) {
                is Select -> select(statement, null, "SELECT", emptyMap(), emptySet()).id
                is Insert -> insert(statement)
                is Update -> update(statement)
                is Delete -> delete(statement)
                else -> {
                    diagnostic(DiagnosticCode.UNSUPPORTED_STATEMENT, "Unsupported statement: ${statement.javaClass.simpleName}")
                    null
                }
            }
            diagnostics += SemanticDiagnostic(DiagnosticCode.SOURCE_RANGE_UNKNOWN, AnalysisQuality.UNKNOWN,
                "AST source ranges have not been proven against the original text; only whole-input navigation is available.")
            return model(root)
        }

        /** Lexical veto only, not a second SQL parser. Never alter the parser's process-global properties. */
        private fun hasQuotedDottedIdentifier(sql: String): Boolean {
            var i = 0
            while (i < sql.length) {
                if (sql.startsWith("--", i)) {
                    i = sql.indexOf('\n', i + 2).takeIf { it >= 0 } ?: sql.length
                } else if (sql.startsWith("/*", i)) {
                    i = sql.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length
                } else if (sql[i] in charArrayOf('\'', '"', '`', '[')) {
                    val opening = sql[i++]
                    val closing = if (opening == '[') ']' else opening
                    var dotted = false
                    while (i < sql.length) {
                        val c = sql[i++]
                        if (c == closing) {
                            if (i < sql.length && sql[i] == closing) { i++; continue }
                            break
                        }
                        if (c == '\\' && snapshot.dialect == SqlInsightDialect.MYSQL && i < sql.length) i++
                        if (c == '.') dotted = true
                    }
                    if (opening != '\'' && dotted) return true
                } else i++
            }
            return false
        }

        private fun model(root: String?) = SqlSemanticModel(snapshot, scopes.toList(), relations.toList(), dependencies.toList(),
            diagnostics.toList(), root, quality(diagnostics.filter { it.code != DiagnosticCode.SOURCE_RANGE_UNKNOWN }.map { it.quality }))

        private fun diagnostic(code: DiagnosticCode, message: String, scope: String? = null, q: AnalysisQuality = AnalysisQuality.PARTIAL): Resolved {
            diagnostics += SemanticDiagnostic(code, q, message, scope)
            return Resolved(emptySet(), q)
        }

        private fun quality(values: List<AnalysisQuality>): AnalysisQuality = when {
            AnalysisQuality.AMBIGUOUS in values -> AnalysisQuality.AMBIGUOUS
            AnalysisQuality.UNKNOWN in values -> AnalysisQuality.UNKNOWN
            AnalysisQuality.PARTIAL in values -> AnalysisQuality.PARTIAL
            AnalysisQuality.CONDITIONAL in values -> AnalysisQuality.CONDITIONAL
            else -> AnalysisQuality.CERTAIN
        }

        private fun frame(parent: String?, label: String, correlation: Frame? = null): Frame {
            checkpoint()
            return Frame("scope-${nextScope++}", parent, label, correlation = correlation)
        }

        private fun finish(f: Frame, operation: SqlOperation, outputs: List<SemanticProjection> = emptyList()): SemanticScope =
            SemanticScope(f.id, f.parent, f.label, operation, f.bindings.map { it.relation.id }, outputs).also { scopes += it }

        private fun key(identifier: String): String {
            val quoted = identifier.length >= 2 && ((identifier.first() == '"' && identifier.last() == '"') ||
                (identifier.first() == '`' && identifier.last() == '`') || (identifier.first() == '[' && identifier.last() == ']'))
            if (quoted) {
                val quote = identifier.last().toString()
                return identifier.substring(1, identifier.length - 1).replace(quote + quote, quote)
            }
            // MySQL table/alias case depends on the server: preserve it rather than invent a case-insensitive binding.
            return if (snapshot.dialect == SqlInsightDialect.POSTGRESQL) identifier.lowercase(Locale.ROOT) else identifier
        }

        private fun qualifiedKey(table: Table): String = table.nameParts.asReversed().joinToString("\u0000") { key(it) }

        private fun verifiedIdentifier(identifier: String): Boolean = when (snapshot.dialect) {
            SqlInsightDialect.POSTGRESQL -> !identifier.startsWith('`') && !identifier.startsWith('[')
            SqlInsightDialect.MYSQL -> !identifier.startsWith('"') && !identifier.startsWith('[')
            SqlInsightDialect.UNKNOWN -> false
        }

        private fun bindTable(table: Table, f: Frame, roles: Set<TableRole>, ctes: Map<String, Cte>, reserved: Set<String>): Binding {
            checkpoint()
            val verified = table.nameParts.all { verifiedIdentifier(it) } && table.alias?.name?.let { verifiedIdentifier(it) } != false
            if (!verified) diagnostic(DiagnosticCode.UNSUPPORTED_DIALECT, "Identifier quoting is not verified for the selected dialect.", f.id)
            val cteName = key(table.name)
            val unqualified = table.nameParts.size == 1
            val cte = if (unqualified) ctes[cteName] else null
            val invalidCte = unqualified && cte == null && cteName in reserved
            if (invalidCte) diagnostic(DiagnosticCode.INVALID_CTE, "CTE ${table.name} is recursive or referenced before its definition.", f.id)
            val alias = table.alias?.name
            val relation = SemanticRelation("relation-${relations.size}", f.id,
                if (cte != null || invalidCte) RelationKind.CTE else RelationKind.TABLE,
                table.fullyQualifiedName, alias, roles, snapshot.databaseContext, cte?.scopeId)
            relations += relation
            val qualifiers = if (alias != null) setOf(key(alias)) else setOf(key(table.name), qualifiedKey(table))
            val outputs = if (invalidCte || !verified) emptyList() else cte?.outputs ?: metadataOutputs(table, relation)
            return Binding(relation, qualifiers, outputs).also { f.bindings += it }
        }

        private fun metadataOutputs(table: Table, relation: SemanticRelation): List<SemanticProjection>? {
            val metadata = snapshot.metadata ?: return null
            checkpoint()
            if (metadata.databaseContext.isBlank() || metadata.version.isBlank() || metadata.databaseContext != snapshot.databaseContext || metadata.version != snapshot.metadataVersion) {
                diagnostic(DiagnosticCode.METADATA_MISMATCH, "Metadata database/version does not match this snapshot.", relation.scopeId)
                return null
            }
            val parts = table.nameParts.asReversed().map(::key)
            val matches = metadata.tables.filter { it.nameParts.map(::key) == parts }
            if (matches.isEmpty()) return null
            if (matches.size != 1 || matches.single().columns.map(::key).distinct().size != matches.single().columns.size) {
                diagnostic(DiagnosticCode.METADATA_MISMATCH, "Metadata table or columns are duplicated.", relation.scopeId)
                return emptyList()
            }
            return matches.single().columns.mapIndexed { i, name ->
                checkpoint()
                SemanticProjection(i, name, name, setOf(SemanticColumn(relation.id, name)), AnalysisQuality.CERTAIN)
            }
        }

        private fun from(item: FromItem, f: Frame, ctes: Map<String, Cte>, reserved: Set<String>) {
            when (item) {
                is Table -> {
                    val binding = bindTable(item, f, setOf(TableRole.READ), ctes, reserved)
                    if (item.pivot != null || item.unPivot != null) {
                        f.bindings[f.bindings.lastIndex] = binding.copy(outputs = emptyList())
                        diagnostic(DiagnosticCode.UNSUPPORTED_CLAUSE, "PIVOT/UNPIVOT output names are not supported.", f.id)
                    }
                    if (item.sampleClause != null)
                        diagnostic(DiagnosticCode.UNSUPPORTED_CLAUSE, "Sampling semantics are not supported.", f.id)
                }
                is ParenthesedSelect -> {
                    val child = select(item.select, f.id, "derived", ctes, reserved)
                    val alias = item.alias?.name
                    val relation = SemanticRelation("relation-${relations.size}", f.id, RelationKind.DERIVED, "derived", alias,
                        setOf(TableRole.READ), snapshot.databaseContext, child.id)
                    relations += relation
                    f.bindings += Binding(relation, alias?.let { setOf(key(it)) }.orEmpty(), child.projections)
                    if (alias == null) diagnostic(DiagnosticCode.UNKNOWN_RELATION, "Derived table has no binding alias.", f.id)
                }
                else -> {
                    // Preserve an unknown input occurrence so a bare column cannot accidentally bind the one known table.
                    val relation = SemanticRelation("relation-${relations.size}", f.id, RelationKind.DERIVED,
                        item.javaClass.simpleName, item.alias?.name, setOf(TableRole.READ), snapshot.databaseContext)
                    relations += relation
                    f.bindings += Binding(relation, item.alias?.name?.let { setOf(key(it)) }.orEmpty(), emptyList())
                    diagnostic(DiagnosticCode.UNSUPPORTED_CLAUSE, "Unsupported FROM item: ${item.javaClass.simpleName}", f.id)
                }
            }
        }

        private fun select(node: Select, parent: String?, label: String, inherited: Map<String, Cte>, outerReserved: Set<String>, correlation: Frame? = null): SemanticScope {
            val f = frame(parent, label, correlation)
            val withItems = node.withItemsList.orEmpty()
            if (withItems.any { it.isRecursive }) {
                diagnostic(DiagnosticCode.RECURSIVE_CTE, "Recursive WITH is not supported; no recursive lineage is asserted.", f.id)
                return finish(f, SqlOperation.SELECT)
            }
            val ctes = inherited.toMutableMap()
            val reserved = outerReserved + withItems.map { key(it.aliasName) }
            withItems.forEach { ctes.remove(key(it.aliasName)) }
            val declared = mutableSetOf<String>()
            for (item in withItems) {
                val name = key(item.aliasName)
                if (!declared.add(name)) {
                    diagnostic(DiagnosticCode.INVALID_CTE, "Duplicate CTE name: ${item.aliasName}", f.id)
                    ctes[name] = Cte(null, emptyList())
                    continue
                }
                // A nested declaration shadows outer declarations, including during its own definition.
                ctes.remove(name)
                val body = item.parenthesedStatement
                if (body !is ParenthesedSelect) {
                    diagnostic(DiagnosticCode.INVALID_CTE, "Data-modifying CTEs are not supported.", f.id)
                    ctes[name] = Cte(null, emptyList())
                    continue
                }
                val child = select(body.select, f.id, "CTE ${item.aliasName}", ctes, reserved)
                var outputs = child.projections
                val aliases = item.withItemList.orEmpty()
                if (aliases.isNotEmpty()) {
                    if (aliases.size != outputs.size) {
                        diagnostic(DiagnosticCode.COLUMN_COUNT_MISMATCH, "CTE column count does not match its projection.", f.id)
                        outputs = emptyList()
                    } else outputs = outputs.mapIndexed { i, p -> p.copy(name = aliases[i].expression.toString()) }
                }
                ctes[name] = Cte(child.id, outputs)
            }
            f.ctes = ctes
            f.reserved = reserved
            if (node is ParenthesedSelect) {
                val child = select(node.select, f.id, label, ctes, reserved, correlation)
                return finish(f, SqlOperation.SELECT, child.projections)
            }
            if (node is SetOperationList) return union(node, f, ctes, reserved, correlation)
            if (node !is PlainSelect) {
                diagnostic(DiagnosticCode.UNSUPPORTED_STATEMENT, "Unsupported SELECT body: ${node.javaClass.simpleName}", f.id)
                return finish(f, SqlOperation.SELECT)
            }
            if (!node.intoTables.isNullOrEmpty() || node.intoTempTable != null) {
                diagnostic(DiagnosticCode.UNSUPPORTED_STATEMENT, "SELECT INTO is not supported as an ordinary read-only SELECT.", f.id)
                return finish(f, SqlOperation.UNSUPPORTED)
            }
            node.fromItem?.let { from(it, f, ctes, reserved) }
            node.joins.orEmpty().forEach { join ->
                from(join.rightItem, f, ctes, reserved)
                if (join.isNatural || !join.usingColumns.isNullOrEmpty()) {
                    f.coalescedJoin = true
                    diagnostic(DiagnosticCode.UNSUPPORTED_CLAUSE, "NATURAL/USING column coalescing requires verified metadata semantics.", f.id)
                }
                join.onExpressions.orEmpty().forEach { dependency(it, DependencyKind.JOIN, f) }
            }
            val outputs = mutableListOf<SemanticProjection>()
            node.selectItems.forEach { item ->
                if (item.expression is AllColumns) outputs += expandStar(item.expression as AllColumns, f, outputs.size)
                else {
                    val resolved = expression(item.expression, f)
                    val name = item.alias?.name ?: (item.expression as? Column)?.columnName
                    outputs += projection(outputs.size, name, item.expression.toString(), resolved, f)
                }
            }
            node.where?.let { dependency(it, DependencyKind.FILTER, f) }
            node.having?.let { dependency(it, DependencyKind.FILTER, f) }
            node.groupBy?.groupByExpressionList?.forEach { raw ->
                if (raw is Expression) dependency(raw, DependencyKind.GROUP, f, outputs, ordinal = true)
            }
            node.orderByElements.orEmpty().forEach { dependency(it.expression, DependencyKind.ORDER, f, outputs, ordinal = true) }
            if (node.qualify != null || node.oracleHierarchical != null || !node.lateralViews.isNullOrEmpty() ||
                !node.windowDefinitions.isNullOrEmpty() || !node.groupBy?.groupingSets.isNullOrEmpty() || node.groupBy?.isMysqlWithRollup == true ||
                node.distinct != null || node.limit != null || node.offset != null || node.fetch != null || node.top != null)
                diagnostic(DiagnosticCode.UNSUPPORTED_CLAUSE, "Some cardinality/window/dialect clauses are not explained; proven direct column origins are retained.", f.id)
            return finish(f, SqlOperation.SELECT, outputs)
        }

        private fun expandStar(star: AllColumns, f: Frame, offset: Int): List<SemanticProjection> {
            val inputs = if (star is AllTableColumns) f.bindings.filter { qualifiedKey(star.table) in it.qualifiers } else f.bindings
            val unsupported = !star.exceptColumns.isNullOrEmpty() || !star.replaceExpressions.isNullOrEmpty() || f.coalescedJoin
            if (unsupported || inputs.isEmpty() || inputs.any { it.outputs.isNullOrEmpty() } || (star is AllTableColumns && inputs.size != 1)) {
                val value = diagnostic(DiagnosticCode.STAR_REQUIRES_METADATA, "Star needs complete ordered columns for each visible relation and supported join semantics.", f.id, AnalysisQuality.UNKNOWN)
                return listOf(projection(offset, null, star.toString(), value, f))
            }
            return inputs.flatMap { it.outputs.orEmpty() }.mapIndexed { i, p ->
                checkpoint()
                projection(offset + i, p.name, star.toString(), Resolved(p.sources, p.quality, p.derived, p.conditionSources), f)
            }
        }

        private fun union(node: SetOperationList, f: Frame, ctes: Map<String, Cte>, reserved: Set<String>, correlation: Frame?): SemanticScope {
            if (node.operations.any { it !is UnionOp }) {
                diagnostic(DiagnosticCode.UNSUPPORTED_STATEMENT, "Only UNION/UNION ALL set operations are verified.", f.id)
                return finish(f, SqlOperation.SELECT)
            }
            val branches = node.selects.mapIndexed { i, child -> select(child, f.id, "UNION branch $i", ctes, reserved, correlation) }
            if (branches.isEmpty() || branches.map { it.projections.size }.distinct().size != 1 || branches.first().projections.isEmpty()) {
                diagnostic(DiagnosticCode.COLUMN_COUNT_MISMATCH, "UNION branches require equal nonempty projection counts.", f.id)
                return finish(f, SqlOperation.SELECT)
            }
            val outputs = branches.first().projections.indices.map { i ->
                val parts = branches.map { it.projections[i] }
                val value = combine(parts.map { Resolved(it.sources, it.quality, it.derived, it.conditionSources) }, true)
                projection(i, parts.first().name, "UNION column ${i + 1}", value, f)
            }
            if (node.operations.any { !(it as UnionOp).isAll })
                dependencies += SemanticDependency(DependencyKind.DEDUPLICATION, f.id, "UNION DISTINCT", outputs.flatMap { it.sources }.toSet(),
                    quality = quality(outputs.map { it.quality }))
            node.orderByElements.orEmpty().forEach { dependency(it.expression, DependencyKind.ORDER, f, outputs, true) }
            if (node.limit != null || node.offset != null || node.fetch != null)
                diagnostic(DiagnosticCode.UNSUPPORTED_CLAUSE, "Set-level pagination is not explained.", f.id)
            return finish(f, SqlOperation.SELECT, outputs)
        }

        private fun projection(index: Int, name: String?, text: String, value: Resolved, f: Frame): SemanticProjection {
            if (known(value.quality) && value.sources.isNotEmpty())
                dependencies += SemanticDependency(if (value.derived) DependencyKind.DERIVED_VALUE else DependencyKind.DIRECT_VALUE,
                    f.id, text, value.sources, outputIndex = index, quality = value.quality)
            if (value.conditions.isNotEmpty()) dependencies += SemanticDependency(DependencyKind.CONDITION, f.id, text,
                value.conditions, outputIndex = index, quality = value.quality)
            return SemanticProjection(index, name, text, value.sources, value.quality, value.derived, conditionSources = value.conditions)
        }

        private fun column(column: Column, f: Frame): Resolved {
            if (!verifiedIdentifier(column.columnName) || column.table?.nameParts?.any { !verifiedIdentifier(it) } == true)
                return diagnostic(DiagnosticCode.UNSUPPORTED_DIALECT, "Unverified column quoting: $column.", f.id)
            val qualifier = column.table?.takeIf { !it.name.isNullOrBlank() }?.let { qualifiedKey(it) }
            if (qualifier == null && f.correlation != null && f.bindings.any { it.outputs == null })
                return diagnostic(DiagnosticCode.AMBIGUOUS_COLUMN, "Unqualified correlated column needs verified local metadata: $column.", f.id, AnalysisQuality.AMBIGUOUS)
            val candidates = if (qualifier == null) f.bindings.filter { b -> b.outputs == null || b.outputs.any { it.name?.let(::key) == key(column.columnName) } }
                else f.bindings.filter { qualifier in it.qualifiers }
            if (candidates.isEmpty() && qualifier != null && f.correlation != null) return column(column, f.correlation)
            if (candidates.isEmpty()) return diagnostic(DiagnosticCode.UNKNOWN_RELATION, "No visible relation for $column.", f.id, AnalysisQuality.UNKNOWN)
            if (candidates.size != 1) return diagnostic(DiagnosticCode.AMBIGUOUS_COLUMN, "Column $column has multiple possible input occurrences.", f.id, AnalysisQuality.AMBIGUOUS)
            val binding = candidates.single()
            if (binding.outputs == null) return Resolved(setOf(SemanticColumn(binding.relation.id, column.columnName)))
            val matching = binding.outputs.filter { it.name != null && key(it.name) == key(column.columnName) }
            if (matching.isEmpty()) return diagnostic(DiagnosticCode.UNKNOWN_COLUMN, "Column $column is not a proven output of its input scope.", f.id, AnalysisQuality.UNKNOWN)
            if (matching.size != 1) return diagnostic(DiagnosticCode.AMBIGUOUS_COLUMN, "Column $column matches duplicate output names.", f.id, AnalysisQuality.AMBIGUOUS)
            val output = matching.single()
            return Resolved(output.sources, output.quality, output.derived, output.conditionSources)
        }

        private fun expression(expr: Expression, f: Frame): Resolved {
            checkpoint()
            if (++expressionDepth > 128) throw Halt(DiagnosticCode.INPUT_BUDGET_EXCEEDED)
            return try { expressionBody(expr, f) } finally { expressionDepth-- }
        }

        private fun expressionBody(expr: Expression, f: Frame): Resolved = when (expr) {
            is Column -> column(expr, f)
            is Function -> function(expr, f)
            is AnalyticExpression -> filteredAggregate(expr, f)
            is CaseExpression -> caseExpression(expr, f)
            is CastExpression -> expression(expr.leftExpression, f).copy(derived = true)
            is Select -> subquery(expr, f, scalar = true)
            is ExistsExpression -> {
                val nested = expr.rightExpression
                if (nested is Select) subquery(nested, f, scalar = false)
                else diagnostic(DiagnosticCode.UNSUPPORTED_EXPRESSION, "EXISTS operand is not a SELECT.", f.id)
            }
            is AllColumns -> diagnostic(DiagnosticCode.STAR_REQUIRES_METADATA, "Star expansion has no verified metadata; no columns are invented.", f.id, AnalysisQuality.UNKNOWN)
            is BinaryExpression -> combine(listOf(expression(expr.leftExpression, f), expression(expr.rightExpression, f)), derived = true)
            is SignedExpression -> expression(expr.expression, f).copy(derived = true)
            is NotExpression -> expression(expr.expression, f).copy(derived = true)
            is IsNullExpression -> expression(expr.leftExpression, f).copy(derived = true)
            is Between -> combine(listOf(expression(expr.leftExpression, f), expression(expr.betweenExpressionStart, f), expression(expr.betweenExpressionEnd, f)), true)
            is InExpression -> combine(listOf(expression(expr.leftExpression, f), expression(expr.rightExpression, f)), true)
            is ExpressionList<*> -> combine(expr.map { expression(it, f) }, expr.size != 1)
            is LongValue, is DoubleValue, is StringValue, is NullValue, is DateValue, is TimeValue, is TimestampValue,
            is JdbcParameter, is JdbcNamedParameter, is BooleanValue -> Resolved(emptySet())
            else -> diagnostic(DiagnosticCode.UNSUPPORTED_EXPRESSION,
                "Expression ${expr.javaClass.simpleName} is outside the verified subset; no value origins are asserted.", f.id)
        }

        private fun function(expr: Function, f: Frame): Resolved {
            val name = expr.name.uppercase(Locale.ROOT)
            if (name !in aggregates && name !in scalarFunctions || expr.multipartName.size != 1 || expr.keep != null ||
                expr.havingClause != null || expr.namedParameters != null || expr.attribute != null || expr.limit != null)
                return diagnostic(DiagnosticCode.UNSUPPORTED_EXPRESSION, "Function shape is not verified: ${expr.name}.", f.id)
            val arguments = expr.parameters.orEmpty()
            val values = if (name == "COUNT" && (expr.isAllColumns || arguments.any { it is AllColumns })) Resolved(emptySet(), derived = true)
                else combine(arguments.map { expression(it, f) }, true)
            val ordering = combine(expr.orderByElements.orEmpty().map { expression(it.expression, f) }, true)
            val conditions = values.conditions + ordering.sources + ordering.conditions
            return when (name) {
                "COUNT" -> values.copy(sources = emptySet(), conditions = conditions + values.sources)
                "NULLIF" -> {
                    if (arguments.size != 2) return diagnostic(DiagnosticCode.UNSUPPORTED_EXPRESSION, "NULLIF needs two operands.", f.id)
                    val first = expression(arguments[0], f)
                    values.copy(sources = if (known(values.quality)) first.sources else emptySet(), conditions = conditions + values.sources)
                }
                else -> values.copy(conditions = conditions, quality = quality(listOf(values.quality, ordering.quality)))
            }
        }

        private fun filteredAggregate(expr: AnalyticExpression, f: Frame): Resolved {
            val name = expr.name.uppercase(Locale.ROOT)
            if (expr.type != AnalyticType.FILTER_ONLY || name !in aggregates || expr.keep != null || expr.havingClause != null)
                return diagnostic(DiagnosticCode.UNSUPPORTED_EXPRESSION, "Window/ordered-set aggregate is outside this subset.", f.id)
            val value = if (expr.isAllColumns || expr.expression is AllColumns || expr.expression == null) Resolved(emptySet(), derived = true)
                else expression(expr.expression, f).copy(derived = true)
            val filter = expr.filterExpression?.let { expression(it, f) } ?: Resolved(emptySet())
            val q = quality(listOf(value.quality, filter.quality))
            return Resolved(if (name == "COUNT" || !known(q)) emptySet() else value.sources, q, true,
                value.conditions + filter.sources + filter.conditions + if (name == "COUNT") value.sources else emptySet())
        }

        private fun caseExpression(expr: CaseExpression, f: Frame): Resolved {
            val values = expr.whenClauses.orEmpty().map { expression(it.thenExpression, f) } +
                listOfNotNull(expr.elseExpression?.let { expression(it, f) })
            val predicates = expr.whenClauses.orEmpty().map { expression(it.whenExpression, f) } +
                listOfNotNull(expr.switchExpression?.let { expression(it, f) })
            val value = combine(values, true)
            val predicate = combine(predicates, true)
            val q = quality(listOf(value.quality, predicate.quality))
            return Resolved(if (known(q)) value.sources else emptySet(), q, true,
                value.conditions + predicate.sources + predicate.conditions)
        }

        private fun subquery(node: Select, outer: Frame, scalar: Boolean): Resolved {
            var body = node
            while (body is ParenthesedSelect) body = body.select
            val start = dependencies.size
            val child = select(node, outer.id, if (scalar) "scalar" else "exists", outer.ctes, outer.reserved, outer)
            val influences = dependencies.drop(start).filter { it.kind in setOf(DependencyKind.FILTER, DependencyKind.JOIN, DependencyKind.GROUP, DependencyKind.CONDITION, DependencyKind.ORDER) }
                .flatMap { it.sources }.toSet()
            if (!scalar) return Resolved(emptySet(), quality(dependencies.drop(start).map { it.quality }), true, influences)
            if (child.projections.size != 1) return diagnostic(DiagnosticCode.COLUMN_COUNT_MISMATCH, "Scalar SELECT must project exactly one column.", outer.id)
            val plain = body as? PlainSelect
            val noRowsSource = plain != null && plain.fromItem == null && plain.joins.isNullOrEmpty()
            val aggregate = plain != null && plain.groupBy == null && plain.selectItems.all { item ->
                val e = item.expression
                (e is Function && e.name.uppercase(Locale.ROOT) in aggregates) ||
                    (e is AnalyticExpression && e.type == AnalyticType.FILTER_ONLY && e.name.uppercase(Locale.ROOT) in aggregates)
            }
            val limitOne = plain?.limit?.rowCount?.let { it is LongValue && it.value in 0L..1L } == true
            if (!noRowsSource && !aggregate && !limitOne)
                return diagnostic(DiagnosticCode.SCALAR_CARDINALITY_UNKNOWN, "At-most-one scalar row is not proven (use aggregate or explicit LIMIT 1).", outer.id, AnalysisQuality.UNKNOWN)
            val p = child.projections.single()
            return Resolved(p.sources, p.quality, true, p.conditionSources + influences)
        }

        private fun combine(parts: List<Resolved>, derived: Boolean): Resolved {
            val q = quality(parts.map { it.quality })
            return Resolved(if (known(q)) parts.flatMap { it.sources }.toSet() else emptySet(), q,
                derived || parts.any { it.derived }, parts.flatMap { it.conditions }.toSet())
        }

        private fun dependency(expr: Expression, kind: DependencyKind, f: Frame, outputs: List<SemanticProjection> = emptyList(), ordinal: Boolean = false) {
            val output = if (ordinal && expr is LongValue && expr.value in 1L..outputs.size.toLong()) outputs[(expr.value - 1).toInt()] else null
            // PostgreSQL GROUP BY prioritizes input names over output aliases. Without metadata, do not guess a collision.
            val aliasMatches = if (kind != DependencyKind.GROUP && expr is Column && expr.table?.name.isNullOrBlank())
                outputs.filter { it.name != null && key(it.name) == key(expr.columnName) } else emptyList()
            val groupAliasCollision = kind == DependencyKind.GROUP && expr is Column && expr.table?.name.isNullOrBlank() &&
                outputs.any { it.name != null && key(it.name) == key(expr.columnName) && it.expression != expr.toString() }
            val resolved = when {
                groupAliasCollision -> diagnostic(DiagnosticCode.AMBIGUOUS_COLUMN, "GROUP BY input/output name precedence needs metadata for $expr.", f.id, AnalysisQuality.AMBIGUOUS)
                output != null -> Resolved(output.sources, output.quality, output.derived)
                ordinal && expr is LongValue -> diagnostic(DiagnosticCode.UNKNOWN_COLUMN, "Invalid projection ordinal $expr.", f.id, AnalysisQuality.UNKNOWN)
                aliasMatches.size > 1 -> diagnostic(DiagnosticCode.AMBIGUOUS_COLUMN, "Ambiguous output alias $expr.", f.id, AnalysisQuality.AMBIGUOUS)
                aliasMatches.size == 1 -> aliasMatches.single().let { Resolved(it.sources, it.quality, it.derived) }
                else -> expression(expr, f)
            }
            dependencies += SemanticDependency(kind, f.id, expr.toString(), resolved.sources + resolved.conditions, quality = resolved.quality)
        }

        private fun insert(node: Insert): String {
            val f = frame(null, "INSERT")
            val target = bindTable(node.table, f, setOf(TableRole.WRITE), emptyMap(), emptySet())
            if (!node.duplicateUpdateSets.isNullOrEmpty() || !node.setUpdateSets.isNullOrEmpty() || node.conflictAction != null ||
                !node.withItemsList.isNullOrEmpty() || node.isOverwrite || node.returningClause != null || node.outputClause != null) {
                diagnostic(DiagnosticCode.UNSUPPORTED_STATEMENT, "INSERT modifiers/upsert/RETURNING are outside the verified subset.", f.id)
                return finish(f, SqlOperation.INSERT).id
            }
            val body = node.select
            if (body == null || body is Values) {
                diagnostic(DiagnosticCode.UNSUPPORTED_STATEMENT, "Only INSERT SELECT is supported.", f.id)
                return finish(f, SqlOperation.INSERT).id
            }
            val source = select(body, f.id, "INSERT source", emptyMap(), emptySet())
            val columns = node.columns.orEmpty()
            when {
                columns.isEmpty() -> diagnostic(DiagnosticCode.TARGET_COLUMNS_REQUIRED, "INSERT SELECT needs explicit target columns.", f.id)
                columns.size != source.projections.size -> diagnostic(DiagnosticCode.COLUMN_COUNT_MISMATCH, "INSERT source/target column counts differ.", f.id)
                source.projections.any { it.quality != AnalysisQuality.CERTAIN } ->
                    diagnostic(DiagnosticCode.UNKNOWN_COLUMN, "INSERT has an unresolved projection; positional assignment is not asserted.", f.id, AnalysisQuality.UNKNOWN)
                else -> columns.forEachIndexed { i, column ->
                    val output = source.projections[i]
                    dependencies += SemanticDependency(DependencyKind.ASSIGNMENT, f.id, output.expression, output.sources,
                        SemanticColumn(target.relation.id, column.columnName))
                }
            }
            return finish(f, SqlOperation.INSERT).id
        }

        private fun update(node: Update): String {
            val f = frame(null, "UPDATE")
            if (node.fromItem != null || !node.joins.isNullOrEmpty() || !node.startJoins.isNullOrEmpty() || !node.withItemsList.isNullOrEmpty()) {
                diagnostic(DiagnosticCode.UNSUPPORTED_STATEMENT, "Only single-table UPDATE without FROM/WITH/JOIN is supported.", f.id)
                return finish(f, SqlOperation.UPDATE).id
            }
            val target = bindTable(node.table, f, setOf(TableRole.READ, TableRole.WRITE), emptyMap(), emptySet())
            val alreadyAssigned = mutableSetOf<String>()
            node.updateSets.forEach { set ->
                if (set.columns.size != set.values.size) {
                    diagnostic(DiagnosticCode.COLUMN_COUNT_MISMATCH, "UPDATE tuple assignment is not aligned.", f.id)
                } else set.columns.forEachIndexed { i, column ->
                    val targetResolution = column(column, f)
                    var value = expression(set.getValue(i), f)
                    if (snapshot.dialect == SqlInsightDialect.MYSQL && value.sources.any { key(it.name) in alreadyAssigned })
                        value = diagnostic(DiagnosticCode.UNSUPPORTED_EXPRESSION, "MySQL sequential assignment reads an earlier modified column.", f.id)
                    if (targetResolution.quality == AnalysisQuality.CERTAIN && value.quality == AnalysisQuality.CERTAIN)
                        dependencies += SemanticDependency(DependencyKind.ASSIGNMENT, f.id, set.getValue(i).toString(), value.sources,
                            SemanticColumn(target.relation.id, column.columnName))
                    alreadyAssigned += key(column.columnName)
                }
            }
            node.where?.let { dependency(it, DependencyKind.FILTER, f) }
            node.orderByElements.orEmpty().forEach { dependency(it.expression, DependencyKind.ORDER, f) }
            if (node.limit != null || node.returningClause != null || node.outputClause != null)
                diagnostic(DiagnosticCode.UNSUPPORTED_CLAUSE, "UPDATE LIMIT/RETURNING/OUTPUT is not explained.", f.id)
            return finish(f, SqlOperation.UPDATE).id
        }

        private fun delete(node: Delete): String {
            val f = frame(null, "DELETE")
            if (!node.tables.isNullOrEmpty() || !node.usingList.isNullOrEmpty() || !node.joins.isNullOrEmpty() || !node.withItemsList.isNullOrEmpty()) {
                diagnostic(DiagnosticCode.UNSUPPORTED_STATEMENT, "Only single-table DELETE without USING/WITH/JOIN is supported.", f.id)
                return finish(f, SqlOperation.DELETE).id
            }
            bindTable(node.table, f, setOf(TableRole.READ, TableRole.DELETE), emptyMap(), emptySet())
            node.where?.let { dependency(it, DependencyKind.FILTER, f) }
            node.orderByElements.orEmpty().forEach { dependency(it.expression, DependencyKind.ORDER, f) }
            if (node.limit != null || node.returningClause != null || node.outputClause != null)
                diagnostic(DiagnosticCode.UNSUPPORTED_CLAUSE, "DELETE LIMIT/RETURNING/OUTPUT is not explained.", f.id)
            return finish(f, SqlOperation.DELETE).id
        }
    }
}
