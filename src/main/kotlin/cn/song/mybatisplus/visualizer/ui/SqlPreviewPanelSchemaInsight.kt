package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.share.SqlSchemaSnapshotCodec
import cn.song.mybatisplus.visualizer.sql.insight.SqlSchemaDiffer
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailPanel
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailRow
import cn.song.mybatisplus.visualizer.ui.insight.boundedTextArea
import com.intellij.openapi.application.ApplicationManager
import java.awt.BorderLayout
import javax.swing.*

internal fun SqlPreviewPanel.showSchemaComparison() {
    if (previewDisposed) return
    val existing = previewTabs.component("schema-comparison")
    if (existing != null) { previewTabs.pane?.selectedComponent = existing; return }
    val before = boundedTextArea(1024 * 1024)
    val after = boundedTextArea(1024 * 1024)
    val results = InsightDetailPanel(listOf(insightText("schema.change"), insightText("schema.table"), insightText("schema.column")), insightText("schema.notice"))
    val host = JPanel(BorderLayout()).apply {
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT,
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(before), JScrollPane(after)).apply { resizeWeight = 0.5 }, results).apply { resizeWeight = 0.3 }, BorderLayout.CENTER)
    }
    results.button(insightText("schema.compare")) {
        val left = before.text; val right = after.text
        var future: java.util.concurrent.Future<*>? = null
        val ticket = previewTabs.beginRefresh("schema-comparison") { future?.cancel(true) }
        if (ticket != null) future = ApplicationManager.getApplication().executeOnPooledThread {
            val a = SqlSchemaSnapshotCodec.parse(left)
            val b = SqlSchemaSnapshotCodec.parse(right)
            val report = if (a is SqlSchemaSnapshotCodec.Result.Accepted && b is SqlSchemaSnapshotCodec.Result.Accepted)
                SqlSchemaDiffer.compare(a.snapshot, b.snapshot, isCancelled = { Thread.currentThread().isInterrupted }) else null
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed) previewTabs.applyIfCurrent(ticket) {
                    if (report == null) {
                        results.showRows(emptyList())
                        results.status.text = listOfNotNull((a as? SqlSchemaSnapshotCodec.Result.Rejected)?.reason, (b as? SqlSchemaSnapshotCodec.Result.Rejected)?.reason).joinToString("\n")
                    } else {
                        results.showRows(report.changes.take(2000).map { change -> InsightDetailRow(
                            listOf(change.kind.name, change.tableNameParts.joinToString("."), change.column ?: "—"),
                            "before=${change.beforeType ?: "UNKNOWN"}\nafter=${change.afterType ?: "UNKNOWN"}\naffected=${change.affected}") })
                        results.status.text = insightText("schema.notice") + "\ndatabase=${report.databaseContext}; ${report.beforeVersion} → ${report.afterVersion}; comparable=${report.comparable}; impactComplete=${report.impactComplete}\n" + report.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" }
                    }
                }
            }
        }
    }
    results.button(insightText("cancel")) { previewTabs.beginRefresh("schema-comparison"); results.status.text = insightText("cancelled") }
    results.button(insightText("close")) { previewTabs.remove("schema-comparison") }
    previewTabs.put("schema-comparison", insightText("schema.title"), host)
    previewTabs.pane?.selectedComponent = host
}
