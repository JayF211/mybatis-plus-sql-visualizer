// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/config/EnhancedCaptureConfirmDialog.kt 移植（EnhancedCaptureConsent 顶层枚举）
package cn.song.mybatisplus.visualizer.config;

/**
 * 增强捕获注入确认结果（T-PS4-01 三态）。
 *
 * - {@link #ACCEPTED}：同意注入——AgentManager 置 confirmed 持久化并继续挂载（此后永不弹）。
 * - {@link #DISABLE_CAPTURE}：关闭增强捕获并不再询问——AgentManager 置 enhancedCaptureEnabled=false
 *   持久化，此后任何进程启动都不弹窗、不 attach，直到用户在设置页重新开启。
 * - {@link #REJECTED}：拒绝（含右上角 X 关闭）——AgentManager 持久化「拒绝时刻」进入 24h 冷却，
 *   冷却期内不重复弹；冷却只作用于弹窗频率、不改变同意门槛（保守口径）。
 */
public enum EnhancedCaptureConsent {
    ACCEPTED,
    DISABLE_CAPTURE,
    REJECTED,
}
