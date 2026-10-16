package cn.song.mybatisplus.visualizer.sql.insight

import java.util.Locale

/** Host expression/tag range, not a claimed exact mapping of decoded SQL tokens. */
data class SqlSourceLocation(val filePath: String, val startOffset: Int, val endOffsetExclusive: Int,
    val modificationStamp: Long? = null, val label: String? = null)
enum class SqlCandidateKind { SQL_FILE, JAVA_CONSTANT, MAPPER_STATEMENT }
data class SqlUsageCandidate(val snapshot: AnalysisSnapshot, val location: SqlSourceLocation, val kind: SqlCandidateKind)
data class SqlUsageDiagnostic(val code: String, val message: String, val location: SqlSourceLocation? = null)
data class SqlUsageCoverage(val scopeDescription: String, val visitedFiles: Int, val candidateCount: Int,
    val skippedFiles: Int = 0, val truncated: Boolean = false, val cancelled: Boolean = false)
data class SqlUsageReference(val location: SqlSourceLocation, val databaseContext: String?, val dialect: SqlInsightDialect,
    val tableName: String, val column: String?, val roles: Set<TableRole>, val dependencyKinds: Set<DependencyKind>, val quality: AnalysisQuality)
data class SqlUsageReport(val candidates: List<SqlUsageCandidate>, val usages: List<SqlUsageReference>,
    val diagnostics: List<SqlUsageDiagnostic>, val coverage: SqlUsageCoverage)
data class SqlObjectQuery(val databaseContext: String?, val tableNameParts: List<String>, val column: String? = null,
    val dialect: SqlInsightDialect = SqlInsightDialect.POSTGRESQL)
data class SqlUsageMatches(val references: List<SqlUsageReference>, val diagnostics: List<SqlUsageDiagnostic>, val complete: Boolean)
data class SqlUsageLimits(val maxFiles: Int = 200, val maxCandidates: Int = 500, val maxFileCharacters: Int = 262_144,
    val maxPsiNodesPerFile: Int = 20_000, val maxDirectories: Int = 500) {
    init { require(maxFiles in 1..5000 && maxCandidates in 1..5000 && maxFileCharacters in 1..1_048_576 && maxPsiNodesPerFile in 1..100_000 && maxDirectories in 1..5000) }
}

/** Conservative dialect-aware identity shared by usage lookup and schema comparison. */
internal object SqlInsightNames {
    fun key(identifier: String, dialect: SqlInsightDialect): String {
        val quote = identifier.firstOrNull()
        if (identifier.length >= 2 && (quote == '"' || quote == '`') && identifier.last() == quote)
            return identifier.substring(1, identifier.length - 1).replace("$quote$quote", "$quote")
        return if (dialect == SqlInsightDialect.POSTGRESQL) identifier.lowercase(Locale.ROOT) else identifier
    }
    fun parts(name: String): List<String> {
        val result = mutableListOf<String>()
        var quote: Char? = null
        var start = 0
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (quote != null && c == quote) {
                if (i + 1 < name.length && name[i + 1] == quote) i++ else quote = null
            } else if (quote == null && (c == '"' || c == '`')) quote = c
            else if (quote == null && c == '.') { result += name.substring(start, i); start = i + 1 }
            i++
        }
        result += name.substring(start)
        return result
    }
    fun table(parts: List<String>, dialect: SqlInsightDialect) = parts.map { key(it, dialect) }
}

object SqlObjectUsageAnalyzer {
    fun analyze(candidates: List<SqlUsageCandidate>, isCancelled: () -> Boolean = { false },
        limits: SqlUsageLimits = SqlUsageLimits(), scopeDescription: String = "explicit candidates"): SqlUsageReport {
        val usages = mutableListOf<SqlUsageReference>()
        val diagnostics = mutableListOf<SqlUsageDiagnostic>()
        val accepted = mutableListOf<SqlUsageCandidate>()
        var cancelled = false
        for (candidate in candidates.take(limits.maxCandidates)) {
            if (isCancelled()) { cancelled = true; break }
            val model = SqlSemanticAnalyzer.analyze(candidate.snapshot, isCancelled)
            if (model.diagnostics.any { it.code == DiagnosticCode.CANCELLED }) { cancelled = true; break }
            accepted += candidate
            model.diagnostics.filter { it.code != DiagnosticCode.SOURCE_RANGE_UNKNOWN }.forEach {
                diagnostics += SqlUsageDiagnostic(it.code.name, it.message, candidate.location)
            }
            for (relation in model.relations.filter { it.kind == RelationKind.TABLE }) {
                val byColumn = linkedMapOf<String, MutableSet<DependencyKind>>()
                model.dependencies.forEach { edge ->
                    edge.sources.filter { it.relationId == relation.id }.forEach { byColumn.getOrPut(it.name) { mutableSetOf() } += edge.kind }
                    edge.targetColumn?.takeIf { it.relationId == relation.id }?.let { byColumn.getOrPut(it.name) { mutableSetOf() } += edge.kind }
                }
                usages += SqlUsageReference(candidate.location, relation.databaseContext, model.snapshot.dialect, relation.name,
                    null, relation.roles, emptySet(), model.quality)
                byColumn.forEach { (column, kinds) -> usages += SqlUsageReference(candidate.location, relation.databaseContext,
                    model.snapshot.dialect, relation.name, column, relation.roles, kinds, model.quality) }
            }
        }
        val truncated = candidates.size > limits.maxCandidates
        if (truncated) diagnostics += SqlUsageDiagnostic("CANDIDATE_LIMIT", "Explicit candidate budget reached.")
        if (cancelled) diagnostics += SqlUsageDiagnostic("CANCELLED", "Usage collection was cancelled; coverage is partial.")
        return SqlUsageReport(accepted, usages.distinct(), diagnostics,
            SqlUsageCoverage(scopeDescription, accepted.map { it.location.filePath }.distinct().size, accepted.size, truncated = truncated, cancelled = cancelled))
    }

    fun findUsages(report: SqlUsageReport, query: SqlObjectQuery): SqlUsageMatches {
        val diagnostics = report.diagnostics.toMutableList()
        val comparable = query.databaseContext != null && query.dialect != SqlInsightDialect.UNKNOWN
        if (!comparable) diagnostics += SqlUsageDiagnostic("DATABASE_IDENTITY_UNKNOWN", "A verified database context and dialect are required for definite impact.")
        val uncertainContext = report.usages.any { it.databaseContext == null || it.dialect == SqlInsightDialect.UNKNOWN }
        if (uncertainContext) diagnostics += SqlUsageDiagnostic("USAGE_CONTEXT_UNKNOWN", "Some candidates have unknown database identity; absence is not proven.")
        val refs = if (!comparable) emptyList() else report.usages.filter { ref ->
            ref.databaseContext == query.databaseContext && ref.dialect == query.dialect &&
                SqlInsightNames.table(SqlInsightNames.parts(ref.tableName), ref.dialect) == SqlInsightNames.table(query.tableNameParts, query.dialect) &&
                (query.column == null || ref.column?.let { SqlInsightNames.key(it, ref.dialect) } == SqlInsightNames.key(query.column, query.dialect))
        }
        return SqlUsageMatches(refs, diagnostics, comparable && !uncertainContext && !report.coverage.cancelled && !report.coverage.truncated && diagnostics.isEmpty())
    }
}
