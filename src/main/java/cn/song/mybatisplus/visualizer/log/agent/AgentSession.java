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
import java.util.concurrent.atomic.AtomicLong;

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

    private volatile boolean alive = true;

    private volatile long lastSeen = System.currentTimeMillis();

    private final DataOutputStream output;
    private final AtomicLong eventDropped = new AtomicLong();

    /** T-AgentLogTime：日志时间未命中计数（诊断——键不等 vs 时序赶不上，真机排障一眼分辨）。 */
    private final AtomicLong timeMiss = new AtomicLong();

    public AgentSession(Project project, long pid, Socket socket, Runnable onStateChange) throws IOException {
        this.project = project;
        this.pid = pid;
        this.socket = socket;
        this.onStateChange = onStateChange;
        this.output = new DataOutputStream(socket.getOutputStream());
        socket.setSoTimeout(0);
        Thread reader = new Thread(this::readLoop, "mps-agent-session-" + pid);
        reader.setDaemon(true);
        reader.start();
    }

    public boolean isAlive() {
        return alive;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public long getPid() {
        return pid;
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
                lastSeen = System.currentTimeMillis();
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
            byte[] body = AgentProtocol.configBody(disabled, desensitize).getBytes(StandardCharsets.UTF_8);
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
