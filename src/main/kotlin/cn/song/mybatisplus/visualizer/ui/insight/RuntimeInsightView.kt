package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.log.agent.TransactionEvent
import cn.song.mybatisplus.visualizer.log.insight.*
import java.awt.BorderLayout
import javax.swing.*

internal data class RuntimeInsightSnapshot(val sessions: List<AnalysisSessionSnapshot>, val requests: List<ObservedRequest>, val transactions: List<TransactionEvent>)
internal interface RuntimeInsightSource {
    fun enable(enabled: Boolean)
    fun snapshot(): RuntimeInsightSnapshot
}

internal object RuntimeInsightPresentation {
    fun workload(snapshot: RuntimeInsightSnapshot, text: (String) -> String = { it }): List<InsightDetailRow> = snapshot.sessions.flatMapIndexed { index, session ->
        session.groups.map { g -> InsightDetailRow(listOf("${index + 1}", g.fingerprint, g.executions.toString(), g.cumulativeDurationNanos.toString(), "${g.errors}/${g.unknownStatus}"),
            "generation=${session.generation}\nenabled=${session.enabled}\nquality=${session.qualityIssues}\n" +
                "database=${runtimeTargetText(g.databaseIdentity, text)}\ncallSite=${g.callSite ?: "UNKNOWN"}\nmaxNanos=${g.maximumDurationNanos}\np95Nanos=${g.p95DurationNanos}\nsamples=${g.executions}") }
    }.take(2000)
    fun requests(snapshot: RuntimeInsightSnapshot, budget: SqlRequestBudget): List<InsightDetailRow> = snapshot.requests.take(500).map { request ->
        val verdict = RequestSqlBudget.evaluate(request.evidence, budget)
        InsightDetailRow(listOf(request.evidence.runId, request.requestId, verdict.status.name, verdict.observedExecutions.toString(), request.intervalUnionNanos?.toString() ?: "UNKNOWN"), buildString {
            appendLine("${verdict.status}: ${verdict.reasons}")
            appendLine("quality=${request.evidence.qualityIssues}; began=${request.evidence.began}; ended=${request.evidence.ended}")
            appendLine("intervalUnionNanos=${request.intervalUnionNanos ?: "UNKNOWN"}; cumulativeNanos=${verdict.observedCumulativeNanos}")
            request.evidence.observations.sortedBy { it.startNanos }.take(2000).forEach {
                appendLine("${it.startNanos} +${it.durationNanos}ns ${it.executionId} ${it.status} ${it.fingerprint}; tag=${it.requestTag ?: "UNKNOWN"}")
            }
            request.repeatedQueries.forEach { appendLine("${it.classification}: ${it.fingerprint} x${it.executions}; evidence=${it.executionIds}") }
        })
    }
    fun transactions(snapshot: RuntimeInsightSnapshot): List<InsightDetailRow> = snapshot.transactions.take(2000).map {
        InsightDetailRow(listOf(it.envelope.runId ?: "UNKNOWN", it.transactionId ?: "UNKNOWN", it.kind ?: "UNKNOWN", it.status ?: "UNKNOWN", it.outcome ?: "UNKNOWN"),
            "connection=${it.connectionId ?: "UNKNOWN"}\nlease=${it.leaseId ?: "UNKNOWN"}\nrequest=${it.requestId ?: "UNKNOWN"}\nautoCommit=${it.autoCommit ?: "UNKNOWN"}\ncoverage=${it.coverage ?: "UNKNOWN"}\nSQLState=${it.sqlState ?: "UNKNOWN"}")
    }
    fun baseline(snapshot: RuntimeInsightSnapshot): SqlWorkloadBaseline {
        val events = snapshot.sessions.flatMap { it.observations }
        val identity = events.map { "${it.runId}/${it.captureEpoch}/${it.databaseIdentity}" }.distinct().sorted().joinToString("|")
        return SqlWorkloadBaseline(identity, events, snapshot.sessions.flatMap { it.qualityIssues }.toSet(), null)
    }
}

internal class RuntimeInsightView(
    private val text: (String) -> String,
    private val source: RuntimeInsightSource,
    private val refresh: () -> Unit,
    close: () -> Unit,
    candidates: () -> Unit,
) {
    val panel = JPanel(BorderLayout())
    val workload = InsightDetailPanel(listOf(text("runtime.session"), text("runtime.shape"), text("runtime.count"), text("runtime.duration"), text("runtime.errors")), text("runtime.notice"))
    private val requests = InsightDetailPanel(listOf("runId", "requestId", text("runtime.verdict"), text("runtime.count"), text("runtime.union")), text("runtime.requestNotice"))
    private val transactions = InsightDetailPanel(listOf("runId", "transactionId", text("runtime.kind"), text("runtime.status"), text("runtime.outcome")), text("runtime.transactionNotice"))
    private val evidence = RuntimeEvidencePanel(text)
    private val enabled = JCheckBox(text("runtime.enable"))
    private val count = JSpinner(SpinnerNumberModel(100, 0, 1_000_000, 1))
    private val millis = JSpinner(SpinnerNumberModel(1000, 0, 1_000_000, 100))
    private var baseline: SqlWorkloadBaseline? = null
    private var last: RuntimeInsightSnapshot? = null
    private var closed = false
    init {
        enabled.addActionListener { source.enable(enabled.isSelected); refresh() }
        workload.actions.add(enabled)
        workload.button(text("runtime.refresh"), refresh)
        workload.button(text("runtime.candidates"), candidates)
        workload.button(text("runtime.baseline")) { last?.let { baseline = RuntimeInsightPresentation.baseline(it); workload.detail.text = text("runtime.baselineSaved") } }
        workload.button(text("runtime.compare")) {
            val before = baseline
            val now = last
            workload.detail.text = if (before == null || now == null) text("runtime.noBaseline")
                else SqlWorkloadBaselineComparator.compare(before, RuntimeInsightPresentation.baseline(now)).toString()
        }
        workload.button(text("close"), close)
        requests.actions.add(JLabel(text("runtime.maxCount"))); requests.actions.add(count)
        requests.actions.add(JLabel(text("runtime.maxMillis"))); requests.actions.add(millis)
        requests.button(text("runtime.evaluate")) { last?.let(::present) }
        panel.add(JTabbedPane().apply {
            addTab(text("runtime.workload"), workload)
            addTab(text("runtime.requests"), requests)
            addTab(text("runtime.transactions"), transactions)
            addTab(text("runtime.evidence"), evidence)
        }, BorderLayout.CENTER)
    }
    fun present(snapshot: RuntimeInsightSnapshot) {
        if (closed) return
        last = snapshot
        enabled.isSelected = snapshot.sessions.any { it.enabled }
        workload.showRows(RuntimeInsightPresentation.workload(snapshot, text))
        workload.status.text = text("runtime.notice") + "\n" + snapshot.sessions.mapIndexed { index, s -> "${index + 1}: enabled=${s.enabled}; quality=${s.qualityIssues}; bytes=${s.retainedBytes}" }.joinToString("\n")
        requests.showRows(RuntimeInsightPresentation.requests(snapshot, SqlRequestBudget((count.value as Number).toInt(), (millis.value as Number).toLong() * 1_000_000)))
        transactions.showRows(RuntimeInsightPresentation.transactions(snapshot))
        evidence.present(snapshot)
    }
    fun dispose() {
        if (closed) return
        closed = true; last = null; baseline = null
        source.enable(false) // Global analysis switch for all attached sessions, explicitly described in the notice.
    }
}
