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
    view.results.button(insightText("import.file")) {
        val file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(), project, null)
        if (file != null && file.isInLocalFileSystem) {
            gate.invalidate()
            val version = gate.capture()
            val path = file.path
            var future: java.util.concurrent.Future<*>? = null
            val ticket = previewTabs.beginRefresh("offline-trace") { future?.cancel(true) }
            if (ticket != null) future = ApplicationManager.getApplication().executeOnPooledThread {
                val parsed = try {
                    OfflineTraceImport.parse(cn.song.mybatisplus.visualizer.ui.insight.LocalInsightFile.readUtf8(java.nio.file.Path.of(path), OfflineTraceImport.MAX_BYTES))
                } catch (failure: Exception) {
                    if (failure is java.util.concurrent.CancellationException || failure is com.intellij.openapi.progress.ProcessCanceledException) throw failure
                    OfflineTraceImport.Result.Rejected("local_file_unreadable_or_oversized")
                }
                ApplicationManager.getApplication().invokeLater {
                    if (!previewDisposed && gate.isCurrent(version)) previewTabs.applyIfCurrent(ticket) { view.present(parsed) }
                }
            }
        }
    }
    previewTabs.put("offline-trace", insightText("import.title"), view.panel, onRemove = { gate.close() })
    previewTabs.pane?.selectedComponent = view.panel
}
