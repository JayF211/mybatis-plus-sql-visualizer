package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real bounded SQL queue and loopback socket; default heartbeat, no artificial short timeout. */
public class SenderLoopThroughputTest {
    private boolean disabled;
    private String heartbeat;
    private ServerSocket server;
    private Socket peer;
    private Thread sender;
    private DataInputStream in;
    private DataOutputStream out;
    private final CountDownLatch idleRead = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicBoolean armIdle = new java.util.concurrent.atomic.AtomicBoolean();
    @Before public void before() throws Exception {
        disabled = AgentState.isDisabled(); heartbeat = System.getProperty(SenderLoop.HEARTBEAT_MS_PROP);
        System.clearProperty(SenderLoop.HEARTBEAT_MS_PROP); AgentState.setDisabled(false); EventBus.clearQueued();
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); server.setSoTimeout(5000);
        sender = new Thread(new SenderLoop(server.getLocalPort(), 1, 1, 2000, 1, (host, port) -> new Socket(host, port) {
            @Override public InputStream getInputStream() throws IOException {
                return new FilterInputStream(super.getInputStream()) {
                    @Override public int read() throws IOException {
                        if (armIdle.compareAndSet(true, false)) idleRead.countDown();
                        return super.read();
                    }
                };
            }
        })); sender.setDaemon(true); sender.start();
        peer = server.accept(); peer.setSoTimeout(6000);
        in = new DataInputStream(peer.getInputStream()); out = new DataOutputStream(peer.getOutputStream());
        assertEquals(4, read()); assertEquals(2, read());
        SenderLoop.writeFrame(out, 3, "{\"disabled\":false,\"captureState\":true,\"captureStateToken\":\"aa11\"}");
        assertEquals(5, read()); assertEquals(6, read());
    }
    @After public void after() throws Exception {
        sender.interrupt(); peer.close(); server.close(); sender.join(3000);
        EventBus.clearQueued(); TraceBus.disconnected(); AgentState.setDisabled(disabled);
        if (heartbeat == null) System.clearProperty(SenderLoop.HEARTBEAT_MS_PROP); else System.setProperty(SenderLoop.HEARTBEAT_MS_PROP, heartbeat);
    }
    private static void enqueue(int count) {
        for (int i = 0; i < count; i++) { SqlEvent e = new SqlEvent(); e.sql = "SELECT " + i; EventBus.offer(e); }
    }
    @Test public void queueArrivalWakesSenderWhileInboundReadIsIdle() throws Exception {
        armIdle.set(true);
        assertTrue("reader must reach a real idle read", idleRead.await(3, TimeUnit.SECONDS));
        long start = System.nanoTime(); enqueue(1);
        while (read() != 1) { }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println("IDLE_TO_SQL firstFrameMs=" + millis + " inboundTimeoutMs=" + SenderLoop.configReadTimeoutMs(10000));
        assertTrue("SQL arrival must wake send independently of idle CONFIG read: " + millis, millis < 400);
    }
    @Test public void fourBurstsDrainWithoutOneReadTimeoutPerBurst() throws Exception {
        long drops = EventBus.droppedCount(); enqueue(256);
        long start = System.nanoTime(); int received = 0;
        while (received < 256) if (read() == 1) received++;
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println("SENDER_DRAIN count=" + received + " wallMs=" + millis + " heartbeatMs=" + SenderLoop.resolveHeartbeatIntervalMs());
        assertEquals(drops, EventBus.droppedCount());
        // Allow one pre-existing idle read (1000ms) + idle pause (200ms) and scheduling headroom.
        // Old sender adds 3 more 1000ms timeouts between the four bursts and fails this bound.
        assertTrue("256 frames took " + millis + "ms; per-burst inbound read must not throttle a busy queue", millis < 2500);
    }
    @Test public void controlOnlyBacklogAlsoDrainsWithoutIdleSleepPerBurst() throws Exception {
        SenderLoop.writeFrame(out, 3, "{\"disabled\":false,\"requestTracing\":true}");
        assertEquals(5, read()); assertEquals(6, read());
        for (int i = 0; i < 256; i++) TraceBus.emit(7, TraceBus.revision(), CaptureLedger.captureEpoch(), TraceBus.facts("START"));
        long start = System.nanoTime(); int received = 0;
        while (received < 256) if (read() == 7) received++;
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println("CONTROL_DRAIN count=" + received + " wallMs=" + millis);
        assertTrue("control backlog must count as busy: " + millis, millis < 2500);
    }
    @Test public void stopConfigIsAcknowledgedDuringBacklogAndNoSqlFollowsAck() throws Exception {
        enqueue(2048);
        assertEquals(1, read());
        long start = System.nanoTime();
        SenderLoop.writeFrame(out, 3, "{\"disabled\":true,\"captureState\":true,\"captureStateToken\":\"bb22\"}");
        int untilAck = 0, cmd;
        while ((cmd = read()) != 5) if (cmd == 1) untilAck++;
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("stop ACK must not be starved, elapsed=" + millis, millis < 2500);
        assertEquals(6, read()); assertTrue(AgentState.isDisabled()); assertEquals(0, EventBus.queuedCount());
        // Explicit another control request proves liveness without sleeping for a heartbeat.
        SenderLoop.writeFrame(out, 3, "{\"disabled\":true}");
        assertEquals(5, read()); assertEquals(6, read());
        System.out.println("SENDER_STOP queued=2048 preAckSql=" + untilAck + " ackMs=" + millis);
    }
    @Test public void partialConfigHeaderNeverBecomesAnIdleTimeout() throws Exception {
        enqueue(128); assertEquals(1, read());
        out.writeByte(0); out.flush(); // first byte consumed, remaining frame withheld
        boolean eof = false;
        try { while (true) read(); } catch (EOFException | SocketException expected) { eof = true; }
        assertTrue("partial frame must close connection, not resume at wrong byte", eof);
    }
    private int read() throws IOException {
        int length = in.readInt(), command = in.readUnsignedByte();
        assertTrue(length > 0 && length <= SenderLoop.MAX_FRAME_LEN);
        byte[] body = new byte[length]; in.readFully(body); return command;
    }
}
