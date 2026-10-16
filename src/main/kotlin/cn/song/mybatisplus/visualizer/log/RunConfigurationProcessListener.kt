package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import java.lang.reflect.InvocationHandler
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

    /** 最近一次 scanAndAttach 的补发数（诊断缝；单线程调用面，无需并发容器）。 */
    @Volatile
    private var lastScanAttachedCount: Int = 0

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

    /**
     * T-FirstUseFreshInstallConsent S2：订阅后补扫在跑的主配置进程——堵「事件早于订阅」竞态。
     * 沙盒实录（idea.log 12:41 会话）：项目打开瞬间 Run 已在跑，EXECUTION_TOPIC 订阅完成于
     * 进程出现之后 → 首轮既无确认弹窗也无 attach（全静默）。
     *
     * **判据勘误（R3）**：R2 首版用 `descriptor.getRunProfile()` 探测主配置——243 平台
     * `RunContentDescriptor` 无此方法（javap 全方法列表实证，构造器可传入但无 getter），
     * 每个描述符 `NoSuchMethodException` 被静默 continue，补扫结构性空转零诊断。现改为
     * **JVM 进程判据**：handler 为 BaseOSProcessHandler 系实例（Java 应用/测试 fork 均是；
     * HTTP 客户端 handler 不在该继承链，天然排除）。残余口径（登记）：首启窗口的测试 fork JVM
     * 会被视为候选——弹确认有三态+冷却兜底，可接受。
     *
     * 20261008 起扫描与补挂实现收敛在 [scanAndAttachRunningProcesses]（设置变更上升沿复用同一入口），
     * 本函数退化为 backfill 场景包装。
     */
    private fun backfillRunningProcesses(project: Project) {
        scanAndAttachRunningProcesses(project, logTag = "backfill")
    }

    /**
     * 20261008 验收偏差 #6 修复（D120，拍板 A「补能力」）：扫描本工程在跑的 JVM 进程并逐个补发
     * onProcessAppeared。此前 attach 只挂 EXECUTION_TOPIC 进程启动钩子（processStarted），用户对
     * **已在跑**的应用开启增强捕获开关时无任何入口挂载（真机实测 idea.log 仅记「拒绝冷却已重置」，
     * 进程零挂载）——验收文档 3.27「attach 后无需重启应用」不可达。现由 AgentManager 设置变更
     * 上升沿（增强重新开启且已确认/刚同意）调用本方法扫描补挂；attachedPids 幂等表兜底防重复挂载。
     *
     * 扫描判据与诊断红线沿用 backfill（R3）：JVM 进程 = BaseOSProcessHandler 系实例（HTTP 客户端
     * handler 不在该继承链，天然排除）；每个描述符跳过原因记 debug、汇总记 INFO（logTag 区分场景）。
     * 全链反射（execution 域 API 不在编译 classpath）+ 异常只 WARN 不抛（补位路径，绝不影响调用方）。
     *
     * @return 补发挂载的进程数（反射链整体失败时 0——失败已记 WARN）。
     */
    internal fun scanAndAttachRunningProcesses(project: Project, logTag: String): Int {
        runCatching {
            doScanAndAttach(project, logTag)
        }.onFailure { t ->
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
            LOG.warn("$logTag: 在跑进程补扫失败（补位路径，不影响调用方）", t)
        }
        return lastScanAttachedCount
    }

    private fun doScanAndAttach(project: Project, logTag: String) {
        val rcmClass = Class.forName(
            "com.intellij.execution.ui.RunContentManager",
            true,
            platformClassLoader,
        )
        val rcm = rcmClass.getMethod("getInstance", Project::class.java).invoke(null, project)
        val raw = runCatching { rcmClass.getMethod("getAllDescriptors").invoke(rcm) }.getOrNull()
        val descriptors = when (raw) {
            is Array<*> -> raw
            is Collection<*> -> raw.toTypedArray()
            else -> null
        } ?: run {
            LOG.info("$logTag: getAllDescriptors 不可用——跳过补扫（project=${project.name}）")
            return
        }
        scanDescriptors(project, logTag, descriptors)
    }

    /** UI-007：与生产补扫共用循环，测试传真实 BaseOSProcessHandler 描述符，不扫描系统进程。 */
    internal fun scanDescriptors(project: Project, logTag: String, descriptors: Array<*>) {
        var noHandler = 0
        var dead = 0
        var nonJvm = 0
        var excluded = 0
        var backfilled = 0
        val jvmHandlerClass = runCatching {
            Class.forName("com.intellij.execution.process.BaseOSProcessHandler", true, platformClassLoader)
        }.getOrNull()
        for (descriptor in descriptors) {
            val d = descriptor ?: continue
            val handler = runCatching {
                d.javaClass.getMethod("getProcessHandler").invoke(d)
            }.getOrNull()
            if (handler == null) {
                noHandler++
                LOG.debug("$logTag: 描述符 ${d.javaClass.name} 无 processHandler——跳过")
                continue
            }
            val alive = runCatching {
                val terminated = handler.javaClass.getMethod("isProcessTerminated").invoke(handler) as? Boolean
                val terminating = handler.javaClass.getMethod("isProcessTerminating").invoke(handler) as? Boolean
                terminated == false && terminating == false
            }.getOrNull()
            if (alive != true) {
                dead++
                LOG.debug("$logTag: 描述符 ${d.javaClass.name} 进程已结束/正在结束——跳过")
                continue
            }
            val isJvm = jvmHandlerClass != null && jvmHandlerClass.isInstance(handler)
            if (!isJvm) {
                nonJvm++
                LOG.debug("$logTag: 描述符 ${d.javaClass.name} handler 非 JVM 进程（${handler.javaClass.name}）——跳过")
                continue
            }
            val agentManager = project.getService(AgentManager::class.java)
            if (!agentManager.isAttachCandidate(handler)) {
                excluded++
                continue
            }
            backfilled++
            LOG.info("$logTag: 发现已在跑的 JVM 候选（${handler.javaClass.name}）——补发 onProcessAppeared")
            agentManager.onProcessAppeared(handler, isMainConfigLaunch = true)
        }
        LOG.info(
            "$logTag: 在跑进程扫描完成——描述符 ${descriptors.size} 个，补发 $backfilled" +
                "（无handler $noHandler / 已结束 $dead / 非JVM $nonJvm / 排除目标 $excluded，project=${project.name}）",
        )
        lastScanAttachedCount = backfilled
    }

    /** 纯判定（测试缝）：profile 是否模块型主配置；反射失败保守按主进程处理（宁清零勿永不归零）。 */    private fun isModuleBasedProfile(profile: Any): Boolean = try {
        Class.forName(
            "com.intellij.execution.configurations.ModuleBasedConfiguration",
            true,
            platformClassLoader,
        ).isInstance(profile)
    } catch (t: Throwable) {
        // T-FatalPassthrough T-FP-02：JVM 级致命错误透传（见 util/FatalErrors.kt）——本文件全部
        // 捕获点的捕获面都覆盖了 Error：ClassNotFoundException（可选能力缺失）仍走既有降级，
        // NoClassDefFoundError/ExceptionInInitializerError（环境/构建损坏）不得降级成一条 warn。
        if (FatalErrors.isFatal(t)) throw t
        LOG.warn("isModuleBasedProfile: 判定失败，按主进程处理（${t.message}）", t)
        true
    }

    internal fun offerStartedProcess(project: Project, handler: Any?, isMainLaunch: Boolean) {
        project.getService(AgentManager::class.java).onProcessAppeared(handler, isMainLaunch)
    }

    /**
     * 订阅项目级 EXECUTION_TOPIC；成功返回 true，execution API 缺失返回 false（降级）。
     * handler 只处理 `processStarted`（243：三参 `(String, ExecutionEnvironment, ProcessHandler)` 单重载）。
     */
    fun subscribe(project: Project, manager: SqlLogManager): Boolean {
        val listenerClass = try {
            Class.forName("com.intellij.execution.ExecutionListener", true, platformClassLoader)
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
            LOG.warn("RunConfigurationProcessListener: ExecutionListener 不可用——降级为 RCM 轮询兜底（project=${project.name}）", t)
            return false
        }
        val topic = try {
            val executionManagerClass =
                Class.forName("com.intellij.execution.ExecutionManager", true, platformClassLoader)
            executionManagerClass.getField("EXECUTION_TOPIC").get(null)
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
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
                        if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
                        LOG.warn("processStarted: 行号清零回调失败（project=${project.name}）", t)
                    }
                } else {
                    LOG.info("processStarted: 非主配置进程启动（HTTP/脚本/profiler 等），行号不清零（project=${project.name}）")
                }
                try {
                    manager.onProjectProcessStarted()
                } catch (t: Throwable) {
                    if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
                    LOG.warn("processStarted: 自动捕捉回调失败（project=${project.name}）", t)
                }

                // M56 T-M56-01/02：增强捕获惰性挂载入口（增强未开启/未确认时空操作；
                // 后台线程等进程就绪 + 3s 去抖再 attach，全程不进 EDT）。
                // T-PS4-01 触发面收窄：未确认态的确认弹窗仅对主配置启动触发（复用上方
                // isMainLaunch 同源判定），HTTP 请求/测试/profiler 等非主配置进程在未确认态
                // 静默跳过——杜绝「拒绝注入的用户每进程启动挨一次模态弹窗」；已确认的 attach
                // 链路仍须经 AgentManager 的 UI-007 目标身份门，授权不等于 helper 可注入。
                try {
                    offerStartedProcess(project, args?.getOrNull(2), isMainLaunch)
                } catch (t: Throwable) {
                    if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
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
            backfillRunningProcesses(project)
            true
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
            LOG.warn("RunConfigurationProcessListener: messageBus 订阅失败——降级为 RCM 轮询兜底（project=${project.name}）", t)
            false
        }
    }
}
