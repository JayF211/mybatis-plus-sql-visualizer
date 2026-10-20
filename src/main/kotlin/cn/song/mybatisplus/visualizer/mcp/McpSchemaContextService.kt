package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.execute.JdbcUrlDatabase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * T-McpToolset sqlv_schema_context 取数适配层。
 *
 * **P4 / T-McpSchemaScope：表名匹配限定到「该数据源自己的 schema/库」。**
 *
 * 一期复用 DatabaseSchemaResolver 的既有通道，而那条通道**只按表名匹配**（不认 schema，也不按别名
 * 过滤数据源）。真机 2026-09-20 实测：`tables: "user"` + `dataSource: "mp_demo@localhost"` 的返回里
 * 同时出现 `mp_demo.user` 业务列与 `mysql.user` 系统列，且表注释取到的是 `mysql.user` 的
 * 「Users and global privileges」——对应 idea.log 那行
 * `scan: table=user, ds=jpa_demo@localhost, tablesScanned=335, hit=true, foundColumns=60`：
 * 一次遍历把**所有同名表的列合并进同一个 Map**（60 列 = 业务表 9 列 + 系统表 50+ 列），
 * 别名甚至没参与匹配（`user` 命中的是列表首个数据源）。
 *
 * 本层改为自己按 DasModel 建「表名 → 目标库那张表」索引：
 *  1. 别名给出且命中时**只扫该数据源**（不再跨数据源按名撞第一个命中）；
 *  2. 数据源内用 [JdbcUrlDatabase.nameFromUrl]（连接 URL 里的库名，T-ConsoleDbContext 同源口径）
 *     取目标库名，只收命名空间祖先链里含该库名的表——同名系统表（`mysql` / `information_schema` …）
 *     不再混入；
 *  3. 目标库名取不到（URL 无库段 / Oracle SID 形态）时退回按名匹配（一期口径，不新增失败模式）；
 *  4. 索引是**调用内**的：同一数据源一次调用只遍历一次模型（替代 T-PS5-06 的扫描账本，且对 N 张
 *     表都生效），**不读 DatabaseSchemaResolver 的正缓存**——那份缓存按表名存的就是合并结果，
 *     读它等于没修（预览/补全链会先把它填成污染值）。
 *
 * 反射链沿用 DatabaseSchemaResolver 已证实的调用面（DasDataSource.getModel → DasModel.traverser
 * 走 Iterable 而非 JBIterable、ObjectKind 非 java.lang.Enum 须 getField 取常量、列类型走
 * DasTypedObject.getDataType().getSpecification()）；Community / 无 database 插件时类缺失 →
 * 全链 runCatching 退化为空索引，最终仍以结构化 SCHEMA_NOT_AVAILABLE 报错（零弹窗 fail closed）。
 */
object McpSchemaContextService {

    private val LOG = Logger.getInstance(McpSchemaContextService::class.java)

    // DBN 反射类名（与 DatabaseSchemaResolver 同源；跨插件 classloader，一律按声明接口取方法）
    private const val DAS_DATA_SOURCE = "com.intellij.database.model.DasDataSource"
    private const val DAS_MODEL = "com.intellij.database.model.DasModel"
    private const val DAS_OBJECT = "com.intellij.database.model.DasObject"
    private const val DAS_TABLE = "com.intellij.database.model.DasTable"
    private const val DAS_COLUMN = "com.intellij.database.model.DasColumn"
    private const val DAS_TYPED_OBJECT = "com.intellij.database.model.DasTypedObject"
    private const val DATA_TYPE = "com.intellij.database.model.DataType"
    private const val OBJECT_KIND = "com.intellij.database.model.ObjectKind"
    private const val RAW_CONNECTION_CONFIG = "com.intellij.database.model.RawConnectionConfig"

    /** 命名空间祖先链的最大回溯层数（库 → 模式 → 表；超出即视为取不到，不做无界遍历）。 */
    private const val MAX_ANCESTOR_DEPTH = 4

    /** 组装结果（[error] 非空 = 结构化错误；否则 [text] 为紧凑上下文）。 */
    data class Outcome(val text: String?, val error: String? = null)

    /**
     * 模型里的一张候选表（反射层产出；纯逻辑层按 [ancestorNames] 判定它属于哪个库）。
     *
     * @param ancestorNames 自表**父节点**向上的命名空间名链（库/模式/数据源；不含表名本身，空白名不入链）
     */
    internal data class ModelTable(
        val name: String,
        val ancestorNames: List<String>,
        val comment: String?,
        val columns: List<McpSchemaFormatter.ColumnLine>,
    )

    /** 测试缝：模型读取替身（默认 null = 走真实 DBN 反射链；单测环境无 database 插件）。 */
    internal var modelTablesForTest: ((Any) -> List<ModelTable>)? = null

    /** 测试缝：数据源连接 URL 替身（默认 null = 走 DBN 反射链）。 */
    internal var dataSourceUrlForTest: ((Any) -> String?)? = null

    /** 测试缝：数据源显示名替身（默认 null = 走 DBN 反射链；单测环境无 database 插件取不到名）。 */
    internal var dsNameForTest: ((Any) -> String?)? = null

    fun buildContext(project: Project, dataSourceAlias: String?, tableNames: List<String>, maxTables: Int): Outcome {
        val requested = tableNames.size
        val limit = McpSchemaFormatter.clampMaxTables(maxTables)

        // 别名存在性校验（别名给出但不匹配 → 明确报错列可选名）
        val alias = dataSourceAlias?.trim()?.takeIf { it.isNotEmpty() }
        val names = McpDbExecutor.listDataSourceNames(project)
        if (McpDbExecutor.isDatabaseAvailable()) {
            if (names.isEmpty()) {
                return Outcome(
                    null,
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_NOT_FOUND,
                        "no data sources configured in this project; configure one in the Database tool window.",
                    ),
                )
            }
            if (alias != null && names.none { it.equals(alias, ignoreCase = true) }) {
                return Outcome(
                    null,
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_NOT_FOUND,
                        "data source '$alias' not found; available: ${names.joinToString(", ")}.",
                    ),
                )
            }
        }

        val diagnostics = mutableListOf<String>()
        val result = collectEntries(targetSources(project, alias, names), tableNames, limit, diagnostics)
        return outcomeOf(
            result.entries, result.missing, limit, requested, diagnostics,
            sourceNames = result.sourceNames,
            // 未指名 alias = 跨源扫描命中，输出披露 auto-resolved 并带 pin 提示（S3）
            autoResolved = alias == null,
        )
    }

    /**
     * 本次调用要扫的数据源：别名命中 → 仅该数据源（P4 的核心：别名不再只是存在性校验）；
     * 未指定别名 → 全部数据源（每个各按自己的 URL 库限定），与一期「无别名即全扫」行为一致。
     */
    private fun targetSources(project: Project, alias: String?, knownNames: List<String>): List<Any> {
        McpDbExecutor.findDataSource(project, alias)?.let { return listOf(it) }
        // 别名给定时 findDataSource 已在上层校验过存在性；这里只可能是「未指定别名 + 多数据源」
        return knownNames.mapNotNull { McpDbExecutor.findDataSource(project, it) }
    }

    /**
     * 组装结果（[entries] 为空时按「无元数据」结构化报错；否则走紧凑格式化）。
     *
     * [diagnostics] 是本次扫描的逐数据源实况（`ds=… targetDb=… modelTables=… indexed=…`）。
     * 复核发现（20260920）：**scope 过滤掉全部候选表**时旧文案只说「可能未连接或内省未完成」，
     * 而真实原因是「表不在该数据源的当前库里」——agent 会照文案做无用重试（重连/等内省）。
     * 故文案补上「表可能不在当前库」这一支，并把实况原样附在末尾（`modelTables>0` 且 `indexed=0`
     * 即「扫到了但都被库名过滤掉」的自解释证据）。
     */
    internal fun outcomeOf(
        entries: List<McpSchemaFormatter.TableEntry>,
        missing: List<String>,
        limit: Int,
        requested: Int,
        diagnostics: List<String> = emptyList(),
        sourceNames: List<String> = emptyList(),
        autoResolved: Boolean = false,
    ): Outcome {
        if (entries.isEmpty()) {
            val scan = if (diagnostics.isEmpty()) "" else " (scanned: ${diagnostics.joinToString("; ")})"
            return Outcome(
                null,
                McpToolErrors.code(
                    McpToolErrors.SCHEMA_NOT_AVAILABLE,
                    "no schema metadata for ${missing.joinToString(", ")}; the table may not exist in the " +
                        "data source's current database, or the data source may be disconnected or " +
                        "introspection has not finished — connect it in the Database tool window and retry." +
                        scan,
                ),
            )
        }
        // T-McpWalkFindings S3：成功路径披露数据源来源——跨源静默解析不披露来源已有两次真机
        // 实证（20260923 jpa_demo 顶替意图库 mp_demo、20261005 两套 user 列结构对不上），
        // agent 拿到的 schema 可能不是意图库的表；未指名 alias 时附 pin 提示让下一次调用自纠。
        val text = buildString {
            if (sourceNames.isNotEmpty()) {
                append("source: ").append(sourceNames.joinToString(", "))
                if (autoResolved) append(" (auto-resolved; pass 'dataSource' to pin one)")
                append("\n\n")
            }
            append(McpSchemaFormatter.format(entries, limit, requested))
        }
        return Outcome(text)
    }

    /**
     * 逐表取数：数据源**一次遍历建索引**，N 张表共用（T-PS5-06 的「同数据源一次调用最多全扫一次」
     * 由索引天然满足，且命中表也不再重复遍历）。表名按大小写不敏感匹配，先到先得。
     *
     * internal 供表驱动单测（单测环境无 database 插件，[modelTablesForTest]/[dataSourceUrlForTest]
     * 注入替身即可直测「限定目标库」整条链路；生产路径无注入，行为一致）。
     */
    internal fun collectEntries(
        sources: List<Any>,
        tableNames: List<String>,
        limit: Int,
        diagnostics: MutableList<String>? = null,
    ): CollectResult {
        val entries = mutableListOf<McpSchemaFormatter.TableEntry>()
        val missing = mutableListOf<String>()
        // 命中表所在数据源名（保序去重；T-McpWalkFindings S3 来源披露——跨源静默解析不披露
        // 来源的两次真机实证：20260923 jpa_demo 顶替 mp_demo、20261005 两套 user 列结构对不上）
        val sourceNames = LinkedHashSet<String>()
        // 身份键（DBN 数据源对象无稳定 equals 语义，沿用 DatabaseSchemaResolver 的 IdentityHashMap 口径）
        val indexBySource = java.util.IdentityHashMap<Any, Map<String, McpSchemaFormatter.TableEntry>>()
        for (name in tableNames.take(limit)) {
            val key = name.lowercase()
            var hit: McpSchemaFormatter.TableEntry? = null
            for (ds in sources) {
                val index = indexBySource.getOrPut(ds) { scanSource(ds, diagnostics) }
                val found = index[key]
                if (found != null) {
                    hit = found
                    dsName(ds).takeIf { it != "?" }?.let { sourceNames.add(it) }
                    break
                }
            }
            // 标签沿用调用方请求的表名（与一期一致：agent 看到的是自己写下的名字）
            if (hit == null) missing.add(name) else entries.add(hit.copy(name = name))
        }
        return CollectResult(entries, missing, sourceNames.toList())
    }

    /** 取数结果：命中表、缺失表名、命中表所在数据源名（S3 来源披露；保序去重）。 */
    internal data class CollectResult(
        val entries: MutableList<McpSchemaFormatter.TableEntry>,
        val missing: MutableList<String>,
        val sourceNames: List<String> = emptyList(),
    )

    /**
     * 单数据源扫描（一次遍历 → 表名索引）；[diagnostics] 非空时记一行实况供错误文案自解释。
     *
     * T-PerfSweep6 T-PS6-02：命中跨调用索引时**零遍历**（不再调 [readModelTables]，也不再逐列反射）。
     * T-PerfSweep7 4b（性能报告发现 6）：两处 scan 实况 INFO 降 DEBUG——命中路径**每次调用**都会
     * 拼行写 idea.log（30s TTL 窗口内每次 sqlv_schema_context 一条），持续调用期是纯噪音；
     * 实况文本不变地进 [diagnostics]（错误文案自解释不受影响），需要排障时开 DEBUG 仍可见。
     */
    private fun scanSource(ds: Any, diagnostics: MutableList<String>?): Map<String, McpSchemaFormatter.TableEntry> {
        val target = targetDatabase(ds)
        cachedIndex(ds, target)?.let { cached ->
            val line = "ds=${dsName(ds)} targetDb=${target ?: "-"} 命中跨调用索引（T-PS6-02）" +
                " indexed=${cached.size}"
            LOG.debug("McpSchemaContextService scan: $line")
            diagnostics?.add(line)
            return cached
        }
        val candidates = modelTablesForTest?.invoke(ds) ?: readModelTables(ds)
        val index = indexModelTables(candidates, target)
        storeIndex(ds, target, index)
        val line = "ds=${dsName(ds)} targetDb=${target ?: "-"} modelTables=${candidates.size} indexed=${index.size}"
        LOG.debug("McpSchemaContextService scan: $line")
        diagnostics?.add(line)
        return index
    }

    // ----------
    // T-PerfSweep6 T-PS6-02：调用内索引 → **跨调用持有**（性能报告发现 2）。
    //
    // 背景：`sqlv_schema_context` 每次调用都重建全量索引——遍历目标数据源整个 DasModel，
    // 且为**所有**表所有列逐列走反射链（每列 4 invoke + 3 runCatching），最终只输出 ≤limit 张表。
    // 而同一数据源的模型在两次调用之间通常完全没变（只有 DDL 才变）。
    //
    // 缓存纪律三条，缺一即脏读或泄漏：
    //  ① **弱键**：键 = 数据源对象身份（DBN 数据源无稳定 equals，沿用既有 IdentityHashMap 口径），
    //     值挂 WeakHashMap——数据源删除后条目随弱键回收（照 `DatabaseSchemaResolver.metaCache` 纪律）。
    //     注意与既有「调用内索引」（`collectEntries` 里的 IdentityHashMap）是**两回事**：那个只在
    //     单次调用内存活、随调用丢弃；本缓存跨调用存活，故必须弱键 + 失效面。
    //  ② **失效双保险**：接 `MetadataInvalidator` 登记表（DDL/事务提交后 O(1) 清空）+ TTL 上界
    //     （[INDEX_TTL_MS] = 30s，与 `DatabaseSchemaResolver` 负缓存同量级）。任一条单独都不够：
    //     只靠 TTL 会让 DDL 建表后 30s 内查不到新表，只靠失效面则「外部改库」（别人建表）无感知。
    //  ③ **测试缝**：本对象是进程级单例，缓存是静态状态、同 JVM 多测试类共享
    //     （默认 maxParallelForks=1、非 testDatabase 无 forkEvery）——用例必须能清，
    //     否则读数随用例顺序漂移（形态对照 `MetadataInvalidator.clearParseCacheForTest`）。
    //
    // **库名维度一并校验**：索引内容依赖 [targetDatabase]（同名表跨 schema 只收目标库那张），
    // 故缓存条目记下建索引时的目标库名，取值时重新求值并比对——不一致即视为未命中重扫
    // （URL 换库 / 重连场景不脏读）。重求值只是一次 `getConnectionConfig().getUrl()` 反射，
    // 相对 DasModel 全遍历可忽略。
    // ----------

    /** 跨调用索引 TTL 上界（毫秒）。 */
    internal const val INDEX_TTL_MS = 30_000L

    /** 测试专用 TTL 旋钮（生产恒等于 [INDEX_TTL_MS]；调短/归零验证过期语义）。 */
    @Volatile internal var indexTtlMs: Long = INDEX_TTL_MS

    /** 跨调用索引容量上界（超上限整体清空——fail safe 方向，与负缓存同口径）。 */
    private const val INDEX_CACHE_MAX = 64

    private class CachedIndex(
        val targetDb: String?,
        val builtAtMs: Long,
        val index: Map<String, McpSchemaFormatter.TableEntry>,
    )

    private val indexCache =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, CachedIndex>())

    init {
        // T-PS6-02 失效面②：DDL/事务提交后清空跨调用索引。**自登记**（而非在 MetadataInvalidator.init
        // 里加行）——缓存是本对象私有状态，登记点与缓存同处一地，改动面收敛在本文件。
        cn.song.mybatisplus.visualizer.execute.MetadataInvalidator.register("McpSchemaContextService.indexCache") {
            clearIndexCache()
        }
    }

    /** 跨调用索引清空（DDL 失效链与测试缝共用；引用置空语义，幂等无害）。 */
    internal fun clearIndexCache() {
        synchronized(indexCache) { indexCache.clear() }
    }

    /** T-PS6-02 测试缝：当前缓存的数据源条目数。 */
    internal fun indexCacheSize(): Int = synchronized(indexCache) { indexCache.size }

    /** 取缓存索引；库名维度不符或已过 TTL 一律视为未命中（过期条目惰性移除）。 */
    private fun cachedIndex(ds: Any, targetDb: String?): Map<String, McpSchemaFormatter.TableEntry>? {
        val entry = synchronized(indexCache) { indexCache[ds] } ?: return null
        if (entry.targetDb != targetDb) return null
        // 严格小于：TTL=0 语义 = 立即过期（同毫秒内也不新鲜），与 isNegativeCached 同边界口径
        if (System.currentTimeMillis() - entry.builtAtMs < indexTtlMs) return entry.index
        synchronized(indexCache) { indexCache.remove(ds) }
        return null
    }

    private fun storeIndex(ds: Any, targetDb: String?, index: Map<String, McpSchemaFormatter.TableEntry>) {
        synchronized(indexCache) {
            if (indexCache.size >= INDEX_CACHE_MAX) indexCache.clear()
            indexCache[ds] = CachedIndex(targetDb, System.currentTimeMillis(), index)
        }
    }

    /**
     * 候选表 → 表名索引（P4 纯逻辑层，可直测）。
     *
     * 同名表跨 schema 时**只收目标库那张**：不在 [targetDatabase] 命名空间下的候选直接丢弃
     * （不合并列——一期把两张表的列并进一个 Map，正是污染的直接成因）。
     * [targetDatabase] 为 null（URL 未带库）时不限定，保持一期按名匹配口径。
     */
    internal fun indexModelTables(
        candidates: List<ModelTable>,
        targetDatabase: String?,
    ): Map<String, McpSchemaFormatter.TableEntry> {
        val out = LinkedHashMap<String, McpSchemaFormatter.TableEntry>()
        for (candidate in candidates) {
            if (targetDatabase != null && !inTargetSchema(candidate.ancestorNames, targetDatabase)) continue
            // 与一期「无列无注释 = 未找到」口径一致（未连接/内省未完成时不冒充命中）
            if (candidate.columns.isEmpty() && candidate.comment == null) continue
            val key = candidate.name.lowercase()
            if (out.containsKey(key)) continue
            out[key] = McpSchemaFormatter.TableEntry(candidate.name, candidate.comment, candidate.columns)
        }
        return out
    }

    /** 祖先命名空间链里是否含目标库名（大小写不敏感；MySQL 的「库」与 PG 的 schema 同链处理）。 */
    internal fun inTargetSchema(ancestorNames: List<String>, targetDatabase: String): Boolean =
        ancestorNames.any { it.equals(targetDatabase, ignoreCase = true) }

    /** 数据源连接 URL 里的目标库名（取不到 → null，调用方退回按名匹配）。 */
    private fun targetDatabase(ds: Any): String? {
        dataSourceUrlForTest?.let { return JdbcUrlDatabase.nameFromUrl(it(ds)) }
        return runCatching {
            val config = Class.forName(DAS_DATA_SOURCE).getMethod("getConnectionConfig").invoke(ds)
            val url = config?.let { Class.forName(RAW_CONNECTION_CONFIG).getMethod("getUrl").invoke(it) } as? String
            JdbcUrlDatabase.nameFromUrl(url)
        }.getOrNull()
    }

    /**
     * F77（T-PerfSweep23）：dsName 反射 Method 缓存——来源披露从每数据源一次扩为
     * **每命中表一次**后（S3 命中表所在数据源名逐表取），Class.forName+getMethod
     * 不再逐次付（negative 也缓存：Community/无 Database 插件下 forName 恒抛，
     * 缓 null 后逐表零反射）。
     */
    private val dasGetNameMethod: java.lang.reflect.Method? =
        runCatching { Class.forName(DAS_DATA_SOURCE).getMethod("getName") }.getOrNull()

    /** 数据源显示名（S3 来源披露与日志用；取不到返回 `?`，披露侧跳过）。 */
    private fun dsName(ds: Any): String =
        dsNameForTest?.invoke(ds)
            ?: runCatching {
                dasGetNameMethod?.invoke(ds) as? String
            }.getOrNull()
            ?: "?"

    /**
     * 读一个数据源 DasModel 里的全部表（表注释 + 列元数据 + 命名空间祖先链）。
     *
     * 反射链逐条对齐 DatabaseSchemaResolver.collectFromDataSource 的实测口径（243/262 双版本）：
     * `getModel()` 声明在 DasDataSource；`traverser()` 返回 JBTreeTraverser（**非 JBIterable**，
     * 只能走 `java.lang.Iterable.iterator()`）；`ObjectKind.COLUMN` 是 DBN 手写 type-safe enum，
     * `enumConstants` 恒 null 必须 `getField`；列类型 `DasTypedObject.getDataType().getSpecification()`；
     * `DasTypedObject.isNotNull()`。任何一步失败 → 空索引（上层报 SCHEMA_NOT_AVAILABLE），不抛。
     */
    private fun readModelTables(ds: Any): List<ModelTable> = runCatching {
        val dasTableClass = Class.forName(DAS_TABLE)
        val dasColumnClass = Class.forName(DAS_COLUMN)
        val objectKindClass = Class.forName(OBJECT_KIND)
        val columnKind = objectKindClass.getField("COLUMN").get(null) ?: return@runCatching emptyList()
        val model = Class.forName(DAS_DATA_SOURCE).getMethod("getModel").invoke(ds)
            ?: return@runCatching emptyList()
        val traverser = Class.forName(DAS_MODEL).getMethod("traverser").invoke(model)
            ?: return@runCatching emptyList()
        // traverser 是 JBTreeTraverser（非 JBIterable）：只能按 java.lang.Iterable 契约迭代
        val elements = traverser as? Iterable<*> ?: return@runCatching emptyList()
        val iterator = elements.iterator()

        val dasObjectClass = Class.forName(DAS_OBJECT)
        val getName = dasObjectClass.getMethod("getName")
        val getComment = dasObjectClass.getMethod("getComment")
        val getDasParent = dasObjectClass.getMethod("getDasParent")
        val getChildren = dasObjectClass.getMethod("getDasChildren", objectKindClass)
        val dasTypedObjectClass = Class.forName(DAS_TYPED_OBJECT)
        val getDataType = dasTypedObjectClass.getMethod("getDataType")
        val getSpecification = Class.forName(DATA_TYPE).getMethod("getSpecification")
        val isNotNull = dasTypedObjectClass.getMethod("isNotNull")

        val out = ArrayList<ModelTable>()
        while (iterator.hasNext()) {
            val element = iterator.next() ?: continue
            if (!dasTableClass.isInstance(element)) continue
            val name = getName.invoke(element) as? String ?: continue
            if (name.isBlank()) continue
            val columns = ArrayList<McpSchemaFormatter.ColumnLine>()
            (getChildren.invoke(element, columnKind) as? Iterable<*>)?.forEach { col ->
                if (col == null || !dasColumnClass.isInstance(col)) return@forEach
                val columnName = getName.invoke(col) as? String ?: return@forEach
                if (columnName.isBlank()) return@forEach
                val comment = getComment.invoke(col) as? String
                val typeSpec = runCatching {
                    getDataType.invoke(col)?.let { getSpecification.invoke(it) } as? String
                }.getOrNull()
                val notNull = runCatching { isNotNull.invoke(col) as? Boolean }.getOrNull()
                // 收录条件与一期一致（放宽到 NOT NULL 列）：clean DDL 的列不被过滤掉
                if (!comment.isNullOrBlank() || !typeSpec.isNullOrBlank() || notNull == true) {
                    columns.add(
                        McpSchemaFormatter.ColumnLine(
                            name = columnName,
                            typeSpec = typeSpec?.takeIf { it.isNotBlank() },
                            comment = comment?.takeIf { it.isNotBlank() },
                            notNull = notNull,
                        ),
                    )
                }
            }
            out.add(
                ModelTable(
                    name = name,
                    ancestorNames = ancestorNames(element, getName, getDasParent),
                    comment = (getComment.invoke(element) as? String)?.takeIf { it.isNotBlank() },
                    columns = columns,
                ),
            )
        }
        out
    }.getOrElse { e ->
        LOG.warn("McpSchemaContextService: 读 DasModel 失败（本次调用按未找到披露）", e)
        emptyList()
    }

    /** 自表向上的命名空间名链（[MAX_ANCESTOR_DEPTH] 层为界；模型根非 DasObject 时自然终止）。 */
    private fun ancestorNames(
        table: Any,
        getName: java.lang.reflect.Method,
        getDasParent: java.lang.reflect.Method,
    ): List<String> {
        val out = ArrayList<String>()
        var current: Any? = runCatching { getDasParent.invoke(table) }.getOrNull()
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            (runCatching { getName.invoke(current) }.getOrNull() as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { out.add(it) }
            current = runCatching { getDasParent.invoke(current) }.getOrNull()
            depth++
        }
        return out
    }
}
