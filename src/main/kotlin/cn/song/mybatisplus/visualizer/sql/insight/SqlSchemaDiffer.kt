package cn.song.mybatisplus.visualizer.sql.insight

import java.util.Locale

data class SqlTypedColumn(val name: String, val typeName: String? = null, val nullable: Boolean? = null)
data class SqlTypedTable(val nameParts: List<String>, val columns: List<SqlTypedColumn>)
data class SqlSchemaSnapshot(val databaseContext: String, val version: String, val tables: List<SqlTypedTable>,
    val dialect: SqlInsightDialect = SqlInsightDialect.POSTGRESQL)
enum class SqlSchemaChangeKind { TABLE_ADDED, TABLE_REMOVED, COLUMN_ADDED, COLUMN_REMOVED, TYPE_CHANGED, NULLABILITY_CHANGED }
data class SqlSchemaChange(val kind: SqlSchemaChangeKind, val tableNameParts: List<String>, val column: String?,
    val beforeType: String? = null, val afterType: String? = null, val affected: List<SqlUsageReference> = emptyList())
data class SqlSchemaDiffReport(val databaseContext: String?, val beforeVersion: String?, val afterVersion: String?,
    val changes: List<SqlSchemaChange>, val diagnostics: List<SqlUsageDiagnostic>, val comparable: Boolean, val impactComplete: Boolean)

/** Compares explicit snapshots only. No missing snapshot can be interpreted as an empty database. */
object SqlSchemaDiffer {
    fun compare(before: SqlSchemaSnapshot?, after: SqlSchemaSnapshot?, usageReport: SqlUsageReport? = null,
        isCancelled: () -> Boolean = { false }): SqlSchemaDiffReport {
        val diagnostics = mutableListOf<SqlUsageDiagnostic>()
        fun rejected(code: String, message: String) = SqlSchemaDiffReport(before?.databaseContext, before?.version, after?.version,
            emptyList(), listOf(SqlUsageDiagnostic(code, message)), false, false)
        if (before == null || after == null) return rejected("SNAPSHOT_MISSING", "Both explicit schema snapshots are required; absence does not prove missing objects.")
        if (before.databaseContext.isBlank() || before.databaseContext != after.databaseContext || before.dialect != after.dialect || before.dialect == SqlInsightDialect.UNKNOWN)
            return rejected("SCHEMA_IDENTITY_MISMATCH", "Snapshots must identify the same database and verified dialect.")
        if (before.version.isBlank() || after.version.isBlank() || before.version == after.version)
            return rejected("SCHEMA_VERSION_UNKNOWN", "Distinct nonblank snapshot versions are required.")
        if (before.tables.size + after.tables.size > 4000 || before.tables.sumOf { it.columns.size } + after.tables.sumOf { it.columns.size } > 50_000)
            return rejected("SCHEMA_BUDGET", "Schema comparison budget exceeded.")
        val dialect = before.dialect
        fun tableKey(t: SqlTypedTable) = SqlInsightNames.table(t.nameParts, dialect)
        val old = before.tables.associateBy(::tableKey)
        val new = after.tables.associateBy(::tableKey)
        if (old.size != before.tables.size || new.size != after.tables.size || (before.tables + after.tables).any { t ->
                t.columns.map { SqlInsightNames.key(it.name, dialect) }.distinct().size != t.columns.size })
            return rejected("SCHEMA_DUPLICATE_IDENTITY", "Duplicate table/column identities prevent a definite comparison.")
        val changes = mutableListOf<SqlSchemaChange>()
        var impactComplete = usageReport != null
        fun record(kind: SqlSchemaChangeKind, table: SqlTypedTable, column: String? = null, from: String? = null, to: String? = null) {
            val matches = usageReport?.let { SqlObjectUsageAnalyzer.findUsages(it, SqlObjectQuery(before.databaseContext, table.nameParts, column, dialect)) }
            if (matches != null) { diagnostics += matches.diagnostics; impactComplete = impactComplete && matches.complete }
            changes += SqlSchemaChange(kind, table.nameParts, column, from, to, matches?.references.orEmpty())
        }
        for (identity in old.keys + new.keys) {
            if (isCancelled()) return rejected("CANCELLED", "Schema comparison cancelled; no partial diff is published.")
            val a = old[identity]
            val b = new[identity]
            if (a == null) { record(SqlSchemaChangeKind.TABLE_ADDED, b!!); continue }
            if (b == null) { record(SqlSchemaChangeKind.TABLE_REMOVED, a); continue }
            val oldColumns = a.columns.associateBy { SqlInsightNames.key(it.name, dialect) }
            val newColumns = b.columns.associateBy { SqlInsightNames.key(it.name, dialect) }
            for (columnKey in oldColumns.keys + newColumns.keys) {
                if (isCancelled()) return rejected("CANCELLED", "Schema comparison cancelled; no partial diff is published.")
                val x = oldColumns[columnKey]
                val y = newColumns[columnKey]
                if (x == null) { record(SqlSchemaChangeKind.COLUMN_ADDED, b, y!!.name, to = y.typeName); continue }
                if (y == null) { record(SqlSchemaChangeKind.COLUMN_REMOVED, a, x.name, from = x.typeName); continue }
                if (x.typeName.isNullOrBlank() || y.typeName.isNullOrBlank())
                    diagnostics += SqlUsageDiagnostic("TYPE_UNKNOWN", "Type is unknown for ${b.nameParts.joinToString(".")}.${y.name}; no type change is asserted.")
                else if (canonicalType(x.typeName) != canonicalType(y.typeName)) record(SqlSchemaChangeKind.TYPE_CHANGED, b, y.name, x.typeName, y.typeName)
                if (x.nullable != null && y.nullable != null && x.nullable != y.nullable)
                    record(SqlSchemaChangeKind.NULLABILITY_CHANGED, b, y.name, x.typeName, y.typeName)
            }
        }
        if (usageReport == null) diagnostics += SqlUsageDiagnostic("USAGE_SNAPSHOT_MISSING", "Schema changes are known, but source mapping coverage was not supplied.")
        if (usageReport != null && (usageReport.coverage.cancelled || usageReport.coverage.truncated || usageReport.usages.any { it.databaseContext == null })) impactComplete = false
        return SqlSchemaDiffReport(before.databaseContext, before.version, after.version, changes, diagnostics.distinct(), true, impactComplete)
    }

    // SQL type spelling normalization only; identifier identity is never normalized this way.
    private fun canonicalType(type: String) = type.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)
}
