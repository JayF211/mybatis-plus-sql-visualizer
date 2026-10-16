package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.share.SqlScenarioRegressionPackage
import cn.song.mybatisplus.visualizer.xml.XmlScenarioResult
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.atomic.AtomicBoolean

internal fun SqlPreviewPanel.exportScenariosInBackground(values: List<XmlScenarioResult>) {
    val view = scenarioInsightView ?: return
    val doc = insightDocument()
    val version = ScenarioInputVersion(auxiliaryInsightGeneration, doc, doc?.modificationStamp, buildHistorySnapshot().toMap())
    val stopped = AtomicBoolean()
    var future: java.util.concurrent.Future<*>? = null
    val ticket = previewTabs.beginRefresh("scenarios") { stopped.set(true); future?.cancel(true) } ?: return
    view.panel.status.text = insightText("loading")
    val started = System.nanoTime()
    future = ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val result = SqlScenarioRegressionPackage.export(values) { stopped.get() || Thread.currentThread().isInterrupted || System.nanoTime() - started > 5_000_000_000L }
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && version.matches(auxiliaryInsightGeneration, insightDocument(), buildHistorySnapshot())) {
                    previewTabs.applyIfCurrent(ticket) {
                        when (result) {
                            is SqlScenarioRegressionPackage.Export.Ready -> { view.panel.status.text = insightText("ready"); showScenarioExportPreview(result.json) }
                            is SqlScenarioRegressionPackage.Export.Rejected -> view.panel.status.text = result.reason
                        }
                    }
                }
            }
        } catch (_: java.util.concurrent.CancellationException) {
            ApplicationManager.getApplication().invokeLater { if (!previewDisposed) previewTabs.applyIfCurrent(ticket) { view.panel.status.text = insightText("timeLimit") } }
        } catch (failure: Exception) {
            if (failure is com.intellij.openapi.progress.ProcessCanceledException) throw failure
            ApplicationManager.getApplication().invokeLater { if (!previewDisposed) previewTabs.applyIfCurrent(ticket) { view.panel.status.text = insightText("failed") } }
        }
    }
}
