package cn.song.mybatisplus.visualizer.update

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationListener
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
        // T-M28.4：内容内 Gitee 链接点击跳转（URL_OPENING_LISTENER 打开浏览器）
        notification.setListener(NotificationListener.URL_OPENING_LISTENER)
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
         * 通知内容：**只显示最新版**变更日志、**每个功能点一行**（用户要求：带换行符，如
         * 「XML 动态 SQL 预览（M24）\n注解 SQL 预览（M25）」）——取 changeNotes 首行
         * （最新版），剥离版本号前缀（`v2026.8.15：`），按分号（`；`/`;`）拆分为功能点，
         * 最多显示 [maxTitleVersions] 个、每个超长截断加省略号；HTML 转义后以
         * **`<br>\n` 连接**——`<br>` 保证通知 HTML 渲染分行、`\n` 保证文本形态含真实
         * 换行符（双满足）；末尾附 Gitee 发布页链接；无标题用默认文案。纯函数（单测覆盖）。
         */
        internal fun notificationContent(changeNotes: String?): String {
            val firstLine = changeNotes?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            val items = if (firstLine.isEmpty()) {
                emptyList()
            } else {
                stripVersionPrefix(firstLine).split(Regex("[；;]"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            val body = if (items.isEmpty()) {
                MpSqlVisualizerBundle.message("update.notification.content")
            } else {
                items.take(maxTitleVersions).joinToString("<br>\n") { item ->
                    val title = if (item.length > maxTitleLength) item.take(maxTitleLength) + "…" else item
                    title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                }
            }
            return body + "<br>\n<a href=\"" + UpdateSource.GITEE_RELEASES_URL + "\">" +
                MpSqlVisualizerBundle.message("update.notification.viewGitee") + "</a>"
        }

        /** 剥离行首版本号前缀（`v2026.8.15：` / `2026.8.15:`，容忍有无 v 与中英文冒号）。 */
        internal fun stripVersionPrefix(line: String): String =
            VERSION_PREFIX_REGEX.replace(line, "")

        private val VERSION_PREFIX_REGEX = Regex("""^v?\d{4}(\.\d{1,2}){1,3}\s*[:：]\s*""")

        private val maxTitleVersions = 8
        private val maxTitleLength = 60
    }
}
