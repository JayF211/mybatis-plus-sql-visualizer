package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.insight.*
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailPanel
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailRow
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import javax.swing.JLabel
import javax.swing.JTextField

internal fun SqlPreviewPanel.showObjectUsage() {
    if (previewDisposed || project?.isDisposed == true) return
    val proj = project ?: return
    val file = FileEditorManager.getInstance(proj).selectedFiles.firstOrNull()
    val service = ProjectSqlUsageService(proj)
    Disposer.register(this, service)
    val panel = InsightDetailPanel(listOf(insightText("usage.table"), insightText("usage.column"), insightText("usage.roles"), insightText("usage.file")), insightText("usage.notice"))
    val database = JTextField(16)
    val table = JTextField(18)
    val column = JTextField(12)
    var report: SqlUsageReport? = null
    panel.actions.add(JLabel(insightText("usage.database"))); panel.actions.add(database)
    panel.actions.add(JLabel(insightText("usage.table"))); panel.actions.add(table)
    panel.actions.add(JLabel(insightText("usage.column"))); panel.actions.add(column)
    fun rows(refs: List<SqlUsageReference>) = refs.take(2000).map { ref ->
        InsightDetailRow(listOf(ref.tableName, ref.column ?: "—", ref.roles.joinToString(), ref.location.filePath),
            "${ref.quality} · ${ref.dependencyKinds}\ndatabase=${ref.databaseContext ?: "UNKNOWN"}\nlocation=${ref.location}\n" + insightText("usage.location"))
    }
    panel.button(insightText("usage.collect")) {
        if (file == null) { panel.status.text = insightText("usage.noFile"); return@button }
        val version = file.modificationStamp
        val result = current
        val dialect = insightDialect()
        val context = database.text.trim().takeIf { it.isNotEmpty() }
        val ticket = previewTabs.beginRefresh("object-usage") { service.cancel() } ?: return@button
        report = null; panel.showRows(emptyList()); panel.status.text = insightText("loading")
        service.collectAsync(SqlUsageSearchScope.CurrentFile(file), AnalysisSnapshot("", dialect, databaseContext = context)) { collected ->
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && !proj.isDisposed && current === result && file.isValid && file.modificationStamp == version) {
                    previewTabs.applyIfCurrent(ticket) {
                        report = collected; panel.showRows(rows(collected.usages))
                        panel.status.text = insightText("usage.notice") + "\n${collected.coverage}\n" + collected.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" }
                    }
                }
            }
        }
    }
    panel.button(insightText("usage.find")) {
        val snapshot = report ?: return@button
        // Identifier parts are explicitly separated by / so quoted dots are never split speculatively.
        val parts = table.text.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val matches = SqlObjectUsageAnalyzer.findUsages(snapshot, SqlObjectQuery(database.text.trim().takeIf { it.isNotEmpty() }, parts,
            column.text.trim().takeIf { it.isNotEmpty() }, insightDialect()))
        panel.showRows(rows(matches.references))
        panel.status.text = insightText("usage.notice") + "\ncomplete=${matches.complete}\n" + matches.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" }
    }
    panel.button(insightText("cancel")) { service.cancel(); panel.status.text = insightText("cancelled") }
    panel.button(insightText("close")) { previewTabs.remove("object-usage") }
    previewTabs.put("object-usage", insightText("usage.title"), panel, onRemove = { Disposer.dispose(service) })
    previewTabs.pane?.selectedComponent = panel
}
