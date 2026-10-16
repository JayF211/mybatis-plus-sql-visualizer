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
    val module = file?.let { com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.module.Module?, RuntimeException> {
        com.intellij.openapi.module.ModuleUtilCore.findModuleForFile(it, proj)
    } }
    val scopeChoice = javax.swing.JComboBox(arrayOf(insightText("usage.currentFile"), insightText("usage.module"))).apply {
        selectedIndex = 0
        isEnabled = module != null
    }
    val service = ProjectSqlUsageService(proj)
    Disposer.register(this, service)
    val panel = InsightDetailPanel(listOf(insightText("usage.table"), insightText("usage.column"), insightText("usage.roles"), insightText("usage.file")), insightText("usage.notice"))
    val database = JTextField(16)
    val table = JTextField(18)
    val column = JTextField(12)
    var report: SqlUsageReport? = null
    var shown = emptyList<SqlUsageReference>()
    panel.actions.add(scopeChoice)
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
        val scope = if (scopeChoice.selectedIndex == 1 && module != null) SqlUsageSearchScope.ModuleFiles(module) else SqlUsageSearchScope.CurrentFile(file)
        service.collectAsync(scope, AnalysisSnapshot("", dialect, databaseContext = context)) { collected ->
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && !proj.isDisposed && current === result && file.isValid && file.modificationStamp == version) {
                    previewTabs.applyIfCurrent(ticket) {
                        report = collected; lastInsightUsageReport = collected
                        shown = collected.usages.take(2000); panel.showRows(rows(shown))
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
        shown = matches.references.take(2000)
        panel.showRows(rows(shown))
        panel.status.text = insightText("usage.notice") + "\ncomplete=${matches.complete}\n" + matches.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" }
    }
    panel.button(insightText("usage.navigate")) {
        shown.getOrNull(panel.selectedRow())?.let { navigateUsage(it.location) { message -> panel.status.text = message } }
    }
    panel.button(insightText("cancel")) { service.cancel(); panel.status.text = insightText("cancelled") }
    panel.button(insightText("close")) { previewTabs.remove("object-usage") }
    previewTabs.put("object-usage", insightText("usage.title"), panel, onRemove = { Disposer.dispose(service) })
    previewTabs.pane?.selectedComponent = panel
}
