package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.project.Project

/** UI-safe descriptor: deliberately contains no URL, credentials or live datasource object. */
data class ComparisonTargetOption(val identity: String, val displayName: String, val dialectName: String?)

/** Independent comparison binding, never the shared/native preview console binding.
 * Discovery/select/refresh run in background. snapshot/invalidateSql/clear are thread-safe and cheap.
 * UI calls invalidateSql on BOTH SQL text and parameter changes before offering new consent.
 */
class ComparisonTargetSession internal constructor(private val discover: () -> List<Record>) {
    constructor(project: Project) : this({
        if (project.isDisposed) emptyList() else DataSourceSettings.getAllDataSources(project).mapNotNull { ds ->
            val target = ExecutionDialectGate.fromDataSource(ds)
            val id = target.stableId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = ExecDialectRouting.dataSourceUrl(ds) ?: return@mapNotNull null
            Record(ComparisonTargetOption(id, target.displayName ?: id, target.dialectName), ds,
                fingerprint(url + "\u0000" + target.dialectName + "\u0000" + configurationRevision(ds)))
        }
    })
    internal data class Record(val option: ComparisonTargetOption, val handle: Any, val configurationFingerprint: String)
    private var generation = 0L
    private var selected: Record? = null

    fun listTargets(): List<ComparisonTargetOption> = discover().map { it.option }.distinctBy { it.identity }

    /** Explicit choice only; duplicate IDs and missing targets are rejected, never alias-fallback. */
    fun select(identity: String): ComparisonTargetState? {
        val match = discover().filter { it.option.identity == identity }.singleOrNull()
        synchronized(this) { generation++; selected = match; return snapshot() }
    }
    @Synchronized fun clear() { generation++; selected = null }
    @Synchronized fun invalidateSql() { generation++ }
    @Synchronized fun snapshot(): ComparisonTargetState? = selected?.let {
        ComparisonTargetState(it.option.identity, generation, it.option.dialectName)
    }

    /** Called at execution boundaries on the worker. Removal/config changes invalidate old consent. */
    fun refresh(): ComparisonTargetState? {
        val before = synchronized(this) { selected to generation }
        val previous = before.first ?: return null
        val found = discover().filter { it.option.identity == previous.option.identity }.singleOrNull()
        synchronized(this) {
            if (selected !== previous || generation != before.second) return snapshot()
            if (found == null || found.handle !== previous.handle ||
                found.configurationFingerprint != previous.configurationFingerprint || found.option != previous.option) {
                generation++; selected = found
            }
            return snapshot()
        }
    }
    companion object {
        internal fun configurationRevision(dataSource: Any): Long =
            Class.forName("com.intellij.database.dataSource.AbstractDataSource", false, ComparisonTargetSession::class.java.classLoader)
                .getMethod("getModificationCount").invoke(dataSource) as Long

        private fun fingerprint(value: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
