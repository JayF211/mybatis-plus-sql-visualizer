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

    private val gson = Gson()

    /** 编码 rawSql + params 为 Base64 载荷。 */
    fun encode(rawSql: String, params: List<LogSqlParam>): String {
        val obj = JsonObject().apply {
            addProperty(RAW_FIELD, rawSql)
            add(
                PARAMS_FIELD,
                JsonArray().apply {
                    params.forEach { p ->
                        add(
                            JsonArray().apply {
                                add(if (p.value == null) JsonNull.INSTANCE else JsonPrimitive(p.value))
                                add(if (p.type == null) JsonNull.INSTANCE else JsonPrimitive(p.type))
                            },
                        )
                    }
                },
            )
        }
        return Base64.getEncoder().encodeToString(gson.toJson(obj).toByteArray(Charsets.UTF_8))
    }

    /**
     * 解码 Base64 载荷为 (rawSql, params)；任何形态异常（非法 Base64 / JSON 缺字段 /
     * 结构不符 / rawSql 空白）返回 null，调用方把 pd 行降级为普通正文注释行。
     */
    fun decode(payload: String): Pair<String, List<LogSqlParam>>? = runCatching {
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
        rawSql to params
    }.getOrNull()
}
