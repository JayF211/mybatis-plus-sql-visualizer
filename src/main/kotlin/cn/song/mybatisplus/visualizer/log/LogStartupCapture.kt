package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目启动自动监听（T-M31.14）：设置「项目启动时自动开始监听」开启时，
 * 项目打开且已有运行中的主控制台时挂载监听（等价自动点击「捕捉」）；
 * 无运行控制台时 [SqlLogManager.startCapture] 守卫跳过——项目真正启动运行时由
 * onConsoleAppeared 自动捕捉。
 *
 * T-M51-04-DEL：删除 ProjectManagerListener 兜底（projectOpened 已 for-removal），
 * 仅保留 postStartupActivity 触发。startCapture() 幂等，onConsoleAppeared 兜底捕捉。
 */
class LogStartupCapture : ProjectActivity {

    override suspend fun execute(project: Project) {
        LOG.info("LogStartupCapture.execute: 触发（project=${project.name}, logAutoCaptureOnStart=${PluginSettings.getInstance().state.logAutoCaptureOnStart}）")

        // 自动启动条件：设置开启（手动停止不影响此设置）
        if (!PluginSettings.getInstance().state.logAutoCaptureOnStart) return
        val manager = project.getService(SqlLogManager::class.java)
        tryStartCapture(manager, project, attempt = 0)
    }

    private fun tryStartCapture(manager: SqlLogManager, project: Project, attempt: Int) {
        LOG.info("LogStartupCapture.tryStartCapture: 第 ${attempt + 1} 次尝试（project=${project.name}）")
        manager.startCapture()
        if (!manager.capturing && attempt < MAX_RETRIES && !project.isDisposed) {
            LOG.info("LogStartupCapture: 未生效，${RETRY_DELAY_MS}ms 后重试 (${attempt + 1}/$MAX_RETRIES)")
            ApplicationManager.getApplication().executeOnPooledThread {
                Thread.sleep(RETRY_DELAY_MS)
                tryStartCapture(manager, project, attempt + 1)
            }
        } else if (manager.capturing) {
            LOG.info("LogStartupCapture: 已成功进入捕捉态")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(LogStartupCapture::class.java)
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 5000L
    }
}
