package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import java.util.UUID;

/** Process-local accounting, independent of the lossy SQL queue. No SQL/parameters retained here.
 * A snapshot is evidence, never an assertion of receiver completeness. Socket flush is not ACK.
 * Counters are cumulative for this agent run; disabled scopes and uninstrumented calls are excluded.
 */
public final class CaptureLedger {
    public static final String RUN_ID = UUID.randomUUID().toString();
    private static long generated, completed, omitted, captureFailed, encodingFailed, transportUncertain, written;
    private static long captureEpoch;

    public static synchronized long captureEpoch() { return captureEpoch; }
    public static synchronized void captureBoundary() { captureEpoch++; }

    public static synchronized long begin() {
        if (AgentState.isDisabled()) return 0;
        return ++generated;
    }
    public static synchronized void complete() { completed++; }
    public static synchronized void omitted() { omitted++; }
    public static synchronized void captureFailed() { captureFailed++; }
    public static synchronized void encodingFailed() { encodingFailed++; }
    public static synchronized void transportUncertain() { transportUncertain++; }
    public static synchronized void written() { written++; }

    /** terminal means a quiescent STOP snapshot only, never process exit or request completion.
     * Reopening invalidates that boundary; no stream ACK or request/transaction coverage is claimed.
     */
    public static synchronized String snapshot() {
        boolean terminal = AgentState.isDisabled() && generated == completed;
        return "{\"protocolVersion\":2,\"runId\":\"" + RUN_ID
                + "\",\"kind\":\"" + (terminal ? "STOP" : "QUALITY") + "\",\"terminal\":" + terminal
                + ",\"captureEpoch\":" + captureEpoch
                + ",\"generated\":" + generated + ",\"lastSequence\":" + generated
                + ",\"completed\":" + completed + ",\"active\":" + (generated - completed)
                + ",\"omitted\":" + omitted + ",\"captureFailed\":" + captureFailed
                + ",\"queueDropped\":" + EventBus.droppedCount() + ",\"cleared\":" + EventBus.clearedCount()
                + ",\"encodingFailed\":" + encodingFailed + ",\"transportUncertain\":" + transportUncertain
                + ",\"written\":" + written
                + ",\"sampled\":0,\"coverage\":\"INSTRUMENTED_JDBC_ONLY\""
                + ",\"capabilities\":[\"EXECUTION_ID\",\"MONOTONIC_NANOS\",\"JDBC_RESULT\",\"LOSS_COUNTERS\"]"
                + ",\"limitations\":[\"NO_REQUEST_BOUNDARIES\",\"NO_TRANSACTION_OUTCOME\",\"NO_DELIVERY_ACK\","
                + "\"PRE_ATTACH_PREPARED_SQL_MAY_BE_UNKNOWN\",\"WOVEN_PARAMETERS_UNAVAILABLE\",\"STACK_BOUNDED\","
                + "\"CROSS_OBJECT_WRAPPER_IDENTITY_UNPROVEN\",\"NO_PROCESS_EXIT_TERMINAL\"]}";
    }
    private CaptureLedger() { }
}
