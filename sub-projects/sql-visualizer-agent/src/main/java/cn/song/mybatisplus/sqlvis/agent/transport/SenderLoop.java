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

    private final int serverPort;

    SenderLoop(int serverPort) {
        this.serverPort = serverPort;
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
        long backoffMs = 1000;
        while (true) {
            Socket socket = null;
            try {
                socket = new Socket("127.0.0.1", serverPort);
                final long heartbeatIntervalMs = resolveHeartbeatIntervalMs();
                socket.setSoTimeout(configReadTimeoutMs(heartbeatIntervalMs));
                socket.setTcpNoDelay(true);
                backoffMs = 1000; // 连接成功：退避复位

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
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, 5000);
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
