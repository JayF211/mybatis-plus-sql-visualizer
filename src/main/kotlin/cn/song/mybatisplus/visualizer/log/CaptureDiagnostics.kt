package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * T-CaptureTruth T-CT-03：捕获链路零产出诊断（SQL 日志面板空态第二行）。
 *
 * 立项动因：捕获零产出时面板只给一个空列表，用户无从判断卡在哪一环——本次现场（沙盒里
 * 「增强已开启但未授权 → 24h 冷却 → 不挂载」+「应用控制台本就没有 SQL 日志」两条通道同时归零）
 * 就是被这条沉默拖了整整一轮排障。诊断行把两条通道的状态摆到台面上：
 *  - 增强通道：复用 [cn.song.mybatisplus.visualizer.log.agent.AgentManager.statusSummary] 的五态文案
 *    （未授权/冷却中/等待进程/已挂载/已回落），未开启时显示「未启用」；
 *  - 控制台通道：已挂载控制台数 + 已扫描行数（[SqlLogConsoleBridge.consoleLinesSeen]）；
 *  - 提示语按「等待控制台 / 控制台无输出 / 有输出但没匹配到 SQL」三档给出可执行的下一步。
 *
 * 纯函数（零平台依赖）便于全分支单测；面板只负责取数（[SqlLogPanel.syncDiagnostics]）。
 */
internal data class CaptureDiagnostics(
    val capturing: Boolean,
    val waitingForConsole: Boolean,
    val entryCount: Int,
    val consolesAttached: Int,
    val linesSeen: Long,
    /** 增强通道状态文案（null = 增强未开启）。 */
    val enhancedStatus: String?,
)

/**
 * 诊断行文案；不需要诊断时返回 null（未捕捉 / 已有条目——空态文案由面板还原为默认）。
 */
internal fun captureDiagnosticsText(d: CaptureDiagnostics): String? {
    if (!d.capturing || d.entryCount > 0) return null
    val head = MpSqlVisualizerBundle.message("log.diag.capturing")
    val enhanced = if (d.enhancedStatus == null) {
        MpSqlVisualizerBundle.message("log.diag.enhancedOff")
    } else {
        MpSqlVisualizerBundle.message("log.diag.enhanced", d.enhancedStatus)
    }
    val console = MpSqlVisualizerBundle.message("log.diag.console", d.consolesAttached, d.linesSeen)
    val hintKey = when {
        d.waitingForConsole -> "log.diag.hint.waiting"
        d.linesSeen <= 0L -> "log.diag.hint.noOutput"
        else -> "log.diag.hint.noSql"
    }
    return "$head｜$enhanced｜$console\n${MpSqlVisualizerBundle.message(hintKey)}"
}
