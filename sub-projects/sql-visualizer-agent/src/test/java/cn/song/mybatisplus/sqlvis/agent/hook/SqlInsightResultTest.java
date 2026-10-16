package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.Test;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import static org.junit.Assert.*;

public class SqlInsightResultTest {
    @Test public void partialBatchIsLowerBoundNotExactCount() {
        SqlEvent event = new SqlEvent();
        EventBuilder.applyResult(event, "executeBatch", new int[]{3, -2, 4}, null);
        assertEquals(Long.valueOf(7), event.updateCount);
        assertEquals("PARTIAL", event.updateCountStatus);
        assertEquals("SUCCESS", event.resultStatus);
    }
    @Test public void allUnknownBatchIsNotZero() {
        SqlEvent event = new SqlEvent();
        EventBuilder.applyResult(event, "executeBatch", new int[]{-2, -2}, null);
        assertNull(event.updateCount);
        assertEquals("UNKNOWN", event.updateCountStatus);
    }
    @Test public void failedBatchCarriesPartialCountsAndSqlState() {
        SqlEvent event = new SqlEvent();
        BatchUpdateException failure = new BatchUpdateException("bad batch", "22000", 1, new int[]{2, -3});
        EventBuilder.applyResult(event, "executeBatch", null, failure);
        assertEquals("ERROR", event.resultStatus);
        assertEquals("22000", event.sqlState);
        assertEquals(Long.valueOf(2), event.updateCount);
        assertEquals("PARTIAL", event.updateCountStatus);
    }
    @Test public void queryAndFailedDmlHaveNoInventedCount() {
        SqlEvent event = new SqlEvent();
        EventBuilder.applyResult(event, "executeQuery", new Object(), null);
        assertEquals("UNKNOWN", event.updateCountStatus);
        event.updateCount = 4L;
        EventBuilder.applyResult(event, "executeUpdate", null, new SQLException("failed", "42000"));
        assertNull(event.updateCount);
        assertEquals("ERROR", event.resultStatus);
    }
    @Test public void legacyGateIsNestedSafe() {
        WeaveGate.enterProxy();
        WeaveGate.enterProxy();
        WeaveGate.exitProxy();
        assertTrue(WeaveGate.isProxyActive());
        WeaveGate.exitProxy();
        assertFalse(WeaveGate.isProxyActive());
    }
}
