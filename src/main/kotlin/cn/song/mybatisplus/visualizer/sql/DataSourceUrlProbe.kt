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
 */
internal object DataSourceUrlProbe {

    private val LOG = Logger.getInstance(DataSourceUrlProbe::class.java)

    /**
     * 取项目数据源列表中首个非空 URL（多数据源取列表序首个——DataSourceManager 列表序即
     * 用户面板展示序；尚无「最近使用」元数据可依，一期不深究）。
     * 探测失败（类缺失/反射异常/无数据源）→ null。
     */
    fun firstUrl(project: Project?): String? = runCatching {
        if (project == null || project.isDisposed) return null
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
