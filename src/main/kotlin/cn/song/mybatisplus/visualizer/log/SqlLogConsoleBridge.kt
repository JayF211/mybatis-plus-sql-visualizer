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
 */
class SqlLogConsoleBridge(
    private val buffer: SqlLogEntryBuffer,
) {

    private var runContentManager: Any? = null
    private val attachedConsoles = IdentityHashMap<Any, Boolean>()
    private val contentListener = object : ContentManagerListener {
        override fun contentAdded(event: ContentManagerEvent) {
            val component = event.content.component
            LOG.debug("contentAdded: ${component.javaClass.name}")
            scanComponent(component)
        }
    }
    private var pollTimer: Timer? = null

    /** 反射取 RunContentManager 实例（双包名候选）并挂载现有 console；环境缺 execution API 时返回 false。 */
    fun attach(project: Project): Boolean {
        if (runContentManager != null) {
            LOG.debug("attach: already attached")
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
        LOG.info("attach: RCM=${rcm.javaClass.name}, consolesAttached=${attachedConsoles.size}")
        return true
    }

    /** 轮询兜底（262 无 contentAdded 监听接口；2s 周期 scanAll，幂等——新 console 出现即挂载）。 */
    fun startPolling() {
        if (pollTimer != null) return
        pollTimer = Timer(POLL_INTERVAL_MS) { scanAll() }.apply { isRepeats = true; start() }
        LOG.debug("startPolling: 2s scan fallback started")
    }

    fun stopPolling() {
        pollTimer?.stop()
        pollTimer = null
    }

    /** 移除挂载（项目关闭/面板销毁时）。 */
    fun detach() {
        stopPolling()
        val rcm = runContentManager ?: return
        try {
            rcm.javaClass.getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
        } catch (t: Throwable) {
            LOG.debug("detach: removeContentManagerListener failed", t)
        }
        runContentManager = null
        attachedConsoles.clear()
    }

    private fun attachContentListener(rcm: Any) {
        try {
            rcm.javaClass.getMethod("addContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
            LOG.debug("attach: contentAdded listener registered on ${rcm.javaClass.name}")
        } catch (t: Throwable) {
            // 2026.2 接口无此方法（不 extends ContentManager）→ 轮询兜底
            LOG.debug("attach: addContentManagerListener unavailable on ${rcm.javaClass.name} — polling fallback covers new consoles", t)
        }
    }

    /** 遍历 RunContentManager 全部运行描述符，对每个 console 挂载 filter（幂等）。 */
    private fun scanAll() {
        val rcm = runContentManager ?: return
        val descriptors = try {
            rcm.javaClass.getMethod("getAllDescriptors").invoke(rcm) as? Array<*>
        } catch (t: Throwable) {
            // 262 的接口为 getAllDescriptors() 返回 List —— 兼容两种返回形态
            try {
                val result = rcm.javaClass.getMethod("getAllDescriptors").invoke(rcm)
                (result as? Collection<*>)?.toTypedArray()
            } catch (t2: Throwable) {
                LOG.warn("scanAll: getAllDescriptors failed on ${rcm.javaClass.name}", t2)
                null
            }
        } ?: return
        LOG.debug("scanAll: ${descriptors.size} run descriptors")
        for (descriptor in descriptors) {
            val component = try {
                descriptor?.javaClass?.getMethod("getComponent")?.invoke(descriptor) as? Component
            } catch (t: Throwable) {
                LOG.warn("scanAll: getComponent failed on ${descriptor?.javaClass?.name}", t)
                null
            } ?: continue
            LOG.debug("scanAll: descriptor component=${component.javaClass.name}")
            scanComponent(component)
        }
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
        if (attachedConsoles.containsKey(console)) {
            LOG.debug("attachFilter: already attached ${console.javaClass.name}")
            return
        }
        attachedConsoles[console] = true
        try {
            val filterIface = Class.forName(FILTER_IFACE_NAME)
            val parser = MyBatisLogParser()
            val filter = Proxy.newProxyInstance(
                filterIface.classLoader,
                arrayOf(filterIface),
                LogFilterProxyHandler { line ->
                    parser.feed(line)
                    parser.takeEntries().forEach { buffer.addEntry(it) }
                },
            )
            console.javaClass.getMethod("addMessageFilter", filterIface).invoke(console, filter)
            LOG.info("attachFilter: filter attached to ${console.javaClass.name}")
        } catch (t: Throwable) {
            attachedConsoles.remove(console) // 挂载失败允许下次重试
            LOG.warn("attachFilter: failed on ${console.javaClass.name}", t)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogConsoleBridge::class.java)

        /** 双包名候选：2024.3/2025.3 在 execution 包，2026.2 移到 execution.ui 包（实证）。 */
        private val RCM_CLASS_CANDIDATES = listOf(
            "com.intellij.execution.RunContentManager",
            "com.intellij.execution.ui.RunContentManager",
        )
        private const val FILTER_IFACE_NAME = "com.intellij.execution.ui.ConsoleView\$Filter"
        private val CONTENT_LISTENER_CLASS = ContentManagerListener::class.java
        private const val POLL_INTERVAL_MS = 2000

        /** 执行体系 API 在当前环境是否可用（测试环境/SDK 发行版为 false）。 */
        fun isConsoleApiAvailable(): Boolean = RCM_CLASS_CANDIDATES.any {
            try {
                Class.forName(it)
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun runContentManagerOrNull(project: Project): Any? {
            for (className in RCM_CLASS_CANDIDATES) {
                try {
                    val clazz = Class.forName(className)
                    val rcm = clazz.getMethod("getInstance", Project::class.java).invoke(null, project)
                    if (rcm != null) {
                        LOG.info("attach: RCM resolved via $className")
                        return rcm
                    }
                } catch (t: Throwable) {
                    LOG.debug("RCM candidate $className unavailable", t)
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
