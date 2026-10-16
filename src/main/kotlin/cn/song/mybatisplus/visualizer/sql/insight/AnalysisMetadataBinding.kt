package cn.song.mybatisplus.visualizer.sql.insight

/** Explicit offline schema choice for one preview; never inferred from a globally selected datasource. */
class AnalysisMetadataBinding {
    private var generation = 0L
    private var metadata: SqlColumnMetadata? = null
    private var dialect: SqlInsightDialect? = null

    @Synchronized
    fun select(snapshot: SqlSchemaSnapshot): Long {
        require(snapshot.databaseContext.isNotBlank() && snapshot.version.isNotBlank())
        require(snapshot.dialect != SqlInsightDialect.UNKNOWN)
        require(snapshot.tables.size <= 1000 && snapshot.tables.sumOf { it.columns.size } <= 5000)
        generation++
        metadata = SqlColumnMetadata(snapshot.databaseContext, snapshot.version,
            snapshot.tables.map { SqlTableColumns(it.nameParts.toList(), it.columns.map { column -> column.name }) })
        dialect = snapshot.dialect
        return generation
    }

    @Synchronized
    fun clear() { generation++; metadata = null; dialect = null }

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun snapshot(sql: String, actualDialect: SqlInsightDialect, sourceVersion: String): AnalysisSnapshot {
        val selected = metadata.takeIf { dialect == actualDialect }
        return AnalysisSnapshot(sql, actualDialect, selected?.databaseContext, selected?.version,
            sourceVersion = "$sourceVersion:metadata-$generation", metadata = selected)
    }
}
