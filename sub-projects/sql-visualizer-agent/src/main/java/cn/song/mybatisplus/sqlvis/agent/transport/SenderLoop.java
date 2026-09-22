package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
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
 * T-AgentConfigChannel T-01：入站读序对齐为 `[4 字节 len][1 字节 cmd][body]`（与出站、与 IDE 侧
 * 读写完全同形）。此前入站先读 1 字节当命令、再读 4 字节当长度——IDE 下发的 CONFIG 被解析成
 * cmd=0 丢弃、随后按错位长度（实测 9475）阻塞读超时被静默吞掉，控制通道等于完全不通，
 * AgentState.disabled 永远停在 fail-open 初值。畸形长度现在**抛异常走重连**并 WARN 记长度与首字节。
 * T-02：CONFIG 应用成功回一帧 {@link EventBus#CMD_CONFIG_ACK}（「生效」不再靠推断）。
 * T-07：会话建立后 X 毫秒内从未收到任何 CONFIG → 主动停采 + 一次性 WARN（每连接一次，不新开线程）。
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

    /** 入站帧 body 长度上限（1MB）：超限即视为流错位/协议漂移，抛异常走重连而非阻塞读。 */
    static final int MAX_FRAME_LEN = 1 << 20;

    /** CONFIG 看门狗窗口注入点（毫秒；缺省/非法/非正数回落 [DEFAULT_CONFIG_WATCHDOG_MS]）。 */
    static final String CONFIG_WATCHDOG_MS_PROP = "sqlvis.agent.configWatchdogMs";

    /**
     * CONFIG 看门狗默认窗口（T-AgentConfigChannel T-07，设计定案 D2）。
     * 语义：会话建立后这么久**从未收到任何 CONFIG** → 主动停采。取值权衡：
     * 保留「启动即捕获」的启动期收益（IDE 侧 CONFIG 在下发时机上从不是「秒级必达」，
     * 会话校正/心跳都可能晚几秒），同时把「控制通道再出任何问题」的损害从
     * **无界**（本轮现场：整场会话静默采集）压到 X 秒。
     */
    static final long DEFAULT_CONFIG_WATCHDOG_MS = 15_000L;

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
     * CONFIG 看门狗窗口：系统属性 [CONFIG_WATCHDOG_MS_PROP] 优先，解析失败/非正数回落
     * [DEFAULT_CONFIG_WATCHDOG_MS]（注入失误不得改变生产行为）。
     */
    static long resolveConfigWatchdogMs() {
        try {
            final String raw = System.getProperty(CONFIG_WATCHDOG_MS_PROP);
            if (raw != null) {
                final long v = Long.parseLong(raw.trim());
                if (v > 0L) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // 非法值回落默认
        }
        return DEFAULT_CONFIG_WATCHDOG_MS;
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
                final long watchdogMs = resolveConfigWatchdogMs();
                socket.setSoTimeout(configReadTimeoutMs(heartbeatIntervalMs));
                socket.setTcpNoDelay(true);
                backoffMs = 1000; // 连接成功：退避复位

                final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                final DataInputStream in = new DataInputStream(socket.getInputStream());
                writeFrame(out, EventBus.CMD_HELLO, "{\"pid\":" + pid() + "}");
                // 连接建立即补发一条心跳：即便首轮迭代被 CONFIG 读超时拖住，IDE 侧 lastSeen 也从头新鲜
                writeFrame(out, EventBus.CMD_HEARTBEAT, "{}");
                long lastOutboundMs = System.currentTimeMillis();
                // T-07 看门狗：本连接是否收到过任何 CONFIG / 本连接是否已触发（每条连接只触发一次）
                final long connectedAtMs = lastOutboundMs;
                boolean configSeen = false;
                boolean watchdogFired = false;

                final cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[] slot =
                        new cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[1];
                while (true) {
                    // 1) 采集事件尽出（每次最多 64 条防饿死心跳）；单事件编码/写失败只丢该事件
                    //    （IO 异常外抛走重连；编码异常跳过不断连接）
                    //    T-01b：停采态短路——即便队列里还有残留（下降沿清空与并发入队之间的竞态窗口），
                    //    也不在停采期间往外投递（否则面板会在「已停采」后冒条目）
                    int burst = 0;
                    if (!AgentState.isDisabled()) {
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
                    }
                    // 2) 心跳：按真实流逝时间判（每轮都执行，不被 burst 分支包住——事件突发期间也按节奏发）
                    if (System.currentTimeMillis() - lastOutboundMs >= heartbeatIntervalMs) {
                        writeFrame(out, EventBus.CMD_HEARTBEAT, "{}");
                        lastOutboundMs = System.currentTimeMillis();
                    }
                    // 3) 入站帧（非阻塞轮询：超时=无数据）——[len][cmd][body]，畸形帧抛异常走重连
                    try {
                        final InboundFrame frame = readInboundFrame(in);
                        if (frame.cmd == EventBus.CMD_CONFIG) {
                            configSeen = true;
                        }
                        final String ack = handleInbound(frame.cmd, frame.payload);
                        if (ack != null) {
                            // T-02：CONFIG 生效回执（IDE 侧据此判「应用侧确已收到并生效」）
                            writeFrame(out, EventBus.CMD_CONFIG_ACK, ack);
                            lastOutboundMs = System.currentTimeMillis();
                        }
                    } catch (java.net.SocketTimeoutException ignore) {
                        // 无入站数据
                    }
                    // 3.5) T-07 看门狗：会话建立后 X ms 内从未收到任何 CONFIG → 主动停采（每连接一次）
                    //      用既有循环判，不新开线程；停采走 AgentState.setDisabled（顺带清队列，T-01b）
                    if (!configSeen && !watchdogFired
                            && System.currentTimeMillis() - connectedAtMs >= watchdogMs) {
                        watchdogFired = true;
                        System.err.println("[T-AgentConfigChannel] 会话建立后 " + watchdogMs
                                + "ms 内未收到任何 CONFIG，按保守策略主动停采（fail-open 看门狗，"
                                + "本连接仅此一条告警；若为控制通道失效，IDE 侧会同时表现为"
                                + "「已下发但无 CONFIG ACK」）");
                        AgentState.setDisabled(true);
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

    /**
     * 处理一帧入站命令，返回需回给 IDE 的 ACK body（无需回执返回 null）。
     * 包内可见：帧格式测试据此把「解析 → 应用 → 回执」整链一次走通（不绕过拆帧层）。
     */
    String handleInbound(int cmd, String payload) {
        if (cmd != EventBus.CMD_CONFIG) {
            // T-01：未知命令不再静默 return——本轮缺陷正是「命令被错位解析成 0 后无声丢弃」
            System.err.println("[T-AgentConfigChannel] 忽略未知入站命令 cmd=" + cmd
                    + "（期望 CMD_CONFIG=" + EventBus.CMD_CONFIG + "）");
            return null;
        }
        if (payload == null) {
            return null;
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
        AgentState.markConfigApplied();
        // T-02：CONFIG 应用成功回执（取值取**应用后**的生效态，含本次生效的前缀条数）
        return configAckBody();
    }

    /**
     * CONFIG 回执 body（T-02 跨侧字面契约，IDE 侧按同一字面解析）：
     * {@code {"disabled":<true|false>,"desensitize":<true|false>,"skipPrefixes":<整数>}}
     * ——键序固定、无空格、布尔小写字面量、skipPrefixes 为本次生效的帧过滤前缀条数。
     */
    static String configAckBody() {
        return "{\"disabled\":" + AgentState.isDisabled()
                + ",\"desensitize\":" + AgentState.isDesensitize()
                + ",\"skipPrefixes\":" + StackLocator.getExtraSkipPrefixes().length + "}";
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

    /** 一帧入站解析结果：命令号 + 已按 UTF-8 解码的 body。 */
    static final class InboundFrame {
        final int cmd;
        final String payload;

        InboundFrame(int cmd, String payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }

    /**
     * 读一帧入站：`[4 字节 len][1 字节 cmd][len 字节 body]`（T-AgentConfigChannel T-01）。
     *
     * <p>与出站 {@link #writeFrame} 及 IDE 侧 {@code AgentSession} 读写**同形**——此前本方法
     * 的调用点先读 1 字节当命令再读 4 字节当长度，IDE 下发的 CONFIG 因此被解析成 cmd=0 丢弃、
     * 并按错位长度阻塞读超时后静默吞掉（真机实测 len=9475，body 实为 37 字节）。
     *
     * <p>长度非法（≤0 或 > [MAX_FRAME_LEN]）视为流错位/协议漂移：WARN 记录长度与首字节后
     * **抛 IOException**，由外层循环走重连——不再以「读超时=无入站数据」的形态把故障吞掉。
     */
    static InboundFrame readInboundFrame(DataInputStream in) throws IOException {
        final int len = in.readInt();
        final int firstByte = (len >>> 24) & 0xFF;
        if (len <= 0 || len > MAX_FRAME_LEN) {
            System.err.println("[T-AgentConfigChannel] 入站帧长度非法，会话将重连（不再按读超时静默吞）: len="
                    + len + " (0x" + Integer.toHexString(len) + "), 首字节=0x"
                    + Integer.toHexString(firstByte) + ", 上限=" + MAX_FRAME_LEN);
            throw new IOException("bad inbound frame len " + len + " (first byte 0x"
                    + Integer.toHexString(firstByte) + ")");
        }
        final int cmd = in.readUnsignedByte();
        final byte[] body = new byte[len];
        in.readFully(body);
        return new InboundFrame(cmd, new String(body, java.nio.charset.StandardCharsets.UTF_8));
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
