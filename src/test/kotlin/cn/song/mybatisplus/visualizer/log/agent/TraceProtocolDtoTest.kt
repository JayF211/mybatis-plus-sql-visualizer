package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.*
import org.junit.Test

class TraceProtocolDtoTest {
    @Test fun missingControlQualityIsUnknownNotZero() {
        val state = AgentCaptureState.parse("{}")!!
        assertNull(state.controlGenerated)
        assertNull(state.controlDropped)
        assertNull(state.controlCleared)
        assertNull(state.controlEncodingFailed)
        assertNull(state.controlTransportUncertain)
        assertNull(state.controlWritten)
        assertNull(state.controlHookFailed)
        assertNull(state.requestTracing)
        assertNull(state.transactionTracing)
    }
    @Test fun explicitQualityAndMalformedValuesStayDistinct() {
        val state = AgentCaptureState.parse("""{"controlGenerated":3,"controlDropped":0,"controlCleared":-1,"controlEncodingFailed":0.5,"controlTransportUncertain":"0","requestTracing":true,"transactionTracing":false}""")!!
        assertEquals(3L, state.controlGenerated)
        assertEquals(0L, state.controlDropped)
        assertNull(state.controlCleared)
        assertNull(state.controlEncodingFailed)
        assertNull(state.controlTransportUncertain)
        assertEquals(true, state.requestTracing)
        assertEquals(false, state.transactionTracing)
    }
    @Test fun requestAndTransactionRemainScopedObservations() {
        val request = RequestEvent.parse("""{"kind":"END","requestId":"r1","coverage":"SERVLET_THREAD_ONLY","generatedSql":0,"sequence":7}""")!!
        assertEquals("r1", request.requestId)
        assertEquals(7L, request.envelope.sequence)
        assertNull(request.sqlCaptureLost)
        val transaction = TransactionEvent.parse("""{"kind":"CLOSE","status":"SUCCESS","outcome":"UNKNOWN","coverage":"PROXIED_CONNECTION_ONLY"}""")!!
        assertEquals("UNKNOWN", transaction.outcome)
        assertNull(transaction.transactionId)
    }
}
