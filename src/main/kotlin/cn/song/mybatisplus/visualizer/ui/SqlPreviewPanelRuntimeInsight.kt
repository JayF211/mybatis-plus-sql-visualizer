package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.log.insight.AgentAnalysisProjection
import cn.song.mybatisplus.visualizer.log.insight.PreviewExecutionContext
import cn.song.mybatisplus.visualizer.log.insight.PreviewExecutionMatcher
import cn.song.mybatisplus.visualizer.ui.insight.*
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.atomic.AtomicBoolean

internal fun SqlPreviewPanel.showRuntimeInsight() {
    if (previewDisposed || project?.isDisposed == true) return
    val proj = project ?: return
    var view = runtimeInsightView
    if (view == null) {
        val manager = AgentManager.getInstance(proj)
        val source = object : RuntimeInsightSource {
            override fun enable(enabled: Boolean) { manager.setSqlAnalysisEnabled(enabled) }
            override fun snapshot() = RuntimeInsightSnapshot(manager.sqlAnalysisSnapshots(), manager.sqlRequestSnapshots(), manager.sqlTransactionEvents())
        }
        view = RuntimeInsightView(::insightText, source, { refreshRuntimeInsight() }, { previewTabs.remove("runtime-analysis") }, { showExecutionCandidates() })
        runtimeInsightSource = source
        runtimeInsightView = view
        val mounted = view
        previewTabs.put("runtime-analysis", insightText("runtime.title"), mounted.panel, onRemove = {
            mounted.dispose(); runtimeInsightView = null; runtimeInsightSource = null
        })
    }
    previewTabs.pane?.selectedComponent = view.panel
    refreshRuntimeInsight()
}

internal fun SqlPreviewPanel.refreshRuntimeInsight() {
    val view = runtimeInsightView ?: return
    val source = runtimeInsightSource ?: return
    val stopped = AtomicBoolean()
    var future: java.util.concurrent.Future<*>? = null
    val ticket = previewTabs.beginRefresh("runtime-analysis") { stopped.set(true); future?.cancel(true) } ?: return
    future = ApplicationManager.getApplication().executeOnPooledThread {
        val snapshot = source.snapshot()
        ApplicationManager.getApplication().invokeLater {
            if (!previewDisposed && project?.isDisposed != true && !stopped.get()) previewTabs.applyIfCurrent(ticket) { view.present(snapshot) }
        }
    }
}

internal fun SqlPreviewPanel.showExecutionCandidates() {
    val source = runtimeInsightSource ?: return
    val doc = insightDocument() ?: return
    if (doc.textLength == 0 || doc.textLength > 65_536) return
    val sql = doc.text
    val stamp = doc.modificationStamp
    val preview = current
    val panel = InsightDetailPanel(listOf("runId", "executionId", insightText("runtime.shape"), insightText("runtime.status")), insightText("runtime.candidateNotice"))
    panel.button(insightText("runtime.compareSample")) { showObservedSqlDiff() }
    panel.button(insightText("close")) { previewTabs.remove("execution-candidates") }
    previewTabs.put("execution-candidates", insightText("runtime.candidates"), panel)
    previewTabs.pane?.selectedComponent = panel
    val stopped = AtomicBoolean()
    var future: java.util.concurrent.Future<*>? = null
    val ticket = previewTabs.beginRefresh("execution-candidates") { stopped.set(true); future?.cancel(true) } ?: return
    future = ApplicationManager.getApplication().executeOnPooledThread {
        val events = source.snapshot().sessions.flatMap { it.observations }
        val now = System.currentTimeMillis()
        val context = PreviewExecutionContext(AgentAnalysisProjection.shapeFingerprint(sql), null, null, (now - 24 * 60 * 60 * 1000L).coerceAtLeast(0), now)
        val matches = PreviewExecutionMatcher.candidates(context, events)
        val rows = matches.map { candidate ->
            val e = candidate.execution
            InsightDetailRow(listOf(e.runId, e.executionId, e.fingerprint, e.status.name),
                "${candidate.evidence}\nscore=${candidate.score}\nstartedAtMillis=${e.startedAtMillis}\ndurationNanos=${e.durationNanos}\ndatabase=${e.databaseIdentity ?: "UNKNOWN"}\ncallSite=${e.callSite ?: "UNKNOWN"}\nrequestId=${e.requestId ?: "UNKNOWN"}")
        }
        ApplicationManager.getApplication().invokeLater {
            if (!previewDisposed && !stopped.get() && current === preview && insightDocument() === doc && doc.modificationStamp == stamp) {
                previewTabs.applyIfCurrent(ticket) { panel.showRows(rows) }
            }
        }
    }
}
