package cn.song.mybatisplus.visualizer.feedback

import cn.song.mybatisplus.visualizer.update.PluginVersion
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * 报告提交编排（T-ErrReport-04/05）：错误通道（IdeErrorReportSubmitter）与手动入口
 * （Help 菜单 / 设置页按钮）共用的后台提交 + 结果通知 + 手动对话框打开。
 *
 * 网络一律后台线程；回调 `invokeLater` 显式 `ModalityState.any()`——报告对话框/设置页
 * 模态存续期挂起默认 modality 的回调（设置页反馈红线，真机实证）。
 *
 * T-FeedbackErr（2026-09-14 真机）：提交结果不再只有 toast——对话框保持打开并内联展示
 * 「提交中 / 失败原因（服务端文案）/ 重试按钮」，失败时用户能当场改内容再试；
 * 附件上传失败降级为「不带附件提交」并另行提示，不再连坐整帖。
 */
internal object FeedbackReporting {

    /** 本插件根包名：错误池堆栈归属匹配锚（手动形态取最近插件错误用）。 */
    private const val PLUGIN_PACKAGE = "cn.song.mybatisplus.visualizer"

    /**
     * 后台提交（任何线程可调）；完成后 EDT 回调 [onResult]。
     * 网络异常折入 [FeedbackApi.SubmitResult.Failure]，不抛出。
     *
     * T-FeedbackErr：回调升格为 [FeedbackApi.SubmissionOutcome]（结果 + 附件降级提示），
     * 并新增 [onProgress]——提交进度由调用方决定怎么显示（对话框内联状态行），
     * 真机教训是「提交中/提交失败」全靠 toast，用户看不到任何可操作的东西。
     */
    fun submitInBackground(
        submission: FeedbackApi.Submission,
        onProgress: (Boolean) -> Unit = {},
        onResult: (FeedbackApi.SubmissionOutcome) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching { FeedbackApi.submit(submission) }
                .getOrElse { FeedbackApi.SubmissionOutcome(FeedbackApi.SubmitResult.Failure(it.message ?: "submit failed")) }
            ApplicationManager.getApplication().invokeLater(
                {
                    onProgress(false)
                    onResult(outcome)
                },
                ModalityState.any(),
            )
        }
    }

    /** 提交结果 toast：成功带 FR 编号与「查看详情」跳转，失败透出原因。 */
    fun notifyResult(project: Project?, result: FeedbackApi.SubmitResult) {
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("cn.song.mybatisplus.visualizer.feedback")
        when (result) {
            is FeedbackApi.SubmitResult.Success -> group.createNotification(
                MpSqlVisualizerBundle.message("feedback.notify.success.title"),
                MpSqlVisualizerBundle.message("feedback.notify.success.content", result.postNo),
                NotificationType.INFORMATION,
            ).addAction(
                object : com.intellij.openapi.actionSystem.AnAction(
                    MpSqlVisualizerBundle.message("feedback.notify.view"),
                ) {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        cn.song.mybatisplus.visualizer.util.SafeBrowser.browse(FeedbackApi.detailUrl(result.postNo))
                    }
                }
            ).notify(project)

            is FeedbackApi.SubmitResult.Failure -> group.createNotification(
                MpSqlVisualizerBundle.message("feedback.notify.failed.title"),
                result.message,
                NotificationType.ERROR,
            ).notify(project)
        }
    }

    /** 附件降级提示 toast（T-FeedbackErr）：帖子已发出、但堆栈附件没带上。 */
    fun notifyAttachmentWarning(project: Project?, warning: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("cn.song.mybatisplus.visualizer.feedback")
            .createNotification(
                MpSqlVisualizerBundle.message("feedback.notify.attachmentSkipped.title"),
                warning,
                NotificationType.WARNING,
            ).notify(project)
    }

    /** 手动入口（Help 菜单 / 设置页按钮）：打开手动形态报告框，提交后结果走通知。
     *  堆栈区可编辑（粘贴）+「从 IDE 错误池导入」候选（[pluginErrorPoolStacks]，T-FeedbackDlg3）。 */
    fun openManualDialog(project: Project? = null) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = ErrorReportDialog(
                prefill = null,
                stackText = null,
                fingerprint = null,
                ideVersion = ideVersion(),
                pluginVersion = PluginVersion.current(),
                stackPoolProvider = { pluginErrorPoolStacks() },
            ) { submission: FeedbackApi.Submission,
                _: List<BatchSubmitter.BatchItem>,
                onResult: (FeedbackApi.SubmissionOutcome) -> Unit ->
                submitInBackground(submission) { result -> onResult(result) }
            }
            dialog.show()
        }
    }

    /** 报告结果通知（对话框内联状态行的共用呈现，T-FeedbackErr）：成功/失败都落到项目通知组。 */
    fun notifyOutcome(project: Project?, outcome: FeedbackApi.SubmissionOutcome) {
        outcome.warning?.let { notifyAttachmentWarning(project, it) }
        notifyResult(project, outcome.result)
    }

    /**
     * IDE 错误池中本插件错误堆栈候选（T-FeedbackDlg3，手动形态「导入」菜单数据源）：
     * 与 Internal Errors 对话框同一错误列表（243 app-client.jar / 262 ide.impl.jar 均 public，
     * 签名一致，直连编译期依赖），按本插件包名过滤（243 无 belongsToPlugin，统一文本匹配），
     * 最近在前、按堆栈去重、至多 10 条。任何异常折空列表（菜单显示「无」说明项）。
     */
    fun pluginErrorPoolStacks(): List<String> = runCatching {
        com.intellij.diagnostic.MessagePool.getInstance()
            // (includeReadMessages, includeSubmittedMessages)：已读/已报告过的也保留——
            // 用户点「报告到需求池」通常就是来报刚发生过的错
            .getFatalErrors(true, true)
            .asReversed()
            .filter { message -> isPluginError(message.throwableText, message.message) }
            .mapNotNull { message -> message.throwableText?.takeIf { it.isNotBlank() } }
            .distinct()
            .take(10)
    }.getOrDefault(emptyList())

    /** 错误池条目归属判定（纯函数，T-FeedbackDlg2-02）：堆栈全文或消息含本插件包名即算本插件错误。 */
    internal fun isPluginError(throwableText: String?, message: String?): Boolean =
        throwableText?.contains(PLUGIN_PACKAGE) == true || message?.contains(PLUGIN_PACKAGE) == true

    /** 报告时间戳（批量组装与单条附件共用同一格式）。 */
    fun nowStamp(): String = java.time.ZonedDateTime.now().format(
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"),
    )

    /** IDE 版本描述（如 `IntelliJ IDEA 2024.3.7.1 Ultimate`）；平台读取失败返回 null。 */
    fun ideVersion(): String? =
        runCatching { ApplicationInfo.getInstance().fullApplicationName }.getOrNull()
}
