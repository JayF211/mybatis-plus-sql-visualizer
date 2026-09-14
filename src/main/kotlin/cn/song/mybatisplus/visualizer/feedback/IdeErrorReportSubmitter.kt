package cn.song.mybatisplus.visualizer.feedback

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import java.awt.Component

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
 * T-FeedbackBatch（用户定案「不能被轻易限流」）：平台错误列表可多选，`events` 是数组——
 * events[0] 走主预填，其余作为**批次条目**进入同一对话框（可在窗内逐条取消勾选），
 * 提交时由 [BatchSubmitter] 决定「合并成一条」还是「首发带合并附件 + 其余错峰补发」。
 */
class IdeErrorReportSubmitter : ErrorReportSubmitter() {

    /** 去重判定 seam（测试注入；生产走 ErrorFingerprint 24h 窗口）。 */
    @Volatile
    internal var dedupeGate: (String) -> Boolean = { ErrorFingerprint.seenRecently(it) }

    /** 弹框+提交流程 seam（测试注入捕获；生产 [openDialogAndSubmit]）。 */
    @Volatile
    internal var openReportFlow: (ErrorReportComposer.Prefill, String?, String, List<BatchSubmitter.BatchItem>, Consumer<in SubmittedReportInfo>) -> Unit =
        { prefill, stackText, fingerprint, batch, consumer -> openDialogAndSubmit(prefill, stackText, fingerprint, batch, consumer) }

    /** 批次条目组装 seam（测试注入；生产 [batchItemsOf]）。 */
    @Volatile
    internal var batchItemsProvider: (Array<IdeaLoggingEvent>) -> List<BatchSubmitter.BatchItem> = ::batchItemsOf

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
        val event = events.firstOrNull() ?: return false
        val stackText = event.throwableText
        val throwableClassName = event.throwable?.javaClass?.name
        val fingerprint = ErrorFingerprint.of(throwableClassName, stackText)
        if (dedupeGate(fingerprint)) {
            consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.DUPLICATE))
            return true
        }
        val prefill = ErrorReportComposer.prefill(throwableClassName, event.message, stackText)
        val batchItems = batchItemsProvider(events)
        openReportFlow(prefill, stackText.takeIf { it.isNotBlank() }, fingerprint, batchItems, consumer)
        return true
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

    /** 生产流程：EDT 弹确认框，确认后记指纹 + 后台提交 + consumer 回调。 */
    private fun openDialogAndSubmit(
        prefill: ErrorReportComposer.Prefill,
        stackText: String?,
        fingerprint: String,
        batchItems: List<BatchSubmitter.BatchItem>,
        consumer: Consumer<in SubmittedReportInfo>,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = ErrorReportDialog(
                prefill = prefill,
                stackText = stackText,
                fingerprint = fingerprint,
                ideVersion = FeedbackReporting.ideVersion(),
                pluginVersion = cn.song.mybatisplus.visualizer.update.PluginVersion.current(),
            ) { submission: FeedbackApi.Submission,
                selectedItems: List<BatchSubmitter.BatchItem>,
                onResult: (FeedbackApi.SubmissionOutcome) -> Unit ->
                ErrorFingerprint.record(fingerprint)
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
}
