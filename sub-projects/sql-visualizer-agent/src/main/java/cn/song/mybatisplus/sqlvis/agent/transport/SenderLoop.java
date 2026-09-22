package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;

/**
 * 发送线程主循环（§4.3）：loopback TCP 长度前缀帧（4 字节 len + 1 字节 cmd + JSON body）。
 * 连接后先发 HELLO（pid 标识）并**补发一条 HEARTBEAT**（IDE 在线态自连接起即新鲜）；
 * 事件即时 flush；出站静默达心跳间隔（默认 10s）发 HEARTBEAT（IDE 在线态判定）；
 * 入站 CONFIG（禁用/脱敏开关）由同连接读取线程下发 AgentState。断线退避重连（1s→5s 封顶）。
 *
 * T-AgentHeartbeat T-01：心跳节奏改按**真实流逝时间**计。此前用「假定每轮 200ms」累加计时
 * （`idleMs += 200`），而每轮真实耗时≈1.2s（入站 CONFIG 读超时 1s + 轮询睡眠 200ms），
 * 于是「静默 10s 发心跳」实际要 50 轮 ≈ 60s——首条心跳永远赶不上 IDE 侧 30s 在线窗口，
 * 增强捕获会话建立后 30 秒必被摘除（实测：HELLO +17ms，首条 HEARTBEAT +60633ms）。
 * 现以 [lastOutboundMs]（最近一次出站帧的时刻）为准，每轮比较墙钟。
 *
 * T-PerfSweep6 T-PS6-05：退避复位点由「连上即复位」后移到「连接存活超过 [BACKOFF_RESET_AFTER_MS]
 * 才复位」，并对连续「连上即被关」计满 [MAX_FAST_DROPS] 次后放弃该端口（性能报告发现 5 / 发现 9）。
 */
final class SenderLoop implements Runnable {

    /**
     * 默认心跳间隔（§4.3 静默 10s）。
     * IDE 侧 `AgentProtocol.ONLINE_WINDOW_MS` 由同值推导（3 倍余量）——两侧常量由
     * `AgentProtocolSyncTest` 源码扫描锁定（`window >= 3 * interval`），任一侧漂移即转红。
     */
    static final long DEFAULT_HEARTBEAT_MS = 10_000L;

    /** 心跳间隔注入点（毫秒；缺省/非法/非正数回落 [DEFAULT_HEARTBEAT_MS]，生产行为不变）。 */
    static final String HEARTBEAT_MS_PROP = "sqlvis.agent.heartbeatMs";

    /** 空转轮询节奏（决定事件轮询响应性）：每轮最多睡这么久，且不得跨过下一次心跳时刻。 */
    private static final long POLL_IDLE_MS = 200L;

    /** 入站 CONFIG 读超时上限（§4.3 轮询式短超时；默认心跳间隔下即 1s，行为与修复前一致）。 */
    private static final int CONFIG_READ_TIMEOUT_MAX_MS = 1000;

    /** 入站读超时下限（防注入极小间隔时退化为忙轮询）。 */
    private static final int CONFIG_READ_TIMEOUT_MIN_MS = 50;

    /** 初始退避（毫秒；与修复前逐值一致）。 */
    private static final long BACKOFF_INITIAL_MS = 1_000L;

    /** 退避上限（毫秒；与修复前逐值一致）。 */
    private static final long BACKOFF_MAX_MS = 5_000L;

    /**
     * 退避复位阈值（毫秒，T-PerfSweep6 T-PS6-05）。
     *
     * **复位点后移**：此前 `backoffMs = 1000` 打在「socket 连上」处——只要 TCP 握手成功就算成功，
     * 于是「连上即被关」（attach 端口 accept 后即释放，或端口被无关进程复用）这种**必然失败**的
     * 连接每轮都把退避打回 1s，agent 以固定短节奏对着一个不存在的对端写帧（性能报告发现 9）。
     * 现在改为：连接**存活**超过本阈值才算「一次正常掉线」，才复位退避与快速断开计数。
     * 取值 2s 远小于任何真实会话寿命（IDE 侧在线窗口 30s），故正常路径的复位时机体感不变
     * （正常连接掉线时阈值必已满足，退避照旧回到 1s）。
     */
    private static final long BACKOFF_RESET_AFTER_MS = 2_000L;

    /**
     * 连续「连上即被关」次数上限（T-PerfSweep6 T-PS6-05 / 性能报告发现 9）。
     *
     * 达到即**放弃该端口**并退出重连循环：连续这么多次「握手成功、随即断开」已不是「IDE 侧还没起来」
     * 而是「这个端口上坐的不是本插件的监听者」（端口复用）——继续按 1s→5s 退避写帧，轻则空转，
     * 重则向无关进程投递帧。仅统计**已建连后快速断开**者；`Connection refused` 一类不算（那是
     * 「IDE 侧端口尚未就绪」的正常早期形态，须继续重试）。
     *
     * 放弃重试是安全的：通道一旦关闭（运行结束 / 上限 / 端口被复用），同端口不会再出现本插件的
     * 监听者，重试本就无意义（新一次 Run 会前置协商**新**端口并重新注入 agent）。
     */
    private static final int MAX_FAST_DROPS = 10;

    private final int serverPort;
    private final long backoffInitialMs;
    private final long backoffMaxMs;
    private final long backoffResetAfterMs;
    private final int maxFastDrops;

    SenderLoop(int serverPort) {
        this(serverPort, BACKOFF_INITIAL_MS, BACKOFF_MAX_MS, BACKOFF_RESET_AFTER_MS, MAX_FAST_DROPS);
    }

    /**
     * 退避参数注入构造器（同包测试缝）：生产路径一律走单参构造器 + 上列常量。
     * 退避节奏是秒级，靠系统属性压时间会给生产侧多出一个可配置面，故改为构造器注入。
     */
    SenderLoop(int serverPort, long backoffInitialMs, long backoffMaxMs, long backoffResetAfterMs, int maxFastDrops) {
        this.serverPort = serverPort;
        this.backoffInitialMs = backoffInitialMs;
        this.backoffMaxMs = backoffMaxMs;
        this.backoffResetAfterMs = backoffResetAfterMs;
        this.maxFastDrops = maxFastDrops;
    }

    /** 心跳间隔：系统属性 [HEARTBEAT_MS_PROP] 优先，解析失败/非正数回落 [DEFAULT_HEARTBEAT_MS]。 */
    static long resolveHeartbeatIntervalMs() {
        try {
            final String raw = System.getProperty(HEARTBEAT_MS_PROP);
            if (raw != null) {
                final long v = Long.parseLong(raw.trim());
                if (v > 0L) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // 非法值回落默认（不因测试注入失误影响生产节奏）
        }
        return DEFAULT_HEARTBEAT_MS;
    }

    /**
     * 入站轮询读超时：取心跳间隔的 1/3（上下限 [CONFIG_READ_TIMEOUT_MIN_MS]~[CONFIG_READ_TIMEOUT_MAX_MS]）。
     * 读阻塞会把心跳判定推迟一整轮，故读超时必须显著小于心跳间隔；默认间隔下 10000/3 被上限钳到
     * 1000ms——与修复前逐值一致，生产行为不变。
     */
    static int configReadTimeoutMs(long heartbeatIntervalMs) {
        long t = heartbeatIntervalMs / 3L;
        if (t > CONFIG_READ_TIMEOUT_MAX_MS) {
            t = CONFIG_READ_TIMEOUT_MAX_MS;
        }
        if (t < CONFIG_READ_TIMEOUT_MIN_MS) {
            t = CONFIG_READ_TIMEOUT_MIN_MS;
        }
        return (int) t;
    }

    @Override
    public void run() {
        long backoffMs = backoffInitialMs;
        int fastDrops = 0;
        while (true) {
            Socket socket = null;
            boolean connected = false;
            final long attemptStartMs = System.currentTimeMillis();
            try {
                socket = new Socket("127.0.0.1", serverPort);
                connected = true;
                final long heartbeatIntervalMs = resolveHeartbeatIntervalMs();
                socket.setSoTimeout(configReadTimeoutMs(heartbeatIntervalMs));
                socket.setTcpNoDelay(true);
                // T-PerfSweep6 T-PS6-05：此处**不再**复位退避——「连上」不等于「连对」，
                // 复位点后移到 catch 块的「存活超过 [backoffResetAfterMs]」分支。

                final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                final DataInputStream in = new DataInputStream(socket.getInputStream());
                writeFrame(out, EventBus.CMD_HELLO, "{\"pid\":" + pid() + "}");
                // 连接建立即补发一条心跳：即便首轮迭代被 CONFIG 读超时拖住，IDE 侧 lastSeen 也从头新鲜
                writeFrame(out, EventBus.CMD_HEARTBEAT, "{}");
                long lastOutboundMs = System.currentTimeMillis();

                final cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[] slot =
                        new cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[1];
                while (true) {
                    // 1) 采集事件尽出（每次最多 64 条防饿死心跳）；单事件编码/写失败只丢该事件
                    //    （IO 异常外抛走重连；编码异常跳过不断连接）
                    int burst = 0;
                    while (burst < 64 && EventBus.pollEvent(slot, 0)) {
                        try {
                            writeFrame(out, EventBus.CMD_EVENT, EventBus.encodeEvent(slot[0]));
                            burst++;
                            lastOutboundMs = System.currentTimeMillis();
                        } catch (java.io.IOException io) {
                            throw io;
                        } catch (Throwable t) {
                            // 编码失败：丢弃该事件继续（EventBus.droppedCount 可观测）
                        }
                    }
                    // 2) 心跳：按真实流逝时间判（每轮都执行，不被 burst 分支包住——事件突发期间也按节奏发）
                    if (System.currentTimeMillis() - lastOutboundMs >= heartbeatIntervalMs) {
                        writeFrame(out, EventBus.CMD_HEARTBEAT, "{}");
                        lastOutboundMs = System.currentTimeMillis();
                    }
                    // 3) 入站 CONFIG（非阻塞轮询：超时=无数据）
                    try {
                        final int cmd = in.readUnsignedByte();
                        handleInbound(cmd, readPayload(in, cmd));
                    } catch (java.net.SocketTimeoutException ignore) {
                        // 无入站数据
                    }
                    // 4) 空转轮询节奏（有事件突发时不睡，保持事件吞吐）；
                    //    睡眠不得跨过下一次心跳时刻，否则读阻塞会把心跳推后一整轮
                    if (burst == 0) {
                        final long remainMs = heartbeatIntervalMs
                                - (System.currentTimeMillis() - lastOutboundMs);
                        final long sleepMs = Math.max(0L, Math.min(POLL_IDLE_MS, remainMs));
                        if (sleepMs > 0L) {
                            Thread.sleep(sleepMs);
                        }
                    }
                }
            } catch (Throwable t) {
                closeQuietly(socket);
                // T-PerfSweep6 T-PS6-05：退避复位点后移 + 「连上即被关」连续计数。
                // 判据只看「是否建连成功」与「存活时长」，与异常类型无关（断开形态多样：
                // EOFException / SocketException / 读超时后写失败…，逐类型分支必漏）。
                final long aliveMs = System.currentTimeMillis() - attemptStartMs;
                if (connected && aliveMs < backoffResetAfterMs) {
                    // 连上即被关：**不复位**退避（下一轮等待翻倍），并累计快速断开计数
                    fastDrops++;
                    if (fastDrops >= maxFastDrops) {
                        System.err.println("[SqlVisAgent] SenderLoop: 端口 " + serverPort
                                + " 连续 " + fastDrops + " 次「连上即被关」——放弃该端口重试"
                                + "（该端口上的监听者已不是本插件，继续写帧只会空转或投错对端）");
                        return;
                    }
                } else if (connected) {
                    // 连接存活过阈值 = 一次正常掉线：退避与快速断开计数一并复位
                    backoffMs = backoffInitialMs;
                    fastDrops = 0;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, backoffMaxMs);
            } finally {
                closeQuietly(socket);
            }
        }
    }

    private void handleInbound(int cmd, String payload) {
        if (cmd != EventBus.CMD_CONFIG || payload == null) {
            return;
        }
        // 极简解析（仅两个布尔键）：{"disabled":true,"desensitize":false}
        if (payload.contains("\"disabled\"")) {
            AgentState.setDisabled(payload.contains("\"disabled\":true")
                    || payload.contains("\"disabled\": true"));
        }
        if (payload.contains("\"desensitize\"")) {
            AgentState.setDesensitize(payload.contains("\"desensitize\":false")
                    || payload.contains("\"desensitize\": false") ? false : true);
        }
        // T-SourceJumpFunnel P1-3：用户登记的帧过滤前缀（"skipPrefixes":["a.","b."]；缺省键=清空）。
        // 极简提取：定位键名后逐项扫描双引号对，支持 \" 转义（IDE 侧同规则转义）。
        if (payload.contains("\"skipPrefixes\"")) {
            StackLocator.setExtraSkipPrefixes(extractSkipPrefixes(payload));
        } else {
            StackLocator.setExtraSkipPrefixes(new String[0]);
        }
    }

    /** 从 CONFIG payload 提取 "skipPrefixes":[...] 项（无该键/畸形返回空组）。 */
    static String[] extractSkipPrefixes(String payload) {
        final String key = "\"skipPrefixes\":";
        final int k = payload.indexOf(key);
        if (k < 0) {
            return new String[0];
        }
        int i = k + key.length();
        while (i < payload.length() && payload.charAt(i) != '[') {
            i++;
        }
        if (i >= payload.length()) {
            return new String[0];
        }
        i++; // past '['
        final java.util.List<String> out = new java.util.ArrayList<String>(4);
        final StringBuilder cur = new StringBuilder(32);
        boolean inStr = false;
        boolean escaped = false;
        for (; i < payload.length(); i++) {
            final char c = payload.charAt(i);
            if (inStr) {
                if (escaped) {
                    cur.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inStr = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == ']') {
                break;
            }
        }
        return out.toArray(new String[0]);
    }

    private String readPayload(DataInputStream in, int cmd) throws IOException {
        final int len = in.readInt();
        if (len <= 0 || len > 1 << 20) {
            throw new EOFException("bad frame len " + len);
        }
        final byte[] body = new byte[len];
        in.readFully(body);
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    static void writeFrame(DataOutputStream out, int cmd, String payload) throws IOException {
        final byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(body.length);
        out.writeByte(cmd);
        out.write(body);
        out.flush();
    }

    private static long pid() {
        try {
            final String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            final int at = name.indexOf('@');
            return at < 0 ? 0 : Long.parseLong(name.substring(0, at));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignore) {
            }
        }
    }
}
