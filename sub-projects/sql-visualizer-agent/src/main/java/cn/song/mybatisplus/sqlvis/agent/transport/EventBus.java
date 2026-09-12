package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.util.Jsons;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件总线（§4.3 线程模型）：采集线程只入队（有界 2048，满则丢弃并计数——丢事件不丢线程），
 * 单发送线程消费（连接/重连/心跳/入站 CONFIG）。IO 与业务分离。
 */
public final class EventBus {

    public static final int CMD_EVENT = 1;
    public static final int CMD_HEARTBEAT = 2;
    public static final int CMD_CONFIG = 3;
    public static final int CMD_HELLO = 4;

    private static final int QUEUE_CAPACITY = 2048;
    private static final BlockingQueue<SqlEvent> QUEUE = new ArrayBlockingQueue<SqlEvent>(QUEUE_CAPACITY);
    private static final AtomicLong DROPPED = new AtomicLong();

    private static volatile boolean started = false;
    private static volatile int port = 0;

    public static synchronized void start(int serverPort) {
        if (started) {
            return;
        }
        port = serverPort;
        started = true;
        final Thread sender = new Thread(new SenderLoop(serverPort), "sqlvis-agent-sender");
        sender.setDaemon(true);
        sender.start();
    }

    /** 采集线程入口：非阻塞入队，满则丢事件（计数）不阻塞业务线程。 */
    public static void offer(SqlEvent event) {
        if (event == null || !QUEUE.offer(event)) {
            DROPPED.incrementAndGet();
        }
    }

    public static long droppedCount() {
        return DROPPED.get();
    }

    static boolean pollEvent(SqlEvent[] out, long timeoutMs) throws InterruptedException {
        final SqlEvent e = QUEUE.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (e == null) {
            return false;
        }
        out[0] = e;
        return true;
    }

    static String encodeEvent(SqlEvent event) {
        return Jsons.writeEvent(event);
    }

    private EventBus() {
    }
}
