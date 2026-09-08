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
 * 用户确认后后台提交（[FeedbackReporting.submitInBackground]）→
 * consumer 回调 NEW_ISSUE（带 FR 编号与详情页）/ FAILED。
 */
class IdeErrorReportSubmitter : ErrorReportSubmitter() {

    /** 去重判定 seam（测试注入；生产走 ErrorFingerprint 24h 窗口）。 */
    @Volatile
    internal var dedupeGate: (String) -> Boolean = { ErrorFingerprint.seenRecently(it) }

    /** 弹框+提交流程 seam（测试注入捕获；生产 [openDialogAndSubmit]）。 */
    @Volatile
    internal var openReportFlow: (ErrorReportComposer.Prefill, String?, String, Consumer<in SubmittedReportInfo>) -> Unit =
        { prefill, stackText, fingerprint, consumer -> openDialogAndSubmit(prefill, stackText, fingerprint, consumer) }

    override fun getReportActionText(): String = MpSqlVisualizerBundle.message("error.report.action")

    override fun getPrivacyNoticeText(): String = MpSqlVisualizerBundle.message("error.report.privacy")

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
        openReportFlow(prefill, stackText.takeIf { it.isNotBlank() }, fingerprint, consumer)
        return true
    }

    /** 生产流程：EDT 弹确认框，确认后记指纹 + 后台提交 + consumer 回调。 */
    private fun openDialogAndSubmit(
        prefill: ErrorReportComposer.Prefill,
        stackText: String?,
        fingerprint: String,
        consumer: Consumer<in SubmittedReportInfo>,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = ErrorReportDialog(
                prefill = prefill,
                stackText = stackText,
                fingerprint = fingerprint,
                ideVersion = FeedbackReporting.ideVersion(),
                pluginVersion = cn.song.mybatisplus.visualizer.update.PluginVersion.current(),
            ) { submission ->
                ErrorFingerprint.record(fingerprint)
                FeedbackReporting.submitInBackground(submission) { result ->
                    consumer.consume(reportInfo(result))
                    FeedbackReporting.notifyResult(null, result)
                }
            }
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
