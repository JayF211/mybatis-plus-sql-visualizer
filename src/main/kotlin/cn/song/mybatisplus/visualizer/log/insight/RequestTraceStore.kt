package cn.song.mybatisplus.visualizer.log.insight

import cn.song.mybatisplus.visualizer.log.agent.RequestEvent
import cn.song.mybatisplus.visualizer.log.agent.TransactionEvent

/** Control facts stay distinct from SQL counts and application-supplied MDC labels. */
data class ObservedRequest(
    val requestId: String,
    val parentRequestId: String?,
    val start: RequestEvent?,
    val end: RequestEvent?,
    val evidence: SqlRequestEvidence,
    val intervalUnionNanos: Long?,
    val repeatedQueries: List<RepeatedQueryEvidence>,
)

data class RepeatedQueryEvidence(val fingerprint: String, val callSite: String?, val databaseIdentity: String?,
    val executions: Int, val executionIds: List<String>, val classification: String = "REPEATED_QUERY_CANDIDATE")

class RequestTraceStore(private val maxRequests: Int = 500, private val maxTransactions: Int = 2000) {
    private data class PairOfEvents(var start: RequestEvent? = null, var end: RequestEvent? = null)
    private val requests = LinkedHashMap<String, PairOfEvents>()
    private val transactions = ArrayDeque<TransactionEvent>()
    private val seenControl = HashSet<Long>()
    private var controlTruncated = false

    @Synchronized
    fun clear() {
        requests.clear()
        transactions.clear()
        seenControl.clear()
        controlTruncated = false
    }

    @Synchronized
    fun accept(event: RequestEvent) {
        val id = event.requestId?.takeIf { it.length in 1..160 } ?: return
        if (event.kind !in setOf("START", "END")) return
        if (!admit(event.envelope.sequence)) return
        val pair = requests.getOrPut(id) { PairOfEvents() }
        if (event.kind == "START") pair.start = event else pair.end = event
        while (requests.size > maxRequests) {
            requests.remove(requests.keys.first())
            controlTruncated = true
        }
    }

    @Synchronized
    fun accept(event: TransactionEvent) {
        if (!admit(event.envelope.sequence)) return
        transactions.addLast(event.copy(errorClass = null, sqlState = event.sqlState?.take(8)))
        while (transactions.size > maxTransactions) {
            transactions.removeFirst()
            controlTruncated = true
        }
    }

    @Synchronized
    fun transactionEvents(): List<TransactionEvent> = transactions.toList()

    @Synchronized
    fun snapshot(sql: AnalysisSessionSnapshot): List<ObservedRequest> = requests.map { (id, pair) ->
        val start = pair.start
        val end = pair.end
        val runId = start?.envelope?.runId ?: end?.envelope?.runId.orEmpty()
        val events = sql.observations.filter { it.runId == runId && it.requestId == id }
        val quality = sql.qualityIssues.toMutableSet()
        if (controlTruncated) quality += "control_capacity"
        if (end?.asyncStarted != false) quality += "async_coverage_unknown"
        if (end?.generatedSql != end?.completedSql) quality += "sql_still_active"
        val expected = end?.completedSql?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        val evidence = SqlRequestEvidence(id, runId, start != null, end != null, end?.lastSqlSequence,
            expected, end?.sqlCaptureLost, if (end?.generatedSql != null && end.generatedSql == end.completedSql) 0 else null,
            crossThreadCoverageProven = false,
            supportedCapture = end?.coverage == "SERVLET_THREAD_ONLY", observations = events, qualityIssues = quality)
        val repeated = events.groupBy { Triple(it.fingerprint, it.callSite, it.databaseIdentity) }
            .filterValues { it.size >= 3 }.map { (key, group) ->
                RepeatedQueryEvidence(key.first, key.second, key.third, group.size, group.take(20).map { it.executionId })
            }
        ObservedRequest(id, start?.parentRequestId ?: end?.parentRequestId, start, end, evidence,
            intervalUnion(events), repeated)
    }

    private fun admit(sequence: Long?): Boolean {
        if (sequence == null || sequence <= 0) return false
        if (seenControl.size >= 10_000) {
            controlTruncated = true
            return false
        }
        return seenControl.add(sequence)
    }

    private fun intervalUnion(events: List<SqlExecutionObservation>): Long? {
        if (events.isEmpty()) return 0
        val intervals = events.map {
            if (it.durationNanos < 0 || it.startNanos > Long.MAX_VALUE - it.durationNanos) return null
            it.startNanos to it.startNanos + it.durationNanos
        }.sortedBy { it.first }
        var begin = intervals.first().first
        var end = intervals.first().second
        var total = 0L
        for (interval in intervals.drop(1)) {
            if (interval.first <= end) end = maxOf(end, interval.second) else {
                val length = end - begin
                if (length < 0 || Long.MAX_VALUE - total < length) return null
                total += length
                begin = interval.first
                end = interval.second
            }
        }
        val length = end - begin
        return if (length < 0 || Long.MAX_VALUE - total < length) null else total + length
    }
}
