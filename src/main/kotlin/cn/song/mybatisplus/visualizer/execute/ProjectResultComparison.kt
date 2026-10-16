package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.util.concurrent.Future

/** Headless production entry. UI supplies the already-consumed exact-pair consent and confirmation
 * facts; currentTarget must read a thread-safe immutable UI snapshot, never PSI on this worker.
 * Its generation MUST advance on either SQL edit, parameter edit or target change (not just binding).
 * It must remain backed by live state, not a captured constant.
 * No alias fallback, datasource chooser, console injection or editor write occurs here.
 */
object ProjectResultComparison {
    /** Preferred entry: explicit comparison-only binding and SQL/parameter edit generation. */
    fun executeAsync(
        project: Project,
        authorized: SqlComparisonRequest,
        safety: ComparisonSafetyContext,
        targetSession: ComparisonTargetSession,
        ordered: Boolean = false,
        cancellation: ComparisonCancellation = ComparisonCancellation(),
        onResult: (ComparisonExecutionResult) -> Unit,
    ): Future<*> = executeAsync(project, authorized, safety, targetSession::refresh, ordered, cancellation, onResult)

    fun executeAsync(
        project: Project,
        authorized: SqlComparisonRequest,
        safety: ComparisonSafetyContext,
        currentTarget: () -> ComparisonTargetState?,
        ordered: Boolean = false,
        cancellation: ComparisonCancellation = ComparisonCancellation(),
        onResult: (ComparisonExecutionResult) -> Unit,
    ): Future<*> = ApplicationManager.getApplication().executeOnPooledThread {
        var bound: Any? = null
        var connectionFingerprint: String? = null
        val resolve = {
            if (project.isDisposed) null else currentTarget()?.let { current ->
                val ds = DataSourceSettings.getDataSourceById(project, current.identity)
                if (ds == null) null else {
                    val actual = ExecutionDialectGate.fromDataSource(ds)
                    // Freeze the actual connection config, not its friendly name. The digest stays
                    // local and is never exported; connection credentials are not logged.
                    val config = (ExecDialectRouting.dataSourceUrl(ds) ?: "") + "\u0000" + actual.dialectName +
                        "\u0000" + ComparisonTargetSession.configurationRevision(ds)
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(config.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                    if (bound == null) { bound = ds; connectionFingerprint = digest }
                    if (bound !== ds || connectionFingerprint != digest || actual.stableId != current.identity) null
                    else current.copy(dialectName = actual.dialectName)
                }
            }
        }
        val service = ResultComparisonService(
            open = { _, validate ->
                validate()
                RemoteSqlComparisonBridge.open(project, bound ?: throw ComparisonRejected("target_unavailable"), validate)
            },
            currentTarget = resolve,
        )
        val result = service.execute(authorized, safety, ordered, cancellation)
        if (!project.isDisposed) onResult(result) // caller marshals UI presentation to EDT
    }
}
