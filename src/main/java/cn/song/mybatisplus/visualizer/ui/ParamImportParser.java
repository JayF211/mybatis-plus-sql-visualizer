package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P-05：参数批量导入解析器——支持 JSON / CSV / key=value 三种格式自动识别。
 *
 * 纯逻辑、无依赖，可独立单测。
 */
public final class ParamImportParser {

    public static final ParamImportParser INSTANCE = new ParamImportParser();

    private ParamImportParser() {
    }

    /** 自动识别格式并解析为参数映射。 */
    @NotNull
    public Map<String, String> parse(@NotNull String text) {
        if (text.isBlank()) return java.util.Collections.emptyMap();
        String trimmedStart = ltrim(text);
        if (trimmedStart.startsWith("{") || trimmedStart.startsWith("[")) return parseJson(text);
        List<String> lines = text.lines().toList();
        if (text.contains("\n") && !lines.isEmpty() && lines.get(0).contains(",")) return parseCsv(text);
        return parseKeyValue(text);
    }

    /** 解析扁平 JSON（仅 String / Number / Boolean 值；嵌套对象 / 数组跳过该 key）。 */
    @NotNull
    public Map<String, String> parseJson(@NotNull String text) {
        Map<String, String> result = new LinkedHashMap<>();
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) return java.util.Collections.emptyMap();
        int i = 1;
        int len = trimmed.length();
        while (i < len) {
            while (i < len && (trimmed.charAt(i) == ' ' || trimmed.charAt(i) == '\t'
                    || trimmed.charAt(i) == '\n' || trimmed.charAt(i) == '\r' || trimmed.charAt(i) == ',')) i++;
            if (i >= len || trimmed.charAt(i) == '}') break;
            if (trimmed.charAt(i) != '"') return result;
            i++;
            int keyStart = i;
            while (i < len && trimmed.charAt(i) != '"') {
                if (trimmed.charAt(i) == '\\' && i + 1 < len) i++;
                i++;
            }
            String key = trimmed.substring(keyStart, i);
            if (i < len) i++;
            while (i < len && (trimmed.charAt(i) == ' ' || trimmed.charAt(i) == '\t')) i++;
            if (i >= len || trimmed.charAt(i) != ':') return result;
            i++;
            while (i < len && (trimmed.charAt(i) == ' ' || trimmed.charAt(i) == '\t')) i++;
            if (i >= len) break;
            if (trimmed.charAt(i) == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < len && trimmed.charAt(i) != '"') {
                    if (trimmed.charAt(i) == '\\' && i + 1 < len) {
                        i++;
                        sb.append(unescape(trimmed.charAt(i)));
                    } else {
                        sb.append(trimmed.charAt(i));
                    }
                    i++;
                }
                if (i < len) i++;
                result.put(key, sb.toString());
            } else if (trimmed.charAt(i) == '{' || trimmed.charAt(i) == '[') {
                i = skipValue(trimmed, i);
            } else {
                int valueStart = i;
                while (i < len && trimmed.charAt(i) != ',' && trimmed.charAt(i) != '}'
                        && trimmed.charAt(i) != '\n' && trimmed.charAt(i) != '\r') i++;
                String raw = trimmed.substring(valueStart, i).trim();
                if (!raw.isEmpty() && !raw.equals("null")) result.put(key, raw);
            }
        }
        return result;
    }

    /** JSON 转义字符还原（最小集）。 */
    private char unescape(char c) {
        switch (c) {
            case '"': return '"';
            case '\\': return '\\';
            case '/': return '/';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'b': return '\b';
            case 'f': return '\f';
            default: return c;
        }
    }

    /** 跳过 JSON 中一个 value（嵌套对象 / 数组），返回跳过后的位置。 */
    private int skipValue(String text, int start) {
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        int i = start;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < len) i++;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i + 1;
                }
            }
            i++;
        }
        return i;
    }

    /** 解析 CSV（首行 header + 第二行值，RFC 4180 最小兼容）。 */
    @NotNull
    public Map<String, String> parseCsv(@NotNull String text) {
        List<String> lines = text.lines().filter(s -> !s.isBlank()).toList();
        if (lines.size() < 2) return java.util.Collections.emptyMap();
        List<String> headers = parseCsvRow(lines.get(0));
        List<String> values = parseCsvRow(lines.get(1));
        if (headers.isEmpty()) return java.util.Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        for (int idx = 0; idx < headers.size(); idx++) {
            String k = headers.get(idx).trim();
            if (!k.isEmpty()) {
                result.put(k, idx < values.size() ? (values.get(idx) != null ? values.get(idx).trim() : "") : "");
            }
        }
        return result;
    }

    /** 解析 CSV 单行（RFC 4180 最小兼容：引号包裹含逗号/引号值，"" 转义为 "）。 */
    private List<String> parseCsvRow(String line) {
        List<String> fields = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int len = line.length();
        while (i < len) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else sb.append(c);
            }
            i++;
        }
        fields.add(sb.toString());
        return fields;
    }

    /** 解析 key=value 格式（行分隔；URL query string 的 `&` 视为分隔符）。 */
    @NotNull
    public Map<String, String> parseKeyValue(@NotNull String text) {
        if (text.isBlank()) return java.util.Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        String[] lines = text.split("[&\n\r]", -1);
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) continue;
            int eq = trimmedLine.indexOf('=');
            if (eq <= 0) continue;
            String key = trimmedLine.substring(0, eq).trim();
            String value = trimmedLine.substring(eq + 1).trim();
            if (!key.isEmpty()) result.put(key, value);
        }
        return result;
    }

    private static String ltrim(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }
}
