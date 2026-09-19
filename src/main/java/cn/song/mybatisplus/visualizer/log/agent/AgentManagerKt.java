// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt 移植（agentStatusText 顶层函数）
package cn.song.mybatisplus.visualizer.log.agent;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

/**
 * AgentManager.kt 顶层函数载体。
 */
public final class AgentManagerKt {

    private AgentManagerKt() {
    }

    /**
     * T-CaptureTruth T-CT-01：增强捕获状态行文案（纯函数，五态全分支可单测）。
     *
     * 立项动因：原实现只有「有活会话 / 曾挂载 / 其余 null」三态——<b>「开关开着但从未授权」落进 null
     * 分支</b>，设置页状态行渲染成空白而复选框仍显示勾选，用户读作「开着且没事」，实际一条都捕不到
     * （T-CaptureTruth 根因之三；旧守卫断言「无会话应返回 null」把该行为钉死，本次一并改）。
     *
     * 五态：
     *  1. 关闭（!enabled）→ null（复选框未勾选，无需叙事）；
     *  2. 有活会话 →「已挂载 pid=…」；
     *  3. 未授权 + 冷却中 → 含剩余小时与「重新授权」指引（冷却原本静默，用户无从得知被拒过）；
     *  4. 未授权 + 无冷却 → 下次启动主配置时会询问；
     *  5. 已授权：曾挂载过 → 回落文案（T-RA-01 起区分「连接断开」与「心跳过期/应用无响应」两因）；
     *    从未挂载 → 等待应用进程启动后挂载。
     */
    public static String agentStatusText(AgentStatusSnapshot s) {
        if (!s.enabled()) {
            return null;
        }
        if (!s.livePids().isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < s.livePids().size(); i++) {
                if (i > 0) {
                    joined.append(',');
                }
                joined.append("pid=").append(s.livePids().get(i));
            }
            return MpSqlVisualizerBundle.message("settings.enhancedCapture.status.attached", joined.toString());
        }
        if (!s.confirmed()) {
            if (s.rejectCooldownRemainingMs() > 0) {
                // 小时向上取整（不足 1 小时也显示 1，避免「剩 0 小时」的歧义）
                long hours = Math.max(1L, (s.rejectCooldownRemainingMs() + 3_599_999L) / 3_600_000L);
                return MpSqlVisualizerBundle.message("settings.enhancedCapture.status.unauthorizedCooldown", hours);
            }
            return MpSqlVisualizerBundle.message("settings.enhancedCapture.status.unauthorizedPending");
        }
        if (s.everAttached()) {
            // T-ReachAudit T-RA-01：心跳过期 = 应用侧停摆（socket 未断），与真断连的处置不同——
            // 前者用户该去看应用进程，后者只是连接掉了，故分开叙述
            return MpSqlVisualizerBundle.message(
                s.heartbeatExpired()
                    ? "settings.enhancedCapture.status.heartbeatLost"
                    : "settings.enhancedCapture.status.fellBack");
        }
        return MpSqlVisualizerBundle.message("settings.enhancedCapture.status.authorizedWaiting");
    }
}
