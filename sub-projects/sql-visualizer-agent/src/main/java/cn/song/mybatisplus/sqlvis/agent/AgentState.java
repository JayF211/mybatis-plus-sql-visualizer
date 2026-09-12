package cn.song.mybatisplus.sqlvis.agent;

/**
 * agent 全局态（采集红线 §4.2 #5）：禁用开关 = volatile 短路（纳秒级，全局禁用不卸载语义）。
 * CONFIG 命令（cmd=3）下发时由 EventSender 线程更新。
 */
public final class AgentState {

    /** 全局禁用开关：true = 采集短路（钩子零开销直返），已 retransform 的类不还原（Instrumentation 不可逆）。 */
    private static volatile boolean disabled = false;

    /** CONFIG 下发的脱敏开关：true = 打码（默认开，数据不出应用 JVM 前已处理）。 */
    private static volatile boolean desensitize = true;

    private static volatile String logLevel = "warn";

    public static boolean isDisabled() {
        return disabled;
    }

    public static void setDisabled(boolean value) {
        disabled = value;
    }

    public static boolean isDesensitize() {
        return desensitize;
    }

    public static void setDesensitize(boolean value) {
        desensitize = value;
    }

    public static String getLogLevel() {
        return logLevel;
    }

    public static void setLogLevel(String value) {
        logLevel = value == null ? "warn" : value;
    }

    private AgentState() {
    }
}
