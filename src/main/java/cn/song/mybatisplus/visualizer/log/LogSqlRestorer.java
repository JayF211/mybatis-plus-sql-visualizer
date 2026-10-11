package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 日志参数还原器（T-M31-02）：把 `?` 占位符按 {@link LogSqlParam} 顺序替换为可执行 SQL 值。
 *
 * 引号规则（对齐 MyBatis 参数绑定语义与既有 ParameterReplacer 的引号判定经验）：
 * - 数值/布尔类型标注（Long/Integer/Double/Boolean 等）→ 原样
 * - <b>日期族类型标注（R3，2026-09-29）→ ANSI 类型字面量</b> {@code DATE '…'} / {@code TIMESTAMP '…'}：
 *   裸引号串在 Oracle 对日期/时间戳列隐式转换恒败（ORA-01861/01843，竞品同款差评形态；
 *   dbx 六引擎缺口矩阵实测见 {@code docs/调研报告/20260929-1500-R3-日期类型缺口矩阵-验证报告.md}），
 *   而 ANSI 类型字面量在全部实测引擎（Oracle 11g/23、达梦 DM8、金仓、MySQL 5.7/8.4、
 *   PostgreSQL 16、openGauss）通用——无需方言分支。值文本不匹配日期形态时回落引号，
 *   包装永不产生非法字面量；{@code LocalTime}/带时区值/无类型标注不包装（边界见 renderDateFamily KDoc）
 * - String/其他类型标注 → 引号包裹 + 内部 `'` 转义为 `''`（标准 SQL 字符串转义）
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
        List<Integer> positions = PlaceholderPositionsKt.placeholderPositions(sql);
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
        String dateFamily = renderDateFamily(value, type);
        if (dateFamily != null) {
            return dateFamily;
        }
        return PLAIN_TYPES.contains(type) ? value : quote(value);
    }

    /**
     * 日期族类型标注 → ANSI 类型字面量（R3 缺口矩阵修复）。按简单名归一匹配
     * （兼容 MyBatis 简单名 {@code Timestamp}、Hibernate JDBC 类型 {@code TIMESTAMP}、全限定名 {@code java.sql.Timestamp}）。
     *
     * <p>刻意不包装（边界登记）：
     * <ul>
     * <li>{@code LocalTime}/{@code Time}：纯时刻比较语义取决于目标列类型（对 TIME 列各库皆可、对 datetime 列
     *   表现分裂），且 Oracle 不支持 ANSI {@code TIME} 字面量——维持引号；</li>
     * <li>{@code OffsetDateTime} 等带时区值：ANSI TIMESTAMP 字面量不收时区后缀，方言级包装需
     *   {@code TO_TIMESTAMP_TZ} 一类机制，超出本件（登记决策点 D3）；</li>
     * <li>无类型标注：启发式无法区分日期串与字符串列的日期值（{@code WHERE code='2025-01-01'}），
     *   误包装会改变字符串列语义（决策点 D2）。</li>
     * </ul>
     */
    private String renderDateFamily(String value, String type) {
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        String simple = type.substring(type.lastIndexOf('.') + 1);
        switch (simple) {
            case "LocalDate":
                return DATE_RE.matcher(v).matches() ? "DATE '" + v + "'" : null;
            case "Date":
            case "DATE":
                if (DATE_RE.matcher(v).matches()) {
                    return "DATE '" + v + "'";
                }
                if (TS_RE.matcher(v).matches()) {
                    return "TIMESTAMP '" + normalizeTs(v) + "'";
                }
                return null;
            case "Timestamp":
            case "LocalDateTime":
            case "TIMESTAMP":
            case "DATETIME":
                return TS_RE.matcher(v).matches() ? "TIMESTAMP '" + normalizeTs(v) + "'" : null;
            default:
                return null;
        }
    }

    /** ISO 'T' 分隔符归一为空格（ANSI 字面量不接受 'T'；LocalDateTime.toString 产出 'T'）；缺秒补 {@code :00}（Oracle 实测缺秒 ORA-01861）。 */
    private String normalizeTs(String v) {
        String out = (v.length() > 10 && v.charAt(10) == 'T')
                ? v.substring(0, 10) + ' ' + v.substring(11) : v;
        if (TS_NO_SEC_RE.matcher(out).matches()) {
            out += ":00";
        }
        return out;
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

    /** 日期字面量形态（`yyyy-MM-dd`，Java 日期 toString 标准补零形态）。 */
    private static final Pattern DATE_RE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** 时间戳字面量形态（`yyyy-MM-dd[ T]HH:mm[:ss][.f…`，秒与小数可选——缺秒由 normalizeTs 归一）。 */
    private static final Pattern TS_RE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2})?(\\.\\d{1,9})?");

    /** 缺秒时间戳（`yyyy-MM-dd HH:mm`）——normalizeTs 补 `:00` 用。 */
    private static final Pattern TS_NO_SEC_RE = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
}
