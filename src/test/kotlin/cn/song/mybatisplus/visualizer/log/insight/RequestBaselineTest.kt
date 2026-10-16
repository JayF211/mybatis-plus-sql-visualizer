package cn.song.mybatisplus.visualizer.log.insight

import org.junit.Assert.*
import org.junit.Test

class RequestBaselineTest {
    private val shape = "a".repeat(64)
    private fun evidence() = SqlRequestEvidence("raw-request", "raw-run", true, true, 1, 1, 0, 0, true, true,
        listOf(SqlExecutionObservation("raw-run", "e1", 1, 1, 1, 1, 10, 1, shape, "db-secret", "secret-path", "secret-tag",
            ExecutionObservationStatus.SUCCESS, requestId = "raw-request")))
    private fun baseline(e: SqlRequestEvidence = evidence()) = (RequestBaselines.capture(e, "local test", "jdbc:secret-context") as RequestBaselines.BuildResult.Ready).baseline
    private fun json(b: RequestBaseline = baseline()) = (RequestBaselineCodec.encode(b) as RequestBaselineCodec.EncodeResult.Ready).json

    @Test fun `explicit export roundtrip contains only digests counters label and quality`() {
        val encoded = json()
        for (secret in listOf("raw-run", "raw-request", "db-secret", "secret-path", "secret-tag", "jdbc:secret-context")) assertFalse(encoded.contains(secret))
        assertEquals(baseline(), (RequestBaselineCodec.decode(encoded) as RequestBaselineCodec.DecodeResult.Ready).baseline)
        assertEquals(SqlBudgetStatus.PASS, RequestBaselines.evaluate(baseline(), evidence(), "jdbc:secret-context").status)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, RequestBaselines.evaluate(baseline(), evidence(), "other").status)
    }

    @Test fun `filtered incomplete source survives export and cannot authorize pass`() {
        val partial = baseline(evidence().copy(expectedExecutions = 2, qualityIssues = setOf("password=do-not-export")))
        assertFalse(partial.complete)
        assertFalse(json(partial).contains("do-not-export"))
        val loaded = (RequestBaselineCodec.decode(json(partial)) as RequestBaselineCodec.DecodeResult.Ready).baseline
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, RequestBaselines.evaluate(loaded, evidence(), "jdbc:secret-context").status)
        assertTrue(loaded.coverageIssues.contains("terminal_count_mismatch"))
    }

    @Test fun `strict loading rejects duplicate fields versions counts unknown data and nested payloads`() {
        val valid = json()
        val invalid = listOf(valid.replaceFirst("{", "{\"schemaVersion\":1,"), valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("\"executions\":1", "\"executions\":2"), valid.replaceFirst("{", "{\"sql\":\"secret\","),
            valid.replace("\"environmentLabel\":\"local test\"", "\"environmentLabel\":{}"), valid + "{}",
            valid.replace("\"complete\":true", "\"complete\":false"))
        invalid.forEach { assertTrue(it, RequestBaselineCodec.decode(it) is RequestBaselineCodec.DecodeResult.Rejected) }
        assertTrue(RequestBaselineCodec.decode(" ".repeat(RequestBaselineCodec.MAX_BYTES + 1)) is RequestBaselineCodec.DecodeResult.Rejected)
    }

    @Test fun `credentials labels raw fingerprints and cancellation are rejected`() {
        assertTrue(RequestBaselines.capture(evidence(), "password hunter2", "ctx") is RequestBaselines.BuildResult.Rejected)
        val raw = evidence().copy(observations = evidence().observations.map { it.copy(fingerprint = "SELECT secret") })
        assertTrue(RequestBaselines.capture(raw, "test", "ctx") is RequestBaselines.BuildResult.Rejected)
        assertTrue(RequestBaselines.capture(evidence(), "test", "ctx") { true } is RequestBaselines.BuildResult.Rejected)
        assertTrue(RequestBaselineCodec.decode(json()) { true } is RequestBaselineCodec.DecodeResult.Rejected)
    }
}
