package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.util.Jsons;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Optional bounded control channel. No SQL, request URL, headers or body are retained.
 * Admission identity precedes queue loss. Flags default off and are reset on reconnect.
 * Trace revision fences nonce/config changes independently of the SQL capture epoch.
 */
public final class TraceBus {
    public static final int REQUEST = 7, TRANSACTION = 8;
    private static final ArrayBlockingQueue<Frame> QUEUE = new ArrayBlockingQueue<Frame>(1024);
    private static final AtomicLong IDS = new AtomicLong();
    private static long generated, dropped, cleared, encodingFailed, transportUncertain, written, revision, hookFailed;
    private static boolean requests, transactions;
    private static String nonce;

    public static synchronized boolean enabled(int command) {
        return !AgentState.isDisabled() && (command == REQUEST ? requests : transactions);
    }
    public static synchronized long revision() { return revision; }
    public static String id(String kind) { return CaptureLedger.RUN_ID + ":" + kind + IDS.incrementAndGet(); }

    public static synchronized void configure(Boolean request, Boolean transaction, String token, boolean tokenPresent) {
        boolean nextRequest = request == null ? requests : request;
        boolean nextTransaction = transaction == null ? transactions : transaction;
        String nextToken = tokenPresent ? token : nonce;
        if (nextRequest != requests || nextTransaction != transactions
                || !java.util.Objects.equals(nextToken, nonce)) {
            revision++;
            cleared += QUEUE.size(); QUEUE.clear();
        }
        requests = nextRequest; transactions = nextTransaction; nonce = nextToken;
    }
    public static synchronized void disconnected() {
        requests = false; transactions = false; nonce = null; revision++;
        cleared += QUEUE.size(); QUEUE.clear();
    }
    public static synchronized long emit(int command, long entryRevision, long entryEpoch, Map<String, Object> facts) {
        long sequence = ++generated;
        if (!enabled(command) || entryRevision != revision || entryEpoch != CaptureLedger.captureEpoch()) {
            cleared++; return sequence;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>(facts);
            body.put("runId", CaptureLedger.RUN_ID); body.put("captureStateToken", nonce);
            body.put("captureEpoch", entryEpoch); body.put("sequence", sequence);
            body.put("threadId", Thread.currentThread().getId()); body.put("ts", System.currentTimeMillis());
            if (!QUEUE.offer(new Frame(command, revision, entryEpoch, Jsons.writeObject(body)))) dropped++;
        } catch (Throwable failure) { encodingFailed++; }
        return sequence;
    }
    public static Map<String, Object> facts(String kind) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>(); fields.put("kind", kind); return fields;
    }
    static synchronized Frame poll() {
        Frame frame;
        while ((frame = QUEUE.poll()) != null) {
            if (frame.revision == revision && frame.epoch == CaptureLedger.captureEpoch() && enabled(frame.command)) return frame;
            cleared++;
        }
        return null;
    }
    public static synchronized void hookFailed() { hookFailed++; }
    static synchronized void encodedFailure() { encodingFailed++; }
    static synchronized void uncertain() { transportUncertain++; }
    static synchronized void written() { written++; }
    public static synchronized String qualityFields() {
        return ",\"controlGenerated\":" + generated + ",\"controlDropped\":" + dropped
                + ",\"controlCleared\":" + cleared + ",\"controlEncodingFailed\":" + encodingFailed
                + ",\"controlTransportUncertain\":" + transportUncertain + ",\"controlWritten\":" + written
                + ",\"controlHookFailed\":" + hookFailed
                + ",\"requestTracing\":" + requests + ",\"transactionTracing\":" + transactions;
    }
    static final class Frame {
        final int command; final long revision, epoch; final String body;
        Frame(int command, long revision, long epoch, String body) {
            this.command = command; this.revision = revision; this.epoch = epoch; this.body = body;
        }
    }
    private TraceBus() { }
}
