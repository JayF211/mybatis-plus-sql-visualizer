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
 * HELLO 后 1.5 倍间隔内收到首条心跳、相邻心跳间隔 ≤ 1.5 倍间隔、3 秒观察窗 ≥ 6 条心跳。
 * 计时退回累加形态 → 首条心跳读数退化到 ~8 倍间隔，必红。
 *
 * 注：[EventBus.start] 是 JVM 内单例（started 标志），故本类只跑一例；系统属性用完即清，
 * 同 JVM 里的其它用例不受污染。
 *
 * <p><b>回归鉴别力（T-AgentResiduals R3 放宽容差时复核，勿据「容差放大了」就以为守卫松了）</b>：
 * 本用例真正钉住的是**节奏形态**，不是某一毫秒数。计时退回累加形态（`idleMs += 200`，见 SenderLoop
 * KDoc）时读数退化，且 R3 落地时**实测过每条判据的鉴别力**：累加形态下相邻间隔的中位数 ≈626ms
 * （> 1.5× 典型上限 450ms，**必红**）、单次间隔在 600ms 上下浮动（2× 单次上限时红时绿，故单次上限
 * 不能独任形态判据）、3000ms 窗口内心跳条数仍在 6 条以上（**条数判据对这条退化不敏感**，别拿它当
 * 主判据）。故判据分工：`TYPICAL_TOLERANCE_MS`（中位数，1.5×）守形态，`TOLERANCE_MS`（单次，2×）
 * 只吸收并行构建的百毫秒级调度抖动（R3 立项读数：本机门禁实测命中过一次 472ms > 450ms 而同一份
 * 代码复跑 5645 例全绿——属抖动而非退化）。
 */
public class HeartbeatRhythmTest {

    private static final long INTERVAL_MS = 300L;
    /**
     * 容差：**2 倍**设定间隔（机器抖动/调度延迟余量）。
     *
     * T-AgentResiduals R3（2026-09-22）由 1.5 倍放宽到 2 倍，依据是**并行构建下的调度抖动实测可达
     * 百毫秒级**：本机门禁实测命中过一次 472ms > 450ms（1.5× 容差）而同一份代码复跑 5645 例全绿
     * ——即那次是调度抖动而非节奏退化（属 T-PerfSweep6 发现 14 的登记处置）。
     *
     * 放宽的**边界**（P-3 已验，见类注释「回归鉴别力」段）：被本用例钉住的两种退化形态读数分别是
     * 「首条心跳 ≈8 倍间隔（2400ms）」与「观察窗内心跳条数不足」——两者都远在 2×（600ms）之外，
     * 故放宽到 2× 不改变「退化必红」这一性质，只把抖动噪声移出判定面。
     */
    private static final long TOLERANCE_MS = INTERVAL_MS * 2;
    /**
     * **典型节奏**（观察窗内相邻间隔的**中位数**）上限：1.5 倍设定间隔。
     *
     * 这是本用例的**形态判据**（R3 落地时实测得出，勿删）：容差放宽到 2× 后，单次间隔上限已不足以
     * 分辨「累加计时形态」（每 2 轮发一条，间隔 ≈2 倍设定值）与「调度抖动」（个别次被推后）——
     * 实测累加形态单次间隔在 600ms 上下浮动，会**时红时绿**。中位数则稳定：累加形态每条都超标
     * （中位数 ≈626ms 必红），正确形态中位数 ≈300ms 不受个别抖动影响。故两条判据分工——
     * [TOLERANCE_MS] 吸收抖动、本值守住形态。
     */
    private static final long TYPICAL_TOLERANCE_MS = INTERVAL_MS * 3 / 2;
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
            final java.util.List<Long> gaps = new java.util.ArrayList<Long>();
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
                final long gap = f.atMs - prevAt;
                gaps.add(gap);
                maxGap = Math.max(maxGap, gap);
                prevAt = f.atMs;
                heartbeats++;
            }
            // 典型节奏（中位数）——**形态判据**：累加计时形态下**每一条**间隔都落在 ~2 倍设定值上
            // （中位数同样超标），而并行构建的调度抖动只抬高**个别**间隔（中位数不动）。
            // 故 2× 的单次上限只用来吸收抖动，1.5× 的中位数用来守住节奏形态（R3 落地时实测：
            // 累加形态中位数 ≈626ms 必红，正确形态中位数 ≈300ms）。
            final long medianGap = median(gaps);
            assertTrue("相邻心跳的典型间隔（中位数）应 ≤ " + TYPICAL_TOLERANCE_MS + "ms，实测 " + medianGap
                    + "ms（形态判据：累加计时形态下每条间隔都超标、中位数同样超标；单次调度抖动不影响中位数）",
                    medianGap <= TYPICAL_TOLERANCE_MS);
            assertTrue("相邻心跳最大间隔应 ≤ " + TOLERANCE_MS + "ms，实测 " + maxGap + "ms（单次上限只吸收调度抖动）",
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

    /** 相邻间隔的中位数（奇数取中间项、偶数取偏小的一侧；空集合返回 0）。 */
    private static long median(java.util.List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        final java.util.List<Long> sorted = new java.util.ArrayList<Long>(values);
        java.util.Collections.sort(sorted);
        return sorted.get((sorted.size() - 1) / 2);
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
