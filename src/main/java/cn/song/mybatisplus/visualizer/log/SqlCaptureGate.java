package cn.song.mybatisplus.visualizer.log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M56 T-M56-02：增强捕获接管闸门——agent 在线时置 true，控制台桥的解析入口短路（空转），
 * 面板只呈现 agent 通道条目；连接丢失/开关关闭回落时置 false 恢复。桥链路本身零改动。
 * AtomicBoolean：读侧（逐行热路径）单次 volatile 读，写侧（会话状态切换）罕见。
 */
public final class SqlCaptureGate {

    private SqlCaptureGate() {
    }

    public static final AtomicBoolean paused = new AtomicBoolean(false);

    /**
     * M56 T-M56-09：「EP 首行标志」——consoleFilterProvider EP 的 Filter 首次处理控制台行时
     * 置 true（会话内粘滞）。桥的轮询据此跳过 addMessageFilter 挂载（EP 已覆盖全部控制台，
     * 双挂载会双路解析）；定时器本身保留（控制台摘除检测等职责）。
     */
    public static volatile boolean epActive = false;
}
