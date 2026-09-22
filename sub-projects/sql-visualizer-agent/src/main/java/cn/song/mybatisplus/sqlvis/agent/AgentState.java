package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;

import java.util.concurrent.atomic.AtomicLong;

/**
 * agent 全局态（采集红线 §4.2 #5）：禁用开关 = volatile 短路（纳秒级，全局禁用不卸载语义）。
 * CONFIG 命令（cmd=3）下发时由 EventSender 线程更新。
 *
 * T-AgentConfigChannel T-01b：`disabled` 的**下降沿**（false → true）顺带清空
 * {@link EventBus} 已排队事件——否则停采指令生效瞬间，连接建立前的存量事件仍会被补投到面板
 * （应用侧「真停」就只停住了后续采集，停不住已经排队的）。
 * T-AgentConfigChannel T-07：看门狗在会话建立后迟迟收不到 CONFIG 时会走同一条 setDisabled(true)。
 */
public final class AgentState {

    /** 全局禁用开关：true = 采集短路（钩子零开销直返），已 retransform 的类不还原（Instrumentation 不可逆）。 */
    private static volatile boolean disabled = false;

    /** CONFIG 下发的脱敏开关：true = 打码（默认开，数据不出应用 JVM 前已处理）。 */
    private static volatile boolean desensitize = true;

    private static volatile String logLevel = "warn";

    /**
     * 已成功应用的 CONFIG 帧数（T-AgentConfigChannel T-02 可观测性）：0 = 控制通道从未通过。
     * 排障时「IDE 说下发了、应用侧说没收到」这类争议，读这个计数即可判定（无需行为反推）。
     */
    private static final AtomicLong CONFIG_APPLIED = new AtomicLong();

    public static boolean isDisabled() {
        return disabled;
    }

    public static void setDisabled(boolean value) {
        final boolean prev = disabled;
        disabled = value;
        if (!prev && value) {
            // 停采下降沿：清空已排队事件并计数（EventBus.clearedCount 可观测）
            EventBus.clearQueued();
        }
    }

    public static boolean isDesensitize() {
        return desensitize;
    }

    public static void setDesensitize(boolean value) {
        desensitize = value;
    }

    /** CONFIG 帧应用成功（含 ACK 回执）时调用：计数供排障与测试观测。 */
    public static void markConfigApplied() {
        CONFIG_APPLIED.incrementAndGet();
    }

    /** 已成功应用的 CONFIG 帧数（T-AgentConfigChannel T-02）。 */
    public static long configAppliedCount() {
        return CONFIG_APPLIED.get();
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
