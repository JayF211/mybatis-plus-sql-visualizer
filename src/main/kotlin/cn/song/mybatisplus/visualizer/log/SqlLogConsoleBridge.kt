package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.project.Project
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * 运行控制台反射桥（T-M31-03）：`com.intellij.execution.*`（RunContentManager/ConsoleView）
 * 不在插件编译 classpath（IPGP 发行版裁剪 execution 模块），运行期由真实 IDE 提供——
 * 与既有 `execute/` 数据库链路同一隔离策略（build.gradle.kts 注释约定）。
 *
 * 全部调用经反射 + Proxy，任一步失败即优雅降级（返回 false / 静默跳过），不抛异常。
 * 面板可见性由 [SqlLogManager.attach] 驱动；新 console（每次 Run）经 ContentManagerListener 增量挂载。
 */
class SqlLogConsoleBridge(
    private val buffer: SqlLogEntryBuffer,
) {

    private var runContentManager: Any? = null
    private val attachedConsoles = IdentityHashMap<Any, Boolean>()
    private val contentListener = object : ContentManagerListener {
        override fun contentAdded(event: ContentManagerEvent) {
            scanComponent(event.content.component)
        }
    }

    /** 反射取 RunContentManager 实例并挂载现有 console；环境缺 execution API 时返回 false。 */
    fun attach(project: Project): Boolean {
        if (runContentManager != null) return true
        val rcm = runContentManagerOrNull(project) ?: return false
        runContentManager = rcm
        attachContentListener(rcm)
        scanAll()
        return true
    }

    /** 移除挂载（项目关闭/面板销毁时）。 */
    fun detach() {
        val rcm = runContentManager ?: return
        try {
            rcm.javaClass.getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
        } catch (_: Throwable) {
            // 忽略：随 console 生命周期自然失效
        }
        runContentManager = null
        attachedConsoles.clear()
    }

    private fun attachContentListener(rcm: Any) {
        try {
            rcm.javaClass.getMethod("addContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
        } catch (_: Throwable) {
            // 新 console 增量挂载不可用：仅覆盖挂载时已存在的 console
        }
    }

    /** 遍历 RunContentManager 全部运行描述符，对每个 console 挂载 filter（幂等）。 */
    private fun scanAll() {
        val rcm = runContentManager ?: return
        val descriptors = try {
            rcm.javaClass.getMethod("getAllDescriptors").invoke(rcm) as? Array<*>
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
    }

    /** 组件树中查找 ConsoleView 实现并挂载（供 contentAdded 与面板扫描复用）。 */
    fun scanComponent(component: Component?) {
        findConsoleView(component)?.let { attachFilter(it) }
    }

    private fun attachFilter(console: Any) {
        if (attachedConsoles.containsKey(console)) return
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
        } catch (_: Throwable) {
            attachedConsoles.remove(console) // 挂载失败允许下次重试
        }
    }

    companion object {
        private const val RCM_CLASS_NAME = "com.intellij.execution.RunContentManager"
        private const val FILTER_IFACE_NAME = "com.intellij.execution.ui.ConsoleView\$Filter"
        private val CONTENT_LISTENER_CLASS = ContentManagerListener::class.java

        /** 执行体系 API 在当前环境是否可用（测试环境/异常环境为 false）。 */
        fun isConsoleApiAvailable(): Boolean = try {
            Class.forName(RCM_CLASS_NAME)
            true
        } catch (_: Throwable) {
            false
        }

        private fun runContentManagerOrNull(project: Project): Any? = try {
            Class.forName(RCM_CLASS_NAME)
                .getMethod("getInstance", Project::class.java)
                .invoke(null, project)
        } catch (_: Throwable) {
            null
        }
    }
}

/** 组件树中查找实现 `com.intellij.execution.ui.ConsoleView` 接口的组件（接口名匹配，无编译期引用）。 */
internal fun findConsoleView(component: Component?): Any? {
    if (component == null) return null
    if (component.javaClass.interfaces.any { it.name == "com.intellij.execution.ui.ConsoleView" }) return component
    if (component is Container) {
        for (child in component.components) {
            findConsoleView(child)?.let { return it }
        }
    }
    return null
}
