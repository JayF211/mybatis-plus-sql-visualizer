package cn.song.mybatisplus.visualizer.sql

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger

/**
 * T-DialectAutoInfer：IDE 数据源 URL 探测（DatabaseTools API，Ultimate-only）。
 *
 * 链路（262.3 javap 实锤，plugins/DatabaseTools/lib/modules/intellij.database*.jar）：
 *  - `DataSourceManager.getManagers(project)` → 各 manager `getDataSources()` → RawDataSource
 *  - `RawDataSource` 经 `DasDataSource.getConnectionConfig()` 得 `RawConnectionConfig.getUrl()`
 *  全程反射（IPGP 裁剪/Community 无 DatabaseTools 时类缺失 → runCatching 返回 null，静默降级）。
 *  只读探测，无 EDT 约束（与 [cn.song.mybatisplus.visualizer.execute.DialectPreMapping] 的
 *  读探测同类）；调用方通常已在后台线程。
 *
 * T-PS5-03：结果加**单槽 TTL 缓存**——方言智能推断开启后单次预览会多次走
 * [DialectRegistry.effective]（报告实测约 7 次），每次探测都是一串反射调用；URL 本身是
 * 低频变更数据，TTL 窗口内复用即可。失效双保险：
 *  1. [invalidate]——agent 会话建立/断开事件点清（[cn.song.mybatisplus.visualizer.log.agent.AgentManager]）；
 *  2. TTL 上界（默认 5s）——数据源编辑后**至多一个 TTL 窗口内方言滞后**（可接受：预览是只读展示）。
 * 槽位持项目**弱引用**（不因缓存滞留已关闭项目），项目身份不同即重探。
 */
internal object DataSourceUrlProbe {

    private val LOG = Logger.getInstance(DataSourceUrlProbe::class.java)

    /** 单槽缓存条目（项目弱引用 + 结果 + 时间戳）。 */
    private class UrlSlot(
        val project: java.lang.ref.WeakReference<Project>,
        val url: String?,
        val atMs: Long,
    )

    /** T-PS5-03：TTL 上界（毫秒）。测试注入短窗驱动过期语义。 */
    internal var ttlMs: Long = 5_000L

    /** T-PS5-03 测试缝：时间源（默认系统时钟）。 */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /** T-PS5-03 测试缝：探测替身（默认 null = 走真实反射链）。 */
    internal var probeForTest: ((Project) -> String?)? = null

    /** T-PS5-03 测试缝：真实探测调用计数（断言 TTL 窗口内只探测一次）。 */
    internal val probeCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var slot: UrlSlot? = null

    /**
     * 取项目数据源列表中首个非空 URL（多数据源取列表序首个——DataSourceManager 列表序即
     * 用户面板展示序；尚无「最近使用」元数据可依，一期不深究）。
     * 探测失败（类缺失/反射异常/无数据源）→ null。TTL 窗口内命中缓存不再探测。
     */
    fun firstUrl(project: Project?): String? {
        if (project == null) return null
        val now = clockMs()
        slot?.let { cached ->
            if (cached.project.get() === project && now - cached.atMs <= ttlMs) return cached.url
        }
        val url = probeFirstUrl(project)
        slot = UrlSlot(java.lang.ref.WeakReference(project), url, now)
        return url
    }

    /**
     * T-PS5-03：缓存失效（agent 会话建立/断开事件点调用）。
     * `project == null` 或与本槽项目同身份时清槽（单槽语义：异项目槽本就不可命中）。
     */
    fun invalidate(project: Project?) {
        val cached = slot ?: return
        if (project == null || cached.project.get() === project) slot = null
    }

    /** T-PS5-03 测试缝：无条件清槽（用例隔离）。 */
    internal fun invalidateAllForTest() {
        slot = null
    }

    /** 真实探测（反射链，失败静默 null）；[probeForTest] 注入时走替身。 */
    private fun probeFirstUrl(project: Project): String? {
        probeCount.incrementAndGet()
        probeForTest?.let { return it(project) }
        return runCatching {
            if (project.isDisposed) return null
            val managers = dataSourceManagers(project) ?: return null
            for (manager in managers) {
                // manager 为反射拿到的任意类型（Any），统一走反射取 getDataSources()
                val dataSources = invoke(manager, "getDataSources") as? List<*> ?: continue
                for (ds in dataSources.filterNotNull()) {
                    val config = invoke(ds, "getConnectionConfig") ?: continue
                    val url = invoke(config, "getUrl") as? String
                    if (!url.isNullOrBlank()) return url
                }
            }
            null
        }.getOrNull()
    }

    /** `DataSourceManager.getManagers(project)` 反射调用；类缺失/异常 → null。 */
    private fun dataSourceManagers(project: Project): List<Any>? {
        val managerClass = runCatching {
            Class.forName("com.intellij.database.psi.DataSourceManager", true, DataSourceUrlProbe::class.java.classLoader)
        }.getOrNull() ?: return null
        val managers = runCatching {
            managerClass.getMethod("getManagers", Project::class.java).invoke(null, project)
        }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return managers as? List<Any>
    }

    private fun invoke(target: Any, method: String): Any? = runCatching {
        target.javaClass.getMethod(method).invoke(target)
    }.onFailure {
        LOG.debug("DataSourceUrlProbe.$method 反射失败（${target.javaClass.name}）", it)
    }.getOrNull()
}
