package cn.song.mybatisplus.visualizer.log.agent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * agent 通信协议常量与编解码（§4.3，M56 T-M56-01/02）：
 * loopback TCP，长度前缀帧（4 字节 len + 1 字节 command + JSON body UTF-8）。
 * 命令三值起步 + HELLO（agent 连接后自报 pid）；loadAgent 参数 k=v 分号分隔。
 * 两侧（agent Java / IDE Java）各自实现同一契约——本类是 IDE 侧权威定义，
 * agent 侧 SenderLoop/EventBus 的常量须与这里逐值一致（AgentProtocolSyncTest 锁定）。
 *
 * <p>T-AgentConfigChannel T-02：新增 agent → IDE 方向的 {@link #CMD_CONFIG_ACK}（控制指令回执）——
 * 此前只有 IDE → agent 单向，IDE 侧日志只证明「指令已写出」，应用侧生效与否零证据。
 */
public final class AgentProtocol {

    private AgentProtocol() {
    }

    /** 事件帧：SQL 结构化事件流（JSON body，见 {@link AgentEvent}）。 */
    public static final int CMD_EVENT = 1;

    /** 心跳帧：静默 10s 一发，在线态判定依据。 */
    public static final int CMD_HEARTBEAT = 2;

    /** 配置帧（IDE → agent）：禁用开关/脱敏开关下发。 */
    public static final int CMD_CONFIG = 3;

    /** 握手帧：agent 连接后首帧，body {@code {"pid":<n>}}。 */
    public static final int CMD_HELLO = 4;

    /**
     * 配置回执帧（agent → IDE，T-AgentConfigChannel T-02）：应用侧把 CONFIG 应用到采集开关后
     * 回一帧，body 见 {@link #configAckBody}。
     *
     * <p>为什么需要：本轮现场（开关未开却被捕获）里 IDE 侧只有「CONFIG 已下发」的日志，
     * 应用侧收到与否零证据——排障只能靠行为反推（全天无 {@code startCapture} 却有条目）。回执把
     * 「生效」从推断变成一行可核实的日志，并把「控制通道静默失效」暴露成可观测事实。
     *
     * <p>取值 5（下一个空闲号）。<b>旧 agent 天然兼容</b>（未知 cmd 走 else 分支忽略），故本常量
     * 只在支持回执的新 agent 侧出现——{@code AgentProtocolSyncTest.命令常量两侧一致} 因此暂不锁它，
     * 待应用侧 T-02 落地后由两侧同步补断言。
     */
    public static final int CMD_CONFIG_ACK = 5;

    /** 单帧 payload 上限（1MB：SQL/堆栈文本远小于此，超限视为流损坏立即断开）。 */
    public static final int MAX_FRAME_LEN = 1 << 20;

    /**
     * agent 侧心跳间隔（§4.3 静默 10s）——与 agent {@code SenderLoop.DEFAULT_HEARTBEAT_MS} 同值，
     * 由 {@code AgentProtocolSyncTest} 源码扫描锁定（{@code ONLINE_WINDOW_MS >= 3 * 该值}）。
     */
    public static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    /**
     * 在线态判定：最近 N 毫秒内有心跳或事件。
     *
     * <p><b>不变量：窗口 = 3 倍心跳间隔</b>——留 3 次心跳的容错余量（一次网络/调度抖动、一次事件突发
     * 占用发送线程、一次入站读阻塞跨过判定点）。窗口不再与心跳间隔各自写死：此前 agent 侧按
     * 「假定每轮 200ms」累加计时，「静默 10s」实际要 60s 才发（真实每轮≈1.2s），而窗口 30s 照旧
     * 执行 → 增强捕获会话建立后 30 秒必被摘除（T-AgentHeartbeat 现场：HELLO +17ms、首条
     * HEARTBEAT +60633ms）。值未变（心跳 10s → 窗口 30s），推导关系写进代码，任一侧漂移都会在
     * {@code AgentProtocolSyncTest.心跳节奏_窗口不小于三倍间隔} 转红。
     */
    public static final long ONLINE_WINDOW_MS = HEARTBEAT_INTERVAL_MS * 3;

    /** loadAgent 参数串（§4.3 k=v 契约，agent 侧 AgentArgs.parse 对应）。 */
    public static String agentArgs(int port, String logLevel) {
        return "port=" + port + ";logLevel=" + logLevel;
    }

    /** loadAgent 参数串（logLevel 缺省 warn，Kotlin 默认参数的便捷重载）。 */
    public static String agentArgs(int port) {
        return agentArgs(port, "warn");
    }

    /** CONFIG 帧 body：禁用/脱敏开关（agent 侧极简 contains 解析对应——键序固定）。 */
    public static String configBody(boolean disabled, boolean desensitize) {
        return "{\"disabled\":" + disabled + ",\"desensitize\":" + desensitize + "}";
    }

    /**
     * T-SourceJumpFunnel P1-3：CONFIG 帧扩展 body——追加用户登记的帧过滤前缀
     * （{@code "skipPrefixes":["a.","b."]}；空列表省略键，旧 agent contains 解析天然忽略未知键）。
     * 前缀文本 JSON 转义走最简 deny-list（反斜杠/引号/控制字符），与 {@link #configBody(boolean, boolean)} 的
     * 固定布尔键序兼容（agent 侧 disabled/desensitize 判定不受追加键影响）。
     * T-ToolSiteBizFrame：三参形态 = 不带 JAR 业务帧反向名单（master 同名重载委托 emptyList）。
     */
    public static String configBody(boolean disabled, boolean desensitize, java.util.List<String> skipPrefixes) {
        return configBody(disabled, desensitize, skipPrefixes, java.util.List.of());
    }

    /**
     * T-ToolSiteBizFrame：CONFIG 帧再追加 JAR 业务帧反向名单
     * （{@code "bizJarPrefixes":["a.","b."]}；空列表省略键，旧 agent contains 解析天然忽略未知键）。
     * 语义与 skipPrefixes 相反（名单内包名的帧即使来自 JAR 也按业务帧对待），键彼此独立。
     */
    public static String configBody(boolean disabled, boolean desensitize,
            java.util.List<String> skipPrefixes, java.util.List<String> bizJarPrefixes) {
        java.util.List<String> clean = new java.util.ArrayList<>();
        for (String p : skipPrefixes) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                clean.add(trimmed);
            }
        }
        java.util.List<String> cleanBiz = new java.util.ArrayList<>();
        for (String p : bizJarPrefixes) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                cleanBiz.add(trimmed);
            }
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add("\"disabled\":" + disabled);
        parts.add("\"desensitize\":" + desensitize);
        // 空列表省略键（agent 侧缺键=清空，语义等同）
        if (!clean.isEmpty()) {
            StringBuilder arr = new StringBuilder();
            for (int i = 0; i < clean.size(); i++) {
                if (i > 0) {
                    arr.append(',');
                }
                arr.append(escapeArrayItem(clean.get(i)));
            }
            parts.add("\"skipPrefixes\":[" + arr + "]");
        }
        if (!cleanBiz.isEmpty()) {
            StringBuilder arr = new StringBuilder();
            for (int i = 0; i < cleanBiz.size(); i++) {
                if (i > 0) {
                    arr.append(',');
                }
                arr.append(escapeArrayItem(cleanBiz.get(i)));
            }
            parts.add("\"bizJarPrefixes\":[" + arr + "]");
        }
        return "{" + String.join(",", parts) + "}";
    }

    /** 数组项最简 deny-list JSON 转义（反斜杠/引号/控制字符）。 */
    private static String escapeArrayItem(String p) {
        return "\"" + p.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\b", "\\b").replace("\u000C", "\\f")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    // ---------- T-AgentConfigChannel T-02：CONFIG 回执（agent → IDE） ----------

    /**
     * CONFIG 回执生效值四元（{@link #parseConfigAck} 产物）。
     * {@code skipPrefixes}/{@code bizJarPrefixes} 是应用侧<b>实际接受</b>的帧过滤前缀与 JAR 业务帧反向名单条数
     * （不是列表本身——回执只报生效结果，前缀文本两侧各自持有，IDE 侧下发源即设置页）。
     */
    public record ConfigAck(
        /** 应用侧生效的禁用采集开关值。 */
        boolean disabled,
        /** 应用侧生效的脱敏开关值。 */
        boolean desensitize,
        /** 应用侧实际接受的帧过滤前缀条数（不是列表本身）。 */
        int skipPrefixes,
        /** T-ToolSiteBizFrame：应用侧实际接受的 JAR 业务帧反向名单条数（不是列表本身）。 */
        int bizJarPrefixes
    ) {
    }

    /**
     * 回执 body（agent 侧同契约逐字节一致）：
     * {@code {"disabled":<bool>,"desensitize":<bool>,"skipPrefixes":<int>,"bizJarPrefixes":<int>}}。
     *
     * <p>键序固定、无空格——<b>这是刻意的严格形态</b>：两侧各自手写 JSON，本轮缺陷正是「形态漂移
     * 无人发现」（出站写 {@code [len][cmd]}、入站按 {@code [cmd][len]} 读，两侧都自认为对）。回执只认
     * 这一种形态，任何偏差都会被 {@link #parseConfigAck} 记成畸形并留痕，不会静默按「差不多」放行。
     * bizJarPrefixes 为 T-ToolSiteBizFrame 列尾追加段（agent 与 IDE 同制品分发、版本恒同对；
     * 旧 agent 错配窗口的 3 键 ACK 按畸形留痕——与 skipPrefixes 引入时同边界）。
     */
    public static String configAckBody(boolean disabled, boolean desensitize, int skipPrefixes, int bizJarPrefixes) {
        return "{\"disabled\":" + disabled + ",\"desensitize\":" + desensitize
            + ",\"skipPrefixes\":" + skipPrefixes + ",\"bizJarPrefixes\":" + bizJarPrefixes + "}";
    }

    /** 严格形态判据（无空格、键序固定、值域受限）。 */
    private static final java.util.regex.Pattern CONFIG_ACK_REGEX =
        java.util.regex.Pattern.compile("\\{\"disabled\":(true|false),\"desensitize\":(true|false),\"skipPrefixes\":(-?\\d+),\"bizJarPrefixes\":(-?\\d+)\\}");

    /**
     * 回执解析失败累计（诊断计数，跨会话单调递增）：畸形回执是「两侧契约漂移」的直接证据，
     * 故不只丢帧，还要计数（会话侧 WARN 会带出该累计值）。
     */
    public static final AtomicLong configAckParseFailures = new AtomicLong();

    /**
     * 解析 CONFIG 回执 body；<b>不抛</b>——非 {@link #configAckBody} 的严格形态一律返回 null 并累加
     * {@link #configAckParseFailures}（调用方只记 WARN 不中断读循环；畸形回执不构成掉线理由）。
     * {@code skipPrefixes}/{@code bizJarPrefixes} 溢出 Int 同样按畸形处理（回执值域本就该是小整数）。
     */
    public static ConfigAck parseConfigAck(String body) {
        java.util.regex.Matcher m = CONFIG_ACK_REGEX.matcher(body);
        if (!m.matches()) {
            configAckParseFailures.incrementAndGet();
            return null;
        }
        Integer n = toIntOrNull(m.group(3));
        Integer biz = toIntOrNull(m.group(4));
        if (n == null || biz == null) {
            configAckParseFailures.incrementAndGet();
            return null;
        }
        return new ConfigAck(
            "true".equals(m.group(1)),
            "true".equals(m.group(2)),
            n,
            biz);
    }

    /** Kotlin {@code String.toIntOrNull} 等价：非整数/溢出 Int 返回 null。 */
    private static Integer toIntOrNull(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
