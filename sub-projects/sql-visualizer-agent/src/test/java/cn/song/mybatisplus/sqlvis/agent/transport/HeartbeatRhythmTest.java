package cn.song.mybatisplus.sqlvis.agent.transport;

import org.junit.Test;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T-AgentHeartbeat T-02：心跳节奏实测（真起 agent 侧 [EventBus] + 探针 ServerSocket）。
 *
 * 缺陷现场：`SenderLoop` 用「假定每轮 200ms」累加计时（`idleMs += 200`），而每轮真实耗时≈1.2s
 * （入站 CONFIG 读超时 1s + 轮询睡眠 200ms），于是「静默 10s 发心跳」实际要 50 轮 ≈ 60s——
 * 首条心跳永远赶不上 IDE 侧 30s 在线窗口（实测：HELLO +17ms，首条 HEARTBEAT +60633ms）。
 * 本用例把间隔经系统属性压到 300ms 真跑一遍，断言三件事：
 * HELLO 后 2 倍间隔内收到首条心跳、相邻心跳间隔 ≤ 2 倍间隔、3 秒观察窗 ≥ 6 条心跳。
 * 计时退回累加形态 → 首条心跳读数退化到 ~8 倍间隔，必红。
 *
 * T-PerfSweep6 T-PS6-09（性能报告发现 14a）：容差由 1.5 倍放宽到 **2 倍**。1.5 倍在并行构建
 * （多 worker 抢 CPU / GC 抖动）下偶发红，而它要拦的退化形态（累加计时）读数在 8 倍量级——
 * 放宽到 2 倍仍留有 4 倍判别余量，红/绿判据不因放宽而失效。
 *
 * 注：[EventBus.start] 是 JVM 内单例（started 标志），故本类只跑一例；系统属性用完即清，
 * 同 JVM 里的其它用例不受污染。
 */
public class HeartbeatRhythmTest {

    private static final long INTERVAL_MS = 300L;
    /** 容差：2 倍设定间隔（机器抖动/调度延迟余量；退化形态读数在 8 倍量级，判别余量仍 4 倍）。 */
    private static final long TOLERANCE_MS = INTERVAL_MS * 2;
    private static final long WATCH_MS = 3000L;
    private static final int MIN_HEARTBEATS = 6;
    /** 单帧等待上限（远大于间隔；并行构建下线程启动/调度抖动可能达百毫秒级）。 */
    private static final int FRAME_WAIT_MS = 3000;

    @Test
    public void 心跳节奏_首条即时且间隔不超设定值() throws Exception {
        final String prevProp = System.getProperty(SenderLoop.HEARTBEAT_MS_PROP);
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket socket = null;
        try {
            System.setProperty(SenderLoop.HEARTBEAT_MS_PROP, String.valueOf(INTERVAL_MS));
            assertEquals("系统属性应被解析为心跳间隔",
                    INTERVAL_MS, SenderLoop.resolveHeartbeatIntervalMs());

            EventBus.start(server.getLocalPort());
            server.setSoTimeout(5000);
            socket = server.accept();
            // 单帧等待上限取得远大于间隔：本机并行会话下线程启动/调度偶有百毫秒级抖动，
            // 读超时只作「帧彻底不来」的兜底，节奏判定一律走帧到达时刻的实测差值
            socket.setSoTimeout(FRAME_WAIT_MS);
            final DataInputStream in = new DataInputStream(socket.getInputStream());

            final Frame hello = readFrame(in);
            assertEquals("首帧应为 HELLO", EventBus.CMD_HELLO, hello.cmd);

            final Frame first;
            try {
                first = readFrame(in);
            } catch (SocketTimeoutException e) {
                fail("HELLO 后 " + socket.getSoTimeout() + "ms 内未收到首条心跳（期望 ≤" + TOLERANCE_MS
                        + "ms；「假定每轮 200ms」累加计时形态读数 ≈" + (INTERVAL_MS * 8) + "ms，"
                        + "生产默认间隔下 ≈60s——首条心跳赶不上 IDE 侧 30s 在线窗口）");
                return;
            }
            assertEquals("HELLO 后应紧接着一条 HEARTBEAT（连接建立即补发）",
                    EventBus.CMD_HEARTBEAT, first.cmd);
            final long firstGap = first.atMs - hello.atMs;
            assertTrue("HELLO 后首条心跳应在 " + TOLERANCE_MS + "ms 内，实测 " + firstGap
                    + "ms（「假定每轮 200ms」累加计时形态实测 2410ms；生产默认间隔下实测 +60633ms）",
                    firstGap <= TOLERANCE_MS);

            // 观察窗：心跳节奏
            final long t0 = System.currentTimeMillis();
            long prevAt = first.atMs;
            long maxGap = 0L;
            int heartbeats = 1;
            while (System.currentTimeMillis() - t0 < WATCH_MS) {
                final Frame f;
                try {
                    f = readFrame(in);
                } catch (SocketTimeoutException e) {
                    fail("观察窗内 " + socket.getSoTimeout() + "ms 无任何帧（期望每 ~" + INTERVAL_MS
                            + "ms 一条心跳；窗口内已收 " + heartbeats + " 条）");
                    return;
                }
                if (f.cmd != EventBus.CMD_HEARTBEAT) {
                    continue; // 事件帧不参与节奏判定
                }
                maxGap = Math.max(maxGap, f.atMs - prevAt);
                prevAt = f.atMs;
                heartbeats++;
            }
            assertTrue("相邻心跳最大间隔应 ≤ " + TOLERANCE_MS + "ms，实测 " + maxGap + "ms",
                    maxGap <= TOLERANCE_MS);
            assertTrue("观察窗 " + WATCH_MS + "ms 内应至少 " + MIN_HEARTBEATS + " 条心跳，实测 " + heartbeats,
                    heartbeats >= MIN_HEARTBEATS);
        } finally {
            if (prevProp == null) {
                System.clearProperty(SenderLoop.HEARTBEAT_MS_PROP);
            } else {
                System.setProperty(SenderLoop.HEARTBEAT_MS_PROP, prevProp);
            }
            closeQuietly(socket);
            closeQuietly(server);
        }
    }

    /** 一帧：命令号 + 读全时刻（用于节奏测量）。 */
    private static final class Frame {
        final int cmd;
        final long atMs;

        Frame(int cmd, long atMs) {
            this.cmd = cmd;
            this.atMs = atMs;
        }
    }

    private static Frame readFrame(DataInputStream in) throws IOException {
        final int len = in.readInt();
        final int cmd = in.readUnsignedByte();
        final byte[] body = new byte[len];
        in.readFully(body);
        return new Frame(cmd, System.currentTimeMillis());
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
            }
        }
    }
}
