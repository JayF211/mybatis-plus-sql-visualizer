package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.insight.AnalysisSnapshot
import cn.song.mybatisplus.visualizer.sql.insight.SqlOptimizationAdvisor
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailPanel
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailRow
import com.intellij.openapi.application.ApplicationManager

internal fun SqlPreviewPanel.showOptimizationCandidates() {
    if (previewDisposed) return
    val document = insightDocument() ?: return
    if (document.textLength > 65_536) return
    val sql = document.text
    val version = document.modificationStamp
    val result = current
    val dialect = insightDialect()
    val panel = InsightDetailPanel(listOf(insightText("optimization.rule"), insightText("optimization.expression")), insightText("optimization.notice"))
    panel.button(insightText("close")) { previewTabs.remove("optimization") }
    previewTabs.put("optimization", insightText("optimization.title"), panel)
    previewTabs.pane?.selectedComponent = panel
    var future: java.util.concurrent.Future<*>? = null
    val ticket = previewTabs.beginRefresh("optimization") { future?.cancel(true) } ?: return
    panel.button(insightText("cancel")) { previewTabs.beginRefresh("optimization"); panel.status.text = insightText("cancelled") }
    future = ApplicationManager.getApplication().executeOnPooledThread {
        val report = SqlOptimizationAdvisor.analyze(AnalysisSnapshot(sql, dialect), isCancelled = { Thread.currentThread().isInterrupted })
        ApplicationManager.getApplication().invokeLater {
            if (!previewDisposed && current === result && insightDocument() === document && document.modificationStamp == version) {
                previewTabs.applyIfCurrent(ticket) {
                    panel.showRows(report.candidates.map { candidate -> InsightDetailRow(listOf(candidate.ruleId, candidate.expression),
                        "${candidate.evidence}\n${candidate.preconditions.joinToString("\n")}\nstatement=${candidate.statementIndex + 1}; range=${candidate.sourceRange ?: "UNKNOWN"}") })
                    panel.status.text = insightText("optimization.notice") + "\ncancelled=${report.cancelled}; truncated=${report.truncated}\n" + report.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" }
                }
            }
        }
    }
}
