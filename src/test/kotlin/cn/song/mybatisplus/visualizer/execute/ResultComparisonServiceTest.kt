package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID

class ResultComparisonServiceTest {
    private val dialect = ExecutionDialectGate.Request(ExecutionDialectGate.RenderMode.MANUAL, "H2", ExecutionDialectGate.Intent.QUERY)
    private fun request(left: String = "SELECT 1 AS x", right: String = left, maxRows: Int = 10) =
        (SqlComparisonRequest.prepare(left, right, "fixture", 1, maxRows) as SqlComparisonRequest.Preparation.Ready).request
    private fun approve(request: SqlComparisonRequest): SqlComparisonRequest = SqlComparisonConsent(request).consume(
        true, request.baselineSql, request.candidateSql, request.targetIdentity, request.targetGeneration)!!
    private fun safety(request: SqlComparisonRequest) = ComparisonSafetyContext(request.fingerprint, dialect, dialect,
        rawSegmentsPresent = false, rawSegmentsApproved = false, parameterBindingsResolved = true,
        selectFunctionSideEffectsApproved = true)

    @Test fun `real execution preserves duplicates null empty and decimals`() {
        val request = request("SELECT CAST(NULL AS VARCHAR(8)) AS x UNION ALL SELECT '' UNION ALL SELECT ''")
        fixture { session ->
            val result = service(session).execute(approve(request), safety(request))
            assertEquals(SqlResultComparisonStatus.MATCH, result.comparison.status)
            assertEquals(3, result.baseline!!.rows.size)
            assertEquals(SqlResultValue.Null, result.baseline.rows[0][0])
            assertEquals(SqlResultValue.Text(""), result.baseline.rows[1][0])
        }
    }
    @Test fun `unconsumed and reused consent never opens connection`() {
        var opens = 0
        val service = ResultComparisonService({ _, _ -> opens++; error("must not open") }, { ComparisonTargetState("fixture", 1, "H2") })
        val request = request()
        assertEquals("consent_not_consumed_or_reused", service.execute(request, safety(request)).comparison.reason)
        assertEquals(0, opens)
        fixture { session ->
            val live = service(session); approve(request)
            assertEquals(SqlResultComparisonStatus.MATCH, live.execute(request, safety(request)).comparison.status)
            assertEquals("consent_not_consumed_or_reused", live.execute(request, safety(request)).comparison.reason)
        }
    }
    @Test fun `approval flags dialect and target are enforced before opening`() {
        val variants = listOf<(ComparisonSafetyContext) -> ComparisonSafetyContext>(
            { it.copy(selectFunctionSideEffectsApproved = false) },
            { it.copy(rawSegmentsPresent = true) },
            { it.copy(parameterBindingsResolved = false) },
            { it.copy(requestFingerprint = "other") },
            { it.copy(candidateDialect = dialect.copy(renderedDialectName = "MySQL")) },
        )
        for (change in variants) {
            val request = request(); var opens = 0
            val service = ResultComparisonService({ _, _ -> opens++; error("blocked") }, { ComparisonTargetState("fixture", 1, "H2") })
            assertEquals(SqlResultComparisonStatus.INCONCLUSIVE, service.execute(approve(request), change(safety(request))).comparison.status)
            assertEquals(0, opens)
        }
        val request = request(); var opens = 0
        val changed = ResultComparisonService({ _, _ -> opens++; error("blocked") }, { ComparisonTargetState("fixture", 2, "H2") })
        assertEquals("target_changed", changed.execute(approve(request), safety(request)).comparison.reason)
        assertEquals(0, opens)
    }
    @Test fun `target change between selects prevents candidate execution`() {
        val request = request(); var generation = 1L; var calls = 0
        val session = object : ComparisonQuerySession {
            override fun query(sql: String, contextKey: String, maxRows: Int, timeoutSeconds: Int,
                               cancellation: ComparisonCancellation, validate: () -> Unit): SqlResultSnapshot {
                validate(); calls++; generation++
                return SqlResultSnapshot(listOf(SqlResultColumn("x", java.sql.Types.INTEGER)), emptyList(), contextKey = contextKey)
            }
            override fun close() { }
        }
        val service = ResultComparisonService({ _, _ -> session }, { ComparisonTargetState("fixture", generation, "H2") })
        assertEquals("target_changed", service.execute(approve(request), safety(request)).comparison.reason)
        assertEquals(1, calls)
    }
    @Test fun `truncation failure cancellation and unproven order cannot match`() {
        fixture { session ->
            val request = request("SELECT X FROM SYSTEM_RANGE(1,3)", maxRows = 2)
            assertEquals("baseline_incomplete", service(session).execute(approve(request), safety(request)).comparison.reason)
        }
        fixture { session ->
            val request = request("SELECT missing_column FROM missing_table")
            assertEquals(SqlResultComparisonStatus.INCONCLUSIVE, service(session).execute(approve(request), safety(request)).comparison.status)
        }
        val request = request(); val cancelled = ComparisonCancellation().also { it.cancel() }
        val blocked = ResultComparisonService({ _, _ -> error("must not connect") }, { ComparisonTargetState("fixture", 1, "H2") })
        assertEquals("cancelled", blocked.execute(approve(request), safety(request), cancellation = cancelled).comparison.reason)
        fixture { session ->
            val ordered = request("SELECT 1 AS x ORDER BY x")
            assertEquals("order_not_proven", service(session).execute(approve(ordered), safety(ordered), ordered = true).comparison.reason)
        }
    }
    @Test fun `named binds and explicit cancellation do not bypass execution boundary`() {
        val request = request("SELECT :name AS x")
        var opens = 0
        val blocked = ResultComparisonService({ _, _ -> opens++; error("must not open") }, { ComparisonTargetState("fixture", 1, "H2") })
        assertEquals("select_or_binding_guard", blocked.execute(approve(request), safety(request)).comparison.reason)
        assertEquals(0, opens)
        val cancellation = ComparisonCancellation(); var cancels = 0
        cancellation.attach { cancels++; throw java.sql.SQLException("cancel unsupported") }
        cancellation.cancel(); cancellation.cancel(); cancellation.attach(null)
        assertEquals(1, cancels)
        try { cancellation.check(); fail() } catch (_: java.util.concurrent.CancellationException) { }
    }
    @Test fun `cancelling after baseline prevents candidate statement`() {
        val request = request(); val cancellation = ComparisonCancellation(); var calls = 0
        val session = object : ComparisonQuerySession {
            override fun query(sql: String, contextKey: String, maxRows: Int, timeoutSeconds: Int,
                cancellation: ComparisonCancellation, validate: () -> Unit): SqlResultSnapshot {
                calls++; cancellation.cancel()
                return SqlResultSnapshot(listOf(SqlResultColumn("x", java.sql.Types.INTEGER)), emptyList(), contextKey = contextKey)
            }
            override fun close() { }
        }
        val result = service(session).execute(approve(request), safety(request), cancellation = cancellation)
        assertEquals("cancelled", result.comparison.reason); assertEquals(1, calls)
    }
    @Test fun `fatal failures are never converted to inconclusive`() {
        val request = request(); val fatal = OutOfMemoryError("fixture")
        val service = ResultComparisonService({ _, _ -> throw java.lang.reflect.InvocationTargetException(fatal) }, { ComparisonTargetState("fixture", 1, "H2") })
        try { service.execute(approve(request), safety(request)); fail() } catch (actual: OutOfMemoryError) { assertSame(fatal, actual) }
    }
    private fun service(session: ComparisonQuerySession) = ResultComparisonService({ _, validate -> validate(); session }, { ComparisonTargetState("fixture", 1, "H2") })
    private fun fixture(block: (ComparisonQuerySession) -> Unit) {
        Class.forName("org.h2.Driver")
        DriverManager.getConnection("jdbc:h2:mem:compare_${UUID.randomUUID()}").use { connection ->
            block(object : ComparisonQuerySession {
                override fun query(sql: String, contextKey: String, maxRows: Int, timeoutSeconds: Int,
                                   cancellation: ComparisonCancellation, validate: () -> Unit): SqlResultSnapshot {
                    validate()
                    return connection.createStatement().use { statement ->
                        statement.queryTimeout = timeoutSeconds; statement.maxRows = maxRows + 1
                        statement.executeQuery(sql).use { rows -> SqlResultCollector.collect(rows, contextKey, maxRows = maxRows,
                            checkCancelled = { cancellation.check(); validate() }) }
                    }
                }
                override fun close() { }
            })
        }
    }
}
