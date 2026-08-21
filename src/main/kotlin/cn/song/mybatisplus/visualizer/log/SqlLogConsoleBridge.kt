package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import javax.swing.Timer

/**
 * 运行控制台反射桥（T-M31-03）：`com.intellij.execution.*`（RunContentManager/ConsoleView）
 * 不在插件编译 classpath（IPGP SDK 发行版裁剪 execution 模块），运行期由真实 IDE 提供——
 * 与既有 `execute/` 数据库链路同一隔离策略（build.gradle.kts 注释约定）。
 *
 * **T-M31.10 版本差异实证（真实 IDE 2026.2.1 实测失败定位）**：
 * - 2024.3/2025.3：`com.intellij.execution.RunContentManager`（extends ContentManager，有 addContentManagerListener）
 * - **2026.2：接口移到 `com.intellij.execution.ui.RunContentManager`，不再 extends ContentManager**（无 addContentManagerListener）
 * → 双包名候选加载；增量挂载（contentAdded）在 262 无接口方法 → **轮询兜底**（2s scanAll，幂等）。
 *
 * 全部调用经反射 + Proxy，任一步失败即优雅降级（返回 false / 静默跳过），不抛异常。
 *
 * T-xx（设置生效性审计）：`open` 开放子类化——[SqlLogManager] 内部测试缝（installBridgeForTest）
 * 注入受控桥（attach 恒成功/恒失败）验证 capturing 状态机，生产不受影响。
 */
open class SqlLogConsoleBridge(
    private val buffer: SqlLogEntryBuffer,
    private val parserConfig: () -> ParserConfig = {
        ParserConfig("Preparing:", "Parameters:", true, 20000)
    },
    private val syncBufferConfig: () -> Unit = {},
    private val onConsoleAppeared: () -> Unit = {},
    private val isCapturing: () -> Boolean = { true },
) {

    private var runContentManager: Any? = null
    /**
     * 已挂载的控制台 → Filter 代理映射。
     * 值从 Boolean 改为 Pair<filter, ifaceClass>，供 [detachAllFilters] 移除 Filter 使用。
     */
    val attachedConsoles = IdentityHashMap<Any, Pair<Any, Class<*>>>()
    private val contentListener = object : ContentManagerListener {
        override fun contentAdded(event: ContentManagerEvent) {
            val component = event.content.component
            LOG.info("contentAdded: ${component.javaClass.name}")
            scanComponent(component)
            onConsoleAppeared()
        }
        override fun contentRemoved(event: ContentManagerEvent) {
            LOG.info("contentRemoved: ${event.content.component.javaClass.name}")
            onConsoleRemoved()
        }
    }

    private var pollTimer: Timer? = null
    /** 高频补捞定时器（attach 后短暂运行，捕获第一条 SQL）。 */
    private var catchUpTimer: Timer? = null
    /** 新控制台出现回调（由 manager 设置）。 */
    var onConsoleRemoved: () -> Unit = {}

    /** 反射取 RunContentManager 实例（双包名候选）并挂载现有 console；环境缺 execution API 时返回 false。
     *  T-xx：`open` 供测试受控桥覆写（attach 恒成功/恒失败）。 */
    open fun attach(project: Project): Boolean {
        LOG.info("attach: 开始（project=${project.name}, runContentManager=${runContentManager?.javaClass?.name ?: "null"}）")
        if (runContentManager != null) {
            LOG.info("attach: already attached")
            return true
        }
        val rcm = runContentManagerOrNull(project)
        if (rcm == null) {
            LOG.warn("attach: no RunContentManager candidate available (execution API missing in this environment)")
            return false
        }
        runContentManager = rcm
        attachContentListener(rcm)
        scanAll()
        LOG.info("attach: 成功 RCM=${rcm.javaClass.name}, consolesAttached=${attachedConsoles.size}")
        return true
    }

    /** 停止快速轮询定时器。 */
    private fun stopCatchUpPolling() {
        catchUpTimer?.stop()
        catchUpTimer = null
    }

    /**
     * 启动轮询：无控制台时快速轮询（500ms）→ 检测到新控制台后切换常规轮询（2s）。
     * 性能：500ms 间隔仅在有项目运行且未检测到控制台时运行，检测到后立即切 2s。
     */
    fun startPolling() {
        if (catchUpTimer != null || pollTimer != null) return
        startFastPolling()
        LOG.debug("startPolling: 快速轮询已启动（500ms）")
    }

    fun stopPolling() {
        pollTimer?.stop()
        pollTimer = null
        stopCatchUpPolling()
    }

    /** 轮询是否活跃（测试断言用：验证 stop 后轮询已停止）。 */
    val isPollingActive: Boolean get() = pollTimer != null || catchUpTimer != null

    /** 快速轮询（500ms）—— 项目运行中但未检测到控制台时，尽快捕获第一条 SQL。 */
    private fun startFastPolling() {
        stopCatchUpPolling()
        catchUpTimer = Timer(FAST_POLL_INTERVAL_MS) { scanAll() }.apply { isRepeats = true; start() }
    }

    /** 常规轮询（2s）—— 已检测到控制台后，低频兜底。 */
    private fun startNormalPolling() {
        stopCatchUpPolling()
        pollTimer = Timer(POLL_INTERVAL_MS) { scanAll() }.apply { isRepeats = true; start() }
    }

    /** 移除挂载（项目关闭/面板销毁时）。 */
    fun detach() {
        stopPolling()
        detachAllFilters()
        val rcm = runContentManager ?: return
        try {
            rcm.javaClass.getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
        } catch (t: Throwable) {
            LOG.debug("detach: removeContentManagerListener failed", t)
        }
        runContentManager = null
    }

    /** ContentManagerListener 是否注册成功（243/253=成功，262+=失败→需轮询兜底）。 */
    var contentListenerRegistered = false
        private set

    private fun attachContentListener(rcm: Any) {
        try {
            rcm.javaClass.getMethod("addContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
            contentListenerRegistered = true
            LOG.info("attach: ContentManagerListener 注册成功（${rcm.javaClass.name}）")
        } catch (t: Throwable) {
            // 2026.2 接口无此方法（不 extends ContentManager）→ 轮询兜底
            contentListenerRegistered = false
            LOG.warn("attach: ContentManagerListener 不可用（${rcm.javaClass.name}）— 轮询兜底")
        }
    }

    /** 上一轮是否检测到主运行控制台（ConsoleViewImpl——Spring Boot 等 run 进程）。 */
    private var lastHadMainConsole = false

    /**
     * 实时查询 RunContentManager 当前是否有**进程存活的**主运行控制台。
     * 经实证：getAllDescriptors() 在进程终止后仍返回旧 tab（tab 保留），
     * 故须额外检查 ProcessHandler.isProcessTerminated()——进程死即视为「无主控制台」。
     * `open` 供测试受控桥覆写（见 [SqlLogManager] 内部测试缝）。
     */
    open fun hasMainConsole(): Boolean {
        val rcm = runContentManager ?: return false
        val descriptors = try {
            val raw = rcm.javaClass.getMethod("getAllDescriptors").invoke(rcm)
            when (raw) {
                is Array<*> -> raw
                is Collection<*> -> raw.toTypedArray()
                else -> null
            }
        } catch (_: Throwable) {
            null
        } ?: return false
        for (descriptor in descriptors) {
            val component = try {
                descriptor?.javaClass?.getMethod("getComponent")?.invoke(descriptor) as? Component
            } catch (_: Throwable) {
                null
            } ?: continue
            val isMain = component.javaClass.name.contains("ConsoleViewImpl") ||
                !component.javaClass.name.contains("HttpSingleResponseConsole")
            if (!isMain) continue
            // 主控制台存在 → 检查进程是否还活着
            if (isProcessAlive(descriptor)) return true
        }
        return false
    }

    /** 反射检查 RunContentDescriptor 关联的进程是否存活（ProcessHandler.isProcessTerminated == false 且 isProcessTerminating == false）。 */
    private fun isProcessAlive(descriptor: Any?): Boolean {
        if (descriptor == null) return false
        return try {
            val processHandler = descriptor.javaClass.getMethod("getProcessHandler").invoke(descriptor) ?: return false
            val terminated = processHandler.javaClass.getMethod("isProcessTerminated").invoke(processHandler) as? Boolean ?: true
            val terminating = processHandler.javaClass.getMethod("isProcessTerminating").invoke(processHandler) as? Boolean ?: true
            val alive = !terminated && !terminating
            LOG.debug("isProcessAlive: alive=$alive, descriptor=${descriptor.javaClass.simpleName}")
            alive
        } catch (t: Throwable) {
            LOG.debug("isProcessAlive: 反射失败（${t.javaClass.simpleName}），默认视为存活")
            true // 反射失败时保守视为存活（避免误停）
        }
    }

    /**
     * 遍历 RunContentManager 全部运行描述符，对每个 console 挂载 filter（幂等）。
     * 主控制台移除检测：HttpSingleResponseConsole（HTTP 请求工具窗口）常驻，
     * 即使 Spring Boot 停止也不消失——故检测「ConsoleViewImpl 主控制台消失」而非「所有控制台消失」。
     */
    open fun scanAll() {
        val hasMain = hasMainConsole()
        // 每轮状态日志（debug——诊断主控制台移除检测）
        LOG.debug("scanAll: hasMainConsole=$hasMain, lastHadMainConsole=$lastHadMainConsole")
        // 主控制台移除：上一轮有、当前轮无 → 项目停止
        if (lastHadMainConsole && !hasMain) {
            LOG.info("scanAll: 主控制台已移除（上轮有、当前无），触发 onConsoleRemoved")
            onConsoleRemoved()
        }
        // 主控制台出现 → 自动重新监听
        if (!lastHadMainConsole && hasMain) {
            LOG.info("scanAll: 主控制台出现，触发 onConsoleAppeared")
            onConsoleAppeared()
            if (catchUpTimer != null) {
                LOG.info("scanAll: 检测到控制台，快速轮询 → 常规轮询（2s）")
                startNormalPolling()
            }
        }
        // 挂载 filter（幂等）
        val rcm = runContentManager ?: return
        val descriptors = try {
            val raw = rcm.javaClass.getMethod("getAllDescriptors").invoke(rcm)
            when (raw) {
                is Array<*> -> raw
                is Collection<*> -> raw.toTypedArray()
                else -> null
            }
        } catch (_: Throwable) {
            null
        } ?: return
        for (descriptor in descriptors) {
            val component = try {
                descriptor?.javaClass?.getMethod("getComponent")?.invoke(descriptor) as? Component
            } catch (_: Throwable) {
                null
            } ?: continue
            scanComponent(component)
        }
        lastHadMainConsole = hasMain
    }

    /** 组件树中查找 ConsoleView 实现并挂载（供 contentAdded 与扫描复用）。 */
    fun scanComponent(component: Component?) {
        val console = findConsoleView(component)
        if (console == null) {
            LOG.debug("scanComponent: no ConsoleView found under ${component?.javaClass?.name}")
            return
        }
        attachFilter(console)
    }

    private fun attachFilter(console: Any) {
        // BUG FIX（停止后仍捕获）：非捕捉态时不挂载 Filter——守卫轮询/事件回调触发的 scanAll。
        if (!isCapturing()) {
            LOG.debug("attachFilter: 非捕捉态，跳过挂载 ${console.javaClass.name}")
            return
        }
        if (attachedConsoles.containsKey(console)) {
            LOG.debug("attachFilter: already attached ${console.javaClass.name}")
            return
        }
        for (filterIfaceName in FILTER_IFACE_CANDIDATES) {
            try {
                val filterIface = Class.forName(filterIfaceName, true, platformClassLoader)
                val config = parserConfig()
                // 双解析器：MyBatis + Hibernate 并列运行，互不干扰
                val myBatisParser = MyBatisLogParser(
                    sqlPrefix = config.sqlPrefix,
                    paramPrefix = config.paramPrefix,
                    mixedPrefix = config.mixedPrefix,
                    maxParseLength = config.maxParseLength,
                )
                val hibernateParser = HibernateLogParser(
                    sqlPrefix = config.hibernateSqlPrefix,
                    paramPrefix = config.hibernateParamPrefix,
                    mixedPrefix = config.mixedPrefix,
                    maxParseLength = config.maxParseLength,
                )
                val hibernateEnabled = config.hibernateEnabled
                val filter = Proxy.newProxyInstance(
                    filterIface.classLoader,
                    arrayOf(filterIface),
                    LogFilterProxyHandler { line ->
                        // T-xx（设置生效性审计）：每次喂行前同步解析器配置——前缀/混合/最大长度即时生效，
                        // 对齐 filterKeywords/去重窗口的 T-M31.18 实时同步策略；此前 parser 挂载时固化配置，
                        // 捕捉中修改仅对**之后新挂载**的 console 生效（读缓存旧值），现同策略修齐
                        val cfg = parserConfig()
                        myBatisParser.sqlPrefix = cfg.sqlPrefix
                        myBatisParser.paramPrefix = cfg.paramPrefix
                        myBatisParser.mixedPrefix = cfg.mixedPrefix
                        myBatisParser.maxParseLength = cfg.maxParseLength
                        hibernateParser.sqlPrefix = cfg.hibernateSqlPrefix
                        hibernateParser.paramPrefix = cfg.hibernateParamPrefix
                        hibernateParser.mixedPrefix = cfg.mixedPrefix
                        hibernateParser.maxParseLength = cfg.maxParseLength
                        LOG.debug("FilterProxy: line=${line.take(120)}")
                        // 喂行到两个解析器（各自忽略不匹配的行）
                        myBatisParser.feed(line)
                        if (hibernateEnabled) hibernateParser.feed(line)
                        // T-M31.18：每次入队前同步设置（去重窗口/过滤关键字即时生效，不再依赖重新捕捉）
                        syncBufferConfig()
                        val taken = myBatisParser.takeEntries() + hibernateParser.takeEntries()
                        if (taken.isNotEmpty()) LOG.debug("FilterProxy: 捕获到 ${taken.size} 条 SQL: ${taken.first().restoredSql.take(80)}")
                        taken.forEach { buffer.addEntry(it) }
                    },
                )
                console.javaClass.getMethod("addMessageFilter", filterIface).invoke(console, filter)
                attachedConsoles[console] = filter to filterIface
                LOG.info("attachFilter: filter($filterIfaceName) attached to ${console.javaClass.name}")
                return
            } catch (t: Throwable) {
                LOG.debug("attachFilter: candidate $filterIfaceName failed on ${console.javaClass.name}", t)
            }
        }
        LOG.warn("attachFilter: all filter candidates failed on ${console.javaClass.name}")
    }

    /**
     * BUG FIX（停止后仍捕获）：移除所有已挂载控制台上的 Filter。
     * 遍历 [attachedConsoles]，对每个 console 调用 removeMessageFilter 反射移除 Filter 代理，
     * 然后清空映射——确保 stop 后控制台不再向 buffer 喂 SQL。
     */
    open fun detachAllFilters() {
        for ((console, filterPair) in attachedConsoles) {
            val (filter, filterIface) = filterPair
            try {
                console.javaClass.getMethod("removeMessageFilter", filterIface).invoke(console, filter)
                LOG.info("detachAllFilters: filter removed from ${console.javaClass.name}")
            } catch (t: Throwable) {
                LOG.debug("detachAllFilters: removeMessageFilter failed on ${console.javaClass.name}", t)
            }
        }
        attachedConsoles.clear()
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogConsoleBridge::class.java)

        /** 双包名候选：2024.3/2025.3 在 execution 包，2026.2 移到 execution.ui 包（实证）。 */
        private val RCM_CLASS_CANDIDATES = listOf(
            "com.intellij.execution.RunContentManager",
            "com.intellij.execution.ui.RunContentManager",
        )
        /** Filter 接口双候选：243/253 为 ConsoleView 嵌套接口；2026.2 移除嵌套接口，addMessageFilter 参数改为独立接口（T-M31.12 实证）。 */
        private val FILTER_IFACE_CANDIDATES = listOf(
            "com.intellij.execution.ui.ConsoleView\$Filter",
            "com.intellij.execution.filters.Filter",
        )
        private val CONTENT_LISTENER_CLASS = ContentManagerListener::class.java
        private const val POLL_INTERVAL_MS = 2000 // 常规轮询间隔
        private const val FAST_POLL_INTERVAL_MS = 100 // 快速轮询间隔（未检测到控制台时）——捕获第一条 SQL

        /** 平台 classloader（execution API 在此 classloader 中，不在插件 classloader 中）。 */
        private val platformClassLoader: ClassLoader =
            com.intellij.openapi.project.ProjectManager::class.java.classLoader

        /** 执行体系 API 在当前环境是否可用（测试环境/SDK 发行版为 false）。 */
        fun isConsoleApiAvailable(): Boolean = RCM_CLASS_CANDIDATES.any {
            try {
                Class.forName(it, true, platformClassLoader)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun runContentManagerOrNull(project: Project): Any? {
            LOG.info("runContentManagerOrNull: 尝试解析 RCM（candidates=$RCM_CLASS_CANDIDATES, cl=${platformClassLoader.javaClass.simpleName}）")
            for (className in RCM_CLASS_CANDIDATES) {
                try {
                    val clazz = Class.forName(className, true, platformClassLoader)
                    LOG.info("runContentManagerOrNull: $className 已加载，调用 getInstance")
                    val rcm = clazz.getMethod("getInstance", Project::class.java).invoke(null, project)
                    if (rcm != null) {
                        LOG.info("attach: RCM resolved via $className")
                        return rcm
                    }
                    LOG.warn("runContentManagerOrNull: $className.getInstance 返回 null")
                } catch (t: Throwable) {
                    LOG.warn("runContentManagerOrNull: $className 不可用（${t.javaClass.simpleName}: ${t.message}）")
                }
            }
            return null
        }
    }
}

/** 组件树中查找实现 `com.intellij.execution.ui.ConsoleView` 接口的组件（接口名匹配，无编译期引用）。 */
internal fun findConsoleView(component: Component?): Any? {
    if (component == null) return null
    if (implementsConsoleView(component.javaClass)) return component
    if (component is Container) {
        for (child in component.components) {
            findConsoleView(child)?.let { return it }
        }
    }
    return null
}

/** 类（含父类/父接口链）是否实现 ConsoleView 接口（接口名匹配）。 */
private fun implementsConsoleView(clazz: Class<*>): Boolean {
    if (clazz.interfaces.any { it.name == CONSOLE_VIEW_IFACE_NAME }) return true
    return clazz.interfaces.any { implementsConsoleView(it) } || clazz.superclass?.let { implementsConsoleView(it) } == true
}

private const val CONSOLE_VIEW_IFACE_NAME = "com.intellij.execution.ui.ConsoleView"
