package cn.song.mybatisplus.visualizer.sql.insight

import cn.song.mybatisplus.visualizer.log.agent.RequestEvent
import cn.song.mybatisplus.visualizer.log.agent.TraceEventEnvelope
import cn.song.mybatisplus.visualizer.log.insight.*
import cn.song.mybatisplus.visualizer.ui.insight.InsightPresenter
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory

/** Opt-in measurement through exact --tests selection. No timing assertion disguises an over-budget result. */
class SqlInsightPerfTest {
    @Test fun measureFixedModelWorkloads() {
        val metrics = linkedMapOf<String, Any>()
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val startCpu = os.processCpuTime
        val startWall = System.nanoTime()
        val startHeap = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        val sqls = listOf(
            "SELECT u.id,u.name FROM users u WHERE u.id=1",
            "SELECT u.id,o.amount FROM users u JOIN orders o ON o.user_id=u.id WHERE o.amount>10 ORDER BY o.amount",
            "WITH x AS (SELECT id,amount FROM orders) SELECT id,amount FROM x",
            "SELECT user_id,SUM(amount) AS total FROM orders GROUP BY user_id",
            "INSERT INTO archive(id,amount) SELECT id,amount FROM orders WHERE amount>100",
        )
        fun batch(): Unit { sqls.forEach { assertNotNull(SqlSemanticAnalyzer.analyze(AnalysisSnapshot(it)).rootScopeId) } }
        repeat(30) { batch() }
        val semantic = LongArray(250) { i -> timed { SqlSemanticAnalyzer.analyze(AnalysisSnapshot(sqls[i % sqls.size])) } }
        metrics["semanticWarm"] = stats(semantic) + mapOf("budgetP95Ms" to 200, "samples" to semantic.size,
            "meetsSuggestedBudget" to (percentile(semantic) <= 200_000_000L))
        val cancellation = LongArray(100) {
            var calls = 0
            timed {
                val model = SqlSemanticAnalyzer.analyze(AnalysisSnapshot(sqls[1])) { ++calls > 8 }
                assertTrue(model.diagnostics.any { it.code == DiagnosticCode.CANCELLED })
                assertTrue(model.relations.isEmpty())
            }
        }
        metrics["cancelDuringTraversal"] = stats(cancellation) + mapOf("budgetP95Ms" to 200,
            "includesParseTime" to true, "meetsSuggestedBudget" to (percentile(cancellation) <= 200_000_000L))
        val large = "SELECT " + "a,".repeat(40_000) + "a FROM t"
        metrics["oversizedSql"] = stats(LongArray(100) { timed {
            assertTrue(SqlSemanticAnalyzer.analyze(AnalysisSnapshot(large)).diagnostics.any { it.code == DiagnosticCode.INPUT_BUDGET_EXCEEDED })
        } })
        val within = "SELECT " + (1..500).joinToString(",") { "t.c$it" } + " FROM t"
        metrics["wide500Columns"] = stats(LongArray(20) { timed { SqlSemanticAnalyzer.analyze(AnalysisSnapshot(within)) } })

        val store = AnalysisSessionStore()
        val ticket = store.start(AnalysisCaptureBoundary("perf-run", 1, 0))
        metrics["ingest10000Nanos"] = timed { for (i in 1..10_000) assertTrue(store.accept(ticket, event(i))) }
        var snapshot = store.snapshot()
        assertEquals(10_000, snapshot.observations.size)
        metrics["boundedSnapshot10000"] = stats(LongArray(50) { timed { snapshot = store.snapshot() } })
        metrics["retainedEstimatedBytes"] = snapshot.retainedBytes
        assertTrue(snapshot.retainedBytes <= 64L * 1024 * 1024)
        store.accept(ticket, event(10_001))
        assertEquals(10_000, store.snapshot().observations.size)
        assertTrue("store_capacity" in store.snapshot().qualityIssues)
        val trace = RequestTraceStore()
        for (request in 0 until 500) {
            val id = "request-$request"
            for (end in listOf(false, true)) trace.accept(RequestEvent(
                TraceEventEnvelope("perf-run", "aa01", 1, request * 2L + if (end) 2 else 1, 1, 1000),
                if (end) "END" else "START", id, null, request * 100_000L, 90_000,
                if (end) "SUCCESS" else "UNKNOWN", "SERVLET_THREAD_ONLY", null, false,
                20, 20, (request + 1) * 20L, 0, 0))
        }
        val grouped = trace.snapshot(snapshot)
        assertEquals(500, grouped.size)
        assertEquals(10_000, grouped.sumOf { it.evidence.observations.size })
        metrics["group500Requests10000Sql"] = stats(LongArray(50) { timed { trace.snapshot(snapshot) } })
        val relations = (0 until 250).map { SemanticRelation("r$it", "s", RelationKind.TABLE, "table_$it", null,
            setOf(TableRole.READ, TableRole.WRITE), "local") }
        val graph = SqlSemanticModel(AnalysisSnapshot("SELECT 1"), listOf(SemanticScope("s", null, "graph fixture", SqlOperation.SELECT,
            relations.map { it.id }, emptyList())), relations,
            (0 until 500).map { SemanticDependency(DependencyKind.ASSIGNMENT, "s", "fixture", setOf(SemanticColumn("r${it % 100}", "a")),
                SemanticColumn("r${100 + it % 90}", "b")) }, emptyList(), "s", AnalysisQuality.CERTAIN)
        metrics["graph250Relations500Edges"] = stats(LongArray(50) { timed {
            val view = InsightPresenter.build(graph, { it })
            assertTrue(view.vertices.size <= 200); assertTrue(view.arrows.size <= 400); assertTrue(view.graphLimited)
        } })
        metrics["wholeMeasurement"] = mapOf("wallNanos" to System.nanoTime() - startWall,
            "processCpuNanos" to os.processCpuTime - startCpu, "heapBeforeBytes" to startHeap,
            "heapAfterBytes" to ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
            "hostContention" to "UNKNOWN: other IDE/UI activity may contend; not idle-host certification")
        metrics["environment"] = mapOf("java" to System.getProperty("java.version"), "vm" to System.getProperty("java.vm.name"),
            "processors" to Runtime.getRuntime().availableProcessors(), "heapUsedBytes" to ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
            "heapMaxBytes" to Runtime.getRuntime().maxMemory(), "gitSha" to ProcessBuilder("git", "rev-parse", "HEAD").start().inputStream.bufferedReader().readText().trim())
        val out = File("build/sql-insight-perf/model-metrics.json")
        out.parentFile.mkdirs(); out.writeText(GsonBuilder().setPrettyPrinting().create().toJson(metrics))
        println("SQL_INSIGHT_PERF ${out.absolutePath}")
        println(GsonBuilder().create().toJson(metrics))
    }
    private fun event(i: Int) = SqlExecutionObservation("perf-run", "execution-$i", i.toLong(), 1, 1000,
        i * 1000L, 500, 1, "template-${i % 50}", "local-fixture", "Fixture#query", null,
        ExecutionObservationStatus.SUCCESS, requestId = "request-${(i - 1) / 20}")
    private fun timed(action: () -> Unit): Long { val start = System.nanoTime(); action(); return System.nanoTime() - start }
    private fun percentile(values: LongArray): Long = values.sorted()[(values.size * 95 + 99) / 100 - 1]
    private fun stats(values: LongArray): Map<String, Any> = mapOf("samples" to values.size, "p50Nanos" to values.sorted()[values.size / 2],
        "p95Nanos" to percentile(values), "maxNanos" to values.max(), "totalNanos" to values.sum())
}
