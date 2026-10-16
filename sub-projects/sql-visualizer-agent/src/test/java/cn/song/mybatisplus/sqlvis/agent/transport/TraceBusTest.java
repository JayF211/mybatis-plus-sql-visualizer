package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.hook.RequestHooks;
import org.junit.*;
import static org.junit.Assert.*;

public class TraceBusTest {
    private boolean disabled;
    @Before public void setup() { disabled = AgentState.isDisabled(); AgentState.setDisabled(false); TraceBus.disconnected(); }
    @After public void cleanup() { TraceBus.disconnected(); AgentState.setDisabled(disabled); }
    @Test public void oldConfigCannotEnableTraceAndNewConfigCan() {
        SenderLoop sender = new SenderLoop(0);
        sender.handleInbound(EventBus.CMD_CONFIG, "{\"disabled\":false}");
        assertFalse(TraceBus.enabled(7)); assertFalse(TraceBus.enabled(8));
        sender.handleInbound(EventBus.CMD_CONFIG, "{\"requestTracing\":true,\"captureStateToken\":\"aaaa\"}");
        long token = RequestHooks.enter(null); RequestHooks.exit(token, null);
        assertTrue(TraceBus.poll().body.contains("aaaa")); assertNotNull(TraceBus.poll());
        assertTrue(sender.captureStateBody().contains("controlGenerated"));
    }
    @Test public void nonceChangeDropsOldQueueAndOldRequestEnd() {
        TraceBus.configure(true, false, "aaaa", true);
        long token = RequestHooks.enter(null);
        TraceBus.configure(true, false, "bbbb", true);
        RequestHooks.exit(token, null);
        assertNull(TraceBus.poll()); assertNull(RequestHooks.currentId());
        assertTrue(TraceBus.qualityFields().contains("controlCleared"));
    }
    @Test public void fullControlQueueStillProducesLossEvidence() {
        TraceBus.configure(true, false, "aaaa", true);
        long before = number("controlDropped");
        for (int i = 0; i < 1025; i++) TraceBus.emit(7, TraceBus.revision(), CaptureLedger.captureEpoch(), TraceBus.facts("START"));
        assertEquals(before + 1, number("controlDropped"));
        assertTrue(TraceBus.qualityFields().contains("controlGenerated"));
    }
    @Test public void nestingBudgetDropsObservationsExplicitlyAndCleansThread() {
        TraceBus.configure(true, false, "aaaa", true);
        long before = number("controlHookFailed");
        long[] scopes = new long[128];
        for (int i = 0; i < scopes.length; i++) { scopes[i] = RequestHooks.enter(null); assertTrue(scopes[i] > 0); }
        assertEquals(0, RequestHooks.enter(null));
        assertEquals(before + 1, number("controlHookFailed"));
        for (int i = scopes.length - 1; i >= 0; i--) RequestHooks.exit(scopes[i], null);
        assertNull(RequestHooks.currentId());
    }

    @Test public void disabledAndReopenedCannotReassociateInflightRequest() {
        TraceBus.configure(true, false, "aaaa", true);
        long token = RequestHooks.enter(null); assertNotNull(TraceBus.poll());
        AgentState.setDisabled(true); AgentState.setDisabled(false);
        assertNull(RequestHooks.currentId()); RequestHooks.exit(token, null);
        assertNull(TraceBus.poll());
    }
    private static long number(String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + key + "\":(\\d+)").matcher(TraceBus.qualityFields());
        assertTrue(matcher.find()); return Long.parseLong(matcher.group(1));
    }
}
