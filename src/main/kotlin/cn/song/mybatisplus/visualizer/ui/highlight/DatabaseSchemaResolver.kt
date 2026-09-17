package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 数据源 schema 解析（M35，方案 B：反射隔离，Community 兼容）。
 *
 * 当 IntelliJ IDEA Ultimate / DataGrip 加载了 `com.intellij.database` 插件时，
 * 从已配置的数据源读取指定表的真实字段注释（DB COMMENT），供补全合并——
 * 数据源注释比实体类 `@TableName/@ApiModelProperty` 注释更权威（贴近线上 DDL）。
 *
 * 全反射访问，不 import `com.intellij.database` 任何类——Community 运行时该插件不存在
 * 也能编译/加载（与 [ExecuteSqlSupport] 同一隔离策略）。插件可用性经 `Class.forName`
 * 探测标记类判定，不可用时空转（补全不展示数据源注释，仍用实体注释）。
 *
 * 遍历路径：`DataSourceManager.getManagers(project)` → `getDataSources()` → 每个
 * `DbDataSource.getDasObject()` → `DasModel.traverser().toList()` 筛 `DasTable` →
 * 按名匹配 → `DasTable.getDasChildren(ObjectKind.COLUMN)` → 每列 `getName()/getComment()`。
 */
object DatabaseSchemaResolver {

    private val LOG = Logger.getInstance(DatabaseSchemaResolver::class.java)

    /** database 插件标记类（探测用，M17 同 ExecuteSqlSupport）。 */
    private const val MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager"

    /** DasTable / DasColumn / DasObject 的二进制名（ObjectKind 判类型用）。 */
    private const val DAS_TABLE = "com.intellij.database.model.DasTable"
    private const val DAS_COLUMN = "com.intellij.database.model.DasColumn"
    private const val DAS_OBJECT = "com.intellij.database.model.DasObject"
    private const val OBJECT_KIND = "com.intellij.database.model.ObjectKind"

    // T-Embed-10 真机二轮：Method 一律从「声明接口/类」获取，禁止 obj.javaClass.getMethod——
    // 实现类（LocalDataSource 等）在 database-plugin 内部包（非导出），跨插件 classloader 反射
    // invoke 抛 IllegalAccessException（15:54 沙盒日志实证）。getManagers 从接口拿所以一直成功。
    private const val DAS_DATA_SOURCE = "com.intellij.database.model.DasDataSource"
    private const val DAS_MODEL = "com.intellij.database.model.DasModel"
    private const val DAS_TYPED_OBJECT = "com.intellij.database.model.DasTypedObject"
    private const val DATA_TYPE = "com.intellij.database.model.DataType"

    /** 列名 → 数据源注释（表名匹配不到返回空 Map，调用方回落实体注释）。 */
    fun columnComments(project: Project, tableName: String): Map<String, String> =
        columnMeta(project, tableName).mapValues { it.value.comment ?: "" }

    /**
     * T-Perf-09：仅读缓存的列注释（补全 autopopup 热路径专用）——命中返回缓存值；
     * 未命中返回空 Map（调用方回落实体注释），**不触发数据源全量遍历**。
     * 缓存由 JOIN 富化后台预取（DiagramSchemaEnricher.enrich）或首次 [tableMeta] 调用填充。
     */
    fun columnCommentsCached(project: Project?, tableName: String?): Map<String, String> {
        if (project == null || tableName.isNullOrBlank()) return emptyMap()
        if (!isDatabaseAvailable()) return emptyMap()
        val dataSources = dataSources(project) ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        synchronized(metaCache) {
            for (ds in dataSources) {
                metaCache[ds]?.get(tableName.lowercase())?.let { meta ->
                    // T-TestAssertReal：收录条件放宽后仅非空标记列会带空注释进缓存——
                    // 补全侧只消费 comment，过滤 blank 保持放宽前消费语义
                    meta.columns.forEach { (col, m) ->
                        m.comment?.takeIf { it.isNotBlank() }?.let { out.putIfAbsent(col, it) }
                    }
                }
            }
        }
        return out
    }

    /**
     * 列名 → 列元数据（注释 + DB 真实类型串，T-Embed-02）。
     *
     * 类型串 = `DasTypedObject.getDataType().getSpecification()`（如 `int(11)` / `varchar(64)`，
     * javap 实证 DataType.getSpecification 即 Database「显示图」右对齐类型的渲染来源）。
     * 表名匹配不到返回空 Map，调用方回落实体推断值。
     */
    fun columnMeta(project: Project, tableName: String): Map<String, ColumnMeta> =
        tableMeta(project, tableName).columns

    /**
     * T-Perf-09：TableMeta 缓存——键 = (dataSource 对象弱引用, tableName 小写)。
     * 数据源全量遍历（DasModel.traverser().toList()）代价随 schema 规模线性，JOIN 预览
     * 每打开一次、每个 JOIN 表各触发一次；命中后零遍历。数据源删除后条目随弱键回收；
     * 表匹配不到（通常=未连接/introspection 未完成）走 [negativeCache] 负命中缓存
     * （T-MetaCheapSource：TTL + DDL 失效双保险，见下）。
     *
     * T-DdlCacheInvalidate：DDL/事务提交语句执行提交前经 execute/MetadataInvalidator 全量失效
     * （本缓存与负命中缓存同为此前盘点出的自持 schema 面——ALTER TABLE 后补全/JOIN 富化拿旧结构
     * 是正确性破口，失效走 O(1) 引用清空）。
     */
    private val metaCache =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, MutableMap<String, TableMeta>>())

    /** T-DdlCacheInvalidate：正缓存全量失效（引用置空语义，下次取数零遍历回填）。 */
    fun invalidateAll() {
        synchronized(metaCache) { metaCache.clear() }
    }

    // ----------
    // T-MetaCheapSource（O-21 T-MC-01）：负命中缓存。
    // agent 场景同一幻觉表名会在一次会话内反复尝试，此前每次未命中都全量遍历 DasModel
    // （多数据源 × 大模型时一次调用可到秒级）。键 = (dataSource 弱引用, 表名小写)，值 = 记入时间戳；
    // TTL 30s 用时间戳惰性判定（不用定时器）防长会话陈旧——建表后 30s 内的误报由 MetadataInvalidator
    // 失效兜底（登记 label 见 execute/MetadataInvalidator init 块，DDL/事务后与正缓存一起清）。
    // 弱键同 metaCache 口径：数据源删除后条目随弱键回收。
    //
    // T-MC-02（表名索引：一次遍历建 name → DasTable 索引，把「逐名全扫」变「一次扫描多次查询」）
    // 为可选件，按计划 P3 自律**默认不启用**——真机数据显示 T-MC-01 后仍慢才引入，避免过度工程。
    // ----------

    /** 负缓存 TTL（毫秒）：时间戳惰性过期，过期即重扫，无常驻定时器。 */
    internal const val NEGATIVE_TTL_MS = 30_000L

    /** 测试专用 TTL 旋钮（生产恒等于 [NEGATIVE_TTL_MS]；调短/归零验证过期语义）。 */
    @Volatile internal var negativeTtlMs: Long = NEGATIVE_TTL_MS

    private val negativeCache =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, MutableMap<String, Long>>())

    /** 负缓存清空（MetadataInvalidator 登记入口；引用置空语义，幂等无害）。 */
    internal fun clearNegativeCache() {
        synchronized(negativeCache) { negativeCache.clear() }
    }

    /** 负命中是否在 TTL 内（过期条目惰性移除，不留定时器）。 */
    private fun isNegativeCached(ds: Any, cacheKey: String): Boolean {
        val stamped = synchronized(negativeCache) { negativeCache[ds]?.get(cacheKey) } ?: return false
        // 严格小于：TTL=0 语义=立即过期（同一毫秒内也不新鲜）——负缓存 TTL 测试的确定性
        // 依赖此边界（<= 形态下同毫秒调用判新鲜，分片负载下曾致 flake expected 2 was 1）
        if (System.currentTimeMillis() - stamped < negativeTtlMs) return true
        synchronized(negativeCache) { negativeCache[ds]?.remove(cacheKey) }
        return false
    }

    /**
     * 表级元数据（表注释 + 列元数据，T-Embed-09 一次遍历同时取）。
     *
     * @param comment 数据源表 COMMENT（`DasTable.getComment()`，null/空白 = 数据库没写，调用方回落实体注释）
     */
    fun tableMeta(project: Project, tableName: String): TableMeta {
        if (tableName.isBlank()) return TableMeta(null, emptyMap())
        if (!isDatabaseAvailable()) return TableMeta(null, emptyMap())
        return ReadAction.compute<TableMeta, Throwable> { tryCompute(project, tableName) }
    }

    /** 数据源侧元数据（表注释为 null 时该维度缺失，调用方逐维度回落）。 */
    data class TableMeta(val comment: String?, val columns: Map<String, ColumnMeta>)

    /** 数据源侧列元数据（均为 null 时表示该维度缺失，调用方逐维度回落）。 */
    data class ColumnMeta(val comment: String?, val typeSpec: String?, val notNull: Boolean? = null)

    // T-TestAssertReal：notNull 取自 DasTypedObject.isNotNull()（javap 实证，DasColumn extends
    // DasTypedObject）。收录条件放宽为「comment/typeSpec 非空 或 notNull==true」后，仅非空标记
    // 列（comment/typeSpec 双空）也会进 Map——只消费 comment 的调用点（columnCommentsCached）
    // 自行过滤 blank 保持放宽前语义。
    private fun tryCompute(project: Project, tableName: String): TableMeta {
        val dataSources = dataSources(project) ?: return TableMeta(null, emptyMap()).also {
            LOG.warn("DatabaseSchemaResolver: dataSources(project) reflection failed, table=$tableName")
        }
        if (dataSources.isEmpty()) {
            LOG.info("DatabaseSchemaResolver: no data sources configured, table=$tableName")
            return TableMeta(null, emptyMap())
        }
        return resolveFromSources(tableName, dataSources)
    }

    /**
     * 按数据源逐个解析：正缓存命中 → 负缓存 TTL 内跳过 → 全扫（扫描探针可注入供表驱动测试，
     * 无 database 插件的单测环境走不了真实反射链；生产 scanProbe 传 null 走既有反射路径，
     * 正命中回填与返回行为与 T-Perf-09 原实现逐字节一致）。
     */
    internal fun resolveFromSources(
        tableName: String,
        sources: List<Any>,
        scanProbe: ((Any, String) -> TableMeta)? = null,
    ): TableMeta {
        val scan: (Any, String) -> TableMeta =
            scanProbe ?: { ds, name -> collectFromDataSource(ds, name, Class.forName(DAS_TABLE)) }
        val cacheKey = tableName.lowercase()
        for (ds in sources) {
            // T-Perf-09：缓存命中直接返回（同一数据源同一表零遍历）
            val cached = synchronized(metaCache) { metaCache[ds]?.get(cacheKey) }
            if (cached != null) return cached
            // T-MetaCheapSource：TTL 内负命中跳过该数据源全扫（introspection 完成后由 DDL
            // 失效/TTL 过期两条路放开，见 negativeCache 注释）
            if (isNegativeCached(ds, cacheKey)) continue
            val found = scan(ds, tableName)
            if (found.columns.isNotEmpty() || found.comment != null) {
                synchronized(metaCache) {
                    metaCache.getOrPut(ds) { HashMap() }[cacheKey] = found
                }
                return found
            }
            // 该数据源确认没有此表：记负缓存（时间戳，TTL 内同表查询不再全扫）
            synchronized(negativeCache) {
                negativeCache.getOrPut(ds) { HashMap() }[cacheKey] = System.currentTimeMillis()
            }
        }
        // 表在所有数据源都找不到（通常=未连接/introspection 未完成/表不在当前库）
        LOG.info("DatabaseSchemaResolver: table=$tableName not found in ${sources.size} data sources")
        return TableMeta(null, emptyMap())
    }

    /** database 插件是否可用（Ultimate/DataGrip）。 */
    private fun isDatabaseAvailable(): Boolean = runCatching {
        Class.forName(MANAGER_CLASS)
        true
    }.getOrDefault(false)

    /** 获取所有数据源（反射 `DataSourceManager.getManagers(project)` → `getDataSources()`）。 */
    private fun dataSources(project: Project): List<Any>? = runCatching {
        val managerClass = Class.forName(MANAGER_CLASS)
        val managersMethod = managerClass.getMethod("getManagers", Project::class.java)
        val managers = managersMethod.invoke(null, project) as? List<*> ?: return@runCatching emptyList()
        val dsList = mutableListOf<Any>()
        for (m in managers) {
            if (m == null) continue
            val getDataSources = managerClass.getMethod("getDataSources")
            val list = getDataSources.invoke(m) as? List<*> ?: continue
            dsList.addAll(list.mapNotNull { it })
        }
        dsList
    }.getOrNull()

    /** 从单个数据源收集匹配表的表注释 + 列元数据（遍历 DasModel.traverser().toList()）。 */
    private fun collectFromDataSource(ds: Any, tableName: String, dasTableClass: Class<*>): TableMeta {
        var tableComment: String? = null
        val out = linkedMapOf<String, ColumnMeta>()
        // T-Embed-10.5 探针：B 路径 locateDbTables 直调同模型命中而本链 not found——
        // 分辨 elements 空遍历 / DasTable 筛不出 / 名称不匹配三种情形
        var scannedTables = 0
        var hit = false
        runCatching {
            val dasObjClass = Class.forName(DAS_OBJECT)
            val dasColumnClass = Class.forName(DAS_COLUMN)
            // T-Embed-09 真机修复：243 字节码实证 RawDataSource/DbDataSource 均无 getDasObject()，
            // getModel() 声明在父接口 DasDataSource（B 路径 locateDbTables 直调 ds.model 同源）——
            // 旧链 getDasObject().getModel() 反射 NoSuchMethodException 被吞 → 全量静默回落
            val model = Class.forName(DAS_DATA_SOURCE).getMethod("getModel").invoke(ds)
                ?: return TableMeta(null, emptyMap())
            val traverser = Class.forName(DAS_MODEL).getMethod("traverser").invoke(model)
                ?: return TableMeta(null, emptyMap())
            // 16:09 沙盒日志实证：DasModel.traverser() 返回 JBTreeTraverser（extends
            // FilteredTraverserBase，非 JBIterable 子类）——对 JBIterable 取 toList invoke 抛
            // IllegalArgumentException: object is not an instance of declaring class。
            // FilteredTraverserBase implements java.lang.Iterable，iterator() 为其 public final——
            // 用 Iterable/Iterator 接口方法手动收集（= Kotlin 直调 toList 扩展同语义）
            val elements = try {
                // java.util.Iterator 为 JDK 接口，cast 后 hasNext/next 直调（避免再反射一层）
                val iterator = java.lang.Iterable::class.java.getMethod("iterator")
                    .invoke(traverser) as java.util.Iterator<*>
                val out = mutableListOf<Any?>()
                while (iterator.hasNext()) out.add(iterator.next())
                out
            } catch (e: Throwable) {
                LOG.warn("DatabaseSchemaResolver: traverse elements failed", e)
                return TableMeta(null, emptyMap())
            }
            val objectKindClass = Class.forName(OBJECT_KIND)
            val columnKind = objectKindClass.enumConstants?.firstOrNull { (it as Enum<*>).name == "COLUMN" }
                ?: return TableMeta(null, emptyMap())
            val getNameM = dasObjClass.getMethod("getName")
            val getCommentM = dasObjClass.getMethod("getComment")
            val getChildrenM = dasObjClass.getMethod("getDasChildren", objectKindClass)

            for (element in elements) {
                if (element == null || !dasTableClass.isInstance(element)) continue
                scannedTables++
                val tName = getNameM.invoke(element) as? String ?: continue
                if (!tName.equals(tableName, ignoreCase = true)) continue
                hit = true
                // 表级 COMMENT 与列遍历同一元素读取（blank 视为数据库没写，保留 null 供调用方回落）
                tableComment = (getCommentM.invoke(element) as? String)?.takeIf { it.isNotBlank() }
                val columns = getChildrenM.invoke(element, columnKind) as? Iterable<*> ?: continue
                for (col in columns) {
                    if (col == null || !dasColumnClass.isInstance(col)) continue
                    val cName = getNameM.invoke(col) as? String ?: continue
                    if (cName.isBlank()) continue
                    val comment = getCommentM.invoke(col) as? String
                    // DasTypedObject.getDataType().getSpecification()（反射；列无类型信息时为 null）
                    val typeSpec = runCatching {
                        val dataType = Class.forName(DAS_TYPED_OBJECT).getMethod("getDataType").invoke(col)
                        dataType?.let { Class.forName(DATA_TYPE).getMethod("getSpecification").invoke(it) } as? String
                    }.getOrNull()
                    // T-TestAssertReal：DasTypedObject.isNotNull()（javap 实证声明于 DasTypedObject 接口，
                    // DasColumn extends DasTypedObject）——Method 一律从声明接口获取（T-Embed-10 跨
                    // classloader 教训，禁止 obj.javaClass.getMethod）；反射失败为 null，不进非空清单
                    val notNull = runCatching {
                        Class.forName(DAS_TYPED_OBJECT).getMethod("isNotNull").invoke(col) as? Boolean
                    }.getOrNull()
                    // 收录条件放宽：clean DDL（无注释无类型串）的 NOT NULL 列此前会被过滤掉
                    if (!comment.isNullOrBlank() || !typeSpec.isNullOrBlank() || notNull == true) {
                        out[cName] = ColumnMeta(
                            comment = comment?.takeIf { it.isNotBlank() },
                            typeSpec = typeSpec?.takeIf { it.isNotBlank() },
                            notNull = notNull,
                        )
                    }
                }
            }
        }.onFailure { e ->
            // 升 WARN+堆栈：真机排障依赖此行（仅消息无法定位炸点，16:09 日志教训）
            LOG.warn("DatabaseSchemaResolver.collectFromDataSource failed", e)
        }
        LOG.info(
            "DatabaseSchemaResolver scan: table=$tableName, tablesScanned=$scannedTables, " +
                "hit=$hit, foundColumns=${out.size}, tableComment=${tableComment != null}",
        )
        return TableMeta(tableComment, out)
    }
}