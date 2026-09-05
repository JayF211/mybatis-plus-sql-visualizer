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
 * 启动自动检查（M28 → 内测通道改造）：`com.intellij.postStartupActivity`（每项目触发，应用级单次守卫）——
 *
 * 开关开（[PluginSettings.state.autoCheckUpdate]，默认关——稳定版由 Marketplace 推送）时**静默**
 * 后台检查 Gitee 更新通道：网络失败/清单异常/已是最新/已忽略版本一律不打扰，
 * 仅发现未忽略的可用更新弹通知——`[立即安装]` 后台下载安装（状态栏进度），成功后通知
 * 「重启 IDE 生效」（`[重启 IDE]` 动作 → [RestartDialogImpl.restartWithConfirmation]）；
 * `[忽略此版本]` 写入配置（仅抑制自动通知，手动检查不受影响）；`[稍后]` 关闭通知。
 *
 * 与设置页手动检查共用 [PluginUpdateRunner]（check/downloadAndInstall 同一编排）。
 */
class StartupUpdateCheck : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = PluginSettings.getInstance()
        if (!shouldStartupCheck(settings.state.autoCheckUpdate, alreadyChecked)) return
        alreadyChecked = true
        ignoredVersions = settings.state.ignoredUpdateVersions
        ApplicationManager.getApplication().invokeLater {
            checkAndNotify(project)
        }
    }

    /** 静默检查：仅 Available 且未被忽略时弹通知（UpToDate/Failure/忽略 → 静默）。 */
    private fun checkAndNotify(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.check"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = PluginUpdateRunner.check(PluginVersion.current())
                if (result !is PluginUpdateRunner.CheckResult.Available) return
                // 忽略守卫：该版本已被用户忽略 → 静默（仅抑制自动通知，手动检查不受影响）
                if (isVersionIgnored(result.manifest.version, ignoredVersions)) return
                ApplicationManager.getApplication().invokeLater {
                    showAvailable(project, result.manifest)
                }
            }
        })
    }

    /** 「发现可用更新」通知：标题带插件名（不含版本号，版本号放正文第一行）；立即安装 / 忽略此版本 / 稍后。 */
    @Suppress("DEPRECATION") // T-M52：setListener 在 243/262 标 deprecated，无替代 API（4 参数构造器 262 也废弃）
    private fun showAvailable(project: Project, manifest: UpdateManifest) {
        val pluginName = MpSqlVisualizerBundle.message("plugin.name")
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                // 标题只带插件名，不含版本号（版本号移到正文第一行）
                MpSqlVisualizerBundle.message("update.notification.title", pluginName),
                notificationContent(manifest),
                NotificationType.INFORMATION,
            )
        notification.addAction(NotificationAction.createSimple(MpSqlVisualizerBundle.message("update.notification.installNow")) {
            installNow(project, manifest)
        })
        notification.addAction(NotificationAction.createSimple(MpSqlVisualizerBundle.message("update.notification.ignore")) {
            // 忽略此版本：写入配置，仅抑制自动通知，手动检查不受影响
            PluginSettings.getInstance().loadState(
                PluginSettings.getInstance().state.copy(
                    ignoredUpdateVersions = ignoreVersion(manifest.version, ignoredVersions)
                )
            )
            notification.expire()
        })
        notification.addAction(
            NotificationAction.createSimpleExpiring(MpSqlVisualizerBundle.message("update.notification.later")) { }
        )
        // T-M28.4：内容内 Gitee 链接点击跳转（URL_OPENING_LISTENER 打开浏览器）
        // setListener 在 243 标 deprecated，262 verifyPlugin 报 deprecated；无替代 API，Suppress 保留
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
                // T-Perf-20：ERROR 红色 balloon 降 WARNING（全项目唯一 ERROR 通知清零；
                // 更新页 Failed 态渲染仍承载失败详情，用户主动重试入口不变）
                NotificationType.WARNING,
            )
        runCatching { notification.notify(project) }
    }

    companion object {
        const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.updates"

        /** 应用级单次守卫（postStartupActivity 每项目触发——一个会话只检查一次）。 */
        @Volatile
        private var alreadyChecked = false

        /** 启动时读取的忽略版本列表快照（避免检查过程中配置被修改导致不一致）。 */
        @Volatile
        private var ignoredVersions: String = ""

        /** 启动检查决策（纯函数，单测覆盖）：开关关或本会话已检查 → 跳过。 */
        internal fun shouldStartupCheck(autoCheck: Boolean, alreadyChecked: Boolean): Boolean =
            autoCheck && !alreadyChecked

        /**
         * 通知内容：第一行版本号（`版本 2026.8.22-beta.1（Gitee 通道）`），
         * 随后变更日志有序列表、末尾 Gitee 发布页链接。纯函数（单测直接覆盖）。
         *
         * @param manifest 可用更新的清单（版本号 + 变更日志）。
         */
        internal fun notificationContent(manifest: UpdateManifest): String {
            val versionLine = MpSqlVisualizerBundle.message("update.notification.versionLine", manifest.version)
            val titles = manifest.changeNotes?.lineSequence()
                ?.map { stripVersionPrefix(it.trim()) }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()
            val changeNotesHtml = if (titles.isEmpty()) {
                ""
            } else {
                "<ol>" + titles.take(maxTitleVersions).joinToString("") { item ->
                    val title = if (item.length > maxTitleLength) item.take(maxTitleLength) + "…" else item
                    "<li>" + title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</li>"
                } + "</ol>"
            }
            val giteeLink = "<br>\n<a href=\"" + UpdateSource.GITEE_RELEASES_URL + "\">" +
                MpSqlVisualizerBundle.message("update.notification.viewGitee") + "</a>"
            return versionLine + "<br>" + changeNotesHtml + giteeLink
        }

        /** 剥离行首版本号前缀（`v2026.8.15：` / `2026.8.15:`，容忍有无 v 与中英文冒号）。 */
        internal fun stripVersionPrefix(line: String): String =
            VERSION_PREFIX_REGEX.replace(line, "")

        private val VERSION_PREFIX_REGEX = Regex("""^v?\d{4}(\.\d{1,2}){1,3}\s*[:：]\s*""")

        private val maxTitleVersions = 8
        private val maxTitleLength = 60
    }
}
