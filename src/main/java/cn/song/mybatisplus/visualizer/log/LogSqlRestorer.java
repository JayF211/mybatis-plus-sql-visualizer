package cn.song.mybatisplus.visualizer.log;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 日志参数还原器（T-M31-02）：把 `?` 占位符按 {@link LogSqlParam} 顺序替换为可执行 SQL 值。
 *
 * 引号规则（对齐 MyBatis 参数绑定语义与既有 ParameterReplacer 的引号判定经验）：
 * - 数值/布尔类型标注（Long/Integer/Double/Boolean 等）→ 原样
 * - String/时间/其他类型标注 → 引号包裹 + 内部 `'` 转义为 `''`（标准 SQL 字符串转义）
 * - null 字面量 → `NULL`
 * - 无类型标注（旧版/第三方日志）→ 启发式：数值（含科学计数）与 true/false 原样，其余引号
 *
 * 占位符数量与参数数量不匹配：多出的参数忽略，缺少的参数保留 `?`（面板警示提示）。
 */
public class LogSqlRestorer {

    /** 按序替换 SQL 中 `?` 占位符；无参数时原样返回。 */
    public String restore(String sql, List<LogSqlParam> params) {
        if (params.isEmpty() || sql.isEmpty()) {
            return sql;
        }
        List<Integer> positions = LogSqlRestorerKt.placeholderPositions(sql);
        if (positions.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql.length() + params.size() * 8);
        int prev = 0;
        int paramIdx = 0;
        for (int pos : positions) {
            if (paramIdx >= params.size()) {
                break; // 参数多于占位符：忽略剩余
            }
            sb.append(sql, prev, pos).append(renderParam(params.get(paramIdx)));
            prev = pos + 1;
            paramIdx++;
        }
        sb.append(sql, prev, sql.length());
        return sb.toString();
    }

    /** 单个参数渲染为 SQL 字面量（测试直接断言用）。 */
    public String renderParam(LogSqlParam param) {
        String value = param.value();
        if (value == null) {
            return NULL_SQL; // null 字面量（value/type 均 null）
        }
        String type = param.type();
        if (type == null) {
            return renderUntyped(value);
        }
        return PLAIN_TYPES.contains(type) ? value : quote(value);
    }

    private String renderUntyped(String value) {
        return (NUMERIC_REGEX.matcher(value).matches() || value.equals(TRUE) || value.equals(FALSE))
                ? value : quote(value);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static final String NULL_SQL = "NULL";
    private static final String TRUE = "true";
    private static final String FALSE = "false";

    /**
     * 原样直出（数值/布尔）；其余类型标注一律引号（String/时间/枚举/byte[] 等）。
     *
     * 包含两类类型名：
     * - Java 类型（MyBatis 日志）：Long, Integer, String 等
     * - JDBC 类型（Hibernate 日志）：BIGINT, INTEGER, VARCHAR 等
     */
    public static final Set<String> PLAIN_TYPES = Set.of(
            // Java 类型（MyBatis 日志）
            "Long", "Integer", "Short", "Byte", "BigDecimal", "BigInteger", "Double", "Float", "Number",
            "long", "int", "short", "byte", "double", "float",
            "Boolean", "boolean",
            // JDBC 类型（Hibernate 日志）—— 数值/布尔型原样直出
            "BIGINT", "INTEGER", "SMALLINT", "TINYINT", "FLOAT", "DOUBLE", "NUMERIC", "DECIMAL",
            "BIT", "BOOLEAN",
            "REAL",
            // Java 全限定类型（Hibernate 偶尔输出）
            "java.lang.Long", "java.lang.Integer", "java.lang.Short", "java.lang.Byte",
            "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
            "java.math.BigDecimal", "java.math.BigInteger"
    );

    /** 数值字面量（含小数与科学计数，如 `1.0E10`）。 */
    private static final Pattern NUMERIC_REGEX = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
}
