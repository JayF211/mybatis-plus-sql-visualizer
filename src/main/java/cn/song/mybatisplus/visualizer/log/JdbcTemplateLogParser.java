package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring JdbcTemplate 控制台日志解析器（M47）。
 *
 * 支持的日志形态（DEBUG=SQL 行，TRACE=参数行；两类来自不同类）：
 * - SQL 行（JdbcTemplate，有 SQL 文本）：`Executing prepared SQL statement [SELECT ...]`
 * - SQL 行（字符串重载）：`Executing SQL query [SELECT ...]` / `Executing SQL update [UPDATE ...]`
 * - SQL 行（存储过程）：`Calling stored procedure [{call ...}]`
 * - 参数行（StatementCreatorUtils）：
 *   `Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown`
 * - NamedParameterJdbcTemplate 单行格式：
 *   `sql = SELECT ... WHERE col = :param1 , parameters = {param1=0, param2=abc}`
 *
 * 不匹配的行（MyBatis/Hibernate/纯噪声）自然忽略。
 *
 * 参数按 `column index`（1-based）升序排列后回填 `?`。
 * 类型还原：`value class [java.lang.Integer]` → 去 `java.lang.` 前缀 → `Integer` → 查 {@link LogSqlRestorer#PLAIN_TYPES} 判定引号。
 * NamedParameter 格式：命名参数 `:paramName` 直接替换为 Map 中对应值（启发式判定引号）。
 *
 * maxParseLength 超长截断（防控制台大输出卡顿）。
 */
public class JdbcTemplateLogParser {

    public static final int DEFAULT_MAX_PARSE_LENGTH = 20000;

    /**
     * SQL 行匹配正则：提取 `[SQL]` 文本。组 1 = SQL 文本。
     */
    private static final Pattern SQL_LINE_PATTERN = Pattern.compile(
            "(?:Executing\\s+(?:prepared\\s+SQL\\s+(?:statement|update|query)|SQL\\s+(?:statement|query|update)|SQL\\s+batch\\s+update)|Calling\\s+stored\\s+procedure)\\s*\\[(.*)\\]",
            Pattern.CASE_INSENSITIVE);

    /**
     * 参数行匹配正则。组 1 = column index，组 2 = parameter value，组 3 = value class，组 4 = SQL type。
     */
    private static final Pattern PARAM_LINE_PATTERN = Pattern.compile(
            "Setting\\s+SQL\\s+statement\\s+parameter\\s+value:\\s+column\\s+index\\s+(\\d+),\\s+parameter\\s+value\\s+\\[(.*)],\\s+value\\s+class\\s+\\[(.*?)],\\s+SQL\\s+type\\s+(.*)",
            Pattern.CASE_INSENSITIVE);

    /**
     * NamedParameterJdbcTemplate 单行格式正则。组 1 = SQL 文本，组 2 = 参数 Map 文本。
     */
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile(
            "sql\\s*=\\s*(.*?)\\s*,\\s*parameters\\s*=\\s*\\{([^}]*)\\}",
            Pattern.CASE_INSENSITIVE);

    /**
     * SQL 中命名参数引用模式：`:paramName`（用于收集 params 列表）。
     */
    private static final Pattern NAMED_PARAM_REF = Pattern.compile(":(\\w+)");

    /** 数值字面量（含小数与科学计数）。 */
    private static final Pattern NUMERIC_REGEX = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    private final LogSqlRestorer restorer;
    private boolean enabled;
    private int maxParseLength;

    private final List<LogSqlEntry> entries = new ArrayList<>();
    private String pendingSql = null;
    private final List<IndexedParam> pendingParams = new ArrayList<>();

    public JdbcTemplateLogParser() {
        this(new LogSqlRestorer());
    }

    public JdbcTemplateLogParser(LogSqlRestorer restorer) {
        this(restorer, true, DEFAULT_MAX_PARSE_LENGTH);
    }

    public JdbcTemplateLogParser(LogSqlRestorer restorer, boolean enabled, int maxParseLength) {
        this.restorer = restorer;
        this.enabled = enabled;
        this.maxParseLength = maxParseLength;
    }

    public LogSqlRestorer getRestorer() {
        return restorer;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxParseLength() {
        return maxParseLength;
    }

    public void setMaxParseLength(int maxParseLength) {
        this.maxParseLength = maxParseLength;
    }

    /** 喂入一行控制台输出（任意线程，内部无共享可变状态——解析器实例按 console 隔离）。 */
    public void feed(String line) {
        if (!enabled) {
            return;
        }

        // 分支 1：NamedParameterJdbcTemplate 单行格式（sql = ... , parameters = {...}）
        LogSqlEntry namedEntry = extractNamedParamEntry(line);
        if (namedEntry != null) {
            closePending();
            entries.add(namedEntry);
            return;
        }

        // 分支 2：SQL 行（有 [SQL] 文本）
        String sql = extractSqlFromLine(line);
        if (sql != null) {
            closePending();
            pendingSql = take(sql, maxParseLength);
            pendingParams.clear();
            return;
        }

        // 分支 3：参数行
        IndexedParam param = extractParamFromLine(line);
        if (param != null) {
            if (pendingSql == null) {
                return; // 无 SQL 上下文，忽略
            }
            pendingParams.add(param);
            return;
        }

        // 分支 4：有 pendingSql 后遇到非参数非 SQL 行 → 闭合当前条目
        if (pendingSql != null && !pendingParams.isEmpty()) {
            closePending();
        }
    }

    /** 闭合当前未完成条目（新语句到来 / 面板刷新时调用）。 */
    public LogSqlEntry closePending() {
        String sql = pendingSql;
        if (sql == null) {
            return null;
        }
        pendingSql = null;
        // 按 column index 升序排列（1-based → 0-based 由 restorer 按序处理）
        List<LogSqlParam> sortedParams = pendingParams.stream()
                .sorted(Comparator.comparingInt(IndexedParam::index))
                .map(p -> new LogSqlParam(p.value(), p.mappedType()))
                .toList();
        LogSqlEntry entry = new LogSqlEntry(
                sql,
                restorer.restore(sql, sortedParams),
                sortedParams,
                SqlSource.JDBC);
        entries.add(entry);
        pendingParams.clear();
        return entry;
    }

    /** 取出全部已闭合条目并清空缓冲区（面板刷新用）。 */
    public List<LogSqlEntry> takeEntries() {
        List<LogSqlEntry> result = new ArrayList<>(entries);
        entries.clear();
        return result;
    }

    // ---------- 私有方法 ----------

    /**
     * 尝试从行中提取 NamedParameterJdbcTemplate 格式的完整条目（单行）。
     * 格式：`sql = <SQL> , parameters = {param1=val1, param2=val2}`
     * 命名参数 `:paramName` 直接替换为 Map 中对应值。
     */
    private LogSqlEntry extractNamedParamEntry(String line) {
        Matcher match = NAMED_PARAM_PATTERN.matcher(line);
        if (!match.find()) {
            return null;
        }
        String sql = take(match.group(1).trim(), maxParseLength);
        String paramsText = match.group(2).trim();
        Map<String, String> paramsMap = parseNamedParamsMap(paramsText);
        String restoredSql = replaceNamedParams(sql, paramsMap);
        // 收集 params 列表（按 :paramN 出现顺序）
        List<LogSqlParam> params = new ArrayList<>();
        Matcher refMatch = NAMED_PARAM_REF.matcher(sql);
        while (refMatch.find()) {
            String paramName = refMatch.group(1);
            String value = paramsMap.get(paramName);
            params.add(new LogSqlParam(value, null));
        }
        return new LogSqlEntry(
                sql,
                restoredSql,
                params,
                SqlSource.JDBC);
    }

    /**
     * 解析 NamedParameter 格式的参数 Map 文本。
     * 格式：`param1=0, param2=abc`
     * 值可能含 `=` 或 `,`（如 JSON 字符串），按 `, paramName=` 边界切分。
     */
    private Map<String, String> parseNamedParamsMap(String paramsText) {
        if (paramsText.isEmpty()) {
            return Map.of();
        }
        Map<String, String> params = new HashMap<>();
        // 按 `, key=` 边界切分（lookahead 保留 key）
        String[] entries = paramsText.split("\\s*,\\s*(?=\\w+\\s*=)");
        for (String entry : entries) {
            int eqIdx = entry.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            String key = entry.substring(0, eqIdx).trim();
            String value = entry.substring(eqIdx + 1).trim();
            params.put(key, value);
        }
        return params;
    }

    /**
     * 替换 SQL 中的命名参数为渲染后的字面量。
     * 无类型标注 → 启发式：数值/true/false 原样，其余引号包裹。
     */
    private String replaceNamedParams(String sql, Map<String, String> params) {
        String result = sql;
        for (Map.Entry<String, String> e : params.entrySet()) {
            String rendered = renderUntyped(e.getValue());
            result = result.replace(":" + e.getKey(), rendered);
        }
        return result;
    }

    /**
     * 无类型标注的字面量渲染（启发式）。
     * 数值（含小数/科学计数）/ true/false → 原样；null → NULL；其余 → 引号包裹。
     */
    private String renderUntyped(String value) {
        if (value == null || value.equals("null")) {
            return "NULL";
        }
        if (NUMERIC_REGEX.matcher(value).matches() || value.equals("true") || value.equals("false")) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 尝试从行中提取 SQL 文本（有 `[SQL]` 的语句行）。
     */
    private String extractSqlFromLine(String line) {
        Matcher match = SQL_LINE_PATTERN.matcher(line);
        if (!match.find()) {
            return null;
        }
        return take(match.group(1), maxParseLength);
    }

    /**
     * 尝试从行中提取参数信息。
     * 格式：`Setting SQL statement parameter value: column index N, parameter value [VALUE], value class [CLASS], SQL type TYPE`
     */
    private IndexedParam extractParamFromLine(String line) {
        Matcher match = PARAM_LINE_PATTERN.matcher(line);
        if (!match.find()) {
            return null;
        }
        Integer columnIndex = toIntOrNull(match.group(1));
        if (columnIndex == null) {
            return null;
        }
        String rawValue = match.group(2);
        String className = match.group(3);

        // null 字面量：parameter value [null], value class [null]
        if ("null".equals(rawValue) && "null".equals(className)) {
            return new IndexedParam(columnIndex, null, null);
        }

        String mappedType = mapClassToType(className);
        return new IndexedParam(columnIndex, rawValue, mappedType);
    }

    /**
     * 将 Spring JdbcTemplate 输出的全限定 Java 类名映射到 PLAIN_TYPES 可识别的简名。
     */
    private String mapClassToType(String className) {
        if ("null".equals(className)) {
            return null;
        }
        String t = className;
        if (t.startsWith("java.lang.")) {
            t = t.substring("java.lang.".length());
        } else if (t.startsWith("java.math.")) {
            t = t.substring("java.math.".length());
        } else if (t.startsWith("java.util.")) {
            t = t.substring("java.util.".length());
        } else if (t.startsWith("java.sql.")) {
            t = t.substring("java.sql.".length());
        } else if (t.startsWith("java.time.")) {
            t = t.substring("java.time.".length());
        }
        return t;
    }

    /** 带索引的参数（column index + 值 + 映射后类型名）。 */
    private record IndexedParam(
            int index,
            String value,
            String mappedType
    ) {
    }

    private static String take(String s, int max) {
        return s.substring(0, Math.min(max, s.length()));
    }

    private static Integer toIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
