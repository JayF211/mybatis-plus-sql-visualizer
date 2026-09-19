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
 * 遍历路径：`DataSourceManager.getManagers(project)` → `getDataSources()` → 每个数据源的
 * `DasDataSource.getModel()` → `DasModel.traverser()` 迭代筛 `DasTable` → 按名匹配 →
 * `DasObject.getDasChildren(ObjectKind.COLUMN)` → 每列 `getName()/getComment()/getDataType()`。
 *
 * 消费面：JOIN 图富化（[cn.song.mybatisplus.visualizer.ui.diagram.DiagramSchemaEnricher]）、
 * EXPLAIN 目标表结构、MCP schema 工具，以及 **T-DbSchemaCompletion 的补全候选**
 * （[columnsCached] 只读缓存 + [prefetchTables] 后台预热——数据源列不再只当注释用，
 * 库里存在而实体类没有的列也进补全候选）。
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
     * T-DbSchemaCompletion：多表 schema 列并集（**只读缓存，不触发遍历**）。
     *
     * 补全 autopopup 热路径专用（T-Perf-09 红线：候选生成不做数据源遍历）——命中返回缓存值，
     * 未命中（或数据源未连接/未预热）返回空 Map，调用方回落到实体列。缓存由 [prefetchTables]
     * 在预览打开、控制台就绪时后台填充（JOIN 富化路径经 [tableMeta] 同源填充）。
     *
     * 多表并集按列名去重（同名跨表列取首次命中）：补全上下文是「本文档涉及的表」的扁平视图，
     * 跨表同名列不细分（别名限定补全见计划 §六「明确不做」）。
     */
    fun columnsCached(project: Project?, tableNames: Collection<String>): Map<String, ColumnMeta> {
        if (project == null || tableNames.isEmpty()) return emptyMap()
        if (!isDatabaseAvailable()) return emptyMap()
        val dataSources = dataSources(project) ?: return emptyMap()
        val out = linkedMapOf<String, ColumnMeta>()
        synchronized(metaCache) {
            for (name in tableNames) {
                if (name.isBlank()) continue
                val key = name.lowercase()
                for (ds in dataSources) {
                    metaCache[ds]?.get(key)?.columns?.forEach { (col, meta) -> out.putIfAbsent(col, meta) }
                }
            }
        }
        return out
    }

    /**
     * T-DbSchemaCompletion：预热若干表的 schema 缓存（供 [columnsCached] 零遍历读取）。
     *
     * **调用方负责线程**（BGT 执行；内部 [tableMeta] 自带 ReadAction）；Community、无数据源、
     * 表名为空时立即返回 0。返回真正解析到内容的表数——调用方据此判定是否有必要刷新补全上下文。
     *
     * 数据源晚连接（`getModel()` 为 null）时本方法解析 0 张且**不留负缓存**（见 [resolveFromSources]
     * 的 bail 语义），控制台就绪后重新触发即可命中。
     */
    fun prefetchTables(project: Project?, tableNames: Collection<String>): Int {
        if (project == null) return 0
        if (!isDatabaseAvailable()) return 0
        var resolved = 0
        for (name in tableNames) {
            if (name.isBlank()) continue
            val meta = runCatching { tableMeta(project, name) }.getOrNull() ?: continue
            if (meta.columns.isNotEmpty() || meta.comment != null) resolved++
        }
        return resolved
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

    // ----------
    // T-PS5-06：单调用全扫限额（MCP schema_context 一次调用最多 20 表）。
    // 此前每个 miss 表都对每个数据源重跑一遍 DasModel 全遍历；而 [collectFromDataSource] 的
    // 遍历是**完整**的（命中后不 break，仍走完所有元素；只有「查不了」才提前 bail）——因此同一
    // 数据源在**同一次调用内**的第二张 miss 表，其遍历结论与第一张完全相同，重扫纯属白付
    // （多数据源 × 20 表时一次调用最多 k 次全扫，且全程在 ReadAction 内挡写动作）。
    // 账本按「数据源身份」记账（IdentityHashMap 语义）：同一数据源在一次调用内全扫至多一次；
    // 已全扫过的数据源上的后续 miss 表直接按未找到处理，负缓存照记（结论与全扫等价，跨调用
    // 负缓存语义不变）。**查不了（scanBailed）不记账**——模型未加载/反射链失败时下次必须重试。
    // 作用域仅由 MCP 取数入口显式开启（[withScanBudget]）；其它调用方零行为变化。
    // ----------

    /** 调用内账本（已完整全扫过的数据源身份集）。 */
    internal class ScanBudget {
        internal val scannedSources: MutableSet<Any> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    }

    private val scanBudget = ThreadLocal<ScanBudget?>()

    /**
     * T-PS5-06：在 [block] 内启用单调用全扫限额（MCP `schema_context` 一次调用包一层）。
     * 嵌套调用保留外层账本（内层不重置）；退出恢复原作用域。
     */
    internal fun <T> withScanBudget(block: () -> T): T {
        val prev = scanBudget.get()
        if (prev == null) scanBudget.set(ScanBudget())
        try {
            return block()
        } finally {
            scanBudget.set(prev)
        }
    }

    /** T-PS5-06 测试缝：当前是否处于限额作用域内。 */
    internal fun inScanBudgetForTest(): Boolean = scanBudget.get() != null

    /** 负命中是否在 TTL 内（过期条目惰性移除，不留定时器）。 */
    private fun isNegativeCached(ds: Any, cacheKey: String): Boolean {
        val stamped = synchronized(negativeCache) { negativeCache[ds]?.get(cacheKey) } ?: return false
        // 严格小于：TTL=0 语义=立即过期（同一毫秒内也不新鲜）——负缓存 TTL 测试的确定性
        // 依赖此边界（<= 形态下同毫秒调用判新鲜，分片负载下曾致 flake expected 2 was 1）
        if (System.currentTimeMillis() - stamped < negativeTtlMs) return true
        synchronized(negativeCache) { negativeCache[ds]?.remove(cacheKey) }
        return false
    }

    /** T-PS5-06：记负缓存（时间戳，TTL 内同表同数据源查询不再全扫）。 */
    private fun stampNegative(ds: Any, cacheKey: String) {
        synchronized(negativeCache) {
            negativeCache.getOrPut(ds) { HashMap() }[cacheKey] = System.currentTimeMillis()
        }
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

    /**
     * 数据源侧元数据（表注释为 null 时该维度缺失，调用方逐维度回落）。
     *
     * @param scanBailed T-DbSchemaCompletion：本次**没能查**（数据源模型未加载 / ObjectKind 取不到 /
     *   反射链异常），区别于「查了、确认没有此表」。调用方据此决定是否记负缓存——把"查不了"当
     *   "不存在"会让数据源晚连接时 30s 内的补全与富化全被负缓存挡掉（T-MetaCheapSource 语义缺口）。
     */
    data class TableMeta(
        val comment: String?,
        val columns: Map<String, ColumnMeta>,
        val scanBailed: Boolean = false,
    )

    /** 数据源侧列元数据（均为 null 时表示该维度缺失，调用方逐维度回落）。 */
    data class ColumnMeta(val comment: String?, val typeSpec: String?, val notNull: Boolean? = null)

    // T-TestAssertReal：notNull 取自 DasTypedObject.isNotNull()（javap 实证，DasColumn extends
    // DasTypedObject）。收录条件放宽为「comment/typeSpec 非空 或 notNull==true」后，仅非空标记
    // 列（comment/typeSpec 双空）也会进 Map——只消费 comment 的调用点自行过滤 blank 保持放宽前语义。
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
     * 正命中回填与返回行为与 T-Perf-09 原实现一致）。
     *
     * T-DbSchemaCompletion：查不了（`scanBailed`）与"确认没有"分道——前者不写负缓存、不短路
     * 后续数据源、下次调用重试；聚合结果仅在**所有**数据源都查不了时才标 `scanBailed`。
     */
    internal fun resolveFromSources(
        tableName: String,
        sources: List<Any>,
        scanProbe: ((Any, String) -> TableMeta)? = null,
    ): TableMeta {
        val scan: (Any, String) -> TableMeta =
            scanProbe ?: { ds, name -> collectFromDataSource(ds, name, Class.forName(DAS_TABLE)) }
        val cacheKey = tableName.lowercase()
        val budget = scanBudget.get()
        var bailed = 0
        for (ds in sources) {
            // T-Perf-09：缓存命中直接返回（同一数据源同一表零遍历）
            val cached = synchronized(metaCache) { metaCache[ds]?.get(cacheKey) }
            if (cached != null) return cached
            // T-MetaCheapSource：TTL 内负命中跳过该数据源全扫（introspection 完成后由 DDL
            // 失效/TTL 过期两条路放开，见 negativeCache 注释）
            if (isNegativeCached(ds, cacheKey)) continue
            // T-PS5-06：本次调用内该数据源已完整全扫过 → 不再重扫（结论等价；负缓存照记，
            // 跨调用语义不变）
            if (budget != null && budget.scannedSources.contains(ds)) {
                stampNegative(ds, cacheKey)
                continue
            }
            val found = scan(ds, tableName)
            if (found.columns.isNotEmpty() || found.comment != null) {
                synchronized(metaCache) {
                    metaCache.getOrPut(ds) { HashMap() }[cacheKey] = found
                }
                // 命中即证明遍历完整（collectFromDataSource 命中后不 break）→ 记账
                budget?.scannedSources?.add(ds)
                return found
            }
            // T-DbSchemaCompletion：查不了的（模型未加载 / ObjectKind 取不到）**不写负缓存**——
            // 否则数据源晚连接时会把 30s 内的补全、JOIN 富化全挡掉，且负缓存"过期前不再重试"
            // 恰好卡住刚连上的那一分钟。继续看下一个数据源，都不行就留给下次调用重试。
            if (found.scanBailed) {
                bailed++
                continue
            }
            // T-PS5-06：完整全扫且未命中 → 记账（本次调用内同数据源后续表不再重扫）
            budget?.scannedSources?.add(ds)
            // 该数据源确认没有此表：记负缓存（时间戳，TTL 内同表查询不再全扫）
            stampNegative(ds, cacheKey)
        }
        // 表在所有数据源都没拿到（通常=未连接/introspection 未完成/表不在当前库）
        if (bailed > 0) {
            LOG.info(
                "DatabaseSchemaResolver: table=$tableName 无法解析——$bailed/${sources.size} 个数据源" +
                    "查不了（模型未加载/ObjectKind 取不到，原因见上方 scan 行），未记负缓存、下次调用重试",
            )
        } else {
            LOG.info("DatabaseSchemaResolver: table=$tableName not found in ${sources.size} data sources")
        }
        return TableMeta(null, emptyMap(), scanBailed = bailed > 0 && bailed == sources.size)
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

    /**
     * 取 DBN `ObjectKind` 的具名常量（T-DbSchemaCompletion 根因修复）。
     *
     * **别再写 `enumConstants`**：`com.intellij.database.model.ObjectKind` 是 DBN 手写的
     * type-safe enum（`Object` 子类 implements `Comparable` + public static final 常量 +
     * `ourKinds` 注册表 + `values()`），**不是 `java.lang.Enum`**——262.10968.63 字节码实证：
     * 类文件中 `java/lang/Enum` 出现 0 次、无 `$VALUES`/`valueOf`/`ordinal`。`Class.enumConstants`
     * 对它恒返回 null，历史上本处正是这么写的，于是每次调用都在该行静默早退、列遍历一次都没执行过，
     * 而早退无日志，上层只打印误导性的 `not found in N data sources`（真机 23 次调用 scan 行 0 次，
     * 同名表其实存在于 information_schema）。同仓非反射调用（`DbTableSpecAdapter` 的
     * `ObjectKind.COLUMN`）一直正常，取静态字段即可。
     *
     * 取不到时返回 null 由调用方按「查不了」处理（不写负缓存 + 留日志），不做 API 形态猜测。
     */
    internal fun resolveObjectKind(kindClass: Class<*>, name: String): Any? =
        runCatching { kindClass.getField(name).get(null) }.getOrNull()

    /** ObjectKind 可用常量名（排障用：取不到 COLUMN 时把实际字段列进日志，一眼看出 API 漂移）。 */
    private fun kindConstantNames(kindClass: Class<*>): String =
        runCatching { kindClass.fields.joinToString("/") { it.name } }.getOrDefault("?")

    /** 数据源显示名（日志用；取不到返回 `?`）。 */
    private fun dsName(ds: Any): String = runCatching {
        Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds) as? String
    }.getOrNull() ?: "?"

    /**
     * 从单个数据源收集匹配表的表注释 + 列元数据（遍历 DasModel，命中即取列）。
     *
     * T-DbSchemaCompletion：**任何"查不了"的情形都不再静默早退**——统一置 `bail` 原因，
     * 函数末尾那行 scan 日志必打（此前 model/traverser/columnKind 三处 null 直接 `return`，
     * 既无日志又丢掉了"没查"与"没有"的区别）。
     */
    private fun collectFromDataSource(ds: Any, tableName: String, dasTableClass: Class<*>): TableMeta {
        var tableComment: String? = null
        val out = linkedMapOf<String, ColumnMeta>()
        // T-Embed-10.5 探针：B 路径 locateDbTables 直调同模型命中而本链 not found——
        // 分辨 elements 空遍历 / DasTable 筛不出 / 名称不匹配三种情形
        var scannedTables = 0
        var hit = false
        var bail: String? = null
        runCatching {
            val dasObjClass = Class.forName(DAS_OBJECT)
            val dasColumnClass = Class.forName(DAS_COLUMN)
            // T-Embed-09 真机修复：243 字节码实证 RawDataSource/DbDataSource 均无 getDasObject()，
            // getModel() 声明在父接口 DasDataSource（B 路径 locateDbTables 直调 ds.model 同源）——
            // 旧链 getDasObject().getModel() 反射 NoSuchMethodException 被吞 → 全量静默回落
            val model = Class.forName(DAS_DATA_SOURCE).getMethod("getModel").invoke(ds)
            if (model == null) {
                // 数据源未连接/未内省（预览打开常早于建连）：不是"没有此表"，不能记负缓存
                bail = "数据源模型未加载(getModel=null)"
                return@runCatching
            }
            val traverser = Class.forName(DAS_MODEL).getMethod("traverser").invoke(model)
            if (traverser == null) {
                bail = "DasModel.traverser=null"
                return@runCatching
            }
            // 16:09 沙盒日志实证：DasModel.traverser() 返回 JBTreeTraverser（extends
            // FilteredTraverserBase，非 JBIterable 子类）——对 JBIterable 取 toList invoke 抛
            // IllegalArgumentException: object is not an instance of declaring class。
            // FilteredTraverserBase implements java.lang.Iterable，iterator() 为其 public final——
            // 用 Iterable/Iterator 接口方法手动收集（= Kotlin 直调 toList 扩展同语义）
            val elements = try {
                // java.util.Iterator 为 JDK 接口，cast 后 hasNext/next 直调（避免再反射一层）
                val iterator = java.lang.Iterable::class.java.getMethod("iterator")
                    .invoke(traverser) as java.util.Iterator<*>
                val collected = mutableListOf<Any?>()
                while (iterator.hasNext()) collected.add(iterator.next())
                collected
            } catch (e: Throwable) {
                bail = "遍历 DasModel 失败: ${e.javaClass.simpleName}"
                LOG.warn("DatabaseSchemaResolver: traverse elements failed", e)
                return@runCatching
            }
            val objectKindClass = Class.forName(OBJECT_KIND)
            // T-DbSchemaCompletion：ObjectKind 非 java.lang.Enum，不能走 enumConstants（恒 null）
            val columnKind = resolveObjectKind(objectKindClass, "COLUMN")
            if (columnKind == null) {
                bail = "ObjectKind.COLUMN 取不到（实际常量：${kindConstantNames(objectKindClass)}）"
                return@runCatching
            }
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
            bail = "反射链异常: ${e.javaClass.simpleName}: ${e.message}"
            LOG.warn("DatabaseSchemaResolver.collectFromDataSource failed", e)
        }
        LOG.info(
            "DatabaseSchemaResolver scan: table=$tableName, ds=${dsName(ds)}, bail=${bail ?: "-"}, " +
                "tablesScanned=$scannedTables, hit=$hit, foundColumns=${out.size}, " +
                "tableComment=${tableComment != null}",
        )
        return TableMeta(tableComment, out, scanBailed = bail != null)
    }
}