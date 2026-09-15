package cn.song.mybatisplus.visualizer.feedback

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Component
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * errorHandler EP 实现（T-ErrReport-04）：IDE Internal Errors 对话框对本插件错误的
 * 「报告」按钮接官网需求池。
 *
 * 平台按错误归属插件解析本 EP（IdeErrorsDialog.getSubmitter(Throwable, PluginId)，
 * `com.intellij.errorHandler`，dynamic——243 平台 jar javap 实证，见开发计划 §四）；
 * 无需自行过滤事件来源。Freeze 事件已被归属插件（真机截图实证），同通道可达。
 *
 * 流程：指纹 24h 去重（命中直接 DUPLICATE 回调、不弹框）→ EDT 弹报告确认框 →
 * 用户确认后后台提交（[BatchSubmitter] 按条数分流）→
 * consumer 回调 NEW_ISSUE（带 FR 编号与详情页）/ FAILED。
 *
 * T-FeedbackErr：提交进度与失败原因先落回**对话框状态行**（失败留窗可改可重试），
 * 平台 consumer 与通知随后照旧回调——真机上「只有一个 Toast、没有对话框」的现场即由此修掉。
 *
 * T-FeedbackBatch（用户定案「不能被轻易限流」）：events 数组整体进入同一对话框——
 * events[0] 走主预填，全部事件作为批次条目（可在窗内逐条取消勾选），
 * 提交时由 [BatchSubmitter] 决定「合并成一条」还是「首发带合并附件 + 其余错峰补发」。
 *
 * T-FeedbackBatch2（2026-09-15 用户真机「报告全部还是多次弹窗」）：上面「events 是多条」
 * 的前提在 262 平台不成立——IdeErrorsDialog「Report All」是逐错误簇循环（reportAll →
 * access$reportMessage，IdeErrorsDialog$reportAll$reported$1 字节码实证），每次
 * reportMessage 固定构造**长度 1** 的事件数组（anewarray 1 + cluster.getFirst()）调一次
 * submit。N 个错误 = N 次独立 submit = N 个弹窗，批次分节每次只有 1 条。修法：
 * submit() 不再直接弹框，而是把去抖窗口内的调用**累积合并成一波**（[PendingCall] 队列 +
 * 500ms debounce、自首条起 3s 强制兜底），flush 时合并全部新鲜事件开**一个**对话框，
 * consumer 合成复合回调（本波每个簇都被平台正确标记已提交）。单次调用路径参数与
 * 改造前逐项一致，仅多一次去抖延迟。
 */
class IdeErrorReportSubmitter : ErrorReportSubmitter() {

    /** 去重判定 seam（测试注入；生产走 ErrorFingerprint 24h 窗口）。 */
    @Volatile
    internal var dedupeGate: (String) -> Boolean = { ErrorFingerprint.seenRecently(it) }

    /** 弹框+提交流程 seam（测试注入捕获；生产 [openDialogAndSubmit]）。 */
    @Volatile
    internal var openReportFlow: (ErrorReportComposer.Prefill, String?, List<String>, List<BatchSubmitter.BatchItem>, Consumer<in SubmittedReportInfo>) -> Unit =
        { prefill, stackText, fingerprints, batch, consumer -> openDialogAndSubmit(prefill, stackText, fingerprints, batch, consumer) }

    /** 批次条目组装 seam（测试注入；生产 [batchItemsOf]）。 */
    @Volatile
    internal var batchItemsProvider: (Array<IdeaLoggingEvent>) -> List<BatchSubmitter.BatchItem> = ::batchItemsOf

    /**
     * 去抖调度 seam（测试注入假调度器；null = 生产全局调度器，首次入队时才解析——
     * 保持构造零平台依赖，headless 测试直接 new 不触 [AppExecutorUtil]）。
     */
    @Volatile
    internal var scheduler: ScheduledExecutorService? = null

    /** 时钟 seam（3s 强制兜底的等待测量；测试注入固定时间）。 */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** 同一波待合并调用：新鲜事件 + 该次调用的平台 consumer（flush 后统一回调）。 */
    internal class PendingCall(val events: List<IdeaLoggingEvent>, val consumer: Consumer<in SubmittedReportInfo>)

    private val pendingCalls = ArrayDeque<PendingCall>()
    private val flushLock = Any()

    /** 已排定的 flush 任务（每次入队取消重排 = debounce 语义；flush 执行时置 null）。 */
    private var flushFuture: ScheduledFuture<*>? = null

    /** 首个待处理条目入队时刻（3s 强制兜底的起点）。 */
    private var firstPendingAt = 0L

    // T-ReportL10n-02：这两处文案渲染在平台 Internal Errors 界面里，恒跟随 IDE 显示
    // 语言（messageForIde）不经插件语言设置——真机二次反馈：上轮只改 AUTO 分支落点，
    // 但插件语言默认 zh（非 AUTO），菜单项照样中文与周围英文界面割裂
    override fun getReportActionText(): String = MpSqlVisualizerBundle.messageForIde("error.report.action")

    override fun getPrivacyNoticeText(): String = MpSqlVisualizerBundle.messageForIde("error.report.privacy")

    override fun submit(
        events: Array<IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        if (events.isEmpty()) return false
        // 逐事件指纹去重（T-ErrReport-02 语义不变）：命中的条目剔出本波，整条全命中
        // 才直接 DUPLICATE 回调——262 平台每次调用只有 1 条，行为与改造前等价
        val fresh = events.filter { event ->
            !dedupeGate(ErrorFingerprint.of(event.throwable?.javaClass?.name, event.throwableText))
        }
        if (fresh.isEmpty()) {
            consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.DUPLICATE))
            return true
        }
        enqueue(PendingCall(fresh, consumer))
        return true
    }

    /** 入队并重排去抖 flush（debounce：窗口自**最后一次**调用起算；超 [MAX_WAIT_MS] 立即触发）。 */
    private fun enqueue(call: PendingCall) {
        var delayMs = DEBOUNCE_MS
        val scheduleExecutor = scheduler ?: AppExecutorUtil.getAppScheduledExecutorService()
        synchronized(flushLock) {
            if (pendingCalls.isEmpty()) firstPendingAt = clock()
            pendingCalls.addLast(call)
            flushFuture?.cancel(false)
            if (clock() - firstPendingAt >= MAX_WAIT_MS) delayMs = 0
            flushFuture = scheduleExecutor.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * 合并落框（锁内整体 drain，锁外组装）：全部新鲜事件合成一次 [openReportFlow]——
     * 首事件做主预填，全部事件做批次条目（T-FeedbackBatch 全含契约），consumer 收拢为
     * 复合回调（本波每个平台簇收到同一结果信息）。队列为空（重复触发/已落框）直接返回。
     */
    internal fun flush() {
        val batch: List<PendingCall> = synchronized(flushLock) {
            flushFuture = null
            val drained = pendingCalls.toList()
            pendingCalls.clear()
            drained
        }
        if (batch.isEmpty()) return
        val events = batch.flatMap { it.events }
        val head = events.first()
        val headStack = head.throwableText
        val fingerprints = events.map { ErrorFingerprint.of(it.throwable?.javaClass?.name, it.throwableText) }
        val prefill = ErrorReportComposer.prefill(head.throwable?.javaClass?.name, head.message, headStack)
        val batchItems = batchItemsProvider(events.toTypedArray())
        val composite = Consumer<SubmittedReportInfo> { info ->
            batch.forEach { call -> call.consumer.consume(info) }
        }
        openReportFlow(prefill, headStack.takeIf { it.isNotBlank() }, fingerprints, batchItems, composite)
    }

    /**
     * 用户勾选的错误事件 → 批次条目（纯函数，T-FeedbackBatch）：每个事件一条，
     * 标题=异常类全名、消息/堆栈取事件原值；无异常对象但堆栈非空同样收录。
     */
    internal fun batchItemsOf(events: Array<IdeaLoggingEvent>): List<BatchSubmitter.BatchItem> =
        events.mapNotNull { event ->
            val stack = event.throwableText
            val className = event.throwable?.javaClass?.name
            if (className == null && stack.isNullOrBlank()) return@mapNotNull null
            BatchSubmitter.BatchItem(
                title = className ?: "PluginError",
                message = event.message,
                stackTraceText = stack,
            )
        }

    /** 生产流程：EDT 弹确认框，确认后记指纹（全部合并条目）+ 后台提交 + consumer 回调。 */
    private fun openDialogAndSubmit(
        prefill: ErrorReportComposer.Prefill,
        stackText: String?,
        fingerprints: List<String>,
        batchItems: List<BatchSubmitter.BatchItem>,
        consumer: Consumer<in SubmittedReportInfo>,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = ErrorReportDialog(
                prefill = prefill,
                stackText = stackText,
                fingerprint = fingerprints.firstOrNull(),
                ideVersion = FeedbackReporting.ideVersion(),
                pluginVersion = cn.song.mybatisplus.visualizer.update.PluginVersion.current(),
            ) { submission: FeedbackApi.Submission,
                selectedItems: List<BatchSubmitter.BatchItem>,
                onResult: (FeedbackApi.SubmissionOutcome) -> Unit ->
                // T-FeedbackBatch2：合并波的每个条目都记指纹（原实现只记首条，其余条目的
                // 24h 去重从未生效，重复出现会再次弹框）
                fingerprints.forEach(ErrorFingerprint::record)
                // T-FeedbackBatch：条数决定「合并成一条」还是「首发 + 错峰补发」
                BatchSubmitter.submit(submission, selectedItems) { outcome ->
                    onResult(outcome)
                    consumer.consume(reportInfo(outcome.result))
                    FeedbackReporting.notifyOutcome(null, outcome)
                }
            }
            dialog.batchItems = batchItems
            dialog.show()
        }
    }

    /** 提交结果 → 平台报告信息（纯函数，internal 供测试）。 */
    internal fun reportInfo(result: FeedbackApi.SubmitResult): SubmittedReportInfo = when (result) {
        is FeedbackApi.SubmitResult.Success -> SubmittedReportInfo(
            FeedbackApi.detailUrl(result.postNo),
            result.postNo,
            SubmittedReportInfo.SubmissionStatus.NEW_ISSUE,
        )
        is FeedbackApi.SubmitResult.Failure -> SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED)
    }

    companion object {
        /** debounce 窗口：自最后一次 submit 起等这么久再合并落框（平台逐簇循环间隔远小于此）。 */
        internal const val DEBOUNCE_MS = 500L

        /** 强制落框兜底：自首个待处理条目起超过此时长不再顺延（防逐簇间隔波动致弹窗无限推迟）。 */
        internal const val MAX_WAIT_MS = 3_000L
    }
}
