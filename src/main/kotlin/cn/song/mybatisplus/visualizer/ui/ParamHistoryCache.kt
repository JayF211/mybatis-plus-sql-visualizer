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

    /** R22 T-04：命名方案存储——方案名 → (缓存键 → 参数快照)（accessOrder=true，逐方案 LRU）。 */
    private val schemes = LinkedHashMap<String, LinkedHashMap<String, Map<String, String>>>()

    /** R22 T-04：当前激活方案（面板下拉切换；随持久化落盘；空串 = 默认方案）。 */
    private var activeScheme: String = DEFAULT_SCHEME

    init {
        // 从持久化恢复（IDE 重启后加载历史）
        restoreFromPersistence()
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
        saveForScheme(activeScheme, key, params)
    }

    /**
     * R22 T-04：把快照写入**指定方案**——调用方取快照时的方案名随行落库，
     * 防止「EDT 取快照 → 线程池写回」窗口内用户切换方案导致的串桶。
     *
     * @param scheme 目标方案名（空串 = 默认方案）
     * @param key    缓存键
     * @param params 参数映射（source → value）
     */
    fun saveForScheme(scheme: String, key: String, params: Map<String, String>) {
        if (params.isEmpty()) return
        entriesForScheme(scheme)[key] = params
        enforceMaxSize(entriesForScheme(scheme))
        persistToStorage()
    }

    /**
     * 召回历史参数值（R22 T-04 起按激活方案路由）。
     *
     * @param key 缓存键
     * @return 历史参数映射，未命中返回 null
     */
    fun recall(key: String): Map<String, String>? {
        val entries = if (activeScheme == DEFAULT_SCHEME) cache else schemes[activeScheme] ?: return null
        return entries[key]?.also {
            // 访问后LinkedHashMap自动更新顺序（accessOrder=true），无需手动处理
            enforceMaxSize(entries)
        }
    }

    /** 清空缓存（内存 + 持久化）。R22 T-04：含全部命名方案与激活方案复位（测试隔离边界）。 */
    fun clear() {
        cache.clear()
        schemes.clear()
        activeScheme = DEFAULT_SCHEME
        persistToStorage()
    }

    // ---------------- R22 T-04：命名方案 API（D2 定案——旧数据自动成为默认方案） ----------------

    /** 命名方案清单（默认方案除外，创建序）。 */
    fun schemeNames(): List<String> = schemes.keys.toList()

    /** 当前激活方案名（[DEFAULT_SCHEME] = 默认方案）。 */
    fun activeSchemeName(): String = activeScheme

    /** 是否存在指定命名方案。 */
    fun hasScheme(name: String): Boolean = name in schemes

    /**
     * 切换激活方案（面板下拉驱动）。未知方案名忽略（防脏态）；同名幂等不重复落盘。
     *
     * F49（T-PerfQuick5）：切换**只写激活指针**（[persistActiveSchemePointer]），不再走
     * [persistToStorage] 全量序列化——方案内容未变，重序列化全部命名方案 + 无条件重写
     * legacy 字段属纯写放大，且发生在 EDT 同步路径（性能十七轮 §5.3）。
     */
    fun switchScheme(name: String) {
        if (name != DEFAULT_SCHEME && name !in schemes) return
        if (activeScheme == name) return
        activeScheme = name
        persistActiveSchemePointer()
    }

    /**
     * 另存为命名方案：把 [params] 作为 [key] 的快照写入 [name]（已存在同键则覆盖），
     * 并切换激活到该方案。默认方案名/空名/空快照返回 [SchemeOpResult.INVALID]（调用方
     * 此前静默守卫的显式化——对话框在更早的输入环节已拦，行为不变）；新建方案个数达
     * [MAX_SCHEME_COUNT] 上限返回 [SchemeOpResult.LIMIT_REACHED]（F49）。
     */
    fun saveAsScheme(name: String, key: String, params: Map<String, String>): SchemeOpResult {
        if (name.isBlank() || name == DEFAULT_SCHEME || params.isEmpty()) return SchemeOpResult.INVALID
        if (name !in schemes && schemes.size >= MAX_SCHEME_COUNT) return SchemeOpResult.LIMIT_REACHED
        schemes.getOrPut(name) { newSchemeEntries() }[key] = params
        enforceMaxSize(schemes.getValue(name))
        activeScheme = name
        persistToStorage()
        return SchemeOpResult.OK
    }

    /** 召回指定方案的 [key] 快照（方案不存在/键未命中返回 null）。 */
    fun recallFromScheme(name: String, key: String): Map<String, String>? = schemes[name]?.get(key)

    /** 指定方案当前条目数（缓存键数，测试/对话框摘要用）。 */
    fun schemeEntryCount(name: String): Int = schemes[name]?.size ?: 0

    /** R22 T-04：方案操作结果（对话框状态行分流）。F49 增 LIMIT_REACHED（个数上限）与 INVALID（入参不合规）。 */
    enum class SchemeOpResult { OK, DUPLICATE, NOT_FOUND, DEFAULT_PROTECTED, LIMIT_REACHED, INVALID }

    /** 重命名方案（LinkedHashMap 重建保创建序；激活指针随迁）。 */
    fun renameScheme(oldName: String, newName: String): SchemeOpResult {
        if (oldName == DEFAULT_SCHEME) return SchemeOpResult.DEFAULT_PROTECTED
        if (oldName !in schemes) return SchemeOpResult.NOT_FOUND
        if (newName.isBlank() || newName == DEFAULT_SCHEME || newName in schemes) return SchemeOpResult.DUPLICATE
        val rebuilt = LinkedHashMap<String, LinkedHashMap<String, Map<String, String>>>()
        schemes.forEach { (name, entries) -> rebuilt[if (name == oldName) newName else name] = entries }
        schemes.clear()
        schemes.putAll(rebuilt)
        if (activeScheme == oldName) activeScheme = newName
        persistToStorage()
        return SchemeOpResult.OK
    }

    /** 删除方案（激活方案被删时归位默认方案）。 */
    fun deleteScheme(name: String): SchemeOpResult {
        if (name == DEFAULT_SCHEME) return SchemeOpResult.DEFAULT_PROTECTED
        if (schemes.remove(name) == null) return SchemeOpResult.NOT_FOUND
        if (activeScheme == name) activeScheme = DEFAULT_SCHEME
        persistToStorage()
        return SchemeOpResult.OK
    }

    /** 当前缓存条目数（默认方案；测试断言用）。 */
    internal fun size(): Int = cache.size

    /** 是否包含指定键（激活方案；测试断言用，触发 LRU 顺序更新）。 */
    internal fun containsKey(key: String): Boolean = entriesForScheme(activeScheme).containsKey(key)

    /** R22 T-04：指定方案的条目视图（默认方案 = [cache]；命名方案惰性建，accessOrder=true 同 LRU 语义）。 */
    private fun entriesForScheme(scheme: String): LinkedHashMap<String, Map<String, String>> =
        if (scheme == DEFAULT_SCHEME) cache else schemes.getOrPut(scheme) { newSchemeEntries() }

    /** R22 T-04：新建方案条目映射（与默认方案同 LRU 形态）。 */
    private fun newSchemeEntries(): LinkedHashMap<String, Map<String, String>> =
        LinkedHashMap(maxSize + 1, 0.75f, true)

    /** LRU 淘汰：[entries] 超过 maxSize 时移除最久未访问的条目（头部）。 */
    private fun enforceMaxSize(entries: LinkedHashMap<String, Map<String, String>>) {
        while (entries.size > maxSize) {
            val oldest = entries.keys.first()
            entries.remove(oldest)
        }
    }

    /**
     * 全量持久化到 PluginSettings（IDE 环境）：命名方案 + 激活方案入新字段，默认方案继续写旧字段（降级兼容）；测试环境回落静态存储。
     * F49 计数缝：[persistProbe] 非空时回调一次（基准/守卫测试数「全量序列化次数」）。
     */
    private fun persistToStorage() {
        persistProbe?.invoke()
        val schemesJson = serializeSchemesToJson()
        val active = activeScheme
        val written = runCatching {
            val state = PluginSettings.getInstance().state
            state.paramSchemesJson = schemesJson
            state.paramActiveScheme = active
        }.isSuccess
        if (!written) {
            // 测试环境（无 IDE 服务）：回落静态存储，保证持久化往返可测
            synchronized(fallbackStorage) {
                fallbackStorage[SCHEMES_STORAGE_KEY] = schemesJson
                fallbackStorage[ACTIVE_SCHEME_STORAGE_KEY] = active
            }
        }
        // 旧字段保持既有语义：默认方案快照继续写 paramHistoryJson（旧版构建可读，存量零迁移的另一面）
        val legacyJson = serializeToJson(cache)
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
        val active = activeScheme
        val written = runCatching {
            PluginSettings.getInstance().state.paramActiveScheme = active
        }.isSuccess
        if (!written) {
            synchronized(fallbackStorage) { fallbackStorage[ACTIVE_SCHEME_STORAGE_KEY] = active }
        }
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
                }
            }
        }
        var active = readPersisted(ACTIVE_SCHEME_STORAGE_KEY) { PluginSettings.getInstance().state.paramActiveScheme }
        if (active != DEFAULT_SCHEME && active !in schemes) active = DEFAULT_SCHEME
        activeScheme = active
        // 全方案统一执行上限（含 D2 迁入的默认方案）
        enforceMaxSize(cache)
        schemes.values.forEach { enforceMaxSize(it) }
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
     */
    private fun serializeSchemesToJson(): String {
        if (schemes.isEmpty()) return ""
        val sb = StringBuilder("{")
        var first = true
        for ((name, entries) in schemes) {
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
