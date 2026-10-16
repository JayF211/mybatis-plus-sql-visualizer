package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.log.insight.SqlExecutionObservation
import java.awt.*
import javax.swing.*

internal data class RepeatedAlertKey(val fingerprint: String, val database: String?, val callSite: String?)

/** Presentation-only suppression: observations, workload counts and budget inputs stay untouched. */
internal class RuntimeEvidencePanel(private val text: (String) -> String) : JPanel(BorderLayout()) {
    private val ignored = linkedSetOf<RepeatedAlertKey>()
    private var snapshot: RuntimeInsightSnapshot? = null
    private var keys = emptyList<RepeatedAlertKey>()
    private val alerts = InsightDetailPanel(listOf(text("runtime.shape"), text("runtime.count"), "database", "callSite"), text("runtime.repeatNotice"))
    private val tags = InsightDetailPanel(listOf("runId", "requestTag", text("runtime.count")), text("runtime.tagNotice"))
    private val timeline = RuntimeTimeline()
    private val run = JComboBox<String>()
    init {
        alerts.button(text("runtime.ignore")) {
            keys.getOrNull(alerts.selectedRow())?.let { ignored += it }
            snapshot?.let(::present)
        }
        alerts.button(text("runtime.restore")) { ignored.clear(); snapshot?.let(::present) }
        run.addActionListener { updateTimeline() }
        add(JTabbedPane().apply {
            addTab(text("runtime.tags"), tags)
            addTab(text("runtime.repeated"), alerts)
            addTab(text("runtime.timeline"), JPanel(BorderLayout()).apply { add(run, BorderLayout.NORTH); add(JScrollPane(timeline), BorderLayout.CENTER) })
        }, BorderLayout.CENTER)
    }
    fun present(value: RuntimeInsightSnapshot) {
        snapshot = value
        val events = value.sessions.flatMap { it.observations }
        tags.showRows(events.groupBy { it.runId to it.requestTag }.entries.take(2000).map { (key, list) ->
            InsightDetailRow(listOf(key.first, key.second ?: "UNKNOWN", list.size.toString()), text("runtime.tagNotice") + "\n" + list.take(200).joinToString("\n") { "${it.executionId} ${it.fingerprint} ${it.status}" })
        })
        val repeated = value.requests.flatMap { it.repeatedQueries }.filter { RepeatedAlertKey(it.fingerprint, it.databaseIdentity, it.callSite) !in ignored }.take(2000)
        keys = repeated.map { RepeatedAlertKey(it.fingerprint, it.databaseIdentity, it.callSite) }
        alerts.showRows(repeated.map { InsightDetailRow(listOf(it.fingerprint, it.executions.toString(), it.databaseIdentity ?: "UNKNOWN", it.callSite ?: "UNKNOWN"), "${it.classification}\n${it.executionIds}") })
        alerts.status.text = text("runtime.repeatNotice") + "\nignored=${ignored.size}"
        val selected = run.selectedItem
        run.model = DefaultComboBoxModel(events.map { it.runId }.distinct().take(100).toTypedArray())
        if (selected != null && events.any { it.runId == selected }) run.selectedItem = selected
        updateTimeline()
    }
    private fun updateTimeline() {
        timeline.present(snapshot?.sessions.orEmpty().flatMap { it.observations }.filter { it.runId == run.selectedItem }, text("runtime.timelineNotice"))
    }
    internal fun ignoredForTest(): Set<RepeatedAlertKey> = ignored.toSet()
}

internal class RuntimeTimeline : JComponent() {
    private var events = emptyList<SqlExecutionObservation>()
    private var notice = ""
    fun present(values: List<SqlExecutionObservation>, explanation: String) {
        events = values.filter { it.startNanos >= 0 && it.durationNanos >= 0 && it.durationNanos <= Long.MAX_VALUE - it.startNanos }
            .sortedBy { it.startNanos }.take(200)
        notice = explanation
        preferredSize = Dimension(900, maxOf(100, 60 + events.size * 26))
        revalidate(); repaint()
    }
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.color = UIManager.getColor("Panel.background") ?: Color.WHITE; g.fillRect(0, 0, width, height)
            g.color = UIManager.getColor("Label.foreground") ?: Color.BLACK
            g.drawString(notice.take(120), 8, 20)
            if (events.isEmpty()) return
            val min = events.minOf { it.startNanos }.toDouble()
            val max = events.maxOf { it.startNanos + it.durationNanos }.toDouble()
            val span = (max - min).coerceAtLeast(1.0)
            val available = (width - 240).coerceAtLeast(100)
            events.forEachIndexed { index, event ->
                val y = 40 + index * 26
                g.drawString("${event.threadId ?: "?"} ${event.executionId.take(20)}", 8, y + 13)
                val x = 225 + ((event.startNanos.toDouble() - min) / span * available).toInt()
                val w = (event.durationNanos.toDouble() / span * available).toInt().coerceAtLeast(2)
                g.drawRect(x, y, w, 16)
                g.drawString(event.status.name, x + 3, y + 13)
            }
        } finally { g.dispose() }
    }
}
