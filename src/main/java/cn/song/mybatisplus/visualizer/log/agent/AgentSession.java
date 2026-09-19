package cn.song.mybatisplus.visualizer.log.agent;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.LogSqlModelsKt;
import cn.song.mybatisplus.visualizer.log.LogTimeCorrelator;
import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 单个 agent 会话：读线程循环解帧（EVENT → 适配 → buffer；HEARTBEAT 刷新在线窗口；
 * HELLO 记录 agent 侧 pid），CONFIG 下发。alive = socket 未断。
 */
public class AgentSession {

    private static final Logger LOG = Logger.getInstance(AgentSession.class);

    /** 未命中逐条打印上限（前 N 条够看清两侧 rawSql 差异）。 */
    private static final long MISS_LOG_LIMIT = 3L;

    /** 逐条打印用尽后的累计打印步长。 */
    private static final long MISS_LOG_STEP = 200L;

    private final Project project;
    private final long pid;
    private final Socket socket;
    private final Runnable onStateChange;

    /**
     * T-ReachAudit T-RA-01：时间源（默认系统时钟）。与 {@code AgentManager.clockMs} 同源注入——心跳窗口判定
     * 跨「会话记 lastSeen」与「管理器算过期」两侧，两侧时钟不同源则测试无法推进时间（假时钟一注入，
     * 真实时间戳的 lastSeen 会让会话永远健康），真机也无差异。
     */
    private final LongSupplier clockMs;

    private volatile boolean alive = true;

    private volatile long lastSeen;

    private final DataOutputStream output;
    private final AtomicLong eventDropped = new AtomicLong();

    /** T-DialectAutoInfer：最近一次事件携带的连接 URL（connect 时捕获，会话内记忆；null=旧版 agent/未捕获）。 */
    private volatile String lastConnectionUrl = null;

    /** T-AgentLogTime：日志时间未命中计数（诊断——键不等 vs 时序赶不上，真机排障一眼分辨）。 */
    private final AtomicLong timeMiss = new AtomicLong();

    /** 便捷构造器：clockMs 缺省系统时钟（Kotlin 默认参数）。 */
    public AgentSession(Project project, long pid, Socket socket, Runnable onStateChange) throws IOException {
        this(project, pid, socket, onStateChange, () -> System.currentTimeMillis());
    }

    public AgentSession(Project project, long pid, Socket socket, Runnable onStateChange, LongSupplier clockMs) throws IOException {
        this.project = project;
        this.pid = pid;
        this.socket = socket;
        this.onStateChange = onStateChange;
        this.clockMs = clockMs;
        this.output = new DataOutputStream(socket.getOutputStream());
        this.lastSeen = clockMs.getAsLong();
        socket.setSoTimeout(0);
        Thread reader = new Thread(this::readLoop, "mps-agent-session-" + pid);
        reader.setDaemon(true);
        reader.start();
    }

    public boolean isAlive() {
        return alive;
    }

    /** Kotlin {@code var alive} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public boolean getAlive() {
        return alive;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public long getPid() {
        return pid;
    }

    /** T-DialectAutoInfer：最近一次事件携带的连接 URL（会话内记忆；null=旧版 agent/未捕获）。 */
    public String getLastConnectionUrl() {
        return lastConnectionUrl;
    }

    private void readLoop() {
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            while (true) {
                int len = input.readInt();
                if (len < 0 || len > AgentProtocol.MAX_FRAME_LEN) {
                    // 超限帧容错：读掉 body 丢弃继续，不杀会话（一次大事件不应永久掉线）
                    LOG.warn("frame len=" + len + " 超限（MAX=" + AgentProtocol.MAX_FRAME_LEN + "）——丢弃该帧继续");
                    if (len > 0) {
                        input.skipNBytes((long) len);
                    }
                    continue;
                }
                int cmd = input.readUnsignedByte();
                byte[] body = new byte[len];
                input.readFully(body);
                lastSeen = clockMs.getAsLong();
                handleFrame(cmd, new String(body, StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            LOG.warn("会话读循环退出（pid=" + pid + ", alive 前=" + alive + "）——掉线原因如下", t);
            alive = false;
            onStateChange.run();
        }
    }

    private void handleFrame(int cmd, String body) {
        if (cmd == AgentProtocol.CMD_EVENT) {
            AgentEvent event = AgentEvent.parse(body);
            if (event == null) {
                LOG.warn("EVENT 帧解析失败丢弃（pid=" + pid + ", dropped=" + eventDropped.incrementAndGet()
                    + ", bodyLen=" + body.length() + ", head=" + take(body, 120) + "）");
                return;
            }
            try {
                // T-DialectAutoInfer：connect URL 会话内记忆（推断依据；null 容错——旧版 agent 无此键）
                String url = event.url();
                if (url != null && !url.isBlank()) {
                    lastConnectionUrl = url;
                }
                LogSqlEntry entry = AgentEventAdapter.toEntry(event);
                // T-AgentDialectLoop / T-AgentLogTime：接管期间控制台嗅探暂存的「日志行原始时间」
                // 按 logTimeKey（占位符版 SQL 指纹）取回，参数值序列作次级判别（stampLogTime）。
                // 未命中回落捕获时间（= execute 开始时刻，与日志行只差毫秒级）并留痕 logTimeMiss。
                LogSqlEntry stamped = LogSqlModelsKt.stampLogTime(entry);
                LogSqlEntry effective = stamped != null ? stamped : logTimeMiss(entry);
                project.getService(SqlLogManager.class).getEntries().addEntry(effective);
            } catch (Throwable t) {
                LOG.warn("EVENT 入队失败（不斩断流）", t);
            }
        }
        // HEARTBEAT/HELLO：lastSeen 已刷新；未知命令忽略（协议向前兼容）
    }

    /**
     * T-AgentLogTime：日志时间未命中留痕——前 {@link #MISS_LOG_LIMIT} 条逐条打印键与 SQL 头部
     * （判「两侧 rawSql 不等」），其后每 {@link #MISS_LOG_STEP} 条打一次累计（判「时序赶不上」）。
     * 未命中只影响时间字段（回落 execute 开始时刻），不影响条目本身，故只记日志不动流程。
     */
    private LogSqlEntry logTimeMiss(LogSqlEntry entry) {
        long n = timeMiss.incrementAndGet();
        if (n <= MISS_LOG_LIMIT) {
            LOG.info(
                "日志时间未命中 #" + n + "（回落捕获时间）：key=" + LogSqlModelsKt.logTimeKey(entry)
                    + " params=" + take(LogSqlModelsKt.logTimeParamSignature(entry.params()), 60)
                    + " sql=" + take(entry.rawSql(), 80));
        } else if (n % MISS_LOG_STEP == 0L) {
            LOG.info("日志时间未命中累计 " + n + " 条（最近：key=" + LogSqlModelsKt.logTimeKey(entry)
                + " 相关器存量=" + LogTimeCorrelator.size() + "）");
        }
        return entry;
    }

    public void sendConfig(boolean disabled) {
        sendConfig(disabled, PluginSettings.getInstance().getState().getEnhancedCaptureDesensitize());
    }

    public void sendConfig(boolean disabled, boolean desensitize) {
        try {
            // T-SourceJumpFunnel P1-3：随 CONFIG 附带用户登记的帧过滤前缀（热下发；旧 agent 忽略该键）
            List<String> prefixes = new ArrayList<>();
            for (String line : PluginSettings.getInstance().getState().getSourceFrameSkipPrefixes().lines().toList()) {
                if (!line.isBlank()) {
                    prefixes.add(line);
                }
            }
            byte[] body = AgentProtocol.configBody(disabled, desensitize, prefixes).getBytes(StandardCharsets.UTF_8);
            synchronized (output) {
                output.writeInt(body.length);
                output.write(AgentProtocol.CMD_CONFIG);
                output.write(body);
                output.flush();
            }
        } catch (Throwable t) {
            alive = false;
            onStateChange.run();
        }
    }

    public void close() {
        alive = false;
        try {
            socket.close();
        } catch (Throwable ignored) {
            // 对齐 Kotlin runCatching：关闭失败不影响状态翻转
        }
        onStateChange.run();
    }

    /** Kotlin String.take(n) 等价：取前 n 个字符（不足则全量）。 */
    private static String take(String s, int n) {
        return s.substring(0, Math.min(n, s.length()));
    }
}
