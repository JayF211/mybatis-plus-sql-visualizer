package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter

/** R90 的提交/成功回调共同使用的上下文；只有值快照进入 manager，target 仅在面板会话内。 */
class ExplainPlanHistorySession {
    data class Request internal constructor(
        val key: ExplainPlanKey,
        val capture: ExplainPlanCapture?,
        internal val generation: Long,
        internal val target: Any,
    )
    data class Baseline(val label: String, val raw: McpExplainFormatter.RawResult)
    data class Completion(val baselines: List<Baseline>, val saved: ExplainPlanSaveResult)
    private data class Previous(val request: Request, val raw: McpExplainFormatter.RawResult)
    private var previous: Previous? = null

    fun begin(manager: SqlHistoryManager, previewSql: String, key: ExplainPlanKey, target: Any): Request =
        Request(key, manager.beginExplainCapture(key.projectIdentity, previewSql, key), manager.explainPlanGeneration(), target)

    /** 在保存新结果之前取基线；故不会与自己比较。UI 选择基线只消费返回值，不再执行 SQL。 */
    fun complete(manager: SqlHistoryManager, request: Request, raw: McpExplainFormatter.RawResult): Completion {
        if (request.generation != manager.explainPlanGeneration()) {
            previous = null
            return Completion(emptyList(), ExplainPlanSaveResult.CLEARED_OR_REMOVED)
        }
        val stored = manager.matchingExplainPlans(request.key)
        val baselines = stored.mapNotNull { snapshot -> snapshot.raw()?.let {
            Baseline("${java.time.Instant.ofEpochMilli(snapshot.capturedAt)} · ${snapshot.id.take(8)}", it)
        } }.toMutableList()
        previous?.takeIf {
            it.request.generation == request.generation && it.request.key == request.key &&
                (request.key.persistable || it.request.target === request.target)
        }?.let { last ->
            // R61：上次结果即使超限未持久化，也仍可在同目标会话内比较。
            if (baselines.none { it.raw == last.raw }) baselines.add(0, Baseline("R61", last.raw))
        }
        val saved = request.capture?.let { manager.saveExplainPlan(it, raw) }
            ?: ExplainPlanSaveResult.SESSION_ONLY
        previous = Previous(request, raw)
        return Completion(baselines, saved)
    }
}
