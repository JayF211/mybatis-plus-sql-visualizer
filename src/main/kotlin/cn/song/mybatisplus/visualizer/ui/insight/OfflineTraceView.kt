package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.share.OfflineTraceImport
import cn.song.mybatisplus.visualizer.share.OfflineTraceSnapshot
import java.awt.BorderLayout
import java.net.URI
import javax.swing.*
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

internal fun boundedTextArea(maxCharacters: Int): JTextArea = JTextArea().apply {
    (document as AbstractDocument).documentFilter = object : DocumentFilter() {
        override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
            replace(fb, offset, 0, string, attr)
        }
        override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
            val value = text.orEmpty()
            if (fb.document.length - length + value.length <= maxCharacters) super.replace(fb, offset, length, value, attrs)
        }
    }
}

internal class OfflineTraceView(
    private val text: (String) -> String,
    parse: (String) -> Unit,
    close: () -> Unit,
    private val openLink: (URI) -> Unit,
) {
    val panel = JPanel(BorderLayout())
    val input = boundedTextArea(OfflineTraceImport.MAX_BYTES)
    val results = InsightDetailPanel(listOf("traceId", "spanId", text("runtime.kind"), text("runtime.duration"), text("runtime.status")), text("import.notice"))
    private val template = JTextField(32)
    private var snapshot: OfflineTraceSnapshot? = null
    init {
        results.button(text("import.parse")) { parse(input.text) }
        results.button(text("close"), close)
        results.actions.add(JLabel(text("import.linkTemplate")))
        results.actions.add(template)
        results.button(text("import.openLink")) {
            val span = snapshot?.spans?.getOrNull(results.selectedRow())
            val uri = span?.let { OfflineTraceImport.traceLink(template.text, it.traceId) }
            if (uri != null) openLink(uri) else results.status.text = text("import.invalidLink")
        }
        panel.add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(input), results).apply { resizeWeight = 0.25 }, BorderLayout.CENTER)
    }
    fun present(result: OfflineTraceImport.Result) {
        when (result) {
            is OfflineTraceImport.Result.Accepted -> {
                snapshot = result.snapshot
                results.showRows(result.snapshot.spans.map { span -> InsightDetailRow(
                    listOf(span.traceId, span.spanId, span.operation ?: "UNKNOWN", span.durationNanos.toString(), span.status.name),
                    "parent=${span.parentSpanId ?: "UNKNOWN"}\nservice=${span.service ?: "UNKNOWN"}\ndatabaseSystem=${span.databaseSystem ?: "UNKNOWN"}\nstartUnixNanos=${span.startUnixNanos}\ncoverage=${result.snapshot.completeness}") })
                results.status.text = text("import.notice") + "\n${result.snapshot.sourceFormat}; coverage=${result.snapshot.completeness}; skipped=${result.snapshot.skippedSpans}"
            }
            is OfflineTraceImport.Result.Rejected -> {
                snapshot = null; results.showRows(emptyList()); results.status.text = text("import.rejected") + ": " + result.reason
            }
        }
    }
}
