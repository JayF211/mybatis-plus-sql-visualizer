package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.PluginBuildInfo

/** R90 的提交/成功回调共同使用的上下文；只有值快照进入 manager，target 仅在面板会话内。 */
class ExplainPlanHistorySession {
    class Request internal constructor(
        val key: ExplainPlanKey,
        val capture: ExplainPlanCapture?,
        internal val generation: Long,
        internal val target: Any,
        internal val retentionAtSubmission: Boolean,
    )
    data class Baseline(val label: String, val raw: McpExplainFormatter.RawResult)
    data class Completion(val baselines: List<Baseline>, val saved: ExplainPlanSaveResult)
    private data class Previous(val request: Request, val raw: McpExplainFormatter.RawResult)
    private var previous: Previous? = null

    fun begin(manager: SqlHistoryManager, previewSql: String, key: ExplainPlanKey, target: Any): Request =
        begin(manager, previewSql, key, target, null)

    fun begin(manager: SqlHistoryManager, previewSql: String, key: ExplainPlanKey, target: Any, entryId: String?): Request =
        begin(manager, previewSql, key, target, entryId, false)

    fun begin(manager: SqlHistoryManager, previewSql: String, key: ExplainPlanKey, target: Any,
              entryId: String?, retentionEnabled: Boolean): Request =
        Request(key, if (retentionEnabled) manager.beginExplainCapture(key.projectIdentity, previewSql, key, entryId) else null,
            manager.explainPlanGeneration(), target, retentionEnabled)

    /** 在保存新结果之前取基线；故不会与自己比较。UI 选择基线只消费返回值，不再执行 SQL。 */
    fun complete(manager: SqlHistoryManager, request: Request, raw: McpExplainFormatter.RawResult): Completion =
        complete(manager, request, raw, false)

    fun complete(manager: SqlHistoryManager, request: Request, raw: McpExplainFormatter.RawResult,
                 retentionEnabled: Boolean): Completion {
        val retain = request.retentionAtSubmission && retentionEnabled
        if (request.generation != manager.explainPlanGeneration()) {
            previous = null
            return Completion(emptyList(), ExplainPlanSaveResult.CLEARED_OR_REMOVED)
        }
        val stored = if (retain) manager.matchingExplainPlans(request.key) else emptyList()
        val baselines = stored.mapNotNull { snapshot -> snapshot.raw()?.let {
            Baseline(MpSqlVisualizerBundle.message("panel.explain.history.snapshot",
                PluginBuildInfo.formatBuildTime(snapshot.capturedAt.toString()), snapshot.id.take(8)), it)
        } }.toMutableList()
        previous?.takeIf {
            it.request.generation == request.generation && it.request.key == request.key &&
                (request.key.persistable || it.request.target === request.target)
        }?.let { last ->
            // R61：上次结果即使超限未持久化，也仍可在同目标会话内比较。
            val previousLabel = MpSqlVisualizerBundle.message("panel.explain.history.previousSession")
            val retainedIndex = baselines.indexOfFirst { it.raw == last.raw }
            if (retainedIndex < 0) {
                baselines.add(0, Baseline(previousLabel, last.raw))
            } else {
                // 保持原来的去重、顺序与比较数据，只补充这一项的会话来源。
                val retained = baselines[retainedIndex]
                baselines[retainedIndex] = retained.copy(label = "$previousLabel · ${retained.label}")
            }
        }
        val saved = if (!retain) ExplainPlanSaveResult.RETENTION_DISABLED
            else request.capture?.let { manager.saveExplainPlan(it, raw) } ?: ExplainPlanSaveResult.SESSION_ONLY
        previous = Previous(request, raw)
        return Completion(baselines, saved)
    }
}
