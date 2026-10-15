package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

internal object DialectPreviewStatusText {
    data class Text(val visible: String, val details: String)

    fun describe(decision: DialectDecisionEngine.Decision): Text {
        val title = MpSqlVisualizerBundle.message("panel.meta.dialectStatus")
        val status = when (decision.status) {
            DialectDecisionEngine.Status.MANUAL -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.manual")
            DialectDecisionEngine.Status.RESOLVED -> source(decision.source)
            DialectDecisionEngine.Status.AMBIGUOUS -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.ambiguous")
            DialectDecisionEngine.Status.CONFLICT -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.conflict")
            DialectDecisionEngine.Status.UNAVAILABLE -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.unavailable")
        }
        val rendered = decision.dialectName ?: decision.compatibilityFallbackName ?: "MySQL"
        val visible = if (decision.dialectName == null) {
            MpSqlVisualizerBundle.message("panel.meta.dialectStatus.fallback", rendered, status)
        } else "$title: $rendered ($status)"
        val confidence = MpSqlVisualizerBundle.message("panel.meta.dialectStatus.confidence.${decision.confidence.name}")
        val ids = decision.dataSourceIds.joinToString(", ").ifBlank {
            MpSqlVisualizerBundle.message("panel.meta.dialectStatus.noCandidates")
        }
        return Text(visible, MpSqlVisualizerBundle.message("panel.meta.dialectStatus.details",
            title, rendered, status, confidence, ids))
    }

    private fun source(source: DialectDecisionEngine.Source?): String = when (source) {
        DialectDecisionEngine.Source.MANUAL -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.manual")
        DialectDecisionEngine.Source.CAPTURED_URL -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.runtime")
        DialectDecisionEngine.Source.IDEA_SCOPE, DialectDecisionEngine.Source.IDEA_CONFIGURED_SCOPE ->
            MpSqlVisualizerBundle.message("panel.meta.dialectStatus.scope")
        DialectDecisionEngine.Source.IDEA_PROVIDER -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.provider")
        DialectDecisionEngine.Source.IDEA_DIALECT, DialectDecisionEngine.Source.IDEA_EFFECTIVE_DIALECT ->
            MpSqlVisualizerBundle.message("panel.meta.dialectStatus.ideaDialect")
        DialectDecisionEngine.Source.LEARNED_MODULE -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.module")
        DialectDecisionEngine.Source.PROJECT_AGENT -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.agent")
        DialectDecisionEngine.Source.PROJECT_CONSENSUS, DialectDecisionEngine.Source.IDEA_DEFAULT_SCOPE ->
            MpSqlVisualizerBundle.message("panel.meta.dialectStatus.projectConsensus")
        null -> MpSqlVisualizerBundle.message("panel.meta.dialectStatus.unavailable")
    }
}
