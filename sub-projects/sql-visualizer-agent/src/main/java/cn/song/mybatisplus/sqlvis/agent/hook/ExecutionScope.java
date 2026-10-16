package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.transport.CaptureLedger;
import java.util.ArrayDeque;

/** Shared identity before capture/queue failures. Same-object driver delegation is one execution;
 * a genuinely nested call on another statement remains a distinct execution. No text/time dedup.
 */
final class ExecutionScope {
    private static final ThreadLocal<ArrayDeque<ExecutionScope>> ACTIVE = new ThreadLocal<ArrayDeque<ExecutionScope>>();
    final Object statement;
    final long sequence, startNanos, startMillis, threadId, captureEpoch;
    final String source;
    RequestHooks.Scope request;
    boolean reported, failed;
    String connectionId, leaseId, transactionId;

    private ExecutionScope(Object statement, String source, long sequence) {
        this.statement = statement;
        this.source = source;
        this.sequence = sequence;
        captureEpoch = CaptureLedger.captureEpoch();
        startNanos = System.nanoTime();
        startMillis = System.currentTimeMillis();
        threadId = Thread.currentThread().getId();
    }
    static ExecutionScope begin(Object stmt, String source) {
        ArrayDeque<ExecutionScope> stack = ACTIVE.get();
        if (stack != null) for (ExecutionScope scope : stack) if (scope.statement == stmt) return null;
        final ExecutionScope scope;
        synchronized (CaptureLedger.class) {
            long sequence = CaptureLedger.begin();
            if (sequence == 0) return null;
            try {
                scope = new ExecutionScope(stmt, source, sequence);
            } catch (Throwable failure) {
                CaptureLedger.captureFailed();
                CaptureLedger.complete();
                return null;
            }
        }
        try {
            if (stack == null) { stack = new ArrayDeque<ExecutionScope>(); ACTIVE.set(stack); }
            stack.push(scope);
            scope.request = RequestHooks.current();
            if (scope.request != null) RequestHooks.sqlBegin(scope.sequence);
            return scope;
        } catch (Throwable failure) {
            CaptureLedger.captureFailed();
            CaptureLedger.complete();
            return null;
        }
    }
    static ExecutionScope find(long token) {
        ArrayDeque<ExecutionScope> stack = ACTIVE.get();
        if (stack != null) for (ExecutionScope scope : stack) if (scope.sequence == token) return scope;
        return null;
    }
    void apply(SqlEvent event) {
        event.runId = CaptureLedger.RUN_ID;
        event.executionId = CaptureLedger.RUN_ID + ":" + sequence;
        event.sequence = sequence;
        event.captureEpoch = captureEpoch;
        event.threadId = threadId;
        event.startNanos = startNanos;
        event.timestamp = startMillis;
        event.captureSource = source;
        event.timestampSource = "ENTRY_WALL_MILLIS";
        event.connectionId = connectionId; event.leaseId = leaseId; event.transactionId = transactionId;
        if (request != null) {
            event.requestId = request.id;
            event.parentRequestId = request.parent;
        }
    }
    void close() {
        ArrayDeque<ExecutionScope> stack = ACTIVE.get();
        if (stack != null && stack.remove(this)) {
            if (stack.isEmpty()) ACTIVE.remove();
            if (request != null) RequestHooks.sqlEnd(request, failed, !reported);
            CaptureLedger.complete();
        }
    }
}
