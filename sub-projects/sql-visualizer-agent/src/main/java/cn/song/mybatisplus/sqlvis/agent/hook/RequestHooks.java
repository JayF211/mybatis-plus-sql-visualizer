package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.transport.CaptureLedger;
import cn.song.mybatisplus.sqlvis.agent.transport.TraceBus;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** No Servlet symbols in production linkage. No inheritance/thread-pool propagation is implied. */
public final class RequestHooks {
    private static final ThreadLocal<ArrayDeque<Scope>> STACK = new ThreadLocal<ArrayDeque<Scope>>();
    private static final AtomicLong TOKENS = new AtomicLong();

    public static long enter(Object request) {
        try {
            if (!TraceBus.enabled(TraceBus.REQUEST)) return 0;
            ArrayDeque<Scope> stack = STACK.get();
            if (stack == null) { stack = new ArrayDeque<Scope>(); STACK.set(stack); }
            if (stack.size() >= 128) { TraceBus.hookFailed(); return 0; }
            Scope scope = new Scope(TOKENS.incrementAndGet(), currentId(), request);
            stack.push(scope);
            try { emit(scope, "START", null); }
            catch (Throwable failed) {
                stack.pop(); if (stack.isEmpty()) STACK.remove();
                TraceBus.hookFailed(); return 0;
            }
            return scope.token;
        } catch (Throwable ignored) { TraceBus.hookFailed(); return 0; }
    }
    public static void exit(long token, Throwable failure) {
        if (token == 0) return;
        ArrayDeque<Scope> stack = STACK.get();
        if (stack == null) return;
        Scope scope = stack.peek();
        if (scope == null || scope.token != token) return;
        try { emit(scope, "END", failure); }
        catch (Throwable ignored) { TraceBus.hookFailed(); }
        finally {
            stack.pop();
            if (stack.isEmpty()) STACK.remove();
        }
    }
    static Scope current() {
        ArrayDeque<Scope> stack = STACK.get();
        Scope scope = stack == null ? null : stack.peek();
        return scope != null && TraceBus.enabled(TraceBus.REQUEST)
                && scope.revision == TraceBus.revision() && scope.epoch == CaptureLedger.captureEpoch() ? scope : null;
    }
    public static String currentId() { Scope scope = current(); return scope == null ? null : scope.id; }
    static void sqlBegin(long sequence) {
        ArrayDeque<Scope> stack = STACK.get();
        Scope current = current();
        if (stack != null && current != null) for (Scope scope : stack) {
            if (scope.revision != current.revision || scope.epoch != current.epoch) continue;
            scope.generated++; scope.lastSequence = Math.max(scope.lastSequence, sequence);
        }
    }
    static void sqlEnd(Scope owner, boolean error, boolean lost) {
        ArrayDeque<Scope> stack = STACK.get();
        if (stack != null) for (Scope scope : stack) {
            if (scope.revision != owner.revision || scope.epoch != owner.epoch) continue;
            scope.completed++; if (error) scope.failures++; if (lost) scope.lost++;
        }
    }
    private static void emit(Scope scope, String kind, Throwable failure) {
        Map<String, Object> fields = TraceBus.facts(kind);
        fields.put("requestId", scope.id); fields.put("parentRequestId", scope.parent);
        fields.put("startNanos", scope.start); fields.put("durationNanos", "END".equals(kind) ? System.nanoTime() - scope.start : 0);
        fields.put("status", "START".equals(kind) ? "UNKNOWN" : failure == null ? "SUCCESS" : "ERROR");
        fields.put("coverage", "SERVLET_THREAD_ONLY");
        fields.put("errorClass", failure == null ? null : failure.getClass().getName());
        fields.put("asyncStarted", asyncStarted(scope.request));
        fields.put("generatedSql", scope.generated); fields.put("completedSql", scope.completed);
        fields.put("lastSqlSequence", scope.lastSequence); fields.put("observedSqlFailures", scope.failures);
        fields.put("sqlCaptureLost", scope.lost);
        TraceBus.emit(TraceBus.REQUEST, scope.revision, scope.epoch, fields);
    }
    private static Boolean asyncStarted(Object request) {
        if (request == null) return null;
        for (String api : new String[]{"javax.servlet.ServletRequest", "jakarta.servlet.ServletRequest"}) {
            try {
                Class<?> type = Class.forName(api, false, request.getClass().getClassLoader());
                if (type.isInstance(request)) return (Boolean) type.getMethod("isAsyncStarted").invoke(request);
            } catch (Throwable ignored) { }
        }
        return null;
    }
    static final class Scope {
        final long token, start = System.nanoTime(), revision = TraceBus.revision(), epoch = CaptureLedger.captureEpoch();
        final String id = TraceBus.id("request"), parent;
        final Object request;
        long generated, completed, lastSequence, failures, lost;
        Scope(long token, String parent, Object request) { this.token = token; this.parent = parent; this.request = request; }
    }
    private RequestHooks() { }
}
