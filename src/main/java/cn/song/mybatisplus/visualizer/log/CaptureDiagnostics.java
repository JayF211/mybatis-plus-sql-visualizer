// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/CaptureDiagnostics.kt 移植
package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-CaptureTruth T-CT-03：捕获链路零产出诊断（SQL 日志面板空态第二行）。
 *
 * <p>立项动因：捕获零产出时面板只给一个空列表，用户无从判断卡在哪一环——本次现场（沙盒里
 * 「增强已开启但未授权 → 24h 冷却 → 不挂载」+「应用控制台本就没有 SQL 日志」两条通道同时归零）
 * 就是被这条沉默拖了整整一轮排障。诊断行把两条通道的状态摆到台面上：
 * <ul>
 * <li>增强通道：复用 {@code agent.AgentManager.statusSummary} 的五态文案
 * （未授权/冷却中/等待进程/已挂载/已回落），未开启时显示「未启用」；</li>
 * <li>控制台通道：已挂载控制台数 + 已扫描行数（{@code SqlLogConsoleBridge.consoleLinesSeen}）；</li>
 * <li>提示语按「等待控制台 / 控制台无输出 / 有输出但没匹配到 SQL」三档给出可执行的下一步。</li>
 * </ul>
 *
 * <p>T-RA-03：控制台通道再加两个「行去哪了」的计数——{@code SqlLogConsoleBridge.replayDropCount}
 * （重放守卫丢弃）与 {@code SqlLogConsoleBridge.epActiveShortCircuitCount}（EP 短路）。二者原本只在
 * 测试里读，生产侧零出口：守卫误把正常流式行判成重放、或 EP 短路后条目未落到本面板时，
 * 面板照旧显示「已扫描 N 行 / 产出 0 条」，把「有行但被吃掉」伪装成「压根没输出」。
 * 只在计数 &gt; 0 时显示——常态零值不应给正常场景添噪。
 *
 * <p>纯函数（零平台依赖）便于全分支单测；面板只负责取数（{@code SqlLogPanel.syncDiagnostics}）。
 */
public record CaptureDiagnostics(
    /** 是否处于捕获开启状态。 */
    boolean capturing,
    /** 是否仍在等待应用控制台出现（控制台通道未挂载）。 */
    boolean waitingForConsole,
    /** 面板当前条目数（&gt;0 即有产出，不出诊断行）。 */
    int entryCount,
    /** 已挂载的应用控制台数。 */
    int consolesAttached,
    /** 控制台通道已扫描行数。 */
    long linesSeen,
    /** 增强通道状态文案（null = 增强未开启）。 */
    @Nullable String enhancedStatus,
    /** 重放守卫丢弃行数（T-RA-03；0 = 无丢弃，不显示）。 */
    long replayDropped,
    /** EP 通道短路行数（T-RA-03；0 = 无短路，不显示）。 */
    long epShortCircuited
) {

    /**
     * 诊断行文案；不需要诊断时返回 null（未捕捉 / 已有条目——空态文案由面板还原为默认）。
     */
    @Nullable
    public static String captureDiagnosticsText(@NotNull CaptureDiagnostics d) {
        if (!d.capturing || d.entryCount > 0) return null;
        String head = MpSqlVisualizerBundle.message("log.diag.capturing");
        String enhanced = d.enhancedStatus == null
            ? MpSqlVisualizerBundle.message("log.diag.enhancedOff")
            : MpSqlVisualizerBundle.message("log.diag.enhanced", d.enhancedStatus);
        String console = MpSqlVisualizerBundle.message("log.diag.console", d.consolesAttached, d.linesSeen);
        // T-RA-03：丢弃/短路计数只在非零时挂尾——零值是常态，常态不该给诊断行添噪；
        // 非零则意味着「桥看到过行却没产出」，是排障时最先要看的两条线索。
        List<String> dropped = new ArrayList<>();
        if (d.replayDropped > 0L) {
            dropped.add(MpSqlVisualizerBundle.message("log.diag.replayDropped", d.replayDropped));
        }
        if (d.epShortCircuited > 0L) {
            dropped.add(MpSqlVisualizerBundle.message("log.diag.epShortCircuit", d.epShortCircuited));
        }
        List<String> parts = new ArrayList<>(List.of(head, enhanced, console));
        parts.addAll(dropped);
        String channels = String.join("｜", parts);
        String hintKey = d.waitingForConsole
            ? "log.diag.hint.waiting"
            : (d.linesSeen <= 0L ? "log.diag.hint.noOutput" : "log.diag.hint.noSql");
        return channels + "\n" + MpSqlVisualizerBundle.message(hintKey);
    }
}
