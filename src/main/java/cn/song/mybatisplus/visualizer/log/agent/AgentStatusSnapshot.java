// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt 移植（AgentStatusSnapshot 顶层 data class）
package cn.song.mybatisplus.visualizer.log.agent;

import java.util.List;

/**
 * T-CaptureTruth T-CT-01：增强捕获状态行输入快照（纯数据，{@link AgentManagerKt#agentStatusText} 入参，零平台依赖）。
 */
public record AgentStatusSnapshot(
    /** 增强捕获总开关是否开启。 */
    boolean enabled,
    /** 注入确认是否已达成。 */
    boolean confirmed,
    /** 拒绝冷却剩余毫秒数（0 = 无冷却）。 */
    long rejectCooldownRemainingMs,
    /** 健康（socket 未断且心跳窗口内）会话的 pid 列表。 */
    List<Long> livePids,
    /** 是否发生过成功挂载。 */
    boolean everAttached,
    /** T-ReachAudit T-RA-01：最近一次摘除是否因心跳过期（应用侧无响应，而非连接断开）。 */
    boolean heartbeatExpired
) {
    /** 便捷构造器：heartbeatExpired 缺省 false（Kotlin 默认参数）。 */
    public AgentStatusSnapshot(boolean enabled, boolean confirmed, long rejectCooldownRemainingMs,
                               List<Long> livePids, boolean everAttached) {
        this(enabled, confirmed, rejectCooldownRemainingMs, livePids, everAttached, false);
    }
}
