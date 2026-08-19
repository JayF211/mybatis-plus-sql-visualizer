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
 */
class ParamHistoryCache(private val maxSize: Int = MAX_HISTORY_SIZE) {

    /** LRU 内存缓存（accessOrder=true：访问顺序，最近访问在尾部）。 */
    private val cache = LinkedHashMap<String, Map<String, String>>(maxSize + 1, 0.75f, true)

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
    fun computeKey(tableName: String?, rawSql: String): String? {
        if (tableName.isNullOrEmpty()) return null
        return "$tableName:${fingerprint(rawSql)}"
    }

    /**
     * 保存参数值到缓存（LRU 淘汰 + 持久化）。
     *
     * @param key    缓存键（由 [computeKey] 生成）
     * @param params 参数映射（source → value）
     */
    fun save(key: String, params: Map<String, String>) {
        if (params.isEmpty()) return
        cache[key] = params
        enforceMaxSize()
        persistToStorage()
    }

    /**
     * 召回历史参数值。
     *
     * @param key 缓存键
     * @return 历史参数映射，未命中返回 null
     */
    fun recall(key: String): Map<String, String>? {
        return cache[key]?.also {
            // 访问后LinkedHashMap自动更新顺序（accessOrder=true），无需手动处理
            enforceMaxSize()
        }
    }

    /** 清空缓存（内存 + 持久化）。 */
    fun clear() {
        cache.clear()
        persistToStorage()
    }

    /** 当前缓存条目数（测试断言用）。 */
    internal fun size(): Int = cache.size

    /** 是否包含指定键（测试断言用，触发 LRU 顺序更新）。 */
    internal fun containsKey(key: String): Boolean = cache.containsKey(key)

    /** LRU 淘汰：超过 maxSize 时移除最久未访问的条目（头部）。 */
    private fun enforceMaxSize() {
        while (cache.size > maxSize) {
            val oldest = cache.keys.first()
            cache.remove(oldest)
        }
    }

    /** 持久化到 PluginSettings.State.paramHistoryJson（IDE 环境）；测试环境回落静态存储。 */
    private fun persistToStorage() {
        val json = serializeToJson()
        val written = runCatching { PluginSettings.getInstance().state.paramHistoryJson = json }.isSuccess
        if (!written) {
            // 测试环境（无 IDE 服务）：回落静态存储，保证持久化往返可测
            synchronized(fallbackStorage) { fallbackStorage[STORAGE_KEY] = json }
        }
    }

    /** 从 PluginSettings.State.paramHistoryJson 恢复内存缓存（IDE 环境）；测试环境回落静态存储。 */
    private fun restoreFromPersistence() {
        var json = runCatching { PluginSettings.getInstance().state.paramHistoryJson }.getOrDefault("")
        if (json.isBlank()) {
            // 测试环境：读静态存储
            synchronized(fallbackStorage) { json = fallbackStorage[STORAGE_KEY] ?: "" }
        }
        if (json.isBlank()) return
        runCatching {
            deserializeFromJson(json).forEach { (key, params) ->
                cache[key] = params
            }
        }
        enforceMaxSize()
    }

    /**
     * 自研最小 JSON 序列化（格式：`[{"key":{...params}}]`）。
     * 仅处理 String→String 映射，key/value 含特殊字符时转义。
     */
    private fun serializeToJson(): String {
        if (cache.isEmpty()) return ""
        val sb = StringBuilder("[")
        cache.entries.forEachIndexed { index, (key, params) ->
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
