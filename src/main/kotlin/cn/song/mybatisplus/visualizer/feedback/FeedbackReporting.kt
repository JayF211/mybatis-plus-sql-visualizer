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

    /** 手动入口（Help 菜单 / 设置页按钮）：打开手动形态报告框，提交后结果走通知。 */
    fun openManualDialog(project: Project? = null) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = ErrorReportDialog(
                prefill = null,
                stackText = null,
                fingerprint = null,
                ideVersion = ideVersion(),
                pluginVersion = PluginVersion.current(),
            ) { submission ->
                submitInBackground(submission) { result -> notifyResult(project, result) }
            }
            dialog.show()
        }
    }

    /** IDE 版本描述（如 `IntelliJ IDEA 2024.3.7.1 Ultimate`）；平台读取失败返回 null。 */
    fun ideVersion(): String? =
        runCatching { ApplicationInfo.getInstance().fullApplicationName }.getOrNull()
}
