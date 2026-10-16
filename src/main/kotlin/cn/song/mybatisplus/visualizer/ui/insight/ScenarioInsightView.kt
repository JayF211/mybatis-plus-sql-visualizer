package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.share.SqlScenarioRegressionPackage
import cn.song.mybatisplus.visualizer.xml.XmlScenarioInput
import cn.song.mybatisplus.visualizer.xml.XmlScenarioResult
import javax.swing.JButton

internal object ScenarioInsightPresentation {
    fun inputs(current: Map<String, String>, stored: List<Pair<String, Map<String, String>>>): List<XmlScenarioInput> =
        listOf(XmlScenarioInput("current", current.toMap())) + stored.take(19).mapIndexed { index, (_, values) ->
            XmlScenarioInput("preset-${index + 1}", values.toMap())
        }

    fun row(result: XmlScenarioResult, label: String): InsightDetailRow {
        val semantic = result.semantics
        val trace = result.trace
        val details = buildString {
            appendLine(result.sql.orEmpty())
            appendLine("\nQUALITY: ${semantic?.quality ?: "UNKNOWN"}")
            semantic?.relations.orEmpty().forEach { appendLine("${it.name} ${it.alias.orEmpty()} · ${it.roles}") }
            semantic?.outputFields.orEmpty().forEach { appendLine("${it.index + 1}. ${it.name ?: it.expression} · ${it.quality} · ${it.sources}") }
            appendLine("\nCONDITIONS: ${result.conditionResults}")
            appendLine("\nTRACE: ${trace?.locationKind ?: "UNKNOWN"}; truncated=${trace?.truncated ?: false}")
            trace?.events.orEmpty().take(2000).forEach {
                appendLine("${it.nodePath} #${it.invocation} ${it.kind} accepted=${it.accepted} basis=${it.conditionBasis ?: "UNKNOWN"}")
                it.expression?.let(::appendLine); it.appendedTemplate?.let(::appendLine)
            }
            result.warnings.forEach(::appendLine)
            semantic?.diagnostics.orEmpty().forEach { appendLine("${it.code} ${it.quality}: ${it.message}") }
            result.failure?.let { appendLine("FAILED: $it") }
        }
        return InsightDetailRow(listOf(label, semantic?.quality?.name ?: "UNKNOWN", result.parameterCount.toString(),
            result.failure ?: "${semantic?.relations?.size ?: 0}"), details)
    }
}

internal class ScenarioInsightView(
    text: (String) -> String,
    refresh: () -> Unit,
    cancel: () -> Unit,
    close: () -> Unit,
    private val exportPreview: (String) -> Unit,
) {
    val panel = InsightDetailPanel(listOf(text("scenario.name"), text("quality"), text("scenario.parameters"), text("scenario.relations")), text("scenario.notice"))
    private var results = emptyList<XmlScenarioResult>()
    private val exportButton: JButton
    init {
        panel.button(text("refresh"), refresh)
        panel.button(text("cancel"), cancel)
        panel.button(text("close"), close)
        exportButton = panel.button(text("scenario.export")) {
            when (val exported = SqlScenarioRegressionPackage.export(results)) {
                is SqlScenarioRegressionPackage.Export.Ready -> exportPreview(exported.json)
                is SqlScenarioRegressionPackage.Export.Rejected -> panel.status.text = exported.reason
            }
        }.apply { isEnabled = false }
    }
    fun clear(message: String) {
        results = emptyList(); exportButton.isEnabled = false
        panel.showRows(emptyList()); panel.status.text = message
    }
    fun present(values: List<XmlScenarioResult>, labels: List<String>, message: String) {
        results = values
        panel.showRows(values.mapIndexed { index, value -> ScenarioInsightPresentation.row(value, labels.getOrElse(index) { value.name }) })
        panel.status.text = message
        exportButton.isEnabled = values.isNotEmpty()
    }
}
