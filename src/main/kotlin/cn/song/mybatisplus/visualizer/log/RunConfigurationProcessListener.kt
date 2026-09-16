package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import java.lang.reflect.InvocationHandler
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import java.lang.reflect.Proxy

/**
 * 运行配置进程启动监听（20260907-2045 第二轮修复；22:53 会话修正反射细节）——
 * 「项目启动时自动开始监听」的进程级入口。
 *
 * 根因（沙盒日志 20:41 会话实证）：用户手动停止后 `suppressed=true` 持久化 + 桥轮询/contentListener
 * 全部拆除；此后再 Run 项目时 [LogStartupCapture]（postStartupActivity 只跑一次）、
 * [SqlLogManager.onConsoleAppeared]（`suppressed && !userArmed` 守卫拦截）、设置页边沿（设置未再变更）
 * 三条路径全部失效——自动监听永久沉没。项目真正启动是用户对「自动监听」的再次确认
 * （对齐踩坑第十一轮「设置优先」），须在此清 suppressed 并重启捕捉。
 *
 * 平台兼容：
 *  - `ExecutionManager.EXECUTION_TOPIC` / `ExecutionListener` 在 243 位于 `app-client.jar`
 *    （execution API 域）；插件 classloader 是平台 classloader 的子类，双亲委派下
 *    `Class.forName` 需显式传 platformClassLoader（pitfall-classloader-forname-execution-api）；
 *  - **subscribe 必须走编译期 `MessageBusConnection.subscribe(Topic, L)` 直调**——22:53 会话实证
 *    `connection.javaClass.methods.first { name=="subscribe" }` 拿到的是泛型擦除后的
 *    `SimpleMessageBusConnection.subscribe(Topic, Object)` 之外的某个桥方法序，invoke 抛
 *    `IllegalArgumentException: argument type mismatch`（Topic 类型形参与 Object 不匹配）。
 *    `MessageBusConnection`/`SimpleMessageBusConnection` 是 `util.jar` 全平台稳定接口，
 *    可编译期直引，无需反射；只有 execution 域（可能被 IPGP 裁剪/移位）才反射。
 *
 * 回调分工：`processStarted` → 先判定是否主配置（[isModuleBasedLaunch]，主业务 Java 应用才清行号——
 * HTTP 请求/profiler/远程调试等非模块型 RunProfile 跳过清零，防项目运行期间反复发请求把行号错误归零；
 * 用户反馈「项目启动中多次发送请求都从头计数」的根因）→ [SqlLogManager.resetEntrySequence]（T-LogRowNumber：
 * 行号清零）→ [SqlLogManager.onProjectProcessStarted]（清 suppressed + startCapture 幂等）；
 * `processTerminated` **不**在此处理——项目停止语义由 [SqlLogManager.onConsoleRemoved] 承担，
 * 此处若停会对所有 RunProfile 触发（HTTP 请求/profiler 均是进程，踩坑第九轮 v2 误停教训）。
 *
 * 生命周期：`project.messageBus.connect(manager)`（manager 是 project 服务 Disposable），
 * 项目关闭自动断开。
 */
object RunConfigurationProcessListener {

    private val LOG = Logger.getInstance(RunConfigurationProcessListener::class.java)

    /** 平台 classloader（ExecutionListener 等平台 API 所在；插件 classloader 是其子类）。 */
    private val platformClassLoader: ClassLoader =
        Project::class.java.classLoader

    /**
     * T-LogRowNumber：判定一次 processStarted 是否「主业务 Java 应用」启动。
     * 243 反编译实证 processStarted 签名为 `(String executorId, ExecutionEnvironment env, ProcessHandler)`，
     * `env.getRunProfile()` 返回 RunProfile；仅当其实现 [ModuleBasedConfiguration]（Application/JUnit/SpringBoot
     * 等主 run 配置的共同父类）才视为主进程。HTTP 请求脚本、profiler、远程调试等非模块型 RunProfile 被排除，
     * 避免项目运行期间的附带进程启动把行号错误清零。反射读取——execution 域 API 不在编译 classpath。
     */
    private fun isModuleBasedLaunch(args: Array<out Any>?): Boolean {
        val profile = args?.getOrNull(1)?.let { env ->
            runCatching { env.javaClass.getMethod("getRunProfile").invoke(env) }.getOrNull()
        }
        if (profile == null) return false
        return isModuleBasedProfile(profile)
    }

    /** 纯判定（测试缝）：profile 是否模块型主配置；反射失败保守按主进程处理（宁清零勿永不归零）。 */
    private fun isModuleBasedProfile(profile: Any): Boolean = try {
        Class.forName(
            "com.intellij.execution.configurations.ModuleBasedConfiguration",
            true,
            platformClassLoader,
        ).isInstance(profile)
    } catch (t: Throwable) {
        LOG.warn("isModuleBasedProfile: 判定失败，按主进程处理（${t.message}）", t)
        true
    }

    /**
     * 订阅项目级 EXECUTION_TOPIC；成功返回 true，execution API 缺失返回 false（降级）。
     * handler 只处理 `processStarted`（243：三参 `(String, ExecutionEnvironment, ProcessHandler)` 单重载）。
     */
    fun subscribe(project: Project, manager: SqlLogManager): Boolean {
        val listenerClass = try {
            Class.forName("com.intellij.execution.ExecutionListener", true, platformClassLoader)
        } catch (t: Throwable) {
            LOG.warn("RunConfigurationProcessListener: ExecutionListener 不可用——降级为 RCM 轮询兜底（project=${project.name}）", t)
            return false
        }
        val topic = try {
            val executionManagerClass =
                Class.forName("com.intellij.execution.ExecutionManager", true, platformClassLoader)
            executionManagerClass.getField("EXECUTION_TOPIC").get(null)
        } catch (t: Throwable) {
            LOG.warn("RunConfigurationProcessListener: EXECUTION_TOPIC 不可用——降级为 RCM 轮询兜底（project=${project.name}）", t)
            return false
        }
        val handler = InvocationHandler { _, method, args ->
            if (method.name == "processStarted") {
                // T-LogRowNumber：行号清零须识别「主配置启动」——processStarted 对**任意**运行配置进程
                // （HTTP 请求脚本/profiler/重复 Run 的测试等都走 EXECUTION_TOPIC）都会触发。无条件清零会让
                // 项目运行期间每次发请求都从 #1 重新计数（用户反馈）。仅当该 RunProfile 是主业务 Java 应用
                // （ModuleBasedConfiguration 子类）时清零，对齐「重新 Run 主配置」语义。
                val isMainLaunch = isModuleBasedLaunch(args)
                if (isMainLaunch) {
                    try {
                        manager.resetEntrySequence()
                    } catch (t: Throwable) {
                        LOG.warn("processStarted: 行号清零回调失败（project=${project.name}）", t)
                    }
                } else {
                    LOG.info("processStarted: 非主配置进程启动（HTTP/脚本/profiler 等），行号不清零（project=${project.name}）")
                }
                try {
                    manager.onProjectProcessStarted()
                } catch (t: Throwable) {
                    LOG.warn("processStarted: 自动捕捉回调失败（project=${project.name}）", t)
                }

                // M56 T-M56-01/02：增强捕获惰性挂载入口（增强未开启/未确认时空操作；
                // 后台线程等进程就绪 + 3s 去抖再 attach，全程不进 EDT）
                try {
                    project.getService(AgentManager::class.java).onProcessAppeared(args?.getOrNull(2))
                } catch (t: Throwable) {
                    LOG.warn("processStarted: 增强捕获回调失败（project=${project.name}）", t)
                }
            }
            null
        }
        val listener = Proxy.newProxyInstance(platformClassLoader, arrayOf(listenerClass), handler)
        return try {
            // 编译期直调 MessageBusConnection.subscribe(Topic, L)——243/253/262 全平台稳定接口
            val connection: MessageBusConnection = project.messageBus.connect(manager)
            connection.subscribe(
                @Suppress("UNCHECKED_CAST")
                (topic as com.intellij.util.messages.Topic<Any>),
                listener,
            )
            LOG.info("RunConfigurationProcessListener: 已订阅 EXECUTION_TOPIC（project=${project.name}）")
            true
        } catch (t: Throwable) {
            LOG.warn("RunConfigurationProcessListener: messageBus 订阅失败——降级为 RCM 轮询兜底（project=${project.name}）", t)
            false
        }
    }
}
