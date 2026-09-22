package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.execute.JdbcUrlDatabase
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
 * `DasDataSource.getModel()` → `DasModel.traverser()` 迭代筛 `DasTable` → 按名匹配（P7 起追加
 * 「表祖先链含本数据源 URL 的库名」限定，见 [collectFromDataSource]）→
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
    private const val RAW_CONNECTION_CONFIG = "com.intellij.database.model.RawConnectionConfig"

    /** P7 祖先链（库 → 模式 → 表）的最大回溯层数；超出即视为取不到，不做无界遍历。 */
    private const val MAX_ANCESTOR_DEPTH = 4

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
     *
     * P7：同一表名**只认首个持有它的数据源**（不再跨数据源并集，见 [mergeCachedColumns]）；
     * 该数据源内的缓存条目本身也带库名维度，不会把 A 库那张表的列喂给 B 库。
     */
    fun columnsCached(project: Project?, tableNames: Collection<String>): Map<String, ColumnMeta> {
        if (project == null || tableNames.isEmpty()) return emptyMap()
        if (!isDatabaseAvailable()) return emptyMap()
        val dataSources = dataSources(project) ?: return emptyMap()
        synchronized(metaCache) {
            // P7：按数据源顺序给出「表名 → 该源缓存里的列」查询器，由 [mergeCachedColumns] 取首个持有者
            val sources: List<(String) -> Map<String, ColumnMeta>?> = dataSources.map { ds ->
                val db = databaseNameOf(ds)
                val lookup: (String) -> Map<String, ColumnMeta>? = { name ->
                    metaCache[ds]?.get(cacheKeyOf(db, name))?.columns
                }
                lookup
            }
            return mergeCachedColumns(tableNames, sources)
        }
    }

    /**
     * P7：补全候选的多表列并集（纯逻辑，单测直测）。
     *
     * [sources] 按数据源顺序排列，每项是「表名 → 该数据源缓存里的列」（该源未持有返回 null）。
     * **每个表名只认首个持有它的数据源**——旧实现把全部数据源的同名表列 putIfAbsent 并成一个 Map，
     * 跨库同名表（业务表 + `mysql.user` 之流）的列互相污染。不同表名之间仍按列名去重
     * （同名跨表列取首次命中，一期口径不变）。
     */
    internal fun mergeCachedColumns(
        tableNames: Collection<String>,
        sources: List<(String) -> Map<String, ColumnMeta>?>,
    ): Map<String, ColumnMeta> {
        val out = linkedMapOf<String, ColumnMeta>()
        for (name in tableNames) {
            if (name.isBlank()) continue
            for (lookup in sources) {
                val columns = lookup(name) ?: continue
                columns.forEach { (col, meta) -> out.putIfAbsent(col, meta) }
                break
            }
        }
        return out
    }

    /**
     * O-58：多表的**全列名**集合（**只读缓存，不触发遍历**）。
     *
     * 与 [columnsCached] 同源同口径——同一 [metaCache]、同 P7 库名维度、同「首个持有者」语义，
     * 差别只在返回维度：[columnsCached] 给「列名 → 列元数据」（因此天然只含带元数据的子集，见
     * [TableMeta.allColumnNames] 的说明），这里给「表名 → 该表全部列名」。单开入口而不是复用
     * [columnsCached]，既让两处语义各自明确，也让 [columnsCached] 的既有消费方（补全候选）
     * 零改动。未预热 / 数据源未连 / 社区版一律返回空 Map，调用方回落。
     */
    fun columnNamesCached(project: Project?, tableNames: Collection<String>): Map<String, Set<String>> {
        if (project == null || tableNames.isEmpty()) return emptyMap()
        if (!isDatabaseAvailable()) return emptyMap()
        val dataSources = dataSources(project) ?: return emptyMap()
        synchronized(metaCache) {
            return mergeCachedColumnNames(tableNames, columnNameSources(dataSources))
        }
    }

    /**
     * P7：按数据源顺序给出「表名 → 该源缓存里的全列名」查询器（该源未持有该表返回 null）。
     * 与 [columnsCached] 内部的查询器同构；抽成 internal 是为了让 [mergeCachedColumnNames]
     * 的合并语义能在无 database 插件的单测环境里直测（同 [mergeCachedColumns] 的既有做法）。
     */
    internal fun columnNameSources(dataSources: List<Any>): List<(String) -> Set<String>?> =
        dataSources.map { ds ->
            val db = databaseNameOf(ds)
            val lookup: (String) -> Set<String>? = { name ->
                metaCache[ds]?.get(cacheKeyOf(db, name))?.allColumnNames
            }
            lookup
        }

    /**
     * P7：「首个持有者」合并（口径与 [mergeCachedColumns] 逐条一致）——每个表名只认首个持有它的
     * 数据源，避免跨库同名表互相污染；不同表名之间各取各的。
     */
    internal fun mergeCachedColumnNames(
        tableNames: Collection<String>,
        sources: List<(String) -> Set<String>?>,
    ): Map<String, Set<String>> {
        val out = linkedMapOf<String, Set<String>>()
        for (name in tableNames) {
            if (name.isBlank()) continue
            for (lookup in sources) {
                val names = lookup(name) ?: continue
                out[name] = names
                break
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
     * 数据源晚连接（`getModel()` 为 null）或内省未完成（模型非 null 但遍历 0 张表）时本方法解析 0 张
     * 且**不留负缓存**（见 [resolveFromSources] 的 bail 语义与 [bailAfterTraversal]），控制台就绪后
     * 重新触发即可命中。
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
     * T-Perf-09：TableMeta 缓存——键 = (dataSource 对象弱引用, [cacheKeyOf] 产出的库名+表名键)。
     * 数据源全量遍历（DasModel.traverser().toList()）代价随 schema 规模线性，JOIN 预览
     * 每打开一次、每个 JOIN 表各触发一次；命中后零遍历。数据源删除后条目随弱键回收；
     * 表匹配不到（通常=未连接/introspection 未完成）走 [negativeCache] 负命中缓存
     * （T-MetaCheapSource：TTL + DDL 失效双保险，见下）。
     *
     * P7（缓存维度）：内层键含库名——**命中发生在匹配逻辑之前**（`resolveFromSources` 先查缓存再
     * 决定扫不扫），只改匹配对已缓存条目完全无效；库名进键后，同名表在不同库/不同连接 URL 下的
     * 结论各自成条目，不会互相喂（库名取不到时键退回纯表名，与修复前逐字一致）。
     *
     * T-DdlCacheInvalidate：DDL/事务提交语句执行提交前经 execute/MetadataInvalidator 全量失效
     * （本缓存与负命中缓存同为此前盘点出的自持 schema 面——ALTER TABLE 后补全/JOIN 富化拿旧结构
     * 是正确性破口，失效走 O(1) 引用清空）。
     */
    private val metaCache =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, MutableMap<String, TableMeta>>())

    /** T-DdlCacheInvalidate：正缓存全量失效（引用置空语义，下次取数零遍历回填；库名记忆一并清）。 */
    fun invalidateAll() {
        synchronized(metaCache) { metaCache.clear() }
        synchronized(dsDatabaseCache) { dsDatabaseCache.clear() }
    }

    // ----------
    // P7：数据源库名维度（连接 URL 里的库名）。
    // 一个数据源可以同时看见多个库/模式（MySQL 的 `mpj_demo` 与 `mysql`、PG 的多 schema），
    // 同名表的解析结论按库不同——故缓存键与匹配条件都要带库名。口径复用 execute/JdbcUrlDatabase
    // （T-ConsoleDbContext 同源）与 McpSchemaContextService 的 P4 实现：`getConnectionConfig()
    // .getUrl()` → nameFromUrl；取不到（URL 无库段 / Oracle SID 形态 / 无 database 插件）→ null，
    // 调用方**退回按名匹配**，不引入新失败模式。
    // ----------

    /** 数据源 → 连接 URL 库名（null 也缓存，用哨兵区分「未解析」与「解析为 null」）。 */
    private val dsDatabaseCache =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Any>())

    private val NO_DATABASE = Any()

    /** 测试缝：数据源库名替身（默认 null = 走 DBN 反射链；单测环境无 database 插件）。 */
    internal var dataSourceDatabaseForTest: ((Any) -> String?)? = null

    /** 数据源连接 URL 里的库名（取不到 → null，调用方退回按名匹配）。 */
    internal fun databaseNameOf(ds: Any): String? {
        dataSourceDatabaseForTest?.let { return it(ds) }
        synchronized(dsDatabaseCache) { dsDatabaseCache[ds] }?.let {
            return if (it === NO_DATABASE) null else it as String
        }
        val resolved = runCatching {
            val config = Class.forName(DAS_DATA_SOURCE).getMethod("getConnectionConfig").invoke(ds)
            val url = config?.let { Class.forName(RAW_CONNECTION_CONFIG).getMethod("getUrl").invoke(it) } as? String
            JdbcUrlDatabase.nameFromUrl(url)
        }.getOrNull()
        synchronized(dsDatabaseCache) { dsDatabaseCache[ds] = resolved ?: NO_DATABASE }
        return resolved
    }

    /**
     * 缓存键（正/负缓存同口径）：库名 + 表名小写；库名取不到时**退回纯表名**（与修复前逐字一致）。
     *
     * 分隔符用 NUL——标识符里不可能出现，杜绝「库名或表名自带分隔符」造成的键碰撞。
     */
    private fun cacheKeyOf(database: String?, tableName: String): String {
        val table = tableName.lowercase()
        val db = database?.trim()?.lowercase().orEmpty()
        return if (db.isEmpty()) table else "$db\u0000$table"
    }

    // ----------
    // T-MetaCheapSource（O-21 T-MC-01）：负命中缓存。
    // agent 场景同一幻觉表名会在一次会话内反复尝试，此前每次未命中都全量遍历 DasModel
    // （多数据源 × 大模型时一次调用可到秒级）。键 = (dataSource 弱引用, [cacheKeyOf] 键：库名+表名)，
    // 值 = 记入时间戳；
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

    /**
     * T-PS5-13：负缓存容量上界（照 `Ognl.kt` CACHE_MAX / StackLocator 惯例）——超上限**整体清空**。
     * 键含表名，长会话里 agent 幻觉表名可以无界累积（TTL 只做惰性过期、不做容量约束）；
     * 清空代价 = 若干次重扫（fail safe 方向，与负缓存语义一致）。TTL 惰性过期语义不变。
     */
    internal const val NEGATIVE_CACHE_MAX = 512

    /** T-PS5-13：负缓存当前条目数（跨数据源求和；测试缝与容量判定共用）。 */
    internal fun negativeCacheSize(): Int = synchronized(negativeCache) { negativeCache.values.sumOf { it.size } }

    // ----------
    // T-PS5-06：单调用全扫限额（MCP schema_context 一次调用最多 20 表）。
    // 此前每个 miss 表都对每个数据源重跑一遍 DasModel 全遍历；而 [collectFromDataSource] 的
    // 遍历是**完整**的（命中后不 break，仍走完所有元素；只有「查不了」才提前 bail）——因此同一
    // 数据源在**同一次调用内**的第二张 miss 表，其遍历结论与第一张完全相同，重扫纯属白付
    // （多数据源 × 20 表时一次调用最多 k 次全扫，且全程在 ReadAction 内挡写动作）。
    // 账本按「数据源身份」记账（IdentityHashMap 语义）：同一数据源在一次调用内全扫至多一次；
    // 已全扫过的数据源上的后续 miss 表直接按未找到处理，负缓存照记（结论与全扫等价，跨调用
    // 负缓存语义不变）。**查不了（scanBailed）不记账**——模型未加载/反射链失败时下次必须重试。
    // 作用域仅由 [withScanBudget] 显式开启；**当前无生产调用方**（原唯一入口
    // McpSchemaContextService 的 schema_context 已改自建索引，见其 P4 注释），仅测试使用；
    // 其它调用方零行为变化。
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
     *
     * **当前无生产调用方，仅测试使用**——原唯一入口（MCP `schema_context`）已改为自建调用内索引
     * （McpSchemaContextService P4），本方法与其账本语义保留作回归护栏，新接调用方前先确认是否真需要。
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
            // T-PS5-13：容量上界——溢出整体清空（本键随后照常写入；清空只丢命中率不丢正确性）
            if (negativeCacheSize() >= NEGATIVE_CACHE_MAX) negativeCache.clear()
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
        /**
         * O-58：该表的**全部**列名——含 [columns] 因「无注释 ∧ 无类型串 ∧ notNull 不为 true」而
         * 未收录元数据的列。列名集合是独立维度：列名校正要在它上面做**唯一性**判定，而唯一性判定
         * 在不完整子集上会失真（同形两列里少了一列 → 歧义消失 → 选错列）。默认空集，既有构造点
         * （测试替身 / bail 早退）语义逐字不变。
         */
        val allColumnNames: Set<String> = emptySet(),
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
            scanProbe ?: { ds, name -> collectFromDataSource(ds, name) }
        val budget = scanBudget.get()
        var bailed = 0
        for (ds in sources) {
            // P7：缓存键带库名维度（同一数据源可同时看见多个库/模式，同名表结论按库不同；
            // 库名取不到时键退回纯表名，与修复前逐字一致）
            val cacheKey = cacheKeyOf(databaseNameOf(ds), tableName)
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
     * 一次 DasModel 遍历的产物：元素列表 + 表/列访问器（[bail] 非空 = 查不了，元素无意义）。
     *
     * 生产由 [reflectTraversal] 反射产出；单测经 [traversalForTest] 注入替身——无 database 插件时
     * `Class.forName` 全链不可用，而 P7 的匹配/合并/判定语义（祖先链限库、首个匹配锁定、遍历 0 张
     * 表判「查不了」）全都落在下面这段循环上，不给替身就一条都测不到。替身只换「取数」，
     * 匹配与合并逻辑仍是生产代码本体。
     */
    internal class ModelTraversal(
        val elements: List<Any?>,
        val accessors: DasAccessors,
    )

    /**
     * 表/列读取访问器（生产实现包的是 `Method.invoke`，反射链口径见 [reflectTraversal]）。
     *
     * @param parent 表/命名空间的父节点（P7 祖先链用；取不到返回 null → 不限定库）
     */
    internal class DasAccessors(
        val isTable: (Any) -> Boolean,
        val isColumn: (Any) -> Boolean,
        val name: (Any) -> String?,
        val comment: (Any) -> String?,
        val children: (Any) -> Iterable<*>?,
        val parent: (Any) -> Any? = { null },
        val typeSpec: (Any) -> String? = { null },
        val notNull: (Any) -> Boolean? = { null },
    )

    /** 遍历替身（测试缝；默认 null = 真实反射链）。 */
    internal var traversalForTest: ((Any) -> ModelTraversal)? = null

    /** P7b：一张 DasTable 都没扫到（模型非 null 但内省未完成）时的 bail 原因。 */
    internal const val TRAVERSED_ZERO_TABLES = "DasModel 遍历 0 张表（内省未完成）"

    /**
     * P7b：遍历结束后的「查不了」判定（纯逻辑，单测直测）。
     *
     * 模型非 null 却一张表都没扫到 = **没查**（内省未完成 / 模型只建了库节点），不是「没有此表」——
     * 判成后者会记负缓存，把补全与 JOIN 富化钉 30s；更直接的危害是 DDL 生效探测
     * （SqlPreviewPanel.maybeProbeDdlEffective 的 500/1500/3000ms 三次退避重试）首次那一记负缓存
     * 会把后两次全挡掉，误报「DDL 未生效」。
     *
     * 已有 bail（模型未加载 / 遍历抛错 / ObjectKind 取不到）时不覆盖——那是更精确的原因。
     */
    internal fun bailAfterTraversal(hit: Boolean, scannedTables: Int, existingBail: String?): String? =
        existingBail ?: TRAVERSED_ZERO_TABLES.takeIf { !hit && scannedTables == 0 }

    /**
     * P7：自表向上的命名空间名链（库 → 模式 → …）。口径与 McpSchemaContextService 的 P4 同源
     * （那边自建索引时用的是同一判定）；链上取不到父节点即自然终止，超过 [MAX_ANCESTOR_DEPTH] 层
     * 视为取不到（不做无界回溯）。
     */
    private fun ancestorChain(element: Any, acc: DasAccessors): List<String> {
        val out = ArrayList<String>()
        var current = acc.parent(element)
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            acc.name(current)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            current = acc.parent(current)
            depth++
        }
        return out
    }

    /**
     * 真实反射链遍历（T-Embed-09/10 实测口径）。[Pair] 第二项非空 = **查不了**的原因
     * （模型未加载 / traverser 为 null / 遍历抛错 / ObjectKind 取不到）。
     *
     * 反射链自身异常（类缺失等）不在此吞——由 [collectFromDataSource] 外层 runCatching 收敛为
     * 「反射链异常」bail，保持 T-DbSchemaCompletion 的日志口径。
     */
    private fun reflectTraversal(ds: Any): Pair<ModelTraversal?, String?> {
        val dasObjClass = Class.forName(DAS_OBJECT)
        val dasColumnClass = Class.forName(DAS_COLUMN)
        // T-Embed-09 真机修复：243 字节码实证 RawDataSource/DbDataSource 均无 getDasObject()，
        // getModel() 声明在父接口 DasDataSource（B 路径 locateDbTables 直调 ds.model 同源）——
        // 旧链 getDasObject().getModel() 反射 NoSuchMethodException 被吞 → 全量静默回落
        val model = Class.forName(DAS_DATA_SOURCE).getMethod("getModel").invoke(ds)
            // 数据源未连接/未内省（预览打开常早于建连）：不是"没有此表"，不能记负缓存
            ?: return null to "数据源模型未加载(getModel=null)"
        val traverser = Class.forName(DAS_MODEL).getMethod("traverser").invoke(model)
            ?: return null to "DasModel.traverser=null"
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
            LOG.warn("DatabaseSchemaResolver: traverse elements failed", e)
            return null to "遍历 DasModel 失败: ${e.javaClass.simpleName}"
        }
        val objectKindClass = Class.forName(OBJECT_KIND)
        // T-DbSchemaCompletion：ObjectKind 非 java.lang.Enum，不能走 enumConstants（恒 null）
        val columnKind = resolveObjectKind(objectKindClass, "COLUMN")
            ?: return null to "ObjectKind.COLUMN 取不到（实际常量：${kindConstantNames(objectKindClass)}）"
        val dasTableClass = Class.forName(DAS_TABLE)
        val getNameM = dasObjClass.getMethod("getName")
        val getCommentM = dasObjClass.getMethod("getComment")
        val getChildrenM = dasObjClass.getMethod("getDasChildren", objectKindClass)
        // P7：祖先链（库/模式）判定用 DasObject.getDasParent。取不到 → parent 恒 null → 不限定库
        // （退回按名匹配，不得引入新失败模式）
        val getDasParentM = runCatching { dasObjClass.getMethod("getDasParent") }.getOrNull()
        // DasTypedObject.getDataType().getSpecification()（反射；列无类型信息时为 null）
        val dasTypedObjectClass = Class.forName(DAS_TYPED_OBJECT)
        val getDataTypeM = dasTypedObjectClass.getMethod("getDataType")
        val getSpecificationM = Class.forName(DATA_TYPE).getMethod("getSpecification")
        // T-TestAssertReal：DasTypedObject.isNotNull()（javap 实证声明于 DasTypedObject 接口，
        // DasColumn extends DasTypedObject）——Method 一律从声明接口获取（T-Embed-10 跨
        // classloader 教训，禁止 obj.javaClass.getMethod）；反射失败为 null，不进非空清单
        val isNotNullM = dasTypedObjectClass.getMethod("isNotNull")
        return ModelTraversal(
            elements = elements,
            accessors = DasAccessors(
                isTable = { dasTableClass.isInstance(it) },
                isColumn = { dasColumnClass.isInstance(it) },
                name = { getNameM.invoke(it) as? String },
                comment = { getCommentM.invoke(it) as? String },
                children = { getChildrenM.invoke(it, columnKind) as? Iterable<*> },
                parent = { el -> getDasParentM?.let { m -> runCatching { m.invoke(el) }.getOrNull() } },
                typeSpec = { col ->
                    runCatching {
                        getDataTypeM.invoke(col)?.let { getSpecificationM.invoke(it) } as? String
                    }.getOrNull()
                },
                notNull = { col -> runCatching { isNotNullM.invoke(col) as? Boolean }.getOrNull() },
            ),
        ) to null
    }

    /**
     * 从单个数据源收集匹配表的表注释 + 列元数据（遍历 DasModel，命中即取列）。
     *
     * T-DbSchemaCompletion：**任何"查不了"的情形都不再静默早退**——统一置 `bail` 原因，
     * 函数末尾那行 scan 日志必打（此前 model/traverser/columnKind 三处 null 直接 `return`，
     * 既无日志又丢掉了"没查"与"没有"的区别）。
     *
     * P7（同名表污染）：命中条件由「表名相等」收紧为「表名相等 ∧ 表祖先链含本数据源 URL 的库名」，
     * 且**首个匹配即锁定**——旧实现只比表名、命中后继续遍历，把后续同名表（`mysql.user` /
     * `information_schema` 之流）的列并进同一个 Map，表注释也被最后一张覆盖（真机 60 列
     * = 业务表 9 列 + 系统表 50+）。库名取不到（URL 无库段 / Oracle SID 形态）或祖先链读不出时
     * **不限定**，退回按名匹配。
     *
     * P7b：模型非 null 但一张表都没扫到 → 判「查不了」（见 [bailAfterTraversal]）。
     */
    private fun collectFromDataSource(ds: Any, tableName: String): TableMeta {
        var tableComment: String? = null
        val out = linkedMapOf<String, ColumnMeta>()
        // O-58：列名**无条件**收录（下面的元数据过滤只作用于 out，不影响本集合）
        val allNames = linkedSetOf<String>()
        // T-Embed-10.5 探针：B 路径 locateDbTables 直调同模型命中而本链 not found——
        // 分辨 elements 空遍历 / DasTable 筛不出 / 名称不匹配三种情形
        var scannedTables = 0
        var hit = false
        var bail: String? = null
        // P7：本数据源的库名维度（连接 URL 里的库名；取不到 → null，退回按名匹配）
        val targetDb = databaseNameOf(ds)
        runCatching {
            val stub = traversalForTest?.invoke(ds)
            val traversal: ModelTraversal
            if (stub != null) {
                traversal = stub
            } else {
                val (reflected, reason) = reflectTraversal(ds)
                if (reflected == null) {
                    bail = reason
                    return@runCatching
                }
                traversal = reflected
            }
            val acc = traversal.accessors
            for (element in traversal.elements) {
                if (element == null || !acc.isTable(element)) continue
                scannedTables++
                val tName = acc.name(element) ?: continue
                if (!tName.equals(tableName, ignoreCase = true)) continue
                // P7：首个匹配即锁定——同名表后续出现的一律跳过（旧行为=继续合并、注释被最后一张覆盖）
                if (hit) continue
                // P7：库维度限定——表祖先链须含本数据源 URL 的库名；链读不出（空）时不限定
                if (targetDb != null) {
                    val chain = ancestorChain(element, acc)
                    if (chain.isNotEmpty() && !chain.any { it.equals(targetDb, ignoreCase = true) }) continue
                }
                hit = true
                // 表级 COMMENT 与列遍历同一元素读取（blank 视为数据库没写，保留 null 供调用方回落）
                tableComment = acc.comment(element)?.takeIf { it.isNotBlank() }
                val columns = acc.children(element) ?: continue
                for (col in columns) {
                    if (col == null || !acc.isColumn(col)) continue
                    val cName = acc.name(col) ?: continue
                    if (cName.isBlank()) continue
                    allNames.add(cName)
                    val comment = acc.comment(col)
                    val typeSpec = acc.typeSpec(col)
                    val notNull = acc.notNull(col)
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
        // P7b：遍历 0 张表 = 没查（内省未完成），不是「没有此表」——不记负缓存、下次调用重试
        bail = bailAfterTraversal(hit, scannedTables, bail)
        LOG.info(
            "DatabaseSchemaResolver scan: table=$tableName, ds=${dsName(ds)}, bail=${bail ?: "-"}, " +
                "tablesScanned=$scannedTables, hit=$hit, foundColumns=${out.size}, " +
                "totalColumns=${allNames.size}, tableComment=${tableComment != null}",
        )
        return TableMeta(tableComment, out, scanBailed = bail != null, allColumnNames = allNames)
    }
}