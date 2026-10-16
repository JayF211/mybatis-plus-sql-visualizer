package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailPanel
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailRow
import cn.song.mybatisplus.visualizer.ui.insight.boundedTextArea
import com.intellij.openapi.application.ApplicationManager
import java.awt.BorderLayout
import javax.swing.*

/** User-supplied one-shot sample, not a runtime capture subscription or automatic execution pairing. */
internal fun SqlPreviewPanel.showObservedSqlDiff() {
    if (previewDisposed) return
    val doc = insightDocument() ?: return
    if (doc.textLength > 65_536) return
    val baseline = doc.text
    val stamp = doc.modificationStamp
    val preview = current
    val sample = boundedTextArea(65_536)
    val results = InsightDetailPanel(listOf(insightText("runtime.diffKind"), insightText("runtime.previewLine"), insightText("runtime.actualLine")), insightText("runtime.diffNotice"))
    val host = JPanel(BorderLayout()).apply { add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(sample), results).apply { resizeWeight = 0.3 }, BorderLayout.CENTER) }
    results.button(insightText("runtime.compareSample")) {
        if (current !== preview || insightDocument() !== doc || doc.modificationStamp != stamp) {
            results.showRows(emptyList()); results.status.text = insightText("stale"); return@button
        }
        val actual = sample.text
        var future: java.util.concurrent.Future<*>? = null
        val ticket = previewTabs.beginRefresh("observed-sql-diff") { future?.cancel(true) } ?: return@button
        future = ApplicationManager.getApplication().executeOnPooledThread {
            val diff = SqlDiffer.computeDiff(baseline, actual)
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && current === preview && insightDocument() === doc && doc.modificationStamp == stamp) {
                    previewTabs.applyIfCurrent(ticket) {
                        results.showRows(diff.take(2000).map { InsightDetailRow(listOf(it.type.name, it.oldLineNumber?.toString() ?: "—", it.newLineNumber?.toString() ?: "—"),
                            "${it.oldLine.orEmpty()}\n→\n${it.newLine.orEmpty()}") })
                        results.status.text = insightText("runtime.diffNotice") + "\ndiffRows=${diff.size}; displayed=${minOf(2000, diff.size)}"
                    }
                }
            }
        }
    }
    results.button(insightText("close")) { previewTabs.remove("observed-sql-diff") }
    previewTabs.put("observed-sql-diff", insightText("runtime.compareSample"), host)
    previewTabs.pane?.selectedComponent = host
}
