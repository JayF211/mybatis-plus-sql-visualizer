package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * 运行配置进程启动监听（20260907-2045 第二轮修复）——「项目启动时自动开始监听」的进程级入口。
 *
 * 根因（沙盒日志 20:41 会话实证）：用户手动停止后 `suppressed=true` 持久化 + 桥轮询/contentListener
 * 全部拆除；此后再 Run 项目时 [LogStartupCapture]（postStartupActivity 只跑一次）、
 * [SqlLogManager.onConsoleAppeared]（`suppressed && !userArmed` 守卫拦截）、设置页边沿（设置未再变更）
 * 三条路径全部失效——自动监听永久沉没。项目真正启动是用户对「自动监听」的再次确认
 * （对齐踩坑第十一轮「设置优先」），须在此清 suppressed 并重启捕捉。
 *
 * 平台兼容：`ExecutionManager.EXECUTION_TOPIC` + `ExecutionListener` 在 2024.3 于
 * `app-client.jar`（execution API 域）；2026.2 IPGP 裁剪后部分 execution API 移位——
 * 全部经 `Class.forName(name, true, platformClassLoader)` 显式平台 classloader 反射解析
 * （双亲委派下插件 classloader 找不到平台已加载类，见 pitfall-classloader-forname-execution-api）；
 * 任一环节失败则静默降级为「无进程级入口」（既有 RCM 轮询兜底仍在）。
 *
 * 回调分工：`processStarted` → [SqlLogManager.onProjectProcessStarted]（清 suppressed + startCapture 幂等）；
 * `processTerminated` **不**在此处理——项目停止语义由 [SqlLogManager.onConsoleRemoved]（控制台移除）
 * 承担，此处若停会对所有 RunProfile 触发（HTTP 请求/profiler 均是进程，踩坑第九轮 v2 误停教训）。
 *
 * 生命周期：messageBus connect 挂 manager（project 服务 Disposable），项目关闭自动断开。
 */
object RunConfigurationProcessListener {

    private val LOG = Logger.getInstance(RunConfigurationProcessListener::class.java)

    /** 平台 classloader（ExecutionListener 等平台 API 所在；插件 classloader 是其子类）。 */
    private val platformClassLoader: ClassLoader =
        Project::class.java.classLoader

    /**
     * 订阅项目级 EXECUTION_TOPIC；成功返回 true，execution API 缺失返回 false（降级）。
     * handler 只实现三参版 `processStarted`（243 起为接口唯一进程启动回调形态），
     * 其余方法走 InvocationHandler 默认分支直接忽略。
     */
    fun subscribe(project: Project, manager: SqlLogManager): Boolean {
        return try {
            val executionManagerClass = Class.forName("com.intellij.execution.ExecutionManager", true, platformClassLoader)
            val listenerClass = Class.forName("com.intellij.execution.ExecutionListener", true, platformClassLoader)
            val topic = executionManagerClass.getField("EXECUTION_TOPIC").get(null)
            val handler = InvocationHandler { _, method, args ->
                if (method.name == "processStarted" && args != null && args.size >= 2) {
                    try {
                        manager.onProjectProcessStarted()
                    } catch (t: Throwable) {
                        LOG.warn("processStarted: 自动捕捉回调失败（project=${project.name}）", t)
                    }
                }
                null
            }
            val listener = Proxy.newProxyInstance(platformClassLoader, arrayOf(listenerClass), handler)
            val bus = project.messageBus
            // connect(disposable) 须兼容 243/262——messageBus.connect(Disposable) 全平台存在
            val connectMethod = bus.javaClass.methods.first { it.name == "connect" && it.parameterCount == 1 }
            val connection = connectMethod.invoke(bus, project)
            val subscribeMethod = connection.javaClass.methods.first {
                it.name == "subscribe" && it.parameterCount == 2
            }
            subscribeMethod.invoke(connection, topic, listener)
            LOG.info("RunConfigurationProcessListener: 已订阅 EXECUTION_TOPIC（project=${project.name}）")
            true
        } catch (t: Throwable) {
            LOG.warn("RunConfigurationProcessListener: EXECUTION_TOPIC 不可用——降级为 RCM 轮询兜底（project=${project.name}）", t)
            false
        }
    }
}
