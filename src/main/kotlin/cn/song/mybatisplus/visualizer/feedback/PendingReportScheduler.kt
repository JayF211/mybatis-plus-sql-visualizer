package cn.song.mybatisplus.visualizer.feedback

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 待发报告调度器（T-FeedbackBatch，2026-09-14 用户定案「不能被轻易限流」）。
 *
 * 背景：需求池对 `POST /feedback/posts` 的限流是 **5 次/小时/IP**（服务端恒定规则，插件左右不了）。
 * 用户在 IDE 错误列表里一次勾选十几条时，逐条提交必然撞 429——本条链路负责在**用户确认之后**
 * 把超出一轮额度的部分排队，按 12 分钟一发的节奏（1 小时 5 发）错峰送出，队列放内存不落盘。
 *
 * 语义约定（避免「静默替用户发东西」）：
 * - 入队发生在用户点击提交之后，内容已在该对话框里预览确认过；
 * - 首发成功才排后续（首发失败＝额度或网络有问题，继续排只会连环失败）；
 * - 排队不阻塞窗口，关窗仍会照发；应用退出即丢弃（不落盘、不重启后续发）。
 * - T-PerfSweep2-05：发送期间新入队的批次并入队尾顺延补发（此前被静默丢弃）——内容同样
 *   已在对话框里确认过，只是排队位置靠后，红线不变。
 *
 * 调度形态（T-PerfSweep2-05）：单拍链式——每拍发一条，队列非空再 schedule 下一拍。
 * 此前是 drain 循环内 `Thread.sleep(720s)` 逐条间隔：占住唯一调度线程 12 分钟×N，
 * 且 drain 期间 [enqueue] 被静默拒收。链式化后线程在两拍之间归还池，闲时零唤醒不变。
 */
@Service(Service.Level.APP)
class PendingReportScheduler {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sql-visualizer-feedback-queue").apply { isDaemon = true }
    }

    /** 待发队列：[enqueue] 并入、[tick] 逐条消费。 */
    private val pending = ArrayDeque<FeedbackApi.Submission>()

    /**
     * 在途定时任务，唯一调度权凭证：`scheduled != null && !isDone` ⟺ 有（且仅有一个）
     * 在途或待触发的拍拥有队列。[enqueue] 与 [tick] 的重排判定都认它，双链不可能并存。
     */
    private var scheduled: ScheduledFuture<*>? = null

    /**
     * 入队（[delaySeconds] 默认见 [INTERVAL_SECONDS]；测试传 0 走真实单线程池即时消费）。
     */
    fun enqueue(submissions: List<FeedbackApi.Submission>, delaySeconds: Long = INTERVAL_SECONDS) {
        if (submissions.isEmpty()) return
        synchronized(this) {
            pending.addAll(submissions)
            if (scheduled?.isDone == false) return // 在途拍拥有队列，新条目由它接管
            scheduled = executor.schedule({ tick(delaySeconds) }, delaySeconds, TimeUnit.SECONDS)
        }
    }

    /**
     * 单拍：取队首发送（成功与否都继续，不重试）→ 队列非空必续链。
     * 收尾续链与 [enqueue] 同锁互斥：发送期间 enqueue 若已另排新拍（旧 future 未 done），
     * 这里 cancel 掉再排自己的——保证任意时刻至多一条链（丢唤醒修复：T-PerfSweep2-05
     * 自测实证，误判「在途拍接管」会让后续批次无人消费）。
     */
    internal fun tick(delaySeconds: Long = INTERVAL_SECONDS) {
        val next = synchronized(this) { pending.removeFirstOrNull() } ?: run {
            synchronized(this) { if (pending.isEmpty()) scheduled = null }
            return
        }
        runCatching { FeedbackApi.submit(next) }
        synchronized(this) {
            val chained = if (pending.isNotEmpty()) {
                executor.schedule({ tick(delaySeconds) }, delaySeconds, TimeUnit.SECONDS)
            } else {
                null
            }
            scheduled?.cancel(false) // 当前拍的 future 必已 done（no-op）；enqueue 抢排的未触发拍被取消
            scheduled = chained
        }
    }

    /** 队列状态（queued = 有在途拍；idle = 无排队；供测试与诊断）。 */
    internal fun describeQueue(): String = synchronized(this) {
        if (scheduled?.isDone == false) "queued" else "idle"
    }

    /** 应用级实例（测试环境无 service registry 时返回 null，调用方静默跳过）。 */
    companion object {
        /** 错峰间隔（秒）：见类 KDoc。 */
        internal const val INTERVAL_SECONDS = 12 * 60L

        fun scheduleQuietly(submissions: List<FeedbackApi.Submission>) {
            runCatching {
                ApplicationManager.getApplication().getService(PendingReportScheduler::class.java)
                    ?.enqueue(submissions)
            }
        }
    }
}
