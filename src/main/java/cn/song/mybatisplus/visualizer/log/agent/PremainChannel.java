// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt 移植（PremainChannel 顶层类）
package cn.song.mybatisplus.visualizer.log.agent;

import java.net.ServerSocket;

/**
 * T-M56Phase2-Premain：一条待回连的 premain 通道——启动前协商出的 loopback 端口 + 其监听 socket。
 * {@link #claimed} 由 accept 线程在收到回连时置位（{@code AgentManager.hasPendingPremainChannel()} 与
 * prune 的判据）。
 */
public final class PremainChannel {

    /** 前置协商出的 loopback 端口。 */
    public final int port;

    /** 监听 socket（回连 accept 用；超时/清理时关闭）。 */
    public final ServerSocket server;

    /** 通道准备时刻（时钟同 AgentManager.clockMs；过期清理与 attach 跳过窗判定用）。 */
    public final long preparedAtMs;

    /** 回连是否已被 accept 线程认领（volatile：accept 线程写、巡检/判定线程读）。 */
    public volatile boolean claimed = false;

    public PremainChannel(int port, ServerSocket server, long preparedAtMs) {
        this.port = port;
        this.server = server;
        this.preparedAtMs = preparedAtMs;
    }
}
