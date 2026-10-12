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

    /** T-DialectQuoteWarn §十二：**按名**探测的单槽缓存（键＝项目身份 + 数据源名）。 */
    private class NamedUrlSlot(
        val project: java.lang.ref.WeakReference<Project>,
        val name: String,
        val url: String?,
        val atMs: Long,
    )

    /** T-PS5-03：TTL 上界（毫秒）。测试注入短窗驱动过期语义。 */
    internal var ttlMs: Long = 5_000L

    /** T-PS5-03 测试缝：时间源（默认系统时钟）。 */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /** T-PS5-03 测试缝：探测替身（默认 null = 走真实反射链）。 */
    internal var probeForTest: ((Project) -> String?)? = null

    /** T-DialectQuoteWarn §十二测试缝：按名探测替身（默认 null = 走真实反射链）。 */
    internal var probeByNameForTest: ((Project, String) -> String?)? = null

    /** T-PS5-03 测试缝：真实探测调用计数（断言 TTL 窗口内只探测一次）。 */
    internal val probeCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var slot: UrlSlot? = null

    @Volatile
    private var namedSlot: NamedUrlSlot? = null

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
     * T-DialectQuoteWarn §十二：取**指定名字**的数据源 URL（同名取首个非空）。
     *
     * 与 [firstUrl] 同一反射链（`DataSourceManager.getManagers` → `getDataSources()` →
     * `getName()` 匹配 → `getConnectionConfig().getUrl()`），差别只在**按名匹配**而非取首个。
     * 为什么必须按名：预览绑定的是「上次使用 / 已绑定」那一个数据源（面板 `bindstate.associated`），
     * 与项目内**首个**数据源可以不同且连接选项相反（本项目 demo：首个 jpa_demo、绑定 mpj_demo，
     * 两者 `ANSI_QUOTES` 状态相反）——拿 [firstUrl] 当绑定源会给出错误结论。
     *
     * `project == null`、`name` 空、探测失败（类缺失/反射异常/无同名数据源）→ null（调用方按
     * 「未知」处理，不假装知道连接选项）。TTL 窗口内同 (项目, 名) 命中缓存不再探测。
     */
    fun urlByName(project: Project?, name: String?): String? {
        if (project == null || name.isNullOrBlank()) return null
        val now = clockMs()
        namedSlot?.let { cached ->
            if (cached.project.get() === project && cached.name == name && now - cached.atMs <= ttlMs) {
                return cached.url
            }
        }
        val url = probeUrlByName(project, name)
        namedSlot = NamedUrlSlot(java.lang.ref.WeakReference(project), name, url, now)
        return url
    }

    /**
     * T-PS5-03：缓存失效（agent 会话建立/断开事件点调用）。
     * `project == null` 或与本槽项目同身份时清槽（单槽语义：异项目槽本就不可命中）。
     */
    fun invalidate(project: Project?) {
        val cached = slot
        if (cached != null && (project == null || cached.project.get() === project)) slot = null
        val named = namedSlot
        if (named != null && (project == null || named.project.get() === project)) namedSlot = null
    }

    /** T-PS5-03 测试缝：无条件清槽（用例隔离）。 */
    internal fun invalidateAllForTest() {
        slot = null
        namedSlot = null
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

    /** 按名真实探测（反射链，失败静默 null）；[probeByNameForTest] 注入时走替身。 */
    private fun probeUrlByName(project: Project, name: String): String? {
        probeCount.incrementAndGet()
        probeByNameForTest?.let { return it(project, name) }
        return runCatching {
            if (project.isDisposed) return null
            val managers = dataSourceManagers(project) ?: return null
            for (manager in managers) {
                val dataSources = invoke(manager, "getDataSources") as? List<*> ?: continue
                for (ds in dataSources.filterNotNull()) {
                    // 名字面量取法与 DataSourceSelector.boundConsoleDataSourceName 同源（LocalDataSource.getName）
                    val dsName = invoke(ds, "getName") as? String ?: continue
                    if (dsName != name) continue
                    val config = invoke(ds, "getConnectionConfig") ?: continue
                    val url = invoke(config, "getUrl") as? String
                    if (!url.isNullOrBlank()) return url
                }
            }
            null
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // T-DialectQuoteWarn §十二：URL → ANSI_QUOTES 三态
    // ------------------------------------------------------------------

    /**
     * 从 JDBC URL 判定是否**已声明** `sql_mode` 含 `ANSI_QUOTES`（T-DialectQuoteWarn §十二）。
     *
     * 返回值三态：
     *  - `true`  = URL 里 `sql_mode=` 的取值含 `ANSI_QUOTES` 项 → 可证明该连接双引号是标识符引号；
     *  - `false` = URL 非空但未声明（含完全没有 `sql_mode`）→ 已知未声明；
     *  - `null`  = URL 为空/取不到 → 未知。
     *
     * **只做「声明了」的正向证明**：URL 未声明**不等于**连接未开——生效 `sql_mode` = 服务端全局 +
     * 会话覆盖，URL 里的 `sessionVariables` 只是会话覆盖，服务端全局仍可能开着。故 `false` 只表达
     * 「URL 未声明」，调用方（规则）据此只能降级 WARN，不得据此报 ERROR。
     *
     * 解析口径：先 URL-decode（连接串里 `,`/`'` 常被编码为 `%2C`/`%27`），再定位每个 `sql_mode=`
     * 赋值，取其值（带引号则读到配对引号，否则读到 `&`/`;`/串尾），按 `,`/空白分词后不区分大小写
     * 比对 `ANSI_QUOTES`。匹配从严（只认 `sql_mode` 赋值内的项），宁可漏判也不误判为「已声明」。
     */
    fun ansiQuotesFromUrl(url: String?): Boolean? {
        if (url.isNullOrBlank()) return null
        val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        var from = 0
        while (true) {
            val m = SQL_MODE_ASSIGN.find(decoded, from) ?: return false
            val afterAssign = m.range.last + 1
            val rest = decoded.substring(afterAssign)
            val quote = rest.firstOrNull()?.takeIf { it == '\'' || it == '"' }
            val value = if (quote != null) {
                val end = rest.indexOf(quote, startIndex = 1).let { if (it < 0) rest.length else it }
                rest.substring(1, end)
            } else {
                val end = rest.indexOfFirst { it == '&' || it == ';' }
                if (end < 0) rest else rest.substring(0, end)
            }
            if (value.split(',', ' ', '\t').any { it.trim().equals("ANSI_QUOTES", ignoreCase = true) }) {
                return true
            }
            from = afterAssign
        }
    }

    /** `sql_mode=` 赋值定位（不区分大小写）。 */
    private val SQL_MODE_ASSIGN = Regex("(?i)sql_mode\\s*=\\s*")

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
