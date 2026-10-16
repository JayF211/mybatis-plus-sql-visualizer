package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import java.security.MessageDigest

/**
 * P-05：参数历史缓存——LRU 淘汰 + 持久化。
 *
 * 同一实体类的查询在不同预览间复用上次填的参数值（按 `表名 + SQL 模板指纹` 缓存）。
 *  - 内存缓存：[LinkedHashMap] accessOrder=true（LRU），运行时快速读写。
 *  - 持久化：[PluginSettings.State.paramHistoryJson]（JSON 字符串），IDE 重启后恢复。
 *  - 缓存上限 [MAX_HISTORY_SIZE] = 50 条（LRU 淘汰），单条无 TTL。
 *
 * 序列化/反序列化用自研最小 JSON（避免引入新依赖）；格式：
 * ```json
 * [{"key":"tbl:abcdef...",{"source1":"val1","source2":"val2"}}]
 * ```
 *
 * R22 T-04（命名方案，D2 定案）：存储结构从「单方案」升级「多方案」——
 *  - **默认方案**（[DEFAULT_SCHEME]）：既有自动召回链路的数据落点，仍持久化到
 *    `PluginSettings.State.paramHistoryJson`（旧字段语义不变——降级兼容 + 存量零迁移）；
 *  - **命名方案**：方案名 → (缓存键 → 快照)，持久化到 `paramSchemesJson`；激活方案落 `paramActiveScheme`。
 *  旧 `paramHistoryJson` 数据在恢复时自动成为默认方案内容（**D2：旧数据自动成为「默认方案」零迁移损失**），
 *  老用户无感。`save`/`recall` 按激活方案路由——未触碰方案的调用方（含既有自动保存/召回链）行为逐字节不变。
 */
class ParamHistoryCache(private val maxSize: Int = MAX_HISTORY_SIZE) {

    /** LRU 内存缓存（accessOrder=true：访问顺序，最近访问在尾部）。默认方案（R22 T-04）的数据落点。 */
    private val cache = LinkedHashMap<String, Map<String, String>>(maxSize + 1, 0.75f, true)

    /**
     * F54（D31 道 B，性能十八轮）：**桶外粗锁**——保全部 accessOrder LinkedHashMap 桶
     * （默认桶 [cache] 与命名桶）跨线程结构一致。
     *
     * 为什么不是 CHM/读写锁（性能十九轮对上轮建议的收敛定案）：accessOrder=true 的
     * **读即回写**（get 触发 afterNodeAccess 改链）——CHM 只保桶级原子但丢 LRU 语义、
     * 读写锁的读锁内含结构回写（「读锁」名不副实）。粗锁＝单把互斥锁包全部桶操作
     * （含持久化序列化的桶遍历快照），自动召回链争用面＝每操作一次无竞争 synchronized
     * （~15-20ns 量级），换结构一致 + LRU 序不漂移。顶层 [schemes] 的 CHM/COWAL（F50）
     * 不在锁内——桶引用的搬运（建桶/换名/删除）自身线程安全，桶**内容**操作才需要锁。
     */
    private val bucketLock = Any()

    /**
     * R22 T-04：命名方案存储——方案名 → (缓存键 → 参数快照)（逐方案 LRU）。
     *
     * F50（T-PerfQuick5）：顶层结构 **ConcurrentHashMap 化**——后台 pooled 写回
     * （[saveForScheme]，SqlPreviewPanel 写回线程）与 EDT 读（[schemeNames]/对话框渲染器）
     * 此前共用裸 LinkedHashMap 无任何同步保护，结构写与遍历并发即 CME/丢条目面
     * （性能十七轮 §4.5，T-Perf-24 同族形态的面扩大）。内层逐方案 LRU（accessOrder）
     * 维持原样——与默认方案 [cache] 同族既知形态，本轮在案不动。
     */
    private val schemes = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, Map<String, String>>>()

    /**
     * F50：命名方案**创建序**索引——CHM 无序，而方案清单的「创建序」是 UI 既有语义
     * （对话框列表/下拉顺序，ParamHistoryCacheTest 创建序断言在案）。序列化顺序也按本索引
     * 走（[serializeSchemesToJson]），持久化往返创建序不漂移。复制写清单（读多写少、
     * 写仅限方案操作与惰性建桶）。
     */
    private val schemeOrder = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * T-DevAcceptFix2 ①（20261006 第二轮验收）：激活方案**按缓存键（=方法/SQL）记录**。
     *
     * 原实现为全局单值——预设名清单与激活指针全局共享，用户实测「所有的方法都是一套预设」。
     * 现：本表按缓存键记激活名（键=方法/SQL，与快照同分桶）；
     * [legacyActiveScheme] 承接旧持久化字段 `paramActiveScheme`（全局单值）作为
     * **未记录键的回落值**，且仅在「该名字在当前键下确有条目」时才生效（否则回落默认方案）
     * ——迁移安全（V92）。
     */
    private val activeSchemeByKey = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 旧全局单值激活方案（持久化兼容 + 未记录键的回落；语义见 [activeSchemeFor]）。
     * F54 微（性能十八轮 :51）：@Volatile——EDT 写 / pooled 读窗口窄但真实（写未发布时
     * 读侧可能长眼看旧值），引用写原子性由 volatile 发布补齐。
     */
    @Volatile
    private var legacyActiveScheme: String = DEFAULT_SCHEME

    /**
     * T-M19-StorageClear：本实例已同步到的应用级存储代际。
     *
     * 设置页清理会递增全局代际并在同一互斥区清空所有存活实例；异步写回必须携带取快照时
     * 的代际，代际不一致即拒绝，防止「用户确认清理 → 旧面板 500ms/pooled 写回迟到」把明文
     * 参数重新写进 mp-sql-visualizer.xml。
     */
    @Volatile
    private var observedStorageGeneration: Long = 0L

    init {
        // 注册、取代际与持久化恢复必须和应用级清理串行：否则可能在清理刚写空后又恢复旧字段。
        synchronized(storageMutationLock) {
            observedStorageGeneration = storageGeneration.get()
            liveInstances.add(this)
            restoreFromPersistence()
        }
    }

    /**
     * 计算缓存键 = `tableName:fingerprint`（无表名返回 null，不缓存）。
     *
     * @param tableName 表名（null 表示纯文本预览，不缓存）
     * @param rawSql    原始 SQL（用于计算指纹）
     * @return 缓存键，或 null（无表名时不缓存）
     */
    /**
     * 计算缓存键（R22 T-01，D1=A 定案）：有表名 = `tableName:fingerprint`（存量兼容）；
     * **无表名 = `sql:` + SQL 文本规范化指纹**（dedupeKey 同源口径——`normalizeSqlWhitespace`
     * + `sqlFingerprint`），纯文本/自定义 DAO 预览的调试参数不再丢失。
     *
     * @param tableName 表名（null 表示纯文本预览——R22 起以规范化指纹为键持久化）
     * @param rawSql    原始 SQL（用于计算指纹）
     * @return 缓存键（恒非 null——rawSql 空串也有指纹；调用方仍可按需判空）
     */
    fun computeKey(tableName: String?, rawSql: String): String? {
        if (!tableName.isNullOrEmpty()) return "$tableName:${fingerprint(rawSql)}"
        val normalized = cn.song.mybatisplus.visualizer.log.normalizeSqlWhitespace(rawSql)
        return "sql:${cn.song.mybatisplus.visualizer.log.sqlFingerprint(normalized)}"
    }

    /**
     * 保存参数值到缓存（LRU 淘汰 + 持久化）。
     *
     * R22 T-04：按**激活方案**路由（未触碰方案的调用方 = 默认方案，行为不变）。
     * 线程池延迟落盘场景（快照与写入可能跨方案切换）请用 [saveForScheme] 显式带方案名。
     *
     * @param key    缓存键（由 [computeKey] 生成）
     * @param params 参数映射（source → value）
     */
    fun save(key: String, params: Map<String, String>) {
        val generation = generationToken()
        saveForScheme(activeSchemeFor(key), key, params, generation)
    }

    /**
     * R22 T-04：把快照写入**指定方案**——调用方取快照时的方案名随行落库，
     * 防止「EDT 取快照 → 线程池写回」窗口内用户切换方案导致的串桶。
     *
     * T-M19-StorageClear：异步调用方必须传取快照时的 [expectedGeneration]。设置页清理与写入
     * 共用 [storageMutationLock]；清理后到达的旧代际任务直接拒绝，不得复活已清参数。
     *
     * @param scheme 目标方案名（空串 = 默认方案）
     * @param key    缓存键
     * @param params 参数映射（source → value）
     * @return 是否实际写入；空快照或代际已失效返回 false
     */
    fun saveForScheme(
        scheme: String,
        key: String,
        params: Map<String, String>,
        expectedGeneration: Long = generationToken(),
    ): Boolean {
        if (params.isEmpty()) return false
        return synchronized(storageMutationLock) {
            if (expectedGeneration != storageGeneration.get() || observedStorageGeneration != expectedGeneration) {
                return@synchronized false
            }
            // F54：桶内容写 + 淘汰同一临界区（entries 捕获一次——旧实现两次 entriesForScheme 查表顺带收敛）
            synchronized(bucketLock) {
                val entries = entriesForScheme(scheme)
                entries[key] = params
                enforceMaxSize(entries)
            }
            persistToStorage()
            true
        }
    }

    /**
     * 召回历史参数值（R22 T-04 起按激活方案路由）。
     *
     * @param key 缓存键
     * @return 历史参数映射，未命中返回 null
     */
    fun recall(key: String): Map<String, String>? {
        // F54：accessOrder 读即回写 LRU 序——读也在桶外粗锁临界区内（这是读写锁被否的根因）
        return synchronized(bucketLock) {
            val active = activeSchemeFor(key)
            val entries = if (active == DEFAULT_SCHEME) cache else schemes[active] ?: return null
            entries[key]?.also {
                // 访问后LinkedHashMap自动更新顺序（accessOrder=true），无需手动处理
                enforceMaxSize(entries)
            }
        }
    }

    /**
     * 清空参数历史（全部存活实例 + 持久化字段）。R22 T-04：含全部命名方案与激活方案复位。
     * T-M19-StorageClear：这是应用级失效动作，不再只清调用实例；仅把 PluginSettings 中四个参数
     * 历史字段写空，绝不删除共享的 mp-sql-visualizer.xml，也不触碰其中其他设置组件。
     */
    fun clear() = clearPersistedHistory(this)

    /** 当前实例的存储代际令牌；异步写回须在 EDT 取快照时一并捕获。 */
    internal fun generationToken(): Long = observedStorageGeneration

    /** R92: old open panels must not export cleared simulation values into fresh snapshots. */
    internal fun isCurrentGeneration(): Boolean = observedStorageGeneration == storageGeneration.get()

    /**
     * 新预览开始时接纳最新代际。showResult 已先清空并重建面板参数状态，因此此时不再携带清理前
     * 的 UI 快照；接纳后允许保存新输入。清理发生后仍停留在原预览的面板保持旧代际，任何写回均拒绝。
     */
    internal fun adoptCurrentGenerationForFreshPreview() {
        synchronized(storageMutationLock) {
            observedStorageGeneration = storageGeneration.get()
        }
    }

    /** 清空本实例的全部桶；调用方须持 [storageMutationLock]。代际不在此更新，以封住旧面板后续写回。 */
    private fun invalidateForClear() {
        synchronized(bucketLock) {
            cache.clear()
            schemes.clear()
            schemeOrder.clear()
            activeSchemeByKey.clear()
            legacyActiveScheme = DEFAULT_SCHEME
        }
    }

    // ---------------- R22 T-04：命名方案 API（D2 定案——旧数据自动成为默认方案） ----------------

    /**
     * 命名方案清单（默认方案除外，创建序）——**全量**名单（序列化遍历用）。
     * F50：创建序由 [schemeOrder] 承载；CHM 直查键拼接在尾部（极窄竞态窗内「已建桶未入序」
     * 的条目不丢），distinct 去重。
     * F54 微（性能十八轮 :138 中间分配）：原 `(schemeOrder + schemes.keys).distinct()`
     * 先拼接中间 List 再 distinct 二次分配——改 [orderedSchemeNames] 一次成型。
     */
    fun schemeNames(): List<String> = orderedSchemeNames()

    data class SchemeSnapshot(val name: String, val values: Map<String, String>)
    data class ScenarioSnapshots(val storageGeneration: Long, val schemes: List<SchemeSnapshot>)

    /** Iterating entries avoids access-order get(), so batch analysis cannot mutate LRU or active preset. */
    fun snapshotSchemesFor(key: String, limit: Int = 20): ScenarioSnapshots {
        require(limit in 1..20)
        return synchronized(storageMutationLock) {
            val generation = storageGeneration.get()
            if (observedStorageGeneration != generation) return@synchronized ScenarioSnapshots(generation, emptyList())
            synchronized(bucketLock) {
                val result = mutableListOf<SchemeSnapshot>()
                fun add(name: String, entries: Map<String, Map<String, String>>) {
                    if (result.size >= limit) return
                    entries.entries.firstOrNull { it.key == key }?.value?.let {
                        result += SchemeSnapshot(name, it.toMap())
                    }
                }
                add(DEFAULT_SCHEME, cache)
                for (name in orderedSchemeNames()) {
                    schemes[name]?.let { add(name, it) }
                    if (result.size >= limit) break
                }
                ScenarioSnapshots(generation, result.toList())
            }
        }
    }

    /**
     * 创建序方案名清单（一次成型，无中间 List）：序表先入、CHM 直查键补尾，HashSet 去重。
     * 语义与旧 `(schemeOrder + schemes.keys).distinct()` 逐值等价。
     */
    private fun orderedSchemeNames(): List<String> {
        if (schemeOrder.isEmpty()) return schemes.keys.toList()
        val seen = HashSet<String>(schemeOrder.size + schemes.size)
        val out = ArrayList<String>(schemeOrder.size + schemes.size)
        for (name in schemeOrder) if (seen.add(name)) out.add(name)
        for (name in schemes.keys) if (seen.add(name)) out.add(name)
        return out
    }

    /**
     * T-DevAcceptFix2 ①：**指定缓存键（=方法/SQL）可见**的命名方案清单（创建序）——
     * UI（面板下拉/管理对话框）只列「在本方法下确有条目」的预设，达成「每个方法一套预设、互不干扰」。
     * key 为 null/空（无预览上下文）→ 空表（只有默认方案）。
     * F54：桶级 containsKey 探测入临界区（读侧与桶扩容/结构写互斥）。
     */
    fun schemeNamesFor(key: String?): List<String> {
        if (key.isNullOrEmpty()) return emptyList()
        val names = orderedSchemeNames()
        return synchronized(bucketLock) {
            names.filter { schemes[it]?.containsKey(key) == true }
        }
    }

    /**
     * T-DevAcceptFix2 ①：指定缓存键的激活方案名。取值链：按键记录 → 旧全局值回落 → 默认方案；
     * 回落值仅在「该名字在该键下确有条目」时生效（否则默认方案）——迁移安全（V92）。
     * key 为 null/空 → 默认方案。F54：桶级探测入临界区。
     */
    fun activeSchemeFor(key: String?): String {
        if (key.isNullOrEmpty()) return DEFAULT_SCHEME
        val stored = activeSchemeByKey[key] ?: legacyActiveScheme
        if (stored == DEFAULT_SCHEME) return DEFAULT_SCHEME
        return if (synchronized(bucketLock) { schemes[stored]?.containsKey(key) == true }) stored else DEFAULT_SCHEME
    }

    /** 是否存在指定命名方案。 */
    fun hasScheme(name: String): Boolean = schemes.containsKey(name)

    /**
     * 旧全局激活方案名（**回落值视图**，数据模型层测试用）——T-DevAcceptFix2 ① 起 UI 一律走
     * [activeSchemeFor]（按键）。本视图返回 [legacyActiveScheme]（每次切换/另存都会同步更新，
     * 语义＝「最近一次激活的方案名」）。生产侧调用点已全部迁移，禁在新代码中使用。
     */
    internal fun activeSchemeName(): String = legacyActiveScheme

    /**
     * 单参切换（**无键上下文**，数据模型层测试用）——只更新回落值，不建按键记录。
     * 生产侧（面板/对话框）一律走两参形态。
     */
    internal fun switchScheme(name: String) {
        switchScheme(name, null)
    }

    /**
     * 切换指定缓存键的激活方案（面板下拉驱动）。未知方案名忽略（防脏态）；同名幂等不重复落盘。
     *
     * F49（T-PerfQuick5）：切换**只写激活指针**（[persistActiveSchemePointer]），不再走
     * [persistToStorage] 全量序列化——方案内容未变，重序列化全部命名方案 + 无条件重写
     * legacy 字段属纯写放大，且发生在 EDT 同步路径（性能十七轮 §5.3）。
     * T-DevAcceptFix2 ①：指针按键记录（key 空 = 无预览上下文，仅更新回落值不建记录）。
     */
    fun switchScheme(name: String, key: String?) = synchronized(storageMutationLock) {
        if (observedStorageGeneration != storageGeneration.get()) return@synchronized
        if (name != DEFAULT_SCHEME && !schemes.containsKey(name)) return@synchronized
        if (key.isNullOrEmpty()) {
            legacyActiveScheme = name
            persistActiveSchemePointer()
            return@synchronized
        }
        if (activeSchemeByKey[key] == name && legacyActiveScheme == name) return@synchronized
        activeSchemeByKey[key] = name
        legacyActiveScheme = name
        persistActiveSchemePointer()
    }

    /**
     * 另存为命名方案：把 [params] 作为 [key] 的快照写入 [name]（该键下已存在同名条目则覆盖），
     * 并把该键激活切到 [name]。默认方案名/空名/空快照返回 [SchemeOpResult.INVALID]（调用方
     * 此前静默守卫的显式化——对话框在更早的输入环节已拦，行为不变）；方案名个数达
     * [MAX_SCHEME_COUNT] 上限返回 [SchemeOpResult.LIMIT_REACHED]（F49）。
     *
     * T-DevAcceptFix2 ①：查重口径从「名字全局唯一」改为「**该键下**是否已有同名条目」——
     * 同名预设可在不同方法下各自存在、各存各值（D71 推荐案；重命名/删除维持名字级）。
     */
    fun saveAsScheme(name: String, key: String, params: Map<String, String>): SchemeOpResult =
        synchronized(storageMutationLock) {
            if (observedStorageGeneration != storageGeneration.get()) return@synchronized SchemeOpResult.INVALID
            if (name.isBlank() || name == DEFAULT_SCHEME || params.isEmpty()) return@synchronized SchemeOpResult.INVALID
            if (!schemes.containsKey(name) && schemes.size >= MAX_SCHEME_COUNT) {
                return@synchronized SchemeOpResult.LIMIT_REACHED
            }
            // F54：桶内容写 + 淘汰入临界区（CHM 原子建桶后桶内 put/淘汰仍是裸结构操作）
            synchronized(bucketLock) {
                val entries = entriesForScheme(name)
                entries[key] = params
                enforceMaxSize(entries)
            }
            activeSchemeByKey[key] = name
            legacyActiveScheme = name
            persistToStorage()
            SchemeOpResult.OK
        }

    /** 召回指定方案的 [key] 快照（方案不存在/键未命中返回 null）。F54：get 读即回写 → 临界区。 */
    fun recallFromScheme(name: String, key: String): Map<String, String>? =
        synchronized(bucketLock) { schemes[name]?.get(key) }

    /** 指定方案当前条目数（缓存键数，测试/对话框摘要用）。 */
    fun schemeEntryCount(name: String): Int = synchronized(bucketLock) { schemes[name]?.size ?: 0 }

    /** R22 T-04：方案操作结果（对话框状态行分流）。F49 增 LIMIT_REACHED（个数上限）与 INVALID（入参不合规）。 */
    enum class SchemeOpResult { OK, DUPLICATE, NOT_FOUND, DEFAULT_PROTECTED, LIMIT_REACHED, INVALID }

    /**
     * 重命名方案（激活指针随迁）。
     * F50：CHM 化后不再整表重建——remove+put 保序（创建序由 [schemeOrder] 原位换名承载），
     * 语义与旧 LinkedHashMap 重建逐值等价（NOT_FOUND/DUPLICATE 判定次序不变）。
     */
    fun renameScheme(oldName: String, newName: String): SchemeOpResult = synchronized(storageMutationLock) {
        if (observedStorageGeneration != storageGeneration.get()) return@synchronized SchemeOpResult.INVALID
        if (oldName == DEFAULT_SCHEME) return@synchronized SchemeOpResult.DEFAULT_PROTECTED
        val entries = schemes[oldName] ?: return@synchronized SchemeOpResult.NOT_FOUND
        if (newName.isBlank() || newName == DEFAULT_SCHEME || schemes.containsKey(newName)) {
            return@synchronized SchemeOpResult.DUPLICATE
        }
        schemes.remove(oldName)
        schemes[newName] = entries
        val idx = schemeOrder.indexOf(oldName)
        if (idx >= 0) {
            schemeOrder.removeAt(idx)
            schemeOrder.add(idx, newName)
        }
        // T-DevAcceptFix2 ①：重命名维持**名字级**（D71 定案）——按键指针与回落值随迁
        activeSchemeByKey.entries.removeIf { it.value == oldName }
        synchronized(bucketLock) {
            activeSchemeByKey.keys.toList().forEach { k ->
                if (schemes.getValue(newName).containsKey(k)) activeSchemeByKey[k] = newName
            }
        }
        if (legacyActiveScheme == oldName) legacyActiveScheme = newName
        persistToStorage()
        SchemeOpResult.OK
    }

    /** 删除方案（激活方案被删时归位默认方案）。T-DevAcceptFix2 ①：按键指针与回落值同步摘除。 */
    fun deleteScheme(name: String): SchemeOpResult = synchronized(storageMutationLock) {
        if (observedStorageGeneration != storageGeneration.get()) return@synchronized SchemeOpResult.INVALID
        if (name == DEFAULT_SCHEME) return@synchronized SchemeOpResult.DEFAULT_PROTECTED
        if (schemes.remove(name) == null) return@synchronized SchemeOpResult.NOT_FOUND
        schemeOrder.remove(name)
        activeSchemeByKey.entries.removeIf { it.value == name }
        if (legacyActiveScheme == name) legacyActiveScheme = DEFAULT_SCHEME
        persistToStorage()
        SchemeOpResult.OK
    }

    /** 当前缓存条目数（默认方案；测试断言用）。 */
    internal fun size(): Int = synchronized(bucketLock) { cache.size }

    /** 是否包含指定键（该键的激活方案；测试断言用，触发 LRU 顺序更新）。 */
    internal fun containsKey(key: String): Boolean =
        synchronized(bucketLock) { entriesForScheme(activeSchemeFor(key)).containsKey(key) }

    /** R22 T-04：指定方案的条目视图（默认方案 = [cache]；命名方案惰性建，同 LRU 语义）。
     * F50：CHM 原子建桶（computeIfAbsent，原 getOrPut 非原子），建桶同时补创建序（幂等）。 */
    private fun entriesForScheme(scheme: String): LinkedHashMap<String, Map<String, String>> {
        if (scheme == DEFAULT_SCHEME) return cache
        val entries = schemes.computeIfAbsent(scheme) { newSchemeEntries() }
        schemeOrder.addIfAbsent(scheme)
        return entries
    }

    /** R22 T-04：新建方案条目映射（与默认方案同 LRU 形态）。 */
    private fun newSchemeEntries(): LinkedHashMap<String, Map<String, String>> =
        LinkedHashMap(maxSize + 1, 0.75f, true)

    /** LRU 淘汰：[entries] 超过 maxSize 时移除最久未访问的条目（头部）。F54：调用方须持 [bucketLock]。 */
    private fun enforceMaxSize(entries: LinkedHashMap<String, Map<String, String>>) {
        while (entries.size > maxSize) {
            val oldest = entries.keys.first()
            entries.remove(oldest)
        }
    }

    /**
     * 全量持久化到 PluginSettings（IDE 环境）：命名方案 + 激活方案入新字段，默认方案继续写旧字段（降级兼容）；测试环境回落静态存储。
     * F49 计数缝：[persistProbe] 非空时回调一次（基准/守卫测试数「全量序列化次数」）。
     * F54：两段桶遍历序列化（[serializeSchemesToJson]/[serializeToJson]）在 [bucketLock]
     * 临界区内取**一致性快照**（pooled 写回与持久化并发时不再有 CME 面）；存储写入
     * （PluginSettings/fallbackStorage）留在锁外——锁只保桶结构，不背 IO。
     */
    private fun persistToStorage() {
        persistProbe?.invoke()
        val schemesJson: String
        val legacyJson: String
        synchronized(bucketLock) {
            schemesJson = serializeSchemesToJson()
            legacyJson = serializeToJson(cache)
        }
        val active = legacyActiveScheme
        val activeByKey = serializeActiveByKey()
        val written = runCatching {
            val state = PluginSettings.getInstance().state
            state.paramSchemesJson = schemesJson
            state.paramActiveScheme = active
            state.paramActiveSchemeByKey = activeByKey
        }.isSuccess
        if (!written) {
            // 测试环境（无 IDE 服务）：回落静态存储，保证持久化往返可测
            synchronized(fallbackStorage) {
                fallbackStorage[SCHEMES_STORAGE_KEY] = schemesJson
                fallbackStorage[ACTIVE_SCHEME_STORAGE_KEY] = active
                fallbackStorage[ACTIVE_BY_KEY_STORAGE_KEY] = activeByKey
            }
        }
        // 旧字段保持既有语义：默认方案快照继续写 paramHistoryJson（旧版构建可读，存量零迁移的另一面）
        val legacyWritten = runCatching { PluginSettings.getInstance().state.paramHistoryJson = legacyJson }.isSuccess
        if (!legacyWritten) {
            synchronized(fallbackStorage) { fallbackStorage[STORAGE_KEY] = legacyJson }
        }
    }

    /**
     * F49：只写激活方案指针——`switchScheme` 专用轻量落盘路径，不做方案集/legacy 的
     * 全量重序列化（内容未变，重写属 EDT 同步路径上的纯写放大）。
     */
    private fun persistActiveSchemePointer() {
        val active = legacyActiveScheme
        val activeByKey = serializeActiveByKey()
        val written = runCatching {
            val state = PluginSettings.getInstance().state
            state.paramActiveScheme = active
            state.paramActiveSchemeByKey = activeByKey
        }.isSuccess
        if (!written) {
            synchronized(fallbackStorage) {
                fallbackStorage[ACTIVE_SCHEME_STORAGE_KEY] = active
                fallbackStorage[ACTIVE_BY_KEY_STORAGE_KEY] = activeByKey
            }
        }
    }

    /**
     * T-DevAcceptFix2 ①：按键激活指针序列化（`{"缓存键":"方案名", ...}`，最小 JSON 形态；
     * 复用 [escapeJson]/[unescapeJson] 与 [splitTopLevelEntries] 切分工具）。
     */
    private fun serializeActiveByKey(): String {
        if (activeSchemeByKey.isEmpty()) return ""
        val sb = StringBuilder("{")
        var first = true
        // 键排序后遍历（对齐 F50 口径）：CHM 遍历序为哈希序，不排序会使持久化 JSON 序漂移
        for (k in activeSchemeByKey.keys.sorted()) {
            val v = activeSchemeByKey[k] ?: continue
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(escapeJson(k)).append("\":\"").append(escapeJson(v)).append("\"")
        }
        sb.append("}")
        return sb.toString()
    }

    /** T-DevAcceptFix2 ①：按键激活指针反序列化（损坏/空 → 空表，不崩溃）。 */
    private fun deserializeActiveByKey(json: String): Map<String, String> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
        val result = linkedMapOf<String, String>()
        for ((k, v) in splitTopLevelEntries(trimmed.substring(1, trimmed.length - 1))) {
            val key = unescapeJson(k)
            val name = unescapeJson(v.removeSurrounding("\""))
            if (key.isNotEmpty() && name.isNotEmpty()) result[key] = name
        }
        return result
    }

    /** 从持久化恢复内存缓存（IDE 环境）；测试环境回落静态存储。
     *  R22 T-04（D2 定案）：旧 `paramHistoryJson` 数据自动成为「默认方案」——零迁移损失，老用户无感。 */
    private fun restoreFromPersistence() {
        // 旧字段 → 默认方案（D2 迁移落点：旧字段只读兼容，数据原样进默认方案）
        var legacyJson = readPersisted(STORAGE_KEY) { PluginSettings.getInstance().state.paramHistoryJson }
        if (legacyJson.isNotBlank()) {
            runCatching {
                deserializeFromJson(legacyJson).forEach { (key, params) ->
                    cache[key] = params
                }
            }
        }
        // 新字段 → 命名方案 + 激活方案（字段为空/损坏时保持默认方案激活态）
        val schemesJson = readPersisted(SCHEMES_STORAGE_KEY) { PluginSettings.getInstance().state.paramSchemesJson }
        if (schemesJson.isNotBlank()) {
            runCatching {
                deserializeSchemesFromJson(schemesJson).forEach { (name, entries) ->
                    schemes[name] = LinkedHashMap(entries)
                    schemeOrder.addIfAbsent(name) // F50：JSON 序 = 创建序，恢复时同步入序
                }
            }
        }
        var active = readPersisted(ACTIVE_SCHEME_STORAGE_KEY) { PluginSettings.getInstance().state.paramActiveScheme }
        if (active != DEFAULT_SCHEME && !schemes.containsKey(active)) active = DEFAULT_SCHEME
        legacyActiveScheme = active
        // T-DevAcceptFix2 ①：按键激活指针恢复（旧字段已作为回落值装载，未记录键自动回落它）
        val byKeyJson = readPersisted(ACTIVE_BY_KEY_STORAGE_KEY) { PluginSettings.getInstance().state.paramActiveSchemeByKey }
        if (byKeyJson.isNotBlank()) {
            runCatching {
                deserializeActiveByKey(byKeyJson).forEach { (k, v) -> activeSchemeByKey[k] = v }
            }
        }
        // 全方案统一执行上限（含 D2 迁入的默认方案）；F54：构造期 this 未逃逸、并发未起，
        // 但按 enforceMaxSize 的持锁契约统一走临界区（防后续演进把恢复挪出构造器时踩裸调）
        synchronized(bucketLock) {
            enforceMaxSize(cache)
            schemes.values.forEach { enforceMaxSize(it) }
        }
    }

    /** 读持久化值（IDE 环境：PluginSettings.State；为空回落测试环境静态存储）。 */
    private inline fun readPersisted(storageKey: String, fromState: () -> String): String {
        val fromSettings = runCatching { fromState() }.getOrDefault("")
        if (fromSettings.isNotBlank()) return fromSettings
        synchronized(fallbackStorage) { return fallbackStorage[storageKey] ?: "" }
    }

    /**
     * 自研最小 JSON 序列化（格式：`[{"key":{...params}}]`）。
     * 仅处理 String→String 映射，key/value 含特殊字符时转义。
     * R22 T-04：参数化条目源——默认方案与命名方案共用同一数组格式。
     */
    private fun serializeToJson(entries: Map<String, Map<String, String>>): String {
        if (entries.isEmpty()) return ""
        val sb = StringBuilder("[")
        entries.entries.forEachIndexed { index, (key, params) ->
            if (index > 0) sb.append(",")
            sb.append("{\"").append(escapeJson(key)).append("\":{")
            params.entries.forEachIndexed { pIdx, (pKey, pValue) ->
                if (pIdx > 0) sb.append(",")
                sb.append("\"").append(escapeJson(pKey)).append("\":\"").append(escapeJson(pValue)).append("\"")
            }
            sb.append("}}")
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * R22 T-04：命名方案序列化——`{"方案名":[{key:{...params}}], ...}`。
     * 每个方案的条目复用旧数组格式（[serializeToJson]）；空方案跳过，全空返回空串（对齐旧字段「无数据=空串」）。
     * F50：按 [schemeOrder] 创建序遍历（CHM 直查键序为哈希序，直接遍历会使持久化 JSON 序漂移）。
     */
    private fun serializeSchemesToJson(): String {
        val orderedNames = schemeNames()
        if (orderedNames.isEmpty()) return ""
        val sb = StringBuilder("{")
        var first = true
        for (name in orderedNames) {
            val entries = schemes[name] ?: continue
            if (entries.isEmpty()) continue
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(escapeJson(name)).append("\":").append(serializeToJson(entries))
        }
        sb.append("}")
        return if (first) "" else sb.toString()
    }

    /**
     * R22 T-04：命名方案反序列化（解析 `{"方案名":[...]}`；失败返回空 map——持久化数据损坏不崩溃）。
     */
    private fun deserializeSchemesFromJson(json: String): Map<String, Map<String, Map<String, String>>> {
        val result = linkedMapOf<String, Map<String, Map<String, String>>>()
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
        val content = trimmed.substring(1, trimmed.length - 1)
        for ((name, valueText) in splitTopLevelEntries(content)) {
            if (!valueText.startsWith("[") || !valueText.endsWith("]")) continue
            result[unescapeJson(name)] = deserializeFromJson(valueText)
        }
        return result
    }

    /** R22 T-04：拆顶层 `"name":value` 对（考虑嵌套括号与字符串转义；value 可为对象或数组）。 */
    private fun splitTopLevelEntries(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var depth = 0
        var colonIdx = -1
        var entryStart = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ // 跳过转义对（如 \" \\）
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> depth--
                ':' -> if (depth == 0 && colonIdx < 0) colonIdx = i
                ',' -> if (depth == 0) {
                    if (entryStart < colonIdx) {
                        val name = parseJsonString(text, entryStart).first
                        result += name to text.substring(colonIdx + 1, i).trim()
                    }
                    entryStart = i + 1
                    colonIdx = -1
                }
            }
            i++
        }
        if (entryStart < colonIdx) {
            val name = parseJsonString(text, entryStart).first
            result += name to text.substring(colonIdx + 1).trim()
        }
        return result
    }

    /**
     * 自研最小 JSON 反序列化（解析 `[{"key":{...params}}]` 格式）。
     * 解析失败返回空 map（容错：持久化数据损坏不崩溃）。
     */
    private fun deserializeFromJson(json: String): Map<String, Map<String, String>> {
        val result = linkedMapOf<String, Map<String, String>>()
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyMap()
        // 简化解析：按顶层对象拆分
        val objects = splitTopLevelObjects(trimmed.substring(1, trimmed.length - 1))
        for (obj in objects) {
            val objTrimmed = obj.trim()
            if (!objTrimmed.startsWith("{") || !objTrimmed.endsWith("}")) continue
            val content = objTrimmed.substring(1, objTrimmed.length - 1)
            // 第一个 key 是缓存键，value 是 params 对象
            val colonIdx = content.indexOf(":")
            if (colonIdx <= 0) continue
            val key = unescapeJson(parseJsonString(content, 0).first)
            val paramsObjStart = content.indexOf("{", colonIdx)
            if (paramsObjStart < 0) continue
            val paramsObj = parseJsonObject(content.substring(paramsObjStart))
            result[key] = paramsObj
        }
        return result
    }

    /** 按顶层对象拆分（考虑嵌套括号）。 */
    private fun splitTopLevelObjects(text: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result += text.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return result
    }

    /** 解析 JSON 对象（`{...}`）为 String→String 映射。 */
    private fun parseJsonObject(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
        val content = trimmed.substring(1, trimmed.length - 1)
        var i = 0
        while (i < content.length) {
            // 跳过空白与逗号
            while (i < content.length && (content[i] == ' ' || content[i] == '\t' || content[i] == ',')) i++
            if (i >= content.length) break
            val (key, nextAfterKey) = parseJsonString(content, i)
            i = nextAfterKey
            while (i < content.length && content[i] == ' ') i++
            if (i < content.length && content[i] == ':') i++
            while (i < content.length && content[i] == ' ') i++
            val (value, nextAfterValue) = parseJsonString(content, i)
            i = nextAfterValue
            result[unescapeJson(key)] = unescapeJson(value)
        }
        return result
    }

    /** 解析 JSON 双引号字符串，返回 (值, 下一个位置)。 */
    private fun parseJsonString(text: String, start: Int): Pair<String, Int> {
        var i = start
        val len = text.length
        while (i < len && text[i] != '"') i++
        if (i >= len) return "" to len
        i++ // 跳过起始引号
        val sb = StringBuilder()
        while (i < len && text[i] != '"') {
            if (text[i] == '\\' && i + 1 < len) {
                i++
                sb.append(unescapeJsonChar(text[i]))
            } else {
                sb.append(text[i])
            }
            i++
        }
        if (i < len) i++ // 跳过闭合引号
        return sb.toString() to i
    }

    private fun unescapeJsonChar(c: Char): Char = when (c) {
        '"' -> '"'
        '\\' -> '\\'
        '/' -> '/'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        else -> c
    }

    /** JSON 字符串转义（最小集：引号、反斜杠、换行、回车、制表符）。 */
    private fun escapeJson(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 字符串整体反转义（处理 \\ \" \\n 等）。 */
    private fun unescapeJson(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                sb.append(unescapeJsonChar(text[i + 1]))
                i += 2
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    companion object {
        /** T-M19-StorageClear：参数存储写与应用级清理共用互斥，保证「清理返回后旧写不复活」。 */
        private val storageMutationLock = Any()

        /** 应用级存储代际；每次确认清理递增。 */
        private val storageGeneration = java.util.concurrent.atomic.AtomicLong()

        /**
         * 存活缓存实例弱集合：设置页清理时立即清掉已打开 SqlPreviewPanel 的内存桶；弱引用不延长
         * 面板生命周期。所有访问均在 [storageMutationLock] 内。
         */
        private val liveInstances: MutableSet<ParamHistoryCache> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<ParamHistoryCache, Boolean>())

        /**
         * 设置页「清除参数历史与命名方案」的唯一入口。
         *
         * 仅清 [PluginSettings.State] 中四个参数字段与测试回落存储；共享 XML 中语言、日志、外观等
         * 其他组件状态原样保留，绝不删除 mp-sql-visualizer.xml 文件。
         */
        @JvmStatic
        fun clearPersistedHistory() = clearPersistedHistory(null)

        /** 实例 clear 用于测试隔离/显式复位：发起实例可立即按新代际继续使用；其他已打开实例保持失效。 */
        private fun clearPersistedHistory(origin: ParamHistoryCache?) {
            synchronized(storageMutationLock) {
                val nextGeneration = storageGeneration.incrementAndGet()
                liveInstances.toList().forEach { it.invalidateForClear() }
                origin?.observedStorageGeneration = nextGeneration

                val stateWrite = runCatching {
                    PluginSettings.getInstance().state.apply {
                        paramHistoryJson = ""
                        paramSchemesJson = ""
                        paramActiveScheme = DEFAULT_SCHEME
                        paramActiveSchemeByKey = ""
                    }
                }
                val app = runCatching { com.intellij.openapi.application.ApplicationManager.getApplication() }.getOrNull()
                if (app != null && !app.isUnitTestMode) stateWrite.getOrThrow()
                synchronized(fallbackStorage) {
                    fallbackStorage[STORAGE_KEY] = ""
                    fallbackStorage[SCHEMES_STORAGE_KEY] = ""
                    fallbackStorage[ACTIVE_SCHEME_STORAGE_KEY] = DEFAULT_SCHEME
                    fallbackStorage[ACTIVE_BY_KEY_STORAGE_KEY] = ""
                }
            }
        }

        /** 缓存上限（LRU 淘汰）。 */
        const val MAX_HISTORY_SIZE = 50

        /** F49：命名方案个数上限（防方案集无界膨胀；达上限后新建另存被拒，存量操作不受影响）。 */
        const val MAX_SCHEME_COUNT = 20

        /**
         * F49 计数缝（测试用）：非空时 [persistToStorage]（全量序列化）每次回调一次。
         * 「switch 只写指针」守卫=switchScheme 前后计数不变；摘掉修复（switch 回走全量）→ 计数+1 → 红。
         */
        @Volatile
        internal var persistProbe: (() -> Unit)? = null

        /** R22 T-04：默认方案内部名（空串——用户方案名恒非空不冲突；UI 显示名经 bundle「默认方案」本地化）。 */
        const val DEFAULT_SCHEME = ""

        /** R22 T-04：命名方案持久化 key（IDE：PluginSettings.State.paramSchemesJson；测试：静态存储 key）。 */
        private const val SCHEMES_STORAGE_KEY = "paramSchemes"

        /** R22 T-04：激活方案持久化 key（IDE：PluginSettings.State.paramActiveScheme；测试：静态存储 key）。 */
        private const val ACTIVE_SCHEME_STORAGE_KEY = "paramActiveScheme"

        /** T-DevAcceptFix2 ①：按键激活指针的测试环境静态存储键（IDE 环境走 PluginSettings 同名字段）。 */
        private const val ACTIVE_BY_KEY_STORAGE_KEY = "paramActiveSchemeByKey"

        /** 持久化 key（IDE 环境：PluginSettings.State.paramHistoryJson 字段；测试环境：静态存储 key）。 */
        private const val STORAGE_KEY = "paramHistory"

        /**
         * 测试环境回落静态存储（无 IDE 服务时保证持久化往返可测）。
         * IDE 环境不使用本存储（走 PluginSettings）。
         */
        private val fallbackStorage = mutableMapOf<String, String>()

        /**
         * SQL 模板指纹：SHA-256 前 16 hex 字符。
         * 相同 SQL → 相同指纹；不同 SQL → 不同指纹（碰撞概率可忽略）。
         */
        fun fingerprint(sql: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(sql.toByteArray())
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
