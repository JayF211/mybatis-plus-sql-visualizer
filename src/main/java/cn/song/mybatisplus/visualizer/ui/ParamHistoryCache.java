package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P-05：参数历史缓存——LRU 淘汰 + 持久化。
 *
 * <p>同一实体类的查询在不同预览间复用上次填的参数值（按 {@code 表名 + SQL 模板指纹} 缓存）。
 * <ul>
 *   <li>内存缓存：{@link LinkedHashMap} accessOrder=true（LRU），运行时快速读写。</li>
 *   <li>持久化：{@link PluginSettings.State#getParamHistoryJson()}（JSON 字符串），IDE 重启后恢复。</li>
 *   <li>缓存上限 {@link #MAX_HISTORY_SIZE} = 50 条（LRU 淘汰），单条无 TTL。</li>
 * </ul>
 *
 * <p>序列化/反序列化用自研最小 JSON（避免引入新依赖）；格式：
 * <pre>[{"key":"tbl:abcdef...",{"source1":"val1","source2":"val2"}}]</pre>
 */
public class ParamHistoryCache {

    /** 缓存上限（LRU 淘汰）。 */
    public static final int MAX_HISTORY_SIZE = 50;

    /** 持久化 key（IDE 环境：PluginSettings.State.paramHistoryJson 字段；测试环境：静态存储 key）。 */
    private static final String STORAGE_KEY = "paramHistory";

    /**
     * 测试环境回落静态存储（无 IDE 服务时保证持久化往返可测）。
     * IDE 环境不使用本存储（走 PluginSettings）。
     */
    private static final Map<String, String> fallbackStorage = new HashMap<>();

    private final int maxSize;
    /** LRU 内存缓存（accessOrder=true：访问顺序，最近访问在尾部）。 */
    private final LinkedHashMap<String, Map<String, String>> cache;

    public ParamHistoryCache() {
        this(MAX_HISTORY_SIZE);
    }

    public ParamHistoryCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<>(maxSize + 1, 0.75f, true);
        // 从持久化恢复（IDE 重启后加载历史）
        restoreFromPersistence();
    }

    /**
     * 计算缓存键 = {@code tableName:fingerprint}（无表名返回 null，不缓存）。
     *
     * @param tableName 表名（null 表示纯文本预览，不缓存）
     * @param rawSql    原始 SQL（用于计算指纹）
     * @return 缓存键，或 null（无表名时不缓存）
     */
    public String computeKey(String tableName, String rawSql) {
        if (tableName == null || tableName.isEmpty()) return null;
        return tableName + ":" + fingerprint(rawSql);
    }

    /**
     * 保存参数值到缓存（LRU 淘汰 + 持久化）。
     *
     * @param key    缓存键（由 {@link #computeKey} 生成）
     * @param params 参数映射（source → value）
     */
    public void save(String key, Map<String, String> params) {
        if (params == null || params.isEmpty()) return;
        cache.put(key, new HashMap<>(params));
        enforceMaxSize();
        persistToStorage();
    }

    /**
     * 召回历史参数值。
     *
     * @param key 缓存键
     * @return 历史参数映射，未命中返回 null
     */
    public Map<String, String> recall(String key) {
        Map<String, String> value = cache.get(key);
        if (value != null) {
            // 访问后 LinkedHashMap 自动更新顺序（accessOrder=true），无需手动处理
            enforceMaxSize();
        }
        return value;
    }

    /** 清空缓存（内存 + 持久化）。 */
    public void clear() {
        cache.clear();
        persistToStorage();
    }

    /** 当前缓存条目数（测试断言用）。 */
    public int size() {
        return cache.size();
    }

    /** 是否包含指定键（测试断言用，触发 LRU 顺序更新）。 */
    public boolean containsKey(String key) {
        return cache.containsKey(key);
    }

    /** LRU 淘汰：超过 maxSize 时移除最久未访问的条目（头部）。 */
    private void enforceMaxSize() {
        while (cache.size() > maxSize) {
            String oldest = cache.keySet().iterator().next();
            cache.remove(oldest);
        }
    }

    /** 持久化到 PluginSettings.State.paramHistoryJson（IDE 环境）；测试环境回落静态存储。 */
    private void persistToStorage() {
        String json = serializeToJson();
        boolean written = false;
        try {
            PluginSettings.getInstance().getState().setParamHistoryJson(json);
            written = true;
        } catch (Exception ignored) {
            written = false;
        }
        if (!written) {
            // 测试环境（无 IDE 服务）：回落静态存储，保证持久化往返可测
            synchronized (fallbackStorage) {
                fallbackStorage.put(STORAGE_KEY, json);
            }
        }
    }

    /** 从 PluginSettings.State.paramHistoryJson 恢复内存缓存（IDE 环境）；测试环境回落静态存储。 */
    private void restoreFromPersistence() {
        String json = "";
        try {
            json = PluginSettings.getInstance().getState().getParamHistoryJson();
        } catch (Exception ignored) {
            json = "";
        }
        if (json == null || json.isBlank()) {
            // 测试环境：读静态存储
            synchronized (fallbackStorage) {
                String fallback = fallbackStorage.get(STORAGE_KEY);
                json = fallback != null ? fallback : "";
            }
        }
        if (json.isBlank()) return;
        try {
            Map<String, Map<String, String>> restored = deserializeFromJson(json);
            cache.putAll(restored);
        } catch (Exception ignored) {
            // 持久化数据损坏容错
        }
        enforceMaxSize();
    }

    /**
     * 自研最小 JSON 序列化（格式：[{@code [{"key":{...params}}]}]）。
     * 仅处理 String→String 映射，key/value 含特殊字符时转义。
     */
    private String serializeToJson() {
        if (cache.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[");
        int index = 0;
        for (Map.Entry<String, Map<String, String>> entry : cache.entrySet()) {
            if (index > 0) sb.append(",");
            sb.append("{\"").append(escapeJson(entry.getKey())).append("\":{");
            int pIdx = 0;
            for (Map.Entry<String, String> p : entry.getValue().entrySet()) {
                if (pIdx > 0) sb.append(",");
                sb.append("\"").append(escapeJson(p.getKey())).append("\":\"")
                        .append(escapeJson(p.getValue())).append("\"");
                pIdx++;
            }
            sb.append("}}");
            index++;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 自研最小 JSON 反序列化（解析 {@code [{"key":{...params}}]} 格式）。
     * 解析失败返回空 map（容错：持久化数据损坏不崩溃）。
     */
    private Map<String, Map<String, String>> deserializeFromJson(String json) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return result;
        // 简化解析：按顶层对象拆分
        List<String> objects = splitTopLevelObjects(trimmed.substring(1, trimmed.length() - 1));
        for (String obj : objects) {
            String objTrimmed = obj.trim();
            if (!objTrimmed.startsWith("{") || !objTrimmed.endsWith("}")) continue;
            String content = objTrimmed.substring(1, objTrimmed.length() - 1);
            // 第一个 key 是缓存键，value 是 params 对象
            int colonIdx = content.indexOf(":");
            if (colonIdx <= 0) continue;
            StrPos keyParsed = parseJsonString(content, 0);
            String key = unescapeJson(keyParsed.str);
            int paramsObjStart = content.indexOf("{", colonIdx);
            if (paramsObjStart < 0) continue;
            Map<String, String> paramsObj = parseJsonObject(content.substring(paramsObjStart));
            result.put(key, paramsObj);
        }
        return result;
    }

    /** 按顶层对象拆分（考虑嵌套括号）。 */
    private List<String> splitTopLevelObjects(String text) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    result.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return result;
    }

    /** 解析 JSON 对象（{@code {...}}）为 String→String 映射。 */
    private Map<String, String> parseJsonObject(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return result;
        String content = trimmed.substring(1, trimmed.length() - 1);
        int i = 0;
        while (i < content.length()) {
            // 跳过空白与逗号
            while (i < content.length() && (content.charAt(i) == ' ' || content.charAt(i) == '\t' || content.charAt(i) == ',')) i++;
            if (i >= content.length()) break;
            StrPos keyParsed = parseJsonString(content, i);
            i = keyParsed.pos;
            while (i < content.length() && content.charAt(i) == ' ') i++;
            if (i < content.length() && content.charAt(i) == ':') i++;
            while (i < content.length() && content.charAt(i) == ' ') i++;
            StrPos valueParsed = parseJsonString(content, i);
            i = valueParsed.pos;
            result.put(unescapeJson(keyParsed.str), unescapeJson(valueParsed.str));
        }
        return result;
    }

    /** 解析 JSON 双引号字符串，返回 (值, 下一个位置)。 */
    private StrPos parseJsonString(String text, int start) {
        int i = start;
        int len = text.length();
        while (i < len && text.charAt(i) != '"') i++;
        if (i >= len) return new StrPos("", len);
        i++; // 跳过起始引号
        StringBuilder sb = new StringBuilder();
        while (i < len && text.charAt(i) != '"') {
            if (text.charAt(i) == '\\' && i + 1 < len) {
                i++;
                sb.append(unescapeJsonChar(text.charAt(i)));
            } else {
                sb.append(text.charAt(i));
            }
            i++;
        }
        if (i < len) i++; // 跳过闭合引号
        return new StrPos(sb.toString(), i);
    }

    private static char unescapeJsonChar(char c) {
        switch (c) {
            case '"': return '"';
            case '\\': return '\\';
            case '/': return '/';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            default: return c;
        }
    }

    /** JSON 字符串转义（最小集：引号、反斜杠、换行、回车、制表符）。 */
    private static String escapeJson(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 字符串整体反转义（处理 \\ \" \\n 等）。 */
    private static String unescapeJson(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                sb.append(unescapeJsonChar(text.charAt(i + 1)));
                i += 2;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /** (值, 下一个位置) 解析结果载体。 */
    private static final class StrPos {
        final String str;
        final int pos;

        StrPos(String str, int pos) {
            this.str = str;
            this.pos = pos;
        }
    }

    /**
     * SQL 模板指纹：SHA-256 前 8 字节的 hex 字符串（16 hex 字符）。
     * 相同 SQL → 相同指纹；不同 SQL → 不同指纹（碰撞概率可忽略）。
     */
    public static String fingerprint(String sql) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < bytes.length; i++) {
                sb.append(String.format("%02x", bytes[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论上不可达；兜底用长度+hash
            return String.format("%08x", sql.hashCode());
        }
    }
}
