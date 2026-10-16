package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.*
import cn.song.mybatisplus.visualizer.ui.insight.*
import org.junit.Assert.*
import org.junit.Test

class ComparisonUiPolicyTest {
    private fun request() = (SqlComparisonRequest.prepare("SELECT 1", "SELECT 2", "target", 4) as SqlComparisonRequest.Preparation.Ready).request
    @Test fun `unresolved placeholders and absent approvals fail closed`() {
        assertFalse(ComparisonUiPolicy.bindingsResolved("SELECT ?", "SELECT 1"))
        assertFalse(ComparisonUiPolicy.bindingsResolved("SELECT :name", "SELECT 1"))
        assertFalse(ComparisonUiPolicy.bindingsResolved("SELECT \${name}", "SELECT 1"))
        assertNull(ComparisonUiPolicy.safety(request(), ComparisonUiApprovals(false, true, true, false, true), false))
        assertNull(ComparisonUiPolicy.safety(request(), ComparisonUiApprovals(true, false, true, false, true), false))
        assertNull(ComparisonUiPolicy.safety(request(), ComparisonUiApprovals(true, true, true, false, true), true))
    }
    @Test fun `approved literal pair uses two unknown dialect gates and one shot exact consent`() {
        val request = request()
        val safety = ComparisonUiPolicy.safety(request, ComparisonUiApprovals(true, true, true, true, true), true)!!
        assertEquals(ExecutionDialectGate.RenderMode.UNKNOWN, safety.baselineDialect.renderMode)
        assertEquals(ExecutionDialectGate.RenderMode.UNKNOWN, safety.candidateDialect.renderMode)
        assertEquals(request.fingerprint, safety.requestFingerprint)
        val consent = SqlComparisonConsent(request)
        assertNotNull(consent.consume(true, "SELECT 1", "SELECT 2", "target", 4))
        assertNull(consent.consume(true, "SELECT 1", "SELECT 2", "target", 4))
        assertNull(SqlComparisonConsent(request()).consume(true, "SELECT 1", "SELECT 3", "target", 4))
    }
}
