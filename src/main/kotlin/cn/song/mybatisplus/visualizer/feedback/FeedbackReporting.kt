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
 */
internal object FeedbackReporting {

    /** 本插件根包名：错误池堆栈归属匹配锚（手动形态取最近插件错误用）。 */
    private const val PLUGIN_PACKAGE = "cn.song.mybatisplus.visualizer"

    /**
     * 后台提交（任何线程可调）；完成后 EDT 回调 [onResult]。
     * 网络异常折入 [FeedbackApi.SubmitResult.Failure]，不抛出。
     */
    fun submitInBackground(
        submission: FeedbackApi.Submission,
        onResult: (FeedbackApi.SubmitResult) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { FeedbackApi.submit(submission) }
                .getOrElse { FeedbackApi.SubmitResult.Failure(it.message ?: "submit failed") }
            ApplicationManager.getApplication().invokeLater(
                { onResult(result) },
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
            ) { submission ->
                submitInBackground(submission) { result -> notifyResult(project, result) }
            }
            dialog.show()
        }
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

    /** IDE 版本描述（如 `IntelliJ IDEA 2024.3.7.1 Ultimate`）；平台读取失败返回 null。 */
    fun ideVersion(): String? =
        runCatching { ApplicationInfo.getInstance().fullApplicationName }.getOrNull()
}
