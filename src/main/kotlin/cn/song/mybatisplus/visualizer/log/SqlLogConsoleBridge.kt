package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.Component
import java.awt.Container
import java.io.File
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
    /** 每个已挂载控制台 → 解析器上下文（含暂存源码位置，F-44-6）。 */
    private val consoleContexts = IdentityHashMap<Any, BridgeLineContext>()
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
    /** 当前快速轮询间隔（T-LogPerf-01：500ms 起步指数退避，上限=常规间隔）。 */
    private var currentFastDelayMs = FAST_POLL_INTERVAL_MS
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
     * 启动轮询：无控制台时快速轮询（500ms 起步，每拍无主控制台间隔翻倍至 2s 封顶）
     * → 检测到新控制台后切换常规轮询（2s）。
     * 性能（T-LogPerf-01）：快速轮询不再 100ms 常驻——armed 态全程的 EDT 负载
     * 从每秒 10 拍降至 2~4 拍起步并快速衰减到常规频率。
     */
    fun startPolling() {
        if (catchUpTimer != null || pollTimer != null) return
        startFastPolling()
        LOG.debug("startPolling: 快速轮询已启动（${FAST_POLL_INTERVAL_MS}ms，指数退避至 ${POLL_INTERVAL_MS}ms）")
    }

    fun stopPolling() {
        pollTimer?.stop()
        pollTimer = null
        stopCatchUpPolling()
    }

    /** 轮询是否活跃（测试断言用：验证 stop 后轮询已停止）。 */
    val isPollingActive: Boolean get() = pollTimer != null || catchUpTimer != null

    /** 快速轮询（500ms 起步）—— 项目运行中但未检测到控制台时，尽快捕获第一条 SQL。 */
    private fun startFastPolling() {
        stopCatchUpPolling()
        currentFastDelayMs = FAST_POLL_INTERVAL_MS
        catchUpTimer = Timer(currentFastDelayMs) { onCatchUpTick() }.apply { isRepeats = true; start() }
    }

    /** 快速轮询回调：先扫描（可能切走常规轮询），仍在等待且无主控制台则退避放宽下一拍。 */
    private fun onCatchUpTick() {
        scanAll()
        if (catchUpTimer != null && !lastHadMainConsole) {
            currentFastDelayMs = nextFastDelay(currentFastDelayMs, backoff = true)
            catchUpTimer?.delay = currentFastDelayMs
        }
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
        val descriptors = descriptorsOf(rcm) ?: return false
        for (descriptor in descriptors) {
            val component = componentOf(descriptor) ?: continue
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
            val processHandler = cachedMethod(descriptor.javaClass, "getProcessHandler")?.invoke(descriptor)
                ?: return false
            val terminated = cachedMethod(processHandler.javaClass, "isProcessTerminated")?.invoke(processHandler) as? Boolean ?: true
            val terminating = cachedMethod(processHandler.javaClass, "isProcessTerminating")?.invoke(processHandler) as? Boolean ?: true
            val alive = !terminated && !terminating
            LOG.debug("isProcessAlive: alive=$alive, descriptor=${descriptor.javaClass.simpleName}")
            alive
        } catch (t: Throwable) {
            LOG.debug("isProcessAlive: 反射失败（${t.javaClass.simpleName}），默认视为存活")
            true // 反射失败时保守视为存活（避免误停）
        }
    }

    /** descriptor → 已解析 console 缓存（T-LogPerf-03：命中且已挂载时跳过组件树遍历）。 */
    private val descriptorConsoleCache = IdentityHashMap<Any, Any?>()

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
        // 挂载 filter（幂等）；已挂载的 console 经缓存短路，不再遍历组件树（T-LogPerf-03）
        val rcm = runContentManager ?: return
        val descriptors = descriptorsOf(rcm) ?: return
        for (descriptor in descriptors) {
            if (descriptor == null) continue
            val cachedConsole = descriptorConsoleCache[descriptor]
            if (shouldSkipRescan(cachedConsole, attachedConsoles.keys)) continue
            val component = componentOf(descriptor) ?: continue
            scanComponent(component)
            descriptorConsoleCache[descriptor] = findConsoleView(component)
            if (descriptorConsoleCache.size > DESCRIPTOR_CACHE_MAX) descriptorConsoleCache.clear()
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
                // 三解析器：MyBatis + Hibernate + JdbcTemplate 并列运行，互不干扰（M47）
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
                val jdbcParser = JdbcTemplateLogParser(
                    enabled = config.jdbcEnabled,
                    maxParseLength = config.maxParseLength,
                )
                val hibernateEnabled = config.hibernateEnabled
                // 每个 console 持有独立解析器 + 暂存源码位置（避免多控制台相互干扰，F-44-6）
                val cfg0 = parserConfig()
                val ctx = BridgeLineContext(
                    myBatisParser = MyBatisLogParser(
                        sqlPrefix = cfg0.sqlPrefix,
                        paramPrefix = cfg0.paramPrefix,
                        mixedPrefix = cfg0.mixedPrefix,
                        maxParseLength = cfg0.maxParseLength,
                    ),
                    hibernateParser = HibernateLogParser(
                        sqlPrefix = cfg0.hibernateSqlPrefix,
                        paramPrefix = cfg0.hibernateParamPrefix,
                        mixedPrefix = cfg0.mixedPrefix,
                        maxParseLength = cfg0.maxParseLength,
                    ),
                    jdbcParser = JdbcTemplateLogParser(enabled = cfg0.jdbcEnabled, maxParseLength = cfg0.maxParseLength),
                    configProvider = parserConfig,
                    hibernateEnabled = cfg0.hibernateEnabled,
                )
                consoleContexts[console] = ctx
                // T-LogPerf-04：timePrefix 缓存化——设置变更走 refreshConsoleContexts，逐行不再读 PluginSettings
                timePrefixCache = PluginSettings.getInstance().state.logTimePrefix.takeIf { it.isNotBlank() }
                val filter = Proxy.newProxyInstance(
                    filterIface.classLoader,
                    arrayOf(filterIface),
                    LogFilterProxyHandler { line ->
                        val context = consoleContexts[console] ?: return@LogFilterProxyHandler
                        processConsoleLine(line, context, timePrefixCache, syncBufferConfig).forEach { buffer.addEntry(it) }
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

    /** 捕捉中逐行使用的 timePrefix 缓存（T-LogPerf-04：仅设置变更/挂载时刷新）。 */
    @Volatile
    var timePrefixCache: String? = null
        private set

    /**
     * T-LogPerf-04：设置变更事件驱动刷新——[SqlLogManager] 订阅 SETTINGS_CHANGE_TOPIC 后调用，
     * 一次同步全部已挂载上下文的解析器配置与 timePrefix 缓存，取代逐行 configProvider() 同步。
     */
    fun refreshConsoleContexts() {
        val cfg = parserConfig()
        for (ctx in consoleContexts.values) applyConfig(ctx, cfg)
        timePrefixCache = PluginSettings.getInstance().state.logTimePrefix.takeIf { it.isNotBlank() }
    }

    /** 把 [ParserConfig] 快照同步进 [BridgeLineContext]（挂载与设置变更共用）。 */
    private fun applyConfig(ctx: BridgeLineContext, cfg: ParserConfig) {
        ctx.myBatisParser.sqlPrefix = cfg.sqlPrefix
        ctx.myBatisParser.paramPrefix = cfg.paramPrefix
        ctx.myBatisParser.mixedPrefix = cfg.mixedPrefix
        ctx.myBatisParser.maxParseLength = cfg.maxParseLength
        ctx.hibernateParser.sqlPrefix = cfg.hibernateSqlPrefix
        ctx.hibernateParser.paramPrefix = cfg.hibernateParamPrefix
        ctx.hibernateParser.mixedPrefix = cfg.mixedPrefix
        ctx.hibernateParser.maxParseLength = cfg.maxParseLength
        ctx.jdbcParser.enabled = cfg.jdbcEnabled
        ctx.jdbcParser.maxParseLength = cfg.maxParseLength
        ctx.hibernateEnabled = cfg.hibernateEnabled
    }

    /**
     * BUG FIX（停止后仍捕获）：移除所有已挂载控制台上的 Filter。
     * 遍历 [attachedConsoles]，对每个 console 调用 removeMessageFilter 反射移除 Filter 代理，
     * 然后清空映射——确保 stop 后控制台不再向 buffer 喂 SQL。
     *
     * 改进：尝试所有候选 Filter 接口（不依赖存储的接口），防止因接口不匹配导致移除失败。
     * 失败时输出 WARN 日志（非 DEBUG），便于用户/开发者诊断。
     */
    open fun detachAllFilters() {
        for ((console, filterPair) in attachedConsoles) {
            consoleContexts.remove(console)
            val (filter, filterIface) = filterPair
            var removed = false
            // 优先尝试存储的接口
            try {
                console.javaClass.getMethod("removeMessageFilter", filterIface).invoke(console, filter)
                removed = true
                LOG.info("detachAllFilters: filter removed from ${console.javaClass.name}")
            } catch (_: Throwable) {
                // 存储的接口失败，尝试所有候选接口
                for (candidateName in FILTER_IFACE_CANDIDATES) {
                    try {
                        val candidate = Class.forName(candidateName, true, platformClassLoader)
                        console.javaClass.getMethod("removeMessageFilter", candidate).invoke(console, filter)
                        removed = true
                        LOG.info("detachAllFilters: filter removed via fallback $candidateName from ${console.javaClass.name}")
                        break
                    } catch (_: Throwable) {
                        // continue trying
                    }
                }
            }
            if (!removed) {
                LOG.warn("detachAllFilters: FAILED to remove filter from ${console.javaClass.name} (filter will remain active!)")
            }
        }
        attachedConsoles.clear()
        // T-LogPerf-03：挂载记录清空后缓存全部失效，下一轮重新全量扫描
        descriptorConsoleCache.clear()
    }

    /**
     * BUG FIX（停止后仍捕获）：移除 contentManagerListener（243/253 平台）。
     * stop 后若不移除，控制台事件（contentAdded）会触发 onConsoleAppeared 重启捕捉。
     */
    open fun detachContentListener() {
        if (!contentListenerRegistered) return
        val rcm = runContentManager ?: return
        try {
            rcm.javaClass.getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
            LOG.info("detachContentListener: contentManagerListener removed")
        } catch (t: Throwable) {
            LOG.debug("detachContentListener: removeContentManagerListener failed", t)
        }
        contentListenerRegistered = false
    }

    /**
     * F-44-6：实时日志的源码位置不再由本 IDE 线程取栈（取不到应用帧，恒为 null），
     * 改为由运行期 [SqlSourceInterceptor] 在应用线程捕获、打印标记行，
     * 经 [processConsoleLine] 解析为 sourceFile/sourceLine（见 [SqlSourceMarker] / [CallerSourceLocator]）。
     */

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
        private const val FAST_POLL_INTERVAL_MS = 500 // 快速轮询起步间隔——T-LogPerf-01：指数退避至常规间隔，不再 100ms 常驻
        private const val DESCRIPTOR_CACHE_MAX = 64 // descriptor→console 缓存上限（超限整体失效防死引用滞留）

        /** T-LogPerf-01：快速轮询指数退避（纯函数便于单测）——无主控制台每拍翻倍，上限=常规间隔；重置回落起步间隔。 */
        internal fun nextFastDelay(currentMs: Int, backoff: Boolean): Int =
            if (!backoff) FAST_POLL_INTERVAL_MS
            else (currentMs * 2).coerceAtMost(POLL_INTERVAL_MS)

        /** T-LogPerf-03：短路判定（纯函数便于单测）——已知 console 且已挂载时跳过重新扫描。 */
        internal fun shouldSkipRescan(cachedConsole: Any?, attachedConsoles: Collection<Any>): Boolean =
            cachedConsole != null && attachedConsoles.any { it === cachedConsole }

        /** T-LogPerf-02：反射 Method 缓存（热路径每拍轮询不再重复 getMethod 查找；找不到也缓存 null）。 */
        private val methodCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method?>()

        private fun cachedMethod(clazz: Class<*>, name: String): java.lang.reflect.Method? {
            val key = "${clazz.name}#$name"
            methodCache[key]?.let { return it }
            val method = try {
                clazz.getMethod(name)
            } catch (_: Throwable) {
                null
            }
            methodCache[key] = method
            return method
        }

        private fun descriptorsOf(rcm: Any): Array<*>? = try {
            val raw = cachedMethod(rcm.javaClass, "getAllDescriptors")?.invoke(rcm)
            when (raw) {
                is Array<*> -> raw
                is Collection<*> -> raw.toTypedArray()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }

        private fun componentOf(descriptor: Any?): Component? {
            if (descriptor == null) return null
            return try {
                cachedMethod(descriptor.javaClass, "getComponent")?.invoke(descriptor) as? Component
            } catch (_: Throwable) {
                null
            }
        }

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

/**
 * 每个已挂载控制台持有的一组解析器 + 暂存源码位置（F-44-6）。
 * 解析器跨行保持配对状态（MyBatis 的 Preparing/Parameters 跨多行），故须按 console 持久化。
 * T-LogPerf-04：配置快照在挂载/设置变更时经 [SqlLogConsoleBridge.refreshConsoleContexts] 写入，
 * 逐行不再调 configProvider 同步（字段保留供测试直接构造）。
 */
internal class BridgeLineContext(
    val myBatisParser: MyBatisLogParser,
    val hibernateParser: HibernateLogParser,
    val jdbcParser: JdbcTemplateLogParser,
    val configProvider: () -> ParserConfig,
    var pendingSource: Pair<String, Int>? = null,
    /** Hibernate 喂行开关（快照同步自 ParserConfig.hibernateEnabled）。 */
    var hibernateEnabled: Boolean = true,
    /**
     * F-44-7：最近来源标记队列（用于 COUNT 查询等 MP 内部触发的 SQL）。
     * MP 分页拦截器执行的 COUNT 查询不经过本拦截器，故无标记行；
     * 通过此队列回溯同一线程的上一个来源，关联到 COUNT 查询。
     */
    val recentSources: ArrayDeque<Pair<String, Int>> = ArrayDeque(8),
)

/**
 * 处理一行控制台文本（F-44-6）：
 *  1. 若为本插件源码标记（[SqlSourceInterceptor] 注入）→ 暂存，不喂解析器；
 *  2. 喂行到三个解析器（配置快照已由设置变更事件同步——T-LogPerf-04），取出已配对 SQL 条目；
 *  3. 附加耗时解析结果 + 暂存源码位置，写入 [LogSqlEntry]。
 * 纯函数式、无 IDE 依赖，可在单元测试中直接驱动（[SqlLogConsoleBridgeMarkerTest]）。
 */
internal fun processConsoleLine(
    line: String,
    ctx: BridgeLineContext,
    timePrefix: String?,
    syncBufferConfig: () -> Unit,
): List<LogSqlEntry> {
    // 1) 实时源码标记 → 暂存（不喂解析器，避免被当成 SQL）
    val marker = SqlSourceMarker.parse(line)
    if (marker != null) {
        ctx.pendingSource = marker
        // F-44-7：同时加入最近来源队列（用于 COUNT 查询回溯）
        ctx.recentSources.addLast(marker)
        if (ctx.recentSources.size > 8) ctx.recentSources.removeFirst()
        return emptyList()
    }
    // 2) 喂行到三个解析器（各自忽略不匹配的行；配置快照随设置变更事件刷新，逐行零同步成本）
    ctx.myBatisParser.feed(line)
    if (ctx.hibernateEnabled) ctx.hibernateParser.feed(line)
    ctx.jdbcParser.feed(line)
    val taken = ctx.myBatisParser.takeEntries() + ctx.hibernateParser.takeEntries() + ctx.jdbcParser.takeEntries()
    if (taken.isEmpty()) return emptyList()
    // 3) 耗时 + 源码位置（标记不可用时 sourceFile/sourceLine 为 null，回退表名搜索）
    // 解析策略：优先从日志行文本解析（ExecutionTimeParser），命中则统一填入；
    // 未命中时对每个条目尝试从 rawSql/restoredSql 解析（LogDurationParser，覆盖 druid/p6spy 格式）
    val lineDuration = ExecutionTimeParser.parse(line, timePrefix)
    var src = ctx.pendingSource
    if (src == null) {
        // F-44-7：无标记时，检测是否为 COUNT 查询 → 回溯最近来源
        val isCountQuery = taken.any { it.restoredSql.trimStart().startsWith("SELECT COUNT(", ignoreCase = true) }
        if (isCountQuery && ctx.recentSources.isNotEmpty()) {
            src = ctx.recentSources.last()
        }
    }
    val result = taken.map { e ->
        val duration = lineDuration ?: LogDurationParser.parse(e)
        e.copy(
            sourceFile = src?.first,
            sourceLine = src?.second ?: 0,
            executionTimeMs = duration ?: e.executionTimeMs,
        )
    }
    if (ctx.pendingSource != null) ctx.pendingSource = null
    return result
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
