package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.share.OfflineTraceImport
import cn.song.mybatisplus.visualizer.ui.insight.OfflineTraceView
import com.intellij.openapi.application.ApplicationManager

internal fun SqlPreviewPanel.showOfflineTraceImport() {
    if (previewDisposed || project?.isDisposed == true) return
    val existing = previewTabs.component("offline-trace")
    if (existing != null) { previewTabs.pane?.selectedComponent = existing; return }
    lateinit var view: OfflineTraceView
    lateinit var gate: cn.song.mybatisplus.visualizer.ui.insight.InsightInputVersion
    view = OfflineTraceView(::insightText, { json ->
        val version = gate.capture()
        view.clear(insightText("loading"))
        var future: java.util.concurrent.Future<*>? = null
        val ticket = previewTabs.beginRefresh("offline-trace") { future?.cancel(true) }
        if (ticket != null) future = ApplicationManager.getApplication().executeOnPooledThread {
            val result = OfflineTraceImport.parse(json)
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && project?.isDisposed != true && gate.isCurrent(version)) previewTabs.applyIfCurrent(ticket) { view.present(result) }
            }
        }
    }, { previewTabs.remove("offline-trace") }, { uri ->
        // Only a user-configured HTTPS template and an explicit button click reach this boundary.
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            runCatching { java.awt.Desktop.getDesktop().browse(uri) }
                .onFailure { view.results.status.text = insightText("failed") }
        }
    })
    gate = cn.song.mybatisplus.visualizer.ui.insight.InsightInputVersion({
        previewTabs.beginRefresh("offline-trace"); view.clear(insightText("stale"))
    }, view.input.document)
    previewTabs.put("offline-trace", insightText("import.title"), view.panel, onRemove = { gate.close() })
    previewTabs.pane?.selectedComponent = view.panel
}
