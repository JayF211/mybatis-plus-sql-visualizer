package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.transport.CaptureLedger;
import cn.song.mybatisplus.sqlvis.agent.transport.TraceBus;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;

/** One observed Connection proxy lifetime, not a physical connection or a proven pool checkout.
 * No driver calls are added. Unknown entry autoCommit and disabled intervals invalidate evidence.
 */
final class TransactionTracker {
    private String connectionId, leaseId;
    private String transactionId;
    private Boolean autoCommit;
    private boolean closed;
    private long revision = -1, epoch = -1;
    private final IdentityHashMap<Object, String> savepoints = new IdentityHashMap<Object, String>();

    synchronized void sql(ExecutionScope event) {
        if (!sync()) return;
        if (Boolean.FALSE.equals(autoCommit) && transactionId == null) {
            transactionId = TraceBus.id("tx"); emit("BEGIN", null, null, "UNKNOWN");
        }
        event.connectionId = connectionId; event.leaseId = leaseId; event.transactionId = transactionId;
    }
    synchronized void after(String method, Object[] args, Object result, Throwable failure,
                            long entryRevision, long entryEpoch) {
        if (!sync() || entryRevision != revision || entryEpoch != epoch) return;
        String kind = kind(method);
        if (kind == null) return;
        String outcome = "UNKNOWN", savepoint = null;
        if ("getAutoCommit".equals(method)) {
            if (failure == null && result instanceof Boolean) autoCommit = (Boolean) result;
            return;
        }
        if (failure == null) {
            if ("setAutoCommit".equals(method)) {
                boolean next = (Boolean) args[0];
                if (next && Boolean.FALSE.equals(autoCommit) && transactionId != null) outcome = "COMMITTED";
                autoCommit = next;
            } else if ("commit".equals(method)) {
                if (Boolean.FALSE.equals(autoCommit) && transactionId != null) outcome = "COMMITTED";
            } else if ("rollback".equals(method)) {
                if (args == null || args.length == 0) {
                    if (Boolean.FALSE.equals(autoCommit) && transactionId != null) outcome = "ROLLED_BACK";
                } else {
                    kind = "ROLLBACK_TO_SAVEPOINT"; savepoint = savepoints.get(args[0]);
                    outcome = savepoint == null ? "UNKNOWN" : "SAVEPOINT_ROLLED_BACK";
                    // Driver-specific descendant invalidation is not inferred. Keep only the
                    // positively observed rollback target; other handles become unknown.
                    savepoints.clear();
                    if (savepoint != null) savepoints.put(args[0], savepoint);
                }
            } else if ("setSavepoint".equals(method) && result instanceof Savepoint) {
                if (Boolean.FALSE.equals(autoCommit) && transactionId == null) transactionId = TraceBus.id("tx");
                savepoint = TraceBus.id("savepoint");
                if (savepoints.size() >= 256) {
                    savepoints.clear(); TraceBus.hookFailed(); // explicit bounded-state truncation
                }
                savepoints.put(result, savepoint);
            } else if ("releaseSavepoint".equals(method)) {
                savepoint = savepoints.remove(args[0]);
            } else if ("close".equals(method)) {
                // Closing a pooled/logical connection is neither proof of commit nor rollback.
                closed = true;
            }
        } else if ("rollback".equals(method) && args != null && args.length == 1) {
            kind = "ROLLBACK_TO_SAVEPOINT"; savepoint = savepoints.get(args[0]);
        }
        emit(kind, savepoint, failure, outcome);
        if (failure == null && ("commit".equals(method)
                || ("rollback".equals(method) && (args == null || args.length == 0))
                || ("setAutoCommit".equals(method) && Boolean.TRUE.equals(autoCommit)) || "close".equals(method))) {
            transactionId = null; savepoints.clear();
        }
        if (failure != null && ("commit".equals(method) || "rollback".equals(method) || "setAutoCommit".equals(method))) {
            // JDBC errors do not prove whether the server completed a boundary.
            transactionId = null; autoCommit = null; savepoints.clear();
        }
    }
    private boolean sync() {
        long currentRevision = TraceBus.revision(), currentEpoch = CaptureLedger.captureEpoch();
        if (revision != currentRevision || epoch != currentEpoch || !TraceBus.enabled(TraceBus.TRANSACTION)) {
            transactionId = null; autoCommit = null; savepoints.clear();
            revision = currentRevision; epoch = currentEpoch;
        }
        if (closed || !TraceBus.enabled(TraceBus.TRANSACTION)) return false;
        if (connectionId == null) {
            connectionId = TraceBus.id("connection"); leaseId = TraceBus.id("lease");
        }
        return true;
    }
    private static String kind(String method) {
        if ("commit".equals(method)) return "COMMIT";
        if ("rollback".equals(method)) return "ROLLBACK";
        if ("setAutoCommit".equals(method)) return "AUTO_COMMIT";
        if ("getAutoCommit".equals(method)) return "OBSERVE_AUTO_COMMIT";
        if ("close".equals(method)) return "CLOSE";
        if ("setSavepoint".equals(method)) return "SAVEPOINT";
        if ("releaseSavepoint".equals(method)) return "RELEASE_SAVEPOINT";
        return null;
    }
    private void emit(String kind, String savepoint, Throwable failure, String outcome) {
        Map<String, Object> fields = TraceBus.facts(kind);
        fields.put("connectionId", connectionId); fields.put("leaseId", leaseId);
        fields.put("transactionId", transactionId); fields.put("savepointId", savepoint);
        fields.put("requestId", RequestHooks.currentId()); fields.put("autoCommit", autoCommit);
        fields.put("status", failure == null ? "SUCCESS" : "ERROR"); fields.put("outcome", outcome);
        fields.put("errorClass", failure == null ? null : failure.getClass().getName());
        fields.put("sqlState", failure instanceof SQLException ? ((SQLException) failure).getSQLState() : null);
        fields.put("coverage", "PROXIED_CONNECTION_ONLY");
        TraceBus.emit(TraceBus.TRANSACTION, revision, epoch, fields);
    }
}
