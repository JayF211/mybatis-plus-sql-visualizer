package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

internal object DialectSettingsStatusText {
    fun describe(projectCount: Int, decision: DialectDecisionEngine.Decision?): String {
        if (projectCount == 0) return MpSqlVisualizerBundle.message("settings.dialect.inferred.noProject")
        if (projectCount != 1) return MpSqlVisualizerBundle.message("settings.dialect.inferred.multipleProjects")
        return when (decision?.status) {
            DialectDecisionEngine.Status.MANUAL -> MpSqlVisualizerBundle.message(
                "settings.dialect.inferred.projectManual", decision.dialectName.orEmpty(),
            )
            DialectDecisionEngine.Status.RESOLVED -> {
                val source = when (decision.source) {
                    DialectDecisionEngine.Source.PROJECT_CONSENSUS ->
                        MpSqlVisualizerBundle.message("settings.dialect.inferred.projectCandidates")
                    DialectDecisionEngine.Source.PROJECT_AGENT ->
                        MpSqlVisualizerBundle.message("settings.dialect.inferred.projectAgent")
                    else -> MpSqlVisualizerBundle.message("settings.dialect.inferred.otherSource")
                }
                MpSqlVisualizerBundle.message("settings.dialect.inferred.withSource", decision.dialectName.orEmpty(), source)
            }
            DialectDecisionEngine.Status.AMBIGUOUS -> MpSqlVisualizerBundle.message("settings.dialect.inferred.ambiguous")
            DialectDecisionEngine.Status.CONFLICT -> MpSqlVisualizerBundle.message("settings.dialect.inferred.conflict")
            else -> MpSqlVisualizerBundle.message("settings.dialect.inferred.unavailable")
        }
    }
}
