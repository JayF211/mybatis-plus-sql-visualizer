package cn.song.mybatisplus.visualizer.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置导入/导出序列化（O-5 Phase A）。
 *
 * 将 PluginSettings.State 的子集序列化为 JSON 文件供团队分发；导入时解析 JSON 并恢复状态。
 * 手动 JSON 实现（无 kotlinx.serialization 依赖）——覆盖可覆盖项（方言/参数替换/表名映射/
 * 多租户/数据权限），不含全局项（界面语言/快捷键/更新通道）。
 *
 * 导出面是 State 的子集，故导入按「JSON 出现的键才覆盖、未出现的键保留现值」合并
 * （见 {@link #importFromJson} 的 base 形参）——否则文件未承载的字段会被静默重置。
 *
 * 格式：扁平 JSON 对象（string/boolean/number/array）；版本号 CURRENT_VERSION 用于前向兼容。
 */
public final class SettingsIO {

    private SettingsIO() {
    }

    /** 当前序列化格式版本（前向兼容：导入时版本 > 当前则拒绝）。 */
    public static final int CURRENT_VERSION = 1;

    /** 导入异常（格式错误/版本不兼容）。 */
    public static class ImportException extends RuntimeException {
        public ImportException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /**
     * 将状态序列化为 JSON 字符串（仅可覆盖项 + 日志相关配置）。
     *
     * @param state 要导出的状态
     * @return JSON 字符串（UTF-8，人类可读）
     */
    public static String exportToJson(PluginSettings.State state) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(CURRENT_VERSION).append(",\n");
        sb.append("  \"dialectAuto\": ").append(state.getDialectAuto()).append(",\n");
        sb.append("  \"dialect\": ").append(jsonString(state.getDialect())).append(",\n");
        sb.append("  \"replaceParams\": ").append(state.getReplaceParams()).append(",\n");
        sb.append("  \"formatSql\": ").append(state.getFormatSql()).append(",\n");
        sb.append("  \"verticalPairAnnotation\": ").append(state.getVerticalPairAnnotation()).append(",\n");
        sb.append("  \"autoFoldEnabled\": ").append(state.getAutoFoldEnabled()).append(",\n");
        sb.append("  \"autoFoldFieldThreshold\": ").append(state.getAutoFoldFieldThreshold()).append(",\n");
        sb.append("  \"injectLogicDelete\": ").append(state.getInjectLogicDelete()).append(",\n");
        sb.append("  \"logicDeleteValue\": ").append(jsonString(state.getLogicDeleteValue())).append(",\n");
        sb.append("  \"injectTenantLine\": ").append(state.getInjectTenantLine()).append(",\n");
        sb.append("  \"tenantColumnName\": ").append(jsonString(state.getTenantColumnName())).append(",\n");
        sb.append("  \"tenantColumnDefault\": ").append(jsonString(state.getTenantColumnDefault())).append(",\n");
        sb.append("  \"injectTenantOnInsert\": ").append(state.getInjectTenantOnInsert()).append(",\n");
        sb.append("  \"tablePrefix\": ").append(jsonString(state.getTablePrefix())).append(",\n");
        sb.append("  \"tableSuffix\": ").append(jsonString(state.getTableSuffix())).append(",\n");
        sb.append("  \"tableNameInference\": ").append(jsonString(state.getTableNameInference())).append(",\n");
        sb.append("  \"ignoredFields\": ").append(jsonString(state.getIgnoredFields())).append(",\n");
        sb.append("  \"removeTablePrefix\": ").append(jsonString(state.getRemoveTablePrefix())).append(",\n");
        sb.append("  \"removeTableSuffix\": ").append(jsonString(state.getRemoveTableSuffix())).append(",\n");
        sb.append("  \"dataPermissionHint\": ").append(state.getDataPermissionHint()).append(",\n");
        sb.append("  \"dataPermissionMockCondition\": ").append(jsonString(state.getDataPermissionMockCondition())).append(",\n");
        sb.append("  \"dynamicTableNameRules\": ").append(serializeRules(state.getDynamicTableNameRules())).append("\n");
        sb.append("}");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    /**
     * 从 JSON 字符串解析状态。
     *
     * 合并语义（T-Fix-SettingsScope）：以 base 为底，仅覆盖 JSON 中<b>实际出现</b>的键；
     * JSON 未出现的键保留 base 的现值，未知字段忽略。
     *
     * 为什么必须以现值为底：{@link #exportToJson} 只写 21 个可覆盖项，而 {@link PluginSettings.State}
     * 有 77 个 var 字段。若以新建出厂默认态为底，一次导入会把参数历史（paramHistoryJson）、
     * 增强捕获授权记忆、反馈身份、日志自动保存目录等<b>文件里根本没有的字段</b>静默重置为
     * 出厂默认——用户侧表现为不可见的数据丢失（本文件 KDoc 自称「不含全局项」，却又把
     * autoCheckUpdate/ignoredUpdateVersions 一并重置，自相矛盾）。
     *
     * @param json JSON 字符串
     * @param base 未出现在 JSON 中的键的取值来源（默认出厂默认态，供「只要文件值」的调用方使用）
     * @return 恢复后的 State（不含全局项如 language/shortcut，保持 base 现值）
     * @throws ImportException JSON 格式错误或版本不兼容
     */
    public static PluginSettings.State importFromJson(String json) throws ImportException {
        return importFromJson(json, new PluginSettings.State());
    }

    /** 全参形态（base 缺省出厂默认态，Kotlin 默认参数的显式入口）。 */
    public static PluginSettings.State importFromJson(String json, PluginSettings.State base) throws ImportException {
        Map<String, Object> map = parseJson(json);
        Object verObj = map.get("version");
        if (!(verObj instanceof Number)) {
            throw new ImportException("缺少 version 字段");
        }
        int version = ((Number) verObj).intValue();
        if (version > CURRENT_VERSION) {
            throw new ImportException("配置文件版本不匹配（文件版本 " + version + " > 当前支持版本 " + CURRENT_VERSION + "）");
        }
        // copy() 而非直接改写 base：调用方传入的多为活状态（instance.state.copy()），
        // 就地改写会污染它
        PluginSettings.State state = base.copy();
        // 版本 <= CURRENT_VERSION：逐字段读取，缺失则保留 base 现值
        Boolean dialectAuto = getBoolean(map, "dialectAuto");
        if (dialectAuto != null) state.setDialectAuto(dialectAuto);
        String dialect = getString(map, "dialect");
        if (dialect != null) state.setDialect(dialect);
        Boolean replaceParams = getBoolean(map, "replaceParams");
        if (replaceParams != null) state.setReplaceParams(replaceParams);
        Boolean formatSql = getBoolean(map, "formatSql");
        if (formatSql != null) state.setFormatSql(formatSql);
        Boolean verticalPairAnnotation = getBoolean(map, "verticalPairAnnotation");
        if (verticalPairAnnotation != null) state.setVerticalPairAnnotation(verticalPairAnnotation);
        Boolean autoFoldEnabled = getBoolean(map, "autoFoldEnabled");
        if (autoFoldEnabled != null) state.setAutoFoldEnabled(autoFoldEnabled);
        Integer autoFoldFieldThreshold = getInt(map, "autoFoldFieldThreshold");
        if (autoFoldFieldThreshold != null) state.setAutoFoldFieldThreshold(autoFoldFieldThreshold);
        Boolean injectLogicDelete = getBoolean(map, "injectLogicDelete");
        if (injectLogicDelete != null) state.setInjectLogicDelete(injectLogicDelete);
        String logicDeleteValue = getString(map, "logicDeleteValue");
        if (logicDeleteValue != null) state.setLogicDeleteValue(logicDeleteValue);
        Boolean injectTenantLine = getBoolean(map, "injectTenantLine");
        if (injectTenantLine != null) state.setInjectTenantLine(injectTenantLine);
        String tenantColumnName = getString(map, "tenantColumnName");
        if (tenantColumnName != null) state.setTenantColumnName(tenantColumnName);
        String tenantColumnDefault = getString(map, "tenantColumnDefault");
        if (tenantColumnDefault != null) state.setTenantColumnDefault(tenantColumnDefault);
        Boolean injectTenantOnInsert = getBoolean(map, "injectTenantOnInsert");
        if (injectTenantOnInsert != null) state.setInjectTenantOnInsert(injectTenantOnInsert);
        String tablePrefix = getString(map, "tablePrefix");
        if (tablePrefix != null) state.setTablePrefix(tablePrefix);
        String tableSuffix = getString(map, "tableSuffix");
        if (tableSuffix != null) state.setTableSuffix(tableSuffix);
        String tableNameInference = getString(map, "tableNameInference");
        if (tableNameInference != null) state.setTableNameInference(tableNameInference);
        String ignoredFields = getString(map, "ignoredFields");
        if (ignoredFields != null) state.setIgnoredFields(ignoredFields);
        String removeTablePrefix = getString(map, "removeTablePrefix");
        if (removeTablePrefix != null) state.setRemoveTablePrefix(removeTablePrefix);
        String removeTableSuffix = getString(map, "removeTableSuffix");
        if (removeTableSuffix != null) state.setRemoveTableSuffix(removeTableSuffix);
        Boolean dataPermissionHint = getBoolean(map, "dataPermissionHint");
        if (dataPermissionHint != null) state.setDataPermissionHint(dataPermissionHint);
        String dataPermissionMockCondition = getString(map, "dataPermissionMockCondition");
        if (dataPermissionMockCondition != null) state.setDataPermissionMockCondition(dataPermissionMockCondition);
        List<Object> rules = getArray(map, "dynamicTableNameRules");
        if (rules != null) state.setDynamicTableNameRules(deserializeRules(rules));
        return state;
    }

    // ------------------------------------------------------------------
    // 序列化辅助
    // ------------------------------------------------------------------

    /** JSON 字符串值转义（转义引号/反斜杠/控制字符）。 */
    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 序列化动态表名规则为 JSON 数组。 */
    private static String serializeRules(List<TableNameRule> rules) {
        if (rules.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) sb.append(", ");
            TableNameRule rule = rules.get(i);
            sb.append("{");
            sb.append("\"pattern\": ").append(jsonString(rule.getPattern())).append(", ");
            sb.append("\"replacement\": ").append(jsonString(rule.getReplacement())).append(", ");
            sb.append("\"enabled\": ").append(rule.isEnabled());
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // JSON 解析（轻量递归下降）
    // ------------------------------------------------------------------

    /** 解析 JSON 对象字符串为 Map（仅支持 object/array/string/number/boolean/null）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        JsonParser parser = new JsonParser(json);
        Object result = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new ImportException("JSON 解析错误：末尾有多余字符");
        }
        if (!(result instanceof Map)) {
            throw new ImportException("JSON 顶层应为对象");
        }
        return (Map<String, Object>) result;
    }

    /** 简易 JSON 解析器（递归下降）。 */
    private static final class JsonParser {

        private final String input;
        private int pos = 0;

        JsonParser(String input) {
            this.input = input;
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) throw new ImportException("JSON 意外结束");
            char c = peek();
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { parseNull(); return null; }
            if (Character.isDigit(c) || c == '-') return parseNumber();
            throw new ImportException("JSON 解析错误：位置 " + pos + " 处意外字符 '" + c + "'");
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek() == ',') { pos++; continue; }
                if (peek() == '}') { pos++; return map; }
                throw new ImportException("JSON 解析错误：对象中期望 ',' 或 '}'");
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                if (peek() == ',') { pos++; continue; }
                if (peek() == ']') { pos++; return list; }
                throw new ImportException("JSON 解析错误：数组中期望 ',' 或 ']'");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!atEnd()) {
                char c = peek();
                pos++;
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw new ImportException("JSON 字符串转义不完整");
                    char esc = peek();
                    pos++;
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\u000C'); break;
                        case 'u':
                            if (pos + 4 > input.length()) throw new ImportException("JSON unicode 转义不完整");
                            String hex = input.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            throw new ImportException("JSON 未知转义序列 '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new ImportException("JSON 字符串未闭合");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!atEnd() && Character.isDigit(peek())) pos++;
            boolean isFloat = false;
            if (!atEnd() && peek() == '.') {
                isFloat = true;
                pos++;
                while (!atEnd() && Character.isDigit(peek())) pos++;
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                pos++;
                if (!atEnd() && (peek() == '+' || peek() == '-')) pos++;
                while (!atEnd() && Character.isDigit(peek())) pos++;
            }
            String text = input.substring(start, pos);
            if (isFloat || text.toLowerCase().contains("e")) {
                return Double.parseDouble(text);
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return Double.parseDouble(text);
            }
        }

        private boolean parseBoolean() {
            if (input.startsWith("true", pos)) { pos += 4; return true; }
            if (input.startsWith("false", pos)) { pos += 5; return false; }
            throw new ImportException("JSON 解析错误：位置 " + pos + " 处期望 boolean");
        }

        private void parseNull() {
            if (input.startsWith("null", pos)) { pos += 4; return; }
            throw new ImportException("JSON 解析错误：位置 " + pos + " 处期望 null");
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(peek())) pos++;
        }

        private void expect(char c) {
            if (atEnd() || peek() != c) {
                throw new ImportException("JSON 解析错误：位置 " + pos + " 处期望 '" + c + "'");
            }
            pos++;
        }

        private char peek() {
            return input.charAt(pos);
        }

        boolean atEnd() {
            return pos >= input.length();
        }
    }

    // ------------------------------------------------------------------
    // Map 辅助（类型安全取值）
    // ------------------------------------------------------------------

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static Boolean getBoolean(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    private static Integer getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getArray(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof List ? (List<Object>) v : null;
    }

    /** 解析动态表名规则数组。 */
    private static List<TableNameRule> deserializeRules(List<Object> list) {
        List<TableNameRule> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            if (!(m.get("pattern") instanceof String)) continue;
            if (!(m.get("replacement") instanceof String)) continue;
            String pattern = (String) m.get("pattern");
            String replacement = (String) m.get("replacement");
            Object en = m.get("enabled");
            boolean enabled = en instanceof Boolean ? (Boolean) en : true;
            result.add(new TableNameRule(pattern, replacement, enabled));
        }
        return result;
    }
}
