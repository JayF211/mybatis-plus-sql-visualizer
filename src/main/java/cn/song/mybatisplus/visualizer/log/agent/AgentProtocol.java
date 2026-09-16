package cn.song.mybatisplus.visualizer.log.agent;

/**
 * agent 通信协议常量与编解码（§4.3，M56 T-M56-01/02）：
 * loopback TCP，长度前缀帧（4 字节 len + 1 字节 command + JSON body UTF-8）。
 * 命令三值起步 + HELLO（agent 连接后自报 pid）；loadAgent 参数 k=v 分号分隔。
 * 两侧（agent Java / IDE Java）各自实现同一契约——本类是 IDE 侧权威定义，
 * agent 侧 SenderLoop/EventBus 的常量须与这里逐值一致（AgentProtocolSyncTest 锁定）。
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

    /** 单帧 payload 上限（1MB：SQL/堆栈文本远小于此，超限视为流损坏立即断开）。 */
    public static final int MAX_FRAME_LEN = 1 << 20;

    /** 在线态判定：最近 N 毫秒内有心跳或事件。 */
    public static final long ONLINE_WINDOW_MS = 30_000L;

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
}
