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
 * <p>M56 T-M56-00：payload 扩展可选键 {@code m}（mapperStatement）/ {@code e}（errorText）/
 * {@code q}（requestTag）/ {@code u}（updateCount）——写入端仅在非默认时落键，
 * 旧 payload（无扩展键）解码出 null，旧历史文件可读。
 *
 * <p>选 Base64 加独立注释行的理由：
 * - 参数值可能含 {@code |}、逗号、{@code --}、括号、换行等任意文本，明文分隔符方案必然引入转义歧义；
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
    private static final String MAPPER_FIELD = "m";
    private static final String ERROR_FIELD = "e";
    private static final String TAG_FIELD = "q";
    private static final String UPDATE_COUNT_FIELD = "u";
    // T-SourceJumpFunnel：候选 [file, line] 对数组 + 候选类名 + 栈快照（历史读回后跳转回退仍可用）
    private static final String CANDS_FIELD = "cd";
    private static final String CAND_CLASSES_FIELD = "cc";
    private static final String STACK_DIGEST_FIELD = "sd";

    private static final Gson GSON = new Gson();

    /**
     * pd 载荷解码结果。mapperStatement / errorText / requestTag / updateCount 为 M56 扩展字段，
     * 旧 payload 为 null。
     */
    public record DecodedPayload(
            /** rawSql 原文。 */
            String rawSql,
            /** 参数列表（[value, type] 对还原）。 */
            List<LogSqlParam> params,
            /** mapper 语句 id（旧 payload 为 null）。 */
            String mapperStatement,
            /** 执行报错（旧 payload 为 null）。 */
            String errorText,
            /** 请求标签（旧 payload 为 null）。 */
            String requestTag,
            /** 影响行数（旧 payload 为 null）。 */
            Long updateCount,
            /** T-SourceJumpFunnel：候选 [file, line]（栈序）；旧 payload 为空。 */
            List<Pair<String, Integer>> sourceCandidates,
            /** T-SourceJumpFunnel：候选类名（栈序对齐）；旧 payload 为空。 */
            List<String> candidateClasses,
            /** T-SourceJumpFunnel：调用栈快照；旧 payload 为 null。 */
            String stackDigest) {

        /** 无扩展字段形态（Kotlin data class 默认参数等价）。 */
        public DecodedPayload(String rawSql, List<LogSqlParam> params) {
            this(rawSql, params, null, null, null, null, List.of(), List.of(), null);
        }
    }

    /** 编码 rawSql + params 为 Base64 载荷（无 M56 扩展字段形态，等价 encodeEntry 全默认条目）。 */
    public static String encode(String rawSql, List<LogSqlParam> params) {
        return encodeEntry(new LogSqlEntry(rawSql, rawSql, params));
    }

    /**
     * 编码条目为 Base64 载荷：rawSql + params 恒写；M56 扩展字段（mapperStatement/errorText/
     * requestTag/updateCount）仅非默认时落键，旧版本插件解码端忽略未知键（gson 宽松解析）。
     */
    public static String encodeEntry(LogSqlEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty(RAW_FIELD, entry.rawSql());
        JsonArray arr = new JsonArray();
        for (LogSqlParam p : entry.params()) {
            JsonArray pair = new JsonArray();
            pair.add(p.value() == null ? JsonNull.INSTANCE : new JsonPrimitive(p.value()));
            pair.add(p.type() == null ? JsonNull.INSTANCE : new JsonPrimitive(p.type()));
            arr.add(pair);
        }
        obj.add(PARAMS_FIELD, arr);
        if (entry.mapperStatement() != null) {
            obj.addProperty(MAPPER_FIELD, entry.mapperStatement());
        }
        if (entry.errorText() != null) {
            obj.addProperty(ERROR_FIELD, entry.errorText());
        }
        if (entry.requestTag() != null) {
            obj.addProperty(TAG_FIELD, entry.requestTag());
        }
        if (entry.updateCount() != null) {
            obj.addProperty(UPDATE_COUNT_FIELD, entry.updateCount());
        }
        // T-SourceJumpFunnel：候选/栈快照（非默认才落键，旧版本解码端忽略未知键）
        if (!entry.sourceCandidates().isEmpty()) {
            JsonArray cands = new JsonArray();
            for (Pair<String, Integer> candidate : entry.sourceCandidates()) {
                JsonArray pair = new JsonArray();
                pair.add(candidate.getFirst());
                pair.add(candidate.getSecond());
                cands.add(pair);
            }
            obj.add(CANDS_FIELD, cands);
        }
        if (!entry.candidateClasses().isEmpty()) {
            JsonArray classes = new JsonArray();
            for (String cls : entry.candidateClasses()) {
                classes.add(cls);
            }
            obj.add(CAND_CLASSES_FIELD, classes);
        }
        if (entry.stackDigest() != null) {
            obj.addProperty(STACK_DIGEST_FIELD, entry.stackDigest());
        }
        return Base64.getEncoder().encodeToString(GSON.toJson(obj).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码 Base64 载荷为 DecodedPayload；任何形态异常（非法 Base64 / JSON 缺字段 /
     * 结构不符 / rawSql 空白）返回 null，调用方把 pd 行降级为普通正文注释行。
     * M56 扩展键缺失/形态异常按缺省 null 容错（不因扩展键损坏丢弃整条）。
     */
    public static DecodedPayload decode(String payload) {
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
            // T-SourceJumpFunnel：候选 [file, line] 对数组（栈序）——形态异常的元素逐个跳过（forEach return 等价）
            List<Pair<String, Integer>> sourceCandidates = new ArrayList<>();
            JsonElement candsEl = json.get(CANDS_FIELD);
            JsonArray candsArr = candsEl != null && candsEl.isJsonArray() ? candsEl.getAsJsonArray() : null;
            if (candsArr != null) {
                for (JsonElement el : candsArr) {
                    if (!el.isJsonArray()) continue;
                    JsonArray pair = el.getAsJsonArray();
                    JsonElement fileEl = getOrNull(pair, 0);
                    String file = fileEl != null && fileEl.isJsonPrimitive() ? fileEl.getAsString() : null;
                    if (file == null) continue;
                    JsonElement lineEl = getOrNull(pair, 1);
                    int line = lineEl != null && lineEl.isJsonPrimitive() ? lineEl.getAsInt() : 0;
                    sourceCandidates.add(new Pair<>(file, Math.max(line, 0)));
                }
            }
            // T-SourceJumpFunnel：候选类名（栈序对齐）——非原始值逐个跳过
            List<String> candidateClasses = new ArrayList<>();
            JsonElement classesEl = json.get(CAND_CLASSES_FIELD);
            JsonArray classesArr = classesEl != null && classesEl.isJsonArray() ? classesEl.getAsJsonArray() : null;
            if (classesArr != null) {
                for (JsonElement el : classesArr) {
                    if (el != null && el.isJsonPrimitive()) {
                        candidateClasses.add(el.getAsString());
                    }
                }
            }
            return new DecodedPayload(
                    rawSql,
                    params,
                    stringField(json, MAPPER_FIELD),
                    stringField(json, ERROR_FIELD),
                    stringField(json, TAG_FIELD),
                    longField(json, UPDATE_COUNT_FIELD),
                    sourceCandidates,
                    candidateClasses,
                    stringField(json, STACK_DIGEST_FIELD));
        } catch (Exception e) {
            return null;
        }
    }

    /** Kotlin JsonArray.getOrNull 扩展等价：下标越界返回 null。 */
    private static JsonElement getOrNull(JsonArray arr, int i) {
        return arr.size() > i ? arr.get(i) : null;
    }

    /** M56 扩展字符串键读取：缺失/非原始值/空白按 null（master takeIf 链等价）。 */
    private static String stringField(JsonObject json, String field) {
        JsonElement el = json.get(field);
        if (el == null || !el.isJsonPrimitive()) return null;
        String s = el.getAsString();
        return s.isBlank() ? null : s;
    }

    /** M56 扩展数值键读取：缺失/非原始值/非数值按 null。 */
    private static Long longField(JsonObject json, String field) {
        JsonElement el = json.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return null;
        return el.getAsLong();
    }
}
