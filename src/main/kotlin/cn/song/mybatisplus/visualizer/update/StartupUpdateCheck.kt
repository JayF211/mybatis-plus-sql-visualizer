package cn.song.mybatisplus.visualizer.update

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.ui.RestartDialogImpl

/**
 * 启动自动检查（M28）：`com.intellij.postStartupActivity`（每项目触发，应用级单次守卫）——
 *
 * 开关开（[PluginSettings.state.autoCheckUpdate]，用户拍板默认开）时**静默**后台检查：
 * 网络失败/清单异常/已是最新一律不打扰，仅发现新版本弹通知——`[立即更新]` 后台下载安装
 * （状态栏进度），成功后通知「重启 IDE 生效」（`[重启 IDE]` 动作 →
 * [RestartDialogImpl.restartWithConfirmation]）；`[稍后]` 关闭通知。
 *
 * 与设置页手动检查共用 [PluginUpdateRunner]（check/downloadAndInstall 同一编排）。
 */
class StartupUpdateCheck : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!shouldStartupCheck(PluginSettings.getInstance().state.autoCheckUpdate, alreadyChecked)) return
        alreadyChecked = true
        ApplicationManager.getApplication().invokeLater {
            checkAndNotify(project)
        }
    }

    /** 静默检查：仅 Available 弹通知（UpToDate/Failure 静默——启动不打扰原则）。 */
    private fun checkAndNotify(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.check"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = PluginUpdateRunner.check(PluginVersion.current())
                if (result !is PluginUpdateRunner.CheckResult.Available) return
                ApplicationManager.getApplication().invokeLater {
                    showAvailable(project, result.manifest)
                }
            }
        })
    }

    /** 「发现新版本」通知：标题带插件名（T-M28.3 用户要求）；内容只保留变更日志标题行；立即更新 / 稍后。 */
    private fun showAvailable(project: Project, manifest: UpdateManifest) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                MpSqlVisualizerBundle.message(
                    "update.notification.title",
                    MpSqlVisualizerBundle.message("plugin.name"),
                    manifest.version,
                ),
                notificationContent(manifest.changeNotes),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple(MpSqlVisualizerBundle.message("update.notification.installNow")) {
                installNow(project, manifest)
            })
            .addAction(
                NotificationAction.createSimpleExpiring(MpSqlVisualizerBundle.message("update.notification.later")) { }
            )
        runCatching { notification.notify(project) }
    }

    /** 后台下载安装（状态栏进度）；成功 → 重启通知，失败 → 错误通知。 */
    private fun installNow(project: Project, manifest: UpdateManifest) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.download"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = PluginUpdateRunner.downloadAndInstall(manifest, indicator)
                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        PluginUpdateRunner.InstallResult.Installed -> showInstalled(project)
                        is PluginUpdateRunner.InstallResult.Failed -> showInstallFailed(project, result.message)
                    }
                }
            }
        })
    }

    /** 「更新完成，重启生效」通知：重启 IDE 动作（IDE 原生确认框，可选稍后）。 */
    private fun showInstalled(project: Project) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                MpSqlVisualizerBundle.message("update.notification.installed.title"),
                MpSqlVisualizerBundle.message("update.notification.installed.content"),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple(MpSqlVisualizerBundle.message("update.notification.restart")) {
                RestartDialogImpl.restartWithConfirmation()
            })
        runCatching { notification.notify(project) }
    }

    private fun showInstallFailed(project: Project, message: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                MpSqlVisualizerBundle.message("update.notification.failed.title"),
                MpSqlVisualizerBundle.message("settings.update.installFailed", message),
                NotificationType.ERROR,
            )
        runCatching { notification.notify(project) }
    }

    companion object {
        const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.updates"

        /** 应用级单次守卫（postStartupActivity 每项目触发——一个会话只检查一次）。 */
        @Volatile
        private var alreadyChecked = false

        /** 启动检查决策（纯函数，单测覆盖）：开关关或本会话已检查 → 跳过。 */
        internal fun shouldStartupCheck(autoCheck: Boolean, alreadyChecked: Boolean): Boolean =
            autoCheck && !alreadyChecked

        /**
         * 通知内容：只保留变更日志**标题行**（首行，超长截断加省略号）——用户要求
         * 「更新内容只需要保留标题即可」，全文展示留给设置页变更日志区；无标题用默认文案。
         * 纯函数（单测直接覆盖）。
         */
        internal fun notificationContent(changeNotes: String?): String {
            val firstLine = changeNotes?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            if (firstLine.isEmpty()) {
                return MpSqlVisualizerBundle.message("update.notification.content")
            }
            return if (firstLine.length > maxTitleLength) {
                firstLine.take(maxTitleLength) + "…"
            } else {
                firstLine
            }
        }

        private val maxTitleLength = 100
    }
}
