package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Disposer

/** Shared invalidation boundary for all snapshot consumers, even before the analysis tab is opened. */
internal fun SqlPreviewPanel.bindAuxiliaryInsightDocument(document: Document?) {
    if (auxiliaryInsightDocument === document) return
    auxiliaryInsightDocument = document
    auxiliaryInsightListener?.let(Disposer::dispose)
    auxiliaryInsightListener = null
    invalidateAuxiliaryInsights()
    if (document != null && !previewDisposed) {
        val lifetime = Disposer.newDisposable(this, "SqlPreview.auxiliaryInsight.document")
        auxiliaryInsightListener = lifetime
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { invalidateAuxiliaryInsights() }
        }, lifetime)
    }
}

internal fun SqlPreviewPanel.invalidateAuxiliaryInsights() {
    auxiliaryInsightGeneration++
    lastInsightUsageReport = null
    comparisonSqlInvalidated?.invoke()
    scenarioInsightView?.clear(insightText("stale"))
    previewTabs.beginRefresh("scenarios")
    for (id in listOf("scenario-export", "observed-sql-diff", "execution-candidates", "optimization")) previewTabs.remove(id)
}

internal data class ScenarioInputVersion(val generation: Long, val document: Document?, val stamp: Long?, val values: Map<String, String>) {
    fun matches(generation: Long, document: Document?, values: Map<String, String>): Boolean =
        this.generation == generation && this.document === document && stamp == document?.modificationStamp && this.values == values
}
