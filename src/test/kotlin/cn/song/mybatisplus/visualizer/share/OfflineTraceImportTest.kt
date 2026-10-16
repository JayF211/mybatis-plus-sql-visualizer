package cn.song.mybatisplus.visualizer.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTraceImportTest {
    private val trace = "1234567890abcdef1234567890abcdef"
    private val span = "1234567890abcdef"
    private fun document(entries: String = entry()): String = """
        {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"secret-service"}}]},
        "scopeSpans":[{"spans":[$entries]}]}]}
    """.trimIndent()
    private fun entry(traceId: String = trace, status: String = "2", database: Boolean = true): String = """
        {"traceId":"$traceId","spanId":"$span","name":"SELECT secret_password FROM user",
         "startTimeUnixNano":"100","endTimeUnixNano":"150","status":{"code":$status},
         "attributes":[${if (database) """{"key":"db.system.name","value":{"stringValue":"postgresql"}},""" else ""}
         {"key":"db.query.text","value":{"stringValue":"SELECT secret_password"}},
         {"key":"db.operation.name","value":{"stringValue":"SELECT"}},
         {"key":"db.query.parameter.password","value":{"stringValue":"top-secret"}}]}
    """.trimIndent()

    @Test
    fun `imports bounded database spans without SQL or application identifiers`() {
        val result = OfflineTraceImport.parse(document()) as OfflineTraceImport.Result.Accepted
        val imported = result.snapshot.spans.single()
        assertEquals(50L, imported.durationNanos)
        assertEquals(ImportedSpanStatus.ERROR, imported.status)
        assertEquals("SELECT", imported.operation)
        assertEquals("postgresql", imported.databaseSystem)
        assertTrue(imported.service.orEmpty().startsWith("service_"))
        assertFalse(result.toString().contains("secret"))
        assertEquals("unknown_external_coverage", result.snapshot.completeness)
    }

    @Test
    fun `unset status is not success`() {
        val result = OfflineTraceImport.parse(document(entry(status = "0"))) as OfflineTraceImport.Result.Accepted
        assertEquals(ImportedSpanStatus.UNSET, result.snapshot.spans.single().status)
    }

    @Test
    fun `non database spans counted as skipped not fabricated database events`() {
        val result = OfflineTraceImport.parse(document(entry(database = false))) as OfflineTraceImport.Result.Accepted
        assertTrue(result.snapshot.spans.isEmpty())
        assertEquals(1, result.snapshot.skippedSpans)
    }

    @Test
    fun `duplicate keys and duplicate identities rejected`() {
        assertTrue(OfflineTraceImport.parse("{\"resourceSpans\":[],\"resourceSpans\":[]}") is OfflineTraceImport.Result.Rejected)
        val result = OfflineTraceImport.parse(document(entry() + "," + entry())) as OfflineTraceImport.Result.Rejected
        assertEquals("duplicate_span_identity", result.reason)
    }

    @Test
    fun `zero malformed and non hexadecimal ids rejected`() {
        for (id in listOf("0".repeat(32), "not-an-id", "g".repeat(32))) {
            assertEquals("invalid_span_identity", (OfflineTraceImport.parse(document(entry(traceId = id))) as OfflineTraceImport.Result.Rejected).reason)
        }
    }

    @Test
    fun `depth size and trailing content rejected`() {
        assertTrue(OfflineTraceImport.parse("[".repeat(40) + "0" + "]".repeat(40)) is OfflineTraceImport.Result.Rejected)
        assertTrue(OfflineTraceImport.parse(" ".repeat(OfflineTraceImport.MAX_BYTES + 1)) is OfflineTraceImport.Result.Rejected)
        assertTrue(OfflineTraceImport.parse(document() + "{}") is OfflineTraceImport.Result.Rejected)
        assertTrue(OfflineTraceImport.parse("{broken}") is OfflineTraceImport.Result.Rejected)
    }

    @Test
    fun `time ordering and invalid status rejected`() {
        assertEquals("invalid_span_time", (OfflineTraceImport.parse(document().replace("\"150\"", "\"90\"")) as OfflineTraceImport.Result.Rejected).reason)
        assertEquals("invalid_span_status", (OfflineTraceImport.parse(document(entry(status = "99"))) as OfflineTraceImport.Result.Rejected).reason)
    }

    @Test
    fun `trace links allow only configured https host and valid ids`() {
        assertNotNull(OfflineTraceImport.traceLink("https://observability.example/trace/{traceId}", trace))
        assertNull(OfflineTraceImport.traceLink("javascript:alert('{traceId}')", trace))
        assertNull(OfflineTraceImport.traceLink("file:///tmp/{traceId}", trace))
        assertNull(OfflineTraceImport.traceLink("https://user:pass@example.com/{traceId}", trace))
        assertNull(OfflineTraceImport.traceLink("https://{traceId}.example.com/", trace))
        assertNull(OfflineTraceImport.traceLink("https://example.com/{traceId}", "not-valid"))
        assertNull(OfflineTraceImport.traceLink("https://example.com/", trace))
    }
}
