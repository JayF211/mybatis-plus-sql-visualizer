package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener

/**
 * 项目启动自动监听的可靠触发机制（T-M31.14 兜底）。
 *
 * 经实证：postStartupActivity 在 262 平台不可靠（可能因执行类未加载致 bridge.attach 静默失败），
 * 改用 messageBus 注册 ProjectManagerListener（EDT 上触发，时机更晚——execution API 类已加载完毕）。
 *
 * 不使用 @Service(APP) 注册——262 平台 @Service(APP) 默认懒加载，无代码访问则不实例化，listener 永不注册。
 * 改为由 [LogStartupCapture] 在 postStartupActivity 触发后注册（平台保证调用）。
 *
 * 与 [LogStartupCapture]（postStartupActivity）双保险——startCapture() 幂等（capturing 守卫），
 * 双触发不重复挂载；项目关闭（isDisposed）时停止重试。
 * 无运行中的主控制台时 startCapture 守卫跳过（项目打开不监听，启动运行由 onConsoleAppeared 自动捕捉）。
 */
object ProjectListenerRegistrar {

    private val LOG = Logger.getInstance(ProjectListenerRegistrar::class.java)
    private var subscribed = false

    /** 由 LogStartupCapture.execute() 调用——平台保证 postStartupActivity 触发后执行。 */
    fun registerIfNeeded() {
        if (subscribed) return
        subscribed = true

        val connection = ApplicationManager.getApplication().messageBus.connect()
        val listener = object : ProjectManagerListener {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun projectOpened(project: Project) {
                LOG.info("ProjectListenerRegistrar.projectOpened: 触发（project=${project.name}, logAutoCaptureOnStart=${PluginSettings.getInstance().state.logAutoCaptureOnStart}）")
                if (!PluginSettings.getInstance().state.logAutoCaptureOnStart) return
                val manager = project.getService(SqlLogManager::class.java)
                tryStartCapture(manager, project, attempt = 0)
            }
        }
        connection.subscribe(ProjectManager.TOPIC, listener)
        LOG.info("ProjectListenerRegistrar: ProjectManagerListener 已注册到 messageBus")

        // 兜底：注册时项目已经打开（热加载/安装后重启），projectOpened 不会再次触发
        ProjectManager.getInstance().openProjects.forEach { project ->
            LOG.info("ProjectListenerRegistrar: 发现已打开项目 ${project.name}，直接尝试 startCapture")
            if (PluginSettings.getInstance().state.logAutoCaptureOnStart) {
                val manager = project.getService(SqlLogManager::class.java)
                tryStartCapture(manager, project, attempt = 0)
            }
        }
    }

    private fun tryStartCapture(manager: SqlLogManager, project: Project, attempt: Int) {
        LOG.info("tryStartCapture: 第 ${attempt + 1} 次尝试（project=${project.name}）")
        manager.startCapture()
        if (!manager.capturing && attempt < MAX_RETRIES && !project.isDisposed) {
            LOG.info("tryStartCapture: 未生效，${RETRY_DELAY_MS}ms 后重试 (${attempt + 1}/$MAX_RETRIES)")
            ApplicationManager.getApplication().executeOnPooledThread {
                Thread.sleep(RETRY_DELAY_MS)
                tryStartCapture(manager, project, attempt + 1)
            }
        } else if (manager.capturing) {
            LOG.info("tryStartCapture: 已成功进入捕捉态")
        }
    }

    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 5000L
}
