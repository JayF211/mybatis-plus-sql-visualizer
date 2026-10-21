package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * R53（V80① 落地）：工具窗双通道捕获状态栏（SQL 日志页签底部，捕捉期间常驻）。
 *
 * <p>立项动因：增强捕获「现在捕获没有 / 暂停了」状态此前只藏在空态诊断行里——列表有条目时
 * 用户完全看不到双通道状态；R53 把两条通道的实时状态摆到台面上：</p>
 * <ul>
 * <li>控制台通道：SqlLogConsoleBridge.attachedConsoles（已挂载数）+ consoleLinesSeen（已扫描行）；</li>
 * <li>增强 agent 通道：总开关关（未开启）/ 暂停中 / 已挂载 pid / 已收事件 N（复用
 *     AgentManager.statusSummary 的五态文案）。</li>
 * </ul>
 *
 * <p>V80① 证伪结论：取数全为只读内存态（CopyOnWriteArrayList / ConcurrentHashMap / AtomicLong /
 * volatile 字段），工具窗 2s 无锁轮询安全——与既有空态诊断行（SqlLogPanel.syncDiagnostics）
 * 同一取数口径，不新开线程。</p>
 *
 * <p>纯函数（零平台依赖）便于全分支单测；面板只负责取数与渲染（SqlLogPanel.syncCaptureStatusBar）。</p>
 */
public final class CaptureStatusBar {

    private CaptureStatusBar() {
    }

    /** 状态栏输入快照（纯数据）。 */
    public record CaptureStatusLine(
            /** 是否在捕捉中（状态栏只在捕捉期间显示；非捕捉态返回 null）。 */
            boolean capturing,
            /** 控制台通道：已挂载控制台数。 */
            int consolesAttached,
            /** 控制台通道：已扫描行数（SqlLogConsoleBridge.consoleLinesSeen）。 */
            long linesSeen,
            /**
             * 增强 agent 通道：AgentManager.statusSummary 成品文案（null = 增强总开关关）。
             * 只传成品文案而非快照，面板取数经 statusSummary 单点（与设置页/空态诊断行同源）。
             */
            @Nullable String agentStatus,
            /** 增强 agent 通道：健康（已挂载）会话 pid 列表（AgentManager.healthyPids）。 */
            @NotNull List<Long> agentLivePids,
            /** 增强 agent 通道：用户是否已暂停（AgentManager.userPaused）。 */
            boolean agentPaused,
            /** 增强 agent 通道：已收事件累计（AgentEventAdapter.receivedEvents）。 */
            long agentEventsReceived
    ) {
    }

    /**
     * 状态栏两段文案（第一段=控制台通道，第二段=增强通道）；非捕捉态返回 null（状态栏清空隐藏）。
     *
     * @return Pair(控制台段, 增强段)；null = 非捕捉态
     */
    public static @Nullable Pair captureStatusLineText(@NotNull CaptureStatusLine d) {
        if (!d.capturing()) {
            return null;
        }
        String console = MpSqlVisualizerBundle.message("log.statusbar.console",
                d.consolesAttached(), d.linesSeen());
        String agent;
        if (d.agentStatus() == null) {
            // 总开关关：无叙事（与 agentStatusText 的 !enabled → null 同口径）
            agent = MpSqlVisualizerBundle.message("log.statusbar.agent.off");
        } else if (d.agentPaused()) {
            // 暂停中：暂停语义盖过挂载叙事（暂停中也可能已挂载——连接活着、只是不入列表）
            agent = MpSqlVisualizerBundle.message("log.statusbar.agent.paused",
                    d.agentStatus(), d.agentEventsReceived());
        } else if (!d.agentLivePids().isEmpty()) {
            // 已挂载：状态文案（已挂载 pid=…）+ 已收事件 N
            agent = MpSqlVisualizerBundle.message("log.statusbar.agent.attached",
                    d.agentStatus(), d.agentEventsReceived());
        } else {
            // 未挂载：等待/冷却/回落等既有五态文案
            agent = MpSqlVisualizerBundle.message("log.statusbar.agent.summary", d.agentStatus());
        }
        return new Pair(console, agent);
    }

    /** 两段文案载体（Kotlin Pair 的 Java 承载）。 */
    public record Pair(String first, String second) {
    }
}
