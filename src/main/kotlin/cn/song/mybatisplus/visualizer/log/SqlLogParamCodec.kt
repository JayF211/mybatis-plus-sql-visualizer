package cn.song.mybatisplus.visualizer.log

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.Base64

/**
 * 历史文件参数载荷编解码（T-HistParam F1）：格式 v2 在头部注释行之下新增独立注释行
 * `-- pd: <base64> --`，payload 为 UTF-8 JSON `{"r": rawSql, "p": [[value, type], ...]}`
 * （value/type 可为 null）的 Base64 编码，正文保持可执行 restoredSql（F-43 初心：文件
 * 可直接在数据库工具打开执行）。
 *
 * M56 T-M56-00：payload 扩展可选键 `m`（mapperStatement）/ `e`（errorText）/ `q`（requestTag）/
 * `u`（updateCount）——写入端仅在非默认时落键，旧 payload（无扩展键）解码出 null，旧历史文件可读。
 *
 * 选 Base64 加独立注释行的理由：
 * - 参数值可能含 `|`、逗号、`--`、括号、换行等任意文本，明文分隔符方案必然引入转义歧义；
 *   Base64 字符集（A-Za-z0-9+/=）与头部解析正则、字段分隔符零冲突，单行形态不破坏
 *   [SqlLogFileReader.countInFile] 的头部行计数判据；
 * - 旧版本插件的 Reader 正则锚定行尾，读到 pd 行会把它并进 SQL 正文当注释行——展示冗余
 *   但可执行性不破坏（回滚场景安全）；若把参数塞进头部行本体，旧正则整条解析失败丢条目。
 */
object SqlLogParamCodec {

    private const val RAW_FIELD = "r"
    private const val PARAMS_FIELD = "p"
    private const val MAPPER_FIELD = "m"
    private const val ERROR_FIELD = "e"
    private const val TAG_FIELD = "q"
    private const val UPDATE_COUNT_FIELD = "u"
    // T-SourceJumpFunnel：候选 [file, line] 对数组 + 候选类名 + 栈快照（历史读回后跳转回退仍可用）
    private const val CANDS_FIELD = "cd"
    private const val CAND_CLASSES_FIELD = "cc"
    private const val STACK_DIGEST_FIELD = "sd"

    private val gson = Gson()

    /** pd 载荷解码结果。[mapperStatement] / [errorText] / [requestTag] / [updateCount] 为 M56 扩展字段，旧 payload 为 null。 */
    data class DecodedPayload(
        val rawSql: String,
        val params: List<LogSqlParam>,
        val mapperStatement: String? = null,
        val errorText: String? = null,
        val requestTag: String? = null,
        val updateCount: Long? = null,
        /** T-SourceJumpFunnel：候选 [file, line]（栈序）；旧 payload 为空。 */
        val sourceCandidates: List<Pair<String, Int>> = emptyList(),
        /** T-SourceJumpFunnel：候选类名（栈序对齐）；旧 payload 为空。 */
        val candidateClasses: List<String> = emptyList(),
        /** T-SourceJumpFunnel：调用栈快照；旧 payload 为 null。 */
        val stackDigest: String? = null,
    )

    /** 编码 rawSql + params 为 Base64 载荷（无 M56 扩展字段形态，等价 [encodeEntry] 全默认条目）。 */
    fun encode(rawSql: String, params: List<LogSqlParam>): String =
        encodeEntry(
            LogSqlEntry(
                rawSql = rawSql,
                restoredSql = rawSql,
                params = params,
            ),
        )

    /**
     * 编码条目为 Base64 载荷：rawSql + params 恒写；M56 扩展字段（mapperStatement/errorText/
     * requestTag/updateCount）仅非默认时落键，旧版本插件解码端忽略未知键（gson 宽松解析）。
     */
    fun encodeEntry(entry: LogSqlEntry): String {
        val obj = JsonObject().apply {
            addProperty(RAW_FIELD, entry.rawSql)
            add(
                PARAMS_FIELD,
                JsonArray().apply {
                    entry.params.forEach { p ->
                        add(
                            JsonArray().apply {
                                add(if (p.value == null) JsonNull.INSTANCE else JsonPrimitive(p.value))
                                add(if (p.type == null) JsonNull.INSTANCE else JsonPrimitive(p.type))
                            },
                        )
                    }
                },
            )
            entry.mapperStatement?.let { addProperty(MAPPER_FIELD, it) }
            entry.errorText?.let { addProperty(ERROR_FIELD, it) }
            entry.requestTag?.let { addProperty(TAG_FIELD, it) }
            entry.updateCount?.let { addProperty(UPDATE_COUNT_FIELD, it) }
            // T-SourceJumpFunnel：候选/栈快照（非默认才落键，旧版本解码端忽略未知键）
            if (entry.sourceCandidates.isNotEmpty()) {
                add(
                    CANDS_FIELD,
                    JsonArray().apply {
                        entry.sourceCandidates.forEach { (file, line) ->
                            add(JsonArray().apply { add(file); add(line) })
                        }
                    },
                )
            }
            if (entry.candidateClasses.isNotEmpty()) {
                add(
                    CAND_CLASSES_FIELD,
                    JsonArray().apply { entry.candidateClasses.forEach { add(it) } },
                )
            }
            entry.stackDigest?.let { addProperty(STACK_DIGEST_FIELD, it) }
        }
        return Base64.getEncoder().encodeToString(gson.toJson(obj).toByteArray(Charsets.UTF_8))
    }

    /**
     * 解码 Base64 载荷为 [DecodedPayload]；任何形态异常（非法 Base64 / JSON 缺字段 /
     * 结构不符 / rawSql 空白）返回 null，调用方把 pd 行降级为普通正文注释行。
     * M56 扩展键缺失/形态异常按缺省 null 容错（不因扩展键损坏丢弃整条）。
     */
    fun decode(payload: String): DecodedPayload? = runCatching {
        val json = JsonParser.parseString(String(Base64.getDecoder().decode(payload), Charsets.UTF_8))
            .takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val rawSql = json.get(RAW_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: return null
        val params = mutableListOf<LogSqlParam>()
        json.get(PARAMS_FIELD)?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            val arr = el.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            if (arr.size() < 1) return null
            val value = arr.get(0)?.takeIf { it.isJsonPrimitive }?.asString
            val type = if (arr.size() > 1) arr.get(1)?.takeIf { it.isJsonPrimitive }?.asString else null
            params.add(LogSqlParam(value, type))
        }
        DecodedPayload(
            rawSql = rawSql,
            params = params,
            mapperStatement = json.get(MAPPER_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            errorText = json.get(ERROR_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            requestTag = json.get(TAG_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            updateCount = json.get(UPDATE_COUNT_FIELD)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong,
            sourceCandidates = run {
                val arr = json.get(CANDS_FIELD)?.takeIf { it.isJsonArray }?.asJsonArray
                val out = mutableListOf<Pair<String, Int>>()
                arr?.forEach { el ->
                    val pair = el.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
                    val file = pair.getOrNull(0)?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                    val line = pair.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    out.add(file to maxOf(line, 0))
                }
                out
            },            candidateClasses = run {
                val arr = json.get(CAND_CLASSES_FIELD)?.takeIf { it.isJsonArray }?.asJsonArray
                val out = mutableListOf<String>()
                arr?.forEach { el ->
                    el.takeIf { it.isJsonPrimitive }?.asString?.let { out.add(it) }
                }
                out
            },
            stackDigest = json.get(STACK_DIGEST_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun com.google.gson.JsonArray.getOrNull(i: Int) = if (size() > i) get(i) else null
}
