package cn.song.mybatisplus.visualizer.sql.insight

/** Immutable, offline input. Null context/version means unknown, never the active IDE database. */
data class AnalysisSnapshot(
    val sql: String,
    val dialect: SqlInsightDialect = SqlInsightDialect.POSTGRESQL,
    val databaseContext: String? = null,
    val metadataVersion: String? = null,
    val scenarioId: String? = null,
    val sourceVersion: String? = null,
    val metadata: SqlColumnMetadata? = null,
)

/** Caller-supplied, ordered schema snapshot; never loaded from the IDE or database by the analyzer. */
data class SqlColumnMetadata(val databaseContext: String, val version: String, val tables: List<SqlTableColumns>)
/** Identifier parts are ordered catalog/schema/table, retaining SQL quoting. */
data class SqlTableColumns(val nameParts: List<String>, val columns: List<String>)

enum class SqlInsightDialect { MYSQL, POSTGRESQL, UNKNOWN }
enum class AnalysisQuality { CERTAIN, PARTIAL, AMBIGUOUS, UNKNOWN, CONDITIONAL }
enum class SqlOperation { SELECT, INSERT, UPDATE, DELETE, UNSUPPORTED, MERGE, CREATE_TEMP, DROP_TEMP }
enum class RelationKind { TABLE, CTE, DERIVED, TEMPORARY }
enum class TableRole { READ, WRITE, DELETE }
enum class DependencyKind { DIRECT_VALUE, DERIVED_VALUE, ASSIGNMENT, JOIN, FILTER, GROUP, ORDER, CONDITION, MERGE_MATCH, DEDUPLICATION }
enum class DiagnosticCode {
    PARSE_ERROR, UNSUPPORTED_STATEMENT, UNSUPPORTED_DIALECT, UNSUPPORTED_EXPRESSION,
    UNSUPPORTED_CLAUSE, AMBIGUOUS_COLUMN, UNKNOWN_COLUMN, UNKNOWN_RELATION,
    STAR_REQUIRES_METADATA, RECURSIVE_CTE, INVALID_CTE, TARGET_COLUMNS_REQUIRED,
    COLUMN_COUNT_MISMATCH, SOURCE_RANGE_UNKNOWN, INPUT_BUDGET_EXCEEDED, CANCELLED,
    METADATA_MISMATCH, SCALAR_CARDINALITY_UNKNOWN, TEMPORARY_STATE_UNKNOWN, CONDITIONAL_WRITE,
}

/** No guessed token offsets. Whole input navigation remains possible via snapshot.sql. */
data class SqlSourceRange(val startOffset: Int, val endOffsetExclusive: Int)

data class SemanticDiagnostic(
    val code: DiagnosticCode,
    val quality: AnalysisQuality,
    val message: String,
    val scopeId: String? = null,
)

data class SemanticRelation(
    val id: String,
    val scopeId: String,
    val kind: RelationKind,
    /** AST-rendered identifier retaining catalog/schema and quotes; not a database identity. */
    val name: String,
    val alias: String?,
    val roles: Set<TableRole>,
    val databaseContext: String?,
    val sourceScopeId: String? = null,
)

data class SemanticColumn(val relationId: String, val name: String)

data class SemanticProjection(
    val index: Int,
    val name: String?,
    val expression: String,
    /** Proven base-column origins only. An incomplete expression has no asserted value origins. */
    val sources: Set<SemanticColumn>,
    val quality: AnalysisQuality,
    val derived: Boolean = false,
    val sourceRange: SqlSourceRange? = null,
    val conditionSources: Set<SemanticColumn> = emptySet(),
)

/** JOIN/FILTER/GROUP/ORDER affect rows; they are deliberately not projection value origins. */
data class SemanticDependency(
    val kind: DependencyKind,
    val scopeId: String,
    val expression: String,
    val sources: Set<SemanticColumn>,
    val targetColumn: SemanticColumn? = null,
    val outputIndex: Int? = null,
    val quality: AnalysisQuality = AnalysisQuality.CERTAIN,
    val sourceRange: SqlSourceRange? = null,
    val branchId: String? = null,
)

data class SemanticScope(
    val id: String,
    val parentScopeId: String?,
    val label: String,
    val operation: SqlOperation,
    val relationIds: List<String>,
    val projections: List<SemanticProjection>,
)

data class SqlSemanticModel(
    val snapshot: AnalysisSnapshot,
    val scopes: List<SemanticScope>,
    val relations: List<SemanticRelation>,
    val dependencies: List<SemanticDependency>,
    val diagnostics: List<SemanticDiagnostic>,
    val rootScopeId: String?,
    val quality: AnalysisQuality,
    /** Textual statement order, not evidence of execution or transaction commit. */
    val statementScopeIds: List<String> = emptyList(),
) {
    val outputFields: List<SemanticProjection>
        get() = scopes.firstOrNull { it.id == rootScopeId }?.projections.orEmpty()
}
