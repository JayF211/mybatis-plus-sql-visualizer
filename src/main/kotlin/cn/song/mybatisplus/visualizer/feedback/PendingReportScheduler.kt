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
 */
@Service(Service.Level.APP)
class PendingReportScheduler {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sql-visualizer-feedback-queue").apply { isDaemon = true }
    }

    private var scheduled: ScheduledFuture<*>? = null

    /**
     * 错峰间隔：服务端窗口 3600s / 5 次 = 720s，取 [INTERVAL_SECONDS] 留足余量
     * （同 IP 可能还有别的插件/浏览器提交在共用这个桶）。
     */
    fun enqueue(submissions: List<FeedbackApi.Submission>) {
        if (submissions.isEmpty()) return
        val queue = ArrayDeque(submissions)
        synchronized(this) {
            if (scheduled?.isDone == false) return // 已有队列在跑，同一批不重复排
            scheduled = executor.schedule({ drain(queue) }, INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** 队列剩余条数（0 = 无排队；供测试与诊断）。 */
    internal fun describeQueue(): String = synchronized(this) {
        if (scheduled?.isDone == false) "queued" else "idle"
    }

    /** 应用级实例（测试环境无 service registry 时返回 null，调用方静默跳过）。 */
    companion object {
        /** 错峰间隔（秒）：见 [enqueue] 注释。 */
        internal const val INTERVAL_SECONDS = 12 * 60L

        fun scheduleQuietly(submissions: List<FeedbackApi.Submission>) {
            runCatching {
                ApplicationManager.getApplication().getService(PendingReportScheduler::class.java)
                    ?.enqueue(submissions)
            }
        }
    }

    /** 顺序发出剩余帖子，每条之间隔 [INTERVAL_SECONDS]（成功与否都继续下一条，不重试）。 */
    private fun drain(queue: ArrayDeque<FeedbackApi.Submission>) {
        while (queue.isNotEmpty()) {
            val submission = queue.removeFirst()
            runCatching { FeedbackApi.submit(submission) }
            if (queue.isNotEmpty()) Thread.sleep(TimeUnit.SECONDS.toMillis(INTERVAL_SECONDS))
        }
    }
}