package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.insight.WriteImpactContext
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.log.agent.*
import org.junit.Assert.*
import org.junit.Test

class WriteImpactContextTest {
    @Test fun `static write context contains target predicate and non executed COUNT`() {
        val sql = "UPDATE account SET balance=0 WHERE id=7"
        val context = WriteImpactContext.capture(sql, 11, 3, MySqlDialect)
        assertEquals("account", context.target)
        assertEquals("id = 7", context.predicate)
        assertTrue(context.countProbe!!.contains("COUNT(*)"))
        assertTrue(context.limitation.contains("NOT_COMMIT"))
        assertTrue(context.matches(sql, 11, 3))
        assertFalse(context.matches(sql, 12, 3))
        assertFalse(context.matches(sql + "0", 11, 3))
    }
    @Test fun `unbounded and unknown dialect do not invent safe COUNT or matched transaction`() {
        assertNull(WriteImpactContext.capture("DELETE FROM account", 1, 1, MySqlDialect).countProbe)
        val context = WriteImpactContext.capture("DELETE FROM account WHERE id=1", 1, 1, null)
        assertNull(context.countProbe)
        val tx = TransactionEvent(TraceEventEnvelope("other-run", null, null, 1, 1, 1), "CLOSE", "conn", null, "tx", null, null, "SUCCESS", null, null, null, null, "UNKNOWN")
        val text = context.describe(tx, null, "COUNT is not commit")
        assertTrue(text.contains("MANUAL_NOTE_NOT_MATCHED"))
        assertTrue(text.contains("outcome=UNKNOWN"))
    }
}
