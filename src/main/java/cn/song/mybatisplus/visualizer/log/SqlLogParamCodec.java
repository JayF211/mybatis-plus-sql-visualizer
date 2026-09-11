package cn.song.mybatisplus.visualizer.log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import kotlin.Pair;

/**
 * 历史文件参数载荷编解码（T-HistParam F1）：格式 v2 在头部注释行之下新增独立注释行
 * `-- pd: <base64> --`，payload 为 UTF-8 JSON `{"r": rawSql, "p": [[value, type], ...]}`
 * （value/type 可为 null）的 Base64 编码，正文保持可执行 restoredSql（F-43 初心：文件
 * 可直接在数据库工具打开执行）。
 *
 * 选 Base64 加独立注释行的理由：
 * - 参数值可能含 `|`、逗号、`--`、括号、换行等任意文本，明文分隔符方案必然引入转义歧义；
 *   Base64 字符集（A-Za-z0-9+/=）与头部解析正则、字段分隔符零冲突，单行形态不破坏
 * [SqlLogFileReader.countInFile] 的头部行计数判据；
 * - 旧版本插件的 Reader 正则锚定行尾，读到 pd 行会把它并进 SQL 正文当注释行——展示冗余
 *   但可执行性不破坏（回滚场景安全）；若把参数塞进头部行本体，旧正则整条解析失败丢条目。
 */
public final class SqlLogParamCodec {

    public static final SqlLogParamCodec INSTANCE = new SqlLogParamCodec();
    private SqlLogParamCodec() {}

    private static final String RAW_FIELD = "r";
    private static final String PARAMS_FIELD = "p";

    private static final Gson GSON = new Gson();

    /** 编码 rawSql + params 为 Base64 载荷。 */
    public static String encode(String rawSql, List<LogSqlParam> params) {
        JsonObject obj = new JsonObject();
        obj.addProperty(RAW_FIELD, rawSql);
        JsonArray arr = new JsonArray();
        for (LogSqlParam p : params) {
            JsonArray pair = new JsonArray();
            pair.add(p.value() == null ? JsonNull.INSTANCE : new JsonPrimitive(p.value()));
            pair.add(p.type() == null ? JsonNull.INSTANCE : new JsonPrimitive(p.type()));
            arr.add(pair);
        }
        obj.add(PARAMS_FIELD, arr);
        return Base64.getEncoder().encodeToString(GSON.toJson(obj).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码 Base64 载荷为 (rawSql, params)；任何形态异常（非法 Base64 / JSON 缺字段 /
     * 结构不符 / rawSql 空白）返回 null，调用方把 pd 行降级为普通正文注释行。
     */
    public static Pair<String, List<LogSqlParam>> decode(String payload) {
        try {
            String decoded = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(decoded);
            if (!parsed.isJsonObject()) return null;
            JsonObject json = parsed.getAsJsonObject();
            JsonElement rawEl = json.get(RAW_FIELD);
            String rawSql = rawEl != null && rawEl.isJsonPrimitive() ? rawEl.getAsString() : null;
            if (rawSql == null || rawSql.isBlank()) return null;
            List<LogSqlParam> params = new ArrayList<>();
            JsonElement paramsEl = json.get(PARAMS_FIELD);
            if (paramsEl != null && paramsEl.isJsonArray()) {
                for (JsonElement el : paramsEl.getAsJsonArray()) {
                    if (!el.isJsonArray()) return null;
                    JsonArray arr = el.getAsJsonArray();
                    if (arr.size() < 1) return null;
                    JsonElement valueEl = arr.get(0);
                    String value = valueEl != null && valueEl.isJsonPrimitive() ? valueEl.getAsString() : null;
                    String type = null;
                    if (arr.size() > 1) {
                        JsonElement typeEl = arr.get(1);
                        type = typeEl != null && typeEl.isJsonPrimitive() ? typeEl.getAsString() : null;
                    }
                    params.add(new LogSqlParam(value, type));
                }
            }
            return new Pair<>(rawSql, params);
        } catch (Exception e) {
            return null;
        }
    }
}
